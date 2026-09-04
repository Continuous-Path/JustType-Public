package org.continuouspath.justtype.activity

import android.os.Bundle
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.Constants.KEY_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACCEPT_ANY
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEADZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEVICE_DESCRIPTOR
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEVICE_NAME
import org.continuouspath.justtype.R
import org.continuouspath.justtype.input.InputCaptureGate
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getFloat
import kotlin.math.max

class SetupJoystickFragment :
	Fragment(),
	MotionEventInterceptor {

	private var selectButton: Button? = null
	private var selectedLabel: TextView? = null
	private var waitingForDevice = false
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.activity_setup_joystick, container, false)

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val repo = SettingsRepository.get()

		val backButton: ImageButton = view.findViewById(R.id.backButton)
		val cornerBiasSeek: SeekBar = view.findViewById(R.id.cornerBiasSeek)
		val cornerBiasValue: TextView = view.findViewById(R.id.cornerBiasValue)
		val deadZoneSeek: SeekBar = view.findViewById(R.id.joystickDeadZoneSeek)
		val deadZoneValue: TextView = view.findViewById(R.id.joystickDeadZoneValue)
		val activeZoneSeek: SeekBar = view.findViewById(R.id.joystickActiveZoneSeek)
		val activeZoneValue: TextView = view.findViewById(R.id.joystickActiveZoneValue)

		backButton.setOnClickListener {
			parentFragmentManager.popBackStack()
			activity?.takeIf { !it.isFinishing }?.finish()
		}

		val selectBtn: Button = view.findViewById(R.id.selectJoystickButton)
		val selectedText: TextView = view.findViewById(R.id.selectedJoystickLabel)
		val acceptAnyToggle: SwitchCompat = view.findViewById(R.id.acceptAnyJoystickToggle)
		selectButton = selectBtn
		selectedLabel = selectedText

		fun renderSelectedLabel() {
			val name = repo.getString(KEY_JOYSTICK_DEVICE_NAME, "")
			selectedText.text = if (name.isEmpty()) {
				getString(R.string.setup_joy_device_none)
			} else {
				getString(R.string.setup_joy_device_selected, name)
			}
		}

		fun applyAcceptAnyState(acceptAny: Boolean) {
			// Selecting a specific device is only meaningful when not accepting any.
			selectBtn.isEnabled = !acceptAny
			selectBtn.alpha = if (acceptAny) DISABLED_ALPHA else 1f
		}

		val acceptAnySaved = repo.getBoolean(KEY_JOYSTICK_ACCEPT_ANY, true)
		acceptAnyToggle.isChecked = acceptAnySaved
		applyAcceptAnyState(acceptAnySaved)
		renderSelectedLabel()

		acceptAnyToggle.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_JOYSTICK_ACCEPT_ANY, isChecked)
			if (isChecked && waitingForDevice) {
				waitingForDevice = false
				InputCaptureGate.end(repo)
			}
			applyAcceptAnyState(isChecked)
		}

		selectBtn.setOnClickListener {
			waitingForDevice = true
			InputCaptureGate.begin(repo) // suppress live IME/Nav input during capture
			selectedText.text = getString(R.string.setup_joy_selecting_device)
		}

		val savedCornerBias =
			if (repo.contains(KEY_JOYSTICK_CORNER_BIAS)) {
				repo.getFloat(KEY_JOYSTICK_CORNER_BIAS)
			} else {
				val legacy = repo.getFloat(KEY_CORNER_BIAS, 1.35f)
				repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, legacy)
				legacy
			}.coerceIn(0.5f, 2.0f)
		cornerBiasSeek.max = 150
		cornerBiasSeek.progress = ((savedCornerBias - 0.5f) * 100f).toInt().coerceIn(0, 150)
		cornerBiasValue.text = getString(R.string.format_decimal_two, savedCornerBias)

		val savedDeadZone = repo.getFloat(KEY_JOYSTICK_DEADZONE).coerceIn(0.1f, 0.8f)
		deadZoneSeek.max = 70
		deadZoneSeek.progress = ((savedDeadZone - 0.1f) * 100).toInt().coerceIn(0, 70)
		deadZoneValue.text = getString(R.string.format_decimal_two, savedDeadZone)

		// Phase 3D (Δ-13): Active Zone capped at 0.90 (slider max progress
		// 80 = 0.10 + 0.80). Was 0.99 / progress max 89.
		val savedActiveZoneRaw = repo.getFloat(KEY_JOYSTICK_ACTIVEZONE)
		val savedActiveZone = max(savedActiveZoneRaw, savedDeadZone + 0.01f).coerceAtMost(0.90f)
		activeZoneSeek.max = 80
		activeZoneSeek.progress = ((savedActiveZone - 0.1f) * 100).toInt().coerceIn(0, 80)
		activeZoneValue.text = getString(R.string.format_decimal_two, savedActiveZone)

		cornerBiasSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val bias = 0.5f + (progress.toFloat() / 100f)
					cornerBiasValue.text = getString(R.string.format_decimal_two, bias)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val bias = (0.5f + (cornerBiasSeek.progress.toFloat() / 100f)).coerceIn(0.5f, 2.0f)
					repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, bias)
				}
			},
		)

		deadZoneSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val dz = 0.1f + (progress.toFloat() / 100f)
					deadZoneValue.text = getString(R.string.format_decimal_two, dz)
					val currentActive = 0.1f + (activeZoneSeek.progress.toFloat() / 100f)
					if (currentActive <= dz) {
						val newActive = (dz + 0.01f).coerceAtMost(0.90f)
						activeZoneSeek.progress = ((newActive - 0.1f) * 100).toInt().coerceIn(0, 80)
						activeZoneValue.text = getString(R.string.format_decimal_two, newActive)
					}
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val p = seekBar?.progress ?: 15
					val dz = 0.1f + (p.toFloat() / 100f)
					repo.putFloat(KEY_JOYSTICK_DEADZONE, dz)
					val currentActive = 0.1f + (activeZoneSeek.progress.toFloat() / 100f)
					val adjActive = max(currentActive, dz + 0.01f).coerceAtMost(0.90f)
					activeZoneSeek.progress = ((adjActive - 0.1f) * 100).toInt().coerceIn(0, 80)
					repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, adjActive)
				}
			},
		)

		activeZoneSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val dz = 0.1f + (deadZoneSeek.progress.toFloat() / 100f)
					var az = 0.1f + (progress.toFloat() / 100f)
					if (az <= dz) az = (dz + 0.01f).coerceAtMost(0.90f)
					activeZoneValue.text = getString(R.string.format_decimal_two, az)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val dz = 0.1f + (deadZoneSeek.progress.toFloat() / 100f)
					var az = 0.1f + ((seekBar?.progress ?: 50).toFloat() / 100f)
					if (az <= dz) az = (dz + 0.01f).coerceAtMost(0.90f)
					repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, az)
					activeZoneValue.text = getString(R.string.format_decimal_two, az)
				}
			},
		)

		// Phase 3B: attach INFO PROMPT icons.
		val root = view as ViewGroup
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.label_corner_bias,
			R.string.info_prompt_joy_corner_weighting,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_joy_dead_zone,
			R.string.info_prompt_joy_resting_zone,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_joy_active_zone,
			R.string.info_prompt_joy_active_zone,
		)
	}

	/** Capture the device of the next joystick motion while waiting for a selection. */
	override fun interceptMotionEvent(event: MotionEvent): Boolean {
		if (!waitingForDevice) return false
		val src = event.source
		val isJoystick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
			(src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
		if (!isJoystick) return false
		val device = event.device ?: return false
		val descriptor = device.descriptor ?: return false

		waitingForDevice = false
		val repo = SettingsRepository.get()
		repo.edit()
			.putString(KEY_JOYSTICK_DEVICE_DESCRIPTOR, descriptor)
			.putString(KEY_JOYSTICK_DEVICE_NAME, device.name ?: descriptor)
			.apply()
		InputCaptureGate.end(repo)
		selectedLabel?.text = getString(R.string.setup_joy_device_selected, device.name ?: descriptor)
		return true
	}

	override fun onPause() {
		super.onPause()
		// Failsafe: never leave live input suppressed if we leave mid-capture.
		waitingForDevice = false
		InputCaptureGate.end(SettingsRepository.get())
		SettingsSpeechController.stop()
	}

	private companion object {
		private const val DISABLED_ALPHA = 0.4f
	}
}
