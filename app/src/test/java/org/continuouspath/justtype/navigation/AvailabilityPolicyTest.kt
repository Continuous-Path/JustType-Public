package org.continuouspath.justtype.navigation

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.navigation.AvailabilityPolicy.SelectionProbe
import org.continuouspath.justtype.navigation.engine.NavDirection
import org.junit.Test

class AvailabilityPolicyTest {

	private val actionLongClick = 0x20 // AccessibilityNodeInfo.ACTION_LONG_CLICK

	@Test
	fun `long-press enables via the action id or the flag`() {
		assertThat(AvailabilityPolicy.map(SelectionProbe(clickableActionIds = setOf(actionLongClick)))[NavAction.LongPress]).isTrue()
		assertThat(AvailabilityPolicy.map(SelectionProbe(isLongClickable = true))[NavAction.LongPress]).isTrue()
		assertThat(AvailabilityPolicy.map(SelectionProbe())[NavAction.LongPress]).isFalse()
	}

	@Test
	fun `double-tap follows clickability`() {
		assertThat(AvailabilityPolicy.map(SelectionProbe(isClickable = true))[NavAction.DoubleTap]).isTrue()
		assertThat(AvailabilityPolicy.map(SelectionProbe())[NavAction.DoubleTap]).isFalse()
	}

	@Test
	fun `scroll keys grey per planned direction`() {
		val map = AvailabilityPolicy.map(SelectionProbe(scrollDirections = setOf(NavDirection.UP, NavDirection.LEFT)))
		assertThat(map[NavAction.ScrollUp]).isTrue()
		assertThat(map[NavAction.ScrollLeft]).isTrue()
		assertThat(map[NavAction.ScrollDown]).isFalse()
		assertThat(map[NavAction.ScrollRight]).isFalse()
		assertThat(map[NavAction.OpenScroll]).isTrue()
	}

	@Test
	fun `an action-less surface with gestures stays fully scroll-enabled`() {
		// The injected swipe is the only probe of its scrollability.
		val map = AvailabilityPolicy.map(SelectionProbe(gesturesAvailable = true))
		assertThat(map[NavAction.ScrollUp]).isTrue()
		assertThat(map[NavAction.ScrollDown]).isTrue()
		assertThat(map[NavAction.ScrollLeft]).isTrue()
		assertThat(map[NavAction.ScrollRight]).isTrue()
		assertThat(map[NavAction.OpenScroll]).isTrue()
	}

	@Test
	fun `no directions and no gestures leaves scrolling off`() {
		val map = AvailabilityPolicy.map(SelectionProbe())
		assertThat(map[NavAction.OpenScroll]).isFalse()
		assertThat(map[NavAction.ScrollUp]).isFalse()
	}

	@Test
	fun `planned directions win over the gesture-only fallback`() {
		// With a real plan, only planned directions enable even if gestures are granted.
		val map = AvailabilityPolicy.map(
			SelectionProbe(scrollDirections = setOf(NavDirection.DOWN), gesturesAvailable = true),
		)
		assertThat(map[NavAction.ScrollDown]).isTrue()
		assertThat(map[NavAction.ScrollUp]).isFalse()
	}

	@Test
	fun `node-independent keys are always live`() {
		val map = AvailabilityPolicy.map(SelectionProbe())
		val alwaysOn = listOf(
			NavAction.Home, NavAction.Recents, NavAction.OpenMenu, NavAction.BackToNav,
			NavAction.NextMenuPage, NavAction.PrevMenuPage,
			NavAction.PickUp, NavAction.DropTarget,
			NavAction.DragMoveUp, NavAction.DragMoveDown, NavAction.DragMoveLeft, NavAction.DragMoveRight,
		)
		for (action in alwaysOn) {
			assertThat(map[action]).isTrue()
		}
	}
}
