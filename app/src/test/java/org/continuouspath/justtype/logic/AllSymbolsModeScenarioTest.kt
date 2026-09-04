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
 * Integration tests for ALL SYMBOLS MODE (Phase 6). Drives [JTUI.buttonPressed] through the
 * hierarchical symbol picker and asserts navigation, single/multi insert, paging, return-routing,
 * and verbatim (no case-fold) output.
 *
 * Root page-1 categories land on keys [0,2,3,4,5,7] = Punctuation, Brackets, Quotes, Currency,
 * Math, Signs (SPATIAL_PAGE_KEYS order). So descending Currency = buttonPressed(4); within Currency
 * the leaves `$ € £ ¥ ₩ ¢` land on the same keys, so picking `€` = buttonPressed(2).
 */
@RunWith(RobolectricTestRunner::class)
class AllSymbolsModeScenarioTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val committed get() = h.committed
	private val lastSnapshot get() = h.lastSnapshot

	@Before fun setUp() {
		h = TestJtui(tmpDir.root)
	}

	@After fun tearDown() {
		h.tearDown()
	}

	/** Enter the picker from Symbols3 (single-insert), with Main as the eventual caller. */
	private fun enterSingleFromSymbols3() {
		jtui.setCurrentPage("Symbols3") // from Main → subModeCaller = "Main"
		jtui.buttonPressed(0) // ALL SYMBOLS MODE button
	}

	@Test fun `entering ALL SYMBOLS from Symbols3 shows the root picker`() {
		enterSingleFromSymbols3()
		assertThat(jtui.getCurrentPage()).isEqualTo("AllSymbols")
	}

	@Test fun `single-insert picks one symbol and returns to the caller`() {
		enterSingleFromSymbols3()
		jtui.buttonPressed(4) // descend Currency
		jtui.buttonPressed(2) // pick €
		assertThat(committed.toString()).isEqualTo("€")
		assertThat(jtui.getCurrentPage()).isEqualTo("Main")
	}

	@Test fun `single-insert returns to the LetterSymbol caller when entered from there`() {
		jtui.setCurrentPage("LetterSymbol")
		jtui.setCurrentPage("Symbols3") // subModeCaller = "LetterSymbol"
		jtui.buttonPressed(0)
		jtui.buttonPressed(4) // Currency
		jtui.buttonPressed(2) // €
		assertThat(committed.toString()).isEqualTo("€")
		assertThat(jtui.getCurrentPage()).isEqualTo("LetterSymbol")
	}

	@Test fun `multi-insert stays in the picker and returns to root after each pick`() {
		jtui.setCurrentPage("SymbolsMulti3")
		jtui.buttonPressed(0) // ALL SYMBOLS (multi)
		jtui.buttonPressed(4) // Currency
		jtui.buttonPressed(2) // €  → emits, back to root, stays in picker
		assertThat(committed.toString()).isEqualTo("€")
		assertThat(jtui.getCurrentPage()).isEqualTo("AllSymbols")
		jtui.buttonPressed(4) // Currency again (from root)
		jtui.buttonPressed(3) // £
		assertThat(committed.toString()).isEqualTo("€£")
		assertThat(jtui.getCurrentPage()).isEqualTo("AllSymbols")
	}

	@Test fun `MORE pages to the second set of categories`() {
		enterSingleFromSymbols3()
		jtui.buttonPressed(6) // MORE → root page 2 (Fractions, Superscripts, Arrows, Shapes, Marks, Science)
		jtui.buttonPressed(0) // descend Fractions
		jtui.buttonPressed(0) // pick ½
		assertThat(committed.toString()).isEqualTo("½")
		assertThat(jtui.getCurrentPage()).isEqualTo("Main")
	}

	@Test fun `UP at root exits to the entry page`() {
		jtui.setCurrentPage("SymbolsMulti3")
		jtui.buttonPressed(0)
		assertThat(jtui.getCurrentPage()).isEqualTo("AllSymbols")
		jtui.buttonPressed(1) // UP at root → exit
		assertThat(jtui.getCurrentPage()).isEqualTo("SymbolsMulti3")
	}

	@Test fun `UP mid-tree ascends one level then exits at root`() {
		enterSingleFromSymbols3()
		jtui.buttonPressed(4) // descend Currency
		jtui.buttonPressed(1) // UP → back to root (still in picker)
		assertThat(jtui.getCurrentPage()).isEqualTo("AllSymbols")
		jtui.buttonPressed(1) // UP at root → exit to Symbols3
		assertThat(jtui.getCurrentPage()).isEqualTo("Symbols3")
	}

	@Test fun `cased glyph inserts verbatim with no case folding`() {
		enterSingleFromSymbols3()
		jtui.buttonPressed(6) // MORE → page 2
		jtui.buttonPressed(2) // descend Superscripts & units
		jtui.buttonPressed(7) // pick µ (page-1 index 5 → key 7)
		assertThat(committed.toString()).isEqualTo("µ") // MICRO SIGN, not Greek mu U+03BC
	}

	@Test fun `µ key label renders verbatim, not uppercased`() {
		enterSingleFromSymbols3()
		jtui.buttonPressed(6) // PAGE 2 → root page 2
		jtui.buttonPressed(2) // descend Superscripts & units
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.keyLabels?.get(7)).isEqualTo("µ") // U+00B5, never "Μ" U+039C
	}

	@Test fun `key 6 shows the next page number and wraps with RETURN`() {
		enterSingleFromSymbols3()
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.keyLabels?.get(6)).isEqualTo("PAGE 2") // root page 1 of 2
		jtui.buttonPressed(6) // → last page
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.keyLabels?.get(6)).isEqualTo("RETURN\nTO PAGE\n1")
	}

	@Test fun `center square names the current set`() {
		enterSingleFromSymbols3()
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.centerSpace).isEqualTo("ALL SYMBOLS")
		jtui.buttonPressed(4) // descend Currency
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.centerSpace).isEqualTo("Currency")
	}

	@Test fun `long category name wraps in the center square`() {
		enterSingleFromSymbols3()
		jtui.buttonPressed(6) // → root page 2
		jtui.buttonPressed(2) // descend Superscripts & units
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.centerSpace).isEqualTo("Super-\nscripts\n& Units")
	}

	@Test fun `ALL SYMBOLS is reachable from SymbolsMulti1 key 0 in multi mode`() {
		jtui.setCurrentPage("SymbolsMulti1")
		jtui.buttonPressed(0) // ALL SYMBOLS MODE (was <SPACE>)
		assertThat(jtui.getCurrentPage()).isEqualTo("AllSymbols")
		jtui.buttonPressed(4) // Currency
		jtui.buttonPressed(2) // €  → multi: emits and returns to root
		assertThat(committed.toString()).isEqualTo("€")
		assertThat(jtui.getCurrentPage()).isEqualTo("AllSymbols")
	}
}
