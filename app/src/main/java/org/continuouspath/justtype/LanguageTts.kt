package org.continuouspath.justtype

import java.util.Locale

/**
 * Pure helpers for mapping a typing language to a spoken [Locale] and for the best-effort
 * inference of a TTS voice's gender/type. Kept free of Android dependencies (only [Locale],
 * which is `java.util`) so they unit-test without Robolectric.
 */

/** Best-guess voice type. [UNKNOWN] = the name gave no usable signal (the common case). */
enum class VoiceGender { MALE, FEMALE, CHILD, UNKNOWN }

/**
 * The [Locale] to speak a typing language in. v1 uses the bare ISO code (e.g. "es"); regional
 * variants ("es-ES" / "es-419") are a follow-up isolated to this function. Unknown ids fall back
 * to the device locale so speech never silently targets the wrong language.
 */
fun localeForTypingLanguage(id: String): Locale = CanonicalLanguages.byId(id)?.localeCode?.let { Locale(it) } ?: Locale.getDefault()

private val CHILD_MARKERS = listOf("child", "kid", "junior")
private val FEMALE_WORD_MARKERS = listOf("female", "woman", "girl")
private val MALE_WORD_MARKERS = listOf("male", "boy") // checked AFTER female ("female" contains "male")
private val ESPEAK_VARIANT = Regex("""\+([mf])\d""") // eSpeak variant convention, e.g. "en+f3", "gmw/en+m1"

/**
 * Best-effort guess of a voice's gender/type from its name. Android's [android.speech.tts.Voice]
 * exposes no gender field, so this is a heuristic over naming conventions used by common engines
 * (explicit "male"/"female"/"child" words, delimited single-letter markers like "-f-", and eSpeak's
 * "+f3"/"+m1" variants). Returns [VoiceGender.UNKNOWN] when nothing matches — the picker then relies
 * on the user's Test/Confirm. Child voices are uncommon and usually named explicitly.
 */
fun guessVoiceGender(voiceName: String): VoiceGender {
	val n = voiceName.lowercase()
	if (CHILD_MARKERS.any { it in n }) return VoiceGender.CHILD
	// Word markers first; check female before male because "female" contains "male".
	if (FEMALE_WORD_MARKERS.any { it in n }) return VoiceGender.FEMALE
	if (MALE_WORD_MARKERS.any { it in n }) return VoiceGender.MALE
	return when (delimitedGenderLetter(n)) {
		'f' -> VoiceGender.FEMALE
		'm' -> VoiceGender.MALE
		else -> VoiceGender.UNKNOWN
	}
}

/** Single-letter gender markers: eSpeak "+f3"/"+m1", or a delimited "f"/"m" token ("-f-", "_m_"). */
private fun delimitedGenderLetter(lowerName: String): Char? {
	ESPEAK_VARIANT.find(lowerName)?.let { return it.groupValues[1][0] }
	val tokens = lowerName.split(Regex("[^a-z0-9]+"))
	return when {
		tokens.any { it == "f" } -> 'f'
		tokens.any { it == "m" } -> 'm'
		else -> null
	}
}

private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

/** Variant code in Google voice names, e.g. "en-us-x-tpf-local" → "tpf". */
private val GOOGLE_VARIANT = Regex("""-x-([a-z]{3})[-#]""")

/**
 * Gender of known Google TTS voice variant codes. The codes are undocumented, so this table is
 * best-effort and holds only entries confirmed by listening on a device — extend freely as more are
 * validated. A code covers both its -local and -network twins (same persona). Unlisted codes stay
 * [VoiceGender.UNKNOWN], which renders as a neutral "Voice N". English + Spanish sets validated
 * 2026-07-17 on a Pixel Tablet; "tfn" (en-NG) sounded ambiguous and is deliberately omitted.
 */
private val GOOGLE_VARIANT_GENDERS: Map<String, VoiceGender> = mapOf(
	// en-AU
	"aua" to VoiceGender.FEMALE,
	"aub" to VoiceGender.MALE,
	"auc" to VoiceGender.FEMALE,
	"aud" to VoiceGender.MALE,
	// en-IN
	"ena" to VoiceGender.FEMALE,
	"enc" to VoiceGender.FEMALE,
	"end" to VoiceGender.MALE,
	"ene" to VoiceGender.MALE,
	// en-GB
	"gba" to VoiceGender.FEMALE,
	"gbb" to VoiceGender.MALE,
	"gbc" to VoiceGender.FEMALE,
	"gbd" to VoiceGender.MALE,
	"gbg" to VoiceGender.FEMALE,
	"rjs" to VoiceGender.MALE,
	// en-US
	"iob" to VoiceGender.FEMALE,
	"iog" to VoiceGender.FEMALE,
	"iol" to VoiceGender.MALE,
	"iom" to VoiceGender.MALE,
	"sfg" to VoiceGender.FEMALE,
	"tpc" to VoiceGender.FEMALE,
	"tpd" to VoiceGender.MALE,
	"tpf" to VoiceGender.FEMALE,
	// es-ES
	"eea" to VoiceGender.FEMALE,
	"eec" to VoiceGender.FEMALE,
	"eed" to VoiceGender.MALE,
	"eee" to VoiceGender.FEMALE,
	"eef" to VoiceGender.MALE,
	// es-US
	"esc" to VoiceGender.FEMALE,
	"esd" to VoiceGender.MALE,
	"esf" to VoiceGender.MALE,
	"sfb" to VoiceGender.FEMALE,
)

/**
 * Gender of Google's generic per-locale default voices ("en-US-language"), which carry no variant
 * code. Same validation caveats as [GOOGLE_VARIANT_GENDERS]; "en-ng-language" sounded ambiguous and
 * is omitted. Keys are lowercase.
 */
