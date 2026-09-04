package org.continuouspath.justtype.activity

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.Constants.KEY_AUTOREPEAT_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_AUTOREPEAT_MODE
import org.continuouspath.justtype.Constants.KEY_BEEP_EACH_SCAN_STEP
import org.continuouspath.justtype.Constants.KEY_INITIAL_SCAN_DELAY_INCREASE_SEC
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
import org.continuouspath.justtype.Constants.KEY_SCAN_LAYOUT_SIZE
import org.continuouspath.justtype.Constants.KEY_SCAN_REPEAT_COUNT
import org.continuouspath.justtype.Constants.KEY_SCAN_STEP_DELAY_SEC
import org.continuouspath.justtype.Constants.KEY_SCAN_SWITCH_CODE
import org.continuouspath.justtype.Constants.KEY_SELECT_KEY_TRIGGERS_SCAN
import org.continuouspath.justtype.Constants.KEY_SHOW_NEXT_KEY
import org.continuouspath.justtype.Constants.KEY_SKIP_KEYS_NO_VALID
import org.continuouspath.justtype.Constants.KEY_SWITCH_DEBOUNCE_MS
import org.continuouspath.justtype.Constants.SCAN_LAYOUT_SIZE_LARGE
import org.continuouspath.justtype.Constants.SCAN_LAYOUT_SIZE_SMALL
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.continuouspath.justtype.R
import org.continuouspath.justtype.input.HatSwitchCodes
import org.continuouspath.justtype.input.InputCaptureGate
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt
import org.continuouspath.justtype.settings.getString

