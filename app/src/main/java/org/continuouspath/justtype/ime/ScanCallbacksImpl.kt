package org.continuouspath.justtype.ime

/**
 * Non-IME-host dependencies that [ScanCallbacksImpl] needs.
 *
 * Both flag-guarded callbacks ([flashSwitchBar], [beepSwitchActivation])
 * read their guard via the corresponding boolean property on this Deps.
 * The guards mirror real IME state: [isInputViewInflated] (introduced in
 * 3.13.4) gates the overlay flash; [isTouchScreenSwitchBeepEnabled] gates
 * the beep on touch-driven scan activation.
 */
interface ScanCallbacksImplDeps {
	val isInputViewInflated: Boolean
	val isTouchScreenSwitchBeepEnabled: Boolean
	fun flashSwitchBar(green: Boolean, red: Boolean)
	fun beepSwitchActivation()
	fun persistAutoLearnedSwitchCode(keyCode: Int)
}

/**
 * Named impl of [ScanCallbacks], extracted from the inline anonymous object
 * in JustTypeIME.
 *
 * Two of three callbacks have flag guards (`inputViewInflated`,
 * `touchScreenSwitchBeepEnabled`); both flags are exactly the kind of state
 * that regresses silently when a future change inverts a condition or
 * removes the check, so they live behind the Deps surface and have direct
 * unit-test coverage here.
 */
class ScanCallbacksImpl(
	private val deps: ScanCallbacksImplDeps,
) : ScanCallbacks {

	override fun flashSwitchBar() {
		if (deps.isInputViewInflated) deps.flashSwitchBar(green = true, red = false)
	}

	override fun beepSwitchActivation() {
		if (deps.isTouchScreenSwitchBeepEnabled) deps.beepSwitchActivation()
	}

	override fun autoLearnSwitchCode(keyCode: Int) = deps.persistAutoLearnedSwitchCode(keyCode)
}
