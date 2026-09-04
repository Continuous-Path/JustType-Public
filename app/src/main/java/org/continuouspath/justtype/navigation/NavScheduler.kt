package org.continuouspath.justtype.navigation

import android.os.Handler

/**
 * Tokened postDelayed for pending nav callbacks (edge-scroll reselect, double-tap
 * second click), so a newer press/window change can cancel superseded work.
 */
class NavScheduler(private val handler: Handler) {
	private val pending = mutableMapOf<Any, Runnable>()

	fun post(token: Any, delayMs: Long, action: () -> Unit) {
		cancel(token)
		val runnable = Runnable {
			pending.remove(token)
			action()
		}
		pending[token] = runnable
		handler.postDelayed(runnable, delayMs)
	}

	fun cancel(token: Any) {
		pending.remove(token)?.let(handler::removeCallbacks)
	}

	fun cancelAll() {
		pending.values.forEach(handler::removeCallbacks)
		pending.clear()
	}
}
