package org.continuouspath.justtype.ime

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.continuouspath.justtype.Constants.ACTION_HEAD_TRACKING_DISABLED
import org.continuouspath.justtype.Constants.ACTION_HEAD_TRACKING_ENABLED
import org.continuouspath.justtype.Constants.ACTION_HEAD_TRACKING_PAUSE
import org.continuouspath.justtype.Constants.ACTION_HEAD_TRACKING_POP_OUT
import org.continuouspath.justtype.Constants.ACTION_HEAD_TRACKING_UNPAUSE
import org.continuouspath.justtype.Constants.EXTRA_FRAME_INTERVAL_MS
import org.continuouspath.justtype.Constants.EXTRA_PACKAGE_NAME
import org.continuouspath.justtype.Constants.INPUT_METHOD_HEAD_TRACKING
import org.continuouspath.justtype.Constants.INPUT_METHOD_NONE
import org.continuouspath.justtype.Constants.KEY_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_AIM_TOLERANCE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_AUTO_FALLBACK_ACTIVE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DEADZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DEBUG_OVERLAY
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DIAG_LOGS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXITZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXIT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_KEY_ACT_THRESHOLD
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_PITCH_SCALE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_REARM_IN_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_RESPONSE_CURVE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_RESTART_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_JUSTTYPE_ENABLED_HEADBOARD
import org.continuouspath.justtype.HeadTrackingState
import org.continuouspath.justtype.R
import org.continuouspath.justtype.input.HeadInputBus
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt
import java.util.concurrent.atomic.AtomicLong

/**
 * Callbacks from [HeadTrackingSubsystem] to its host. Extends the shared
 * [HeadTrackingFeedbackCallbacks] with IME-only operations, which default to
 * no-op so non-IME surfaces (the navigation overlay) need not override them.
 */
interface HeadTrackingCallbacks : HeadTrackingFeedbackCallbacks {
	fun buttonPressed(octant: Int, shouldAbort: () -> Boolean) {}
	fun setWldGeneration(generation: Long) {}
	fun handleExternalButtonInput(buttonIndex: Int) {}

	/**
	 * Whether the UP exit gesture pops the cursor out to a text field (IME) vs.
	 * having no text field at all (nav overlay). When false, the subsystem skips
	 * the HeadBoard pop-out broadcast and lets [onExitGesture] own the behavior.
	 */
	val popsOutOnExit: Boolean get() = true

	/** Invoked on the UP exit gesture. IME pops out (default no-op here); nav minimizes. */
	fun onExitGesture() {}

	/**
	 * Invoked when head tracking is the active method but no HeadBoard frames have arrived for
	 * a sustained window — HeadBoard likely stopped, crashed, or was uninstalled mid-session.
	 * The host falls back to a usable method so the user isn't locked out. Default no-op.
	 */
	fun onHeadTrackingUnavailable() {}

	/**
	 * Called once when HeadBoard frames resume after [onHeadTrackingUnavailable] fired — i.e. the
	 * anti-lockout touch fallback can be undone and head tracking restored. Default no-op.
	 */
	fun onHeadTrackingRecovered() {}

	/**
	 * Called on any keyboard exit: clears the pending key sequence and
	 * suppresses the next automatic pull-in for ~1 s. The pull-in suppression
	 * defends against the spurious onStartInput Android fires when HeadBoard's
	 * pop-out SAW window-stack change perturbs IME focus, which would otherwise
	 * re-populate the just-cleared state from the cursor position.
	 */
	fun onKeyboardExit() {}

	/**
	 * Called on intentional keyboard re-entry (frame-gap detector or the
	 * legacy pausedFlow latch). If "Auto-Load Word at Cursor" is ON, pull
	 * in the word at the cursor — matching the behavior of a fresh tap on
	 * a word. Auto-Load OFF: no-op (user can still press Select to pull in).
	 * Bypasses the exit-time suppression window for this one call only.
	 */
	fun onKeyboardReEntry() {}
}

