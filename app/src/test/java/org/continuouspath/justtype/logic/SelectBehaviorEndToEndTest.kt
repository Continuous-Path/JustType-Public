package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.activity.DeveloperSettingsActivity
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Adaptive select-behavior machinery END-TO-END on the built-in English DB,
 * replaying residual-diagnostic states (docs/.plans/sls-select-test-recipes.md):
 *
 *  - HEAD MISS ("m"): nothing fully typed in the head, the typed word demoted
 *    below — "and -> organs" (pure trie), "said -> to" (dual-source N row).
 *    The state the force ladder promotes and the adaptive ramp will act on.
 *  - AMBIGUITY ("d"): a fully-typed alternative already heads the list —
 *    "the -> uk" (us shares the key sequence). Promotion must stand down.
 *
 * Covers the full loop: state classification, episode recording through the
 * real commit paths, EWMA decay, force promotion, signal-fired stand-down,
 * warming reversal, and custom-DB persistence (the Dev readout rows).
 */
@RunWith(RobolectricTestRunner::class)
class SelectBehaviorEndToEndTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val repo get() = h.repo

	@Before fun setUp() {
		h = TestJtui(tmpDir.root)
		val appRepo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		appRepo.putString(Constants.KEY_TYPING_LANGUAGE, "English")
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	/** JTUI paged cell order (PAGED_ORDINAL_FOR_AMBIG): flat offset per ambig key. */
	private val pagedOrdinalForAmbig = intArrayOf(0, 3, 1, 4, 2, 5)

	private fun type(word: String, keyCount: Int = Int.MAX_VALUE) {
		jtui.wordKeySequence(word)!!.take(keyCount).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
	}

	/** Type [context], commit it (Select + first key of [next] = AK-after-SEL
	 *  finalize), then type all of [next]. Mirrors real texting flow — the
	 *  context commit itself records a depth-h episode, by design. */
	private fun commitThenType(context: String, next: String) {
		type(context)
		jtui.buttonPressed(selectPos)
		type(next)
	}

	private fun list() = jtui.selectionListForTest()

	private fun outputAt(i: Int): String = (list()[i]["output"] as? String).orEmpty()

	private fun indexOfWord(word: String): Int = list().indexOfFirst { (it["output"] as? String)?.equals(word, ignoreCase = true) == true }

	/** Fully-specified at the current sequence: trie kr==0, or a single-word
	 *  N row whose full key length equals the typed length (dual source). */
	private fun isFullyTyped(e: Map<String, Any?>, typedLen: Int): Boolean = when (e["type"]) {
		"X", "E", "2" -> ((e["keysRemaining"] as? Int) ?: 0) == 0
		"L" -> ((e["keysRemaining"] as? Int) ?: 1) == 0
		"N" -> e["ngbMulti"] != true && (e["ngbKeySeqLen"] as? Int) == typedLen
		else -> false
	}

	/** Asserts the HEAD-MISS state: [word] fully typed strictly below a head
	 *  with no fully-typed row. Returns the word's flat index. */
	private fun assertHeadMissState(word: String): Int {
		val typedLen = jtui.wordKeySequence(word)!!.size
		val idx = indexOfWord(word)
		assertThat(idx).isAtLeast(2)
		assertThat(isFullyTyped(list()[idx], typedLen)).isTrue()
		assertThat(list().take(2).none { isFullyTyped(it, typedLen) }).isTrue()
		return idx
	}

	/** Retire the cold-start FTS floor for [word] by using it once — floored,
	 *  a cold top-band fully-typed word heads its list and no head-miss
	 *  exists to exercise (the floor is the FIX for those states). */
	private fun warmWord(word: String) {
		type(word)
		jtui.buttonPressed(selectPos)
		type("and", keyCount = 1) // AK-after-SEL commits
		jtui.resetJTUI(false, false)
	}

	/** Paged dig: Select to row 0, row 1, then page 1; letter-pick flat [idx]. */
	private fun digAndPick(idx: Int) {
		repeat(3) { jtui.buttonPressed(selectPos) }
		val ordinal = idx - 2
		assertThat(ordinal).isLessThan(6) // recipe targets sit on page 1
		jtui.buttonPressed(pagePos[pagedOrdinalForAmbig.indexOf(ordinal)])
	}

	@Test fun `observe - digging to the head-missed word records an ns_m F p1 episode`() {
		commitThenType("and", "organs") // recipe EN-4: organized/organization head
		val idx = assertHeadMissState("organs")
		val before = jtui.wordUseCountForTest("organs")
		digAndPick(idx)
		// The paged pick is a FINAL commit: usage recorded, episode closed.
		assertThat(jtui.wordUseCountForTest("organs")).isGreaterThan(before)
		val stats = jtui.selStatsForTest()
		assertThat(stats["ns_m:F.p1"]).isWithin(1e-9).of(1.0)
		// The context commit ("and" at slot 1) was itself an episode, decayed once.
		val headEpisode = stats.entries.single { it.key.endsWith(":F.h") }
		assertThat(headEpisode.value).isWithin(1e-9).of(0.99)
	}

	@Test fun `force-head - the Select press lifts a dual-source typed word to slot 1`() {
		repo.putInt(DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE, 2)
		warmWord("to") // cold-floored it would head the list — no miss to fix
		commitThenType("said", "to") // recipe EN-7: the/that predictions head; to is an N row
		assertHeadMissState("to")
		val before = jtui.wordUseCountForTest("to")
		jtui.buttonPressed(selectPos)
		// Promotion happened at the press, before the first step landed.
		assertThat(outputAt(0).lowercase()).isEqualTo("to")
		assertThat(h.lastSnapshot!!.currentSelectionIndex).isEqualTo(0)
		// AK-after-SEL commits the promoted word — the FTS/NS flow end-to-end.
		type("and", keyCount = 1)
		assertThat(jtui.wordUseCountForTest("to")).isGreaterThan(before)
		// The dual-source row commits as kind F (fully specified), at the head,
		// in a no-signal head-miss state.
		assertThat(jtui.selStatsForTest()["ns_m:F.h"]).isWithin(1e-9).of(1.0)
	}

	@Test fun `force-page1 - a deep typed word moves to the leading page-1 cells`() {
		repo.putInt(DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE, 1)
		commitThenType("in", "situ") // recipe EN-9: situ at the last page-1 cell
		val idx = assertHeadMissState("situ")
		val headBefore = list().take(2).map { it["output"] }
		jtui.buttonPressed(selectPos)
		// Head untouched; the demoted word is now nearer the front of page 1.
		assertThat(list().take(2).map { it["output"] }).isEqualTo(headBefore)
		val after = indexOfWord("situ")
		assertThat(after).isAtLeast(2)
		assertThat(after).isLessThan(idx)
	}

	@Test fun `ambiguity state - force-head stands down when the head already shows a typed word`() {
		repo.putInt(DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE, 2)
		commitThenType("the", "uk") // recipe EN-1: us (same keys) heads the list
		val typedLen = jtui.wordKeySequence("uk")!!.size
		// uk IS fully typed below the head — but so is us, in the head.
		val ukIdx = indexOfWord("uk")
		assertThat(ukIdx).isAtLeast(2)
		assertThat(isFullyTyped(list()[ukIdx], typedLen)).isTrue()
		assertThat(list().take(2).any { isFullyTyped(it, typedLen) }).isTrue()
		val orderBefore = list().map { it["output"] }
		jtui.buttonPressed(selectPos)
		// Promotion must NOT fire: digging here is key ambiguity, not a miss.
		assertThat(list().map { it["output"] }).isEqualTo(orderBefore)
		type("and", keyCount = 1) // commit the selected head row
		val stats = jtui.selStatsForTest()
		assertThat(stats.keys.filter { it.startsWith("ns_m:") }).isEmpty()
		assertThat(stats.keys.filter { it.startsWith("ns_d:") }).isNotEmpty()
	}

	@Test fun `signal fired - episode records s_m and force-head stands down`() {
		repo.putBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, true)
		repo.putInt(Constants.KEY_NGB_CONFIDENCE_THRESHOLD, 20) // most permissive theta
		repo.putBoolean(DeveloperSettingsActivity.KEY_NGB_CONF_THETA_ADAPTIVE, false)
		repo.putInt(DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE, 2)
		warmWord("to")
		warmWord("us")
		// Head-miss recipes differ in p-hat; find one where the signal FIRES
		// for the fully-typed state (deterministic given the fixed DB+weights).
		val recipes = listOf("said" to "to", "to" to "us", "the" to "sa", "and" to "organs")
		var target: String? = null
		for ((ctx, word) in recipes) {
			commitThenType(ctx, word)
			assertHeadMissState(word)
			if (jtui.ngbConfLastObservationForTest?.third == true) {
				target = word
				break
			}
			// Clear the typed target (UnDo per keystroke) and try the next state.
			repeat(jtui.wordKeySequence(word)!!.size) { jtui.buttonPressed(1) }
		}
		assertThat(target).isNotNull()
		assertThat(h.confidenceSignals).isGreaterThan(0)
		val orderBefore = list().map { it["output"] }
		jtui.buttonPressed(selectPos)
		// Mechanism A never acts where the signal guided the user: no reorder.
		assertThat(list().map { it["output"] }).isEqualTo(orderBefore)
		// Already engaged at row 0 by the press above: two more Selects reach
		// page 1, then letter-pick the target's cell.
		val idx = indexOfWord(target!!)
		repeat(2) { jtui.buttonPressed(selectPos) }
		jtui.buttonPressed(pagePos[pagedOrdinalForAmbig.indexOf(idx - 2)])
		// The dig episode is the ONLY signal-fired head-miss one (context
		// commits are never head-miss states — their word heads the list).
		val signaled = jtui.selStatsForTest().keys.filter { it.startsWith("s_m:") }
		assertThat(signaled).containsExactly("s_m:F.p1")
	}

	@Test fun `episodes persist in the custom DB with EWMA decay - the Dev readout rows`() {
		commitThenType("and", "organs")
		digAndPick(assertHeadMissState("organs"))
		val stats = jtui.selStatsForTest()
		assertThat(stats["ns_m:F.p1"]).isWithin(1e-9).of(1.0)
		// Same rows through the standalone connection the Dev dialog uses.
		WordDb.openStandalone(File(tmpDir.root, "CustomDb.db")).use { db ->
			val dump = db.selStatsDump()
			val row = dump.single { it.first == "English" && it.second == "ns_m:F.p1" }
			assertThat(row.third).isWithin(1e-9).of(1.0)
			// EWMA invariant: bucket sum = decayed episode total (2 episodes here).
			val total = dump.filter { it.first == "English" }.sumOf { it.third }
			assertThat(total).isWithin(1e-9).of(0.99 + 1.0)
		}
	}

	@Test fun `warming reverses the demotion - the recipe self-destructs by design`() {
		// After one dig-and-pick, useCount + recency (bucket 0 under the tuned
		// rec weights) must rescue the word into the head — the reversibility
		// premise the EWMA decay is built around, and why crib-sheet recipes
		// wear out.
		commitThenType("and", "organs")
		digAndPick(assertHeadMissState("organs"))
		commitThenType("and", "organs")
		assertThat(indexOfWord("organs")).isLessThan(2)
	}
}
