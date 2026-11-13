import { readFileSync } from "node:fs";
import type { SymbolData } from "../src/engine/allSymbols";
import { type EngineOptions, JustTypeEngine, type LanguageData, type NgramData } from "../src/engine/engine";

const load = (name: string) => JSON.parse(readFileSync(new URL(`../data/${name}`, import.meta.url), "utf8"));

export const english: LanguageData = load("english.json");
export const englishNgrams: NgramData = load("english.ngrams.json");
export const espanol: LanguageData = load("espanol.json");
export const symbols: SymbolData = load("symbols.json");

/** Slots of ambiguous keys 0..5. */
export const LETTER_SLOTS = [0, 2, 3, 4, 5, 7];
export const SELECT = 6;
export const UNDO = 1;

export class Typist {
	readonly errors: number[] = [];
	readonly spoken: string[] = [];
	readonly copied: string[] = [];
	readonly engine: JustTypeEngine;

	constructor(options: Partial<EngineOptions> = {}) {
		this.engine = new JustTypeEngine({
			data: english,
			now: () => 1_800_000_000_000,
			events: {
				onError: () => this.errors.push(1),
				onSpeak: (t) => this.spoken.push(t),
				onCopy: (t) => this.copied.push(t),
			},
			...options,
		});
	}

	get snap() {
		return this.engine.getSnapshot();
	}

	get outputs(): string[] {
		return this.snap.list.flatMap((i) => ("output" in i ? [i.output] : []));
	}

	press(...slots: number[]): this {
		for (const slot of slots) this.engine.press(slot);
		return this;
	}

	/** Types the keys for `word` without selecting it. */
	keys(word: string): this {
		const seq = this.engine.keySequenceFor(word);
		if (!seq) throw new Error(`untypeable: ${word}`);
		return this.press(...seq.map((k) => LETTER_SLOTS[k]));
	}

	/** Selects the list row whose output is `output`: by Select, or by a page pick (which commits it). */
	choose(output: string): this {
		for (let guard = 0; guard < 40; guard++) {
			const { list, selection, pagedRows, keys } = this.snap;
			const index = list.findIndex((i) => "output" in i && i.output === output);
			if (index < 0) throw new Error(`'${output}' not offered: ${list.map((i) => i.display).join(", ")}`);
			if (pagedRows && index >= pagedRows[0] && index < pagedRows[1]) {
				return this.press(keys.findIndex((k) => k.kind === "word" && k.label === list[index].display));
			}
			if (!pagedRows && selection === index) return this;
			this.press(SELECT);
		}
		throw new Error(`could not reach '${output}'`);
	}

	/** Types `word` and selects it. */
	word(word: string): this {
		return this.keys(word).choose(word);
	}

	/** Presses the function key labelled `label` on the current page. */
	fn(label: string): this {
		const slot = this.snap.keys.findIndex((k) => k.label?.replace(/\n/g, " ") === label);
		if (slot < 0) throw new Error(`no key '${label}' on ${this.snap.page}`);
		return this.press(slot);
	}

	/** Opens a list-function page: the key carrying `badge`, then Select. */
	open(badge: "SYM" | "FNS" | "MENU"): this {
		return this.press(
			this.snap.keys.findIndex((k) => k.badge === badge),
			SELECT,
		);
	}

	/** On the spelling page: picks each character of `text`. */
	spell(text: string): this {
		for (const ch of text) {
			const group = this.engine.keySequenceFor(ch)?.[0];
			if (group === undefined) throw new Error(`cannot spell '${ch}'`);
			this.press(LETTER_SLOTS[group]).fn(ch);
		}
		return this;
	}
}
