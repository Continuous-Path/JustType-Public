package org.continuouspath.justtype.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.continuouspath.justtype.Constants.ACTION_EXTERNAL_JOYSTICK_INPUT
import org.continuouspath.justtype.Constants.EXTRA_BUTTON_INDEX
import org.continuouspath.justtype.Constants.EXTRA_DIRECTION
import org.continuouspath.justtype.Constants.EXTRA_X
import org.continuouspath.justtype.Constants.EXTRA_Y
import org.continuouspath.justtype.GamepadDirectionDetector

/**
 * BroadcastReceiver that accepts joystick-like input from external apps.
 *
 * Supported input formats (in order of precedence):
 * 1. button_index (Int 0-7) - Direct button press (for compatibility)
 * 2. direction (String) - Direction enum value (for compatibility)
 * 3. x, y (Float) - Raw joystick coordinates (-1.0 to 1.0) - PRIMARY FOR HEAD TRACKING
 *
 * Broadcast action: org.continuouspath.justtype.EXTERNAL_JOYSTICK_INPUT
 */
class ExternalInputReceiver(
	private val onButtonIndexReceived: ((buttonIndex: Int) -> Unit)? = null,
	private val onCoordinatesReceived: ((x: Float, y: Float) -> Unit)? = null,
	private val onFrameTimestamp: ((timestampMs: Long) -> Unit)? = null,
	private val onFrameSeq: ((seq: Long) -> Unit)? = null,
) : BroadcastReceiver() {

	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		if (intent.action != ACTION_EXTERNAL_JOYSTICK_INPUT) return

//        Log.d("ExternalInputReceiver", "Received external joystick input intent")

		// Priority 1: Raw x/y coordinates (for head tracking)
		if (intent.hasExtra(EXTRA_X) && intent.hasExtra(EXTRA_Y)) {
			val x = intent.getFloatExtra(EXTRA_X, 0f)
			val y = intent.getFloatExtra(EXTRA_Y, 0f)
			val frameTs = intent.getLongExtra("frameTs", 0L)
			val frameSeq = intent.getLongExtra("frameSeq", 0L)
			onCoordinatesReceived?.invoke(x, y)
			if (frameTs > 0L) {
				onFrameTimestamp?.invoke(frameTs)
			}
			if (frameSeq > 0L) {
				onFrameSeq?.invoke(frameSeq)
			}
			return
		}

		// Priority 2: Direct button index (for compatibility)
		if (intent.hasExtra(EXTRA_BUTTON_INDEX)) {
			val buttonIndex = intent.getIntExtra(EXTRA_BUTTON_INDEX, -1)
			if (buttonIndex in 0..7) {
				onButtonIndexReceived?.invoke(buttonIndex)
				return
			}
		}

		// Priority 3: Direction enum (for compatibility)
		if (intent.hasExtra(EXTRA_DIRECTION)) {
			val directionStr = intent.getStringExtra(EXTRA_DIRECTION)?.uppercase()
			val direction = parseDirection(directionStr)
			if (direction != null) {
				val buttonIndex = directionToIndex(direction)
				onButtonIndexReceived?.invoke(buttonIndex)
				return
			}
		}
	}

	private fun parseDirection(directionStr: String?): GamepadDirectionDetector.Direction? = when (directionStr) {
		"UP" -> GamepadDirectionDetector.Direction.UP
		"DOWN" -> GamepadDirectionDetector.Direction.DOWN
		"LEFT" -> GamepadDirectionDetector.Direction.LEFT
		"RIGHT" -> GamepadDirectionDetector.Direction.RIGHT
		"UP_LEFT", "UPLEFT" -> GamepadDirectionDetector.Direction.UP_LEFT
		"UP_RIGHT", "UPRIGHT" -> GamepadDirectionDetector.Direction.UP_RIGHT
		"DOWN_LEFT", "DOWNLEFT" -> GamepadDirectionDetector.Direction.DOWN_LEFT
		"DOWN_RIGHT", "DOWNRIGHT" -> GamepadDirectionDetector.Direction.DOWN_RIGHT
		else -> null
	}

	private fun directionToIndex(dir: GamepadDirectionDetector.Direction): Int = when (dir) {
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
