package org.continuouspath.justtype.ime

import android.util.Log

/**
 * Lightweight, gated diagnostic logger for head-tracking latency
 * investigation. Toggle-able via the head-tracking "Debug Overlay"
 * preference (mirrored into [enabled] by HeadTrackingSubsystem.loadCachedPrefs).
 *
 * Filter logcat with: `adb logcat -s HT_TIMING`
 *
 * The [log] helper is `inline`, so when [enabled] is false the lambda
 * is never evaluated and no string is built — zero allocation cost.
 */
object HtTimingDiagnostics {
	const val TAG = "HT_TIMING"

	/**
	 * Minimum dt (ms) between consecutive applyUiUpdate calls before a
	 * GAP line is emitted. Set above the natural inter-frame interval
	 * even at degraded frame rates so we only see actual stalls, not
	 * normal cadence.
	 */
	const val GAP_THRESHOLD_MS = 150L

	@Volatile var enabled: Boolean = false

	inline fun log(message: () -> String) {
		if (enabled) Log.d(TAG, message())
	}
}
