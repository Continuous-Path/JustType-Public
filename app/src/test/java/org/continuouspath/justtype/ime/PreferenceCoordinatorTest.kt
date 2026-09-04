package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.INPUT_METHOD_DIRECTIONAL_SELECTION
import org.continuouspath.justtype.Constants.INPUT_METHOD_DIRECT_SELECTION
import org.continuouspath.justtype.Constants.INPUT_METHOD_NONE
import org.continuouspath.justtype.Constants.INPUT_METHOD_SINGLE_SWITCH
import org.continuouspath.justtype.Constants.INPUT_METHOD_TWO_SWITCH
import org.continuouspath.justtype.Constants.KEY_APP_LANGUAGE
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_DIRECT_SELECTION_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_SIZE_RATIO
import org.continuouspath.justtype.Constants.KEY_LAYOUT_MODE
import org.continuouspath.justtype.Constants.KEY_PHRASE_AUTO_OUTPUT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_REQUIRE_ACCENTED_KEYS
import org.continuouspath.justtype.Constants.KEY_SCAN_LAYOUT_SIZE
import org.continuouspath.justtype.Constants.KEY_SHOW_ACCENTED_KEYS
import org.continuouspath.justtype.Constants.KEY_TYPING_LANGUAGE
import org.continuouspath.justtype.Constants.MODE_ALPHA
import org.continuouspath.justtype.Constants.MODE_OPT
import org.continuouspath.justtype.Constants.PREFS_KEY_LANGUAGE_TTS_VOICE
import org.continuouspath.justtype.logic.LayoutMode
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Characterization tests for [PreferenceCoordinator].
 *
 * Locks down the input-method routing, clamping, and per-key dispatch
 * behavior used by JustTypeIME. Production code is unchanged — these
 * tests assert *current* behavior so future refactors don't drift.
 */
@Suppress("DEPRECATION") // tests intentionally exercise legacy KEY_INPUT_METHOD
@RunWith(RobolectricTestRunner::class)
class PreferenceCoordinatorTest {

	private lateinit var repo: SettingsRepository
	private lateinit var coordinator: PreferenceCoordinator

	private val scanSubsystem: ScanSubsystem = mock()
	private val twoSwitchSubsystem: TwoSwitchSubsystem = mock()
	private val joystickSubsystem: JoystickSubsystem = mock()
	private val mouseJoystickSubsystem: MouseJoystickSubsystem = mock()
	private val headTrackingSubsystem: HeadTrackingSubsystem = mock()
	private val phraseFlowController: PhraseFlowController = mock()
	private val ttsController: TtsController = mock()
	private val imeTextController: ImeTextController = mock()

	// Late-init subsystem stand-ins (callbacks return these from their getters).
	private val externalSwitchHandler: ExternalSwitchHandler = mock()
	private val overlayCoordinator: OverlayCoordinator = mock()

	// Tracking state for the FakeCallbacks below.
	private var capturedState: PreferenceState? = null
	private var recreateCount = 0
	private var forceUpdateCount = 0
	private var reinitJtuiCount = 0
	private var applyTtsCount = 0
	private var keyHistoryVisibilityCount = 0
	private var keyHistoryHeightCount = 0
	private var keyHistoryShrinkCount = 0
	private var updateButtonClickListenersCount = 0
	private var setActiveLayoutCalls = mutableListOf<Boolean>()
	private var useScanLayoutValue: Boolean = false
	private var applyKeyboardSizeCount = 0
	private val executedBlocks = mutableListOf<() -> Unit>()
	private val debugMessages = mutableListOf<String>()

	private var jtuiInitialized = true
	private var inSettings = false
	private var externalSwitchHandlerProvider: () -> ExternalSwitchHandler? = { externalSwitchHandler }
	private var overlayCoordinatorProvider: () -> OverlayCoordinator? = { overlayCoordinator }

