package org.continuouspath.justtype.navigation

import org.continuouspath.justtype.navigation.engine.ScrollReach

/**
 * Owns the overlay's page, key-availability map, and transient scroll reach. Transitions run
 * the injected Android-side effects (render, drag teardown, reach arrow, reposition timeout)
 * so the rules themselves are unit-testable without a service.
 */
class NavPageState(
	private val computeAvailability: () -> Map<NavAction, Boolean>,
	private val onRender: () -> Unit,
	private val onDragTeardown: () -> Unit,
	private val onSeedSelectCursor: () -> Unit,
	private val onShowReachArrow: (ScrollReach) -> Unit,
	private val onHideArrow: () -> Unit,
	private val armRepositionTimeout: (Boolean) -> Unit,
) {
	var page: OverlayPage = OverlayPage.Nav
		private set
	var availability: Map<NavAction, Boolean> = emptyMap()
		private set
	var scrollReach: ScrollReach = ScrollReach.default()
		private set

	fun openMenu() {
		// Open the menu even with no focused node — Home/Recents/Reposition are
		// node-independent (reposition especially is wanted on focus-less screens like
		// the home screen). Context actions (tap/long-press/scroll) grey out when null.
		availability = computeAvailability()
		page = OverlayPage.MenuPage1
		onRender()
	}

	/** ScrollMode entry recomputes availability first and refuses (error cue) when nothing can scroll. */
	fun openScrollMode(): Boolean {
		val fresh = computeAvailability()
		if (fresh[NavAction.OpenScroll] != true) return false
		availability = fresh
		armRepositionTimeout(false)
		page = OverlayPage.ScrollMode
		scrollReach = ScrollReach.default() // reach is per-session: always start from the default
		onRender()
		onShowReachArrow(scrollReach)
		return true
	}

	fun goToPage(target: OverlayPage) {
		onHideArrow()
		// Only the dragging page holds a live session; every other target (incl. BACK to the
		// pick-up page and a fresh pick-up entry) starts clean — the held target is discarded.
		if (target != OverlayPage.DragMoveMode) onDragTeardown()
		page = target
		onRender()
		// The pick-up page nudges a free start-point cursor — seed it after the teardown above.
		if (target == OverlayPage.DragMode) onSeedSelectCursor()
		// Reposition mode has no cancel key (overlay is non-focusable). Auto-return to Nav
		// if no region is picked, so a mis-press or "happy where it is" isn't a dead end.
		armRepositionTimeout(target == OverlayPage.RepositionMode)
	}

	fun pageForNav(action: NavAction): OverlayPage = when (action) {
		NavAction.NextMenuPage -> OverlayPage.MenuPage2
		// BACK from the dragging page returns to the pick-up page (cancel handled in goToPage); else to the menu.
		NavAction.PrevMenuPage -> if (page == OverlayPage.DragMoveMode) OverlayPage.DragMode else OverlayPage.MenuPage1
		NavAction.OpenDrag -> OverlayPage.DragMode
		else -> OverlayPage.RepositionMode // EnterReposition
	}

	fun returnToNav() {
		armRepositionTimeout(false)
		onHideArrow()
		onDragTeardown()
		page = OverlayPage.Nav
		availability = emptyMap()
		onRender()
	}

	/** The reposition auto-return fired; only acts if the page is still RepositionMode. */
	fun repositionTimedOut() {
		if (page == OverlayPage.RepositionMode) returnToNav()
	}

	/** Bare state reset for teardown paths (window loss, overlay hide) — no effects run. */
	fun resetToNav() {
		page = OverlayPage.Nav
		availability = emptyMap()
	}

	/** Longer/Shorter keys adjust the transient reach; false at the bound (→ error cue). */
	fun adjustReach(grow: Boolean, increment: Int, min: Int, max: Int): Boolean {
		val next = if (grow) scrollReach.longer(increment, max) else scrollReach.shorter(increment, min)
		if (next == scrollReach) return false // already at the bound — error cue
		scrollReach = next
		onShowReachArrow(scrollReach)
		return true
	}

	// Pages whose visible keys enable/disable per the selected element or its scrollability. When the
	// selection moves (or a scroll changes what can scroll) on one of these, the keys must re-grey.
	private val selectionDependentPages = setOf(
		OverlayPage.MenuPage1, // OpenScroll greys per scrollability
		OverlayPage.ScrollMode, // scroll-direction keys grey as directions exhaust
	)

	/**
	 * Recompute the selected element's availability and rerender, but only if the page shows
	 * selection-dependent keys and the map actually changed (so a held key's auto-repeat survives).
	 */
	fun refreshSelectionAvailability() {
		if (page !in selectionDependentPages) return
		val fresh = computeAvailability()
		if (fresh != availability) {
			availability = fresh
			onRender()
		}
	}
}
