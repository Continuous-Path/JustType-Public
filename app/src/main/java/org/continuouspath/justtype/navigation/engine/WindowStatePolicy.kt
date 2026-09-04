package org.continuouspath.justtype.navigation.engine

/**
 * Whether the current selection survives a window-state change. The service probes the live
 * node/windows; the survival rule itself lives here, pure and testable.
 */
object WindowStatePolicy {
	fun selectionSurvives(
		otherAppWindowAnnounced: Boolean,
		bounds: NavBounds,
		overlayUp: Boolean,
		visibleToUser: Boolean,
		heldWindowPresent: Boolean,
	): Boolean = !otherAppWindowAnnounced &&
		!bounds.isEmpty &&
		// A full-screen capture overlay makes app nodes report isVisibleToUser=false
		// (same rule as NodeSnapshotter) — skip the visibility check while one is up.
		(overlayUp || visibleToUser) &&
		heldWindowPresent
}
