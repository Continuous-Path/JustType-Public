package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.PosEncoding
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Characterization tests for the WLD disambiguation engine, locking in CURRENT behavior
 * ahead of the KMP extraction (Phase 0.1). These tests document behavior as-is — some of
 * it is surprising (see inline notes) but must not change during the extraction.
 */
@RunWith(RobolectricTestRunner::class)
class WldEngineInvariantTest {

	@get:Rule val tmpDir = TemporaryFolder()

	// Production optimized layout (lettersPerKeyOptimized in JTUI). keyNumber = list index.
	private val optimizedLetters = listOf("gemz", "tr-'p", "is.kw", "lufcy", "banq", "ojvhdx")
	private val keyGemz = 0
	private val keyLufcy = 3
	private val keyBanq = 4

	private lateinit var wordDb: WordDb

	@Before fun setUp() {
		wordDb = WordDb.openStandalone(File(tmpDir.root, "invariant.db"))
	}

	@After fun tearDown() {
		runCatching { wordDb.close() }
	}

	private fun newWld(
		diacritics: Map<Char, List<Char>> = emptyMap(),
	): WLD = WLD(optimizedLetters, wordDb, diacriticVariantsByBase = diacritics)

	private fun WLD.search(
		keys: List<Int>,
		maxWordCompleteEntries: Int = 100,
		minFreqClass: Int? = null,
		includeExcludedAtEnd: Boolean = false,
		anyFreqMask: Long = ClassMasks.CLASS_JUSTTYPE_MASK,
		minFreqMask: Long = 0L,
		baseSearchDepth: Int = 8,
		searchExpansionDepth: Int = 7,
		maxExaminedNodes: Int = 5000,
	): WLD.DisambiguationResult = getDisambiguationCandidates(
		keys = keys,
		maxWordCompleteEntries = maxWordCompleteEntries,
		minFreqClass = minFreqClass,
		includeExcludedAtEnd = includeExcludedAtEnd,
		anyFreqMask = anyFreqMask,
		minFreqMask = minFreqMask,
		baseSearchDepth = baseSearchDepth,
		searchExpansionDepth = searchExpansionDepth,
		maxExaminedNodes = maxExaminedNodes,
	)

	/** All letters map to key 0 (GEMZ), so the trie is a single chain z → zz → … → zzzzz. */
	private fun chainWld(): WLD {
		val wld = newWld()
		wld.addWords(
			listOf("z;100", "zz;100", "zzz;100", "zzzz;100", "zzzzz;100"),
			avoid = emptySet(),
			classMask = ClassMasks.CLASS_JUSTTYPE_MASK,
		)
		return wld
	}

	// ── 1. BFS termination / depth caps ─────────────────────────────────────

	@Test fun `maxDepth caps completion depth and terminates MAXD`() {
		val wld = chainWld()
		// baseSearchDepth=2, searchExpansionDepth=0 → maxDepth=2 beyond the consumed key.
		val result = wld.search(listOf(keyGemz), baseSearchDepth = 2, searchExpansionDepth = 0)
		assertThat(result.candidates.map { it.lowerWord }).containsExactly("z", "zz", "zzz").inOrder()
		assertThat(result.termination).isEqualTo("MAXD")
		assertThat(result.maxDepth).isEqualTo(2)
	}

	@Test fun `same word IS found once searchExpansionDepth is raised`() {
		val wld = chainWld()
		val result = wld.search(listOf(keyGemz), baseSearchDepth = 2, searchExpansionDepth = 2)
		assertThat(result.candidates.map { it.lowerWord })
			.containsExactly("z", "zz", "zzz", "zzzz", "zzzzz").inOrder()
		// NOTE (locked-in): termination stays "MAXD" even when the trie is exhausted
		// before the depth cap is reached — there is no distinct "exhausted" code.
		assertThat(result.termination).isEqualTo("MAXD")
	}

	@Test fun `zero depth returns only exact-length words`() {
		val wld = chainWld()
		val result = wld.search(listOf(keyGemz), baseSearchDepth = 0, searchExpansionDepth = 0)
		assertThat(result.candidates.map { it.lowerWord }).containsExactly("z")
		assertThat(result.termination).isEqualTo("MAXD")
	}

