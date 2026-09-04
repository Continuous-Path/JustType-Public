package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DragPathsTest {
	private fun point(x: Int, y: Int) = NavBounds(x, y, x, y)

	@Test
	fun `drop holds at the pickup, travels to the drop, then settles`() {
		val g = DragPaths.drop(point(200, 400), point(600, 900))
		// HOLD_TICKS hold ticks + one move + one settle.
		assertThat(g.segments).hasSize(DragPaths.HOLD_TICKS + 2)
		// The hold ticks stay at the pickup Y and within 1px of the pickup X.
		g.segments.take(DragPaths.HOLD_TICKS).forEach { tick ->
			assertThat(tick.startY).isEqualTo(400)
			assertThat(tick.startX).isIn(199..201)
		}
		val move = g.segments[DragPaths.HOLD_TICKS]
		assertThat(move.endX).isEqualTo(600)
		assertThat(move.endY).isEqualTo(900)
		val settle = g.segments.last()
		assertThat(settle.startX).isEqualTo(600)
		assertThat(settle.startY).isEqualTo(900)
		assertThat(settle.endX).isEqualTo(600)
		assertThat(settle.endY).isEqualTo(900)
	}

	@Test
	fun `each segment starts pixel-exact where the previous ended`() {
		// The streamed continueStroke chain is rejected if a segment doesn't start where the last ended.
		val segs = DragPaths.drop(NavBounds(10, 20, 40, 60), NavBounds(500, 700, 700, 900)).segments
		segs.zipWithNext().forEach { (a, b) ->
			assertThat(b.startX).isEqualTo(a.endX)
			assertThat(b.startY).isEqualTo(a.endY)
		}
	}

	@Test
	fun `hold ticks emit real motion so the launcher sees a held touch`() {
		// A tick with no coordinate change emits no ACTION_MOVE — the bug we're avoiding. At least
		// some ticks must move (the 1px wiggle), so the hold isn't silent.
		val ticks = DragPaths.drop(point(300, 300), point(800, 300)).segments.take(DragPaths.HOLD_TICKS)
		assertThat(ticks.any { it.startX != it.endX || it.startY != it.endY }).isTrue()
	}

	@Test
	fun `pickup starts at the pickup center and the move ends at the drop center`() {
		val g = DragPaths.drop(NavBounds(100, 100, 300, 300), NavBounds(500, 700, 700, 900))
		assertThat(g.segments.first().startX).isEqualTo(200)
		assertThat(g.segments.first().startY).isEqualTo(200)
		val move = g.segments[DragPaths.HOLD_TICKS]
		assertThat(move.endX).isEqualTo(600)
		assertThat(move.endY).isEqualTo(800)
	}

	@Test
	fun `the hold window is split across the tick segments`() {
		val g = DragPaths.drop(point(0, 0), point(100, 100), holdMs = 600L, moveMs = 300L)
		val holdTotal = g.segments.take(DragPaths.HOLD_TICKS).sumOf { it.durationMs }
		// Each tick is holdMs/HOLD_TICKS (floored ≥1), so the total is within a tick of holdMs.
		assertThat(holdTotal).isAtLeast(600L - DragPaths.HOLD_TICKS)
		assertThat(g.segments[DragPaths.HOLD_TICKS].durationMs).isEqualTo(300L)
	}

	@Test
	fun `total duration sums every segment`() {
		val g = DragPaths.drop(point(0, 0), point(100, 100), holdMs = 600L, moveMs = 300L)
		assertThat(g.totalDurationMs).isEqualTo(g.segments.sumOf { it.durationMs })
		// hold ticks + move (300) + settle: strictly greater than the move leg alone.
		assertThat(g.totalDurationMs).isGreaterThan(300L)
	}

	@Test
	fun `a drop in place still emits well-formed segments`() {
		val g = DragPaths.drop(point(500, 500), point(500, 500))
		assertThat(g.segments).hasSize(DragPaths.HOLD_TICKS + 2)
		// The move + settle collapse to zero-length at the pickup; ticks wiggle 1px.
		assertThat(g.segments.last().startX).isEqualTo(g.segments.last().endX)
		assertThat(g.segments.last().startY).isEqualTo(g.segments.last().endY)
	}

	@Test
	fun `move duration scales with distance at the fixed speed`() {
		// 900px at 1.5 px/ms = 600ms, inside the clamp range.
		assertThat(DragPaths.moveDurationFor(900f)).isEqualTo(600L)
	}

	@Test
	fun `move duration clamps a tiny drag up to the floor`() {
		assertThat(DragPaths.moveDurationFor(30f)).isEqualTo(DragPaths.MOVE_MIN_DURATION_MS)
	}

	@Test
	fun `move duration clamps a huge drag down to the ceiling`() {
		assertThat(DragPaths.moveDurationFor(100_000f)).isEqualTo(DragPaths.MOVE_MAX_DURATION_MS)
	}

	@Test
	fun `drop derives the move duration from the travel distance when moveMs is omitted`() {
		// After the 1px hold wiggle the pickup X may be +1; distance ≈ hypot(299..300, 400) ≈ 500px.
		val g = DragPaths.drop(point(0, 0), point(300, 400))
		val move = g.segments[DragPaths.HOLD_TICKS]
		val expected = DragPaths.moveDurationFor(
			kotlin.math.hypot((move.endX - move.startX).toFloat(), (move.endY - move.startY).toFloat()),
		)
		assertThat(move.durationMs).isEqualTo(expected)
	}
}
