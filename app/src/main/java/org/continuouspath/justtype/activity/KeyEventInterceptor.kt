package org.continuouspath.justtype.activity

import android.view.KeyEvent

/**
 * Implemented by fragments hosted inside [SetupHostActivity] that need to
 * intercept hardware key events early — before normal view dispatch.
 *
 * Mirrors the activity-level `dispatchKeyEvent(event)` contract: the host
 * routes events here first; returning `true` consumes the event, returning
 * `false` lets the host fall through to `super.dispatchKeyEvent(event)`.
 *
 * Used by switch-assignment setup screens (Two Switch, Single Switch) that
 * need to capture any keypress while waiting for the user to press their
 * physical switch — including keys that focus navigation would normally
 * eat. View-level `OnKeyListener`s would be focus-bound and would either
 * miss keys or require requesting focus (which pops the soft keyboard,
 * breaking the setup UX). Host-level dispatch sidesteps both problems.
 */
interface KeyEventInterceptor {
	fun interceptKeyEvent(event: KeyEvent): Boolean
}
