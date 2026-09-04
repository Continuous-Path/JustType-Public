package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.hierarchy.loadDiacriticTree
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hybrid accent fallback (mixed-mapping layouts, Espanol v5): a word containing an explicit
 * variant letter is also reachable via its base-folded key sequence at a discounted rank
 * (d≈0.1 == +3 freqClass steps — docs/.plans/enhanced-analyzer/plan.md).
 */
@RunWith(RobolectricTestRunner::class)
class WldAccentFallbackTest {

	@get:Rule val tmpDir = TemporaryFolder()

	// Espanol v5 mixed-mapping letters (lettersPerKey letter portion). keyNumber = index.
	private val esLetters = listOf("pazd", "qñlcok", "igwés", "túyríó", "unmáh", "febvjx")
	private val keyQ = 1
	private val keyE = 5 // e on febvjx
	private val keyAcute = 2 // é on igwés
	private val keyU = 4

	private lateinit var wordDb: WordDb
	private lateinit var variantsByBase: Map<Char, List<Char>>

	@Before fun setUp() {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		wordDb = WordDb.open(tmpDir.root, app.assets)
		variantsByBase = loadDiacriticTree(app.assets).entries.associate { (base, group) ->
			base to group.variants.flatMap { v ->
				buildList {
					v.char.firstOrNull()?.let { add(it) }
					v.upper?.firstOrNull()?.let { add(it) }
				}
			}
		}
	}

	@After fun tearDown() {
		runCatching { wordDb.close() }
	}

	private fun makeWld(fallback: Boolean) = WLD(
		esLetters,
		wordDb,
		diacriticVariantsByBase = variantsByBase,
		accentFallbackEnabled = fallback,
	).also {
		it.addWords(listOf("que;100000", "qué;40000"), emptySet(), ClassMasks.CLASS_JUSTTYPE_MASK)
	}

	private fun candidatesFor(wld: WLD, keys: List<Int>) = wld.getDisambiguationCandidates(
		keys = keys,
		maxWordCompleteEntries = 20,
		minFreqClass = null,
		includeExcludedAtEnd = false,
		anyFreqMask = -1L,
		minFreqMask = 0L,
		baseSearchDepth = 3,
		searchExpansionDepth = 0,
		maxExaminedNodes = 10_000,
	).candidates

	@Test fun `base-folded sequence surfaces the accented word at a discounted rank`() {
		val wld = makeWld(fallback = true)
		val cands = candidatesFor(wld, listOf(keyQ, keyU, keyE))
		val que = cands.first { it.lowerWord == "que" }
		val acute = cands.first { it.lowerWord == "qué" }
		// que keeps its true class; qué carries the fallback discount (+3, x0.125 freq).
		assertThat(que.freqClass).isEqualTo(1)
		assertThat(acute.freqClass).isEqualTo(que.freqClass + 3)
	}

	@Test fun `exact accented sequence is unaffected by the fallback`() {
		val wld = makeWld(fallback = true)
		val cands = candidatesFor(wld, listOf(keyQ, keyU, keyAcute))
		val acute = cands.first { it.lowerWord == "qué" }
		assertThat(acute.freqClass).isEqualTo(1) // undiscounted on its own path
		assertThat(cands.none { it.lowerWord == "que" }).isTrue()
	}

	@Test fun `fallback disabled removes the folded path`() {
		val wld = makeWld(fallback = false)
		val cands = candidatesFor(wld, listOf(keyQ, keyU, keyE))
		assertThat(cands.none { it.lowerWord == "qué" }).isTrue()
		assertThat(cands.any { it.lowerWord == "que" }).isTrue()
	}

	@Test fun `prefix search lists a variant word only once`() {
		val wld = makeWld(fallback = true)
		// "qu" descends both the e child (fallback qué + exact que) and the é child (exact qué).
		val cands = candidatesFor(wld, listOf(keyQ, keyU))
		assertThat(cands.count { it.lowerWord == "qué" }).isEqualTo(1)
	}

	@Test fun `fallback continuation lights the base letter hint`() {
		val wld = makeWld(fallback = true)
		val letters = wld.getNextLettersForKeys(listOf(keyQ, keyU), -1L, 0L, null)
		// The exact path continues with é; the fallback path continues on the e KEY, so the
		// hint set carries the base form too.
		assertThat(letters).contains('e')
		assertThat(letters).contains('é')
	}

	@Test fun `english layout self-disables even when the flag is on`() {
		val en = WLD(
			listOf("gemz", "tr-'p", "is.kw", "lufcy", "banq", "ojvhdx"),
			wordDb,
			diacriticVariantsByBase = variantsByBase,
			accentFallbackEnabled = true,
		)
		en.addWords(listOf("cafe;5000", "café;2000"), emptySet(), ClassMasks.CLASS_JUSTTYPE_MASK)
		// é is NOT an explicit letter here: both words share one exact sequence already, and no
		// fallback duplicates appear.
		val keys = en.translateToKeysOrNull("cafe")!!
		val cands = candidatesFor(en, keys)
		assertThat(cands.count { it.lowerWord == "café" }).isEqualTo(1)
		assertThat(cands.first { it.lowerWord == "café" }.freqClass).isEqualTo(6) // its own class, undiscounted
	}
}
