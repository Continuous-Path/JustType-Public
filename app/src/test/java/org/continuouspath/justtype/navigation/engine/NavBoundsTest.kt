package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavBoundsTest {

	@Test
	fun `dimensions and centers`() {
		val b = NavBounds(10, 20, 110, 220)
		assertThat(b.width).isEqualTo(100)
		assertThat(b.height).isEqualTo(200)
		assertThat(b.centerX).isEqualTo(60)
		assertThat(b.centerY).isEqualTo(120)
		assertThat(b.isEmpty).isFalse()
	}

	@Test
	fun `zero or negative size is empty`() {
		assertThat(NavBounds(5, 5, 5, 50).isEmpty).isTrue()
		assertThat(NavBounds(5, 5, 50, 5).isEmpty).isTrue()
		assertThat(NavBounds(5, 5, 4, 50).isEmpty).isTrue()
	}

	@Test
	fun `contains includes equal and inner rects, excludes overlap`() {
		val outer = NavBounds(0, 0, 100, 100)
		assertThat(outer.contains(outer)).isTrue()
		assertThat(outer.contains(NavBounds(10, 10, 90, 90))).isTrue()
		assertThat(outer.contains(NavBounds(10, 10, 110, 90))).isFalse()
		assertThat(NavBounds(10, 10, 90, 90).contains(outer)).isFalse()
	}

	@Test
	fun `intersects matches Rect semantics — shared edge does not intersect`() {
		val a = NavBounds(0, 0, 100, 100)
		assertThat(a.intersects(NavBounds(50, 50, 150, 150))).isTrue()
		assertThat(a.intersects(NavBounds(100, 0, 200, 100))).isFalse()
		assertThat(a.intersects(NavBounds(0, 100, 100, 200))).isFalse()
		assertThat(a.intersects(NavBounds(150, 150, 250, 250))).isFalse()
	}

	@Test
	fun `intersectClamped clips a container that spills past the screen edges`() {
		val screen = NavBounds(0, 0, 1000, 2000)
		// A list scrolled so its top is above the screen and bottom below it.
		val container = NavBounds(0, -500, 1000, 2500)
		val clipped = container.intersectClamped(screen)
		assertThat(clipped).isEqualTo(screen)
	}

	@Test
	fun `intersectClamped keeps an inner container untouched`() {
		val screen = NavBounds(0, 0, 1000, 2000)
		val container = NavBounds(100, 300, 900, 1700)
		assertThat(container.intersectClamped(screen)).isEqualTo(container)
	}

	@Test
	fun `intersectClamped falls back to the screen when the container is fully off-screen`() {
		val screen = NavBounds(0, 0, 1000, 2000)
		val offScreen = NavBounds(0, 3000, 1000, 4000) // entirely below the screen
		assertThat(offScreen.intersectClamped(screen)).isEqualTo(screen)
	}

	@Test
	fun `area multiplies width by height as a long`() {
		assertThat(NavBounds(0, 0, 1000, 2000).area).isEqualTo(2_000_000L)
		// Big enough that an Int multiply would overflow.
		assertThat(NavBounds(0, 0, 100_000, 100_000).area).isEqualTo(10_000_000_000L)
		assertThat(NavBounds(5, 5, 5, 5).area).isEqualTo(0L)
	}

	@Test
	fun `centerDistanceSqTo is the squared distance between centers`() {
		val a = NavBounds(0, 0, 100, 100) // center (50, 50)
		val b = NavBounds(30, 40, 130, 140) // center (80, 90)
		assertThat(a.centerDistanceSqTo(b)).isEqualTo(30L * 30 + 40L * 40)
		assertThat(b.centerDistanceSqTo(a)).isEqualTo(a.centerDistanceSqTo(b))
		assertThat(a.centerDistanceSqTo(a)).isEqualTo(0L)
	}
}
