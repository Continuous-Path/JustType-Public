package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUI
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [UiUpdateCallbacksImpl] guard logic.
 *
 * The JTUI-touching members intentionally accept either Constructed or Ready
 * state via [getJtui]: UI snapshot updates can fire during JTUI construction,
 * and gating them on Ready would drop legitimate state reads. These tests pin
 * the null-tolerance half of that contract; plain forwarding is not re-tested.
 */
class UiUpdateCallbacksImplTest {

	private val jtui: JTUI = mock()
	private val deps = FakeDeps()
	private lateinit var subject: UiUpdateCallbacksImpl

	@Before
	fun setUp() {
		subject = UiUpdateCallbacksImpl(deps)
	}

	@Test
	fun `getAutoCapReason returns NONE when jtui is null`() {
		deps.fakeJtui = null
		assertThat(subject.getAutoCapReason()).isEqualTo(AutoCapReason.NONE)
	}

	@Test
	fun `getShiftState returns false when jtui is null`() {
		deps.fakeJtui = null
		assertThat(subject.getShiftState()).isFalse()
	}

	@Test
	fun `getAmbiguousSequenceLength returns 0 when jtui is null`() {
		deps.fakeJtui = null
		assertThat(subject.getAmbiguousSequenceLength()).isEqualTo(0)
	}

	@Test
	fun `resetJTUI no-ops without crashing when jtui is null`() {
		deps.fakeJtui = null
		subject.resetJTUI(true, true, AutoCapReason.MANUAL)
	}

	@Test
	fun `reads and writes pass through when jtui is present`() {
		deps.fakeJtui = jtui
		whenever(jtui.getShiftState()).thenReturn(true)
		assertThat(subject.getShiftState()).isTrue()
		subject.resetJTUI(shiftState = true, callUpdate = false, autoCapReason = AutoCapReason.MANUAL)
		verify(jtui).resetJTUI(true, false, autoCapReason = AutoCapReason.MANUAL)
	}

	private inner class FakeDeps : UiUpdateCallbacksImplDeps {
		var fakeJtui: JTUI? = null

		override fun getJtui(): JTUI? = fakeJtui
		override fun updateScanColumnViews(buffers: List<CharSequence>) = Unit
		override fun updateJtColumnViews(buffers: List<CharSequence>) = Unit
		override fun updateSelectionListDimensions() = Unit
		override fun applyScanTopRowSize() = Unit
		override fun updateItemsPerColumn() = Unit
		override fun updateShiftFromCursor(suppressUpdateUI: Boolean) = Unit
		override fun getCursorOffset(): Int = 0
		override fun setIgnoreCursorRange(start: Int, end: Int) = Unit
		override fun applyEditorUpdate(preview: String) = Unit
		override fun commitImmediateText(text: String) = Unit
		override fun getInputConnection(): android.view.inputmethod.InputConnection? = null
		override fun debugLog(message: String) = Unit
		override fun debugLog(category: DebugCategory, message: String) = Unit
		override fun getHeadTrackingCenterOverride(): String? = null
	}
}
