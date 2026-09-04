package org.continuouspath.justtype.ime

import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants.KEY_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_AUTOREPEAT_MODE
import org.continuouspath.justtype.Constants.KEY_BEEP_EACH_SCAN_STEP
import org.continuouspath.justtype.Constants.KEY_INITIAL_SCAN_DELAY_INCREASE_SEC
import org.continuouspath.justtype.Constants.KEY_LAYOUT_MODE
import org.continuouspath.justtype.Constants.KEY_SCAN_REPEAT_COUNT
import org.continuouspath.justtype.Constants.KEY_SCAN_STEP_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_SCAN_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_SELECT_KEY_TRIGGERS_SCAN
import org.continuouspath.justtype.Constants.KEY_SHOW_NEXT_KEY
import org.continuouspath.justtype.Constants.KEY_SKIP_KEYS_NO_VALID
import org.continuouspath.justtype.Constants.MODE_OPT
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.continuouspath.justtype.ScanState
import org.continuouspath.justtype.logic.JTUISnapshot
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt
import org.continuouspath.justtype.settings.getString

/**
 * Callbacks from [ScanSubsystem] to the IME for shared operations
 * that the scan subsystem doesn't own.
 */
interface ScanCallbacks {
	/** Flash the switch bar on activation. */
	fun flashSwitchBar()

	/** Beep on switch activation. */
	fun beepSwitchActivation()

	/** Auto-learn switch code (persists to settings). */
	fun autoLearnSwitchCode(keyCode: Int)
}

/**
 * Self-contained single-switch scanning subsystem. Thin shell around [ScanState] that
 * owns coroutine timers, audio, view-bridge IO, and settings reads. State decisions
 * (which key is highlighted, when to advance, when to stop) live in [ScanState].
 *
 * Communicates with the IME through [HighlightBridge], [KeyActivationSink], and
 * [ScanCallbacks].
 */
