package org.continuouspath.justtype.ime

import org.continuouspath.justtype.logic.AutoCapReason

/**
 * Reverse-communication interface from [ImeTextController] back to [JustTypeIME][org.continuouspath.justtype.JustTypeIME].
 *
 * ImeTextController owns all text-editing state and logic; this interface
 * provides access to things it does *not* own: JTUI state, phrase flow,
 * lifecycle flags, and speech helpers delegated through the IME.
 */
interface ImeTextCallbacks {

	// ── JTUI state queries ──────────────────────────────────────────────

	val isJtuiInitialized: Boolean
	val isInputViewShown: Boolean
	fun getAmbiguousSequenceLength(): Int
	fun getShiftState(): Boolean
	fun getIsManualShift(): Boolean
	fun getAutoCapReason(): AutoCapReason
	fun isSpellingMode(): Boolean
	fun isNumericMode(): Boolean
	fun getSpeakState(): Boolean
	fun selectedCandidateSuppressesLeadingSpace(): Boolean

	// ── JTUI commands ───────────────────────────────────────────────────

	fun setShiftState(shift: Boolean, isManual: Boolean, skipUpdate: Boolean, autoReason: AutoCapReason)
	fun setSpeakState(enabled: Boolean, announce: Boolean)
	fun resetJTUI(shiftState: Boolean, callUpdate: Boolean, isManualShift: Boolean = false, resetToStartPage: Boolean = false, autoCapReason: AutoCapReason, preserveAutospace: Boolean = false)
	fun forceUpdateUi()
	fun withUiSuppressed(block: () -> Unit)
	fun setSkipPostProcessingAfterPullIn(skip: Boolean)
	fun getSelectKeyCount(): Int
	fun isWordProducible(word: String): Boolean
	fun getCurrentPage(): String
	fun clearManualShift()
	fun hasPendingAmbiguityWithoutSelect(): Boolean
	fun getImmedCharCount(): Int
	fun clearImmedCharCount()

	/**
	 * Replays a word's key sequence in JTUI for pull-in. [precedingText] is the
	 * committed text before the pulled word — C2 context reconstruction runs
	 * between the internal reset and the key replay, so the replayed list
	 * already carries the prediction block.
	 * @return true if the candidate was force-selected (targetIndex > 0 or selectFirstOnMatch)
	 */
	fun replayWordInJtui(word: String, capitalize: Boolean, isAllUpper: Boolean, selectFirstOnMatch: Boolean, suppressUIUpdate: Boolean, precedingText: String? = null): Boolean

	/**
	 * Replays only the first [keyCount] keys of a word's sequence (same-field resume:
	 * restore a mid-entry sequence over its finalized word). No Select stepping.
	 * [precedingText]: see [replayWordInJtui].
	 */
	fun replayWordPrefixInJtui(word: String, keyCount: Int, capitalize: Boolean, isAllUpper: Boolean, precedingText: String? = null): Boolean = false

	/** C2: re-derive NGB context from the text preceding the cursor after a
	 *  whole-word delete (the reset just performed cleared it fail-soft). */
	fun reconstructNgbContext(precedingText: String?) {}

	/** C3: spans starting at the tapped word that match [followingText].
	 *  Returns the char count the longest span consumes BEYOND the word
	 *  (0 = none — standard pull-in). Also performs the C2 reconstruction. */
	fun probeNgbSpan(word: String, precedingText: String?, followingText: String): Int = 0

	/** C3: after the tapped word's replay, present the span group with the
	 *  longest text match selected. Returns its output, or null. */
	fun activateNgbSpan(): String? = null

	/** The word's full internal key sequence, or null if not producible. */
	fun wordKeyIndices(word: String): List<Int>? = null
	fun forceUpdateUi(skipComposing: Boolean, selectedCandidate: String?, topCandidate: String?)

	// ── IME state (not owned by text controller) ────────────────────────

	var isEditMode: Boolean
	var isNewInputSession: Boolean

	// ── Phrase flow (Step 8 boundary) ───────────────────────────────────

	val phraseFlow: PhraseFlowHandle?

	// ── Speech helpers (delegated through IME → TtsController) ──────────

	fun speakQueued(text: String)
	fun reuseOrCreatePendingSelection(text: String, type: String): TtsController.PendingSelection
	fun speakIfEnabled(pending: TtsController.PendingSelection?): Boolean
	fun cancelScheduledSpeak(clearPending: Boolean = true)
	fun rememberLastSpoken(text: String, type: String)
	fun getPendingSelection(): TtsController.PendingSelection?
	fun setPendingSelection(pending: TtsController.PendingSelection?)

	// ── Spelling/numeric tracking ───────────────────────────────────────

	fun recordSpellNumeric(text: String)
	fun flushSpellNumericIfNeeded(reason: String)

	// ── Text analysis (delegates to JTUI/TextUtils) ────────────────────

	fun isKnownAbbreviation(word: String): Boolean
	fun isKnownDomain(word: String): Boolean
	fun isAlphaChar(c: Char): Boolean
	fun isWordDbChar(c: Char): Boolean
	fun sendDpadEvent(direction: Int, movementMode: Int)
	fun isLineBreak(c: Char): Boolean

	// ── Settings ────────────────────────────────────────────────────────

	fun getAutoRestore(): Boolean

	// ── Feedback ────────────────────────────────────────────────────────

	fun errorNotification()
	fun errorBeep(force: Boolean)

	// ── Debug ───────────────────────────────────────────────────────────

	fun debugLog(message: String)
}

/**
 * Thin handle to PhraseFlowController for boundary isolation.
 * ImeTextController only needs to check if a flow is active and delegate
 * consume/backspace calls. The full PhraseFlowController stays in JustTypeIME
 * until Step 8 extracts it.
 */
interface PhraseFlowHandle {
	fun consumeImmediate(text: String): Boolean
	fun consumeNumeric(text: String): Boolean
	fun consumeSpelling(text: String): Boolean
	fun handleBackspace(): Boolean
}
