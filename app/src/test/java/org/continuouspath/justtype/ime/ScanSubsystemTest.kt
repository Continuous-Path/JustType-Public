package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.continuouspath.justtype.Constants.KEY_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_AUTOREPEAT_MODE
import org.continuouspath.justtype.Constants.KEY_BEEP_EACH_SCAN_STEP
import org.continuouspath.justtype.Constants.KEY_INITIAL_SCAN_DELAY_INCREASE_SEC
import org.continuouspath.justtype.Constants.KEY_LAYOUT_MODE
import org.continuouspath.justtype.Constants.KEY_SCAN_REPEAT_COUNT
import org.continuouspath.justtype.Constants.KEY_SCAN_STEP_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_SCAN_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_SELECT_KEY_TRIGGERS_SCAN
import org.continuouspath.justtype.Constants.KEY_SHOW_NEXT_KEY
import org.continuouspath.justtype.Constants.KEY_SKIP_KEYS_NO_VALID
import org.continuouspath.justtype.Constants.MODE_OPT
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.continuouspath.justtype.logic.JTUISnapshot
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScanSubsystemTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private lateinit var testScope: TestScope
	private lateinit var subsystem: ScanSubsystem
	private lateinit var repo: SettingsRepository

	// Tracking for HighlightBridge calls
	private val highlightedButtons = mutableMapOf<Int, Int>()
	private val restoredButtons = mutableListOf<Int>()

	// Tracking for KeyActivationSink calls
	private val activatedKeys = mutableListOf<Int>()
	private var selectActivated = 0

	// Tracking for ScanCallbacks calls
	private var flashSwitchBarCount = 0
	private var beepActivationCount = 0
	private var autoLearnedCode: Int? = null

	private val highlightBridge = object : HighlightBridge {
		override fun highlightButton(index: Int, color: Int) {
			highlightedButtons[index] = color
		}

		override fun clearHighlights() {
			highlightedButtons.clear()
		}

		override fun flashButton(index: Int, color: Int, durationMs: Long, onComplete: (() -> Unit)?) {
			// not used by scan subsystem
		}

		override fun highlightButtons(highlights: Map<Int, Int>) {
			highlightedButtons.putAll(highlights)
		}

		override fun restoreButton(index: Int) {
			restoredButtons.add(index)
			highlightedButtons.remove(index)
		}
	}

	private val keySink = object : KeyActivationSink {
		override fun activateKey(index: Int) {
			activatedKeys.add(index)
		}

		override fun activateKeySilent(index: Int) {
			activatedKeys.add(index)
		}

		override fun activateSelect() {
			selectActivated++
		}

		override fun isReady(): Boolean = true
	}

	private val callbacks = object : ScanCallbacks {
		override fun flashSwitchBar() {
			flashSwitchBarCount++
		}

		override fun beepSwitchActivation() {
			beepActivationCount++
		}

		override fun autoLearnSwitchCode(keyCode: Int) {
			autoLearnedCode = keyCode
		}
	}

	@Before
	fun setUp() {
		repo = SettingsRepository.getInstance(RuntimeEnvironment.getApplication())
		SettingsRegistry.getInstance(RuntimeEnvironment.getApplication()) // ScanSubsystem reads SettingsRegistry.get(); init so the test is order-independent
		repo.clearForTesting()
		testScope = TestScope(StandardTestDispatcher())
		subsystem = ScanSubsystem(
			scope = testScope,
			highlightBridge = highlightBridge,
			keySink = keySink,
			callbacks = callbacks,
		)
		// Load defaults
		loadDefaultSettings()
	}

	@After
	fun tearDown() {
		subsystem.destroy()
	}

	private fun loadDefaultSettings() {
		repo.putString(KEY_LAYOUT_MODE, MODE_OPT)
		repo.putInt(KEY_SCAN_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
		repo.putInt(KEY_SCAN_REPEAT_COUNT, 3)
		repo.putFloat(KEY_SCAN_STEP_DELAY_SEC, 1.0f)
		repo.putFloat(KEY_INITIAL_SCAN_DELAY_INCREASE_SEC, 0f)
		repo.putBoolean(KEY_SKIP_KEYS_NO_VALID, false)
		repo.putBoolean(KEY_SHOW_NEXT_KEY, false)
		repo.putBoolean(KEY_AUTOREPEAT_MODE, false)
		repo.putFloat(KEY_AUTOREPEAT_DELAY_SEC, 1.0f)
		repo.putBoolean(KEY_SELECT_KEY_TRIGGERS_SCAN, false)
		repo.putBoolean(KEY_BEEP_EACH_SCAN_STEP, false)
		subsystem.loadSettings(repo)
	}

	// --- Pure logic tests ---

	@Test
	fun `computeScanOrder returns optimized order when layout is optimized`() {
		repo.putString(KEY_LAYOUT_MODE, MODE_OPT)
		subsystem.loadSettings(repo)
		assertThat(subsystem.computeScanOrder()).isEqualTo(listOf(6, 7, 0, 3, 2, 5, 4, 1))
	}

	@Test
	fun `computeScanOrder returns standard order when layout is standard`() {
		repo.putString(KEY_LAYOUT_MODE, "std")
		subsystem.loadSettings(repo)
		assertThat(subsystem.computeScanOrder()).isEqualTo(listOf(6, 0, 3, 5, 2, 4, 7, 1))
	}

	@Test
	fun `buildValidOrder returns full order when skip-invalid is off`() {
		repo.putBoolean(KEY_SKIP_KEYS_NO_VALID, false)
		subsystem.loadSettings(repo)
		subsystem.startScan()
		// buildValidOrder should return the full scan order
		assertThat(subsystem.buildValidOrder()).isEqualTo(subsystem.computeScanOrder())
	}

	// --- State machine tests ---

	@Test
	fun `startScan sets isActive to true`() {
		assertThat(subsystem.isActive).isFalse()
		subsystem.startScan()
		assertThat(subsystem.isActive).isTrue()
	}

	@Test
	fun `startScan highlights first key`() {
		subsystem.startScan()
		assertThat(subsystem.highlightedIndex).isNotNull()
		assertThat(highlightedButtons).isNotEmpty()
	}

	@Test
	fun `stopScan sets isActive to false`() {
		subsystem.startScan()
		subsystem.stopScan()
		assertThat(subsystem.isActive).isFalse()
	}

	@Test
	fun `stopScan clears highlights when requested`() {
		subsystem.startScan()
		assertThat(subsystem.highlightedIndex).isNotNull()
		subsystem.stopScan(clearHighlight = true)
		assertThat(subsystem.highlightedIndex).isNull()
		assertThat(subsystem.nextHighlightIndex).isNull()
	}

	@Test
	fun `stopScan preserves highlights when not requested`() {
		subsystem.startScan()
		val highlighted = subsystem.highlightedIndex
		subsystem.stopScan(clearHighlight = false)
		assertThat(subsystem.highlightedIndex).isEqualTo(highlighted)
	}

	// --- Coroutine timing tests ---

	@Test
	fun `scan step advances after delay`() = testScope.runTest {
		repo.putFloat(KEY_SCAN_STEP_DELAY_SEC, 1.0f)
		repo.putFloat(KEY_INITIAL_SCAN_DELAY_INCREASE_SEC, 0f)
		subsystem.loadSettings(repo)
		subsystem.startScan()
		val firstHighlight = subsystem.highlightedIndex

		// Advance past the delay (1000ms step + 0ms extra = 1000ms)
		advanceTimeBy(1001L)

		// Should have advanced to next key
		assertThat(subsystem.highlightedIndex).isNotEqualTo(firstHighlight)
		subsystem.stopScan()
	}

	@Test
	fun `first step has extra delay`() = testScope.runTest {
		repo.putFloat(KEY_SCAN_STEP_DELAY_SEC, 1.0f)
		repo.putFloat(KEY_INITIAL_SCAN_DELAY_INCREASE_SEC, 0.5f)
		subsystem.loadSettings(repo)
		subsystem.startScan()
		val firstHighlight = subsystem.highlightedIndex

		// Advance past base delay but not extra (1000ms step + 500ms extra)
		advanceTimeBy(1001L)
		assertThat(subsystem.highlightedIndex).isEqualTo(firstHighlight)

		// Now advance past the extra delay
		advanceTimeBy(500L)
		assertThat(subsystem.highlightedIndex).isNotEqualTo(firstHighlight)
		subsystem.stopScan()
	}

	@Test
	fun `scan stops after repeat count passes`() = testScope.runTest {
		repo.putInt(KEY_SCAN_REPEAT_COUNT, 1)
		repo.putFloat(KEY_SCAN_STEP_DELAY_SEC, 0.25f)
		repo.putFloat(KEY_INITIAL_SCAN_DELAY_INCREASE_SEC, 0f)
		subsystem.loadSettings(repo)
		subsystem.startScan()
		assertThat(subsystem.isActive).isTrue()

		// Advance through all 8 keys (first pass) + wrap around triggers stop
		// 8 keys × 250ms = 2000ms for one pass, then stop at wrap
		advanceTimeBy(3000L)
		assertThat(subsystem.isActive).isFalse()
	}

	@Test
	fun `stopScan cancels pending timer`() = testScope.runTest {
		subsystem.startScan()
		subsystem.stopScan()

		// Advance time — scan should NOT advance because it was stopped
		advanceTimeBy(5000L)
		assertThat(subsystem.isActive).isFalse()
	}

	@Test
	fun `destroy cancels all coroutines`() = testScope.runTest {
		subsystem.startScan()
		subsystem.destroy()

		// Advance time — should not crash or advance
		advanceTimeBy(5000L)
	}

	// --- Switch event tests ---

	@Test
	fun `handleSwitchDown starts scan when not active`() {
		assertThat(subsystem.isActive).isFalse()
		subsystem.handleSwitchDown(42)
		assertThat(subsystem.isActive).isTrue()
		subsystem.stopScan()
	}

	@Test
	fun `handleSwitchDown activates current key when active`() = testScope.runTest {
		subsystem.startScan()
		val currentKey = subsystem.highlightedIndex!!

		subsystem.handleSwitchDown(42)
		assertThat(activatedKeys).contains(currentKey)
		subsystem.stopScan()
	}

	@Test
	fun `handleSwitchDown auto-learns switch code when undefined`() {
		subsystem.handleSwitchDown(42)
		assertThat(autoLearnedCode).isEqualTo(42)
	}

	@Test
	fun `handleSwitchDown does not auto-learn when code already set`() {
		repo.putInt(KEY_SCAN_SWITCH_CODE, 99)
		subsystem.loadSettings(repo)
		subsystem.handleSwitchDown(42)
		assertThat(autoLearnedCode).isNull()
		subsystem.stopScan()
	}

	@Test
	fun `handleSwitchUp restarts scan when active`() = testScope.runTest {
		subsystem.startScan()
		assertThat(subsystem.isActive).isTrue()
		subsystem.handleSwitchUp()
		// After switchUp, scan restarts from beginning
		assertThat(subsystem.isActive).isTrue()
		subsystem.stopScan()
	}

	// --- Callback tests ---

	@Test
	fun `handleSwitchDown calls flashSwitchBar`() {
		subsystem.handleSwitchDown(42)
		assertThat(flashSwitchBarCount).isEqualTo(1)
		subsystem.stopScan()
	}

	@Test
	fun `handleSwitchDown calls beepSwitchActivation`() {
		subsystem.handleSwitchDown(42)
		assertThat(beepActivationCount).isEqualTo(1)
		subsystem.stopScan()
	}

	// --- List mode tests ---

	@Test
	fun `select key triggers list mode when enabled`() = testScope.runTest {
		repo.putBoolean(KEY_SELECT_KEY_TRIGGERS_SCAN, true)
		subsystem.loadSettings(repo)
		subsystem.startScan()

		// Advance until we hit the select key (index 6, which is first in optimized order)
		// In optimized order, first key IS the select key (6)
		subsystem.handleSwitchDown(42)
		// First activation should be select activation (activateSelect)
		assertThat(selectActivated).isGreaterThan(0)
		subsystem.stopScan()
	}

	// --- Auto-repeat tests ---

	@Test
	fun `auto-repeat fires at configured delay`() = testScope.runTest {
		repo.putBoolean(KEY_AUTOREPEAT_MODE, true)
		repo.putFloat(KEY_AUTOREPEAT_DELAY_SEC, 0.5f)
		subsystem.loadSettings(repo)
		subsystem.startScan()

		// Activate a key (switch down while scanning)
		activatedKeys.clear()
		subsystem.handleSwitchDown(42)
		val firstActivations = activatedKeys.size

		// Advance past auto-repeat delay
		advanceTimeBy(600L)
		assertThat(activatedKeys.size).isGreaterThan(firstActivations)

		// Cancel auto-repeat to avoid uncompleted coroutines
		subsystem.handleSwitchUp()
	}

	@Test
	fun `handleSwitchUp cancels auto-repeat`() = testScope.runTest {
		repo.putBoolean(KEY_AUTOREPEAT_MODE, true)
		repo.putFloat(KEY_AUTOREPEAT_DELAY_SEC, 0.5f)
		subsystem.loadSettings(repo)
		subsystem.startScan()

		subsystem.handleSwitchDown(42)
		subsystem.handleSwitchUp()
		activatedKeys.clear()

		// Auto-repeat must not fire after release; scan steps go through
		// highlightBridge, so any keySink activation here is auto-repeat.
		advanceTimeBy(2000L)
		assertThat(activatedKeys).isEmpty()
		subsystem.stopScan()
	}

	// --- updateValidMask tests ---

	@Test
	fun `updateValidMask keeps all valid when skip-invalid is off`() {
		repo.putBoolean(KEY_SKIP_KEYS_NO_VALID, false)
		subsystem.loadSettings(repo)

		val snapshot = createSnapshot(
			ambiguousKeyMask = List(8) { true },
			nextLetterHints = setOf('A'),
			highlightNextLetters = true,
		)
		subsystem.updateValidMask(snapshot)

		// Should still include all keys
		assertThat(subsystem.buildValidOrder().size).isEqualTo(8)
	}

	@Test
	fun `updateValidMask filters keys when skip-invalid is on`() {
		repo.putBoolean(KEY_SKIP_KEYS_NO_VALID, true)
		subsystem.loadSettings(repo)
		subsystem.startScan()

		val snapshot = createSnapshot(
			ambiguousKeyMask = List(8) { true },
			nextLetterHints = setOf('A'),
			highlightNextLetters = true,
			keyLabels = listOf("AB", "CD", "AE", "FG", "HI", "JK", "SEL", "AL"),
		)
		subsystem.updateValidMask(snapshot)

		// Only keys containing 'A' (or 'a'), plus select (6) and undo (1) should be valid
		val validOrder = subsystem.buildValidOrder()
		assertThat(validOrder.size).isLessThan(8)
		assertThat(validOrder).contains(6) // select always valid
		assertThat(validOrder).contains(1) // undo always valid
	}

	// Helper to create minimal JTUISnapshot for testing
	private fun createSnapshot(
		ambiguousKeyMask: List<Boolean> = List(8) { false },
		nextLetterHints: Set<Char> = emptySet(),
		accentNextLetterHints: Set<Char> = emptySet(),
		highlightNextLetters: Boolean = false,
		keyLabels: List<String> = List(8) { "" },
		keyLabelGrids: List<List<String>> = List(8) { emptyList() },
	): JTUISnapshot = JTUISnapshot(
		outputBuffer = "",
		ambigBuffer = "",
		selectionListBuffers = emptyList(),
		keyHistoryBuffer = "",
		centerSpace = "",
		keyLabels = keyLabels,
		keyLabelGrids = keyLabelGrids,
		ambigKeyLabels = List(8) { emptyList() },
		baseOutput = "",
		speechString = "",
		customWord = null,
		speakState = false,
		shiftState = false,
		isManualShift = false,
		isSpellingMode = false,
		topCandidateOutput = null,
		selectedCandidateOutput = null,
		topCandidateType = null,
		selectedCandidateType = null,
		highlightNextLetters = highlightNextLetters,
		nextLetterHints = nextLetterHints,
		accentNextLetterHints = accentNextLetterHints,
		ambiguousKeyMask = ambiguousKeyMask,
	)
}
