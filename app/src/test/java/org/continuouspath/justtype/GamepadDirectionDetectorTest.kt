package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the dual-stick selection used by [GamepadDirectionDetector.StickMode.BOTH].
 * The full motion path can't be unit-tested (synthesized MotionEvents have no InputDevice,
 * so getCenteredAxis short-circuits), so the testable logic is the pure stick chooser.
 */
class GamepadDirectionDetectorTest {

	@Test
	fun `left stick wins when pushed further`() {
		assertThat(chooseStick(lx = -0.9f, ly = 0.1f, rx = 0.2f, ry = 0.0f)).isEqualTo(-0.9f to 0.1f)
	}

	@Test
	fun `right stick wins when pushed further`() {
		assertThat(chooseStick(lx = 0.1f, ly = 0.0f, rx = 0.0f, ry = 0.8f)).isEqualTo(0.0f to 0.8f)
	}

	@Test
	fun `tie favors the left stick`() {
		assertThat(chooseStick(lx = 0.5f, ly = 0.0f, rx = 0.5f, ry = 0.0f)).isEqualTo(0.5f to 0.0f)
	}

	@Test
	fun `both centered returns left zeros`() {
		assertThat(chooseStick(lx = 0.0f, ly = 0.0f, rx = 0.0f, ry = 0.0f)).isEqualTo(0.0f to 0.0f)
	}
}
