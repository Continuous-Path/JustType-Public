package org.continuouspath.justtype.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.continuouspath.justtype.ClassMasks
import java.io.File

/**
 * Shared support for the engine golden fixtures (Phase 0.2 of the KMP extraction).
 *
 * The fixture JSON (src/test/resources/golden/key_sequences.json) captures the exact
 * candidate lists the WLD engine produces for a curated set of key sequences over a
 * small, committed word list. [GoldenFixtureTest] replays it; [GoldenFixtureGenerator]
 * regenerates it. The word list is deliberately independent of the full EnglishDb asset
 * so fixtures stay stable and fast.
 */
object GoldenFixtures {

	const val RESOURCE_PATH = "golden/key_sequences.json"

	const val LAYOUT_OPTIMIZED = "optimized"
	const val LAYOUT_ALPHABETICAL = "alphabetical"

	// Mirrors the production layouts (lettersPerKeyOptimized / lettersPerKeyAlpha in JTUI).
	val optimizedLetters = listOf("gemz", "tr-'p", "is.kw", "lufcy", "banq", "ojvhdx")
	val alphaLetters = listOf("abcd'", "nopqr", "ef-gh", "stu.", "ijklm", "vwxyz")

	fun lettersFor(layout: String): List<String> = when (layout) {
		LAYOUT_OPTIMIZED -> optimizedLetters
		LAYOUT_ALPHABETICAL -> alphaLetters
		else -> error("Unknown layout '$layout'")
	}

	/**
	 * Deterministic seed corpus ("word;rawFreq" — same format as the asset word lists).
	 * Raw frequencies span all 14 freqClass tiers. Order matters: it fixes trie node
	 * insertion order and therefore candidate order within a BFS depth.
	 */
	val justTypeWords = listOf(
		"the;95000", "of;60000", "and;55000", "to;50000", "a;45000",
		"in;40000", "is;35000", "it;30000", "you;28000", "that;25000",
		"he;22000", "was;20000", "for;18000", "on;16000", "are;15000",
		"as;14000", "with;13000", "his;12000", "they;11000", "at;10000",
		"be;9000", "this;8500", "have;8000", "from;7000", "or;6000",
		"one;5000", "had;4500", "by;4000", "word;3500", "but;3000",
		"not;2600", "what;2200", "all;1800", "were;1500", "we;1300",
		"when;1100", "your;900", "can;700", "said;500", "there;300",
		"use;150", "each;75", "which;37", "she;17", "do;9", "how;8",
	)

	/** Words carrying CLASS_CUSTOM_WORDS_MASK, to exercise class-mask filtering. */
	val customWords = listOf("zed;1000", "qat;1000")

	fun buildWld(layout: String, wordDb: WordDb): WLD {
		val wld = WLD(lettersFor(layout), wordDb)
		wld.addWords(justTypeWords, avoid = emptySet(), classMask = ClassMasks.CLASS_JUSTTYPE_MASK)
		wld.addWords(customWords, avoid = emptySet(), classMask = ClassMasks.CLASS_CUSTOM_WORDS_MASK)
		return wld
	}

	@Serializable
	data class GoldenParams(
		val maxWordCompleteEntries: Int,
		val minFreqClass: Int?,
		val includeExcludedAtEnd: Boolean,
		val anyFreqMask: Long,
		val minFreqMask: Long,
		val baseSearchDepth: Int,
		val searchExpansionDepth: Int,
		val maxExaminedNodes: Int,
	)

	@Serializable
	data class GoldenFixture(
		val layout: String,
		val keys: List<Int>,
		val params: GoldenParams,
		val expected: List<String>,
		val termination: String,
	)

	val json = Json { prettyPrint = true }

	fun run(wld: WLD, fixture: GoldenFixture): WLD.DisambiguationResult = wld.getDisambiguationCandidates(
		keys = fixture.keys,
		maxWordCompleteEntries = fixture.params.maxWordCompleteEntries,
		minFreqClass = fixture.params.minFreqClass,
		includeExcludedAtEnd = fixture.params.includeExcludedAtEnd,
		anyFreqMask = fixture.params.anyFreqMask,
		minFreqMask = fixture.params.minFreqMask,
		baseSearchDepth = fixture.params.baseSearchDepth,
		searchExpansionDepth = fixture.params.searchExpansionDepth,
		maxExaminedNodes = fixture.params.maxExaminedNodes,
	)

	/** Resolves app/src/test/resources/golden/ for the generator (tests run with user.dir = module dir). */
	fun resourceDirForWrite(): File {
		var dir: File? = File(System.getProperty("user.dir") ?: ".")
		while (dir != null) {
			val direct = File(dir, "src/test/resources")
			if (direct.isDirectory) return File(direct, "golden")
			val viaApp = File(dir, "app/src/test/resources")
			if (viaApp.isDirectory) return File(viaApp, "golden")
			dir = dir.parentFile
		}
		error("Could not locate src/test/resources from ${System.getProperty("user.dir")}")
	}
}
