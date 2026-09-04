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
 * Vietnamese selection-list ordering + next-tone-mark prediction, end-to-end on
 * the real TiengViet DB. The core invariant: fully-typed strings (tone keystroke
 * included — keysRemaining == 0) always sort above incompletely-typed strings,
 * measured in KEYSTROKES, not characters (Việt = 4 letters + nặng = 5 keys).
 */
@RunWith(RobolectricTestRunner::class)
class VietnameseSelectionTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { repo ->
			repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
			// List-buffer assertions; pin the mode these tests were written
			// under (paged became the shipped default, 2026-08-10).
			repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		}
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		jtui.init()
		jtui.showNextLetterHints = true
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)

	/** Words currently in the selection list, top to bottom (column buffers flattened). */
	private fun listedWords(): List<String> = h.lastSnapshot!!.selectionListBuffers
		.joinToString("\n")
		.split("\n")
		.map { it.trim() }
		.filter { it.isNotEmpty() }

	@Test fun `fully-typed toned words sort above incomplete longer words`() {
		// Blocks-policy semantics (shipped default is now Interleave).
		setSlsPolicy(0, tonePromoted = true, seqAdd = 20f, seqMult = 2.5f)
		val vietKeys = jtui.wordKeySequence("việt")
		assertThat(vietKeys).isNotNull()
		assertThat(vietKeys!!.size).isEqualTo(5) // 4 letter keys + nặng tone key

		vietKeys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val words = listedWords()
		assertThat(words).isNotEmpty()
		// FTS = the word's full trie sequence (tones included) is exactly the 4
		// pressed keys; ITS = it still needs keystrokes. FTS must all sort first.
		val complete = words.map { w -> jtui.wordKeySequence(w.lowercase())?.size == 4 }
		assertThat(complete.first()).isTrue()
		val firstIncomplete = complete.indexOfFirst { !it }
		if (firstIncomplete >= 0) {
			assertThat(complete.subList(firstIncomplete, complete.size).none { it }).isTrue()
		}
		// "việt" (5 keys) is offered — but only below every fully-typed word.
		val vietIdx = words.indexOfFirst { it.equals("việt", ignoreCase = true) }
		if (vietIdx >= 0 && firstIncomplete >= 0) {
			assertThat(vietIdx).isAtLeast(firstIncomplete)
		}
	}

	@Test fun `next-tone-mark prediction highlights completing tones only`() {
		val vietKeys = jtui.wordKeySequence("việt")!!
		vietKeys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val toneKeys = h.lastSnapshot!!.nextToneKeys
		assertThat(toneKeys).isNotNull()
		// nặng completes "việt", so its key must be offered.
		assertThat(toneKeys).contains(vietKeys.last())
	}

	@Test fun `tone prediction is inactive outside a sequence`() {
		jtui.forceUpdateUi()
		assertThat(h.lastSnapshot!!.nextToneKeys).isNull()
	}

	@Test fun `tone-pending words outrank letter-incomplete words regardless of frequency`() {
		// p-h-ê-n: guaranteed = 2 FTS (phên, phon) + 3 FTEFTM (phến, phòn, phền,
		// freq <= 6) -> ITS budget is 8-5=3, admitting phòng (36k), phóng, phong —
		// but never above the FTEFTM block: letter certainty beats a 6000x
		// frequency edge. phỏng (4th ITS) falls outside the budget.
		setSlsPolicy(0, tonePromoted = true, seqAdd = 20f, seqMult = 2.5f)
		val keys = jtui.wordKeySequence("phền")!!
		assertThat(keys).hasSize(5) // 4 letters + huyền

		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val words = listedWords().map { it.lowercase() }
		val toneOnly = listOf("phến", "phòn", "phền")
		toneOnly.forEach { assertThat(words).contains(it) }
		assertThat(words).contains("phòng")
		val lastToneOnly = toneOnly.maxOf { words.indexOf(it) }
		assertThat(lastToneOnly).isLessThan(words.indexOf("phòng"))
		assertThat(words).doesNotContain("phỏng")
	}

	@Test fun `letter-incomplete words are omitted once guaranteed entries fill the list`() {
		// c-h-u-y: 7 FTS + 5 FTEFTM = 12 guaranteed entries (>= 8) — no letter-ITS
		// are appended, so chuyện (freq 160k, three keystrokes away) is not shown.
		setSlsPolicy(0, tonePromoted = true, seqAdd = 20f, seqMult = 2.5f)
		val keys = jtui.wordKeySequence("chụy")!!
		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val words = listedWords().map { it.lowercase() }
		listOf("chụy", "chùy", "uỵch").forEach { assertThat(words).contains(it) }
		assertThat(words).doesNotContain("chuyện")
	}

	@Test fun `interleave with demoted tone rows is the shipped default`() {
		// No overrides beyond list mode: the campaign defaults (policy 2,
		// tone demoted, seq 1/1) must interleave out of the box — phòng
		// (freqClass 2, one key away) above the rare FTS phên (freqClass 12).
		val keys = jtui.wordKeySequence("phền")!!
		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val words = listedWords().map { it.lowercase() }
		assertThat(words).contains("phên")
		assertThat(words.indexOf("phòng")).isLessThan(words.indexOf("phên"))
	}

	private fun setSlsPolicy(policy: Int, tonePromoted: Boolean, seqAdd: Float, seqMult: Float) {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putInt("sls_partition_policy", policy)
		repo.putBoolean("sls_tone_promoted", tonePromoted)
		repo.putFloat("seq_add_weight", seqAdd)
		repo.putFloat("seq_mult_weight", seqMult)
	}

	@Test fun `interleave policy ranks frequent completions above rare FTS but keeps every FTS listed`() {
		// p-h-ê-n under policy 2 (interleave), tone demoted, seq weights 1/1:
		// phòng (36k, freqClass 2, one key away) outscores the rare FTS phên
		// (21, freqClass 12) — but both FTS stay listed (reachability invariant).
		setSlsPolicy(2, tonePromoted = false, seqAdd = 1f, seqMult = 1f)
		val keys = jtui.wordKeySequence("phền")!!
		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val words = listedWords().map { it.lowercase() }
		assertThat(words).contains("phên")
		assertThat(words).contains("phon")
		assertThat(words.indexOf("phòng")).isLessThan(words.indexOf("phên"))
	}

	@Test fun `FTS pin keeps the best fully-typed word at slot 1 under interleave`() {
		// Policy 1: slot 1 stays the top FTS (phên, freqClass 12 beats phon, 13);
		// below the pin the order is pure metric — phòng above the other FTS.
		setSlsPolicy(1, tonePromoted = false, seqAdd = 1f, seqMult = 1f)
		val keys = jtui.wordKeySequence("phền")!!
		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val words = listedWords().map { it.lowercase() }
		assertThat(words.first()).isEqualTo("phên")
		assertThat(words.indexOf("phòng")).isLessThan(words.indexOf("phon"))
	}

	@Test fun `blocks policy with demoted tone rows fills by metric`() {
		// Policy 0 with toneOnly demoted: FTS block first, then completions by
		// metric — the rare tone-pending phền (freqClass 14) no longer outranks
		// the frequent letter-completion phòng.
		setSlsPolicy(0, tonePromoted = false, seqAdd = 1f, seqMult = 1f)
		val keys = jtui.wordKeySequence("phền")!!
		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val words = listedWords().map { it.lowercase() }
		assertThat(words.first()).isEqualTo("phên")
		val phong = words.indexOf("phòng")
		assertThat(phong).isGreaterThan(words.indexOf("phon"))
		if (words.contains("phền")) {
			assertThat(phong).isLessThan(words.indexOf("phền"))
		}
	}

	private fun enableTav() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TONE_ENTRY_POSITION, Constants.TONE_ENTRY_AFTER_VOWEL)
		jtui.init()
	}

	@Test fun `TAV inserts the tone key after its carrier vowel`() {
		enableTav()
		// hòa: tone carrier is o (mid-word) -> h,o,TONE,a; the plain word shares
		// the letter keys around the inserted tone.
		val toned = jtui.wordKeySequence("hòa")!!
		val plain = jtui.wordKeySequence("hoa")!!
		assertThat(toned).hasSize(4)
		assertThat(plain).hasSize(3)
		assertThat(toned.take(2)).isEqualTo(plain.take(2))
		assertThat(toned[3]).isEqualTo(plain[2])
		// Vowel-final words still put the tone last (TAE and TAV coincide there).
		val sac = jtui.wordKeySequence("chú")!!
		assertThat(sac.take(3)).isEqualTo(jtui.wordKeySequence("chu")!!)
	}

	@Test fun `TAV collapses pre-tone tone families to unmarked rows`() {
		enableTav()
		// c-h-u typed: the chú/chù/chủ/chũ/chụ family is NEVER enumerated before
		// its tone keystroke — the unmarked ngang row "chu" stands for it.
		val chuKeys = jtui.wordKeySequence("chu")!!
		chuKeys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		var words = listedWords().map { it.lowercase() }
		assertThat(words).contains("chu")
		listOf("chú", "chù", "chủ", "chũ", "chụ").forEach { assertThat(words).doesNotContain(it) }

		// The tone keystroke then surfaces the toned word as a fully-typed candidate.
		val sacKey = jtui.wordKeySequence("chú")!![3]
		jtui.buttonPressed(pagePos[sacKey])
		jtui.forceUpdateUi()
		words = listedWords().map { it.lowercase() }
		assertThat(words).contains("chú")
	}

	@Test fun `TAV tone-established completions keep their marks (Cliff's hai-hao bug)`() {
		enableTav()
		// l-a-huyền typed ("là" fully entered): deeper words whose tone is ALREADY
		// typed (hài, hào — one letter remaining) are ordinary completions and must
		// display WITH their marks — never stripped to "hai"/"hao" (the FTEFTM
		// misclassification: keysRemaining==1 && len==seqLen also matches them).
		val keys = jtui.wordKeySequence("là")!!
		assertThat(keys).hasSize(3)
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		val words = listedWords().map { it.lowercase() }
		assertThat(words).contains("là")
		listOf("hai", "hao", "san", "sai", "lau", "hay").forEach {
			assertThat(words).doesNotContain(it)
		}
	}

	@Test fun `selecting a TAV synthetic row previews its string`() {
		enableTav()
		val keys = jtui.wordKeySequence("mứ")!!
		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		val words = listedWords().map { it.lowercase() }
		val target = words.indexOf("mư")
		assertThat(target).isAtLeast(0)
		repeat(target + 1) { jtui.buttonPressed(6) } // SELECT steps to the row
		jtui.forceUpdateUi()
		assertThat(h.lastSnapshot!!.selectedCandidateOutput?.lowercase()).isEqualTo("mư")
	}

	/** Cell 7 (bottom-center) of the given key's grid, by internal keyNum. */
	private fun toneCell(keyNum: Int): String = h.lastSnapshot!!.keyLabelGrids[pagePos[keyNum]][7]

	/** The TAV tone-form column (top-center, center, bottom-center) of the given key. */
	private fun toneColumn(keyNum: Int): List<String> {
		val g = h.lastSnapshot!!.keyLabelGrids[pagePos[keyNum]]
		return listOf(g[1], g[4], g[7])
	}

	@Test fun `TAV tone keys show the previous keystroke's vowel with tone applied`() {
		enableTav()
		// The display is the tone label in TAV, not a hint — it must not depend
		// on the next-letter hints toggle.
		jtui.showNextLetterHints = false
		val caKeys = jtui.wordKeySequence("cá")!! // c, a, sắc
		val sacKey = caKeys[2]
		val huyenKey = jtui.wordKeySequence("cà")!![2]
		caKeys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		// a is the only vowel on its key, and cá/cà are live — each tone key
		// shows exactly the carrier with its own mark, lowercase (position 1),
		// alone at the bottom of the center column.
		assertThat(toneColumn(sacKey)).containsExactly("", "", "á").inOrder()
		assertThat(toneColumn(huyenKey)).containsExactly("", "", "à").inOrder()
	}

	@Test fun `TAV forms fill bottom then top, sparing the center cell`() {
		enableTav()
		// c then the ô/u key: both carriers live (cố, cú) — two forms occupy
		// bottom + top with the CENTER cell empty (fill order 7/1/4: the center
		// sits next to mid-right slot labels and is used only when unavoidable),
		// ô-form above u-form per the key face.
		val coKeys = jtui.wordKeySequence("cố")!! // c, ô, sắc
		coKeys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(toneColumn(coKeys[2])).containsExactly("ố", "", "ú").inOrder()
		// Form-bearing keys are flagged so SquareButton can nudge a form that
		// sits left of a wide slot label ("60.", "15_") — and only there.
		assertThat(h.lastSnapshot!!.tavToneFormKeys).contains(coKeys[2])
	}

	@Test fun `TAV tone cells are blank at the root and after the tone is typed`() {
		enableTav()
		val caKeys = jtui.wordKeySequence("cá")!!
		val sacKey = caKeys[2]
		jtui.forceUpdateUi()
		// Root: no previous vowel, tone never valid — and the static tone label
		// must NOT render (TAE-only).
		assertThat(toneCell(sacKey)).isEmpty()
		// After the full toned word the syllable's one tone is spent: blank again.
		caKeys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(toneCell(sacKey)).isEmpty()
	}

	@Test fun `TAE keeps its static tone labels`() {
		val sacKey = jtui.wordKeySequence("cá")!!.last()
		jtui.forceUpdateUi()
		assertThat(toneCell(sacKey)).isNotEmpty()
	}

	@Test fun `TAV tone forms echo the typed carrier's case`() {
		enableTav()
		// ở = ơ + hỏi, carrier at word start: with SHIFT active for the keystroke
		// the displayed form must be the uppercase Ở the user is completing.
		val oKeys = jtui.wordKeySequence("ở")!! // ơ-key, hỏi
		jtui.setShiftState(true, isManual = true)
		jtui.buttonPressed(pagePos[oKeys[0]])
		jtui.forceUpdateUi()
		val column = toneColumn(oKeys[1]).joinToString("")
		assertThat(column).contains("Ở")
		assertThat(column).doesNotContain("ở")
	}

	@Test fun `TAV forms follow the Alphabetic layout's tone keys and letters`() {
		enableTav()
		jtui.layoutMode = LayoutMode.Alphabetical
		// Alphabetic key 0 carries a, ă, â (with b/c/d): after c,a every live
		// a-quality carrier shows on sắc's alpha key — a full column, key-face
		// order reading top to bottom.
		val caKeys = jtui.wordKeySequence("cá")!!
		caKeys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(toneColumn(caKeys[2])).containsExactly("á", "ắ", "ấ").inOrder()
		assertThat(h.lastSnapshot!!.tavToneFormKeys).contains(caKeys[2])
	}

	@Test fun `TAV next-letter hints stay valid after a mid-word tone key`() {
		enableTav()
		// "cái" = c,a,sắc,i: after the first three keys the letter i must be a
		// valid (black) next-letter hint — the tone key consumed a keystroke but
		// no character, so char indexing lags key indexing by one.
		val keys = jtui.wordKeySequence("cái")!!
		assertThat(keys).hasSize(4)
		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(h.lastSnapshot!!.nextLetterHints).contains('I')
	}

	@Test fun `TAV synthesizes an unmarked row when no ngang word exists`() {
		enableTav()
		// m-ư: mứ/mừ exist but "mư" is not a word — a synthetic confirmation row
		// still shows the unmarked spelling (selecting it outputs the string).
		val keys = jtui.wordKeySequence("mứ")!!
		assertThat(keys).hasSize(3) // m, ư, tone
		keys.dropLast(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		val words = listedWords().map { it.lowercase() }
		assertThat(words).contains("mư")
		listOf("mứ", "mừ").forEach { assertThat(words).doesNotContain(it) }
	}

	@Test fun `every fully-typed candidate appears even when the group exceeds eight`() {
		// The densest VN ambiguity group: 16 words share the exact key sequence of
		// "hoa" (corpus-derived). Every one must be selectable — an FTS missing from
		// the list is permanently unreachable (no further keystroke can surface it).
		val group = listOf(
			"hoa", "hề", "hò", "lò", "lề", "loa", "sò", "som",
			"èo", "soa", "hom", "lom", "yêm", "lêm", "hêm", "sề",
		)
		val keys = jtui.wordKeySequence("hoa")!!
		assertThat(keys).hasSize(3)
		group.forEach { assertThat(jtui.wordKeySequence(it)).isEqualTo(keys) }

		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()

		val words = listedWords().map { it.lowercase() }
		group.forEach { w -> assertThat(words).contains(w) }
	}
}
