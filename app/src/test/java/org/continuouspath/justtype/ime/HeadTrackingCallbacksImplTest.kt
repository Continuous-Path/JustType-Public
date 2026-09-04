package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.logic.JTUI
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

/**
 * Tests for [HeadTrackingCallbacksImpl] guard logic.
 *
 * The impl's job is to gate JTUI access on readiness state and apply view-shown
 * + range guards inside [handleExternalButtonInput]. Tests cover the gates
 * without standing up the IME service. JTUI is mocked because it's the surface
 * under test; the IME-host side-effecting calls (beep, highlight) are recorded
 * via FakeDeps counters.
 */
class HeadTrackingCallbacksImplTest {

	private val jtui: JTUI = mock()
	private val deps = FakeDeps()
	private lateinit var subject: HeadTrackingCallbacksImpl

	@Before
	fun setUp() {
		subject = HeadTrackingCallbacksImpl(deps)
	}

	// ── Readiness properties ──────────────────────────────────────────────

	@Test
	fun `isJtuiInitialized is true only when imeState is Ready`() {
		deps.setState(IMEState.Loading)
		assertThat(subject.isJtuiInitialized).isFalse()

		deps.setState(IMEState.Constructed(jtui))
		assertThat(subject.isJtuiInitialized).isFalse()

		deps.setState(IMEState.Ready(jtui))
		assertThat(subject.isJtuiInitialized).isTrue()
	}

	@Test
	fun `isInputViewShown delegates to deps`() {
		deps.viewShown = false
		assertThat(subject.isInputViewShown).isFalse()
		deps.viewShown = true
		assertThat(subject.isInputViewShown).isTrue()
	}

	@Test
	fun `isBeepEnabled delegates to deps`() {
		deps.beepEnabled = false
		assertThat(subject.isBeepEnabled).isFalse()
		deps.beepEnabled = true
		assertThat(subject.isBeepEnabled).isTrue()
	}

	// ── playActivationBeep ────────────────────────────────────────────────

	@Test
	fun `playActivationBeep forwards to deps`() {
		subject.playActivationBeep()
		assertThat(deps.beepCount).isEqualTo(1)
	}

	// ── JTUI write guards ─────────────────────────────────────────────────

