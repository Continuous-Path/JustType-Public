package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusGeometryTest {

	private fun ib(index: Int, left: Int, top: Int, right: Int, bottom: Int) = FocusGeometry.IndexedBounds(index, NavBounds(left, top, right, bottom))

	@Test
	fun `in-beam candidate beats a nearer diagonal one`() {
		val cur = NavBounds(0, 1000, 200, 1100)
		val aligned = ib(0, 800, 1000, 1000, 1100) // 600px right, same row
		val diagonal = ib(1, 300, 1150, 500, 1250) // 100px right but a row below
		assertThat(FocusGeometry.pickNeighbor(cur, listOf(diagonal, aligned), NavDirection.RIGHT)).isEqualTo(0)
	}

	@Test
	fun `out-of-beam candidate dominated by perpendicular travel yields no neighbor`() {
		val cur = NavBounds(500, 2000, 700, 2100)
		val junk = ib(0, 900, 2102, 1100, 2202) // 2px forward, 400px sideways
		assertThat(FocusGeometry.pickNeighbor(cur, listOf(junk), NavDirection.DOWN)).isNull()
	}

	@Test
	fun `right walks the row to the nearest in-row candidate`() {
		val cur = NavBounds(0, 0, 200, 100)
		val nextInRow = ib(0, 250, 0, 450, 100)
		val farInRow = ib(1, 500, 0, 700, 100)
		val closerButBelow = ib(2, 250, 150, 450, 250)
		assertThat(FocusGeometry.pickNeighbor(cur, listOf(farInRow, closerButBelow, nextInRow), NavDirection.RIGHT)).isEqualTo(0)
	}

	@Test
	fun `partially overlapping next row is reachable going down`() {
		val cur = NavBounds(0, 500, 1080, 700)
		val overlappingNext = ib(0, 0, 680, 1080, 880) // starts above cur's bottom edge
		assertThat(FocusGeometry.pickNeighbor(cur, listOf(overlappingNext), NavDirection.DOWN)).isEqualTo(0)
	}

	@Test
	fun `candidates containing or contained by current are never picked`() {
		val cur = NavBounds(100, 100, 300, 200)
		val container = ib(0, 0, 0, 1080, 2280)
		val inner = ib(1, 150, 150, 250, 200)
		assertThat(FocusGeometry.pickNeighbor(cur, listOf(container, inner), NavDirection.DOWN)).isNull()
	}

	@Test
	fun `equal candidates tie-break to tree order`() {
		val cur = NavBounds(400, 400, 600, 500)
		val first = ib(0, 400, 600, 600, 700)
		val duplicate = ib(1, 400, 600, 600, 700)
		assertThat(FocusGeometry.pickNeighbor(cur, listOf(first, duplicate), NavDirection.DOWN)).isEqualTo(0)
	}

	@Test
	fun `up and left mirror the port symmetrically`() {
		val cur = NavBounds(400, 1000, 600, 1100)
		val above = ib(0, 400, 800, 600, 900)
		val leftOf = ib(1, 100, 1000, 300, 1100)
		assertThat(FocusGeometry.pickNeighbor(cur, listOf(above, leftOf), NavDirection.UP)).isEqualTo(0)
		assertThat(FocusGeometry.pickNeighbor(cur, listOf(above, leftOf), NavDirection.LEFT)).isEqualTo(1)
	}

	@Test
	fun `after-scroll pick prefers the nearest candidate toward the travel direction`() {
		val previousRing = NavBounds(0, 900, 1080, 1000)
		val justAbove = ib(0, 0, 880, 1080, 940) // nearer, but behind the downward travel
		val justBelow = ib(1, 0, 1000, 1080, 1100)
		assertThat(FocusGeometry.pickAfterScroll(previousRing, listOf(justAbove, justBelow), NavDirection.DOWN)).isEqualTo(1)
	}

	@Test
	fun `after-scroll pick falls back to nearest overall when nothing lies toward travel`() {
		val previousRing = NavBounds(0, 1800, 1080, 1900)
		val near = ib(0, 0, 1500, 1080, 1600)
		val far = ib(1, 0, 100, 1080, 200)
		assertThat(FocusGeometry.pickAfterScroll(previousRing, listOf(far, near), NavDirection.DOWN)).isEqualTo(0)
	}

	@Test
	fun `after-scroll pick on an empty field is null`() {
		assertThat(FocusGeometry.pickAfterScroll(NavBounds(0, 0, 100, 100), emptyList(), NavDirection.DOWN)).isNull()
	}
}
