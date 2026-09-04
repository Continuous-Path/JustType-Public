package org.continuouspath.justtype.ime

import android.content.Context
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_SKIP_KEYS_NO_VALID
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUISnapshot
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.view.KeyHistoryView
import org.continuouspath.justtype.view.SquareButton
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Characterization tests for [UiUpdateHandler].
 *
 * Locks down the snapshot-to-view dispatch logic that lives behind
 * `handleUiSnapshot()`. Production code is unchanged — assertions
 * capture *current* behavior so future refactors don't drift.
 */
@RunWith(RobolectricTestRunner::class)
class UiUpdateHandlerTest {

	private lateinit var context: Context
	private lateinit var repo: SettingsRepository
	private lateinit var handler: UiUpdateHandler

	private val phraseFlowController: PhraseFlowController = mock()
	private val scanSubsystem: ScanSubsystem = mock()
	private val ttsController: TtsController = mock()
	private val imeTextController: ImeTextController = mock()

	// Real Robolectric views for assertions.
	private lateinit var selectionListView: TextView
	private lateinit var centerLabelView: TextView

	// Mocks for views we only verify calls on.
	private val keyHistoryView: KeyHistoryView = mock()
	private val keyHistoryScrollView: HorizontalScrollView = mock()
	private val keyGridActiveView: View = mock()
	private val selectionListScanContainer: View = mock()

	// Buttons can be swapped per-test (e.g. plain Button vs SquareButton).
	private var buttons: List<Button> = List(8) { mock<SquareButton>() }

	private var useScan = false

	// Hand-rolled fake for UiUpdateCallbacks.
	private val updatedScanBuffers = mutableListOf<List<CharSequence>>()
	private val updatedJtBuffers = mutableListOf<List<CharSequence>>()
	private val editorUpdates = mutableListOf<String>()
	private val committed = mutableListOf<String>()
	private val ignoreRanges = mutableListOf<Pair<Int, Int>>()
	private val updateShiftFromCursorCalls = mutableListOf<Boolean>()
	private val resetJtuiCalls = mutableListOf<Triple<Boolean, Boolean, AutoCapReason>>()
	private var updateSelectionListDimensionsCount = 0
	private var applyScanTopRowSizeCount = 0
	private var updateItemsPerColumnCount = 0
	private var inputConnection: InputConnection? = mock()
	private var ambiguousSequenceLength: Int = 0
	private var cursorOffset: Int = 0

	private val callbacks = object : UiUpdateCallbacks {
		override fun updateScanColumnViews(buffers: List<CharSequence>) {
			updatedScanBuffers.add(buffers)
		}
		override fun updateJtColumnViews(buffers: List<CharSequence>) {
			updatedJtBuffers.add(buffers)
		}
		override fun updateSelectionListDimensions() {
			updateSelectionListDimensionsCount++
		}
		override fun applyScanTopRowSize() {
			applyScanTopRowSizeCount++
		}
		override fun updateItemsPerColumn() {
			updateItemsPerColumnCount++
		}
		override fun updateShiftFromCursor(suppressUpdateUI: Boolean) {
			updateShiftFromCursorCalls.add(suppressUpdateUI)
		}
		override fun resetJTUI(shiftState: Boolean, callUpdate: Boolean, autoCapReason: AutoCapReason) {
			resetJtuiCalls.add(Triple(shiftState, callUpdate, autoCapReason))
		}
		override fun getAutoCapReason(): AutoCapReason = AutoCapReason.NONE
		override fun getShiftState(): Boolean = false
		override fun getAmbiguousSequenceLength(): Int = ambiguousSequenceLength
		override fun getCursorOffset(): Int = cursorOffset
		override fun setIgnoreCursorRange(start: Int, end: Int) {
			ignoreRanges.add(start to end)
		}
		override fun applyEditorUpdate(preview: String) {
			editorUpdates.add(preview)
		}
		override fun commitImmediateText(text: String) {
			committed.add(text)
		}
		override fun getInputConnection(): InputConnection? = inputConnection
		override fun debugLog(message: String) { /* no-op */ }
		override fun debugLog(category: DebugCategory, message: String) { /* no-op */ }
	}

