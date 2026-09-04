package org.continuouspath.justtype.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.logging.ExceptionReporter
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.CASE_MODE_SENTENCE
import org.continuouspath.justtype.logic.CASE_TYPE_LOWER
import org.continuouspath.justtype.logic.CASE_TYPE_TITLE
import org.continuouspath.justtype.logic.CASE_TYPE_UPPER
import org.continuouspath.justtype.logic.JTUI
import org.continuouspath.justtype.settings.getBoolean

/**
 * Owns all text-editing state and logic extracted from JustTypeIME (Phase 2 Step 7b).
 *
 * Responsibilities:
 * - InputConnection text operations (composing, commit, delete, selection)
 * - Autospace decision tree (16 interacting flags)
 * - Pull-in flow (delete-replay-insert)
 * - Cursor movement, bookmarks, case change
 * - Auto-shift from cursor context
 * - JTUI output callback implementations (onNumericOutput, onImmediateOutput, etc.)
 *
 * Threading: all methods run on `Dispatchers.Main.immediate` via the service [scope].
 * Pull-in scheduling uses coroutine [Job] instead of Handler (Cluster D migration).
 *
 * @param scope Service-scoped coroutine scope (cancelled in onDestroy)
 * @param getInputConnection Lambda returning the current InputConnection
 * @param callbacks Reverse-communication interface to JustTypeIME
 * @param ttsController TTS controller for direct speech calls
 */
