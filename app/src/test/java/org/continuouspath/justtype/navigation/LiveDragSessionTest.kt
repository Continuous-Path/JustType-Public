package org.continuouspath.justtype.navigation

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveDragSessionTest {

	private data class Step(val fromX: Int, val fromY: Int, val x: Int, val y: Int, val willContinue: Boolean)

	/**
	 * Records the dispatched steps and invokes each onResult synchronously (simulating immediate
	 * stroke completion), so the session's callback-gated queue drains deterministically in the test.
	 */
	private class FakeDispatcher(private val failAfter: Int = Int.MAX_VALUE) : LiveStrokeDispatcher {
		val steps = mutableListOf<Step>()
		private var calls = 0

		private fun stroke(willContinue: Boolean): GestureDescription.StrokeDescription {
			val path = Path().apply {
				moveTo(0f, 0f)
				lineTo(1f, 0f)
			}
			return GestureDescription.StrokeDescription(path, 0, 1, willContinue)
		}

		override fun beginDragStroke(
			x: Int,
			y: Int,
			onResult: (GestureDescription.StrokeDescription?) -> Unit,
		): Boolean {
			steps.add(Step(x, y, x, y, true))
			val fail = calls++ >= failAfter
			onResult(if (fail) null else stroke(true))
			return true
		}

		override fun continueDragStroke(
			previous: GestureDescription.StrokeDescription,
			fromX: Int,
			fromY: Int,
			x: Int,
			y: Int,
			willContinue: Boolean,
			onResult: (GestureDescription.StrokeDescription?) -> Unit,
		): Boolean {
			steps.add(Step(fromX, fromY, x, y, willContinue))
			val fail = calls++ >= failAfter
			onResult(if (fail) null else stroke(willContinue))
			return true
		}
	}

	@Test
	fun `inactive until begin`() {
		val session = LiveDragSession(FakeDispatcher())
		assertThat(session.isActive).isFalse()
		assertThat(session.extendTo(10, 10)).isFalse()
		assertThat(session.release()).isFalse()
	}

	@Test
	fun `begin presses down and dwells with in-place hold ticks`() {
		val fake = FakeDispatcher()
		val session = LiveDragSession(fake)
		assertThat(session.begin(100, 200)).isTrue()
		assertThat(session.isActive).isTrue()
		// First step is the press-down at the pickup; the rest are in-place hold ticks.
		assertThat(fake.steps.first()).isEqualTo(Step(100, 200, 100, 200, true))
		val ticks = fake.steps.drop(1)
		assertThat(ticks).isNotEmpty()
		ticks.forEach { s ->
			assertThat(s.y).isEqualTo(200)
			assertThat(s.x).isIn(100..101)
			assertThat(s.willContinue).isTrue()
		}
	}

	@Test
	fun `every step starts pixel-exact where the previous ended`() {
		val fake = FakeDispatcher()
		val session = LiveDragSession(fake)
		session.begin(100, 200)
		session.extendTo(300, 400)
		session.extendTo(500, 600)
		// The down has from==to; every continuation starts where the previous ended.
		fake.steps.drop(1).zipWithNext().forEach { (a, b) ->
			assertThat(b.fromX).isEqualTo(a.x)
			assertThat(b.fromY).isEqualTo(a.y)
		}
	}

	@Test
	fun `the first hold tick continues from the exact pickup point`() {
		// The injector rejects a continuation whose start does not match the down's last emitted point.
		// The down is a pure point at the pickup, so the first hold tick MUST start at the pickup — an
		// off-by-one here cancels the whole drag on tick one (regression: down ended at x+1, tick from x).
		val fake = FakeDispatcher()
		val session = LiveDragSession(fake)
		session.begin(100, 200)
		val firstContinuation = fake.steps[1]
		assertThat(firstContinuation.fromX).isEqualTo(100)
		assertThat(firstContinuation.fromY).isEqualTo(200)
	}

	@Test
	fun `extendTo drags the held pointer to the new point`() {
		val fake = FakeDispatcher()
		val session = LiveDragSession(fake)
		session.begin(100, 200)
		session.extendTo(300, 400)
		val move = fake.steps.last()
		assertThat(move.x).isEqualTo(300)
		assertThat(move.y).isEqualTo(400)
		assertThat(move.willContinue).isTrue()
	}

	@Test
	fun `release lifts the pointer and clears the session`() {
		val fake = FakeDispatcher()
		val session = LiveDragSession(fake)
		session.begin(100, 200)
		session.extendTo(300, 400)
		assertThat(session.release()).isTrue()
		val last = fake.steps.last()
		assertThat(last.willContinue).isFalse() // the lift
		assertThat(last.x).isEqualTo(300)
		assertThat(last.y).isEqualTo(400)
		assertThat(session.isActive).isFalse()
		assertThat(session.release()).isFalse() // no double release
	}

	@Test
	fun `abort releases in place and clears the session`() {
		val fake = FakeDispatcher()
		val session = LiveDragSession(fake)
		session.begin(100, 200)
		session.extendTo(300, 400)
		session.abort()
		assertThat(fake.steps.last().willContinue).isFalse() // lifted, not left down
		assertThat(session.isActive).isFalse()
	}

	@Test
	fun `a failed press-down leaves the session inactive`() {
		// The down is queued (begin returns true) but its callback reports failure, tearing down.
		val session = LiveDragSession(FakeDispatcher(failAfter = 0))
		session.begin(100, 200)
		assertThat(session.isActive).isFalse()
	}

	@Test
	fun `a cancelled stroke tears the session down`() {
		// down + 8 ticks succeed (9 calls); the extend (10th) is cancelled.
		val fake = FakeDispatcher(failAfter = 9)
		val session = LiveDragSession(fake)
		assertThat(session.begin(0, 0)).isTrue()
		session.extendTo(50, 50)
		assertThat(session.isActive).isFalse()
	}

	@Test
	fun `keep-alive re-dispatches in place during idle and stops on release`() {
		val handler = android.os.Handler(android.os.Looper.getMainLooper())
		val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
		val fake = FakeDispatcher()
		val session = LiveDragSession(fake, NavScheduler(handler), keepAliveMs = 10L)

		session.begin(100, 200)
		val afterBegin = fake.steps.size

		// Idle the looper so several scheduled keep-alive wiggles fire.
		looper.idleFor(java.time.Duration.ofMillis(55))
		val added = fake.steps.drop(afterBegin)
		assertThat(added.size).isAtLeast(3)
		added.forEach { s ->
			assertThat(s.y).isEqualTo(200)
			assertThat(s.x).isIn(100..101) // never drifts
			assertThat(s.willContinue).isTrue()
		}
		added.zipWithNext().forEach { (a, b) -> assertThat(b.fromX).isEqualTo(a.x) } // continuity

		session.release()
		val afterRelease = fake.steps.size
		looper.idleFor(java.time.Duration.ofMillis(55))
		assertThat(fake.steps.size).isEqualTo(afterRelease) // no more wiggles once released
	}

	/** Holds each stroke's callback so the caller can complete them one at a time, on demand. */
	private class DeferredDispatcher : LiveStrokeDispatcher {
		val steps = mutableListOf<Step>()
		private val pending = ArrayDeque<Pair<Boolean, (GestureDescription.StrokeDescription?) -> Unit>>()

		private fun enqueue(willContinue: Boolean, onResult: (GestureDescription.StrokeDescription?) -> Unit) {
			pending.add(willContinue to onResult)
		}

		/** Complete the oldest in-flight stroke successfully. Returns false if none pending. */
		fun completeNext(): Boolean {
			val (willContinue, cb) = pending.removeFirstOrNull() ?: return false
			val path = Path().apply {
				moveTo(0f, 0f)
				lineTo(1f, 0f)
			}
			cb(GestureDescription.StrokeDescription(path, 0, 1, willContinue))
			return true
		}

		override fun beginDragStroke(x: Int, y: Int, onResult: (GestureDescription.StrokeDescription?) -> Unit): Boolean {
			steps.add(Step(x, y, x, y, true))
			enqueue(true, onResult)
			return true
		}

		override fun continueDragStroke(
			previous: GestureDescription.StrokeDescription,
			fromX: Int,
			fromY: Int,
			x: Int,
			y: Int,
			willContinue: Boolean,
			onResult: (GestureDescription.StrokeDescription?) -> Unit,
		): Boolean {
			steps.add(Step(fromX, fromY, x, y, willContinue))
			enqueue(willContinue, onResult)
			return true
		}
	}

	@Test
	fun `rapid extends coalesce so the finger heads straight to the latest target`() {
		val fake = DeferredDispatcher()
		val session = LiveDragSession(fake)
		session.begin(100, 200)
		fake.completeNext() // the press-down completes; 8 hold ticks enqueue, first one dispatches

		// Drain the hold ticks so we're past the pickup (each completion dispatches the next).
		while (fake.completeNext()) { /* drain until the queue empties and nothing new dispatches */ }
		val afterPickup = fake.steps.size

		// Three arrow presses arrive faster than strokes complete: only the FIRST dispatches now;
		// the 2nd and 3rd coalesce into one pending target while the 1st is in flight.
		session.extendTo(300, 200)
		session.extendTo(400, 200)
		session.extendTo(500, 200)
		fake.completeNext() // finishes the first extend; the coalesced latest dispatches next
		fake.completeNext()

		val moves = fake.steps.drop(afterPickup)
		// The finger must reach 500 (the latest) and must NOT have stepped through 400 as a separate move.
		assertThat(moves.map { it.x }).contains(500)
		assertThat(moves.count { it.x == 400 }).isEqualTo(0) // the stale middle target was coalesced away
	}
}
