package org.continuouspath.justtype

/**
 * Tracks in-flight TTS utterances (across both engines) and reports speaking-state
 * transitions, so the visual indicator shows once per burst instead of flickering
 * between consecutive queued utterances.
 *
 * Thread-safe: UtteranceProgressListener callbacks arrive on a binder thread.
 */
class SpeechIndicatorTracker(private val onSpeakingChanged: (Boolean) -> Unit) {

	private val lock = Any()
	private val inFlight = mutableSetOf<String>()
	private var speaking = false

	/**
	 * Record a successfully enqueued utterance. [flushedPrefixes] names id prefixes whose
	 * queue entries a QUEUE_FLUSH just replaced — dropped here in case the engine never
	 * delivers onStop for them.
	 */
	fun onEnqueued(id: String, flushedPrefixes: List<String> = emptyList()) {
		synchronized(lock) {
			if (flushedPrefixes.isNotEmpty()) {
				inFlight.removeAll { entry -> flushedPrefixes.any { entry.startsWith(it) } }
			}
			inFlight.add(id)
		}
	}

	fun onUtteranceStarted() {
		val show = synchronized(lock) {
			if (!speaking) {
				speaking = true
				true
			} else {
				false
			}
		}
		if (show) onSpeakingChanged(true)
	}

	/** Handles onDone, onError, and onStop alike: remove the utterance, hide once drained. */
	fun onUtteranceFinished(id: String?) {
		val hide = synchronized(lock) {
			if (id != null) inFlight.remove(id)
			maybeStopSpeakingLocked()
		}
		if (hide) onSpeakingChanged(false)
	}

	/** One engine was recreated: its in-flight utterances will never call back, so drop them. */
	fun dropPrefixes(prefixes: List<String>) {
		val hide = synchronized(lock) {
			inFlight.removeAll { entry -> prefixes.any { entry.startsWith(it) } }
			maybeStopSpeakingLocked()
		}
		if (hide) onSpeakingChanged(false)
	}

	/** Teardown: pending callbacks will never arrive, so force-hide. */
	fun reset() {
		val hide = synchronized(lock) {
			inFlight.clear()
			maybeStopSpeakingLocked()
		}
		if (hide) onSpeakingChanged(false)
	}

	private fun maybeStopSpeakingLocked(): Boolean {
		if (speaking && inFlight.isEmpty()) {
			speaking = false
			return true
		}
		return false
	}
}
