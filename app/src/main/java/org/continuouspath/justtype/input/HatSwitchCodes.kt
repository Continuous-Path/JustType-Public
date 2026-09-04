package org.continuouspath.justtype.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED

/**
 * Maps a d-pad HAT axis to a `KEYCODE_DPAD_*` code so the d-pad can drive switch roles
 * from motion events. A gamepad d-pad arrives as HAT-axis motion, not KeyEvents, so it
 * can't come through `onKeyEvent` the way face buttons and keyboard keys do.
 *
 * Cardinals only — diagonals and center resolve to [SWITCH_CODE_UNDEFINED], so a
 * single binding is one direction and held/repeating HAT motion debounces cleanly.
 */
object HatSwitchCodes {

	fun hatToDpadKeyCode(hatX: Float, hatY: Float): Int = when {
		hatY < 0f && hatX == 0f -> KeyEvent.KEYCODE_DPAD_UP
		hatY > 0f && hatX == 0f -> KeyEvent.KEYCODE_DPAD_DOWN
		hatX < 0f && hatY == 0f -> KeyEvent.KEYCODE_DPAD_LEFT
		hatX > 0f && hatY == 0f -> KeyEvent.KEYCODE_DPAD_RIGHT
		else -> SWITCH_CODE_UNDEFINED
	}

	/** Convenience for callers holding a [MotionEvent] from a gamepad/joystick source. */
	fun hatToDpadKeyCode(event: MotionEvent): Int = hatToDpadKeyCode(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y))
}
