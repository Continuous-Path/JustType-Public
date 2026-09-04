package org.continuouspath.justtype.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver that handles clear highlights requests from HeadBoard.
 */
class ClearHighlightsReceiver(
	private val onClearHighlights: (() -> Unit)? = null,
) : BroadcastReceiver() {
	companion object {
		const val ACTION_CLEAR_HIGHLIGHTS = "org.continuouspath.justtype.CLEAR_HIGHLIGHTS"
	}

	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		if (intent.action == ACTION_CLEAR_HIGHLIGHTS) {
			onClearHighlights?.invoke()
		}
	}
}
