package org.continuouspath.justtype.activity

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Tests for [SetupHostActivity].
 */
@RunWith(RobolectricTestRunner::class)
class SetupHostActivityTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private val controllers = mutableListOf<ActivityController<SetupHostActivity>>()

	@Before
	fun setUp() {
		// The setup fragments read from the registry; init it so this class doesn't depend on
		// another test having done so first (the SettingsRegistry-not-initialized ordering flake).
		SettingsRegistry.getInstance(ApplicationProvider.getApplicationContext())
	}

	@After
	fun tearDown() {
		controllers.forEach { it.pause().stop().destroy() }
		controllers.clear()
	}

	// ── intent factory ────────────────────────────────────────────────────

	@Test
	fun `intent factory sets target extra and component`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		for (target in ALL_TARGETS) {
			val intent = SetupHostActivity.intent(ctx, target)
			assertThat(intent.component?.className)
				.isEqualTo(SetupHostActivity::class.java.name)
			assertThat(intent.getStringExtra(SetupHostActivity.EXTRA_SETUP_TARGET))
				.isEqualTo(target)
		}
	}

	// ── fragment-mode routing ─────────────────────────────────────────────

	@Test
	fun `routes TARGET_JOYSTICK to SetupJoystickFragment`() {
		val activity = launchWith(SetupHostActivity.TARGET_JOYSTICK)
		activity.supportFragmentManager.executePendingTransactions()
		assertThat(activity.isFinishing).isFalse()
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
		val fragment = activity.supportFragmentManager.findFragmentById(R.id.setup_host_container)
		assertThat(fragment).isInstanceOf(SetupJoystickFragment::class.java)
	}

	@Test
	fun `routes TARGET_TOUCH_SCREEN_SWITCH to SetupTouchScreenSwitchFragment`() {
		val activity = launchWith(SetupHostActivity.TARGET_TOUCH_SCREEN_SWITCH)
		activity.supportFragmentManager.executePendingTransactions()
		assertThat(activity.isFinishing).isFalse()
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
		val fragment = activity.supportFragmentManager.findFragmentById(R.id.setup_host_container)
		assertThat(fragment).isInstanceOf(SetupTouchScreenSwitchFragment::class.java)
	}

	@Test
	fun `routes TARGET_TWO_SWITCH to SetupTwoSwitchFragment`() {
		val activity = launchWith(SetupHostActivity.TARGET_TWO_SWITCH)
		activity.supportFragmentManager.executePendingTransactions()
		assertThat(activity.isFinishing).isFalse()
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
		val fragment = activity.supportFragmentManager.findFragmentById(R.id.setup_host_container)
		assertThat(fragment).isInstanceOf(SetupTwoSwitchFragment::class.java)
	}

	@Test
	fun `routes TARGET_SINGLE_SWITCH to SetupSingleSwitchFragment`() {
		val activity = launchWith(SetupHostActivity.TARGET_SINGLE_SWITCH)
		activity.supportFragmentManager.executePendingTransactions()
		assertThat(activity.isFinishing).isFalse()
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
		val fragment = activity.supportFragmentManager.findFragmentById(R.id.setup_host_container)
		assertThat(fragment).isInstanceOf(SetupSingleSwitchFragment::class.java)
	}

	@Test
	fun `routes TARGET_HEAD_TRACKING to SetupHeadTrackingFragment`() {
		val activity = launchWith(SetupHostActivity.TARGET_HEAD_TRACKING)
		activity.supportFragmentManager.executePendingTransactions()
		assertThat(activity.isFinishing).isFalse()
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
		val fragment = activity.supportFragmentManager.findFragmentById(R.id.setup_host_container)
		assertThat(fragment).isInstanceOf(SetupHeadTrackingFragment::class.java)
	}

	// ── shim routing ──────────────────────────────────────────────────────

	@Test
	fun `routes TARGET_DIRECTIONAL_SELECTION to SetupDirectionalSelectionActivity`() {
		assertShimRoutes(SetupHostActivity.TARGET_DIRECTIONAL_SELECTION, SetupDirectionalSelectionActivity::class.java)
	}

	@Test
	fun `routes TARGET_DIRECT_SELECTION to SetupDirectSelectionActivity`() {
		assertShimRoutes(SetupHostActivity.TARGET_DIRECT_SELECTION, SetupDirectSelectionActivity::class.java)
	}

	@Test
	fun `host finishes itself after shim routing for every shim target`() {
		for (target in SHIM_TARGETS) {
			val activity = launchWith(target)
			assertThat(activity.isFinishing).isTrue()
		}
	}

	// ── defensive cases ───────────────────────────────────────────────────

	@Test
	fun `missing target extra finishes without launching anything`() {
		val intent = Intent(
			ApplicationProvider.getApplicationContext(),
			SetupHostActivity::class.java,
		)
		val controller = Robolectric.buildActivity(SetupHostActivity::class.java, intent).create()
		controllers += controller
		val activity = controller.get()
		assertThat(activity.isFinishing).isTrue()
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
	}

	// ── dispatchKeyEvent → KeyEventInterceptor forwarding ─────────────────

	@Test
	fun `dispatchKeyEvent forwards to KeyEventInterceptor fragment when consumed`() {
		val activity = launchWith(SetupHostActivity.TARGET_JOYSTICK)
		activity.supportFragmentManager.executePendingTransactions()
		// Replace the routed fragment with a controllable interceptor stub.
		val stub = ConsumingInterceptorFragment()
		activity.supportFragmentManager.beginTransaction()
			.replace(R.id.setup_host_container, stub)
			.commitNow()

		val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_1)
		val result = activity.dispatchKeyEvent(event)

		assertThat(result).isTrue()
		assertThat(stub.received).isEqualTo(event)
	}

	@Test
	fun `dispatchKeyEvent falls through when interceptor declines`() {
		val activity = launchWith(SetupHostActivity.TARGET_JOYSTICK)
		activity.supportFragmentManager.executePendingTransactions()
		val stub = DecliningInterceptorFragment()
		activity.supportFragmentManager.beginTransaction()
			.replace(R.id.setup_host_container, stub)
			.commitNow()

		val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_1)
		// Falls through to super; super's return value depends on the activity's
		// state. We don't care what super returns — we care that the interceptor
		// was consulted. The stub records that.
		activity.dispatchKeyEvent(event)
		assertThat(stub.received).isEqualTo(event)
	}

	@Test
	fun `unknown target extra finishes without launching anything`() {
		val intent = Intent(
			ApplicationProvider.getApplicationContext(),
			SetupHostActivity::class.java,
		).putExtra(SetupHostActivity.EXTRA_SETUP_TARGET, "no_such_target")
		val controller = Robolectric.buildActivity(SetupHostActivity::class.java, intent).create()
		controllers += controller
		val activity = controller.get()
		assertThat(activity.isFinishing).isTrue()
		assertThat(shadowOf(activity).nextStartedActivity).isNull()
	}

	// ── helpers ───────────────────────────────────────────────────────────

	private fun assertShimRoutes(target: String, expected: Class<*>) {
		val activity = launchWith(target)
		val started = shadowOf(activity).nextStartedActivity
		assertThat(started).isNotNull()
		assertThat(started.component?.className).isEqualTo(expected.name)
	}

	private fun launchWith(target: String): SetupHostActivity {
		val intent = SetupHostActivity.intent(
			ApplicationProvider.getApplicationContext(),
			target,
		)
		val controller = Robolectric.buildActivity(SetupHostActivity::class.java, intent).create()
		controllers += controller
		return controller.get()
	}

	companion object {
		private val FRAGMENT_TARGETS = listOf(
			SetupHostActivity.TARGET_JOYSTICK,
			SetupHostActivity.TARGET_TOUCH_SCREEN_SWITCH,
			SetupHostActivity.TARGET_TWO_SWITCH,
			SetupHostActivity.TARGET_SINGLE_SWITCH,
			SetupHostActivity.TARGET_HEAD_TRACKING,
		)

		private val SHIM_TARGETS = listOf(
			SetupHostActivity.TARGET_DIRECTIONAL_SELECTION,
			SetupHostActivity.TARGET_DIRECT_SELECTION,
		)

		private val ALL_TARGETS = FRAGMENT_TARGETS + SHIM_TARGETS
	}

	/**
	 * Records the last received key event and consumes it. Used to verify
	 * SetupHostActivity.dispatchKeyEvent forwards events to the active
	 * KeyEventInterceptor fragment.
	 */
	class ConsumingInterceptorFragment :
		androidx.fragment.app.Fragment(),
		KeyEventInterceptor {
		var received: android.view.KeyEvent? = null
		override fun interceptKeyEvent(event: android.view.KeyEvent): Boolean {
			received = event
			return true
		}
	}

	/** Like [ConsumingInterceptorFragment] but returns false to test the fall-through path. */
	class DecliningInterceptorFragment :
		androidx.fragment.app.Fragment(),
		KeyEventInterceptor {
		var received: android.view.KeyEvent? = null
		override fun interceptKeyEvent(event: android.view.KeyEvent): Boolean {
			received = event
			return false
		}
	}
}
