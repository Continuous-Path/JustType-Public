package org.continuouspath.justtype.activity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen capture surface for the mouse-joystick calibration wizard. Reads absolute rawX/rawY
 * from mouse hover events (the same source the IME's runtime differences), shows the live per-event
 * delta and the eventTime dt on screen, and reports the peak sustained magnitude — the raw signal
 * the wizard derives sensitivity/deadzone from. This first cut is a probe: it proves the Setup
 * Activity actually receives mouse hover, and doubles as the wizard's measurement view.
 */
class MjCalibrationProbeView(context: Context) : View(context) {
	private val bgPaint = Paint().apply { color = Color.parseColor("#101418") }
	private val textPaint = Paint().apply {
		color = Color.WHITE
		isAntiAlias = true
		textSize = 42f
	}
	private val bigPaint = Paint().apply {
		color = Color.parseColor("#04DE71")
		isAntiAlias = true
		textSize = 64f
		isFakeBoldText = true
	}
	private val dotPaint = Paint().apply {
		color = Color.parseColor("#04DE71")
		isAntiAlias = true
	}

	private var lastX = Float.NaN
	private var lastY = Float.NaN
	private var lastEventTime = 0L
	private var eventCount = 0
	private var lastDx = 0f
	private var lastDy = 0f
	private var lastDtMs = 0L
	private var peakSpeedPxPerSec = 0f
	private var deviceLabel = "(no mouse event yet)"
	private var cursorX = 0f
	private var cursorY = 0f
	private var sawMouse = false

	// Pointer-capture spike: tap toggles capture on this (focusable, Activity-hosted) view — the
	// guaranteed-context proof that capture + relative deltas work on this hardware at all.
	private var captureState = "tap screen to test pointer capture"
	private var capturedEvents = 0
	private var lastCapDx = 0f
	private var lastCapDy = 0f

	init {
		isFocusable = true
		isFocusableInTouchMode = true
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		if (event.actionMasked == MotionEvent.ACTION_UP) {
			if (hasPointerCapture()) {
				releasePointerCapture()
			} else {
				requestFocus()
				requestPointerCapture()
				android.util.Log.d("MJ_CAP", "PROBE capture requested hasCapture=${hasPointerCapture()}")
			}
			performClick()
		}
		return true
	}

	override fun performClick(): Boolean {
		super.performClick()
		return true
	}

	override fun onPointerCaptureChange(hasCapture: Boolean) {
		super.onPointerCaptureChange(hasCapture)
		captureState = if (hasCapture) "CAPTURE ACTIVE ✓ (tap to release)" else "capture released — tap to re-test"
		android.util.Log.d("MJ_CAP", "PROBE ${if (hasCapture) "CAPTURE_GRANTED" else "CAPTURE_LOST"}")
		invalidate()
	}

	override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
		if (event.action == MotionEvent.ACTION_MOVE) {
			capturedEvents++
			lastCapDx = event.x
			lastCapDy = event.y
			android.util.Log.d(
				"MJ_CAP",
				"PROBE REL d=(${"%.1f".format(event.x)},${"%.1f".format(event.y)}) src=${event.source}",
			)
			invalidate()
		}
		return true
	}

	override fun onHoverEvent(event: MotionEvent): Boolean {
		consume(event)
		return true
	}

	// Some devices route mouse motion through generic-motion rather than hover; capture both.
	override fun onGenericMotionEvent(event: MotionEvent): Boolean {
		if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
			consume(event)
			return true
		}
		return super.onGenericMotionEvent(event)
	}

	private fun consume(event: MotionEvent) {
		val isMouse = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
		if (isMouse) sawMouse = true
		eventCount++
		cursorX = event.rawX
		cursorY = event.rawY
		deviceLabel = "src=${event.source} mouse=$isMouse dev=${event.device?.name ?: "?"}"
		if (!lastX.isNaN()) {
			lastDx = event.rawX - lastX
			lastDy = event.rawY - lastY
			lastDtMs = if (lastEventTime == 0L) 0L else event.eventTime - lastEventTime
			if (lastDtMs > 0) {
				val speed = kotlin.math.hypot(lastDx, lastDy) / (lastDtMs / 1000f)
				if (speed > peakSpeedPxPerSec) peakSpeedPxPerSec = speed
				// Log every sample so a device's motion character (delta size, event gap, speed) can be
				// read off-device to compare joysticks and validate the time-normalization (B1) plan.
				android.util.Log.d(
					"MJ_CAL",
					"dev=${event.device?.name} d=(${"%.1f".format(lastDx)},${"%.1f".format(lastDy)}) " +
						"dtMs=$lastDtMs speed=${speed.toInt()} peak=${peakSpeedPxPerSec.toInt()}",
				)
			}
		}
		lastX = event.rawX
		lastY = event.rawY
		lastEventTime = event.eventTime
		invalidate()
	}

	fun resetPeak() {
		peakSpeedPxPerSec = 0f
		invalidate()
	}

	override fun onDraw(canvas: Canvas) {
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
		var y = 100f
		val line = { s: String, p: Paint ->
			canvas.drawText(s, 40f, y, p)
			y += p.textSize + 18f
		}
		line("MJ calibration probe — move the joystick", textPaint)
		line(if (sawMouse) "MOUSE HOVER RECEIVED ✓" else "waiting for a mouse hover event…", bigPaint)
		line(deviceLabel, textPaint)
		line("events=$eventCount  raw=(${cursorX.toInt()}, ${cursorY.toInt()})", textPaint)
		line("delta=(${"%.1f".format(lastDx)}, ${"%.1f".format(lastDy)})  dt=${lastDtMs}ms", textPaint)
		line("peak speed = ${peakSpeedPxPerSec.toInt()} px/sec", bigPaint)
		line(captureState, bigPaint)
		line("captured events=$capturedEvents  rel=(${"%.1f".format(lastCapDx)}, ${"%.1f".format(lastCapDy)})", textPaint)
		if (cursorX > 0 || cursorY > 0) {
			canvas.drawCircle(cursorX, cursorY, 24f, dotPaint)
		}
	}
}
