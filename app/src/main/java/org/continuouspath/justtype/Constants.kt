package org.continuouspath.justtype

/**
 * SharedPreferences key constants used throughout JustType.
 *
 * When adding a new preference key here, also add the corresponding setting
 * definition (with label, type, default value, etc.) to [SettingsRegistry]
 * so it appears in the keyboard-driven Settings Mode.
 *
 * @see org.continuouspath.justtype.settings.SettingsRegistry
 */
object Constants {
	const val PREFS_NAME = "JustTypePrefs"
	const val KEY_OVERLAY_PERMISSION_REQUESTED = "overlay_permission_requested"
	const val KEY_NAV_PERMISSION_REQUESTED = "nav_permission_requested"
	const val KEY_WELCOME_GUIDE_SEEN = "welcome_guide_seen"
	const val KEY_NAVIGATION_MODE_ENABLED = "navigation_mode_enabled"
	const val KEY_NAVIGATION_OVERLAY_REQUESTED = "navigation_overlay_requested"

	// Nav directional selection: full-screen swipe region vs inset (default inset keeps system gestures).
	const val KEY_NAV_TOUCH_FULLSCREEN_SWIPE = "nav_touch_fullscreen_swipe"

	// Nav keyboard appearance.
	const val KEY_NAV_KEY_OPACITY_PERCENT = "nav_key_opacity_percent" // 0..100, key fill opacity
	const val KEY_NAV_PANEL_OPACITY_PERCENT = "nav_panel_opacity_percent" // 0..100, window panel opacity
	const val KEY_NAV_SIZE_PERCENT = "nav_size_percent" // 50..150, grid scale
	const val KEY_NAV_TRANSPARENCY_MODE = "nav_transparency_mode" // see NAV_TRANSPARENCY_* below
	const val KEY_NAV_HIDE_DRAG_HANDLE = "nav_hide_drag_handle" // hide the drag handle once positioned
	const val KEY_NAV_LIVE_DRAG = "nav_live_drag" // drag mode: live-held finger vs cursor-then-commit
	const val KEY_NAV_THEME = "nav_theme" // see NAV_THEME_* below

	// What stays visible as key opacity drops.
	const val NAV_TRANSPARENCY_GLYPH_OUTLINE = "glyph_outline" // glyph + faint border stay (default)
	const val NAV_TRANSPARENCY_GLYPH_ONLY = "glyph_only" // only the glyph stays
	const val NAV_TRANSPARENCY_UNIFORM = "uniform" // glyph, fill, border all fade together

	// Nav keyboard theme: dark or light key faces + glyph colour, or follow the system.
	const val NAV_THEME_SYSTEM = "system"
	const val NAV_THEME_LIGHT = "light"
	const val NAV_THEME_DARK = "dark"
	const val NAV_KEY_OPACITY_DEFAULT = 66
	const val NAV_PANEL_OPACITY_DEFAULT = 33
	const val NAV_SIZE_PERCENT_DEFAULT = 80
	const val KEY_JUSTTYPE_ENABLED_HEADBOARD = "justtype_enabled_headboard"
	const val KEY_SPEAK_SETTINGS_PROMPTS = "speak_settings_prompts"
	const val KEY_SHOW_BUTTONS_PRESSED = "show_buttons_pressed"
	const val KEY_LAYOUT_MODE = "layout_mode"

	// Which language's Optimized layout to use: LAYOUT_SOURCE_MATCH follows the typing language;
	// otherwise a CanonicalLanguage.id whose (memorized) optimized layout is kept for all languages.
	const val KEY_OPTIMIZED_LAYOUT_SOURCE = "optimized_layout_source"
	const val LAYOUT_SOURCE_MATCH = "match"

	// Tone-key label style for tone-keystroke languages (LayoutSpec formatVersion 2).
	// Values match the layout JSON's tones.labels style keys.
	// Vietnamese tone-entry position (EXCLUSIVE models, Cliff 2026-08-05): tone keystroke
	// at syllable END (canonical Telex/VNI) or immediately AFTER the carrier vowel (TAV —
	// collapsed pre-tone candidates, per-vowel tone display). Critical pref: trie rebuilds.
	const val KEY_TONE_ENTRY_POSITION = "tone_entry_position"
	const val KEY_NGB_PREDICTIONS = "ngb_predictions"

	// Word list style (sls.md "Word-list-style modes", Cliff 2026-08-12):
	// the user-chosen, DETERMINISTIC balance between classic fixed-SLS
	// ordering (same keys -> same words, always) and data-driven prediction.
	// Replaces both the Word Predictions toggle (classic == off) and the
	// retired adaptive Mechanism A. KEY_NGB_PREDICTIONS is DERIVED from it
	// (style != classic) at the SettingsRepository write-through, keeping
	// every internal read and enabledWhenKey gate working unchanged.
	const val KEY_WORD_LIST_STYLE = "word_list_style"
	const val WORD_LIST_STYLE_CLASSIC = "classic"
	const val WORD_LIST_STYLE_STEADY2 = "steady2"
	const val WORD_LIST_STYLE_STEADY1 = "steady1"
	const val WORD_LIST_STYLE_PREDICTIVE = "predictive"

