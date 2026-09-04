package org.continuouspath.justtype.ime

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.data.PhraseRepository
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logic.AutoCapReason
import org.continuouspath.justtype.logic.JTUI
import org.continuouspath.justtype.logic.LayoutMode
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * EDITOR-LEVEL integration of the paged-pick commit: real JTUI + real
 * ImeTextController + real UiUpdateHandler, editing a real EditText through
 * a BaseInputConnection — the full snapshot -> composing/commit pipeline the
 * IME runs (snapshots dispatched synchronously, matching Main.immediate).
 *
 * Regression target (Cliff, 2026-08-11): a word picked from a page-list menu
 * must be FINALIZED in the editor — not left as composing text for the next
 * word's preview to replace.
 */
@Suppress("EmptyFunctionBlock") // no-op callback fakes, same pattern as the sibling fixtures
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PagedPickEditorIntegrationTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var testScope: TestScope
	private lateinit var editText: EditText
	private lateinit var ic: InputConnection
	private lateinit var controller: ImeTextController
	private lateinit var handler: UiUpdateHandler
	private lateinit var jtui: JTUI
	private lateinit var repo: SettingsRepository
	private val trace = mutableListOf<String>()
	private var lastSnap: org.continuouspath.justtype.logic.JTUISnapshot? = null

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	/** JTUI paged cell order (PAGED_ORDINAL_FOR_AMBIG): flat offset per ambig key. */
	private val pagedOrdinalForAmbig = intArrayOf(0, 3, 1, 4, 2, 5)

	@Before
	fun setUp() {
		testScope = TestScope(StandardTestDispatcher())
		val app = RuntimeEnvironment.getApplication()
		SettingsRepository.resetInstanceForTesting()
		SettingsRegistry.resetInstanceForTesting()
		SettingsRegistry.getInstance(app)
		repo = SettingsRepository.getInstance(app)
		repo.putString(Constants.KEY_LAYOUT_MODE, Constants.MODE_OPT)
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "English")
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_PAGED)

		editText = EditText(app)
		ic = object : BaseInputConnection(editText, true) {
			override fun getEditable(): android.text.Editable = editText.text

			// BaseInputConnection returns null here; real editors implement it,
			// and the autospace logic depends on it. Synthesize from the view.
			override fun getExtractedText(
				request: android.view.inputmethod.ExtractedTextRequest?,
				flags: Int,
			): android.view.inputmethod.ExtractedText = android.view.inputmethod.ExtractedText().apply {
				text = editText.text.toString()
				startOffset = 0
				selectionStart = android.text.Selection.getSelectionStart(editText.text).coerceAtLeast(0)
				selectionEnd = android.text.Selection.getSelectionEnd(editText.text).coerceAtLeast(0)
			}
		}
		val tts = TtsController(
			context = app,
			scope = testScope,
			getSpeakState = { false },
			getInputConnection = { ic },
		)
		controller = ImeTextController(
			scope = testScope,
			getInputConnection = { ic },
			getInputEditorInfo = { null },
			callbacks = IntegrationImeTextCallbacks(),
			ttsController = tts,
		)

		jtui = JTUI(
			onAddNewPhrase = {},
			phraseRepository = PhraseRepository(java.io.File(tmpDir.root, "phrases.json")),
			sayInterruptible = {},
			sayQueued = {},
			onUiUpdate = {
				lastSnap = it
				trace.add(
					"SNAPSHOT top='${it.topCandidateOutput}' sel='${it.selectedCandidateOutput}' " +
						"ambig='${it.ambigBuffer}' listSize=${jtui.selectionListForTest().size} " +
						"editor='${editText.text}'",
				)
				handler.handleUiSnapshot(it)
			},
			onImmediateOutput = { controller.onImmediateOutput(it) },
			onSpellingOutput = {},
			onSpeakSentence = {},
			onSpeakNextSentence = {},
			onFinalizeText = { controller.onFinalizeText(it) },
			onNumericOutput = { controller.onNumericOutput(it) },
			onAmbiguousSequenceStart = { controller.onAmbiguousSequenceStart() },
			onSpaceIfNeeded = {},
			// Real IME wiring (JustTypeIME does the same): UnDo/delete flows are
			// exactly the composing/editor behaviors this harness exists to test.
			onUndoPressed = { context -> controller.onUndoPressed(context) },
			onDeleteWord = {},
			onDeleteChar = { controller.handleDeleteChar() },
			assets = app.assets,
			filesDir = tmpDir.root,
			prefs = repo,
			context = app,
			onEnterKey = {},
			onSetAutospaceSuppressed = {},
			onConfidenceSignal = {},
			onCouldHaveSavedPrompt = {},
			onNgbSpanCollapse = {},
		)

		val selectionListView = TextView(app).apply {
			layoutParams = ViewGroup.LayoutParams(100, 100)
		}
		handler = UiUpdateHandler(
			context = app,
			getKeyHistoryView = { mock() },
			getKeyHistoryScrollView = { mock<HorizontalScrollView>() },
			getSelectionListView = { selectionListView },
			getCenterLabelView = { TextView(app) },
			getKeyGridActiveView = { mock<View>() },
			getButtons = { List(8) { mock<org.continuouspath.justtype.view.SquareButton>() } },
			getSelectionListScanContainer = { mock<View>() },
			useScanLayout = { false },
			phraseFlowController = mock(),
			scanSubsystem = mock(),
			ttsController = tts,
			imeTextController = controller,
			callbacks = object : UiUpdateCallbacks {
				override fun updateScanColumnViews(buffers: List<CharSequence>) {}
				override fun updateJtColumnViews(buffers: List<CharSequence>) {}
				override fun updateSelectionListDimensions() {}
				override fun applyScanTopRowSize() {}
				override fun updateItemsPerColumn() {}
				override fun updateShiftFromCursor(suppressUpdateUI: Boolean) {}
				override fun resetJTUI(shiftState: Boolean, callUpdate: Boolean, autoCapReason: AutoCapReason) {
					jtui.resetJTUI(shiftState, callUpdate, autoCapReason = autoCapReason)
				}
				override fun getAutoCapReason(): AutoCapReason = jtui.getAutoCapReason()
				override fun getShiftState(): Boolean = jtui.getShiftState()
				override fun getAmbiguousSequenceLength(): Int = jtui.getAmbiguousSequenceLength()
				override fun getCursorOffset(): Int = editText.selectionStart.coerceAtLeast(0)
				override fun setIgnoreCursorRange(start: Int, end: Int) {}
				override fun applyEditorUpdate(preview: String) = controller.applyEditorUpdate(preview)
				override fun commitImmediateText(text: String) = controller.commitImmediateText(text)
				override fun getInputConnection(): InputConnection? = ic
				override fun debugLog(message: String) {
					trace.add(message)
				}
				override fun debugLog(category: DebugCategory, message: String) {
					trace.add(message)
				}
				override fun getHeadTrackingCenterOverride(): String? = null
			},
		)

		jtui.init()
		jtui.layoutMode = LayoutMode.Optimized
	}

	@After
	fun tearDown() {
		testScope.coroutineContext[Job]?.cancel()
		ResetSingletonsRule.resetAll()
	}

	private fun press(button: Int) {
		jtui.buttonPressed(button)
		// Flush the search-result / snapshot posts like the device main loop.
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
	}

	private fun type(word: String, keyCount: Int = Int.MAX_VALUE) {
		jtui.wordKeySequence(word)!!.take(keyCount).forEach { press(pagePos[it]) }
	}

	private fun pickFlatIndex(idx: Int) {
		repeat(3) { press(selectPos) } // rows 0, 1, then page 1
		press(pagePos[pagedOrdinalForAmbig.indexOf(idx - 2)])
	}

	private fun editor(): String = editText.text.toString()

	private fun composingRegion(): String? {
		val start = BaseInputConnection.getComposingSpanStart(editText.text)
		val end = BaseInputConnection.getComposingSpanEnd(editText.text)
		return if (start >= 0 && end >= start) editText.text.substring(start, end) else null
	}

	private fun indexOfWord(word: String): Int = jtui.selectionListForTest()
		.indexOfFirst { (it["output"] as? String)?.equals(word, ignoreCase = true) == true }

	@Test fun `paged pick is finalized - next word appends after it, never replaces`() {
		// The crib-sheet dig flow: type a word, page to it, letter-pick it.
		type("organs") // organized/organization head the list; organs demoted below
		val idx = indexOfWord("organs")
		assertThat(idx).isAtLeast(2)
		pickFlatIndex(idx)
		trace.add("=== after pick: editor='${editor()}' composing='${composingRegion()}' ===")
		// The picked word is COMMITTED: present, and no composing region remains.
		assertThat(editor().trim().lowercase()).isEqualTo("organs")
		assertThat(composingRegion()).isNull()
		// Type the next word: it must append after an autospace, not replace.
		type("to")
		if (!editor().lowercase().startsWith("organs ")) {
			println(trace.takeLast(90).joinToString("\n"))
		}
		assertThat(editor().lowercase()).startsWith("organs ")
		assertThat(composingRegion()).isNotNull() // next word still composing
		assertThat(editor().lowercase()).doesNotContain("organsto")
	}

	@Test fun `paged pick after a context commit keeps both words`() {
		// Full recipe flow: commit "and" (Select + AK finalize), then dig to organs.
		type("and")
		press(selectPos)
		trace.add("=== AK-after-SEL boundary ===")
		val keys = jtui.wordKeySequence("organs")!!
		press(pagePos[keys.first()]) // finalizes "and" + starts new sequence
		// (No preview yet: a single press of this key could be the Navigation
		// list function, so the list shows only the P row — by design.)
		keys.drop(1).forEach { press(pagePos[it]) }
		// The committed word stands; an autospace separates it from the new
		// word's composing preview.
		if (!editor().lowercase().startsWith("and ")) {
			println(trace.takeLast(90).joinToString("\n"))
		}
		assertThat(editor().lowercase()).startsWith("and ")
		val idx = indexOfWord("organs")
		assertThat(idx).isAtLeast(2)
		pickFlatIndex(idx)
		assertThat(editor().lowercase()).isEqualTo("and organs")
		assertThat(composingRegion()).isNull()
		// And the word after that still appends.
		type("to")
		assertThat(editor().lowercase()).startsWith("and organs ")
	}

	@Test fun `zero-K follower pick is finalized too`() {
		// Commit a word, then pick a follower from the zero-K paged menu.
		type("of")
		press(selectPos)
		type("and", keyCount = 1) // AK-after-SEL commits "of"; UnDo the stray key
		press(1)
		assertThat(editor().trim().lowercase()).isEqualTo("of")
		// Engage the zero-K follower list and page-pick a follower.
		val list = jtui.selectionListForTest()
		if (list.size >= 8) {
			pickFlatIndex(2) // first page-1 cell
			val words = editor().trim().split(Regex("\\s+"))
			assertThat(words.size).isEqualTo(2)
			assertThat(words[0].lowercase()).isEqualTo("of")
			assertThat(composingRegion()).isNull()
		}
	}

	@Test fun `select of a zero-K follower after a paged pick appends - never replaces`() {
		// Cliff's bug (2026-08-14): page-pick a word, then SELECT a zero-K
		// follower from the new list — the follower must append after an
		// autospace; it must never replace the picked word (the pick is FINAL).
		type("organs")
		val idx = indexOfWord("organs")
		assertThat(idx).isAtLeast(2)
		pickFlatIndex(idx)
		assertThat(editor().trim().lowercase()).isEqualTo("organs")
		// SELECT steps into the zero-K follower list; the selected follower's
		// composing preview must land AFTER the picked word.
		press(selectPos)
		trace.add("=== after zero-K select: editor='${editor()}' composing='${composingRegion()}' ===")
		if (!editor().lowercase().startsWith("organs ")) {
			println(trace.takeLast(90).joinToString("\n"))
		}
		assertThat(editor().lowercase()).startsWith("organs ")
		// AK-after-SEL commits the follower; both words stand.
		type("and", keyCount = 1)
		val words = editor().trim().split(Regex("\\s+"))
		assertThat(words.size).isAtLeast(2)
		assertThat(words[0].lowercase()).isEqualTo("organs")
	}

	@Test fun `undo after a paged pick deletes the pick's last char - never the text before a zombie region`() {
		// Cliff's "Bigimprovements" (2026-08-14): after a pick whose word the
		// editor re-marked composing, deleteSurroundingText SKIPS the region,
		// so each UnDo ate the character BEFORE the word (the autospace, then
		// the previous word's letters). The zombie guard seals first: UnDo
		// must delete the picked word's own last character.
		type("and")
		press(selectPos)
		press(pagePos[jtui.wordKeySequence("organs")!!.first()]) // AK-after-SEL commits "and"
		jtui.wordKeySequence("organs")!!.drop(1).forEach { press(pagePos[it]) }
		pickFlatIndex(indexOfWord("organs"))
		assertThat(editor().lowercase()).isEqualTo("and organs")
		// Recreate the device churn: a reset wiped the undo stack (so UnDo
		// cannot restore the pre-pick page — that path is the designed
		// mis-pick recovery and works), and the editor re-marked the picked
		// word as composing while the controller believes it sealed.
		jtui.resetJTUI(false, false)
		val start = editor().indexOf("organs", ignoreCase = true)
		ic.setComposingRegion(start, editor().length)
		assertThat(composingRegion()).isNotNull()
		press(1) // UnDo
		trace.add("=== after undo: editor='${editor()}' composing='${composingRegion()}' ===")
		// The deletion lands INSIDE the picked word (its 's' — auto-restore
		// may then pull the word in and preview a candidate, standard resume
		// editing). The autospace and the previous word must be untouched:
		// with the zombie unsealed, deleteSurroundingText skipped the region
		// and ate the space ("andorgans"), then the 'd'.
		if (!editor().lowercase().startsWith("and ")) {
			println(trace.takeLast(60).joinToString("\n"))
		}
		assertThat(editor().lowercase()).startsWith("and ")
		// Genuine fusion check (no space-stripping — that would flag the
		// CORRECT "and organs" too): the words never merge.
		assertThat(editor().lowercase()).doesNotContain("andorgan")
	}

	@Test fun `pull-in of a page-buried word relocates it to slot 2 and selects it`() {
		// Cliff's page-group pull-in rule (2026-08-14): "add enough Selects to
		// make it the currently selected word" is impossible for a word that
		// resolves inside a page group (Selects open pages instead). The word
		// relocates to list slot 2, and the reconstruction selects it there.
		ic.commitText("and organs", 1)
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		val res = controller.tryImmediatePullInAtCurrentCursor()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		trace.add("=== after pull-in: res=$res editor='${editor()}' composing='${composingRegion()}' sel=${jtui.getCurrentSelectionIndex()} outs=${jtui.getSelectionOutputs().take(3)} ===")
		if (jtui.getCurrentSelectionIndex() != 1) {
			println(trace.takeLast(60).joinToString("\n"))
		}
		assertThat(res).isGreaterThan(0)
		// organized/organization outrank organs — it would sit page-buried;
		// the rule relocates it to slot 2 (index 1) and selects it.
		assertThat(jtui.getSelectionOutputs().getOrNull(1)?.lowercase()).isEqualTo("organs")
		assertThat(jtui.getCurrentSelectionIndex()).isEqualTo(1)
		// The editor is untouched: same text, now composing for re-editing.
		assertThat(editor().lowercase()).isEqualTo("and organs")
		assertThat(composingRegion()?.lowercase()).isEqualTo("organs")
	}

	@Test fun `undo beside an inactive word pulls it in whole - no character deleted`() {
		// Cliff (2026-08-14): cursor adjacent to FINALIZED text with an empty
		// key buffer — UnDo must pull the whole object back in BEFORE any
		// deletion. The old delete-first order chopped the last character and
		// then cascaded (the truncated string matches no database word).
		ic.commitText("and organs", 1)
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		press(1) // UnDo
		trace.add("=== after undo: editor='${editor()}' composing='${composingRegion()}' ===")
		if (editor().lowercase() != "and organs") {
			println(trace.takeLast(60).joinToString("\n"))
		}
		// Nothing deleted: the word is back in play as composing text.
		assertThat(editor().lowercase()).isEqualTo("and organs")
		assertThat(composingRegion()?.lowercase()).isEqualTo("organs")
		assertThat(jtui.getAmbiguousSequenceLength()).isGreaterThan(0)
	}

	@Test fun `undo after a trailing space still deletes the space then pulls in`() {
		// The space case is unchanged: UnDo removes the trailing space, then
		// the preceding object pulls in (the pre-existing Context 8 flow).
		ic.commitText("and organs ", 1)
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		press(1) // UnDo
		trace.add("=== after undo(space): editor='${editor()}' composing='${composingRegion()}' ===")
		if (editor().lowercase() != "and organs") {
			println(trace.takeLast(60).joinToString("\n"))
		}
		assertThat(editor().lowercase()).isEqualTo("and organs")
		assertThat(composingRegion()?.lowercase()).isEqualTo("organs")
	}

	@Test fun `finalized flag SELECT leg - lingering composing region survives a zero-K select`() {
		// The device shape of the same bug: the editor re-marks the picked
		// word as composing (churn the controller cannot see), then the user
		// presses SELECT on the zero-K list. Without the flag's Select leg the
		// follower's composing preview REPLACED the picked word.
		type("organs")
		pickFlatIndex(indexOfWord("organs"))
		assertThat(editor().trim().lowercase()).isEqualTo("organs")
		ic.setComposingRegion(0, editor().length)
		assertThat(composingRegion()).isNotNull()
		press(selectPos)
		trace.add("=== after churn+select: editor='${editor()}' composing='${composingRegion()}' ===")
		if (!editor().lowercase().startsWith("organs ")) {
			println(trace.takeLast(90).joinToString("\n"))
		}
		assertThat(editor().lowercase()).startsWith("organs ")
		val follower = editor().trim().split(Regex("\\s+")).getOrNull(1)
		assertThat(follower).isNotNull()
	}

	@Test fun `field entry with no word at cursor derives the NGB context - BOS and mid-text`() {
		// Cliff's "the never recovers at BOS" (2026-08-12): entering a field
		// with the cursor after a sentence boundary (or an empty field) left
		// the context NULL — the BOS row never served. The field-entry funnel
		// reconstructs from the preceding text.
		editText.setText("We got home. ")
		android.text.Selection.setSelection(editText.text, editText.text.length)
		controller.reconstructNgbContextAtCursor()
		assertThat(jtui.ngbContextForTest()).isEqualTo(JTUI.NGB_BOS_CTX to true)
		// Empty field = a known sentence start too.
		editText.setText("")
		android.text.Selection.setSelection(editText.text, 0)
		controller.reconstructNgbContextAtCursor()
		assertThat(jtui.ngbContextForTest().first).isEqualTo(JTUI.NGB_BOS_CTX)
		// Mid-text entry after a space: the preceding word carries the context.
		editText.setText("It's hard ")
		android.text.Selection.setSelection(editText.text, editText.text.length)
		controller.reconstructNgbContextAtCursor()
		assertThat(jtui.ngbContextForTest().first).isEqualTo("hard")
		// Comma-class boundary stays fail-soft null.
		editText.setText("we got, ")
		android.text.Selection.setSelection(editText.text, editText.text.length)
		controller.reconstructNgbContextAtCursor()
		assertThat(jtui.ngbContextForTest().first).isNull()
	}

	@Test fun `page-pick after a wake pull-in is finalized - the texting resume flow`() {
		// A word committed earlier stands in the editor; the keyboard state was
		// reset (sleep boundary). Select on the empty keyboard pulls the word
		// back in; the user pages and letter-picks a replacement.
		type("organs")
		pickFlatIndex(indexOfWord("organs"))
		assertThat(editor().trim().lowercase()).isEqualTo("organs")
		// Sleep boundary: keyboard state resets; editor text stays.
		jtui.resetJTUI(false, callUpdateUi = true, resetToStartPage = true)
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		assertThat(jtui.selectionListForTest()).isEmpty()
		// Wake: Select on the empty keyboard triggers the manual pull-in.
		trace.add("=== manual pull-in ===")
		val pulledIn = controller.handleManualPullIn()
		org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		trace.add("=== after pull-in: editor='${editor()}' composing='${composingRegion()}' pulledIn=$pulledIn ===")
		assertThat(pulledIn).isTrue()
		// The word is composing again with its list rebuilt around it.
		assertThat(editor().trim().lowercase()).isEqualTo("organs")
		val list = jtui.selectionListForTest()
		assertThat(list.size).isAtLeast(8)
		// A DIFFERENT word on page 1 — the replacement the user pages to.
		val pickIdx = (2 until 8).first {
			val t = list[it]["type"] as? String
			t in listOf("X", "L", "E", "2", "N") &&
				(list[it]["output"] as? String)?.equals("organs", ignoreCase = true) == false
		}
		val pickedWord = (list[pickIdx]["output"] as String).lowercase()
		// Step Select until paged mode engages (replay may have left the
		// selection anywhere in the head), then letter-pick the cell.
		var guard = 0
		while (jtui.pagedSelectPageForTest() == null && guard < 5) {
			press(selectPos)
			guard++
		}
		assertThat(jtui.pagedSelectPageForTest()).isEqualTo(0)
		press(pagePos[pagedOrdinalForAmbig.indexOf(pickIdx - 2)])
		trace.add("=== after replace pick: editor='${editor()}' composing='${composingRegion()}' picked='$pickedWord' ===")
		if (editor().trim().lowercase() != pickedWord || composingRegion() != null) {
			println(trace.takeLast(120).joinToString("\n"))
		}
		// The picked replacement is FINALIZED: exactly one committed word, no
		// composing region left behind.
		assertThat(editor().trim().lowercase()).isEqualTo(pickedWord)
		assertThat(composingRegion()).isNull()
		// And the next word appends after an autospace instead of replacing.
		type("to")
		assertThat(editor().lowercase()).startsWith("$pickedWord ")
		assertThat(editor().lowercase()).doesNotContain("${pickedWord}to")
	}

	@Test fun `force-head promotion commits cleanly at the editor level`() {
		// The Dev-ladder flow Cliff exercises with the crib sheet: at the
		// Select press the demoted FTS is promoted to slot 1 and selected; the
		// next ambig key commits it (AK-after-SEL).
		repo.putInt(
			org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE,
			2,
		)
		type("organs")
		press(selectPos) // promotion reorders the list mid-engagement, selects organs
		trace.add("=== after force-head select: editor='${editor()}' composing='${composingRegion()}' ===")
		val keys = jtui.wordKeySequence("to")!!
		press(pagePos[keys.first()]) // AK finalizes the promoted word
		trace.add("=== after AK commit: editor='${editor()}' composing='${composingRegion()}' ===")
		if (!editor().trim().lowercase().startsWith("organs")) {
			println(trace.takeLast(120).joinToString("\n"))
		}
		assertThat(editor().trim().lowercase().startsWith("organs")).isTrue()
		keys.drop(1).forEach { press(pagePos[it]) }
		assertThat(editor().lowercase()).startsWith("organs ")
		assertThat(editor().lowercase()).doesNotContain("organsto")
	}

	@Test fun `force-page1 promotion then paged pick commits cleanly at the editor level`() {
		repo.putInt(
			org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE,
			1,
		)
		type("organs")
		press(selectPos) // promotion moves organs to the leading page-1 cell
		val idx = indexOfWord("organs")
		assertThat(idx).isAtLeast(2)
		// Continue the engagement into paged mode and pick it.
		var guard = 0
		while (jtui.pagedSelectPageForTest() == null && guard < 5) {
			press(selectPos)
			guard++
		}
		press(pagePos[pagedOrdinalForAmbig.indexOf(idx - 2)])
		trace.add("=== after force-page1 pick: editor='${editor()}' composing='${composingRegion()}' ===")
		if (editor().trim().lowercase() != "organs" || composingRegion() != null) {
			println(trace.takeLast(120).joinToString("\n"))
		}
		assertThat(editor().trim().lowercase()).isEqualTo("organs")
		assertThat(composingRegion()).isNull()
		type("to")
		assertThat(editor().lowercase()).startsWith("organs ")
	}

	@Test fun `finalized flag - a lingering composing region on the picked word is sealed, not replaced`() {
		// Cliff's device symptom (2026-08-11): the picked word sits in the
		// editor as a COMPOSING region; because the pick cleared the key
		// buffer, no Select activation remains and finalize-on-ambig never
		// fires — so the next word's autospace commitText(" ") REPLACES the
		// region and the word vanishes. The finalized flag re-seals it.
		type("organs")
		pickFlatIndex(indexOfWord("organs"))
		assertThat(editor().trim().lowercase()).isEqualTo("organs")
		// Recreate the lingering state: the editor re-marks the picked word as
		// composing while the controller believes everything is sealed.
		ic.setComposingRegion(0, editor().length)
		assertThat(composingRegion()).isNotNull()
		// Next word: without the flag this REPLACED the picked word.
		type("to")
		trace.add("=== after next word: editor='${editor()}' composing='${composingRegion()}' ===")
		if (!editor().lowercase().startsWith("organs ")) {
			println(trace.takeLast(90).joinToString("\n"))
		}
		assertThat(editor().lowercase()).startsWith("organs ")
		assertThat(editor().lowercase()).doesNotContain("organsto")
	}

	/** Minimal ImeTextCallbacks for the integration (mirrors the controller
	 *  test fake; JTUI-backed where the composing pipeline reads state). */
	private inner class IntegrationImeTextCallbacks : ImeTextCallbacks {
		override val isJtuiInitialized: Boolean = true
		override val isInputViewShown: Boolean = true
		override fun getAmbiguousSequenceLength(): Int = jtui.getAmbiguousSequenceLength()
		override fun getShiftState(): Boolean = false
		override fun getIsManualShift(): Boolean = false
		override fun getAutoCapReason(): AutoCapReason = AutoCapReason.NONE
		override fun isSpellingMode(): Boolean = false
		override fun isNumericMode(): Boolean = false
		override fun getSpeakState(): Boolean = false
		override fun selectedCandidateSuppressesLeadingSpace(): Boolean = false
		override fun setShiftState(shift: Boolean, isManual: Boolean, skipUpdate: Boolean, autoReason: AutoCapReason) {}
		override fun setSpeakState(enabled: Boolean, announce: Boolean) {}
		override fun resetJTUI(shiftState: Boolean, callUpdate: Boolean, isManualShift: Boolean, resetToStartPage: Boolean, autoCapReason: AutoCapReason, preserveAutospace: Boolean) {}
		override fun forceUpdateUi() {}
		override fun withUiSuppressed(block: () -> Unit) = block()
		override fun setSkipPostProcessingAfterPullIn(skip: Boolean) {}
		override fun getSelectKeyCount(): Int = jtui.getSelectKeyCount()
		override fun isWordProducible(word: String): Boolean = jtui.isWordProducible(word)
		override fun getCurrentPage(): String = ""
		override fun clearManualShift() {}
		override fun hasPendingAmbiguityWithoutSelect(): Boolean = false
		override fun getImmedCharCount(): Int = 0
		override fun clearImmedCharCount() {}

		// Real pull-in replay (mirrors ImeTextCallbacksImpl.replayWordInJtui).
		override fun replayWordInJtui(word: String, capitalize: Boolean, isAllUpper: Boolean, selectFirstOnMatch: Boolean, suppressUIUpdate: Boolean, precedingText: String?): Boolean {
			var forceSelectedCandidate = false
			jtui.withUiSuppressed {
				jtui.setCurrentPageToStartingPage()
				jtui.resetJTUI(capitalize, false, autoCapReason = AutoCapReason.NONE)
				jtui.ngbReconstructContext(precedingText)
				val keys = jtui.mapWordToKeyIndices(word)
				if (keys != null) {
					keys.forEach { k -> jtui.pressAmbiguousKeyNumberSilently(k, true) }
					if (!jtui.ngbSpanPending()) {
						// Page-group rule: relocate a page-buried word to slot 2
						// (mirrors ImeTextCallbacksImpl).
						val targetIndex = jtui.pullInTargetIndex(word)
						val shouldSelectFirst = selectFirstOnMatch && targetIndex == 0
						if (targetIndex > 0 || shouldSelectFirst) {
							var index = 0
							repeat(targetIndex + 1) { jtui.pressSelectSilently(index++ < targetIndex) }
							forceSelectedCandidate = true
						}
					}
				}
			}
			return forceSelectedCandidate
		}
		override fun wordKeyIndices(word: String): List<Int>? = jtui.mapWordToKeyIndices(word)
		override fun forceUpdateUi(skipComposing: Boolean, selectedCandidate: String?, topCandidate: String?) {
			jtui.forceUpdateUi(skipComposing, selectedCandidate, topCandidate)
		}
		override fun reconstructNgbContext(precedingText: String?) {
			jtui.ngbReconstructContext(precedingText)
		}
		override fun probeNgbSpan(word: String, precedingText: String?, followingText: String): Int = jtui.ngbSpanProbe(word, precedingText, followingText)
		override fun activateNgbSpan(): String? = jtui.ngbSpanActivate()
		override var isEditMode: Boolean = false
		override var isNewInputSession: Boolean = false
		override val phraseFlow: PhraseFlowHandle? = null
		override fun speakQueued(text: String) {}
		override fun reuseOrCreatePendingSelection(text: String, type: String) = TtsController.PendingSelection(text, type)
		override fun speakIfEnabled(pending: TtsController.PendingSelection?): Boolean = false
		override fun cancelScheduledSpeak(clearPending: Boolean) {}
		override fun rememberLastSpoken(text: String, type: String) {}
		override fun getPendingSelection(): TtsController.PendingSelection? = null
		override fun setPendingSelection(pending: TtsController.PendingSelection?) {}
		override fun recordSpellNumeric(text: String) {}
		override fun flushSpellNumericIfNeeded(reason: String) {}
		override fun isKnownAbbreviation(word: String): Boolean = false
		override fun isKnownDomain(word: String): Boolean = false
		override fun isAlphaChar(c: Char): Boolean = c.isLetter()
		override fun isWordDbChar(c: Char): Boolean = c in setOf('\'', '’', '-', '.')
		override fun sendDpadEvent(direction: Int, movementMode: Int) {}
		override fun isLineBreak(c: Char): Boolean = TextUtils.isLineBreak(c)
		override fun getAutoRestore(): Boolean = true
		override fun errorNotification() {}
		override fun errorBeep(force: Boolean) {}
		override fun debugLog(message: String) {
			trace.add(message)
		}
	}
}
