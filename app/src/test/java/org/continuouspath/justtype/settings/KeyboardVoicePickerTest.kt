package org.continuouspath.justtype.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LanguageTtsPreferences
import org.continuouspath.justtype.TtsVoiceOption
import org.continuouspath.justtype.TtsVoiceServices
import org.continuouspath.justtype.VoiceGender
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class KeyboardVoicePickerTest {

	private class FakeVoiceServices : TtsVoiceServices {
		var scanCallback: ((List<TtsVoiceOption>, String?) -> Unit)? = null
		var cancelled = false
		val previewed = mutableListOf<TtsVoiceOption>()
		var previewStopped = false

		override fun startScan(locale: Locale, onDone: (List<TtsVoiceOption>, String?) -> Unit): () -> Unit {
			scanCallback = onDone
			return { cancelled = true }
		}

		override fun preview(option: TtsVoiceOption, locale: Locale) {
			previewed.add(option)
		}

		override fun stopPreview() {
			previewStopped = true
		}
	}

	private lateinit var repo: SettingsRepository
	private lateinit var services: FakeVoiceServices
	private lateinit var controller: KeyboardSettingsController
	private var lastDisplay: SettingsDisplayState? = null
	private val launchedActions = mutableListOf<String>()

	// Two engines both serve "es", so every friendly label carries the engine suffix. Sorted for
	// display (no gender pref, en test locale) the order is:
	//   "Spanish (Spain) Female 1 — Engine A"         (es-es-voice-2)
	//   "Spanish (Spain) Voice 1 — Engine A"          (es-es-voice-1)
	//   "Spanish (United States) Male 1 — Engine B"   (es-us-voice-1)
	private val voices = listOf(
		TtsVoiceOption("com.engine.a", "Engine A", "es-es-voice-1", VoiceGender.UNKNOWN, "es", "ES"),
		TtsVoiceOption("com.engine.a", "Engine A", "es-es-voice-2", VoiceGender.FEMALE, "es", "ES"),
		TtsVoiceOption("com.engine.b", "Engine B", "es-us-voice-1", VoiceGender.MALE, "es", "US"),
	)

	@Before fun setUp() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		SettingsRepository.resetInstanceForTesting()
		repo = SettingsRepository.getInstance(ctx)
		SettingsRegistry.reinitialize(ctx)
		services = FakeVoiceServices()
		controller = KeyboardSettingsController(
			context = ctx,
			prefs = repo,
			onDisplayUpdate = { lastDisplay = it },
			onSettingsExit = {},
			onRequestKeyboardPage = {},
			onApplySettingImmediate = { _, _ -> },
			onRevertSettingImmediate = { _, _ -> },
			voiceServices = services,
			onLaunchAction = { launchedActions.add(it) },
		)
		controller.enter()
		// Voice and language settings now live on the LANGUAGE OPTIONS sub-page.
		activateRow(ctx.getString(org.continuouspath.justtype.R.string.sr_main_nav_language_options))
	}

	@After fun tearDown() {
		controller.dispose()
		SettingsRepository.resetInstanceForTesting()
		SettingsRegistry.resetInstanceForTesting()
	}

	/** DOWN (key 6) until the focused row has [label], then press key 2 (action). */
	private fun activateRow(label: String) {
		repeat(60) {
			val display = lastDisplay!!
			val focused = display.rows.getOrNull(display.focusedRowIndex)
			if (focused?.label == label) {
				controller.handleKey(2)
				return
			}
			controller.handleKey(6)
		}
		error("Row '$label' not found on the main settings page")
	}

	private fun mainPageLabel(res: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

	private fun enterVoicePicker() = activateRow(mainPageLabel(org.continuouspath.justtype.R.string.sr_main_speech_voice))

	@Test fun `entering the picker scans then lists voices with picker key legend`() {
		enterVoicePicker()
		assertThat(services.scanCallback).isNotNull() // scan started
		assertThat(lastDisplay!!.rows.single().isInfoText).isTrue() // scanning row

		services.scanCallback!!.invoke(voices, "com.engine.a")

		val display = lastDisplay!!
		assertThat(display.rows).hasSize(3)
		assertThat(display.rows[0].label).isEqualTo("Spanish (Spain) Female 1 — Engine A")
		assertThat(display.rows[1].label).isEqualTo("Spanish (Spain) Voice 1 — Engine A")
		assertThat(display.rows[2].label).isEqualTo("Spanish (United States) Male 1 — Engine B")
		assertThat(display.keyLabels[1]).isNotEmpty() // UP
		assertThat(display.keyLabels[2]).isNotEmpty() // TEST
		assertThat(display.keyLabels[5]).isNotEmpty() // APPLY
		assertThat(display.keyLabels[6]).isNotEmpty() // DOWN
		assertThat(display.keyLabels[7]).isNotEmpty() // BACK
	}

	@Test fun `up and down move the highlight and announce the focused voice`() {
		enterVoicePicker()
		services.scanCallback!!.invoke(voices, null)
		assertThat(lastDisplay!!.focusedRowIndex).isEqualTo(0)

		controller.handleKey(6) // DOWN
		assertThat(lastDisplay!!.focusedRowIndex).isEqualTo(1)
		assertThat(lastDisplay!!.speechText).isEqualTo("Spanish (Spain) Voice 1 — Engine A")

		controller.handleKey(6) // DOWN
		controller.handleKey(6) // DOWN — clamped at last row
		assertThat(lastDisplay!!.focusedRowIndex).isEqualTo(2)

		controller.handleKey(1) // UP
		assertThat(lastDisplay!!.focusedRowIndex).isEqualTo(1)
	}

	@Test fun `test key previews the highlighted voice`() {
		enterVoicePicker()
		services.scanCallback!!.invoke(voices, null)
		controller.handleKey(6) // DOWN to the second sorted row
		controller.handleKey(2) // TEST
		assertThat(services.previewed.single().voiceName).isEqualTo("es-es-voice-1")
	}

	@Test fun `apply persists the highlighted voice and marks it current`() {
		enterVoicePicker()
		services.scanCallback!!.invoke(voices, null)
		controller.handleKey(6) // DOWN
		controller.handleKey(5) // APPLY

		val saved = LanguageTtsPreferences.voiceFor(repo, Constants.TYPING_LANGUAGE_ENGLISH)
		assertThat(saved?.enginePackage).isEqualTo("com.engine.a")
		assertThat(saved?.voiceName).isEqualTo("es-es-voice-1")
		assertThat(lastDisplay!!.rows[1].valueText).isNotNull() // CURRENT marker
		assertThat(lastDisplay!!.rows[0].valueText).isNull()
	}

	@Test fun `picker starts on the previously saved voice`() {
		LanguageTtsPreferences.setVoice(
			repo,
			Constants.TYPING_LANGUAGE_ENGLISH,
			org.continuouspath.justtype.TtsVoicePref("com.engine.b", "es-us-voice-1"),
		)
		enterVoicePicker()
		services.scanCallback!!.invoke(voices, null)
		assertThat(lastDisplay!!.focusedRowIndex).isEqualTo(2)
	}

	@Test fun `back returns to the settings page and stops the preview`() {
		enterVoicePicker()
		services.scanCallback!!.invoke(voices, null)
		controller.handleKey(7) // BACK
		assertThat(services.previewStopped).isTrue()
		// Back on the main page: rows are the registry settings again, not the voice list.
		assertThat(lastDisplay!!.rows.size).isGreaterThan(3)
	}

	@Test fun `empty scan shows none-found and disables TEST and APPLY`() {
		enterVoicePicker()
		services.scanCallback!!.invoke(emptyList(), null)
		val display = lastDisplay!!
		assertThat(display.rows.single().isInfoText).isTrue()
		assertThat(display.keyLabels[2]).isEmpty() // TEST hidden
		assertThat(display.keyLabels[5]).isEmpty() // APPLY hidden
		controller.handleKey(5) // APPLY — must be a no-op
		assertThat(LanguageTtsPreferences.voiceFor(repo, Constants.TYPING_LANGUAGE_ENGLISH)).isNull()
	}

	@Test fun `dispose cancels an in-flight scan`() {
		enterVoicePicker()
		controller.dispose()
		assertThat(services.cancelled).isTrue()
	}

	@Test fun `action rows show OPEN on key 2 when focused`() {
		val label = mainPageLabel(org.continuouspath.justtype.R.string.sr_main_speech_voice)
		repeat(60) {
			val display = lastDisplay!!
			if (display.rows.getOrNull(display.focusedRowIndex)?.label == label) {
				val open = mainPageLabel(org.continuouspath.justtype.R.string.settings_ctrl_open)
				assertThat(display.keyLabels[2]).isEqualTo(open)
				return
			}
			controller.handleKey(6)
		}
		error("Voice-picker action row not found")
	}

	@Test fun `ui voice picker applies under the ui pref key`() {
		activateRow(mainPageLabel(org.continuouspath.justtype.R.string.sr_main_voice_for_ui_language))
		services.scanCallback!!.invoke(voices, null)
		controller.handleKey(5) // APPLY first option

		val uiKey = org.continuouspath.justtype.UiVoice.prefKey(repo)
		val saved = LanguageTtsPreferences.voiceFor(repo, uiKey)
		assertThat(saved?.voiceName).isEqualTo("es-es-voice-2")
		// The typing-language voice must be untouched.
		assertThat(LanguageTtsPreferences.voiceFor(repo, Constants.TYPING_LANGUAGE_ENGLISH)).isNull()
	}

	@Test fun `get more languages action fires the launcher`() {
		activateRow(mainPageLabel(org.continuouspath.justtype.R.string.sr_main_get_more_languages))
		assertThat(launchedActions).containsExactly("get_more_languages")
	}

	// ── Friendly names on the main settings page ─────────────────────

	private fun mainPageRow(res: Int): SettingsRow {
		controller.handleKey(6) // any navigation re-emits the page
		return lastDisplay!!.rows.first { it.label == mainPageLabel(res) }
	}

	@Test fun `voices matching the gender preference sort to the top`() {
		repo.putString(Constants.KEY_TTS_VOICE_GENDER, Constants.TTS_GENDER_MALE)
		enterVoicePicker()
		services.scanCallback!!.invoke(voices, null)
		assertThat(lastDisplay!!.rows[0].label).isEqualTo("Spanish (United States) Male 1 — Engine B")
	}

	@Test fun `voice rows show DEFAULT VOICE variants when nothing is selected`() {
		assertThat(mainPageRow(org.continuouspath.justtype.R.string.sr_main_speech_voice).valueText)
			.isEqualTo("DEFAULT VOICE")
		assertThat(mainPageRow(org.continuouspath.justtype.R.string.sr_main_voice_for_ui_language).valueText)
			.isEqualTo("DEFAULT VOICE")

		// Each row follows its own voice-type preference.
		repo.putString(Constants.KEY_TTS_VOICE_GENDER, Constants.TTS_GENDER_FEMALE)
		repo.putString(Constants.KEY_TTS_UI_VOICE_GENDER, Constants.TTS_GENDER_MALE)
		assertThat(mainPageRow(org.continuouspath.justtype.R.string.sr_main_speech_voice).valueText)
			.isEqualTo("DEFAULT FEMALE VOICE")
		assertThat(mainPageRow(org.continuouspath.justtype.R.string.sr_main_voice_for_ui_language).valueText)
			.isEqualTo("DEFAULT MALE VOICE")
	}

	@Test fun `applied voice shows its friendly name on the settings row`() {
		enterVoicePicker()
		services.scanCallback!!.invoke(voices, null)
		controller.handleKey(5) // APPLY the first sorted voice
		controller.handleKey(7) // BACK to the main page

		assertThat(mainPageRow(org.continuouspath.justtype.R.string.sr_main_speech_voice).valueText)
			.isEqualTo("Spanish (Spain) Female 1 — Engine A")
	}
}
