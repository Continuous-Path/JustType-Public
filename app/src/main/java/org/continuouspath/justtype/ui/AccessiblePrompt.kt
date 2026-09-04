package org.continuouspath.justtype.ui

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import org.continuouspath.justtype.R

/**
 * Accessibility-first prompt overlay. Displays a message over the user's
 * app (above the IME), dismissable via the UnDo key (intercepted by
 * [org.continuouspath.justtype.logic.JTUI] when this prompt is showing) or
 * programmatically.
 *
 * Renders as a system overlay via [WindowManager], which requires
 * `SYSTEM_ALERT_WINDOW` permission. That permission is requested by
 * [org.continuouspath.justtype.activity.SettingsActivity.maybePromptOverlayPermission]
 * at first install (Strategy C). If permission is not yet granted,
 * [show] is a no-op — callers don't need to check; they just call
 * [show] and the prompt appears if possible.
 *
 * Threading: must be called on the main thread.
 *
 * Single-instance per host: a second [show] call while a prompt is
 * already visible replaces the existing one.
 */
class AccessiblePrompt(private val context: Context) {
	private val windowManager: WindowManager =
		context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

	private var currentView: View? = null
	private var onDismissCallback: (() -> Unit)? = null

	/** True while a prompt is currently displayed. */
	val isShowing: Boolean
		get() = currentView != null

	/**
	 * Display the prompt with the given [messageText]. If [onDismissed] is
	 * provided, it is invoked when the prompt is removed (whether by UnDo
	 * key, by programmatic [dismiss], or by replacement).
	 *
	 * No-op if overlay permission has not been granted. Callers can rely
	 * on this and don't need to check `Settings.canDrawOverlays` first.
	 */
	fun show(messageText: String, onDismissed: (() -> Unit)? = null) {
		if (!Settings.canDrawOverlays(context)) return

		// If something is already showing, dismiss it first so its callback
		// fires and we don't leak the previous view.
		if (currentView != null) dismiss()

		val view = LayoutInflater.from(context).inflate(R.layout.accessible_prompt, null, false)
		view.findViewById<TextView>(R.id.acc_prompt_message).text = messageText

		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			// NOT_FOCUSABLE: keyboard input still routes to the user's app.
			// NOT_TOUCHABLE: tapping the prompt does nothing (UnDo is the
			// dismissal path — keeps the interaction model consistent for
			// users who can't reliably tap).
			// LAYOUT_IN_SCREEN: fill the full screen above status/nav bars.
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		)
		params.gravity = Gravity.TOP

		try {
			windowManager.addView(view, params)
			currentView = view
			onDismissCallback = onDismissed
		} catch (_: Exception) {
			// WindowManager.addView can throw in race conditions (e.g.,
			// overlay permission revoked between check and add). Silently
			// fall through — the caller doesn't need to know.
		}
	}

	/**
	 * Remove the prompt from the screen and invoke the on-dismissed
	 * callback if set. Idempotent — calling when nothing is showing is
	 * a no-op.
	 */
	fun dismiss() {
		val view = currentView ?: return
		currentView = null
		val callback = onDismissCallback
		onDismissCallback = null
		try {
			windowManager.removeView(view)
		} catch (_: Exception) {
			// View may have already been removed under us; ignore.
		}
		callback?.invoke()
	}
}
