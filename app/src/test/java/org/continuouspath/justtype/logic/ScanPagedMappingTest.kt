package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.ScanState
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Single-switch scan layout page mechanics (Cliff, 2026-08-13): page-group
 * entries hand out in SCAN order — the first ambiguous key the scan reaches
 * after Select carries the most likely entry — and the scan selection list
 * renders groups ROW-major (two lines of three, ranks 1-3 then 4-6),
 * mirroring the two key rows the scan sweeps. The JT grid keeps its
 * column-major mapping (pinned by the SelectBehavior/PagedSelect suites).
 */
@RunWith(RobolectricTestRunner::class)
class ScanPagedMappingTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { r ->
			r.putString(Constants.KEY_INPUT_METHOD_PRIMARY, Constants.INPUT_METHOD_SINGLE_SWITCH)
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

	private fun openPage() {
		type("the")
		repeat(3) { jtui.buttonPressed(selectPos) } // rows 0,1, then page 0
		jtui.forceUpdateUi()
	}

	private fun pageWords(): List<String> = jtui.selectionListForTest()
		.drop(2) // two list rows
		.take(6)
		.map { (it["display"] as? String).orEmpty() }

	@Test fun `key faces hand out page entries in scan order - most likely first`() {
		openPage()
		val words = pageWords()
		val grids = h.lastSnapshot!!.keyLabelGrids
		// Scan sequence after Select: buttons 7, 0, 3, 2, 5, 4 -> ranks 1..6.
		val ambigButtonsInScanOrder = ScanState.SCAN_ORDER_OPTIMIZED
			.filter { it != ScanState.SELECT_KEY_INDEX && it != ScanState.UNDO_KEY_INDEX }
		assertThat(ambigButtonsInScanOrder).isEqualTo(listOf(7, 0, 3, 2, 5, 4))
		ambigButtonsInScanOrder.forEachIndexed { rank, btn ->
			assertThat(grids[btn][4]).isEqualTo(words[rank])
		}
	}

	@Test fun `picking the first-scanned key commits the top-ranked entry`() {
		openPage()
		val topWord = pageWords().first().lowercase()
		val before = jtui.wordUseCountForTest(topWord)
		jtui.buttonPressed(7) // first ambiguous key in the scan sequence
		// The paged pick is a FINAL commit: usage lands on the picked word.
		assertThat(jtui.wordUseCountForTest(topWord)).isGreaterThan(before)
	}

	@Test fun `scan page groups render as two rows of three`() {
		openPage()
		val words = pageWords()
		val buffer = h.lastSnapshot!!.selectionListBuffers.first().toString()
		// Row-major: ranks 1-3 tab-joined on one line, 4-6 on the next.
		assertThat(buffer).contains(words.take(3).joinToString("\t"))
		assertThat(buffer).contains(words.drop(3).take(3).joinToString("\t"))
	}
}
