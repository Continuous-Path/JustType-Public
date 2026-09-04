package org.continuouspath.justtype.settings

import android.content.Context
import org.continuouspath.justtype.DebugSettingsPreset
import org.continuouspath.justtype.settings.SettingsDefaults.ensureAll
import org.continuouspath.justtype.settings.SettingsDefaults.ensureNonRegistryDefaults
import org.continuouspath.justtype.Constants as C

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║                       Default settings values.                       ║
 * ║                                                                      ║
 * ║  Call [ensureAll] at app startup, after restoring a backup, and      ║
 * ║  anywhere else defaults need to be guaranteed.                       ║
 * ║                                                                      ║
 * ║  When adding a new setting:                                          ║
 * ║   • If it appears in the keyboard Settings Mode → add it to          ║
 * ║     SettingsRegistry (default is defined there).                     ║
 * ║   • If it is non-registry (switch codes, masks, internal flags) →    ║
 * ║     add it to [ensureNonRegistryDefaults] below.                     ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */
object SettingsDefaults {

	/**
	 * Ensures every setting has a default value in the repository.
	 * Safe to call multiple times — never overwrites existing values.
	 *
	 * Covers:
	 * 1. All settings defined in [SettingsRegistry] (keyboard Settings Mode).
	 * 2. Non-registry settings managed only through the touchscreen UI.
	 * 3. One-time migration of legacy [C.KEY_INPUT_METHOD] → [C.KEY_INPUT_METHOD_PRIMARY].
	 * 4. In debug builds: applies developer overrides from `debug-settings.json` (see [DebugSettingsPreset]).
	 *
	 * @param context Required for debug builds to load the preset asset. Pass null to skip the preset.
	 */
	fun ensureAll(repo: SettingsRepository, context: Context? = null) {
		// Migration must run before ensureDefaults so that the legacy value
		// is preserved before a default overwrites the key.
		migrateInputMethodKey(repo)
		SettingsRegistry.get().ensureDefaults(repo)
		// Derived-key reconcile (word-list-style, sls.md): KEY_NGB_PREDICTIONS
		// always mirrors the style (classic == predictions off) — covers fresh
		// installs, backup restores, and any write that bypassed the
		// SettingsRepository.putString write-through.
		repo.putBoolean(
			C.KEY_NGB_PREDICTIONS,
			repo.getString(C.KEY_WORD_LIST_STYLE, C.WORD_LIST_STYLE_PREDICTIVE) != C.WORD_LIST_STYLE_CLASSIC,
		)
		ensureNonRegistryDefaults(repo)
		ensureTierAwareDebugDefaults(repo)
		context?.let { DebugSettingsPreset.apply(it) }
	}

	/**
	 * Tier-aware first-install defaults for the debug-log settings. Only
	 * writes values that aren't already present (typical SharedPreferences
	 * "ensure default" pattern).
	 *
	 * Tier 0 (release): KEY_ENABLE_DEBUG_LOG stays at registry default
	 *   (false). No DebugLog file ever written anyway.
	 * Tier 1 (beta): seed KEY_ENABLE_DEBUG_LOG = true (beta-tester opted
	 *   into a diagnostic build, so log on by default) and
	 *   KEY_DEBUG_LOG_CATEGORIES = TIER1_DEFAULT_ENABLED (Lifecycle only;
	 *   beta-tester can flip other Tier-1 categories on via Developer
	 *   Settings per developer guidance).
	 * Tier 2 (internal/debug): leave at registry defaults — internal users
	 *   know what they want and can configure freely.
	 */
	private fun ensureTierAwareDebugDefaults(repo: SettingsRepository) {
		if (org.continuouspath.justtype.BuildConfig.DEBUG_TIER != 1) return
		var changed = false
		val editor = repo.edit()
		if (!repo.contains(C.KEY_ENABLE_DEBUG_LOG)) {
			editor.putBoolean(C.KEY_ENABLE_DEBUG_LOG, true)
			changed = true
		}
		if (!repo.contains(C.KEY_DEBUG_LOG_CATEGORIES)) {
			val defaults = org.continuouspath.justtype.logging.DebugCategory
				.toPrefValues(org.continuouspath.justtype.logging.DebugLogger.TIER1_DEFAULT_ENABLED)
			editor.putStringSet(C.KEY_DEBUG_LOG_CATEGORIES, defaults)
			changed = true
		}
		if (changed) editor.apply()
	}

