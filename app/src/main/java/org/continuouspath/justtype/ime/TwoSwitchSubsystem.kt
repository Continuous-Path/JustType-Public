package org.continuouspath.justtype.ime

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants.INPUT_METHOD_NONE
import org.continuouspath.justtype.Constants.INPUT_METHOD_TWO_SWITCH
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_AUTOREPEAT_MODE
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_BEEP_ACTIVATION
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATIONS
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_SHOW_BAND
import org.continuouspath.justtype.TwoSwitchState
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt

/**
 * Callbacks from [TwoSwitchSubsystem] to the IME for shared operations.
 */
interface TwoSwitchCallbacks {
	/** Flash the switch bar on activation. */
	fun flashSwitchBar(flashGreen: Boolean, flashRed: Boolean)

	/** Beep on switch activation (external switch path — gated by [TwoSwitchSubsystem.beepActivation]). */
	fun beepSwitchActivation()

	/** Beep on touch screen switch activation (gated by touchScreenSwitchBeepEnabled in IME). */
	fun beepTouchSwitchActivation()

	/**
	 * Single combined tone used in place of switch-activation + key-activation
	 * beeps on the third switch press when BOTH per-switch and per-keystroke
	 * beep settings are ON. Default no-op so test fakes don't break.
	 */
	fun beepSwitchKeyCombined() {}

	/**
	 * Intermediate group-narrow feedback (4v4→2v2, 2v2→1v1): a soft step haptic + (if [beep]) a
	 * small step beep — distinct from the full feedback the final key press gets. Default no-op.
	 */
	fun stepFeedback(beep: Boolean) {}

	/**
	 * Normal key-activation feedback for the final key press ([index]), on top of the silent sink
	 * press: the IME flashes green + beeps + buzzes here (master-gated, and skips if the press
	 * errored); Nav adds beep+haptic (its sink already flashes). Default no-op.
	 */
	fun finalActivationFeedback(index: Int) {}

	/** Debug logging. */
	fun debugLog(message: String)
}

/**
 * Self-contained two-switch binary-search selection subsystem. Thin shell over
 * [TwoSwitchState] that owns coroutine timers (auto-repeat, activation-repeat, timeout),
 * view-bridge IO, audio (flash/beep), settings reads, and view-readiness deferral.
 */
