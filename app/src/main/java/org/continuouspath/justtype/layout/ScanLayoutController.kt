package org.continuouspath.justtype.layout

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.DrawableCompat
import org.continuouspath.justtype.R
import org.continuouspath.justtype.view.KeyHistoryView

/**
 * Controller for the Scan keyboard layout.
 * Manages buttons arranged in rows (large: 2 rows of 4, small: 1 row of 8),
 * plus a center preview and dynamic column selection list.
 */
class ScanLayoutController(
	private val root: View,
	private val context: Context,
) : BaseLayoutController() {
	override lateinit var rootView: View
		private set

	override lateinit var buttons: List<Button>
		private set

	override lateinit var centerLabel: TextView
		private set

	override var selectionListView: View? = null
		private set

	override lateinit var keyHistoryView: KeyHistoryView
		private set

	override lateinit var keyHistoryScrollView: View
		private set

	override var keyGridView: View? = null
		private set

	// Scan-specific views
	lateinit var topRow: ConstraintLayout
		private set

	lateinit var keysContainer: LinearLayout
		private set

	lateinit var largeRowTop: LinearLayout
		private set

	lateinit var largeRowBottom: LinearLayout
		private set

	lateinit var smallRow: LinearLayout
		private set

	lateinit var buttonPool: LinearLayout
		private set

	lateinit var selectionListContainer: LinearLayout
		private set

	// Landscape-only wrapper (keys + center label left, selection list right).
	// Null when the portrait variant inflated; sizing branches on it.
	private var leftColumn: LinearLayout? = null

	// Total height (px) the IME may occupy (set by the IME per orientation);
	// 0 falls back to 85% of screen height.
	var heightBudgetPx: Int = 0

	// Dynamic column TextViews for selection list
	private val columnViews = mutableListOf<TextView>()

	// Selection-list text size (px); pref-driven via the IME, defaults to the dimen.
	var selectionTextSizePx: Float =
		context.resources.getDimensionPixelSize(R.dimen.selection_text_size).toFloat()
		set(value) {
			if (field == value) return
			field = value
			columnViews.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, value) }
		}

	// Sizing state
	private var topRowHeightTarget: Int = -1

	// Layout size preference (true = large/2 rows, false = small/1 row)
	var isLargeLayout: Boolean = true
		set(value) {
			field = value
			configureButtonRows()
		}

	// Layout optimized preference (affects button order)
	var isOptimizedLayout: Boolean = false

	// Scan highlighting colors
	var highlightColor: Int = Color.parseColor("#FFD600")
	var nextHighlightColor: Int = Color.parseColor("#FFFBD6")

	// Track scan highlight state
	private var scanHighlightedIndex: Int? = null
	private var scanNextHighlightIndex: Int? = null

	override fun initialize() {
		// Find container views
		rootView = root.findViewById(R.id.scanLayoutContainer)
		keyHistoryScrollView = root.findViewById(R.id.keyHistoryScrollViewScan)
		keyHistoryView = root.findViewById(R.id.keyHistoryViewScan)

		// Find scan-specific views
		topRow = root.findViewById(R.id.scanTopRow)
		keysContainer = root.findViewById(R.id.scanKeysContainer)
		keyGridView = keysContainer
		largeRowTop = root.findViewById(R.id.scanLargeRowTop)
		largeRowBottom = root.findViewById(R.id.scanLargeRowBottom)
		smallRow = root.findViewById(R.id.scanSmallRow)
		buttonPool = root.findViewById(R.id.scanButtonPool)

		// Find center label and selection container
		centerLabel = root.findViewById(R.id.centerLabelScan)
		centerLabel.setTextColor(Color.WHITE)
		centerLabel.gravity = Gravity.CENTER

		selectionListContainer = root.findViewById(R.id.selectionListScanContainer)
		leftColumn = root.findViewById(R.id.scanLeftColumn)

		// Find and setup buttons
		buttons =
			listOf(
				root.findViewById(R.id.btn0Scan),
				root.findViewById(R.id.btn1Scan),
				root.findViewById(R.id.btn2Scan),
				root.findViewById(R.id.btn3Scan),
				root.findViewById(R.id.btn4Scan),
				root.findViewById(R.id.btn5Scan),
				root.findViewById(R.id.btn6Scan),
				root.findViewById(R.id.btn7Scan),
			)

		applyButtonStyling()
		storeOriginalBackgrounds()

		// Scan keys draw their letter grids in onDraw with no text — give screen
		// readers at least a positional handle (1-based, scan order).
		buttons.forEachIndexed { i, btn ->
			btn.contentDescription = context.getString(R.string.content_desc_scan_key, i + 1)
		}

		// Add layout listener for key history width changes
		keyHistoryScrollView.addOnLayoutChangeListener { sv, left, _, right, _, oldLeft, _, oldRight, _ ->
			val newWidth = right - left - sv.paddingLeft - sv.paddingRight
			val oldWidth = oldRight - oldLeft - sv.paddingLeft - sv.paddingRight
			if (newWidth != oldWidth && newWidth > 0) {
				keyHistoryView.setAvailableExtent(newWidth)
			}
		}

		// Add layout watchers for sizing
		addTopRowLayoutWatchers()
	}

	/**
	 * Configure button arrangement based on large/small layout preference.
	 */
	fun configureButtonRows() {
		// Physical arrangement = the scan sequence (large: rows of 4, small:
		// one row), so the scan reads left-to-right(-then-down) — the single
		// source is ScanState (see its companion doc).
		val order =
			if (isOptimizedLayout) {
				org.continuouspath.justtype.ScanState.SCAN_ORDER_OPTIMIZED
			} else {
				org.continuouspath.justtype.ScanState.SCAN_ORDER_ALPHA
			}
		val orderLarge = order
		val orderSmall = order

		if (isLargeLayout) {
			largeRowTop.visibility = View.VISIBLE
			largeRowBottom.visibility = View.VISIBLE
			smallRow.visibility = View.GONE

			largeRowTop.removeAllViews()
			largeRowBottom.removeAllViews()

			orderLarge.take(4).forEach { idx ->
				val btn = buttons[idx]
				(btn.parent as? ViewGroup)?.removeView(btn)
				val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
				btn.layoutParams = lp
				largeRowTop.addView(btn)
			}

			orderLarge.drop(4).forEach { idx ->
				val btn = buttons[idx]
				(btn.parent as? ViewGroup)?.removeView(btn)
				val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
				btn.layoutParams = lp
				largeRowBottom.addView(btn)
			}
		} else {
			largeRowTop.visibility = View.GONE
			largeRowBottom.visibility = View.GONE
			smallRow.visibility = View.VISIBLE

			smallRow.removeAllViews()

			orderSmall.forEach { idx ->
				val btn = buttons[idx]
				(btn.parent as? ViewGroup)?.removeView(btn)
				val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
				btn.layoutParams = lp
				smallRow.addView(btn)
			}
		}

		applyTopRowSize()
	}

	/**
	 * Apply sizing to the top row (center label and selection container).
	 */
	fun applyTopRowSize(retries: Int = 4) {
		topRow.post {
			val metrics = root.resources.displayMetrics
			val budget = if (heightBudgetPx > 0) {
				heightBudgetPx
			} else {
				val fraction = if (metrics.widthPixels > metrics.heightPixels) {
					DEFAULT_BUDGET_FRACTION_LANDSCAPE
				} else {
					DEFAULT_BUDGET_FRACTION
				}
				(metrics.heightPixels * fraction).toInt()
			}
			val historyH = keyHistoryScrollView.height
			val rowCount = if (isLargeLayout) 2 else 1
			val perRow = if (isLargeLayout) 4 else 8

			val left = leftColumn
			if (left != null) {
				// Landscape: center label stacked on key rows in a fixed-width left
				// column, selection list filling the remaining width. Label renders at
				// key size, so (rowCount + 1) squares + history share the budget.
				val screenW = metrics.widthPixels
				val fromHeight = (budget - historyH - keysContainer.paddingTop) / (rowCount + 1)
				// Width cap yields to the minimum touch target (the column just widens
				// past its usual share); the height budget always binds.
				val minTouchPx = (MIN_TOUCH_TARGET_DP * metrics.density).toInt()
				val fromWidth = (screenW * MAX_KEYS_WIDTH_FRACTION / perRow).toInt()
					.coerceAtLeast(minTouchPx)
				val keyH = minOf(fromHeight, fromWidth).coerceAtLeast(1)
				topRowHeightTarget = keyH
				centerLabel.layoutParams = centerLabel.layoutParams.apply {
					width = keyH
					height = keyH
				}
				topRow.layoutParams = topRow.layoutParams.apply { height = keyH }
				left.layoutParams = left.layoutParams.apply { width = keyH * perRow }
				applyKeyHeights(keyH)
				topRow.requestLayout()
				return@post
			}

			val availableWidth = keysContainer.width.takeIf { it > 0 } ?: topRow.width
			if (availableWidth <= 0) {
				if (retries > 0) applyTopRowSize(retries - 1)
				return@post
			}

			// Width-derived height overflows in landscape — clamp against the budget.
			val targetHeight = (availableWidth * 0.25f).toInt()
				.coerceAtMost((budget * TOP_ROW_MAX_BUDGET_FRACTION).toInt())
				.coerceAtLeast(1)
			val centerWidth = targetHeight
			val selectionWidth = (availableWidth - centerWidth).coerceAtLeast(1)
			topRowHeightTarget = targetHeight

			val centerLp = centerLabel.layoutParams
			centerLp.width = centerWidth
			centerLp.height = targetHeight
			centerLabel.layoutParams = centerLp

			val selLp = selectionListContainer.layoutParams
			selLp.width = selectionWidth
			selLp.height = targetHeight
			selectionListContainer.layoutParams = selLp

			topRow.layoutParams = topRow.layoutParams.apply { height = targetHeight }

			// Key rows share what the budget leaves after the top row and history bar.
			val naturalKey = availableWidth / perRow
			val rowsBudget = budget - targetHeight - historyH - keysContainer.paddingTop
			applyKeyHeights(minOf(naturalKey, rowsBudget / rowCount).coerceAtLeast(1))
			topRow.requestLayout()
		}
	}

	private fun applyKeyHeights(keyH: Int) {
		buttons.forEach { btn ->
			(btn.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
				if (lp.height != keyH) {
					lp.height = keyH
					btn.layoutParams = lp
				}
			}
		}
	}

	private fun addTopRowLayoutWatchers() {
		val listener =
			View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
				// Layout changed - can add logging or react to changes here
			}
		centerLabel.addOnLayoutChangeListener(listener)
		selectionListContainer.addOnLayoutChangeListener(listener)
		topRow.addOnLayoutChangeListener(listener)
	}

	/**
	 * Update the dynamic column views with the provided buffers.
	 * Creates, removes, or updates TextViews as needed.
	 */
	fun updateColumnViews(buffers: List<CharSequence>) {
		val neededCount = buffers.size.coerceAtLeast(1)

		// Remove excess TextViews
		while (columnViews.size > neededCount) {
			val view = columnViews.removeAt(columnViews.size - 1)
			selectionListContainer.removeView(view)
		}

		// Add new TextViews if needed
		while (columnViews.size < neededCount) {
			val textView = createColumnTextView()
			columnViews.add(textView)
			selectionListContainer.addView(textView)
		}

		// Update text content
		buffers.forEachIndexed { index, buffer ->
			columnViews.getOrNull(index)?.let { textView ->
				textView.text = buffer
				textView.scrollTo(0, 0)
			}
		}

		// Update selectionListView reference
		selectionListView = columnViews.firstOrNull()
	}

	private fun createColumnTextView(): TextView {
		// Use the container's context (Material3 DayNight via the IME's
		// ContextThemeWrapper inflater) so ?android:attr/textColorPrimary
		// resolves theme-aware: dark on light bg in light mode, light on
		// dark bg in dark mode. The container's background drawable
		// (rounded_rectangle_bg → ?colorBackground) flips with the theme,
		// so the text color must too — mirrors the JT layout's
		// @style/SelectionList, keeping both layouts consistent.
		val ctx = selectionListContainer.context
		val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
		val themedTextColor = try {
			ta.getColor(0, Color.WHITE)
		} finally {
			ta.recycle()
		}
		return TextView(ctx).apply {
			layoutParams =
				LinearLayout.LayoutParams(
					0,
					LinearLayout.LayoutParams.MATCH_PARENT,
					1f, // Equal weight for all columns
				)
			setTextColor(themedTextColor)
			gravity = Gravity.START or Gravity.TOP
			includeFontPadding = false
			val padding = ctx.resources.getDimensionPixelSize(R.dimen.button_padding)
			setPadding(padding, padding, padding, padding)
			setTextSize(TypedValue.COMPLEX_UNIT_PX, selectionTextSizePx)
		}
	}

	/**
	 * Get the column views (for compatibility with IME code).
	 */
	fun getColumnViews(): List<TextView> = columnViews

	/**
	 * Calculate items per column based on container height.
	 */
	fun calculateItemsPerColumn(): Int {
		val containerHeight = selectionListContainer.height
		if (containerHeight <= 0) return 0

		val textSizePx = selectionTextSizePx.toInt()
		val paddingPx = context.resources.getDimensionPixelSize(R.dimen.button_padding)
		val lineHeight = (textSizePx * 1.4f).toInt()
		val availableHeight = containerHeight - (paddingPx * 2)

		return (availableHeight / lineHeight).coerceAtLeast(1)
	}

	/**
	 * Calculate max columns based on minimum column width.
	 */
	fun calculateMaxColumns(): Int {
		val containerWidth = selectionListContainer.width
		if (containerWidth <= 0) return 1

		val minColumnWidth = selectionTextSizePx.toInt() * 8
		return (containerWidth / minColumnWidth).coerceIn(1, 4)
	}

	/**
	 * Get container dimensions.
	 */
	fun getContainerDimensions(): Pair<Int, Int>? {
		val width = selectionListContainer.width
		val height = selectionListContainer.height
		return if (width > 0 && height > 0) Pair(width, height) else null
	}

	/**
	 * Set scan highlights on buttons.
	 * @param currentIdx Index of currently highlighted button
	 * @param nextIdx Index of next button to highlight (optional)
	 */
	fun setScanHighlights(
		currentIdx: Int,
		nextIdx: Int?,
	) {
		// Restore previous highlights
		fun restore(idx: Int?) {
			idx ?: return
			buttons.getOrNull(idx)?.let { btn ->
				buttonOriginalBackgrounds[btn]?.let { btn.background = it }
				btn.foreground = null
			}
		}
		restore(scanHighlightedIndex)
		if (scanNextHighlightIndex != currentIdx) restore(scanNextHighlightIndex)

		// Apply current highlight
		buttons.getOrNull(currentIdx)?.let { btn ->
			val orig = buttonOriginalBackgrounds[btn]
			val tinted =
				(orig?.constantState?.newDrawable()?.mutate() ?: btn.background.mutate()).let { d ->
					DrawableCompat.wrap(d).also { DrawableCompat.setTint(it, highlightColor) }
				}
			btn.background = tinted
		}

		// Apply next highlight
		nextIdx?.let { idx ->
			buttons.getOrNull(idx)?.let { btn ->
				val orig = buttonOriginalBackgrounds[btn]
				val tinted =
					(orig?.constantState?.newDrawable()?.mutate() ?: btn.background.mutate()).let { d ->
						DrawableCompat.wrap(d).also { DrawableCompat.setTint(it, nextHighlightColor) }
					}
				btn.background = tinted
			}
		}

		scanHighlightedIndex = currentIdx
		scanNextHighlightIndex = nextIdx
	}

	/**
	 * Clear scan highlights.
	 */
	fun clearScanHighlights() {
		scanHighlightedIndex?.let { idx ->
			buttons.getOrNull(idx)?.let { btn ->
				buttonOriginalBackgrounds[btn]?.let { btn.background = it }
				btn.foreground = null
			}
		}
		scanNextHighlightIndex?.let { idx ->
			buttons.getOrNull(idx)?.let { btn ->
				buttonOriginalBackgrounds[btn]?.let { btn.background = it }
				btn.foreground = null
			}
		}
		scanHighlightedIndex = null
		scanNextHighlightIndex = null
	}

	override fun cleanup() {
		super.cleanup()
		clearScanHighlights()
		columnViews.clear()
	}

	companion object {
		// Fallback IME height budgets when the host doesn't set heightBudgetPx;
		// mirror JustTypeIME's orientation headroom (0.15 portrait / 0.30 landscape).
		private const val DEFAULT_BUDGET_FRACTION = 0.85f
		private const val DEFAULT_BUDGET_FRACTION_LANDSCAPE = 0.70f

		// Accessibility floor for key touch targets.
		private const val MIN_TOUCH_TARGET_DP = 48f

		// Top-row share of the budget. Portrait stays width-driven
		// (0.25 * width is well under this); tight budgets get clamped.
		private const val TOP_ROW_MAX_BUDGET_FRACTION = 0.35f

		// Landscape: key columns never exceed this share of the screen width,
		// keeping the rest for the side selection list.
		private const val MAX_KEYS_WIDTH_FRACTION = 0.6f
	}
}
