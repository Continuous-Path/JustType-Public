package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/** Pure-helper tests for [localeForTypingLanguage], [guessVoiceGender] and [autoSelectVoice]. */
class LanguageTtsTest {

	// ── localeForTypingLanguage ─────────────────────────────────────────

	@Test fun `known ids resolve to their ISO locale`() {
		assertThat(localeForTypingLanguage("English").language).isEqualTo("en")
		assertThat(localeForTypingLanguage("Espanol").language).isEqualTo("es")
	}

	@Test fun `unknown id falls back to the device locale`() {
		assertThat(localeForTypingLanguage("Klingon")).isEqualTo(Locale.getDefault())
	}

	// ── guessVoiceGender ────────────────────────────────────────────────

	@Test fun `explicit gender words are detected (female before male)`() {
		assertThat(guessVoiceGender("en-US-female")).isEqualTo(VoiceGender.FEMALE)
		assertThat(guessVoiceGender("female")).isEqualTo(VoiceGender.FEMALE) // contains "male" but female wins
		assertThat(guessVoiceGender("male-voice")).isEqualTo(VoiceGender.MALE)
		assertThat(guessVoiceGender("woman")).isEqualTo(VoiceGender.FEMALE)
		assertThat(guessVoiceGender("boy")).isEqualTo(VoiceGender.MALE)
	}

	@Test fun `child voices are detected`() {
		assertThat(guessVoiceGender("child")).isEqualTo(VoiceGender.CHILD)
		assertThat(guessVoiceGender("en-us-kid")).isEqualTo(VoiceGender.CHILD)
		assertThat(guessVoiceGender("voice-junior")).isEqualTo(VoiceGender.CHILD)
	}

	@Test fun `delimited and eSpeak markers are detected`() {
		assertThat(guessVoiceGender("en-us-f-network")).isEqualTo(VoiceGender.FEMALE)
		assertThat(guessVoiceGender("en+f3")).isEqualTo(VoiceGender.FEMALE)
		assertThat(guessVoiceGender("gmw/en+m1")).isEqualTo(VoiceGender.MALE)
	}

	@Test fun `opaque engine voice names are unknown`() {
		assertThat(guessVoiceGender("es-es-x-eea-local")).isEqualTo(VoiceGender.UNKNOWN)
		assertThat(guessVoiceGender("en-us-x-sfg-local")).isEqualTo(VoiceGender.UNKNOWN)
	}

	// ── autoSelectVoice ─────────────────────────────────────────────────

	private val male = TtsVoiceCandidate("eng.a", "m1", VoiceGender.MALE)
	private val female = TtsVoiceCandidate("eng.b", "f1", VoiceGender.FEMALE)
	private val unknown = TtsVoiceCandidate("eng.a", "u1", VoiceGender.UNKNOWN)

	@Test fun `empty candidates yield null`() {
		assertThat(autoSelectVoice(emptyList(), Constants.TTS_GENDER_ANY, "eng.a")).isNull()
	}

	@Test fun `gender preference selects a matching voice`() {
		val pick = autoSelectVoice(listOf(male, female), Constants.TTS_GENDER_FEMALE, "eng.a")
		assertThat(pick).isEqualTo(female)
	}

	@Test fun `no gender match falls back, preferring the default engine`() {
		// Prefer CHILD but none exists → fall back to all; default engine eng.a wins.
		val pick = autoSelectVoice(listOf(male, female, unknown), Constants.TTS_GENDER_CHILD, "eng.a")
		assertThat(pick?.enginePackage).isEqualTo("eng.a")
	}

	@Test fun `ANY preference prefers the default engine`() {
		val pick = autoSelectVoice(listOf(female, male), Constants.TTS_GENDER_ANY, "eng.a")
		assertThat(pick).isEqualTo(male) // eng.a is the default engine
	}

	// ── inferVoiceGender ────────────────────────────────────────────────

	@Test fun `name-based markers win regardless of engine`() {
		assertThat(inferVoiceGender("com.google.android.tts", "en-us-x-sfg#female_2-local")).isEqualTo(VoiceGender.FEMALE)
		assertThat(inferVoiceGender("com.espeak", "en+m1")).isEqualTo(VoiceGender.MALE)
	}

	@Test fun `opaque google variants without a curated entry stay unknown`() {
		// GOOGLE_VARIANT_GENDERS holds only device-validated codes; unlisted ones must not guess.
		assertThat(inferVoiceGender("com.google.android.tts", "en-us-x-zzz-local")).isEqualTo(VoiceGender.UNKNOWN)
		assertThat(inferVoiceGender("com.other.engine", "en-us-x-zzz-local")).isEqualTo(VoiceGender.UNKNOWN)
	}

	@Test fun `curated google variant codes map both twins to the validated gender`() {
		assertThat(inferVoiceGender("com.google.android.tts", "en-au-x-aua-local")).isEqualTo(VoiceGender.FEMALE)
		assertThat(inferVoiceGender("com.google.android.tts", "en-au-x-aua-network")).isEqualTo(VoiceGender.FEMALE)
		assertThat(inferVoiceGender("com.google.android.tts", "en-us-x-tpd-network")).isEqualTo(VoiceGender.MALE)
		// Ambiguous-by-ear code deliberately unlisted; other engines never use the Google table.
		assertThat(inferVoiceGender("com.google.android.tts", "en-ng-x-tfn-local")).isEqualTo(VoiceGender.UNKNOWN)
		assertThat(inferVoiceGender("com.other.engine", "en-au-x-aua-local")).isEqualTo(VoiceGender.UNKNOWN)
	}

