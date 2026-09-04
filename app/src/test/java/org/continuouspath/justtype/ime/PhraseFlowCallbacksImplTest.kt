package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.data.PhraseEntry
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUI
import org.continuouspath.justtype.logic.LayoutMode
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [PhraseFlowCallbacksImpl] guard logic: the tolerant getJtui()
 * contract — writes silently no-op and reads return sensible defaults when
 * JTUI isn't yet assigned. Plain forwarding is not re-tested here.
 */
class PhraseFlowCallbacksImplTest {

	private val jtui: JTUI = mock()
	private val deps = FakeDeps()
	private lateinit var subject: PhraseFlowCallbacksImpl

	@Before
	fun setUp() {
		subject = PhraseFlowCallbacksImpl(deps)
	}

	@Test
	fun `JTUI writes no-op without crashing when jtui is null`() {
		deps.fakeJtui = null
		subject.setPhraseFlowMode(true)
		subject.startAbbreviationEntry(useAlpha = true, inPhraseFlow = false)
		subject.setCapsLock(true)
		subject.setPhraseAbbrevModeActive(true)
		subject.resetJTUI(true, true, false, false, AutoCapReason.MANUAL)
		subject.setCurrentPageToStartingPage()
		subject.addPhraseEntry(samplePhraseEntry())
	}

	@Test
	fun `reads and writes pass through when jtui is present`() {
		deps.fakeJtui = jtui
		whenever(jtui.getShiftState()).thenReturn(true)
		assertThat(subject.getShiftState()).isTrue()
		// Positional-arg order pin for the widest write.
		subject.resetJTUI(
			capitalize = true,
			callUpdateUi = false,
			isManualShift = true,
			resetToStartPage = true,
			autoCapReason = AutoCapReason.MANUAL,
		)
		verify(jtui).resetJTUI(true, false, true, true, AutoCapReason.MANUAL)
	}

	@Test
	fun `getShiftState returns false when jtui is null`() {
		deps.fakeJtui = null
		assertThat(subject.getShiftState()).isFalse()
	}

	@Test
	fun `getAutoCapReason returns NONE when jtui is null`() {
		deps.fakeJtui = null
		assertThat(subject.getAutoCapReason()).isEqualTo(AutoCapReason.NONE)
	}

	@Test
	fun `getLayoutMode returns Alphabetical default when jtui is null`() {
		deps.fakeJtui = null
		assertThat(subject.getLayoutMode()).isEqualTo(LayoutMode.Alphabetical)
	}

	private fun samplePhraseEntry() = PhraseEntry(
		phraseUUID = "uuid-1",
		abbreviation = "abbr",
		phrase = "expanded phrase",
		createdAt = 0L,
		updatedAt = 0L,
		classMask = 0L,
	)

	private inner class FakeDeps : PhraseFlowCallbacksImplDeps {
		var fakeJtui: JTUI? = null

		override fun getJtui(): JTUI? = fakeJtui
		override fun autoCommitSelectedPhrase(text: String) = Unit
		override fun scheduleBackup() = Unit
		override fun executeOnUiThread(block: () -> Unit) = block()
		override fun debugLog(message: String) = Unit
	}
}
