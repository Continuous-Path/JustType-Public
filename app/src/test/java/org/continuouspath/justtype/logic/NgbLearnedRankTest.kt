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
 * NGB learned tier end-to-end on the real TiengViet DB: transitions learned
 * through the real commit paths re-rank the pool on the next visit to the
 * same context, and multi-syllable learned targets round-trip the
 * space-joined storage the engine splits back at fetch.
 */
@RunWith(RobolectricTestRunner::class)
class NgbLearnedRankTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { repo ->
			repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
		}
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
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

	private fun wordIndex(word: String): Int {
		jtui.forceUpdateUi()
		return listedWords().indexOfFirst { it.equals(word, ignoreCase = true) }
	}

	/** Types [word] fully and steps SELECT onto its entry; the next word's
	 *  first key commits it (AK-after-SEL). */
	private fun typeAndSelect(word: String) {
		jtui.wordKeySequence(word)!!.forEach { jtui.buttonPressed(pagePos[it]) }
		val idx = wordIndex(word)
		assertThat(idx).isAtLeast(0)
		repeat(idx + 1) { jtui.buttonPressed(selectPos) }
	}

	/** Commits [word], then types the first two keys of [next]. */
	private fun commitThenStart(word: String, next: String) {
		typeAndSelect(word)
		jtui.wordKeySequence(next)!!.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
	}

	@Test fun `repeated commits lift a weak follower to the top of its block`() {
		// thầy sits in chúc's block behind thọ under the v12 table (higher
		// eff but more keys remaining — the unified ordering favors thọ), so
		// it starts listed-but-not-top. (Was thọ — v12 promoted it to the
		// static head; thư fell out of the visible list entirely. Table-drift
		// re-pin, 2026-08-13.)
		commitThenStart("chúc", "thầy")
		assertThat(wordIndex("thầy")).isGreaterThan(0)
		// Finish and commit thầy, then repeat the bigram through real commits.
		jtui.wordKeySequence("thầy")!!.drop(2).forEach { jtui.buttonPressed(pagePos[it]) }
		val idx = wordIndex("thầy")
		assertThat(idx).isAtLeast(0)
		repeat(idx + 1) { jtui.buttonPressed(selectPos) }
		repeat(2) {
			typeAndSelect("chúc")
			typeAndSelect("thầy")
		}
		jtui.buttonPressed(pagePos[0]) // AK commits the final thầy
		jtui.resetJTUI(false, false) // run ends: recognizer flushes
		assertThat(jtui.ngbUserRowsForTest("chúc")).contains("thầy" to 3)

		commitThenStart("chúc", "thầy")
		assertThat(wordIndex("thầy")).isEqualTo(0)
	}

	@Test fun `learned multi-syllable target round-trips the space-joined storage`() {
		typeAndSelect("chúc")
		typeAndSelect("sức")
		typeAndSelect("khỏe")
		jtui.buttonPressed(pagePos[0]) // AK commits khỏe, starts a throwaway word
		jtui.resetJTUI(false, false)
		// The recognizer merged sức+khỏe on the unit basis: one space-joined
		// target for chúc, no interim sức transition.
		assertThat(jtui.ngbUserRowsForTest("chúc")).contains("sức khỏe" to 1)
		assertThat(jtui.ngbUserRowsForTest("sức")).isEmpty()

		// The stored form splits back into the pool: the unit tops its block.
		commitThenStart("chúc", "sức")
		assertThat(wordIndex("sức khỏe")).isEqualTo(0)
	}
}
