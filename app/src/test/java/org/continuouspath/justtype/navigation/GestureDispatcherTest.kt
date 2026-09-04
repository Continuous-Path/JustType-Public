package org.continuouspath.justtype.navigation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.RectF
import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.navigation.engine.DragGesture
import org.continuouspath.justtype.navigation.engine.DragSegment
import org.continuouspath.justtype.navigation.engine.SwipeSegment
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Pins the stroke construction the injector actually sees — the layer of the
 * historical dead-drag bug (a continuation whose start doesn't pixel-match the
 * prior stroke's last emitted point cancels the whole gesture). Dispatch is
 * captured via the GestureDispatcher test seams; real injection outcomes are
 * on-device territory.
 */
@RunWith(RobolectricTestRunner::class)
class GestureDispatcherTest {

	class StubService : AccessibilityService() {
		override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
		override fun onInterrupt() = Unit
	}

	private lateinit var service: AccessibilityService
	private val dispatched = mutableListOf<Pair<GestureDescription, AccessibilityService.GestureResultCallback?>>()
	private var dispatchReturns = true

	@Before
	fun setUp() {
		service = Robolectric.setupService(StubService::class.java)
	}

	private fun dispatcher(capable: Boolean = true) = GestureDispatcher(
		service = service,
		hasGestureCapability = { capable },
		dispatchGesture = { description, callback ->
			dispatched.add(description to callback)
			dispatchReturns
		},
	)

	private fun strokeBounds(description: GestureDescription, i: Int = 0): RectF {
		val bounds = RectF()
		description.getStroke(i).path.computeBounds(bounds, true)
		return bounds
	}

	// ── availability gate ─────────────────────────────────────────────────

	@Test
	fun `nothing dispatches without the gestures capability`() {
		val d = dispatcher(capable = false)
		var done: Boolean? = null
		assertThat(d.swipe(SwipeSegment(0, 0, 10, 10), onDone = { done = it })).isFalse()
		assertThat(d.tap(1, 1, onDone = { done = it })).isFalse()
		assertThat(d.beginDragStroke(1, 1) { }).isFalse()
		assertThat(dispatched).isEmpty()
		assertThat(done).isNull()
	}

	// ── live-held drag stroke construction ────────────────────────────────

	@Test
	fun `beginDragStroke dispatches a pure point that stays down`() {
		dispatcher().beginDragStroke(40, 70) { }

		assertThat(dispatched).hasSize(1)
		val description = dispatched.single().first
		assertThat(description.strokeCount).isEqualTo(1)
		val stroke = description.getStroke(0)
		// Pure point at the pickup: no trailing lineTo, so the down's last
		// emitted point is exactly (40,70) and the continuation can match it.
		assertThat(strokeBounds(description)).isEqualTo(RectF(40f, 70f, 40f, 70f))
		assertThat(stroke.willContinue()).isTrue()
		assertThat(stroke.duration).isGreaterThan(0)
	}

	@Test
	fun `continueDragStroke starts pixel-exact at the prior end and lifts when told`() {
		val d = dispatcher()
		var running: GestureDescription.StrokeDescription? = null
		d.beginDragStroke(40, 70) { running = it }
		// Simulate the framework completing the down.
		dispatched.single().second!!.onCompleted(null)
		assertThat(running).isNotNull()

		d.continueDragStroke(running!!, fromX = 40, fromY = 70, x = 90, y = 70, willContinue = true) { }
		assertThat(dispatched).hasSize(2)
		val move = dispatched[1].first
		// The path spans exactly prior-end → new point.
		assertThat(strokeBounds(move)).isEqualTo(RectF(40f, 70f, 90f, 70f))
		assertThat(move.getStroke(0).willContinue()).isTrue()

		d.continueDragStroke(running!!, fromX = 90, fromY = 70, x = 90, y = 70, willContinue = false) { }
		val lift = dispatched[2].first
		// from == to: zero-length hold (pure point), and the pointer lifts.
		assertThat(strokeBounds(lift)).isEqualTo(RectF(90f, 70f, 90f, 70f))
		assertThat(lift.getStroke(0).willContinue()).isFalse()
	}

	@Test
	fun `cancelled live stroke reports null so the caller can abort the chain`() {
		val d = dispatcher()
		var result: GestureDescription.StrokeDescription? = GestureDescription.StrokeDescription(
			android.graphics.Path().apply { moveTo(0f, 0f) },
			0,
			1,
		)
		d.beginDragStroke(5, 5) { result = it }
		dispatched.single().second!!.onCancelled(null)
		assertThat(result).isNull()
	}

	// ── pre-baked drag chain ──────────────────────────────────────────────

	@Test
	fun `dragGesture dispatches every segment with hold segments as pure points`() {
		val gesture = DragGesture(
			listOf(
				DragSegment(startX = 10, startY = 20, endX = 10, endY = 20, durationMs = 400), // hold
				DragSegment(startX = 10, startY = 20, endX = 60, endY = 20, durationMs = 120), // move
				DragSegment(startX = 60, startY = 20, endX = 60, endY = 20, durationMs = 40), // release hold
			),
		)
		var done: Boolean? = null
		assertThat(dispatcher().dragGesture(gesture) { done = it }).isTrue()

		assertThat(done).isTrue()
		assertThat(dispatched).hasSize(3)
		assertThat(strokeBounds(dispatched[0].first)).isEqualTo(RectF(10f, 20f, 10f, 20f))
		assertThat(strokeBounds(dispatched[1].first)).isEqualTo(RectF(10f, 20f, 60f, 20f))
		assertThat(strokeBounds(dispatched[2].first)).isEqualTo(RectF(60f, 20f, 60f, 20f))
		// All but the last stay down; the last lifts.
		assertThat(dispatched[0].first.getStroke(0).willContinue()).isTrue()
		assertThat(dispatched[1].first.getStroke(0).willContinue()).isTrue()
		assertThat(dispatched[2].first.getStroke(0).willContinue()).isFalse()
		// Streamed with null callbacks (not gated on onCompleted).
		assertThat(dispatched.all { it.second == null }).isTrue()
	}

	@Test
	fun `dragGesture stops at the first failed dispatch and reports failure`() {
		val gesture = DragGesture(
			listOf(
				DragSegment(startX = 0, startY = 0, endX = 0, endY = 0, durationMs = 10),
				DragSegment(startX = 0, startY = 0, endX = 5, endY = 5, durationMs = 10),
			),
		)
		dispatchReturns = false
		var done: Boolean? = null
		assertThat(dispatcher().dragGesture(gesture) { done = it }).isFalse()
		assertThat(done).isFalse()
		assertThat(dispatched).hasSize(1) // no further segments after the failure
	}

	// ── tap + swipe ───────────────────────────────────────────────────────

	@Test
	fun `tap is a point stroke and routes the callback result`() {
		var done: Boolean? = null
		dispatcher().tap(33, 44) { done = it }
		val (description, callback) = dispatched.single()
		assertThat(strokeBounds(description)).isEqualTo(RectF(33f, 44f, 33f, 44f))
		callback!!.onCompleted(null)
		assertThat(done).isTrue()
	}

	@Test
	fun `swipe spans start to end and reports cancellation`() {
		var done: Boolean? = null
		dispatcher().swipe(SwipeSegment(0, 100, 0, 300), onDone = { done = it })
		val (description, callback) = dispatched.single()
		assertThat(strokeBounds(description)).isEqualTo(RectF(0f, 100f, 0f, 300f))
		callback!!.onCancelled(null)
		assertThat(done).isFalse()
	}
}
