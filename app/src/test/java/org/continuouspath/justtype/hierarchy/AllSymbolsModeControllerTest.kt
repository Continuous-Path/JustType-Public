package org.continuouspath.justtype.hierarchy

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AllSymbolsModeControllerTest {

	private lateinit var root: SymbolBranch

	@Before fun setUp() {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		root = loadSymbolTree(app.assets)
	}

	private fun controller(mode: InsertMode = InsertMode.SINGLE, entry: String = "Symbols3") = AllSymbolsModeController(root, mode, entry)

	private fun leafChars(views: List<SymbolSlotView>) = views.filterIsInstance<SymbolSlotView.Leaf>().map { it.char }

	private fun branchIndices(views: List<SymbolSlotView>) = views.filterIsInstance<SymbolSlotView.Branch>().map { it.absIndex }

	@Test fun `root shows the first six categories as branches`() {
		val c = controller()
		val views = c.currentSlots()
		assertThat(views).hasSize(6)
		assertThat(views.all { it is SymbolSlotView.Branch }).isTrue()
		assertThat(c.atRoot).isTrue()
		assertThat(c.hasMorePages).isTrue() // 12 categories => 2 pages
		assertThat((views[0] as SymbolSlotView.Branch).preview.first { it.isNotEmpty() }).isEqualTo(".")
	}

	@Test fun `MORE pages the root and wraps`() {
		val c = controller()
		assertThat(branchIndices(c.currentSlots())).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
		c.more()
		assertThat(branchIndices(c.currentSlots())).containsExactly(6, 7, 8, 9, 10, 11).inOrder()
		c.more() // wraps
		assertThat(branchIndices(c.currentSlots())).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
	}

	@Test fun `descend into Currency surfaces its leaves page by page`() {
		val c = controller()
		assertThat(c.descend(3)).isTrue() // index 3 = Currency
		assertThat(c.atRoot).isFalse()
		assertThat(leafChars(c.currentSlots())).containsExactly("$", "€", "£", "¥", "₹", "₩").inOrder()
		c.more()
		assertThat(leafChars(c.currentSlots())).containsExactly("¢", "₽", "฿", "₱", "₺", "₪").inOrder()
	}

	@Test fun `descend on a leaf index is a no-op`() {
		val c = controller()
		c.descend(3) // Currency: all children are leaves
		assertThat(c.descend(0)).isFalse()
	}

	@Test fun `ascend restores the parent page`() {
		val c = controller()
		c.more() // root page 2
		assertThat(c.descend(6)).isTrue() // Fractions
		assertThat(leafChars(c.currentSlots()).first()).isEqualTo("½")
		assertThat(c.ascend()).isTrue()
		assertThat(branchIndices(c.currentSlots())).containsExactly(6, 7, 8, 9, 10, 11).inOrder()
	}

	@Test fun `ascend at root returns false`() {
		assertThat(controller().ascend()).isFalse()
	}

	@Test fun `reset returns to root page one`() {
		val c = controller()
		c.more()
		c.descend(6)
		c.reset()
		assertThat(c.atRoot).isTrue()
		assertThat(branchIndices(c.currentSlots())).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
	}

	@Test fun `a category preview equals that category's first page`() {
		val c = controller()
		val preview = (c.currentSlots()[3] as SymbolSlotView.Branch).preview.filter { it.isNotEmpty() }
		c.descend(3)
		assertThat(preview).containsExactlyElementsIn(leafChars(c.currentSlots()))
	}

	@Test fun `every symbol is reachable and none dropped`() {
		val reachable = mutableSetOf<String>()
		for (cat in root.children.indices) {
			val c = controller()
			assertThat(c.descend(cat)).isTrue()
			// 6 pages covers the largest category (Currency = 4 pages)
			repeat(6) {
				reachable += leafChars(c.currentSlots())
				c.more()
			}
		}
		assertThat(reachable).containsExactlyElementsIn(inventory(root))
	}

	@Test fun `insert mode and entry page are retained`() {
		val c = controller(InsertMode.MULTI, "SymbolsMulti3")
		assertThat(c.insertMode).isEqualTo(InsertMode.MULTI)
		assertThat(c.entryPage).isEqualTo("SymbolsMulti3")
	}

	@Test fun `pageIndex pageCount and set name track navigation`() {
		val c = controller()
		assertThat(c.pageCount).isEqualTo(2) // 12 categories over 2 pages
		assertThat(c.pageIndex).isEqualTo(0)
		assertThat(c.currentSetName).isEqualTo("ALL SYMBOLS")
		assertThat(c.descend(3)).isTrue() // Currency
		assertThat(c.currentSetName).isEqualTo("Currency")
		assertThat(c.pageIndex).isEqualTo(0)
		assertThat(c.pageCount).isEqualTo(4) // 21 currency symbols over 4 pages
		c.more()
		assertThat(c.pageIndex).isEqualTo(1)
	}

	@Test fun `cased glyphs are surfaced verbatim`() {
		val c = controller()
		c.descend(7) // Superscripts & units
		val seen = mutableListOf<String>()
		repeat(3) {
			seen += leafChars(c.currentSlots())
			c.more()
		}
		assertThat(seen).containsAtLeast("µ", "Ω")
	}

	private fun inventory(node: SymbolNode): Set<String> = when (node) {
		is SymbolLeaf -> setOf(node.char)
		is SymbolBranch -> node.children.flatMap { inventory(it) }.toSet()
	}
}
