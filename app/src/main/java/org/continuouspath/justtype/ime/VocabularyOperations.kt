package org.continuouspath.justtype.ime

/**
 * Narrow callback interface exposing the vocabulary-lifecycle operations that
 * [BroadcastBridgeCallbacksImpl] needs from JTUI.
 *
 * Mirrors the 3.11 [PreferenceCoordinatorCallbacks] narrowing pattern: instead
 * of holding a raw `JTUI` reference and reaching into ~5 methods on it,
 * BroadcastBridge consumes this interface, which both documents the contract
 * explicitly and makes vocab IPC behavior testable with a fake instead of a
 * full JTUI mock.
 *
 * The full-JTUI-as-adapter exception remains for [ImeTextCallbacksImpl] (30+
 * methods make narrowing non-viable). With only 5 operations here, the
 * narrowing pays for itself.
 */
interface VocabularyOperations {
	/** Merge `sourceMask` into `targetMask` in JTUI's vocabulary state. */
	fun mergeVocabularyMasks(sourceMask: Long, targetMask: Long)

	/** Drop entries matching `deleteMask` from JTUI's vocabulary state. */
	fun clearVocabularyMasks(deleteMask: Long)

	/** Re-read vocabulary tables from disk into JTUI's in-memory caches. */
	fun reloadVocabularyFromDb()

	/** Trigger a UI refresh after a vocabulary mutation. */
	fun forceUpdateUi()

	/** Re-run JTUI's full init (e.g. after a data-restore broadcast). */
	fun init()
}
