package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DragStepTest {
	private val min = 5
	private val max = 25
	private val step = 5

	@Test
	fun `default is the baseline percent`() {
		assertThat(DragStep.default().percent).isEqualTo(10)
	}

	@Test
	fun `longer increases by step and clamps at max`() {
		assertThat(DragStep(10).longer(step, max)).isEqualTo(DragStep(15))
		assertThat(DragStep(22).longer(step, max)).isEqualTo(DragStep(25))
		assertThat(DragStep(25).longer(step, max)).isEqualTo(DragStep(25))
	}

	@Test
	fun `shorter decreases by step and clamps at min`() {
		assertThat(DragStep(10).shorter(step, min)).isEqualTo(DragStep(5))
		assertThat(DragStep(7).shorter(step, min)).isEqualTo(DragStep(5))
		assertThat(DragStep(5).shorter(step, min)).isEqualTo(DragStep(5))
	}

	@Test
	fun `atMin and atMax report the bounds`() {
		assertThat(DragStep(5).atMin(min)).isTrue()
		assertThat(DragStep(10).atMin(min)).isFalse()
		assertThat(DragStep(25).atMax(max)).isTrue()
		assertThat(DragStep(20).atMax(max)).isFalse()
	}

	@Test
	fun `stepPx scales the reference span by percent`() {
		assertThat(DragStep(10).stepPx(1080)).isEqualTo(108)
		assertThat(DragStep(25).stepPx(1080)).isEqualTo(270)
		assertThat(DragStep(5).stepPx(1000)).isEqualTo(50)
	}
}
