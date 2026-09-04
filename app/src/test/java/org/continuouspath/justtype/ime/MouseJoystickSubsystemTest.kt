package org.continuouspath.justtype.ime

import android.view.MotionEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.continuouspath.justtype.Constants.INPUT_METHOD_MOUSE_JOYSTICK
import org.continuouspath.justtype.Constants.KEY_BEEP_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_FLASH_KEY_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_ACTIVEZONE
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_DEADZONE
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS
import org.continuouspath.justtype.Constants.KEY_MOUSE_JOYSTICK_SENSITIVITY_DP
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MouseJoystickSubsystemTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private lateinit var testScope: TestScope
	private lateinit var subsystem: MouseJoystickSubsystem
	private lateinit var repo: SettingsRepository

	private val buttonDrawables = mutableMapOf<Int, Int>()
	private val restoredBackgrounds = mutableListOf<Int>()
	private val keyboardBorderStates = mutableListOf<Boolean>()
	private val pointerHiddenStates = mutableListOf<Boolean>()
	private val silentActivatedKeys = mutableListOf<Int>()
	private var acquireToneCount = 0
	private var releaseToneCount = 0
	private var exitTickCount = 0

	// Running absolute cursor position; the subsystem differences consecutive rawX/rawY.
	private var cursorX = 0f
	private var cursorY = 0f

	// Monotonic eventTime for the hover mocks; each event advances it by EVENT_GAP_MS.
	private var probeTimeMs = 1000L

	private val viewBridge = object : JoystickViewBridge {
		override val buttonCount: Int = 8
		override val isViewReady: Boolean = true

		override fun setButtonDrawable(index: Int, drawableResId: Int) {
			buttonDrawables[index] = drawableResId
		}

		override fun restoreButtonBackground(index: Int) {
			restoredBackgrounds.add(index)
			buttonDrawables.remove(index)
		}

		override fun showKeyboardBorder(show: Boolean) {
			keyboardBorderStates.add(show)
		}

		override fun setMousePointerHidden(hidden: Boolean) {
			pointerHiddenStates.add(hidden)
		}
	}

	private val keySink = object : KeyActivationSink {
		override fun activateKey(index: Int) { /* not used by MJ */ }
		override fun activateSelect() { /* not used by MJ */ }
		override fun activateKeySilent(index: Int) {
			silentActivatedKeys.add(index)
		}
		override fun isReady(): Boolean = true
	}

	private val callbacks = object : MouseJoystickCallbacks {
		override fun playActivationBeep() { /* test fakes don't beep */ }
		override fun debugLog(message: String) { /* test fakes don't log */ }
		override fun playCaptureAcquiredTone() {
			acquireToneCount++
		}
		override fun playCaptureReleasedTone() {
			releaseToneCount++
		}
		override fun playExitCountdownTick() {
			exitTickCount++
		}
		override fun verifyInputConnectionLive() { /* capture never granted under Robolectric */ }
	}

	@Before
	fun setUp() {
		testScope = TestScope(StandardTestDispatcher())
		// Repo writes consult the registry (RegistryAwareRepo); init explicitly rather than
		// relying on an earlier test class in the same worker JVM having done it.
		org.continuouspath.justtype.settings.SettingsRegistry.reinitialize(RuntimeEnvironment.getApplication())
		repo = SettingsRepository.getInstance(RuntimeEnvironment.getApplication())
		repo.clearForTesting()
		// Default to method active so isEnabled = true after loadSettings.
		repo.putString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_MOUSE_JOYSTICK)
		repo.putFloat(KEY_MOUSE_JOYSTICK_DEADZONE, 0.25f)
		repo.putFloat(KEY_MOUSE_JOYSTICK_ACTIVEZONE, 0.60f)
		// With the 10ms event gap, fed speed = delta*100 px/sec; sensitivity 500 dp/sec makes the
		// normalized magnitude equal the raw per-move delta (density cancels), so a moveBy(dx) maps
		// dx→magnitude just as before B1 — keeping the arithmetic assertions below intact.
		repo.putInt(KEY_MOUSE_JOYSTICK_SENSITIVITY_DP, 500)
		repo.putInt(KEY_MOUSE_JOYSTICK_EXIT_DELAY_MS, 1000)
		repo.putInt(KEY_MOUSE_JOYSTICK_REENGAGE_HYSTERESIS_MS, 750)
		repo.putBoolean(KEY_FLASH_KEY_FEEDBACK, false)
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, false)

		subsystem = MouseJoystickSubsystem(
			context = RuntimeEnvironment.getApplication(),
			scope = testScope,
			viewBridge = viewBridge,
			keySink = keySink,
			callbacks = callbacks,
		)
		subsystem.loadSettings(repo)
		// Enter the keyboard so the hover baseline is seeded (first move produces no delta).
		enterKeyboard()
	}

	@After
	fun tearDown() {
		subsystem.destroy()
	}

	// ── Event helpers ──────────────────────────────────────────────────

	/** Deliver HOVER_ENTER and seed the delta baseline with one zero-delta move. */
	private fun enterKeyboard() {
		// Mid-display start: Robolectric's display is 320x470, and a position on/past an edge would
		// trip the edge-pin synthesis on the zero-delta seed move.
		cursorX = 160f
		cursorY = 235f
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		// First move only establishes lastHoverX/Y — no delta fed.
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_MOVE))
	}

	/**
	 * A hover event at the current cursor position. Each event advances eventTime by [EVENT_GAP_MS] so
	 * the subsystem's time-normalized velocity (px/sec = delta / dt) sees a fixed gap: with a 10ms gap
	 * the fed speed is delta * 100 px/sec.
	 */
	private fun hoverEvent(action: Int): MotionEvent {
		probeTimeMs += EVENT_GAP_MS
		val t = probeTimeMs
		return mock {
			on { this.action } doReturn action
			on { rawX } doReturn cursorX
			on { rawY } doReturn cursorY
			on { eventTime } doReturn t
		}
	}

	/** Advance the cursor by (dx, dy) and deliver the resulting HOVER_MOVE (delta = dx, dy). */
	private fun moveBy(dx: Float, dy: Float): MotionEvent {
		val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
		cursorX += dx * density
		cursorY += dy * density
		val event = hoverEvent(MotionEvent.ACTION_HOVER_MOVE)
		subsystem.handleMouseHoverEvent(event)
		return event
	}

	// ── Engage on hover-enter ──────────────────────────────────────────

	@Test
	fun `hover-enter engages and plays the engage tone when beep enabled`() {
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		subsystem.cancelAndClear() // setUp already engaged; start disengaged so this ENTER is fresh
		acquireToneCount = 0
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(acquireToneCount).isEqualTo(1)
	}

	@Test
	fun `re-entering the keyboard from the barrier does not replay the engage tone`() {
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		subsystem.cancelAndClear()
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER)) // fresh engage
		acquireToneCount = 0
		// A barrier edge re-crossing re-fires ENTER while still engaged — the tone must NOT replay.
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(acquireToneCount).isEqualTo(0)
	}

	@Test
	fun `hover event ignored when disabled`() {
		repo.putString(KEY_INPUT_METHOD_PRIMARY, "")
		subsystem.loadSettings(repo)
		assertThat(subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))).isFalse()
	}

	@Test
	fun `pointer hides once on engage and restores on teardown, without thrashing on re-enter`() {
		subsystem.loadSettings(repo)
		// First engage hides the OS pointer.
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(pointerHiddenStates).containsExactly(true)
		// A re-enter (e.g. crossing back over the barrier edge) must NOT re-toggle — session-sticky.
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(pointerHiddenStates).containsExactly(true)
		// Teardown restores the pointer, so a later plain-mouse user never inherits a hidden cursor.
		subsystem.cancelAndClear()
		assertThat(pointerHiddenStates).containsExactly(true, false).inOrder()
		// A fresh session hides again.
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(pointerHiddenStates).containsExactly(true, false, true).inOrder()
	}

	// ── Hover movement → highlight ─────────────────────────────────────

	@Test
	fun `right movement produces feedback highlight on right key`() = testScope.runTest {
		// sensitivityPx clamps SENSITIVITY_DP to min 5 → sensitivityPx ≥ 5.0.
		// EMA(0.4): smoothedVx = 0.4 * dx. Want norm = smoothedVx / 5 in feedback (0.25–0.60).
		// dx = 5 → smoothedVx = 2.0 → norm = 0.4 (feedback zone).
		moveBy(5f, 0f)
		// RIGHT octant index is 4.
		assertThat(buttonDrawables[4]).isEqualTo(R.drawable.button_background_feedback)
	}

	@Test
	fun `a choppy device and a smooth device at the same speed reach the same zone`() {
		// B1: velocity is px/sec, so a choppy stick (big delta, big gap) and a smooth one (small delta,
		// small gap) moving at the same physical speed land in the same zone — instead of the choppy
		// one spiking on its huge per-event jump. Both here move RIGHT at ~the same px/sec.
		val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
		// Smooth: 2px every 10ms (= 200 dp/sec-equiv → feedback zone). Drive several to settle the EMA.
		repeat(8) { deliverMove(dxDp = 2f, gapMs = 10L, density) }
		val smoothZoneKeys = buttonDrawables.keys.toSet()

		// Reset and drive the choppy device: 20px every 100ms — the SAME 200 dp/sec-equiv speed.
		cancelAndReset()
		repeat(8) { deliverMove(dxDp = 20f, gapMs = 100L, density) }
		val choppyZoneKeys = buttonDrawables.keys.toSet()

		// Both highlight the RIGHT key (index 4) — the choppy jump did not over-spike into another zone.
		assertThat(smoothZoneKeys).contains(4)
		assertThat(choppyZoneKeys).contains(4)
	}

	private fun cancelAndReset() {
		subsystem.cancelAndClear()
		buttonDrawables.clear()
		enterKeyboard()
	}

	private fun deliverMove(dxDp: Float, gapMs: Long, density: Float) {
		cursorX += dxDp * density
		probeTimeMs += gapMs
		val t = probeTimeMs
		subsystem.handleMouseHoverEvent(
			mock {
				on { action } doReturn MotionEvent.ACTION_HOVER_MOVE
				on { rawX } doReturn cursorX
				on { rawY } doReturn cursorY
				on { eventTime } doReturn t
			},
		)
	}

	@Test
	fun `clampToUnit caps magnitude at one while preserving the push angle`() {
		// The diagonal-snap fix: a strong off-45 vector must scale to magnitude 1 keeping its ANGLE,
		// not clamp each axis (which inflated diagonals to hypot(1,1)=1.41 and snapped the direction).
		// A mostly-right push (2.0, -0.5): per-axis clamp → (1.0, -0.5), angle 27 degrees (diagonal);
		// vector clamp → same direction as (2.0, -0.5), angle ~14 degrees (still RIGHT).
		val (x, y) = MouseJoystickSubsystem.clampToUnitForTest(2.0f, -0.5f)
		assertThat(kotlin.math.hypot(x, y)).isWithin(0.001f).of(1f)
		// Angle preserved: y/x ratio unchanged from the input.
		assertThat(y / x).isWithin(0.001f).of(-0.5f / 2.0f)
		// A vector already within the unit circle is returned unchanged.
		val (ix, iy) = MouseJoystickSubsystem.clampToUnitForTest(0.3f, -0.4f)
		assertThat(ix).isEqualTo(0.3f)
		assertThat(iy).isEqualTo(-0.4f)
	}

	@Test
	fun `clampDelta caps a teleport jump at the max frame delta, keeping direction`() {
		// A choppy joystick's full-screen jump (e.g. 1000px right, 200px down) must be scaled down to
		// the cap so a single frame can't slam the cursor into a screen edge — but its ANGLE is kept.
		val maxPx = 300f
		val (dx, dy) = MouseJoystickSubsystem.clampDeltaForTest(1000f, 200f, maxPx)
		assertThat(kotlin.math.hypot(dx, dy)).isWithin(0.01f).of(maxPx)
		assertThat(dy / dx).isWithin(0.001f).of(200f / 1000f) // direction preserved
		// A normal small frame delta passes through untouched.
		val (sx, sy) = MouseJoystickSubsystem.clampDeltaForTest(12f, -8f, maxPx)
		assertThat(sx).isEqualTo(12f)
		assertThat(sy).isEqualTo(-8f)
	}

	// ── UP-direction dwell exit timer ──────────────────────────────────

	@Test
	fun `sustained UP at high magnitude exits after the dwell delay`() = testScope.runTest {
		// Push large UP delta so smoothed/coerced normY saturates to -1 (exit zone ≥0.95).
		// Sustain by re-feeding every 50 ms — velocity decay kicks in at 150 ms; without
		// fresh input, magnitude drops and cancels the timer. Exit delay = 1000 ms.
		val deadline = 1200L
		var elapsed = 0L
		while (elapsed < deadline) {
			moveBy(0f, -100f)
			advanceTimeBy(50)
			elapsed += 50
		}
		assertThat(keyboardBorderStates).contains(true)
	}

	@Test
	fun `exit dwell completes when velocity decays to dead (cursor pinned at screen top)`() = testScope.runTest {
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		releaseToneCount = 0
		// One strong UP push starts the exit timer, then NO further motion — as when the cursor is
		// pinned at the physical screen top and rawY can't decrease, so velocity decays to DEAD.
		// Regression: this used to cancel the dwell; the exit must still complete.
		moveBy(0f, -100f)
		advanceTimeBy(1200) // past exitDelayMs (1000) with no re-feed
		assertThat(releaseToneCount).isAtLeast(1)
	}

	@Test
	fun `exit dwell ticks audibly so the countdown is not visual-only`() = testScope.runTest {
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		exitTickCount = 0
		// Sustain UP long enough to reach the flash/tick phase (starts at exitDelayMs/2 = 500ms).
		val deadline = 900L
		var elapsed = 0L
		while (elapsed < deadline) {
			moveBy(0f, -100f)
			advanceTimeBy(50)
			elapsed += 50
		}
		assertThat(exitTickCount).isAtLeast(1)
	}

	@Test
	fun `direction change off UP cancels exit timer`() = testScope.runTest {
		// Push UP saturating delta to start exit timer.
		moveBy(0f, -100f)
		// Counter-EMA: push DOWN with larger delta to flip smoothedVy positive.
		repeat(5) {
			moveBy(0f, 100f)
			advanceTimeBy(20)
		}
		// Even with subsequent time, no border-off/on flashing from a fired timer.
		advanceTimeBy(2000)
		// A cancelled timer leaves at most the initial border-on then border-off, never a release.
		assertThat(silentActivatedKeys).isEmpty()
	}

	// ── Positive key activation ────────────────────────────────────────

	@Test
	fun `push into the activation zone fires the RIGHT key after the dwell`() = testScope.runTest {
		// The whole point of MJ: a push must eventually type a key. dx=8 → EMA →
		// normX ~0.64, inside ACT (0.60..0.95 — harder saturates into EXIT
		// suppression); the 100ms activation timer then fires octant 4 once.
		moveBy(8f, 0f)
		advanceTimeBy(150)
		assertThat(silentActivatedKeys).containsExactly(4)
	}

	// ── Teardown + disable ─────────────────────────────────────────────

	@Test
	fun `cancelAndClear clears highlight and pending timer`() = testScope.runTest {
		moveBy(0f, -100f)
		subsystem.cancelAndClear()
		advanceTimeBy(2000)
		assertThat(buttonDrawables).isEmpty()
	}

	@Test
	fun `disabling subsystem while timer pending cancels timer cleanly`() = testScope.runTest {
		moveBy(0f, -100f)
		advanceTimeBy(100)

		// Disable by switching primary method away.
		repo.putString(KEY_INPUT_METHOD_PRIMARY, "")
		subsystem.loadSettings(repo)
		advanceTimeBy(2000)

		assertThat(buttonDrawables).isEmpty()
	}

	// ── EXIT-zone activation suppression ───────────────────────────────

	@Test
	fun `fast push past activation ring into EXIT does not type a stray character`() = testScope.runTest {
		// First push lands in ACTIVATION zone: dy=-8 → EMA → normY ~ -0.64 (UP, mag 0.64).
		// This schedules a 100ms activation timer in MouseJoystickSubsystem.
		moveBy(0f, -8f)
		assertThat(buttonDrawables[1]).isEqualTo(R.drawable.button_background_joystick_pale_green)

		// Brief delay — not enough to fire activation (ACTIVATION_DELAY_MS = 100).
		advanceTimeBy(50)

		// Second push jumps past the activation ring into EXIT (mag clamps to 1.0).
		moveBy(0f, -100f)

		// Advance past activation delay and exit dwell start.
		advanceTimeBy(200)

		// Headline assertion: no stray keystroke fired even though we briefly held ACT.
		assertThat(silentActivatedKeys).isEmpty()
	}

	@Test
	fun `idle feedback frames do not spam showKeyboardBorder`() = testScope.runTest {
		keyboardBorderStates.clear()
		// Hold cursor in FEEDBACK zone (no EXIT activity).
		repeat(5) {
			moveBy(5f, 0f)
			advanceTimeBy(20)
		}
		// cancelExitTimer should be a no-op when no timer is running.
		assertThat(keyboardBorderStates).isEmpty()
	}

	// ── Re-engage hysteresis ───────────────────────────────────────────
	// Note: ShadowSystemClock.advanceBy moves SystemClock.uptimeMillis(), which the
	// hysteresis check reads. The coroutine StandardTestDispatcher's virtual time
	// (advanceTimeBy) is independent — these advance separate clocks.

	@Test
	fun `voluntary dwell-exit blocks re-engage until hysteresis elapses`() = testScope.runTest {
		// Drive a full UP dwell to trigger a voluntary exit.
		val deadline = 1200L
		var elapsed = 0L
		while (elapsed < deadline) {
			moveBy(0f, -100f)
			advanceTimeBy(50)
			elapsed += 50
		}
		acquireToneCount = 0
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)

		// Immediate re-enter — blocked (no engage tone).
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(acquireToneCount).isEqualTo(0)

		// Just before the window closes — still blocked.
		ShadowSystemClock.advanceBy(Duration.ofMillis(700))
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(acquireToneCount).isEqualTo(0)

		// Past the window — re-engage allowed (engage tone fires).
		ShadowSystemClock.advanceBy(Duration.ofMillis(100))
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(acquireToneCount).isEqualTo(1)
	}

	@Test
	fun `involuntary teardown does not engage hysteresis`() {
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		subsystem.cancelAndClear() // e.g. keyboard hidden — not a user exit
		acquireToneCount = 0

		// Re-enter is immediately allowed — teardown is not a voluntary release.
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(acquireToneCount).isEqualTo(1)
	}

	@Test
	fun `hover moves during the hysteresis window do not engage or type`() = testScope.runTest {
		// Bug: hysteresis swallowed ENTER but not MOVE, so the kbd silently typed while "blocked" —
		// engaged stayed false yet MOVEs drove highlights/activations. Now MOVEs are gated on engaged.
		val deadline = 1200L
		var elapsed = 0L
		while (elapsed < deadline) { // drive a full UP dwell → voluntary exit → hysteresis armed
			moveBy(0f, -100f)
			advanceTimeBy(50)
			elapsed += 50
		}
		assertThat(subsystem.isEngaged).isFalse() // voluntary exit tore the session down
		silentActivatedKeys.clear()
		buttonDrawables.clear()
		// ENTER is swallowed by hysteresis, so the session stays disengaged; feeding MOVEs a magnitude
		// that WOULD activate the RIGHT key (see the feedback-highlight test) must do nothing.
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		assertThat(subsystem.isEngaged).isFalse() // still blocked
		repeat(4) { moveBy(5f, 0f) }
		advanceTimeBy(200) // past ACTIVATION_DELAY_MS — a fed move would have committed by now
		assertThat(subsystem.isEngaged).isFalse()
		assertThat(buttonDrawables).isEmpty() // no highlight
		assertThat(silentActivatedKeys).isEmpty() // nothing typed
	}

	// ── Lost cursor (beyond barrier / system windows) ──────────────────

	@Test
	fun `a lost cursor runs the graceful exit countdown instead of a silent teardown`() = testScope.runTest {
		// Bug: releasing the stick with the cursor beyond our windows (status bar / navbar) tore the
		// session down silently after 300ms — no border, no tone, cursor just popped visible.
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		enterKeyboard()
		subsystem.updateKeyboardHeight(1000)
		keyboardBorderStates.clear()
		releaseToneCount = 0
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_EXIT))
		// Silence past the detection window starts the standard countdown, not a bare teardown.
		advanceTimeBy(400) // > BARRIER_DEACTIVATE_TIMEOUT_MS (300)
		assertThat(subsystem.isEngaged).isTrue() // still alive during the countdown
		assertThat(keyboardBorderStates).contains(true) // border countdown is showing
		// The exit delay later, the session releases with the release tone — a signposted escape.
		advanceTimeBy(1100) // > exitDelayMs (1000)
		assertThat(subsystem.isEngaged).isFalse()
		assertThat(releaseToneCount).isEqualTo(1)
		assertThat(exitTickCount).isGreaterThan(0) // countdown was audible
	}

	@Test
	fun `steering back during the lost-cursor countdown keeps the session alive`() = testScope.runTest {
		enterKeyboard()
		subsystem.updateKeyboardHeight(1000)
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_EXIT))
		advanceTimeBy(400) // countdown running
		assertThat(subsystem.isEngaged).isTrue()
		// Cursor found again: re-enter the keyboard and steer sideways — a real (non-dead-zone) push
		// cancels the countdown. A single tiny move stays DEAD, which deliberately holds the dwell.
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
		repeat(2) { moveBy(5f, 0f) }
		advanceTimeBy(2000) // way past the would-be release
		assertThat(subsystem.isEngaged).isTrue() // session survived
	}

	@Test
	fun `an up push in the barrier below exit speed still starts the countdown`() = testScope.runTest {
		// EXIT_MISS on-device: hard pushes peak at 0.81-0.90, under the 0.95 exit zone, so the border
		// never flashed. Once the cursor is physically in the barrier, an activation-strength up push
		// is an escape in progress and must start the countdown.
		enterKeyboard()
		subsystem.updateKeyboardHeight(1000)
		keyboardBorderStates.clear()
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_EXIT)) // cursor now in barrier
		repeat(4) { moveBy(0f, -4f) } // sustained up converging to ~0.7 normalized - real-push territory
		assertThat(keyboardBorderStates).contains(true)
	}

	@Test
	fun `the same sub-exit up push inside the keyboard does not start the countdown`() = testScope.runTest {
		// Guard: up-key selection at activation strength inside the grid must not flash the border.
		enterKeyboard()
		keyboardBorderStates.clear()
		repeat(4) { moveBy(0f, -4f) }
		assertThat(keyboardBorderStates).isEmpty()
	}

	// ── Pointer capture (spike) ────────────────────────────────────────

	/** A captured relative-motion frame: x/y are the deltas, eventTime advances by [EVENT_GAP_MS]. */
	private fun capturedEvent(dx: Float, dy: Float): MotionEvent {
		probeTimeMs += EVENT_GAP_MS
		val t = probeTimeMs
		return mock {
			on { this.action } doReturn MotionEvent.ACTION_MOVE
			on { x } doReturn dx
			on { y } doReturn dy
			on { eventTime } doReturn t
		}
	}

	@Test
	fun `capture grant cancels an engage-time exit countdown and keeps the session alive`() = testScope.runTest {
		// Bug: the countdown born while crossing the barrier during engage survived into the captured
		// session (no hover frames arrive to cancel it) and silently tore it down 2s later.
		enterKeyboard()
		subsystem.updateKeyboardHeight(1000)
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_EXIT))
		advanceTimeBy(400) // lost-cursor countdown is now running
		subsystem.onCaptureGrantedForTest()
		advanceTimeBy(3000) // way past the countdown's would-be release
		assertThat(subsystem.isEngaged).isTrue()
	}

	@Test
	fun `captured relative motion drives selection after capture grant`() = testScope.runTest {
		// Bug: captured deltas never reached the velocity pipeline, so the session went dead after
		// the last hover-fed activation. Deltas are raw counts scaled by CAPTURE_COUNT_SCALE (16):
		// 0.125 counts / 10ms * 16 = 200 px/s = norm 0.4 at sensitivity 500 - the FEEDBACK zone.
		subsystem.onCaptureGrantedForTest()
		buttonDrawables.clear()
		repeat(4) { subsystem.feedCapturedMotionForTest(capturedEvent(0.125f, 0f)) }
		// Sustained rightward relative motion converges to the FEEDBACK zone - RIGHT (index 4) lights.
		assertThat(buttonDrawables).containsKey(4)
	}

	@Test
	fun `captured batched samples are summed, not truncated to the last one`() = testScope.runTest {
		// The OS coalesces raw HID reports per display frame: reading only x/y drops the historical
		// samples (on-device: a fast push read as +-1 count per frame). Sum = 0.05+0.05+0.025 =
		// 0.125 counts -> FEEDBACK; the last sample alone (0.025) would stay deep in the dead zone.
		subsystem.onCaptureGrantedForTest()
		buttonDrawables.clear()
		repeat(4) {
			probeTimeMs += EVENT_GAP_MS
			val t = probeTimeMs
			val event = mock<MotionEvent> {
				on { this.action } doReturn MotionEvent.ACTION_MOVE
				on { x } doReturn 0.025f
				on { y } doReturn 0f
				on { historySize } doReturn 2
				on { getHistoricalX(0) } doReturn 0.05f
				on { getHistoricalX(1) } doReturn 0.05f
				on { getHistoricalY(0) } doReturn 0f
				on { getHistoricalY(1) } doReturn 0f
				on { eventTime } doReturn t
			}
			subsystem.feedCapturedMotionForTest(event)
		}
		assertThat(buttonDrawables).containsKey(4)
	}

	@Test
	fun `a touch-pause releases capture but keeps the session alive with no countdown`() = testScope.runTest {
		// Capture redirects touchscreen events to the overlay, so a finger tap must pause MJ
		// (release capture, touches flow again) WITHOUT the capture-loss watchdog tearing it down.
		enterKeyboard()
		subsystem.updateKeyboardHeight(1000)
		subsystem.onCaptureGrantedForTest()
		keyboardBorderStates.clear()
		subsystem.pauseCaptureForTouchForTest()
		subsystem.onCaptureLostForTest() // the system's capture-change(false) that follows our hide
		advanceTimeBy(3000) // past watchdog + a full countdown
		assertThat(subsystem.isEngaged).isTrue() // paused, not torn down
		assertThat(keyboardBorderStates).isEmpty() // no countdown started
	}

	@Test
	fun `an unexpected capture loss arms the silence watchdog`() = testScope.runTest {
		// Focus stolen / window killed with the session engaged: hover may never resume, so the
		// watchdog must start the graceful countdown rather than leaving a zombie session.
		enterKeyboard()
		subsystem.updateKeyboardHeight(1000)
		subsystem.onCaptureGrantedForTest()
		keyboardBorderStates.clear()
		subsystem.onCaptureLostForTest() // no pause preceded this - unexpected
		advanceTimeBy(400) // > BARRIER_DEACTIVATE_TIMEOUT_MS (300)
		assertThat(keyboardBorderStates).contains(true) // countdown running
	}

	@Test
	fun `a stray hover exit while disengaged does not start a ghost countdown`() = testScope.runTest {
		subsystem.cancelAndClear()
		keyboardBorderStates.clear()
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_EXIT))
		advanceTimeBy(3000) // past lost-cursor detection + a full countdown
		assertThat(keyboardBorderStates).isEmpty()
	}

	// ── Edge-pin synthesis ─────────────────────────────────────────────

	@Test
	fun `pushing against a display edge keeps selecting in that direction`() = testScope.runTest {
		// Bug: with the cursor pinned on a display edge, rawX/Y freeze, so pushes toward that edge fed
		// nothing and keys in that direction were unselectable. The OS still delivers MOVEs with
		// identical coords while the stick pushes the wall — synthesize the push from them.
		subsystem.cancelAndClear()
		cursorX = 319f // right edge of Robolectric's 320px-wide display
		cursorY = 235f
		subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER)) // baseline at the edge
		buttonDrawables.clear()
		// Zero-delta MOVEs at the edge = still pushing right; EMA needs a few frames to cross zones.
		repeat(3) { subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_MOVE)) }
		// RIGHT octant index is 4 (see the feedback-highlight test) — it must light up.
		assertThat(buttonDrawables).containsKey(4)
	}

	@Test
	fun `zero-delta moves away from any edge synthesize nothing`() = testScope.runTest {
		// The same zero-delta stream mid-screen must stay inert — synthesis only fires on an edge.
		subsystem.cancelAndClear()
		enterKeyboard() // mid-display baseline
		buttonDrawables.clear()
		repeat(3) { subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_MOVE)) }
		assertThat(buttonDrawables).isEmpty()
	}

	// ── Mouse-button release ───────────────────────────────────────────

	@Test
	fun `a mouse-button press releases capture and its touch does not type`() = testScope.runTest {
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		enterKeyboard()
		releaseToneCount = 0
		// The click's button-press releases immediately (voluntary exit → release tone).
		assertThat(subsystem.handleMouseHoverEvent(hoverEvent(MotionEvent.ACTION_BUTTON_PRESS))).isTrue()
		assertThat(releaseToneCount).isEqualTo(1)
		// The touch that same click delivers is swallowed so it can't land as a keystroke.
		assertThat(subsystem.handleMouseTouchEvent()).isTrue()
	}

	// ── Exit tone ──────────────────────────────────────────────────────

	@Test
	fun `dwell-exit plays the exit tone when beep enabled`() = testScope.runTest {
		repo.putBoolean(KEY_BEEP_KEY_FEEDBACK, true)
		subsystem.loadSettings(repo)
		releaseToneCount = 0

		val deadline = 1200L
		var elapsed = 0L
		while (elapsed < deadline) {
			moveBy(0f, -100f)
			advanceTimeBy(50)
			elapsed += 50
		}
		assertThat(releaseToneCount).isAtLeast(1)
	}

	private companion object {
		// Fixed per-event time gap for the hover mocks; makes the time-normalized speed = delta / gap.
		const val EVENT_GAP_MS = 10L
	}
}
