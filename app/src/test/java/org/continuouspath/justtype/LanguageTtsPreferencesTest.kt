package org.continuouspath.justtype

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanguageTtsPreferencesTest {

	private lateinit var repo: SettingsRepository

	@Before fun setUp() {
		SettingsRepository.resetInstanceForTesting()
		repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
	}

	@After fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	@Test fun `no stored voice returns null`() {
		assertThat(LanguageTtsPreferences.voiceFor(repo, "Espanol")).isNull()
	}

	@Test fun `set then read round-trips engine and voice`() {
		LanguageTtsPreferences.setVoice(repo, "Espanol", TtsVoicePref("com.google.tts", "es-es-x-eea-local"))
		assertThat(LanguageTtsPreferences.voiceFor(repo, "Espanol"))
			.isEqualTo(TtsVoicePref("com.google.tts", "es-es-x-eea-local"))
	}

	@Test fun `entries are per-language and independent`() {
		LanguageTtsPreferences.setVoice(repo, "English", TtsVoicePref("eng.a", "en-1"))
		LanguageTtsPreferences.setVoice(repo, "Espanol", TtsVoicePref("eng.b", "es-1"))
		assertThat(LanguageTtsPreferences.voiceFor(repo, "English")?.voiceName).isEqualTo("en-1")
		assertThat(LanguageTtsPreferences.voiceFor(repo, "Espanol")?.voiceName).isEqualTo("es-1")
	}

	@Test fun `null fields collapse to no-preference`() {
		LanguageTtsPreferences.setVoice(repo, "English", TtsVoicePref(null, null))
		assertThat(LanguageTtsPreferences.voiceFor(repo, "English")).isNull()
	}

	@Test fun `voice-only pref keeps the default engine`() {
		LanguageTtsPreferences.setVoice(repo, "English", TtsVoicePref(null, "en-1"))
		val pref = LanguageTtsPreferences.voiceFor(repo, "English")
		assertThat(pref?.enginePackage).isNull()
		assertThat(pref?.voiceName).isEqualTo("en-1")
	}

	@Test fun `clear removes only the targeted language`() {
		LanguageTtsPreferences.setVoice(repo, "English", TtsVoicePref("eng.a", "en-1"))
		LanguageTtsPreferences.setVoice(repo, "Espanol", TtsVoicePref("eng.b", "es-1"))
		LanguageTtsPreferences.clear(repo, "English")
		assertThat(LanguageTtsPreferences.voiceFor(repo, "English")).isNull()
		assertThat(LanguageTtsPreferences.voiceFor(repo, "Espanol")?.voiceName).isEqualTo("es-1")
	}
}
