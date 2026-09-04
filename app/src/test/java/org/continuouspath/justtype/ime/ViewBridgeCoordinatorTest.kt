package org.continuouspath.justtype.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.continuouspath.justtype.R
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/**
 * Characterization tests for [ViewBridgeCoordinator].
 *
 * Pins down the 5 bridge interfaces consolidated by this class:
 * [HighlightBridge], [KeyActivationSink], [TwoSwitchViewBridge],
 * [JoystickViewBridge], [HeadTrackingViewBridge]. Production code is
 * unchanged — assertions lock current behavior including the
 * pass-by-reference maps shared with [KeyFeedbackController] and
 * the layout-swap safety provided by `isButtonsReady`/`getOrNull`.
 *
 * No mocks: hand-rolled fake for [ViewBridgeCallbacks], Robolectric
 * for stateful views, real `Drawable` instances throughout. Flash
 * timing uses direct invocation of the captured restore Runnable
 * (3.3 lesson — Robolectric's `idle()`/`idleFor()` did not flush
 * `postDelayed` runnables reliably).
 */
@RunWith(RobolectricTestRunner::class)
class ViewBridgeCoordinatorTest {

	private lateinit var context: Context
	private lateinit var coordinator: ViewBridgeCoordinator
	private lateinit var scope: CoroutineScope

	private var currentButtons: List<Button> = emptyList()
	private val buttonOriginalBackgrounds = mutableMapOf<Button, Drawable>()
	private val flashRestores = mutableMapOf<Button, Runnable>()
	private val standingBackgrounds = mutableMapOf<Button, Drawable>()

	// State backing the ViewBridgeCallbacks fake.
	private var clView: TextView? = null
	private var slView: TextView? = null
	private var clOriginalBackground: Drawable? = null
	private var buttonsJT: List<Button> = emptyList()
	private var jtuiInitialized = true

	private val triggerKeyActivationCalls = mutableListOf<Int>()
	private val buttonPressedOnUiThreadCalls = mutableListOf<Int>()
	private var triggerSelectActivationCount = 0

	private val callbacks = object : ViewBridgeCallbacks {
		override val centerLabelView: TextView?
			get() = this@ViewBridgeCoordinatorTest.clView
		override val selectionListView: TextView?
			get() = this@ViewBridgeCoordinatorTest.slView
		override var centerLabelOriginalBackground: Drawable?
			get() = this@ViewBridgeCoordinatorTest.clOriginalBackground
			set(value) {
				this@ViewBridgeCoordinatorTest.clOriginalBackground = value
			}

		override fun getButtonsJT(): List<Button> = this@ViewBridgeCoordinatorTest.buttonsJT

		override fun triggerKeyActivation(index: Int, suppressBeep: Boolean) {
			triggerKeyActivationCalls.add(index)
		}

		override fun triggerSelectActivation() {
			triggerSelectActivationCount++
		}

		override val isJtuiInitialized: Boolean
			get() = this@ViewBridgeCoordinatorTest.jtuiInitialized

		override fun buttonPressedOnUiThread(index: Int) {
			buttonPressedOnUiThreadCalls.add(index)
		}
	}

	@Before
	fun setUp() {
		context = RuntimeEnvironment.getApplication()
		scope = CoroutineScope(Dispatchers.Unconfined)
		coordinator = ViewBridgeCoordinator(
			context,
			scope,
			{ currentButtons },
			buttonOriginalBackgrounds,
			flashRestores,
			standingBackgrounds,
			callbacks,
		)
	}

	@After
	fun tearDown() {
		// Guard the lateinit: a setUp failure must not mask itself here and
		// must still reach the looper idle (this class previously wedged workers).
		if (::scope.isInitialized) {
			scope.cancel()
		}
		Shadows.shadowOf(Looper.getMainLooper()).idle()
	}

	private fun makeButton(initialBg: Drawable = ColorDrawable(Color.GRAY)): Button = Button(context).also { it.background = initialBg }

	private fun populate(n: Int) {
		currentButtons = (0 until n).map { makeButton() }
	}

	private fun captureOriginals() {
		currentButtons.forEach { buttonOriginalBackgrounds[it] = it.background }
	}

	// region Group 1 — HighlightBridge

