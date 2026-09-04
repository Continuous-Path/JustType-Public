package org.continuouspath.justtype

import org.continuouspath.justtype.logic.JTUISnapshot

/**
 * Pure-Kotlin state machine for single-switch scanning.
 *
 * Owns the scan progression logic: which key is highlighted, when to advance, when to
 * stop after `repeatCount` passes without activation, list-mode entry/exit, and the
 * valid-key mask derived from JTUI snapshots.
 *
 * Stays out of: coroutine timers, view-bridge IO, audio (beep/flash), settings IO.
 * Those concerns live in [org.continuouspath.justtype.ime.ScanSubsystem], which feeds events
 * to this state and dispatches the resulting [ScanResult] snapshot.
 *
 * Mirrors the shape of [JoystickState] / [HeadTrackingState]: deterministic, snapshot
 * return, no side effects.
 */
class ScanState {

	// ── Configuration ────────────────────────────────────────────────────────

	data class Config(
		val stepDelayMs: Long = 1000L,
		val firstExtraDelayMs: Long = 0L,
		val repeatCount: Int = 3,
		val skipInvalid: Boolean = false,
		val showNextKey: Boolean = false,
		val selectTriggersList: Boolean = false,
		val layoutOptimized: Boolean = false,
		val autoRepeatEnabled: Boolean = false,
		val beepEachStep: Boolean = false,
		// Explicit scan sequence (button indices). When set, overrides the IME's frequency
		// orders — Nav uses a clockwise spatial order. Null = use computeScanOrder()'s defaults.
		val scanOrderOverride: List<Int>? = null,
	)

	private var config: Config = Config()

	fun applyConfig(newConfig: Config) {
		config = newConfig
	}

	// ── Events ───────────────────────────────────────────────────────────────

	sealed interface Event {
		object SwitchDown : Event
		object SwitchUp : Event
		object Tick : Event
		data class UiSnapshot(val snapshot: JTUISnapshot) : Event
		object Stop : Event
	}

	// ── Result snapshot ──────────────────────────────────────────────────────

	data class ScanResult(
		val isActive: Boolean,
		val highlightedIdx: Int?,
		val nextHighlightIdx: Int?,
		// Fire-and-forget actions for the subsystem to dispatch:
		val activateKey: Int? = null,
		val activateSelect: Boolean = false,
		val shouldBeepStep: Boolean = false,
		val nextTickDelayMs: Long? = null,
		val listMode: Boolean = false,
		val startAutoRepeatForKey: Int? = null,
		val cancelAutoRepeat: Boolean = false,
	)

	// ── Internal state ───────────────────────────────────────────────────────

	private var _isActive: Boolean = false
	private var listMode: Boolean = false
	private var scanOrder: List<Int> = (0..7).toList()
	private var validOrder: List<Int> = (0..7).toList()
	private var indexInOrder: Int = 0
	private var passesWithoutActivation: Int = 0
	private var validKeyMask: BooleanArray = BooleanArray(8) { true }
	private var highlightedIdx: Int? = null
	private var nextHighlightIdx: Int? = null

	// ── Public read-only state ───────────────────────────────────────────────

	val isActive: Boolean get() = _isActive
	val isListMode: Boolean get() = listMode
	val currentHighlight: Int? get() = highlightedIdx
	val currentNextHighlight: Int? get() = nextHighlightIdx

	companion object {
		const val SELECT_KEY_INDEX = 6
		const val UNDO_KEY_INDEX = 1

		/**
		 * Canonical scan sequences (Cliff, 2026-08-13), consumed by both the
		 * highlight stepping here and the physical row arrangement in
		 * ScanLayoutController — one source, never let them drift.
		 *
		 * Optimized: Select first, UnDo/Delete last, ambiguous keys between
		 * in DESCENDING summed letter frequency (frequency-weighted over the
		 * shipped EnglishWordsRaw): ojvhdx 20.0% > gemz 16.8% > iskw 16.5% >
		 * trp 15.75% > banq 15.74% > lufcy 15.2%. English-derived; per-
		 * language orders are a later refinement.
		 *
		 * Alphabetic: stays in ALPHABETICAL key order (abcd efgh ijklm nopqr
		 * stu vwxyz) by design — no frequency sort (Cliff's ruling).
		 */
		val SCAN_ORDER_OPTIMIZED = listOf(6, 7, 0, 3, 2, 5, 4, 1)
		val SCAN_ORDER_ALPHA = listOf(6, 0, 3, 5, 2, 4, 7, 1)
	}

