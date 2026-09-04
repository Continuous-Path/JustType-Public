package org.continuouspath.justtype.navigation

import org.continuouspath.justtype.ime.KeyActivationSink

class NavKeyActivationSink(
	private val provider: () -> NavInputSurface?,
	// Fired with the activated key index after every activation. Two-switch passes a flash;
	// scan/joystick leave it null since they own their own key visuals.
	private val onActivate: ((Int) -> Unit)? = null,
	// True for the two-switch sink: activateKey is its auto-repeat path, so it replays the last
	// repeatable action instead of re-resolving the index on a possibly-changed page. Scan uses
	// activateKey as a primary activation, so it stays false.
	private val autoRepeatReplaysLast: Boolean = false,
) : KeyActivationSink {

	override fun activateKey(index: Int) {
		val surface = provider() ?: return
		// Only flash on a real activation; a no-op key gets the error cue instead (see NavInputSurface).
		val acted = if (autoRepeatReplaysLast) surface.repeatLast() else surface.onButtonPressed(index)
		if (acted) onActivate?.invoke(index)
	}

	override fun activateKeySilent(index: Int) {
		// Only flash on a real activation; a no-op key gets the error cue instead (see NavInputSurface).
		if (provider()?.onButtonPressed(index) == true) onActivate?.invoke(index)
	}

	override fun activateKeyNoBeep(index: Int) {
		// Only flash on a real activation; a no-op key gets the error cue instead (see NavInputSurface).
		if (provider()?.onButtonPressed(index) == true) onActivate?.invoke(index)
	}

	override fun activateSelect() {
		// no-op
	}

	override fun isReady(): Boolean = provider()?.isReady() == true
}
