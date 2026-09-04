package org.continuouspath.justtype.activity

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.continuouspath.justtype.Constants.KEY_DIRECT_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_DIRECT_AUTOREPEAT_MODE
import org.continuouspath.justtype.Constants.KEY_DIRECT_SELECTION_DEBOUNCE_MS
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt

class SetupDirectSelectionActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	private lateinit var repo: SettingsRepository

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_setup_direct_selection)

		repo = SettingsRepository.getInstance(this)

		val backButton: ImageButton = findViewById(R.id.backButton)
		backButton.setOnClickListener { finish() }

		// Debounce slider (0-500ms, step 10ms)
		// 0 = disabled (OFF), 10-500 = active
		val debounceSeek: SeekBar = findViewById(R.id.debounceSeek)
		val debounceValue: TextView = findViewById(R.id.debounceValue)

		val savedDebounce = repo.getInt(KEY_DIRECT_SELECTION_DEBOUNCE_MS).coerceIn(0, 500)
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
					repo.putInt(KEY_DIRECT_SELECTION_DEBOUNCE_MS, value)
				}
			},
		)

		// Auto-repeat: hold a repeatable Nav key (move/scroll) to repeat it.
		val autoRepeatSwitch: SwitchCompat = findViewById(R.id.autoRepeatSwitch)
		val autoRepeatDelaySeek: SeekBar = findViewById(R.id.autoRepeatDelaySeek)
		val autoRepeatDelayValue: TextView = findViewById(R.id.autoRepeatDelayValue)

		val autoRepeatSaved = repo.getBoolean(KEY_DIRECT_AUTOREPEAT_MODE)
		val delaySaved = repo.getFloat(KEY_DIRECT_AUTOREPEAT_DELAY_SEC).coerceIn(0.25f, 3.0f)
		autoRepeatSwitch.isChecked = autoRepeatSaved
		autoRepeatDelaySeek.progress = ((delaySaved - 0.25f) / 0.05f).toInt().coerceIn(0, 55)
		autoRepeatDelayValue.text = getString(R.string.format_delay_sec, delaySaved)
		updateAutoRepeatEnabled(autoRepeatDelaySeek, autoRepeatDelayValue, autoRepeatSaved)

		autoRepeatSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_DIRECT_AUTOREPEAT_MODE, isChecked)
			updateAutoRepeatEnabled(autoRepeatDelaySeek, autoRepeatDelayValue, isChecked)
		}

		autoRepeatDelaySeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					autoRepeatDelayValue.text = getString(R.string.format_delay_sec, 0.25f + progress * 0.05f)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					repo.putFloat(KEY_DIRECT_AUTOREPEAT_DELAY_SEC, 0.25f + autoRepeatDelaySeek.progress * 0.05f)
				}
			},
		)

		// Phase 3B: attach INFO PROMPT icon.
		val root: ViewGroup = findViewById(android.R.id.content)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ds_touch_debounce,
			R.string.info_prompt_ds_debounce,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ds_auto_repeat,
			R.string.info_prompt_ds_auto_repeat,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ds_auto_repeat_delay,
			R.string.info_prompt_ds_auto_repeat_delay,
		)
	}

	private fun updateAutoRepeatEnabled(
		seek: SeekBar,
		valueLabel: TextView,
		enabled: Boolean,
	) {
		seek.isEnabled = enabled
		valueLabel.alpha = if (enabled) 1.0f else 0.4f
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}

	private fun formatDebounce(ms: Int): String = if (ms <= 0) getString(R.string.format_debounce_off) else getString(R.string.format_debounce_ms, ms)
}