	@Test
	fun `highlightButton tints target and leaves siblings untouched`() {
		populate(3)
		captureOriginals()
		val originalBg1 = currentButtons[1].background

		coordinator.highlightButton(0, Color.RED)

		// Highlighted button now has a different (tinted) drawable.
		assertThat(currentButtons[0].background).isNotSameInstanceAs(buttonOriginalBackgrounds[currentButtons[0]])
		// Sibling untouched.
		assertThat(currentButtons[1].background).isSameInstanceAs(originalBg1)
	}

	@Test
	fun `highlightButton no-op when buttons list empty`() {
		coordinator.highlightButton(0, Color.RED)
		// No exception, no state change.
		assertThat(buttonOriginalBackgrounds).isEmpty()
	}

	@Test
	fun `clearHighlights restores all backgrounds and nullifies foregrounds`() {
		populate(3)
		captureOriginals()
		val originals = currentButtons.map { buttonOriginalBackgrounds[it] }
		// Mutate to simulate prior highlight.
		currentButtons.forEach {
			it.background = ColorDrawable(Color.MAGENTA)
			it.foreground = ColorDrawable(Color.BLUE)
		}

		coordinator.clearHighlights()

		currentButtons.forEachIndexed { idx, btn ->
			assertThat(btn.background).isSameInstanceAs(originals[idx])
			assertThat(btn.foreground).isNull()
		}
	}

	@Test
	fun `highlightButtons applies map entries and skips unmentioned indices`() {
		populate(3)
		captureOriginals()
		val originalBg1 = currentButtons[1].background

		coordinator.highlightButtons(mapOf(0 to Color.YELLOW, 2 to Color.parseColor("#FF8800")))

		assertThat(currentButtons[0].background).isNotSameInstanceAs(buttonOriginalBackgrounds[currentButtons[0]])
		assertThat(currentButtons[1].background).isSameInstanceAs(originalBg1)
		assertThat(currentButtons[2].background).isNotSameInstanceAs(buttonOriginalBackgrounds[currentButtons[2]])
	}

	@Test
	fun `restoreButton restores single background and clears its foreground, out-of-range no-op`() {
		populate(3)
		captureOriginals()
		val original0 = buttonOriginalBackgrounds[currentButtons[0]]
		// Simulate prior highlight on button 0.
		currentButtons[0].background = ColorDrawable(Color.MAGENTA)
		currentButtons[0].foreground = ColorDrawable(Color.BLUE)
		// Sibling stays in its mutated state to confirm we don't touch it.
		val mutatedBg2 = ColorDrawable(Color.CYAN)
		currentButtons[2].background = mutatedBg2

		coordinator.restoreButton(0)
		coordinator.restoreButton(99) // out-of-range → no-op

		assertThat(currentButtons[0].background).isSameInstanceAs(original0)
		assertThat(currentButtons[0].foreground).isNull()
		assertThat(currentButtons[2].background).isSameInstanceAs(mutatedBg2)
	}

	@Test
	fun `flashButton tints immediately, restore Runnable resets background and fires onComplete`() {
		populate(2)
		captureOriginals()
		val original = buttonOriginalBackgrounds[currentButtons[0]]
		var completedCalls = 0

		coordinator.flashButton(0, Color.RED, durationMs = 100L) { completedCalls++ }

		// Immediate effect: tinted background, restore Runnable captured.
		assertThat(currentButtons[0].background).isNotSameInstanceAs(original)
		assertThat(flashRestores[currentButtons[0]]).isNotNull()
		assertThat(completedCalls).isEqualTo(0)

		// Direct-invoke the captured runnable (3.3 lesson).
		flashRestores[currentButtons[0]]!!.run()

		assertThat(currentButtons[0].background).isSameInstanceAs(original)
		assertThat(flashRestores[currentButtons[0]]).isNull()
		assertThat(completedCalls).isEqualTo(1)
	}

	@Test
	fun `flash restore settles to a standing tint applied DURING the flash, not a stale snapshot`() {
		populate(2)
		captureOriginals()

		coordinator.flashButton(0, Color.RED, 100L)
		// A subsystem repaint lands mid-flash (e.g. two-switch split moved under an error flash).
		coordinator.tintButton(0, Color.BLUE)
		val standing = standingBackgrounds[currentButtons[0]]
		assertThat(standing).isNotNull()

		flashRestores[currentButtons[0]]!!.run()

		assertThat(currentButtons[0].background).isSameInstanceAs(standing)
	}

