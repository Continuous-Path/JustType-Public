package org.continuouspath.justtype

/**
 * Pure-Kotlin state machine for two-switch binary-search selection.
 *
 * 8 keyboard keys split into Red/Green groups of 4. The user presses Red or Green
 * to narrow candidates by half; after 3 presses one key is selected.
 *
 * Owns: step counter, candidate lists, pending touch target, active flag.
 *
 * Stays out of: coroutine timers (auto-repeat, activation-repeat, timeout), view-bridge
 * IO (tints, foregrounds), audio (flash/beep), settings reads, view-readiness deferral.
 * Those concerns live in [org.continuouspath.justtype.ime.TwoSwitchSubsystem], which feeds
 * events to this state and dispatches the resulting [TwoSwitchResult] snapshot.
 */
class TwoSwitchState {

	// ── Configuration ────────────────────────────────────────────────────────

	data class Config(
		val timeoutMs: Long = 0L,
		val showBand: Boolean = false,
		val disableHighlight: Boolean = false,
		val autoRepeatEnabled: Boolean = false,
		val autoRepeatDelayMs: Long = 1000L,
		val repeatActivationEnabled: Boolean = false,
		val repeatActivationDelayMs: Long = 1000L,
		val beepActivation: Boolean = false,
	)

	private var config: Config = Config()

	fun applyConfig(newConfig: Config) {
		config = newConfig
	}

	// ── Events ───────────────────────────────────────────────────────────────

	sealed interface Event {
		/**
		 * External (Bluetooth) switch press. [switchHeld] reflects whether the user is
		 * still holding the switch — used to gate auto-repeat on cycle completion.
		 */
		data class ExternalSwitch(val role: String, val switchHeld: Boolean) : Event

		/** External switch release. Cancels in-flight repeats. */
		object ExternalSwitchUp : Event

		/** Touch overlay press at step 0 / 1 (immediate); step 2 is pending until [TouchUp]. */
		data class TouchDown(val role: String) : Event

		/** Touch overlay release. Activates pending step-2 target if any. */
		object TouchUp : Event

		/** Reset/start the cycle. [restartOnly] preserves timeout pause behavior. */
		data class StartCycle(val restartOnly: Boolean = false) : Event

		/** Hard reset — clear colors, drop candidates, mark inactive. */
		object ClearColors : Event

		/** Timeout coroutine fired (caller scheduled it earlier). */
		object Timeout : Event
	}

	// ── Result snapshot ──────────────────────────────────────────────────────

	sealed interface TimerAction {
		data class ScheduleTimeout(val delayMs: Long) : TimerAction
		object CancelTimeout : TimerAction
		data class StartAutoRepeat(val keyIndex: Int) : TimerAction
		object CancelAutoRepeat : TimerAction
		data class StartActivationRepeat(val role: String) : TimerAction
		object CancelActivationRepeat : TimerAction
	}

	data class TwoSwitchResult(
		val isActive: Boolean,
		val step: Int,
		val red: List<Int>,
		val green: List<Int>,
		val pendingHighlight: Int? = null,
		// Fire-and-forget actions:
		val activateKey: Int? = null,
		val activateKeySilent: Boolean = false,
		val shouldFlashGreen: Boolean = false,
		val shouldFlashRed: Boolean = false,
		val shouldBeepSwitch: Boolean = false,
		val shouldBeepTouch: Boolean = false,
		val timerActions: List<TimerAction> = emptyList(),
		val cycleCompleted: Boolean = false,
		val debugMessage: String? = null,
		val applyColors: Boolean = false,
		val clearAll: Boolean = false,
	)

	// ── Internal state ───────────────────────────────────────────────────────

	private var _isActive: Boolean = false
	private var step: Int = 0
	private var candidates: List<Int> = emptyList()
	private var red: List<Int> = emptyList()
	private var green: List<Int> = emptyList()
	private var pendingTouchTarget: Int? = null
	private var pendingTouchRole: String? = null

	// ── Public read-only state ───────────────────────────────────────────────

	val isActive: Boolean get() = _isActive
	val currentStep: Int get() = step
	val currentRed: List<Int> get() = red
	val currentGreen: List<Int> get() = green

