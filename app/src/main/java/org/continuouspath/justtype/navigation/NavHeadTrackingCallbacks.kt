package org.continuouspath.justtype.navigation

import android.util.Log
import org.continuouspath.justtype.ime.HeadTrackingCallbacks

/**
 * Head-tracking callbacks for the navigation overlay. Supplies the shared feedback
 * subset, plus [buttonPressed] to fire an activated octant at the nav input surface.
 * Head tracking's only activation path is [buttonPressed] (unlike scan/two-switch,
 * which use a KeyActivationSink); the remaining IME-only members keep their no-op
 * defaults from [HeadTrackingCallbacks].
 */
class NavHeadTrackingCallbacks(
	private val feedback: NavSubsystemFeedback,
	private val inputSurface: () -> NavInputSurface?,
	private val onExit: () -> Unit,
	private val onUnavailable: () -> Unit,
) : HeadTrackingCallbacks {

	override fun buttonPressed(octant: Int, shouldAbort: () -> Boolean) {
		if (shouldAbort()) return
		inputSurface()?.onButtonPressed(octant)
	}

	// Nav has no text field — UP exits by minimizing the overlay instead of popping out.
	override val popsOutOnExit: Boolean get() = false
	override fun onExitGesture() = onExit()

	// HeadBoard stopped mid-session — recover to a Nav-operable state (the shared watchdog fires this).
	override fun onHeadTrackingUnavailable() = onUnavailable()

	override fun playActivationBeep() = feedback.activationFeedback()
	override fun playCorrectTone() = feedback.beepCombined()
	override fun playCancelTone() = feedback.beepStep()

	override val isInputViewShown: Boolean get() = inputSurface() != null
	override val isJtuiInitialized: Boolean get() = true
	override val isBeepEnabled: Boolean get() = true
	override val isCorrectionBeepEnabled: Boolean get() = true
	override val isCorrectionFlashRedEnabled: Boolean get() = true

	override fun forceUpdateUi() { /* Nav has no JTUI to refresh */ }

	override fun debugLog(message: String) {
		Log.d("NavHeadTracking", message)
	}
}
