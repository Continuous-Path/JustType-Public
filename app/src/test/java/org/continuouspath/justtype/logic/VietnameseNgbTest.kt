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
 * NGB context predictions end-to-end on the real TiengViet DB
 * (docs/.plans/ngram/engine-spec.md): pool fetch on word commit, prediction
 * block ABOVE the letter-exact entries (anchor=0), key-prefix filtering,
 * fail-soft context, and the settings kill-switch.
 */
@RunWith(RobolectricTestRunner::class)
class VietnameseNgbTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { repo ->
			repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
			// These tests assert list-buffer content; pin the mode they were
			// written under (paged became the shipped default, 2026-08-10).
			repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		}
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6
	private val undoPos = 1

	private fun enableTav() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TONE_ENTRY_POSITION, Constants.TONE_ENTRY_AFTER_VOWEL)
		jtui.init()
	}

	private fun listedWords(): List<String> = h.lastSnapshot!!.selectionListBuffers
		.joinToString("\n")
		.split("\n")
		.map { it.trim() }
		.filter { it.isNotEmpty() }

	/** Types [word]'s keys, selects the top entry, then presses the first
	 *  [nextKeyCount] keys of [next] — the AK-after-SEL commit path (KF_Term
	 *  rides every letter key). Two keys minimum: the harness runs without the
	 *  IME's async search callback, and a single-key search stays deferred. */
	private fun commitThenStart(word: String, next: String, nextKeyCount: Int = 2): List<Int> {
		val keys = jtui.wordKeySequence(word)!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		val nextKeys = jtui.wordKeySequence(next)!!
		nextKeys.take(nextKeyCount).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		return nextKeys
	}

	@Test fun `committing a word promotes its followers above letter-exact entries`() {
		// chúc -> mừng: a canonical Vietnamese bigram. After committing "chúc"
		// and typing two of mừng's keys, the block must already list "mừng" —
		// ahead of every letter-exact/frequency candidate for those keys.
		commitThenStart("chúc", "mừng")
		val words = listedWords()
		assertThat(words).isNotEmpty()
		assertThat(words.indexOf("mừng")).isAtLeast(0)
		assertThat(words.indexOf("mừng")).isLessThan(NgbEngine.BLOCK_SIZE)
	}

	@Test fun `multi-syllable unit predictions surface and survive deeper typing`() {
		// chúc -> "sức khỏe" (unit target). After chúc + s-key, the unit should
		// be in the block; after typing all of sức's keys it must still match
		// (key-prefix compatibility across the internal space).
		val sucKeys = commitThenStart("chúc", "sức")
		assertThat(listedWords().take(NgbEngine.BLOCK_SIZE + 2).any { it == "sức khỏe" }).isTrue()
		sucKeys.drop(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(listedWords().take(NgbEngine.BLOCK_SIZE + 2).any { it == "sức khỏe" }).isTrue()
	}

	@Test fun `letter-exact entries survive below the block`() {
		// The block must never hide the letter-exact interpretation: after
		// chúc + two keys, the list still contains ordinary candidates beyond
		// the block (reachability guarantee).
		commitThenStart("chúc", "mừng")
		val words = listedWords()
		assertThat(words.size).isGreaterThan(NgbEngine.BLOCK_SIZE)
	}

	@Test fun `accepting a multi-syllable unit records usage on every component`() {
		val beforeSuc = jtui.wordUseCountForTest("sức")
		val beforeKhoe = jtui.wordUseCountForTest("khỏe")
		commitThenStart("chúc", "sức")
		// Step the selection onto the "sức khỏe" unit entry, then commit it
		// with the next word's first key (AK-after-SEL).
		val words = listedWords()
		val unitIdx = words.indexOfFirst { it.equals("sức khỏe", ignoreCase = true) }
		assertThat(unitIdx).isAtLeast(0)
		repeat(unitIdx + 1) { jtui.buttonPressed(selectPos) }
		jtui.wordKeySequence("năm")!!.take(1).forEach { jtui.buttonPressed(pagePos[it]) }
		assertThat(jtui.wordUseCountForTest("sức")).isGreaterThan(beforeSuc)
		assertThat(jtui.wordUseCountForTest("khỏe")).isGreaterThan(beforeKhoe)
	}

	@Test fun `vietnamese derives syllable-based traits with tone keys`() {
		assertThat(jtui.ngbTraitsForTest())
			.isEqualTo(LanguageTraits(syllableBased = true, hasToneKeys = true))
	}

	// ── C3: pull-in NGB-span expansion ──

	/** Probe + tapped-word replay + activation: the IME pull-in flow's span path. */
	private fun spanPullIn(tapped: String, preceding: String?, following: String): Pair<Int, String?> {
		val extra = jtui.ngbSpanProbe(tapped, preceding, following)
		jtui.wordKeySequence(tapped)!!.forEach { jtui.buttonPressed(pagePos[it]) }
		val out = if (extra > 0) jtui.ngbSpanActivate() else null
		jtui.forceUpdateUi()
		return extra to out
	}

	@Test fun `span probe matches the following text and selects the longest - C3`() {
		val following = " mừng năm mới"
		val (extra, out) = spanPullIn("chúc", null, following)
		assertThat(extra).isGreaterThan(0)
		// The selected output is the span AS IT STANDS IN THE FIELD.
		assertThat(out).isEqualTo("chúc" + following.substring(0, extra))
		assertThat(h.lastSnapshot!!.currentSelectionIndex).isEqualTo(0)
		assertThat(listedWords().first()).isEqualTo(out)
	}

	@Test fun `mid-unit tap offers the remainder from here - C3`() {
		// Tapping "Chí" inside "... Hồ Chí Minh": ctx "hồ" (C2), whose pool
		// holds the remainder "chí minh" — a span STARTING at the tap. Spans
		// never reach backward past the tapped word.
		val extra = jtui.ngbSpanProbe("Chí", "Thành phố Hồ", " Minh.")
		assertThat(extra).isEqualTo(" Minh".length)
	}

	@Test fun `no span offer when the following text does not match - C3`() {
		assertThat(jtui.ngbSpanProbe("chúc", null, " bạn hiền")).isEqualTo(0)
		// Word-boundary required: "mừngxyz" must not match "mừng".
		assertThat(jtui.ngbSpanProbe("chúc", null, " mừngxyz")).isEqualTo(0)
	}

	@Test fun `undo off the span head collapses to the tapped word - C3`() {
		val (_, out) = spanPullIn("chúc", null, " mừng")
		assertThat(out).isNotNull()
		jtui.buttonPressed(undoPos) // pops the pre-selection snapshot -> collapse
		jtui.forceUpdateUi()
		assertThat(h.spanCollapses).isEqualTo(1)
		val words = listedWords()
		assertThat(words.none { it.contains(' ') }).isTrue() // NGB span entries gone
		val sel = h.lastSnapshot!!.currentSelectionIndex
		assertThat(sel).isNotNull()
		assertThat(words[sel!!].lowercase()).isEqualTo("chúc")
	}

	@Test fun `select stepping past the span group collapses identically - C3`() {
		val (_, out) = spanPullIn("chúc", null, " mừng")
		assertThat(out).isNotNull()
		// Step forward until the group boundary is crossed (group is small).
		repeat(4) { if (h.spanCollapses == 0) jtui.buttonPressed(selectPos) }
		jtui.forceUpdateUi()
		assertThat(h.spanCollapses).isEqualTo(1)
		val words = listedWords()
		val sel = h.lastSnapshot!!.currentSelectionIndex
		assertThat(sel).isNotNull()
		assertThat(words[sel!!].lowercase()).isEqualTo("chúc")
	}

	@Test fun `ambiguous key during span session collapses then applies - C3`() {
		val (_, out) = spanPullIn("chúc", null, " mừng")
		assertThat(out).isNotNull()
		// AK: collapse first (ruling a); the collapsed state has the tapped
		// word selected, so AK-after-SEL commits it and starts the new word.
		val namKeys = jtui.wordKeySequence("năm")!!
		jtui.buttonPressed(pagePos[namKeys[0]])
		assertThat(h.spanCollapses).isEqualTo(1)
		assertThat(jtui.ngbContextForTest().first).isEqualTo("chúc")
	}

	@Test fun `suppressed first page renders continuously - no phantom page gap`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_PAGED)
		// khoẻ's full sequence yields 3 candidates — below the 4-item
		// first-page minimum, so paging cannot engage; a page-styled blank
		// separator would masquerade as a page element (Cliff, 2026-08-09).
		val keys = jtui.wordKeySequence("khoẻ")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		val buf = h.lastSnapshot!!.selectionListBuffers.joinToString("\n")
		assertThat(buf.split("\n").count { it.trim().isNotEmpty() }).isAtLeast(2)
		assertThat(buf).doesNotContain("\n\n")
	}

	@Test fun `reconstructed context restores predictions - C2`() {
		// After a whole-word delete, the IME re-derives context from the text
		// before the cursor: the zero-K window reopens and the replacement
		// word gets its prediction block.
		jtui.ngbReconstructContext("tôi chúc")
		assertThat(jtui.ngbContextForTest()).isEqualTo("chúc" to true)
		jtui.forceUpdateUi()
		// Zero-K follower window (auto-shift may case the display forms).
		assertThat(listedWords().map { it.lowercase() }).contains("mừng")
		val keys = jtui.wordKeySequence("mừng")!!
		keys.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(jtui.ngbContextForTest()).isEqualTo("chúc" to true)
		val idx = listedWords().indexOfFirst { it.equals("mừng", ignoreCase = true) }
		assertThat(idx).isAtLeast(0)
		assertThat(idx).isLessThan(NgbEngine.BLOCK_SIZE)
	}

	@Test fun `reconstruction honors the gate and fails soft - C2`() {
		// Trailing multi-syllable unit: complete word, entry-state gate CLOSED.
		jtui.ngbReconstructContext("chúc sức khỏe")
		assertThat(jtui.ngbContextForTest()).isEqualTo("khỏe" to false)
		// Punctuation at the boundary breaks context (fail-soft).
		jtui.ngbReconstructContext("xin chào,")
		assertThat(jtui.ngbContextForTest().first).isNull()
		jtui.ngbReconstructContext(null)
		assertThat(jtui.ngbContextForTest().first).isNull()
	}

	@Test fun `toggle off restores the frequency-only list`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putBoolean(Constants.KEY_NGB_PREDICTIONS, false)
		commitThenStart("chúc", "mừng")
		val words = listedWords()
		// mừng (freqClass-ranked) must not have been promoted into the block:
		// with a single key typed it cannot sit in the first block-size slots.
		assertThat(words.indexOf("mừng")).isNotEqualTo(0)
	}

	@Test fun `selecting a prediction drives the composing preview`() {
		// Regression (found on device): the composing region follows the SELECTED
		// entry, and finalization commits composing — a type-N selection that
		// doesn't update the preview commits the WRONG word ("an" for "mừng").
		commitThenStart("chúc", "mừng")
		jtui.buttonPressed(selectPos)
		jtui.forceUpdateUi()
		assertThat(h.lastSnapshot!!.selectedCandidateOutput).isEqualTo("mừng")
	}

	@Test fun `undo of a keystroke keeps the word's context - issue 6`() {
		// Cliff's misspelling scenario: chúc committed, wrong key typed, UNDO,
		// correct keys retyped — the chúc context must survive the undo and
		// mừng must be promoted again.
		val keys = jtui.wordKeySequence("chúc")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		val mKeys = jtui.wordKeySequence("mừng")!!
		jtui.buttonPressed(pagePos[mKeys[0]]) // commits chúc, starts new word
		jtui.buttonPressed(pagePos[if (mKeys[1] == 5) 0 else 5]) // a WRONG key
		jtui.buttonPressed(undoPos) // UNDO removes the wrong keystroke
		jtui.buttonPressed(pagePos[mKeys[1]]) // correct second key
		jtui.forceUpdateUi()
		assertThat(listedWords().indexOf("mừng")).isAtLeast(0)
		assertThat(listedWords().indexOf("mừng")).isLessThan(NgbEngine.BLOCK_SIZE)
	}

	@Test fun `select key previews the prediction before and the next item after - issue 1`() {
		commitThenStart("chúc", "mừng")
		assertThat(h.lastSnapshot!!.keyLabels[selectPos]).contains("mừng")
		jtui.buttonPressed(selectPos)
		jtui.forceUpdateUi()
		assertThat(h.lastSnapshot!!.keyLabels[selectPos]).contains("anh")
	}

	@Test fun `composing preview is WYSIWYG - top item including predictions - issue 2`() {
		commitThenStart("chúc", "mừng")
		assertThat(h.lastSnapshot!!.topCandidateOutput).isEqualTo("mừng")
	}

	@Test fun `pull-in replay includes tone keystrokes in both tone modes`() {
		// mapWordToKeyIndices feeds pull-in replay; it must equal the WLD
		// encoding — tone keystroke included, placed per the CURRENT setting
		// (restoration-time rule, Cliff 2026-08-08). The old letters-only map
		// dropped tone keys entirely.
		val tae = jtui.mapWordToKeyIndices("chúc")
		assertThat(tae).isEqualTo(jtui.wordKeySequence("chúc"))
		assertThat(tae!!.size).isEqualTo(5) // c,h,u,c + sắc appended (TAE)
		enableTav()
		val tav = jtui.mapWordToKeyIndices("chúc")
		assertThat(tav).isEqualTo(jtui.wordKeySequence("chúc"))
		assertThat(tav!!.size).isEqualTo(5) // c,h,u + sắc after carrier + c
		assertThat(tav).isNotEqualTo(tae)
		tav.forEach { jtui.pressAmbiguousKeyNumberSilently(it, true) }
		assertThat(jtui.getSelectionOutputs().any { it.equals("chúc", ignoreCase = true) }).isTrue()
	}

	@Test fun `recognition learns transitions on the table's segmentation basis`() {
		// Type "cảm ơn bạn" word by word through the real commit paths. The
		// recognizer must merge cảm+ơn into the unit (no interim transition)
		// and learn exactly (ơn -> bạn) when the run flushes.
		for (word in listOf("cảm", "ơn", "bạn")) {
			val keys = jtui.wordKeySequence(word)!!
			keys.forEach { jtui.buttonPressed(pagePos[it]) }
			jtui.buttonPressed(selectPos)
		}
		jtui.buttonPressed(pagePos[0]) // AK commits bạn, starts a throwaway word
		jtui.resetJTUI(false, false) // run ends: recognizer flushes
		assertThat(jtui.ngbUserRowsForTest("ơn")).contains("bạn" to 1)
		assertThat(jtui.ngbUserRowsForTest("cảm")).isEmpty()
	}

	@Test fun `no learning in suppressed - password - fields`() {
		jtui.ngbLearningSuppressed = true
		for (word in listOf("cảm", "ơn", "bạn")) {
			val keys = jtui.wordKeySequence(word)!!
			keys.forEach { jtui.buttonPressed(pagePos[it]) }
			jtui.buttonPressed(selectPos)
		}
		jtui.buttonPressed(pagePos[0])
		jtui.resetJTUI(false, false)
		assertThat(jtui.ngbUserRowsForTest("ơn")).isEmpty()
	}

	@Test fun `predictions display the DB's preferred case form`() {
		// việt -> nam: the DB's case counts make "Nam" title-dominant; the
		// prediction must surface as "Nam", not raw lowercase (device round 2).
		commitThenStart("việt", "nam")
		val words = listedWords()
		assertThat(words.take(NgbEngine.BLOCK_SIZE + 1)).contains("Nam")
	}

	@Test fun `block dedup never evicts other case variants`() {
		// là -> hà: whichever form the block shows, the OTHER DB case variant
		// ("Hà" title / "hà" lower) must survive below (device round 2: the
		// canonical dedup was deleting the title-case entry entirely).
		// Blocks + promoted tone fixture: hà must be tone-guaranteed listed so
		// the dedup rule (the target here) is what decides the variant's fate —
		// under the interleave default it can miss the ITS budget legitimately.
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putInt("sls_partition_policy", 0)
		repo.putBoolean("sls_tone_promoted", true)
		commitThenStart("là", "hà")
		val words = listedWords()
		assertThat(words.any { it == "Hà" }).isTrue()
	}

	@Test fun `paged final pick commits and opens the zero-K window`() {
		// Enter paged selection, pick via AK: the selection is FINAL — the
		// sequence empties (committed) and the follower window can populate.
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_PAGED)
		// Written under blocks + promoted tone rows (>= 6 guaranteed entries at
		// c-h); pin that fixture — the paged commit mechanics are the target.
		repo.putInt("sls_partition_policy", 0)
		repo.putBoolean("sls_tone_promoted", true)
		val keys = jtui.wordKeySequence("chưa")!! // c,h prefix: 8+ candidates
		keys.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(listedWords().size).isAtLeast(6) // page mode reachable
		repeat(3) { jtui.buttonPressed(selectPos) } // slots 1,2 then page mode
		jtui.buttonPressed(pagePos[0]) // Key 0 = page slot 1: unambiguous pick
		jtui.forceUpdateUi()
		assertThat(h.lastSnapshot!!.ambigBuffer.trim()).isEmpty()
	}

	@Test fun `proper-noun units display canonical dictionary case`() {
		// thành -> remainder of "Thành phố Hồ Chí Minh": per-syllable counts
		// cannot produce proper-noun caps (chí is lower-dominant as a common
		// word); the unit's Kaikki orthography must flow through — including
		// to REMAINDERS, which inherit the parent unit's display.
		commitThenStart("thành", "phố")
		assertThat(listedWords().any { it == "phố Hồ Chí Minh" }).isTrue()
	}

	@Test fun `first select returns keyboard to initial state - issue 7`() {
		// TAV: mid-sequence the tone keys show per-vowel forms; after the first
		// SELECT the word is chosen — forms clear, list-function badges return.
		enableTav()
		val keys = jtui.wordKeySequence("chúc")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.forceUpdateUi()
		val grids = h.lastSnapshot!!.keyLabelGrids
		for (k in 0..5) {
			assertThat(grids[pagePos[k]][7]).isEmpty()
			assertThat(grids[pagePos[k]][4]).isEmpty()
		}
		assertThat((0..5).any { grids[pagePos[it]][1].isNotEmpty() }).isTrue()
	}
}
