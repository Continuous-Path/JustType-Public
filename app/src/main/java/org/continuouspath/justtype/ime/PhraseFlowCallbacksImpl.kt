package org.continuouspath.justtype.ime

import org.continuouspath.justtype.data.PhraseEntry
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUI
import org.continuouspath.justtype.logic.LayoutMode

/**
 * Non-IME-host dependencies that [PhraseFlowCallbacksImpl] needs.
 *
 * The phrase flow runs after JTUI's heavy init has completed (it is launched
 * from user-driven text events in the IME), so [getJtui] returns whatever
 * the current state has assigned (Constructed or Ready). The Loading-state
 * fall-through defaults (false / NONE / DEFAULT) prevent crashes if a flow
 * fires before init somehow.
 */
interface PhraseFlowCallbacksImplDeps {
	fun getJtui(): JTUI?
	fun autoCommitSelectedPhrase(text: String)
	fun scheduleBackup()
	fun executeOnUiThread(block: () -> Unit)
	fun debugLog(message: String)
}

/**
 * Named impl of [PhraseFlowCallbacks], extracted from the inline anonymous
 * object in JustTypeIME.
 *
 * The impl owns the null-jtui defaults for [getShiftState], [getAutoCapReason],
 * and [getLayoutMode] so the phrase-flow contract stays stable whether or
 * not JTUI is fully constructed.
 */
class PhraseFlowCallbacksImpl(
	private val deps: PhraseFlowCallbacksImplDeps,
) : PhraseFlowCallbacks {

	override fun setPhraseFlowMode(active: Boolean) {
		deps.getJtui()?.setPhraseFlowMode(active)
	}

	override fun startAbbreviationEntry(useAlpha: Boolean, inPhraseFlow: Boolean) {
		deps.getJtui()?.startAbbreviationEntry(useAlpha, inPhraseFlow)
	}

	override fun setCapsLock(active: Boolean) {
		deps.getJtui()?.setCapsLock(active)
	}

	override fun setPhraseAbbrevModeActive(active: Boolean) {
		deps.getJtui()?.setPhraseAbbrevModeActive(active)
	}

	override fun resetJTUI(
		capitalize: Boolean,
		callUpdateUi: Boolean,
		isManualShift: Boolean,
		resetToStartPage: Boolean,
		autoCapReason: AutoCapReason,
	) {
		deps.getJtui()?.resetJTUI(capitalize, callUpdateUi, isManualShift, resetToStartPage, autoCapReason)
	}

	override fun setCurrentPageToStartingPage() {
		deps.getJtui()?.setCurrentPageToStartingPage()
	}

	override fun getShiftState(): Boolean = deps.getJtui()?.getShiftState() ?: false

	override fun getAutoCapReason(): AutoCapReason = deps.getJtui()?.getAutoCapReason() ?: AutoCapReason.NONE

	override fun getLayoutMode(): LayoutMode = deps.getJtui()?.layoutMode ?: LayoutMode.Alphabetical

	override fun addPhraseEntry(entry: PhraseEntry) {
		deps.getJtui()?.addPhraseEntry(entry)
	}

	override fun autoCommitComposingText(text: String) = deps.autoCommitSelectedPhrase(text)

	override fun scheduleBackup() = deps.scheduleBackup()

	override fun debugLog(message: String) = deps.debugLog(message)

	override fun executeOnUiThread(block: () -> Unit) = deps.executeOnUiThread(block)
}