	@Test
	fun `flash restore settles to original after a standing tint is cleared DURING the flash`() {
		populate(2)
		captureOriginals()
		coordinator.tintButton(0, Color.BLUE)

		coordinator.flashButton(0, Color.RED, 100L)
		coordinator.restoreButtonBackground(0) // standing tint cleared mid-flash

		flashRestores[currentButtons[0]]!!.run()

		assertThat(currentButtons[0].background).isSameInstanceAs(buttonOriginalBackgrounds[currentButtons[0]])
	}

	@Test
	fun `flashButton called twice on same button replaces the prior pending restore`() {
		populate(2)
		captureOriginals()

		coordinator.flashButton(0, Color.RED, 100L)
		val firstRunnable = flashRestores[currentButtons[0]]
		assertThat(firstRunnable).isNotNull()

		coordinator.flashButton(0, Color.GREEN, 100L)
		val secondRunnable = flashRestores[currentButtons[0]]

		assertThat(secondRunnable).isNotNull()
		assertThat(secondRunnable).isNotSameInstanceAs(firstRunnable)
	}

	// endregion

	// region Group 2 — KeyActivationSink

	@Test
	fun `activateKey forwards to triggerKeyActivation`() {
		coordinator.activateKey(3)

		assertThat(triggerKeyActivationCalls).containsExactly(3)
	}

	@Test
	fun `activateKeySilent is a no-op when JTUI not initialized`() {
		jtuiInitialized = false

		coordinator.activateKeySilent(2)

		assertThat(buttonPressedOnUiThreadCalls).isEmpty()
	}

	@Test
	fun `activateKeySilent forwards to buttonPressedOnUiThread when initialized`() {
		jtuiInitialized = true

		coordinator.activateKeySilent(5)

		assertThat(buttonPressedOnUiThreadCalls).containsExactly(5)
	}

	@Test
	fun `activateSelect forwards once and isReady mirrors isJtuiInitialized`() {
		coordinator.activateSelect()
		assertThat(triggerSelectActivationCount).isEqualTo(1)

		jtuiInitialized = true
		assertThat(coordinator.isReady()).isTrue()
		jtuiInitialized = false
		assertThat(coordinator.isReady()).isFalse()
	}

	// endregion

	// region Group 3 — Common bridge surface

	@Test
	fun `buttonCount returns size when ready and 0 when empty`() {
		assertThat(coordinator.buttonCount).isEqualTo(0)
		populate(8)
		assertThat(coordinator.buttonCount).isEqualTo(8)
		currentButtons = emptyList()
		assertThat(coordinator.buttonCount).isEqualTo(0)
	}

	@Test
	fun `isViewReady true when populated, false when empty`() {
		assertThat(coordinator.isViewReady).isFalse()
		populate(1)
		assertThat(coordinator.isViewReady).isTrue()
	}

	@Test
	fun `tintButton tints in range and out-of-range is a no-op`() {
		populate(3)
		captureOriginals()
		val originalBg1 = currentButtons[1].background

		coordinator.tintButton(0, Color.RED)
		coordinator.tintButton(99, Color.BLUE)

		assertThat(currentButtons[0].background).isNotSameInstanceAs(buttonOriginalBackgrounds[currentButtons[0]])
		assertThat(currentButtons[1].background).isSameInstanceAs(originalBg1)
		// Out-of-range did not crash and did not register a foreign entry.
		assertThat(buttonOriginalBackgrounds.size).isEqualTo(3)
	}

	@Test
	fun `restoreButtonBackground restores from map and missing entry leaves background unchanged`() {
		populate(2)
		val captured = currentButtons[0].background
		buttonOriginalBackgrounds[currentButtons[0]] = captured
		// Mutate then restore.
		currentButtons[0].background = ColorDrawable(Color.MAGENTA)
		coordinator.restoreButtonBackground(0)
		assertThat(currentButtons[0].background).isSameInstanceAs(captured)

		// Button 1 has no entry → call is a safe no-op (does not NPE, does not change bg).
		val bg1 = currentButtons[1].background
		coordinator.restoreButtonBackground(1)
		assertThat(currentButtons[1].background).isSameInstanceAs(bg1)
	}

