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
 * Spanish NGB end-to-end on the built EspanolDb (coverage-first K400 table
 * from HPLT spa shards 1/3/4, the first table built WITH the BOS row —
 * sls.md "BOS prediction row"): word-based traits, context prediction, and
 * the sentence-start follower window.
 */
@RunWith(RobolectricTestRunner::class)
class EspanolNgbTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root)
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "Espanol")
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	@Test fun `spanish DB ships NGB context data with word-based traits`() {
		assertThat(jtui.ngbActiveForTest()).isTrue()
		assertThat(jtui.ngbTraitsForTest()).isEqualTo(LanguageTraits.WORD_BASED)
	}

	@Test fun `committing a word opens the follower window`() {
		val keys = jtui.wordKeySequence("para")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("el")!!.take(1).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		// "para el / para que / para la..." — the pool must be alive.
		assertThat(jtui.ngbPoolDisplaysForTest()).isNotEmpty()
	}

	@Test fun `BOS context serves the sentence-opener row`() {
		// After sentence-final punctuation the reserved "\n" row serves the
		// opener distribution: el/la/en/y/no lead the corpus counts.
		jtui.ngbReconstructContext("Hola amigo. ")
		assertThat(jtui.ngbContextForTest()).isEqualTo(JTUI.NGB_BOS_CTX to true)
		val pool = jtui.ngbPoolDisplaysForTest()
		assertThat(pool).isNotEmpty()
		assertThat(pool.take(8).map { it.lowercase() }).contains("el")
	}

	@Test fun `empty field serves the BOS row too`() {
		jtui.ngbReconstructContext("")
		assertThat(jtui.ngbPoolDisplaysForTest()).isNotEmpty()
	}

	@Test fun `sentence openers personalize the BOS row through ngb_user`() {
		jtui.ngbReconstructContext("Hola amigo. ")
		// Type a word at the BOS state and commit it via Select + AK.
		val keys = jtui.wordKeySequence("gracias")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("por")!!.take(1).forEach { jtui.buttonPressed(pagePos[it]) }
		val learned = jtui.ngbUserRowsForTest(JTUI.NGB_BOS_CTX)
		assertThat(learned.map { it.first }).contains("gracias")
	}
}
