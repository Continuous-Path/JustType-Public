package org.continuouspath.justtype.activity

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_GREEN_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_RED_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_AUTOREPEAT_MODE
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_BEEP_ACTIVATION
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATIONS
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_TWO_SWITCH_SHOW_BAND
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.continuouspath.justtype.R
import org.continuouspath.justtype.input.HatSwitchCodes
import org.continuouspath.justtype.input.InputCaptureGate
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt

class SetupTwoSwitchFragment :
	Fragment(),
	KeyEventInterceptor,
	MotionEventInterceptor {

	private var waitingForRed = false
	private var waitingForGreen = false
	private var hatCentered = true // d-pad HAT edge detection while binding
	private lateinit var redLabel: TextView
	private lateinit var greenLabel: TextView
	private lateinit var activateRedButton: Button
	private lateinit var activateGreenButton: Button
	private lateinit var clearRedButton: ImageButton
	private lateinit var clearGreenButton: ImageButton
	private lateinit var repo: SettingsRepository
	private lateinit var touchSwitchToggle: SwitchCompat

	// The theme's default label color, captured once so we can swap back from the warning red.
	private var primaryTextColor: Int = 0

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.activity_setup_two_switch, container, false)

	@Suppress("LongMethod")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		repo = SettingsRepository.getInstance(requireContext())

		val backButton: ImageButton = view.findViewById(R.id.backButton)
		activateRedButton = view.findViewById(R.id.activateRedButton)
		activateGreenButton = view.findViewById(R.id.activateGreenButton)
		clearRedButton = view.findViewById(R.id.clearRedButton)
		clearGreenButton = view.findViewById(R.id.clearGreenButton)
		redLabel = view.findViewById(R.id.redSwitchLabel)
		greenLabel = view.findViewById(R.id.greenSwitchLabel)
		primaryTextColor = redLabel.currentTextColor // theme default, before any warning recolor
		val highlightSeek: SeekBar = view.findViewById(R.id.highlightTimeoutSeek)
		val highlightValue: TextView = view.findViewById(R.id.highlightTimeoutValue)
		val stuckTimeoutSeek: SeekBar = view.findViewById(R.id.stuckTimeoutSeek)
		val stuckTimeoutValue: TextView = view.findViewById(R.id.stuckTimeoutValue)
		val showColorBandSwitch: SwitchCompat = view.findViewById(R.id.showColorBandSwitch)
		val autoRepeatSwitch: SwitchCompat = view.findViewById(R.id.twoSwitchAutoRepeatSwitch)
		val autoRepeatDelaySeek: SeekBar = view.findViewById(R.id.twoSwitchAutoRepeatDelaySeek)
		val autoRepeatDelayValue: TextView = view.findViewById(R.id.twoSwitchAutoRepeatDelayValue)
		val autoRepeatDelayRow: View = view.findViewById(R.id.twoSwitchAutoRepeatDelayRow)
		val repeatActivationSwitch: SwitchCompat = view.findViewById(R.id.twoSwitchRepeatActivationSwitch)
		val repeatActivationDelaySeek: SeekBar = view.findViewById(R.id.twoSwitchRepeatActivationDelaySeek)
		val repeatActivationDelayValue: TextView = view.findViewById(R.id.twoSwitchRepeatActivationDelayValue)
		val repeatActivationDelayRow: View = view.findViewById(R.id.twoSwitchRepeatActivationDelayRow)
		val beepActivationSwitch: SwitchCompat = view.findViewById(R.id.twoSwitchBeepActivationSwitch)
		touchSwitchToggle = view.findViewById(R.id.touchScreenSwitchToggle)

		backButton.setOnClickListener {
			parentFragmentManager.popBackStack()
			activity?.takeIf { !it.isFinishing }?.finish()
		}
		// Ensure we can receive hardware key events even with no text fields.
		// Note: Not requesting focus to prevent keyboard from opening.
		view.isFocusableInTouchMode = true

		updateSwitchLabels()

		// Pressing an assign button starts capture (cancelling any capture already waiting on the
		// other switch); pressing it again while waiting cancels. Works with the touchscreen
		// switch on too — physical binds and touchscreen input coexist.
		activateRedButton.setOnClickListener {
			if (waitingForRed) cancelCapture() else startCapture(red = true)
		}

		activateGreenButton.setOnClickListener {
			if (waitingForGreen) cancelCapture() else startCapture(red = false)
		}

		clearRedButton.setOnClickListener {
			cancelCapture()
			repo.putInt(KEY_RED_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
			updateSwitchLabels()
		}

		clearGreenButton.setOnClickListener {
			cancelCapture()
			repo.putInt(KEY_GREEN_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
			updateSwitchLabels()
		}

		val highlightSaved = repo.getInt(KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC).coerceIn(0, 120)
		highlightSeek.max = 120
		highlightSeek.progress = highlightSaved
		highlightValue.text = formatHighlightTimeout(highlightSaved)

		// Stuck Switch Timeout: 2..30 s (mirrors the Keyboard Settings two_switch page)
		val stuckSaved = repo.getInt(KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC).coerceIn(STUCK_TIMEOUT_MIN_SEC, STUCK_TIMEOUT_MAX_SEC)
		stuckTimeoutSeek.max = STUCK_TIMEOUT_MAX_SEC - STUCK_TIMEOUT_MIN_SEC
		stuckTimeoutSeek.progress = stuckSaved - STUCK_TIMEOUT_MIN_SEC
		stuckTimeoutValue.text = getString(R.string.sr_format_sec, stuckSaved)

		val showBandSaved = repo.getBoolean(KEY_TWO_SWITCH_SHOW_BAND)
		val touchSwitchSaved = repo.getBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)
		val autoRepeatSaved = repo.getBoolean(KEY_TWO_SWITCH_AUTOREPEAT_MODE)
		val autoRepeatDelaySaved =
			repo.getFloat(KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC).coerceIn(0.25f, 3.0f)
		val repeatActivationSaved = repo.getBoolean(KEY_TWO_SWITCH_REPEAT_ACTIVATIONS)
		val repeatActivationDelaySaved =
			repo.getFloat(KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC).coerceIn(0.25f, 3.0f)
		val beepActivationSaved = repo.getBoolean(KEY_TWO_SWITCH_BEEP_ACTIVATION)
		showColorBandSwitch.isChecked = showBandSaved
		touchSwitchToggle.isChecked = touchSwitchSaved
		autoRepeatSwitch.isChecked = autoRepeatSaved
		repeatActivationSwitch.isChecked = repeatActivationSaved
		beepActivationSwitch.isChecked = beepActivationSaved
		// "Beep on Each Switch Activation" only affects the intermediate hits when the master
		// "Beep when key is activated" is on — grey it out otherwise so the dependency is clear.
		val masterBeepOn = repo.getBoolean(KEY_BEEP_KEY_FEEDBACK)
		beepActivationSwitch.isEnabled = masterBeepOn
		beepActivationSwitch.alpha = if (masterBeepOn) 1f else 0.4f

		autoRepeatDelaySeek.max = 55
		autoRepeatDelaySeek.progress = ((autoRepeatDelaySaved - 0.25f) / 0.05f).toInt().coerceIn(0, 55)
		autoRepeatDelayValue.text = getString(R.string.format_delay_sec, autoRepeatDelaySaved)
		updateAutoRepeatVisibility(autoRepeatDelayRow, autoRepeatDelaySeek, autoRepeatDelayValue, autoRepeatSaved)
		repeatActivationDelaySeek.max = 55
		repeatActivationDelaySeek.progress =
			((repeatActivationDelaySaved - 0.25f) / 0.05f).toInt().coerceIn(0, 55)
		repeatActivationDelayValue.text = getString(R.string.format_delay_sec, repeatActivationDelaySaved)
		updateAutoRepeatVisibility(
			repeatActivationDelayRow,
			repeatActivationDelaySeek,
			repeatActivationDelayValue,
			repeatActivationSaved,
		)

		highlightSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					highlightValue.text = formatHighlightTimeout(progress)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = seekBar?.progress ?: 0
					repo.putInt(KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC, value)
				}
			},
		)

		stuckTimeoutSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					stuckTimeoutValue.text = getString(R.string.sr_format_sec, STUCK_TIMEOUT_MIN_SEC + progress)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					repo.putInt(KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC, STUCK_TIMEOUT_MIN_SEC + stuckTimeoutSeek.progress)
				}
			},
		)

		showColorBandSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_TWO_SWITCH_SHOW_BAND, isChecked)
		}

		touchSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED, isChecked)
			if (waitingForRed || waitingForGreen) cancelCapture() else updateSwitchLabels()
		}

		autoRepeatDelaySeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = 0.25f + progress * 0.05f
					autoRepeatDelayValue.text = getString(R.string.format_delay_sec, value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = 0.25f + (seekBar?.progress ?: 0) * 0.05f
					repo.putFloat(KEY_TWO_SWITCH_AUTOREPEAT_DELAY_SEC, value)
				}
			},
		)

		autoRepeatSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_TWO_SWITCH_AUTOREPEAT_MODE, isChecked)
			updateAutoRepeatVisibility(autoRepeatDelayRow, autoRepeatDelaySeek, autoRepeatDelayValue, isChecked)
		}

		repeatActivationDelaySeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = 0.25f + progress * 0.05f
					repeatActivationDelayValue.text = getString(R.string.format_delay_sec, value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = 0.25f + (seekBar?.progress ?: 0) * 0.05f
					repo.putFloat(KEY_TWO_SWITCH_REPEAT_ACTIVATION_DELAY_SEC, value)
				}
			},
		)

		repeatActivationSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_TWO_SWITCH_REPEAT_ACTIVATIONS, isChecked)
			updateAutoRepeatVisibility(
				repeatActivationDelayRow,
				repeatActivationDelaySeek,
				repeatActivationDelayValue,
				isChecked,
			)
		}

		beepActivationSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_TWO_SWITCH_BEEP_ACTIVATION, isChecked)
		}

		// Phase 3B: attach INFO PROMPT icons.
		val infoRoot = view as ViewGroup
		// Phase 3D info-icon sweep: previously-missing "Use Touchscreen Switch" row.
		SettingsInfoHelper.attachInfoIcon(
			infoRoot,
			R.string.label_use_touch_screen_switch,
			R.string.info_prompt_ts_touchscreen_switch,
		)
		SettingsInfoHelper.attachInfoIcon(
			infoRoot,
			R.string.setup_ts_activate_red,
			R.string.info_prompt_ts_assign_switch,
		)
		SettingsInfoHelper.attachInfoIcon(
			infoRoot,
			R.string.setup_ts_activate_green,
			R.string.info_prompt_ts_assign_switch,
		)
		SettingsInfoHelper.attachInfoIcon(
			infoRoot,
			R.string.setup_ts_highlight_timeout,
			R.string.info_prompt_ts_reset_delay,
		)
		SettingsInfoHelper.attachInfoIcon(
			infoRoot,
			R.string.setup_ts_show_color_band,
			R.string.info_prompt_ts_show_color_band,
		)
		SettingsInfoHelper.attachInfoIcon(
			infoRoot,
			R.string.setup_ts_auto_repeat_key,
			R.string.info_prompt_ts_auto_repeat_key,
		)
		SettingsInfoHelper.attachInfoIcon(
			infoRoot,
			R.string.setup_ts_auto_repeat_switch,
			R.string.info_prompt_ts_auto_repeat_switch,
		)
		SettingsInfoHelper.attachInfoIcon(
			infoRoot,
			R.string.setup_ts_beep_activation,
			R.string.info_prompt_ts_beep,
		)
	}

	override fun onPause() {
		super.onPause()
		// Failsafe: never leave live input suppressed if we leave mid-capture.
		cancelCapture()
		SettingsSpeechController.stop()
	}

	/** Start waiting for a physical press for one switch; the other switch's capture is cancelled. */
	private fun startCapture(red: Boolean) {
		cancelCapture()
		if (red) waitingForRed = true else waitingForGreen = true
		hatCentered = true // require a fresh d-pad press, not one already held
		InputCaptureGate.begin(repo) // suppress live IME/Nav input during capture
		val button = if (red) activateRedButton else activateGreenButton
		button.text = getString(R.string.assign_switch_waiting)
		button.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.switch_setup_waiting)
		val label = if (red) redLabel else greenLabel
		label.text = getString(if (red) R.string.waiting_for_red_switch else R.string.waiting_for_green_switch)
		label.setTextColor(primaryTextColor)
	}

	/** Stop waiting (if waiting) and restore both assign buttons + labels to their standing state. */
	private fun cancelCapture() {
		waitingForRed = false
		waitingForGreen = false
		InputCaptureGate.end(repo)
		activateRedButton.text = getString(R.string.setup_ts_activate_red)
		activateRedButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.switch_setup_red)
		activateGreenButton.text = getString(R.string.setup_ts_activate_green)
		activateGreenButton.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.switch_setup_green)
		updateSwitchLabels()
	}

	override fun interceptKeyEvent(event: KeyEvent): Boolean {
		if (waitingForRed || waitingForGreen) {
			// Sliding window: keep the gate alive while actuating (slow presses).
			InputCaptureGate.refresh(repo)
		}
		// Catch hardware keys early in dispatch so they are not eaten by focus navigation.
		if ((waitingForRed || waitingForGreen) &&
			event.action == KeyEvent.ACTION_DOWN &&
			event.repeatCount == 0
		) {
			bindWaitingSwitch(event.keyCode)
			return true
		}
		return false
	}

	/** Bind the d-pad: it reaches the Nav overlay as HAT motion where face buttons can't. */
	override fun interceptMotionEvent(event: MotionEvent): Boolean {
		if (!(waitingForRed || waitingForGreen)) return false
		InputCaptureGate.refresh(repo) // sliding window, mirrors interceptKeyEvent
		val code = HatSwitchCodes.hatToDpadKeyCode(event)
		if (code == SWITCH_CODE_UNDEFINED) {
			hatCentered = true // released/centered — re-arm for the next press
			return false
		}
		if (!hatCentered) return true // still held; consume but don't re-bind
		hatCentered = false
		bindWaitingSwitch(code)
		return true
	}

	private fun bindWaitingSwitch(keyCode: Int) {
		if (waitingForRed) {
			assignSwitch(KEY_RED_SWITCH_CODE, keyCode)
		} else {
			assignSwitch(KEY_GREEN_SWITCH_CODE, keyCode)
		}
		cancelCapture()
	}

	private fun assignSwitch(
		prefKey: String,
		keyCode: Int,
	) {
		var red = repo.getInt(KEY_RED_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
		var green = repo.getInt(KEY_GREEN_SWITCH_CODE, SWITCH_CODE_UNDEFINED)

		if (prefKey == KEY_RED_SWITCH_CODE) {
			red = keyCode
			if (green == keyCode) {
				green = SWITCH_CODE_UNDEFINED
			}
		} else {
			green = keyCode
			if (red == keyCode) {
				red = SWITCH_CODE_UNDEFINED
			}
		}
		// Red≠green dedup (above) is kept — they're active at the same time. No cross-method
		// clearing of the scan code: single- and two-switch never run at once, so a code may be
		// bound to both; routing picks the role by the active method.
		repo.edit()
			.putInt(KEY_RED_SWITCH_CODE, red)
			.putInt(KEY_GREEN_SWITCH_CODE, green)
			.apply()
	}

	private fun updateSwitchLabels() {
		val touchSwitchEnabled = repo.getBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)
		val red = repo.getInt(KEY_RED_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
		val green = repo.getInt(KEY_GREEN_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
		applySwitchLabel(
			redLabel,
			red,
			touchSwitchEnabled,
			R.string.red_switch_label,
			R.string.red_switch_label_with_touch,
			R.string.red_switch_touch_screen,
		)
		applySwitchLabel(
			greenLabel,
			green,
			touchSwitchEnabled,
			R.string.green_switch_label,
			R.string.green_switch_label_with_touch,
			R.string.green_switch_touch_screen,
		)
		updateClearButton(clearRedButton, red)
		updateClearButton(clearGreenButton, green)
	}

	/**
	 * Physical bind, touchscreen, or both — they coexist. The red "no switch assigned"
	 * warning shows only when neither input can drive the switch.
	 */
	private fun applySwitchLabel(
		label: TextView,
		code: Int,
		touchEnabled: Boolean,
		boundTemplate: Int,
		boundWithTouchTemplate: Int,
		touchOnlyText: Int,
	) {
		if (code == SWITCH_CODE_UNDEFINED && !touchEnabled) {
			label.text = getString(R.string.switch_unassigned_warning)
			label.setTextColor(ContextCompat.getColor(requireContext(), R.color.switch_unassigned_warning))
			return
		}
		label.text = when {
			code == SWITCH_CODE_UNDEFINED -> getString(touchOnlyText)
			touchEnabled -> getString(boundWithTouchTemplate, switchLabel(code))
			else -> getString(boundTemplate, switchLabel(code))
		}
		label.setTextColor(primaryTextColor)
	}

	private fun updateClearButton(button: ImageButton, code: Int) {
		val assigned = code != SWITCH_CODE_UNDEFINED
		button.isEnabled = assigned
		button.alpha = if (assigned) 1f else 0.4f
	}

	private fun switchLabel(code: Int): String {
		if (code == SWITCH_CODE_UNDEFINED) return getString(R.string.switch_undefined)
		return when (code) {
			KeyEvent.KEYCODE_1 -> getString(R.string.switch_1)
			KeyEvent.KEYCODE_2 -> getString(R.string.switch_2)
			KeyEvent.KEYCODE_3 -> getString(R.string.switch_3)
			KeyEvent.KEYCODE_NUMPAD_1 -> getString(R.string.numpad_1)
			KeyEvent.KEYCODE_NUMPAD_2 -> getString(R.string.numpad_2)
			KeyEvent.KEYCODE_NUMPAD_3 -> getString(R.string.numpad_3)
			Constants.HEADBOARD_SWITCH_1_KEYCODE -> getString(R.string.headboard_switch_1)
			Constants.HEADBOARD_SWITCH_2_KEYCODE -> getString(R.string.headboard_switch_2)
			else -> {
				val raw = KeyEvent.keyCodeToString(code)
				raw
					.removePrefix("KEYCODE_")
					.replace('_', ' ')
					.lowercase()
					.replaceFirstChar { it.uppercase() }
			}
		}
	}

	private fun formatHighlightTimeout(value: Int): String = if (value <= 0) {
		getString(R.string.format_highlight_timeout_disabled)
	} else {
		getString(R.string.format_highlight_timeout_sec, value)
	}

	private fun updateAutoRepeatVisibility(
		row: View,
		seek: SeekBar,
		valueLabel: TextView,
		enabled: Boolean,
	) {
		row.visibility = if (enabled) View.VISIBLE else View.GONE
		seek.isEnabled = enabled
		valueLabel.alpha = if (enabled) 1.0f else 0.4f
	}

	private companion object {
		// Range mirrors the SettingsRegistry two_switch entry (2..30 s).
		const val STUCK_TIMEOUT_MIN_SEC = 2
		const val STUCK_TIMEOUT_MAX_SEC = 30
	}
}
