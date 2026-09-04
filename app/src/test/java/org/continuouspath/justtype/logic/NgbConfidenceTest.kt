package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * NGB-D confidence model unit tests (docs/.plans/ngram/plan.md "NGB-D"):
 * the fitted logistic, per-word signal suppression, commit labeling with
 * could-have-saved accounting, SGD personalization bounds, and persistence.
 */
class NgbConfidenceTest {

	@Test
	fun `phat matches the offline-fitted logistic`() {
		val c = NgbConfidence()
		// z = 2.9038*0.9 + 0.7308*2 + 4.9125 + 0.4893 - 8.7774 = 0.69942
		assertThat(c.phat(0.9, 2, topIsPrediction = true, ctxValid = true))
			.isWithin(1e-3).of(0.6680)
		// A weak FTS-only state is near-certainly not the intended stop point.
		assertThat(c.phat(0.05, 1, topIsPrediction = false, ctxValid = false)).isLessThan(0.01)
	}

	@Test
	fun `signal fires once per top and re-arms when the top changes`() {
		val c = NgbConfidence()
		assertThat(c.observe(0.95, 2, true, true, "mừng", 0.5)).isTrue()
		// Same top at the next keystroke: suppressed (quiet until the top changes).
		assertThat(c.observe(0.95, 3, true, true, "mừng", 0.5)).isFalse()
		// New top clearing the threshold: fires again.
		assertThat(c.observe(0.95, 4, true, true, "khỏe", 0.5)).isTrue()
		// The earlier top coming back is a NEW top relative to the last signal.
		assertThat(c.observe(0.95, 5, true, true, "mừng", 0.5)).isTrue()
	}

	@Test
	fun `re-rendering the same state is not a new observation`() {
		val c = NgbConfidence()
		assertThat(c.observe(0.95, 2, true, true, "mừng", 0.5)).isTrue()
		// Same keystroke count + same top (SEL cycling, UI refresh): ignored.
		assertThat(c.observe(0.95, 2, true, true, "mừng", 0.5)).isFalse()
	}

	@Test
	fun `below-threshold states never fire`() {
		val c = NgbConfidence()
		assertThat(c.observe(0.10, 1, false, true, "mừng", 0.5)).isFalse()
	}

	@Test
	fun `commit counts keystrokes typed after the earliest confident matching state`() {
		val c = NgbConfidence()
		c.observe(0.95, 2, true, true, "mừng", 0.5) // confident, correct top
		c.observe(0.60, 3, true, true, "mừng", 0.5)
		c.observe(0.70, 4, true, true, "mừng", 0.5)
		val outcome = c.onCommit("Mừng", 0.5, learn = false)
		assertThat(outcome.savedKeystrokes).isEqualTo(2)
		assertThat(outcome.distractedSignals).isEqualTo(0)
		// State cleared: a fresh word starts with no snapshots.
		assertThat(c.onCommit("mừng", 0.5, learn = false).savedKeystrokes).isEqualTo(0)
	}

	@Test
	fun `commit of a different word yields no savings, one distraction, and lowers phat`() {
		val c = NgbConfidence()
		val before = c.phat(0.95, 2, topIsPrediction = true, ctxValid = true)
		c.observe(0.95, 2, true, true, "sai", 0.5)
		val outcome = c.onCommit("đúng", 0.5, learn = true)
		assertThat(outcome.savedKeystrokes).isEqualTo(0)
		assertThat(outcome.distractedSignals).isEqualTo(1)
		assertThat(c.phat(0.95, 2, topIsPrediction = true, ctxValid = true)).isLessThan(before)
	}

	@Test
	fun `distracted counts every fired top that was not committed`() {
		val c = NgbConfidence()
		c.observe(0.95, 2, true, true, "sai", 0.5) // fires
		c.observe(0.95, 3, true, true, "khác", 0.5) // top changed: fires again
		c.observe(0.95, 4, true, true, "mừng", 0.5) // fires; this one is committed
		val outcome = c.onCommit("mừng", 0.5, learn = false)
		assertThat(outcome.distractedSignals).isEqualTo(2)
		// Suppressed and below-threshold observations are never distractions.
		c.observe(0.10, 2, false, true, "sai", 0.5)
		assertThat(c.onCommit("khác", 0.5, learn = false).distractedSignals).isEqualTo(0)
	}

