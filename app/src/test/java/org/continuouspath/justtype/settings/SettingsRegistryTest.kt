package org.continuouspath.justtype.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRegistryTest {
	private lateinit var context: Context
	private lateinit var registry: SettingsRegistry
	private lateinit var repo: SettingsRepository

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		// Initialize singletons (idempotent if already created)
		registry = SettingsRegistry.getInstance(context)
		repo = SettingsRepository.getInstance(context)
		// Clear all data so each test starts fresh
		repo.clearForTesting()
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
		SettingsRegistry.resetInstanceForTesting()
	}

	@Test
	fun `all SubPage targetPageIds reference real pages`() {
		val pageIds = registry.pages.keys
		registry.pages.values
			.flatten()
			.filterIsInstance<SettingsDef.SubPage>()
			.forEach { subPage ->
				assertTrue(
					"SubPage '${subPage.key}' references unknown page '${subPage.targetPageId}'",
					subPage.targetPageId in pageIds,
				)
			}
	}

	@Test
	fun `all setting keys within a page are unique`() {
		registry.pages.forEach { (pageId, items) ->
			val keys = items.map { it.key }
			val duplicates = keys.groupBy { it }.filter { it.value.size > 1 }.keys
			assertTrue(
				"Duplicate keys found on page '$pageId': $duplicates",
				duplicates.isEmpty(),
			)
		}
	}

	@Test
	fun `all IntSlider defaultValues are within their min-max range`() {
		registry.pages.values
			.flatten()
			.filterIsInstance<SettingsDef.IntSlider>()
			.forEach { slider ->
				assertTrue(
					"IntSlider '${slider.key}' default ${slider.defaultValue} is below min ${slider.min}",
					slider.defaultValue >= slider.min,
				)
				assertTrue(
					"IntSlider '${slider.key}' default ${slider.defaultValue} is above max ${slider.max}",
					slider.defaultValue <= slider.max,
				)
			}
	}

	@Test
	fun `all FloatSlider defaultValues are within their min-max range`() {
		registry.pages.values
			.flatten()
			.filterIsInstance<SettingsDef.FloatSlider>()
			.forEach { slider ->
				assertTrue(
					"FloatSlider '${slider.key}' default ${slider.defaultValue} is below min ${slider.min}",
					slider.defaultValue >= slider.min,
				)
				assertTrue(
					"FloatSlider '${slider.key}' default ${slider.defaultValue} is above max ${slider.max}",
					slider.defaultValue <= slider.max,
				)
			}
	}

	@Test
	fun `accent_fallback migrates to require_accented_keys with inverted sense`() {
		// Legacy user who turned Accent Fallback OFF -> now requires accented keys.
		repo.putBoolean(org.continuouspath.justtype.Constants.KEY_ACCENT_FALLBACK, false)
		registry.ensureDefaults(repo)
		assertTrue(repo.getBoolean(org.continuouspath.justtype.Constants.KEY_REQUIRE_ACCENTED_KEYS, false))
		assertTrue(!repo.contains(org.continuouspath.justtype.Constants.KEY_ACCENT_FALLBACK))
	}

	@Test
	fun `accent_fallback ON migrates to require OFF and is removed`() {
		repo.putBoolean(org.continuouspath.justtype.Constants.KEY_ACCENT_FALLBACK, true)
		registry.ensureDefaults(repo)
		assertTrue(!repo.getBoolean(org.continuouspath.justtype.Constants.KEY_REQUIRE_ACCENTED_KEYS, true))
		assertTrue(!repo.contains(org.continuouspath.justtype.Constants.KEY_ACCENT_FALLBACK))
	}

	@Test
	fun `ensureDefaults writes correct types for each setting kind`() {
		registry.ensureDefaults(repo)

		registry.pages.values.flatten().forEach { item ->
			when (item) {
				is SettingsDef.Toggle -> {
					assertTrue("Toggle '${item.key}' should be in repo", repo.contains(item.key))
					repo.getBoolean(item.key, !item.defaultValue) // reads without exception
				}
				is SettingsDef.IntSlider -> {
					assertTrue("IntSlider '${item.key}' should be in repo", repo.contains(item.key))
					repo.getInt(item.key, -1)
				}
				is SettingsDef.FloatSlider -> {
					assertTrue("FloatSlider '${item.key}' should be in repo", repo.contains(item.key))
					repo.getFloat(item.key, -1f)
				}
				is SettingsDef.Choice -> {
					assertTrue("Choice '${item.key}' should be in repo", repo.contains(item.key))
					assertNotNull(repo.getString(item.key, ""))
				}
				is SettingsDef.KeyCapture -> {
					assertTrue("KeyCapture '${item.key}' should be in repo", repo.contains(item.key))
					repo.getInt(item.key, -1)
				}
				else -> { /* SectionHeader, SubPage, InfoText have no stored value */ }
			}
		}
	}

	@Test
	fun `main page exists and is not empty`() {
		assertNotNull(registry.pages["main"])
		assertTrue((registry.pages["main"]?.size ?: 0) > 0)
	}

	@Test
	fun `input_methods page exists`() {
		assertNotNull(registry.pages["input_methods"])
	}

	@Test
	fun `language options are ordered with each voice-type preference before its picker`() {
		val keys = registry.pages["language_options"].orEmpty().map { it.key }
		val expected = listOf(
			org.continuouspath.justtype.Constants.KEY_APP_LANGUAGE,
			org.continuouspath.justtype.Constants.KEY_TYPING_LANGUAGE,
			org.continuouspath.justtype.Constants.KEY_TTS_VOICE_GENDER,
			"voice_for_language",
			"get_more_languages",
			org.continuouspath.justtype.Constants.KEY_TTS_UI_VOICE_GENDER,
			"voice_for_ui_language",
			org.continuouspath.justtype.Constants.KEY_SPELL_DIACRITIC_SCOPE,
		)
		assertTrue("main page must contain the language options in order", keys.filter { it in expected } == expected)
	}

	@Test
	fun `mouse_joystick page leads with presets and hides advanced knobs`() {
		val items = registry.pages["mouse_joystick"].orEmpty()
		val keys = items.map { it.key }
		// One-tap presets come first, each an Action.
		val presetKeys = listOf("mj_preset_light", "mj_preset_standard", "mj_preset_firm")
		assertTrue("presets must be present", keys.containsAll(presetKeys))
		presetKeys.forEach { k ->
			assertTrue("$k must be an Action", items.first { it.key == k } is SettingsDef.Action)
		}
		// The primary knobs (sensitivity, dead zone) sit before the Advanced header;
		// the expert knobs (active zone, corner bias, reengage) sit after it.
		val advancedIdx = keys.indexOf("mj_advanced_header")
		assertTrue("Advanced header must exist", advancedIdx >= 0)
		assertTrue(
			"sensitivity + dead zone must precede Advanced",
			keys.indexOf(org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_SENSITIVITY_DP) in 0 until advancedIdx &&
				keys.indexOf(org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_DEADZONE) in 0 until advancedIdx,
		)
		assertTrue(
			"reengage must be under Advanced",
			keys.indexOf(org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS) > advancedIdx,
		)
		// Barrier height is an implementation detail — no longer a visible knob.
		assertTrue(
			"barrier height must not be shown",
			keys.none { it == org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_BARRIER_HEIGHT_DP },
		)
	}

	@Test
	fun `spanish region row appears only when the langpack is present`() {
		val spanishKey = org.continuouspath.justtype.Constants.KEY_SPANISH_REGION
		assertTrue(registry.pages["language_options"].orEmpty().none { it.key == spanishKey })

		val spanish = org.continuouspath.justtype.LanguageEntry(
			name = org.continuouspath.justtype.Constants.TYPING_LANGUAGE_ESPANOL,
			localeCode = "es",
			diacriticSet = "",
			present = true,
			dbFileName = "EspanolDbActive.db",
		)
		val items = org.continuouspath.justtype.LanguageRegistry.load(repo)
		org.continuouspath.justtype.LanguageRegistry.save(repo, org.continuouspath.justtype.LanguageRegistry.upsert(items, spanish))
		SettingsRegistry.reinitialize(context)
		assertTrue(SettingsRegistry.get().pages["language_options"].orEmpty().any { it.key == spanishKey })
	}
}
