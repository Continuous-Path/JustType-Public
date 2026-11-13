/** Text-buffer rules: automatic spacing, sentence boundaries, and case forms. */
import type { WordEntry } from "./wld";

const NO_SPACE_AFTER = "([{@#$/_-";
const SENTENCE_END = ".!?…\n";

/** True when the next word must be separated from `text` by a space. */
export function needsSpace(text: string): boolean {
	if (text === "") return false;
	const last = text[text.length - 1];
	if (/\s/.test(last) || NO_SPACE_AFTER.includes(last)) return false;
	// A quote mark only takes a following space when it closes a quotation.
	if (last === '"') return isQuoteClosed(text);
	return true;
}

/** True when every `"` in `text` is paired. */
export function isQuoteClosed(text: string): boolean {
	return (text.split('"').length - 1) % 2 === 0;
}

/** True at the start of the text and after sentence-final punctuation or a line break. */
export function atSentenceStart(text: string): boolean {
	return /(^|[.!?…\n])["')\]]*\s*$/.test(text);
}

/** The sentence the cursor is in (or has just ended). */
export function lastSentence(text: string): string {
	const trimmed = text.trimEnd();
	let start = 0;
	for (let i = trimmed.length - 2; i >= 0; i--) {
		if (SENTENCE_END.includes(trimmed[i])) {
			start = i + 1;
			break;
		}
	}
	return trimmed.slice(start).trim();
}

export function capitalize(word: string): string {
	return word.charAt(0).toUpperCase() + word.slice(1);
}

/** A word's output forms, preferred first, per its case type. */
export function caseForms(entry: WordEntry): string[] {
	const title = capitalize(entry.lower);
	switch (entry.caseType) {
		case "LF":
			return [entry.lower, title];
		case "TF":
			return [title, entry.lower];
		case "TO":
		case "AC":
		case "OC":
			return [entry.surface];
		default:
			return [entry.lower];
	}
}

export function applyShift(output: string, shift: boolean, capsLock: boolean): string {
	if (capsLock) return output.toUpperCase();
	return shift ? capitalize(output) : output;
}
