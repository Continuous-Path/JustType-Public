package org.continuouspath.justtype.ime

import android.text.Editable
import android.text.Selection
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * NGB span collapse at the editor seam: after a span pull-in, the collapse
 * returns the span's tail to committed text and re-marks only the tapped word
 * as composing — the field content itself never changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NgbSpanCollapseTest {

	private lateinit var testScope: TestScope
	private lateinit var editText: EditText
	private lateinit var ic: InputConnection
	private lateinit var callbacks: SpanFakeCallbacks
	private lateinit var controller: ImeTextController

	@Before
	fun setUp() {
		testScope = TestScope(StandardTestDispatcher())
		val context = RuntimeEnvironment.getApplication()
		editText = EditText(context)
		// EditText-backed IC with extracted-text support: runPullInFlow reads
		// the full field through getExtractedText.
		ic = object : BaseInputConnection(editText, true) {
			override fun getEditable(): Editable = editText.text
			override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
				text = editText.text.toString()
				startOffset = 0
				selectionStart = Selection.getSelectionStart(editText.text)
				selectionEnd = Selection.getSelectionEnd(editText.text)
			}
		}
		callbacks = SpanFakeCallbacks()
		controller = ImeTextController(
			scope = testScope,
			getInputConnection = { ic },
			getInputEditorInfo = { null },
			callbacks = callbacks,
			ttsController = TtsController(
				context = context,
				scope = testScope,
				getSpeakState = { false },
				getInputConnection = { ic },
			),
		)
	}

	@After
	fun tearDown() {
		testScope.coroutineContext[Job]?.cancel()
	}

	@Test
	fun `collapse leaves the editor text intact and re-marks only the tapped word`() {
		editText.setText("hello world")
		editText.setSelection(5)
		callbacks.spanExtra = " world".length
		callbacks.spanOutput = "hello world"

		assertThat(controller.runPullInFlow("hello", 0, 5)).isTrue()
		shadowOf(android.os.Looper.getMainLooper()).idle()
		// The composing pipeline re-emits the span output; simulate that emit.
		ic.setComposingText("hello world", 1)
		controller.lastComposingSent = "hello world"
		val before = editText.text.toString()

		controller.handleNgbSpanCollapse()

		assertThat(editText.text.toString()).isEqualTo(before)
		assertThat(BaseInputConnection.getComposingSpanStart(editText.text)).isEqualTo(0)
		assertThat(BaseInputConnection.getComposingSpanEnd(editText.text)).isEqualTo(5)
		assertThat(controller.haveComposing).isTrue()
		assertThat(controller.lastComposingSent).isNull() // forces a composing re-send
	}

	@Test
	fun `collapse without a span session is a no-op`() {
		editText.setText("hello")
		editText.setSelection(5)

		controller.handleNgbSpanCollapse()

		assertThat(editText.text.toString()).isEqualTo("hello")
		assertThat(controller.haveComposing).isFalse()
	}

	/** Minimal fake: span probe/activation are scripted, the rest is inert. */
	@Suppress("EmptyFunctionBlock")
	private class SpanFakeCallbacks : ImeTextCallbacks {
		var spanExtra = 0
		var spanOutput: String? = null
		override fun probeNgbSpan(word: String, precedingText: String?, followingText: String): Int = spanExtra
		override fun activateNgbSpan(): String? = spanOutput

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
		override fun withUiSuppressed(block: () -> Unit) = block()
		override fun setSkipPostProcessingAfterPullIn(skip: Boolean) {}
		override fun getSelectKeyCount(): Int = 0
		override fun isWordProducible(word: String): Boolean = false
		override fun getCurrentPage(): String = ""
		override fun clearManualShift() {}
		override fun hasPendingAmbiguityWithoutSelect(): Boolean = false
		override fun getImmedCharCount(): Int = 0
		override fun clearImmedCharCount() {}
		override fun replayWordInJtui(word: String, capitalize: Boolean, isAllUpper: Boolean, selectFirstOnMatch: Boolean, suppressUIUpdate: Boolean, precedingText: String?): Boolean = false
		override fun forceUpdateUi(skipComposing: Boolean, selectedCandidate: String?, topCandidate: String?) {}
		override var isEditMode: Boolean = false
		override var isNewInputSession: Boolean = false
		override val phraseFlow: PhraseFlowHandle? = null
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
		override fun isWordDbChar(c: Char): Boolean = c in setOf('\'', '’', '-', '.')
		override fun sendDpadEvent(direction: Int, movementMode: Int) {}
		override fun isLineBreak(c: Char): Boolean = TextUtils.isLineBreak(c)
		override fun getAutoRestore(): Boolean = true
		override fun errorNotification() {}
		override fun errorBeep(force: Boolean) {}
		override fun debugLog(message: String) {}
	}
}