	// NGB-D selection-confidence signal (docs/.plans/ngram/plan.md, "NGB-D"):
	// notify the user when the top list item is probably their word.
	const val KEY_NGB_CONFIDENCE_ENABLED = "ngb_confidence_enabled"
	const val KEY_NGB_CONFIDENCE_ACTION = "ngb_confidence_action"

	// Battery Saver: master toggle for low-resource devices (docs/.local/plans/battery-saver-mode.md).
	// Read at each gated call site rather than rewriting dependent keys' values, so a user's own
	// fine-grained choices survive toggling this off and back on.
	const val KEY_BATTERY_SAVER_MODE = "battery_saver_mode"

	// Caps the loaded trie to freqClass <= this value while Battery Saver is on — drops only the
	// rarest tier (freqClass 14). Derived from real per-language freqClass distributions (2026-09-03):
	// keeps 66.5%/31.9%/66.3% of English/Espanol/TiengViet word COUNT, but a much higher share of
	// real-world usage since freqClass 14 is by construction the least-used tier.
	const val BATTERY_SAVER_MAX_FREQ_CLASS = 13

	// Threshold as a percent (20..95): p-hat is calibrated, so 80 really means
	// "four out of five signals are right". Dev-facing since mechanism B —
	// it seeds/overrides the adaptive theta (sls.md "Adaptive select-behavior
	// mechanisms" B).
	const val KEY_NGB_CONFIDENCE_THRESHOLD = "ngb_confidence_threshold"

	// Mechanism B user control: the signal's target precision as a percent
	// (60..95) — "if it interrupts, it should be right this often". The
	// adaptive theta walks to the step-ratio equilibrium inc/(inc+dec) = p*.
	const val KEY_NGB_CONF_PRECISION = "ngb_conf_precision"
	const val NGB_CONFIDENCE_ACTION_BEEP = "beep"
	const val NGB_CONFIDENCE_ACTION_FLASH = "flash"
	const val NGB_CONFIDENCE_ACTION_BOTH = "beep_flash"

	// Could-have-saved bookkeeping (counters only; drives the periodic
	// "N keystrokes could have been saved" prompt while the signal is off).
	// The distracted counter is its full-disclosure partner: signals whose
	// top was NOT the word the user committed.
	const val KEY_NGB_CONF_SAVED_KEYS = "ngb_conf_saved_keys"
	const val KEY_NGB_CONF_DISTRACTED = "ngb_conf_distracted_signals"
	const val KEY_NGB_CONF_PROMPTED_SAVED = "ngb_conf_prompted_saved"
	const val KEY_NGB_CONF_LAST_PROMPT_MS = "ngb_conf_last_prompt_ms"
	const val TONE_ENTRY_END = "end"
	const val TONE_ENTRY_AFTER_VOWEL = "after_vowel"
	const val KEY_TONE_LABEL_STYLE = "tone_label_style"
	const val TONE_LABEL_STYLE_MARK = "mark"
	const val TONE_LABEL_STYLE_VNI = "vni"
	const val TONE_LABEL_STYLE_TELEX = "telex"

	// Word Selection mode: LIST = linear Select stepping; PAGED = hybrid paged pick
	// (first N Selects step the list, the next press pages 6 words onto the letter keys).
	const val KEY_WORD_SELECTION_MODE = "word_selection_mode"
	const val WORD_SELECTION_LIST = "list"
	const val WORD_SELECTION_PAGED = "paged"
	const val KEY_PAGED_LISTED_WORDS = "paged_listed_words"
	const val MODE_ALPHA = "alpha"
	const val MODE_OPT = "opt"
	const val KEY_NEXT_LETTER_HINTS = "next_letter_hints_enabled"
	const val KEY_JOYSTICK_DEADZONE = "joystick_deadzone"
	const val KEY_JOYSTICK_ACTIVEZONE = "joystick_activezone"
	const val KEY_JOYSTICK_CORNER_BIAS = "joystick_corner_bias"
	const val KEY_JOYSTICK_DEVICE_DESCRIPTOR = "joystick_device_descriptor" // empty = none selected
	const val KEY_JOYSTICK_DEVICE_NAME = "joystick_device_name" // friendly name for display
	const val KEY_JOYSTICK_ACCEPT_ANY = "joystick_accept_any" // true = any joystick (default)

