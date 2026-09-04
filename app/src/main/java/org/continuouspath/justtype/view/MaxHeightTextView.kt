package org.continuouspath.justtype.view

import android.content.Context
import android.util.AttributeSet

class MaxHeightTextView
@JvmOverloads
constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = android.R.attr.textViewStyle,
) : androidx.appcompat.widget.AppCompatTextView(context, attrs, defStyleAttr) {
	var maxAllowedHeightPx: Int? = null

	override fun onMeasure(
		widthMeasureSpec: Int,
		heightMeasureSpec: Int,
	) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		maxAllowedHeightPx?.let { maxH ->
			if (measuredHeight > maxH) {
				setMeasuredDimension(measuredWidth, maxH)
			}
		}
	}
}