/**
 * Manages head-tracking input processing, zone-based highlighting, debug overlays,
 * exit-zone timer/border effects, selection-list pause styling, and HeadBoard IPC.
 *
 * Wraps [HeadTrackingState] (already-extracted state machine) and translates its
 * results into view-bridge highlight calls, exit behaviors, and key activations.
 *
 * **Threading model (Step 6b — coroutine-based):**
 * - `processingDispatcher` — single-thread coroutine dispatcher for coordinate processing (~60 fps)
 * - `wldDispatcher` — single-thread coroutine dispatcher for async word search
 * - UI updates coalesced via `StateFlow` + `collectLatest` on `Dispatchers.Main`
 * - Activation events delivered via `Channel` consumed on `Dispatchers.Main`
 * - All highlight/overlay/border methods run on main thread
 *
 * Replaces ~42 fields and ~30 methods previously in JustTypeIME.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeadTrackingSubsystem(
	private val scope: CoroutineScope,
	private val context: Context,
	private val viewBridge: HeadTrackingViewBridge,
	private val callbacks: HeadTrackingCallbacks,
	private val settingsRepo: SettingsRepository,
	// Injectable so tests can keep frame processing inside virtual time;
	// defaults are single-threaded for serialization.
	private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
	private val wldDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : InputMethod {
	// ── Drawable resource IDs ──────────────────────────────────────────
	companion object {
		internal val DRAWABLE_DEADZONE = R.drawable.button_background_deadzone
		internal val DRAWABLE_FEEDBACK = R.drawable.button_background_feedback
		internal val DRAWABLE_PALE_GREEN = R.drawable.button_background_joystick_pale_green
		internal val DRAWABLE_DARK_GREEN = R.drawable.button_background_joystick_dark_green
		internal val DRAWABLE_RED = R.drawable.button_background_joystick_red

		// Filled-blue center square. Used in two contexts:
		//   - Normal operation: shown steady when cursor is in Resting Zone.
		//   - Restart in progress: flashes on/off while cursor is in Resting Zone.
		internal val DRAWABLE_DEADZONE_FILLED = R.drawable.button_background_deadzone_filled
		internal val DRAWABLE_DEBUG_SOLID = R.drawable.debug_outline_solid
		internal val DRAWABLE_DEBUG_TRANSPARENT = R.drawable.debug_outline_transparent
		internal const val BORDER_FLASH_INTERVAL_MS = 250L
		internal const val RESUME_BORDER_FLASH_MS = 500L

		// AwaitingRest phase: center-square outline flashes at a fixed cadence
		// while the cursor is anywhere outside the Resting Zone after a resume.
		// Cadence of the center-square flash during the restart process.
		internal const val RESTART_FLASH_INTERVAL_MS = 250L

		// Diagnostic threshold for inter-frame gaps. HeadBoard normally
		// delivers ~60 fps (~17 ms inter-frame). A gap larger than this
		// likely means either (a) HeadBoard genuinely paused broadcasting
		// (popped out, lost focus) and the explicit RESUME signal was lost
		// or came from a legacy build, or (b) the main thread / processing
		// dispatcher stalled long enough to starve frame ingest. A HT_DIAG
		// log line is emitted in either case (gated by the diag-logs setting)
		// so we can spot the cause in field captures. NOTE: this detector
		// does NOT trigger an actual Restart Delay — false-firing during
		// normal typing is far more disruptive than missing a Restart Delay
		// on a true re-entry where the RESUME broadcast was lost. The
		// authoritative re-entry trigger is HeadBoard's explicit
		// ACTION_HEAD_TRACKING_RESUME broadcast.
		internal const val FRAME_GAP_LOG_THRESHOLD_MS = 1000L

		// Anti-lockout watchdog: if head tracking is active and the keyboard is shown but no
		// HeadBoard frame arrives for this long, fall back to a usable method. Frames keep
		// flowing while the cursor is paused/popped-out, so a silence this long means HeadBoard
		// actually stopped — generous enough not to fire on transient hiccups.
		internal const val HEAD_TRACKING_STALL_MS = 8000L
		private const val WATCHDOG_POLL_MS = 1000L

		// Defer window for the HeadBoard "tracking disabled" notification.
		// Android fires onFinishInput both for true session ends and for
		// transient focus shuffles (e.g. HeadBoard adding its cursor SAW
		// window during a pop-out). For the latter, onStartInput fires
		// again very quickly with the same EditorInfo — within 500 ms is
		// plenty. We use this as the cutoff: if a re-start arrives inside
		// this window, the deferred DISABLED broadcast is cancelled and
		// HeadBoard never disarms.
		internal const val HEADBOARD_DISABLE_DEFER_MS = 500L

		// Pause Mode frame throttle: interval requested from HeadBoard while paused.
		// One frame/sec is enough to notice the cursor entering the left Exit Zone
		// (the un-pause zone); the UNPAUSE broadcast then restores the full rate
		// before the dwell gesture is evaluated.
		internal const val PAUSE_MODE_FRAME_INTERVAL_MS = 1000L
	}

	/**
	 * Post-resume restart phase. After a LEFT-exit resume or pop-out
	 * auto-resume, key activations are disabled for `cachedRestartTimerMs`
	 * while the center square flashes — a buffer to prevent unintended
	 * activations from cursor wobble during re-entry. The timer is purely
	 * wall-clock; the cursor can be anywhere.
	 */
	private enum class RestartPhase { Idle, Restarting }

	// ── Center-label state messaging ─────────────────────────────────────
	//
	// During head-tracking exit / pause / resume the center square shows
	// a user-facing status message in place of the normal "Main" / current
	// word indicator. State transitions:
	//
	//   NONE → PAUSING       on entering RIGHT-exit zone (timer started)
	//   NONE → EXITING       on entering UP-exit zone (timer started)
	//   PAUSING/EXITING → NONE   on leaving EXIT zone before timer fired
	//   PAUSING → PAUSED     when RIGHT-exit timer fires
	//   EXITING → EXITED     when UP-exit timer fires
	//   * → RESUMING         when enterRestart() runs (LEFT-exit, RESUME signal)
	//   RESUMING → NONE      when restart completes (completeRestart)
	//
	// UiUpdateHandler reads the override via callbacks.getHeadTrackingCenterOverride()
	// every snapshot; setCenterLabelState(NONE) then triggers a forceUpdateUi
	// so JTUI repaints the normal label.
	private enum class CenterLabelState {
		NONE,
		PAUSING,
		PAUSED,
		EXITING,
		EXITED,
		RESUMING,
	}
	private var centerLabelState: CenterLabelState = CenterLabelState.NONE

	fun getCenterLabelOverride(): String? = when (centerLabelState) {
		CenterLabelState.NONE -> null
		CenterLabelState.PAUSING -> context.getString(R.string.head_tracking_label_pausing)
		CenterLabelState.PAUSED -> context.getString(R.string.head_tracking_label_paused)
		CenterLabelState.EXITING -> context.getString(R.string.head_tracking_label_exiting)
		CenterLabelState.EXITED -> context.getString(R.string.head_tracking_label_exited)
		CenterLabelState.RESUMING -> context.getString(R.string.head_tracking_label_resuming)
	}

	private fun setCenterLabelState(state: CenterLabelState) {
		if (centerLabelState == state) return
		centerLabelState = state
		// Always force a JTUI UI refresh — UiUpdateHandler will then read
		// our override (or null for NONE) and paint the right text.
		callbacks.forceUpdateUi()
	}
	private var pendingHeadBoardEnable = false

	// ── State machine ─────────────────────────────────────────────────
	@Volatile private var state: HeadTrackingState? = null

	// ── Highlight tracking ────────────────────────────────────────────
	private var currentHighlightIndex: Int? = null

	// Octant currently painted pale-red as the "cancelled key" cue after a
	// Correct/Backtrack gesture. Survives across frames so the red persists
	// alongside the pale-green on the corrected-to key; cleared when the
	// arming cycle ends (cursor returns to FEEDBACK or DEAD).
	private var correctedFromIndex: Int? = null
	private var currentZone: HeadTrackingState.Zone? = null
	private var currentHighlightColor: String? = null // "feedback", "pale_green", "dark_green", "dead"
	private var isCenterLabelHighlighted: Boolean = false

	// ── Debug overlay ─────────────────────────────────────────────────
	private var debugOverlayIndex: Int? = null
	private var debugOverlayOriginalForeground: Drawable? = null
	private var debugOverlayIsButton: Boolean = false

	// ── Cached preferences (background thread reads) ──────────────────
	private var cachedInputMethod: String = "direct_selection"
	private var cachedPitchScale: Float = 1.1f
	private var cachedResponseCurve: Float = 1.0f
	private var cachedDebugOverlayEnabled: Boolean = false
	private var cachedFlashKeyFeedbackEnabled: Boolean = true

	// ── Exit zone state ───────────────────────────────────────────────
	private var exitDelayMs: Long = 3000L
	private var exitTimerRunning: Boolean = false
	private var exitDirection: HeadTrackingState.ExitDirection = HeadTrackingState.ExitDirection.NONE
	private var borderFlashing: Boolean = false

	// Time-driven timer (not frame-driven). Frame deliveries are deduplicated by StateFlow
	// when the cursor is held steady, so the timer cannot advance via per-frame ticks.
	private var exitTimerJob: Job? = null
	private var flashJob: Job? = null
	private var headBoardTimeoutJob: Job? = null
	private var borderVisible: Boolean = true

	// Deferred-disable coroutine. When onInputFinished fires we don't
	// immediately tell HeadBoard "disabled" — Android also fires onFinishInput
	// for transient focus reshuffles (e.g. HeadBoard adding its cursor SAW
	// during a pop-out), and those shouldn't disarm HeadBoard. We schedule
	// the disable here; if onInputStarted fires within DISABLE_DEFER_MS, the
	// scheduled disable is cancelled (the field re-focused, never truly left).
	private var deferredDisableJob: Job? = null

	// Restart process (after LEFT-exit resume). The cursor must come to
	// rest in the Resting Zone for `cachedRestartTimerMs` before normal
	// key activations resume. While in any non-Idle phase, activations
	// are gated off and ACT/EXIT zones are treated as FEEDBACK.
	// @Volatile: written on Main, read on the pose processing thread — a stale read would let an
	// activation slip through the restart gate the background enqueue path is meant to block.
	@Volatile
	private var restartPhase: RestartPhase = RestartPhase.Idle
	private var restartFlashJob: Job? = null
	private var restartTimerJob: Job? = null
	private var cachedRestartTimerMs: Long = 1500L

	// Tracked during the restart phase so the flash coroutine knows which
	// drawable to show on ON-toggle: filled if cursor in Resting, outline
	// if in an octant.
	private var restartCursorInResting: Boolean = false
	private var restartFlashVisible: Boolean = true

	// ── Background processing (coroutine-based) ─────────────────────
	private val processResultFlow = MutableStateFlow<HeadTrackingState.ProcessResult?>(null)
	private val activationChannel = Channel<ActivationEvent>(Channel.UNLIMITED)
	var selectKeyIndex: Int = 6

	/** Exposed for JTUI constructor (replaces wldWorkerHandler). */
	val wldCoroutineDispatcher get() = wldDispatcher

	// Generation counter for async wldSelection staleness detection
	private val activationGeneration = AtomicLong(0)

	// Pause/resume flags — StateFlow for cross-coroutine visibility.
	// Restart-process gating uses `restartPhase` instead of a separate flag.
	private val pausedFlow = MutableStateFlow(false)
	private val poppedOutFlow = MutableStateFlow(false)

	// Mirror of HeadBoard's Pause Mode frame throttle: true while HeadBoard has been
	// told to drop pose frames to ~1/sec. Lets PAUSE/UNPAUSE broadcasts fire only on
	// transitions (the paused branch of applyUiUpdate runs per frame).
	private var frameRateReduced = false

	// ── Diagnostics ───────────────────────────────────────────────────
	// Gated by the "Diagnostic Logs" head-tracking developer setting
	// (KEY_HEADTRACKING_DIAG_LOGS, default true). Refreshed by loadCachedPrefs.
	private var diagEnabled: Boolean = true
	private var diagLastFrameNs: Long = 0L
	private var diagFrameCount: Int = 0
	private var diagLastReportNs: Long = 0L
	private var diagPreviousZone: HeadTrackingState.Zone? = null

	// Inter-call gap tracking for applyUiUpdate (main-thread responsiveness probe).
	// Gated by HtTimingDiagnostics.enabled.
	private var lastApplyUiUpdateNs: Long = 0L

	// Inter-arrival tracking for onCoordinatesReceived broadcasts (receiver-thread
	// probe — distinguishes "frames not arriving" from "frames arriving but blocked").
	// Gated by HtTimingDiagnostics.enabled. Single-thread access (broadcast handler).
	private var lastOnReceiveNs: Long = 0L

	// Most recent frameSeq received from HeadBoard. Used to detect dropped
	// broadcasts as gaps in the sequence (frameSeq jumps by >1 in one step).
	// Single-thread access (broadcast handler). A *decrease* indicates the
	// HeadBoard process restarted; we reset our tracking and don't log a gap.
	private var lastFrameSeq: Long = 0L

	// Wall-clock timestamp (ns) of the most recent processFrame call. Used by
	// the frame-gap detector at the top of processFrame to recognise a cursor
	// re-entry into the keyboard region (first install, or HeadBoard resuming
	// broadcasts after a pop-out to the text field). Single-thread access
	// (processingDispatcher is single-thread by construction).
	private var lastProcessedFrameNs: Long = 0L

	// Authoritative "HeadBoard alive" heartbeat for the anti-lockout watchdog: ns of the most
	// recent coordinate frame. Written on the bus pose thread, read by the Main-thread watchdog.
	@Volatile private var lastFrameReceivedNs: Long = 0L

	// One-shot latch so a single stall fires the unavailable callback once; cleared on the next frame.
	@Volatile private var headTrackingUnavailableFired: Boolean = false

	// True between the watchdog firing and the next frame — so frame resume fires onHeadTrackingRecovered once.
	@Volatile private var autoFallbackActive: Boolean = false
	private var watchdogJob: Job? = null

	// Stall clock for the watchdog + frame heartbeat; injectable so tests can
	// advance it alongside virtual coroutine time.
	@VisibleForTesting
	internal var nanoClock: () -> Long = System::nanoTime

	// ── Lifecycle ─────────────────────────────────────────────────────

	/**
	 * Subscribe to the shared head-input bus and start coroutine collectors.
	 * Must be called during onCreateInputView, before JTUI construction.
	 *
	 * The bus owns the receivers/pose thread; this listener carries the same
	 * per-callback bodies the in-subsystem receivers used to run. Frame
	 * callbacks fire on the bus pose thread (single-thread, so the diagnostics
	 * counters below stay race-free); the signal callbacks fire on the main looper.
	 */
	fun startAndRegisterReceivers() {
		HeadInputBus.addListener(context, busListener)
		// Start coroutine collectors for UI updates and activation events
		startCollectors()
	}

	private val busListener = object : HeadInputBus.HeadInputListener {
		override fun onExternalButton(buttonIndex: Int) {
			scope.launch(Dispatchers.Main) { callbacks.handleExternalButtonInput(buttonIndex) }
		}

		override fun onCoordinates(x: Float, y: Float) {
			lastFrameReceivedNs = nanoClock()
			headTrackingUnavailableFired = false
			// Frames are back after the anti-lockout fallback fired — undo the touch fallback once.
			if (autoFallbackActive) {
				autoFallbackActive = false
				scope.launch(Dispatchers.Main) { callbacks.onHeadTrackingRecovered() }
			}
			if (HtTimingDiagnostics.enabled) {
				val nowNs = System.nanoTime()
				if (lastOnReceiveNs > 0L) {
					val dtMs = (nowNs - lastOnReceiveNs) / 1_000_000L
					if (dtMs >= HtTimingDiagnostics.GAP_THRESHOLD_MS) {
						Log.d(HtTimingDiagnostics.TAG, "onReceive GAP dt=${dtMs}ms")
					}
				}
				lastOnReceiveNs = nowNs
			}
			scope.launch(processingDispatcher) { processFrame(x, y) }
		}

		override fun onFrameTimestamp(timestampMs: Long) {
			if (diagEnabled) {
				val latencyMs = SystemClock.uptimeMillis() - timestampMs
				Log.d("HT_DIAG", "E2E_LATENCY: ${latencyMs}ms")
			}
		}

		override fun onFrameSeq(seq: Long) {
			// Detect gaps in the broadcast delivery path. A jump of >1
			// since the last observed seq means at least one broadcast
			// was lost between HeadBoard and our BroadcastReceiver.
			// A *decrease* means HeadBoard restarted; reset tracking
			// silently rather than logging a giant phantom gap.
			val prev = lastFrameSeq
			if (diagEnabled && prev > 0L && seq > prev) {
				val gap = seq - prev - 1L
				if (gap > 0L) {
					Log.d("HT_DIAG", "SEQ_GAP  lost=$gap  prev=$prev  cur=$seq")
				}
			} else if (prev > 0L && seq < prev) {
				Log.d("HT_DIAG", "SEQ_RESET  prev=$prev  cur=$seq  (HeadBoard restarted)")
			}
			lastFrameSeq = seq
		}

		override fun onServiceState(stateOrdinal: Int) {
			if (pendingHeadBoardEnable) {
				pendingHeadBoardEnable = false
				headBoardTimeoutJob?.cancel()
				if (stateOrdinal == 1) {
					// HeadBoard is currently DISABLED — we need to enable it
					sendHeadBoardEnable()
					settingsRepo.putBoolean(KEY_JUSTTYPE_ENABLED_HEADBOARD, true)
				}
				// If stateOrdinal == 0 (ENABLE), HeadBoard was already on — don't set flag
			}
		}

		// HeadBoard broadcasts this when its cursor re-enters JustType's keyboard
		// region (the moment HeadBoard transitions into JustType-joystick-mode).
		// AUTHORITATIVE trigger for the Restart Delay — more reliable than inferring
		// re-entry from the 3000ms frame-gap fallback in processFrame().
		override fun onResumeSignal() {
			Log.d("HT_DIAG", "RESUME_SIGNAL received from HeadBoard — entering restart phase")
			scope.launch(Dispatchers.Main) {
				// Reset latches so processFrame() doesn't ignore frames
				// that arrive during restart. state may be null if the
				// subsystem hasn't fully come up yet — skip the hts reset
				// in that case; processFrame will start fresh anyway.
				pausedFlow.value = false
				poppedOutFlow.value = false
				// HeadBoard self-cleared its Pause Mode throttle on this same
				// joystick re-entry; resync the mirror (broadcast only if stale).
				setHeadBoardFrameRateReduced(false)
				// Reset on the processing dispatcher so it serializes with process() on that single
				// thread — resetting on Main races an in-flight frame and can leave torn state.
				state?.let { withContext(processingDispatcher) { it.reset() } }
				enterRestart()
				callbacks.onKeyboardReEntry()
			}
		}
	}

	/** Launch collectors that consume processing results and activation events. */
	private fun startCollectors() {
		// UI update collector — replaces uiHandler.post { applyUiUpdate() }
		scope.launch(Dispatchers.Main) {
			processResultFlow.collectLatest { result ->
				if (result != null) applyUiUpdate(result)
			}
		}

		// Activation event collector — replaces ConcurrentLinkedQueue polling
		scope.launch(Dispatchers.Main) {
			for (event in activationChannel) {
				if (pausedFlow.value || restartPhase != RestartPhase.Idle) continue
				if (event.beepEnabled) callbacks.playActivationBeep()
				val myGeneration = activationGeneration.incrementAndGet()
				callbacks.setWldGeneration(myGeneration)
				val shouldAbort: (() -> Boolean) = {
					activationGeneration.get() != myGeneration
				}
				val activationStartNs = if (HtTimingDiagnostics.enabled) System.nanoTime() else 0L
				if (HtTimingDiagnostics.enabled) {
					Log.d(HtTimingDiagnostics.TAG, "activation dispatch oct=${event.octant}")
				}
				callbacks.buttonPressed(event.octant, shouldAbort)
				if (HtTimingDiagnostics.enabled) {
					val elapsedMs = (System.nanoTime() - activationStartNs) / 1_000_000L
					Log.d(HtTimingDiagnostics.TAG, "activation done     oct=${event.octant} elapsedMs=$elapsedMs")
				}
			}
		}

		// Mirror this surface's pause state onto the shared bus so other surfaces
		// (the navigation overlay) can observe it. IME remains the source of truth.
		scope.launch {
			pausedFlow.collect { HeadInputBus.setPaused(it) }
		}
	}

	/**
	 * Construct [HeadTrackingState] from settings and set exit delay.
	 * Called from onCreateInputView after subsystem construction.
	 */
	override fun loadSettings(repo: SettingsRepository) {
		val deadZone = repo.getFloat(KEY_HEADTRACKING_DEADZONE)
		val activeZone = repo.getFloat(KEY_HEADTRACKING_ACTIVEZONE)
		val exitZone = repo.getFloat(KEY_HEADTRACKING_EXITZONE)
		val keyActThreshold = repo.getInt(KEY_HEADTRACKING_KEY_ACT_THRESHOLD) / 100f
		val aimTolerance = repo.getInt(KEY_HEADTRACKING_AIM_TOLERANCE) / 100f
		val rearmInFeedback = repo.getBoolean(KEY_HEADTRACKING_REARM_IN_FEEDBACK)
		val cornerBias = if (repo.contains(KEY_HEADTRACKING_CORNER_BIAS)) {
			repo.getFloat(KEY_HEADTRACKING_CORNER_BIAS)
		} else {
			val legacy = repo.getFloat(KEY_CORNER_BIAS, 1.00f)
			repo.putFloat(KEY_HEADTRACKING_CORNER_BIAS, legacy)
			legacy
		}.coerceIn(0.5f, 2.0f)
		state = HeadTrackingState(
			deadZone,
			activeZone,
			exitZone,
			keyActThreshold,
			cornerBias,
			aimTolerance,
			rearmInFeedback,
		)
		exitDelayMs = repo.getInt(KEY_HEADTRACKING_EXIT_DELAY_MS, 3000).toLong()
		cachedRestartTimerMs = repo.getInt(KEY_HEADTRACKING_RESTART_DELAY_MS, 1500).toLong().coerceIn(500L, 3000L)
	}

	/**
	 * Cache preferences for the background processing thread.
	 * Called from loadAndApplyPreferences.
	 */
	fun loadCachedPrefs(effectiveMethod: String, repo: SettingsRepository) {
		cachedInputMethod = effectiveMethod
		cachedPitchScale = repo.getFloat(KEY_HEADTRACKING_PITCH_SCALE)
		cachedResponseCurve = repo.getFloat(KEY_HEADTRACKING_RESPONSE_CURVE)
		cachedDebugOverlayEnabled = repo.getBoolean(KEY_HEADTRACKING_DEBUG_OVERLAY)
		cachedFlashKeyFeedbackEnabled = repo.getBoolean(KEY_FLASH_KEY_FEEDBACK)
		diagEnabled = repo.getBoolean(KEY_HEADTRACKING_DIAG_LOGS)
		HtTimingDiagnostics.enabled = cachedDebugOverlayEnabled
	}

	/**
	 * Reconstruct [HeadTrackingState] on zone parameter pref change.
	 * Called from the pref change listener.
	 */
	fun updateZoneParams(repo: SettingsRepository) {
		val deadZone = repo.getFloat(KEY_HEADTRACKING_DEADZONE)
		val activeZone = repo.getFloat(KEY_HEADTRACKING_ACTIVEZONE)
		val exitZone = repo.getFloat(KEY_HEADTRACKING_EXITZONE)
		val keyActThreshold = repo.getInt(KEY_HEADTRACKING_KEY_ACT_THRESHOLD) / 100f
		val aimTolerance = repo.getInt(KEY_HEADTRACKING_AIM_TOLERANCE) / 100f
		val rearmInFeedback = repo.getBoolean(KEY_HEADTRACKING_REARM_IN_FEEDBACK)
		val cornerBias = if (repo.contains(KEY_HEADTRACKING_CORNER_BIAS)) {
			repo.getFloat(KEY_HEADTRACKING_CORNER_BIAS)
		} else {
			val legacy = repo.getFloat(KEY_CORNER_BIAS, 1.00f)
			repo.putFloat(KEY_HEADTRACKING_CORNER_BIAS, legacy)
			legacy
		}.coerceIn(0.5f, 2.0f)
		state = HeadTrackingState(
			deadZone,
			activeZone,
			exitZone,
			keyActThreshold,
			cornerBias,
			aimTolerance,
			rearmInFeedback,
		)
		exitDelayMs = repo.getInt(KEY_HEADTRACKING_EXIT_DELAY_MS, 3000).toLong()
		cachedRestartTimerMs = repo.getInt(KEY_HEADTRACKING_RESTART_DELAY_MS, 1500).toLong().coerceIn(500L, 3000L)
	}

	/** Reset diagnostic counters. Called after loadSettings. */
	fun startProcessor() {
		diagFrameCount = 0
		diagLastReportNs = 0L
		diagPreviousZone = null
		Log.d("HT_DIAG", "Head tracking processor diagnostics reset")
		startUnavailableWatchdog()
	}

	/** True when head tracking is the effective primary (or legacy) input method. */
	@Suppress("DEPRECATION") // legacy key is the intended fall-through when no primary is set
	private fun isHeadTrackingActiveMethod(): Boolean {
		val primary = settingsRepo.getString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_NONE)
		val legacy = settingsRepo.getString(KEY_INPUT_METHOD, INPUT_METHOD_NONE)
		return primary == INPUT_METHOD_HEAD_TRACKING ||
			(primary == INPUT_METHOD_NONE && legacy == INPUT_METHOD_HEAD_TRACKING)
	}

	/**
	 * Anti-lockout watchdog: while head tracking is active and the keyboard is shown, fire
	 * [HeadTrackingCallbacks.onHeadTrackingUnavailable] once if no frame arrives for
	 * [HEAD_TRACKING_STALL_MS]. The window resets whenever head tracking isn't the relevant
	 * surface, so a normal method switch or hidden keyboard never trips it.
	 */
	private fun startUnavailableWatchdog() {
		watchdogJob?.cancel()
		headTrackingUnavailableFired = false
		// Re-arm recovery if an auto-fallback is still active across an input-view rebuild, so a
		// resumed frame still restores head tracking.
		autoFallbackActive = settingsRepo.getBoolean(KEY_HEADTRACKING_AUTO_FALLBACK_ACTIVE, false)
		watchdogJob = scope.launch(Dispatchers.Main) {
			// Reference time the current stall is measured from: the latest frame, or the moment
			// head tracking last became the relevant surface (covers "active but no frames yet").
			var stallSinceNs = nanoClock()
			while (isActive) {
				delay(WATCHDOG_POLL_MS)
				val now = nanoClock()
				// Don't count a stall while HT isn't the active surface, or while the cursor is paused
				// or popped out to the text field — HeadBoard legitimately stops streaming frames then,
				// so this isn't a lockout (recovery arrives via the explicit RESUME broadcast).
				if (!callbacks.isInputViewShown ||
					!isHeadTrackingActiveMethod() ||
					pausedFlow.value ||
					poppedOutFlow.value
				) {
					stallSinceNs = now
					continue
				}
				val frameNs = lastFrameReceivedNs
				if (frameNs > stallSinceNs) stallSinceNs = frameNs
				if (now - stallSinceNs >= HEAD_TRACKING_STALL_MS * 1_000_000L && !headTrackingUnavailableFired) {
					headTrackingUnavailableFired = true
					autoFallbackActive = true // arm recovery: the next frame undoes the fallback
					callbacks.debugLog("[HT watchdog] no HeadBoard frames for ${HEAD_TRACKING_STALL_MS}ms while active — falling back")
					callbacks.onHeadTrackingUnavailable()
				}
			}
		}
	}

	/** Called from onFinishInput — cancel exit timer, reset pause, clear borders. */
	fun onInputFinished() {
		cancelExitZoneTimer()
		cancelRestartJobs()
		restartPhase = RestartPhase.Idle
		pausedFlow.value = false
		poppedOutFlow.value = false
		// Pause state is reset here, so the frame throttle must be too. Matters for
		// transient focus shuffles where the deferred DISABLED broadcast is cancelled
		// and HeadBoard would otherwise keep the 1 fps throttle into the next session.
		setHeadBoardFrameRateReduced(false)
		viewBridge.showKeyboardBorder(false)
		viewBridge.hideSelectionListBorder()
		viewBridge.resetSelectionListStyling()
	}

	/** Called from onWindowShown. */
	fun onWindowShown() {
		viewBridge.resetSelectionListStyling()
		// Note: Restart Delay is NOT triggered here. onWindowShown can fire
		// while the cursor is still outside the keyboard (e.g., spurious IME
		// hide-show during a pop-out to the text field above) — triggering
		// here would show the flash before the cursor actually re-enters.
		// Instead, Restart Delay is triggered by the frame-gap detector in
		// processFrame: when the first frame arrives after a long absence
		// (first install, or HeadBoard resuming after pop-out), that frame
		// itself is the signal that the cursor has entered the keyboard.
	}

	/**
	 * Unsubscribe from the head-input bus and cancel coroutine jobs/channels.
	 * Must be called before serviceJob.cancel() in onDestroy.
	 * Note: scope is serviceScope owned by IME — we do NOT cancel it here.
	 * Safe before [startAndRegisterReceivers]: removing an unregistered listener
	 * is a no-op (the bus ref-count never went up).
	 */
	override fun destroy() {
		// Drop our bus subscription; the bus tears down receivers/thread at zero listeners.
		HeadInputBus.removeListener(busListener)

		// Close channels and cancel jobs
		watchdogJob?.cancel()
		activationChannel.close()
		flashJob?.cancel()
		headBoardTimeoutJob?.cancel()
		exitTimerJob?.cancel()
		restartFlashJob?.cancel()
		restartTimerJob?.cancel()
		deferredDisableJob?.cancel()

		Log.d("HT_DIAG", "Head tracking subsystem destroyed")
	}

	override fun cancelAndClear() {
		cancelExitZoneTimer()
		clearHighlight()
	}

	// ── Highlight methods (main thread) ────────────────────────────────

	/** Clear all head tracking highlights (dead zone, feedback, activation). */
	fun clearHighlight() {
		if (!viewBridge.isViewReady) return
		if (currentHighlightIndex == null && !isCenterLabelHighlighted && currentHighlightColor == null && correctedFromIndex == null) return

		// If transitioning out of Cancel state, restore every button first.
		restoreAllButtonsIfRed()

		// Clear button highlights
		currentHighlightIndex?.let { viewBridge.restoreButtonBackground(it) }
		currentHighlightIndex = null
		// Clear the corrected-from pale-red highlight if it was painted.
		clearCorrectedFromHighlight()

		// Clear center label highlight
		if (isCenterLabelHighlighted) {
			viewBridge.restoreCenterLabelBackground()
			isCenterLabelHighlighted = false
		}

		currentZone = null
		currentHighlightColor = null
	}

	/**
	 * Authoritative single-source-of-truth for the center label's blue
	 * "you are in Resting Zone" indicator. Called once per applyUiUpdate
	 * frame (after the per-key highlight switch and the Correct-flash
	 * paint) so the invariant holds regardless of which button-highlight
	 * helper above did or did not run its own center-clear branch.
	 *
	 * The per-key setters (setDeadZoneHighlight, setFeedbackHighlight,
	 * setPaleGreenHighlight, setDarkGreenHighlight, setAllRedHighlight)
	 * each have legacy center-clear/paint logic that remains as defensive
	 * coverage — those paths are no-ops when the state is already aligned,
	 * so the redundancy is harmless.
	 */
	private fun applyCenterLabelForZone(zone: HeadTrackingState.Zone) {
		if (!viewBridge.isViewReady) return
		val shouldBeBlue = (zone == HeadTrackingState.Zone.DEAD)
		when {
			shouldBeBlue && !isCenterLabelHighlighted -> {
				viewBridge.setCenterLabelDrawable(DRAWABLE_DEADZONE_FILLED)
				isCenterLabelHighlighted = true
			}
			!shouldBeBlue && isCenterLabelHighlighted -> {
				viewBridge.restoreCenterLabelBackground()
				isCenterLabelHighlighted = false
			}
		}
	}

	private fun setDeadZoneHighlight() {
		if (!viewBridge.isViewReady) return
		if (currentHighlightColor == "dead" && isCenterLabelHighlighted && correctedFromIndex == null) return

		// If transitioning out of Cancel state, restore every button first.
		restoreAllButtonsIfRed()

		// Clear any previous button highlights
		currentHighlightIndex?.let { viewBridge.restoreButtonBackground(it) }
		currentHighlightIndex = null
		// Returning to Resting Zone ends the arming cycle — clear the pale-red
		// "cancelled key" highlight if one is still painted from a Correct gesture.
		clearCorrectedFromHighlight()

		// Apply dead zone highlight to center label — filled blue, visible
		// whenever the cursor is in the Resting Zone during normal operation.
		viewBridge.setCenterLabelDrawable(DRAWABLE_DEADZONE_FILLED)
		isCenterLabelHighlighted = true
		currentZone = HeadTrackingState.Zone.DEAD
		currentHighlightColor = "dead"
	}

	private fun setFeedbackHighlight(index: Int) {
		if (!viewBridge.isViewReady) return
		if (index !in 0 until viewBridge.buttonCount) return
		if (currentHighlightIndex == index && currentHighlightColor == "feedback" && correctedFromIndex == null) return

		// If transitioning out of Cancel state, restore every button first.
		restoreAllButtonsIfRed()
		// Back in Feedback Zone — arming cycle is over; clear any pale-red
		// "cancelled key" highlight from a prior Correct gesture this cycle.
		clearCorrectedFromHighlight()

		// Clear center label highlight if any
		if (isCenterLabelHighlighted) {
			viewBridge.restoreCenterLabelBackground()
			isCenterLabelHighlighted = false
		}

		// Clear previous highlight if different button
		if (currentHighlightIndex != null && currentHighlightIndex != index) {
			currentHighlightIndex?.let { viewBridge.restoreButtonBackground(it) }
		}

		viewBridge.setButtonDrawable(index, DRAWABLE_FEEDBACK)
		currentHighlightIndex = index
		currentZone = HeadTrackingState.Zone.FEEDBACK
		currentHighlightColor = "feedback"
	}

	private fun setPaleGreenHighlight(index: Int) {
		if (!viewBridge.isViewReady) return
		if (index !in 0 until viewBridge.buttonCount) return
		if (currentHighlightIndex == index && currentHighlightColor == "pale_green") return

		// If transitioning out of Cancel state, restore every button first.
		restoreAllButtonsIfRed()

		if (isCenterLabelHighlighted) {
			viewBridge.restoreCenterLabelBackground()
			isCenterLabelHighlighted = false
		}

		if (currentHighlightIndex != null && currentHighlightIndex != index) {
			currentHighlightIndex?.let { viewBridge.restoreButtonBackground(it) }
		}

		viewBridge.setButtonDrawable(index, DRAWABLE_PALE_GREEN)
		currentHighlightIndex = index
		currentZone = HeadTrackingState.Zone.ACTIVATION
		currentHighlightColor = "pale_green"
	}

	private fun setDarkGreenHighlight(index: Int) {
		if (!viewBridge.isViewReady) return
		if (index !in 0 until viewBridge.buttonCount) return
		if (currentHighlightIndex == index && currentHighlightColor == "dark_green") return

		// If transitioning out of Cancel state, restore every button first.
		restoreAllButtonsIfRed()

		if (isCenterLabelHighlighted) {
			viewBridge.restoreCenterLabelBackground()
			isCenterLabelHighlighted = false
		}

		if (currentHighlightIndex != null && currentHighlightIndex != index) {
			currentHighlightIndex?.let { viewBridge.restoreButtonBackground(it) }
		}

		viewBridge.setButtonDrawable(index, DRAWABLE_DARK_GREEN)
		currentHighlightIndex = index
		currentZone = HeadTrackingState.Zone.ACTIVATION
		currentHighlightColor = "dark_green"
	}

	/**
	 * Cancel state — paint all 8 buttons light red. Used when the user has
	 * moved the cursor far enough from a locked key to signal "abort current
	 * activation attempt." Cleared by restoreAllButtonsIfRed() when any
	 * other highlight setter runs next.
	 */
	private fun setAllRedHighlight() {
		if (!viewBridge.isViewReady) return
		if (currentHighlightColor == "red") return // idempotent
		// Cancel covers all 8 buttons with red — the per-key "cancelled key"
		// tracking is superseded and will be cleared by restoreAllButtonsIfRed
		// when the next non-red setter runs.
		correctedFromIndex = null

		// Cancel-state visual replaces any prior single-key highlight or
		// dead-zone center indicator (we're definitionally past DEAD zone).
		if (isCenterLabelHighlighted) {
			viewBridge.restoreCenterLabelBackground()
			isCenterLabelHighlighted = false
		}
		// If a single key was highlighted (pale-green just before Cancel
		// triggered), its background will be overwritten by the setButtonDrawable
		// loop below — no need to restore it first.

		val count = viewBridge.buttonCount
		for (i in 0 until count) {
			viewBridge.setButtonDrawable(i, DRAWABLE_RED)
		}

		currentHighlightIndex = null // no single key carries the highlight
		currentZone = null
		currentHighlightColor = "red"
	}

	/**
	 * Paint the cancelled-from key pale-red when a Correct/Backtrack gesture
	 * fires. Skipped if the cancelled-from key is the same as the current
	 * (now-locked) highlight — the per-frame highlight setter is authoritative
	 * for that key. The red persists across subsequent frames in the same
	 * arming cycle (setPaleGreenHighlight / setDarkGreenHighlight don't
	 * touch this key) and is cleared when the cycle ends.
	 */
	private fun setCorrectedFromHighlight(index: Int) {
		if (!viewBridge.isViewReady) return
		if (index !in 0 until viewBridge.buttonCount) return
		// Don't paint over the currently-locked key — its pale-green/dark-green
		// is the authoritative highlight for the corrected-TO key.
		if (currentHighlightIndex == index) return
		// If a prior corrected-from is set on a different key, restore it first.
		correctedFromIndex?.let { prev ->
			if (prev != index) viewBridge.restoreButtonBackground(prev)
		}
		viewBridge.setButtonDrawable(index, DRAWABLE_RED)
		correctedFromIndex = index
	}

	/** Restore the corrected-from key's background and clear the tracker. */
	private fun clearCorrectedFromHighlight() {
		correctedFromIndex?.let { viewBridge.restoreButtonBackground(it) }
		correctedFromIndex = null
	}

	/**
	 * If currently in Cancel (all-keys-red) state, restore every button to its
	 * original background. Called at the top of every non-red highlight setter
	 * so that transitioning OUT of Cancel cleanly returns each key to baseline
	 * before the next single-key paint runs.
	 *
	 * Returns true if a restoration happened (currently unused, but useful for
	 * future invariants/debugging).
	 */
	private fun restoreAllButtonsIfRed(): Boolean {
		if (currentHighlightColor != "red") return false
		val count = viewBridge.buttonCount
		for (i in 0 until count) {
			viewBridge.restoreButtonBackground(i)
		}
		currentHighlightColor = null
		return true
	}

	// ── Debug overlay (main thread) ────────────────────────────────────

	/**
	 * Update debug overlay to show a red outline on the key the head position is over.
	 * Shows actual head position, not the locked position during activation.
	 */
	fun updateDebugOverlay(currentOctant: Int?, isInActivationZone: Boolean) {
		if (!viewBridge.isViewReady) return

		val targetIndex = currentOctant ?: -1

		// If same as current, just update the color if zone changed
		if (debugOverlayIndex == targetIndex) {
			val drawableRes = if (isInActivationZone) DRAWABLE_DEBUG_SOLID else DRAWABLE_DEBUG_TRANSPARENT
			if (targetIndex == -1) {
				viewBridge.setCenterLabelForeground(drawableRes)
			} else {
				viewBridge.setButtonForeground(targetIndex, drawableRes)
			}
			return
		}

		// Clear previous debug overlay
		clearDebugOverlay()

		val drawableRes = if (isInActivationZone) DRAWABLE_DEBUG_SOLID else DRAWABLE_DEBUG_TRANSPARENT

		if (targetIndex == -1) {
			// Dead zone — outline the center label
			debugOverlayOriginalForeground = viewBridge.getCenterLabelForeground()
			viewBridge.setCenterLabelForeground(drawableRes)
			debugOverlayIsButton = false
			debugOverlayIndex = targetIndex
		} else {
			// Button — outline the button at currentOctant
			if (targetIndex in 0 until viewBridge.buttonCount) {
				debugOverlayOriginalForeground = viewBridge.getButtonForeground(targetIndex)
				viewBridge.setButtonForeground(targetIndex, drawableRes)
				debugOverlayIsButton = true
				debugOverlayIndex = targetIndex
			}
		}
	}

	/** Clear the debug overlay from whatever view currently has it. */
	fun clearDebugOverlay() {
		val idx = debugOverlayIndex ?: return
		if (debugOverlayIsButton) {
			viewBridge.restoreButtonForeground(idx, debugOverlayOriginalForeground)
		} else {
			viewBridge.restoreCenterLabelForeground(debugOverlayOriginalForeground)
		}
		debugOverlayOriginalForeground = null
		debugOverlayIndex = null
	}

	// ── Background processing (bg thread) ──────────────────────────────

	/**
	 * Process a single head tracking coordinate frame on the background thread.
	 * Called for EVERY frame from HeadBoard (~60 fps).
	 */
	fun processFrame(x: Float, y: Float) {
		if (!callbacks.isJtuiInitialized) return
		if (!callbacks.isInputViewShown) return
		if (cachedInputMethod != INPUT_METHOD_HEAD_TRACKING) return
		val hts = state ?: return

		// Inter-frame gap diagnostic. HeadBoard delivers frames at ~60 fps
		// (~17 ms inter-frame) while the cursor is in the keyboard region.
		// Larger gaps suggest a real pause (cursor popped out — RESUME
		// broadcast expected on re-entry) or a main-thread / dispatcher
		// stall starving frame ingest. We only LOG here; the authoritative
		// trigger for Restart Delay is HeadBoard's ACTION_HEAD_TRACKING_RESUME
		// broadcast. Triggering restart from a frame-timing heuristic was
		// false-firing during normal typing whenever inter-frame timing
		// hiccupped — far more disruptive than the alternative (missing a
		// restart cue on a true re-entry where the RESUME signal was lost).
		val nowNs = System.nanoTime()
		val gapMs = if (lastProcessedFrameNs > 0L) {
			(nowNs - lastProcessedFrameNs) / 1_000_000L
		} else {
			Long.MAX_VALUE
		}
		lastProcessedFrameNs = nowNs
		if (diagEnabled && gapMs > FRAME_GAP_LOG_THRESHOLD_MS && gapMs != Long.MAX_VALUE) {
			Log.d("HT_DIAG", "FRAME_GAP gap=${gapMs}ms (>${FRAME_GAP_LOG_THRESHOLD_MS}ms) — RESUME broadcast may have been missed or main-thread stalled")
		}

		// (Removed: pop-out-auto-resume path that fired enterRestart on any
		// frame arriving while pausedFlow && poppedOutFlow were both true.
		// HeadBoard's broadcast pipeline routinely lets one or two frames
		// in-flight reach JustType in the ~17 ms between triggerExitBehavior(UP)
		// setting the flags and HeadBoard processing the POP_OUT broadcast.
		// Those in-flight frames would satisfy the latch and trigger a
		// Restart Delay visual cue immediately on EXIT-UP — visually
		// distracting since the user is intentionally leaving. The explicit
		// ACTION_HEAD_TRACKING_RESUME broadcast handler is now the sole
		// authoritative re-entry trigger; frames that arrive while paused
		// fall through to hts.process() below and update the state machine
		// silently — applyUiUpdate's paused branch will discard the result
		// until LEFT-exit or RESUME explicitly un-pauses.)

		// Apply pitch scale and response curve
		val scaledY = (y * cachedPitchScale).coerceIn(-1.0f, 1.0f)
		val curvedX = applyResponseCurve(x, cachedResponseCurve)
		val curvedY = applyResponseCurve(scaledY, cachedResponseCurve)

		val result = hts.process(curvedX, curvedY)

		// Diagnostic logging — reuses the nowNs captured at the top of
		// processFrame for the frame-gap detector.
		if (diagEnabled) {
			diagFrameCount++
			if (diagPreviousZone != null && result.zone != diagPreviousZone) {
				val mag = kotlin.math.hypot(curvedX.toDouble(), curvedY.toDouble()).toFloat()
				Log.d(
					"HT_DIAG",
					"ZONE $diagPreviousZone → ${result.zone}  mag=%.3f" +
						"  oct=${result.currentOctant}  locked=${result.highlightOctant}" +
						"  hl=${result.highlightState}  activate=${result.shouldActivate}"
							.format(mag),
				)
			}
			if (result.shouldActivate) {
				val mag = kotlin.math.hypot(curvedX.toDouble(), curvedY.toDouble()).toFloat()
				Log.d("HT_DIAG", "ACTIVATE oct=${result.octant}  mag=%.3f  zone=${result.zone}".format(mag))
			}
			if (result.cursorOctantJumped) {
				val mag = kotlin.math.hypot(curvedX.toDouble(), curvedY.toDouble()).toFloat()
				Log.d(
					"HT_DIAG",
					"OCTANT_JUMP  prev=${result.previousCursorOctant} -> cur=${result.currentOctant}" +
						"  zone=${result.zone}  mag=%.3f".format(mag),
				)
			}
			diagPreviousZone = result.zone
			if (diagLastReportNs > 0 && nowNs - diagLastReportNs >= 2_000_000_000L) {
				val elapsedMs = (nowNs - diagLastReportNs) / 1_000_000.0
				val fps = diagFrameCount / (elapsedMs / 1000.0)
				Log.d("HT_DIAG", "RATE: %.1f fps (%d frames in %.0f ms)".format(fps, diagFrameCount, elapsedMs))
				diagFrameCount = 0
				diagLastReportNs = nowNs
			} else if (diagLastReportNs == 0L) {
				diagLastReportNs = nowNs
			}
			diagLastFrameNs = nowNs
		}

		// Handle activation — send to channel for main-thread collector
		if (result.shouldActivate && result.octant != null && !pausedFlow.value && restartPhase == RestartPhase.Idle) {
			activationChannel.trySend(
				ActivationEvent(result.octant, callbacks.isBeepEnabled),
			)
		}

		// Emit to StateFlow — conflation handles coalescing automatically
		processResultFlow.value = result
	}

	/**
	 * Applies head tracking UI updates on the main thread.
	 * Called by the StateFlow collector with the latest ProcessResult.
	 */
	internal fun applyUiUpdate(result: HeadTrackingState.ProcessResult) {
		if (HtTimingDiagnostics.enabled) {
			val nowNs = System.nanoTime()
			if (lastApplyUiUpdateNs > 0L) {
				val dtMs = (nowNs - lastApplyUiUpdateNs) / 1_000_000L
				if (dtMs >= HtTimingDiagnostics.GAP_THRESHOLD_MS) {
					Log.d(
						HtTimingDiagnostics.TAG,
						"applyUiUpdate GAP dt=${dtMs}ms zone=${result.zone} hl=${result.highlightState} oct=${result.highlightOctant}",
					)
				}
			}
			lastApplyUiUpdateNs = nowNs
		}
		// Handle paused state — only process exit zone for resume
		if (pausedFlow.value) {
			if (result.isInExitZone && result.exitDirection == HeadTrackingState.ExitDirection.LEFT) {
				// Un-pause attempt: restore HeadBoard's full frame rate so the
				// dwell gets normally-sampled frames instead of one per second.
				setHeadBoardFrameRateReduced(false)
				handleExitZoneTimer(result.exitDirection)
			} else {
				cancelExitZoneTimer()
				// No (or failed) un-pause attempt: back to the reduced Pause Mode
				// rate. Popped-out is excluded — HeadBoard runs its normal cursor
				// outside the keyboard there and isn't streaming joystick frames.
				if (!poppedOutFlow.value) setHeadBoardFrameRateReduced(true)
			}
			return
		}

		// Handle restart process — timer is wall-clock, runs regardless of
		// cursor position. Cursor zone only affects the center-indicator
		// drawable (filled-blue in Resting, outline-only in octant) and
		// whether to paint yellow on a key. The flash coroutine handles
		// on/off toggling; this code handles per-frame zone-driven changes.
		if (restartPhase != RestartPhase.Idle) {
			val inResting = result.zone == HeadTrackingState.Zone.DEAD
			if (inResting != restartCursorInResting) {
				restartCursorInResting = inResting
				paintRestartCenter()
			}
			if (inResting) {
				// Cursor over center — no octant highlight.
				currentHighlightIndex?.let { viewBridge.restoreButtonBackground(it) }
				currentHighlightIndex = null
			} else {
				// Paint yellow on the cursor's natural octant. Bypasses
				// setFeedbackHighlight so the flashing center indicator
				// isn't cleared as a side effect.
				val octant = result.currentOctant
				if (octant != null && octant in 0 until viewBridge.buttonCount) {
					if (currentHighlightIndex != octant || currentHighlightColor != "feedback") {
						currentHighlightIndex?.takeIf { it != octant }?.let {
							viewBridge.restoreButtonBackground(it)
						}
						viewBridge.setButtonDrawable(octant, DRAWABLE_FEEDBACK)
						currentHighlightIndex = octant
						currentZone = HeadTrackingState.Zone.FEEDBACK
						currentHighlightColor = "feedback"
					}
				}
			}
			cancelExitZoneTimer()
			return
		}

		// Apply highlights based on current state
		when (result.highlightState) {
			HeadTrackingState.HighlightState.NONE -> {
				clearHighlight()
				if (result.zone == HeadTrackingState.Zone.DEAD) {
					setDeadZoneHighlight()
				}
			}
			HeadTrackingState.HighlightState.YELLOW -> {
				if (result.highlightOctant != null) {
					setFeedbackHighlight(result.highlightOctant)
				}
			}
			HeadTrackingState.HighlightState.PALE_GREEN -> {
				if (result.highlightOctant != null) {
					Log.d("HT_DIAG", "UI_HIGHLIGHT PALE_GREEN applied to oct=${result.highlightOctant}  zone=${result.zone}  locked=${result.highlightOctant}")
					setPaleGreenHighlight(result.highlightOctant)
				}
			}
			HeadTrackingState.HighlightState.DARK_GREEN -> {
				if (result.highlightOctant != null) {
					if (cachedFlashKeyFeedbackEnabled) {
						setDarkGreenHighlight(result.highlightOctant)
					} else {
						// Flash disabled: keep the armed-color highlight rather
						// than clearing — otherwise the user loses all visual
						// confirmation of which key is locked during the
						// post-activation persist period (still in ACTIVATION
						// or EXIT zone), which also produces the "keyboard
						// border shown with no key highlighted" UX glitch.
						setPaleGreenHighlight(result.highlightOctant)
					}
				}
			}
			HeadTrackingState.HighlightState.RED -> {
				// Cancel state — all 8 keys painted light red. highlightOctant
				// is null here (the visual applies globally, not per-key).
				setAllRedHighlight()
			}
		}

		// Visual cue for the Correct/Backtrack gesture: paint the cancelled-from
		// key pale-red on the frame the gesture fires. Gated by the dedicated
		// "Correction Gesture - Cancelled Key Flashes Red" setting so users
		// with visual-only feedback preferences can keep it on independently
		// of audio. Apply AFTER the per-frame highlight setter so the new
		// locked key has been painted pale-green first (setPaleGreenHighlight
		// will have restored the cancelled-from key's background as a
		// side-effect of clearing currentHighlightIndex on the cycle's prior
		// frame — we repaint it red here).
		if (result.shouldPlayCorrectTone &&
			result.correctedFromOctant != null &&
			callbacks.isCorrectionFlashRedEnabled
		) {
			setCorrectedFromHighlight(result.correctedFromOctant)
		}

		// Authoritative center-label state: filled blue iff cursor is in
		// Resting Zone. Decoupled from the per-key highlight switch above
		// so that no path through the helpers (setFeedbackHighlight et al.,
		// each of which has an early-return guard on no-state-change) can
		// leave a stale blue paint when the cursor has moved out of DEAD,
		// or fail to repaint when re-entering. paintRestartCenter / paused
		// paths return early above and aren't reached here, so this only
		// runs during normal head-tracking operation.
		applyCenterLabelForZone(result.zone)

		// Audio cues for Correct (incl. Backtrack) and Cancel gestures. The
		// Correct tone has its own toggle so vision-impaired users can keep
		// it on independently of "Beep when key is activated"; the Cancel
		// tone (rare, abort cue) stays gated by the general beep setting.
		if (result.shouldPlayCorrectTone && callbacks.isCorrectionBeepEnabled) {
			callbacks.playCorrectTone()
		}
		if (callbacks.isBeepEnabled && result.shouldPlayCancelTone) {
			callbacks.playCancelTone()
		}

		// Handle exit zone timer
		if (result.zone == HeadTrackingState.Zone.EXIT) {
			handleExitZoneTimer(result.exitDirection)
		} else {
			cancelExitZoneTimer()
		}

		// Update debug overlay if enabled
		if (cachedDebugOverlayEnabled) {
			val actualOctant = if (result.zone == HeadTrackingState.Zone.DEAD) null else result.currentOctant
			val isInActivationZone = result.zone == HeadTrackingState.Zone.ACTIVATION ||
				result.zone == HeadTrackingState.Zone.EXIT
			updateDebugOverlay(actualOctant, isInActivationZone)
		} else {
			if (debugOverlayIndex != null) {
				clearDebugOverlay()
			}
		}
	}

	// ── Math ───────────────────────────────────────────────────────────

	internal fun applyResponseCurve(value: Float, exponent: Float): Float {
		if (exponent == 1.0f) return value
		val sign = if (value < 0) -1f else 1f
		return sign * Math.pow(kotlin.math.abs(value).toDouble(), exponent.toDouble()).toFloat()
	}

	// ── Exit zone (main thread) ────────────────────────────────────────

	private fun handleExitZoneTimer(exitDirection: HeadTrackingState.ExitDirection) {
		// Idempotent: once the timer is running, frame-driven re-invocations are no-ops.
		// The coroutine below advances by wall-clock time so steady-state holds still fire
		// even when StateFlow conflates identical ProcessResults.
		if (exitTimerRunning) return

		// Center-label state: while the exit-delay timer is running, show
		// "Pausing"/"Exiting" so the user has feedback that the gesture has
		// been registered. LEFT is the un-pause direction and DOWN is unused
		// — leave any existing PAUSED / EXITED state in place for those.
		when (exitDirection) {
			HeadTrackingState.ExitDirection.UP -> setCenterLabelState(CenterLabelState.EXITING)
			HeadTrackingState.ExitDirection.RIGHT -> setCenterLabelState(CenterLabelState.PAUSING)
			else -> { /* keep prior state */ }
		}

		this.exitDirection = exitDirection
		exitTimerRunning = true
		borderFlashing = false
		viewBridge.showKeyboardBorder(true)

		val halfDelay = exitDelayMs / 2
		val remainder = exitDelayMs - halfDelay
		exitTimerJob = scope.launch {
			delay(halfDelay)
			borderFlashing = true
			startBorderFlashing()
			delay(remainder)
			// Reset timer state BEFORE firing so triggerExitBehavior can repaint the border
			// without us immediately wiping it (RIGHT shows the selection-list border; LEFT
			// flashes the keyboard border for RESUME_BORDER_FLASH_MS).
			finalizeExitTimer()
			triggerExitBehavior(exitDirection)
		}
	}

	/** Cancel the exit zone timer and stop any flashing. */
	fun cancelExitZoneTimer() {
		if (!exitTimerRunning) return
		exitTimerJob?.cancel()
		finalizeExitTimer()
		// Clear the transient "Pausing"/"Exiting" label when the user moves
		// out of the exit zone before the timer fires. Leave PAUSED / EXITED
		// / RESUMING in place — those are post-trigger states owned by other
		// transitions.
		if (centerLabelState == CenterLabelState.PAUSING ||
			centerLabelState == CenterLabelState.EXITING
		) {
			setCenterLabelState(CenterLabelState.NONE)
		}
	}

	private fun finalizeExitTimer() {
		exitTimerJob = null
		exitTimerRunning = false
		borderFlashing = false
		exitDirection = HeadTrackingState.ExitDirection.NONE
		stopBorderFlashing()
		viewBridge.showKeyboardBorder(false)
	}

	private fun startBorderFlashing() {
		stopBorderFlashing()
		borderVisible = true
		flashJob = scope.launch {
			while (isActive) {
				delay(BORDER_FLASH_INTERVAL_MS)
				borderVisible = !borderVisible
				viewBridge.showKeyboardBorder(borderVisible)
			}
		}
	}

	private fun stopBorderFlashing() {
		flashJob?.cancel()
		flashJob = null
		borderVisible = true
	}

	// ── Restart process (post-resume) ─────────────────────────────────

	/**
	 * Enter the Restarting phase after a LEFT-exit resume or pop-out
	 * auto-resume. Suppresses key activations for `cachedRestartTimerMs`
	 * while the center square flashes; cursor can be anywhere.
	 */
	private fun enterRestart() {
		cancelRestartJobs()
		restartPhase = RestartPhase.Restarting
		restartFlashVisible = true
		setCenterLabelState(CenterLabelState.RESUMING)
		// The initial in-Resting state is updated by the first applyUiUpdate
		// frame; start with the outline drawable as a safe default.
		paintRestartCenter()
		restartFlashJob = scope.launch {
			while (isActive) {
				delay(RESTART_FLASH_INTERVAL_MS)
				restartFlashVisible = !restartFlashVisible
				paintRestartCenter()
			}
		}
		restartTimerJob = scope.launch {
			delay(cachedRestartTimerMs)
			completeRestart()
		}
	}

	/**
	 * Paint the center indicator during the Restarting phase, reflecting
	 * (a) the current flash state (visible vs hidden) and (b) the cursor's
	 * current zone (filled-blue if in Resting, outline if in an octant).
	 * Called from both the flash coroutine (on each toggle) and applyUiUpdate
	 * (when the cursor enters/leaves the Resting Zone) — either source can
	 * trigger a visual change.
	 */
	private fun paintRestartCenter() {
		if (restartPhase != RestartPhase.Restarting) return
		if (restartFlashVisible) {
			val drawable = if (restartCursorInResting) DRAWABLE_DEADZONE_FILLED else DRAWABLE_DEADZONE
			viewBridge.setCenterLabelDrawable(drawable)
			isCenterLabelHighlighted = true
		} else {
			viewBridge.restoreCenterLabelBackground()
			isCenterLabelHighlighted = false
		}
	}

	/**
	 * Successful end of restart: stop flashing, play the Correct tone,
	 * transition to Idle so activations resume. The next applyUiUpdate
	 * paints the normal-operation center indicator based on cursor zone.
	 */
	private fun completeRestart() {
		cancelRestartJobs()
		restartPhase = RestartPhase.Idle
		setCenterLabelState(CenterLabelState.NONE)
		// Settle the center indicator to whatever the cursor's current
		// zone calls for. Easiest is to clear here and let the next
		// applyUiUpdate frame re-paint correctly.
		viewBridge.restoreCenterLabelBackground()
		isCenterLabelHighlighted = false
		currentZone = null
		currentHighlightColor = null
		callbacks.playCorrectTone()
	}

	/** Cancel both flash and timer jobs (lifecycle cleanup). */
	private fun cancelRestartJobs() {
		restartFlashJob?.cancel()
		restartFlashJob = null
		restartTimerJob?.cancel()
		restartTimerJob = null
	}

	private fun triggerExitBehavior(direction: HeadTrackingState.ExitDirection) {
		when (direction) {
			HeadTrackingState.ExitDirection.UP -> {
				pausedFlow.value = true
				setCenterLabelState(CenterLabelState.EXITED)
				clearHighlight()
				// Finalize any composing preview and clear the pending key
				// sequence / selection list. The leading autospace stays
				// in the text field — the just-typed word is now permanent.
				// Pull-in is suppressed to defend against the spurious
				// onStartInput during HeadBoard's pop-out window-stack reshuffle.
				callbacks.onKeyboardExit()
				if (callbacks.popsOutOnExit) {
					// IME: cursor pops out to the text field above the keyboard.
					poppedOutFlow.value = true
					notifyHeadBoardPopOut()
				}
				// Nav minimizes here; IME default is a no-op.
				callbacks.onExitGesture()
			}
			HeadTrackingState.ExitDirection.RIGHT -> {
				pausedFlow.value = true
				setCenterLabelState(CenterLabelState.PAUSED)
				clearHighlight()
				// Pull-in suppression is defensive on Pause — typically no
				// window-stack reshuffle here, but keeping exit paths
				// consistent protects against future regressions.
				callbacks.onKeyboardExit()
				viewBridge.showSelectionListPaused(
					context.getString(R.string.head_tracking_paused),
				)
				viewBridge.showSelectionListBorder()
				// Pause Mode: drop HeadBoard to ~1 frame/sec. The paused branch of
				// applyUiUpdate watches those frames for the left-Exit-Zone un-pause.
				setHeadBoardFrameRateReduced(true)
			}
			HeadTrackingState.ExitDirection.LEFT -> {
				if (pausedFlow.value) {
					pausedFlow.value = false
					// Normally a no-op — the un-pause attempt already restored the
					// rate — but guarantees we never resume typing at 1 fps.
					setHeadBoardFrameRateReduced(false)
					viewBridge.hideSelectionListPaused()
					callbacks.forceUpdateUi()
					viewBridge.hideSelectionListBorder()
					viewBridge.showKeyboardBorder(true)
					scope.launch {
						delay(RESUME_BORDER_FLASH_MS)
						viewBridge.showKeyboardBorder(false)
					}
					// Begin restart process — activations stay disabled until
					// the cursor comes to rest in the Resting Zone long enough.
					enterRestart()
				}
			}
			else -> { /* Do nothing for DOWN or NONE */ }
		}
	}

	// ── HeadBoard IPC ──────────────────────────────────────────────────

	/**
	 * Tell HeadBoard to throttle pose frames to the Pause Mode rate
	 * ([PAUSE_MODE_FRAME_INTERVAL_MS], ~1 fps) or restore its normal rate.
	 * Deduplicated via [frameRateReduced] — callers may invoke per frame;
	 * a broadcast is only sent on an actual transition. HeadBoard also
	 * self-clears its throttle on joystick re-entry and on ENABLED /
	 * DISABLED / POP_OUT, so a lost UNPAUSE cannot strand it at 1 fps.
	 */
	private fun setHeadBoardFrameRateReduced(reduced: Boolean) {
		if (frameRateReduced == reduced) return
		frameRateReduced = reduced
		val intent = Intent(if (reduced) ACTION_HEAD_TRACKING_PAUSE else ACTION_HEAD_TRACKING_UNPAUSE).apply {
			putExtra(EXTRA_PACKAGE_NAME, context.packageName)
			if (reduced) putExtra(EXTRA_FRAME_INTERVAL_MS, PAUSE_MODE_FRAME_INTERVAL_MS)
		}
		runCatching { context.sendBroadcast(intent) }
	}

	/** Notify HeadBoard to pop out the cursor to the text field. */
	fun notifyHeadBoardPopOut() {
		val intent = Intent(ACTION_HEAD_TRACKING_POP_OUT).apply {
			putExtra(EXTRA_PACKAGE_NAME, context.packageName)
		}
		runCatching { context.sendBroadcast(intent) }
	}

	/** Notify HeadBoard of current head tracking state. */
	fun notifyHeadBoardOfTrackingState() {
		val isHeadTracking = isHeadTrackingActiveMethod()

		val action = if (isHeadTracking) ACTION_HEAD_TRACKING_ENABLED else ACTION_HEAD_TRACKING_DISABLED
		val intent = Intent(action).apply {
			putExtra(EXTRA_PACKAGE_NAME, context.packageName)
		}
		runCatching { context.sendBroadcast(intent) }

		if (isHeadTracking) {
			enableHeadBoardIfNeeded()
		} else {
			disableHeadBoardIfWeEnabled()
		}
	}

	/**
	 * Schedule a HeadBoard "tracking disabled" broadcast, deferred by
	 * [HEADBOARD_DISABLE_DEFER_MS]. If [cancelDeferredHeadBoardDisable] is
	 * called before the delay elapses (i.e. onStartInput fires soon after
	 * onFinishInput — Android's signal for a transient focus shuffle, not
	 * a true session end), the broadcast is never sent and HeadBoard stays
	 * in its armed/joystick state. Replaces the previous unconditional
	 * `notifyHeadBoardDisabled` to defend against the spurious
	 * onFinishInput that Android fires when HeadBoard adds its cursor SAW
	 * during pop-out / pop-in.
	 */
	fun scheduleHeadBoardDisable() {
		deferredDisableJob?.cancel()
		deferredDisableJob = scope.launch {
			delay(HEADBOARD_DISABLE_DEFER_MS)
			notifyHeadBoardDisabled()
			deferredDisableJob = null
		}
	}

	/** Immediately broadcast "head-tracking off" (no defer). */
	private fun notifyHeadBoardDisabled() {
		val intent = Intent(ACTION_HEAD_TRACKING_DISABLED).apply {
			putExtra(EXTRA_PACKAGE_NAME, context.packageName)
		}
		runCatching { context.sendBroadcast(intent) }
	}

	/** Cancel a pending deferred disable. Called from onStartInput. */
	fun cancelDeferredHeadBoardDisable() {
		deferredDisableJob?.cancel()
		deferredDisableJob = null
	}

	private fun enableHeadBoardIfNeeded() {
		pendingHeadBoardEnable = true
		val intent = Intent("REQUEST_SERVICE_STATE").apply {
			putExtra("state", "main")
		}
		runCatching { context.sendBroadcast(intent) }

		headBoardTimeoutJob?.cancel()
		headBoardTimeoutJob = scope.launch {
			delay(500)
			if (pendingHeadBoardEnable) {
				pendingHeadBoardEnable = false
				sendHeadBoardEnable()
				settingsRepo.putBoolean(KEY_JUSTTYPE_ENABLED_HEADBOARD, true)
			}
		}
	}

	private fun sendHeadBoardEnable() {
		val intent = Intent("CHANGE_SERVICE_STATE").apply {
			putExtra("state", 0) // ServiceState.ENABLE ordinal
		}
		runCatching { context.sendBroadcast(intent) }
	}

	private fun disableHeadBoardIfWeEnabled() {
		// CRITICAL: cancel any pending enable BEFORE the early-return below.
		// Without this, a quick disable-after-enable sequence (e.g. user
		// unchecks the Head Tracking checkbox in InputMethodsActivity, which
		// writes KEY_INPUT_METHOD_PRIMARY=NONE and then KEY_INPUT_METHOD=
		// direct_selection in close succession) leaves the scheduled enable
		// in `headBoardTimeoutJob` (and the `pendingHeadBoardEnable` flag)
		// alive — the legacy-fallback branch in notifyHeadBoardOfTrackingState
		// triggered an enable on the first write before the second write
		// arrived to disable. The scheduled enable would then re-fire ~500ms
		// later, restarting HeadBoard tracking the user just turned off.
		pendingHeadBoardEnable = false
		headBoardTimeoutJob?.cancel()
		headBoardTimeoutJob = null

		if (!settingsRepo.getBoolean(KEY_JUSTTYPE_ENABLED_HEADBOARD, false)) return

		val intent = Intent("CHANGE_SERVICE_STATE").apply {
			putExtra("state", 1) // ServiceState.DISABLE ordinal
		}
		runCatching { context.sendBroadcast(intent) }
		settingsRepo.putBoolean(KEY_JUSTTYPE_ENABLED_HEADBOARD, false)
	}

	private data class ActivationEvent(val octant: Int, val beepEnabled: Boolean)
}