	// ── Constants ────────────────────────────────────────────────────────────

	private val startRed = listOf(1, 2, 4, 7)
	private val startGreen = listOf(0, 3, 5, 6)

	val sequences: Map<Int, List<String>> by lazy {
		(0..7).associateWith { computeSequenceForKey(it) }
	}

	// ── Pure logic exposed for direct testing ────────────────────────────────

	fun computeSequenceForKey(key: Int): List<String> {
		var r = startRed
		var g = startGreen
		val sequence = mutableListOf<String>()
		repeat(3) {
			val chooseRed = r.contains(key)
			sequence.add(if (chooseRed) "Red" else "Green")
			val cands = if (chooseRed) r else g
			if (sequence.size < 3) {
				val sorted = cands.sorted()
				val mid = kotlin.math.max(1, sorted.size / 2)
				r = sorted.take(mid)
				g = sorted.drop(mid)
			}
		}
		return sequence
	}

	internal fun splitCandidates() {
		val sorted = candidates.sorted()
		if (sorted.size <= 1) {
			red = sorted
			green = emptyList()
			return
		}
		val mid = kotlin.math.max(1, sorted.size / 2)
		red = sorted.take(mid)
		green = sorted.drop(mid)
	}

	// Test-only accessors so existing tests can reach internal state.
	internal fun setCandidatesForTest(c: List<Int>) {
		candidates = c
	}

	internal fun getRedForTest(): List<Int> = red

	internal fun getGreenForTest(): List<Int> = green

	// ── Reset ────────────────────────────────────────────────────────────────

	fun reset() {
		_isActive = false
		step = 0
		candidates = emptyList()
		red = emptyList()
		green = emptyList()
		pendingTouchTarget = null
		pendingTouchRole = null
	}

	// ── Event processing ─────────────────────────────────────────────────────

	fun process(event: Event): TwoSwitchResult = when (event) {
		is Event.ExternalSwitch -> handleExternalSwitch(event.role, event.switchHeld)
		Event.ExternalSwitchUp -> handleExternalSwitchUp()
		is Event.TouchDown -> handleTouchDown(event.role)
		Event.TouchUp -> handleTouchUp()
		is Event.StartCycle -> startCycleResult(event.restartOnly)
		Event.ClearColors -> clearColorsResult()
		Event.Timeout -> handleTimeout()
	}

	private fun handleExternalSwitch(role: String, switchHeld: Boolean): TwoSwitchResult {
		val flashGreen = role == "Green Switch"
		val flashRed = role == "Red Switch"
		val beepSwitch = config.beepActivation

		if (!_isActive) {
			val startResult = startCycleResult(restartOnly = config.timeoutMs > 0L)
			if (config.timeoutMs > 0L) {
				return startResult.copy(
					shouldFlashGreen = flashGreen,
					shouldFlashRed = flashRed,
					shouldBeepSwitch = beepSwitch,
					debugMessage = "[twoSwitch] restart from idle via $role, waiting for first decision",
				)
			}
		}

		val result = applySelection(role, fromTouch = false, debugTag = "[twoSwitch]")
		val timers = result.timerActions.toMutableList()

		// Activation-repeat: schedule for in-progress (non-completed) presses; a completing press must
		// CANCEL any pending activation-repeat armed by an earlier step, else it fires against the
		// freshly-restarted cycle and auto-selects a key with no user input.
		if (config.repeatActivationEnabled) {
			timers.add(if (result.cycleCompleted) TimerAction.CancelActivationRepeat else TimerAction.StartActivationRepeat(role))
		}

		// Auto-repeat: if the cycle just completed AND user is still holding the switch,
		// start auto-repeat on the activated key (mirrors original line 319).
		if (result.cycleCompleted && config.autoRepeatEnabled && switchHeld && result.activateKey != null) {
			timers.add(TimerAction.StartAutoRepeat(result.activateKey))
		}

		return result.copy(
			shouldFlashGreen = flashGreen,
			shouldFlashRed = flashRed,
			shouldBeepSwitch = beepSwitch || result.shouldBeepSwitch,
			timerActions = timers,
		)
	}

