package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The key-history bar highlights, on each history key, the character the
 * currently selected/previewed word takes from that key (char i ↔ key i).
 */
@RunWith(RobolectricTestRunner::class)
class HistoryHighlightScenarioTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root)
	}

	@After fun tearDown() {
		h.tearDown()
	}

	@Test
	fun `pressing ambiguous keys previews the top candidate as the highlight word`() {
		h.jtui.buttonPressed(0)
		h.jtui.buttonPressed(5)

		val snap = h.lastSnapshot!!
		assertThat(snap.historyHighlightWord).isNotNull()
		assertThat(snap.historyHighlightWord).isEqualTo(snap.topCandidateOutput)
	}

	@Test
	fun `highlight word chars map onto the ambiguous key grids in typed order`() {
		h.jtui.buttonPressed(0)
		h.jtui.buttonPressed(5)

		val snap = h.lastSnapshot!!
		val word = snap.historyHighlightWord!!
		assertThat(snap.ambigKeyLabels).hasSize(2)
		snap.ambigKeyLabels.forEachIndexed { i, grid ->
			val ch = word.getOrNull(i) ?: return@forEachIndexed
			val upper = if (ch.isLetter()) ch.uppercaseChar() else ch
			assertWithMessage("char '$upper' of '$word' must sit on history key $i: $grid")
				.that(grid.any { it.contains(upper) })
				.isTrue()
		}
	}

	@Test
	fun `toggle off suppresses the highlight word but not the candidate preview`() {
		h.repo.putBoolean(org.continuouspath.justtype.Constants.KEY_KEY_HISTORY_HIGHLIGHT, false)
		h.jtui.buttonPressed(0)

		val snap = h.lastSnapshot!!
		assertThat(snap.historyHighlightWord).isNull()
		assertThat(snap.topCandidateOutput).isNotNull()
	}

	@Test
	fun `clearing the sequence clears the highlight word`() {
		h.jtui.buttonPressed(0)
		assertThat(h.lastSnapshot!!.historyHighlightWord).isNotNull()

		h.jtui.clearPendingKeySequence()
		h.jtui.forceUpdateUi()
		assertThat(h.lastSnapshot!!.historyHighlightWord).isNull()
	}
}
