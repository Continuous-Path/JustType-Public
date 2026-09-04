package org.continuouspath.justtype.navigation

import androidx.annotation.DrawableRes
import org.continuouspath.justtype.R

sealed class NavAction(
	val glyph: String,
	@DrawableRes val iconRes: Int = 0,
	@DrawableRes val iconResDark: Int = 0,
) {
	object Up : NavAction("↑", R.drawable.ic_nav_focus_up, R.drawable.ic_nav_focus_up_dark)
	object Down : NavAction("↓", R.drawable.ic_nav_focus_down, R.drawable.ic_nav_focus_down_dark)
	object Left : NavAction("←", R.drawable.ic_nav_focus_left, R.drawable.ic_nav_focus_left_dark)
	object Right : NavAction("→", R.drawable.ic_nav_focus_right, R.drawable.ic_nav_focus_right_dark)
	object Tap : NavAction("●", R.drawable.ic_nav_tap, R.drawable.ic_nav_tap_dark)
	object Back : NavAction("◀", R.drawable.ic_nav_android_back, R.drawable.ic_nav_android_back_dark)

	object OpenMenu : NavAction("⋯", R.drawable.ic_nav_layer_1_to_2, R.drawable.ic_nav_layer_1_to_2_dark)
	object BackToNav : NavAction("✕", R.drawable.ic_nav_layer_2_to_1, R.drawable.ic_nav_layer_2_to_1_dark)
	object NextMenuPage : NavAction("»")
	object PrevMenuPage : NavAction("«", R.drawable.ic_nav_return_to_layer_2, R.drawable.ic_nav_return_to_layer_2_dark)

	object LongPress : NavAction("◉", R.drawable.ic_nav_long_press, R.drawable.ic_nav_long_press_dark)
	object DoubleTap : NavAction("◎", R.drawable.ic_nav_double_tap, R.drawable.ic_nav_double_tap_dark)
	object ScrollUp : NavAction("⇡", R.drawable.ic_nav_scroll_up, R.drawable.ic_nav_scroll_up_dark)
	object ScrollDown : NavAction("⇣", R.drawable.ic_nav_scroll_down, R.drawable.ic_nav_scroll_down_dark)
	object ScrollLeft : NavAction("⇠", R.drawable.ic_nav_scroll_left, R.drawable.ic_nav_scroll_left_dark)
	object ScrollRight : NavAction("⇢", R.drawable.ic_nav_scroll_right, R.drawable.ic_nav_scroll_right_dark)
	object Home : NavAction("⌂", R.drawable.ic_nav_android_home, R.drawable.ic_nav_android_home_dark)
	object Recents : NavAction("⊟", R.drawable.ic_nav_android_app_switcher, R.drawable.ic_nav_android_app_switcher_dark)

	object OpenScroll : NavAction("SCROLL", R.drawable.ic_nav_mode_scroll, R.drawable.ic_nav_mode_scroll_dark)
	object OpenDrag : NavAction("DRAG", R.drawable.ic_nav_mode_drag, R.drawable.ic_nav_mode_drag_dark)

	// Scroll-reach + drag-step adjust keys (LONGER/SHORTER, shared by ScrollMode and the drag move page).
	object PathLonger : NavAction("+", R.drawable.ic_nav_path_longer, R.drawable.ic_nav_path_longer_dark)
	object PathShorter : NavAction("−", R.drawable.ic_nav_path_shorter, R.drawable.ic_nav_path_shorter_dark)

	// Drag: pick up the focused element (SELECT), then move the drop cursor and release it (DROP).
	object PickUp : NavAction("◍", R.drawable.ic_nav_drag_select, R.drawable.ic_nav_drag_select_dark)
	object DropTarget : NavAction("DROP")
	object DragMoveUp : NavAction("↥")
	object DragMoveDown : NavAction("↧")
	object DragMoveLeft : NavAction("↤")
	object DragMoveRight : NavAction("↦")

	// ScrollMode slot 2 — the layout spec marks it [TBD]; visible placeholder, error cue on press.
	object SpeedAdjustTbd : NavAction("[TBD]")

	object AltLayout1 : NavAction("[1]", R.drawable.ic_nav_alt_layout_1)
	object AltLayout2 : NavAction("[2]", R.drawable.ic_nav_alt_layout_2)

	// Reposition mode: enter the 8-arrow snap layout, then snap the window to a region.
	object EnterReposition : NavAction("✥", R.drawable.ic_nav_mode_move_keyboard, R.drawable.ic_nav_mode_move_keyboard_dark)
	data class SnapTo(val region: NavRegion) : NavAction(region.glyph, region.iconRes, region.iconResDark)

	object Empty : NavAction("·")
	data class Stub(val slotIndex: Int) : NavAction("·")
}

/** A screen region the Nav keyboard can snap to (corners + edges; center is unused). */
enum class NavRegion(
	val glyph: String,
	@DrawableRes val iconRes: Int,
	@DrawableRes val iconResDark: Int,
) {
	TOP_LEFT("↖", R.drawable.ic_nav_keyboard_move_up_left, R.drawable.ic_nav_keyboard_move_up_left_dark),
	TOP_CENTER("↑", R.drawable.ic_nav_keyboard_move_up, R.drawable.ic_nav_keyboard_move_up_dark),
	TOP_RIGHT("↗", R.drawable.ic_nav_keyboard_move_up_right, R.drawable.ic_nav_keyboard_move_up_right_dark),
	CENTER_LEFT("←", R.drawable.ic_nav_keyboard_move_left, R.drawable.ic_nav_keyboard_move_left_dark),
	CENTER_RIGHT("→", R.drawable.ic_nav_keyboard_move_right, R.drawable.ic_nav_keyboard_move_right_dark),
	BOTTOM_LEFT("↙", R.drawable.ic_nav_keyboard_move_down_left, R.drawable.ic_nav_keyboard_move_down_left_dark),
	BOTTOM_CENTER("↓", R.drawable.ic_nav_keyboard_move_down, R.drawable.ic_nav_keyboard_move_down_dark),
	BOTTOM_RIGHT("↘", R.drawable.ic_nav_keyboard_move_down_right, R.drawable.ic_nav_keyboard_move_down_right_dark),
}

sealed class OverlayPage {
	object Nav : OverlayPage()
	object MenuPage1 : OverlayPage()
	object MenuPage2 : OverlayPage()
	object RepositionMode : OverlayPage()
	object DragMode : OverlayPage() // pick-up page: SELECT TARGET
	object DragMoveMode : OverlayPage() // dragging page: MOVE TARGET
	object ScrollMode : OverlayPage()
}

/**
 * Actions that make sense to fire repeatedly while a switch is held (two-switch auto-repeat):
 * continuous motion, not one-shot or page-changing actions.
 */
val NavAction.isRepeatable: Boolean
	get() = when (this) {
		NavAction.Up, NavAction.Down, NavAction.Left, NavAction.Right,
		NavAction.ScrollUp, NavAction.ScrollDown, NavAction.ScrollLeft, NavAction.ScrollRight,
		NavAction.DragMoveUp, NavAction.DragMoveDown, NavAction.DragMoveLeft, NavAction.DragMoveRight,
		-> true
		else -> false
	}

interface NavActionDispatcher {
	/** Perform the action. Returns false if it was a no-op (empty/unavailable/failed). */
	fun dispatch(action: NavAction): Boolean
}
