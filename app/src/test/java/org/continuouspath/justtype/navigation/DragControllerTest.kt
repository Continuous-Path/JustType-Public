package org.continuouspath.justtype.navigation

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.navigation.engine.NavDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DragControllerTest {
	private val screen = Rect(0, 0, 1000, 2000)

	private fun controller() = DragController(stepMin = 5, stepMax = 25, stepIncrement = 5)

	@Test
	fun `inactive until an element is selected`() {
		val c = controller()
		assertThat(c.isActive).isFalse()
		assertThat(c.buildDrop()).isNull()
		assertThat(c.cursorBounds()).isNull()
	}

	@Test
	fun `select seeds the cursor on the pickup and resets the step`() {
		val c = controller()
		c.shrinkStep() // move off default first
		c.select(Rect(100, 100, 300, 300), screen)
		assertThat(c.isActive).isTrue()
		assertThat(c.cursorBounds()).isEqualTo(Rect(100, 100, 300, 300))
		assertThat(c.pickupBounds()).isEqualTo(Rect(100, 100, 300, 300))
		assertThat(c.step.percent).isEqualTo(10) // reset to default
	}

	@Test
	fun `nudge moves the cursor by the step distance keeping its size`() {
		val c = controller()
		c.select(Rect(100, 100, 300, 300), screen) // center (200,200), 200x200, default step 10% of 1000 = 100px
		c.nudge(NavDirection.RIGHT)
		assertThat(c.cursorBounds()).isEqualTo(Rect(200, 100, 400, 300)) // center moved to (300,200)
		c.nudge(NavDirection.DOWN)
		assertThat(c.cursorBounds()).isEqualTo(Rect(200, 200, 400, 400)) // center (300,300)
	}

	@Test
	fun `nudge clamps the cursor center inside the screen`() {
		val c = controller()
		c.select(Rect(940, 100, 960, 200), screen) // center x=950, step 100px → 1050 clamps to last column 999
		c.nudge(NavDirection.RIGHT)
		assertThat(c.cursorBounds()!!.centerX()).isEqualTo(999) // right (1000) is exclusive; last pixel is 999
	}

	@Test
	fun `step grows and shrinks within bounds`() {
		val c = controller()
		c.select(Rect(0, 0, 10, 10), screen)
		c.growStep()
		assertThat(c.step.percent).isEqualTo(15)
		c.shrinkStep()
		c.shrinkStep()
		assertThat(c.step.percent).isEqualTo(5)
		assertThat(c.atMinStep()).isTrue()
		c.shrinkStep()
		assertThat(c.step.percent).isEqualTo(5) // clamped
	}

	@Test
	fun `buildDrop goes from the pickup center to the moved cursor center`() {
		val c = controller()
		c.select(Rect(100, 100, 300, 300), screen) // pickup center (200,200)
		c.nudge(NavDirection.RIGHT) // cursor center (300,200)
		val g = c.buildDrop()!!
		assertThat(g.segments.first().startX).isEqualTo(200)
		assertThat(g.segments.first().startY).isEqualTo(200)
		assertThat(g.segments.last().endX).isEqualTo(300)
		assertThat(g.segments.last().endY).isEqualTo(200)
	}

	@Test
	fun `cancel clears the session`() {
		val c = controller()
		c.select(Rect(0, 0, 10, 10), screen)
		c.cancel()
		assertThat(c.isActive).isFalse()
		assertThat(c.buildDrop()).isNull()
	}

	@Test
	fun `nudged clamps inside the screen — top-left inclusive, right-bottom one inside (exclusive dims)`() {
		assertThat(DragController.nudged(500, 0, NavDirection.UP, 100, screen)).isEqualTo(500 to 0)
		assertThat(DragController.nudged(0, 500, NavDirection.LEFT, 100, screen)).isEqualTo(0 to 500)
		// right=1000 / bottom=2000 are exclusive; the cursor stops at the last addressable pixel.
		assertThat(DragController.nudged(500, 2000, NavDirection.DOWN, 100, screen)).isEqualTo(500 to 1999)
		assertThat(DragController.nudged(1000, 500, NavDirection.RIGHT, 100, screen)).isEqualTo(999 to 500)
	}
}
