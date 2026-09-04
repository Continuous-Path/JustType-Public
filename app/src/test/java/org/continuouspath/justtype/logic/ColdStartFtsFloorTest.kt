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
 * Cold-start FTS floor (Cliff's "a below and / to below the" first-run
 * report, 2026-08-12): a never-used top-band fully-typed word is never
 * outranked by an incomplete row — not by a just-used word's recency edge,
 * not by the within-band frequency extension. Retires per word at first use.
 */
@RunWith(RobolectricTestRunner::class)
class ColdStartFtsFloorTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { }
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "English")
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	private fun listedWords(): List<String> = h.lastSnapshot!!.selectionListBuffers
		.joinToString("\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

	/** BOS state, two keys of "to": the strongest opener ("the", kr 1) vs the
	 *  fully-typed "to" — the extension-vs-completeness battleground. */
	private fun typeToAtBos(): List<String> {
		jtui.resetJTUI(false, false)
		jtui.ngbReconstructContext("We got home. ")
		jtui.wordKeySequence("to")!!.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		return listedWords()
	}

	@Test fun `cold fully-typed top-band word heads the list over stronger incomplete rows`() {
		// Without the floor, BOS "the" (2.3M opener) outranked fully-typed
		// "to" by ~4.7 extension points against a 1-point seq advantage.
		assertThat(typeToAtBos().first().lowercase()).isEqualTo("to")
	}

	@Test fun `the floor retires at the word's first real use`() {
		// Commit "to" once: its cold floor is gone (the harness clock is
		// frozen at 0, so no recency advantage masks the retirement — the
		// extension battle resumes and the stronger opener wins again).
		jtui.resetJTUI(false, false)
		jtui.ngbReconstructContext("We got home. ")
		jtui.wordKeySequence("to")!!.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("be")!!.take(1).forEach { jtui.buttonPressed(pagePos[it]) }
		assertThat(jtui.wordUseCountForTest("to")).isEqualTo(1)
		assertThat(typeToAtBos().first().lowercase()).isEqualTo("the")
	}

	@Test fun `null-context trie list keeps the fully-typed word first`() {
		jtui.resetJTUI(false, false)
		jtui.ngbReconstructContext(null)
		jtui.wordKeySequence("a")!!.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		assertThat(listedWords().first().lowercase()).isEqualTo("a")
	}
}
