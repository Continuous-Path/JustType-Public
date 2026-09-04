package org.continuouspath.justtype.ime

import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUI

/**
 * Non-JTUI dependencies that [ImeTextCallbacksImpl] needs from the IME host.
 */
interface ImeTextCallbacksImplDeps {
	val isInputViewShown: Boolean
	fun isAlphaChar(c: Char): Boolean
	var isEditMode: Boolean
	var isNewInputSession: Boolean
	val phraseFlowHandle: PhraseFlowHandle?
	fun sendDpadEvent(direction: Int, movementMode: Int)
	fun speakQueued(text: String)
	fun reuseOrCreatePendingSelection(text: String, type: String): TtsController.PendingSelection
	fun speakIfEnabled(pending: TtsController.PendingSelection?): Boolean
	fun cancelScheduledSpeak(clearPending: Boolean)
	fun rememberLastSpoken(text: String, type: String)
	fun getPendingSelection(): TtsController.PendingSelection?
	fun setPendingSelection(pending: TtsController.PendingSelection?)
	fun recordSpellNumeric(text: String)
	fun flushSpellNumericIfNeeded(reason: String)
	fun getAutoRestore(): Boolean
	fun errorNotification()
	fun errorBeep(force: Boolean)
	fun debugLog(message: String)
}

/**
 * Named implementation of [ImeTextCallbacks], extracted from the anonymous
 * object that was previously inline in JustTypeIME.
 *
 * JTUI calls are guarded: [getJtui] returns null when JTUI is not yet
 * initialised, and each override falls back to a safe default.
 */
