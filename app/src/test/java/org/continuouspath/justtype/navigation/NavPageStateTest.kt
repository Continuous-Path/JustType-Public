package org.continuouspath.justtype.navigation

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.navigation.engine.ScrollReach
import org.junit.Test

class NavPageStateTest {

	private val effects = mutableListOf<String>()
	private var nextAvailability: Map<NavAction, Boolean> = emptyMap()

	private val state = NavPageState(
		computeAvailability = { nextAvailability },
		onRender = { effects += "render" },
		onDragTeardown = { effects += "teardown" },
		onSeedSelectCursor = { effects += "seed" },
		onShowReachArrow = { effects += "arrow:${it.percent}" },
		onHideArrow = { effects += "hideArrow" },
		armRepositionTimeout = { effects += "timeout:$it" },
	)

	@Test
	fun `starts on Nav with nothing available`() {
		assertThat(state.page).isEqualTo(OverlayPage.Nav)
		assertThat(state.availability).isEmpty()
		assertThat(state.scrollReach).isEqualTo(ScrollReach.default())
	}

	@Test
	fun `openMenu recomputes availability and renders MenuPage1`() {
		nextAvailability = mapOf(NavAction.Home to true)
		state.openMenu()
		assertThat(state.page).isEqualTo(OverlayPage.MenuPage1)
		assertThat(state.availability).isEqualTo(mapOf(NavAction.Home to true))
		assertThat(effects).containsExactly("render")
	}

	@Test
	fun `openScrollMode refuses when nothing can scroll`() {
		nextAvailability = mapOf(NavAction.OpenScroll to false)
		assertThat(state.openScrollMode()).isFalse()
		assertThat(state.page).isEqualTo(OverlayPage.Nav)
		assertThat(effects).isEmpty()
	}

	@Test
	fun `openScrollMode enters with a fresh per-session reach`() {
		state.adjustReach(grow = false, increment = 25, min = 25, max = 100) // pre-dirty the reach
		effects.clear()
		nextAvailability = mapOf(NavAction.OpenScroll to true)
		assertThat(state.openScrollMode()).isTrue()
		assertThat(state.page).isEqualTo(OverlayPage.ScrollMode)
		assertThat(state.scrollReach).isEqualTo(ScrollReach.default())
		assertThat(effects).containsExactly("timeout:false", "render", "arrow:100").inOrder()
	}

	@Test
	fun `adjustReach moves between the bounds and cues at them`() {
		assertThat(state.adjustReach(grow = true, increment = 25, min = 25, max = 100)).isFalse() // already at 100
		assertThat(state.adjustReach(grow = false, increment = 25, min = 25, max = 100)).isTrue()
		assertThat(state.scrollReach.percent).isEqualTo(75)
		assertThat(effects).containsExactly("arrow:75")
		repeat(2) { state.adjustReach(grow = false, increment = 25, min = 25, max = 100) }
		assertThat(state.scrollReach.percent).isEqualTo(25)
		assertThat(state.adjustReach(grow = false, increment = 25, min = 25, max = 100)).isFalse()
	}

	@Test
	fun `goToPage tears down drag state except when entering the dragging page`() {
		state.goToPage(OverlayPage.MenuPage2)
		assertThat(effects).containsExactly("hideArrow", "teardown", "render", "timeout:false").inOrder()
		effects.clear()
		state.goToPage(OverlayPage.DragMoveMode) // live session must survive the transition
		assertThat(effects).containsExactly("hideArrow", "render", "timeout:false").inOrder()
	}

	@Test
	fun `entering the pick-up page seeds the cursor after teardown and render`() {
		state.goToPage(OverlayPage.DragMode)
		assertThat(effects).containsExactly("hideArrow", "teardown", "render", "seed", "timeout:false").inOrder()
	}

	@Test
	fun `reposition mode arms the auto-return timeout`() {
		state.goToPage(OverlayPage.RepositionMode)
		assertThat(effects).contains("timeout:true")
	}

	@Test
	fun `pageForNav routes the menu-page actions`() {
		assertThat(state.pageForNav(NavAction.NextMenuPage)).isEqualTo(OverlayPage.MenuPage2)
		assertThat(state.pageForNav(NavAction.PrevMenuPage)).isEqualTo(OverlayPage.MenuPage1)
		assertThat(state.pageForNav(NavAction.OpenDrag)).isEqualTo(OverlayPage.DragMode)
		assertThat(state.pageForNav(NavAction.EnterReposition)).isEqualTo(OverlayPage.RepositionMode)
	}

	@Test
	fun `BACK from the dragging page returns to the pick-up page`() {
		state.goToPage(OverlayPage.DragMoveMode)
		assertThat(state.pageForNav(NavAction.PrevMenuPage)).isEqualTo(OverlayPage.DragMode)
	}

	@Test
	fun `returnToNav clears everything and disarms the timeout`() {
		nextAvailability = mapOf(NavAction.Home to true)
		state.openMenu()
		effects.clear()
		state.returnToNav()
		assertThat(state.page).isEqualTo(OverlayPage.Nav)
		assertThat(state.availability).isEmpty()
		assertThat(effects).containsExactly("timeout:false", "hideArrow", "teardown", "render").inOrder()
	}

	@Test
	fun `reposition timeout only fires while still on RepositionMode`() {
		state.goToPage(OverlayPage.MenuPage1)
		effects.clear()
		state.repositionTimedOut()
		assertThat(effects).isEmpty()
		state.goToPage(OverlayPage.RepositionMode)
		effects.clear()
		state.repositionTimedOut()
		assertThat(state.page).isEqualTo(OverlayPage.Nav)
	}

	@Test
	fun `resetToNav is a bare state reset with no effects`() {
		nextAvailability = mapOf(NavAction.Home to true)
		state.openMenu()
		effects.clear()
		state.resetToNav()
		assertThat(state.page).isEqualTo(OverlayPage.Nav)
		assertThat(state.availability).isEmpty()
		assertThat(effects).isEmpty()
	}

	@Test
	fun `availability refresh is gated to selection-dependent pages`() {
		nextAvailability = mapOf(NavAction.OpenScroll to true)
		state.refreshSelectionAvailability() // Nav page: no-op
		assertThat(effects).isEmpty()

		state.openMenu()
		effects.clear()
		nextAvailability = mapOf(NavAction.OpenScroll to false)
		state.refreshSelectionAvailability()
		assertThat(state.availability).isEqualTo(mapOf(NavAction.OpenScroll to false))
		assertThat(effects).containsExactly("render")
	}

	@Test
	fun `availability refresh skips the render when the map is unchanged`() {
		// The diff gate keeps a held key's auto-repeat alive across no-op refreshes.
		nextAvailability = mapOf(NavAction.OpenScroll to true)
		state.openMenu()
		effects.clear()
		state.refreshSelectionAvailability()
		assertThat(effects).isEmpty()
	}
}
