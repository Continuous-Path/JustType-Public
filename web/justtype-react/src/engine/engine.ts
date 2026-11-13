/**
 * The JustType typing engine — a web port of the core of the app's `JTUI`: eight keys,
 * ambiguous key sequences resolved through {@link Wld}, Select / UnDo, paged word selection,
 * phrases, and the symbol, number, function, spelling and edit pages. It owns the text
 * buffer and has no DOM dependency.
 */
import { SCAN_ORDER_ALPHA, SCAN_ORDER_OPTIMIZED } from "../input/scan";
import { AllSymbolsController, CELLS_PER_PAGE, type SymbolData } from "./allSymbols";
import { type Direction, MoveMode, TextBuffer } from "./buffer";
import { type KeyLayout, type LayoutMode, type LayoutSpec, cellChars, parseLayout } from "./layout";
import { BLOCK_SIZE, NgramModel, type Prediction, ZERO_K_SIZE, contextFor } from "./ngram";
import { COLD_FTS_FLOOR, FREQ_SLOPE, assemble, sortMetric } from "./ranking";
import { type EngineStore, type PersistedState, type Phrase, newRow, normalizeState } from "./store";
import { applyShift, atSentenceStart, capitalize, caseForms, isQuoteClosed, lastSentence, needsSpace } from "./text";
import { type CaseType, DEFAULT_SEARCH, Wld, type WordEntry, computeFreqClass } from "./wld";

/** Key slots in reading order: NW, N, NE, W, E, SW, S, SE. */
export const SLOT_COUNT = 8;
export const UNDO_SLOT = 1;
export const SELECT_SLOT = 6;

/** Slots of ambiguous keys 0..5 (also used for six-way pick pages). */
const LETTER_SLOTS = [0, 2, 3, 4, 5, 7];
/** 9-cell key-face index -> slot (-1 = the centre cell, which has no slot). */
const CELL_TO_SLOT = [0, 1, 2, 3, -1, 4, 5, 6, 7];
/** Key-face cells that preview a six-way pick page. */
const PREVIEW_CELLS = [0, 2, 3, 5, 6, 8];
/** Scales a bigram count onto the unigram frequency scale for block ordering. */
const NGRAM_BLOCK_SCALE = 19;
const TOP_BAND = 40_000;
const USER_WORD_FREQ = 2500;

/** Paged selection: keyNum -> position on the page (column-major on the grid). */
const PAGED_ORDINAL_GRID = [0, 3, 1, 4, 2, 5];
const PAGED_WORDS_PER_PAGE = 6;
/** Paging only starts when at least this many rows would fill the first page. */
const PAGED_MIN_FIRST_PAGE = 4;

const FAMILY_MAX_CANDIDATES = 60;
const FAMILY_MIN_KEYS_REMAINING = 5;
const FAMILY_STEM_BACKOFF = 5;

