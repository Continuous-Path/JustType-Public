package org.continuouspath.justtype.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class SquareTextView
@JvmOverloads
constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
	override fun onMeasure(
		widthMeasureSpec: Int,
		heightMeasureSpec: Int,
	) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		val size = kotlin.math.min(measuredWidth, measuredHeight)
		setMeasuredDimension(size, size)
	}
}
