package org.continuouspath.justtype.settings

import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger

/**
 * Shared crash-LOOP recovery: forces a safe, operable configuration only after repeated crashes
 * within a short window, so a single transient crash never wipes a good setup. Called from both
 * the IME ([org.continuouspath.justtype.ime.StartupManager]) and the Nav accessibility service on their
 * respective startups. The window counter is process-wide (any component's crash counts) because a
 * crash kills the shared process regardless of which component threw.
 */
object CrashLoopRecovery {

	const val CRASH_LOOP_THRESHOLD = 3
	const val CRASH_LOOP_WINDOW_MS = 60_000L

	/**
	 * Record the crash at [crashTime] and, if it's the [CRASH_LOOP_THRESHOLD]th within
	 * [CRASH_LOOP_WINDOW_MS], run [onLoopDetected] (the caller's component-specific safe-mode
	 * actions) then reset the window. A crash outside the window starts a fresh burst.
	 * @return true if a loop was detected and recovery ran.
	 */
	fun record(repo: SettingsRepository, crashTime: Long, onLoopDetected: () -> Unit): Boolean {
		val windowStart = repo.getLong(Constants.KEY_CRASH_RECOVERY_WINDOW_START, 0L)
		val withinWindow = windowStart > 0L && crashTime - windowStart <= CRASH_LOOP_WINDOW_MS
		val count = if (withinWindow) repo.getInt(Constants.KEY_CRASH_RECOVERY_COUNT, 0) + 1 else 1
		if (count >= CRASH_LOOP_THRESHOLD) {
			onLoopDetected()
			repo.edit()
				.putInt(Constants.KEY_CRASH_RECOVERY_COUNT, 0)
				.putLong(Constants.KEY_CRASH_RECOVERY_WINDOW_START, 0L)
				.apply()
			return true
		}
		repo.edit()
			.putInt(Constants.KEY_CRASH_RECOVERY_COUNT, count)
			.putLong(Constants.KEY_CRASH_RECOVERY_WINDOW_START, if (count == 1) crashTime else windowStart)
			.apply()
		return false
	}

	/** IME safe mode: Direct Selection fallback + re-coerce an out-of-range keyboard size. */
	fun imeSafeMode(repo: SettingsRepository) {
		DebugLogger.log(DebugCategory.Lifecycle) { "[CrashLoopRecovery] IME crash loop — forcing Direct Selection safe mode" }
		repo.forceDirectSelectionFallback()
		repo.applySafeKeyboardDefaults()
	}

	/**
	 * Nav safe mode: stop auto-showing the overlay that keeps crashing the service (it stays
	 * enabled; the user can re-open Nav deliberately), and force Direct Selection so there is
	 * always a working way to type while Nav is quieted.
	 */
	fun navSafeMode(repo: SettingsRepository) {
		DebugLogger.log(DebugCategory.Lifecycle) { "[CrashLoopRecovery] Nav crash loop — quieting overlay + Direct Selection fallback" }
		repo.putBoolean(Constants.KEY_NAVIGATION_OVERLAY_REQUESTED, false)
		repo.forceDirectSelectionFallback()
	}
}
