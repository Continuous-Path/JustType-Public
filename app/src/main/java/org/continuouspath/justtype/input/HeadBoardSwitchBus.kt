package org.continuouspath.justtype.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import org.continuouspath.justtype.Constants.ACTION_EXTERNAL_SWITCH
import org.continuouspath.justtype.Constants.EXTRA_SWITCH_INDEX
import org.continuouspath.justtype.Constants.EXTRA_SWITCH_IS_DOWN
import org.continuouspath.justtype.Constants.HEADBOARD_SWITCH_1_KEYCODE
import org.continuouspath.justtype.Constants.HEADBOARD_SWITCH_2_KEYCODE
import org.continuouspath.justtype.Constants.PERMISSION_RECEIVE_HEADBOARD_EVENT
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-scoped source for HeadBoard-driven switch presses.
 *
 * HeadBoard lets the user bind one of its triggers (a face gesture or a captured key) to
 * "JustType Switch 1/2"; it broadcasts [ACTION_EXTERNAL_SWITCH] with press/release edges.
 * This bus synthesizes a [KeyEvent] carrying the matching reserved keycode
 * ([HEADBOARD_SWITCH_1_KEYCODE]/[HEADBOARD_SWITCH_2_KEYCODE], source keyboard) and offers it
 * to consumers in [Priority] order, stopping at the first that consumes — mirroring how the
 * system routes a physical key (setup capture, then Nav overlay, then IME). Because the
 * events are ordinary KeyEvents fed into the ordinary entry points, they are capturable in
 * the switch setup screens and respect [InputCaptureGate] like any hardware switch.
 *
 * Receiver is ref-counted: registered while >= 1 consumer is present, torn down at zero.
 * Delivery is on the main looper, matching every consumer's key-handling thread.
 */
object HeadBoardSwitchBus {

	/** Delivery order; lower ordinal is offered the event first. */
	enum class Priority { CAPTURE, NAV, IME }

	/** Return true to consume the event and stop delivery. */
	fun interface Consumer {
		fun onHeadBoardSwitchEvent(event: KeyEvent): Boolean
	}

	private data class Entry(val priority: Priority, val consumer: Consumer)

	private val entries = CopyOnWriteArrayList<Entry>()
	private var receiver: BroadcastReceiver? = null
	private var appContext: Context? = null

	/** Down-times per switch index so the synthesized UP pairs with its DOWN. */
	private val downTimeMs = longArrayOf(0L, 0L)

	@Synchronized
	fun addConsumer(context: Context, priority: Priority, consumer: Consumer) {
		ensureStarted(context)
		entries.add(Entry(priority, consumer))
	}

	@Synchronized
	fun removeConsumer(consumer: Consumer) {
		entries.removeAll { it.consumer === consumer }
		if (entries.isEmpty()) stop()
	}

	@Synchronized
	private fun ensureStarted(context: Context) {
		if (receiver != null) return
		val ctx = context.applicationContext
		appContext = ctx
		val r = object : BroadcastReceiver() {
			override fun onReceive(c: Context?, intent: Intent?) {
				val index = intent?.getIntExtra(EXTRA_SWITCH_INDEX, -1) ?: return
				if (index != 1 && index != 2) return
				val isDown = intent.getBooleanExtra(EXTRA_SWITCH_IS_DOWN, true)
				deliver(index, isDown)
			}
		}
		receiver = r
		ctx.registerReceiver(
			r,
			IntentFilter(ACTION_EXTERNAL_SWITCH),
			PERMISSION_RECEIVE_HEADBOARD_EVENT,
			null, // main looper — every consumer handles keys on the main thread
			Context.RECEIVER_EXPORTED,
		)
	}

	@Synchronized
	private fun stop() {
		receiver?.let { r -> appContext?.let { runCatching { it.unregisterReceiver(r) } } }
		receiver = null
		appContext = null
	}

	private fun deliver(index: Int, isDown: Boolean) {
		val keyCode = if (index == 1) HEADBOARD_SWITCH_1_KEYCODE else HEADBOARD_SWITCH_2_KEYCODE
		val now = SystemClock.uptimeMillis()
		if (isDown) downTimeMs[index - 1] = now
		val event = KeyEvent(
			/* downTime = */ if (isDown) now else downTimeMs[index - 1],
			/* eventTime = */ now,
			/* action = */ if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
			/* code = */ keyCode,
			/* repeat = */ 0,
		)
		// Keyboard source so the switch pipeline's external-keyboard checks accept it.
		event.source = InputDevice.SOURCE_KEYBOARD
		for (entry in entries.sortedBy { it.priority.ordinal }) {
			if (entry.consumer.onHeadBoardSwitchEvent(event)) return
		}
	}
}
