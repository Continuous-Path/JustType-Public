package org.continuouspath.justtype.view

import android.content.Context
import android.util.AttributeSet
import android.widget.GridLayout

class SquareGridLayout
@JvmOverloads
constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : GridLayout(context, attrs, defStyleAttr) {
	// Upper bound on the square side, in px. 0 = unbounded (square side follows width). The IME sets
	// this from the available keyboard height so landscape/tablets don't grow a full-width square
	// taller than the screen. Height still tracks width until width exceeds this cap.
	var maxSquarePx: Int = 0
		set(value) {
			if (field != value) {
				field = value
				requestLayout()
			}
		}

	override fun onMeasure(
		widthMeasureSpec: Int,
		heightMeasureSpec: Int,
	) {
		// Measure normally to get an initial width
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)

		val width = MeasureSpec.getSize(widthMeasureSpec)
		val size = if (maxSquarePx > 0) minOf(width, maxSquarePx) else width
		val squareSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

		// Re-measure enforcing a square (height == width, capped at maxSquarePx)
		super.onMeasure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), squareSpec)
		setMeasuredDimension(size, size)
	}
}
