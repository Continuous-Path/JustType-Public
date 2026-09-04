package org.continuouspath.justtype.ime

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import androidx.core.graphics.drawable.DrawableCompat
import org.continuouspath.justtype.Constants.KEY_BATTERY_SAVER_MODE
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_NGB_CONFIDENCE_ACTION
import org.continuouspath.justtype.Constants.KEY_VIBRATION_FEEDBACK
import org.continuouspath.justtype.Constants.NGB_CONFIDENCE_ACTION_BEEP
import org.continuouspath.justtype.Constants.NGB_CONFIDENCE_ACTION_FLASH
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean

/**
 * Callbacks that [KeyFeedbackController] needs from the IME host.
 */
interface KeyFeedbackCallbacks {
	val isJtuiInitialized: Boolean
	val flashKeyFeedbackEnabled: Boolean
	val errorBeepEnabled: Boolean
	val directSelectionEnabled: Boolean
	val directSelectionDebounceMs: Int
	val useScanLayout: Boolean
	var lastDirectSelectionActivationTime: Long
	fun buttonPressedOnUi(index: Int)
	fun debugLog(message: String)
}

/**
 * Owns key-press feedback: tone generation, button flash/highlight animations,
 * direct-selection click listeners, and error beeps.
 *
 * Shares the [buttonOriginalBackgrounds], [flashRestores], and [standingBackgrounds]
 * maps with [ViewBridgeCoordinator] — both classes read/write the same instances.
 */