	@Before
	fun setUp() {
		context = RuntimeEnvironment.getApplication()
		org.continuouspath.justtype.settings.SettingsRegistry.getInstance(context)
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()

		selectionListView = TextView(context)
		// Give it layoutParams so the post-block's `sel.layoutParams.apply` doesn't NPE
		// when flushPosts() is invoked on the non-scan path.
		selectionListView.layoutParams = ViewGroup.LayoutParams(100, 100)
		centerLabelView = TextView(context)

		handler = UiUpdateHandler(
			context = context,
			getKeyHistoryView = { keyHistoryView },
			getKeyHistoryScrollView = { keyHistoryScrollView },
			getSelectionListView = { selectionListView },
			getCenterLabelView = { centerLabelView },
			getKeyGridActiveView = { keyGridActiveView },
			getButtons = { buttons },
			getSelectionListScanContainer = { selectionListScanContainer },
			useScanLayout = { useScan },
			phraseFlowController = phraseFlowController,
			scanSubsystem = scanSubsystem,
			ttsController = ttsController,
			imeTextController = imeTextController,
			callbacks = callbacks,
		)
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	@Test
	fun `selection scroll maps entry index through wrapped layout lines`() {
		val tv = TextView(context)
		tv.textSize = 20f
		tv.setPadding(0, 14, 0, 10) // scroll bounds must include both paddings
		// Each entry soft-wraps to ~2 layout lines at this width: index-as-line-number
		// scrolling would land far above the real entry.
		val entries = (0 until 12).map { "entry$it " + "wrap ".repeat(8) }
		tv.text = entries.joinToString("\n")
		// Robolectric's text shadow doesn't soft-wrap, so force overflow with a short
		// viewport; the offset->line mapping is identical for wrapped and unwrapped text.
		tv.measure(
			View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
		)
		tv.layout(0, 0, 300, 200)
		val layout = tv.layout!!

		handler.scrollSelectionListToSelection(tv, 8)
		var off = 0
		repeat(8) { off = tv.text.indexOf('\n', off) + 1 }
		val endOff = tv.text.indexOf('\n', off).let { if (it < 0) tv.text.length else it }
		val entryTop = layout.getLineTop(layout.getLineForOffset(off))
		val entryBottom = layout.getLineBottom(layout.getLineForOffset(endOff))
		assertThat(tv.scrollY).isGreaterThan(0)
		assertThat(entryTop).isAtLeast(tv.scrollY)
		assertThat(entryBottom).isAtMost(tv.scrollY + tv.height)

		// Mid-list: the highlight rides the viewport midpoint (Cliff's contract);
		// screen position = paddingTop + layoutY - scrollY.
		assertThat(entryBottom + tv.totalPaddingTop - tv.scrollY).isEqualTo(tv.height / 2)

		// Last entry: scroll caps at max INCLUDING padding — the entry must sit
		// fully above the viewport bottom, not half-occluded below it.
		handler.scrollSelectionListToSelection(tv, 11)
		val maxScroll = layout.height + tv.totalPaddingTop + tv.totalPaddingBottom - tv.height
		assertThat(tv.scrollY).isEqualTo(maxScroll)
		val lastBottomOnScreen = layout.getLineBottom(layout.lineCount - 1) + tv.totalPaddingTop - tv.scrollY
		assertThat(lastBottomOnScreen).isAtMost(tv.height - tv.totalPaddingBottom)

		// Stepping back to the first entry returns to the top.
		handler.scrollSelectionListToSelection(tv, 0)
		assertThat(tv.scrollY).isEqualTo(0)
	}

	@Test
	fun `selection list view pins scroll against internal resets`() {
		val tv = org.continuouspath.justtype.view.SelectionListView(context)
		tv.textSize = 20f
		tv.text = (0 until 12).joinToString("\n") { "entry$it" }
		tv.measure(
			View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
		)
		tv.layout(0, 0, 300, 200)

		// The authoritative pass pins; TextView-internal scrolls (e.g.
		// bringTextIntoView's scroll-to-top after setText) land on the pin.
		handler.scrollSelectionListToSelection(tv, 8)
		val pinned = tv.scrollY
		assertThat(pinned).isGreaterThan(0)
		tv.scrollTo(0, 0)
		assertThat(tv.scrollY).isEqualTo(pinned)

		// Unpinned, scrolls pass through again.
		tv.pinnedScrollY = null
		tv.scrollTo(0, 0)
		assertThat(tv.scrollY).isEqualTo(0)
	}

	@Test
	fun `paged buffers scroll to the highlight span not entry-index newlines`() {
		val tv = TextView(context)
		tv.textSize = 20f
		// Paged shape: linear rows, then page groups behind BLANK separator lines
		// with two tabbed words per row — entry-index-to-newline mapping is wrong
		// here; the highlight span is the source of truth.
		val sb = android.text.SpannableStringBuilder()
		repeat(4) { sb.append("row$it\n") }
		repeat(5) { g ->
			sb.append("\n")
			repeat(4) { r -> sb.append("g${g}left$r\tg${g}right$r").append("\n") }
		}
		val target = sb.toString().indexOf("g3left2")
		val targetEnd = sb.toString().indexOf('\n', target)
		sb.setSpan(
			org.continuouspath.justtype.logic.JTUI.FullWidthLineBackgroundSpan(-0x10000),
			target,
			targetEnd,
			android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
		)
		tv.text = sb
		tv.measure(
			View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
		)
		tv.layout(0, 0, 300, 200)
		val layout = tv.layout!!

		// A stale entry index (7) must be IGNORED in favor of the span.
		val y = handler.computeSelectionScrollY(tv, null, 7)
		val spanBottom = layout.getLineBottom(layout.getLineForOffset(targetEnd))
		val maxScroll = layout.height + tv.totalPaddingTop + tv.totalPaddingBottom - tv.height
		val expected = (spanBottom + tv.totalPaddingTop - tv.height / 2).coerceIn(0, maxScroll)
		assertThat(y).isEqualTo(expected)
		assertThat(y).isGreaterThan(0)
	}

	// Setter helper — synchronous DataStore write so handleUiSnapshot()
	// reads our value (the async put*() path can race the persistent collect job).
	private fun setBoolean(key: String, value: Boolean) {
		repo.edit().putBoolean(key, value).commit()
	}

	private fun flushPosts() {
		shadowOf(Looper.getMainLooper()).idle()
	}

	@Suppress("LongParameterList") // Mirrors JTUISnapshot's many fields; tests vary 1–2 at a time.
	private fun snapshot(
		outputBuffer: String = "",
		ambigBuffer: String = "",
		selectionListBuffers: List<CharSequence> = listOf(""),
		keyHistoryBuffer: String = "",
		centerSpace: String = "",
		keyLabels: List<String> = List(8) { "" },
		keyLabelGrids: List<List<String>> = emptyList(),
		ambigKeyLabels: List<List<String>> = emptyList(),
		baseOutput: String = "",
		speechString: String = "",
		customWord: String? = null,
		speakState: Boolean = false,
		shiftState: Boolean = false,
		isManualShift: Boolean = false,
		isSpellingMode: Boolean = false,
		topCandidateOutput: String? = null,
		selectedCandidateOutput: String? = null,
		topCandidateType: String? = null,
		selectedCandidateType: String? = null,
		highlightNextLetters: Boolean = false,
		nextLetterHints: Set<Char> = emptySet(),
		accentNextLetterHints: Set<Char> = emptySet(),
		ambiguousKeyMask: List<Boolean> = List(8) { false },
		highlightedKeyIndices: Set<Int> = emptySet(),
		currentSelectionIndex: Int? = null,
		historyHighlightWord: String? = null,
	): JTUISnapshot = JTUISnapshot(
		outputBuffer = outputBuffer,
		ambigBuffer = ambigBuffer,
		selectionListBuffers = selectionListBuffers,
		keyHistoryBuffer = keyHistoryBuffer,
		centerSpace = centerSpace,
		keyLabels = keyLabels,
		keyLabelGrids = keyLabelGrids,
		ambigKeyLabels = ambigKeyLabels,
		baseOutput = baseOutput,
		speechString = speechString,
		customWord = customWord,
		speakState = speakState,
		shiftState = shiftState,
		isManualShift = isManualShift,
		isSpellingMode = isSpellingMode,
		topCandidateOutput = topCandidateOutput,
		selectedCandidateOutput = selectedCandidateOutput,
		topCandidateType = topCandidateType,
		selectedCandidateType = selectedCandidateType,
		highlightNextLetters = highlightNextLetters,
		nextLetterHints = nextLetterHints,
		accentNextLetterHints = accentNextLetterHints,
		ambiguousKeyMask = ambiguousKeyMask,
		highlightedKeyIndices = highlightedKeyIndices,
		currentSelectionIndex = currentSelectionIndex,
		historyHighlightWord = historyHighlightWord,
	)

	// ── Group 1: Layout routing ──────────────────────────────────────────

	@Test
	fun `useScanLayout true routes selectionList to updateScanColumnViews`() {
		useScan = true
		val buffers = listOf<CharSequence>("col1", "col2", "col3")
		handler.handleUiSnapshot(snapshot(selectionListBuffers = buffers))

		assertThat(updatedScanBuffers).hasSize(1)
		assertThat(updatedScanBuffers[0]).isEqualTo(buffers)
		// Non-scan path NOT taken.
		assertThat(selectionListView.text.toString()).isEmpty()
	}

	@Test
	fun `useScanLayout false sets selectionListView text to first buffer`() {
		useScan = false
		val buffers = listOf<CharSequence>("hello", "world")
		handler.handleUiSnapshot(snapshot(selectionListBuffers = buffers))

		assertThat(selectionListView.text.toString()).isEqualTo("hello")
		assertThat(updatedScanBuffers).isEmpty()
	}

	@Test
	fun `useScanLayout false with empty buffers sets text to empty`() {
		useScan = false
		handler.handleUiSnapshot(snapshot(selectionListBuffers = emptyList()))

		assertThat(selectionListView.text.toString()).isEmpty()
	}

	// ── Group 2: KeyHistory + scroll ─────────────────────────────────────

	@Test
	fun `keyHistoryView setKeyHistory called with ambigKeyLabels`() {
		val labels = listOf(listOf("A", "B", "C"), listOf("D", "E", "F"))
		handler.handleUiSnapshot(snapshot(ambigKeyLabels = labels))

		verify(keyHistoryView).setKeyHistory(labels)
	}

	@Test
	fun `keyHistoryView receives the history highlight word`() {
		val labels = listOf(listOf("A", "B", "C"))
		handler.handleUiSnapshot(snapshot(ambigKeyLabels = labels, historyHighlightWord = "at"))

		verify(keyHistoryView).setKeyHistory(labels, "at")
	}

	@Test
	fun `keyHistoryScrollView fullScroll FOCUS_RIGHT after idleMainLooper`() {
		whenever(keyHistoryScrollView.post(any())).thenAnswer { invocation ->
			val r = invocation.getArgument<Runnable>(0)
			r.run()
			true
		}
		handler.handleUiSnapshot(snapshot())
		flushPosts()

		verify(keyHistoryScrollView).fullScroll(View.FOCUS_RIGHT)
	}

	// ── Group 3: Center label ────────────────────────────────────────────

	@Test
	fun `centerLabel text matches ui centerSpace`() {
		handler.handleUiSnapshot(snapshot(centerSpace = "abc"))
		assertThat(centerLabelView.text.toString()).isEqualTo("abc")
	}

	@Test
	fun `centerLabel gravity is TOP when isSpellingMode true`() {
		handler.handleUiSnapshot(snapshot(isSpellingMode = true))
		assertThat(centerLabelView.gravity).isEqualTo(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
	}

	@Test
	fun `centerLabel gravity is CENTER when isSpellingMode false`() {
		handler.handleUiSnapshot(snapshot(isSpellingMode = false))
		assertThat(centerLabelView.gravity).isEqualTo(Gravity.CENTER)
	}

	// ── Group 4: Buttons ────────────────────────────────────────────────

	@Test
	fun `SquareButton no grid uses setCenteredLabel and clears text`() {
		val labels = listOf("abc", "def", "ghi", "jkl", "mno", "pqr", "stu", "vwx")
		handler.handleUiSnapshot(snapshot(keyLabels = labels))

		val btn0 = buttons[0] as SquareButton
		verify(btn0).setCenteredLabel("abc")
		verify(btn0).text = ""
	}

	@Test
	fun `SquareButton with grid content uses setLabelGrid not centered`() {
		val grid0 = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i")
		val grids = listOf(grid0) + List(7) { emptyList<String>() }
		handler.handleUiSnapshot(snapshot(keyLabels = List(8) { "x" }, keyLabelGrids = grids))

		val btn0 = buttons[0] as SquareButton
		verify(btn0).setLabelGrid(grid0)
		verify(btn0).text = ""
		verify(btn0, never()).setCenteredLabel(any())
	}

	@Test
	fun `setNextLetterHints isAmbig true only for ambiguous indices`() {
		val mask = listOf(false, false, false, true, false, false, false, false)
		handler.handleUiSnapshot(snapshot(ambiguousKeyMask = mask))

		verify(buttons[3] as SquareButton).setNextLetterHints(any(), any(), any(), eq(true), any(), anyOrNull(), any())
		verify(buttons[0] as SquareButton).setNextLetterHints(any(), any(), any(), eq(false), any(), anyOrNull(), any())
		verify(buttons[7] as SquareButton).setNextLetterHints(any(), any(), any(), eq(false), any(), anyOrNull(), any())
	}

	@Test
	fun `setHighlighted true only for indices in highlightedKeyIndices`() {
		handler.handleUiSnapshot(snapshot(highlightedKeyIndices = setOf(2, 5)))

		verify(buttons[2] as SquareButton).setHighlighted(true)
		verify(buttons[5] as SquareButton).setHighlighted(true)
		verify(buttons[0] as SquareButton).setHighlighted(false)
		verify(buttons[3] as SquareButton).setHighlighted(false)
		verify(buttons[7] as SquareButton).setHighlighted(false)
	}

	@Test
	fun `plain Button gets text label and setNextLetterHints not called`() {
		buttons = List(8) { mock<Button>() }
		handler.handleUiSnapshot(snapshot(keyLabels = List(8) { "Z" }))

		verify(buttons[0]).text = "Z"
		// Only SquareButton has setNextLetterHints — plain Button never receives it.
		// (Sanity check: setHighlighted is also absent; verifying it would not compile
		// as the method doesn't exist on Button.)
	}

	@Test
	fun `keyLabels out of bounds yields empty label without crash`() {
		// 8 buttons, but keyLabels is empty — getOrNull returns null → "".
		handler.handleUiSnapshot(snapshot(keyLabels = emptyList()))

		val btn0 = buttons[0] as SquareButton
		verify(btn0).setCenteredLabel("")
	}

	// ── Group 5: Hint forcing ────────────────────────────────────────────

	@Test
	fun `hint forcing off when KEY_SKIP_KEYS_NO_VALID is false`() {
		setBoolean(KEY_SKIP_KEYS_NO_VALID, false)
		val mask = listOf(true, false, false, false, false, false, false, false)
		handler.handleUiSnapshot(
			snapshot(
				ambiguousKeyMask = mask,
				nextLetterHints = emptySet(),
				highlightNextLetters = false,
			),
		)

		// No forcing → effectiveHighlight = highlightNextLetters (false).
		verify(buttons[0] as SquareButton).setNextLetterHints(eq(false), any(), any(), eq(true), any(), anyOrNull(), any())
	}

	@Test
	fun `hint forcing engages when skip enabled and ambig mask set and hints empty`() {
		setBoolean(KEY_SKIP_KEYS_NO_VALID, true)
		val mask = listOf(true, true, false, false, false, false, false, false)
		handler.handleUiSnapshot(
			snapshot(
				ambiguousKeyMask = mask,
				nextLetterHints = emptySet(),
				accentNextLetterHints = emptySet(),
			),
		)

		// Forcing engaged → effectiveHighlight = true on every SquareButton.
		for (btn in buttons) {
			verify(btn as SquareButton).setNextLetterHints(eq(true), any(), any(), any(), any(), anyOrNull(), any())
		}
	}

	@Test
	fun `hint forcing skipped when hints non-empty`() {
		setBoolean(KEY_SKIP_KEYS_NO_VALID, true)
		val mask = listOf(true, false, false, false, false, false, false, false)
		handler.handleUiSnapshot(
			snapshot(
				ambiguousKeyMask = mask,
				nextLetterHints = setOf('a'),
				highlightNextLetters = false,
			),
		)

		// Hints non-empty → forcing is NOT triggered → highlight stays at highlightNextLetters (false).
		verify(buttons[0] as SquareButton).setNextLetterHints(eq(false), any(), any(), eq(true), any(), anyOrNull(), any())
	}

	// ── Group 6: Composing text — reset path ─────────────────────────────

	@Test
	fun `resetJTUI true triggers updateShiftFromCursor and resetJTUI callbacks`() {
		whenever(imeTextController.resetJTUI).thenReturn(true)
		handler.handleUiSnapshot(snapshot())

		assertThat(updateShiftFromCursorCalls).containsExactly(true)
		assertThat(resetJtuiCalls).hasSize(1)
		verify(imeTextController).resetJTUI = false
		verify(imeTextController).haveComposing = false
		// applyEditorUpdate / commitImmediateText NOT called on reset path.
		assertThat(editorUpdates).isEmpty()
		assertThat(committed).isEmpty()
	}

	// ── Group 7: Composing text — delete-on-empty-preview ────────────────

	@Test
	fun `deletes composing text when preview empty and ambLen is zero`() {
		whenever(imeTextController.lastComposingSent).thenReturn("test")
		ambiguousSequenceLength = 0
		cursorOffset = 5
		val ic = inputConnection!!

		handler.handleUiSnapshot(
			snapshot(
				topCandidateOutput = null,
				selectedCandidateOutput = null,
				isSpellingMode = false,
			),
		)

		verify(ic).setComposingText("", 1)
		verify(imeTextController).haveComposing = false
		verify(imeTextController).lastComposingSent = null
		verify(imeTextController).autoSpaceDecision = false
		verify(imeTextController).autoSpaceInserted = false
		assertThat(ignoreRanges).contains(3 to 7) // selStart=5-2, selEnd=5+2
	}

	@Test
	fun `does not delete composing text when ambiguous sequence still active`() {
		whenever(imeTextController.lastComposingSent).thenReturn("test")
		ambiguousSequenceLength = 2
		val ic = inputConnection!!

		handler.handleUiSnapshot(
			snapshot(topCandidateOutput = null, selectedCandidateOutput = null),
		)

		verify(ic, never()).setComposingText(any(), any())
	}

	@Test
	fun `null inputConnection does not crash on delete path`() {
		whenever(imeTextController.lastComposingSent).thenReturn("test")
		ambiguousSequenceLength = 0
		inputConnection = null

		handler.handleUiSnapshot(
			snapshot(topCandidateOutput = null, selectedCandidateOutput = null),
		)

		// No crash; nothing to verify on a null IC.
		assertThat(ignoreRanges).isEmpty()
	}

	// ── Group 8: applyEditorUpdate vs commitImmediateText ────────────────

	@Test
	fun `applyEditorUpdate called with selectedCandidateOutput preview`() {
		handler.handleUiSnapshot(
			snapshot(
				selectedCandidateOutput = "hello",
				isSpellingMode = false,
			),
		)

		assertThat(editorUpdates).containsExactly("hello")
		assertThat(committed).isEmpty()
	}

	@Test
	fun `commitImmediateText with full baseOutput when no committed prefix`() {
		whenever(imeTextController.lastCommittedBaseOutput).thenReturn("")
		handler.handleUiSnapshot(
			snapshot(
				baseOutput = "abc",
				topCandidateOutput = null,
				selectedCandidateOutput = null,
			),
		)

		assertThat(committed).containsExactly("abc")
		verify(imeTextController).lastCommittedBaseOutput = "abc"
	}

	@Test
	fun `commitImmediateText with delta when baseOutput extends committed prefix`() {
		whenever(imeTextController.lastCommittedBaseOutput).thenReturn("abc")
		handler.handleUiSnapshot(
			snapshot(
				baseOutput = "abcdef",
				topCandidateOutput = null,
				selectedCandidateOutput = null,
			),
		)

		assertThat(committed).containsExactly("def")
		verify(imeTextController).lastCommittedBaseOutput = "abcdef"
	}

	@Test
	fun `commitImmediateText with full baseOutput when no shared prefix`() {
		whenever(imeTextController.lastCommittedBaseOutput).thenReturn("abc")
		handler.handleUiSnapshot(
			snapshot(
				baseOutput = "xyz",
				topCandidateOutput = null,
				selectedCandidateOutput = null,
			),
		)

		assertThat(committed).containsExactly("xyz")
		verify(imeTextController).lastCommittedBaseOutput = "xyz"
	}

	// ── Group 9: Pass-throughs ───────────────────────────────────────────

	@Test
	fun `phraseFlowController onUiSnapshot called once`() {
		val ui = snapshot()
		handler.handleUiSnapshot(ui)
		verify(phraseFlowController).onUiSnapshot(ui)
	}

	@Test
	fun `scanSubsystem updateValidMask called once with snapshot`() {
		val ui = snapshot()
		handler.handleUiSnapshot(ui)
		verify(scanSubsystem).updateValidMask(ui)
	}

	@Test
	fun `ttsController handleSelectionSpeech called with derived args`() {
		handler.handleUiSnapshot(
			snapshot(
				speakState = true,
				selectedCandidateOutput = "sel",
				selectedCandidateType = "word",
			),
		)
		verify(ttsController).handleSelectionSpeech(
			speakState = eq(true),
			selText = eq("sel"),
			selType = eq("word"),
		)
	}

	@Test
	fun `phraseFlowController handleUiAutoCommit called with derived args`() {
		whenever(imeTextController.suspendCommit).thenReturn(false)
		handler.handleUiSnapshot(
			snapshot(
				selectedCandidateOutput = "sel",
				selectedCandidateType = "word",
			),
		)
		verify(phraseFlowController).handleUiAutoCommit(
			suspendCommit = eq(false),
			selectedType = eq("word"),
			selectedCandidate = eq("sel"),
		)
	}

	// ── Group 10: suspendCommit short-circuit ────────────────────────────

	@Test
	fun `suspendCommit true with resetJTUI false skips both commit branches`() {
		whenever(imeTextController.suspendCommit).thenReturn(true)
		whenever(imeTextController.resetJTUI).thenReturn(false)
		handler.handleUiSnapshot(
			snapshot(
				baseOutput = "abc",
				selectedCandidateOutput = "preview",
			),
		)

		assertThat(editorUpdates).isEmpty()
		assertThat(committed).isEmpty()
		// Pass-throughs still fire.
		verify(phraseFlowController, times(1)).onUiSnapshot(any())
		verify(scanSubsystem, times(1)).updateValidMask(any())
	}
}
