package org.continuouspath.justtype.ime

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants.KEY_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACCEPT_ANY
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEADZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEVICE_DESCRIPTOR
import org.continuouspath.justtype.GamepadDirectionDetector
import org.continuouspath.justtype.input.HatSwitchCodes
import org.continuouspath.justtype.input.HatSwitchEdgeDetector
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getFloat
import kotlin.math.max

/**
 * Handles external switch input: Bluetooth switches (1/2/3 keys), gamepad D-pad,
 * and analog stick motion events. Routes events to the correct input subsystem
 * (scan, two-switch, joystick) via [ExternalSwitchCallbacks].
 *
 * Extracted from JustTypeIME (Phase 2 Step 9).
 */
class ExternalSwitchHandler(
	private val scope: CoroutineScope,
	private val callbacks: ExternalSwitchCallbacks,
) {
	// ── Debounce state ────────────────────────────────────────────────

	private var debounceMs: Long = 120L
	private val lastDownTimeMs = mutableMapOf<Int, Long>()
	private val lastUpTimeMs = mutableMapOf<Int, Long>()

	// ── Stuck switch timeout ──────────────────────────────────────────

	private var stuckTimeoutMs: Long = 10_000L
	private val stuckTimeoutJobs = mutableMapOf<Int, Job>()

	// ── Gamepad ───────────────────────────────────────────────────────

	private var gamepadDetector: GamepadDirectionDetector? = null

	// Turns the d-pad HAT stream (no key-up) into paired switch down/up when scan/two-switch is active.
	private val hatSwitchEdges = HatSwitchEdgeDetector()

	// ── Public API ────────────────────────────────────────────────────

	fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
		if (callbacks.isSwitchInputLoggingEnabled) {
			android.util.Log.d(
				"SwitchProbe",
				"IME handleKeyDown code=$keyCode extKbd=${isExternalKeyboardSource(event)} " +
					"viewShown=${callbacks.isInputViewShown} capturing=${callbacks.isCapturingKey}",
			)
		}
		// If Settings Mode is capturing a switch assignment, intercept ANY key code
		if (isExternalKeyboardSource(event) && callbacks.isJtuiInitialized && callbacks.isCapturingKey) {
			if (event.repeatCount == 0) {
				callbacks.handleRawKeyCapture(keyCode)
			}
			return true
		}

		// Accept whatever code the user bound (scan/red/green), not just the legacy 1/2/3.
		if (isExternalKeyboardSource(event)) {
			if (!callbacks.isInputViewShown) return false
			if (isConfiguredSwitchCode(keyCode)) {
				val eventTime = eventTimeOrNow(event)
				if (
					event.repeatCount == 0 &&
					!shouldDebounce(keyCode, eventTime, lastDownTimeMs, "down")
				) {
					handleBluetoothSwitch(keyCode, event)
				}
				return true
			}
		}

		if (isGamepadSource(event.source) && callbacks.isJoystickMethodActive) {
			val dir = when (keyCode) {
				KeyEvent.KEYCODE_DPAD_UP -> GamepadDirectionDetector.Direction.UP
				KeyEvent.KEYCODE_DPAD_DOWN -> GamepadDirectionDetector.Direction.DOWN
				KeyEvent.KEYCODE_DPAD_LEFT -> GamepadDirectionDetector.Direction.LEFT
				KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadDirectionDetector.Direction.RIGHT
				KeyEvent.KEYCODE_DPAD_UP_LEFT -> GamepadDirectionDetector.Direction.UP_LEFT
				KeyEvent.KEYCODE_DPAD_UP_RIGHT -> GamepadDirectionDetector.Direction.UP_RIGHT
				KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> GamepadDirectionDetector.Direction.DOWN_LEFT
				KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> GamepadDirectionDetector.Direction.DOWN_RIGHT
				else -> null
			}
			if (dir != null) {
				if (event.repeatCount == 0 && callbacks.isJtuiInitialized) {
					val index = directionToIndex(dir)
					callbacks.buttonPressed(index)
				}
				return true
			}
		}
		return false
	}

	fun handleKeyUp(keyCode: Int, event: KeyEvent): Boolean {
		if (isExternalKeyboardSource(event)) {
			if (!callbacks.isInputViewShown) return false
			if (isConfiguredSwitchCode(keyCode)) {
				val eventTime = eventTimeOrNow(event)
				if (
					event.repeatCount == 0 &&
					!shouldDebounce(keyCode, eventTime, lastUpTimeMs, "up")
				) {
					handleBluetoothSwitch(keyCode, event)
				}
				return true
			}
		}
		return false
	}

	/**
	 * True if [keyCode] should drive a switch: a code the user bound (scan/red/green), or —
	 * when nothing is bound yet — the legacy default switch keys so first-time/unconfigured
	 * single-switch still works out of the box.
	 */
	private fun isConfiguredSwitchCode(keyCode: Int): Boolean {
		val codes = callbacks.getSwitchCodes()
		if (keyCode == codes.scanCode || keyCode == codes.redCode || keyCode == codes.greenCode) {
			return true
		}
		return codes.scanCode == SWITCH_CODE_UNDEFINED &&
			callbacks.isSingleSwitchEnabled &&
			keyCode in LEGACY_SWITCH_KEYCODES
	}

	fun handleGenericMotionEvent(event: MotionEvent): Boolean {
		// Scan / two-switch: a bound d-pad direction acts as a switch. The HAT streams while held with
		// no key-up, so edge-detect it into paired down/up (mirrors the Nav overlay). Joystick mode
		// falls through so the analog stick keeps driving directional selection.
		if (!callbacks.isJoystickMethodActive) return routeHatSwitch(event)
		if (gamepadDetector?.handleMotionEvent(event) == true) return true

		if (event.action == MotionEvent.ACTION_MOVE && isGamepadSource(event.source)) {
			val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			if (hatX != 0f || hatY != 0f) {
				val dir = when {
					hatY < 0f && hatX == 0f -> GamepadDirectionDetector.Direction.UP
					hatY > 0f && hatX == 0f -> GamepadDirectionDetector.Direction.DOWN
					hatX < 0f && hatY == 0f -> GamepadDirectionDetector.Direction.LEFT
					hatX > 0f && hatY == 0f -> GamepadDirectionDetector.Direction.RIGHT
					hatY < 0f && hatX < 0f -> GamepadDirectionDetector.Direction.UP_LEFT
					hatY < 0f && hatX > 0f -> GamepadDirectionDetector.Direction.UP_RIGHT
					hatY > 0f && hatX < 0f -> GamepadDirectionDetector.Direction.DOWN_LEFT
					hatY > 0f && hatX > 0f -> GamepadDirectionDetector.Direction.DOWN_RIGHT
					else -> null
				}
				if (dir != null && callbacks.isJtuiInitialized) {
					val index = directionToIndex(dir)
					callbacks.buttonPressed(index)
					return true
				}
			}
		}
		return false
	}

	/**
	 * Edge-detect a d-pad HAT motion into a switch down/up for scan/two-switch. Only a direction
	 * that actually drives the active method actuates; other directions are ignored so a stray
	 * release can't restart an active cycle.
	 */
	private fun routeHatSwitch(event: MotionEvent): Boolean {
		if (!callbacks.isInputViewShown) return false
		if (event.action != MotionEvent.ACTION_MOVE || !isGamepadSource(event.source)) return false
		var handled = false
		hatSwitchEdges.onHatCode(
			code = HatSwitchCodes.hatToDpadKeyCode(event),
			actuates = { code -> isConfiguredSwitchCode(code) && activeSwitchRole(code) != null },
			onDown = { code ->
				switchDown(code)
				handled = true
			},
			onUp = { code ->
				switchUp(code)
				handled = true
			},
		)
		return handled
	}

	fun cancelAllStuckTimeouts() {
		stuckTimeoutJobs.values.forEach { it.cancel() }
		stuckTimeoutJobs.clear()
	}

	fun initGamepadDetector(
		params: GamepadParams,
		acceptDevice: (InputDevice) -> Boolean = { true },
	) {
		gamepadDetector = GamepadDirectionDetector(
			deadZone = params.deadZone,
			activeZone = params.activeZone,
			stickMode = GamepadDirectionDetector.StickMode.BOTH,
			acceptDevice = acceptDevice,
			cardinalWidthDeg = params.cardinalWidthDeg,
			diagonalWidthDeg = params.diagonalWidthDeg,
			onDirectionChanged = { _ ->
				// Not used in continuous mode
			},
			onContinuousUpdate = { x, y ->
				callbacks.joystickInput(x, y)
			},
		)
	}

	fun updateSettings(debounceMs: Long, stuckTimeoutMs: Long) {
		this.debounceMs = debounceMs
		this.stuckTimeoutMs = stuckTimeoutMs
	}

	fun destroy() {
		cancelAllStuckTimeouts()
	}

	// ── Internal ──────────────────────────────────────────────────────

	private fun eventTimeOrNow(event: KeyEvent): Long = event.eventTime.takeIf { it > 0 } ?: SystemClock.uptimeMillis()

	private fun shouldDebounce(
		keyCode: Int,
		eventTime: Long,
		lastAcceptedMap: MutableMap<Int, Long>,
		actionLabel: String,
	): Boolean {
		val last = lastAcceptedMap[keyCode] ?: Long.MIN_VALUE
		val delta = eventTime - last
		if (delta in 0 until debounceMs) {
			callbacks.debugLog("[extSwitch] Debounce $actionLabel for keyCode=$keyCode; delta=${delta}ms < ${debounceMs}ms")
			return true
		}
		lastAcceptedMap[keyCode] = eventTime
		return false
	}

	/**
	 * Central handler for external Bluetooth switch/keyboard events (keys "1", "2", "3").
	 * Called only after per-action debouncing in handleKeyDown/handleKeyUp.
	 */
	private fun handleBluetoothSwitch(keyCode: Int, event: KeyEvent) {
		val action = when (event.action) {
			KeyEvent.ACTION_DOWN -> "down"
			KeyEvent.ACTION_UP -> "up"
			else -> "other"
		}
		callbacks.debugLog(
			"[handleBluetoothSwitchEvent] keyCode=$keyCode action=$action repeat=${event.repeatCount} " +
				"role=${activeSwitchRole(keyCode)} debounceMs=$debounceMs",
		)
		when (action) {
			"down" -> switchDown(keyCode)
			"up" -> switchUp(keyCode)
		}
	}

	/**
	 * Resolve what [keyCode] drives under the ACTIVE method — [ROLE_SCAN], [ROLE_RED],
	 * [ROLE_GREEN], or null. Not a fixed scan-first precedence: the same code may be bound to
	 * both scan and a two-switch (they never run at once), so a scan-first `when` would
	 * mis-route it to scan even in two-switch mode. Single-switch also keeps the unbound-scan
	 * fallback when no scan code is set. Callers gate on [isConfiguredSwitchCode] first.
	 */
	private fun activeSwitchRole(keyCode: Int): String? {
		val codes = callbacks.getSwitchCodes()
		val twoSwitchRole = when (keyCode) {
			codes.redCode -> ROLE_RED
			codes.greenCode -> ROLE_GREEN
			else -> null
		}
		val scanFires = callbacks.isSingleSwitchEnabled &&
			(keyCode == codes.scanCode || (codes.scanCode == SWITCH_CODE_UNDEFINED && twoSwitchRole == null))
		return when {
			scanFires -> ROLE_SCAN
			callbacks.isTwoSwitchEnabled && twoSwitchRole != null -> twoSwitchRole
			else -> null
		}
	}

	/** Switch DOWN (BT key or HAT edge): arm the stuck timeout + dispatch to the active method. */
	private fun switchDown(keyCode: Int) {
		val role = activeSwitchRole(keyCode) ?: return
		startStuckTimeout(keyCode, role)
		if (role == ROLE_SCAN) {
			callbacks.scanSwitchDown(keyCode)
		} else {
			callbacks.setTwoSwitchHeld(true)
			callbacks.twoSwitchDown(role)
		}
	}

	/** Switch UP (BT key or HAT edge): cancel the stuck timeout + dispatch to the active method. */
	private fun switchUp(keyCode: Int) {
		cancelStuckTimeout(keyCode)
		val role = activeSwitchRole(keyCode) ?: return
		if (role == ROLE_SCAN) {
			callbacks.scanSwitchUp()
		} else {
			callbacks.setTwoSwitchHeld(false)
			callbacks.twoSwitchUp()
		}
	}

	private fun startStuckTimeout(keyCode: Int, role: String) {
		cancelStuckTimeout(keyCode)
		stuckTimeoutJobs[keyCode] = scope.launch {
			delay(stuckTimeoutMs)
			callbacks.debugLog("[extSwitch] Stuck timeout for keyCode=$keyCode role=$role after ${stuckTimeoutMs}ms; auto-releasing")
			if (role == ROLE_SCAN) {
				callbacks.scanSwitchUp()
			} else {
				callbacks.setTwoSwitchHeld(false)
				callbacks.twoSwitchUp()
			}
			// A HAT hold gets no more motion events after a controller disconnect; drop the edge
			// detector's held state so the release already fired here can't fire again.
			hatSwitchEdges.clearIfHeld(keyCode)
			stuckTimeoutJobs.remove(keyCode)
		}
	}

	private fun cancelStuckTimeout(keyCode: Int) {
		stuckTimeoutJobs.remove(keyCode)?.cancel()
	}

	private fun isGamepadSource(src: Int): Boolean = (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
		(src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

	/**
	 * Checks if the key event is from an external keyboard source (e.g., Bluetooth keyboard/switch).
	 * In an InputMethodService, KeyEvents with SOURCE_KEYBOARD are from physical/external keyboards,
	 * not from the virtual keyboard (which uses InputConnection methods instead).
	 */
	private fun isExternalKeyboardSource(event: KeyEvent): Boolean {
		val source = event.source
		return (source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
	}

	companion object {
		private const val SWITCH_CODE_UNDEFINED = -1

		private const val ROLE_SCAN = "Scan Switch"
		private const val ROLE_RED = "Red Switch"
		private const val ROLE_GREEN = "Green Switch"

		/** Default switch keys honored only when the user hasn't bound any switch code. */
		private val LEGACY_SWITCH_KEYCODES = setOf(
			KeyEvent.KEYCODE_1,
			KeyEvent.KEYCODE_2,
			KeyEvent.KEYCODE_3,
			KeyEvent.KEYCODE_NUMPAD_1,
			KeyEvent.KEYCODE_NUMPAD_2,
			KeyEvent.KEYCODE_NUMPAD_3,
		)

		/** Maps a [GamepadDirectionDetector.Direction] to a button index (0-7). */
		fun directionToIndex(dir: GamepadDirectionDetector.Direction): Int = when (dir) {
			GamepadDirectionDetector.Direction.UP_LEFT -> 0
			GamepadDirectionDetector.Direction.UP -> 1
			GamepadDirectionDetector.Direction.UP_RIGHT -> 2
			GamepadDirectionDetector.Direction.LEFT -> 3
			GamepadDirectionDetector.Direction.RIGHT -> 4
			GamepadDirectionDetector.Direction.DOWN_LEFT -> 5
			GamepadDirectionDetector.Direction.DOWN -> 6
			GamepadDirectionDetector.Direction.DOWN_RIGHT -> 7
		}
	}
}

/** Immutable snapshot of switch code assignments from settings. */
data class SwitchCodeConfig(
	val scanCode: Int,
	val redCode: Int,
	val greenCode: Int,
)

/** Parameters for [GamepadDirectionDetector] initialization. */
data class GamepadParams(
	val deadZone: Float,
	val activeZone: Float,
	val cardinalWidthDeg: Float,
	val diagonalWidthDeg: Float,
) {
	companion object {
		/**
		 * Build params from joystick settings. Shared by the IME and the nav overlay so
		 * the deadzone/activezone clamping + corner-bias→sector-width math stay in sync.
		 */
		fun fromSettings(repo: SettingsRepository): GamepadParams {
			val dz = repo.getFloat(KEY_JOYSTICK_DEADZONE)
			val azRaw = repo.getFloat(KEY_JOYSTICK_ACTIVEZONE)
			val az = max(azRaw, dz + 0.01f).coerceAtMost(0.99f)
			val cornerBias = if (repo.contains(KEY_JOYSTICK_CORNER_BIAS)) {
				repo.getFloat(KEY_JOYSTICK_CORNER_BIAS)
			} else {
				val legacy = repo.getFloat(KEY_CORNER_BIAS, 1.35f)
				repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, legacy)
				legacy
			}.coerceIn(0.5f, 2.0f)
			val cardinalWidth = 90f / (1f + cornerBias)
			return GamepadParams(
				deadZone = dz,
				activeZone = az,
				cardinalWidthDeg = cardinalWidth,
				diagonalWidthDeg = 90f - cardinalWidth,
			)
		}

		/**
		 * Device gate: matches the user's selected joystick, or every device when "accept any"
		 * is on / nothing bound. Read once at construction — reconstruct the detector to re-read.
		 */
		fun deviceFilterFromSettings(repo: SettingsRepository): (InputDevice) -> Boolean {
			val acceptAny = repo.getBoolean(KEY_JOYSTICK_ACCEPT_ANY, true)
			val selected = repo.getString(KEY_JOYSTICK_DEVICE_DESCRIPTOR, "")
			if (acceptAny || selected.isEmpty()) return { true }
			return { device -> device.descriptor == selected }
		}
	}
}

/**
 * Reverse-communication interface from [ExternalSwitchHandler] back to JustTypeIME.
 */
interface ExternalSwitchCallbacks {
	// ── JTUI state queries ────────────────────────────────────────────
	val isInputViewShown: Boolean
	val isJtuiInitialized: Boolean
	val isCapturingKey: Boolean

	/** True when joystick is the selected input method — gates analog-stick + gamepad-dpad input. */
	val isJoystickMethodActive: Boolean

	/** Dev toggle: emit SwitchProbe input-routing logs. */
	val isSwitchInputLoggingEnabled: Boolean

	// ── JTUI commands ─────────────────────────────────────────────────
	fun handleRawKeyCapture(keyCode: Int)
	fun buttonPressed(index: Int)

	// ── Subsystem routing ─────────────────────────────────────────────
	fun scanSwitchDown(keyCode: Int)
	fun scanSwitchUp()
	fun twoSwitchDown(role: String)
	fun twoSwitchUp()
	fun setTwoSwitchHeld(held: Boolean)
	fun joystickInput(x: Float, y: Float)

	// ── Settings ──────────────────────────────────────────────────────
	fun getSwitchCodes(): SwitchCodeConfig
	val isSingleSwitchEnabled: Boolean
	val isTwoSwitchEnabled: Boolean

	// ── Debug ─────────────────────────────────────────────────────────
	fun debugLog(message: String)
}