private val GOOGLE_NAMED_VOICE_GENDERS: Map<String, VoiceGender> = mapOf(
	"en-au-language" to VoiceGender.FEMALE,
	"en-in-language" to VoiceGender.FEMALE,
	"en-gb-language" to VoiceGender.FEMALE,
	"en-us-language" to VoiceGender.MALE,
	"es-es-language" to VoiceGender.FEMALE,
	"es-us-language" to VoiceGender.FEMALE,
)

/**
 * [guessVoiceGender] plus an engine-aware fallback: Google voice names carry no gender words, but
 * their variant code (or, for generic defaults, their full name) sometimes identifies a known voice
 * (see [GOOGLE_VARIANT_GENDERS] / [GOOGLE_NAMED_VOICE_GENDERS]).
 */
fun inferVoiceGender(enginePackage: String, voiceName: String): VoiceGender {
	val guessed = guessVoiceGender(voiceName)
	if (guessed != VoiceGender.UNKNOWN) return guessed
	if (enginePackage == GOOGLE_TTS_PACKAGE) {
		val lower = voiceName.lowercase()
		val code = GOOGLE_VARIANT.find(lower)?.groupValues?.get(1)
		GOOGLE_VARIANT_GENDERS[code]?.let { return it }
		GOOGLE_NAMED_VOICE_GENDERS[lower]?.let { return it }
	}
	return VoiceGender.UNKNOWN
}

private const val LOCAL_SUFFIX = "-local"
private const val NETWORK_SUFFIX = "-network"

/** "en-au-x-aua" for either twin of the pair; null when the name has neither suffix. */
private fun voicePersonaBase(voiceName: String): String? = when {
	voiceName.endsWith(LOCAL_SUFFIX) -> voiceName.dropLast(LOCAL_SUFFIX.length)
	voiceName.endsWith(NETWORK_SUFFIX) -> voiceName.dropLast(NETWORK_SUFFIX.length)
	else -> null
}

/**
 * Collapse -local/-network twins (same engine, same persona at different quality) into ONE picker
 * entry, keeping the offline-capable -local twin as the canonical representative. Unpaired voices
 * pass through untouched. Selection stays per-persona: [preferredVoiceVariant] later swaps to the
 * network twin whenever the device is online.
 */
fun collapseVoiceTwins(options: List<TtsVoiceOption>): List<TtsVoiceOption> {
	val names = options.map { it.enginePackage to it.voiceName }.toSet()
	return options.filter { o ->
		// Drop a network voice only when its local twin (the canonical representative) is present.
		!o.voiceName.endsWith(NETWORK_SUFFIX) ||
			(o.enginePackage to voicePersonaBase(o.voiceName) + LOCAL_SUFFIX) !in names
	}
}

/**
 * The variant of [storedName] to actually speak with right now: the network twin when [online] and
 * it exists in [availableNames], else the local twin when it exists, else [storedName] itself.
 * Handles a stored name of either variant, so old prefs keep working.
 */
fun preferredVoiceVariant(storedName: String, availableNames: Set<String>, online: Boolean): String {
	val base = voicePersonaBase(storedName) ?: return storedName
	val network = base + NETWORK_SUFFIX
	val local = base + LOCAL_SUFFIX
	return when {
		online && network in availableNames -> network
		local in availableNames -> local
		else -> storedName
	}
}

/** A voice on the live TTS engine, reduced to the fields default selection needs. */
data class LiveVoiceInfo(val name: String, val language: String, val country: String, val gender: VoiceGender)

/**
 * The voice to auto-bind when the user has a gender preference but no explicit selection: same
 * language and preferred gender, voices in the target country first, name-sorted so the same device
 * always speaks with the same default. Null (= keep the engine default) when the preference is "any"
 * or nothing matches.
 */
fun selectGenderedDefault(voices: List<LiveVoiceInfo>, target: Locale, genderPref: String): LiveVoiceInfo? {
	val wanted = when (genderPref) {
		Constants.TTS_GENDER_MALE -> VoiceGender.MALE
		Constants.TTS_GENDER_FEMALE -> VoiceGender.FEMALE
		Constants.TTS_GENDER_CHILD -> VoiceGender.CHILD
		else -> return null
	}
	val pool = voices
		.filter { it.language.equals(target.language, ignoreCase = true) && it.gender == wanted }
		.sortedBy { it.name }
	return pool.firstOrNull { it.country.equals(target.country, ignoreCase = true) } ?: pool.firstOrNull()
}

/** A voice discovered during the picker scan, reduced to the fields auto-selection needs. */
data class TtsVoiceCandidate(val enginePackage: String, val voiceName: String, val gender: VoiceGender)

/**
 * Pick a sensible default voice from [candidates] given the user's [genderPref] (a `TTS_GENDER_*`
 * value) and the system [defaultEngine]. Voices whose guessed gender matches the preference win; if
 * none match (or the preference is "any"), all candidates are eligible. Within the eligible set the
 * default engine is preferred, else the first candidate. Returns null when there are no candidates.
 */
fun autoSelectVoice(candidates: List<TtsVoiceCandidate>, genderPref: String, defaultEngine: String?): TtsVoiceCandidate? {
	if (candidates.isEmpty()) return null
	val wanted = when (genderPref) {
		Constants.TTS_GENDER_MALE -> VoiceGender.MALE
		Constants.TTS_GENDER_FEMALE -> VoiceGender.FEMALE
		Constants.TTS_GENDER_CHILD -> VoiceGender.CHILD
		else -> null // TTS_GENDER_ANY / unknown: no gender constraint
	}
	val pool = wanted?.let { g -> candidates.filter { it.gender == g } }?.ifEmpty { candidates } ?: candidates
	return pool.firstOrNull { it.enginePackage == defaultEngine } ?: pool.first()
}
