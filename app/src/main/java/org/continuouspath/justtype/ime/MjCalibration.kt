package org.continuouspath.justtype.ime

import kotlin.math.hypot

/**
 * Pure derivation math for the mouse-joystick calibration wizard: turns raw motion samples into a
 * dead zone and a sensitivity, with no Android dependencies so it's directly testable.
 *
 * Two measurement phases feed samples as (dx, dy, dtMs) — raw pixel deltas differenced from absolute
 * rawX/rawY, with the event-time gap. Working in px/sec (dx/dt) makes the result independent of the
 * display's event rate (60 vs 120 Hz), which is the whole point of measuring rather than guessing.
 *
 * - Resting phase ("let go"): the p95 of per-sample speed is the stick's idle jitter floor.
 * - Push phase ("push all the way"): the peak sustained speed is full deflection.
 *
 * Sensitivity = the push peak (so a full push maps to a normalized magnitude near 1). Dead zone
 * scales with how much the idle jitter is relative to that peak — a twitchy stick needs a wider
 * resting zone so it doesn't drift into a key on its own.
 */
class MjCalibration {
	private val restingSpeeds = mutableListOf<Float>()
	private var pushPeakPxPerSec = 0f
	private var pushSampleCount = 0

	/** Feed one resting-phase sample (raw pixel delta + event-time gap in ms). */
	fun addRestingSample(dx: Float, dy: Float, dtMs: Long) {
		speedOf(dx, dy, dtMs)?.let { restingSpeeds.add(it) }
	}

	/** Feed one push-phase sample. */
	fun addPushSample(dx: Float, dy: Float, dtMs: Long) {
		speedOf(dx, dy, dtMs)?.let {
			pushSampleCount++
			if (it > pushPeakPxPerSec) pushPeakPxPerSec = it
		}
	}

	private fun speedOf(dx: Float, dy: Float, dtMs: Long): Float? {
		if (dtMs <= 0L) return null // a zero/backwards gap can't yield a speed
		return hypot(dx, dy) / (dtMs / MS_PER_SEC)
	}

	/** The idle jitter floor (p95 of resting speed), 0 if no resting samples were seen. */
	fun restingNoisePxPerSec(): Float = percentile(restingSpeeds, RESTING_PERCENTILE)

	fun pushPeakPxPerSec(): Float = pushPeakPxPerSec

	/**
	 * Derive the settings from the samples collected so far.
	 * @param density display density, to convert px/sec into the stored dp/sec sensitivity unit.
	 */
	fun derive(density: Float): Result {
		val noise = restingNoisePxPerSec()
		val peak = pushPeakPxPerSec
		// Guard rails: no real push, or the push barely rose above idle jitter → can't distinguish.
		if (peak < MIN_USABLE_PUSH_PX_PER_SEC || pushSampleCount < MIN_PUSH_SAMPLES || peak < noise * MIN_PUSH_OVER_NOISE) {
			return Result(usable = false, deadZone = 0f, sensitivityDpPerSec = 0)
		}
		val deadZone = (noise / peak * DEAD_ZONE_MARGIN).coerceIn(DEAD_ZONE_MIN, DEAD_ZONE_MAX)
		val sensitivityDpPerSec = (peak / density).toInt().coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
		return Result(usable = true, deadZone = deadZone, sensitivityDpPerSec = sensitivityDpPerSec)
	}

	fun reset() {
		restingSpeeds.clear()
		pushPeakPxPerSec = 0f
		pushSampleCount = 0
	}

	/** Derived calibration. [usable] is false when the samples were too weak/ambiguous to trust. */
	data class Result(
		val usable: Boolean,
		val deadZone: Float,
		val sensitivityDpPerSec: Int,
	)

	companion object {
		private const val MS_PER_SEC = 1000f
		private const val RESTING_PERCENTILE = 0.95f

		// Dead zone = idle-noise-to-push ratio, widened by a safety margin, then clamped.
		private const val DEAD_ZONE_MARGIN = 3.0f
		private const val DEAD_ZONE_MIN = 0.05f
		private const val DEAD_ZONE_MAX = 0.40f

		// Sensitivity range in dp/sec (the new B1 unit). Calibrated from real push speeds on-device
		// (full-push ~1400-2500 dp/sec). Matches the settings slider range.
		const val SENSITIVITY_MIN = 100
		const val SENSITIVITY_MAX = 2500

		// Usability guard rails.
		private const val MIN_USABLE_PUSH_PX_PER_SEC = 200f
		private const val MIN_PUSH_SAMPLES = 5
		private const val MIN_PUSH_OVER_NOISE = 2.0f

		/** Linear-interpolated [p]-percentile (0..1) of [values]; 0 for an empty list. */
		fun percentile(values: List<Float>, p: Float): Float {
			if (values.isEmpty()) return 0f
			val sorted = values.sorted()
			if (sorted.size == 1) return sorted[0]
			val rank = p.coerceIn(0f, 1f) * (sorted.size - 1)
			val lo = rank.toInt()
			val hi = (lo + 1).coerceAtMost(sorted.size - 1)
			val frac = rank - lo
			return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
		}
	}
}
