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
 * Full-screen pass-through overlay marking the drop point during a drag: a crosshair at the cursor,
 * with a faint line back to the pickup so the pending drag reads at a glance. Touches pass through.
 */
class DragCursorOverlay(private val context: Context) {
	// TYPE_ACCESSIBILITY_OVERLAY windows must be added by the accessibility service's own
	// (UI) context — a window context made from applicationContext can't add that type.
	private val windowContext: Context get() = context

	private var windowManager: WindowManager? = null
	private var view: CursorView? = null

	fun show() {
		if (view != null) return
		val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		val cursor = CursorView(windowContext)
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
			wm.addView(cursor, params)
			windowManager = wm
			view = cursor
		} catch (_: Exception) {
			view = null
			windowManager = null
		}
	}

	fun hide() {
		val cursor = view
		val wm = windowManager
		if (cursor != null && wm != null) {
			try {
				wm.removeView(cursor)
			} catch (_: Exception) {
				// already removed
			}
		}
		view = null
		windowManager = null
	}

	/** Marker at [cursor], with a lead line from [pickup] (null pickup → crosshair only). */
	fun setCursor(cursor: Rect, pickup: Rect?) {
		view?.update(cursor.centerX(), cursor.centerY(), pickup)
	}

	private class CursorView(context: Context) : View(context) {
		private val markPaint = Paint().apply {
			isAntiAlias = true
			style = Paint.Style.STROKE
			strokeWidth = STROKE_WIDTH_PX
			strokeCap = Paint.Cap.ROUND
			color = CURSOR_COLOR
		}
		private val leadPaint = Paint().apply {
			isAntiAlias = true
			style = Paint.Style.STROKE
			strokeWidth = LEAD_WIDTH_PX
			color = LEAD_COLOR
		}
		private var cx = 0
		private var cy = 0
		private var pickup: Rect? = null
		private val locationOnScreen = IntArray(2)

		fun update(cx: Int, cy: Int, pickup: Rect?) {
			this.cx = cx
			this.cy = cy
			this.pickup = pickup
			invalidate()
		}

		override fun onDraw(canvas: Canvas) {
			super.onDraw(canvas)
			getLocationOnScreen(locationOnScreen)
			canvas.save()
			canvas.translate(-locationOnScreen[0].toFloat(), -locationOnScreen[1].toFloat())
			val x = cx.toFloat()
			val y = cy.toFloat()
			pickup?.let { canvas.drawLine(it.centerX().toFloat(), it.centerY().toFloat(), x, y, leadPaint) }
			canvas.drawCircle(x, y, RADIUS_PX, markPaint)
			canvas.drawLine(x - ARM_PX, y, x + ARM_PX, y, markPaint)
			canvas.drawLine(x, y - ARM_PX, x, y + ARM_PX, markPaint)
			canvas.restore()
		}

		private companion object {
			val CURSOR_COLOR = Color.argb(220, 0, 200, 255) // matches the focus ring
			val LEAD_COLOR = Color.argb(120, 0, 200, 255)
			const val STROKE_WIDTH_PX = 8f
			const val LEAD_WIDTH_PX = 4f
			const val RADIUS_PX = 22f
			const val ARM_PX = 34f
		}
	}
}