	// Set to a wall-clock timestamp (ms) while a setup screen is actively capturing
	// input (joystick device or switch key). Live IME/Nav subsystems suppress input
	// while this is recent, so capture isn't double-handled. Auto-expires (failsafe).
	const val KEY_INPUT_CAPTURE_ACTIVE_AT_MS = "input_capture_active_at_ms"
	const val INPUT_CAPTURE_TIMEOUT_MS = 15000L
	const val KEY_HEADTRACKING_CORNER_BIAS = "headtracking_corner_bias"
	const val KEY_CORNER_BIAS = "corner_bias" // Legacy shared key (migrate to per-input keys)
	const val KEY_HEADTRACKING_DEADZONE = "headtracking_deadzone"
	const val KEY_HEADTRACKING_ACTIVEZONE = "headtracking_activezone"
	const val KEY_HEADTRACKING_EXITZONE = "headtracking_exitzone" // Exit zone threshold (default 1.0)
	const val KEY_HEADTRACKING_KEY_ACT_THRESHOLD = "headtracking_key_act_threshold" // 0-100%, default 10%
	const val KEY_HEADTRACKING_AIM_TOLERANCE = "headtracking_aim_tolerance" // 0-100%, default 30% — extends locked key's natural half-octant by this fraction
	const val KEY_HEADTRACKING_EXIT_DELAY_MS = "headtracking_exit_delay_ms" // 2000-5000ms, default 3000ms
	const val KEY_HEADTRACKING_RESTART_DELAY_MS = "headtracking_restart_delay_ms" // 500-3000ms (250ms step), default 1500ms — post-pause/exit timer during which key activations are disabled
	const val KEY_HEADTRACKING_PITCH_SCALE = "headtracking_pitch_scale" // 1.0-2.0, default 1.1 - vertical sensitivity multiplier
	const val KEY_HEADTRACKING_RESPONSE_CURVE = "headtracking_response_curve" // 0.5-1.5, default 1.0 - power curve exponent (< 1.0 = more sensitive near center)

	// Head-tracking anti-lockout auto-fallback: while active, the keyboard is running a temporary
	// touch fallback (Direct Selection on) because HeadBoard went silent; it auto-restores when
	// frames resume. The prior key remembers the user's real Direct-Selection setting to restore.
	const val KEY_HEADTRACKING_AUTO_FALLBACK_ACTIVE = "headtracking_auto_fallback_active"
	const val KEY_HEADTRACKING_FALLBACK_PRIOR_DIRECT_SEL = "headtracking_fallback_prior_direct_sel"
	const val KEY_HEADTRACKING_DEBUG_OVERLAY = "headtracking_debug_overlay" // Debug: show red outline on current head position key
	const val KEY_HEADTRACKING_DIAG_LOGS = "headtracking_diag_logs" // Debug: emit HT_DIAG per-frame logs (E2E_LATENCY, ZONE, RATE, etc.)
	const val KEY_HEADTRACKING_REARM_IN_FEEDBACK = "headtracking_rearm_in_feedback" // ON: re-arm in Feedback Zone (fast); OFF (default): require return to Resting Zone between activations
	const val KEY_HEADTRACKING_CORRECTION_BEEP = "headtracking_correction_beep" // ON (default): play a short tone when the Correct/Backtrack gesture fires
	const val KEY_HEADTRACKING_CORRECTION_FLASH_RED = "headtracking_correction_flash_red" // ON (default): paint the cancelled key pale-red when a Correct/Backtrack gesture fires
	const val KEY_SPEAK_SELECTED_WORD = "speak_selected_word"
	const val KEY_SPEAK_SELECTED_KEY = "speak_selected_key"
	const val KEY_SPEAK_PUNCTUATION = "speak_punctuation"
	const val KEY_SHOW_EDIT_OPS = "show_edit_operations"
	const val KEY_ENABLE_DEBUG_LOG = "enable_debug_log"

	// Dev: force the pre-34 joystick code path (no setMotionEventSources) on a 34+ device,
	// so pre-34 joystick-on-Nav behavior is testable without an old device.
	const val KEY_DEV_FORCE_PRE34_JOYSTICK = "dev_force_pre34_joystick"

	// Dev: emit SwitchProbe logs for switch/gamepad input routing (IME + Nav). Off by default.
	const val KEY_DEV_SWITCH_INPUT_LOGS = "dev_switch_input_logs"

	// Dev: emit edge-scroll diagnostics from Nav. Defaults to BuildConfig.DEBUG (on in debug, off in release).
	const val KEY_DEV_SCROLL_LOGS = "dev_scroll_logs"
	const val KEY_DEBUG_LOG_CATEGORIES = "debug_log_categories"
	const val KEY_DEBUG_LOG_RETENTION_DAYS = "debug_log_retention_days"
	const val KEY_KEYBOARD_SIZE_RATIO = "keyboard_size_ratio" // 0.50..0.95 fraction of width for grid