	@Test
	fun `matching commits raise phat and drift stays clamped`() {
		val c = NgbConfidence()
		val before = c.phat(0.95, 2, topIsPrediction = true, ctxValid = true)
		repeat(10_000) {
			c.observe(0.95, 2, true, true, "mừng", 0.99)
			c.onCommit("mừng", 0.99, learn = true)
		}
		assertThat(c.phat(0.95, 2, topIsPrediction = true, ctxValid = true)).isGreaterThan(before)
		c.exportWeights().forEach { (key, w) ->
			val i = NgbConfidence.FEATURE_KEYS.indexOf(key)
			if (i < 0) return@forEach // shadow-counter entries, not weights
			val ref = NgbConfidence.DEFAULT_WEIGHTS[i]
			assertThat(w).isAtMost(ref + NgbConfidence.WEIGHT_DRIFT_LIMIT)
			assertThat(w).isAtLeast(ref - NgbConfidence.WEIGHT_DRIFT_LIMIT)
		}
	}

	@Test
	fun `learn=false leaves weights untouched`() {
		val c = NgbConfidence()
		val before = c.exportWeights()
		c.observe(0.95, 2, true, true, "mừng", 0.5)
		c.onCommit("khác", 0.5, learn = false)
		assertThat(c.exportWeights()).isEqualTo(before)
	}

	@Test
	fun `import applies saved values, restores defaults for missing keys, clamps outliers`() {
		val c = NgbConfidence()
		c.importWeights(mapOf("bias" to -8.0))
		assertThat(c.exportWeights()["bias"]).isWithin(1e-9).of(-8.0)
		assertThat(c.exportWeights()["posterior"]).isWithin(1e-9).of(NgbConfidence.DEFAULT_WEIGHTS[0])
		c.importWeights(mapOf("posterior" to 99.0))
		assertThat(c.exportWeights()["posterior"])
			.isWithin(1e-9).of(NgbConfidence.DEFAULT_WEIGHTS[0] + NgbConfidence.WEIGHT_DRIFT_LIMIT)
		// Missing keys reset: the previous -8.0 bias is back at its default.
		assertThat(c.exportWeights()["bias"]).isWithin(1e-9).of(NgbConfidence.DEFAULT_WEIGHTS[4])
	}

	// ── Adaptive theta (mechanism B, shadow placement 2026-08-12): every
	// commit replays the word's states against the whole threshold grid;
	// theta = the lowest evidenced bin whose observed precision meets the
	// user's target ──

	/** Posterior whose p-hat clears every grid bin at the kcount cap
	 *  (~0.995 at k=8; only ~0.72 at k=2 — keep synthetic states deep). */
	private val hiPosterior = 0.99

	/** One word with a single high-confidence state ("mừng" on top), then
	 *  commit [committed] — committed=="mừng" confirms the top was right. */
	private fun NgbConfidence.word(committed: String) {
		observe(hiPosterior, 8, true, true, "mừng", 2.0) // threshold 2.0: never live-fires
		onCommit(committed, 2.0, learn = true, sgd = false)
	}

	/** Feed [n] words at a high-confidence state, [correct] of them
	 *  committing the top. */
	private fun NgbConfidence.stream(n: Int, correct: Int) {
		repeat(correct) { word("mừng") }
		repeat(n - correct) { word("khác") }
	}

	@Test
	fun `no evidence places theta at the sweep-measured seed`() {
		val c = NgbConfidence()
		assertThat(c.thetaFor(0.85)).isWithin(1e-9).of(0.80) // first bin with measured 85%+
		assertThat(c.thetaFor(0.60)).isWithin(1e-9).of(NgbConfidence.THETA_MIN)
		assertThat(c.thetaFor(0.95)).isWithin(1e-9).of(NgbConfidence.THETA_MAX) // nothing measured that high
	}

