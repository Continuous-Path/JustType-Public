/** Candidate ordering — the app's sort metric and list assembly (lower metric = earlier). */
import type { Candidate } from "./wld";

const FREQ_ADD = 1.0;
const FREQ_MULT = 1.25;
const SEQ_ADD = 1.0;
const SEQ_MULT = 1.0;
const USE_ADD = 3.0;
const USE_MULT = 1.15;
const RECENCY_ADD = 2.0;
const RECENCY_MULT = 1.75;

/** A never-used top-band fully-typed word is never outranked by an incomplete one. */
export const COLD_FTS_FLOOR = 380.0;
export const FREQ_SLOPE = FREQ_ADD * FREQ_MULT;
export const LIST_BUDGET = 8;

export interface MetricInput {
	freqClass: number;
	useCount: number;
	lastUse: number;
	keysRemaining: number;
}

/** Recency bucket 0 (just used) .. 6 (ancient); 7 = never used. */
function recencyClass(lastUse: number, nowSec: number): number {
	if (lastUse === 0) return 7;
	const minutes = (nowSec - lastUse) / 60;
	const bounds = [15, 150, 1500, 15000, 150000, 1500000];
	const i = bounds.findIndex((b) => minutes <= b);
	return i === -1 ? 6 : i;
}

export function sortMetric(c: MetricInput, nowSec: number): number {
	const fc = c.freqClass - 1;
	const freq = fc * FREQ_ADD * FREQ_MULT ** fc;
	const delta = Math.max(c.keysRemaining, 0);
	const seq = delta * SEQ_ADD * SEQ_MULT ** delta;
	const useFactor = c.useCount >= 7 ? 0 : c.useCount === 0 ? 3.5 : Math.trunc((7 - c.useCount) / 2);
	let use = useFactor * USE_ADD * USE_MULT ** useFactor;
	const rc = recencyClass(c.lastUse, nowSec);
	let recency = rc * RECENCY_ADD * RECENCY_MULT ** rc;
	if (c.freqClass === 1) {
		use = 0;
		recency /= 2;
	} else {
		use /= 2;
	}
	const coldFloor = c.freqClass === 1 && c.keysRemaining === 0 && c.useCount === 0 ? -COLD_FTS_FLOOR : 0;
	return freq + seq + use + recency + coldFloor;
}

/**
 * Orders candidates by metric and trims the list: every fully-typed word is kept (nothing
 * more can be typed to reach it), and completions only fill up to {@link LIST_BUDGET}.
 */
export function assemble(candidates: Candidate[], nowSec: number): Candidate[] {
	const sorted = candidates
		.map((c) => ({ c, m: sortMetric({ ...c.entry, freqClass: c.freqClass, keysRemaining: c.keysRemaining }, nowSec) }))
		.sort((a, b) => a.m - b.m)
		.map((x) => x.c);
	const exactCount = sorted.filter((c) => c.keysRemaining === 0).length;
	let fill = Math.max(LIST_BUDGET - exactCount, 0);
	return sorted.filter((c) => c.keysRemaining === 0 || fill-- > 0);
}
