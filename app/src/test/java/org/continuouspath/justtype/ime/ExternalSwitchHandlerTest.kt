package org.continuouspath.justtype.ime

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.continuouspath.justtype.GamepadDirectionDetector
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExternalSwitchHandlerTest {

	private lateinit var testScope: TestScope
	private lateinit var handler: ExternalSwitchHandler

	// Backing fields use leading-underscore names so the anonymous-object overrides
	// below can reference them without `this`-shadowing recursion.
	private var _isInputViewShown = true
	private var _isJtuiInitialized = true
	private var _isCapturingKey = false
	private var _isSingleSwitchEnabled = false
	private var _isTwoSwitchEnabled = false
	private var _isJoystickMethodActive = true // existing gamepad-path tests assume joystick is active
	private var _switchCodes = SwitchCodeConfig(
		scanCode = KeyEvent.KEYCODE_1,
		redCode = KeyEvent.KEYCODE_2,
		greenCode = KeyEvent.KEYCODE_3,
	)

	private val handleRawKeyCaptures = mutableListOf<Int>()
	private val buttonPresses = mutableListOf<Int>()
	private val scanSwitchDowns = mutableListOf<Int>()
	private var scanSwitchUps = 0
	private val twoSwitchDowns = mutableListOf<String>()
	private var twoSwitchUps = 0
	private val twoSwitchHeldStates = mutableListOf<Boolean>()
	private val joystickInputs = mutableListOf<Pair<Float, Float>>()
	private val debugLogs = mutableListOf<String>()

	private val callbacks = object : ExternalSwitchCallbacks {
		override val isInputViewShown get() = _isInputViewShown
		override val isJtuiInitialized get() = _isJtuiInitialized
		override val isCapturingKey get() = _isCapturingKey
		override val isSingleSwitchEnabled get() = _isSingleSwitchEnabled
		override val isTwoSwitchEnabled get() = _isTwoSwitchEnabled
		override val isJoystickMethodActive get() = _isJoystickMethodActive
		override val isSwitchInputLoggingEnabled get() = false

		override fun handleRawKeyCapture(keyCode: Int) {
			handleRawKeyCaptures.add(keyCode)
		}

		override fun buttonPressed(index: Int) {
			buttonPresses.add(index)
		}

		override fun scanSwitchDown(keyCode: Int) {
			scanSwitchDowns.add(keyCode)
		}

		override fun scanSwitchUp() {
			scanSwitchUps++
		}

		override fun twoSwitchDown(role: String) {
			twoSwitchDowns.add(role)
		}

		override fun twoSwitchUp() {
			twoSwitchUps++
		}

		override fun setTwoSwitchHeld(held: Boolean) {
			twoSwitchHeldStates.add(held)
		}

		override fun joystickInput(x: Float, y: Float) {
			joystickInputs.add(x to y)
		}

		override fun getSwitchCodes() = _switchCodes

		override fun debugLog(message: String) {
			debugLogs.add(message)
		}
	}

	@Before
	fun setUp() {
		testScope = TestScope(StandardTestDispatcher())
		handler = ExternalSwitchHandler(testScope, callbacks)
		// Routing gates on the active method; default both on. Tests that care set one explicitly.
		_isSingleSwitchEnabled = true
		_isTwoSwitchEnabled = true
	}

	// ── KeyEvent / MotionEvent helpers ─────────────────────────────────

	private fun bluetoothKeyDown(
		keyCode: Int,
		eventTime: Long = 100L,
		repeat: Int = 0,
	): KeyEvent = KeyEvent(
		eventTime,
		eventTime,
		KeyEvent.ACTION_DOWN,
		keyCode,
		repeat,
		0,
		1,
		0,
		0,
		InputDevice.SOURCE_KEYBOARD,
	)

	private fun bluetoothKeyUp(
		keyCode: Int,
		eventTime: Long = 100L,
		repeat: Int = 0,
	): KeyEvent = KeyEvent(
		eventTime,
		eventTime,
		KeyEvent.ACTION_UP,
		keyCode,
		repeat,
		0,
		1,
		0,
		0,
		InputDevice.SOURCE_KEYBOARD,
	)

	private fun gamepadKeyDown(
		keyCode: Int,
		repeat: Int = 0,
		eventTime: Long = 100L,
	): KeyEvent = KeyEvent(
		eventTime,
		eventTime,
		KeyEvent.ACTION_DOWN,
		keyCode,
		repeat,
		0,
		2,
		0,
		0,
		InputDevice.SOURCE_GAMEPAD,
	)

	private fun touchscreenKeyEvent(
		action: Int,
		keyCode: Int,
		eventTime: Long = 100L,
	): KeyEvent = KeyEvent(
		eventTime,
		eventTime,
		action,
		keyCode,
		0,
		0,
		3,
		0,
		0,
		InputDevice.SOURCE_TOUCHSCREEN,
	)

	private fun gamepadMotionMove(
		hatX: Float,
		hatY: Float,
		eventTime: Long = 100L,
	): MotionEvent {
		val coords = MotionEvent.PointerCoords().apply {
			setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
			setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
		}
		val props = MotionEvent.PointerProperties().apply {
			id = 0
			toolType = MotionEvent.TOOL_TYPE_UNKNOWN
		}
		return MotionEvent.obtain(
			eventTime,
			eventTime,
			MotionEvent.ACTION_MOVE,
			1,
			arrayOf(props),
			arrayOf(coords),
			0,
			0,
			1f,
			1f,
			0,
			0,
			InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK,
			0,
		)
	}

	// region Group 1 — Pure helpers

	@Test
	fun `directionToIndex maps all 8 directions to 0-7`() {
		assertThat(ExternalSwitchHandler.directionToIndex(GamepadDirectionDetector.Direction.UP_LEFT)).isEqualTo(0)
		assertThat(ExternalSwitchHandler.directionToIndex(GamepadDirectionDetector.Direction.UP)).isEqualTo(1)
		assertThat(ExternalSwitchHandler.directionToIndex(GamepadDirectionDetector.Direction.UP_RIGHT)).isEqualTo(2)
		assertThat(ExternalSwitchHandler.directionToIndex(GamepadDirectionDetector.Direction.LEFT)).isEqualTo(3)
		assertThat(ExternalSwitchHandler.directionToIndex(GamepadDirectionDetector.Direction.RIGHT)).isEqualTo(4)
		assertThat(ExternalSwitchHandler.directionToIndex(GamepadDirectionDetector.Direction.DOWN_LEFT)).isEqualTo(5)
		assertThat(ExternalSwitchHandler.directionToIndex(GamepadDirectionDetector.Direction.DOWN)).isEqualTo(6)
		assertThat(ExternalSwitchHandler.directionToIndex(GamepadDirectionDetector.Direction.DOWN_RIGHT)).isEqualTo(7)
	}

	// endregion

	// region Group 2 — Key event source gating

	@Test
	fun `handleKeyDown returns false for non-keyboard non-gamepad source`() {
		val event = touchscreenKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)
		val result = handler.handleKeyDown(KeyEvent.KEYCODE_1, event)
		assertThat(result).isFalse()
		assertThat(scanSwitchDowns).isEmpty()
		assertThat(buttonPresses).isEmpty()
	}

	@Test
	fun `handleKeyUp returns false for non-keyboard source`() {
		val event = touchscreenKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_1)
		val result = handler.handleKeyUp(KeyEvent.KEYCODE_1, event)
		assertThat(result).isFalse()
		assertThat(scanSwitchUps).isEqualTo(0)
	}

	@Test
	fun `handleGenericMotionEvent returns false for non-gamepad source with no detector`() {
		val coords = MotionEvent.PointerCoords()
		val props = MotionEvent.PointerProperties().apply { id = 0 }
		val event = MotionEvent.obtain(
			100L,
			100L,
			MotionEvent.ACTION_MOVE,
			1,
			arrayOf(props),
			arrayOf(coords),
			0,
			0,
			1f,
			1f,
			0,
			0,
			InputDevice.SOURCE_TOUCHSCREEN,
			0,
		)
		val result = handler.handleGenericMotionEvent(event)
		assertThat(result).isFalse()
		assertThat(buttonPresses).isEmpty()
	}

	// endregion

	// region Group 3 — Settings-mode key capture

	@Test
	fun `key capture intercepts keyboard event when capturing and initialized`() {
		_isCapturingKey = true
		val arbitraryKeyCode = KeyEvent.KEYCODE_F
		val result = handler.handleKeyDown(arbitraryKeyCode, bluetoothKeyDown(arbitraryKeyCode))
		assertThat(result).isTrue()
		assertThat(handleRawKeyCaptures).containsExactly(arbitraryKeyCode)
		assertThat(scanSwitchDowns).isEmpty()
		assertThat(twoSwitchDowns).isEmpty()
	}

	@Test
	fun `key capture skips handleRawKeyCapture for repeat events but still consumes`() {
		_isCapturingKey = true
		val arbitraryKeyCode = KeyEvent.KEYCODE_F
		val result = handler.handleKeyDown(arbitraryKeyCode, bluetoothKeyDown(arbitraryKeyCode, repeat = 1))
		assertThat(result).isTrue()
		assertThat(handleRawKeyCaptures).isEmpty()
	}

	@Test
	fun `key capture path is bypassed when JTUI is not initialized`() {
		_isCapturingKey = true
		_isJtuiInitialized = false
		val result = handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1))
		// Falls through to BT switch path because the capture block is gated by isJtuiInitialized.
		assertThat(handleRawKeyCaptures).isEmpty()
		assertThat(result).isTrue()
		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_1)
	}

	// endregion

	// region Group 4 — Bluetooth switch routing

	@Test
	fun `scan switch keycode routes to scanSwitchDown on key down`() {
		val result = handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1))
		assertThat(result).isTrue()
		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_1)
		assertThat(twoSwitchDowns).isEmpty()
	}

	@Test
	fun `scan switch keycode routes to scanSwitchUp on key up`() {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		val result = handler.handleKeyUp(KeyEvent.KEYCODE_1, bluetoothKeyUp(KeyEvent.KEYCODE_1, eventTime = 200L))
		assertThat(result).isTrue()
		assertThat(scanSwitchUps).isEqualTo(1)
	}

	@Test
	fun `red switch keycode routes to twoSwitchDown with held flag`() {
		handler.handleKeyDown(KeyEvent.KEYCODE_2, bluetoothKeyDown(KeyEvent.KEYCODE_2))
		assertThat(twoSwitchHeldStates).containsExactly(true)
		assertThat(twoSwitchDowns).containsExactly("Red Switch")
	}

	@Test
	fun `green switch keycode routes to twoSwitchDown with role`() {
		handler.handleKeyDown(KeyEvent.KEYCODE_3, bluetoothKeyDown(KeyEvent.KEYCODE_3))
		assertThat(twoSwitchDowns).containsExactly("Green Switch")
		assertThat(twoSwitchHeldStates).containsExactly(true)
	}

	// ── D-pad HAT → switch (scan/two-switch, joystick off) ────────────────

	@Test
	fun `bound d-pad HAT drives two-switch down then up`() {
		_isJoystickMethodActive = false
		_isTwoSwitchEnabled = true
		_isSingleSwitchEnabled = false
		_switchCodes = SwitchCodeConfig(
			scanCode = SWITCH_CODE_UNDEFINED,
			redCode = KeyEvent.KEYCODE_DPAD_RIGHT,
			greenCode = KeyEvent.KEYCODE_DPAD_LEFT,
		)

		val down = handler.handleGenericMotionEvent(gamepadMotionMove(1f, 0f)) // right = red
		val up = handler.handleGenericMotionEvent(gamepadMotionMove(0f, 0f)) // centered = release

		assertThat(down).isTrue()
		assertThat(up).isTrue()
		assertThat(twoSwitchDowns).containsExactly("Red Switch")
		assertThat(twoSwitchHeldStates).containsExactly(true, false)
		assertThat(twoSwitchUps).isEqualTo(1)
	}

	@Test
	fun `bound d-pad HAT drives scan down then up`() {
		_isJoystickMethodActive = false
		_isTwoSwitchEnabled = false
		_isSingleSwitchEnabled = true
		_switchCodes = SwitchCodeConfig(
			scanCode = KeyEvent.KEYCODE_DPAD_UP,
			redCode = SWITCH_CODE_UNDEFINED,
			greenCode = SWITCH_CODE_UNDEFINED,
		)

		handler.handleGenericMotionEvent(gamepadMotionMove(0f, -1f)) // up = scan
		handler.handleGenericMotionEvent(gamepadMotionMove(0f, 0f)) // release

		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_DPAD_UP)
		assertThat(scanSwitchUps).isEqualTo(1)
	}

	@Test
	fun `red-bound d-pad HAT does not drive scan in single-switch mode`() {
		_isJoystickMethodActive = false
		_isTwoSwitchEnabled = false
		_isSingleSwitchEnabled = true
		_switchCodes = SwitchCodeConfig(
			scanCode = KeyEvent.KEYCODE_DPAD_UP,
			redCode = KeyEvent.KEYCODE_DPAD_RIGHT,
			greenCode = KeyEvent.KEYCODE_DPAD_LEFT,
		)

		val handled = handler.handleGenericMotionEvent(gamepadMotionMove(1f, 0f)) // right = red-bound

		assertThat(handled).isFalse()
		assertThat(scanSwitchDowns).isEmpty()
		assertThat(twoSwitchDowns).isEmpty()
	}

	@Test
	fun `unbound d-pad direction does not actuate a switch`() {
		_isJoystickMethodActive = false
		_isTwoSwitchEnabled = true
		_isSingleSwitchEnabled = false
		_switchCodes = SwitchCodeConfig(
			scanCode = SWITCH_CODE_UNDEFINED,
			redCode = KeyEvent.KEYCODE_DPAD_RIGHT,
			greenCode = KeyEvent.KEYCODE_DPAD_LEFT,
		)

		val handled = handler.handleGenericMotionEvent(gamepadMotionMove(0f, -1f)) // up = unbound

		assertThat(handled).isFalse()
		assertThat(twoSwitchDowns).isEmpty()
	}

	@Test
	fun `red switch keycode routes to twoSwitchUp with cleared held flag`() {
		handler.handleKeyDown(KeyEvent.KEYCODE_2, bluetoothKeyDown(KeyEvent.KEYCODE_2, eventTime = 100L))
		handler.handleKeyUp(KeyEvent.KEYCODE_2, bluetoothKeyUp(KeyEvent.KEYCODE_2, eventTime = 200L))
		assertThat(twoSwitchHeldStates).containsExactly(true, false).inOrder()
		assertThat(twoSwitchUps).isEqualTo(1)
	}

	@Test
	fun `single-switch fallback routes unmatched keycode as scan when enabled`() {
		_switchCodes = SwitchCodeConfig(
			scanCode = -1,
			redCode = KeyEvent.KEYCODE_2,
			greenCode = KeyEvent.KEYCODE_3,
		)
		_isSingleSwitchEnabled = true
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1))
		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_1)
		assertThat(twoSwitchDowns).isEmpty()
	}

	@Test
	fun `keycode that is not a configured switch is not consumed`() {
		// Unbound key must return false (not be consumed), not just be ignored.
		_switchCodes = SwitchCodeConfig(scanCode = 100, redCode = 101, greenCode = 102)
		val result = handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1))
		assertThat(result).isFalse()
		assertThat(scanSwitchDowns).isEmpty()
		assertThat(twoSwitchDowns).isEmpty()
	}

	@Test
	fun `numpad keycode is accepted by the BT switch when arm`() {
		_switchCodes = SwitchCodeConfig(
			scanCode = KeyEvent.KEYCODE_NUMPAD_1,
			redCode = KeyEvent.KEYCODE_NUMPAD_2,
			greenCode = KeyEvent.KEYCODE_NUMPAD_3,
		)
		handler.handleKeyDown(KeyEvent.KEYCODE_NUMPAD_1, bluetoothKeyDown(KeyEvent.KEYCODE_NUMPAD_1))
		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_NUMPAD_1)
	}

	@Test
	fun `bound controller button routes as scan switch`() {
		_switchCodes = SwitchCodeConfig(
			scanCode = KeyEvent.KEYCODE_BUTTON_A,
			redCode = -1,
			greenCode = -1,
		)
		val result = handler.handleKeyDown(KeyEvent.KEYCODE_BUTTON_A, bluetoothKeyDown(KeyEvent.KEYCODE_BUTTON_A))
		assertThat(result).isTrue()
		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_BUTTON_A)
	}

	@Test
	fun `bound controller buttons route as red and green two-switch`() {
		_switchCodes = SwitchCodeConfig(
			scanCode = -1,
			redCode = KeyEvent.KEYCODE_BUTTON_A,
			greenCode = KeyEvent.KEYCODE_BUTTON_B,
		)
		handler.handleKeyDown(KeyEvent.KEYCODE_BUTTON_A, bluetoothKeyDown(KeyEvent.KEYCODE_BUTTON_A))
		handler.handleKeyDown(KeyEvent.KEYCODE_BUTTON_B, bluetoothKeyDown(KeyEvent.KEYCODE_BUTTON_B))
		assertThat(twoSwitchDowns).containsExactly("Red Switch", "Green Switch")
	}

	@Test
	fun `scan-bound key does not scan while two-switch is the active method`() {
		_switchCodes = SwitchCodeConfig(scanCode = KeyEvent.KEYCODE_BUTTON_A, redCode = -1, greenCode = -1)
		_isSingleSwitchEnabled = false
		_isTwoSwitchEnabled = true
		handler.handleKeyDown(KeyEvent.KEYCODE_BUTTON_A, bluetoothKeyDown(KeyEvent.KEYCODE_BUTTON_A))
		assertThat(scanSwitchDowns).isEmpty()
		assertThat(twoSwitchDowns).isEmpty()
	}

	@Test
	fun `a code bound to both scan and red scans in single-switch mode`() {
		_switchCodes = SwitchCodeConfig(scanCode = KeyEvent.KEYCODE_BUTTON_A, redCode = KeyEvent.KEYCODE_BUTTON_A, greenCode = KeyEvent.KEYCODE_BUTTON_B)
		_isSingleSwitchEnabled = true
		_isTwoSwitchEnabled = false
		handler.handleKeyDown(KeyEvent.KEYCODE_BUTTON_A, bluetoothKeyDown(KeyEvent.KEYCODE_BUTTON_A))
		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_BUTTON_A)
		assertThat(twoSwitchDowns).isEmpty()
	}

	@Test
	fun `a code bound to both scan and red fires red in two-switch mode`() {
		_switchCodes = SwitchCodeConfig(scanCode = KeyEvent.KEYCODE_BUTTON_A, redCode = KeyEvent.KEYCODE_BUTTON_A, greenCode = KeyEvent.KEYCODE_BUTTON_B)
		_isSingleSwitchEnabled = false
		_isTwoSwitchEnabled = true
		handler.handleKeyDown(KeyEvent.KEYCODE_BUTTON_A, bluetoothKeyDown(KeyEvent.KEYCODE_BUTTON_A))
		assertThat(twoSwitchDowns).containsExactly("Red Switch")
		assertThat(scanSwitchDowns).isEmpty()
	}

	@Test
	fun `handleKeyDown returns false when input view is not shown`() {
		_isInputViewShown = false
		val result = handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1))
		assertThat(result).isFalse()
		assertThat(scanSwitchDowns).isEmpty()
	}

	@Test
	fun `handleKeyUp returns false when input view is not shown`() {
		_isInputViewShown = false
		val result = handler.handleKeyUp(KeyEvent.KEYCODE_1, bluetoothKeyUp(KeyEvent.KEYCODE_1))
		assertThat(result).isFalse()
		assertThat(scanSwitchUps).isEqualTo(0)
	}

	// endregion

	// region Group 5 — Debounce

	@Test
	fun `second key down within debounce window is debounced`() {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 150L))
		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_1)
	}

	@Test
	fun `second key down outside debounce window is accepted`() {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 220L))
		assertThat(scanSwitchDowns).containsExactly(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_1)
	}

	@Test
	fun `down and up debounce maps are tracked separately`() {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 150L))
		handler.handleKeyUp(KeyEvent.KEYCODE_1, bluetoothKeyUp(KeyEvent.KEYCODE_1, eventTime = 150L))
		// Second down debounced; up accepted because lastUpTimeMs starts empty (Long.MIN_VALUE baseline).
		assertThat(scanSwitchDowns).hasSize(1)
		assertThat(scanSwitchUps).isEqualTo(1)
	}

	@Test
	fun `updateSettings disables debounce when set to zero`() {
		handler.updateSettings(debounceMs = 0L, stuckTimeoutMs = 10_000L)
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 101L))
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 102L))
		assertThat(scanSwitchDowns).hasSize(3)
	}

	// endregion

	// region Group 6 — Stuck timeout

	@Test
	fun `scan down without up auto-releases after stuck timeout`() = testScope.runTest {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1))
		runCurrent()
		assertThat(scanSwitchDowns).hasSize(1)
		assertThat(scanSwitchUps).isEqualTo(0)
		advanceTimeBy(11_000)
		runCurrent()
		assertThat(scanSwitchUps).isEqualTo(1)
	}

	@Test
	fun `up event before stuck timeout cancels auto-release`() = testScope.runTest {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		runCurrent()
		advanceTimeBy(5_000)
		handler.handleKeyUp(KeyEvent.KEYCODE_1, bluetoothKeyUp(KeyEvent.KEYCODE_1, eventTime = 5_100L))
		runCurrent()
		assertThat(scanSwitchUps).isEqualTo(1)
		advanceTimeBy(11_000)
		runCurrent()
		assertThat(scanSwitchUps).isEqualTo(1)
	}

	@Test
	fun `second down on same keycode replaces existing stuck timer`() = testScope.runTest {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		runCurrent()
		advanceTimeBy(5_000)
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 5_500L))
		runCurrent()
		assertThat(scanSwitchDowns).hasSize(2)
		advanceTimeBy(11_000)
		runCurrent()
		// Only one auto-release fires (from the second timer); the first was cancelled.
		assertThat(scanSwitchUps).isEqualTo(1)
	}

	@Test
	fun `red switch stuck timeout fires twoSwitchUp with cleared held flag`() = testScope.runTest {
		handler.handleKeyDown(KeyEvent.KEYCODE_2, bluetoothKeyDown(KeyEvent.KEYCODE_2))
		runCurrent()
		advanceTimeBy(11_000)
		runCurrent()
		assertThat(twoSwitchUps).isEqualTo(1)
		assertThat(twoSwitchHeldStates).containsExactly(true, false).inOrder()
	}

	@Test
	fun `cancelAllStuckTimeouts cancels all pending auto-releases`() = testScope.runTest {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		handler.handleKeyDown(KeyEvent.KEYCODE_2, bluetoothKeyDown(KeyEvent.KEYCODE_2, eventTime = 100L))
		runCurrent()
		handler.cancelAllStuckTimeouts()
		advanceTimeBy(11_000)
		runCurrent()
		assertThat(scanSwitchUps).isEqualTo(0)
		assertThat(twoSwitchUps).isEqualTo(0)
	}

	@Test
	fun `destroy cancels all pending auto-releases`() = testScope.runTest {
		handler.handleKeyDown(KeyEvent.KEYCODE_1, bluetoothKeyDown(KeyEvent.KEYCODE_1, eventTime = 100L))
		runCurrent()
		handler.destroy()
		advanceTimeBy(11_000)
		runCurrent()
		assertThat(scanSwitchUps).isEqualTo(0)
	}

	// ── D-pad HAT stuck timeout (controller disconnect mid-hold) ──────────

	private fun bindHatTwoSwitch() {
		_isJoystickMethodActive = false
		_isTwoSwitchEnabled = true
		_isSingleSwitchEnabled = false
		_switchCodes = SwitchCodeConfig(
			scanCode = SWITCH_CODE_UNDEFINED,
			redCode = KeyEvent.KEYCODE_DPAD_RIGHT,
			greenCode = KeyEvent.KEYCODE_DPAD_LEFT,
		)
	}

	@Test
	fun `held HAT switch auto-releases after stuck timeout`() = testScope.runTest {
		bindHatTwoSwitch()
		handler.handleGenericMotionEvent(gamepadMotionMove(1f, 0f)) // red held, then no more events
		runCurrent()
		advanceTimeBy(11_000)
		runCurrent()
		assertThat(twoSwitchUps).isEqualTo(1)
		assertThat(twoSwitchHeldStates).containsExactly(true, false).inOrder()
	}

	@Test
	fun `HAT release after stuck auto-release does not fire a second up`() = testScope.runTest {
		bindHatTwoSwitch()
		handler.handleGenericMotionEvent(gamepadMotionMove(1f, 0f))
		runCurrent()
		advanceTimeBy(11_000)
		runCurrent()
		handler.handleGenericMotionEvent(gamepadMotionMove(0f, 0f)) // real release arrives late
		runCurrent()
		assertThat(twoSwitchUps).isEqualTo(1)
	}

	@Test
	fun `HAT release before stuck timeout cancels auto-release`() = testScope.runTest {
		bindHatTwoSwitch()
		handler.handleGenericMotionEvent(gamepadMotionMove(1f, 0f))
		handler.handleGenericMotionEvent(gamepadMotionMove(0f, 0f))
		runCurrent()
		advanceTimeBy(11_000)
		runCurrent()
		assertThat(twoSwitchUps).isEqualTo(1) // only the real release
	}

	@Test
	fun `held HAT scan switch auto-releases after stuck timeout`() = testScope.runTest {
		_isJoystickMethodActive = false
		_isTwoSwitchEnabled = false
		_isSingleSwitchEnabled = true
		_switchCodes = SwitchCodeConfig(
			scanCode = KeyEvent.KEYCODE_DPAD_UP,
			redCode = SWITCH_CODE_UNDEFINED,
			greenCode = SWITCH_CODE_UNDEFINED,
		)
		handler.handleGenericMotionEvent(gamepadMotionMove(0f, -1f))
		runCurrent()
		advanceTimeBy(11_000)
		runCurrent()
		assertThat(scanSwitchUps).isEqualTo(1)
	}

	// endregion

	// region Group 7 — Gamepad / motion events

	@Test
	fun `dpad keydown maps to buttonPressed via direction lookup`() {
		handler.handleKeyDown(KeyEvent.KEYCODE_DPAD_UP, gamepadKeyDown(KeyEvent.KEYCODE_DPAD_UP))
		assertThat(buttonPresses).containsExactly(1)
	}

	@Test
	fun `dpad keydown with repeat consumes event without buttonPressed`() {
		val result = handler.handleKeyDown(
			KeyEvent.KEYCODE_DPAD_UP,
			gamepadKeyDown(KeyEvent.KEYCODE_DPAD_UP, repeat = 1),
		)
		assertThat(result).isTrue()
		assertThat(buttonPresses).isEmpty()
	}

	@Test
	fun `dpad keydown when JTUI is not initialized still consumes but no callback`() {
		_isJtuiInitialized = false
		val result = handler.handleKeyDown(
			KeyEvent.KEYCODE_DPAD_LEFT,
			gamepadKeyDown(KeyEvent.KEYCODE_DPAD_LEFT),
		)
		assertThat(result).isTrue()
		assertThat(buttonPresses).isEmpty()
	}

	@Test
	fun `gamepad keydown for non-direction keycode returns false`() {
		val event = KeyEvent(
			100L,
			100L,
			KeyEvent.ACTION_DOWN,
			KeyEvent.KEYCODE_BUTTON_A,
			0,
			0,
			2,
			0,
			0,
			InputDevice.SOURCE_GAMEPAD,
		)
		val result = handler.handleKeyDown(KeyEvent.KEYCODE_BUTTON_A, event)
		assertThat(result).isFalse()
		assertThat(buttonPresses).isEmpty()
	}

	@Test
	fun `motion event with cardinal hat axis routes to buttonPressed`() {
		val event = gamepadMotionMove(hatX = -1f, hatY = 0f)
		val result = handler.handleGenericMotionEvent(event)
		assertThat(result).isTrue()
		assertThat(buttonPresses).containsExactly(3)
	}

	@Test
	fun `motion event with diagonal hat axis routes to buttonPressed`() {
		val event = gamepadMotionMove(hatX = 1f, hatY = -1f)
		val result = handler.handleGenericMotionEvent(event)
		assertThat(result).isTrue()
		assertThat(buttonPresses).containsExactly(2)
	}

	@Test
	fun `motion event with both hat axes zero produces no callback`() {
		val event = gamepadMotionMove(hatX = 0f, hatY = 0f)
		val result = handler.handleGenericMotionEvent(event)
		assertThat(result).isFalse()
		assertThat(buttonPresses).isEmpty()
	}

	// endregion

	// region Group 8 — Initialization + lifecycle

	@Test
	fun `initGamepadDetector does not break the hat-axis fallback`() {
		// TODO(Track B / Phase 3.10+): GamepadDirectionDetector is constructed internally with
		// no DI seam. When the detector is injected, expand this test to assert the
		// onContinuousUpdate → callbacks.joystickInput wiring.
		handler.initGamepadDetector(
			GamepadParams(
				deadZone = 0.1f,
				activeZone = 0.5f,
				cardinalWidthDeg = 45f,
				diagonalWidthDeg = 50f,
			),
		)
		// Synthesized MotionEvents have no InputDevice attached, so the detector's
		// `e.device ?: return false` short-circuits to false — verify the fallback hat-axis
		// branch still routes the event correctly.
		val event = gamepadMotionMove(hatX = 1f, hatY = 0f)
		assertThat(handler.handleGenericMotionEvent(event)).isTrue()
		assertThat(buttonPresses).containsExactly(4)
	}

	@Test
	fun `destroy without prior state runs without exception`() {
		handler.destroy()
		assertThat(scanSwitchUps).isEqualTo(0)
		assertThat(twoSwitchUps).isEqualTo(0)
	}

	// endregion
}
