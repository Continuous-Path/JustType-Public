package org.continuouspath.justtype.navigation

import android.graphics.Rect
import org.continuouspath.justtype.navigation.engine.DragStep
import org.continuouspath.justtype.navigation.engine.NavDirection

/**
 * Free crosshair for the drag pick-up page ("SELECT TARGET"): a point the user nudges to pick
 * ANY drag start, not just an a11y element. Kept off [DragController] so its cancel/isActive
 * invariants still mean "a DROP session is live"; the step is per-session. Owns position/step
 * state only — rendering stays with the service (the shared drop-cursor overlay).
 */
class SelectCursorController(
	private val stepMin: Int,
	private val stepMax: Int,
	private val stepIncrement: Int,
) {
	private var cursor: Rect? = null
	var step: DragStep = DragStep.default()
		private set

	val isActive: Boolean get() = cursor != null

	/** Seed near [anchor]'s center (else screen center), clamped on screen; resets the step. */
	fun seed(anchor: Rect?, screen: Rect) {
		val cx = (anchor?.centerX() ?: screen.centerX()).coerceIn(0, (screen.width() - 1).coerceAtLeast(0))
		val cy = (anchor?.centerY() ?: screen.centerY()).coerceIn(0, (screen.height() - 1).coerceAtLeast(0))
		cursor = Rect(cx, cy, cx, cy)
		step = DragStep.default()
	}

	/** Move one step toward [direction], clamped inside [screen]. False if no cursor is active. */
	fun nudge(direction: NavDirection, screen: Rect): Boolean {
		val c = cursor ?: return false
		val delta = step.stepPx(minOf(screen.width(), screen.height()))
		val (nx, ny) = DragController.nudged(c.centerX(), c.centerY(), direction, delta, screen)
		cursor = Rect(nx, ny, nx, ny)
		return true
	}

	/** Grow/shrink the step one increment. False when already at the bound (→ error cue). */
	fun adjustStep(grow: Boolean): Boolean {
		val before = step
		step = if (grow) step.longer(stepIncrement, stepMax) else step.shorter(stepIncrement, stepMin)
		return step != before
	}

	fun atMinStep(): Boolean = step.atMin(stepMin)

	/** The current crosshair point (for the overlay), or null when inactive. */
	fun cursorBounds(): Rect? = cursor?.let { Rect(it) }

	fun clear() {
		cursor = null
		step = DragStep.default()
	}
}
