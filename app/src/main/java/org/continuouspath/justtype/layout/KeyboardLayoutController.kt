package org.continuouspath.justtype.layout

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.continuouspath.justtype.view.KeyHistoryView

/**
 * Interface for keyboard layout controllers.
 * Each layout type (JT grid, Scan) implements this interface to provide
 * a consistent API for the LayoutManager.
 */
interface KeyboardLayoutController {
	/** The root container view for this layout */
	val rootView: View

	/** List of buttons in this layout (always 8 buttons, indices 0-7) */
	val buttons: List<Button>

	/** The center label view showing current page/state */
	val centerLabel: TextView

	/** The selection list view (or container for scan layout) */
	val selectionListView: View?

	/** The key history view for this layout */
	val keyHistoryView: KeyHistoryView

	/** The key history scroll view container */
	val keyHistoryScrollView: View

	/** The key grid view (for measuring/sizing purposes) */
	val keyGridView: View?

	/** Map to store original button backgrounds for restore after flash */
	val buttonOriginalBackgrounds: MutableMap<Button, Drawable?>

	/**
	 * Set visibility of this layout.
	 * @param visible true to show, false to hide
	 */
	fun setVisible(visible: Boolean)

	/**
	 * Highlight a button at the given index.
	 * @param index Button index (0-7)
	 * @param color Highlight color
	 */
	fun highlightButton(
		index: Int,
		color: Int,
	)

	/**
	 * Clear all button highlights, restoring original backgrounds.
	 */
	fun clearHighlights()

	/**
	 * Flash a button temporarily.
	 * @param index Button index (0-7)
	 * @param color Flash color
	 * @param durationMs Duration in milliseconds
	 * @param onComplete Callback when flash completes
	 */
	fun flashButton(
		index: Int,
		color: Int,
		durationMs: Long,
		onComplete: (() -> Unit)? = null,
	)

	/**
	 * Update key history visibility based on preference.
	 * @param showKeyHistory true to show key history, false to hide
	 */
	fun setKeyHistoryVisible(showKeyHistory: Boolean)

	/**
	 * Configure shrink-to-fit for key history view.
	 * @param enabled true to enable shrink-to-fit
	 */
	fun setKeyHistoryShrinkToFit(enabled: Boolean)

	/**
	 * Toggle the newest-key accent marker on the key history view.
	 */
	fun setKeyHistoryMarkLatest(enabled: Boolean)

	/**
	 * Initialize the controller after view inflation.
	 */
	fun initialize()

	/**
	 * Clean up resources when the layout is being destroyed.
	 */
	fun cleanup()
}
