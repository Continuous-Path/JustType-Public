package org.continuouspath.justtype.ime

import android.graphics.drawable.Drawable

/**
 * Abstraction over keyboard button visuals for two-switch input.
 *
 * Two-switch highlighting is fundamentally different from scan: it tints ALL 8
 * buttons (some red, some green, some restored), applies foreground strips, and
 * has a "disable highlight" mode where backgrounds are restored but strips persist.
 *
 * The IME orchestrator implements this interface and delegates to the active
 * button array and original-background map.
 */
interface TwoSwitchViewBridge {
	/** Number of keyboard buttons. */
	val buttonCount: Int

	/** Whether views are initialized and ready. */
	val isViewReady: Boolean

	/** Tint a button's background by cloning the original and applying a color. */
	fun tintButton(index: Int, color: Int)

	/** Flash a button briefly, then settle back to its standing background. */
	fun flashButton(index: Int, color: Int, durationMs: Long, onComplete: (() -> Unit)?)

	/** Restore a single button's background to original (does NOT clear foreground). */
	fun restoreButtonBackground(index: Int)

	/** Restore ALL button backgrounds to originals (does NOT clear foregrounds). */
	fun restoreAllBackgrounds()

	/** Set a Drawable as the button's foreground (for color-code strips). */
	fun setButtonForeground(index: Int, drawable: Drawable)

	/** Clear the foreground of a single button. */
	fun clearButtonForeground(index: Int)

	/** Clear all button foregrounds. */
	fun clearAllForegrounds()
}