	@Test fun `maxWordCompleteEntries terminates with MQC`() {
		val wld = chainWld()
		// Words at the start node ("z") do NOT count toward maxWordCompleteEntries;
		// only BFS-collected completions do.
		val result = wld.search(listOf(keyGemz), maxWordCompleteEntries = 2)
		assertThat(result.candidates.map { it.lowerWord }).containsExactly("z", "zz", "zzz").inOrder()
		assertThat(result.termination).isEqualTo("MQC")
	}

	@Test fun `tiny maxExaminedNodes terminates early with MEN and omits deeper words`() {
		val wld = chainWld()
		val result = wld.search(listOf(keyGemz), maxExaminedNodes = 1)
		assertThat(result.candidates.map { it.lowerWord }).containsExactly("z", "zz").inOrder()
		assertThat(result.termination).isEqualTo("MEN")
		assertThat(result.examinedNodes).isEqualTo(1)
	}

	@Test fun `larger maxExaminedNodes finds the deeper words`() {
		val wld = chainWld()
		val result = wld.search(listOf(keyGemz), maxExaminedNodes = 5000)
		assertThat(result.candidates.map { it.lowerWord }).contains("zzzzz")
	}

	@Test fun `missing trie branch terminates BOT with no candidates`() {
		val wld = chainWld()
		val result = wld.search(listOf(keyBanq))
		assertThat(result.candidates).isEmpty()
		assertThat(result.termination).isEqualTo("BOT")
	}

	@Test fun `empty candidate result reports BOT even when the branch exists`() {
		val wld = chainWld()
		// Branch exists but every word is filtered out by the class mask.
		val result = wld.search(listOf(keyGemz), anyFreqMask = ClassMasks.CLASS_PHRASES_MASK)
		assertThat(result.candidates).isEmpty()
		assertThat(result.termination).isEqualTo("BOT")
	}

	// ── 2. freqClass bucketing (14 tiers) ───────────────────────────────────

	@Test fun `computeFreqClass boundaries map to the 14 tiers`() {
		val expected = mapOf(
			100_000 to 1, 40_000 to 1,
			39_999 to 2, 20_000 to 2,
			19_999 to 3, 10_000 to 3,
			9_999 to 4, 5_000 to 4,
			4_999 to 5, 2_500 to 5,
			2_499 to 6, 1_250 to 6,
			1_249 to 7, 625 to 7,
			624 to 8, 300 to 8,
			299 to 9, 150 to 9,
			149 to 10, 75 to 10,
			74 to 11, 37 to 11,
			36 to 12, 17 to 12,
			// NOTE (locked-in): the 13/14 boundary uses a strict "> 8" unlike the ">=" of
			// every other tier, so raw 9 → 13 but raw 8 → 14.
			16 to 13, 9 to 13,
			8 to 14, 1 to 14, 0 to 14,
		)
		expected.forEach { (raw, tier) ->
			assertThat(WordDb.computeFreqClass(raw)).isEqualTo(tier)
		}
	}

	@Test fun `addWords assigns freqClass from rawFreq through the engine path`() {
		// WLD keeps a private duplicate of computeFreqClass; lock its behavior via candidates.
		val wld = newWld()
		wld.addWords(
			listOf("z;40000", "zz;9", "zzz;8"),
			avoid = emptySet(),
			classMask = ClassMasks.CLASS_JUSTTYPE_MASK,
		)
		val byWord = wld.search(listOf(keyGemz)).candidates.associateBy { it.lowerWord }
		assertThat(byWord["z"]!!.freqClass).isEqualTo(1)
		assertThat(byWord["zz"]!!.freqClass).isEqualTo(13)
		assertThat(byWord["zzz"]!!.freqClass).isEqualTo(14)
	}

