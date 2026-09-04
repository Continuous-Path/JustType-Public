package org.continuouspath.justtype.ime

import android.content.Context
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import org.continuouspath.justtype.Constants.KEY_SKIP_KEYS_NO_VALID
import org.continuouspath.justtype.R
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.CenterSquareState
import org.continuouspath.justtype.logic.JTUI
import org.continuouspath.justtype.logic.JTUISnapshot
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean

/**
 * Callbacks that [UiUpdateHandler] needs from the IME host.
 */
interface UiUpdateCallbacks {
	fun updateScanColumnViews(buffers: List<CharSequence>)
	fun updateJtColumnViews(buffers: List<CharSequence>)
	fun updateSelectionListDimensions()
	fun applyScanTopRowSize()
	fun updateItemsPerColumn()
	fun updateShiftFromCursor(suppressUpdateUI: Boolean)
	fun resetJTUI(shiftState: Boolean, callUpdate: Boolean, autoCapReason: AutoCapReason)
	fun getAutoCapReason(): AutoCapReason
	fun getShiftState(): Boolean
	fun getAmbiguousSequenceLength(): Int
	fun getCursorOffset(): Int
	fun setIgnoreCursorRange(start: Int, end: Int)
	fun applyEditorUpdate(preview: String)
	fun commitImmediateText(text: String)
	fun getInputConnection(): InputConnection?
	fun debugLog(message: String)
	fun debugLog(category: DebugCategory, message: String)

	/**
	 * Optional head-tracking center-label override. When non-null, the
	 * returned string is shown in the center square in place of the
	 * normal "Main" / current-word indicator from the JTUI snapshot.
	 * Used to surface exit / pause / resume state during head-tracking
	 * input. Default no-op for hosts that don't run head-tracking.
	 */
	fun getHeadTrackingCenterOverride(): String? = null
}

/**
 * Handles JTUI UI snapshot updates — extracted from the onUiUpdate lambda
 * that was inline in JustTypeIME.onCreateInputView.
 */
