package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.logic.JTUI
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/**
 * Tests for [ExternalSwitchCallbacksImpl] guard logic: JTUI access is gated
 * on [IMEState.Ready], and Ready-state commands hop to the main thread.
 * Plain subsystem forwarding is not re-tested here.
 */
class ExternalSwitchCallbacksImplTest {

	private val jtui: JTUI = mock()
	private val deps = FakeDeps()
	private lateinit var subject: ExternalSwitchCallbacksImpl

	@Before
	fun setUp() {
		subject = ExternalSwitchCallbacksImpl(deps)
	}

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
	fun `isCapturingKey is false when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		assertThat(subject.isCapturingKey).isFalse()
	}

	@Test
	fun `isCapturingKey reads from jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		whenever(jtui.isCapturingKey).thenReturn(true)
		assertThat(subject.isCapturingKey).isTrue()

		whenever(jtui.isCapturingKey).thenReturn(false)
		assertThat(subject.isCapturingKey).isFalse()
	}

	@Test
	fun `handleRawKeyCapture no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.handleRawKeyCapture(42)
		verifyNoInteractions(jtui)
	}

	@Test
	fun `handleRawKeyCapture forwards to jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.handleRawKeyCapture(42)
		verify(jtui).handleRawKeyCapture(42)
	}

	@Test
	fun `buttonPressed no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.buttonPressed(3)
		assertThat(deps.launchedBlocks).isEmpty()
		verifyNoInteractions(jtui)
	}

	@Test
	fun `buttonPressed launches a main-thread block that calls jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.buttonPressed(3)
		assertThat(deps.launchedBlocks).hasSize(1)
		// Execute the captured block to verify it invokes jtui.buttonPressed
		deps.launchedBlocks.first().invoke()
		verify(jtui).buttonPressed(3)
	}

	private inner class FakeDeps : ExternalSwitchCallbacksImplDeps {
		private var _imeState: IMEState = IMEState.Loading
		val launchedBlocks = mutableListOf<() -> Unit>()

		fun setState(state: IMEState) {
			_imeState = state
		}

		override val imeState: IMEState get() = _imeState
		override val isInputViewShown: Boolean get() = false
		override val isSingleSwitchEnabled: Boolean get() = false
		override val isTwoSwitchEnabled: Boolean get() = false
		override val isJoystickMethodActive: Boolean get() = false
		override val isSwitchInputLoggingEnabled: Boolean get() = false
		override fun getJtuiOrNull(): JTUI? = (_imeState as? IMEState.Ready)?.jtui

		override fun launchOnMain(block: () -> Unit) {
			launchedBlocks.add(block)
		}

		override fun scanSwitchDown(keyCode: Int) = Unit
		override fun scanSwitchUp() = Unit
		override fun twoSwitchDown(role: String) = Unit
		override fun twoSwitchUp() = Unit
		override fun setTwoSwitchHeld(held: Boolean) = Unit
		override fun joystickInput(x: Float, y: Float) = Unit
		override fun getSwitchCodes(): SwitchCodeConfig = SwitchCodeConfig(scanCode = 0, redCode = 0, greenCode = 0)
		override fun debugLog(message: String) = Unit
	}
}
