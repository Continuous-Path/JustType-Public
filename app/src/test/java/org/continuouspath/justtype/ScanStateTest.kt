package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.logic.JTUISnapshot
import org.junit.Test

class ScanStateTest {

	private fun makeState(config: ScanState.Config = ScanState.Config()): ScanState {
		val state = ScanState()
		state.applyConfig(config)
		return state
	}

	private fun emptySnapshot(
		ambiguousKeyMask: List<Boolean> = List(8) { false },
		highlightNextLetters: Boolean = false,
		nextLetterHints: Set<Char> = emptySet(),
		accentNextLetterHints: Set<Char> = emptySet(),
		keyLabels: List<String> = List(8) { "" },
		keyLabelGrids: List<List<String>> = List(8) { emptyList() },
	): JTUISnapshot = JTUISnapshot(
		outputBuffer = "",
		ambigBuffer = "",
		selectionListBuffers = emptyList(),
		keyHistoryBuffer = "",
		centerSpace = "",
		keyLabels = keyLabels,
		keyLabelGrids = keyLabelGrids,
		ambigKeyLabels = emptyList(),
		baseOutput = "",
		speechString = "",
		customWord = null,
		speakState = false,
		shiftState = false,
		isManualShift = false,
		isSpellingMode = false,
		topCandidateOutput = null,
		selectedCandidateOutput = null,
		topCandidateType = null,
		selectedCandidateType = null,
		highlightNextLetters = highlightNextLetters,
		nextLetterHints = nextLetterHints,
		accentNextLetterHints = accentNextLetterHints,
		ambiguousKeyMask = ambiguousKeyMask,
	)

	// ── Pure logic tests ─────────────────────────────────────────────────────

	@Test
	fun `computeScanOrder optimized layout returns expected order`() {
		val state = makeState(ScanState.Config(layoutOptimized = true))
		assertThat(state.computeScanOrder()).isEqualTo(listOf(6, 7, 0, 3, 2, 5, 4, 1))
	}

	@Test
	fun `computeScanOrder standard layout returns expected order`() {
		val state = makeState(ScanState.Config(layoutOptimized = false))
		assertThat(state.computeScanOrder()).isEqualTo(listOf(6, 0, 3, 5, 2, 4, 7, 1))
	}

	@Test
	fun `computeScanOrder uses the override when provided`() {
		val clockwise = listOf(0, 1, 2, 4, 7, 6, 5, 3)
		val state = makeState(ScanState.Config(scanOrderOverride = clockwise))
		assertThat(state.computeScanOrder()).isEqualTo(clockwise)
	}

	@Test
	fun `scanOrderOverride wins over layoutOptimized`() {
		val clockwise = listOf(0, 1, 2, 4, 7, 6, 5, 3)
		val state = makeState(ScanState.Config(layoutOptimized = true, scanOrderOverride = clockwise))
		assertThat(state.computeScanOrder()).isEqualTo(clockwise)
	}

	@Test
	fun `buildValidOrder returns full order when skipInvalid is off`() {
		val state = makeState(ScanState.Config(skipInvalid = false))
		state.process(ScanState.Event.SwitchDown) // initialises scanOrder via startCycle
		assertThat(state.buildValidOrder()).isEqualTo(state.computeScanOrder())
	}

	// ── Lifecycle ────────────────────────────────────────────────────────────

	@Test
	fun `initial state is inactive with no highlights`() {
		val state = makeState()
		assertThat(state.isActive).isFalse()
		assertThat(state.currentHighlight).isNull()
		assertThat(state.currentNextHighlight).isNull()
	}

	@Test
	fun `SwitchDown when idle starts cycle`() {
		val state = makeState()
		val result = state.process(ScanState.Event.SwitchDown)
		assertThat(result.isActive).isTrue()
		assertThat(result.highlightedIdx).isNotNull()
		assertThat(result.nextTickDelayMs).isNotNull()
		assertThat(state.isActive).isTrue()
	}

	@Test
	fun `SwitchDown when active activates current key`() {
		val state = makeState()
		state.process(ScanState.Event.SwitchDown) // start
		val result = state.process(ScanState.Event.SwitchDown) // activate
		assertThat(result.activateKey).isNotNull()
	}

	@Test
	fun `SwitchUp when idle is no-op cancellation signal`() {
		val state = makeState()
		val result = state.process(ScanState.Event.SwitchUp)
		assertThat(result.isActive).isFalse()
		assertThat(result.cancelAutoRepeat).isTrue()
	}

