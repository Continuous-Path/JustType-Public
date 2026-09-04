package org.continuouspath.justtype.settings

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.TtsVoiceOption
import org.continuouspath.justtype.TtsVoiceServices
import org.continuouspath.justtype.langpack.CatalogEntry
import org.continuouspath.justtype.langpack.LangpackKeyboardServices
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class KeyboardLangManagerTest {

	private class FakeLangpackServices : LangpackKeyboardServices {
		var catalog: List<CatalogEntry> = emptyList()
		var loadCallback: ((List<CatalogEntry>) -> Unit)? = null
		val downloads = mutableListOf<String>()
		val removals = mutableListOf<String>()

		override fun loadCatalog(onResult: (List<CatalogEntry>) -> Unit): () -> Unit {
			loadCallback = onResult
			return {}
		}

		fun deliver() = loadCallback!!.invoke(catalog)

		override fun download(entry: CatalogEntry) {
			downloads.add(entry.id)
		}

		override fun remove(languageId: String, onDone: () -> Unit) {
			removals.add(languageId)
			onDone()
		}
	}

	private class FakeVoiceServices : TtsVoiceServices {
		var scanCallback: ((List<TtsVoiceOption>, String?) -> Unit)? = null
		var scanLocale: Locale? = null

		override fun startScan(locale: Locale, onDone: (List<TtsVoiceOption>, String?) -> Unit): () -> Unit {
			scanLocale = locale
			scanCallback = onDone
			return {}
		}

		override fun preview(option: TtsVoiceOption, locale: Locale) = Unit
		override fun stopPreview() = Unit
	}

	private lateinit var repo: SettingsRepository
	private lateinit var services: FakeLangpackServices
	private lateinit var voiceServices: FakeVoiceServices
	private lateinit var controller: KeyboardSettingsController
	private var lastDisplay: SettingsDisplayState? = null

	private val catalog = listOf(
		CatalogEntry("English", "English", CatalogEntry.State.BUILT_IN),
		CatalogEntry("Espanol", "Español", CatalogEntry.State.INSTALLED),
		CatalogEntry("Francais", "Français", CatalogEntry.State.AVAILABLE, downloadBytes = 5_000_000),
	)

	@Before fun setUp() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		SettingsRepository.resetInstanceForTesting()
		repo = SettingsRepository.getInstance(ctx)
		SettingsRegistry.reinitialize(ctx)
		services = FakeLangpackServices().also { it.catalog = catalog }
		voiceServices = FakeVoiceServices()
		controller = KeyboardSettingsController(
			context = ctx,
			prefs = repo,
			onDisplayUpdate = { lastDisplay = it },
			onSettingsExit = {},
			onRequestKeyboardPage = {},
			onApplySettingImmediate = { _, _ -> },
			onRevertSettingImmediate = { _, _ -> },
			voiceServices = voiceServices,
			langpackServices = services,
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
			if (display.rows.getOrNull(display.focusedRowIndex)?.label == label) {
				controller.handleKey(2)
				return
			}
			controller.handleKey(6)
		}
		error("Row '$label' not found on the current settings page")
	}

	private fun enterLangManager() {
		val label = ApplicationProvider.getApplicationContext<android.content.Context>()
			.getString(org.continuouspath.justtype.R.string.sr_main_get_more_languages)
		repeat(60) {
			val display = lastDisplay!!
			if (display.rows.getOrNull(display.focusedRowIndex)?.label == label) {
				controller.handleKey(2)
				return
			}
			controller.handleKey(6)
		}
		error("Get More Languages row not found")
	}

	@Test fun `opens in-keyboard with catalog rows and per-state key labels`() {
		enterLangManager()
		assertThat(lastDisplay!!.rows.single().isInfoText).isTrue() // loading row
		services.deliver()

		val display = lastDisplay!!
		assertThat(display.rows.map { it.label }).containsExactly("English", "Español", "Français").inOrder()
		// Focused on English (built-in): neither DOWNLOAD nor REMOVE offered.
		assertThat(display.keyLabels[0]).isEmpty()
		assertThat(display.keyLabels[2]).isEmpty()
		assertThat(display.keyLabels[1]).isNotEmpty() // UP
		assertThat(display.keyLabels[6]).isNotEmpty() // DOWN
		assertThat(display.keyLabels[7]).isNotEmpty() // BACK
	}

	@Test fun `key 2 is SELECT VOICE on installed rows and DOWNLOAD on available rows`() {
		enterLangManager()
		services.deliver()
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		controller.handleKey(6) // DOWN → Español (installed)
		assertThat(lastDisplay!!.keyLabels[2])
			.isEqualTo(ctx.getString(org.continuouspath.justtype.R.string.languages_action_select_voice))
		controller.handleKey(6) // DOWN → Français (available)
		assertThat(lastDisplay!!.keyLabels[2])
			.isEqualTo(ctx.getString(org.continuouspath.justtype.R.string.languages_action_download))

		controller.handleKey(2) // DOWNLOAD
		assertThat(services.downloads).containsExactly("Francais")
	}

	@Test fun `remove requires two presses and only works on installed rows`() {
		enterLangManager()
		services.deliver()
		controller.handleKey(0) // REMOVE on built-in English — no-op
		assertThat(services.removals).isEmpty()

		controller.handleKey(6) // DOWN → Español (installed)
		assertThat(lastDisplay!!.keyLabels[0]).isNotEmpty() // REMOVE offered
		controller.handleKey(0) // arm
		assertThat(services.removals).isEmpty()
		assertThat(lastDisplay!!.rows[1].pendingValueText).isNotNull() // armed marker
		controller.handleKey(0) // confirm
		assertThat(services.removals).containsExactly("Espanol")
	}

	@Test fun `moving the cursor disarms a pending remove`() {
		enterLangManager()
		services.deliver()
		controller.handleKey(6) // → Español
		controller.handleKey(0) // arm
		controller.handleKey(6) // move away disarms
		controller.handleKey(1) // back up
		controller.handleKey(0) // arm again (first press after disarm)
		assertThat(services.removals).isEmpty()
	}

	@Test fun `back returns to the settings page`() {
		enterLangManager()
		services.deliver()
		controller.handleKey(7) // BACK
		assertThat(lastDisplay!!.rows.size).isGreaterThan(3) // main settings rows again
	}

	@Test fun `select voice on an installed row opens the voice picker for that language`() {
		enterLangManager()
		services.deliver()
		controller.handleKey(6) // DOWN → Español (installed)
		val selectVoice = ApplicationProvider.getApplicationContext<android.content.Context>()
			.getString(org.continuouspath.justtype.R.string.languages_action_select_voice)
		assertThat(lastDisplay!!.keyLabels[2]).isEqualTo(selectVoice)

		controller.handleKey(2) // SELECT VOICE
		assertThat(voiceServices.scanCallback).isNotNull()
		assertThat(voiceServices.scanLocale?.language).isEqualTo("es")
		assertThat(lastDisplay!!.pageTitle).contains("Español")
	}

	@Test fun `back from the voice picker returns to the language list`() {
		enterLangManager()
		services.deliver()
		controller.handleKey(6) // → Español
		controller.handleKey(2) // SELECT VOICE
		voiceServices.scanCallback!!.invoke(emptyList(), null)
		controller.handleKey(7) // BACK from picker
		services.deliver() // resume triggers a catalog reload
		assertThat(lastDisplay!!.rows.map { it.label }).containsExactly("English", "Español", "Français").inOrder()
	}

	@Test fun `a download watched to completion auto-opens the voice picker`() {
		enterLangManager()
		services.deliver()
		controller.handleKey(6)
		controller.handleKey(6) // → Français (available)
		controller.handleKey(2) // DOWNLOAD (arms the install watch)

		services.catalog = listOf(
			catalog[0],
			catalog[1],
			CatalogEntry("Francais", "Français", CatalogEntry.State.DOWNLOADING, progressPct = 40),
		)
		services.deliver()
		assertThat(voiceServices.scanCallback).isNull() // still downloading — no picker yet

		services.catalog = listOf(
			catalog[0],
			catalog[1],
			CatalogEntry("Francais", "Français", CatalogEntry.State.INSTALLED),
		)
		services.deliver()

		assertThat(voiceServices.scanCallback).isNotNull() // picker auto-opened
		assertThat(voiceServices.scanLocale?.language).isEqualTo("fr")
		assertThat(lastDisplay!!.pageTitle).contains("Français")
	}
}
