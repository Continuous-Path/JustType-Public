package org.continuouspath.justtype.navigation

import android.util.Log
import org.continuouspath.justtype.ime.ScanCallbacks

class NavScanCallbacks(
	private val feedback: NavSubsystemFeedback,
	private val persistAutoLearnedSwitchCode: (Int) -> Unit,
) : ScanCallbacks {

	override fun flashSwitchBar() {
		// no-op (Nav has no switch bar)
	}

	override fun beepSwitchActivation() {
		feedback.activationFeedback()
	}

	override fun autoLearnSwitchCode(keyCode: Int) {
		Log.d("NavScanCallbacks", "Auto-learned scan switch code: $keyCode")
		persistAutoLearnedSwitchCode(keyCode)
	}
}
