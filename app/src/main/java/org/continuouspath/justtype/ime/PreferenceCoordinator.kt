package org.continuouspath.justtype.ime

import android.content.Context
import android.content.SharedPreferences
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.Constants.DEFAULT_ERROR_BEEP
import org.continuouspath.justtype.Constants.INPUT_METHOD_DIRECTIONAL_SELECTION
import org.continuouspath.justtype.Constants.INPUT_METHOD_DIRECT_SELECTION
import org.continuouspath.justtype.Constants.INPUT_METHOD_NONE
import org.continuouspath.justtype.Constants.INPUT_METHOD_SINGLE_SWITCH
import org.continuouspath.justtype.Constants.INPUT_METHOD_TWO_SWITCH
import org.continuouspath.justtype.Constants.KEY_APP_LANGUAGE
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_DEBUG_LOG_CATEGORIES
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT
import org.continuouspath.justtype.Constants.KEY_DIRECT_SELECTION_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_ENABLE_DEBUG_LOG
import org.continuouspath.justtype.Constants.KEY_ERROR_BEEP
import org.continuouspath.justtype.Constants.KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORRECTION_BEEP
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORRECTION_FLASH_RED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_SIZE_RATIO
import org.continuouspath.justtype.Constants.KEY_KEY_HISTORY_SHRINK_TO_FIT
import org.continuouspath.justtype.Constants.KEY_LAYOUT_MODE
import org.continuouspath.justtype.Constants.KEY_NEXT_LETTER_HINTS
import org.continuouspath.justtype.Constants.KEY_OPTIMIZED_LAYOUT_SOURCE
import org.continuouspath.justtype.Constants.KEY_PHRASE_AUTO_OUTPUT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_SCAN_LAYOUT_SIZE
import org.continuouspath.justtype.Constants.KEY_SHOW_BUTTONS_PRESSED
import org.continuouspath.justtype.Constants.KEY_SHOW_EDIT_OPS
import org.continuouspath.justtype.Constants.KEY_SPEAK_OUTPUT_PHRASE
import org.continuouspath.justtype.Constants.KEY_SPEAK_OUTPUT_SENTENCE
import org.continuouspath.justtype.Constants.KEY_SPEAK_OUTPUT_WORD
import org.continuouspath.justtype.Constants.KEY_SPEAK_PUNCT_NAMES
import org.continuouspath.justtype.Constants.KEY_SWITCH_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_TOUCH_OVERLAY_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_BEEP
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_FLASH
import org.continuouspath.justtype.Constants.KEY_TSS_BUTTON_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_MODE
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_OPACITY
import org.continuouspath.justtype.Constants.KEY_TTS_UI_VOICE_GENDER
import org.continuouspath.justtype.Constants.KEY_TTS_VOICE_GENDER
import org.continuouspath.justtype.Constants.KEY_TYPING_LANGUAGE
import org.continuouspath.justtype.Constants.MODE_ALPHA
import org.continuouspath.justtype.Constants.PREFS_KEY_LANGUAGE_TTS_VOICE
import org.continuouspath.justtype.Constants.SCAN_LAYOUT_SIZE_SMALL
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.logic.LayoutMode
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getInt
import org.continuouspath.justtype.settings.getString

/**
 * Snapshot of all preference-derived state that JustTypeIME uses at runtime.
 * Written by [PreferenceCoordinator.loadAndApplyAll], applied via
 * [PreferenceCoordinatorCallbacks.applyPreferenceState].
 */
data class PreferenceState(
	val directionalSelectionEnabled: Boolean,
	val touchScreenSwitchEnabled: Boolean,
	val directSelectionEnabled: Boolean,
	val twoSwitchEnabled: Boolean,
	val singleSwitchEnabled: Boolean,
	val scanLayoutSizeLarge: Boolean,
	val flashKeyFeedbackEnabled: Boolean,
	val beepKeyFeedbackEnabled: Boolean,
	val touchScreenSwitchFlashEnabled: Boolean,
	val touchScreenSwitchBeepEnabled: Boolean,
	val directSelectionDebounceMs: Int,
	val showButtonsPressedPref: Boolean,
	val keyHistoryShrinkToFitPref: Boolean,
	val errorBeepEnabled: Boolean,
	val correctionBeepEnabled: Boolean,
	val correctionFlashRedEnabled: Boolean,
	val enableDebugLog: Boolean,
)

