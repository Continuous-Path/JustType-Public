package org.continuouspath.justtype.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Selection-list text view that PINS its vertical scroll position.
 *
 * After every setText, TextView registers an internal pre-draw handler whose
 * bringTextIntoView() scrolls a non-editable view back to the TOP — one frame
 * ahead of (or behind) the selection-list midpoint scroll, so the list flashed
 * unscrolled with the highlight leaping to the viewport bottom before the real
 * position was restored. Timing-based fixes lose that race by design; instead,
 * while [pinnedScrollY] is set every scroll request — internal or external —
 * lands on the pinned value.
 */
class SelectionListView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

	/** Target scrollY enforced against all scroll requests; null = unmanaged. */
	var pinnedScrollY: Int? = null
		set(value) {
			field = value
			if (value != null && scrollY != value) super.scrollTo(scrollX, value)
		}

	override fun scrollTo(x: Int, y: Int) {
		super.scrollTo(x, pinnedScrollY ?: y)
	}
}
