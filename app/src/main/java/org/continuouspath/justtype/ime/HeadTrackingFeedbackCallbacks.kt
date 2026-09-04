package org.continuouspath.justtype.ime

/**
 * Surface-agnostic head-tracking callbacks: audio feedback, readiness queries,
 * the beep/flash settings, UI refresh, and logging.
 *
 * Both the IME and the navigation overlay supply these. The IME-only operations
 * (key activation, WLD generation, external button input, keyboard exit/re-entry)
 * live in [HeadTrackingCallbacks], which extends this and defaults them to no-op.
 */
interface HeadTrackingFeedbackCallbacks {
	fun playActivationBeep()
	fun playCorrectTone()
	fun playCancelTone()
	val isInputViewShown: Boolean
	val isJtuiInitialized: Boolean
	val isBeepEnabled: Boolean

	/** "Correction Gesture Beep" setting — gates the Correct/Backtrack tone. */
	val isCorrectionBeepEnabled: Boolean

	/** "Correction Gesture - Cancelled Key Flashes Red" setting — gates the pale-red visual on the cancelled key. */
	val isCorrectionFlashRedEnabled: Boolean
	fun forceUpdateUi()
	fun debugLog(message: String)
}
