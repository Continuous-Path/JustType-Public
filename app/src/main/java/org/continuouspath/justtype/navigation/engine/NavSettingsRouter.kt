package org.continuouspath.justtype.navigation.engine

import org.continuouspath.justtype.Constants

/**
 * Classifies a changed settings key into the effect the Nav service must apply.
 * The key sets are the routing spec (drift-guarded by test); the service supplies the reactions.
 */
object NavSettingsRouter {

	enum class Effect {
		/** A setup screen toggled input capture — release/reclaim joystick motion sources. */
		RECONCILE_MOTION,

		/** Dev scroll-log toggle — re-read the flag. */
		RELOAD_SCROLL_LOGS,

		/** Input method / joystick params / switch binds changed — rebuild the input layer in place. */
		REBUILD_INPUT,

		/** Scan behavior tweak — re-applied live to a built subsystem via loadSettings (no rebuild). */
		RELOAD_SCAN,

		/** Two-switch behavior tweak — re-applied live via loadSettings (no rebuild). */
		RELOAD_TWO_SWITCH,

		/** Appearance change (incl. theme) — re-tint and re-render, no overlay rebuild. */
		REAPPLY_APPEARANCE,
		NONE,
	}

	fun classify(key: String?): Effect = when (key) {
		Constants.KEY_INPUT_CAPTURE_ACTIVE_AT_MS -> Effect.RECONCILE_MOTION
		Constants.KEY_DEV_SCROLL_LOGS -> Effect.RELOAD_SCROLL_LOGS
		Constants.KEY_DEV_FORCE_PRE34_JOYSTICK, in INPUT_METHOD_KEYS, in JOYSTICK_KEYS, in SWITCH_CODE_KEYS ->
			Effect.REBUILD_INPUT
		in SCAN_BEHAVIOR_KEYS -> Effect.RELOAD_SCAN
		in TWO_SWITCH_BEHAVIOR_KEYS -> Effect.RELOAD_TWO_SWITCH
		in APPEARANCE_KEYS -> Effect.REAPPLY_APPEARANCE
		else -> Effect.NONE
	}

	/** Setting keys that change which input subsystem the overlay should run. */
	@Suppress("DEPRECATION") // KEY_INPUT_METHOD is effectiveInputMethod()'s legacy fallback.
	private val INPUT_METHOD_KEYS = setOf(
		Constants.KEY_INPUT_METHOD_PRIMARY,
		Constants.KEY_INPUT_METHOD,
		Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED,
		Constants.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED,
		Constants.KEY_NAV_TOUCH_FULLSCREEN_SWIPE,
	)

	// Switch-code binds change whether a subsystem can exist (two-switch skips building with
	// no codes and no touch input) → rebuild the input layer.
	private val SWITCH_CODE_KEYS = setOf(
		Constants.KEY_SCAN_SWITCH_CODE,
		Constants.KEY_RED_SWITCH_CODE,
		Constants.KEY_GREEN_SWITCH_CODE,
	)

	private val SCAN_BEHAVIOR_KEYS = setOf(
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

	private val TWO_SWITCH_BEHAVIOR_KEYS = setOf(
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

	// Changing any rebuilds the input layer so the gamepad detector picks up new params live.
	private val JOYSTICK_KEYS = setOf(
		Constants.KEY_JOYSTICK_DEVICE_DESCRIPTOR,
		Constants.KEY_JOYSTICK_ACCEPT_ANY,
		Constants.KEY_JOYSTICK_DEADZONE,
		Constants.KEY_JOYSTICK_ACTIVEZONE,
		Constants.KEY_JOYSTICK_CORNER_BIAS,
	)

	private val APPEARANCE_KEYS = setOf(
		Constants.KEY_NAV_THEME,
		Constants.KEY_NAV_KEY_OPACITY_PERCENT,
		Constants.KEY_NAV_PANEL_OPACITY_PERCENT,
		Constants.KEY_NAV_SIZE_PERCENT,
		Constants.KEY_NAV_TRANSPARENCY_MODE,
		Constants.KEY_NAV_HIDE_DRAG_HANDLE,
	)
}
