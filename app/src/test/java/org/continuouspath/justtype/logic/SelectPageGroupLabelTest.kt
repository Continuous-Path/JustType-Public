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
 * Select-key page-group glyph (Cliff's icon, 2026-08-13): when the next
 * Select press opens or advances a page group, the key label carries the
 * "PageGroup1" sentinel (rendered as the fuzzy-words glyph by SquareButton)
 * instead of the page's unreadably small top word. Ordinary next-item
 * previews are unchanged, and speech still speaks the page-top word.
 */
@RunWith(RobolectricTestRunner::class)
class SelectPageGroupLabelTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root)
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "English")
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	private fun selectKeyLabel(): String = h.lastSnapshot!!.keyLabels.getOrNull(selectPos).orEmpty()

	private fun type(word: String, keyCount: Int = Int.MAX_VALUE) {
		jtui.wordKeySequence(word)!!.take(keyCount).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
	}

	@Test fun `word preview while stepping list rows, glyph when the next press opens the page`() {
		type("the")
		// No selection yet: next press selects row 0 — an ordinary word preview.
		assertThat(selectKeyLabel()).startsWith("SELECT\n")
		assertThat(selectKeyLabel()).doesNotContain("PageGroup1")
		jtui.buttonPressed(selectPos) // row 0
		jtui.forceUpdateUi()
		assertThat(selectKeyLabel()).doesNotContain("PageGroup1")
		jtui.buttonPressed(selectPos) // row 1 — next press opens page 0
		jtui.forceUpdateUi()
		assertThat(selectKeyLabel()).isEqualTo("SELECT\nPageGroup1")
	}

	@Test fun `glyph persists while paging as long as another page follows`() {
		type("the")
		repeat(3) { jtui.buttonPressed(selectPos) } // rows 0,1 then page 0 opens
		jtui.forceUpdateUi()
		// "the" at 3 keys has a deep candidate list — page 1 exists.
		assertThat(selectKeyLabel()).isEqualTo("SELECT\nPageGroup1")
	}
}