class ImeTextCallbacksImpl(
	private val getJtui: () -> JTUI?,
	private val isJtuiReady: () -> Boolean,
	private val deps: ImeTextCallbacksImplDeps,
) : ImeTextCallbacks {

	// ── JTUI state queries ──────────────────────────────────────────────

	override val isJtuiInitialized: Boolean get() = isJtuiReady()
	override val isInputViewShown: Boolean get() = deps.isInputViewShown
	override fun getAmbiguousSequenceLength(): Int = getJtui()?.getAmbiguousSequenceLength() ?: 0
	override fun getShiftState(): Boolean = getJtui()?.getShiftState() ?: false
	override fun getIsManualShift(): Boolean = getJtui()?.getIsManualShift() ?: false
	override fun getAutoCapReason(): AutoCapReason = getJtui()?.getAutoCapReason() ?: AutoCapReason.NONE
	override fun isSpellingMode(): Boolean = getJtui()?.getIsSpellingMode() ?: false
	override fun isNumericMode(): Boolean = getJtui()?.isInNumericMode() ?: false
	override fun getSpeakState(): Boolean = getJtui()?.getSpeakState() ?: false
	override fun selectedCandidateSuppressesLeadingSpace(): Boolean = getJtui()?.selectedCandidateSuppressesLeadingSpace() ?: false

	// ── JTUI commands ───────────────────────────────────────────────────

	override fun setShiftState(shift: Boolean, isManual: Boolean, skipUpdate: Boolean, autoReason: AutoCapReason) {
		getJtui()?.setShiftState(shift, isManual, skipUpdate, autoReason)
	}
	override fun setSpeakState(enabled: Boolean, announce: Boolean) {
		getJtui()?.setSpeakState(enabled, announce)
	}
	override fun resetJTUI(shiftState: Boolean, callUpdate: Boolean, isManualShift: Boolean, resetToStartPage: Boolean, autoCapReason: AutoCapReason, preserveAutospace: Boolean) {
		getJtui()?.resetJTUI(shiftState, callUpdate, isManualShift = isManualShift, resetToStartPage = resetToStartPage, autoCapReason = autoCapReason, preserveAutospace = preserveAutospace)
	}
	override fun forceUpdateUi() {
		getJtui()?.forceUpdateUi(false)
	}
	override fun withUiSuppressed(block: () -> Unit) {
		getJtui()?.withUiSuppressed(block) ?: block()
	}
	override fun setSkipPostProcessingAfterPullIn(skip: Boolean) {
		getJtui()?.let { it.skipPostProcessingAfterPullIn = skip }
	}
	override fun getSelectKeyCount(): Int = getJtui()?.getSelectKeyCount() ?: 0
	override fun isWordProducible(word: String): Boolean = getJtui()?.isWordProducible(word) ?: false
	override fun getCurrentPage(): String = getJtui()?.let {
		try {
			it.getCurrentPage()
		} catch (_: Exception) {
			""
		}
	} ?: ""
	override fun clearManualShift() {
		getJtui()?.clearManualShift()
	}
	override fun hasPendingAmbiguityWithoutSelect(): Boolean = getJtui()?.let {
		try {
			it.hasPendingAmbiguityWithoutSelect()
		} catch (_: Exception) {
			false
		}
	} ?: false
	override fun getImmedCharCount(): Int = getJtui()?.getImmedCharCount() ?: 0
	override fun clearImmedCharCount() {
		getJtui()?.clearImmedCharCount()
	}

	override fun replayWordInJtui(word: String, capitalize: Boolean, isAllUpper: Boolean, selectFirstOnMatch: Boolean, suppressUIUpdate: Boolean, precedingText: String?): Boolean {
		val jtui = getJtui() ?: return false
		var forceSelectedCandidate = false
		jtui.withUiSuppressed {
			jtui.setCurrentPageToStartingPage()
			jtui.resetJTUI(capitalize, false, autoCapReason = AutoCapReason.NONE)
			// C2: restore context BEFORE the replay — targetIndex below is found
			// on the real list, so the prediction block shifts it correctly.
			jtui.ngbReconstructContext(precedingText)
			if (isAllUpper) jtui.setCapsLock(true)
			val keys = jtui.mapWordToKeyIndices(word)
			if (keys != null) {
				keys.forEach { k -> jtui.pressAmbiguousKeyNumberSilently(k, true) }
				// C3: with a span pending, activation owns the selection — the
				// replay leaves the list unstepped.
				if (!jtui.ngbSpanPending()) {
					// Page-group rule (Cliff, 2026-08-14): a page-buried word is
					// relocated to list slot 2 first, so the Select presses
					// below actually reach it (stepping past the list rows
					// would open pages, never select the word).
					val targetIndex = jtui.pullInTargetIndex(word)
					val shouldSelectFirst = selectFirstOnMatch && targetIndex == 0
					if (targetIndex > 0 || shouldSelectFirst) {
						val presses = (targetIndex + 1)
						var index = 0
						repeat(presses) { jtui.pressSelectSilently(index++ < targetIndex) }
						forceSelectedCandidate = true
					}
				}
				jtui.debugShowAmbiguousSequence("[runPullInFlow 2c] Ambig sequence init: ")
			}
			if (suppressUIUpdate) jtui.restoreCurrentPageToPreviousPage()
		}
		return forceSelectedCandidate
	}

	override fun replayWordPrefixInJtui(word: String, keyCount: Int, capitalize: Boolean, isAllUpper: Boolean, precedingText: String?): Boolean {
		val jtui = getJtui() ?: return false
		val keys = jtui.mapWordToKeyIndices(word)?.take(keyCount) ?: return false
		jtui.withUiSuppressed {
			jtui.setCurrentPageToStartingPage()
			jtui.resetJTUI(capitalize, false, autoCapReason = AutoCapReason.NONE)
			jtui.ngbReconstructContext(precedingText)
			if (isAllUpper) jtui.setCapsLock(true)
			keys.forEach { k -> jtui.pressAmbiguousKeyNumberSilently(k, true) }
			jtui.debugShowAmbiguousSequence("[replayWordPrefixInJtui] Restored sequence: ")
		}
		return true
	}

	override fun wordKeyIndices(word: String): List<Int>? = getJtui()?.mapWordToKeyIndices(word)

	override fun reconstructNgbContext(precedingText: String?) {
		getJtui()?.ngbReconstructContext(precedingText)
	}

	override fun probeNgbSpan(word: String, precedingText: String?, followingText: String): Int = getJtui()?.ngbSpanProbe(word, precedingText, followingText) ?: 0

	override fun activateNgbSpan(): String? = getJtui()?.ngbSpanActivate()

	override fun forceUpdateUi(skipComposing: Boolean, selectedCandidate: String?, topCandidate: String?) {
		getJtui()?.forceUpdateUi(skipComposing, selectedCandidate, topCandidate)
	}

	// ── Text analysis ───────────────────────────────────────────────────

	override fun isKnownAbbreviation(word: String): Boolean = getJtui()?.isKnownAbbreviation(word) ?: false
	override fun isKnownDomain(word: String): Boolean = getJtui()?.isKnownDomain(word) ?: false
	override fun isAlphaChar(c: Char): Boolean = deps.isAlphaChar(c)
	override fun isWordDbChar(c: Char): Boolean = getJtui()?.isWordDbChar(c) ?: false
	override fun sendDpadEvent(direction: Int, movementMode: Int) = deps.sendDpadEvent(direction, movementMode)
	override fun isLineBreak(c: Char): Boolean = TextUtils.isLineBreak(c)

	// ── IME state ───────────────────────────────────────────────────────

	override var isEditMode: Boolean
		get() = deps.isEditMode
		set(value) {
			deps.isEditMode = value
		}
	override var isNewInputSession: Boolean
		get() = deps.isNewInputSession
		set(value) {
			deps.isNewInputSession = value
		}

	// ── Phrase flow ─────────────────────────────────────────────────────

	override val phraseFlow: PhraseFlowHandle? get() = deps.phraseFlowHandle

	// ── Speech helpers ──────────────────────────────────────────────────

	override fun speakQueued(text: String) = deps.speakQueued(text)
	override fun reuseOrCreatePendingSelection(text: String, type: String) = deps.reuseOrCreatePendingSelection(text, type)
	override fun speakIfEnabled(pending: TtsController.PendingSelection?) = deps.speakIfEnabled(pending)
	override fun cancelScheduledSpeak(clearPending: Boolean) = deps.cancelScheduledSpeak(clearPending)
	override fun rememberLastSpoken(text: String, type: String) = deps.rememberLastSpoken(text, type)
	override fun getPendingSelection() = deps.getPendingSelection()
	override fun setPendingSelection(pending: TtsController.PendingSelection?) = deps.setPendingSelection(pending)

	// ── Spelling/numeric tracking ───────────────────────────────────────

	override fun recordSpellNumeric(text: String) = deps.recordSpellNumeric(text)
	override fun flushSpellNumericIfNeeded(reason: String) = deps.flushSpellNumericIfNeeded(reason)

	// ── Settings / Feedback / Debug ─────────────────────────────────────

	override fun getAutoRestore(): Boolean = deps.getAutoRestore()
	override fun errorNotification() = deps.errorNotification()
	override fun errorBeep(force: Boolean) = deps.errorBeep(force)
	override fun debugLog(message: String) = deps.debugLog(message)
}