class UiUpdateHandler(
	private val context: Context,
	private val getKeyHistoryView: () -> org.continuouspath.justtype.view.KeyHistoryView?,
	private val getKeyHistoryScrollView: () -> View?,
	private val getSelectionListView: () -> TextView?,
	private val getCenterLabelView: () -> TextView?,
	private val getKeyGridActiveView: () -> View?,
	private val getButtons: () -> List<Button>,
	private val getSelectionListScanContainer: () -> View?,
	private val useScanLayout: () -> Boolean,
	private val phraseFlowController: PhraseFlowController,
	private val scanSubsystem: ScanSubsystem,
	private val ttsController: TtsController,
	private val imeTextController: ImeTextController,
	private val callbacks: UiUpdateCallbacks,
) {

	fun handleUiSnapshot(ui: JTUISnapshot) {
		phraseFlowController.onUiSnapshot(ui)

		// Update key history; scroll to the newest key (bar end, or column bottom in landscape)
		getKeyHistoryView()?.setKeyHistory(ui.ambigKeyLabels, ui.historyHighlightWord)
		getKeyHistoryScrollView()?.post {
			when (val sv = getKeyHistoryScrollView()) {
				is HorizontalScrollView -> sv.fullScroll(View.FOCUS_RIGHT)
				is ScrollView -> sv.fullScroll(View.FOCUS_DOWN)
			}
		}

		// Update selection list
		if (useScanLayout()) {
			callbacks.updateScanColumnViews(ui.selectionListBuffers)
		} else {
			val firstBuffer: CharSequence = ui.selectionListBuffers.firstOrNull() ?: ""
			getSelectionListView()?.let { sel ->
				// Precompute against the OUTGOING layout: setText nulls the TextView's
				// text Layout, so computing afterwards finds nothing (and must not zero
				// the scroll — that rendered a frame unscrolled with the new highlight,
				// the bar leaping to the bottom). For Select-stepping the text geometry
				// is unchanged, so this value is exact and the first frame lands final.
				val precomputedScrollY = computeSelectionScrollY(sel, firstBuffer, ui.currentSelectionIndex)
				// Pin the scroll so TextView's own bringTextIntoView (registered
				// internally on setText, scrolls to top on the next pre-draw) lands on
				// OUR target instead of flashing the list unscrolled. null unpins —
				// fresh/unselected lists may scroll to top naturally.
				(sel as? org.continuouspath.justtype.view.SelectionListView)?.pinnedScrollY = precomputedScrollY
				sel.setText(
					if (firstBuffer is android.text.Spannable) {
						android.text.SpannableString(firstBuffer)
					} else {
						firstBuffer
					},
				)
				precomputedScrollY?.let { sel.scrollTo(0, it) }
				sel.invalidate()
				sel.requestLayout()
			}
			// Landscape JT columns: overflow buffers render beside the main list.
			callbacks.updateJtColumnViews(ui.selectionListBuffers)
		}

		scanSubsystem.updateValidMask(ui)
		val topCandidate = ui.topCandidateOutput ?: ""
		val selectedCandidate = ui.selectedCandidateOutput ?: ""
		val selectedType = ui.selectedCandidateType ?: ""
		val preview = ui.selectedCandidateOutput ?: ui.topCandidateOutput

		callbacks.debugLog(
			"[onCreateInputView 1]  onUiUpdate: shift=${ui.shiftState}  preview='$preview'" +
				"   ui.topCandidateOutput='$topCandidate'  ui.selectedCandidateOutput='$selectedCandidate'" +
				"   isSpellingMode=${ui.isSpellingMode}" +
				"   imeTextController.suspendCommit=${imeTextController.suspendCommit}" +
				"  imeTextController.haveComposing=${imeTextController.haveComposing}" +
				"  imeTextController.lastComposingSent=${imeTextController.lastComposingSent}" +
				"  imeTextController.isPullInMode=${imeTextController.isPullInMode}" +
				"  imeTextController.autoSpaceDecision=${imeTextController.autoSpaceDecision}",
		)

		// Delegate speech scheduling to TtsController
		ttsController.handleSelectionSpeech(
			speakState = ui.speakState,
			selText = ui.selectedCandidateOutput ?: ui.topCandidateOutput ?: "",
			selType = ui.selectedCandidateType ?: "",
		)
		phraseFlowController.handleUiAutoCommit(
			suspendCommit = imeTextController.suspendCommit,
			selectedType = selectedType,
			selectedCandidate = selectedCandidate,
		)

		// Update selection list dimensions and apply the authoritative scroll:
		// the pre-draw hook registered from the inner post runs after the new
		// text's layout pass, when entry bounds are exact.
		if (!useScanLayout()) {
			val selIndexForScroll = ui.currentSelectionIndex
			getSelectionListView()?.post {
				val sel = getSelectionListView() ?: return@post
				val activeGrid = getKeyGridActiveView() ?: return@post
				sel.layoutParams = sel.layoutParams.apply { height = activeGrid.height }
				sel.requestLayout()
				sel.post {
					callbacks.updateSelectionListDimensions()
					androidx.core.view.OneShotPreDrawListener.add(sel) {
						scrollSelectionListToSelection(sel, selIndexForScroll)
					}
				}
			}
		} else {
			callbacks.applyScanTopRowSize()
			getSelectionListScanContainer()?.post {
				callbacks.updateSelectionListDimensions()
				callbacks.updateItemsPerColumn()
			}
		}

		updateCenterLabel(ui)

		// Next-letter hints and key labels
		val shouldForceInvalidHints = SettingsRepository.get().getBoolean(KEY_SKIP_KEYS_NO_VALID) &&
			ui.ambiguousKeyMask.any { it } &&
			ui.nextLetterHints.isEmpty()
		val effectiveHints: Set<Char> =
			if (shouldForceInvalidHints) setOf('\u0000') else (ui.nextLetterHints + ui.accentNextLetterHints)
		val effectiveHighlight = ui.highlightNextLetters || shouldForceInvalidHints
		callbacks.debugLog(
			DebugCategory.WordDb,
			"[hintRender] highlightNextLetters=${ui.highlightNextLetters} nextHints=${ui.nextLetterHints.size}" +
				" accentHints=${ui.accentNextLetterHints.size} ambigKeys=${ui.ambiguousKeyMask.count { it }}" +
				" shouldForceInvalid=$shouldForceInvalidHints effectiveHighlight=$effectiveHighlight" +
				" effectiveHints=${effectiveHints.size}",
		)

		getButtons().forEachIndexed { index, button ->
			val label = ui.keyLabels.getOrNull(index) ?: ""
			val gridLabels = ui.keyLabelGrids.getOrNull(index)
			val hasGridContent = gridLabels?.any { it.isNotEmpty() } == true
			if (button is org.continuouspath.justtype.view.SquareButton) {
				if (hasGridContent) {
					button.setLabelGrid(gridLabels!!)
					button.text = ""
				} else {
					button.setCenteredLabel(label)
					button.text = ""
				}
				val isAmbigKey = ui.ambiguousKeyMask.getOrElse(index) { false }
				if (isAmbigKey) {
					val keyChars = label.toCharArray().toSet()
					val hintsOnKey = effectiveHints.count { keyChars.contains(it.uppercaseChar()) || keyChars.contains(it.lowercaseChar()) }
					callbacks.debugLog(
						DebugCategory.WordDb,
						"[hintRender] keyIdx=$index label='$label' ambig=$isAmbigKey hintsOnKey=$hintsOnKey",
					)
				}
				// Next-tone-mark prediction: page position -> internal keyNum (1=UNDO,
				// 6=SELECT have none); null keeps the tone label neutral.
				val internalKeyNum = intArrayOf(0, -1, 1, 2, 3, 4, -1, 5).getOrElse(index) { -1 }
				val toneApplicable = ui.nextToneKeys?.let { if (internalKeyNum >= 0) internalKeyNum in it else null }
				button.setNextLetterHints(
					effectiveHighlight,
					ui.nextLetterHints,
					ui.accentNextLetterHints,
					isAmbigKey,
					ui.slotCellChars,
					toneApplicable,
					tavToneFormNudge = internalKeyNum >= 0 && internalKeyNum in ui.tavToneFormKeys,
				)
				button.setHighlighted(index in ui.highlightedKeyIndices)
			} else {
				button.text = label
			}
		}

		// Handle composing text state
		if (imeTextController.resetJTUI) {
			callbacks.debugLog("[onCreateInputView 2]  Reset JTUI state - imeTextController.resetJTUI flag is set, callUpdate = true")
			callbacks.updateShiftFromCursor(true)
			callbacks.resetJTUI(callbacks.getShiftState(), true, callbacks.getAutoCapReason())
			imeTextController.resetJTUI = false
			imeTextController.haveComposing = false
		} else if (!imeTextController.suspendCommit) {
			callbacks.debugLog(
				"[onCreateInputView 1b]  !imeTextController.suspendCommit path: preview='$preview'" +
					"  imeTextController.lastComposingSent=${imeTextController.lastComposingSent}" +
					"  imeTextController.haveComposing=${imeTextController.haveComposing}" +
					"  imeTextController.isPullInMode=${imeTextController.isPullInMode}",
			)
			val ambLenCheck = try {
				callbacks.getAmbiguousSequenceLength()
			} catch (_: Exception) {
				0
			}
			if (!ui.isSpellingMode && preview.isNullOrEmpty() && !imeTextController.lastComposingSent.isNullOrEmpty()) {
				if (ambLenCheck == 0) {
					callbacks.debugLog("[onCreateInputView 2a] Deleting composing text '${imeTextController.lastComposingSent}' - no preview available (ambLen=$ambLenCheck, imeTextController.autoSpaceInserted=${imeTextController.autoSpaceInserted})")
					val ic = callbacks.getInputConnection()
					if (ic != null) {
						val saveIgnore = imeTextController.ignoreSelectionUpdate
						try {
							imeTextController.ignoreSelectionUpdate = true
							var selStart = callbacks.getCursorOffset()
							var selEnd = selStart + 2
							selStart = if (selStart < 2) 0 else (selStart - 2)
							callbacks.setIgnoreCursorRange(selStart, selEnd)
							callbacks.debugLog("[onCreateInputView 2a1] About to delete composing text, current ambLen=$ambLenCheck")
							ic.setComposingText("", 1)
							callbacks.debugLog("[onCreateInputView 2a2] Composing text deleted")
							imeTextController.haveComposing = false
							imeTextController.lastComposingSent = null
							imeTextController.autoSpaceDecision = false
							imeTextController.autoSpaceInserted = false
							callbacks.debugLog("[onCreateInputView 2d] Composing text deleted")
						} catch (_: Exception) {
						} finally {
							imeTextController.ignoreSelectionUpdate = saveIgnore
						}
					} else {
						callbacks.debugLog("[onCreateInputView 2a-skip] Skipping compose delete (ambLen=$ambLenCheck, preview empty but sequence active)")
					}
				}
			}

			if (!ui.isSpellingMode && !preview.isNullOrEmpty() && !imeTextController.suspendCommit) {
				callbacks.debugLog("[onCreateInputView 3]  Calling applyEditorUpdate():  preview='$preview' shift=${ui.shiftState}")
				callbacks.applyEditorUpdate(preview)
			} else if (ui.baseOutput.isNotEmpty() && ui.baseOutput != imeTextController.lastCommittedBaseOutput) {
				val newPart = if (ui.baseOutput.startsWith(imeTextController.lastCommittedBaseOutput)) {
					ui.baseOutput.substring(imeTextController.lastCommittedBaseOutput.length)
				} else {
					ui.baseOutput
				}
				callbacks.debugLog("[onCreateInputView 4]  Committing baseOutput delta: '$newPart' (full='${ui.baseOutput}', was='${imeTextController.lastCommittedBaseOutput}')")
				callbacks.commitImmediateText(newPart)
				imeTextController.lastCommittedBaseOutput = ui.baseOutput
			}
		}
	}

	internal fun scrollSelectionListToSelection(selView: TextView?, selectionIndex: Int?) {
		if (selView == null) return
		val y = computeSelectionScrollY(selView, null, selectionIndex) ?: return
		(selView as? org.continuouspath.justtype.view.SelectionListView)?.pinnedScrollY = y
		selView.scrollTo(0, y)
	}

	/**
	 * Target scrollY for the selected entry, or null when it cannot be computed
	 * (no text Layout — e.g. right after setText): callers must then LEAVE the
	 * current scroll untouched; zeroing here caused a flash-unscrolled artifact.
	 *
	 * The selection is located by its highlight span (JTUI marks every selected
	 * row — list mode, paged rows, paged page-blocks — with
	 * FullWidthLineBackgroundSpan), which is format-agnostic: paged buffers
	 * carry blank separator lines and two words per row, so entry-index-to-
	 * newline mapping does NOT hold there. Plain text falls back to the
	 * newline walk by entry index. [content] lets callers measure an INCOMING
	 * buffer against the outgoing layout (exact while Select-stepping).
	 */
	internal fun computeSelectionScrollY(
		selView: TextView,
		content: CharSequence?,
		selectionIndex: Int?,
	): Int? {
		val layout = selView.layout ?: return null
		if (layout.lineCount == 0) return null
		val text = content ?: selView.text
		val spanned = text as? Spanned
		val span = spanned
			?.getSpans(0, text.length, JTUI.FullWidthLineBackgroundSpan::class.java)
			?.minByOrNull { spanned.getSpanStart(it) }
		var startOffset: Int
		val endOffset: Int
		if (span != null) {
			startOffset = spanned.getSpanStart(span)
			endOffset = spanned.getSpanEnd(span)
		} else {
			val idx = selectionIndex ?: -1
			if (idx < 0) return 0
			// Entry index -> offsets via hard newlines (single-line-entry lists).
			startOffset = 0
			var remaining = idx
			while (remaining > 0) {
				val nl = text.indexOf('\n', startOffset)
				if (nl < 0) break
				startOffset = nl + 1
				remaining--
			}
			if (remaining > 0) {
				// Selection lives beyond this view's entries (e.g. another column).
				return null
			}
			val nextNl = text.indexOf('\n', startOffset)
			endOffset = if (nextNl >= 0) nextNl else text.length
		}
		// The layout may hold the OUTGOING text (precompute path): clamp offsets.
		val layoutLen = layout.text?.length ?: return null
		startOffset = startOffset.coerceAtMost(layoutLen)
		val firstLine = layout.getLineForOffset(startOffset)
		val lastLine = layout.getLineForOffset(endOffset.coerceAtMost(layoutLen))
		var lineTop = layout.getLineTop(firstLine)
		var lineBottom = layout.getLineBottom(lastLine)
		if (text is Spannable) {
			val imageSpans = text.getSpans(startOffset, endOffset, ImageSpan::class.java)
			if (imageSpans.isNotEmpty()) {
				val baseline = layout.getLineBaseline(firstLine)
				for (span in imageSpans) {
					val h = span.drawable.bounds.height()
					lineTop = kotlin.math.min(lineTop, baseline - h)
					lineBottom = kotlin.math.max(lineBottom, baseline)
				}
			}
		}
		// Anticipatory scrolling (Cliff 2026-08-04): once the highlight would sink
		// into the lower half of the viewport while undisplayed entries remain
		// below, the list scrolls up instead — the highlight rides the midpoint
		// until the tail is fully visible, then resumes moving down to the last
		// entry. The stateless clamp implements the whole contract: early entries
		// -> 0, mid-list -> pinned at midpoint, tail -> capped at maxScroll.
		// Screen position of layout-coordinate y is paddingTop + y - scrollY, and the
		// scrollable extent includes both paddings — omitting them capped the scroll
		// short, leaving the last entry clipped below the viewport.
		val viewHeight = selView.height
		val padTop = selView.totalPaddingTop
		val maxScroll = (layout.height + padTop + selView.totalPaddingBottom - viewHeight)
			.coerceAtLeast(0)
		return (lineBottom + padTop - viewHeight / 2).coerceIn(0, maxScroll)
	}

	/**
	 * Set the center-label text with a smart shrink-to-fit pass.
	 *
	 * Android's [TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration]
	 * shrinks until the text fits in the bounds, but treats mid-word
	 * wrapping as a valid fit. For multi-line strings like
	 * "Resuming\nJustType", that can produce ugly results like:
	 *
	 *     Resumin
	 *           g
	 *     JustType
	 *
	 * — three physical lines for two logical lines, with "Resuming"
	 * broken mid-word.
	 *
	 * For text that contains explicit line breaks (`\n`), this method
	 * disables Android's auto-sizer and measures each line individually:
	 * we pick the largest size where every logical line fits on a single
	 * physical line. For single-line text (the common case — "Main", a
	 * current word, etc.), we fall back to Android's auto-sizer, which
	 * is correct for that case.
	 *
	 * If the view hasn't been measured yet (first frame of a session),
	 * we also fall back to auto-sizer; its internal OnLayoutChangeListener
	 * handles the deferred sizing correctly.
	 */
	private fun applyCenterLabelText(tv: TextView, text: String, maxSpOverride: Int? = null) {
		tv.text = text

		val displayMetrics = context.resources.displayMetrics
		val defaultSp = context.resources.getDimension(R.dimen.button_text_size) /
			displayMetrics.scaledDensity
		val maxSp = maxSpOverride ?: defaultSp.toInt().coerceAtLeast(8)
		val minSp = 6

		val widthPx = tv.width - tv.paddingLeft - tv.paddingRight
		val heightPx = tv.height - tv.paddingTop - tv.paddingBottom
		val hasExplicitBreaks = text.contains('\n')
		val needsCustomFit = hasExplicitBreaks && widthPx > 0 && heightPx > 0

		if (!needsCustomFit) {
			// Fall back to Android's auto-sizer for single-line text or
			// for the first-frame "not yet measured" case.
			if (tv is androidx.appcompat.widget.AppCompatTextView) {
				TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
					tv,
					minSp,
					maxSp,
					1,
					TypedValue.COMPLEX_UNIT_SP,
				)
			}
			return
		}

		// Custom shrink-to-fit: each \n-separated chunk must fit on its
		// own physical line. Disable Android's auto-sizer so it doesn't
		// override our chosen size on the next layout pass.
		TextViewCompat.setAutoSizeTextTypeWithDefaults(
			tv,
			TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE,
		)

		val logicalLines = text.split('\n')
		val paint = TextPaint(tv.paint)

		val chosenSp = (maxSp downTo minSp).firstOrNull { sp ->
			paint.textSize = sp * displayMetrics.scaledDensity
			val fm = paint.fontMetrics
			val lineHeight = fm.descent - fm.ascent + fm.leading
			val totalHeight = lineHeight * logicalLines.size
			val fitsHeight = totalHeight <= heightPx
			val fitsWidth = fitsHeight && logicalLines.maxOf { paint.measureText(it) } <= widthPx
			fitsWidth
		} ?: minSp

		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, chosenSp.toFloat())
	}

	/**
	 * Center label update. Head-tracking exit/pause/resume states can
	 * override the normal JTUI text via callbacks.getHeadTrackingCenterOverride()
	 * — when non-null, that string wins (and head-tracking owns the
	 * background, so live-surface styling stands down entirely).
	 */
	private fun updateCenterLabel(ui: JTUISnapshot) {
		val htOverride = callbacks.getHeadTrackingCenterOverride()
		val centerText = htOverride ?: ui.centerSpace
		val centerState = if (htOverride != null) CenterSquareState.EMPTY else ui.centerSquareState
		getCenterLabelView()?.let { tv ->
			tv.gravity = if (ui.isSpellingMode) {
				Gravity.TOP or Gravity.CENTER_HORIZONTAL
			} else {
				Gravity.CENTER
			}
			applyCenterSquareStyle(tv, centerState, headTrackingActive = htOverride != null)
			applyCenterLabelText(
				tv,
				centerText,
				maxSpOverride = if (centerState == CenterSquareState.SIGNAL) SIGNAL_MAX_SP else null,
			)
		}
	}

	/**
	 * Center-square live-surface treatment (sls.md "Center-square surface").
	 * Reasserted on every snapshot, so a stale background self-heals on the
	 * next update. Styling is redundant with content by design (bold + dark
	 * text on a light field, and the SIGNAL state's max-fit font), so no
	 * state is carried by hue alone. While head-tracking shows its override
	 * text the background is ITS surface (pause/resume drawables) — leave it.
	 */
	private fun applyCenterSquareStyle(tv: TextView, state: CenterSquareState, headTrackingActive: Boolean) {
		if (!headTrackingActive) {
			when (state) {
				CenterSquareState.ARMED ->
					tv.setBackgroundColor(ContextCompat.getColor(context, R.color.center_armed_bg))
				CenterSquareState.SIGNAL ->
					tv.setBackgroundColor(ContextCompat.getColor(context, R.color.center_signal_bg))
				else -> tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
			}
		}
		val live = state == CenterSquareState.ARMED || state == CenterSquareState.SIGNAL
		tv.setTextColor(
			ContextCompat.getColor(
				context,
				if (live) R.color.center_live_text else R.color.center_label_text_color,
			),
		)
		tv.setTypeface(null, if (live) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
	}

	private companion object {
		/** SIGNAL state autosize ceiling: "the largest font that fits the
		 *  square" — the uniform autosizer shrinks from here to fit. */
		const val SIGNAL_MAX_SP = 72
	}
}
