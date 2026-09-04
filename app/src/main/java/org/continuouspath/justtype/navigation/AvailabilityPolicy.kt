package org.continuouspath.justtype.navigation

import org.continuouspath.justtype.navigation.engine.NavDirection

/** Pure derivation of the per-key availability map from a probe of the current selection. */
object AvailabilityPolicy {

	/** What the service saw for the selection's clickable ancestor + scroll/gesture state. */
	data class SelectionProbe(
		val clickableActionIds: Set<Int> = emptySet(),
		val isClickable: Boolean = false,
		val isLongClickable: Boolean = false,
		val scrollDirections: Set<NavDirection> = emptySet(),
		val gesturesAvailable: Boolean = false,
	)

	// AccessibilityNodeInfo.ACTION_LONG_CLICK — mirrored so the policy stays framework-free.
	private const val ACTION_LONG_CLICK_ID = 0x20

	fun map(probe: SelectionProbe): Map<NavAction, Boolean> {
		val dirs = probe.scrollDirections
		// An action-less surface with gestures granted stays fully enabled — the injected swipe
		// is the only probe of its scrollability.
		val gestureOnly = dirs.isEmpty() && probe.gesturesAvailable
		return mapOf(
			NavAction.LongPress to (ACTION_LONG_CLICK_ID in probe.clickableActionIds || probe.isLongClickable),
			NavAction.DoubleTap to probe.isClickable,
			NavAction.ScrollUp to (NavDirection.UP in dirs || gestureOnly),
			NavAction.ScrollDown to (NavDirection.DOWN in dirs || gestureOnly),
			NavAction.ScrollLeft to (NavDirection.LEFT in dirs || gestureOnly),
			NavAction.ScrollRight to (NavDirection.RIGHT in dirs || gestureOnly),
			NavAction.OpenScroll to (dirs.isNotEmpty() || gestureOnly),
			NavAction.Home to true,
			NavAction.Recents to true,
			NavAction.OpenMenu to true,
			NavAction.BackToNav to true,
			NavAction.NextMenuPage to true,
			NavAction.PrevMenuPage to true,
			// The pick-up cursor always has a start point (free cursor, seeded on entry), so SELECT is
			// always live — the drop cursor and step keys are always live on the move page too.
			NavAction.PickUp to true,
			NavAction.DropTarget to true,
			NavAction.DragMoveUp to true,
			NavAction.DragMoveDown to true,
			NavAction.DragMoveLeft to true,
			NavAction.DragMoveRight to true,
		)
	}
}
