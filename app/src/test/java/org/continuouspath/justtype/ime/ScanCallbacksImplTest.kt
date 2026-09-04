package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ScanCallbacksImpl] guard logic.
 *
 * The two flag-guarded callbacks (flashSwitchBar's inputViewInflated guard,
 * beepSwitchActivation's touchScreenSwitchBeepEnabled guard) are exactly
 * the kind of code that regresses silently when a future change inverts
 * a condition or removes the check. These tests document each guard
 * across both branches.
 */
class ScanCallbacksImplTest {

	private val deps = FakeDeps()
	private lateinit var subject: ScanCallbacksImpl

	@Before
	fun setUp() {
		subject = ScanCallbacksImpl(deps)
	}

	// ── flashSwitchBar guard ──────────────────────────────────────────────

	@Test
	fun `flashSwitchBar no-ops when input view not inflated`() {
		deps.inputViewInflated = false
		subject.flashSwitchBar()
		assertThat(deps.flashSwitchBarCalls).isEmpty()
	}

	@Test
	fun `flashSwitchBar fires green-true red-false when input view inflated`() {
		deps.inputViewInflated = true
		subject.flashSwitchBar()
		assertThat(deps.flashSwitchBarCalls).containsExactly(true to false)
	}

	// ── beepSwitchActivation guard ────────────────────────────────────────

	@Test
	fun `beepSwitchActivation no-ops when touch-screen-switch-beep disabled`() {
		deps.touchScreenSwitchBeepEnabled = false
		subject.beepSwitchActivation()
		assertThat(deps.beepCount).isEqualTo(0)
	}

	@Test
	fun `beepSwitchActivation fires when touch-screen-switch-beep enabled`() {
		deps.touchScreenSwitchBeepEnabled = true
		subject.beepSwitchActivation()
		assertThat(deps.beepCount).isEqualTo(1)
	}

	// ── autoLearnSwitchCode forwards unconditionally ──────────────────────

	@Test
	fun `autoLearnSwitchCode forwards keyCode to deps`() {
		subject.autoLearnSwitchCode(99)
		assertThat(deps.persistedSwitchCodes).containsExactly(99)
	}

	@Test
	fun `autoLearnSwitchCode is not gated by inputViewInflated or touch beep enabled`() {
		// Persistence is the auto-learn primitive — it must run regardless of
		// whether the input view is currently inflated or beeps are enabled.
		deps.inputViewInflated = false
		deps.touchScreenSwitchBeepEnabled = false
		subject.autoLearnSwitchCode(42)
		assertThat(deps.persistedSwitchCodes).containsExactly(42)
	}

	// ── Test fakes ────────────────────────────────────────────────────────

	private inner class FakeDeps : ScanCallbacksImplDeps {
		var inputViewInflated: Boolean = false
		var touchScreenSwitchBeepEnabled: Boolean = false

		val flashSwitchBarCalls = mutableListOf<Pair<Boolean, Boolean>>()
		var beepCount = 0
		val persistedSwitchCodes = mutableListOf<Int>()

		override val isInputViewInflated: Boolean get() = inputViewInflated
		override val isTouchScreenSwitchBeepEnabled: Boolean get() = touchScreenSwitchBeepEnabled

		override fun flashSwitchBar(green: Boolean, red: Boolean) {
			flashSwitchBarCalls.add(green to red)
		}

		override fun beepSwitchActivation() {
			beepCount++
		}

		override fun persistAutoLearnedSwitchCode(keyCode: Int) {
			persistedSwitchCodes.add(keyCode)
		}
	}
}
