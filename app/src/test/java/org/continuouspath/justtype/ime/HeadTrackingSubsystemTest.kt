package org.continuouspath.justtype.ime

import android.graphics.drawable.Drawable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DEADZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DEBUG_OVERLAY
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXITZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXIT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_KEY_ACT_THRESHOLD
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_PITCH_SCALE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_RESPONSE_CURVE
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HeadTrackingSubsystemTest {

	private lateinit var testScope: TestScope
	private lateinit var subsystem: HeadTrackingSubsystem
	private lateinit var repo: SettingsRepository

	// ViewBridge tracking
	private val buttonDrawables = mutableMapOf<Int, Int>()
	private val restoredBackgrounds = mutableListOf<Int>()
	private var centerLabelDrawable: Int? = null
	private var centerLabelRestored = false
	private var viewReady = true

	// Foreground tracking (debug overlay)
	private val buttonForegrounds = mutableMapOf<Int, Int>()
	private var centerLabelForeground: Int? = null
	private val restoredButtonForegrounds = mutableMapOf<Int, Drawable?>()
	private var restoredCenterLabelForeground: Drawable? = null
	private var centerLabelForegroundRestored = false

	// Border / selection list tracking
	private var keyboardBorderShown = false
	private var selectionListBorderShown = false
	private var selectionListPausedText: String? = null
	private var selectionListPausedHidden = false
	private var selectionListStylingReset = false

	// Callback tracking
	private var beepCount = 0
	private var buttonPressedOctants = mutableListOf<Int>()
	private var wldGenerations = mutableListOf<Long>()
	private var externalButtonInputs = mutableListOf<Int>()
	private var inputViewShown = true
	private var jtuiInitialized = true
	private var beepEnabled = true
	private var forceUpdateUiCount = 0
	private var onKeyboardExitCount = 0
	private var onKeyboardReEntryCount = 0
	private val debugMessages = mutableListOf<String>()

	private val viewBridge = object : HeadTrackingViewBridge {
		override val buttonCount: Int = 8
		override val isViewReady: Boolean get() = viewReady

		override fun setButtonDrawable(index: Int, drawableResId: Int) {
			buttonDrawables[index] = drawableResId
		}

		override fun restoreButtonBackground(index: Int) {
			restoredBackgrounds.add(index)
			buttonDrawables.remove(index)
		}

		override fun setCenterLabelDrawable(drawableResId: Int) {
			centerLabelDrawable = drawableResId
		}

		override fun restoreCenterLabelBackground() {
			centerLabelDrawable = null
			centerLabelRestored = true
		}

		override fun setButtonForeground(index: Int, drawableResId: Int) {
			buttonForegrounds[index] = drawableResId
		}

		override fun setCenterLabelForeground(drawableResId: Int) {
			centerLabelForeground = drawableResId
		}

		override fun getButtonForeground(index: Int): Drawable? = null
		override fun getCenterLabelForeground(): Drawable? = null

		override fun restoreButtonForeground(index: Int, drawable: Drawable?) {
			restoredButtonForegrounds[index] = drawable
			buttonForegrounds.remove(index)
		}

		override fun restoreCenterLabelForeground(drawable: Drawable?) {
			restoredCenterLabelForeground = drawable
			centerLabelForeground = null
			centerLabelForegroundRestored = true
		}

		override fun showKeyboardBorder(show: Boolean) {
			keyboardBorderShown = show
		}

		override fun showSelectionListBorder() {
			selectionListBorderShown = true
		}

		override fun hideSelectionListBorder() {
			selectionListBorderShown = false
		}

		override fun showSelectionListPaused(text: String) {
			selectionListPausedText = text
		}

		override fun hideSelectionListPaused() {
			selectionListPausedHidden = true
		}

		override fun resetSelectionListStyling() {
			selectionListStylingReset = true
		}
	}

	private var correctToneCount = 0
	private var cancelToneCount = 0

	private val callbacks = object : HeadTrackingCallbacks {
		override fun playActivationBeep() {
			beepCount++
		}
		override fun playCorrectTone() {
			correctToneCount++
		}
		override fun playCancelTone() {
			cancelToneCount++
		}
		override fun buttonPressed(octant: Int, shouldAbort: () -> Boolean) {
			buttonPressedOctants.add(octant)
		}
		override fun setWldGeneration(generation: Long) {
			wldGenerations.add(generation)
		}
		override fun handleExternalButtonInput(buttonIndex: Int) {
			externalButtonInputs.add(buttonIndex)
		}
		override val isInputViewShown: Boolean get() = inputViewShown
		override val isJtuiInitialized: Boolean get() = jtuiInitialized
		override val isBeepEnabled: Boolean get() = beepEnabled
		override val isCorrectionBeepEnabled: Boolean get() = beepEnabled
		override val isCorrectionFlashRedEnabled: Boolean get() = beepEnabled
		override fun forceUpdateUi() {
			forceUpdateUiCount++
		}
		override fun onKeyboardExit() {
			onKeyboardExitCount++
		}
		override fun onKeyboardReEntry() {
			onKeyboardReEntryCount++
		}
		override fun debugLog(message: String) {
			debugMessages.add(message)
		}
		override fun onHeadTrackingUnavailable() {
			unavailableCount++
		}

		// Recovery needs a bus-injected frame — instrumented territory, not
		// recorded here so the fake doesn't imply coverage that doesn't exist.
		override fun onHeadTrackingRecovered() = Unit
	}

	private var unavailableCount = 0

	@Before
	fun setUp() {
		val testDispatcher = StandardTestDispatcher()
		testScope = TestScope(testDispatcher)
		val ctx = RuntimeEnvironment.getApplication()
		SettingsRegistry.getInstance(ctx) // registry-aware getFloat needs this initialized
		repo = SettingsRepository.getInstance(ctx)
		repo.clearForTesting()

		// Set default HT settings
		repo.putFloat(KEY_HEADTRACKING_DEADZONE, 0.25f)
		repo.putFloat(KEY_HEADTRACKING_ACTIVEZONE, 0.37f)
		repo.putFloat(KEY_HEADTRACKING_EXITZONE, 0.80f)
		repo.putInt(KEY_HEADTRACKING_KEY_ACT_THRESHOLD, 10)
		repo.putFloat(KEY_HEADTRACKING_CORNER_BIAS, 1.3f)
		repo.putInt(KEY_HEADTRACKING_EXIT_DELAY_MS, 3500)
		repo.putFloat(KEY_HEADTRACKING_PITCH_SCALE, 1.1f)
		repo.putFloat(KEY_HEADTRACKING_RESPONSE_CURVE, 1.0f)
		repo.putBoolean(KEY_HEADTRACKING_DEBUG_OVERLAY, false)
		repo.putString(KEY_INPUT_METHOD_PRIMARY, "head_tracking")

		subsystem = HeadTrackingSubsystem(
			scope = testScope,
			context = ctx,
			viewBridge = viewBridge,
			callbacks = callbacks,
			settingsRepo = repo,
			// Keeps frame processing + word search inside the TestScope's virtual time
			processingDispatcher = testDispatcher,
			wldDispatcher = testDispatcher,
		)
		subsystem.loadSettings(repo)
		subsystem.loadCachedPrefs("head_tracking", repo)
	}

	@After
	fun tearDown() {
		if (::subsystem.isInitialized) subsystem.destroy()
		testScope.coroutineContext[Job]?.cancel()
		SettingsRepository.resetInstanceForTesting()
	}

	// ── Highlight tests ───────────────────────────────────────────────

	@Test
	fun `clearHighlight does nothing when already cleared`() {
		subsystem.clearHighlight()
		assertThat(restoredBackgrounds).isEmpty()
		assertThat(centerLabelRestored).isFalse()
	}

	// ── Debug overlay tests ───────────────────────────────────────────

	@Test
	fun `updateDebugOverlay sets foreground on button`() {
		subsystem.updateDebugOverlay(3, false)
		assertThat(buttonForegrounds).containsKey(3)
		assertThat(buttonForegrounds[3]).isEqualTo(R.drawable.debug_outline_transparent)
	}

	@Test
	fun `updateDebugOverlay uses solid drawable in activation zone`() {
		subsystem.updateDebugOverlay(5, true)
		assertThat(buttonForegrounds[5]).isEqualTo(R.drawable.debug_outline_solid)
	}

	@Test
	fun `updateDebugOverlay on center label`() {
		subsystem.updateDebugOverlay(null, false)
		assertThat(centerLabelForeground).isEqualTo(R.drawable.debug_outline_transparent)
	}

	@Test
	fun `clearDebugOverlay restores previous foreground`() {
		subsystem.updateDebugOverlay(3, false)
		assertThat(buttonForegrounds).containsKey(3)

		subsystem.clearDebugOverlay()
		assertThat(buttonForegrounds).doesNotContainKey(3)
	}

	@Test
	fun `updateDebugOverlay transitions between button and center`() {
		subsystem.updateDebugOverlay(3, false)
		assertThat(buttonForegrounds).containsKey(3)

		subsystem.updateDebugOverlay(null, false)
		// Previous button foreground should be restored
		assertThat(buttonForegrounds).doesNotContainKey(3)
		assertThat(centerLabelForeground).isNotNull()
	}

	// ── Response curve tests ──────────────────────────────────────────

	@Test
	fun `applyResponseCurve passthrough with exponent 1`() {
		assertThat(subsystem.applyResponseCurve(0.5f, 1.0f)).isEqualTo(0.5f)
		assertThat(subsystem.applyResponseCurve(-0.3f, 1.0f)).isEqualTo(-0.3f)
	}

	@Test
	fun `applyResponseCurve preserves sign`() {
		val result = subsystem.applyResponseCurve(-0.5f, 2.0f)
		assertThat(result).isLessThan(0f)
	}

	@Test
	fun `applyResponseCurve zero returns zero`() {
		assertThat(subsystem.applyResponseCurve(0f, 2.0f)).isEqualTo(0f)
	}

	@Test
	fun `applyResponseCurve exponent greater than 1 dampens small values`() {
		val result = subsystem.applyResponseCurve(0.5f, 2.0f)
		assertThat(result).isLessThan(0.5f)
	}

	// ── Anti-lockout watchdog ─────────────────────────────────────────

	private fun runWatchdog(stallSeconds: Int) {
		// The watchdog launches on Dispatchers.Main and measures stalls with the
		// injected nano clock; drive both in lockstep with virtual time.
		var fakeNs = 0L
		subsystem.nanoClock = { fakeNs }
		Dispatchers.setMain(StandardTestDispatcher(testScope.testScheduler))
		try {
			subsystem.startProcessor()
			repeat(stallSeconds) {
				fakeNs += 1_000_000_000L
				testScope.testScheduler.advanceTimeBy(1_000)
				testScope.testScheduler.runCurrent()
			}
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `watchdog fires onHeadTrackingUnavailable once when frames stall while active`() {
		inputViewShown = true
		runWatchdog(stallSeconds = 12)
		// 8s stall threshold crossed once; the one-shot latch stops repeats.
		assertThat(unavailableCount).isEqualTo(1)
	}

	@Test
	fun `watchdog stays quiet while the keyboard is hidden`() {
		inputViewShown = false
		runWatchdog(stallSeconds = 20)
		assertThat(unavailableCount).isEqualTo(0)
	}

	@Test
	fun `watchdog stays quiet when head tracking is not the active method`() {
		inputViewShown = true
		repo.putString(KEY_INPUT_METHOD_PRIMARY, "none")
		runWatchdog(stallSeconds = 20)
		assertThat(unavailableCount).isEqualTo(0)
	}

	// ── onInputFinished tests ─────────────────────────────────────────

	@Test
	fun `onInputFinished clears borders and resets styling`() {
		subsystem.onInputFinished()
		assertThat(keyboardBorderShown).isFalse()
		assertThat(selectionListBorderShown).isFalse()
		assertThat(selectionListStylingReset).isTrue()
	}

	// ── onWindowShown tests ───────────────────────────────────────────

	@Test
	fun `onWindowShown resets selection list styling`() {
		subsystem.onWindowShown()
		assertThat(selectionListStylingReset).isTrue()
	}

	// ── View not ready tests ──────────────────────────────────────────

	@Test
	fun `clearHighlight does nothing when view not ready`() {
		viewReady = false
		subsystem.clearHighlight()
		assertThat(restoredBackgrounds).isEmpty()
	}

	@Test
	fun `updateDebugOverlay does nothing when view not ready`() {
		viewReady = false
		subsystem.updateDebugOverlay(3, false)
		assertThat(buttonForegrounds).isEmpty()
	}

	// ── destroy tests ─────────────────────────────────────────────────

	@Test
	fun `destroy can be called without prior startAndRegisterReceivers`() {
		// Should not crash even without threads started
		subsystem.destroy()
	}
}
