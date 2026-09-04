package org.continuouspath.justtype.settings

import android.content.Context
import org.continuouspath.justtype.BuildIdentity
import org.continuouspath.justtype.CanonicalLanguages
import org.continuouspath.justtype.EnglishRegion
import org.continuouspath.justtype.LanguageRegistry
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.SpanishRegion
import org.continuouspath.justtype.Constants as C

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  SINGLE SOURCE OF TRUTH for all user-facing JustType settings.       ║
 * ║                                                                      ║
 * ║  When adding or modifying a setting:                                 ║
 * ║   1. Add/update the entry in the appropriate page below.             ║
 * ║   2. The keyboard Settings Mode display updates automatically.       ║
 * ║   3. Default values here are applied via ensureDefaults().           ║
 * ║   4. The standard touchscreen settings UI (SettingsActivity /        ║
 * ║      setup activities) must be updated separately to match.          ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 *
 * Pages mirror the existing SettingsActivity hierarchy:
 *   main → input_methods → head_tracking / joystick / single_switch / two_switch / ...
 *        → vocabulary
 *        → developer
 */
class SettingsRegistry private constructor(
	private val context: Context,
) {
	private fun s(resId: Int): String = context.getString(resId)

	/** Installed (present) typing languages, English first. Rebuilt on [reinitialize]. */
	private fun typingLanguageOptions(): List<Pair<String, String>> {
		val repo = SettingsRepository.getInstance(context)
		LanguageRegistry.ensureDefaults(repo)
		val present = LanguageRegistry.load(repo).filter { it.present }.map { it.name }
		val ordered = listOf(C.TYPING_LANGUAGE_ENGLISH) +
			present.filter { it != C.TYPING_LANGUAGE_ENGLISH }.sorted()
		return ordered.map { it to CanonicalLanguages.endonymFor(it) }
	}

	/** Options for the Optimized-layout source: follow the typing language, or pin one language's. */
	private fun layoutSourceOptions(): List<Pair<String, String>> = listOf(C.LAYOUT_SOURCE_MATCH to s(R.string.sr_opt_layout_match)) +
		typingLanguageOptions().map { (id, endonym) -> id to endonym }

	/** True when any installed language's layout carries tone keys — gates the tone-label row. */
	private fun toneLanguageInstalled(): Boolean {
		val repo = SettingsRepository.getInstance(context)
		LanguageRegistry.ensureDefaults(repo)
		return LanguageRegistry.load(repo).any { it.present && it.layoutJson.contains("\"tones\"") }
	}

	/** True when the Spanish langpack is present — gates the Spanish Region row. */
	private fun spanishInstalled(): Boolean {
		val repo = SettingsRepository.getInstance(context)
		LanguageRegistry.ensureDefaults(repo)
		return LanguageRegistry.load(repo).any { it.present && it.name == C.TYPING_LANGUAGE_ESPANOL }
	}

	/** True when any installed language's Optimized layout carries accent variants as
	 * first-class letters (mixed mapping, e.g. Espanol v5) — gates the accent-fallback row.
	 * Detection: a diacritic-set char appearing inside the layout's lettersPerKey array
	 * (base-folded layouts list only base letters there). */
	private fun mixedMappingLanguageInstalled(): Boolean {
		val repo = SettingsRepository.getInstance(context)
		LanguageRegistry.ensureDefaults(repo)
		return LanguageRegistry.load(repo).any { e ->
			val letters = e.layoutJson.substringAfter("\"lettersPerKey\"", "").substringBefore("]")
			e.present && e.diacriticSet.any { ch -> letters.contains(ch) }
		}
	}

	val pages: Map<String, List<SettingsDef>> = buildPages()
	val pageTitles: Map<String, String> = buildPageTitles()

	private val itemsByKey: Map<String, SettingsDef> = buildMap {
		for ((_, items) in pages) {
			for (item in items) {
				when (item) {
					is SettingsDef.Toggle,
					is SettingsDef.IntSlider,
					is SettingsDef.FloatSlider,
					is SettingsDef.Choice,
					is SettingsDef.KeyCapture,
					-> put(item.key, item)
					is SettingsDef.SectionHeader,
					is SettingsDef.SubPage,
					is SettingsDef.InfoText,
					is SettingsDef.Action,
					-> { /* no persisted value, skip */ }
				}
			}
		}
	}

	/** Returns the registry entry for a persisted-value key, or null if unknown. */
	fun findByKey(key: String): SettingsDef? = itemsByKey[key]

	private fun buildPages(): Map<String, List<SettingsDef>> {
		// Pre-capture format strings for use inside lambdas
		val fmtOff = s(R.string.sr_format_off)
		val fmtMs = s(R.string.sr_format_ms)
		val fmtSec = s(R.string.sr_format_sec)
		val fmtSecFloat = s(R.string.sr_format_sec_float)
		val fmtSecFloat2 = s(R.string.sr_format_sec_float2)
		val fmtPercent = s(R.string.sr_format_percent)
		val fmtDpInt = s(R.string.sr_format_dp_int)
		val fmtDecimal2 = s(R.string.sr_format_decimal2)
		val fmtDecimal1 = s(R.string.sr_format_decimal1)
		val fmtInfinite = s(R.string.sr_format_infinite)

		val voiceTypeOptions = listOf(
			C.TTS_GENDER_ANY to s(R.string.sr_voice_type_any),
			C.TTS_GENDER_MALE to s(R.string.sr_voice_type_male),
			C.TTS_GENDER_FEMALE to s(R.string.sr_voice_type_female),
			C.TTS_GENDER_CHILD to s(R.string.sr_voice_type_child),
		)

		return mapOf(
			// ── Main Settings Page ─────────────────────────────────────────
			"main" to
				buildList {
					addAll(
						listOf(
							// Navigation links
							SettingsDef.SubPage("nav_input", s(R.string.sr_main_nav_input_methods), targetPageId = "input_methods", prominent = true),
							SettingsDef.SubPage("nav_language", s(R.string.sr_main_nav_language_options), targetPageId = "language_options", prominent = true),
							SettingsDef.SubPage("nav_keyboard_setup", s(R.string.sr_main_nav_keyboard_setup), targetPageId = "keyboard_setup", prominent = true),
							SettingsDef.SubPage("nav_navigation_mode", s(R.string.navigation_mode_main_row_label), targetPageId = "navigation_mode", prominent = true),
							SettingsDef.SubPage("nav_vocab", s(R.string.sr_main_nav_vocabulary), targetPageId = "vocabulary", prominent = true),
							SettingsDef.SubPage(
								"nav_backup",
								s(R.string.sr_main_nav_backup),
								description = s(R.string.sr_main_nav_backup_desc),
								targetPageId = "backup_info",
								prominent = true,
							),
							SettingsDef.SubPage("nav_developer", s(R.string.sr_main_nav_developer), targetPageId = "developer"),
							// ── Performance ────────────────────────────────────────────
							// Battery Saver (docs/.local/plans/battery-saver-mode.md): read
							// directly at each gated call site (ngbActive(), word-usage
							// tracking, key-press animations) rather than rewriting the
							// dependent settings' stored values, so the user's own choices
							// survive toggling this off and back on.
							SettingsDef.SectionHeader("sec_performance", s(R.string.sr_main_sec_performance)),
							SettingsDef.Toggle(
								C.KEY_BATTERY_SAVER_MODE,
								s(R.string.sr_main_battery_saver_mode),
								defaultValue = false,
								infoPrompt = s(R.string.info_prompt_battery_saver_mode),
							),
							// ── Speech Output ──────────────────────────────────────────
							SettingsDef.SectionHeader("sec_speech", s(R.string.sr_main_sec_speech)),
							SettingsDef.Toggle(
								C.KEY_SPEAK_OUTPUT_WORD,
								s(R.string.sr_main_speak_output_word),
								defaultValue = false,
								infoPrompt = s(R.string.info_prompt_speak_output_word),
							),
							SettingsDef.Toggle(
								C.KEY_SPEAK_OUTPUT_PHRASE,
								s(R.string.sr_main_speak_output_phrase),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_speak_output_phrase),
							),
							SettingsDef.Toggle(
								C.KEY_SPEAK_OUTPUT_SENTENCE,
								s(R.string.sr_main_speak_output_sentence),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_speak_output_sentence),
							),
							SettingsDef.Toggle(
								C.KEY_SPEAK_PUNCT_NAMES,
								s(R.string.sr_main_speak_punct_names),
								defaultValue = false,
								infoPrompt = s(R.string.info_prompt_speak_punct_names),
							),
							SettingsDef.Choice(
								C.KEY_TTS_SPEAKING_INDICATOR_ICON,
								s(R.string.sr_main_speaking_indicator_icon),
								defaultValue = C.TTS_INDICATOR_ICON_OUTLINED,
								options = listOf(
									C.TTS_INDICATOR_ICON_OUTLINED to s(R.string.sr_opt_indicator_outlined),
									C.TTS_INDICATOR_ICON_WAVE to s(R.string.sr_opt_indicator_wave),
								),
								infoPrompt = s(R.string.info_prompt_speaking_indicator_icon),
							),
							SettingsDef.IntSlider(
								C.KEY_PHRASE_AUTO_OUTPUT_DELAY_MS,
								s(R.string.sr_main_phrase_speak_delay),
								description = s(R.string.sr_main_phrase_speak_delay_desc),
								defaultValue = 1500,
								min = 0,
								max = 5000,
								step = 100,
								formatValue = { if (it == 0) fmtOff else fmtSecFloat.format(it / 1000f) },
								infoPrompt = s(R.string.info_prompt_phrase_auto_output),
							),
							// ── Feedback ───────────────────────────────────────────────
							SettingsDef.SectionHeader("sec_feedback", s(R.string.sr_main_submit_feedback)),
							SettingsDef.Action(
								key = "submit_feedback",
								label = s(R.string.sr_main_submit_feedback),
								description = s(R.string.sr_main_submit_feedback_description),
								actionId = "submit_feedback",
							),
							// ── About ──────────────────────────────────────────────────
							// Foot of the first page: the build a tester is running, and the
							// commit it came from, so a bug report identifies its source tree.
							SettingsDef.SectionHeader("sec_about", s(R.string.sr_main_sec_about)),
							SettingsDef.InfoText(
								key = "build_identity",
								label = BuildIdentity.version,
								// InfoText renders description ?: label as one block, so the
								// description carries the version line too.
								description = BuildIdentity.settingsFooter(),
							),
						),
					)
				},
			// ── Language Options Page ──────────────────────────────────────
			"language_options" to
				buildList {
					// ── Language Options ─────────────────────────────────────
					// Ordered so a voice-type preference precedes its voice picker, encouraging
					// users to set the preference before browsing voices.
					add(SettingsDef.SectionHeader("sec_language", s(R.string.sr_main_section_language_options)))
					add(
						SettingsDef.Choice(
							C.KEY_APP_LANGUAGE,
							s(R.string.sr_main_ui_language),
							defaultValue = C.LANGUAGE_SYSTEM,
							options =
							listOf(
								C.LANGUAGE_SYSTEM to s(R.string.settings_language_system_default),
								C.LANGUAGE_EN to "English",
								C.LANGUAGE_ES to "Español",
								C.LANGUAGE_SW to "Kiswahili",
							),
							infoPrompt = s(R.string.info_prompt_ui_language),
						),
					)
					add(
						SettingsDef.Choice(
							C.KEY_TYPING_LANGUAGE,
							s(R.string.sr_main_typing_language),
							defaultValue = C.TYPING_LANGUAGE_ENGLISH,
							// value = CanonicalLanguage.id; label = endonym. Only languages present on
							// the device; reinitialize() after a langpack install refreshes this list.
							options = typingLanguageOptions(),
							infoPrompt = s(R.string.info_prompt_typing_language),
						),
					)
					// Preferred voice type for the user's own (typing-language) voice — seeds voice
					// auto-discovery and the auto-bound default when no voice is selected.
					// (Gender is best-effort: Android's Voice API has no gender field; see guessVoiceGender.)
					add(
						SettingsDef.Choice(
							C.KEY_TTS_VOICE_GENDER,
							s(R.string.sr_main_voice_type),
							defaultValue = C.TTS_GENDER_ANY,
							options = voiceTypeOptions,
							infoPrompt = s(R.string.info_prompt_voice_type),
						),
					)
					// In-keyboard voice picker (VOICE_PICK mode). The touchscreen SettingsActivity
					// skips this row — it injects its own value-bearing row.
					add(
						SettingsDef.Action(
							key = "voice_for_language",
							label = s(R.string.sr_main_speech_voice),
							description = s(R.string.sr_main_speech_voice_desc),
							actionId = "voice_for_language",
						),
					)
					add(
						SettingsDef.Action(
							key = "get_more_languages",
							label = s(R.string.sr_main_get_more_languages),
							description = s(R.string.sr_main_get_more_languages_description),
							actionId = "get_more_languages",
						),
					)
					// Voice type for UI feedback (see UiVoice) — separate from the personal one:
					// an opposite-gender UI voice keeps system feedback distinct from the user's
					// own spoken communication.
					add(
						SettingsDef.Choice(
							C.KEY_TTS_UI_VOICE_GENDER,
							s(R.string.sr_main_ui_voice_type),
							defaultValue = C.TTS_GENDER_ANY,
							options = voiceTypeOptions,
							infoPrompt = s(R.string.info_prompt_ui_voice_type),
						),
					)
					// The "device voice" (see UiVoice): prompts / key names spoken TO the user, kept
					// distinct from the typing-language voice that speaks the user's own text.
					add(
						SettingsDef.Action(
							key = "voice_for_ui_language",
							label = s(R.string.sr_main_voice_for_ui_language),
							description = s(R.string.sr_main_voice_for_ui_language_desc),
							actionId = "voice_for_ui_language",
						),
					)
					// Only meaningful once the Spanish langpack is installed; reinitialize() after
					// install/remove keeps this in sync.
					add(
						SettingsDef.Choice(
							C.KEY_EXCLUDED_WORDS,
							s(R.string.sr_main_excluded_words),
							defaultValue = C.EXCLUDED_WORDS_OFFENSIVE,
							options =
							listOf(
								C.EXCLUDED_WORDS_NONE to "None",
								C.EXCLUDED_WORDS_OFFENSIVE to "Offensive Words",
								C.EXCLUDED_WORDS_POTENTIALLY_OFFENSIVE to "Potentially Offensive Words",
							),
							infoPrompt = s(R.string.info_prompt_excluded_words),
						),
					)
					add(
						SettingsDef.Choice(
							C.KEY_ENGLISH_REGION,
							s(R.string.sr_main_english_region),
							defaultValue = EnglishRegion.ANY,
							options =
							listOf(
								EnglishRegion.ANY to "General",
								EnglishRegion.US to "American (US)",
								EnglishRegion.UK to "British (UK)",
							),
							infoPrompt = s(R.string.info_prompt_english_region),
						),
					)
					if (spanishInstalled()) {
						add(
							SettingsDef.Choice(
								C.KEY_SPANISH_REGION,
								s(R.string.sr_main_spanish_region),
								defaultValue = SpanishRegion.ANY,
								options =
								listOf(
									// value = SpanishRegion id; label = the region's own name (only affects Spanish).
									SpanishRegion.ANY to "General",
									SpanishRegion.CASTILIAN to "España",
									SpanishRegion.MEXICAN to "México",
									SpanishRegion.LATAM to "Latinoamérica",
								),
								infoPrompt = s(R.string.info_prompt_spanish_region),
							),
						)
					}
					// Family expansion (sls.md "family expansion"): its own delay,
					// deliberately separate from the capitalized-forms delay in
					// Keyboard Setup — a user may want different pauses for each.
					add(
						SettingsDef.Toggle(
							C.KEY_FAMILY_EXPAND_ENABLED,
							s(R.string.sr_main_family_expansion),
							defaultValue = false,
							infoPrompt = s(R.string.info_prompt_family_expand),
						),
					)
					add(
						SettingsDef.IntSlider(
							C.KEY_FAMILY_EXPAND_DELAY_MS,
							s(R.string.sr_main_family_expansion_delay),
							defaultValue = 1500,
							min = 1000,
							max = 3000,
							step = 250,
							formatValue = { fmtSecFloat.format(it / 1000f) },
							infoPrompt = s(R.string.info_prompt_family_expand_delay),
						),
					)
					// Language-specific rows: only meaningful for mixed-mapping layouts (accents on
					// their own keys). Follows the spanishInstalled()/toneLanguageInstalled()
					// pattern — reinitialize() after langpack install/remove keeps it in sync.
					if (mixedMappingLanguageInstalled()) {
						add(
							SettingsDef.Toggle(
								C.KEY_SHOW_ACCENTED_KEYS,
								s(R.string.sr_main_show_accented_keys),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_show_accented_keys),
							),
						)
						add(
							SettingsDef.Toggle(
								C.KEY_REQUIRE_ACCENTED_KEYS,
								s(R.string.sr_main_require_accented_keys),
								defaultValue = false,
								infoPrompt = s(R.string.info_prompt_require_accented_keys),
								enabledWhenKey = C.KEY_SHOW_ACCENTED_KEYS,
							),
						)
					}
					add(
						SettingsDef.Choice(
							C.KEY_SPELL_DIACRITIC_SCOPE,
							s(R.string.sr_spell_diacritic_scope),
							defaultValue = C.DIACRITIC_SCOPE_CURRENT,
							options = listOf(
								C.DIACRITIC_SCOPE_OFF to s(R.string.sr_spell_scope_off),
								C.DIACRITIC_SCOPE_CURRENT to s(R.string.sr_spell_scope_current),
								C.DIACRITIC_SCOPE_LOADED to s(R.string.sr_spell_scope_loaded),
								C.DIACRITIC_SCOPE_ALL to s(R.string.sr_spell_scope_all),
							),
							infoPrompt = s(R.string.sr_spell_diacritic_scope_info),
						),
					)
					// Tone-key label style — only meaningful once a tone-keystroke language
					// (e.g. Tiếng Việt) is installed; reinitialize() keeps this in sync.
					if (toneLanguageInstalled()) {
						add(
							SettingsDef.Choice(
								C.KEY_TONE_ENTRY_POSITION,
								s(R.string.sr_main_tone_entry_position),
								defaultValue = C.TONE_ENTRY_END,
								options = listOf(
									C.TONE_ENTRY_END to s(R.string.sr_opt_tone_entry_end),
									C.TONE_ENTRY_AFTER_VOWEL to s(R.string.sr_opt_tone_entry_after_vowel),
								),
								infoPrompt = s(R.string.info_prompt_tone_entry_position),
							),
						)
						add(
							SettingsDef.Choice(
								C.KEY_TONE_LABEL_STYLE,
								s(R.string.sr_main_tone_label_style),
								defaultValue = C.TONE_LABEL_STYLE_MARK,
								options = listOf(
									C.TONE_LABEL_STYLE_MARK to s(R.string.sr_opt_tone_marks),
									C.TONE_LABEL_STYLE_VNI to s(R.string.sr_opt_tone_vni),
									C.TONE_LABEL_STYLE_TELEX to s(R.string.sr_opt_tone_telex),
								),
								infoPrompt = s(R.string.info_prompt_tone_label_style),
								// TAV shows per-vowel tone forms on the keys; label styles apply
								// only to tone-at-end entry.
								enabledWhenKey = C.KEY_TONE_ENTRY_POSITION,
								enabledWhenValue = C.TONE_ENTRY_END,
							),
						)
					}
				},
			// ── JustType Keyboard Setup Page ───────────────────────────────
			"keyboard_setup" to
				buildList {
					addAll(
						listOf(
							// ── Keyboard Setup ───────────────────────────────────────────────
							SettingsDef.SectionHeader("sec_keyboard", s(R.string.sr_main_sec_keyboard_setup)),
							SettingsDef.Choice(
								C.KEY_LAYOUT_MODE,
								s(R.string.sr_main_letter_arrangement),
								defaultValue = C.MODE_OPT,
								options = listOf(C.MODE_OPT to s(R.string.sr_opt_optimized), C.MODE_ALPHA to s(R.string.sr_opt_alphabetical)),
								infoPrompt = s(R.string.info_prompt_letter_arrangement),
							),
							SettingsDef.Choice(
								C.KEY_OPTIMIZED_LAYOUT_SOURCE,
								s(R.string.sr_main_optimized_layout_source),
								defaultValue = C.LAYOUT_SOURCE_MATCH,
								// value = LAYOUT_SOURCE_MATCH or a CanonicalLanguage.id (present languages only;
								// reinitialize() after a langpack install refreshes this list).
								options = layoutSourceOptions(),
								infoPrompt = s(R.string.info_prompt_optimized_layout_source),
							),
							SettingsDef.Toggle(
								C.KEY_ENTER_EXTRA_BLANK_LINE,
								s(R.string.sr_main_extra_blank_line),
								defaultValue = false,
								infoPrompt = s(R.string.info_prompt_extra_blank_line),
							),
							// ── Appearance ─────────────────────────────────────────────
							SettingsDef.SectionHeader("sec_appearance", s(R.string.sr_main_sec_appearance)),
							SettingsDef.FloatSlider(
								C.KEY_KEYBOARD_SIZE_RATIO,
								s(R.string.sr_main_keyboard_size),
								defaultValue = 0.55f,
								min = 0.50f,
								max = 0.95f,
								step = 0.01f,
								formatValue = { fmtPercent.format((it * 100).toInt()) },
								dangerous = true,
								testable = true,
								infoPrompt = s(R.string.info_prompt_keyboard_size),
							),
							SettingsDef.IntSlider(
								C.KEY_SELECTION_TEXT_SIZE_SP,
								s(R.string.sr_main_selection_text_size),
								// Matches the sw-bucket dimen (14/16/18sp), so fresh installs look unchanged.
								defaultValue = (
									context.resources.getDimension(R.dimen.selection_text_size) /
										context.resources.configuration.fontScale /
										context.resources.displayMetrics.density
									).toInt(),
								min = 12,
								max = 32,
								step = 1,
								formatValue = { it.toString() },
								infoPrompt = s(R.string.info_prompt_selection_text_size),
							),
							SettingsDef.Toggle(
								C.KEY_NEXT_LETTER_HINTS,
								s(R.string.sr_main_next_letter_hints),
								defaultValue = true,
							),
							SettingsDef.Toggle(
								C.KEY_SHOW_BUTTONS_PRESSED,
								s(R.string.sr_main_show_key_history),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_show_key_history),
							),
							SettingsDef.FloatSlider(
								C.KEY_KEY_HISTORY_HEIGHT_PERCENT,
								s(R.string.sr_main_key_history_height),
								defaultValue = 0.75f,
								min = 0.25f,
								max = 1.0f,
								step = 0.05f,
								formatValue = { fmtPercent.format((it * 100).toInt()) },
								infoPrompt = s(R.string.info_prompt_key_history_height),
							),
							SettingsDef.Toggle(
								C.KEY_KEY_HISTORY_SHRINK_TO_FIT,
								s(R.string.sr_main_shrink_key_history),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_key_history_shrink),
							),
							SettingsDef.Toggle(
								C.KEY_KEY_HISTORY_HIGHLIGHT,
								s(R.string.sr_main_highlight_key_history),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_key_history_highlight),
							),
							SettingsDef.Toggle(
								C.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE,
								s(R.string.sr_main_vertical_key_history),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_key_history_vertical),
							),
							SettingsDef.Toggle(
								C.KEY_KEY_HISTORY_MARK_LATEST,
								s(R.string.sr_main_mark_latest_key_history),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_key_history_mark_latest),
							),
							// ── Selection List ─────────────────────────────────────────
							SettingsDef.SectionHeader("sec_selection", s(R.string.sr_main_sec_selection)),
							SettingsDef.Choice(
								C.KEY_WORD_SELECTION_MODE,
								s(R.string.sr_main_word_selection),
								defaultValue = C.WORD_SELECTION_PAGED,
								options = listOf(
									C.WORD_SELECTION_LIST to s(R.string.sr_opt_word_selection_list),
									C.WORD_SELECTION_PAGED to s(R.string.sr_opt_word_selection_paged),
								),
								infoPrompt = s(R.string.info_prompt_word_selection),
							),
							SettingsDef.IntSlider(
								C.KEY_PAGED_LISTED_WORDS,
								s(R.string.sr_main_paged_listed_words),
								defaultValue = 2,
								min = 0,
								max = 3,
								infoPrompt = s(R.string.info_prompt_paged_listed_words),
							),
							SettingsDef.Toggle(
								C.KEY_AUTO_RESTORE_SELECTION,
								s(R.string.sr_main_auto_restore_selection),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_auto_load_word),
							),
							SettingsDef.Toggle(
								C.KEY_CASETYPE_VARIANTS_ENABLED,
								s(R.string.sr_main_show_case_type_variants),
								defaultValue = false,
								infoPrompt = s(R.string.info_prompt_case_type_variants),
							),
							SettingsDef.Toggle(
								C.KEY_CASETYPE_DEFERRED_EXPAND,
								s(R.string.sr_main_deferred_case_expansion),
								defaultValue = false,
								infoPrompt = s(R.string.info_prompt_case_type_deferred),
							),
							SettingsDef.IntSlider(
								C.KEY_CASETYPE_EXPAND_DELAY_MS,
								s(R.string.sr_main_case_expansion_delay),
								defaultValue = 0,
								min = 0,
								max = 4000,
								step = 500,
								formatValue = { if (it == 0) fmtOff else fmtSecFloat.format(it / 1000f) },
								infoPrompt = s(R.string.info_prompt_case_type_delay),
							),
							SettingsDef.Toggle(
								C.KEY_SHOW_ABBREV_IN_SELECTION,
								s(R.string.sr_main_show_abbrev_in_selection),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_show_abbrev_in_selection),
							),
							SettingsDef.Toggle(
								C.KEY_SHOW_PHRASE_IN_SELECTION,
								s(R.string.sr_main_show_phrase_in_selection),
								defaultValue = true,
								infoPrompt = s(R.string.info_prompt_show_phrase_in_selection),
							),
							// Word list style (sls.md word-list-style modes): the
							// deterministic classic-vs-prediction balance. Classic ==
							// predictions off — KEY_NGB_PREDICTIONS is derived from it
							// (SettingsRepository write-through), which is what grays
							// the dependent prediction settings below.
							SettingsDef.Choice(
								C.KEY_WORD_LIST_STYLE,
								s(R.string.sr_main_word_list_style),
								defaultValue = C.WORD_LIST_STYLE_PREDICTIVE,
								options = listOf(
									C.WORD_LIST_STYLE_PREDICTIVE to s(R.string.sr_opt_wls_predictive),
									C.WORD_LIST_STYLE_STEADY1 to s(R.string.sr_opt_wls_steady1),
									C.WORD_LIST_STYLE_STEADY2 to s(R.string.sr_opt_wls_steady2),
									C.WORD_LIST_STYLE_CLASSIC to s(R.string.sr_opt_wls_classic),
								),
								infoPrompt = s(R.string.info_prompt_word_list_style),
							),
							SettingsDef.Toggle(
								C.KEY_NGB_CONFIDENCE_ENABLED,
								s(R.string.sr_main_ngb_confidence),
								defaultValue = false,
								infoPrompt = s(R.string.info_prompt_ngb_confidence),
								enabledWhenKey = C.KEY_NGB_PREDICTIONS,
							),
							SettingsDef.Choice(
								C.KEY_NGB_CONFIDENCE_ACTION,
								s(R.string.sr_main_ngb_confidence_action),
								defaultValue = C.NGB_CONFIDENCE_ACTION_BEEP,
								options = listOf(
									C.NGB_CONFIDENCE_ACTION_BEEP to s(R.string.sr_opt_ngb_conf_beep),
									C.NGB_CONFIDENCE_ACTION_FLASH to s(R.string.sr_opt_ngb_conf_flash),
									C.NGB_CONFIDENCE_ACTION_BOTH to s(R.string.sr_opt_ngb_conf_both),
								),
								infoPrompt = s(R.string.info_prompt_ngb_confidence_action),
								enabledWhenKey = C.KEY_NGB_CONFIDENCE_ENABLED,
							),
							// Mechanism B (sls.md): the user states the signal's target
							// precision; the raw threshold adapts to meet it and now
							// lives on the developer page.
							SettingsDef.IntSlider(
								C.KEY_NGB_CONF_PRECISION,
								s(R.string.sr_main_ngb_conf_precision),
								defaultValue = 85,
								min = 60,
								max = 95,
								step = 5,
								formatValue = { "$it%" },
								infoPrompt = s(R.string.info_prompt_ngb_conf_precision),
							),
						),
					)
				},
			// ── Input Methods Page ─────────────────────────────────────────
			"input_methods" to
				listOf(
					SettingsDef.SectionHeader("sec_primary", s(R.string.sr_input_methods_sec_primary)),
					SettingsDef.Choice(
						C.KEY_INPUT_METHOD_PRIMARY,
						s(R.string.sr_input_methods_primary_method),
						defaultValue = C.INPUT_METHOD_NONE,
						options =
						listOf(
							C.INPUT_METHOD_NONE to s(R.string.sr_opt_none),
							C.INPUT_METHOD_HEAD_TRACKING to s(R.string.sr_opt_head_tracking),
							C.INPUT_METHOD_JOYSTICK to s(R.string.sr_opt_joystick),
							C.INPUT_METHOD_MOUSE_JOYSTICK to s(R.string.sr_opt_mouse_joystick),
							C.INPUT_METHOD_SINGLE_SWITCH to s(R.string.sr_opt_single_switch),
							C.INPUT_METHOD_TWO_SWITCH to s(R.string.sr_opt_two_switch),
						),
						dangerous = true,
						testable = true,
					),
					SettingsDef.Toggle(
						C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED,
						s(R.string.sr_input_methods_direct_selection),
						defaultValue = true,
						dangerous = true,
						testable = true,
					),
					SettingsDef.Toggle(
						C.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED,
						s(R.string.sr_input_methods_directional_selection),
						defaultValue = false,
						dangerous = true,
						testable = true,
					),
					SettingsDef.Toggle(
						C.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED,
						s(R.string.sr_input_methods_touch_screen_switch),
						defaultValue = false,
						dangerous = true,
						testable = true,
					),
					// Sub-pages for method-specific settings
					SettingsDef.SectionHeader("sec_method_setup", s(R.string.sr_input_methods_sec_method_setup)),
					SettingsDef.SubPage(
						"nav_head_tracking",
						s(R.string.sr_input_methods_nav_head_tracking),
						targetPageId = "head_tracking",
					),
					SettingsDef.SubPage(
						"nav_joystick",
						s(R.string.sr_input_methods_nav_joystick),
						targetPageId = "joystick",
					),
					SettingsDef.SubPage(
						"nav_mouse_joystick",
						s(R.string.sr_input_methods_nav_mouse_joystick),
						targetPageId = "mouse_joystick",
					),
					SettingsDef.SubPage(
						"nav_single_switch",
						s(R.string.sr_input_methods_nav_single_switch),
						targetPageId = "single_switch",
					),
					SettingsDef.SubPage(
						"nav_two_switch",
						s(R.string.sr_input_methods_nav_two_switch),
						targetPageId = "two_switch",
					),
					SettingsDef.SubPage(
						"nav_touch_switch",
						s(R.string.sr_input_methods_nav_touch_switch),
						targetPageId = "touch_switch",
					),
					SettingsDef.SubPage(
						"nav_direct_sel",
						s(R.string.sr_input_methods_nav_direct_sel),
						targetPageId = "direct_selection",
					),
					SettingsDef.SubPage(
						"nav_directional_sel",
						s(R.string.sr_input_methods_nav_directional_sel),
						targetPageId = "directional_selection",
					),
					// Shared feedback settings
					SettingsDef.SectionHeader("sec_feedback", s(R.string.sr_input_methods_sec_feedback)),
					SettingsDef.Toggle(
						C.KEY_FLASH_KEY_FEEDBACK,
						s(R.string.sr_input_methods_flash_key),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_BEEP_KEY_FEEDBACK,
						s(R.string.sr_input_methods_beep_key),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_VIBRATION_FEEDBACK,
						s(R.string.sr_input_methods_vibration_feedback),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_SPEAK_SELECTED_WORD,
						s(R.string.sr_input_methods_speak_selected_word),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_SPEAK_SELECTED_KEY,
						s(R.string.sr_input_methods_speak_selected_key),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_SPEAK_PUNCTUATION,
						s(R.string.sr_input_methods_speak_punctuation),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_SPEAK_SETTINGS_PROMPTS,
						s(R.string.sr_input_methods_speak_prompts),
						defaultValue = true,
					),
				),
			// ── Head Tracking Settings ─────────────────────────────────────
			"head_tracking" to
				listOf(
					SettingsDef.SectionHeader("sec_ht_zones", s(R.string.sr_head_tracking_sec_zones)),
					SettingsDef.FloatSlider(
						C.KEY_HEADTRACKING_DEADZONE,
						s(R.string.sr_head_tracking_dead_zone),
						defaultValue = 0.23f,
						min = 0.10f,
						max = 0.50f,
						step = 0.01f,
						formatValue = { fmtPercent.format((it * 100).toInt()) },
						dangerous = true,
						testable = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_HEADTRACKING_ACTIVEZONE,
						s(R.string.sr_head_tracking_active_zone),
						defaultValue = 0.38f,
						min = 0.10f,
						max = 0.75f,
						step = 0.01f,
						formatValue = { fmtPercent.format((it * 100).toInt()) },
						dangerous = true,
						testable = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_HEADTRACKING_EXITZONE,
						s(R.string.sr_head_tracking_exit_zone),
						defaultValue = 0.80f,
						min = 0.10f,
						max = 0.99f,
						step = 0.01f,
						formatValue = { fmtPercent.format((it * 100).toInt()) },
						dangerous = true,
						testable = true,
					),
					SettingsDef.IntSlider(
						C.KEY_HEADTRACKING_KEY_ACT_THRESHOLD,
						s(R.string.sr_head_tracking_activation_threshold),
						defaultValue = 33,
						min = 0,
						max = 100,
						step = 1,
						formatValue = { String.format(fmtPercent, it) },
						dangerous = true,
						testable = true,
					),
					SettingsDef.IntSlider(
						C.KEY_HEADTRACKING_AIM_TOLERANCE,
						s(R.string.sr_head_tracking_aim_tolerance),
						defaultValue = 67,
						min = 0,
						max = 100,
						step = 1,
						formatValue = { String.format(fmtPercent, it) },
						dangerous = true,
						testable = true,
					),
					SettingsDef.IntSlider(
						C.KEY_HEADTRACKING_EXIT_DELAY_MS,
						s(R.string.sr_head_tracking_exit_delay),
						defaultValue = 3000,
						min = 2000,
						max = 5000,
						step = 100,
						formatValue = { fmtSecFloat.format(it / 1000f) },
					),
					SettingsDef.IntSlider(
						C.KEY_HEADTRACKING_RESTART_DELAY_MS,
						s(R.string.sr_head_tracking_restart_delay),
						defaultValue = 2000,
						min = 500,
						max = 3000,
						step = 100,
						formatValue = { fmtSecFloat.format(it / 1000f) },
						infoPrompt = s(R.string.info_prompt_ht_restart_timer),
					),
					SettingsDef.SectionHeader("sec_ht_tuning", s(R.string.sr_head_tracking_sec_tuning)),
					SettingsDef.FloatSlider(
						C.KEY_HEADTRACKING_PITCH_SCALE,
						s(R.string.sr_head_tracking_vertical_sensitivity),
						defaultValue = 1.1f,
						min = 1.0f,
						max = 2.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
						testable = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_HEADTRACKING_RESPONSE_CURVE,
						s(R.string.sr_head_tracking_response_curve),
						defaultValue = 1.0f,
						min = 0.5f,
						max = 1.5f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
						testable = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_HEADTRACKING_CORNER_BIAS,
						s(R.string.sr_head_tracking_corner_bias),
						defaultValue = 1.00f,
						min = 0.5f,
						max = 2.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
						testable = true,
					),
					SettingsDef.Toggle(
						C.KEY_HEADTRACKING_DEBUG_OVERLAY,
						s(R.string.sr_head_tracking_debug_overlay),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_HEADTRACKING_DIAG_LOGS,
						s(R.string.sr_head_tracking_diag_logs),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_HEADTRACKING_REARM_IN_FEEDBACK,
						s(R.string.sr_head_tracking_rearm_in_feedback),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_HEADTRACKING_CORRECTION_BEEP,
						s(R.string.sr_head_tracking_correction_beep),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_HEADTRACKING_CORRECTION_FLASH_RED,
						s(R.string.sr_head_tracking_correction_flash_red),
						defaultValue = true,
					),
				),
			// ── Joystick Settings ──────────────────────────────────────────
			"joystick" to
				listOf(
					SettingsDef.FloatSlider(
						C.KEY_JOYSTICK_DEADZONE,
						s(R.string.sr_joystick_dead_zone),
						defaultValue = 0.25f,
						min = 0.10f,
						max = 0.80f,
						step = 0.01f,
						formatValue = { fmtPercent.format((it * 100).toInt()) },
						dangerous = true,
						testable = true,
					),
					// Phase 3D (Δ-13): Active Zone capped at 0.90 per spec
					// (was 0.99). Provides headroom between Active and the
					// Exit Zone (which can extend up to 1.0).
					SettingsDef.FloatSlider(
						C.KEY_JOYSTICK_ACTIVEZONE,
						s(R.string.sr_joystick_active_zone),
						defaultValue = 0.60f,
						min = 0.10f,
						max = 0.90f,
						step = 0.01f,
						formatValue = { fmtPercent.format((it * 100).toInt()) },
						dangerous = true,
						testable = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_JOYSTICK_CORNER_BIAS,
						s(R.string.sr_joystick_corner_bias),
						defaultValue = 1.35f,
						min = 0.5f,
						max = 2.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
						testable = true,
					),
					// Device *binding* (name/descriptor picker) stays touch-only — it needs
					// plugged-in hardware enumeration. This toggle is the parity-relevant part.
					SettingsDef.Toggle(
						C.KEY_JOYSTICK_ACCEPT_ANY,
						s(R.string.setup_joy_accept_any),
						defaultValue = true,
					),
				),
			// ── Mouse Joystick Settings ────────────────────────────────────
			"mouse_joystick" to
				listOf(
					// Start with one-tap presets: most users find their fit here without touching a slider.
					SettingsDef.SectionHeader("mj_preset_header", s(R.string.sr_mouse_joystick_preset_header)),
					SettingsDef.Action("mj_preset_light", s(R.string.sr_mouse_joystick_preset_light), actionId = "mj_preset_light"),
					SettingsDef.Action("mj_preset_standard", s(R.string.sr_mouse_joystick_preset_standard), actionId = "mj_preset_standard"),
					SettingsDef.Action("mj_preset_firm", s(R.string.sr_mouse_joystick_preset_firm), actionId = "mj_preset_firm"),
					// The two knobs most likely to need per-person adjustment.
					SettingsDef.SectionHeader("mj_fine_tuning_header", s(R.string.sr_mouse_joystick_fine_tuning_header)),
					SettingsDef.IntSlider(
						C.KEY_MOUSE_JOYSTICK_SENSITIVITY_DP,
						s(R.string.sr_mouse_joystick_sensitivity),
						// dp/SECOND for full deflection (time-normalized velocity). Higher = a faster push
						// is needed. Real full-push speeds measured ~1400-2500 dp/sec.
						defaultValue = 800,
						min = 100,
						max = 2500,
						step = 50,
						formatValue = { String.format(fmtDpInt, it) },
						testable = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_MOUSE_JOYSTICK_DEADZONE,
						s(R.string.sr_mouse_joystick_dead_zone),
						defaultValue = 0.20f,
						min = 0.01f,
						max = 0.80f,
						step = 0.01f,
						formatValue = { fmtPercent.format((it * 100).toInt()) },
						dangerous = true,
						testable = true,
					),
					SettingsDef.IntSlider(
						C.KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS,
						s(R.string.sr_mouse_joystick_exit_delay),
						defaultValue = 2000,
						min = 500,
						max = 5000,
						step = 100,
						formatValue = { String.format(fmtMs, it) },
					),
					// Expert geometry / timing knobs — most users never need these.
					SettingsDef.SectionHeader("mj_advanced_header", s(R.string.sr_mouse_joystick_advanced_header)),
					SettingsDef.FloatSlider(
						C.KEY_MOUSE_JOYSTICK_ACTIVEZONE,
						s(R.string.sr_mouse_joystick_active_zone),
						defaultValue = 0.55f,
						min = 0.01f,
						max = 0.90f,
						step = 0.01f,
						formatValue = { fmtPercent.format((it * 100).toInt()) },
						dangerous = true,
						testable = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_MOUSE_JOYSTICK_CORNER_BIAS,
						s(R.string.sr_mouse_joystick_corner_bias),
						defaultValue = 1.35f,
						min = 0.5f,
						max = 2.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
						testable = true,
					),
					SettingsDef.IntSlider(
						C.KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS,
						s(R.string.sr_mouse_joystick_reengage_hysteresis),
						defaultValue = 750,
						min = 0,
						max = 2000,
						step = 50,
						formatValue = { String.format(fmtMs, it) },
					),
				),
			// ── Single Switch Settings ─────────────────────────────────────
			"single_switch" to
				listOf(
					SettingsDef.KeyCapture(
						C.KEY_SCAN_SWITCH_CODE,
						s(R.string.sr_single_switch_assign_switch),
						undefinedValue = C.SWITCH_CODE_UNDEFINED,
					),
					SettingsDef.IntSlider(
						C.KEY_SWITCH_DEBOUNCE_MS,
						s(R.string.sr_single_switch_debounce),
						description = s(R.string.sr_single_switch_debounce_desc),
						defaultValue = 120,
						min = 0,
						max = 1000,
						step = 10,
						formatValue = { String.format(fmtMs, it) },
					),
					SettingsDef.FloatSlider(
						C.KEY_SCAN_STEP_DELAY_SEC,
						s(R.string.sr_single_switch_scan_step_delay),
						defaultValue = 1.0f,
						min = 0.25f,
						max = 3.0f,
						step = 0.05f,
						formatValue = { fmtSecFloat2.format(it) },
						testable = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_INITIAL_SCAN_DELAY_INCREASE_SEC,
						s(R.string.sr_single_switch_initial_delay),
						defaultValue = 0f,
						min = 0f,
						max = 1.5f,
						step = 0.05f,
						formatValue = { if (it == 0f) fmtOff else fmtSecFloat2.format(it) },
					),
					SettingsDef.IntSlider(
						C.KEY_SCAN_REPEAT_COUNT,
						s(R.string.sr_single_switch_scan_repeat_count),
						defaultValue = 3,
						min = 0,
						max = 10,
						step = 1,
						formatValue = { if (it == 0) fmtInfinite else "$it" },
					),
					SettingsDef.Toggle(
						C.KEY_SKIP_KEYS_NO_VALID,
						s(R.string.sr_single_switch_skip_no_valid),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_SHOW_NEXT_KEY,
						s(R.string.sr_single_switch_show_next_key),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_AUTOREPEAT_MODE,
						s(R.string.sr_single_switch_auto_repeat_mode),
						defaultValue = false,
					),
					SettingsDef.FloatSlider(
						C.KEY_AUTOREPEAT_DELAY_SEC,
						s(R.string.sr_single_switch_auto_repeat_delay),
						defaultValue = 1.0f,
						min = 0.25f,
						max = 3.0f,
						step = 0.05f,
						formatValue = { fmtSecFloat2.format(it) },
					),
					SettingsDef.Toggle(
						C.KEY_SELECT_KEY_TRIGGERS_SCAN,
						s(R.string.sr_single_switch_select_triggers_scan),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_BEEP_EACH_SCAN_STEP,
						s(R.string.sr_single_switch_beep_each_step),
						defaultValue = false,
					),
					SettingsDef.Choice(
						C.KEY_SCAN_LAYOUT_SIZE,
						s(R.string.sr_single_switch_scan_layout_size),
						defaultValue = C.SCAN_LAYOUT_SIZE_LARGE,
						options =
						listOf(
							C.SCAN_LAYOUT_SIZE_LARGE to s(R.string.sr_opt_large),
							C.SCAN_LAYOUT_SIZE_SMALL to s(R.string.sr_opt_small),
						),
					),
					SettingsDef.IntSlider(
						C.KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC,
						s(R.string.sr_single_switch_highlight_timeout),
						defaultValue = 0,
						min = 0,
						max = 120,
						step = 5,
						formatValue = { if (it == 0) fmtOff else String.format(fmtSec, it) },
					),
				),
			// ── Two Switch Settings ────────────────────────────────────────
			"two_switch" to
				listOf(
					SettingsDef.KeyCapture(
						C.KEY_RED_SWITCH_CODE,
						s(R.string.sr_two_switch_assign_red),
						undefinedValue = C.SWITCH_CODE_UNDEFINED,
					),
					SettingsDef.KeyCapture(
						C.KEY_GREEN_SWITCH_CODE,
						s(R.string.sr_two_switch_assign_green),
						undefinedValue = C.SWITCH_CODE_UNDEFINED,
					),
					SettingsDef.Toggle(
						C.KEY_TWO_SWITCH_AUTOREPEAT_MODE,
						s(R.string.sr_two_switch_auto_repeat_mode),
						defaultValue = false,
					),
					SettingsDef.FloatSlider(
						C.KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC,
						s(R.string.sr_two_switch_auto_repeat_delay),
						defaultValue = 1.0f,
						min = 0.25f,
						max = 3.0f,
						step = 0.05f,
						formatValue = { fmtSecFloat2.format(it) },
					),
					SettingsDef.Toggle(
						C.KEY_TWO_SWITCH_REPEAT_ACTIVATIONS,
						s(R.string.sr_two_switch_repeat_activations),
						defaultValue = true,
					),
					SettingsDef.FloatSlider(
						C.KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC,
						s(R.string.sr_two_switch_repeat_activation_delay),
						defaultValue = 1.0f,
						min = 0.25f,
						max = 3.0f,
						step = 0.05f,
						formatValue = { fmtSecFloat2.format(it) },
					),
					SettingsDef.Toggle(
						C.KEY_TWO_SWITCH_BEEP_ACTIVATION,
						s(R.string.sr_two_switch_beep_activation),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_TWO_SWITCH_SHOW_BAND,
						s(R.string.sr_two_switch_show_band),
						defaultValue = true,
					),
					// Phase 3C: "Disable Highlight" was removed; red/green key
					// highlighting is now always-on in Two-Switch mode.
					SettingsDef.IntSlider(
						C.KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC,
						s(R.string.sr_two_switch_stuck_timeout),
						defaultValue = 10,
						min = 2,
						max = 30,
						step = 1,
						formatValue = { String.format(fmtSec, it) },
					),
				),
			// ── Touch Screen Switch Settings ───────────────────────────────
			"touch_switch" to
				listOf(
					SettingsDef.SectionHeader("sec_ts_how", s(R.string.sr_touch_switch_sec_how)),
					SettingsDef.InfoText(
						"info_ts_1",
						s(R.string.sr_touch_switch_info_1),
					),
					SettingsDef.InfoText(
						"info_ts_2",
						s(R.string.sr_touch_switch_info_2),
					),
					SettingsDef.SectionHeader("sec_ts_settings", s(R.string.sr_touch_switch_sec_settings)),
					SettingsDef.Choice(
						C.KEY_TOUCH_SCREEN_SWITCH_MODE,
						s(R.string.sr_touch_switch_mode),
						defaultValue = C.TOUCH_SCREEN_SWITCH_MODE_SINGLE,
						options =
						listOf(
							C.TOUCH_SCREEN_SWITCH_MODE_SINGLE to s(R.string.sr_opt_single),
							C.TOUCH_SCREEN_SWITCH_MODE_TWO to s(R.string.sr_opt_two),
						),
					),
					SettingsDef.IntSlider(
						C.KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS,
						s(R.string.sr_touch_switch_debounce),
						description = s(R.string.sr_touch_switch_debounce_desc),
						defaultValue = 120,
						min = 0,
						max = 1000,
						step = 10,
						formatValue = { String.format(fmtMs, it) },
					),
					SettingsDef.IntSlider(
						C.KEY_TOUCH_OVERLAY_TIMEOUT_SEC,
						s(R.string.sr_touch_switch_overlay_timeout),
						defaultValue = 4,
						min = 2,
						max = 10,
						step = 1,
						formatValue = { String.format(fmtSec, it) },
					),
					SettingsDef.Toggle(
						C.KEY_TOUCH_SCREEN_SWITCH_FLASH,
						s(R.string.sr_touch_switch_flash),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_TOUCH_SCREEN_SWITCH_BEEP,
						s(R.string.sr_touch_switch_beep),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER,
						s(R.string.show_region_border),
						defaultValue = false,
					),
					SettingsDef.IntSlider(
						C.KEY_TSS_BUTTON_HEIGHT_PERCENT,
						s(R.string.sr_touch_switch_button_height),
						defaultValue = 10,
						min = 5,
						max = 100,
						step = 5,
						formatValue = { fmtPercent.format(it) },
					),
					SettingsDef.IntSlider(
						C.KEY_TSS_OVERLAY_OPACITY,
						s(R.string.sr_touch_switch_overlay_opacity),
						defaultValue = 50,
						min = 10,
						max = 100,
						step = 5,
						formatValue = { fmtPercent.format(it) },
					),
					SettingsDef.Toggle(
						C.KEY_TSS_OVERLAY_MODE,
						s(R.string.sr_touch_switch_overlay_mode),
						description = s(R.string.sr_touch_switch_overlay_mode_desc),
						defaultValue = false,
					),
				),
			// ── Direct Selection Settings ──────────────────────────────────
			"direct_selection" to
				listOf(
					SettingsDef.SectionHeader("sec_ds_how", s(R.string.sr_direct_selection_sec_how)),
					SettingsDef.InfoText(
						"info_ds_1",
						s(R.string.sr_direct_selection_info_1),
					),
					SettingsDef.InfoText(
						"info_ds_2",
						s(R.string.sr_direct_selection_info_2),
					),
					SettingsDef.SectionHeader("sec_ds_tips", s(R.string.sr_direct_selection_sec_tips)),
					SettingsDef.InfoText(
						"info_ds_tips",
						s(R.string.sr_direct_selection_info_tips),
					),
					SettingsDef.SectionHeader("sec_ds_settings", s(R.string.sr_direct_selection_sec_settings)),
					SettingsDef.IntSlider(
						C.KEY_DIRECT_SELECTION_DEBOUNCE_MS,
						s(R.string.sr_direct_selection_debounce),
						description = s(R.string.sr_direct_selection_debounce_desc),
						defaultValue = 120,
						min = 0,
						max = 1000,
						step = 10,
						formatValue = { String.format(fmtMs, it) },
					),
					SettingsDef.Toggle(
						C.KEY_DIRECT_AUTOREPEAT_MODE,
						s(R.string.sr_direct_selection_auto_repeat),
						description = s(R.string.sr_direct_selection_auto_repeat_desc),
						defaultValue = false,
					),
					SettingsDef.FloatSlider(
						C.KEY_DIRECT_AUTOREPEAT_DELAY_SEC,
						s(R.string.sr_direct_selection_auto_repeat_delay),
						defaultValue = 1.0f,
						min = 0.25f,
						max = 3.0f,
						step = 0.05f,
						formatValue = { fmtSecFloat2.format(it) },
					),
				),
			// ── Directional Selection Settings ─────────────────────────────
			"directional_selection" to
				listOf(
					SettingsDef.SectionHeader("sec_dirsec_how", s(R.string.sr_directional_selection_sec_how)),
					SettingsDef.InfoText(
						"info_dirsec_1",
						s(R.string.sr_directional_selection_info_1),
					),
					SettingsDef.InfoText(
						"info_dirsec_2",
						s(R.string.sr_directional_selection_info_2),
					),
					SettingsDef.SectionHeader("sec_dirsec_settings", s(R.string.sr_directional_selection_sec_settings)),
					SettingsDef.IntSlider(
						C.KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS,
						s(R.string.sr_directional_selection_debounce),
						description = s(R.string.sr_directional_selection_debounce_desc),
						defaultValue = 120,
						min = 0,
						max = 1000,
						step = 10,
						formatValue = { String.format(fmtMs, it) },
					),
					SettingsDef.IntSlider(
						C.KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT,
						s(R.string.sr_directional_selection_swipe_sensitivity),
						description = s(R.string.sr_directional_selection_swipe_sensitivity_desc),
						defaultValue = 5,
						min = 2,
						max = 20,
						step = 1,
						formatValue = { fmtPercent.format(it) },
					),
					SettingsDef.Toggle(
						C.KEY_DIRECTIONAL_SHOW_REGION_BORDER,
						s(R.string.show_region_border),
						defaultValue = false,
					),
				),
			// ── Vocabulary Settings ────────────────────────────────────────
			"vocabulary" to
				listOf(
					SettingsDef.SectionHeader("sec_vocab_sources", s(R.string.sr_vocabulary_sec_sources)),
					SettingsDef.Toggle(
						C.KEY_VOCAB_INCLUDE_JUSTTYPE,
						s(R.string.sr_vocabulary_include_justtype),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_VOCAB_INCLUDE_CUSTOM_WORDS,
						s(R.string.sr_vocabulary_include_custom_words),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_VOCAB_INCLUDE_PHRASES,
						s(R.string.sr_vocabulary_include_phrases),
						defaultValue = true,
					),
					SettingsDef.Toggle(
						C.KEY_VOCAB_PROMOTE_IMPORTED,
						s(R.string.sr_vocabulary_promote_imported),
						defaultValue = true,
					),
					SettingsDef.SectionHeader("sec_vocab_filter", s(R.string.sr_vocabulary_sec_filter)),
					SettingsDef.Toggle(
						C.KEY_VOCAB_FREQ_FILTER_ENABLED,
						s(R.string.sr_vocabulary_enable_freq_filter),
						defaultValue = false,
					),
					// Phase 3D: KEY_VOCAB_MIN_FREQ_NEXT_LETTER orphan removed.
					// User confirmed there is no separate "Limit Next-Letter
					// Hints to Common Words" feature in the current product
					// (Accented-hint frequency filtering applies only to
					// JustType database words via KEY_VOCAB_ACCENT_MIN_FREQ /
					// KEY_VOCAB_ACCENT_MAX_FREQ).
					// Phase 3D (B-VM-FIX-FREQ-RANGES): range narrowed 1..20 → 1..14
					// to match the System UI slider users have been seeing.
					SettingsDef.IntSlider(
						C.KEY_VOCAB_MIN_FREQ_SELECTION,
						s(R.string.sr_vocabulary_min_freq_selection),
						defaultValue = 1,
						min = 1,
						max = 14,
						step = 1,
						formatValue = { "$it" },
					),
					SettingsDef.Toggle(
						C.KEY_VOCAB_SHOW_EXCLUDED_AT_END,
						s(R.string.sr_vocabulary_show_excluded),
						defaultValue = false,
					),
					SettingsDef.SectionHeader("sec_vocab_accent", s(R.string.sr_vocabulary_sec_accent)),
					SettingsDef.Toggle(
						C.KEY_VOCAB_ACCENT_ENABLED,
						s(R.string.sr_vocabulary_enable_accent),
						defaultValue = false,
					),
					// Phase 3D (B-VM-FIX-FREQ-RANGES): ranges aligned to System UI.
					// Min Freq: 1..15, where 15 = "NO MINIMUM" (slider far right).
					// Max Freq: 0..14, where 0 = "NO MAXIMUM" (slider far left).
					SettingsDef.IntSlider(
						C.KEY_VOCAB_ACCENT_MIN_FREQ,
						s(R.string.sr_vocabulary_accent_min_freq),
						defaultValue = 15,
						min = 1,
						max = 15,
						step = 1,
						formatValue = { "$it" },
					),
					SettingsDef.IntSlider(
						C.KEY_VOCAB_ACCENT_MAX_FREQ,
						s(R.string.sr_vocabulary_accent_max_freq),
						defaultValue = 0,
						min = 0,
						max = 14,
						step = 1,
						formatValue = { if (it == 0) fmtOff else "$it" },
					),
					// Phase 3D (Δ-35): range tightened from 0..100 to 0..20 to
					// match System-side slider; semantics unchanged (0 = OFF /
					// no use-count limit, 1..20 = "turn off accent hints once
					// spelled this many times").
					SettingsDef.IntSlider(
						C.KEY_VOCAB_ACCENT_USECOUNT_MAX,
						s(R.string.sr_vocabulary_accent_max_use_count),
						defaultValue = 0,
						min = 0,
						max = 20,
						step = 1,
						formatValue = { if (it == 0) fmtOff else "$it" },
					),
					SettingsDef.IntSlider(
						C.KEY_VOCAB_ACCENT_USECOUNT_MAX_JT,
						s(R.string.sr_vocabulary_accent_max_use_jt),
						defaultValue = 0,
						min = 0,
						max = 20,
						step = 1,
						formatValue = { if (it == 0) fmtOff else "$it" },
					),
					// v4.1 Phase 4 — Spell Mode diacritic & case settings. These gate the manual
					// diacritic-variant pages reached from Two-Key Spell Mode; distinct from the
					// vocab-accent settings above which gate word-prediction display.
					SettingsDef.SectionHeader("sec_spell_diacritics", s(R.string.sr_spell_sec_diacritics)),
					// (The "Diacritic letters in Letter Spell Mode" scope Choice lives on the main page, in the
					// Language section next to Typing Language.)
					SettingsDef.Toggle(
						C.KEY_TURKISH_AZERI_CASE_OVERRIDE,
						s(R.string.sr_spell_turkish_azeri_override),
						defaultValue = false,
						infoPrompt = s(R.string.sr_spell_turkish_azeri_override_info),
					),
				),
			// ── Navigation Mode Page ───────────────────────────────────────
			// Touch side routes to SetupNavigationModeFragment (a custom screen with the
			// service-enable flow) instead of rendering this page; the keyboard Settings
			// Mode renders it directly. Keep these entries mirrored with that fragment
			// (see docs/settings-parity.md).
			"navigation_mode" to
				listOf(
					SettingsDef.InfoText(
						"info_navigation_mode_overview",
						s(R.string.navigation_mode_info_overview),
					),
					SettingsDef.Toggle(
						C.KEY_NAV_LIVE_DRAG,
						s(R.string.sr_nav_live_drag),
						defaultValue = false,
						infoPrompt = s(R.string.info_prompt_nav_live_drag),
					),
					SettingsDef.SectionHeader("sec_nav_appearance", s(R.string.nav_appearance_section)),
					SettingsDef.Choice(
						C.KEY_NAV_THEME,
						s(R.string.nav_appearance_theme),
						defaultValue = C.NAV_THEME_SYSTEM,
						options = listOf(
							C.NAV_THEME_SYSTEM to s(R.string.nav_appearance_theme_system),
							C.NAV_THEME_LIGHT to s(R.string.nav_appearance_theme_light),
							C.NAV_THEME_DARK to s(R.string.nav_appearance_theme_dark),
						),
					),
					SettingsDef.IntSlider(
						C.KEY_NAV_KEY_OPACITY_PERCENT,
						s(R.string.nav_appearance_key_opacity),
						defaultValue = C.NAV_KEY_OPACITY_DEFAULT,
						min = 0,
						max = 100,
						step = 5,
						formatValue = { fmtPercent.format(it) },
					),
					SettingsDef.IntSlider(
						C.KEY_NAV_PANEL_OPACITY_PERCENT,
						s(R.string.nav_appearance_panel_opacity),
						defaultValue = C.NAV_PANEL_OPACITY_DEFAULT,
						min = 0,
						max = 100,
						step = 5,
						formatValue = { fmtPercent.format(it) },
					),
					SettingsDef.IntSlider(
						C.KEY_NAV_SIZE_PERCENT,
						s(R.string.nav_appearance_size),
						defaultValue = C.NAV_SIZE_PERCENT_DEFAULT,
						min = 50,
						max = 150,
						step = 5,
						formatValue = { fmtPercent.format(it) },
					),
					SettingsDef.Choice(
						C.KEY_NAV_TRANSPARENCY_MODE,
						s(R.string.nav_appearance_transparency_mode),
						defaultValue = C.NAV_TRANSPARENCY_GLYPH_OUTLINE,
						options = listOf(
							C.NAV_TRANSPARENCY_GLYPH_OUTLINE to s(R.string.nav_appearance_mode_glyph_outline),
							C.NAV_TRANSPARENCY_GLYPH_ONLY to s(R.string.nav_appearance_mode_glyph_only),
							C.NAV_TRANSPARENCY_UNIFORM to s(R.string.nav_appearance_mode_uniform),
						),
					),
					SettingsDef.Toggle(
						C.KEY_NAV_HIDE_DRAG_HANDLE,
						s(R.string.nav_appearance_hide_handle),
						defaultValue = false,
					),
				),
			// ── Backup Info Page (placeholder) ─────────────────────────────
			"backup_info" to
				listOf(
					SettingsDef.SectionHeader("sec_backup_info", s(R.string.sr_backup_sec_info)),
					SettingsDef.SectionHeader(
						"sec_backup_note",
						s(R.string.sr_backup_sec_note),
					),
				),
			// ── Developer Settings ─────────────────────────────────────────
			"developer" to
				listOf(
					SettingsDef.SectionHeader("sec_dev_sort", s(R.string.sr_developer_sec_sort)),
					SettingsDef.FloatSlider(
						"freq_add_weight",
						s(R.string.sr_developer_freq_add_weight),
						defaultValue = 1.0f,
						min = 0.0f,
						max = 2.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
					),
					SettingsDef.FloatSlider(
						"freq_mult_weight",
						s(R.string.sr_developer_freq_mult_weight),
						defaultValue = 1.25f,
						min = 1.0f,
						max = 2.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
					),
					SettingsDef.FloatSlider(
						"seq_add_weight",
						s(R.string.sr_developer_seq_add_weight),
						defaultValue = 1.0f,
						min = 0.0f,
						max = 50.0f,
						step = 1.0f,
						formatValue = { fmtDecimal1.format(it) },
					),
					SettingsDef.FloatSlider(
						"seq_mult_weight",
						s(R.string.sr_developer_seq_mult_weight),
						defaultValue = 1.0f,
						min = 1.0f,
						max = 8.0f,
						step = 0.1f,
						formatValue = { fmtDecimal2.format(it) },
					),
					SettingsDef.FloatSlider(
						"use_add_weight",
						s(R.string.sr_developer_use_add_weight),
						defaultValue = 3.0f,
						min = 0.0f,
						max = 6.0f,
						step = 0.1f,
						formatValue = { fmtDecimal2.format(it) },
					),
					SettingsDef.FloatSlider(
						"use_mult_weight",
						s(R.string.sr_developer_use_mult_weight),
						defaultValue = 1.15f,
						min = 1.0f,
						max = 2.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
					),
					SettingsDef.FloatSlider(
						"recency_add_weight",
						s(R.string.sr_developer_recency_add_weight),
						defaultValue = 2.0f,
						min = 0.0f,
						max = 4.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
					),
					SettingsDef.FloatSlider(
						"recency_mult_weight",
						s(R.string.sr_developer_recency_mult_weight),
						defaultValue = 1.75f,
						min = 1.0f,
						max = 2.0f,
						step = 0.05f,
						formatValue = { fmtDecimal2.format(it) },
					),
					SettingsDef.IntSlider(
						"sls_partition_policy",
						s(R.string.sr_developer_sls_partition),
						defaultValue = 2,
						min = 0,
						max = 2,
						step = 1,
						formatValue = {
							when (it) {
								1 -> s(R.string.sr_developer_sls_partition_pin)
								2 -> s(R.string.sr_developer_sls_partition_interleave)
								else -> s(R.string.sr_developer_sls_partition_blocks)
							}
						},
						infoPrompt = s(R.string.info_prompt_sls_partition),
					),
					SettingsDef.Toggle(
						"sls_tone_promoted",
						s(R.string.sr_developer_sls_tone_promoted),
						defaultValue = false,
						infoPrompt = s(R.string.info_prompt_sls_tone_promoted),
					),
					SettingsDef.IntSlider(
						"select_behavior_mode",
						s(R.string.sr_developer_select_behavior),
						defaultValue = 0,
						min = 0,
						max = 3,
						step = 1,
						formatValue = {
							when (it) {
								1 -> s(R.string.sr_developer_select_behavior_force_page1)
								2 -> s(R.string.sr_developer_select_behavior_force_head)
								3 -> s(R.string.sr_developer_select_behavior_adaptive)
								else -> s(R.string.sr_developer_select_behavior_observe)
							}
						},
						infoPrompt = s(R.string.info_prompt_select_behavior),
					),
					SettingsDef.Toggle(
						"ngb_conf_theta_adaptive",
						s(R.string.dev_ngb_conf_theta_adaptive),
						defaultValue = true,
						infoPrompt = s(R.string.info_prompt_ngb_conf_theta_adaptive),
					),
					SettingsDef.IntSlider(
						C.KEY_NGB_CONFIDENCE_THRESHOLD,
						s(R.string.sr_main_ngb_confidence_threshold),
						defaultValue = 65,
						min = 20,
						max = 95,
						step = 5,
						formatValue = { "$it%" },
						infoPrompt = s(R.string.info_prompt_ngb_confidence_threshold),
					),
					SettingsDef.SectionHeader("sec_dev_search", s(R.string.sr_developer_sec_search)),
					SettingsDef.IntSlider(
						"search_bsd",
						s(R.string.sr_developer_beam_search_depth),
						defaultValue = 8,
						min = 3,
						max = 10,
						step = 1,
						formatValue = { "$it" },
					),
					SettingsDef.IntSlider(
						"search_sed",
						s(R.string.sr_developer_subseq_edit_distance),
						defaultValue = 7,
						min = 0,
						max = 10,
						step = 1,
						formatValue = { "$it" },
					),
					SettingsDef.IntSlider(
						"search_mqc",
						s(R.string.sr_developer_max_queue_candidates),
						defaultValue = 100,
						min = 5,
						max = 1000,
						step = 5,
						formatValue = { "$it" },
					),
					SettingsDef.IntSlider(
						"search_men",
						s(R.string.sr_developer_max_examined_nodes),
						defaultValue = 5000,
						min = 50,
						max = 10000,
						step = 50,
						formatValue = { "$it" },
					),
					SettingsDef.Toggle(
						"search_ignore_men",
						s(R.string.sr_developer_ignore_max_examined),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_CRASH_REPORT_PROMPT_ENABLED,
						s(R.string.sr_developer_crash_report_prompt),
						defaultValue = true,
						infoPrompt = s(R.string.info_prompt_crash_report_prompt),
					),
					SettingsDef.IntSlider(
						"case_switch_margin",
						s(R.string.sr_developer_case_switch_margin),
						defaultValue = 2,
						min = 1,
						max = 8,
						step = 1,
						formatValue = { "$it" },
					),
					SettingsDef.IntSlider(
						"family_expand_min_kr",
						s(R.string.sr_developer_family_min_kr),
						defaultValue = 5,
						min = 2,
						max = 8,
						step = 1,
						formatValue = { "$it" },
					),
					SettingsDef.IntSlider(
						"family_expand_stem_backoff",
						s(R.string.sr_developer_family_stem_backoff),
						defaultValue = 5,
						min = 0,
						max = 8,
						step = 1,
						formatValue = { "$it" },
					),
					SettingsDef.SectionHeader("sec_dev_behavior", s(R.string.sr_developer_sec_behavior)),
					SettingsDef.Toggle(
						C.KEY_EXACT_CASE_PULL_IN,
						s(R.string.sr_main_exact_case_pull_in),
						defaultValue = true,
					),
					SettingsDef.SectionHeader("sec_dev_debug", s(R.string.sr_developer_sec_debug)),
					SettingsDef.Toggle(
						C.KEY_ENABLE_DEBUG_LOG,
						s(R.string.sr_developer_enable_debug_log),
						defaultValue = false,
					),
					SettingsDef.IntSlider(
						C.KEY_DEBUG_LOG_RETENTION_DAYS,
						s(R.string.sr_developer_debug_log_retention),
						defaultValue = 14,
						min = 1,
						max = 30,
						step = 1,
						formatValue = { if (it == 1) s(R.string.sr_format_day_singular) else String.format(s(R.string.sr_format_days_plural), it) },
					),
					SettingsDef.Toggle(
						"show_sort_metric",
						s(R.string.sr_developer_show_sort_metric),
						defaultValue = false,
					),
					SettingsDef.Toggle(
						C.KEY_SHOW_EDIT_OPS,
						s(R.string.sr_developer_show_edit_ops),
						defaultValue = false,
					),
				),
		)
	}

	/** Look up the page title for display in the overlay header. */
	private fun buildPageTitles(): Map<String, String> = mapOf(
		"main" to s(R.string.sr_page_main),
		"input_methods" to s(R.string.sr_page_input_methods),
		"head_tracking" to s(R.string.sr_page_head_tracking),
		"joystick" to s(R.string.sr_page_joystick),
		"mouse_joystick" to s(R.string.sr_page_mouse_joystick),
		"single_switch" to s(R.string.sr_page_single_switch),
		"two_switch" to s(R.string.sr_page_two_switch),
		"touch_switch" to s(R.string.sr_page_touch_switch),
		"direct_selection" to s(R.string.sr_page_direct_selection),
		"directional_selection" to s(R.string.sr_page_directional_selection),
		"vocabulary" to s(R.string.sr_page_vocabulary),
		"backup_info" to s(R.string.sr_page_backup_info),
		"developer" to s(R.string.sr_page_developer),
	)

	/**
	 * Write default values to the repository for every setting defined in the registry,
	 * if the key is not already present. This is the single source of truth for defaults —
	 * SettingsDefaults.ensureAll() calls this so that default values are never
	 * duplicated across multiple files.
	 */
	fun ensureDefaults(repo: SettingsRepository) {
		var changed = false
		val editor = repo.edit()
		// One-time migration: accent_fallback (fallback ON) became require_accented_keys
		// (require OFF) with inverted sense. Runs before the defaults loop so the migrated
		// value wins over the default.
		val c = org.continuouspath.justtype.Constants
		if (repo.contains(c.KEY_ACCENT_FALLBACK)) {
			if (!repo.contains(c.KEY_REQUIRE_ACCENTED_KEYS)) {
				editor.putBoolean(c.KEY_REQUIRE_ACCENTED_KEYS, !repo.getBoolean(c.KEY_ACCENT_FALLBACK, true))
			}
			editor.remove(c.KEY_ACCENT_FALLBACK)
			changed = true
		}
		for ((_, items) in pages) {
			for (item in items) {
				if (repo.contains(item.key)) continue
				when (item) {
					is SettingsDef.Toggle -> {
						editor.putBoolean(item.key, item.defaultValue)
						changed = true
					}
					is SettingsDef.IntSlider -> {
						editor.putInt(item.key, item.defaultValue)
						changed = true
					}
					is SettingsDef.FloatSlider -> {
						editor.putFloat(item.key, item.defaultValue)
						changed = true
					}
					is SettingsDef.Choice -> {
						editor.putString(item.key, item.defaultValue)
						changed = true
					}
					is SettingsDef.KeyCapture -> {
						editor.putInt(item.key, item.undefinedValue)
						changed = true
					}
					is SettingsDef.SectionHeader,
					is SettingsDef.SubPage,
					is SettingsDef.InfoText,
					is SettingsDef.Action,
					-> { /* no defaults to write */ }
				}
			}
		}
		if (changed) editor.apply()
	}

	companion object {
		@Volatile
		private var instance: SettingsRegistry? = null

		fun getInstance(context: Context): SettingsRegistry = instance ?: synchronized(this) {
			instance ?: SettingsRegistry(context.applicationContext).also { instance = it }
		}

		// For callers that know it's already initialized
		fun get(): SettingsRegistry = instance
			?: throw IllegalStateException("SettingsRegistry not initialized. Call getInstance(context) first.")

		/**
		 * Force-recreate the singleton with a new Context (e.g. after locale change).
		 * All string labels are re-resolved from the new context's resources.
		 * Locale-wraps the application context itself so an Activity reference
		 * can never be retained by the singleton.
		 */
		fun reinitialize(context: Context) {
			synchronized(this) {
				instance = SettingsRegistry(LocaleHelper.wrap(context.applicationContext))
			}
		}

		/**
		 * Nulls the singleton for test isolation.
		 * **Only for use in tests.**
		 */
		@JvmStatic
		internal fun resetInstanceForTesting() {
			synchronized(this) { instance = null }
		}
	}
}
