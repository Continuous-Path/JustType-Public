/** The message being composed: text, a cursor, an optional selection and an edit history. */

const WORD_CHAR = /[\p{L}\p{N}'’-]/u;
const SENTENCE_END = ".!?…\n";
const MAX_HISTORY = 50;

export type Direction = "left" | "right" | "up" | "down";

/** What a cursor key moves by: [left/right unit, up/down unit]. */
export enum MoveMode {
	CharacterLine = 0,
	WordSentence = 1,
	ParagraphAll = 2,
}

interface Checkpoint {
	before: string;
	after: string;
}

export class TextBuffer {
	/** Text before / after the cursor. */
	before = "";
	after = "";
	/** The fixed end of the selection (absolute index), or null. */
	anchor: number | null = null;
	private history: Checkpoint[] = [];

	constructor(text = "") {
		this.before = text;
	}

	get text(): string {
		return this.before + this.after;
	}

	get cursor(): number {
		return this.before.length;
	}

	set(text: string, cursor = text.length): void {
		const at = Math.max(0, Math.min(cursor, text.length));
		this.before = text.slice(0, at);
		this.after = text.slice(at);
		this.anchor = null;
	}

	/** The selected range as [start, end), or null. */
	selection(): [number, number] | null {
		if (this.anchor === null || this.anchor === this.cursor) return null;
		return [Math.min(this.anchor, this.cursor), Math.max(this.anchor, this.cursor)];
	}

	selectedText(): string {
		const range = this.selection();
		return range ? this.text.slice(range[0], range[1]) : "";
	}

	/** Saves the current text so {@link undo} can return to it. */
	checkpoint(): void {
		this.history.push({ before: this.before, after: this.after });
		if (this.history.length > MAX_HISTORY) this.history.shift();
	}

	undo(): boolean {
		const previous = this.history.pop();
		if (!previous) return false;
		this.before = previous.before;
		this.after = previous.after;
		this.anchor = null;
		return true;
	}

	replaceRange(start: number, end: number, replacement: string): void {
		const text = this.text;
		this.before = text.slice(0, start) + replacement;
		this.after = text.slice(end);
		this.anchor = null;
	}

	deleteSelection(): boolean {
		const range = this.selection();
		if (!range) return false;
		this.replaceRange(range[0], range[1], "");
		return true;
	}

	/** Inserts at the cursor, replacing any selection. */
	insert(s: string): void {
		this.deleteSelection();
		this.anchor = null;
		this.before += s;
	}

	/** Deletes the selection, or the character before the cursor. */
	backspace(): boolean {
		if (this.deleteSelection()) return true;
		if (this.before === "") return false;
		this.before = Array.from(this.before).slice(0, -1).join("");
		return true;
	}

	deleteWord(): boolean {
		if (this.deleteSelection()) return true;
		if (this.before === "") return false;
		this.before = this.before.replace(/\s*\S+\s*$/, "");
		return true;
	}

	moveTo(index: number, extend = false): void {
		const from = this.cursor;
		const text = this.text;
		const at = Math.max(0, Math.min(index, text.length));
		this.before = text.slice(0, at);
		this.after = text.slice(at);
		this.anchor = extend ? (this.anchor ?? from) : null;
	}

	move(direction: Direction, mode: MoveMode, extend = false): void {
		this.moveTo(this.target(direction, mode), extend);
	}

	private target(direction: Direction, mode: MoveMode): number {
		const text = this.text;
		const at = this.cursor;
		const back = direction === "left" || direction === "up";
		const horizontal = direction === "left" || direction === "right";
		if (mode === MoveMode.CharacterLine) {
			if (horizontal) return back ? at - (Array.from(this.before).pop()?.length ?? 0) : at + (Array.from(this.after)[0]?.length ?? 0);
			return this.lineTarget(back);
		}
		if (mode === MoveMode.WordSentence) {
			if (horizontal) return back ? this.wordStartBefore(at) : this.wordEndAfter(at);
			return back ? this.boundaryBefore(at, SENTENCE_END) : this.boundaryAfter(at, SENTENCE_END);
		}
		if (horizontal) return back ? this.boundaryBefore(at, "\n") : this.boundaryAfter(at, "\n");
		return back ? 0 : text.length;
	}

	/** Same column on the previous / next hard line (soft wrapping is the view's business). */
	private lineTarget(up: boolean): number {
		const text = this.text;
		const lineStart = this.before.lastIndexOf("\n") + 1;
		const column = this.cursor - lineStart;
		if (up) {
			if (lineStart === 0) return 0;
			const previousStart = text.lastIndexOf("\n", lineStart - 2) + 1;
			return Math.min(previousStart + column, lineStart - 1);
		}
		const lineEnd = text.indexOf("\n", this.cursor);
		if (lineEnd === -1) return text.length;
		const nextEnd = text.indexOf("\n", lineEnd + 1);
		return Math.min(lineEnd + 1 + column, nextEnd === -1 ? text.length : nextEnd);
	}

	private wordStartBefore(at: number): number {
		const text = this.text;
		let i = at;
		while (i > 0 && !WORD_CHAR.test(text[i - 1])) i--;
		while (i > 0 && WORD_CHAR.test(text[i - 1])) i--;
		return i;
	}

	private wordEndAfter(at: number): number {
		const text = this.text;
		let i = at;
		while (i < text.length && !WORD_CHAR.test(text[i])) i++;
		while (i < text.length && WORD_CHAR.test(text[i])) i++;
		return i;
	}

	/** Start of the unit (sentence / paragraph) before `at`: just past the previous terminator run. */
	private boundaryBefore(at: number, terminators: string): number {
		const text = this.text;
		let i = at;
		// Step over the terminator and spacing that end the unit the cursor sits after.
		while (i > 0 && (terminators.includes(text[i - 1]) || /\s/.test(text[i - 1]))) i--;
		while (i > 0 && !terminators.includes(text[i - 1])) i--;
		while (i < at && /\s/.test(text[i])) i++;
		return i;
	}

	/** Start of the unit after `at`, or the end of the text. */
	private boundaryAfter(at: number, terminators: string): number {
		const text = this.text;
		let i = at;
		while (i < text.length && !terminators.includes(text[i])) i++;
		while (i < text.length && (terminators.includes(text[i]) || /\s/.test(text[i]))) i++;
		return i;
	}

	/** The word touching the cursor as [start, end), or null. */
	wordRangeAtCursor(): [number, number] | null {
		const text = this.text;
		let start = this.cursor;
		let end = this.cursor;
		while (start > 0 && WORD_CHAR.test(text[start - 1])) start--;
		while (end < text.length && WORD_CHAR.test(text[end])) end++;
		return start === end ? null : [start, end];
	}

	/**
	 * The sentence around the cursor as [start, end). A cursor resting after a terminator
	 * (and any spacing) still belongs to the sentence it has just ended.
	 */
	sentenceRangeAtCursor(): [number, number] {
		const text = this.text;
		let start = this.cursor;
		while (start > 0 && (SENTENCE_END.includes(text[start - 1]) || /\s/.test(text[start - 1]))) start--;
		while (start > 0 && !SENTENCE_END.includes(text[start - 1])) start--;
		while (start < text.length && /\s/.test(text[start])) start++;
		let end = start;
		while (end < text.length && !SENTENCE_END.includes(text[end])) end++;
		while (end < text.length && SENTENCE_END.includes(text[end]) && text[end] !== "\n") end++;
		return [start, end];
	}
}
