package org.continuouspath.justtype.ime

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.InputConnection
import android.widget.EditText
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.continuouspath.justtype.logic.AutoCapReason
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Characterization tests for ImeTextController (Phase 2 Step 7b).
 *
 * These tests verify the extracted text-editing logic behaves correctly
 * in isolation from JustTypeIME and JTUI. They use Robolectric's
 * BaseInputConnection backed by a real EditText for realistic IC behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImeTextControllerTest {

	private lateinit var testScope: TestScope
	private lateinit var editText: EditText
	private lateinit var ic: InputConnection
	private lateinit var ttsController: TtsController
	private lateinit var fakeCallbacks: FakeImeTextCallbacks
	private lateinit var controller: ImeTextController

	@Before
	fun setUp() {
		testScope = TestScope(StandardTestDispatcher())
		val context = RuntimeEnvironment.getApplication()
		editText = EditText(context)
		// Stock BaseInputConnection edits its own internal buffer; overriding getEditable makes
		// IC operations land in the EditText (like the platform's EditableInputConnection), so
		// assertions on editText.text reflect what the IC did. Without this the class only passed
		// when lucky cross-class Robolectric state intervened.
		ic = object : BaseInputConnection(editText, true) {
			override fun getEditable(): android.text.Editable = editText.text
		}
		fakeCallbacks = FakeImeTextCallbacks()
		ttsController = TtsController(
			context = context,
			scope = testScope,
			getSpeakState = { false },
			getInputConnection = { ic },
		)
		controller = ImeTextController(
			scope = testScope,
			getInputConnection = { ic },
			getInputEditorInfo = { null },
			callbacks = fakeCallbacks,
			ttsController = ttsController,
		)
	}

	@After
	fun tearDown() {
		testScope.coroutineContext[Job]?.cancel()
	}

	// ── Autospace: shouldInsertLeadingSpace ──────────────────────────────

	@Test
	fun `shouldInsertLeadingSpace returns true after letter`() {
		assertThat(controller.shouldInsertLeadingSpace('a', null)).isTrue()
	}

	@Test
	fun `shouldInsertLeadingSpace returns true after digit`() {
		assertThat(controller.shouldInsertLeadingSpace('5', null)).isTrue()
	}

	@Test
	fun `shouldInsertLeadingSpace returns false after space`() {
		assertThat(controller.shouldInsertLeadingSpace(' ', null)).isFalse()
	}

	@Test
	fun `shouldInsertLeadingSpace returns false after null`() {
		assertThat(controller.shouldInsertLeadingSpace(null, null)).isFalse()
	}

	@Test
	fun `shouldInsertLeadingSpace returns false after newline`() {
		assertThat(controller.shouldInsertLeadingSpace('\n', null)).isFalse()
	}

	@Test
	fun `shouldInsertLeadingSpace returns false after opening paren`() {
		assertThat(controller.shouldInsertLeadingSpace('(', null)).isFalse()
	}

	@Test
	fun `shouldInsertLeadingSpace returns false after opening bracket`() {
		assertThat(controller.shouldInsertLeadingSpace('[', null)).isFalse()
	}

	@Test
	fun `shouldInsertLeadingSpace returns false after opening quote`() {
		assertThat(controller.shouldInsertLeadingSpace('"', null)).isFalse()
	}

	// ── Autospace: shouldAllowAutospace ──────────────────────────────────

	@Test
	fun `shouldAllowAutospace returns true after letter`() {
		assertThat(controller.shouldAllowAutospace('a')).isTrue()
	}

	@Test
	fun `shouldAllowAutospace returns true after digit`() {
		assertThat(controller.shouldAllowAutospace('5')).isTrue()
	}

	@Test
	fun `shouldAllowAutospace returns false after space`() {
		assertThat(controller.shouldAllowAutospace(' ')).isFalse()
	}

	@Test
	fun `shouldAllowAutospace returns false after null`() {
		assertThat(controller.shouldAllowAutospace(null)).isFalse()
	}

	@Test
	fun `shouldAllowAutospace returns true after period`() {
		assertThat(controller.shouldAllowAutospace('.')).isTrue()
	}

	@Test
	fun `shouldAllowAutospace returns true after exclamation`() {
		assertThat(controller.shouldAllowAutospace('!')).isTrue()
	}

	// ── Autospace: shouldInsertTrailingSpace ─────────────────────────────

	@Test
	fun `shouldInsertTrailingSpace returns true before letter`() {
		assertThat(controller.shouldInsertTrailingSpace('a', null)).isTrue()
	}

	@Test
	fun `shouldInsertTrailingSpace returns false before space`() {
		assertThat(controller.shouldInsertTrailingSpace(' ', null)).isFalse()
	}

	@Test
	fun `shouldInsertTrailingSpace returns false before newline`() {
		assertThat(controller.shouldInsertTrailingSpace('\n', null)).isFalse()
	}

	// ── Autospace: shouldSuppressLeadingAutospace ────────────────────────

	@Test
	fun `shouldSuppressLeadingAutospace returns true for apostrophe-led text`() {
		assertThat(controller.shouldSuppressLeadingAutospace("'s")).isTrue()
	}

	@Test
	fun `shouldSuppressLeadingAutospace returns true for hyphen-led text`() {
		assertThat(controller.shouldSuppressLeadingAutospace("-tion")).isTrue()
	}

	@Test
	fun `shouldSuppressLeadingAutospace returns true for period-led text`() {
		assertThat(controller.shouldSuppressLeadingAutospace(".com")).isTrue()
	}

	@Test
	fun `shouldSuppressLeadingAutospace returns false for letter-led text`() {
		assertThat(controller.shouldSuppressLeadingAutospace("hello")).isFalse()
	}

	@Test
	fun `shouldSuppressLeadingAutospace returns false for empty text`() {
		assertThat(controller.shouldSuppressLeadingAutospace("")).isFalse()
	}

	// ── Composing state management ──────────────────────────────────────

	@Test
	fun `initial state has no composing`() {
		assertThat(controller.haveComposing).isFalse()
		assertThat(controller.lastComposingSent).isNull()
		assertThat(controller.lastPreview).isEqualTo("")
	}

	@Test
	fun `initial autospace flags are all false`() {
		assertThat(controller.autoSpaceDecision).isFalse()
		assertThat(controller.autoSpaceInserted).isFalse()
		assertThat(controller.autoSpaceInsertionDelayed).isFalse()
		assertThat(controller.spacePossible).isFalse()
		assertThat(controller.pendingTrailingSpace).isFalse()
	}

	@Test
	fun `suspendCommit starts false`() {
		assertThat(controller.suspendCommit).isFalse()
	}

	// ── Cursor/selection state ──────────────────────────────────────────

	@Test
	fun `setIgnoreCursorRange stores with padding and clears range`() {
		controller.setIgnoreCursorRange(5, 10)
		// Implementation pads the range: start-1, end+1
		assertThat(controller.ignoreCursorStart).isEqualTo(4)
		assertThat(controller.ignoreCursorEnd).isEqualTo(11)

		controller.setIgnoreCursorRange(-1, -1)
		assertThat(controller.ignoreCursorStart).isEqualTo(-1)
		assertThat(controller.ignoreCursorEnd).isEqualTo(-1)
	}

	@Test
	fun `bookmark initial values are minus one`() {
		assertThat(controller.bookmarkA).isEqualTo(-1)
		assertThat(controller.bookmarkB).isEqualTo(-1)
	}

	// ── Pull-in state ───────────────────────────────────────────────────

	@Test
	fun `pull-in state starts clean`() {
		assertThat(controller.isPullInMode).isFalse()
		assertThat(controller.lastPullInWord).isNull()
		assertThat(controller.detectedWord).isEmpty()
		assertThat(controller.attemptPullInOnFirstUpdate).isFalse()
	}

	@Test
	fun `cancelPullIn clears pending pull-in`() {
		controller.isPullInMode = true
		controller.cancelPullIn()
		// cancelPullIn clears pendingPullIn and increments token
		assertThat(controller.pendingPullIn).isNull()
	}

	// ── onImmediateOutput ───────────────────────────────────────────────

	@Test
	fun `onImmediateOutput commits text via IC`() {
		editText.setText("Hello ")
		editText.setSelection(6)

		controller.onImmediateOutput("world")

		// After immediate output, composing should be cleared
		assertThat(controller.haveComposing).isFalse()
		assertThat(controller.lastComposingSent).isNull()
		// Autospace flags should be reset
		assertThat(controller.autoSpaceDecision).isFalse()
		assertThat(controller.autoSpaceInserted).isFalse()
	}

	@Test
	fun `onImmediateOutput with phrase flow active is consumed`() {
		fakeCallbacks.phraseFlowHandle = object : PhraseFlowHandle {
			override fun consumeImmediate(text: String) = true
			override fun consumeNumeric(text: String) = false
			override fun consumeSpelling(text: String) = false
			override fun handleBackspace() = false
		}
		controller.onImmediateOutput("test")
		// Should be consumed by phrase flow — composing state unchanged
		assertThat(controller.haveComposing).isFalse()
	}

	// ── onFinalizeText ──────────────────────────────────────────────────

	@Test
	fun `onFinalizeText clears composing state`() {
		controller.haveComposing = true
		controller.lastComposingSent = "test"
		controller.autoSpaceDecision = true
		controller.autoSpaceInserted = true

		controller.onFinalizeText("test")

		assertThat(controller.haveComposing).isFalse()
		assertThat(controller.lastComposingSent).isNull()
		assertThat(controller.autoSpaceDecision).isFalse()
		assertThat(controller.autoSpaceInserted).isFalse()
	}

	@Test
	fun `onFinalizeText with empty text does not trigger speech`() {
		controller.onFinalizeText("")
		// No crash, no side effects
		assertThat(controller.haveComposing).isFalse()
	}

	// ── onAmbiguousSequenceStart ────────────────────────────────────────

	@Test
	fun `onAmbiguousSequenceStart clears pending trailing space`() {
		controller.pendingTrailingSpace = true
		controller.onAmbiguousSequenceStart()
		assertThat(controller.pendingTrailingSpace).isFalse()
	}

	// ── onSpellingOutput ────────────────────────────────────────────────

	@Test
	fun `onSpellingOutput sets composing text and state`() {
		editText.setText("")
		editText.setSelection(0)

		controller.onSpellingOutput("abc")

		assertThat(controller.haveComposing).isTrue()
		assertThat(controller.lastComposingSent).isEqualTo("abc")
		assertThat(controller.autoSpaceDecision).isFalse()
		assertThat(controller.autoSpaceInserted).isFalse()
	}

	@Test
	fun `onSpellingOutput consumed by phrase flow`() {
		fakeCallbacks.phraseFlowHandle = object : PhraseFlowHandle {
			override fun consumeImmediate(text: String) = false
			override fun consumeNumeric(text: String) = false
			override fun consumeSpelling(text: String) = true
			override fun handleBackspace() = false
		}
		controller.onSpellingOutput("test")
		assertThat(controller.haveComposing).isFalse()
	}

	// ── onNumericOutput ─────────────────────────────────────────────────

	@Test
	fun `onNumericOutput with empty text clears composing`() {
		controller.haveComposing = true
		controller.lastComposingSent = "5"
		controller.autoSpaceInserted = false

		controller.onNumericOutput("")

		assertThat(controller.haveComposing).isFalse()
		assertThat(controller.lastComposingSent).isNull()
	}

	@Test
	fun `onNumericOutput with non-empty text sets composing`() {
		editText.setText("test ")
		editText.setSelection(5)

		controller.onNumericOutput("7")

		assertThat(controller.haveComposing).isTrue()
		assertThat(controller.lastComposingSent).isEqualTo("7")
	}

	// ── onSpeakSentence ─────────────────────────────────────────────────

	@Test
	fun `onSpeakSentence returns immediately when phrase flow active`() {
		fakeCallbacks.phraseFlowHandle = object : PhraseFlowHandle {
			override fun consumeImmediate(text: String) = false
			override fun consumeNumeric(text: String) = false
			override fun consumeSpelling(text: String) = false
			override fun handleBackspace() = false
		}
		// Should not crash
		controller.onSpeakSentence(true)
	}

	// ── Auto-shift: computeAutoShift ────────────────────────────────────

	/** Creates a controller backed by a mock IC that returns the given text/selection. */
	private fun controllerWithMockIc(text: String, selectionStart: Int): ImeTextController {
		val et = ExtractedText().apply {
			this.text = text
			this.selectionStart = selectionStart
			this.selectionEnd = selectionStart
		}
		val mockIc: InputConnection = mock()
		whenever(mockIc.getExtractedText(any(), any())).thenReturn(et)
		whenever(mockIc.getTextBeforeCursor(any(), any())).thenReturn(
			if (selectionStart > 0) text.substring(0, selectionStart) else "",
		)
		whenever(mockIc.getTextAfterCursor(any(), any())).thenReturn(
			if (selectionStart < text.length) text.substring(selectionStart) else "",
		)
		return ImeTextController(
			scope = testScope,
			getInputConnection = { mockIc },
			getInputEditorInfo = { null },
			callbacks = fakeCallbacks,
			ttsController = ttsController,
		)
	}

	@Test
	fun `computeAutoShift returns true at start of empty text`() {
		val ctrl = controllerWithMockIc("", 0)
		assertThat(ctrl.computeAutoShift()).isTrue()
		assertThat(ctrl.lastAutoCapReason).isEqualTo(AutoCapReason.FIELD_START)
	}

	@Test
	fun `computeAutoShift returns true after sentence ender and space`() {
		val ctrl = controllerWithMockIc("Hello. ", 7)
		assertThat(ctrl.computeAutoShift()).isTrue()
		assertThat(ctrl.lastAutoCapReason).isEqualTo(AutoCapReason.SENTENCE_START)
	}

	@Test
	fun `computeAutoShift returns false in middle of word`() {
		val ctrl = controllerWithMockIc("Hello world", 8)
		assertThat(ctrl.computeAutoShift()).isFalse()
	}

	@Test
	fun `computeAutoShift returns true after newline`() {
		val ctrl = controllerWithMockIc("Hello.\n", 7)
		assertThat(ctrl.computeAutoShift()).isTrue()
		assertThat(ctrl.lastAutoCapReason).isEqualTo(AutoCapReason.LINE_START)
	}

	@Test
	fun `computeAutoShift returns true after exclamation and space`() {
		val ctrl = controllerWithMockIc("Stop! ", 6)
		assertThat(ctrl.computeAutoShift()).isTrue()
		assertThat(ctrl.lastAutoCapReason).isEqualTo(AutoCapReason.SENTENCE_START)
	}

	@Test
	fun `computeAutoShift returns false after comma and space`() {
		val ctrl = controllerWithMockIc("Hello, ", 7)
		assertThat(ctrl.computeAutoShift()).isFalse()
	}

	// ── Autospace edit mode ─────────────────────────────────────────────

	@Test
	fun `beginAutospaceEdit sets ignore mode`() {
		controller.beginAutospaceEdit()
		assertThat(controller.autospaceIgnoreActive).isTrue()
	}

	@Test
	fun `maybeResetAutospaceIgnore resets when pending`() {
		controller.autospaceIgnoreActive = true
		controller.autospaceIgnoreResetPending = true
		controller.autospaceIgnorePrev = false

		controller.maybeResetAutospaceIgnore()

		assertThat(controller.autospaceIgnoreResetPending).isFalse()
		assertThat(controller.autospaceIgnoreActive).isFalse()
	}

	@Test
	fun `maybeResetAutospaceIgnore is no-op when not pending`() {
		controller.autospaceIgnoreActive = true
		controller.autospaceIgnoreResetPending = false

		controller.maybeResetAutospaceIgnore()

		assertThat(controller.autospaceIgnoreActive).isTrue()
	}

	// ── handleSelectionUpdate filtering ─────────────────────────────────

	@Test
	fun `handleSelectionUpdate skips when isPullInMode`() {
		controller.isPullInMode = true
		// Should not crash or process further
		controller.handleSelectionUpdate(0, 0, 5, 5, -1, -1)
		// If isPullInMode, processing is skipped — haveComposing should be unchanged
		assertThat(controller.haveComposing).isFalse()
	}

	@Test
	fun `handleSelectionUpdate skips when isEditMode`() {
		fakeCallbacks.isEditMode = true
		controller.handleSelectionUpdate(0, 0, 5, 5, -1, -1)
		assertThat(controller.haveComposing).isFalse()
	}

	@Test
	fun `handleSelectionUpdate skips when ignoreSelectionUpdate`() {
		controller.ignoreSelectionUpdate = true
		controller.handleSelectionUpdate(0, 0, 5, 5, -1, -1)
		assertThat(controller.haveComposing).isFalse()
	}

	@Test
	fun `handleSelectionUpdate skips when selection unchanged`() {
		controller.lastSelStart = 5
		controller.lastSelEnd = 5
		controller.handleSelectionUpdate(0, 0, 5, 5, -1, -1)
		// No state change
		assertThat(controller.haveComposing).isFalse()
	}

	// ── applyEditorUpdate ───────────────────────────────────────────────

	@Test
	fun `applyEditorUpdate with null candidate and no prior composing is no-op`() {
		controller.applyEditorUpdate(null)
		assertThat(controller.haveComposing).isFalse()
	}

	@Test
	fun `applyEditorUpdate with empty preview clears composing`() {
		controller.haveComposing = true
		controller.lastComposingSent = "test"

		editText.setText("test")
		editText.setSelection(4)
		ic.setComposingText("test", 1)

		controller.applyEditorUpdate(null)

		assertThat(controller.haveComposing).isFalse()
		assertThat(controller.lastComposingSent).isNull()
	}

	// ── relinquishComposingOnHide ───────────────────────────────────────

	@Test
	fun `relinquishComposingOnHide commits the word once and clears bookkeeping`() {
		editText.setText("This is a ")
		editText.setSelection(10)
		ic.setComposingText("test", 1)
		controller.haveComposing = true
		controller.lastComposingSent = "test"
		controller.lastPreview = "test"

		controller.relinquishComposingOnHide()

		// The word stays exactly once (committed), never duplicated on later emits.
		assertThat(editText.text.toString()).isEqualTo("This is a test")
		assertThat(controller.haveComposing).isFalse()
		assertThat(controller.lastComposingSent).isNull()
		assertThat(controller.lastPreview).isEmpty()
	}

	@Test
	fun `relinquishComposingOnHide is a safe no-op without composing text`() {
		editText.setText("done")
		editText.setSelection(4)

		controller.relinquishComposingOnHide()

		assertThat(editText.text.toString()).isEqualTo("done")
		assertThat(controller.haveComposing).isFalse()
	}

	// ── Editing engine: delete char / delete word ────────────────────────

	@Test
	fun `deleteLeftChar removes the char before the cursor and reports success`() {
		editText.setText("abc")
		editText.setSelection(3)

		val newPos = controller.deleteLeftChar()

		assertThat(editText.text.toString()).isEqualTo("ab")
		// BaseInputConnection has no extracted text, so the exact position is
		// degraded here — the contract worth pinning is success (>= 0) vs -1.
		assertThat(newPos).isAtLeast(0)
	}

	@Test
	fun `deleteLeftChar mid-text deletes left of the cursor only`() {
		editText.setText("abcd")
		editText.setSelection(2)

		controller.deleteLeftChar()

		assertThat(editText.text.toString()).isEqualTo("acd")
	}

	@Test
	fun `deleteLeftChar at the start of text deletes nothing and reports failure`() {
		editText.setText("abc")
		editText.setSelection(0)

		assertThat(controller.deleteLeftChar()).isEqualTo(-1)
		assertThat(editText.text.toString()).isEqualTo("abc")
	}

	@Test
	fun `handleDeleteWord removes the word before the cursor`() {
		editText.setText("hello world")
		editText.setSelection(11)

		controller.handleDeleteWord()

		assertThat(editText.text.toString()).isEqualTo("hello ")
	}

	@Test
	fun `handleDeleteWord mid-word removes the word portion before the cursor`() {
		editText.setText("hello world again")
		editText.setSelection(8) // inside "world", after "wo"

		controller.handleDeleteWord()

		// Ctrl+Backspace semantics: only the chars of the word left of the cursor go.
		assertThat(editText.text.toString()).isEqualTo("hello rld again")
	}

	@Test
	fun `handleDeleteWord re-derives NGB context from the remaining text - C2`() {
		editText.setText("hello world")
		editText.setSelection(11)

		controller.handleDeleteWord()

		assertThat(editText.text.toString()).isEqualTo("hello ")
		// The reconstruction call carries exactly the committed text before the cursor.
		assertThat(fakeCallbacks.reconstructedContexts).containsExactly("hello ")
	}

	@Test
	fun `handleDeleteWord on an empty editor signals an error and changes nothing`() {
		editText.setText("")
		editText.setSelection(0)

		controller.handleDeleteWord()

		assertThat(fakeCallbacks.errorNotificationCount).isEqualTo(1)
		assertThat(editText.text.toString()).isEmpty()
	}

	@Test
	fun `handleDeleteWord with active composition clears the composition not committed text`() {
		editText.setText("hello ")
		editText.setSelection(6)
		ic.setComposingText("wip", 1)
		controller.haveComposing = true

		controller.handleDeleteWord()

		assertThat(editText.text.toString()).isEqualTo("hello ")
		assertThat(controller.haveComposing).isFalse()
		// C2: predictions continue from the committed text still before the cursor.
		assertThat(fakeCallbacks.reconstructedContexts).containsExactly("hello ")
	}

	// ── FakeImeTextCallbacks ────────────────────────────────────────────

	/**
	 * Minimal fake implementation of ImeTextCallbacks for testing.
	 * Records calls and provides configurable return values.
	 */
	@Suppress("EmptyFunctionBlock")
	private class FakeImeTextCallbacks : ImeTextCallbacks {
		override val isJtuiInitialized: Boolean = true
		override val isInputViewShown: Boolean = true
		override fun getAmbiguousSequenceLength(): Int = 0
		override fun getShiftState(): Boolean = false
		override fun getIsManualShift(): Boolean = false
		override fun getAutoCapReason(): AutoCapReason = AutoCapReason.NONE
		override fun isSpellingMode(): Boolean = false
		override fun isNumericMode(): Boolean = false
		override fun getSpeakState(): Boolean = false
		override fun selectedCandidateSuppressesLeadingSpace(): Boolean = false

		override fun setShiftState(shift: Boolean, isManual: Boolean, skipUpdate: Boolean, autoReason: AutoCapReason) {}
		override fun setSpeakState(enabled: Boolean, announce: Boolean) {}
		override fun resetJTUI(shiftState: Boolean, callUpdate: Boolean, isManualShift: Boolean, resetToStartPage: Boolean, autoCapReason: AutoCapReason, preserveAutospace: Boolean) {}
		override fun forceUpdateUi() {}
		override fun withUiSuppressed(block: () -> Unit) {
			block()
		}
		override fun setSkipPostProcessingAfterPullIn(skip: Boolean) {}
		override fun getSelectKeyCount(): Int = 0
		override fun isWordProducible(word: String): Boolean = false
		override fun getCurrentPage(): String = ""
		override fun clearManualShift() {}
		override fun hasPendingAmbiguityWithoutSelect(): Boolean = false
		override fun getImmedCharCount(): Int = 0
		override fun clearImmedCharCount() {}
		override fun replayWordInJtui(word: String, capitalize: Boolean, isAllUpper: Boolean, selectFirstOnMatch: Boolean, suppressUIUpdate: Boolean, precedingText: String?): Boolean {
			replayedPrecedingTexts.add(precedingText)
			return false
		}
		override fun forceUpdateUi(skipComposing: Boolean, selectedCandidate: String?, topCandidate: String?) {}

		// C2 observability: preceding text handed to context reconstruction.
		val reconstructedContexts = mutableListOf<String?>()
		val replayedPrecedingTexts = mutableListOf<String?>()
		override fun reconstructNgbContext(precedingText: String?) {
			reconstructedContexts.add(precedingText)
		}

		private var _editMode = false
		override var isEditMode: Boolean
			get() = _editMode
			set(value) {
				_editMode = value
			}
		override var isNewInputSession: Boolean = false

		var phraseFlowHandle: PhraseFlowHandle? = null
		override val phraseFlow: PhraseFlowHandle? get() = phraseFlowHandle

		override fun speakQueued(text: String) {}
		override fun reuseOrCreatePendingSelection(text: String, type: String) = TtsController.PendingSelection(text, type)
		override fun speakIfEnabled(pending: TtsController.PendingSelection?): Boolean = false
		override fun cancelScheduledSpeak(clearPending: Boolean) {}
		override fun rememberLastSpoken(text: String, type: String) {}
		override fun getPendingSelection(): TtsController.PendingSelection? = null
		override fun setPendingSelection(pending: TtsController.PendingSelection?) {}

		override fun recordSpellNumeric(text: String) {}
		override fun flushSpellNumericIfNeeded(reason: String) {}

		override fun isKnownAbbreviation(word: String): Boolean = false
		override fun isKnownDomain(word: String): Boolean = false
		override fun isAlphaChar(c: Char): Boolean = c.isLetter()
		override fun isWordDbChar(c: Char): Boolean = c in setOf('\'', '\u2019', '-', '.')
		override fun sendDpadEvent(direction: Int, movementMode: Int) {}
		override fun isLineBreak(c: Char): Boolean = TextUtils.isLineBreak(c)

		override fun getAutoRestore(): Boolean = true

		var errorNotificationCount = 0
		override fun errorNotification() {
			errorNotificationCount++
		}
		override fun errorBeep(force: Boolean) {}

		val debugMessages = mutableListOf<String>()
		override fun debugLog(message: String) {
			debugMessages.add(message)
		}
	}
}
