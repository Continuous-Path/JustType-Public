package org.continuouspath.justtype.navigation

import android.util.Log
import org.continuouspath.justtype.ime.JoystickCallbacks

/**
 * Joystick callbacks for the navigation overlay. Key activation flows through the
 * subsystem's [org.continuouspath.justtype.ime.KeyActivationSink], so this only supplies
 * the activation beep + debug logging.
 */
class NavJoystickCallbacks(
	private val feedback: NavSubsystemFeedback,
) : JoystickCallbacks {
	override fun playActivationBeep() = feedback.activationFeedback()

	override fun debugLog(message: String) {
		Log.d("NavJoystick", message)
	}
}
