package org.continuouspath.justtype.ime

import android.view.InputDevice
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACCEPT_ANY
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEADZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEVICE_DESCRIPTOR
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GamepadParamsTest {

	private lateinit var repo: SettingsRepository

	@Before
	fun setUp() {
		val app = RuntimeEnvironment.getApplication()
		SettingsRegistry.getInstance(app)
		repo = SettingsRepository.getInstance(app)
		repo.clearForTesting()
	}

	@After
	fun tearDown() {
		// Leave SettingsRegistry set: other tests in the JVM assume it stays initialized.
		SettingsRepository.resetInstanceForTesting()
	}

	@Test
	fun `corner bias maps to complementary sector widths summing to 90`() {
		repo.putFloat(KEY_JOYSTICK_DEADZONE, 0.25f)
		repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.60f)
		repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, 1.0f)

		val p = GamepadParams.fromSettings(repo)

		// bias 1.0 → cardinal = 90/2 = 45, diagonal = 45
		assertThat(p.cardinalWidthDeg).isWithin(0.01f).of(45f)
		assertThat(p.diagonalWidthDeg).isWithin(0.01f).of(45f)
		assertThat(p.cardinalWidthDeg + p.diagonalWidthDeg).isWithin(0.01f).of(90f)
	}

	@Test
	fun `active zone is clamped above deadzone`() {
		repo.putFloat(KEY_JOYSTICK_DEADZONE, 0.50f)
		repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.40f) // below deadzone
		repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, 1.0f)

		val p = GamepadParams.fromSettings(repo)

		assertThat(p.activeZone).isGreaterThan(p.deadZone)
	}

	@Test
	fun `corner bias is clamped to its valid range`() {
		repo.putFloat(KEY_JOYSTICK_DEADZONE, 0.25f)
		repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.60f)
		repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, 5.0f) // above the 2.0 cap

		val p = GamepadParams.fromSettings(repo)

		// clamped to 2.0 → cardinal = 90/3 = 30, diagonal = 60
		assertThat(p.cardinalWidthDeg).isWithin(0.01f).of(30f)
		assertThat(p.diagonalWidthDeg).isWithin(0.01f).of(60f)
	}

	@Test
	fun `legacy corner bias migrates to the joystick key`() {
		repo.putFloat(KEY_JOYSTICK_DEADZONE, 0.25f)
		repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.60f)
		repo.putFloat(KEY_CORNER_BIAS, 1.5f) // legacy only; joystick key absent

		GamepadParams.fromSettings(repo)

		assertThat(repo.getFloat(KEY_JOYSTICK_CORNER_BIAS, 0f)).isEqualTo(1.5f)
	}

	@Test
	fun `device filter accepts any device when accept-any is on`() {
		repo.putBoolean(KEY_JOYSTICK_ACCEPT_ANY, true)
		repo.putString(KEY_JOYSTICK_DEVICE_DESCRIPTOR, "some-descriptor")

		// accept-any short-circuits before touching the device, so a null device is safe here.
		val filter = GamepadParams.deviceFilterFromSettings(repo)

		assertThat(filter(deviceWithDescriptor(null))).isTrue()
		assertThat(filter(deviceWithDescriptor("anything"))).isTrue()
	}

	@Test
	fun `device filter accepts any device when no device is bound`() {
		repo.putBoolean(KEY_JOYSTICK_ACCEPT_ANY, false)
		repo.putString(KEY_JOYSTICK_DEVICE_DESCRIPTOR, "")

		val filter = GamepadParams.deviceFilterFromSettings(repo)

		assertThat(filter(deviceWithDescriptor("anything"))).isTrue()
	}

	// Accept-all branches short-circuit before reading the device, so the unstubbed mock is safe.
	private fun deviceWithDescriptor(@Suppress("UNUSED_PARAMETER") descriptor: String?): InputDevice = org.mockito.kotlin.mock()
}
