package org.continuouspath.justtype.ime

/**
 * Abstraction over keyboard button highlighting, used by all input-method
 * subsystems (scan, two-switch, joystick, head-tracking) to provide visual
 * feedback without depending on the full [org.continuouspath.justtype.layout.LayoutManager].
 *
 * The IME orchestrator implements this interface and delegates to the active
 * [org.continuouspath.justtype.layout.KeyboardLayoutController].
 */
interface HighlightBridge {
	/**
	 * Highlight a button at the given index.
	 * @param index Button index (0-7)
	 * @param color Highlight color (ARGB)
	 */
	fun highlightButton(index: Int, color: Int)

	/** Clear all button highlights, restoring original backgrounds. */
	fun clearHighlights()

	/**
	 * Flash a button temporarily with the given color.
	 * @param index Button index (0-7)
	 * @param color Flash color (ARGB)
	 * @param durationMs Duration in milliseconds
	 * @param onComplete Optional callback when the flash completes
	 */
	fun flashButton(index: Int, color: Int, durationMs: Long, onComplete: (() -> Unit)? = null)

	/**
	 * Highlight multiple buttons at once, each with its own color.
	 * Clears any prior highlights before applying new ones.
	 * @param highlights map of button index to ARGB color
	 */
	fun highlightButtons(highlights: Map<Int, Int>)

	/**
	 * Restore the original background of a specific button.
	 * @param index Button index (0-7)
	 */
	fun restoreButton(index: Int)
}
