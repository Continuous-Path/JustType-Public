package org.continuouspath.justtype.navigation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import org.continuouspath.justtype.navigation.engine.DragGesture
import org.continuouspath.justtype.navigation.engine.SwipeSegment

/** Thin dispatchGesture wrapper - the scroll/tap fallback when no accessibility action works. */
class GestureDispatcher(
	private val service: AccessibilityService,
	// Test seams; default to the live service.
	private val hasGestureCapability: () -> Boolean = {
		service.serviceInfo?.let {
			it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
		} == true
	},
	private val dispatchGesture: (GestureDescription, AccessibilityService.GestureResultCallback?) -> Boolean =
		{ description, callback -> service.dispatchGesture(description, callback, null) },
) : LiveStrokeDispatcher {
	/** False until the user re-grants the service with the gestures capability. */
	val available: Boolean
		get() = hasGestureCapability()

	/** True when the gesture was queued; [onDone] reports how the injection actually ended. */
	fun swipe(segment: SwipeSegment, onDone: (Boolean) -> Unit): Boolean {
		val path = Path().apply {
			moveTo(segment.startX.toFloat(), segment.startY.toFloat())
			lineTo(segment.endX.toFloat(), segment.endY.toFloat())
		}
		return dispatch(path, SWIPE_DURATION_MS, onDone)
	}

	fun tap(x: Int, y: Int, onDone: (Boolean) -> Unit): Boolean {
		val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
		return dispatch(path, TAP_DURATION_MS, onDone)
	}

	/**
	 * Injects [gesture] as one continued press-hold-drag-release. Each segment is its own
	 * [dispatchGesture], streamed back-to-back with a `null` callback (NOT gated on the previous
	 * gesture's `onCompleted` — that would block for the full hold duration and leave the pointer
	 * dead-still across the callback + IPC seam, which a launcher reads as a completed long-press).
	 * Each continuing segment is built via the running stroke's [StrokeDescription.continueStroke]
	 * and its path starts pixel-exact where the previous ended (the injector matches continuations by
	 * the held pointer's last coordinates; a mismatch is rejected), so the pointer stays down through
	 * the whole hold to drag to release as one drag. [onDone] reports whether every segment queued.
	 */
	fun dragGesture(gesture: DragGesture, onDone: (Boolean) -> Unit): Boolean {
		if (!available) return false
		val segments = gesture.segments
		if (segments.isEmpty()) return false
		var previous: GestureDescription.StrokeDescription? = null
		segments.forEachIndexed { index, seg ->
			val willContinue = index < segments.lastIndex
			val path = Path().apply {
				moveTo(seg.startX.toFloat(), seg.startY.toFloat())
				// Zero-length path (no lineTo) = a hold in place; a real move adds the line.
				if (seg.startX != seg.endX || seg.startY != seg.endY) lineTo(seg.endX.toFloat(), seg.endY.toFloat())
			}
			// startTime 0: relative to this segment's own gesture. Duration must be > 0.
			val duration = seg.durationMs.coerceAtLeast(1L)
			val stroke = previous?.continueStroke(path, 0, duration, willContinue)
				?: GestureDescription.StrokeDescription(path, 0, duration, willContinue)
			val description = GestureDescription.Builder().addStroke(stroke).build()
			// null callback: fire immediately, don't wait — that wait is the pause we're removing.
			if (!dispatchGesture(description, null)) {
				onDone(false)
				return false
			}
			previous = stroke
		}
		onDone(true)
		return true
	}

	// ── Live-held drag ─────────────────────────────────────────────────
	// A held drag whose stroke chain is extended in real time as the user presses arrows. Each stroke
	// is dispatched sequentially, the next one fired only from the previous stroke's onCompleted —
	// NOT synchronously. A synchronous burst is racy: a continuation dispatched before the previous
	// stroke has physically played is matched against an empty last-sample buffer, rejected, and it
	// cancels the pending gesture, so the pointer never lands (no events at all). Sequential gating
	// is the framework-blessed pattern (see AOSP CTS testContinuedGestures_motionEventsContinue).
	// Durations are fixed short constants, never real idle time (would exceed MAX_GESTURE_DURATION_MS).

	/**
	 * Press down at ([x], [y]) and hold. [onResult] delivers the running stroke on completion (null on
	 * cancel/failure) so the caller can chain the next continuation. Returns false if unavailable.
	 */
	override fun beginDragStroke(x: Int, y: Int, onResult: (GestureDescription.StrokeDescription?) -> Unit): Boolean {
		if (!available) return false
		// A pure point at the pickup: the down's last emitted point is exactly (x,y), so the first
		// continuation must also start at (x,y). A trailing lineTo here would end the down at x+1 and
		// the injector would reject the next stroke (its start must match the prior stroke's last point).
		val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
		return dispatchLiveStroke(GestureDescription.StrokeDescription(path, 0, LIVE_SEGMENT_MS, true), onResult)
	}

	/**
	 * Extend the held drag from [previous]'s end to ([x], [y]). [willContinue] false lifts the pointer
	 * (the drop/release). [onResult] delivers the new running stroke on completion (null on cancel).
	 */
	override fun continueDragStroke(
		previous: GestureDescription.StrokeDescription,
		fromX: Int,
		fromY: Int,
		x: Int,
		y: Int,
		willContinue: Boolean,
		onResult: (GestureDescription.StrokeDescription?) -> Unit,
	): Boolean {
		if (!available) return false
		val path = Path().apply {
			moveTo(fromX.toFloat(), fromY.toFloat()) // pixel-exact continuation of the held pointer
			if (fromX != x || fromY != y) lineTo(x.toFloat(), y.toFloat())
		}
		return dispatchLiveStroke(previous.continueStroke(path, 0, LIVE_SEGMENT_MS, willContinue), onResult)
	}

	private fun dispatchLiveStroke(
		stroke: GestureDescription.StrokeDescription,
		onResult: (GestureDescription.StrokeDescription?) -> Unit,
	): Boolean {
		val description = GestureDescription.Builder().addStroke(stroke).build()
		val callback = object : AccessibilityService.GestureResultCallback() {
			override fun onCompleted(gestureDescription: GestureDescription?) = onResult(stroke)
			override fun onCancelled(gestureDescription: GestureDescription?) = onResult(null)
		}
		return dispatchGesture(description, callback)
	}

	private fun dispatch(path: Path, durationMs: Long, onDone: (Boolean) -> Unit): Boolean {
		if (!available) return false
		val gesture = GestureDescription.Builder()
			.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
			.build()
		val callback = object : AccessibilityService.GestureResultCallback() {
			override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
			override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
		}
		return dispatchGesture(gesture, callback)
	}

	companion object {
		private const val SWIPE_DURATION_MS = 300L
		private const val TAP_DURATION_MS = 50L

		// Fixed per-segment duration for a live-held drag: long enough to emit a clean move event,
		// short enough to feel responsive. Must be a constant, not real idle time (see beginDragStroke).
		private const val LIVE_SEGMENT_MS = 60L
	}
}
