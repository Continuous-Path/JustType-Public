package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUI
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Characterization tests for [ImeTextCallbacksImpl].
 *
 * Mocking-policy exception: this test file uses mockito-kotlin on [JTUI]
 * even though JTUI is an untested collaborator. Per the [overview policy]
 * (../../../../docs/.plans/modernization/phase3/overview.md#mocking-policy),
 * untested collaborators normally get hand-rolled fakes. For an *adapter*,
 * however, every method on the surface is load-bearing — that's the whole
 * point of the adapter. Hand-rolling 40+ JTUI overrides on a fake produces
 * ~200 lines of pure boilerplate, while `verify(jtui).method(args)`
 * accomplishes the same assertion in one line. Documented exception in the
 * step 3.3 plan.
 */
class ImeTextCallbacksImplTest {

	private val jtui: JTUI = mock()
	private var jtuiHolder: JTUI? = jtui
	private var jtuiReady: Boolean = true

	private val deps = FakeDeps()

	private lateinit var subject: ImeTextCallbacksImpl

	@Before
	fun setUp() {
		jtuiHolder = jtui
		jtuiReady = true
		subject = ImeTextCallbacksImpl(
			getJtui = { jtuiHolder },
			isJtuiReady = { jtuiReady },
			deps = deps,
		)
	}

	// ── Group 1 — JTUI delegators: null fallback ────────────────────────────

	@Test
	fun `null jtui returns default values from all getters`() {
		jtuiHolder = null

		assertThat(subject.getAmbiguousSequenceLength()).isEqualTo(0)
		assertThat(subject.getShiftState()).isFalse()
		assertThat(subject.getIsManualShift()).isFalse()
		assertThat(subject.getAutoCapReason()).isEqualTo(AutoCapReason.NONE)
		assertThat(subject.isSpellingMode()).isFalse()
		assertThat(subject.isNumericMode()).isFalse()
		assertThat(subject.getSpeakState()).isFalse()
		assertThat(subject.selectedCandidateSuppressesLeadingSpace()).isFalse()
		assertThat(subject.getSelectKeyCount()).isEqualTo(0)
		assertThat(subject.isWordProducible("hello")).isFalse()
		assertThat(subject.getCurrentPage()).isEqualTo("")
		assertThat(subject.hasPendingAmbiguityWithoutSelect()).isFalse()
		assertThat(subject.getImmedCharCount()).isEqualTo(0)
		assertThat(subject.isKnownAbbreviation("abc")).isFalse()
		assertThat(subject.isKnownDomain("example.com")).isFalse()
		assertThat(subject.isWordDbChar('a')).isFalse()
	}

	@Test
	fun `null jtui makes all command methods no-op without throwing`() {
		jtuiHolder = null

		// Should not throw.
		subject.setShiftState(true, false, false, AutoCapReason.NONE)
		subject.setSpeakState(true, false)
		subject.resetJTUI(false, false, false, false, AutoCapReason.NONE)
		subject.forceUpdateUi()
		subject.setSkipPostProcessingAfterPullIn(true)
		subject.clearManualShift()
		subject.clearImmedCharCount()
		subject.forceUpdateUi(true, "sel", "top")
	}

	@Test
	fun `withUiSuppressed runs block directly when jtui is null`() {
		jtuiHolder = null
		var ran = false

		subject.withUiSuppressed { ran = true }

		assertThat(ran).isTrue()
	}

	// ── Group 2 — JTUI delegators: forwarding ───────────────────────────────

	@Test
	fun `getters return the value JTUI provides`() {
		whenever(jtui.getAmbiguousSequenceLength()).thenReturn(7)
		whenever(jtui.getShiftState()).thenReturn(true)
		whenever(jtui.getIsManualShift()).thenReturn(true)
		whenever(jtui.getAutoCapReason()).thenReturn(AutoCapReason.SENTENCE_START)
		whenever(jtui.getIsSpellingMode()).thenReturn(true)
		whenever(jtui.isInNumericMode()).thenReturn(true)
		whenever(jtui.getSpeakState()).thenReturn(true)
		whenever(jtui.selectedCandidateSuppressesLeadingSpace()).thenReturn(true)
		whenever(jtui.getSelectKeyCount()).thenReturn(3)
		whenever(jtui.isWordProducible("hi")).thenReturn(true)
		whenever(jtui.getCurrentPage()).thenReturn("alpha")
		whenever(jtui.hasPendingAmbiguityWithoutSelect()).thenReturn(true)
		whenever(jtui.getImmedCharCount()).thenReturn(2)
		whenever(jtui.isKnownAbbreviation("Mr")).thenReturn(true)
		whenever(jtui.isKnownDomain("example.com")).thenReturn(true)
		whenever(jtui.isWordDbChar('a')).thenReturn(true)

		assertThat(subject.getAmbiguousSequenceLength()).isEqualTo(7)
		assertThat(subject.getShiftState()).isTrue()
		assertThat(subject.getIsManualShift()).isTrue()
		assertThat(subject.getAutoCapReason()).isEqualTo(AutoCapReason.SENTENCE_START)
		assertThat(subject.isSpellingMode()).isTrue()
		assertThat(subject.isNumericMode()).isTrue()
		assertThat(subject.getSpeakState()).isTrue()
		assertThat(subject.selectedCandidateSuppressesLeadingSpace()).isTrue()
		assertThat(subject.getSelectKeyCount()).isEqualTo(3)
		assertThat(subject.isWordProducible("hi")).isTrue()
		assertThat(subject.getCurrentPage()).isEqualTo("alpha")
		assertThat(subject.hasPendingAmbiguityWithoutSelect()).isTrue()
		assertThat(subject.getImmedCharCount()).isEqualTo(2)
		assertThat(subject.isKnownAbbreviation("Mr")).isTrue()
		assertThat(subject.isKnownDomain("example.com")).isTrue()
		assertThat(subject.isWordDbChar('a')).isTrue()
	}

	@Test
	fun `commands forward args to JTUI`() {
		subject.setShiftState(shift = true, isManual = true, skipUpdate = true, autoReason = AutoCapReason.MANUAL)
		verify(jtui).setShiftState(true, true, true, AutoCapReason.MANUAL)

		subject.setSpeakState(enabled = true, announce = true)
		verify(jtui).setSpeakState(true, true)

		subject.resetJTUI(
			shiftState = true,
			callUpdate = false,
			isManualShift = true,
			resetToStartPage = true,
			autoCapReason = AutoCapReason.FIELD_START,
		)
		verify(jtui).resetJTUI(
			capitalize = true,
			callUpdateUi = false,
			isManualShift = true,
			resetToStartPage = true,
			autoCapReason = AutoCapReason.FIELD_START,
		)

		subject.setSkipPostProcessingAfterPullIn(true)
		verify(jtui).skipPostProcessingAfterPullIn = true

		subject.clearManualShift()
		verify(jtui).clearManualShift()

		subject.clearImmedCharCount()
		verify(jtui).clearImmedCharCount()
	}

	@Test
	fun `getCurrentPage and hasPendingAmbiguityWithoutSelect swallow JTUI exceptions`() {
		whenever(jtui.getCurrentPage()).thenThrow(RuntimeException("boom"))
		whenever(jtui.hasPendingAmbiguityWithoutSelect()).thenThrow(RuntimeException("boom"))

		assertThat(subject.getCurrentPage()).isEqualTo("")
		assertThat(subject.hasPendingAmbiguityWithoutSelect()).isFalse()
	}

	@Test
	fun `forceUpdateUi overloads target the correct JTUI signature`() {
		subject.forceUpdateUi()
		verify(jtui).forceUpdateUi(false)

		subject.forceUpdateUi(skipComposing = true, selectedCandidate = "sel", topCandidate = "top")
		verify(jtui).forceUpdateUi(true, "sel", "top")
	}

	// ── Group 3 — replayWordInJtui ──────────────────────────────────────────

	@Test
	fun `replayWordInJtui returns false when jtui is null and makes no calls`() {
		jtuiHolder = null

		val result = subject.replayWordInJtui("hi", capitalize = false, isAllUpper = false, selectFirstOnMatch = false, suppressUIUpdate = false)

		assertThat(result).isFalse()
	}

	@Test
	fun `replayWordInJtui returns false after reset when mapWordToKeyIndices returns null`() {
		stubWithUiSuppressed()
		whenever(jtui.mapWordToKeyIndices("zz")).thenReturn(null)

		val result = subject.replayWordInJtui("zz", capitalize = false, isAllUpper = false, selectFirstOnMatch = false, suppressUIUpdate = false)

		assertThat(result).isFalse()
		// Reset still happens before the null-check returns false.
		verify(jtui).setCurrentPageToStartingPage()
		verify(jtui).resetJTUI(false, false, autoCapReason = AutoCapReason.NONE)
		verify(jtui, never()).pressSelectSilently(any())
	}

	@Test
	fun `replayWordInJtui setCapsLock called when isAllUpper is true`() {
		stubWithUiSuppressed()
		whenever(jtui.mapWordToKeyIndices("HI")).thenReturn(listOf(2, 3))
		whenever(jtui.pullInTargetIndex("HI")).thenReturn(0)

		subject.replayWordInJtui("HI", capitalize = true, isAllUpper = true, selectFirstOnMatch = false, suppressUIUpdate = false)

		verify(jtui).setCapsLock(true)
	}

	@Test
	fun `replayWordInJtui returns false when targetIndex is 0 and selectFirstOnMatch is false`() {
		stubWithUiSuppressed()
		whenever(jtui.mapWordToKeyIndices("hi")).thenReturn(listOf(2, 3))
		whenever(jtui.pullInTargetIndex("hi")).thenReturn(0)

		val result = subject.replayWordInJtui("hi", capitalize = false, isAllUpper = false, selectFirstOnMatch = false, suppressUIUpdate = false)

		assertThat(result).isFalse()
		verify(jtui, never()).pressSelectSilently(any())
	}

	@Test
	fun `replayWordInJtui returns true with one pressSelectSilently when targetIndex is 0 and selectFirstOnMatch is true`() {
		stubWithUiSuppressed()
		whenever(jtui.mapWordToKeyIndices("hi")).thenReturn(listOf(2, 3))
		whenever(jtui.pullInTargetIndex("hi")).thenReturn(0)

		val result = subject.replayWordInJtui("hi", capitalize = false, isAllUpper = false, selectFirstOnMatch = true, suppressUIUpdate = false)

		assertThat(result).isTrue()
		verify(jtui, times(1)).pressSelectSilently(false)
	}

	@Test
	fun `replayWordInJtui presses select three times for targetIndex 2`() {
		stubWithUiSuppressed()
		whenever(jtui.mapWordToKeyIndices("baz")).thenReturn(listOf(2, 3, 4))
		whenever(jtui.pullInTargetIndex("baz")).thenReturn(2)

		val result = subject.replayWordInJtui("baz", capitalize = false, isAllUpper = false, selectFirstOnMatch = false, suppressUIUpdate = false)

		assertThat(result).isTrue()
		// indices 0 and 1 are suppressed (true), final press at index 2 is not (false)
		verify(jtui, times(2)).pressSelectSilently(true)
		verify(jtui, times(1)).pressSelectSilently(false)
	}

	@Test
	fun `replayWordInJtui restores previous page when suppressUIUpdate is true`() {
		stubWithUiSuppressed()
		whenever(jtui.mapWordToKeyIndices("hi")).thenReturn(listOf(2, 3))
		whenever(jtui.pullInTargetIndex("hi")).thenReturn(0)

		subject.replayWordInJtui("hi", capitalize = false, isAllUpper = false, selectFirstOnMatch = false, suppressUIUpdate = true)

		verify(jtui).restoreCurrentPageToPreviousPage()
	}

	// ── Group 4 — Deps delegation + properties ──────────────────────────────

	@Test
	fun `isInputViewShown reads from deps`() {
		deps.isInputViewShown = true
		assertThat(subject.isInputViewShown).isTrue()

		deps.isInputViewShown = false
		assertThat(subject.isInputViewShown).isFalse()
	}

	@Test
	fun `isEditMode round-trips through deps`() {
		deps.isEditMode = false
		assertThat(subject.isEditMode).isFalse()

		subject.isEditMode = true
		assertThat(deps.isEditMode).isTrue()
		assertThat(subject.isEditMode).isTrue()
	}

	@Test
	fun `isNewInputSession round-trips through deps`() {
		deps.isNewInputSession = false
		assertThat(subject.isNewInputSession).isFalse()

		subject.isNewInputSession = true
		assertThat(deps.isNewInputSession).isTrue()
	}

	@Test
	fun `phraseFlow returns whatever the deps fake exposes`() {
		assertThat(subject.phraseFlow).isNull()

		val handle = object : PhraseFlowHandle {
			override fun consumeImmediate(text: String): Boolean = false
			override fun consumeNumeric(text: String): Boolean = false
			override fun consumeSpelling(text: String): Boolean = false
			override fun handleBackspace(): Boolean = false
		}
		deps.phraseFlowHandle = handle

		assertThat(subject.phraseFlow).isSameInstanceAs(handle)
	}

	@Test
	fun `speech helpers forward args to deps`() {
		subject.speakQueued("hello")
		assertThat(deps.speakQueuedCalls).containsExactly("hello")

		val pending = TtsController.PendingSelection("a", "X")
		deps.reuseOrCreateReturn = pending
		val got = subject.reuseOrCreatePendingSelection("a", "X")
		assertThat(got).isSameInstanceAs(pending)
		assertThat(deps.reuseOrCreateCalls).containsExactly("a" to "X")

		deps.speakIfEnabledReturn = true
		assertThat(subject.speakIfEnabled(pending)).isTrue()
		assertThat(deps.speakIfEnabledCalls).containsExactly(pending)

		subject.cancelScheduledSpeak(clearPending = false)
		assertThat(deps.cancelScheduledSpeakCalls).containsExactly(false)

		subject.rememberLastSpoken("hi", "Y")
		assertThat(deps.rememberLastSpokenCalls).containsExactly("hi" to "Y")

		deps.pendingSelectionField = pending
		assertThat(subject.getPendingSelection()).isSameInstanceAs(pending)

		val newPending = TtsController.PendingSelection("z", "Z")
		subject.setPendingSelection(newPending)
		assertThat(deps.pendingSelectionField).isSameInstanceAs(newPending)
	}

	@Test
	fun `recordSpellNumeric and flushSpellNumericIfNeeded forward args`() {
		subject.recordSpellNumeric("12")
		assertThat(deps.recordSpellNumericCalls).containsExactly("12")

		subject.flushSpellNumericIfNeeded("commit")
		assertThat(deps.flushSpellNumericCalls).containsExactly("commit")
	}

	@Test
	fun `getAutoRestore and errorNotification and debugLog forward to deps`() {
		deps.autoRestoreReturn = true
		assertThat(subject.getAutoRestore()).isTrue()

		subject.errorNotification()
		assertThat(deps.errorNotificationCount).isEqualTo(1)

		subject.debugLog("dbg")
		assertThat(deps.debugLogCalls).containsExactly("dbg")
	}

	@Test
	fun `errorBeep forwards force flag`() {
		subject.errorBeep(force = true)
		subject.errorBeep(force = false)

		assertThat(deps.errorBeepCalls).containsExactly(true, false).inOrder()
	}

	@Test
	fun `isLineBreak delegates to TextUtils`() {
		assertThat(subject.isLineBreak('\n')).isTrue()
		assertThat(subject.isLineBreak('a')).isFalse()
	}

	@Test
	fun `isAlphaChar delegates to deps`() {
		deps.isAlphaCharReturn = true
		assertThat(subject.isAlphaChar('a')).isTrue()
		assertThat(deps.isAlphaCharCalls).containsExactly('a')
	}

	@Test
	fun `sendDpadEvent forwards args to deps`() {
		subject.sendDpadEvent(direction = 5, movementMode = 2)
		assertThat(deps.sendDpadEventCalls).containsExactly(5 to 2)
	}

	@Test
	fun `isJtuiInitialized reads from isJtuiReady lambda`() {
		jtuiReady = true
		assertThat(subject.isJtuiInitialized).isTrue()

		jtuiReady = false
		assertThat(subject.isJtuiInitialized).isFalse()
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	private fun stubWithUiSuppressed() {
		whenever(jtui.withUiSuppressed(any())).then {
			@Suppress("UNCHECKED_CAST")
			(it.arguments[0] as () -> Unit).invoke()
		}
	}

	/**
	 * Hand-rolled fake for [ImeTextCallbacksImplDeps]. Records per-method calls
	 * with primitive payloads so tests can assert exact-arg forwarding.
	 */
	private class FakeDeps : ImeTextCallbacksImplDeps {
		override var isInputViewShown: Boolean = false
		override var isEditMode: Boolean = false
		override var isNewInputSession: Boolean = false
		override var phraseFlowHandle: PhraseFlowHandle? = null

		var isAlphaCharReturn: Boolean = false
		val isAlphaCharCalls = mutableListOf<Char>()
		override fun isAlphaChar(c: Char): Boolean {
			isAlphaCharCalls += c
			return isAlphaCharReturn
		}

		val sendDpadEventCalls = mutableListOf<Pair<Int, Int>>()
		override fun sendDpadEvent(direction: Int, movementMode: Int) {
			sendDpadEventCalls += direction to movementMode
		}

		val speakQueuedCalls = mutableListOf<String>()
		override fun speakQueued(text: String) {
			speakQueuedCalls += text
		}

		var reuseOrCreateReturn: TtsController.PendingSelection = TtsController.PendingSelection("", "")
		val reuseOrCreateCalls = mutableListOf<Pair<String, String>>()
		override fun reuseOrCreatePendingSelection(text: String, type: String): TtsController.PendingSelection {
			reuseOrCreateCalls += text to type
			return reuseOrCreateReturn
		}

		var speakIfEnabledReturn: Boolean = false
		val speakIfEnabledCalls = mutableListOf<TtsController.PendingSelection?>()
		override fun speakIfEnabled(pending: TtsController.PendingSelection?): Boolean {
			speakIfEnabledCalls += pending
			return speakIfEnabledReturn
		}

		val cancelScheduledSpeakCalls = mutableListOf<Boolean>()
		override fun cancelScheduledSpeak(clearPending: Boolean) {
			cancelScheduledSpeakCalls += clearPending
		}

		val rememberLastSpokenCalls = mutableListOf<Pair<String, String>>()
		override fun rememberLastSpoken(text: String, type: String) {
			rememberLastSpokenCalls += text to type
		}

		var pendingSelectionField: TtsController.PendingSelection? = null
		override fun getPendingSelection(): TtsController.PendingSelection? = pendingSelectionField
		override fun setPendingSelection(pending: TtsController.PendingSelection?) {
			pendingSelectionField = pending
		}

		val recordSpellNumericCalls = mutableListOf<String>()
		override fun recordSpellNumeric(text: String) {
			recordSpellNumericCalls += text
		}

		val flushSpellNumericCalls = mutableListOf<String>()
		override fun flushSpellNumericIfNeeded(reason: String) {
			flushSpellNumericCalls += reason
		}

		var autoRestoreReturn: Boolean = false
		override fun getAutoRestore(): Boolean = autoRestoreReturn

		var errorNotificationCount: Int = 0
		override fun errorNotification() {
			errorNotificationCount++
		}

		val errorBeepCalls = mutableListOf<Boolean>()
		override fun errorBeep(force: Boolean) {
			errorBeepCalls += force
		}

		val debugLogCalls = mutableListOf<String>()
		override fun debugLog(message: String) {
			debugLogCalls += message
		}
	}
}