class ImeTextController(
	private val scope: CoroutineScope,
	private val getInputConnection: () -> InputConnection?,
	private val getInputEditorInfo: () -> EditorInfo?,
	private val callbacks: ImeTextCallbacks,
	private val ttsController: TtsController,
) {

	// ── Composing/preview state ─────────────────────────────────────────

	internal var suspendCommit: Boolean = false
	internal var haveComposing: Boolean = false
	internal var allowComposing: Boolean = true
	internal var lastPreview: String? = ""
	internal var lastComposingSent: String? = null

	// Pull-in suppression window after the user has exited the keyboard via
	// head-tracking. The spurious onFinishInput → onStartInput cycle that
	// Android fires during HeadBoard's pop-out SAW window-stack change would
	// otherwise re-populate state.ambiguousKeySequence (and selectionList)
	// from the word at the cursor, immediately undoing the exit-time clear.
	// While SystemClock.uptimeMillis() < pullInSuppressedUntilMs, the
	// tryImmediatePullInAtCurrentCursor call is a no-op.
	private var pullInSuppressedUntilMs: Long = 0L

	// ── Autospace flags ─────────────────────────────────────────────────

	internal var autoSpaceDecision: Boolean = false
	internal var autoSpaceInserted: Boolean = false
	internal var autoSpaceInsertionDelayed: Boolean = false
	internal var spacePossible: Boolean = false
	internal var lastPreviewSuppressLeadingSpace: Boolean = false
	internal val autospaceSuppressLeadingChars: Set<Char> = setOf('\'', '\u2019', '-', '.')
	internal var autoSpaceInsertPos: Int = -1
	internal var autospaceIgnoreActive: Boolean = false
	internal var pendingTrailingSpace: Boolean = false
	internal var autospaceIgnorePrev: Boolean = false
	internal var autospaceIgnoreResetPending: Boolean = false
	internal var suppressAutospaceForField: Boolean = false

	// Suppresses autospacing for the duration of a LETTER/SYMBOL MODE session (all sub-modes:
	// Two-Key Spell, Symbols, 123 Numbers, Emoji). Independent of suppressAutospaceForField so the
	// per-field value is never clobbered. Set/cleared by JTUI via onSetAutospaceSuppressed.
	internal var suppressAutospaceMode: Boolean = false

	private val autospaceSuppressed: Boolean get() = suppressAutospaceForField || suppressAutospaceMode

	// ── Ignore cursor range (our own edits) ─────────────────────────────

	internal var cursorBeforeInsertion: Int = -1
	internal var ignoreCursorStart: Int = -1
	internal var ignoreCursorEnd: Int = -1

	// ── Cursor/selection state ──────────────────────────────────────────

	internal var ignoreSelectionUpdate: Boolean = false

	// One-shot flag: when set, the NEXT onUpdateSelection callback is
	// consumed without running processSelectionChange. Used by UnDo
	// Contexts 23 and 5, which call setComposingText("") synchronously
	// inside an ignoreSelectionUpdate try/finally — but Android fires the
	// matching onUpdateSelection asynchronously after the flag has been
	// restored, so it slips through and causes processSelectionChange's
	// branch 4aa to wipe JTUI state (deleting the autospace and the
	// remaining ambig sequence). This flag bridges that async gap.
	internal var suppressNextSelectionUpdate: Boolean = false
	internal var lastSelStart: Int = -1
	internal var lastSelEnd: Int = -1
	internal var selectionAnchor: Int = -1
	internal var pageMoveSavedCursorPos: Int = -1
	internal var pageMoveBalance: Int = 0
	internal var bookmarkA: Int = -1
	internal var bookmarkB: Int = -1
	internal var et: ExtractedText? = null
	internal var currentStartOffset: Int = -1
	internal var lastStartOffset: Int = -1
	internal var currentSelectionStart: Int = -1
	internal var lastSelectionStart: Int = -1
	internal var currentSelectionEnd: Int = -1
	internal var lastSelectionEnd: Int = -1

	// ── Pull-in state ───────────────────────────────────────────────────

	internal val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
	internal var pendingPullIn: Runnable? = null
	internal var pullInToken: Long = 0L
	internal var isPullInMode: Boolean = false
	internal var lastPullInWord: String? = null
	internal var lastPullInStart: Int = -1
	internal var lastPullInEnd: Int = -1
	internal var lastPullInTs: Long = 0L
	internal var detectedWord: String = ""
	internal var detectedStart: Int = -1
	internal var detectedEnd: Int = -1
	internal var attemptPullInOnFirstUpdate: Boolean = false

	// ── Misc text state ─────────────────────────────────────────────────

	internal var showEditOps: Boolean = false
	internal var resetJTUI: Boolean = false
	internal var lastAutoCapReason: AutoCapReason = AutoCapReason.NONE
	internal var lastCommittedBaseOutput: String = ""
	internal var selectCountSinceLastAmbig: Int = 0
	internal var lastImeEditMs: Long = 0L

	// ── Text query helpers ──────────────────────────────────────────────

	fun getCursorOffset(): Int {
		return try {
			val ic = getInputConnection() ?: return -1
			val req = android.view.inputmethod.ExtractedTextRequest()
			val et = ic.getExtractedText(req, 0)
			et?.selectionStart ?: -1
		} catch (_: Exception) {
			-1
		}
	}

	fun getCurrentSelection() {
		val ic = getInputConnection() ?: return
		try {
			et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
		} catch (e: Exception) {
			ExceptionReporter.reportSilent("ImeTextController:getCurrentSelection", e)
		}
		currentStartOffset = (et?.startOffset ?: 0).coerceAtLeast(0)
		val relSelStartEarly = minOf(et?.selectionStart ?: 0, et?.selectionEnd ?: 0)
		val relSelEndEarly = maxOf(et?.selectionStart ?: 0, et?.selectionEnd ?: 0)
		currentSelectionStart = currentStartOffset + relSelStartEarly
		currentSelectionEnd = currentStartOffset + relSelEndEarly
	}

	fun findStringBeforeCursor(
		searchString: String,
		cursorOffset: Int,
		ignoreCase: Boolean = true,
	): Pair<Int, Int>? {
		val ic = getInputConnection() ?: return null
		val textBefore = ic.getTextBeforeCursor(cursorOffset, 0)?.toString() ?: return null
		val index = if (ignoreCase) {
			textBefore.lastIndexOf(searchString, ignoreCase = true)
		} else {
			textBefore.lastIndexOf(searchString)
		}
		if (index < 0) return null
		val startAbs = index
		val endAbs = index + searchString.length
		return Pair(startAbs, endAbs)
	}

	fun setIgnoreCursorRange(start: Int, end: Int) {
		if (start > 0) {
			ignoreCursorStart = start - 1
		} else {
			ignoreCursorStart = start
		}
		if (end >= 0) {
			ignoreCursorEnd = end + 1
		} else {
			ignoreCursorEnd = end
		}
		callbacks.debugLog("[setIgnoreCursorRange()] set $ignoreCursorStart..$ignoreCursorEnd")
	}

	// ── Autospace helpers ───────────────────────────────────────────────

	fun shouldInsertTrailingSpace(charAfter: Char, nextChar: Char?): Boolean {
		if (charAfter.isWhitespace()) return false
		if (charAfter in setOf(',', '.', '!', '?', ')', ';', ':', ']', '}')) return false
		if (charAfter.isLetter() || charAfter.isDigit()) return true
		if (charAfter in setOf('/', '(', '{', '$', '[')) return true
		if (charAfter in setOf('"', '\'')) {
			return nextChar != null && !nextChar.isWhitespace()
		}
		if (charAfter in setOf('-', '&')) {
			return nextChar == null || nextChar.isWhitespace()
		}
		return false
	}

	fun shouldSuppressLeadingAutospace(text: String): Boolean {
		val first = text.firstOrNull() ?: return false
		if (first in autospaceSuppressLeadingChars) return true
		if (callbacks.selectedCandidateSuppressesLeadingSpace()) return true
		return false
	}

	fun beginAutospaceEdit() {
		if (!autospaceIgnoreActive) {
			autospaceIgnorePrev = ignoreSelectionUpdate
			ignoreSelectionUpdate = true
			autospaceIgnoreActive = true
		}
		autospaceIgnoreResetPending = true
	}

	fun maybeResetAutospaceIgnore() {
		if (!autospaceIgnoreResetPending) return
		autospaceIgnoreResetPending = false
		ignoreSelectionUpdate = autospaceIgnorePrev
		autospaceIgnoreActive = false
		setIgnoreCursorRange(-1, -1)
	}

	/**
	 * Window hidden: the editor may finalize our composition while hidden, and stale
	 * composing bookkeeping then re-inserts the word on reopen, duplicating it
	 * ("test" → "testtest" per hide/show cycle). Commit deterministically and clear;
	 * the reopen pull-in re-absorbs the word at the cursor instead.
	 */
	fun relinquishComposingOnHide() {
		getInputConnection()?.let { ic -> runCatching { ic.finishComposingText() } }
		haveComposing = false
		lastComposingSent = null
		lastPreview = ""
	}

	fun clearComposingPreviewIfNeeded(ic: InputConnection) {
		if (!haveComposing || lastComposingSent.isNullOrEmpty()) return
		beginAutospaceEdit()
		val saveIgnoreSelectionUpdate = ignoreSelectionUpdate
		try {
			ignoreSelectionUpdate = true
			ic.setComposingText("", 1)
		} catch (_: Exception) {
		} finally {
			ignoreSelectionUpdate = saveIgnoreSelectionUpdate
		}
		haveComposing = false
		lastComposingSent = null
	}

	fun removeLeadingAutospaceIfPresent(ic: InputConnection) {
		if (!autoSpaceInserted) return
		val insertPos = autoSpaceInsertPos
		if (insertPos < 0) return
		val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
		val full = et?.text?.toString() ?: return
		if (insertPos !in full.indices) return
		if (full[insertPos] != ' ') return
		val origSelStart = et.selectionStart.coerceAtLeast(0)
		val origSelEnd = et.selectionEnd.coerceAtLeast(0)
		val saveIgnoreSelectionUpdate = ignoreSelectionUpdate
		try {
			beginAutospaceEdit()
			ignoreSelectionUpdate = true
			setIgnoreCursorRange(insertPos, insertPos + 1)
			ic.setSelection(insertPos + 1, insertPos + 1)
			ic.deleteSurroundingText(1, 0)
			val newLen = (full.length - 1).coerceAtLeast(0)
			val adjStart = (if (origSelStart > insertPos) origSelStart - 1 else origSelStart).coerceIn(0, newLen)
			val adjEnd = (if (origSelEnd > insertPos) origSelEnd - 1 else origSelEnd).coerceIn(0, newLen)
			ic.setSelection(adjStart, adjEnd)
			callbacks.debugLog("[removeLeadingAutospaceIfPresent] Removed autospace at $insertPos")
		} catch (_: Exception) {
		} finally {
			ignoreSelectionUpdate = saveIgnoreSelectionUpdate
		}
		autoSpaceInserted = false
		autoSpaceInsertPos = -1
	}

	fun shouldInsertLeadingSpace(charBefore: Char?, prevChar: Char?): Boolean {
		if (charBefore == null) return false
		if (autospaceSuppressed) return false
		if (charBefore.isWhitespace()) return false
		if (charBefore in setOf('/', '(', '{', '[')) return false
		if (charBefore.isLetterOrDigit()) return true
		if (charBefore in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')) return true
		if (charBefore in setOf('-', '&')) {
			return prevChar == null || prevChar.isWhitespace()
		}
		return false
	}

	fun shouldAllowAutospace(lastChar: Char?): Boolean {
		if (lastChar == null) return false
		if (autospaceSuppressed) return false
		if (lastChar.isLetterOrDigit()) return true
		if (lastChar in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')) return true
		if (lastChar in setOf('-', '&')) return true
		return false
	}

	// ── Commit helper ───────────────────────────────────────────────────

	fun commitImmediateText(text: String) {
		val ic = getInputConnection() ?: return
		callbacks.debugLog("[commitImmediateText] Committing immediate text: '$text'")
		val saveIgnoreSelectionUpdate = ignoreSelectionUpdate
		try {
			ignoreSelectionUpdate = true
			ic.commitText(text, 1)
			lastImeEditMs = android.os.SystemClock.uptimeMillis()
		} finally {
			ignoreSelectionUpdate = saveIgnoreSelectionUpdate
		}
		val lastChar = if (text.isNotEmpty()) text.last() else null
		spacePossible = shouldAllowAutospace(lastChar)
		callbacks.debugLog("[commitImmediateText] Immediate text committed, spacePossible=$spacePossible")
	}

	// ── TextUtils wrappers ──────────────────────────────────────────────

	private fun findWordBoundaryLeft(text: String, pos: Int) = TextUtils.findWordBoundaryLeft(text, pos)
	private fun findWordBoundaryRight(text: String, pos: Int) = TextUtils.findWordBoundaryRight(text, pos)
	private fun findSentenceStart(text: String, pos: Int) = TextUtils.findSentenceStart(
		text,
		pos,
		isKnownAbbreviation = { callbacks.isKnownAbbreviation(it) },
		isKnownDomain = { callbacks.isKnownDomain(it) },
	)
	private fun findSentenceEnd(text: String, pos: Int) = TextUtils.findSentenceEnd(
		text,
		pos,
		isKnownAbbreviation = { callbacks.isKnownAbbreviation(it) },
		isKnownDomain = { callbacks.isKnownDomain(it) },
	)
	private fun findParagraphStart(text: String, pos: Int) = TextUtils.findParagraphStart(text, pos)
	private fun findNextParagraphStart(text: String, pos: Int) = TextUtils.findNextParagraphStart(text, pos)
	private fun findParagraphStartSingle(text: String, pos: Int) = TextUtils.findParagraphStartSingle(text, pos)
	private fun findNextParagraphStartSingle(text: String, pos: Int) = TextUtils.findNextParagraphStartSingle(text, pos)
	private fun isSentenceEnderAt(text: CharSequence, index: Int) = TextUtils.isSentenceEnderAt(
		text,
		index,
		isKnownAbbreviation = { callbacks.isKnownAbbreviation(it) },
		isKnownDomain = { callbacks.isKnownDomain(it) },
	)
	private fun toTitleCase(text: String) = TextUtils.toTitleCase(text)
	private fun toSentenceCase(text: String) = TextUtils.toSentenceCase(
		text,
		isKnownAbbreviation = { callbacks.isKnownAbbreviation(it) },
		isKnownDomain = { callbacks.isKnownDomain(it) },
	)
	private fun findWordAtCursorStart(text: String, pos: Int): Int {
		var i = pos
		if (i > 0 && i <= text.length && !text[i.coerceAtMost(text.length - 1)].isLetterOrDigit()) i--
		while (i > 0 && text[i - 1].isLetterOrDigit()) i--
		return i
	}
	private fun findWordAtCursorEnd(text: String, pos: Int): Int {
		var i = pos
		if (i > 0 && i <= text.length && i < text.length && !text[i].isLetterOrDigit()) i--
		while (i < text.length && text[i].isLetterOrDigit()) i++
		return i
	}

	// ── Cursor movement ─────────────────────────────────────────────────

	fun handleCursorMove(direction: Int, hadAmbig: Boolean, movementMode: Int = JTUI.MOVEMENT_CHARACTER_LINE, isSelecting: Boolean = false) {
		val ic = getInputConnection() ?: return
		if (hadAmbig) {
			val saved = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				ic.finishComposingText()
			} catch (_: Exception) {
			} finally {
				ignoreSelectionUpdate = saved
			}
			haveComposing = false
			lastComposingSent = null
			autoSpaceDecision = false
			autoSpaceInserted = false
			pendingTrailingSpace = false
		}
		val isPageMove = movementMode == JTUI.MOVEMENT_PARAGRAPH_PAGE &&
			(direction == JTUI.CURSOR_UP || direction == JTUI.CURSOR_DOWN)
		if (!isPageMove) clearPageMoveState()
		if (isSelecting) {
			handleSelectingCursorMove(ic, direction, movementMode)
		} else {
			selectionAnchor = -1
			if (isPageMove) {
				handlePageMove(ic, direction)
			} else {
				val needsTextCalc = (
					movementMode == JTUI.MOVEMENT_WORD_SENTENCE &&
						(direction == JTUI.CURSOR_UP || direction == JTUI.CURSOR_DOWN)
					) ||
					(movementMode == JTUI.MOVEMENT_PARAGRAPH_PAGE)
				if (needsTextCalc) {
					moveCursorByTextCalc(ic, direction, movementMode)
				} else {
					callbacks.sendDpadEvent(direction, movementMode)
				}
			}
		}
		lastImeEditMs = android.os.SystemClock.uptimeMillis()
	}

	private fun moveCursorByTextCalc(ic: InputConnection, direction: Int, movementMode: Int) {
		val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
		val text = et.text?.toString() ?: return
		val pos = et.selectionStart
		val newPos = when {
			movementMode == JTUI.MOVEMENT_WORD_SENTENCE && direction == JTUI.CURSOR_UP -> findSentenceStart(text, pos)
			movementMode == JTUI.MOVEMENT_WORD_SENTENCE && direction == JTUI.CURSOR_DOWN -> findSentenceEnd(text, pos)
			movementMode == JTUI.MOVEMENT_PARAGRAPH_PAGE && direction == JTUI.CURSOR_LEFT -> findParagraphStart(text, pos)
			movementMode == JTUI.MOVEMENT_PARAGRAPH_PAGE && direction == JTUI.CURSOR_RIGHT -> findNextParagraphStart(text, pos)
			else -> pos
		}
		if (newPos != pos) ic.setSelection(newPos, newPos)
	}

	private fun handlePageMove(ic: InputConnection, direction: Int) {
		if (pageMoveSavedCursorPos < 0) {
			val et = ic.getExtractedText(ExtractedTextRequest(), 0)
			pageMoveSavedCursorPos = et?.selectionStart ?: -1
			pageMoveBalance = 0
			callbacks.debugLog("[handlePageMove] Saved cursor pos=$pageMoveSavedCursorPos")
		}
		val delta = if (direction == JTUI.CURSOR_UP) -1 else 1
		pageMoveBalance += delta
		if (pageMoveBalance == 0 && pageMoveSavedCursorPos >= 0) {
			callbacks.debugLog("[handlePageMove] Balance returned to 0, restoring cursor to $pageMoveSavedCursorPos")
			ic.setSelection(pageMoveSavedCursorPos, pageMoveSavedCursorPos)
			clearPageMoveState()
			return
		}
		val now = android.os.SystemClock.uptimeMillis()
		val keyCode = if (direction == JTUI.CURSOR_UP) KeyEvent.KEYCODE_PAGE_UP else KeyEvent.KEYCODE_PAGE_DOWN
		ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
		ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
		callbacks.debugLog("[handlePageMove] Sent PAGE_${if (direction == JTUI.CURSOR_UP) "UP" else "DOWN"}, balance=$pageMoveBalance")
	}

	fun clearPageMoveState() {
		if (pageMoveSavedCursorPos >= 0) {
			callbacks.debugLog("[clearPageMoveState] Clearing page move state (was pos=$pageMoveSavedCursorPos, balance=$pageMoveBalance)")
		}
		pageMoveSavedCursorPos = -1
		pageMoveBalance = 0
	}

	fun handleScroll(direction: Int) {
		val ic = getInputConnection() ?: return
		if (haveComposing) {
			val saved = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				ic.finishComposingText()
			} catch (_: Exception) {
			} finally {
				ignoreSelectionUpdate = saved
			}
			haveComposing = false
			lastComposingSent = null
			autoSpaceDecision = false
			autoSpaceInserted = false
			pendingTrailingSpace = false
		}
		clearPageMoveState()
		val keyCode = if (direction == JTUI.CURSOR_UP) KeyEvent.KEYCODE_PAGE_UP else KeyEvent.KEYCODE_PAGE_DOWN
		callbacks.debugLog("[handleScroll] Sending ${if (direction == JTUI.CURSOR_UP) "PAGE_UP" else "PAGE_DOWN"}")
		ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
		ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
		lastImeEditMs = android.os.SystemClock.uptimeMillis()
	}

	private fun handleSelectingCursorMove(ic: InputConnection, direction: Int, movementMode: Int) {
		val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
		val text = et.text?.toString() ?: return
		val selStart = et.selectionStart
		val selEnd = et.selectionEnd
		if (selectionAnchor < 0) {
			selectionAnchor = selStart
			callbacks.debugLog("[handleSelectingCursorMove] Anchor set at $selectionAnchor")
		}
		val activeEnd = if (selStart == selectionAnchor) {
			selEnd
		} else if (selEnd == selectionAnchor) {
			selStart
		} else {
			selEnd
		}
		val needsKeyProbe = movementMode == JTUI.MOVEMENT_CHARACTER_LINE &&
			(direction == JTUI.CURSOR_UP || direction == JTUI.CURSOR_DOWN)
		val needsPageProbe = movementMode == JTUI.MOVEMENT_PARAGRAPH_PAGE &&
			(direction == JTUI.CURSOR_UP || direction == JTUI.CURSOR_DOWN)
		if (needsKeyProbe || needsPageProbe) {
			val keyCode = if (needsPageProbe) {
				if (direction == JTUI.CURSOR_UP) KeyEvent.KEYCODE_PAGE_UP else KeyEvent.KEYCODE_PAGE_DOWN
			} else {
				if (direction == JTUI.CURSOR_UP) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
			}
			val newActiveEnd = probeKeyPosition(ic, activeEnd, keyCode)
			callbacks.debugLog("[handleSelectingCursorMove] key probe: anchor=$selectionAnchor activeEnd=$activeEnd -> $newActiveEnd")
			ic.setSelection(selectionAnchor, newActiveEnd)
			return
		}
		val newActiveEnd = when (movementMode) {
			JTUI.MOVEMENT_CHARACTER_LINE -> when (direction) {
				JTUI.CURSOR_LEFT -> (activeEnd - 1).coerceAtLeast(0)
				JTUI.CURSOR_RIGHT -> (activeEnd + 1).coerceAtMost(text.length)
				else -> activeEnd
			}
			JTUI.MOVEMENT_WORD_SENTENCE -> when (direction) {
				JTUI.CURSOR_LEFT -> findWordBoundaryLeft(text, activeEnd)
				JTUI.CURSOR_RIGHT -> findWordBoundaryRight(text, activeEnd)
				JTUI.CURSOR_UP -> findSentenceStart(text, activeEnd)
				JTUI.CURSOR_DOWN -> findSentenceEnd(text, activeEnd)
				else -> activeEnd
			}
			JTUI.MOVEMENT_PARAGRAPH_PAGE -> when (direction) {
				JTUI.CURSOR_LEFT -> findParagraphStartSingle(text, activeEnd)
				JTUI.CURSOR_RIGHT -> findNextParagraphStartSingle(text, activeEnd)
				else -> activeEnd
			}
			else -> activeEnd
		}
		callbacks.debugLog("[handleSelectingCursorMove] anchor=$selectionAnchor activeEnd=$activeEnd -> $newActiveEnd")
		ic.setSelection(selectionAnchor, newActiveEnd)
	}

	private fun probeKeyPosition(ic: InputConnection, activeEnd: Int, keyCode: Int): Int {
		ic.beginBatchEdit()
		try {
			ic.setSelection(activeEnd, activeEnd)
			val now = android.os.SystemClock.uptimeMillis()
			ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
			ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
			val etAfter = ic.getExtractedText(ExtractedTextRequest(), 0)
			return etAfter?.selectionStart ?: activeEnd
		} finally {
			ic.endBatchEdit()
		}
	}

	// ── Bookmark ────────────────────────────────────────────────────────

	fun handleBookmark(action: Int, isSelecting: Boolean = false) {
		val ic = getInputConnection() ?: return
		if (haveComposing) {
			val saved = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				ic.finishComposingText()
			} catch (_: Exception) {
			} finally {
				ignoreSelectionUpdate = saved
			}
			haveComposing = false
			lastComposingSent = null
			autoSpaceDecision = false
			autoSpaceInserted = false
			pendingTrailingSpace = false
		}
		when (action) {
			JTUI.BOOKMARK_SET_A, JTUI.BOOKMARK_SET_B -> {
				val et = ic.getExtractedText(ExtractedTextRequest(), 0)
				if (et != null) {
					val pos = et.startOffset + et.selectionStart
					if (action == JTUI.BOOKMARK_SET_A) {
						bookmarkA = pos
						callbacks.debugLog("[handleBookmark] Set bookmark A to position $pos")
					} else {
						bookmarkB = pos
						callbacks.debugLog("[handleBookmark] Set bookmark B to position $pos")
					}
				}
			}
			JTUI.BOOKMARK_JUMP_A, JTUI.BOOKMARK_JUMP_B -> {
				val pos = if (action == JTUI.BOOKMARK_JUMP_A) bookmarkA else bookmarkB
				val label = if (action == JTUI.BOOKMARK_JUMP_A) "A" else "B"
				if (pos < 0) {
					callbacks.debugLog("[handleBookmark] Bookmark $label not set")
					return
				}
				val et = ic.getExtractedText(ExtractedTextRequest(), 0)
				val textLen = if (et?.text != null) et.startOffset + et.text.length else pos
				val clampedPos = pos.coerceAtMost(textLen)
				val savedIgnore = ignoreSelectionUpdate
				try {
					ignoreSelectionUpdate = true
					if (isSelecting) {
						if (selectionAnchor < 0 && et != null) {
							selectionAnchor = et.startOffset + et.selectionStart
						}
						callbacks.debugLog("[handleBookmark] Selecting from anchor=$selectionAnchor to bookmark $label at $clampedPos")
						ic.setSelection(selectionAnchor, clampedPos)
					} else {
						selectionAnchor = -1
						callbacks.debugLog("[handleBookmark] Jumping to bookmark $label at position $clampedPos")
						ic.setSelection(clampedPos, clampedPos)
					}
				} finally {
					ignoreSelectionUpdate = savedIgnore
				}
			}
		}
		lastImeEditMs = android.os.SystemClock.uptimeMillis()
	}

	// ── Case change ─────────────────────────────────────────────────────

	fun handleCaseChange(caseType: Int, caseMode: Int) {
		val ic = getInputConnection() ?: return
		val selected = ic.getSelectedText(0)?.toString()
		if (!selected.isNullOrEmpty()) {
			handleCaseChangeOnSelection(ic, selected, caseType, caseMode)
		} else {
			handleCaseChangeAtCursor(ic, caseType, caseMode)
		}
	}

	private fun handleCaseChangeOnSelection(ic: InputConnection, selected: String, caseType: Int, caseMode: Int) {
		val transformed = when (caseType) {
			CASE_TYPE_TITLE -> if (caseMode == CASE_MODE_SENTENCE) toSentenceCase(selected) else toTitleCase(selected)
			CASE_TYPE_UPPER -> selected.uppercase()
			CASE_TYPE_LOWER -> selected.lowercase()
			else -> return
		}
		if (transformed == selected) {
			callbacks.debugLog("[handleCaseChangeOnSelection] Already in target case, no-op")
			return
		}
		callbacks.debugLog("[handleCaseChangeOnSelection] Replacing '$selected' with '$transformed'")
		val saveIgnore = ignoreSelectionUpdate
		try {
			ignoreSelectionUpdate = true
			ic.commitText(transformed, 1)
			val curPos = getCursorOffset()
			ic.setSelection(curPos - transformed.length, curPos)
		} finally {
			ignoreSelectionUpdate = saveIgnore
		}
	}

	private fun handleCaseChangeAtCursor(ic: InputConnection, caseType: Int, caseMode: Int) {
		val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
		val text = et.text?.toString() ?: return
		val cursor = et.selectionStart.coerceIn(0, text.length)
		if (caseType == CASE_TYPE_TITLE && caseMode == CASE_MODE_SENTENCE) {
			val start = findSentenceStart(text, cursor)
			val end = findSentenceEnd(text, cursor)
			if (start >= end) return
			val sentence = text.substring(start, end)
			val transformed = toSentenceCase(sentence)
			if (transformed == sentence) return
			replaceRange(ic, start, end, transformed, cursor)
		} else {
			val wordStart = findWordAtCursorStart(text, cursor)
			val wordEnd = findWordAtCursorEnd(text, cursor)
			if (wordStart >= wordEnd) return
			val word = text.substring(wordStart, wordEnd)
			val transformed = when (caseType) {
				CASE_TYPE_TITLE -> toTitleCase(word)
				CASE_TYPE_UPPER -> word.uppercase()
				CASE_TYPE_LOWER -> word.lowercase()
				else -> return
			}
			if (transformed == word) return
			replaceRange(ic, wordStart, wordEnd, transformed, cursor)
		}
	}

	private fun replaceRange(ic: InputConnection, start: Int, end: Int, replacement: String, restoreCursor: Int) {
		callbacks.debugLog("[replaceRange] Replacing [$start..$end] with '$replacement', restoring cursor to $restoreCursor")
		val saveIgnore = ignoreSelectionUpdate
		try {
			ignoreSelectionUpdate = true
			ic.setSelection(start, end)
			ic.commitText(replacement, 1)
			val adjustedCursor = if (restoreCursor > end) {
				restoreCursor + (replacement.length - (end - start))
			} else if (restoreCursor >= start) {
				start + replacement.length
			} else {
				restoreCursor
			}
			ic.setSelection(adjustedCursor, adjustedCursor)
		} finally {
			ignoreSelectionUpdate = saveIgnore
		}
	}

	// ── Edit mode / deletion helpers ────────────────────────────────────

	fun handleEditModeExit() {
		clearPageMoveState()
		val autoRestore = callbacks.getAutoRestore()
		if (!autoRestore) {
			callbacks.debugLog("[handleEditModeExit] Auto-restore disabled, skipping pull-in")
			return
		}
		callbacks.debugLog("[handleEditModeExit] Attempting pull-in at current cursor")
		val result = tryImmediatePullInAtCurrentCursor(selectFirstOnMatch = true)
		if (result > 0) {
			haveComposing = true
			callbacks.debugLog("[handleEditModeExit] Pull-in succeeded")
		} else {
			callbacks.debugLog("[handleEditModeExit] No word at cursor (result=$result)")
			updateShiftFromCursor()
		}
	}

	fun handleEditingDelete() {
		val ic = getInputConnection() ?: return
		callbacks.debugLog("[handleEditingDelete] Deleting character before cursor")
		val saveIgnore = ignoreSelectionUpdate
		try {
			ignoreSelectionUpdate = true
			ic.deleteSurroundingText(1, 0)
		} finally {
			ignoreSelectionUpdate = saveIgnore
		}
	}

	// ── Deletion ────────────────────────────────────────────────────────

	fun deleteLeftChar(attemptPullIn: Boolean = true): Int {
		val ic = getInputConnection() ?: return -1
		val saveIgnoreSelectionUpdate = ignoreSelectionUpdate
		ignoreSelectionUpdate = true
		val et = try {
			ic.getExtractedText(ExtractedTextRequest(), 0)
		} catch (e: Exception) {
			null
		}

		val cursorPos = if (et != null && et.text != null) {
			et.selectionStart.coerceIn(0, et.text.length)
		} else {
			val before = try {
				ic.getTextBeforeCursor(1, 0)
			} catch (_: Exception) {
				null
			}
			if (before != null && before.isNotEmpty()) 1 else 0
		}

		callbacks.debugLog("[deleteLeftChar] Current cursor position: $cursorPos")

		if (cursorPos <= 0) {
			callbacks.debugLog("[deleteLeftChar] Cannot delete: cursor at start of text")
			return -1
		}

		val charToDelete = try {
			val textBefore = ic.getTextBeforeCursor(1, 0)
			if (textBefore != null && textBefore.isNotEmpty()) textBefore[0] else null
		} catch (e: Exception) {
			null
		}

		try {
			if (!attemptPullIn) {
				setIgnoreCursorRange(cursorPos - 1, cursorPos)
			} else {
				setIgnoreCursorRange(-1, -1)
			}
			ic.deleteSurroundingText(1, 0)
			val newCursorPos = cursorPos - 1
			callbacks.debugLog("[deleteLeftChar] Deleted character '$charToDelete', new cursor position: $newCursorPos")
			ignoreSelectionUpdate = saveIgnoreSelectionUpdate
			return newCursorPos
		} catch (e: Exception) {
			callbacks.debugLog("[deleteLeftChar] Error deleting character: ${e.message}")
			ignoreSelectionUpdate = saveIgnoreSelectionUpdate
			return -1
		}
	}

	fun handleDeleteWord() {
		callbacks.debugLog("[KF_DeleteWord] ENTRY")
		val ic = getInputConnection()
		if (ic == null) {
			callbacks.debugLog("[KF_DeleteWord] BLOCKED: inputConnection is null")
			callbacks.errorNotification()
			return
		}

		cancelPullIn()

		val prevSuspend = suspendCommit
		suspendCommit = true
		try {
			val hadComposing = haveComposing
			if (hadComposing) {
				val savedIgnore = ignoreSelectionUpdate
				getCurrentSelection()
				setIgnoreCursorRange(currentSelectionStart, currentSelectionEnd)
				try {
					ignoreSelectionUpdate = true
					ic.setComposingText("", 1)
				} catch (_: Exception) {
				} finally {
					ignoreSelectionUpdate = savedIgnore
				}
				haveComposing = false
				lastComposingSent = null
				lastPreview = null
				autoSpaceDecision = false
				autoSpaceInserted = false
				val capitalize = callbacks.getShiftState()
				val isManual = callbacks.getIsManualShift()
				val speakState = callbacks.getSpeakState()
				callbacks.resetJTUI(capitalize, true, isManual, autoCapReason = lastAutoCapReason)
				callbacks.setSpeakState(speakState, false)
				// C2: the composing word is gone; predictions continue from the
				// committed text still before the cursor.
				callbacks.reconstructNgbContext(ic.getTextBeforeCursor(NGB_CTX_READ_CHARS, 0)?.toString())
				callbacks.debugLog("[KF_DeleteWord] Removed composing text and cleared buffers")
				return
			}

			val savedIgnore = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				ic.finishComposingText()
			} catch (_: Exception) {
			} finally {
				ignoreSelectionUpdate = savedIgnore
			}
			haveComposing = false
			lastComposingSent = null
			lastPreview = null
			autoSpaceDecision = false
			autoSpaceInserted = false

			val editorState = run {
				val req = ExtractedTextRequest()
				val etNow = try {
					ic.getExtractedText(req, 0)
				} catch (_: Exception) {
					null
				}
				if (etNow?.text != null) {
					Pair(etNow.text.toString(), etNow.selectionStart.coerceIn(0, etNow.text.length))
				} else {
					val before = try {
						ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
					} catch (_: Exception) {
						""
					}
					val after = try {
						ic.getTextAfterCursor(500, 0)?.toString() ?: ""
					} catch (_: Exception) {
						""
					}
					if (before.isEmpty() && after.isEmpty()) null else Pair(before + after, before.length)
				}
			}

			if (editorState == null) {
				callbacks.debugLog("[KF_DeleteWord] BLOCKED: unable to read editor text")
				callbacks.errorNotification()
				return
			}

			val full = editorState.first
			var cursorPos = editorState.second.coerceIn(0, full.length)
			if (full.isEmpty()) {
				callbacks.debugLog("[KF_DeleteWord] No text available to delete")
				callbacks.errorNotification()
				return
			}
			val textLength = full.length

			fun isNumericChar(c: Char): Boolean {
				val numericExtras = setOf(
					'+', '-', '/', '*', '=', '.', ',', ':', '#', '%', '(', ')', '$', '€', '¥',
					'£', '￦', '¢', '°', '℃', '℉', 'e', 'a', 'm', 'p',
				)
				return c.isDigit() || (c !in setOf(' ', '\t', '\n', '\r') && c in numericExtras)
			}

			fun moveCursorSilently(pos: Int) {
				val target = pos.coerceIn(0, textLength)
				val saved = ignoreSelectionUpdate
				try {
					ignoreSelectionUpdate = true
					setIgnoreCursorRange(target, target)
					ic.setSelection(target, target)
				} catch (_: Exception) {
				} finally {
					ignoreSelectionUpdate = saved
				}
			}

			fun deleteRange(start: Int, end: Int, label: String, clearIgnoreRange: Boolean = true): Boolean {
				val safeStart = start.coerceIn(0, textLength)
				val safeEnd = end.coerceIn(safeStart, textLength)
				if (safeStart >= safeEnd) return false
				val beforeCount = safeEnd - safeStart
				val saved = ignoreSelectionUpdate
				return try {
					setIgnoreCursorRange(safeStart, safeEnd)
					ignoreSelectionUpdate = true
					ic.setSelection(safeEnd, safeEnd)
					ic.deleteSurroundingText(beforeCount, 0)
					ic.setSelection(safeStart, safeStart)
					callbacks.debugLog("[KF_DeleteWord] $label deleted range $safeStart..$safeEnd")
					true
				} catch (e: Exception) {
					callbacks.debugLog("[KF_DeleteWord] $label deletion failed: ${e.message}")
					false
				} finally {
					ignoreSelectionUpdate = saved
					if (clearIgnoreRange) {
						setIgnoreCursorRange(-1, -1)
					}
				}
			}

			fun finalizeAfterDelete(callUpdate: Boolean = true) {
				haveComposing = false
				lastComposingSent = null
				autoSpaceDecision = false
				autoSpaceInserted = false
				try {
					updateShiftFromCursor(true)
				} catch (_: Exception) {
				}
				val capitalize = callbacks.getShiftState()
				val isManual = callbacks.getIsManualShift()
				val speakState = callbacks.getSpeakState()
				callbacks.resetJTUI(capitalize, callUpdate, isManual, autoCapReason = lastAutoCapReason)
				callbacks.setSpeakState(speakState, false)
				// C2 (plan item 9): a whole word was deleted; invisibly re-derive
				// the NGB context from the text now before the cursor, so the
				// replacement word gets its zero-K window and prediction block.
				callbacks.reconstructNgbContext(ic.getTextBeforeCursor(NGB_CTX_READ_CHARS, 0)?.toString())
			}

			when (canPullInAtCursor(checkRightContext = true)) {
				1 -> {
					if (deleteRange(detectedStart, detectedEnd, "producible", clearIgnoreRange = false)) {
						finalizeAfterDelete(false)
						return
					}
				}
				-1 -> {
					// Error/blocked: fall back to delete-char
				}
			}

			val wordRight = cursorPos
			var workingCursor = cursorPos
			val charBefore = full.getOrNull(cursorPos - 1)

			if (charBefore != null && charBefore.isWhitespace()) {
				while (workingCursor > 0 && full[workingCursor - 1].isWhitespace()) {
					workingCursor--
				}
				moveCursorSilently(workingCursor)
				val pullLeft = canPullInAtCursor(checkRightContext = true)
				if (pullLeft == 1) {
					setIgnoreCursorRange(detectedStart, wordRight)
					if (deleteRange(detectedStart, wordRight, "whitespace-left", clearIgnoreRange = false)) {
						finalizeAfterDelete(false)
						return
					}
				}
			}

			if (workingCursor > 0 && isNumericChar(full[workingCursor - 1])) {
				var startNum = workingCursor
				while (startNum > 0 && isNumericChar(full[startNum - 1])) {
					startNum--
				}
				setIgnoreCursorRange(startNum, wordRight)
				if (deleteRange(startNum, wordRight, "numeric", clearIgnoreRange = false)) {
					finalizeAfterDelete(false)
					return
				}
			}

			fun isAlpha23(c: Char): Boolean = callbacks.isAlphaChar(c) || callbacks.isWordDbChar(c)
			var startDel = workingCursor
			while (startDel > 0 && isAlpha23(full[startDel - 1])) {
				startDel--
			}
			if (startDel < wordRight) {
				setIgnoreCursorRange(startDel, wordRight)
				if (deleteRange(startDel, wordRight, "fallback-alpha", clearIgnoreRange = false)) {
					finalizeAfterDelete(false)
					return
				}
			}

			callbacks.debugLog("[KF_DeleteWord] No word found to delete (cursor=$cursorPos), falling back to DeleteChar")
			moveCursorSilently(cursorPos)
			handleDeleteChar()
		} finally {
			suspendCommit = prevSuspend
		}
	}

	fun handleDeleteChar() {
		callbacks.debugLog("[KF_DeleteChar] ENTRY")
		val ic = getInputConnection()
		if (ic == null) {
			callbacks.debugLog("[KF_DeleteChar] BLOCKED: inputConnection is null")
			callbacks.errorNotification()
			return
		}

		cancelPullIn()

		val prevSuspend = suspendCommit
		suspendCommit = true
		try {
			val savedIgnore = ignoreSelectionUpdate
			if (haveComposing) {
				try {
					ignoreSelectionUpdate = true
					ic.finishComposingText()
				} catch (_: Exception) {
				} finally {
					ignoreSelectionUpdate = savedIgnore
				}
				haveComposing = false
				lastComposingSent = null
				lastPreview = null
				autoSpaceDecision = false
				autoSpaceInserted = false
				val capitalize = callbacks.getShiftState()
				val isManual = callbacks.getIsManualShift()
				val speakState = callbacks.getSpeakState()
				callbacks.resetJTUI(capitalize, true, isManual, autoCapReason = lastAutoCapReason)
				callbacks.setSpeakState(speakState, false)
			} else {
				try {
					ignoreSelectionUpdate = true
					ic.finishComposingText()
				} catch (_: Exception) {
				} finally {
					ignoreSelectionUpdate = savedIgnore
				}
				haveComposing = false
				lastComposingSent = null
				lastPreview = null
				autoSpaceDecision = false
				autoSpaceInserted = false
			}

			val res = deleteLeftChar(false)
			if (res == -1) {
				callbacks.errorBeep(false)
			} else {
				try {
					updateShiftFromCursor(true)
				} catch (e: Exception) {
					ExceptionReporter.reportSilent("ImeTextController:handleDeleteChar", e)
				}
			}
		} finally {
			suspendCommit = prevSuspend
		}
	}

	fun deleteSurroundingWithLog(label: String, beforeCount: Int, afterCount: Int) {
		val ic = getInputConnection() ?: return
		val sel = getCursorOffset()
		val beforeStr = try {
			ic.getTextBeforeCursor(beforeCount, 0)?.toString() ?: ""
		} catch (_: Exception) {
			""
		}
		val afterStr = try {
			ic.getTextAfterCursor(afterCount, 0)?.toString() ?: ""
		} catch (_: Exception) {
			""
		}
		callbacks.debugLog("$label call deleteSurroundingText(before=$beforeCount, after=$afterCount) at sel=$sel; will delete before='$beforeStr' after='$afterStr'")
		val startDel = (sel - beforeCount).coerceAtLeast(0)
		val endDel = (sel + afterCount).coerceAtLeast(startDel)
		setIgnoreCursorRange(startDel, endDel)
		val saveIgnoreSelectionUpdate = ignoreSelectionUpdate
		try {
			ignoreSelectionUpdate = true
			ic.deleteSurroundingText(beforeCount, afterCount)
		} finally {
			ignoreSelectionUpdate = saveIgnoreSelectionUpdate
			setIgnoreCursorRange(-1, -1)
		}
		val selAfter = getCursorOffset()
		callbacks.debugLog("$label after delete: sel=$selAfter")
	}

	// ── Auto-shift ──────────────────────────────────────────────────────

	fun updateShiftFromCursor(suppressUpdateUI: Boolean = false) {
		val isManualShift = callbacks.getIsManualShift()
		val shouldShift: Boolean
		val reason: AutoCapReason
		if (isManualShift) {
			shouldShift = callbacks.getShiftState()
			reason = AutoCapReason.MANUAL
		} else {
			shouldShift = try {
				computeAutoShift()
			} catch (_: Exception) {
				false
			}
			reason = lastAutoCapReason
		}
		callbacks.debugLog("[updateShiftFromCursor] auto-shift: shouldShift=$shouldShift isManualShift=$isManualShift suppressUpdateUI=$suppressUpdateUI reason=$reason")
		callbacks.setShiftState(shouldShift, isManualShift, suppressUpdateUI, reason)
	}

	internal fun computeAutoShift(): Boolean {
		lastAutoCapReason = AutoCapReason.NONE
		val ic = getInputConnection() ?: return false
		val et = try {
			ic.getExtractedText(ExtractedTextRequest(), 0)
		} catch (_: Exception) {
			null
		} ?: return false
		val full = et.text?.toString() ?: return false
		val sel = et.selectionStart.coerceIn(0, full.length)
		if (sel <= 0) {
			lastAutoCapReason = AutoCapReason.FIELD_START
			return true
		}
		// Start-of-line: only whitespace since last newline
		run {
			var k = sel - 1
			while (k >= 0 && full[k].isWhitespace() && !callbacks.isLineBreak(full[k])) k--
			if (k >= 0 && callbacks.isLineBreak(full[k])) {
				callbacks.debugLog("[computeAutoShift] auto-shift: start-of-line whitespace segment -> true")
				lastAutoCapReason = AutoCapReason.LINE_START
				return true
			}
		}
		// Check for sentence-end
		var i = sel - 1
		var ws = 0
		while (i >= 0 && full[i].isWhitespace()) {
			ws++
			i--
		}
		if (i < 0) return true
		val c = full[i]
		val res = TextUtils.isEosChar(c) && (ws >= 1)
		callbacks.debugLog("[computeAutoShift] eos='$c' ws=$ws -> $res")
		if (res) {
			lastAutoCapReason = if (c == '.' &&
				TextUtils.isLikelyAbbreviation(
					full,
					i,
					isKnownAbbreviation = { callbacks.isKnownAbbreviation(it) },
				)
			) {
				AutoCapReason.ABBREVIATION
			} else {
				AutoCapReason.SENTENCE_START
			}
		} else {
			lastAutoCapReason = AutoCapReason.NONE
		}
		return res
	}

	// ── Speech text helpers ─────────────────────────────────────────────

	fun extractLastSentenceFromEditor(setCursorToNextSentence: Boolean = false): String? {
		callbacks.debugLog("[extractLastSentenceFromEditor] ENTRY setCursorToNextSentence=$setCursorToNextSentence")
		val ic = getInputConnection() ?: return null
		val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
		val text = et.text?.toString() ?: return null
		val cursor = et.selectionStart.coerceIn(0, text.length)
		if (text.isEmpty()) return null

		val sentenceStart: Int
		val sentenceEnd: Int

		if (setCursorToNextSentence) {
			var nextStart = cursor
			while (nextStart < text.length && text[nextStart].isWhitespace()) nextStart++
			while (nextStart < text.length && isSentenceEnderAt(text, nextStart)) nextStart++
			while (nextStart < text.length && text[nextStart].isWhitespace()) nextStart++
			if (nextStart >= text.length) return null
			sentenceStart = nextStart
			sentenceEnd = findSentenceEnd(text, sentenceStart)
			var newPos = sentenceEnd
			while (newPos < text.length && text[newPos].isWhitespace()) newPos++
			callbacks.debugLog("[extractLastSentenceFromEditor] next: moving cursor to $newPos")
			setIgnoreCursorRange(newPos - 1, newPos + 1)
			ic.setSelection(newPos, newPos)
			setIgnoreCursorRange(-1, -1)
		} else {
			sentenceStart = findSentenceStart(text, cursor)
			sentenceEnd = findSentenceEnd(text, sentenceStart)
		}

		if (sentenceStart < sentenceEnd) {
			val sentence = text.substring(sentenceStart, sentenceEnd).trim()
			callbacks.debugLog("[extractLastSentenceFromEditor] result: '${sentence.take(80)}'")
			return if (sentence.isNotBlank()) sentence else null
		}
		return null
	}

	fun shouldSpeakSentence(): Boolean {
		val ic = getInputConnection() ?: return false
		val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
		val text = et.text?.toString() ?: return false
		val cursor = et.selectionStart.coerceIn(0, text.length)
		var i = cursor - 1
		while (i >= 0 && text[i].isWhitespace() && !callbacks.isLineBreak(text[i])) i--
		if (i < 0 || callbacks.isLineBreak(text[i])) return false
		return isSentenceEnderAt(text, i)
	}

	// ── Pull-in ─────────────────────────────────────────────────────────

	fun cancelPullIn() {
		try {
			pendingPullIn?.let { mainHandler.removeCallbacks(it) }
		} catch (e: Exception) {
			ExceptionReporter.reportSilent("ImeTextController:cancelPullIn", e)
		}
		pendingPullIn = null
		pullInToken++
		isPullInMode = false
	}

	fun canPullInAtCursor(checkRightContext: Boolean = true): Int {
		detectedWord = ""
		detectedStart = -1
		detectedEnd = -1

		if (!callbacks.isJtuiInitialized) {
			callbacks.debugLog("[canPullInAtCursor 1] BLOCKED: jtui NOT initialized")
			return -1
		}
		val ic = getInputConnection()
		if (ic == null) {
			callbacks.debugLog("[canPullInAtCursor 2] BLOCKED: currentInputConnection is NULL")
			return -1
		}
		val req = ExtractedTextRequest()
		val et = try {
			ic.getExtractedText(req, 0)
		} catch (e: Exception) {
			callbacks.debugLog("[canPullInAtCursor 3a] WARNING: getExtractedText() threw exception: ${e.message}, trying fallback")
			null
		}

		val full: String
		val cursorPos: Int
		val textStartOffset: Int
		if (et == null || et.text == null) {
			callbacks.debugLog("[canPullInAtCursor 3b] getExtractedText() returned null, using fallback method")
			var before: CharSequence? = null
			var after: CharSequence? = null
			try {
				before = ic.getTextBeforeCursor(500, 0)
				callbacks.debugLog("[canPullInAtCursor 3b1] getTextBeforeCursor returned: '$before' (null=${before == null}, length=${before?.length ?: 0})")
			} catch (e: Exception) {
				callbacks.debugLog("[canPullInAtCursor 3b2] getTextBeforeCursor threw: ${e.message}")
			}
			try {
				after = ic.getTextAfterCursor(500, 0)
				callbacks.debugLog("[canPullInAtCursor 3b3] getTextAfterCursor returned: '$after' (null=${after == null}, length=${after?.length ?: 0})")
			} catch (e: Exception) {
				callbacks.debugLog("[canPullInAtCursor 3b4] getTextAfterCursor threw: ${e.message}")
			}
			val beforeStr = before?.toString() ?: ""
			val afterStr = after?.toString() ?: ""
			if (beforeStr.isEmpty() && afterStr.isEmpty()) {
				callbacks.debugLog("[canPullInAtCursor 3c] BLOCKED: Both getTextBeforeCursor and getTextAfterCursor returned empty")
				return -1
			}
			full = beforeStr + afterStr
			cursorPos = beforeStr.length
			textStartOffset = 0
			callbacks.debugLog("[canPullInAtCursor 3d] Fallback: before.length=${beforeStr.length}, after.length=${afterStr.length}, cursorPos=$cursorPos, full='$full'")
		} else {
			full = et.text.toString()
			cursorPos = et.selectionStart.coerceIn(0, full.length)
			textStartOffset = et.startOffset.coerceAtLeast(0)
			callbacks.debugLog("[canPullInAtCursor 3e] ExtractedText: full.length=${full.length}, cursorPos=$cursorPos, et.selectionEnd=${et.selectionEnd}, startOffset=$textStartOffset")
		}

		val contextStart = (cursorPos - 20).coerceAtLeast(0)
		val contextEnd = (cursorPos + 20).coerceAtMost(full.length)
		val contextBefore = full.substring(contextStart, cursorPos)
		val contextAfter = full.substring(cursorPos, contextEnd)
		callbacks.debugLog("[canPullInAtCursor 4] cursorPos=$cursorPos full.length=${full.length} context='$contextBefore|$contextAfter' checkRightContext=$checkRightContext")

		fun isAlpha2(c: Char) = callbacks.isAlphaChar(c)
		fun isAlpha3(c: Char) = callbacks.isWordDbChar(c)
		fun isAlpha23(c: Char) = isAlpha2(c) || isAlpha3(c)

		val charBefore = if (cursorPos > 0) full.getOrNull(cursorPos - 1) else null
		val charAt = if (cursorPos < full.length) full.getOrNull(cursorPos) else null
		return try {
			val leftCheck = (cursorPos > 0 && isAlpha23(charBefore ?: ' '))
			val rightCheck = if (checkRightContext) {
				(cursorPos < full.length && isAlpha23(charAt ?: ' '))
			} else {
				false
			}
			callbacks.debugLog("[canPullInAtCursor 5] charBefore='$charBefore' isAlpha=$leftCheck, charAt='$charAt' isAlpha=$rightCheck")

			val touching = leftCheck
			if (!touching) {
				callbacks.debugLog("[canPullInAtCursor 6] FAILED cursorPos=$cursorPos NOT touching any alpha chars (leftCheck only)")
				return 0
			}
			var endConservative = cursorPos
			if (checkRightContext) {
				while (endConservative < full.length && isAlpha2(full[endConservative])) endConservative++
			}
			var endExtended = cursorPos
			if (checkRightContext) {
				while (endExtended < full.length && isAlpha23(full[endExtended])) endExtended++
			}
			var endTrimmed = endExtended
			while (endTrimmed > cursorPos && !isAlpha2(full[endTrimmed - 1])) endTrimmed--

			val rightBounds = mutableListOf<Int>()
			if (endExtended > cursorPos) rightBounds.add(endExtended)
			if (endTrimmed != endExtended && endTrimmed > cursorPos) rightBounds.add(endTrimmed)
			if (endConservative > cursorPos && endConservative != endExtended && endConservative != endTrimmed) rightBounds.add(endConservative)
			if (rightBounds.isEmpty()) rightBounds.add(cursorPos)

			callbacks.debugLog("[canPullInAtCursor 6b] Right bounds: ext=$endExtended trim=$endTrimmed cons=$endConservative (${rightBounds.size} unique)")

			fun expandLeftAndSearch(rightBound: Int): Triple<String, Int, Int>? {
				var start = cursorPos
				var foundWord = false
				var bestWord = ""
				var bestStart = -1
				var stillLooking = true
				while (stillLooking) {
					var lastPos = start
					while (start > 0 && isAlpha2(full[start - 1])) start--
					if (start < lastPos) {
						val testWord = full.substring(start, rightBound.coerceAtMost(full.length))
						if (testWord.isNotEmpty() && callbacks.isWordProducible(testWord)) {
							foundWord = true
							bestWord = testWord
							bestStart = start
						}
					} else {
						stillLooking = false
					}
					lastPos = start
					while (start > 0 && isAlpha3(full[start - 1])) start--
					if (start < lastPos) {
						val testWord = full.substring(start, rightBound.coerceAtMost(full.length))
						if (testWord.isNotEmpty() && callbacks.isWordProducible(testWord)) {
							foundWord = true
							bestWord = testWord
							bestStart = start
						}
					} else {
						stillLooking = false
					}
				}
				return if (foundWord) Triple(bestWord, bestStart, rightBound) else null
			}

			var searchResult: Triple<String, Int, Int>? = null
			for (rightBound in rightBounds) {
				searchResult = expandLeftAndSearch(rightBound)
				if (searchResult != null) {
					callbacks.debugLog("[canPullInAtCursor 8] Match with rightBound=$rightBound: '${searchResult.first}' at ${searchResult.second}..$rightBound")
					break
				}
			}

			if (searchResult != null) {
				val (wordNow, wordStart, endUsed) = searchResult
				detectedWord = wordNow
				detectedStart = wordStart + textStartOffset
				detectedEnd = endUsed + textStartOffset
				callbacks.debugLog("[canPullInAtCursor 9] SUCCESS: Found producible word '$wordNow' at abs $detectedStart..$detectedEnd (textRelative $wordStart..$endUsed, textStartOffset=$textStartOffset, cursorPos=$cursorPos)")
				1
			} else {
				0
			}
		} catch (e: Exception) {
			callbacks.debugLog("[canPullInAtCursor ERR] Exception near cursorPos=$cursorPos: ${e.message}")
			-1
		}
	}

	/**
	 * Called from the head-tracking subsystem when the user exits the keyboard
	 * (UP pop-out or RIGHT pause). Sets a 1-second window during which the
	 * next tryImmediatePullInAtCurrentCursor call is a no-op. Defends against
	 * the spurious onStartInput cycle Android fires during HeadBoard's pop-out
	 * window-stack changes, which would otherwise immediately re-populate
	 * state from the cursor position and undo the exit-time clear.
	 */
	fun suppressPullInForExit() {
		pullInSuppressedUntilMs = android.os.SystemClock.uptimeMillis() + PULL_IN_SUPPRESS_AFTER_EXIT_MS
		callbacks.debugLog("[suppressPullInForExit] Pull-in suppressed for ${PULL_IN_SUPPRESS_AFTER_EXIT_MS}ms")
	}

	/**
	 * Called from the head-tracking subsystem on intentional keyboard
	 * re-entry (frame-gap detector fires, or the legacy pausedFlow latch
	 * resolves). If the "Auto-Load Word at Cursor" preference is ON,
	 * triggers an immediate pull-in at the current cursor — matching the
	 * behavior of a fresh tap on a word in the text field. With Auto-Load
	 * OFF, this is a no-op and the user can still use Select to manually
	 * pull in.
	 *
	 * The exit-time pull-in suppression (set by suppressPullInForExit) is
	 * deliberately NOT cleared here — it's still protecting against any
	 * straggler spurious onStartInput. The intentional re-entry pull-in
	 * bypasses the suppression for this single call only, via the
	 * bypassExitSuppression flag.
	 */
	fun onKeyboardReEntry() {
		if (callbacks.getAutoRestore()) {
			callbacks.debugLog("[onKeyboardReEntry] Auto-Load is ON, attempting pull-in at cursor")
			tryImmediatePullInAtCurrentCursor(selectFirstOnMatch = true, bypassExitSuppression = true)
		} else {
			callbacks.debugLog("[onKeyboardReEntry] Auto-Load is OFF, skipping pull-in (user can press Select to pull in)")
		}
	}

	/**
	 * Derive the NGB context from the text before the cursor — the FIELD-ENTRY
	 * funnel (Cliff's "the never recovers at BOS", 2026-08-12). Pull-in and
	 * delete-word reconstruct on their own paths, but a field entry with NO
	 * producible word at the cursor (empty field, cursor after a space or
	 * sentence-final punctuation) previously left the context NULL: the BOS
	 * row never served at fresh-field sentence starts, and mid-text cursor
	 * placement lost the preceding word. No IC / no text stays fail-soft null.
	 */
	fun reconstructNgbContextAtCursor() {
		val ic = getInputConnection() ?: run {
			android.util.Log.d("NGB_TRACE", "[reconstructAtCursor] NO InputConnection — context stays as-is")
			return
		}
		val text = ic.getTextBeforeCursor(NGB_CTX_READ_CHARS, 0)?.toString()
		android.util.Log.d("NGB_TRACE", "[reconstructAtCursor] textBefore=${text?.let { "'…${it.takeLast(12).replace("\n", "\\n")}'" } ?: "null"}")
		callbacks.reconstructNgbContext(text)
	}

	fun tryImmediatePullInAtCurrentCursor(
		selectFirstOnMatch: Boolean = false,
		// Intentional re-entry from the head-tracking subsystem sets this true
		// to bypass the exit-time suppression window without clearing it —
		// later spurious onStartInput calls within the same window still get
		// suppressed.
		bypassExitSuppression: Boolean = false,
		// Same-field resume: restore this pre-pause ambiguous sequence (no Select
		// activations) over the word at the cursor — ONLY when it prefix-matches
		// (or equals) that word's key sequence. Mismatch = no restore, no pull-in.
		resumeKeys: List<Int>? = null,
	): Int {
		callbacks.debugLog("[tryImmediatePullInAtCurrentCursor 0] ENTRY: selectFirstOnMatch=$selectFirstOnMatch, haveComposing=$haveComposing, bypassExitSuppression=$bypassExitSuppression")
		if (!bypassExitSuppression) {
			val now = android.os.SystemClock.uptimeMillis()
			if (now < pullInSuppressedUntilMs) {
				callbacks.debugLog("[tryImmediatePullInAtCurrentCursor 0a] SUPPRESSED: recent keyboard exit (${pullInSuppressedUntilMs - now}ms remaining)")
				return -1
			}
		}
		if (haveComposing) {
			callbacks.debugLog("[tryImmediatePullInAtCurrentCursor 1] BLOCKED: haveComposing=true")
			return -1
		}

		val canPullIn = canPullInAtCursor(checkRightContext = true)
		if (canPullIn != 1) {
			callbacks.debugLog("[tryImmediatePullInAtCurrentCursor 2] BLOCKED: canPullInAtCursor returned $canPullIn (1=ok, 0=no producible word, -1=no IC / not initialized)")
			return canPullIn
		}

		val msSinceLastPullIn = android.os.SystemClock.uptimeMillis() - lastPullInTs
		if (isPullInMode || (detectedWord.equals(lastPullInWord, ignoreCase = true) && detectedEnd == lastPullInEnd && msSinceLastPullIn < 2000L)) {
			callbacks.debugLog("[tryImmediatePullInAtCurrentCursor 10] BLOCKED: word '$detectedWord' at $detectedStart..$detectedEnd matches recent pull-in (${msSinceLastPullIn}ms ago, isPullInMode=$isPullInMode)")
			return 1
		}
		var keyCount: Int? = null
		if (resumeKeys != null) {
			val wordKeys = callbacks.wordKeyIndices(detectedWord)
			if (wordKeys == null || resumeKeys.size > wordKeys.size || wordKeys.take(resumeKeys.size) != resumeKeys) {
				callbacks.debugLog("[tryImmediatePullInAtCurrentCursor 13] resume sequence (${resumeKeys.size} keys) does not prefix '$detectedWord' — no restore, no pull-in")
				return 0
			}
			keyCount = resumeKeys.size
		}
		callbacks.debugLog("[tryImmediatePullInAtCurrentCursor 11] Calling runPullInFlow() for '$detectedWord' at $detectedStart..$detectedEnd (keyCount=$keyCount)")
		val flowOk = runPullInFlow(detectedWord, detectedStart, detectedEnd, selectFirstOnMatch, keyCount = keyCount)
		callbacks.debugLog("[tryImmediatePullInAtCurrentCursor 12] runPullInFlow returned $flowOk → pull-in result=${if (flowOk) 1 else 0}")
		return if (flowOk) 1 else 0
	}

	// C3: the span text as it stood in the field at pull-in (original casing) —
	// the collapse restores its tail as committed text. Null = no span session.
	private var ngbSpanOriginalText: String? = null

	/**
	 * C3 collapse (Cliff spec 5/6): the span group closes; only the tapped
	 * word remains composing, and the span's TAIL returns to committed text —
	 * replicating the ordinary pull-in state of the tapped word ("replicate
	 * that initial state"). Mechanism: restore the full span as composing,
	 * commit it wholesale, then re-mark just the tapped word as composing.
	 */
	fun handleNgbSpanCollapse() {
		val ic = getInputConnection() ?: return
		val spanText = ngbSpanOriginalText ?: return
		val word = lastPullInWord ?: return
		ngbSpanOriginalText = null
		val savedIgnore = ignoreSelectionUpdate
		try {
			ignoreSelectionUpdate = true
			setIgnoreCursorRange(lastPullInStart, lastPullInStart + spanText.length)
			ic.setComposingText(spanText, 1)
			ic.finishComposingText()
			ic.setComposingRegion(lastPullInStart, lastPullInStart + word.length)
		} catch (e: Exception) {
			callbacks.debugLog("[ngbSpanCollapse] composing-region restore failed: ${e.message}")
		} finally {
			ignoreSelectionUpdate = savedIgnore
			setIgnoreCursorRange(-1, -1)
		}
		// Force the next applyEditorUpdate to re-send the (tapped-word) composing.
		lastComposingSent = null
		haveComposing = true
		callbacks.debugLog("[ngbSpanCollapse] tail restored; composing='$word' span='$spanText'")
	}

	internal fun runPullInFlow(word: String, startAbs: Int, endAbs: Int, selectFirstOnMatch: Boolean = false, suppressUIUpdate: Boolean = false, keyCount: Int? = null): Boolean {
		val ambLenBefore = callbacks.getAmbiguousSequenceLength()
		callbacks.debugLog("[runPullInFlow 0] ENTRY: word='$word', ambLenBefore=$ambLenBefore, suppressUIUpdate=$suppressUIUpdate")
		val icNow = getInputConnection() ?: return false
		lastPullInWord = word
		lastPullInStart = startAbs
		lastPullInEnd = endAbs
		lastPullInTs = android.os.SystemClock.uptimeMillis()
		val lastPullInModeFlag = isPullInMode
		isPullInMode = true

		// Captured before the try so the catch can restore the caller's value (not force false, which
		// would re-enable commits inside a region a nested caller had suppressed).
		val prevSuspend = suspendCommit
		try {
			val req = ExtractedTextRequest()
			val et2 = icNow.getExtractedText(req, 0) ?: return false
			val full2 = et2.text?.toString() ?: return false
			val valOffset = et2.startOffset.coerceAtLeast(0)
			val relStart = startAbs - valOffset
			val relEnd = endAbs - valOffset
			if (relStart < 0 || relEnd > full2.length || relStart >= relEnd) {
				callbacks.debugLog("[runPullInFlow 0] pull-in validation: Abort: invalid bounds abs=$startAbs..$endAbs rel=$relStart..$relEnd len=${full2.length} startOffset=$valOffset")
				isPullInMode = lastPullInModeFlag
				return false
			}
			val slice = try {
				full2.substring(relStart, relEnd)
			} catch (_: Exception) {
				""
			}
			if (!slice.equals(word, ignoreCase = true)) {
				callbacks.debugLog("[runPullInFlow 1] pull-in/validate Abort: slice '$slice' != word '$word' (abs=$startAbs..$endAbs rel=$relStart..$relEnd startOffset=$valOffset)")
				isPullInMode = lastPullInModeFlag
				return false
			}

			// === STEP 0.5 (C3): probe for an NGB span starting at the tapped word ===
			// Standard interactive pull-ins only (not prefix-resume). The probe
			// also performs the C2 context reconstruction it needs.
			val spanExtra = if (keyCount == null) {
				callbacks.probeNgbSpan(word, full2.substring(0, relStart), full2.substring(relEnd))
			} else {
				0
			}
			val spanEndAbs = endAbs + spanExtra
			ngbSpanOriginalText = if (spanExtra > 0) full2.substring(relStart, relEnd + spanExtra) else null
			if (spanExtra > 0) {
				lastPullInEnd = spanEndAbs
				callbacks.debugLog("[runPullInFlow 0.5] C3 span match: '$ngbSpanOriginalText' (+$spanExtra chars)")
			}

			// === STEP 1: Delete the word (or its full matched span) from the text field ===
			val cursorPos = (et2.selectionStart + valOffset).coerceIn(startAbs, endAbs)
			val charsBefore = cursorPos - startAbs
			val charsAfter = spanEndAbs - cursorPos
			setIgnoreCursorRange(startAbs, spanEndAbs)
			val saveIgnoreSelectionUpdate = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				callbacks.debugLog("[runPullInFlow 1a] deleteSurroundingText($charsBefore, $charsAfter) at cursor=$cursorPos: word='$word' range=$startAbs..$spanEndAbs")
				icNow.deleteSurroundingText(charsBefore, charsAfter)
				callbacks.debugLog("[runPullInFlow 1b] Word '$word' deleted from editor")
			} finally {
				ignoreSelectionUpdate = saveIgnoreSelectionUpdate
				setIgnoreCursorRange(-1, -1)
			}
			haveComposing = false
			lastComposingSent = null

			// === STEP 2: Reset JTUI and replay key sequence ===
			val letters = word.filter { it.isLetter() }
			val isAllUpper = letters.isNotEmpty() && letters.all { it.isUpperCase() }
			val isTitleCase = letters.isNotEmpty() &&
				letters.first().isUpperCase() &&
				(letters.length == 1 || letters.drop(1).all { it.isLowerCase() })
			val capitalize = isTitleCase

			callbacks.debugLog("[runPullInFlow 2a] Case pattern for '$word': isAllUpper=$isAllUpper, isTitleCase=$isTitleCase, capitalize=$capitalize")

			suspendCommit = true
			// C2: the committed text before the pulled word re-derives the NGB
			// context inside the replay (between its reset and the key presses).
			val precedingText = full2.substring(0, relStart)
			val forceSelectedCandidate = if (keyCount != null) {
				callbacks.replayWordPrefixInJtui(word, keyCount, capitalize, isAllUpper, precedingText)
				false
			} else {
				callbacks.replayWordInJtui(word, capitalize, isAllUpper, selectFirstOnMatch, suppressUIUpdate, precedingText)
			}
			callbacks.debugLog("[runPullInFlow 2d] JTUI replay complete (keyCount=$keyCount).")

			// === STEP 2.5 (C3): present the span group, longest text match selected ===
			val spanOutput = if (spanExtra > 0) callbacks.activateNgbSpan() else null
			if (spanExtra > 0 && spanOutput == null) ngbSpanOriginalText = null

			// === STEP 3: Let forceUpdateUi trigger the normal composing pipeline ===
			// Restore BEFORE forceUpdateUi — snapshot runs synchronously on Main.immediate and would skip applyEditorUpdate while suspended.
			suspendCommit = prevSuspend
			autoSpaceDecision = true
			autoSpaceInserted = false
			spacePossible = false
			callbacks.debugLog("[runPullInFlow 3a] suppressUIUpdate=$suppressUIUpdate for word '$word' suspendCommit=$suspendCommit selectKeyCount=${callbacks.getSelectKeyCount()})")
			if (!suppressUIUpdate) {
				if (spanOutput != null) {
					callbacks.forceUpdateUi(false, spanOutput, null)
				} else if (forceSelectedCandidate) {
					callbacks.forceUpdateUi(false, word, null)
				} else {
					callbacks.forceUpdateUi(false, null, word)
				}
			}
			callbacks.debugLog("[runPullInFlow 3b] pull-in: forceUpdateUi posted for word '$word' (suspendCommit=$suspendCommit prevSuspend=$prevSuspend selectKeyCount=${callbacks.getSelectKeyCount()})")
			haveComposing = true
		} catch (e: Exception) {
			callbacks.debugLog("[runPullInFlow X] EXCEPTION after STEP 1 delete: ${e.javaClass.simpleName}: ${e.message}")
			suspendCommit = prevSuspend // restore the caller's value, not force false
		}
		// Defer isPullInMode restoration
		val restoreMode = lastPullInModeFlag
		mainHandler.post {
			isPullInMode = restoreMode
			callbacks.debugLog("[runPullInFlow 3c] isPullInMode restored to $restoreMode (async)")
		}
		val ambLenAfter = callbacks.getAmbiguousSequenceLength()
		callbacks.debugLog("[runPullInFlow 5] EXIT SUCCESS: word='$word', ambLenAfter=$ambLenAfter")
		return true
	}

	// ── JTUI callback implementations ───────────────────────────────────

	fun onNumericOutput(text: String) {
		if (callbacks.phraseFlow?.consumeNumeric(text) == true) return
		val ic = getInputConnection() ?: return
		val saveIgnore = ignoreSelectionUpdate
		try {
			ignoreSelectionUpdate = true
			if (text.isEmpty()) {
				if (autoSpaceInserted) {
					removeLeadingAutospaceIfPresent(ic)
				}
				autoSpaceDecision = false
				autoSpaceInserted = false
				ic.finishComposingText()
				haveComposing = false
				lastComposingSent = null
			} else {
				if (text.length == 1 && !autospaceSuppressed) {
					val prevChar = ic.getTextBeforeCursor(1, 0)?.toString()?.firstOrNull()
					val prevPrevChar = ic.getTextBeforeCursor(2, 0)?.toString()?.getOrNull(0)
					if (shouldInsertLeadingSpace(prevChar, prevPrevChar)) {
						val curPos = getCursorOffset()
						beginAutospaceEdit()
						setIgnoreCursorRange(if (curPos < 2) 0 else curPos - 2, curPos + 2)
						ic.commitText(" ", 1)
						autoSpaceInserted = true
						autoSpaceInsertPos = curPos
						callbacks.debugLog("[onNumericOutput] Inserted leading autospace at pos=$curPos")
					}
					autoSpaceDecision = true
				}
				ic.setComposingText(text, 1)
				haveComposing = true
				lastComposingSent = text
			}
			lastImeEditMs = android.os.SystemClock.uptimeMillis()
		} finally {
			ignoreSelectionUpdate = saveIgnore
		}
	}

	fun onImmediateOutput(text: String) {
		if (callbacks.phraseFlow?.consumeImmediate(text) == true) return
		callbacks.debugLog("[onImmediateOutput] Outputting finalized text: '$text'")
		val ic = getInputConnection()
		if (ic != null) {
			val saveIgnore = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				ic.finishComposingText()
				ic.commitText(text, 1)
				lastImeEditMs = android.os.SystemClock.uptimeMillis()
			} finally {
				ignoreSelectionUpdate = saveIgnore
			}
			haveComposing = false
			lastComposingSent = null
			autoSpaceDecision = false
			autoSpaceInserted = false
			val lastChar = if (text.isNotEmpty()) text.last() else null
			spacePossible = shouldAllowAutospace(lastChar)
			callbacks.debugLog("[onImmediateOutput] Composing finalized, spacePossible=$spacePossible, autoSpace flags reset")
		}
		val pending = callbacks.reuseOrCreatePendingSelection(text, "X")
		callbacks.setPendingSelection(pending)
		callbacks.speakIfEnabled(pending)
		if (callbacks.isSpellingMode() || callbacks.isNumericMode()) {
			callbacks.recordSpellNumeric(text)
			if (text.any { it.isWhitespace() }) {
				callbacks.flushSpellNumericIfNeeded("whitespace")
			}
		}
		if (text.matches(Regex(".*[.!?]\\s+$"))) {
			callbacks.setShiftState(true, isManual = false, skipUpdate = false, autoReason = AutoCapReason.SENTENCE_START)
		}
	}

	fun onSpellingOutput(textParam: String) {
		if (callbacks.phraseFlow?.consumeSpelling(textParam) == true) return
		callbacks.debugLog("[onSpellingOutput] Updating current (composing) spelling text to '$textParam'")
		val ic = getInputConnection()
		if (ic != null) {
			val cursorBefore = getCursorOffset()
			val compLen = textParam.length
			val saveIgnore = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				if (cursorBefore >= 0) {
					setIgnoreCursorRange(cursorBefore, cursorBefore + compLen)
				} else {
					setIgnoreCursorRange(-1, -1)
				}
				ic.setComposingText(textParam, 1)
				lastImeEditMs = android.os.SystemClock.uptimeMillis()
			} finally {
				ignoreSelectionUpdate = saveIgnore
				setIgnoreCursorRange(-1, -1)
			}
			haveComposing = true
			lastComposingSent = textParam
			autoSpaceDecision = false
			autoSpaceInserted = false
			val lastChar = if (textParam.isNotEmpty()) textParam.last() else null
			spacePossible = shouldAllowAutospace(lastChar)
			callbacks.debugLog("[onSpellingOutput 2] Composing finalized, spacePossible=$spacePossible, autoSpace flags reset")
		}
	}

	fun onSpeakSentence(checkPrev: Boolean) {
		if (callbacks.phraseFlow != null) return
		val speakTheSentence = if (checkPrev) {
			try {
				val result = shouldSpeakSentence()
				callbacks.debugLog("[onSpeakSentence] checkPrev=true, shouldSpeakSentence=$result")
				result
			} catch (e: Exception) {
				callbacks.debugLog("[onSpeakSentence] Exception: ${e.message}")
				false
			}
		} else {
			true
		}
		if (speakTheSentence) {
			val saveEditMode = callbacks.isEditMode
			callbacks.isEditMode = true
			val sentence = extractLastSentenceFromEditor()
			callbacks.debugLog("[onSpeakSentence] Speaking sentence from editor: '${sentence?.take(80)}'")
			if (sentence != null && sentence.isNotBlank()) {
				callbacks.speakQueued(sentence)
				callbacks.rememberLastSpoken(sentence, "sentence")
			}
			callbacks.isEditMode = saveEditMode
		}
	}

	fun onSpeakNextSentence() {
		val saveSpeechState = callbacks.getSpeakState()
		val saveEditMode = callbacks.isEditMode
		callbacks.isEditMode = true
		callbacks.setSpeakState(false, false)
		val sentence = extractLastSentenceFromEditor(true)
		callbacks.debugLog("[onSpeakNextSentence] Speaking sentence from editor: '${sentence?.take(80)}'")
		if (sentence != null && sentence.isNotBlank()) {
			callbacks.speakQueued(sentence)
			callbacks.rememberLastSpoken(sentence, "sentence")
		}
		callbacks.setSpeakState(saveSpeechState, false)
		callbacks.isEditMode = saveEditMode
	}

	fun onFinalizeText(text: String) {
		if (callbacks.phraseFlow?.consumeImmediate(text) == true) return
		callbacks.debugLog("[onFinalizeText] Finalizing text: '$text' haveComposing=$haveComposing lastComposingSent=$lastComposingSent isPullInMode=$isPullInMode")
		val ic = getInputConnection()
		if (ic != null) {
			val saveIgnore = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				// Finalize THIS text: when the caller's word differs from what is
				// currently composed (paged pick vs the page-top preview), replace
				// the composing text before sealing — finishComposingText() alone
				// would commit the stale preview (device bug: picked Việt, got the
				// preview word).
				if (text.isNotEmpty() && haveComposing && lastComposingSent != null && lastComposingSent != text) {
					ic.setComposingText(text, 1)
				}
				ic.finishComposingText()
				lastImeEditMs = android.os.SystemClock.uptimeMillis()
			} finally {
				ignoreSelectionUpdate = saveIgnore
			}
			haveComposing = false
			lastComposingSent = null
			autoSpaceDecision = false
			autoSpaceInserted = false
			val lastChar = if (text.isNotEmpty()) text.last() else null
			spacePossible = shouldAllowAutospace(lastChar)
			callbacks.debugLog("[onFinalizeText] Composing finalized, spacePossible=$spacePossible, autoSpace flags reset")
		}
		if (text.isNotEmpty()) {
			val pending = callbacks.reuseOrCreatePendingSelection(text, "X")
			callbacks.setPendingSelection(pending)
			callbacks.speakIfEnabled(pending)
			if (callbacks.isSpellingMode() || callbacks.isNumericMode()) {
				callbacks.recordSpellNumeric(text)
				if (text.any { it.isWhitespace() }) {
					callbacks.flushSpellNumericIfNeeded("whitespace")
				}
			}
			if (text.matches(Regex(".*[.!?]\\s+$"))) {
				callbacks.setShiftState(true, isManual = false, skipUpdate = false, autoReason = AutoCapReason.SENTENCE_START)
			}
		}
	}

	fun onAmbiguousSequenceStart() {
		pendingTrailingSpace = false
		callbacks.debugLog("[onAmbiguousSequenceStart] Starting new ambiguous sequence")
		callbacks.getPendingSelection()?.let { pending ->
			if (callbacks.speakIfEnabled(pending)) {
				callbacks.cancelScheduledSpeak(clearPending = false)
				callbacks.setPendingSelection(pending)
			}
		}
		callbacks.cancelScheduledSpeak()
		callbacks.flushSpellNumericIfNeeded("ambig_start")
		val ic = getInputConnection()
		if (ic != null) {
			try {
				val charAfter = ic.getTextAfterCursor(1, 0)?.toString()?.firstOrNull()
				val nextChar = ic.getTextAfterCursor(2, 0)?.toString()?.getOrNull(1)
				if (charAfter != null && shouldInsertTrailingSpace(charAfter, nextChar)) {
					callbacks.debugLog("[onAmbiguousSequenceStart] Deferring trailing space before '$charAfter' until preview is non-empty")
					pendingTrailingSpace = true
				}
			} catch (e: Exception) {
				callbacks.debugLog("[onAmbiguousSequenceStart] Exception: ${e.message}")
			}
		}
	}

	fun onSpaceIfNeeded(afterPunct: Boolean) {
		callbacks.debugLog("[onSpaceIfNeeded] Checking if space needed after punctuation")
		val ic = getInputConnection() ?: return
		try {
			val needSpace: Boolean
			if (afterPunct) {
				val charAfter = ic.getTextAfterCursor(1, 0)?.toString()?.firstOrNull()
				val nextChar = ic.getTextAfterCursor(2, 0)?.toString()?.getOrNull(1)
				needSpace = when {
					charAfter == null -> {
						callbacks.debugLog("[onSpaceIfNeeded 1] At end of text, inserting space")
						true
					}
					charAfter == '\n' -> {
						callbacks.debugLog("[onSpaceIfNeeded 2] At end of line (newline), inserting space")
						true
					}
					shouldInsertTrailingSpace(charAfter, nextChar) -> {
						callbacks.debugLog("[onSpaceIfNeeded 3] Following char '$charAfter' requires space")
						true
					}
					else -> false
				}
			} else {
				val prevChar = ic.getTextBeforeCursor(1, 0)?.toString()?.firstOrNull()
				val prevPrevChar = ic.getTextBeforeCursor(2, 0)?.toString()?.getOrNull(0)
				needSpace = shouldInsertLeadingSpace(prevChar, prevPrevChar)
			}
			if (needSpace) {
				callbacks.debugLog("[onSpaceIfNeeded 4] Inserting space after punctuation")
				val saveIgnore = ignoreSelectionUpdate
				try {
					ignoreSelectionUpdate = true
					ic.commitText(" ", 1)
				} finally {
					ignoreSelectionUpdate = saveIgnore
				}
			}
		} catch (e: Exception) {
			callbacks.debugLog("[onSpaceIfNeeded 7] Exception: ${e.message}")
		}
	}

	fun onUndoPressed(context: org.continuouspath.justtype.logic.UndoContext) {
		if (callbacks.phraseFlow?.handleBackspace() == true) return
		callbacks.debugLog("[onUndoPressed 1] Context: ambigBefore=${context.ambigSeqLenBefore}, currentSelection=${context.currentSelection}, hadComposing=${context.hadComposingBefore}, willHaveComposing=${context.willHaveComposingAfter}, listFunctionCount=${context.listFunctionCount}")

		val ambigSeqLen = context.ambigSeqLenBefore
		val currentSelection = context.currentSelection
		val hasComposing = haveComposing
		val listFunctionCount = context.listFunctionCount
		val willHaveComposing = context.willHaveComposingAfter
		val selectCount = (currentSelection ?: -1) + 1

		callbacks.debugLog("[onUndoPressed 2] Variables: ambigSeqLen=$ambigSeqLen, selectCount=$selectCount, hasComposing=$hasComposing, listFunctionCount=$listFunctionCount, willHaveComposing=$willHaveComposing")

		val prevSuspend = suspendCommit
		suspendCommit = true

		if ((willHaveComposing || (!hasComposing && !willHaveComposing)) && ((ambigSeqLen > 1) || (selectCount > 0))) {
			callbacks.debugLog("[onUndoPressed] Context 1: Will have composing after UnDo - UI will be refreshed automatically")
		} else if (hasComposing &&
			((ambigSeqLen > 1) || ((ambigSeqLen == 1) && (selectCount == (listFunctionCount + 1)))) &&
			!willHaveComposing
		) {
			callbacks.debugLog("[onUndoPressed] Context 23: Removing composing text, NOT checking for autospace")
			val ic = getInputConnection()
			if (ic != null) {
				val saveIgnore = ignoreSelectionUpdate
				try {
					ignoreSelectionUpdate = true
					ic.setComposingText("", 1)
					// Suppress the async onUpdateSelection that fires after the
					// composing region collapses. Without this, processSelectionChange's
					// branch 4aa would wipe JTUI's pending ambig sequence and delete
					// the leading autospace, derailing UnDo #3 into Context 8.
					suppressNextSelectionUpdate = true
					callbacks.debugLog("[onUndoPressed] Context 23: Composing text removed (autospace NOT deleted)")
					haveComposing = false
					lastComposingSent = null
				} finally {
					ignoreSelectionUpdate = saveIgnore
				}
			}
		} else if (hasComposing && (ambigSeqLen == 1)) {
			callbacks.debugLog("[onUndoPressed] Context 5: Removing composing text only (no pull-in)")
			val ic = getInputConnection()
			if (ic != null) {
				val saveIgnore = ignoreSelectionUpdate
				try {
					ignoreSelectionUpdate = true
					ic.setComposingText("", 1)
					// Same async-gap suppression as Context 23 — see comment there.
					suppressNextSelectionUpdate = true
					callbacks.debugLog("[onUndoPressed] Context 5: Composing text removed (no autospace deletion)")
					haveComposing = false
					lastComposingSent = null
				} finally {
					ignoreSelectionUpdate = saveIgnore
				}
			}
		} else if (!hasComposing && (ambigSeqLen == 1)) {
			// Context 7 fires when the user UnDoes the first (and only) keystroke
			// of a new word, AND that key is one of the list-function keys (2/4/7)
			// where the initial keypress shows only list-function alternates with
			// no text candidate — hence !hasComposing. Other keys would have set
			// up composing text and hit Context 5 instead.
			//
			// We DO call resetJTUI here (full state cleanup) but with
			// preserveAutospace=true so that the leading autospace remains in
			// the text field — it belongs to the now-finalized PRIOR word and
			// should be deleted by the NEXT UnDo press (Context 8, which runs
			// deleteLeftChar + optional pull-in if Auto-Load is ON).
			//
			// preserveAutospace swaps the internal onNumericOutput("") call
			// (which removes the autospace via removeLeadingAutospaceIfPresent)
			// for onFinalizeText("") (which only finalizes composing). All
			// other resetJTUI cleanup — state.outputString, selectionList,
			// currentSelection, undoStack, etc. — still runs, which is needed
			// for subsequent tap-to-pull-in to work correctly.
			callbacks.debugLog("[onUndoPressed] Context 7: resetJTUI (preserveAutospace=true) for list-function key first-press case")
			val shouldShift = try {
				computeAutoShift()
			} catch (_: Exception) {
				false
			}
			callbacks.resetJTUI(shouldShift, true, autoCapReason = lastAutoCapReason, preserveAutospace = true)
			haveComposing = false
		} else if (ambigSeqLen == 0) {
			handleUndoWithEmptySequence(hasComposing)
		} else {
			callbacks.debugLog("[onUndoPressed] Context 10: UNANTICIPATED CONTEXT!")
			callbacks.debugLog("[onUndoPressed] Variables: ambigSeqLen=$ambigSeqLen, hasComposing=$hasComposing, listFunctionCount=$listFunctionCount, willHaveComposing=$willHaveComposing")
			callbacks.errorNotification()
		}
		suspendCommit = prevSuspend
	}

	/** UnDo Context 8: empty key buffer — cursor-relative deletion with the
	 *  zombie-region guard and pull-in-first rule (extracted for complexity). */
	private fun handleUndoWithEmptySequence(hasComposing: Boolean) {
		callbacks.debugLog("[onUndoPressed] Context 8: Ambiguous sequence empty, processing deletion")
		if (hasComposing) {
			callbacks.debugLog("[onUndoPressed] Context 8: ERROR - hasComposing is true but ambigSeqLen is 0!")
			callbacks.errorNotification()
		}
		val autoRestore = callbacks.getAutoRestore()
		val savePullInMode = isPullInMode
		isPullInMode = true
		val saveIgnore = ignoreSelectionUpdate
		ignoreSelectionUpdate = true
		try {
			// Zombie-region guard (Cliff's "Bigimprovements", 2026-08-14):
			// editor churn can re-mark the finalized pick as composing while
			// the controller believes it sealed (hasComposing false, so the
			// tripwire above stays quiet). deleteSurroundingText SKIPS a
			// composing region — each UnDo would eat the character BEFORE
			// the word (the autospace, then the previous word's letters).
			// Seal first; idempotent when no region exists.
			if (!hasComposing) getInputConnection()?.finishComposingText()
			// Cursor adjacent to an INACTIVE word (Cliff, 2026-08-14):
			// UnDo pulls the whole object back in BEFORE any deletion —
			// the next UnDo then edits its keystrokes. The old
			// delete-first order corrupted the word (last char gone, so
			// the pull-in of a nonword failed and the cascade kept
			// deleting until some shorter prefix matched the database).
			// A cursor after a SPACE falls through unchanged: the space
			// is deleted, then the preceding object pulls in below.
			if (autoRestore &&
				canPullInAtCursor(checkRightContext = false) == 1 &&
				runPullInFlow(detectedWord, detectedStart, detectedEnd, selectFirstOnMatch = false, suppressUIUpdate = false)
			) {
				callbacks.debugLog("[onUndoPressed] Context 8: cursor touched inactive word — pulled in WHOLE, no deletion")
				haveComposing = true
				callbacks.setSkipPostProcessingAfterPullIn(true)
				return
			}
			val newCursorPos = deleteLeftChar(false)
			if (newCursorPos == -1) {
				callbacks.debugLog("[onUndoPressed] Context 8/9: Cursor at start, calling errorNotification")
				callbacks.errorNotification()
				return
			}
			if (!autoRestore) {
				callbacks.debugLog("[onUndoPressed] Context 8: Deleted char only (autoRestore=OFF, no pull-in)")
				return
			}
			callbacks.debugLog("[onUndoPressed] Context 8: Deleted char, attempting pull-in (autoRestore=ON)")
			if (canPullInAtCursor(checkRightContext = false) != 1) {
				callbacks.debugLog("[onUndoPressed] Context 8: canPullInAtCursor() returns FALSE after deletion")
				return
			}
			val res = runPullInFlow(detectedWord, detectedStart, detectedEnd, selectFirstOnMatch = false, suppressUIUpdate = false)
			if (res) {
				callbacks.debugLog("[onUndoPressed] Context 8: Pull-in SUCCESS — skipping post-processing")
				haveComposing = true
				callbacks.setSkipPostProcessingAfterPullIn(true)
			} else {
				callbacks.debugLog("[onUndoPressed] Context 8: Pull-in FAILED")
			}
		} finally {
			ignoreSelectionUpdate = saveIgnore
			mainHandler.post {
				isPullInMode = savePullInMode
				callbacks.debugLog("[onUndoPressed] Context 8: isPullInMode restored to $savePullInMode (async)")
			}
		}
	}

	// ── Complex orchestrators ───────────────────────────��────────────────

	/**
	 * Handles the text-editing portion of onUpdateSelection.
	 * The IME override calls super.onUpdateSelection() and handles UI concerns (overlay, requestShowSelf),
	 * then delegates here for text-editing logic.
	 *
	 * @return true if the caller should return early (handled), false to continue
	 */
	fun handleSelectionUpdate(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
		callbacks.debugLog(
			"[onUpdateSelection 0a-entry] ENTRY: " +
				"oldSel=$oldSelStart..$oldSelEnd newSel=$newSelStart..$newSelEnd " +
				"cand=$candidatesStart..$candidatesEnd  state: " +
				"isPullInMode=$isPullInMode isEditMode=${callbacks.isEditMode} " +
				"ignoreSelectionUpdate=$ignoreSelectionUpdate " +
				"suppressNextSelectionUpdate=$suppressNextSelectionUpdate " +
				"lastSel=$lastSelStart..$lastSelEnd " +
				"ignoreCursorRange=$ignoreCursorStart..$ignoreCursorEnd " +
				"haveComposing=$haveComposing pull-in flow context follows",
		)
		if (isPullInMode || callbacks.isEditMode) {
			callbacks.debugLog(
				"[onUpdateSelection 0b] Skip:  " +
					"isPullInMode=$isPullInMode  isEditMode=${callbacks.isEditMode}    " +
					"selection $newSelStart..$newSelEnd    " +
					"ignore-range $ignoreCursorStart..$ignoreCursorEnd; skipping pull-in",
			)
			return
		} else if (resetJTUI) {
			callbacks.debugLog("[onUpdateSelection 0c]  Reset JTUI state - resetJTUI flag is set, callUpdate = false")
			updateShiftFromCursor(true)
			callbacks.resetJTUI(callbacks.getShiftState(), false, autoCapReason = callbacks.getAutoCapReason())
			resetJTUI = false
			haveComposing = false
		}
		if (ignoreSelectionUpdate) {
			callbacks.debugLog("[onUpdateSelection 0d] Skip:  ignoreSelectionUpdate=TRUE     selection $newSelStart..$newSelEnd    ignore-range $ignoreCursorStart..$ignoreCursorEnd; skipping")
			return
		}
		if (suppressNextSelectionUpdate) {
			suppressNextSelectionUpdate = false
			lastSelStart = newSelStart
			lastSelEnd = newSelEnd
			updateShiftFromCursor()
			callbacks.debugLog("[onUpdateSelection 0d2] Skip:  suppressNextSelectionUpdate=TRUE (one-shot); new lastSel=$newSelStart..$newSelEnd; skipping")
			return
		}
		if (newSelStart == lastSelStart && newSelEnd == lastSelEnd) {
			callbacks.debugLog("[onUpdateSelection 0e] Skip:       New selection $newSelStart..$newSelEnd  ==   Last selection $lastSelStart..$lastSelEnd; skipping")
			return
		}
		// If selection change is within our ignore range, treat it as internal and skip handling
		if (ignoreCursorStart >= 0 && newSelStart >= ignoreCursorStart && newSelEnd <= ignoreCursorEnd) {
			lastSelStart = newSelStart
			lastSelEnd = newSelEnd
			updateShiftFromCursor()
			callbacks.debugLog("[onUpdateSelection 1] Skip:  Ignore-range:   selection $newSelStart..$newSelEnd inside $ignoreCursorStart..$ignoreCursorEnd; skipping")
			return
		}

		// If pull-in was blocked at onStartInput (InputConnection not ready), retry now
		if (attemptPullInOnFirstUpdate) {
			attemptPullInOnFirstUpdate = false
			val autoRestore = callbacks.getAutoRestore()
			if (autoRestore) {
				callbacks.debugLog("[onUpdateSelection 1a] Retrying pull-in (deferred from onStartInput) at cursor $newSelStart..$newSelEnd")
				val pullInResult = tryImmediatePullInAtCurrentCursor(selectFirstOnMatch = true)
				if (pullInResult > 0) {
					updateShiftFromCursor()
					callbacks.debugLog("[onUpdateSelection 1b] Pull-in SUCCESS - word pulled in and selected")
					haveComposing = true
					lastSelStart = newSelStart
					lastSelEnd = newSelEnd
					return
				} else {
					callbacks.debugLog("[onUpdateSelection 1c] Pull-in FAILED (result=$pullInResult) - continuing with normal flow")
					reconstructNgbContextAtCursor()
				}
			} else {
				callbacks.debugLog("[onUpdateSelection 1a] Skipping deferred pull-in (auto-restore disabled)")
				reconstructNgbContextAtCursor()
			}
		}

		// Process the selection change
		callbacks.debugLog("[onUpdateSelection 2] Calling processSelectionChange():   oldSel: $oldSelStart..$oldSelEnd  newSel: $newSelStart..$newSelEnd  candidate: $candidatesStart..$candidatesEnd ")
		processSelectionChange(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
	}

	private fun processSelectionChange(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
		callbacks.debugLog("[processSelectionChange 2a] ENTERING   Params:  Ignore-range:$ignoreCursorStart..$ignoreCursorEnd   oldSelStart $oldSelStart   oldSelEnd $oldSelEnd   newSelStart $newSelStart   newSelEnd $newSelEnd  candidatesStart $candidatesStart   candidatesEnd $candidatesEnd  ")
		callbacks.debugLog("[processSelectionChange 2a1] State: haveComposing=$haveComposing  lastPullInWord='$lastPullInWord'  lastPullInEnd=$lastPullInEnd  msSincePullIn=${android.os.SystemClock.uptimeMillis() - lastPullInTs}")

		val editModePage = callbacks.getCurrentPage()
		if (editModePage.startsWith("editMode")) {
			callbacks.debugLog("[processSelectionChange 2a-editMode] On edit mode page '$editModePage', skipping pull-in/reset")
			lastSelStart = newSelStart
			lastSelEnd = newSelEnd
			return
		}

		val ic = getInputConnection() ?: run {
			callbacks.debugLog("[processSelectionChange 2a-noIC] No InputConnection — skipping pull-in")
			return
		}
		if ((newSelStart == oldSelStart) && (newSelEnd == oldSelEnd)) {
			callbacks.debugLog("[processSelectionChange 2a-noChange] oldSel==newSel ($oldSelStart..$oldSelEnd) — skipping pull-in (no-op cursor update)")
			return
		}
		val composingActive = (candidatesStart >= 0 && candidatesEnd > candidatesStart)
		if (composingActive) {
			val outsideComposing = (newSelStart < candidatesStart || newSelEnd > candidatesEnd)

			if (outsideComposing) {
				callbacks.debugLog("[processSelectionChange 2b] External relocation detected: selection $newSelStart..$newSelEnd outside composing $candidatesStart..$candidatesEnd")
				val saveIgnore = ignoreSelectionUpdate
				try {
					ignoreSelectionUpdate = true
					getInputConnection()?.finishComposingText()
				} catch (e: Exception) {
					ExceptionReporter.reportSilent("ImeTextController:processSelectionChange:2b-finishComposing", e)
				} finally {
					ignoreSelectionUpdate = saveIgnore
				}
				haveComposing = false
				autoSpaceInserted = false
				pendingTrailingSpace = false

				callbacks.clearManualShift()
				callbacks.debugLog("[processSelectionChange 2c]  calling resetJTUI() -  callUpdate = false")
				val shouldShift = try {
					computeAutoShift()
				} catch (_: Exception) {
					false
				}
				callbacks.resetJTUI(shouldShift, false, resetToStartPage = true, autoCapReason = lastAutoCapReason)
				val autoRestore = callbacks.getAutoRestore()
				if (autoRestore) {
					callbacks.debugLog("[processSelectionChange 2d] Stabilize:  Calling tryImmediatePullInAtCurrentCursor() after external relocation")
					val pullInResult2d = tryImmediatePullInAtCurrentCursor(selectFirstOnMatch = true)
					callbacks.debugLog("[processSelectionChange 2d-result] pull-in result from 2d = $pullInResult2d")
					if (pullInResult2d > 0) {
						setIgnoreCursorRange(lastPullInStart, lastPullInEnd)
						callbacks.forceUpdateUi()
						haveComposing = true
						setIgnoreCursorRange(-1, -1)
					} else {
						callbacks.debugLog("[processSelectionChange 2d2]  calling resetJTUI() -  callUpdate = true")
						setIgnoreCursorRange(-1, -1)
						callbacks.resetJTUI(shouldShift, true, autoCapReason = lastAutoCapReason)
						resetJTUI = false
						haveComposing = false
						// Reset wiped the NGB context; the text before the cursor
						// still carries it (". "/newline = BOS, else the prev word).
						reconstructNgbContextAtCursor()
					}
				} else {
					callbacks.debugLog("[processSelectionChange 2d-noauto] Auto-restore disabled, skipping pull-in after external relocation")
					callbacks.resetJTUI(shouldShift, true, autoCapReason = lastAutoCapReason)
					resetJTUI = false
					haveComposing = false
					reconstructNgbContextAtCursor()
				}
			} else {
				callbacks.debugLog("[processSelectionChange 2e] Selection $newSelStart..$newSelEnd within composing $candidatesStart..$candidatesEnd - ignoring")
				val isScreenTap = (newSelStart == newSelEnd)
				if (isScreenTap) {
					ic.setSelection(candidatesEnd, candidatesEnd)
					ic.setComposingRegion(candidatesStart, candidatesEnd)
					autoSpaceDecision = true
				}
			}
			return
		}
		lastSelStart = newSelStart
		lastSelEnd = newSelEnd

		if (haveComposing) {
			val msSincePullIn = android.os.SystemClock.uptimeMillis() - lastPullInTs
			if (msSincePullIn < 300) {
				callbacks.debugLog("[processSelectionChange 2d-guard] Skip: haveComposing=true but candidatesStart=$candidatesStart within pull-in cooldown (${msSincePullIn}ms) — trusting deferred composing pipeline")
				lastSelStart = newSelStart
				lastSelEnd = newSelEnd
				return
			}
			callbacks.debugLog("[processSelectionChange 2d] haveComposing=true but composing region cleared externally (candidatesStart=$candidatesStart) — resetting state")
			haveComposing = false
			lastComposingSent = null
			autoSpaceInserted = false
			pendingTrailingSpace = false
			callbacks.clearManualShift()
			val shouldShift = try {
				computeAutoShift()
			} catch (_: Exception) {
				false
			}
			val autoRestore = callbacks.getAutoRestore()
			if (autoRestore) {
				callbacks.debugLog("[processSelectionChange 2d-haveComp] Stabilize: Calling tryImmediatePullInAtCurrentCursor() after composing cleared externally")
				callbacks.resetJTUI(shouldShift, false, autoCapReason = lastAutoCapReason)
				val pullInResultHC = tryImmediatePullInAtCurrentCursor(selectFirstOnMatch = true)
				callbacks.debugLog("[processSelectionChange 2d-haveComp-result] pull-in result = $pullInResultHC")
				if (pullInResultHC > 0) {
					setIgnoreCursorRange(lastPullInStart, lastPullInEnd)
					callbacks.forceUpdateUi()
					haveComposing = true
					setIgnoreCursorRange(-1, -1)
				} else {
					callbacks.resetJTUI(shouldShift, true, autoCapReason = lastAutoCapReason)
					reconstructNgbContextAtCursor()
				}
			} else {
				callbacks.debugLog("[processSelectionChange 2d-noauto] Auto-restore disabled after external composing clear — resetting JTUI")
				callbacks.resetJTUI(shouldShift, true, autoCapReason = lastAutoCapReason)
				reconstructNgbContextAtCursor()
			}
			return
		}

		// Stabilize JTUI core: if there is a pending ambiguous sequence with no Select yet,
		// apply one Select silently BUT do not commit it at the new cursor position.
		try {
			if (callbacks.hasPendingAmbiguityWithoutSelect()) {
				callbacks.debugLog("[processSelectionChange 3] Stabilize - Apply one Select to finalize pending ambiguous sequence without committing at new cursor")
				getInputConnection()?.finishComposingText()
			}
		} catch (e: Exception) {
			ExceptionReporter.reportSilent("ImeTextController:processSelectionChange:3-stabilize", e)
		}
		updateShiftFromCursor()

		// Skip pull-in logic on pages where it should not trigger automatically
		val currentPage = callbacks.getCurrentPage()
		val skipPullInPages = setOf(
			"Spelling", "SpellingAlpha",
			"Spell0", "Spell2", "Spell3", "Spell4", "Spell5", "Spell7",
			"SpellAlpha0", "SpellAlpha2", "SpellAlpha3", "SpellAlpha4", "SpellAlpha5", "SpellAlpha7",
		)
		if (currentPage in skipPullInPages) {
			callbacks.debugLog("[processSelectionChange 4skip] Skipping pull-in: on spelling page (currentPage='$currentPage')")
			return
		}

		val autoRestore = callbacks.getAutoRestore()
		if (!autoRestore) {
			callbacks.debugLog("[processSelectionChange 4skip-noauto] Skipping pull-in: auto-restore disabled")
			updateShiftFromCursor(true)
			callbacks.resetJTUI(callbacks.getShiftState(), false, callbacks.getIsManualShift(), autoCapReason = callbacks.getAutoCapReason())
			reconstructNgbContextAtCursor()
			return
		}

		// Quick pre-check: is cursor touching any word character to the LEFT?
		val charBeforeCursor = ic.getTextBeforeCursor(1, 0)?.toString()?.lastOrNull()
		val touchesWord = charBeforeCursor != null && (callbacks.isAlphaChar(charBeforeCursor) || callbacks.isWordDbChar(charBeforeCursor))
		val isScreenTap = ((newSelStart >= 0) && (newSelStart == newSelEnd))
		if (isScreenTap && !touchesWord) {
			if ((newSelStart - oldSelEnd) == callbacks.getImmedCharCount()) {
				callbacks.debugLog("[processSelectionChange 4a]  Detected Immediate char output action - ignoring")
				callbacks.clearImmedCharCount()
				return
			}
			callbacks.debugLog("[processSelectionChange 4aa] New cursor does not touch word - calling resetJTUI() -  callUpdate = false")
			updateShiftFromCursor(true)
			callbacks.resetJTUI(
				callbacks.getShiftState(),
				false,
				callbacks.getIsManualShift(),
				autoCapReason = callbacks.getAutoCapReason(),
			)
			// The cursor not touching a word is exactly where the preceding
			// text carries the context (". "/newline = BOS) — this was the
			// post-punctuation orphan reset killing the in-flow BOS.
			reconstructNgbContextAtCursor()
			return
		}

		// Cancel any prior pending pull-in tasks
		try {
			pendingPullIn?.let { mainHandler.removeCallbacks(it) }
		} catch (e: Exception) {
			ExceptionReporter.reportSilent("ImeTextController:processSelectionChange:4-cancelPending", e)
		}
		pendingPullIn = null
		pullInToken++

		// Use tryImmediatePullInAtCurrentCursor for full word detection and pull-in.
		callbacks.debugLog("[processSelectionChange 4b]  Calling tryImmediatePullInAtCurrentCursor() at newSel=$newSelStart..$newSelEnd charBefore='$charBeforeCursor' touchesWord=$touchesWord")
		val pullInResult = tryImmediatePullInAtCurrentCursor(selectFirstOnMatch = true)
		callbacks.debugLog("[processSelectionChange 4b-result] pull-in result = $pullInResult (1=success, 0=no producible word, -1=blocked / not initialized / suppressed / haveComposing)")
		if (pullInResult <= 0) {
			if (pullInResult == 0) {
				callbacks.debugLog("[processSelectionChange 4c2] No producible word found - calling resetJTUI() -  callUpdate = false")
			} else {
				callbacks.debugLog("[processSelectionChange 4c3] Pull-in BLOCKED (result=$pullInResult) — see preceding tryImmediatePullInAtCurrentCursor log for reason")
			}
			updateShiftFromCursor(true)
			callbacks.resetJTUI(
				callbacks.getShiftState(),
				false,
				callbacks.getIsManualShift(),
				autoCapReason = callbacks.getAutoCapReason(),
			)
			resetJTUI = false
			haveComposing = false
			reconstructNgbContextAtCursor()
			return
		}
		lastImeEditMs = android.os.SystemClock.uptimeMillis()
	}

	fun applyEditorUpdate(topCandidate: String?) {
		maybeResetAutospaceIgnore()
		val ic = getInputConnection() ?: return

		// Additional finalization check: if there's previous composing text but no new preview
		if (!lastComposingSent.isNullOrEmpty() && topCandidate.isNullOrEmpty()) {
			callbacks.debugLog("[applyEditorUpdate 0b] Finalizing previous composing text '$lastComposingSent' before starting new sequence with no preview")
			try {
				ic.finishComposingText()
				haveComposing = false
				lastComposingSent = null
				callbacks.debugLog("[applyEditorUpdate 0c] Previous composing text finalized")
			} catch (e: Exception) {
				ExceptionReporter.reportSilent("ImeTextController:applyEditorUpdate:0c-finalize", e)
			}
		}

		// Zombie-region guard (Cliff, 2026-08-14): about to start a FRESH
		// composing region while the controller believes nothing is composing
		// — if editor churn re-marked the last finalized word, setComposingText
		// would REPLACE it. Seal whatever the editor holds first; idempotent.
		if (!haveComposing && !topCandidate.isNullOrEmpty()) {
			try {
				ic.finishComposingText()
			} catch (e: Exception) {
				ExceptionReporter.reportSilent("ImeTextController:applyEditorUpdate:zombie-seal", e)
			}
		}

		val preview = topCandidate ?: ""
		val samePreviewOrNull = (preview.contentEquals(lastPreview.toString()) || preview.isEmpty())
		val composingActive = preview.isNotEmpty()
		val cursorBefore = getCursorOffset()
		callbacks.debugLog("[applyEditorUpdate 0a1] autospace/seq:   preview='$preview'   lastPreview='$lastPreview'   cursorBefore=$cursorBefore")
		val ambLen = try {
			callbacks.getAmbiguousSequenceLength()
		} catch (_: Exception) {
			0
		}
		getCurrentSelection()

		// When focusing a new field, sync our base to the editor without committing previous text
		if (callbacks.isNewInputSession && resetJTUI && !composingActive) {
			callbacks.debugLog("[applyEditorUpdate 3a] New Input Session:  preview text=\"$preview\"")
			ic.finishComposingText()
			updateShiftFromCursor(true)
			callbacks.debugLog("[applyEditorUpdate 3a2] Calling resetJTUI()  -  callUpdate = true")
			callbacks.resetJTUI(callbacks.getShiftState(), true, autoCapReason = callbacks.getAutoCapReason())
			callbacks.isNewInputSession = false
			resetJTUI = false
			haveComposing = false
			if (samePreviewOrNull) {
				callbacks.debugLog("[applyEditorUpdate 3b] New Input Session, but no new preview:  preview text=\"$preview\"")
				return
			}
		}

		val saveLastPreview = lastPreview
		lastPreview = if (lastComposingSent.isNullOrEmpty()) "" else lastComposingSent.toString()
		callbacks.debugLog("[applyEditorUpdate 9] lastPreview UPDATED from '$saveLastPreview' TO '$lastPreview'  lastComposingSent='$lastComposingSent'  isPullInMode=$isPullInMode  autoSpaceDecision=$autoSpaceDecision  autoSpaceInserted=$autoSpaceInserted")
		if (preview.isNotEmpty() && preview != lastPreview) {
			callbacks.debugLog("[applyEditorUpdate 10] NEW PREVIEW path: preview='$preview'  lastPreview='$lastPreview'  (MISMATCH — will process auto-space logic)")
			try {
				// Insert deferred trailing space now that we have a real preview
				if (pendingTrailingSpace) {
					pendingTrailingSpace = false
					callbacks.debugLog("[applyEditorUpdate] Inserting deferred trailing space before following word")
					val saveIgnore = ignoreSelectionUpdate
					try {
						ignoreSelectionUpdate = true
						val curPos = getCursorOffset()
						setIgnoreCursorRange(if (curPos < 2) 0 else curPos - 2, curPos + 2)
						ic.commitText(" ", 1)
						val newPos = getCursorOffset()
						ic.setSelection(newPos - 1, newPos - 1)
					} finally {
						ignoreSelectionUpdate = saveIgnore
					}
				}
				val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
				if (et != null) {
					val selStart = et.selectionStart.coerceAtLeast(0)
					val compLen = preview.length
					val full = et.text?.toString()
					val prevChar = if (selStart > 0 && full != null && selStart - 1 < full.length) full[selStart - 1] else null
					val prevPrevChar = if (selStart > 1 && full != null && selStart - 2 < full.length) full[selStart - 2] else null
					callbacks.debugLog("[applyEditorUpdate 12] Params: len=$compLen autoSpaceDecision=$autoSpaceDecision selStart=$selStart prevChar='${prevChar ?: '∅'}' prevPrevChar='${prevPrevChar ?: '∅'}'")
					val firstNew = preview.firstOrNull()
					val suppressLeadingSpace = shouldSuppressLeadingAutospace(preview)
					val wantsAutospace =
						!suppressLeadingSpace &&
							!isPullInMode &&
							(firstNew != null && callbacks.isAlphaChar(firstNew)) &&
							shouldInsertLeadingSpace(prevChar, prevPrevChar)
					if (suppressLeadingSpace != lastPreviewSuppressLeadingSpace) {
						clearComposingPreviewIfNeeded(ic)
						if (autoSpaceInserted && !wantsAutospace) {
							removeLeadingAutospaceIfPresent(ic)
						} else if (!autoSpaceInserted && wantsAutospace) {
							beginAutospaceEdit()
							setIgnoreCursorRange(selStart, selStart + 1)
							val saveIgnore = ignoreSelectionUpdate
							try {
								ignoreSelectionUpdate = true
								ic.commitText(" ", 1)
							} finally {
								ignoreSelectionUpdate = saveIgnore
							}
							lastImeEditMs = android.os.SystemClock.uptimeMillis()
							autoSpaceInserted = true
							autoSpaceInsertPos = selStart
						}
						callbacks.debugLog("[applyEditorUpdate] leading autospace suppression=$suppressLeadingSpace preview='$preview'")
						autoSpaceDecision = true
					}
					var need = false
					if (suppressLeadingSpace) {
						autoSpaceDecision = true
						need = false
					} else if (!autoSpaceDecision) {
						need = !autoSpaceInserted && !isPullInMode && shouldInsertLeadingSpace(prevChar, prevPrevChar) && (firstNew != null && callbacks.isAlphaChar(firstNew))
						autoSpaceDecision = true
					}
					callbacks.debugLog("[applyEditorUpdate 13] first/non-first preview: selStart=$selStart prevChar='${prevChar ?: '∅'}' cursorBefore=$cursorBeforeInsertion needSpace=$need decided=$autoSpaceDecision autoSpaceInserted=$autoSpaceInserted")
					if (need) {
						callbacks.debugLog("[applyEditorUpdate 13b] isPullInMode=$isPullInMode prevChar='${prevChar ?: '∅'}' needSpace=$need autoSpaceInserted=$autoSpaceInserted")
						setIgnoreCursorRange(selStart, selStart + 1)
						callbacks.debugLog("[applyEditorUpdate 13c] commitText():  ignoreCursorStart=$ignoreCursorStart  ignoreCursorEnd=$ignoreCursorEnd   commit=' '  (autospace)")
						beginAutospaceEdit()
						val saveIgnore = ignoreSelectionUpdate
						try {
							ignoreSelectionUpdate = true
							ic.commitText(" ", 1)
						} finally {
							ignoreSelectionUpdate = saveIgnore
						}
						lastImeEditMs = android.os.SystemClock.uptimeMillis()
						autoSpaceInserted = true
						autoSpaceInsertPos = selStart
						callbacks.debugLog("[applyEditorUpdate 14] Inserted Auto-space at preview=$preview; Amb seqLen=$ambLen")
					} else {
						autoSpaceInsertionDelayed = (ambLen == 1 && firstNew == null)
						if (autoSpaceInsertionDelayed) {
							callbacks.debugLog("[applyEditorUpdate 15a] DELAYED Auto-space at preview= \"$preview\" Amb seqLen=$ambLen")
						} else {
							callbacks.debugLog("[applyEditorUpdate 15b] Auto-space NOT NEEDED at preview= \"$preview\" Amb seqLen=$ambLen")
						}
					}
					val compStart = if (autoSpaceInserted) selStart + 1 else selStart
					setIgnoreCursorRange(compStart, compStart + compLen)
				}
			} catch (e: Exception) {
				ExceptionReporter.reportSilent("ImeTextController:applyEditorUpdate:2-setIgnore", e)
			}

			// Now insert composing preview (if allowed in this editor)
			if (allowComposing) {
				callbacks.debugLog("[applyEditorUpdate 15e] setComposingText():  ignoreCursorStart=$ignoreCursorStart  ignoreCursorEnd=$ignoreCursorEnd   preview='$preview'")
				val saveIgnore = ignoreSelectionUpdate
				try {
					ignoreSelectionUpdate = true
					ic.setComposingText(preview, 1)
				} finally {
					ignoreSelectionUpdate = saveIgnore
				}
				haveComposing = true
				lastComposingSent = preview
			} else {
				callbacks.debugLog("[applyEditorUpdate 15f]  allowComposing=FALSE:   Skipped setComposingText: composing not allowed for this editor")
			}
			lastImeEditMs = android.os.SystemClock.uptimeMillis()
		} else if (preview.isEmpty()) {
			handleEmptyPreview(ic, cursorBefore)
		} else {
			// preview unchanged: keep composing; no-op
			callbacks.debugLog("[applyEditorUpdate 18b]  No-op: preview unchanged; confirm that composing text is set to preview=$preview")
			if (allowComposing) {
				callbacks.debugLog("[applyEditorUpdate 18d] setComposingText():  ignoreCursorStart=$ignoreCursorStart  ignoreCursorEnd=$ignoreCursorEnd   preview='$preview'")
				val saveIgnore = ignoreSelectionUpdate
				try {
					ignoreSelectionUpdate = true
					ic.setComposingText(preview, 1)
				} finally {
					ignoreSelectionUpdate = saveIgnore
				}
				haveComposing = true
				lastComposingSent = preview
			} else {
				callbacks.debugLog("[applyEditorUpdate 18e]  allowComposing=FALSE:   Skipped setComposingText: composing not allowed for this editor")
			}
			lastImeEditMs = android.os.SystemClock.uptimeMillis()
		}
		lastPreview = preview.toString()
		lastPreviewSuppressLeadingSpace = shouldSuppressLeadingAutospace(preview)
		val cursorAfter = getCursorOffset()
		callbacks.debugLog("[applyEditorUpdate 19] Cursor moved from $cursorBefore to $cursorAfter")
		if (!autospaceIgnoreResetPending) {
			setIgnoreCursorRange(-1, -1)
		}
	}

	private fun handleEmptyPreview(ic: InputConnection, cursorBefore: Int) {
		if (haveComposing) {
			callbacks.debugLog(
				"[applyEditorUpdate 16] setComposingText():  " +
					"ignoreCursorStart=$ignoreCursorStart  ignoreCursorEnd=$ignoreCursorEnd   " +
					"word='empty string'  cursorBefore=$cursorBefore",
			)
			val saveIgnore = ignoreSelectionUpdate
			try {
				ignoreSelectionUpdate = true
				ic.setComposingText("", 1)
			} finally {
				ignoreSelectionUpdate = saveIgnore
			}
		}
		haveComposing = false
		lastComposingSent = null
		val ambLenNow = try {
			callbacks.getAmbiguousSequenceLength()
		} catch (_: Exception) {
			0
		}
		if (ambLenNow == 0) {
			autoSpaceDecision = false
			autoSpaceInserted = false
			pendingTrailingSpace = false
			lastPreviewSuppressLeadingSpace = false
			setIgnoreCursorRange(-1, -1)
		} else {
			callbacks.debugLog("[applyEditorUpdate 17] empty preview with active sequence; retaining state")
		}
	}

	// ── Dpad / clipboard / enter / speech (moved from JustTypeIME Step 12) ─

	fun sendDpadEvent(direction: Int, movementMode: Int) {
		val ic = getInputConnection() ?: return
		val now = android.os.SystemClock.uptimeMillis()
		when (movementMode) {
			JTUI.MOVEMENT_CHARACTER_LINE -> {
				val keyCode = when (direction) {
					JTUI.CURSOR_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
					JTUI.CURSOR_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
					JTUI.CURSOR_UP -> KeyEvent.KEYCODE_DPAD_UP
					JTUI.CURSOR_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
					else -> return
				}
				ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
				ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
			}
			JTUI.MOVEMENT_WORD_SENTENCE -> {
				val keyCode = when (direction) {
					JTUI.CURSOR_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
					JTUI.CURSOR_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
					JTUI.CURSOR_UP -> KeyEvent.KEYCODE_DPAD_UP
					JTUI.CURSOR_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
					else -> return
				}
				ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON))
				ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_CTRL_ON))
			}
			JTUI.MOVEMENT_PARAGRAPH_PAGE -> {
				val keyCode = when (direction) {
					JTUI.CURSOR_LEFT, JTUI.CURSOR_UP -> KeyEvent.KEYCODE_PAGE_UP
					JTUI.CURSOR_RIGHT, JTUI.CURSOR_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
					else -> return
				}
				ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
				ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
			}
		}
	}

	/** @return true if a word was pulled in (caller beeps otherwise). */
	fun handleManualPullIn(): Boolean {
		callbacks.debugLog("[handleManualPullIn] Manual pull-in triggered via Select key")
		val result = tryImmediatePullInAtCurrentCursor(selectFirstOnMatch = true)
		return if (result > 0) {
			callbacks.debugLog("[handleManualPullIn] Pull-in succeeded")
			haveComposing = true
			true
		} else {
			callbacks.debugLog("[handleManualPullIn] Pull-in failed (result=$result)")
			false
		}
	}

	fun handleClipboardAction(keyCode: Int) {
		val ic = getInputConnection() ?: return
		if (keyCode == -1) {
			val now = android.os.SystemClock.uptimeMillis()
			callbacks.debugLog("[handleClipboardAction] Sending Ctrl+Z")
			ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
			ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
		} else {
			val menuId = when (keyCode) {
				KeyEvent.KEYCODE_CUT -> android.R.id.cut
				KeyEvent.KEYCODE_COPY -> android.R.id.copy
				KeyEvent.KEYCODE_PASTE -> android.R.id.paste
				else -> return
			}
			// CUT/COPY with nothing selected is a silent no-op; signal it instead.
			if ((keyCode == KeyEvent.KEYCODE_CUT || keyCode == KeyEvent.KEYCODE_COPY) &&
				ic.getSelectedText(0).isNullOrEmpty()
			) {
				callbacks.errorBeep(false)
				return
			}
			callbacks.debugLog("[handleClipboardAction] performContextMenuAction menuId=$menuId")
			ic.performContextMenuAction(menuId)
		}
	}

	fun handleSpeakSelectionOrSentence() {
		val ic = getInputConnection()
		val selected = ic?.getSelectedText(0)?.toString()
		if (!selected.isNullOrBlank()) {
			callbacks.debugLog("[handleSpeakSelectionOrSentence] Speaking selection: '${selected.take(50)}...'")
			ttsController.speakInterruptible(selected)
		} else {
			callbacks.debugLog("[handleSpeakSelectionOrSentence] No selection, speaking sentence")
			speakLastSentence()
		}
	}

	fun speakLastSentence() {
		val ic = getInputConnection() ?: return
		val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
		val text = et.text?.toString() ?: return
		val cursor = et.selectionStart.coerceIn(0, text.length)
		if (text.isEmpty()) return

		val sentenceStart = TextUtils.findSentenceStart(
			text,
			cursor,
			isKnownAbbreviation = { callbacks.isKnownAbbreviation(it) },
			isKnownDomain = { callbacks.isKnownDomain(it) },
		)
		val sentenceEnd = TextUtils.findSentenceEnd(
			text,
			sentenceStart,
			isKnownAbbreviation = { callbacks.isKnownAbbreviation(it) },
			isKnownDomain = { callbacks.isKnownDomain(it) },
		)

		if (sentenceStart < sentenceEnd) {
			val sentence = text.substring(sentenceStart, sentenceEnd).trim()
			if (sentence.isNotBlank()) {
				callbacks.debugLog("[speakLastSentence] Speaking: '${sentence.take(80)}...'")
				ttsController.speakInterruptible(sentence)
			}
		}
	}

	fun errorNotification() {
		callbacks.debugLog("[errorNotification] Error condition detected")
		val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
		toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
		scope.launch {
			delay(200)
			toneGen.release()
		}
	}

	fun handleEnterAction() {
		val ic = getInputConnection()
		if (ic == null) {
			callbacks.debugLog("[handleEnterAction] InputConnection null; ignoring")
			return
		}
		val info = getInputEditorInfo()
		val imeOptions = info?.imeOptions ?: EditorInfo.IME_NULL
		val actionId = imeOptions and EditorInfo.IME_MASK_ACTION
		val forceNoAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
		val inputType = info?.inputType ?: 0
		val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
		val hasExplicitAction = !forceNoAction &&
			actionId != EditorInfo.IME_ACTION_NONE &&
			actionId != EditorInfo.IME_ACTION_UNSPECIFIED

		if (hasExplicitAction && !isMultiLine) {
			callbacks.debugLog("[handleEnterAction] Performing editor action ${describeEditorAction(actionId)}")
			ic.performEditorAction(actionId)
			return
		}

		val repo = org.continuouspath.justtype.settings.SettingsRepository.get()
		val extraBlank = repo.getBoolean(org.continuouspath.justtype.Constants.KEY_ENTER_EXTRA_BLANK_LINE)
		val newlineText = if (extraBlank) "\n\n" else "\n"
		callbacks.debugLog("[handleEnterAction] Inserting newline (action=${describeEditorAction(actionId)}, multiLine=$isMultiLine, forceNoAction=$forceNoAction, extraBlank=$extraBlank)")
		ic.finishComposingText()
		ic.commitText(newlineText, 1)
		haveComposing = false
		lastComposingSent = null
		autoSpaceDecision = false
		// Defer shift update so it runs after JTUI's KF_Enter handler finishes
		scope.launch(Dispatchers.Main) { updateShiftFromCursor() }
	}

	private fun describeEditorAction(actionId: Int): String = when (actionId) {
		EditorInfo.IME_ACTION_DONE -> "DONE"
		EditorInfo.IME_ACTION_GO -> "GO"
		EditorInfo.IME_ACTION_SEARCH -> "SEARCH"
		EditorInfo.IME_ACTION_SEND -> "SEND"
		EditorInfo.IME_ACTION_NEXT -> "NEXT"
		EditorInfo.IME_ACTION_PREVIOUS -> "PREVIOUS"
		EditorInfo.IME_ACTION_NONE -> "NONE"
		EditorInfo.IME_ACTION_UNSPECIFIED -> "UNSPECIFIED"
		else -> "UNKNOWN($actionId)"
	}

	// ── Lifecycle ────────────────────────────────────────────────────────

	fun destroy() {
		// Nothing to release yet; IME onDestroy seam for resources this controller acquires later.
	}

	companion object {
		// How long after a keyboard exit (head-tracking pop-out or pause) to
		// suppress automatic pull-in at the cursor. Just needs to outlast the
		// spurious onFinishInput → onStartInput cycle that Android fires
		// during HeadBoard's pop-out window-stack changes (typically tens of
		// milliseconds). A real user re-entry takes much longer than 1 s.
		private const val PULL_IN_SUPPRESS_AFTER_EXIT_MS = 1000L

		// C2 context reconstruction: how much committed text to read back for
		// trailing-syllable derivation. Comfortably covers the 8-syllable
		// window JTUI considers; tiny relative to editor extract sizes.
		private const val NGB_CTX_READ_CHARS = 200
	}
}
