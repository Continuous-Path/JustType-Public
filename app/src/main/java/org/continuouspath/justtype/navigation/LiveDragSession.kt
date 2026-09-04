package org.continuouspath.justtype.navigation

import android.accessibilityservice.GestureDescription

/**
 * The continued-stroke primitives a live-held drag needs; [GestureDispatcher] implements it.
 * Each call dispatches one stroke and delivers the running stroke via `onResult` when it completes
 * (null on cancel/failure), so the caller chains the next continuation from the callback — sequential
 * dispatch, never a synchronous burst (which the injector rejects and cancels).
 */
interface LiveStrokeDispatcher {
	fun beginDragStroke(x: Int, y: Int, onResult: (GestureDescription.StrokeDescription?) -> Unit): Boolean
	fun continueDragStroke(
		previous: GestureDescription.StrokeDescription,
		fromX: Int,
		fromY: Int,
		x: Int,
		y: Int,
		willContinue: Boolean,
		onResult: (GestureDescription.StrokeDescription?) -> Unit,
	): Boolean
}

/**
 * A live-held drag: the pointer is pressed down at pickup and held DOWN while the user extends the
 * drag one step at a time in real time (each arrow press calls [extendTo]), then lifted by [release].
 *
 * Strokes MUST be dispatched sequentially — each continuation fired only from the previous stroke's
 * completion callback, never synchronously. A synchronous burst is matched against the injector's
 * last-sent sample before the previous stroke has played, so it is rejected and cancels the pending
 * gesture (the pointer never lands). So this is a queue: targets are enqueued by [begin]/[extendTo]/
 * [release]/keep-alive and drained one at a time by [pump], which dispatches the next only when the
 * previous completes. A keep-alive enqueues a 1px in-place wiggle while idle so the motion stream
 * never goes silent (some app drag engines drop a drag on a motion gap).
 *
 * The swappable seam noted on [DragController]: the service picks this or the pre-baked path by the
 * live-drag setting; both reuse the same continued-stroke primitives.
 */
class LiveDragSession(
	private val gestures: LiveStrokeDispatcher,
	private val scheduler: NavScheduler? = null,
	private val keepAliveMs: Long = KEEP_ALIVE_MS,
) {
	private enum class Kind { HOLD, MOVE, LIFT }
	private data class Target(val x: Int, val y: Int, val kind: Kind) {
		val willContinue: Boolean get() = kind != Kind.LIFT
	}

	private var running: GestureDescription.StrokeDescription? = null
	private var active = false // a session exists (finger down or coming down)
	private var dispatching = false // a stroke is in flight; the next waits for its callback
	private var curX = 0
	private var curY = 0
	private var wiggleOut = false
	private val queue = ArrayDeque<Target>()

	val isActive: Boolean get() = active

	/** Press down at ([x], [y]) and hold in place long enough for a long-press pickup to arm. */
	fun begin(x: Int, y: Int): Boolean {
		if (active) return false
		active = true
		dispatching = true
		curX = x
		curY = y
		val queued = gestures.beginDragStroke(x, y) { stroke ->
			dispatching = false
			if (stroke == null) {
				teardown()
			} else {
				running = stroke
				// Enqueue the hold ticks (1px in-place wiggle) so the launcher long-press arms.
				repeat(HOLD_TICKS) { i -> queue.add(Target(if (i % 2 == 0) x + 1 else x, y, Kind.HOLD)) }
				pump()
			}
		}
		if (!queued) teardown()
		return queued
	}

	/**
	 * Extend the held drag to ([x], [y]). Coalesces: pending (not-yet-dispatched) moves are dropped
	 * and replaced with this latest target, so the finger heads straight to where the cursor is now
	 * instead of crawling through every stale intermediate point — sequential dispatch rate-limits how
	 * fast strokes play, so without coalescing a burst of presses would lag far behind. Hold ticks are
	 * kept (the pickup may still be arming). No-op returning false if the session isn't active.
	 */
	fun extendTo(x: Int, y: Int): Boolean {
		if (!active) return false
		queue.removeAll { it.kind == Kind.MOVE }
		queue.add(Target(x, y, Kind.MOVE))
		pump()
		return true
	}

	/** Lift the pointer at the current point, committing the drop. */
	fun release(): Boolean {
		if (!active) return false
		cancelKeepAlive()
		// Lift at the pending move target if one is queued, else the current point; drop everything else.
		val target = queue.lastOrNull { it.kind == Kind.MOVE }
		queue.clear()
		queue.add(Target(target?.x ?: curX, target?.y ?: curY, Kind.LIFT))
		pump()
		return true
	}

	/**
	 * End the session without a deliberate drop (BACK / window change). Releases in place - the
	 * framework offers no app-triggerable cancel that every app honors, so a drop-in-place is the
	 * safe teardown; a leaked held pointer would wedge the touch system.
	 */
	fun abort() {
		if (active) release()
	}

	// Dispatch the next queued target if idle; the callback pumps the one after it.
	private fun pump() {
		if (dispatching || !active) return
		val prev = running
		val next = queue.removeFirstOrNull()
		if (next == null) {
			scheduleKeepAlive() // queue drained — keep the pointer alive until more input or teardown
			return
		}
		if (prev == null) {
			teardown()
			return
		}
		dispatching = true
		val queued = gestures.continueDragStroke(prev, curX, curY, next.x, next.y, next.willContinue) { stroke ->
			dispatching = false
			when {
				stroke == null -> teardown()
				!next.willContinue -> teardown() // the release stroke lifted the pointer; session over
				else -> {
					running = stroke
					curX = next.x
					curY = next.y
					pump()
				}
			}
		}
		if (!queued) teardown()
	}

	// While idle (queue empty, finger held), enqueue a 1px in-place wiggle so motion never goes silent.
	// Oscillates out 1px then back so the held target never drifts.
	private fun scheduleKeepAlive() {
		val s = scheduler ?: return
		s.post(KEEP_ALIVE_TOKEN, keepAliveMs) {
			if (!active || dispatching || queue.isNotEmpty()) return@post
			wiggleOut = !wiggleOut
			queue.add(Target(if (wiggleOut) curX + 1 else curX - 1, curY, Kind.MOVE))
			pump()
		}
	}

	private fun cancelKeepAlive() {
		scheduler?.cancel(KEEP_ALIVE_TOKEN)
	}

	private fun teardown() {
		cancelKeepAlive()
		queue.clear()
		running = null
		active = false
		dispatching = false
	}

	companion object {
		// Same dwell trick as the pre-baked pickup: enough in-place ticks to clear the long-press.
		private const val HOLD_TICKS = 8

		// Idle-gap keep-alive: short enough the motion stream never goes silent long enough for a
		// launcher to drop the drag, but not so tight it floods the injector.
		private const val KEEP_ALIVE_MS = 100L
		private val KEEP_ALIVE_TOKEN = Any()
	}
}