	const val KEY_ENTER_EXTRA_BLANK_LINE = "enter_extra_blank_line" // ENTER inserts \n\n instead of \n
	const val KEY_EXACT_CASE_PULL_IN = "exact_case_pull_in" // Require exact case match (except first letter) for pull-in
	const val KEY_KEY_HISTORY_HEIGHT_DP = "key_history_height_dp" // 36..120 dp height for key history (legacy, migrated to percent)
	const val KEY_KEY_HISTORY_HEIGHT_PERCENT = "key_history_height_percent" // 0.25..1.0 fraction of key height
	const val KEY_KEY_HISTORY_SHRINK_TO_FIT = "key_history_shrink_to_fit" // Shrink key history to fit available width
	const val KEY_KEY_HISTORY_HIGHLIGHT = "key_history_highlight_word" // Highlight the selected word's char on each history key
	const val KEY_KEY_HISTORY_VERTICAL_LANDSCAPE = "key_history_vertical_landscape" // Landscape: side column instead of top bar
	const val KEY_KEY_HISTORY_MARK_LATEST = "key_history_mark_latest" // Accent border on the most recently typed history key
	const val KEY_SELECTION_TEXT_SIZE_SP = "selection_text_size_sp" // Selection-list text size; default derived from the sw-bucket dimen
	const val KEY_ERROR_BEEP = "error_beep_enabled"

	/**
	 * Canonical default for [KEY_ERROR_BEEP]. The setting is owned by the "Beep When Keystroke
	 * Has No Effect (Error Beep)" switch on the INPUT METHODS page, which is a hand-built
	 * Activity rather than a SettingsRegistry entry — so the default lives here instead of in
	 * the registry, and reads must use the two-arg getBoolean.
	 */
	const val DEFAULT_ERROR_BEEP = false
	const val KEY_PHRASE_AUTO_OUTPUT_DELAY_MS = "phrase_auto_output_delay_ms"

	@Deprecated("Use KEY_INPUT_METHOD_PRIMARY. Kept for backwards compatibility with HeadBoard. Use SharedPreferences.effectiveInputMethod() to read.")
	const val KEY_INPUT_METHOD = "input_method"
	const val KEY_INPUT_METHOD_PRIMARY = "input_method_primary" // Primary input method (one of head_tracking, joystick, single_switch, two_switch, or empty)
	const val KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED = "input_method_directional_selection_enabled"
	const val KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED = "input_method_direct_selection_enabled"
	const val KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED = "input_method_touch_screen_switch_enabled"
	const val INPUT_METHOD_HEAD_TRACKING = "head_tracking"
	const val INPUT_METHOD_JOYSTICK = "joystick"
	const val INPUT_METHOD_MOUSE_JOYSTICK = "mouse_joystick"
	const val INPUT_METHOD_SINGLE_SWITCH = "single_switch"
	const val INPUT_METHOD_TWO_SWITCH = "two_switch"
	const val INPUT_METHOD_DIRECTIONAL_SELECTION = "directional_selection"
	const val INPUT_METHOD_DIRECT_SELECTION = "direct_selection"
	const val INPUT_METHOD_NONE = "" // No primary method selected
	const val KEY_MOUSE_JOYSTICK_DEADZONE = "mouse_joystick_deadzone"
	const val KEY_MOUSE_JOYSTICK_ACTIVEZONE = "mouse_joystick_activezone"
	const val KEY_MOUSE_JOYSTICK_CORNER_BIAS = "mouse_joystick_corner_bias"
	const val KEY_MOUSE_JOYSTICK_SENSITIVITY_DP = "mouse_joystick_sensitivity_dp"
	const val KEY_MOUSE_JOYSTICK_BARRIER_HEIGHT_DP = "mouse_joystick_barrier_height_dp"
	const val KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS = "mouse_joystick_exit_delay_ms"
	const val KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS = "mouse_joystick_reengage_hysteresis_ms"
	const val KEY_SWITCH_DEBOUNCE_MS = "switch_debounce_ms"
	const val KEY_SCAN_STEP_DELAY_SEC = "scan_step_delay_sec"
	const val KEY_INITIAL_SCAN_DELAY_INCREASE_SEC = "initial_scan_delay_increase_sec"
	const val KEY_SKIP_KEYS_NO_VALID = "skip_keys_no_valid"
	const val KEY_SHOW_NEXT_KEY = "show_next_key"
	const val KEY_AUTOREPEAT_MODE = "auto_repeat_mode"
	const val KEY_AUTOREPEAT_DELAY_SEC = "auto_repeat_delay_sec"
	const val KEY_SELECT_KEY_TRIGGERS_SCAN = "select_key_triggers_scan"
	const val KEY_SCAN_REPEAT_COUNT = "scan_repeat_count"
	const val KEY_SCAN_SWITCH_CODE = "scan_switch_code"
	const val KEY_SCAN_LAYOUT_SIZE = "scan_layout_size"
	const val SCAN_LAYOUT_SIZE_LARGE = "scan_layout_large"
	const val SCAN_LAYOUT_SIZE_SMALL = "scan_layout_small"
	const val KEY_BEEP_EACH_SCAN_STEP = "beep_each_scan_step"
	const val KEY_FLASH_KEY_FEEDBACK = "flash_key_feedback"
	const val KEY_BEEP_KEY_FEEDBACK = "beep_key_feedback"
	const val KEY_VIBRATION_FEEDBACK = "vibration_feedback"
	const val KEY_CASETYPE_VARIANTS_ENABLED = "casetype_variants_enabled"
	const val KEY_CASETYPE_DEFERRED_EXPAND = "casetype_deferred_expand"
	const val KEY_CASETYPE_EXPAND_DELAY_MS = "casetype_expand_delay_ms"

