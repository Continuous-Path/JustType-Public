package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Case-variant DB rows merging into one trie entry (the "i above I" failure,
 * 2026-08-10): a stale lowercase duplicate must not zero the real row's
 * usage, and case-insensitive stats resolution must reach the stored row.
 */
@RunWith(RobolectricTestRunner::class)
class WldDuplicateRowTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var wordDb: WordDb
	private val letters = listOf("ab", "cd", "ef", "gh", "ij", "kl")

	@Before fun setUp() {
		wordDb = WordDb.openStandalone(File(tmpDir.root, "dup.db"))
	}

	@After fun tearDown() {
		runCatching { wordDb.close() }
	}

	@Test fun `duplicate lowercase row never zeroes the trie entry's usage`() {
		val wld = WLD(letters, wordDb)
		val stats = wordDb.getOrCreateStats("I", defaultFreqClass = 1, defaultRawFreq = 40000)
		wld.updateOrAddWord(
			stats.wordID,
			"I",
			1,
			useCount = 4,
			lastUseTime = 100,
			classMask = 1L,
			posEncoded = 0,
			rawFreq = 40000,
		)
		// the orphan pattern: later row, same lowercase, no usage
		wld.updateOrAddWord(
			stats.wordID + 1,
			"i",
			7,
			useCount = 0,
			lastUseTime = 0,
			classMask = 1L,
			posEncoded = 0,
			rawFreq = 0,
		)

		val keys = wld.translateToKeysOrNull("i")!!
		val result = wld.getDisambiguationCandidates(
			keys, maxWordCompleteEntries = 100, minFreqClass = null,
			includeExcludedAtEnd = false, anyFreqMask = 1L, minFreqMask = 0L,
			baseSearchDepth = 8, searchExpansionDepth = 7, maxExaminedNodes = 5000,
		)
		val cand = result.candidates.single { it.lowerWord == "i" }
		assertThat(cand.useCount).isEqualTo(4)
		assertThat(cand.freqClass).isEqualTo(1)
	}

	@Test fun `caseStatsFor resolves case-insensitively to the stored row`() {
		val wld = WLD(letters, wordDb)
		val stats = wordDb.getOrCreateStats("I", defaultFreqClass = 1, defaultRawFreq = 40000)
		wld.updateOrAddWord(
			stats.wordID,
			"I",
			1,
			useCount = 0,
			lastUseTime = 0,
			classMask = 1L,
			posEncoded = 0,
			rawFreq = 40000,
		)
		val resolved = wld.caseStatsFor("i")
		assertThat(resolved).isNotNull()
		assertThat(resolved!!.first).isEqualTo("I")
		assertThat(resolved.second.wordID).isEqualTo(stats.wordID)
	}
}
