package org.continuouspath.justtype.ime

import org.continuouspath.justtype.settings.SettingsRepository

/**
 * Shared contract for input method subsystems (Joystick, Scan, TwoSwitch, HeadTracking).
 *
 * The interface is intentionally minimal — only methods that genuinely unify across
 * all four subsystems live here. Method-specific entry points (e.g. `Scan.startScan`,
 * `TwoSwitch.onViewsReady`, `HeadTracking.notifyHeadBoardOfTrackingState`) stay on
 * the concrete class.
 *
 * `ExternalSwitchHandler` is **not** an `InputMethod` — it is a sibling event router
 * that dispatches gamepad/external-key events into one of the four input methods.
 */
interface InputMethod {
	/** Re-read settings from the repo and apply them. Idempotent; safe to call repeatedly. */
	fun loadSettings(repo: SettingsRepository)

	/**
	 * Stop any in-flight activation and clear any visible highlight, but leave settings
	 * loaded and the subsystem ready to receive new input.
	 */
	fun cancelAndClear()

	/** Tear down: cancel coroutines, release resources, unregister listeners. */
	fun destroy()
}
