package org.continuouspath.justtype.input

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.junit.Test

class HatSwitchEdgeDetectorTest {

	private val up = KeyEvent.KEYCODE_DPAD_UP
	private val down = KeyEvent.KEYCODE_DPAD_DOWN

	/** Records emitted events as "down:<code>" / "up:<code>" in order. */
	private class Recorder {
		val events = mutableListOf<String>()
		val onDown: (Int) -> Unit = { events += "down:$it" }
		val onUp: (Int) -> Unit = { events += "up:$it" }
	}

	@Test
	fun `press then release of a bound direction fires one down and one up`() {
		val det = HatSwitchEdgeDetector()
		val r = Recorder()
		val actuates = { code: Int -> code == down }

		det.onHatCode(down, actuates, r.onDown, r.onUp) // press
		det.onHatCode(SWITCH_CODE_UNDEFINED, actuates, r.onDown, r.onUp) // release (centered)

		assertThat(r.events).containsExactly("down:$down", "up:$down").inOrder()
	}

	@Test
	fun `held direction does not repeat the down`() {
		val det = HatSwitchEdgeDetector()
		val r = Recorder()
		val actuates = { code: Int -> code == down }

		det.onHatCode(down, actuates, r.onDown, r.onUp)
		det.onHatCode(down, actuates, r.onDown, r.onUp) // still held — HAT keeps streaming
		det.onHatCode(down, actuates, r.onDown, r.onUp)

		assertThat(r.events).containsExactly("down:$down")
	}

	@Test
	fun `unbound direction fires nothing and leaves no phantom held`() {
		val det = HatSwitchEdgeDetector()
		val r = Recorder()
		val actuates = { code: Int -> code == down } // up is NOT bound

		det.onHatCode(up, actuates, r.onDown, r.onUp) // press unbound up
		det.onHatCode(SWITCH_CODE_UNDEFINED, actuates, r.onDown, r.onUp) // release

		assertThat(r.events).isEmpty() // no stray up (this is the bug the detector fixes)
	}

	@Test
	fun `switching directions without centering releases the old and presses the new`() {
		val det = HatSwitchEdgeDetector()
		val r = Recorder()
		val actuates = { _: Int -> true } // both bound (two-switch red/green)

		det.onHatCode(up, actuates, r.onDown, r.onUp) // red
		det.onHatCode(down, actuates, r.onDown, r.onUp) // green, no center between

		assertThat(r.events).containsExactly("down:$up", "up:$up", "down:$down").inOrder()
	}

	@Test
	fun `clear drops held state without firing an up`() {
		val det = HatSwitchEdgeDetector()
		val r = Recorder()
		val actuates = { _: Int -> true }

		det.onHatCode(down, actuates, r.onDown, r.onUp) // held
		det.clear()
		det.onHatCode(SWITCH_CODE_UNDEFINED, actuates, r.onDown, r.onUp) // would-be release

		assertThat(r.events).containsExactly("down:$down") // clear ate the held, no up
	}

	@Test
	fun `clearIfHeld of the held code eats the release and a continued hold re-downs`() {
		val det = HatSwitchEdgeDetector()
		val r = Recorder()
		val actuates = { _: Int -> true }

		det.onHatCode(down, actuates, r.onDown, r.onUp) // held
		det.clearIfHeld(down) // stuck-timeout auto-release
		det.onHatCode(down, actuates, r.onDown, r.onUp) // still physically held — new press

		assertThat(r.events).containsExactly("down:$down", "down:$down").inOrder()
	}

	@Test
	fun `clearIfHeld of a different code leaves the held state alone`() {
		val det = HatSwitchEdgeDetector()
		val r = Recorder()
		val actuates = { _: Int -> true }

		det.onHatCode(down, actuates, r.onDown, r.onUp) // held
		det.clearIfHeld(up) // unrelated code timed out
		det.onHatCode(SWITCH_CODE_UNDEFINED, actuates, r.onDown, r.onUp) // real release

		assertThat(r.events).containsExactly("down:$down", "up:$down").inOrder()
	}
}
