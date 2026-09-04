package org.continuouspath.justtype.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_DEADZONE
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_SENSITIVITY_DP
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt
import kotlin.math.max

class SetupMouseJoystickFragment : Fragment() {
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.activity_setup_mouse_joystick, container, false)

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val repo = SettingsRepository.get()

		val backButton: ImageButton = view.findViewById(R.id.backButton)
		val cornerBiasSeek: SeekBar = view.findViewById(R.id.mjCornerBiasSeek)
		val cornerBiasValue: TextView = view.findViewById(R.id.mjCornerBiasValue)
		val deadZoneSeek: SeekBar = view.findViewById(R.id.mjDeadZoneSeek)
		val deadZoneValue: TextView = view.findViewById(R.id.mjDeadZoneValue)
		val activeZoneSeek: SeekBar = view.findViewById(R.id.mjActiveZoneSeek)
		val activeZoneValue: TextView = view.findViewById(R.id.mjActiveZoneValue)
		val sensitivitySeek: SeekBar = view.findViewById(R.id.mjSensitivitySeek)
		val sensitivityValue: TextView = view.findViewById(R.id.mjSensitivityValue)
		val exitDelaySeek: SeekBar = view.findViewById(R.id.mjExitDelaySeek)
		val exitDelayValue: TextView = view.findViewById(R.id.mjExitDelayValue)
		val reengageSeek: SeekBar = view.findViewById(R.id.mjReengageSeek)
		val reengageValue: TextView = view.findViewById(R.id.mjReengageValue)
		val resetButton: Button = view.findViewById(R.id.resetToDefaultsButton)

		backButton.setOnClickListener {
			parentFragmentManager.popBackStack()
			activity?.takeIf { !it.isFinishing }?.finish()
		}

		view.findViewById<Button>(R.id.mjCalibrateButton).setOnClickListener {
			showCalibrationProbe()
		}

		val savedCornerBias = repo.getFloat(KEY_MOUSE_JOYSTICK_CORNER_BIAS).coerceIn(0.5f, 2.0f)
		cornerBiasSeek.max = 150
		cornerBiasSeek.progress = ((savedCornerBias - 0.5f) * 100f).toInt().coerceIn(0, 150)
		cornerBiasValue.text = getString(R.string.format_decimal_two, savedCornerBias)

		val savedDeadZone = repo.getFloat(KEY_MOUSE_JOYSTICK_DEADZONE).coerceIn(0.01f, 0.80f)
		deadZoneSeek.max = 79
		deadZoneSeek.progress = ((savedDeadZone - 0.01f) * 100f).toInt().coerceIn(0, 79)
		deadZoneValue.text = getString(R.string.format_decimal_two, savedDeadZone)

		val savedActiveZoneRaw = repo.getFloat(KEY_MOUSE_JOYSTICK_ACTIVEZONE)
		val savedActiveZone = max(savedActiveZoneRaw, savedDeadZone + 0.01f).coerceAtMost(0.90f)
		activeZoneSeek.max = 89
		activeZoneSeek.progress = ((savedActiveZone - 0.01f) * 100f).toInt().coerceIn(0, 89)
		activeZoneValue.text = getString(R.string.format_decimal_two, savedActiveZone)

		// Sensitivity is dp/SECOND now (time-normalized): 100..2500 in 50-step increments.
		val savedSensitivity = repo.getInt(KEY_MOUSE_JOYSTICK_SENSITIVITY_DP).coerceIn(SENS_MIN, SENS_MAX)
		sensitivitySeek.max = (SENS_MAX - SENS_MIN) / SENS_STEP
		sensitivitySeek.progress = ((savedSensitivity - SENS_MIN) / SENS_STEP).coerceIn(0, sensitivitySeek.max)
		sensitivityValue.text = savedSensitivity.toString()

		// Exit Delay (500-5000ms in 100ms steps, default 2000ms) — slider stores progress as (ms - 500) / 100.
		val savedExitDelayMs = repo.getInt(KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS).coerceIn(500, 5000)
		exitDelaySeek.max = 45
		exitDelaySeek.progress = ((savedExitDelayMs - 500) / 100).coerceIn(0, 45)
		exitDelayValue.text = getString(R.string.format_exit_delay_sec, savedExitDelayMs / 1000f)

		// Reengage Delay (0-2000ms in 50ms steps, default 750ms) — slider stores progress as ms / 50.
		val savedReengageMs = repo.getInt(KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS).coerceIn(0, 2000)
		reengageSeek.max = 40
		reengageSeek.progress = (savedReengageMs / 50).coerceIn(0, 40)
		reengageValue.text = getString(R.string.format_exit_delay_sec, savedReengageMs / 1000f)

		cornerBiasSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					val bias = 0.5f + (progress.toFloat() / 100f)
					cornerBiasValue.text = getString(R.string.format_decimal_two, bias)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val bias = (0.5f + (cornerBiasSeek.progress.toFloat() / 100f)).coerceIn(0.5f, 2.0f)
					repo.putFloat(KEY_MOUSE_JOYSTICK_CORNER_BIAS, bias)
				}
			},
		)

		deadZoneSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					val dz = 0.01f + (progress.toFloat() / 100f)
					deadZoneValue.text = getString(R.string.format_decimal_two, dz)
					val currentActive = 0.01f + (activeZoneSeek.progress.toFloat() / 100f)
					if (currentActive <= dz) {
						val newActive = (dz + 0.01f).coerceAtMost(0.90f)
						activeZoneSeek.progress = ((newActive - 0.01f) * 100f).toInt().coerceIn(0, 89)
						activeZoneValue.text = getString(R.string.format_decimal_two, newActive)
					}
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val p = seekBar?.progress ?: 19
					val dz = 0.01f + (p.toFloat() / 100f)
					repo.putFloat(KEY_MOUSE_JOYSTICK_DEADZONE, dz)
					val currentActive = 0.01f + (activeZoneSeek.progress.toFloat() / 100f)
					val adjActive = max(currentActive, dz + 0.01f).coerceAtMost(0.90f)
					activeZoneSeek.progress = ((adjActive - 0.01f) * 100f).toInt().coerceIn(0, 89)
					repo.putFloat(KEY_MOUSE_JOYSTICK_ACTIVEZONE, adjActive)
				}
			},
		)

		activeZoneSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					val dz = 0.01f + (deadZoneSeek.progress.toFloat() / 100f)
					var az = 0.01f + (progress.toFloat() / 100f)
					if (az <= dz) az = (dz + 0.01f).coerceAtMost(0.90f)
					activeZoneValue.text = getString(R.string.format_decimal_two, az)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val dz = 0.01f + (deadZoneSeek.progress.toFloat() / 100f)
					var az = 0.01f + ((seekBar?.progress ?: 54).toFloat() / 100f)
					if (az <= dz) az = (dz + 0.01f).coerceAtMost(0.90f)
					repo.putFloat(KEY_MOUSE_JOYSTICK_ACTIVEZONE, az)
					activeZoneValue.text = getString(R.string.format_decimal_two, az)
				}
			},
		)

		sensitivitySeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					sensitivityValue.text = (SENS_MIN + progress * SENS_STEP).toString()
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					repo.putInt(KEY_MOUSE_JOYSTICK_SENSITIVITY_DP, SENS_MIN + (seekBar?.progress ?: 0) * SENS_STEP)
				}
			},
		)

		exitDelaySeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					val delayMs = 500 + (progress * 100)
					exitDelayValue.text = getString(R.string.format_exit_delay_sec, delayMs / 1000f)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val delayMs = (500 + ((seekBar?.progress ?: 15) * 100)).coerceIn(500, 5000)
					repo.putInt(KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS, delayMs)
				}
			},
		)

		reengageSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					val delayMs = progress * 50
					reengageValue.text = getString(R.string.format_exit_delay_sec, delayMs / 1000f)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val delayMs = ((seekBar?.progress ?: 15) * 50).coerceIn(0, 2000)
					repo.putInt(KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS, delayMs)
				}
			},
		)

		resetButton.setOnClickListener {
			val defCornerBias = 1.35f
			val defDeadZone = 0.20f
			val defActiveZone = 0.55f
			val defSensitivity = 20
			val defExitDelayMs = 2000
			val defReengageMs = 750
			repo.putFloat(KEY_MOUSE_JOYSTICK_CORNER_BIAS, defCornerBias)
			repo.putFloat(KEY_MOUSE_JOYSTICK_DEADZONE, defDeadZone)
			repo.putFloat(KEY_MOUSE_JOYSTICK_ACTIVEZONE, defActiveZone)
			repo.putInt(KEY_MOUSE_JOYSTICK_SENSITIVITY_DP, defSensitivity)
			repo.putInt(KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS, defExitDelayMs)
			repo.putInt(KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS, defReengageMs)
			cornerBiasSeek.progress = ((defCornerBias - 0.5f) * 100f).toInt().coerceIn(0, 150)
			cornerBiasValue.text = getString(R.string.format_decimal_two, defCornerBias)
			deadZoneSeek.progress = ((defDeadZone - 0.01f) * 100f).toInt().coerceIn(0, 79)
			deadZoneValue.text = getString(R.string.format_decimal_two, defDeadZone)
			activeZoneSeek.progress = ((defActiveZone - 0.01f) * 100f).toInt().coerceIn(0, 89)
			activeZoneValue.text = getString(R.string.format_decimal_two, defActiveZone)
			sensitivitySeek.progress = (defSensitivity - 5).coerceIn(0, 295)
			sensitivityValue.text = defSensitivity.toString()
			exitDelaySeek.progress = ((defExitDelayMs - 500) / 100).coerceIn(0, 45)
			exitDelayValue.text = getString(R.string.format_exit_delay_sec, defExitDelayMs / 1000f)
			reengageSeek.progress = (defReengageMs / 50).coerceIn(0, 40)
			reengageValue.text = getString(R.string.format_exit_delay_sec, defReengageMs / 1000f)
		}

		// One-tap presets: write the movement-needed (dp/sec) + resting-zone pair and reflect it in the
		// two sliders. Higher dp/sec = less sensitive. Mirrors the in-keyboard settings presets.
		val applyPreset: (Int, Float) -> Unit = { sensitivityDpPerSec, deadZone ->
			repo.putInt(KEY_MOUSE_JOYSTICK_SENSITIVITY_DP, sensitivityDpPerSec)
			repo.putFloat(KEY_MOUSE_JOYSTICK_DEADZONE, deadZone)
			sensitivitySeek.progress = ((sensitivityDpPerSec - SENS_MIN) / SENS_STEP).coerceIn(0, sensitivitySeek.max)
			sensitivityValue.text = sensitivityDpPerSec.toString()
			deadZoneSeek.progress = ((deadZone - 0.01f) * 100f).toInt().coerceIn(0, 79)
			deadZoneValue.text = getString(R.string.format_decimal_two, deadZone)
		}
		view.findViewById<Button>(R.id.mjPresetLightButton).setOnClickListener { applyPreset(500, 0.25f) }
		view.findViewById<Button>(R.id.mjPresetStandardButton).setOnClickListener { applyPreset(800, 0.20f) }
		view.findViewById<Button>(R.id.mjPresetFirmButton).setOnClickListener { applyPreset(1400, 0.15f) }

		val root = view as ViewGroup
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_mj_corner_bias,
			R.string.info_prompt_mj_corner_weighting,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_mj_dead_zone,
			R.string.info_prompt_mj_resting_zone,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_mj_active_zone,
			R.string.info_prompt_mj_active_zone,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_mj_sensitivity,
			R.string.info_prompt_mj_sensitivity,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_mj_exit_delay,
			R.string.info_prompt_mj_exit_delay,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_mj_reengage_hysteresis,
			R.string.info_prompt_mj_reengage_hysteresis,
		)
	}

	/** Overlay the full-screen calibration probe on the activity content. Long-press to dismiss. */
	private fun showCalibrationProbe() {
		val act = activity ?: return
		val root = act.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
		val probe = MjCalibrationProbeView(act)
		probe.setOnLongClickListener {
			root.removeView(probe)
			true
		}
		root.addView(
			probe,
			android.view.ViewGroup.LayoutParams(
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
				android.view.ViewGroup.LayoutParams.MATCH_PARENT,
			),
		)
		probe.requestFocus()
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}

	private companion object {
		// Sensitivity slider is dp/sec: 100..2500 in 50-step increments.
		const val SENS_MIN = 100
		const val SENS_MAX = 2500
		const val SENS_STEP = 50
	}
}