	@Test fun `generic google default voices map by exact name`() {
		assertThat(inferVoiceGender("com.google.android.tts", "en-US-language")).isEqualTo(VoiceGender.MALE)
		assertThat(inferVoiceGender("com.google.android.tts", "en-GB-language")).isEqualTo(VoiceGender.FEMALE)
		assertThat(inferVoiceGender("com.google.android.tts", "es-ES-language")).isEqualTo(VoiceGender.FEMALE)
		assertThat(inferVoiceGender("com.google.android.tts", "en-NG-language")).isEqualTo(VoiceGender.UNKNOWN)
	}

	@Test fun `spanish variant codes map to their validated gender`() {
		assertThat(inferVoiceGender("com.google.android.tts", "es-es-x-eea-local")).isEqualTo(VoiceGender.FEMALE)
		assertThat(inferVoiceGender("com.google.android.tts", "es-es-x-eed-network")).isEqualTo(VoiceGender.MALE)
		assertThat(inferVoiceGender("com.google.android.tts", "es-us-x-sfb-local")).isEqualTo(VoiceGender.FEMALE)
	}

	// ── collapseVoiceTwins ──────────────────────────────────────────────

	private fun opt(name: String, engine: String = "com.google.android.tts") = TtsVoiceOption(engine, "Google", name, VoiceGender.UNKNOWN, "en", "US")

	@Test fun `local-network twins collapse to the local representative`() {
		val collapsed = collapseVoiceTwins(
			listOf(opt("en-us-x-iob-local"), opt("en-us-x-iob-network"), opt("en-US-language")),
		)
		assertThat(collapsed.map { it.voiceName }).containsExactly("en-us-x-iob-local", "en-US-language")
	}

	@Test fun `unpaired network voices and other engines survive the collapse`() {
		val collapsed = collapseVoiceTwins(
			listOf(
				opt("en-us-x-net-only-network"),
				opt("en-us-x-iob-local"),
				opt("en-us-x-iob-network", engine = "com.other.engine"),
			),
		)
		assertThat(collapsed.map { it.voiceName })
			.containsExactly("en-us-x-net-only-network", "en-us-x-iob-local", "en-us-x-iob-network")
	}

	// ── preferredVoiceVariant ───────────────────────────────────────────

	private val twins = setOf("en-us-x-iob-local", "en-us-x-iob-network")

	@Test fun `online prefers the network twin, offline the local`() {
		assertThat(preferredVoiceVariant("en-us-x-iob-local", twins, online = true)).isEqualTo("en-us-x-iob-network")
		assertThat(preferredVoiceVariant("en-us-x-iob-local", twins, online = false)).isEqualTo("en-us-x-iob-local")
		// A stored network name (old prefs) still resolves to the working twin offline.
		assertThat(preferredVoiceVariant("en-us-x-iob-network", twins, online = false)).isEqualTo("en-us-x-iob-local")
	}

	@Test fun `variant selection is a no-op without a twin or suffix`() {
		assertThat(preferredVoiceVariant("en-US-language", setOf("en-US-language"), online = true)).isEqualTo("en-US-language")
		assertThat(preferredVoiceVariant("en-us-x-iob-network", setOf("en-us-x-iob-network"), online = false))
			.isEqualTo("en-us-x-iob-network")
	}

	// ── selectGenderedDefault ───────────────────────────────────────────

	private val usFemale = LiveVoiceInfo("us-f", "en", "US", VoiceGender.FEMALE)
	private val gbFemale = LiveVoiceInfo("gb-f", "en", "GB", VoiceGender.FEMALE)
	private val usMale = LiveVoiceInfo("us-m", "en", "US", VoiceGender.MALE)
	private val esFemale = LiveVoiceInfo("es-f", "es", "ES", VoiceGender.FEMALE)

	@Test fun `ANY preference never auto-binds`() {
		assertThat(selectGenderedDefault(listOf(usFemale), Locale("en", "US"), Constants.TTS_GENDER_ANY)).isNull()
	}

	@Test fun `selection filters by language and gender, preferring the target country`() {
		val all = listOf(esFemale, usMale, gbFemale, usFemale)
		assertThat(selectGenderedDefault(all, Locale("en", "US"), Constants.TTS_GENDER_FEMALE)).isEqualTo(usFemale)
		assertThat(selectGenderedDefault(all, Locale("en", "US"), Constants.TTS_GENDER_MALE)).isEqualTo(usMale)
		assertThat(selectGenderedDefault(all, Locale("es"), Constants.TTS_GENDER_FEMALE)).isEqualTo(esFemale)
	}

	@Test fun `country-less target picks the first name-sorted match`() {
		assertThat(selectGenderedDefault(listOf(usFemale, gbFemale), Locale("en"), Constants.TTS_GENDER_FEMALE))
			.isEqualTo(gbFemale) // "gb-f" < "us-f"
	}

	@Test fun `no gender match yields null so the engine default stands`() {
		assertThat(selectGenderedDefault(listOf(usFemale), Locale("en", "US"), Constants.TTS_GENDER_CHILD)).isNull()
		assertThat(selectGenderedDefault(listOf(esFemale), Locale("en", "US"), Constants.TTS_GENDER_FEMALE)).isNull()
	}
}
