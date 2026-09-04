package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.navigation.engine.NavSettingsRouter.Effect
import org.junit.Test

/** Drift guard: every settings key the Nav service reacts to, pinned to its effect. */
class NavSettingsRouterTest {

	@Test
	fun `capture toggle reconciles motion sources`() {
		assertThat(NavSettingsRouter.classify(Constants.KEY_INPUT_CAPTURE_ACTIVE_AT_MS))
			.isEqualTo(Effect.RECONCILE_MOTION)
	}

	@Test
	fun `dev scroll-log toggle reloads the flag`() {
		assertThat(NavSettingsRouter.classify(Constants.KEY_DEV_SCROLL_LOGS)).isEqualTo(Effect.RELOAD_SCROLL_LOGS)
	}

	@Test
	@Suppress("DEPRECATION") // KEY_INPUT_METHOD is effectiveInputMethod()'s legacy fallback.
	fun `input-method, joystick, and switch-code keys rebuild the input layer`() {
		val keys = listOf(
			Constants.KEY_DEV_FORCE_PRE34_JOYSTICK,
			Constants.KEY_INPUT_METHOD_PRIMARY,
			Constants.KEY_INPUT_METHOD,
			Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED,
			Constants.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED,
			Constants.KEY_NAV_TOUCH_FULLSCREEN_SWIPE,
			Constants.KEY_SCAN_SWITCH_CODE,
			Constants.KEY_RED_SWITCH_CODE,
			Constants.KEY_GREEN_SWITCH_CODE,
			Constants.KEY_JOYSTICK_DEVICE_DESCRIPTOR,
			Constants.KEY_JOYSTICK_ACCEPT_ANY,
			Constants.KEY_JOYSTICK_DEADZONE,
			Constants.KEY_JOYSTICK_ACTIVEZONE,
			Constants.KEY_JOYSTICK_CORNER_BIAS,
		)
		for (key in keys) {
			assertThat(NavSettingsRouter.classify(key)).isEqualTo(Effect.REBUILD_INPUT)
		}
	}

	@Test
	fun `scan behavior keys reload the scan subsystem live`() {
		val keys = listOf(
			Constants.KEY_SCAN_STEP_DELAY_SEC,
			Constants.KEY_INITIAL_SCAN_DELAY_INCREASE_SEC,
			Constants.KEY_SCAN_REPEAT_COUNT,
			Constants.KEY_SKIP_KEYS_NO_VALID,
			Constants.KEY_SHOW_NEXT_KEY,
			Constants.KEY_SELECT_KEY_TRIGGERS_SCAN,
			Constants.KEY_AUTOREPEAT_MODE,
			Constants.KEY_AUTOREPEAT_DELAY_SEC,
			Constants.KEY_BEEP_EACH_SCAN_STEP,
		)
		for (key in keys) {
			assertThat(NavSettingsRouter.classify(key)).isEqualTo(Effect.RELOAD_SCAN)
		}
	}

	@Test
	fun `two-switch behavior keys reload the two-switch subsystem live`() {
		val keys = listOf(
			Constants.KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC,
			Constants.KEY_TWO_SWITCH_SHOW_BAND,
			Constants.KEY_TWO_SWITCH_AUTOREPEAT_MODE,
			Constants.KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC,
			Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATIONS,
			Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC,
			Constants.KEY_TWO_SWITCH_BEEP_ACTIVATION,
			Constants.KEY_BEEP_KEY_FEEDBACK,
			Constants.KEY_FLASH_KEY_FEEDBACK,
		)
		for (key in keys) {
			assertThat(NavSettingsRouter.classify(key)).isEqualTo(Effect.RELOAD_TWO_SWITCH)
		}
	}

	@Test
	fun `appearance keys re-apply live without a rebuild`() {
		val keys = listOf(
			Constants.KEY_NAV_THEME,
			Constants.KEY_NAV_KEY_OPACITY_PERCENT,
			Constants.KEY_NAV_PANEL_OPACITY_PERCENT,
			Constants.KEY_NAV_SIZE_PERCENT,
			Constants.KEY_NAV_TRANSPARENCY_MODE,
			Constants.KEY_NAV_HIDE_DRAG_HANDLE,
		)
		for (key in keys) {
			assertThat(NavSettingsRouter.classify(key)).isEqualTo(Effect.REAPPLY_APPEARANCE)
		}
	}

	@Test
	fun `unknown and null keys are ignored`() {
		assertThat(NavSettingsRouter.classify("some_unrelated_key")).isEqualTo(Effect.NONE)
		assertThat(NavSettingsRouter.classify(null)).isEqualTo(Effect.NONE)
	}
}
