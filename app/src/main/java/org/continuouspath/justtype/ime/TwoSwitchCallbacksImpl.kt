package org.continuouspath.justtype.ime

/**
 * Non-IME-host dependencies that [TwoSwitchCallbacksImpl] needs.
 *
 * Two of four callbacks are flag-guarded: [flashSwitchBar]'s
 * [isInputViewInflated] gate and [beepTouchSwitchActivation]'s
 * [isTouchScreenSwitchBeepEnabled] gate. Both flags are exactly the kind
 * of state that regresses silently when a future change inverts a
 * condition or removes the check.
 */
interface TwoSwitchCallbacksImplDeps {
	val isInputViewInflated: Boolean
	val isTouchScreenSwitchBeepEnabled: Boolean
	fun flashSwitchBar(flashGreen: Boolean, flashRed: Boolean)
	fun beepSwitchActivation()
	fun beepSwitchKeyCombined()
	fun stepFeedback(beep: Boolean)
	fun finalActivationFeedback(index: Int)
	fun debugLog(message: String)
}

/**
 * Named impl of [TwoSwitchCallbacks], extracted from the inline anonymous
 * object in JustTypeIME.
 *
 * The flash/beep guards are the testable surface; the unconditional
 * external-switch beep and debug log forward through the Deps surface.
 */
class TwoSwitchCallbacksImpl(
	private val deps: TwoSwitchCallbacksImplDeps,
) : TwoSwitchCallbacks {

	override fun flashSwitchBar(flashGreen: Boolean, flashRed: Boolean) {
		if (deps.isInputViewInflated) deps.flashSwitchBar(flashGreen, flashRed)
	}

	override fun beepSwitchActivation() = deps.beepSwitchActivation()

	override fun beepTouchSwitchActivation() {
		if (deps.isTouchScreenSwitchBeepEnabled) deps.beepSwitchActivation()
	}

	override fun beepSwitchKeyCombined() = deps.beepSwitchKeyCombined()

	override fun stepFeedback(beep: Boolean) = deps.stepFeedback(beep)

	override fun finalActivationFeedback(index: Int) = deps.finalActivationFeedback(index)

	override fun debugLog(message: String) = deps.debugLog(message)
}
