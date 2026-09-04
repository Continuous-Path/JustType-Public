package org.continuouspath.justtype.layout

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.view.KeyHistoryView
import org.continuouspath.justtype.view.SelectionListView

/**
 * Controller for the JT (JustType) 3x3 grid keyboard layout.
 * Manages the grid of 8 buttons arranged around a center label,
 * plus a selection list on the side.
 */
class JTLayoutController(
	private val root: View,
) : BaseLayoutController() {
	override lateinit var rootView: View
		private set

	override lateinit var buttons: List<Button>
		private set

	override lateinit var centerLabel: TextView
		private set

	override var selectionListView: View? = null
		private set

	override lateinit var keyHistoryView: KeyHistoryView
		private set

	override lateinit var keyHistoryScrollView: View
		private set

	override var keyGridView: View? = null
		private set

	// JT-specific selection list as TextView
	lateinit var selectionListTextView: TextView
		private set

	// Landscape-only wrapper enabling multi-column word lists; null in portrait.
	var columnsContainer: LinearLayout? = null
		private set

	private val extraColumnViews = mutableListOf<TextView>()

	override fun initialize() {
		// Find container views
		rootView = root.findViewById(R.id.jtLayoutContainer)

		// Landscape carries both history containers — a vertical side column beside the
		// grid and the portrait-style top bar; the preference picks which one is live.
		// Portrait has no vertical container, so it always uses the top bar.
		val verticalScrollView = root.findViewById<View?>(R.id.keyHistoryScrollViewVertical)
		val useVertical = verticalScrollView != null &&
			SettingsRepository.getInstance(root.context)
				.getBoolean(Constants.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE, true)
		if (useVertical) {
			keyHistoryScrollView = verticalScrollView!!
			keyHistoryView = root.findViewById(R.id.keyHistoryViewVertical)
		} else {
			keyHistoryScrollView = root.findViewById(R.id.keyHistoryScrollView)
			keyHistoryView = root.findViewById(R.id.keyHistoryView)
		}
		keyHistoryView.setVertical(useVertical)

		// Find key grid
		keyGridView = root.findViewById(R.id.keyGrid)

		// Find center label
		centerLabel = root.findViewById(R.id.centerLabel)
		centerLabel.setTextColor(Color.WHITE)
		centerLabel.gravity = Gravity.CENTER

		// Find selection list
		selectionListTextView = root.findViewById(R.id.selectionList)
		selectionListView = selectionListTextView
		columnsContainer = root.findViewById(R.id.selectionListJtColumns)

		// Find and setup buttons
		buttons =
			listOf(
				root.findViewById(R.id.btn0),
				root.findViewById(R.id.btn1),
				root.findViewById(R.id.btn2),
				root.findViewById(R.id.btn3),
				root.findViewById(R.id.btn4),
				root.findViewById(R.id.btn5),
				root.findViewById(R.id.btn6),
				root.findViewById(R.id.btn7),
			)

		applyButtonStyling()
		storeOriginalBackgrounds()

		// Track the scroll-axis extent for shrink-to-fit
		keyHistoryScrollView.addOnLayoutChangeListener { sv, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
			val newExtent: Int
			val oldExtent: Int
			if (useVertical) {
				newExtent = bottom - top - sv.paddingTop - sv.paddingBottom
				oldExtent = oldBottom - oldTop - sv.paddingTop - sv.paddingBottom
			} else {
				newExtent = right - left - sv.paddingLeft - sv.paddingRight
				oldExtent = oldRight - oldLeft - sv.paddingLeft - sv.paddingRight
			}
			if (newExtent != oldExtent && newExtent > 0) {
				keyHistoryView.setAvailableExtent(newExtent)
			}
		}
	}

	/**
	 * Update the selection list text content.
	 * For JT layout, this is a single column TextView.
	 */
	fun updateSelectionList(text: String) {
		selectionListTextView.text = text
		selectionListTextView.scrollTo(0, 0)
	}

	/**
	 * Get the selection list dimensions for JTUI configuration.
	 * @return Pair of (width, height) in pixels, or null if not laid out
	 */
	fun getSelectionListDimensions(): Pair<Int, Int>? {
		val width = selectionListTextView.width
		val height = selectionListTextView.height
		return if (width > 0 && height > 0) Pair(width, height) else null
	}

	/**
	 * Render overflow buffers as extra columns beside the main list (landscape only —
	 * portrait has no columns container). Buffer 0 stays on [selectionListTextView],
	 * which keeps the scroll-pinning highlight machinery; buffers 1..n get plain
	 * column views that always fit their content.
	 */
	fun updateColumnViews(buffers: List<CharSequence>) {
		val container = columnsContainer ?: return
		val extraNeeded = (buffers.size - 1).coerceAtLeast(0)
		while (extraColumnViews.size > extraNeeded) {
			container.removeView(extraColumnViews.removeAt(extraColumnViews.size - 1))
		}
		while (extraColumnViews.size < extraNeeded) {
			val view = createExtraColumnView()
			extraColumnViews.add(view)
			container.addView(view)
		}
		buffers.drop(1).forEachIndexed { index, buffer ->
			extraColumnViews.getOrNull(index)?.let {
				it.text = buffer
				it.scrollTo(0, 0)
			}
		}
	}

	private fun createExtraColumnView(): TextView {
		val primary = selectionListTextView
		return SelectionListView(primary.context).apply {
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).also { lp ->
				(primary.layoutParams as? LinearLayout.LayoutParams)?.let { lp.setMargins(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
			}
			background = primary.background?.constantState?.newDrawable()
			setTextColor(primary.textColors)
			setTextSize(TypedValue.COMPLEX_UNIT_PX, primary.textSize)
			includeFontPadding = false
			gravity = Gravity.START or Gravity.TOP
			setPadding(primary.paddingLeft, primary.paddingTop, primary.paddingRight, primary.paddingBottom)
		}
	}

	/** Push a new text size (px) to the extra columns; the primary is set by the IME. */
	fun applyColumnTextSize(sizePx: Float) {
		extraColumnViews.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx) }
	}
}