	// ── Pure logic exposed for direct testing ────────────────────────────────

	fun computeScanOrder(): List<Int> = config.scanOrderOverride
		?: if (config.layoutOptimized) SCAN_ORDER_OPTIMIZED else SCAN_ORDER_ALPHA

	fun buildValidOrder(): List<Int> {
		val base = scanOrder
		if (!config.skipInvalid || !validKeyMask.any { it }) return base
		val filtered = base.filter { idx -> validKeyMask.getOrElse(idx) { true } }
		return if (filtered.isEmpty()) base else filtered
	}

	// ── Reset / lifecycle ────────────────────────────────────────────────────

	fun reset() {
		_isActive = false
		listMode = false
		indexInOrder = 0
		passesWithoutActivation = 0
		highlightedIdx = null
		nextHighlightIdx = null
	}

	/**
	 * Mark the cycle inactive but preserve highlight slots. Used by callers that want
	 * to suspend scanning without visually clearing the keys (e.g. between cycle
	 * restarts).
	 */
	fun markInactiveKeepHighlights() {
		_isActive = false
		listMode = false
	}

	// ── Event processing ─────────────────────────────────────────────────────

	fun process(event: Event): ScanResult = when (event) {
		Event.SwitchDown -> handleSwitchDown()
		Event.SwitchUp -> handleSwitchUp()
		Event.Tick -> handleTick()
		Event.Stop -> handleStop()
		is Event.UiSnapshot -> handleUiSnapshot(event.snapshot)
	}

	private fun handleSwitchDown(): ScanResult {
		// Idle → start fresh
		if (!_isActive) {
			passesWithoutActivation = 0
			scanOrder = computeScanOrder()
			validOrder = buildValidOrder()
			return startCycle(fromList = false, resetPasses = true)
		}
		// List mode → activate select, restart in key scan mode
		if (listMode) {
			val restart = startCycle(fromList = false, resetPasses = true)
			return restart.copy(activateSelect = true, cancelAutoRepeat = true)
		}
		// Active key scan: activate current key (or trigger list mode if Select)
		val currentKey = validOrder.getOrElse(indexInOrder) { 0 }
		if (config.selectTriggersList && currentKey == SELECT_KEY_INDEX) {
			listMode = true
			passesWithoutActivation = 0
			// stay on current until timer resumes
			return ScanResult(
				isActive = true,
				highlightedIdx = highlightedIdx,
				nextHighlightIdx = nextHighlightIdx,
				activateSelect = true,
				nextTickDelayMs = config.stepDelayMs + config.firstExtraDelayMs,
				listMode = true,
			)
		}
		return ScanResult(
			isActive = true,
			highlightedIdx = highlightedIdx,
			nextHighlightIdx = nextHighlightIdx,
			activateKey = currentKey,
			listMode = listMode,
			startAutoRepeatForKey = if (config.autoRepeatEnabled) currentKey else null,
		)
	}

	private fun handleSwitchUp(): ScanResult {
		if (!_isActive) {
			return ScanResult(
				isActive = false,
				highlightedIdx = null,
				nextHighlightIdx = null,
				cancelAutoRepeat = true,
			)
		}
		// Active: clear highlights and restart scan
		highlightedIdx = null
		nextHighlightIdx = null
		val restart = startCycle(fromList = false, resetPasses = true)
		return restart.copy(cancelAutoRepeat = true)
	}

	private fun handleTick(): ScanResult {
		if (!_isActive) {
			return ScanResult(isActive = false, highlightedIdx = null, nextHighlightIdx = null)
		}
		if (listMode) {
			// Selection list scan: generate Select activation each step
			return ScanResult(
				isActive = true,
				highlightedIdx = highlightedIdx,
				nextHighlightIdx = nextHighlightIdx,
				activateSelect = true,
				shouldBeepStep = config.beepEachStep,
				nextTickDelayMs = config.stepDelayMs,
				listMode = true,
			)
		}
		if (validOrder.isEmpty()) {
			validOrder = buildValidOrder()
			if (validOrder.isEmpty()) {
				return stopCycleSnapshot()
			}
		}
		indexInOrder = (indexInOrder + 1) % validOrder.size
		val nextDelay: Long
		if (indexInOrder == 0) {
			passesWithoutActivation += 1
			if (passesWithoutActivation >= config.repeatCount) {
				return stopCycleSnapshot()
			}
			nextDelay = config.stepDelayMs + config.firstExtraDelayMs
		} else {
			nextDelay = config.stepDelayMs
		}
		updateHighlightSlots()
		return ScanResult(
			isActive = true,
			highlightedIdx = highlightedIdx,
			nextHighlightIdx = nextHighlightIdx,
			shouldBeepStep = config.beepEachStep,
			nextTickDelayMs = nextDelay,
			listMode = false,
		)
	}

