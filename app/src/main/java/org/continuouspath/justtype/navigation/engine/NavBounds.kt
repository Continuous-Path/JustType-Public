package org.continuouspath.justtype.navigation.engine

/** Value-type screen rectangle — the engine's android.graphics.Rect stand-in, plain-JVM testable. */
data class NavBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
	val width: Int get() = right - left
	val height: Int get() = bottom - top
	val centerX: Int get() = (left + right) / 2
	val centerY: Int get() = (top + bottom) / 2
	val isEmpty: Boolean get() = width <= 0 || height <= 0
	val area: Long get() = width.toLong() * height

	fun contains(other: NavBounds): Boolean = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

	fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

	fun intersects(other: NavBounds): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom

	fun centerDistanceSqTo(other: NavBounds): Long {
		val dx = (centerX - other.centerX).toLong()
		val dy = (centerY - other.centerY).toLong()
		return dx * dx + dy * dy
	}

	/**
	 * This rectangle clipped to [bounds]. If the two don't overlap (a container scrolled fully off
	 * [bounds]), returns [bounds] itself so callers always get a usable, on-screen rectangle.
	 */
	fun intersectClamped(bounds: NavBounds): NavBounds {
		val clipped = NavBounds(
			maxOf(left, bounds.left),
			maxOf(top, bounds.top),
			minOf(right, bounds.right),
			minOf(bottom, bounds.bottom),
		)
		return if (clipped.isEmpty) bounds else clipped
	}
}

enum class NavDirection { UP, DOWN, LEFT, RIGHT }