	@Test
	fun `buttonPressed no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.buttonPressed(3) { false }
		verifyNoInteractions(jtui)
	}

	@Test
	fun `buttonPressed forwards to jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		val abort = { false }
		subject.buttonPressed(3, abort)
		verify(jtui).buttonPressed(3, abort)
	}

	@Test
	fun `setWldGeneration no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.setWldGeneration(7L)
		verifyNoInteractions(jtui)
	}

	@Test
	fun `setWldGeneration writes to jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.setWldGeneration(7L)
		verify(jtui).wldGeneration = 7L
	}

	@Test
	fun `forceUpdateUi no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.forceUpdateUi()
		verifyNoInteractions(jtui)
	}

	@Test
	fun `forceUpdateUi forwards to jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.forceUpdateUi()
		verify(jtui).forceUpdateUi(false)
	}

	// ── handleExternalButtonInput multi-guard ─────────────────────────────

	@Test
	fun `handleExternalButtonInput no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		deps.viewShown = true
		subject.handleExternalButtonInput(3)
		verifyNoInteractions(jtui)
		assertThat(deps.highlightedIndex).isNull()
	}

	@Test
	fun `handleExternalButtonInput no-ops when imeState is Constructed`() {
		deps.setState(IMEState.Constructed(jtui))
		deps.viewShown = true
		subject.handleExternalButtonInput(3)
		verifyNoInteractions(jtui)
		assertThat(deps.highlightedIndex).isNull()
	}

	@Test
	fun `handleExternalButtonInput rejects buttonIndex below range`() {
		deps.setState(IMEState.Ready(jtui))
		deps.viewShown = true
		subject.handleExternalButtonInput(-1)
		verifyNoInteractions(jtui)
		assertThat(deps.highlightedIndex).isNull()
	}

	@Test
	fun `handleExternalButtonInput rejects buttonIndex above range`() {
		deps.setState(IMEState.Ready(jtui))
		deps.viewShown = true
		subject.handleExternalButtonInput(8)
		verifyNoInteractions(jtui)
		assertThat(deps.highlightedIndex).isNull()
	}

	@Test
	fun `handleExternalButtonInput forwards to jtui when Ready and in range`() {
		deps.setState(IMEState.Ready(jtui))
		deps.viewShown = true
		subject.handleExternalButtonInput(3)
		verify(jtui).buttonPressed(3)
		assertThat(deps.highlightedIndex).isEqualTo(3)
	}

	@Test
	fun `handleExternalButtonInput forwards to jtui but skips highlight when view hidden`() {
		deps.setState(IMEState.Ready(jtui))
		deps.viewShown = false
		subject.handleExternalButtonInput(3)
		verify(jtui).buttonPressed(3)
		assertThat(deps.highlightedIndex).isNull()
	}

	@Test
	fun `handleExternalButtonInput accepts boundary values 0 and 7`() {
		deps.setState(IMEState.Ready(jtui))
		deps.viewShown = true

		subject.handleExternalButtonInput(0)
		verify(jtui).buttonPressed(0)
		assertThat(deps.highlightedIndex).isEqualTo(0)

		subject.handleExternalButtonInput(7)
		verify(jtui).buttonPressed(7)
		assertThat(deps.highlightedIndex).isEqualTo(7)
	}

	// ── debugLog ──────────────────────────────────────────────────────────

	@Test
	fun `debugLog forwards to deps`() {
		subject.debugLog("hello")
		assertThat(deps.debugMessages).containsExactly("hello")
	}

	@Test
	fun `onHeadTrackingUnavailable forwards to deps`() {
		subject.onHeadTrackingUnavailable()
		assertThat(deps.onHeadTrackingUnavailableCount).isEqualTo(1)
	}

	@Test
	fun `onHeadTrackingRecovered forwards to deps`() {
		subject.onHeadTrackingRecovered()
		assertThat(deps.onHeadTrackingRecoveredCount).isEqualTo(1)
	}

	// ── Test fakes ────────────────────────────────────────────────────────

	private inner class FakeDeps : HeadTrackingCallbacksImplDeps {
		private var _imeState: IMEState = IMEState.Loading
		var viewShown: Boolean = false
		var beepEnabled: Boolean = false
		var beepCount: Int = 0
		var highlightedIndex: Int? = null
		val debugMessages = mutableListOf<String>()

		fun setState(state: IMEState) {
			_imeState = state
		}

		override val imeState: IMEState get() = _imeState
		override val isInputViewShown: Boolean get() = viewShown
		override val isBeepEnabled: Boolean get() = beepEnabled
		override val isCorrectionBeepEnabled: Boolean get() = beepEnabled
		override val isCorrectionFlashRedEnabled: Boolean get() = beepEnabled
		override fun getJtuiOrNull(): JTUI? = (_imeState as? IMEState.Ready)?.jtui
		override fun playActivationBeep() {
			beepCount++
		}
		override fun playCorrectTone() = Unit
		override fun playCancelTone() = Unit
		override fun setHighlight(buttonIndex: Int) {
			highlightedIndex = buttonIndex
		}
		override fun suppressPullInForExit() = Unit
		override fun onKeyboardReEntry() = Unit
		var onHeadTrackingUnavailableCount: Int = 0
		override fun onHeadTrackingUnavailable() {
			onHeadTrackingUnavailableCount++
		}
		var onHeadTrackingRecoveredCount: Int = 0
		override fun onHeadTrackingRecovered() {
			onHeadTrackingRecoveredCount++
		}
		override fun debugLog(message: String) {
			debugMessages.add(message)
		}
	}
}
