package org.continuouspath.justtype.input

import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED

/**
 * Edge-detects switch presses from a d-pad HAT axis. HAT motion streams while held and has no
 * key-up, so this turns the stream into one [onDown] per press and one [onUp] per release.
 *
 * Only directions that actuate a switch are tracked (the caller's [actuates] predicate decides):
 * a press of an unbound direction is ignored, so it can never leave a phantom "held" that fires a
 * stray release — which would otherwise restart an active scan cycle.
 */
class HatSwitchEdgeDetector {

	private var heldCode = SWITCH_CODE_UNDEFINED

	/**
	 * Feed the HAT-derived code for one motion event ([SWITCH_CODE_UNDEFINED] = centered/none).
	 * Fires [onUp] (with the released code) for the previously-held direction and/or [onDown]
	 * for a newly-pressed one.
	 */
	fun onHatCode(
		code: Int,
		actuates: (Int) -> Boolean,
		onDown: (Int) -> Unit,
		onUp: (Int) -> Unit,
	) {
		val pressed = if (code != SWITCH_CODE_UNDEFINED && actuates(code)) code else SWITCH_CODE_UNDEFINED
		if (pressed == heldCode) return // unchanged (still held, or still none)
		if (heldCode != SWITCH_CODE_UNDEFINED) onUp(heldCode)
		heldCode = pressed
		if (pressed != SWITCH_CODE_UNDEFINED) onDown(pressed)
	}

	/** Drop any held state (on teardown), without firing an up. */
	fun clear() {
		heldCode = SWITCH_CODE_UNDEFINED
	}

	/**
	 * Drop the held state only if it is [code] (after an auto-release), without firing an up —
	 * so the eventual real release can't fire a second up, and a continued hold re-downs.
	 */
	fun clearIfHeld(code: Int) {
		if (heldCode == code) heldCode = SWITCH_CODE_UNDEFINED
	}
}
