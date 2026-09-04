package org.continuouspath.justtype.navigation

import android.graphics.Rect
import org.continuouspath.justtype.navigation.engine.DragGesture
import org.continuouspath.justtype.navigation.engine.DragPaths
import org.continuouspath.justtype.navigation.engine.DragStep
import org.continuouspath.justtype.navigation.engine.NavBounds
import org.continuouspath.justtype.navigation.engine.NavDirection

/**
 * Owns one drag session: the picked-up element, the drop cursor, and the transient nudge step.
 * The drop gesture is built here ([buildDrop]) so the injection strategy is swappable — this
 * cursor-then-commit build fires one continued gesture at drop time; a future live-held mode
 * swaps this class without touching the service wiring. A collaborator so the service stays at
 * its function budget.
 */
class DragController(
	private val stepMin: Int,
	private val stepMax: Int,
	private val stepIncrement: Int,
) {
	private var pickup: Rect? = null
	private var cursor: Rect? = null
	private var screen: Rect = Rect()
	var step: DragStep = DragStep.default()
		private set

	val isActive: Boolean get() = pickup != null

	/** Begin a session: remember [element] as the pickup, seed the cursor there, reset the step. */
	fun select(element: Rect, screen: Rect) {
		pickup = Rect(element)
		cursor = Rect(element)
		this.screen = Rect(screen)
		step = DragStep.default()
	}

	/** Move the cursor one step toward [direction], clamped inside the screen. No-op if inactive. */
	fun nudge(direction: NavDirection) {
		val c = cursor ?: return
		val delta = step.stepPx(minOf(screen.width(), screen.height()))
		val (nx, ny) = nudged(c.centerX(), c.centerY(), direction, delta, screen)
		c.offsetTo(nx - c.width() / 2, ny - c.height() / 2)
	}

	fun growStep() {
		step = step.longer(stepIncrement, stepMax)
	}

	fun shrinkStep() {
		step = step.shorter(stepIncrement, stepMin)
	}

	fun atMinStep(): Boolean = step.atMin(stepMin)

	fun atMaxStep(): Boolean = step.atMax(stepMax)

	/** The current cursor rect (for the overlay), or null when inactive. */
	fun cursorBounds(): Rect? = cursor?.let { Rect(it) }

	fun pickupBounds(): Rect? = pickup?.let { Rect(it) }

	/** The drop gesture from pickup to the current cursor. Null if no session is active. */
	fun buildDrop(): DragGesture? {
		val p = pickup ?: return null
		val c = cursor ?: return null
		return DragPaths.drop(p.toNavBounds(), c.toNavBounds())
	}

	fun cancel() {
		pickup = null
		cursor = null
		screen = Rect()
		step = DragStep.default()
	}

	private fun Rect.toNavBounds() = NavBounds(left, top, right, bottom)

	companion object {
		/** Pure cursor-move math: one [delta]-px step toward [direction] from ([x], [y]), clamped to [screen]. */
		fun nudged(x: Int, y: Int, direction: NavDirection, delta: Int, screen: Rect): Pair<Int, Int> {
			// right/bottom are exclusive dimensions (valid pixels are 0..dim-1); clamp one inside so the
			// drop point never lands past the last column/row or on the bottom nav-bar gesture line.
			val maxX = (screen.right - 1).coerceAtLeast(screen.left)
			val maxY = (screen.bottom - 1).coerceAtLeast(screen.top)
			val nx = when (direction) {
				NavDirection.LEFT -> x - delta
				NavDirection.RIGHT -> x + delta
				else -> x
			}.coerceIn(screen.left, maxX)
			val ny = when (direction) {
				NavDirection.UP -> y - delta
				NavDirection.DOWN -> y + delta
				else -> y
			}.coerceIn(screen.top, maxY)
			return nx to ny
		}
	}
}
