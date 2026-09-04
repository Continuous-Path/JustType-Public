package org.continuouspath.justtype

/**
 * State machine for head tracking input processing.
 * Manages zones (DEAD, FEEDBACK, ACTIVATION, EXIT, RESET) and activation logic.
 *
 * Zones (from center outward):
 * - DEAD: Center region, no key selected
 * - FEEDBACK: Key is highlighted (yellow), not yet ready to activate
 * - ACTIVATION: Key is locked and highlighted pale green, ready to activate on return
 * - EXIT: Beyond activation zone, triggers exit delay timer for pop-out/pause behaviors
 * - RESET: Transitional zone when returning from activation without activating
 *
 * Behavior:
 * - Leave DEAD zone into sector N: Key N lights YELLOW
 * - Move to sector M while in FEEDBACK: Key N -> WHITE, Key M -> YELLOW
 * - Enter ACTIVATION from sector N: Key N lights PALE GREEN, octant locks
 * - Move to sector M while in ACTIVATION: Key N remains PALE GREEN (NO CHANGE)
 * - Cursor returns below activation threshold: Beep (if enabled), key turns DARK GREEN
 * - Key remains DARK GREEN while cursor stays in activation/exit zone
 * - Drop below ACTIVATION zone: All highlights cleared, current sector's key turns YELLOW
 */
