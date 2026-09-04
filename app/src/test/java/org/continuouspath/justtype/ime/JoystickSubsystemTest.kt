package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_CORNER_BIAS
import org.continuouspath.justtype.Constants.KEY_JOYSTICK_DEADZONE
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class JoystickSubsystemTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private lateinit var testScope: TestScope
	private lateinit var subsystem: JoystickSubsystem
	private lateinit var repo: SettingsRepository

	// Tracking for JoystickViewBridge calls
	private val buttonDrawables = mutableMapOf<Int, Int>()
	private val restoredBackgrounds = mutableListOf<Int>()
	private var viewReady = true

	// Tracking for KeyActivationSink calls
	private val silentActivatedKeys = mutableListOf<Int>()
	private var keySinkReady = true

	// Tracking for JoystickCallbacks calls
	private var beepCount = 0
	private val debugMessages = mutableListOf<String>()

	private val viewBridge = object : JoystickViewBridge {
		override val buttonCount: Int = 8
		override val isViewReady: Boolean get() = viewReady

		override fun setButtonDrawable(index: Int, drawableResId: Int) {
			buttonDrawables[index] = drawableResId
		}

		override fun restoreButtonBackground(index: Int) {
			restoredBackgrounds.add(index)
			buttonDrawables.remove(index)
		}

		override fun showKeyboardBorder(show: Boolean) { /* no-op for joystick tests */ }

		override fun setMousePointerHidden(hidden: Boolean) { /* no-op for joystick tests */ }
	}

	private val keySink = object : KeyActivationSink {
		override fun activateKey(index: Int) {
			// Not used by JoystickSubsystem
		}

		override fun activateSelect() {
			// Not used by JoystickSubsystem
		}

		override fun activateKeySilent(index: Int) {
			silentActivatedKeys.add(index)
		}

		override fun isReady(): Boolean = keySinkReady
	}

	private val callbacks = object : JoystickCallbacks {
		override fun playActivationBeep() {
			beepCount++
		}

		override fun debugLog(message: String) {
			debugMessages.add(message)
		}
	}

	@Before
	fun setUp() {
		testScope = TestScope(StandardTestDispatcher())
		// Repo writes consult the registry (RegistryAwareRepo); init explicitly rather than
		// relying on an earlier test class in the same worker JVM having done it.
		org.continuouspath.justtype.settings.SettingsRegistry.reinitialize(RuntimeEnvironment.getApplication())
		repo = SettingsRepository.getInstance(RuntimeEnvironment.getApplication())
		repo.clearForTesting()

		subsystem = JoystickSubsystem(
			scope = testScope,
			viewBridge = viewBridge,
			keySink = keySink,
			callbacks = callbacks,
		)

		// Default settings: standard zones
		repo.putFloat(KEY_JOYSTICK_DEADZONE, 0.25f)
		repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.60f)
		repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, 1.35f)
		subsystem.loadSettings(repo)
	}

	@After
	fun tearDown() {
		subsystem.destroy()
	}

	private fun clearTracking() {
		buttonDrawables.clear()
		restoredBackgrounds.clear()
		silentActivatedKeys.clear()
		beepCount = 0
		debugMessages.clear()
	}

	// ── Dead zone tests ────────────────────────────────────────────────

	@Test
	fun `dead zone coordinates produce no highlight`() = testScope.runTest {
		subsystem.handleInput(0f, 0f)
		assertThat(buttonDrawables).isEmpty()
		assertThat(silentActivatedKeys).isEmpty()
	}

	@Test
	fun `small magnitude stays in dead zone`() = testScope.runTest {
		subsystem.handleInput(0.1f, 0.1f) // ~0.14 magnitude, below 0.25 deadzone
		assertThat(buttonDrawables).isEmpty()
	}

	// ── Feedback zone tests ────────────────────────────────────────────

	@Test
	fun `feedback zone shows yellow highlight`() = testScope.runTest {
		// Push right into feedback zone (magnitude ~0.4, between 0.25 and 0.60)
		subsystem.handleInput(0.4f, 0f)
		assertThat(buttonDrawables).containsKey(4) // RIGHT = index 4
		assertThat(buttonDrawables[4]).isEqualTo(R.drawable.button_background_feedback)
	}

	@Test
	fun `moving between sectors in feedback zone restores previous`() = testScope.runTest {
		// Push right into feedback zone
		subsystem.handleInput(0.4f, 0f)
		assertThat(buttonDrawables).containsKey(4)

		clearTracking()
		// Move to up (feedback zone)
		subsystem.handleInput(0f, -0.4f)
		// Previous (index 4) should have been restored
		assertThat(restoredBackgrounds).contains(4)
		// New highlight should be UP = index 1
		assertThat(buttonDrawables).containsKey(1)
		assertThat(buttonDrawables[1]).isEqualTo(R.drawable.button_background_feedback)
	}

	@Test
	fun `returning to dead zone clears highlight`() = testScope.runTest {
		subsystem.handleInput(0.4f, 0f)
		assertThat(buttonDrawables).isNotEmpty()

		clearTracking()
		subsystem.handleInput(0f, 0f)
		// Highlight should be cleared
		assertThat(buttonDrawables).isEmpty()
	}

	// ── Activation zone tests ──────────────────────────────────────────

	@Test
	fun `entering activation zone shows pale green`() = testScope.runTest {
		// Push right into activation zone (magnitude ~0.8, above 0.60)
		subsystem.handleInput(0.8f, 0f)
		assertThat(buttonDrawables[4]).isEqualTo(R.drawable.button_background_joystick_pale_green)
		assertThat(subsystem.isInActivationSequence).isTrue()
	}

	@Test
	fun `activation sequence fires key after 100ms`() = testScope.runTest {
		subsystem.handleInput(0.8f, 0f) // Enter activation zone (RIGHT = index 4)
		assertThat(silentActivatedKeys).isEmpty()

		advanceTimeBy(101)
		assertThat(silentActivatedKeys).containsExactly(4)
	}

	@Test
	fun `activation beep plays after 100ms when enabled`() = testScope.runTest {
		subsystem.handleInput(0.8f, 0f)
		assertThat(beepCount).isEqualTo(0)

		advanceTimeBy(101)
		assertThat(beepCount).isEqualTo(1)
	}

	@Test
	fun `activation beep does not play when disabled`() = testScope.runTest {
		repo.putBoolean(KEY_FLASH_KEY_FEEDBACK, true)
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, false)
		subsystem.loadSettings(repo)
		subsystem.handleInput(0.8f, 0f)

		advanceTimeBy(101)
		assertThat(beepCount).isEqualTo(0)
		// Key still activates
		assertThat(silentActivatedKeys).containsExactly(4)
	}

	@Test
	fun `flash shows dark green then returns to pale green`() = testScope.runTest {
		subsystem.handleInput(0.8f, 0f) // Enter activation (RIGHT = 4)

		// At 100ms: beep + key fire + dark green flash starts
		advanceTimeBy(101)
		assertThat(buttonDrawables[4]).isEqualTo(R.drawable.button_background_joystick_dark_green)

		// At 350ms (100 + 250): flash ends, back to pale green
		advanceTimeBy(250)
		assertThat(buttonDrawables[4]).isEqualTo(R.drawable.button_background_joystick_pale_green)
	}

	@Test
	fun `no flash keeps dark green`() = testScope.runTest {
		repo.putBoolean(KEY_FLASH_KEY_FEEDBACK, false)
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		subsystem.handleInput(0.8f, 0f) // Enter activation

		advanceTimeBy(101)
		assertThat(buttonDrawables[4]).isEqualTo(R.drawable.button_background_joystick_dark_green)

		// Should stay dark green (no flash revert)
		advanceTimeBy(300)
		assertThat(buttonDrawables[4]).isEqualTo(R.drawable.button_background_joystick_dark_green)
	}

	// ── Cursor drop activation tests ───────────────────────────────────

	@Test
	fun `dropping to feedback after activation sequence does not re-fire key`() = testScope.runTest {
		// Enter activation zone; the sequence fires the key at 100ms.
		subsystem.handleInput(0.8f, 0f) // RIGHT in activation
		advanceTimeBy(400)
		clearTracking()

		// Drop to feedback zone (still RIGHT) — completeActivation(), no second fire.
		subsystem.handleInput(0.4f, 0f)
		assertThat(silentActivatedKeys).isEmpty()
	}

	@Test
	fun `normal drop-below activation fires key when not in sequence`() = testScope.runTest {
		// Enter activation zone
		subsystem.handleInput(0.8f, 0f) // RIGHT in activation

		// Complete activation sequence (key fires at 100ms)
		advanceTimeBy(400)
		clearTracking()

		// Now push into activation again from fresh state
		subsystem.handleInput(0f, 0f) // Reset to dead zone
		clearTracking()

		// Enter feedback then activation
		subsystem.handleInput(0.4f, 0f)
		clearTracking()
		subsystem.handleInput(0.8f, 0f)

		// Let activation complete
		advanceTimeBy(400)
		assertThat(silentActivatedKeys).containsExactly(4)
	}

	// ── Moved-to-neighbor tests ────────────────────────────────────────

	@Test
	fun `moving to neighbor in activation zone clears highlights`() = testScope.runTest {
		// Enter activation zone (RIGHT)
		subsystem.handleInput(0.8f, 0f)
		assertThat(subsystem.isInActivationSequence).isTrue()

		clearTracking()

		// Move to UP while still in activation zone
		subsystem.handleInput(0f, -0.8f)
		// Should cancel activation and clear highlights
		assertThat(subsystem.isInActivationSequence).isFalse()
	}

	@Test
	fun `moving to neighbor cancels pending activation job`() = testScope.runTest {
		subsystem.handleInput(0.8f, 0f) // Enter activation (RIGHT)

		// Before 100ms activation fires, move to neighbor
		advanceTimeBy(50)
		subsystem.handleInput(0f, -0.8f) // Move to UP

		// Advance past original activation time
		advanceTimeBy(100)
		// Key should NOT have been activated
		assertThat(silentActivatedKeys).isEmpty()
	}

	// ── View bridge interaction tests ──────────────────────────────────

	@Test
	fun `handleInput returns immediately when view not ready`() = testScope.runTest {
		viewReady = false
		subsystem.handleInput(0.8f, 0f)
		assertThat(buttonDrawables).isEmpty()
		assertThat(subsystem.isInActivationSequence).isFalse()
	}

	@Test
	fun `handleInput returns immediately when joystickState is null`() = testScope.runTest {
		// Create a fresh subsystem without loading settings
		val fresh = JoystickSubsystem(
			scope = testScope,
			viewBridge = viewBridge,
			keySink = keySink,
			callbacks = callbacks,
		)
		fresh.handleInput(0.8f, 0f)
		assertThat(buttonDrawables).isEmpty()
	}

	// ── Cancel and clear tests ─────────────────────────────────────────

	@Test
	fun `cancelAndClear cancels activation and clears highlight`() = testScope.runTest {
		subsystem.handleInput(0.8f, 0f) // Start activation sequence
		assertThat(subsystem.isInActivationSequence).isTrue()
		assertThat(buttonDrawables).isNotEmpty()

		subsystem.cancelAndClear()
		assertThat(subsystem.isInActivationSequence).isFalse()

		// Advance time — activation should NOT fire
		advanceTimeBy(200)
		assertThat(silentActivatedKeys).isEmpty()
	}

	@Test
	fun `cancelAndClear when no activation in progress`() = testScope.runTest {
		subsystem.handleInput(0.4f, 0f) // Feedback highlight only
		assertThat(buttonDrawables).isNotEmpty()

		subsystem.cancelAndClear()
		assertThat(buttonDrawables).isEmpty()
	}

	// ── Lifecycle tests ────────────────────────────────────────────────

	@Test
	fun `destroy cancels all pending jobs`() = testScope.runTest {
		subsystem.handleInput(0.8f, 0f) // Start activation
		assertThat(subsystem.isInActivationSequence).isTrue()

		subsystem.destroy()
		assertThat(subsystem.isInActivationSequence).isFalse()

		advanceTimeBy(200)
		assertThat(silentActivatedKeys).isEmpty()
	}

	// ── Settings loading tests ─────────────────────────────────────────

	@Test
	fun `loadSettings creates JoystickState with correct zones`() = testScope.runTest {
		repo.edit()
			.putFloat(KEY_JOYSTICK_DEADZONE, 0.30f)
			.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.70f)
			.apply()
		subsystem.loadSettings(repo)

		// Magnitude ~0.25 should be in dead zone (below 0.30)
		subsystem.handleInput(0.25f, 0f)
		assertThat(buttonDrawables).isEmpty()

		// Magnitude ~0.5 should be in feedback zone (between 0.30 and 0.70)
		subsystem.handleInput(0.5f, 0f)
		assertThat(buttonDrawables[4]).isEqualTo(R.drawable.button_background_feedback)
	}

	@Test
	fun `activeZone clamped to deadzone plus 0_01`() = testScope.runTest {
		repo.edit()
			.putFloat(KEY_JOYSTICK_DEADZONE, 0.50f)
			.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.40f) // Less than deadzone
			.apply()
		subsystem.loadSettings(repo)

		// Active zone should be clamped to 0.51 (0.50 + 0.01)
		// Magnitude ~0.52 should be in activation zone
		subsystem.handleInput(0.52f, 0f)
		assertThat(subsystem.isInActivationSequence).isTrue()
	}

	@Test
	fun `legacy corner bias migration`() = testScope.runTest {
		// Start completely fresh so migration path triggers from legacy key
		repo.clearForTesting()
		repo.putFloat(KEY_JOYSTICK_DEADZONE, 0.25f)
		repo.putFloat(KEY_JOYSTICK_ACTIVEZONE, 0.60f)
		repo.putFloat(KEY_CORNER_BIAS, 1.5f)

		subsystem.loadSettings(repo)

		// Should have migrated the value
		assertThat(repo.getFloat(KEY_JOYSTICK_CORNER_BIAS, 0f)).isEqualTo(1.5f)
	}

	@Test
	fun `corner bias clamped to 0_5 to 2_0`() = testScope.runTest {
		repo.putFloat(KEY_JOYSTICK_CORNER_BIAS, 5.0f) // Way above 2.0
		subsystem.loadSettings(repo)

		// Should still work (bias is clamped internally)
		subsystem.handleInput(0.4f, 0f)
		assertThat(buttonDrawables).isNotEmpty()
	}

	@Test
	fun `loadSettings updates flash and beep settings`() = testScope.runTest {
		repo.putBoolean(KEY_FLASH_KEY_FEEDBACK, false)
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, false)
		subsystem.loadSettings(repo)
		assertThat(subsystem.flashEnabled).isFalse()
		assertThat(subsystem.beepEnabled).isFalse()

		repo.putBoolean(KEY_FLASH_KEY_FEEDBACK, true)
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		assertThat(subsystem.flashEnabled).isTrue()
		assertThat(subsystem.beepEnabled).isTrue()
	}

	// ── Key sink ready tests ───────────────────────────────────────────

	@Test
	fun `activation does not fire key when keySink not ready`() = testScope.runTest {
		keySinkReady = false
		subsystem.handleInput(0.8f, 0f) // Enter activation

		advanceTimeBy(101)
		assertThat(silentActivatedKeys).isEmpty()
		// Beep should still play (gated by subsystem, not keySink)
		assertThat(beepCount).isEqualTo(1)
	}

	// ── Activation sequence state during highlight updates ─────────────

	@Test
	fun `highlights do not change during activation sequence`() = testScope.runTest {
		subsystem.handleInput(0.8f, 0f) // Enter activation (RIGHT = 4)
		assertThat(subsystem.isInActivationSequence).isTrue()
		clearTracking()

		// Send another input while in activation sequence (still RIGHT, still activation)
		subsystem.handleInput(0.8f, 0.01f)
		// Should return early — no additional highlight changes
		assertThat(restoredBackgrounds).isEmpty()
	}

	@Test
	fun `activation uses activateKeySilent not activateKey`() = testScope.runTest {
		val regularActivations = mutableListOf<Int>()
		val sink = object : KeyActivationSink {
			override fun activateKey(index: Int) {
				regularActivations.add(index)
			}
			override fun activateSelect() { /* Not used by JoystickSubsystem */ }
			override fun activateKeySilent(index: Int) {
				silentActivatedKeys.add(index)
			}
			override fun isReady(): Boolean = true
		}
		val sub = JoystickSubsystem(
			scope = testScope,
			viewBridge = viewBridge,
			keySink = sink,
			callbacks = callbacks,
		)
		sub.loadSettings(repo)
		silentActivatedKeys.clear()

		sub.handleInput(0.8f, 0f)
		advanceTimeBy(101)

		assertThat(regularActivations).isEmpty()
		assertThat(silentActivatedKeys).containsExactly(4)
	}
}
