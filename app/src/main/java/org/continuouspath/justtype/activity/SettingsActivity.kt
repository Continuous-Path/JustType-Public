package org.continuouspath.justtype.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import org.continuouspath.justtype.LanguageTtsPreferences
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.TtsVoiceNames
import org.continuouspath.justtype.UiVoice
import org.continuouspath.justtype.settings.PageHooks
import org.continuouspath.justtype.settings.RenderResult
import org.continuouspath.justtype.settings.SettingsDef
import org.continuouspath.justtype.settings.SettingsDefaults
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRenderer
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.applySafeKeyboardDefaults
import org.continuouspath.justtype.settings.effectiveInputMethod
import org.continuouspath.justtype.settings.forceDirectSelectionFallback
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getString
import org.continuouspath.justtype.welcome.WelcomeGuideActivity
import org.continuouspath.justtype.Constants as C

/**
 * Touchscreen settings UI. Renders the `main` page of [SettingsRegistry] via
 * [SettingsRenderer]; bespoke header + cross-key conditional state + a
 * confirmation dialog on Letter Arrangement live in this file.
 *
 * Sub-pages (`input_methods`, `vocabulary`, etc.) are reached via dedicated
 * activities today — the renderer's SubPage row clicks route through
 * [navigateToSubPage].
 */
class SettingsActivity : AppCompatActivity() {

	private lateinit var renderer: SettingsRenderer
	private lateinit var repo: SettingsRepository
	private var rendered: RenderResult? = null
	private var crashPromptDialog: AlertDialog? = null

	/** Registry instance the page was rendered from; used to detect staleness in [onResume]. */
	private var registryAtRender: SettingsRegistry? = null

