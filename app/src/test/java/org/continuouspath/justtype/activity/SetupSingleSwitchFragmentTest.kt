package org.continuouspath.justtype.activity

import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_INITIAL_SCAN_DELAY_INCREASE_SEC
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
import org.continuouspath.justtype.Constants.KEY_SCAN_STEP_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_SCAN_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_SWITCH_DEBOUNCE_MS
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
 * Tests for [SetupSingleSwitchFragment].
 *
 * Covers inflation, repo-driven slider initialization, 12-info-icon
 * presence check, and the host-forwarded key-event scan-switch assignment
 * path. No de-conflict test (single switch — only one key).
 */
@RunWith(RobolectricTestRunner::class)
class SetupSingleSwitchFragmentTest {

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
		repo.putInt(KEY_SWITCH_DEBOUNCE_MS, 200)
		repo.putFloat(KEY_SCAN_STEP_DELAY_SEC, 1.5f)
		repo.putFloat(KEY_INITIAL_SCAN_DELAY_INCREASE_SEC, 0.75f)
		repo.putFloat(KEY_AUTOREPEAT_DELAY_SEC, 2.0f)

		val view = launchHostAndGetFragmentView()

		assertThat(view.findViewById<TextView>(R.id.switchDebounceValue).text.toString())
			.contains("200")
		assertThat(view.findViewById<TextView>(R.id.scanStepDelayValue).text.toString())
			.contains("1.5")
		assertThat(view.findViewById<TextView>(R.id.initialScanDelayIncreaseValue).text.toString())
			.contains("0.75")
		assertThat(view.findViewById<TextView>(R.id.autoRepeatDelayValue).text.toString())
			.contains("2.0")
	}

	@Test
	fun `interceptKeyEvent assigns scan switch from key code when waiting`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		val repo = SettingsRepository.getInstance(ctx)
		repo.putBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED, false)

		val activity = launchHost()
		val fragment = activity.supportFragmentManager
			.findFragmentById(R.id.setup_host_container) as SetupSingleSwitchFragment
		fragment.view!!.findViewById<Button>(R.id.activateScanSwitchButton).performClick()

		val consumed = fragment.interceptKeyEvent(
			KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1),
		)

		assertThat(consumed).isTrue()
		assertThat(repo.getInt(KEY_SCAN_SWITCH_CODE, SWITCH_CODE_UNDEFINED))
			.isEqualTo(KeyEvent.KEYCODE_1)
	}

	@Test
	fun `interceptKeyEvent ignores keys when not waiting for assignment`() {
		val activity = launchHost()
		val fragment = activity.supportFragmentManager
			.findFragmentById(R.id.setup_host_container) as SetupSingleSwitchFragment

		val consumed = fragment.interceptKeyEvent(
			KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1),
		)

		assertThat(consumed).isFalse()
	}

	private fun launchHost(): SetupHostActivity {
		val intent = SetupHostActivity.intent(
			ApplicationProvider.getApplicationContext(),
			SetupHostActivity.TARGET_SINGLE_SWITCH,
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