	// Family expansion (sls.md "family expansion"): Select-then-pause on a
	// long word inserts a page group of same-letter-stem words. User-facing
	// (Language Options); the min-kr / stem-backoff dials stay in Dev.
	const val KEY_FAMILY_EXPAND_ENABLED = "family_expand_enabled"
	const val KEY_FAMILY_EXPAND_DELAY_MS = "family_expand_delay_ms"
	const val KEY_AUTO_RESTORE_SELECTION = "auto_restore_selection"
	const val KEY_SHOW_ABBREV_IN_SELECTION = "show_abbrev_in_selection"
	const val KEY_SHOW_PHRASE_IN_SELECTION = "show_phrase_in_selection"
	const val KEY_SPEAK_OUTPUT_WORD = "speak_output_word"
	const val KEY_SPEAK_OUTPUT_PHRASE = "speak_output_phrase"
	const val KEY_SPEAK_OUTPUT_SENTENCE = "speak_output_sentence"
	const val KEY_SPEAK_PUNCT_NAMES = "speak_punct_names"
	const val KEY_RED_SWITCH_CODE = "red_switch_code"
	const val KEY_GREEN_SWITCH_CODE = "green_switch_code"
	const val KEY_TWO_SWITCH_AUTOREPEAT_MODE = "two_switch_autorepeat_mode"
	const val KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC = "two_switch_autorepeat_delay_sec"
	const val KEY_TWO_SWITCH_REPEAT_ACTIVATIONS = "two_switch_repeat_activations"
	const val KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC = "two_switch_repeat_activation_delay_sec"
	const val KEY_TWO_SWITCH_BEEP_ACTIVATION = "two_switch_beep_activation"
	const val SWITCH_CODE_UNDEFINED = -1
	const val KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC = "keyboard_highlight_timeout_sec"
	const val KEY_TWO_SWITCH_SHOW_BAND = "two_switch_show_band"
	const val KEY_TOUCH_OVERLAY_TIMEOUT_SEC = "touch_overlay_timeout_sec"
	const val KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC = "external_switch_stuck_timeout_sec"
	const val KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS = "touch_screen_switch_debounce_ms"
	const val KEY_TOUCH_SCREEN_SWITCH_MODE = "touch_screen_switch_mode"
	const val KEY_TOUCH_SCREEN_SWITCH_FLASH = "touch_screen_switch_flash"
	const val KEY_TOUCH_SCREEN_SWITCH_BEEP = "touch_screen_switch_beep"
	const val TOUCH_SCREEN_SWITCH_MODE_SINGLE = "single"
	const val TOUCH_SCREEN_SWITCH_MODE_TWO = "two"
	const val KEY_TSS_BUTTON_HEIGHT_DP = "tss_button_height_dp" // legacy, migrated to KEY_TSS_BUTTON_HEIGHT_PERCENT
	const val KEY_TSS_OVERLAY_BUTTONS = "tss_overlay_buttons" // legacy, migrated to KEY_TSS_OVERLAY_MODE
	const val KEY_TSS_BUTTON_HEIGHT_PERCENT = "tss_button_height_percent" // 5..100 int (% of display height), default 10
	const val KEY_TSS_OVERLAY_OPACITY = "tss_overlay_opacity" // 10..100 int (% opacity), default 50
	const val KEY_TSS_OVERLAY_MODE = "tss_overlay_mode" // boolean: full-screen overlay mode, default false
	const val KEY_DIRECT_SELECTION_DEBOUNCE_MS = "direct_selection_debounce_ms"
	const val KEY_DIRECT_AUTOREPEAT_MODE = "direct_autorepeat_mode"
	const val KEY_DIRECT_AUTOREPEAT_DELAY_SEC = "direct_autorepeat_delay_sec"
	const val KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS = "directional_selection_debounce_ms"
	const val KEY_DIRECTIONAL_SELECTION_SWIPE_DISTANCE_DP = "directional_selection_swipe_distance_dp"
	const val KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT = "directional_selection_swipe_percent"

	// Outline the touch-capture region so the user sees where swipes/taps are read. Per-mode.
	const val KEY_DIRECTIONAL_SHOW_REGION_BORDER = "directional_show_region_border"
	const val KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER = "touch_screen_switch_show_region_border"
	const val KEY_VOCAB_FREQ_FILTER_ENABLED = "vocab_freq_filter_enabled"

