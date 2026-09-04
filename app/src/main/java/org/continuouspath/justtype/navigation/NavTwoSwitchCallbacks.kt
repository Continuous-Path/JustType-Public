package org.continuouspath.justtype.navigation

import android.util.Log
import org.continuouspath.justtype.ime.TwoSwitchCallbacks

class NavTwoSwitchCallbacks(
	private val feedback: NavSubsystemFeedback,
) : TwoSwitchCallbacks {

	override fun flashSwitchBar(flashGreen: Boolean, flashRed: Boolean) {
		// no-op (Nav has no switch bar)
	}

	override fun beepSwitchActivation() {
		feedback.activationFeedback()
	}

	override fun beepTouchSwitchActivation() {
		feedback.activationFeedback()
	}

	override fun beepSwitchKeyCombined() {
		feedback.beepCombined()
	}

	override fun stepFeedback(beep: Boolean) {
		feedback.stepActivationFeedback(beep)
	}

	// finalActivationFeedback intentionally not overridden (interface default no-op): the sink's
	// onActivate owns flash + beep + haptic and fires only on a real activation, so a no-op key
	// gets the error cue alone, not error + success together.

	override fun debugLog(message: String) {
		Log.d("NavTwoSwitchCallbacks", message)
	}
}
