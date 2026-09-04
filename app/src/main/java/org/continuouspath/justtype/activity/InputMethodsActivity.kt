package org.continuouspath.justtype.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.net.toUri
import org.continuouspath.justtype.Constants.DEFAULT_ERROR_BEEP
import org.continuouspath.justtype.Constants.INPUT_METHOD_DIRECTIONAL_SELECTION
import org.continuouspath.justtype.Constants.INPUT_METHOD_DIRECT_SELECTION
import org.continuouspath.justtype.Constants.INPUT_METHOD_HEAD_TRACKING
import org.continuouspath.justtype.Constants.INPUT_METHOD_JOYSTICK
import org.continuouspath.justtype.Constants.INPUT_METHOD_MOUSE_JOYSTICK
import org.continuouspath.justtype.Constants.INPUT_METHOD_NONE
import org.continuouspath.justtype.Constants.INPUT_METHOD_SINGLE_SWITCH
import org.continuouspath.justtype.Constants.INPUT_METHOD_TWO_SWITCH
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_ERROR_BEEP
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED
import org.continuouspath.justtype.Constants.KEY_SPEAK_PUNCTUATION
import org.continuouspath.justtype.Constants.KEY_SPEAK_SELECTED_KEY
import org.continuouspath.justtype.Constants.KEY_SPEAK_SELECTED_WORD
import org.continuouspath.justtype.Constants.KEY_SPEAK_SETTINGS_PROMPTS
import org.continuouspath.justtype.Constants.KEY_TOUCH_OVERLAY_TIMEOUT_SEC
import org.continuouspath.justtype.Constants.KEY_VIBRATION_FEEDBACK
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getInt
import org.continuouspath.justtype.settings.getString

class InputMethodsActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	companion object {
		// HeadBoard package name for checking if it's installed
		private const val HEADBOARD_PACKAGE = "org.continuouspath.headboard"
	}

	private lateinit var setupTouchScreenMethodButton: Button
	private lateinit var setupOtherMethodButton: Button
	private lateinit var headTrackingCheckBox: CheckBox
	private lateinit var headTrackingContainer: LinearLayout
	private lateinit var headTrackingHint: TextView
	private var isHeadBoardInstalled: Boolean = false
	private lateinit var joystickCheckBox: CheckBox
	private lateinit var mouseJoystickCheckBox: CheckBox
	private lateinit var singleSwitchCheckBox: CheckBox
	private lateinit var twoSwitchCheckBox: CheckBox
	private lateinit var directionalSelectionCheckBox: CheckBox
	private lateinit var directSelectionCheckBox: CheckBox
	private lateinit var touchScreenSwitchCheckBox: CheckBox
	private lateinit var touchHeading: TextView
	private lateinit var otherHeading: TextView
	private lateinit var touchContainer: LinearLayout
	private lateinit var otherContainer: LinearLayout
	
	private var lastCheckedPrimaryMethod: CheckBox? = null
	private var lastCheckedTouchScreenMethod: CheckBox? = null

	/** Suppresses onInputMethodChanged() while reloading preferences into checkboxes. */
	private var isLoadingPreferences = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_input_methods)

		val prefs = SettingsRepository.getInstance(this)
		val backButton: ImageButton = findViewById(R.id.backButton)
		setupTouchScreenMethodButton = findViewById(R.id.setupTouchScreenMethodButton)
		setupOtherMethodButton = findViewById(R.id.setupOtherMethodButton)
		val beepKeyFeedbackSwitch: SwitchCompat = findViewById(R.id.beepKeyFeedbackSwitch)
		val beepKeyFeedbackLayout: LinearLayout = findViewById(R.id.beepKeyFeedbackLayout)
		val errorBeepSwitch: SwitchCompat = findViewById(R.id.errorBeepSwitch)
		val errorBeepLayout: LinearLayout = findViewById(R.id.errorBeepLayout)
		val vibrateKeyFeedbackSwitch: SwitchCompat = findViewById(R.id.vibrateKeyFeedbackSwitch)
		val vibrateKeyFeedbackLayout: LinearLayout = findViewById(R.id.vibrateKeyFeedbackLayout)
		val speakSelectedKeySwitch: SwitchCompat = findViewById(R.id.speakSelectedKeySwitch)
		val speakSelectedKeyLayout: LinearLayout = findViewById(R.id.speakSelectedKeyLayout)
		val speakPunctuationSwitch: SwitchCompat = findViewById(R.id.speakPunctuationSwitch)
		val speakPunctuationLayout: LinearLayout = findViewById(R.id.speakPunctuationLayout)
		val speakSelectedWordSwitch: SwitchCompat = findViewById(R.id.speakSelectedWordSwitch)
		val speakSelectedWordLayout: LinearLayout = findViewById(R.id.speakSelectedWordLayout)
		val speakSettingsPromptsSwitch: SwitchCompat = findViewById(R.id.speakSettingsPromptsSwitch)
		val speakSettingsPromptsLayout: LinearLayout = findViewById(R.id.speakSettingsPromptsLayout)
		val flashKeyFeedbackSwitch: SwitchCompat = findViewById(R.id.flashKeyFeedbackSwitch)
		val flashKeyFeedbackLayout: LinearLayout = findViewById(R.id.flashKeyFeedbackLayout)
		val touchOverlayTimeoutSeek: SeekBar = findViewById(R.id.touchOverlayTimeoutSeek)
		val touchOverlayTimeoutValue: TextView = findViewById(R.id.touchOverlayTimeoutValue)

		touchHeading = findViewById(R.id.touchInputHeading)
		otherHeading = findViewById(R.id.otherInputHeading)
		touchContainer = findViewById(R.id.touchInputContainer)
		otherContainer = findViewById(R.id.otherInputContainer)
		headTrackingCheckBox = findViewById(R.id.inputMethodHeadTracking)
		headTrackingContainer = findViewById(R.id.headTrackingContainer)
		headTrackingHint = findViewById(R.id.headTrackingHint)
		joystickCheckBox = findViewById(R.id.inputMethodJoystick)
		mouseJoystickCheckBox = findViewById(R.id.inputMethodMouseJoystick)
		singleSwitchCheckBox = findViewById(R.id.inputMethodSingleSwitch)
		twoSwitchCheckBox = findViewById(R.id.inputMethodTwoSwitch)
		directionalSelectionCheckBox = findViewById(R.id.inputMethodDirectionalSelection)
		directSelectionCheckBox = findViewById(R.id.inputMethodDirectSelection)
		touchScreenSwitchCheckBox = findViewById(R.id.inputMethodTouchScreenSwitch)

		backButton.setOnClickListener { finish() }
		
		// Check if HeadBoard is installed and disable head tracking option if not
		isHeadBoardInstalled = isPackageInstalled(HEADBOARD_PACKAGE)
		updateHeadTrackingAvailability()

		// Load saved preferences
		loadInputMethodPreferences(prefs)
		
		// Set up listeners for all checkboxes
		headTrackingCheckBox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) lastCheckedPrimaryMethod = headTrackingCheckBox
			onInputMethodChanged()
		}
		joystickCheckBox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) lastCheckedPrimaryMethod = joystickCheckBox
			onInputMethodChanged()
		}
		mouseJoystickCheckBox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) lastCheckedPrimaryMethod = mouseJoystickCheckBox
			onInputMethodChanged()
		}
		singleSwitchCheckBox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) lastCheckedPrimaryMethod = singleSwitchCheckBox
			onInputMethodChanged()
		}
		twoSwitchCheckBox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) lastCheckedPrimaryMethod = twoSwitchCheckBox
			onInputMethodChanged()
		}
		directionalSelectionCheckBox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) lastCheckedTouchScreenMethod = directionalSelectionCheckBox
			onInputMethodChanged()
		}
		directSelectionCheckBox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) lastCheckedTouchScreenMethod = directSelectionCheckBox
			onInputMethodChanged()
		}
		touchScreenSwitchCheckBox.setOnCheckedChangeListener { _, isChecked ->
			if (isChecked) lastCheckedTouchScreenMethod = touchScreenSwitchCheckBox
			onInputMethodChanged()
		}

		// Touch screen method setup button
		setupTouchScreenMethodButton.setOnClickListener {
			val currentPrefs = SettingsRepository.getInstance(this)
			val directionalEnabled = currentPrefs.getBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED)
			val directEnabled = currentPrefs.getBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED)
			val touchSwitchEnabled = currentPrefs.getBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)
			
			when {
				directionalEnabled -> startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_DIRECTIONAL_SELECTION))
				directEnabled -> startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_DIRECT_SELECTION))
				touchSwitchEnabled -> startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_TOUCH_SCREEN_SWITCH))
			}
		}
		
		// Other input method setup button
		setupOtherMethodButton.setOnClickListener {
			val currentPrefs = SettingsRepository.getInstance(this)
			val primaryMethod = currentPrefs.getString(KEY_INPUT_METHOD_PRIMARY)
			
			when (primaryMethod) {
				INPUT_METHOD_JOYSTICK -> startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_JOYSTICK))
				INPUT_METHOD_MOUSE_JOYSTICK -> startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_MOUSE_JOYSTICK))
				INPUT_METHOD_SINGLE_SWITCH -> startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_SINGLE_SWITCH))
				INPUT_METHOD_TWO_SWITCH -> startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_TWO_SWITCH))
				INPUT_METHOD_HEAD_TRACKING -> startActivity(SetupHostActivity.intent(this, SetupHostActivity.TARGET_HEAD_TRACKING))
			}
		}

		// Load feedback prefs
		beepKeyFeedbackSwitch.isChecked = prefs.getBoolean(KEY_BEEP_KEY_FEEDBACK)
		errorBeepSwitch.isChecked = prefs.getBoolean(KEY_ERROR_BEEP, DEFAULT_ERROR_BEEP)
		vibrateKeyFeedbackSwitch.isChecked = prefs.getBoolean(KEY_VIBRATION_FEEDBACK)
		speakSelectedKeySwitch.isChecked = prefs.getBoolean(KEY_SPEAK_SELECTED_KEY)
		speakSelectedWordSwitch.isChecked = prefs.getBoolean(KEY_SPEAK_SELECTED_WORD)
		val speakPunc = prefs.getBoolean(KEY_SPEAK_PUNCTUATION)
		speakPunctuationSwitch.isChecked = speakSelectedKeySwitch.isChecked && speakPunc
		speakPunctuationSwitch.isEnabled = speakSelectedKeySwitch.isChecked
		flashKeyFeedbackSwitch.isChecked = prefs.getBoolean(KEY_FLASH_KEY_FEEDBACK)
		speakSettingsPromptsSwitch.isChecked = prefs.getBoolean(KEY_SPEAK_SETTINGS_PROMPTS)

		// Touch overlay timeout slider
		val savedTimeoutSec = prefs.getInt(KEY_TOUCH_OVERLAY_TIMEOUT_SEC).coerceIn(2, 10)
		touchOverlayTimeoutSeek.max = 8 // 2..10 => range 8
		touchOverlayTimeoutSeek.progress = (savedTimeoutSec - 2).coerceIn(0, 8)
		touchOverlayTimeoutValue.text = getString(R.string.format_timeout_sec, savedTimeoutSec)
		touchOverlayTimeoutSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val timeoutSec = (progress + 2).coerceIn(2, 10)
					touchOverlayTimeoutValue.text = getString(R.string.format_timeout_sec, timeoutSec)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val progress = touchOverlayTimeoutSeek.progress
					val timeoutSec = (progress + 2).coerceIn(2, 10)
					prefs.putInt(KEY_TOUCH_OVERLAY_TIMEOUT_SEC, timeoutSec)
				}
			},
		)

		beepKeyFeedbackSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_BEEP_KEY_FEEDBACK, isChecked)
		}
		beepKeyFeedbackLayout.setOnClickListener {
			beepKeyFeedbackSwitch.isChecked = !beepKeyFeedbackSwitch.isChecked
		}

		errorBeepSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_ERROR_BEEP, isChecked)
		}
		errorBeepLayout.setOnClickListener {
			errorBeepSwitch.isChecked = !errorBeepSwitch.isChecked
		}
		vibrateKeyFeedbackSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_VIBRATION_FEEDBACK, isChecked)
		}
		vibrateKeyFeedbackLayout.setOnClickListener {
			vibrateKeyFeedbackSwitch.isChecked = !vibrateKeyFeedbackSwitch.isChecked
		}

		speakSelectedKeySwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SPEAK_SELECTED_KEY, isChecked)
			speakPunctuationSwitch.isEnabled = isChecked
			if (!isChecked) {
				speakPunctuationSwitch.isChecked = false
				prefs.putBoolean(KEY_SPEAK_PUNCTUATION, false)
			} else {
				speakPunctuationSwitch.isChecked = prefs.getBoolean(KEY_SPEAK_PUNCTUATION)
			}
		}
		speakSelectedKeyLayout.setOnClickListener {
			speakSelectedKeySwitch.isChecked = !speakSelectedKeySwitch.isChecked
		}

		speakPunctuationSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SPEAK_PUNCTUATION, isChecked)
		}
		speakPunctuationLayout.setOnClickListener {
			if (speakSelectedKeySwitch.isChecked) {
				speakPunctuationSwitch.isChecked = !speakPunctuationSwitch.isChecked
			}
		}

		speakSelectedWordSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SPEAK_SELECTED_WORD, isChecked)
		}
		speakSelectedWordLayout.setOnClickListener {
			speakSelectedWordSwitch.isChecked = !speakSelectedWordSwitch.isChecked
		}

		flashKeyFeedbackSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_FLASH_KEY_FEEDBACK, isChecked)
		}
		flashKeyFeedbackLayout.setOnClickListener {
			flashKeyFeedbackSwitch.isChecked = !flashKeyFeedbackSwitch.isChecked
		}

		speakSettingsPromptsSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SPEAK_SETTINGS_PROMPTS, isChecked)
		}
		speakSettingsPromptsLayout.setOnClickListener {
			speakSettingsPromptsSwitch.isChecked = !speakSettingsPromptsSwitch.isChecked
		}

		// Phase 3B: attach INFO PROMPT icons. SettingsInfoHelper handles both
		// SwitchCompat-style rows and CompoundButton-with-inline-text rows
		// (CheckBox/Switch/ToggleButton — see helper's CompoundButton branch),
		// so the input-method CheckBox rows below are wired the same way as
		// the SwitchCompat rows.
		val rootForInfoSearch: ViewGroup = findViewById(android.R.id.content)
		// Phase 3D info-icon sweep: section heading + 3 CheckBox rows that
		// previously had no info icons.
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_touch_heading,
			R.string.info_prompt_section_touchscreen_input_methods,
		)
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_direct_selection,
			R.string.info_prompt_direct_selection,
		)
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_directional_selection,
			R.string.info_prompt_directional_selection,
		)
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_touch_screen_switch,
			R.string.info_prompt_im_touchscreen_switch,
		)
		// Adaptive Input Methods section heading + 4 method rows.
		// Resource id `info_prompt_alternative_input_method` retains its
		// pre-rename name to avoid breaking sibling locale resource files;
		// only the displayed string value is the new "Adaptive" wording.
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_other_heading,
			R.string.info_prompt_alternative_input_method,
		)
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_head_tracking,
			R.string.info_prompt_im_head_tracking,
		)
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_joystick,
			R.string.info_prompt_im_joystick,
		)
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_mouse_joystick,
			R.string.info_prompt_im_joystick,
		)
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_single_switch,
			R.string.info_prompt_im_single_switch,
		)
		SettingsInfoHelper.attachInfoIcon(
			rootForInfoSearch,
			R.string.input_methods_two_switch,
			R.string.info_prompt_im_two_switch,
		)
		SettingsInfoHelper.attachInfoIcon(
			beepKeyFeedbackLayout,
			R.string.input_methods_beep_key,
			R.string.info_prompt_beep_on_key_select,
		)
		SettingsInfoHelper.attachInfoIcon(
			errorBeepLayout,
			R.string.input_methods_error_beep,
			R.string.info_prompt_error_beep,
		)
		SettingsInfoHelper.attachInfoIcon(
			vibrateKeyFeedbackLayout,
			R.string.sr_input_methods_vibration_feedback,
			R.string.info_prompt_vibration_feedback,
		)
		SettingsInfoHelper.attachInfoIcon(
			speakSelectedKeyLayout,
			R.string.input_methods_speak_key,
			R.string.info_prompt_speak_selected_key,
		)
		SettingsInfoHelper.attachInfoIcon(
			speakPunctuationLayout,
			R.string.input_methods_speak_punct,
			R.string.info_prompt_speak_punctuation,
		)
		SettingsInfoHelper.attachInfoIcon(
			speakSelectedWordLayout,
			R.string.input_methods_speak_word,
			R.string.info_prompt_speak_selected_word,
		)
		SettingsInfoHelper.attachInfoIcon(
			speakSettingsPromptsLayout,
			R.string.input_methods_speak_prompts,
			R.string.info_prompt_speak_prompts_aloud,
		)
		SettingsInfoHelper.attachInfoIcon(
			flashKeyFeedbackLayout,
			R.string.input_methods_flash_key,
			R.string.info_prompt_flash_key_feedback,
		)
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}

	override fun onResume() {
		super.onResume()
		// Reload preferences to reflect changes made via Settings Mode (keyboard-driven)
		val prefs = SettingsRepository.getInstance(this)
		loadInputMethodPreferences(prefs)
	}

	private fun loadInputMethodPreferences(prefs: SettingsRepository) {
		isLoadingPreferences = true
		try {
			loadInputMethodPreferencesInner(prefs)
		} finally {
			isLoadingPreferences = false
		}
	}

	private fun loadInputMethodPreferencesInner(prefs: SettingsRepository) {
		val primaryMethod = prefs.getString(KEY_INPUT_METHOD_PRIMARY)
		val directionalEnabled = prefs.getBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED)
		// Direct selection defaults to true (set by ensureDefaults)
		val directEnabled = prefs.getBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED)
		val touchSwitchEnabled = prefs.getBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)

		var needsSave = false
		when (primaryMethod) {
			INPUT_METHOD_JOYSTICK -> {
				joystickCheckBox.isChecked = true
				lastCheckedPrimaryMethod = joystickCheckBox
			}
			INPUT_METHOD_MOUSE_JOYSTICK -> {
				mouseJoystickCheckBox.isChecked = true
				lastCheckedPrimaryMethod = mouseJoystickCheckBox
			}
			INPUT_METHOD_SINGLE_SWITCH -> {
				singleSwitchCheckBox.isChecked = true
				lastCheckedPrimaryMethod = singleSwitchCheckBox
			}
			INPUT_METHOD_TWO_SWITCH -> {
				twoSwitchCheckBox.isChecked = true
				lastCheckedPrimaryMethod = twoSwitchCheckBox
			}
			INPUT_METHOD_HEAD_TRACKING -> {
				// Only enable head tracking if HeadBoard is installed
				if (isHeadBoardInstalled) {
					headTrackingCheckBox.isChecked = true
					lastCheckedPrimaryMethod = headTrackingCheckBox
				} else {
					// Fall back to direct selection if HeadBoard was uninstalled
					directSelectionCheckBox.isChecked = true
					lastCheckedTouchScreenMethod = directSelectionCheckBox
					lastCheckedPrimaryMethod = null
					needsSave = true
				}
			}
			else -> {
				lastCheckedPrimaryMethod = null
			}
		}
		directionalSelectionCheckBox.isChecked = directionalEnabled
		directSelectionCheckBox.isChecked = directEnabled
		touchScreenSwitchCheckBox.isChecked = touchSwitchEnabled
		
		// Set last checked touch screen method (only if not already set during fallback)
		if (lastCheckedTouchScreenMethod == null) {
			lastCheckedTouchScreenMethod =
				when {
					directionalEnabled -> directionalSelectionCheckBox
					directEnabled -> directSelectionCheckBox
					touchSwitchEnabled -> touchScreenSwitchCheckBox
					else -> null
				}
		}
		
		// Save preferences if we had to fall back from head tracking
		if (needsSave) {
			saveInputMethodPreferences()
		}
		
		val effectivePrimaryMethod = if (needsSave) INPUT_METHOD_NONE else primaryMethod
		updateSetupButtonLabel(effectivePrimaryMethod)
		updateCheckboxVisualStates()
	}

	private fun onInputMethodChanged() {
		if (isLoadingPreferences) return
		// Validate and enforce selection rules
		validateAndEnforceRules()
		
		// Save preferences
		saveInputMethodPreferences()
		
		// Update setup button
		val prefs = SettingsRepository.getInstance(this)
		val primaryMethod = prefs.getString(KEY_INPUT_METHOD_PRIMARY)
		updateSetupButtonLabel(primaryMethod)
		updateCheckboxVisualStates()
		
		// Check for overlay permission if directional selection or mouse joystick is enabled
		if (directionalSelectionCheckBox.isChecked || mouseJoystickCheckBox.isChecked) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				if (!Settings.canDrawOverlays(this)) {
					requestOverlayPermission()
				}
			}
		}
	}

	private fun validateAndEnforceRules() {
		val touchScreenMethods =
			listOf(
				directSelectionCheckBox,
				directionalSelectionCheckBox,
				touchScreenSwitchCheckBox,
			)
		
		val otherInputMethods =
			listOf(
				headTrackingCheckBox,
				joystickCheckBox,
				mouseJoystickCheckBox,
				singleSwitchCheckBox,
				twoSwitchCheckBox,
			)
		
		val allMethods = touchScreenMethods + otherInputMethods
		
		val selectedTouchScreen = touchScreenMethods.filter { it.isChecked }
		val selectedOther = otherInputMethods.filter { it.isChecked }
		
		// Rule 0: Directional Selection and Single Switch Scanning are incompatible
		// Check this FIRST before other rules to prevent both from being selected
		if (directionalSelectionCheckBox.isChecked && singleSwitchCheckBox.isChecked) {
			// Determine which one was just checked based on lastChecked tracking
			val directionalJustChecked = lastCheckedTouchScreenMethod == directionalSelectionCheckBox
			val sssJustChecked = lastCheckedPrimaryMethod == singleSwitchCheckBox
			
			if (directionalJustChecked && !sssJustChecked) {
				// Directional was just checked, uncheck SSS
				setCheckboxCheckedWithoutTriggeringListener(singleSwitchCheckBox, false)
				lastCheckedPrimaryMethod = null
			} else if (sssJustChecked && !directionalJustChecked) {
				// SSS was just checked, uncheck Directional and default to Direct
				setCheckboxCheckedWithoutTriggeringListener(directionalSelectionCheckBox, false)
				setCheckboxCheckedWithoutTriggeringListener(directSelectionCheckBox, true)
				lastCheckedTouchScreenMethod = directSelectionCheckBox
			} else {
				// Can't determine which was just checked, default to unchecking Directional
				setCheckboxCheckedWithoutTriggeringListener(directionalSelectionCheckBox, false)
				setCheckboxCheckedWithoutTriggeringListener(directSelectionCheckBox, true)
				lastCheckedTouchScreenMethod = directSelectionCheckBox
			}
			// Re-validate after making changes to ensure other rules are applied
			validateAndEnforceRules()
			return
		}
		
		// Rule 1: Only one touch screen method can be selected at a time (mutually exclusive)
		if (selectedTouchScreen.size > 1) {
			// Keep the last one that was checked, uncheck others
			val methodToKeep = lastCheckedTouchScreenMethod ?: selectedTouchScreen.first()
			touchScreenMethods.forEach { checkbox ->
				if (checkbox != methodToKeep && checkbox.isChecked) {
					setCheckboxCheckedWithoutTriggeringListener(checkbox, false)
				}
			}
			lastCheckedTouchScreenMethod = methodToKeep
			validateAndEnforceRules()
			return
		} else if (selectedTouchScreen.size == 1) {
			lastCheckedTouchScreenMethod = selectedTouchScreen.first()
		} else {
			lastCheckedTouchScreenMethod = null
		}

		// Rule 2: Only one other input method can be selected at a time (mutually exclusive)
		if (selectedOther.size > 1) {
			// Keep the last one that was checked, uncheck others
			val methodToKeep = lastCheckedPrimaryMethod ?: selectedOther.first()
			otherInputMethods.forEach { checkbox ->
				if (checkbox != methodToKeep && checkbox.isChecked) {
					setCheckboxCheckedWithoutTriggeringListener(checkbox, false)
				}
			}
			lastCheckedPrimaryMethod = methodToKeep
			validateAndEnforceRules()
			return
		} else if (selectedOther.size == 1) {
			lastCheckedPrimaryMethod = selectedOther.first()
		} else {
			lastCheckedPrimaryMethod = null
		}

		// Rule 3: At least one option total must be selected (can't have both sections empty)
		val anySelected = allMethods.any { it.isChecked }
		if (!anySelected) {
			// Default to Direct Selection
			setCheckboxCheckedWithoutTriggeringListener(directSelectionCheckBox, true)
			lastCheckedTouchScreenMethod = directSelectionCheckBox
			validateAndEnforceRules()
			return
		}

		// Rule 4: Touch Screen Switch is a sub-mode of Single-Switch / Two-Switch.
		// Listeners normally keep the invariant; this catches the case where Rule 2
		// dropped the SSS/TS primary because the user picked an incompatible one.
		if (touchScreenSwitchCheckBox.isChecked &&
			!singleSwitchCheckBox.isChecked &&
			!twoSwitchCheckBox.isChecked
		) {
			setCheckboxCheckedWithoutTriggeringListener(touchScreenSwitchCheckBox, false)
			setCheckboxCheckedWithoutTriggeringListener(directSelectionCheckBox, true)
			lastCheckedTouchScreenMethod = directSelectionCheckBox
			validateAndEnforceRules()
			return
		}

		updateCheckboxVisualStates()
	}

	private fun setCheckboxCheckedWithoutTriggeringListener(
		checkbox: CheckBox,
		checked: Boolean,
	) {
		checkbox.setOnCheckedChangeListener(null)
		checkbox.isChecked = checked
		// Re-attach the appropriate listener
		when (checkbox) {
			headTrackingCheckBox ->
				headTrackingCheckBox.setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) lastCheckedPrimaryMethod = headTrackingCheckBox
					onInputMethodChanged()
				}
			joystickCheckBox ->
				joystickCheckBox.setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) lastCheckedPrimaryMethod = joystickCheckBox
					onInputMethodChanged()
				}
			mouseJoystickCheckBox ->
				mouseJoystickCheckBox.setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) lastCheckedPrimaryMethod = mouseJoystickCheckBox
					onInputMethodChanged()
				}
			singleSwitchCheckBox ->
				singleSwitchCheckBox.setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) lastCheckedPrimaryMethod = singleSwitchCheckBox
					onInputMethodChanged()
				}
			twoSwitchCheckBox ->
				twoSwitchCheckBox.setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) lastCheckedPrimaryMethod = twoSwitchCheckBox
					onInputMethodChanged()
				}
			directionalSelectionCheckBox ->
				directionalSelectionCheckBox.setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) lastCheckedTouchScreenMethod = directionalSelectionCheckBox
					onInputMethodChanged()
				}
			directSelectionCheckBox ->
				directSelectionCheckBox.setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) lastCheckedTouchScreenMethod = directSelectionCheckBox
					onInputMethodChanged()
				}
			touchScreenSwitchCheckBox ->
				touchScreenSwitchCheckBox.setOnCheckedChangeListener { _, isChecked ->
					if (isChecked) lastCheckedTouchScreenMethod = touchScreenSwitchCheckBox
					onInputMethodChanged()
				}
		}
	}

	private fun saveInputMethodPreferences() {
		val prefs = SettingsRepository.getInstance(this)
		// Determine primary method
		val primaryMethod =
			when {
				headTrackingCheckBox.isChecked -> INPUT_METHOD_HEAD_TRACKING
				joystickCheckBox.isChecked -> INPUT_METHOD_JOYSTICK
				mouseJoystickCheckBox.isChecked -> INPUT_METHOD_MOUSE_JOYSTICK
				singleSwitchCheckBox.isChecked -> INPUT_METHOD_SINGLE_SWITCH
				twoSwitchCheckBox.isChecked -> INPUT_METHOD_TWO_SWITCH
				else -> INPUT_METHOD_NONE
			}

		prefs.putString(KEY_INPUT_METHOD_PRIMARY, primaryMethod)
		prefs.putBoolean(
			KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED,
			directionalSelectionCheckBox.isChecked,
		)
		prefs.putBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, directSelectionCheckBox.isChecked)
		prefs.putBoolean(
			KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED,
			touchScreenSwitchCheckBox.isChecked,
		)

		// For backwards compatibility (HeadBoard reads KEY_INPUT_METHOD), mirror to the legacy key.
		// Rule 4 guarantees touchScreenSwitch implies SSS or TS, so primaryMethod != NONE in that case.
		val backwardCompatMethod =
			when {
				primaryMethod != INPUT_METHOD_NONE -> primaryMethod
				directionalSelectionCheckBox.isChecked -> INPUT_METHOD_DIRECTIONAL_SELECTION
				else -> INPUT_METHOD_DIRECT_SELECTION
			}
		prefs.putString(KEY_INPUT_METHOD, backwardCompatMethod)
	}

	private fun updateCheckboxVisualStates() {
		// Disabled checkboxes don't fire listeners on tap, so dependency rules
		// (Directional ⊕ SSS, TSS requires SSS/TS) can't be violated by user input.
		fun setEnabled(cb: CheckBox, enabled: Boolean) {
			cb.isEnabled = enabled
			cb.alpha = if (enabled) 1.0f else 0.4f
		}

		val directionalSelected = directionalSelectionCheckBox.isChecked
		val sssSelected = singleSwitchCheckBox.isChecked
		val tsSelected = twoSwitchCheckBox.isChecked

		// Directional Selection and Single Switch Scanning are incompatible.
		setEnabled(directionalSelectionCheckBox, !sssSelected)
		setEnabled(singleSwitchCheckBox, !directionalSelected)

		// Touch Screen Switch is a sub-mode of SSS/TS — gated on one being selected.
		setEnabled(touchScreenSwitchCheckBox, sssSelected || tsSelected)

		directSelectionCheckBox.alpha = 1.0f
		joystickCheckBox.alpha = 1.0f
		mouseJoystickCheckBox.alpha = 1.0f
		twoSwitchCheckBox.alpha = 1.0f
		// headTrackingCheckBox state is managed by updateHeadTrackingAvailability().
	}

	private fun updateSetupButtonLabel(primaryMethod: String) {
		val prefs = SettingsRepository.getInstance(this)
		
		// Update touch screen method setup button
		val directionalEnabled = prefs.getBoolean(KEY_INPUT_METHOD_DIRECTIONAL_SELECTION_ENABLED)
		val directEnabled = prefs.getBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED)
		val touchSwitchEnabled = prefs.getBoolean(KEY_INPUT_METHOD_TOUCH_SCREEN_SWITCH_ENABLED)
		
		val touchLabel =
			when {
				directionalEnabled -> getString(R.string.setup_directional_selection)
				directEnabled -> getString(R.string.setup_direct_selection)
				touchSwitchEnabled -> getString(R.string.setup_touch_screen_switch)
				else -> null
			}
		
		if (touchLabel != null) {
			setupTouchScreenMethodButton.visibility = View.VISIBLE
			setupTouchScreenMethodButton.text = touchLabel
		} else {
			setupTouchScreenMethodButton.visibility = View.GONE
		}
		
		// Update other input method setup button
		if (primaryMethod != INPUT_METHOD_NONE) {
			val otherLabel =
				when (primaryMethod) {
					INPUT_METHOD_JOYSTICK -> getString(R.string.setup_joystick)
					INPUT_METHOD_MOUSE_JOYSTICK -> getString(R.string.setup_mouse_joystick)
					INPUT_METHOD_SINGLE_SWITCH -> getString(R.string.setup_single_switch_scanning)
					INPUT_METHOD_TWO_SWITCH -> getString(R.string.setup_two_switch_selection)
					INPUT_METHOD_HEAD_TRACKING -> getString(R.string.setup_head_tracking)
					else -> null
				}
			if (otherLabel != null) {
				setupOtherMethodButton.visibility = View.VISIBLE
				setupOtherMethodButton.text = otherLabel
			} else {
				setupOtherMethodButton.visibility = View.GONE
			}
		} else {
			setupOtherMethodButton.visibility = View.GONE
		}
	}

	/**
	 * Requests SYSTEM_ALERT_WINDOW permission by opening system settings.
	 */
	private fun requestOverlayPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			val intent =
				Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
					data = "package:$packageName".toUri()
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				}
			try {
				startActivity(intent)
			} catch (_: Exception) {
				// Permission request failed, but that's okay
			}
		}
	}

	/**
	 * Check if a package is installed on the device.
	 */
	private fun isPackageInstalled(packageName: String): Boolean = try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
		} else {
			@Suppress("DEPRECATION")
			packageManager.getPackageInfo(packageName, 0)
		}
		true
	} catch (_: PackageManager.NameNotFoundException) {
		false
	}

	/**
	 * Update the head tracking checkbox availability based on HeadBoard installation status.
	 */
	private fun updateHeadTrackingAvailability() {
		if (isHeadBoardInstalled) {
			// HeadBoard is installed - enable head tracking option
			headTrackingCheckBox.isEnabled = true
			headTrackingCheckBox.alpha = 1.0f
			headTrackingHint.visibility = View.GONE
		} else {
			// HeadBoard is not installed - disable and grey out head tracking option
			headTrackingCheckBox.isEnabled = false
			headTrackingCheckBox.isChecked = false
			headTrackingCheckBox.alpha = 0.5f
			headTrackingHint.visibility = View.VISIBLE
		}
	}
}
