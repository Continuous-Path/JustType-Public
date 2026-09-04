package org.continuouspath.justtype.ime

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_NGB_CONFIDENCE_ACTION
import org.continuouspath.justtype.Constants.KEY_VIBRATION_FEEDBACK
import org.continuouspath.justtype.Constants.NGB_CONFIDENCE_ACTION_BEEP
import org.continuouspath.justtype.Constants.NGB_CONFIDENCE_ACTION_BOTH
import org.continuouspath.justtype.Constants.NGB_CONFIDENCE_ACTION_FLASH
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Characterization tests for [KeyFeedbackController].
 *
 * Locks down tone playback, key flash/highlight animations,
 * direct-selection click listeners, and error beeps. Production
 * code is unchanged — these tests assert *current* behavior so
 * future refactors don't drift.
 */
@RunWith(RobolectricTestRunner::class)
class KeyFeedbackControllerTest {

	private lateinit var repo: SettingsRepository
	private lateinit var subject: KeyFeedbackController

	private val scanSubsystem: ScanSubsystem = mock()
	private val headTrackingSubsystem: HeadTrackingSubsystem = mock()
	private val joystickSubsystem: JoystickSubsystem = mock()

	private val highlightDrawable: Drawable = ColorDrawable(Color.GREEN)
	private val buttonOriginalBackgrounds: MutableMap<Button, Drawable> = mutableMapOf()
	private val flashRestores: MutableMap<Button, Runnable> = mutableMapOf()
	private val standingBackgrounds: MutableMap<Button, Drawable> = mutableMapOf()

	private var buttons: List<Button> = emptyList()
	private val callbacks = FakeCallbacks()

