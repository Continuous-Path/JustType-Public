package org.continuouspath.justtype.logic

import kotlin.math.exp

/**
 * NGB-D selection-confidence model (docs/.plans/ngram/plan.md, "NGB-D").
 *
 * Estimates p-hat = P(top selection-list item == the user's intended word)
 * at every keystroke, from engine-observable features only: the top item's
 * posterior share of the alive candidate field (all scores on the corpus
 * count scale), keystroke count, whether the top is an NGB prediction, and
 * context validity. Initial weights were fitted offline in the analyzer
 * simulator (tools/ngb_confidence.py, fts-replay emission; eval AUC 0.885,
 * calibrated within ~2 points across [0, 0.99]) — so the user's threshold
 * maps to a real probability. Every commit provides a free label (did the
 * final commit match the top?), driving on-device SGD personalization.
 * Weights only — no text is ever stored.
 *
 * Adaptive theta (mechanism B, shadow placement — Cliff + Claude
 * 2026-08-12): at every commit the word's snapshot history is replayed
 * against the whole candidate-threshold grid with the live fire-suppression
 * rules, and each would-have-fired signal is CONFIRMED correct or incorrect
 * by the committed word — evidence that needs no user reaction, so the
 * counters accrue even while the signal is hidden. Theta is then PLACED,
 * not walked: the lowest evidenced threshold whose observed precision meets
 * the user's target. Cold start seeds from the offline sweep's measured
 * precision-per-threshold table.
 *
 * Pure logic, no Android dependencies (testable like NgbRecognizer).
 */
