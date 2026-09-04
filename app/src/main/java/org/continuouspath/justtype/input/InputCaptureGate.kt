package org.continuouspath.justtype.input

import android.os.SystemClock
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.settings.SettingsRepository

/**
 * Cross-process gate: while a setup screen captures input, live IME/Nav subsystems suppress
 * switch/joystick input so it isn't double-handled. Backed by a [SettingsRepository]
 * timestamp that auto-expires after [Constants.INPUT_CAPTURE_TIMEOUT_MS] so a dead setup
 * screen can't jam live input permanently.
 */
object InputCaptureGate {
	fun begin(repo: SettingsRepository) {
		repo.putLong(Constants.KEY_INPUT_CAPTURE_ACTIVE_AT_MS, SystemClock.elapsedRealtime())
	}

	/**
	 * Push the expiry forward (sliding window). Call on each event received while still
	 * waiting for a capture, so slow/assisted actuation doesn't hit the failsafe mid-capture.
	 */
	fun refresh(repo: SettingsRepository) = begin(repo)

	fun end(repo: SettingsRepository) {
		repo.putLong(Constants.KEY_INPUT_CAPTURE_ACTIVE_AT_MS, 0L)
	}

	/** True while a setup capture is in progress and not yet expired. */
	fun isActive(repo: SettingsRepository): Boolean {
		val at = repo.getLong(Constants.KEY_INPUT_CAPTURE_ACTIVE_AT_MS, 0L)
		if (at == 0L) return false
		val elapsed = SystemClock.elapsedRealtime() - at
		return elapsed in 0..Constants.INPUT_CAPTURE_TIMEOUT_MS
	}
}
