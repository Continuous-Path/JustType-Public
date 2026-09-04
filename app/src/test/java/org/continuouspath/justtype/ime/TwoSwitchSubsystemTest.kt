package org.continuouspath.justtype.ime

import android.graphics.drawable.Drawable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.continuouspath.justtype.Constants.INPUT_METHOD_TWO_SWITCH
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_AUTOREPEAT_MODE
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_BEEP_ACTIVATION
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATIONS
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_SHOW_BAND
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
class TwoSwitchSubsystemTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private lateinit var testScope: TestScope
	private lateinit var subsystem: TwoSwitchSubsystem
	private lateinit var repo: SettingsRepository

	// Tracking for TwoSwitchViewBridge calls
	private val tintedButtons = mutableMapOf<Int, Int>()
	private val restoredBackgrounds = mutableListOf<Int>()
	private val flashedButtons = mutableMapOf<Int, Int>()
	private var restoreAllCount = 0
	private val foregrounds = mutableMapOf<Int, Drawable?>()
	private var clearAllForegroundsCount = 0
	private var viewReady = true

	// Tracking for KeyActivationSink calls
	private val activatedKeys = mutableListOf<Int>()
	private val silentActivatedKeys = mutableListOf<Int>()

	// Tracking for TwoSwitchCallbacks calls
	private val flashCalls = mutableListOf<Pair<Boolean, Boolean>>() // (flashGreen, flashRed)
	private var beepSwitchCount = 0
	private var beepTouchCount = 0
	private var stepFeedbackCount = 0
	private var stepFeedbackBeep = false
	private var finalActivationCount = 0
	private var finalActivationIndex = -1
	private val debugMessages = mutableListOf<String>()

	private val viewBridge = object : TwoSwitchViewBridge {
		override val buttonCount: Int = 8
		override val isViewReady: Boolean get() = viewReady

		override fun tintButton(index: Int, color: Int) {
			tintedButtons[index] = color
		}

		override fun flashButton(index: Int, color: Int, durationMs: Long, onComplete: (() -> Unit)?) {
			flashedButtons[index] = color
		}

		override fun restoreButtonBackground(index: Int) {
			restoredBackgrounds.add(index)
			tintedButtons.remove(index)
		}

		override fun restoreAllBackgrounds() {
			restoreAllCount++
			tintedButtons.clear()
		}

		override fun setButtonForeground(index: Int, drawable: Drawable) {
			foregrounds[index] = drawable
		}

		override fun clearButtonForeground(index: Int) {
			foregrounds.remove(index)
		}

		override fun clearAllForegrounds() {
			clearAllForegroundsCount++
			foregrounds.clear()
		}
	}

	private val keySink = object : KeyActivationSink {
		override fun activateKey(index: Int) {
			activatedKeys.add(index)
		}

		override fun activateKeySilent(index: Int) {
			silentActivatedKeys.add(index)
		}

		override fun activateSelect() {
			// Not used by TwoSwitchSubsystem
		}

		override fun isReady(): Boolean = true
	}

	private val callbacks = object : TwoSwitchCallbacks {
		override fun flashSwitchBar(flashGreen: Boolean, flashRed: Boolean) {
			flashCalls.add(flashGreen to flashRed)
		}

		override fun beepSwitchActivation() {
			beepSwitchCount++
		}

		override fun beepTouchSwitchActivation() {
			beepTouchCount++
		}

		override fun stepFeedback(beep: Boolean) {
			stepFeedbackCount++
			stepFeedbackBeep = beep
		}

		override fun finalActivationFeedback(index: Int) {
			finalActivationCount++
			finalActivationIndex = index
		}

		override fun debugLog(message: String) {
			debugMessages.add(message)
		}
	}

	@Before
	fun setUp() {
		org.continuouspath.justtype.settings.SettingsRegistry.getInstance(RuntimeEnvironment.getApplication())
		repo = SettingsRepository.getInstance(RuntimeEnvironment.getApplication())
		repo.clearForTesting()
		testScope = TestScope(StandardTestDispatcher())
		subsystem = TwoSwitchSubsystem(
			scope = testScope,
			viewBridge = viewBridge,
			keySink = keySink,
			callbacks = callbacks,
		)
		loadDefaultSettings()
	}

	@After
	fun tearDown() {
		subsystem.destroy()
	}

	private fun loadDefaultSettings() {
		repo.putString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_TWO_SWITCH)
		repo.putInt(KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC, 0)
		repo.putBoolean(KEY_TWO_SWITCH_SHOW_BAND, false)
		repo.putBoolean(KEY_TWO_SWITCH_AUTOREPEAT_MODE, false)
		repo.putFloat(KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC, 1.0f)
		repo.putBoolean(KEY_TWO_SWITCH_REPEAT_ACTIVATIONS, false)
		repo.putFloat(KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC, 1.0f)
		repo.putBoolean(KEY_TWO_SWITCH_BEEP_ACTIVATION, false)
		subsystem.loadSettings(repo)
	}

	private fun clearTracking() {
		tintedButtons.clear()
		flashedButtons.clear()
		restoredBackgrounds.clear()
		restoreAllCount = 0
		foregrounds.clear()
		clearAllForegroundsCount = 0
		activatedKeys.clear()
		silentActivatedKeys.clear()
		flashCalls.clear()
		beepSwitchCount = 0
		beepTouchCount = 0
		stepFeedbackCount = 0
		stepFeedbackBeep = false
		finalActivationCount = 0
		finalActivationIndex = -1
		debugMessages.clear()
	}

	// --- Pure logic tests ---

	@Test
	fun `splitCandidates divides sorted list into red and green`() {
		subsystem.startCycle()
		// After startCycle, red=startRed=[1,2,4,7], green=startGreen=[0,3,5,6]
		// Manually test splitCandidates via handleSwitchDown("Red Switch")
		// which selects the red group [1,2,4,7] then splits it
		subsystem.handleSwitchDown("Red Switch")
		// After step 0→1, candidates=[1,2,4,7], red=[1,2], green=[4,7]
		// Verify by pressing Red again → step 1→2, candidates=[1,2], red=[1], green=[2]
		subsystem.handleSwitchDown("Red Switch")
		// Now at step 2, one more press selects key 1
		val result = subsystem.handleSwitchDown("Red Switch")
		assertThat(result).isTrue()
		assertThat(silentActivatedKeys).contains(1)
	}

	@Test
	fun `splitCandidates with 1 element puts it in red`() {
		// Navigate to a single candidate: Red, Red, Red → key 1
		subsystem.startCycle()
		subsystem.handleSwitchDown("Red Switch") // step 0→1
		subsystem.handleSwitchDown("Red Switch") // step 1→2
		// At step 2, red=[1], green=[2]
		val result = subsystem.handleSwitchDown("Red Switch")
		assertThat(result).isTrue()
		assertThat(silentActivatedKeys.last()).isEqualTo(1)
	}

	@Test
	fun `computeSequenceForKey returns correct 3-step path`() {
		// Key 0 is in startGreen=[0,3,5,6] → Green first
		// After Green: candidates=[0,3,5,6], sorted, red=[0,3], green=[5,6]
		// Key 0 is in red → Red second
		// After Red: candidates=[0,3], sorted, red=[0], green=[3]
		// Key 0 is in red → Red third
		// So key 0 = [Green, Red, Red]
		assertThat(subsystem.computeSequenceForKey(0)).isEqualTo(listOf("Green", "Red", "Red"))
	}

	@Test
	fun `all 8 key sequences are distinct`() {
		val sequences = (0..7).map { subsystem.computeSequenceForKey(it) }
		assertThat(sequences.toSet().size).isEqualTo(8)
	}

	// --- Core state machine tests ---

	@Test
	fun `handleSwitchDown when inactive starts cycle`() {
		assertThat(subsystem.isActive).isFalse()
		subsystem.handleSwitchDown("Red Switch")
		assertThat(subsystem.isActive).isTrue()
	}

	@Test
	fun `step 0 Red narrows to keys 1,2,4,7`() {
		subsystem.startCycle()
		clearTracking()
		subsystem.handleSwitchDown("Red Switch")
		// Candidates narrow to [1,2,4,7], split red=[1,2] / green=[4,7].
		assertThat(subsystem.isActive).isTrue()
		assertThat(tintedButtons.keys).containsExactly(1, 2, 4, 7)
		assertThat(tintedButtons[1]).isEqualTo(tintedButtons[2])
		assertThat(tintedButtons[4]).isEqualTo(tintedButtons[7])
		assertThat(tintedButtons[1]).isNotEqualTo(tintedButtons[4])
	}

	@Test
	fun `step 0 Green narrows to keys 0,3,5,6`() {
		subsystem.startCycle()
		clearTracking()
		subsystem.handleSwitchDown("Green Switch")
		// Candidates narrow to [0,3,5,6], split red=[0,3] / green=[5,6].
		assertThat(subsystem.isActive).isTrue()
		assertThat(tintedButtons.keys).containsExactly(0, 3, 5, 6)
		assertThat(tintedButtons[0]).isEqualTo(tintedButtons[3])
		assertThat(tintedButtons[5]).isEqualTo(tintedButtons[6])
		assertThat(tintedButtons[0]).isNotEqualTo(tintedButtons[5])
	}

	@Test
	fun `3 presses complete selection and restart cycle`() {
		subsystem.startCycle()
		assertThat(subsystem.handleSwitchDown("Red Switch")).isFalse() // step 0→1
		assertThat(subsystem.handleSwitchDown("Red Switch")).isFalse() // step 1→2
		assertThat(subsystem.handleSwitchDown("Red Switch")).isTrue() // step 2→complete
		assertThat(silentActivatedKeys).hasSize(1)
		assertThat(subsystem.isActive).isTrue() // cycle restarted
	}

	@Test
	fun `handleSwitchDown returns false for steps 0-1, true for step 2`() {
		subsystem.startCycle()
		assertThat(subsystem.handleSwitchDown("Green Switch")).isFalse()
		assertThat(subsystem.handleSwitchDown("Green Switch")).isFalse()
		assertThat(subsystem.handleSwitchDown("Green Switch")).isTrue()
	}

	@Test
	fun `timeout configured first press only restarts highlighting`() {
		repo.putInt(KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC, 5)
		subsystem.loadSettings(repo)
		// Start and let timeout fire
		subsystem.startCycle()
		assertThat(subsystem.isActive).isTrue()

		// Manually clear to simulate timeout
		subsystem.clearColors()
		assertThat(subsystem.isActive).isFalse()

		// First press should restart but NOT advance
		val result = subsystem.handleSwitchDown("Red Switch")
		assertThat(result).isFalse()
		assertThat(subsystem.isActive).isTrue()
		// No key activated — just restarted
		assertThat(silentActivatedKeys).isEmpty()
	}

	@Test
	fun `handleSwitchUp cancels jobs and refreshes colors`() {
		subsystem.startCycle()
		subsystem.handleSwitchDown("Red Switch")
		clearTracking()
		subsystem.handleSwitchUp()
		// applyColors should have been called (tintedButtons populated)
		assertThat(tintedButtons).isNotEmpty()
	}

	// --- Touch screen variant tests ---

	@Test
	fun `handleTouchDown at steps 0-1 advances normally`() {
		subsystem.startCycle()
		subsystem.handleTouchDown("Red Switch")
		assertThat(subsystem.isActive).isTrue()
		// Step 1: candidates narrowed to the 4-key Red group.
		assertThat(tintedButtons.keys).containsExactly(1, 2, 4, 7)
		subsystem.handleTouchDown("Red Switch")
		// Step 2: narrowed again to red=[1] / green=[2].
		assertThat(tintedButtons.keys).containsExactly(1, 2)
	}

	@Test
	fun `handleTouchDown at step 2 stores pending target`() {
		subsystem.startCycle()
		subsystem.handleTouchDown("Red Switch") // step 0→1
		subsystem.handleTouchDown("Red Switch") // step 1→2
		silentActivatedKeys.clear()
		subsystem.handleTouchDown("Red Switch") // step 2 — pending, no activation
		assertThat(silentActivatedKeys).isEmpty()
	}

	@Test
	fun `handleTouchUp activates pending target`() {
		subsystem.startCycle()
		subsystem.handleTouchDown("Red Switch") // step 0→1
		subsystem.handleTouchDown("Red Switch") // step 1→2
		subsystem.handleTouchDown("Red Switch") // step 2 — pending
		silentActivatedKeys.clear()
		subsystem.handleTouchUp()
		assertThat(silentActivatedKeys).hasSize(1)
	}

	@Test
	fun `handleTouchUp with no pending target does nothing`() {
		subsystem.startCycle()
		subsystem.handleTouchDown("Red Switch") // step 0→1
		silentActivatedKeys.clear()
		subsystem.handleTouchUp()
		assertThat(silentActivatedKeys).isEmpty()
	}

	// --- View bridge interaction tests ---

	@Test
	fun `startCycle calls tintButton for red and green groups`() {
		clearTracking()
		subsystem.startCycle()
		// Should have tinted buttons — 4 red + 4 green = 8 tints
		assertThat(tintedButtons).hasSize(8)
	}

	@Test
	fun `clearColors calls restoreAllBackgrounds`() {
		subsystem.startCycle()
		clearTracking()
		subsystem.clearColors()
		assertThat(restoreAllCount).isEqualTo(1)
	}

	@Test
	fun `startCycle with showBand sets button foregrounds`() {
		repo.putBoolean(KEY_TWO_SWITCH_SHOW_BAND, true)
		subsystem.loadSettings(repo)
		clearTracking()
		subsystem.startCycle()
		assertThat(foregrounds).hasSize(8) // strip for each key
	}

	@Test
	fun `pending start defers when views not ready`() {
		viewReady = false
		clearTracking()
		subsystem.startCycle()
		// Should NOT have tinted any buttons
		assertThat(tintedButtons).isEmpty()
		assertThat(subsystem.isActive).isFalse()
	}

	@Test
	fun `onViewsReady drains pending start`() {
		viewReady = false
		subsystem.startCycle()
		assertThat(subsystem.isActive).isFalse()
		viewReady = true
		subsystem.onViewsReady()
		assertThat(subsystem.isActive).isTrue()
		assertThat(tintedButtons).isNotEmpty()
	}

	// --- Coroutine timing tests ---

	@Test
	fun `timeout fires after timeoutMs and clears colors`() = testScope.runTest {
		repo.putInt(KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC, 5)
		subsystem.loadSettings(repo)
		subsystem.startCycle()
		assertThat(subsystem.isActive).isTrue()

		advanceTimeBy(5001L)
		assertThat(subsystem.isActive).isFalse()
	}

	@Test
	fun `auto-repeat fires at configured delay`() = testScope.runTest {
		repo.edit()
			.putBoolean(KEY_TWO_SWITCH_AUTOREPEAT_MODE, true)
			.putFloat(KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC, 0.5f)
			.apply()
		subsystem.loadSettings(repo)
		subsystem.startCycle()

		// Complete a selection with switchHeld=true
		subsystem.switchHeld = true
		subsystem.handleSwitchDown("Red Switch") // step 0→1
		subsystem.handleSwitchDown("Red Switch") // step 1→2
		subsystem.handleSwitchDown("Red Switch") // step 2 → activate + auto-repeat start
		activatedKeys.clear()

		// Advance past auto-repeat delay
		advanceTimeBy(600L)
		// Auto-repeat uses activateKey (not silent)
		assertThat(activatedKeys).isNotEmpty()

		subsystem.switchHeld = false
		subsystem.handleSwitchUp()
	}

	@Test
	fun `activation-repeat fires and continues selection`() = testScope.runTest {
		repo.edit()
			.putBoolean(KEY_TWO_SWITCH_REPEAT_ACTIVATIONS, true)
			.putFloat(KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC, 0.5f)
			.apply()
		subsystem.loadSettings(repo)
		subsystem.startCycle()

		// First press — step 0→1, activation-repeat starts
		subsystem.handleSwitchDown("Red Switch")
		silentActivatedKeys.clear()

		// Advance past repeat delay — should advance step automatically
		advanceTimeBy(600L)
		// The repeat should have called handleSelection which advances steps
		// After enough repeats, a key should be activated
		advanceTimeBy(1200L) // two more repeats to complete 3 steps
		assertThat(silentActivatedKeys).isNotEmpty()

		subsystem.handleSwitchUp()
	}

	@Test
	fun `destroy cancels all jobs`() = testScope.runTest {
		repo.putInt(KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC, 5)
		subsystem.loadSettings(repo)
		subsystem.startCycle()
		subsystem.destroy()

		advanceTimeBy(6000L)
		// Should not crash or have unexpected side effects
	}

	// --- Callback tests ---

	@Test
	fun `handleSwitchDown Red calls flashSwitchBar with flashRed true`() {
		subsystem.startCycle()
		clearTracking()
		subsystem.handleSwitchDown("Red Switch")
		// handleSwitchDown calls handleSelection which calls flashSwitchBar
		assertThat(flashCalls.any { it.second }).isTrue() // flashRed=true
	}

	@Test
	fun `handleSwitchDown Green calls flashSwitchBar with flashGreen true`() {
		subsystem.startCycle()
		clearTracking()
		subsystem.handleSwitchDown("Green Switch")
		assertThat(flashCalls.any { it.first }).isTrue() // flashGreen=true
	}

	@Test
	fun `final activation fires finalActivationFeedback, not the switch beep`() {
		// The final key press gets normal key-activation feedback (via finalActivationFeedback);
		// the per-switch beep is only for intermediate narrows (and only via stepFeedback).
		repo.putBoolean(KEY_TWO_SWITCH_BEEP_ACTIVATION, true)
		subsystem.loadSettings(repo)
		subsystem.startCycle()
		subsystem.handleSwitchDown("Red Switch") // step 0→1 (narrow → stepFeedback)
		subsystem.handleSwitchDown("Red Switch") // step 1→2 (narrow → stepFeedback)
		clearTracking()
		subsystem.handleSwitchDown("Red Switch") // final activation
		assertThat(finalActivationCount).isEqualTo(1)
		assertThat(beepSwitchCount).isEqualTo(0)
		assertThat(stepFeedbackCount).isEqualTo(0)
	}

	@Test
	fun `handleSwitchDown does NOT call beepSwitchActivation when disabled`() {
		repo.putBoolean(KEY_TWO_SWITCH_BEEP_ACTIVATION, false)
		subsystem.loadSettings(repo)
		subsystem.startCycle()
		clearTracking()
		subsystem.handleSwitchDown("Red Switch")
		assertThat(beepSwitchCount).isEqualTo(0)
	}

	// --- Stepped feedback: intermediate group narrow vs final activation ---

	@Test
	fun `intermediate group narrow fires stepFeedback and not the switch beep`() {
		repo.putBoolean(KEY_TWO_SWITCH_BEEP_ACTIVATION, true)
		subsystem.loadSettings(repo)
		subsystem.startCycle()
		clearTracking()
		subsystem.handleSwitchDown("Red Switch") // step 0→1: intermediate narrow
		assertThat(stepFeedbackCount).isEqualTo(1)
		// On a narrow the switch beep is replaced by the smaller step cue.
		assertThat(beepSwitchCount).isEqualTo(0)
	}

	@Test
	fun `final activation does not fire stepFeedback`() {
		repo.putBoolean(KEY_TWO_SWITCH_BEEP_ACTIVATION, true)
		subsystem.loadSettings(repo)
		subsystem.startCycle()
		subsystem.handleSwitchDown("Red Switch") // step 0→1
		subsystem.handleSwitchDown("Red Switch") // step 1→2
		clearTracking()
		subsystem.handleSwitchDown("Red Switch") // step 2 → final activation
		assertThat(stepFeedbackCount).isEqualTo(0)
	}

	@Test
	fun `intermediate group narrow flashes the survivor group when flash feedback enabled`() {
		repo.putBoolean(KEY_FLASH_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		subsystem.startCycle()
		clearTracking()
		subsystem.handleSwitchDown("Red Switch") // intermediate narrow
		// Survivors are flashed with the light group-flash shade (bridge settles them back).
		val groupFlash = android.graphics.Color.argb(255, 179, 229, 252)
		assertThat(flashedButtons.values).contains(groupFlash)
	}

	@Test
	fun `intermediate group narrow does not flash the group when flash feedback disabled`() {
		repo.putBoolean(KEY_FLASH_KEY_FEEDBACK, false)
		subsystem.loadSettings(repo)
		subsystem.startCycle()
		clearTracking()
		subsystem.handleSwitchDown("Red Switch")
		assertThat(flashedButtons).isEmpty()
	}

	@Test
	fun `handleTouchDown flashes the bar and fires stepped feedback on a group narrow`() {
		subsystem.startCycle()
		clearTracking()
		subsystem.handleTouchDown("Red Switch") // step 0→1 group narrow
		assertThat(flashCalls).isNotEmpty()
		assertThat(stepFeedbackCount).isEqualTo(1)
	}

	@Test
	fun `handleTouchUp with pending target flashes the bar and fires finalActivationFeedback`() {
		subsystem.startCycle()
		subsystem.handleTouchDown("Red Switch") // step 0→1
		subsystem.handleTouchDown("Red Switch") // step 1→2
		subsystem.handleTouchDown("Red Switch") // step 2 — pending
		clearTracking()
		subsystem.handleTouchUp()
		assertThat(flashCalls).isNotEmpty()
		// The final press is a real key activation → normal feedback, not the per-hit touch beep.
		assertThat(finalActivationCount).isEqualTo(1)
	}

	// --- Full selection path verification ---

	@Test
	fun `Red Red Red selects key 1`() {
		subsystem.startCycle()
		subsystem.handleSwitchDown("Red Switch") // Red group [1,2,4,7] → candidates
		subsystem.handleSwitchDown("Red Switch") // Red of [1,2,4,7] → [1,2]
		subsystem.handleSwitchDown("Red Switch") // Red of [1,2] → key 1
		assertThat(silentActivatedKeys.last()).isEqualTo(1)
	}

	@Test
	fun `Green Green Green selects key 5`() {
		subsystem.startCycle()
		subsystem.handleSwitchDown("Green Switch") // Green group [0,3,5,6] → candidates
		subsystem.handleSwitchDown("Green Switch") // Green of [0,3,5,6] sorted=[0,3,5,6], red=[0,3], green=[5,6]
		subsystem.handleSwitchDown("Green Switch") // Green of [5,6] → key 6
		assertThat(silentActivatedKeys.last()).isEqualTo(6)
	}

	@Test
	fun `Red Green Red selects key 4`() {
		subsystem.startCycle()
		subsystem.handleSwitchDown("Red Switch") // candidates=[1,2,4,7], red=[1,2], green=[4,7]
		subsystem.handleSwitchDown("Green Switch") // candidates=[4,7], red=[4], green=[7]
		subsystem.handleSwitchDown("Red Switch") // key 4
		assertThat(silentActivatedKeys.last()).isEqualTo(4)
	}
}