const PHRASE_SNIPPET_LENGTH = 60;
const WORD_START = /^[\p{L}\p{N}]/u;
const WORD_END = /[\p{L}\p{N}'’-]$/u;

const MOVE_LABELS = ["CHAR /\nLINE\n⟳", "WORD /\nSENTENCE\n⟳", "PARA /\nALL\n⟳", "BOOKMARK\n⟳"];
const MOVE_CENTER = [
	"CURSOR MODE:\nCHARACTER / LINE",
	"CURSOR MODE:\nWORD / SENTENCE",
	"CURSOR MODE:\nPARAGRAPH / ALL",
	"BOOKMARK MODE",
];
const BOOKMARK_MODE = 3;

export interface LanguageData {
	format: number;
	language: string;
	layout: unknown;
	/** Newline-separated `surface<TAB>rawFreq<TAB>caseType[<TAB>level]`. */
	words: string;
	/** Base letter -> its diacritic variants (lowercase). */
	diacritics?: Record<string, string>;
}

export interface NgramData {
	format: number;
	language: string;
	rows: string;
}

export type ExcludedWords = "none" | "offensive" | "potentiallyOffensive";
export type WordSelection = "paged" | "list";
export type PhraseFlowPhase = "phrase" | "abbreviation";

export interface EngineEvents {
	/** Text the engine wants spoken (a selected word, a finished sentence, or on request). */
	onSpeak?(text: string): void;
	/** A key press that could not be applied. */
	onError?(): void;
	/** Text was cut or copied; mirror it to the system clipboard. */
	onCopy?(text: string): void;
	/** PAGE UP / PAGE DOWN: scroll the message view. */
	onScroll?(direction: "up" | "down"): void;
}

export interface EngineOptions {
	data: LanguageData;
	ngrams?: NgramData | null;
	/** The ALL SYMBOLS MODE tree (`data/symbols.json`); without it that key is blank. */
	symbols?: SymbolData | null;
	store?: EngineStore;
	events?: EngineEvents;
	layoutMode?: LayoutMode;
	excludedWords?: ExcludedWords;
	autoCapitalize?: boolean;
	/** Speak each selected word and each finished sentence. */
	speech?: boolean;
	/** `paged` (default): after the listed words, Select pages the rest onto the letter keys. */
	wordSelection?: WordSelection;
	/** Rows stepped through one by one before paging starts (0–3, default 2). */
	pagedListedWords?: number;
	/** Mark which key-face letters some word continues with. */
	nextLetterHints?: boolean;
	/** Pausing on a long selected word offers words sharing its stem. */
	familyExpand?: boolean;
	familyExpandDelayMs?: number;
	/** UnDo at the end of a word (and Select on an empty list) reloads it for editing. */
	pullIn?: boolean;
	/** Let accented words be typed with their base letters, at a lower rank. */
	accentFallback?: boolean;
	initialText?: string;
	/** Clock in epoch milliseconds (injectable for tests). */
	now?: () => number;
}

export type ListItem =
	| {
			type: "word";
			display: string;
			output: string;
			keysRemaining: number;
			/** From the next-word model rather than the key sequence alone. */
			predicted: boolean;
			/** Inserted by family expansion. */
			family?: boolean;
			/** Pausing on this row would expand its word family. */
			expandable?: boolean;
	  }
	| { type: "phrase"; display: string; output: string }
	| { type: "page"; display: string; page: string }
	/** An inert filler that keeps a family group on whole pages. */
	| { type: "pad"; display: "" };

export interface KeyFace {
	kind: "letters" | "function" | "word" | "blank";
	/** `letters`: the 9-cell key face. */
	grid?: string[];
	/** `function` / `word`: the label (may contain line breaks). */
	label?: string;
	/** `letters`: the list function reached by this key + Select. */
	badge?: string;
	/** Toggle keys: whether the toggle is on. */
	active?: boolean;
	/** `letters`, with next-letter hints on: per cell, whether a word continues with it. */
	hints?: boolean[];
}

export interface Snapshot {
	page: string;
	keys: KeyFace[];
	list: ListItem[];
	selection: number | null;
	/** Paged selection: the open page, or null. `pagedRows` is its [first, end) list range. */
	pagedPage: number | null;
	pagedRows: [number, number] | null;
	/** The whole message; `before` / `after` split it at the cursor. */
	text: string;
	before: string;
	after: string;
	cursor: number;
	/** The selected text range as [start, end), or null. */
	textSelection: [number, number] | null;
	/** The word being composed at the cursor (not yet committed). */
	composing: string;
	/** `text` with `composing` spliced in at the cursor, spaced as it will be committed. */
	displayText: string;
	keySequence: number[];
	shift: boolean;
	capsLock: boolean;
	speech: boolean;
	layoutMode: LayoutMode;
	/** Mode text for the centre of the grid (edit mode, ALL SYMBOLS), or "". */
	centerText: string;
	/** Set while a new phrase or its abbreviation is being entered. */
	phraseFlow: { phase: PhraseFlowPhase; phrase: string } | null;
}

interface KeyDef extends KeyFace {
	run?: () => void;
}

interface InternalItem {
	item: ListItem;
	entry: WordEntry | null;
}

interface UndoSnapshot {
	seq: number[];
	selection: number | null;
	paged: number | null;
	capPending: boolean;
	shift: boolean;
	autoShift: boolean;
}

type SpellMode = "word" | "letters" | "abbreviation" | "edit";
type SymOptions = { spaceBefore?: boolean; endSentence?: boolean; label?: string; stay?: boolean };

const BLANK: KeyDef = { kind: "blank" };

function isOutput(item: ListItem): item is Extract<ListItem, { output: string }> {
	return item.type === "word" || item.type === "phrase";
}

export class JustTypeEngine {
	private readonly spec: LayoutSpec;
	private readonly entries: WordEntry[] = [];
	private readonly tries = new Map<LayoutMode, Wld>();
	private readonly diacritics: Record<string, string>;
	private readonly store?: EngineStore;
	private readonly persisted: PersistedState;
	private readonly now: () => number;
	private readonly autoCapitalize: boolean;
	private readonly accentFallback: boolean;
	private readonly pullInEnabled: boolean;
	private readonly listedWords: number;
	private readonly familyDelayMs: number;
	private ngrams: NgramModel | null;
	private symbols: SymbolData | null;
	private events: EngineEvents;
	private excluded: ExcludedWords;
	private layoutMode: LayoutMode;
	private wordSelection: WordSelection;
	private hints: boolean;
	private familyEnabled: boolean;
	private scanOrder = false;

	private readonly buf: TextBuffer;
	private page = "Main";
	/** Where the symbol / number / spelling pages return to. */
	private caller: "Main" | "LetterSymbol" = "Main";
	private seq: number[] = [];
	private selection: number | null = null;
	private paged: number | null = null;
	private list: InternalItem[] = [];
	private listKey: string | null = null;
	/** Bumped whenever something the list depends on changes outside the key state. */
	private version = 0;
	private undo: UndoSnapshot[] = [];
	private shift = false;
	/** `shift` was set by auto-capitalisation rather than by the user. */
	private autoShift = false;
	private capsLock = false;
	/** The shift state captured when the current key sequence began. */
	private capPending = false;
	private speech: boolean;
	private lastCommitted = "";
	private speakIndex = 0;

	private spell = "";
	private spellMode: SpellMode = "word";
	private spellReturn = "Main";
	private spellNeedsSpace = false;
	private numericFresh = false;
	private allSymbols: AllSymbolsController | null = null;
	private flow: { phase: PhraseFlowPhase; phrase: string; saved: { text: string; cursor: number } } | null = null;
	private phraseIndex: Map<string, Phrase[]> | null = null;

	private selecting = false;
	private moveMode = 0;
	private marks: { a: number | null; b: number | null } = { a: null, b: null };
	private clipboard = "";

	private familyTimer: ReturnType<typeof setTimeout> | null = null;
	/** While a family group is inserted, the listed (non-paged) region ends here. */
	private familyListedRows: number | null = null;

	private snapshot!: Snapshot;
	private readonly listeners = new Set<() => void>();

	constructor(options: EngineOptions) {
		this.spec = parseLayout(options.data.layout);
		this.diacritics = options.data.diacritics ?? {};
		this.ngrams = options.ngrams ? new NgramModel(options.ngrams.rows) : null;
		this.symbols = options.symbols ?? null;
		this.store = options.store;
		this.persisted = normalizeState(options.store?.load() ?? null);
		this.events = options.events ?? {};
		this.now = options.now ?? Date.now;
		this.autoCapitalize = options.autoCapitalize ?? true;
		this.accentFallback = options.accentFallback ?? true;
		this.pullInEnabled = options.pullIn ?? true;
		this.excluded = options.excludedWords ?? "offensive";
		this.layoutMode = options.layoutMode === "alpha" && this.spec.alpha ? "alpha" : "optimized";
		this.wordSelection = options.wordSelection ?? "paged";
		this.listedWords = Math.max(0, Math.min(options.pagedListedWords ?? 2, 3));
		this.hints = options.nextLetterHints ?? false;
		this.familyEnabled = options.familyExpand ?? false;
		this.familyDelayMs = options.familyExpandDelayMs ?? 1500;
		this.speech = options.speech ?? false;
		this.buf = new TextBuffer(options.initialText ?? "");

		for (const line of options.data.words.split("\n")) {
			const [surface, freq, caseType, level] = line.split("\t");
			if (surface) this.addEntry(surface, Number(freq), (caseType || "LO") as CaseType, Number(level ?? 0), false);
		}
		for (const word of this.persisted.userWords) {
			this.addEntry(word, USER_WORD_FREQ, word === word.toLowerCase() ? "LO" : "OC", 0, true);
		}
		this.afterTextEdit();
		this.refresh();
	}

	// ── Public API ──

	subscribe = (listener: () => void): (() => void) => {
		this.listeners.add(listener);
		return () => this.listeners.delete(listener);
	};

	getSnapshot = (): Snapshot => this.snapshot;

	/** Presses the key in `slot` (0..7: NW, N, NE, W, E, SW, S, SE). */
	press(slot: number): void {
		this.cancelFamilyTimer();
		this.ensureList();
		const key = this.pageKeys()[slot];
		if (!key?.run) {
			this.events.onError?.();
			return;
		}
		key.run();
		this.refresh();
	}

	/** Replaces the message, abandoning any word in progress. */
	setText(text: string, cursor = text.length): void {
		this.leaveSequence(false);
		this.buf.set(text, cursor);
		this.page = "Main";
		this.spell = "";
		this.afterTextEdit();
		this.refresh();
	}

	/** Moves the cursor, accepting a selected word first. */
	setCursor(index: number): void {
		this.leaveSequence(true);
		this.buf.moveTo(index);
		this.afterTextEdit();
		this.refresh();
	}

	/**
	 * Reloads the word at `index` into the key sequence, selected, so it can be changed
	 * with Select / UnDo. Returns false (leaving the cursor there) if no known word is at `index`.
	 */
	pullInAt(index: number): boolean {
		this.leaveSequence(true);
		this.buf.moveTo(index);
		this.afterTextEdit();
		const pulled = this.page === "Main" && this.tryPullIn();
		this.refresh();
		return pulled;
	}

	setLayoutMode(mode: LayoutMode): void {
		if (mode === this.layoutMode || (mode === "alpha" && !this.spec.alpha)) return;
		this.layoutMode = mode;
		this.phraseIndex = null;
		this.resetSequence();
		this.refresh();
	}

	/** Attaches (or removes) the next-word table; it can arrive after the keyboard is in use. */
	setNgrams(ngrams: NgramData | null | undefined): void {
		this.ngrams = ngrams ? new NgramModel(ngrams.rows) : null;
		this.version++;
		this.refresh();
	}

	setSymbols(symbols: SymbolData | null | undefined): void {
		this.symbols = symbols ?? null;
		this.refresh();
	}

	setSpeech(on: boolean): void {
		this.speech = on;
		this.refresh();
	}

	setExcludedWords(excluded: ExcludedWords): void {
		this.excluded = excluded;
		this.version++;
		this.refresh();
	}

	setWordSelection(mode: WordSelection): void {
		this.wordSelection = mode;
		this.paged = null;
		this.refresh();
	}

	setNextLetterHints(on: boolean): void {
		this.hints = on;
		this.refresh();
	}

	setFamilyExpand(on: boolean): void {
		this.familyEnabled = on;
		this.version++;
		this.refresh();
	}

	/** Paged cells follow the scan order, so the first key scanned holds the likeliest word. */
	setScanOrder(scanning: boolean): void {
		if (this.scanOrder === scanning) return;
		this.scanOrder = scanning;
		this.refresh();
	}

	setEvents(events: EngineEvents): void {
		this.events = events;
	}

	/** Sets what PASTE inserts (e.g. from the system clipboard). */
	setClipboard(text: string): void {
		this.clipboard = text;
	}

	/** The key sequence that types `word` on the active layout, or null. */
	keySequenceFor(word: string): number[] | null {
		return this.wld().translate(word);
	}

	getPhrases(): Phrase[] {
		return [...this.persisted.phrases];
	}

	/** Stores a phrase typed by `abbreviation`. Returns null if the abbreviation cannot be typed. */
	addPhrase(abbreviation: string, phrase: string): Phrase | null {
		const abbr = abbreviation.trim();
		if (!abbr || !phrase.trim() || !this.wld().translate(abbr)) return null;
		const entry: Phrase = {
			id: `${this.now().toString(36)}-${this.persisted.phrases.length}`,
			abbreviation: abbr,
			phrase,
		};
		this.persisted.phrases.push(entry);
		this.phrasesChanged();
		this.refresh();
		return entry;
	}

	removePhrase(id: string): void {
		this.persisted.phrases = this.persisted.phrases.filter((p) => p.id !== id);
		this.phrasesChanged();
		this.refresh();
	}

	/** Runs a pending family expansion now (the pause timer calls this). */
	expandFamilyNow(): boolean {
		this.cancelFamilyTimer();
		const expanded = this.selection !== null && this.expandFamily(this.selection);
		if (expanded) this.refresh();
		return expanded;
	}

	private phrasesChanged(): void {
		this.phraseIndex = null;
		this.version++;
		this.store?.save(this.persisted);
	}

	// ── Vocabulary ──

	private addEntry(surface: string, rawFreq: number, caseType: CaseType, level: number, user: boolean): void {
		const [useCount, lastUse] = this.persisted.usage[surface] ?? [0, 0];
		const entry: WordEntry = {
			id: this.entries.length,
			lower: surface.toLowerCase(),
			surface,
			caseType,
			rawFreq,
			freqClass: computeFreqClass(rawFreq),
			useCount,
			lastUse,
			level,
			user,
		};
		this.entries.push(entry);
		for (const trie of this.tries.values()) trie.add(entry);
	}

	private keyLayout(): KeyLayout {
		return this.layoutMode === "alpha" && this.spec.alpha ? this.spec.alpha : this.spec.optimized;
	}

	private wld(): Wld {
		let trie = this.tries.get(this.layoutMode);
		if (!trie) {
			trie = new Wld(this.keyLayout().lettersPerKey, {
				variantsByBase: this.diacritics,
				accentFallback: this.accentFallback,
			});
			for (const entry of this.entries) trie.add(entry);
			this.tries.set(this.layoutMode, trie);
		}
		return trie;
	}

	private include = (entry: WordEntry): boolean => {
		if (entry.level === 0 || this.excluded === "none") return true;
		return this.excluded === "offensive" && entry.level === 2;
	};

	/** The list function (if any) opened by keyNum `k` + Select. */
	private listFunction(k: number): { page: string; badge: string; display: string } | undefined {
		const keys = this.spec.functionKeys;
		if (k === keys.symbols) return { page: "Symbols1", badge: "SYM", display: "SYMBOLS" };
		if (k === keys.functions) {
			return { page: this.flow ? "Functions0" : "Functions1", badge: "FNS", display: "FUNCTIONS" };
		}
		if (k === keys.navigation) return { page: "Menu", badge: "MENU", display: "MENU" };
		return undefined;
	}

	// ── Selection list ──

	private nowSec(): number {
		return Math.floor(this.now() / 1000);
	}

	private wordItems(entry: WordEntry, keysRemaining: number, predicted: boolean, shift: boolean): InternalItem[] {
		const outputs = caseForms(entry).map((form) => applyShift(form, shift, this.capsLock));
		return [...new Set(outputs)].map((output) => ({
			item: { type: "word", display: output, output, keysRemaining, predicted },
			entry,
		}));
	}

	private pool(): Prediction[] {
		return this.ngrams?.poolFor(contextFor(this.buf.before), this.persisted.userBigrams) ?? [];
	}

	/** Predictions offered before any key is typed. */
	private zeroKeyItems(shift: boolean): InternalItem[] {
		const out: InternalItem[] = [];
		for (const p of this.pool()) {
			if (p.deep || out.length >= ZERO_K_SIZE) break;
			const entry = this.wld().find(p.word);
			if (entry && this.include(entry)) out.push(this.wordItems(entry, 0, true, shift)[0]);
		}
		return out;
	}

	/** Predictions that still match the typed keys, ordered by the shared sort metric. */
	private predictionBlock(shift: boolean): InternalItem[] {
		const wld = this.wld();
		const typed = this.seq.length;
		const now = this.nowSec();
		const alive: { m: number; entry: WordEntry; keysRemaining: number }[] = [];
		for (const p of this.pool()) {
			const entry = wld.find(p.word);
			const keys = wld.translate(p.word);
			if (!entry || !keys || !this.include(entry)) continue;
			if (keys.length < typed || this.seq.some((k, i) => keys[i] !== k)) continue;
			const keysRemaining = keys.length - typed;
			if (p.deep && keysRemaining > 0) continue;
			const source = Math.max(p.eff * NGRAM_BLOCK_SCALE, entry.rawFreq);
			const topBand = source > TOP_BAND;
			const freqClass = topBand ? 1 : computeFreqClass(source);
			let m = sortMetric({ freqClass, useCount: entry.useCount, lastUse: entry.lastUse, keysRemaining }, now);
			// Past the top band the banded metric saturates; extend it continuously.
			if (topBand) m -= FREQ_SLOPE * Math.log2(source / TOP_BAND);
			// The cold-start floor is for words frequent in their own right, not by context.
			if (freqClass === 1 && keysRemaining === 0 && entry.useCount === 0 && entry.rawFreq <= TOP_BAND) {
				m += COLD_FTS_FLOOR;
			}
			alive.push({ m, entry, keysRemaining });
		}
		return alive
			.sort((a, b) => a.m - b.m)
			.slice(0, BLOCK_SIZE)
			.map((a) => this.wordItems(a.entry, a.keysRemaining, true, shift)[0]);
	}

	/** Stored phrases whose abbreviation is exactly the typed keys. */
	private phraseItems(): InternalItem[] {
		if (!this.phraseIndex) {
			this.phraseIndex = new Map();
			for (const phrase of this.persisted.phrases) {
				const keys = this.wld().translate(phrase.abbreviation)?.join("");
				if (!keys) continue;
				const row = this.phraseIndex.get(keys) ?? [];
				row.push(phrase);
				this.phraseIndex.set(keys, row);
			}
		}
		return (this.phraseIndex.get(this.seq.join("")) ?? []).map((p) => {
			const flat = p.phrase.replace(/\n/g, " ");
			const snippet = flat.length > PHRASE_SNIPPET_LENGTH ? `${flat.slice(0, PHRASE_SNIPPET_LENGTH)}...` : flat;
			return { item: { type: "phrase", display: `${p.abbreviation} - ${snippet}`, output: p.phrase }, entry: null };
		});
	}

	private sequenceItems(shift: boolean): InternalItem[] {
		const candidates = this.wld().candidates(this.seq, { ...DEFAULT_SEARCH, include: this.include });
		const words = assemble(candidates, this.nowSec()).flatMap((c) =>
			this.wordItems(c.entry, c.keysRemaining, false, shift),
		);
		const block = this.predictionBlock(shift);
		const blockOutputs = new Set(block.map((b) => (isOutput(b.item) ? b.item.output : "")));
		return [...block, ...this.phraseItems(), ...words.filter((w) => !isOutput(w.item) || !blockOutputs.has(w.item.output))];
	}

	private buildList(): InternalItem[] {
		if (this.page !== "Main") return [];
		if (this.seq.length === 0) return this.zeroKeyItems(this.shift);
		const words = this.sequenceItems(this.capPending);
		const fn = this.seq.length === 1 ? this.listFunction(this.seq[0]) : undefined;
		return fn ? [{ item: { type: "page", display: fn.display, page: fn.page }, entry: null }, ...words] : words;
	}

	/** Rebuilds the list only when what it depends on has changed, so inserted groups survive. */
	private ensureList(): void {
		const key = [
			this.page,
			this.seq.join(""),
			this.capPending,
			this.shift,
			this.capsLock,
			this.layoutMode,
			this.version,
			contextFor(this.buf.before),
		].join("|");
		if (key === this.listKey) return;
		this.listKey = key;
		this.familyListedRows = null;
		this.list = this.buildList();
		if (this.familyEnabled) {
			this.list.forEach((row, i) => {
				if (row.item.type === "word" && this.familyEligible(i)) row.item.expandable = true;
			});
		}
	}

	private selected(): InternalItem | null {
		return this.selection === null ? null : (this.list[this.selection] ?? null);
	}

	// ── Paged selection ──

	private pagedFirstRow(): number {
		let lastListFunction = -1;
		this.list.forEach((row, i) => {
			if (row.item.type === "page") lastListFunction = i;
		});
		const base = Math.max(this.listedWords, lastListFunction + 1);
		if (this.familyListedRows === null) return base;
		return Math.min(Math.max(this.familyListedRows, lastListFunction + 1), base);
	}

	private pagedStart(page: number): number {
		return this.pagedFirstRow() + page * PAGED_WORDS_PER_PAGE;
	}

	private wouldEnterPaged(currentIndex: number): boolean {
		if (this.wordSelection !== "paged") return false;
		const first = this.pagedFirstRow();
		return currentIndex + 1 >= first && this.list.length >= first + PAGED_MIN_FIRST_PAGE;
	}

	/** keyNum -> position on a selection page. */
	private pagedOrdinals(): number[] {
		if (!this.scanOrder) return PAGED_ORDINAL_GRID;
		const ordinals = [...PAGED_ORDINAL_GRID];
		let rank = 0;
		for (const slot of this.layoutMode === "alpha" ? SCAN_ORDER_ALPHA : SCAN_ORDER_OPTIMIZED) {
			const keyNum = LETTER_SLOTS.indexOf(slot);
			if (keyNum >= 0) ordinals[keyNum] = rank++;
		}
		return ordinals;
	}

	private pagedPick(row: number): void {
		const target = this.list[row];
		if (!target || target.item.type === "pad") {
			this.events.onError?.();
			return;
		}
		if (target.item.type === "page") {
			this.openListFunction(target.item.page);
			return;
		}
		// A pick is final: the key chose the word unambiguously.
		if (this.speech) this.events.onSpeak?.(target.item.output);
		this.commit(target);
	}

	// ── Word families ──

	private familyCandidates(word: string): WordEntry[] {
		const floor = Math.max(2, this.seq.length);
		let stem = word.slice(0, Math.max(word.length - FAMILY_STEM_BACKOFF, 0));
		// Never shorter than what was typed: a shorter stem would admit words that contradict it.
		if (stem.length < floor) stem = word.slice(0, floor);
		if (stem.length < 2) return [];
		for (;;) {
			const found = this.wld()
				.wordsWithPrefix(stem, word, FAMILY_MAX_CANDIDATES)
				.filter((e) => e.lower !== word && this.include(e));
			// Widen the stem until a page fills.
			if (found.length >= PAGED_WORDS_PER_PAGE || stem.length <= floor) return found;
			stem = stem.slice(0, -1);
		}
	}

	private familyEligible(index: number): boolean {
		const row = this.list[index];
		if (!this.familyEnabled || !row?.entry || row.item.type !== "word" || row.item.family) return false;
		if (this.seq.length === 0 || this.paged !== null) return false;
		if (row.item.keysRemaining < FAMILY_MIN_KEYS_REMAINING) return false;
		const listed = new Set(this.list.map((r) => r.entry?.lower));
		return this.familyCandidates(row.entry.lower).some((e) => !listed.has(e.lower));
	}

	private scheduleFamily(index: number): void {
		if (!this.familyEligible(index)) return;
		this.familyTimer = setTimeout(() => this.expandFamilyNow(), this.familyDelayMs);
	}

	private cancelFamilyTimer(): void {
		if (this.familyTimer !== null) clearTimeout(this.familyTimer);
		this.familyTimer = null;
	}

	/** Inserts the family of the row at `index` directly after it. */
	private expandFamily(index: number): boolean {
		const row = this.list[index];
		if (!row?.entry || !this.familyEligible(index)) return false;
		const typed = this.seq.length;
		const group: InternalItem[] = this.familyCandidates(row.entry.lower).map((entry) => {
			const keys = this.wld().translate(entry.lower)?.length ?? typed;
			const member = this.wordItems(entry, Math.max(keys - typed, 0), false, this.capPending)[0];
			if (member.item.type === "word") member.item.family = true;
			return member;
		});
		if (group.length === 0) return false;
		const paged = this.wordSelection === "paged";
		// Whole pages only, so the group never absorbs ordinary rows into its last page.
		while (paged && group.length % PAGED_WORDS_PER_PAGE !== 0) {
			group.push({ item: { type: "pad", display: "" }, entry: null });
		}
		if (row.item.type === "word") row.item.expandable = false;
		this.list.splice(index + 1, 0, ...group);
		if (paged) this.familyListedRows = index + 1;
		return true;
	}

	// ── Main-page keys ──

	private takeUndoSnapshot(): UndoSnapshot {
		return {
			seq: [...this.seq],
			selection: this.selection,
			paged: this.paged,
			capPending: this.capPending,
			shift: this.shift,
			autoShift: this.autoShift,
		};
	}

	private restore(s: UndoSnapshot): void {
		this.seq = [...s.seq];
		this.selection = s.selection;
		this.paged = s.paged;
		this.capPending = s.capPending;
		this.shift = s.shift;
		this.autoShift = s.autoShift;
	}

	private resetSequence(): void {
		this.seq = [];
		this.selection = null;
		this.paged = null;
		this.capPending = false;
		this.undo = [];
	}

	/** Ends the word in progress before the cursor or text changes under it. */
	private leaveSequence(acceptSelected: boolean): void {
		this.cancelFamilyTimer();
		const sel = this.selected();
		if (acceptSelected && sel && isOutput(sel.item)) this.commit(sel);
		else this.resetSequence();
	}

	private pressLetters(key: number): void {
		// A new key after a selection accepts the selected word.
		const sel = this.selected();
		if (sel && isOutput(sel.item)) this.commit(sel);
		const before = this.takeUndoSnapshot();
		if (this.seq.length === 0) {
			this.capPending = this.shift;
			this.shift = false;
		}
		this.seq.push(key);
		this.selection = null;
		this.ensureList();
		if (this.list.length === 0) {
			// No word continues this way: refuse the key rather than strand the user.
			this.restore(before);
			this.events.onError?.();
			return;
		}
		this.undo.push(before);
	}

	private openListFunction(page: string): void {
		const shift = this.capPending;
		this.resetSequence();
		this.shift = shift;
		this.caller = "Main";
		this.goTo(page);
	}

	private pressSelect(): void {
		if (this.list.length === 0) {
			// Select on an empty keyboard reloads the word touching the cursor.
			if (this.seq.length === 0 && this.pullInEnabled && this.tryPullIn()) return;
			this.events.onError?.();
			return;
		}
		if (this.paged !== null) {
			if (this.pagedStart(this.paged + 1) >= this.list.length) {
				this.events.onError?.();
				return;
			}
			this.undo.push(this.takeUndoSnapshot());
			this.paged += 1;
			return;
		}
		if (this.wouldEnterPaged(this.selection ?? -1)) {
			this.undo.push(this.takeUndoSnapshot());
			this.paged = 0;
			return;
		}
		const next = (this.selection ?? -1) + 1;
		const target = this.list[next];
		if (!target || target.item.type === "pad") {
			this.events.onError?.();
			return;
		}
		if (target.item.type === "page") {
			this.openListFunction(target.item.page);
			return;
		}
		this.undo.push(this.takeUndoSnapshot());
		this.selection = next;
		if (this.speech) this.events.onSpeak?.(target.item.output);
		this.scheduleFamily(next);
	}

	private pressUndo(): void {
		const previous = this.undo.pop();
		if (previous) {
			this.restore(previous);
			if (this.seq.length === 0 && this.selection === null && this.paged === null) this.undo = [];
			return;
		}
		if (this.buf.selection()) {
			this.buf.checkpoint();
			this.buf.deleteSelection();
			this.afterTextEdit();
			return;
		}
		// Backing into a finished word reopens it: this UnDo removes its Select, the next
		// ones its letters.
		if (this.pullInEnabled && WORD_END.test(this.buf.before) && this.tryPullIn()) {
			const withoutSelect = this.undo.pop();
			if (withoutSelect) this.restore(withoutSelect);
			return;
		}
		if (this.buf.backspace()) this.afterTextEdit();
		else this.events.onError?.();
	}

	// ── Pull-in ──

	/**
	 * Takes the word touching the cursor out of the text and rebuilds the state its typist
	 * would be in: its keys typed and enough Selects pressed to have it selected.
	 */
	private tryPullIn(): boolean {
		const range = this.buf.wordRangeAtCursor();
		if (!range) return false;
		const word = this.buf.text.slice(range[0], range[1]);
		const keys = this.wld().translate(word);
		if (!keys) return false;
		const saved = { text: this.buf.text, cursor: this.buf.cursor, state: this.takeUndoSnapshot() };
		this.buf.replaceRange(range[0], range[1], "");

		const find = (cap: boolean, match: (output: string) => boolean): number => {
			this.seq = [...keys];
			this.capPending = cap;
			this.shift = false;
			this.autoShift = false;
			this.selection = null;
			this.paged = null;
			this.ensureList();
			return this.list.findIndex((row) => row.item.type === "word" && match(row.item.output));
		};
		let cap = false;
		let index = find(false, (o) => o === word);
		if (index < 0) {
			cap = true;
			index = find(true, (o) => o === word);
		}
		if (index < 0) {
			cap = false;
			index = find(false, (o) => o.toLowerCase() === word.toLowerCase());
		}
		if (index < 0) {
			this.buf.set(saved.text, saved.cursor);
			this.restore(saved.state);
			return false;
		}
		// A word inside a page group cannot be reached by Selects; move it to the second row.
		if (this.wordSelection === "paged" && index >= this.pagedFirstRow()) {
			const [row] = this.list.splice(index, 1);
			index = Math.min(1, this.list.length);
			this.list.splice(index, 0, row);
		}
		const base = { paged: null, autoShift: false };
		this.undo = keys.map((_, i) => ({
			...base,
			seq: keys.slice(0, i),
			selection: null,
			capPending: i > 0 && cap,
			shift: i === 0 && cap,
		}));
		for (let s = 0; s <= index; s++) {
			this.undo.push({ ...base, seq: [...keys], selection: s === 0 ? null : s - 1, capPending: cap, shift: false });
		}
		this.selection = index;
		return true;
	}

	// ── Text ──

	private commit(sel: InternalItem): void {
		if (!isOutput(sel.item)) return;
		const ctx = contextFor(this.buf.before);
		this.insertWord(sel.item.output);
		const entry = sel.entry;
		if (entry) {
			entry.useCount += 1;
			entry.lastUse = this.nowSec();
			this.persisted.usage[entry.surface] = [entry.useCount, entry.lastUse];
			if (ctx !== null) {
				const row = (this.persisted.userBigrams[ctx] ??= newRow());
				row[entry.lower] = (row[entry.lower] ?? 0) + 1;
			}
			this.store?.save(this.persisted);
		}
		this.lastCommitted = sel.item.output;
		this.version++;
		this.resetSequence();
		this.shift = false;
		this.autoShift = false;
		this.afterTextEdit();
	}

	/** Inserts a word at the cursor, spaced from its neighbours. */
	private insertWord(word: string): void {
		this.buf.deleteSelection();
		this.buf.insert((needsSpace(this.buf.before) ? " " : "") + word);
		if (WORD_START.test(this.buf.after)) this.buf.after = ` ${this.buf.after}`;
		this.afterTextEdit();
	}

	private append(s: string, spaceBefore: boolean): void {
		this.buf.deleteSelection();
		this.buf.insert((spaceBefore && needsSpace(this.buf.before) ? " " : "") + s);
		this.afterTextEdit();
	}

	/** Re-derives auto-capitalisation from where the cursor now sits. */
	private afterTextEdit(): void {
		if (!this.autoCapitalize) return;
		if (atSentenceStart(this.buf.before)) {
			if (!this.shift) this.autoShift = true;
			this.shift = true;
		} else if (this.autoShift) {
			this.shift = false;
			this.autoShift = false;
		}
	}

	private editText(edit: () => boolean): void {
		if (edit()) this.afterTextEdit();
		else this.events.onError?.();
	}

	private goTo(page: string): void {
		this.page = page;
		if (page === "Numbers1") this.numericFresh = true;
	}

	private backToCaller(): void {
		this.page = this.caller;
	}

	private backToMain(): void {
		this.selecting = false;
		this.moveMode = 0;
		this.caller = "Main";
		this.page = "Main";
	}

	private composing(): string {
		if (this.page.startsWith("Spell")) return this.spellMode === "letters" ? "" : this.spell;
		const sel = this.selected();
		if (sel && isOutput(sel.item)) return sel.item.output;
		const top = this.seq.length ? this.list[0]?.item : undefined;
		return top?.type === "word" ? top.output : "";
	}

	private speak(text: string): void {
		if (text) this.events.onSpeak?.(text);
		else this.events.onError?.();
	}

	// ── Pages ──

	private fn(label: string, run: () => void, active?: boolean): KeyDef {
		return { kind: "function", label, run, active };
	}

	private go(label: string, page: string): KeyDef {
		return this.fn(label, () => this.goTo(page));
	}

	/** A key showing up to six choices; pressing it opens `page`. */
	private group(choices: string[], page: string): KeyDef {
		const grid = new Array(9).fill("");
		choices.forEach((c, i) => {
			grid[PREVIEW_CELLS[i]] = c;
		});
		return { kind: "letters", grid, run: () => this.goTo(page) };
	}

	private shiftKey(stay: boolean): KeyDef {
		return this.fn(
			"SHIFT",
			() => {
				this.shift = !this.shift;
				this.autoShift = false;
				if (!stay) this.page = "Main";
			},
			this.shift,
		);
	}

	private capsKey(): KeyDef {
		return this.fn(
			"CAPS\nLOCK\nON/OFF",
			() => {
				this.capsLock = !this.capsLock;
				this.page = "Main";
			},
			this.capsLock,
		);
	}

	/** A symbol key. `stay` keeps the page open (repeated-symbols pages). */
	private sym(s: string, o: SymOptions = {}): KeyDef {
		return this.fn(o.label ?? s, () => {
			this.append(s, o.spaceBefore ?? false);
			if (o.endSentence && !o.stay && this.speech) this.events.onSpeak?.(lastSentence(this.buf.before));
			if (!o.stay) this.backToCaller();
		});
	}

	private symbolPages(stay: boolean): KeyDef[][] {
		const prefix = stay ? "SymbolsMulti" : "Symbols";
		const pageLabel = (n: number) => (stay ? `REPEATED\nSYMBOLS\nPG. ${n}` : `SYMBOL\nPG. ${n}`);
		const back = this.fn(this.caller === "LetterSymbol" ? "BACK" : "BACK\nTO\nMAIN", () => this.backToCaller());
		const all = this.symbols ? this.fn("ALL\nSYMBOLS\nMODE", () => this.openAllSymbols(stay)) : BLANK;
		const s = (ch: string, o: SymOptions = {}) => this.sym(ch, { ...o, stay });
		return [
			[
				stay ? all : this.go("TYPE\nREPEATED\nSYMBOLS", "SymbolsMulti1"),
				back,
				s(".", { endSentence: true }),
				s("-"),
				s("!", { endSentence: true }),
				s(","),
				this.go(pageLabel(2), `${prefix}2`),
				s("?", { endSentence: true }),
			],
			[
				s(" ", { label: "<SPACE>" }),
				this.go(pageLabel(1), `${prefix}1`),
				s("@"),
				s(":"),
				s("(", { spaceBefore: true }),
				s(";"),
				this.go(pageLabel(3), `${prefix}3`),
				s(")"),
			],
			[
				all,
				this.go(pageLabel(2), `${prefix}2`),
				s("&", { spaceBefore: true }),
				s("'"),
				s('"', { spaceBefore: isQuoteClosed(this.buf.before) }),
				s("*"),
				back,
				s("/"),
			],
		];
	}

	/** A number-mode key; single letters (K, M, e) follow shift. */
	private digit(s: string, returnTo?: string): KeyDef {
		const isLetter = s.length === 1 && /\p{L}/u.test(s);
		const out = s === "<SP>" ? " " : isLetter ? (this.upper() ? s.toUpperCase() : s.toLowerCase()) : s;
		return this.fn(s === "<SP>" ? s : out, () => {
			this.append(out, this.numericFresh);
			this.numericFresh = false;
			if (isLetter) this.shift = false;
			if (returnTo) this.page = returnTo;
		});
	}

	/** Six number-mode symbols, one per letter slot. */
	private punctRow(chars: string[]): KeyDef[] {
		const [c0, c1, c2, c3, c4, c5] = chars.map((c) => this.digit(c, "Numbers2"));
		return [c0, this.go("CANCEL", "Numbers2"), c1, c2, c3, c4, this.shiftKey(true), c5];
	}

	private mainPage(): KeyDef[] {
		const grids = this.keyLayout().grids;
		if (this.paged !== null && this.pagedStart(this.paged) >= this.list.length) this.paged = null;
		const paged = this.paged;
		const ordinals = this.pagedOrdinals();
		const hintSet =
			this.hints && paged === null && this.seq.length > 0 && this.selection === null
				? this.wld().nextLetters(this.seq, this.include)
				: null;
		const letters = (k: number): KeyDef => {
			if (paged !== null) {
				const row = this.pagedStart(paged) + ordinals[k];
				const item = this.list[row]?.item;
				if (!item || item.type === "pad") return BLANK;
				return { kind: "word", label: item.display, run: () => this.pagedPick(row) };
			}
			return {
				kind: "letters",
				grid: grids[k].map((cell) => cell.toUpperCase()),
				badge: this.listFunction(k)?.badge,
				hints: hintSet
					? grids[k].map((cell) => cellChars(this.spec, cell).some((ch) => hintSet.has(ch.toLowerCase())))
					: undefined,
				run: () => this.pressLetters(k),
			};
		};
		return [
			letters(0),
			this.fn("UnDo /\nDELETE", () => this.pressUndo()),
			letters(1),
			letters(2),
			letters(3),
			letters(4),
			this.fn("SELECT", () => this.pressSelect()),
			letters(5),
		];
	}

	private functionPages(): Record<string, () => KeyDef[]> {
		const toMain = this.fn("BACK\nTO\nMAIN", () => this.backToMain());
		const enter = (stay: boolean) =>
			this.fn("ENTER", () => {
				this.append("\n", false);
				if (!stay) this.page = "Main";
			});
		const deleteWord = (stay: boolean) =>
			this.fn("DELETE\nWORD", () => {
				this.editText(() => this.buf.deleteWord());
				if (!stay) this.page = "Main";
			});
		const numberDelete = this.fn("DELETE", () => this.editText(() => this.buf.backspace()));
		const done = this.fn("<DONE>", () => this.backToCaller());
		const enterSub = (label: string, page: string, caller: "Main" | "LetterSymbol") =>
			this.fn(label, () => {
				this.caller = caller;
				this.goTo(page);
			});
		const cursor = (label: string, direction: Direction, mode = this.moveMode) =>
			this.fn(label, () => {
				this.buf.move(direction, mode as MoveMode, this.selecting);
				this.afterTextEdit();
			});
		const mark = (label: string, name: "a" | "b", jump: boolean) =>
			this.fn(label, () => {
				if (!jump) {
					this.marks[name] = this.buf.cursor;
					return;
				}
				const at = this.marks[name];
				if (at === null) {
					this.events.onError?.();
					return;
				}
				this.buf.moveTo(at, this.selecting);
				this.afterTextEdit();
			});
		const editUndo = this.fn("UNDO", () => this.editText(() => this.buf.undo()));
		const toCursor = this.go("BACK TO\nCURSOR\nMODE", this.moveMode === BOOKMARK_MODE ? "EditBookmark" : "Edit1");

		return {
			Symbols1: () => this.symbolPages(false)[0],
			Symbols2: () => this.symbolPages(false)[1],
			Symbols3: () => this.symbolPages(false)[2],
			SymbolsMulti1: () => this.symbolPages(true)[0],
			SymbolsMulti2: () => this.symbolPages(true)[1],
			SymbolsMulti3: () => this.symbolPages(true)[2],
			Functions0: () => [
				deleteWord(false),
				toMain,
				this.fn("DONE WITH\nPHRASE", () => this.finishPhraseText()),
				this.capsKey(),
				this.shiftKey(false),
				enter(false),
				this.go("FUNCTION\nPG. 1", "Functions1"),
				this.fn("CANCEL\nPHRASE", () => this.endPhraseFlow()),
			],
			Functions1: () => [
				this.capsKey(),
				this.flow ? this.go("PHRASE\nENTRY\nFUNCTIONS", "Functions0") : toMain,
				this.shiftKey(false),
				this.fn(
					"SPEECH\nON/OFF",
					() => {
						this.speech = !this.speech;
						this.page = "Main";
					},
					this.speech,
				),
				this.fn("SPEAK\nSENTENCE", () => {
					this.speak(lastSentence(this.buf.before));
					this.page = "Main";
				}),
				enter(false),
				this.go("FUNCTION\nPG. 2", "Functions2"),
				this.fn("SPEAK\n(LAST)\nSELECTION", () => {
					this.speak(this.lastCommitted);
					this.page = "Main";
				}),
			],
			Functions2: () => [
				this.fn("PAGE\nUP", () => this.events.onScroll?.("up")),
				this.go("FUNCTION\nPG. 1", "Functions1"),
				this.fn("DELETE\nCHAR", () => this.editText(() => this.buf.backspace())),
				this.fn("PAGE\nDOWN", () => this.events.onScroll?.("down")),
				deleteWord(true),
				enter(true),
				toMain,
				this.fn("SPEAK\nNEXT\nSENTENCE", () => this.speakNextSentence()),
			],
			Menu: () => [
				this.fn("ADD NEW\nWORD", () => this.startSpelling("word", "Main")),
				this.flow ? BLANK : this.fn("ADD NEW\nPHRASE", () => this.startPhraseFlow()),
				this.go("LETTER /\nSYMBOL\nMODE", "LetterSymbol"),
				BLANK,
				enterSub("123\nNUMBER\nMODE", "Numbers1", "Main"),
				BLANK,
				toMain,
				this.go("EDIT\nMODE", "Edit1"),
			],
			LetterSymbol: () => [
				this.fn("LETTER\nSPELL\nMODE", () => this.startSpelling("letters", "LetterSymbol")),
				this.fn("UnDo /\nDELETE", () => this.editText(() => this.buf.backspace())),
				enterSub("!.?\nSYMBOL\nMODE", "Symbols1", "LetterSymbol"),
				cursor("←", "left", MoveMode.CharacterLine),
				cursor("→", "right", MoveMode.CharacterLine),
				enterSub("123\nNUMBER\nMODE", "Numbers1", "LetterSymbol"),
				toMain,
				BLANK,
			],
			Numbers1: () => [
				this.digit("1"),
				numberDelete,
				this.digit("4"),
				this.digit("2"),
				this.digit("5"),
				this.digit("3"),
				done,
				this.go("6 - 0\n+-./,", "Numbers2"),
			],
			Numbers2: () => [
				this.digit("6"),
				numberDelete,
				this.digit("9"),
				this.digit("7"),
				this.digit("0"),
				this.digit("8"),
				this.go("+-./,", "NumbersPunct"),
				this.fn("1 - 5\n(OR\n<DONE>)", () => {
					this.page = "Numbers1";
				}),
			],
			NumbersPunct: () => [
				this.group(["$", "€", "¥", "£", "₹", "R$"], "NumPunct0"),
				this.go("CANCEL", "Numbers2"),
				this.group(["(", ")", "<", ">", "[", "]"], "NumPunct2"),
				this.group(["#", "%", "<SP>", "°", "K", "M"], "NumPunct3"),
				this.group(["+", "-", "/", "*", "=", "e"], "NumPunct4"),
				this.digit(".", "Numbers2"),
				this.digit(",", "Numbers2"),
				this.digit(":", "Numbers2"),
			],
			NumPunct0: () => this.punctRow(["$", "€", "¥", "£", "₹", "R$"]),
			NumPunct2: () => this.punctRow(["(", ")", "<", ">", "[", "]"]),
			NumPunct3: () => this.punctRow(["#", "%", "<SP>", "°", "K", "M"]),
			NumPunct4: () => this.punctRow(["+", "-", "/", "*", "=", "e"]),
			Edit1: () =>
				this.editCursorPage(cursor("↑", "up"), cursor("←", "left"), cursor("→", "right"), cursor("↓", "down")),
			EditBookmark: () =>
				this.editCursorPage(
					mark("JUMP TO\nMARK A", "a", true),
					mark("SET\nMARK A", "a", false),
					mark("SET\nMARK B", "b", false),
					mark("JUMP TO\nMARK B", "b", true),
				),
			Edit2: () => [
				this.fn("CUT", () => this.copySelection(true)),
				editUndo,
				this.go("ADJUST\nCASE\nMODE", "Edit3"),
				this.fn("COPY", () => this.copySelection(false)),
				toCursor,
				this.fn("PASTE", () => this.paste()),
				this.fn(this.buf.selection() ? "SPEAK\nSELECTION" : "SPEAK\nSENTENCE", () => {
					const [start, end] = this.buf.sentenceRangeAtCursor();
					this.speak(this.buf.selectedText() || this.buf.text.slice(start, end));
				}),
				toMain,
			],
			Edit3: () => [
				this.fn("word\n↓\nWord", () =>
					this.changeCase(false, (t) => t.replace(/\p{L}[\p{L}'’]*/gu, (w) => capitalize(w.toLowerCase()))),
				),
				editUndo,
				this.fn("APPLY\nSentence\ncase…", () => this.changeCase(true, (t) => capitalize(t.toLowerCase()))),
				this.fn("word\n↓\nWORD", () => this.changeCase(false, (t) => t.toUpperCase())),
				toCursor,
				this.fn("Word\n↓\nword", () => this.changeCase(false, (t) => t.toLowerCase())),
				this.fn("EDIT\nWORD", () => this.startSpelling("edit", "Edit3")),
				toMain,
			],
		};
	}

	// ── Edit mode ──

	/** The cursor page: `n`, `w`, `e`, `s` are the four direction (or bookmark) keys. */
	private editCursorPage(n: KeyDef, w: KeyDef, e: KeyDef, s: KeyDef): KeyDef[] {
		return [
			this.fn(
				this.selecting ? "(SELECTING\nTEXT)" : "SELECT\nTEXT",
				() => {
					this.selecting = !this.selecting;
					this.buf.anchor = this.selecting ? this.buf.cursor : null;
				},
				this.selecting,
			),
			n,
			this.go("CUT /\nCOPY /\nPASTE /\nCASE ⟳", "Edit2"),
			w,
			e,
			this.fn(MOVE_LABELS[this.moveMode], () => {
				this.moveMode = (this.moveMode + 1) % MOVE_LABELS.length;
				this.page = this.moveMode === BOOKMARK_MODE ? "EditBookmark" : "Edit1";
			}),
			s,
			this.fn("BACK\nTO\nMAIN", () => this.backToMain()),
		];
	}

	private copySelection(cut: boolean): void {
		const text = this.buf.selectedText();
		if (!text) {
			this.events.onError?.();
			return;
		}
		this.clipboard = text;
		this.events.onCopy?.(text);
		this.selecting = false;
		if (cut) {
			this.buf.checkpoint();
			this.buf.deleteSelection();
			this.afterTextEdit();
		} else {
			this.buf.anchor = null;
		}
	}

	private paste(): void {
		if (!this.clipboard) {
			this.events.onError?.();
			return;
		}
		this.selecting = false;
		this.buf.checkpoint();
		this.buf.insert(this.clipboard);
		this.afterTextEdit();
	}

	/** Recases the selection, or else the word (or sentence) at the cursor. */
	private changeCase(sentence: boolean, recase: (text: string) => string): void {
		const range =
			this.buf.selection() ?? (sentence ? this.buf.sentenceRangeAtCursor() : this.buf.wordRangeAtCursor());
		if (!range || range[0] === range[1]) {
			this.events.onError?.();
			return;
		}
		this.selecting = false;
		this.buf.checkpoint();
		this.buf.replaceRange(range[0], range[1], recase(this.buf.text.slice(range[0], range[1])));
		this.afterTextEdit();
	}

	private speakNextSentence(): void {
		const sentences = (this.buf.text.match(/[^.!?…\n]+[.!?…]*/g) ?? []).map((s) => s.trim()).filter(Boolean);
		if (sentences.length === 0) {
			this.events.onError?.();
			return;
		}
		this.speak(sentences[this.speakIndex % sentences.length]);
		this.speakIndex = (this.speakIndex + 1) % sentences.length;
	}

	// ── Phrases ──

	/** ADD NEW PHRASE: set the message aside and type the phrase with the normal keyboard. */
	private startPhraseFlow(): void {
		this.flow = { phase: "phrase", phrase: "", saved: { text: this.buf.text, cursor: this.buf.cursor } };
		this.buf.set("");
		this.afterTextEdit();
		this.backToMain();
	}

	/** DONE WITH PHRASE: keep the typed phrase and ask for its abbreviation. */
	private finishPhraseText(): void {
		const phrase = this.buf.text.trim();
		if (!this.flow || !phrase) {
			this.events.onError?.();
			return;
		}
		this.flow.phase = "abbreviation";
		this.flow.phrase = phrase;
		this.buf.set("");
		this.startSpelling("abbreviation", "Main");
	}

	/** Leaves the phrase flow and brings the message back. */
	private endPhraseFlow(): void {
		if (this.flow) this.buf.set(this.flow.saved.text, this.flow.saved.cursor);
		this.flow = null;
		this.spell = "";
		this.shift = false;
		this.autoShift = false;
		this.afterTextEdit();
		this.backToMain();
	}

	// ── ALL SYMBOLS MODE ──

	private openAllSymbols(multi: boolean): void {
		if (!this.symbols) return;
		this.allSymbols = new AllSymbolsController(this.symbols, multi);
		this.page = "AllSymbols";
	}

	private allSymbolsPage(): KeyDef[] {
		const ctrl = this.allSymbols;
		const page: KeyDef[] = new Array(SLOT_COUNT).fill(BLANK);
		if (!ctrl) return page;
		page[UNDO_SLOT] = this.fn(ctrl.atRoot ? "BACK" : "UP", () => {
			if (ctrl.ascend()) return;
			this.allSymbols = null;
			this.page = ctrl.multi ? "SymbolsMulti1" : "Symbols3";
		});
		if (ctrl.pageCount > 1) {
			const last = ctrl.pageIndex === ctrl.pageCount - 1;
			page[SELECT_SLOT] = this.fn(last ? "RETURN\nTO PAGE\n1" : `PAGE ${ctrl.pageIndex + 2}`, () => ctrl.more());
		}
		ctrl.slots().forEach((slot, i) => {
			if (slot.kind === "branch") {
				page[LETTER_SLOTS[i]] = { kind: "letters", grid: slot.preview, run: () => void ctrl.descend(slot.index) };
				return;
			}
			page[LETTER_SLOTS[i]] = this.fn(slot.char, () => {
				// Verbatim: symbols are never case-folded or auto-spaced.
				this.append(slot.char, false);
				if (ctrl.multi) {
					ctrl.reset();
				} else {
					this.allSymbols = null;
					this.backToCaller();
				}
			});
		});
		return page;
	}

	// ── Spelling ──

	private upper(): boolean {
		return this.shift || this.capsLock;
	}

	private startSpelling(mode: SpellMode, returnTo: string): void {
		this.spellMode = mode;
		this.spellReturn = returnTo;
		this.spell = "";
		this.spellNeedsSpace = true;
		if (returnTo === "LetterSymbol") this.caller = "LetterSymbol";
		this.page = "Spelling";
	}

	private spellChar(ch: string): KeyDef {
		const out = this.upper() ? ch.toUpperCase() : ch;
		return this.fn(out, () => {
			if (this.spellMode === "letters") {
				// Letter mode types straight into the message.
				this.append(out, this.spellNeedsSpace);
				this.spellNeedsSpace = false;
			} else {
				this.spell += out;
			}
			this.shift = false;
			this.autoShift = false;
			this.page = "Spelling";
		});
	}

	private spellingPage(): KeyDef[] {
		const grids = this.keyLayout().grids;
		const page: KeyDef[] = new Array(SLOT_COUNT).fill(BLANK);
		LETTER_SLOTS.forEach((slot, k) => {
			page[slot] = {
				kind: "letters",
				grid: grids[k].map((cell) => (this.upper() ? cell.toUpperCase() : cell)),
				run: () => this.goTo(`Spell:${k}`),
			};
		});
		page[UNDO_SLOT] = this.fn("DELETE", () => {
			if (this.spell) this.spell = Array.from(this.spell).slice(0, -1).join("");
			else if (this.spellMode === "letters" || this.spellMode === "word") this.editText(() => this.buf.backspace());
			else this.events.onError?.();
		});
		page[SELECT_SLOT] = this.fn("<DONE>", () => this.finishSpelling());
		return page;
	}

	/** One key's characters, each on the slot matching its place on the key face. */
	private spellKeyPage(k: number): KeyDef[] {
		const page: (KeyDef | null)[] = new Array(SLOT_COUNT).fill(null);
		const unplaced: KeyDef[] = [];
		this.keyLayout().grids[k].forEach((cell, index) => {
			if (cell === "") return;
			const chars = cellChars(this.spec, cell);
			const def = chars.length === 1 ? this.spellChar(chars[0]) : this.go(cell, `SpellSlot:${k}:${index}`);
			const slot = CELL_TO_SLOT[index];
			if (slot >= 0 && !page[slot]) page[slot] = def;
			else unplaced.push(def);
		});
		const extras = [
			{ prefer: UNDO_SLOT, def: this.go("BACK", "Spelling") },
			{ prefer: SELECT_SLOT, def: this.shiftKey(true) },
		];
		for (const { prefer, def } of extras) {
			const slot = page[prefer] ? page.indexOf(null) : prefer;
			if (slot >= 0) page[slot] = def;
		}
		for (const def of unplaced) {
			const slot = page.indexOf(null);
			if (slot >= 0) page[slot] = def;
		}
		return page.map((def) => def ?? BLANK);
	}

	/** The members of a multi-character cell (e.g. "15_"). */
	private spellSlotPage(k: number, cell: number): KeyDef[] {
		const page: KeyDef[] = new Array(SLOT_COUNT).fill(BLANK);
		const chars = cellChars(this.spec, this.keyLayout().grids[k][cell] ?? "");
		chars.slice(0, CELLS_PER_PAGE).forEach((ch, i) => {
			page[LETTER_SLOTS[i]] = this.spellChar(ch);
		});
		page[UNDO_SLOT] = this.go("BACK", `Spell:${k}`);
		return page;
	}

	private finishSpelling(): void {
		const word = this.spell;
		this.spell = "";
		if (this.spellMode === "abbreviation") {
			if (!this.flow || !word || !this.wld().translate(word)) {
				this.events.onError?.();
				return;
			}
			const phrase = this.flow.phrase;
			this.endPhraseFlow();
			this.persisted.phrases.push({
				id: `${this.now().toString(36)}-${this.persisted.phrases.length}`,
				abbreviation: word,
				phrase,
			});
			this.phrasesChanged();
			return;
		}
		this.page = this.spellReturn;
		if (!word) return;
		if (this.spellMode === "edit") {
			this.buf.checkpoint();
			this.insertWord(word);
			return;
		}
		// ADD NEW WORD: type the word and teach it to the vocabulary.
		this.insertWord(word);
		const lower = word.toLowerCase();
		const typeable = /^[\p{L}'-]+$/u.test(word) && this.wld().translate(word) !== null;
		if (typeable && !this.wld().find(lower)) {
			this.addEntry(word, USER_WORD_FREQ, word === lower ? "LO" : "OC", 0, true);
			this.persisted.userWords.push(word);
			this.version++;
			this.store?.save(this.persisted);
		}
	}

	private pageKeys(): KeyDef[] {
		if (this.page === "Main") return this.mainPage();
		if (this.page === "Spelling") return this.spellingPage();
		if (this.page === "AllSymbols") return this.allSymbolsPage();
		const [name, k, cell] = this.page.split(":");
		if (name === "Spell") return this.spellKeyPage(Number(k));
		if (name === "SpellSlot") return this.spellSlotPage(Number(k), Number(cell));
		return this.functionPages()[this.page]?.() ?? this.mainPage();
	}

	private centerText(): string {
		if (this.page === "AllSymbols") return this.allSymbols?.setName.toUpperCase() ?? "";
		if (this.page === "Edit1" || this.page === "EditBookmark") return MOVE_CENTER[this.moveMode];
		if (this.page === "Edit2") return "CLIPBOARD";
		if (this.page === "Edit3") return "ADJUST CASE";
		return "";
	}

	// ── Snapshot ──

	private refresh(): void {
		this.ensureList();
		const keys = this.pageKeys().map(({ run: _run, ...face }) => face);
		const composing = this.composing();
		const { before, after } = this.buf;
		const lead = composing && needsSpace(before) ? " " : "";
		const trail = composing && WORD_START.test(after) ? " " : "";
		const pagedRows: [number, number] | null =
			this.paged === null
				? null
				: [this.pagedStart(this.paged), Math.min(this.pagedStart(this.paged + 1), this.list.length)];
		this.snapshot = {
			page: this.page,
			keys,
			list: this.list.map((i) => i.item),
			selection: this.selection,
			pagedPage: this.paged,
			pagedRows,
			text: before + after,
			before,
			after,
			cursor: before.length,
			textSelection: this.buf.selection(),
			composing,
			displayText: before + lead + composing + trail + after,
			keySequence: [...this.seq],
			shift: this.seq.length ? this.capPending : this.shift,
			capsLock: this.capsLock,
			speech: this.speech,
			layoutMode: this.layoutMode,
			centerText: this.centerText(),
			phraseFlow: this.flow ? { phase: this.flow.phase, phrase: this.flow.phrase } : null,
		};
		for (const listener of this.listeners) listener();
	}
}
