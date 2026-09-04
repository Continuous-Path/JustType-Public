package org.continuouspath.justtype

import android.content.Context
import android.util.Log
import org.continuouspath.justtype.settings.SettingsRepository
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/** A picker option paired with its rendered friendly label and gender-preference match. */
data class LabeledVoice(val option: TtsVoiceOption, val label: String, val matchesGenderPref: Boolean)

/**
 * Stable, human-readable display names for TTS voices — "English (United States) Female 1" instead
 * of "en-us-x-tpf-local". Voices are bucketed by (language, region, gender) and each gets a
 * persistent ordinal within its bucket, stored as JSON under [Constants.PREFS_KEY_TTS_VOICE_NAMES]
 * (same storage shape as [LanguageTtsPreferences]), so a voice keeps its number across rescans,
 * enumeration-order changes, and engine reinstalls. Only the ordinal is stored; labels are rendered
 * at read time so they follow the current UI language.
 */
object TtsVoiceNames {
	private const val TAG = "TtsVoiceNames"
	private const val F_LANG = "lang"
	private const val F_COUNTRY = "country"
	private const val F_GENDER = "gender"
	private const val F_ORD = "ord"
	private const val F_ENGINE_LABEL = "engineLabel"

	internal data class Entry(
		val enginePackage: String,
		val voiceName: String,
		val lang: String,
		val country: String,
		val gender: VoiceGender,
		val ord: Int,
		val engineLabel: String,
	)

	// ── Registration ─────────────────────────────────────────────────────

	/**
	 * Pure ordinal assignment: each new voice gets the lowest unused ordinal ≥ 1 in its bucket
	 * (ordinals are claimed across engines so two same-bucket voices never share a number). New
	 * options are processed in (engine, name) order, making a batch registration independent of
	 * enumeration order. A voice whose inferred gender has changed (the curated tables learned it)
	 * is re-bucketed with a fresh ordinal — the one sanctioned renumbering, since "Voice 3" becoming
	 * "Female 1" is the whole point of learning its gender. Returns null when nothing changed.
	 */
	internal fun registerInto(existing: Map<String, Entry>, options: List<TtsVoiceOption>): Map<String, Entry>? {
		var changed = false
		val result = existing.toMutableMap()
		for (o in options.sortedWith(compareBy({ it.enginePackage }, { it.voiceName }))) {
			val key = keyOf(o.enginePackage, o.voiceName)
			val prior = result[key]
			if (prior == null || prior.gender != o.gender) {
				val lang = o.language.lowercase(Locale.ROOT)
				val country = o.country.uppercase(Locale.ROOT)
				val used = result.values
					.filter { it != prior && it.lang == lang && it.country == country && it.gender == o.gender }
					.map { it.ord }
					.toSet()
				val ord = generateSequence(1) { it + 1 }.first { it !in used }
				result[key] = Entry(o.enginePackage, o.voiceName, lang, country, o.gender, ord, o.engineLabel)
				changed = true
			} else if (prior.engineLabel != o.engineLabel) {
				result[key] = prior.copy(engineLabel = o.engineLabel)
				changed = true
			}
		}
		return if (changed) result else null
	}

	// ── Lookup / rendering ───────────────────────────────────────────────

	/** Friendly name of a registered voice, or null if it was never seen by a scan. */
	fun resolve(context: Context, repo: SettingsRepository, enginePackage: String?, voiceName: String): String? {
		val entries = load(repo)
		val entry = if (enginePackage != null) {
			entries[keyOf(enginePackage, voiceName)]
		} else {
			entries.values.firstOrNull { it.voiceName == voiceName }
		}
		return entry?.let { renderLabel(context, it, multiEngineLangs(entries.values)) }
	}

	/**
	 * Summary text for a voice settings row: the selection's friendly name (raw name if unregistered),
	 * or the DEFAULT … label reflecting the voice-type preference when nothing is selected.
	 */
	fun currentSelectionLabel(context: Context, repo: SettingsRepository, prefKey: String): String {
		val pref = LanguageTtsPreferences.voiceFor(repo, prefKey)
		val voice = pref?.voiceName
		if (voice.isNullOrEmpty()) {
			val res = when (repo.getString(UiVoice.genderPrefKeyFor(prefKey), Constants.TTS_GENDER_ANY)) {
				Constants.TTS_GENDER_MALE -> R.string.tts_default_voice_male
				Constants.TTS_GENDER_FEMALE -> R.string.tts_default_voice_female
				Constants.TTS_GENDER_CHILD -> R.string.tts_default_voice_child
				else -> R.string.tts_default_voice
			}
			return context.getString(res)
		}
		return resolve(context, repo, pref.enginePackage, voice) ?: voice
	}

