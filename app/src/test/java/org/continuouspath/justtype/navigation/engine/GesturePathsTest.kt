package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GesturePathsTest {
	private val screen = NavBounds(0, 0, 1000, 2000)

	@Test
	fun `revealing content below swipes the finger upward along the center line`() {
		val s = GesturePaths.scrollSwipe(screen, NavDirection.DOWN, 100, avoid = null)
		assertThat(s.startX).isEqualTo(500)
		assertThat(s.endX).isEqualTo(500)
		assertThat(s.startY).isGreaterThan(s.endY)
	}

	@Test
	fun `revealing content to the right swipes the finger leftward`() {
		val s = GesturePaths.scrollSwipe(screen, NavDirection.RIGHT, 100, avoid = null)
		assertThat(s.startY).isEqualTo(1000)
		assertThat(s.endY).isEqualTo(1000)
		assertThat(s.startX).isGreaterThan(s.endX)
	}

	@Test
	fun `distance percent scales the travel span`() {
		val full = GesturePaths.scrollSwipe(screen, NavDirection.DOWN, 100, avoid = null)
		val half = GesturePaths.scrollSwipe(screen, NavDirection.DOWN, 50, avoid = null)
		val fullSpan = full.startY - full.endY
		val halfSpan = half.startY - half.endY
		assertThat(halfSpan).isEqualTo(fullSpan / 2)
	}

	@Test
	fun `segment keeps off the container edges`() {
		val s = GesturePaths.scrollSwipe(screen, NavDirection.UP, 100, avoid = null)
		assertThat(minOf(s.startY, s.endY)).isAtLeast(300) // 15% margin of 2000
		assertThat(maxOf(s.startY, s.endY)).isAtMost(1700)
	}

	@Test
	fun `swipe line covered by the grid shifts to the wider free side`() {
		val gridOnCenterRight = NavBounds(450, 800, 950, 1300)
		val s = GesturePaths.scrollSwipe(screen, NavDirection.DOWN, 100, avoid = gridOnCenterRight)
		assertThat(s.startX).isEqualTo(225) // middle of the free strip left of the grid
	}

	@Test
	fun `shifted swipe line is pulled out of the edge-gesture zone when the strip allows`() {
		val gridNearLeftEdge = NavBounds(60, 500, 800, 1500)
		val s = GesturePaths.scrollSwipe(screen, NavDirection.DOWN, 100, avoid = gridNearLeftEdge)
		assertThat(s.startX).isEqualTo(850) // right strip midpoint 900, clamped inside the 15% margin
	}

	@Test
	fun `swipe line clear of the grid stays centered`() {
		val gridBottomRight = NavBounds(600, 1500, 1000, 2000)
		val s = GesturePaths.scrollSwipe(screen, NavDirection.RIGHT, 100, avoid = gridBottomRight)
		assertThat(s.startY).isEqualTo(1000)
	}
}
