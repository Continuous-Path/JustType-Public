package org.continuouspath.justtype.ime

/**
 * Abstraction for triggering key activations from input-method subsystems.
 *
 * All input methods (scan, two-switch, joystick, head-tracking) ultimately
 * need to "press a key" on the keyboard. This interface decouples that action
 * from the full IME service, allowing subsystems to be tested in isolation.
 */
interface KeyActivationSink {
	/**
	 * Activate the key at the given button index (0-7).
	 * This triggers the same logic as a direct touch on that key.
	 */
	fun activateKey(index: Int)

	/**
	 * Activate the select/submit action (center key equivalent).
	 */
	fun activateSelect()

	/**
	 * Activate the key at the given index silently — no flash or beep feedback.
	 * Used when feedback was already provided by the triggering gesture.
	 */
	fun activateKeySilent(index: Int)

	/**
	 * Activate the key at the given index, flashing if flash feedback is
	 * enabled, but suppressing the per-keystroke activation beep. The
	 * caller is expected to play its own audio cue in place of the
	 * suppressed beep (used by Two-Switch Selection's combined-tone path
	 * to avoid back-to-back switch+key beeps on the third switch press).
	 *
	 * Default routes to [activateKey] so non-IME (test) implementations
	 * don't break; the real ViewBridgeCoordinator overrides it.
	 */
	fun activateKeyNoBeep(index: Int) = activateKey(index)

	/**
	 * Whether the IME is ready to accept key activations.
	 * Subsystems should check this before triggering activations.
	 */
	fun isReady(): Boolean
}