	/**
	 * Registers [options] (assigning ordinals to voices not yet in the map — existing entries are
	 * never renumbered), renders labels, and sorts for a picker: voices matching the voice-type
	 * preference governing [voicePrefKey] first, then locale-aware label order (stable tie-break on
	 * engine + raw name).
	 */
	fun prepareForDisplay(
		context: Context,
		repo: SettingsRepository,
		options: List<TtsVoiceOption>,
		voicePrefKey: String = "",
	): List<LabeledVoice> {
		registerInto(load(repo), options)?.let { save(repo, it) }
		val entries = load(repo)
		val multi = multiEngineLangs(entries.values)
		val wanted = when (repo.getString(UiVoice.genderPrefKeyFor(voicePrefKey), Constants.TTS_GENDER_ANY)) {
			Constants.TTS_GENDER_MALE -> VoiceGender.MALE
			Constants.TTS_GENDER_FEMALE -> VoiceGender.FEMALE
			Constants.TTS_GENDER_CHILD -> VoiceGender.CHILD
			else -> null
		}
		val collator = Collator.getInstance(uiLocale(context))

		// Zero-pad digit runs so lexical comparison orders "Voice 2" before "Voice 10".
		fun sortKey(label: String) = label.replace(Regex("""\d+""")) { it.value.padStart(3, '0') }
		return options
			.map { o ->
				val label = entries[keyOf(o.enginePackage, o.voiceName)]?.let { renderLabel(context, it, multi) } ?: o.voiceName
				LabeledVoice(o, label, wanted == null || o.gender == wanted)
			}
			.sortedWith(
				compareByDescending<LabeledVoice> { it.matchesGenderPref }
					.then(compareBy(collator) { sortKey(it.label) })
					.thenBy { it.option.enginePackage }
					.thenBy { it.option.voiceName },
			)
	}

	private fun renderLabel(context: Context, e: Entry, multiEngineLangs: Set<String>): String {
		val ui = uiLocale(context)
		val langName = Locale(e.lang).getDisplayLanguage(ui).replaceFirstChar { it.uppercase(ui) }
		val base = if (e.country.isNotEmpty()) {
			context.getString(R.string.tts_friendly_lang_region, langName, Locale(e.lang, e.country).getDisplayCountry(ui))
		} else {
			langName
		}
		val genderWord = context.getString(
			when (e.gender) {
				VoiceGender.MALE -> R.string.sr_voice_type_male
				VoiceGender.FEMALE -> R.string.sr_voice_type_female
				VoiceGender.CHILD -> R.string.sr_voice_type_child
				VoiceGender.UNKNOWN -> R.string.tts_voice_generic
			},
		)
		val name = context.getString(R.string.tts_friendly_name_format, base, genderWord, e.ord)
		return if (e.lang in multiEngineLangs) context.getString(R.string.tts_friendly_name_with_engine, name, e.engineLabel) else name
	}

	/** Languages served by more than one engine — only their labels carry the engine suffix. */
	private fun multiEngineLangs(entries: Collection<Entry>): Set<String> = entries.groupBy { it.lang }.filterValues { bucket -> bucket.distinctBy { it.enginePackage }.size > 1 }.keys

	private fun uiLocale(context: Context): Locale {
		val locales = context.resources.configuration.locales
		return if (locales.isEmpty) Locale.getDefault() else locales[0]
	}

	// ── Persistence (shape mirrors LanguageTtsPreferences) ───────────────

	private fun keyOf(enginePackage: String, voiceName: String) = "$enginePackage|$voiceName"

	internal fun load(repo: SettingsRepository): Map<String, Entry> {
		val raw = repo.getString(Constants.PREFS_KEY_TTS_VOICE_NAMES, "")
		if (raw.isEmpty()) return emptyMap()
		val root = runCatching { JSONObject(raw) }.getOrElse {
			Log.w(TAG, "Failed to parse voice-name map JSON; treating as empty.")
			return emptyMap()
		}
		val out = mutableMapOf<String, Entry>()
		for (key in root.keys()) {
			val o = root.optJSONObject(key)
			val sep = key.indexOf('|')
			if (o == null || sep <= 0 || sep == key.length - 1) continue
			out[key] = Entry(
				enginePackage = key.substring(0, sep),
				voiceName = key.substring(sep + 1),
				lang = o.optString(F_LANG, ""),
				country = o.optString(F_COUNTRY, ""),
				gender = runCatching { VoiceGender.valueOf(o.optString(F_GENDER, "")) }.getOrDefault(VoiceGender.UNKNOWN),
				ord = o.optInt(F_ORD, 1),
				engineLabel = o.optString(F_ENGINE_LABEL, ""),
			)
		}
		return out
	}

	private fun save(repo: SettingsRepository, entries: Map<String, Entry>) {
		val root = JSONObject()
		for ((key, e) in entries) {
			root.put(
				key,
				JSONObject()
					.put(F_LANG, e.lang)
					.put(F_COUNTRY, e.country)
					.put(F_GENDER, e.gender.name)
					.put(F_ORD, e.ord)
					.put(F_ENGINE_LABEL, e.engineLabel),
			)
		}
		repo.putString(Constants.PREFS_KEY_TTS_VOICE_NAMES, root.toString())
	}
}
