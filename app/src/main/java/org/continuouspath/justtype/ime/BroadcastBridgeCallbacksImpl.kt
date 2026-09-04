package org.continuouspath.justtype.ime

import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import java.util.Locale

/**
 * Non-IME-host dependencies that [BroadcastBridgeCallbacksImpl] needs.
 *
 * `executeOnMain` is the dispatch indirection that lets tests invoke the
 * handlers synchronously. Production passes a coroutine-launching lambda;
 * tests pass `block -> block()`.
 *
 * `getVocabularyOperations()` returns the narrow [VocabularyOperations]
 * interface (vs. raw JTUI). Returns null when JTUI isn't yet ready, so
 * callers get the same null-safe semantics as the previous `getJtuiOrNull`
 * surface but only see the 5 operations BroadcastBridge actually uses.
 */
interface BroadcastBridgeCallbacksImplDeps {
	val imeState: IMEState
	fun getVocabularyOperations(): VocabularyOperations?
	fun getActiveVocabMask(): Long
	fun reloadPhrases()
	fun reloadAllPreferences()
	fun clearAllHighlights()
	fun executeOnMain(block: () -> Unit)
}

/**
 * Named impl of [BroadcastBridge.Callbacks], extracted from the inline
 * anonymous object in JustTypeIME.
 *
 * Each handler gates on [IMEState.Ready] and dispatches the body via
 * [BroadcastBridgeCallbacksImplDeps.executeOnMain] so the call sequence
 * (merge → clear → reload → updateUi) is testable without coroutines.
 *
 * Vocabulary mutations go through the narrow [VocabularyOperations]
 * interface — closes the 3.11-pass gap where this impl held a direct
 * JTUI reference.
 */
class BroadcastBridgeCallbacksImpl(
	private val deps: BroadcastBridgeCallbacksImplDeps,
) : BroadcastBridge.Callbacks {

	override fun onVocabUpdated(sourceMask: Long, targetMask: Long, deleteMask: Long) {
		if (deps.imeState !is IMEState.Ready) return
		deps.executeOnMain {
			val vocab = deps.getVocabularyOperations() ?: return@executeOnMain
			DebugLogger.log(DebugCategory.WordDb) {
				"[vocabUpdatedReceiver] activeMask=${hex(deps.getActiveVocabMask())} " +
					"mergeSource=${hex(sourceMask)} mergeTarget=${hex(targetMask)} " +
					"deleteMask=${hex(deleteMask)}"
			}
			if (sourceMask != 0L && targetMask != 0L) {
				vocab.mergeVocabularyMasks(sourceMask, targetMask)
			}
			if (deleteMask != 0L) {
				vocab.clearVocabularyMasks(deleteMask)
			}
			vocab.reloadVocabularyFromDb()
			vocab.forceUpdateUi()
		}
	}

	override fun onDataRestored() {
		if (deps.imeState !is IMEState.Ready) return
		deps.executeOnMain {
			val vocab = deps.getVocabularyOperations() ?: return@executeOnMain
			deps.reloadPhrases()
			vocab.init()
			deps.reloadAllPreferences()
			vocab.forceUpdateUi()
		}
	}

	override fun onClearHighlights() {
		deps.clearAllHighlights()
	}

	private fun hex(mask: Long): String = "0x" + mask.toString(16).uppercase(Locale.getDefault())
}