	@Test
	fun `SwitchUp when active restarts scan`() {
		val state = makeState()
		state.process(ScanState.Event.SwitchDown)
		val result = state.process(ScanState.Event.SwitchUp)
		assertThat(result.isActive).isTrue()
		assertThat(result.cancelAutoRepeat).isTrue()
	}

	@Test
	fun `Stop clears state`() {
		val state = makeState()
		state.process(ScanState.Event.SwitchDown)
		assertThat(state.isActive).isTrue()
		state.process(ScanState.Event.Stop)
		assertThat(state.isActive).isFalse()
		assertThat(state.currentHighlight).isNull()
	}

	@Test
	fun `reset clears state`() {
		val state = makeState()
		state.process(ScanState.Event.SwitchDown)
		state.reset()
		assertThat(state.isActive).isFalse()
		assertThat(state.currentHighlight).isNull()
	}

	// ── Tick advancement ─────────────────────────────────────────────────────

	@Test
	fun `Tick advances to next key`() {
		val state = makeState()
		state.process(ScanState.Event.SwitchDown)
		val firstHighlight = state.currentHighlight
		state.process(ScanState.Event.Tick)
		assertThat(state.currentHighlight).isNotEqualTo(firstHighlight)
	}

	@Test
	fun `Tick when inactive is no-op`() {
		val state = makeState()
		val result = state.process(ScanState.Event.Tick)
		assertThat(result.isActive).isFalse()
		assertThat(result.nextTickDelayMs).isNull()
	}

	@Test
	fun `scan stops after repeatCount passes without activation`() {
		val state = makeState(ScanState.Config(repeatCount = 1))
		state.process(ScanState.Event.SwitchDown)
		// Walk through the entire order so indexInOrder wraps to 0 (one pass)
		repeat(8) { state.process(ScanState.Event.Tick) }
		// Next tick after wrap should stop (passes >= 1)
		assertThat(state.isActive).isFalse()
	}

	// ── Auto-repeat ──────────────────────────────────────────────────────────

	@Test
	fun `SwitchDown active with autoRepeat returns startAutoRepeatForKey`() {
		val state = makeState(ScanState.Config(autoRepeatEnabled = true))
		state.process(ScanState.Event.SwitchDown)
		val result = state.process(ScanState.Event.SwitchDown)
		assertThat(result.startAutoRepeatForKey).isNotNull()
	}

	@Test
	fun `SwitchDown active without autoRepeat does not signal repeat`() {
		val state = makeState(ScanState.Config(autoRepeatEnabled = false))
		state.process(ScanState.Event.SwitchDown)
		val result = state.process(ScanState.Event.SwitchDown)
		assertThat(result.startAutoRepeatForKey).isNull()
	}

	// ── List-mode ────────────────────────────────────────────────────────────

	@Test
	fun `selectTriggersList enters list mode when current key is Select`() {
		val state = makeState(ScanState.Config(selectTriggersList = true))
		state.process(ScanState.Event.SwitchDown) // start; first highlight is Select (index 6)
		// Cycle order is [6, ...] for both layouts — first key IS Select
		val result = state.process(ScanState.Event.SwitchDown) // SwitchDown on Select
		assertThat(result.activateSelect).isTrue()
		assertThat(result.listMode).isTrue()
		assertThat(state.isListMode).isTrue()
	}

	@Test
	fun `Tick in list mode keeps activating Select`() {
		val state = makeState(ScanState.Config(selectTriggersList = true))
		state.process(ScanState.Event.SwitchDown)
		state.process(ScanState.Event.SwitchDown) // enters list mode
		val result = state.process(ScanState.Event.Tick)
		assertThat(result.activateSelect).isTrue()
		assertThat(result.listMode).isTrue()
	}

	@Test
	fun `SwitchDown in list mode activates select and exits list mode`() {
		val state = makeState(ScanState.Config(selectTriggersList = true))
		state.process(ScanState.Event.SwitchDown)
		state.process(ScanState.Event.SwitchDown) // enters list mode
		assertThat(state.isListMode).isTrue()
		val result = state.process(ScanState.Event.SwitchDown) // exits list mode
		assertThat(result.activateSelect).isTrue()
		assertThat(result.listMode).isFalse()
		assertThat(state.isListMode).isFalse()
	}

