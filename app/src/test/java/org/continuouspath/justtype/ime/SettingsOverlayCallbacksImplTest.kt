package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SettingsOverlayCallbacksImpl].
 *
 * The interesting surface is [onSettingsOverlayHidden]: it always runs the
 * preference-load + overlay-update + key-history-apply sequence, then
 * conditionally enters a try/finally bookend around the inputView recreate.
 * The 3.13.3/3.13.4 hardening turned this into the *only* recreate path
 * left in the IME — these tests document that contract.
 */
class SettingsOverlayCallbacksImplTest {

	private val deps = FakeDeps()
	private lateinit var subject: SettingsOverlayCallbacksImpl

	@Before
	fun setUp() {
		subject = SettingsOverlayCallbacksImpl(deps)
	}

	// ── executeOnUiThread ─────────────────────────────────────────────────

	@Test
	fun `executeOnUiThread forwards block to deps`() {
		var ran = false
		subject.executeOnUiThread { ran = true }
		assertThat(ran).isTrue()
	}

	// ── onSettingsOverlayShown gate ───────────────────────────────────────

	@Test
	fun `onSettingsOverlayShown no-ops when input view not inflated`() {
		deps.inputViewInflated = false
		subject.onSettingsOverlayShown()
		assertThat(deps.lastKeyHistoryVisible).isNull()
	}

	@Test
	fun `onSettingsOverlayShown hides key history when input view inflated`() {
		deps.inputViewInflated = true
		subject.onSettingsOverlayShown()
		assertThat(deps.lastKeyHistoryVisible).isFalse()
	}

	// ── onSettingsOverlayHidden ───────────────────────────────────────────

	@Test
	fun `onSettingsOverlayHidden runs preference and overlay refresh sequence`() {
		deps.inputViewInflated = false
		subject.onSettingsOverlayHidden()

		assertThat(deps.loadAndApplyAllCount).isEqualTo(1)
		assertThat(deps.updateDirectionalSelectionCount).isEqualTo(1)
		assertThat(deps.updateTouchScreenSwitchCount).isEqualTo(1)
		assertThat(deps.applyKeyHistoryVisibilityCount).isEqualTo(1)
	}

	@Test
	fun `onSettingsOverlayHidden does not recreate input view when not inflated`() {
		deps.inputViewInflated = false
		subject.onSettingsOverlayHidden()

		assertThat(deps.recreateInputViewCount).isEqualTo(0)
		assertThat(deps.recreatingFlagSequence).isEmpty()
	}

	@Test
	fun `onSettingsOverlayHidden recreates input view when inflated`() {
		deps.inputViewInflated = true
		subject.onSettingsOverlayHidden()

		assertThat(deps.recreateInputViewCount).isEqualTo(1)
	}

	@Test
	fun `onSettingsOverlayHidden brackets recreate with isRecreatingInputView true then false`() {
		deps.inputViewInflated = true
		subject.onSettingsOverlayHidden()

		// Before recreate: set true. After recreate: set false.
		assertThat(deps.recreatingFlagSequence).containsExactly(true, false).inOrder()
	}

	@Test
	fun `onSettingsOverlayHidden resets isRecreatingInputView to false even when recreate throws`() {
		deps.inputViewInflated = true
		deps.recreateThrows = true

		runCatching { subject.onSettingsOverlayHidden() }

		assertThat(deps.recreatingFlagSequence).containsExactly(true, false).inOrder()
	}

	@Test
	fun `onSettingsOverlayHidden runs sequence in correct order`() {
		deps.inputViewInflated = true
		subject.onSettingsOverlayHidden()

		// The preference-load + overlay updates + key-history apply all
		// happen *before* the recreate. This ordering is load-bearing for
		// the post-3.13.3 contract: setActiveLayout (inside loadAndApplyAll)
		// swaps containers before the view tree recreates around them.
		assertThat(deps.callOrder).containsExactly(
			"loadAndApplyAllPreferences",
			"updateDirectionalSelection",
			"updateTouchScreenSwitch",
			"applyKeyHistoryVisibility",
			"setIsRecreatingInputView(true)",
			"recreateInputView",
			"setIsRecreatingInputView(false)",
		).inOrder()
	}

	// ── Forwarders ────────────────────────────────────────────────────────

	@Test
	fun `updateKeyLabels forwards to deps`() {
		subject.updateKeyLabels(listOf("a", "b", "c"))
		assertThat(deps.lastKeyLabels).containsExactly("a", "b", "c").inOrder()
	}

	@Test
	fun `updateCenterText forwards to deps`() {
		subject.updateCenterText("hi")
		assertThat(deps.lastCenterText).isEqualTo("hi")
	}

	@Test
	fun `debugLog forwards to deps`() {
		subject.debugLog("x")
		assertThat(deps.debugMessages).containsExactly("x")
	}

	// ── Test fakes ────────────────────────────────────────────────────────

	private inner class FakeDeps : SettingsOverlayCallbacksImplDeps {
		var inputViewInflated: Boolean = false
		var recreateThrows: Boolean = false

		var lastKeyHistoryVisible: Boolean? = null

		var loadAndApplyAllCount = 0
		var updateDirectionalSelectionCount = 0
		var updateTouchScreenSwitchCount = 0
		var applyKeyHistoryVisibilityCount = 0
		var recreateInputViewCount = 0
		val recreatingFlagSequence = mutableListOf<Boolean>()

		var lastKeyLabels: List<String>? = null
		var lastCenterText: String? = null
		val debugMessages = mutableListOf<String>()

		val callOrder = mutableListOf<String>()

		override val isInputViewInflated: Boolean get() = inputViewInflated

		override fun executeOnUiThread(block: () -> Unit) {
			block()
		}

		override fun setKeyHistoryVisible(visible: Boolean) {
			lastKeyHistoryVisible = visible
		}

		override fun loadAndApplyAllPreferences() {
			loadAndApplyAllCount++
			callOrder.add("loadAndApplyAllPreferences")
		}

		override fun updateDirectionalSelection() {
			updateDirectionalSelectionCount++
			callOrder.add("updateDirectionalSelection")
		}

		override fun updateTouchScreenSwitch() {
			updateTouchScreenSwitchCount++
			callOrder.add("updateTouchScreenSwitch")
		}

		override fun applyKeyHistoryVisibility() {
			applyKeyHistoryVisibilityCount++
			callOrder.add("applyKeyHistoryVisibility")
		}

		override fun setIsRecreatingInputView(value: Boolean) {
			recreatingFlagSequence.add(value)
			callOrder.add("setIsRecreatingInputView($value)")
		}

		override fun recreateInputView() {
			recreateInputViewCount++
			callOrder.add("recreateInputView")
			if (recreateThrows) error("recreate failed")
		}

		override fun updateSettingsKeyLabels(labels: List<String>) {
			lastKeyLabels = labels
		}

		override fun updateSettingsCenterText(text: String) {
			lastCenterText = text
		}

		override fun debugLog(message: String) {
			debugMessages.add(message)
		}
	}
}