	/**
	 * Defaults for settings that do not appear in the keyboard Settings Mode.
	 * These are only accessible via the touchscreen settings UI.
	 */
	private fun ensureNonRegistryDefaults(repo: SettingsRepository) {
		var changed = false
		val editor = repo.edit()

		fun putInt(key: String, value: Int) {
			if (!repo.contains(key)) {
				editor.putInt(key, value)
				changed = true
			}
		}
		fun putLong(key: String, value: Long) {
			if (!repo.contains(key)) {
				editor.putLong(key, value)
				changed = true
			}
		}
		fun putBoolean(key: String, value: Boolean) {
			if (!repo.contains(key)) {
				editor.putBoolean(key, value)
				changed = true
			}
		}

		putInt(C.KEY_SCAN_SWITCH_CODE, C.SWITCH_CODE_UNDEFINED)
		putInt(C.KEY_RED_SWITCH_CODE, C.SWITCH_CODE_UNDEFINED)
		putInt(C.KEY_GREEN_SWITCH_CODE, C.SWITCH_CODE_UNDEFINED)
		putLong(C.KEY_VOCAB_ACTIVE_MASK, 0L)
		putLong(C.KEY_VOCAB_ACCENT_MODULE_MASK, 0L)
		putInt(C.KEY_EXPORT_USAGE_AT_LEAST_COUNT, 0)
		putBoolean(C.KEY_JUSTTYPE_ENABLED_HEADBOARD, false)

		if (changed) editor.apply()
	}

	/**
	 * One-time migration: if the legacy [C.KEY_INPUT_METHOD] key is present but
	 * [C.KEY_INPUT_METHOD_PRIMARY] is absent, copy the value across.
	 * The legacy key is left in place for backwards compatibility with HeadBoard.
	 */
	private fun migrateInputMethodKey(repo: SettingsRepository) {
		if (!repo.contains(C.KEY_INPUT_METHOD_PRIMARY) && repo.contains(C.KEY_INPUT_METHOD)) {
			val legacyValue = repo.getString(C.KEY_INPUT_METHOD, C.INPUT_METHOD_NONE)
			repo.edit().putString(C.KEY_INPUT_METHOD_PRIMARY, legacyValue).apply()
		}
	}
}

/**
 * Returns the effective primary input method, reading [C.KEY_INPUT_METHOD_PRIMARY]
 * with a fallback to the legacy [C.KEY_INPUT_METHOD] for backwards compatibility.
 */
fun SettingsRepository.effectiveInputMethod(): String {
	val primary = getString(C.KEY_INPUT_METHOD_PRIMARY, "")
	if (primary.isNotEmpty()) return primary
	return getString(C.KEY_INPUT_METHOD, C.INPUT_METHOD_NONE)
}

/**
 * Force a safe, operable input configuration — Direct Selection only — so a user who can
 * no longer drive their chosen method (e.g. a crash loop, or head tracking gone unresponsive)
 * lands somewhere usable. Mirrors the HeadBoard-missing fallback in InputMethodsActivity.
 * Writing the primary key notifies listeners synchronously, so a live session swaps at once.
 */
fun SettingsRepository.forceDirectSelectionFallback() {
	edit()
		.putString(C.KEY_INPUT_METHOD_PRIMARY, C.INPUT_METHOD_NONE)
		.putBoolean(C.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, true)
		.putString(C.KEY_INPUT_METHOD, C.INPUT_METHOD_DIRECT_SELECTION) // legacy mirror (HeadBoard reads this)
		.apply()
}

/**
 * Re-coerce the keyboard size ratio into its registry-defined range only if it is out of
 * bounds, so a corrupted/extreme value can't render an unusable keyboard. Touches nothing else.
 */
fun SettingsRepository.applySafeKeyboardDefaults() {
	val def = SettingsRegistry.get().findByKey(C.KEY_KEYBOARD_SIZE_RATIO) as? SettingsDef.FloatSlider ?: return
	val ratio = getFloat(C.KEY_KEYBOARD_SIZE_RATIO, def.defaultValue)
	if (ratio.isNaN() || ratio < def.min || ratio > def.max) {
		edit().putFloat(C.KEY_KEYBOARD_SIZE_RATIO, def.defaultValue).apply()
	}
}
