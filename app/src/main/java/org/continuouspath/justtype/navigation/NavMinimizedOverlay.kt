package org.continuouspath.justtype.navigation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Small floating button shown while the nav keyboard is minimized. Draggable,
 * tap re-opens, and carries a real-time state indicator (input-method glyph +
 * state color) so the user can tell at a glance what the nav kbd is doing.
 *
 * Its own [WindowManager] window — separate from [NavigationOverlayHost] — so it
 * can outlive the grid and generalize to non-head-tracking use later.
 */
class NavMinimizedOverlay(
	private val context: Context,
	private val onTap: () -> Unit,
) {
	/** Visual state of the minimized button. Extend as new methods/states appear. */
	enum class State { ACTIVE, PAUSED, IDLE }

	// TYPE_ACCESSIBILITY_OVERLAY windows must be added by the accessibility service's own
	// (UI) context — a window context made from applicationContext can't add that type.
	private val windowContext: Context get() = context

	private var windowManager: WindowManager? = null
	private var root: FrameLayout? = null
	private var glyph: TextView? = null
	private var params: WindowManager.LayoutParams? = null

	/** Show the button at screen position [atX], [atY] (top-left). */
	fun show(atX: Int, atY: Int, methodGlyph: String, state: State) {
		if (root != null) return
		val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		val size = dpToPx(BUTTON_DP)

		val container = FrameLayout(windowContext)
		val label = TextView(windowContext).apply {
			text = methodGlyph
			setTextColor(Color.WHITE)
			textSize = GLYPH_SP
			gravity = Gravity.CENTER
		}
		container.addView(
			label,
			FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT,
			),
		)

		val lp = WindowManager.LayoutParams(
			size,
			size,
			overlayType(),
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.TOP or Gravity.START
			x = atX.coerceAtLeast(0)
			y = atY.coerceAtLeast(0)
		}

		try {
			wm.addView(container, lp)
		} catch (_: Exception) {
			return // overlay permission missing or window error — fail closed
		}
		windowManager = wm
		root = container
		glyph = label
		params = lp
		attachDrag(container, lp, wm)
		setState(state)
	}

	fun setState(state: State) {
		val root = root ?: return
		val color = when (state) {
			State.ACTIVE -> COLOR_ACTIVE
			State.PAUSED -> COLOR_PAUSED
			State.IDLE -> COLOR_IDLE
		}
		root.background = GradientDrawable().apply {
			shape = GradientDrawable.OVAL
			setColor(color)
			setStroke(dpToPx(STROKE_DP), Color.WHITE)
		}
	}

	fun hide() {
		val view = root
		val wm = windowManager
		if (view != null && wm != null) {
			try {
				wm.removeView(view)
			} catch (_: Exception) {
				// already gone
			}
		}
		root = null
		glyph = null
		windowManager = null
		params = null
	}

	/** Current top-left screen position, or null when not shown. */
	fun position(): Pair<Int, Int>? = params?.let { it.x to it.y }

	private fun attachDrag(view: View, lp: WindowManager.LayoutParams, wm: WindowManager) {
		var startX = 0
		var startY = 0
		var touchRawX = 0f
		var touchRawY = 0f
		var dragged = false
		view.setOnTouchListener { v, event ->
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					startX = lp.x
					startY = lp.y
					touchRawX = event.rawX
					touchRawY = event.rawY
					dragged = false
					true
				}
				MotionEvent.ACTION_MOVE -> {
					val dx = (event.rawX - touchRawX).toInt()
					val dy = (event.rawY - touchRawY).toInt()
					if (!dragged && (kotlin.math.abs(dx) > TOUCH_SLOP_PX || kotlin.math.abs(dy) > TOUCH_SLOP_PX)) {
						dragged = true
					}
					if (dragged) {
						val metrics = context.resources.displayMetrics
						val maxX = (metrics.widthPixels - v.width).coerceAtLeast(0)
						val maxY = (metrics.heightPixels - v.height).coerceAtLeast(0)
						lp.x = (startX + dx).coerceIn(0, maxX)
						lp.y = (startY + dy).coerceIn(0, maxY)
						runCatching { wm.updateViewLayout(view, lp) }
					}
					true
				}
				MotionEvent.ACTION_UP -> {
					if (!dragged) {
						v.performClick()
						onTap()
					}
					true
				}
				else -> false
			}
		}
	}

	private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

	private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_DIP,
		dp.toFloat(),
		context.resources.displayMetrics,
	).toInt()

	private companion object {
		const val BUTTON_DP = 56
		const val STROKE_DP = 2
		const val GLYPH_SP = 22f
		const val TOUCH_SLOP_PX = 16
		val COLOR_ACTIVE = Color.parseColor("#2E7D32") // green — tracking active
		val COLOR_PAUSED = Color.parseColor("#F9A825") // amber — paused
		val COLOR_IDLE = Color.parseColor("#455A64") // slate — minimized/idle
	}
}
