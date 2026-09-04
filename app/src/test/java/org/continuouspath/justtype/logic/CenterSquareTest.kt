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
 * Center-square live surface (sls.md "Center-square surface", Cliff
 * 2026-08-13): on the Main page the center square mirrors the text that is
 * provisionally committed at the insertion point, and the snapshot state
 * drives the visual treatment — EMPTY in the resting states (the zero-K
 * menu at BOS / after a page pick, an open page display), NEUTRAL while
 * typing, SIGNAL when the confidence signal fires for that word, ARMED
 * while a Select activation holds a provisional commit.
 */
@RunWith(RobolectricTestRunner::class)
class CenterSquareTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { r ->
			r.putBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, true)
			r.putInt(Constants.KEY_NGB_CONFIDENCE_THRESHOLD, 20)
			// Fixture pins the raw 20% threshold: adaptive placement off.
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

	private fun type(word: String, keyCount: Int = Int.MAX_VALUE) {
		jtui.wordKeySequence(word)!!.take(keyCount).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
	}

	private fun snapshot() = h.lastSnapshot!!

	@Test fun `at rest the center square is empty - no Main label`() {
		jtui.forceUpdateUi()
		assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.EMPTY)
		assertThat(snapshot().centerSpace).isEmpty()
	}

	@Test fun `typing mirrors the top candidate as the provisional word`() {
		type("the")
		assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.NEUTRAL)
		assertThat(snapshot().centerSpace).isEqualTo(snapshot().topCandidateOutput)
		assertThat(snapshot().centerSpace.lowercase()).isEqualTo("the")
	}

	@Test fun `select arms the square with the provisionally committed word`() {
		type("the")
		jtui.buttonPressed(selectPos)
		jtui.forceUpdateUi()
		assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.ARMED)
		assertThat(snapshot().centerSpace.lowercase()).isEqualTo("the")
	}

	@Test fun `stepping the selection keeps the square armed on the new word`() {
		type("the")
		repeat(2) { jtui.buttonPressed(selectPos) }
		jtui.forceUpdateUi()
		val sel = snapshot().currentSelectionIndex
		if (sel != null) { // still in list rows (not yet a page display)
			assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.ARMED)
			assertThat(snapshot().centerSpace).isNotEmpty()
		}
	}

	@Test fun `page display empties the square while the field keeps the prior word`() {
		// Page-menu spec (2026-08-13): opening a page leaves the previously
		// selected row provisionally committed IN THE FIELD (no likelihood
		// claim for the page's first cell) — but the square goes EMPTY as the
		// page-mode signal: stepping into a page declares intent to move off
		// that word, and showing it green would read as a threat to force it.
		type("the")
		repeat(2) { jtui.buttonPressed(selectPos) }
		jtui.forceUpdateUi()
		val armedWord = snapshot().centerSpace
		assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.ARMED)
		jtui.buttonPressed(selectPos) // opens page 0
		jtui.forceUpdateUi()
		assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.EMPTY)
		assertThat(snapshot().centerSpace).isEmpty()
		// The insertion point still holds the prior word (page = menu).
		assertThat(snapshot().selectedCandidateOutput).isEqualTo(armedWord)
	}

	@Test fun `zero-K follower menu after a commit stays empty until typing resumes`() {
		// Commit "of" (Select + AK-after-SEL on the next word's first key).
		// The follower window is a MENU and the single-key state defers the
		// search to placeholders — no provisional text either way, so the
		// square stays EMPTY (exactly when composing is absent)…
		type("of")
		jtui.buttonPressed(selectPos)
		type("the", keyCount = 1)
		assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.EMPTY)
		assertThat(snapshot().centerSpace).isEmpty()
		// …and goes live with the second keystroke, when composing starts.
		jtui.wordKeySequence("the")!!.drop(1).take(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(snapshot().centerSquareState).isNotEqualTo(CenterSquareState.EMPTY)
		assertThat(snapshot().centerSpace).isNotEmpty()
	}

	@Test fun `confidence fire escalates the square to SIGNAL on the vouched word`() {
		// of -> the: the strongest bigram; at threshold 20 the signal fires
		// (the EnglishNgbTest confidence fixture). The square must carry the
		// SIGNAL state and show the exact word the signal vouches for.
		type("of")
		jtui.buttonPressed(selectPos)
		type("the", keyCount = 2)
		assertThat(h.confidenceSignals).isGreaterThan(0)
		assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.SIGNAL)
		assertThat(snapshot().centerSpace.lowercase()).isEqualTo("the")
	}

	@Test fun `non-main pages keep their page name in the square`() {
		jtui.setCurrentPage("Symbols")
		jtui.forceUpdateUi()
		assertThat(snapshot().centerSquareState).isEqualTo(CenterSquareState.EMPTY)
		assertThat(snapshot().centerSpace).isNotEmpty()
	}
}
