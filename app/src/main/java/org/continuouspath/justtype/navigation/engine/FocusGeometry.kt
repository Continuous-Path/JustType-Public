package org.continuouspath.justtype.navigation.engine

/**
 * Directional neighbor picking, ported from AOSP FocusFinder's rect algorithm:
 * edge-based candidacy, beam preference, 13·major² + minor² weighted distance.
 * Two JT additions: containment rejection, and an off-axis cap on out-of-beam
 * candidates so a boundary press reads as "no neighbor" and can edge-scroll.
 */
@Suppress("TooManyFunctions") // helpers mirror AOSP FocusFinder 1:1 to keep the port auditable
object FocusGeometry {
	data class IndexedBounds(val index: Int, val bounds: NavBounds)

	private const val MAX_OFF_AXIS_RATIO = 2

	/** Best next selection from [current] toward [dir], or null when the field has no acceptable candidate. */
	fun pickNeighbor(current: NavBounds, candidates: List<IndexedBounds>, dir: NavDirection): Int? {
		var best: IndexedBounds? = null
		for (cand in candidates) {
			if (!acceptable(dir, current, cand.bounds)) continue
			if (best == null || isBetterCandidate(dir, current, cand.bounds, best.bounds)) best = cand
		}
		return best?.index
	}

	/**
	 * Post-scroll reselect: the candidate nearest the previous ring rect, preferring ones whose
	 * center lies at or past it toward [travel] — continues the motion instead of a blind edge jump.
	 */
	fun pickAfterScroll(previous: NavBounds, candidates: List<IndexedBounds>, travel: NavDirection): Int? {
		if (candidates.isEmpty()) return null
		val toward = candidates.filter { isTowardTravel(previous, it.bounds, travel) }
		return toward.ifEmpty { candidates }.minByOrNull { previous.centerDistanceSqTo(it.bounds) }?.index
	}

	private fun isTowardTravel(previous: NavBounds, dest: NavBounds, travel: NavDirection): Boolean = when (travel) {
		NavDirection.UP -> dest.centerY <= previous.centerY
		NavDirection.DOWN -> dest.centerY >= previous.centerY
		NavDirection.LEFT -> dest.centerX <= previous.centerX
		NavDirection.RIGHT -> dest.centerX >= previous.centerX
	}

	private fun acceptable(dir: NavDirection, src: NavBounds, dest: NavBounds): Boolean {
		if (dest == src || dest.contains(src) || src.contains(dest)) return false
		if (!isCandidate(src, dest, dir)) return false
		// Out-of-beam picks dominated by perpendicular travel are junk hops at a boundary.
		return beamsOverlap(dir, src, dest) || minorAxisDistance(dir, src, dest) <= MAX_OFF_AXIS_RATIO * majorAxisDistance(dir, src, dest)
	}

	/** Edge-based candidacy (AOSP): partially overlapping rects qualify; strict center progress is not required. */
	fun isCandidate(src: NavBounds, dest: NavBounds, dir: NavDirection): Boolean = when (dir) {
		NavDirection.LEFT -> (src.right > dest.right || src.left >= dest.right) && src.left > dest.left
		NavDirection.RIGHT -> (src.left < dest.left || src.right <= dest.left) && src.right < dest.right
		NavDirection.UP -> (src.bottom > dest.bottom || src.top >= dest.bottom) && src.top > dest.top
		NavDirection.DOWN -> (src.top < dest.top || src.bottom <= dest.top) && src.bottom < dest.bottom
	}

	/** True when [r1] is a better pick than [r2] from [src] toward [dir] (AOSP isBetterCandidate). */
	fun isBetterCandidate(dir: NavDirection, src: NavBounds, r1: NavBounds, r2: NavBounds): Boolean {
		if (!isCandidate(src, r1, dir)) return false
		if (!isCandidate(src, r2, dir)) return true
		if (beamBeats(dir, src, r1, r2)) return true
		if (beamBeats(dir, src, r2, r1)) return false
		return weightedDistance(majorAxisDistance(dir, src, r1), minorAxisDistance(dir, src, r1)) <
			weightedDistance(majorAxisDistance(dir, src, r2), minorAxisDistance(dir, src, r2))
	}

	/** In-beam [r1] beats out-of-beam [r2], except a vertically closer-past-the-far-edge [r2] (AOSP beamBeats). */
	private fun beamBeats(dir: NavDirection, src: NavBounds, r1: NavBounds, r2: NavBounds): Boolean {
		val r1InBeam = beamsOverlap(dir, src, r1)
		val r2InBeam = beamsOverlap(dir, src, r2)
		if (r2InBeam || !r1InBeam) return false
		if (!isToDirectionOf(dir, src, r2)) return true
		if (dir == NavDirection.LEFT || dir == NavDirection.RIGHT) return true
		return majorAxisDistance(dir, src, r1) < majorAxisDistanceToFarEdge(dir, src, r2)
	}

	/** Perpendicular projections overlap: the candidate sits in the strip [src] sweeps toward [dir]. */
	fun beamsOverlap(dir: NavDirection, a: NavBounds, b: NavBounds): Boolean = when (dir) {
		NavDirection.LEFT, NavDirection.RIGHT -> b.bottom > a.top && b.top < a.bottom
		NavDirection.UP, NavDirection.DOWN -> b.right > a.left && b.left < a.right
	}

	private fun isToDirectionOf(dir: NavDirection, src: NavBounds, dest: NavBounds): Boolean = when (dir) {
		NavDirection.LEFT -> src.left >= dest.right
		NavDirection.RIGHT -> src.right <= dest.left
		NavDirection.UP -> src.top >= dest.bottom
		NavDirection.DOWN -> src.bottom <= dest.top
	}

	private fun majorAxisDistance(dir: NavDirection, src: NavBounds, dest: NavBounds): Int = maxOf(
		0,
		when (dir) {
			NavDirection.LEFT -> src.left - dest.right
			NavDirection.RIGHT -> dest.left - src.right
			NavDirection.UP -> src.top - dest.bottom
			NavDirection.DOWN -> dest.top - src.bottom
		},
	)

	private fun majorAxisDistanceToFarEdge(dir: NavDirection, src: NavBounds, dest: NavBounds): Int = maxOf(
		1,
		when (dir) {
			NavDirection.LEFT -> src.left - dest.left
			NavDirection.RIGHT -> dest.right - src.right
			NavDirection.UP -> src.top - dest.top
			NavDirection.DOWN -> dest.bottom - src.bottom
		},
	)

	private fun minorAxisDistance(dir: NavDirection, src: NavBounds, dest: NavBounds): Int = when (dir) {
		NavDirection.LEFT, NavDirection.RIGHT -> kotlin.math.abs(src.centerY - dest.centerY)
		NavDirection.UP, NavDirection.DOWN -> kotlin.math.abs(src.centerX - dest.centerX)
	}

	private fun weightedDistance(major: Int, minor: Int): Long = 13L * major * major + minor.toLong() * minor
}
