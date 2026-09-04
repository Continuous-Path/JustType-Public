package org.continuouspath.justtype.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.settings.SettingsRepository
import kotlin.math.max
import kotlin.math.min

/**
 * Custom view to display ambiguous key history as mini graphical representations
 * of the keys themselves, showing only uppercase letters in a 3x3 grid.
 * Keys run start-to-end in a horizontal bar, or top-to-bottom as a side column
 * ([setVertical]); content beyond the container scrolls along that axis.
 * Supports shrink-to-fit mode where key size is reduced to fit the available extent.
 */
class KeyHistoryView
@JvmOverloads
constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
	private var keyLabelGrids: List<List<String>> = emptyList()

	// Word being formed: char i highlights its cell on history key i.
	private var highlightWord: String? = null
	private var maxKeyHeightDp: Float = 96f // Maximum/configured height (twice the original 48dp)
	private var effectiveKeyHeightDp: Float = 96f // Actual height used (may be reduced by shrink-to-fit)

	// Shrink-to-fit settings
	private var shrinkToFitEnabled: Boolean = false
	private var availableExtentPx: Int = 0 // Container extent along the scroll axis, for shrink-to-fit

	/** True when keys stack top-to-bottom (landscape side column) instead of left-to-right. */
	var isVertical: Boolean = false
		private set

	// Accent border on the newest key, anchoring the bar's direction/order.
	private var markLatest: Boolean = true

	// Paint objects
	private val keyBorderPaint =
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.BLACK
			style = Paint.Style.STROKE
		}

	private val textPaint =
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.BLACK
			textAlign = Paint.Align.CENTER
		}

	private val keyFillPaint =
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			style = Paint.Style.FILL
		}

	// Selection-list light green: keys render on the same white tile in both UI modes,
	// so the light variant keeps black text readable everywhere.
	private val highlightPaint =
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFF90EE90.toInt()
			style = Paint.Style.FILL
		}

	// Newest-key marker: blue so it can't be confused with the green word-char highlight.
	// Stroke width tracks key size in updateDimensions so tiny shrink-to-fit keys
	// aren't dominated by the border.
	private val markerPaint =
		Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = 0xFF1E88E5.toInt()
			style = Paint.Style.STROKE
		}

	// Scratch rects reused across onDraw passes — no per-key allocation.
	private val keyRect = RectF()
	private val highlightRect = RectF()
	private val frameRect = RectF()

	// Cell padding (fraction of cell size)
	private val cellPaddingFraction = 0.08f

	// Geometry shared with the real keyboard keys so the bar matches at any size.
	private val keyCornerRadiusFraction = KeyTileDrawable.CORNER_RADIUS_FRACTION
	private val borderStrokeFraction = KeyTileDrawable.BORDER_STROKE_FRACTION

	// Newest-key marker replaces the black border on that key — same geometry, so
	// the corners align by construction — slightly thicker for emphasis.
	private val markerStrokeFraction = 0.035f

	// Inset of the 3x3 glyph grid from each key edge, so letters don't touch the border.
	private val gridInsetFraction = 0.05f

	// Helper to check if a character is non-full-height punctuation
	// These are punctuation marks that are smaller than full-height characters
	private fun isNonFullHeightPunctuation(char: Char): Boolean = char in listOf('\'', '-', '.', ',', ';', ':', '`', '"')

	// Helper to check if a string contains any non-full-height punctuation
	private fun hasNonFullHeightPunctuation(text: String): Boolean = text.any { isNonFullHeightPunctuation(it) }

	// History keys only need to CONFIRM which key was pressed, not show every slot char:
	// stacked slot labels ("#/-\n@+") elide to their first row, capped at 3 glyphs ("#/-").
	private fun historyCellLabel(label: String): String = label.substringBefore('\n').take(3)

	// Cell on history key [keyIndex] holding the selected word's char for that key,
	// or -1. Matches the raw (unelided) label so stacked slot cells still hit.
	@androidx.annotation.VisibleForTesting
	internal fun highlightCellIndex(grid: List<String>, keyIndex: Int): Int {
		val word = highlightWord ?: return -1
		if (keyIndex >= word.length) return -1
		val ch = word[keyIndex]
		val upper = if (ch.isLetter()) ch.uppercaseChar() else ch
		for (idx in grid.indices) {
			if (grid[idx].indexOf(upper) >= 0) return idx
		}
		return -1
	}

	// Dimensions (will be calculated based on effectiveKeyHeightDp)
	private var keyWidth: Float = 0f
	private var keyHeight: Float = 0f

	// dp-scaled so spacing holds up across screen densities (was raw px).
	private val keySpacing = 4f * context.resources.displayMetrics.density // Space between keys
	private val barPaddingPx = 3f * context.resources.displayMetrics.density // Padding at start/end of bar

	init {
		updateDimensions()
	}

	private fun updateDimensions() {
		// Convert dp to pixels
		val density = context.resources.displayMetrics.density
		keyHeight = effectiveKeyHeightDp * density
		// Keep keys square
		keyWidth = keyHeight
		keyBorderPaint.strokeWidth = (keyHeight * borderStrokeFraction).coerceAtLeast(1f * density)
		markerPaint.strokeWidth = (keyHeight * markerStrokeFraction).coerceAtLeast(1f * density)
		// Font size will be calculated based on the most crowded key
		updateFontSize()
	}

	private fun updateFontSize() {
		if (keyLabelGrids.isEmpty()) {
			// Default font size if no keys (increased by 50%)
			val density = context.resources.displayMetrics.density
			val cellSize = keyHeight / 3f
			textPaint.textSize = (cellSize * 0.4f * 1.5f).coerceAtMost(36f * density)
			return
		}

		// Find the longest SINGLE-CHAR label across all keys. Multi-char slot cells
		// ("15_", stacked "#/-@+") are elided and width-shrunk per cell at draw time —
		// letting them drive the global fit would shrink every letter on the bar.
		var longestLabel = ""
		for (grid in keyLabelGrids) {
			for (label in grid) {
				val cell = historyCellLabel(label)
				if (cell.length == 1 && cell.length > longestLabel.length) {
					longestLabel = cell
				}
			}
		}

		if (longestLabel.isEmpty()) {
			// No labels, use default (increased by 50%)
			val density = context.resources.displayMetrics.density
			val cellSize = keyHeight / 3f
			textPaint.textSize = (cellSize * 0.4f * 1.5f).coerceAtMost(36f * density)
			return
		}

		// Calculate optimal font size based on cell dimensions and longest label
		val gridInset = keyHeight * gridInsetFraction
		val cellWidth = (keyWidth - 2 * gridInset) / 3f
		val cellHeight = (keyHeight - 2 * gridInset) / 3f

		// Binary search for optimal font size that fits the longest label
		val density = context.resources.displayMetrics.density
		var minSize = 8f * density // Minimum readable size
		var maxSize = minOf(cellWidth, cellHeight) * 0.95f // Maximum (95% of smaller cell dimension)
		var bestSize = minSize

		// Calculate cell padding
		val cellPadding = minOf(cellWidth, cellHeight) * cellPaddingFraction

		// Binary search to find the largest font size that fits
		while (maxSize - minSize > 0.5f) {
			val testSize = (minSize + maxSize) / 2f
			textPaint.textSize = testSize

			// Measure text bounds for the longest label
			val bounds = Rect()
			textPaint.getTextBounds(longestLabel, 0, longestLabel.length, bounds)
			val textWidth = bounds.width().toFloat()
			val textHeight = (-textPaint.ascent() + textPaint.descent()) // Full text height

			// Check if text fits in cell (with padding)
			if (textWidth <= cellWidth - (cellPadding * 2) && textHeight <= cellHeight - (cellPadding * 2)) {
				bestSize = testSize
				minSize = testSize
			} else {
				maxSize = testSize
			}
		}

		// Apply the calculated font size, increase by 50% for better readability
		// Cap at 36sp (24sp * 1.5) for very large keys
		textPaint.textSize = (bestSize * 1.5f).coerceAtMost(36f * density)
	}

	fun setKeyHistory(keyGrids: List<List<String>>, highlightWord: String? = null) {
		keyLabelGrids = keyGrids
		this.highlightWord = highlightWord
		recalculateShrinkToFit()
		// updateDimensions() — NOT just updateFontSize() — so the new
		// effectiveKeyHeightDp from recalculateShrinkToFit() is propagated to
		// keyHeight (the pixel value onMeasure reads). updateDimensions calls
		// updateFontSize internally. Previously this only called updateFontSize,
		// so the very first setKeyHistory after install would compute a smaller
		// effectiveKeyHeightDp but render at the unshrunk keyHeight — the user
		// had to toggle shrink-to-fit off/on to force updateDimensions via
		// setShrinkToFitEnabled.
		updateDimensions()
		requestLayout()
		invalidate()
	}

	fun setMarkLatest(enabled: Boolean) {
		if (markLatest == enabled) return
		markLatest = enabled
		invalidate()
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		markLatest = SettingsRepository.get()
			.getBoolean(Constants.KEY_KEY_HISTORY_MARK_LATEST, true)
	}

	fun setVertical(vertical: Boolean) {
		if (isVertical == vertical) return
		isVertical = vertical
		recalculateShrinkToFit()
		updateDimensions()
		requestLayout()
		invalidate()
	}

	fun setKeyHistoryHeight(heightDp: Float) {
		maxKeyHeightDp = heightDp.coerceAtLeast(12f)
		recalculateShrinkToFit()
		updateDimensions()
		requestLayout()
		invalidate()
	}

	/**
	 * Enable or disable shrink-to-fit mode.
	 * When enabled, the key height will be reduced if needed to fit all keys within the available width.
	 *
	 * Always recalculates/redraws — no equality guard. The guard previously prevented the first
	 * pref-driven apply from re-running the recalc/layout pipeline once availableWidthPx and
	 * keyLabelGrids had been populated, which manifested as "default ON not taking effect until
	 * the user toggles off/on" at first install.
	 */
	fun setShrinkToFitEnabled(enabled: Boolean) {
		shrinkToFitEnabled = enabled
		recalculateShrinkToFit()
		updateDimensions()
		requestLayout()
		invalidate()
	}

	/**
	 * Set the available extent along the scroll axis for shrink-to-fit calculations:
	 * the scroll container's content width when horizontal, its content height when vertical.
	 */
	fun setAvailableExtent(extentPx: Int) {
		if (availableExtentPx != extentPx) {
			availableExtentPx = extentPx
			recalculateShrinkToFit()
			updateDimensions()
			requestLayout()
			invalidate()
		}
	}

	/**
	 * Returns the natural width needed to display all keys at the maximum configured height.
	 * Useful for determining if scrolling would be needed.
	 */
	fun getNaturalWidthPx(): Int {
		val density = context.resources.displayMetrics.density
		val maxKeyHeightPx = maxKeyHeightDp * density
		val keyCount = max(1, keyLabelGrids.size)
		return ((maxKeyHeightPx + keySpacing) * keyCount + keySpacing + barPaddingPx * 2).toInt()
	}

	/**
	 * Recalculate the effective key height based on shrink-to-fit settings.
	 */
	private fun recalculateShrinkToFit() {
		if (!shrinkToFitEnabled || availableExtentPx <= 0 || keyLabelGrids.isEmpty()) {
			effectiveKeyHeightDp = maxKeyHeightDp
			return
		}

		val density = context.resources.displayMetrics.density
		val keyCount = keyLabelGrids.size

		// Calculate the maximum key size that would fit all keys in the available extent
		// Formula: availableExtent = barPadding*2 + keyCount * (keySize + keySpacing) + keySpacing
		// Solving for keySize: keySize = (availableExtent - barPadding*2 - keySpacing) / keyCount - keySpacing
		val availableForKeys = availableExtentPx - barPaddingPx * 2 - keySpacing
		val maxKeySizePx = (availableForKeys / keyCount) - keySpacing
		val maxKeySizeDp = maxKeySizePx / density

		// Use the smaller of the configured max height and the calculated fit height
		// But don't go below a minimum readable size (36dp)
		effectiveKeyHeightDp = min(maxKeyHeightDp, maxKeySizeDp).coerceIn(36f, maxKeyHeightDp)
	}

	override fun onMeasure(
		widthMeasureSpec: Int,
		heightMeasureSpec: Int,
	) {
		// Reserve space even when empty so the history bar stays visible when enabled
		val keyCount = max(1, keyLabelGrids.size)
		// Along the scroll axis: all keys + spacing; across it: exactly one key.
		val alongAxis = ((keyWidth + keySpacing) * keyCount + keySpacing + barPaddingPx * 2).toInt()
		val desiredWidth = if (isVertical) keyWidth.toInt() else alongAxis
		val desiredHeight = if (isVertical) alongAxis else keyHeight.toInt()

		val width = resolveSize(desiredWidth, widthMeasureSpec)
		val height = resolveSize(desiredHeight, heightMeasureSpec)

		setMeasuredDimension(width, height)
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)

		if (keyLabelGrids.isEmpty()) return

		// Keys advance along the scroll axis; the cross axis starts flush at 0.
		var offset = barPaddingPx + keySpacing

		for (i in keyLabelGrids.indices) {
			val grid = keyLabelGrids[i]
			val highlightIdx = highlightCellIndex(grid, i)
			val keyLeft = if (isVertical) 0f else offset
			val keyTop = if (isVertical) offset else 0f

			keyRect.set(keyLeft, keyTop, keyLeft + keyWidth, keyTop + keyHeight)
			val radius = keyHeight * keyCornerRadiusFraction
			canvas.drawRoundRect(keyRect, radius, radius, keyFillPaint)

			// Draw 3x3 grid of letters and punctuation (inset from the key edges)
			val gridInset = keyHeight * gridInsetFraction
			val cellWidth = (keyWidth - 2 * gridInset) / 3f
			val cellHeight = (keyHeight - 2 * gridInset) / 3f

			for (row in 0..2) {
				for (col in 0..2) {
					val index = row * 3 + col
					if (index < grid.size) {
						val label = historyCellLabel(grid[index])
						if (label.isNotEmpty()) {
							val cellX = keyLeft + gridInset + col * cellWidth + cellWidth / 2
							val cellY = keyTop + gridInset + row * cellHeight + cellHeight / 2

							// This cell's character forms the selected word — mark it.
							if (index == highlightIdx) {
								val cellLeft = keyLeft + gridInset + col * cellWidth
								val cellTop = keyTop + gridInset + row * cellHeight
								highlightRect.set(cellLeft, cellTop, cellLeft + cellWidth, cellTop + cellHeight)
								val cellRadius = cellWidth * 0.25f
								canvas.drawRoundRect(highlightRect, cellRadius, cellRadius, highlightPaint)
							}

							val originalSize = textPaint.textSize
							// Lone non-full-height punctuation renders 50% larger; the boost is
							// wrong for multi-glyph slot labels ("15_", "#/-").
							if (label.length == 1 && hasNonFullHeightPunctuation(label)) {
								textPaint.textSize = originalSize * 1.5f
							}
							// Half-width of a single character at the letters' size: slot labels
							// justify to the letters' column edge and extend toward the (empty)
							// center column, so they keep near-letter size instead of cramming
							// into one cell width.
							val singleHalf = textPaint.measureText("M") / 2f
							val maxWidth = cellWidth * 1.4f
							val measured = textPaint.measureText(label)
							if (measured > maxWidth) {
								textPaint.textSize = textPaint.textSize * maxWidth / measured
							}

							// Adjust Y position based on current font metrics
							val adjustedY = cellY - (textPaint.ascent() + textPaint.descent()) / 2
							when {
								label.length > 1 && col == 0 -> {
									textPaint.textAlign = Paint.Align.LEFT
									canvas.drawText(label, cellX - singleHalf, adjustedY, textPaint)
									textPaint.textAlign = Paint.Align.CENTER
								}
								label.length > 1 && col == 2 -> {
									textPaint.textAlign = Paint.Align.RIGHT
									canvas.drawText(label, cellX + singleHalf, adjustedY, textPaint)
									textPaint.textAlign = Paint.Align.CENTER
								}
								else -> canvas.drawText(label, cellX, adjustedY, textPaint)
							}

							// Restore original font size
							textPaint.textSize = originalSize
						}
					}
				}
			}

			// Border draws last so it always sits above cell highlights; the newest key
			// gets the blue marker as its border instead — same geometry, no seams.
			val framePaint = if (markLatest && i == keyLabelGrids.lastIndex) markerPaint else keyBorderPaint
			val inset = framePaint.strokeWidth / 2f
			frameRect.set(keyRect)
			frameRect.inset(inset, inset)
			canvas.drawRoundRect(frameRect, radius - inset, radius - inset, framePaint)

			offset += keyHeight + keySpacing
		}
	}
}