/**
 * Callbacks that [PreferenceCoordinator] needs from the IME host.
 */
interface PreferenceCoordinatorCallbacks {
	// ── State queries ──
	val isJtuiInitialized: Boolean
	val isInSettingsMode: Boolean

	// ── Late-initialized subsystem access (null if not yet ready) ──
	fun getExternalSwitchHandler(): ExternalSwitchHandler?
	fun getOverlayCoordinator(): OverlayCoordinator?

	// ── Narrow JTUI operations (no-op when JTUI is not yet ready) ──
	fun setShowNextLetterHints(enabled: Boolean)
	fun setLayoutMode(mode: LayoutMode)
	fun updateLocaleContext(context: Context)

	// ── Preference state batch write ──
	fun applyPreferenceState(state: PreferenceState)

	// ── View / layout operations ──
	fun applyKeyHistoryVisibility()
	fun applyKeyHistoryHeight(repo: SettingsRepository)
	fun applyKeyHistoryShrinkToFit()
	fun updateButtonClickListeners()
	fun setActiveLayout(useScan: Boolean)
	fun useScanLayout(): Boolean
	fun applyKeyboardSize()

	// ── Critical-setting operations ──
	fun recreateInputView()
	fun forceUpdateUi()
	fun reinitJtuiInBackground()

	/** Re-bind TTS to the active typing language's voice without a full JTUI reinit. */
	fun applyTtsForActiveLanguage()

	/** Re-bind the UI ("device") voice to the current UI language + its chosen voice. */
	fun applyUiVoice()

	fun debugLog(message: String)
	fun executeOnUiThread(block: () -> Unit)
}

/**
 * Centralises preference loading, critical-setting handling, and the
 * onCreate preference listener.  Subsystems initialised in [onCreate]
 * are held as direct references; late-init subsystems (initialised in
 * [onCreateInputView]) are obtained through [PreferenceCoordinatorCallbacks].
 */
