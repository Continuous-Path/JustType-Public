/** Trie-based word lookup / disambiguation — the web twin of the app's `WLD`. */
import { AMBIG_KEYS } from "./layout";

/** LO lower only · LF lower first · TF title first · TO title only · AC acronym · OC own case. */
export type CaseType = "LO" | "LF" | "TF" | "TO" | "AC" | "OC";

export interface WordEntry {
	id: number;
	lower: string;
	/** The word as stored (carries the case for TO / AC / OC). */
	surface: string;
	caseType: CaseType;
	rawFreq: number;
	freqClass: number;
	useCount: number;
	/** Epoch seconds of the last use; 0 = never. */
	lastUse: number;
	/** 0 none · 1 offensive · 2 potentially offensive. */
	level: number;
	user: boolean;
}

export interface Candidate {
	entry: WordEntry;
	/** Keystrokes still needed to reach the word (0 = fully typed). */
	keysRemaining: number;
	/** The entry's class, or a penalised one when reached through the accent fallback. */
	freqClass: number;
}

export interface SearchOptions {
	maxCompletions: number;
	baseDepth: number;
	expansionDepth: number;
	maxExaminedNodes: number;
	include: (entry: WordEntry) => boolean;
}

export const DEFAULT_SEARCH: Omit<SearchOptions, "include"> = {
	maxCompletions: 100,
	baseDepth: 8,
	expansionDepth: 7,
	maxExaminedNodes: 5000,
};

export interface WldOptions {
	/** Base letter -> its diacritic variants (lowercase). Variants type on the base letter's key. */
	variantsByBase?: Record<string, string>;
	/**
	 * Make a word that contains an accented letter with its own key (Spanish "qué") also
	 * reachable by typing the base letter, at a discounted rank.
	 */
	accentFallback?: boolean;
}

interface Node {
	next: (Node | undefined)[] | null;
	exact: WordEntry[] | null;
	/** Words reachable here only through the accent fallback. */
	fallback: WordEntry[] | null;
}

const FREQ_CLASS_FLOORS = [40000, 20000, 10000, 5000, 2500, 1250, 625, 300, 150, 75, 37, 17, 9];
const MAX_FREQ_CLASS = 14;
const FALLBACK_FREQ_CLASS_PENALTY = 3;
const MAX_HINT_LETTERS = 64;

/** 1 (most frequent) .. 14, from a raw corpus count. */
export function computeFreqClass(raw: number): number {
	const i = FREQ_CLASS_FLOORS.findIndex((floor) => raw >= floor);
	return i === -1 ? MAX_FREQ_CLASS : i + 1;
}

function fallbackClass(entry: WordEntry): number {
	return Math.min(entry.freqClass + FALLBACK_FREQ_CLASS_PENALTY, MAX_FREQ_CLASS);
}

function newNode(): Node {
	return { next: null, exact: null, fallback: null };
}

function commonPrefixLength(a: string, b: string): number {
	let i = 0;
	while (i < a.length && i < b.length && a[i] === b[i]) i++;
	return i;
}

export class Wld {
	private readonly root = newNode();
	private readonly charToKey = new Map<string, number>();
	/** Accented letter with its own key -> its base letter's key / base letter. */
	private readonly fallbackKey = new Map<string, number>();
	private readonly fallbackBase = new Map<string, string>();
	private readonly byLower = new Map<string, WordEntry>();

	constructor(lettersPerKey: string[], options: WldOptions = {}) {
		const setKey = (ch: string, key: number) => {
			this.charToKey.set(ch.toLowerCase(), key);
			this.charToKey.set(ch.toUpperCase(), key);
		};
		const variants = options.variantsByBase ?? {};
		const explicit = new Set(lettersPerKey.flatMap((chars) => [...chars.toLowerCase()]));
		lettersPerKey.forEach((chars, key) => {
			for (const ch of chars) {
				setKey(ch, key);
				// Layout letters win over variant folding: a variant with its own key keeps it.
				for (const v of variants[ch.toLowerCase()] ?? "") if (!explicit.has(v)) setKey(v, key);
			}
		});
		if (!options.accentFallback) return;
		for (const [base, chars] of Object.entries(variants)) {
			const baseKey = this.charToKey.get(base);
			if (baseKey === undefined) continue;
			for (const v of chars) {
				if (explicit.has(v) && this.charToKey.get(v) !== baseKey) {
					this.fallbackKey.set(v, baseKey);
					this.fallbackKey.set(v.toUpperCase(), baseKey);
					this.fallbackBase.set(v, base);
				}
			}
		}
	}

	/** The key sequence that types `word`, or null if a character is on no key. */
	translate(word: string): number[] | null {
		const out: number[] = [];
		for (const ch of word) {
			const key = this.charToKey.get(ch);
			if (key === undefined) return null;
			out.push(key);
		}
		return out;
	}