	private fun handleExternalSwitchUp(): TwoSwitchResult {
		val timerActions = mutableListOf<TimerAction>(
			TimerAction.CancelAutoRepeat,
			TimerAction.CancelActivationRepeat,
		)
		return TwoSwitchResult(
			isActive = _isActive,
			step = step,
			red = red,
			green = green,
			timerActions = timerActions,
			applyColors = _isActive,
		)
	}

	private fun handleTouchDown(role: String): TwoSwitchResult {
		// Clear any previous pending selection
		pendingTouchTarget = null
		pendingTouchRole = null

		val flashGreen = role == "Green Switch"
		val flashRed = role == "Red Switch"

		// If idle, start/restart the cycle
		val timerActions = mutableListOf<TimerAction>()
		if (!_isActive) {
			val startResult = startCycleResult(restartOnly = config.timeoutMs > 0L)
			timerActions.addAll(startResult.timerActions)
			if (config.timeoutMs > 0L) {
				return startResult.copy(
					shouldFlashGreen = flashGreen,
					shouldFlashRed = flashRed,
					shouldBeepTouch = true,
					timerActions = timerActions,
					debugMessage = "[twoSwitchTouch] restart from idle via $role, waiting for first decision",
				)
			}
		}

		when (step) {
			0 -> {
				val takeRed = role == "Red Switch"
				candidates = if (takeRed) red else green
				splitCandidates()
				step = 1
				timerActions.add(TimerAction.ScheduleTimeout(config.timeoutMs))
				return TwoSwitchResult(
					isActive = true,
					step = 1,
					red = red,
					green = green,
					shouldFlashGreen = flashGreen,
					shouldFlashRed = flashRed,
					shouldBeepTouch = true,
					timerActions = timerActions,
					applyColors = true,
					debugMessage = "[twoSwitchTouch] step1 via $role, candidates=$candidates",
				)
			}
			1 -> {
				val takeRed = role == "Red Switch"
				candidates = if (takeRed) red else green
				splitCandidates()
				step = 2
				timerActions.add(TimerAction.ScheduleTimeout(config.timeoutMs))
				return TwoSwitchResult(
					isActive = true,
					step = 2,
					red = red,
					green = green,
					shouldFlashGreen = flashGreen,
					shouldFlashRed = flashRed,
					shouldBeepTouch = true,
					timerActions = timerActions,
					applyColors = true,
					debugMessage = "[twoSwitchTouch] step2 via $role, candidates=$candidates",
				)
			}
			2 -> {
				// At step 2, prepare the target but don't trigger yet — wait for ACTION_UP
				val takeRed = role == "Red Switch"
				val target = if (takeRed) red.firstOrNull() else green.firstOrNull()
				if (target != null) {
					pendingTouchTarget = target
					pendingTouchRole = role
					return TwoSwitchResult(
						isActive = true,
						step = step,
						red = red,
						green = green,
						pendingHighlight = target,
						shouldFlashGreen = flashGreen,
						shouldFlashRed = flashRed,
						shouldBeepTouch = true,
						timerActions = timerActions,
						debugMessage = "[twoSwitchTouch] step3 DOWN via $role, pending target=$target (will trigger on UP)",
					)
				}
			}
		}

		return TwoSwitchResult(
			isActive = _isActive,
			step = step,
			red = red,
			green = green,
			shouldFlashGreen = flashGreen,
			shouldFlashRed = flashRed,
			shouldBeepTouch = true,
			timerActions = timerActions,
		)
	}

	private fun handleTouchUp(): TwoSwitchResult {
		val target = pendingTouchTarget
		val role = pendingTouchRole

		// Clear pending state
		pendingTouchTarget = null
		pendingTouchRole = null

		if (target != null) {
			val flashGreen = role == "Green Switch"
			val flashRed = role == "Red Switch"
			// Activate the target (silent — cycle completion path)
			val restartResult = startCycleResult(restartOnly = false)
			return restartResult.copy(
				activateKey = target,
				activateKeySilent = true,
				shouldFlashGreen = flashGreen,
				shouldFlashRed = flashRed,
				shouldBeepTouch = true,
				cycleCompleted = true,
				debugMessage = "[twoSwitchTouch] step3 UP, activating key=$target via $role",
			)
		}

		return TwoSwitchResult(
			isActive = _isActive,
			step = step,
			red = red,
			green = green,
		)
	}

