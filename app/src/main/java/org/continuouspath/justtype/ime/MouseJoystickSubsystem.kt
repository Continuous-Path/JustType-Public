package org.continuouspath.justtype.ime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants.INPUT_METHOD_MOUSE_JOYSTICK
import org.continuouspath.justtype.Constants.INPUT_METHOD_NONE
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_DEADZONE
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_SENSITIVITY_DP
import org.continuouspath.justtype.JoystickState
import org.continuouspath.justtype.MouseJoystickBarrierOverlay
import org.continuouspath.justtype.MouseJoystickCaptureOverlay
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt

/**
 * Handles mouse-as-joystick input for wheelchair users whose Bluetooth joystick
 * presents as a mouse to Android.
 *
 * Reads absolute mouse-hover position (`rawX/rawY`) from the IME window's hover
 * events, differencing consecutive positions into per-frame deltas that feed a
 * [JoystickState] state machine. When the cursor leaves the keyboard's top edge a
 * transparent [MouseJoystickBarrierOverlay] strip keeps catching hover so input
 * survives past the edge. Exit is via UP-direction dwell (border-flash countdown).
 * Pointer capture is deliberately NOT used: an IME's window is non-focusable, and
 * Android grants capture only to the focused window.
 */
class MouseJoystickSubsystem(
	private val context: Context,
	private val scope: CoroutineScope,
	private val viewBridge: JoystickViewBridge,
	private val keySink: KeyActivationSink,
	private val callbacks: MouseJoystickCallbacks,
) : InputMethod {

	companion object {
		private val DRAWABLE_FEEDBACK = R.drawable.button_background_feedback
		private val DRAWABLE_PALE_GREEN = R.drawable.button_background_joystick_pale_green
		private val DRAWABLE_DARK_GREEN = R.drawable.button_background_joystick_dark_green

		private const val ACTIVATION_DELAY_MS = 100L
		private const val FLASH_DURATION_MS = 250L

		private const val EMA_ALPHA = 0.40f

		private const val DECAY_START_DELAY_MS = 150L
		private const val DECAY_STEP_MS = 16L
		private const val DECAY_FACTOR = 0.7f

		private const val DEFAULT_EXIT_DELAY_MS = 2000L
		private const val DEFAULT_REENGAGE_HYSTERESIS_MS = 750L
		private const val EXIT_ZONE_THRESHOLD = 0.95f
		private const val FLASH_ON_MS = 120L
		private const val FLASH_OFF_MS = 120L

		// Cursor left the barrier without re-entering the keyboard — tear down.
		private const val BARRIER_DEACTIVATE_TIMEOUT_MS = 300L

		// A mouse touch this soon after a click-release is the releasing click's own tap — swallow it.
		private const val CLICK_TOUCH_SWALLOW_MS = 150L

		private const val MS_PER_SEC = 1000f

		// Sensitivity range in dp/sec (the time-normalized unit). Real joystick full-push speeds
		// measured at ~1400-2500 dp/sec, so the range spans gentle to fast sticks.
		const val SENSITIVITY_MIN_DP_PER_SEC = 100
		const val SENSITIVITY_MAX_DP_PER_SEC = 2500

		// Edge-pin synthesis: strength of the substituted push (kept below the 0.95 exit zone so it
		// drives activation, never the exit dwell), how close to a display edge counts as pinned, and
		// how small a frame delta counts as "not actually moving".
		private const val EDGE_PIN_MAGNITUDE = 0.92f
		private const val EDGE_EPS_PX = 2f
		private const val ZERO_DELTA_EPS_PX = 1f

		private const val TAG = "MJ_ESC"
		private const val EXIT_MISS_LOG_THROTTLE_MS = 500L

		// Captured deltas are RAW device counts, not the acceleration-scaled screen px the hover
		// path sees (Pixel 8 log: the same stick that jumps 285px/frame on hover sends exactly
		// +-1 count per 16ms frame under capture, ~20x below the dead zone). Scale counts into the
		// hover-equivalent unit so the one sensitivity setting means the same thing in both modes.
		// Provisional until the calibration wizard measures per device; tune from CAP_VEL logs.
		private const val CAPTURE_COUNT_SCALE = 16f
		private const val CAP_VEL_LOG_THROTTLE_MS = 500L

		// A single hover frame may only move the cursor this far (dp) toward driving velocity. A choppy
		// joystick can teleport ~285px (one frame measured at 1079px = full screen width) and slam the
		// cursor into a screen edge in one step, where rawX/rawY stop changing and velocity dies. Capping
		// the per-frame contribution keeps the cursor off the edges so deltas keep flowing.
		private const val MAX_FRAME_DELTA_DP = 120f

		/** Scale ([x], [y]) to magnitude ≤ 1, keeping direction. Exposed for the diagonal-clamp test. */
		@androidx.annotation.VisibleForTesting
		fun clampToUnitForTest(x: Float, y: Float): Pair<Float, Float> {
			val mag = kotlin.math.hypot(x, y)
			return if (mag > 1f) (x / mag) to (y / mag) else x to y
		}

		/** Scale ([dx], [dy]) to magnitude ≤ [maxPx], keeping direction. Exposed for the delta-clamp test. */
		@androidx.annotation.VisibleForTesting
		fun clampDeltaForTest(dx: Float, dy: Float, maxPx: Float): Pair<Float, Float> {
			val mag = kotlin.math.hypot(dx, dy)
			return if (mag > maxPx) (dx / mag * maxPx) to (dy / mag * maxPx) else dx to dy
		}
	}

	// ── Settings ───────────────────────────────────────────────────────
	var isEnabled = false
		private set
	private var joystickState: JoystickState? = null
	private var sensitivityPx: Float = 240f
	private var maxFrameDeltaPx: Float = MAX_FRAME_DELTA_DP
	private var flashEnabled = true
	private var beepEnabled = true
	private var exitDelayMs: Long = DEFAULT_EXIT_DELAY_MS
	private var reengageHysteresisMs: Long = DEFAULT_REENGAGE_HYSTERESIS_MS

	// Timestamp of the last *voluntary* release (dwell-exit). Blocks immediate
	// re-engage for the hysteresis window; an involuntary teardown (keyboard hide)
	// does NOT touch these, so re-entry works normally after that.
	private var hasVoluntarilyReleased: Boolean = false
	private var lastVoluntaryReleaseAtMs: Long = 0L

	// ── Velocity tracking ──────────────────────────────────────────────
	private var smoothedVx = 0f
	private var smoothedVy = 0f
	private var decayJob: Job? = null

	// ── Hover position + barrier ───────────────────────────────────────
	// Absolute cursor position of the previous hover frame; deltas are the difference.
	private var lastHoverX = Float.NaN
	private var lastHoverY = Float.NaN

	// eventTime of the previous hover frame; the gap normalizes deltas into px/sec.
	private var lastEventTimeMs = 0L

	// eventTime of the previous captured frame (the captured stream has its own cadence).
	private var lastCaptureEventTimeMs = 0L
	private var keyboardHeightPx: Int = 0
	private var isInBarrierZone = false
	private var barrierDeactivateJob: Job? = null
	private val barrierOverlay = MouseJoystickBarrierOverlay(context).also { overlay ->
		overlay.setCallbacks(object : MouseJoystickBarrierOverlay.Callbacks {
			override fun onBarrierHover(event: MotionEvent) = this@MouseJoystickSubsystem.onBarrierHover(event)
			override fun onBarrierExited() = this@MouseJoystickSubsystem.onBarrierExited()
		})
	}

	// Spike: while capture is held there is no cursor and no screen edges - the barrier, edge-pin
	// synthesis, and delta clamp are all idle because the hover stream stops. Hover resumes (and
	// those safety nets with it) whenever capture is denied or lost.
	private var captureActive = false
	private val captureOverlay = MouseJoystickCaptureOverlay(context).also { overlay ->
		overlay.setCallbacks(object : MouseJoystickCaptureOverlay.Callbacks {
			override fun onCaptureGranted() = this@MouseJoystickSubsystem.onCaptureGranted()
			override fun onCaptureLost() = this@MouseJoystickSubsystem.onCaptureLost()
			override fun onCapturedMotion(event: MotionEvent) = this@MouseJoystickSubsystem.feedCapturedMotion(event)
			override fun onCapturedTouch() = this@MouseJoystickSubsystem.pauseCaptureForTouch()
		})
	}

	// ── Activation sequence (mirrors JoystickSubsystem) ────────────────
	private var currentHighlightIndex: Int? = null
	private var activationLockedOctant: Int? = null
	private var activationJob: Job? = null
	private var flashJob: Job? = null
	private var isInActivationSequence = false

	// Session-sticky so barrier edge-crossings (repeated HOVER_ENTER) don't re-toggle the OS pointer.
	private var pointerHidden = false

	private var lastExitMissLogMs = 0L
	private var lastCapVelLogMs = 0L

	// True between a real engage (HOVER_ENTER accepted) and teardown. Hover MOVEs are ignored until
	// engaged, so the reengage-hysteresis window can't be bypassed by MOVEs arriving without an ENTER.
	private var engaged = false

	/** Whether MJ currently holds the pointer (used to swallow the click's touch, see [handleMouseTouchEvent]). */
	val isEngaged: Boolean get() = engaged

	// ── Dwell-to-exit timer ────────────────────────────────────────────
	private var exitTimerJob: Job? = null
	private var wasInExitZone: Boolean = false

	// ── Lifecycle ──────────────────────────────────────────────────────

	override fun loadSettings(repo: SettingsRepository) {
		val primaryMethod = repo.getString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE) ?: INPUT_METHOD_NONE
		val wasEnabled = isEnabled
		isEnabled = primaryMethod == INPUT_METHOD_MOUSE_JOYSTICK

		val dz = repo.getFloat(KEY_MOUSE_JOYSTICK_DEADZONE).coerceIn(0.01f, 0.80f)
		val azRaw = repo.getFloat(KEY_MOUSE_JOYSTICK_ACTIVEZONE)
		val az = azRaw.coerceAtLeast(dz + 0.01f).coerceAtMost(0.90f)
		val cornerBias = repo.getFloat(KEY_MOUSE_JOYSTICK_CORNER_BIAS).coerceIn(0.5f, 2.0f)

		joystickState = JoystickState(
			deadZone = dz,
			activeZone = az,
			cornerBias = cornerBias,
			exitZone = EXIT_ZONE_THRESHOLD,
		)

		val density = context.resources.displayMetrics.density
		// The setting is now dp/SECOND (movement speed for full deflection), not dp/event — velocity is
		// time-normalized (see feedHoverDelta). sensitivityPx is the px/sec speed that maps to magnitude 1.
		val sensitiveDpPerSec = repo.getInt(KEY_MOUSE_JOYSTICK_SENSITIVITY_DP).coerceIn(SENSITIVITY_MIN_DP_PER_SEC, SENSITIVITY_MAX_DP_PER_SEC)
		sensitivityPx = sensitiveDpPerSec * density
		maxFrameDeltaPx = MAX_FRAME_DELTA_DP * density

		flashEnabled = repo.getBoolean(KEY_FLASH_KEY_FEEDBACK)
		beepEnabled = repo.getBoolean(KEY_BEEP_KEY_FEEDBACK)
		exitDelayMs = repo.getInt(KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS).coerceIn(500, 5000).toLong()
		reengageHysteresisMs = repo.getInt(KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS).coerceIn(0, 2000).toLong()

		if (!isEnabled && wasEnabled) {
			cancelAndClear()
			barrierOverlay.hide()
		}
	}

	override fun destroy() {
		cancelAndClear()
	}

	override fun cancelAndClear() {
		cancelActivationSequence()
		clearHighlight()
		cancelExitTimer()
		resetVelocity()
		wasInExitZone = false
		engaged = false
		lastHoverX = Float.NaN
		lastHoverY = Float.NaN
		lastEventTimeMs = 0L
		barrierDeactivateJob?.cancel()
		barrierDeactivateJob = null
		isInBarrierZone = false
		// Remove the barrier overlay window if it's up: teardown (keyboard close / method switch) used
		// to leave it added, leaking the window AND leaving isShowing=true so the next show() no-op'd
		// the barrier for the rest of the process. hide() is a no-op when it isn't showing.
		barrierOverlay.hide()
		captureActive = false
		captureOverlay.hide()
		// Single choke point for every teardown path — always restore the pointer so a later
		// plain-mouse user (or MJ disabled) never inherits a hidden cursor.
		showPointer()
	}

	// Toggle the OS pointer once per session (idempotent), so barrier edge-crossings don't thrash it.
	private fun hidePointer() {
		if (pointerHidden) return
		pointerHidden = true
		viewBridge.setMousePointerHidden(true)
	}

	private fun showPointer() {
		if (!pointerHidden) return
		pointerHidden = false
		viewBridge.setMousePointerHidden(false)
	}

	// ── Velocity → joystick input ─────────────────────────────────────

	private fun processVelocity(dx: Float, dy: Float) {
		smoothedVx = EMA_ALPHA * dx + (1f - EMA_ALPHA) * smoothedVx
		smoothedVy = EMA_ALPHA * dy + (1f - EMA_ALPHA) * smoothedVy

		// Clamp the VECTOR magnitude, not each axis: per-axis clamping let a diagonal reach
		// hypot(1,1)=1.41, so fast off-45 pushes saturated both axes and snapped to a diagonal
		// octant (wrong key), and the EXIT zone was reachable at only 0.67 per axis. Scaling by the
		// magnitude preserves the push's true angle.
		val (normX, normY) = clampToUnit(smoothedVx / sensitivityPx, smoothedVy / sensitivityPx)

		processInput(normX, normY)
		scheduleVelocityDecay()
	}

	/** Scale ([x], [y]) so its magnitude is at most 1, keeping the direction. */
	private fun clampToUnit(x: Float, y: Float): Pair<Float, Float> = clampToUnitForTest(x, y)

	private fun scheduleVelocityDecay() {
		decayJob?.cancel()
		decayJob = scope.launch {
			delay(DECAY_START_DELAY_MS)
			while (smoothedVx != 0f || smoothedVy != 0f) {
				smoothedVx *= DECAY_FACTOR
				smoothedVy *= DECAY_FACTOR
				if (kotlin.math.abs(smoothedVx) < 0.001f && kotlin.math.abs(smoothedVy) < 0.001f) {
					smoothedVx = 0f
					smoothedVy = 0f
					processInput(0f, 0f)
					break
				}
				val (normX, normY) = clampToUnit(smoothedVx / sensitivityPx, smoothedVy / sensitivityPx)
				processInput(normX, normY)
				delay(DECAY_STEP_MS)
			}
			decayJob = null
		}
	}

	private fun resetVelocity() {
		smoothedVx = 0f
		smoothedVy = 0f
		decayJob?.cancel()
		decayJob = null
	}

	// ── State machine processing (mirrors JoystickSubsystem.handleInput) ──

	private fun processInput(x: Float, y: Float) {
		if (!viewBridge.isViewReady) return
		val state = joystickState ?: return

		val result = state.process(x, y)
		logExitNearMiss(x, y, result)
		maybeUpdateExitTimer(result)

		if (result.movedToNeighborInActivation) {
			cancelActivationSequence()
			clearHighlight()
			return
		}

		if (result.justEnteredActivation && result.highlightOctant != null) {
			startActivationSequence(result.highlightOctant)
			return
		}

		if (result.shouldActivate && result.activatedOctant != null) {
			if (isInActivationSequence) {
				completeActivation()
			} else {
				triggerKeyActivation(result.activatedOctant)
			}
			clearHighlight()
		}

		if (isInActivationSequence) return

		when (result.highlightState) {
			JoystickState.HighlightState.NONE -> clearHighlight()
			JoystickState.HighlightState.YELLOW -> result.highlightOctant?.let { setFeedbackHighlight(it) }
			JoystickState.HighlightState.PALE_GREEN -> result.highlightOctant?.let { setPaleGreenHighlight(it) }
			JoystickState.HighlightState.DARK_GREEN -> result.highlightOctant?.let { setDarkGreenHighlight(it) }
		}
	}

	// ── Activation sequence ────────────────────────────────────────────

	private fun startActivationSequence(octant: Int) {
		cancelActivationSequence()
		isInActivationSequence = true
		activationLockedOctant = octant
		setPaleGreenHighlight(octant)
		activationJob = scope.launch {
			delay(ACTIVATION_DELAY_MS)
			if (!viewBridge.isViewReady || !isInActivationSequence) return@launch
			if (beepEnabled) callbacks.playActivationBeep()
			if (keySink.isReady()) keySink.activateKeySilent(octant)
			if (flashEnabled) {
				setDarkGreenHighlight(octant)
				flashJob = scope.launch {
					delay(FLASH_DURATION_MS)
					if (isInActivationSequence && activationLockedOctant == octant) {
						setPaleGreenHighlight(octant)
					}
				}
			} else {
				setDarkGreenHighlight(octant)
			}
		}
	}

	private fun cancelActivationSequence() {
		activationJob?.cancel()
		activationJob = null
		flashJob?.cancel()
		flashJob = null
		isInActivationSequence = false
		activationLockedOctant = null
	}

	private fun completeActivation() = cancelActivationSequence()

	private fun triggerKeyActivation(index: Int) {
		if (!keySink.isReady()) return
		keySink.activateKeySilent(index)
		if (beepEnabled) callbacks.playActivationBeep()
	}

	// ── Highlighting ───────────────────────────────────────────────────

	private fun setFeedbackHighlight(index: Int) {
		if (index !in 0 until viewBridge.buttonCount) return
		restorePreviousIfDifferent(index)
		viewBridge.setButtonDrawable(index, DRAWABLE_FEEDBACK)
		currentHighlightIndex = index
	}

	private fun setPaleGreenHighlight(index: Int) {
		if (index !in 0 until viewBridge.buttonCount) return
		restorePreviousIfDifferent(index)
		viewBridge.setButtonDrawable(index, DRAWABLE_PALE_GREEN)
		currentHighlightIndex = index
	}

	private fun setDarkGreenHighlight(index: Int) {
		if (index !in 0 until viewBridge.buttonCount) return
		restorePreviousIfDifferent(index)
		viewBridge.setButtonDrawable(index, DRAWABLE_DARK_GREEN)
		currentHighlightIndex = index
	}

	private fun clearHighlight() {
		currentHighlightIndex?.let { viewBridge.restoreButtonBackground(it) }
		currentHighlightIndex = null
	}

	private fun restorePreviousIfDifferent(newIndex: Int) {
		val prev = currentHighlightIndex
		if (prev != null && prev != newIndex) viewBridge.restoreButtonBackground(prev)
	}

	// ── Hover input (absolute position → deltas) ──────────────────────

	/**
	 * Handle a `SOURCE_MOUSE` hover event delivered to the keyboard window. Deltas
	 * come from differencing absolute `rawX/rawY`. HOVER_EXIT (cursor left the
	 * keyboard's top edge) raises the barrier strip so input continues past the
	 * edge — the dwell timer, not the exit itself, decides when to tear down.
	 * The re-engage hysteresis gates the first re-entry after a voluntary dwell-exit.
	 */
	fun handleMouseHoverEvent(event: MotionEvent): Boolean {
		if (!isEnabled) return false
		return when (event.action) {
			MotionEvent.ACTION_HOVER_ENTER -> {
				if (isInHysteresisWindow()) return true
				// Cursor re-entered the keyboard from the barrier — cancel the pending teardown but leave
				// the barrier up (it stays raised the whole session so a big jump can't slip past it).
				barrierDeactivateJob?.cancel()
				barrierDeactivateJob = null
				isInBarrierZone = false
				lastHoverX = event.rawX
				lastHoverY = event.rawY
				smoothedVx = 0f
				smoothedVy = 0f
				val freshEngage = !engaged // barrier↔keyboard re-crossings re-fire ENTER while engaged
				engaged = true
				hidePointer() // the OS pointer is noise while driving MJ; restored in cancelAndClear
				// Raise the barrier on the FIRST engage and keep it up: it covers the whole area above the
				// keyboard, so a big joystick jump that punches the cursor past the top edge is caught by
				// the barrier instead of escaping to the app window (the core MJ escape bug). Idempotent.
				if (freshEngage && keyboardHeightPx > 0) barrierOverlay.show(keyboardHeightPx)
				// Spike: also try to acquire pointer capture via the focusable overlay. If granted, the
				// hover stream stops and captured relative deltas take over (edge-free); if denied or
				// lost, this hover pipeline just keeps working.
				if (freshEngage) captureOverlay.show()
				// Only chime on a genuine session start, not on every barrier edge re-crossing, or the
				// "capture acquired" tone repeats and falsely implies capture had been lost.
				if (freshEngage && beepEnabled) callbacks.playCaptureAcquiredTone()
				true
			}
			MotionEvent.ACTION_HOVER_MOVE -> {
				// Ignore MOVEs that arrive without an accepted ENTER (e.g. during the reengage-hysteresis
				// window, where ENTER is swallowed) — otherwise the keyboard would silently type with the
				// pointer still visible and no engage tone.
				if (engaged) {
					feedHoverDelta(event)
					// New mouse motion after a touch-pause resumes captured mode (no-op while showing).
					if (!captureActive) captureOverlay.show()
				}
				true
			}
			MotionEvent.ACTION_HOVER_EXIT -> {
				// Cursor left the keyboard's top edge into the barrier (already raised on engage). Don't
				// reset velocity: the state machine must stay in the EXIT zone for the dwell timer to fire.
				// Arm the deactivate timeout: an exit the barrier never catches (down over the navbar, or a
				// click-induced EXIT) must still tear down. Barrier re-entry / barrier hover cancels it.
				// Not when disengaged (a stray EXIT would flash a ghost countdown) and not under capture
				// (the cursor vanishing IS the capture starting - position state is void there).
				if (engaged && !captureActive) {
					isInBarrierZone = true
					startBarrierDeactivateTimeout()
				}
				true
			}
			// A mouse-button press is the documented instant escape: release capture now instead of
			// letting the click land as a stray keystroke under the (hidden) cursor. Consumed so the
			// tap never reaches a key.
			MotionEvent.ACTION_BUTTON_PRESS -> {
				val wasEngaged = engaged
				if (wasEngaged) releaseByClick() // clears engaged via deactivate → cancelAndClear
				wasEngaged // consume only while engaged; otherwise let normal handling proceed
			}
			else -> false
		}
	}

	/**
	 * Swallow the touch a mouse click delivers while MJ holds the pointer, so the click can't land as a
	 * keystroke on the key beneath the hidden cursor. The release click's own ACTION_BUTTON_PRESS
	 * arrives (and tears down) just before its ACTION_DOWN, so also swallow touches within a short
	 * window after a voluntary release — that touch is the releasing click itself. Returns true when
	 * consumed.
	 */
	fun handleMouseTouchEvent(): Boolean = engaged ||
		(hasVoluntarilyReleased && SystemClock.uptimeMillis() - lastVoluntaryReleaseAtMs < CLICK_TOUCH_SWALLOW_MS)

	/** Release capture on a mouse-button click — the instant escape promised in settings. */
	private fun releaseByClick() {
		markVoluntaryRelease()
		if (beepEnabled) callbacks.playCaptureReleasedTone()
		deactivate()
	}

	/** Suppress re-entry for the hysteresis window after a voluntary dwell-exit. */
	private fun isInHysteresisWindow(): Boolean {
		if (!hasVoluntarilyReleased) return false
		return SystemClock.uptimeMillis() - lastVoluntaryReleaseAtMs < reengageHysteresisMs
	}

	/** Difference this hover frame's absolute position against the last, feeding the delta. */
	private fun feedHoverDelta(event: MotionEvent) {
		if (lastHoverX.isNaN()) {
			lastHoverX = event.rawX
			lastHoverY = event.rawY
			lastEventTimeMs = event.eventTime
			return
		}
		val rawDx = event.rawX - lastHoverX
		val rawDy = event.rawY - lastHoverY
		val dtMs = event.eventTime - lastEventTimeMs
		lastHoverX = event.rawX
		lastHoverY = event.rawY
		lastEventTimeMs = event.eventTime
		if (dtMs <= 0L) return // no time elapsed → no speed to compute
		if (feedEdgePin(event, rawDx, rawDy)) return
		// Cap the per-frame travel (magnitude, keeping direction): one choppy-joystick teleport can jump
		// the cursor a full screen width and slam it into an edge, where rawX/rawY freeze and velocity
		// dies. Bounding each frame's contribution keeps the cursor off the edges so deltas keep flowing.
		val (dx, dy) = clampDelta(rawDx, rawDy)
		// Velocity in px/SECOND, not raw px/event: a choppy device sends huge deltas at long, irregular
		// gaps (measured: ~285px jumps every ~235ms) while a smooth one sends tiny deltas rapidly.
		// Dividing by the event gap turns both into a comparable steady speed — the fix for the clunky
		// feel of a low-rate joystick, and it makes sensitivity independent of the display's event rate.
		val secs = dtMs / MS_PER_SEC
		processVelocity(dx / secs, dy / secs)
	}

	// MOVEs arriving with ~zero delta while the cursor sits on a display edge mean the stick is
	// pushing against the wall (a resting stick sends no events at all) — the OS clamps rawX/rawY so
	// the motion is invisible. Substitute a strong push toward that edge so keys in that direction
	// stay selectable. Release is self-limiting: events stop, so normal decay takes over.
	private fun feedEdgePin(event: MotionEvent, rawDx: Float, rawDy: Float): Boolean {
		if (kotlin.math.abs(rawDx) >= ZERO_DELTA_EPS_PX || kotlin.math.abs(rawDy) >= ZERO_DELTA_EPS_PX) return false
		val dm = context.resources.displayMetrics
		val pinX = when {
			event.rawX <= EDGE_EPS_PX -> -1f
			event.rawX >= dm.widthPixels - 1 - EDGE_EPS_PX -> 1f
			else -> 0f
		}
		val pinY = when {
			event.rawY <= EDGE_EPS_PX -> -1f
			event.rawY >= dm.heightPixels - 1 - EDGE_EPS_PX -> 1f
			else -> 0f
		}
		if (pinX == 0f && pinY == 0f) return false
		val mag = kotlin.math.hypot(pinX, pinY)
		val speed = EDGE_PIN_MAGNITUDE * sensitivityPx
		processVelocity(pinX / mag * speed, pinY / mag * speed)
		return true
	}

	/** Scale a raw per-frame pixel delta so its magnitude is at most [maxFrameDeltaPx], keeping direction. */
	private fun clampDelta(dx: Float, dy: Float): Pair<Float, Float> = clampDeltaForTest(dx, dy, maxFrameDeltaPx)

	// ── Barrier overlay callbacks ─────────────────────────────────────

	private fun onBarrierHover(event: MotionEvent) {
		barrierDeactivateJob?.cancel()
		barrierDeactivateJob = null
		feedHoverDelta(event)
	}

	private fun onBarrierExited() {
		// Cursor left the barrier strip — either upward (leaving entirely) or back down
		// into the keyboard (HOVER_ENTER will cancel this). A short timeout resolves it.
		startBarrierDeactivateTimeout()
	}

	// ── Capture overlay callbacks (spike) ──────────────────────────────

	private fun onCaptureGranted() {
		captureActive = true
		lastCaptureEventTimeMs = 0L
		// Captured = no cursor on screen, so ALL position-based state is void: the lost-cursor
		// timeout AND any exit countdown born during engage (it would otherwise run to completion
		// unopposed and silently tear the captured session down 2s in).
		barrierDeactivateJob?.cancel()
		barrierDeactivateJob = null
		isInBarrierZone = false
		cancelExitTimer()
		// The decisive spike check: does typing still land while our overlay holds input focus?
		callbacks.verifyInputConnectionLive()
	}

	private fun onCaptureLost() {
		// An intentional pause/teardown clears captureActive BEFORE hiding, so only an UNEXPECTED
		// loss (focus stolen, window killed) falls through to arm the silence watchdog.
		if (!captureActive) return
		captureActive = false
		// The cursor just reappeared somewhere. If it lands over our windows, hover resumes and
		// cancels this; if it landed outside them, silence must still tear the session down.
		if (engaged) startBarrierDeactivateTimeout()
	}

	// Nic's modal design: a finger on the screen pauses MJ. Release capture so the touch session
	// flows normally (capture redirects touchscreen events too); the session stays engaged in
	// hover-fallback mode, and the next mouse motion re-acquires capture via the hover stream.
	private fun pauseCaptureForTouch() {
		if (!captureActive) return
		captureActive = false
		captureOverlay.hide()
	}

	/** Captured relative stream: x/y ARE the frame deltas — no differencing, no edges, no clamps. */
	private fun feedCapturedMotion(event: MotionEvent) {
		if (!isEnabled || !engaged) return
		when (event.action) {
			// Same instant escape as the hover path: a click releases rather than typing a stray key.
			MotionEvent.ACTION_BUTTON_PRESS -> releaseByClick()
			// Captured relative events normally arrive as MOVE with deltas in x/y, but some input
			// stacks deliver HOVER_MOVE or only populate the relative axes - accept all shapes.
			MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
				// The OS coalesces raw HID reports into one event per display frame; every batched
				// sample is its own delta, so the frame's true travel is the SUM of all of them.
				var dx = 0f
				var dy = 0f
				for (i in 0 until event.historySize) {
					dx += event.getHistoricalX(i)
					dy += event.getHistoricalY(i)
				}
				dx += event.x
				dy += event.y
				if (dx == 0f && dy == 0f) {
					dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
					dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
				}
				val dtMs = event.eventTime - lastCaptureEventTimeMs
				val first = lastCaptureEventTimeMs == 0L
				lastCaptureEventTimeMs = event.eventTime
				if (first || dtMs <= 0L) return
				val secs = dtMs / MS_PER_SEC
				processVelocity(dx * CAPTURE_COUNT_SCALE / secs, dy * CAPTURE_COUNT_SCALE / secs)
				logCaptureVelocity(dx * CAPTURE_COUNT_SCALE / secs, dy * CAPTURE_COUNT_SCALE / secs)
			}
		}
	}

	// Tuning data for CAPTURE_COUNT_SCALE: the scaled speed and where it lands in the zones.
	private fun logCaptureVelocity(vxPxPerSec: Float, vyPxPerSec: Float) {
		val now = SystemClock.uptimeMillis()
		if (now - lastCapVelLogMs < CAP_VEL_LOG_THROTTLE_MS) return
		lastCapVelLogMs = now
		val speed = kotlin.math.hypot(vxPxPerSec, vyPxPerSec)
		Log.d("MJ_CAP", "CAP_VEL px/s=${speed.toInt()} norm=${"%.2f".format(speed / sensitivityPx)}")
	}

	@androidx.annotation.VisibleForTesting
	internal fun onCaptureGrantedForTest() = onCaptureGranted()

	@androidx.annotation.VisibleForTesting
	internal fun onCaptureLostForTest() = onCaptureLost()

	@androidx.annotation.VisibleForTesting
	internal fun pauseCaptureForTouchForTest() = pauseCaptureForTouch()

	@androidx.annotation.VisibleForTesting
	internal fun feedCapturedMotionForTest(event: MotionEvent) = feedCapturedMotion(event)

	private fun startBarrierDeactivateTimeout() {
		barrierDeactivateJob?.cancel()
		barrierDeactivateJob = scope.launch {
			delay(BARRIER_DEACTIVATE_TIMEOUT_MS)
			// Hover went silent with the cursor beyond our windows (status bar, navbar, another app's
			// region — system windows we cannot cover). Don't tear down silently: run the standard exit
			// countdown (border flash + ticks + release tone) so the escape is signposted, and steering
			// back during the window keeps the session alive. If a dwell is already running, it owns
			// the release.
			if (exitTimerJob == null) {
				Log.d(TAG, "LOST_CURSOR hover silent ${BARRIER_DEACTIVATE_TIMEOUT_MS}ms, starting exit countdown")
				startExitTimer()
			}
		}
	}

	/** Full teardown: cursor left for good without completing the dwell. */
	private fun deactivate() {
		cancelAndClear()
		barrierOverlay.hide()
	}

	/** Keep the barrier pinned above the keyboard as its height changes (raises it if engage beat the height). */
	fun updateKeyboardHeight(heightPx: Int) {
		keyboardHeightPx = heightPx
		if (engaged && isEnabled && heightPx > 0) {
			barrierOverlay.show(heightPx) // idempotent: repositions if already up, raises it if engage came first
		}
	}

	// Decide whether sustained UP at high magnitude should drive the exit timer.
	// On EXIT entry, also cancel any pending activation: the user pushed past
	// the activation ring on purpose, so the 100 ms activation timer that may
	// have been scheduled when crossing the ring must not fire a stray keystroke.
	private fun maybeUpdateExitTimer(result: JoystickState.ProcessResult) {
		// In EXIT zone, JoystickState guarantees currentOctant is non-null
		// (geometric angle is always set before zone determination), so result.octant
		// is the source of truth — no fallback chain needed.
		val isUpDirection = result.octant != null && result.octant in 0..2
		val enteringExit = result.isInExitZone && !wasInExitZone
		wasInExitZone = result.isInExitZone

		if (enteringExit) {
			cancelActivationSequence()
			clearHighlight()
		}

		when {
			result.isInExitZone && isUpDirection -> startExitTimer()
			// A deliberate up push whose cursor has physically left the keyboard (in the barrier) is an
			// escape in progress even below the exit-speed threshold: measured hard pushes peak at
			// 0.81-0.90 (EXIT_MISS), so the speed gate alone misses them. Position + direction is the
			// stronger signal; the countdown still cancels on a steer back down.
			isInBarrierZone && isUpDirection && result.zone == JoystickState.Zone.ACTIVATION -> startExitTimer()
			// A running dwell only aborts on a deliberate steer AWAY from up (a non-up octant). It must
			// survive velocity decay: when the cursor pins at the physical screen top, rawY can't
			// decrease, so the up push decays down through the zones to DEAD even though the user is
			// still holding up. Cancelling on those intermediate frames would make the exit unreachable
			// at the top edge. DEAD and any still-up frame keep the dwell; only a real down/side push ends it.
			exitTimerJob != null && !isUpDirection && result.zone != JoystickState.Zone.DEAD -> cancelExitTimer()
			exitTimerJob != null -> Unit // running dwell, still up-ish or decayed to dead — hold it
			else -> cancelExitTimer()
		}
	}

	private fun startExitTimer() {
		if (exitTimerJob != null) return
		Log.d(TAG, "EXIT_START dwell=${exitDelayMs}ms")
		viewBridge.showKeyboardBorder(true)
		exitTimerJob = scope.launch {
			delay(exitDelayMs / 2)
			// flashJob is a child of exitTimerJob via structured concurrency —
			// cancelling exitTimerJob propagates to it automatically.
			val flashJob = launch {
				while (isActive) {
					viewBridge.showKeyboardBorder(false)
					delay(FLASH_OFF_MS)
					viewBridge.showKeyboardBorder(true)
					// Tick with each flash so the release countdown isn't visual-only (the green border
					// is subtle and off-screen for a user watching the app they're driving).
					if (beepEnabled) callbacks.playExitCountdownTick()
					delay(FLASH_ON_MS)
				}
			}
			delay(exitDelayMs / 2)
			flashJob.cancel()
			Log.d(TAG, "EXIT_RELEASE")
			markVoluntaryRelease()
			if (beepEnabled) callbacks.playCaptureReleasedTone()
			deactivate()
		}
	}

	private fun markVoluntaryRelease() {
		hasVoluntarilyReleased = true
		lastVoluntaryReleaseAtMs = SystemClock.uptimeMillis()
	}

	// Diagnoses "the border flash never started": a strong up push that stays below the 0.95 exit
	// zone (e.g. long event gaps dropping the time-normalized magnitude) is logged, throttled.
	private fun logExitNearMiss(x: Float, y: Float, result: JoystickState.ProcessResult) {
		if (exitTimerJob != null || result.isInExitZone) return
		if (result.octant == null || result.octant !in 0..2) return
		val mag = kotlin.math.hypot(x, y)
		if (mag < 0.75f) return
		val now = SystemClock.uptimeMillis()
		if (now - lastExitMissLogMs < EXIT_MISS_LOG_THROTTLE_MS) return
		lastExitMissLogMs = now
		Log.d(TAG, "EXIT_MISS mag=$mag octant=${result.octant} (exit needs >= 0.95 up)")
	}

	private fun cancelExitTimer() {
		// Only touch the bridge if a timer was actually running. Otherwise every
		// non-EXIT frame would call showKeyboardBorder(false) unnecessarily.
		// Cancelling exitTimerJob propagates to its child flash coroutine via
		// structured concurrency — no separate field to track.
		val hadTimer = exitTimerJob != null
		exitTimerJob?.cancel()
		exitTimerJob = null
		if (hadTimer) {
			Log.d(TAG, "EXIT_CANCEL")
			viewBridge.showKeyboardBorder(false)
		}
	}
}
