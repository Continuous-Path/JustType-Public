package org.continuouspath.justtype.activity

import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_GREEN_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_RED_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
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
 * Tests for [SetupTwoSwitchFragment].
 *
 * Covers inflation, repo-driven slider initialization, info-icon presence,
 * and the host-forwarded key-event switch assignment path including the
 * de-conflict logic when the same key is assigned to both switches.
 */
@RunWith(RobolectricTestRunner::class)
class SetupTwoSwitchFragmentTest {

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
	fun `beep-on-switch toggle is disabled when master beep is off`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		SettingsRepository.getInstance(ctx).putBoolean(KEY_BEEP_KEY_FEEDBACK, false)

		val view = launchHostAndGetFragmentView()

		assertThat(view.findViewById<SwitchCompat>(R.id.twoSwitchBeepActivationSwitch).isEnabled).isFalse()
	}

	@Test
	fun `beep-on-switch toggle is enabled when master beep is on`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		SettingsRepository.getInstance(ctx).putBoolean(KEY_BEEP_KEY_FEEDBACK, true)

		val view = launchHostAndGetFragmentView()

		assertThat(view.findViewById<SwitchCompat>(R.id.twoSwitchBeepActivationSwitch).isEnabled).isTrue()
	}

	@Test
	fun `slider initial values reflect stored prefs`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		val repo = SettingsRepository.getInstance(ctx)
		repo.putInt(KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC, 30)
		repo.putFloat(KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC, 1.5f)
		repo.putFloat(KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC, 2.0f)

		val view = launchHostAndGetFragmentView()

		assertThat(view.findViewById<TextView>(R.id.highlightTimeoutValue).text.toString())
			.contains("30")
		assertThat(view.findViewById<TextView>(R.id.twoSwitchAutoRepeatDelayValue).text.toString())
			.contains("1.5")
		assertThat(view.findViewById<TextView>(R.id.twoSwitchRepeatActivationDelayValue).text.toString())
			.contains("2.0")
	}

	@Test
	fun `interceptKeyEvent assigns waiting RED switch from key code`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		val repo = SettingsRepository.getInstance(ctx)
		repo.putBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED, false)

		val activity = launchHost()
		val fragment = activity.supportFragmentManager
			.findFragmentById(R.id.setup_host_container) as SetupTwoSwitchFragment
		fragment.view!!.findViewById<Button>(R.id.activateRedButton).performClick()

		val consumed = fragment.interceptKeyEvent(
			KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1),
		)

		assertThat(consumed).isTrue()
		assertThat(repo.getInt(KEY_RED_SWITCH_CODE, SWITCH_CODE_UNDEFINED))
			.isEqualTo(KeyEvent.KEYCODE_1)
	}

	@Test
	fun `interceptKeyEvent de-conflicts when same key assigned to both`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		val repo = SettingsRepository.getInstance(ctx)
		repo.putBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED, false)
		repo.putInt(KEY_RED_SWITCH_CODE, KeyEvent.KEYCODE_1)
		repo.putInt(KEY_GREEN_SWITCH_CODE, SWITCH_CODE_UNDEFINED)

		val activity = launchHost()
		val fragment = activity.supportFragmentManager
			.findFragmentById(R.id.setup_host_container) as SetupTwoSwitchFragment
		fragment.view!!.findViewById<Button>(R.id.activateGreenButton).performClick()

		fragment.interceptKeyEvent(
			KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1),
		)

		assertThat(repo.getInt(KEY_GREEN_SWITCH_CODE, SWITCH_CODE_UNDEFINED))
			.isEqualTo(KeyEvent.KEYCODE_1)
		assertThat(repo.getInt(KEY_RED_SWITCH_CODE, SWITCH_CODE_UNDEFINED))
			.isEqualTo(SWITCH_CODE_UNDEFINED)
	}

	@Test
	fun `interceptKeyEvent ignores keys when not waiting for assignment`() {
		val activity = launchHost()
		val fragment = activity.supportFragmentManager
			.findFragmentById(R.id.setup_host_container) as SetupTwoSwitchFragment

		val consumed = fragment.interceptKeyEvent(
			KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1),
		)

		assertThat(consumed).isFalse()
	}

	private fun launchHost(): SetupHostActivity {
		val intent = SetupHostActivity.intent(
			ApplicationProvider.getApplicationContext(),
			SetupHostActivity.TARGET_TWO_SWITCH,
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