	@Test fun `minFreqClass filter excludes rarer words unless includeExcludedAtEnd flags them`() {
		val wld = newWld()
		wld.addWords(
			listOf("z;40000", "zz;300"), // freqClass 1 and 8
			avoid = emptySet(),
			classMask = ClassMasks.CLASS_JUSTTYPE_MASK,
		)
		val filtered = wld.search(
			listOf(keyGemz),
			anyFreqMask = 0L,
			minFreqMask = ClassMasks.CLASS_JUSTTYPE_MASK,
			minFreqClass = 4,
		)
		assertThat(filtered.candidates.map { it.lowerWord }).containsExactly("z")

		val withExcluded = wld.search(
			listOf(keyGemz),
			anyFreqMask = 0L,
			minFreqMask = ClassMasks.CLASS_JUSTTYPE_MASK,
			minFreqClass = 4,
			includeExcludedAtEnd = true,
		)
		val byWord = withExcluded.candidates.associateBy { it.lowerWord }
		assertThat(byWord.keys).containsExactly("z", "zz")
		assertThat(byWord["z"]!!.isLowFrequency).isFalse()
		assertThat(byWord["zz"]!!.isLowFrequency).isTrue()
	}

	// ── 3. Case-learning margin (incrementCaseCount) ────────────────────────

	private fun caseWld(word: String): WLD {
		val wld = newWld()
		// "LF,NN" → posEncoded with CaseType LOWER_FIRST, so the adaptive LF/TF logic applies.
		wld.addWords(listOf("$word;1000;LF,NN"), avoid = emptySet(), classMask = ClassMasks.CLASS_JUSTTYPE_MASK)
		return wld
	}

	private fun dbCaseType(word: String): Int = PosEncoding.caseType(wordDb.getPosEncoded(word))

	private fun counts(word: String): CaseCounts = wordDb.getCaseCountsByID(wordDb.getWordIDByWord(word)!!)!!

	@Test fun `fresh word needs margin usages to flip - equality with the rival is not enough`() {
		// The flip fires at newMyCount >= max(rivalCount, margin). A fresh lowercase word
		// (lower=1, title=0) must see `margin` title usages before LF→TF — a single usage
		// reaching equality (1 >= 1) must NOT flip (issue #21).
		val wld = caseWld("gem")
		assertThat(counts("gem")).isEqualTo(CaseCounts(lower = 1, title = 0, upper = 0, original = 0))
		assertThat(dbCaseType("gem")).isEqualTo(PosEncoding.CASE_LOWER_ONLY) // DB row starts unmutated (0)

		wld.incrementCaseCount("gem", WordCaseForm.TITLE, margin = 2)
		assertThat(counts("gem")).isEqualTo(CaseCounts(lower = 1, title = 1, upper = 0, original = 0))
		assertThat(dbCaseType("gem")).isEqualTo(PosEncoding.CASE_LOWER_ONLY) // no flip after one usage

		wld.incrementCaseCount("gem", WordCaseForm.TITLE, margin = 2)
		assertThat(counts("gem")).isEqualTo(CaseCounts(lower = 1, title = 2, upper = 0, original = 0))
		assertThat(dbCaseType("gem")).isEqualTo(PosEncoding.CASE_TITLE_FIRST) // flips at margin
	}

	@Test fun `margin=1 flips on the first non-preferred usage`() {
		val wld = caseWld("gem")
		wld.incrementCaseCount("gem", WordCaseForm.TITLE, margin = 1)
		assertThat(dbCaseType("gem")).isEqualTo(PosEncoding.CASE_TITLE_FIRST)
	}

	@Test fun `with a 2-point lead the flip needs exactly margin usages - boundary either side`() {
		val wld = caseWld("mez")
		// Build a lower lead of 2 (my=1 >= rival 0 keeps CaseType at LF — no mutation logged).
		wld.incrementCaseCount("mez", WordCaseForm.LOWER, margin = 2)
		assertThat(counts("mez")).isEqualTo(CaseCounts(lower = 2, title = 0, upper = 0, original = 0))
		assertThat(dbCaseType("mez")).isEqualTo(PosEncoding.CASE_LOWER_ONLY) // still unmutated

		// Title usage #1: count increments but 1 < 2 → NO flip yet.
		wld.incrementCaseCount("mez", WordCaseForm.TITLE, margin = 2)
		assertThat(counts("mez").title).isEqualTo(1)
		assertThat(dbCaseType("mez")).isEqualTo(PosEncoding.CASE_LOWER_ONLY)

		// Title usage #2: title reaches 2 == lower → flips to TITLE_FIRST.
		wld.incrementCaseCount("mez", WordCaseForm.TITLE, margin = 2)
		assertThat(counts("mez").title).isEqualTo(2)
		assertThat(dbCaseType("mez")).isEqualTo(PosEncoding.CASE_TITLE_FIRST)
	}

