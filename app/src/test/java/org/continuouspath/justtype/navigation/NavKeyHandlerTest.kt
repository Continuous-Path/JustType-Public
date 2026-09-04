package org.continuouspath.justtype.navigation

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NavKeyHandlerTest {

	private val scope = CoroutineScope(Dispatchers.Unconfined)

	@After
	fun tearDown() {
		scope.cancel()
		shadowOf(Looper.getMainLooper()).idle()
	}

	private fun dispatcher(result: Boolean, seen: MutableList<NavAction> = mutableListOf()) = object : NavActionDispatcher {
		override fun dispatch(action: NavAction): Boolean {
			seen.add(action)
			return result
		}
	}

	@Test
	fun `onKeyPressed forwards the mapped action and returns its dispatch result`() {
		val seen = mutableListOf<NavAction>()
		val handler = NavKeyHandler(dispatcher(result = true, seen = seen), mapOf(1 to NavAction.Up))
		assertThat(handler.onKeyPressed(1)).isTrue()
		assertThat(seen).containsExactly(NavAction.Up)
	}

	@Test
	fun `onKeyPressed returns false for an unmapped index without dispatching`() {
		val seen = mutableListOf<NavAction>()
		val handler = NavKeyHandler(dispatcher(result = true, seen = seen), mapOf(1 to NavAction.Up))
		assertThat(handler.onKeyPressed(5)).isFalse()
		assertThat(seen).isEmpty()
	}

	@Test
	fun `onKeyPressed propagates a no-op action result`() {
		val handler = NavKeyHandler(dispatcher(result = false), mapOf(3 to NavAction.Empty))
		assertThat(handler.onKeyPressed(3)).isFalse()
	}

	private fun surface(onNoOp: (Int) -> Unit, dispatchResult: Boolean) = NavInputSurface(
		context = RuntimeEnvironment.getApplication(),
		scope = scope,
		dispatcher = dispatcher(result = dispatchResult),
		currentPageProvider = { OverlayPage.Nav },
		readyProvider = { true },
		onNoOp = onNoOp,
	)

	@Test
	fun `NavInputSurface fires onNoOp when the press is a no-op`() {
		val noOps = mutableListOf<Int>()
		val acted = surface(onNoOp = { noOps.add(it) }, dispatchResult = false).onButtonPressed(1)
		assertThat(acted).isFalse()
		assertThat(noOps).containsExactly(1)
	}

	@Test
	fun `NavInputSurface does not fire onNoOp on a real activation`() {
		val noOps = mutableListOf<Int>()
		val acted = surface(onNoOp = { noOps.add(it) }, dispatchResult = true).onButtonPressed(1)
		assertThat(acted).isTrue()
		assertThat(noOps).isEmpty()
	}

	private fun surface(seen: MutableList<NavAction>) = NavInputSurface(
		context = RuntimeEnvironment.getApplication(),
		scope = scope,
		dispatcher = dispatcher(result = true, seen = seen),
		currentPageProvider = { OverlayPage.Nav },
		readyProvider = { true },
		onNoOp = {},
	)

	@Test
	fun `repeatLast replays the last repeatable action`() {
		val seen = mutableListOf<NavAction>()
		val surface = surface(seen)
		surface.onButtonPressed(1) // Nav index 1 = Up (repeatable)
		assertThat(surface.repeatLast()).isTrue()
		assertThat(seen).containsExactly(NavAction.Up, NavAction.Up)
	}

	@Test
	fun `repeatLast is a silent no-op after a non-repeatable action`() {
		val seen = mutableListOf<NavAction>()
		val surface = surface(seen)
		surface.onButtonPressed(5) // Nav index 5 = DoubleTap (not repeatable)
		assertThat(surface.repeatLast()).isFalse()
		assertThat(seen).containsExactly(NavAction.DoubleTap) // no replay
	}

	@Test
	fun `repeatLast returns false before any press`() {
		assertThat(surface(mutableListOf()).repeatLast()).isFalse()
	}

	@Test
	fun `drag pick-up page arrows drive the precise-pixel cursor and are hold-repeatable`() {
		// The SELECT-TARGET page moves a free pixel cursor (reusing DragMove* actions), not element nav.
		val drag = NavKeyHandler.mappingFor(OverlayPage.DragMode)
		assertThat(drag[1]).isEqualTo(NavAction.DragMoveUp)
		assertThat(drag[3]).isEqualTo(NavAction.DragMoveLeft)
		assertThat(drag[4]).isEqualTo(NavAction.DragMoveRight)
		assertThat(drag[6]).isEqualTo(NavAction.DragMoveDown)
		assertThat(drag[2]).isEqualTo(NavAction.PickUp) // SELECT
		assertThat(drag[5]).isEqualTo(NavAction.PathLonger) // step keys now active here
		assertThat(drag[7]).isEqualTo(NavAction.PathShorter)
		// Auto-repeat (hold-to-move) must work for the cursor arrows, or a switch user gets one nudge.
		listOf(drag[1], drag[3], drag[4], drag[6]).forEach {
			assertThat(it!!.isRepeatable).isTrue()
		}
	}
}