	private fun handleTimeout(): TwoSwitchResult {
		val msg = "[twoSwitch] timeout reached (${config.timeoutMs}ms); resetting highlights"
		// clearColors() in original sets isActive=false and clears candidates
		_isActive = false
		step = 0
		red = emptyList()
		green = emptyList()
		candidates = emptyList()
		// Drop any step-2 touch target too, else a release after this timeout activates a key from
		// the cycle that was just reset.
		pendingTouchTarget = null
		pendingTouchRole = null
		return TwoSwitchResult(
			isActive = false,
			step = 0,
			red = emptyList(),
			green = emptyList(),
			clearAll = true,
			debugMessage = msg,
			timerActions = listOf(TimerAction.CancelTimeout),
		)
	}

	private fun applySelection(role: String, fromTouch: Boolean, debugTag: String): TwoSwitchResult {
		val flashGreen = role == "Green Switch"
		val flashRed = role == "Red Switch"
		val beepTouch = !fromTouch

		when (step) {
			0 -> {
				val takeRed = role == "Red Switch"
				candidates = if (takeRed) red else green
				splitCandidates()
				step = 1
				return TwoSwitchResult(
					isActive = true,
					step = 1,
					red = red,
					green = green,
					shouldFlashGreen = flashGreen,
					shouldFlashRed = flashRed,
					shouldBeepTouch = beepTouch,
					applyColors = true,
					timerActions = listOf(TimerAction.ScheduleTimeout(config.timeoutMs)),
					debugMessage = "$debugTag step1 via $role, candidates=$candidates",
				)
			}
			1 -> {
				val takeRed = role == "Red Switch"
				candidates = if (takeRed) red else green
				splitCandidates()
				step = 2
				return TwoSwitchResult(
					isActive = true,
					step = 2,
					red = red,
					green = green,
					shouldFlashGreen = flashGreen,
					shouldFlashRed = flashRed,
					shouldBeepTouch = beepTouch,
					applyColors = true,
					timerActions = listOf(TimerAction.ScheduleTimeout(config.timeoutMs)),
					debugMessage = "$debugTag step2 via $role, candidates=$candidates",
				)
			}
			2 -> {
				val takeRed = role == "Red Switch"
				val target = if (takeRed) red.firstOrNull() else green.firstOrNull()
				val msg = if (target != null) "$debugTag step3 via $role, activating key=$target" else null
				val restart = startCycleResult(restartOnly = false)
				return restart.copy(
					activateKey = target,
					activateKeySilent = target != null,
					shouldFlashGreen = flashGreen,
					shouldFlashRed = flashRed,
					shouldBeepTouch = beepTouch,
					cycleCompleted = true,
					debugMessage = msg,
				)
			}
		}
		return TwoSwitchResult(
			isActive = _isActive,
			step = step,
			red = red,
			green = green,
		)
	}

	private fun startCycleResult(restartOnly: Boolean): TwoSwitchResult {
		_isActive = true
		step = 0
		candidates = (0..7).toList()
		red = startRed
		green = startGreen
		val timerActions = mutableListOf<TimerAction>()
		if (!restartOnly) {
			timerActions.add(TimerAction.ScheduleTimeout(config.timeoutMs))
		} else {
			timerActions.add(TimerAction.CancelTimeout)
		}
		return TwoSwitchResult(
			isActive = true,
			step = 0,
			red = red,
			green = green,
			timerActions = timerActions,
			applyColors = true,
		)
	}

	private fun clearColorsResult(): TwoSwitchResult {
		red = emptyList()
		green = emptyList()
		candidates = emptyList()
		step = 0
		_isActive = false
		return TwoSwitchResult(
			isActive = false,
			step = 0,
			red = emptyList(),
			green = emptyList(),
			clearAll = true,
			timerActions = listOf(TimerAction.CancelTimeout),
		)
	}
}
