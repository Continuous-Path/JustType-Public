package org.continuouspath.justtype.activity

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_TOUCH_OVERLAY_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_TSS_BUTTON_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_OPACITY
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
 * Tests for [SetupTouchScreenSwitchFragment].
 *
 * Mirrors [SetupJoystickFragmentTest]: initial-value tests driven through
 * [SetupHostActivity].
 */
@RunWith(RobolectricTestRunner::class)
class SetupTouchScreenSwitchFragmentTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private val controllers = mutableListOf<ActivityController<SetupHostActivity>>()

	@Before
	fun setUp() {
		SettingsRegistry.getInstance(ApplicationProvider.getApplicationContext())
	}

	@After
	fun tearDown() {
		controllers.forEach { it.pause().stop().destroy() }
		controllers.clear()
		SettingsRepository.resetInstanceForTesting()
	}

	@Test
	fun `slider initial values reflect stored prefs`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		val repo = SettingsRepository.getInstance(ctx)
		repo.putInt(KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS, 200)
		repo.putInt(KEY_TSS_BUTTON_HEIGHT_PERCENT, 25)
		repo.putInt(KEY_TSS_OVERLAY_OPACITY, 75)
		repo.putInt(KEY_TOUCH_OVERLAY_TIMEOUT_SEC, 6)

		val view = launchHostAndGetFragmentView()

		assertThat(view.findViewById<TextView>(R.id.debounceValue).text.toString())
			.contains("200")
		assertThat(view.findViewById<TextView>(R.id.buttonHeightValue).text.toString())
			.contains("25")
		assertThat(view.findViewById<TextView>(R.id.overlayOpacityValue).text.toString())
			.contains("75")
		assertThat(view.findViewById<TextView>(R.id.touchTimeoutValue).text.toString())
			.contains("6")
	}

	private fun launchHost(): SetupHostActivity {
		val intent = SetupHostActivity.intent(
			ApplicationProvider.getApplicationContext(),
			SetupHostActivity.TARGET_TOUCH_SCREEN_SWITCH,
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

	private fun launchHostAndGetFragmentView(): View = launchHost().supportFragmentManager
		.findFragmentById(R.id.setup_host_container)
		?.view!!
}
