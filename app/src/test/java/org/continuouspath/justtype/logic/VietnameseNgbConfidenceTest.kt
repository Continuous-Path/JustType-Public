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
 * NGB-D confidence signal end-to-end on the real TiengViet DB: per-keystroke
 * observations flow from the list build, the signal fires on a canonical
 * bigram, the enable toggle gates only the user-facing signal (learning and
 * the could-have-saved counter continue), and personalized weights persist
 * to the custom DB and reload.
 */
@RunWith(RobolectricTestRunner::class)
class VietnameseNgbConfidenceTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val repo get() = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { r ->
			r.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
			r.putBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, true)
			r.putInt(Constants.KEY_NGB_CONFIDENCE_THRESHOLD, 20)
			// Fixture wants the raw 20% threshold verbatim: pin adaptive theta off.
			r.putBoolean(org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_NGB_CONF_THETA_ADAPTIVE, false)
		}
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	/** Types [word]'s keys and selects the top entry; the following word's
	 *  first [nextKeyCount] keys commit it (AK-after-SEL path). Two keys
	 *  minimum: single-key searches stay deferred in the harness. */
	private fun commitThenStart(word: String, next: String, nextKeyCount: Int = 2): List<Int> {
		val keys = jtui.wordKeySequence(word)!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		val nextKeys = jtui.wordKeySequence(next)!!
		nextKeys.take(nextKeyCount).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		return nextKeys
	}

	@Test fun `observations flow and the signal fires on a canonical bigram`() {
		// chúc -> mừng: after two of mừng's keys the prediction top is mừng
		// with a dominant posterior; at threshold 20% the signal must fire.
		commitThenStart("chúc", "mừng")
		val obs = jtui.ngbConfLastObservationForTest
		assertThat(obs).isNotNull()
		assertThat(obs!!.second).isEqualTo(2)
		assertThat(obs.first).isGreaterThan(0.0)
		assertThat(h.confidenceSignals).isGreaterThan(0)
	}

	@Test fun `enable toggle gates the signal but not the observations`() {
		repo.putBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, false)
		commitThenStart("chúc", "mừng")
		assertThat(h.confidenceSignals).isEqualTo(0)
		// Learning still observes the state (off = hidden, not disabled).
		assertThat(jtui.ngbConfLastObservationForTest).isNotNull()
	}

	@Test fun `could-have-saved keystrokes accumulate while the signal is off`() {
		repo.putBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, false)
		// Type mừng to completion after chúc, then commit it via the next
		// word's keys: the top was mừng from 2 keys on, so the tail of its
		// sequence was avoidable — the counter must record those keystrokes.
		val mungKeys = commitThenStart("chúc", "mừng")
		mungKeys.drop(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		val nextKeys = jtui.wordKeySequence("năm")!!
		nextKeys.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.ngbConfFlushForTest()
		assertThat(repo.getLong(Constants.KEY_NGB_CONF_SAVED_KEYS, 0L)).isGreaterThan(0L)
	}

	@Test fun `personalized weights persist to the custom DB and reload`() {
		commitThenStart("chúc", "mừng")
		val mungKeys = jtui.wordKeySequence("mừng")!!
		mungKeys.drop(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.buttonPressed(selectPos)
		jtui.wordKeySequence("năm")!!.take(2).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.ngbConfFlushForTest()
		val learned = jtui.ngbConfWeightsForTest()
		// SGD ran on real labels: at least one weight moved off its default.
		val defaults = NgbConfidence.FEATURE_KEYS
			.mapIndexed { i, k -> k to NgbConfidence.DEFAULT_WEIGHTS[i] }.toMap()
		assertThat(learned).isNotEqualTo(defaults)
		// Reload from the custom DB: the persisted weights come back.
		jtui.init()
		assertThat(jtui.ngbConfWeightsForTest()).isEqualTo(learned)
	}
}
