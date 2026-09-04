package org.continuouspath.justtype.navigation.engine

/**
 * How far a scroll action reaches, as a percent of the container's usable span. A transient,
 * per-mode working value the service holds in memory and resets on each ScrollMode entry — the
 * bounds live with the service, so this stays a pure, android-free value.
 */
data class ScrollReach(val percent: Int) {
	fun longer(step: Int, max: Int): ScrollReach = ScrollReach((percent + step).coerceAtMost(max))

	fun shorter(step: Int, min: Int): ScrollReach = ScrollReach((percent - step).coerceAtLeast(min))

	fun atMax(max: Int): Boolean = percent >= max

	fun atMin(min: Int): Boolean = percent <= min

	companion object {
		const val DEFAULT_PERCENT = 100

		fun default(): ScrollReach = ScrollReach(DEFAULT_PERCENT)

		/** A swipe this short reads as a tap rather than a scroll — drives the "MIN" indicator. */
		fun isBelowTapThreshold(spanPx: Int, tapThresholdPx: Int): Boolean = spanPx <= tapThresholdPx
	}
}
