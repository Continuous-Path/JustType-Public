package org.continuouspath.justtype.utils

import android.os.SystemClock
import android.util.Log
import org.continuouspath.justtype.BuildConfig

/**
 * Lightweight timing for the startup/typing hot paths — a cheap alternative to a benchmark module.
 * Logs elapsed wall-clock (ms) to the `PERF` tag; read with `adb logcat -s PERF`. No-op in release
 * (Tier 0) so end users pay nothing. Deliberately minimal: enough to answer "is anything slow?",
 * not a permanent measurement harness.
 */
object PerfTrace {

	/** Enabled in debug/beta/internal (tier > 0), off in release. */
	val enabled: Boolean = BuildConfig.DEBUG_TIER > 0

	/** Run [block], logging how long it took under [label]. Returns the block's result. */
	inline fun <T> measure(label: String, block: () -> T): T {
		if (!enabled) return block()
		val start = SystemClock.elapsedRealtime()
		try {
			return block()
		} finally {
			Log.i(TAG, "$label: ${SystemClock.elapsedRealtime() - start}ms")
		}
	}

	/** Log a completed duration measured elsewhere (e.g. across a coroutine boundary). */
	fun log(label: String, elapsedMs: Long) {
		if (enabled) Log.i(TAG, "$label: ${elapsedMs}ms")
	}

	/** Wall-clock timestamp for spans that start and end in different scopes. */
	fun now(): Long = SystemClock.elapsedRealtime()

	const val TAG = "PERF"
}
