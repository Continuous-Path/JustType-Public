package org.continuouspath.justtype.navigation

import android.graphics.Rect
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.navigation.engine.DragStep
import org.continuouspath.justtype.navigation.engine.NavDirection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectCursorControllerTest {

	private val screen = Rect(0, 0, 1000, 2000)

	private fun controller() = SelectCursorController(stepMin = 5, stepMax = 25, stepIncrement = 5)

	@Test
	fun `seeds at the anchor center`() {
		val c = controller()
		c.seed(Rect(100, 200, 300, 400), screen)
		assertThat(c.cursorBounds()).isEqualTo(Rect(200, 300, 200, 300))
		assertThat(c.isActive).isTrue()
	}

	@Test
	fun `seeds at screen center without an anchor`() {
		val c = controller()
		c.seed(null, screen)
		assertThat(c.cursorBounds()).isEqualTo(Rect(500, 1000, 500, 1000))
	}

	@Test
	fun `seed clamps an off-screen anchor onto the screen`() {
		val c = controller()
		c.seed(Rect(-500, -500, -400, -400), screen)
		assertThat(c.cursorBounds()).isEqualTo(Rect(0, 0, 0, 0))
		c.seed(Rect(5000, 5000, 6000, 6000), screen)
		assertThat(c.cursorBounds()).isEqualTo(Rect(999, 1999, 999, 1999))
	}

	@Test
	fun `seed resets the step for the new session`() {
		val c = controller()
		c.seed(null, screen)
		c.adjustStep(grow = true)
		assertThat(c.step).isNotEqualTo(DragStep.default())
		c.seed(null, screen)
		assertThat(c.step).isEqualTo(DragStep.default())
	}

	@Test
	fun `nudge moves one step and clamps at the edge`() {
		val c = controller()
		c.seed(Rect(500, 1000, 500, 1000), screen)
		val delta = c.step.stepPx(1000) // shorter screen dimension
		assertThat(c.nudge(NavDirection.RIGHT, screen)).isTrue()
		assertThat(c.cursorBounds()!!.centerX()).isEqualTo(500 + delta)

		repeat(50) { c.nudge(NavDirection.UP, screen) }
		assertThat(c.cursorBounds()!!.centerY()).isEqualTo(0)
		repeat(50) { c.nudge(NavDirection.RIGHT, screen) }
		assertThat(c.cursorBounds()!!.centerX()).isEqualTo(999) // exclusive right edge
	}

	@Test
	fun `nudge without a seed is a no-op`() {
		assertThat(controller().nudge(NavDirection.DOWN, screen)).isFalse()
	}

	@Test
	fun `adjustStep reports false at the bounds`() {
		val c = controller()
		c.seed(null, screen)
		// default 10, max 25: 15, 20, 25, then pinned
		assertThat(c.adjustStep(grow = true)).isTrue()
		assertThat(c.adjustStep(grow = true)).isTrue()
		assertThat(c.adjustStep(grow = true)).isTrue()
		assertThat(c.adjustStep(grow = true)).isFalse()
		// back down to 5, then pinned
		repeat(4) { assertThat(c.adjustStep(grow = false)).isTrue() }
		assertThat(c.adjustStep(grow = false)).isFalse()
		assertThat(c.atMinStep()).isTrue()
	}

	@Test
	fun `clear deactivates and resets`() {
		val c = controller()
		c.seed(null, screen)
		c.adjustStep(grow = true)
		c.clear()
		assertThat(c.isActive).isFalse()
		assertThat(c.cursorBounds()).isNull()
		assertThat(c.step).isEqualTo(DragStep.default())
	}
}
