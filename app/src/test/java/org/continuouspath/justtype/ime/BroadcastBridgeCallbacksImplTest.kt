package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.logic.JTUI
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for [BroadcastBridgeCallbacksImpl] handler logic.
 *
 * The impl owns: imeState gating, mask-branch decisions, vocab-op call ordering.
 * `executeOnMain` is overridden in tests to run synchronously, so the body is
 * inspectable without coroutine machinery.
 *
 * Vocabulary operations are exercised via a fake [VocabularyOperations]
 * implementation (not a JTUI mock) — this is the 3.13.6 narrowing pay-off.
 * The test no longer needs to know JTUI exists.
 */
class BroadcastBridgeCallbacksImplTest {

	// jtui is only kept around as the "Ready" payload — we never read its
	// methods, since the impl now goes through VocabularyOperations.
	private val jtui: JTUI = mock()
	private val deps = FakeDeps()
	private lateinit var subject: BroadcastBridgeCallbacksImpl

	@Before
	fun setUp() {
		deps.reset()
		subject = BroadcastBridgeCallbacksImpl(deps)
	}

	// ── onVocabUpdated: imeState gate ─────────────────────────────────────

	@Test
	fun `onVocabUpdated no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.onVocabUpdated(1L, 2L, 4L)
		assertThat(deps.executeOnMainCount).isEqualTo(0)
		assertThat(deps.fakeVocab.callOrder).isEmpty()
	}

	// ── onVocabUpdated: merge branch ──────────────────────────────────────

	@Test
	fun `onVocabUpdated merges when both source and target are nonzero`() {
		deps.setState(IMEState.Ready(jtui))
		subject.onVocabUpdated(sourceMask = 0x1L, targetMask = 0x2L, deleteMask = 0L)
		assertThat(deps.fakeVocab.mergeCalls).containsExactly(0x1L to 0x2L)
	}

	@Test
	fun `onVocabUpdated does not merge when only source is set`() {
		deps.setState(IMEState.Ready(jtui))
		subject.onVocabUpdated(sourceMask = 0x1L, targetMask = 0L, deleteMask = 0L)
		assertThat(deps.fakeVocab.mergeCalls).isEmpty()
	}

	@Test
	fun `onVocabUpdated does not merge when only target is set`() {
		deps.setState(IMEState.Ready(jtui))
		subject.onVocabUpdated(sourceMask = 0L, targetMask = 0x2L, deleteMask = 0L)
		assertThat(deps.fakeVocab.mergeCalls).isEmpty()
	}

	// ── onVocabUpdated: delete branch ─────────────────────────────────────

	@Test
	fun `onVocabUpdated clears when deleteMask nonzero`() {
		deps.setState(IMEState.Ready(jtui))
		subject.onVocabUpdated(sourceMask = 0L, targetMask = 0L, deleteMask = 0x4L)
		assertThat(deps.fakeVocab.clearCalls).containsExactly(0x4L)
	}

	@Test
	fun `onVocabUpdated does not clear when deleteMask is zero`() {
		deps.setState(IMEState.Ready(jtui))
		subject.onVocabUpdated(sourceMask = 0x1L, targetMask = 0x2L, deleteMask = 0L)
		assertThat(deps.fakeVocab.clearCalls).isEmpty()
	}

	// ── onVocabUpdated: call ordering ─────────────────────────────────────

	@Test
	fun `onVocabUpdated runs merge then clear then reload then forceUpdateUi`() {
		deps.setState(IMEState.Ready(jtui))
		subject.onVocabUpdated(sourceMask = 0x1L, targetMask = 0x2L, deleteMask = 0x4L)

		assertThat(deps.fakeVocab.callOrder).containsExactly(
			"mergeVocabularyMasks(0x1, 0x2)",
			"clearVocabularyMasks(0x4)",
			"reloadVocabularyFromDb",
			"forceUpdateUi",
		).inOrder()
	}

	@Test
	fun `onVocabUpdated always runs reload and forceUpdateUi when Ready`() {
		deps.setState(IMEState.Ready(jtui))
		subject.onVocabUpdated(0L, 0L, 0L)
		assertThat(deps.fakeVocab.callOrder).containsExactly(
			"reloadVocabularyFromDb",
			"forceUpdateUi",
		).inOrder()
	}

	// ── onDataRestored ────────────────────────────────────────────────────

	@Test
	fun `onDataRestored no-ops when imeState is Loading`() {
		deps.setState(IMEState.Loading)
		subject.onDataRestored()
		assertThat(deps.reloadPhrasesCount).isEqualTo(0)
		assertThat(deps.reloadAllPreferencesCount).isEqualTo(0)
		assertThat(deps.fakeVocab.callOrder).isEmpty()
	}

	@Test
	fun `onDataRestored runs reload phrases, jtui init, reload prefs, forceUpdateUi in order`() {
		deps.setState(IMEState.Ready(jtui))
		subject.onDataRestored()

		// Per-collaborator counts (deps is a fake)
		assertThat(deps.reloadPhrasesCount).isEqualTo(1)
		assertThat(deps.reloadAllPreferencesCount).isEqualTo(1)

		// VocabularyOperations invocation order
		assertThat(deps.fakeVocab.callOrder).containsExactly(
			"init",
			"forceUpdateUi",
		).inOrder()
	}

	// ── onClearHighlights ─────────────────────────────────────────────────

	@Test
	fun `onClearHighlights delegates regardless of imeState`() {
		deps.setState(IMEState.Loading)
		subject.onClearHighlights()
		assertThat(deps.clearAllHighlightsCount).isEqualTo(1)

		deps.setState(IMEState.Ready(jtui))
		subject.onClearHighlights()
		assertThat(deps.clearAllHighlightsCount).isEqualTo(2)
	}

	// ── Test fakes ────────────────────────────────────────────────────────

	/**
	 * Records every VocabularyOperations call in order, with arg fingerprints
	 * for the mask methods. Replaces the JTUI mock + Mockito inOrder dance
	 * the prior test used.
	 */
	private class FakeVocabularyOperations : VocabularyOperations {
		val mergeCalls = mutableListOf<Pair<Long, Long>>()
		val clearCalls = mutableListOf<Long>()
		val callOrder = mutableListOf<String>()

		override fun mergeVocabularyMasks(sourceMask: Long, targetMask: Long) {
			mergeCalls.add(sourceMask to targetMask)
			callOrder.add("mergeVocabularyMasks(0x${sourceMask.toString(16)}, 0x${targetMask.toString(16)})")
		}

		override fun clearVocabularyMasks(deleteMask: Long) {
			clearCalls.add(deleteMask)
			callOrder.add("clearVocabularyMasks(0x${deleteMask.toString(16)})")
		}

		override fun reloadVocabularyFromDb() {
			callOrder.add("reloadVocabularyFromDb")
		}

		override fun forceUpdateUi() {
			callOrder.add("forceUpdateUi")
		}

		override fun init() {
			callOrder.add("init")
		}

		fun reset() {
			mergeCalls.clear()
			clearCalls.clear()
			callOrder.clear()
		}
	}

	private inner class FakeDeps : BroadcastBridgeCallbacksImplDeps {
		private var stateBacking: IMEState = IMEState.Loading
		var fakeActiveVocabMask: Long = 0L
		val fakeVocab = FakeVocabularyOperations()

		var reloadPhrasesCount = 0
		var reloadAllPreferencesCount = 0
		var clearAllHighlightsCount = 0
		var executeOnMainCount = 0

		fun setState(state: IMEState) {
			stateBacking = state
		}

		fun reset() {
			stateBacking = IMEState.Loading
			fakeActiveVocabMask = 0L
			fakeVocab.reset()
			reloadPhrasesCount = 0
			reloadAllPreferencesCount = 0
			clearAllHighlightsCount = 0
			executeOnMainCount = 0
		}

		override val imeState: IMEState get() = stateBacking
		override fun getVocabularyOperations(): VocabularyOperations? = if (stateBacking is IMEState.Ready) fakeVocab else null
		override fun getActiveVocabMask(): Long = fakeActiveVocabMask
		override fun reloadPhrases() {
			reloadPhrasesCount++
		}
		override fun reloadAllPreferences() {
			reloadAllPreferencesCount++
		}
		override fun clearAllHighlights() {
			clearAllHighlightsCount++
		}
		override fun executeOnMain(block: () -> Unit) {
			executeOnMainCount++
			block()
		}
	}
}
