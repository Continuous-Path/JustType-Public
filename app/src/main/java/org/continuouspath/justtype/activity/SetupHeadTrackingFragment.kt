package org.continuouspath.justtype.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.Constants.KEY_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_AIM_TOLERANCE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORRECTION_BEEP
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_CORRECTION_FLASH_RED
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DEADZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXITZONE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_EXIT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_KEY_ACT_THRESHOLD
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_PITCH_SCALE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_RESPONSE_CURVE
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_RESTART_DELAY_MS
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.settings.getInt
import kotlin.math.max

class SetupHeadTrackingFragment : Fragment() {
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.activity_setup_head_tracking, container, false)

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		val prefs = SettingsRepository.getInstance(requireContext())

		val backButton: ImageButton = view.findViewById(R.id.backButton)
		val cornerBiasSeek: SeekBar = view.findViewById(R.id.cornerBiasSeek)
		val cornerBiasValue: TextView = view.findViewById(R.id.cornerBiasValue)
		val deadZoneSeek: SeekBar = view.findViewById(R.id.headTrackingDeadZoneSeek)
		val deadZoneValue: TextView = view.findViewById(R.id.headTrackingDeadZoneValue)
		val activeZoneSeek: SeekBar = view.findViewById(R.id.headTrackingActiveZoneSeek)
		val activeZoneValue: TextView = view.findViewById(R.id.headTrackingActiveZoneValue)
		val exitZoneSeek: SeekBar = view.findViewById(R.id.headTrackingExitZoneSeek)
		val exitZoneValue: TextView = view.findViewById(R.id.headTrackingExitZoneValue)
		val keyActThresholdSeek: SeekBar = view.findViewById(R.id.headTrackingKeyActThresholdSeek)
		val keyActThresholdValue: TextView = view.findViewById(R.id.headTrackingKeyActThresholdValue)
		val aimToleranceSeek: SeekBar = view.findViewById(R.id.headTrackingAimToleranceSeek)
		val aimToleranceValue: TextView = view.findViewById(R.id.headTrackingAimToleranceValue)
		val exitDelaySeek: SeekBar = view.findViewById(R.id.headTrackingExitDelaySeek)
		val exitDelayValue: TextView = view.findViewById(R.id.headTrackingExitDelayValue)
		val restartDelaySeek: SeekBar = view.findViewById(R.id.headTrackingRestartDelaySeek)
		val restartDelayValue: TextView = view.findViewById(R.id.headTrackingRestartDelayValue)
		val pitchScaleSeek: SeekBar = view.findViewById(R.id.pitchScaleSeek)
		val pitchScaleValue: TextView = view.findViewById(R.id.pitchScaleValue)
		val responseCurveSeek: SeekBar = view.findViewById(R.id.responseCurveSeek)
		val responseCurveValue: TextView = view.findViewById(R.id.responseCurveValue)

		backButton.setOnClickListener {
			parentFragmentManager.popBackStack()
			activity?.takeIf { !it.isFinishing }?.finish()
		}

		val savedCornerBias =
			if (prefs.contains(KEY_HEADTRACKING_CORNER_BIAS)) {
				prefs.getFloat(KEY_HEADTRACKING_CORNER_BIAS)
			} else {
				val legacy = prefs.getFloat(KEY_CORNER_BIAS, 1.3f)
				prefs.putFloat(KEY_HEADTRACKING_CORNER_BIAS, legacy)
				legacy
			}.coerceIn(0.5f, 2.0f)
		cornerBiasSeek.max = 150
		cornerBiasSeek.progress = ((savedCornerBias - 0.5f) * 100f).toInt().coerceIn(0, 150)
		cornerBiasValue.text = getString(R.string.format_decimal_two, savedCornerBias)

		// Pitch Scale (Vertical Sensitivity): 1.0 - 2.0, default 1.1
		val savedPitchScale = prefs.getFloat(KEY_HEADTRACKING_PITCH_SCALE).coerceIn(1.0f, 2.0f)
		pitchScaleSeek.max = 100 // 1.0 to 2.0 in 0.01 increments
		pitchScaleSeek.progress = ((savedPitchScale - 1.0f) * 100f).toInt().coerceIn(0, 100)
		pitchScaleValue.text = getString(R.string.format_decimal_two, savedPitchScale)

		// Response Curve: 0.5 - 1.5, default 1.0 (linear)
		// Values < 1.0 make small movements more responsive (power curve amplifies small inputs)
		val savedResponseCurve = prefs.getFloat(KEY_HEADTRACKING_RESPONSE_CURVE).coerceIn(0.5f, 1.5f)
		responseCurveSeek.max = 100 // 0.5 to 1.5 in 0.01 increments
		responseCurveSeek.progress = ((savedResponseCurve - 0.5f) * 100f).toInt().coerceIn(0, 100)
		responseCurveValue.text = getString(R.string.format_decimal_two, savedResponseCurve)

		val savedDeadZone = prefs.getFloat(KEY_HEADTRACKING_DEADZONE).coerceIn(0.1f, 0.5f)
		deadZoneSeek.max = 40
		deadZoneSeek.progress = ((savedDeadZone - 0.1f) * 100).toInt().coerceIn(0, 40)
		deadZoneValue.text = getString(R.string.format_decimal_two, savedDeadZone)

		val savedActiveZoneRaw = prefs.getFloat(KEY_HEADTRACKING_ACTIVEZONE)
		val savedActiveZone = max(savedActiveZoneRaw, savedDeadZone + 0.01f).coerceAtMost(0.75f)
		activeZoneSeek.max = 65
		activeZoneSeek.progress = ((savedActiveZone - 0.1f) * 100).toInt().coerceIn(0, 65)
		activeZoneValue.text = getString(R.string.format_decimal_two, savedActiveZone)

		val savedExitZoneRaw = prefs.getFloat(KEY_HEADTRACKING_EXITZONE)
		val savedExitZone = max(savedExitZoneRaw, savedActiveZone + 0.01f).coerceAtMost(0.99f)
		exitZoneSeek.max = 89
		exitZoneSeek.progress = ((savedExitZone - 0.1f) * 100).toInt().coerceIn(0, 89)
		exitZoneValue.text = getString(R.string.format_decimal_two, savedExitZone)

		// Key Activation Threshold (0-100%, default 20%)
		val savedKeyActThreshold = prefs.getInt(KEY_HEADTRACKING_KEY_ACT_THRESHOLD).coerceIn(0, 100)
		keyActThresholdSeek.max = 100
		keyActThresholdSeek.progress = savedKeyActThreshold
		keyActThresholdValue.text = getString(R.string.format_percent, savedKeyActThreshold)

		// Aim Tolerance (0-100%, default 67%) — extends the locked key's
		// lock zone by this fraction of its natural half-octant before a
		// Correct gesture triggers.
		val savedAimTolerance = prefs.getInt(KEY_HEADTRACKING_AIM_TOLERANCE).coerceIn(0, 100)
		aimToleranceSeek.max = 100
		aimToleranceSeek.progress = savedAimTolerance
		aimToleranceValue.text = getString(R.string.format_percent, savedAimTolerance)

		// Exit/Pause Delay (2000-5000ms, default 3000ms) - stored in 100ms increments for slider
		val savedExitDelayMs = prefs.getInt(KEY_HEADTRACKING_EXIT_DELAY_MS).coerceIn(2000, 5000)
		exitDelaySeek.max = 30 // 2.0s to 5.0s in 0.1s increments = 30 steps
		exitDelaySeek.progress = ((savedExitDelayMs - 2000) / 100).coerceIn(0, 30)
		exitDelayValue.text = getString(R.string.format_exit_delay_sec, savedExitDelayMs / 1000f)

		// Restart Timer (500-3000ms in 100ms steps, default 1500ms)
		val savedRestartTimerMs = prefs.getInt(KEY_HEADTRACKING_RESTART_DELAY_MS).coerceIn(500, 3000)
		restartDelaySeek.max = 25 // 0.5s..3.0s in 0.1s increments = 25 steps
		restartDelaySeek.progress = ((savedRestartTimerMs - 500) / 100).coerceIn(0, 25)
		restartDelayValue.text = getString(R.string.format_exit_delay_sec, savedRestartTimerMs / 1000f)

		// Correction-gesture feedback (mirrors the Keyboard Settings head_tracking page)
		val correctionBeepSwitch: SwitchCompat = view.findViewById(R.id.correctionBeepSwitch)
		correctionBeepSwitch.isChecked = prefs.getBoolean(KEY_HEADTRACKING_CORRECTION_BEEP, true)
		correctionBeepSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_HEADTRACKING_CORRECTION_BEEP, isChecked)
		}
		val correctionFlashRedSwitch: SwitchCompat = view.findViewById(R.id.correctionFlashRedSwitch)
		correctionFlashRedSwitch.isChecked = prefs.getBoolean(KEY_HEADTRACKING_CORRECTION_FLASH_RED, true)
		correctionFlashRedSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_HEADTRACKING_CORRECTION_FLASH_RED, isChecked)
		}

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
					prefs.putFloat(KEY_HEADTRACKING_CORNER_BIAS, bias)
				}
			},
		)

		pitchScaleSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val scale = 1.0f + (progress.toFloat() / 100f)
					pitchScaleValue.text = getString(R.string.format_decimal_two, scale)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val scale = (1.0f + (pitchScaleSeek.progress.toFloat() / 100f)).coerceIn(1.0f, 2.0f)
					prefs.putFloat(KEY_HEADTRACKING_PITCH_SCALE, scale)
				}
			},
		)

		responseCurveSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val curve = 0.5f + (progress.toFloat() / 100f)
					responseCurveValue.text = getString(R.string.format_decimal_two, curve)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val curve = (0.5f + (responseCurveSeek.progress.toFloat() / 100f)).coerceIn(0.5f, 1.5f)
					prefs.putFloat(KEY_HEADTRACKING_RESPONSE_CURVE, curve)
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
						val newActive = (dz + 0.01f).coerceAtMost(0.75f)
						activeZoneSeek.progress = ((newActive - 0.1f) * 100).toInt().coerceIn(0, 65)
						activeZoneValue.text = getString(R.string.format_decimal_two, newActive)
					}
					val currentExit = 0.1f + (exitZoneSeek.progress.toFloat() / 100f)
					if (currentExit <= currentActive) {
						val newExit = (currentActive + 0.01f).coerceAtMost(0.99f)
						exitZoneSeek.progress = ((newExit - 0.1f) * 100).toInt().coerceIn(0, 89)
						exitZoneValue.text = getString(R.string.format_decimal_two, newExit)
					}
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val p = seekBar?.progress ?: 15
					val dz = 0.1f + (p.toFloat() / 100f)
					prefs.putFloat(KEY_HEADTRACKING_DEADZONE, dz)
					val currentActive = 0.1f + (activeZoneSeek.progress.toFloat() / 100f)
					val adjActive = max(currentActive, dz + 0.01f).coerceAtMost(0.75f)
					activeZoneSeek.progress = ((adjActive - 0.1f) * 100).toInt().coerceIn(0, 65)
					prefs.putFloat(KEY_HEADTRACKING_ACTIVEZONE, adjActive)
					val currentExit = 0.1f + (exitZoneSeek.progress.toFloat() / 100f)
					val adjExit = max(currentExit, adjActive + 0.01f).coerceAtMost(0.99f)
					exitZoneSeek.progress = ((adjExit - 0.1f) * 100).toInt().coerceIn(0, 89)
					prefs.putFloat(KEY_HEADTRACKING_EXITZONE, adjExit)
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
					if (az <= dz) az = (dz + 0.01f).coerceAtMost(0.75f)
					activeZoneValue.text = getString(R.string.format_decimal_two, az)
					val currentExit = 0.1f + (exitZoneSeek.progress.toFloat() / 100f)
					if (currentExit <= az) {
						val newExit = (az + 0.01f).coerceAtMost(0.99f)
						exitZoneSeek.progress = ((newExit - 0.1f) * 100).toInt().coerceIn(0, 89)
						exitZoneValue.text = getString(R.string.format_decimal_two, newExit)
					}
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val dz = 0.1f + (deadZoneSeek.progress.toFloat() / 100f)
					var az = 0.1f + ((seekBar?.progress ?: 35).toFloat() / 100f)
					if (az <= dz) az = (dz + 0.01f).coerceAtMost(0.75f)
					prefs.putFloat(KEY_HEADTRACKING_ACTIVEZONE, az)
					activeZoneValue.text = getString(R.string.format_decimal_two, az)
					val currentExit = 0.1f + (exitZoneSeek.progress.toFloat() / 100f)
					val adjExit = max(currentExit, az + 0.01f).coerceAtMost(0.99f)
					exitZoneSeek.progress = ((adjExit - 0.1f) * 100).toInt().coerceIn(0, 89)
					prefs.putFloat(KEY_HEADTRACKING_EXITZONE, adjExit)
				}
			},
		)

		exitZoneSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val az = 0.1f + (activeZoneSeek.progress.toFloat() / 100f)
					var ez = 0.1f + (progress.toFloat() / 100f)
					if (ez <= az) ez = (az + 0.01f).coerceAtMost(0.99f)
					exitZoneValue.text = getString(R.string.format_decimal_two, ez)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val az = 0.1f + (activeZoneSeek.progress.toFloat() / 100f)
					var ez = 0.1f + ((seekBar?.progress ?: 70).toFloat() / 100f)
					if (ez <= az) ez = (az + 0.01f).coerceAtMost(0.99f)
					prefs.putFloat(KEY_HEADTRACKING_EXITZONE, ez)
					exitZoneValue.text = getString(R.string.format_decimal_two, ez)
				}
			},
		)

		keyActThresholdSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					keyActThresholdValue.text = getString(R.string.format_percent, progress)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val threshold = (seekBar?.progress ?: 20).coerceIn(0, 100)
					prefs.putInt(KEY_HEADTRACKING_KEY_ACT_THRESHOLD, threshold)
				}
			},
		)

		aimToleranceSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					aimToleranceValue.text = getString(R.string.format_percent, progress)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val tolerance = (seekBar?.progress ?: 30).coerceIn(0, 100)
					prefs.putInt(KEY_HEADTRACKING_AIM_TOLERANCE, tolerance)
				}
			},
		)

		exitDelaySeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val delayMs = 2000 + (progress * 100)
					exitDelayValue.text = getString(R.string.format_exit_delay_sec, delayMs / 1000f)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val delayMs = (2000 + ((seekBar?.progress ?: 10) * 100)).coerceIn(2000, 5000)
					prefs.putInt(KEY_HEADTRACKING_EXIT_DELAY_MS, delayMs)
				}
			},
		)

		restartDelaySeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val delayMs = 500 + (progress * 100)
					restartDelayValue.text = getString(R.string.format_exit_delay_sec, delayMs / 1000f)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val delayMs = (500 + ((seekBar?.progress ?: 10) * 100)).coerceIn(500, 3000)
					prefs.putInt(KEY_HEADTRACKING_RESTART_DELAY_MS, delayMs)
				}
			},
		)

		// Phase 3B: attach INFO PROMPT icons. Setup-page row containers have
		// no XML ids, so we hand the helper the fragment root and let it
		// locate each row by matching the title text.
		val root = view as ViewGroup
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.label_corner_bias,
			R.string.info_prompt_ht_corner_weighting,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ht_vertical_sensitivity,
			R.string.info_prompt_ht_vertical_sensitivity,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ht_response_curve,
			R.string.info_prompt_ht_responsiveness,
		)
		// Phase 3C added a "--- Zone Thresholds ---" section heading; the
		// section's INFO PROMPT (which describes the Resting Zone behavior)
		// now lives on the heading itself rather than on the first slider.
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ht_section_zone_thresholds,
			R.string.info_prompt_section_ht_zone_thresholds,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ht_active_zone,
			R.string.info_prompt_ht_active_zone,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ht_exit_zone,
			R.string.info_prompt_ht_exit_zone,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ht_key_activation,
			R.string.info_prompt_ht_key_activation_threshold,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.setup_ht_exit_delay,
			R.string.info_prompt_ht_exit_pause_delay,
		)
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}
}
