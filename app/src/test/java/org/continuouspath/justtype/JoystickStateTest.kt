package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoystickStateTest {

	// ── Defaults: exitZone unreachable, behavior unchanged ──────────────────

	@Test
	fun `default constructor with magnitude 1 stays in ACTIVATION (no EXIT)`() {
		val s = JoystickState(deadZone = 0.2f, activeZone = 0.5f)

		val r = s.process(0f, -1f) // straight UP, magnitude 1.0
		assertThat(r.zone).isEqualTo(JoystickState.Zone.ACTIVATION)
		assertThat(r.isInExitZone).isFalse()
	}

	@Test
	fun `default constructor activation-then-drop fires shouldActivate`() {
		val s = JoystickState(deadZone = 0.2f, activeZone = 0.5f)

		s.process(0f, -1f) // enter ACTIVATION (UP)
		val drop = s.process(0f, -0.3f) // drop to FEEDBACK on same axis

		assertThat(drop.zone).isEqualTo(JoystickState.Zone.FEEDBACK)
		assertThat(drop.shouldActivate).isTrue()
	}

	@Test
	fun `jitter just below activeZone stays in ACTIVATION and does not re-fire`() {
		// activeZone 0.5, hysteresis 0.05 → magnitude must fall below 0.45 to leave ACTIVATION.
		val s = JoystickState(deadZone = 0.2f, activeZone = 0.5f)
		s.process(0f, -1f) // enter ACTIVATION (UP)

		// A jitter to 0.47 (below activeZone but inside the band) must NOT drop out or fire.
		val jitter = s.process(0f, -0.47f)
		assertThat(jitter.zone).isEqualTo(JoystickState.Zone.ACTIVATION)
		assertThat(jitter.shouldActivate).isFalse()

		// Back up and a real drop below the re-arm floor fires exactly once.
		s.process(0f, -1f)
		val drop = s.process(0f, -0.4f)
		assertThat(drop.zone).isEqualTo(JoystickState.Zone.FEEDBACK)
		assertThat(drop.shouldActivate).isTrue()
	}

	// ── EXIT zone: enter / no stray activation on drop ──────────────────────

	@Test
	fun `crossing exitZone enters EXIT and sets isInExitZone`() {
		val s = JoystickState(deadZone = 0.2f, activeZone = 0.5f, exitZone = 0.95f)

		val r = s.process(0f, -1f)
		assertThat(r.zone).isEqualTo(JoystickState.Zone.EXIT)
		assertThat(r.isInExitZone).isTrue()
		assertThat(r.highlightState).isEqualTo(JoystickState.HighlightState.NONE)
		assertThat(r.highlightOctant).isNull()
	}

	@Test
	fun `EXIT then drop to DEAD does NOT fire shouldActivate`() {
		val s = JoystickState(deadZone = 0.2f, activeZone = 0.5f, exitZone = 0.95f)

		s.process(0f, -1f) // EXIT
		val drop = s.process(0f, 0f) // straight to DEAD

		assertThat(drop.zone).isEqualTo(JoystickState.Zone.DEAD)
		assertThat(drop.shouldActivate).isFalse()
	}

	@Test
	fun `EXIT then drop through FEEDBACK to DEAD does NOT fire shouldActivate`() {
		val s = JoystickState(deadZone = 0.2f, activeZone = 0.5f, exitZone = 0.95f)

		s.process(0f, -1f) // EXIT
		val feedback = s.process(0f, -0.3f) // FEEDBACK
		val dead = s.process(0f, 0f) // DEAD

		assertThat(feedback.zone).isEqualTo(JoystickState.Zone.FEEDBACK)
		assertThat(feedback.shouldActivate).isFalse()
		assertThat(dead.shouldActivate).isFalse()
	}

	@Test
	fun `EXIT then back to ACTIVATION on same octant fresh-locks for normal activation`() {
		val s = JoystickState(deadZone = 0.2f, activeZone = 0.5f, exitZone = 0.95f)

		s.process(0f, -1f) // EXIT (UP)
		val act = s.process(0f, -0.7f) // back into ACTIVATION on UP

		assertThat(act.zone).isEqualTo(JoystickState.Zone.ACTIVATION)
		assertThat(act.justEnteredActivation).isTrue()

		val drop = s.process(0f, -0.3f) // drop into FEEDBACK
		assertThat(drop.shouldActivate).isTrue()
	}

	// ── Constructor guard ───────────────────────────────────────────────────

	@Test(expected = IllegalArgumentException::class)
	fun `constructor rejects exitZone less than activeZone`() {
		JoystickState(deadZone = 0.2f, activeZone = 0.5f, exitZone = 0.4f)
	}

	@Test(expected = IllegalArgumentException::class)
	fun `constructor rejects exitZone equal to activeZone`() {
		JoystickState(deadZone = 0.2f, activeZone = 0.5f, exitZone = 0.5f)
	}

	@Test
	fun `constructor accepts MAX_VALUE sentinel (disabled exit zone)`() {
		JoystickState(deadZone = 0.2f, activeZone = 0.5f, exitZone = Float.MAX_VALUE)
		// no throw
	}
}