	@Before
	fun setUp() {
		val context = RuntimeEnvironment.getApplication()
		// Registry-aware reads (e.g. vibration/beep gates) resolve defaults via the registry.
		SettingsRegistry.getInstance(context)
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()

		// Pre-populate eight buttons + their original backgrounds so flash/highlight have something to work with.
		buttons = List(BUTTON_COUNT) { idx ->
			Button(context).also { btn ->
				buttonOriginalBackgrounds[btn] = ColorDrawable(Color.DKGRAY + idx)
				btn.background = buttonOriginalBackgrounds[btn]
			}
		}

		subject = KeyFeedbackController(
			getButtons = { buttons },
			buttonOriginalBackgrounds = buttonOriginalBackgrounds,
			flashRestores = flashRestores,
			standingBackgrounds = standingBackgrounds,
			highlightDrawable = highlightDrawable,
			scanSubsystem = scanSubsystem,
			headTrackingSubsystem = headTrackingSubsystem,
			joystickSubsystem = joystickSubsystem,
			callbacks = callbacks,
		)
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	// ── Group 1 — Tone feedback ─────────────────────────────────────────────

	@Test
	fun `playKeyActivationTone uses key tone generator with correct args`() {
		val keyTone: ToneGenerator = mock()
		subject.keyToneGenerator = keyTone

		subject.playKeyActivationTone()

		verify(keyTone).startTone(ToneGenerator.TONE_PROP_ACK, 120)
	}

	@Test
	fun `playKeyActivationTone is no-op when keyToneGenerator is null`() {
		subject.keyToneGenerator = null

		subject.playKeyActivationTone()
		// No throw is sufficient — no observable side effect when null.
	}

	@Test
	fun `beepSwitchActivation uses switch tone generator with correct args`() {
		val switchTone: ToneGenerator = mock()
		subject.switchToneGenerator = switchTone

		subject.beepSwitchActivation()

		verify(switchTone).startTone(ToneGenerator.TONE_PROP_ACK, 60)
	}

	@Test
	fun `errorBeep returns early when errorBeepEnabled is false and not forced`() {
		val errorTone: ToneGenerator = mock()
		subject.errorToneGenerator = errorTone
		callbacks.errorBeepEnabled = false

		subject.errorBeep(force = false)

		verify(errorTone, never()).startTone(any(), any())
	}

	@Test
	fun `errorBeep plays even when errorBeepEnabled is false if force is true`() {
		val errorTone: ToneGenerator = mock()
		subject.errorToneGenerator = errorTone
		callbacks.errorBeepEnabled = false

		subject.errorBeep(force = true)

		verify(errorTone).startTone(ToneGenerator.TONE_PROP_NACK, 80)
	}

	@Test
	fun `errorBeep swallows tone exception and routes to debugLog`() {
		// Requires inline mock-maker (mockito-kotlin 5.x default) for ToneGenerator (final framework class).
		val errorTone: ToneGenerator = mock()
		whenever(errorTone.startTone(any(), any())).thenThrow(RuntimeException("device busy"))
		subject.errorToneGenerator = errorTone
		callbacks.errorBeepEnabled = true

		subject.errorBeep()

		assertThat(callbacks.debugLogCalls).hasSize(1)
		assertThat(callbacks.debugLogCalls[0]).contains("[errorBeep]")
		assertThat(callbacks.debugLogCalls[0]).contains("device busy")
	}

	// ── Group 2 — triggerKeyActivation ──────────────────────────────────────

	@Test
	fun `triggerKeyActivation returns early when JTUI is not initialized`() {
		callbacks.isJtuiInitialized = false
		val keyTone: ToneGenerator = mock()
		subject.keyToneGenerator = keyTone

		subject.triggerKeyActivation(2)

		assertThat(callbacks.buttonPressedOnUiCalls).isEmpty()
		verify(keyTone, never()).startTone(any(), any())
	}

	@Test
	fun `triggerKeyActivation flashes button when flashKeyFeedbackEnabled is true`() {
		callbacks.isJtuiInitialized = true
		callbacks.flashKeyFeedbackEnabled = true
		setBeepPref(false)

		subject.triggerKeyActivation(3)

		assertThat(callbacks.buttonPressedOnUiCalls).containsExactly(3)
		// Flash applies a tinted drawable to the button at index 3.
		assertThat(buttons[3].background).isNotSameInstanceAs(buttonOriginalBackgrounds[buttons[3]])
		// And registers a restore runnable.
		assertThat(flashRestores).containsKey(buttons[3])
	}

	@Test
	fun `flash restores the standing background, not the stored original`() {
		callbacks.isJtuiInitialized = true
		callbacks.flashKeyFeedbackEnabled = true
		setBeepPref(false)

		// A two-switch standing tint registered in the shared standing map.
		val standing = ColorDrawable(Color.GREEN)
		standingBackgrounds[buttons[3]] = standing
		buttons[3].background = standing

		subject.triggerKeyActivation(3)
		// Mid-flash the button shows the flash tint, not the standing background.
		assertThat(buttons[3].background).isNotSameInstanceAs(standing)

		// Run the scheduled restore.
		flashRestores[buttons[3]]!!.run()

		// Settled to the standing background — not the stored original (that was the bug).
		assertThat(buttons[3].background).isSameInstanceAs(standing)
		assertThat(buttons[3].background).isNotSameInstanceAs(buttonOriginalBackgrounds[buttons[3]])
	}

	@Test
	fun `error flash restores the standing background`() {
		whenever(scanSubsystem.selectKeyIndex).thenReturn(2)
		val standing = ColorDrawable(Color.RED)
		standingBackgrounds[buttons[2]] = standing
		buttons[2].background = standing

		subject.errorFeedback()
		flashRestores[buttons[2]]!!.run()

		assertThat(buttons[2].background).isSameInstanceAs(standing)
	}

	@Test
	fun `errorFeedback flashes the noted activated key, not the scan-select fallback`() {
		// The silent (two-switch) activation path records the key via noteActivatedKey.
		whenever(scanSubsystem.selectKeyIndex).thenReturn(0)
		subject.noteActivatedKey(5)

		subject.errorFeedback()

		assertThat(flashRestores).containsKey(buttons[5])
		assertThat(flashRestores).doesNotContainKey(buttons[0])
	}

	@Test
	fun `triggerKeyActivation skips flash when flashKeyFeedbackEnabled is false`() {
		callbacks.isJtuiInitialized = true
		callbacks.flashKeyFeedbackEnabled = false
		setBeepPref(false)

		subject.triggerKeyActivation(3)

		assertThat(callbacks.buttonPressedOnUiCalls).containsExactly(3)
		// No flash means no restore runnable scheduled.
		assertThat(flashRestores).doesNotContainKey(buttons[3])
	}

	@Test
	fun `triggerKeyActivation respects KEY_BEEP_KEY_FEEDBACK setting`() {
		callbacks.isJtuiInitialized = true
		callbacks.flashKeyFeedbackEnabled = false
		val keyTone: ToneGenerator = mock()
		subject.keyToneGenerator = keyTone

		setBeepPref(false)
		subject.triggerKeyActivation(0)
		verify(keyTone, never()).startTone(any(), any())

		setBeepPref(true)
		subject.triggerKeyActivation(0)
		verify(keyTone).startTone(ToneGenerator.TONE_PROP_ACK, 120)
	}

	@Test
	fun `triggerSelectActivation forwards to triggerKeyActivation with scan select index`() {
		callbacks.isJtuiInitialized = true
		callbacks.flashKeyFeedbackEnabled = false
		whenever(scanSubsystem.selectKeyIndex).thenReturn(6)
		setBeepPref(false)

		subject.triggerSelectActivation()

		assertThat(callbacks.buttonPressedOnUiCalls).containsExactly(6)
	}

	// ── Group 3 — Highlight ─────────────────────────────────────────────────

	@Test
	fun `setHighlight is a no-op when buttons list is empty`() {
		buttons = emptyList()

		subject.setHighlight(2)

		assertThat(subject.highlightedIndex).isNull()
	}

	@Test
	fun `setHighlight is idempotent for the same index`() {
		subject.setHighlight(2)
		val firstBg = buttons[2].background

		subject.setHighlight(2)

		assertThat(subject.highlightedIndex).isEqualTo(2)
		assertThat(buttons[2].background).isSameInstanceAs(firstBg)
	}

	@Test
	fun `setHighlight on a new index restores the previous and highlights the new`() {
		val origA = buttons[1].background
		val origB = buttons[4].background

		subject.setHighlight(1)
		subject.setHighlight(4)

		assertThat(buttons[1].background).isSameInstanceAs(origA)
		assertThat(buttons[4].background).isNotSameInstanceAs(origB)
		assertThat(subject.highlightedIndex).isEqualTo(4)
	}

	@Test
	fun `clearHighlight restores the highlighted button and resets state`() {
		val origA = buttons[1].background

		subject.setHighlight(1)
		subject.clearHighlight()

		assertThat(buttons[1].background).isSameInstanceAs(origA)
		assertThat(subject.highlightedIndex).isNull()
	}

	// ── Group 4 — clearAllHighlights ────────────────────────────────────────

	@Test
	fun `clearAllHighlights delegates to head and joystick subsystems`() {
		subject.clearAllHighlights()

		verify(headTrackingSubsystem).clearHighlight()
		verify(headTrackingSubsystem).clearDebugOverlay()
		verify(joystickSubsystem).cancelAndClear()
	}

	@Test
	fun `clearAllHighlights cancels pending flash restores and empties the map`() {
		// Trigger a flash so flashRestores has an entry.
		callbacks.useScanLayout = false
		subject.flashButton(buttons[2])
		assertThat(flashRestores).isNotEmpty()

		subject.clearAllHighlights()

		assertThat(flashRestores).isEmpty()
	}

	// ── Group 5 — Flash ─────────────────────────────────────────────────────

	@Test
	fun `flashButton in scan layout takes the green-tint path without scale animation`() {
		callbacks.useScanLayout = true
		val origBg = buttons[3].background

		subject.flashButton(buttons[3])

		// Background was replaced with a green-tinted drawable.
		assertThat(buttons[3].background).isNotSameInstanceAs(origBg)
		// scaleX is unchanged (no animation kicked off).
		assertThat(buttons[3].scaleX).isEqualTo(1f)
	}

	@Test
	fun `flashButton non-scan path queues a restore runnable in flashRestores`() {
		callbacks.useScanLayout = false

		subject.flashButton(buttons[3])

		assertThat(flashRestores).containsKey(buttons[3])
	}

	@Test
	fun `flashGreenForIndex is a no-op when buttons list is empty`() {
		buttons = emptyList()

		subject.flashGreenForIndex(2)
		// No throw, no mutation — nothing observable.
		assertThat(flashRestores).isEmpty()
	}

	@Test
	fun `flash restore settles to the CURRENT standing background, not a stale snapshot`() {
		subject.flashGreenForIndex(2)
		// A subsystem repaint lands mid-flash (scan/two-switch tint registered as standing state).
		val standing = ColorDrawable(Color.CYAN)
		standingBackgrounds[buttons[2]] = standing

		// The restore runnable is parked in flashRestores; invoke directly so we don't depend on
		// Robolectric main-looper paused/unpaused behaviour for postDelayed flushing.
		flashRestores[buttons[2]]!!.run()

		assertThat(buttons[2].background).isSameInstanceAs(standing)
	}

	@Test
	fun `flash restore settles to the original when no standing tint exists`() {
		subject.flashGreenForIndex(2)
		assertThat(buttons[2].background).isNotSameInstanceAs(buttonOriginalBackgrounds[buttons[2]])

		flashRestores[buttons[2]]!!.run()

		assertThat(buttons[2].background).isSameInstanceAs(buttonOriginalBackgrounds[buttons[2]])
	}

	// ── Group 6 — updateButtonClickListeners ────────────────────────────────

	@Test
	fun `updateButtonClickListeners clears listeners when directSelectionEnabled is false`() {
		callbacks.directSelectionEnabled = false
		callbacks.isJtuiInitialized = true
		buttons.forEach { it.setOnClickListener { fail("listener should be cleared") } }

		subject.updateButtonClickListeners(buttonsJT = buttons, buttonsScan = emptyList())

		// performClick fires nothing because the listener was cleared (no fail() invoked).
		buttons.forEach { it.performClick() }
		assertThat(callbacks.buttonPressedOnUiCalls).isEmpty()
	}

	@Test
	fun `updateButtonClickListeners with directSelectionEnabled wires JT button clicks`() {
		callbacks.directSelectionEnabled = true
		callbacks.isJtuiInitialized = true
		callbacks.directSelectionDebounceMs = 0
		callbacks.flashKeyFeedbackEnabled = false
		setBeepPref(false)

		subject.updateButtonClickListeners(buttonsJT = buttons, buttonsScan = emptyList())
		buttons[2].performClick()

		assertThat(callbacks.buttonPressedOnUiCalls).containsExactly(2)
	}

	@Test
	fun `clicks within debounce window are dropped`() {
		callbacks.directSelectionEnabled = true
		callbacks.isJtuiInitialized = true
		callbacks.directSelectionDebounceMs = 1_000_000
		callbacks.flashKeyFeedbackEnabled = false
		setBeepPref(false)

		subject.updateButtonClickListeners(buttonsJT = buttons, buttonsScan = emptyList())
		buttons[1].performClick()
		buttons[1].performClick()

		// Second click is within the debounce window — only the first fires.
		assertThat(callbacks.buttonPressedOnUiCalls).containsExactly(1)
	}

	@Test
	fun `debounce of zero allows consecutive clicks`() {
		callbacks.directSelectionEnabled = true
		callbacks.isJtuiInitialized = true
		callbacks.directSelectionDebounceMs = 0
		callbacks.flashKeyFeedbackEnabled = false
		setBeepPref(false)

		subject.updateButtonClickListeners(buttonsJT = buttons, buttonsScan = emptyList())
		buttons[5].performClick()
		buttons[5].performClick()

		assertThat(callbacks.buttonPressedOnUiCalls).containsExactly(5, 5).inOrder()
	}

	// ── Group 7 — Haptics + errorFeedback ───────────────────────────────────

	@Test
	fun `triggerKeyActivation vibrates when vibration feedback enabled`() {
		callbacks.isJtuiInitialized = true
		callbacks.flashKeyFeedbackEnabled = false
		setBeepPref(false)
		setVibrationPref(true)
		val vibrator: Vibrator = mock()
		whenever(vibrator.hasVibrator()).thenReturn(true)
		subject.vibrator = vibrator

		subject.triggerKeyActivation(0)

		verify(vibrator).vibrate(any<VibrationEffect>())
	}

	@Test
	fun `triggerKeyActivation does not vibrate when vibration feedback disabled`() {
		callbacks.isJtuiInitialized = true
		callbacks.flashKeyFeedbackEnabled = false
		setBeepPref(false)
		setVibrationPref(false)
		val vibrator: Vibrator = mock()
		whenever(vibrator.hasVibrator()).thenReturn(true)
		subject.vibrator = vibrator

		subject.triggerKeyActivation(0)

		verify(vibrator, never()).vibrate(any<VibrationEffect>())
	}

	@Test
	fun `errorFeedback flashes a key even when flash and error beep are off`() {
		callbacks.flashKeyFeedbackEnabled = false
		callbacks.errorBeepEnabled = false
		setVibrationPref(false)
		whenever(scanSubsystem.selectKeyIndex).thenReturn(0)
		val origBg = buttons[0].background

		subject.errorFeedback()

		// Always-on visual: the key flashes regardless of the flash / error-beep gates.
		assertThat(buttons[0].background).isNotSameInstanceAs(origBg)
		assertThat(flashRestores).containsKey(buttons[0])
	}

	@Test
	fun `errorFeedback vibrates with error pattern when vibration enabled`() {
		callbacks.errorBeepEnabled = false
		setVibrationPref(true)
		whenever(scanSubsystem.selectKeyIndex).thenReturn(0)
		val vibrator: Vibrator = mock()
		whenever(vibrator.hasVibrator()).thenReturn(true)
		subject.vibrator = vibrator

		subject.errorFeedback()

		verify(vibrator).vibrate(any<VibrationEffect>())
	}

	@Test
	fun `triggerKeyActivation skips activation feedback when the handler reports an error`() {
		callbacks.isJtuiInitialized = true
		callbacks.flashKeyFeedbackEnabled = true
		setBeepPref(true)
		val keyTone: ToneGenerator = mock()
		subject.keyToneGenerator = keyTone
		whenever(scanSubsystem.selectKeyIndex).thenReturn(0)
		// Simulate a no-op key: the handler triggers error feedback during buttonPressedOnUi.
		callbacks.onButtonPressed = { subject.errorFeedback() }

		subject.triggerKeyActivation(3)

		// No activation tone — the press was a no-op, so it reads as an error only.
		verify(keyTone, never()).startTone(ToneGenerator.TONE_PROP_ACK, 120)
		// The error flash landed on the pressed key (3), not the Select key.
		assertThat(flashRestores).containsKey(buttons[3])
	}

	// ── Group 8 — NGB confidence signal ─────────────────────────────────────

	@Test
	fun `confidenceSignal beep action plays the prompt tone and does not flash`() {
		setConfidenceActionPref(NGB_CONFIDENCE_ACTION_BEEP)
		val keyTone: ToneGenerator = mock()
		subject.keyToneGenerator = keyTone
		whenever(scanSubsystem.selectKeyIndex).thenReturn(6)

		subject.confidenceSignal()

		verify(keyTone).startTone(ToneGenerator.TONE_PROP_PROMPT, 90)
		assertThat(flashRestores).isEmpty()
	}

	@Test
	fun `confidenceSignal flash action flashes the select key and stays silent`() {
		setConfidenceActionPref(NGB_CONFIDENCE_ACTION_FLASH)
		val keyTone: ToneGenerator = mock()
		subject.keyToneGenerator = keyTone
		whenever(scanSubsystem.selectKeyIndex).thenReturn(6)

		subject.confidenceSignal()

		verify(keyTone, never()).startTone(any(), any())
		assertThat(flashRestores).containsKey(buttons[6])
	}

	@Test
	fun `confidenceSignal both action beeps and flashes`() {
		setConfidenceActionPref(NGB_CONFIDENCE_ACTION_BOTH)
		val keyTone: ToneGenerator = mock()
		subject.keyToneGenerator = keyTone
		whenever(scanSubsystem.selectKeyIndex).thenReturn(6)

		subject.confidenceSignal()

		verify(keyTone).startTone(ToneGenerator.TONE_PROP_PROMPT, 90)
		assertThat(flashRestores).containsKey(buttons[6])
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	/**
	 * Synchronous DataStore write for [KEY_BEEP_KEY_FEEDBACK]. Required because
	 * [KeyFeedbackController.triggerKeyActivation] reads this directly off the
	 * singleton — without a synchronous write the test races the persistent
	 * collect job. See step 3.1 retrospective for the original incident.
	 */
	private fun setBeepPref(enabled: Boolean) {
		repo.edit().putBoolean(KEY_BEEP_KEY_FEEDBACK, enabled).commit()
	}

	private fun setVibrationPref(enabled: Boolean) {
		repo.edit().putBoolean(KEY_VIBRATION_FEEDBACK, enabled).commit()
	}

	private fun setConfidenceActionPref(action: String) {
		repo.edit().putString(KEY_NGB_CONFIDENCE_ACTION, action).commit()
	}

	private fun fail(msg: String): Nothing = throw AssertionError(msg)

	/**
	 * Hand-rolled fake for [KeyFeedbackCallbacks]. Allows tests to mutate the
	 * gate values directly without re-stubbing.
	 */
	private class FakeCallbacks : KeyFeedbackCallbacks {
		override var isJtuiInitialized: Boolean = true
		override var flashKeyFeedbackEnabled: Boolean = false
		override var errorBeepEnabled: Boolean = true
		override var directSelectionEnabled: Boolean = false
		override var directSelectionDebounceMs: Int = 0
		override var useScanLayout: Boolean = false
		override var lastDirectSelectionActivationTime: Long = 0L

		val buttonPressedOnUiCalls = mutableListOf<Int>()
		var onButtonPressed: (Int) -> Unit = {}
		override fun buttonPressedOnUi(index: Int) {
			buttonPressedOnUiCalls += index
			onButtonPressed(index)
		}

		val debugLogCalls = mutableListOf<String>()
		override fun debugLog(message: String) {
			debugLogCalls += message
		}
	}

	private companion object {
		const val BUTTON_COUNT = 8
	}
}
