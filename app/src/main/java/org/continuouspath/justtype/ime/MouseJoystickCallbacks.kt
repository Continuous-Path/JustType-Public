package org.continuouspath.justtype.ime

/**
 * Mouse-Joystick-specific callbacks into the IME. Adds engage/exit tones on top
 * of the shared [JoystickCallbacks] contract.
 */
interface MouseJoystickCallbacks : JoystickCallbacks {
	/** Play the engage tone (rising prompt). Subsystem only invokes when enabled + beep ON. */
	fun playCaptureAcquiredTone()

	/** Play the exit tone (descending beep). Subsystem only invokes when enabled + beep ON. */
	fun playCaptureReleasedTone()

	/** Play a countdown tick during the exit dwell. Subsystem only invokes when enabled + beep ON. */
	fun playExitCountdownTick()

	/** Spike: verify the editor's InputConnection is still live while the capture overlay holds focus. */
	fun verifyInputConnectionLive()
}