	/** The base-folded key sequence, or null when it equals the exact one. */
	private translateFolded(word: string): number[] | null {
		if (this.fallbackKey.size === 0) return null;
		let differs = false;
		const out: number[] = [];
		for (const ch of word) {
			const folded = this.fallbackKey.get(ch);
			const key = folded ?? this.charToKey.get(ch);
			if (key === undefined) return null;
			if (folded !== undefined) differs = true;
			out.push(key);
		}
		return differs ? out : null;
	}

	private walk(keys: number[], create: boolean): Node | null {
		let node = this.root;
		for (const key of keys) {
			if (create) {
				node.next ??= new Array(AMBIG_KEYS).fill(undefined);
				node = node.next[key] ??= newNode();
			} else {
				const child = node.next?.[key];
				if (!child) return null;
				node = child;
			}
		}
		return node;
	}

	/** Returns false when the word cannot be typed on this layout. */
	add(entry: WordEntry): boolean {
		const keys = this.translate(entry.lower);
		if (!keys || keys.length === 0) return false;
		((this.walk(keys, true) as Node).exact ??= []).push(entry);
		const folded = this.translateFolded(entry.lower);
		if (folded) ((this.walk(folded, true) as Node).fallback ??= []).push(entry);
		// First wins: the source list is frequency-ordered, so this is the dominant form.
		if (!this.byLower.has(entry.lower)) this.byLower.set(entry.lower, entry);
		return true;
	}

	find(lower: string): WordEntry | undefined {
		return this.byLower.get(lower);
	}

	/** Fully-typed words for `keys`, then breadth-first completions (shallowest first). */
	candidates(keys: number[], options: SearchOptions): Candidate[] {
		const node = this.walk(keys, false);
		if (!node) return [];
		const out: Candidate[] = [];
		const seen = new Set<number>();
		const push = (entry: WordEntry, keysRemaining: number, fallback: boolean) => {
			if (!options.include(entry) || seen.has(entry.id)) return false;
			seen.add(entry.id);
			out.push({ entry, keysRemaining, freqClass: fallback ? fallbackClass(entry) : entry.freqClass });
			return true;
		};
		node.exact?.forEach((entry) => push(entry, 0, false));
		node.fallback?.forEach((entry) => push(entry, 0, true));

		const maxDepth = options.baseDepth + options.expansionDepth;
		let frontier = (node.next ?? []).filter((n): n is Node => !!n);
		let completions = 0;
		let examined = 0;
		for (let depth = 1; depth <= maxDepth && frontier.length; depth++) {
			const nextFrontier: Node[] = [];
			for (const n of frontier) {
				if (n.exact || n.fallback) {
					examined++;
					for (const entry of n.exact ?? []) {
						if (push(entry, depth, false) && ++completions >= options.maxCompletions) return out;
					}
					for (const entry of n.fallback ?? []) {
						if (push(entry, depth, true) && ++completions >= options.maxCompletions) return out;
					}
					if (examined >= options.maxExaminedNodes) return out;
				}
				if (n.next) for (const child of n.next) if (child) nextFrontier.push(child);
			}
			frontier = nextFrontier;
		}
		return out;
	}

	/** The letters that some word offers as the keystroke after `keys` (lowercase). */
	nextLetters(keys: number[], include: (entry: WordEntry) => boolean): Set<string> {
		const letters = new Set<string>();
		const start = this.walk(keys, false);
		if (!start) return letters;
		const depth = keys.length;
		const collect = (node: Node) => {
			for (const entry of node.exact ?? []) {
				if (entry.lower.length > depth && include(entry)) letters.add(entry.lower[depth]);
			}
			// On a fallback path the user types base letters, so hint the base form.
			for (const entry of node.fallback ?? []) {
				if (entry.lower.length > depth && include(entry)) {
					const ch = entry.lower[depth];
					letters.add(this.fallbackBase.get(ch) ?? ch);
				}
			}
			for (const child of node.next ?? []) {
				if (child && letters.size < MAX_HINT_LETTERS) collect(child);
			}
		};
		collect(start);
		return letters;
	}

	/**
	 * Words spelled with the letter prefix `stem`, closest to `source` first (longest shared
	 * prefix, then alphabetical).
	 */
	wordsWithPrefix(stem: string, source: string, limit: number): WordEntry[] {
		const keys = this.translate(stem);
		const start = keys && this.walk(keys, false);
		if (!start || stem === "") return [];
		const found = new Map<number, WordEntry>();
		const stack = [start];
		while (stack.length) {
			const node = stack.pop() as Node;
			for (const entry of node.exact ?? []) {
				if (entry.lower.startsWith(stem)) found.set(entry.id, entry);
			}
			for (const child of node.next ?? []) if (child) stack.push(child);
		}
		return [...found.values()]
			.sort(
				(a, b) =>
					commonPrefixLength(b.lower, source) - commonPrefixLength(a.lower, source) ||
					(a.lower < b.lower ? -1 : a.lower > b.lower ? 1 : 0),
			)
			.slice(0, limit);
	}
}
