package org.continuouspath.justtype.input

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * A "surface" that wants input-method subsystems (scan, two-switch, head-tracking,
 * joystick, mouse-joystick, touch-detection) to drive its 8-key grid.
 *
 * The IME implements this via [org.continuouspath.justtype.ime.ViewBridgeCoordinator];
 * the navigation overlay implements it via [org.continuouspath.justtype.navigation.NavInputSurface].
 * Subsystems route detected button presses through [onButtonPressed] without knowing
 * which surface they're attached to.
 */
interface InputSurface {
	val context: Context
	val scope: CoroutineScope
	val buttonCount: Int

	fun isReady(): Boolean

	/** Activate the key. Returns false if the press was a no-op (e.g. a disabled/unavailable key). */
	fun onButtonPressed(index: Int): Boolean
	fun onSelect()
	fun onExitGesture(direction: ExitDirection)
}
