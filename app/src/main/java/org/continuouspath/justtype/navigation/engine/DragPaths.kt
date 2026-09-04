package org.continuouspath.justtype.navigation.engine

/** One leg of a drag: a straight finger travel from [startX], [startY] to [endX], [endY]. */
data class DragSegment(val startX: Int, val startY: Int, val endX: Int, val endY: Int, val durationMs: Long)

/**
 * An ordered drop gesture: press-and-hold at the pickup point, then travel to the drop point. The
 * dispatcher fires each segment as one continued stroke in its own dispatch (all but the last keep
 * the pointer down), so the app sees a single press-hold-drag-release. The hold is expressed as a
 * run of tiny in-place "tick" segments (not one silent stroke) so the launcher's long-press detector
 * gets a steady stream of held-in-place move events and arms the pickup before the finger travels.
 */
data class DragGesture(val segments: List<DragSegment>) {
	/** Wall-clock length of the whole drop once dispatched — segments play back-to-back. */
	val totalDurationMs: Long get() = segments.sumOf { it.durationMs }
}

/** Pure geometry for the injected drop gesture, so the dispatch adapter stays thin. */
object DragPaths {
	// Total press-hold window before the drag travels. Only needs to clear the launcher long-press
	// (getLongPressTimeout * 0.75 ≈ 300ms) with margin — held as ticks below, not one silent stroke.
	const val HOLD_DURATION_MS = 600L

	// The hold is split into this many in-place ticks. A single zero-length hold stroke emits NO
	// motion events (the injector only emits ACTION_MOVE on coordinate change), so the launcher never
	// sees a held touch and reads the gesture as a swipe. Each tick nudges 1px (within touch-slop, so
	// it doesn't cancel long-press) to emit a real move event that proves "still down, still here".
	const val HOLD_TICKS = 6

	// Brief hold at the destination before release so the drop registers on the target slot.
	const val SETTLE_DURATION_MS = 150L

	// Travel speed for the move leg: duration is derived from the drag distance so every
	// drag moves at the same deliberate pace. Too fast reads as a fling and outruns the
	// app's drag-follow; a fixed duration would crawl on short drags and fling on long ones.
	const val MOVE_SPEED_PX_PER_MS = 1.5f

	// Keep the derived move duration sane: never a fling-length twitch, never a crawl.
	const val MOVE_MIN_DURATION_MS = 300L
	const val MOVE_MAX_DURATION_MS = 1500L

	/** Move duration for a straight [distancePx] travel at [MOVE_SPEED_PX_PER_MS], clamped. */
	fun moveDurationFor(distancePx: Float): Long = (distancePx / MOVE_SPEED_PX_PER_MS).toLong().coerceIn(MOVE_MIN_DURATION_MS, MOVE_MAX_DURATION_MS)

	/**
	 * Drop gesture from [pickup] to [drop]: hold-ticks to pick up, drag to the target, settle, release.
	 * Every segment's start point is pixel-exact the previous segment's end so the streamed strokes
	 * continue one held pointer. The move duration is derived from the travel distance (constant
	 * speed) unless [moveMs] is given.
	 */
	fun drop(
		pickup: NavBounds,
		drop: NavBounds,
		holdMs: Long = HOLD_DURATION_MS,
		moveMs: Long? = null,
	): DragGesture {
		val px = pickup.centerX
		val py = pickup.centerY
		val dx = drop.centerX
		val dy = drop.centerY
		val segments = mutableListOf<DragSegment>()

		// Hold: a run of 1px in-place ticks so the launcher sees a held-still touch and arms long-press.
		val tickMs = (holdMs / HOLD_TICKS).coerceAtLeast(1L)
		var hx = px
		repeat(HOLD_TICKS) { i ->
			val nx = if (i % 2 == 0) px + 1 else px // alternate 1px wiggle, well within touch-slop
			segments.add(DragSegment(hx, py, nx, py, tickMs))
			hx = nx
		}

		val distance = kotlin.math.hypot((dx - hx).toFloat(), (dy - py).toFloat())
		segments.add(DragSegment(hx, py, dx, dy, moveMs ?: moveDurationFor(distance))) // drag
		segments.add(DragSegment(dx, dy, dx, dy, SETTLE_DURATION_MS)) // settle before release
		return DragGesture(segments)
	}
}
