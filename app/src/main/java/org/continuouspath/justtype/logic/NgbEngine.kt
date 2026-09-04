package org.continuouspath.justtype.logic

/**
 * NGB prediction engine (docs/.plans/ngram/engine-spec.md).
 *
 * One fetch per COMMITTED WORD — never per keystroke: the merged pool for a
 * context syllable is built once (single ngb_ctx seek + an indexed 1N1 query)
 * and then filtered against typed keys in memory by the caller.
 *
 * Sources, in Cliff's taxonomy:
 *  - 1N2 + 1N3: the packed ngb_ctx row — targets (units or syllables) that
 *    FOLLOW the context, rank-merged at build time.
 *  - 1N1: units whose FIRST syllable is the context — the user may be typing
 *    a multi-syllable word syllable-by-syllable, so its REMAINDER is a valid
 *    continuation. Excluded when the context arrived as the final syllable
 *    of a completed word (the entry-state gate: it cannot also be a word
 *    start).
 *
 * All weights are effective counts on the language-DB scale, precomputed at
 * langpack build; the engine does no probability math.
 */
class NgbEngine(
	private val wordDb: WordDb,
	// User-learned tier (custom DB) + its language scope; null = static only.
	private val customDb: WordDb? = null,
	private val lang: String = "",
) {

	/** One pool entry. [syls] is the OUTPUT (a remainder for 1N1 entries). */
	data class Prediction(
		val syls: List<String>,
		val eff: Long,
		// True when selecting this entry emits more than one syllable.
		val multi: Boolean = false,
		// Set by the learned tier (Phase C): the user has used this before.
		val userUsed: Boolean = false,
		// Canonical orthography when it differs from lowercase ("Hồ Chí Minh")
		// — proper-noun units carry their dictionary case (Kaikki).
		val displaySyls: List<String> = syls,
		// Row rank beyond POOL_SIZE: alive only once FULLY TYPED (the
		// dual-source row surfaces exactly when letter certainty completes).
		val deep: Boolean = false,
	)

	/** True when the active language DB ships NGB data. Cached after first read. */
	val hasData: Boolean by lazy {
		runCatching { wordDb.ngbHasData() }.getOrDefault(false)
	}

	// lowercase unit -> canonical display; proper nouns only, loaded once.
	private val displayOverrides: Map<String, String> by lazy {
		runCatching { wordDb.ngbUnitDisplayOverrides() }.getOrDefault(emptyMap())
	}

	/**
	 * The merged, rank-ordered prediction pool for [ctx] (<= [POOL_SIZE]).
	 * [gateOpen] false = entry-state gate CLOSED (context was the final
	 * syllable of a completed word): the 1N1 source is skipped.
	 */
	fun poolFor(ctx: String?, gateOpen: Boolean): List<Prediction> {
		if (ctx == null || !hasData) return emptyList()
		val merged = HashMap<List<String>, Long>()
		// Format handling (v2 varint blob vs legacy text) lives in WordDb; the
		// engine sees rank-ordered (target, eff) pairs either way. Targets keep
		// their syllable split — key sequences are derived per syllable at
		// refresh (the tone-position hazard), so the surface must never be
		// stored pre-joined.
		wordDb.ngbContextTargets(ctx)?.forEach { (target, eff) ->
			merged.merge(target.split(' '), eff, ::maxOf)
		}
		// 1N1 remainders inherit their PARENT unit's canonical case: the
		// remainder of "Thành phố Hồ Chí Minh" is "phố Hồ Chí Minh" even
		// though the remainder itself is no unit.
		val remDisplay = HashMap<List<String>, List<String>>()
		if (gateOpen) {
			for (unit in wordDb.ngbUnitsByFirstSyl(ctx)) {
				mergeRemainder(unit, merged, remDisplay)
			}
		}
		// User tier: ADDITIVE on top of the static effective count — one use
		// outweighs thousands of corpus counts for that context (USER_BOOST is
		// the beta of the engine spec; simulator learning experiment to tune).
		val used = HashSet<List<String>>()
		customDb?.ngbUserRows(lang, ctx)?.forEach { (target, count) ->
			val syls = target.split(' ')
			merged[syls] = (merged[syls] ?: 0L) + count.toLong() * USER_BOOST
			used.add(syls)
		}
		// Top POOL_SIZE as before; single-word targets BEYOND the cap are
		// retained too, flagged deep — they join the alive set only once
		// FULLY TYPED ("a deep-ranked follower matches the prefix exactly",
		// the pool-cap catch; Cliff's "also say" incident: say at row rank
		// 84 lost its contextual row to the fetch cut). Deep units stay
		// excluded (sim-measured VN loss via multi-token commits + gate).
		return merged.entries.sortedByDescending { it.value }
			.withIndex()
			.filter { (i, entry) -> i < POOL_SIZE || entry.key.size == 1 }
			.map { (i, entry) ->
				val display = displayOverrides[entry.key.joinToString(" ")]?.split(' ')
					?: remDisplay[entry.key]
				Prediction(
					entry.key,
					entry.value,
					multi = entry.key.size > 1,
					userUsed = entry.key in used,
					displaySyls = display ?: entry.key,
					deep = i >= POOL_SIZE,
				)
			}
	}

	private fun mergeRemainder(
		unit: WordDb.NgbUnit,
		merged: HashMap<List<String>, Long>,
		remDisplay: HashMap<List<String>, List<String>>,
	) {
		val remainder = unit.syls.split(' ').drop(1)
		if (remainder.isEmpty()) return
		merged.merge(remainder, unit.eff1n1, ::maxOf)
		if (unit.display != unit.syls) {
			remDisplay.putIfAbsent(remainder, unit.display.split(' ').drop(1))
		}
	}

	companion object {
		const val POOL_SIZE = 60

		/** Slots the prediction block occupies above the letter-exact word
		 *  (anchor=0 per the slot-budget sweep; size is a display choice). */
		const val BLOCK_SIZE = 4

		/** Zero-keystroke window size at word start. */
		const val ZERO_K_SIZE = 8

		/** Effective-count boost per user-tier use (~ a strong corpus bigram
		 *  per use on the WordsRaw scale). Tunable, pending the simulator's
		 *  learning experiment. */
		const val USER_BOOST = 250_000L
	}
}
