package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Tests for [TwoSwitchCallbacksImpl] guard logic.
 *
 * Two of four callbacks are flag-guarded: flashSwitchBar's inputViewInflated
 * gate and beepTouchSwitchActivation's touchScreenSwitchBeepEnabled gate.
 * The non-touch beepSwitchActivation forwards unconditionally — the
 * touch-vs-external split is what makes both beep paths worth testing
 * independently.
 */
class TwoSwitchCallbacksImplTest {

	private val deps = FakeDeps()
	private lateinit var subject: TwoSwitchCallbacksImpl

	@Before
	fun setUp() {
		subject = TwoSwitchCallbacksImpl(deps)
	}

	// ── flashSwitchBar guard ──────────────────────────────────────────────

	@Test
	fun `flashSwitchBar no-ops when input view not inflated`() {
		deps.inputViewInflated = false
		subject.flashSwitchBar(flashGreen = true, flashRed = false)
		assertThat(deps.flashSwitchBarCalls).isEmpty()
	}

	@Test
	fun `flashSwitchBar forwards both flag args when input view inflated`() {
		deps.inputViewInflated = true
		subject.flashSwitchBar(flashGreen = true, flashRed = false)
		assertThat(deps.flashSwitchBarCalls).containsExactly(true to false)
	}

	@Test
	fun `flashSwitchBar accepts independent green and red flags`() {
		deps.inputViewInflated = true
		subject.flashSwitchBar(flashGreen = false, flashRed = true)
		subject.flashSwitchBar(flashGreen = true, flashRed = true)
		assertThat(deps.flashSwitchBarCalls).containsExactly(false to true, true to true).inOrder()
	}

	// ── beepSwitchActivation: external switch path (always fires) ────────

	@Test
	fun `beepSwitchActivation fires unconditionally regardless of touch flag`() {
		deps.touchScreenSwitchBeepEnabled = false
		subject.beepSwitchActivation()
		assertThat(deps.beepCount).isEqualTo(1)
	}

	@Test
	fun `beepSwitchActivation fires unconditionally regardless of view-inflated flag`() {
		deps.inputViewInflated = false
		subject.beepSwitchActivation()
		assertThat(deps.beepCount).isEqualTo(1)
	}

	// ── beepTouchSwitchActivation: touch path (gated) ─────────────────────

	@Test
	fun `beepTouchSwitchActivation no-ops when touch beep disabled`() {
		deps.touchScreenSwitchBeepEnabled = false
		subject.beepTouchSwitchActivation()
		assertThat(deps.beepCount).isEqualTo(0)
	}

	@Test
	fun `beepTouchSwitchActivation fires when touch beep enabled`() {
		deps.touchScreenSwitchBeepEnabled = true
		subject.beepTouchSwitchActivation()
		assertThat(deps.beepCount).isEqualTo(1)
	}

	// ── stepFeedback forwards unconditionally ────────────────────────────

	@Test
	fun `stepFeedback forwards to deps`() {
		subject.stepFeedback(beep = true)
		assertThat(deps.stepFeedbackCount).isEqualTo(1)
		assertThat(deps.lastStepBeep).isTrue()
	}

	@Test
	fun `finalActivationFeedback forwards to deps`() {
		subject.finalActivationFeedback(4)
		assertThat(deps.finalActivationIndex).isEqualTo(4)
	}

	// ── debugLog forwards unconditionally ────────────────────────────────

	@Test
	fun `debugLog forwards message`() {
		subject.debugLog("x")
		assertThat(deps.debugMessages).containsExactly("x")
	}

	// ── Test fakes ────────────────────────────────────────────────────────

	private inner class FakeDeps : TwoSwitchCallbacksImplDeps {
		var inputViewInflated: Boolean = false
		var touchScreenSwitchBeepEnabled: Boolean = false

		val flashSwitchBarCalls = mutableListOf<Pair<Boolean, Boolean>>()
		var beepCount = 0
		var stepFeedbackCount = 0
		var lastStepBeep = false
		var finalActivationIndex = -1
		val debugMessages = mutableListOf<String>()

		override val isInputViewInflated: Boolean get() = inputViewInflated
		override val isTouchScreenSwitchBeepEnabled: Boolean get() = touchScreenSwitchBeepEnabled

		override fun flashSwitchBar(flashGreen: Boolean, flashRed: Boolean) {
			flashSwitchBarCalls.add(flashGreen to flashRed)
		}

		override fun beepSwitchActivation() {
			beepCount++
		}

		override fun beepSwitchKeyCombined() = Unit

		override fun stepFeedback(beep: Boolean) {
			stepFeedbackCount++
			lastStepBeep = beep
		}

		override fun finalActivationFeedback(index: Int) {
			finalActivationIndex = index
		}

		override fun debugLog(message: String) {
			debugMessages.add(message)
		}
	}
}
