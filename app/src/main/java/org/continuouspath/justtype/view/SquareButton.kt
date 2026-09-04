package org.continuouspath.justtype.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatButton
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_SIZE_RATIO
import org.continuouspath.justtype.settings.SettingsRepository
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class SquareButton @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = androidx.appcompat.R.attr.buttonStyle,
) : AppCompatButton(context, attrs, defStyleAttr) {
	companion object {
		private val arrowChars = setOf('\u2190', '\u2191', '\u2192', '\u2193')
		private val cycleChars = setOf('\u27F3')

		// Fraction of the smaller content dimension occupied by a centered icon.
		private const val ICON_FRACTION = 0.9f

		// Lower fraction of the content box reserved for a captioned icon's caption text.
		private const val CAPTION_FRACTION = 0.30f

		// Absolute floor (sp) for auto-scaled key text, so alphabet labels stay
		// legible even at the smallest keyboard size on low-density screens.
		private const val MIN_KEY_TEXT_SP = 10f

		// Process-shared cache of decoded drawable bitmaps. Each onCreateInputView
		// inflates ~30 SquareButtons x 9 bitmaps; without this, every recreate
		// re-decodes 270 PNGs. Failed lookups cache Optional.empty() so missing
		// resources don't retry on every construction.
		private val bitmapCache = ConcurrentHashMap<String, Optional<Bitmap>>()

		private fun cachedBitmap(context: Context, resourceName: String): Bitmap? {
			val cached = bitmapCache[resourceName]
			if (cached != null) return cached.orElse(null)
			val loaded = decodeBitmap(context, resourceName)
			bitmapCache[resourceName] = Optional.ofNullable(loaded)
			return loaded
		}

		private fun decodeBitmap(context: Context, resourceName: String): Bitmap? = try {
			val resId = context.resources.getIdentifier(resourceName, "drawable", context.packageName)
			if (resId != 0) {
				context.resources.openRawResource(resId).use { stream ->
					BitmapFactory.decodeStream(stream)
				}
			} else {
				null
			}
		} catch (e: android.content.res.Resources.NotFoundException) {
			android.util.Log.w("SquareButton", "drawable not found: $resourceName", e)
			null
		} catch (e: java.io.IOException) {
			android.util.Log.w("SquareButton", "failed to decode drawable: $resourceName", e)
			null
		}
	}
	private var labelGrid: List<String>? = null // 9 items row-major
	private var centeredLabel: String? = null
	private var centeredIcon: Drawable? = null
	private var tintCenteredIcon = true
	private var iconCaption: String? = null

	// Page-group glyph for the Select key (label sentinel "PageGroup1"): two
	// columns of fuzzy word silhouettes — "the next press opens a page".
	// Drawn UNTINTED: tinting flattens the silhouettes' soft alpha edges.
	private val pageGroupDrawable: Drawable? by lazy {
		androidx.core.content.ContextCompat.getDrawable(context, org.continuouspath.justtype.R.drawable.ic_select_page_group)
	}

	// Bitmaps for list function labels (center cell hints) and selection list images.
	// Resolved through the process-shared cache; first access per name decodes once.
	private val symBitmap: Bitmap? get() = cachedBitmap(context, "symbols_list_function_label_transparent")
	private val fnsBitmap: Bitmap? get() = cachedBitmap(context, "function_function_label_transparent")
	private val navBitmap: Bitmap? get() = cachedBitmap(context, "navigation_function_label_transparent")
	private val symbols1Bitmap: Bitmap? get() = cachedBitmap(context, "symbols1_list_function_image")
	private val symbols2Bitmap: Bitmap? get() = cachedBitmap(context, "symbols2_list_function_image")
	private val symbols3Bitmap: Bitmap? get() = cachedBitmap(context, "symbols3_list_function_image")
	private val functions1Bitmap: Bitmap? get() = cachedBitmap(context, "functions1_list_function_image")
	private val functions2Bitmap: Bitmap? get() = cachedBitmap(context, "functions2_list_function_image")
	private val navigationBitmap: Bitmap? get() = cachedBitmap(context, "navigation_list_function_image")

	private var hintAllowedChars: Set<Char>? = null
	private var hintSlotChars: Map<String, String> = emptyMap()
	private var accentAllowedChars: Set<Char>? = null
	private var hintActive: Boolean = false
	private var hintIsAmbigKey: Boolean = false

	// Next-tone-mark prediction state for THIS key's tone label (cell 7):
	// null = neutral (inactive), true = tone completes a candidate (highlight),
	// false = no candidate takes this tone (gray).
	private var toneHintApplicable: Boolean? = null

	// TAV: this key's grid carries tone forms. A form is nudged left ONLY when
	// a wide slot label ("60.", "15_") occupies the cell to its right — those
	// labels justify toward the key edge and spill toward the center column.
	// Next to single letters the form stays centered (a nudge there looks off).
	private var tavToneFormNudge: Boolean = false
	private var isKeyHighlighted: Boolean = false

	/** Cached keyboard size ratio from SettingsRepository; updated in onAttachedToWindow and updateKeyboardSizeRatio(). */
	private var cachedKbRatio: Float = 0.55f
	private val dimmedColor = Color.parseColor("#FFB0A0") // pale red for invalid letters
	private val accentColor = Color.parseColor("#E53935")

	// Center-column cells (top-center 1, center 4, bottom-center 7) are display
	// zones — list-function badges, tone labels, TAV tone forms, paged-select
	// words — never layout letters, so letter-hint coloring skips them.
	private val displayZoneCells = setOf(1, 4, 7)

	/**
	 * Resting (non-highlight) background instance, captured once by the layout
	 * controller. onDraw compares the live [getBackground] against this BY
	 * IDENTITY: every highlight/flash path swaps in a NEW drawable and every
	 * restore reassigns this exact instance, so a non-identical background means
	 * a (light) highlight is showing and key text must flip to dark.
	 */
	private var restingBackground: Drawable? = null

	/** See [restingBackground]. */
	fun setRestingBackground(background: Drawable?) {
		restingBackground = background
	}

	init {
		// Improve vertical centering for multi-line text grids
		includeFontPadding = false
		gravity = Gravity.CENTER
		// Enable better text wrapping for multi-line content
		maxLines = 3
		isSingleLine = false
	}

	fun setLabelGrid(cells: List<String>) {
		if (cells.size == 9) {
			labelGrid = cells
			centeredLabel = null
			invalidate()
		}
	}

	fun setCenteredLabel(label: String?) {
		centeredLabel = label
		labelGrid = null
		invalidate()
	}

	/** Show a vector/bitmap drawable centered in the button, tinted to currentTextColor
	 *  ([tintWithTextColor] false keeps multi-color drawables as authored).
	 *  Set null to clear and revert to text rendering. */
	fun setCenteredIcon(icon: Drawable?, tintWithTextColor: Boolean = true) {
		if (centeredIcon === icon && tintCenteredIcon == tintWithTextColor && iconCaption == null) return
		centeredIcon = icon?.mutate()
		tintCenteredIcon = tintWithTextColor
		iconCaption = null
		invalidate()
	}

	/** Like [setCenteredIcon] but with a one-word caption drawn below the icon. */
	fun setCaptionedIcon(icon: Drawable?, caption: String, tintWithTextColor: Boolean = true) {
		if (centeredIcon === icon && tintCenteredIcon == tintWithTextColor && iconCaption == caption) return
		centeredIcon = icon?.mutate()
		tintCenteredIcon = tintWithTextColor
		iconCaption = caption
		invalidate()
	}

	private val highlightPaint = Paint().apply {
		color = Color.parseColor("#4D2196F3") // semi-transparent blue
		style = Paint.Style.FILL
	}

	// Scratch objects reused across onDraw passes — no per-frame allocation.
	private val bitmapSrcRect = android.graphics.Rect()
	private val bitmapDstRect = android.graphics.RectF()
	private val cellScales = FloatArray(9)
	private var lineHeights = FloatArray(4)

	fun setHighlighted(highlighted: Boolean) {
		if (isKeyHighlighted != highlighted) {
			isKeyHighlighted = highlighted
			invalidate()
		}
	}

	fun setNextLetterHints(
		enabled: Boolean,
		allowedChars: Set<Char>?,
		accentChars: Set<Char>?,
		isAmbiguousKey: Boolean,
		slotChars: Map<String, String> = emptyMap(),
		toneApplicable: Boolean? = null,
		tavToneFormNudge: Boolean = false,
	) {
		hintIsAmbigKey = isAmbiguousKey
		hintActive = enabled && isAmbiguousKey
		hintAllowedChars = allowedChars?.map { it.uppercaseChar() }?.toSet()
		accentAllowedChars = accentChars?.map { it.uppercaseChar() }?.toSet()
		hintSlotChars = slotChars
		toneHintApplicable = toneApplicable
		this.tavToneFormNudge = tavToneFormNudge
		invalidate()
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		// Measure normally to get both width and height constraints
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		val size = kotlin.math.min(measuredWidth, measuredHeight)
		setMeasuredDimension(size, size)
	}

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		super.onLayout(changed, left, top, right, bottom)
		// Ensure text is properly centered after layout
		gravity = Gravity.CENTER
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		val repo = SettingsRepository.get()
		cachedKbRatio = repo.getFloat(KEY_KEYBOARD_SIZE_RATIO, 0.55f)
	}

	/** Updates the cached keyboard size ratio (e.g. when prefs change) and redraws. */
	fun updateKeyboardSizeRatio(ratio: Float) {
		cachedKbRatio = ratio
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		// Dark text when a (light) highlight background is showing, light text at
		// rest on the dark keyboard. See [restingBackground] for the identity rule.
		val onLightBg = restingBackground != null && background !== restingBackground
		val restColor = if (onLightBg) Color.BLACK else currentTextColor
		val minTextPx = MIN_KEY_TEXT_SP * resources.displayMetrics.scaledDensity
		val grid = labelGrid
		val single = centeredLabel
		val icon = centeredIcon
		if (grid == null && single == null) {
			super.onDraw(canvas)
			val caption = iconCaption
			if (icon != null) {
				if (caption != null) drawCaptionedIcon(canvas, icon, caption, restColor) else drawCenteredIcon(canvas, icon, restColor)
			}
			if (isKeyHighlighted) {
				canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), highlightPaint)
			}
			return
		}

		val contentLeft = paddingLeft
		val contentTop = paddingTop
		val contentRight = width - paddingRight
		val contentBottom = height - paddingBottom
		val contentWidth = (contentRight - contentLeft).toFloat()
		val contentHeight = (contentBottom - contentTop).toFloat()

		if (isKeyHighlighted) {
			canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), highlightPaint)
		}

		if (single != null) {
			// Inset for SELECT/UNDO (minimal so font can be as large as possible)
			val inset = kotlin.math.min(contentWidth, contentHeight) * 0.02f
			val cl = contentLeft + inset
			val ct = contentTop + inset
			val cr = contentRight - inset
			val cb = contentBottom - inset
			val cw = cr - cl
			val ch = cb - ct

			val p: Paint = paint
			p.textAlign = Paint.Align.CENTER
			val baseTextSize = textSize

			val lines = single.split('\n')
			if (lines.size == 1) {
				p.textSize = baseTextSize
				val widthAtBase = p.measureText(single)
				val fmBase = p.fontMetrics
				val heightAtBase = fmBase.bottom - fmBase.top
				val singleWidthFactor = when {
					cachedKbRatio < 0.45f -> 1.20f
					cachedKbRatio < 0.55f -> 1.10f
					else -> 0.90f
				}
				val targetWidth = cw * singleWidthFactor
				val targetHeight = ch * 0.88f
				val scaleW = if (widthAtBase > 0f) targetWidth / widthAtBase else 1f
				val scaleH = if (heightAtBase > 0f) targetHeight / heightAtBase else 1f
				val desiredScale = kotlin.math.min(scaleW, scaleH)
				val maxScaleUp = when {
					single.length <= 2 -> 2.5f
					single.length <= 6 -> 1.6f
					else -> 1.0f
				}
				val newTextSize = (
					if (desiredScale >= 1f) {
						kotlin.math.min(baseTextSize * desiredScale, baseTextSize * maxScaleUp)
					} else {
						baseTextSize * desiredScale.coerceAtLeast(0.25f)
					}
					).coerceAtLeast(minTextPx)

				p.textSize = newTextSize
				val fm = p.fontMetrics
				val baseline = (ct + cb) / 2f - (fm.ascent + fm.descent) / 2f
				val cx = (cl + cr) / 2f
				p.color = restColor
				canvas.drawText(single, cx, baseline, p)
				p.color = currentTextColor
				// restore
				p.textSize = baseTextSize
				return
			} else {
				// Multi-line centered block
				val cx = (cl + cr) / 2f

				// If this is the main SELECT key (2 lines: "SELECT", word preview),
				// scale the last line to fit. Otherwise keep uniform font size.
				val isSelect = lines.size >= 2 &&
					lines.firstOrNull()?.equals("SELECT", ignoreCase = true) == true

				val sizes = MutableList(lines.size) { baseTextSize }
				if (isSelect) {
					// Lock BOTH the "SELECT" label size AND the gap to the
					// values computed for an 8-character reference word so that
					// only the candidate word font changes between renders.
					val refWord = "MMMMMMMM" // 8-char reference for sizing
					p.textSize = baseTextSize
					val selectWidthAtBase = p.measureText("SELECT").coerceAtLeast(1f)
					val selectHeightAtBase = (p.fontMetrics.descent - p.fontMetrics.ascent).coerceAtLeast(1f)
					val refWordWidthAtBase = p.measureText(refWord).coerceAtLeast(1f)
					val maxWidth = cw * 0.94f

					// Compute the reference word font size
					val refWordScaleW = maxWidth / refWordWidthAtBase
					val refWordSize = baseTextSize * refWordScaleW.coerceIn(0.5f, 1f)
					p.textSize = refWordSize
					val refWordLineHeight = p.fontMetrics.descent - p.fontMetrics.ascent

					// Compute the fixed gap from the reference word scale
					val refWordScale = (refWordSize / baseTextSize.coerceAtLeast(1f)).coerceIn(0f, 1f)
					val lockedGap = ch * (0.10f + 0.18f * refWordScale)

					// Size "SELECT" from the space remaining after reference word + gap
					val spaceForSelect = (ch * 0.92f) - refWordLineHeight - lockedGap
					val scaleW = (cw * 0.90f) / selectWidthAtBase
					val scaleH = spaceForSelect / selectHeightAtBase
					val selectDesiredScale = kotlin.math.min(scaleW, scaleH)
					val lockedSelectSize = baseTextSize * selectDesiredScale.coerceIn(0.25f, 1f)
					sizes[0] = lockedSelectSize
					p.textSize = lockedSelectSize
					val selectLineHeight = p.fontMetrics.descent - p.fontMetrics.ascent

					// Compute actual word size from remaining space (using locked gap)
					val maxHeightForWord = (ch * 0.92f) - selectLineHeight - lockedGap
					var scaleIndex = -1
					for (i in lines.indices.reversed()) {
						if (lines[i].isNotEmpty()) {
							scaleIndex = i
							break
						}
					}
					if (scaleIndex >= 0 && maxHeightForWord > 0f) {
						p.textSize = baseTextSize
						val widthAtBase = p.measureText(lines[scaleIndex]).coerceAtLeast(1f)
						val lineHeightBase = (p.fontMetrics.descent - p.fontMetrics.ascent).coerceAtLeast(1f)
						val scaleWWord = maxWidth / widthAtBase
						val scaleHWord = (maxHeightForWord / lineHeightBase).coerceAtLeast(0.4f)
						val desiredScale = kotlin.math.min(scaleWWord, scaleHWord)
						sizes[scaleIndex] = kotlin.math.max(baseTextSize * 0.5f, baseTextSize * desiredScale)
					}

					// Overflow check: only shrink the WORD, never SELECT or the gap
					if (scaleIndex >= 0) {
						p.textSize = sizes[scaleIndex]
						val wordLineHeight = p.fontMetrics.descent - p.fontMetrics.ascent
						val totalBlockHeight = selectLineHeight + lockedGap + wordLineHeight
						val maxBlockHeight = ch * 0.92f
						if (totalBlockHeight > maxBlockHeight) {
							val availableForWord = maxBlockHeight - selectLineHeight - lockedGap
							if (availableForWord > 0f) {
								val shrink = availableForWord / wordLineHeight
								sizes[scaleIndex] = sizes[scaleIndex] * shrink
							}
						}
					}
				}

				// For non-SELECT multi-line (function names), shrink uniformly if needed.
				// Width factor is more generous at smaller keyboard sizes to compensate
				// for underestimated content width. Height at 100% only constrains when
				// text block genuinely exceeds key height (short-word 3+ line labels).
				if (!isSelect) {
					p.textSize = baseTextSize
					var maxW = 0f
					var totalH = 0f
					for (ln in lines) {
						maxW = kotlin.math.max(maxW, p.measureText(ln))
						val fmB = p.fontMetrics
						totalH += (fmB.descent - fmB.ascent)
					}
					val widthFactor = when {
						cachedKbRatio < 0.45f -> 1.20f
						cachedKbRatio < 0.55f -> 1.10f
						else -> 0.90f
					}
					val maxWidth = cw * widthFactor
					val maxHeight = ch * 1.00f
					val scaleW = if (maxW > 0f) maxWidth / maxW else 1f
					val scaleH = if (totalH > 0f) maxHeight / totalH else 1f
					val desired = kotlin.math.min(scaleW, scaleH)
					val uniform = baseTextSize * desired.coerceIn(0.25f, 1.5f)
					for (i in sizes.indices) sizes[i] = uniform
				}

				// Boost arrow and cycle symbol lines to be larger and bold
				// Only boost arrows on 2-line cursor keys (e.g. "LINE\n↑"), not on
				// 3-line case keys (e.g. "word\n↓\nWord")
				val boostArrows = lines.size == 2
				for (i in lines.indices) {
					if (boostArrows && lines[i].length == 1 && lines[i][0] in arrowChars) {
						sizes[i] = sizes[i] * 1.8f
					} else if (lines[i].length == 1 && lines[i][0] in cycleChars) {
						sizes[i] = sizes[i] * 1.6f
					}
				}

				// Check if SELECT key has a list function image to display
				val lastLine = lines.lastOrNull() ?: ""
				val listFunctionBitmap = when (lastLine) {
					"Symbols1" -> symbols1Bitmap
					"Symbols2" -> symbols2Bitmap
					"Symbols3" -> symbols3Bitmap
					"Functions1" -> functions1Bitmap
					"Functions2" -> functions2Bitmap
					"Navigation" -> navigationBitmap
					else -> null
				}

				val isPageGroup = lastLine == "PageGroup1" // JTUI SELECT_PAGE_GROUP_SENTINEL

				if (isSelect && (listFunctionBitmap != null || isPageGroup)) {
					// Render SELECT at the top, then image below
					// Draw "SELECT" text at the top
					p.textSize = sizes[0]
					val selectHeight = p.fontMetrics.descent - p.fontMetrics.ascent
					val selectTop = ct + ch * 0.05f // Near top with small margin
					val selectBaseline = selectTop - p.fontMetrics.ascent
					p.color = restColor
					canvas.drawText(lines[0], cx, selectBaseline, p)
					p.color = currentTextColor

					// Draw list function image below SELECT, smaller to fit
					val imageTop = selectTop + selectHeight + ch * 0.05f
					val availableHeight = cb - imageTop - ch * 0.05f
					val availableWidth = cw * 0.9f

					val pageIcon = if (isPageGroup) pageGroupDrawable else null
					if (pageIcon != null) {
						// Page-group glyph, untinted (soft alpha silhouettes).
						val iw = pageIcon.intrinsicWidth.coerceAtLeast(1)
						val ih = pageIcon.intrinsicHeight.coerceAtLeast(1)
						val scale = kotlin.math.min(availableWidth / iw, availableHeight / ih) * 0.9f
						val w = (iw * scale).toInt().coerceAtLeast(1)
						val hPx = (ih * scale).toInt().coerceAtLeast(1)
						val left = (cx - w / 2f).toInt()
						val top = (imageTop + (availableHeight - hPx) / 2f).toInt()
						pageIcon.setBounds(left, top, left + w, top + hPx)
						pageIcon.draw(canvas)
					} else if (listFunctionBitmap != null) {
						// Scale image to fit (smaller than in selection list)
						val scale = kotlin.math.min(
							availableWidth / listFunctionBitmap.width,
							availableHeight / listFunctionBitmap.height,
						) * 0.6f * 1.75f // Make it smaller on Select key, then 75% larger

						val scaledWidth = listFunctionBitmap.width * scale
						val scaledHeight = listFunctionBitmap.height * scale

						val imageLeft = cx - scaledWidth / 2f
						val imageRight = imageLeft + scaledWidth
						val imageBottom = imageTop + scaledHeight

						bitmapSrcRect.set(0, 0, listFunctionBitmap.width, listFunctionBitmap.height)
						bitmapDstRect.set(imageLeft, imageTop, imageRight, imageBottom)
						canvas.drawBitmap(listFunctionBitmap, bitmapSrcRect, bitmapDstRect, null)
					}
				} else {
					// Normal multi-line rendering (including SELECT + word)
					val hasSpecialSymbol = lines.any { it.length == 1 && ((boostArrows && it[0] in arrowChars) || it[0] in cycleChars) }
					val arrowGap = if (hasSpecialSymbol) ch * 0.06f else 0f
					val selectWordGap = if (isSelect && lines.size >= 2) {
						// Fixed gap computed from 8-char reference word, matching the sizing phase
						p.textSize = baseTextSize
						val refWWidth = p.measureText("MMMMMMMM").coerceAtLeast(1f)
						val refWScale = ((cw * 0.94f) / refWWidth).coerceIn(0.5f, 1f)
						ch * (0.10f + 0.18f * refWScale)
					} else {
						0f
					}

					var blockHeight = 0f
					if (lineHeights.size < lines.size) lineHeights = FloatArray(lines.size)
					for (i in lines.indices) {
						p.textSize = sizes[i]
						val lh = p.fontMetrics.descent - p.fontMetrics.ascent
						lineHeights[i] = lh
						blockHeight += lh
					}
					blockHeight += arrowGap
					blockHeight += selectWordGap

					p.textSize = sizes.first()
					val fmFirst = p.fontMetrics
					val verticalNudge = if (hasSpecialSymbol) ch * 0.15f else 0f
					var baseline = (ct + cb) / 2f - blockHeight / 2f - fmFirst.ascent + verticalNudge

					p.color = restColor
					for (i in lines.indices) {
						p.textSize = sizes[i]
						val isSymbolLine = lines[i].length == 1 && (lines[i][0] in arrowChars || lines[i][0] in cycleChars)
						if (isSymbolLine) p.isFakeBoldText = true
						canvas.drawText(lines[i], cx, baseline, p)
						if (isSymbolLine) p.isFakeBoldText = false
						baseline += lineHeights[i]
						if (isSelect && i == 0) baseline += selectWordGap
						if (hasSpecialSymbol && i < lines.lastIndex) baseline += arrowGap
					}
					p.color = currentTextColor
				}
				// restore
				p.textSize = baseTextSize
				return
			}
		}

		// Column widths: 1:2:1 proportion for grids
		val g = grid ?: return
		val unit = contentWidth / 4f
		val colX = floatArrayOf(
			contentLeft + 0f * unit,
			contentLeft + 1f * unit,
			contentLeft + 3f * unit,
		)
		val colW = floatArrayOf(unit, 2f * unit, unit)

		// Three row centers
		val rowY = floatArrayOf(
			contentTop + contentHeight * 1f / 6f,
			contentTop + contentHeight * 3f / 6f,
			contentTop + contentHeight * 5f / 6f,
		)

		val p: Paint = paint
		p.textAlign = Paint.Align.CENTER
		val baseTextSize = textSize
		val baseTextColor = currentTextColor
		p.color = restColor

		val rowCellHeight = contentHeight / 3f
		val isSmall = cachedKbRatio < 0.52f
		val isTiny = cachedKbRatio < 0.48f
		// A lone center-cell word (paged word selection) draws on its own path: nearly the
		// whole key face, letter-label-comparable size, hyphenated wrap when too long.
		val onlyCenterCell = g[4].isNotEmpty() && g.withIndex().all { (i, s) -> i == 4 || s.isEmpty() }
		if (onlyCenterCell) {
			drawLoneWord(
				canvas,
				g[4].trim(),
				contentLeft.toFloat(),
				contentTop.toFloat(),
				contentRight.toFloat(),
				contentBottom.toFloat(),
				baseTextSize,
				baseTextColor,
			)
			return
		}
		// Compute per-cell scales and determine if all content cells are single characters
		cellScales.fill(1f)
		var allSingleChar = true
		var globalScale = Float.MAX_VALUE
		for (r in 0 until 3) {
			for (c in 0 until 3) {
				val idx = r * 3 + c
				val cellText = g[idx]
				if (cellText.isEmpty()) continue
				val isHint = (r == 0 && c == 1) && (cellText == "SYM" || cellText == "FNS" || cellText == "NAV")
				if (isHint) continue
				if (cellText.length > 1) allSingleChar = false
				p.textSize = baseTextSize
				val isMultiChar = cellText.length > 1
				val targetW = if (isMultiChar && c != 1) colW[c] * 1.8f else colW[c] * 0.92f
				val targetH = rowCellHeight * 0.92f
				// Stacked slot cells ("#/-\n@+"): measure the widest row, height covers both.
				val cellLines = cellText.split('\n')
				val widthAtBase = cellLines.maxOf { p.measureText(it) }.coerceAtLeast(1f)
				val fmBase = p.fontMetrics
				val heightAtBase = ((fmBase.descent - fmBase.ascent) * cellLines.size).coerceAtLeast(1f)
				val scaleW = if (widthAtBase > 0f) targetW / widthAtBase else 1f
				val scaleH = if (heightAtBase > 0f) targetH / heightAtBase else 1f
				val desiredScale = kotlin.math.min(scaleW, scaleH)
				cellScales[idx] = desiredScale.coerceIn(0.25f, 3f)
				globalScale = kotlin.math.min(globalScale, desiredScale)
			}
		}
		if (globalScale == Float.MAX_VALUE) globalScale = 1f
		val effectiveGlobalScale = globalScale.coerceIn(0.25f, 3f)
		// Half-width of a single ambiguous character at its drawn size: slot labels
		// ("15_", "60.", stacked "#/-@+") justify to the letters' column edge — left
		// column labels start at the letters' left edge and extend toward the empty
		// center, right column mirrored — instead of centering over the column.
		val refSingleScale = run {
			var s = Float.NaN
			for (i in 0 until 9) {
				if (g[i].length == 1) {
					s = if (allSingleChar) effectiveGlobalScale else cellScales[i]
					break
				}
			}
			if (s.isNaN()) 1f else s
		}
		val refHalfWidth = run {
			p.textSize = baseTextSize * refSingleScale
			p.measureText("M") / 2f
		}
		// Use uniform scale when all cells are single characters (e.g. ambiguous letter keys)
		// Use per-cell scale when cells have mixed-length content (e.g. numeric punctuation)
		for (r in 0 until 3) {
			for (c in 0 until 3) {
				val idx = r * 3 + c
				val cellText = g[idx]
				if (cellText.isEmpty()) continue
				val isHint = (r == 0 && c == 1) && (cellText == "SYM" || cellText == "FNS" || cellText == "NAV")
				if (isHint) {
					val bitmap = when (cellText) {
						"SYM" -> symBitmap
						"FNS" -> fnsBitmap
						"NAV" -> navBitmap
						else -> null
					}
					if (bitmap != null) {
						val targetWidth = colW[c] * 0.8f
						val targetHeight = rowCellHeight * 0.8f
						val baseScale = kotlin.math.min(targetWidth / bitmap.width, targetHeight / bitmap.height)
						// 1.5x in the top cell: large enough to read, clear of the corner
						// letters and of the bottom-center dynamic (tone) cell.
						val scale = baseScale * 1.5f
						val scaledWidth = bitmap.width * scale
						val scaledHeight = bitmap.height * scale
						val centerX = (contentLeft + contentRight) / 2f
						val centerY = contentTop + rowCellHeight * 0.75f
						val bitmapLeft = centerX - scaledWidth / 2f
						val bitmapTop = centerY - scaledHeight / 2f
						val bitmapRight = bitmapLeft + scaledWidth
						val bitmapBottom = bitmapTop + scaledHeight
						bitmapSrcRect.set(0, 0, bitmap.width, bitmap.height)
						bitmapDstRect.set(bitmapLeft, bitmapTop, bitmapRight, bitmapBottom)
						canvas.drawBitmap(bitmap, bitmapSrcRect, bitmapDstRect, null)
					} else {
						val hintScale = if (isTiny) {
							0.70f
						} else if (isSmall) {
							0.85f
						} else {
							0.95f
						}
						p.textSize = baseTextSize * hintScale
						p.isFakeBoldText = true
						val fm = p.fontMetrics
						val baseline = rowY[r] - (fm.ascent + fm.descent) / 2f
						val cx = colX[c] + colW[c] / 2f
						canvas.drawText(cellText, cx, baseline, p)
					}
				} else {
					p.isFakeBoldText = false
					val cellScale = if (allSingleChar) effectiveGlobalScale else cellScales[idx]
					// Floor only the uniform alphabet-key size; dense mixed grids keep
					// the relative scale to avoid clipping.
					val cellTextSize = baseTextSize * cellScale
					p.textSize = if (allSingleChar) cellTextSize.coerceAtLeast(minTextPx) else cellTextSize
					val fm = p.fontMetrics
					val baseline = rowY[r] - (fm.ascent + fm.descent) / 2f
					var cx = colX[c] + colW[c] / 2f
					// TAV tone form with a wide slot label to its right ("60.", "15_"):
					// nudge left, clear of the label's spill toward the center column.
					// Forms next to single letters stay centered (see tavToneFormNudge).
					val wideRightNeighbor = (g.getOrNull(idx + 1)?.length ?: 0) > 1
					if (tavToneFormNudge && (idx == 4 || idx == 1) && wideRightNeighbor) {
						cx -= colW[c] * 0.25f
					}
					val prevColor = p.color
					val prevBold = p.isFakeBoldText
					// Cell 7 is the tone-mark label, not a letter: it follows next-tone-mark
					// prediction (applicable = highlight, inapplicable = gray, inactive =
					// neutral) instead of letter hints. Multi-char slot cells ("@_", "#/-")
					// gray unless ANY of their chars is a valid next.
					if (idx == 7 && hintActive && hintIsAmbigKey && cellText.isNotEmpty()) {
						toneHintApplicable?.let { applicable ->
							if (applicable) {
								p.color = restColor
								p.isFakeBoldText = true
							} else {
								p.color = dimmedColor
							}
						}
					}
					val shouldHint = hintActive && hintIsAmbigKey && cellText.isNotEmpty() && idx !in displayZoneCells
					if (shouldHint) {
						// A slot cell's label may be elided ("15_" for 12345_): match hint
						// chars against the slot's FULL character set, not the label glyphs.
						val matchText = hintSlotChars[cellText] ?: cellText
						val anyAccent = matchText.any { accentAllowedChars?.contains(it.uppercaseChar()) == true }
						val anyValid = matchText.any { hintAllowedChars?.contains(it.uppercaseChar()) == true }
						when {
							anyAccent -> {
								p.color = accentColor
								p.isFakeBoldText = true
							}
							anyValid -> {
								p.color = restColor
								p.isFakeBoldText = true
							}
							else -> p.color = dimmedColor
						}
					}
					// Multi-char slot labels justify to the letters' column edge (see
					// refHalfWidth above); single characters stay centered.
					fun drawCellLine(line: String, y: Float) {
						when {
							line.length > 1 && c == 0 -> {
								p.textAlign = Paint.Align.LEFT
								canvas.drawText(line, cx - refHalfWidth, y, p)
								p.textAlign = Paint.Align.CENTER
							}
							line.length > 1 && c == 2 -> {
								p.textAlign = Paint.Align.RIGHT
								canvas.drawText(line, cx + refHalfWidth, y, p)
								p.textAlign = Paint.Align.CENTER
							}
							else -> canvas.drawText(line, cx, y, p)
						}
					}
					val drawLines = cellText.split('\n')
					if (drawLines.size > 1) {
						val fmL = p.fontMetrics
						val lineH = (fmL.descent - fmL.ascent) * 0.95f
						val y0 = baseline - lineH * (drawLines.size - 1) / 2f
						drawLines.forEachIndexed { li, line -> drawCellLine(line, y0 + li * lineH) }
					} else {
						drawCellLine(cellText, baseline)
					}
					p.color = prevColor
					p.isFakeBoldText = prevBold
				}
			}
		}

		// restore
		p.textSize = baseTextSize
		p.isFakeBoldText = false
		p.color = baseTextColor
	}

	// Paged word selection: one word owns the key face. Margins are minimal so the
	// word reads at letter-label size; a word that would fall below ~0.8x the base
	// size breaks into two hyphenated lines at a syllable-ish boundary instead.
	private fun drawLoneWord(
		canvas: Canvas,
		word: String,
		left: Float,
		top: Float,
		right: Float,
		bottom: Float,
		baseTextSize: Float,
		baseTextColor: Int,
	) {
		if (word.isEmpty()) return // blank slot on the last page
		val p = paint
		val maxW = (right - left) * 0.96f
		val cx = (left + right) / 2f
		val cy = (top + bottom) / 2f
		p.textSize = baseTextSize
		p.isFakeBoldText = false
		val fm = p.fontMetrics
		val lineH = (fm.descent - fm.ascent).coerceAtLeast(1f)
		// Consistency over fill: words render at the base letter-label size whenever they
		// fit (never upscaled); only genuinely long words shrink or wrap.
		val oneLineScale = kotlin.math.min(
			maxW / p.measureText(word).coerceAtLeast(1f),
			(bottom - top) * 0.5f / lineH,
		)
		if (oneLineScale >= 0.8f || word.length < 4) {
			p.textSize = baseTextSize * oneLineScale.coerceIn(0.25f, 1f)
			val fm2 = p.fontMetrics
			canvas.drawText(word, cx, cy - (fm2.ascent + fm2.descent) / 2f, p)
		} else {
			val split = wordBreakIndex(word)
			val line1 = word.substring(0, split) + "-"
			val line2 = word.substring(split)
			val widest = kotlin.math.max(p.measureText(line1), p.measureText(line2)).coerceAtLeast(1f)
			val scale = kotlin.math.min(maxW / widest, (bottom - top) * 0.40f / lineH).coerceIn(0.25f, 1f)
			p.textSize = baseTextSize * scale
			val fm2 = p.fontMetrics
			val lh = fm2.descent - fm2.ascent
			val mid = -(fm2.ascent + fm2.descent) / 2f
			canvas.drawText(line1, cx, cy - lh / 2f + mid, p)
			canvas.drawText(line2, cx, cy + lh / 2f + mid, p)
		}
		p.textSize = baseTextSize
		p.color = baseTextColor
	}

	// Prefer a vowel-to-consonant boundary near the middle (syllable-ish); else midpoint.
	private fun wordBreakIndex(word: String): Int {
		val vowels = "aeiouyăâêôơưàáảãạèéẻẽẹìíỉĩịòóỏõọùúủũụýỳỷỹỵ"
		val mid = word.length / 2
		for (d in 0..3) {
			for (i in intArrayOf(mid - d, mid + d)) {
				if (i in 3..word.length - 3 &&
					word[i - 1].lowercaseChar() in vowels &&
					word[i].lowercaseChar() !in vowels
				) {
					return i
				}
			}
		}
		return mid.coerceIn(1, word.length - 1)
	}

	/** Icon scaled into the upper content box, [caption] auto-fitted into the lower [CAPTION_FRACTION]. */
	private fun drawCaptionedIcon(canvas: Canvas, icon: Drawable, caption: String, tint: Int) {
		val contentLeft = paddingLeft
		val contentTop = paddingTop
		val contentRight = width - paddingRight
		val contentBottom = height - paddingBottom
		val cw = (contentRight - contentLeft).coerceAtLeast(0)
		val ch = (contentBottom - contentTop).coerceAtLeast(0)
		if (cw == 0 || ch == 0) return

		val iconBoxH = ch * (1f - CAPTION_FRACTION)
		val iw = icon.intrinsicWidth.takeIf { it > 0 } ?: cw
		val ih = icon.intrinsicHeight.takeIf { it > 0 } ?: ch
		val targetBox = kotlin.math.min(cw.toFloat(), iconBoxH) * ICON_FRACTION
		val scale = kotlin.math.min(targetBox / iw, targetBox / ih)
		val w = (iw * scale).toInt().coerceAtLeast(1)
		val h = (ih * scale).toInt().coerceAtLeast(1)
		val left = contentLeft + (cw - w) / 2
		val top = contentTop + ((iconBoxH - h) / 2f).toInt().coerceAtLeast(0)
		if (tintCenteredIcon) icon.setTint(tint)
		icon.setBounds(left, top, left + w, top + h)
		icon.draw(canvas)

		val p: Paint = paint
		val baseTextSize = textSize
		val prevAlign = p.textAlign
		val prevColor = p.color
		p.textAlign = Paint.Align.CENTER
		p.textSize = baseTextSize
		val captionTop = contentTop + iconBoxH
		val captionH = contentBottom - captionTop
		val widthAtBase = p.measureText(caption).coerceAtLeast(1f)
		val heightAtBase = (p.fontMetrics.descent - p.fontMetrics.ascent).coerceAtLeast(1f)
		val minTextPx = MIN_KEY_TEXT_SP * resources.displayMetrics.scaledDensity
		val fit = kotlin.math.min(cw * 0.92f / widthAtBase, captionH * 0.85f / heightAtBase)
		p.textSize = (baseTextSize * fit.coerceIn(0.25f, 1.2f)).coerceAtLeast(minTextPx)
		p.isFakeBoldText = true
		p.color = tint
		val fm = p.fontMetrics
		canvas.drawText(caption, (contentLeft + contentRight) / 2f, captionTop + captionH / 2f - (fm.ascent + fm.descent) / 2f, p)
		p.isFakeBoldText = false
		p.color = prevColor
		p.textSize = baseTextSize
		p.textAlign = prevAlign
	}

	private fun drawCenteredIcon(canvas: Canvas, icon: Drawable, tint: Int) {
		val contentLeft = paddingLeft
		val contentTop = paddingTop
		val contentRight = width - paddingRight
		val contentBottom = height - paddingBottom
		val cw = (contentRight - contentLeft).coerceAtLeast(0)
		val ch = (contentBottom - contentTop).coerceAtLeast(0)
		if (cw == 0 || ch == 0) return
		val iw = icon.intrinsicWidth.takeIf { it > 0 } ?: cw
		val ih = icon.intrinsicHeight.takeIf { it > 0 } ?: ch
		val targetBox = kotlin.math.min(cw, ch) * ICON_FRACTION
		val scale = kotlin.math.min(targetBox / iw, targetBox / ih)
		val w = (iw * scale).toInt().coerceAtLeast(1)
		val h = (ih * scale).toInt().coerceAtLeast(1)
		val left = contentLeft + (cw - w) / 2
		val top = contentTop + (ch - h) / 2
		if (tintCenteredIcon) icon.setTint(tint)
		icon.setBounds(left, top, left + w, top + h)
		icon.draw(canvas)
	}
}
