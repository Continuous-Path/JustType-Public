package org.continuouspath.justtype.ime

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.logic.JTUI
import org.continuouspath.justtype.logic.LayoutMode
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Tests for [PreferenceCoordinatorCallbacksImpl] guard logic.
 *
 * The impl's job is to gate JTUI/subsystem access on readiness state — these
 * tests cover the gates without standing up the IME service. Mocking JTUI for
 * the same reason as ImeTextCallbacksImplTest: it's the surface under test.
 */
class PreferenceCoordinatorCallbacksImplTest {

	private val jtui: JTUI = mock()
	private val deps = FakeDeps()
	private lateinit var subject: PreferenceCoordinatorCallbacksImpl

	@Before
	fun setUp() {
		subject = PreferenceCoordinatorCallbacksImpl(deps)
	}

	// ── isJtuiInitialized ─────────────────────────────────────────────────

	@Test
	fun `isJtuiInitialized is true only when imeState is Ready`() {
		deps.setState(IMEState.Loading)
		assertThat(subject.isJtuiInitialized).isFalse()

		deps.setState(IMEState.Ready(jtui))
		assertThat(subject.isJtuiInitialized).isTrue()
	}

	// ── isInSettingsMode ──────────────────────────────────────────────────

	@Test
	fun `isInSettingsMode false when jtui not available`() {
		deps.setJtuiAvailable(false)
		deps.inSettingsMode = true

		assertThat(subject.isInSettingsMode).isFalse()
	}

	@Test
	fun `isInSettingsMode delegates to deps when jtui available`() {
		deps.setJtuiAvailable(true)
		deps.inSettingsMode = true
		assertThat(subject.isInSettingsMode).isTrue()

		deps.inSettingsMode = false
		assertThat(subject.isInSettingsMode).isFalse()
	}

	// ── JTUI write guards (Loading state must no-op) ──────────────────────