	@Test
	fun `high observed precision places theta at the grid floor`() {
		val c = NgbConfidence()
		c.stream(n = 40, correct = 40) // every would-fire confirmed correct
		assertThat(c.thetaFor(0.85)).isWithin(1e-9).of(NgbConfidence.THETA_MIN)
	}

	@Test
	fun `low observed precision places theta at the quiet top`() {
		val c = NgbConfidence()
		c.stream(n = 40, correct = 10) // 25% precision everywhere
		assertThat(c.thetaFor(0.85)).isWithin(1e-9).of(NgbConfidence.THETA_MAX)
	}

	@Test
	fun `placement reacts to the precision target without new evidence`() {
		val c = NgbConfidence()
		c.stream(n = 40, correct = 30) // 75% observed precision at every bin
		assertThat(c.thetaFor(0.70)).isWithin(1e-9).of(NgbConfidence.THETA_MIN)
		assertThat(c.thetaFor(0.90)).isWithin(1e-9).of(NgbConfidence.THETA_MAX)
	}

	@Test
	fun `under the evidence floor the seed keeps ruling`() {
		val c = NgbConfidence()
		c.stream(n = 10, correct = 10) // well short of the floor
		assertThat(c.thetaFor(0.85)).isWithin(1e-9).of(0.80) // still the seed
	}

	@Test
	fun `abandoned words contribute no shadow evidence`() {
		val c = NgbConfidence()
		repeat(40) {
			c.observe(hiPosterior, 8, true, true, "mừng", 2.0)
			c.reset() // deleted / field change: no commit, no evidence
		}
		assertThat(c.thetaFor(0.85)).isWithin(1e-9).of(0.80) // seed untouched
	}

	@Test
	fun `replay applies the fire-suppression rule per bin`() {
		val c = NgbConfidence()
		// Top flickers mừng -> khác -> mừng at high p-hat: three would-fires
		// (re-notify on new top), two of them on the committed word.
		repeat(40) {
			c.observe(hiPosterior, 6, true, true, "mừng", 2.0)
			c.observe(hiPosterior, 7, true, true, "khác", 2.0)
			c.observe(hiPosterior, 8, true, true, "mừng", 2.0)
			c.onCommit("mừng", 2.0, learn = true, sgd = false)
		}
		// Observed precision 2/3 ~ 0.667 at every bin: meets 0.65, not 0.70.
		assertThat(c.thetaFor(0.65)).isWithin(1e-9).of(NgbConfidence.THETA_MIN)
		assertThat(c.thetaFor(0.70)).isWithin(1e-9).of(NgbConfidence.THETA_MAX)
	}

	@Test
	fun `sgd=false leaves weights untouched while counters accrue`() {
		val c = NgbConfidence()
		val weightsBefore = NgbConfidence.FEATURE_KEYS.associateWith { c.exportWeights()[it] }
		c.stream(n = 40, correct = 30)
		NgbConfidence.FEATURE_KEYS.forEach { k ->
			assertThat(c.exportWeights()[k]).isEqualTo(weightsBefore[k])
		}
		assertThat(c.thetaFor(0.60)).isWithin(1e-9).of(NgbConfidence.THETA_MIN) // evidence arrived
	}

	@Test
	fun `shadow counters persist through export-import`() {
		val c = NgbConfidence()
		c.stream(n = 40, correct = 40)
		val fresh = NgbConfidence()
		fresh.importWeights(c.exportWeights())
		assertThat(fresh.thetaFor(0.85)).isWithin(1e-9).of(NgbConfidence.THETA_MIN)
		// And a truly fresh import falls back to the seed.
		fresh.importWeights(emptyMap())
		assertThat(fresh.thetaFor(0.85)).isWithin(1e-9).of(0.80)
	}
}