	@Test fun `count stops incrementing once it exceeds the rival by the margin`() {
		val wld = caseWld("mez")
		wld.incrementCaseCount("mez", WordCaseForm.LOWER, margin = 2) // lower=2
		repeat(2) { wld.incrementCaseCount("mez", WordCaseForm.TITLE, margin = 2) } // title=2, flip to TF

		// title=2 rival=2: 2 < 2+2 → increments to 3; then 3 < 4 → increments to 4.
		wld.incrementCaseCount("mez", WordCaseForm.TITLE, margin = 2)
		wld.incrementCaseCount("mez", WordCaseForm.TITLE, margin = 2)
		assertThat(counts("mez").title).isEqualTo(4)

		// title=4 rival=2: 4 >= 2+2 → capped, no further increment.
		wld.incrementCaseCount("mez", WordCaseForm.TITLE, margin = 2)
		assertThat(counts("mez").title).isEqualTo(4)
		assertThat(counts("mez").lower).isEqualTo(2)
	}

	// ── 4. Diacritic mapping ────────────────────────────────────────────────

	@Test fun `diacritic variants map to the base letter's key`() {
		val wld = newWld(diacritics = mapOf('u' to listOf('û', 'Û'), 'a' to listOf('á')))
		assertThat(wld.translateToKeysOrNull("u")).containsExactly(keyLufcy)
		assertThat(wld.translateToKeysOrNull("û")).containsExactly(keyLufcy)
		assertThat(wld.translateToKeysOrNull("Û")).containsExactly(keyLufcy)
		assertThat(wld.translateToKeysOrNull("á")).containsExactly(keyBanq)
		assertThat(wld.translateToKeysOrNull("ûz")).containsExactly(keyLufcy, keyGemz).inOrder()
	}

	@Test fun `unmappable characters return null and are not word-db chars`() {
		val wld = newWld(diacritics = mapOf('u' to listOf('û')))
		assertThat(wld.translateToKeysOrNull("ç")).isNull() // variant never registered
		assertThat(wld.translateToKeysOrNull("z!")).isNull() // one bad char poisons the whole string
		assertThat(wld.translateToKeysOrNull("é")).isNull()
		assertThat(wld.isWordDbChar('ç')).isFalse()
		assertThat(wld.isWordDbChar('û')).isTrue()
	}

	// ── 5. Phrase abbreviations ─────────────────────────────────────────────

	private fun phraseEntry(abbrev: String, phrase: String, uuid: String) = org.continuouspath.justtype.data.PhraseEntry(
		phraseUUID = uuid,
		abbreviation = abbrev,
		phrase = phrase,
		classMask = ClassMasks.CLASS_PHRASES_MASK,
	)

	@Test fun `re-adding an abbreviation with a new UUID surfaces it once, mapped to the newest phrase`() {
		// Regression: two phrases sharing an abbreviation but with different UUIDs both pass the
		// phraseIds guard, so the second used to append a SECOND exact-leaf entry with the same
		// wordID — surfacing the abbreviation twice in the selection list and making one phrase
		// unreachable. The exact-leaf must be replaced (last-wins), not appended.
		val wld = newWld()
		wld.addPhraseEntry(phraseEntry("brb", "be right back", "uuid-old"))
		wld.addPhraseEntry(phraseEntry("brb", "bathroom break", "uuid-new"))

		val keys = wld.translateToKeysOrNull("brb")!!
		val matches = wld.getPhraseMatches(
			keys = keys,
			anyFreqMask = ClassMasks.CLASS_PHRASES_MASK,
			minFreqMask = 0L,
			minFreqClass = null,
		)
		assertThat(matches).hasSize(1)
		assertThat(matches.first().phraseUUID).isEqualTo("uuid-new")
	}
}
