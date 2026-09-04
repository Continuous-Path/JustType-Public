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
import java.io.File

/**
 * BUG #3 — words containing diacritic variants must be addable to and recallable from the word
 * DB, with each variant routed to its base letter's ambiguous key. Mirrors Bug scenario 2: add
 * "ûti" in ADD NEW WORD mode (fresh DB), then recall it by the optimized key sequence
 * lufcy / tr-'px / is.kw.
 */
@RunWith(RobolectricTestRunner::class)
class WldDiacriticWordTest {

	@get:Rule val tmpDir = TemporaryFolder()

	// Production optimized layout (lettersPerKeyOptimized in JTUI). keyNumber = list index.
	private val optimizedLetters = listOf("gemz", "tr-'p", "is.kw", "lufcy", "banq", "ojvhdx")
	private val keyTrpx = 1
	private val keyIskw = 2
	private val keyLufcy = 3

	private lateinit var wordDb: WordDb
	private lateinit var customDb: WordDb
	private lateinit var wld: WLD

	@Before fun setUp() {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		wordDb = WordDb.open(tmpDir.root, app.assets)
		customDb = WordDb.openStandalone(File(tmpDir.root, "custom.db"))
		val tree = loadDiacriticTree(app.assets)
		val variantsByBase = tree.entries.associate { (base, group) ->
			base to group.variants.flatMap { v ->
				buildList {
					v.char.firstOrNull()?.let { add(it) }
					v.upper?.firstOrNull()?.let { add(it) }
				}
			}
		}
		wld = WLD(optimizedLetters, wordDb, customDb, diacriticVariantsByBase = variantsByBase)
	}

	@After fun tearDown() {
		runCatching { wordDb.close() }
		runCatching { customDb.close() }
	}

	@Test fun `diacritic and high-codepoint chars are valid word-db chars`() {
		assertThat(wld.isWordDbChar('û')).isTrue() // U+00FB (in ASCII-extended array range)
		assertThat(wld.isWordDbChar('ā')).isTrue() // U+0101 (above 256 — high map)
		assertThat(wld.isWordDbChar('ụ')).isTrue() // U+1EE5 (Vietnamese, high map)
	}

	@Test fun `diacritic variant maps to its base letter key`() {
		// "ûti": û → u's key (lufcy=3), t → tr-'px=1, i → is.kw=2
		assertThat(wld.translateToKeysOrNull("ûti")).containsExactly(keyLufcy, keyTrpx, keyIskw).inOrder()
		// Base 'u' and its variant 'û' resolve to the same key.
		assertThat(wld.translateToKeysOrNull("u")).isEqualTo(wld.translateToKeysOrNull("û"))
	}

	@Test fun `period hyphen and apostrophe are valid word chars`() {
		// is.kw contains '.', tr-'px contains '-' and '\''.
		assertThat(wld.translateToKeysOrNull("s.")).containsExactly(keyIskw, keyIskw).inOrder()
		assertThat(wld.translateToKeysOrNull("r-")).containsExactly(keyTrpx, keyTrpx).inOrder()
	}

	@Test fun `custom word with a diacritic is added and recalled by its key sequence`() {
		wld.addCustomWord("ûti")
		val result = wld.getDisambiguationCandidates(
			keys = listOf(keyLufcy, keyTrpx, keyIskw),
			maxWordCompleteEntries = 20,
			minFreqClass = null,
			includeExcludedAtEnd = true,
			anyFreqMask = -1L, // all classes eligible
			minFreqMask = 0L,
			baseSearchDepth = 3,
			searchExpansionDepth = 3,
			maxExaminedNodes = 10_000,
		)
		assertThat(result.candidates.map { it.lowerWord }).contains("ûti")
		val entry = result.candidates.first { it.lowerWord == "ûti" }
		assertThat(entry.classMask and ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK).isNotEqualTo(0L)
	}
}