	@Test
	fun `setButtonDrawable captures original on first call only and does not overwrite on subsequent calls`() {
		populate(1)
		val firstOriginal = currentButtons[0].background

		coordinator.setButtonDrawable(0, R.drawable.button_background_activation)

		// Original captured on first call.
		assertThat(buttonOriginalBackgrounds[currentButtons[0]]).isSameInstanceAs(firstOriginal)
		// Background swapped to a non-null drawable that is not the original.
		assertThat(currentButtons[0].background).isNotNull()
		assertThat(currentButtons[0].background).isNotSameInstanceAs(firstOriginal)

		// Restoring brings the original back.
		coordinator.restoreButtonBackground(0)
		assertThat(currentButtons[0].background).isSameInstanceAs(firstOriginal)

		// Calling setButtonDrawable a second time must NOT overwrite the captured original.
		coordinator.setButtonDrawable(0, R.drawable.button_background_feedback)
		assertThat(buttonOriginalBackgrounds[currentButtons[0]]).isSameInstanceAs(firstOriginal)
	}

	// endregion

	// region Group 4 — Button foreground management

	@Test
	fun `setButtonForeground by resId assigns a non-null foreground drawable`() {
		populate(1)

		coordinator.setButtonForeground(0, R.drawable.button_background_activation)

		assertThat(currentButtons[0].foreground).isNotNull()
	}

	@Test
	fun `setButtonForeground by drawable assigns the exact instance`() {
		populate(1)
		val drawable: Drawable = ColorDrawable(Color.MAGENTA)

		coordinator.setButtonForeground(0, drawable)

		assertThat(currentButtons[0].foreground).isSameInstanceAs(drawable)
	}

	@Test
	fun `getButtonForeground returns current foreground and null when buttons list empty`() {
		assertThat(coordinator.getButtonForeground(0)).isNull()

		populate(1)
		val drawable: Drawable = ColorDrawable(Color.BLUE)
		currentButtons[0].foreground = drawable

		assertThat(coordinator.getButtonForeground(0)).isSameInstanceAs(drawable)
	}

	@Test
	fun `clearButtonForeground nulls one and clearAllForegrounds nulls all`() {
		populate(3)
		currentButtons.forEach { it.foreground = ColorDrawable(Color.BLUE) }

		coordinator.clearButtonForeground(0)
		assertThat(currentButtons[0].foreground).isNull()
		assertThat(currentButtons[1].foreground).isNotNull()

		coordinator.clearAllForegrounds()
		currentButtons.forEach { assertThat(it.foreground).isNull() }
	}

	@Test
	fun `restoreButtonForeground assigns drawable and null arg sets foreground to null`() {
		populate(1)
		val fg: Drawable = ColorDrawable(Color.GREEN)
		currentButtons[0].foreground = ColorDrawable(Color.BLUE)

		coordinator.restoreButtonForeground(0, fg)
		assertThat(currentButtons[0].foreground).isSameInstanceAs(fg)

		coordinator.restoreButtonForeground(0, null)
		assertThat(currentButtons[0].foreground).isNull()
	}

	// endregion

	// region Group 5 — Center label

	@Test
	fun `setCenterLabelDrawable is a safe no-op when centerLabelView is null`() {
		clView = null
		clOriginalBackground = null

		coordinator.setCenterLabelDrawable(R.drawable.button_background_activation)

		assertThat(clOriginalBackground).isNull()
	}

	@Test
	fun `setCenterLabelDrawable captures original on first call only`() {
		clView = TextView(context).apply { background = ColorDrawable(Color.GRAY) }
		val capturedOriginal = clView!!.background

		coordinator.setCenterLabelDrawable(R.drawable.button_background_activation)

		assertThat(clOriginalBackground).isSameInstanceAs(capturedOriginal)
		val newBackgroundFirst = clView!!.background
		assertThat(newBackgroundFirst).isNotSameInstanceAs(capturedOriginal)

		// Second call — does not overwrite the captured original.
		coordinator.setCenterLabelDrawable(R.drawable.button_background_feedback)
		assertThat(clOriginalBackground).isSameInstanceAs(capturedOriginal)
	}

