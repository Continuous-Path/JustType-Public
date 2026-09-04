package org.continuouspath.justtype.ime

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.data.PhraseEntry
import org.continuouspath.justtype.data.PhraseRepository
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.LayoutMode
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PhraseFlowControllerTest {

	private lateinit var context: Context
	private lateinit var testScope: TestScope
	private lateinit var phraseRepository: PhraseRepository
	private lateinit var controller: PhraseFlowController

	// Callback tracking
	private val resetJtuiCalls = mutableListOf<ResetJtuiArgs>()
	private val phraseFlowModeStates = mutableListOf<Boolean>()
	private val capsLockStates = mutableListOf<Boolean>()
	private val abbrevModeActiveStates = mutableListOf<Boolean>()
	private val abbrevEntries = mutableListOf<Pair<Boolean, Boolean>>()
	private val autoCommitTexts = mutableListOf<String>()
	private val addedEntries = mutableListOf<PhraseEntry>()
	private var scheduleBackupCount = 0
	private var setCurrentPageCount = 0
	private val debugLogs = mutableListOf<String>()
	private var shiftStateValue = false
	private var autoCapReasonValue = AutoCapReason.NONE
	private var layoutModeValue = LayoutMode.Alphabetical

	private data class ResetJtuiArgs(
		val capitalize: Boolean,
		val callUpdateUi: Boolean,
		val isManualShift: Boolean,
		val resetToStartPage: Boolean,
		val autoCapReason: AutoCapReason,
	)

	private val callbacks = object : PhraseFlowCallbacks {
		override fun setPhraseFlowMode(active: Boolean) {
			phraseFlowModeStates.add(active)
		}

		override fun startAbbreviationEntry(useAlpha: Boolean, inPhraseFlow: Boolean) {
			abbrevEntries.add(useAlpha to inPhraseFlow)
		}

		override fun setCapsLock(active: Boolean) {
			capsLockStates.add(active)
		}

		override fun setPhraseAbbrevModeActive(active: Boolean) {
			abbrevModeActiveStates.add(active)
		}

		override fun resetJTUI(
			capitalize: Boolean,
			callUpdateUi: Boolean,
			isManualShift: Boolean,
			resetToStartPage: Boolean,
			autoCapReason: AutoCapReason,
		) {
			resetJtuiCalls.add(
				ResetJtuiArgs(capitalize, callUpdateUi, isManualShift, resetToStartPage, autoCapReason),
			)
		}

		override fun setCurrentPageToStartingPage() {
			setCurrentPageCount++
		}

		override fun getShiftState(): Boolean = shiftStateValue
		override fun getAutoCapReason(): AutoCapReason = autoCapReasonValue
		override fun getLayoutMode(): LayoutMode = layoutModeValue
		override fun addPhraseEntry(entry: PhraseEntry) {
			addedEntries.add(entry)
		}

		override fun autoCommitComposingText(text: String) {
			autoCommitTexts.add(text)
		}

		override fun scheduleBackup() {
			scheduleBackupCount++
		}

		override fun debugLog(message: String) {
			debugLogs.add(message)
		}

		override fun executeOnUiThread(block: () -> Unit) {
			// Inline-execute. Production path is also synchronous from the IME thread.
			block()
		}
	}

	@Before
	fun setUp() {
		context = RuntimeEnvironment.getApplication()
		testScope = TestScope(StandardTestDispatcher())
		phraseRepository = mock()
		controller = PhraseFlowController(testScope, context, phraseRepository, callbacks)
	}

	@After
	fun tearDown() {
		testScope.coroutineContext[Job]?.cancel()
	}

	private fun makePhraseEntry(abbrev: String = "ht", phrase: String = "hi there"): PhraseEntry = PhraseEntry(
		phraseUUID = "uuid-1",
		abbreviation = abbrev,
		phrase = phrase,
		createdAt = 1000L,
		updatedAt = 1000L,
		classMask = 0L,
	)

	private fun consumeStartedActivityIntent(): Intent? {
		val shadow = Shadows.shadowOf(context as android.app.Application)
		return shadow.nextStartedActivity
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 1 — Flow lifecycle
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `initial state is inactive with null handle`() {
		assertThat(controller.isActive).isFalse()
		assertThat(controller.flowHandle).isNull()
	}

	@Test
	fun `startFlow with initial phrase activates and launches overlay`() {
		controller.startFlow("hello")
		assertThat(controller.isActive).isTrue()
		assertThat(controller.flowHandle).isNotNull()
		// resetJTUI called with manual capitalize + reset-to-start.
		assertThat(resetJtuiCalls).hasSize(1)
		val resetArgs = resetJtuiCalls[0]
		assertThat(resetArgs.capitalize).isTrue()
		assertThat(resetArgs.callUpdateUi).isTrue()
		assertThat(resetArgs.isManualShift).isTrue()
		assertThat(resetArgs.resetToStartPage).isTrue()
		assertThat(resetArgs.autoCapReason).isEqualTo(AutoCapReason.MANUAL)
		assertThat(phraseFlowModeStates).contains(true)
		// Activity launched with EXTRA_RESULT_RECEIVER + EXTRA_INITIAL_PHRASE.
		val intent = consumeStartedActivityIntent()
		assertThat(intent).isNotNull()
		assertThat(intent!!.hasExtra(Constants.EXTRA_RESULT_RECEIVER)).isTrue()
		assertThat(intent.getStringExtra(Constants.EXTRA_INITIAL_PHRASE)).isEqualTo("hello")
	}

	@Test
	fun `startFlow without initial phrase omits EXTRA_INITIAL_PHRASE`() {
		controller.startFlow()
		val intent = consumeStartedActivityIntent()
		assertThat(intent).isNotNull()
		assertThat(intent!!.hasExtra(Constants.EXTRA_RESULT_RECEIVER)).isTrue()
		assertThat(intent.hasExtra(Constants.EXTRA_INITIAL_PHRASE)).isFalse()
	}

	@Test
	fun `startFlow while active replaces the existing flow`() {
		controller.startFlow("first")
		// Drain the activity intent to clear shadow state.
		consumeStartedActivityIntent()
		assertThat(controller.isActive).isTrue()
		val firstHandle = controller.flowHandle

		controller.startFlow("second")
		// New flow activated — previous one was cancelled.
		assertThat(controller.isActive).isTrue()
		// setPhraseFlowMode called multiple times (true→false→true).
		assertThat(phraseFlowModeStates.count { it }).isAtLeast(2)
		// flowHandle is replaced (different instance — cancelFlow nulls activeFlow then
		// new ActiveFlow is created).
		assertThat(controller.flowHandle).isNotEqualTo(firstHandle)
	}

	@Test
	fun `cancelCurrentFlow while active deactivates and resets`() {
		controller.startFlow("hello")
		consumeStartedActivityIntent()
		assertThat(controller.isActive).isTrue()

		controller.cancelCurrentFlow()
		assertThat(controller.isActive).isFalse()
		// finishFlow path: setPhraseFlowMode(false) was called.
		assertThat(phraseFlowModeStates).contains(false)
		assertThat(setCurrentPageCount).isAtLeast(1)
	}

	@Test
	fun `cancelCurrentFlow while inactive is no-op`() {
		assertThat(controller.isActive).isFalse()
		controller.cancelCurrentFlow()
		// No callbacks fired.
		assertThat(phraseFlowModeStates).isEmpty()
		assertThat(setCurrentPageCount).isEqualTo(0)
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 2 — cancelOrReset
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `cancelOrReset while active behaves like cancelCurrentFlow`() {
		controller.startFlow("hello")
		consumeStartedActivityIntent()
		controller.cancelOrReset()
		assertThat(controller.isActive).isFalse()
		assertThat(phraseFlowModeStates).contains(false)
	}

	@Test
	fun `cancelOrReset while inactive resets phrase mode`() {
		controller.cancelOrReset()
		// The `?: run { ... }` branch fired.
		assertThat(phraseFlowModeStates).containsExactly(false)
		assertThat(setCurrentPageCount).isEqualTo(1)
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 3 — onDonePressed
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `onDonePressed with Alphabetical layout starts alpha abbrev entry`() {
		layoutModeValue = LayoutMode.Alphabetical
		controller.startFlow("hello")
		consumeStartedActivityIntent()
		// Reset trackers from start.
		abbrevEntries.clear()
		capsLockStates.clear()
		abbrevModeActiveStates.clear()

		controller.onDonePressed()
		assertThat(abbrevEntries).contains(true to true) // useAlpha=true, inPhraseFlow=true
		assertThat(capsLockStates).contains(true)
		assertThat(abbrevModeActiveStates).contains(true)
	}

	@Test
	fun `onDonePressed with Optimized layout starts non-alpha abbrev entry`() {
		layoutModeValue = LayoutMode.Optimized
		controller.startFlow("hello")
		consumeStartedActivityIntent()
		abbrevEntries.clear()

		controller.onDonePressed()
		assertThat(abbrevEntries).contains(false to true)
	}

	@Test
	fun `onDonePressed with no active flow is no-op`() {
		controller.onDonePressed()
		assertThat(abbrevEntries).isEmpty()
		assertThat(capsLockStates).isEmpty()
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 4 — Auto-commit scheduling
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `scheduleAutoCommit with zero delay does not schedule`() = testScope.runTest {
		controller.autoCommitDelayMs = 0L
		controller.scheduleAutoCommit("foo")
		runCurrent()
		advanceTimeBy(1000)
		runCurrent()
		assertThat(autoCommitTexts).isEmpty()
	}

	@Test
	fun `scheduleAutoCommit fires after delay`() = testScope.runTest {
		controller.autoCommitDelayMs = 200L
		controller.scheduleAutoCommit("foo")
		runCurrent()
		advanceTimeBy(199)
		runCurrent()
		assertThat(autoCommitTexts).isEmpty()
		advanceTimeBy(2)
		runCurrent()
		assertThat(autoCommitTexts).containsExactly("foo")
	}

	@Test
	fun `second scheduleAutoCommit cancels first`() = testScope.runTest {
		controller.autoCommitDelayMs = 200L
		controller.scheduleAutoCommit("foo")
		runCurrent()
		controller.scheduleAutoCommit("bar")
		runCurrent()
		advanceTimeBy(250)
		runCurrent()
		// Only the second candidate committed.
		assertThat(autoCommitTexts).containsExactly("bar")
	}

	@Test
	fun `cancelAutoCommit prevents commit`() = testScope.runTest {
		controller.autoCommitDelayMs = 200L
		controller.scheduleAutoCommit("foo")
		runCurrent()
		controller.cancelAutoCommit()
		advanceTimeBy(1000)
		runCurrent()
		assertThat(autoCommitTexts).isEmpty()
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 5 — handleUiAutoCommit decision matrix
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `handleUiAutoCommit with active flow cancels`() = testScope.runTest {
		controller.autoCommitDelayMs = 200L
		controller.scheduleAutoCommit("foo")
		runCurrent()
		controller.startFlow("hello")
		consumeStartedActivityIntent()
		// Active flow + non-suspend → cancel.
		controller.handleUiAutoCommit(suspendCommit = false, selectedType = "PH", selectedCandidate = "x")
		advanceTimeBy(500)
		runCurrent()
		// "foo" was scheduled BEFORE active; once active became true, handleUiAutoCommit
		// path triggers cancelAutoCommit.
		assertThat(autoCommitTexts).doesNotContain("foo")
	}

	@Test
	fun `handleUiAutoCommit with suspendCommit cancels`() = testScope.runTest {
		controller.autoCommitDelayMs = 200L
		controller.handleUiAutoCommit(suspendCommit = true, selectedType = "PH", selectedCandidate = "x")
		advanceTimeBy(500)
		runCurrent()
		assertThat(autoCommitTexts).isEmpty()
	}

	@Test
	fun `handleUiAutoCommit with PH type and candidate schedules`() = testScope.runTest {
		controller.autoCommitDelayMs = 100L
		controller.handleUiAutoCommit(suspendCommit = false, selectedType = "PH", selectedCandidate = "abc")
		advanceTimeBy(150)
		runCurrent()
		assertThat(autoCommitTexts).containsExactly("abc")
	}

	@Test
	fun `handleUiAutoCommit with non-PH type cancels`() = testScope.runTest {
		controller.autoCommitDelayMs = 100L
		controller.handleUiAutoCommit(suspendCommit = false, selectedType = "WD", selectedCandidate = "abc")
		advanceTimeBy(150)
		runCurrent()
		assertThat(autoCommitTexts).isEmpty()
	}

	@Test
	fun `handleUiAutoCommit with empty candidate cancels`() = testScope.runTest {
		controller.autoCommitDelayMs = 100L
		controller.handleUiAutoCommit(suspendCommit = false, selectedType = "PH", selectedCandidate = "")
		advanceTimeBy(150)
		runCurrent()
		assertThat(autoCommitTexts).isEmpty()
	}

	@Test
	fun `handleUiAutoCommit with zero delay cancels`() = testScope.runTest {
		controller.autoCommitDelayMs = 0L
		controller.handleUiAutoCommit(suspendCommit = false, selectedType = "PH", selectedCandidate = "abc")
		advanceTimeBy(1000)
		runCurrent()
		assertThat(autoCommitTexts).isEmpty()
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 6 — ResultReceiver paths
	// ──────────────────────────────────────────────────────────────────

	private fun extractResultReceiver(intent: Intent): ResultReceiver? =
		@Suppress("DEPRECATION")
		intent.getParcelableExtra(Constants.EXTRA_RESULT_RECEIVER)

	@Test
	fun `RESULT_OK with phrase and abbrev triggers repository add`() {
		whenever(phraseRepository.add(any(), any())).thenReturn(makePhraseEntry("ht", "hi there"))
		controller.startFlow()
		val intent = consumeStartedActivityIntent()
		val receiver = extractResultReceiver(intent!!)!!
		val bundle = Bundle().apply {
			putString(Constants.EXTRA_PHRASE, "hi there")
			putString(Constants.EXTRA_ABBREV, "ht")
		}
		receiver.send(RESULT_OK, bundle)
		Shadows.shadowOf(Looper.getMainLooper()).idle()
		verify(phraseRepository).add("ht", "hi there")
		assertThat(addedEntries).hasSize(1)
		assertThat(scheduleBackupCount).isEqualTo(1)
		assertThat(controller.isActive).isFalse()
	}

	@Test
	fun `RESULT_OK with empty phrase skips repository add`() {
		controller.startFlow()
		val intent = consumeStartedActivityIntent()
		val receiver = extractResultReceiver(intent!!)!!
		val bundle = Bundle().apply {
			putString(Constants.EXTRA_PHRASE, "")
			putString(Constants.EXTRA_ABBREV, "ht")
		}
		receiver.send(RESULT_OK, bundle)
		Shadows.shadowOf(Looper.getMainLooper()).idle()
		verifyNoInteractions(phraseRepository)
		assertThat(addedEntries).isEmpty()
		assertThat(controller.isActive).isFalse()
	}

	@Test
	fun `non-OK result code skips add and finishes flow`() {
		controller.startFlow()
		val intent = consumeStartedActivityIntent()
		val receiver = extractResultReceiver(intent!!)!!
		receiver.send(android.app.Activity.RESULT_CANCELED, null)
		Shadows.shadowOf(Looper.getMainLooper()).idle()
		verifyNoInteractions(phraseRepository)
		assertThat(controller.isActive).isFalse()
	}

	@Test
	fun `finishFlow is idempotent`() {
		whenever(phraseRepository.add(any(), any())).thenReturn(makePhraseEntry())
		controller.startFlow()
		val intent = consumeStartedActivityIntent()
		val receiver = extractResultReceiver(intent!!)!!
		val bundle = Bundle().apply {
			putString(Constants.EXTRA_PHRASE, "hi")
			putString(Constants.EXTRA_ABBREV, "h")
		}
		receiver.send(RESULT_OK, bundle)
		Shadows.shadowOf(Looper.getMainLooper()).idle()
		val firstFinishCount = phraseFlowModeStates.count { !it }
		// Send again — should be silently ignored.
		receiver.send(RESULT_OK, bundle)
		Shadows.shadowOf(Looper.getMainLooper()).idle()
		val secondFinishCount = phraseFlowModeStates.count { !it }
		assertThat(secondFinishCount).isEqualTo(firstFinishCount)
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 7 — destroy + handle pass-through
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `destroy cancels auto-commit and clears active flow`() = testScope.runTest {
		controller.autoCommitDelayMs = 200L
		controller.scheduleAutoCommit("foo")
		runCurrent()
		controller.destroy()
		advanceTimeBy(500)
		runCurrent()
		// No commit — destroy cancelled it.
		assertThat(autoCommitTexts).isEmpty()
		// Subsequent calls are safe no-ops.
		controller.onDonePressed()
		assertThat(controller.isActive).isFalse()
	}

	@Test
	fun `flowHandle returns ActiveFlow with all-false consume overrides`() {
		controller.startFlow()
		consumeStartedActivityIntent()
		val handle = controller.flowHandle!!
		assertThat(handle.consumeImmediate("x")).isFalse()
		assertThat(handle.consumeNumeric("x")).isFalse()
		assertThat(handle.consumeSpelling("x")).isFalse()
		assertThat(handle.handleBackspace()).isFalse()
	}
}