	@Test
	fun `setShowNextLetterHints no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.setShowNextLetterHints(true)
		verify(jtui, never()).showNextLetterHints = any()
	}

	@Test
	fun `setShowNextLetterHints writes to jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.setShowNextLetterHints(true)
		verify(jtui).showNextLetterHints = true
	}

	@Test
	fun `setLayoutMode no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.setLayoutMode(LayoutMode.Optimized)
		verify(jtui, never()).layoutMode = any()
	}

	@Test
	fun `setLayoutMode writes to jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.setLayoutMode(LayoutMode.Optimized)
		verify(jtui).layoutMode = LayoutMode.Optimized
	}

	@Test
	fun `updateLocaleContext no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		val ctx: Context = mock()
		subject.updateLocaleContext(ctx)
		verify(jtui, never()).updateLocaleContext(any())
	}

	@Test
	fun `updateLocaleContext delegates to jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		val ctx: Context = mock()
		subject.updateLocaleContext(ctx)
		verify(jtui).updateLocaleContext(ctx)
	}

	@Test
	fun `forceUpdateUi no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.forceUpdateUi()
		verify(jtui, never()).forceUpdateUi()
	}

	@Test
	fun `forceUpdateUi delegates to jtui when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.forceUpdateUi()
		verify(jtui).forceUpdateUi()
	}

	// ── reinitJtuiInBackground (gate on imeState) ─────────────────────────

	@Test
	fun `reinitJtuiInBackground no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.reinitJtuiInBackground()
		assertThat(deps.reinitCount).isEqualTo(0)
	}

	@Test
	fun `reinitJtuiInBackground delegates when imeState is Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.reinitJtuiInBackground()
		assertThat(deps.reinitCount).isEqualTo(1)
	}

	@Test
	fun `applyTtsForActiveLanguage delegates even when imeState is Loading`() {
		// TTS is independent of JTUI readiness, so this is intentionally unguarded.
		deps.setState(IMEState.Loading)
		subject.applyTtsForActiveLanguage()
		assertThat(deps.applyTtsCount).isEqualTo(1)
	}

	// ── Lateinit-equivalent guards (delegate to deps booleans) ─────────────

	@Test
	fun `updateButtonClickListeners no-ops when buttons not ready`() {
		deps.buttonsReady = false
		subject.updateButtonClickListeners()
		assertThat(deps.updateButtonClickListenersCount).isEqualTo(0)
	}

	@Test
	fun `updateButtonClickListeners delegates when buttons ready`() {
		deps.buttonsReady = true
		subject.updateButtonClickListeners()
		assertThat(deps.updateButtonClickListenersCount).isEqualTo(1)
	}

	@Test
	fun `setActiveLayout no-ops when layout containers not ready`() {
		deps.layoutContainersReady = false
		subject.setActiveLayout(true)
		assertThat(deps.setActiveLayoutCalls).isEmpty()
	}

	@Test
	fun `setActiveLayout delegates when layout containers ready`() {
		deps.layoutContainersReady = true
		subject.setActiveLayout(true)
		assertThat(deps.setActiveLayoutCalls).containsExactly(true)
	}

	@Test
	fun `recreateInputView no-ops when layoutManager not ready`() {
		deps.layoutManagerReady = false
		subject.recreateInputView()
		assertThat(deps.recreateInputViewCount).isEqualTo(0)
	}

	@Test
	fun `recreateInputView delegates when layoutManager ready`() {
		deps.layoutManagerReady = true
		subject.recreateInputView()
		assertThat(deps.recreateInputViewCount).isEqualTo(1)
	}

	// ── Pure forwarders (smoke test) ──────────────────────────────────────

	@Test
	fun `applyPreferenceState forwards to deps`() {
		val state = samplePreferenceState()
		subject.applyPreferenceState(state)
		assertThat(deps.lastAppliedState).isEqualTo(state)
	}

	@Test
	fun `getExternalSwitchHandler forwards to deps`() {
		val handler: ExternalSwitchHandler = mock()
		deps.fakeExternalSwitchHandler = handler
		assertThat(subject.getExternalSwitchHandler()).isSameInstanceAs(handler)
	}

	@Test
	fun `getOverlayCoordinator forwards to deps`() {
		val coordinator: OverlayCoordinator = mock()
		deps.fakeOverlayCoordinator = coordinator
		assertThat(subject.getOverlayCoordinator()).isSameInstanceAs(coordinator)
	}

	@Test
	fun `executeOnUiThread forwards block to deps`() {
		var ran = false
		subject.executeOnUiThread { ran = true }
		assertThat(ran).isTrue()
	}

	// ── Test fakes ────────────────────────────────────────────────────────

	private fun samplePreferenceState() = PreferenceState(
		directionalSelectionEnabled = true,
		touchScreenSwitchEnabled = false,
		directSelectionEnabled = false,
		twoSwitchEnabled = false,
		singleSwitchEnabled = false,
		scanLayoutSizeLarge = true,
		flashKeyFeedbackEnabled = true,
		beepKeyFeedbackEnabled = false,
		touchScreenSwitchFlashEnabled = false,
		touchScreenSwitchBeepEnabled = false,
		directSelectionDebounceMs = 100,
		showButtonsPressedPref = false,
		keyHistoryShrinkToFitPref = false,
		errorBeepEnabled = false,
		correctionBeepEnabled = true,
		correctionFlashRedEnabled = true,
		enableDebugLog = true,
	)

	private inner class FakeDeps : PreferenceCoordinatorCallbacksImplDeps {
		private var _imeState: IMEState = IMEState.Loading
		private var _isJtuiAvailable: Boolean = false
		var inSettingsMode: Boolean = false
		var fakeExternalSwitchHandler: ExternalSwitchHandler? = null
		var fakeOverlayCoordinator: OverlayCoordinator? = null
		var buttonsReady: Boolean = false
		var layoutContainersReady: Boolean = false
		var layoutManagerReady: Boolean = false

		var lastAppliedState: PreferenceState? = null
		var updateButtonClickListenersCount = 0
		val setActiveLayoutCalls = mutableListOf<Boolean>()
		var recreateInputViewCount = 0
		var reinitCount = 0
		var applyTtsCount = 0
		val debugMessages = mutableListOf<String>()

		fun setState(state: IMEState) {
			_imeState = state
		}
		fun setJtuiAvailable(available: Boolean) {
			_isJtuiAvailable = available
		}

		override val imeState: IMEState get() = _imeState
		override val isJtuiAvailable: Boolean get() = _isJtuiAvailable
		override fun getJtuiOrNull(): JTUI? = (_imeState as? IMEState.Ready)?.jtui
		override fun isInSettingsMode(): Boolean = inSettingsMode

		override fun getExternalSwitchHandler(): ExternalSwitchHandler? = fakeExternalSwitchHandler
		override fun getOverlayCoordinator(): OverlayCoordinator? = fakeOverlayCoordinator

		override fun applyPreferenceState(state: PreferenceState) {
			lastAppliedState = state
		}

		override fun applyKeyHistoryVisibility() { /* no-op for tests */ }
		override fun applyKeyHistoryHeight(repo: SettingsRepository) { /* no-op for tests */ }
		override fun applyKeyHistoryShrinkToFit() { /* no-op for tests */ }

		override fun isButtonsReady(): Boolean = buttonsReady
		override fun updateButtonClickListeners() {
			updateButtonClickListenersCount++
		}

		override fun isLayoutContainersReady(): Boolean = layoutContainersReady
		override fun setActiveLayout(useScan: Boolean) {
			setActiveLayoutCalls.add(useScan)
		}
		override fun useScanLayout(): Boolean = false

		override fun isLayoutManagerReady(): Boolean = layoutManagerReady
		override fun recreateInputView() {
			recreateInputViewCount++
		}
		override fun applyKeyboardSize() { /* no-op for tests */ }

		override fun reinitJtuiInBackground() {
			reinitCount++
		}

		override fun applyTtsForActiveLanguage() {
			applyTtsCount++
		}

		override fun applyUiVoice() = Unit

		override fun debugLog(message: String) {
			debugMessages.add(message)
		}

		override fun executeOnUiThread(block: () -> Unit) {
			block()
		}
	}
}
