package org.continuouspath.justtype

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager

/**
 * Transparent WindowManager overlay covering the whole area above the keyboard.
 * When the mouse cursor leaves the keyboard's top edge, hover events are forwarded
 * to [Callbacks] so [MouseJoystickSubsystem] can keep processing joystick input
 * instead of losing the cursor to the app window behind it.
 */
class MouseJoystickBarrierOverlay(private val context: Context) {

	interface Callbacks {
		/** Called on ACTION_HOVER_ENTER or ACTION_HOVER_MOVE within the barrier. */
		fun onBarrierHover(event: MotionEvent)

		/** Called on ACTION_HOVER_EXIT from the barrier. */
		fun onBarrierExited()
	}

	private var windowManager: WindowManager? = null
	private var overlayView: View? = null
	private var isShowing = false
	private var callbacks: Callbacks? = null

	private var keyboardHeightPx: Int = 0

	fun setCallbacks(callbacks: Callbacks) {
		this.callbacks = callbacks
	}

	fun show(keyboardHeightPx: Int) {
		this.keyboardHeightPx = keyboardHeightPx
		if (isShowing) {
			updatePosition()
			return
		}
		val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		windowManager = wm
		overlayView = createView()
		try {
			wm.addView(overlayView, createParams())
			isShowing = true
			Log.d(TAG, "BARRIER_SHOWN height=${catchHeightPx()} y=$keyboardHeightPx (kbd=$keyboardHeightPx screen=${screenHeightPx()})")
		} catch (_: Exception) {
			overlayView = null
			windowManager = null
		}
	}

	fun hide() {
		if (!isShowing) return
		overlayView?.let {
			try {
				windowManager?.removeView(it)
			} catch (_: Exception) {}
		}
		overlayView = null
		windowManager = null
		isShowing = false
	}

	fun updatePosition(keyboardHeightPx: Int = this.keyboardHeightPx) {
		this.keyboardHeightPx = keyboardHeightPx
		if (!isShowing) return
		val wm = windowManager ?: return
		val view = overlayView ?: return
		val params = view.layoutParams as? WindowManager.LayoutParams ?: return
		params.height = catchHeightPx()
		params.y = keyboardHeightPx
		try {
			wm.updateViewLayout(view, params)
		} catch (_: Exception) {}
	}

	// Cover the entire area above the keyboard: from the keyboard's top edge up to the screen top.
	private fun catchHeightPx(): Int = (screenHeightPx() - keyboardHeightPx).coerceAtLeast(1)

	private fun screenHeightPx(): Int = context.resources.displayMetrics.heightPixels

	private fun createView(): View = object : View(context) {
		override fun onHoverEvent(event: MotionEvent): Boolean {
			if (!isShowing) return false
			return when (event.action) {
				MotionEvent.ACTION_HOVER_ENTER,
				MotionEvent.ACTION_HOVER_MOVE,
				-> {
					Log.d(TAG, "BARRIER_HOVER action=${event.action} raw=(${event.rawX},${event.rawY})")
					callbacks?.onBarrierHover(event)
					true
				}
				MotionEvent.ACTION_HOVER_EXIT -> {
					callbacks?.onBarrierExited()
					true
				}
				else -> false
			}
		}

		override fun onTouchEvent(event: MotionEvent): Boolean = false
	}.apply {
		setBackgroundColor(0x00000000)
		importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
		// The barrier only exists during an MJ session, so hide the OS pointer over it too; the icon
		// dies with the window on hide(), so no restore is needed.
		pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
	}

	private fun createParams(): WindowManager.LayoutParams {
		val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		return WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT,
			catchHeightPx(),
			type,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.BOTTOM or Gravity.START
			x = 0
			y = keyboardHeightPx
		}
	}

	private companion object {
		const val TAG = "MJ_ESC"
	}
}
