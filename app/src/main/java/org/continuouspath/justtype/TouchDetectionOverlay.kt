package org.continuouspath.justtype

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Combined overlay that handles both directional selection and touch screen switch input methods.
 * Only one mode is active at a time. When INACTIVE, the overlay does not consume touch events.
 */
class TouchDetectionOverlay(
	private val context: Context,
) {
	/**
	 * Mode of operation for the overlay.
	 */
	enum class Mode {
		/**
		 * Overlay is inactive - does not consume touch events and should be removed from WindowManager.
		 */
		INACTIVE,

		/**
		 * Directional selection mode - detects directional gestures/swipes.
		 */
		DIRECTIONAL_SELECTION,

		/**
		 * Touch screen switch mode - detects touch input for switch activation.
		 */
		TOUCH_SCREEN_SWITCH,

		/**
		 * Directional two-switch mode - detects left/right swipes and maps them
		 * to two-switch green/red actions. Used when both directional selection
		 * and two-switch selection are enabled together.
		 */
		DIRECTIONAL_TWO_SWITCH,
	}

	// On API 30+, window-manager calls must come from a Context whose declared
	// window-type matches the LayoutParams type. The IME's own context is typed
	// TYPE_INPUT_METHOD, so adding a TYPE_APPLICATION_OVERLAY view from it trips
	// StrictMode's IncorrectContextUseViolation. Use a window-typed context instead.
	// Lazy so we don't trip Robolectric (which throws UnsupportedOperationException
	// from createWindowContext) at construction time in unit tests.
	private val windowContext: Context by lazy {
		when {
			// Display-specific overload is API 31+.
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> try {
				val display = context.display ?: context.applicationContext.display
				if (display != null) {
					context.applicationContext.createWindowContext(
						display,
						WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
						null,
					)
				} else {
					context
				}
			} catch (_: Exception) {
				context
			}
			// API 30 only has the default-display overload.
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> try {
				context.applicationContext.createWindowContext(
					WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
					null,
				)
			} catch (_: Exception) {
				context
			}
			else -> context
		}
	}

	private var windowManager: WindowManager? = null
	private var overlayView: View? = null
	private var isShowing = false
	private var currentMode = Mode.INACTIVE

	// Directional selection callbacks
	private var onDirectionDetected: ((GamepadDirectionDetector.Direction) -> Unit)? = null
	private var onCloseKeyboard: (() -> Unit)? = null

	// Touch screen switch callback
	private var onSwitch: ((role: String, action: Int) -> Unit)? = null

	// Directional selection parameters. Percent of the SHORT display edge (the
	// portrait width), so a swipe stays the same physical length after rotation.
	private var minSwipeDistancePx = shortEdgePx() * 5 / 100f // Default 5%
	private val angleToleranceDeg = 22.5f // Allow 22.5 degrees tolerance for each direction
	private var touchDownX: Float = 0f
	private var touchDownY: Float = 0f
	private var touchDownTime: Long = 0L
	private var timeoutDurationMs = 4000L // Default 4 seconds (configurable)

	// Touch screen switch debouncing
	private var touchSwitchDebounceMs = 0 // 0 = disabled
	private var lastTouchSwitchActivationTime = 0L

	// Height (px) the overlay window is resized to in TSS mode. 0 = full-screen (directional modes).
	private var tssButtonHeightPx: Int = 0

	// Directional selection debouncing
	private var directionalDebounceMs = 0 // 0 = disabled
	private var lastDirectionalActivationTime = 0L

	/**
	 * Set the current mode of operation.
	 * When set to INACTIVE, the overlay is completely removed from WindowManager.
	 * When set to an active mode, the overlay is shown if not already showing.
	 */
	fun setMode(mode: Mode) {
		if (currentMode == mode) {
			// If already INACTIVE, ensure overlay is actually removed
			if (mode == Mode.INACTIVE && isShowing) {
				hide()
			}
			return
		}

		currentMode = mode

		when (mode) {
			Mode.INACTIVE -> {
				hide() // Completely remove overlay from WindowManager
			}
			Mode.DIRECTIONAL_SELECTION, Mode.DIRECTIONAL_TWO_SWITCH -> {
				// Directional modes need full-screen coverage — restore if previously shrunk for TSS
				if (!isShowing) {
					show()
				} else {
					setTssRegionHeight(0) // restore full-screen
				}
			}
			Mode.TOUCH_SCREEN_SWITCH -> {
				if (!isShowing) {
					show()
				}
				// TSS region height is set separately via setTssRegionHeight() after mode is set
			}
		}
	}

	/**
	 * Get the current mode.
	 */
	fun getMode(): Mode = currentMode

	/**
	 * Check if the overlay is currently active (not INACTIVE).
	 */
	fun isActive(): Boolean = currentMode != Mode.INACTIVE

	/**
	 * Set callbacks for directional selection mode.
	 */
	fun setDirectionalSelectionCallbacks(
		onDirectionDetected: (GamepadDirectionDetector.Direction) -> Unit,
		onCloseKeyboard: () -> Unit,
	) {
		this.onDirectionDetected = onDirectionDetected
		this.onCloseKeyboard = onCloseKeyboard
	}

	/**
	 * Set callback for touch screen switch mode.
	 */
	fun setTouchScreenSwitchCallback(onSwitch: (role: String, action: Int) -> Unit) {
		this.onSwitch = onSwitch
	}

	/**
	 * Set the timeout duration in seconds for touch events.
	 * When a touch lasts longer than this duration, the keyboard will be hidden.
	 *
	 * @param timeoutSec Timeout in seconds (should be between 2 and 10)
	 */
	fun setTimeoutSeconds(timeoutSec: Int) {
		timeoutDurationMs = (timeoutSec * 1000L).coerceIn(2000L, 10000L)
	}

	/**
	 * Set the minimum swipe distance for directional selection as a percentage of the
	 * short display edge (orientation-invariant — see [shortEdgePx]).
	 *
	 * @param percent Percentage of the short edge (should be between 2 and 20)
	 */
	fun setMinSwipeDistancePercent(percent: Int) {
		minSwipeDistancePx = shortEdgePx() * percent.coerceIn(2, 20) / 100f
	}

	private fun shortEdgePx(): Int {
		val m = context.resources.displayMetrics
		return minOf(m.widthPixels, m.heightPixels)
	}

	/**
	 * Set the debounce time for touch screen switch.
	 *
	 * @param debounceMs Debounce time in milliseconds (0 = disabled, max 500ms)
	 */
	fun setTouchSwitchDebounce(debounceMs: Int) {
		touchSwitchDebounceMs = debounceMs.coerceIn(0, 500)
	}

	/**
	 * Set the debounce time for directional selection.
	 *
	 * @param debounceMs Debounce time in milliseconds (0 = disabled, max 500ms)
	 */
	fun setDirectionalDebounce(debounceMs: Int) {
		directionalDebounceMs = debounceMs.coerceIn(0, 500)
	}

	/**
	 * Resize the overlay window to cover only the bottom [heightPx] pixels of the screen.
	 * Used in TSS mode so that touches above the button region physically miss the overlay window
	 * and are delivered to the app behind it, rather than being intercepted and discarded.
	 * Pass 0 (or a negative value) to restore full-screen coverage (used for directional modes).
	 */
	fun setTssRegionHeight(heightPx: Int) {
		tssButtonHeightPx = heightPx
		if (!isShowing) return
		val wm = windowManager ?: return
		val view = overlayView ?: return
		val params = view.layoutParams as? WindowManager.LayoutParams ?: return
		if (heightPx > 0) {
			params.height = heightPx
			params.gravity = Gravity.BOTTOM or Gravity.START
		} else {
			params.height = WindowManager.LayoutParams.MATCH_PARENT
			params.gravity = Gravity.TOP or Gravity.START
		}
		try {
			wm.updateViewLayout(view, params)
		} catch (_: Exception) {
		}
	}

	/**
	 * Show the overlay and add it to WindowManager.
	 * Only works if mode is not INACTIVE.
	 */
	private fun show() {
		if (isShowing || currentMode == Mode.INACTIVE) return

		windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
		if (windowManager == null) return

		overlayView = createOverlayView()
		val params = createLayoutParams()

		try {
			windowManager!!.addView(overlayView, params)
			isShowing = true
		} catch (_: Exception) {
			overlayView = null
		}
	}

	/**
	 * Hide the overlay and completely remove it from WindowManager.
	 * Sets mode to INACTIVE as a precaution.
	 */
	fun hide() {
		if (!isShowing) {
			currentMode = Mode.INACTIVE
			return
		}

		currentMode = Mode.INACTIVE

		overlayView?.let { view ->
			try {
				windowManager?.removeView(view)
			} catch (_: Exception) {
				// Ignore errors when removing view
			}
		}
		overlayView = null
		windowManager = null
		isShowing = false
	}

	private fun createOverlayView(): View {
		return object : View(context) {
			override fun onTouchEvent(event: MotionEvent): Boolean {
				// CRITICAL SAFETY CHECK: When INACTIVE, NEVER consume touch events
				// Double-check mode in case it changed between when event was dispatched
				if (currentMode == Mode.INACTIVE || !isShowing) {
					return false // Don't consume events when inactive or not showing
				}

				// Additional safety: re-check mode before handling
				val handled =
					when (currentMode) {
						Mode.DIRECTIONAL_SELECTION -> handleDirectionalSelectionTouch(event)
						Mode.TOUCH_SCREEN_SWITCH -> handleTouchScreenSwitchTouch(event)
						Mode.DIRECTIONAL_TWO_SWITCH -> handleDirectionalTwoSwitchTouch(event)
						Mode.INACTIVE -> false // Should not reach here, but safety check
					}

				if (handled && event.action == MotionEvent.ACTION_UP) {
					// Notify accessibility services of click-equivalent interaction
					performClick()
				}
				return handled
			}
		}.apply {
			setBackgroundColor(0x00000000) // Fully transparent
		}
	}

	private fun handleDirectionalSelectionTouch(event: MotionEvent): Boolean {
		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				touchDownX = event.x
				touchDownY = event.y
				touchDownTime = System.currentTimeMillis()
			}
			MotionEvent.ACTION_UP -> {
				val touchUpX = event.x
				val touchUpY = event.y
				val touchUpTime = System.currentTimeMillis()
				val duration = touchUpTime - touchDownTime

				// Check for timeout
				if (duration >= timeoutDurationMs) {
					onCloseKeyboard?.invoke()
					return true
				}

				// Check debounce - if within debounce window, ignore this swipe
				if (directionalDebounceMs > 0) {
					val timeSinceLastActivation = touchUpTime - lastDirectionalActivationTime
					if (timeSinceLastActivation < directionalDebounceMs) {
						return true // Consume but ignore (debounced)
					}
				}

				// Detect swipe direction
				val dx = touchUpX - touchDownX
				val dy = touchUpY - touchDownY
				val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

				if (distance >= minSwipeDistancePx) {
					val direction = detectDirection(dx, dy)
					direction?.let {
						// Update last activation time
						lastDirectionalActivationTime = touchUpTime
						onDirectionDetected?.invoke(it)
					}
				}
			}
			MotionEvent.ACTION_CANCEL -> {
				// Handle finger sliding off screen edge - reset touch state
				// Simply ignore the gesture (don't detect direction) since touch was cancelled
				touchDownTime = 0L
			}
			MotionEvent.ACTION_MOVE -> {
				// Check for timeout during move
				val currentTime = System.currentTimeMillis()
				val duration = currentTime - touchDownTime
				if (duration >= timeoutDurationMs) {
					onCloseKeyboard?.invoke()
					return true
				}
			}
		}
		return true // Consume all touch events in directional selection mode
	}

	private fun handleTouchScreenSwitchTouch(event: MotionEvent): Boolean {
		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				touchDownX = event.x
				touchDownY = event.y
				touchDownTime = System.currentTimeMillis()

				// Check debounce - if within debounce window, ignore this event
				if (touchSwitchDebounceMs > 0) {
					val timeSinceLastActivation = touchDownTime - lastTouchSwitchActivationTime
					if (timeSinceLastActivation < touchSwitchDebounceMs) {
						return true // Consume but ignore (debounced)
					}
				}

				val screenWidth = context.resources.displayMetrics.widthPixels
				val role = if (event.rawX <= screenWidth / 2f) "LeftSwitch" else "RightSwitch"
				onSwitch?.invoke(role, MotionEvent.ACTION_DOWN)
			}
			MotionEvent.ACTION_UP -> {
				val touchUpTime = System.currentTimeMillis()
				val duration = touchUpTime - touchDownTime

				// Check for timeout
				if (duration >= timeoutDurationMs) {
					onCloseKeyboard?.invoke()
					return true
				}

				// Check debounce - if within debounce window, ignore this event
				if (touchSwitchDebounceMs > 0) {
					val timeSinceLastActivation = touchUpTime - lastTouchSwitchActivationTime
					if (timeSinceLastActivation < touchSwitchDebounceMs) {
						return true // Consume but ignore (debounced)
					}
				}

				// Update last activation time
				lastTouchSwitchActivationTime = touchUpTime

				val screenWidth = context.resources.displayMetrics.widthPixels
				val role = if (event.rawX <= screenWidth / 2f) "LeftSwitch" else "RightSwitch"
				onSwitch?.invoke(role, MotionEvent.ACTION_UP)
			}
			MotionEvent.ACTION_CANCEL -> {
				// Handle finger sliding off screen edge - treat as ACTION_UP to reset state
				// Use the original touch down position's side for consistency
				val screenWidth = context.resources.displayMetrics.widthPixels
				val role = if (touchDownX <= screenWidth / 2f) "LeftSwitch" else "RightSwitch"
				onSwitch?.invoke(role, MotionEvent.ACTION_UP)
			}
			MotionEvent.ACTION_MOVE -> {
				// Check for timeout during move
				val currentTime = System.currentTimeMillis()
				val duration = currentTime - touchDownTime
				if (duration >= timeoutDurationMs) {
					onCloseKeyboard?.invoke()
					return true
				}
			}
		}
		return true // Consume all touch events in touch screen switch mode
	}

	/**
	 * Handles swipe input for the combined directional + two-switch mode.
	 * Detects swipes like directional selection, but only interprets them as left or right,
	 * then triggers the corresponding two-switch action (LeftSwitch → Green, RightSwitch → Red).
	 */
	private fun handleDirectionalTwoSwitchTouch(event: MotionEvent): Boolean {
		when (event.action) {
			MotionEvent.ACTION_DOWN -> {
				touchDownX = event.x
				touchDownY = event.y
				touchDownTime = System.currentTimeMillis()
			}
			MotionEvent.ACTION_UP -> {
				val touchUpX = event.x
				val touchUpY = event.y
				val touchUpTime = System.currentTimeMillis()
				val duration = touchUpTime - touchDownTime

				if (duration >= timeoutDurationMs) {
					onCloseKeyboard?.invoke()
					return true
				}

				// Debounce using directional debounce setting
				if (directionalDebounceMs > 0) {
					val timeSinceLastActivation = touchUpTime - lastDirectionalActivationTime
					if (timeSinceLastActivation < directionalDebounceMs) {
						return true
					}
				}

				val dx = touchUpX - touchDownX
				val dy = touchUpY - touchDownY
				val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

				if (distance >= minSwipeDistancePx) {
					lastDirectionalActivationTime = touchUpTime
					// Determine left or right based on horizontal component of swipe
					val role = if (dx <= 0f) "LeftSwitch" else "RightSwitch"
					onSwitch?.invoke(role, MotionEvent.ACTION_DOWN)
					onSwitch?.invoke(role, MotionEvent.ACTION_UP)
				}
			}
			MotionEvent.ACTION_CANCEL -> {
				touchDownTime = 0L
			}
			MotionEvent.ACTION_MOVE -> {
				val currentTime = System.currentTimeMillis()
				val duration = currentTime - touchDownTime
				if (duration >= timeoutDurationMs) {
					onCloseKeyboard?.invoke()
					return true
				}
			}
		}
		return true
	}

	private fun detectDirection(
		dx: Float,
		dy: Float,
	): GamepadDirectionDetector.Direction? {
		// Calculate angle in degrees (0° = right, 90° = down, 180° = left, 270° = up)
		// Note: Android Y+ is down, so we need to invert Y
		val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

		// Normalize angle to [0, 360)
		val normalizedAngle = wrapDeg(angleDeg)

		// Map angle to one of 8 directions with tolerance
		return when {
			isInRange(normalizedAngle, 0f, angleToleranceDeg) ||
				isInRange(normalizedAngle, 360f - angleToleranceDeg, 360f) ->
				GamepadDirectionDetector.Direction.RIGHT
			isInRange(normalizedAngle, 45f - angleToleranceDeg, 45f + angleToleranceDeg) ->
				GamepadDirectionDetector.Direction.DOWN_RIGHT
			isInRange(normalizedAngle, 90f - angleToleranceDeg, 90f + angleToleranceDeg) ->
				GamepadDirectionDetector.Direction.DOWN
			isInRange(normalizedAngle, 135f - angleToleranceDeg, 135f + angleToleranceDeg) ->
				GamepadDirectionDetector.Direction.DOWN_LEFT
			isInRange(normalizedAngle, 180f - angleToleranceDeg, 180f + angleToleranceDeg) ->
				GamepadDirectionDetector.Direction.LEFT
			isInRange(normalizedAngle, 225f - angleToleranceDeg, 225f + angleToleranceDeg) ->
				GamepadDirectionDetector.Direction.UP_LEFT
			isInRange(normalizedAngle, 270f - angleToleranceDeg, 270f + angleToleranceDeg) ->
				GamepadDirectionDetector.Direction.UP
			isInRange(normalizedAngle, 315f - angleToleranceDeg, 315f + angleToleranceDeg) ->
				GamepadDirectionDetector.Direction.UP_RIGHT
			else -> {
				// Fallback: snap to nearest direction center
				val centers =
					listOf(
						0f to GamepadDirectionDetector.Direction.RIGHT,
						45f to GamepadDirectionDetector.Direction.DOWN_RIGHT,
						90f to GamepadDirectionDetector.Direction.DOWN,
						135f to GamepadDirectionDetector.Direction.DOWN_LEFT,
						180f to GamepadDirectionDetector.Direction.LEFT,
						225f to GamepadDirectionDetector.Direction.UP_LEFT,
						270f to GamepadDirectionDetector.Direction.UP,
						315f to GamepadDirectionDetector.Direction.UP_RIGHT,
					)
				centers
					.minByOrNull { (center, _) ->
						val diff = abs(wrapDeg(normalizedAngle - center))
						minOf(diff, 360f - diff)
					}?.second
			}
		}
	}

	private fun wrapDeg(d: Float): Float {
		var x = d % 360f
		if (x < 0) x += 360f
		return x
	}

	private fun isInRange(
		angle: Float,
		start: Float,
		end: Float,
	): Boolean = if (start <= end) {
		angle in start..end
	} else {
		angle >= start || angle <= end
	}

	private fun createLayoutParams(): WindowManager.LayoutParams {
		val type =
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

		return WindowManager
			.LayoutParams(
				WindowManager.LayoutParams.MATCH_PARENT,
				WindowManager.LayoutParams.MATCH_PARENT,
				type,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
					WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
					WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
					WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
				PixelFormat.TRANSLUCENT,
			).apply {
				gravity = Gravity.TOP or Gravity.START
				x = 0
				y = 0
			}
	}
}