class KeyFeedbackController(
	private val getButtons: () -> List<Button>,
	val buttonOriginalBackgrounds: MutableMap<Button, Drawable>,
	val flashRestores: MutableMap<Button, Runnable>,
	val standingBackgrounds: MutableMap<Button, Drawable>,
	private val highlightDrawable: Drawable,
	private val scanSubsystem: ScanSubsystem,
	private val headTrackingSubsystem: HeadTrackingSubsystem,
	private val joystickSubsystem: JoystickSubsystem,
	private val callbacks: KeyFeedbackCallbacks,
) {
	private val buttons: List<Button> get() = getButtons()
	private val isButtonsReady: Boolean get() = buttons.isNotEmpty()

	// ── Owned state ─────────────────────────────────────────────────────

	var highlightedIndex: Int? = null
	private var lastActivatedKeyIndex: Int? = null
	private var errorFeedbackPending = false
	val keyFlashDurationMs: Long = 200L
	val keyFlashColor: Int = Color.parseColor("#81C784")
	private val keyErrorFlashColor: Int = Color.parseColor("#E53935")
	var keyToneGenerator: ToneGenerator? = null
	var switchToneGenerator: ToneGenerator? = null
	var errorToneGenerator: ToneGenerator? = null
	var vibrator: Vibrator? = null

	// ── Tone feedback ───────────────────────────────────────────────────

	fun playKeyActivationTone() {
		keyToneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
	}

	/** Rising prompt tone for Mouse Joystick capture acquired. Gating is caller's responsibility. */
	fun playCaptureAcquiredTone() {
		keyToneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 80)
	}

	/** Descending beep tone for Mouse Joystick capture released. Gating is caller's responsibility. */
	fun playCaptureReleasedTone() {
		keyToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
	}

	/** Short soft tick during the Mouse Joystick exit-dwell countdown, so the release isn't visual-only. */
	fun playExitCountdownTick() {
		keyToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 30)
	}

	fun beepSwitchActivation() {
		switchToneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 60)
	}

	/**
	 * Smaller intermediate cue for a two-switch group-narrow step (4v4→2v2, 2v2→1v1): a soft,
	 * short step beep + a short step buzz, each gated by its master toggle. Distinct from the
	 * full feedback the final key press gets.
	 */
	fun stepFeedback(beep: Boolean) {
		if (beep) switchToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 30)
		if (vibrationEnabled()) vibrateStep()
	}

	/**
	 * Combined tone used when Two-Switch Selection's per-switch beep AND
	 * the per-keystroke beep are both ON: the third switch press in a
	 * sequence would otherwise produce a back-to-back "beep-beep" (60 ms
	 * switch tone + 120 ms key tone). Replace that with a single tone
	 * that's noticeably longer than the 60 ms per-switch tone — long
	 * enough to clearly read as "key activated" without dragging on.
	 */
	fun playSwitchKeyCombinedTone() {
		keyToneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 170)
	}

	fun errorBeep(force: Boolean = false) {
		if (!force && !callbacks.errorBeepEnabled) return
		try {
			val tone = errorToneGenerator ?: return
			// Phase 3D (Δ-6): play a short double-beep instead of a single
			// 150 ms tone, so it's distinguishable from the per-keystroke
			// single beep when "Beep when key is activated" is also ON.
			// ToneGenerator.startTone is asynchronous; chain the second
			// beep via a delayed Runnable so they sound as two distinct
			// beeps.
			val firstDurationMs = 80
			val gapMs = 80
			// NACK timbre (shared with the head-tracking correct/cancel tones) reads
			// clearly as a "negative" event, distinct from the ACK activation tone.
			tone.startTone(ToneGenerator.TONE_PROP_NACK, firstDurationMs)
			android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
				try {
					tone.startTone(ToneGenerator.TONE_PROP_NACK, firstDurationMs)
				} catch (e: Exception) {
					callbacks.debugLog("[errorBeep] second tone failed: ${e.message}")
				}
			}, (firstDurationMs + gapMs).toLong())
		} catch (e: Exception) {
			callbacks.debugLog("[errorBeep] Unable to play tone: ${e.message}")
		}
	}

	/**
	 * Single short low tone for the head-tracking Correct / Backtrack gesture.
	 * Distinguishable from activation (single TONE_PROP_ACK, higher timbre) and
	 * from Cancel (two of these chained). Gating on the beep-feedback setting
	 * is the caller's responsibility, matching the playKeyActivationTone pattern.
	 */
	fun playCorrectTone() {
		keyToneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 60)
	}

	/**
	 * Two short low tones for the head-tracking Cancel gesture, chained via a
	 * delayed Runnable so they sound as two distinct beeps. Same NACK timbre as
	 * playCorrectTone but doubled — communicates "activation attempt abandoned."
	 * Shares the NACK timbre with errorBeep, distinguished by its pulse lengths.
	 * Gating on the beep-feedback setting is the caller's responsibility.
	 */
	fun playCancelTone() {
		try {
			val tone = keyToneGenerator ?: return
			val firstDurationMs = 60
			val gapMs = 100
			tone.startTone(ToneGenerator.TONE_PROP_NACK, firstDurationMs)
			android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
				try {
					tone.startTone(ToneGenerator.TONE_PROP_NACK, firstDurationMs)
				} catch (e: Exception) {
					callbacks.debugLog("[playCancelTone] second tone failed: ${e.message}")
				}
			}, (firstDurationMs + gapMs).toLong())
		} catch (e: Exception) {
			callbacks.debugLog("[playCancelTone] Unable to play tone: ${e.message}")
		}
	}

	// ── Key activation ──────────────────────────────────────────────────

	fun triggerKeyActivation(index: Int, suppressBeep: Boolean = false) {
		if (!callbacks.isJtuiInitialized) return
		lastActivatedKeyIndex = index
		errorFeedbackPending = false
		callbacks.buttonPressedOnUi(index)
		// buttonPressedOnUi runs the key handler synchronously (Main.immediate). If that
		// produced a no-op/error (errorFeedback fired), skip the activation feedback so the
		// key reads as an error, not a successful press.
		if (errorFeedbackPending) return
		if (callbacks.flashKeyFeedbackEnabled) {
			flashGreenForIndex(index)
		}
		if (!suppressBeep) {
			val beepEnabled = SettingsRepository.get().getBoolean(KEY_BEEP_KEY_FEEDBACK)
			if (beepEnabled) {
				playKeyActivationTone()
			}
			if (vibrationEnabled()) vibrateActivation()
		}
	}

	/**
	 * Record the key an out-of-band activation targeted (two-switch/scan silent path, which doesn't
	 * go through [triggerKeyActivation]), so a subsequent [errorFeedback] flashes that key, not a
	 * stale one. Clears the error latch so [keyActivationFeedback] can tell success from error.
	 */
	fun noteActivatedKey(index: Int) {
		lastActivatedKeyIndex = index
		errorFeedbackPending = false
	}

	/**
	 * Normal key-activation feedback (green flash + key beep + haptic, each master-gated) for a key
	 * activated via the silent path (two-switch final press). The handler already ran, so this only
	 * renders feedback — and skips it if that activation errored ([errorFeedback] already fired).
	 */
	fun keyActivationFeedback(index: Int) {
		if (errorFeedbackPending) return
		if (callbacks.flashKeyFeedbackEnabled) flashGreenForIndex(index)
		if (SettingsRepository.get().getBoolean(KEY_BEEP_KEY_FEEDBACK)) playKeyActivationTone()
		if (vibrationEnabled()) vibrateActivation()
	}

	// ── Haptic feedback ─────────────────────────────────────────────────

	private fun vibrationEnabled(): Boolean = SettingsRepository.get().getBoolean(KEY_VIBRATION_FEEDBACK)

	// Battery Saver skips the cosmetic scale bounce (docs/.local/plans/battery-saver-mode.md) —
	// the color flash itself (flashButtonTinted/settleBackground) still runs either way.
	private fun batterySaverOn(): Boolean = SettingsRepository.get().getBoolean(KEY_BATTERY_SAVER_MODE)

	private fun vibrateActivation() {
		val v = vibrator ?: return
		if (!v.hasVibrator()) return
		try {
			v.vibrate(VibrationEffect.createOneShot(20L, VibrationEffect.DEFAULT_AMPLITUDE))
		} catch (e: Exception) {
			callbacks.debugLog("[vibrateActivation] failed: ${e.message}")
		}
	}

	/** Shorter, lighter buzz for an intermediate two-switch group-narrow step. */
	private fun vibrateStep() {
		val v = vibrator ?: return
		if (!v.hasVibrator()) return
		try {
			v.vibrate(VibrationEffect.createOneShot(10L, VibrationEffect.DEFAULT_AMPLITUDE))
		} catch (e: Exception) {
			callbacks.debugLog("[vibrateStep] failed: ${e.message}")
		}
	}

	private fun vibrateError() {
		val v = vibrator ?: return
		if (!v.hasVibrator()) return
		try {
			// Distinct double-pulse so it reads differently from the activation buzz.
			v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 40L, 60L, 40L), -1))
		} catch (e: Exception) {
			callbacks.debugLog("[vibrateError] failed: ${e.message}")
		}
	}

	/**
	 * Always-on non-audio error feedback: a red key flash + error vibration, plus the
	 * (gated) error beep. Routed from every silent-failure site so something registers
	 * even when audio and the error beep are off.
	 */
	fun errorFeedback(force: Boolean = false) {
		errorFeedbackPending = true
		// Flash the key that was actually pressed (not the Select key) so the red flash
		// lands where the user looked. Falls back to the scan select key if nothing is known.
		val index = highlightedIndex ?: lastActivatedKeyIndex ?: scanSubsystem.selectKeyIndex
		buttons.getOrNull(index)?.let { flashButtonTinted(it, color = keyErrorFlashColor) }
		if (vibrationEnabled()) vibrateError()
		errorBeep(force)
	}

	fun triggerSelectActivation() {
		triggerKeyActivation(scanSubsystem.selectKeyIndex)
	}

	/**
	 * NGB-D confidence signal (plan.md "NGB-D"): the top list item is probably
	 * the intended word — stop typing and look. A distinct POSITIVE cue: single
	 * rising PROMPT tone (vs the ACK activation beep and NACK error timbres)
	 * and/or a green flash on the Select key — where the user's next action
	 * would land. Action choice is the user's setting.
	 */
	fun confidenceSignal() {
		val action = SettingsRepository.get()
			.getString(KEY_NGB_CONFIDENCE_ACTION, NGB_CONFIDENCE_ACTION_BEEP)
		if (action != NGB_CONFIDENCE_ACTION_FLASH) {
			keyToneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 90)
		}
		if (action != NGB_CONFIDENCE_ACTION_BEEP) {
			flashGreenForIndex(scanSubsystem.selectKeyIndex)
		}
	}

	// ── Highlight ───────────────────────────────────────────────────────

	fun setHighlight(index: Int) {
		if (!isButtonsReady) return
		if (highlightedIndex == index) return
		highlightedIndex?.let { prev ->
			buttons.getOrNull(prev)?.let { restoreStanding(it) }
		}
		buttons.getOrNull(index)?.let { btn ->
			val hl = highlightDrawable.constantState?.newDrawable()?.mutate() ?: highlightDrawable
			standingBackgrounds[btn] = hl
			btn.background = hl
		}
		highlightedIndex = index
	}

	fun clearHighlight() {
		if (!isButtonsReady) return
		highlightedIndex?.let { prev ->
			buttons.getOrNull(prev)?.let { restoreStanding(it) }
		}
		highlightedIndex = null
	}

	/** Drop a key's standing highlight and paint its original background. */
	private fun restoreStanding(btn: Button) {
		standingBackgrounds.remove(btn)
		buttonOriginalBackgrounds[btn]?.let { btn.background = it }
	}

	/** The background a key settles to when a flash ends: its standing tint, else the original. */
	private fun settleBackground(btn: Button): Drawable? = standingBackgrounds[btn] ?: buttonOriginalBackgrounds[btn]

	fun clearAllHighlights() {
		if (!isButtonsReady) return
		clearHighlight()
		headTrackingSubsystem.clearHighlight()
		headTrackingSubsystem.clearDebugOverlay()
		joystickSubsystem.cancelAndClear()
		flashRestores.forEach { (button, restore) ->
			button.removeCallbacks(restore)
			button.animate().cancel()
			settleBackground(button)?.let { button.background = it }
		}
		flashRestores.clear()
	}

	// ── Flash ───────────────────────────────────────────────────────────

	fun flashButton(button: Button) {
		if (callbacks.useScanLayout || batterySaverOn()) {
			flashButtonTinted(button)
			return
		}
		// Finish any in-flight flash first so its scale restore runs.
		flashRestores.remove(button)?.let {
			button.removeCallbacks(it)
			it.run()
		}
		button.animate().cancel()
		val originalScaleX = button.scaleX
		val originalScaleY = button.scaleY
		val hl = highlightDrawable.constantState?.newDrawable()?.mutate() ?: highlightDrawable
		button.background = hl
		button.animate().scaleX(1.08f).scaleY(1.08f).setDuration(50).start()
		val restore = Runnable {
			// Settle to the CURRENT standing background, recomputed now — never a stale snapshot.
			settleBackground(button)?.let { button.background = it }
			button.animate().scaleX(originalScaleX).scaleY(originalScaleY).setDuration(70).start()
			flashRestores.remove(button)
		}
		flashRestores[button] = restore
		button.postDelayed(restore, 90)
	}

	fun flashGreenForIndex(index: Int) {
		if (!isButtonsReady) return
		val btn = buttons.getOrNull(index) ?: return
		flashButtonTinted(btn)
	}

	private fun flashButtonTinted(btn: Button, color: Int = keyFlashColor) {
		val origBg = buttonOriginalBackgrounds[btn]
		// Finish any in-flight flash first.
		flashRestores.remove(btn)?.let {
			btn.removeCallbacks(it)
			it.run()
		}
		val tinted = (origBg?.constantState?.newDrawable()?.mutate() ?: btn.background.mutate()).let { d ->
			DrawableCompat.wrap(d).also { DrawableCompat.setTint(it, color) }
		}
		btn.background = tinted
		val restore = Runnable {
			// Settle to the CURRENT standing background (scan/two-switch tint or original),
			// recomputed now — never a stale snapshot of what stood when the flash began.
			settleBackground(btn)?.let { btn.background = it }
			btn.animate().alpha(1f).setDuration(70).start()
			flashRestores.remove(btn)
		}
		flashRestores[btn] = restore
		btn.postDelayed(restore, keyFlashDurationMs)
	}

	// ── Button click listeners ──────────────────────────────────────────

	fun updateButtonClickListeners(buttonsJT: List<Button>, buttonsScan: List<Button>) {
		if (callbacks.directSelectionEnabled) {
			buttonsJT.forEachIndexed { index, button ->
				button.setOnClickListener {
					if (!callbacks.isJtuiInitialized) return@setOnClickListener
					val now = System.currentTimeMillis()
					if (callbacks.directSelectionDebounceMs > 0) {
						val elapsed = now - callbacks.lastDirectSelectionActivationTime
						if (elapsed < callbacks.directSelectionDebounceMs) return@setOnClickListener
					}
					callbacks.lastDirectSelectionActivationTime = now
					triggerKeyActivation(index)
				}
			}
			buttonsScan.forEachIndexed { index, button ->
				button.setOnClickListener {
					if (!callbacks.isJtuiInitialized) return@setOnClickListener
					val now = System.currentTimeMillis()
					if (callbacks.directSelectionDebounceMs > 0) {
						val elapsed = now - callbacks.lastDirectSelectionActivationTime
						if (elapsed < callbacks.directSelectionDebounceMs) return@setOnClickListener
					}
					callbacks.lastDirectSelectionActivationTime = now
					triggerKeyActivation(index)
				}
			}
		} else {
			buttonsJT.forEach { it.setOnClickListener(null) }
			buttonsScan.forEach { it.setOnClickListener(null) }
		}
	}
}
