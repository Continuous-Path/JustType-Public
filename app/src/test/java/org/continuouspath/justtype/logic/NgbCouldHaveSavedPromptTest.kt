package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Could-have-saved prompt gating: while the confidence signal is OFF, crossing
 * the 300-key milestone prompts exactly once per crossing and records the
 * prompted total; with the signal ON the prompt never fires.
 */
@RunWith(RobolectricTestRunner::class)
class NgbCouldHaveSavedPromptTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val repo get() = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())

	private fun start(confidenceEnabled: Boolean) {
		h = TestJtui(tmpDir.root) { r ->
			r.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
			r.putBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, confidenceEnabled)
			r.putInt(Constants.KEY_NGB_CONFIDENCE_THRESHOLD, 20)
			// One saved-keystroke commit away from the 300-key milestone.
			r.putLong(Constants.KEY_NGB_CONF_SAVED_KEYS, 299L)
		}
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	/** chúc committed, mừng typed to completion and committed: the tail of
	 *  mừng's sequence was avoidable — saved keystrokes accrue. */
	private fun driveSavedKeystrokes() {
		jtui.wordKeySequence("chúc")!!.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("mừng")!!.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("năm")!!.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
	}

	@Test fun `crossing the milestone prompts once per flush`() {
		start(confidenceEnabled = false)
		driveSavedKeystrokes()
		jtui.ngbConfFlushForTest()
		val total = repo.getLong(Constants.KEY_NGB_CONF_SAVED_KEYS, 0L)
		assertThat(total).isAtLeast(300L)
		assertThat(h.couldHaveSavedPrompts).containsExactly(total)
		assertThat(repo.getLong(Constants.KEY_NGB_CONF_PROMPTED_SAVED, 0L)).isEqualTo(total)

		// Nothing new pending: a second flush must not re-prompt.
		jtui.ngbConfFlushForTest()
		assertThat(h.couldHaveSavedPrompts).hasSize(1)
	}

	@Test fun `no prompt while the confidence signal is enabled`() {
		start(confidenceEnabled = true)
		driveSavedKeystrokes()
		jtui.ngbConfFlushForTest()
		assertThat(h.couldHaveSavedPrompts).isEmpty()
	}
}