	/** Which registry page this instance renders; sub-pages reuse this activity. */
	private var pageId: String = PAGE_MAIN

	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_settings)

		pageId = intent.getStringExtra(EXTRA_PAGE_ID) ?: PAGE_MAIN
		setupHeader()
		setupImeWarningBanner()
		setupNavWarningBanner()

		val registry = SettingsRegistry.getInstance(applicationContext)
		registryAtRender = registry
		repo = SettingsRepository.getInstance(applicationContext)
		SettingsDefaults.ensureAll(repo, applicationContext)
		maybePromptOverlayPermission()
		maybePromptNavPermission()
		maybeLaunchWelcomeGuideOnFirstRun()

		renderer = SettingsRenderer(
			context = this,
			repo = repo,
			scope = lifecycleScope,
			onSubPageClicked = ::navigateToSubPage,
			onActionClicked = ::handleAction,
		)

		val container: LinearLayout = findViewById(R.id.settingsContent)
		val pageItems = registry.pages[pageId].orEmpty()

		// Hooks are per-page because the settings they anchor to now live on sub-pages:
		// Letter Arrangement gets a bespoke confirm-on-change row (Keyboard Setup), and each
		// voice picker sits directly under its voice-type preference (Language Options). The
		// skipped keys are rendered by those injected rows instead of the default renderer.
		val skipKeys = when (pageId) {
			PAGE_KEYBOARD_SETUP -> setOf(C.KEY_LAYOUT_MODE)
			PAGE_LANGUAGE_OPTIONS -> setOf("voice_for_language", "voice_for_ui_language")
			else -> emptySet()
		}
		val hooks = PageHooks(
			beforePage = if (pageId == PAGE_MAIN) ::injectWelcomeGuideRow else null,
			afterKey = when (pageId) {
				PAGE_KEYBOARD_SETUP -> mapOf(C.KEY_LAYOUT_MODE to ::injectLetterArrangementRow)
				PAGE_LANGUAGE_OPTIONS -> mapOf(
					C.KEY_TTS_VOICE_GENDER to ::injectVoiceForLanguageRow,
					C.KEY_TTS_UI_VOICE_GENDER to ::injectVoiceForUiLanguageRow,
				)
				else -> emptyMap()
			},
		)

		rendered = renderer.renderPage(pageItems, container, hooks, skipKeys = skipKeys)
		if (pageId == PAGE_MAIN) injectEmergencyResetRow(container)

		attachLanguageRelocalizeListener()
		attachTtsAutoPickListener()
		attachInfoIcons(pageItems)
		attachCrossKeyState()
		gateDeveloperEntry()
	}

	// ── Lifecycle ────────────────────────────────────────────────────────

	override fun onResume() {
		super.onResume()
		updateImeWarningBanner()
		updateNavWarningBanner()
		maybePromptCrashReport()
		// Re-render when the registry was rebuilt while this activity sat in the back stack —
		// e.g. LanguagesActivity reinitializes it after a langpack install/remove, which changes
		// the typing-language options (and the label shown for the current value). Mirrors the
		// recreate() precedent in attachLanguageRelocalizeListener.
		if (isRenderedPageStale()) recreate()
	}

	/** True when the page was rendered from a registry instance that has since been replaced. */
	internal fun isRenderedPageStale(): Boolean = registryAtRender !== SettingsRegistry.getInstance(applicationContext)

	/**
	 * After a crash, offer (once) to email a content-safe report. The pending flag is set by
	 * [org.continuouspath.justtype.CrashHandler] and survives the IME's crash-recovery clear; we
	 * clear it as soon as the prompt shows so it asks only once per crash.
	 */
	private fun maybePromptCrashReport() {
		if (!repo.getBoolean(C.KEY_CRASH_REPORT_PENDING, false)) return
		repo.putBoolean(C.KEY_CRASH_REPORT_PENDING, false)
		if (!repo.getBoolean(C.KEY_CRASH_REPORT_PROMPT_ENABLED, true)) return
		crashPromptDialog = AlertDialog.Builder(this)
			.setTitle(R.string.crash_prompt_title)
			.setMessage(R.string.crash_prompt_message)
			.setPositiveButton(R.string.crash_prompt_send) { _, _ ->
				org.continuouspath.justtype.logging.DebugLogShareHelper.emailCrashReport(this)
			}
			.setNegativeButton(R.string.crash_prompt_dismiss, null)
			.show()
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}

	override fun onDestroy() {
		// An unanswered prompt would leak its window; re-arm the flag so the recreated
		// activity (e.g. after rotation) asks again.
		crashPromptDialog?.let {
			if (it.isShowing) {
				repo.putBoolean(C.KEY_CRASH_REPORT_PENDING, true)
				it.dismiss()
			}
		}
		crashPromptDialog = null
		// Settings hub torn down — release the shared prompt-TTS engine (re-inits lazily on next use).
		if (isFinishing) SettingsSpeechController.shutdown()
		super.onDestroy()
	}

	// ── Bespoke header / banner / permission prompt ──────────────────────

	private fun setupHeader() {
		val backButton: ImageButton = findViewById(R.id.backButton)
		val titleText: TextView = findViewById(R.id.titleText)

		// Sub-pages reuse this activity, so the header shows the registry's title for the page.
		if (pageId != PAGE_MAIN) {
			SettingsRegistry.getInstance(applicationContext).pageTitles[pageId]?.let {
				titleText.text = it
			}
		}

		// CATEGORY_LAUNCHER distinguishes the app icon from the system IME-settings launch (both use ACTION_MAIN).
		val isLaunchedFromAppIcon = intent.hasCategory(Intent.CATEGORY_LAUNCHER)
		if (isLaunchedFromAppIcon) backButton.visibility = View.GONE
		backButton.setOnClickListener { finish() }

		// Easter egg: 5 taps within 2s reveals the Developer Settings row.
		// The reveal flag is read by gateDeveloperEntry() after the renderer
		// has produced the row view.
		var tapCount = 0
		var lastTap = 0L
		titleText.setOnClickListener {
			val now = SystemClock.uptimeMillis()
			if (now - lastTap > TAP_WINDOW_MS) tapCount = 0
			lastTap = now
			tapCount += 1
			if (tapCount >= TAP_THRESHOLD) {
				rendered?.viewByKey?.get(NAV_DEVELOPER_KEY)?.visibility = View.VISIBLE
			}
		}

		// Tier indicator: surface the build tier in the title for non-release
		// builds so support sessions can confirm what the user is running.
		// Tier 0 (release) shows the title as-is — no marker needed.
		val tierSuffix = when (org.continuouspath.justtype.BuildConfig.DEBUG_TIER) {
			1 -> "  ·  ${getString(R.string.settings_tier_beta)}"
			2 -> "  ·  ${getString(R.string.settings_tier_internal)}"
			else -> ""
		}
		if (tierSuffix.isNotEmpty()) {
			titleText.text = "${titleText.text}$tierSuffix"
		}
	}

	private fun setupImeWarningBanner() {
		val openSettingsButton: Button = findViewById(R.id.openKeyboardSettingsButton)
		openSettingsButton.setOnClickListener {
			startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
		}
	}

	private fun setupNavWarningBanner() {
		findViewById<Button>(R.id.enableNavKeyboardButton).setOnClickListener {
			runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
		}
	}

	private fun updateNavWarningBanner() {
		findViewById<LinearLayout>(R.id.navWarningBanner).visibility =
			if (isNavServiceEnabled()) View.GONE else View.VISIBLE
	}

	/** True when JustType's Navigation accessibility service is enabled in system settings. */
	private fun isNavServiceEnabled(): Boolean {
		val enabled = Settings.Secure.getString(
			contentResolver,
			Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
		) ?: return false
		return enabled.split(':').any { it.startsWith("$packageName/") }
	}

	private fun maybePromptNavPermission() {
		if (isNavServiceEnabled()) return
		if (repo.getBoolean(C.KEY_NAV_PERMISSION_REQUESTED, false)) return
		repo.putBoolean(C.KEY_NAV_PERMISSION_REQUESTED, true)
		AlertDialog.Builder(this)
			.setTitle(R.string.navigation_mode_service_prompt_title)
			.setMessage(R.string.navigation_mode_service_prompt_message)
			.setPositiveButton(R.string.settings_enable_nav_keyboard) { _, _ ->
				runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
			}
			.setNegativeButton(R.string.overlay_permission_later, null)
			.show()
	}

	private fun updateImeWarningBanner() {
		val warningBanner: LinearLayout = findViewById(R.id.imeWarningBanner)
		warningBanner.visibility = if (isJustTypeImeEnabled()) View.GONE else View.VISIBLE
		// Header overlays the ScrollView, so pad the ScrollView by the header's
		// measured height so initial content isn't hidden behind it.
		val header: LinearLayout = findViewById(R.id.settingsHeader)
		val scroll: ScrollView = findViewById(R.id.settingsScrollView)
		header.post {
			scroll.setPadding(
				scroll.paddingLeft,
				header.height,
				scroll.paddingRight,
				scroll.paddingBottom,
			)
		}
	}

	private fun isJustTypeImeEnabled(): Boolean {
		return try {
			val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
				?: return false
			imm.enabledInputMethodList.any { it.packageName == packageName }
		} catch (_: Exception) {
			false
		}
	}

	private fun maybePromptOverlayPermission() {
		if (Settings.canDrawOverlays(this)) return
		if (repo.getBoolean(C.KEY_OVERLAY_PERMISSION_REQUESTED, false)) return
		repo.putBoolean(C.KEY_OVERLAY_PERMISSION_REQUESTED, true)
		AlertDialog.Builder(this)
			.setTitle(R.string.overlay_permission_title)
			.setMessage(R.string.overlay_permission_message)
			.setPositiveButton(R.string.overlay_permission_grant) { _, _ ->
				val intent = Intent(
					Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
					"package:$packageName".toUri(),
				)
				try {
					startActivity(intent)
				} catch (_: Exception) {}
			}
			.setNegativeButton(R.string.overlay_permission_later, null)
			.show()
	}

	// ── Sub-page routing ──────────────────────────────────────────────────

	private fun navigateToSubPage(targetPageId: String) {
		if (targetPageId == "navigation_mode") {
			startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_NAVIGATION_MODE))
			return
		}
		if (targetPageId == PAGE_LANGUAGE_OPTIONS || targetPageId == PAGE_KEYBOARD_SETUP) {
			startActivity(
				Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE_ID, targetPageId),
			)
			return
		}
		val target = when (targetPageId) {
			"input_methods" -> InputMethodsActivity::class.java
			"vocabulary" -> VocabularyManagementActivity::class.java
			"developer" -> DeveloperSettingsActivity::class.java
			"backup_info" -> BackupRestoreActivity::class.java
			else -> null
		}
		if (target != null) startActivity(Intent(this, target))
	}

	// ── Action button dispatch ────────────────────────────────────────────

	private fun handleAction(actionId: String) {
		when (actionId) {
			ACTION_SUBMIT_FEEDBACK -> org.continuouspath.justtype.logging.DebugLogShareHelper.shareLogs(this)
			ACTION_GET_MORE_LANGUAGES -> startActivity(Intent(this, LanguagesActivity::class.java))
			else -> { /* unknown action — silently ignored to keep registry rollouts safe */ }
		}
	}

	// ── Welcome Guide entry ───────────────────────────────────────────────

	private fun maybeLaunchWelcomeGuideOnFirstRun() {
		if (repo.getBoolean(C.KEY_WELCOME_GUIDE_SEEN, false)) return
		startActivity(WelcomeGuideActivity.intent(this))
	}

	private fun injectWelcomeGuideRow(container: ViewGroup) {
		val row = LayoutInflater.from(this)
			.inflate(R.layout.row_setting_subpage, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		label.text = getString(R.string.welcome_guide_open_button)
		row.setOnClickListener {
			startActivity(WelcomeGuideActivity.intent(this))
		}
		container.addView(row)
	}

	// ── Emergency Reset (anti-lockout recovery) ───────────────────────────

	/**
	 * Caregiver-reachable recovery: forces Direct Selection so a user locked out
	 * by a broken input method can type again. Lives at the foot of the main page
	 * — reachable from the launcher with no adb, away from accidental taps.
	 */
	private fun injectEmergencyResetRow(container: ViewGroup) {
		val inflater = LayoutInflater.from(this)

		val header = inflater.inflate(R.layout.row_setting_section_header, container, false) as TextView
		header.text = getString(R.string.emergency_reset_section)
		ViewCompat.setAccessibilityHeading(header, true)
		container.addView(header)

		val row = inflater.inflate(R.layout.row_setting_subpage, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val description: TextView = row.findViewById(R.id.rowDescription)
		label.text = getString(R.string.emergency_reset_label)
		description.text = getString(R.string.emergency_reset_description)
		description.visibility = View.VISIBLE
		row.contentDescription = "${label.text}. ${description.text}"
		row.setOnClickListener { confirmEmergencyReset() }
		container.addView(row)
	}

	private fun confirmEmergencyReset() {
		AlertDialog.Builder(this)
			.setTitle(R.string.emergency_reset_confirm_title)
			.setMessage(R.string.emergency_reset_confirm_message)
			.setPositiveButton(R.string.emergency_reset_confirm_button) { _, _ ->
				repo.forceDirectSelectionFallback()
				repo.applySafeKeyboardDefaults()
				Toast.makeText(this, R.string.emergency_reset_done, Toast.LENGTH_LONG).show()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	// ── Bespoke Letter Arrangement row (confirm-on-change) ────────────────

	private fun injectLetterArrangementRow(container: ViewGroup) {
		val row = LayoutInflater.from(this)
			.inflate(R.layout.row_setting_choice, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val valueView: TextView = row.findViewById(R.id.rowValue)
		label.text = getString(R.string.sr_main_letter_arrangement)

		fun valueLabel(value: String): String = getString(
			if (value == C.MODE_ALPHA) R.string.sr_opt_alphabetical else R.string.sr_opt_optimized,
		)
		valueView.text = valueLabel(repo.getString(C.KEY_LAYOUT_MODE))

		row.setOnClickListener {
			val current = repo.getString(C.KEY_LAYOUT_MODE)
			val options = listOf(
				C.MODE_OPT to getString(R.string.sr_opt_optimized),
				C.MODE_ALPHA to getString(R.string.sr_opt_alphabetical),
			)
			val labels = options.map { it.second }.toTypedArray()
			val currentIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0)

			AlertDialog.Builder(this)
				.setTitle(R.string.sr_main_letter_arrangement)
				.setSingleChoiceItems(labels, currentIndex) { dialog, which ->
					val newValue = options[which].first
					if (newValue == current) {
						dialog.dismiss()
						return@setSingleChoiceItems
					}
					AlertDialog.Builder(this)
						.setTitle(R.string.letter_arrangement_confirm_title)
						.setMessage(R.string.letter_arrangement_confirm_message)
						.setPositiveButton(R.string.letter_arrangement_confirm_yes) { _, _ ->
							repo.putString(C.KEY_LAYOUT_MODE, newValue)
							valueView.text = valueLabel(newValue)
							dialog.dismiss()
						}
						.setNegativeButton(R.string.letter_arrangement_confirm_no) { _, _ -> dialog.dismiss() }
						.setOnCancelListener { dialog.dismiss() }
						.show()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}

		container.addView(row)

		SettingsInfoHelper.attachInfoIcon(
			row as ViewGroup,
			getString(R.string.sr_main_letter_arrangement),
			getString(R.string.info_prompt_letter_arrangement),
		)

		// Keep label in sync with external writes (e.g. settings mode).
		val listener = repo.addMainThreadListener { _, k ->
			if (k == C.KEY_LAYOUT_MODE) {
				valueView.text = valueLabel(repo.getString(C.KEY_LAYOUT_MODE))
			}
		}
		lifecycleScope.coroutineContext[kotlinx.coroutines.Job]
			?.invokeOnCompletion { repo.removeChangeListener(listener) }
	}

	// ── Bespoke "Voice for {language}" row + TTS auto-pick ────────────────

	/** Row (after Typing Language) that opens the voice picker for the currently-selected language. */
	/** Pref keys that change what the injected voice rows display as their value. */
	private val voiceSummaryKeys =
		setOf(C.PREFS_KEY_LANGUAGE_TTS_VOICE, C.PREFS_KEY_TTS_VOICE_NAMES, C.KEY_TTS_VOICE_GENDER, C.KEY_TTS_UI_VOICE_GENDER)

	private fun injectVoiceForLanguageRow(container: ViewGroup) {
		val row = LayoutInflater.from(this).inflate(R.layout.row_setting_choice, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val valueView: TextView = row.findViewById(R.id.rowValue)

		fun currentLangId() = repo.getString(C.KEY_TYPING_LANGUAGE, C.TYPING_LANGUAGE_ENGLISH)
		label.text = getString(R.string.sr_main_speech_voice)
		fun refresh() {
			valueView.text = TtsVoiceNames.currentSelectionLabel(this, repo, currentLangId())
		}
		refresh()

		row.setOnClickListener { TtsVoicePicker(this, repo, currentLangId()).show() }
		container.addView(row)

		SettingsInfoHelper.attachInfoIcon(
			row as ViewGroup,
			getString(R.string.sr_main_speech_voice),
			getString(R.string.sr_main_speech_voice_desc),
		)

		val listener = repo.addMainThreadListener { _, k ->
			if (k in voiceSummaryKeys || k == C.KEY_TYPING_LANGUAGE) refresh()
		}
		lifecycleScope.coroutineContext[kotlinx.coroutines.Job]
			?.invokeOnCompletion { repo.removeChangeListener(listener) }
	}

	/** "Voice for UI Language" with the current selection as its value (mirrors the speech-voice row). */
	private fun injectVoiceForUiLanguageRow(container: ViewGroup) {
		val row = LayoutInflater.from(this).inflate(R.layout.row_setting_choice, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val valueView: TextView = row.findViewById(R.id.rowValue)

		label.text = getString(R.string.sr_main_voice_for_ui_language)
		fun refresh() {
			valueView.text = TtsVoiceNames.currentSelectionLabel(this, repo, UiVoice.prefKey(repo))
		}
		refresh()

		row.setOnClickListener { TtsVoicePicker.forUiLanguage(this, repo).show() }
		container.addView(row)

		SettingsInfoHelper.attachInfoIcon(
			row as ViewGroup,
			getString(R.string.sr_main_voice_for_ui_language),
			getString(R.string.sr_main_voice_for_ui_language_desc),
		)

		val listener = repo.addMainThreadListener { _, k ->
			if (k in voiceSummaryKeys || k == C.KEY_APP_LANGUAGE) refresh()
		}
		lifecycleScope.coroutineContext[kotlinx.coroutines.Job]
			?.invokeOnCompletion { repo.removeChangeListener(listener) }
	}

	/** On switching to a language with no stored voice, auto-discover + confirm one via the picker. */
	private fun attachTtsAutoPickListener() {
		val listener = repo.addMainThreadListener { _, key ->
			if (key == C.KEY_TYPING_LANGUAGE && !isFinishing && !isDestroyed) {
				val id = repo.getString(C.KEY_TYPING_LANGUAGE, C.TYPING_LANGUAGE_ENGLISH)
				if (LanguageTtsPreferences.voiceFor(repo, id) == null) {
					TtsVoicePicker(this, repo, id).show()
				}
			}
		}
		lifecycleScope.coroutineContext[kotlinx.coroutines.Job]
			?.invokeOnCompletion { repo.removeChangeListener(listener) }
	}

	// ── UI Language relocalization ────────────────────────────────────────

	private fun attachLanguageRelocalizeListener() {
		val listener = repo.addMainThreadListener { _, key ->
			if (key == C.KEY_APP_LANGUAGE && !isFinishing && !isDestroyed) {
				SettingsRegistry.reinitialize(this)
				recreate()
			}
		}
		lifecycleScope.coroutineContext[kotlinx.coroutines.Job]
			?.invokeOnCompletion { repo.removeChangeListener(listener) }
	}

	// ── Info icons (helper drives off resolved label/prompt strings) ─────

	private fun attachInfoIcons(items: List<SettingsDef>) {
		val viewByKey = rendered?.viewByKey ?: return
		items
			.mapNotNull { def -> def.infoPrompt?.let { Triple(def.key, def.label, it) } }
			.forEach { (key, label, prompt) ->
				val rowView = viewByKey[key] as? ViewGroup ?: return@forEach
				SettingsInfoHelper.attachInfoIcon(rowView, label, prompt)
			}
	}

	// ── Cross-key conditional state ───────────────────────────────────────

	private fun attachCrossKeyState() {
		val viewByKey = rendered?.viewByKey ?: return

		fun applyKeyboardSizeEnabled() {
			val isSingleSwitch = repo.effectiveInputMethod() == C.INPUT_METHOD_SINGLE_SWITCH
			viewByKey[C.KEY_KEYBOARD_SIZE_RATIO]?.let {
				it.isEnabled = !isSingleSwitch
				it.alpha = if (isSingleSwitch) DISABLED_ALPHA else 1.0f
			}
		}

		fun applyKeyHistoryVisibility() {
			val on = repo.getBoolean(C.KEY_SHOW_BUTTONS_PRESSED)
			viewByKey[C.KEY_KEY_HISTORY_HEIGHT_PERCENT]?.visibility =
				if (on) View.VISIBLE else View.GONE
			viewByKey[C.KEY_KEY_HISTORY_SHRINK_TO_FIT]?.visibility =
				if (on) View.VISIBLE else View.GONE
			viewByKey[C.KEY_KEY_HISTORY_HIGHLIGHT]?.visibility =
				if (on) View.VISIBLE else View.GONE
			viewByKey[C.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE]?.visibility =
				if (on) View.VISIBLE else View.GONE
			viewByKey[C.KEY_KEY_HISTORY_MARK_LATEST]?.visibility =
				if (on) View.VISIBLE else View.GONE
		}

		fun applyCaseTypeCascade() {
			val variantsOn = repo.getBoolean(C.KEY_CASETYPE_VARIANTS_ENABLED)
			val deferredOn = repo.getBoolean(C.KEY_CASETYPE_DEFERRED_EXPAND)
			val delayActive = variantsOn && deferredOn
			viewByKey[C.KEY_CASETYPE_DEFERRED_EXPAND]?.let {
				it.isEnabled = variantsOn
				it.alpha = if (variantsOn) 1.0f else DISABLED_ALPHA
			}
			viewByKey[C.KEY_CASETYPE_EXPAND_DELAY_MS]?.let {
				it.isEnabled = delayActive
				it.alpha = if (delayActive) 1.0f else DISABLED_ALPHA
			}
		}

		fun applySelectionInvariant() {
			val abbrev = repo.getBoolean(C.KEY_SHOW_ABBREV_IN_SELECTION)
			val phrase = repo.getBoolean(C.KEY_SHOW_PHRASE_IN_SELECTION)
			if (!abbrev && !phrase) repo.putBoolean(C.KEY_SHOW_ABBREV_IN_SELECTION, true)
		}

		applyKeyboardSizeEnabled()
		applyKeyHistoryVisibility()
		applyCaseTypeCascade()

		val listener = repo.addMainThreadListener { _, key ->
			when (key) {
				C.KEY_INPUT_METHOD_PRIMARY -> applyKeyboardSizeEnabled()
				C.KEY_SHOW_BUTTONS_PRESSED -> applyKeyHistoryVisibility()
				C.KEY_CASETYPE_VARIANTS_ENABLED, C.KEY_CASETYPE_DEFERRED_EXPAND -> applyCaseTypeCascade()
				C.KEY_SHOW_ABBREV_IN_SELECTION, C.KEY_SHOW_PHRASE_IN_SELECTION -> applySelectionInvariant()
			}
		}
		lifecycleScope.coroutineContext[kotlinx.coroutines.Job]
			?.invokeOnCompletion { repo.removeChangeListener(listener) }
	}

	private fun gateDeveloperEntry() {
		// Hidden until the easter egg fires (5 taps on title within 2s).
		rendered?.viewByKey?.get(NAV_DEVELOPER_KEY)?.visibility = View.GONE
	}

	companion object {
		/** Intent extra naming the registry page to render (defaults to [PAGE_MAIN]). */
		const val EXTRA_PAGE_ID = "page_id"
		const val PAGE_MAIN = "main"
		const val PAGE_LANGUAGE_OPTIONS = "language_options"
		const val PAGE_KEYBOARD_SETUP = "keyboard_setup"

		private const val NAV_DEVELOPER_KEY = "nav_developer"
		private const val TAP_THRESHOLD = 5
		private const val TAP_WINDOW_MS = 2000L
		private const val DISABLED_ALPHA = 0.4f
		const val ACTION_SUBMIT_FEEDBACK = "submit_feedback"
		const val ACTION_GET_MORE_LANGUAGES = "get_more_languages"
	}
}
