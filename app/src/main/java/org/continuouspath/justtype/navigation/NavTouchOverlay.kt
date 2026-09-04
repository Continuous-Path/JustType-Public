package org.continuouspath.justtype.navigation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.continuouspath.justtype.GamepadDirectionDetector
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Nav-owned touchable overlay for touch-screen-switch and directional-selection
 * input. Deliberately NOT the IME's [org.continuouspath.justtype.TouchDetectionOverlay]:
 * an [android.accessibilityservice.AccessibilityService] has no `onWindowHidden`
 * lifecycle hook to deactivate a consuming overlay, so this owns three independent,
 * out-of-band teardown paths instead:
 *
 *  1. A separate top-window 'X' dismiss button (its own window, so the capture
 *     view's `return true` can never swallow its touches).
 *  2. A 30s inactivity timeout, reset only on a *detected gesture* (a stuck or
 *     continuous touch still times out).
 *  3. Volume-down ×3 — handled by the service's `onKeyEvent`, calling [forceTearDown].
 *
 * The capture view consumes touches only while [mode] != INACTIVE (mirrors the
 * IME overlay's INACTIVE guard).
 */
class NavTouchOverlay(
	private val context: Context,
	/** Inset capture region from screen edges (preserves system gestures). */
	private val insetSwipeRegion: Boolean,
	/** TSS: (role, MotionEvent action). role is "LeftSwitch"/"RightSwitch". */
	private val onSwitch: (role: String, action: Int) -> Unit,
	/** Directional: detected 8-way direction. */
	private val onDirection: (GamepadDirectionDetector.Direction) -> Unit,
	/** All three failsafes funnel here so the service can tear the overlay down. */
	private val onTearDown: () -> Unit,
	/** Outline the capture region with a thin border so the user sees where touches are read. */
	private val showBorder: Boolean = false,
) {
	// TYPE_ACCESSIBILITY_OVERLAY windows must be added by the accessibility service's own
	// (UI) context — a window context made from applicationContext can't add that type.
	private val windowContext: Context get() = context

	private var windowManager: WindowManager? = null
	private var captureView: View? = null
	private var dismissView: View? = null
	private var isShowing = false
	private var mode: NavTouchMode = NavTouchMode.INACTIVE

	private val mainHandler = Handler(Looper.getMainLooper())
	private val timeoutRunnable = Runnable { forceTearDown() }

	private val minSwipeDistancePx =
		context.resources.displayMetrics.widthPixels * SWIPE_DISTANCE_PERCENT / 100f
	private var touchDownX = 0f
	private var touchDownY = 0f

	/** Show the overlay in [newMode]. No-op for INACTIVE. */
	fun show(newMode: NavTouchMode) {
		if (newMode == NavTouchMode.INACTIVE) return
		mode = newMode
		if (isShowing) return
		val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		val capture = createCaptureView()
		val dismiss = createDismissView()
		try {
			wm.addView(capture, createCaptureParams())
			wm.addView(dismiss, createDismissParams()) // added AFTER capture → on top
			windowManager = wm
			captureView = capture
			dismissView = dismiss
			isShowing = true
			armTimeout()
		} catch (_: Exception) {
			// Best-effort teardown of whatever attached, then bail to null state.
			runCatching { wm.removeView(capture) }
			runCatching { wm.removeView(dismiss) }
			windowManager = null
			captureView = null
			dismissView = null
			isShowing = false
		}
	}

	/** Tear the overlay down and notify the service. Idempotent. */
	fun forceTearDown() {
		mode = NavTouchMode.INACTIVE
		mainHandler.removeCallbacks(timeoutRunnable)
		val wm = windowManager
		dismissView?.let { v -> if (wm != null) runCatching { wm.removeView(v) } }
		captureView?.let { v -> if (wm != null) runCatching { wm.removeView(v) } }
		dismissView = null
		captureView = null
		windowManager = null
		val wasShowing = isShowing
		isShowing = false
		if (wasShowing) onTearDown()
	}

	private fun armTimeout() {
		mainHandler.removeCallbacks(timeoutRunnable)
		mainHandler.postDelayed(timeoutRunnable, INACTIVITY_TIMEOUT_MS)
	}

	/** Reset the inactivity timer — called only on a detected gesture. */
	private fun onGestureDetected() = armTimeout()

	// ── Capture view ──────────────────────────────────────────────────────

	private fun createCaptureView(): View = object : View(windowContext) {
		override fun onTouchEvent(event: MotionEvent): Boolean {
			if (mode == NavTouchMode.INACTIVE || !isShowing) return false
			return when (mode) {
				NavTouchMode.TOUCH_SCREEN_SWITCH -> handleTouchScreenSwitch(event)
				NavTouchMode.DIRECTIONAL_SELECTION -> handleDirectional(event)
				NavTouchMode.INACTIVE -> false
			}
		}
	}.apply {
		background = if (showBorder) {
			GradientDrawable().apply {
				setColor(Color.TRANSPARENT)
				setStroke(dpToPx(BORDER_WIDTH_DP), BORDER_COLOR)
			}
		} else {
			null
		}
	}

	private fun handleTouchScreenSwitch(event: MotionEvent): Boolean {
		val screenWidth = context.resources.displayMetrics.widthPixels
		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				val role = if (event.rawX <= screenWidth / 2f) "LeftSwitch" else "RightSwitch"
				onSwitch(role, MotionEvent.ACTION_DOWN)
				onGestureDetected()
			}
			MotionEvent.ACTION_UP -> {
				val role = if (event.rawX <= screenWidth / 2f) "LeftSwitch" else "RightSwitch"
				onSwitch(role, MotionEvent.ACTION_UP)
				onGestureDetected()
			}
			MotionEvent.ACTION_CANCEL -> {
				// Finger slid off — treat as UP on the down-side for symmetry.
				val role = if (touchDownX <= screenWidth / 2f) "LeftSwitch" else "RightSwitch"
				onSwitch(role, MotionEvent.ACTION_UP)
			}
		}
		return true
	}

	private fun handleDirectional(event: MotionEvent): Boolean {
		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				touchDownX = event.x
				touchDownY = event.y
			}
			MotionEvent.ACTION_UP -> {
				val dx = event.x - touchDownX
				val dy = event.y - touchDownY
				val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
				if (distance >= minSwipeDistancePx) {
					detectDirection(dx, dy)?.let {
						onDirection(it)
						onGestureDetected()
					}
				}
			}
		}
		return true
	}

	/** 8-way direction from a swipe delta. Copied from TouchDetectionOverlay. */
	private fun detectDirection(dx: Float, dy: Float): GamepadDirectionDetector.Direction? {
		val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
		val a = wrapDeg(angleDeg)
		return when {
			inRange(a, 0f, TOL) || inRange(a, 360f - TOL, 360f) -> GamepadDirectionDetector.Direction.RIGHT
			inRange(a, 45f - TOL, 45f + TOL) -> GamepadDirectionDetector.Direction.DOWN_RIGHT
			inRange(a, 90f - TOL, 90f + TOL) -> GamepadDirectionDetector.Direction.DOWN
			inRange(a, 135f - TOL, 135f + TOL) -> GamepadDirectionDetector.Direction.DOWN_LEFT
			inRange(a, 180f - TOL, 180f + TOL) -> GamepadDirectionDetector.Direction.LEFT
			inRange(a, 225f - TOL, 225f + TOL) -> GamepadDirectionDetector.Direction.UP_LEFT
			inRange(a, 270f - TOL, 270f + TOL) -> GamepadDirectionDetector.Direction.UP
			inRange(a, 315f - TOL, 315f + TOL) -> GamepadDirectionDetector.Direction.UP_RIGHT
			else -> null
		}
	}

	private fun wrapDeg(d: Float): Float {
		var x = d % 360f
		if (x < 0) x += 360f
		return x
	}

	private fun inRange(angle: Float, start: Float, end: Float): Boolean = if (start <= end) angle in start..end else angle >= start || angle <= end

	private fun createCaptureParams(): WindowManager.LayoutParams {
		val type = overlayType()
		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.MATCH_PARENT,
			type,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
				WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
			PixelFormat.TRANSLUCENT,
		)
		params.gravity = Gravity.TOP or Gravity.START
		if (insetSwipeRegion) {
			// Inset by a margin so system back-gesture + notification pull survive.
			val inset = dpToPx(EDGE_INSET_DP)
			params.x = inset
			params.y = inset
			params.width = context.resources.displayMetrics.widthPixels - inset * 2
			params.height = context.resources.displayMetrics.heightPixels - inset * 2
		}
		return params
	}

	// ── Dismiss button (separate top window) ────────────────────────────────

	private fun createDismissView(): View = TextView(windowContext).apply {
		text = DISMISS_GLYPH
		setTextColor(Color.WHITE)
		setTextSize(TypedValue.COMPLEX_UNIT_SP, DISMISS_GLYPH_SP)
		gravity = Gravity.CENTER
		val size = dpToPx(DISMISS_SIZE_DP)
		minWidth = size
		minHeight = size
		background = GradientDrawable().apply {
			shape = GradientDrawable.OVAL
			setColor(DISMISS_BG_COLOR)
		}
		setOnClickListener { forceTearDown() }
	}

	private fun createDismissParams(): WindowManager.LayoutParams {
		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			overlayType(),
			// Focusable=false but touchable (no NOT_TOUCHABLE) so the click lands.
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		)
		params.gravity = Gravity.TOP or Gravity.END
		val margin = dpToPx(DISMISS_MARGIN_DP)
		params.x = margin
		params.y = margin
		return params
	}

	private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

	private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_DIP,
		dp.toFloat(),
		context.resources.displayMetrics,
	).toInt()

	companion object {
		private const val INACTIVITY_TIMEOUT_MS = 30_000L
		private const val SWIPE_DISTANCE_PERCENT = 5
		private const val TOL = 22.5f
		private const val EDGE_INSET_DP = 48

		private const val DISMISS_GLYPH = "✕"
		private const val DISMISS_GLYPH_SP = 22f
		private const val DISMISS_SIZE_DP = 48
		private const val DISMISS_MARGIN_DP = 8
		private val DISMISS_BG_COLOR = Color.argb(0xCC, 0x00, 0x00, 0x00)

		private const val BORDER_WIDTH_DP = 2
		private val BORDER_COLOR = Color.argb(0x99, 0xFF, 0xFF, 0xFF) // slightly transparent white
	}
}
