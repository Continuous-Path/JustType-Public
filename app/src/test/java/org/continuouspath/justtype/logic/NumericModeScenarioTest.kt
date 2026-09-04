package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Numeric-Mode Punct sub-pages: the letter-symbols used as metric prefixes (`k`, `m`, `e`) must
 * DISPLAY in the case that will be emitted — i.e. follow the SHIFT state — not be force-uppercased.
 * `punctRow` lays chars out at keys [0,2,3,4,5,7] with SHIFT on key 6.
 */
@RunWith(RobolectricTestRunner::class)
class NumericModeScenarioTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val lastSnapshot get() = h.lastSnapshot

	@Before fun setUp() {
		h = TestJtui(tmpDir.root)
	}

	@After fun tearDown() {
		h.tearDown()
	}

	@Test fun `NumPunct4 e-key label follows SHIFT case`() {
		jtui.setCurrentPage("NumPunct4") // + - / * = e  → 'e' at key 7
		jtui.forceUpdateUi()
		val a = lastSnapshot?.keyLabels?.get(7)
		jtui.buttonPressed(6) // SHIFT (toggles shiftState, rebuilds, refreshes)
		val b = lastSnapshot?.keyLabels?.get(7)
		// Must toggle between lowercase and uppercase — never stuck on "E".
		assertThat(setOf(a, b)).containsExactly("e", "E")
	}

	@Test fun `NumPunct3 K and M labels follow SHIFT case`() {
		jtui.setCurrentPage("NumPunct3") // # % <SP> ° K M  → 'K' at key 5, 'M' at key 7
		jtui.forceUpdateUi()
		val k1 = lastSnapshot?.keyLabels?.get(5)
		val m1 = lastSnapshot?.keyLabels?.get(7)
		jtui.buttonPressed(6) // SHIFT
		val k2 = lastSnapshot?.keyLabels?.get(5)
		val m2 = lastSnapshot?.keyLabels?.get(7)
		assertThat(setOf(k1, k2)).containsExactly("k", "K")
		assertThat(setOf(m1, m2)).containsExactly("m", "M")
	}
}