	// Phase 3D: KEY_VOCAB_MIN_FREQ_NEXT_LETTER deleted as orphan. There is
	// no separate "Limit Next-Letter Hints to Common Words" feature in the
	// current product; accent-hint frequency filtering is JustType-DB-only
	// via KEY_VOCAB_ACCENT_MIN_FREQ / KEY_VOCAB_ACCENT_MAX_FREQ. Any
	// previously-stored pref value at "vocab_min_freq_next_letter" is now
	// dead and will be ignored.
	const val KEY_VOCAB_MIN_FREQ_SELECTION = "vocab_min_freq_selection"
	const val KEY_VOCAB_SHOW_EXCLUDED_AT_END = "vocab_show_excluded_at_end"
	const val KEY_VOCAB_INCLUDE_JUSTTYPE = "vocab_include_justtype"
	const val KEY_VOCAB_INCLUDE_CUSTOM_WORDS = "vocab_include_custom_words"
	const val KEY_VOCAB_INCLUDE_PHRASES = "vocab_include_phrases"
	const val KEY_VOCAB_ACTIVE_MASK = "vocab_active_mask"

	/**
	 * Which coarse-language levels are kept out of the predictions. Words are graded at DB build
	 * time from {Lang}WordsAvoid.txt: level 1 (offensive) and level 2 (potentially offensive)
	 * carry ClassMasks bits; level 0 carries none. Slurs are never in the DB at all, so no value
	 * of this setting reveals them.
	 */
	const val KEY_EXCLUDED_WORDS = "excluded_words"

	/** Nothing filtered — every word in the DB is offered. */
	const val EXCLUDED_WORDS_NONE = "none"

	/** Default: level 1 only. */
	const val EXCLUDED_WORDS_OFFENSIVE = "offensive"

	/** Strictest: levels 1 and 2. */
	const val EXCLUDED_WORDS_POTENTIALLY_OFFENSIVE = "potentially_offensive"
	const val KEY_VOCAB_PROMOTE_IMPORTED = "vocab_promote_imported"
	const val KEY_VOCAB_ACCENT_ENABLED = "vocab_accent_enabled"
	const val KEY_VOCAB_ACCENT_USECOUNT_MAX = "vocab_accent_usecount_max"
	const val KEY_VOCAB_ACCENT_USECOUNT_MAX_JT = "vocab_accent_usecount_max_jt"
	const val KEY_VOCAB_ACCENT_MIN_FREQ = "vocab_accent_min_freq"
	const val KEY_VOCAB_ACCENT_MAX_FREQ = "vocab_accent_max_freq"
	const val KEY_VOCAB_ACCENT_MODULE_MASK = "vocab_accent_module_mask"

	// v4.1 Letter Spell Mode diacritic scope + case override. Distinct from KEY_VOCAB_ACCENT_* above:
	// those gate word-prediction vocab; these gate manual diacritic entry in LETTER SPELL MODE. The scope
	// selects which languages' diacritics are offered (each language's set is derived from its corpus; see
	// LanguageRegistry). Replaces the retired tier/Vietnamese settings.
	const val KEY_SPELL_DIACRITIC_SCOPE = "spell_diacritic_scope" // "off" | "current" | "loaded" | "all"
	const val DIACRITIC_SCOPE_OFF = "off"
	const val DIACRITIC_SCOPE_CURRENT = "current"
	const val DIACRITIC_SCOPE_LOADED = "loaded"
	const val DIACRITIC_SCOPE_ALL = "all"
	const val KEY_TURKISH_AZERI_CASE_OVERRIDE = "turkish_azeri_case_override"
	const val PREFS_KEY_LANGUAGE_REGISTRY = "language_registry_json"

	// Language packs (downloadable per-language word DBs)
	const val PREFS_KEY_LANGPACK_PENDING = "langpack_pending_json"
	const val PREFS_KEY_LANGPACK_MANIFEST_CACHE = "langpack_manifest_cache_json"
	const val KEY_DEV_LANGPACK_MANIFEST_URL = "dev_langpack_manifest_url" // developer override; empty = BuildConfig default

	const val KEY_EXPORT_USAGE_AT_LEAST_COUNT = "export_usage_at_least_count"
	const val ACTION_HEAD_TRACKING_ENABLED = "org.continuouspath.justtype.ACTION_HEAD_TRACKING_ENABLED"
	const val ACTION_HEAD_TRACKING_DISABLED = "org.continuouspath.justtype.ACTION_HEAD_TRACKING_DISABLED"
	const val ACTION_HEAD_TRACKING_POP_OUT = "org.continuouspath.justtype.ACTION_HEAD_TRACKING_POP_OUT"

