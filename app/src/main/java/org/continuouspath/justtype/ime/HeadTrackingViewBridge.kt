package org.continuouspath.justtype.ime

import android.graphics.drawable.Drawable

/**
 * Abstraction for head-tracking visual feedback.
 *
 * Head tracking needs button highlights (same drawable-based approach as
 * [JoystickViewBridge]), plus center-label dead-zone highlights, debug overlay
 * foregrounds on both buttons and center label, keyboard border effects, and
 * selection-list pause/border styling.
 *
 * The IME implements this interface alongside [JoystickViewBridge]; the shared
 * signatures (`setButtonDrawable`, `restoreButtonBackground`, etc.) resolve to
 * a single implementation via Kotlin's diamond-inheritance rule.
 */
interface HeadTrackingViewBridge {
	/** Whether views are initialized and the input view is shown. */
	val isViewReady: Boolean

	/** Number of keyboard buttons. */
	val buttonCount: Int

	// ── Button highlights (shared with JoystickViewBridge) ────────────

	/** Set a button's background to a named drawable resource. */
	fun setButtonDrawable(index: Int, drawableResId: Int)

	/** Restore a button's background to its original. */
	fun restoreButtonBackground(index: Int)

	// ── Center label (dead zone) ──────────────────────────────────────

	/** Set the center label's background to a drawable resource. */
	fun setCenterLabelDrawable(drawableResId: Int)

	/** Restore the center label's background to its original. */
	fun restoreCenterLabelBackground()

	// ── Debug overlay (foreground on buttons + center label) ──────────

	fun setButtonForeground(index: Int, drawableResId: Int)
	fun setCenterLabelForeground(drawableResId: Int)
	fun getButtonForeground(index: Int): Drawable?
	fun getCenterLabelForeground(): Drawable?
	fun restoreButtonForeground(index: Int, drawable: Drawable?)
	fun restoreCenterLabelForeground(drawable: Drawable?)

	// ── Keyboard border ───────────────────────────────────────────────

	fun showKeyboardBorder(show: Boolean)

	// ── Selection list styling ────────────────────────────────────────

	fun showSelectionListBorder()
	fun hideSelectionListBorder()
	fun showSelectionListPaused(text: String)
	fun hideSelectionListPaused()
	fun resetSelectionListStyling()
}
