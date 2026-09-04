package org.continuouspath.justtype

import android.content.Context
import org.continuouspath.justtype.settings.SettingsRepository
import org.json.JSONObject
import java.io.IOException

/**
 * Applies developer-specified settings overrides from `debug-settings.json` at startup.
 *
 * This file only exists in the `debug` source set and is stripped from release builds.
 *
 * ## Usage
 * Place a `debug-settings.json` file at:
 *   `app/src/debug/assets/debug-settings.json`
 *
 * The file IS tracked by git — any preset committed there applies to every developer's
 * debug builds. Useful for shared baseline overrides; revert with care.
 *
 * ## Format
 * ```json
 * {
 *   "input_method_primary": "head_tracking",
 *   "headtracking_deadzone": 0.20,
 *   "show_buttons_pressed": false
 * }
 * ```
 * Keys must match the SharedPreferences key constants in [org.continuouspath.justtype.Constants].
 * Values are applied on each app launch, after ensureDefaults() runs.
 *
 * ## Notes
 * - Boolean values: use JSON booleans (`true`/`false`)
 * - Float values: use JSON numbers with a decimal point (`0.5`)
 * - Int values: use JSON integers (`100`)
 * - String values: use JSON strings (`"head_tracking"`)
 * - Long values: use JSON integers (written as Long via [JSONObject.getLong])
 */
object DebugSettingsPreset {
	fun apply(context: Context) {
		val json = loadJson(context) ?: return
		val repo = SettingsRepository.getInstance(context)
		val editor = repo.edit()
		var changed = false

		for (key in json.keys()) {
			when (val value = json.get(key)) {
				is Boolean -> {
					editor.putBoolean(key, value)
					changed = true
				}
				is Int -> {
					editor.putInt(key, value)
					changed = true
				}
				is Long -> {
					editor.putLong(key, value)
					changed = true
				}
				is Double -> {
					editor.putFloat(key, value.toFloat())
					changed = true
				}
				is String -> {
					editor.putString(key, value)
					changed = true
				}
				else -> android.util.Log.w("DebugSettingsPreset", "Unsupported value type for key '$key': ${value::class.simpleName}")
			}
		}

		if (changed) {
			editor.apply()
			android.util.Log.d("DebugSettingsPreset", "Applied ${json.length()} debug setting(s) from debug-settings.json")
		}
	}

	private fun loadJson(context: Context): JSONObject? = try {
		val text =
			context.assets
				.open("debug-settings.json")
				.bufferedReader()
				.use { it.readText() }
		JSONObject(text)
	} catch (_: IOException) {
		null // File doesn't exist — that's fine
	} catch (e: Exception) {
		android.util.Log.w("DebugSettingsPreset", "Failed to parse debug-settings.json: ${e.message}")
		null
	}
}