	private fun handleStop(): ScanResult {
		_isActive = false
		listMode = false
		highlightedIdx = null
		nextHighlightIdx = null
		return ScanResult(
			isActive = false,
			highlightedIdx = null,
			nextHighlightIdx = null,
			cancelAutoRepeat = true,
		)
	}

	private fun handleUiSnapshot(ui: JTUISnapshot): ScanResult {
		updateValidMask(ui)
		validOrder = buildValidOrder()
		// Only refresh highlight slots when scan is active — the original subsystem's
		// updateValidMask never touched highlightedIndex / nextHighlightIndex; doing so
		// here when inactive would paint a stale highlight on the first JTUI snapshot
		// after keyboard open (regression, fixed 2026-05-01).
		// The user's scan position (`indexInOrder`) is intentionally preserved when
		// active — see plan pre-flight Primary risk: snapshot mid-pass keeps the
		// current index, allowing the next tick to advance from that slot.
		if (_isActive) {
			updateHighlightSlots()
		}
		return ScanResult(
			isActive = _isActive,
			highlightedIdx = if (_isActive) highlightedIdx else null,
			nextHighlightIdx = if (_isActive) nextHighlightIdx else null,
			listMode = listMode,
		)
	}

	// ── Internal helpers ─────────────────────────────────────────────────────

	private fun startCycle(fromList: Boolean, resetPasses: Boolean): ScanResult {
		scanOrder = computeScanOrder()
		validOrder = buildValidOrder()
		indexInOrder = 0
		if (resetPasses) passesWithoutActivation = 0
		listMode = fromList
		_isActive = true
		updateHighlightSlots()
		return ScanResult(
			isActive = true,
			highlightedIdx = highlightedIdx,
			nextHighlightIdx = nextHighlightIdx,
			shouldBeepStep = config.beepEachStep,
			nextTickDelayMs = (config.stepDelayMs + config.firstExtraDelayMs).coerceAtLeast(0L),
			listMode = listMode,
		)
	}

	private fun stopCycleSnapshot(): ScanResult {
		_isActive = false
		listMode = false
		highlightedIdx = null
		nextHighlightIdx = null
		return ScanResult(
			isActive = false,
			highlightedIdx = null,
			nextHighlightIdx = null,
			cancelAutoRepeat = true,
		)
	}

	private fun updateHighlightSlots() {
		val currentKey = validOrder.getOrElse(indexInOrder) { 0 }
		val hasSkipped = config.skipInvalid && validKeyMask.any { !it }
		val nextKey = if (config.showNextKey && validOrder.size > 1) {
			val nextIdx = (indexInOrder + 1) % validOrder.size
			if (hasSkipped) validOrder[nextIdx] else null
		} else {
			null
		}
		highlightedIdx = currentKey
		nextHighlightIdx = nextKey
	}

	private fun updateValidMask(ui: JTUISnapshot) {
		val hasAmbigKeys = ui.ambiguousKeyMask.any { it }
		val hints = ui.nextLetterHints + ui.accentNextLetterHints

		// Skipping off or no ambiguous keys → everything stays valid.
		if (!config.skipInvalid || !hasAmbigKeys) {
			validKeyMask = BooleanArray(8) { true }
			return
		}

		// Otherwise: start with all false; always keep Select/Undo available.
		val mask = BooleanArray(8) { false }
		mask[SELECT_KEY_INDEX] = true
		mask[UNDO_KEY_INDEX] = true

		// If we have highlight hints and non-empty next-letter hints, mark only
		// matching keys as valid.
		if (ui.highlightNextLetters && hints.isNotEmpty()) {
			ui.keyLabels.forEachIndexed { idx, label ->
				if (idx == SELECT_KEY_INDEX || idx == UNDO_KEY_INDEX) return@forEachIndexed
				val grid = ui.keyLabelGrids.getOrNull(idx)
				val chars = if (grid != null && grid.any { it.isNotEmpty() }) {
					grid.joinToString("").toCharArray().toSet()
				} else {
					label.toCharArray().toSet()
				}
				if (chars.any { hints.contains(it.uppercaseChar()) || hints.contains(it.lowercaseChar()) }) {
					mask[idx] = true
				}
			}
		}

		validKeyMask = mask
	}
}
