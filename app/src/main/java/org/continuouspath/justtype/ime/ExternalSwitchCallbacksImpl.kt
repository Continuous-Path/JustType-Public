package org.continuouspath.justtype.ime

import org.continuouspath.justtype.logic.JTUI

/**
 * Non-IME-host dependencies that [ExternalSwitchCallbacksImpl] needs.
 *
 * JTUI access is mediated through [getJtuiOrNull] which returns null unless
 * [IMEState] is [IMEState.Ready], so the impl never touches a half-initialised
 * JTUI. Subsystem references are exposed as direct properties because they
 * are constructed before the ExternalSwitchHandler that uses them.
 */
interface ExternalSwitchCallbacksImplDeps {
	val imeState: IMEState
	val isInputViewShown: Boolean
	val isSingleSwitchEnabled: Boolean
	val isTwoSwitchEnabled: Boolean
	val isJoystickMethodActive: Boolean
	val isSwitchInputLoggingEnabled: Boolean
	fun getJtuiOrNull(): JTUI?

	fun launchOnMain(block: () -> Unit)

	fun scanSwitchDown(keyCode: Int)
	fun scanSwitchUp()
	fun twoSwitchDown(role: String)
	fun twoSwitchUp()
	fun setTwoSwitchHeld(held: Boolean)
	fun joystickInput(x: Float, y: Float)

	fun getSwitchCodes(): SwitchCodeConfig

	fun debugLog(message: String)
}

/**
 * Named impl of [ExternalSwitchCallbacks], extracted from the inline anonymous
 * object in JustTypeIME.
 *
 * The impl owns the JTUI readiness gates (Ready-state checks before
 * key-capture and button-pressed routing). Subsystem routing methods
 * delegate directly because subsystem availability is established at
 * IME construction time, before the ExternalSwitchHandler activates.
 */
class ExternalSwitchCallbacksImpl(
	private val deps: ExternalSwitchCallbacksImplDeps,
) : ExternalSwitchCallbacks {

	override val isInputViewShown: Boolean
		get() = deps.isInputViewShown

	override val isJtuiInitialized: Boolean
		get() = deps.imeState is IMEState.Ready

	override val isCapturingKey: Boolean
		get() = deps.getJtuiOrNull()?.isCapturingKey == true

	override fun handleRawKeyCapture(keyCode: Int) {
		deps.getJtuiOrNull()?.handleRawKeyCapture(keyCode)
	}

	override fun buttonPressed(index: Int) {
		val jtui = deps.getJtuiOrNull() ?: return
		deps.launchOnMain { jtui.buttonPressed(index) }
	}

	override fun scanSwitchDown(keyCode: Int) = deps.scanSwitchDown(keyCode)
	override fun scanSwitchUp() = deps.scanSwitchUp()
	override fun twoSwitchDown(role: String) = deps.twoSwitchDown(role)
	override fun twoSwitchUp() = deps.twoSwitchUp()
	override fun setTwoSwitchHeld(held: Boolean) = deps.setTwoSwitchHeld(held)
	override fun joystickInput(x: Float, y: Float) = deps.joystickInput(x, y)

	override fun getSwitchCodes(): SwitchCodeConfig = deps.getSwitchCodes()

	override val isSingleSwitchEnabled: Boolean
		get() = deps.isSingleSwitchEnabled

	override val isTwoSwitchEnabled: Boolean
		get() = deps.isTwoSwitchEnabled

	override val isJoystickMethodActive: Boolean
		get() = deps.isJoystickMethodActive

	override val isSwitchInputLoggingEnabled: Boolean
		get() = deps.isSwitchInputLoggingEnabled

	override fun debugLog(message: String) = deps.debugLog(message)
}
