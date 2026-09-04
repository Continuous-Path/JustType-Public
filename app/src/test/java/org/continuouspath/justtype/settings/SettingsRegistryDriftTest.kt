package org.continuouspath.justtype.settings

import android.content.Context
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class SettingsRegistryDriftTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private lateinit var context: Context
	private lateinit var repo: SettingsRepository
	private lateinit var registry: SettingsRegistry
	private lateinit var scope: CoroutineScope
	private var activityController: ActivityController<AppCompatActivity>? = null

	@Before
	fun setUp() {
		activityController = Robolectric.buildActivity(AppCompatActivity::class.java).create()
		context = activityController!!.get()
		val appContext = ApplicationProvider.getApplicationContext<Context>()
		repo = SettingsRepository.getInstance(appContext)
		repo.clearForTesting()
		registry = SettingsRegistry.getInstance(appContext)
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	}

	@After
	fun tearDown() {
		scope.coroutineContext[Job]?.cancel()
		activityController?.pause()?.stop()?.destroy()
		activityController = null
		SettingsRepository.resetInstanceForTesting()
	}

	@Test
	fun `every registry page renders every key without throwing`() {
		val renderer = SettingsRenderer(context = context, repo = repo, scope = scope)
		assertThat(registry.pages).isNotEmpty()
		for ((pageId, items) in registry.pages) {
			val container = LinearLayout(context)
			val result = try {
				renderer.renderPage(items, container)
			} catch (e: Throwable) {
				throw AssertionError("renderPage failed for page=$pageId", e)
			}
			assertThat(result.viewByKey.keys)
				.containsExactlyElementsIn(items.map { it.key }.toSet())
		}
	}

	@Test
	fun `core pages stay registered`() {
		// One-directional: additions never break this; a dropped page does.
		assertThat(registry.pages.keys).containsAtLeast(
			"main",
			"input_methods",
			"head_tracking",
			"joystick",
			"mouse_joystick",
			"single_switch",
			"two_switch",
			"touch_switch",
			"direct_selection",
			"directional_selection",
			"vocabulary",
			"navigation_mode",
			"backup_info",
			"developer",
		)
	}

	// Both-surfaces parity contract (docs/settings-parity.md): these keys are
	// exposed in BOTH the keyboard Settings Mode (this registry) and the touch
	// UI's hand-built screens. Removing one from the registry silently breaks
	// the keyboard side — this makes it loud. The touch side has no automated
	// mirror; run the audit recipe in docs/settings-parity.md when editing the
	// setup fragments/activities.
	@Test
	fun `parity-contract keys stay in the registry`() {
		val c = org.continuouspath.justtype.Constants
		val allKeys = registry.pages.values.flatten().map { it.key }.toSet()
		assertThat(allKeys).containsAtLeast(
			c.KEY_HEADTRACKING_CORRECTION_BEEP,
			c.KEY_HEADTRACKING_CORRECTION_FLASH_RED,
			c.KEY_JOYSTICK_ACCEPT_ANY,
			c.KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC,
			c.KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER,
			c.KEY_DIRECTIONAL_SHOW_REGION_BORDER,
			c.KEY_TURKISH_AZERI_CASE_OVERRIDE,
			c.KEY_NAV_LIVE_DRAG,
			c.KEY_NAV_THEME,
			c.KEY_NAV_KEY_OPACITY_PERCENT,
			c.KEY_NAV_PANEL_OPACITY_PERCENT,
			c.KEY_NAV_SIZE_PERCENT,
			c.KEY_NAV_TRANSPARENCY_MODE,
			c.KEY_NAV_HIDE_DRAG_HANDLE,
		)
	}

	@Test
	fun `no page is empty`() {
		// Guards against a page accidentally losing all of its keys, without
		// mirroring the exact page list (which broke on every intentional
		// page addition).
		assertThat(registry.pages).isNotEmpty()
		for ((_, items) in registry.pages) {
			assertThat(items).isNotEmpty()
		}
	}
}
