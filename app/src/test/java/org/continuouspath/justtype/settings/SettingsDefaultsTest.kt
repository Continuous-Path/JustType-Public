package org.continuouspath.justtype.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.continuouspath.justtype.Constants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsDefaultsTest {
	private lateinit var context: Context
	private lateinit var repo: SettingsRepository

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		// Initialize singletons (idempotent if already created)
		SettingsRegistry.getInstance(context)
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
	fun `ensureAll sets all non-registry defaults for fresh prefs`() {
		SettingsDefaults.ensureAll(repo)

		assertTrue(repo.contains(Constants.KEY_SCAN_SWITCH_CODE))
		assertTrue(repo.contains(Constants.KEY_RED_SWITCH_CODE))
		assertTrue(repo.contains(Constants.KEY_GREEN_SWITCH_CODE))
		assertTrue(repo.contains(Constants.KEY_VOCAB_ACTIVE_MASK))
		assertTrue(repo.contains(Constants.KEY_VOCAB_ACCENT_MODULE_MASK))
		assertTrue(repo.contains(Constants.KEY_EXPORT_USAGE_AT_LEAST_COUNT))
		assertTrue(repo.contains(Constants.KEY_JUSTTYPE_ENABLED_HEADBOARD))

		assertEquals(Constants.SWITCH_CODE_UNDEFINED, repo.getInt(Constants.KEY_SCAN_SWITCH_CODE, 0))
		assertEquals(Constants.SWITCH_CODE_UNDEFINED, repo.getInt(Constants.KEY_RED_SWITCH_CODE, 0))
		assertEquals(Constants.SWITCH_CODE_UNDEFINED, repo.getInt(Constants.KEY_GREEN_SWITCH_CODE, 0))
		assertEquals(0L, repo.getLong(Constants.KEY_VOCAB_ACTIVE_MASK, 1L))
		assertEquals(0L, repo.getLong(Constants.KEY_VOCAB_ACCENT_MODULE_MASK, 1L))
		assertEquals(0, repo.getInt(Constants.KEY_EXPORT_USAGE_AT_LEAST_COUNT, -1))
		assertEquals(false, repo.getBoolean(Constants.KEY_JUSTTYPE_ENABLED_HEADBOARD, true))
	}

	@Test
	fun `ensureAll does not overwrite existing values`() {
		repo.putInt(Constants.KEY_SCAN_SWITCH_CODE, 999)
		repo.putBoolean(Constants.KEY_JUSTTYPE_ENABLED_HEADBOARD, true)

		SettingsDefaults.ensureAll(repo)

		assertEquals(999, repo.getInt(Constants.KEY_SCAN_SWITCH_CODE, 0))
		assertEquals(true, repo.getBoolean(Constants.KEY_JUSTTYPE_ENABLED_HEADBOARD, false))
	}

	@Test
	fun `ensureAll migrates legacy KEY_INPUT_METHOD to KEY_INPUT_METHOD_PRIMARY`() {
		@Suppress("DEPRECATION")
		repo.putString(Constants.KEY_INPUT_METHOD, Constants.INPUT_METHOD_HEAD_TRACKING)

		SettingsDefaults.ensureAll(repo)

		assertEquals(
			Constants.INPUT_METHOD_HEAD_TRACKING,
			repo.getString(Constants.KEY_INPUT_METHOD_PRIMARY, ""),
		)
	}

	@Test
	fun `ensureAll migration does not overwrite existing KEY_INPUT_METHOD_PRIMARY`() {
		repo.putString(Constants.KEY_INPUT_METHOD_PRIMARY, Constants.INPUT_METHOD_JOYSTICK)
		@Suppress("DEPRECATION")
		repo.putString(Constants.KEY_INPUT_METHOD, Constants.INPUT_METHOD_HEAD_TRACKING)

		SettingsDefaults.ensureAll(repo)

		assertEquals(
			Constants.INPUT_METHOD_JOYSTICK,
			repo.getString(Constants.KEY_INPUT_METHOD_PRIMARY, ""),
		)
	}

	@Test
	fun `effectiveInputMethod returns primary key when present`() {
		repo.putString(Constants.KEY_INPUT_METHOD_PRIMARY, Constants.INPUT_METHOD_JOYSTICK)
		@Suppress("DEPRECATION")
		repo.putString(Constants.KEY_INPUT_METHOD, Constants.INPUT_METHOD_HEAD_TRACKING)

		assertEquals(Constants.INPUT_METHOD_JOYSTICK, repo.effectiveInputMethod())
	}

	@Test
	fun `effectiveInputMethod falls back to legacy key when primary is absent`() {
		@Suppress("DEPRECATION")
		repo.putString(Constants.KEY_INPUT_METHOD, Constants.INPUT_METHOD_SINGLE_SWITCH)

		assertEquals(Constants.INPUT_METHOD_SINGLE_SWITCH, repo.effectiveInputMethod())
	}

	@Test
	fun `effectiveInputMethod returns NONE when both keys are absent`() {
		assertEquals(Constants.INPUT_METHOD_NONE, repo.effectiveInputMethod())
	}

	@Test
	fun `forceDirectSelectionFallback writes a usable Direct Selection config`() {
		repo.putString(Constants.KEY_INPUT_METHOD_PRIMARY, Constants.INPUT_METHOD_HEAD_TRACKING)

		repo.forceDirectSelectionFallback()

		assertEquals(Constants.INPUT_METHOD_NONE, repo.getString(Constants.KEY_INPUT_METHOD_PRIMARY, ""))
		assertTrue(repo.getBoolean(Constants.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, false))
		@Suppress("DEPRECATION")
		assertEquals(Constants.INPUT_METHOD_DIRECT_SELECTION, repo.getString(Constants.KEY_INPUT_METHOD, ""))
	}

	@Test
	fun `applySafeKeyboardDefaults coerces an out-of-range size ratio to the default`() {
		val def = SettingsRegistry.get().findByKey(Constants.KEY_KEYBOARD_SIZE_RATIO) as SettingsDef.FloatSlider
		repo.putFloat(Constants.KEY_KEYBOARD_SIZE_RATIO, def.max + 1f)

		repo.applySafeKeyboardDefaults()

		assertEquals(def.defaultValue, repo.getFloat(Constants.KEY_KEYBOARD_SIZE_RATIO, -1f), 0.0001f)
	}

	@Test
	fun `applySafeKeyboardDefaults leaves an in-range size ratio untouched`() {
		val def = SettingsRegistry.get().findByKey(Constants.KEY_KEYBOARD_SIZE_RATIO) as SettingsDef.FloatSlider
		val inRange = (def.min + def.max) / 2f
		repo.putFloat(Constants.KEY_KEYBOARD_SIZE_RATIO, inRange)

		repo.applySafeKeyboardDefaults()

		assertEquals(inRange, repo.getFloat(Constants.KEY_KEYBOARD_SIZE_RATIO, -1f), 0.0001f)
	}
}
