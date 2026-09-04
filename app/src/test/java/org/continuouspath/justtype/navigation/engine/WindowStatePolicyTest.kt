package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WindowStatePolicyTest {

	private val bounds = NavBounds(0, 0, 100, 100)

	private fun survives(
		otherAppWindow: Boolean = false,
		bounds: NavBounds = this.bounds,
		overlayUp: Boolean = false,
		visible: Boolean = true,
		windowPresent: Boolean = true,
	) = WindowStatePolicy.selectionSurvives(otherAppWindow, bounds, overlayUp, visible, windowPresent)

	@Test
	fun `a benign transition keeps the selection`() {
		assertThat(survives()).isTrue()
	}

	@Test
	fun `another app window announcing itself is a real change`() {
		assertThat(survives(otherAppWindow = true)).isFalse()
	}

	@Test
	fun `an empty-bounds selection does not survive`() {
		assertThat(survives(bounds = NavBounds(50, 50, 50, 50))).isFalse()
		assertThat(survives(bounds = NavBounds(50, 50, 40, 60))).isFalse()
	}

	@Test
	fun `an invisible selection dies without a capture overlay`() {
		assertThat(survives(visible = false)).isFalse()
	}

	@Test
	fun `a capture overlay bypasses the visibility check`() {
		// Full-screen capture overlays blind isVisibleToUser (reports false for covered app nodes).
		assertThat(survives(visible = false, overlayUp = true)).isTrue()
	}

	@Test
	fun `a vanished window kills the selection even when everything else holds`() {
		assertThat(survives(windowPresent = false)).isFalse()
	}
}