class TwoSwitchSubsystem(
	private val scope: CoroutineScope,
	private val viewBridge: TwoSwitchViewBridge,
	private val keySink: KeyActivationSink,
	private val callbacks: TwoSwitchCallbacks,
) : InputMethod {

	// --- Pure state machine ---
	private val state = TwoSwitchState()

	// --- Public state (read by IME for routing/stuck-timeout) ---
	val isActive: Boolean get() = state.isActive
	var switchHeld: Boolean = false

	// --- Subsystem-owned state ---
	private var showBand: Boolean = false
	private var disableHighlight: Boolean = false
	internal var beepActivation: Boolean = false
	internal var keyBeepFeedbackEnabled: Boolean = false
	private var flashKeyFeedbackEnabled: Boolean = false
	private var pendingStart: Boolean = false
	private var pendingRestartOnly: Boolean = false

	// --- Timers ---
	private var autoRepeatJob: Job? = null
	private var repeatActivationJob: Job? = null
	private var timeoutJob: Job? = null

	// --- Constants ---
	private val redTint = Color.argb(255, 255, 210, 210)
	private val greenTint = Color.argb(255, 210, 255, 210)
	private val stripRed = Color.argb(255, 170, 0, 0)
	private val stripGreen = Color.argb(255, 0, 140, 0)
	private val stripHeightFrac = 0.10f
	private val stripWidthFrac = 0.75f
	private val pendingRedTint = Color.argb(255, 255, 100, 100)
	private val pendingGreenTint = Color.argb(255, 100, 255, 100)

	// Group-narrow flash: a light neutral shade (distinct from the green/red region tints and the
	// green key-activation flash) that briefly marks the just-selected group before it re-splits.
	private val groupFlashTint = Color.argb(255, 179, 229, 252)
	private val groupFlashMs = 120L

	// --- Cached config for timer dispatch ---
	private var autoRepeatDelayMs: Long = 1000L
	private var repeatActivationDelayMs: Long = 1000L
	private var timeoutMs: Long = 0L

	// --- Lifecycle ---

	override fun loadSettings(repo: SettingsRepository) {
		val enabled = repo.getString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE) == INPUT_METHOD_TWO_SWITCH
		timeoutMs = repo.getInt(KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC).coerceIn(0, 120) * 1000L
		val showBandPref = repo.getBoolean(KEY_TWO_SWITCH_SHOW_BAND)
		showBand = enabled && showBandPref
		// Phase 3C: "Disable Highlight" was removed; red/green key highlighting
		// is now always-on in Two-Switch mode.
		disableHighlight = false
		val autoRepeatEnabled = repo.getBoolean(KEY_TWO_SWITCH_AUTOREPEAT_MODE)
		autoRepeatDelayMs = (repo.getFloat(KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC).coerceIn(0.25f, 3.0f) * 1000L).toLong()
		val repeatActivationEnabled = repo.getBoolean(KEY_TWO_SWITCH_REPEAT_ACTIVATIONS)
		repeatActivationDelayMs = (repo.getFloat(KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC).coerceIn(0.25f, 3.0f) * 1000L).toLong()
		beepActivation = repo.getBoolean(KEY_TWO_SWITCH_BEEP_ACTIVATION)
		keyBeepFeedbackEnabled = repo.getBoolean(KEY_BEEP_KEY_FEEDBACK)
		flashKeyFeedbackEnabled = repo.getBoolean(KEY_FLASH_KEY_FEEDBACK)

		state.applyConfig(
			TwoSwitchState.Config(
				timeoutMs = timeoutMs,
				showBand = showBand,
				disableHighlight = disableHighlight,
				autoRepeatEnabled = autoRepeatEnabled,
				autoRepeatDelayMs = autoRepeatDelayMs,
				repeatActivationEnabled = repeatActivationEnabled,
				repeatActivationDelayMs = repeatActivationDelayMs,
				beepActivation = beepActivation,
			),
		)
	}

	override fun destroy() {
		autoRepeatJob?.cancel()
		repeatActivationJob?.cancel()
		timeoutJob?.cancel()
	}

	override fun cancelAndClear() {
		autoRepeatJob?.cancel()
		autoRepeatJob = null
		repeatActivationJob?.cancel()
		repeatActivationJob = null
		timeoutJob?.cancel() // else an orphaned highlight timeout fires on the torn-down cycle
		timeoutJob = null
		clearColors()
	}

	// --- External switch events ---

	/**
	 * Handle external switch press. Returns true if a key was activated (cycle completed).
	 */
	fun handleSwitchDown(role: String): Boolean {
		val result = state.process(TwoSwitchState.Event.ExternalSwitch(role, switchHeld))
		dispatch(result)
		return result.cycleCompleted
	}

	fun handleSwitchUp() {
		dispatch(state.process(TwoSwitchState.Event.ExternalSwitchUp))
	}

	// --- Touch screen switch events ---

	fun handleTouchDown(role: String) {
		dispatch(state.process(TwoSwitchState.Event.TouchDown(role)))
	}

	fun handleTouchUp() {
		val result = state.process(TwoSwitchState.Event.TouchUp)
		// keySink readiness gate (see original line 213) — only activate if ready.
		if (result.activateKey != null && !keySink.isReady()) {
			// Skip activation; still dispatch any UI updates.
			dispatch(result.copy(activateKey = null, activateKeySilent = false))
			return
		}
		dispatch(result)
	}

	// --- Cycle control ---

	fun startCycle(restartOnly: Boolean = false) {
		if (!viewBridge.isViewReady) {
			pendingStart = true
			pendingRestartOnly = if (pendingStart) pendingRestartOnly && restartOnly else restartOnly
			callbacks.debugLog("[twoSwitch] startCycle deferred (views not ready)")
			return
		}
		dispatch(state.process(TwoSwitchState.Event.StartCycle(restartOnly)))
	}

	fun clearColors() {
		dispatch(state.process(TwoSwitchState.Event.ClearColors))
	}

	fun onViewsReady() {
		if (pendingStart) {
			val restartOnly = pendingRestartOnly
			pendingStart = false
			pendingRestartOnly = false
			startCycle(restartOnly)
		}
	}

	// --- Pure-logic accessors (kept for test compatibility) ---

	internal fun splitCandidates() = state.splitCandidates()

	internal fun computeSequenceForKey(key: Int): List<String> = state.computeSequenceForKey(key)

	// --- Internal: dispatch state result ---

	private fun dispatch(result: TwoSwitchState.TwoSwitchResult) {
		// Debug logging
		result.debugMessage?.let { callbacks.debugLog(it) }

		// A hit that narrows the group (4v4→2v2, 2v2→1v1) but doesn't yet activate a key. Uses the
		// flash flags (set from the switch role, independent of the beep setting) so the stepped
		// haptic/flash still fire when the beep is off. Excludes the idle-restart hit (step 0), the
		// final activation, and touch's pending-highlight preview step.
		val isGroupNarrow = (result.shouldFlashGreen || result.shouldFlashRed) &&
			result.activateKey == null &&
			!result.cycleCompleted &&
			result.step > 0 &&
			result.pendingHighlight == null

		// Apply colors / tint based on red/green lists
		if (result.applyColors) {
			applyColors(result.red, result.green)
		}

		// Pending-touch highlight (brighter tint on pre-activation key)
		result.pendingHighlight?.let { highlightPendingKey(it, result.red) }

		// Clear all (timeout / clearColors path)
		if (result.clearAll) {
			viewBridge.restoreAllBackgrounds()
			if (!showBand || !disableHighlight) {
				viewBridge.clearAllForegrounds()
			}
		}

		// Stepped feedback. The final key press (3rd hit) is a real key activation → normal key
		// flash + beep + haptic via the sink (error-aware). Intermediate group-narrow hits get a
		// smaller cue: a soft step haptic + a step beep gated by "Beep on Each Switch Activation"
		// (which is itself only meaningful when the master key-beep is on) + a light group flash.
		val activate = result.activateKey
		if (result.shouldFlashGreen || result.shouldFlashRed) {
			callbacks.flashSwitchBar(result.shouldFlashGreen, result.shouldFlashRed)
		}
		if (isGroupNarrow) {
			callbacks.stepFeedback(beep = keyBeepFeedbackEnabled && beepActivation)
			flashGroup(result.red, result.green)
		} else if (activate == null) {
			// Non-narrow, non-activation hit (idle-restart, touch pending): keep the plain per-hit beeps.
			// On the final activation these are suppressed — its beep comes from finalActivationFeedback.
			if (result.shouldBeepSwitch) callbacks.beepSwitchActivation()
			if (result.shouldBeepTouch) callbacks.beepTouchSwitchActivation()
		}

		// Activation — silent sink press (types the key / performs the nav action) plus normal
		// key-activation feedback via the callback: IME flashes+beeps+buzzes (error-aware), Nav
		// adds beep+haptic on top of its sink's flash.
		if (activate != null && keySink.isReady()) {
			keySink.activateKeySilent(activate)
			callbacks.finalActivationFeedback(activate)
		}

		// Timer actions
		for (action in result.timerActions) {
			handleTimerAction(action)
		}
	}

	private fun handleTimerAction(action: TwoSwitchState.TimerAction) {
		when (action) {
			is TwoSwitchState.TimerAction.ScheduleTimeout -> scheduleTimeout(action.delayMs)
			TwoSwitchState.TimerAction.CancelTimeout -> cancelTimeout()
			is TwoSwitchState.TimerAction.StartAutoRepeat -> startAutoRepeat(action.keyIndex)
			TwoSwitchState.TimerAction.CancelAutoRepeat -> cancelAutoRepeat()
			is TwoSwitchState.TimerAction.StartActivationRepeat -> startActivationRepeat(action.role)
			TwoSwitchState.TimerAction.CancelActivationRepeat -> cancelActivationRepeat()
		}
	}

	// --- Visual feedback ---

	private fun applyColors(red: List<Int>, green: List<Int>) {
		val sequences = state.sequences
		for (idx in 0 until viewBridge.buttonCount) {
			val inRed = red.contains(idx)
			val inGreen = green.contains(idx)

			if (!disableHighlight) {
				when {
					inRed -> viewBridge.tintButton(idx, redTint)
					inGreen -> viewBridge.tintButton(idx, greenTint)
					else -> viewBridge.restoreButtonBackground(idx)
				}
			} else {
				viewBridge.restoreButtonBackground(idx)
			}

			if (showBand) {
				val seqColors = sequences[idx] ?: emptyList()
				val shouldShowStrip = if (disableHighlight) (inRed || inGreen) else true
				if (shouldShowStrip) {
					viewBridge.setButtonForeground(
						idx,
						ColorCodeStripDrawable(
							seqColors.map { if (it == "Red") stripRed else stripGreen },
							stripHeightFrac,
							stripWidthFrac,
						),
					)
				} else {
					viewBridge.clearButtonForeground(idx)
				}
			} else {
				viewBridge.clearButtonForeground(idx)
			}
		}
	}

	private fun highlightPendingKey(target: Int, red: List<Int>) {
		// First restore all buttons to current two-switch colors, then apply brighter tint.
		applyColors(red, state.currentGreen)
		val tint = if (red.contains(target)) pendingRedTint else pendingGreenTint
		viewBridge.tintButton(target, tint)
	}

	/**
	 * Briefly flash the just-selected group (the survivors of a narrow step) a light shade. The
	 * bridge settles each key back to its standing background (the red/green split) afterwards,
	 * so any repaint that lands mid-flash wins. Gated by the flash-key-feedback setting.
	 */
	private fun flashGroup(red: List<Int>, green: List<Int>) {
		if (!flashKeyFeedbackEnabled || !viewBridge.isViewReady) return
		for (idx in red) viewBridge.flashButton(idx, groupFlashTint, groupFlashMs, null)
		for (idx in green) viewBridge.flashButton(idx, groupFlashTint, groupFlashMs, null)
	}

	// --- Timers ---

	private fun startAutoRepeat(index: Int) {
		cancelAutoRepeat()
		autoRepeatJob = scope.launch {
			delay(autoRepeatDelayMs)
			if (switchHeld) {
				keySink.activateKey(index)
				startAutoRepeat(index)
			}
		}
	}

	private fun cancelAutoRepeat() {
		autoRepeatJob?.cancel()
		autoRepeatJob = null
	}

	private fun startActivationRepeat(role: String) {
		cancelActivationRepeat()
		repeatActivationJob = scope.launch {
			delay(repeatActivationDelayMs)
			val completed = handleSwitchDown(role)
			if (completed) {
				cancelActivationRepeat()
			} else {
				startActivationRepeat(role)
			}
		}
	}

	private fun cancelActivationRepeat() {
		repeatActivationJob?.cancel()
		repeatActivationJob = null
	}

	private fun scheduleTimeout(delayMs: Long) {
		cancelTimeout()
		if (delayMs <= 0L) return
		timeoutJob = scope.launch {
			delay(delayMs)
			dispatch(state.process(TwoSwitchState.Event.Timeout))
		}
	}

	private fun cancelTimeout() {
		timeoutJob?.cancel()
		timeoutJob = null
	}
}

