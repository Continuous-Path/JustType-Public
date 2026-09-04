package org.continuouspath.justtype.ime

import android.content.Context
import org.continuouspath.justtype.logic.JTUI
import org.continuouspath.justtype.logic.LayoutMode
import org.continuouspath.justtype.settings.SettingsRepository

/**
 * Non-IME-host dependencies that [PreferenceCoordinatorCallbacksImpl] needs.
 *
 * The Deps surface is intentionally narrow: each method/property represents one
 * piece of IME-host state the impl reaches into. JTUI access is mediated through
 * [getJtuiOrNull] which returns null unless [IMEState] is [IMEState.Ready],
 * so the impl never touches a half-initialised JTUI.
 */
interface PreferenceCoordinatorCallbacksImplDeps {
	val imeState: IMEState
	val isJtuiAvailable: Boolean
	fun getJtuiOrNull(): JTUI?
	fun isInSettingsMode(): Boolean

	fun getExternalSwitchHandler(): ExternalSwitchHandler?
	fun getOverlayCoordinator(): OverlayCoordinator?

	fun applyPreferenceState(state: PreferenceState)

	fun applyKeyHistoryVisibility()
	fun applyKeyHistoryHeight(repo: SettingsRepository)
	fun applyKeyHistoryShrinkToFit()

	fun isButtonsReady(): Boolean
	fun updateButtonClickListeners()

	fun isLayoutContainersReady(): Boolean
	fun setActiveLayout(useScan: Boolean)
	fun useScanLayout(): Boolean

	fun isLayoutManagerReady(): Boolean
	fun recreateInputView()
	fun applyKeyboardSize()

	fun reinitJtuiInBackground()

	/** Re-bind TTS to the active typing language's voice without a full JTUI reinit. */
	fun applyTtsForActiveLanguage()

	fun applyUiVoice()

	fun debugLog(message: String)
	fun executeOnUiThread(block: () -> Unit)
}

/**
 * Named impl of [PreferenceCoordinatorCallbacks], extracted from the inline
 * anonymous object in JustTypeIME.
 *
 * The impl owns the readiness guards (imeState checks + lateinit-equivalent
 * checks via Deps) so they can be tested without instantiating the IME service.
 */
class PreferenceCoordinatorCallbacksImpl(
	private val deps: PreferenceCoordinatorCallbacksImplDeps,
) : PreferenceCoordinatorCallbacks {

	override val isJtuiInitialized: Boolean
		get() = deps.imeState is IMEState.Ready

	override val isInSettingsMode: Boolean
		get() = deps.isJtuiAvailable && deps.isInSettingsMode()

	override fun getExternalSwitchHandler(): ExternalSwitchHandler? = deps.getExternalSwitchHandler()

	override fun getOverlayCoordinator(): OverlayCoordinator? = deps.getOverlayCoordinator()

	override fun setShowNextLetterHints(enabled: Boolean) {
		deps.getJtuiOrNull()?.showNextLetterHints = enabled
	}

	override fun setLayoutMode(mode: LayoutMode) {
		deps.getJtuiOrNull()?.layoutMode = mode
	}

	override fun updateLocaleContext(context: Context) {
		deps.getJtuiOrNull()?.updateLocaleContext(context)
	}

	override fun applyPreferenceState(state: PreferenceState) = deps.applyPreferenceState(state)

	override fun applyKeyHistoryVisibility() = deps.applyKeyHistoryVisibility()
	override fun applyKeyHistoryHeight(repo: SettingsRepository) = deps.applyKeyHistoryHeight(repo)
	override fun applyKeyHistoryShrinkToFit() = deps.applyKeyHistoryShrinkToFit()

	override fun updateButtonClickListeners() {
		if (deps.isButtonsReady()) deps.updateButtonClickListeners()
	}

	override fun setActiveLayout(useScan: Boolean) {
		if (deps.isLayoutContainersReady()) deps.setActiveLayout(useScan)
	}

	override fun useScanLayout(): Boolean = deps.useScanLayout()

	override fun recreateInputView() {
		if (deps.isLayoutManagerReady()) deps.recreateInputView()
	}

	override fun applyKeyboardSize() {
		if (deps.isLayoutManagerReady()) deps.applyKeyboardSize()
	}

	override fun forceUpdateUi() {
		deps.getJtuiOrNull()?.forceUpdateUi()
	}

	override fun reinitJtuiInBackground() {
		if (deps.imeState is IMEState.Ready) deps.reinitJtuiInBackground()
	}

	// TTS is independent of JTUI readiness (the controller is live from onCreate), so this is
	// unguarded — a voice pref change should re-bind the voice whether or not the input view is up.
	override fun applyTtsForActiveLanguage() = deps.applyTtsForActiveLanguage()

	override fun applyUiVoice() = deps.applyUiVoice()

	override fun debugLog(message: String) = deps.debugLog(message)
	override fun executeOnUiThread(block: () -> Unit) = deps.executeOnUiThread(block)
}
