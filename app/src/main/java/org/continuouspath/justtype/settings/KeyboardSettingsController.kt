package org.continuouspath.justtype.settings

import android.content.Context
import android.os.CountDownTimer
import android.view.KeyEvent
import org.continuouspath.justtype.CanonicalLanguages
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LanguageTtsPreferences
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.TtsVoiceCandidate
import org.continuouspath.justtype.TtsVoiceNames
import org.continuouspath.justtype.TtsVoiceOption
import org.continuouspath.justtype.TtsVoicePref
import org.continuouspath.justtype.UiVoice
import org.continuouspath.justtype.autoSelectVoice
import org.continuouspath.justtype.localeForTypingLanguage

/**
 * Display state emitted by the controller after every state change.
 * The IME uses this to render the settings overlay panel,
 * update dynamic key labels, and set center text.
 */
data class SettingsDisplayState(
	/** Page title for the overlay header. */
	val pageTitle: String,
	/** Rows to render in the overlay panel. */
	val rows: List<SettingsRow>,
	/** Index into [rows] that is currently focused (for highlighting + auto-scroll). */
	val focusedRowIndex: Int,
	/** Text for the center display area (setting name + values). */
	val centerText: String,
	/** Dynamic key labels for the 8 keys (indices 0–7). */
	val keyLabels: List<String>,
	/** Speech string to announce (null = nothing new to speak). */
	val speechText: String? = null,
	/** Whether the settings overlay should be visible. */
	val overlayVisible: Boolean = true,
	/** Auto-revert countdown message, if active. */
	val autoRevertMessage: String? = null,
	/** Transient prompt message to display visually (e.g., "Applied", "Assigned: Key 1"). */
	val promptMessage: String? = null,
)

/**
 * One row in the settings overlay panel.
 */
data class SettingsRow(
	val label: String,
	val valueText: String?,
	val pendingValueText: String?,
	val description: String? = null,
	val isSectionHeader: Boolean = false,
	val isInfoText: Boolean = false,
	val isFocused: Boolean = false,
	val isSubPage: Boolean = false,
)

/**
 * The mode the controller is currently operating in.
 */
enum class SettingsMode {
	/** Browsing a list of settings (main navigation). */
	PAGE_NAV,

	/** Adjusting a slider value on the dedicated slider page. */
	SLIDER_ADJUST,

	/** Test mode — inert 8-key keyboard, auto-reverts on timer expiry. */
	TEST_MODE,

	/** Key capture mode — waiting for any raw hardware key event to assign a switch. */
	KEY_CAPTURE,

	/** Voice picker — navigating the scanned TTS voice list for the current typing language. */
	VOICE_PICK,

	/** Language manager — downloading/removing language packs from the catalog. */
	LANG_MANAGE,
}

/**
 * Step size options for slider adjustment.
 */
enum class SliderStepSize {
	ONE_NOTCH,
	FIVE_PERCENT,
	TEN_PERCENT,
	;

	fun displayLabel(context: Context): String = when (this) {
		ONE_NOTCH -> context.getString(R.string.settings_ctrl_step_one_notch)
		FIVE_PERCENT -> context.getString(R.string.settings_ctrl_step_five_percent)
		TEN_PERCENT -> context.getString(R.string.settings_ctrl_step_ten_percent)
	}

	fun next(): SliderStepSize = when (this) {
		ONE_NOTCH -> FIVE_PERCENT
		FIVE_PERCENT -> TEN_PERCENT
		TEN_PERCENT -> ONE_NOTCH
	}
}

/**
 * State machine controlling keyboard-driven settings navigation.
 *
 * Manages:
 * - Current page + cursor position
 * - Page navigation stack (for BACK)
 * - Pending unsaved values (adjust without saving)
 * - Original/snapshot values (for REVERT)
 * - Slider adjustment sub-mode
 * - Test mode with auto-revert
 * - Auto-revert countdown for dangerous settings
 *
 * All input arrives through [handleKey]. After each key,
 * the controller calls [onDisplayUpdate] with a fresh [SettingsDisplayState].
 */
