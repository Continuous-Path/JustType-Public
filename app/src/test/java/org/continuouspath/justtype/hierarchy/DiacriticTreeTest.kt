package org.continuouspath.justtype.hierarchy

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiacriticTreeTest {

	private lateinit var tree: Map<Char, DiacriticGroup>

	@Before fun setUp() {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		tree = loadDiacriticTree(app.assets)
	}

	@Test fun `every Latin vowel has a diacritic group`() {
		assertThat(tree.keys).containsAtLeast('a', 'e', 'i', 'o', 'u', 'y')
	}

	@Test fun `null allowed set returns every variant, ordered by tier then rank`() {
		val a = tree.getValue('a')
		val v = variantsForCharSet(a, null)
		assertThat(v).hasSize(a.variants.size)
		val order = v.map { it.tier.ordinal to it.rankInTier }
		assertThat(order).isEqualTo(order.sortedWith(compareBy({ it.first }, { it.second })))
	}

	@Test fun `empty allowed set returns no variants`() {
		assertThat(variantsForCharSet(tree.getValue('a'), emptySet())).isEmpty()
	}

	@Test fun `a constrained allowed set returns only the matching variants`() {
		val v = variantsForCharSet(tree.getValue('e'), setOf('é', 'è'))
		assertThat(v.map { it.char }).containsExactly("é", "è")
	}

	@Test fun `every variant glyph is a non-ASCII letter matched by its first char`() {
		// Guards the first-char match in variantsForCharSet against the real character_hierarchy.json.
		tree.values.flatMap { it.variants }.forEach { variant ->
			val scanned = mutableSetOf<Char>()
			DiacriticDerivation.scanWord(variant.char, scanned)
			assertThat(scanned).contains(variant.char.first().lowercaseChar())
		}
	}

	@Test fun `diacriticBearingLetters covers a c e i n o s u y`() {
		val bearers = diacriticBearingLetters(tree)
		assertThat(bearers).containsAtLeast('a', 'c', 'e', 'i', 'n', 'o', 's', 'u', 'y')
	}
}
