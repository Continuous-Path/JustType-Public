package org.continuouspath.justtype.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavHeadTrackingCallbacksTest {

	private fun callbacks(
		surface: () -> NavInputSurface? = { null },
		onExit: () -> Unit = {},
		onUnavailable: () -> Unit = {},
	) = NavHeadTrackingCallbacks(
		feedback = NavSubsystemFeedback(), // ToneGenerator init is null-safe in JVM tests
		inputSurface = surface,
		onExit = onExit,
		onUnavailable = onUnavailable,
	)

	@Test
	fun `nav does not pop out on exit`() {
		assertFalse(callbacks().popsOutOnExit)
	}

	@Test
	fun `exit gesture invokes the minimize lambda`() {
		var exited = 0
		val cb = callbacks(onExit = { exited++ })
		cb.onExitGesture()
		assertEquals(1, exited)
	}

	@Test
	fun `head tracking unavailable invokes the recovery lambda`() {
		var recovered = 0
		val cb = callbacks(onUnavailable = { recovered++ })
		cb.onHeadTrackingUnavailable()
		assertEquals(1, recovered)
	}

	@Test
	fun `buttonPressed aborts without firing when shouldAbort is true`() {
		// surface returns null anyway; this asserts the abort guard returns early.
		val cb = callbacks(surface = { error("surface should not be queried when aborting") })
		cb.buttonPressed(3) { true }
	}

	@Test
	fun `isInputViewShown follows the surface presence`() {
		assertFalse(callbacks(surface = { null }).isInputViewShown)
	}
}
