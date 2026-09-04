package org.continuouspath.justtype

/**
 * State machine for joystick input processing with zone-based activation.
 * Manages zones (DEAD, FEEDBACK, ACTIVATION, optional EXIT) and key highlighting/activation logic.
 *
 * Zones (from center outward):
 * - DEAD: Center region (below deadZone), no key selected
 * - FEEDBACK: Key is highlighted yellow (between deadZone and activeZone)
 * - ACTIVATION: Key is locked and highlighted green (above activeZone)
 * - EXIT: Past activation ring (above exitZone); abandons activation, signals exit gesture
 *
 * Behavior:
 * - DEAD zone: All keys WHITE
 * - Leave DEAD zone into sector N: Key N lights YELLOW
 * - Move to sector M while in FEEDBACK: Key N returns WHITE, key M lights YELLOW
 * - Enter ACTIVATION from sector N: Key N locks, turns PALE GREEN, then triggers activation
 * - Move to neighboring key while in ACTIVATION: All keys WHITE until below activation zone
 * - Drop below ACTIVATION: Current sector's key turns YELLOW
 * - Enter EXIT: All keys WHITE, lock cleared (no stray activation on pass-through to FEEDBACK)
 */
class JoystickState(
	private val deadZone: Float,
	private val activeZone: Float,
	cornerBias: Float = 1.35f,
	private val exitZone: Float = Float.MAX_VALUE,
) {
	init {
		require(exitZone > activeZone || exitZone == Float.MAX_VALUE) {
			"exitZone ($exitZone) must exceed activeZone ($activeZone), or be Float.MAX_VALUE (disabled)"
		}
	}

	enum class Zone {
		DEAD,
		FEEDBACK,
		ACTIVATION,
		EXIT,
	}

	private companion object {
		// Magnitude must fall this far below activeZone to leave ACTIVATION, so jitter around the
		// boundary can't flip the zone (and re-fire the locked key) repeatedly.
		const val REARM_HYSTERESIS = 0.05f
	}

	/**
	 * Highlight state for UI rendering
	 */
	enum class HighlightState {
		NONE, // WHITE (no highlight)
		YELLOW, // YELLOW (feedback zone)
		PALE_GREEN, // PALE PASTEL GREEN (just entered activation)
		DARK_GREEN, // DARK GREEN (locked in activation)
	}

	private val cornerBiasClamped = cornerBias.coerceIn(0.5f, 2.0f)

	private var currentOctant: Int? = null
	private var currentZone: Zone = Zone.DEAD
	private var lastMagnitude: Float = 0f

	// Key locking - locks octant when entering activation zone
	private var lockedOctant: Int? = null

	// Track if cursor moved to different octant while in activation zone
	private var movedToNeighborInActivation: Boolean = false

	// Track if we've just entered activation (for initial pale green state)
	private var justEnteredActivation: Boolean = false

	data class ProcessResult(
		val octant: Int?, // Current octant (0-7) or null for dead zone
		val zone: Zone, // Current zone
		val highlightState: HighlightState, // What color the highlighted key should be
		val highlightOctant: Int?, // Which octant to highlight (may differ from current)
		val shouldActivate: Boolean, // True when key should be activated
		val activatedOctant: Int?, // Which octant was activated (only when shouldActivate=true)
		val justEnteredActivation: Boolean, // True on the first frame entering activation
		val movedToNeighborInActivation: Boolean, // True if user moved to neighbor while locked
		val isInExitZone: Boolean = false, // True when magnitude is in EXIT zone (above exitZone)
	)

	/**
	 * Process new coordinates and return state update.
	 */
	fun process(x: Float, y: Float): ProcessResult {
		val magnitude = kotlin.math.hypot(x.toDouble(), y.toDouble()).toFloat()
		val previousZone = currentZone
		lastMagnitude = magnitude

		// Calculate angle and octant
		val angleDeg = Math.toDegrees(kotlin.math.atan2(-y.toDouble(), x.toDouble())).toFloat()
		val direction = angleToEightWay(angleDeg)
		val octant = directionToIndex(direction)

		currentOctant = octant

		// Determine current zone (order matters — EXIT must be checked before ACTIVATION).
		// Hysteresis on the activation boundary: once in ACTIVATION, stay until magnitude drops a small
		// band below activeZone. Without it, jitter around the threshold flips ACTIVATION<->FEEDBACK and
		// each drop-to-feedback can fire the locked key again — a double-type. The drop-below-to-commit
		// still works: FEEDBACK is entered once magnitude falls under (activeZone - band).
		val activationFloor = if (previousZone == Zone.ACTIVATION) activeZone - REARM_HYSTERESIS else activeZone
		currentZone = when {
			magnitude < deadZone -> Zone.DEAD
			magnitude >= exitZone -> Zone.EXIT
			magnitude >= activationFloor -> Zone.ACTIVATION
			else -> Zone.FEEDBACK
		}

		var shouldActivate = false
		var activatedOctant: Int? = null
		var highlightState = HighlightState.NONE
		var highlightOctant: Int? = null
		val wasJustEnteredActivation = justEnteredActivation
		justEnteredActivation = false

		when (currentZone) {
			Zone.DEAD -> {
				// Reset all state when entering dead zone
				if (lockedOctant != null) {
					// We had a locked key - don't activate on drop to dead zone
					lockedOctant = null
				}
				movedToNeighborInActivation = false
				highlightState = HighlightState.NONE
				highlightOctant = null
			}

			Zone.FEEDBACK -> {
				if (previousZone == Zone.ACTIVATION && !movedToNeighborInActivation) {
					// Dropping from activation zone - activate the locked key
					if (lockedOctant != null) {
						shouldActivate = true
						activatedOctant = lockedOctant
					}
					lockedOctant = null
				}

				// Reset moved-to-neighbor flag when we're back in feedback
				if (movedToNeighborInActivation) {
					movedToNeighborInActivation = false
					lockedOctant = null
				}

				// In feedback zone - highlight current octant yellow
				highlightState = HighlightState.YELLOW
				highlightOctant = octant
			}

			Zone.ACTIVATION -> {
				if (previousZone != Zone.ACTIVATION) {
					// Just entered activation zone - lock the octant
					lockedOctant = octant
					movedToNeighborInActivation = false
					justEnteredActivation = true
					highlightState = HighlightState.PALE_GREEN
					highlightOctant = octant
				} else {
					// Already in activation zone
					if (lockedOctant != null && octant != lockedOctant && !movedToNeighborInActivation) {
						// Moved to a different octant while in activation - invalidate
						movedToNeighborInActivation = true
					}

					if (movedToNeighborInActivation) {
						// User moved to neighbor - all keys WHITE
						highlightState = HighlightState.NONE
						highlightOctant = null
					} else {
						// Still on locked octant - dark green (or pale if just entered)
						highlightState = if (wasJustEnteredActivation) HighlightState.PALE_GREEN else HighlightState.DARK_GREEN
						highlightOctant = lockedOctant
					}
				}
			}

			Zone.EXIT -> {
				// Invariant: EXIT must clear lockedOctant. Otherwise the
				// previousZone==ACTIVATION branch in FEEDBACK (above) re-fires
				// activation on pass-through back to DEAD, undoing the "deliberate
				// exit gesture" semantic.
				lockedOctant = null
				movedToNeighborInActivation = false
				highlightState = HighlightState.NONE
				highlightOctant = null
			}
		}

		return ProcessResult(
			octant = currentOctant,
			zone = currentZone,
			highlightState = highlightState,
			highlightOctant = highlightOctant,
			shouldActivate = shouldActivate,
			activatedOctant = activatedOctant,
			justEnteredActivation = justEnteredActivation,
			movedToNeighborInActivation = movedToNeighborInActivation,
			isInExitZone = currentZone == Zone.EXIT,
		)
	}

	private fun angleToEightWay(angle: Float): GamepadDirectionDetector.Direction {
		val a = wrapDeg(angle)

		// Sector widths derived from corner bias (cardinal + diagonal = 90 degrees)
		val cardinalWidthDeg = 90f / (1f + cornerBiasClamped)
		val diagonalWidthDeg = 90f - cardinalWidthDeg

		val sectors = listOf(
			Sector(GamepadDirectionDetector.Direction.RIGHT, 0f, cardinalWidthDeg / 2),
			Sector(GamepadDirectionDetector.Direction.UP_RIGHT, 45f, diagonalWidthDeg / 2),
			Sector(GamepadDirectionDetector.Direction.UP, 90f, cardinalWidthDeg / 2),
			Sector(GamepadDirectionDetector.Direction.UP_LEFT, 135f, diagonalWidthDeg / 2),
			Sector(GamepadDirectionDetector.Direction.LEFT, 180f, cardinalWidthDeg / 2),
			Sector(GamepadDirectionDetector.Direction.DOWN_LEFT, 225f, diagonalWidthDeg / 2),
			Sector(GamepadDirectionDetector.Direction.DOWN, 270f, cardinalWidthDeg / 2),
			Sector(GamepadDirectionDetector.Direction.DOWN_RIGHT, 315f, diagonalWidthDeg / 2),
		)

		val hits = sectors.filter { it.contains(a, ::wrapDeg, ::inRangeWrap) }
		if (hits.size == 1) return hits.first().dir
		if (hits.size > 1) {
			return hits.minBy { angDistDeg(a, it.center) }.dir
		}
		return sectors.minBy { angDistDeg(a, it.center) }.dir
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

	private data class Sector(
		val dir: GamepadDirectionDetector.Direction,
		val center: Float,
		val halfWidth: Float,
	) {
		fun contains(a: Float, wrapper: (Float) -> Float, rangeWrapper: (Float, Float, Float) -> Boolean): Boolean {
			val start = wrapper(center - halfWidth)
			val end = wrapper(center + halfWidth)
			return rangeWrapper(a, start, end)
		}
	}

	private fun wrapDeg(d: Float): Float {
		var x = d % 360f
		if (x < 0) x += 360f
		return x
	}

	private fun inRangeWrap(a: Float, start: Float, end: Float): Boolean = if (start <= end) a in start..end else (a >= start || a <= end)

	private fun angDistDeg(a: Float, b: Float): Float {
		val diff = kotlin.math.abs(wrapDeg(a) - wrapDeg(b))
		return kotlin.math.min(diff, 360f - diff)
	}

	fun reset() {
		currentOctant = null
		currentZone = Zone.DEAD
		lastMagnitude = 0f
		lockedOctant = null
		movedToNeighborInActivation = false
		justEnteredActivation = false
	}
}
