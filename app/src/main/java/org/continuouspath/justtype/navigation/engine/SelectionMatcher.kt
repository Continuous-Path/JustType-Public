package org.continuouspath.justtype.navigation.engine

/** Identity of the selected node that survives tree re-captures (the live node may die any time). */
data class SelectionFingerprint(
	val windowId: Int,
	val viewId: String?,
	val className: String?,
	val textFingerprint: Int,
	val bounds: NavBounds,
)

fun NodeSnapshot.fingerprint(): SelectionFingerprint = SelectionFingerprint(windowId, viewId, className, textFingerprint, bounds)

/**
 * Re-finds the selection among fresh candidates after its live node dies: identity fields must
 * match AND the position must still be plausible — a bounds-identical but *different* node never
 * masquerades as the selection, and identical siblings (repeating list rows) can't be confused
 * from far away.
 */
object SelectionMatcher {
	const val CENTER_TOLERANCE_PX = 48

	/** Index into [fresh] of the same logical node as [held], or null when it's gone. */
	fun match(held: SelectionFingerprint, fresh: List<SelectionFingerprint>): Int? = fresh.indices
		.filter { sameIdentity(held, fresh[it]) && spatiallyPlausible(held.bounds, fresh[it].bounds) }
		.minByOrNull { held.bounds.centerDistanceSqTo(fresh[it].bounds) }

	/** Re-seed after a loss: the candidate whose center is nearest the last ring [anchor]. */
	fun nearestToAnchor(anchor: NavBounds, fresh: List<NavBounds>): Int? = fresh.indices.minByOrNull { anchor.centerDistanceSqTo(fresh[it]) }

	private fun sameIdentity(a: SelectionFingerprint, b: SelectionFingerprint): Boolean = a.windowId == b.windowId &&
		a.viewId == b.viewId &&
		a.className == b.className &&
		a.textFingerprint == b.textFingerprint

	/** Center within tolerance, or containment either way (the node grew/shrank in place). */
	private fun spatiallyPlausible(a: NavBounds, b: NavBounds): Boolean = a.centerDistanceSqTo(b) <= CENTER_TOLERANCE_PX.toLong() * CENTER_TOLERANCE_PX || a.contains(b) || b.contains(a)
}
