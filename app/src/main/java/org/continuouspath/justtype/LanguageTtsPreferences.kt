package org.continuouspath.justtype

import android.util.Log
import org.continuouspath.justtype.settings.SettingsRepository
import org.json.JSONObject

/**
 * A user's chosen TTS voice for one language. A null field means "use the engine/language default":
 * [enginePackage] null → default engine; [voiceName] null → the engine's default voice for the locale.
 */
data class TtsVoicePref(val enginePackage: String?, val voiceName: String?)

/**
 * Per-language TTS voice preferences, persisted as a JSON object `{languageId -> {engine, voice}}`
 * under [Constants.PREFS_KEY_LANGUAGE_TTS_VOICE] (mirrors [LanguageRegistry]'s storage shape).
 *
 * Deliberately kept separate from [LanguageEntry]: that entry's DB-derived fields are rewritten by
 * [LanguageRegistry.setDiacriticSet] / `ensureDefaults` (clobber risk), and a voice choice can exist
 * before a language's word DB is `present` — which is exactly what the download seam needs.
 */
object LanguageTtsPreferences {
	private const val TAG = "LanguageTtsPreferences"
	private const val FIELD_ENGINE = "engine"
	private const val FIELD_VOICE = "voice"

	/** The stored voice for [languageId], or null if the user hasn't chosen one (→ system default). */
	fun voiceFor(repo: SettingsRepository, languageId: String): TtsVoicePref? {
		val obj = load(repo).optJSONObject(languageId) ?: return null
		val engine = obj.optString(FIELD_ENGINE, "").ifEmpty { null }
		val voice = obj.optString(FIELD_VOICE, "").ifEmpty { null }
		if (engine == null && voice == null) return null
		return TtsVoicePref(engine, voice)
	}

	fun setVoice(repo: SettingsRepository, languageId: String, pref: TtsVoicePref) {
		val root = load(repo)
		root.put(
			languageId,
			JSONObject()
				.put(FIELD_ENGINE, pref.enginePackage ?: "")
				.put(FIELD_VOICE, pref.voiceName ?: ""),
		)
		save(repo, root)
	}

	fun clear(repo: SettingsRepository, languageId: String) {
		val root = load(repo)
		if (!root.has(languageId)) return
		root.remove(languageId)
		save(repo, root)
	}

	private fun load(repo: SettingsRepository): JSONObject {
		val raw = repo.getString(Constants.PREFS_KEY_LANGUAGE_TTS_VOICE, "")
		if (raw.isEmpty()) return JSONObject()
		return runCatching { JSONObject(raw) }.getOrElse {
			Log.w(TAG, "Failed to parse TTS voice prefs JSON; treating as empty.")
			JSONObject()
		}
	}

	private fun save(repo: SettingsRepository, root: JSONObject) {
		repo.putString(Constants.PREFS_KEY_LANGUAGE_TTS_VOICE, root.toString())
	}
}