class SetupSingleSwitchFragment :
	Fragment(),
	KeyEventInterceptor,
	MotionEventInterceptor {

	private lateinit var prefs: SettingsRepository
	private var waitingForScanSwitch = false
	private var hatCentered = true // d-pad HAT edge detection while binding
	private lateinit var scanSwitchLabel: TextView
	private lateinit var activateScanSwitchButton: Button
	private lateinit var clearScanSwitchButton: ImageButton
	private lateinit var beepEachStepSwitch: SwitchCompat
	private lateinit var touchSwitchToggle: SwitchCompat

	// The assign button's default background tint, captured once so waiting-state recolor can restore it.
	private var assignButtonDefaultTint: android.content.res.ColorStateList? = null

	// The theme's default label color, captured once so we can swap back from the warning red.
	private var primaryTextColor: Int = 0

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.activity_setup_single_switch, container, false)

	@Suppress("LongMethod")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		prefs = SettingsRepository.getInstance(requireContext())

		val backButton: ImageButton = view.findViewById(R.id.backButton)
		backButton.setOnClickListener {
			parentFragmentManager.popBackStack()
			activity?.takeIf { !it.isFinishing }?.finish()
		}

		// Controls
		activateScanSwitchButton = view.findViewById(R.id.activateScanSwitchButton)
		clearScanSwitchButton = view.findViewById(R.id.clearScanSwitchButton)
		assignButtonDefaultTint = activateScanSwitchButton.backgroundTintList
		scanSwitchLabel = view.findViewById(R.id.scanSwitchLabel)
		primaryTextColor = scanSwitchLabel.currentTextColor // theme default, before any warning recolor
		val debounceSeek: SeekBar = view.findViewById(R.id.switchDebounceSeek)
		val debounceValue: TextView = view.findViewById(R.id.switchDebounceValue)

		val scanStepSeek: SeekBar = view.findViewById(R.id.scanStepDelaySeek)
		val scanStepValue: TextView = view.findViewById(R.id.scanStepDelayValue)

		val initialDelaySeek: SeekBar = view.findViewById(R.id.initialScanDelayIncreaseSeek)
		val initialDelayValue: TextView = view.findViewById(R.id.initialScanDelayIncreaseValue)

		val skipKeysSwitch: SwitchCompat = view.findViewById(R.id.skipKeysSwitch)
		val showNextKeySwitch: SwitchCompat = view.findViewById(R.id.showNextKeySwitch)
		val autoRepeatSwitch: SwitchCompat = view.findViewById(R.id.autoRepeatSwitch)
		val autoRepeatDelaySeek: SeekBar = view.findViewById(R.id.autoRepeatDelaySeek)
		val autoRepeatDelayValue: TextView = view.findViewById(R.id.autoRepeatDelayValue)
		val selectTriggersScanSwitch: SwitchCompat = view.findViewById(R.id.selectTriggersScanSwitch)
		val repeatCountSpinner: Spinner = view.findViewById(R.id.scanRepeatCountSpinner)
		val scanLayoutGroup: RadioGroup = view.findViewById(R.id.scanLayoutSizeGroup)
		val scanLayoutSmall: RadioButton = view.findViewById(R.id.scanLayoutSmall)
		val scanLayoutLarge: RadioButton = view.findViewById(R.id.scanLayoutLarge)
		beepEachStepSwitch = view.findViewById(R.id.beepEachStepSwitch)
		touchSwitchToggle = view.findViewById(R.id.touchScreenSwitchToggle)

		// Debounce (0..1000 ms, step 10ms, 0 = disabled)
		val debounceSaved = prefs.getInt(KEY_SWITCH_DEBOUNCE_MS).coerceIn(0, 1000)
		debounceSeek.max = 100 // 0..1000 in steps of 10 = 101 positions (0-100)
		debounceSeek.progress = (debounceSaved / 10).coerceIn(0, 100)
		debounceValue.text = formatSwitchDebounce(debounceSaved)

		// Scan step delay (0.25..3.00, step 0.05)
		val scanSaved = prefs.getFloat(KEY_SCAN_STEP_DELAY_SEC).coerceIn(0.25f, 3.0f)
		scanStepSeek.max = 55
		scanStepSeek.progress = ((scanSaved - 0.25f) / 0.05f).toInt().coerceIn(0, 55)
		scanStepValue.text = getString(R.string.format_delay_sec, scanSaved)

		// Initial scan delay increase (0..1.50, step 0.05)
		val initialSaved = prefs.getFloat(KEY_INITIAL_SCAN_DELAY_INCREASE_SEC).coerceIn(0f, 1.5f)
		initialDelaySeek.max = 30
		initialDelaySeek.progress = (initialSaved / 0.05f).toInt().coerceIn(0, 30)
		initialDelayValue.text = getString(R.string.format_delay_sec, initialSaved)

		val skipSaved = prefs.getBoolean(KEY_SKIP_KEYS_NO_VALID)
		val showNextSaved = prefs.getBoolean(KEY_SHOW_NEXT_KEY)
		val autoRepeatSaved = prefs.getBoolean(KEY_AUTOREPEAT_MODE)
		val autoRepeatDelaySaved = prefs.getFloat(KEY_AUTOREPEAT_DELAY_SEC).coerceIn(0.25f, 3.0f)
		val selectScanSaved = prefs.getBoolean(KEY_SELECT_KEY_TRIGGERS_SCAN)
		val repeatCountSaved = prefs.getInt(KEY_SCAN_REPEAT_COUNT).coerceIn(0, 10)
		val scanLayoutSizeSaved = prefs.getString(KEY_SCAN_LAYOUT_SIZE)
		val scanSwitchCodeSaved = prefs.getInt(KEY_SCAN_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
		val beepEachStepSaved = prefs.getBoolean(KEY_BEEP_EACH_SCAN_STEP)
		val touchSwitchSaved = prefs.getBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)

		skipKeysSwitch.isChecked = skipSaved
		showNextKeySwitch.isChecked = showNextSaved
		autoRepeatSwitch.isChecked = autoRepeatSaved
		selectTriggersScanSwitch.isChecked = selectScanSaved
		scanLayoutSmall.isChecked = scanLayoutSizeSaved == SCAN_LAYOUT_SIZE_SMALL
		scanLayoutLarge.isChecked = scanLayoutSizeSaved != SCAN_LAYOUT_SIZE_SMALL
		beepEachStepSwitch.isChecked = beepEachStepSaved
		touchSwitchToggle.isChecked = touchSwitchSaved

		autoRepeatDelaySeek.max = 55
		autoRepeatDelaySeek.progress = ((autoRepeatDelaySaved - 0.25f) / 0.05f).toInt().coerceIn(0, 55)
		autoRepeatDelayValue.text = getString(R.string.format_delay_sec, autoRepeatDelaySaved)

		val adapter =
			ArrayAdapter<Int>(
				requireContext(),
				android.R.layout.simple_spinner_item,
				(0..10).toList(),
			)
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
		repeatCountSpinner.adapter = adapter
		repeatCountSpinner.setSelection(repeatCountSaved)

		updateScanSwitchLabel(scanSwitchCodeSaved)

		// Pressing the assign button starts capture; pressing it again while waiting cancels.
		// Works with the touchscreen switch on too — physical binds and touchscreen input coexist.
		activateScanSwitchButton.setOnClickListener {
			if (waitingForScanSwitch) cancelCapture() else startCapture()
		}

		clearScanSwitchButton.setOnClickListener {
			cancelCapture()
			prefs.putInt(KEY_SCAN_SWITCH_CODE, SWITCH_CODE_UNDEFINED)
			updateScanSwitchLabel(SWITCH_CODE_UNDEFINED)
		}

		// Listeners
		debounceSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = progress * 10
					debounceValue.text = formatSwitchDebounce(value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = (debounceSeek.progress * 10).coerceIn(0, 1000)
					prefs.putInt(KEY_SWITCH_DEBOUNCE_MS, value)
				}
			},
		)

		scanStepSeek.setOnSeekBarChangeListener(
			simpleSeek { progress, persist ->
				val value = 0.25f + progress * 0.05f
				scanStepValue.text = getString(R.string.format_delay_sec, value)
				if (persist) prefs.putFloat(KEY_SCAN_STEP_DELAY_SEC, value)
			},
		)

		initialDelaySeek.setOnSeekBarChangeListener(
			simpleSeek { progress, persist ->
				val value = progress * 0.05f
				initialDelayValue.text = getString(R.string.format_delay_sec, value)
				if (persist) prefs.putFloat(KEY_INITIAL_SCAN_DELAY_INCREASE_SEC, value)
			},
		)

		autoRepeatDelaySeek.setOnSeekBarChangeListener(
			simpleSeek { progress, persist ->
				val value = 0.25f + progress * 0.05f
				autoRepeatDelayValue.text = getString(R.string.format_delay_sec, value)
				if (persist) prefs.putFloat(KEY_AUTOREPEAT_DELAY_SEC, value)
			},
		)

		skipKeysSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SKIP_KEYS_NO_VALID, isChecked)
			updateShowNextEnabled(showNextKeySwitch, isChecked)
		}

		showNextKeySwitch.setOnCheckedChangeListener { _, isChecked ->
			if (showNextKeySwitch.isEnabled) {
				prefs.putBoolean(KEY_SHOW_NEXT_KEY, isChecked)
			}
		}

		autoRepeatSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_AUTOREPEAT_MODE, isChecked)
			updateAutoRepeatEnabled(autoRepeatDelaySeek, autoRepeatDelayValue, isChecked)
		}

		selectTriggersScanSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SELECT_KEY_TRIGGERS_SCAN, isChecked)
		}

		repeatCountSpinner.onItemSelectedListener =
			object : AdapterView.OnItemSelectedListener {
				override fun onItemSelected(
					parent: AdapterView<*>,
					view: View?,
					position: Int,
					id: Long,
				) {
					prefs.putInt(KEY_SCAN_REPEAT_COUNT, position)
				}

				override fun onNothingSelected(parent: AdapterView<*>) { /* no-op */ }
			}

		touchSwitchToggle.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED, isChecked)
			if (waitingForScanSwitch) {
				cancelCapture()
			} else {
				updateScanSwitchLabel(prefs.getInt(KEY_SCAN_SWITCH_CODE, SWITCH_CODE_UNDEFINED))
			}
		}

		scanLayoutGroup.setOnCheckedChangeListener { _, checkedId ->
			val value =
				if (checkedId == R.id.scanLayoutSmall) {
					SCAN_LAYOUT_SIZE_SMALL
				} else {
					SCAN_LAYOUT_SIZE_LARGE
				}
			prefs.putString(KEY_SCAN_LAYOUT_SIZE, value)
		}

		beepEachStepSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_BEEP_EACH_SCAN_STEP, isChecked)
		}

		// Ensure dependent states reflect initial prefs
		updateShowNextEnabled(showNextKeySwitch, skipSaved)
		updateAutoRepeatEnabled(autoRepeatDelaySeek, autoRepeatDelayValue, autoRepeatSaved)

		// Phase 3B: attach INFO PROMPT icons.
		val root = view as ViewGroup
		// Phase 3D info-icon sweep: previously-missing "Use Touchscreen Switch" row.
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.label_use_touch_screen_switch,
			R.string.info_prompt_ss_touchscreen_switch,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_activate_switch,
			R.string.info_prompt_ss_assign_switch,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_scanning_layout_size,
			R.string.info_prompt_ss_scan_layout_size,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_scan_step_delay,
			R.string.info_prompt_ss_scan_step_delay,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_initial_delay,
			R.string.info_prompt_ss_initial_delay,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_beep_each_step,
			R.string.info_prompt_ss_beep,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_skip_keys,
			R.string.info_prompt_ss_skip_invalid,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_show_next_key,
			R.string.info_prompt_ss_show_next_key,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_auto_repeat_mode,
			R.string.info_prompt_ss_auto_repeat_mode,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_select_triggers_scan,
			R.string.info_prompt_ss_select_triggers_scan,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_repeat_count,
			R.string.info_prompt_ss_scan_repeat_count,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ss_physical_debounce,
			R.string.info_prompt_ss_debounce,
		)
	}

	override fun onPause() {
		super.onPause()
		// Failsafe: never leave live input suppressed if we leave mid-capture.
		cancelCapture()
		SettingsSpeechController.stop()
	}

	private fun startCapture() {
		waitingForScanSwitch = true
		hatCentered = true // require a fresh d-pad press, not one already held
		InputCaptureGate.begin(prefs) // suppress live IME/Nav input during capture
		activateScanSwitchButton.text = getString(R.string.assign_switch_waiting)
		activateScanSwitchButton.backgroundTintList =
			ContextCompat.getColorStateList(requireContext(), R.color.switch_setup_waiting)
		scanSwitchLabel.text = getString(R.string.waiting_for_scanning_switch)
		scanSwitchLabel.setTextColor(primaryTextColor)
	}

	/** Stop waiting (if waiting) and restore the assign button + label to their standing state. */
	private fun cancelCapture() {
		waitingForScanSwitch = false
		InputCaptureGate.end(prefs)
		activateScanSwitchButton.text = getString(R.string.setup_ss_activate_switch)
		activateScanSwitchButton.backgroundTintList = assignButtonDefaultTint
		updateScanSwitchLabel(prefs.getInt(KEY_SCAN_SWITCH_CODE, SWITCH_CODE_UNDEFINED))
	}

	override fun interceptKeyEvent(event: KeyEvent): Boolean {
		if (waitingForScanSwitch) {
			// Sliding window: keep the gate alive while the user is actuating, so slow
			// presses don't hit the failsafe before the binding press lands.
			InputCaptureGate.refresh(prefs)
		}
		if (waitingForScanSwitch &&
			event.action == KeyEvent.ACTION_DOWN &&
			event.repeatCount == 0
		) {
			assignScanSwitch(event.keyCode)
			cancelCapture()
			return true
		}
		return false
	}

	/** Bind the d-pad: it reaches the Nav overlay as HAT motion where face buttons can't. */
	override fun interceptMotionEvent(event: MotionEvent): Boolean {
		if (!waitingForScanSwitch) return false
		InputCaptureGate.refresh(prefs) // sliding window, mirrors interceptKeyEvent
		val code = HatSwitchCodes.hatToDpadKeyCode(event)
		if (code == SWITCH_CODE_UNDEFINED) {
			hatCentered = true // released/centered — re-arm for the next press
			return false
		}
		if (!hatCentered) return true // still held; consume but don't re-bind
		hatCentered = false
		assignScanSwitch(code)
		cancelCapture()
		return true
	}

	private fun assignScanSwitch(keyCode: Int) {
		// No cross-method de-conflict: single- and two-switch are never active at once, so the same
		// code may be bound to both. Routing resolves the role by the active method.
		prefs.putInt(KEY_SCAN_SWITCH_CODE, keyCode)
		updateScanSwitchLabel(keyCode)
	}

	/**
	 * Physical bind, touchscreen, or both — they coexist. The red "no switch assigned"
	 * warning shows only when neither input can drive the switch.
	 */
	private fun updateScanSwitchLabel(keyCode: Int) {
		val touchSwitchEnabled = prefs.getBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)
		val assigned = keyCode != SWITCH_CODE_UNDEFINED
		clearScanSwitchButton.isEnabled = assigned
		clearScanSwitchButton.alpha = if (assigned) 1f else 0.4f
		if (!assigned && !touchSwitchEnabled) {
			scanSwitchLabel.text = getString(R.string.switch_unassigned_warning)
			scanSwitchLabel.setTextColor(ContextCompat.getColor(requireContext(), R.color.switch_unassigned_warning))
			return
		}
		scanSwitchLabel.text = when {
			!assigned -> getString(R.string.switch_configured_as, getString(R.string.touch_screen))
			touchSwitchEnabled -> getString(R.string.switch_configured_as_with_touch, switchLabelForKeyCode(keyCode))
			else -> getString(R.string.switch_configured_as, switchLabelForKeyCode(keyCode))
		}
		scanSwitchLabel.setTextColor(primaryTextColor)
	}

	/** Human-readable label for any key code; handles common keys and falls back to keyCodeToString. */
	private fun switchLabelForKeyCode(keyCode: Int): String {
		if (keyCode == SWITCH_CODE_UNDEFINED) return getString(R.string.scanning_switch_not_configured)
		return when (keyCode) {
			KeyEvent.KEYCODE_1 -> getString(R.string.switch_1)
			KeyEvent.KEYCODE_2 -> getString(R.string.switch_2)
			KeyEvent.KEYCODE_3 -> getString(R.string.switch_3)
			KeyEvent.KEYCODE_NUMPAD_1 -> getString(R.string.numpad_1)
			KeyEvent.KEYCODE_NUMPAD_2 -> getString(R.string.numpad_2)
			KeyEvent.KEYCODE_NUMPAD_3 -> getString(R.string.numpad_3)
			Constants.HEADBOARD_SWITCH_1_KEYCODE -> getString(R.string.headboard_switch_1)
			Constants.HEADBOARD_SWITCH_2_KEYCODE -> getString(R.string.headboard_switch_2)
			else -> {
				val raw = KeyEvent.keyCodeToString(keyCode)
				raw
					.removePrefix("KEYCODE_")
					.replace('_', ' ')
					.lowercase()
					.replaceFirstChar { it.uppercase() }
			}
		}
	}

	/** Runs [apply] live while dragging (persist=false, label only) and on release (persist=true). */
	private fun simpleSeek(apply: (progress: Int, persist: Boolean) -> Unit): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
		override fun onProgressChanged(
			seekBar: SeekBar?,
			progress: Int,
			fromUser: Boolean,
		) {
			apply(progress, false)
		}

		override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

		override fun onStopTrackingTouch(seekBar: SeekBar?) {
			apply(seekBar?.progress ?: 0, true)
		}
	}

	private fun updateShowNextEnabled(
		showNext: SwitchCompat,
		skipEnabled: Boolean,
	) {
		showNext.isEnabled = skipEnabled
		showNext.alpha = if (skipEnabled) 1.0f else 0.4f
	}

	private fun updateAutoRepeatEnabled(
		seek: SeekBar,
		valueLabel: TextView,
		enabled: Boolean,
	) {
		seek.isEnabled = enabled
		valueLabel.alpha = if (enabled) 1.0f else 0.4f
	}

	private fun formatSwitchDebounce(ms: Int): String = if (ms <= 0) getString(R.string.format_debounce_off) else getString(R.string.format_debounce_ms, ms)
}
