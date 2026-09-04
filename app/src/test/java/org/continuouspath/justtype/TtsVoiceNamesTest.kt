package org.continuouspath.justtype

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TtsVoiceNamesTest {

	private lateinit var ctx: Context
	private lateinit var repo: SettingsRepository

	@Before fun setUp() {
		ctx = ApplicationProvider.getApplicationContext()
		SettingsRepository.resetInstanceForTesting()
		repo = SettingsRepository.getInstance(ctx)
	}

	@After fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	private fun option(
		voiceName: String,
		gender: VoiceGender = VoiceGender.UNKNOWN,
		engine: String = "com.engine.a",
		engineLabel: String = "Engine A",
		language: String = "en",
		country: String = "US",
	) = TtsVoiceOption(engine, engineLabel, voiceName, gender, language, country)

	private fun labelsOf(options: List<TtsVoiceOption>): List<String> = TtsVoiceNames.prepareForDisplay(ctx, repo, options).map { it.label }

	// ── Ordinal assignment ───────────────────────────────────────────

	@Test fun `same-bucket voices number from 1 in engine-name order`() {
		val labels = labelsOf(listOf(option("v-b"), option("v-a")))
		assertThat(labels).containsExactly("English (United States) Voice 1", "English (United States) Voice 2").inOrder()
		// v-a sorts before v-b at registration, so it owns ordinal 1.
		val first = TtsVoiceNames.prepareForDisplay(ctx, repo, listOf(option("v-a"))).single()
		assertThat(first.label).isEqualTo("English (United States) Voice 1")
	}

	@Test fun `buckets are independent per language, region, and gender`() {
		val labels = labelsOf(
			listOf(
				option("v1", VoiceGender.FEMALE),
				option("v2", VoiceGender.MALE),
				option("v3", country = "GB"),
				option("v4", language = "es", country = "ES"),
			),
		)
		assertThat(labels).containsExactly(
			"English (United States) Female 1",
			"English (United States) Male 1",
			"English (United Kingdom) Voice 1",
			"Spanish (Spain) Voice 1",
		)
	}

	@Test fun `registration order does not affect assigned names`() {
		val opts = listOf(option("v-c"), option("v-a"), option("v-b"))
		val forward = TtsVoiceNames.registerInto(emptyMap(), opts)!!
		val reversed = TtsVoiceNames.registerInto(emptyMap(), opts.reversed())!!
		assertThat(forward).isEqualTo(reversed)
	}

	@Test fun `existing voices keep their ordinals when new ones arrive`() {
		TtsVoiceNames.prepareForDisplay(ctx, repo, listOf(option("v-b")))
		val byName = TtsVoiceNames.prepareForDisplay(ctx, repo, listOf(option("v-a"), option("v-b")))
			.associateBy { it.option.voiceName }
		// v-b registered first and keeps ordinal 1 even though v-a sorts before it.
		assertThat(byName["v-b"]!!.label).isEqualTo("English (United States) Voice 1")
		assertThat(byName["v-a"]!!.label).isEqualTo("English (United States) Voice 2")
	}

	@Test fun `re-registering the same voices changes nothing`() {
		val opts = listOf(option("v-a"), option("v-b"))
		TtsVoiceNames.prepareForDisplay(ctx, repo, opts)
		val snapshot = TtsVoiceNames.load(repo)
		assertThat(TtsVoiceNames.registerInto(snapshot, opts)).isNull()
	}

	@Test fun `a voice whose gender becomes known re-buckets with a fresh ordinal`() {
		TtsVoiceNames.prepareForDisplay(ctx, repo, listOf(option("v-a"), option("v-b")))
		// The curated table later learns v-a is female: same voice re-registers with the new gender.
		val labels = labelsOf(listOf(option("v-a", VoiceGender.FEMALE), option("v-b")))
		assertThat(labels).containsExactly(
			"English (United States) Female 1",
			"English (United States) Voice 2", // v-b keeps its original ordinal
		)
	}

	@Test fun `numeric ordinals sort naturally past nine`() {
		val opts = (1..11).map { option("v-%02d".format(it)) }
		val labels = labelsOf(opts)
		assertThat(labels.first()).isEqualTo("English (United States) Voice 1")
		assertThat(labels[1]).isEqualTo("English (United States) Voice 2")
		assertThat(labels.last()).isEqualTo("English (United States) Voice 11")
	}

	@Test fun `lowest unused ordinal fills gaps`() {
		val existing = TtsVoiceNames.registerInto(emptyMap(), listOf(option("v-1"), option("v-2"), option("v-3")))!!
		val withGap = existing.filterValues { it.voiceName != "v-2" } // ordinal 2 freed
		val updated = TtsVoiceNames.registerInto(withGap, listOf(option("v-9")))!!
		assertThat(updated.values.first { it.voiceName == "v-9" }.ord).isEqualTo(2)
	}

	@Test fun `map persists across load and save round-trip`() {
		TtsVoiceNames.prepareForDisplay(ctx, repo, listOf(option("v-a", VoiceGender.FEMALE, country = "GB")))
		val entry = TtsVoiceNames.load(repo).values.single()
		assertThat(entry.lang).isEqualTo("en")
		assertThat(entry.country).isEqualTo("GB")
		assertThat(entry.gender).isEqualTo(VoiceGender.FEMALE)
		assertThat(entry.ord).isEqualTo(1)
		assertThat(entry.engineLabel).isEqualTo("Engine A")
	}

	// ── Rendering ────────────────────────────────────────────────────

	@Test fun `region-less voices render without parentheses`() {
		val labels = labelsOf(listOf(option("v-a", VoiceGender.MALE, country = "")))
		assertThat(labels).containsExactly("English Male 1")
	}

	@Test fun `engine suffix appears only when several engines serve a language`() {
		val labels = labelsOf(
			listOf(
				option("v-a"),
				option("v-b", engine = "com.engine.b", engineLabel = "Engine B"),
				option("v-es", language = "es", country = "ES"),
			),
		)
		assertThat(labels).containsExactly(
			"English (United States) Voice 1 — Engine A",
			"English (United States) Voice 2 — Engine B",
			"Spanish (Spain) Voice 1",
		)
	}

	@Test fun `unregistered voices fall back to the raw name`() {
		assertThat(TtsVoiceNames.resolve(ctx, repo, "com.engine.a", "never-scanned")).isNull()
	}

	// ── Selection summaries ──────────────────────────────────────────

	@Test fun `no selection renders the DEFAULT label for the gender preference`() {
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "English")).isEqualTo("DEFAULT VOICE")
		repo.putString(Constants.KEY_TTS_VOICE_GENDER, Constants.TTS_GENDER_MALE)
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "English")).isEqualTo("DEFAULT MALE VOICE")
		repo.putString(Constants.KEY_TTS_VOICE_GENDER, Constants.TTS_GENDER_FEMALE)
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "English")).isEqualTo("DEFAULT FEMALE VOICE")
		repo.putString(Constants.KEY_TTS_VOICE_GENDER, Constants.TTS_GENDER_CHILD)
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "English")).isEqualTo("DEFAULT CHILD VOICE")
	}

	@Test fun `ui voice rows follow the UI voice-type preference, not the personal one`() {
		repo.putString(Constants.KEY_TTS_VOICE_GENDER, Constants.TTS_GENDER_MALE)
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "ui-en")).isEqualTo("DEFAULT VOICE")
		repo.putString(Constants.KEY_TTS_UI_VOICE_GENDER, Constants.TTS_GENDER_FEMALE)
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "ui-en")).isEqualTo("DEFAULT FEMALE VOICE")
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "English")).isEqualTo("DEFAULT MALE VOICE")
	}

	@Test fun `picker sorting follows the preference governing its pref key`() {
		repo.putString(Constants.KEY_TTS_UI_VOICE_GENDER, Constants.TTS_GENDER_MALE)
		val options = listOf(option("v-f", VoiceGender.FEMALE), option("v-m", VoiceGender.MALE))
		assertThat(TtsVoiceNames.prepareForDisplay(ctx, repo, options, "ui-en").first().option.voiceName).isEqualTo("v-m")
		// Personal picker: pref unset, so female sorts first by label.
		assertThat(TtsVoiceNames.prepareForDisplay(ctx, repo, options, "English").first().option.voiceName).isEqualTo("v-f")
	}

	@Test fun `a registered selection renders its friendly name`() {
		TtsVoiceNames.prepareForDisplay(ctx, repo, listOf(option("v-a", VoiceGender.FEMALE)))
		LanguageTtsPreferences.setVoice(repo, "English", TtsVoicePref("com.engine.a", "v-a"))
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "English"))
			.isEqualTo("English (United States) Female 1")
	}

	@Test fun `an unregistered selection falls back to the raw voice name`() {
		LanguageTtsPreferences.setVoice(repo, "English", TtsVoicePref("com.engine.a", "mystery-voice"))
		assertThat(TtsVoiceNames.currentSelectionLabel(ctx, repo, "English")).isEqualTo("mystery-voice")
	}

	// ── Sorting ──────────────────────────────────────────────────────

	@Test fun `gender preference matches sort first, rest by label`() {
		repo.putString(Constants.KEY_TTS_VOICE_GENDER, Constants.TTS_GENDER_MALE)
		val labeled = TtsVoiceNames.prepareForDisplay(
			ctx,
			repo,
			listOf(
				option("v-f", VoiceGender.FEMALE),
				option("v-m", VoiceGender.MALE),
				option("v-u"),
			),
		)
		assertThat(labeled.first().option.voiceName).isEqualTo("v-m")
		assertThat(labeled.first().matchesGenderPref).isTrue()
		assertThat(labeled.drop(1).map { it.label }).isInOrder()
		assertThat(labeled.drop(1).map { it.matchesGenderPref }).doesNotContain(true)
	}
}
