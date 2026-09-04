package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * English NGB end-to-end on the built-in English DB: word-based traits
 * (empty unit inventory), context = the previous word, zero-K follower
 * window, prediction block above letter-exact, and the confidence signal
 * live in English (docs/.plans/ngram/plan.md, EN base round).
 */
@RunWith(RobolectricTestRunner::class)
class EnglishNgbTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { r ->
			r.putBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, true)
			r.putInt(Constants.KEY_NGB_CONFIDENCE_THRESHOLD, 20)
			// Fixture wants the raw 20% threshold verbatim: pin adaptive theta off.
			r.putBoolean(org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_NGB_CONF_THETA_ADAPTIVE, false)
		}
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "English")
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	private fun listedWords(): List<String> = h.lastSnapshot!!.selectionListBuffers
		.joinToString("\n")
		.split("\n")
		.map { it.trim() }
		.filter { it.isNotEmpty() }

	private fun commitThenStart(word: String, next: String, nextKeyCount: Int = 2): List<Int> {
		val keys = jtui.wordKeySequence(word)!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		val nextKeys = jtui.wordKeySequence(next)!!
		nextKeys.take(nextKeyCount).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		return nextKeys
	}

	@Test fun `english derives word-based traits`() {
		assertThat(jtui.ngbTraitsForTest()).isEqualTo(LanguageTraits.WORD_BASED)
	}

	@Test fun `english DB ships NGB context data`() {
		// The build must have populated ngb_ctx (EnglishNgb.txt input) — the
		// whole EN feature set keys off this.
		assertThat(jtui.ngbActiveForTest()).isTrue()
	}

	@Test fun `committing a word opens the zero-K follower window`() {
		val keys = jtui.wordKeySequence("of")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		val next = jtui.wordKeySequence("the")!!
		next.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		// "the" after "of" is the strongest bigram in the table: it must sit
		// in the prediction block above the letter-exact entries.
		val idx = listedWords().indexOfFirst { it.equals("the", ignoreCase = true) }
		assertThat(idx).isAtLeast(0)
		assertThat(idx).isLessThan(NgbEngine.BLOCK_SIZE)
	}

	@Test fun `confidence signal is live in english`() {
		commitThenStart("of", "the")
		assertThat(jtui.ngbConfLastObservationForTest).isNotNull()
		assertThat(h.confidenceSignals).isGreaterThan(0)
	}

	@Test fun `sortmetric tags render by tier`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putBoolean(
			org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_SHOW_SORT_METRIC,
			true,
		)
		repo.putInt(
			org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_SORT_METRIC_VERBOSITY,
			1,
		)
		// Tag rendering asserted against the list buffer; pin list mode
		// (paged became the shipped default, 2026-08-10).
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		commitThenStart("of", "the")
		val buf = h.lastSnapshot!!.selectionListBuffers.joinToString("\n")
		assertThat(buf).contains("[N") // n-gram block entry tagged
		assertThat(buf).contains("[F") // a fully typed candidate tagged
		// Tier 2 appends the score.
		repo.putInt(
			org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_SORT_METRIC_VERBOSITY,
			2,
		)
		jtui.forceUpdateUi()
		val buf2 = h.lastSnapshot!!.selectionListBuffers.joinToString("\n")
		assertThat(buf2).containsMatch("\\[N[ng]*\\] \\d")
	}

	@Test fun `accepting a prediction records usage and recency`() {
		val before = jtui.wordUseCountForTest("the")
		// Type "of", commit it, type 2 keys of "the": the block holds "the".
		commitThenStart("of", "the")
		// Select the block top and commit it with the next word's first key
		// (AK-after-SEL) — the N-type acceptance path.
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("best")!!.take(1).forEach { jtui.buttonPressed(pagePos[it]) }
		assertThat(jtui.wordUseCountForTest("the")).isGreaterThan(before)
	}

	@Test fun `prediction for a case-fixed word displays its stored case and creates no orphan row`() {
		// but -> i is the top EnglishNgb target; the DB stores the word as "I"
		// (original-case, avoid-file case-fix). The prediction pool must render
		// "I" — resolved case-insensitively through the REAL row — and the
		// display path must not fabricate a lowercase "i" row. Regression for
		// Cliff's "i above I" failure (2026-08-10): the orphan's lower-dominant
		// counts made the prediction render lowercase, clobbered the trie
		// entry's useCount at rebuild, and split block/trie dedup so the word
		// appeared twice with the correct case only mid-list.
		assertThat(jtui.wordRowExistsForTest("i")).isFalse()
		commitThenStart("but", "it")
		val pool = jtui.ngbPoolDisplaysForTest()
		assertThat(pool).contains("I")
		assertThat(pool).doesNotContain("i")
		assertThat(jtui.wordRowExistsForTest("i")).isFalse()
	}

	@Test fun `fully-typed familiar word heads the block over higher-eff completions`() {
		// would -> {say 287k, it 255k, i 192k, suggest 191k} on the ISKW key:
		// raw eff order put "say" first even though "I" is FULLY TYPED at one
		// key with a 1M unigram count (Cliff's "would I" incident,
		// 2026-08-10). The unified block ordering — band(max(rawFreq,
		// M_c x eff)) + keys-still-untyped + global use/recency — must put
		// "I" at the head of the block.
		commitThenStart("would", "it")
		val iKeys = jtui.wordKeySequence("i")!!
		val block = jtui.ngbBlockDisplaysForTest(iKeys)
		assertThat(block).isNotEmpty()
		assertThat(block.first()).isEqualTo("I")
	}

	@Test fun `fully-typed deep-rank word beats weak surviving predictions`() {
		// also -> say sits at row rank 84 (eff 56k), beyond POOL_SIZE=60: the
		// fetch cut starved its dual-source row, so the block showed only the
		// weak deep survivors include/includes/included (raw 322/258/173,
		// 4-6 keys away) above fully-typed say (Cliff's "also say" incident).
		// Deep singles are now retained and join the alive set once FULLY
		// TYPED; under the unified metric say heads the list.
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		commitThenStart("also", "say", nextKeyCount = 3)
		val words = listedWords()
		assertThat(words.first()).isEqualTo("say")
	}

	@Test fun `region demotion never touches region-neutral words - the only-below-vayu incident`() {
		// english_region=en-US once demoted fully-typed "only" (band 2, raw
		// 37k) below vayu (raw 8): EnglishRegionTags carried Wiktionary
		// SENSE-label skews (only;GB, half;GB, abroad;US...) instead of
		// spelling variants, and the interleave partition made the two-level
		// demotion list-wide. With the pair-filtered tag list, neutral words
		// stay undemoted: fully-typed "only" heads its list under en-US.
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		repo.putString(Constants.KEY_ENGLISH_REGION, org.continuouspath.justtype.EnglishRegion.US)
		val keys = jtui.wordKeySequence("only")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(listedWords().first().lowercase()).isEqualTo("only")
	}

	@Test fun `sentence boundaries derive the BOS context - known starts only`() {
		// sls.md BOS row: sentence-final punctuation, a trailing newline, and
		// an empty field are KNOWN sentence starts -> the reserved "\n" ctx.
		// Comma-class breaks and unknown state stay fail-soft null.
		jtui.ngbReconstructContext("We got home. ")
		assertThat(jtui.ngbContextForTest()).isEqualTo(JTUI.NGB_BOS_CTX to true)
		jtui.ngbReconstructContext("really?!  ")
		assertThat(jtui.ngbContextForTest().first).isEqualTo(JTUI.NGB_BOS_CTX)
		jtui.ngbReconstructContext("")
		assertThat(jtui.ngbContextForTest().first).isEqualTo(JTUI.NGB_BOS_CTX)
		jtui.ngbReconstructContext("hello\n")
		assertThat(jtui.ngbContextForTest().first).isEqualTo(JTUI.NGB_BOS_CTX)
		jtui.ngbReconstructContext("we got, ")
		assertThat(jtui.ngbContextForTest().first).isNull()
		jtui.ngbReconstructContext(null)
		assertThat(jtui.ngbContextForTest().first).isNull()
		jtui.ngbReconstructContext("we got ")
		assertThat(jtui.ngbContextForTest().first).isEqualTo("got")
		// The EN table ships a "\n" row (BOS re-stream, 2026-08-12): known
		// sentence starts serve the opener distribution — "the" leads it.
		jtui.ngbReconstructContext("We got home. ")
		val pool = jtui.ngbPoolDisplaysForTest()
		assertThat(pool).isNotEmpty()
		assertThat(pool.map { it.lowercase() }).contains("the")
	}

	@Test fun `learning records word bigrams through the empty unit inventory`() {
		commitThenStart("we", "should", nextKeyCount = jtui.wordKeySequence("should")!!.size)
		jtui.buttonPressed(selectPos)
		// Commit "should" via the next word's first keys (AK-after-SEL).
		jtui.wordKeySequence("go")!!.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		val rows = jtui.ngbUserRowsForTest("we")
		assertThat(rows.map { it.first }).contains("should")
	}
}
