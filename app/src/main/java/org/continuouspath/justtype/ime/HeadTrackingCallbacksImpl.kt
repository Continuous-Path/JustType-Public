package org.continuouspath.justtype.ime

import org.continuouspath.justtype.logic.JTUI

/**
 * Non-IME-host dependencies that [HeadTrackingCallbacksImpl] needs.
 *
 * JTUI access is mediated through [getJtuiOrNull] which returns null unless
 * [IMEState] is [IMEState.Ready], so the impl never touches a half-initialised
 * JTUI. The boolean properties expose IME-host runtime state (view shown,
 * beep-feedback setting) that gates side-effecting behavior.
 */
interface HeadTrackingCallbacksImplDeps {
	val imeState: IMEState
	val isInputViewShown: Boolean
	val isBeepEnabled: Boolean
	val isCorrectionBeepEnabled: Boolean
	val isCorrectionFlashRedEnabled: Boolean
	fun getJtuiOrNull(): JTUI?
	fun playActivationBeep()
	fun playCorrectTone()
	fun playCancelTone()
	fun setHighlight(buttonIndex: Int)
	fun suppressPullInForExit()
	fun onKeyboardReEntry()
	fun onHeadTrackingUnavailable()
	fun onHeadTrackingRecovered()
	fun debugLog(message: String)
}

/**
 * Named impl of [HeadTrackingCallbacks], extracted from the inline anonymous
 * object in JustTypeIME.
 *
 * The impl owns the readiness gates and the multi-condition guard inside
 * [handleExternalButtonInput] so they can be tested without instantiating
 * the IME service.
 */
class HeadTrackingCallbacksImpl(
	private val deps: HeadTrackingCallbacksImplDeps,
) : HeadTrackingCallbacks {

	override val isInputViewShown: Boolean
		get() = deps.isInputViewShown

	override val isJtuiInitialized: Boolean
		get() = deps.imeState is IMEState.Ready

	override val isBeepEnabled: Boolean
		get() = deps.isBeepEnabled

	override val isCorrectionBeepEnabled: Boolean
		get() = deps.isCorrectionBeepEnabled

	override val isCorrectionFlashRedEnabled: Boolean
		get() = deps.isCorrectionFlashRedEnabled

	override fun playActivationBeep() = deps.playActivationBeep()

	override fun playCorrectTone() = deps.playCorrectTone()

	override fun playCancelTone() = deps.playCancelTone()

	override fun buttonPressed(octant: Int, shouldAbort: () -> Boolean) {
		deps.getJtuiOrNull()?.buttonPressed(octant, shouldAbort)
	}

	override fun setWldGeneration(generation: Long) {
		deps.getJtuiOrNull()?.wldGeneration = generation
	}

	override fun handleExternalButtonInput(buttonIndex: Int) {
		val jtui = deps.getJtuiOrNull() ?: return
		if (buttonIndex !in 0..7) return
		jtui.buttonPressed(buttonIndex)
		if (deps.isInputViewShown) deps.setHighlight(buttonIndex)
	}

	override fun forceUpdateUi() {
		deps.getJtuiOrNull()?.forceUpdateUi(false)
	}

	override fun onKeyboardExit() {
		deps.getJtuiOrNull()?.clearPendingKeySequence()
		deps.suppressPullInForExit()
	}

	override fun onKeyboardReEntry() = deps.onKeyboardReEntry()

	override fun onHeadTrackingUnavailable() = deps.onHeadTrackingUnavailable()

	override fun onHeadTrackingRecovered() = deps.onHeadTrackingRecovered()

	override fun debugLog(message: String) = deps.debugLog(message)
}
