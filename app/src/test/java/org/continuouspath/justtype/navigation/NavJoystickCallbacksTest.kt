package org.continuouspath.justtype.navigation

import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NavJoystickCallbacksTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	@Before
	fun setUp() {
		val context = RuntimeEnvironment.getApplication()
		// activationFeedback() reads the beep/vibration settings, resolved via the registry.
		SettingsRegistry.getInstance(context)
		SettingsRepository.getInstance(context).clearForTesting()
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	private fun callbacks() = NavJoystickCallbacks(
		feedback = NavSubsystemFeedback(), // ToneGenerator init is null-safe in JVM tests
	)

	@Test
	fun `activation beep does not throw`() {
		callbacks().playActivationBeep()
	}

	@Test
	fun `debug log does not throw`() {
		callbacks().debugLog("hello")
	}
}
