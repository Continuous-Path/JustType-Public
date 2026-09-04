package org.continuouspath.justtype.activity

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SHOW_REGION_BORDER
import org.continuouspath.justtype.Constants.KEY_TOUCH_OVERLAY_TIMEOUT_SEC
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getInt

class SetupDirectionalSelectionActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	private lateinit var repo: SettingsRepository

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_setup_directional_selection)

		repo = SettingsRepository.get()

		val backButton: ImageButton = findViewById(R.id.backButton)
		backButton.setOnClickListener { finish() }

		// Swipe distance slider (2-20% of screen width, step 1%)
		val swipeDistanceSeek: SeekBar = findViewById(R.id.swipeDistanceSeek)
		val swipeDistanceValue: TextView = findViewById(R.id.swipeDistanceValue)
		val swipeDistanceIndicator: View = findViewById(R.id.swipeDistanceIndicator)

		val savedSwipePercent = repo.getInt(KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT).coerceIn(2, 20)
		swipeDistanceSeek.max = 18 // (20 - 2) = 18 steps
		swipeDistanceSeek.progress = (savedSwipePercent - 2).coerceIn(0, 18)
		swipeDistanceValue.text = getString(R.string.format_percent, savedSwipePercent)

		// Update visual indicator width to show actual swipe distance. The threshold is
		// percent of the SHORT display edge (orientation-invariant, matching
		// TouchDetectionOverlay), so preview the same physical length here.
		val updateIndicatorWidth: (Int) -> Unit = { percent ->
			swipeDistanceIndicator.post {
				val m = resources.displayMetrics
				val parentWidth =
					(swipeDistanceIndicator.parent as? android.view.View)?.width
						?: m.widthPixels
				val shortEdge = minOf(m.widthPixels, m.heightPixels)
				val indicatorWidth = (shortEdge * percent / 100f).toInt().coerceIn(4, parentWidth)
				val lp = swipeDistanceIndicator.layoutParams
				lp.width = indicatorWidth
				swipeDistanceIndicator.layoutParams = lp
			}
		}
		updateIndicatorWidth(savedSwipePercent)

		swipeDistanceSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = progress + 2
					swipeDistanceValue.text = getString(R.string.format_percent, value)
					updateIndicatorWidth(value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = swipeDistanceSeek.progress + 2
					repo.putInt(KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT, value)
				}
			},
		)

		val borderSwitch: SwitchCompat = findViewById(R.id.showRegionBorderSwitch)
		borderSwitch.isChecked = repo.getBoolean(KEY_DIRECTIONAL_SHOW_REGION_BORDER, false)
		borderSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_DIRECTIONAL_SHOW_REGION_BORDER, isChecked)
		}

		// Touch timeout slider (2-10 seconds)
		val touchTimeoutSeek: SeekBar = findViewById(R.id.touchTimeoutSeek)
		val touchTimeoutValue: TextView = findViewById(R.id.touchTimeoutValue)

		val savedTimeout = repo.getInt(KEY_TOUCH_OVERLAY_TIMEOUT_SEC).coerceIn(2, 10)
		touchTimeoutSeek.max = 8 // 2..10 => range 8
		touchTimeoutSeek.progress = (savedTimeout - 2).coerceIn(0, 8)
		touchTimeoutValue.text = getString(R.string.format_timeout_sec, savedTimeout)

		touchTimeoutSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = progress + 2
					touchTimeoutValue.text = getString(R.string.format_timeout_sec, value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = (touchTimeoutSeek.progress + 2).coerceIn(2, 10)
					repo.putInt(KEY_TOUCH_OVERLAY_TIMEOUT_SEC, value)
				}
			},
		)

		// Debounce slider (0-500ms, step 10ms)
		// 0 = disabled (OFF), 10-500 = active
		val debounceSeek: SeekBar = findViewById(R.id.debounceSeek)
		val debounceValue: TextView = findViewById(R.id.debounceValue)

		val savedDebounce = repo.getInt(KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS).coerceIn(0, 500)
		debounceSeek.max = 50 // 0..500 in steps of 10
		debounceSeek.progress = (savedDebounce / 10).coerceIn(0, 50)
		debounceValue.text = formatDebounce(savedDebounce)

		debounceSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = progress * 10
					debounceValue.text = formatDebounce(value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = debounceSeek.progress * 10
					repo.putInt(KEY_DIRECTIONAL_SELECTION_DEBOUNCE_MS, value)
				}
			},
		)

		// Phase 3B: attach INFO PROMPT icons.
		val root: ViewGroup = findViewById(android.R.id.content)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_dir_swipe_sensitivity,
			R.string.info_prompt_dirsel_swipe_sensitivity,
		)
		// Phase 3D info-icon sweep: previously-missing Debounce row.
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_dir_swipe_debounce,
			R.string.info_prompt_dirsel_debounce,
		)
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}

	private fun formatDebounce(ms: Int): String = if (ms <= 0) getString(R.string.format_debounce_off) else getString(R.string.format_debounce_ms, ms)
}
