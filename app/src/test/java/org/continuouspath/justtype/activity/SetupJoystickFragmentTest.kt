package org.continuouspath.justtype.activity

import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEADZONE
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Tests for [SetupJoystickFragment].
 *
 * Verifies the fragment reads the stored joystick prefs into its sliders.
 * Persistence back through [SettingsRepository] is not yet covered.
 */
@RunWith(RobolectricTestRunner::class)
class SetupJoystickFragmentTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private val controllers = mutableListOf<ActivityController<SetupHostActivity>>()

	@Before
	fun setUp() {
		SettingsRegistry.getInstance(androidx.test.core.app.ApplicationProvider.getApplicationContext())
	}

	@After
	fun tearDown() {
		controllers.forEach { it.pause().stop().destroy() }
		controllers.clear()
		SettingsRepository.resetInstanceForTesting()
	}

	@Test
	fun `slider initial values reflect stored prefs`() {
		val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
		val repo = SettingsRepository.getInstance(ctx)
		repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, 1.0f)
		repo.putFloat(KEY_JOYSTICK_DEADZONE, 0.30f)
		repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.70f)

		val activity = launchHost()
		val view = activity.supportFragmentManager
			.findFragmentById(R.id.setup_host_container)
			?.view!!

		val cornerBiasValue = view.findViewById<TextView>(R.id.cornerBiasValue)
		val deadZoneValue = view.findViewById<TextView>(R.id.joystickDeadZoneValue)
		val activeZoneValue = view.findViewById<TextView>(R.id.joystickActiveZoneValue)

		assertThat(cornerBiasValue.text.toString().toFloat()).isWithin(0.01f).of(1.0f)
		assertThat(deadZoneValue.text.toString().toFloat()).isWithin(0.01f).of(0.30f)
		assertThat(activeZoneValue.text.toString().toFloat()).isWithin(0.01f).of(0.70f)
	}

	private fun launchHost(): SetupHostActivity {
		val intent = SetupHostActivity.intent(
			androidx.test.core.app.ApplicationProvider.getApplicationContext(),
			SetupHostActivity.TARGET_JOYSTICK,
		)
		val controller = Robolectric.buildActivity(SetupHostActivity::class.java, intent)
			.create()
			.start()
			.resume()
		controllers += controller
		val activity = controller.get()
		activity.supportFragmentManager.executePendingTransactions()
		return activity
	}
}
