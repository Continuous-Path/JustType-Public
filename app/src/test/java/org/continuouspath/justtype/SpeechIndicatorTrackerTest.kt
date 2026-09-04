package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class SpeechIndicatorTrackerTest {

	private lateinit var tracker: SpeechIndicatorTracker
	private val changes = mutableListOf<Boolean>()

	@Before
	fun setUp() {
		changes.clear()
		tracker = SpeechIndicatorTracker { changes.add(it) }
	}

	@Test
	fun `shows on first start and hides when drained`() {
		tracker.onEnqueued("a:1")
		assertThat(changes).isEmpty()
		tracker.onUtteranceStarted()
		assertThat(changes).containsExactly(true)
		tracker.onUtteranceFinished("a:1")
		assertThat(changes).containsExactly(true, false).inOrder()
	}

	@Test
	fun `queued utterances do not flicker between onDone and next onStart`() {
		tracker.onEnqueued("a:1")
		tracker.onUtteranceStarted()
		tracker.onEnqueued("a:2")
		tracker.onUtteranceFinished("a:1") // a:2 still pending — must not hide
		tracker.onUtteranceStarted()
		tracker.onUtteranceFinished("a:2")
		assertThat(changes).containsExactly(true, false).inOrder()
	}

	@Test
	fun `stop of the only utterance hides`() {
		tracker.onEnqueued("a:1")
		tracker.onUtteranceStarted()
		// tts.stop() / QUEUE_FLUSH lands in onStop, routed to onUtteranceFinished
		tracker.onUtteranceFinished("a:1")
		assertThat(changes).containsExactly(true, false).inOrder()
	}

	@Test
	fun `no callback while never started even when set drains`() {
		tracker.onEnqueued("a:1")
		tracker.onUtteranceFinished("a:1")
		assertThat(changes).isEmpty()
	}

	@Test
	fun `flush prefixes drop stale queue entries so drain still hides`() {
		tracker.onEnqueued("nonint:1") // enqueued but engine never starts it (flushed silently)
		tracker.onEnqueued("amb:2", flushedPrefixes = listOf("nonint:", "amb:"))
		tracker.onUtteranceStarted()
		tracker.onUtteranceFinished("amb:2")
		// Without the prefix drop, the ghost nonint:1 would keep the icon stuck visible.
		assertThat(changes).containsExactly(true, false).inOrder()
	}

	@Test
	fun `flush prefixes leave other engine's utterances alone`() {
		tracker.onEnqueued("nonint:1")
		tracker.onUtteranceStarted()
		tracker.onEnqueued("ui:2", flushedPrefixes = listOf("ui:"))
		tracker.onUtteranceFinished("ui:2")
		assertThat(changes).containsExactly(true) // nonint:1 still speaking — no hide
	}

	@Test
	fun `dropPrefixes hides when it drains the set`() {
		tracker.onEnqueued("nonint:1")
		tracker.onUtteranceStarted()
		tracker.dropPrefixes(listOf("nonint:"))
		assertThat(changes).containsExactly(true, false).inOrder()
	}

	@Test
	fun `dropPrefixes keeps icon while other prefix still in flight`() {
		tracker.onEnqueued("nonint:1")
		tracker.onEnqueued("ui:2")
		tracker.onUtteranceStarted()
		tracker.dropPrefixes(listOf("ui:"))
		assertThat(changes).containsExactly(true)
	}

	@Test
	fun `reset force-hides while speaking`() {
		tracker.onEnqueued("a:1")
		tracker.onUtteranceStarted()
		tracker.reset()
		assertThat(changes).containsExactly(true, false).inOrder()
	}

	@Test
	fun `reset while idle emits nothing`() {
		tracker.reset()
		assertThat(changes).isEmpty()
	}

	@Test
	fun `unknown finished id with empty set still hides after untracked start`() {
		tracker.onUtteranceStarted() // utterance enqueued before tracker attached
		tracker.onUtteranceFinished("ghost:1")
		assertThat(changes).containsExactly(true, false).inOrder()
	}

	@Test
	fun `second burst shows again after first drained`() {
		tracker.onEnqueued("a:1")
		tracker.onUtteranceStarted()
		tracker.onUtteranceFinished("a:1")
		tracker.onEnqueued("a:2")
		tracker.onUtteranceStarted()
		tracker.onUtteranceFinished("a:2")
		assertThat(changes).containsExactly(true, false, true, false).inOrder()
	}
}