	@Test
	fun `restoreCenterLabelBackground after setCenterLabelDrawable returns to captured original`() {
		val originalBg: Drawable = ColorDrawable(Color.GRAY)
		clView = TextView(context).apply { background = originalBg }

		coordinator.setCenterLabelDrawable(R.drawable.button_background_activation)
		coordinator.restoreCenterLabelBackground()

		assertThat(clView!!.background).isSameInstanceAs(originalBg)
	}

	@Test
	fun `restoreCenterLabelBackground with null captured original sets background to null`() {
		clView = TextView(context).apply { background = ColorDrawable(Color.GRAY) }
		clOriginalBackground = null

		coordinator.restoreCenterLabelBackground()

		assertThat(clView!!.background).isNull()
	}

	@Test
	fun `setCenterLabelForeground get and restore round-trip`() {
		clView = TextView(context)

		coordinator.setCenterLabelForeground(R.drawable.button_background_activation)
		assertThat(coordinator.getCenterLabelForeground()).isNotNull()

		val newFg: Drawable = ColorDrawable(Color.MAGENTA)
		coordinator.restoreCenterLabelForeground(newFg)
		assertThat(coordinator.getCenterLabelForeground()).isSameInstanceAs(newFg)

		coordinator.restoreCenterLabelForeground(null)
		assertThat(coordinator.getCenterLabelForeground()).isNull()
	}

	// endregion

	// region Group 6 — Borders

	@Test
	fun `showKeyboardBorder assigns a GradientDrawable on parent ViewGroup and hide nulls it`() {
		populate(3)
		val parent = LinearLayout(context).apply { currentButtons.forEach { addView(it) } }
		buttonsJT = currentButtons

		coordinator.showKeyboardBorder(true)
		assertThat(parent.foreground).isInstanceOf(GradientDrawable::class.java)

		coordinator.showKeyboardBorder(false)
		assertThat(parent.foreground).isNull()
	}

	@Test
	fun `showKeyboardBorder is a no-op when buttonsJT is empty`() {
		buttonsJT = emptyList()

		coordinator.showKeyboardBorder(true)
		// No crash, no state to assert on.
	}

	@Test
	fun `showKeyboardBorder is a no-op when first button has no ViewGroup parent`() {
		populate(1)
		buttonsJT = currentButtons // first button is parentless (Robolectric default)
		assertThat(currentButtons[0].parent).isNull()

		coordinator.showKeyboardBorder(true)
		// No crash; safe-cast `as? ViewGroup` returned null.
	}

	@Test
	fun `selection list border show toggles foreground and hide nulls and null view is a no-op for both`() {
		// Null-view branch first.
		slView = null
		coordinator.showSelectionListBorder()
		coordinator.hideSelectionListBorder()

		// Now with a real view.
		val view = TextView(context)
		slView = view

		coordinator.showSelectionListBorder()
		assertThat(view.foreground).isInstanceOf(GradientDrawable::class.java)

		coordinator.hideSelectionListBorder()
		assertThat(view.foreground).isNull()
	}

	// endregion

	// region Group 7 — Selection list state

	@Test
	fun `showSelectionListPaused sets text, centers gravity, and bolds typeface`() {
		val view = TextView(context)
		slView = view

		coordinator.showSelectionListPaused("hi")

		assertThat(view.text.toString()).isEqualTo("hi")
		assertThat(view.gravity).isEqualTo(Gravity.CENTER)
		assertThat(view.typeface.style and Typeface.BOLD).isEqualTo(Typeface.BOLD)
	}

	@Test
	fun `hideSelectionListPaused resets gravity, alignment, and typeface`() {
		val view = TextView(context).apply {
			gravity = Gravity.CENTER
			setTypeface(typeface, Typeface.BOLD)
		}
		slView = view

		coordinator.hideSelectionListPaused()

		assertThat(view.gravity).isEqualTo(Gravity.START or Gravity.TOP)
		assertThat(view.textAlignment).isEqualTo(View.TEXT_ALIGNMENT_TEXT_START)
		// setTypeface(null, NORMAL) — Robolectric may leave typeface null; behavior under test is "no longer bold".
		assertThat((view.typeface?.style ?: 0) and Typeface.BOLD).isEqualTo(0)
	}

