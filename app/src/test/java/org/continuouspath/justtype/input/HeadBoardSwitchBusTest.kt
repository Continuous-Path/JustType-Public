package org.continuouspath.justtype.input

import android.content.Intent
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.ACTION_EXTERNAL_SWITCH
import org.continuouspath.justtype.Constants.EXTRA_SWITCH_INDEX
import org.continuouspath.justtype.Constants.EXTRA_SWITCH_IS_DOWN
import org.continuouspath.justtype.Constants.HEADBOARD_SWITCH_1_KEYCODE
import org.continuouspath.justtype.Constants.HEADBOARD_SWITCH_2_KEYCODE
import org.continuouspath.justtype.Constants.PERMISSION_RECEIVE_HEADBOARD_EVENT
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HeadBoardSwitchBusTest {

	private val ctx get() = RuntimeEnvironment.getApplication()
	private val registered = mutableListOf<HeadBoardSwitchBus.Consumer>()

	@Before
	fun grantSenderPermission() {
		shadowOf(ctx).grantPermissions(PERMISSION_RECEIVE_HEADBOARD_EVENT)
	}

	private fun addConsumer(priority: HeadBoardSwitchBus.Priority, consume: Boolean, into: MutableList<KeyEvent>): HeadBoardSwitchBus.Consumer {
		val consumer = HeadBoardSwitchBus.Consumer { event ->
			into.add(event)
			consume
		}
		HeadBoardSwitchBus.addConsumer(ctx, priority, consumer)
		registered.add(consumer)
		return consumer
	}

	private fun broadcast(index: Int, isDown: Boolean) {
		ctx.sendBroadcast(
			Intent(ACTION_EXTERNAL_SWITCH)
				.putExtra(EXTRA_SWITCH_INDEX, index)
				.putExtra(EXTRA_SWITCH_IS_DOWN, isDown),
		)
		shadowOf(Looper.getMainLooper()).idle()
	}

	@After
	fun tearDown() {
		registered.forEach { HeadBoardSwitchBus.removeConsumer(it) }
	}

	@Test
	fun `broadcast becomes a keyboard-source KeyEvent with the reserved code`() {
		val events = mutableListOf<KeyEvent>()
		addConsumer(HeadBoardSwitchBus.Priority.IME, consume = true, into = events)

		broadcast(index = 1, isDown = true)
		broadcast(index = 2, isDown = true)

		assertThat(events.map { it.keyCode })
			.containsExactly(HEADBOARD_SWITCH_1_KEYCODE, HEADBOARD_SWITCH_2_KEYCODE)
			.inOrder()
		assertThat(events[0].action).isEqualTo(KeyEvent.ACTION_DOWN)
		assertThat(events[0].source and InputDevice.SOURCE_KEYBOARD).isEqualTo(InputDevice.SOURCE_KEYBOARD)
	}

	@Test
	fun `up pairs with its down via downTime`() {
		val events = mutableListOf<KeyEvent>()
		addConsumer(HeadBoardSwitchBus.Priority.IME, consume = true, into = events)

		broadcast(index = 1, isDown = true)
		broadcast(index = 1, isDown = false)

		assertThat(events.map { it.action })
			.containsExactly(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)
			.inOrder()
		assertThat(events[1].downTime).isEqualTo(events[0].downTime)
	}

	@Test
	fun `consuming capture consumer starves lower priorities, non-consuming passes through`() {
		val captureSeen = mutableListOf<KeyEvent>()
		val imeSeen = mutableListOf<KeyEvent>()
		val capture = addConsumer(HeadBoardSwitchBus.Priority.CAPTURE, consume = true, into = captureSeen)
		addConsumer(HeadBoardSwitchBus.Priority.IME, consume = true, into = imeSeen)

		broadcast(index = 1, isDown = true)
		assertThat(captureSeen).hasSize(1)
		assertThat(imeSeen).isEmpty()

		HeadBoardSwitchBus.removeConsumer(capture)
		registered.remove(capture)
		broadcast(index = 1, isDown = false)
		assertThat(imeSeen).hasSize(1)
	}

	@Test
	fun `receiver tears down at zero consumers and re-arms on the next add`() {
		val first = mutableListOf<KeyEvent>()
		val consumer = addConsumer(HeadBoardSwitchBus.Priority.IME, consume = true, into = first)
		HeadBoardSwitchBus.removeConsumer(consumer)
		registered.remove(consumer)

		broadcast(index = 1, isDown = true)
		assertThat(first).isEmpty()

		val second = mutableListOf<KeyEvent>()
		addConsumer(HeadBoardSwitchBus.Priority.IME, consume = true, into = second)
		broadcast(index = 1, isDown = true)
		assertThat(second).hasSize(1)
	}

	@Test
	fun `malformed and out-of-range broadcasts are ignored`() {
		val events = mutableListOf<KeyEvent>()
		addConsumer(HeadBoardSwitchBus.Priority.IME, consume = true, into = events)

		ctx.sendBroadcast(Intent(ACTION_EXTERNAL_SWITCH)) // no index extra
		broadcast(index = 3, isDown = true)
		broadcast(index = 0, isDown = true)
		shadowOf(Looper.getMainLooper()).idle()

		assertThat(events).isEmpty()
	}
}
