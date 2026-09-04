package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TtsControllerTest {

	private lateinit var testScope: TestScope
	private lateinit var controller: TtsController
	private var speakStateEnabled: Boolean = true

	@Before
	fun setUp() {
		testScope = TestScope(StandardTestDispatcher())
		controller = TtsController(
			context = RuntimeEnvironment.getApplication(),
			scope = testScope,
			getSpeakState = { speakStateEnabled },
			getInputConnection = { null },
		)
		// Enable all speech categories by default
		controller.speakOutputWordEnabled = true
		controller.speakOutputPhraseEnabled = true
		controller.speakOutputSentenceEnabled = true
		controller.speakPunctNamesEnabled = false
	}

	@After
	fun tearDown() {
		testScope.coroutineContext[Job]?.cancel()
	}

	// ── speakIfEnabled ──────────────────────────────────────────────────

	@Test
	fun `speakIfEnabled returns false for null pending`() {
		assertThat(controller.speakIfEnabled(null)).isFalse()
	}

	@Test
	fun `speakIfEnabled returns false for blank text`() {
		val pending = TtsController.PendingSelection("  ", "X")
		assertThat(controller.speakIfEnabled(pending)).isFalse()
	}

	@Test
	fun `speakIfEnabled returns false when speak state is off`() {
		speakStateEnabled = false
		val pending = TtsController.PendingSelection("hello", "X")
		assertThat(controller.speakIfEnabled(pending)).isFalse()
	}

	@Test
	fun `speakIfEnabled returns false when already spoken`() {
		val pending = TtsController.PendingSelection("hello", "X", spoken = true)
		assertThat(controller.speakIfEnabled(pending)).isFalse()
	}

	@Test
	fun `speakIfEnabled returns false when word output disabled`() {
		controller.speakOutputWordEnabled = false
		val pending = TtsController.PendingSelection("hello", "X")
		assertThat(controller.speakIfEnabled(pending)).isFalse()
	}

	@Test
	fun `speakIfEnabled returns false when phrase output disabled`() {
		controller.speakOutputPhraseEnabled = false
		val pending = TtsController.PendingSelection("hello world", "PH")
		assertThat(controller.speakIfEnabled(pending)).isFalse()
	}

	@Test
	fun `speakIfEnabled returns false when sentence output disabled`() {
		controller.speakOutputSentenceEnabled = false
		val pending = TtsController.PendingSelection("Hello world.", "sentence")
		assertThat(controller.speakIfEnabled(pending)).isFalse()
	}

	@Test
	fun `speakIfEnabled skips punctuation-only when punct names disabled`() {
		controller.speakPunctNamesEnabled = false
		val pending = TtsController.PendingSelection("...", "X")
		assertThat(controller.speakIfEnabled(pending)).isFalse()
	}

	@Test
	fun `speakIfEnabled allows punctuation-only when punct names enabled`() {
		controller.speakPunctNamesEnabled = true
		controller.init()
		val pending = TtsController.PendingSelection("...", "X")
		// Returns true because TTS engine is initialized (even if actual speech fails in test)
		assertThat(controller.speakIfEnabled(pending)).isTrue()
		assertThat(pending.spoken).isTrue()
		controller.shutdown()
	}

	@Test
	fun `speakIfEnabled marks pending as spoken on success`() {
		controller.init()
		val pending = TtsController.PendingSelection("hello", "X")
		val result = controller.speakIfEnabled(pending)
		assertThat(result).isTrue()
		assertThat(pending.spoken).isTrue()
		controller.shutdown()
	}

	@Test
	fun `speakIfEnabled invokes autoCapAfterSpeak callback for terminal punctuation`() {
		controller.init()
		var callbackInvoked = false
		val pending = TtsController.PendingSelection("Hello. ", "X")
		controller.speakIfEnabled(pending) { callbackInvoked = true }
		assertThat(callbackInvoked).isTrue()
		controller.shutdown()
	}

	@Test
	fun `speakIfEnabled does not invoke autoCapAfterSpeak callback for normal text`() {
		controller.init()
		var callbackInvoked = false
		val pending = TtsController.PendingSelection("hello", "X")
		controller.speakIfEnabled(pending) { callbackInvoked = true }
		assertThat(callbackInvoked).isFalse()
		controller.shutdown()
	}

	// ── rememberLastSpoken ──────────────────────────────────────────────

	@Test
	fun `rememberLastSpoken stores text and type`() {
		controller.rememberLastSpoken("hello", "X")
		assertThat(controller.lastSpokenSelectionText).isEqualTo("hello")
		assertThat(controller.lastSpokenSelectionType).isEqualTo("X")
	}

	@Test
	fun `rememberLastSpoken with markPending true sets pending selection`() {
		controller.rememberLastSpoken("hello", "X", markPending = true)
		assertThat(controller.pendingSelection).isNotNull()
		assertThat(controller.pendingSelection?.spoken).isTrue()
	}

	@Test
	fun `rememberLastSpoken with markPending false does not set pending selection`() {
		controller.rememberLastSpoken("hello", "X", markPending = false)
		assertThat(controller.pendingSelection).isNull()
	}

	// ── reuseOrCreatePendingSelection ───────────────────────────────────

	@Test
	fun `reuseOrCreatePendingSelection creates new when no existing`() {
		val pending = controller.reuseOrCreatePendingSelection("hello", "X")
		assertThat(pending.text).isEqualTo("hello")
		assertThat(pending.type).isEqualTo("X")
		assertThat(pending.spoken).isFalse()
	}

	@Test
	fun `reuseOrCreatePendingSelection reuses existing when text matches`() {
		val original = TtsController.PendingSelection("hello", "PH", spoken = true)
		controller.setPendingSelection(original)
		val reused = controller.reuseOrCreatePendingSelection("hello", "X")
		assertThat(reused).isSameInstanceAs(original)
	}

	@Test
	fun `reuseOrCreatePendingSelection creates new when text differs`() {
		val original = TtsController.PendingSelection("hello", "X", spoken = true)
		controller.setPendingSelection(original)
		val created = controller.reuseOrCreatePendingSelection("world", "X")
		assertThat(created).isNotSameInstanceAs(original)
		assertThat(created.text).isEqualTo("world")
	}

	// ── cancelScheduledSpeak ────────────────────────────────────────────

	@Test
	fun `cancelScheduledSpeak with clearPending true clears pending selection`() {
		controller.setPendingSelection(TtsController.PendingSelection("hello", "X"))
		controller.cancelScheduledSpeak(clearPending = true)
		assertThat(controller.pendingSelection).isNull()
	}

	@Test
	fun `cancelScheduledSpeak with clearPending false keeps pending selection`() {
		val pending = TtsController.PendingSelection("hello", "X")
		controller.setPendingSelection(pending)
		controller.cancelScheduledSpeak(clearPending = false)
		assertThat(controller.pendingSelection).isSameInstanceAs(pending)
	}

	// ── scheduleSpeakSelected (coroutine-based) ─────────────────────────

	@Test
	fun `scheduleSpeakSelected does nothing for null text`() {
		controller.speakDelayMs = 500L
		controller.scheduleSpeakSelected(null, "X")
		assertThat(controller.pendingSelection).isNull()
	}

	@Test
	fun `scheduleSpeakSelected does nothing when delay is 0`() {
		controller.speakDelayMs = 0L
		controller.scheduleSpeakSelected("hello", "X")
		assertThat(controller.pendingSelection).isNull()
	}

	@Test
	fun `scheduleSpeakSelected does nothing when word output disabled`() {
		controller.speakDelayMs = 500L
		controller.speakOutputWordEnabled = false
		controller.scheduleSpeakSelected("hello", "X")
		assertThat(controller.pendingSelection).isNull()
	}

	@Test
	fun `scheduleSpeakSelected sets pending selection`() = testScope.runTest {
		controller.speakDelayMs = 500L
		controller.init()
		controller.scheduleSpeakSelected("hello", "X")
		assertThat(controller.pendingSelection).isNotNull()
		assertThat(controller.pendingSelection?.text).isEqualTo("hello")
		assertThat(controller.pendingSelection?.spoken).isFalse()
		controller.shutdown()
	}

	@Test
	fun `scheduleSpeakSelected speaks after delay`() = testScope.runTest {
		controller.speakDelayMs = 500L
		controller.init()
		controller.scheduleSpeakSelected("hello", "X")
		assertThat(controller.pendingSelection?.spoken).isFalse()
		advanceTimeBy(600L)
		assertThat(controller.pendingSelection?.spoken).isTrue()
		controller.shutdown()
	}

	@Test
	fun `scheduleSpeakSelected cancels previous scheduled speak`() = testScope.runTest {
		controller.speakDelayMs = 500L
		controller.init()
		controller.scheduleSpeakSelected("first", "X")
		advanceTimeBy(200L)
		controller.scheduleSpeakSelected("second", "X")
		advanceTimeBy(600L)
		assertThat(controller.pendingSelection?.text).isEqualTo("second")
		assertThat(controller.pendingSelection?.spoken).isTrue()
		controller.shutdown()
	}

	// ── handleSelectionSpeech ───────────────────────────────────────────

	@Test
	fun `handleSelectionSpeech does nothing when speak state is off`() {
		controller.speakDelayMs = 500L
		controller.handleSelectionSpeech(speakState = false, selText = "hello", selType = "X")
		assertThat(controller.pendingSelection).isNull()
	}

	@Test
	fun `handleSelectionSpeech cancels scheduled speak for non-speakable type`() = testScope.runTest {
		controller.speakDelayMs = 500L
		controller.init()
		controller.scheduleSpeakSelected("hello", "X")
		controller.handleSelectionSpeech(speakState = true, selText = "hello", selType = "")
		// Empty type is not speakable, so should cancel
		assertThat(controller.pendingSelection).isNull()
		controller.shutdown()
	}

	// ── spell/numeric accumulation ──────────────────────────────────────

	@Test
	fun `recordSpellNumeric accumulates text`() {
		controller.init()
		controller.recordSpellNumeric("a")
		controller.recordSpellNumeric("b")
		controller.recordSpellNumeric("c")
		// Flush should speak the accumulated "abc"
		controller.flushSpellNumericIfNeeded("test")
		assertThat(controller.lastSpokenSelectionText).isEqualTo("abc")
		controller.shutdown()
	}

	@Test
	fun `recordSpellNumeric ignores blank text`() {
		controller.init()
		controller.recordSpellNumeric("a")
		controller.recordSpellNumeric("  ") // blank — should be skipped
		controller.recordSpellNumeric("b")
		controller.flushSpellNumericIfNeeded("test")
		assertThat(controller.lastSpokenSelectionText).isEqualTo("ab")
		controller.shutdown()
	}

	// ── lifecycle ───────────────────────────────────────────────────────

	@Test
	fun `shutdown cancels scheduled speak jobs`() = testScope.runTest {
		controller.speakDelayMs = 500L
		controller.init()
		controller.scheduleSpeakSelected("hello", "X")
		controller.shutdown()
		advanceTimeBy(600L)
		// Pending selection should still exist but not have been spoken
		// (the job was cancelled before it could run)
		assertThat(controller.pendingSelection?.spoken).isFalse()
	}
}