/**
 * Drawable that renders a 3-segment color-code strip at the bottom of a button.
 * Each segment shows Red or Green to indicate the 3-press sequence for that key.
 */
internal class ColorCodeStripDrawable(
	private val colors: List<Int>,
	private val heightFraction: Float,
	private val widthFraction: Float,
) : android.graphics.drawable.Drawable() {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val rect = RectF()

	override fun draw(canvas: android.graphics.Canvas) {
		val b = bounds
		if (colors.size < 3 || b.width() <= 0 || b.height() <= 0) return
		val h = b.height() * heightFraction
		val stripWidth = b.width() * widthFraction
		val left = b.centerX() - stripWidth / 2f
		val top = b.bottom - h
		val segWidth = stripWidth / 3f
		// Draw three segments
		for (i in 0..2) {
			rect.set(
				left + i * segWidth,
				top,
				left + (i + 1) * segWidth,
				b.bottom.toFloat(),
			)
			paint.style = Paint.Style.FILL
			paint.color = colors[i]
			canvas.drawRect(rect, paint)
		}
		// Draw separators
		paint.style = Paint.Style.STROKE
		paint.color = Color.BLACK
		paint.strokeWidth = 4f
		canvas.drawLine(left + segWidth, top, left + segWidth, b.bottom.toFloat(), paint)
		canvas.drawLine(left + 2 * segWidth, top, left + 2 * segWidth, b.bottom.toFloat(), paint)
	}

	override fun setAlpha(alpha: Int) {
		paint.alpha = alpha
	}

	override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
		paint.colorFilter = colorFilter
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.OPAQUE
}