class NgbConfidence(
	initialWeights: DoubleArray = DEFAULT_WEIGHTS.copyOf(),
) {
	// [posterior, kcount, isPred, ctxValid] + bias — order fixed by FEATURE_KEYS.
	private val weights: DoubleArray = initialWeights.copyOf()
	private val reference: DoubleArray = initialWeights.copyOf()

	private class Snapshot(
		val features: DoubleArray,
		val phat: Double,
		val topLower: String,
		val keystrokes: Int,
	)

	private val snapshots = ArrayList<Snapshot>()
	private var lastSignaledTop: String? = null

	// Tops the signal fired on this word — the honesty ledger: each one either
	// becomes the committed word (a save) or a distraction (Cliff's
	// full-disclosure counter pair).
	private val signaledTops = ArrayList<String>()

	// Shadow-theta counters: per candidate threshold, EWMA-decayed
	// (would-have-fired, would-have-been-correct) masses from the commit-time
	// replay. These ARE the adaptive-theta state; theta itself is derived.
	private val shadowFires = DoubleArray(THETA_GRID.size)
	private val shadowCorrect = DoubleArray(THETA_GRID.size)

	/** Commit outcome: keystrokes the signal could have saved, and signals
	 *  whose top was NOT what the user finally committed (distractions). */
	data class CommitOutcome(val savedKeystrokes: Int, val distractedSignals: Int)

	/** p-hat for one list state. */
	fun phat(posterior: Double, keystrokes: Int, topIsPrediction: Boolean, ctxValid: Boolean): Double = sigmoid(dot(featureVector(posterior, keystrokes, topIsPrediction, ctxValid)))

	/**
	 * Record one per-keystroke list state and decide whether the signal fires.
	 * Fires when p-hat >= [threshold] and the top differs from the last top
	 * already signaled for this word (the false-alarm suppression the
	 * simulator sweep measured: quiet until the top CHANGES).
	 */
	fun observe(
		posterior: Double,
		keystrokes: Int,
		topIsPrediction: Boolean,
		ctxValid: Boolean,
		topLower: String,
		threshold: Double,
	): Boolean {
		val last = snapshots.lastOrNull()
		if (last != null && last.keystrokes == keystrokes && last.topLower == topLower) {
			return false // same state re-rendered (SEL cycling, UI refresh) — not a new observation
		}
		val x = featureVector(posterior, keystrokes, topIsPrediction, ctxValid)
		val p = sigmoid(dot(x))
		if (snapshots.size < WORD_SNAPSHOT_MAX) {
			snapshots.add(Snapshot(x, p, topLower, keystrokes))
		}
		if (p < threshold || topLower == lastSignaledTop) return false
		lastSignaledTop = topLower
		signaledTops.add(topLower)
		return true
	}

	/**
	 * The word is committed: label every recorded state, update the shadow
	 * counters (the counterfactual replay — runs on every learning commit,
	 * hidden signal included, since the commit itself confirms or refutes
	 * every would-have-fired top), optionally apply the SGD update ([learn]
	 * false in password/sensitive fields; [sgd] false while adaptive theta
	 * is on, so drift stays attributable to one mechanism), and return the
	 * could-have-saved keystroke count — keystrokes typed AFTER the earliest
	 * state whose p-hat cleared [threshold] with the committed word on top.
	 */
	fun onCommit(
		committed: String,
		threshold: Double,
		learn: Boolean,
		sgd: Boolean = true,
	): CommitOutcome {
		val committedLower = committed.trim().lowercase()
		var saved = 0
		if (snapshots.isNotEmpty()) {
			val lastK = snapshots.maxOf { it.keystrokes }
			val firstConfident = snapshots.firstOrNull {
				it.phat >= threshold && it.topLower == committedLower
			}
			if (firstConfident != null) saved = (lastK - firstConfident.keystrokes).coerceAtLeast(0)
			if (learn) learnFromCommit(committedLower, sgd)
		}
		val distracted = signaledTops.count { it != committedLower }
		reset()
		return CommitOutcome(saved, distracted)
	}

	private fun learnFromCommit(committedLower: String, sgd: Boolean) {
		shadowReplay(committedLower)
		if (!sgd) return
		for (s in snapshots) {
			sgdStep(s, if (s.topLower == committedLower) 1.0 else 0.0)
		}
	}

	/** Replay this word's states against every candidate threshold with the
	 *  live fire rule (first crossing fires, then quiet until the top
	 *  changes); each would-fire resolves against the committed word. */
	private fun shadowReplay(committedLower: String) {
		for (i in THETA_GRID.indices) {
			shadowFires[i] *= SHADOW_DECAY
			shadowCorrect[i] *= SHADOW_DECAY
			var lastFired: String? = null
			for (s in snapshots) {
				if (s.phat < THETA_GRID[i] || s.topLower == lastFired) continue
				lastFired = s.topLower
				shadowFires[i] += 1.0
				if (s.topLower == committedLower) shadowCorrect[i] += 1.0
			}
		}
	}

	/**
	 * The placed theta for [precisionTarget]: the lowest candidate threshold
	 * with enough would-fire evidence whose observed precision meets the
	 * target. Evidenced bins that all miss the target place at the quiet
	 * top; no evidence at all falls back to the sweep-measured seed.
	 */
	fun thetaFor(precisionTarget: Double): Double {
		val p = precisionTarget.coerceIn(PRECISION_TARGET_MIN, PRECISION_TARGET_MAX)
		var anyEvidence = false
		for (i in THETA_GRID.indices) {
			if (shadowFires[i] < SHADOW_EVIDENCE_FLOOR) continue
			anyEvidence = true
			if (shadowCorrect[i] / shadowFires[i] >= p) return THETA_GRID[i]
		}
		return if (anyEvidence) THETA_MAX else seedThetaFor(p)
	}

	/** Word abandoned without a commit (field change, reset, pull-in). */
	fun reset() {
		snapshots.clear()
		signaledTops.clear()
		lastSignaledTop = null
	}

	fun exportWeights(): Map<String, Double> = buildMap {
		FEATURE_KEYS.forEachIndexed { i, k -> put(k, weights[i]) }
		THETA_GRID.forEachIndexed { i, t ->
			put(shadowKey(t, "f"), shadowFires[i])
			put(shadowKey(t, "c"), shadowCorrect[i])
		}
	}

	/** Restore persisted weights + shadow counters (e.g. at language
	 *  switch). Starts from the fitted defaults / empty counters, then
	 *  applies saved values — missing and unknown keys fall back cleanly. */
	fun importWeights(saved: Map<String, Double>) {
		FEATURE_KEYS.forEachIndexed { i, k ->
			weights[i] = saved[k]?.let { clamp(it, reference[i]) } ?: reference[i]
		}
		THETA_GRID.forEachIndexed { i, t ->
			shadowFires[i] = (saved[shadowKey(t, "f")] ?: 0.0).coerceAtLeast(0.0)
			shadowCorrect[i] = (saved[shadowKey(t, "c")] ?: 0.0).coerceIn(0.0, shadowFires[i])
		}
	}

	private fun sgdStep(s: Snapshot, label: Double) {
		val p = sigmoid(dot(s.features))
		val g = LEARNING_RATE * (label - p)
		for (i in weights.indices) {
			weights[i] = clamp(weights[i] + g * s.features[i], reference[i])
		}
	}

	// Personalization stays anchored: each weight may drift at most
	// WEIGHT_DRIFT_LIMIT from its fitted initial, so no input pattern can
	// push the model into nonsense (or a beep storm) that re-learning
	// couldn't walk back.
	private fun clamp(v: Double, ref: Double): Double = v.coerceIn(ref - WEIGHT_DRIFT_LIMIT, ref + WEIGHT_DRIFT_LIMIT)

	private fun featureVector(posterior: Double, keystrokes: Int, topIsPrediction: Boolean, ctxValid: Boolean) = doubleArrayOf(
		posterior.coerceIn(0.0, 1.0),
		keystrokes.coerceAtMost(KCOUNT_CAP).toDouble(),
		if (topIsPrediction) 1.0 else 0.0,
		if (ctxValid) 1.0 else 0.0,
		1.0, // bias
	)

	private fun dot(x: DoubleArray): Double {
		var z = 0.0
		for (i in x.indices) z += weights[i] * x[i]
		return z
	}

	private fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))

	companion object {
		/** Fitted offline 2026-08-12 on the TiengViet v12 table + corrected
		 *  accounting (analyzer runs/hplt/conf_weights_5_v12.json, AUC 0.8775). */
		val DEFAULT_WEIGHTS = doubleArrayOf(2.9038, 0.7308, 4.9125, 0.4893, -8.7774)

		val FEATURE_KEYS = listOf("posterior", "kcount", "is_pred", "ctx_valid", "bias")

		// ── Adaptive theta (mechanism B, shadow placement) ──
		// Grid ends are measured (2026-08-12 corrected-accounting sweep):
		// 0.45 = the DKWF=1.0 net-utility optimum, 0.90 near-silent.
		val THETA_GRID = doubleArrayOf(0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90)
		const val THETA_MIN = 0.45
		const val THETA_MAX = 0.90
		const val PRECISION_TARGET_MIN = 0.60
		const val PRECISION_TARGET_MAX = 0.95

		/** Per-bin observed fire precision from the same sweep
		 *  (runs/hplt/conf_sweep_v12.txt top-hit%), the cold-start seed:
		 *  the lowest threshold whose MEASURED precision meets the target. */
		val SEED_PRECISION = doubleArrayOf(0.612, 0.651, 0.695, 0.729, 0.766, 0.801, 0.841, 0.871, 0.905, 0.939)

		fun seedThetaFor(precisionTarget: Double): Double {
			val i = SEED_PRECISION.indexOfFirst { it >= precisionTarget }
			return if (i >= 0) THETA_GRID[i] else THETA_MAX
		}

		private fun shadowKey(theta: Double, suffix: String): String = "sh${(theta * 100).toInt()}_$suffix"

		/** EWMA decay per learning commit: an effective evidence window of
		 *  ~1000 words, so placement tracks register shifts without jitter. */
		const val SHADOW_DECAY = 0.999

		/** Minimum decayed would-fire mass before a bin's precision estimate
		 *  is trusted (binomial noise at 25 fires ~ +-8 points, tolerable for
		 *  a floor that only tightens with use). */
		const val SHADOW_EVIDENCE_FLOOR = 25.0

		/** Feature cap matching the simulator's emission (min(k, 8)). */
		const val KCOUNT_CAP = 8

		const val LEARNING_RATE = 0.01
		const val WEIGHT_DRIFT_LIMIT = 3.0

		/** Per-word snapshot cap — beyond this the word is long enough that
		 *  further states add nothing to labeling. */
		const val WORD_SNAPSHOT_MAX = 12
	}
}
