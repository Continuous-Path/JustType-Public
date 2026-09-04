package org.continuouspath.justtype.navigation.engine

/**
 * Switch-code classification for the active Nav input method. The service captures the
 * configured codes + which subsystem is live; the routing rules live here, pure and testable.
 */
data class SwitchRoles(
	val redCode: Int,
	val greenCode: Int,
	val scanCode: Int,
	val twoSwitchActive: Boolean,
	val scanActive: Boolean,
	val undefinedCode: Int,
) {
	fun roleForKeyCode(keyCode: Int): String? = when (keyCode) {
		redCode -> ROLE_RED
		greenCode -> ROLE_GREEN
		else -> null
	}

	/** True when a scan subsystem would act on [keyCode] (explicit code, or candidates while unconfigured). */
	fun scanMatches(keyCode: Int): Boolean = keyCode == scanCode || (scanCode == undefinedCode && isCandidateScanCode(keyCode))

	/** True if the active subsystem's DOWN handler would act on [keyCode] (mirrors key-event routing). */
	fun recognizes(keyCode: Int): Boolean = when {
		twoSwitchActive -> roleForKeyCode(keyCode) != null
		scanActive -> scanMatches(keyCode)
		else -> false
	}

	/**
	 * True if [code] is bound to a switch role for the active method (so a HAT press should fire).
	 * Unlike [recognizes], scan requires an explicitly configured code — HAT presses never
	 * fall back to the candidate set.
	 */
	fun actuates(code: Int): Boolean = when {
		code == undefinedCode -> false
		twoSwitchActive -> roleForKeyCode(code) != null
		scanActive -> code == scanCode
		else -> false
	}

	companion object {
		const val ROLE_RED = "Red Switch"
		const val ROLE_GREEN = "Green Switch"

		// KeyEvent.KEYCODE_1..3 / KEYCODE_NUMPAD_1..3 — mirrored so the engine stays android-free.
		private val CANDIDATE_SCAN_CODES = setOf(8, 9, 10, 145, 146, 147)

		fun isCandidateScanCode(keyCode: Int): Boolean = keyCode in CANDIDATE_SCAN_CODES
	}
}