	// ── UI snapshot / valid mask ─────────────────────────────────────────────

	@Test
	fun `UiSnapshot with skipInvalid off keeps all keys valid`() {
		val state = makeState(ScanState.Config(skipInvalid = false))
		state.process(ScanState.Event.SwitchDown)
		val snapshot = emptySnapshot(
			ambiguousKeyMask = List(8) { true },
			highlightNextLetters = true,
			nextLetterHints = setOf('a'),
			keyLabels = listOf("X", "Y", "Z", "W", "Q", "R", "S", "T"),
		)
		state.process(ScanState.Event.UiSnapshot(snapshot))
		assertThat(state.buildValidOrder()).isEqualTo(state.computeScanOrder())
	}

	@Test
	fun `UiSnapshot with skipInvalid filters to keys with matching hints`() {
		val state = makeState(ScanState.Config(skipInvalid = true))
		state.process(ScanState.Event.SwitchDown)
		val snapshot = emptySnapshot(
			ambiguousKeyMask = List(8) { true },
			highlightNextLetters = true,
			nextLetterHints = setOf('A'),
			keyLabels = listOf("a", "b", "c", "d", "e", "f", "g", "h"),
		)
		state.process(ScanState.Event.UiSnapshot(snapshot))
		val order = state.buildValidOrder()
		// Should contain Select (6) and Undo (1) at minimum, plus key 0 ("a")
		assertThat(order).contains(0)
		assertThat(order).contains(ScanState.SELECT_KEY_INDEX)
		assertThat(order).contains(ScanState.UNDO_KEY_INDEX)
	}

	@Test
	fun `UiSnapshot when scan is inactive does not paint highlights`() {
		// Regression: on first JTUI snapshot after keyboard open in direct-selection
		// mode, scan must not produce a highlightedIdx (would paint a yellow key).
		val state = makeState()
		assertThat(state.isActive).isFalse()
		val snapshot = emptySnapshot(ambiguousKeyMask = List(8) { false })
		val result = state.process(ScanState.Event.UiSnapshot(snapshot))
		assertThat(result.isActive).isFalse()
		assertThat(result.highlightedIdx).isNull()
		assertThat(result.nextHighlightIdx).isNull()
		assertThat(state.currentHighlight).isNull()
	}

	@Test
	fun `UiSnapshot mid-cycle preserves indexInOrder position`() {
		// Pre-flight Primary risk: snapshot mid-pass keeps current scan position.
		val state = makeState(ScanState.Config(skipInvalid = false))
		state.process(ScanState.Event.SwitchDown)
		state.process(ScanState.Event.Tick) // index now 1
		val highlightBeforeSnapshot = state.currentHighlight
		val snapshot = emptySnapshot(ambiguousKeyMask = List(8) { false })
		state.process(ScanState.Event.UiSnapshot(snapshot))
		// After snapshot with skipInvalid off, validOrder is the same; indexInOrder stays
		// at 1; current highlight is still at order[1]
		assertThat(state.currentHighlight).isEqualTo(highlightBeforeSnapshot)
	}

	// ── nextHighlight preview ────────────────────────────────────────────────

	@Test
	fun `showNextKey off produces no nextHighlight`() {
		val state = makeState(ScanState.Config(showNextKey = false))
		state.process(ScanState.Event.SwitchDown)
		assertThat(state.currentNextHighlight).isNull()
	}

	@Test
	fun `showNextKey on without skipInvalid produces no nextHighlight`() {
		// nextHighlight is only set when there are skipped keys in the mask
		val state = makeState(ScanState.Config(showNextKey = true, skipInvalid = false))
		state.process(ScanState.Event.SwitchDown)
		assertThat(state.currentNextHighlight).isNull()
	}

	// ── Beep gating ──────────────────────────────────────────────────────────

	@Test
	fun `beepEachStep off suppresses beep signal`() {
		val state = makeState(ScanState.Config(beepEachStep = false))
		state.process(ScanState.Event.SwitchDown)
		val result = state.process(ScanState.Event.Tick)
		assertThat(result.shouldBeepStep).isFalse()
	}

	@Test
	fun `beepEachStep on emits beep signal`() {
		val state = makeState(ScanState.Config(beepEachStep = true))
		state.process(ScanState.Event.SwitchDown)
		val result = state.process(ScanState.Event.Tick)
		assertThat(result.shouldBeepStep).isTrue()
	}
}
