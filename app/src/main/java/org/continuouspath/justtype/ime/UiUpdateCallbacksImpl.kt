package org.continuouspath.justtype.ime

import android.view.inputmethod.InputConnection
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUI

/**
 * Non-IME-host dependencies that [UiUpdateCallbacksImpl] needs.
 *
 * The four JTUI read/write methods accept either the [IMEState.Constructed]
 * or [IMEState.Ready] state via [getJtui] (i.e. anything where the JTUI
 * instance is assigned). This is intentionally looser than most callbacks —
 * UI snapshot updates can fire while JTUI is still being constructed, so
 * gating them on Ready would drop legitimate state reads.
 */
@Suppress("TooManyFunctions") // Wraps the 13-method UiUpdateCallbacks surface; narrowing is the JTUI-decoupling work tracked in 3.13.6.
interface UiUpdateCallbacksImplDeps {
	fun getJtui(): JTUI?

	fun updateScanColumnViews(buffers: List<CharSequence>)
	fun updateJtColumnViews(buffers: List<CharSequence>)
	fun updateSelectionListDimensions()
	fun applyScanTopRowSize()
	fun updateItemsPerColumn()
	fun updateShiftFromCursor(suppressUpdateUI: Boolean)

	fun getCursorOffset(): Int
	fun setIgnoreCursorRange(start: Int, end: Int)
	fun applyEditorUpdate(preview: String)
	fun commitImmediateText(text: String)

	fun getInputConnection(): InputConnection?

	fun debugLog(message: String)
	fun debugLog(category: DebugCategory, message: String)

	fun getHeadTrackingCenterOverride(): String?
}

/**
 * Named impl of [UiUpdateCallbacks], extracted from the inline anonymous
 * object in JustTypeIME.
 *
 * The impl owns the JTUI fall-through defaults (NONE / false / 0) for the
 * read methods so callers see a stable contract whether or not JTUI is
 * yet constructed. The reset method silently no-ops on Loading.
 */
class UiUpdateCallbacksImpl(
	private val deps: UiUpdateCallbacksImplDeps,
) : UiUpdateCallbacks {

	override fun updateScanColumnViews(buffers: List<CharSequence>) = deps.updateScanColumnViews(buffers)

	override fun updateJtColumnViews(buffers: List<CharSequence>) = deps.updateJtColumnViews(buffers)

	override fun updateSelectionListDimensions() = deps.updateSelectionListDimensions()
	override fun applyScanTopRowSize() = deps.applyScanTopRowSize()
	override fun updateItemsPerColumn() = deps.updateItemsPerColumn()

	override fun updateShiftFromCursor(suppressUpdateUI: Boolean) = deps.updateShiftFromCursor(suppressUpdateUI)

	override fun resetJTUI(shiftState: Boolean, callUpdate: Boolean, autoCapReason: AutoCapReason) {
		deps.getJtui()?.resetJTUI(shiftState, callUpdate, autoCapReason = autoCapReason)
	}

	override fun getAutoCapReason(): AutoCapReason = deps.getJtui()?.getAutoCapReason() ?: AutoCapReason.NONE

	override fun getShiftState(): Boolean = deps.getJtui()?.getShiftState() ?: false

	override fun getAmbiguousSequenceLength(): Int = deps.getJtui()?.getAmbiguousSequenceLength() ?: 0

	override fun getCursorOffset(): Int = deps.getCursorOffset()

	override fun setIgnoreCursorRange(start: Int, end: Int) = deps.setIgnoreCursorRange(start, end)

	override fun applyEditorUpdate(preview: String) = deps.applyEditorUpdate(preview)

	override fun commitImmediateText(text: String) = deps.commitImmediateText(text)

	override fun getInputConnection(): InputConnection? = deps.getInputConnection()

	override fun debugLog(message: String) = deps.debugLog(message)
	override fun debugLog(category: DebugCategory, message: String) = deps.debugLog(category, message)

	override fun getHeadTrackingCenterOverride(): String? = deps.getHeadTrackingCenterOverride()
}
