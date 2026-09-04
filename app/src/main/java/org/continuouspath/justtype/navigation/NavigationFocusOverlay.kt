package org.continuouspath.justtype.navigation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.View
import android.view.WindowManager

/**
 * Full-screen pass-through overlay that draws a translucent rectangle around
 * the navigation-mode "selected" node. Touches always pass through.
 */
class NavigationFocusOverlay(
	private val context: Context,
) {
	// TYPE_ACCESSIBILITY_OVERLAY windows must be added by the accessibility service's own
	// (UI) context — a window context made from applicationContext can't add that type.
	private val windowContext: Context get() = context

	private var windowManager: WindowManager? = null
	private var view: FocusRingView? = null

	fun show() {
		if (view != null) return
		val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		val ring = FocusRingView(windowContext)
		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT,
		)
		try {
			wm.addView(ring, params)
			windowManager = wm
			view = ring
		} catch (_: Exception) {
			view = null
			windowManager = null
		}
	}

	fun hide() {
		val ring = view
		val wm = windowManager
		if (ring != null && wm != null) {
			try {
				wm.removeView(ring)
			} catch (_: Exception) {
				// already removed
			}
		}
		view = null
		windowManager = null
	}

	fun setBounds(rect: Rect?) {
		view?.updateBounds(rect)
	}

	private class FocusRingView(context: Context) : View(context) {
		private val fillPaint = Paint().apply {
			isAntiAlias = true
			style = Paint.Style.FILL
			color = Color.argb(48, 0, 200, 255)
		}
		private val strokePaint = Paint().apply {
			isAntiAlias = true
			style = Paint.Style.STROKE
			strokeWidth = 6f
			color = Color.argb(220, 0, 200, 255)
		}
		private var bounds: Rect? = null

		fun updateBounds(rect: Rect?) {
			bounds = rect
			invalidate()
		}

		private val locationOnScreen = IntArray(2)

		override fun onDraw(canvas: Canvas) {
			super.onDraw(canvas)
			val r = bounds ?: return
			getLocationOnScreen(locationOnScreen)
			val dx = -locationOnScreen[0]
			val dy = -locationOnScreen[1]
			canvas.save()
			canvas.translate(dx.toFloat(), dy.toFloat())
			canvas.drawRect(r, fillPaint)
			canvas.drawRect(r, strokePaint)
			canvas.restore()
		}
	}
}
