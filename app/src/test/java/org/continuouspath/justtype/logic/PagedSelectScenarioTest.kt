package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hybrid paged word selection (PAGE-BASED Word Selection): the first N Select
 * presses step the list linearly; the next press shows the following 6 list rows
 * on the letter keys; a letter key picks its word; UnDo restores the prior state.
 */
@RunWith(RobolectricTestRunner::class)
class PagedSelectScenarioTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val repo get() = h.repo
	private val lastSnapshot get() = h.lastSnapshot

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { repo ->
			repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_PAGED)
			repo.putInt(Constants.KEY_PAGED_LISTED_WORDS, 2)
		}
	}

	@After fun tearDown() {
		h.tearDown()
	}

	/** Two ambig keystrokes on Key 0 ("gemz") — yields a multi-row list, no list-function rows. */
	private fun typeTwoKeys() {
		jtui.buttonPressed(0)
		jtui.buttonPressed(0)
	}

	private fun grids(): List<List<String>> = lastSnapshot!!.keyLabelGrids

	@Test fun `third Select pages 6 words onto the letter keys`() {
		typeTwoKeys()
		jtui.buttonPressed(6) // row 0
		jtui.buttonPressed(6) // row 1 (last listed word)
		assertThat(grids()[0][0]).isNotEmpty() // still the letter grid
		jtui.buttonPressed(6) // enters paged mode
		val g = grids()
		assertThat(g[0][4]).isNotEmpty() // word centered on Key 0
		// letter cells are cleared — only the center word remains
		assertThat(g[0].filterIndexed { i, s -> i != 4 && s.isNotEmpty() }).isEmpty()
	}

	@Test fun `letter key picks its page word - FINAL commit, sequence cleared`() {
		// Paged pick is FINAL (Cliff, 2026-08-08): the AK commits immediately
		// (no deferred selection) and the keyboard returns to word-start state.
		typeTwoKeys()
		repeat(3) { jtui.buttonPressed(6) }
		val pageWord = grids()[0][4]
		jtui.buttonPressed(0) // pick ordinal 0 = list row 2: commits
		assertThat(lastSnapshot!!.currentSelectionIndex).isNull()
		assertThat(lastSnapshot!!.ambigBuffer.trim()).isEmpty()
		assertThat(grids()[0][0]).isNotEmpty() // letter grid restored
		assertThat(pageWord).isNotEmpty()
	}

	@Test fun `Delete undoes one page step at a time`() {
		typeTwoKeys()
		repeat(3) { jtui.buttonPressed(6) } // rows 0,1 then page 1
		assertThat(grids()[0][4]).isNotEmpty()
		jtui.buttonPressed(1) // UnDo — back to linear state, row 1 selected
		assertThat(grids()[0][0]).isNotEmpty() // letter grid restored
		assertThat(lastSnapshot!!.currentSelectionIndex).isEqualTo(1)
	}

	@Test fun `zero listed words pages on the first Select`() {
		repo.putInt(Constants.KEY_PAGED_LISTED_WORDS, 0)
		typeTwoKeys()
		jtui.buttonPressed(6)
		assertThat(grids()[0][4]).isNotEmpty()
	}

	@Test fun `list mode is unaffected`() {
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		typeTwoKeys()
		repeat(3) { jtui.buttonPressed(6) }
		assertThat(lastSnapshot!!.currentSelectionIndex).isEqualTo(2)
		assertThat(grids()[0][0]).isNotEmpty() // never paged
	}
}
