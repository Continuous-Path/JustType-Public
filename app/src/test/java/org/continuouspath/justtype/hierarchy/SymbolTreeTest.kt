package org.continuouspath.justtype.hierarchy

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SymbolTreeTest {

	private lateinit var root: SymbolBranch

	@Before fun setUp() {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		root = loadSymbolTree(app.assets)
	}

	@Test fun `loads the twelve flat categories in order`() {
		val labels = root.children.map { it.label }
		assertThat(labels).containsExactly(
			"Punctuation", "Brackets", "Quotes", "Currency", "Math", "Signs",
			"Fractions", "Superscripts & units", "Arrows", "Shapes", "Marks", "Science",
		).inOrder()
	}

	@Test fun `each category renders as a descending branch at uncommon`() {
		val slots = renderLevel(root, SymbolRaritySetting.UNCOMMON)
		assertThat(slots).hasSize(12)
		assertThat(slots.all { it is RenderedSlot.Branch }).isTrue()
		// First category (Punctuation) preview leads with the period anchor.
		assertThat((slots[0] as RenderedSlot.Branch).preview.first()).isEqualTo(".")
	}

	@Test fun `a category's children render as direct leaves`() {
		val currency = root.children.single { it.label == "Currency" } as SymbolBranch
		val slots = renderLevel(currency, SymbolRaritySetting.UNCOMMON)
		assertThat((slots[0] as RenderedSlot.Leaf).char).isEqualTo("$")
		assertThat((slots[1] as RenderedSlot.Leaf).char).isEqualTo("€")
		assertThat((slots[2] as RenderedSlot.Leaf).char).isEqualTo("£")
	}

	@Test fun `tier still prunes — primary-only keeps page-one leaves and hides the rest`() {
		val currency = root.children.single { it.label == "Currency" } as SymbolBranch
		val slots = renderLevel(currency, SymbolRaritySetting.PRIMARY_ONLY)
		val visible = slots.filterIsInstance<RenderedSlot.Leaf>().map { it.char }
		assertThat(visible).containsExactly("$", "€", "£", "¥", "₹", "₩")
		assertThat(slots.drop(6).all { it is RenderedSlot.Empty }).isTrue()
	}

	@Test fun `cased glyphs are preserved verbatim in the data`() {
		val chars = leaves(root).map { it.char }
		// These must survive as-is (ALL SYMBOLS MODE inserts them without case-folding).
		assertThat(chars).containsAtLeast("µ", "Ω", "π")
	}

	@Test fun `every leaf is precomposed NFC`() {
		leaves(root).forEach {
			assertThat(java.text.Normalizer.isNormalized(it.char, java.text.Normalizer.Form.NFC)).isTrue()
		}
	}

	private fun leaves(node: SymbolNode): List<SymbolLeaf> = when (node) {
		is SymbolLeaf -> listOf(node)
		is SymbolBranch -> node.children.flatMap { leaves(it) }
	}
}
