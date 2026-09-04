package org.continuouspath.justtype.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import org.continuouspath.justtype.Constants.ACTION_DATA_RESTORED
import org.continuouspath.justtype.Constants.ACTION_VOCAB_UPDATED
import org.continuouspath.justtype.Constants.EXTRA_VOCAB_DELETE_MASK
import org.continuouspath.justtype.Constants.EXTRA_VOCAB_MERGE_SOURCE_MASK
import org.continuouspath.justtype.Constants.EXTRA_VOCAB_MERGE_TARGET_MASK
import org.continuouspath.justtype.Constants.PERMISSION_RECEIVE_HEADBOARD_EVENT
import org.continuouspath.justtype.receiver.ClearHighlightsReceiver

/**
 * Owns the three data-oriented [BroadcastReceiver]s that were previously registered
 * inline in [org.continuouspath.justtype.JustTypeIME.onCreate] and torn down in `onDestroy`.
 *
 * Head-tracking receivers (`externalInputReceiver`, `headBoardStateReceiver`) are
 * intentionally excluded — they depend on `htProcessingHandler` and HeadBoard IPC
 * and will move with the HeadTrackingSubsystem extraction (Phase 2 Step 6a).
 *
 * @param context Service context used for [Context.registerReceiver]
 */
class BroadcastBridge(private val context: Context) {

	/**
	 * Callback interface implemented by the IME to handle broadcast events.
	 */
	interface Callbacks {
		/** Vocabulary masks were updated (merge and/or delete). */
		fun onVocabUpdated(sourceMask: Long, targetMask: Long, deleteMask: Long)

		/** Full data restore completed — reload phrases, settings, and UI. */
		fun onDataRestored()

		/** HeadBoard requested all key highlights be cleared. */
		fun onClearHighlights()
	}

	private var vocabUpdatedReceiver: BroadcastReceiver? = null
	private var dataRestoredReceiver: BroadcastReceiver? = null
	private var clearHighlightsReceiver: ClearHighlightsReceiver? = null

	/**
	 * Registers all three receivers. Safe to call only once — call [unregisterAll]
	 * before calling again.
	 */
	fun registerAll(callbacks: Callbacks) {
		clearHighlightsReceiver = ClearHighlightsReceiver(
			onClearHighlights = { callbacks.onClearHighlights() },
		)
		// HeadBoard-sent: enforce the sender permission.
		ContextCompat.registerReceiver(
			context,
			clearHighlightsReceiver,
			IntentFilter(ClearHighlightsReceiver.ACTION_CLEAR_HIGHLIGHTS),
			PERMISSION_RECEIVE_HEADBOARD_EVENT,
			null,
			ContextCompat.RECEIVER_EXPORTED,
		)

		vocabUpdatedReceiver = object : BroadcastReceiver() {
			override fun onReceive(ctx: Context?, intent: Intent?) {
				val sourceMask = intent?.getLongExtra(EXTRA_VOCAB_MERGE_SOURCE_MASK, 0L) ?: 0L
				val targetMask = intent?.getLongExtra(EXTRA_VOCAB_MERGE_TARGET_MASK, 0L) ?: 0L
				val deleteMask = intent?.getLongExtra(EXTRA_VOCAB_DELETE_MASK, 0L) ?: 0L
				callbacks.onVocabUpdated(sourceMask, targetMask, deleteMask)
			}
		}
		// JT-internal (sent by our own activities): not visible to other apps.
		ContextCompat.registerReceiver(
			context,
			vocabUpdatedReceiver,
			IntentFilter(ACTION_VOCAB_UPDATED),
			null,
			null,
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)

		dataRestoredReceiver = object : BroadcastReceiver() {
			override fun onReceive(ctx: Context?, intent: Intent?) {
				callbacks.onDataRestored()
			}
		}
		ContextCompat.registerReceiver(
			context,
			dataRestoredReceiver,
			IntentFilter(ACTION_DATA_RESTORED),
			null,
			null,
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
	}

	/**
	 * Unregisters all receivers. Safe to call multiple times — subsequent calls are no-ops.
	 */
	fun unregisterAll() {
		clearHighlightsReceiver?.let {
			try {
				context.unregisterReceiver(it)
			} catch (_: Exception) {}
		}
		clearHighlightsReceiver = null

		vocabUpdatedReceiver?.let {
			try {
				context.unregisterReceiver(it)
			} catch (_: Exception) {}
		}
		vocabUpdatedReceiver = null

		dataRestoredReceiver?.let {
			try {
				context.unregisterReceiver(it)
			} catch (_: Exception) {}
		}
		dataRestoredReceiver = null
	}
}
