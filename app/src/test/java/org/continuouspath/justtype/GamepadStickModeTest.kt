package org.continuouspath.justtype

import android.view.InputDevice
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stick-click matching per [GamepadDirectionDetector.StickMode]: LEFT listens to the left thumb
 * button, RIGHT to the right, BOTH to either. (The IME's joystick deliberately uses BOTH.)
 */
@RunWith(RobolectricTestRunner::class)
class GamepadStickModeTest {

	private fun detector(mode: GamepadDirectionDetector.StickMode): GamepadDirectionDetector {
		clicks.clear()
		return GamepadDirectionDetector(
			stickMode = mode,
			onDirectionChanged = {},
			onStickClick = { down -> clicks.add(down) },
		)
	}

	private val clicks = mutableListOf<Boolean>()

	private fun gamepadKey(keyCode: Int): KeyEvent = KeyEvent(
		100L, 100L, KeyEvent.ACTION_DOWN, keyCode, 0, 0, 1, 0, 0, InputDevice.SOURCE_GAMEPAD,
	)

	@Test
	fun `LEFT mode matches only the left thumb button`() {
		val det = detector(GamepadDirectionDetector.StickMode.LEFT)
		assertThat(det.handleKeyEvent(gamepadKey(KeyEvent.KEYCODE_BUTTON_THUMBL))).isTrue()
		assertThat(det.handleKeyEvent(gamepadKey(KeyEvent.KEYCODE_BUTTON_THUMBR))).isFalse()
	}

	@Test
	fun `RIGHT mode matches only the right thumb button`() {
		val det = detector(GamepadDirectionDetector.StickMode.RIGHT)
		assertThat(det.handleKeyEvent(gamepadKey(KeyEvent.KEYCODE_BUTTON_THUMBR))).isTrue()
		assertThat(det.handleKeyEvent(gamepadKey(KeyEvent.KEYCODE_BUTTON_THUMBL))).isFalse()
	}

	@Test
	fun `BOTH mode matches either thumb button`() {
		val det = detector(GamepadDirectionDetector.StickMode.BOTH)
		assertThat(det.handleKeyEvent(gamepadKey(KeyEvent.KEYCODE_BUTTON_THUMBL))).isTrue()
		assertThat(det.handleKeyEvent(gamepadKey(KeyEvent.KEYCODE_BUTTON_THUMBR))).isTrue()
	}

	@Test
	fun `a non-thumb gamepad button never matches`() {
		val det = detector(GamepadDirectionDetector.StickMode.BOTH)
		assertThat(det.handleKeyEvent(gamepadKey(KeyEvent.KEYCODE_BUTTON_A))).isFalse()
	}

	@Test
	fun `a non-gamepad source is ignored even for the right keycode`() {
		clicks.clear()
		val det = GamepadDirectionDetector(
			stickMode = GamepadDirectionDetector.StickMode.RIGHT,
			onDirectionChanged = {},
			onStickClick = { down -> clicks.add(down) },
		)
		val keyboardThumb = KeyEvent(
			100L, 100L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBR, 0, 0, 1, 0, 0,
			InputDevice.SOURCE_KEYBOARD,
		)
		assertThat(det.handleKeyEvent(keyboardThumb)).isFalse()
		assertThat(clicks).isEmpty()
	}
}
