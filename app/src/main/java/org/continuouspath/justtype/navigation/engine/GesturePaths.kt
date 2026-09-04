package org.continuouspath.justtype.navigation.engine

/** Start→end line for an injected swipe. */
data class SwipeSegment(val startX: Int, val startY: Int, val endX: Int, val endY: Int)

/** Pure geometry for gesture-injected scrolls, so the dispatch adapter stays thin. */
object GesturePaths {
	// Stay off the container edges: system edge gestures (back, drawers) live there.
	private const val EDGE_MARGIN_FRACTION = 0.15

	/**
	 * Swipe that reveals content toward [dir]: the finger travels opposite [dir] across
	 * [distancePercent] of the container's usable span, centered in [area]. A swipe line
	 * covered by [avoid] (the Nav grid window) shifts to the roomier free side of it.
	 */
	fun scrollSwipe(area: NavBounds, dir: NavDirection, distancePercent: Int, avoid: NavBounds?): SwipeSegment {
		val vertical = dir == NavDirection.UP || dir == NavDirection.DOWN
		return if (vertical) {
			val x = crossAxisPosition(area.left, area.right, avoid?.left, avoid?.right)
			val (start, end) = travel(area.top, area.bottom, distancePercent, towardFarEdge = dir == NavDirection.UP)
			SwipeSegment(x, start, x, end)
		} else {
			val y = crossAxisPosition(area.top, area.bottom, avoid?.top, avoid?.bottom)
			val (start, end) = travel(area.left, area.right, distancePercent, towardFarEdge = dir == NavDirection.LEFT)
			SwipeSegment(start, y, end, y)
		}
	}

	/** Finger travel along the scroll axis; revealing toward the near edge means moving toward the far one. */
	private fun travel(low: Int, high: Int, distancePercent: Int, towardFarEdge: Boolean): Pair<Int, Int> {
		val margin = ((high - low) * EDGE_MARGIN_FRACTION).toInt()
		val usable = (high - low) - 2 * margin
		val span = usable * distancePercent.coerceIn(1, 100) / 100
		val center = (low + high) / 2
		val start = center - span / 2
		val end = start + span
		return if (towardFarEdge) start to end else end to start
	}

	/**
	 * Center of the cross axis, shifted to the wider free strip when [avoid] covers it. Shifted
	 * positions are pulled out of the system edge-gesture zones when that doesn't re-enter [avoid];
	 * a strip too thin for both constraints keeps its midpoint (edge risk beats pressing our keys).
	 */
	private fun crossAxisPosition(low: Int, high: Int, avoidLow: Int?, avoidHigh: Int?): Int {
		val center = (low + high) / 2
		if (avoidLow == null || avoidHigh == null) return center
		if (center < avoidLow || center > avoidHigh) return center
		val margin = ((high - low) * EDGE_MARGIN_FRACTION).toInt()
		val before = avoidLow - low
		val after = high - avoidHigh
		val strips = if (before >= after) {
			listOf((low + avoidLow) / 2 to (low until avoidLow), (avoidHigh + high) / 2 to (avoidHigh + 1..high))
		} else {
			listOf((avoidHigh + high) / 2 to (avoidHigh + 1..high), (low + avoidLow) / 2 to (low until avoidLow))
		}
		for ((midpoint, range) in strips) {
			val clamped = midpoint.coerceIn(low + margin, high - margin)
			if (clamped in range) return clamped
		}
		return if (before >= after) (low + avoidLow) / 2 else (avoidHigh + high) / 2
	}
}
