package org.continuouspath.justtype.ime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEADZONE
import org.continuouspath.justtype.JoystickState
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat

/**
 * Callbacks from [JoystickSubsystem] to the IME for shared operations.
 */
interface JoystickCallbacks {
	/** Play key activation beep (IME gates on cached beepKeyFeedbackEnabled). */
	fun playActivationBeep()

	/** Debug logging. */
	fun debugLog(message: String)
}

/**
 * Manages joystick input processing, zone-based highlighting, and activation timing.
 *
 * Wraps [JoystickState] (already-extracted state machine) and translates its
 * [JoystickState.ProcessResult] into view-bridge highlight calls and key activations.
 *
 * Replaces ~8 fields and ~9 methods previously in JustTypeIME.
 */
class JoystickSubsystem(
	private val scope: CoroutineScope,
	private val viewBridge: JoystickViewBridge,
	private val keySink: KeyActivationSink,
	private val callbacks: JoystickCallbacks,
) : InputMethod {
	// ── Drawable resource IDs ──────────────────────────────────────────
	companion object {
		internal val DRAWABLE_FEEDBACK = R.drawable.button_background_feedback
		internal val DRAWABLE_PALE_GREEN = R.drawable.button_background_joystick_pale_green
		internal val DRAWABLE_DARK_GREEN = R.drawable.button_background_joystick_dark_green

		internal const val ACTIVATION_DELAY_MS = 100L
		internal const val FLASH_DURATION_MS = 250L
	}

	// ── State ──────────────────────────────────────────────────────────
	private var joystickState: JoystickState? = null
	private var currentHighlightIndex: Int? = null
	private var activationLockedOctant: Int? = null
	private var activationJob: Job? = null
	private var flashJob: Job? = null

	/** True during the pale green + activation sequence. */
	var isInActivationSequence: Boolean = false
		private set

	/** Whether flash feedback is enabled for activation. Set during [loadSettings]. */
	var flashEnabled: Boolean = true
		private set

	/** Whether beep feedback is enabled for activation. Set during [loadSettings]. */
	var beepEnabled: Boolean = true
		private set

	// ── Lifecycle ──────────────────────────────────────────────────────

	/**
	 * Load joystick settings and construct the [JoystickState] state machine.
	 *
	 * Also reads beep/flash feedback prefs so the subsystem can gate them
	 * without reaching back into the IME.
	 */
	override fun loadSettings(repo: SettingsRepository) {
		val dz = repo.getFloat(KEY_JOYSTICK_DEADZONE)
		val azRaw = repo.getFloat(KEY_JOYSTICK_ACTIVEZONE)
		val az = kotlin.math.max(azRaw, dz + 0.01f).coerceAtMost(0.99f)
		val cornerBias = if (repo.contains(KEY_JOYSTICK_CORNER_BIAS)) {
			repo.getFloat(KEY_JOYSTICK_CORNER_BIAS)
		} else {
			val legacy = repo.getFloat(KEY_CORNER_BIAS, 1.35f)
			repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, legacy)
			legacy
		}.coerceIn(0.5f, 2.0f)

		joystickState = JoystickState(
			deadZone = dz,
			activeZone = az,
			cornerBias = cornerBias,
		)
		flashEnabled = repo.getBoolean(KEY_FLASH_KEY_FEEDBACK)
		beepEnabled = repo.getBoolean(KEY_BEEP_KEY_FEEDBACK)
	}

	/** Cancel all pending jobs. Called from IME's onDestroy. */
	override fun destroy() {
		cancelActivationSequence()
	}

	// ── Input processing ───────────────────────────────────────────────

	/**
	 * Process joystick coordinates. Called from GamepadDirectionDetector.onContinuousUpdate.
	 */
	fun handleInput(x: Float, y: Float) {
		if (!viewBridge.isViewReady) return
		val state = joystickState ?: return

		val result = state.process(x, y)

		// Handle zone transition: if user moved to neighbor while in activation, clear highlights
		if (result.movedToNeighborInActivation) {
			cancelActivationSequence()
			clearHighlight()
			return
		}

		// Handle just entering activation zone — start activation sequence
		if (result.justEnteredActivation && result.highlightOctant != null) {
			startActivationSequence(result.highlightOctant)
			return
		}

		// Handle activation (cursor dropped below activation zone)
		if (result.shouldActivate && result.activatedOctant != null) {
			if (isInActivationSequence) {
				// Key was already activated during sequence, just clean up
				completeActivation()
			} else {
				// Normal activation — fire the key
				triggerKeyActivation(result.activatedOctant)
			}
			clearHighlight()
			// Continue to set feedback highlight for current position
		}

		// If in middle of activation sequence, don't change highlights
		if (isInActivationSequence) {
			return
		}

		// Update highlighting based on state
		when (result.highlightState) {
			JoystickState.HighlightState.NONE -> clearHighlight()
			JoystickState.HighlightState.YELLOW -> {
				result.highlightOctant?.let { setFeedbackHighlight(it) }
			}
			JoystickState.HighlightState.PALE_GREEN -> {
				result.highlightOctant?.let { setPaleGreenHighlight(it) }
			}
			JoystickState.HighlightState.DARK_GREEN -> {
				result.highlightOctant?.let { setDarkGreenHighlight(it) }
			}
		}
	}

	// ── Cleanup ────────────────────────────────────────────────────────

	/** Cancel activation sequence and clear all highlights. */
	override fun cancelAndClear() {
		cancelActivationSequence()
		clearHighlight()
	}

	// ── Activation sequence ────────────────────────────────────────────

	/**
	 * Start the joystick activation sequence:
	 * 1. Key lights PALE GREEN for 100ms
	 * 2. If beep enabled, emit beep
	 * 3. Fire key activation (silent — no flash from keySink)
	 * 4. If flash enabled: DARK GREEN flash (250ms), then back to PALE GREEN
	 * 5. If flash disabled: Key turns DARK GREEN and stays
	 */
	private fun startActivationSequence(octant: Int) {
		cancelActivationSequence()

		isInActivationSequence = true
		activationLockedOctant = octant

		// Set initial PALE GREEN highlight
		setPaleGreenHighlight(octant)

		// After 100ms pale green period
		activationJob = scope.launch {
			delay(ACTIVATION_DELAY_MS)

			// Bail if view was hidden or sequence was cancelled
			if (!viewBridge.isViewReady || !isInActivationSequence) return@launch

			// Play activation beep
			if (beepEnabled) {
				callbacks.playActivationBeep()
			}

			// Fire the key activation
			if (keySink.isReady()) {
				keySink.activateKeySilent(octant)
			}

			if (flashEnabled) {
				// Flash DARK GREEN for 250ms
				setDarkGreenHighlight(octant)
				flashJob = scope.launch {
					delay(FLASH_DURATION_MS)
					// After flash, go back to PALE GREEN until cursor drops
					if (isInActivationSequence && activationLockedOctant == octant) {
						setPaleGreenHighlight(octant)
					}
				}
			} else {
				// No flash — just turn DARK GREEN and stay until cursor drops
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

	private fun completeActivation() {
		cancelActivationSequence()
		// Key was already activated during sequence, just clean up highlights
	}

	/** Trigger key activation for normal drop-below-activation-zone case. */
	private fun triggerKeyActivation(index: Int) {
		if (!keySink.isReady()) return
		keySink.activateKeySilent(index)
		if (beepEnabled) {
			callbacks.playActivationBeep()
		}
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
		if (prev != null && prev != newIndex) {
			viewBridge.restoreButtonBackground(prev)
		}
	}
}
