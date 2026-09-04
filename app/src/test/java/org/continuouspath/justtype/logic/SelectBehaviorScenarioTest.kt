package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.activity.DeveloperSettingsActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Adaptive select-behavior substrate + Dev force-enable ladder
 * (docs/.plans/sls.md "Adaptive select-behavior mechanisms"): every Select
 * engagement records one EWMA-decayed episode (signal state, demoted-FTS
 * presence, outcome kind + depth) in the custom DB; the force modes promote
 * demoted fully-typed words at the first Select press of a no-signal state.
 */
@RunWith(RobolectricTestRunner::class)
class SelectBehaviorScenarioTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val repo get() = h.repo

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { repo ->
			repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_PAGED)
			repo.putInt(Constants.KEY_PAGED_LISTED_WORDS, 2)
		}
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private fun entryType(e: Map<String, Any?>): String? = e["type"] as? String

	private fun isDemotedFtsRow(e: Map<String, Any?>): Boolean {
		val type = entryType(e)
		return type in setOf("X", "L", "E", "2") &&
			((e["keysRemaining"] as? Int) ?: if (type == "L") 1 else 0) == 0
	}

	/** Ambig index 0..5 -> keyboard button on the Optimized grid. */
	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)

	/**
	 * Types "organs" (no context — pure trie): organized/organization head the
	 * interleaved list while fully-typed organs sits demoted below, and no
	 * fully-typed word is in the head. Two-key states can't produce this —
	 * 2-letter kr0 words are frequent enough that whenever one is demoted an
	 * even likelier one heads the list (the us/uk ambiguity shape). Returns
	 * the demoted fully-typed indices.
	 */
	private fun typeHeadMissWord(): List<Int> {
		jtui.wordKeySequence("organs")!!.forEach { jtui.buttonPressed(pagePos[it]) }
		val list = jtui.selectionListForTest()
		assertThat(list.take(2).none { isDemotedFtsRow(it) }).isTrue()
		val demoted = list.indices.drop(2).filter { isDemotedFtsRow(list[it]) }
		assertThat(demoted).isNotEmpty()
		return demoted
	}

	@Test fun `paged pick records one episode with kind, depth and state`() {
		jtui.buttonPressed(0)
		jtui.buttonPressed(0)
		repeat(3) { jtui.buttonPressed(6) } // rows 0, 1, then page 1
		jtui.buttonPressed(0) // pick page cell 0 = list row 2: FINAL commit
		val stats = jtui.selStatsForTest()
		assertThat(stats).hasSize(1)
		val bucket = stats.keys.single()
		// No confidence signal in this setup; outcome depth is page 1.
		assertThat(bucket).startsWith("ns_")
		assertThat(bucket).endsWith(".p1")
		assertThat(stats[bucket]).isWithin(1e-9).of(1.0)
	}

	@Test fun `linear head commit records depth h and decays prior episodes`() {
		jtui.buttonPressed(0)
		jtui.buttonPressed(0)
		repeat(3) { jtui.buttonPressed(6) }
		jtui.buttonPressed(0) // first episode: page-1 pick
		jtui.buttonPressed(0)
		jtui.buttonPressed(0)
		jtui.buttonPressed(6) // second episode: row 0 selected
		jtui.buttonPressed(0) // ambig key finalizes the selection (head commit)
		val stats = jtui.selStatsForTest()
		val headBuckets = stats.keys.filter { it.endsWith(".h") }
		assertThat(headBuckets).hasSize(1)
		assertThat(stats[headBuckets.single()]).isWithin(1e-9).of(1.0)
		// The earlier page-1 episode decayed once.
		val p1Bucket = stats.keys.single { it.endsWith(".p1") }
		assertThat(stats[p1Bucket]).isWithin(1e-9).of(0.99)
	}

	@Test fun `abandoning an engagement records an abandon episode`() {
		jtui.buttonPressed(0)
		jtui.buttonPressed(0)
		jtui.buttonPressed(6) // engage: row 0 selected
		jtui.buttonPressed(1) // UnDo the Select step (selection back to null)
		jtui.buttonPressed(0) // keep typing: list rebuild closes the episode
		val stats = jtui.selStatsForTest()
		assertThat(stats.keys.filter { it.endsWith(":abandon") }).hasSize(1)
	}

	@Test fun `observe mode never reorders the list`() {
		typeHeadMissWord()
		val before = jtui.selectionListForTest().map { it["output"] }
		jtui.buttonPressed(6)
		assertThat(jtui.selectionListForTest().map { it["output"] }).isEqualTo(before)
	}

	@Test fun `adaptive mode is reserved - observes only, never reorders`() {
		repo.putInt(DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE, 3)
		typeHeadMissWord()
		val before = jtui.selectionListForTest().map { it["output"] }
		jtui.buttonPressed(6)
		assertThat(jtui.selectionListForTest().map { it["output"] }).isEqualTo(before)
	}

	@Test fun `force-head promotes demoted fully-typed words to the head on first Select`() {
		repo.putInt(DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE, 2)
		val demotedIdx = typeHeadMissWord()
		val listBefore = jtui.selectionListForTest()
		val demotedWords = demotedIdx.map { listBefore[it]["output"] }
		jtui.buttonPressed(6) // first Select press applies the promotion
		val after = jtui.selectionListForTest()
		// The promoted words now lead the list, relative order preserved.
		assertThat(after.take(demotedWords.size).map { it["output"] }).isEqualTo(demotedWords)
		// The first press selects the top promoted word.
		assertThat(h.lastSnapshot!!.currentSelectionIndex).isEqualTo(0)
		// Episode state recorded the pre-promotion truth (head miss).
		jtui.buttonPressed(0) // finalize the selection
		val stats = jtui.selStatsForTest()
		val bucket = stats.keys.single()
		assertThat(bucket).startsWith("ns_m:")
		assertThat(bucket).endsWith("F.h")
	}

	@Test fun `sel_stats EWMA decays per episode and scopes by language`() {
		val db = WordDb.openStandalone(tmpDir.newFile("SelStats.db"))
		db.use {
			it.selStatsRecord("English", "ns_d:F.p1", 0.99)
			it.selStatsRecord("English", "ns_n:N.h", 0.99)
			it.selStatsRecord("Vietnamese", "ns_d:F.p1", 0.99)
			val en = it.selStatsAll("English")
			assertThat(en["ns_d:F.p1"]).isWithin(1e-9).of(0.99)
			assertThat(en["ns_n:N.h"]).isWithin(1e-9).of(1.0)
			// Other languages never decay a foreign language's evidence.
			assertThat(it.selStatsAll("Vietnamese")["ns_d:F.p1"]).isWithin(1e-9).of(1.0)
		}
	}

	@Test fun `force-page1 moves demoted fully-typed words onto page one`() {
		repo.putInt(DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE, 1)
		val demotedIdx = typeHeadMissWord()
		val listBefore = jtui.selectionListForTest()
		val headBefore = listBefore.take(2).map { it["output"] }
		val demotedWords = demotedIdx.map { listBefore[it]["output"] }
		jtui.buttonPressed(6)
		val after = jtui.selectionListForTest()
		// Head untouched; demoted FTS rows lead the paged region (rows 2+).
		assertThat(after.take(2).map { it["output"] }).isEqualTo(headBefore)
		assertThat(after.drop(2).take(demotedWords.size).map { it["output"] }).isEqualTo(demotedWords)
	}
}
