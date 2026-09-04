package org.continuouspath.justtype.ime

/**
 * Non-IME-host dependencies that [SettingsOverlayCallbacksImpl] needs.
 *
 * The overlay-hidden path is the most consequential: it gates the recreate
 * on [isInputViewInflated] and bookends [setIsRecreatingInputView] / [recreateInputView]
 * with try/finally. This contract was hardened in 3.13.3/3.13.4 and is now
 * documented explicitly via this Deps interface.
 */
@Suppress("TooManyFunctions") // Wraps IME-host accessors needed by the overlay-hidden recreate sequence.
interface SettingsOverlayCallbacksImplDeps {
	val isInputViewInflated: Boolean

	fun executeOnUiThread(block: () -> Unit)

	fun setKeyHistoryVisible(visible: Boolean)

	fun loadAndApplyAllPreferences()
	fun updateDirectionalSelection()
	fun updateTouchScreenSwitch()
	fun applyKeyHistoryVisibility()

	fun setIsRecreatingInputView(value: Boolean)
	fun recreateInputView()

	fun updateSettingsKeyLabels(labels: List<String>)
	fun updateSettingsCenterText(text: String)

	fun debugLog(message: String)
}

/**
 * Named impl of [SettingsOverlayCallbacks], extracted from the inline anonymous
 * object in JustTypeIME.
 *
 * The impl owns the inputViewInflated guards on [onSettingsOverlayShown] /
 * [onSettingsOverlayHidden] and the try/finally bookend around the recreate
 * path. Both are testable now without standing up the IME service.
 */
class SettingsOverlayCallbacksImpl(
	private val deps: SettingsOverlayCallbacksImplDeps,
) : SettingsOverlayCallbacks {

	override fun executeOnUiThread(block: () -> Unit) = deps.executeOnUiThread(block)

	override fun onSettingsOverlayShown() {
		if (deps.isInputViewInflated) deps.setKeyHistoryVisible(false)
	}

	override fun onSettingsOverlayHidden() {
		deps.loadAndApplyAllPreferences()
		deps.updateDirectionalSelection()
		deps.updateTouchScreenSwitch()
		deps.applyKeyHistoryVisibility()
		if (deps.isInputViewInflated) {
			deps.setIsRecreatingInputView(true)
			try {
				deps.recreateInputView()
			} finally {
				deps.setIsRecreatingInputView(false)
			}
		}
	}

	override fun updateKeyLabels(labels: List<String>) = deps.updateSettingsKeyLabels(labels)

	override fun updateCenterText(text: String) = deps.updateSettingsCenterText(text)

	override fun debugLog(message: String) = deps.debugLog(message)
}