	@Test
	fun `resetSelectionListStyling matches hideSelectionListPaused and null view is a no-op for both`() {
		// Null-view path: neither method should crash.
		slView = null
		coordinator.hideSelectionListPaused()
		coordinator.resetSelectionListStyling()

		// Real view: resetSelectionListStyling produces the same state as hideSelectionListPaused.
		val view = TextView(context).apply {
			gravity = Gravity.CENTER
			setTypeface(typeface, Typeface.BOLD)
		}
		slView = view

		coordinator.resetSelectionListStyling()

		assertThat(view.gravity).isEqualTo(Gravity.START or Gravity.TOP)
		assertThat(view.textAlignment).isEqualTo(View.TEXT_ALIGNMENT_TEXT_START)
		assertThat((view.typeface?.style ?: 0) and Typeface.BOLD).isEqualTo(0)
	}

	// endregion

	// region Group 8 — Layout-swap / lifecycle

	@Test
	fun `tintButton is gated by isButtonsReady across empty-populated-empty transitions`() {
		// Empty: no-op.
		coordinator.tintButton(0, Color.RED)
		assertThat(buttonOriginalBackgrounds).isEmpty()

		// Populate: tints.
		populate(3)
		captureOriginals()
		coordinator.tintButton(0, Color.RED)
		assertThat(currentButtons[0].background).isNotSameInstanceAs(buttonOriginalBackgrounds[currentButtons[0]])

		// Reset to empty: tinting is a no-op again, no exceptions.
		val cachedFirst = currentButtons[0]
		val staleEntryBefore = buttonOriginalBackgrounds[cachedFirst]
		currentButtons = emptyList()
		coordinator.tintButton(0, Color.RED)
		// Map entries from the prior populated state remain (they are pass-by-reference state).
		assertThat(buttonOriginalBackgrounds[cachedFirst]).isSameInstanceAs(staleEntryBefore)
	}

	@Test
	fun `list swap leaves stale map entries harmless and restoreAllBackgrounds operates on current list only`() {
		// First list (A, B, C): capture A's original via setButtonDrawable.
		populate(3)
		val a = currentButtons[0]
		val aOriginal = a.background

		coordinator.setButtonDrawable(0, R.drawable.button_background_activation)
		assertThat(buttonOriginalBackgrounds[a]).isSameInstanceAs(aOriginal)

		// Swap to a fresh list (X, Y, Z).
		val x = makeButton(ColorDrawable(Color.YELLOW))
		val y = makeButton(ColorDrawable(Color.MAGENTA))
		val z = makeButton(ColorDrawable(Color.CYAN))
		currentButtons = listOf(x, y, z)

		// restoreAllBackgrounds iterates X/Y/Z; none are in the map, so no swaps occur.
		val xBgBefore = x.background
		val yBgBefore = y.background
		val zBgBefore = z.background
		coordinator.restoreAllBackgrounds()
		assertThat(x.background).isSameInstanceAs(xBgBefore)
		assertThat(y.background).isSameInstanceAs(yBgBefore)
		assertThat(z.background).isSameInstanceAs(zBgBefore)
		// A's stale map entry is still present but unreachable via current list.
		assertThat(buttonOriginalBackgrounds[a]).isSameInstanceAs(aOriginal)

		// setButtonDrawable on new list captures X's original into the map.
		coordinator.setButtonDrawable(0, R.drawable.button_background_feedback)
		assertThat(buttonOriginalBackgrounds[x]).isSameInstanceAs(xBgBefore)
	}

	@Test
	fun `out-of-range index after a smaller list swap is a safe no-op`() {
		// Start with a 5-button list.
		populate(5)
		captureOriginals()

		// Swap to a smaller 3-button list.
		currentButtons = (0 until 3).map { makeButton() }

		// An index that was valid before the swap is now out of range.
		coordinator.tintButton(4, Color.RED)
		// No exception, no foreign entry recorded.
		assertThat(currentButtons.size).isEqualTo(3)
	}

	// endregion
}
