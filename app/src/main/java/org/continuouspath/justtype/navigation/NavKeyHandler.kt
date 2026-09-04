package org.continuouspath.justtype.navigation

/**
 * Per-page handler. The host swaps a fresh instance per render — mapping
 * differs by [OverlayPage].
 */
class NavKeyHandler(
	private val dispatcher: NavActionDispatcher,
	private val mapping: Map<Int, NavAction>,
) {
	/** Returns false if the key was unmapped or its action was a no-op. */
	fun onKeyPressed(index: Int): Boolean {
		val action = mapping[index] ?: return false
		return dispatcher.dispatch(action)
	}

	companion object {
		val NAV_MAPPING: Map<Int, NavAction> = mapOf(
			0 to NavAction.OpenMenu,
			1 to NavAction.Up,
			2 to NavAction.Tap,
			3 to NavAction.Left,
			4 to NavAction.Right,
			5 to NavAction.DoubleTap,
			6 to NavAction.Down,
			7 to NavAction.LongPress,
		)

		val MENU_PAGE_1: Map<Int, NavAction> = mapOf(
			0 to NavAction.BackToNav,
			1 to NavAction.EnterReposition,
			2 to NavAction.OpenScroll, // goto ScrollMode
			3 to NavAction.Empty,
			4 to NavAction.OpenDrag, // goto DragMode
			5 to NavAction.Back,
			6 to NavAction.Home,
			7 to NavAction.Recents,
		)

		val MENU_PAGE_2: Map<Int, NavAction> = mapOf( // STUB
			0 to NavAction.Empty,
			1 to NavAction.Empty,
			2 to NavAction.Empty,
			3 to NavAction.Empty,
			4 to NavAction.Empty,
			5 to NavAction.Empty,
			6 to NavAction.Empty,
			7 to NavAction.Empty,
		)

		val SCROLL_MAPPING: Map<Int, NavAction> = mapOf( // ScrollMode
			0 to NavAction.PrevMenuPage, // back to MENU_PAGE_1 (spec: layer 2)
			1 to NavAction.ScrollUp,
			2 to NavAction.SpeedAdjustTbd,
			3 to NavAction.ScrollLeft,
			4 to NavAction.ScrollRight,
			5 to NavAction.PathLonger, // scroll-step adjust
			6 to NavAction.ScrollDown,
			7 to NavAction.PathShorter, // scroll-step adjust
		)

		val DRAG_MAPPING: Map<Int, NavAction> = mapOf( // DragMode pick-up page: SELECT TARGET
			0 to NavAction.PrevMenuPage, // back to MENU_PAGE_1 (spec: layer 2)
			1 to NavAction.DragMoveUp, // move the free start-point cursor (precise pixels, any point)
			2 to NavAction.PickUp, // SELECT — start the drag at the cursor
			3 to NavAction.DragMoveLeft,
			4 to NavAction.DragMoveRight,
			5 to NavAction.PathLonger, // grow the pick-up step
			6 to NavAction.DragMoveDown,
			7 to NavAction.PathShorter, // shrink the pick-up step
		)

		val DRAG_MOVE_MAPPING: Map<Int, NavAction> = mapOf( // DragMoveMode dragging page: MOVE TARGET
			0 to NavAction.PrevMenuPage, // BACK — cancel the drag, return to the pick-up page
			1 to NavAction.DragMoveUp,
			2 to NavAction.DropTarget, // DROP — release the held element at the cursor
			3 to NavAction.DragMoveLeft,
			4 to NavAction.DragMoveRight,
			5 to NavAction.PathLonger, // grow the nudge step
			6 to NavAction.DragMoveDown,
			7 to NavAction.PathShorter, // shrink the nudge step
		)

		// 8 perimeter keys → 8 screen regions; positions match the key's place in the grid.
		val REPOSITION_MAPPING: Map<Int, NavAction> = mapOf(
			0 to NavAction.SnapTo(NavRegion.TOP_LEFT),
			1 to NavAction.SnapTo(NavRegion.TOP_CENTER),
			2 to NavAction.SnapTo(NavRegion.TOP_RIGHT),
			3 to NavAction.SnapTo(NavRegion.CENTER_LEFT),
			4 to NavAction.SnapTo(NavRegion.CENTER_RIGHT),
			5 to NavAction.SnapTo(NavRegion.BOTTOM_LEFT),
			6 to NavAction.SnapTo(NavRegion.BOTTOM_CENTER),
			7 to NavAction.SnapTo(NavRegion.BOTTOM_RIGHT),
		)

		fun mappingFor(page: OverlayPage): Map<Int, NavAction> = when (page) {
			OverlayPage.Nav -> NAV_MAPPING
			OverlayPage.MenuPage1 -> MENU_PAGE_1
			OverlayPage.MenuPage2 -> MENU_PAGE_2
			OverlayPage.RepositionMode -> REPOSITION_MAPPING
			OverlayPage.DragMode -> DRAG_MAPPING
			OverlayPage.DragMoveMode -> DRAG_MOVE_MAPPING
			OverlayPage.ScrollMode -> SCROLL_MAPPING
		}
	}
}
