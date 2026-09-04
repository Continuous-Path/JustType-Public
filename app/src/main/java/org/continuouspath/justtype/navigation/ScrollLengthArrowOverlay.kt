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
 * Full-screen pass-through overlay showing a step-size magnitude (scroll reach or drag step):
 * a horizontal double-headed arrow as long as the next press's span (with an optional label at
 * the floor), drawn above the Nav grid — or below it when the grid hugs the screen top.
 */
class ScrollLengthArrowOverlay(private val context: Context) {
	// TYPE_ACCESSIBILITY_OVERLAY windows must be added by the accessibility service's own
	// (UI) context — a window context made from applicationContext can't add that type.
	private val windowContext: Context get() = context

	private companion object {
		const val FADE_OUT_MS = 300L
	}

	private var windowManager: WindowManager? = null
	private var view: LengthArrowView? = null

	fun show() {
		if (view != null) return
		val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		val arrow = LengthArrowView(windowContext)
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
			wm.addView(arrow, params)
			windowManager = wm
			view = arrow
		} catch (_: Exception) {
			view = null
			windowManager = null
		}
	}

	/** Fade the arrow out over [FADE_OUT_MS], then remove the window. Removes immediately if there's no view. */
	fun fadeOut(onDone: () -> Unit) {
		val arrow = view ?: run {
			onDone()
			return
		}
		arrow.animate().cancel()
		arrow.animate()
			.alpha(0f)
			.setDuration(FADE_OUT_MS)
			.withEndAction {
				hide()
				onDone()
			}
			.start()
	}

	fun hide() {
		val arrow = view
		val wm = windowManager
		if (arrow != null && wm != null) {
			arrow.animate().cancel()
			try {
				wm.removeView(arrow)
			} catch (_: Exception) {
				// already removed
			}
		}
		view = null
		windowManager = null
	}

	/** Arrow of [spanPx] beside [gridRect] (null grid → screen center); [label] replaces the span text when set. */
	fun setArrow(spanPx: Int, gridRect: Rect?, screen: Rect, label: String?) {
		view?.apply {
			// A re-show mid-fade snaps back to fully visible.
			animate().cancel()
			alpha = 1f
			update(spanPx, gridRect, screen, label)
		}
	}

	private class LengthArrowView(context: Context) : View(context) {
		private val linePaint = Paint().apply {
			isAntiAlias = true
			style = Paint.Style.STROKE
			strokeWidth = STROKE_WIDTH_PX
			strokeCap = Paint.Cap.ROUND
			color = ARROW_COLOR
		}
		private val labelPaint = Paint().apply {
			isAntiAlias = true
			textAlign = Paint.Align.CENTER
			isFakeBoldText = true
			textSize = LABEL_SP * context.resources.displayMetrics.scaledDensity
			color = ARROW_COLOR
		}
		private var spanPx = 0
		private var gridRect: Rect? = null
		private var screen = Rect()
		private var label: String? = null
		private val locationOnScreen = IntArray(2)

		fun update(spanPx: Int, gridRect: Rect?, screen: Rect, label: String?) {
			this.spanPx = spanPx
			this.gridRect = gridRect
			this.screen = screen
			this.label = label
			invalidate()
		}

		override fun onDraw(canvas: Canvas) {
			super.onDraw(canvas)
			if (spanPx <= 0 && label == null) return
			val grid = gridRect
			val gap = GAP_PX
			val cx = (grid?.centerX() ?: screen.centerX()).toFloat()
			// Above the grid when there's headroom, else below; no grid → screen center.
			val y = when {
				grid == null -> screen.centerY().toFloat()
				grid.top - gap - HEAD_PX * 2 > screen.top -> (grid.top - gap).toFloat()
				else -> (grid.bottom + gap).toFloat()
			}
			getLocationOnScreen(locationOnScreen)
			canvas.save()
			canvas.translate(-locationOnScreen[0].toFloat(), -locationOnScreen[1].toFloat())
			val half = spanPx / 2f
			val left = (cx - half).coerceAtLeast(screen.left + EDGE_PX)
			val right = (left + spanPx).coerceAtMost(screen.right - EDGE_PX)
			canvas.drawLine(left, y, right, y, linePaint)
			// Double-headed: magnitude, not direction.
			canvas.drawLine(left, y, left + HEAD_PX, y - HEAD_PX, linePaint)
			canvas.drawLine(left, y, left + HEAD_PX, y + HEAD_PX, linePaint)
			canvas.drawLine(right, y, right - HEAD_PX, y - HEAD_PX, linePaint)
			canvas.drawLine(right, y, right - HEAD_PX, y + HEAD_PX, linePaint)
			label?.let { canvas.drawText(it, cx, y - HEAD_PX - LABEL_GAP_PX, labelPaint) }
			canvas.restore()
		}

		private companion object {
			val ARROW_COLOR = Color.argb(220, 0, 200, 255) // matches the focus ring
			const val STROKE_WIDTH_PX = 8f
			const val HEAD_PX = 24f
			const val GAP_PX = 36
			const val EDGE_PX = 16f
			const val LABEL_GAP_PX = 12f
			const val LABEL_SP = 16f
		}
	}
}
