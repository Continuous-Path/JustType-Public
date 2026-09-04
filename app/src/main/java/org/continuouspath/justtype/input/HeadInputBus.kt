package org.continuouspath.justtype.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.continuouspath.justtype.Constants.ACTION_EXTERNAL_JOYSTICK_INPUT
import org.continuouspath.justtype.Constants.ACTION_HEAD_TRACKING_RESUME
import org.continuouspath.justtype.Constants.PERMISSION_RECEIVE_HEADBOARD_EVENT
import org.continuouspath.justtype.receiver.ExternalInputReceiver
import java.util.concurrent.CopyOnWriteArraySet

/**
 * App-scoped source for inbound head-tracking events (pose frames, resume
 * signal, HeadBoard service-state, external button input).
 *
 * Extracted from [org.continuouspath.justtype.ime.HeadTrackingSubsystem] so more than
 * one surface (the IME and the navigation overlay) can consume the same stream
 * without each registering its own receivers/thread. Listeners receive the raw
 * streams and marshal to their own dispatcher — the bus does no interpretation
 * and no coroutine launching, so the threading contract matches the original
 * in-subsystem receivers exactly:
 *
 * - the frame callbacks ([HeadInputListener.onCoordinates], [onFrameTimestamp],
 *   [onFrameSeq]) fire on the dedicated pose thread, in that order, within one
 *   broadcast — mirroring [ExternalInputReceiver].
 * - [onResumeSignal] / [onServiceState] fire on the main looper (`null` handler).
 *
 * Receivers + thread are ref-counted: alive while ≥1 listener is registered,
 * torn down at zero.
 */
object HeadInputBus {

	/** Raw inbound streams. Implementors marshal to their own dispatcher. */
	interface HeadInputListener {
		/** A head-pose frame (-1..1). Pose thread. */
		fun onCoordinates(x: Float, y: Float)

		/** Frame send-timestamp (ms) for the most recent [onCoordinates]. Pose thread. */
		fun onFrameTimestamp(timestampMs: Long) {}

		/** Monotonic frame sequence for the most recent [onCoordinates]. Pose thread. */
		fun onFrameSeq(seq: Long) {}

		/** HeadBoard's cursor re-entered the keyboard region. Main looper. */
		fun onResumeSignal() {}

		/** HeadBoard reported its enable state (ordinal). Main looper. */
		fun onServiceState(stateOrdinal: Int) {}

		/** A direct button-index input (0..7) from an external app. Pose thread. */
		fun onExternalButton(buttonIndex: Int) {}
	}

	private val listeners = CopyOnWriteArraySet<HeadInputListener>()

	private var poseThread: HandlerThread? = null
	private var externalInputReceiver: BroadcastReceiver? = null
	private var resumeSignalReceiver: BroadcastReceiver? = null
	private var serviceStateReceiver: BroadcastReceiver? = null
	private var appContext: Context? = null

	private val _globallyPaused = MutableStateFlow(false)
	val globallyPausedFlow: StateFlow<Boolean> = _globallyPaused.asStateFlow()

	fun setPaused(paused: Boolean) {
		_globallyPaused.value = paused
	}

	/** Register a listener; starts the receivers/thread on the first one. */
	@Synchronized
	fun addListener(context: Context, listener: HeadInputListener) {
		ensureStarted(context)
		listeners.add(listener)
	}

	/** Remove a listener; tears down receivers/thread when the last one leaves. */
	@Synchronized
	fun removeListener(listener: HeadInputListener) {
		listeners.remove(listener)
		if (listeners.isEmpty()) stop()
	}

	@Synchronized
	private fun ensureStarted(context: Context) {
		if (poseThread != null) return
		val ctx = context.applicationContext
		appContext = ctx

		// Dedicated thread for pose-frame delivery so onReceive (and the work the
		// listener launches from it) is not blocked by main-thread activity.
		val ht = HandlerThread("HtPoseReceiver").also {
			it.isDaemon = true
			it.start()
		}
		poseThread = ht
		val poseHandler = Handler(ht.looper)

		externalInputReceiver = ExternalInputReceiver(
			onButtonIndexReceived = { idx -> listeners.forEach { it.onExternalButton(idx) } },
			onCoordinatesReceived = { x, y -> listeners.forEach { it.onCoordinates(x, y) } },
			onFrameTimestamp = { ts -> listeners.forEach { it.onFrameTimestamp(ts) } },
			onFrameSeq = { seq -> listeners.forEach { it.onFrameSeq(seq) } },
		)
		ctx.registerReceiver(
			externalInputReceiver,
			IntentFilter(ACTION_EXTERNAL_JOYSTICK_INPUT),
			PERMISSION_RECEIVE_HEADBOARD_EVENT,
			poseHandler,
			Context.RECEIVER_EXPORTED,
		)

		serviceStateReceiver = object : BroadcastReceiver() {
			override fun onReceive(c: Context?, intent: Intent?) {
				val ordinal = intent?.getIntExtra("state", -1) ?: -1
				listeners.forEach { it.onServiceState(ordinal) }
			}
		}
		ctx.registerReceiver(
			serviceStateReceiver,
			IntentFilter("SERVICE_STATE"),
			PERMISSION_RECEIVE_HEADBOARD_EVENT,
			null,
			Context.RECEIVER_EXPORTED,
		)

		resumeSignalReceiver = object : BroadcastReceiver() {
			override fun onReceive(c: Context?, intent: Intent?) {
				listeners.forEach { it.onResumeSignal() }
			}
		}
		ctx.registerReceiver(
			resumeSignalReceiver,
			IntentFilter(ACTION_HEAD_TRACKING_RESUME),
			PERMISSION_RECEIVE_HEADBOARD_EVENT,
			null,
			Context.RECEIVER_EXPORTED,
		)
	}

	@Synchronized
	private fun stop() {
		val ctx = appContext
		externalInputReceiver?.let { runCatching { ctx?.unregisterReceiver(it) } }
		externalInputReceiver = null
		// Quit the thread after unregistering so no further onReceive can post to it.
		poseThread?.quitSafely()
		poseThread = null
		serviceStateReceiver?.let { runCatching { ctx?.unregisterReceiver(it) } }
		serviceStateReceiver = null
		resumeSignalReceiver?.let { runCatching { ctx?.unregisterReceiver(it) } }
		resumeSignalReceiver = null
		appContext = null
	}
}