	// Narrow-JTUI-operation recorders (replace direct verify(jtui)... after 3.11)
	private var lastShowNextLetterHints: Boolean? = null
	private var lastLayoutMode: LayoutMode? = null
	private var updateLocaleContextCount = 0

	private val callbacks = object : PreferenceCoordinatorCallbacks {
		override val isJtuiInitialized: Boolean get() = jtuiInitialized
		override val isInSettingsMode: Boolean get() = inSettings

		override fun getExternalSwitchHandler(): ExternalSwitchHandler? = externalSwitchHandlerProvider()
		override fun getOverlayCoordinator(): OverlayCoordinator? = overlayCoordinatorProvider()
		override fun setShowNextLetterHints(enabled: Boolean) {
			lastShowNextLetterHints = enabled
		}
		override fun setLayoutMode(mode: LayoutMode) {
			lastLayoutMode = mode
		}
		override fun updateLocaleContext(context: android.content.Context) {
			updateLocaleContextCount++
		}

		override fun applyPreferenceState(state: PreferenceState) {
			capturedState = state
		}

		override fun applyKeyHistoryVisibility() {
			keyHistoryVisibilityCount++
		}
		override fun applyKeyHistoryHeight(repo: SettingsRepository) {
			keyHistoryHeightCount++
		}
		override fun applyKeyHistoryShrinkToFit() {
			keyHistoryShrinkCount++
		}
		override fun updateButtonClickListeners() {
			updateButtonClickListenersCount++
		}
		override fun setActiveLayout(useScan: Boolean) {
			setActiveLayoutCalls.add(useScan)
		}
		override fun useScanLayout(): Boolean = useScanLayoutValue

		override fun recreateInputView() {
			recreateCount++
		}
		override fun applyKeyboardSize() {
			applyKeyboardSizeCount++
		}
		override fun forceUpdateUi() {
			forceUpdateCount++
		}
		override fun reinitJtuiInBackground() {
			reinitJtuiCount++
		}
		override fun applyTtsForActiveLanguage() {
			applyTtsCount++
		}

		override fun applyUiVoice() = Unit

		override fun debugLog(message: String) {
			debugMessages.add(message)
		}
		override fun executeOnUiThread(block: () -> Unit) {
			executedBlocks.add(block)
		}
	}

	@Before
	fun setUp() {
		org.continuouspath.justtype.settings.SettingsRegistry.getInstance(RuntimeEnvironment.getApplication())
		repo = SettingsRepository.getInstance(RuntimeEnvironment.getApplication())
		repo.clearForTesting()
		coordinator = PreferenceCoordinator(
			context = RuntimeEnvironment.getApplication(),
			scanSubsystem = scanSubsystem,
			twoSwitchSubsystem = twoSwitchSubsystem,
			joystickSubsystem = joystickSubsystem,
			mouseJoystickSubsystem = mouseJoystickSubsystem,
			headTrackingSubsystem = headTrackingSubsystem,
			phraseFlowController = phraseFlowController,
			ttsController = ttsController,
			imeTextController = imeTextController,
			callbacks = callbacks,
		)
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	// Setter helpers — use commit() (synchronous DataStore write) instead of
	// the async put*() path so subsequent loadAndApplyAll() reads see our value.
	private fun setString(key: String, value: String) {
		repo.edit().putString(key, value).commit()
	}

	private fun setBoolean(key: String, value: Boolean) {
		repo.edit().putBoolean(key, value).commit()
	}

	private fun setInt(key: String, value: Int) {
		repo.edit().putInt(key, value).commit()
	}

	// ── Group 1: isCriticalSettingChange ────────────────────────────────

	@Test
	fun `isCriticalSettingChange returns true for all critical keys`() {
		val criticalKeys = listOf(
			KEY_INPUT_METHOD,
			KEY_INPUT_METHOD_PRIMARY,
			KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED,
			KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED,
			KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED,
			KEY_LAYOUT_MODE,
			KEY_SCAN_LAYOUT_SIZE,
			KEY_KEYBOARD_SIZE_RATIO,
			KEY_APP_LANGUAGE,
			KEY_TYPING_LANGUAGE,
		)
		for (key in criticalKeys) {
			assertThat(coordinator.isCriticalSettingChange(key)).isTrue()
		}
	}

	@Test
	fun `isCriticalSettingChange returns false for non-critical keys`() {
		assertThat(coordinator.isCriticalSettingChange(KEY_BEEP_KEY_FEEDBACK)).isFalse()
		assertThat(coordinator.isCriticalSettingChange(KEY_FLASH_KEY_FEEDBACK)).isFalse()
		assertThat(coordinator.isCriticalSettingChange(KEY_PHRASE_AUTO_OUTPUT_DELAY_MS)).isFalse()
	}

	@Test
	fun `isCriticalSettingChange returns false for empty string`() {
		assertThat(coordinator.isCriticalSettingChange("")).isFalse()
	}

	// ── Group 2: loadAndApplyAll — effectiveMethod derivation ──────────

	@Test
	fun `effectiveMethod is TWO_SWITCH when primary is two switch`() {
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_TWO_SWITCH)
		coordinator.loadAndApplyAll()
		val state = capturedState!!
		assertThat(state.twoSwitchEnabled).isTrue()
		assertThat(state.singleSwitchEnabled).isFalse()
		// Two-switch enabled → startCycle, not clearColors.
		verify(twoSwitchSubsystem).startCycle(eq(false))
		verify(twoSwitchSubsystem, never()).clearColors()
		verify(headTrackingSubsystem).loadCachedPrefs(eq(INPUT_METHOD_TWO_SWITCH), eq(repo))
	}

