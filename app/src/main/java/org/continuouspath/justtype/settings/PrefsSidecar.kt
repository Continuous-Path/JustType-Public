package org.continuouspath.justtype.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.continuouspath.justtype.utils.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * JSON mirror of the SettingsRepository cache, written debounced after each
 * persisted change. Used by the DataStore corruption handler to recover user
 * data instead of falling back to registry defaults.
 *
 * Lives in app filesDir (survives process death; not pm-clear/uninstall).
 */
object PrefsSidecar {
	private const val FILENAME = "prefs_sidecar.json"

	fun file(context: Context): File = File(context.filesDir, FILENAME)

	fun write(context: Context, prefs: Preferences) = writeTo(file(context), prefs)

	fun read(context: Context): Preferences? = readFrom(file(context))

	/** Serialize [prefs] to [target] as typed JSON. Shared by the sidecar and UpdateSnapshot. */
	fun writeTo(target: File, prefs: Preferences) {
		val obj = JSONObject()
		for ((key, value) in prefs.asMap()) {
			val entry = JSONObject()
			when (value) {
				is Boolean -> {
					entry.put("type", "boolean")
					entry.put("value", value)
				}
				is Int -> {
					entry.put("type", "int")
					entry.put("value", value)
				}
				is Long -> {
					entry.put("type", "long")
					entry.put("value", value)
				}
				is Float -> {
					entry.put("type", "float")
					entry.put("value", value)
				}
				is String -> {
					entry.put("type", "string")
					entry.put("value", value)
				}
				// String sets are not currently used persistently; skip.
				else -> continue
			}
			obj.put(key.name, entry)
		}
		runCatching {
			AtomicFile.writeBytes(target) { out -> out.write(obj.toString().toByteArray()) }
		}.onFailure { e ->
			android.util.Log.w("SettingsInit", "PrefsSidecar.write failed", e)
		}
	}

	/** Deserialize typed JSON written by [writeTo]; null if missing/empty/corrupt. */
	fun readFrom(f: File): Preferences? {
		if (!f.exists() || f.length() == 0L) return null
		return runCatching {
			val obj = JSONObject(f.readText())
			val prefs = mutablePreferencesOf()
			val keys = obj.keys()
			while (keys.hasNext()) {
				val k = keys.next()
				val entry = obj.optJSONObject(k) ?: continue
				when (entry.optString("type")) {
					"boolean" -> prefs[booleanPreferencesKey(k)] = entry.optBoolean("value")
					"int" -> prefs[intPreferencesKey(k)] = entry.optInt("value")
					"long" -> prefs[longPreferencesKey(k)] = entry.optLong("value")
					"float" -> prefs[floatPreferencesKey(k)] = entry.optDouble("value").toFloat()
					"string" -> prefs[stringPreferencesKey(k)] = entry.optString("value")
				}
			}
			prefs
		}.onFailure { e ->
			android.util.Log.w("SettingsInit", "PrefsSidecar.read failed; treating as missing", e)
		}.getOrNull()
	}

	/** For tests: wipe the sidecar file. */
	internal fun deleteForTesting(context: Context) {
		file(context).delete()
	}
}