class ScanSubsystem(
	private val scope: CoroutineScope,
	private val highlightBridge: HighlightBridge,
	private val keySink: KeyActivationSink,
	private val callbacks: ScanCallbacks,
	// Explicit scan sequence; null keeps the IME's frequency order. Nav passes a clockwise order.
	private val scanOrderOverride: List<Int>? = null,
) : InputMethod {

	// --- Pure state machine ---
	private val state = ScanState()

	// --- Subsystem-owned mutable state ---
	private var scanSwitchCode: Int = SWITCH_CODE_UNDEFINED

	// --- Public state mirrors (read by IME for cross-boundary concerns) ---
	val isActive: Boolean get() = state.isActive
	val highlightedIndex: Int? get() = state.currentHighlight
	val nextHighlightIndex: Int? get() = state.currentNextHighlight

	// Last-applied highlights so we know what to restore when they change.
	private var lastHighlightedIdx: Int? = null
	private var lastNextHighlightIdx: Int? = null

	// --- Timers ---
	private var scanTimerJob: Job? = null
	private var scanAutoRepeatJob: Job? = null

	// --- Audio ---
	private var scanToneGenerator: ToneGenerator? = null

	// --- Constants ---
	val selectKeyIndex = ScanState.SELECT_KEY_INDEX
	private val highlightColor = android.graphics.Color.parseColor("#FFD600")
	private val nextHighlightColor = android.graphics.Color.parseColor("#FFFBD6")

	// --- Lifecycle ---

	override fun loadSettings(repo: SettingsRepository) {
		val scanLayoutPref = repo.getString(KEY_LAYOUT_MODE)
		val layoutOptimized = scanLayoutPref == MODE_OPT
		scanSwitchCode = repo.getInt(KEY_SCAN_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
		val scanRepeatSaved = repo.getInt(KEY_SCAN_REPEAT_COUNT).coerceIn(0, 10)
		if (scanRepeatSaved != repo.getInt(KEY_SCAN_REPEAT_COUNT)) {
			repo.putInt(KEY_SCAN_REPEAT_COUNT, scanRepeatSaved)
		}
		autoRepeatDelayMs = (repo.getFloat(KEY_AUTOREPEAT_DELAY_SEC).coerceIn(0.25f, 3.0f) * 1000L).toLong()
		val config = ScanState.Config(
			stepDelayMs = (repo.getFloat(KEY_SCAN_STEP_DELAY_SEC).coerceIn(0.25f, 3.0f) * 1000L).toLong(),
			firstExtraDelayMs = (repo.getFloat(KEY_INITIAL_SCAN_DELAY_INCREASE_SEC).coerceIn(0f, 1.5f) * 1000L).toLong(),
			repeatCount = scanRepeatSaved,
			skipInvalid = repo.getBoolean(KEY_SKIP_KEYS_NO_VALID),
			showNextKey = repo.getBoolean(KEY_SHOW_NEXT_KEY),
			selectTriggersList = repo.getBoolean(KEY_SELECT_KEY_TRIGGERS_SCAN),
			layoutOptimized = layoutOptimized,
			autoRepeatEnabled = repo.getBoolean(KEY_AUTOREPEAT_MODE),
			beepEachStep = repo.getBoolean(KEY_BEEP_EACH_SCAN_STEP),
			scanOrderOverride = scanOrderOverride,
		)
		state.applyConfig(config)

		// Initialize tone generator if needed
		if (scanToneGenerator == null) {
			try {
				scanToneGenerator = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
			} catch (_: RuntimeException) {
				// ToneGenerator may fail on some devices
			}
		}
	}

	override fun destroy() {
		scanTimerJob?.cancel()
		scanAutoRepeatJob?.cancel()
		scanToneGenerator?.release()
		scanToneGenerator = null
	}

	override fun cancelAndClear() = stopScan(clearHighlight = true)

	// --- Switch events ---

	fun handleSwitchDown(keyCode: Int) {
		callbacks.flashSwitchBar()
		callbacks.beepSwitchActivation()
		cancelScanTimer()
		// Auto-learn switch if unset
		if (scanSwitchCode == SWITCH_CODE_UNDEFINED) {
			scanSwitchCode = keyCode
			callbacks.autoLearnSwitchCode(keyCode)
		}
		dispatch(state.process(ScanState.Event.SwitchDown))
	}

	fun handleSwitchUp() {
		cancelScanAutoRepeat()
		dispatch(state.process(ScanState.Event.SwitchUp))
	}

	// --- Scan control ---

	// Kicks off a fresh cycle like a SwitchDown, minus switch-code auto-learn.
	fun startScan() {
		dispatch(state.process(ScanState.Event.SwitchDown))
	}

	fun stopScan(clearHighlight: Boolean = true) {
		cancelScanTimer()
		cancelScanAutoRepeat()
		if (clearHighlight) {
			dispatch(state.process(ScanState.Event.Stop))
		} else {
			// Caller wants to keep highlights visible (e.g. inside a SwitchDown handler
			// that's about to restart a fresh cycle). The state machine's Stop clears
			// highlights — bypass it here and only mark the cycle inactive.
			state.markInactiveKeepHighlights()
		}
	}

	// --- UI state update ---

	fun updateValidMask(ui: JTUISnapshot) {
		dispatch(state.process(ScanState.Event.UiSnapshot(ui)))
	}

	// --- Pure-logic accessors (kept for test compatibility) ---

	internal fun computeScanOrder(): List<Int> = state.computeScanOrder()

	internal fun buildValidOrder(): List<Int> = state.buildValidOrder()

	// --- Internal: dispatch a state result to bridges/timers/audio ---

	private fun dispatch(result: ScanState.ScanResult) {
		// Highlights — restore old slots that changed, apply new ones.
		val highlightChanged = result.highlightedIdx != lastHighlightedIdx ||
			result.nextHighlightIdx != lastNextHighlightIdx
		if (highlightChanged) {
			lastHighlightedIdx?.let { highlightBridge.restoreButton(it) }
			lastNextHighlightIdx?.let { idx ->
				if (idx != result.highlightedIdx) highlightBridge.restoreButton(idx)
			}
			val highlights = mutableMapOf<Int, Int>()
			result.highlightedIdx?.let { highlights[it] = highlightColor }
			result.nextHighlightIdx?.let { highlights[it] = nextHighlightColor }
			if (highlights.isNotEmpty()) {
				highlightBridge.highlightButtons(highlights)
			}
			lastHighlightedIdx = result.highlightedIdx
			lastNextHighlightIdx = result.nextHighlightIdx
		}

		// Beep
		if (result.shouldBeepStep) {
			scanToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
		}

		// Activation signals
		result.activateKey?.let { keySink.activateKey(it) }
		if (result.activateSelect) keySink.activateSelect()

		// Auto-repeat
		if (result.cancelAutoRepeat) cancelScanAutoRepeat()
		result.startAutoRepeatForKey?.let { startScanAutoRepeat(it) }

		// Tick scheduling
		if (result.nextTickDelayMs != null) {
			scheduleScanStep(result.nextTickDelayMs)
		}
	}

	private fun scheduleScanStep(delayMs: Long) {
		cancelScanTimer()
		scanTimerJob = scope.launch {
			delay(delayMs.coerceAtLeast(0L))
			dispatch(state.process(ScanState.Event.Tick))
		}
	}

	private fun cancelScanTimer() {
		scanTimerJob?.cancel()
		scanTimerJob = null
	}

	private fun startScanAutoRepeat(index: Int) {
		scanAutoRepeatJob?.cancel()
		scanAutoRepeatJob = scope.launch {
			delay(autoRepeatDelayMs)
			if (state.isActive) {
				keySink.activateKey(index)
				startScanAutoRepeat(index)
			}
		}
	}

	private fun cancelScanAutoRepeat() {
		scanAutoRepeatJob?.cancel()
		scanAutoRepeatJob = null
	}

	private var autoRepeatDelayMs: Long = 1000L
}
