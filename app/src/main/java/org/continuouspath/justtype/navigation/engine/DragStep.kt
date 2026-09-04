package org.continuouspath.justtype.navigation.engine

/**
 * How far one arrow press nudges the drop cursor, as a percent of a reference span (the shorter
 * screen dimension). A transient, per-session working value the service resets on each drag —
 * the span lives with the service, so this stays a pure, android-free value. Sibling of
 * [ScrollReach], which measures scroll distance the same way.
 */
data class DragStep(val percent: Int) {
	fun longer(step: Int, max: Int): DragStep = DragStep((percent + step).coerceAtMost(max))

	fun shorter(step: Int, min: Int): DragStep = DragStep((percent - step).coerceAtLeast(min))

	fun atMax(max: Int): Boolean = percent >= max

	fun atMin(min: Int): Boolean = percent <= min

	/** Nudge distance in pixels for a screen whose shorter side is [screenMinDim]. */
	fun stepPx(screenMinDim: Int): Int = screenMinDim * percent / 100

	companion object {
		const val DEFAULT_PERCENT = 10

		fun default(): DragStep = DragStep(DEFAULT_PERCENT)
	}
}
