package org.continuouspath.justtype.ime

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.ACTION_DATA_RESTORED
import org.continuouspath.justtype.Constants.ACTION_VOCAB_UPDATED
import org.continuouspath.justtype.Constants.EXTRA_VOCAB_DELETE_MASK
import org.continuouspath.justtype.Constants.EXTRA_VOCAB_MERGE_SOURCE_MASK
import org.continuouspath.justtype.Constants.EXTRA_VOCAB_MERGE_TARGET_MASK
import org.continuouspath.justtype.receiver.ClearHighlightsReceiver
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
class BroadcastBridgeTest {

	private lateinit var bridge: BroadcastBridge
	private val context get() = RuntimeEnvironment.getApplication()
	private val shadow: ShadowApplication get() = shadowOf(context)

	// Tracking variables for callback invocations
	private var vocabSourceMask = 0L
	private var vocabTargetMask = 0L
	private var vocabDeleteMask = 0L
	private var vocabCallCount = 0
	private var dataRestoredCallCount = 0
	private var clearHighlightsCallCount = 0

	private val callbacks = object : BroadcastBridge.Callbacks {
		override fun onVocabUpdated(sourceMask: Long, targetMask: Long, deleteMask: Long) {
			vocabSourceMask = sourceMask
			vocabTargetMask = targetMask
			vocabDeleteMask = deleteMask
			vocabCallCount++
		}

		override fun onDataRestored() {
			dataRestoredCallCount++
		}

		override fun onClearHighlights() {
			clearHighlightsCallCount++
		}
	}

	@Before
	fun setUp() {
		bridge = BroadcastBridge(context)
	}

	@After
	fun tearDown() {
		bridge.unregisterAll()
	}

	/**
	 * Finds the registered receiver wrapper for the given action and delivers
	 * the intent directly. Robolectric's 5-arg registerReceiver (with RECEIVER_EXPORTED)
	 * doesn't always auto-deliver via sendBroadcast, so we dispatch manually.
	 */
	private fun deliverBroadcast(intent: Intent) {
		val action = intent.action
		for (wrapper in shadow.registeredReceivers) {
			if (wrapper.intentFilter.matchAction(action)) {
				wrapper.broadcastReceiver.onReceive(context, intent)
				return
			}
		}
	}

	// ── Registration ────────────────────────────────────────────────────

	@Test
	fun `registerAll registers three receivers`() {
		bridge.registerAll(callbacks)
		val actions = shadow.registeredReceivers.map { it.intentFilter.getAction(0) }.toSet()
		assertThat(actions).contains(ACTION_VOCAB_UPDATED)
		assertThat(actions).contains(ACTION_DATA_RESTORED)
		assertThat(actions).contains(ClearHighlightsReceiver.ACTION_CLEAR_HIGHLIGHTS)
	}

	// ── Callback delivery ───────────────────────────────────────────────

	@Test
	fun `vocabUpdated broadcast delivers mask values to callback`() {
		bridge.registerAll(callbacks)
		deliverBroadcast(
			Intent(ACTION_VOCAB_UPDATED).apply {
				putExtra(EXTRA_VOCAB_MERGE_SOURCE_MASK, 0xAL)
				putExtra(EXTRA_VOCAB_MERGE_TARGET_MASK, 0xBL)
				putExtra(EXTRA_VOCAB_DELETE_MASK, 0xCL)
			},
		)
		assertThat(vocabCallCount).isEqualTo(1)
		assertThat(vocabSourceMask).isEqualTo(0xAL)
		assertThat(vocabTargetMask).isEqualTo(0xBL)
		assertThat(vocabDeleteMask).isEqualTo(0xCL)
	}

	@Test
	fun `vocabUpdated broadcast defaults masks to zero when extras missing`() {
		bridge.registerAll(callbacks)
		deliverBroadcast(Intent(ACTION_VOCAB_UPDATED))
		assertThat(vocabCallCount).isEqualTo(1)
		assertThat(vocabSourceMask).isEqualTo(0L)
		assertThat(vocabTargetMask).isEqualTo(0L)
		assertThat(vocabDeleteMask).isEqualTo(0L)
	}

	@Test
	fun `dataRestored broadcast invokes callback`() {
		bridge.registerAll(callbacks)
		deliverBroadcast(Intent(ACTION_DATA_RESTORED))
		assertThat(dataRestoredCallCount).isEqualTo(1)
	}

	@Test
	fun `clearHighlights broadcast invokes callback`() {
		bridge.registerAll(callbacks)
		deliverBroadcast(Intent(ClearHighlightsReceiver.ACTION_CLEAR_HIGHLIGHTS))
		assertThat(clearHighlightsCallCount).isEqualTo(1)
	}

	// ── Unregister lifecycle ────────────────────────────────────────────

	@Test
	fun `unregisterAll removes all receivers`() {
		bridge.registerAll(callbacks)
		bridge.unregisterAll()
		val actions = shadow.registeredReceivers.map { it.intentFilter.getAction(0) }.toSet()
		assertThat(actions).doesNotContain(ACTION_VOCAB_UPDATED)
		assertThat(actions).doesNotContain(ACTION_DATA_RESTORED)
		assertThat(actions).doesNotContain(ClearHighlightsReceiver.ACTION_CLEAR_HIGHLIGHTS)
	}

	@Test
	fun `double unregisterAll does not crash`() {
		bridge.registerAll(callbacks)
		bridge.unregisterAll()
		bridge.unregisterAll() // should be a no-op
	}

	@Test
	fun `unregisterAll before registerAll does not crash`() {
		bridge.unregisterAll() // no receivers registered yet — should be safe
	}
}
