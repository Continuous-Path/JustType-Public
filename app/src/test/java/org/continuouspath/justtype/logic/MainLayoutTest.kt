package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.continuouspath.justtype.hierarchy.diacriticBearingLetters
import org.continuouspath.justtype.hierarchy.loadDiacriticTree
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the v4.1 main-keyboard layout invariants:
 * - Every vowel and every diacritic-bearing consonant has at least one empty adjacent slot
 *   in its key's 9-cell label grid, so spell-mode diacritic display has room.
 * - Source-of-truth for "diacritic-bearing" is character_hierarchy.json (any base letter
 *   with variants); grids are read from the live JTUI snapshot (keyLabelGrids), not a copy.
 */
@RunWith(RobolectricTestRunner::class)
class MainLayoutTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private lateinit var bearers: Set<Char>

	@Before fun setUp() {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		bearers = diacriticBearingLetters(loadDiacriticTree(app.assets))
		h = TestJtui(tmpDir.root)
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private fun mainPageGrids(mode: LayoutMode): List<List<String>> {
		h.jtui.layoutMode = mode
		h.jtui.setCurrentPage("Main")
		h.jtui.forceUpdateUi()
		val grids = h.lastSnapshot!!.keyLabelGrids.filter { grid -> grid.any { it.isNotEmpty() } }
		// The six ambiguous letter keys must be present with real 9-cell grids.
		assertThat(grids.size).isAtLeast(6)
		// Every letter of the alphabet must appear as a single-char cell.
		// (Guards against an emptied letter grid hiding behind the injected
		// between-words hint cells, which are multi-char and don't count.)
		val letters = grids.flatten()
			.filter { it.length == 1 && it[0].isLetter() }
			.map { it[0].lowercaseChar() }
			.toSet()
		assertThat(letters).containsAtLeastElementsIn(('a'..'z').toList())
		return grids
	}

	@Test fun `optimized keys satisfy adjacent-empty-slot rule for diacritic-bearing letters`() {
		for (grid in mainPageGrids(LayoutMode.Optimized)) verifyAdjacency(grid)
	}

	@Test fun `alphabetic keys satisfy adjacent-empty-slot rule for diacritic-bearing letters`() {
		for (grid in mainPageGrids(LayoutMode.Alphabetical)) verifyAdjacency(grid)
	}

	private fun verifyAdjacency(grid: List<String>) {
		val bearerCells = grid.withIndex().filter { (_, cell) -> cell.firstOrNull()?.lowercaseChar() in bearers }
		for ((idx, cell) in bearerCells) {
			val hasEmptyNeighbor = ADJACENCY[idx].any { grid[it].isEmpty() }
			assertWithMessage("char '${cell.first()}' at cell $idx in grid $grid must have an empty adjacent slot")
				.that(hasEmptyNeighbor)
				.isTrue()
		}
	}

	companion object {
		// 8-neighbor adjacency in a 3x3 grid indexed row-major (0..8).
		private val ADJACENCY: List<Set<Int>> = listOf(
			setOf(1, 3, 4),
			setOf(0, 2, 3, 4, 5),
			setOf(1, 4, 5),
			setOf(0, 1, 4, 6, 7),
			setOf(0, 1, 2, 3, 5, 6, 7, 8),
			setOf(1, 2, 4, 7, 8),
			setOf(3, 4, 7),
			setOf(3, 4, 5, 6, 8),
			setOf(4, 5, 7),
		)
	}
}
