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

/**
 * Word-list-style modes (sls.md, Cliff 2026-08-12): the deterministic
 * classic-vs-prediction balance that replaced adaptive Mechanism A.
 * Classic = no context-conditioned content anywhere; steady styles pin
 * the head slots to the classic order above the prediction block.
 */
@RunWith(RobolectricTestRunner::class)
class WordListStyleTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val repo get() = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { r ->
			r.putBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, true)
			r.putInt(Constants.KEY_NGB_CONFIDENCE_THRESHOLD, 20)
			r.putBoolean(DeveloperSettingsActivity.KEY_NGB_CONF_THETA_ADAPTIVE, false)
		}
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "English")
		// Row tags ([N] = prediction-block row) make the assertions
		// structural rather than word-pinned; list mode for the buffer.
		repo.putBoolean(DeveloperSettingsActivity.KEY_SHOW_SORT_METRIC, true)
		repo.putInt(DeveloperSettingsActivity.KEY_SORT_METRIC_VERBOSITY, 1)
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	/** Fresh word: commit "of", type two keys of "the", return the list
	 *  buffer lines (with sortmetric tags). */
	private fun listAfterOfThe(): List<String> {
		jtui.resetJTUI(false, false)
		val keys = jtui.wordKeySequence("of")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("the")!!.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		return h.lastSnapshot!!.selectionListBuffers
			.joinToString("\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
	}

	private fun setStyle(style: String) = repo.putString(Constants.KEY_WORD_LIST_STYLE, style)

	@Test fun `classic derives predictions-off and steady styles keep them on`() {
		setStyle(Constants.WORD_LIST_STYLE_CLASSIC)
		assertThat(repo.getBoolean(Constants.KEY_NGB_PREDICTIONS, true)).isFalse()
		setStyle(Constants.WORD_LIST_STYLE_STEADY2)
		assertThat(repo.getBoolean(Constants.KEY_NGB_PREDICTIONS, false)).isTrue()
		setStyle(Constants.WORD_LIST_STYLE_PREDICTIVE)
		assertThat(repo.getBoolean(Constants.KEY_NGB_PREDICTIONS, false)).isTrue()
	}

	@Test fun `predictive leads with the block - steady styles anchor classic rows above it`() {
		val predictive = listAfterOfThe()
		assertThat(predictive.first()).contains("[N") // of->the block leads

		setStyle(Constants.WORD_LIST_STYLE_STEADY1)
		val steady1 = listAfterOfThe()
		assertThat(steady1.first()).doesNotContain("[N") // slot 1 = classic row
		assertThat(steady1.drop(1).any { it.contains("[N") }).isTrue() // block follows

		setStyle(Constants.WORD_LIST_STYLE_STEADY2)
		val steady2 = listAfterOfThe()
		assertThat(steady2[0]).doesNotContain("[N")
		assertThat(steady2[1]).doesNotContain("[N")
		assertThat(steady2.drop(2).any { it.contains("[N") }).isTrue()
		// The steady anchors ARE the classic head: same rows, same order.
		setStyle(Constants.WORD_LIST_STYLE_CLASSIC)
		val classic = listAfterOfThe()
		assertThat(steady2.take(2).map { it.substringBefore(" [") })
			.isEqualTo(classic.take(2).map { it.substringBefore(" [") })
	}

	@Test fun `classic silences every context-conditioned mechanism`() {
		setStyle(Constants.WORD_LIST_STYLE_CLASSIC)
		jtui.init() // re-derive language stack under the new style
		assertThat(jtui.ngbActiveForTest()).isFalse()
		// Known sentence start serves an EMPTY pool (no BOS row consulted).
		jtui.ngbReconstructContext("We got home. ")
		assertThat(jtui.ngbPoolDisplaysForTest()).isEmpty()
		// The list itself still works, trie-only, and carries no block rows.
		val words = listAfterOfThe()
		assertThat(words).isNotEmpty()
		assertThat(words.none { it.contains("[N") }).isTrue()
		// The confidence signal never fires without the prediction stack.
		assertThat(h.confidenceSignals).isEqualTo(0)
	}

	@Test fun `steady anchors put fully-typed rows before partial ones`() {
		setStyle(Constants.WORD_LIST_STYLE_STEADY2)
		// would + 1 key of "i": "I" is fully typed at one key — under the
		// classic order it must be the first anchor even though the block's
		// higher-eff completions would otherwise lead.
		jtui.resetJTUI(false, false)
		val keys = jtui.wordKeySequence("would")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("i")!!.take(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		val words = h.lastSnapshot!!.selectionListBuffers
			.joinToString("\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
		assertThat(words.first().substringBefore(" [")).isEqualTo("I")
	}
}