@Suppress("LongParameterList") // host-callback seams injected by JTUI; grouping them would just add a pass-through type
class KeyboardSettingsController(
	private var context: Context,
	private val prefs: SettingsRepository,
	private val onDisplayUpdate: (SettingsDisplayState) -> Unit,
	private val onSettingsExit: () -> Unit,
	private val onRequestKeyboardPage: (String) -> Unit,
	private val onApplySettingImmediate: (String, Any) -> Unit,
	private val onRevertSettingImmediate: (String, Any) -> Unit,
	private val sayInterruptible: (String) -> Unit = {},
	private val voiceServices: org.continuouspath.justtype.TtsVoiceServices = org.continuouspath.justtype.DefaultTtsVoiceServices(context),
	/** Fires for Action rows that open an outside surface (share sheet, Languages screen). */
	private val onLaunchAction: (String) -> Unit = {},
	/** Language-pack catalog/download/remove backend; null = fall back to [onLaunchAction]. */
	private val langpackServices: org.continuouspath.justtype.langpack.LangpackKeyboardServices? = null,
) {
	init {
		// Controller strings must follow the app's UI-language preference, not the raw service
		// locale — prompts like "Finding voices…" were otherwise stuck in the system language.
		context = LocaleHelper.wrap(context)
	}
	// ── Current navigation state ─────────────────────────────────────

	private var mode: SettingsMode = SettingsMode.PAGE_NAV

	/** Stack of (pageId, cursorIndex) for BACK navigation. */
	private val pageStack = mutableListOf<Pair<String, Int>>()

	private var currentPageId: String = "main"
	private var cursorIndex: Int = 0

	/** The focusable items on the current page (excludes SectionHeaders). */
	private var focusableIndices: List<Int> = emptyList()

	/** Which focusable index the cursor is at (index into focusableIndices). */
	private var focusableCursorPos: Int = 0

	// ── Pending values ───────────────────────────────────────────────

	/** Pending (unsaved) values, keyed by preference key. */
	private val pendingValues = mutableMapOf<String, Any>()

	/** Snapshot of original values when entering a page or slider, keyed by preference key. */
	private val snapshotValues = mutableMapOf<String, Any>()

	// ── Slider adjustment state ──────────────────────────────────────

	private var sliderStepSize: SliderStepSize = SliderStepSize.ONE_NOTCH
	private var sliderOriginalValue: Any? = null
	private var sliderSettingDef: SettingsDef? = null

	// ── Voice-picker state (VOICE_PICK mode) ─────────────────────────

	/** What the voice picker is choosing a voice FOR: the typing language, or the UI ("device") voice. */
	enum class VoicePickTarget { TYPING, UI }

	private var voiceOptions: List<TtsVoiceOption> = emptyList()

	/** Friendly labels aligned index-for-index with [voiceOptions] (see [TtsVoiceNames]). */
	private var voiceLabels: List<String> = emptyList()
	private var voiceCursor: Int = 0
	private var voiceScanning: Boolean = false
	private var voicePickTarget: VoicePickTarget = VoicePickTarget.TYPING
	private var voicePrefKey: String = Constants.TYPING_LANGUAGE_ENGLISH
	private var voiceTitleName: String = ""
	private var cancelVoiceScan: (() -> Unit)? = null

	/** Where BACK from the voice picker returns to (language manager when entered from there). */
	private var voicePickReturnMode: SettingsMode = SettingsMode.PAGE_NAV

	// ── Test mode state ──────────────────────────────────────────────

	private val testTimeDurations = intArrayOf(5, 10, 20, 30, 60)
	private var testTimeIndex: Int = 1 // Default 10 seconds
	private var testTimer: CountDownTimer? = null
	private var testPreApplyKey: String? = null
	private var testPreApplyValue: Any? = null

	// ── Auto-revert state ────────────────────────────────────────────

	private var autoRevertTimer: CountDownTimer? = null
	private var autoRevertKey: String? = null
	private var autoRevertOriginalValue: Any? = null
	private var autoRevertSecondsLeft: Int = 0

	// ── Key capture state ───────────────────────────────────────

	/** When true, the IME should route the next raw key event here instead of through button mapping. */
	var isCapturingKey: Boolean = false
		private set

	/** The preference key being captured (e.g. KEY_SCAN_SWITCH_CODE). */
	private var captureKey: String? = null

	/** The SettingsDef.KeyCapture item being captured. */
	private var captureItem: SettingsDef.KeyCapture? = null

	// ── Easter egg: Developer Settings reveal ────────────────────────

	private var developerRevealed = false
	private val easterEggSequence = intArrayOf(6, 1, 6, 1, 6) // DOWN UP DOWN UP DOWN
	private val recentKeys = IntArray(5) { -1 }
	private var recentKeyPos = 0

	// ── Prompt display ──────────────────────────────────────────────

	/** Transient prompt message — consumed (cleared) after one display cycle. */
	private var lastPromptMessage: String? = null

	/**
	 * Show a prompt both visually (via [promptMessage] in the display state)
	 * and spoken aloud (if the speak-prompts preference is ON).
	 */
	private fun announcePrompt(message: String) {
		lastPromptMessage = message
		val C = org.continuouspath.justtype.Constants
		if (prefs.getBoolean(C.KEY_SPEAK_SETTINGS_PROMPTS)) {
			sayInterruptible(message)
		}
	}

	// ── Public API ───────────────────────────────────────────────────

	/**
	 * Enter settings mode. Called when the user presses the SETTINGS key.
	 * Navigates to the main settings page.
	 */
	fun enter() {
		mode = SettingsMode.PAGE_NAV
		pageStack.clear()
		pendingValues.clear()
		snapshotValues.clear()
		navigateToPage("main")
		onRequestKeyboardPage("Settings")
	}

	/**
	 * Handle a key press (0–7) in the current settings mode.
	 * Delegates to the appropriate handler based on [mode].
	 */
	fun handleKey(keyIndex: Int) {
		when (mode) {
			SettingsMode.PAGE_NAV -> handlePageNavKey(keyIndex)
			SettingsMode.SLIDER_ADJUST -> handleSliderKey(keyIndex)
			SettingsMode.TEST_MODE -> handleTestKey(keyIndex)
			SettingsMode.KEY_CAPTURE -> handleKeyCaptureKey(keyIndex)
			SettingsMode.VOICE_PICK -> handleVoicePickKey(keyIndex)
			SettingsMode.LANG_MANAGE -> handleLangManageKey(keyIndex)
		}
	}

	/**
	 * Called from the IME's onKeyDown() when [isCapturingKey] is true.
	 * Receives the raw Android KeyCode (not the mapped 0–7 button index)
	 * and stores it as the pending value for the switch being assigned.
	 */
	fun handleRawKeyCapture(keyCode: Int) {
		val item = captureItem ?: return
		val keyName = switchLabelForKeyCode(keyCode)

		pendingValues[item.key] = keyCode

		// For two-switch: prevent duplicate assignments
		handleDuplicateSwitchPrevention(item.key, keyCode)

		// Exit capture mode back to page nav
		isCapturingKey = false
		mode = SettingsMode.PAGE_NAV
		captureKey = null
		captureItem = null
		onRequestKeyboardPage("Settings")

		announcePrompt(context.getString(R.string.sr_key_capture_assigned, keyName))
		emitDisplayState()
	}

	/**
	 * Clean up timers when settings mode is exited externally.
	 */
	fun dispose() {
		testTimer?.cancel()
		testTimer = null
		autoRevertTimer?.cancel()
		autoRevertTimer = null
		cancelVoiceScan?.invoke()
		cancelVoiceScan = null
		voiceServices.stopPreview()
		langRefreshHandler.removeCallbacks(langRefreshRunnable)
		cancelLangLoad?.invoke()
		cancelLangLoad = null
	}

	/** Whether settings mode is currently active. */
	val isActive: Boolean get() = true // Always active while this controller exists

	/**
	 * Re-emit the current display state (e.g. after locale change).
	 * Rebuilds rows from the updated SettingsRegistry labels.
	 * If [newContext] is provided, updates the controller's context first
	 * so that key labels (UP, DOWN, BACK, APPLY, etc.) resolve in the new locale.
	 */
	fun refreshDisplay(newContext: Context? = null) {
		if (newContext != null) {
			context = LocaleHelper.wrap(newContext)
		}
		emitDisplayState()
	}

	// ── Page navigation ──────────────────────────────────────────────

	private fun navigateToPage(pageId: String) {
		currentPageId = pageId
		val items = currentPageItems()
		focusableIndices = items.indices.filter { items[it] !is SettingsDef.SectionHeader && items[it] !is SettingsDef.InfoText }
		focusableCursorPos = 0
		cursorIndex = focusableIndices.firstOrNull() ?: 0

		// Snapshot current values for all settings on this page
		snapshotCurrentPage()

		emitDisplayState(speak = true)
	}

	private fun currentPageItems(): List<SettingsDef> {
		val items = SettingsRegistry.get().pages[currentPageId] ?: emptyList()
		if (currentPageId == "main" && !developerRevealed) {
			return items.filter { it.key != "nav_developer" }
		}
		if (currentPageId == "input_methods") {
			return filterInputMethodSubPages(items)
		}
		return items
	}

	/**
	 * Filter Method Setup sub-pages on the Input Methods page so that only
	 * pages for currently-enabled input methods are visible. This prevents
	 * the user from entering a setup page where they can't test changes.
	 */
	private fun filterInputMethodSubPages(items: List<SettingsDef>): List<SettingsDef> {
		val C = org.continuouspath.justtype.Constants
		// Read effective primary method (pending or saved)
		val primaryMethod = (pendingValues[C.KEY_INPUT_METHOD_PRIMARY] as? String)
			?: prefs.getString(C.KEY_INPUT_METHOD_PRIMARY, C.INPUT_METHOD_NONE) ?: C.INPUT_METHOD_NONE
		val directEnabled = (pendingValues[C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED] as? Boolean)
			?: prefs.getBoolean(C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED)
		val directionalEnabled = (pendingValues[C.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED] as? Boolean)
			?: prefs.getBoolean(C.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED)
		val touchSwitchEnabled = (pendingValues[C.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED] as? Boolean)
			?: prefs.getBoolean(C.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)

		// Also hide the "Method Setup" section header if no sub-pages would be visible
		val visibleSubPageKeys = mutableSetOf<String>()
		if (primaryMethod == C.INPUT_METHOD_HEAD_TRACKING) visibleSubPageKeys.add("nav_head_tracking")
		if (primaryMethod == C.INPUT_METHOD_JOYSTICK) visibleSubPageKeys.add("nav_joystick")
		if (primaryMethod == C.INPUT_METHOD_MOUSE_JOYSTICK) visibleSubPageKeys.add("nav_mouse_joystick")
		if (primaryMethod == C.INPUT_METHOD_SINGLE_SWITCH) visibleSubPageKeys.add("nav_single_switch")
		if (primaryMethod == C.INPUT_METHOD_TWO_SWITCH) visibleSubPageKeys.add("nav_two_switch")
		if (touchSwitchEnabled) visibleSubPageKeys.add("nav_touch_switch")
		if (directEnabled) visibleSubPageKeys.add("nav_direct_sel")
		if (directionalEnabled) visibleSubPageKeys.add("nav_directional_sel")

		return items.filter { item ->
			when {
				item is SettingsDef.SubPage &&
					item.key.startsWith("nav_") &&
					item.key in listOf(
						"nav_head_tracking",
						"nav_joystick",
						"nav_mouse_joystick",
						"nav_single_switch",
						"nav_two_switch",
						"nav_touch_switch",
						"nav_direct_sel",
						"nav_directional_sel",
					) ->
					item.key in visibleSubPageKeys
				// Hide the "Method Setup" section header if no sub-pages are visible
				item is SettingsDef.SectionHeader && item.key == "sec_method_setup" ->
					visibleSubPageKeys.isNotEmpty()
				else -> true
			}
		}
	}

	private fun currentFocusedItem(): SettingsDef? {
		val items = currentPageItems()
		return if (cursorIndex in items.indices) items[cursorIndex] else null
	}

	private fun snapshotCurrentPage() {
		val items = currentPageItems()
		for (item in items) {
			if (item is SettingsDef.SectionHeader || item is SettingsDef.SubPage || item is SettingsDef.InfoText) continue
			// KeyCapture uses readCurrentValue like any other type
			val value = readCurrentValue(item)
			if (value != null) {
				snapshotValues[item.key] = value
			}
		}
	}

	// ── Page Nav key handlers ────────────────────────────────────────

	private fun handlePageNavKey(keyIndex: Int) {
		// Track key sequence for Easter egg detection
		if (!developerRevealed && currentPageId == "main") {
			recentKeys[recentKeyPos % recentKeys.size] = keyIndex
			recentKeyPos++
			checkEasterEgg()
		}

		when (keyIndex) {
			0 -> handleRevert()
			1 -> handleNavigateUp()
			2 -> handleAction()
			3 -> handleLeft()
			4 -> handleRight()
			5 -> handleApply()
			6 -> handleNavigateDown()
			7 -> handleBack()
		}
	}

	private fun checkEasterEgg() {
		if (recentKeyPos < easterEggSequence.size) return

		// Check if focused item is Backup & Restore
		val focused = currentFocusedItem()
		if (focused?.key != "nav_backup") return

		// Check if last 5 keys match the sequence
		val start = recentKeyPos - easterEggSequence.size
		for (i in easterEggSequence.indices) {
			if (recentKeys[(start + i) % recentKeys.size] != easterEggSequence[i]) return
		}

		// Reveal Developer Settings
		developerRevealed = true

		// Rebuild focusable indices with the developer item now included
		val items = currentPageItems()
		focusableIndices = items.indices.filter {
			items[it] !is SettingsDef.SectionHeader && items[it] !is SettingsDef.InfoText
		}
		// Keep cursor at the current position
		focusableCursorPos = focusableCursorPos.coerceIn(0, (focusableIndices.size - 1).coerceAtLeast(0))
		cursorIndex = if (focusableIndices.isNotEmpty()) focusableIndices[focusableCursorPos] else 0

		announcePrompt(context.getString(R.string.sr_main_nav_developer))
		emitDisplayState(speak = false)
	}

	private fun handleNavigateUp() {
		if (focusableIndices.isEmpty()) return
		if (focusableCursorPos > 0) {
			focusableCursorPos--
			cursorIndex = focusableIndices[focusableCursorPos]
			emitDisplayState(speak = true)
		}
	}

	private fun handleNavigateDown() {
		if (focusableIndices.isEmpty()) return
		if (focusableCursorPos < focusableIndices.size - 1) {
			focusableCursorPos++
			cursorIndex = focusableIndices[focusableCursorPos]
			emitDisplayState(speak = true)
		}
	}

	private fun handleAction() {
		val item = currentFocusedItem() ?: return
		when (item) {
			is SettingsDef.Toggle -> {
				if (gateClosed(item.enabledWhenKey, null)) {
					emitDisplayState(speak = true) // announce current (locked OFF) state
					return
				}
				val current = getEffectiveBoolean(item)
				pendingValues[item.key] = !current
				enforceMutualExclusion(item.key, !current)
				emitDisplayState(speak = true)
			}
			is SettingsDef.IntSlider -> enterSliderMode(item)
			is SettingsDef.FloatSlider -> enterSliderMode(item)
			is SettingsDef.Choice -> {
				if (gateClosed(item.enabledWhenKey, item.enabledWhenValue)) {
					emitDisplayState(speak = true)
					return
				}
				val current = getEffectiveChoice(item)
				val idx = item.options.indexOfFirst { it.first == current }
				val nextIdx = if (idx < item.options.size - 1) idx + 1 else 0
				pendingValues[item.key] = item.options[nextIdx].first
				emitDisplayState(speak = true)
			}
			is SettingsDef.SubPage -> {
				pageStack.add(currentPageId to focusableCursorPos)
				navigateToPage(item.targetPageId)
			}
			is SettingsDef.KeyCapture -> enterKeyCaptureMode(item)
			is SettingsDef.SectionHeader -> { /* no-op */ }
			is SettingsDef.InfoText -> { /* no-op */ }
			is SettingsDef.Action -> when {
				item.actionId == ACTION_VOICE_FOR_LANGUAGE -> enterVoicePickMode(VoicePickTarget.TYPING)
				item.actionId == ACTION_VOICE_FOR_UI_LANGUAGE -> enterVoicePickMode(VoicePickTarget.UI)
				item.actionId == ACTION_GET_MORE_LANGUAGES && langpackServices != null -> enterLangManageMode()
				item.actionId in MJ_PRESETS -> applyMjPreset(item.actionId)
				// Share sheet (and Languages screen when no in-keyboard backend) open over the
				// current app (NEW_TASK).
				else -> onLaunchAction(item.actionId)
			}
		}
	}

	private fun handleLeft() {
		val item = currentFocusedItem() ?: return
		when (item) {
			is SettingsDef.Choice -> {
				if (gateClosed(item.enabledWhenKey, item.enabledWhenValue)) {
					emitDisplayState(speak = true)
					return
				}
				val current = getEffectiveChoice(item)
				val idx = item.options.indexOfFirst { it.first == current }
				val prevIdx = if (idx > 0) idx - 1 else item.options.size - 1
				pendingValues[item.key] = item.options[prevIdx].first
				emitDisplayState(speak = true)
			}
			is SettingsDef.Toggle -> {
				// LEFT/RIGHT also toggle for convenience
				if (gateClosed(item.enabledWhenKey, null)) {
					emitDisplayState(speak = true) // announce current (locked OFF) state
					return
				}
				val current = getEffectiveBoolean(item)
				pendingValues[item.key] = !current
				enforceMutualExclusion(item.key, !current)
				emitDisplayState(speak = true)
			}
			else -> { /* no-op for sliders, subpages */ }
		}
	}

	private fun handleRight() {
		val item = currentFocusedItem() ?: return
		when (item) {
			is SettingsDef.Choice -> {
				if (gateClosed(item.enabledWhenKey, item.enabledWhenValue)) {
					emitDisplayState(speak = true)
					return
				}
				val current = getEffectiveChoice(item)
				val idx = item.options.indexOfFirst { it.first == current }
				val nextIdx = if (idx < item.options.size - 1) idx + 1 else 0
				pendingValues[item.key] = item.options[nextIdx].first
				emitDisplayState(speak = true)
			}
			is SettingsDef.Toggle -> {
				if (gateClosed(item.enabledWhenKey, null)) {
					emitDisplayState(speak = true) // announce current (locked OFF) state
					return
				}
				val current = getEffectiveBoolean(item)
				pendingValues[item.key] = !current
				enforceMutualExclusion(item.key, !current)
				emitDisplayState(speak = true)
			}
			else -> { /* no-op */ }
		}
	}

	private fun handleApply() {
		// If auto-revert is counting down, APPLY confirms the change
		if (autoRevertKey != null) {
			confirmAutoRevert()
			return
		}

		if (pendingValues.isEmpty()) return // Nothing to apply

		// Apply ALL pending changes, not just the focused one.
		// Build a list of items to apply by looking up each pending key in the
		// current page items.
		val items = currentPageItems()
		val itemsByKey = items.associateBy { it.key }

		// Track the first dangerous setting encountered for auto-revert
		var dangerousKey: String? = null
		var dangerousOriginal: Any? = null

		val keysToApply = pendingValues.keys.toList()
		for (key in keysToApply) {
			val pendingVal = pendingValues[key] ?: continue
			val item = itemsByKey[key] ?: continue

			// Capture original before overwriting snapshot
			val originalVal = snapshotValues[key]

			// Write to SharedPreferences
			writeValue(key, pendingVal, item)

			// Update snapshot to reflect the new saved value
			snapshotValues[key] = pendingVal
			pendingValues.remove(key)

			// Notify IME for immediate reconfiguration
			onApplySettingImmediate(key, pendingVal)

			// Track first dangerous setting for auto-revert
			if (item.dangerous && originalVal != null && dangerousKey == null) {
				dangerousKey = key
				dangerousOriginal = originalVal
			}
		}

		// Start auto-revert timer for the first dangerous setting (if any)
		if (dangerousKey != null && dangerousOriginal != null) {
			startAutoRevertTimer(dangerousKey, dangerousOriginal)
		}

		announcePrompt(context.getString(R.string.settings_ctrl_applied))
		emitDisplayState()
	}

	private fun handleRevert() {
		val item = currentFocusedItem() ?: return
		val originalVal = snapshotValues[item.key] ?: return

		// Cancel any auto-revert for this setting
		if (autoRevertKey == item.key) {
			cancelAutoRevert()
		}

		pendingValues[item.key] = originalVal
		announcePrompt(context.getString(R.string.settings_ctrl_reverted))
		emitDisplayState(speak = false)
	}

	private fun handleBack() {
		// Cancel any pending auto-revert
		cancelAutoRevert()

		if (pageStack.isNotEmpty()) {
			val (parentPageId, parentCursorPos) = pageStack.removeAt(pageStack.size - 1)
			currentPageId = parentPageId
			val items = currentPageItems()
			focusableIndices = items.indices.filter { items[it] !is SettingsDef.SectionHeader && items[it] !is SettingsDef.InfoText }
			focusableCursorPos = parentCursorPos.coerceIn(0, (focusableIndices.size - 1).coerceAtLeast(0))
			cursorIndex = if (focusableIndices.isNotEmpty()) focusableIndices[focusableCursorPos] else 0
			emitDisplayState(speak = true)
		} else {
			// Exit settings mode entirely
			dispose()
			onSettingsExit()
		}
	}

	// ── Mutual exclusion constraints ──────────────────────────────────

	/**
	 * Enforce constraints between input method toggles:
	 * 1. Direct Selection and Directional Selection are mutually exclusive —
	 *    enabling one automatically disables the other.
	 * 2. When no Primary Input Method is set (NONE), at least one of Direct
	 *    Selection or Directional Selection must remain enabled so the user
	 *    can still activate keys.
	 *
	 * @return true if the toggle was allowed, false if it was blocked.
	 */
	private fun enforceMutualExclusion(key: String, newValue: Boolean): Boolean {
		val C = org.continuouspath.justtype.Constants

		if (newValue) {
			// Turning ON — enforce mutual exclusion
			when (key) {
				C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED -> {
					pendingValues[C.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED] = false
				}
				C.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED -> {
					pendingValues[C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED] = false
				}
			}
			return true
		}

		// Turning OFF — a gate toggle drags its dependents OFF with it.
		if (key == C.KEY_SHOW_ACCENTED_KEYS) {
			pendingValues[C.KEY_REQUIRE_ACCENTED_KEYS] = false
		}

		// Turning OFF — check if this would leave no way to activate keys
		if (key == C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED ||
			key == C.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED
		) {
			val primaryMethod = (pendingValues[C.KEY_INPUT_METHOD_PRIMARY] as? String)
				?: prefs.getString(C.KEY_INPUT_METHOD_PRIMARY, C.INPUT_METHOD_NONE)
				?: C.INPUT_METHOD_NONE

			if (primaryMethod == C.INPUT_METHOD_NONE) {
				// No primary method — check if the other selection method is on
				val otherKey = if (key == C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED) {
					C.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED
				} else {
					C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED
				}

				val otherEnabled = (pendingValues[otherKey] as? Boolean)
					?: prefs.getBoolean(otherKey)

				if (!otherEnabled) {
					// Would leave no input method — block the toggle
					pendingValues.remove(key) // Revert the pending change
					announcePrompt(context.getString(R.string.sr_input_methods_cannot_disable_both))
					return false
				}
			}
		}
		return true
	}

	// ── Key capture mode ────────────────────────────────────────────

	private fun enterKeyCaptureMode(item: SettingsDef.KeyCapture) {
		mode = SettingsMode.KEY_CAPTURE
		captureKey = item.key
		captureItem = item
		isCapturingKey = true

		// Initialize pending to current if not already pending
		if (item.key !in pendingValues) {
			val current = prefs.getInt(item.key, item.undefinedValue)
			pendingValues[item.key] = current
		}

		onRequestKeyboardPage("SettingsKeyCapture")
		emitDisplayState()
	}

	private fun handleKeyCaptureKey(keyIndex: Int) {
		// Only key 7 (BACK) is active during capture — all others are handled
		// via handleRawKeyCapture() from the IME's onKeyDown()
		if (keyIndex == 7) {
			cancelKeyCapture()
		}
		// Other keys are ignored — the real capture happens via handleRawKeyCapture()
	}

	private fun cancelKeyCapture() {
		isCapturingKey = false
		mode = SettingsMode.PAGE_NAV
		val item = captureItem
		if (item != null) {
			// Revert pending to original
			val original = snapshotValues[item.key]
			if (original != null) {
				pendingValues[item.key] = original
			} else {
				pendingValues.remove(item.key)
			}
		}
		captureKey = null
		captureItem = null
		onRequestKeyboardPage("Settings")
		emitDisplayState(speak = true)
	}

	/**
	 * For two-switch mode: if the user assigns the same key code to both
	 * RED and GREEN, clear the other one to avoid conflicts.
	 */
	private fun handleDuplicateSwitchPrevention(assignedKey: String, keyCode: Int) {
		val otherKey = when (assignedKey) {
			org.continuouspath.justtype.Constants.KEY_RED_SWITCH_CODE ->
				org.continuouspath.justtype.Constants.KEY_GREEN_SWITCH_CODE
			org.continuouspath.justtype.Constants.KEY_GREEN_SWITCH_CODE ->
				org.continuouspath.justtype.Constants.KEY_RED_SWITCH_CODE
			else -> return // Not a two-switch key, nothing to do
		}

		// Check if the other switch has the same keyCode
		val otherCurrent = (pendingValues[otherKey] as? Int)
			?: prefs.getInt(otherKey, org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED)
		if (otherCurrent == keyCode) {
			pendingValues[otherKey] = org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
		}
	}

	// ── Language manager state (LANG_MANAGE mode) ────────────────────

	private var langEntries: List<org.continuouspath.justtype.langpack.CatalogEntry> = emptyList()
	private var langCursor: Int = 0
	private var langLoading: Boolean = false
	private var langRemoveArmedId: String? = null

	/** Languages whose download/install this session is watching; completion auto-opens the voice picker. */
	private val langWatchingInstall = mutableSetOf<String>()
	private var cancelLangLoad: (() -> Unit)? = null
	private val langRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private val langRefreshRunnable = object : Runnable {
		override fun run() {
			if (mode != SettingsMode.LANG_MANAGE) return
			refreshLangCatalog(initial = false)
			langRefreshHandler.postDelayed(this, LANG_REFRESH_MS)
		}
	}

	// ── Language manager mode ────────────────────────────────────────

	/**
	 * In-keyboard language manager: the same catalog the Languages screen shows, driven entirely
	 * from the 8 keys. UP/DOWN (keys 1/6) move the highlight, DOWNLOAD (key 2) fetches an
	 * available language, REMOVE (key 0, the Revert-position key, pressed twice to confirm)
	 * deletes an installed one, BACK (key 7) returns. The catalog refreshes every second while
	 * open, which also verifies+installs any download that just finished.
	 */
	private fun enterLangManageMode() {
		val services = langpackServices ?: return
		mode = SettingsMode.LANG_MANAGE
		langEntries = emptyList()
		langCursor = 0
		langLoading = true
		langRemoveArmedId = null
		langWatchingInstall.clear()
		announcePrompt(context.getString(R.string.languages_state_loading))
		emitDisplayState()
		refreshLangCatalog(initial = true)
		langRefreshHandler.removeCallbacks(langRefreshRunnable)
		langRefreshHandler.postDelayed(langRefreshRunnable, LANG_REFRESH_MS)
	}

	/** Return to the language list (from the voice picker) preserving rows/cursor. */
	private fun resumeLangManageMode() {
		mode = SettingsMode.LANG_MANAGE
		emitDisplayState(speak = true)
		refreshLangCatalog(initial = false)
		langRefreshHandler.removeCallbacks(langRefreshRunnable)
		langRefreshHandler.postDelayed(langRefreshRunnable, LANG_REFRESH_MS)
	}

	private fun refreshLangCatalog(initial: Boolean) {
		val services = langpackServices ?: return
		cancelLangLoad?.invoke()
		cancelLangLoad = services.loadCatalog { entries ->
			if (mode != SettingsMode.LANG_MANAGE) return@loadCatalog
			val focusedId = langEntries.getOrNull(langCursor)?.id
			langLoading = false
			langEntries = entries
			langCursor = entries.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 } ?: langCursor.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
			if (langRemoveArmedId != null && entries.getOrNull(langCursor)?.id != langRemoveArmedId) langRemoveArmedId = null
			// A watched download just finished installing: flow straight into its voice picker,
			// exactly as if the user had pressed SELECT VOICE on the new row.
			val justInstalled = langWatchingInstall.firstOrNull { id ->
				entries.any { it.id == id && it.state == org.continuouspath.justtype.langpack.CatalogEntry.State.INSTALLED }
			}
			if (justInstalled != null) {
				langWatchingInstall.remove(justInstalled)
				langCursor = entries.indexOfFirst { it.id == justInstalled }.coerceAtLeast(0)
				enterVoicePickModeForLanguage(justInstalled)
				return@loadCatalog
			}
			emitDisplayState(speak = initial)
		}
	}

	private fun handleLangManageKey(keyIndex: Int) {
		val entry = langEntries.getOrNull(langCursor)
		when (keyIndex) {
			0 -> { // REMOVE (two-stage confirm)
				if (entry == null || entry.state != org.continuouspath.justtype.langpack.CatalogEntry.State.INSTALLED) return
				if (langRemoveArmedId != entry.id) {
					langRemoveArmedId = entry.id
					announcePrompt(context.getString(R.string.languages_remove_confirm_keyboard, entry.endonym))
					emitDisplayState()
				} else {
					langRemoveArmedId = null
					langpackServices?.remove(entry.id) { refreshLangCatalog(initial = false) }
					announcePrompt(context.getString(R.string.languages_removed_announce, entry.endonym))
					emitDisplayState()
				}
			}
			1 -> { // UP
				if (langCursor > 0) {
					langCursor--
					langRemoveArmedId = null
					emitDisplayState(speak = true)
				}
			}
			6 -> { // DOWN
				if (langCursor < langEntries.size - 1) {
					langCursor++
					langRemoveArmedId = null
					emitDisplayState(speak = true)
				}
			}
			2 -> when (entry?.state) { // DOWNLOAD / SELECT VOICE
				org.continuouspath.justtype.langpack.CatalogEntry.State.AVAILABLE -> {
					langpackServices?.download(entry)
					langWatchingInstall.add(entry.id)
					announcePrompt(context.getString(R.string.languages_download_started_announce, entry.endonym))
					refreshLangCatalog(initial = false)
				}
				org.continuouspath.justtype.langpack.CatalogEntry.State.INSTALLED ->
					enterVoicePickModeForLanguage(entry.id)
				else -> Unit
			}
			7 -> exitLangManageMode()
		}
	}

	private fun exitLangManageMode() {
		langRefreshHandler.removeCallbacks(langRefreshRunnable)
		cancelLangLoad?.invoke()
		cancelLangLoad = null
		langRemoveArmedId = null
		mode = SettingsMode.PAGE_NAV
		emitDisplayState(speak = true)
	}

	private fun buildLangManageDisplayState(speak: Boolean): SettingsDisplayState {
		val title = context.getString(R.string.languages_heading)
		val stateOf = { e: org.continuouspath.justtype.langpack.CatalogEntry ->
			when (e.state) {
				org.continuouspath.justtype.langpack.CatalogEntry.State.BUILT_IN -> context.getString(R.string.languages_state_bundled)
				org.continuouspath.justtype.langpack.CatalogEntry.State.INSTALLED -> context.getString(R.string.languages_state_installed)
				org.continuouspath.justtype.langpack.CatalogEntry.State.AVAILABLE ->
					context.getString(R.string.languages_state_size, formatLangBytes(e.downloadBytes))
				org.continuouspath.justtype.langpack.CatalogEntry.State.DOWNLOADING ->
					if (e.progressPct >= 0) {
						context.getString(R.string.languages_state_downloading_pct, e.progressPct)
					} else {
						context.getString(R.string.languages_state_downloading)
					}
				org.continuouspath.justtype.langpack.CatalogEntry.State.INSTALLING -> context.getString(R.string.languages_state_installing)
				org.continuouspath.justtype.langpack.CatalogEntry.State.UNAVAILABLE -> context.getString(R.string.languages_state_unavailable)
			}
		}

		val rows: List<SettingsRow>
		val speech: String?
		if (langLoading && langEntries.isEmpty()) {
			rows = listOf(SettingsRow(context.getString(R.string.languages_state_loading), null, null, isInfoText = true))
			speech = null
		} else {
			rows = langEntries.mapIndexed { index, e ->
				SettingsRow(
					label = e.endonym,
					valueText = stateOf(e),
					pendingValueText = if (e.id == langRemoveArmedId) context.getString(R.string.languages_action_remove) else null,
					isFocused = index == langCursor,
				)
			}
			speech = if (speak) langEntries.getOrNull(langCursor)?.let { "${it.endonym}. ${stateOf(it)}" } else null
		}

		val focused = langEntries.getOrNull(langCursor)
		val canDownload = focused?.state == org.continuouspath.justtype.langpack.CatalogEntry.State.AVAILABLE
		val canRemove = focused?.state == org.continuouspath.justtype.langpack.CatalogEntry.State.INSTALLED
		val key2Label = when {
			canDownload -> context.getString(R.string.languages_action_download)
			canRemove -> context.getString(R.string.languages_action_select_voice)
			else -> ""
		}
		return SettingsDisplayState(
			pageTitle = title,
			rows = rows,
			focusedRowIndex = langCursor.coerceIn(0, (rows.size - 1).coerceAtLeast(0)),
			centerText = title,
			keyLabels = listOf(
				if (canRemove) context.getString(R.string.languages_action_remove) else "", // Key 0
				context.getString(R.string.settings_ctrl_up), // Key 1
				key2Label, // Key 2
				"", // Key 3
				"", // Key 4
				"", // Key 5
				context.getString(R.string.settings_ctrl_down), // Key 6
				context.getString(R.string.settings_ctrl_back), // Key 7
			),
			speechText = speech,
			promptMessage = lastPromptMessage,
		)
	}

	private fun formatLangBytes(bytes: Long): String = when {
		bytes < 0 -> ""
		bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
		else -> "%d KB".format(bytes / 1000)
	}

	// ── Voice picker mode ────────────────────────────────────────────

	/**
	 * In-keyboard TTS voice picker for the current typing language: UP/DOWN (keys 1/6) move
	 * through the scanned voice list, TEST (key 2) speaks a sample with the highlighted voice
	 * via a short-lived preview engine, APPLY (key 5) persists it (the IME rebinds the live
	 * voice through the PREFS_KEY_LANGUAGE_TTS_VOICE listener), BACK (key 7) returns.
	 */
	private fun enterVoicePickMode(target: VoicePickTarget) {
		voicePickTarget = target
		when (target) {
			VoicePickTarget.TYPING -> {
				enterVoicePickModeForLanguage(prefs.getString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ENGLISH))
				return
			}
			VoicePickTarget.UI -> startVoicePick(UiVoice.prefKey(prefs), UiVoice.displayName(prefs), UiVoice.locale(prefs))
		}
	}

	/** Voice picker for a specific typing language (used by the language manager / post-install). */
	private fun enterVoicePickModeForLanguage(languageId: String) {
		voicePickTarget = VoicePickTarget.TYPING
		startVoicePick(languageId, CanonicalLanguages.endonymFor(languageId), localeForTypingLanguage(languageId))
	}

	private fun startVoicePick(prefKey: String, titleName: String, scanLocale: java.util.Locale) {
		voicePrefKey = prefKey
		voiceTitleName = titleName
		voicePickReturnMode = if (mode == SettingsMode.LANG_MANAGE) SettingsMode.LANG_MANAGE else SettingsMode.PAGE_NAV
		if (voicePickReturnMode == SettingsMode.LANG_MANAGE) {
			// Suspend the manager's polling while the picker is up.
			langRefreshHandler.removeCallbacks(langRefreshRunnable)
			cancelLangLoad?.invoke()
			cancelLangLoad = null
		}
		mode = SettingsMode.VOICE_PICK
		voiceScanning = true
		voiceOptions = emptyList()
		voiceLabels = emptyList()
		voiceCursor = 0
		cancelVoiceScan?.invoke()
		cancelVoiceScan = voiceServices.startScan(scanLocale) { options, defaultEngine ->
			if (mode != SettingsMode.VOICE_PICK) return@startScan
			voiceScanning = false
			val labeled = TtsVoiceNames.prepareForDisplay(context, prefs, options, voicePrefKey)
			voiceOptions = labeled.map { it.option }
			voiceLabels = labeled.map { it.label }
			voiceCursor = initialVoiceCursor(voiceOptions, defaultEngine)
			emitDisplayState(speak = true)
		}
		announcePrompt(context.getString(R.string.tts_voice_picker_scanning))
		emitDisplayState()
	}

	/** Start on the persisted voice when present, else the auto-selected best match. */
	private fun initialVoiceCursor(options: List<TtsVoiceOption>, defaultEngine: String?): Int {
		val saved = LanguageTtsPreferences.voiceFor(prefs, voicePrefKey)
		val savedIdx = options.indexOfFirst { it.enginePackage == saved?.enginePackage && it.voiceName == saved?.voiceName }
		if (savedIdx >= 0) return savedIdx
		val genderPref = prefs.getString(UiVoice.genderPrefKeyFor(voicePrefKey), Constants.TTS_GENDER_ANY)
		val auto = autoSelectVoice(
			options.map { TtsVoiceCandidate(it.enginePackage, it.voiceName, it.gender) },
			genderPref,
			defaultEngine,
		)
		return options.indexOfFirst { it.enginePackage == auto?.enginePackage && it.voiceName == auto?.voiceName }
			.coerceAtLeast(0)
	}

	private fun handleVoicePickKey(keyIndex: Int) {
		when (keyIndex) {
			1 -> { // UP
				if (voiceCursor > 0) {
					voiceCursor--
					emitDisplayState(speak = true)
				}
			}
			6 -> { // DOWN
				if (voiceCursor < voiceOptions.size - 1) {
					voiceCursor++
					emitDisplayState(speak = true)
				}
			}
			2 -> { // TEST — speaks with the candidate voice itself
				voiceOptions.getOrNull(voiceCursor)?.let {
					voiceServices.preview(it, voicePickLocale())
				}
			}
			5 -> { // APPLY
				val option = voiceOptions.getOrNull(voiceCursor) ?: return
				LanguageTtsPreferences.setVoice(prefs, voicePrefKey, TtsVoicePref(option.enginePackage, option.voiceName))
				announcePrompt(context.getString(R.string.settings_ctrl_applied))
				emitDisplayState()
			}
			7 -> exitVoicePickMode()
		}
	}

	private fun exitVoicePickMode() {
		cancelVoiceScan?.invoke()
		cancelVoiceScan = null
		voiceServices.stopPreview()
		voiceScanning = false
		if (voicePickReturnMode == SettingsMode.LANG_MANAGE) {
			resumeLangManageMode()
		} else {
			mode = SettingsMode.PAGE_NAV
			emitDisplayState(speak = true)
		}
	}

	private fun voicePickLocale(): java.util.Locale = when (voicePickTarget) {
		VoicePickTarget.TYPING -> localeForTypingLanguage(voicePrefKey)
		VoicePickTarget.UI -> UiVoice.locale(prefs)
	}

	private fun buildVoicePickDisplayState(speak: Boolean): SettingsDisplayState {
		val title = context.getString(R.string.tts_voice_picker_title, voiceTitleName)
		val saved = LanguageTtsPreferences.voiceFor(prefs, voicePrefKey)

		val rows: List<SettingsRow>
		val speech: String?
		when {
			voiceScanning -> {
				rows = listOf(SettingsRow(context.getString(R.string.tts_voice_picker_scanning), null, null, isInfoText = true))
				speech = null
			}
			voiceOptions.isEmpty() -> {
				rows = listOf(
					SettingsRow(context.getString(R.string.tts_voice_picker_none_found, voiceTitleName), null, null, isInfoText = true),
				)
				speech = if (speak) context.getString(R.string.tts_voice_picker_none_found, voiceTitleName) else null
			}
			else -> {
				rows = voiceOptions.mapIndexed { index, option ->
					val isCurrent = option.enginePackage == saved?.enginePackage && option.voiceName == saved?.voiceName
					SettingsRow(
						label = voiceLabels.getOrElse(index) { option.voiceName },
						valueText = if (isCurrent) context.getString(R.string.settings_ctrl_voice_current) else null,
						pendingValueText = null,
						isFocused = index == voiceCursor,
					)
				}
				speech = if (speak) voiceLabels.getOrNull(voiceCursor) ?: voiceOptions.getOrNull(voiceCursor)?.voiceName else null
			}
		}

		return SettingsDisplayState(
			pageTitle = title,
			rows = rows,
			focusedRowIndex = if (voiceScanning || voiceOptions.isEmpty()) 0 else voiceCursor,
			centerText = title,
			keyLabels = listOf(
				"", // Key 0
				context.getString(R.string.settings_ctrl_up), // Key 1
				if (voiceOptions.isNotEmpty()) context.getString(R.string.settings_ctrl_test) else "", // Key 2
				"", // Key 3
				"", // Key 4
				if (voiceOptions.isNotEmpty()) context.getString(R.string.settings_ctrl_apply) else "", // Key 5
				context.getString(R.string.settings_ctrl_down), // Key 6
				context.getString(R.string.settings_ctrl_back), // Key 7
			),
			speechText = speech,
			promptMessage = lastPromptMessage,
		)
	}

	// ── Slider adjustment mode ───────────────────────────────────────

	private fun enterSliderMode(item: SettingsDef) {
		mode = SettingsMode.SLIDER_ADJUST
		sliderSettingDef = item
		sliderStepSize = SliderStepSize.ONE_NOTCH
		testTimeIndex = 1 // Reset to 10 sec

		// Store original value when entering slider mode
		sliderOriginalValue = getEffectiveValue(item)

		// Initialize pending to current if not already pending
		if (item.key !in pendingValues) {
			val current = readCurrentValue(item)
			if (current != null) pendingValues[item.key] = current
		}

		onRequestKeyboardPage("SettingsSlider")
		emitDisplayState(speak = true)
	}

	private fun handleSliderKey(keyIndex: Int) {
		when (keyIndex) {
			0 -> handleSliderRevert()
			1 -> handleTestTimeCycle()
			2 -> handleStepSizeCycle()
			3 -> handleSliderDecrease()
			4 -> handleSliderIncrease()
			5 -> handleSliderApplyAndExit()
			6 -> handleSliderTest()
			7 -> handleSliderBack()
		}
	}

	private fun handleSliderRevert() {
		val item = sliderSettingDef ?: return
		val original = sliderOriginalValue ?: return
		pendingValues[item.key] = original
		announcePrompt(context.getString(R.string.settings_ctrl_reverted))
		emitDisplayState()
	}

	private fun handleTestTimeCycle() {
		testTimeIndex = (testTimeIndex + 1) % testTimeDurations.size
		announcePrompt(context.getString(R.string.settings_ctrl_seconds_speech, testTimeDurations[testTimeIndex]))
		emitDisplayState()
	}

	private fun handleStepSizeCycle() {
		sliderStepSize = sliderStepSize.next()
		announcePrompt(context.getString(R.string.settings_ctrl_step_speech, sliderStepSize.displayLabel(context)))
		emitDisplayState()
	}

	private fun handleSliderDecrease() {
		adjustSlider(-1)
	}

	private fun handleSliderIncrease() {
		adjustSlider(1)
	}

	private fun adjustSlider(direction: Int) {
		val item = sliderSettingDef ?: return
		when (item) {
			is SettingsDef.IntSlider -> {
				val current = (pendingValues[item.key] as? Int) ?: item.defaultValue
				val stepAmount = computeIntStepAmount(item)
				val newVal = (current + direction * stepAmount).coerceIn(item.min, item.max)
				// Snap to step grid
				val snapped = item.min + ((newVal - item.min) / item.step) * item.step
				pendingValues[item.key] = snapped.coerceIn(item.min, item.max)
				emitDisplayState(speak = true)
			}
			is SettingsDef.FloatSlider -> {
				val current = (pendingValues[item.key] as? Float) ?: item.defaultValue
				val stepAmount = computeFloatStepAmount(item)
				val newVal = (current + direction * stepAmount).coerceIn(item.min, item.max)
				// Snap to step grid
				val steps = Math.round((newVal - item.min) / item.step)
				val snapped = item.min + steps * item.step
				pendingValues[item.key] = snapped.coerceIn(item.min, item.max)
				emitDisplayState(speak = true)
			}
			else -> { /* shouldn't happen */ }
		}
	}

	private fun computeIntStepAmount(item: SettingsDef.IntSlider): Int = when (sliderStepSize) {
		SliderStepSize.ONE_NOTCH -> item.step
		SliderStepSize.FIVE_PERCENT -> (item.notchCount * 0.05f).toInt().coerceAtLeast(1) * item.step
		SliderStepSize.TEN_PERCENT -> (item.notchCount * 0.10f).toInt().coerceAtLeast(1) * item.step
	}

	private fun computeFloatStepAmount(item: SettingsDef.FloatSlider): Float = when (sliderStepSize) {
		SliderStepSize.ONE_NOTCH -> item.step
		SliderStepSize.FIVE_PERCENT -> (item.notchCount * 0.05f).toInt().coerceAtLeast(1) * item.step
		SliderStepSize.TEN_PERCENT -> (item.notchCount * 0.10f).toInt().coerceAtLeast(1) * item.step
	}

	private fun handleSliderApplyAndExit() {
		val item = sliderSettingDef ?: return
		val pendingVal = pendingValues[item.key] ?: return

		// Write to SharedPreferences
		writeValue(item.key, pendingVal, item)
		snapshotValues[item.key] = pendingVal
		pendingValues.remove(item.key)
		onApplySettingImmediate(item.key, pendingVal)

		// Return to page nav
		mode = SettingsMode.PAGE_NAV
		sliderSettingDef = null
		onRequestKeyboardPage("Settings")

		// Start auto-revert for dangerous settings
		if (item.dangerous) {
			sliderOriginalValue?.let { startAutoRevertTimer(item.key, it) }
		}

		announcePrompt(context.getString(R.string.settings_ctrl_applied))
		emitDisplayState()
	}

	private fun handleSliderTest() {
		val item = sliderSettingDef ?: return
		if (!item.testable) return

		val pendingVal = pendingValues[item.key] ?: return
		val currentSaved = readCurrentValue(item) ?: return

		// Save what we need to revert to
		testPreApplyKey = item.key
		testPreApplyValue = currentSaved

		// Temporarily write the pending value
		writeValue(item.key, pendingVal, item)
		onApplySettingImmediate(item.key, pendingVal)

		// Switch to test mode
		mode = SettingsMode.TEST_MODE
		onRequestKeyboardPage("SettingsTest")

		// Start the test timer
		val durationSec = testTimeDurations[testTimeIndex]
		announcePrompt(context.getString(R.string.settings_ctrl_testing_for_seconds, durationSec))
		testTimer?.cancel()
		testTimer = object : CountDownTimer(durationSec * 1000L, 1000L) {
			override fun onTick(millisUntilFinished: Long) {
				// Could update display with countdown, but keep it simple
			}
			override fun onFinish() {
				endTestMode()
			}
		}.start()

		emitDisplayState()
	}

	private fun handleSliderBack() {
		val item = sliderSettingDef ?: return
		// Discard any slider-specific pending changes — restore to what it was before slider entry
		val original = sliderOriginalValue
		if (original != null) {
			pendingValues[item.key] = original
		} else {
			pendingValues.remove(item.key)
		}

		mode = SettingsMode.PAGE_NAV
		sliderSettingDef = null
		onRequestKeyboardPage("Settings")
		emitDisplayState(speak = true)
	}

	// ── Test mode ────────────────────────────────────────────────────

	private fun handleTestKey(@Suppress("UNUSED_PARAMETER") keyIndex: Int) {
		// All 8 keys are inert in test mode — just visual/audio feedback
		// The IME handles the beep/flash; nothing to do here
	}

	private fun endTestMode() {
		testTimer?.cancel()
		testTimer = null

		// Revert the temporarily applied value
		val key = testPreApplyKey
		val originalVal = testPreApplyValue
		val item = sliderSettingDef

		if (key != null && originalVal != null && item != null) {
			writeValue(key, originalVal, item)
			onRevertSettingImmediate(key, originalVal)
		}

		testPreApplyKey = null
		testPreApplyValue = null

		// Return to slider adjustment mode
		mode = SettingsMode.SLIDER_ADJUST
		onRequestKeyboardPage("SettingsSlider")
		announcePrompt(context.getString(R.string.settings_ctrl_test_complete))
		emitDisplayState()
	}

	// ── Auto-revert timer ────────────────────────────────────────────

	private fun startAutoRevertTimer(key: String, originalValue: Any) {
		cancelAutoRevert()

		autoRevertKey = key
		autoRevertOriginalValue = originalValue
		autoRevertSecondsLeft = AUTO_REVERT_DURATION_SEC

		autoRevertTimer = object : CountDownTimer(AUTO_REVERT_DURATION_SEC * 1000L, 1000L) {
			override fun onTick(millisUntilFinished: Long) {
				autoRevertSecondsLeft = (millisUntilFinished / 1000).toInt() + 1
				emitDisplayState()
			}
			override fun onFinish() {
				performAutoRevert()
			}
		}.start()
	}

	private fun performAutoRevert() {
		val key = autoRevertKey ?: return
		val originalVal = autoRevertOriginalValue ?: return

		// Find the setting def to write correctly
		val item = findSettingDef(key)
		if (item != null) {
			writeValue(key, originalVal, item)
			onRevertSettingImmediate(key, originalVal)
			snapshotValues[key] = originalVal
		}

		announcePrompt(context.getString(R.string.settings_ctrl_auto_reverted))
		cancelAutoRevert()
		emitDisplayState()
	}

	/** Confirm the auto-revert (user pressed APPLY during countdown). */
	private fun confirmAutoRevert() {
		cancelAutoRevert()
		announcePrompt(context.getString(R.string.settings_ctrl_change_confirmed))
		emitDisplayState()
	}

	private fun cancelAutoRevert() {
		autoRevertTimer?.cancel()
		autoRevertTimer = null
		autoRevertKey = null
		autoRevertOriginalValue = null
		autoRevertSecondsLeft = 0
	}

	// ── Value reading/writing ────────────────────────────────────────

	private fun readCurrentValue(item: SettingsDef): Any? = when (item) {
		is SettingsDef.Toggle -> prefs.getBoolean(item.key, item.defaultValue)
		is SettingsDef.IntSlider -> prefs.getInt(item.key, item.defaultValue)
		is SettingsDef.FloatSlider -> prefs.getFloat(item.key, item.defaultValue)
		is SettingsDef.Choice -> prefs.getString(item.key, item.defaultValue) ?: item.defaultValue
		is SettingsDef.KeyCapture -> prefs.getInt(item.key, item.undefinedValue)
		is SettingsDef.SectionHeader -> null
		is SettingsDef.SubPage -> null
		is SettingsDef.InfoText -> null
		is SettingsDef.Action -> null
	}

	private fun getEffectiveValue(item: SettingsDef): Any? = pendingValues[item.key] ?: readCurrentValue(item)

	private fun getEffectiveBoolean(item: SettingsDef.Toggle): Boolean {
		if (gateClosed(item.enabledWhenKey, null)) return false
		return (pendingValues[item.key] as? Boolean) ?: prefs.getBoolean(item.key, item.defaultValue)
	}

	/** Dependent row gate: closed when the gate pref doesn't hold (boolean true,
	 *  or — with gateValue set — string equality). Rows with a closed gate are
	 *  shown but not adjustable. */
	private fun gateClosed(gateKey: String?, gateValue: String?): Boolean {
		if (gateKey == null) return false
		return if (gateValue != null) {
			((pendingValues[gateKey] as? String) ?: prefs.getString(gateKey, "")) != gateValue
		} else {
			!((pendingValues[gateKey] as? Boolean) ?: prefs.getBoolean(gateKey, true))
		}
	}

	private fun getEffectiveChoice(item: SettingsDef.Choice): String = (pendingValues[item.key] as? String)
		?: prefs.getString(item.key, item.defaultValue)
		?: item.defaultValue

	/** Apply a one-tap MJ preset: write its (movement-needed dp, resting-zone) pair and refresh. */
	private fun applyMjPreset(actionId: String) {
		val (sensitivityDp, deadZone) = MJ_PRESETS[actionId] ?: return
		// Clear any pending edits to these keys so the written preset is what the sliders show.
		pendingValues.remove(Constants.KEY_MOUSE_JOYSTICK_SENSITIVITY_DP)
		pendingValues.remove(Constants.KEY_MOUSE_JOYSTICK_DEADZONE)
		prefs.edit()
			.putInt(Constants.KEY_MOUSE_JOYSTICK_SENSITIVITY_DP, sensitivityDp)
			.putFloat(Constants.KEY_MOUSE_JOYSTICK_DEADZONE, deadZone)
			.apply()
		emitDisplayState(speak = true)
	}

	private fun writeValue(key: String, value: Any, item: SettingsDef) {
		prefs.edit().apply {
			when (item) {
				is SettingsDef.Toggle -> putBoolean(key, value as Boolean)
				is SettingsDef.IntSlider -> putInt(key, value as Int)
				is SettingsDef.FloatSlider -> putFloat(key, value as Float)
				is SettingsDef.Choice -> putString(key, value as String)
				is SettingsDef.KeyCapture -> putInt(key, value as Int)
				else -> { /* no-op */ }
			}
		}.apply()
	}

	private fun findSettingDef(key: String): SettingsDef? {
		for ((_, items) in SettingsRegistry.get().pages) {
			for (item in items) {
				if (item.key == key) return item
			}
		}
		return null
	}

	// ── Display state emission ───────────────────────────────────────

	private fun emitDisplayState(speak: Boolean = false) {
		val state = when (mode) {
			SettingsMode.PAGE_NAV -> buildPageNavDisplayState(speak)
			SettingsMode.SLIDER_ADJUST -> buildSliderDisplayState(speak)
			SettingsMode.TEST_MODE -> buildTestDisplayState()
			SettingsMode.KEY_CAPTURE -> buildKeyCaptureDisplayState()
			SettingsMode.VOICE_PICK -> buildVoicePickDisplayState(speak)
			SettingsMode.LANG_MANAGE -> buildLangManageDisplayState(speak)
		}
		onDisplayUpdate(state)
	}

	private fun buildPageNavDisplayState(speak: Boolean): SettingsDisplayState {
		val items = currentPageItems()
		val pageTitle = SettingsRegistry.get().pageTitles[currentPageId] ?: context.getString(R.string.settings_ctrl_settings_fallback)
		val focusedItem = currentFocusedItem()

		// Build rows
		val rows = items.mapIndexed { index, item ->
			val isFocused = index == cursorIndex
			when (item) {
				is SettingsDef.SectionHeader -> SettingsRow(
					label = item.label,
					valueText = null,
					pendingValueText = null,
					isSectionHeader = true,
				)
				is SettingsDef.InfoText -> SettingsRow(
					label = item.label,
					valueText = null,
					pendingValueText = null,
					isInfoText = true,
				)
				else -> {
					val savedVal = voiceActionValue(item) ?: formatValue(item, readCurrentValue(item))
					val pendingVal = pendingValues[item.key]?.let { formatValue(item, it) }
					SettingsRow(
						label = item.label,
						valueText = savedVal,
						pendingValueText = if (pendingVal != null && pendingVal != savedVal) pendingVal else null,
						description = item.description,
						isFocused = isFocused,
						isSubPage = item is SettingsDef.SubPage,
					)
				}
			}
		}

		// Build center text
		val centerText = buildCenterText(focusedItem)

		// Build key labels
		val keyLabels = buildPageNavKeyLabels(focusedItem)

		// Auto-revert message
		val revertMsg = if (autoRevertKey != null) {
			context.getString(R.string.settings_ctrl_keep_change_revert, autoRevertSecondsLeft)
		} else {
			null
		}

		// Speech
		val speechText = if (speak && focusedItem != null) {
			buildSpeechText(focusedItem)
		} else {
			null
		}

		// Compute focused row index for scroll
		val focusedRowIndex = cursorIndex

		return SettingsDisplayState(
			pageTitle = pageTitle,
			rows = rows,
			focusedRowIndex = focusedRowIndex,
			centerText = centerText,
			keyLabels = keyLabels,
			speechText = speechText,
			autoRevertMessage = revertMsg,
			promptMessage = lastPromptMessage.also { lastPromptMessage = null },
		)
	}

	private fun buildSliderDisplayState(speak: Boolean): SettingsDisplayState {
		val item = sliderSettingDef ?: return buildPageNavDisplayState(false)
		val pageTitle = context.getString(R.string.settings_ctrl_adjust_title, item.label)

		val originalFormatted = formatValue(item, sliderOriginalValue)
		val pendingVal = pendingValues[item.key]
		val pendingFormatted = formatValue(item, pendingVal)

		val centerText = "${item.label}\n$originalFormatted \u2192 $pendingFormatted"

		val testTimeLabel = context.getString(R.string.settings_ctrl_test_time, testTimeDurations[testTimeIndex])
		val stepLabel = context.getString(R.string.settings_ctrl_step_label, sliderStepSize.displayLabel(context))

		val keyLabels = listOf(
			context.getString(R.string.settings_ctrl_revert_to, originalFormatted), // Key 0
			testTimeLabel, // Key 1
			stepLabel, // Key 2
			context.getString(R.string.settings_ctrl_dec), // Key 3
			context.getString(R.string.settings_ctrl_inc), // Key 4
			context.getString(R.string.settings_ctrl_apply_and_exit), // Key 5
			if (item.testable) context.getString(R.string.settings_ctrl_test) else "", // Key 6
			context.getString(R.string.settings_ctrl_back), // Key 7
		)

		val speechText = if (speak) pendingFormatted else null

		// Single-row overlay showing just the slider info
		val rows = listOf(
			SettingsRow(
				label = item.label,
				valueText = originalFormatted,
				pendingValueText = if (pendingFormatted != originalFormatted) pendingFormatted else null,
				isFocused = true,
			),
		)

		return SettingsDisplayState(
			pageTitle = pageTitle,
			rows = rows,
			focusedRowIndex = 0,
			centerText = centerText,
			keyLabels = keyLabels,
			speechText = speechText,
			promptMessage = lastPromptMessage.also { lastPromptMessage = null },
		)
	}

	private fun buildTestDisplayState(): SettingsDisplayState {
		val item = sliderSettingDef
		val label = item?.label ?: context.getString(R.string.settings_ctrl_test_mode_fallback)
		return SettingsDisplayState(
			pageTitle = context.getString(R.string.settings_ctrl_testing_title, label),
			rows = listOf(
				SettingsRow(
					label = context.getString(R.string.settings_ctrl_test_mode),
					valueText = context.getString(R.string.settings_ctrl_press_any_key),
					pendingValueText = null,
					isFocused = true,
				),
			),
			focusedRowIndex = 0,
			centerText = context.getString(R.string.settings_ctrl_test_mode_center, label),
			keyLabels = (1..8).map { "$it" },
			promptMessage = lastPromptMessage.also { lastPromptMessage = null },
		)
	}

	private fun buildKeyCaptureDisplayState(): SettingsDisplayState {
		val item = captureItem
		val label = item?.label ?: context.getString(R.string.sr_single_switch_assign_switch)
		val currentCode = if (item != null) {
			(pendingValues[item.key] as? Int) ?: prefs.getInt(item.key, item.undefinedValue)
		} else {
			-1
		}
		val currentLabel = switchLabelForKeyCode(currentCode)

		return SettingsDisplayState(
			pageTitle = label,
			rows = listOf(
				SettingsRow(
					label = label,
					valueText = currentLabel,
					pendingValueText = null,
					isFocused = true,
				),
			),
			focusedRowIndex = 0,
			centerText = context.getString(R.string.sr_key_capture_prompt),
			keyLabels = listOf("", "", "", "", "", "", "", context.getString(R.string.settings_ctrl_back)),
			promptMessage = lastPromptMessage.also { lastPromptMessage = null },
		)
	}

	/**
	 * Returns a human-readable label for an Android keyCode.
	 * Handles common switch keys with friendly names, falls back to
	 * KeyEvent.keyCodeToString() for arbitrary codes.
	 */
	private fun switchLabelForKeyCode(keyCode: Int): String {
		if (keyCode < 0) return context.getString(R.string.sr_key_capture_not_assigned)
		return when (keyCode) {
			in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
				context.getString(R.string.sr_key_label_number, keyCode - KeyEvent.KEYCODE_0)
			in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
				context.getString(R.string.sr_key_label_numpad, keyCode - KeyEvent.KEYCODE_NUMPAD_0)
			KeyEvent.KEYCODE_SPACE -> context.getString(R.string.sr_key_label_space)
			KeyEvent.KEYCODE_ENTER -> context.getString(R.string.sr_key_label_enter)
			Constants.HEADBOARD_SWITCH_1_KEYCODE -> context.getString(R.string.headboard_switch_1)
			Constants.HEADBOARD_SWITCH_2_KEYCODE -> context.getString(R.string.headboard_switch_2)
			else -> {
				// Strip "KEYCODE_" prefix for readability
				val raw = KeyEvent.keyCodeToString(keyCode)
				raw.removePrefix("KEYCODE_").replace('_', ' ').lowercase()
					.replaceFirstChar { it.uppercase() }
			}
		}
	}

	// ── Center text building ─────────────────────────────────────────

	private fun buildCenterText(item: SettingsDef?): String {
		if (item == null) return ""
		return when (item) {
			is SettingsDef.Toggle -> {
				val effective = getEffectiveBoolean(item)
				"${item.label}\n${if (effective) context.getString(R.string.settings_ctrl_on) else context.getString(R.string.settings_ctrl_off)}"
			}
			is SettingsDef.IntSlider -> {
				val effective = (pendingValues[item.key] as? Int)
					?: prefs.getInt(item.key, item.defaultValue)
				"${item.label}\n${item.formatValue(effective)}"
			}
			is SettingsDef.FloatSlider -> {
				val effective = (pendingValues[item.key] as? Float)
					?: prefs.getFloat(item.key, item.defaultValue)
				"${item.label}\n${item.formatValue(effective)}"
			}
			is SettingsDef.Choice -> {
				val effectiveVal = getEffectiveChoice(item)
				val displayLabel = item.options.firstOrNull { it.first == effectiveVal }?.second ?: effectiveVal
				"${item.label}\n$displayLabel"
			}
			is SettingsDef.KeyCapture -> {
				val effective = (pendingValues[item.key] as? Int)
					?: prefs.getInt(item.key, item.undefinedValue)
				"${item.label}\n${switchLabelForKeyCode(effective)}"
			}
			is SettingsDef.SubPage -> {
				"${item.label}\n\u25B6" // ▶ arrow
			}
			is SettingsDef.SectionHeader -> item.label
			is SettingsDef.InfoText -> item.label
			is SettingsDef.Action -> voiceActionValue(item)?.let { "${item.label}\n$it" } ?: item.label
		}
	}

	// ── Key label building ───────────────────────────────────────────

	private fun buildPageNavKeyLabels(item: SettingsDef?): List<String> {
		// Key 0: REVERT (show original value)
		val revertLabel = if (item != null && item !is SettingsDef.SectionHeader && item !is SettingsDef.SubPage && item !is SettingsDef.InfoText) {
			val originalVal = snapshotValues[item.key]
			if (originalVal != null) context.getString(R.string.settings_ctrl_revert_to, formatValue(item, originalVal)) else context.getString(R.string.settings_ctrl_revert)
		} else {
			context.getString(R.string.settings_ctrl_revert)
		}

		// Key 2: context-sensitive action
		val actionLabel = when (item) {
			is SettingsDef.Toggle -> {
				val current = getEffectiveBoolean(item)
				if (current) context.getString(R.string.settings_ctrl_turn_off) else context.getString(R.string.settings_ctrl_turn_on)
			}
			is SettingsDef.IntSlider, is SettingsDef.FloatSlider -> context.getString(R.string.settings_ctrl_adjust)
			is SettingsDef.Choice -> context.getString(R.string.settings_ctrl_next)
			is SettingsDef.KeyCapture -> context.getString(R.string.sr_key_capture_assign)
			is SettingsDef.SubPage -> context.getString(R.string.settings_ctrl_open)
			is SettingsDef.Action -> context.getString(R.string.settings_ctrl_open)
			else -> ""
		}

		// Key 5: APPLY (or CONFIRM if auto-revert is counting down)
		val applyLabel = if (autoRevertKey != null && autoRevertKey == item?.key) {
			context.getString(R.string.settings_ctrl_confirm_seconds, autoRevertSecondsLeft)
		} else if (autoRevertKey != null) {
			context.getString(R.string.settings_ctrl_confirm_change_seconds, autoRevertSecondsLeft)
		} else {
			context.getString(R.string.settings_ctrl_apply)
		}

		return listOf(
			revertLabel, // Key 0
			context.getString(R.string.settings_ctrl_up), // Key 1
			actionLabel, // Key 2
			context.getString(R.string.settings_ctrl_left), // Key 3
			context.getString(R.string.settings_ctrl_right), // Key 4
			applyLabel, // Key 5
			context.getString(R.string.settings_ctrl_down), // Key 6
			context.getString(R.string.settings_ctrl_back), // Key 7
		)
	}

	// ── Speech ───────────────────────────────────────────────────────

	private fun buildSpeechText(item: SettingsDef): String = when (item) {
		is SettingsDef.Toggle -> {
			val value = getEffectiveBoolean(item)
			"${item.label}, ${if (value) context.getString(R.string.settings_ctrl_on_speech) else context.getString(R.string.settings_ctrl_off_speech)}"
		}
		is SettingsDef.IntSlider -> {
			val value = (pendingValues[item.key] as? Int)
				?: prefs.getInt(item.key, item.defaultValue)
			"${item.label}, ${item.formatValue(value)}"
		}
		is SettingsDef.FloatSlider -> {
			val value = (pendingValues[item.key] as? Float)
				?: prefs.getFloat(item.key, item.defaultValue)
			"${item.label}, ${item.formatValue(value)}"
		}
		is SettingsDef.Choice -> {
			val effectiveVal = getEffectiveChoice(item)
			val displayLabel = item.options.firstOrNull { it.first == effectiveVal }?.second ?: effectiveVal
			"${item.label}, $displayLabel"
		}
		is SettingsDef.KeyCapture -> {
			val value = (pendingValues[item.key] as? Int)
				?: prefs.getInt(item.key, item.undefinedValue)
			"${item.label}, ${switchLabelForKeyCode(value)}"
		}
		is SettingsDef.SubPage -> item.label
		is SettingsDef.SectionHeader -> item.label
		is SettingsDef.InfoText -> item.label
		is SettingsDef.Action -> voiceActionValue(item)?.let { "${item.label}, $it" } ?: item.label
	}

	// ── Value formatting ─────────────────────────────────────────────

	/** Current-selection summary for the two voice Action rows; null for every other item. */
	private fun voiceActionValue(item: SettingsDef): String? {
		if (item !is SettingsDef.Action) return null
		val prefKey = when (item.actionId) {
			ACTION_VOICE_FOR_LANGUAGE -> prefs.getString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ENGLISH)
			ACTION_VOICE_FOR_UI_LANGUAGE -> UiVoice.prefKey(prefs)
			else -> return null
		}
		return TtsVoiceNames.currentSelectionLabel(context, prefs, prefKey)
	}

	private fun formatValue(item: SettingsDef, value: Any?): String {
		if (value == null) return ""
		return when (item) {
			is SettingsDef.Toggle -> if (value as Boolean) context.getString(R.string.settings_ctrl_on) else context.getString(R.string.settings_ctrl_off)
			is SettingsDef.IntSlider -> item.formatValue(value as Int)
			is SettingsDef.FloatSlider -> item.formatValue(value as Float)
			is SettingsDef.Choice -> {
				val strVal = value as String
				item.options.firstOrNull { it.first == strVal }?.second ?: strVal
			}
			is SettingsDef.KeyCapture -> switchLabelForKeyCode(value as Int)
			is SettingsDef.SectionHeader -> ""
			is SettingsDef.SubPage -> ""
			is SettingsDef.InfoText -> ""
			is SettingsDef.Action -> ""
		}
	}

	companion object {
		const val ACTION_VOICE_FOR_LANGUAGE = "voice_for_language"
		const val ACTION_VOICE_FOR_UI_LANGUAGE = "voice_for_ui_language"
		const val ACTION_GET_MORE_LANGUAGES = "get_more_languages"
		private const val LANG_REFRESH_MS = 1000L

		// Mouse-joystick one-tap presets: each writes a (movement-needed dp/sec, resting-zone) pair.
		// Higher dp/sec = a faster push needed = less sensitive; lower = more sensitive. dp/sec because
		// velocity is time-normalized; values calibrated from real full-push speeds (~1400-2500 dp/sec).
		private const val ACTION_MJ_PRESET_LIGHT = "mj_preset_light"
		private const val ACTION_MJ_PRESET_STANDARD = "mj_preset_standard"
		private const val ACTION_MJ_PRESET_FIRM = "mj_preset_firm"
		private val MJ_PRESETS = mapOf(
			ACTION_MJ_PRESET_LIGHT to (500 to 0.25f),
			ACTION_MJ_PRESET_STANDARD to (800 to 0.20f),
			ACTION_MJ_PRESET_FIRM to (1400 to 0.15f),
		)

		/** Duration in seconds for the auto-revert countdown on dangerous settings. */
		const val AUTO_REVERT_DURATION_SEC = 15
	}
}
