package org.continuouspath.justtype.activity

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DEADZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXITZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXIT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_KEY_ACT_THRESHOLD
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_PITCH_SCALE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_RESPONSE_CURVE
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Tests for [SetupHeadTrackingFragment].
 *
 * Mirrors [SetupJoystickFragmentTest] — slider config screen with no key-event
 * handling. Verifies the fragment reads the stored head-tracking prefs into
 * its sliders.
 */
@RunWith(RobolectricTestRunner::class)
class SetupHeadTrackingFragmentTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private val controllers = mutableListOf<ActivityController<SetupHostActivity>>()

	@Before
	fun setUp() {
		SettingsRegistry.getInstance(ApplicationProvider.getApplicationContext())
	}

	@After
	fun tearDown() {
		controllers.forEach { it.pause().stop().destroy() }
		controllers.clear()
		SettingsRepository.resetInstanceForTesting()
	}

	@Test
	fun `slider initial values reflect stored prefs`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		val repo = SettingsRepository.getInstance(ctx)
		repo.putFloat(KEY_HEADTRACKING_CORNER_BIAS, 1.0f)
		repo.putFloat(KEY_HEADTRACKING_PITCH_SCALE, 1.2f)
		repo.putFloat(KEY_HEADTRACKING_RESPONSE_CURVE, 0.8f)
		repo.putFloat(KEY_HEADTRACKING_DEADZONE, 0.20f)
		repo.putFloat(KEY_HEADTRACKING_ACTIVEZONE, 0.50f)
		repo.putFloat(KEY_HEADTRACKING_EXITZONE, 0.85f)
		repo.putInt(KEY_HEADTRACKING_KEY_ACT_THRESHOLD, 25)
		repo.putInt(KEY_HEADTRACKING_EXIT_DELAY_MS, 4000)

		val view = launchHostAndGetFragmentView()

		assertThat(view.findViewById<TextView>(R.id.cornerBiasValue).text.toString().toFloat())
			.isWithin(0.01f).of(1.0f)
		assertThat(view.findViewById<TextView>(R.id.pitchScaleValue).text.toString().toFloat())
			.isWithin(0.01f).of(1.2f)
		assertThat(view.findViewById<TextView>(R.id.responseCurveValue).text.toString().toFloat())
			.isWithin(0.01f).of(0.8f)
		assertThat(view.findViewById<TextView>(R.id.headTrackingDeadZoneValue).text.toString().toFloat())
			.isWithin(0.01f).of(0.20f)
		assertThat(view.findViewById<TextView>(R.id.headTrackingActiveZoneValue).text.toString().toFloat())
			.isWithin(0.01f).of(0.50f)
		assertThat(view.findViewById<TextView>(R.id.headTrackingExitZoneValue).text.toString().toFloat())
			.isWithin(0.01f).of(0.85f)
		// Threshold label uses the format_percent string ("25%"), so trim the % suffix.
		assertThat(
			view.findViewById<TextView>(R.id.headTrackingKeyActThresholdValue)
				.text.toString().trimEnd('%').trim().toInt(),
		).isEqualTo(25)
	}

	// ── helpers ───────────────────────────────────────────────────────────

	private fun launchHost(): SetupHostActivity {
		val intent = SetupHostActivity.intent(
			ApplicationProvider.getApplicationContext(),
			SetupHostActivity.TARGET_HEAD_TRACKING,
		)
		val controller = Robolectric.buildActivity(SetupHostActivity::class.java, intent)
			.create()
			.start()
			.resume()
		controllers += controller
		val activity = controller.get()
		activity.supportFragmentManager.executePendingTransactions()
		return activity
	}

	private fun launchHostAndGetFragmentView(): View = launchHost().supportFragmentManager
		.findFragmentById(R.id.setup_host_container)
		?.view!!
}
