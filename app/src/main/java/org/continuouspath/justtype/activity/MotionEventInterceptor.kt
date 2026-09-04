package org.continuouspath.justtype.activity

import android.view.MotionEvent

/**
 * Lets a [SetupHostActivity] fragment consume generic motion events (joystick/gamepad axes)
 * before view dispatch. Mirrors [KeyEventInterceptor]; returning `true` consumes the event.
 */
interface MotionEventInterceptor {
	fun interceptMotionEvent(event: MotionEvent): Boolean
}