class PreferenceCoordinator(
	private val context: Context,
	private val scanSubsystem: ScanSubsystem,
	private val twoSwitchSubsystem: TwoSwitchSubsystem,
	private val joystickSubsystem: JoystickSubsystem,
	private val mouseJoystickSubsystem: MouseJoystickSubsystem,
	private val headTrackingSubsystem: HeadTrackingSubsystem,
	private val phraseFlowController: PhraseFlowController,
	private val ttsController: TtsController,
	private val imeTextController: ImeTextController,
	private val callbacks: PreferenceCoordinatorCallbacks,
) {
	var isRecreatingInputView = false

	private val criticalSettingKeys = setOf(
		KEY_INPUT_METHOD,
		KEY_INPUT_METHOD_PRIMARY,
		KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED,
		KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED,
		KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED,
		KEY_LAYOUT_MODE,
		KEY_OPTIMIZED_LAYOUT_SOURCE,
		Constants.KEY_TONE_LABEL_STYLE,
		Constants.KEY_TONE_ENTRY_POSITION, // rebuilds the trie (tone keystroke position)
		Constants.KEY_SHOW_ACCENTED_KEYS, // rebuilds layout view + WLD trie (base-only derivation)
		Constants.KEY_REQUIRE_ACCENTED_KEYS, // rebuilds the WLD trie (fallback paths added/removed)
		Constants.KEY_EXCLUDED_WORDS, // rebuilds the WLD trie (offensive-level words filtered in/out)
		Constants.KEY_ENGLISH_REGION, // re-ranks British vs American spellings
		KEY_SCAN_LAYOUT_SIZE,
		KEY_KEYBOARD_SIZE_RATIO,
		Constants.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE, // swaps which history container the layout controller wires

		KEY_APP_LANGUAGE,
		KEY_TYPING_LANGUAGE,
		PREFS_KEY_LANGUAGE_TTS_VOICE,
	)

	fun isCriticalSettingChange(key: String): Boolean = key in criticalSettingKeys

	fun reinitJtuiInBackground() {
		callbacks.reinitJtuiInBackground()
	}

	fun applyTtsForActiveLanguage() {
		callbacks.applyTtsForActiveLanguage()
	}

	// ── Bulk preference load ────────────────────────────────────────────

	fun loadAndApplyAll() {
		val repo = SettingsRepository.get()

		callbacks.getExternalSwitchHandler()?.updateSettings(
			debounceMs = repo.getInt(KEY_SWITCH_DEBOUNCE_MS).toLong().coerceIn(0L, 1000L),
			stuckTimeoutMs = repo.getInt(KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC).coerceIn(2, 60) * 1000L,
		)

		val primaryMethod = repo.getString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE) ?: INPUT_METHOD_NONE
		var directionalSelectionEnabled = repo.getBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED)
		val touchScreenSwitchEnabled = repo.getBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)
		val directSelectionEnabled = repo.getBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED)

		val effectiveMethod = when {
			primaryMethod != INPUT_METHOD_NONE -> primaryMethod
			directionalSelectionEnabled -> INPUT_METHOD_DIRECTIONAL_SELECTION
			directSelectionEnabled -> INPUT_METHOD_DIRECT_SELECTION
			touchScreenSwitchEnabled -> INPUT_METHOD_DIRECT_SELECTION
			else -> INPUT_METHOD_DIRECT_SELECTION
		}

		val twoSwitchEnabled = effectiveMethod == INPUT_METHOD_TWO_SWITCH || primaryMethod == INPUT_METHOD_TWO_SWITCH
		val singleSwitchEnabled = effectiveMethod == INPUT_METHOD_SINGLE_SWITCH || primaryMethod == INPUT_METHOD_SINGLE_SWITCH

		if (singleSwitchEnabled && directionalSelectionEnabled) {
			directionalSelectionEnabled = false
		}

		callbacks.debugLog(
			"[prefs] primaryMethod=$primaryMethod  effectiveMethod=$effectiveMethod" +
				"  singleSwitchEnabled=$singleSwitchEnabled  twoSwitchEnabled=$twoSwitchEnabled" +
				"  directionalSelectionEnabled=$directionalSelectionEnabled" +
				"  directSelectionEnabled=$directSelectionEnabled" +
				"  touchScreenSwitchEnabled=$touchScreenSwitchEnabled",
		)

		scanSubsystem.loadSettings(repo)
		val scanLayoutSizeLarge = repo.getString(KEY_SCAN_LAYOUT_SIZE) != SCAN_LAYOUT_SIZE_SMALL
		val flashKeyFeedbackEnabled = repo.getBoolean(KEY_FLASH_KEY_FEEDBACK)
		val beepKeyFeedbackEnabled = repo.getBoolean(KEY_BEEP_KEY_FEEDBACK)
		twoSwitchSubsystem.loadSettings(repo)
		joystickSubsystem.loadSettings(repo)
		mouseJoystickSubsystem.loadSettings(repo)
		val touchScreenSwitchFlashEnabled = repo.getBoolean(KEY_TOUCH_SCREEN_SWITCH_FLASH)
		val touchScreenSwitchBeepEnabled = repo.getBoolean(KEY_TOUCH_SCREEN_SWITCH_BEEP)
		val showNextLetterHints = repo.getBoolean(KEY_NEXT_LETTER_HINTS)
		callbacks.setShowNextLetterHints(showNextLetterHints)

		headTrackingSubsystem.loadCachedPrefs(effectiveMethod, repo)

		val directSelectionDebounceMs = repo.getInt(KEY_DIRECT_SELECTION_DEBOUNCE_MS).coerceIn(0, 500)

		// Apply state to IME fields BEFORE updating the overlay. The overlay reads
		// callbacks.isTouchScreenSwitchEnabled / isSingleSwitchEnabled / isTwoSwitchEnabled
		// which are getters backed by these fields — if we update the overlay first, it
		// sees the previous values and (e.g.) keeps the TSS bar visible after the user
		// has just turned a switch method off. See InputMethods bug report 2026-05-25.
		callbacks.applyPreferenceState(
			PreferenceState(
				directionalSelectionEnabled = directionalSelectionEnabled,
				touchScreenSwitchEnabled = touchScreenSwitchEnabled,
				directSelectionEnabled = directSelectionEnabled,
				twoSwitchEnabled = twoSwitchEnabled,
				singleSwitchEnabled = singleSwitchEnabled,
				scanLayoutSizeLarge = scanLayoutSizeLarge,
				flashKeyFeedbackEnabled = flashKeyFeedbackEnabled,
				beepKeyFeedbackEnabled = beepKeyFeedbackEnabled,
				touchScreenSwitchFlashEnabled = touchScreenSwitchFlashEnabled,
				touchScreenSwitchBeepEnabled = touchScreenSwitchBeepEnabled,
				directSelectionDebounceMs = directSelectionDebounceMs,
				showButtonsPressedPref = repo.getBoolean(KEY_SHOW_BUTTONS_PRESSED),
				keyHistoryShrinkToFitPref = repo.getBoolean(KEY_KEY_HISTORY_SHRINK_TO_FIT),
				errorBeepEnabled = repo.getBoolean(KEY_ERROR_BEEP, DEFAULT_ERROR_BEEP),
				correctionBeepEnabled = repo.getBoolean(KEY_HEADTRACKING_CORRECTION_BEEP),
				correctionFlashRedEnabled = repo.getBoolean(KEY_HEADTRACKING_CORRECTION_FLASH_RED),
				enableDebugLog = repo.getBoolean(KEY_ENABLE_DEBUG_LOG),
			),
		)

		callbacks.getOverlayCoordinator()?.let { oc ->
			oc.updateConfig(
				OverlayConfig(
					timeoutSec = repo.getInt(KEY_TOUCH_OVERLAY_TIMEOUT_SEC).coerceIn(2, 10),
					swipePercent = repo.getInt(KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT).coerceIn(2, 20),
					touchSwitchDebounceMs = repo.getInt(KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS).coerceIn(0, 500),
					directionalDebounceMs = repo.getInt(KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS).coerceIn(0, 500),
					overlayModeEnabled = repo.getBoolean(KEY_TSS_OVERLAY_MODE),
					buttonHeightPercent = repo.getInt(KEY_TSS_BUTTON_HEIGHT_PERCENT).coerceIn(5, 100),
					overlayOpacityPercent = repo.getInt(KEY_TSS_OVERLAY_OPACITY).coerceIn(10, 100),
				),
			)
			oc.updateDirectionalSelection()
			oc.updateTouchScreenSwitch()
		}

		callbacks.updateButtonClickListeners()
		callbacks.setActiveLayout(singleSwitchEnabled)

		if (twoSwitchEnabled) {
			twoSwitchSubsystem.startCycle(restartOnly = false)
		} else {
			twoSwitchSubsystem.clearColors()
		}

		callbacks.applyKeyHistoryVisibility()
		callbacks.applyKeyHistoryHeight(repo)
		callbacks.applyKeyHistoryShrinkToFit()

		val layoutPref = repo.getString(KEY_LAYOUT_MODE)
		val mode = if (layoutPref == MODE_ALPHA) {
			LayoutMode.Alphabetical
		} else {
			LayoutMode.Optimized
		}
		callbacks.setLayoutMode(mode)

		val phraseAutoCommitDelayMs = repo.getInt(KEY_PHRASE_AUTO_OUTPUT_DELAY_MS).coerceIn(0, 5000).toLong()
		phraseFlowController.autoCommitDelayMs = phraseAutoCommitDelayMs
		ttsController.speakDelayMs = phraseAutoCommitDelayMs
		ttsController.speakOutputWordEnabled = repo.getBoolean(KEY_SPEAK_OUTPUT_WORD)
		ttsController.speakOutputPhraseEnabled = repo.getBoolean(KEY_SPEAK_OUTPUT_PHRASE)
		ttsController.speakOutputSentenceEnabled = repo.getBoolean(KEY_SPEAK_OUTPUT_SENTENCE)
		ttsController.speakPunctNamesEnabled = repo.getBoolean(KEY_SPEAK_PUNCT_NAMES)

		imeTextController.showEditOps = repo.getBoolean(KEY_SHOW_EDIT_OPS)

		DebugLogger.setEnabledCategories(
			DebugCategory.fromPrefValues(
				repo.getStringSet(KEY_DEBUG_LOG_CATEGORIES, null)?.toSet(),
			),
		)
	}

	// ── Critical-setting handler ────────────────────────────────────────

	fun handleCriticalSettingChange(key: String) {
		DebugLogger.log(DebugCategory.Lifecycle) {
			"[handleCriticalSettingChange] Handling critical setting change: $key"
		}

		loadAndApplyAll()

		when (key) {
			KEY_INPUT_METHOD,
			KEY_INPUT_METHOD_PRIMARY,
			-> {
				// loadAndApplyAll already swapped layout containers (setActiveLayout),
				// re-bound click listeners, refreshed overlay config, and pushed
				// setLayoutMode through JTUI. The only remaining work specific to
				// input-method changes is the HeadBoard tracking-state notification.
				headTrackingSubsystem.notifyHeadBoardOfTrackingState()
			}

			KEY_LAYOUT_MODE -> {
				// JTUI.layoutMode setter (invoked via loadAndApplyAll → setLayoutMode)
				// already rebuilds WLD, reloads vocab, redefines pages, and refreshes keys.
				// No view-tree recreate needed.
				if (callbacks.isJtuiInitialized) callbacks.forceUpdateUi()
			}

			KEY_SCAN_LAYOUT_SIZE -> {
				// setActiveLayout re-runs configureScanButtons + applyScanTopRowSize
				// through LayoutManager — no need to inflate the view tree.
				callbacks.setActiveLayout(callbacks.useScanLayout())
			}

			KEY_KEYBOARD_SIZE_RATIO -> {
				// applyKeyboardSize adjusts grid/list layout weights and refreshes
				// per-key cached ratios — no view-tree recreate needed.
				callbacks.applyKeyboardSize()
			}

			KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED,
			KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED,
			KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED,
			-> {
				if (!callbacks.isInSettingsMode) {
					callbacks.getOverlayCoordinator()?.let {
						it.updateDirectionalSelection()
						it.updateTouchScreenSwitch()
					}
					if (callbacks.isJtuiInitialized) {
						callbacks.forceUpdateUi()
					}
				}
			}

			KEY_APP_LANGUAGE -> {
				val newContext = LocaleHelper.wrap(context)
				org.continuouspath.justtype.settings.SettingsRegistry.reinitialize(newContext)
				callbacks.applyUiVoice()
				if (callbacks.isJtuiInitialized) {
					callbacks.executeOnUiThread {
						callbacks.updateLocaleContext(newContext)
						callbacks.forceUpdateUi()
					}
				}
			}

			KEY_TYPING_LANGUAGE -> {
				callbacks.reinitJtuiInBackground()
			}

			KEY_OPTIMIZED_LAYOUT_SOURCE -> {
				// Re-resolve the pinned/matched layout: full reinit rebuilds WLD and all pages.
				callbacks.reinitJtuiInBackground()
			}

			Constants.KEY_TONE_LABEL_STYLE -> {
				// Tone-key captions live in the page grids; reinit rebuilds them.
				callbacks.reinitJtuiInBackground()
			}

			Constants.KEY_SHOW_ACCENTED_KEYS,
			Constants.KEY_REQUIRE_ACCENTED_KEYS,
			-> {
				// Key faces, letter groups, and the WLD trie all derive from these
				// at build time — full reinit.
				callbacks.reinitJtuiInBackground()
			}

			Constants.KEY_ENGLISH_REGION -> {
				// Ranking-only: the words are already loaded, the selection list just needs
				// re-sorting, which the next update does.
				if (callbacks.isJtuiInitialized) callbacks.forceUpdateUi()
			}

			Constants.KEY_EXCLUDED_WORDS -> {
				// Which offensive levels are withheld decides which words are loaded into the
				// trie, so the vocabulary has to be re-fetched. Without this the change only
				// landed on the next IME restart.
				callbacks.reinitJtuiInBackground()
			}

			PREFS_KEY_LANGUAGE_TTS_VOICE -> {
				// Voice chosen in a picker — re-bind TTS only (no JTUI reinit). The store holds
				// both typing-language and UI ("device") voices, so refresh both engines.
				callbacks.applyTtsForActiveLanguage()
				callbacks.applyUiVoice()
			}

			KEY_TTS_VOICE_GENDER -> {
				// With no explicit selection, TtsController auto-binds a gender-matched default,
				// so a preference change must re-bind its engine.
				callbacks.applyTtsForActiveLanguage()
			}

			KEY_TTS_UI_VOICE_GENDER -> {
				callbacks.applyUiVoice()
			}
		}
	}

	// ── Listener factory ────────────────────────────────────────────────

	fun createOnCreateListener(): SharedPreferences.OnSharedPreferenceChangeListener {
		return SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			callbacks.debugLog("[onCreate] Preference changed: $key, reloading preferences")

			if (callbacks.isInSettingsMode) {
				callbacks.debugLog("[onCreate] In Settings Mode — skipping critical handling for $key")
				return@OnSharedPreferenceChangeListener
			}

			if (key != null && isCriticalSettingChange(key)) {
				DebugLogger.log(DebugCategory.Lifecycle) {
					"[prefsListener] Critical setting changed: $key - performing full layout refresh"
				}
				handleCriticalSettingChange(key)
			} else {
				loadAndApplyAll()
			}
		}
	}
}
