package org.continuouspath.justtype.welcome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository

/**
 * Multi-step welcome guide. Host activity owns step state + nav-bar wiring;
 * each step is a [WelcomeStepFragment] that renders its own body.
 *
 * Steps live in [STEPS] — order is presentation order. Add a step by
 * appending its fragment-supplier here.
 *
 * Marks [Constants.KEY_WELCOME_GUIDE_SEEN] true on finish or skip so the
 * first-launch gate doesn't re-trigger.
 */
class WelcomeGuideActivity : AppCompatActivity() {

	private lateinit var prefs: SettingsRepository
	private lateinit var stepIndicator: TextView
	private lateinit var backButton: Button
	private lateinit var nextButton: Button
	private lateinit var skipButton: Button

	private var currentStep: Int = 0

	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_welcome_guide)

		prefs = SettingsRepository.getInstance(applicationContext)

		findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
		stepIndicator = findViewById(R.id.stepIndicator)
		backButton = findViewById(R.id.welcomeBackButton)
		nextButton = findViewById(R.id.welcomeNextButton)
		skipButton = findViewById(R.id.welcomeSkipButton)

		backButton.setOnClickListener { goToStep(currentStep - 1) }
		nextButton.setOnClickListener { advance() }
		skipButton.setOnClickListener { finishGuide() }

		currentStep = savedInstanceState?.getInt(STATE_CURRENT_STEP) ?: 0
		showStep(currentStep)
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putInt(STATE_CURRENT_STEP, currentStep)
	}

	private fun advance() {
		if (currentStep >= STEPS.lastIndex) {
			finishGuide()
		} else {
			goToStep(currentStep + 1)
		}
	}

	private fun goToStep(index: Int) {
		if (index !in STEPS.indices) return
		currentStep = index
		showStep(index)
	}

	private fun showStep(index: Int) {
		val fragment = STEPS[index].invoke()
		supportFragmentManager.beginTransaction()
			.replace(R.id.welcomeGuideContainer, fragment)
			.commit()

		stepIndicator.text = getString(
			R.string.welcome_guide_step_indicator,
			index + 1,
			STEPS.size,
		)
		backButton.isEnabled = index > 0
		val isLast = index == STEPS.lastIndex
		nextButton.text = getString(
			if (isLast) R.string.welcome_guide_finish else R.string.welcome_guide_next,
		)
		skipButton.visibility = if (isLast) android.view.View.GONE else android.view.View.VISIBLE
		// Give D-pad / switch users a clear default focus on each step.
		nextButton.post { nextButton.requestFocus() }
	}

	private fun finishGuide() {
		prefs.putBoolean(Constants.KEY_WELCOME_GUIDE_SEEN, true)
		finish()
	}

	companion object {
		private const val STATE_CURRENT_STEP = "welcome_guide_current_step"

		private val STEPS: List<() -> Fragment> = listOf(
			::WelcomeStepIntroFragment,
			::WelcomeStepEnableImeFragment,
			::WelcomeStepDoneFragment,
		)

		fun intent(context: Context): Intent = Intent(context, WelcomeGuideActivity::class.java)
	}
}
