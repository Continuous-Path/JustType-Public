package org.continuouspath.justtype.ime

/**
 * Abstraction for joystick highlight rendering.
 *
 * Joystick highlighting uses named drawable resources as full button-background
 * replacements — fundamentally different from [HighlightBridge] (color-based) and
 * [TwoSwitchViewBridge] (tint-based). This bridge keeps it simple: set a drawable
 * resource as a button's background, or restore the original.
 */
interface JoystickViewBridge {
	/** Whether views are initialized and the input view is shown. */
	val isViewReady: Boolean

	/** Number of keyboard buttons. */
	val buttonCount: Int

	/** Set a button's background to a named drawable resource. */
	fun setButtonDrawable(index: Int, drawableResId: Int)

	/** Restore a button's background to its original. */
	fun restoreButtonBackground(index: Int)

	/** Show or hide a green border around the keyboard grid (exit-gesture confirmation). */
	fun showKeyboardBorder(show: Boolean)

	/** Hide the OS mouse pointer over the keyboard while driving mouse-joystick, or restore it. */
	fun setMousePointerHidden(hidden: Boolean)
}
