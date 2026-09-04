package org.continuouspath.justtype.navigation

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import org.continuouspath.justtype.input.ExitDirection
import org.continuouspath.justtype.input.InputSurface

/**
 * Nav-side [InputSurface] impl. Routes subsystem-detected button presses
 * through [NavKeyHandler] for the current overlay page — same path as a
 * touch on the button.
 */
class NavInputSurface(
	override val context: Context,
	override val scope: CoroutineScope,
	private val dispatcher: NavActionDispatcher,
	private val currentPageProvider: () -> OverlayPage,
	private val readyProvider: () -> Boolean,
	private val onNoOp: (Int) -> Unit,
) : InputSurface {

	override val buttonCount: Int = 8

	// Last action dispatched from a real press, kept only if it's repeatable — what a held-switch
	// auto-repeat replays (see [repeatLast]) instead of re-resolving the index on a changed page.
	private var lastRepeatableAction: NavAction? = null

	override fun isReady(): Boolean = readyProvider()

	override fun onButtonPressed(index: Int): Boolean {
		val action = NavKeyHandler.mappingFor(currentPageProvider())[index]
		lastRepeatableAction = action?.takeIf { it.isRepeatable }
		val acted = action != null && dispatcher.dispatch(action)
		if (!acted) onNoOp(index) // disabled/unavailable key hit via a non-direct method → error cue
		return acted
	}

	/**
	 * Replay the last repeatable action for two-switch auto-repeat. Re-resolving the pressed index
	 * would be wrong once a completing action changed the page; a one-shot/page-changing last action
	 * is a silent no-op (no error cue while held).
	 */
	fun repeatLast(): Boolean {
		val action = lastRepeatableAction ?: return false
		return dispatcher.dispatch(action)
	}

	override fun onSelect() { /* no center-key concept on Nav */ }

	override fun onExitGesture(direction: ExitDirection) { /* TBD: pause/hide overlay on exit gesture */ }
}