	// Pause Mode frame-rate coordination: PAUSE tells HeadBoard to throttle pose frames
	// to ~1/sec (extra EXTRA_FRAME_INTERVAL_MS overrides); UNPAUSE restores the normal rate.
	const val ACTION_HEAD_TRACKING_PAUSE = "org.continuouspath.justtype.ACTION_HEAD_TRACKING_PAUSE"
	const val ACTION_HEAD_TRACKING_UNPAUSE = "org.continuouspath.justtype.ACTION_HEAD_TRACKING_UNPAUSE"
	const val EXTRA_FRAME_INTERVAL_MS = "frame_interval_ms"
	const val ACTION_HEAD_TRACKING_RESUME = "org.continuouspath.justtype.ACTION_HEAD_TRACKING_RESUME"

	// Navigation-overlay head tracking. Unlike the IME actions above, these tell
	// HeadBoard to stream pose frames without requiring JustType's IME keyboard to
	// be open (the nav overlay is an AccessibilityService window, not an IME).
	const val ACTION_NAV_HEAD_TRACKING_ENABLED = "org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_ENABLED"
	const val ACTION_NAV_HEAD_TRACKING_DISABLED = "org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_DISABLED"

	// Nav kbd minimized + armed: HeadBoard runs normally (cursor visible) but watches
	// for a downward push to broadcast ACTION_HEAD_TRACKING_RESUME, re-opening the nav kbd.
	const val ACTION_NAV_HEAD_TRACKING_ARMED = "org.continuouspath.justtype.ACTION_NAV_HEAD_TRACKING_ARMED"
	const val EXTRA_PACKAGE_NAME = "package_name"
	const val ACTION_VOCAB_UPDATED = "org.continuouspath.justtype.ACTION_VOCAB_UPDATED"
	const val EXTRA_VOCAB_MERGE_SOURCE_MASK = "extra_vocab_merge_source_mask"
	const val EXTRA_VOCAB_MERGE_TARGET_MASK = "extra_vocab_merge_target_mask"
	const val EXTRA_VOCAB_DELETE_MASK = "extra_vocab_delete_mask"
	const val ACTION_DATA_RESTORED = "org.continuouspath.justtype.ACTION_DATA_RESTORED"
	const val KEY_LAST_RUN_VERSION = "last_run_version_code"
	const val EXTRA_RESULT_RECEIVER = "org.continuouspath.justtype.extra.RESULT_RECEIVER"
	const val EXTRA_INITIAL_PHRASE = "org.continuouspath.justtype.extra.INITIAL_PHRASE"
	const val EXTRA_INITIAL_ABBREV = "org.continuouspath.justtype.extra.INITIAL_ABBREV"
	const val EXTRA_PHRASE = "org.continuouspath.justtype.extra.PHRASE"
	const val EXTRA_ABBREV = "org.continuouspath.justtype.extra.ABBREV"
	const val ACTION_IME_DONE = "org.continuouspath.justtype.action.PHRASE_OVERLAY_DONE"
	const val ACTION_IME_CANCEL = "org.continuouspath.justtype.action.PHRASE_OVERLAY_CANCEL"
	const val PREFS_KEY_CLASS_METADATA = "class_metadata_json"
	const val KEY_NEEDS_FULL_REINIT = "needs_full_reinit"
	const val KEY_LAST_UPDATE_TIME = "last_package_update_time"
	const val ACTION_EXTERNAL_JOYSTICK_INPUT = "org.continuouspath.justtype.EXTERNAL_JOYSTICK_INPUT"
	const val EXTRA_X = "x"
	const val EXTRA_Y = "y"
	const val EXTRA_DIRECTION = "direction"
	const val EXTRA_BUTTON_INDEX = "button_index"

	// HeadBoard-driven switches: HeadBoard binds one of its triggers (face gesture or
	// captured key) to "JustType Switch 1/2" and broadcasts press/release edges here.
	// HeadBoardSwitchBus synthesizes KeyEvents with the reserved codes below so the
	// events ride the normal switch pipeline (capturable in setup, InputCaptureGate-aware).
	const val ACTION_EXTERNAL_SWITCH = "org.continuouspath.justtype.ACTION_EXTERNAL_SWITCH"

	// Sender permission required by every HeadBoard-facing receiver (see AndroidManifest).
	const val PERMISSION_RECEIVE_HEADBOARD_EVENT = "org.continuouspath.justtype.permission.RECEIVE_HEADBOARD_EVENT"
	const val EXTRA_SWITCH_INDEX = "switchIndex"
	const val EXTRA_SWITCH_IS_DOWN = "isDown"

