package org.continuouspath.justtype.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.Constants.KEY_TOUCH_OVERLAY_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_BEEP
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_FLASH
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_MODE
import org.continuouspath.justtype.Constants.KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER
import org.continuouspath.justtype.Constants.KEY_TSS_BUTTON_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_MODE
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_OPACITY
import org.continuouspath.justtype.Constants.TOUCH_SCREEN_SWITCH_MODE_SINGLE
import org.continuouspath.justtype.Constants.TOUCH_SCREEN_SWITCH_MODE_TWO
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getInt
import org.continuouspath.justtype.settings.getString

class SetupTouchScreenSwitchFragment : Fragment() {

	private lateinit var repo: SettingsRepository

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.activity_setup_touch_screen_switch, container, false)

	@Suppress("LongMethod")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		repo = SettingsRepository.getInstance(requireContext())

		val backButton: ImageButton = view.findViewById(R.id.backButton)
		backButton.setOnClickListener {
			parentFragmentManager.popBackStack()
			activity?.takeIf { !it.isFinishing }?.finish()
		}

		// Switch bar mode: single (one green bar) or two (green left, red right)
		val switchModeRadioGroup: RadioGroup = view.findViewById(R.id.switchModeRadioGroup)
		val switchModeSingle: RadioButton = view.findViewById(R.id.switchModeSingle)
		val switchModeTwo: RadioButton = view.findViewById(R.id.switchModeTwo)
		val savedMode = repo.getString(KEY_TOUCH_SCREEN_SWITCH_MODE)
		when (savedMode) {
			TOUCH_SCREEN_SWITCH_MODE_SINGLE -> switchModeSingle.isChecked = true
			else -> switchModeTwo.isChecked = true
		}
		switchModeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
			val mode =
				when (checkedId) {
					R.id.switchModeSingle -> TOUCH_SCREEN_SWITCH_MODE_SINGLE
					else -> TOUCH_SCREEN_SWITCH_MODE_TWO
				}
			repo.putString(KEY_TOUCH_SCREEN_SWITCH_MODE, mode)
		}

		// Flash on activation
		val flashSwitch: SwitchCompat = view.findViewById(R.id.flashOnActivationSwitch)
		flashSwitch.isChecked = repo.getBoolean(KEY_TOUCH_SCREEN_SWITCH_FLASH)
		flashSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_TOUCH_SCREEN_SWITCH_FLASH, isChecked)
		}

		val borderSwitch: SwitchCompat = view.findViewById(R.id.showRegionBorderSwitch)
		borderSwitch.isChecked = repo.getBoolean(KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER, false)
		borderSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER, isChecked)
		}

		// Beep on activation
		val beepSwitch: SwitchCompat = view.findViewById(R.id.beepOnActivationSwitch)
		beepSwitch.isChecked = repo.getBoolean(KEY_TOUCH_SCREEN_SWITCH_BEEP)
		beepSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_TOUCH_SCREEN_SWITCH_BEEP, isChecked)
		}

		// Debounce slider (0-500ms, step 10ms)
		// 0 = disabled (OFF), 10-500 = active
		val debounceSeek: SeekBar = view.findViewById(R.id.debounceSeek)
		val debounceValue: TextView = view.findViewById(R.id.debounceValue)

		val savedDebounce = repo.getInt(KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS).coerceIn(0, 500)
		debounceSeek.max = 50 // 0..500 in steps of 10 = 51 values, but 0-50 = 51 positions
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

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = debounceSeek.progress * 10
					repo.putInt(KEY_TOUCH_SCREEN_SWITCH_DEBOUNCE_MS, value)
				}
			},
		)

		// Button height slider (5-100% of screen height, step 5%)
		val buttonHeightSeek: SeekBar = view.findViewById(R.id.buttonHeightSeek)
		val buttonHeightValue: TextView = view.findViewById(R.id.buttonHeightValue)

		val savedButtonHeight = repo.getInt(KEY_TSS_BUTTON_HEIGHT_PERCENT).coerceIn(5, 100)
		buttonHeightSeek.max = 19 // (100 - 5) / 5 = 19 steps
		buttonHeightSeek.progress = ((savedButtonHeight - 5) / 5).coerceIn(0, 19)
		buttonHeightValue.text = getString(R.string.format_tss_button_height_percent, savedButtonHeight)

		buttonHeightSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = 5 + progress * 5
					buttonHeightValue.text = getString(R.string.format_tss_button_height_percent, value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = 5 + buttonHeightSeek.progress * 5
					repo.putInt(KEY_TSS_BUTTON_HEIGHT_PERCENT, value)
				}
			},
		)

		// Overlay opacity slider (10-100%, step 5%)
		val overlayOpacitySeek: SeekBar = view.findViewById(R.id.overlayOpacitySeek)
		val overlayOpacityValue: TextView = view.findViewById(R.id.overlayOpacityValue)

		val savedOpacity = repo.getInt(KEY_TSS_OVERLAY_OPACITY).coerceIn(10, 100)
		overlayOpacitySeek.max = 18 // (100 - 10) / 5 = 18 steps
		overlayOpacitySeek.progress = ((savedOpacity - 10) / 5).coerceIn(0, 18)
		overlayOpacityValue.text = getString(R.string.format_tss_button_height_percent, savedOpacity)

		overlayOpacitySeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = 10 + progress * 5
					overlayOpacityValue.text = getString(R.string.format_tss_button_height_percent, value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = 10 + overlayOpacitySeek.progress * 5
					repo.putInt(KEY_TSS_OVERLAY_OPACITY, value)
				}
			},
		)

		// Touch Timeout slider (2–10 sec) — only meaningful when Overlay Mode is ON.
		// Shares KEY_TOUCH_OVERLAY_TIMEOUT_SEC with Directional Selection.
		val touchTimeoutLabel: TextView = view.findViewById(R.id.touchTimeoutLabel)
		val touchTimeoutRow: ViewGroup = view.findViewById(R.id.touchTimeoutRow)
		val touchTimeoutSeek: SeekBar = view.findViewById(R.id.touchTimeoutSeek)
		val touchTimeoutValue: TextView = view.findViewById(R.id.touchTimeoutValue)

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

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = (touchTimeoutSeek.progress + 2).coerceIn(2, 10)
					repo.putInt(KEY_TOUCH_OVERLAY_TIMEOUT_SEC, value)
				}
			},
		)

		// Overlay mode toggle — controls enable state of Touch Timeout slider.
		val overlayModeSwitch: SwitchCompat = view.findViewById(R.id.overlayModeSwitch)
		val initialOverlayMode = repo.getBoolean(KEY_TSS_OVERLAY_MODE)
		overlayModeSwitch.isChecked = initialOverlayMode
		updateTouchTimeoutEnabled(initialOverlayMode, touchTimeoutLabel, touchTimeoutRow, touchTimeoutSeek)
		overlayModeSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_TSS_OVERLAY_MODE, isChecked)
			updateTouchTimeoutEnabled(isChecked, touchTimeoutLabel, touchTimeoutRow, touchTimeoutSeek)
		}

		// Phase 3B: attach INFO PROMPT icons.
		val root = view as ViewGroup
		// Section header doubles as the "How it Works" overview prompt.
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_tss_how_it_works,
			R.string.info_prompt_section_tcs_how_works,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.touch_screen_switch_mode_label,
			R.string.info_prompt_tcs_switch_mode,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.touch_screen_switch_flash,
			R.string.info_prompt_tcs_flash,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.touch_screen_switch_beep,
			R.string.info_prompt_tcs_beep,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_tss_touch_debounce,
			R.string.info_prompt_tcs_debounce,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_tss_button_height,
			R.string.info_prompt_tcs_switch_height,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_tss_overlay_mode,
			R.string.info_prompt_tcs_overlay_mode,
		)
		// Phase 3D B-TC-CONFIRM-OVERLAY-TIMEOUT: info_prompt_tcs_overlay_timeout
		// describes the Touch Timeout / Overlay Mode Display Timeout — moved from
		// the (unrelated) Overlay Opacity slider to the new Touch Timeout slider.
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_dir_touch_timeout,
			R.string.info_prompt_tcs_overlay_timeout,
		)
	}

	/**
	 * Touch Timeout is only meaningful when Overlay Mode is ON. When Overlay Mode
	 * is OFF the slider is grayed out (still visible so the relationship is
	 * discoverable, but non-interactive). See Phase 3D B-TC-CONFIRM-OVERLAY-TIMEOUT.
	 */
	private fun updateTouchTimeoutEnabled(
		overlayModeOn: Boolean,
		label: TextView,
		row: ViewGroup,
		seek: SeekBar,
	) {
		val alpha = if (overlayModeOn) 1f else 0.4f
		label.alpha = alpha
		row.alpha = alpha
		seek.isEnabled = overlayModeOn
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}

	private fun formatDebounce(ms: Int): String = if (ms <= 0) getString(R.string.format_debounce_off) else getString(R.string.format_debounce_ms, ms)
}
