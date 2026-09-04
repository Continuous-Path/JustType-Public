package org.continuouspath.justtype.navigation

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import org.continuouspath.justtype.Constants.DEFAULT_ERROR_BEEP
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_ERROR_BEEP
import org.continuouspath.justtype.Constants.KEY_VIBRATION_FEEDBACK
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean

/**
 * Audio + haptic feedback for the Navigation overlay keyboard. Mirrors the IME's
 * KeyFeedbackController gating: the activation beep follows [KEY_BEEP_KEY_FEEDBACK]
 * and the activation buzz follows [KEY_VIBRATION_FEEDBACK]. [vibrator] is supplied
 * by the service; null on devices without one.
 */
class NavSubsystemFeedback(private val vibrator: Vibrator? = null) {
	private var tone: ToneGenerator? = try {
		ToneGenerator(AudioManager.STREAM_MUSIC, 100)
	} catch (_: RuntimeException) {
		null
	}

	private fun beepEnabled(): Boolean = SettingsRepository.get().getBoolean(KEY_BEEP_KEY_FEEDBACK)
	private fun vibrationEnabled(): Boolean = SettingsRepository.get().getBoolean(KEY_VIBRATION_FEEDBACK)

	/** Combined key-activation feedback: beep + haptic, each gated by its own setting. */
	fun activationFeedback() {
		beepActivation()
		vibrateActivation()
	}

	/**
	 * Smaller intermediate cue for a two-switch group-narrow step: a soft, short step beep + a
	 * short step buzz, each gated by its master toggle. Distinct from [activationFeedback].
	 */
	fun stepActivationFeedback(beep: Boolean) {
		if (beep) beepStep()
		vibrateStep()
	}

	fun beepActivation() {
		if (!beepEnabled()) return
		tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
	}

	// Step/combined tones keep their existing (head-tracking-driven) gating semantics.
	fun beepStep() {
		tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 25)
	}

	fun beepCombined() {
		tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
	}

	/**
	 * Error cue for a no-op/disabled key: a distinct NACK tone (gated by the error-beep setting, like
	 * the IME) + an always-on double-pulse buzz (gated by the vibration setting). The red flash is the
	 * caller's job.
	 */
	fun errorFeedback() {
		if (SettingsRepository.get().getBoolean(KEY_ERROR_BEEP, DEFAULT_ERROR_BEEP)) {
			tone?.startTone(ToneGenerator.TONE_PROP_NACK, 120)
		}
		vibrateError()
	}

	private fun vibrateActivation() {
		val v = vibrator ?: return
		if (!vibrationEnabled() || !v.hasVibrator()) return
		try {
			v.vibrate(VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE))
		} catch (_: Exception) {
			// Best-effort; a vibrate failure must not break input.
		}
	}

	/** Shorter, lighter buzz for an intermediate two-switch group-narrow step. */
	private fun vibrateStep() {
		val v = vibrator ?: return
		if (!vibrationEnabled() || !v.hasVibrator()) return
		try {
			v.vibrate(VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE))
		} catch (_: Exception) {
			// Best-effort; a vibrate failure must not break input.
		}
	}

	/** Distinct double-pulse buzz for an error, so it reads differently from an activation buzz. */
	private fun vibrateError() {
		val v = vibrator ?: return
		if (!vibrationEnabled() || !v.hasVibrator()) return
		try {
			v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 40L, 60L, 40L), -1))
		} catch (_: Exception) {
			// Best-effort; a vibrate failure must not break input.
		}
	}

	fun release() {
		tone?.release()
		tone = null
	}
}