	// Reserved synthetic keycodes, far above any real KeyEvent code.
	const val HEADBOARD_SWITCH_1_KEYCODE = 7001
	const val HEADBOARD_SWITCH_2_KEYCODE = 7002
	const val KEY_BACKUP_TREE_URI = "backup_tree_uri"
	const val KEY_BACKUP_LAST_TS = "backup_last_ts"
	const val KEY_BACKUP_PROMPTED = "backup_prompted"
	const val KEY_HAS_RUN_BEFORE = "has_run_before"
	const val KEY_LAST_SESSION_CRASHED = "last_session_crashed"
	const val KEY_LAST_CRASH_TIME = "last_crash_time"
	const val KEY_LAST_CRASH_MESSAGE = "last_crash_message"
	const val KEY_LAST_CRASH_THREAD = "last_crash_thread"
	const val KEY_CRASH_RECOVERY_COUNT = "crash_recovery_count" // crashes seen in the current burst window
	const val KEY_CRASH_RECOVERY_WINDOW_START = "crash_recovery_window_start" // ms timestamp of the burst's first crash

	// Survives the IME's crash-recovery clear; SettingsActivity offers to email a report, then clears it.
	const val KEY_CRASH_REPORT_PENDING = "crash_report_pending"

	// Dev toggle: show the post-crash "send a report?" prompt in Settings (default on).
	const val KEY_CRASH_REPORT_PROMPT_ENABLED = "crash_report_prompt_enabled"

	// Language settings
	const val KEY_APP_LANGUAGE = "app_language" // UI language (locale for strings)
	const val LANGUAGE_SYSTEM = "system" // Follow device system language
	const val LANGUAGE_EN = "en"
	const val LANGUAGE_ES = "es"
	const val LANGUAGE_SW = "sw"

	const val KEY_TYPING_LANGUAGE = "typing_language" // Word database language (CanonicalLanguage.id)
	const val TYPING_LANGUAGE_ENGLISH = "English"
	const val TYPING_LANGUAGE_ESPANOL = "Espanol" // ASCII id; endonym "Español" (see CanonicalLanguages)

	// Regional preference for Spanish word ranking; values are SpanishRegion.{ANY,CASTILIAN,MEXICAN,LATAM}.
	const val KEY_SPANISH_REGION = "spanish_region"

	// British/American preference for English word ranking; values are
	// EnglishRegion.{ANY,UK,US}. Both spellings stay in the DB either way — this only ranks.
	const val KEY_ENGLISH_REGION = "english_region"

	// Mixed-mapping layouts (Espanol v5+). "Show accented characters on keys" (default ON)
	// renders the accent letters on their own keys; OFF derives a base-only view — accents
	// stripped from the key faces and folded onto their base letters' keys (same base-letter
	// positions, +2.4% E for Spanish). "Require use of accented character keys" (default OFF)
	// disables the hybrid fallback: a word typed without its accents is then NOT offered.
	// Require is only meaningful (and only enabled) while Show is ON. Rows only appear when an
	// installed language's layout carries first-class accent letters.
	const val KEY_SHOW_ACCENTED_KEYS = "show_accented_keys"
	const val KEY_REQUIRE_ACCENTED_KEYS = "require_accented_keys"

	// Legacy key (replaced by KEY_REQUIRE_ACCENTED_KEYS with inverted sense); migrated and
	// removed by SettingsRegistry.ensureDefaults.
	const val KEY_ACCENT_FALLBACK = "accent_fallback"

	// Per-language TTS voice selection. PREFS_KEY_LANGUAGE_TTS_VOICE stores a JSON map
	// {languageId -> {engine, voice}} (see LanguageTtsPreferences) so the spoken voice follows the
	// typing language. KEY_TTS_VOICE_GENDER is a global voice-type preference that seeds auto-discovery.
	// Gender is best-effort: Android's Voice API has no gender field, so it is inferred heuristically
	// (see guessVoiceGender) and confirmed by the user's Test/Confirm in the picker.
	const val PREFS_KEY_LANGUAGE_TTS_VOICE = "language_tts_voice_json"

	// Stable friendly-name map for discovered voices: {"engine|voiceName" -> {lang, country, gender,
	// ord, engineLabel}} (see TtsVoiceNames). Ordinals persist so "English (US) Female 2" never
	// renumbers across rescans.
	const val PREFS_KEY_TTS_VOICE_NAMES = "tts_voice_names_json"
	const val KEY_TTS_VOICE_GENDER = "tts_voice_gender"

	// Separate voice-type preference for the UI ("device") voice — users may deliberately pick the
	// opposite of their personal voice type so system feedback is easy to tell apart.
	const val KEY_TTS_UI_VOICE_GENDER = "tts_ui_voice_gender"
	const val TTS_GENDER_ANY = "any"
	const val TTS_GENDER_MALE = "male"
	const val TTS_GENDER_FEMALE = "female"
	const val TTS_GENDER_CHILD = "child"

	// Animated icon shown while TTS speech is in progress (see SpeechIndicatorOverlay).
	const val KEY_TTS_SPEAKING_INDICATOR_ICON = "tts_speaking_indicator_icon"
	const val TTS_INDICATOR_ICON_OUTLINED = "outlined"
	const val TTS_INDICATOR_ICON_WAVE = "wave"
}
