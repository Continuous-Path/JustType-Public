package org.continuouspath.justtype.logic

import kotlinx.serialization.encodeToString
import org.continuouspath.justtype.ClassMasks
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Regenerates src/test/resources/golden/key_sequences.json from the CURRENT engine.
 *
 * Kept @Ignore'd: golden fixtures lock in behavior, so regeneration is a deliberate act.
 * To regenerate: remove @Ignore, run `./jt test "*GoldenFixtureGenerator*"`, restore
 * @Ignore, and review the JSON diff like production code.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenFixtureGenerator {

	@get:Rule val tmpDir = TemporaryFolder()

	private val pJt = GoldenFixtures.GoldenParams(
		maxWordCompleteEntries = 100,
		minFreqClass = null,
		includeExcludedAtEnd = false,
		anyFreqMask = ClassMasks.CLASS_JUSTTYPE_MASK,
		minFreqMask = 0L,
		baseSearchDepth = 8,
		searchExpansionDepth = 7,
		maxExaminedNodes = 5000,
	)
	private val pJtCustom = pJt.copy(
		anyFreqMask = ClassMasks.CLASS_JUSTTYPE_MASK or ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK,
	)
	private val pMinFc4 = pJt.copy(
		anyFreqMask = 0L,
		minFreqMask = ClassMasks.CLASS_JUSTTYPE_MASK,
		minFreqClass = 4,
	)
	private val pExcl4 = pMinFc4.copy(includeExcludedAtEnd = true)
	private val pShallow = pJt.copy(baseSearchDepth = 3, searchExpansionDepth = 0)
	private val pTinyMen = pJt.copy(maxExaminedNodes = 2)
	private val pCustomOnly = pJt.copy(anyFreqMask = ClassMasks.CLASS_CUSTOM_WORDS_MASK)

	/** (layout, word whose key sequence to use, prefix length or null for full, params). */
	private data class Spec(
		val layout: String,
		val word: String,
		val prefixLen: Int?,
		val params: GoldenFixtures.GoldenParams,
	)

	private val specs = listOf(
		// Optimized layout
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "the", null, pJt),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "the", 1, pJt),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "the", 2, pJt),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "and", null, pJt),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "you", null, pJt),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "is", null, pJt),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "was", null, pJt),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "on", null, pJt),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "which", null, pJt),
		// Settings variations
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "the", null, pMinFc4),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "the", null, pExcl4),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "the", 1, pShallow),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "the", 1, pTinyMen),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "zed", null, pCustomOnly),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "zed", null, pJtCustom),
		Spec(GoldenFixtures.LAYOUT_OPTIMIZED, "zed", null, pJt), // custom words filtered out
		// Alphabetical layout
		Spec(GoldenFixtures.LAYOUT_ALPHABETICAL, "the", null, pJt),
		Spec(GoldenFixtures.LAYOUT_ALPHABETICAL, "the", 1, pJt),
		Spec(GoldenFixtures.LAYOUT_ALPHABETICAL, "and", null, pJt),
		Spec(GoldenFixtures.LAYOUT_ALPHABETICAL, "you", null, pJt),
		Spec(GoldenFixtures.LAYOUT_ALPHABETICAL, "is", null, pJt),
		Spec(GoldenFixtures.LAYOUT_ALPHABETICAL, "was", null, pJt),
		Spec(GoldenFixtures.LAYOUT_ALPHABETICAL, "which", null, pJt),
		Spec(GoldenFixtures.LAYOUT_ALPHABETICAL, "zed", null, pJtCustom),
	)

	@Ignore("Golden fixture generator — remove @Ignore and run manually to regenerate the JSON")
	@Test
	fun regenerate() {
		val fixtures = mutableListOf<GoldenFixtures.GoldenFixture>()
		for (layout in listOf(GoldenFixtures.LAYOUT_OPTIMIZED, GoldenFixtures.LAYOUT_ALPHABETICAL)) {
			WordDb.openStandalone(File(tmpDir.root, "golden-$layout.db")).use { db ->
				fixtures += fixturesFor(layout, db)
			}
		}
		val outFile = File(GoldenFixtures.resourceDirForWrite(), "key_sequences.json")
		outFile.parentFile?.mkdirs()
		outFile.writeText(GoldenFixtures.json.encodeToString(fixtures) + "\n")
		println("Wrote ${fixtures.size} golden fixtures to $outFile")
	}

	private fun fixturesFor(layout: String, db: WordDb): List<GoldenFixtures.GoldenFixture> {
		val wld = GoldenFixtures.buildWld(layout, db)
		return specs.filter { it.layout == layout }.map { spec ->
			val fullKeys = wld.translateToKeysOrNull(spec.word)
				?: error("Seed word '${spec.word}' not mappable on $layout")
			val keys = spec.prefixLen?.let { fullKeys.take(it) } ?: fullKeys
			val probe = GoldenFixtures.GoldenFixture(layout, keys, spec.params, emptyList(), "")
			val result = GoldenFixtures.run(wld, probe)
			probe.copy(
				expected = result.candidates.map { it.lowerWord },
				termination = result.termination,
			)
		}
	}
}
