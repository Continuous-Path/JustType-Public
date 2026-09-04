package org.continuouspath.justtype.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegistryAwareRepoTest {
	private lateinit var context: Context
	private lateinit var registry: SettingsRegistry
	private lateinit var repo: SettingsRepository

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		registry = SettingsRegistry.getInstance(context)
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
		SettingsRegistry.resetInstanceForTesting()
	}

	@Test
	fun `getBoolean(key) returns registry default when no value stored`() {
		val toggles = registry.pages.values.flatten().filterIsInstance<SettingsDef.Toggle>()
		assertThat(toggles).isNotEmpty()
		for (def in toggles) {
			assertThat(repo.getBoolean(def.key)).isEqualTo(def.defaultValue)
		}
	}

	@Test
	fun `getBoolean(key) returns stored value when present`() {
		val def = registry.pages.values.flatten().filterIsInstance<SettingsDef.Toggle>().first()
		val opposite = !def.defaultValue
		repo.putBoolean(def.key, opposite)
		assertThat(repo.getBoolean(def.key)).isEqualTo(opposite)
	}

	@Test
	fun `getInt(key) returns registry default for every IntSlider`() {
		val sliders = registry.pages.values.flatten().filterIsInstance<SettingsDef.IntSlider>()
		assertThat(sliders).isNotEmpty()
		for (def in sliders) {
			assertThat(repo.getInt(def.key)).isEqualTo(def.defaultValue)
		}
	}

	@Test
	fun `getFloat(key) returns registry default for every FloatSlider`() {
		val sliders = registry.pages.values.flatten().filterIsInstance<SettingsDef.FloatSlider>()
		assertThat(sliders).isNotEmpty()
		for (def in sliders) {
			assertThat(repo.getFloat(def.key)).isEqualTo(def.defaultValue)
		}
	}

	@Test
	fun `getString(key) returns registry default for every Choice`() {
		val choices = registry.pages.values.flatten().filterIsInstance<SettingsDef.Choice>()
		assertThat(choices).isNotEmpty()
		for (def in choices) {
			assertThat(repo.getString(def.key)).isEqualTo(def.defaultValue)
		}
	}

	@Test
	fun `getBoolean(key) throws for unregistered key`() {
		val ex = assertThrows(IllegalStateException::class.java) {
			repo.getBoolean("nonexistent_internal_flag_key_xyz")
		}
		assertThat(ex.message).contains("not registered")
	}

	@Test
	fun `getBoolean(key) throws when key is registered as wrong type`() {
		// Pick a Choice key and try to read it as Boolean.
		val choice = registry.pages.values.flatten().filterIsInstance<SettingsDef.Choice>().first()
		val ex = assertThrows(IllegalArgumentException::class.java) {
			repo.getBoolean(choice.key)
		}
		assertThat(ex.message).contains("not Toggle")
	}
}
