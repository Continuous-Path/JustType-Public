/** What the engine learns about its user, and where it is kept. */
import type { UserBigrams } from "./ngram";

export interface PersistedState {
	/** Word surface -> [use count, last use (epoch seconds)]. */
	usage: Record<string, [number, number]>;
	/** Words added through ADD NEW WORD. */
	userWords: string[];
	userBigrams: UserBigrams;
	phrases: Phrase[];
}

/** A stored phrase, typed by its abbreviation. */
export interface Phrase {
	id: string;
	abbreviation: string;
	phrase: string;
}

export interface EngineStore {
	load(): PersistedState | null;
	save(state: PersistedState): void;
}

/** A prototype-free map: vocabulary words such as "constructor" must not hit `Object.prototype`. */
function dict<T>(source: Record<string, T> = {}): Record<string, T> {
	return Object.assign(Object.create(null), source);
}

/** Rebuilds loaded (plain JSON) state on prototype-free maps, tolerating missing fields. */
export function normalizeState(state: Partial<PersistedState> | null): PersistedState {
	const userBigrams = dict<Record<string, number>>();
	for (const [ctx, row] of Object.entries(state?.userBigrams ?? {})) userBigrams[ctx] = dict(row);
	return {
		usage: dict(state?.usage),
		userWords: [...(state?.userWords ?? [])],
		userBigrams,
		phrases: [...(state?.phrases ?? [])],
	};
}

export function newRow(): Record<string, number> {
	return dict();
}

/** `localStorage`-backed store. Storage failures (private mode, quota) degrade to no learning. */
export function localStorageStore(key = "justtype.v1"): EngineStore {
	return {
		load() {
			try {
				const raw = localStorage.getItem(key);
				return raw ? JSON.parse(raw) : null;
			} catch {
				return null;
			}
		},
		save(state) {
			try {
				localStorage.setItem(key, JSON.stringify(state));
			} catch {
				// Not persisted; the in-memory state still applies for this session.
			}
		},
	};
}