	@Test
	fun `effectiveMethod is SINGLE_SWITCH when primary is single switch`() {
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_SINGLE_SWITCH)
		coordinator.loadAndApplyAll()
		val state = capturedState!!
		assertThat(state.singleSwitchEnabled).isTrue()
		assertThat(state.twoSwitchEnabled).isFalse()
		// Single-switch routes the active layout to the scan layout.
		assertThat(setActiveLayoutCalls.last()).isTrue()
		verify(twoSwitchSubsystem).clearColors()
		verify(headTrackingSubsystem).loadCachedPrefs(eq(INPUT_METHOD_SINGLE_SWITCH), eq(repo))
	}

	@Test
	fun `effectiveMethod is DIRECTIONAL when primary NONE and directional flag on`() {
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE)
		setBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED, true)
		setBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, false)
		coordinator.loadAndApplyAll()
		val state = capturedState!!
		assertThat(state.twoSwitchEnabled).isFalse()
		assertThat(state.singleSwitchEnabled).isFalse()
		assertThat(state.directionalSelectionEnabled).isTrue()
		verify(headTrackingSubsystem).loadCachedPrefs(eq(INPUT_METHOD_DIRECTIONAL_SELECTION), eq(repo))
	}

	@Test
	fun `effectiveMethod is DIRECT when primary NONE and direct flag on`() {
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE)
		setBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED, false)
		setBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, true)
		coordinator.loadAndApplyAll()
		val state = capturedState!!
		assertThat(state.directSelectionEnabled).isTrue()
		verify(headTrackingSubsystem).loadCachedPrefs(eq(INPUT_METHOD_DIRECT_SELECTION), eq(repo))
	}

	@Test
	fun `effectiveMethod is DIRECT when only touchScreen flag is on`() {
		// touchScreen alone short-circuits to DIRECT_SELECTION (current behavior).
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE)
		setBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED, false)
		setBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, false)
		setBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED, true)
		coordinator.loadAndApplyAll()
		verify(headTrackingSubsystem).loadCachedPrefs(eq(INPUT_METHOD_DIRECT_SELECTION), eq(repo))
	}

	@Test
	fun `effectiveMethod defaults to DIRECT when all flags off`() {
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE)
		setBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED, false)
		setBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, false)
		setBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED, false)
		coordinator.loadAndApplyAll()
		verify(headTrackingSubsystem).loadCachedPrefs(eq(INPUT_METHOD_DIRECT_SELECTION), eq(repo))
	}

	// ── Group 3: loadAndApplyAll — mutual exclusion + clamps ───────────

	@Test
	fun `singleSwitch + directional forces directional false`() {
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_SINGLE_SWITCH)
		setBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED, true)
		coordinator.loadAndApplyAll()
		val state = capturedState!!
		assertThat(state.singleSwitchEnabled).isTrue()
		assertThat(state.directionalSelectionEnabled).isFalse()
	}

	@Test
	fun `phrase auto-output delay is clamped to 0 lower bound`() {
		setInt(KEY_PHRASE_AUTO_OUTPUT_DELAY_MS, -100)
		coordinator.loadAndApplyAll()
		verify(phraseFlowController).autoCommitDelayMs = eq(0L)
		verify(ttsController).speakDelayMs = eq(0L)
	}

	@Test
	fun `phrase auto-output delay is clamped to 5000 upper bound`() {
		setInt(KEY_PHRASE_AUTO_OUTPUT_DELAY_MS, 9999)
		coordinator.loadAndApplyAll()
		verify(phraseFlowController).autoCommitDelayMs = eq(5000L)
		verify(ttsController).speakDelayMs = eq(5000L)
	}

	@Test
	fun `direct selection debounce is clamped to 0 to 500`() {
		setInt(KEY_DIRECT_SELECTION_DEBOUNCE_MS, -50)
		coordinator.loadAndApplyAll()
		assertThat(capturedState!!.directSelectionDebounceMs).isEqualTo(0)

		setInt(KEY_DIRECT_SELECTION_DEBOUNCE_MS, 1000)
		coordinator.loadAndApplyAll()
		assertThat(capturedState!!.directSelectionDebounceMs).isEqualTo(500)
	}

	@Test
	fun `external switch stuck timeout is clamped to 2 to 60 seconds and converted to ms`() {
		setInt(KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC, 1) // below min
		coordinator.loadAndApplyAll()
		verify(externalSwitchHandler).updateSettings(any(), eq(2_000L))

		setInt(KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC, 999) // above max
		coordinator.loadAndApplyAll()
		verify(externalSwitchHandler).updateSettings(any(), eq(60_000L))
	}

	// ── Group 4: loadAndApplyAll — downstream calls ─────────────────────

	@Test
	fun `subsystem loadSettings calls fan out`() {
		coordinator.loadAndApplyAll()
		verify(scanSubsystem).loadSettings(eq(repo))
		verify(twoSwitchSubsystem).loadSettings(eq(repo))
		verify(joystickSubsystem).loadSettings(eq(repo))
	}

	@Test
	fun `twoSwitch enabled triggers startCycle, disabled triggers clearColors`() {
		// Disabled path
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE)
		coordinator.loadAndApplyAll()
		verify(twoSwitchSubsystem).clearColors()

		// Enabled path
		setString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_TWO_SWITCH)
		coordinator.loadAndApplyAll()
		verify(twoSwitchSubsystem).startCycle(eq(false))
	}

	@Test
	fun `key history callbacks fire once per loadAndApplyAll`() {
		coordinator.loadAndApplyAll()
		assertThat(keyHistoryVisibilityCount).isEqualTo(1)
		assertThat(keyHistoryHeightCount).isEqualTo(1)
		assertThat(keyHistoryShrinkCount).isEqualTo(1)
		assertThat(updateButtonClickListenersCount).isEqualTo(1)
	}

	@Test
	fun `applyPreferenceState runs before overlay updateTouchScreenSwitch`() {
		// Regression: the overlay reads callbacks.isTouchScreenSwitchEnabled etc.,
		// which are backed by the IME fields written in applyPreferenceState. If
		// the overlay update ran first, it would see stale values and (e.g.) keep
		// the TSS bar visible after the user disabled the underlying switch method.
		// See https://github.com/Continuous-Path/JustType1 commit history 2026-05-25.
		var stateAppliedWhenOverlayUpdated = false
		doAnswer {
			stateAppliedWhenOverlayUpdated = capturedState != null
			Unit
		}.whenever(overlayCoordinator).updateTouchScreenSwitch()

		coordinator.loadAndApplyAll()

		assertThat(stateAppliedWhenOverlayUpdated).isTrue()
	}

	// ── Group 5: loadAndApplyAll — layout + JTUI pass-through ──────────

	@Test
	fun `layoutMode ALPHA sets jtui layoutMode to Alphabetical`() {
		setString(KEY_LAYOUT_MODE, MODE_ALPHA)
		coordinator.loadAndApplyAll()
		assertThat(lastLayoutMode).isEqualTo(LayoutMode.Alphabetical)
	}

	@Test
	fun `layoutMode OPT sets jtui layoutMode to Optimized`() {
		setString(KEY_LAYOUT_MODE, MODE_OPT)
		coordinator.loadAndApplyAll()
		assertThat(lastLayoutMode).isEqualTo(LayoutMode.Optimized)
	}

	// ── Group 6: handleCriticalSettingChange — per-key dispatch ────────

	@Test
	fun `KEY_INPUT_METHOD applies via loadAndApplyAll without view recreate`() {
		inSettings = false
		coordinator.handleCriticalSettingChange(KEY_INPUT_METHOD)
		assertThat(recreateCount).isEqualTo(0)
		// Overlay updates run once via loadAndApplyAll; the IM dispatch branch
		// no longer duplicates them.
		verify(overlayCoordinator).updateDirectionalSelection()
		verify(overlayCoordinator).updateTouchScreenSwitch()
		verify(headTrackingSubsystem).notifyHeadBoardOfTrackingState()
		// loadAndApplyAll always runs first.
		assertThat(capturedState).isNotNull()
	}

	@Test
	fun `KEY_INPUT_METHOD inside settings still notifies head board`() {
		inSettings = true
		coordinator.handleCriticalSettingChange(KEY_INPUT_METHOD)
		assertThat(recreateCount).isEqualTo(0)
		// notifyHeadBoardOfTrackingState always runs after IM changes.
		verify(headTrackingSubsystem).notifyHeadBoardOfTrackingState()
	}

	@Test
	fun `KEY_LAYOUT_MODE applies via JTUI without view recreate`() {
		jtuiInitialized = true
		coordinator.handleCriticalSettingChange(KEY_LAYOUT_MODE)
		assertThat(recreateCount).isEqualTo(0)
		// loadAndApplyAll already pushed setLayoutMode through; the only follow-up
		// is forceUpdateUi.
		assertThat(forceUpdateCount).isAtLeast(1)
	}

	@Test
	fun `KEY_SCAN_LAYOUT_SIZE re-runs setActiveLayout without view recreate`() {
		useScanLayoutValue = true
		coordinator.handleCriticalSettingChange(KEY_SCAN_LAYOUT_SIZE)
		assertThat(recreateCount).isEqualTo(0)
		assertThat(setActiveLayoutCalls).contains(true)
	}

	@Test
	fun `KEY_KEYBOARD_SIZE_RATIO applies size without view recreate`() {
		coordinator.handleCriticalSettingChange(KEY_KEYBOARD_SIZE_RATIO)
		assertThat(recreateCount).isEqualTo(0)
		assertThat(applyKeyboardSizeCount).isEqualTo(1)
	}

	@Test
	fun `KEY_INPUT_METHOD_DIRECTIONAL outside settings updates overlay and forces ui`() {
		inSettings = false
		jtuiInitialized = true
		coordinator.handleCriticalSettingChange(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED)
		assertThat(forceUpdateCount).isEqualTo(1)
	}

	@Test
	fun `KEY_INPUT_METHOD_DIRECT inside settings skips overlay and forceUpdate`() {
		inSettings = true
		coordinator.handleCriticalSettingChange(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED)
		assertThat(forceUpdateCount).isEqualTo(0)
	}

	@Test
	fun `KEY_APP_LANGUAGE schedules locale update on ui thread`() {
		jtuiInitialized = true
		coordinator.handleCriticalSettingChange(KEY_APP_LANGUAGE)
		assertThat(executedBlocks).hasSize(1)
		// Run the captured block to verify what it does.
		val forceUpdateBefore = forceUpdateCount
		executedBlocks.first().invoke()
		assertThat(updateLocaleContextCount).isEqualTo(1)
		assertThat(forceUpdateCount).isEqualTo(forceUpdateBefore + 1)
	}

	@Test
	fun `KEY_TYPING_LANGUAGE triggers reinitJtuiInBackground`() {
		coordinator.handleCriticalSettingChange(KEY_TYPING_LANGUAGE)
		assertThat(reinitJtuiCount).isEqualTo(1)
	}

	@Test
	fun `KEY_SHOW_ACCENTED_KEYS triggers reinitJtuiInBackground`() {
		coordinator.handleCriticalSettingChange(KEY_SHOW_ACCENTED_KEYS)
		assertThat(reinitJtuiCount).isEqualTo(1)
	}

	@Test
	fun `KEY_REQUIRE_ACCENTED_KEYS triggers reinitJtuiInBackground`() {
		coordinator.handleCriticalSettingChange(KEY_REQUIRE_ACCENTED_KEYS)
		assertThat(reinitJtuiCount).isEqualTo(1)
	}

	@Test
	fun `PREFS_KEY_LANGUAGE_TTS_VOICE re-binds TTS without JTUI reinit`() {
		coordinator.handleCriticalSettingChange(PREFS_KEY_LANGUAGE_TTS_VOICE)
		assertThat(applyTtsCount).isEqualTo(1)
		assertThat(reinitJtuiCount).isEqualTo(0)
	}

	// ── Group 7: createOnCreateListener ─────────────────────────────────

	@Test
	fun `listener in settings mode ignores all changes`() {
		inSettings = true
		val listener = coordinator.createOnCreateListener()
		listener.onSharedPreferenceChanged(null, KEY_INPUT_METHOD)
		listener.onSharedPreferenceChanged(null, KEY_BEEP_KEY_FEEDBACK)
		// Neither path ran.
		assertThat(capturedState).isNull()
		assertThat(recreateCount).isEqualTo(0)
	}

	@Test
	fun `listener routes critical key to handleCriticalSettingChange`() {
		inSettings = false
		val listener = coordinator.createOnCreateListener()
		listener.onSharedPreferenceChanged(null, KEY_INPUT_METHOD)
		// handleCriticalSettingChange runs loadAndApplyAll (state captured) and
		// then notifies the head-tracking subsystem for KEY_INPUT_METHOD.
		assertThat(capturedState).isNotNull()
		verify(headTrackingSubsystem).notifyHeadBoardOfTrackingState()
	}

	@Test
	fun `listener routes non-critical key to loadAndApplyAll only`() {
		inSettings = false
		val listener = coordinator.createOnCreateListener()
		listener.onSharedPreferenceChanged(null, KEY_BEEP_KEY_FEEDBACK)
		// Non-critical: loadAndApplyAll runs (state captured) but no recreate.
		assertThat(capturedState).isNotNull()
		assertThat(recreateCount).isEqualTo(0)
	}

	@Test
	fun `listener with null key falls through to loadAndApplyAll`() {
		inSettings = false
		val listener = coordinator.createOnCreateListener()
		listener.onSharedPreferenceChanged(null, null)
		assertThat(capturedState).isNotNull()
		assertThat(recreateCount).isEqualTo(0)
	}
}
