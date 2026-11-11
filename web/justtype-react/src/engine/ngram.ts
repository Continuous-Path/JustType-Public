/** Next-word prediction pool — a single-word subset of the app's `NgbEngine`. */

/** Context key for a sentence start (the literal two characters backslash + n). */
export const BOS = "\\n";
export const POOL_SIZE = 60;
export const BLOCK_SIZE = 4;
export const ZERO_K_SIZE = 8;
/** Effective-count boost per use of a follower by this user. */
export const USER_BOOST = 250_000;

const SENTENCE_FINAL = ".!?…";

export interface Prediction {
	word: string;
	eff: number;
	/** Ranked beyond the pool cap: offered only once fully typed. */
	deep: boolean;
}

export type UserBigrams = Record<string, Record<string, number>>;

export class NgramModel {
	private readonly rows = new Map<string, string>();

	/** `rows` is newline-separated `ctx<TAB>word:eff|word:eff|...`. */
	constructor(rows: string) {
		for (const line of rows.split("\n")) {
			const tab = line.indexOf("\t");
			if (tab > 0) this.rows.set(line.slice(0, tab), line.slice(tab + 1));
		}
	}

	poolFor(ctx: string | null, user: UserBigrams): Prediction[] {
		if (ctx === null) return [];
		const merged = new Map<string, number>();
		for (const part of this.rows.get(ctx)?.split("|") ?? []) {
			const colon = part.lastIndexOf(":");
			const eff = Number(part.slice(colon + 1));
			if (colon > 0 && Number.isFinite(eff)) merged.set(part.slice(0, colon), eff);
		}
		for (const [word, count] of Object.entries(user[ctx] ?? {})) {
			merged.set(word, (merged.get(word) ?? 0) + count * USER_BOOST);
		}
		return [...merged.entries()]
			.sort((a, b) => b[1] - a[1])
			.map(([word, eff], i) => ({ word, eff, deep: i >= POOL_SIZE }));
	}
}

/**
 * The prediction context for text ending at the cursor: {@link BOS} at a sentence start,
 * the last word after a completed word, or null when nothing sensible precedes.
 */
export function contextFor(text: string): string | null {
	const trimmed = text.trimEnd();
	if (trimmed === "") return BOS;
	if (SENTENCE_FINAL.includes(trimmed[trimmed.length - 1])) return BOS;
	const match = /[\p{L}'’]+$/u.exec(trimmed);
	return match ? match[0].toLowerCase().replace("’", "'") : null;
}
