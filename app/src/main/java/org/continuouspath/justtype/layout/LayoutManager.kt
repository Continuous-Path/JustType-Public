package org.continuouspath.justtype.layout

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.continuouspath.justtype.Constants.INPUT_METHOD_HEAD_TRACKING
import org.continuouspath.justtype.Constants.INPUT_METHOD_SINGLE_SWITCH
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD
import org.continuouspath.justtype.Constants.KEY_LAYOUT_MODE
import org.continuouspath.justtype.Constants.KEY_SCAN_LAYOUT_SIZE
import org.continuouspath.justtype.Constants.MODE_OPT
import org.continuouspath.justtype.Constants.SCAN_LAYOUT_SIZE_LARGE
import org.continuouspath.justtype.Constants.SCAN_LAYOUT_SIZE_SMALL
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.view.KeyHistoryView

/**
 * Manages switching between keyboard layouts (JT grid and Scan).
 * Provides a unified interface for the IME service to interact with the active layout.
 */
class LayoutManager(
	private val root: View,
	private val context: Context,
	private val repo: SettingsRepository,
) {
	// Layout controllers
	val jtController: JTLayoutController = JTLayoutController(root)
	val scanController: ScanLayoutController = ScanLayoutController(root, context)

	// Active layout state
	private var _useScanLayout: Boolean = false
	val useScanLayout: Boolean get() = _useScanLayout

	private var _activeController: KeyboardLayoutController = jtController
	val activeController: KeyboardLayoutController get() = _activeController

	// Convenience accessors that delegate to active controller
	val buttons: List<Button> get() = _activeController.buttons
	val centerLabel: TextView get() = _activeController.centerLabel
	val selectionListView: View? get() = _activeController.selectionListView
	val keyHistoryView: KeyHistoryView get() = _activeController.keyHistoryView
	val keyHistoryScrollView: View get() = _activeController.keyHistoryScrollView
	val keyGridView: View? get() = _activeController.keyGridView
	val buttonOriginalBackgrounds: MutableMap<Button, Drawable?> get() = _activeController.buttonOriginalBackgrounds

	// Combined button backgrounds from both layouts
	val allButtonOriginalBackgrounds: MutableMap<Button, Drawable?> = mutableMapOf()

	/**
	 * Initialize both layout controllers.
	 * Must be called after the root view has been inflated.
	 */
	fun initialize() {
		jtController.initialize()
		scanController.initialize()

		// Store all button backgrounds
		allButtonOriginalBackgrounds.putAll(jtController.buttonOriginalBackgrounds)
		allButtonOriginalBackgrounds.putAll(scanController.buttonOriginalBackgrounds)

		// Read initial layout preference
		val inputMethod =
			repo.getString(
				KEY_INPUT_METHOD,
				INPUT_METHOD_HEAD_TRACKING,
			)
		_useScanLayout = inputMethod == INPUT_METHOD_SINGLE_SWITCH

		// Read scan layout size preference
		val scanLayoutSize =
			repo.getString(
				KEY_SCAN_LAYOUT_SIZE,
				SCAN_LAYOUT_SIZE_LARGE,
			)
		scanController.isLargeLayout = scanLayoutSize != SCAN_LAYOUT_SIZE_SMALL

		// Read layout mode preference for optimized button order
		val layoutMode = repo.getString(KEY_LAYOUT_MODE, MODE_OPT)
		scanController.isOptimizedLayout = layoutMode == MODE_OPT

		// Set initial active layout
		setActiveLayout(_useScanLayout)
	}

	/**
	 * Switch to the specified layout.
	 * @param useScan true for scan layout, false for JT layout
	 */
	fun setActiveLayout(useScan: Boolean) {
		_useScanLayout = useScan

		// Update visibility
		jtController.setVisible(!useScan)
		scanController.setVisible(useScan)

		// Update active controller reference
		_activeController = if (useScan) scanController else jtController

		// Configure scan layout if active
		if (useScan) {
			scanController.configureButtonRows()
			scanController.applyTopRowSize()
		}
	}

	/**
	 * Update key history visibility for both layouts based on preference.
	 * @param showKeyHistory true to show key history
	 */
	fun setKeyHistoryVisible(showKeyHistory: Boolean) {
		// Only show key history for the active layout
		jtController.setKeyHistoryVisible(!_useScanLayout && showKeyHistory)
		scanController.setKeyHistoryVisible(_useScanLayout && showKeyHistory)
	}

	/**
	 * Update shrink-to-fit for both layouts.
	 * @param enabled true to enable shrink-to-fit
	 */
	fun setKeyHistoryMarkLatest(enabled: Boolean) {
		jtController.setKeyHistoryMarkLatest(enabled)
		scanController.setKeyHistoryMarkLatest(enabled)
	}

	fun setKeyHistoryShrinkToFit(enabled: Boolean) {
		jtController.setKeyHistoryShrinkToFit(enabled)
		scanController.setKeyHistoryShrinkToFit(enabled)
	}

	/**
	 * Update scan layout size (large = 2 rows, small = 1 row).
	 * @param isLarge true for large layout (2 rows of 4 buttons)
	 */
	fun setScanLayoutSize(isLarge: Boolean) {
		scanController.isLargeLayout = isLarge
		if (_useScanLayout) {
			scanController.configureButtonRows()
		}
	}

	/**
	 * Update button order optimization.
	 * @param isOptimized true for optimized button order
	 */
	fun setOptimizedLayout(isOptimized: Boolean) {
		scanController.isOptimizedLayout = isOptimized
		if (_useScanLayout) {
			scanController.configureButtonRows()
		}
	}

	/**
	 * Update selection list content.
	 * @param buffers For scan layout, list of column buffers. For JT layout, first buffer is used.
	 */
	fun updateSelectionList(buffers: List<CharSequence>) {
		if (_useScanLayout) {
			scanController.updateColumnViews(buffers)
		} else {
			val text = buffers.firstOrNull()?.toString() ?: ""
			jtController.updateSelectionList(text)
		}
	}

	/**
	 * Get selection list dimensions for JTUI configuration.
	 */
	fun getSelectionListDimensions(): Pair<Int, Int>? = if (_useScanLayout) {
		scanController.getContainerDimensions()
	} else {
		jtController.getSelectionListDimensions()
	}

	/**
	 * Calculate items per column for scan layout.
	 */
	fun calculateItemsPerColumn(): Int = if (_useScanLayout) {
		scanController.calculateItemsPerColumn()
	} else {
		0 // JT layout uses single column
	}

	/**
	 * Calculate max columns for scan layout.
	 */
	fun calculateMaxColumns(): Int = if (_useScanLayout) {
		scanController.calculateMaxColumns()
	} else {
		1 // JT layout uses single column
	}

	/**
	 * Apply top row size for scan layout.
	 */
	fun applyScanTopRowSize() {
		if (_useScanLayout) {
			scanController.applyTopRowSize()
		}
	}

	/**
	 * Configure scan buttons based on current preferences.
	 */
	fun configureScanButtons() {
		scanController.configureButtonRows()
	}

	/**
	 * Post a runnable to be executed after the selection list container is laid out.
	 */
	fun postOnSelectionListLayout(action: () -> Unit) {
		if (_useScanLayout) {
			scanController.selectionListContainer.post(action)
		} else {
			jtController.selectionListTextView.post(action)
		}
	}

	/**
	 * Clean up both controllers.
	 */
	fun cleanup() {
		jtController.cleanup()
		scanController.cleanup()
	}
}
