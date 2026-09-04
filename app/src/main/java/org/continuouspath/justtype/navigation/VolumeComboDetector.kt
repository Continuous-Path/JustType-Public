package org.continuouspath.justtype.navigation

/**
 * Detects N presses within a sliding time window — the volume-down ×3 failsafe
 * that tears down [NavTouchOverlay] even if the capture view is consuming every
 * touch. Records event timestamps in a ring buffer; [tripped] is true when the
 * oldest of the last [size] presses is within [windowMs] of the newest.
 */
class VolumeComboDetector(private val size: Int = 3) {
	private val timestamps = LongArray(size)
	private var count = 0

	fun record(timeMs: Long) {
		// Shift left, append — small fixed size, no allocation.
		for (i in 0 until size - 1) timestamps[i] = timestamps[i + 1]
		timestamps[size - 1] = timeMs
		if (count < size) count++
	}

	fun tripped(windowMs: Long): Boolean = count >= size && timestamps[size - 1] - timestamps[0] <= windowMs

	fun clear() {
		count = 0
		timestamps.fill(0L)
	}
}
