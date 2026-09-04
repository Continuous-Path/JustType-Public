package org.continuouspath.justtype

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Spike: a 1x1 focusable overlay window that acquires pointer capture for Mouse Joystick.
 *
 * Pointer capture needs the FOCUSED window, which the IME's own window (non-focusable by design)
 * can never be. This overlay takes input focus WITHOUT becoming the IME target: FLAG_ALT_FOCUSABLE_IM
 * excludes it from IME targeting, so the editor behind should keep its keyboard binding. While
 * captured there is no visible cursor and no screen edges - motion arrives as unbounded relative
 * deltas in [Callbacks.onCapturedMotion]. Whether the editor's InputConnection stays LIVE through
 * this is the spike's make-or-break question; [Callbacks.onCaptureGranted] triggers that probe.
 */
class MouseJoystickCaptureOverlay(private val context: Context) {

	interface Callbacks {
		/** Capture granted - run the InputConnection probe and switch input to the captured stream. */
		fun onCaptureGranted()

		/** Capture lost (focus change, window removed) - fall back to the hover pipeline. */
		fun onCaptureLost()

		/** A SOURCE_MOUSE_RELATIVE event: x/y are unbounded relative deltas, buttons included. */
		fun onCapturedMotion(event: MotionEvent)

		/** A finger touched the screen while captured - pause MJ so the touch session flows normally. */
		fun onCapturedTouch()
	}

	private var windowManager: WindowManager? = null
	private var overlayView: View? = null
	private var isShowing = false
	private var callbacks: Callbacks? = null

	// On API 30+, window-manager calls must come from a Context whose declared window-type matches
	// the LayoutParams type (the IME's context is TYPE_INPUT_METHOD). Lazy so Robolectric unit
	// tests (createWindowContext unsupported) don't trip at construction.
	private val windowContext: Context by lazy {
		when {
			// Display-specific overload is API 31+.
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> try {
				val display = context.display ?: context.applicationContext.display
				if (display != null) {
					context.applicationContext.createWindowContext(
						display,
						WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
						null,
					)
				} else {
					context
				}
			} catch (_: Exception) {
				context
			}
			// API 30 only has the default-display overload.
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> try {
				context.applicationContext.createWindowContext(
					WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
					null,
				)
			} catch (_: Exception) {
				context
			}
			else -> context
		}
	}

	fun setCallbacks(callbacks: Callbacks) {
		this.callbacks = callbacks
	}

	val isCaptureActive: Boolean get() = overlayView?.hasPointerCapture() == true

	// Any add failure must degrade to the hover pipeline, never crash the IME (anti-lockout).
	@Suppress("TooGenericExceptionCaught")
	fun show() {
		if (isShowing) return
		val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		windowManager = wm
		overlayView = createView()
		try {
			wm.addView(overlayView, createParams())
			isShowing = true
			Log.d(TAG, "OVERLAY_ADDED awaiting window focus")
		} catch (e: Exception) {
			Log.w(TAG, "OVERLAY_ADD_FAILED ${e.message}")
			overlayView = null
			windowManager = null
		}
	}

	fun hide() {
		if (!isShowing) return
		overlayView?.let { view ->
			try {
				if (view.hasPointerCapture()) view.releasePointerCapture()
				windowManager?.removeView(view)
			} catch (_: Exception) {}
		}
		overlayView = null
		windowManager = null
		isShowing = false
		Log.d(TAG, "OVERLAY_REMOVED")
	}

	private fun createView(): View = object : View(windowContext) {
		// Diagnostic: reveal the exact shape of the first captured events (action, x/y vs relative
		// axes, source) so a device whose stream doesn't match assumptions is visible in logcat.
		private var rawLogged = 0

		override fun onAttachedToWindow() {
			super.onAttachedToWindow()
			requestFocus()
		}

		override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
			super.onWindowFocusChanged(hasWindowFocus)
			Log.d(TAG, "WINDOW_FOCUS=$hasWindowFocus")
			if (hasWindowFocus && !hasPointerCapture()) {
				requestPointerCapture()
				Log.d(TAG, "CAPTURE_REQUESTED hasCapture=${hasPointerCapture()}")
			}
		}

		override fun onPointerCaptureChange(hasCapture: Boolean) {
			super.onPointerCaptureChange(hasCapture)
			Log.d(TAG, if (hasCapture) "CAPTURE_GRANTED" else "CAPTURE_LOST")
			if (hasCapture) callbacks?.onCaptureGranted() else callbacks?.onCaptureLost()
		}

		override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
			// Capture redirects ALL pointer-class devices here - including the TOUCHSCREEN. A finger
			// must pause MJ (release capture so touches flow normally again), never vanish into the
			// void, and its absolute coordinates must never feed the velocity pipeline.
			if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
				if (event.actionMasked == MotionEvent.ACTION_DOWN) {
					Log.d(TAG, "CAP_TOUCH finger down while captured - pausing for direct touch")
					callbacks?.onCapturedTouch()
				}
				return false
			}
			if (rawLogged < RAW_EVENT_LOG_COUNT) {
				rawLogged++
				var sumX = event.x
				var sumY = event.y
				for (i in 0 until event.historySize) {
					sumX += event.getHistoricalX(i)
					sumY += event.getHistoricalY(i)
				}
				Log.d(
					TAG,
					"CAP_EVT action=${event.action} xy=(${event.x},${event.y}) hist=${event.historySize} " +
						"sum=($sumX,$sumY) src=${event.source}",
				)
			}
			callbacks?.onCapturedMotion(event)
			return true
		}
	}.apply {
		isFocusable = true
		isFocusableInTouchMode = true
		importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	private fun createParams(): WindowManager.LayoutParams {
		val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		// Focusable (NO FLAG_NOT_FOCUSABLE) so capture can be granted. FLAG_ALT_FOCUSABLE_IM keeps
		// it out of IME targeting so the editor behind stays the keyboard's client. 1x1 +
		// FLAG_NOT_TOUCH_MODAL routes every touch to the windows beneath (also dodges the
		// Android 12 pass-through block on FLAG_NOT_TOUCHABLE overlays).
		return WindowManager.LayoutParams(
			1,
			1,
			type,
			WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.TOP or Gravity.START
			x = 0
			y = 0
		}
	}

	private companion object {
		const val TAG = "MJ_CAP"
		const val RAW_EVENT_LOG_COUNT = 8
	}
}
