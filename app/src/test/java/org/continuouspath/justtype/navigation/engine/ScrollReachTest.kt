package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollReachTest {
	private val min = 25
	private val max = 100
	private val step = 25

	@Test
	fun `default is the full-page percent`() {
		assertThat(ScrollReach.default().percent).isEqualTo(100)
		assertThat(ScrollReach.default().atMax(max)).isTrue()
	}

	@Test
	fun `longer increases by step and clamps at max`() {
		assertThat(ScrollReach(50).longer(step, max)).isEqualTo(ScrollReach(75))
		assertThat(ScrollReach(90).longer(step, max)).isEqualTo(ScrollReach(100))
		assertThat(ScrollReach(100).longer(step, max)).isEqualTo(ScrollReach(100))
	}

	@Test
	fun `shorter decreases by step and clamps at min`() {
		assertThat(ScrollReach(50).shorter(step, min)).isEqualTo(ScrollReach(25))
		assertThat(ScrollReach(30).shorter(step, min)).isEqualTo(ScrollReach(25))
		assertThat(ScrollReach(25).shorter(step, min)).isEqualTo(ScrollReach(25))
	}

	@Test
	fun `atMin and atMax report the bounds`() {
		assertThat(ScrollReach(25).atMin(min)).isTrue()
		assertThat(ScrollReach(50).atMin(min)).isFalse()
		assertThat(ScrollReach(100).atMax(max)).isTrue()
		assertThat(ScrollReach(75).atMax(max)).isFalse()
	}

	@Test
	fun `isBelowTapThreshold trips at or under the threshold`() {
		assertThat(ScrollReach.isBelowTapThreshold(spanPx = 10, tapThresholdPx = 24)).isTrue()
		assertThat(ScrollReach.isBelowTapThreshold(spanPx = 24, tapThresholdPx = 24)).isTrue()
		assertThat(ScrollReach.isBelowTapThreshold(spanPx = 25, tapThresholdPx = 24)).isFalse()
	}
}
