package org.continuouspath.justtype.logic

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.text.style.LineBackgroundSpan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.continuouspath.justtype.BuildConfig
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.Constants.DIACRITIC_SCOPE_CURRENT
import org.continuouspath.justtype.Constants.KEY_CASETYPE_DEFERRED_EXPAND
import org.continuouspath.justtype.Constants.KEY_CASETYPE_EXPAND_DELAY_MS
import org.continuouspath.justtype.Constants.KEY_CASETYPE_VARIANTS_ENABLED
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_SIZE_RATIO
import org.continuouspath.justtype.Constants.KEY_SHOW_ABBREV_IN_SELECTION
import org.continuouspath.justtype.Constants.KEY_SHOW_PHRASE_IN_SELECTION
import org.continuouspath.justtype.Constants.KEY_SPEAK_OUTPUT_SENTENCE
import org.continuouspath.justtype.Constants.KEY_SPEAK_PUNCTUATION
import org.continuouspath.justtype.Constants.KEY_SPEAK_SELECTED_KEY
import org.continuouspath.justtype.Constants.KEY_SPEAK_SELECTED_WORD
import org.continuouspath.justtype.Constants.KEY_SPELL_DIACRITIC_SCOPE
import org.continuouspath.justtype.Constants.KEY_TURKISH_AZERI_CASE_OVERRIDE
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_ENABLED
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_MAX_FREQ
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_MIN_FREQ
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_MODULE_MASK
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_USECOUNT_MAX
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_USECOUNT_MAX_JT
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACTIVE_MASK
import org.continuouspath.justtype.Constants.KEY_VOCAB_FREQ_FILTER_ENABLED
import org.continuouspath.justtype.Constants.KEY_VOCAB_INCLUDE_CUSTOM_WORDS
import org.continuouspath.justtype.Constants.KEY_VOCAB_INCLUDE_JUSTTYPE
import org.continuouspath.justtype.Constants.KEY_VOCAB_INCLUDE_PHRASES
import org.continuouspath.justtype.Constants.KEY_VOCAB_MIN_FREQ_SELECTION
import org.continuouspath.justtype.Constants.KEY_VOCAB_PROMOTE_IMPORTED
import org.continuouspath.justtype.EnglishRegion
import org.continuouspath.justtype.LanguageRegistry
import org.continuouspath.justtype.PosEncoding
import org.continuouspath.justtype.R
import org.continuouspath.justtype.SpanishRegion
import org.continuouspath.justtype.activity.DeveloperSettingsActivity
import org.continuouspath.justtype.data.PhraseEntry
import org.continuouspath.justtype.data.PhraseRepository
import org.continuouspath.justtype.hierarchy.AllSymbolsModeController
import org.continuouspath.justtype.hierarchy.DiacriticDerivation
import org.continuouspath.justtype.hierarchy.DiacriticGroup
import org.continuouspath.justtype.hierarchy.DiacriticVariant
import org.continuouspath.justtype.hierarchy.HierarchyLoader
import org.continuouspath.justtype.hierarchy.InsertMode
import org.continuouspath.justtype.hierarchy.LayoutSlot
import org.continuouspath.justtype.hierarchy.SPATIAL_PAGE_KEYS
import org.continuouspath.justtype.hierarchy.SymbolSlotView
import org.continuouspath.justtype.hierarchy.VariantLayout
import org.continuouspath.justtype.hierarchy.assignSpatial
import org.continuouspath.justtype.hierarchy.layoutVariants
import org.continuouspath.justtype.hierarchy.toPreviewGrid
import org.continuouspath.justtype.hierarchy.variantsForCharSet
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.effectiveInputMethod
import org.continuouspath.justtype.settings.getBoolean
import org.json.JSONObject
import java.io.File
import java.util.Locale

// Core constants mirroring main.py
private const val NumberOfKeys = 8

private const val KF_Term = 0
private const val KF_Ambig = 1
private const val KF_Select = 2
private const val KF_Undo = 3
private const val KF_Snug = 4
private const val KF_Immed = 5
private const val KF_GoToPage = 6
private const val KF_Shift = 7
private const val KF_SymbolMode = 8
private const val KF_ClearInput = 9
private const val KF_DeleteWord = 10
private const val KF_Speech = 11
private const val KF_SpeakSentence = 12
private const val KF_Enter = 13

@Suppress("UnusedPrivateProperty", "TopLevelPropertyNaming")
private const val KF_Back = 14 // reserved for future BACK key restoration; previously used in Navigation page slot 0
private const val KF_ScrollUp = 15
private const val KF_ScrollDown = 16
private const val KF_CapsLock = 17

// 18 reserved (formerly KF_Menu — Menu button removed in v4.1 Navigate page)

private const val KF_Speak = 20
private const val KF_SaveLast = 21
private const val KF_SpeakNextSentence = 22
private const val KF_SpeakLastSelection = 23
private const val KF_EndSentence = 24
private const val KF_SpaceIfNeeded = 25
private const val KF_RefreshUI = 26
private const val KF_ImmedNoSpace = 27
private const val KF_ClearOutput = 28
private const val KF_CheckAddCustomWord = 29
private const val KF_CreateCustomWord = 30
private const val KF_ImmedSpell = 31
private const val KF_SpellDelete = 32
private const val KF_NumericDelete = 33
private const val KF_ClearAmbig = 34
private const val KF_AddNewPhrase = 35
private const val KF_CancelNewPhrase = 36
private const val KF_PhraseDone = 37
private const val KF_FinishNumericString = 38
private const val KF_DeleteChar = 39
private const val KF_RefreshKeyboardView = 40
private const val KF_SpaceIfNeededMulti = 41
private const val KF_EndSentenceMulti = 42
private const val KF_CursorLeft = 43
private const val KF_CursorRight = 44
private const val KF_CursorUp = 45
private const val KF_CursorDown = 46
private const val KF_SelectText = 47
private const val KF_BackToMain = 48
private const val KF_CycleCursorMode = 49
private const val KF_Cut = 50
private const val KF_Copy = 51
private const val KF_Paste = 52
private const val KF_EditUndo = 53
private const val KF_SpeakSelectionOrSentence = 54
private const val KF_CaseToTitle = 55
private const val KF_CaseToUpper = 56
private const val KF_CaseToLower = 57
private const val KF_CaseToSentence = 58
private const val KF_EditWord = 59
private const val KF_SetMarkA = 60
private const val KF_SetMarkB = 61
private const val KF_JumpToMarkA = 62
private const val KF_JumpToMarkB = 63

// ── Settings navigation ──────────────────────────────────────
private const val KF_EnterSettings = 70
private const val KF_SettingsKey = 71 // arg = key index 0–7, dispatched to controller

private const val KF_OpenNavigationKeyboard = 72
private const val KF_BackToCaller = 73
private const val KF_SpellShiftCycle = 74
private const val KF_SpellSetAccumulate = 75 // arg: 1 = accumulate (ADD NEW WORD), 0 = emit-each (LETTER/SYMBOL)
private const val KF_AllSymbolsDescend = 76 // arg: absolute child index of the current ALL SYMBOLS level
private const val KF_AllSymbolsAscend = 77 // ALL SYMBOLS: up one level; at root, exit to the entry page
private const val KF_AllSymbolsMore = 78 // ALL SYMBOLS: page to the next six entries of the current level
private const val KF_AllSymbolsPick = 79 // arg: symbol string — verbatim insert (no case-fold)

const val CASE_MODE_WORD = 0
const val CASE_MODE_SENTENCE = 1
const val CASE_TYPE_TITLE = 0
const val CASE_TYPE_UPPER = 1
const val CASE_TYPE_LOWER = 2

private const val StartingPage = "Main"

// v4.1 layouts (slides 1 + 2). Key-index → letter group preserves the prior code's keyNum
// assignments — only period migrates into is.kw (Optimized); the rest are
// unchanged-or-reordered. X stays on ojvhdx: layout-analyzer measured the x-to-tr-'p move at
// E 0.042684 -> 0.043639 (+2.2% extra Selects), and per-language layouts make the
// empty-cell-next-to-vowel spell-mode reservation unnecessary for English. Alphabetic:
// apostrophe joins abcd', hyphen joins efgh-, period joins stu. Slot positions within each
// key are set via ambigGrid in definePages.
// Bottom-center display cell of a key's 9-cell grid — always letter-free (letters use
// cells 0,2,3,5,6,8), so tone-keystroke languages render their tone mark there.
private const val TONE_LABEL_CELL = 7

// TAV tone-form display column, in FILL-PRIORITY order: bottom-center, then
// top-center, then center — the center cell is used only when all three forms
// show (and is drawn nudged left, clear of mid-right slot labels). Whatever
// cells are used, the column reads top→bottom in key-face order.
private val TAV_TONE_FORM_FILL = listOf(TONE_LABEL_CELL, 1, 4)
private const val PAGED_WORDS_PER_PAGE = 6

// Family expansion presents ALL stem matches (as many page groups as
// needed); this cap only guards pathological short stems.
private const val FAMILY_MAX_CANDIDATES = 60

// Select-key label sentinel: the next press opens/advances a page group,
// rendered as the page-group glyph by SquareButton (stringly matched, the
// Symbols1/Functions1 convention).
private const val SELECT_PAGE_GROUP_SENTINEL = "PageGroup1"

// Paging engages only when the FIRST page holds at least this many items
// (Cliff, 2026-08-08): a 1-3 item "page" costs extra Selects versus plain
// list cycling AND renders as a single column that reads like a gapped
// list; four-plus items force the second column — visibly a page group.
// Lists up to (listed slots + 3) stay pure list mode.
private const val PAGED_MIN_FIRST_PAGE = 4

/** Prediction-trust multiplier for the unified block ordering (mfit optimum
 *  ~19; the resulting order is insensitive across M_c 5..100 because the
 *  freq banding and the max() against the unigram count absorb the scale). */
private const val NGB_BLOCK_M_C = 19.0

/** Cold-start FTS floor: dominates the never-used recency gap (351.9) plus
 *  the worst within-band extension differential (~17) with margin. */
private const val COLD_FTS_FLOOR = 380.0

// Longest left-column word that still clears the preview's 200dp tab stop; longer
// words stack their pair on two lines instead of overlapping the right column.
private const val PAGED_PREVIEW_STACK_CHARS = 14

// Paged-selection word order follows the page-key column reading order (Keys 0,3,5
// down the left, 2,4,7 down the right): internal ambig key number -> word ordinal.
private val PAGED_ORDINAL_FOR_AMBIG = intArrayOf(0, 3, 1, 4, 2, 5)

// Spell page key -> the 9-grid cell at that spatial position (inverse of cellToPos).
private val CELL_FOR_PAGE_KEY = mapOf(0 to 0, 2 to 2, 3 to 3, 4 to 5, 5 to 6, 7 to 8)

private val lettersPerKeyOptimized = listOf("gemz", "tr-'p", "is.kw", "lufcy", "banq", "ojvhdx")
private val lettersPerKeyAlpha = listOf("abcd'", "nopqr", "ef-gh", "stu.", "ijklm", "vwxyz")

// 9-cell grids matching lettersPerKeyOptimized — the built-in fallback when the active
// language's DB carries no layoutJson metadata (see LayoutSpec). English's grids are
// hand-tuned (v4.1 slides), so they are spelled out rather than derived.
private val builtinOptimizedGrids = listOf(
	listOf("g", "", "e", "", "", "", "m", "", "z"),
	listOf("t", "", "r", "-", "", "'", "p", "", ""),
	listOf("i", "", "s", "", "", ".", "k", "", "w"),
	listOf("l", "", "u", "f", "", "", "c", "", "y"),
	listOf("b", "", "a", "", "", "", "n", "", "q"),
	listOf("o", "", "j", "x", "", "v", "h", "", "d"),
)

// 9-cell grids matching lettersPerKeyAlpha — fallback when the layout spec has no alpha section.
private val builtinAlphaGrids = listOf(
	listOf("a", "", "b", "", "", "'", "c", "", "d"),
	listOf("n", "", "o", "p", "", "", "q", "", "r"),
	listOf("e", "", "f", "", "", "-", "g", "", "h"),
	listOf("s", "", "t", "", "", "", "u", "", "."),
	listOf("i", "", "j", "", "", "k", "l", "", "m"),
	listOf("v", "", "w", "x", "", "y", "z", "", ""),
)

enum class LayoutMode { Alphabetical, Optimized }

/**
 * Visual state of the Main-page center square (sls.md "Center-square
 * surface", Cliff 2026-08-13). The square mirrors the provisionally
 * committed text; the state drives the treatment: EMPTY = nothing
 * provisional (resting states, banners, non-Main pages — default look),
 * NEUTRAL = live provisional word while typing, SIGNAL = the confidence
 * signal vouches for that word (dramatic styling), ARMED = a Select
 * activation holds a provisional commit (next AK finalizes it).
 */
enum class CenterSquareState { EMPTY, NEUTRAL, SIGNAL, ARMED }

// UI snapshot for binding
data class JTUISnapshot(
	val outputBuffer: String,
	val ambigBuffer: String,
	val selectionListBuffers: List<CharSequence>, // Dynamic column buffers for selection list
	val keyHistoryBuffer: String,
	val centerSpace: String,
	val centerSquareState: CenterSquareState = CenterSquareState.EMPTY,
	val keyLabels: List<String>,
	val keyLabelGrids: List<List<String>>, // 9-cell grids per key (row-major)
	val ambigKeyLabels: List<List<String>>, // 9-cell grids for ambiguous key history
	val baseOutput: String,
	var speechString: String,
	var customWord: String?,
	val speakState: Boolean,
	val shiftState: Boolean,
	var isManualShift: Boolean,
	val isSpellingMode: Boolean,
	val topCandidateOutput: String?,
	val selectedCandidateOutput: String?,
	val topCandidateType: String?,
	val selectedCandidateType: String?,
	val highlightNextLetters: Boolean,
	val nextLetterHints: Set<Char>,
	val accentNextLetterHints: Set<Char>,
	// Slot-cell label -> full char string ("15_" -> "12345_"), so hint graying can
	// match a slot's ACTUAL characters, not just the glyphs of its elided label.
	val slotCellChars: Map<String, String> = emptyMap(),
	// Next-tone-mark prediction (internal keyNums whose tone completes a candidate);
	// null = inactive (tone-less language / empty sequence / hints off / TAV,
	// where the center column carries the per-vowel tone-form display in
	// keyLabelGrids).
	val nextToneKeys: Set<Int>? = null,
	// TAV: internal keyNums whose grids currently carry tone forms. SquareButton
	// nudges a form left ONLY when a wide slot label ("60.", "15_") occupies the
	// cell to its right and spills toward it; forms elsewhere stay centered.
	val tavToneFormKeys: Set<Int> = emptySet(),
	val ambiguousKeyMask: List<Boolean>,
	val highlightedKeyIndices: Set<Int> = emptySet(),
	val currentSelectionIndex: Int? = null, // Selection list index for scroll-into-view
	// Word whose chars the key-history keys highlight (char i ↔ history key i);
	// null when no word-type candidate is selected or previewed.
	val historyHighlightWord: String? = null,
)

// UnDo context for comprehensive UnDo processing
data class UndoContext(
	val ambigSeqLenBefore: Int, // Length before UnDo
	val currentSelection: Int?,
	val listFunctionCount: Int, // Number of list functions associated with first ambig key in sequence
	val hadComposingBefore: Boolean, // Had composing text before
	val willHaveComposingAfter: Boolean, // Will have composing after
)

// Host-callback bundle; grouping into deps interfaces is planned follow-up extraction work.
@Suppress("LongParameterList")
class JTUI(
	private val onAddNewPhrase: (String) -> Unit,
	private val onCancelNewPhrase: () -> Unit = {},
	private val onPhraseDone: () -> Unit = {},
	private val phraseRepository: PhraseRepository,
	private val sayInterruptible: (String) -> Unit,
	private val sayQueued: (String) -> Unit,
	// UI ("device") speech: prompts, key names, and announcements addressed TO the user, spoken in
	// the UI language's voice (see UiVoice) — distinct from the typing voice above, which is the
	// user's own composed text. Default no-op keeps test/bare constructions unchanged.
	private val sayUiInterruptible: (String) -> Unit = {},
	private val sayUiQueued: (String) -> Unit = {},
	private val onUiUpdate: (JTUISnapshot) -> Unit,
	private val onImmediateOutput: (String) -> Unit,
	private val onSpellingOutput: (String) -> Unit,
	private val onSpeakSentence: (Boolean) -> Unit,
	private val onSpeakNextSentence: () -> Unit,
	private val onSpeakLastSelection: () -> Unit = {},
	private val onFinalizeText: (String) -> Unit,
	private val onNumericOutput: (String) -> Unit,
	private val onAmbiguousSequenceStart: () -> Unit,
	private val onSpaceIfNeeded: (Boolean) -> Unit,
	private val onUndoPressed: (UndoContext) -> Unit,
	private val onDeleteWord: () -> Unit,
	private val onDeleteChar: () -> Unit,
	private val assets: AssetManager,
	private val filesDir: File,
	private val prefs: SettingsRepository,
	context: Context,
	private val onEnterKey: () -> Unit,
	private val onErrorBeep: (Boolean) -> Unit = {},
	// NGB-D confidence signal: the top list item is probably the intended
	// word — stop typing and look. Fired at most once per top per word.
	private val onConfidenceSignal: () -> Unit = {},
	// C3 span collapse: the IME restores the span tail as committed text
	// with only the tapped word composing (setComposingRegion dance).
	private val onNgbSpanCollapse: () -> Unit = {},
	// Periodic "N keystrokes could have been saved" prompt while the signal
	// is off (could-have-saved counter keeps running by design).
	private val onCouldHaveSavedPrompt: (Long) -> Unit = {},
	// Notifies the IME to suppress (true) or resume (false) autospacing for the whole
	// LETTER/SYMBOL MODE session. See ImeTextController.suppressAutospaceMode.
	private val onSetAutospaceSuppressed: (Boolean) -> Unit = {},
	private val onCustomWordIntercept: (String) -> Boolean = { false },
	private val onDataMutation: () -> Unit = {},
	private val onCursorMove: (direction: Int, hasActiveAmbig: Boolean, movementMode: Int, isSelecting: Boolean) -> Unit = { _, _, _, _ -> },
	private val onScroll: (direction: Int) -> Unit = {},
	private val onBookmark: (action: Int, isSelecting: Boolean) -> Unit = { _, _ -> },
	private val onManualPullIn: () -> Unit = {},
	private val onEditModeExit: () -> Unit = {},
	private val onClipboardAction: (Int) -> Unit = {},
	private val onSpeakSelectionOrSentence: () -> Unit = {},
	private val onCaseChange: (caseType: Int, caseMode: Int) -> Unit = { _, _ -> },
	private val onEditingDelete: () -> Unit = {},
	private val onSettingsEnter: () -> Unit = {},
	private val onSettingsExit: () -> Unit = {},
	private val onSettingsDisplayUpdate: (org.continuouspath.justtype.settings.SettingsDisplayState) -> Unit = {},
	private val onSettingsApply: (key: String, value: Any) -> Unit = { _, _ -> },
	private val onSettingsAction: (actionId: String) -> Unit = {},
	private val settingsLangpackServices: org.continuouspath.justtype.langpack.LangpackKeyboardServices? = null,
	private val onOpenNavigationKeyboard: () -> Unit = {},
	private val wldDispatcher: CoroutineDispatcher? = null,
	private val wldScope: CoroutineScope? = null,
	private val log: (DebugCategory, String) -> Unit = { _, _ -> },
	// AccessiblePrompt intercept: when a prompt is showing, the UnDo key
	// dismisses the prompt instead of performing its normal undo action.
	// Both lambdas default to no-op so tests / non-IME use sites are
	// unaffected. See [AccessiblePrompt] for the prompt component itself.
	private val isAccessiblePromptShowing: () -> Boolean = { false },
	private val dismissAccessiblePrompt: () -> Unit = {},
) {
	companion object {
		// C2 reconstruction: trailing syllables considered for context
		// re-derivation (greedy window, matches recognizer maxUnitSyls + slack).
		private const val NGB_CTX_WINDOW_SYLS = 8

		// BOS prediction row (sls.md, Cliff 2026-08-11): the reserved context
		// key for sentence starts — the literal two-character sequence
		// backslash+n, matching the {Lang}Ngb.txt row. Tokens are letter-only,
		// so it can never collide with a real word. Sentence-final characters
		// switch the context to it; ¿¡ open sentences and leave it standing.
		const val NGB_BOS_CTX = "\\n"
		private const val NGB_SENTENCE_FINAL = ".!?…"

		// NGB-D confidence bookkeeping (see plan.md "NGB-D").
		private const val NGB_CONF_SAVE_EVERY = 25
		private const val NGB_CONF_PROMPT_EVERY_KEYS = 300L
		private const val NGB_CONF_PROMPT_MIN_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000
		private const val NGB_CONF_PHRASE_SCORE = 1000.0

		// Select-behavior substrate (sls.md "Adaptive select-behavior mechanisms").
		// EWMA decay per episode: half-life ~69 episodes, so the evidence — and any
		// adaptation later driven by it — stays reversible as user strategies change.
		private const val SEL_STATS_DECAY = 0.99

		// Dev force-enable ladder (KEY_SELECT_BEHAVIOR_MODE). ADAPTIVE is reserved:
		// it observes only until the ramp ships (staging (iv) — needs the field
		// distributions this substrate records first).
		const val SELECT_BEHAVIOR_OBSERVE = 0
		const val SELECT_BEHAVIOR_FORCE_PAGE1 = 1
		const val SELECT_BEHAVIOR_FORCE_HEAD = 2
		const val SELECT_BEHAVIOR_ADAPTIVE = 3

		const val CURSOR_LEFT = 0
		const val CURSOR_RIGHT = 1
		const val CURSOR_UP = 2
		const val CURSOR_DOWN = 3

		const val MOVEMENT_CHARACTER_LINE = 0
		const val MOVEMENT_WORD_SENTENCE = 1
		const val MOVEMENT_PARAGRAPH_PAGE = 2
		const val MOVEMENT_BOOKMARK = 3

		const val BOOKMARK_SET_A = 60
		const val BOOKMARK_SET_B = 61
		const val BOOKMARK_JUMP_A = 62
		const val BOOKMARK_JUMP_B = 63

		// A page is part of a "sub-mode" (Spell / Symbols / Numbers and all their inner + dynamic
		// drill pages) when its name carries one of these prefixes. Used for return-to-caller
		// routing: entering a sub-mode from a caller page (Main / Navigation / LetterSymbol)
		// records the caller in state.subModeCaller so KF_BackToCaller routes back to Main (caller
		// was Main or Navigation — the menu was just transit) or to LetterSymbol (keep composing).
		// Prefix-based so dynamic pages (e.g. SpellVar_*) count as in-sub-mode and don't reset the
		// caller mid-session.
		fun isSubModePage(page: String): Boolean = page.startsWith("Spell") || page.startsWith("Symbols") || page.startsWith("Numbers") || page.startsWith("NumPunct") || page == "AllSymbols"

		/** Spoken (TTS) names for punctuation characters. */
		private val PUNCTUATION_NAME_RES = mapOf(
			'!' to R.string.speech_punct_exclamation,
			'?' to R.string.speech_punct_question,
			'.' to R.string.speech_punct_period,
			',' to R.string.speech_punct_comma,
			';' to R.string.speech_punct_semicolon,
			':' to R.string.speech_punct_colon,
			'\'' to R.string.speech_punct_apostrophe,
			'"' to R.string.speech_punct_double_quote,
			'-' to R.string.speech_punct_hyphen,
			'_' to R.string.speech_punct_underscore,
			'/' to R.string.speech_punct_slash,
			'\\' to R.string.speech_punct_backslash,
			'(' to R.string.speech_punct_left_paren,
			')' to R.string.speech_punct_right_paren,
			'[' to R.string.speech_punct_left_bracket,
			']' to R.string.speech_punct_right_bracket,
			'{' to R.string.speech_punct_left_brace,
			'}' to R.string.speech_punct_right_brace,
			'@' to R.string.speech_punct_at_sign,
			'#' to R.string.speech_punct_hash,
			'$' to R.string.speech_punct_dollar,
			'%' to R.string.speech_punct_percent,
			'^' to R.string.speech_punct_caret,
			'&' to R.string.speech_punct_ampersand,
			'*' to R.string.speech_punct_asterisk,
			'+' to R.string.speech_punct_plus,
			'=' to R.string.speech_punct_equals,
			'<' to R.string.speech_punct_less_than,
			'>' to R.string.speech_punct_greater_than,
			'`' to R.string.speech_punct_backtick,
			'~' to R.string.speech_punct_tilde,
			'|' to R.string.speech_punct_pipe,
		)
	}

	/** Mutable context for locale changes at runtime. */
	private var context: Context = context

	/**
	 * Replaces the context used for string resolution and rebuilds all
	 * page labels.  Call after changing the UI language preference.
	 */
	fun updateLocaleContext(newContext: Context) {
		context = newContext
		definePages()
		if (isInSettingsMode) {
			// Stay in Settings Mode — refresh the overlay with updated locale labels
			// and pass the new context so key labels resolve in the new locale
			settingsController?.refreshDisplay(newContext)
		} else {
			updateKeysAndSelection()
		}
	}

	private lateinit var wordDb: WordDb
	private lateinit var customDb: WordDb
	private val customDbFile by lazy { File(filesDir, "CustomDb.db") }

	// ── NGB context prediction (docs/.plans/ngram/engine-spec.md) ──
	// One pool fetch per committed word; per-keystroke work is in-memory
	// key-prefix filtering. Context is fail-soft: any doubt -> null -> the
	// list behaves exactly as before NGB (and SELECT-at-root keeps pull-in).
	private var ngbEngine: NgbEngine? = null
	private var ngbRecognizer: NgbRecognizer? = null
	private var ngbLang: String = ""
	private var ngbTraits: LanguageTraits = LanguageTraits.WORD_BASED

	/** Set by the IME per editor: no learning in password/sensitive fields. */
	@Volatile var ngbLearningSuppressed: Boolean = false

	private data class NgbPoolEntry(
		val syls: List<String>, // raw lowercase syllables (pool identity)
		val display: String, // case-preferred composed output ("Việt Nam")
		val canonical: String, // dedup/case-recompute basis
		val casePref: CasePreference?, // single-syllable predictions only
		val eff: Long,
		val multi: Boolean,
		val keySeq: List<Int>,
		val userUsed: Boolean,
		// Beyond-POOL_SIZE single (deep row rank): alive only when fully typed.
		val deep: Boolean,
	)

	private var ngbPool: List<NgbPoolEntry> = emptyList()

	private fun ngbActive(): Boolean = !prefs.getBoolean(Constants.KEY_BATTERY_SAVER_MODE, false) &&
		prefs.getBoolean(Constants.KEY_NGB_PREDICTIONS, true) &&
		ngbEngine?.hasData == true

	/**
	 * A word reached the output: update context + refetch the pool. [completedWord]
	 * true when the commit closed a whole word (multi-syllable unit or phrase) —
	 * Cliff's entry-state gate: its final syllable cannot also start the next word.
	 */
	private fun ngbOnWordCommitted(output: String, completedWord: Boolean) {
		ngbSpanReset() // C3 session ends with the word
		if (!ngbActive()) return
		val wasBos = state.ngbContext == NGB_BOS_CTX
		ngbConfOnCommit(output)
		val syls = output.trim().split(' ', '\n').filter { it.isNotEmpty() }.map { it.lowercase(Locale.getDefault()) }
		state.ngbContext = syls.lastOrNull()
		state.ngbGateOpen = !completedWord
		// Recognition-based learning: transitions on the shipped table's own
		// segmentation basis; manual typing of a unit promotes it exactly like
		// accepting it would. Suppressed in password/sensitive fields.
		if (!ngbLearningSuppressed && syls.isNotEmpty()) {
			// Habitual sentence openers personalize the BOS row like any
			// learned bigram — the recognizer's own stream has no BOS notion,
			// so the transition is bumped directly.
			if (wasBos) customDb.ngbUserBump(ngbLang, NGB_BOS_CTX, syls.joinToString(" "))
			ngbRecognizer?.commit(syls)?.forEach { (ctx, target) ->
				customDb.ngbUserBump(ngbLang, ctx, target.joinToString(" "))
			}
		}
		ngbRefreshPool()
	}

	/** Sentence boundary reached IN-FLOW (sentence-final terminator or Enter):
	 *  predictions switch to the BOS row — the sentence-opener distribution,
	 *  the most frequent context there is (sls.md: web 24.8% / AAC 43.1% of
	 *  openers sit in the top-8 window). Languages without a "\n" table row
	 *  simply get an empty pool — same as the old cleared context. */
	private fun ngbSentenceBoundary() {
		ngbTrace { "sentence boundary; ngbActive=${ngbActive()}" }
		if (!ngbActive()) return
		state.ngbContext = NGB_BOS_CTX
		state.ngbGateOpen = true
		ngbRefreshPool()
		if (state.ambiguousKeySequence.isEmpty() && state.currentSelection == null && state.systemSelectionList.isEmpty()) {
			updateSelectionList(listOf(applyShiftAndCaps(ngbZeroKEntries())), null)
		}
	}

	/** Post-emission hook for immediate output: sentence-final punctuation
	 *  moves the context to BOS. ¿¡ and other non-final characters leave the
	 *  context alone (¿ OPENS a sentence — an already-standing BOS stays). */
	private fun maybeNgbSentenceBoundary(emitted: String) {
		val last = emitted.trimEnd().lastOrNull() ?: return
		if (last in NGB_SENTENCE_FINAL) ngbSentenceBoundary()
	}

	private fun ngbClearContext() {
		// Seal + learn the final transition of the run before dropping state.
		if (!ngbLearningSuppressed) {
			ngbRecognizer?.flush()?.forEach { (ctx, target) ->
				customDb.ngbUserBump(ngbLang, ctx, target.joinToString(" "))
			}
		} else {
			ngbRecognizer?.clear()
		}
		// Word abandoned without commit: drop its confidence snapshots too, and
		// take the natural boundary to flush counters + personalized weights.
		ngbConfidence.reset()
		ngbConfPending = null
		if (::customDb.isInitialized) ngbConfFlush()
		// C3: a PENDING probe survives this clear — the pull-in flow probes
		// BEFORE its replay resets JTUI, and activation follows right after.
		if (ngbSpanMode != NgbSpanMode.PENDING) ngbSpanReset()
		ngbTrace { "clear ctx (was=${state.ngbContext})" }
		state.ngbContext = null
		ngbPool = emptyList()
	}

	/**
	 * C2 delete-context reconstruction (plan.md round-1 item 9): re-derive the
	 * prediction context from the committed text preceding an edit — called by
	 * the IME on completion of ANY pull-in and after whole-word deletion,
	 * where resetJTUI just performed the fail-soft clear. The trailing
	 * syllables are greedy-matched against the unit inventory on the same
	 * basis as learning: trailing multi-syllable unit => gate CLOSED, bare
	 * syllable => OPEN. Fail-soft stands: null/blank text, trailing
	 * punctuation, or no recognizer => stay contextless. The learning stream
	 * is NOT reseeded — those transitions were already sealed at their
	 * original commits; reconstruction is display/prediction state only.
	 */
	fun ngbReconstructContext(precedingText: String?) {
		ngbTrace {
			val tail = precedingText?.let { "'…${it.takeLast(12).replace("\n", "\\n")}'" } ?: "null"
			"reconstruct from $tail; ngbActive=${ngbActive()}"
		}
		if (!ngbActive()) return
		val derived = ngbDeriveFromText(precedingText)
		if (derived == null) {
			state.ngbContext = null
			ngbPool = emptyList()
			return
		}
		state.ngbContext = derived.first
		state.ngbGateOpen = derived.second
		ngbRefreshPool()
		// The delete flows land at an empty sequence: surface the zero-K window
		// immediately (wldSelection only RETURNS a list — apply directly, the
		// same way the paged final commit repopulates it). The pull-in flow
		// replays keys right after and rebuilds its own list.
		if (state.ambiguousKeySequence.isEmpty() && state.currentSelection == null && state.systemSelectionList.isEmpty()) {
			updateSelectionList(listOf(applyShiftAndCaps(ngbZeroKEntries())), null)
		}
	}

	/** Trailing letter-token window of [text] -> (context syllable, gateOpen),
	 *  the BOS sentinel at a KNOWN sentence boundary, or null when the
	 *  boundary breaks context without being one (comma-class punctuation,
	 *  digits, unknown state). */
	private fun ngbDeriveFromText(text: String?): Pair<String, Boolean>? {
		val rec = ngbRecognizer ?: return null
		if (text == null) return null // UNKNOWN editor state: fail-soft null
		val trimmed = text.trimEnd()
		// KNOWN sentence boundaries serve the BOS row: an empty field, a
		// trailing newline (Enter), or sentence-final punctuation.
		val trailingWhitespace = text.substring(trimmed.length)
		if (trimmed.isEmpty() || '\n' in trailingWhitespace || trimmed.last() in NGB_SENTENCE_FINAL) {
			return NGB_BOS_CTX to true
		}
		if (!trimmed.last().isLetter()) return null
		val tokens = trimmed.split(' ', '\n').filter { it.isNotEmpty() }
		// Trailing run of pure-letter tokens; numbers/punctuation break it.
		val syls = tokens
			.takeLastWhile { tok -> tok.all { it.isLetter() } }
			.takeLast(NGB_CTX_WINDOW_SYLS)
			.map { it.lowercase(Locale.getDefault()) }
		if (syls.isEmpty()) return null
		return rec.deriveContext(syls)
	}

	/** (context, gateOpen) — C2 test observability. */
	@androidx.annotation.VisibleForTesting
	fun ngbContextForTest(): Pair<String?, Boolean> = state.ngbContext to state.ngbGateOpen

	@androidx.annotation.VisibleForTesting
	fun ngbTraitsForTest(): LanguageTraits = ngbTraits

	@androidx.annotation.VisibleForTesting
	fun ngbActiveForTest(): Boolean = ngbActive()

	@androidx.annotation.VisibleForTesting
	fun wordUseCountForTest(word: String): Int = runCatching { wordDb.getOrCreateStats(word, 7).useCount }.getOrDefault(0)

	/** The trie-resolved ranking stats the unified block ordering consults
	 *  (useCount, lastUseTime) plus the active DB clock — cold-start probes. */
	fun ngbRankingStatsForTest(word: String): Triple<Int, Int, Int>? = wld.rankingStatsFor(word)?.let { Triple(it.useCount, it.lastUseTime, wordDb.relativeTime()) }

	/** Exact-case row existence, read-only (orphan-row regression checks). */
	@androidx.annotation.VisibleForTesting
	fun wordRowExistsForTest(word: String): Boolean = wordDb.getWordIDByWord(word) != null

	/** Current NGB pool display strings, in eff order (case-display checks). */
	@androidx.annotation.VisibleForTesting
	fun ngbPoolDisplaysForTest(): List<String> = ngbPool.map { it.display }

	/** Block displays for a typed key sequence (unified-order checks; the
	 *  harness defers single-key searches, so tests call the assembly directly). */
	@androidx.annotation.VisibleForTesting
	fun ngbBlockDisplaysForTest(keys: List<Int>): List<String> = ngbHeadEntries(keys).mapNotNull { it["display"] as? String }

	// ── C3: pull-in NGB-span expansion (plan.md "C3", Cliff spec) ──
	// Tapping a word that BEGINS a known span matching the following text
	// offers the whole span: spans grow FORWARD only from the tapped word,
	// so every offered span is prefix-compatible with the restored key
	// sequence (block semantics: the sequence is the filter, not the
	// identity). Leaving the span group in either direction collapses to
	// the ordinary pull-in state of the tapped word alone.

	private enum class NgbSpanMode { NONE, PENDING, ACTIVE, COLLAPSED }

	private var ngbSpanMode = NgbSpanMode.NONE
	private var ngbSpanEntries: List<MutableMap<String, Any?>> = emptyList() // longest first
	private var ngbSpanTapped: String = ""

	private fun ngbSpanReset() {
		ngbSpanMode = NgbSpanMode.NONE
		ngbSpanEntries = emptyList()
		ngbSpanTapped = ""
	}

	/** True between a successful span probe and its activation — the pull-in
	 *  replay must not select-step (the activation sets the selection). */
	fun ngbSpanPending(): Boolean = ngbSpanMode == NgbSpanMode.PENDING

	/** The list head for the current NGB mode: while a C3 span session is
	 *  ACTIVE the head is the text-matching spans (longest first); PENDING
	 *  keeps them hidden so the pull-in replay lands on the plain list
	 *  (activation follows); after the collapse the NGB entries stay out for
	 *  the rest of this word's edit (Cliff spec 5-7); otherwise the ordinary
	 *  prediction block. */
	private fun ngbHeadEntries(keys: List<Int>): List<MutableMap<String, Any?>> = when (ngbSpanMode) {
		NgbSpanMode.ACTIVE -> ngbSpanEntries.map { HashMap(it) }
		NgbSpanMode.PENDING, NgbSpanMode.COLLAPSED -> emptyList()
		NgbSpanMode.NONE -> ngbPredictionEntries(keys)
	}

	/**
	 * Probe for spans starting at the tapped word that match the FOLLOWING
	 * committed text verbatim (canonical lowercase, word-boundary after).
	 * Sources: the unit inventory keyed by first syllable (context-free —
	 * covers document start) and the C2-reconstructed pool's remainders
	 * (covers taps on a MIDDLE syllable of a larger unit). Returns the
	 * character count the longest span consumes BEYOND the tapped word
	 * (0 = no span; standard pull-in proceeds).
	 */
	fun ngbSpanProbe(tapped: String, precedingText: String?, followingText: String): Int {
		ngbSpanReset()
		if (!ngbActive()) return 0
		ngbReconstructContext(precedingText)
		val tappedLower = tapped.lowercase(Locale.getDefault())
		val matches = LinkedHashMap<String, Int>() // lower span -> extra chars
		fun tryMatch(syls: List<String>) {
			if (syls.size < 2 || syls[0] != tappedLower) return
			val expected = syls.drop(1).joinToString(separator = "") { " $it" }
			if (followingText.length < expected.length) return
			if (followingText.substring(0, expected.length).lowercase(Locale.getDefault()) != expected) return
			if (followingText.getOrNull(expected.length)?.isLetter() == true) return
			matches.putIfAbsent(syls.joinToString(" "), expected.length)
		}
		runCatching { wordDb.ngbUnitsByFirstSyl(tappedLower) }.getOrDefault(emptyList())
			.forEach { tryMatch(it.syls.split(' ')) }
		ngbPool.forEach { tryMatch(it.syls) }
		if (matches.isEmpty()) return 0
		ngbSpanTapped = tapped
		ngbSpanEntries = matches.entries
			.sortedByDescending { it.value }
			.map { (_, extra) ->
				// Display the span AS IT STANDS IN THE FIELD (original casing).
				val original = tapped + followingText.substring(0, extra)
				mutableMapOf<String, Any?>(
					"type" to "N",
					"display" to original,
					"output" to original,
					"canonicalOutput" to original.lowercase(Locale.getDefault()),
					"casePreference" to null,
					"forcedCaseForm" to WordCaseForm.ORIGINAL,
					"preserveOriginalCase" to true,
					"countOfOccurrence" to Int.MAX_VALUE,
					"POS" to "",
					"FreqClass" to 1,
					"ClassMask" to 0L,
					"UseCount" to 0,
					"UseTime" to JSONObject.NULL,
					"keysRemaining" to 0,
					"caseCount" to 1,
					"ngbMulti" to true,
					"ngbUserUsed" to false,
					"ngbSpan" to true,
				)
			}
		ngbSpanMode = NgbSpanMode.PENDING
		return matches.values.max()
	}

	/**
	 * Called after the pull-in replay of the tapped word: switch the list to
	 * span presentation and SELECT the longest text match (Cliff spec 4).
	 * A pre-selection undo snapshot makes the first UnDo step off the group
	 * head, triggering the collapse (spec 5). Returns the selected span
	 * output for the composing pipeline, or null when no span is pending.
	 */
	fun ngbSpanActivate(): String? {
		if (ngbSpanMode != NgbSpanMode.PENDING || ngbSpanEntries.isEmpty()) {
			ngbSpanReset()
			return null
		}
		ngbSpanMode = NgbSpanMode.ACTIVE
		ngbSpanRebuildList()
		undoStack.addLast(
			state.copy(
				keyHistory = ArrayList(state.keyHistory),
				systemSelectionList = ArrayList(state.systemSelectionList),
				ambiguousKeySequence = ArrayList(state.ambiguousKeySequence),
			),
		)
		state.currentSelection = 0
		state.selectionGen = selectionGeneration
		return selectionList.getOrNull(0)?.get("output") as? String
	}

	/**
	 * Leave the span group (spec 5/6): every NGB entry collapses out of the
	 * list, the tapped word's ordinary list returns with the tapped word's
	 * entry highlighted, and the IME restores the span tail as committed
	 * text with only the tapped word composing.
	 */
	private fun ngbSpanCollapse() {
		if (ngbSpanMode != NgbSpanMode.ACTIVE) return
		ngbSpanMode = NgbSpanMode.COLLAPSED
		onNgbSpanCollapse()
		ngbSpanRebuildList()
		val tappedLower = ngbSpanTapped.lowercase(Locale.getDefault())
		val idx = selectionList.indexOfFirst {
			((it["canonicalOutput"] ?: it["output"]) as? String)?.lowercase(Locale.getDefault()) == tappedLower
		}
		state.currentSelection = idx.takeIf { it >= 0 }
		state.selectionGen = selectionGeneration
		updateUi(false)
	}

	/** Rebuild + apply the selection list for the current sequence under the
	 *  current span mode (wldSelection only RETURNS; apply directly). */
	private fun ngbSpanRebuildList() {
		val adjusted = wldSelection().map { original ->
			val mutable = original.toMutableMap()
			(mutable["output"] as? String)?.let { mutable["canonicalOutput"] = it }
			mutable
		}
		updateSelectionList(listOf(state.systemSelectionList, applyShiftAndCaps(adjusted)), null)
	}

	private fun ngbRefreshPool() {
		val engine = ngbEngine
		val ctx = state.ngbContext
		if (engine == null || ctx == null || !ngbActive()) {
			ngbPool = emptyList()
			return
		}
		ngbTrace { "refresh pool ctx='${ctx.replace("\n", "\\n")}' gate=${state.ngbGateOpen}" }
		ngbPool = engine.poolFor(ctx, state.ngbGateOpen).mapNotNull { p ->
			val seq = mutableListOf<Int>()
			for (s in p.syls) {
				val ks = wld.translateToKeysOrNull(s) ?: return@mapNotNull null
				seq.addAll(ks)
			}
			// Display case, in priority order: canonical dictionary orthography
			// when the unit carries one ("Hồ Chí Minh" — per-syllable counts
			// CANNOT reconstruct proper-noun caps: chí is lower-dominant as a
			// common word); else per-syllable DB case preferences ("Nam").
			val display: String
			val pref: CasePreference?
			if (p.displaySyls != p.syls) {
				display = p.displaySyls.joinToString(" ")
				pref = null
			} else {
				val cased = p.syls.map { ngbPreferredCaseForm(it) }
				display = cased.joinToString(" ") { it.first }
				pref = if (p.syls.size == 1) cased[0].second else null
			}
			NgbPoolEntry(
				syls = p.syls,
				display = display,
				canonical = if (p.syls.size == 1) p.syls[0] else display,
				casePref = pref,
				eff = p.eff,
				multi = p.multi,
				keySeq = seq,
				userUsed = p.userUsed,
				deep = p.deep,
			)
		}
	}

	/** (preferred-cased form, its CasePreference) for one in-vocab syllable,
	 *  from the same DB case counts that drive the word's own entries.
	 *  Resolved case-insensitively through the trie: prediction targets are
	 *  lowercase corpus tokens while the stored row may be case-fixed ("I",
	 *  "China"). The old exact-word getOrCreateStats lookup missed those rows
	 *  AND fabricated fc7 lowercase orphan rows whose lower-dominant counts
	 *  made predictions render lowercase forever, clobbered the real entry's
	 *  useCount at every trie rebuild, and split block/trie dedup ("i" vs
	 *  "I") — the word then appeared twice, correctly cased only mid-list
	 *  (Cliff's "i above I" failure, 2026-08-10). */
	private fun ngbPreferredCaseForm(syl: String): Pair<String, CasePreference?> {
		val (stored, stats) = wld.caseStatsFor(syl) ?: return syl to null
		val top = maxOf(
			stats.lowerCaseCount,
			stats.titleCaseCount,
			stats.upperCaseCount,
			stats.originalCaseCount,
		)
		val form = when {
			top <= 0 || top == stats.lowerCaseCount -> WordCaseForm.LOWER
			top == stats.titleCaseCount -> WordCaseForm.TITLE
			top == stats.upperCaseCount -> WordCaseForm.UPPER
			else -> WordCaseForm.ORIGINAL
		}
		val pref = CasePreference(
			preferredForm = form,
			lowerCount = stats.lowerCaseCount,
			titleCount = stats.titleCaseCount,
			upperCount = stats.upperCaseCount,
			originalCount = stats.originalCaseCount,
		)
		val display = if (form == WordCaseForm.ORIGINAL) stored else applyCaseForm(syl, form)
		return display to pref
	}

	/** Selection-list map for one prediction (type "N"). */
	private fun ngbEntry(e: NgbPoolEntry): MutableMap<String, Any?> = mutableMapOf(
		"type" to "N",
		"display" to e.display,
		"output" to e.display,
		"canonicalOutput" to e.canonical,
		"casePreference" to e.casePref,
		"forcedCaseForm" to (e.casePref?.preferredForm ?: WordCaseForm.ORIGINAL),
		"preserveOriginalCase" to e.multi,
		"countOfOccurrence" to e.eff.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
		"POS" to "",
		"FreqClass" to 1,
		"ClassMask" to 0L,
		"UseCount" to 0,
		"UseTime" to JSONObject.NULL,
		"keysRemaining" to 0,
		// applyShiftAndCaps DROPS word-typed entries with caseCount <= 0.
		"caseCount" to 1,
		"ngbMulti" to e.multi,
		// Select-behavior substrate: full key length of the prediction, so a
		// dual-source row (word fully typed, trie row deduped away) is still
		// recognizable as fully typed at the current sequence length.
		"ngbKeySeqLen" to e.keySeq.size,
		// Consumed by the visual pass: the user's own previously-used
		// prediction gets its distinctive (subtle) marking.
		"ngbUserUsed" to e.userUsed,
	)

	/**
	 * After the first SELECT on a sequence the word is chosen and the next AK
	 * starts a NEW word — so the keyboard renders its initial (between-words)
	 * state: list-function badges on, tone forms off (Cliff, issue 7).
	 */
	private fun postSelectionState(): Boolean = state.ambiguousKeySequence.isNotEmpty() && state.currentSelection != null

	/** Zero-keystroke window: the pool's head, shown before any AK (word start). */
	private fun ngbZeroKEntries(): List<MutableMap<String, Any?>> = ngbPool.filter { !it.deep }.take(NgbEngine.ZERO_K_SIZE).map { ngbEntry(it) }

	/** The prediction block for a typed sequence: key-prefix-compatible pool
	 *  entries, rank order, block-sized (anchor=0: rendered ABOVE letter-exact). */
	private fun ngbPredictionEntries(keys: List<Int>): List<MutableMap<String, Any?>> {
		if (ngbPool.isEmpty()) return emptyList()
		// Deep entries (row rank beyond POOL_SIZE) join only once FULLY TYPED
		// — "a deep-ranked follower matches the prefix exactly" (Cliff's
		// pool-cap catch; the "also say" incident: say at row rank 84).
		val alive = ngbPool.filter {
			it.keySeq.size >= keys.size &&
				it.keySeq.subList(0, keys.size) == keys &&
				(!it.deep || it.keySeq.size == keys.size)
		}
		if (alive.isEmpty()) return emptyList()
		return orderBlockUnified(alive, keys.size)
			.take(NgbEngine.BLOCK_SIZE)
			.map { ngbEntry(it) }
	}

	/**
	 * Unified single-formula ordering for the prediction block (sls.md; the
	 * "would I" incident, Cliff 2026-08-10). Raw effective-count order ignored
	 * two signals the head must respect: letter certainty (a fully-typed word
	 * vs predictions needing 2-6 more keys) and the user's own familiarity
	 * with the word. Each entry flows through the ONE sortmetric —
	 * freqMetric over band(max(rawFreq, M_c x eff)) for single-word entries
	 * (units keep M_c x eff: no unigram exists), seqMetric over the keys
	 * still UNTYPED, and the word's GLOBAL use/recency (first component for
	 * units). Ties keep eff order (stable sort). Sim-validated: order is
	 * insensitive to M_c across 5..100 (banding + max absorb it) and KSPLS
	 * is unchanged while familiar fully-typed words reclaim slot 1.
	 */
	private fun orderBlockUnified(alive: List<NgbPoolEntry>, typedKeys: Int): List<NgbPoolEntry> {
		// Above the top band the banded freqMetric saturates and would throw
		// away contextual-evidence gaps (VN chúc→mừng vs anh, sls.md band-
		// quantization note): extend it continuously past band 1 — a monotone
		// -(freqAdd·freqMult)·log2(source/40000) boost per doubling, matching
		// the first-band spacing at the boundary.
		val slope = getF("freq_add_weight", 1.0f) * getF("freq_mult_weight", 1.25f)
		return alive.sortedBy { p ->
			val entry = wld.rankingStatsFor(p.syls.first())
			val boosted = p.eff.toDouble() * NGB_BLOCK_M_C
			val source = if (p.multi || entry == null) boosted else maxOf(boosted, entry.rawFreq.toDouble())
			val topBand = source > 40000.0
			val fc = if (topBand) {
				1
			} else {
				WordDb.computeFreqClass(source.toInt())
			}
			// seq counts keys remaining of the CURRENT word (first syllable)
			// only: letter certainty about the word in progress. A unit's
			// later words are future input, not uncertainty — length is a
			// layout problem, never a ranking factor (sls.md).
			val firstKeys = wld.translateToKeysOrNull(p.syls.first())?.size ?: p.keySeq.size
			val candidate = WLD.CandidateEntry(
				wordID = entry?.wordID ?: 0,
				lowerWord = p.syls.first(),
				freqClass = fc,
				useCount = entry?.useCount ?: 0,
				lastUseTime = entry?.lastUseTime ?: 0,
				classMask = 0L,
				posEncoded = 0,
				isLowFrequency = false,
				keysRemaining = (firstKeys - typedKeys).coerceAtLeast(0),
			)
			var m = computeSortMetrics(candidate, false, 0L, false).sortMetric
			if (topBand) {
				m -= slope * kotlin.math.log2(source / 40000.0)
			}
			// The cold-start FTS floor keys off freqClass 1 — for block rows
			// that class is CONTEXTUALLY banded (M_c x eff), which would let
			// a junk opener like BOS->"th" ride the floor. Eligibility is the
			// word's OWN unigram band: reverse the floor otherwise.
			if (fc == 1 &&
				candidate.keysRemaining == 0 &&
				candidate.useCount == 0 &&
				(entry == null || entry.rawFreq <= 40_000)
			) {
				m += COLD_FTS_FLOOR
			}
			m
		}
	}

	// ── NGB-D selection-confidence signal (plan.md "NGB-D") ──
	// p-hat = P(top list item == intended word), from a small calibrated
	// logistic model. Snapshots + labeling + the could-have-saved counter run
	// whenever NGB is active; the user-facing signal is separately gated.
	private val ngbConfidence = NgbConfidence()
	private var ngbConfPending: NgbConfCandidate? = null
	private var ngbConfCommits = 0
	private var ngbConfSavedPending = 0L
	private var ngbConfDistractedPending = 0L

	private fun ngbConfidenceOn(): Boolean = ngbActive() && prefs.getBoolean(Constants.KEY_NGB_CONFIDENCE_ENABLED, false)

	/** Mechanism B (sls.md): theta is PLACED from the shadow counters at the
	 *  user's precision target (seeded from the offline sweep until evidence
	 *  accrues). The Dev toggle turns placement off -> the raw Dev slider. */
	private fun ngbConfThetaAdaptive(): Boolean = prefs.getBoolean(DeveloperSettingsActivity.KEY_NGB_CONF_THETA_ADAPTIVE, true)

	private fun ngbConfDevTheta(): Double = prefs.getInt(Constants.KEY_NGB_CONFIDENCE_THRESHOLD, 65).coerceIn(20, 95) / 100.0

	private fun ngbConfPrecisionTarget(): Double = prefs.getInt(Constants.KEY_NGB_CONF_PRECISION, 85).coerceIn(60, 95) / 100.0

	private fun ngbConfThreshold(): Double = if (ngbConfThetaAdaptive()) ngbConfidence.thetaFor(ngbConfPrecisionTarget()) else ngbConfDevTheta()

	/**
	 * Confidence features for the list state just built — the top candidate's
	 * posterior share of the alive candidate field, every score on the corpus
	 * count scale (predictions by effective count, words by rawFreq,
	 * dual-source by max). Mirrors the simulator's scored_field, so the
	 * offline calibration transfers.
	 */
	private fun ngbConfCandidateFor(
		keys: List<Int>,
		hasNgbBlock: Boolean,
		phraseMatches: List<Map<String, Any?>>,
		expandedEntries: List<MutableMap<String, Any?>>,
		displayCandidates: List<WLD.CandidateEntry>,
	): NgbConfCandidate? {
		if (!ngbActive()) return null
		val alivePreds = ngbPool.filter { it.keySeq.size >= keys.size && it.keySeq.subList(0, keys.size) == keys }
		val field = HashMap<String, Double>()
		for (c in displayCandidates) {
			field.merge(c.lowerWord, c.rawFreq.coerceAtLeast(1).toDouble(), ::maxOf)
		}
		for (p in alivePreds) {
			field.merge(p.canonical.lowercase(Locale.getDefault()), p.eff.toDouble(), ::maxOf)
		}
		for (ph in phraseMatches) {
			(ph["output"] as? String)?.let { field.merge(it.lowercase(Locale.getDefault()), NGB_CONF_PHRASE_SCORE, ::maxOf) }
		}
		// Top mirrors finalCandidates order: prediction block, phrases, words.
		val top: Pair<String, Boolean>? = when {
			hasNgbBlock -> alivePreds.first().canonical.lowercase(Locale.getDefault()) to true
			phraseMatches.isNotEmpty() -> (phraseMatches.first()["output"] as? String)?.lowercase(Locale.getDefault())?.to(false)
			else -> {
				val e = expandedEntries.firstOrNull()
				((e?.get("canonicalOutput") ?: e?.get("output")) as? String)?.lowercase(Locale.getDefault())?.to(false)
			}
		}
		val total = field.values.sum()
		if (top == null || total <= 0.0) return null
		return NgbConfCandidate(
			posterior = (field[top.first] ?: 1.0) / total,
			keystrokes = keys.size,
			topIsPred = top.second,
			ctxValid = ngbPool.isNotEmpty(),
			topLower = top.first,
		)
	}

	/** Consume the freshest list state in [updateUi]: record the observation
	 *  and fire the signal when p-hat clears the user threshold. Skipped while
	 *  the user is already engaging the list (selection or paged mode). */
	private fun ngbConfObserve(topCandidate: String?) {
		val pending = ngbConfPending ?: return
		ngbConfPending = null
		if (topCandidate == null || state.currentSelection != null || state.pagedSelectPage != null) return
		if (state.ambiguousKeySequence.size != pending.keystrokes) return
		val fire = ngbConfidence.observe(
			pending.posterior,
			pending.keystrokes,
			pending.topIsPred,
			pending.ctxValid,
			pending.topLower,
			ngbConfThreshold(),
		)
		ngbConfLastObservationForTest = Triple(pending.posterior, pending.keystrokes, fire)
		ngbConfLastFired = fire && ngbConfidenceOn()
		if (ngbConfLastFired) onConfidenceSignal()
	}

	/** Commit funnel: label this word's snapshots (free supervision), update
	 *  the could-have-saved / distracted counter pair (both run regardless of
	 *  the enable state — full disclosure), and periodically persist weights.
	 *  Saved keystrokes self-correct while the signal is ON: accepting a
	 *  correct signal ends the word at that state, leaving nothing unsaved. */
	private fun ngbConfOnCommit(output: String) {
		val outcome = ngbConfidence.onCommit(
			output,
			ngbConfThreshold(),
			learn = !ngbLearningSuppressed,
			// Weight SGD frozen while shadow placement drives theta, so p-hat
			// drift stays attributable to one mechanism. The shadow counters
			// themselves always accrue (the commit confirms every would-fire
			// — no user reaction needed, hidden signal included).
			sgd = !ngbConfThetaAdaptive(),
		)
		ngbConfCommits += 1
		if (outcome.savedKeystrokes > 0) ngbConfSavedPending += outcome.savedKeystrokes
		if (outcome.distractedSignals > 0) ngbConfDistractedPending += outcome.distractedSignals
		if (ngbConfCommits % NGB_CONF_SAVE_EVERY == 0) ngbConfFlush()
	}

	/** Flush pending counters + weights (batch: called every
	 *  [NGB_CONF_SAVE_EVERY] commits and at context boundaries). */
	private fun ngbConfFlush() {
		if (ngbConfSavedPending > 0) {
			val total = prefs.getLong(Constants.KEY_NGB_CONF_SAVED_KEYS, 0L) + ngbConfSavedPending
			ngbConfSavedPending = 0L
			prefs.putLong(Constants.KEY_NGB_CONF_SAVED_KEYS, total)
			maybePromptCouldHaveSaved(total)
		}
		if (ngbConfDistractedPending > 0) {
			prefs.putLong(
				Constants.KEY_NGB_CONF_DISTRACTED,
				prefs.getLong(Constants.KEY_NGB_CONF_DISTRACTED, 0L) + ngbConfDistractedPending,
			)
			ngbConfDistractedPending = 0L
		}
		if (ngbConfCommits > 0) {
			runCatching { customDb.ngbConfSaveWeights(ngbLang, ngbConfidence.exportWeights()) }
		}
	}

	private fun maybePromptCouldHaveSaved(total: Long) {
		if (ngbConfidenceOn()) return
		val prompted = prefs.getLong(Constants.KEY_NGB_CONF_PROMPTED_SAVED, 0L)
		if (total - prompted < NGB_CONF_PROMPT_EVERY_KEYS) return
		val now = System.currentTimeMillis()
		if (now - prefs.getLong(Constants.KEY_NGB_CONF_LAST_PROMPT_MS, 0L) < NGB_CONF_PROMPT_MIN_INTERVAL_MS) return
		prefs.putLong(Constants.KEY_NGB_CONF_PROMPTED_SAVED, total)
		prefs.putLong(Constants.KEY_NGB_CONF_LAST_PROMPT_MS, now)
		onCouldHaveSavedPrompt(total)
	}

	// ── Select-behavior substrate (sls.md "Adaptive select-behavior mechanisms") ──
	// An EPISODE spans one list engagement: state is captured at the FIRST Select
	// press (the user's done-declaration in the strategy matrix), the outcome at
	// commit — or "abandon" when the list rebuilds underneath (user kept typing).
	// Counters are EWMA-decayed in the custom DB; observation runs ALWAYS, the
	// force modes only reorder. Field distributions gate any automatic behavior.

	/** State captured at the first Select press of an engagement.
	 *  [ftsState]: "m" = head miss (nothing fully typed in the head, a
	 *  fully-typed word demoted below it — the state mechanism A remedies);
	 *  "d" = a demoted fully-typed word exists but the head ALREADY shows a
	 *  fully-typed alternative (key ambiguity, e.g. us/uk — digging here is
	 *  necessity, not strategy, and promotion would be wrong); "n" = none. */
	private data class SelectEpisode(val signaled: Boolean, val ftsState: String)

	private var selEpisode: SelectEpisode? = null

	// True when the confidence signal actually fired (and was user-visible) for
	// the CURRENT list state. Reset on every list rebuild; set in ngbConfObserve.
	private var ngbConfLastFired = false

	private fun selectBehaviorMode(): Int = prefs.getInt(DeveloperSettingsActivity.KEY_SELECT_BEHAVIOR_MODE, SELECT_BEHAVIOR_OBSERVE)
		.coerceIn(SELECT_BEHAVIOR_OBSERVE, SELECT_BEHAVIOR_ADAPTIVE)

	/** True when the row is fully specified at the CURRENT sequence — the
	 *  "FTS" of the strategy matrix. Trie rows by keysRemaining (same
	 *  defaulting as [sortMetricTag]); single-word N rows by their full key
	 *  length ([ngbEntry]'s ngbKeySeqLen) matching the typed length — a
	 *  dual-source word whose trie row was deduped into the block still
	 *  counts. Units, phrases and TAV base rows never do. */
	private fun fullyTypedRow(item: Map<String, Any?>): Boolean {
		val type = item["type"] as? String
		return when (type) {
			"X", "L", "E", "2" ->
				((item["keysRemaining"] as? Int) ?: if (type == "L") 1 else 0) == 0
			"N" -> {
				val typedLen = state.ambiguousKeySequence.size
				typedLen > 0 &&
					item["ngbMulti"] != true &&
					(item["ngbKeySeqLen"] as? Int) == typedLen
			}
			else -> false
		}
	}

	/** First Select press of an engagement: record the episode state, then (force
	 *  ladder only) promote demoted fully-typed rows. Promotion is confined to
	 *  no-signal HEAD-MISS states — mechanism A never acts where the signal
	 *  already guided the user, nor where the head already offers a fully-typed
	 *  word (the blanket-FTS-tier variant measured catastrophic in the sim). */
	private fun selEpisodeBegin() {
		val boundary = pagedFirstRow()
		val headFts = (0 until boundary.coerceAtMost(selectionList.size))
			.any { fullyTypedRow(selectionList[it]) }
		val demoted = (boundary until selectionList.size).filter { fullyTypedRow(selectionList[it]) }
		val ftsState = when {
			demoted.isEmpty() -> "n"
			headFts -> "d"
			else -> "m"
		}
		selEpisode = SelectEpisode(signaled = ngbConfLastFired, ftsState = ftsState)
		if (ngbConfLastFired || ftsState != "m") return
		val insertAt = when (selectBehaviorMode()) {
			SELECT_BEHAVIOR_FORCE_PAGE1 -> boundary
			// Head: after the last list-function row, before the first word row.
			SELECT_BEHAVIOR_FORCE_HEAD -> selectionList.indexOfLast { (it["type"] as? String) == "P" } + 1
			else -> return // Observe + Adaptive (ramp not built): never reorder.
		}
		val list = selectionList.toMutableList()
		val moved = demoted.map { list[it] }
		demoted.asReversed().forEach { list.removeAt(it) }
		list.addAll(insertAt.coerceAtMost(list.size), moved)
		selectionList = list
		// Head rows pushed into the paged region may still be collapsed case
		// pairs — re-apply the paged-region normalization from the build path.
		if (pagedSelectionEnabled()) expandAlternatesFrom(pagedFirstRow())
	}

	/** Commit funnel for the episode: kind + depth of what was ultimately
	 *  picked. Kind F = fully specified as committed (trie OR dual-source
	 *  block row); N/PH/B = predictive/phrase/TAV harvest; I = incomplete. */
	private fun selEpisodeCommit(index: Int) {
		val ep = selEpisode ?: return
		selEpisode = null
		val sel = selectionList.getOrNull(index) ?: return
		val type = sel["type"] as? String ?: return
		val kind = when {
			type == "P" -> return // list-function pick: not a word outcome
			fullyTypedRow(sel) -> "F"
			type == "N" -> "N"
			type == "PH" -> "PH"
			type == "B" -> "B"
			else -> "I"
		}
		val boundary = pagedFirstRow()
		val depth = when {
			index < boundary -> "h"
			index < boundary + PAGED_WORDS_PER_PAGE -> "p1"
			else -> "pd"
		}
		selStatsWrite(ep, "$kind.$depth")
	}

	/** The list rebuilt (or cleared) under an open episode: the user moved on
	 *  without committing from it. */
	private fun selEpisodeAbandon() {
		val ep = selEpisode ?: return
		selEpisode = null
		selStatsWrite(ep, "abandon")
	}

	private fun selStatsWrite(ep: SelectEpisode, outcome: String) {
		if (!::customDb.isInitialized) return
		val stateKey = (if (ep.signaled) "s" else "ns") + "_" + ep.ftsState
		synchronized(vocabLock) {
			runCatching { customDb.selStatsRecord(ngbLang, "$stateKey:$outcome", SEL_STATS_DECAY) }
		}
	}

	// The Optimized layout in effect (from the active language's DB, or the pinned language's
	// registry copy), or null to use the built-in English layout. Set in init().
	private var activeLayoutSpec: LayoutSpec? = null

	/**
	 * The Optimized layout to use: the pinned language's (KEY_OPTIMIZED_LAYOUT_SOURCE) when set
	 * and available, else the active language's own [dbSpec]. Users who have memorized one
	 * optimized layout can keep it for every language they type in.
	 */
	private fun resolveLayoutSpec(dbSpec: LayoutSpec?): LayoutSpec? {
		val source = prefs.getString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, Constants.LAYOUT_SOURCE_MATCH)
		if (source != Constants.LAYOUT_SOURCE_MATCH) {
			val pinned = LanguageRegistry.load(prefs).firstOrNull { it.name == source }?.layoutJson
			if (!pinned.isNullOrEmpty()) {
				LayoutSpec.parse(pinned) { msg -> log(DebugCategory.Lifecycle, msg) }?.let { return it }
			}
			// English pinned before its DB ever opened: the built-in layout IS English's.
			if (source == Constants.TYPING_LANGUAGE_ENGLISH) return null
		}
		return dbSpec
	}

	// ── Derived base view ("Show accented characters on keys" OFF) ───────────────
	// Mixed-mapping layouts only (v1 with first-class accent letters): the accents are
	// stripped from the key faces and letter groups; the WLD's variant routing then folds
	// them onto their base letters' keys and the spell drill offers them under the base —
	// the exact v4 base-folding behavior on the v5 letter positions (+2.4% E for Spanish).
	// Tone-keystroke layouts (Vietnamese) never strip: their quality letters (ô ê ă...) are
	// diacritic-tree variants AND first-class letters by design.

	/** Layout chars that are diacritic variants of another on-keyboard char. */
	private fun strippableAccentChars(): Set<Char> {
		val spec = activeLayoutSpec ?: return emptySet()
		if (spec.tones != null) return emptySet()
		if (prefs.getBoolean(Constants.KEY_SHOW_ACCENTED_KEYS, true)) return emptySet()
		val letters = spec.lettersPerKey.flatMapTo(mutableSetOf()) { it.map(Char::lowercaseChar) }
		val tree = HierarchyLoader.get(assets).diacriticTree
		return tree.entries
			.filter { (base, _) -> base in letters }
			.flatMap { (_, group) -> group.variants.mapNotNull { it.char.firstOrNull()?.lowercaseChar() } }
			.filterTo(mutableSetOf()) { it in letters }
	}

	private fun optimizedLetters(): List<String> {
		val letters = activeLayoutSpec?.lettersPerKey ?: lettersPerKeyOptimized
		val strip = strippableAccentChars()
		if (strip.isEmpty()) return letters
		return letters.map { key -> key.filterNot { it.lowercaseChar() in strip } }
	}

	private fun optimizedGrids(): List<List<String>> {
		val grids = activeLayoutSpec?.grids ?: builtinOptimizedGrids
		val strip = strippableAccentChars()
		if (strip.isEmpty()) return grids
		return grids.map { g ->
			g.map { cell -> if (cell.length == 1 && cell.first().lowercaseChar() in strip) "" else cell }
		}
	}

	private fun alphaLetters(): List<String> = activeLayoutSpec?.alphaLettersPerKey ?: lettersPerKeyAlpha

	private fun alphaGrids(): List<List<String>> = activeLayoutSpec?.alphaGrids ?: builtinAlphaGrids

	/**
	 * Tone-keystroke encoding for the given mode (LayoutSpec formatVersion 2): marked char →
	 * (base letter, tone KEY NUMBER). Alphabetic mode uses the alpha section's tone keys —
	 * the same tones sit on different keys there. Empty for tone-less languages.
	 */
	private fun toneFoldForMode(mode: LayoutMode): Map<Char, Pair<Char, Int>> {
		val tones = activeLayoutSpec?.tones ?: return emptyMap()
		val keys = if (mode == LayoutMode.Alphabetical) {
			activeLayoutSpec?.alphaToneKeys ?: tones.keys
		} else {
			tones.keys
		}
		return buildMap {
			tones.fold.forEach { (marked, baseAndTone) ->
				keys[baseAndTone.second]?.let { keyNum -> put(marked, baseAndTone.first to keyNum) }
			}
		}
	}

	/**
	 * Renders a tone-keystroke key's mark in the free bottom-center display cell (7), styled
	 * per the user's tone-label preference (bare mark / VNI digit / Telex letter). No-op for
	 * tone-less languages, non-tone keys, or if the cell is somehow occupied. Effective in
	 * TAE only: TAV overrides cell 7 per keystroke with the per-vowel tone-form display
	 * (see renderKeyLabelGrids / computeTavToneFormLabels).
	 */
	private fun withToneLabel(grid: List<String>, keyNum: Int, alphaMode: Boolean): List<String> {
		val tones = activeLayoutSpec?.tones ?: return grid
		val keys = if (alphaMode) activeLayoutSpec?.alphaToneKeys ?: tones.keys else tones.keys
		val toneId = keys.entries.firstOrNull { it.value == keyNum }?.key ?: return grid
		val style = prefs.getString(Constants.KEY_TONE_LABEL_STYLE, Constants.TONE_LABEL_STYLE_MARK)
		val raw = tones.labels[style]?.get(toneId)
			?: tones.labels[Constants.TONE_LABEL_STYLE_MARK]?.get(toneId)
			?: return grid
		// A bare combining mark renders on a dotted circle for a stable standalone glyph.
		val label = if (raw.isNotEmpty() && Character.getType(raw[0]) == Character.NON_SPACING_MARK.toInt()) {
			"◌$raw"
		} else {
			raw
		}
		if (grid.getOrNull(TONE_LABEL_CELL)?.isNotEmpty() == true) return grid
		return grid.toMutableList().also { it[TONE_LABEL_CELL] = label }
	}

	private var _layoutMode: LayoutMode = LayoutMode.Alphabetical
	var layoutMode: LayoutMode
		get() = _layoutMode
		set(value) {
			if (_layoutMode == value) return
			_layoutMode = value
			lastLangFetchMask = 0L
			wld = WLD(
				lettersPerKey = if (value == LayoutMode.Alphabetical) alphaLetters() else optimizedLetters(),
				wordDb = wordDb,
				customDb = customDb,
				log = log,
				diacriticVariantsByBase = diacriticVariantsByBase,
				toneFoldToKey = toneFoldForMode(value),
				accentFallbackEnabled = accentFallbackEnabled(),
				toneAfterVowel = toneAfterVowelActive(),
			)
			wld.addPhraseEntries(phraseRepository.all())
			reloadVocabularyFromDb()
			definePages()
			updateKeysAndSelection()
		}

	// Set the backing field without triggering the heavy setter. Called during init() so the
	// first WLD + definePages use the persisted layout (avoids the Alphabetic flash — BUG #4).
	private fun initLayoutModeFromPrefs() {
		_layoutMode = if (prefs.getString(Constants.KEY_LAYOUT_MODE, Constants.MODE_OPT) == Constants.MODE_ALPHA) {
			LayoutMode.Alphabetical
		} else {
			LayoutMode.Optimized
		}
	}

	// base letter → every diacritic-variant char form (both cases), independent of display
	// settings, so the WLD can add/recall words containing any diacritic by routing each variant
	// to its base letter's key (BUG #3). Cached since the diacritic tree is immutable.
	private val diacriticVariantsByBase: Map<Char, List<Char>> by lazy {
		HierarchyLoader.get(assets).diacriticTree.entries.associate { (base, group) ->
			base to group.variants.flatMap { v ->
				buildList {
					v.char.firstOrNull()?.let { add(it) }
					v.upper?.firstOrNull()?.let { add(it) }
				}
			}
		}
	}
	private lateinit var wld: WLD

	// Hybrid accent fallback (mixed-mapping layouts): setting default ON; tone-keystroke (v2)
	// languages are excluded (their marked forms fold via tone keys, not accent keys), and WLD
	// self-disables when the layout has no explicit variant letters (English, Alphabetic).
	/** Tone-after-vowel entry active (tone languages only; exclusive with tone-at-end). */
	private fun toneAfterVowelActive(): Boolean = activeLayoutSpec?.tones != null &&
		prefs.getString(Constants.KEY_TONE_ENTRY_POSITION, Constants.TONE_ENTRY_END) ==
		Constants.TONE_ENTRY_AFTER_VOWEL

	// Fallback is OFF only when the user explicitly requires accented keys — and "require"
	// is only meaningful while accents are shown (the base view folds everything anyway,
	// making fallback entries structurally empty).
	private fun accentFallbackEnabled(): Boolean {
		if (activeLayoutSpec?.tones != null) return false
		val require = prefs.getBoolean(Constants.KEY_REQUIRE_ACCENTED_KEYS, false) &&
			prefs.getBoolean(Constants.KEY_SHOW_ACCENTED_KEYS, true)
		return !require
	}

	// Preference for showing word frequencies

	// Preference for next-letter hints
	var showNextLetterHints: Boolean = false

	// Dynamic column layout for selection list
	// Items per column (0 = single column, put all items in one column)
	var itemsPerColumn: Int = 0

	// Maximum columns allowed (0 = unlimited, constrained only by width)
	var maxColumns: Int = 0

	// Selection-list line height (px) for weighted column splits (0 = unknown)
	var selectionLineHeightPx: Int = 0

	// Selection list dimensions for image sizing (in pixels)
	var selectionListWidth: Int = 0
	var selectionListHeight: Int = 0

	// Selection list text size (px) — lets the paged preview measure words so its
	// two columns are laid out to actually fit the panel.
	var selectionListTextSizePx: Float = 0f

	private enum class PhraseFlowPhase { NONE, PHRASE, ABBREV }
	private var phraseFlowActive: Boolean = false
	private var phraseFlowPhase: PhraseFlowPhase = PhraseFlowPhase.NONE

	// Pages
	data class KeyDef(
		val label: List<String>?, // null if single label; otherwise 9 labels
		val singleLabel: String?,
		val display: String,
		val functions: List<Pair<Int, Any?>>, // (code,arg)
		val singleKeyPages: List<String> = emptyList(),
	)

	private val pages: MutableMap<String, List<KeyDef>> = mutableMapOf()

	// State
	private data class State(
		var currentPage: String = StartingPage,
		var previousPage: String = StartingPage,
		// The "caller" page that originally entered the current sub-mode (Spelling, Symbols,
		// Numbers and their inner pages). Set by setCurrentPage when transitioning from a
		// caller page (Main / Navigation / LetterSymbol) into a sub-mode; preserved across
		// navigation within the sub-mode; consumed by KF_BackToCaller for return-to-caller.
		var subModeCaller: String? = null,
		var currentSelection: Int? = null,
		var keyHistory: MutableList<KeyDef> = mutableListOf(),
		var systemSelectionList: MutableList<Map<String, Any?>> = mutableListOf(),
		var selectKeyCount: Int = 0,
		var ambiguousKeySequence: MutableList<KeyDef> = mutableListOf(),
		var emptyAmbigSequence: Boolean = false,
		// NGB context (docs/.plans/ngram/engine-spec.md): part of State so UnDo
		// restores it — deleting a keystroke must not lose the word's context
		// (Cliff, device review 2026-08-08, issue 6).
		var ngbContext: String? = null,
		var ngbGateOpen: Boolean = true,
		var outputString: String = "",
		var immedCharString: String = "",
		var numericString: String = "",
		var immedCharCount: Int = 0,
		var speechString: String = "",
		var customWordString: String = "",
		var shiftState: Boolean = true,
		var isManualShift: Boolean = false,
		var capsState: Boolean = false,
		var capsTempDisable: Boolean = false,
		var speakState: Boolean = true,
		var capitalizePending: Boolean = false,
		var autoCapReason: AutoCapReason = AutoCapReason.NONE,
		var pendingAutoCapReason: AutoCapReason = AutoCapReason.NONE,
		var isSpellingMode: Boolean = false,
		var forceFirstImmediateSpace: Boolean = false,
		// Two-Key Spell output mode. true = accumulate into customWordString and show as composing
		// text (ADD NEW WORD / phrase-abbreviation, finalized into the vocab on DONE). false =
		// commit each unambiguously-selected character immediately (LETTER/SYMBOL MODE — the user
		// is just typing characters into the field, with no vocab tracking).
		var spellAccumulate: Boolean = true,
		// Suppresses all autospacing while inside LETTER/SYMBOL MODE: only characters the user
		// explicitly selects should be emitted. Set on entry to the LetterSymbol page, cleared on
		// return to Main, where normal autospacing resumes.
		var suppressAutospace: Boolean = false,
		var listFunctionCount: Int = 0, // Number of list functions associated with first ambig key in sequence
		var listFunctionPresent: Boolean = false, // AmbigSeqlen == 1 so list function(s) are currently contained in selection list
		// Hybrid paged word selection: null = inactive; 0-based page of 6 list rows shown
		// on the letter keys. Constructor property so undo snapshots restore page steps.
		var pagedSelectPage: Int? = null,
		// Selection-list generation this state's currentSelection refers to. A selection
		// restored from an undo snapshot is only valid against the SAME list build.
		var selectionGen: Int = 0,
	)

	// Result of async wldSelection — carries all data needed for post-processing on main thread
	private data class WldSelectionResult(
		val candidates: List<Map<String, Any?>>,
		val phraseMatches: List<Map<String, Any?>>,
		val emptyAmbigSequence: Boolean,
		val termination: String,
		val maxDepth: Int,
		val examinedNodes: Int,
		val elapsedMs: Double,
		// NGB-D confidence features for this list state (null: zero-K window,
		// NGB inactive, or no word-like top).
		val ngbConf: NgbConfCandidate? = null,
	)

	/** One per-keystroke confidence observation, computed with the list. */
	private data class NgbConfCandidate(
		val posterior: Double,
		val keystrokes: Int,
		val topIsPred: Boolean,
		val ctxValid: Boolean,
		val topLower: String,
	)

	private var state = State()
	private val undoStack = ArrayDeque<State>()

	// ── Hybrid paged word selection (PAGE-BASED Word Selection) ──────────────
	// The first pagedListedWords() Select presses step the list linearly; the next
	// press redraws the letter keys with the following 6 list rows (reading order:
	// page Keys 0,3,5 down the left column, then 2,4,7) and a letter key picks its
	// word. UnDo restores the prior state through the normal undo stack.

	private fun pagedSelectionEnabled(): Boolean = prefs.getString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_PAGED) ==
		Constants.WORD_SELECTION_PAGED

	private fun pagedListedWords(): Int = prefs.getInt(Constants.KEY_PAGED_LISTED_WORDS, 2).coerceIn(0, 3)

	// Paging never engages while list-function rows remain: selecting one re-defines
	// the keyboard, so it makes no sense as a page pick. The paged region starts after
	// the LAST list-function row (or after the listed-words count, whichever is later).
	// While a family group is inserted, the listed region ends at the paused
	// row (pausing on slot 1 renders the group as the page at slot 2).
	// Cleared on every list rebuild alongside the group itself.
	private var familyListedRows: Int? = null

	private fun pagedFirstRow(): Int {
		val lastListFunction = selectionList.indexOfLast { (it["type"] as? String) == "P" }
		val base = maxOf(pagedListedWords(), lastListFunction + 1)
		val family = familyListedRows ?: return base
		return family.coerceAtLeast(lastListFunction + 1).coerceAtMost(base)
	}

	private fun pagedStartRow(page: Int): Int = pagedFirstRow() + page * PAGED_WORDS_PER_PAGE

	/** Single-switch scanning shows the scan layout; its page mechanics
	 *  (row-major cells, scan-sequence ordinals) fork on this. */
	private fun scanLayoutActive(): Boolean = prefs.effectiveInputMethod() == Constants.INPUT_METHOD_SINGLE_SWITCH

	/**
	 * Ambiguous key -> flat page ordinal. The JT grid renders page cells
	 * COLUMN-major (left column = ranks 1-3), the static array. The scan
	 * layout renders ROW-major and hands ranks out in SCAN order, so the
	 * first key the scan reaches after Select always carries the most
	 * likely entry — derived live from the scan sequence plus the page's
	 * own ambiguous-key assignments, never hardcoded (the sequence is
	 * frequency-derived data and has already changed twice).
	 */
	private fun pagedOrdinalForAmbig(ambigNum: Int): Int {
		if (!scanLayoutActive()) return PAGED_ORDINAL_FOR_AMBIG[ambigNum]
		return scanPagedOrdinals()[ambigNum]
	}

	private fun scanPagedOrdinals(): IntArray {
		val optimized = prefs.getString(Constants.KEY_LAYOUT_MODE, Constants.MODE_OPT) == Constants.MODE_OPT
		val order = if (optimized) {
			org.continuouspath.justtype.ScanState.SCAN_ORDER_OPTIMIZED
		} else {
			org.continuouspath.justtype.ScanState.SCAN_ORDER_ALPHA
		}
		val keyList = pages[state.currentPage] ?: return PAGED_ORDINAL_FOR_AMBIG
		val ordinals = PAGED_ORDINAL_FOR_AMBIG.copyOf()
		var rank = 0
		for (btn in order) {
			val ambig = keyList.getOrNull(btn)?.functions
				?.firstOrNull { it.first == KF_Ambig }?.second as? Int
			if (ambig != null && ambig in 0..5) ordinals[ambig] = rank++
		}
		return ordinals
	}

	private fun wouldEnterPagedSelection(currentIdx: Int, selectionCount: Int): Boolean {
		if (!pagedSelectionEnabled()) return false
		// C3: linear navigation only while the span group is open — paged entry
		// mid-group would bypass the collapse boundary (spec 5/6).
		if (ngbSpanMode == NgbSpanMode.ACTIVE) return false
		val first = pagedFirstRow()
		return currentIdx + 1 >= first && selectionCount >= first + PAGED_MIN_FIRST_PAGE
	}

	/** Handles a keypress while paged selection is active. Returns true if consumed. */
	private fun handlePagedSelectKey(buttonNumber: Int): Boolean {
		val page = state.pagedSelectPage ?: return false
		// Stale paging (the list was rebuilt or shrank underneath — e.g. a language
		// switch or an undo-restored snapshot): exit and process the key normally.
		// Never let a stale page consume keystrokes.
		if (pagedStartRow(page) >= selectionList.size) {
			state.pagedSelectPage = null
			return false
		}
		val key = pages[state.currentPage]?.getOrNull(buttonNumber) ?: return false
		// Select (page advance) and UnDo (state restore) run through their normal handlers.
		if (key.functions.any { it.first == KF_Select || it.first == KF_Undo }) return false
		val ambigNum = key.functions.firstOrNull { it.first == KF_Ambig }?.second as? Int
		if (ambigNum == null || ambigNum !in 0..5) {
			// Any other key exits paging and then processes normally.
			state.pagedSelectPage = null
			return false
		}
		val row = pagedStartRow(page) + pagedOrdinalForAmbig(ambigNum)
		val item = selectionList.getOrNull(row)
		if (item == null || item["familyPad"] == true) {
			onErrorBeep(false) // empty slot on the last page / family-group pad
			return true
		}
		// Snapshot so UnDo restores the paged state prior to this pick.
		undoStack.addLast(
			state.copy(
				keyHistory = ArrayList(state.keyHistory),
				systemSelectionList = ArrayList(state.systemSelectionList),
				ambiguousKeySequence = ArrayList(state.ambiguousKeySequence),
			),
		)
		state.pagedSelectPage = null
		state.currentSelection = row
		state.selectionGen = selectionGeneration
		handleDeferredExpansion(row)
		if (item["type"] == "P") {
			// List-function pick: not a word outcome — drop the episode silently.
			selEpisode = null
			// Page-jump entries behave exactly as when reached via linear Select.
			undoStack.clear()
			state.ambiguousKeySequence.clear()
			state.keyHistory.clear()
			setCurrentPage(item["output"] as String)
			if (state.currentPage == "Spelling" || state.currentPage == "SpellingAlpha") {
				state.immedCharString = ""
				state.forceFirstImmediateSpace = true
			}
		} else {
			maybeSpeakSelectedWord(item)
			// A paged pick is FINAL (Cliff, 2026-08-08): the AK unambiguously
			// selected the object, so commit immediately — the emptied sequence
			// opens the zero-K window with the word's followers.
			val type = item["type"] as? String
			val outputWord = item["output"] as? String ?: ""
			if (type in listOf("X", "L", "E", "2", "B", "PH", "N") && outputWord.isNotEmpty()) {
				recordWordUsageForSelection(row)
				onFinalizeText(outputWord)
				// The pick resolves the whole sequence and clears the buffer —
				// arm the finalized flag so the NEXT keystroke can re-seal a
				// lingering composing region (no Select activation survives to
				// trigger finalize-on-ambig).
				pagedPickFinalizedWord = outputWord
				ngbOnWordCommitted(
					outputWord,
					completedWord = type == "PH" || (type == "N" && item["ngbMulti"] == true),
				)
				if (type == "2") {
					synchronized(vocabLock) { wld.addCustomWord(outputWord) }
					invalidateRootHintCaches()
					onDataMutation()
				}
				undoStack.clear()
				state.currentSelection = null
				state.systemSelectionList.clear()
				state.ambiguousKeySequence.clear()
				state.keyHistory.clear()
				state.shiftState = false
				state.isManualShift = false
				state.capsTempDisable = false
				state.capitalizePending = false
				state.pendingAutoCapReason = AutoCapReason.NONE
				state.autoCapReason = AutoCapReason.NONE
				updateAmbiguousKeySequence()
				// Zero-K window: the committed word's followers, ready before any
				// keystroke. Applied directly — wldSelection() only RETURNS a list.
				updateSelectionList(listOf(applyShiftAndCaps(ngbZeroKEntries())), null)
			}
		}
		updateUi(false)
		return true
	}

	/** Active ALL SYMBOLS MODE session (null when not in the picker). */
	private var allSymbols: AllSymbolsModeController? = null
	private var suppressUi: Boolean = false

	// Settings controller (lazy-initialized on first entry into settings)
	private var settingsController: org.continuouspath.justtype.settings.KeyboardSettingsController? = null
	var isInSettingsMode: Boolean = false
		private set

	/** True when the settings controller is waiting for a raw key code (switch assignment). */
	val isCapturingKey: Boolean
		get() = settingsController?.isCapturingKey == true

	/** Route a raw hardware key code to the settings controller for switch assignment. */
	fun handleRawKeyCapture(keyCode: Int) {
		settingsController?.handleRawKeyCapture(keyCode)
	}

	// Generation counter for async wldSelection — set by IME before calling buttonPressed().
	// Worker thread compares its captured generation against this to detect stale results.
	@Volatile var wldGeneration: Long = 0L

	// Selection list
	private var selectionList: List<Map<String, Any?>> = emptyList()

	// Bumped on every selection-list rebuild; guards selection indices against lists
	// that changed underneath them (see State.selectionGen).
	private var selectionGeneration = 0

	// Pop an undo snapshot; a selection (or open page) referring to a different list
	// generation is meaningless against the live list and must never be committed —
	// a stale index caused the runaway re-commit loop ("resultsoriented").
	private fun restoreStateFromUndo() {
		state = undoStack.removeLast()
		if (state.selectionGen != selectionGeneration) {
			state.currentSelection = null
			state.pagedSelectPage = null
		}
		// The pick this flag guarded was undone — re-sealing would wrongly
		// re-commit it (see pagedPickFinalizedWord).
		pagedPickFinalizedWord = null
		// NGB context rides State across UnDo (issue 6): deleting a keystroke
		// keeps the word's context; rewinding past the commit restores the
		// pre-commit context. The pool is derived — refetch to match.
		ngbRefreshPool()
	}

	// Cliff's finalized flag (2026-08-11): a page-list pick fully resolves its
	// sequence and CLEARS the key buffer, so the next ambiguous keystroke finds
	// no Select activation and finalize-on-ambig never fires. If the editor
	// still holds the picked word as a composing region (device flows where the
	// pick's seal did not stick), the next preview's autospace commitText(" ")
	// REPLACES that region — the word vanishes. The flag carries the picked
	// word so the incoming keystroke can re-finalize it first (idempotent when
	// the pick already sealed); cleared once consumed, on UnDo, and on reset.
	private var pagedPickFinalizedWord: String? = null
	private var pendingExpandIndex: Int? = null
	private var expandTimerRunnable: Runnable? = null
	private var undoOnlyReversedSelect: Boolean = false

	/** Set by the IME's onUndoPressed callback when runPullInFlow succeeds.
	 *  Causes the post-processing block in buttonPressed to skip redundant
	 *  wldSelection/updateUi (runPullInFlow already set up the UI). */
	var skipPostProcessingAfterPullIn: Boolean = false
	private val expandHandler = android.os.Handler(android.os.Looper.getMainLooper())
	private var suppressNextSelectedWordSpeak: String? = null
	private var nextLetterHints: Set<Char> = emptySet()

	// --- Root hints cache: recomputed only when vocab mask config changes ---
	private var cachedRootHints: Set<Char>? = null
	private var cachedRootHintsMaskKey: Triple<Long, Long, Int?>? = null // (anyFreqMask, minFreqMask, minFreqClass)
	private var cachedAccentRootHints: Set<Char>? = null
	private var cachedAccentRootHintsMaskKey: Any? = null // AccentMaskConfig as cache key

	/**
	 * Root-hint caches are TRIE state, keyed only by the vocab masks — masks
	 * are identical across languages, so without this the Vietnamese hint set
	 * survived a switch to English and kept f/j/w/z (the four letters absent
	 * from the Vietnamese alphabet) grayed on the English layout (Cliff,
	 * 2026-08-10). Drop them whenever the trie is rebuilt or gains words.
	 */
	private fun invalidateRootHintCaches() {
		cachedRootHints = null
		cachedRootHintsMaskKey = null
		cachedAccentRootHints = null
		cachedAccentRootHintsMaskKey = null
	}

	// --- Scaled list function bitmap cache ---
	private val scaledBitmapCache = mutableMapOf<String, android.graphics.Bitmap>()
	private var scaledBitmapCacheSize: Int = 0 // imageSizePx when cache was populated
	private var lastSearchElapsedMs: Double = 0.0
	private var searchTimingCount: Int = 0
	private var searchTimingTotalMs: Long = 0
	private var searchTimingMinMs: Long = Long.MAX_VALUE
	private var searchTimingMaxMs: Long = 0
	private var lastSearchTerminationCode: String = ""
	private var lastSearchMaxDepth: Int = 0
	private var lastSearchExaminedNodes: Int = 0
	private fun consumeCapsTemp() {
		if (state.capsTempDisable && state.capsState) {
			state.capsTempDisable = false
		}
	}

	private fun isNumericPage(name: String = state.currentPage): Boolean {
		val lower = name.lowercase(Locale.getDefault())
		return lower.startsWith("numbers") || lower.startsWith("numpunct")
	}

	// Center-square mode label for a page. All-uppercase, line-broken to fit the square (matches
	// the spelling banner style). Falls through to the raw page name for pages without a mode label.
	private fun pageDisplayName(page: String): String = when {
		page == "LetterSymbol" -> context.getString(R.string.jtui_center_letters_symbols)
		page == "Navigation" -> context.getString(R.string.jtui_center_navigate)
		page.startsWith("SymbolsMulti") -> context.getString(R.string.jtui_center_repeated_symbols)
		page.startsWith("Symbols") -> context.getString(R.string.jtui_center_symbols)
		page == "NumbersPunct" || page.startsWith("NumPunct") -> context.getString(R.string.jtui_center_numeric_punctuation)
		page.startsWith("Numbers") -> context.getString(R.string.jtui_center_numbers)
		page.startsWith("Functions") -> context.getString(R.string.jtui_center_functions)
		else -> page
	}

	private fun appendNumericText(raw: String) {
		if (raw.isEmpty()) {
			onErrorBeep(false)
			return
		}
		state.numericString += raw
		onNumericOutput(state.numericString)
	}

	private fun deleteNumericChar(): Boolean {
		if (state.numericString.isEmpty()) return false
		state.numericString = state.numericString.dropLast(1)
		onNumericOutput(state.numericString)
		return true
	}

	private fun finalizeNumericText() {
		if (state.numericString.isEmpty()) return
		val finalized = state.numericString
		onFinalizeText(finalized)
		state.numericString = ""
		onNumericOutput("")
	}

	// Helper to load list function bitmap
	private fun loadListFunctionBitmap(name: String): Bitmap? = try {
		val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
		if (resId != 0) {
			val inputStream = context.resources.openRawResource(resId)
			val bitmap = BitmapFactory.decodeStream(inputStream)
			inputStream.close()
			bitmap
		} else {
			null
		}
	} catch (e: Exception) {
		null
	}

	// Helper to get list function bitmap by page name
	private fun getListFunctionBitmap(pageName: String): Bitmap? = when (pageName) {
		"Symbols1" -> loadListFunctionBitmap("symbols1_list_function_image")
		"Symbols2" -> loadListFunctionBitmap("symbols2_list_function_image")
		"Symbols3" -> loadListFunctionBitmap("symbols3_list_function_image")
		"Functions1" -> loadListFunctionBitmap("functions1_list_function_image")
		"Functions2" -> loadListFunctionBitmap("functions2_list_function_image")
		"Navigation" -> loadListFunctionBitmap("navigation_list_function_image")
		else -> null
	}

	// Custom span for full-width line background with optional gap extension.
	// minLineHeightPx: when > 0, ensures the drawn band is at least this tall (for image lines where layout line height can be too small).
	internal class FullWidthLineBackgroundSpan(
		private val color: Int,
		private val extendUpHalfGap: Boolean = false,
		private val extendDownHalfGap: Boolean = false,
		private val minLineHeightPx: Int = 0,
	) : LineBackgroundSpan {
		override fun drawBackground(
			canvas: Canvas,
			paint: Paint,
			left: Int,
			right: Int,
			top: Int,
			baseline: Int,
			bottom: Int,
			text: CharSequence,
			start: Int,
			end: Int,
			lineNumber: Int,
		) {
			val oldColor = paint.color
			paint.color = color
			var drawTop = top.toFloat()
			var drawBottom = bottom.toFloat()

			var lineHeight = bottom - top
			// For lines that contain only an ImageSpan, layout often reports a tiny line height (e.g. space character).
			// Force a minimum height so the highlight is visible.
			if (minLineHeightPx > 0 && lineHeight < minLineHeightPx) {
				lineHeight = minLineHeightPx
				val center = (top + bottom) / 2f
				drawTop = center - lineHeight / 2f
				drawBottom = center + lineHeight / 2f
			}

			// Extend upward halfway into the gap (if there's a previous line)
			if (extendUpHalfGap && lineNumber > 0) {
				drawTop = drawTop - lineHeight * 0.035f
			}

			// Extend downward halfway into the gap (if there's a next line)
			if (extendDownHalfGap) {
				drawBottom = drawBottom + lineHeight * 0.0005f
			}

			// Draw full-width rectangle
			canvas.drawRect(left.toFloat(), drawTop, right.toFloat(), drawBottom, paint)
			paint.color = oldColor
		}
	}

	// Serializes init() and any vocabulary-reload entry point. Without this, two
	// concurrent init() calls (e.g. onCreateInputView's IO coroutine + a typing-
	// language change firing Thread { jtui.init() }.start()) race on `wld`'s
	// mutable lists and CME during reloadVocabularyFromDb().
	private val vocabLock = Any()

	fun init() = synchronized(vocabLock) {
		if (::wordDb.isInitialized) {
			runCatching { wordDb.close() }
		}
		if (::customDb.isInitialized) {
			runCatching { customDb.close() }
		}
		// Open the active language's word DB (id = KEY_TYPING_LANGUAGE, e.g. "English"/"Espanol").
		// Falls back to the default English DB if the selected language has no source on this
		// device (e.g. prefs restored from backup but its langpack was never downloaded) — and
		// resets the pref so the settings UI reflects the language actually in use.
		val activeLanguage = LanguageRegistry.activeLanguageNames(prefs).first()
		val perfDbOpen = org.continuouspath.justtype.utils.PerfTrace.now()
		wordDb = runCatching { WordDb.open(filesDir, assets, activeLanguage) }
			.getOrElse { e ->
				log(DebugCategory.Lifecycle, "No word DB for '$activeLanguage' (${e.message}); falling back to English")
				if (activeLanguage != Constants.TYPING_LANGUAGE_ENGLISH) {
					prefs.putString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ENGLISH)
				}
				WordDb.open(filesDir, assets)
			}
		customDb = WordDb.openStandalone(customDbFile)
		ngbLang = activeLanguage
		ngbEngine = NgbEngine(wordDb, customDb, ngbLang)
		// Personalized confidence weights + shadow-theta counters (fitted
		// defaults / empty counters when none saved).
		ngbConfidence.importWeights(runCatching { customDb.ngbConfWeights(ngbLang) }.getOrDefault(emptyMap()))
		// The trie is being rebuilt for this language: stale hints must not survive.
		invalidateRootHintCaches()
		// Word-based languages ship an EMPTY unit inventory: the recognizer then
		// does plain word-bigram learning, and every unit-driven fork is a
		// data-level no-op (verified by the EN simulator round).
		val ngbUnitInventory = if (ngbEngine?.hasData == true) {
			runCatching { wordDb.ngbUnitStrings() }.getOrDefault(emptyList())
				.mapTo(HashSet()) { it.split(' ') }
		} else {
			HashSet()
		}
		ngbRecognizer = if (ngbEngine?.hasData == true) NgbRecognizer(ngbUnitInventory) else null
		org.continuouspath.justtype.utils.PerfTrace.log("  init: WordDb.open (active + custom)", org.continuouspath.justtype.utils.PerfTrace.now() - perfDbOpen)
		// Per-language Optimized layout baked into the DB by BuildWordDbTask. Mirrored into the
		// LanguageRegistry so a pinned layout stays available while another language is active.
		val layoutJsonRaw = wordDb.getMetadata(LayoutSpec.METADATA_KEY)
		val dbSpec = layoutJsonRaw?.let { json ->
			LayoutSpec.parse(json) { msg -> log(DebugCategory.Lifecycle, msg) }
		}
		if (dbSpec != null && layoutJsonRaw != null) {
			// Recomputed: the open may have fallen back to English (and reset the pref above).
			val openedLanguage = LanguageRegistry.activeLanguageNames(prefs).first()
			val cached = LanguageRegistry.load(prefs).firstOrNull { it.name == openedLanguage }?.layoutJson
			if (cached != layoutJsonRaw) LanguageRegistry.setLayoutJson(prefs, openedLanguage, layoutJsonRaw)
		}
		activeLayoutSpec = resolveLayoutSpec(dbSpec)
		ngbTraits = LanguageTraits.from(activeLayoutSpec, ngbUnitInventory.isNotEmpty())
		lastLangFetchMask = 0L
		initLayoutModeFromPrefs()
		val initialLetters = if (_layoutMode == LayoutMode.Alphabetical) alphaLetters() else optimizedLetters()
		val perfWld = org.continuouspath.justtype.utils.PerfTrace.now()
		wld = WLD(
			initialLetters,
			wordDb,
			customDb,
			log,
			diacriticVariantsByBase,
			toneFoldForMode(_layoutMode),
			accentFallbackEnabled = accentFallbackEnabled(),
			toneAfterVowel = toneAfterVowelActive(),
		)
		wld.addPhraseEntries(phraseRepository.all())
		org.continuouspath.justtype.utils.PerfTrace.log("  init: WLD build + phrases", org.continuouspath.justtype.utils.PerfTrace.now() - perfWld)
		val perfVocab = org.continuouspath.justtype.utils.PerfTrace.now()
		reloadVocabularyFromDbLocked()
		org.continuouspath.justtype.utils.PerfTrace.log("  init: reloadVocabularyFromDb", org.continuouspath.justtype.utils.PerfTrace.now() - perfVocab)

		definePages()
		updateKeysAndSelection()
	}

	private var lastLangFetchMask: Long = 0L

	fun reloadVocabularyFromDb() = synchronized(vocabLock) { reloadVocabularyFromDbLocked() }

	/** Last offensive-level exclusion mask applied; a change forces a full vocabulary rebuild. */
	private var lastExcludeMask: Long = -1L

	/** Last Battery Saver freqClass cap applied; a change forces a full vocabulary rebuild
	 *  (docs/.local/plans/battery-saver-mode.md, lever 4) since clearClassMasks only clears
	 *  classMask bits — it can't remove trie entries above a new, stricter cap. */
	private var lastMaxFreqClass: Int? = -1

	private fun reloadVocabularyFromDbLocked() {
		// Sync the active language's corpus-derived diacritic set (DB `metadata` → LanguageRegistry) so
		// LETTER SPELL MODE filters variants by language with no DB I/O on the layout path.
		LanguageRegistry.ensureDefaults(prefs)
		val language = LanguageRegistry.activeLanguageNames(prefs).first()
		wordDb.getMetadata("diacriticSet")?.let { encoded ->
			val current = LanguageRegistry.load(prefs).firstOrNull { it.name == language }?.diacriticSet
			if (current != encoded) LanguageRegistry.setDiacriticSet(prefs, language, DiacriticDerivation.decode(encoded))
		}
		val activeMask = prefs.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
		val fetchMask = ClassMasks.CLASS_JUSTTYPE_MASK or activeMask
		// Coarse language is graded in the DB rather than removed, so this setting takes effect
		// on reload without a rebuild. Slurs were dropped at build time and are unaffected by it.
		val excludeMask = when (
			prefs.getString(Constants.KEY_EXCLUDED_WORDS, Constants.EXCLUDED_WORDS_OFFENSIVE)
		) {
			Constants.EXCLUDED_WORDS_NONE -> 0L
			Constants.EXCLUDED_WORDS_POTENTIALLY_OFFENSIVE ->
				ClassMasks.CLASS_OFFENSIVE_MASK or ClassMasks.CLASS_POTENTIALLY_OFFENSIVE_MASK
			else -> ClassMasks.CLASS_OFFENSIVE_MASK
		}
		if (lastExcludeMask != excludeMask) {
			lastLangFetchMask = 0L
			wld.clearClassMasks(ClassMasks.CLASS_JUSTTYPE_MASK)
			lastExcludeMask = excludeMask
		}

		// Battery Saver caps the trie to freqClass <= BATTERY_SAVER_MAX_FREQ_CLASS (lever 4,
		// docs/.local/plans/battery-saver-mode.md) — drops the rarest tier only. A change (either
		// direction) forces a full rebuild: clearClassMasks can't remove entries above a new cap.
		val maxFreqClass = if (prefs.getBoolean(Constants.KEY_BATTERY_SAVER_MODE, false)) {
			Constants.BATTERY_SAVER_MAX_FREQ_CLASS
		} else {
			null
		}
		if (lastMaxFreqClass != maxFreqClass) {
			lastLangFetchMask = 0L
			wld.clearClassMasks(ClassMasks.CLASS_JUSTTYPE_MASK)
			lastMaxFreqClass = maxFreqClass
		}

		if (lastLangFetchMask != 0L && lastLangFetchMask != fetchMask) {
			val added = fetchMask and lastLangFetchMask.inv()
			val removed = lastLangFetchMask and fetchMask.inv()
			debugLog(
				DebugCategory.WordDb,
				"[reloadVocabularyFromDb] incremental: added=${hexMask(added)} removed=${hexMask(removed)}",
			)
			if (removed != 0L) wld.clearClassMasks(removed)
			if (added != 0L) {
				val newEntries = wordDb.getWordsWithMask(added, excludeMask, maxFreqClass)
				debugLog(DebugCategory.WordDb, "[reloadVocabularyFromDb] incremental addEntries=${newEntries.size}")
				newEntries.forEach { entry ->
					wld.updateOrAddWord(
						wordID = entry.wordID,
						word = entry.word,
						freqClass = entry.freqClass,
						useCount = entry.useCount,
						lastUseTime = wordDb.absoluteToRelativeTime(entry.useTime),
						classMask = entry.classMask,
						posEncoded = entry.posEncoded,
						rawFreq = entry.rawFreq,
					)
				}
			}
			lastLangFetchMask = fetchMask
			return
		}

		val entries = wordDb.getWordsWithMask(fetchMask, excludeMask, maxFreqClass)
		debugLog(
			DebugCategory.WordDb,
			"[reloadVocabularyFromDb] activeMask=${hexMask(activeMask)} fetchMask=${hexMask(fetchMask)} langEntries=${entries.size}",
		)
		if (entries.isNotEmpty()) {
			val preview = entries.take(5).joinToString { "${it.word}:${hexMask(it.classMask)}" }
			debugLog(DebugCategory.WordDb, "[reloadVocabularyFromDb] sample=$preview")
		}
		entries.forEach { entry ->
			wld.updateOrAddWord(
				wordID = entry.wordID,
				word = entry.word,
				freqClass = entry.freqClass,
				useCount = entry.useCount,
				lastUseTime = wordDb.absoluteToRelativeTime(entry.useTime),
				classMask = entry.classMask,
				posEncoded = entry.posEncoded,
				rawFreq = entry.rawFreq,
			)
		}
		lastLangFetchMask = fetchMask

		val customEntries = customDb.getWordsWithMask(ClassMasks.CLASS_CUSTOM_WORDS_MASK or ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK)
		debugLog(DebugCategory.WordDb, "[reloadVocabularyFromDb] customEntries=${customEntries.size}")
		customEntries.forEach { entry ->
			wld.updateOrAddWord(
				wordID = entry.wordID,
				word = entry.word,
				freqClass = entry.freqClass,
				useCount = entry.useCount,
				lastUseTime = customDb.absoluteToRelativeTime(entry.useTime),
				classMask = entry.classMask,
				posEncoded = entry.posEncoded,
				rawFreq = entry.rawFreq,
			)
		}
	}

	fun mergeVocabularyMasks(sourceMask: Long, targetMask: Long) = synchronized(vocabLock) {
		wld.mergeClassMasks(sourceMask, targetMask)
	}

	fun clearVocabularyMasks(mask: Long) = synchronized(vocabLock) {
		wld.clearClassMasks(mask)
	}

	// Allow IME to explicitly control next-character capitalization based on cursor context
	fun setShiftState(
		enabled: Boolean,
		isManual: Boolean = false,
		skipUpdate: Boolean = false,
		autoReason: AutoCapReason = AutoCapReason.NONE,
	) {
		if (state.shiftState == enabled && state.isManualShift == isManual && state.autoCapReason == autoReason) return
		state.shiftState = enabled
		state.isManualShift = isManual
		state.autoCapReason = if (enabled) autoReason else AutoCapReason.NONE
		debugLog(
			DebugCategory.ShiftState,
			"[setShiftState] enabled=$enabled, manual=$isManual, skipUpdate=$skipUpdate, autoReason=$autoReason",
		)
		if (!skipUpdate) updateUi(false)
	}

	fun getImmedCharCount(): Int = state.immedCharCount

	fun clearImmedCharCount() {
		state.immedCharCount = 0
	}

	fun getShiftState(): Boolean = state.shiftState
	fun getAutoCapReason(): AutoCapReason = state.autoCapReason

	// Allow IME to explicitly clear a previous manual shift if cursor is relocated
	fun clearManualShift() {
		state.isManualShift = false
	}

	/**
	 * Finalize the in-progress ambiguous-key sequence and clear related
	 * transient state. Called externally (e.g. from the head-tracking
	 * subsystem when pause/exit fires) when the user has finished typing
	 * the current word. The composing preview (the underlined word) is
	 * committed in place — any leading autospace that was inserted before
	 * the word is preserved in the text field, since the word is now a
	 * permanent part of the user's input. The pending key sequence and
	 * key-history visualization are cleared so the next typing session
	 * starts fresh (potentially in a different text field after re-entry).
	 */
	fun clearPendingKeySequence() {
		undoStack.clear()
		state.ambiguousKeySequence.clear()
		state.keyHistory.clear()
		state.numericString = ""
		// onFinalizeText("") commits the composing preview without touching
		// the leading autospace. The previous onNumericOutput("") path also
		// called removeLeadingAutospaceIfPresent — which would delete the
		// space between the prior word and the just-finalized one (e.g.
		// "This is good" → "This isgood" on exit).
		onFinalizeText("")
		// Clear the selection list (the strip of candidate words) — it's
		// a separate field from state.ambiguousKeySequence, and without
		// this call the strip keeps showing candidates for the just-cleared
		// sequence and Select still cycles through them.
		updateSelectionList(listOf(emptyList()), null)
		updateAmbiguousKeySequence()
		// Force a UI refresh so the Key History view reflects the cleared
		// state immediately. Without this, the row visualization keeps
		// showing the now-stale "active sequence" of keys.
		updateUi(false)
	}

	fun getIsManualShift(): Boolean = state.isManualShift

	// Allow IME to explicitly control speech state
	fun setSpeakState(enabled: Boolean, updateUINow: Boolean = false) {
		state.speakState = enabled
		if (updateUINow) {
			updateUi(false)
		}
	}

	fun getSpeakState(): Boolean = state.speakState
	fun getIsSpellingMode(): Boolean = state.isSpellingMode
	fun isInNumericMode(): Boolean = isNumericPage()

	fun getCurrentPage(): String = state.currentPage

	fun setCapsLock(enabled: Boolean) {
		state.capsState = enabled
		updateUi(false)
	}

	private var savedPage: String = StartingPage

	var cursorMovementMode: Int = MOVEMENT_CHARACTER_LINE
		private set
	var isSelectingText: Boolean = false
		private set
	var spellingReturnPage: String? = null

	fun setCurrentPageToStartingPage() {
		savedPage = state.previousPage
		state.previousPage = state.currentPage
		state.currentPage = StartingPage
		log(DebugCategory.Lifecycle, "[setCurrentPageToStartingPage 1] Switched from page ${state.previousPage} to ${state.currentPage}    savedPage = '$savedPage'")
		syncSpellingModeWithCurrentPage()
	}

	private fun setAutospaceSuppressed(suppressed: Boolean) {
		if (state.suppressAutospace == suppressed) return
		state.suppressAutospace = suppressed
		onSetAutospaceSuppressed(suppressed)
	}

	fun setCurrentPage(targetPage: String = StartingPage) {
		if (state.currentPage != targetPage) {
			val enteringSubMode = isSubModePage(targetPage) && !isSubModePage(state.currentPage)
			val leavingSubMode = !isSubModePage(targetPage) && isSubModePage(state.currentPage)
			if (enteringSubMode) state.subModeCaller = state.currentPage
			if (leavingSubMode) state.subModeCaller = null
			// Autospacing is suppressed throughout a LETTER/SYMBOL MODE session (all sub-modes) and
			// resumes on Main. The IME is notified so its own autospace machinery is suppressed too.
			if (targetPage == "LetterSymbol") setAutospaceSuppressed(true)
			if (targetPage == StartingPage) setAutospaceSuppressed(false)
			savedPage = state.previousPage
			state.previousPage = state.currentPage
			state.currentPage = targetPage
			log(DebugCategory.Lifecycle, "[setCurrentPage 1] Switched from page ${state.previousPage} to ${state.currentPage}    savedPage = '$savedPage' subModeCaller = '${state.subModeCaller}'")
		} else {
			log(DebugCategory.Lifecycle, "[setCurrentPage 2] No change in page, currentPage remains = '${state.currentPage}'  previousPage = '${state.previousPage}'    savedPage = '$savedPage")
		}
		// Rebuild on entry so pages reflect current state: spell pages bake in shift/caps state and
		// the SHIFT-button label; Symbols pages bake in the context-dependent "back" label (which
		// depends on state.subModeCaller, set just above); numeric-punct pages bake letter-key
		// labels in the current shift case.
		if (isSpellingPage(targetPage) || targetPage.startsWith("Symbols") || isNumericPage(targetPage)) {
			definePages()
		}
		syncSpellingModeWithCurrentPage()
		updateUi(true)
	}

	fun setPhraseFlowMode(active: Boolean) {
		if (phraseFlowActive == active) return
		phraseFlowActive = active
		if (!active) phraseFlowPhase = PhraseFlowPhase.NONE
		definePages()
		updateKeysAndSelection()
	}

	fun setPhraseAbbrevModeActive(active: Boolean) {
		phraseFlowPhase = if (active) PhraseFlowPhase.ABBREV else PhraseFlowPhase.NONE
	}

	fun setCurrentPageForPhraseFunctions() {
		savedPage = state.previousPage
		state.previousPage = state.currentPage
		state.currentPage = "Functions0"
		log(DebugCategory.Lifecycle, "[setCurrentPageForPhraseFunctions] Switching to Functions0 for phrase flow")
		syncSpellingModeWithCurrentPage()
	}

	fun restoreCurrentPageToPreviousPage() {
		log(DebugCategory.Lifecycle, "[restoreCurrentPageToPreviousPage 1] Switching back from page '${state.currentPage}' to page '${state.previousPage}'     savedPage = '$savedPage'")
		state.currentPage = state.previousPage
		state.previousPage = savedPage
		syncSpellingModeWithCurrentPage()
	}

	fun startAbbreviationEntry(useAlphabetical: Boolean, inPhraseFlow: Boolean = false) {
		state.customWordString = ""
		state.forceFirstImmediateSpace = true
		state.spellAccumulate = true // abbreviation builds a vocab key, same as ADD NEW WORD
		val targetPage = if (useAlphabetical) "SpellingAlpha" else "Spelling"
		setCurrentPage(targetPage)
		if (inPhraseFlow) {
			phraseFlowPhase = PhraseFlowPhase.ABBREV
		}
		updateSelectionList(listOf(emptyList()), null)
		updateUi(false)
	}

	fun getCurrentCustomWord(): String = state.customWordString

	fun addPhraseEntry(entry: PhraseEntry) = synchronized(vocabLock) {
		wld.addPhraseEntry(entry)
	}

	private fun syncSpellingModeWithCurrentPage() {
		val spelling = isSpellingPage(state.currentPage)
		if (state.isSpellingMode != spelling) {
			log(DebugCategory.Lifecycle, "[syncSpellingMode] Setting isSpellingMode=$spelling for page='${state.currentPage}'")
			state.isSpellingMode = spelling
		}
	}

	private fun isSpellingPage(page: String): Boolean = page.startsWith("Spell")

	fun buttonPressed(buttonNumber: Int, shouldAbort: (() -> Boolean)? = null, applyPendingHighlight: (() -> Unit)? = null) {
		log(DebugCategory.Lifecycle, "[buttonPressed 0] ENTER: buttonNumber=$buttonNumber, state.currentPage='${state.currentPage}'")
		if (buttonNumber !in 0 until NumberOfKeys) return

		// Settings pages: dispatch directly to controller, skip all standard processing
		if (isInSettingsMode && state.currentPage.startsWith("Settings")) {
			val key = pages[state.currentPage]?.getOrNull(buttonNumber) ?: return
			for ((code, arg) in key.functions) {
				when (code) {
					KF_SettingsKey -> settingsController?.handleKey(arg as? Int ?: 0)
				}
			}
			return
		}

		// Paged word selection: letter keys pick from the current page. Must run before
		// finalize-on-ambig — a pick is a selection, not the start of a new sequence.
		if (state.pagedSelectPage != null && handlePagedSelectKey(buttonNumber)) return

		// Finalize-on-ambig at entry: if an ambiguous key arrives AND we have an active sequence with a selection,
		// capture the current selected candidate and reset sequence state before starting a new one.
		var isUnDoKey = false
		run {
			val key = pages[state.currentPage]?.getOrNull(buttonNumber)
			val isAmbigIncoming = key?.functions?.any { it.first == KF_Ambig } == true
			isUnDoKey = key?.functions?.any { it.first == KF_Undo } == true
			if (isUnDoKey) {
				log(DebugCategory.UndoFlow, "[buttonPressed ] isUnDoKey set to TRUE")
			}
			// AccessiblePrompt intercept: UnDo dismisses an active prompt
			// instead of performing its normal undo action. Consume the
			// keypress entirely — no normal post-processing.
			if (isUnDoKey && isAccessiblePromptShowing()) {
				dismissAccessiblePrompt()
				log(DebugCategory.Lifecycle, "[buttonPressed] UnDo intercepted by AccessiblePrompt")
				return
			}
			val hasActiveSeq = state.ambiguousKeySequence.isNotEmpty()
			// C3 ruling (a): an ambiguous key during a span session collapses the
			// group FIRST, then applies normally — the user tapped to EDIT; typing
			// means editing the tapped word, not accepting the span already there.
			// Post-collapse the tapped word is selected, so the ordinary
			// AK-after-SEL flow below commits it — today's pull-in behavior.
			if (isAmbigIncoming && ngbSpanMode == NgbSpanMode.ACTIVE) {
				ngbSpanCollapse()
			}
			// Never finalize a selection stamped against a different list build.
			val hasSelect = state.currentSelection != null && state.selectionGen == selectionGeneration
			// Zero-K predictions: a type-"N" selection can be active with an EMPTY
			// sequence (word-start window); the incoming AK finalizes it the same way.
			val zeroKSelect = isAmbigIncoming &&
				!hasActiveSeq &&
				hasSelect &&
				(selectionList.getOrNull(state.currentSelection ?: -1)?.get("type") as? String) == "N"
			// Cliff's finalized flag: the pick left no Select activation for the
			// gate below, so an incoming AK re-finalizes the picked word itself
			// — sealing any composing region the editor still holds before the
			// new sequence's preview can replace it. Idempotent when the pick
			// already sealed. Consumed on ANY incoming AK.
			if (isAmbigIncoming) {
				val armed = pagedPickFinalizedWord
				pagedPickFinalizedWord = null
				if (armed != null && !hasActiveSeq && state.currentSelection == null) {
					log(DebugCategory.Lifecycle, "[buttonPressed] finalized-flag re-seal of paged pick '$armed'")
					onFinalizeText(armed)
				}
			}
			if (isAmbigIncoming && (hasActiveSeq || zeroKSelect) && hasSelect) {
				val idx = state.currentSelection!!
				val item = selectionList.getOrNull(idx)
				val type = item?.get("type") as? String
				val wordLike = type in listOf("X", "L", "E", "2", "B", "PH", "N")
				val finalText = if (wordLike) (item?.get("output") as? String) else null
				if (!finalText.isNullOrEmpty()) {
					val wordTypes = setOf("X", "L", "E", "2", "B", "PH", "N")
					val usageIndex = state.currentSelection
						?: selectionList.indexOfFirst { (it["type"] as? String) in wordTypes }.takeIf { it != -1 }
					usageIndex?.let { recordWordUsageForSelection(it) }
					// Call IME to finalize the text immediately
					log(DebugCategory.Lifecycle, "[buttonPressed 1] finalize/jtui:  Calling onFinalizeText with '" + finalText + "' before starting new ambig key")
					onFinalizeText(finalText)
					ngbOnWordCommitted(
						finalText,
						completedWord = type == "PH" || (type == "N" && item["ngbMulti"] == true),
					)
					// Clear undo history and current sequence state before starting new sequence
					undoStack.clear()
					state.currentSelection = null
					state.systemSelectionList.clear()
					state.ambiguousKeySequence.clear()
					state.keyHistory.clear()
					// Do NOT touch outputString or shift/caps; IME has committed finalText
				}
			}
		}
		// push state if not an UnDo key
		val wasEmptyAmbig = state.ambiguousKeySequence.isEmpty()
		// Guard the page/key lookup like the safe reads above (L1105/L1118): an unknown page key or
		// out-of-range button is a no-op keypress, not a crash.
		val key = pages[state.currentPage]?.getOrNull(buttonNumber) ?: return
		val currentKey = key.copy()
		val selectionCount = selectionList.size
		val currentSelectionIndex = state.currentSelection ?: -1
		val isSelectKey = currentKey.functions.any { it.first == KF_Select }
		val selectWillChange = if (!isSelectKey) {
			true
		} else if (selectionCount == 0) {
			false
		} else {
			val pagedPage = state.pagedSelectPage
			when {
				// Paging: Select changes state only when another page exists.
				pagedPage != null -> pagedStartRow(pagedPage + 1) < selectionCount
				wouldEnterPagedSelection(currentSelectionIndex, selectionCount) -> true
				else -> currentSelectionIndex < 0 || currentSelectionIndex < selectionCount - 1
			}
		}

		var shouldRecordKey = !isUnDoKey && (!isSelectKey || selectWillChange)
		// CK if (shouldRecordKey){
		// CK     val skipUndoPushPages = setOf(
		// CK         "Spelling", "SpellingAlpha",
		// CK         "Spell0", "Spell2", "Spell3", "Spell4", "Spell5", "Spell7",
		// CK         "SpellAlpha0", "SpellAlpha2", "SpellAlpha3", "SpellAlpha4", "SpellAlpha5", "SpellAlpha7",
		// CK         "Numbers1", "Numbers2", "NumbersPunct", "NumPunct0", "NumPunct2","NumPunct3","NumPunct4",
		// CK        "Functions1", "Functions2"
		// CK     )
		// CK     debugLog("[buttonPressed 4skip?] Checking current page for key ${buttonNumber} (currentPage='${state.currentPage}')")
		// CK     if (state.currentPage in skipUndoPushPages) {
		// CK         debugLog("[buttonPressed 4skip] Skipping UnDo stack push for key ${buttonNumber} (currentPage='${state.currentPage}')")
		// CK         shouldRecordKey = false
		// CK     }
		// CK }
		if (shouldRecordKey) {
			undoStack.addLast(
				state.copy(
					keyHistory = ArrayList(state.keyHistory),
					systemSelectionList = ArrayList(state.systemSelectionList),
					ambiguousKeySequence = ArrayList(state.ambiguousKeySequence),
				),
			)
			state.keyHistory.add(currentKey)
			updateAmbiguousKeySequence()
		}

		// Debug: log ambiguous and select keys
		val isAmbigKey = currentKey.functions.any { it.first == KF_Ambig }
		if (isAmbigKey) {
			val ambNum =
				(currentKey.functions.firstOrNull { it.first == KF_Ambig }?.second as? Int) ?: -1
			log(DebugCategory.AmbigBuffer, "[key] Ambig key pressed: index=" + buttonNumber + " ambNum=" + ambNum + " display='" + currentKey.display + "'")
			debugShowAmbiguousSequence("[buttonPressed] Ambig key pressed: ")
		}
		if (isSelectKey) {
			log(DebugCategory.AmbigBuffer, "[key] Select pressed")
		}

		// Optional: speak key name on press
		maybeSpeakKey(currentKey)

		for ((code, arg) in currentKey.functions) {
			when (code) {
				KF_Term -> {
					debugLog("[KF_Term 1] ENTRY: state.currentSelection=${state.currentSelection}")
					// Deliberately no implicit first-word fallback here (unlike KF_Enter).
					val explicitSelection = state.currentSelection
					if (explicitSelection != null) {
						val wordTypes = setOf("X", "L", "E", "2", "B", "N")
						val sel = selectionList.getOrNull(explicitSelection)
						val type = sel?.get("type") as? String
						if (sel != null && type in wordTypes) {
							val outputWord = sel["output"] as? String ?: ""
							state.outputString += outputWord
							recordWordUsageForSelection(explicitSelection)
							ngbOnWordCommitted(
								outputWord,
								completedWord = type == "N" && sel["ngbMulti"] == true,
							)
							debugLog("[KF_Term] state.currentSelection now reset to null")
							state.currentSelection = null
							state.systemSelectionList.clear()
							if (type == "2") {
								synchronized(vocabLock) { wld.addCustomWord(outputWord) }
								invalidateRootHintCaches()
								onDataMutation()
							}
							state.shiftState = false
							state.isManualShift = false
							state.capsTempDisable = false
							state.capitalizePending = false
							state.pendingAutoCapReason = AutoCapReason.NONE
							state.autoCapReason = AutoCapReason.NONE
						}
					}
				}

				KF_Ambig -> {
					if (wasEmptyAmbig) {
						state.capitalizePending = state.shiftState
						state.pendingAutoCapReason =
							if (state.shiftState) state.autoCapReason else AutoCapReason.NONE
						state.shiftState = false
						state.autoCapReason = AutoCapReason.NONE
						// If caps lock was temporarily disabled via SHIFT, restore after first key.
						if (state.capsTempDisable) {
							state.capsTempDisable = false
						}
						if (!state.capitalizePending) {
							state.pendingAutoCapReason = AutoCapReason.NONE
						}
						// Don't reset state.isManualShift yet - check when state.capitalizePending is applied
						// Notify IME that a new ambiguous sequence is starting
						onAmbiguousSequenceStart()
					}
					if (state.ambiguousKeySequence.size == 1) {
						if (currentKey.singleKeyPages.isNotEmpty()) {
							// First key is a list function key (produces system list, not word list) if listFunctionCount > 0
							state.systemSelectionList.clear()
							state.listFunctionCount = currentKey.singleKeyPages.size
							state.listFunctionPresent = true
							currentKey.singleKeyPages.forEach { p ->
								state.systemSelectionList.add(
									mapOf(
										"type" to "P",
										"display" to p,
										"output" to p,
										"countOfOccurrence" to 0,
										"POS" to "",
									),
								)
							}
						} else {
							state.systemSelectionList.clear()
							state.listFunctionCount = 0
							state.listFunctionPresent = false
						}
					} else {
						state.systemSelectionList.clear()
						// CK state.listFunctionCount = 0
						state.listFunctionPresent = false
					}
				}

				KF_Select -> {
					cancelPendingExpand()
					// Cliff's finalized flag, SELECT leg (2026-08-14): stepping into
					// the zero-K follower list right after a paged pick must not let
					// the follower's composing preview REPLACE the picked word when
					// the pick's seal did not stick on-device (the same churn the AK
					// leg guards) — re-seal first. Idempotent when already sealed;
					// consumed here exactly like on an incoming AK.
					run {
						val armed = pagedPickFinalizedWord
						pagedPickFinalizedWord = null
						if (armed != null && state.ambiguousKeySequence.isEmpty() && state.currentSelection == null) {
							log(DebugCategory.Lifecycle, "[KF_Select] finalized-flag re-seal of paged pick '$armed'")
							onFinalizeText(armed)
						}
					}
					val wasPage =
						state.currentSelection?.let { selectionList[it]["type"] == "P" } ?: false
					val selectionCountCurrent = selectionList.size
					if (selectionCountCurrent == 0) {
						if (state.ambiguousKeySequence.isEmpty()) {
							// Select on an empty keyboard pulls in the word touching the
							// cursor (e.g. resume selecting after a sleep finalized the
							// word). The IME side beeps when there is nothing to pull in.
							onManualPullIn()
							return
						}
						onErrorBeep(false)
						return
					}
					// First Select press of an engagement: open the select-behavior
					// episode (and, on the Dev force ladder, promote demoted FTS rows
					// before any index is captured). Span sessions have their own
					// list semantics — excluded.
					if (state.currentSelection == null &&
						state.pagedSelectPage == null &&
						ngbSpanMode != NgbSpanMode.ACTIVE
					) {
						selEpisodeBegin()
					}
					// Hybrid paged selection: advance the page, or enter paged mode when the
					// next linear step would pass the listed words. End of list: stay on the
					// last page with the error beep (list-mode end behavior).
					state.pagedSelectPage?.let { pagedPage ->
						if (pagedStartRow(pagedPage) >= selectionCountCurrent) {
							// Stale page (list rebuilt underneath): clear and fall through
							// to normal Select handling.
							state.pagedSelectPage = null
							return@let
						}
						if (pagedStartRow(pagedPage + 1) < selectionCountCurrent) {
							state.pagedSelectPage = pagedPage + 1
							updateUi(false)
						} else {
							onErrorBeep(false)
						}
						return
					}
					if (wouldEnterPagedSelection(state.currentSelection ?: -1, selectionCountCurrent)) {
						state.pagedSelectPage = 0
						updateUi(false)
						return
					}
					if (state.currentSelection == null) state.currentSelection = -1
					val currentIdx = state.currentSelection ?: -1
					if (currentIdx >= selectionCountCurrent - 1) {
						onErrorBeep(false)
						return
					}
					// getOrNull: selectionCountCurrent is a snapshot and can exceed
					// selectionList.size — degrade to an error beep, not a crash.
					val newIdx = currentIdx + 1
					// C3 spec 6: Select stepping forward OFF the last span entry
					// collapses the group — identical outcome to backing out (5).
					if (ngbSpanMode == NgbSpanMode.ACTIVE && newIdx >= ngbSpanEntries.size) {
						ngbSpanCollapse()
						return
					}
					val cur = selectionList.getOrNull(newIdx)
					if (cur == null) {
						onErrorBeep(false)
						return
					}
					state.currentSelection = newIdx
					state.selectionGen = selectionGeneration

					handleDeferredExpansion(newIdx)
					if (cur["type"] == "P") {
						// List-function selection: not a word outcome for the
						// select-behavior episode — drop it silently.
						selEpisode = null
						// CKCK Clear ambiguous key buffeer here?
						log(DebugCategory.AmbigBuffer, "[KF_Select 0] Clearing ambig buffer due to page jump")
						undoStack.clear()
						state.ambiguousKeySequence.clear()
						state.keyHistory.clear()
						// CKCK updateAmbiguousKeySequence()   // Needed???
						log(DebugCategory.AmbigBuffer, "[KF_Select 1] resetting current page from ${getCurrentPage()}  to  ${cur["output"] as String}")
						setCurrentPage(cur["output"] as String)
						// Prepare to start spelling if Spelling page is utilized
						if ((state.currentPage == "Spelling") || (state.currentPage == "SpellingAlpha")) {
							state.immedCharString = ""
							state.forceFirstImmediateSpace = true // Flag that first immediate char output may require autospace
						}
					} else if (wasPage) {
						// Cancel spelling mode if Spelling page is stepped over
						log(DebugCategory.AmbigBuffer, "[KF_Select 2] resetting current page from ${getCurrentPage()}  to  $StartingPage")
						setCurrentPageToStartingPage()
					}
					// Optional: speak word when selection moves to a word-like entry
					maybeSpeakSelectedWord(cur)

					// After Select, reset next-letter hints to starting state (use cache)
					if (showNextLetterHints) {
						val masks = buildNextLetterMaskConfig()
						val maskKey = Triple(masks.anyFreqMask, masks.minFreqMask, masks.minFreqClass)
						nextLetterHints = if (cachedRootHints != null && cachedRootHintsMaskKey == maskKey) {
							cachedRootHints!!
						} else {
							computeRootLetterHints(
								masks.anyFreqMask,
								masks.minFreqMask,
								masks.minFreqClass,
							).also {
								cachedRootHints = it
								cachedRootHintsMaskKey = maskKey
							}
						}
					}
					// Always refresh UI so selection list highlight updates (e.g. when moving through SYM1/SYM2/SYM3 images)
					updateUi(false)
				}

				KF_Undo -> {
					log(DebugCategory.UndoFlow, "[KF_Undo 1] ENTRY: undoStack.size=${undoStack.size}, ambigSeqLen=${state.ambiguousKeySequence.size}, ambigKeys=${state.ambiguousKeySequence.map { it.display }}")
					// Capture current context BEFORE UnDo
					val ambigSeqLenBefore = state.ambiguousKeySequence.size
					val currentSelection = state.currentSelection
					val listFunctionCount = state.listFunctionCount
					val hadComposingBefore = !selectionList.isEmpty() &&
						selectionList.any {
							val t = it["type"] as? String
							t == "X" || t == "L" || t == "E" || t == "2" || t == "B" || t == "PH" || t == "N"
						} &&
						state.systemSelectionList.isEmpty()
					val selectCount = (currentSelection ?: -1) + 1
					// There will still be composing text after this UnDo IF:
					// There will still be two or more ambiguous keys, OR...
					val willHaveComposingAfter = (ambigSeqLenBefore > 2) ||
						// There will be one ambig key with NO list functions, or there are one or more Selects to delete before deleting an ambig key. OR...
						((ambigSeqLenBefore == 2) && ((listFunctionCount == 0) || (selectCount > 0))) ||
						// There are no list functions, but there are one or more Selects to delete, OR there are one or more text objects remaining between currently selected objects and any preceding list functions
						((ambigSeqLenBefore == 1) && (((listFunctionCount == 0) && (selectCount > 0)) || (selectCount > (listFunctionCount + 1))))
					log(DebugCategory.UndoFlow, "[KF_Undo 2] ENTRY: ambigSeqLenBefore=$ambigSeqLenBefore, currentSelection=$currentSelection, listFunctionCount=$listFunctionCount, hadComposingBefore=$hadComposingBefore, willHaveComposingAfter=$willHaveComposingAfter")

					// CK var callOnUndoPressed = true
					if (undoStack.size >= 1) {
						// undoStack.removeLast()      <= Removed stack pop - UnDo key is no longer added
						// Revert JTUI to previous state
						restoreStateFromUndo()
						updateAmbiguousKeySequence()
						// C3 spec 5: backing off the span-group head (the popped
						// snapshot is the pre-selection state) collapses the group.
						if (ngbSpanMode == NgbSpanMode.ACTIVE && state.currentSelection == null) {
							ngbSpanCollapse()
							return
						}
						log(DebugCategory.UndoFlow, "[KF_Undo 3] After popping UnDo stack: ambigSeqLen=${state.ambiguousKeySequence.size}, ambigKeys=${state.ambiguousKeySequence.map { it.display }}")
						undoOnlyReversedSelect = state.ambiguousKeySequence.size == ambigSeqLenBefore
						// If UnDo resulted in empty ambiguous sequence, clear the undo stack
						// so the next UnDo acts as backspace instead of popping the empty state
						if (state.ambiguousKeySequence.isEmpty()) {
							log(DebugCategory.UndoFlow, "[KF_Undo 4] Ambiguous sequence now empty after UnDo, clearing undo stack")
							undoStack.clear()
						}
					}
					// CK else {
					// CK     val currentSel = state.currentSelection
					// CK     if (currentSel != null) {
					// CK         if ((currentSel > 0) && (state.listFunctionCount > 0)) {
					// CK             state.currentSelection = currentSel - 1
					// CK             forceUpdateUi(true)
					// CK             callOnUndoPressed = false
					// CK         } else if (state.listFunctionCount > 0) {
					// CK             callOnUndoPressed = (state.listFunctionCount == 0)   // If we were in the last list function before backing out of the start of the list, all we need is to go back to Main
					// CK             updateKeysAndSelection()      // In any case, return to the Main keyboard
					// CK         }                                 // If we weren't in a list function, this should force UnDo to revert to character deletion
					// CK     }
					// CK }
					// CK if (callOnUndoPressed) {
					// Call comprehensive UnDo handler
					val context = UndoContext(
						ambigSeqLenBefore = ambigSeqLenBefore,
						currentSelection = state.currentSelection,
						listFunctionCount = state.listFunctionCount,
						hadComposingBefore = hadComposingBefore,
						willHaveComposingAfter = willHaveComposingAfter,
					)
					log(DebugCategory.UndoFlow, "[KF_Undo 5] Calling onUndoPressed with context: before=$ambigSeqLenBefore, hadComposing=$hadComposingBefore, willHaveComposing=$willHaveComposingAfter, listFunctionCount=$listFunctionCount")
					onUndoPressed(context)
					// CK  }
				}

				KF_DeleteWord -> {
					finalizeNumericText()
					onFinalizeText("")
					onDeleteWord()
				}

				KF_DeleteChar -> {
					finalizeNumericText()
					onFinalizeText("")
					onDeleteChar()
				}

				KF_Snug -> {
					state.outputString = state.outputString.rstripSpaces()
				}

				KF_Immed -> {
					val out =
						(arg as String).let {
							val result = if (state.shiftState || isCapsActive()) {
								upperWithLocale(it)
							} else {
								it.lowercase()
							}
							if (state.shiftState) {
								state.shiftState = false
								state.autoCapReason = AutoCapReason.NONE
							}
							result
						}
					if (isNumericPage()) {
						appendNumericText(out)
						state.currentSelection = null
						state.systemSelectionList.clear()
						undoStack.clear()
						updateAmbiguousKeySequence()
					} else {
						state.immedCharCount = out.length
						onImmediateOutput(out)
						state.immedCharString += out
						state.currentSelection = null
						state.systemSelectionList.clear()
						undoStack.clear()
						updateAmbiguousKeySequence()
						maybeNgbSentenceBoundary(out)
					}
				}

				KF_ImmedNoSpace -> {
					var out =
						(arg as String).let {
							val result = if (state.shiftState || isCapsActive()) {
								it.uppercase()
							} else {
								it.lowercase()
							}
							if (state.shiftState) {
								state.shiftState = false
								state.autoCapReason = AutoCapReason.NONE
							}
							result
						}
					if (out.equals("SAVE") || out.equals("save")) {
						// Save current output string happens in KF_CheckAddCustomWord
					} else if (!out.equals(' ')) {
						if (out.equals("<SP>") || out.equals("<SPACE>") || out.equals("<sp>") || out.equals("<space>")) { // Output space if that is the option explicitly displayed
							out = " "
						}
						if (state.forceFirstImmediateSpace) {
							onSpaceIfNeeded(false)
							state.forceFirstImmediateSpace = false
						}
						if (isNumericPage()) {
							appendNumericText(out)
							state.currentSelection = null
							state.systemSelectionList.clear()
							undoStack.clear()
							updateAmbiguousKeySequence()
						} else {
							state.immedCharCount = out.length
							onImmediateOutput(out)
							state.immedCharString += out
							state.currentSelection = null
							state.systemSelectionList.clear()
							undoStack.clear()
							updateAmbiguousKeySequence()
							consumeCapsTemp()
							maybeNgbSentenceBoundary(out)
						}
					} else {
						onErrorBeep(false)
					}
				}

				KF_ImmedSpell -> {
					val out =
						(arg as String).let {
							val result = if (state.shiftState || isCapsActive()) {
								upperWithLocale(it)
							} else {
								it.lowercase()
							}
							if (state.shiftState) {
								state.shiftState = false
								state.autoCapReason = AutoCapReason.NONE
							}
							result
						}
					if (out.equals("SAVE") || out.equals("save")) {
						if (spellingReturnPage == null) {
							// Save current output string happens in KF_CheckAddCustomWord
						}
					} else {
						debugLog("[KF_ImmedSpell 1] out=$out editingMode=${spellingReturnPage != null} accumulate=${state.spellAccumulate}")
						val trimmedOut = out.trim()
						if (trimmedOut.isEmpty()) {
							debugLog("[KF_ImmedSpell 1a] Ignoring blank spelling key output")
							onErrorBeep(false)
						} else if (spellingReturnPage != null) {
							onImmediateOutput(trimmedOut)
						} else if (!state.spellAccumulate) {
							// LETTER/SYMBOL MODE: commit each unambiguous character immediately, like a
							// regular immediate key. No customWordString tracking (not building a vocab
							// word) and no autospacing — only explicitly-selected characters are emitted.
							if (state.forceFirstImmediateSpace && !state.suppressAutospace) {
								onSpaceIfNeeded(false)
							}
							state.forceFirstImmediateSpace = false
							onImmediateOutput(trimmedOut)
							state.immedCharString += trimmedOut
							state.currentSelection = null
							state.systemSelectionList.clear()
							undoStack.clear()
							updateAmbiguousKeySequence()
							consumeCapsTemp()
						} else {
							val normalizedOut = trimmedOut
							if (state.forceFirstImmediateSpace) {
								debugLog("[KF_ImmedSpell 2] Calling onSpaceIfNeeded(false)")
								onSpaceIfNeeded(false)
								state.forceFirstImmediateSpace = false
							}
							state.customWordString += normalizedOut
							debugLog("[KF_ImmedSpell 2] customWordString=${state.customWordString}")
							onSpellingOutput(state.customWordString)
							state.currentSelection = null
							state.systemSelectionList.clear()
							undoStack.clear()
							updateAmbiguousKeySequence()
							consumeCapsTemp()
						}
					}
				}

				KF_Enter -> {
					finalizeNumericText()
					val wordTypes = setOf("X", "L", "E", "2", "B", "PH")
					val explicitSelection = state.currentSelection
					val firstWordIndex = selectionList.indexOfFirst { (it["type"] as? String) in wordTypes }
					val effectiveIndex = explicitSelection ?: firstWordIndex.takeIf { it >= 0 }
					effectiveIndex?.let { recordWordUsageForSelection(it) }
					onEnterKey()
					state.currentSelection = null
					state.systemSelectionList.clear()
					undoStack.clear()
					state.keyHistory.clear()
					updateAmbiguousKeySequence()
					state.numericString = ""
					onNumericOutput("")
					// A newline is a sentence boundary: serve the BOS row.
					ngbSentenceBoundary()
				}

				KF_SpellDelete -> {
					if (spellingReturnPage != null) {
						onEditingDelete()
					} else if (state.customWordString.isNotEmpty()) {
						state.customWordString = state.customWordString.dropLast(1)
						onSpellingOutput(state.customWordString)
					} else {
						onErrorBeep(false)
					}
				}

				KF_NumericDelete -> {
					if (!deleteNumericChar()) onErrorBeep(false)
				}
				KF_FinishNumericString -> {
					finalizeNumericText()
					// Notify IME via onFinalizeText to flush numeric speech if needed
					onFinalizeText("")
				}

				KF_CheckAddCustomWord -> {
					val returnPage = spellingReturnPage
					if (returnPage != null) {
						spellingReturnPage = null
						onFinalizeText(state.customWordString)
						state.customWordString = ""
						state.isSpellingMode = false
						log(DebugCategory.Lifecycle, "[KF_CheckAddCustomWord] Edit word done, returning to $returnPage")
						setCurrentPage(returnPage)
						return
					}
					if (phraseFlowPhase == PhraseFlowPhase.ABBREV) {
						onPhraseDone()
						phraseFlowPhase = PhraseFlowPhase.NONE
						state.customWordString = ""
						state.isSpellingMode = false
						return
					}
					if (!state.spellAccumulate) {
						// LETTER/SYMBOL MODE: characters were already committed individually; DONE
						// creates no vocab word. Continue (do NOT return) so the DONE button's
						// remaining functions — notably KF_BackToCaller — still run and exit the mode.
						state.customWordString = ""
						state.isSpellingMode = false
						continue
					}
					// Attempt to add spelling string as custom word
					state.customWordString = state.customWordString.rstripSpaces()
					if (onCustomWordIntercept(state.customWordString)) {
						debugLog("[KF_CheckAddCustomWord] Intercepted by IME (custom flow), skipping addCustomWord")
						state.customWordString = ""
						state.isSpellingMode = false
						debugLog("[KF_CheckAddCustomWord] Set isSpellingMode to: false (intercept)")
						return
					}
					onFinalizeText(state.customWordString)
					synchronized(vocabLock) { wld.addCustomWord(state.customWordString) }
					invalidateRootHintCaches()
					onDataMutation()
					state.customWordString = ""
					state.isSpellingMode = false
					debugLog("[KF_CheckAddCustomWord] Set isSpellingMode to: false")
				}

				KF_CreateCustomWord -> {
					onFinalizeText("") // If any composing text is still present, finalize it before proceeding
					state.isSpellingMode = true // Flag that we are in Spelling Mode
					state.customWordString = ""
					state.forceFirstImmediateSpace = true // Flag that first immediate char output may require autospace
					debugLog("[KF_CreateCustomWord] Set isSpellingMode to: true")
				}

				KF_ClearOutput -> {
					debugLog("[KF_ClearOutput] Clearing output string ${state.outputString}")
					// Clear output string between uses
					state.outputString = ""
					state.numericString = ""
					onNumericOutput("")
				}

				KF_SpaceIfNeeded -> {
					// Check if a space should be inserted after the character just committed
					// This is handled by the IME callback (suppressed in LETTER/SYMBOL MODE).
					if (!state.suppressAutospace) onSpaceIfNeeded(true)
				}

				KF_SpaceIfNeededMulti -> {
					// Check if a space should be inserted after the two characters previously committed
					// This is handled by the IME callback (suppressed in LETTER/SYMBOL MODE).
					if (!state.suppressAutospace) onSpaceIfNeeded(false)
				}

				KF_GoToPage -> {
					var targetPage = arg as String
					if (isNumericPage() && !isNumericPage(targetPage)) {
						finalizeNumericText()
					}
					if (targetPage == "editMode1" && !state.currentPage.startsWith("editMode")) {
						cursorMovementMode = MOVEMENT_CHARACTER_LINE
					}
					// If returning to cursor mode while in bookmark mode, go to bookmark page
					if (targetPage == "editMode1" && cursorMovementMode == MOVEMENT_BOOKMARK) {
						targetPage = "editModeBookmark"
					}
					// Keep selection list highlight in sync: if target page is in the list (e.g. Symbols1/2/3), set currentSelection to that index
					val idxInList = selectionList.indexOfFirst { (it["output"] as? String) == targetPage || (it["display"] as? String) == targetPage }
					if (idxInList >= 0) {
						state.currentSelection = idxInList
					}
					log(DebugCategory.Lifecycle, "[GoToPage] Navigating from '${state.currentPage}' to '$targetPage'")
					setCurrentPage(targetPage)
				}

				KF_RefreshUI -> {
					log(DebugCategory.Lifecycle, "[KF_RefreshUI] Refreshing UI on ${state.currentPage}")
					forceUpdateUi(true)
				}

				KF_RefreshKeyboardView -> {
					log(DebugCategory.Lifecycle, "[KF_RefreshKeyboardView] Refreshing UI on ${state.currentPage}")
					// Despite the name, this has always done a full update — the old
					// keyboardOnly flag was never wired to anything.
					updateUi(true)
				}

				KF_Shift -> {
					if (state.capsState) {
						state.capsTempDisable = !state.capsTempDisable
						state.shiftState = false
						state.isManualShift = true
						state.autoCapReason = AutoCapReason.NONE
					} else {
						state.shiftState = !state.shiftState
						state.isManualShift = true
						state.autoCapReason = if (state.shiftState) AutoCapReason.MANUAL else AutoCapReason.NONE
					}
					// Numeric-punct letter keys (e, k, m) show their label in the case that will be
					// emitted; rebuild so the display tracks the shift state.
					if (isNumericPage()) definePages()
				}

				KF_CapsLock -> {
					state.capsState = !state.capsState
					if (!state.capsState) {
						state.capsTempDisable = false
					}
				}

				KF_SpellSetAccumulate -> {
					// Set the spell-output mode on entry to Two-Key Spell Mode and reset any
					// stale accumulation so sessions never bleed into one another.
					state.spellAccumulate = (arg as? Int ?: 1) == 1
					state.customWordString = ""
					state.forceFirstImmediateSpace = true
				}

				KF_SpellShiftCycle -> {
					// 3-state cycle for the Spell Mode SHIFT key: SHIFT → CAPS LOCK ON → CAPS LOCK OFF.
					// Letter entry while shift is active resets shiftState (handled in KF_Immed /
					// KF_ImmedSpell), so the user gets one-shot shift; CAPS LOCK persists until toggled.
					when {
						state.capsState -> {
							// CAPS LOCK ON → press takes us to CAPS LOCK OFF (idle state)
							state.capsState = false
							state.capsTempDisable = false
							state.shiftState = false
						}
						state.shiftState -> {
							// SHIFT ON → press promotes to CAPS LOCK ON
							state.capsState = true
							state.shiftState = false
							state.isManualShift = true
						}
						else -> {
							// Idle → press enables one-shot SHIFT
							state.shiftState = true
							state.isManualShift = true
							state.autoCapReason = AutoCapReason.MANUAL
						}
					}
					definePages()
				}

				KF_Speech -> {
					val a = arg as Int
					state.speakState = when (a) {
						0 -> false
						1 -> true
						else -> !state.speakState
					}
				}

				KF_ClearInput -> {
					// Clear undo history and current sequence state before starting new sequence
					undoStack.clear()
					state.currentSelection = null
					state.systemSelectionList.clear()
					updateSelectionList(listOf(emptyList()), null)
					state.ambiguousKeySequence.clear()
					state.keyHistory.clear()
					state.numericString = ""
					onNumericOutput("")
					updateAmbiguousKeySequence()
				}

				KF_ClearAmbig -> {
					// Clear undo history and current sequence state before starting new sequence
					undoStack.clear()
					state.ambiguousKeySequence.clear()
					state.keyHistory.clear()
					state.numericString = ""
					onNumericOutput("")
					updateAmbiguousKeySequence()
				}

				KF_AddNewPhrase -> {
					onAddNewPhrase(state.outputString)
				}
				KF_CancelNewPhrase -> {
					onCancelNewPhrase()
				}
				KF_PhraseDone -> {
					onPhraseDone()
				}

				KF_Speak -> {
					sayInterruptible(state.outputString)
				}

				KF_SpeakSentence -> {
					onSpeakSentence(false)
				}

				KF_SpeakNextSentence -> {
					onSpeakNextSentence()
				}

				KF_SpeakLastSelection -> {
					onSpeakLastSelection()
				}

				KF_EndSentence -> { // If speech is ON, speak the sentence just completed
					val allowSentenceSpeech = state.speakState &&
						prefs.getBoolean(KEY_SPEAK_OUTPUT_SENTENCE)
					if (allowSentenceSpeech) {
						onSpeakSentence(false)
					}
				}

				KF_EndSentenceMulti -> { // If speech is ON, and previous characters suggest a completed sentence, speak the sentence
					val allowSentenceSpeech = state.speakState &&
						prefs.getBoolean(KEY_SPEAK_OUTPUT_SENTENCE)
					if (allowSentenceSpeech) {
						onSpeakSentence(true)
					}
				}

				KF_SaveLast -> {
					val k = state.customWordString.trim().substringAfterLast(' ', "")
					if (k.isNotEmpty()) {
						synchronized(vocabLock) { wld.addCustomWord(k) }
						invalidateRootHintCaches()
						onDataMutation()
					}
				}

				KF_CursorLeft -> {
					val hasAmbig = state.ambiguousKeySequence.isNotEmpty()
					if (hasAmbig) {
						val sel = state.currentSelection
						val wordTypes = setOf("X", "L", "E", "2", "B")
						val item = sel?.let { selectionList.getOrNull(it) }
						val text = if (item != null && (item["type"] as? String) in wordTypes) {
							item["output"] as? String ?: ""
						} else {
							selectionList.firstOrNull { (it["type"] as? String) in wordTypes }
								?.get("output") as? String ?: ""
						}
						if (text.isNotEmpty()) onFinalizeText(text)
						undoStack.clear()
						state.currentSelection = null
						state.systemSelectionList.clear()
						state.ambiguousKeySequence.clear()
						state.keyHistory.clear()
						updateSelectionList(listOf(emptyList()), null)
					}
					onCursorMove(CURSOR_LEFT, hasAmbig, cursorMovementMode, isSelectingText)
				}

				KF_CursorRight -> {
					val hasAmbig = state.ambiguousKeySequence.isNotEmpty()
					if (hasAmbig) {
						val sel = state.currentSelection
						val wordTypes = setOf("X", "L", "E", "2", "B")
						val item = sel?.let { selectionList.getOrNull(it) }
						val text = if (item != null && (item["type"] as? String) in wordTypes) {
							item["output"] as? String ?: ""
						} else {
							selectionList.firstOrNull { (it["type"] as? String) in wordTypes }
								?.get("output") as? String ?: ""
						}
						if (text.isNotEmpty()) onFinalizeText(text)
						undoStack.clear()
						state.currentSelection = null
						state.systemSelectionList.clear()
						state.ambiguousKeySequence.clear()
						state.keyHistory.clear()
						updateSelectionList(listOf(emptyList()), null)
					}
					onCursorMove(CURSOR_RIGHT, hasAmbig, cursorMovementMode, isSelectingText)
				}

				KF_CursorUp -> {
					if (state.ambiguousKeySequence.isNotEmpty()) {
						val sel = state.currentSelection
						val wordTypes = setOf("X", "L", "E", "2", "B")
						val item = sel?.let { selectionList.getOrNull(it) }
						val text = if (item != null && (item["type"] as? String) in wordTypes) {
							item["output"] as? String ?: ""
						} else {
							selectionList.firstOrNull { (it["type"] as? String) in wordTypes }
								?.get("output") as? String ?: ""
						}
						if (text.isNotEmpty()) onFinalizeText(text)
						undoStack.clear()
						state.currentSelection = null
						state.systemSelectionList.clear()
						state.ambiguousKeySequence.clear()
						state.keyHistory.clear()
						updateSelectionList(listOf(emptyList()), null)
					}
					onCursorMove(CURSOR_UP, false, cursorMovementMode, isSelectingText)
				}

				KF_CursorDown -> {
					if (state.ambiguousKeySequence.isNotEmpty()) {
						val sel = state.currentSelection
						val wordTypes = setOf("X", "L", "E", "2", "B")
						val item = sel?.let { selectionList.getOrNull(it) }
						val text = if (item != null && (item["type"] as? String) in wordTypes) {
							item["output"] as? String ?: ""
						} else {
							selectionList.firstOrNull { (it["type"] as? String) in wordTypes }
								?.get("output") as? String ?: ""
						}
						if (text.isNotEmpty()) onFinalizeText(text)
						undoStack.clear()
						state.currentSelection = null
						state.systemSelectionList.clear()
						state.ambiguousKeySequence.clear()
						state.keyHistory.clear()
						updateSelectionList(listOf(emptyList()), null)
					}
					onCursorMove(CURSOR_DOWN, false, cursorMovementMode, isSelectingText)
				}

				KF_SelectText -> {
					isSelectingText = !isSelectingText
					log(DebugCategory.Lifecycle, "[KF_SelectText] isSelectingText toggled to $isSelectingText")
				}

				KF_CycleCursorMode -> {
					val wasBookmark = cursorMovementMode == MOVEMENT_BOOKMARK
					cursorMovementMode = when (cursorMovementMode) {
						MOVEMENT_CHARACTER_LINE -> MOVEMENT_WORD_SENTENCE
						MOVEMENT_WORD_SENTENCE -> MOVEMENT_PARAGRAPH_PAGE
						MOVEMENT_PARAGRAPH_PAGE -> MOVEMENT_BOOKMARK
						else -> MOVEMENT_CHARACTER_LINE
					}
					// Switch between editMode1 and editModeBookmark pages
					if (cursorMovementMode == MOVEMENT_BOOKMARK) {
						setCurrentPage("editModeBookmark")
					} else if (wasBookmark) {
						setCurrentPage("editMode1")
					}
					log(DebugCategory.Lifecycle, "[KF_CycleCursorMode] cursorMovementMode set to $cursorMovementMode")
				}

				KF_Cut -> {
					log(DebugCategory.Lifecycle, "[KF_Cut] Sending CUT action, clearing selection mode")
					isSelectingText = false
					onClipboardAction(android.view.KeyEvent.KEYCODE_CUT)
				}
				KF_Copy -> {
					log(DebugCategory.Lifecycle, "[KF_Copy] Sending COPY action, clearing selection mode")
					isSelectingText = false
					onClipboardAction(android.view.KeyEvent.KEYCODE_COPY)
				}
				KF_Paste -> {
					log(DebugCategory.Lifecycle, "[KF_Paste] Sending PASTE action, clearing selection mode")
					isSelectingText = false
					onClipboardAction(android.view.KeyEvent.KEYCODE_PASTE)
				}
				KF_EditUndo -> {
					log(DebugCategory.Lifecycle, "[KF_EditUndo] Sending Ctrl+Z undo")
					onClipboardAction(-1)
				}
				KF_SpeakSelectionOrSentence -> {
					log(DebugCategory.Lifecycle, "[KF_SpeakSelectionOrSentence] Speaking selection or sentence")
					onSpeakSelectionOrSentence()
				}

				KF_CaseToTitle -> {
					log(DebugCategory.Lifecycle, "[KF_CaseToTitle] Applying title case (word level)")
					onCaseChange(CASE_TYPE_TITLE, CASE_MODE_WORD)
				}
				KF_CaseToUpper -> {
					log(DebugCategory.Lifecycle, "[KF_CaseToUpper] Applying upper case")
					onCaseChange(CASE_TYPE_UPPER, CASE_MODE_WORD)
				}
				KF_CaseToLower -> {
					log(DebugCategory.Lifecycle, "[KF_CaseToLower] Applying lower case")
					onCaseChange(CASE_TYPE_LOWER, CASE_MODE_WORD)
				}
				KF_CaseToSentence -> {
					log(DebugCategory.Lifecycle, "[KF_CaseToSentence] Applying sentence case")
					onCaseChange(CASE_TYPE_TITLE, CASE_MODE_SENTENCE)
				}
				KF_EditWord -> {
					log(DebugCategory.Lifecycle, "[KF_EditWord] Entering spelling mode for word editing, return to editMode3")
					spellingReturnPage = "editMode3"
					onFinalizeText("")
					state.isSpellingMode = true
					state.customWordString = ""
					state.forceFirstImmediateSpace = true
				}

				KF_BackToMain -> {
					isSelectingText = false
					cursorMovementMode = MOVEMENT_CHARACTER_LINE
					log(DebugCategory.Lifecycle, "[KF_BackToMain] Cleared edit mode state, navigating to Main")
					setCurrentPageToStartingPage()
					onEditModeExit()
				}

				KF_BackToCaller -> {
					val target = if (state.subModeCaller == "LetterSymbol") "LetterSymbol" else "Main"
					log(DebugCategory.Lifecycle, "[KF_BackToCaller] subModeCaller='${state.subModeCaller}' → $target")
					setCurrentPage(target)
				}

				KF_SymbolMode -> {
					val entry = state.currentPage
					val mode = if (entry.startsWith("SymbolsMulti")) InsertMode.MULTI else InsertMode.SINGLE
					allSymbols = AllSymbolsModeController(HierarchyLoader.get(assets).symbolTree, mode, entry)
					pages["AllSymbols"] = buildAllSymbolsPage()
					log(DebugCategory.Lifecycle, "[KF_SymbolMode] Enter ALL SYMBOLS from '$entry' mode=$mode")
					setCurrentPage("AllSymbols")
				}

				KF_AllSymbolsDescend -> {
					allSymbols?.descend(arg as Int)
					pages["AllSymbols"] = buildAllSymbolsPage()
					forceUpdateUi(true)
				}

				KF_AllSymbolsMore -> {
					allSymbols?.more()
					pages["AllSymbols"] = buildAllSymbolsPage()
					forceUpdateUi(true)
				}

				KF_AllSymbolsAscend -> {
					val ctrl = allSymbols
					if (ctrl != null && ctrl.ascend()) {
						pages["AllSymbols"] = buildAllSymbolsPage()
						forceUpdateUi(true)
					} else {
						val back = ctrl?.entryPage ?: "Symbols1"
						allSymbols = null
						setCurrentPage(back)
					}
				}

				KF_AllSymbolsPick -> {
					val ch = arg as String
					state.immedCharCount = ch.length
					onImmediateOutput(ch)
					state.immedCharString += ch
					state.currentSelection = null
					state.systemSelectionList.clear()
					undoStack.clear()
					updateAmbiguousKeySequence()
					maybeNgbSentenceBoundary(ch)
					val ctrl = allSymbols
					if (ctrl?.insertMode == InsertMode.MULTI) {
						ctrl.reset()
						pages["AllSymbols"] = buildAllSymbolsPage()
						forceUpdateUi(true)
					} else {
						allSymbols = null
						setCurrentPage(if (state.subModeCaller == "LetterSymbol") "LetterSymbol" else "Main")
					}
				}

				KF_ScrollUp -> {
					log(DebugCategory.Lifecycle, "[KF_ScrollUp] Page up")
					onScroll(CURSOR_UP)
				}
				KF_ScrollDown -> {
					log(DebugCategory.Lifecycle, "[KF_ScrollDown] Page down")
					onScroll(CURSOR_DOWN)
				}

				KF_SetMarkA -> {
					log(DebugCategory.Lifecycle, "[KF_SetMarkA] Setting bookmark A")
					onBookmark(KF_SetMarkA, false)
				}
				KF_SetMarkB -> {
					log(DebugCategory.Lifecycle, "[KF_SetMarkB] Setting bookmark B")
					onBookmark(KF_SetMarkB, false)
				}
				KF_JumpToMarkA -> {
					log(DebugCategory.Lifecycle, "[KF_JumpToMarkA] Jumping to bookmark A (selecting=$isSelectingText)")
					onBookmark(KF_JumpToMarkA, isSelectingText)
				}
				KF_JumpToMarkB -> {
					log(DebugCategory.Lifecycle, "[KF_JumpToMarkB] Jumping to bookmark B (selecting=$isSelectingText)")
					onBookmark(KF_JumpToMarkB, isSelectingText)
				}

				// ── Settings ─────────────────────────────────────
				KF_EnterSettings -> {
					log(DebugCategory.Lifecycle, "[KF_EnterSettings] Entering settings mode")
					enterSettingsMode()
				}
				KF_OpenNavigationKeyboard -> {
					log(DebugCategory.Lifecycle, "[KF_OpenNavigationKeyboard] Requesting Nav keyboard")
					onOpenNavigationKeyboard()
				}
				KF_SettingsKey -> {
					val keyIdx = arg as? Int ?: 0
					log(DebugCategory.Lifecycle, "[KF_SettingsKey] Settings key $keyIdx pressed")
					settingsController?.handleKey(keyIdx)
				}
			}
		}
		// CKCK Test whether state.listFunctionCount > 0; if so, do not add words...
		if (state.currentPage == StartingPage) {
			// If a pull-in just completed (e.g. UnDo Context 8), skip redundant
			// wldSelection/updateUi — runPullInFlow already set up the UI via forceUpdateUi.
			if (skipPostProcessingAfterPullIn) {
				skipPostProcessingAfterPullIn = false
				log(
					DebugCategory.UndoFlow,
					"[buttonPressed] Skipping post-processing — pull-in already set up UI",
				)
				return
			}
			// Apply any pending highlight update before checking abort
			applyPendingHighlight?.invoke()
			// Abort guard: if another non-Select key is already queued, skip expensive
			// wldSelection/updateUi — state updates above are already applied.
			if (shouldAbort?.invoke() == true && !isSelectKey) {
				log(
					DebugCategory.Lifecycle,
					"[buttonPressed] Skipping wldSelection/updateUi — next key already queued",
				)
				return
			}
			if (isSelectKey || undoOnlyReversedSelect) {
				undoOnlyReversedSelect = false
			} else {
				// CK if (state.listFunctionCount == 0) {
				if (!state.listFunctionPresent) {
					val dispatcher = wldDispatcher
					val wScope = wldScope
					if (dispatcher != null && wScope != null && shouldAbort != null) {
						// ── Async path (head tracking): run BFS on coroutine dispatcher ──
						val capturedKeys = ambigKeySequenceNumbers()
						val myGeneration = wldGeneration
						val capturedIsAmbigKey = isAmbigKey
						wScope.launch(dispatcher) {
							// The BFS iterates the WLD trie, which init()/vocab reload/merge/clear mutate
							// under vocabLock — take it here too so this worker-thread read can't race a
							// concurrent mutation (ConcurrentModificationException / torn reads / closed DB).
							val result = synchronized(vocabLock) { wldSelectionInternal(capturedKeys, shouldAbort) }
							if (shouldAbort.invoke()) {
								log(
									DebugCategory.Lifecycle,
									"[buttonPressed] Worker aborted wldSelection — next key arrived mid-search",
								)
								return@launch
							}
							withContext(Dispatchers.Main) {
								// Check generation — if a newer key has run Phase 1, discard this stale result
								if (wldGeneration != myGeneration) {
									log(
										DebugCategory.Lifecycle,
										"[buttonPressed] Discarding stale wldSelection result (gen $myGeneration vs $wldGeneration)",
									)
									return@withContext
								}
								// Apply result on main thread
								lastSearchTerminationCode = result.termination
								lastSearchMaxDepth = result.maxDepth
								lastSearchExaminedNodes = result.examinedNodes
								lastSearchElapsedMs = result.elapsedMs
								state.emptyAmbigSequence = result.emptyAmbigSequence
								ngbConfPending = result.ngbConf
								val adjusted = result.candidates.map { original ->
									val mutable = original.toMutableMap()
									val out = mutable["output"] as? String
									if (out != null) {
										mutable["canonicalOutput"] = out
									}
									mutable
								}.onEach {
									if (it["type"] == "L") {
										it["countOfOccurrence"] = ((it["countOfOccurrence"] as Int) * 0.1).toInt()
									}
								}
								val finalSel = applyShiftAndCaps(adjusted)
								val hasSystemEntries = state.systemSelectionList.isNotEmpty()
								val noWordEntries = state.emptyAmbigSequence && capturedIsAmbigKey
								if (!hasSystemEntries && noWordEntries) {
									log(
										DebugCategory.AmbigBuffer,
										"[buttonPressed] Async: ambiguous key produced no results - reverting state and beeping",
									)
									onErrorBeep(false)
									if (undoStack.isNotEmpty()) {
										restoreStateFromUndo()
									} else {
										if (state.keyHistory.isNotEmpty()) state.keyHistory.removeAt(state.keyHistory.lastIndex)
										if (state.ambiguousKeySequence.isNotEmpty()) state.ambiguousKeySequence.removeAt(state.ambiguousKeySequence.lastIndex)
									}
									updateUi(false)
									return@withContext
								}
								updateSelectionList(listOf(state.systemSelectionList, finalSel), state.currentSelection)
								updateUi(false)
							}
						}
						return // Don't block main thread — Phase 2 runs async
					}
					// ── Synchronous path (touch/switch/gamepad): unchanged ──
					val wldList = wldSelection(shouldAbort, applyPendingHighlight)
					// If aborted mid-search, skip the rest
					applyPendingHighlight?.invoke()
					if (shouldAbort?.invoke() == true) {
						log(
							DebugCategory.Lifecycle,
							"[buttonPressed] Aborted after wldSelection — next key arrived mid-search",
						)
						return
					}
					val adjusted = wldList.map { original ->
						val mutable = original.toMutableMap()
						val out = mutable["output"] as? String
						if (out != null) {
							mutable["canonicalOutput"] = out
						}
						mutable
					}.onEach {
						if (it["type"] == "L") {
							it["countOfOccurrence"] = ((it["countOfOccurrence"] as Int) * 0.1).toInt()
						}
					}
					val finalSel = applyShiftAndCaps(adjusted)
					val hasSystemEntries = state.systemSelectionList.isNotEmpty()
					// val hasWordEntries = finalSel.isNotEmpty()
					val noWordEntries = state.emptyAmbigSequence && isAmbigKey
					if (!hasSystemEntries && noWordEntries) {
						log(
							DebugCategory.AmbigBuffer,
							"[buttonPressed] Ambiguous key produced no results - reverting state and beeping",
						)
						onErrorBeep(false)
						if (undoStack.isNotEmpty()) {
							restoreStateFromUndo()
						} else {
							if (state.keyHistory.isNotEmpty()) state.keyHistory.removeAt(state.keyHistory.lastIndex)
							if (state.ambiguousKeySequence.isNotEmpty()) state.ambiguousKeySequence.removeAt(state.ambiguousKeySequence.lastIndex)
						}
						return
					}
					updateSelectionList(listOf(state.systemSelectionList, finalSel), state.currentSelection)
				} else {
					updateSelectionList(listOf(state.systemSelectionList), state.currentSelection)
				}
			}
			debugLog("[POST updateSelectionList 1] currentSelection=${state.currentSelection}   listFunctionCount=${state.listFunctionCount} ")
			// Final abort check before expensive UI rendering
			// (Select key must always render — its KF_Select handler already ran above)
			applyPendingHighlight?.invoke()
			if (shouldAbort?.invoke() == true && !isSelectKey) {
				log(
					DebugCategory.Lifecycle,
					"[buttonPressed] Skipping updateUi — next key arrived before rendering",
				)
				return
			}
			updateUi(false)
		}
	}

	private fun updateUi(suppressTopCandidate: Boolean = false, forceSelectedCandidate: String? = null, forceTopCandidate: String? = null) {
		if (suppressUi) {
			debugLog("[updateUi 1] suppressUi= TRUE     UI update suppressed")
			return
		}
		debugLog("[updateUi 2] suppressTopCandidate=$suppressTopCandidate, selectionList.size=${selectionList.size},  currentSelection=${state.currentSelection}   state.outputString=${state.outputString}")
		val keyLabels = renderKeyLabels()
		// Null in paged word selection: those grids carry page words (center cell
		// included) and must not get tone forms or the center-cell nudge.
		val tavToneLabels = if (toneAfterVowelActive() && state.pagedSelectPage == null) {
			// Post-selection: empty map (not null) so tone keys' center columns
			// CLEAR — the initial-state face, not the dying word's forms.
			if (postSelectionState()) emptyMap() else computeTavToneFormLabels()
		} else {
			null
		}
		val keyLabelGrids = renderKeyLabelGrids(tavToneLabels)
		val selectedEntry = state.currentSelection?.let { selectionList.getOrNull(it) }
		val outWithSel =
			if (selectedEntry?.get("type") in listOf("X", "L", "E", "2", "B")) {
				state.outputString + (selectedEntry?.get("output") as? String ?: "")
			} else {
				state.outputString
			}

		var centerPreview = forceSelectedCandidate
		if (centerPreview == null) {
			centerPreview =
				// Paged selection (Cliff, 2026-08-13, superseding issue-3 option
				// (a)): a page is a MENU — its first cell holds no likelihood
				// claim (negligible in ordinary pages, none at all in a family
				// group), so an open page leaves the provisional composing text
				// UNCHANGED: the previously selected row still stands (it is
				// also what a terminator commits — currentSelection persists
				// through paging), and a BOS/list-restart page stays null.
				if (!suppressTopCandidate &&
					selectedEntry?.get("type") in listOf("X", "L", "E", "2", "B", "PH", "N")
				) {
					selectedEntry?.get("output") as? String
				} else if (state.outputString.isNotEmpty()) {
					state.outputString
				} else if (state.isSpellingMode) {
					state.immedCharString
				} else if (isNumericPage() && state.numericString.isNotEmpty()) {
					state.numericString
				} else {
					null
				}
		}

		val keyHist = buildString {
			var first = true
			state.keyHistory.forEach { k ->
				var d = k.display.lowercase(Locale.getDefault())
				if (first && state.shiftState) {
					d = d.uppercase(Locale.getDefault())
				}
				if (isCapsActive()) {
					d = d.uppercase(Locale.getDefault())
				}
				append(" ").append(d)
				first = false
			}
		}.let {
			val parts = it.split(" ")
			if (parts.size > 20) "..." + parts.takeLast(19).joinToString(" ") else it
		}

		val ambig = state.ambiguousKeySequence.joinToString(separator = " ") { it.display }

		// Build ambiguous key label grids for graphical display
		val ambigKeyLabels = state.ambiguousKeySequence.map { key ->
			if (key.label != null) {
				// Extract alphabetic characters (uppercase) and punctuation from each of the 9 cells
				// Omit list-function indicators (SYM, FNS, NAV, etc.)
				key.label.map { cell ->
					// Keep letters (uppercase) and punctuation, but filter out list-function indicators
					val filtered = cell.filter { char ->
						char.isLetter() ||
							char.isDigit() ||
							char in listOf('\'', '-', '.', ',', ';', ':', '`', '"', '!', '?', '(', ')', '[', ']', '{', '}', '/', '\\', '|', '&', '*', '+', '=', '_', '@', '#', '$', '%', '^', '~')
					}
					// Convert letters to uppercase, keep punctuation as-is
					filtered.map { if (it.isLetter()) it.uppercaseChar() else it }.joinToString("")
				}
			} else {
				// Single label key - not typically in ambiguous sequence, but handle it
				List(9) { "" }
			}
		}

		// Determine the top-of-list word-like candidate (not selecting it)
		var topCandidate = forceTopCandidate
		var topCandidateType: String? = null
		if (topCandidate == null) {
			topCandidate = if (state.systemSelectionList.isNotEmpty() ||
				suppressTopCandidate ||
				(state.ambiguousKeySequence.isEmpty() && state.currentSelection == null)
			) {
				// When hint/placeholders (type "P") are present, do not preview any
				// word. Same at the zero-K window (empty sequence, no selection):
				// the follower list is a MENU — composing must not start until the
				// user acts, or every paged commit would inject phantom text.
				debugLog("[updateUi 3] topCandidate=null (systemList.size=${state.systemSelectionList.size}, suppressTop=$suppressTopCandidate)")
				null
			} else {
				val idx = selectionList.indexOfFirst {
					val t = it["type"] as? String
					// "N" included (Cliff, issue 2): composing is WYSIWYG — it always
					// shows the top/current list item, predictions included, and a
					// terminator commits exactly what composing shows.
					t == "X" || t == "L" || t == "E" || t == "2" || t == "B" || t == "PH" || t == "N"
				}
				val result = if (idx >= 0) (selectionList[idx]["output"] as? String) else null
				topCandidateType = selectionList.getOrNull(idx)?.get("type") as? String
				debugLog("[updateUi 4] topCandidate='$result' (found at idx=$idx)")
				result
			}
		}

		// NGB-D: consume the confidence features computed with this list state.
		ngbConfObserve(topCandidate)

		debugLog("[updateUi 5] Sending snapshot: topCandidate='$topCandidate', centerPreview='$centerPreview', currentPage='${state.currentPage}', baseOutput='${state.outputString}'")

		val selectedType = selectionList.getOrNull(state.currentSelection ?: -1)?.get("type") as? String

		// Word the history keys are forming: the selected candidate, else the top-of-list
		// candidate previewed in the center. Phrase types are excluded — their output does
		// not map char-per-key onto the ambiguous sequence.
		val historyWordTypes = listOf("X", "L", "E", "2", "B")
		val historyHighlightWord = when {
			!prefs.getBoolean(Constants.KEY_KEY_HISTORY_HIGHLIGHT, true) -> null
			selectedEntry?.get("type") in historyWordTypes -> selectedEntry?.get("output") as? String
			topCandidateType in historyWordTypes -> topCandidate
			else -> null
		}

		// Build selection list with images for list functions
		val showSort = prefs.getBoolean("show_sort_metric", false)

		// Calculate image size based on selection list dimensions
		// Use 80% of the smaller dimension (width or height) to ensure images fit
		val density = context.resources.displayMetrics.density
		val imageSizePx = if (selectionListWidth > 0 && selectionListHeight > 0) {
			val smallerDimension = kotlin.math.min(selectionListWidth, selectionListHeight)
			(smallerDimension * 0.80f).toInt().coerceAtLeast(24)
		} else {
			// Fallback to keyboard-based sizing if dimensions not yet available
			val kbRatio = prefs.getFloat(KEY_KEYBOARD_SIZE_RATIO, 0.55f)
			val keySizeDp = 96f * kbRatio / 0.55f
			val imageSizeDp = keySizeDp * 0.85f
			(imageSizeDp * density).toInt().coerceAtLeast(24)
		}.coerceAtMost((96 * density).toInt()) // Cap keyboard layout images so they don't dominate the list

		val lightGreenColor = 0xFF90EE90.toInt()
		val darkGreenColor = 0xFF2E7D32.toInt()
		val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
		val selectionHighlightColor = if (isDarkMode) darkGreenColor else lightGreenColor
		// Family expansion: pale blue flags expandable rows AND the inserted
		// group — the same hue binds the trigger to its result (Cliff's spec).
		val familyHintColor = if (isDarkMode) 0xFF1565C0.toInt() else 0xFFB3E5FC.toInt()

		// Column split by height units (text row = 1 line, image row = its height in
		// lines) — count-based splits let image pages overflow the column's pixel
		// budget. Column 0 absorbs any overflow: it scrolls with selection pinning,
		// while the extra columns are static and must fit exactly.
		val imageRowUnits = if (selectionLineHeightPx > 0) {
			((imageSizePx + selectionLineHeightPx - 1) / selectionLineHeightPx).coerceAtLeast(1)
		} else {
			1
		}
		val columnAssignment = assignSelectionColumns(
			unitWeights = selectionList.map { if ((it["type"] as? String) == "P") imageRowUnits else 1 },
			unitsPerColumn = itemsPerColumn,
			maxColumns = maxColumns,
		)
		val columnsNeeded = (columnAssignment.lastOrNull() ?: 0) + 1
		val columnItemCounts = IntArray(columnsNeeded).also { counts ->
			columnAssignment.forEach { counts[it]++ }
		}
		val columnFirstIndex = IntArray(columnsNeeded).also { firsts ->
			for (c in 1 until columnsNeeded) firsts[c] = firsts[c - 1] + columnItemCounts[c - 1]
		}

		// PAGE-BASED mode previews the page structure: listed words singly, then
		// tab-aligned groups of 6 mirroring the letter-key pages.
		val pagedGroupedBuffer = if (pagedSelectionEnabled() && selectionList.isNotEmpty()) {
			buildPagedSelectionBuffer(selectionHighlightColor, familyHintColor, imageSizePx)
		} else {
			null
		}

		// Create builders for each column
		val selectionListBuilders = pagedGroupedBuffer?.let { listOf(it) }
			?: (0 until columnsNeeded).map { SpannableStringBuilder() }

		if (pagedGroupedBuffer == null && selectionList.isNotEmpty()) {
			selectionList.forEachIndexed { index, item ->
				val columnIdx = columnAssignment[index]
				val targetBuilder = selectionListBuilders[columnIdx]
				val indexInColumn = index - columnFirstIndex[columnIdx]
				val itemsInThisColumn = columnItemCounts[columnIdx]

				if (indexInColumn > 0) targetBuilder.append("\n")

				val disp = (item["display"] ?: item["output"]) as String
				val type = item["type"] as? String
				val isSelected = index == state.currentSelection
				val lineStart = targetBuilder.length

				// Check if this is a list function (type "P") — use bitmap cache
				// Invalidate cache if image size changed (keyboard resize)
				if (scaledBitmapCacheSize != imageSizePx) {
					scaledBitmapCache.clear()
					scaledBitmapCacheSize = imageSizePx
				}
				val cachedScaled = if (type == "P") scaledBitmapCache[disp] else null
				val listFunctionBitmap = if (type == "P" && cachedScaled == null) getListFunctionBitmap(disp) else null
				if (type == "P" && (cachedScaled != null || listFunctionBitmap != null)) {
					// Render as image using cached scaled bitmap
					val scaledBitmap = cachedScaled ?: run {
						val src = listFunctionBitmap!!
						val scale = kotlin.math.min(
							imageSizePx.toFloat() / src.width,
							imageSizePx.toFloat() / src.height,
						)
						val sw = (src.width * scale).toInt()
						val sh = (src.height * scale).toInt()
						Bitmap.createScaledBitmap(src, sw, sh, true).also {
							scaledBitmapCache[disp] = it
						}
					}
					val scaledWidth = scaledBitmap.width
					val scaledHeight = scaledBitmap.height

					targetBuilder.append(" ") // Space before image
					val imageStart = targetBuilder.length
					targetBuilder.append(" ") // Placeholder for image
					val imageEnd = targetBuilder.length
					val lineEnd = targetBuilder.length

					// Set background span FIRST so it draws behind the image (selection list highlight for image rows).
					if (isSelected) {
						targetBuilder.setSpan(
							FullWidthLineBackgroundSpan(
								selectionHighlightColor,
								extendUpHalfGap = indexInColumn > 0,
								extendDownHalfGap = indexInColumn < itemsInThisColumn - 1,
								minLineHeightPx = scaledHeight,
							),
							lineStart,
							lineEnd,
							SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
						)
					}

					// Create ImageSpan with proper alignment (drawn after background)
					val drawable =
						android.graphics.drawable.BitmapDrawable(context.resources, scaledBitmap)
					drawable.setBounds(0, 0, scaledWidth, scaledHeight)
					val imageSpan = ImageSpan(drawable, ImageSpan.ALIGN_BASELINE)
					targetBuilder.setSpan(
						imageSpan,
						imageStart,
						imageEnd,
						SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
					)
				} else {
					// Render as text. SLS observability tiers (Cliff, 2026-08-10):
					// the switch is the master; the Dev "detail tier" slider picks
					// how much appends — see [sortMetricTag]. Search timing only
					// returns at the full tier (the old always-on token was noise).
					val sortTier = if (showSort) {
						prefs.getInt(DeveloperSettingsActivity.KEY_SORT_METRIC_VERBOSITY, 1).coerceIn(1, 4)
					} else {
						0
					}
					val timeToken = if (sortTier >= 4 && index == 0) {
						String.format(
							Locale.getDefault(),
							" {%.2f}, D=%d, MEN=%d, T=%s,",
							lastSearchElapsedMs,
							lastSearchMaxDepth,
							lastSearchExaminedNodes,
							lastSearchTerminationCode.ifEmpty { "-" },
						)
					} else {
						""
					}
					val taggable = type == "X" ||
						type == "L" ||
						type == "E" ||
						type == "2" ||
						type == "B" ||
						type == "PH" ||
						type == "N"
					val text = when {
						sortTier > 0 && taggable ->
							"$disp$timeToken ${sortMetricTag(item, type ?: "?", sortTier)}"

						else -> {
							"$disp$timeToken"
						}
					}
					targetBuilder.append(text)

					if (text.contains('\t')) {
						val tabPos = (200 * context.resources.displayMetrics.density).toInt()
						targetBuilder.setSpan(
							android.text.style.TabStopSpan.Standard(tabPos),
							lineStart,
							targetBuilder.length,
							SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
						)
					}

					// Family tint (expandable rows + inserted members) first —
					// the selection highlight draws over it.
					if (item["familyExpand"] == true || familyExpandEligible(item)) {
						targetBuilder.setSpan(
							FullWidthLineBackgroundSpan(familyHintColor),
							lineStart,
							targetBuilder.length,
							SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
						)
					}

					// Add full-width background highlight for selected text item
					if (isSelected) {
						val lineEnd = targetBuilder.length
						targetBuilder.setSpan(
							FullWidthLineBackgroundSpan(selectionHighlightColor),
							lineStart,
							lineEnd,
							SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
						)
					}
				}
			}
		}

		debugLog("[updateUi 6] Sending snapshot: state.isSpellingMode=${state.isSpellingMode},  topCandidate='$topCandidate', centerPreview='$centerPreview'")

		val currentPageKeys = pages[state.currentPage] ?: emptyList()
		val ambiguousKeyMaskRaw = currentPageKeys.map { def ->
			def.functions.any { it.first == KF_Ambig }
		}
		val ambiguousKeyMask = if (ambiguousKeyMaskRaw.size < NumberOfKeys) {
			ambiguousKeyMaskRaw + List(NumberOfKeys - ambiguousKeyMaskRaw.size) { false }
		} else {
			ambiguousKeyMaskRaw
		}
		val localHints = if (shouldShowRootHints()) {
			debugLog(
				DebugCategory.WordDb,
				"[rootHints] updateUi root hints path: ambigSeq=${state.ambiguousKeySequence.size} keyHistory=${state.keyHistory.size} selectCount=${state.selectKeyCount}",
			)
			val masks = buildNextLetterMaskConfig()
			val maskKey = Triple(masks.anyFreqMask, masks.minFreqMask, masks.minFreqClass)
			if (cachedRootHints != null && cachedRootHintsMaskKey == maskKey) {
				cachedRootHints!!.also { nextLetterHints = it }
			} else {
				computeRootLetterHints(
					masks.anyFreqMask,
					masks.minFreqMask,
					masks.minFreqClass,
				).also {
					cachedRootHints = it
					cachedRootHintsMaskKey = maskKey
					nextLetterHints = it
				}
			}
		} else if (state.ambiguousKeySequence.isNotEmpty()) {
			computeNextLetterHints()
		} else {
			emptySet()
		}
		val accentHints = if (shouldShowRootHints()) {
			val accentConfig = buildAccentMaskConfig()
			if (cachedAccentRootHints != null && cachedAccentRootHintsMaskKey == accentConfig) {
				cachedAccentRootHints!!
			} else {
				computeAccentRootHints().also {
					cachedAccentRootHints = it
					cachedAccentRootHintsMaskKey = accentConfig
				}
			}
		} else if (state.ambiguousKeySequence.isNotEmpty()) {
			computeAccentNextLetterHints()
		} else {
			emptySet()
		}
		val highlightNextLetters =
			showNextLetterHints &&
				(localHints.isNotEmpty() || accentHints.isNotEmpty() || state.ambiguousKeySequence.isNotEmpty())

		val spellingCenterText = if (state.isSpellingMode) formatSpellingBanner(state.customWordString) else null

		val editModeCenterText = if (state.currentPage.startsWith("editMode")) {
			when (state.currentPage) {
				"editMode2" -> context.getString(R.string.edit_center_clipboard)
				"editMode3" -> context.getString(R.string.edit_center_adjust_case)
				else -> when (cursorMovementMode) {
					MOVEMENT_CHARACTER_LINE -> context.getString(R.string.edit_center_character_line)
					MOVEMENT_WORD_SENTENCE -> context.getString(R.string.edit_center_word_sentence)
					MOVEMENT_PARAGRAPH_PAGE -> context.getString(R.string.edit_center_paragraph_page)
					MOVEMENT_BOOKMARK -> context.getString(R.string.edit_center_bookmark)
					else -> null
				}
			}
		} else {
			null
		}

		val highlightedKeys = if (isSelectingText && (state.currentPage == "editMode1" || state.currentPage == "editModeBookmark")) {
			setOf(0)
		} else {
			emptySet<Int>()
		}

		// ALL SYMBOLS: the center square names the current set (category, or "ALL SYMBOLS" at the root),
		// mapped to a translatable string resource.
		val allSymbolsCenterText = allSymbols?.takeIf { state.currentPage == "AllSymbols" }?.let { ctrl ->
			if (ctrl.atRoot) context.getString(R.string.jtui_symcat_all) else allSymbolsCategoryLabel(ctrl.currentSetName)
		}

		// Center-square live surface (sls.md "Center-square surface", Cliff
		// 2026-08-13): on the Main page the square mirrors EXACTLY the text
		// provisionally committed at the insertion point (WYSIWYG applied
		// case) and reports the state that styles it. Resting states — the
		// zero-K menu (BOS / post-page-pick), an open page display, a
		// non-word slot 1 — show an EMPTY square; the static page name now
		// labels only non-Main pages. Derived per update from list state,
		// never a maintained flag. state.outputString non-empty = the speak
		// -buffer flow, which keeps its accumulated-text center display.
		val centerWordTypes = listOf("X", "L", "E", "2", "B", "PH", "N")
		val selectedCenterWord =
			if (selectedEntry?.get("type") in centerWordTypes) selectedEntry?.get("output") as? String else null
		val centerLive: Pair<String?, CenterSquareState>? =
			if (state.currentPage == StartingPage && state.outputString.isEmpty()) {
				when {
					// An open page EMPTIES the square even though the prior
					// selection is still provisionally committed in the field
					// (the page-menu spec): stepping into a page declares an
					// intention to move OFF that word — keeping it green here
					// would read as the keyboard threatening to force it
					// (Cliff, 2026-08-13). Empty = the page-mode signal.
					state.pagedSelectPage != null -> null to CenterSquareState.EMPTY
					selectedCenterWord != null -> selectedCenterWord to CenterSquareState.ARMED
					state.currentSelection != null -> null to CenterSquareState.EMPTY
					topCandidate != null && selectionList.firstOrNull()?.get("type") in centerWordTypes ->
						topCandidate to if (ngbConfLastFired) CenterSquareState.SIGNAL else CenterSquareState.NEUTRAL
					else -> null to CenterSquareState.EMPTY
				}
			} else {
				null
			}
		val centerBannerText = spellingCenterText ?: editModeCenterText ?: allSymbolsCenterText
		val centerText: String
		val centerState: CenterSquareState
		when {
			centerBannerText != null -> {
				centerText = centerBannerText
				centerState = CenterSquareState.EMPTY
			}
			centerLive != null -> {
				centerText = centerLive.first.orEmpty()
				centerState = centerLive.second
			}
			else -> {
				centerText = centerPreview ?: pageDisplayName(state.currentPage)
				centerState = CenterSquareState.EMPTY
			}
		}

		onUiUpdate(
			JTUISnapshot(
				outputBuffer = outWithSel,
				ambigBuffer = ambig,
				selectionListBuffers = selectionListBuilders,
				keyHistoryBuffer = keyHist,
				centerSpace = centerText,
				centerSquareState = centerState,
				keyLabels = keyLabels,
				keyLabelGrids = keyLabelGrids,
				ambigKeyLabels = ambigKeyLabels,
				baseOutput = state.outputString,
				speechString = state.speechString,
				customWord = state.customWordString,
				speakState = state.speakState,
				shiftState = state.shiftState,
				isManualShift = state.isManualShift,
				isSpellingMode = state.isSpellingMode,
				topCandidateOutput = if (state.isSpellingMode) "" else topCandidate,
				selectedCandidateOutput = if (state.isSpellingMode) "" else centerPreview,
				topCandidateType = if (state.isSpellingMode) "" else topCandidateType,
				selectedCandidateType = if (state.isSpellingMode) "" else selectedType,
				highlightNextLetters = highlightNextLetters,
				nextLetterHints = if (highlightNextLetters) localHints else emptySet(),
				accentNextLetterHints = if (highlightNextLetters) accentHints else emptySet(),
				slotCellChars = activeLayoutSpec?.slotCharsByLabel ?: emptyMap(),
				nextToneKeys = if (highlightNextLetters) computeNextToneKeyHints() else null,
				tavToneFormKeys = tavToneFormKeysOnPage(tavToneLabels),
				ambiguousKeyMask = ambiguousKeyMask,
				highlightedKeyIndices = highlightedKeys,
				currentSelectionIndex = state.currentSelection,
				historyHighlightWord = if (state.isSpellingMode) null else historyHighlightWord,
			),
		)
	}

	private fun formatSpellingBanner(rawCustom: String): String {
		val headingTop = context.getString(R.string.jtui_spelling_banner_top)
		val headingMid = context.getString(R.string.jtui_spelling_banner_mid)
		val indent = "   "
		val customLines = formatCustomWordLines(rawCustom)
		return buildString {
			append(headingTop)
			append('\n')
			append(headingMid)
			if (customLines.isNotEmpty()) {
				append("\n\n")
				customLines.forEachIndexed { index, line ->
					if (index > 0) append('\n')
					append(indent)
					append(line)
				}
			}
		}
	}

	private fun formatCustomWordLines(raw: String): List<String> {
		val trimmed = raw.trim()
		if (trimmed.isEmpty()) return emptyList()
		val maxLineChars = 12
		var text = trimmed
		var needsEllipsis = false
		while (true) {
			val prefixLen = if (needsEllipsis) 3 else 0
			val firstLineCapacity = maxLineChars - prefixLen
			if (firstLineCapacity <= 0) {
				return listOf("..." + text.take(maxLineChars - 3))
			}
			val hyphenNeeded = text.length > firstLineCapacity
			val firstLineContentCapacity = if (hyphenNeeded) firstLineCapacity - 1 else firstLineCapacity
			val totalCapacity = firstLineContentCapacity + maxLineChars
			if (text.length > totalCapacity) {
				val dropCount = text.length - totalCapacity
				text = text.drop(dropCount)
				needsEllipsis = true
				continue
			}
			val prefix = if (needsEllipsis) "..." else ""
			if (!hyphenNeeded) {
				return listOf(prefix + text)
			}
			val contentLen = firstLineContentCapacity.coerceAtLeast(0)
			val firstContent = text.substring(0, contentLen)
			val remainder = text.substring(contentLen)
			val firstLine = prefix + firstContent + "-"
			val secondLine = remainder
			return listOf(firstLine, secondLine)
		}
	}

	private val selectTextLabel by lazy { context.getString(R.string.edit_key_select_text) }
	private val selectingTextLabel by lazy { context.getString(R.string.edit_key_selecting_text) }

	private fun renderKeyLabels(): List<String> {
		val keyList = pages[state.currentPage] ?: return List(NumberOfKeys) { "" }
		// ALL SYMBOLS labels are caseless glyphs — render verbatim (never uppercase, e.g. µ must not become Μ).
		if (state.currentPage == "AllSymbols") {
			return keyList.map { it.singleLabel ?: labelGridToString(it.label) }
		}
		return keyList.map { key ->
			if (key.singleLabel == selectTextLabel) {
				if (isSelectingText) selectingTextLabel else selectTextLabel
			} else if ((state.currentPage == "editMode1" || state.currentPage == "editModeBookmark") && key.functions.any { it.first == KF_CycleCursorMode }) {
				when (cursorMovementMode) {
					MOVEMENT_CHARACTER_LINE -> context.getString(R.string.edit_key_mode_character_line)
					MOVEMENT_WORD_SENTENCE -> context.getString(R.string.edit_key_mode_word_sentence)
					MOVEMENT_BOOKMARK -> context.getString(R.string.jtui_label_bookmark_mode)
					else -> context.getString(R.string.edit_key_mode_paragraph_page)
				}
			} else if (state.currentPage == "editMode1" && key.functions.any { it.first in setOf(KF_CursorUp, KF_CursorDown, KF_CursorLeft, KF_CursorRight) }) {
				val fn = key.functions.first { it.first in setOf(KF_CursorUp, KF_CursorDown, KF_CursorLeft, KF_CursorRight) }.first
				editModeArrowLabel(fn)
			} else if (key.functions.any { it.first == KF_SpeakSelectionOrSentence }) {
				if (isSelectingText) {
					context.getString(R.string.edit_key_speak_selection)
				} else {
					context.getString(R.string.edit_key_speak_sentence)
				}
			} else {
				// Dynamic label for Select key: "SELECT\n<next word>" when available
				if (key.singleLabel == context.getString(R.string.jtui_btn_select)) {
					val selectLabel = context.getString(R.string.jtui_btn_select)
					val next = nextSelectLabel()
					if (next != null) {
						"$selectLabel\n$next"
					} else {
						selectLabel
					}
				} else if (key.singleLabel?.startsWith(context.getString(R.string.jtui_label_speech)) == true) {
					val speechLabel = context.getString(R.string.jtui_label_speech)
					val onLabel = if (state.speakState) context.getString(R.string.jtui_label_on_active) else context.getString(R.string.jtui_label_on)
					val offLabel = if (state.speakState) context.getString(R.string.jtui_label_off) else context.getString(R.string.jtui_label_off_active)
					"$speechLabel\n$onLabel / $offLabel"
				} else if (key.singleLabel?.startsWith(context.getString(R.string.jtui_label_caps_lock).substringBefore("\n")) == true) {
					val capsLockLabel = context.getString(R.string.jtui_label_caps_lock)
					val onLabel = if (isCapsActive()) context.getString(R.string.jtui_label_on_active) else context.getString(R.string.jtui_label_on)
					val offLabel = if (isCapsActive()) context.getString(R.string.jtui_label_off) else context.getString(R.string.jtui_label_off_active)
					"$capsLockLabel\n$onLabel / $offLabel"
				} else if (key.singleLabel != null) {
					val label = key.singleLabel
					val isCaseKey = key.functions.any { it.first in setOf(KF_CaseToTitle, KF_CaseToUpper, KF_CaseToLower, KF_CaseToSentence) }
					if (isCaseKey) {
						label
					} else if (shouldShiftSingleLabel(label)) {
						applyShiftCapsToString(label)
					} else {
						label.uppercase(Locale.getDefault())
					}
				} else {
					// Ambiguous key label grid, apply shift/caps rules
					val base = labelGridToString(key.label)
					applyShiftCapsToString(base)
				}
			}
		}
	}

	private fun editModeArrowLabel(fn: Int): String {
		val arrow = when (fn) {
			KF_CursorUp -> "\u2191"
			KF_CursorDown -> "\u2193"
			KF_CursorLeft -> "\u2190"
			KF_CursorRight -> "\u2192"
			else -> ""
		}
		val isVertical = (fn == KF_CursorUp || fn == KF_CursorDown)
		val unitLabel = when (cursorMovementMode) {
			MOVEMENT_CHARACTER_LINE -> if (isVertical) context.getString(R.string.jtui_unit_line) else context.getString(R.string.jtui_unit_char)
			MOVEMENT_WORD_SENTENCE -> if (isVertical) context.getString(R.string.jtui_unit_sentence) else context.getString(R.string.jtui_unit_word)
			MOVEMENT_PARAGRAPH_PAGE -> if (isVertical) context.getString(R.string.jtui_unit_page) else context.getString(R.string.jtui_unit_para)
			else -> ""
		}
		return "$unitLabel\n$arrow"
	}

	private fun shouldShiftSingleLabel(label: String): Boolean {
		// Single letters used as symbols — Spell-mode letters, and numeric-punct metric prefixes
		// (k, m, e) — display in the case matching SHIFT/CAPS, mirroring how they are output.
		val followsShift = state.currentPage.startsWith("Spell") || isNumericPage()
		val isSingleAlpha = label.length == 1 && label[0].isLetter()
		return followsShift && isSingleAlpha
	}

	private fun isBetweenWords(): Boolean {
		if (state.currentPage != StartingPage) return false
		if (state.ambiguousKeySequence.isEmpty()) return true
		val last = state.keyHistory.lastOrNull()
		val lastIsSelect = last?.functions?.any { it.first == KF_Select } == true
		return lastIsSelect
	}

	/** Label variant of [nextSelectableWord] (Cliff's icon, 2026-08-13):
	 *  when the next press opens or ADVANCES a page group, the Select key
	 *  shows the generic page-group glyph instead of the page's top word —
	 *  the word would render too small to read, and the glyph says what the
	 *  press actually produces. Speech keeps [nextSelectableWord] (the top
	 *  word is speakable at any size). The sentinel is matched stringly in
	 *  SquareButton, like the Symbols1/Functions1 list-function names. */
	private fun nextSelectLabel(): String? {
		if (selectionList.isEmpty()) return null
		state.pagedSelectPage?.let { page ->
			val top = selectPreviewText(selectionList.getOrNull(pagedStartRow(page + 1)))
			return if (top != null) SELECT_PAGE_GROUP_SENTINEL else null
		}
		val currentIdx = state.currentSelection ?: -1
		if (wouldEnterPagedSelection(currentIdx, selectionList.size)) {
			val top = selectPreviewText(selectionList.getOrNull(pagedStartRow(0)))
			return if (top != null) SELECT_PAGE_GROUP_SENTINEL else null
		}
		return nextSelectableWord()
	}

	/** The Select key must always show what pressing Select NOW produces
	 *  (Cliff, device review 2026-08-08, issues 1/4): the next list item —
	 *  or, when the next press opens/advances a page, that page's TOP item. */
	private fun nextSelectableWord(): String? {
		if (selectionList.isEmpty()) return null
		state.pagedSelectPage?.let { page ->
			return selectPreviewText(selectionList.getOrNull(pagedStartRow(page + 1)))
		}
		val currentIdx = state.currentSelection ?: -1
		if (wouldEnterPagedSelection(currentIdx, selectionList.size)) {
			return selectPreviewText(selectionList.getOrNull(pagedStartRow(0)))
		}
		val start = currentIdx + 1
		if (start !in selectionList.indices) return null
		for (i in start until selectionList.size) {
			val text = selectPreviewText(selectionList[i])
			if (text != null) return text
		}
		return null
	}

	/** Preview text for one list item on the Select key; null for non-selectable rows. */
	private fun selectPreviewText(item: Map<String, Any?>?): String? {
		val type = item?.get("type") as? String ?: return null
		// Page entries (type "P") show dynamic Symbols/Functions/Navigation names;
		// phrases show only their abbreviation. "B" (TAV base rows) and "N" (NGB
		// predictions) are selectable and must preview like any word.
		if (type !in listOf("X", "L", "E", "2", "B", "N", "P", "PH")) return null
		val text = when (type) {
			"PH" -> (item["abbrev"] as? String)?.takeIf { it.isNotEmpty() }
			else -> (item["display"] ?: item["output"]) as? String
		}
		return text?.takeIf { it.isNotEmpty() }
	}

	/** Slot-cell label -> its characters: slotGroups is authoritative (elided/stacked displays). */
	private fun resolveSlotChars(cellLabel: String): List<String> = activeLayoutSpec?.slotGroups?.firstOrNull {
		it.display == cellLabel || it.chars.joinToString("") == cellLabel
	}?.chars ?: cellLabel.filter { it != '\n' }.map { it.toString() }

	// Level-3 slot-pick placements: digit slots (5 digits + punct) use column reading
	// order (Keys 0,3,5,2,4) with the punctuation on Key 7; smaller slots fill the
	// last N letter keys in page order, ending on the level-2 key pressed.
	private fun slotPickPlacements(slotChars: List<String>, pos: Int): List<Pair<Int, String>> {
		val digits = slotChars.filter { it.length == 1 && it[0].isDigit() }
		if (digits.size == 5) {
			return listOf(0, 3, 5, 2, 4).zip(digits) +
				listOfNotNull(slotChars.firstOrNull { it !in digits }?.let { 7 to it })
		}
		val anchorIdx = SPATIAL_PAGE_KEYS.indexOf(pos).takeIf { it >= 0 } ?: SPATIAL_PAGE_KEYS.lastIndex
		val startIdx = (anchorIdx - slotChars.size + 1).coerceAtLeast(0)
		return slotChars.mapIndexedNotNull { i, c -> SPATIAL_PAGE_KEYS.getOrNull(startIdx + i)?.to(c) }
	}

	private fun renderKeyLabelGrids(tavToneLabels: Map<Int, List<String>>? = null): List<List<String>> {
		val keyList = pages[state.currentPage] ?: return List(NumberOfKeys) { List(9) { "" } }
		// ALL SYMBOLS preview grids are caseless glyphs — render verbatim (no shift/caps transform).
		if (state.currentPage == "AllSymbols") {
			return keyList.map { it.label ?: List(9) { "" } }
		}
		// Paged word selection: each letter key shows its page word verbatim, centered.
		// Skip a stale page (list rebuilt underneath) — the key handlers clear it.
		state.pagedSelectPage?.takeIf { pagedStartRow(it) < selectionList.size }?.let { page ->
			val start = pagedStartRow(page)
			return keyList.map { key ->
				val ambigNum = key.functions.firstOrNull { it.first == KF_Ambig }?.second as? Int
				if (ambigNum != null && ambigNum in 0..5) {
					// Unused slots on the last page render a lone space: a truly empty grid
					// makes the view fall back to the key's letter label, and these keys
					// must read BLANK (pressing one just error-beeps).
					val word = selectionList.getOrNull(start + pagedOrdinalForAmbig(ambigNum))
						?.let { (it["display"] as? String).orEmpty() }
						?.takeIf { it.isNotEmpty() }
						?: " "
					List(9) { i -> if (i == 4) word else "" }
				} else {
					key.label ?: List(9) { "" }
				}
			}
		}
		val showHints = isBetweenWords() || postSelectionState()
		// TAV: tone keys' center column is the dynamic per-vowel tone-form
		// display — viable forms after the last keystroke, blank otherwise
		// (static tone labels are TAE-only). Injected AFTER the shift/caps
		// transform so the form case echoes the typed carrier, not the current
		// shift state.
		val tavToneKeyNums = if (tavToneLabels != null) {
			toneFoldForMode(layoutMode).values.mapTo(mutableSetOf()) { it.second }
		} else {
			emptySet()
		}
		// Badge positions follow the language's functionKeys (same source as the trigger
		// attachment in singleKeyPagesFor) — internal keyNum -> page position.
		val keyPagePos = intArrayOf(0, 2, 3, 4, 5, 7)
		val fnKeys = activeLayoutSpec?.functionKeys
		val symbolsHintPage = keyPagePos[fnKeys?.get("symbols") ?: 1]
		val functionsHintPage = keyPagePos[fnKeys?.get("functions") ?: 3]
		val navigationHintPage = keyPagePos[fnKeys?.get("navigation") ?: 5]
		return keyList.mapIndexed { index, key ->
			val base = (key.label ?: List(9) { "" }).toMutableList()
			if (showHints) {
				val defaultHint = when (index) {
					symbolsHintPage -> context.getString(R.string.jtui_hint_symbols)
					functionsHintPage -> context.getString(R.string.jtui_hint_functions)
					navigationHintPage -> context.getString(R.string.jtui_hint_navigation)
					else -> null
				}
				if (defaultHint != null) {
					val selectedItem = selectionList.getOrNull(state.currentSelection ?: -1)
					val selectedOutput = selectedItem?.get("output") as? String
					val useLabel = if (
						selectedItem?.get("type") == "P" &&
						selectedOutput != null &&
						key.singleKeyPages.isNotEmpty() &&
						selectedOutput in key.singleKeyPages
					) {
						selectedOutput
					} else {
						defaultHint
					}
					// Top-center cell: the bottom-center cell is the dynamic zone
					// (tone marks; future next-tone/after-vowel displays).
					base[1] = useLabel
				}
			}
			withTavToneForms(applyShiftCapsToGrid(base), key, tavToneLabels, tavToneKeyNums)
		}
	}

	/** Internal keyNums whose grids carry TAV tone forms on the CURRENT page —
	 *  guards the SquareButton nudge against non-letter pages, where the same
	 *  keyNums map to unrelated grid content. */
	private fun tavToneFormKeysOnPage(labels: Map<Int, List<String>>?): Set<Int> {
		val withForms = labels?.filterValues { it.isNotEmpty() }?.keys ?: return emptySet()
		if (withForms.isEmpty()) return emptySet()
		val pageAmbig = (pages[state.currentPage] ?: emptyList())
			.mapNotNullTo(mutableSetOf()) { k -> k.functions.firstOrNull { it.first == KF_Ambig }?.second as? Int }
		return withForms intersect pageAmbig
	}

	/**
	 * Applies the TAV center-column tone-form display to one key's cased grid (see
	 * renderKeyLabelGrids): forms occupy TAV_TONE_FORM_FILL cells by count, and the
	 * used cells read top→bottom in key-face order. Unused cells are left alone
	 * (root list-function badges live in cell 1); the static-label slot always
	 * resets.
	 */
	private fun withTavToneForms(grid: List<String>, key: KeyDef, labels: Map<Int, List<String>>?, toneKeyNums: Set<Int>): List<String> {
		if (labels == null) return grid
		val ambigNum = key.functions.firstOrNull { it.first == KF_Ambig }?.second as? Int ?: return grid
		if (ambigNum !in toneKeyNums) return grid
		val out = grid.toMutableList()
		out[TONE_LABEL_CELL] = ""
		val forms = labels[ambigNum].orEmpty()
		// Overflow beyond the column (unreachable at <=3 vowels per key) joins
		// into the last-assigned cell rather than dropping viable forms.
		val display = if (forms.size > TAV_TONE_FORM_FILL.size) {
			forms.take(TAV_TONE_FORM_FILL.size - 1) + forms.drop(TAV_TONE_FORM_FILL.size - 1).joinToString(" ")
		} else {
			forms
		}
		val cells = TAV_TONE_FORM_FILL.take(display.size).sorted() // row-major sort = top→bottom
		cells.forEachIndexed { i, cell -> out[cell] = display[i] }
		return out
	}

	private fun isCapsActive(): Boolean = state.capsState && !state.capsTempDisable

	private fun applyShiftCapsToGrid(grid: List<String>): List<String> {
		val reserved = setOf(
			context.getString(R.string.jtui_hint_symbols),
			context.getString(R.string.jtui_hint_functions),
			context.getString(R.string.jtui_hint_navigation),
			"Symbols1", "Symbols2", "Symbols3", "Functions1", "Functions2", "Navigation",
		)
		if (isCapsActive()) return grid.map { if (it in reserved) it else it.uppercase(Locale.getDefault()) }
		if (state.shiftState) return grid.map { if (it in reserved) it else it.uppercase(Locale.getDefault()) }
		return grid.map { if (it in reserved) it else it.lowercase(Locale.getDefault()) }
	}

	private fun labelGridToString(grid: List<String>?): String {
		if (grid == null || grid.size != 9) return ""
		val nbsp = "\u00A0" // non-breaking space keeps visual column alignment
		fun cell(s: String) = if (s.isEmpty()) nbsp else s
		fun row(a: String, b: String, c: String): String = "${cell(a)}$nbsp${cell(b)}$nbsp${cell(c)}"

		val top = row(grid[0], grid[1], grid[2])
		val mid = row(grid[3], grid[4], grid[5])
		val bot = row(grid[6], grid[7], grid[8])
		return listOf(top, mid, bot).joinToString("\n")
	}

	private fun applyShiftCapsToString(s: String): String = when {
		isCapsActive() -> s.uppercase(Locale.getDefault())
		state.shiftState -> s.uppercase(Locale.getDefault())
		else -> s.lowercase(Locale.getDefault())
	}

	private val wordEntryTypes = setOf("X", "L", "E", "2", "B", "PH", "N")

	private fun applyShiftAndCaps(list: List<MutableMap<String, Any?>>): List<Map<String, Any?>> {
		val nonWordEntries = mutableListOf<Map<String, Any?>>()
		val variants = mutableListOf<Pair<Map<String, Any?>, Int>>()

		list.forEach { entry ->
			val type = entry["type"] as? String
			val canonical = (entry["canonicalOutput"] as? String) ?: (entry["output"] as? String)
			if (type !in wordEntryTypes || canonical == null) {
				entry["appliedCaseForm"] = WordCaseForm.ORIGINAL
				entry["caseSource"] = CaseSource.DEFAULT
				nonWordEntries.add(entry.toMap())
				return@forEach
			}

			val caseCount = (entry["caseCount"] as? Int) ?: 0
			if (caseCount <= 0) return@forEach

			val forcedForm = entry["forcedCaseForm"] as? WordCaseForm
			val preference = entry["casePreference"] as? CasePreference

			val basePair = if (forcedForm != null) {
				forcedForm to CaseSource.USER_PREFERENCE
			} else {
				determineCaseForm(canonical, preference)
			}

			// Entries with preserveOriginalCase (e.g. custom words like "iOS", phrases)
			// should keep their exact casing unless CAPS LOCK is active.
			val preserveOriginal = entry["preserveOriginalCase"] == true && forcedForm == WordCaseForm.ORIGINAL

			val (finalForm, finalSource) = when {
				isCapsActive() -> WordCaseForm.UPPER to CaseSource.CAPS_LOCK
				preserveOriginal -> basePair
				state.isManualShift || state.shiftState -> WordCaseForm.TITLE to CaseSource.MANUAL_SHIFT
				state.capitalizePending -> {
					val reason = state.pendingAutoCapReason
					val src = when {
						reason == AutoCapReason.SENTENCE_START || reason == AutoCapReason.LINE_START || reason == AutoCapReason.FIELD_START -> CaseSource.AUTO_SENTENCE
						reason == AutoCapReason.ABBREVIATION -> CaseSource.AUTO_ABBREVIATION
						reason == AutoCapReason.MANUAL || state.isManualShift -> CaseSource.MANUAL_SHIFT
						else -> basePair.second
					}
					WordCaseForm.TITLE to src
				}
				else -> basePair
			}

			val finalOutput = applyCaseForm(canonical, finalForm)

			entry["output"] = finalOutput
			val currentDisplay = entry["display"]
			// Preserve custom display for phrase entries (PH) so abbreviation stays visible.
			if (type != "PH" && currentDisplay is String && currentDisplay.isNotEmpty()) {
				val altOut = entry["alternateOutput"] as? String
				if (altOut != null) {
					val altForm = entry["alternateForm"] as? WordCaseForm
					val shiftOverride = isCapsActive() || state.isManualShift || state.shiftState || state.capitalizePending
					val transformedAlt = when {
						shiftOverride -> finalOutput
						altForm != null -> applyCaseForm(canonical, altForm)
						else -> altOut
					}
					if (transformedAlt == finalOutput) {
						entry.remove("alternateOutput")
						entry.remove("alternateForm")
						entry["display"] = finalOutput
					} else {
						entry["alternateOutput"] = transformedAlt
						entry["display"] = "$finalOutput\t($transformedAlt)"
					}
				} else {
					entry["display"] = finalOutput
				}
			}
			entry["appliedCaseForm"] = finalForm
			entry["caseSource"] = finalSource
			variants.add(entry.toMap() to caseCount)
		}

		// Preserve incoming order (which reflects sortMetric ordering) while
		// deduping identical outputs. If a duplicate appears later, keep the
		// first occurrence to avoid reordering by count.
		val dedup = linkedMapOf<String, Triple<Map<String, Any?>, Int, Int>>() // out -> (entry, count, order)
		var order = 0
		variants.forEach { (entry, count) ->
			val outStr = entry["output"] as? String ?: return@forEach
			if (!dedup.containsKey(outStr)) {
				dedup[outStr] = Triple(entry, count, order)
			}
			order += 1
		}

		val sortedVariants = dedup.values.sortedBy { it.third }.map { it.first }
		return nonWordEntries + sortedVariants
	}

	private fun determineCaseForm(
		canonical: String,
		preference: CasePreference?,
	): Pair<WordCaseForm, CaseSource> {
		val result = when {
			isCapsActive() -> WordCaseForm.UPPER to CaseSource.CAPS_LOCK
			state.isManualShift || state.shiftState -> {
				// Manual or active shift: capitalize (Title case) the word
				WordCaseForm.TITLE to CaseSource.MANUAL_SHIFT
			}
			state.capitalizePending -> {
				val reason = state.pendingAutoCapReason
				val source = when {
					reason == AutoCapReason.SENTENCE_START || reason == AutoCapReason.LINE_START || reason == AutoCapReason.FIELD_START -> CaseSource.AUTO_SENTENCE
					reason == AutoCapReason.ABBREVIATION -> CaseSource.AUTO_ABBREVIATION
					state.isManualShift || reason == AutoCapReason.MANUAL -> CaseSource.MANUAL_SHIFT
					else -> CaseSource.DEFAULT
				}
				WordCaseForm.TITLE to source
			}
			else -> {
				val preferredForm = preference?.preferredForm ?: inferDefaultCase(canonical)
				val source = if (preference != null) CaseSource.USER_PREFERENCE else CaseSource.DEFAULT
				preferredForm to source
			}
		}
		if (result.second != CaseSource.DEFAULT) {
			debugLog(
				DebugCategory.ShiftState,
				"[determineCaseForm] canonical='$canonical', appliedForm=${result.first}, source=${result.second}",
			)
		}
		return result
	}

	private fun inferDefaultCase(word: String): WordCaseForm {
		if (word.isEmpty()) return WordCaseForm.ORIGINAL
		val letters = word.filter { it.isLetter() }
		if (letters.isEmpty()) return WordCaseForm.ORIGINAL
		val allUpper = letters.all { it.isUpperCase() }
		val allLower = letters.all { it.isLowerCase() }
		val title = letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() }
		return when {
			allUpper -> WordCaseForm.UPPER
			title -> WordCaseForm.TITLE
			allLower -> WordCaseForm.LOWER
			else -> WordCaseForm.ORIGINAL
		}
	}

	private fun applyCaseForm(word: String, form: WordCaseForm): String {
		return when (form) {
			WordCaseForm.LOWER -> word.lowercase(Locale.getDefault())
			WordCaseForm.TITLE -> {
				val first = word.firstOrNull() ?: return word
				val head = first.uppercaseChar()
				val tail = if (word.length > 1) word.substring(1).lowercase(Locale.getDefault()) else ""
				"$head$tail"
			}
			WordCaseForm.UPPER -> word.uppercase(Locale.getDefault())
			WordCaseForm.ORIGINAL -> word
		}
	}

	private fun updateSelectionList(lists: List<List<Map<String, Any?>>>, curIndex: Int?) {
		cancelPendingExpand()
		familyListedRows = null
		familyNewMemberCache.clear()
		// A rebuild under an open Select episode means the user moved on without
		// committing from that list; the fired flag belongs to the old list.
		selEpisodeAbandon()
		ngbConfLastFired = false
		selectionList = lists.flatten()
		selectionGeneration += 1
		state.selectionGen = selectionGeneration
		state.currentSelection = curIndex
		// Any rebuild invalidates an open page — paging is only meaningful for the
		// list it was opened on (language switches, new sequences, restores).
		state.pagedSelectPage = null
		// PAGE-BASED mode: every page slot is a single case form, so expand collapsed
		// "word (Word)" pairs in the paged region up front — page groups stay stable
		// and each variant gets its own key. Linear rows keep the pair-skip behavior.
		if (pagedSelectionEnabled()) expandAlternatesFrom(pagedFirstRow())
		// Re-apply the pull-in relocation pin: an async search completion
		// rebuilding this list in natural order must not bury the pulled
		// word back inside a page group mid-reconstruction.
		applyPullInPin()
	}

	private fun expandAlternatesFrom(startIdx: Int) {
		var i = startIdx
		while (i < selectionList.size) {
			val item = selectionList[i]
			val hasPair = item["alternateOutput"] as? String != null &&
				item["alternateForm"] as? WordCaseForm != null
			if (hasPair) {
				expandAlternate(i)
				val cur = state.currentSelection
				if (cur != null && cur > i) state.currentSelection = cur + 1
				i += 2
			} else {
				i += 1
			}
		}
	}

	/**
	 * PAGE-BASED selection list: the listed rows render one per line, then each page
	 * group of [PAGED_WORDS_PER_PAGE] renders as three tab-aligned lines of two words
	 * (matching the letter-key column order), with a blank line between groups. The
	 * active page — or the single selected row — highlights in green.
	 */
	private fun buildPagedSelectionBuffer(highlightColor: Int, familyColor: Int, imageSizePx: Int): SpannableStringBuilder {
		val sb = SpannableStringBuilder()
		// First-page minimum (Cliff, 2026-08-09): when the list is too short
		// for paging to engage (wouldEnterPagedSelection's size gate), render
		// EVERYTHING as one continuous list — a page-styled group with its
		// blank separator line would masquerade as a page element the user
		// cannot actually enter.
		val pagingSuppressed = selectionList.size < pagedFirstRow() + PAGED_MIN_FIRST_PAGE
		val listed = if (pagingSuppressed) selectionList.size else pagedFirstRow().coerceAtMost(selectionList.size)
		val half = PAGED_WORDS_PER_PAGE / 2
		// Measured layout when the view has reported its metrics; otherwise fall back
		// to a fixed 200dp tab stop + character-count stacking.
		val measure = if (selectionListWidth > 0 && selectionListTextSizePx > 0f) {
			android.text.TextPaint().apply { textSize = selectionListTextSizePx }
		} else {
			null
		}
		val panelW = selectionListWidth.toFloat()
		val gapPx = 16 * context.resources.displayMetrics.density
		val defaultTab = (200 * context.resources.displayMetrics.density).toInt()

		fun disp(i: Int): String {
			val item = selectionList[i]
			return ((item["display"] ?: item["output"]) as? String).orEmpty()
		}

		fun highlightLine(lineStart: Int, minHeightPx: Int = 0) {
			sb.setSpan(
				FullWidthLineBackgroundSpan(highlightColor, minLineHeightPx = minHeightPx),
				lineStart,
				sb.length,
				SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
		}

		appendPagedLinearRows(sb, listed, imageSizePx, highlightColor, familyColor)

		fun appendGroupLine(leftText: String?, rightText: String?, leftRow: Int?, rightRow: Int?, tab: Int) {
			val lineStart = sb.length
			if (leftText != null) sb.append(leftText)
			if (rightText != null) sb.append("\t").append(rightText)
			sb.setSpan(
				android.text.style.TabStopSpan.Standard(tab),
				lineStart,
				sb.length,
				SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
			// Family tint first: an active selection highlight draws over it.
			applyFamilyTint(sb, lineStart, familyColor, listOf(leftRow, rightRow))
			val cur = state.currentSelection
			if (state.pagedSelectPage == null && cur != null && (cur == leftRow || cur == rightRow)) {
				highlightLine(lineStart)
			}
		}

		// Scan layout: page groups render ROW-major — two lines of three,
		// ranks 1-3 then 4-6 — mirroring the two key rows the scan sweeps
		// left-to-right, in the wide flat scan list (Cliff, 2026-08-13).
		// Other layouts keep the COLUMN-major two-column pairing below.
		val scanRows = scanLayoutActive()
		var start = listed
		var page = 0
		while (start < selectionList.size) {
			if (sb.isNotEmpty()) sb.append("\n\n")
			val groupStart = sb.length
			if (scanRows) {
				val third = (if (panelW > 0f) panelW / 3f else defaultTab.toFloat()).toInt()
				appendScanGroupRows(sb, start, half, third, familyColor, ::disp)
			} else {
				// Column 2 starts just past the group's widest left-column word.
				val tab = if (measure != null) {
					val lastLeft = minOf(start + half, selectionList.size) - 1
					val maxLeft = (start..lastLeft).maxOf { measure.measureText(disp(it)) }
					(maxLeft + gapPx).toInt().coerceAtMost((panelW * 0.6f).toInt())
				} else {
					defaultTab
				}
				appendColumnGroupLines(sb, start, half, tab, measure, panelW, ::disp, ::appendGroupLine)
			}
			if (state.pagedSelectPage == page) highlightLine(groupStart)
			start += PAGED_WORDS_PER_PAGE
			page += 1
		}
		return sb
	}

	/** Column-major page group (JT grid): three lines pairing rank r with
	 *  rank r+3 at the tab stop; a pair that cannot fit the panel stacks. */
	private fun appendColumnGroupLines(
		sb: SpannableStringBuilder,
		start: Int,
		half: Int,
		tab: Int,
		measure: android.text.TextPaint?,
		panelW: Float,
		disp: (Int) -> String,
		appendGroupLine: (String?, String?, Int?, Int?, Int) -> Unit,
	) {
		var firstLine = true
		for (r in 0 until half) {
			val left = start + r
			if (left >= selectionList.size) break
			val right = left + half
			if (!firstLine) sb.append("\n")
			firstLine = false
			val leftText = disp(left)
			val rightText = if (right < selectionList.size) disp(right) else null
			// Pair on one line only when the whole line truly fits the panel —
			// otherwise the TextView soft-wraps and the right word lands at the
			// LEFT margin, scrambling the column correspondence. Stack instead
			// (right word on its own line, still at column 2).
			val fits = when {
				rightText == null -> true
				measure != null -> tab + measure.measureText(rightText) <= panelW * 0.98f
				else -> leftText.length <= PAGED_PREVIEW_STACK_CHARS
			}
			if (rightText != null && !fits) {
				appendGroupLine(leftText, null, left, null, tab)
				sb.append("\n")
				appendGroupLine(null, rightText, null, right, tab)
			} else {
				appendGroupLine(leftText, rightText, left, right, tab)
			}
		}
	}

	/** Pale-blue line tint for family-group members (selection draws over). */
	private fun applyFamilyTint(
		sb: SpannableStringBuilder,
		lineStart: Int,
		familyColor: Int,
		rows: List<Int?>,
	) {
		val family = rows.any { r -> r?.let { selectionList.getOrNull(it)?.get("familyExpand") == true } == true }
		if (family) {
			sb.setSpan(
				FullWidthLineBackgroundSpan(familyColor),
				lineStart,
				sb.length,
				SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
		}
	}

	/** Scan-layout page group: two ROW-major lines of three tab-separated
	 *  entries (ranks 1-3, then 4-6), tabs at panel thirds — mirrors the two
	 *  key rows the scan sweeps (Cliff, 2026-08-13). */
	private fun appendScanGroupRows(
		sb: SpannableStringBuilder,
		start: Int,
		half: Int,
		third: Int,
		familyColor: Int,
		disp: (Int) -> String,
	) {
		for (row in 0 until 2) {
			val rows = (0 until half).mapNotNull { c ->
				(start + row * half + c).takeIf { it < selectionList.size }
			}
			if (rows.isEmpty()) break
			if (row > 0) sb.append("\n")
			val lineStart = sb.length
			rows.forEachIndexed { c, idx ->
				if (c > 0) sb.append("\t")
				sb.append(disp(idx))
			}
			for (stop in intArrayOf(third, third * 2)) {
				sb.setSpan(
					android.text.style.TabStopSpan.Standard(stop),
					lineStart,
					sb.length,
					SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
				)
			}
			applyFamilyTint(sb, lineStart, familyColor, rows)
		}
	}

	/** Linear region of the paged preview: one row per line; list-function rows keep icons. */
	private fun appendPagedLinearRows(
		sb: SpannableStringBuilder,
		listed: Int,
		imageSizePx: Int,
		highlightColor: Int,
		familyColor: Int,
	) {
		for (i in 0 until listed) {
			if (i > 0) sb.append("\n")
			val lineStart = sb.length
			val item = selectionList[i]
			val text = ((item["display"] ?: item["output"]) as? String).orEmpty()
			val isListFunction = (item["type"] as? String) == "P"
			val imageHeight = if (isListFunction) appendListFunctionImage(sb, text, imageSizePx) else 0
			if (imageHeight == 0) sb.append(text)
			// Family tint advertises an expandable row (Select-then-pause offers
			// the family page) and marks inserted members; selection draws over.
			if (item["familyExpand"] == true || familyExpandEligible(item)) {
				sb.setSpan(
					FullWidthLineBackgroundSpan(familyColor, minLineHeightPx = imageHeight),
					lineStart,
					sb.length,
					SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
				)
			}
			if (state.pagedSelectPage == null && i == state.currentSelection) {
				sb.setSpan(
					FullWidthLineBackgroundSpan(highlightColor, minLineHeightPx = imageHeight),
					lineStart,
					sb.length,
					SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
				)
			}
		}
	}

	/** Appends a list-function row's icon as an ImageSpan; returns its height (0 = no bitmap). */
	private fun appendListFunctionImage(sb: SpannableStringBuilder, disp: String, imageSizePx: Int): Int {
		if (scaledBitmapCacheSize != imageSizePx) {
			scaledBitmapCache.clear()
			scaledBitmapCacheSize = imageSizePx
		}
		val scaled = scaledBitmapCache[disp] ?: getListFunctionBitmap(disp)?.let { src ->
			val scale = kotlin.math.min(
				imageSizePx.toFloat() / src.width,
				imageSizePx.toFloat() / src.height,
			)
			Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
				.also { scaledBitmapCache[disp] = it }
		} ?: return 0
		sb.append(" ")
		val imageStart = sb.length
		sb.append(" ")
		val drawable = android.graphics.drawable.BitmapDrawable(context.resources, scaled)
		drawable.setBounds(0, 0, scaled.width, scaled.height)
		sb.setSpan(
			ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
			imageStart,
			sb.length,
			SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
		)
		return scaled.height
	}

	private fun cancelPendingExpand() {
		expandTimerRunnable?.let { expandHandler.removeCallbacks(it) }
		expandTimerRunnable = null
		pendingExpandIndex = null
	}

	private fun handleDeferredExpansion(selectedIdx: Int) {
		if (selectedIdx < 0 || selectedIdx >= selectionList.size) return
		val item = selectionList[selectedIdx]
		val hasCasePair = item["alternateOutput"] as? String != null &&
			item["alternateForm"] as? WordCaseForm != null
		val familyEligible = familyExpandEligible(item)
		if (!hasCasePair && !familyEligible) return

		// The two deferred features carry their OWN delays (Cliff: separate
		// features, separately paced). A family-eligible word waits the family
		// delay and its case pair rides the inserted group; a case-only word
		// keeps the capitalized-forms delay, including its immediate mode.
		if (familyEligible) {
			val delayMs = prefs.getInt(Constants.KEY_FAMILY_EXPAND_DELAY_MS, 1500).coerceAtLeast(1)
			pendingExpandIndex = selectedIdx
			val runnable = Runnable {
				if (state.currentSelection == selectedIdx && pendingExpandIndex == selectedIdx) {
					if (!expandFamily(selectedIdx) && hasCasePair) expandAlternate(selectedIdx)
					updateUi(false)
				}
				pendingExpandIndex = null
				expandTimerRunnable = null
			}
			expandTimerRunnable = runnable
			expandHandler.postDelayed(runnable, delayMs.toLong())
			return
		}
		val delayMs = prefs.getInt(KEY_CASETYPE_EXPAND_DELAY_MS, 0)
		if (delayMs <= 0) {
			expandAlternate(selectedIdx)
		} else {
			pendingExpandIndex = selectedIdx
			val runnable = Runnable {
				if (state.currentSelection == selectedIdx && pendingExpandIndex == selectedIdx) {
					expandAlternate(selectedIdx)
					updateUi(false)
				}
				pendingExpandIndex = null
				expandTimerRunnable = null
			}
			expandTimerRunnable = runnable
			expandHandler.postDelayed(runnable, delayMs.toLong())
		}
	}

	/**
	 * Family expansion (sls.md "family expansion", Cliff 2026-08-13):
	 * Select-then-pause on a LONG word — one whose completion still needs at
	 * least the Dev min-kr keystrokes — offers "words that start like this
	 * but end differently": inflections, declensions, and longer same-stem
	 * words, without typing down to them. Eligible: single-word entries only
	 * (units and list functions excluded), outside an open page display.
	 */
	private fun familyExpandEligible(item: Map<String, Any?>): Boolean {
		// Only once the user has actually started typing the word: resting
		// menus (BOS, startup context lists) meet the keys-remaining floor
		// trivially and must not advertise or trigger expansion (Cliff).
		val stateOk = prefs.getBoolean(Constants.KEY_FAMILY_EXPAND_ENABLED, false) &&
			state.ambiguousKeySequence.isNotEmpty() &&
			state.pagedSelectPage == null &&
			item["familyExpand"] != true
		if (!stateOk) return false
		// English/Spanish for now — stem semantics across Vietnamese syllable
		// boundaries are unvalidated (Cliff's call, 2026-08-13).
		val language = LanguageRegistry.activeLanguageNames(prefs).first()
		if (language != Constants.TYPING_LANGUAGE_ENGLISH &&
			language != Constants.TYPING_LANGUAGE_ESPANOL
		) {
			return false
		}
		val type = item["type"] as? String
		val typeOk = type in listOf("X", "L", "E", "2", "B", "N") &&
			!(type == "N" && item["ngbMulti"] == true)
		val minKr = prefs.getInt(DeveloperSettingsActivity.KEY_FAMILY_EXPAND_MIN_KR, 5)
		return typeOk && familyKeysRemaining(item) >= minKr && familyHasNewMember(item)
	}

	// Word -> "expansion would surface something new" for the CURRENT list;
	// cleared with every rebuild. The blue flag must not advertise a family
	// whose members are all already in the selection list ("horticulture/
	// horticultural both listed, expanding buys nothing" — Cliff, round 3).
	private val familyNewMemberCache = HashMap<String, Boolean>()

	private fun familyHasNewMember(item: Map<String, Any?>): Boolean {
		val word = ((item["canonicalOutput"] ?: item["output"]) as? String)
			?.lowercase(Locale.getDefault()) ?: return false
		return familyNewMemberCache.getOrPut(word) {
			val listed = selectionList.mapNotNullTo(HashSet()) {
				((it["canonicalOutput"] ?: it["output"]) as? String)?.lowercase(Locale.getDefault())
			}
			// Same ADAPTIVE search the expansion runs: the blue flag must
			// promise exactly what the pause will deliver.
			familyCandidatesFor(word).any { it.lowerWord !in listed }
		}
	}

	private fun familyKeysRemaining(item: Map<String, Any?>): Int {
		val typed = state.ambiguousKeySequence.size
		return when {
			item["type"] == "N" -> ((item["ngbKeySeqLen"] as? Int) ?: 0) - typed
			else -> (item["keysRemaining"] as? Int) ?: 0
		}
	}

	/**
	 * Inserts the family page group after the paused-on word. The stem is the
	 * word minus its last Dev stem-backoff letters; candidates are the
	 * frequency-ranked vocabulary words sharing that letter prefix. In paged
	 * mode the group is spliced at the paged-region start, so it renders as a
	 * clean page-1 group and every later page shifts by exactly one Select —
	 * the unintentional-pause cost Cliff specified. The selected word's own
	 * collapsed case pair (if any) leads the group. Returns false when no
	 * family exists (the caller falls back to plain case expansion).
	 */
	/**
	 * Family candidates for [word] with ADAPTIVE stem fill (Cliff,
	 * 2026-08-14): the configured backoff sets the STARTING stem; when it
	 * yields fewer than a full page ("differentiate + differential amid
	 * four empty slots"), the stem shortens one letter at a time until a
	 * page fills (or the 2-letter floor). Prefix families are NESTED, so
	 * each shortening only ADDS candidates — and the prefix-affinity sort
	 * keeps the tight family first, the broadened cohort behind it. All
	 * matches are kept (page 2+ as needed) so a word visible at the
	 * broader stem is never dropped for slot-count reasons.
	 */
	private fun familyCandidatesFor(word: String): List<WLD.CandidateEntry> {
		val backoff = prefs.getInt(DeveloperSettingsActivity.KEY_FAMILY_EXPAND_STEM_BACKOFF, 5)
		val typed = state.ambiguousKeySequence.size
		// Typed-key consistency floor (Cliff, 2026-08-14): a stem shorter
		// than the typed sequence admits words that CONTRADICT keys already
		// typed (a "un" stem offering "unable" after six keys of
		// "understand"). The stem never drops below the typed length —
		// every member then shares the anchor's first N letters, whose keys
		// ARE the typed sequence. Applies to the STARTING stem too: a large
		// backoff dial on a nearly-typed word could begin below N.
		val floor = maxOf(2, typed)
		var stem = word.dropLast(backoff.coerceAtLeast(0))
		if (stem.length < floor) stem = word.take(floor)
		if (stem.length < 2) return emptyList()
		while (true) {
			val candidates = wld.wordsWithLetterPrefix(stem, word, FAMILY_MAX_CANDIDATES, typed)
				.filter { it.lowerWord != word }
			if (candidates.size >= PAGED_WORDS_PER_PAGE || stem.length <= floor) return candidates
			stem = stem.dropLast(1)
		}
	}

	private fun expandFamily(selectedIdx: Int): Boolean {
		val item = selectionList[selectedIdx]
		val word = ((item["canonicalOutput"] ?: item["output"]) as? String)
			?.lowercase(Locale.getDefault()) ?: return false
		// All matching candidates are presented (Cliff), across as many page
		// groups as needed; the cap is a pathological-stem guard only.
		val candidates = familyCandidatesFor(word)
		if (candidates.isEmpty()) return false

		val group = mutableListOf<MutableMap<String, Any?>>()
		// The paused word's alternate case form joins the group first: one
		// keystroke more than the old single-row insertion ONLY when the word
		// is also a family member (Cliff's cost accounting).
		val altOut = item["alternateOutput"] as? String
		val altForm = item["alternateForm"] as? WordCaseForm
		if (altOut != null && altForm != null) {
			val altEntry = item.toMutableMap()
			altEntry.remove("alternateOutput")
			altEntry.remove("alternateForm")
			altEntry["output"] = altOut
			altEntry["display"] = altOut
			altEntry["forcedCaseForm"] = altForm
			altEntry["appliedCaseForm"] = altForm
			altEntry["familyExpand"] = true
			group.add(altEntry)
			// The source row loses its collapsed "(Word)" pair display.
			val src = item.toMutableMap()
			src.remove("alternateOutput")
			src.remove("alternateForm")
			src["display"] = src["output"] as? String ?: word
			val patched = selectionList.toMutableList()
			patched[selectedIdx] = src
			selectionList = patched
		}
		candidates.asSequence()
			.mapNotNull { c -> buildDisplayEntries(c).firstOrNull()?.also { it["lowerWord"] = c.lowerWord } }
			.forEach { entry ->
				// One form per family slot: page cells cannot hold collapsed pairs.
				entry.remove("alternateOutput")
				entry.remove("alternateForm")
				entry["display"] = entry["output"] as? String ?: entry["lowerWord"]
				entry["familyExpand"] = true
				group.add(applyShiftAndCaps(listOf(entry)).first().toMutableMap())
			}
		if (group.isEmpty()) return false

		if (pagedSelectionEnabled()) {
			// The group must occupy WHOLE pages: a partial page would absorb
			// the next ordinary rows into its 6-cell window (that is how
			// "satellite" — a same-keys candidate — appeared inside the
			// intellectuals family, Cliff's filtering report). Inert pads
			// fill the last page; picking one just beeps.
			while (group.size % PAGED_WORDS_PER_PAGE != 0) {
				group.add(
					mutableMapOf(
						"type" to "FP",
						"display" to "",
						"output" to "",
						"familyExpand" to true,
						"familyPad" to true,
					),
				)
			}
		}

		// Insert directly after the paused row: pausing on slot 1 puts the
		// family page at slot 2 for immediate selection, and the old slot-2
		// word is pushed below the inserted pages (Cliff's spec). The listed
		// region shrinks to end at the paused row so the group renders and
		// steps as page groups; cleared on any list rebuild.
		val insertAt = selectedIdx + 1
		val mutable = selectionList.toMutableList()
		mutable.addAll(insertAt, group)
		selectionList = mutable
		if (pagedSelectionEnabled()) familyListedRows = insertAt
		return true
	}

	private fun expandAlternate(idx: Int) {
		if (idx < 0 || idx >= selectionList.size) return
		val item = selectionList[idx]
		val alternateOutput = item["alternateOutput"] as? String ?: return
		val alternateForm = item["alternateForm"] as? WordCaseForm ?: return

		val mutableList = selectionList.toMutableList()
		val mutableItem = item.toMutableMap()
		mutableItem.remove("alternateOutput")
		mutableItem.remove("alternateForm")
		val origOutput = mutableItem["output"] as? String ?: return
		mutableItem["display"] = origOutput
		mutableList[idx] = mutableItem

		val newEntry = mutableItem.toMutableMap()
		newEntry["output"] = alternateOutput
		newEntry["display"] = alternateOutput
		newEntry["forcedCaseForm"] = alternateForm
		newEntry["appliedCaseForm"] = alternateForm
		mutableList.add(idx + 1, newEntry)

		selectionList = mutableList
	}

	private fun updateAmbiguousKeySequence() {
		val hist = state.keyHistory.toList().asReversed()
		val out = mutableListOf<KeyDef>()
		var sawAmbig = false
		state.selectKeyCount = 0
		for (k in hist) {
			val isAmbig = k.functions.any { it.first == KF_Ambig }
			val isSelect = k.functions.any { it.first == KF_Select }
			if (isAmbig) {
				out.add(0, k)
				sawAmbig = true
			} else if (isSelect && !sawAmbig) {
				state.selectKeyCount += 1
			} else {
				break
			}
		}
		state.ambiguousKeySequence = out
		debugShowAmbiguousSequence("[updateAmbiguousKeySequence]  selectCount=${state.selectKeyCount}")
	}

	// ----- Speech helpers -----
	private fun maybeSpeakSelectedWord(item: Map<String, Any?>) {
		val enabled =
			prefs.getBoolean(KEY_SPEAK_SELECTED_WORD)
		if (!enabled) return
		val type = item["type"] as? String ?: return
		if (type !in listOf("X", "L", "E", "2", "B")) return
		val spoken =
			(item["display"] as? String)?.takeIf { it.isNotBlank() } ?: (item["output"] as? String)
				?: return
		// If just spoke this via Select dynamic label, skip duplicating
		val sup = suppressNextSelectedWordSpeak
		if (sup != null && sup.equals(spoken, ignoreCase = true)) {
			suppressNextSelectedWordSpeak = null
			return
		}
		sayInterruptible(spoken)
	}

	private fun maybeSpeakKey(key: KeyDef) {
		val enabled =
			prefs.getBoolean(KEY_SPEAK_SELECTED_KEY)
		if (!enabled) return
		val speakPunc =
			prefs.getBoolean(KEY_SPEAK_PUNCTUATION)

		// Function or single-label key
		if (key.singleLabel != null) {
			val label = key.singleLabel.replace('\n', ' ').trim()
			// Special handling for Select key dynamic preview: speak only the preview item
			if (label.equals("SELECT", ignoreCase = true)) {
				val next = nextSelectableWord()
				if (!next.isNullOrBlank()) {
					// queue speaking the preview and suppress follow-up selected-word speech once
					sayQueued(next)
					suppressNextSelectedWordSpeak = next
					return
				}
			}
			// If it's a single punctuation character, always speak
			if (label.length == 1 && isPunctuation(label[0])) {
				if (speakPunc) {
					val name = punctuationName(label[0]) ?: label
					sayUiQueued(name)
				} else {
					sayUiQueued(label)
				}
				return
			}
			val spoken = formatFunctionLabelForSpeech(label)
			if (spoken.isNotEmpty()) sayUiQueued(spoken)
			return
		}

		// Ambiguous key: speak letters, optionally punctuation at end
		val disp = key.display
		if (disp.isEmpty()) return
		val letters = disp.filter { it.isLetter() }.map { it.toString() }
		val punctChars = disp.filter { !it.isLetter() && !it.isWhitespace() }
		val punctParts =
			if (speakPunc) punctChars.map { punctuationName(it) ?: it.toString() } else emptyList()
		val parts = letters + punctParts
		if (parts.isNotEmpty()) sayUiInterruptible(parts.joinToString(separator = " "))
	}

	private fun isPunctuation(c: Char): Boolean = !c.isLetterOrDigit() && !c.isWhitespace()

	private fun formatFunctionLabelForSpeech(label: String): String {
		val trimmed = label.trim()
		if (trimmed.equals("SELECT", ignoreCase = true)) return context.getString(R.string.speech_select)
		if (trimmed.contains("Delete", ignoreCase = true)) return context.getString(R.string.speech_delete)
		if (trimmed.contains("Space", ignoreCase = true)) return context.getString(R.string.speech_space)
		if (trimmed.contains("123", ignoreCase = true)) return context.getString(R.string.speech_number_mode)
		if (trimmed.contains("0-4", ignoreCase = true)) return context.getString(R.string.speech_zero_through_four)
		if (trimmed.contains("5-9", ignoreCase = true)) return context.getString(R.string.speech_five_through_nine)
		return trimmed
	}

	private fun punctuationName(c: Char): String? = PUNCTUATION_NAME_RES[c]?.let { context.getString(it) }

	private fun ambigKeySequenceNumbers(): List<Int> {
		val keyList = pages[state.currentPage] ?: return emptyList()
		return state.ambiguousKeySequence.map { k ->
			val idx = keyList.indexOf(k)
			// Map button index to ambiguous key index 0..5, based on page definition
			// We encode ambiguous key number in functions arg for KF_Ambig
			(k.functions.firstOrNull { it.first == KF_Ambig }?.second as? Int) ?: 0
		}
	}

	/**
	 * Synchronous wldSelection — used by touch/switch/gamepad callers.
	 * Reads keys from state and applies results directly.
	 */
	private fun wldSelection(shouldAbort: (() -> Boolean)? = null, applyPendingHighlight: (() -> Unit)? = null): List<Map<String, Any?>> {
		android.os.Trace.beginSection("ime.wldSearch")
		try {
			val keys = ambigKeySequenceNumbers()
			val result = wldSelectionInternal(keys, shouldAbort, applyPendingHighlight)
			// Apply diagnostic fields directly (synchronous path)
			lastSearchTerminationCode = result.termination
			lastSearchMaxDepth = result.maxDepth
			lastSearchExaminedNodes = result.examinedNodes
			lastSearchElapsedMs = result.elapsedMs
			state.emptyAmbigSequence = result.emptyAmbigSequence
			ngbConfPending = result.ngbConf
			return result.candidates
		} finally {
			android.os.Trace.endSection()
		}
	}

	/**
	 * Core wldSelection implementation — accepts keys explicitly for thread safety.
	 * Returns a WldSelectionResult without mutating state. Reads the wld trie, so a worker-thread
	 * caller MUST hold vocabLock (the trie is mutated under it by init/reload/merge/clear).
	 */
	private fun wldSelectionInternal(
		keys: List<Int>,
		shouldAbort: (() -> Boolean)? = null,
		applyPendingHighlight: (() -> Unit)? = null,
	): WldSelectionResult {
		if (keys.isEmpty()) {
			// Zero-keystroke window (word start): the pool's head, before any AK.
			// Empty when context is invalid — which also preserves SELECT-at-root
			// manual pull-in (it only fires on an empty list).
			val zeroK = ngbZeroKEntries()
			return WldSelectionResult(zeroK, emptyList(), zeroK.isEmpty(), "NGB0", 0, 0, 0.0)
		}
		val startNs = android.os.SystemClock.elapsedRealtimeNanos()
		val masks = buildVocabMaskConfig(KEY_VOCAB_MIN_FREQ_SELECTION)
		val freqFilterEnabled = prefs.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)
		val selectionSlider =
			prefs.getInt(KEY_VOCAB_MIN_FREQ_SELECTION, 1).coerceIn(1, 14)
		val includeExcludedAtEnd =
			freqFilterEnabled &&
				selectionSlider > 1 &&
				prefs.getBoolean(Constants.KEY_VOCAB_SHOW_EXCLUDED_AT_END)
		val bsd = prefs.getInt(DeveloperSettingsActivity.KEY_SEARCH_BSD, 8).coerceIn(3, 10)
		val sed = prefs.getInt(DeveloperSettingsActivity.KEY_SEARCH_SED, 7).coerceIn(0, 10)
		val mqc = prefs.getInt(DeveloperSettingsActivity.KEY_SEARCH_MQC, 100).coerceIn(5, 1000)
		val ignoreMen = prefs.getBoolean(DeveloperSettingsActivity.KEY_SEARCH_IGNORE_MEN, false)
		val men = if (ignoreMen) {
			Int.MAX_VALUE
		} else {
			prefs.getInt(DeveloperSettingsActivity.KEY_SEARCH_MEN, 5000).coerceIn(50, 10000)
		}

		// ── Phase 1: trie-only candidate collection (no DB lookups) ──
		val searchResult = wld.getDisambiguationCandidates(
			keys,
			maxWordCompleteEntries = mqc,
			minFreqClass = masks.minFreqClass,
			includeExcludedAtEnd = includeExcludedAtEnd,
			anyFreqMask = masks.anyFreqMask,
			minFreqMask = masks.minFreqMask,
			baseSearchDepth = bsd,
			searchExpansionDepth = sed,
			maxExaminedNodes = men,
			shouldAbort = shouldAbort,
			applyPendingHighlight = applyPendingHighlight,
		)
		if (searchResult.candidates.isEmpty()) {
			debugLog(
				DebugCategory.WordDb,
				"[wldSelection] empty results keys=$keys anyMask=${hexMask(masks.anyFreqMask)} minMask=${hexMask(masks.minFreqMask)} minFreqClass=${masks.minFreqClass} termination=${searchResult.termination}",
			)
		}
		val phraseMatches = wld.getPhraseMatches(keys, masks.anyFreqMask, masks.minFreqMask, masks.minFreqClass)
			.mapNotNull { match ->
				val entry = phraseRepository.findByPhraseUUID(match.phraseUUID) ?: return@mapNotNull null
				mapOf(
					"type" to "PH",
					"display" to formatPhraseDisplay(entry),
					"output" to entry.phrase,
					"canonicalOutput" to entry.phrase,
					"abbrev" to entry.abbreviation,
					"phraseId" to entry.phraseUUID,
					"countOfOccurrence" to 1000,
					"caseCount" to 1,
					"ClassMask" to match.classMask,
					"forcedCaseForm" to WordCaseForm.ORIGINAL,
					"preserveOriginalCase" to true,
				)
			}
		val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
		searchTimingCount += 1
		val elapsedRounded = elapsedMs.toLong()
		searchTimingTotalMs += elapsedRounded
		if (elapsedRounded < searchTimingMinMs) searchTimingMinMs = elapsedRounded
		if (elapsedRounded > searchTimingMaxMs) searchTimingMaxMs = elapsedRounded
		if (searchTimingCount % 10 == 0) {
			val avg = if (searchTimingCount > 0) searchTimingTotalMs.toDouble() / searchTimingCount else 0.0
			debugLog(
				DebugCategory.WordDb,
				"Search timing (ms) min=$searchTimingMinMs max=$searchTimingMaxMs avg=${"%.2f".format(avg)}",
			)
		}
		val emptyAmbigSequence = searchResult.candidates.isEmpty() && phraseMatches.isEmpty()
		val seqLen = keys.size
		val activeMask = prefs.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
		val activeImportedMask = getActiveImportedMask(activeMask)
		val pastEnabled = (activeMask and ClassMasks.CLASS_PAST_VOCABULARIES_MASK) != 0L
		val promoteImported = prefs.getBoolean(KEY_VOCAB_PROMOTE_IMPORTED)

		// Apply any pending highlight + abort check before sort phase
		applyPendingHighlight?.invoke()
		if (shouldAbort?.invoke() == true) {
			return WldSelectionResult(emptyList(), phraseMatches, true, searchResult.termination, searchResult.maxDepth, searchResult.examinedNodes, elapsedMs)
		}

		// Sort candidates by trie-only metrics (no DB access needed)
		val weighted = searchResult.candidates.map { candidate ->
			val metrics = computeSortMetrics(
				candidate,
				promoteImported,
				activeImportedMask,
				pastEnabled,
			)
			Pair(candidate, metrics)
		}
		// Spanish regional re-sort: when the user picks a region, soft-demote region-dispreferred word
		// forms (Tier 1: Castilian vosotros forms for the ustedes regions) to the END of the list via a
		// two-level sort — the existing frequency metric still orders within each group, and a demoted
		// word is only shown when nothing better fills the slot (reachable, not deleted). No-op and zero
		// extra cost unless Spanish is active AND a specific region is chosen.
		// Regional re-sort. English (British/American spelling) and Spanish (Peninsular vs Latin
		// American) each soft-demote the dispreferred forms to the END of the list via a two-level
		// sort: the frequency metric still orders within each group, and a demoted word is only
		// shown when nothing better fills the slot — reachable, never deleted. Both spellings stay
		// in the DB, so this costs nothing and is a no-op unless a specific region is chosen.
		val activeLanguage = LanguageRegistry.activeLanguageNames(prefs).first()
		val regionDemote: ((WLD.CandidateEntry) -> Boolean)? = when (activeLanguage) {
			Constants.TYPING_LANGUAGE_ESPANOL -> {
				val region = prefs.getString(Constants.KEY_SPANISH_REGION, SpanishRegion.ANY)
				if (region == SpanishRegion.ANY) {
					null
				} else {
					{ c: WLD.CandidateEntry -> SpanishRegion.demote(c.lowerWord, c.classMask, region) }
				}
			}

			Constants.TYPING_LANGUAGE_ENGLISH -> {
				val region = prefs.getString(Constants.KEY_ENGLISH_REGION, EnglishRegion.ANY)
				if (region == EnglishRegion.ANY) {
					null
				} else {
					{ c: WLD.CandidateEntry -> EnglishRegion.demote(c.classMask, region) }
				}
			}

			else -> null
		}
		val sortedCandidates = if (regionDemote == null) {
			weighted.sortedBy { it.second.sortMetric }.map { it.first }
		} else {
			weighted.sortedWith(
				compareBy(
					{ if (regionDemote(it.first)) 1 else 0 },
					{ it.second.sortMetric },
				),
			).map { it.first }
		}

		// ── Phase 2: DB lookups + case variant expansion (only for displayed entries) ──
		// Apply any pending highlight + abort check before expensive DB lookups
		applyPendingHighlight?.invoke()
		if (shouldAbort?.invoke() == true) {
			return WldSelectionResult(emptyList(), phraseMatches, true, searchResult.termination, searchResult.maxDepth, searchResult.examinedNodes, elapsedMs)
		}

		// Fully-typed vs incomplete is measured in KEYSTROKES, not characters: a
		// tone-marked word's tone key is part of its sequence (Việt = 4 letters +
		// nặng = 5 keys), so at 4 keys it is still incomplete while a 3-letter word
		// whose 4th key was its tone mark is fully typed. keysRemaining is the
		// trie's remaining-keystroke count and handles both alike.
		val partition = partitionBySpecificity(sortedCandidates, seqLen)
		// EVERY fully-typed candidate must reach the list: once all of a word's
		// keystrokes are entered there is nothing more the user can do — an FTS
		// dropped here would be permanently unreachable (82 Vietnamese sequences
		// carry >8 FTS, up to 16). Tone-only-pending words are complete too.
		// Letter-ITS only FILL: when the guaranteed blocks reach 8 entries none
		// are added; below 8, they top the list up to 8 total (Cliff 2026-08-04).
		val tav = toneAfterVowelActive()
		val displayCandidates: List<WLD.CandidateEntry>
		val syntheticBaseRows: List<String>
		if (tav) {
			// TAV pre-tone collapse (Cliff 2026-08-05): tone families are NEVER
			// enumerated before their tone keystroke. One unmarked row per letter
			// spelling — the ngang FTS word when it exists, else a synthetic
			// confirmation row (selecting it outputs the unmarked string verbatim).
			// Deeper pre-tone ITS are not displayed at all; tone-established ITS
			// fill per the standard budget.
			val exactBases = partition.exact.mapTo(HashSet()) { it.lowerWord }
			syntheticBaseRows = partition.toneOnly
				.map { wld.baseFormOf(it.lowerWord) }
				.distinct()
				.filter { it !in exactBases }
			val establishedIts = partition.letterCompletions.filter { !it.tonePending }
			val budget = (8 - partition.exact.size - syntheticBaseRows.size).coerceAtLeast(0)
			displayCandidates = partition.exact + establishedIts.take(budget)
		} else {
			syntheticBaseRows = emptyList()
			displayCandidates = assembleByPartitionPolicy(sortedCandidates, partition)
		}

		// Abort-aware loop: check between candidates (each does DB queries)
		val expandedEntries = mutableListOf<MutableMap<String, Any?>>()
		var exactEntryCount = 0
		for (candidate in displayCandidates) {
			applyPendingHighlight?.invoke()
			if (shouldAbort?.invoke() == true) break
			val entries = buildDisplayEntries(candidate)
			if (candidate.keysRemaining == 0) exactEntryCount += entries.size
			expandedEntries.addAll(entries)
		}
		// TAV synthetic base rows sit directly after the FTS block: type "B" outputs
		// its string verbatim and records no word stats (see recordWordUsageForSelection).
		if (syntheticBaseRows.isNotEmpty()) {
			val rows = syntheticBaseRows.map { base ->
				mutableMapOf<String, Any?>(
					"type" to "B",
					"display" to base,
					"output" to base,
					"canonicalOutput" to base,
					"countOfOccurrence" to 0,
					"POS" to "",
					"FreqClass" to 7,
					"ClassMask" to 0L,
					"UseCount" to 0,
					"UseTime" to JSONObject.NULL,
					"keysRemaining" to 1,
					// applyShiftAndCaps DROPS word-type entries with caseCount <= 0;
					// one case slot also gives the row correct shift/caps rendering.
					"caseCount" to 1,
				)
			}
			expandedEntries.addAll(exactEntryCount.coerceAtMost(expandedEntries.size), rows)
		}
		// NGB prediction block ABOVE the letter-exact entries (anchor=0 per the
		// slot-budget sweep; the sweep measured the anchored slot costing 5 KSPL
		// points). Every existing guarantee below is untouched: the letter-exact
		// word sits at the first post-block slot, nothing is dropped — entries
		// duplicated by the block are removed from the lower section only.
		// C3 span sessions override the block — see [ngbHeadEntries].
		val ngbBlock = ngbHeadEntries(keys)
		// Dedup by EXACT output, not canonical: the block's "Nam" must not
		// evict the DB's other case variants ("nam") from below (Cliff,
		// 2026-08-08 — canonical-dedup was deleting title-case entries).
		val blockOutputs = ngbBlock.mapTo(HashSet()) { it["output"] }
		// FSLS anchors (steady styles): the head slots go to the top rows of
		// the CLASSIC order — fully-typed rows first, then the rest, keeping
		// their relative (shared-metric) order. Anchored rows outrank block
		// duplicates: the anchor keeps its trie row, the block entry yields.
		val anchorRows = fslsAnchorRows(expandedEntries)
		val anchorOutputs = anchorRows.mapTo(HashSet()) { it["output"] }
		// Phrases + ALL guaranteed entries (FTS, then tone-only-pending) + at most
		// 8 letter-ITS candidates, already ordered by certainty block then metric.
		// No positional pruning: a guaranteed-block overflow must not hide the
		// letter-ITS the user is mid-way through typing (e.g. chuyện at c-h-u-y).
		val finalCandidates = anchorRows +
			ngbBlock.filter { it["output"] !in anchorOutputs } +
			phraseMatches +
			expandedEntries.filter { it["output"] !in blockOutputs && it !in anchorRows }
		// No confidence observations during a C3 span session: a selection is
		// active (span) or the NGB offer was declined (collapsed).
		val ngbConf = if (ngbSpanMode == NgbSpanMode.NONE) {
			ngbConfCandidateFor(keys, ngbBlock.isNotEmpty(), phraseMatches, expandedEntries, displayCandidates)
		} else {
			null
		}
		return WldSelectionResult(finalCandidates, phraseMatches, emptyAmbigSequence, searchResult.termination, searchResult.maxDepth, searchResult.examinedNodes, elapsedMs, ngbConf)
	}

	private fun formatPhraseDisplay(entry: org.continuouspath.justtype.data.PhraseEntry): String {
		val showAbbrev =
			prefs.getBoolean(KEY_SHOW_ABBREV_IN_SELECTION)
		val showPhrase =
			prefs.getBoolean(KEY_SHOW_PHRASE_IN_SELECTION)

		val parts = mutableListOf<String>()
		if (showAbbrev) {
			parts.add(entry.abbreviation)
		}
		if (showPhrase) {
			val phraseSnippet = entry.phrase.replace("\n", " ").take(60).let { snippet ->
				if (entry.phrase.length > 60) "$snippet..." else snippet
			}
			parts.add(phraseSnippet)
		}
		if (parts.isEmpty()) {
			parts.add(entry.abbreviation)
		}
		return parts.joinToString(" - ")
	}

	private data class Metrics(
		val freq: Double,
		val seq: Double,
		val use: Double,
		val rec: Double,
		val sortMetric: Double,
	)

	private data class CandidatePartition(
		val exact: List<WLD.CandidateEntry>,
		val toneOnly: List<WLD.CandidateEntry>,
		val letterCompletions: List<WLD.CandidateEntry>,
	)

	/**
	 * Certainty blocks for list assembly. `toneOnly` = Fully-Typed-Except-For-
	 * Tone-Mark (tone-keystroke languages only — in v1 layouts keysRemaining==1
	 * always means a missing LETTER): every letter is entered, only the tone
	 * keystroke remains, so the word's letter content is fully confirmed and the
	 * block ranks above ANY letter-incomplete candidate regardless of frequency.
	 * Like FTS, it is never capped.
	 */
	private fun partitionBySpecificity(
		sorted: List<WLD.CandidateEntry>,
		seqLen: Int,
	): CandidatePartition {
		// tonePending is REQUIRED: in TAV a word with its tone typed and one LETTER
		// remaining also has keysRemaining==1 && len==seqLen — that word is an
		// ordinary (tone-established) completion, not an FTEFTM (misclassifying it
		// stripped its tone marks in the pre-tone collapse: hài rendered as "hai").
		fun isToneOnly(c: WLD.CandidateEntry) = c.tonePending && c.keysRemaining == 1 && c.lowerWord.length == seqLen
		return CandidatePartition(
			exact = sorted.filter { it.keysRemaining == 0 },
			toneOnly = sorted.filter { isToneOnly(it) },
			letterCompletions = sorted.filter { it.keysRemaining != 0 && !isToneOnly(it) },
		)
	}

	/**
	 * Non-TAV list assembly under the SLS partition policy (docs/.plans/sls.md
	 * Stage A). 0 = strict certainty blocks (shipped); 1 = sortmetric-interleaved
	 * with the top FTS pinned at slot 1 (WYSIWYG anchor); 2 = fully
	 * metric-interleaved. Every FTS stays listed under all policies
	 * (reachability), and the ITS fill budget (8 minus guaranteed entries) is
	 * unchanged. toneOnly (FTEFTM) rows are guaranteed only while promoted —
	 * demoted they compete as ordinary completions (measured best for the
	 * interleave policies on Vietnamese).
	 */
	/** FSLS anchor count for the active word-list style (sls.md word-list-
	 *  style modes): slots pinned to the classic order at the head of the
	 *  final list. Classic itself is whole-list (handled via the strict
	 *  partition + derived predictions-off), not an anchor count. */
	private fun wordListStyleAnchors(): Int = when (prefs.getString(Constants.KEY_WORD_LIST_STYLE, Constants.WORD_LIST_STYLE_PREDICTIVE)) {
		Constants.WORD_LIST_STYLE_STEADY1 -> 1
		Constants.WORD_LIST_STYLE_STEADY2 -> 2
		else -> 0
	}

	/** The steady-style anchor rows: the top N entries of the classic order
	 *  among the built trie rows — fully-typed first, then partial, each
	 *  group keeping its (shared-metric) relative order. */
	private fun fslsAnchorRows(expandedEntries: List<MutableMap<String, Any?>>): List<MutableMap<String, Any?>> {
		val n = wordListStyleAnchors()
		if (n == 0) return emptyList()
		// keysRemaining defaulting mirrors [fullyTypedRow].
		fun fts(item: MutableMap<String, Any?>): Boolean {
			val type = item["type"] as? String
			return ((item["keysRemaining"] as? Int) ?: if (type == "L") 1 else 0) == 0
		}
		val trie = expandedEntries.filter { (it["type"] as? String) in setOf("X", "L", "E", "2") }
		return (trie.filter(::fts) + trie.filterNot(::fts)).take(n)
	}

	private fun assembleByPartitionPolicy(
		sortedCandidates: List<WLD.CandidateEntry>,
		partition: CandidatePartition,
	): List<WLD.CandidateEntry> {
		// Classic style = the strict certainty-block order, always (the Dev
		// partition slider stays live for the other styles).
		val classic = prefs.getString(Constants.KEY_WORD_LIST_STYLE, Constants.WORD_LIST_STYLE_PREDICTIVE) ==
			Constants.WORD_LIST_STYLE_CLASSIC
		val policy = if (classic) 0 else prefs.getInt(DeveloperSettingsActivity.KEY_SLS_PARTITION, 2).coerceIn(0, 2)
		val tonePromoted = prefs.getBoolean(DeveloperSettingsActivity.KEY_SLS_TONE_PROMOTED, false)
		val guaranteedTone = if (tonePromoted) partition.toneOnly else emptyList()
		val fill = if (tonePromoted) {
			partition.letterCompletions
		} else {
			sortedCandidates.filter { it.keysRemaining != 0 }
		}
		val itsBudget = (8 - partition.exact.size - guaranteedTone.size).coerceAtLeast(0)
		if (policy == 0) {
			return partition.exact + guaranteedTone + fill.take(itsBudget)
		}
		val listedIds = HashSet<Int>()
		partition.exact.forEach { listedIds.add(it.wordID) }
		guaranteedTone.forEach { listedIds.add(it.wordID) }
		fill.take(itsBudget).forEach { listedIds.add(it.wordID) }
		val ordered = sortedCandidates.filter { it.wordID in listedIds }
		if (policy == 1) {
			val top = partition.exact.firstOrNull() ?: return ordered
			return listOf(top) + ordered.filter { it.wordID != top.wordID }
		}
		return ordered
	}

	private fun getF(key: String, def: Float): Double = prefs.getFloat(key, def).toDouble()

	/** Recency bucket 0 (just used) .. 6 (ancient), 7 = never used. */
	private fun recencyClassOf(lastUseTime: Int): Int {
		if (lastUseTime == 0) return 7
		val elapsedMin = (wordDb.relativeTime() - lastUseTime).toLong() / 60
		return when {
			elapsedMin <= 15L -> 0
			elapsedMin <= 150L -> 1
			elapsedMin <= 1500L -> 2
			elapsedMin <= 15000L -> 3
			elapsedMin <= 150000L -> 4
			elapsedMin <= 1500000L -> 5
			else -> 6
		}
	}

	private fun computeSortMetrics(
		candidate: WLD.CandidateEntry,
		promoteImported: Boolean,
		activeImportedMask: Long,
		pastEnabled: Boolean,
	): Metrics {
		val freqClass = candidate.freqClass
		val classMask = candidate.classMask
		val useCount = candidate.useCount
		val recencyClass = recencyClassOf(candidate.lastUseTime)

		val freqAdd = getF("freq_add_weight", 1.0f)
		val freqMult = getF("freq_mult_weight", 1.25f)
		val seqAdd = getF("seq_add_weight", 1.0f)
		val seqMult = getF("seq_mult_weight", 1.0f)
		val useAdd = getF("use_add_weight", 3.0f)
		val useMult = getF("use_mult_weight", 1.15f)
		val recAdd = getF("recency_add_weight", 2.0f)
		val recMult = getF("recency_mult_weight", 1.75f)

		val freqMetric =
			((freqClass - 1).toDouble() * freqAdd) * Math.pow(freqMult, (freqClass - 1).toDouble())
		// Completion distance in KEYSTROKES (keysRemaining), not characters: a
		// tone-marked word still awaiting its tone key is one keystroke away even
		// though its character count equals the sequence length.
		val delta = candidate.keysRemaining.coerceAtLeast(0)
		val seqMetric = (delta.toDouble() * seqAdd) * Math.pow(seqMult, delta.toDouble())
		val useFactor = when {
			useCount >= 7 -> 0
			useCount == 0 -> 3.5
			else -> (7 - useCount) / 2
		}
		var useMetric = (useFactor.toDouble() * useAdd) * Math.pow(useMult, useFactor.toDouble())
		var recencyMetric =
			(recencyClass.toDouble() * recAdd) * Math.pow(recMult, recencyClass.toDouble())
		if (freqClass == 1) {
			useMetric = 0.0
			recencyMetric = recencyMetric / 2
		} else {
			useMetric = useMetric / 2.0
		}
		val lowFreqPenalty = if (candidate.isLowFrequency) 1_000_000_000.0 else 0.0
		val promoteBoost = if (promoteImported && candidate.keysRemaining == 0) {
			when {
				(classMask and activeImportedMask) != 0L -> -1_000_000_000_000.0
				pastEnabled && (classMask and ClassMasks.CLASS_PAST_VOCABULARIES_MASK) != 0L ->
					-500_000_000_000.0
				else -> 0.0
			}
		} else {
			0.0
		}
		// Cold-start FTS floor (Cliff 2026-08-12 "a below and"): a NEVER-USED
		// top-band fully-typed word is never outranked by an incomplete row —
		// neither by a once-used word's recency edge (351.9 points: r7-never
		// vs r0, the mechanism that buried cold "a" under just-used "and")
		// nor by the within-band continuous extension (up to ~17 points for
		// the strongest bigrams, the BOS "the over to" case). Self-retires
		// per word at its first real use — from then on the user's own
		// usage data rules, by design. Sim-measured cost: <=0.03% KSPLS.
		val coldFtsFloor = if (freqClass == 1 && candidate.keysRemaining == 0 && useCount == 0) -COLD_FTS_FLOOR else 0.0
		val total = freqMetric + seqMetric + useMetric + recencyMetric + lowFreqPenalty + promoteBoost + coldFtsFloor
		return Metrics(freqMetric, seqMetric, useMetric, recencyMetric, total)
	}

	/**
	 * SLS observability tag (Cliff spec, 2026-08-10). Tier 1 appends
	 * [<source><impact>] — source: F fully typed, I incomplete (look-ahead),
	 * N n-gram block, P phrase, T TAV base row; impact letters rank the
	 * metric components by contribution (f frequency class, u use count,
	 * r recency, s completion distance; x low-frequency exclusion penalty,
	 * v vocab-promotion boost; N entries: n corpus-scale effective count,
	 * g learned-tier boost). Tier 2 appends the sortmetric score; tier 3 the
	 * RAW stored values in impact order; tier 4 the component metric values
	 * (the legacy full display; the search-timing token also returns at 4).
	 */
	private fun sortMetricTag(item: Map<String, Any?>, type: String, tier: Int): String {
		if (type == "N") {
			val eff = (item["countOfOccurrence"] as? Int) ?: 0
			val letters = "n" + if (item["ngbUserUsed"] == true) "g" else ""
			val sb = StringBuilder("[N$letters]")
			if (tier >= 2) sb.append(String.format(Locale.getDefault(), " %.3g", eff.toDouble()))
			if (tier >= 3) sb.append(" n=$eff")
			return sb.toString()
		}
		val source = when {
			type == "PH" -> "P"
			type == "B" -> "T"
			((item["keysRemaining"] as? Int) ?: if (type == "L") 1 else 0) == 0 -> "F"
			else -> "I"
		}
		val activeMask = prefs.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
		val m = computeSortMetrics(
			item,
			prefs.getBoolean(KEY_VOCAB_PROMOTE_IMPORTED),
			getActiveImportedMask(activeMask),
			(activeMask and ClassMasks.CLASS_PAST_VOCABULARIES_MASK) != 0L,
		)
		val ranked = listOf("f" to m.freq, "s" to m.seq, "u" to m.use, "r" to m.rec)
			.filter { it.second > 0.0 }
			.sortedByDescending { it.second }
		var letters = ranked.joinToString(separator = "") { it.first }
		if (m.sortMetric >= 1_000_000_000.0) letters += "x"
		if (m.sortMetric <= -100_000_000_000.0) letters = "v$letters"
		val sb = StringBuilder("[$source$letters]")
		if (tier >= 2) sb.append(String.format(Locale.getDefault(), " %.4g", m.sortMetric))
		if (tier >= 3) {
			val useTime = (item["UseTime"] as? Number)?.toLong()
			val values = ranked.joinToString(separator = " ") { (letter, _) ->
				when (letter) {
					"f" -> "f${(item["FreqClass"] as? Int) ?: 14}"
					"u" -> "u${(item["UseCount"] as? Int) ?: 0}"
					"s" -> "s${(item["keysRemaining"] as? Int) ?: 0}"
					else -> "r${recencyClassOf(wordDb.absoluteToRelativeTime(useTime))}"
				}
			}
			if (values.isNotEmpty()) sb.append(" $values")
		}
		if (tier >= 4) {
			sb.append(
				String.format(
					Locale.getDefault(),
					" (F=%.1f S=%.1f U=%.1f R=%.1f)",
					m.freq,
					m.seq,
					m.use,
					m.rec,
				),
			)
		}
		return sb.toString()
	}

	/**
	 * Overload for debug display: extracts candidate-like fields from a
	 * selection-list Map so the existing diagnostic renderer can show metrics.
	 */
	private fun computeSortMetrics(
		item: Map<String, Any?>,
		promoteImported: Boolean,
		activeImportedMask: Long,
		pastEnabled: Boolean,
	): Metrics {
		val output = (item["output"] as? String) ?: ""
		val freqClass = (item["FreqClass"] as? Int) ?: 14
		val classMaskAny = item["ClassMask"]
		val classMask = when (classMaskAny) {
			is Long -> classMaskAny
			is Int -> classMaskAny.toLong()
			is Number -> classMaskAny.toLong()
			else -> 0L
		}
		val useCount = (item["UseCount"] as? Int) ?: 0
		val useTimeAny = item["UseTime"]
		val useTime: Long? = when (useTimeAny) {
			null -> null
			is Long -> useTimeAny
			else -> if (useTimeAny == JSONObject.NULL) null else (useTimeAny as? Number)?.toLong()
		}
		val lastUseTime = wordDb.absoluteToRelativeTime(useTime)
		val lowFrequency = (item["lowFrequency"] as? Boolean) == true
		val candidate = WLD.CandidateEntry(
			wordID = 0,
			lowerWord = output.lowercase(Locale.getDefault()),
			freqClass = freqClass,
			useCount = useCount,
			lastUseTime = lastUseTime,
			classMask = classMask,
			posEncoded = 0,
			isLowFrequency = lowFrequency,
			keysRemaining = (item["keysRemaining"] as? Int) ?: if (item["type"] == "L") 1 else 0,
		)
		return computeSortMetrics(candidate, promoteImported, activeImportedMask, pastEnabled)
	}

	// ── Phase 2: DB lookup + case variant expansion ───────────────────────

	private fun dbForCandidate(candidate: WLD.CandidateEntry): WordDb {
		val isCustom = (candidate.classMask and ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK) != 0L
		return if (isCustom && ::customDb.isInitialized) customDb else wordDb
	}

	private fun buildDisplayEntries(candidate: WLD.CandidateEntry): List<MutableMap<String, Any?>> {
		val db = dbForCandidate(candidate)
		val originalWord = db.getWordByID(candidate.wordID) ?: candidate.lowerWord
		val stats = db.getWordStatsByID(candidate.wordID) ?: db.getOrCreateStats(
			originalWord,
			candidate.freqClass,
			defaultClassMask = candidate.classMask,
		)
		val posTags = PosEncoding.posTagList(candidate.posEncoded)
		val posStr = if (posTags.isNotEmpty()) posTags.joinToString(",") else ""

		fun makeCasePref(form: WordCaseForm) = CasePreference(
			preferredForm = form,
			lowerCount = stats.lowerCaseCount,
			titleCount = stats.titleCaseCount,
			upperCount = stats.upperCaseCount,
			originalCount = stats.originalCaseCount,
		)

		fun makeEntry(output: String, form: WordCaseForm, cnt: Int = 0): MutableMap<String, Any?> {
			val entry = mutableMapOf<String, Any?>(
				// "X" = fully typed (exact), "L" = look-ahead completion needing more keys.
				"type" to if (candidate.keysRemaining == 0) "X" else "L",
				"keysRemaining" to candidate.keysRemaining,
				"display" to output,
				"output" to output,
				"canonicalOutput" to originalWord,
				"countOfOccurrence" to 0,
				"POS" to posStr,
				"FreqClass" to stats.freqClass,
				"ClassMask" to stats.classMask,
				"UseCount" to stats.useCount,
				"UseTime" to (stats.useTime ?: JSONObject.NULL),
				"casePreference" to makeCasePref(form),
				"forcedCaseForm" to form,
				"caseCount" to cnt,
			)
			if (candidate.isLowFrequency) entry["lowFrequency"] = true
			if (PosEncoding.hasPosTag(candidate.posEncoded, "DOM") ||
				PosEncoding.hasPosTag(candidate.posEncoded, "EXT")
			) {
				entry["suppressLeadingSpace"] = true
			}
			return entry
		}

		val caseTypeEnabled = prefs.getBoolean(KEY_CASETYPE_VARIANTS_ENABLED)

		val entries = if (caseTypeEnabled) {
			buildCaseTypeEntries(candidate, originalWord, stats, ::makeEntry)
		} else {
			buildCountBasedEntries(originalWord, stats, ::makeEntry)
		}

		// Mark entries for words whose case must be preserved exactly (e.g. "iOS", "NASA")
		val caseType = PosEncoding.caseType(candidate.posEncoded)
		if (caseType == PosEncoding.CASE_ORIGINAL || caseType == PosEncoding.CASE_ACRONYM) {
			entries.forEach { it["preserveOriginalCase"] = true }
		}
		return entries
	}

	private fun buildCaseTypeEntries(
		candidate: WLD.CandidateEntry,
		originalWord: String,
		stats: DbWordStats,
		makeEntry: (String, WordCaseForm, Int) -> MutableMap<String, Any?>,
	): List<MutableMap<String, Any?>> {
		val caseType = PosEncoding.caseType(candidate.posEncoded)
		val deferredExpand = prefs.getBoolean(KEY_CASETYPE_DEFERRED_EXPAND)

		return when (caseType) {
			PosEncoding.CASE_LOWER_ONLY, PosEncoding.CASE_TITLE_ONLY -> {
				listOf(makeEntry(originalWord, inferCaseForm(originalWord), 1))
			}
			PosEncoding.CASE_ORIGINAL -> {
				listOf(makeEntry(originalWord, WordCaseForm.ORIGINAL, 1))
			}
			PosEncoding.CASE_ACRONYM -> {
				listOf(makeEntry(originalWord, WordCaseForm.UPPER, 1))
			}
			PosEncoding.CASE_LOWER_FIRST -> {
				val lower = applyCaseForm(originalWord, WordCaseForm.LOWER)
				val title = applyCaseForm(originalWord, WordCaseForm.TITLE)
				if (lower == title) {
					return listOf(makeEntry(lower, WordCaseForm.LOWER, 1))
				}
				if (deferredExpand) {
					val entry = makeEntry(
						lower,
						WordCaseForm.LOWER,
						stats.lowerCaseCount.coerceAtLeast(1),
					)
					entry["alternateOutput"] = title
					entry["alternateForm"] = WordCaseForm.TITLE
					entry["display"] = "$lower\t($title)"
					listOf(entry)
				} else {
					listOf(
						makeEntry(lower, WordCaseForm.LOWER, stats.lowerCaseCount.coerceAtLeast(1)),
						makeEntry(title, WordCaseForm.TITLE, stats.titleCaseCount.coerceAtLeast(1)),
					)
				}
			}
			PosEncoding.CASE_TITLE_FIRST -> {
				val title = applyCaseForm(originalWord, WordCaseForm.TITLE)
				val lower = applyCaseForm(originalWord, WordCaseForm.LOWER)
				if (title == lower) {
					return listOf(makeEntry(title, WordCaseForm.TITLE, 1))
				}
				if (deferredExpand) {
					val entry = makeEntry(
						title,
						WordCaseForm.TITLE,
						stats.titleCaseCount.coerceAtLeast(1),
					)
					entry["alternateOutput"] = lower
					entry["alternateForm"] = WordCaseForm.LOWER
					entry["display"] = "$title\t($lower)"
					listOf(entry)
				} else {
					listOf(
						makeEntry(title, WordCaseForm.TITLE, stats.titleCaseCount.coerceAtLeast(1)),
						makeEntry(lower, WordCaseForm.LOWER, stats.lowerCaseCount.coerceAtLeast(1)),
					)
				}
			}
			else -> {
				listOf(makeEntry(originalWord, inferCaseForm(originalWord), 1))
			}
		}
	}

	private fun buildCountBasedEntries(
		originalWord: String,
		stats: DbWordStats,
		makeEntry: (String, WordCaseForm, Int) -> MutableMap<String, Any?>,
	): List<MutableMap<String, Any?>> {
		val buckets = listOf(
			WordCaseForm.LOWER to stats.lowerCaseCount,
			WordCaseForm.TITLE to stats.titleCaseCount,
			WordCaseForm.UPPER to stats.upperCaseCount,
			WordCaseForm.ORIGINAL to stats.originalCaseCount,
		).filter { it.second > 0 }

		if (buckets.isEmpty()) {
			val fallbackForm = inferCaseForm(originalWord)
			return listOf(makeEntry(originalWord, fallbackForm, 1))
		}

		val sorted = buckets.sortedByDescending { it.second }
		return sorted.map { (form, cnt) -> makeEntry(applyCaseForm(originalWord, form), form, cnt) }
	}

	private fun inferCaseForm(word: String): WordCaseForm = when {
		word.all { it.isUpperCase() || !it.isLetter() } -> WordCaseForm.UPPER
		word.isNotEmpty() && word[0].isUpperCase() -> WordCaseForm.TITLE
		else -> WordCaseForm.LOWER
	}

	private fun getActiveImportedMask(activeMask: Long): Long {
		var importedMask = 0L
		for (bit in 5..31) {
			importedMask = importedMask or (1L shl bit)
		}
		return activeMask and importedMask
	}

	// Locale-aware uppercase for letters typed in spell / immediate-emit paths. Pulls the
	// Turkish/Azeri override flag from prefs so `i` → `İ` works regardless of system locale.
	private fun upperWithLocale(s: String): String {
		val override = prefs.getBoolean(KEY_TURKISH_AZERI_CASE_OVERRIDE, false)
		val locale = if (override) Locale("tr") else Locale.getDefault()
		return HierarchyLoader.get(assets).caseMap.toUpper(s, locale)
	}

	/** Builds the 8-key ALL SYMBOLS MODE page for the active controller's current level and page. */
	// One declarative slot→KeyDef table per view kind; splitting it would scatter the page layout.
	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private fun buildAllSymbolsPage(): List<KeyDef> {
		fun blank() = KeyDef(label = null, singleLabel = " ", display = " ", functions = emptyList())
		val ctrl = allSymbols ?: return List(NumberOfKeys) { blank() }
		val page = MutableList(NumberOfKeys) { blank() }
		val upLabel = if (ctrl.atRoot) context.getString(R.string.jtui_btn_back) else context.getString(R.string.jtui_btn_all_symbols_up)
		page[1] = KeyDef(null, upLabel, upLabel, listOf(KF_AllSymbolsAscend to null))
		if (ctrl.pageCount > 1) {
			val pageLabel = if (ctrl.pageIndex < ctrl.pageCount - 1) {
				context.getString(R.string.jtui_btn_all_symbols_page, ctrl.pageIndex + 2)
			} else {
				context.getString(R.string.jtui_btn_all_symbols_page1)
			}
			page[6] = KeyDef(null, pageLabel, pageLabel, listOf(KF_AllSymbolsMore to null))
		}
		ctrl.currentSlots().forEachIndexed { i, view ->
			page[SPATIAL_PAGE_KEYS[i]] = when (view) {
				is SymbolSlotView.Empty -> blank()
				is SymbolSlotView.Leaf -> KeyDef(null, view.char, view.char, listOf(KF_AllSymbolsPick to view.char))
				is SymbolSlotView.Branch ->
					KeyDef(view.preview, null, view.preview.filter { it.isNotEmpty() }.joinToString(""), listOf(KF_AllSymbolsDescend to view.absIndex))
			}
		}
		return page
	}

	/** Maps an ALL SYMBOLS category key (its JSON label) to its translatable display name. */
	private fun allSymbolsCategoryLabel(setKey: String): String = when (setKey) {
		"Punctuation" -> context.getString(R.string.jtui_symcat_punctuation)
		"Brackets" -> context.getString(R.string.jtui_symcat_brackets)
		"Quotes" -> context.getString(R.string.jtui_symcat_quotes)
		"Currency" -> context.getString(R.string.jtui_symcat_currency)
		"Math" -> context.getString(R.string.jtui_symcat_math)
		"Signs" -> context.getString(R.string.jtui_symcat_signs)
		"Fractions" -> context.getString(R.string.jtui_symcat_fractions)
		"Superscripts & units" -> context.getString(R.string.jtui_symcat_superscripts)
		"Arrows" -> context.getString(R.string.jtui_symcat_arrows)
		"Shapes" -> context.getString(R.string.jtui_symcat_shapes)
		"Marks" -> context.getString(R.string.jtui_symcat_marks)
		"Science" -> context.getString(R.string.jtui_symcat_science)
		else -> setKey
	}

	private fun definePages() {
		// The labels are simplified to single strings; behavior is kept.
		fun toGridLabels(text: String): List<String> {
			val chars = text.toCharArray().map { it.toString() }
			val grid = MutableList(9) { "" }
			// Always keep the middle column empty (indices 1,4,7)
			// If 4 or fewer chars, keep the entire middle row empty as well (indices 3,4,5)
			val fillPositions = if (chars.size <= 4) {
				listOf(0, 2, 6, 8) // corners only
			} else {
				listOf(0, 2, 3, 5, 6, 8) // corners + middle row edges
			}
			var cIdx = 0
			for (pos in fillPositions) {
				if (cIdx >= chars.size) break
				grid[pos] = chars[cIdx]
				cIdx += 1
			}
			return grid
		}

		fun toGridLabelsList(display: List<String>): List<String> {
			val grid = MutableList(9) { "" }
			val fillPositions = if (display.size <= 4) {
				listOf(0, 2, 6, 8)
			} else {
				listOf(0, 2, 3, 5, 6, 8)
			}
			var idx = 0
			for (pos in fillPositions) {
				if (idx >= display.size) break
				grid[pos] = display[idx]
				idx += 1
			}
			return grid
		}

		fun ambig(
			display: String,
			keyNum: Int,
			singleKeyPages: List<String> = emptyList(),
		): KeyDef = KeyDef(
			label = toGridLabels(display),
			singleLabel = null,
			display = display,
			functions = listOf(KF_Term to null, KF_Ambig to keyNum),
			singleKeyPages = singleKeyPages,
		)

		// Like ambig, but takes an explicit 9-cell layout (row-major, "" for empty) so the slot
		// positions can deviate from toGridLabels' fixed fill rule. Required for v4.1 5-char
		// keys whose visual placement (slides 1 + 2) doesn't match the algorithmic [0,2,3,5,6].
		fun ambigGrid(
			slots: List<String>,
			keyNum: Int,
			singleKeyPages: List<String> = emptyList(),
		): KeyDef {
			require(slots.size == 9) { "ambigGrid expects 9 cells, got ${slots.size}" }
			return KeyDef(
				label = slots,
				singleLabel = null,
				display = slots.filter { it.isNotEmpty() }.joinToString(""),
				functions = listOf(KF_Term to null, KF_Ambig to keyNum),
				singleKeyPages = singleKeyPages,
			)
		}

		fun btn(display: String, vararg fn: Pair<Int, Any?>): KeyDef = KeyDef(label = null, singleLabel = display, display = display, functions = fn.toList())

		// Like btn but takes a precomputed function list (avoids spread for conditionally-built chains).
		fun btnFns(display: String, fns: List<Pair<Int, Any?>>): KeyDef = KeyDef(label = null, singleLabel = display, display = display, functions = fns)

		fun btnMulti(
			display: String,
			vararg fn: Pair<Int, Any?>,
		): KeyDef = KeyDef(
			label = toGridLabels(display),
			singleLabel = null,
			display = display,
			functions = fn.toList(),
		)

		// Like btnMulti but takes an explicit 9-cell layout (row-major, "" for empty). Use for
		// Spell Mode Phase 1 keys where slot positions must match the v4.1 Main keyboard visual
		// (slides 13 + 18) rather than toGridLabels' fixed fill rule.
		fun btnMultiGrid(
			slots: List<String>,
			vararg fn: Pair<Int, Any?>,
		): KeyDef {
			require(slots.size == 9) { "btnMultiGrid expects 9 cells, got ${slots.size}" }
			return KeyDef(
				label = slots,
				singleLabel = null,
				display = slots.filter { it.isNotEmpty() }.joinToString(""),
				functions = fn.toList(),
			)
		}

		fun btnMultiString(
			display: List<String>,
			vararg fn: Pair<Int, Any?>,
		): KeyDef = KeyDef(
			label = toGridLabelsList(display),
			singleLabel = null,
			display = display.joinToString(" "),
			functions = fn.toList(),
		)
		// Choose spelling page based on layout mode
		val spellingPage =
			if (layoutMode == LayoutMode.Alphabetical) "SpellingAlpha" else "Spelling"

		val functionPagesForMain = if (phraseFlowActive) {
			listOf("Functions0", "Functions1", "Functions2")
		} else {
			listOf("Functions1", "Functions2")
		}

		// Page-slot order matches the prior code's visual positions: NW, N(undo), NE, W, E,
		// SW, S(select), SE. Slide 1 / 2 visuals: keyNum 0 at NW, 1 at NE (with symbols),
		// 2 at W, 3 at E (with functions), 4 at SW, 5 at SE (with navigation).
		// Optimized pages are driven by the active language's layout (its DB's layoutJson row,
		// or the built-in English grids). Page positions stay fixed — 1=UNDO, 6=SELECT — and the
		// List-Function pages ride keyNum 1 (Symbols), 3 (Functions), 5 (Navigation) so function
		// access is identical across languages.
		val optGrids = optimizedGrids()
		fun optGridUpper(k: Int) = withToneLabel(optGrids[k].map { it.uppercase() }, k, alphaMode = false)
		// List-function placement: JT default puts Symbols/Functions/Navigation on keyNums
		// 1/3/5 (page Keys 2/4/7); a language's layout may relocate them (functionKeys in
		// its layoutJson) — e.g. Vietnamese keeps its frequent one-key words function-free.
		val fnKeys = activeLayoutSpec?.functionKeys
		val symbolsKey = fnKeys?.get("symbols") ?: 1
		val functionsKey = fnKeys?.get("functions") ?: 3
		val navigationKey = fnKeys?.get("navigation") ?: 5
		fun singleKeyPagesFor(k: Int): List<String> = when (k) {
			symbolsKey -> listOf("Symbols1", "Symbols2", "Symbols3")
			functionsKey -> functionPagesForMain
			navigationKey -> listOf("Navigation")
			else -> emptyList()
		}
		fun mainPage(gridFor: (Int) -> List<String>): List<KeyDef> = listOf(
			ambigGrid(gridFor(0), 0, singleKeyPagesFor(0)),
			btn(context.getString(R.string.jtui_btn_undo_delete), KF_Undo to null),
			ambigGrid(gridFor(1), 1, singleKeyPagesFor(1)),
			ambigGrid(gridFor(2), 2, singleKeyPagesFor(2)),
			ambigGrid(gridFor(3), 3, singleKeyPagesFor(3)),
			ambigGrid(gridFor(4), 4, singleKeyPagesFor(4)),
			btn(context.getString(R.string.jtui_btn_select), KF_Select to null),
			ambigGrid(gridFor(5), 5, singleKeyPagesFor(5)),
		)
		val main = mainPage(::optGridUpper)

		val alGrids = alphaGrids()
		fun alGridUpper(k: Int) = withToneLabel(alGrids[k].map { it.uppercase() }, k, alphaMode = true)
		val mainAlpha = mainPage(::alGridUpper)

		// The Symbols "back" keys (KF_BackToCaller) are labeled per how Symbols mode was entered:
		// "BACK" when reached from LETTER/SYMBOL MODE, "BACK TO MAIN" when reached from the Main
		// keyboard's List-Function. The Symbols pages are rebuilt on entry (see setCurrentPage) so
		// state.subModeCaller reflects the current entry path.
		val symbolsBackLabel = if (state.subModeCaller == "LetterSymbol") {
			context.getString(R.string.jtui_btn_back)
		} else {
			context.getString(R.string.jtui_btn_back_to_main)
		}

		val symbols1 = listOf(
			btn(context.getString(R.string.jtui_btn_type_multiple_symbols), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "SymbolsMulti1", KF_RefreshUI to null),
			btn(symbolsBackLabel, KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(
				".",
				KF_ClearOutput to null,
				KF_Immed to ".",
				KF_SpaceIfNeeded to null,
				KF_EndSentence to null,
				KF_Shift to 1,
				KF_ClearInput to null,
				KF_ClearOutput to null,
				KF_BackToCaller to null,
				KF_RefreshUI to null,
			),
			btn("-", KF_ClearOutput to null, KF_Immed to "-", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(
				"!",
				KF_ClearOutput to null,
				KF_Immed to "!",
				KF_SpaceIfNeeded to null,
				KF_EndSentence to null,
				KF_Shift to 1,
				KF_ClearInput to null,
				KF_ClearOutput to null,
				KF_BackToCaller to null,
				KF_RefreshUI to null,
			),
			btn(",", KF_ClearOutput to null, KF_Immed to ",", KF_SpaceIfNeeded to null, KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_symbol_pg_2), KF_GoToPage to "Symbols2", KF_RefreshUI to null),
			btn(
				"?",
				KF_ClearOutput to null,
				KF_Immed to "?",
				KF_SpaceIfNeeded to null,
				KF_EndSentence to null,
				KF_Shift to 1,
				KF_ClearInput to null,
				KF_ClearOutput to null,
				KF_BackToCaller to null,
				KF_RefreshUI to null,
			),
		)

		val symbols2 = listOf(
			btn(context.getString(R.string.jtui_btn_space), KF_Immed to " ", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_symbol_pg_1), KF_GoToPage to "Symbols1", KF_RefreshUI to null),
			btn("@", KF_Immed to "@", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(":", KF_Snug to null, KF_Immed to ":", KF_SpaceIfNeeded to null, KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn("(", KF_Immed to " (", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(";", KF_Snug to null, KF_Immed to ";", KF_SpaceIfNeeded to null, KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_symbol_pg_3), KF_GoToPage to "Symbols3", KF_RefreshUI to null),
			btn(")", KF_Snug to null, KF_Immed to ")", KF_SpaceIfNeeded to null, KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
		)

		val symbols3 = listOf(
			btn(context.getString(R.string.jtui_btn_all_symbols_mode), KF_ClearInput to null, KF_SymbolMode to 0),
			btn(context.getString(R.string.jtui_btn_symbol_pg_2), KF_GoToPage to "Symbols2", KF_RefreshUI to null),
			btn("&", KF_Immed to "&", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn("'", KF_Snug to null, KF_Immed to "'", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn("\"", KF_Immed to "\"", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn("*", KF_Immed to "*", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(symbolsBackLabel, KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn("/", KF_Immed to "/", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null),
		)

		val symbolsMulti1 = listOf(
			btn(context.getString(R.string.jtui_btn_all_symbols_mode), KF_ClearInput to null, KF_SymbolMode to 0),
			btn(
				symbolsBackLabel,
				KF_ClearInput to null,
				KF_ClearOutput to null,
				KF_SpaceIfNeededMulti to null,
				KF_EndSentenceMulti to null,
				KF_BackToCaller to null,
				KF_RefreshUI to null,
			),
			btn(
				".",
				KF_ClearOutput to null,
				KF_Immed to ".",
				// KF_SpaceIfNeeded to null,
				// KF_EndSentence to null,
				// KF_Shift to 1,
				KF_ClearInput to null,
				KF_ClearOutput to null,
			),
			btn("-", KF_ClearOutput to null, KF_Immed to "-", KF_ClearInput to null, KF_ClearOutput to null),
			btn(
				"!",
				KF_ClearOutput to null,
				KF_Immed to "!",
				// KF_SpaceIfNeeded to null,
				// KF_EndSentence to null,
				// KF_Shift to 1,
				KF_ClearInput to null,
				KF_ClearOutput to null,
			),
			btn(",", KF_ClearOutput to null, KF_Immed to ",", KF_SpaceIfNeeded to null, KF_ClearInput to null, KF_ClearOutput to null),
			btn(context.getString(R.string.jtui_btn_multi_symbols_pg_2), KF_GoToPage to "SymbolsMulti2", KF_RefreshUI to null),
			btn(
				"?",
				KF_ClearOutput to null,
				KF_Immed to "?",
				// KF_SpaceIfNeeded to null,
				// KF_EndSentence to null,
				// KF_Shift to 1,
				KF_ClearInput to null,
				KF_ClearOutput to null,
			),
		)

		val symbolsMulti2 = listOf(
			btn(context.getString(R.string.jtui_btn_space), KF_Immed to " ", KF_ClearInput to null, KF_ClearOutput to null),
			btn(context.getString(R.string.jtui_btn_multi_symbols_pg_1), KF_GoToPage to "SymbolsMulti1", KF_RefreshUI to null),
			btn("@", KF_Immed to "@", KF_ClearInput to null, KF_ClearOutput to null),
			btn(":", KF_Snug to null, KF_Immed to ":", KF_SpaceIfNeeded to null, KF_ClearInput to null, KF_ClearOutput to null),
			btn("(", KF_Immed to " (", KF_ClearInput to null, KF_ClearOutput to null),
			btn(";", KF_Snug to null, KF_Immed to ";", KF_SpaceIfNeeded to null, KF_ClearInput to null, KF_ClearOutput to null),
			btn(context.getString(R.string.jtui_btn_multi_symbols_pg_3), KF_GoToPage to "SymbolsMulti3", KF_RefreshUI to null),
			btn(")", KF_Snug to null, KF_Immed to ")", KF_SpaceIfNeeded to null, KF_ClearInput to null, KF_ClearOutput to null),
		)

		val symbolsMulti3 = listOf(
			btn(context.getString(R.string.jtui_btn_all_symbols_mode), KF_ClearInput to null, KF_SymbolMode to 0),
			btn(context.getString(R.string.jtui_btn_multi_symbols_pg_2), KF_GoToPage to "SymbolsMulti2", KF_RefreshUI to null),
			btn("&", KF_Immed to "&", KF_ClearInput to null, KF_ClearOutput to null),
			btn("'", KF_Snug to null, KF_Immed to "'", KF_ClearInput to null, KF_ClearOutput to null),
			btn("\"", KF_Immed to "\"", KF_ClearInput to null, KF_ClearOutput to null),
			btn("*", KF_Immed to "*", KF_ClearInput to null, KF_ClearOutput to null),
			btn(
				symbolsBackLabel,
				KF_ClearInput to null,
				KF_ClearOutput to null,
				KF_SpaceIfNeededMulti to null,
				KF_EndSentenceMulti to null,
				KF_BackToCaller to null,
				KF_RefreshUI to null,
			),
			btn("/", KF_Immed to "/", KF_ClearInput to null, KF_ClearOutput to null),
		)

		val numbers1 = listOf(
			btn("1", KF_Immed to "1", KF_GoToPage to "Numbers1"),
			btn(context.getString(R.string.jtui_btn_delete), KF_NumericDelete to null),
			btn("4", KF_Immed to "4", KF_GoToPage to "Numbers1"),
			btn("2", KF_Immed to "2", KF_GoToPage to "Numbers1"),
			btn("5", KF_Immed to "5", KF_GoToPage to "Numbers1"),
			btn("3", KF_Immed to "3", KF_GoToPage to "Numbers1"),
			btn(context.getString(R.string.jtui_btn_done), KF_FinishNumericString to null, KF_BackToCaller to null, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_numbers_6_0), KF_GoToPage to "Numbers2", KF_RefreshUI to null),
		)

		val numbers2 = listOf(
			btn("6", KF_Immed to "6", KF_GoToPage to "Numbers2"),
			btn(context.getString(R.string.jtui_btn_delete), KF_NumericDelete to null),
			btn("9", KF_Immed to "9", KF_GoToPage to "Numbers2"),
			btn("7", KF_Immed to "7", KF_GoToPage to "Numbers2"),
			btn("0", KF_Immed to "0", KF_GoToPage to "Numbers2"),
			btn("8", KF_Immed to "8", KF_GoToPage to "Numbers2"),
			btn(context.getString(R.string.jtui_btn_numbers_punct), KF_GoToPage to "NumbersPunct", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_numbers_1_5), KF_GoToPage to "Numbers1", KF_RefreshUI to null),
		)

		val numbersPunct = listOf(
			btnMultiString(listOf("$", "€", "¥", "£", "₹", "R$"), KF_GoToPage to "NumPunct0", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_cancel), KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btnMultiString(listOf("(", ")", "<", ">", "[", "]"), KF_GoToPage to "NumPunct2", KF_RefreshUI to null),
			btnMultiString(listOf("#", "%", "<SP>", "°", "K", "M"), KF_GoToPage to "NumPunct3", KF_RefreshUI to null),
			btnMulti("+-/*=e", KF_GoToPage to "NumPunct4", KF_RefreshUI to null),
			btn(".", KF_Immed to ".", KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btn(",", KF_Immed to ",", KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btn(":", KF_Immed to ":", KF_GoToPage to "Numbers2", KF_RefreshUI to null),
		)

		// Display a punct char's label in the case that will be emitted: single letters follow the
		// current shift/caps state (so 'e'/'k'/'m' show lowercase until SHIFT); everything else
		// (symbols, "<SP>") is shown verbatim. The emitted case is handled by KF_ImmedNoSpace.
		fun punctLabel(c: String): String = if (c.length == 1 && c[0].isLetter()) {
			if (state.shiftState || isCapsActive()) c.uppercase(Locale.getDefault()) else c.lowercase(Locale.getDefault())
		} else {
			c
		}
		fun punctRow(chars: List<String>): List<KeyDef> = listOf(
			btn(punctLabel(chars[0]), KF_ImmedNoSpace to chars[0], KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_cancel_paren), KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btn(punctLabel(chars[1]), KF_ImmedNoSpace to chars[1], KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btn(punctLabel(chars[2]), KF_ImmedNoSpace to chars[2], KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btn(punctLabel(chars[3]), KF_ImmedNoSpace to chars[3], KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btn(punctLabel(chars[4]), KF_ImmedNoSpace to chars[4], KF_GoToPage to "Numbers2", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_shift), KF_Shift to 2, KF_RefreshUI to null),
			btn(punctLabel(chars[5]), KF_ImmedNoSpace to chars[5], KF_GoToPage to "Numbers2", KF_RefreshUI to null),
		)
		val function0 = listOf(
			btn(context.getString(R.string.jtui_btn_delete_word), KF_DeleteWord to null, KF_ClearInput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_back_to_main), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_done_with_phrase), KF_PhraseDone to null),
			btn(context.getString(R.string.jtui_btn_caps_lock_on_off_spaced), KF_CapsLock to 2, KF_ClearInput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_shift), KF_Shift to 2, KF_ClearInput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_enter), KF_Enter to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_function_pg_1), KF_GoToPage to "Functions1", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_cancel_phrase), KF_CancelNewPhrase to null, KF_GoToPage to "Main", KF_RefreshUI to null),
		)

		val function1 = listOf(
			btn(context.getString(R.string.jtui_btn_caps_lock_on_off), KF_CapsLock to 2, KF_ClearInput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			if (phraseFlowActive) {
				btn(context.getString(R.string.jtui_btn_phrase_entry_functions), KF_GoToPage to "Functions0", KF_RefreshUI to null)
			} else {
				btn(context.getString(R.string.jtui_btn_back_to_main), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "Main", KF_RefreshUI to null)
			},
			btn(context.getString(R.string.jtui_btn_shift), KF_Shift to 2, KF_ClearInput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_speech_on_off), KF_Speech to 2, KF_ClearInput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(
				context.getString(R.string.jtui_btn_speak_sentence),
				KF_SpeakSentence to null,
				KF_ClearInput to null,
				KF_GoToPage to "Main",
				KF_RefreshUI to null,
			),
			btn(context.getString(R.string.jtui_btn_enter), KF_Enter to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_function_pg_2), KF_GoToPage to "Functions2", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_speak_last_selection), KF_SpeakLastSelection to null, KF_ClearInput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
		)

		val function2 = listOf(
			btn(context.getString(R.string.jtui_btn_page_up), KF_ScrollUp to null),
			btn(context.getString(R.string.jtui_btn_function_pg_1), KF_GoToPage to "Functions1", KF_RefreshKeyboardView to null),
			btn(context.getString(R.string.jtui_btn_delete_char), KF_DeleteChar to null, KF_GoToPage to "Functions2", KF_RefreshKeyboardView to null),
			// btn("DELETE\nCHAR", KF_DeleteChar to null, KF_GoToPage to "Functions2"),
			btn(context.getString(R.string.jtui_btn_page_down), KF_ScrollDown to null, KF_GoToPage to "Functions2", KF_RefreshKeyboardView to null),
			// btn("SPEAK\nNEXT\nSENTENCE", KF_SpeakNextSentence to null, KF_ClearInput to null, KF_GoToPage to "Functions2"),
			// btn("SPEAK\nNEXT\nSENTENCE", KF_SpeakNextSentence to null, KF_ClearInput to null),
			// btn("SPEAK\nNEXT\nSENTENCE", KF_SpeakNextSentence to null, KF_GoToPage to "Functions2", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_delete_word), KF_DeleteWord to null, KF_GoToPage to "Functions2", KF_RefreshKeyboardView to null),
			// btn("DELETE\nWORD", KF_DeleteWord to null, KF_GoToPage to "Functions2"),
			btn(context.getString(R.string.jtui_btn_enter), KF_Enter to null, KF_GoToPage to "Functions2", KF_RefreshKeyboardView to null),
			btn(context.getString(R.string.jtui_btn_back_to_main), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_speak_next_sentence), KF_SpeakNextSentence to null, KF_GoToPage to "Functions2", KF_RefreshKeyboardView to null),
		)

		// v4.1 NAVIGATE List-Function (slide 11). Position order: 0=NW, 1=N, 2=NE, 3=W, 4=E,
		// 5=SW, 6=S, 7=SE. Menu key removed; SYSTEM NAV MODE replaces NAV KBD at SW.
		val navigation = listOf(
			btn(
				context.getString(R.string.jtui_btn_add_new_word),
				KF_SpellSetAccumulate to 1,
				KF_ClearInput to null,
				KF_ClearOutput to null,
				KF_SpaceIfNeededMulti to null,
				KF_GoToPage to spellingPage,
				KF_RefreshUI to null,
			),
			btn(context.getString(R.string.jtui_btn_add_new_phrase), KF_ClearInput to null, KF_ClearOutput to null, KF_AddNewPhrase to null, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_letter_symbol_mode), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "LetterSymbol", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_settings), KF_ClearInput to null, KF_EnterSettings to null),
			btn(context.getString(R.string.jtui_btn_123_number_mode), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "Numbers1", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_system_nav_mode), KF_ClearInput to null, KF_OpenNavigationKeyboard to null),
			btn(context.getString(R.string.jtui_btn_back_to_main), KF_GoToPage to "Main", KF_ClearInput to null, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_edit_mode), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "editMode1", KF_RefreshUI to null),
		)

		// v4.1 LETTER/SYMBOL MODE Page 1 (slide 12). UI label is "LETTER SPELL MODE" (formerly
		// "TWO-KEY SPELL MODE"; spec internally calls it "Explicit Letter Mode") — same mode, routes to Spelling /
		// SpellingAlpha per layoutMode. EMOJI button and ← / → arrows are placeholders; arrows
		// support future paging across multiple LETTER/SYMBOL MODE pages and EMOJI wires to a
		// stub in Phase 8.
		val letterSymbol = listOf(
			btn(context.getString(R.string.jtui_btn_letter_spell_mode), KF_SpellSetAccumulate to 0, KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to spellingPage, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_undo_delete), KF_Undo to null),
			btn(context.getString(R.string.jtui_btn_punct_symbol_mode), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "Symbols1", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_nav_left), KF_CursorLeft to null),
			btn(context.getString(R.string.jtui_btn_nav_right), KF_CursorRight to null),
			btn(context.getString(R.string.jtui_btn_123_number_mode), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "Numbers1", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_back_to_main), KF_ClearInput to null, KF_ClearOutput to null, KF_GoToPage to "Main", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_emoji_gifs), KF_RefreshUI to null),
		)

		// v4.1 Two-Key Spell Mode Phase 1 (slide 13). Lowercase letters at the same 9-cell slot
		// positions as the Main keyboard's Optimized layout, with DELETE replacing UNDO/DELETE
		// at N and DONE replacing SELECT at S. Tapping a letter-group key descends to its Phase
		// 2 page (Spell0/2/3/4/5/7) for unambiguous letter selection.
		// DONE finalizes the spell session. ADD NEW WORD / abbreviation (accumulate) appends a
		// trailing space to separate the new word; LETTER/SYMBOL MODE emits no space — only
		// explicitly-selected characters (BUG: no autospace on DONE).
		val spellDoneFns: List<Pair<Int, Any?>> = if (state.spellAccumulate) {
			listOf(KF_CheckAddCustomWord to null, KF_Immed to " ", KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null)
		} else {
			listOf(KF_CheckAddCustomWord to null, KF_ClearInput to null, KF_ClearOutput to null, KF_BackToCaller to null, KF_RefreshUI to null)
		}

		val spelling = listOf(
			btnMultiGrid(optGrids[0], KF_GoToPage to "Spell0", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_delete), KF_SpellDelete to null), // Delete the previously output character
			btnMultiGrid(optGrids[1], KF_GoToPage to "Spell2", KF_RefreshUI to null),
			btnMultiGrid(optGrids[2], KF_GoToPage to "Spell3", KF_RefreshUI to null),
			btnMultiGrid(optGrids[3], KF_GoToPage to "Spell4", KF_RefreshUI to null),
			btnMultiGrid(optGrids[4], KF_GoToPage to "Spell5", KF_RefreshUI to null),
			btnFns(context.getString(R.string.jtui_btn_done), spellDoneFns),
			btnMultiGrid(optGrids[5], KF_GoToPage to "Spell7", KF_RefreshUI to null),
		)

		// v4.1 Two-Key Spell Mode Phase 1 — Alphabetic variant (slide 18). Mirrors mainAlpha
		// slot positions in lowercase.
		val spellingAlpha = listOf(
			btnMultiGrid(alGrids[0], KF_GoToPage to "SpellAlpha0", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_delete), KF_SpellDelete to null), // Delete the previously output character
			btnMultiGrid(alGrids[1], KF_GoToPage to "SpellAlpha2", KF_RefreshUI to null),
			btnMultiGrid(alGrids[2], KF_GoToPage to "SpellAlpha3", KF_RefreshUI to null),
			btnMultiGrid(alGrids[3], KF_GoToPage to "SpellAlpha4", KF_RefreshUI to null),
			btnMultiGrid(alGrids[4], KF_GoToPage to "SpellAlpha5", KF_RefreshUI to null),
			btnFns(context.getString(R.string.jtui_btn_done), spellDoneFns),
			btnMultiGrid(alGrids[5], KF_GoToPage to "SpellAlpha7", KF_RefreshUI to null),
		)

		// v4.1 diacritic drill-down wiring. Reads the language-scope setting + the diacritic tree once per
		// definePages call. A letter routes to a per-letter SpellDiac_<letter> page only when it has
		// variants in scope. allowedDiacritics is the union of the in-scope languages' corpus-derived
		// diacritic characters (null = "All variants"; empty = Off / none) — computed with no DB I/O.
		val diacriticScope = prefs.getString(KEY_SPELL_DIACRITIC_SCOPE, DIACRITIC_SCOPE_CURRENT)
		val allowedDiacritics: Set<Char>? = LanguageRegistry.allowedCharsFor(
			LanguageRegistry.load(prefs),
			diacriticScope,
			LanguageRegistry.activeLanguageNames(prefs),
		)
		val diacriticTree: Map<Char, DiacriticGroup> = HierarchyLoader.get(assets).diacriticTree
		// Single-char cells of each branch's grids are its explicit letters (slot labels are
		// multi-char): used to keep first-class variants out of their base letter's spell drill.
		val optimizedExplicitLetters: Set<Char> =
			optGrids.flatMap { g -> g.mapNotNull { it.singleOrNull()?.lowercaseChar() } }.toSet()
		val alphaExplicitLetters: Set<Char> =
			alGrids.flatMap { g -> g.mapNotNull { it.singleOrNull()?.lowercaseChar() } }.toSet()

		// SHIFT key for Spell Mode — label and behavior cycle SHIFT → CAPS LOCK ON → CAPS LOCK OFF
		// with each press (KF_SpellShiftCycle). Letter entry while shifted clears shift; CAPS LOCK
		// persists until explicitly toggled off.
		val spellShiftLabel = when {
			state.capsState -> context.getString(R.string.jtui_btn_spell_caps_lock_off)
			state.shiftState -> context.getString(R.string.jtui_btn_spell_caps_lock_on)
			else -> context.getString(R.string.jtui_btn_shift)
		}
		fun spellShiftBtn(): KeyDef = btn(spellShiftLabel, KF_SpellShiftCycle to null, KF_RefreshUI to null)

		// v4.1 Phase 4.2 — dynamic Phase 2 builder backed by the pure VariantLayout partitioner.
		// Each letter of the Phase 1 group is placed at its Phase 2 position. Letters with
		// visible diacritic variants render a preview (base + variants / sub-group strings) and
		// drill into selection pages. The full variant set (base + variants) is partitioned by
		// layoutVariants across the letter's key and, when it overflows, an adjacent empty key —
		// with sub-groups for very large sets so no variant is ever dropped (see VariantLayout).
		val variantUpper = state.shiftState || isCapsActive()
		val cellToPos = listOf(0 to 0, 2 to 2, 3 to 3, 5 to 4, 6 to 5, 8 to 7) // Phase-1 cell → Phase-2 pos
		val letterPosAdjacency = mapOf(0 to listOf(3), 2 to listOf(4), 3 to listOf(0, 5), 4 to listOf(2, 7), 5 to listOf(3), 7 to listOf(4))

		fun displayCase(s: String): String = if (variantUpper) upperWithLocale(s) else s
		fun displayVariant(v: DiacriticVariant): String = if (variantUpper) v.upper ?: v.char.uppercase(Locale.getDefault()) else v.char

		val dynamicSpellPages = mutableMapOf<String, List<KeyDef>>()

		// A single (leaf) cell emits its char unambiguously and returns to Phase 1 — this is the
		// first and only point a character is output for the key sequence (no premature emit). A
		// group cell drills to its sub-page.
		fun slotKey(slot: LayoutSlot, subPageKey: String, returnTo: String): KeyDef = when (slot) {
			is LayoutSlot.Single -> btn(slot.value, KF_ImmedSpell to slot.value, KF_GoToPage to returnTo, KF_RefreshUI to null)
			is LayoutSlot.Group -> btn(slot.values.joinToString(""), KF_GoToPage to subPageKey, KF_RefreshUI to null)
		}

		// Build a selection page from a key's slots, placing each slot at the SAME spatial position
		// the preview grid used (so a glyph shown in a preview cell appears on the matching drill
		// key). Singles emit; groups drill to "<pageKey>_g<i>" with sub-pages registered.
		fun buildSelectionPage(slots: List<LayoutSlot>, pageKey: String, returnTo: String): List<KeyDef> {
			val page = MutableList<KeyDef>(8) { btn(" ") }
			page[1] = btn(context.getString(R.string.jtui_btn_cancel_paren), KF_GoToPage to returnTo, KF_RefreshUI to null)
			page[6] = spellShiftBtn()
			var groupIdx = 0
			slots.assignSpatial().forEachIndexed { spatial, slot ->
				if (slot == null) return@forEachIndexed
				val subKey = "${pageKey}_g$groupIdx"
				page[SPATIAL_PAGE_KEYS[spatial]] = slotKey(slot, subKey, returnTo)
				if (slot is LayoutSlot.Group) {
					dynamicSpellPages[subKey] = buildSelectionPage(slot.values.map { LayoutSlot.Single(it) }, subKey, returnTo)
					groupIdx++
				}
			}
			return page
		}

		// Tone-keystroke languages (LayoutSpec v2): (base letter, tone id) -> marked char,
		// inverted from tones.fold. Drives the spell tone level (each tone's marked form is
		// picked on the KEY that carries that tone; the toneless key = ngang/bare letter).
		val toneSpec = activeLayoutSpec?.tones
		val markedForm: Map<Pair<Char, String>, Char> = toneSpec?.fold
			?.entries?.associate { (marked, baseAndTone) -> baseAndTone to marked } ?: emptyMap()

		fun buildTonePage(spec: LayoutSpec.ToneSpec, baseChar: Char, returnTo: String): List<KeyDef> {
			val page = MutableList<KeyDef>(8) { btn(" ") }
			page[1] = btn(context.getString(R.string.jtui_btn_cancel_paren), KF_GoToPage to returnTo, KF_RefreshUI to null)
			page[6] = spellShiftBtn()
			val toneKeys = if (returnTo == "SpellingAlpha") {
				activeLayoutSpec?.alphaToneKeys ?: spec.keys
			} else {
				spec.keys
			}
			fun emitBtn(s: String) = btn(displayCase(s), KF_ImmedSpell to s, KF_GoToPage to returnTo, KF_RefreshUI to null)
			// keyNum -> page position for the six ambiguous keys.
			val keyPagePos = intArrayOf(0, 2, 3, 4, 5, 7)
			val tonelessKey = (0..5).first { k -> toneKeys.values.none { it == k } }
			page[keyPagePos[tonelessKey]] = emitBtn(baseChar.toString())
			toneKeys.forEach { (toneId, keyNum) ->
				markedForm[baseChar to toneId]?.let { marked ->
					page[keyPagePos[keyNum]] = emitBtn(marked.toString())
				}
			}
			return page
		}

		fun buildSpellPhase2(group9Cell: List<String>, returnTo: String): List<KeyDef> {
			val page = MutableList<KeyDef>(8) { btn(" ") }
			page[1] = btn(context.getString(R.string.jtui_btn_cancel_paren), KF_GoToPage to returnTo, KF_RefreshUI to null)
			page[6] = spellShiftBtn()
			val occupied = mutableSetOf(1, 6)

			val groupChars = cellToPos.mapNotNull { (cell, pos) ->
				val char = group9Cell[cell].takeIf { it.isNotBlank() } ?: return@mapNotNull null
				val baseChar = char.lowercase().firstOrNull() ?: return@mapNotNull null
				Triple(char, pos, baseChar)
			}

			// First pass: singular (no-variant) letters; record variant letters for the second pass.
			// Tone-keystroke languages replace the generic diacritic drill entirely: a tone vowel's
			// cell opens the tone page (level 3 = pick the tone on its own key); every other letter
			// is singular; a multi-character slot cell ("@_") drills to a pick page of its chars.
			data class LetterEntry(val char: String, val pos: Int, val variants: List<DiacriticVariant>)
			val variantLetters = mutableListOf<LetterEntry>()
			fun addSlotLevel2(char: String, pos: Int, returnTo: String, page: MutableList<KeyDef>) {
				// The cell label may be an elided display ("15_", stacked "#/-\n@+").
				val slotChars = resolveSlotChars(char)
				val pageKey = "SpellSlot_${returnTo}_" + slotChars.joinToString("")
				// Every slot's L2 key face is a spatial map of its L3 pick keys (invariant,
				// Cliff's spec): each char renders at the cell corresponding to the key that
				// will pick it, derived from the SAME placements that build the pick page.
				val placements = slotPickPlacements(slotChars, pos)
				val slotFace = MutableList(9) { "" }
				placements.forEach { (pp, c) -> CELL_FOR_PAGE_KEY[pp]?.let { cell -> slotFace[cell] = c } }
				page[pos] = KeyDef(label = slotFace, singleLabel = null, display = char, functions = listOf(KF_GoToPage to pageKey, KF_RefreshUI to null))
				val pickPage = MutableList<KeyDef>(8) { btn(" ") }
				pickPage[1] = btn(context.getString(R.string.jtui_btn_cancel_paren), KF_GoToPage to returnTo, KF_RefreshUI to null)
				pickPage[6] = spellShiftBtn()
				placements.forEach { (pp, c) ->
					pickPage[pp] = btn(c, KF_ImmedSpell to c, KF_GoToPage to returnTo, KF_RefreshUI to null)
				}
				dynamicSpellPages[pageKey] = pickPage
			}

			for ((char, pos, baseChar) in groupChars) {
				// Multi-char slot cells (digit slots, string punctuation) are language-
				// agnostic: identical for tone (v2) and letter (v1) layouts.
				if (char.length > 1) {
					addSlotLevel2(char, pos, returnTo, page)
					occupied.add(pos)
				} else if (toneSpec != null) {
					when {
						activeLayoutSpec?.spellToneVowels?.contains(baseChar) == true -> {
							val pageKey = "SpellTone_${returnTo}_$baseChar"
							page[pos] = btn(displayCase(char), KF_GoToPage to pageKey, KF_RefreshUI to null)
							dynamicSpellPages[pageKey] = buildTonePage(toneSpec, baseChar, returnTo)
						}
						else -> page[pos] = btn(displayCase(char), KF_ImmedSpell to char, KF_GoToPage to returnTo, KF_RefreshUI to null)
					}
					occupied.add(pos)
				} else {
					val group = diacriticTree[baseChar]
					// Mixed-mapping layouts (Spanish v5): a variant that is an explicit letter of
					// this branch's layout (á on its own key) is reached directly, never via its
					// base's drill — mirrors the WLD rule "explicit layout letters always win".
					val explicitLetters = if (returnTo == "SpellingAlpha") alphaExplicitLetters else optimizedExplicitLetters
					val variants = if (group != null) {
						variantsForCharSet(group, allowedDiacritics)
							.filter { v -> v.char.firstOrNull()?.lowercaseChar() !in explicitLetters }
					} else {
						emptyList()
					}
					if (variants.isEmpty()) {
						page[pos] = btn(displayCase(char), KF_ImmedSpell to char, KF_GoToPage to returnTo, KF_RefreshUI to null)
						occupied.add(pos)
					} else {
						variantLetters.add(LetterEntry(char, pos, variants))
					}
				}
			}

			// Second pass: partition each variant letter's items via layoutVariants, render the
			// primary (and optional neighbor) preview keys, and register the drill pages.
			// Every variant letter's own position is reserved up front so one letter's variants
			// can never land on (and later be clobbered by) another variant letter's key.
			variantLetters.forEach { occupied.add(it.pos) }
			val letterPositions = cellToPos.map { it.second }
			for ((char, primaryPos, variants) in variantLetters) {
				val baseChar = char.lowercase().single()
				// Free cells this letter's variants may use: spatially adjacent ones first (easier
				// to scan from the base letter), then any other free letter cell — a non-adjacent
				// free cell still beats hiding variants behind a drill page.
				val freeCells = (letterPosAdjacency[primaryPos].orEmpty() + letterPositions)
					.distinct().filter { it !in occupied }
				val neighborPos = freeCells.firstOrNull()

				// Namespace drill-page keys by returnTo so the Optimized (returnTo="Spelling") and
				// Alphabetic (returnTo="SpellingAlpha") builds don't collide on a shared base letter
				// (e.g. 'u' appears in both layouts) — a collision would send the user back to the
				// wrong spell base layer (BUG #1).
				val varBase = "SpellVar_${returnTo}_$baseChar"

				when {
					// Efficiency spread: with enough free cells, the base form sits ALONE on its
					// own key as a one-press direct emit (the common, unaccented case) and EACH
					// variant gets its own free cell as a one-press direct emit — no drill level.
					variants.size <= freeCells.size -> {
						page[primaryPos] = btn(displayCase(char), KF_ImmedSpell to char, KF_GoToPage to returnTo, KF_RefreshUI to null)
						occupied.add(primaryPos)
						variants.forEachIndexed { i, variant ->
							val glyph = displayVariant(variant)
							page[freeCells[i]] = btn(glyph, KF_ImmedSpell to glyph, KF_GoToPage to returnTo, KF_RefreshUI to null)
							occupied.add(freeCells[i])
						}
					}

					// Grouped fallback: fewer free cells than variants, but they all fit on one
					// key — base emits directly and ALL variants share the free key behind one
					// drill page.
					variants.size <= VariantLayout.CELLS_PER_KEY && neighborPos != null -> {
						page[primaryPos] = btn(displayCase(char), KF_ImmedSpell to char, KF_GoToPage to returnTo, KF_RefreshUI to null)
						occupied.add(primaryPos)
						val variantLayout = layoutVariants(variants.map { displayVariant(it) }, hasNeighbor = false)
						page[neighborPos] = btnMultiGrid(
							variantLayout.primary.toPreviewGrid(),
							KF_GoToPage to "${varBase}_n",
							KF_RefreshUI to null,
						)
						dynamicSpellPages["${varBase}_n"] = buildSelectionPage(variantLayout.primary, "${varBase}_n", returnTo)
						occupied.add(neighborPos)
					}

					// Packed fallback: variants overflow a single key (> CELLS_PER_KEY) or no
					// cell is free — base+variants preview on the base key, drill to select.
					else -> {
						val items = listOf(displayCase(char)) + variants.map { displayVariant(it) }
						val layout = layoutVariants(items, hasNeighbor = neighborPos != null)
						// Preview keys only drill — no character is emitted until an unambiguous
						// selection on the drill page (BUG #2).
						page[primaryPos] = btnMultiGrid(
							layout.primary.toPreviewGrid(),
							KF_GoToPage to "${varBase}_p",
							KF_RefreshUI to null,
						)
						dynamicSpellPages["${varBase}_p"] = buildSelectionPage(layout.primary, "${varBase}_p", returnTo)
						occupied.add(primaryPos)

						if (layout.neighbor.isNotEmpty() && neighborPos != null) {
							page[neighborPos] = btnMultiGrid(
								layout.neighbor.toPreviewGrid(),
								KF_GoToPage to "${varBase}_n",
								KF_RefreshUI to null,
							)
							dynamicSpellPages["${varBase}_n"] = buildSelectionPage(layout.neighbor, "${varBase}_n", returnTo)
							occupied.add(neighborPos)
						}
					}
				}
			}
			return page
		}

		pages.clear()
		pages["Main"] = if (layoutMode == LayoutMode.Alphabetical) mainAlpha else main
		pages["Symbols1"] = symbols1
		pages["Symbols2"] = symbols2
		pages["Symbols3"] = symbols3
		pages["SymbolsMulti1"] = symbolsMulti1
		pages["SymbolsMulti2"] = symbolsMulti2
		pages["SymbolsMulti3"] = symbolsMulti3
		pages["Numbers1"] = numbers1
		pages["Numbers2"] = numbers2
		pages["NumbersPunct"] = numbersPunct
		pages["NumPunct0"] = punctRow(listOf("$", "€", "¥", "£", "₹", "R$"))
		pages["NumPunct2"] = punctRow(listOf("(", ")", "<", ">", "[", "]"))
		pages["NumPunct3"] = punctRow(listOf("#", "%", "<SP>", "°", "K", "M"))
		pages["NumPunct4"] = punctRow(listOf("+", "-", "/", "*", "=", "e"))
		pages["Functions0"] = function0
		pages["Functions1"] = function1
		pages["Functions2"] = function2
		pages["Navigation"] = navigation
		pages["LetterSymbol"] = letterSymbol

		val editModeCursor = listOf(
			btn(context.getString(R.string.edit_key_select_text), KF_SelectText to null, KF_RefreshUI to null),
			btn("\u2191", KF_CursorUp to null),
			btn(context.getString(R.string.edit_key_cut_copy_paste_shift), KF_GoToPage to "editMode2", KF_RefreshUI to null),
			btn("\u2190", KF_CursorLeft to null),
			btn("\u2192", KF_CursorRight to null),
			btn("", KF_CycleCursorMode to null, KF_RefreshUI to null),
			btn("\u2193", KF_CursorDown to null),
			btn(context.getString(R.string.edit_key_back_to_main), KF_BackToMain to null, KF_RefreshUI to null),
		)
		pages["editMode1"] = editModeCursor

		val editModeBookmark = listOf(
			btn(context.getString(R.string.edit_key_select_text), KF_SelectText to null, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_jump_to_mark_a), KF_JumpToMarkA to null),
			btn(context.getString(R.string.edit_key_cut_copy_paste_shift), KF_GoToPage to "editMode2", KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_set_mark_a), KF_SetMarkA to null),
			btn(context.getString(R.string.jtui_btn_set_mark_b), KF_SetMarkB to null),
			btn("", KF_CycleCursorMode to null, KF_RefreshUI to null),
			btn(context.getString(R.string.jtui_btn_jump_to_mark_b), KF_JumpToMarkB to null),
			btn(context.getString(R.string.edit_key_back_to_main), KF_BackToMain to null, KF_RefreshUI to null),
		)
		pages["editModeBookmark"] = editModeBookmark

		val editMode2Clipboard = listOf(
			btn(context.getString(R.string.edit_key_cut), KF_Cut to null),
			btn(context.getString(R.string.edit_key_undo), KF_EditUndo to null),
			btn(context.getString(R.string.edit_key_adjust_case), KF_GoToPage to "editMode3", KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_copy), KF_Copy to null),
			btn(context.getString(R.string.edit_key_back_to_cursor), KF_GoToPage to "editMode1", KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_paste), KF_Paste to null),
			btn(context.getString(R.string.edit_key_speak_sentence), KF_SpeakSelectionOrSentence to null, KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_back_to_main), KF_BackToMain to null, KF_RefreshUI to null),
		)
		pages["editMode2"] = editMode2Clipboard

		val editMode3Case = listOf(
			btn(context.getString(R.string.edit_key_case_to_title), KF_CaseToTitle to null, KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_undo), KF_EditUndo to null),
			btn(context.getString(R.string.edit_key_case_to_sentence), KF_CaseToSentence to null, KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_case_to_upper), KF_CaseToUpper to null, KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_back_to_cursor), KF_GoToPage to "editMode1", KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_case_to_lower), KF_CaseToLower to null, KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_edit_word), KF_EditWord to null, KF_GoToPage to "Spelling", KF_RefreshUI to null),
			btn(context.getString(R.string.edit_key_back_to_main), KF_BackToMain to null, KF_RefreshUI to null),
		)
		pages["editMode3"] = editMode3Case

		val editMode5Placeholder = listOf(
			btn(context.getString(R.string.jtui_btn_back_to_edit), KF_GoToPage to "editMode1", KF_RefreshUI to null),
			btn(""),
			btn(""),
			btn(""),
			btn(""),
			btn(""),
			btn(""),
			btn(""),
		)
		pages["editMode5"] = editMode5Placeholder
		pages["Spelling"] = spelling
		pages["SpellingAlpha"] = spellingAlpha
		// v4.1 Phase 4.2 — Phase 2 spell pages built dynamically per group via buildSpellPhase2.
		// Each takes the Phase 1 9-cell letter layout (positions [0,2,3,5,6,8] map to Phase 2
		// keyboard positions [0,2,3,4,5,7]) and produces a page where diacritic-bearing letters
		// show preview grids that drill into variant-selection pages — with overflow into the
		// adjacent empty position and cluster-string drill-down for 13+ variants.
		pages["Spell0"] = buildSpellPhase2(optGrids[0], "Spelling")
		pages["Spell2"] = buildSpellPhase2(optGrids[1], "Spelling")
		pages["Spell3"] = buildSpellPhase2(optGrids[2], "Spelling")
		pages["Spell4"] = buildSpellPhase2(optGrids[3], "Spelling")
		pages["Spell5"] = buildSpellPhase2(optGrids[4], "Spelling")
		pages["Spell7"] = buildSpellPhase2(optGrids[5], "Spelling")
		pages["SpellAlpha0"] = buildSpellPhase2(alGrids[0], "SpellingAlpha")
		pages["SpellAlpha2"] = buildSpellPhase2(alGrids[1], "SpellingAlpha")
		pages["SpellAlpha3"] = buildSpellPhase2(alGrids[2], "SpellingAlpha")
		pages["SpellAlpha4"] = buildSpellPhase2(alGrids[3], "SpellingAlpha")
		pages["SpellAlpha5"] = buildSpellPhase2(alGrids[4], "SpellingAlpha")
		pages["SpellAlpha7"] = buildSpellPhase2(alGrids[5], "SpellingAlpha")

		// Register all drill-down (SpellVar_*) pages collected during Phase 2 page builds.
		pages.putAll(dynamicSpellPages)

		// ── Settings navigation pages ────────────────────────────────
		// Key labels are placeholders — the controller dynamically overrides them via SettingsDisplayState.
		pages["Settings"] = (0..7).map { i ->
			btn("", KF_SettingsKey to i)
		}
		pages["SettingsSlider"] = (0..7).map { i ->
			btn("", KF_SettingsKey to i)
		}
		pages["SettingsTest"] = (0..7).map { i ->
			btn("${i + 1}", KF_SettingsKey to i)
		}
	}

	// ── Settings mode entry/exit ─────────────────────────────────────

	internal fun enterSettingsMode() {
		isInSettingsMode = true
		val controller = org.continuouspath.justtype.settings.KeyboardSettingsController(
			context = context,
			prefs = prefs,
			onDisplayUpdate = { displayState ->
				onSettingsDisplayUpdate(displayState)
				// Speak if speech text is provided and "Speak Selected Key" is enabled
				if (prefs.getBoolean(KEY_SPEAK_SELECTED_KEY)) {
					displayState.speechText?.let { sayUiInterruptible(it) }
				}
			},
			onSettingsExit = {
				exitSettingsMode()
			},
			onRequestKeyboardPage = { pageName ->
				state.currentPage = pageName
			},
			onApplySettingImmediate = { key, value ->
				onSettingsApply(key, value)
			},
			onRevertSettingImmediate = { key, value ->
				onSettingsApply(key, value)
			},
			sayInterruptible = sayUiInterruptible,
			onLaunchAction = onSettingsAction,
			langpackServices = settingsLangpackServices,
		)
		settingsController = controller
		onSettingsEnter()
		controller.enter()
	}

	/**
	 * Re-emits the settings overlay + key legend after a JTUI reinit that rebuilt the pages
	 * (Apply on a reinit-class setting while Settings Mode stays active). No-op outside
	 * Settings Mode.
	 */
	fun refreshSettingsDisplay() {
		settingsController?.refreshDisplay()
	}

	fun exitSettingsMode() {
		isInSettingsMode = false
		settingsController?.dispose()
		settingsController = null
		state.currentPage = "Navigation"
		onSettingsExit()
		forceUpdateUi()
	}

	private fun updateKeysAndSelection() {
		if (isInSettingsMode && settingsController != null) {
			// A reinit while Settings Mode is active (e.g. Apply on Typing Language or Letter
			// Arrangement) must not eject the user: keep key routing on the Settings page so
			// buttonPressed still reaches the controller. The IME refreshes the settings
			// display after the reinit completes.
			log(DebugCategory.AmbigBuffer, "[updateKeysAndSelection 1] in settings mode — keeping Settings page (was ${getCurrentPage()})")
			state.currentPage = "Settings"
		} else {
			log(DebugCategory.AmbigBuffer, "[updateKeysAndSelection 1] resetting current page from ${getCurrentPage()}  to  $StartingPage    state.currentSelection set to null")
			setCurrentPageToStartingPage()
		}
		state.currentSelection = null
		state.keyHistory.clear()
		state.systemSelectionList.clear()
		state.ambiguousKeySequence.clear()
		state.selectKeyCount = 0
		state.listFunctionCount = 0
		state.listFunctionPresent = false
		state.outputString = ""
		state.immedCharString = ""
		state.numericString = ""
		state.capsState = false
		state.speakState = true
		state.capitalizePending = false
		state.isSpellingMode = false
		onNumericOutput("")

		updateSelectionList(listOf(emptyList()), null)
		debugLog("[POST updateSelectionList 2] currentSelection=${state.currentSelection}   listFunctionCount=${state.listFunctionCount} ")
		updateUi(false)
	}

	// ----- Helpers used by IME for reconstruction / context pulls -----
	fun withUiSuppressed(block: () -> Unit) {
		val prev = suppressUi
		suppressUi = true
		try {
			block()
		} finally {
			suppressUi = prev
		}
	}

	// Map word to ambiguous key indices (0..5) based on current layout mapping
	/**
	 * The word's ACTUAL trie key sequence (tone keystrokes included) — unlike
	 * [mapWordToKeyIndices], which is display-map based and tone-unaware.
	 */
	fun wordKeySequence(word: String): List<Int>? = if (::wld.isInitialized) wld.translateToKeysOrNull(word) else null

	/**
	 * Word -> key sequence for pull-in replay. Delegates to the WLD — the
	 * single source of key-sequence truth — so tone keystrokes are included
	 * and placed per the CURRENT tone-position setting (TAE/TAV at the time
	 * of restoration, not of original entry; Cliff, 2026-08-08). The WLD is
	 * mode- and setting-aware (rebuilt on layout/tone-position changes), and
	 * its char map already covers case, diacritic variants, and explicit
	 * variant letters. The previous hand-rolled letters-only map silently
	 * dropped tone keystrokes, breaking toned-word pull-in in both modes.
	 */
	@androidx.annotation.VisibleForTesting
	fun ngbUserRowsForTest(ctx: String): List<Pair<String, Int>> = if (::customDb.isInitialized) customDb.ngbUserRows(ngbLang, ctx) else emptyList()

	@androidx.annotation.VisibleForTesting
	fun selStatsForTest(): Map<String, Double> = if (::customDb.isInitialized) customDb.selStatsAll(ngbLang) else emptyMap()

	@androidx.annotation.VisibleForTesting
	fun selectionListForTest(): List<Map<String, Any?>> = selectionList

	@androidx.annotation.VisibleForTesting
	fun pagedSelectPageForTest(): Int? = state.pagedSelectPage

	/** Last consumed confidence observation: (posterior, keystrokes, fired). */
	@androidx.annotation.VisibleForTesting
	var ngbConfLastObservationForTest: Triple<Double, Int, Boolean>? = null
		private set

	@androidx.annotation.VisibleForTesting
	fun ngbConfWeightsForTest(): Map<String, Double> = ngbConfidence.exportWeights()

	@androidx.annotation.VisibleForTesting
	fun ngbConfFlushForTest() = ngbConfFlush()

	fun mapWordToKeyIndices(word: String): List<Int>? = if (::wld.isInitialized) wld.translateToKeysOrNull(word) else null

	private fun computeRootLetterHints(
		anyFreqMask: Long,
		minFreqMask: Long,
		minFreqClass: Int?,
	): Set<Char> {
		debugLog(
			DebugCategory.WordDb,
			"[rootHints] computeRootLetterHints called: ambigSeq=${state.ambiguousKeySequence.size} keyHistory=${state.keyHistory.size} selectCount=${state.selectKeyCount} currentSelection=${state.currentSelection}",
		)
		// init()/reload mutate the root-entry lists under vocabLock; read under it too (CME otherwise).
		return synchronized(vocabLock) { wld.getRootLettersFiltered(anyFreqMask, minFreqMask, minFreqClass) }
	}

	private fun computeAccentRootHints(): Set<Char> {
		val config = buildAccentMaskConfig()
		if (config.accentMask == 0L) return emptySet()
		val hints = synchronized(vocabLock) {
			wld.getRootLettersAccented(
				accentMask = config.accentMask,
				minFreqClass = config.minFreqClass,
				maxFreqClass = config.maxFreqClass,
				maxUseCountSystem = config.maxUseCountSystem,
				maxUseCountImported = config.maxUseCountImported,
			)
		}
		debugLog(DebugCategory.WordDb, "[accentHints] root hints size=${hints.size}")
		return hints
	}

	private data class VocabMaskConfig(
		val anyFreqMask: Long,
		val minFreqMask: Long,
		val minFreqClass: Int?,
	)

	private data class AccentMaskConfig(
		val accentMask: Long,
		val minFreqClass: Int?,
		val maxFreqClass: Int?,
		val maxUseCountSystem: Int?,
		val maxUseCountImported: Int?,
	)

	private fun buildActiveModuleMask(): Long {
		val includeJustType = prefs.getBoolean(KEY_VOCAB_INCLUDE_JUSTTYPE)
		val includeCustom = prefs.getBoolean(KEY_VOCAB_INCLUDE_CUSTOM_WORDS)
		val includePhrases = prefs.getBoolean(KEY_VOCAB_INCLUDE_PHRASES)
		var mask = 0L
		if (includeJustType) mask = mask or ClassMasks.CLASS_JUSTTYPE_MASK
		if (includeCustom) mask = mask or ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK
		if (includePhrases) mask = mask or ClassMasks.CLASS_PHRASES_MASK
		val dynamicMask = prefs.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
		return mask or dynamicMask
	}

	private fun buildNextLetterMaskConfig(): VocabMaskConfig = buildVocabMaskConfig(KEY_VOCAB_MIN_FREQ_SELECTION)

	private fun shouldShowRootHints(): Boolean {
		val noAmbig = state.ambiguousKeySequence.isEmpty()
		val noHistory = state.keyHistory.isEmpty()
		return (noAmbig && noHistory) || state.selectKeyCount > 0
	}

	private fun buildVocabMaskConfig(minFreqKey: String): VocabMaskConfig {
		val includeJustType = prefs.getBoolean(KEY_VOCAB_INCLUDE_JUSTTYPE)
		val includeCustom = prefs.getBoolean(KEY_VOCAB_INCLUDE_CUSTOM_WORDS)
		val includePhrases = prefs.getBoolean(KEY_VOCAB_INCLUDE_PHRASES)
		val freqFilterEnabled = prefs.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)
		val slider = prefs.getInt(minFreqKey, 1).coerceIn(1, 14)
		val minFreqClass = if (freqFilterEnabled && slider > 1) (15 - slider) else null

		var anyFreqMask = 0L
		var minFreqMask = 0L

		if (includeJustType) {
			if (minFreqClass == null) {
				anyFreqMask = anyFreqMask or ClassMasks.CLASS_JUSTTYPE_MASK
			} else {
				minFreqMask = minFreqMask or ClassMasks.CLASS_JUSTTYPE_MASK
			}
		}
		if (includeCustom) {
			anyFreqMask = anyFreqMask or ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK
		}
		if (includePhrases) {
			anyFreqMask = anyFreqMask or ClassMasks.CLASS_PHRASES_MASK
		}
		val dynamicMask = prefs.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
		anyFreqMask = anyFreqMask or dynamicMask

		debugLog(
			DebugCategory.WordDb,
			"[buildVocabMaskConfig] minFreqKey=$minFreqKey includeJT=$includeJustType includeCustom=$includeCustom includePhrases=$includePhrases freqFilter=$freqFilterEnabled slider=$slider minFreqClass=$minFreqClass dynamicMask=${hexMask(dynamicMask)} anyMask=${hexMask(anyFreqMask)} minMask=${hexMask(minFreqMask)}",
		)

		return VocabMaskConfig(anyFreqMask, minFreqMask, minFreqClass)
	}

	private fun buildAccentMaskConfig(): AccentMaskConfig {
		val enabled = prefs.getBoolean(KEY_VOCAB_ACCENT_ENABLED)
		val rawMask = prefs.getLong(KEY_VOCAB_ACCENT_MODULE_MASK, 0L)
		val activeMask = buildActiveModuleMask()
		// Accent selection is based solely on module bits; don't strip custom/JT bits.
		val accentMask = if (enabled) rawMask and activeMask else 0L

		val minSliderRaw = prefs.getInt(KEY_VOCAB_ACCENT_MIN_FREQ, 15).coerceIn(1, 15)
		val freqFilterEnabled = prefs.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)
		val selectionSlider = prefs.getInt(KEY_VOCAB_MIN_FREQ_SELECTION, 1).coerceIn(1, 14)
		val minSlider = if (
			freqFilterEnabled &&
			selectionSlider > 1 &&
			(accentMask and ClassMasks.CLASS_JUSTTYPE_MASK) != 0L
		) {
			maxOf(minSliderRaw, selectionSlider)
		} else {
			minSliderRaw
		}
		val maxSlider = prefs.getInt(KEY_VOCAB_ACCENT_MAX_FREQ, 0).coerceIn(0, 14)
		val minFreqClass = if (minSlider >= 15) null else 15 - minSlider
		val maxFreqClass = if (maxSlider <= 0) null else 15 - maxSlider
		val maxUseCountImported = prefs.getInt(KEY_VOCAB_ACCENT_USECOUNT_MAX, 0)
			.coerceIn(0, 15)
			.takeIf { it > 0 }
			?.let { (it - 1).coerceAtLeast(0) }
		val maxUseCountSystem = prefs.getInt(KEY_VOCAB_ACCENT_USECOUNT_MAX_JT, 0)
			.coerceIn(0, 15)
			.takeIf { it > 0 }
			?.let { (it - 1).coerceAtLeast(0) }

		debugLog(
			DebugCategory.WordDb,
			"[accentHints] config enabled=$enabled rawMask=${hexMask(rawMask)} activeMask=${hexMask(activeMask)} accentMask=${hexMask(accentMask)} minFreqClass=$minFreqClass maxFreqClass=$maxFreqClass maxUseCountSystem=$maxUseCountSystem maxUseCountImported=$maxUseCountImported",
		)
		return AccentMaskConfig(accentMask, minFreqClass, maxFreqClass, maxUseCountSystem, maxUseCountImported)
	}

	private fun computeNextLetterHints(): Set<Char> {
		if (!showNextLetterHints) return emptySet()

		val selectedIdx = state.currentSelection
		val masks = buildNextLetterMaskConfig()

		if (selectedIdx != null) {
			val itemType = selectionList.getOrNull(selectedIdx)?.get("type") as? String
			if (itemType in listOf("X", "L", "E", "2", "B", "PH", "P")) {
				if (shouldShowRootHints()) {
					debugLog(
						DebugCategory.WordDb,
						"[rootHints] computeNextLetterHints selected path: ambigSeq=${state.ambiguousKeySequence.size} keyHistory=${state.keyHistory.size} selectCount=${state.selectKeyCount}",
					)
					val maskKey = Triple(masks.anyFreqMask, masks.minFreqMask, masks.minFreqClass)
					return if (cachedRootHints != null && cachedRootHintsMaskKey == maskKey) {
						cachedRootHints!!
					} else {
						computeRootLetterHints(
							masks.anyFreqMask,
							masks.minFreqMask,
							masks.minFreqClass,
						).also {
							cachedRootHints = it
							cachedRootHintsMaskKey = maskKey
						}
					}
				}
				return emptySet()
			}
		}

		val keys = ambigKeySequenceNumbers()
		if (keys.isEmpty()) return emptySet()
		return try {
			val base = wld.getNextLettersForKeys(
				keys,
				masks.anyFreqMask,
				masks.minFreqMask,
				masks.minFreqClass,
			).map { it.uppercaseChar() }.toMutableSet()
			base
		} catch (_: Exception) {
			emptySet()
		}
	}

	/**
	 * Next-tone-mark prediction (tone-keystroke languages, mid-sequence only): the
	 * internal key numbers whose tone would complete a fully-lettered candidate.
	 * null = feature inactive (tone-less language, empty sequence, or hints off) —
	 * tone-mark labels render neutrally; non-null = applicable keys highlight,
	 * the rest gray out (an EMPTY set grays every tone mark: no toned completion).
	 * TAE-only: in TAV, cell 7 is the per-vowel tone-form display — every form
	 * shown is live by construction, so there is nothing to gray.
	 */
	private fun computeNextToneKeyHints(): Set<Int>? {
		if (!showNextLetterHints || postSelectionState()) return null
		if (activeLayoutSpec?.tones == null) return null
		if (toneAfterVowelActive()) return null
		val keys = ambigKeySequenceNumbers()
		if (keys.isEmpty()) return null
		val masks = buildNextLetterMaskConfig()
		return try {
			wld.getNextToneKeysForKeys(keys, masks.anyFreqMask, masks.minFreqMask, masks.minFreqClass)
		} catch (_: Exception) {
			null
		}
	}

	/**
	 * TAV per-vowel tone-form display (center column): tone keyNum → the previous
	 * keystroke's vowel(s) carrying that key's tone, shown ONLY when at least one
	 * candidate is live through that tone — an absent form IS the feedback (the
	 * user made a keystroke or spelling error). Independent of the next-letter
	 * hints toggle: this is the tone key's label in TAV, not a hint. Forms are
	 * cased like the selection list cases the carrier (CAPS → upper everywhere;
	 * shift/pending auto-cap → upper at word start only) and ordered by the
	 * vowels' order on the previous key's face.
	 */
	private fun computeTavToneFormLabels(): Map<Int, List<String>> {
		val keys = ambigKeySequenceNumbers()
		if (keys.isEmpty()) return emptyMap()
		val masks = buildNextLetterMaskConfig()
		val forms = try {
			wld.getNextToneFormsForKeys(keys, masks.anyFreqMask, masks.minFreqMask, masks.minFreqClass)
		} catch (_: Exception) {
			emptyMap()
		}
		if (forms.isEmpty()) return emptyMap()
		val prevLetters = (if (layoutMode == LayoutMode.Alphabetical) alphaLetters() else optimizedLetters())
			.getOrNull(keys.last())?.lowercase(Locale.getDefault()) ?: ""
		val fold = toneFoldForMode(layoutMode)
		val upper = isCapsActive() ||
			((state.isManualShift || state.shiftState || state.capitalizePending) && keys.size == 1)
		return forms.mapValues { (_, marked) ->
			marked.sortedBy { m -> fold[m]?.first?.let { prevLetters.indexOf(it) } ?: Int.MAX_VALUE }
				.map { m -> if (upper) upperWithLocale(m.toString()) else m.toString() }
		}
	}

	private fun computeAccentNextLetterHints(): Set<Char> {
		val config = buildAccentMaskConfig()
		if (config.accentMask == 0L) return emptySet()
		val keys = ambigKeySequenceNumbers()
		if (keys.isEmpty()) return emptySet()
		return wld.getNextLettersAccented(
			keys = keys,
			accentMask = config.accentMask,
			minFreqClass = config.minFreqClass,
			maxFreqClass = config.maxFreqClass,
			maxUseCountSystem = config.maxUseCountSystem,
			maxUseCountImported = config.maxUseCountImported,
		)
	}

	// Press ambiguous key number silently (by invoking the appropriate button index on the Main page)
	fun pressAmbiguousKeyNumberSilently(keyNum: Int, suppressThisTime: Boolean) {
		log(DebugCategory.AmbigBuffer, "[pressAmbiguousKeyNumberSilently 1] Pressing key $keyNum    UI suppressed = $suppressThisTime")
		// Map key number -> button index in Main page: 0->0, 1->2, 2->3, 3->4, 4->5, 5->7
		val idx = when (keyNum) {
			0 -> 0
			1 -> 2
			2 -> 3
			3 -> 4
			4 -> 5
			5 -> 7
			else -> return
		}
		val prev = suppressUi
		suppressUi = suppressThisTime
		try {
			buttonPressed(idx)
		} finally {
			suppressUi = prev
		}
	}

	fun pressSelectSilently(suppressThisTime: Boolean) {
		debugLog("[pressSelectSilently 1]  Called with suppressThisTime=$suppressThisTime")
		val prev = suppressUi
		suppressUi = suppressThisTime
		try {
			buttonPressed(6)
		} finally {
			suppressUi = prev
		}
	}

	fun getSelectionOutputs(): List<String> = selectionList.mapNotNull { it["output"] as? String }

	/**
	 * Pull-in reconstruction target (Cliff's page-group rule, 2026-08-14).
	 * The reconstructed state must make the pulled word SELECT-reachable —
	 * "add enough Selects to make it the currently selected word" — but a
	 * word that resolves INSIDE a page group cannot be reached by Selects
	 * (stepping past the list rows opens and advances pages instead, and a
	 * page cell is only reachable by an unambiguous letter-key pick, which
	 * would defeat re-creating the actively-being-typed state). Such a word
	 * is RELOCATED to list slot 2: the minimal honest distortion that
	 * restores the old invariant, mirroring the family-expansion insertion.
	 * Slot 1 stays untouched so rule (1) — a naturally-top word gets no
	 * Select, keeping the keystroke buffer clean for editing — is preserved.
	 * Returns the word's (possibly relocated) flat index, or -1.
	 */
	fun pullInTargetIndex(word: String): Int {
		val idx = selectionList.indexOfFirst {
			(it["output"] as? String)?.equals(word, ignoreCase = true) == true
		}
		if (idx < 0) return idx
		if (!pagedSelectionEnabled() || idx < pagedFirstRow()) return idx
		// PIN the relocation: the replay's search can complete asynchronously
		// AFTER this flow, and its rebuild would restore the natural order —
		// silently un-selecting the pulled word. The pin re-applies the
		// relocation on every rebuild while THIS reconstructed sequence
		// stands (sequence-length keyed: any edit retires it naturally).
		pullInPinnedWord = word
		pullInPinnedSeqLen = state.ambiguousKeySequence.size
		return applyPullInPin() ?: idx
	}

	// Pull-in page-group relocation pin (see pullInTargetIndex).
	private var pullInPinnedWord: String? = null
	private var pullInPinnedSeqLen: Int = -1

	/** Relocates the pinned word to list slot 2 on the CURRENT list.
	 *  Returns its index, or null when the pin does not apply. */
	private fun applyPullInPin(): Int? {
		val word = pullInPinnedWord ?: return null
		if (state.ambiguousKeySequence.size != pullInPinnedSeqLen) return null
		val idx = selectionList.indexOfFirst {
			(it["output"] as? String)?.equals(word, ignoreCase = true) == true
		}
		if (idx < 0) return null
		if (!pagedSelectionEnabled() || idx < pagedFirstRow()) return idx
		val mutable = selectionList.toMutableList()
		val entry = mutable.removeAt(idx)
		val slot = 1.coerceAtMost(mutable.size)
		mutable.add(slot, entry)
		selectionList = mutable
		debugLog("[applyPullInPin] '$word' relocated from page-buried index $idx to list slot ${slot + 1}")
		return slot
	}

	fun getCurrentSelectionIndex(): Int? = state.currentSelection

	fun forceUpdateUi(suppressTopCandidate: Boolean = false, forceSelectedCandidate: String? = null, forceTopCandidate: String? = null) {
		debugLog("[forceUpdateUi 1]  Called with suppressUi=$suppressUi    suppressTopCandidate=$suppressTopCandidate  forceSelectedCandidate=$forceSelectedCandidate  forceTopCandidate=$forceTopCandidate")
		val saveSuppressUi = suppressUi
		suppressUi = false
		updateUi(suppressTopCandidate, forceSelectedCandidate, forceTopCandidate)
		suppressUi = saveSuppressUi
	}

	fun debugShowAmbiguousSequence(callFrom: String) {
		log(DebugCategory.AmbigBuffer, "[$callFrom] Current Ambiguous Sequence: undoStack.size=${undoStack.size}, ambigSeqLen=${state.ambiguousKeySequence.size}, ambigKeys=${state.ambiguousKeySequence.map { it.display }}")
	}

	fun getAmbiguousSequenceLength(): Int = state.ambiguousKeySequence.size

	/** Internal key numbers of the active ambiguous sequence (for resume snapshots). */
	fun getAmbiguousKeyNumbers(): List<Int> = ambigKeySequenceNumbers()

	/** True when the user has stepped the selection list (Select activations). */
	fun hasActiveSelection(): Boolean = state.currentSelection != null

	fun selectedCandidateSuppressesLeadingSpace(): Boolean {
		val idx = state.currentSelection
		val candidate = if (idx != null) selectionList.getOrNull(idx) else selectionList.firstOrNull()
		return candidate?.get("suppressLeadingSpace") == true
	}

	fun isKnownAbbreviation(token: String): Boolean = wld.isKnownAbbreviation(token)
	fun isKnownDomain(token: String): Boolean = wld.isKnownDomain(token)

	fun isWordProducible(word: String): Boolean {
		val keys = mapWordToKeyIndices(word) ?: return false
		val masks = buildVocabMaskConfig(Constants.KEY_VOCAB_MIN_FREQ_SELECTION)
		val bsd = prefs.getInt(DeveloperSettingsActivity.KEY_SEARCH_BSD, 8).coerceIn(3, 10)
		val sed = prefs.getInt(DeveloperSettingsActivity.KEY_SEARCH_SED, 7).coerceIn(0, 10)
		val mqc = prefs.getInt(DeveloperSettingsActivity.KEY_SEARCH_MQC, 100).coerceIn(5, 1000)
		val ignoreMen = prefs.getBoolean(DeveloperSettingsActivity.KEY_SEARCH_IGNORE_MEN, false)
		val men = if (ignoreMen) {
			Int.MAX_VALUE
		} else {
			prefs.getInt(DeveloperSettingsActivity.KEY_SEARCH_MEN, 5000).coerceIn(50, 10000)
		}
		val result = wld.getDisambiguationCandidates(
			keys,
			maxWordCompleteEntries = mqc,
			minFreqClass = masks.minFreqClass,
			includeExcludedAtEnd = false,
			anyFreqMask = masks.anyFreqMask,
			minFreqMask = masks.minFreqMask,
			baseSearchDepth = bsd,
			searchExpansionDepth = sed,
			maxExaminedNodes = men,
		)
		val wordLower = word.lowercase(Locale.getDefault())
		val exactCase = prefs.getBoolean(Constants.KEY_EXACT_CASE_PULL_IN)
		return result.candidates.any { candidate ->
			if (candidate.lowerWord != wordLower) return@any false
			if (!exactCase) return@any true
			// First letter may differ in case (shift/caps); remaining letters must match exactly
			if (word.length <= 1) return@any true
			val wordTail = word.substring(1)
			val candidateTail = candidate.lowerWord.substring(1)
			wordTail == candidateTail
		}
	}

	fun isWordDbChar(char: Char): Boolean = wld.isWordDbChar(char)

	fun hasPendingAmbiguityWithoutSelect(): Boolean = state.ambiguousKeySequence.isNotEmpty() && state.currentSelection == null

	fun getSelectKeyCount(): Int = state.selectKeyCount

	/**
	 * Returns the button index (0-7) of the key with KF_Select on the starting page,
	 * or -1 if not found. Used by JustTypeIME to dynamically identify the Select key
	 * for abort-logic rather than hardcoding a button index.
	 */
	fun getSelectKeyIndex(): Int {
		val startingPageKeys = pages[StartingPage] ?: return -1
		return startingPageKeys.indexOfFirst { key ->
			key.functions.any { it.first == KF_Select }
		}
	}

	// Selection entries encode their origin via "type":
	// "X" = exact dictionary match, "L" = longer completion suggestion,
	// "E" = reserved/legacy word-like entries (treated the same as X/L),
	// "2" = custom-word placeholder surfaced by spelling/custom-word flows.
	private fun recordWordUsageForSelection(index: Int) {
		// Every commit-from-list path funnels through here — close the
		// select-behavior episode first (its own type handling includes PH/B,
		// which the usage gate below excludes).
		selEpisodeCommit(index)
		// Type N included (Cliff, 2026-08-10): accepting an NGB prediction MUST
		// update useCount and recency — the old {X,L,E,2} gate left habitually
		// accepted predictions ranking as never-used in the trie sort.
		val wordTypes = setOf("X", "L", "E", "2", "N")
		val sel = selectionList.getOrNull(index) ?: return
		val type = sel["type"] as? String ?: return
		if (type !in wordTypes) return
		val outputWord = sel["output"] as? String ?: return
		val canonical = (sel["canonicalOutput"] as? String) ?: outputWord
		if (type == "N" && outputWord.contains(' ')) {
			// Multi-word prediction (VN unit; EN collocations later): usage +
			// recency accrue to every component word (per-syllable, per plan).
			// Canonical display governs the casing and the unit is not a trie
			// word, so per-component case and freq-class tracking do not apply.
			try {
				synchronized(vocabLock) {
					outputWord.split(' ').filter { it.isNotEmpty() }.forEach { wld.markWordUsed(it) }
				}
			} catch (_: Exception) {
			}
			return
		}
		val appliedCaseForm = sel["appliedCaseForm"] as? WordCaseForm
		val caseSource = sel["caseSource"] as? CaseSource
		val shouldTrackCase = when {
			appliedCaseForm == null -> false
			caseSource == CaseSource.AUTO_SENTENCE -> false
			appliedCaseForm == WordCaseForm.UPPER && caseSource == CaseSource.CAPS_LOCK -> false
			else -> true
		}
		debugLog(
			DebugCategory.ShiftState,
			"[recordWordUsage] word='$canonical', appliedForm=$appliedCaseForm, source=$caseSource, track=$shouldTrackCase",
		)
		// Battery Saver skips case/frequency-decay adaptation (extra writes on every
		// selection) but always keeps markWordUsed — losing all usage tracking would
		// degrade ranking permanently, not just while the mode is on.
		val batterySaverOn = prefs.getBoolean(Constants.KEY_BATTERY_SAVER_MODE, false)
		try {
			// Under vocabLock: these DB writes must not run against a wld/DB being swapped or closed by
			// a concurrent init()/reload (same lock every mutation path takes).
			synchronized(vocabLock) {
				val firstWordIndex = selectionList.indexOfFirst { (it["type"] as? String) in wordTypes }
				org.continuouspath.justtype.utils.PerfTrace.measure("wld.recordSelectionUsage (main-thread DB write)") {
					wld.inUsageTransaction(outputWord) {
						wld.markWordUsed(outputWord)
						if (shouldTrackCase && !batterySaverOn) {
							val margin = prefs.getInt(
								DeveloperSettingsActivity.KEY_CASE_SWITCH_MARGIN,
								2,
							).coerceIn(1, 8)
							wld.incrementCaseCount(canonical, appliedCaseForm!!, margin)
						}
						if (firstWordIndex != -1 && index != firstWordIndex && !batterySaverOn) {
							wld.decrementFreqClass(outputWord, 2)
						}
					}
				}
			}
		} catch (_: Exception) {
		}

		val posStr = sel["POS"] as? String ?: ""
		if (posStr.contains("ABPS")) {
			state.capitalizePending = true
			state.pendingAutoCapReason = AutoCapReason.ABBREVIATION
			state.isManualShift = false
		} else {
			state.capitalizePending = false
			state.pendingAutoCapReason = AutoCapReason.NONE
			state.isManualShift = false
		}
	}

	fun resetJTUI(
		capitalize: Boolean,
		callUpdateUi: Boolean = true,
		isManualShift: Boolean = false,
		resetToStartPage: Boolean = false,
		autoCapReason: AutoCapReason = AutoCapReason.NONE,
		// When true, finalize the composing preview but leave any leading
		// autospace alone. Used by UnDo's Context 7 (deleting the first
		// keystroke of a new word) so the autospace belonging to the
		// PRIOR word survives — the next UnDo press deletes it. Default
		// false preserves the historical behavior (remove autospace).
		preserveAutospace: Boolean = false,
	) {
		debugLog("[resetJTUI 1]  Called with callUpdateUi=$callUpdateUi     resetToStartPage=$resetToStartPage     preserveAutospace=$preserveAutospace")

		// Clear all state — including undo stack, since old states are meaningless after reset
		// (During pull-in, resetJTUI is called before key replay, so the stack gets rebuilt)
		undoStack.clear()
		// A reset detaches us from whatever the editor held — the armed
		// re-seal (if any) and the pull-in relocation pin refer to a state
		// that no longer exists. (The pull-in replay resets FIRST and pins
		// after, so a fresh pin always survives its own flow.)
		pagedPickFinalizedWord = null
		pullInPinnedWord = null
		pullInPinnedSeqLen = -1
		// NGB fail-soft: a reset means the context may no longer reflect what
		// precedes the cursor (wake, field change, pull-in) — never guess.
		ngbClearContext()
		if (callUpdateUi || resetToStartPage) {
			log(DebugCategory.Lifecycle, "[resetJTUI 2] resetting current page from ${getCurrentPage()}  to  $StartingPage")
			setCurrentPageToStartingPage()
		}
		state.currentSelection = null
		state.keyHistory.clear()
		state.systemSelectionList.clear()
		state.ambiguousKeySequence.clear()
		state.selectKeyCount = 0
		state.outputString = ""
		state.immedCharString = ""
		state.numericString = ""
		// onNumericOutput("") commits composing AND removes any leading
		// autospace via removeLeadingAutospaceIfPresent. onFinalizeText("")
		// does the same compose-commit + autoSpace-flag-reset, but does
		// NOT remove the autospace text from the field. See the
		// preserveAutospace doc above.
		if (preserveAutospace) {
			onFinalizeText("")
		} else {
			onNumericOutput("")
		}
		state.shiftState = capitalize
		state.isManualShift = isManualShift
		state.capsTempDisable = false
		state.autoCapReason = if (capitalize) autoCapReason else AutoCapReason.NONE
		state.capsState = false
		state.speakState = true
		state.capitalizePending = false
		state.pendingAutoCapReason = AutoCapReason.NONE

		// Send UI update with cleared state
		// Note: We only call updateUi, not the manual onUiUpdate + forceUpdateUi
		if (callUpdateUi) {
			// Clear selection list (important: do this before updateUi)
			updateSelectionList(listOf(emptyList()), null)

			// Pass suppressTopCandidate=true to ensure null candidates
			forceUpdateUi(suppressTopCandidate = true)
			debugLog("[resetJTUI 2]  UI updated with cleared state")
		}
	}

	private fun debugLog(message: String) {
		debugLog(inferCategoryForMessage(message), message)
	}

	/** NGB context lifecycle trace — logcat-only, debug builds, for
	 *  device-side dead-zone forensics (adb logcat -s NGB_TRACE). */
	private fun ngbTrace(message: () -> String) {
		if (BuildConfig.DEBUG_EDITING) {
			val chain = Thread.currentThread().stackTrace
				.drop(3).take(6)
				.joinToString("<") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
			android.util.Log.d("NGB_TRACE", "[$chain] ${message()}")
		}
	}

	private fun debugLog(category: DebugCategory, message: String) {
		if (!BuildConfig.DEBUG_EDITING) return
		try {
			val methodName = Thread.currentThread().stackTrace.getOrNull(4)?.methodName ?: "unknown"
			DebugLogger.log(category) { "[JTUI.$methodName] $message" }
		} catch (_: Exception) {}
	}

	private fun inferCategoryForMessage(message: String): DebugCategory {
		val normalized = message.lowercase(Locale.getDefault())
		return when {
			normalized.contains("undo") -> DebugCategory.UndoFlow
			normalized.contains("shift") || normalized.contains("auto-cap") || normalized.contains("caps") -> DebugCategory.ShiftState
			normalized.contains("selection") || normalized.contains("cursor") -> DebugCategory.SelectionSync
			normalized.contains("worddb") || normalized.contains("casecount") -> DebugCategory.WordDb
			else -> DebugCategory.AmbigBuffer
		}
	}

	private fun hexMask(mask: Long): String = "0x" + mask.toString(16).uppercase(Locale.getDefault())
}

private fun String.rstripSpaces(): String = this.replace(Regex("\\s+$"), "")