class HeadTrackingState(
	private val deadZone: Float,
	private val activeZone: Float,
	private val exitZone: Float = 1.0f,
	private val keyActThreshold: Float = 0.10f, // 0.0 = activate immediately on return, 1.0 = activate at activation zone boundary
	cornerBias: Float = 1.3f,
	aimTolerance: Float = 0.3f, // 0.0 = strict octant boundary, 1.0 = lock zone extends a full half-octant beyond
	private val rearmInFeedback: Boolean = true, // ON: re-arm freely from FEEDBACK (current/fast behavior). OFF: require cursor to return to DEAD between activations.
) {
	enum class Zone {
		DEAD,
		FEEDBACK,
		ACTIVATION,
		EXIT,
		RESET,
	}

	/**
	 * Highlight state for UI rendering
	 */
	enum class HighlightState {
		NONE, // WHITE (no highlight)
		YELLOW, // YELLOW (feedback zone)
		PALE_GREEN, // PALE PASTEL GREEN (in activation zone, above threshold)
		DARK_GREEN, // DARK GREEN (activated, below threshold but above activation zone)
		RED, // LIGHT RED (Cancel state — all keys highlighted)
	}

	/**
	 * Direction of correction during a single arming cycle. Set on first
	 * Correct gesture; persists until cursor leaves ACTIVATION/EXIT zone.
	 * Used to enforce monotone direction-of-travel: once committed to CW,
	 * a CCW movement past the original (other than a backtrack) is Cancel.
	 */
	enum class CorrectionDirection {
		NONE,
		CW,
		CCW,
	}

	/**
	 * Exit direction based on octant when entering EXIT zone.
	 * Used to determine exit behavior (pop out, pause, resume).
	 */
	enum class ExitDirection {
		UP, // Octants 0, 1, 2 (UP_LEFT, UP, UP_RIGHT) - Pop out to text field
		RIGHT, // Octant 4 (RIGHT) - Pause to selection list
		LEFT, // Octant 3 (LEFT) - Resume from pause
		DOWN, // Octants 5, 6, 7 (DOWN_LEFT, DOWN, DOWN_RIGHT) - Currently unused
		NONE,
	}

	private val resetZone: Float = deadZone + 0.25f * (activeZone - deadZone)
	private val cornerBiasClamped = cornerBias.coerceIn(0.5f, 2.0f)
	private val aimToleranceClamped = aimTolerance.coerceIn(0.0f, 1.0f)

	private var currentOctant: Int? = null
	private var currentZone: Zone = Zone.DEAD
	private var lastActivatedOctant: Int? = null
	private var lastMagnitude: Float = 0f
	private var activationArmed: Boolean = false
	private var wasInActivationZone: Boolean = false

	// Key locking - locks octant when entering activation zone
	private var lockedOctant: Int? = null

	// Max magnitude tracking for activation threshold
	private var maxMagnitudeInActivation: Float = 0f

	// Track if activation has been triggered (for dark green state)
	private var hasActivated: Boolean = false

	// Exit direction tracking
	private var exitDirection: ExitDirection = ExitDirection.NONE

	// ── Correct/Cancel state (per arming cycle) ──────────────────────
	// The octant originally locked when entering ACTIVATION/EXIT zone.
	// Used to detect Backtrack and to enforce monotone direction-of-travel.
	private var originalLockedOctant: Int? = null

	// Direction committed to during this arming cycle (set on first Correct).
	private var correctionDirection: CorrectionDirection = CorrectionDirection.NONE

	// True while in the Cancel state (all keys red). Cleared on zone exit.
	private var inCancelState: Boolean = false

	// The cursor's natural octant from the previous process() call, with
	// zone context. Used to detect non-contiguous octant jumps: a jump only
	// counts when BOTH the previous and current frame had the cursor in
	// ACTIVATION or EXIT zone (an "octant zone"). F/R/DEAD/RESET are all
	// treated as the single "inner zone" which is contiguous with every
	// octant — entries/exits there are normal motion, not jumps.
	private var lastCursorOctant: Int? = null
	private var lastZoneWasOctant: Boolean = false

	// Once a non-contiguous octant jump is observed within an arming cycle,
	// the breadcrumb trail is broken. Subsequent Cancel triggers in the same
	// cycle are suppressed until the cycle resets (cursor returns to F/R/DEAD).
	// The user can still complete the activation of the current locked key —
	// only the Cancel gesture is disabled, because a leap means the cursor
	// never actually traversed the intermediate octants Cancel requires.
	private var cancelPathBroken: Boolean = false

	// Set true when an activation fires; cleared when the cursor returns to
	// the DEAD zone. When `rearmInFeedback` is false (the new default),
	// ACT/EXIT magnitudes are re-zoned as FEEDBACK while this flag is set —
	// so the cursor can move around freely with yellow highlighting but
	// cannot lock/arm/activate until it has visited DEAD.
	private var needsResetToDead: Boolean = false

	// Transient per-call flags for Correct/Cancel audio cues. Cleared at start of process().
	private var pendingCorrectTone: Boolean = false
	private var pendingCancelTone: Boolean = false

	// The octant the user is moving AWAY from when a Correct/Backtrack
	// gesture fires this frame — used by the subsystem to paint that
	// key pale-red (the "cancelled key" visual cue, gated by the
	// Correction-flash-red setting). Null on frames where no Correct
	// fired. Reset at the start of every process() call.
	private var pendingCorrectFromOctant: Int? = null

	data class ProcessResult(
		val octant: Int?, // Locked octant when in ACTIVATION zone, current octant otherwise
		val zone: Zone,
		val shouldActivate: Boolean,
		val exitDirection: ExitDirection = ExitDirection.NONE,
		val isInExitZone: Boolean = false,
		val currentOctant: Int? = null, // Always the actual current octant (for exit direction detection)
		val highlightState: HighlightState = HighlightState.NONE, // What color the highlighted key should be
		val highlightOctant: Int? = null, // Which octant to highlight
		val shouldPlayCorrectTone: Boolean = false, // True on the frame a Correct gesture (incl. Backtrack) fired
		val correctedFromOctant: Int? = null, // Octant being abandoned by the Correct/Backtrack — paint pale-red as "cancelled key" cue
		val shouldPlayCancelTone: Boolean = false, // True on the frame a Cancel gesture fired
		val cursorOctantJumped: Boolean = false, // True if cursor's natural octant changed by >=2 angular positions since last frame (noise/leap)
		val previousCursorOctant: Int? = null, // Cursor's natural octant from previous frame (for diagnostic correlation)
	)

	/**
	 * Process new coordinates and return state update.
	 *
	 * Key behaviors:
	 * - When entering ACTIVATION zone, the octant is locked (pale green highlight)
	 * - Locked octant persists even if cursor moves to adjacent key while in ACTIVATION
	 * - When cursor returns below activation threshold: trigger activation (beep/flash)
	 * - Key stays DARK GREEN until cursor drops below activation zone
	 * - EXIT zone triggers exit delay timer for pop-out/pause behaviors
	 */
	fun process(x: Float, y: Float): ProcessResult {
		val magnitude = kotlin.math.hypot(x.toDouble(), y.toDouble()).toFloat()
		lastMagnitude = magnitude

		// Clear transient per-call tone flags
		pendingCorrectTone = false
		pendingCancelTone = false
		pendingCorrectFromOctant = null

		// Calculate angle and octant
		val angleDeg = Math.toDegrees(kotlin.math.atan2(-y.toDouble(), x.toDouble())).toFloat()
		val direction = angleToEightWay(angleDeg)
		val octant = directionToIndex(direction)

		val previousZone = currentZone

		// "Re-arm in Feedback Zone" gating. When rearmInFeedback is false
		// and an activation has already fired this trip-out, any subsequent
		// ACT/EXIT magnitude is re-zoned as FEEDBACK — the cursor moves
		// around with yellow highlighting but cannot lock/arm/activate
		// until it has returned to DEAD. The DEAD-entry branch clears
		// needsResetToDead, restoring normal behavior.
		val lockoutActive = needsResetToDead && !rearmInFeedback

		// Detect a non-contiguous octant jump. Per the contiguity model:
		// each octant zone is contiguous with its two angular neighbors and
		// with the combined inner zone (F/R/DEAD/RESET). So a "jump" only
		// counts when BOTH the previous and current frame had the cursor in
		// an octant zone AND the angular delta between them is >= 2.
		// (magnitude >= activeZone identifies ACT or EXIT — the "octant
		// zones" — without depending on the zone-update block below.)
		// Setting cancelPathBroken disqualifies subsequent Cancel triggers
		// in this cycle — the cursor never actually traversed the intermediate
		// octants, so the breadcrumb trail Cancel requires is broken.
		val currentlyInOctantZone = magnitude >= activeZone
		val prevCursorOctant = lastCursorOctant
		val cursorOctantJumped = lastZoneWasOctant &&
			currentlyInOctantZone &&
			prevCursorOctant != null &&
			kotlin.math.abs(signedOctantDelta(prevCursorOctant, octant)) >= 2
		if (cursorOctantJumped) {
			cancelPathBroken = true
		}

		// Determine current zone (order matters - check from outermost to innermost)
		val rawZone = when {
			magnitude >= exitZone -> Zone.EXIT
			magnitude >= activeZone -> Zone.ACTIVATION
			magnitude < deadZone -> Zone.DEAD
			magnitude >= resetZone && wasInActivationZone -> Zone.RESET
			else -> Zone.FEEDBACK
		}
		// During lockout, re-zone ACT/EXIT as FEEDBACK so the cursor moves
		// around with yellow highlighting only; lock/arm/activate are
		// effectively disabled until DEAD is visited.
		currentZone = if (lockoutActive && (rawZone == Zone.ACTIVATION || rawZone == Zone.EXIT)) {
			Zone.FEEDBACK
		} else {
			rawZone
		}

		currentOctant = octant

		// ── Correct/Cancel transition check (unified, pre-when-block) ──────
		// If the previous frame had us in ACTIVATION or EXIT (i.e., we were
		// already inside an arming cycle), and the cursor's angle is now
		// outside the locked octant's lock zone, apply the Correct/Cancel
		// transition BEFORE the zone-transition `when` block below.
		//
		// This is essential: without it, a single-frame transition like
		// ACT/UP_LEFT → FEEDBACK/UP would skip the lock-zone check entirely
		// and fall through to "Entering FEEDBACK from ACT/EXIT", which would
		// fire activation for the cursor's CURRENT octant (UP) instead of
		// recognising the angular drift as a Cancel (or, in less extreme
		// cases, as a Correct that should re-target the activation).
		if (!inCancelState &&
			lockedOctant != null &&
			(previousZone == Zone.ACTIVATION || previousZone == Zone.EXIT)
		) {
			val lockedCenter = octantCenterDeg(lockedOctant!!)
			if (angDistDeg(angleDeg, lockedCenter) > lockZoneHalfWidthDeg(lockedOctant!!)) {
				handleLockZoneExit(octant, magnitude)
			}
		}

		// Handle zone transitions
		var shouldActivate = false
		// The octant being activated this frame. Captured at every shouldActivate
		// site (each below) so it survives the zone-exit branches that clear
		// lockedOctant before reportedOctant is computed. Without this, an
		// activation fired in a zone-exit branch (FEEDBACK/RESET/DEAD entry from
		// ACT/EXIT) would report the cursor's CURRENT natural octant instead of
		// the locked one — which is wrong both when aimTolerance > 0 lets the
		// cursor sit in a neighbor's natural octant while still in the lock zone,
		// and when an angular drift coincides with a zone drop in one frame.
		var activatedOctant: Int? = null
		var highlightState = HighlightState.NONE
		var highlightOctant: Int? = null

		when {
			// Entering dead zone - check for activation first, then reset everything
			currentZone == Zone.DEAD -> {
				if (previousZone != Zone.DEAD) {
					// If jumping directly from ACTIVATION or EXIT to DEAD (fast head movement),
					// and activation was armed but not yet triggered, fire it.
					// activationArmed is forced false in Cancel state, so this correctly
					// suppresses activation when leaving a cancelled cycle.
					if ((previousZone == Zone.ACTIVATION || previousZone == Zone.EXIT) &&
						activationArmed &&
						lockedOctant != null
					) {
						shouldActivate = true
						activatedOctant = lockedOctant
						needsResetToDead = true
					}
					activationArmed = false
					wasInActivationZone = false
					hasActivated = false
					lastActivatedOctant = null
					lockedOctant = null
					maxMagnitudeInActivation = 0f
					exitDirection = ExitDirection.NONE
					// Clear Correct/Cancel state at end of cycle.
					originalLockedOctant = null
					correctionDirection = CorrectionDirection.NONE
					inCancelState = false
					cancelPathBroken = false
					// Re-arm gate: cursor has returned to DEAD, so the next
					// trip-out to ACT is allowed to lock and arm again.
					needsResetToDead = false
				}
				highlightState = HighlightState.NONE
				highlightOctant = null
			}

			// Entering feedback zone from reset - clear reset state
			currentZone == Zone.FEEDBACK && previousZone == Zone.RESET -> {
				wasInActivationZone = false
				hasActivated = false
				lockedOctant = null
				maxMagnitudeInActivation = 0f
				exitDirection = ExitDirection.NONE
				originalLockedOctant = null
				correctionDirection = CorrectionDirection.NONE
				inCancelState = false
				cancelPathBroken = false
				highlightState = HighlightState.YELLOW
				highlightOctant = octant
			}

			// Entering feedback zone from dead - normal state
			currentZone == Zone.FEEDBACK && previousZone == Zone.DEAD -> {
				wasInActivationZone = false
				hasActivated = false
				lockedOctant = null
				maxMagnitudeInActivation = 0f
				exitDirection = ExitDirection.NONE
				originalLockedOctant = null
				correctionDirection = CorrectionDirection.NONE
				inCancelState = false
				cancelPathBroken = false
				highlightState = HighlightState.YELLOW
				highlightOctant = octant
			}

			// Entering feedback zone from activation/exit (cursor dropped below activation zone)
			currentZone == Zone.FEEDBACK && (previousZone == Zone.ACTIVATION || previousZone == Zone.EXIT) -> {
				// If armed (not yet activated), trigger activation on zone exit.
				// activationArmed is false in Cancel state, so this correctly skips activation.
				if (activationArmed && lockedOctant != null) {
					shouldActivate = true
					activatedOctant = lockedOctant
					needsResetToDead = true
					activationArmed = false
				}
				// Reset activation state for next cycle
				wasInActivationZone = false
				hasActivated = false
				lockedOctant = null
				maxMagnitudeInActivation = 0f
				exitDirection = ExitDirection.NONE
				originalLockedOctant = null
				correctionDirection = CorrectionDirection.NONE
				inCancelState = false
				cancelPathBroken = false
				// Highlight current sector yellow
				highlightState = HighlightState.YELLOW
				highlightOctant = octant
			}

			// In feedback zone (not a transition) - follow current sector
			currentZone == Zone.FEEDBACK -> {
				highlightState = HighlightState.YELLOW
				highlightOctant = octant
			}

			// Entering activation zone - lock the octant immediately
			currentZone == Zone.ACTIVATION && previousZone != Zone.ACTIVATION && previousZone != Zone.EXIT -> {
				wasInActivationZone = true
				activationArmed = true
				hasActivated = false
				lockedOctant = octant // Lock the octant - this key will be activated
				lastActivatedOctant = octant
				maxMagnitudeInActivation = magnitude
				// Start of a new arming cycle — initialize Correct/Cancel state.
				originalLockedOctant = octant
				correctionDirection = CorrectionDirection.NONE
				inCancelState = false
				highlightState = HighlightState.PALE_GREEN
				highlightOctant = lockedOctant
			}

			// In activation zone - check for activation threshold
			currentZone == Zone.ACTIVATION -> {
				wasInActivationZone = true
				// Update max magnitude (constantly track furthest point)
				if (magnitude > maxMagnitudeInActivation) {
					maxMagnitudeInActivation = magnitude
				}

				// Correct/Cancel transition check now happens pre-when-block above,
				// so any lock-zone exit (whether in ACT or transitioning out) is
				// handled once and uniformly.

				// Check activation threshold based on locked octant (not current cursor position).
				// The lock stays firm even if the cursor wobbles to a neighboring octant during
				// retreat — once a key is armed (pale green), retreating MUST activate it.
				// Suppressed entirely while in Cancel state.
				if (!inCancelState && activationArmed && lockedOctant != null) {
					val distanceFromMax = maxMagnitudeInActivation - magnitude
					val distanceToActivationStart = maxMagnitudeInActivation - activeZone
					if (distanceToActivationStart > 0) {
						val returnPercentage = distanceFromMax / distanceToActivationStart
						if (returnPercentage >= keyActThreshold) {
							// Activation triggered!
							shouldActivate = true
							activatedOctant = lockedOctant
							needsResetToDead = true
							activationArmed = false
							hasActivated = true
						}
					}
				}

				// Determine highlight color based on state.
				if (inCancelState) {
					// Cancel state — all keys go red (rendered by applyUiUpdate).
					highlightState = HighlightState.RED
					highlightOctant = null
				} else if (activationArmed) {
					// Still armed — show pale green on the locked octant
					highlightState = HighlightState.PALE_GREEN
					highlightOctant = lockedOctant
				} else if (hasActivated) {
					// Activated — show dark green on the locked octant to confirm
					// which key was activated. Clears when cursor drops below
					// activation zone (handled by RESET/FEEDBACK/DEAD transitions).
					highlightState = HighlightState.DARK_GREEN
					highlightOctant = lockedOctant
				} else {
					highlightState = HighlightState.NONE
					highlightOctant = null
				}
			}

			// Entering exit zone - track direction
			currentZone == Zone.EXIT && previousZone != Zone.EXIT -> {
				wasInActivationZone = true
				exitDirection = octantToExitDirection(octant)
				// Keep the locked octant from activation zone, or initialize fresh if
				// the cursor jumped straight from a non-armed zone (DEAD/FEEDBACK) to EXIT.
				if (lockedOctant == null) {
					lockedOctant = octant
					activationArmed = true
					hasActivated = false
					// New arming cycle starting in EXIT zone — initialize Correct/Cancel state.
					originalLockedOctant = octant
					correctionDirection = CorrectionDirection.NONE
					inCancelState = false
				}
				// Update max magnitude
				if (magnitude > maxMagnitudeInActivation) {
					maxMagnitudeInActivation = magnitude
				}
				// Highlight by state.
				if (inCancelState) {
					highlightState = HighlightState.RED
					highlightOctant = null
				} else if (activationArmed) {
					highlightState = HighlightState.PALE_GREEN
					highlightOctant = lockedOctant
				} else if (hasActivated) {
					highlightState = HighlightState.DARK_GREEN
					highlightOctant = lockedOctant
				} else {
					highlightState = HighlightState.NONE
					highlightOctant = null
				}
			}

			// In exit zone - update max magnitude and check for activation on return
			currentZone == Zone.EXIT -> {
				if (magnitude > maxMagnitudeInActivation) {
					maxMagnitudeInActivation = magnitude
				}

				// Correct/Cancel transition check happens pre-when-block; no need
				// to repeat it here.

				// Check for activation threshold. Suppressed in Cancel state.
				if (!inCancelState && activationArmed && lockedOctant != null) {
					val distanceFromMax = maxMagnitudeInActivation - magnitude
					val distanceToActivationStart = maxMagnitudeInActivation - activeZone
					if (distanceToActivationStart > 0) {
						val returnPercentage = distanceFromMax / distanceToActivationStart
						if (returnPercentage >= keyActThreshold) {
							shouldActivate = true
							activatedOctant = lockedOctant
							needsResetToDead = true
							activationArmed = false
							hasActivated = true
						}
					}
				}
				// Highlight by state.
				if (inCancelState) {
					highlightState = HighlightState.RED
					highlightOctant = null
				} else if (activationArmed) {
					highlightState = HighlightState.PALE_GREEN
					highlightOctant = lockedOctant
				} else if (hasActivated) {
					highlightState = HighlightState.DARK_GREEN
					highlightOctant = lockedOctant
				} else {
					highlightState = HighlightState.NONE
					highlightOctant = null
				}
			}

			// Entering reset zone from activation/exit - fully clear lock state
			currentZone == Zone.RESET && (previousZone == Zone.ACTIVATION || previousZone == Zone.EXIT) -> {
				// If armed (not yet activated), trigger activation as cursor leaves activation zone.
				// activationArmed is false in Cancel state, so this correctly skips activation.
				if (activationArmed && lockedOctant != null) {
					shouldActivate = true
					activatedOctant = lockedOctant
					needsResetToDead = true
				}
				// Clear all lock state - cursor is free to lock onto a new key
				activationArmed = false
				wasInActivationZone = false
				hasActivated = false
				lockedOctant = null
				maxMagnitudeInActivation = 0f
				exitDirection = ExitDirection.NONE
				originalLockedOctant = null
				correctionDirection = CorrectionDirection.NONE
				inCancelState = false
				cancelPathBroken = false
				// Show yellow on current octant (cursor is free)
				highlightState = HighlightState.YELLOW
				highlightOctant = octant
			}

			// In reset zone (from other transitions or staying) - keep disarmed, no highlight
			currentZone == Zone.RESET -> {
				activationArmed = false
				highlightState = HighlightState.NONE
				highlightOctant = null
			}
		}

		// Determine which octant to return for the IME-side activation handler.
		// If an activation fired this frame, that takes priority — use the
		// explicitly-captured activatedOctant (the locked key at the moment
		// the activation fired). This is essential when the activation fires
		// from a zone-exit branch (FEEDBACK/RESET/DEAD entry) where lockedOctant
		// has already been cleared, and when the cursor's natural octant
		// differs from the locked octant (e.g., aimTolerance > 0 letting the
		// cursor sit just inside a neighbor while still in the lock zone).
		val reportedOctant = if (shouldActivate && activatedOctant != null) {
			activatedOctant
		} else {
			when (currentZone) {
				Zone.ACTIVATION, Zone.EXIT -> lockedOctant ?: octant
				else -> octant
			}
		}

		// Persist this frame's cursor octant + zone-context for next-frame
		// non-contiguous-jump detection.
		lastCursorOctant = octant
		lastZoneWasOctant = currentZone == Zone.ACTIVATION || currentZone == Zone.EXIT

		return ProcessResult(
			octant = reportedOctant,
			zone = currentZone,
			shouldActivate = shouldActivate,
			exitDirection = if (currentZone == Zone.EXIT) exitDirection else ExitDirection.NONE,
			isInExitZone = currentZone == Zone.EXIT,
			currentOctant = octant,
			highlightState = highlightState,
			highlightOctant = highlightOctant,
			shouldPlayCorrectTone = pendingCorrectTone,
			correctedFromOctant = pendingCorrectFromOctant,
			shouldPlayCancelTone = pendingCancelTone,
			cursorOctantJumped = cursorOctantJumped,
			previousCursorOctant = prevCursorOctant,
		)
	}

	/**
	 * Convert octant index to exit direction.
	 * UP octants (0, 1, 2) -> UP (pop out to text field)
	 * RIGHT octant (4) -> RIGHT (pause to selection list)
	 * LEFT octant (3) -> LEFT (resume from pause)
	 * DOWN octants (5, 6, 7) -> DOWN (unused)
	 */
	private fun octantToExitDirection(octant: Int): ExitDirection = when (octant) {
		0, 1, 2 -> ExitDirection.UP // UP_LEFT, UP, UP_RIGHT
		3 -> ExitDirection.LEFT // LEFT
		4 -> ExitDirection.RIGHT // RIGHT
		5, 6, 7 -> ExitDirection.DOWN // DOWN_LEFT, DOWN, DOWN_RIGHT
		else -> ExitDirection.NONE
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
		lastActivatedOctant = null
		lastMagnitude = 0f
		activationArmed = false
		wasInActivationZone = false
		hasActivated = false
		lockedOctant = null
		maxMagnitudeInActivation = 0f
		exitDirection = ExitDirection.NONE
		originalLockedOctant = null
		correctionDirection = CorrectionDirection.NONE
		inCancelState = false
		lastCursorOctant = null
		lastZoneWasOctant = false
		cancelPathBroken = false
		needsResetToDead = false
		pendingCorrectTone = false
		pendingCancelTone = false
	}

	// ── Correct/Cancel helpers ───────────────────────────────────────

	/** Angular center of each octant, in degrees [0, 360). */
	private fun octantCenterDeg(o: Int): Float = when (o) {
		0 -> 135f // UP_LEFT
		1 -> 90f // UP
		2 -> 45f // UP_RIGHT
		3 -> 180f // LEFT
		4 -> 0f // RIGHT
		5 -> 225f // DOWN_LEFT
		6 -> 270f // DOWN
		7 -> 315f // DOWN_RIGHT
		else -> 0f
	}

	private fun isCardinal(o: Int): Boolean = o == 1 || o == 3 || o == 4 || o == 6

	/** Half-width of the octant's natural sector, in degrees. Depends on cornerBias. */
	private fun octantHalfWidthDeg(o: Int): Float {
		val cardinalWidth = 90f / (1f + cornerBiasClamped)
		return if (isCardinal(o)) cardinalWidth / 2f else (90f - cardinalWidth) / 2f
	}

	/** Lock zone half-width: natural half-width extended by aimTolerance. */
	private fun lockZoneHalfWidthDeg(o: Int): Float = octantHalfWidthDeg(o) * (1f + aimToleranceClamped)

	/**
	 * Map octant index (per [directionToIndex]) to its angular position 0..7
	 * going around the dial. Octant *indices* are NOT in angular order — they
	 * follow the GamepadDirectionDetector.Direction enum ordering — so we have
	 * to translate to angular position before doing any "are these adjacent?"
	 * arithmetic.
	 *
	 *   angle    octant       angular pos
	 *     0°    RIGHT (4)         0
	 *    45°    UP_RIGHT (2)      1
	 *    90°    UP (1)            2
	 *   135°    UP_LEFT (0)       3
	 *   180°    LEFT (3)          4
	 *   225°    DOWN_LEFT (5)     5
	 *   270°    DOWN (6)          6
	 *   315°    DOWN_RIGHT (7)    7
	 */
	private fun octantToAngularPos(o: Int): Int = when (o) {
		4 -> 0 // RIGHT
		2 -> 1 // UP_RIGHT
		1 -> 2 // UP
		0 -> 3 // UP_LEFT
		3 -> 4 // LEFT
		5 -> 5 // DOWN_LEFT
		6 -> 6 // DOWN
		7 -> 7 // DOWN_RIGHT
		else -> 0
	}

	/**
	 * Signed angular delta from [from] to [to], in octant-units, wrapping
	 * around the dial. Operates on *angular position* — not raw octant index
	 * — so adjacent octants (like RIGHT↔UP_RIGHT) reliably read as |delta|=1
	 * regardless of how the indices are numbered.
	 *
	 * Range: -3 to +4. Sign labels "CW vs CCW" in the angular-pos coordinate
	 * system; the algorithm only cares that opposite-direction movements are
	 * distinguished, not which way is which on the physical screen.
	 */
	private fun signedOctantDelta(from: Int, to: Int): Int {
		val fromPos = octantToAngularPos(from)
		val toPos = octantToAngularPos(to)
		val raw = (toPos - fromPos + 8) % 8
		return if (raw <= 4) raw else raw - 8
	}

	/**
	 * Apply Correct/Cancel transition when the cursor has left the current
	 * lock zone. Updates lockedOctant, correctionDirection, inCancelState,
	 * activationArmed, hasActivated, maxMagnitudeInActivation, and the
	 * pendingCorrectTone/pendingCancelTone flags as appropriate.
	 *
	 * Expanded Correct Zone (post-correction):
	 * Once a Correct has occurred (correctionDirection != NONE), the cursor
	 * has more lateral room before Cancel triggers. Specifically, the natural
	 * octant immediately past the corrected position (in correctionDirection)
	 * is silently tolerated — lock stays on the corrected key, no highlight
	 * change, no Cancel. Only when the cursor reaches the octant *beyond*
	 * that (|delta| >= 2 from the locked) does Cancel fire. This gives the
	 * user a wide activation target after a Correct, removing the pressure
	 * to "snap back" quickly to center.
	 */
	private fun handleLockZoneExit(newOctant: Int, currentMagnitude: Float) {
		val current = lockedOctant ?: return
		val original = originalLockedOctant ?: return

		// Once the originally-locked key has been activated this cycle,
		// Correct/Cancel/Backtrack are all inert. The activation is already
		// committed — re-arming via triggerCorrect/Backtrack would let the
		// activation threshold fire a SECOND time for a different key as
		// the cursor continued retreating. (Scenario: lock key 0, retreat
		// fires activation; cursor drifts laterally to key 3 still in ACT
		// zone; old code would re-arm on key 3 and fire activation again
		// as the cursor finished retreating.) Visually, the DARK_GREEN
		// stays on the original activated key for the rest of this cycle.
		if (hasActivated) return

		val delta = signedOctantDelta(current, newOctant)

		if (kotlin.math.abs(delta) >= 2) {
			// Cancel candidate: cursor is 2+ octants from the locked key.
			// Disqualify (drop, no Cancel fired) if the breadcrumb trail is
			// broken — i.e., a non-contiguous octant jump occurred earlier
			// in this cycle. Cancel requires the cursor to have traversed
			// through the intermediate octants contiguously. The cycle stays
			// in this "no-Cancel" state until it resets (cursor returns to
			// F/R/DEAD). The user can still activate the locked key normally;
			// only the Cancel gesture is suppressed.
			if (cancelPathBroken) return
			triggerCancel()
			return
		}
		if (delta == 0) return // Shouldn't happen outside lock zone; defensive

		val intendedDir = if (delta > 0) CorrectionDirection.CW else CorrectionDirection.CCW
		when {
			correctionDirection == CorrectionDirection.NONE -> {
				// First Correct in this cycle
				triggerCorrect(newOctant, intendedDir, currentMagnitude)
			}
			correctionDirection == intendedDir -> {
				// Continuing in the committed direction
				if (current == original) {
					// We're at original after a backtrack — re-Correct to corrected position
					triggerCorrect(newOctant, intendedDir, currentMagnitude)
				} else {
					// We're at the corrected position and cursor has drifted one octant
					// further in the correctionDirection. Expanded Correct Zone: stay
					// silent — lock stays on the corrected key, no Cancel fires. The
					// only way to Cancel from here is the |delta| >= 2 branch above.
				}
			}
			else -> {
				// Opposite direction from committed
				if (newOctant == original) {
					// Backtrack to original — allowed; direction memory persists
					triggerBacktrack(currentMagnitude)
				} else {
					// Opposite direction past original — Cancel
					triggerCancel()
				}
			}
		}
	}

	private fun triggerCorrect(newOctant: Int, direction: CorrectionDirection, currentMagnitude: Float) {
		// Capture the octant we're leaving BEFORE reassigning lockedOctant —
		// the subsystem paints this one pale-red as the "cancelled key" cue.
		pendingCorrectFromOctant = lockedOctant
		lockedOctant = newOctant
		correctionDirection = direction
		maxMagnitudeInActivation = currentMagnitude
		activationArmed = true
		hasActivated = false
		pendingCorrectTone = true
	}

	private fun triggerBacktrack(currentMagnitude: Float) {
		// Capture the (corrected) octant being abandoned before backtracking
		// to the original — same visual semantics as triggerCorrect.
		pendingCorrectFromOctant = lockedOctant
		lockedOctant = originalLockedOctant
		maxMagnitudeInActivation = currentMagnitude
		activationArmed = true
		hasActivated = false
		// correctionDirection unchanged — the budget is spent for this cycle
		pendingCorrectTone = true
	}

	private fun triggerCancel() {
		inCancelState = true
		activationArmed = false
		hasActivated = false
		pendingCancelTone = true
		// lockedOctant kept for diagnostic visibility; not used while inCancelState
	}
}
