package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TwoSwitchStateTest {

	private fun makeState(config: TwoSwitchState.Config = TwoSwitchState.Config()): TwoSwitchState {
		val state = TwoSwitchState()
		state.applyConfig(config)
		return state
	}

	// ── Pure logic ───────────────────────────────────────────────────────────

	@Test
	fun `splitCandidates divides sorted list into red and green`() {
		val state = makeState()
		state.setCandidatesForTest(listOf(0, 1, 2, 3, 4, 5, 6, 7))
		state.splitCandidates()
		assertThat(state.getRedForTest()).isEqualTo(listOf(0, 1, 2, 3))
		assertThat(state.getGreenForTest()).isEqualTo(listOf(4, 5, 6, 7))
	}

	@Test
	fun `splitCandidates with single element puts it in red`() {
		val state = makeState()
		state.setCandidatesForTest(listOf(5))
		state.splitCandidates()
		assertThat(state.getRedForTest()).isEqualTo(listOf(5))
		assertThat(state.getGreenForTest()).isEmpty()
	}

	@Test
	fun `computeSequenceForKey returns 3-step path`() {
		val state = makeState()
		// Key 0: in startGreen (0,3,5,6) → first press is Green
		assertThat(state.computeSequenceForKey(0)).hasSize(3)
		// All sequences should be exactly 3 steps
		(0..7).forEach { assertThat(state.computeSequenceForKey(it)).hasSize(3) }
	}

	@Test
	fun `all 8 key sequences are distinct`() {
		val state = makeState()
		val sequences = (0..7).map { state.computeSequenceForKey(it) }
		assertThat(sequences.toSet()).hasSize(8)
	}

	// ── Lifecycle ────────────────────────────────────────────────────────────

	@Test
	fun `initial state is inactive`() {
		val state = makeState()
		assertThat(state.isActive).isFalse()
		assertThat(state.currentStep).isEqualTo(0)
	}

	@Test
	fun `StartCycle activates and initializes red green`() {
		val state = makeState()
		val result = state.process(TwoSwitchState.Event.StartCycle())
		assertThat(result.isActive).isTrue()
		assertThat(result.step).isEqualTo(0)
		assertThat(result.red).isEqualTo(listOf(1, 2, 4, 7))
		assertThat(result.green).isEqualTo(listOf(0, 3, 5, 6))
		assertThat(result.applyColors).isTrue()
	}

	@Test
	fun `ClearColors resets state`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.ClearColors)
		assertThat(result.isActive).isFalse()
		assertThat(state.isActive).isFalse()
		assertThat(result.clearAll).isTrue()
	}

	@Test
	fun `reset clears state`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		state.reset()
		assertThat(state.isActive).isFalse()
		assertThat(state.currentStep).isEqualTo(0)
	}

	// ── External switch — 3-step binary search ───────────────────────────────

	@Test
	fun `step 0 Red narrows to keys 1 2 4 7`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = true))
		assertThat(result.step).isEqualTo(1)
	}

	@Test
	fun `step 0 Green narrows to keys 0 3 5 6`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Green Switch", switchHeld = true))
		assertThat(result.step).isEqualTo(1)
	}

	@Test
	fun `3 Red presses complete cycle and activate key 1`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		// Red, Red, Red — narrows to startRed=[1,2,4,7] → split [1,2]/[4,7] → Red [1,2] → split [1]/[2] → Red [1]
		val r1 = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		val r2 = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		val r3 = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		assertThat(r1.cycleCompleted).isFalse()
		assertThat(r2.cycleCompleted).isFalse()
		assertThat(r3.cycleCompleted).isTrue()
		assertThat(r3.activateKey).isEqualTo(1)
	}

	@Test
	fun `cycleCompleted with switchHeld and autoRepeat schedules StartAutoRepeat`() {
		val state = makeState(TwoSwitchState.Config(autoRepeatEnabled = true))
		state.process(TwoSwitchState.Event.StartCycle())
		state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = true))
		state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = true))
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = true))
		val hasAutoRepeat = result.timerActions.any { it is TwoSwitchState.TimerAction.StartAutoRepeat }
		assertThat(hasAutoRepeat).isTrue()
	}

	@Test
	fun `cycleCompleted without switchHeld does not schedule auto-repeat`() {
		val state = makeState(TwoSwitchState.Config(autoRepeatEnabled = true))
		state.process(TwoSwitchState.Event.StartCycle())
		state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		val hasAutoRepeat = result.timerActions.any { it is TwoSwitchState.TimerAction.StartAutoRepeat }
		assertThat(hasAutoRepeat).isFalse()
	}

	@Test
	fun `repeatActivation schedules StartActivationRepeat for in-progress press`() {
		val state = makeState(TwoSwitchState.Config(repeatActivationEnabled = true))
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		val hasRepeat = result.timerActions.any { it is TwoSwitchState.TimerAction.StartActivationRepeat }
		assertThat(hasRepeat).isTrue()
		assertThat(result.cycleCompleted).isFalse()
	}

	@Test
	fun `cycleCompleted does not schedule activation-repeat`() {
		val state = makeState(TwoSwitchState.Config(repeatActivationEnabled = true))
		state.process(TwoSwitchState.Event.StartCycle())
		state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		assertThat(result.cycleCompleted).isTrue()
		val hasRepeat = result.timerActions.any { it is TwoSwitchState.TimerAction.StartActivationRepeat }
		assertThat(hasRepeat).isFalse()
	}

	@Test
	fun `cycleCompleted cancels a pending activation-repeat so it cannot resume selecting`() {
		// Regression: a manually-completed cycle left the step-1 activation-repeat armed, which then
		// fired against the restarted cycle and auto-selected a key with no user input.
		val state = makeState(TwoSwitchState.Config(repeatActivationEnabled = true))
		state.process(TwoSwitchState.Event.StartCycle())
		state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		assertThat(result.cycleCompleted).isTrue()
		val cancels = result.timerActions.any { it is TwoSwitchState.TimerAction.CancelActivationRepeat }
		assertThat(cancels).isTrue()
	}

	// ── External switch up ───────────────────────────────────────────────────

	@Test
	fun `ExternalSwitchUp cancels both repeat timers`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.ExternalSwitchUp)
		assertThat(result.timerActions).contains(TwoSwitchState.TimerAction.CancelAutoRepeat)
		assertThat(result.timerActions).contains(TwoSwitchState.TimerAction.CancelActivationRepeat)
	}

	// ── Touch (DOWN/UP) ──────────────────────────────────────────────────────

	@Test
	fun `TouchDown step 0 advances to step 1`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.TouchDown("Red Switch"))
		assertThat(result.step).isEqualTo(1)
	}

	@Test
	fun `TouchDown at step 2 sets pendingHighlight`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		state.process(TwoSwitchState.Event.TouchDown("Red Switch"))
		state.process(TwoSwitchState.Event.TouchDown("Red Switch"))
		val result = state.process(TwoSwitchState.Event.TouchDown("Red Switch"))
		assertThat(result.pendingHighlight).isNotNull()
		assertThat(result.activateKey).isNull() // not yet activated
	}

	@Test
	fun `TouchUp activates pending step-2 target`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		state.process(TwoSwitchState.Event.TouchDown("Red Switch"))
		state.process(TwoSwitchState.Event.TouchDown("Red Switch"))
		state.process(TwoSwitchState.Event.TouchDown("Red Switch")) // sets pending
		val result = state.process(TwoSwitchState.Event.TouchUp)
		assertThat(result.activateKey).isNotNull()
		assertThat(result.activateKeySilent).isTrue()
		assertThat(result.cycleCompleted).isTrue()
	}

	@Test
	fun `TouchUp without pending target is no-op`() {
		val state = makeState()
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.TouchUp)
		assertThat(result.activateKey).isNull()
	}

	@Test
	fun `Timeout while a step-2 touch is pending drops it so release does not activate`() {
		// Regression: handleTimeout reset the cycle but left pendingTouchTarget, so releasing after a
		// mid-hold timeout typed a key from the already-cleared cycle.
		val state = makeState(TwoSwitchState.Config(timeoutMs = 2000L))
		state.process(TwoSwitchState.Event.StartCycle())
		state.process(TwoSwitchState.Event.TouchDown("Red Switch"))
		state.process(TwoSwitchState.Event.TouchDown("Red Switch"))
		state.process(TwoSwitchState.Event.TouchDown("Red Switch")) // sets pending at step 2
		state.process(TwoSwitchState.Event.Timeout) // fires while finger still down
		val result = state.process(TwoSwitchState.Event.TouchUp)
		assertThat(result.activateKey).isNull() // pending was cleared by the timeout
	}

	// ── Timeout ──────────────────────────────────────────────────────────────

	@Test
	fun `Timeout clears state`() {
		val state = makeState(TwoSwitchState.Config(timeoutMs = 1000L))
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.Timeout)
		assertThat(result.isActive).isFalse()
		assertThat(result.clearAll).isTrue()
		assertThat(state.isActive).isFalse()
	}

	// ── Beep gating ──────────────────────────────────────────────────────────

	@Test
	fun `beepActivation off suppresses switch beep`() {
		val state = makeState(TwoSwitchState.Config(beepActivation = false))
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		assertThat(result.shouldBeepSwitch).isFalse()
	}

	@Test
	fun `beepActivation on emits switch beep`() {
		val state = makeState(TwoSwitchState.Config(beepActivation = true))
		state.process(TwoSwitchState.Event.StartCycle())
		val result = state.process(TwoSwitchState.Event.ExternalSwitch("Red Switch", switchHeld = false))
		assertThat(result.shouldBeepSwitch).isTrue()
	}
}
