package org.continuouspath.justtype.ime

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.continuouspath.justtype.R
import org.continuouspath.justtype.TouchDetectionOverlay
import org.continuouspath.justtype.input.InputSurface

/**
 * Manages overlay lifecycle: TouchDetectionOverlay (directional selection, touch screen switch,
 * directional two-switch modes), switch bar rendering (inline in-layout + floating system window),
 * flash feedback, and overlay settings.
 *
 * Extracted from JustTypeIME (Phase 2 Step 10).
 */
class OverlayCoordinator(
	private val context: Context,
	private val scope: CoroutineScope,
	private val callbacks: OverlayCoordinatorCallbacks,
) {
	// -- Touch detection overlay (system window) --
	private var touchDetectionOverlay: TouchDetectionOverlay? = null

	// -- Inline switch bar (in IME layout) --
	private var switchBarContainer: FrameLayout? = null
	private var switchBarView: View? = null
	private var switchBarDrawable: SwitchBarDrawable? = null

	// -- Floating switch bar overlay (system window) --
	private var overlayBarView: View? = null
	private var overlayBarDrawable: SwitchBarDrawable? = null
	private var isOverlayBarShowing: Boolean = false

	// The IME context's WindowManager is typed TYPE_INPUT_METHOD; adding the
	// TYPE_APPLICATION_OVERLAY bar through it is a window-type mismatch on API 30+.
	// Same pattern as TouchDetectionOverlay.windowContext (lazy + catch: Robolectric
	// throws from createWindowContext).
	private val overlayBarContext: Context by lazy {
		when {
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

	// -- Settings --
	private var config = OverlayConfig()

	// -- Flash reset --
	private var flashResetJob: Job? = null

	// -- Public API --

	fun initSwitchBar(container: FrameLayout?) {
		switchBarContainer = container
		val single = callbacks.isSingleSwitchEnabled
		switchBarDrawable = SwitchBarDrawable(
			single,
			SwitchBarDrawable.GREEN_COLOR,
			SwitchBarDrawable.RED_COLOR,
			SwitchBarDrawable.FLASH_GREEN_COLOR,
			SwitchBarDrawable.FLASH_RED_COLOR,
		)
		val barHeightPx = switchBarHeightPx(config.buttonHeightPercent)
		switchBarView = View(context).apply {
			background = switchBarDrawable
			contentDescription = context.getString(R.string.content_desc_switch_bar)
			layoutParams = FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				barHeightPx,
			)
		}
		switchBarContainer?.removeAllViews()
		switchBarContainer?.addView(switchBarView)
	}

	fun updateDirectionalSelection() {
		// CRITICAL: If keyboard is not visible, ALWAYS set overlay to INACTIVE and remove it
		if (!callbacks.isInputViewShown) {
			touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
			touchDetectionOverlay?.hide()
			switchBarContainer?.visibility = View.GONE
			hideSwitchButtonsOverlay()
			return
		}

		ensureOverlayInitialized()

		if (callbacks.isDirectionalSelectionEnabled) {
			// Check permission before showing overlay
			if (Settings.canDrawOverlays(context)) {
				// When both directional selection and two-switch are enabled,
				// use the combined mode where swipes are simplified to left/right
				// and mapped to two-switch green/red actions
				val mode = if (callbacks.isTwoSwitchEnabled) {
					TouchDetectionOverlay.Mode.DIRECTIONAL_TWO_SWITCH
				} else {
					TouchDetectionOverlay.Mode.DIRECTIONAL_SELECTION
				}
				touchDetectionOverlay?.setMode(mode)
			} else {
				// Permission not granted, request it
				requestOverlayPermission()
			}
		} else {
			// Directional selection is disabled - only set to INACTIVE if touch screen switch is also not active
			val touchSwitchActive = callbacks.isTouchScreenSwitchEnabled &&
				(callbacks.isSingleSwitchEnabled || callbacks.isTwoSwitchEnabled)
			if (!touchSwitchActive) {
				touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
			}
		}
	}

	fun updateTouchScreenSwitch() {
		// CRITICAL: If keyboard is not visible, ALWAYS set overlay to INACTIVE and remove it
		if (!callbacks.isInputViewShown) {
			touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
			touchDetectionOverlay?.hide()
			switchBarContainer?.visibility = View.GONE
			hideSwitchButtonsOverlay()
			return
		}

		ensureOverlayInitialized()

		val touchSwitchActive = callbacks.isTouchScreenSwitchEnabled &&
			(callbacks.isSingleSwitchEnabled || callbacks.isTwoSwitchEnabled)
		if (touchSwitchActive) {
			// Don't override directional two-switch mode -- swipes handle two-switch input
			if (callbacks.isDirectionalSelectionEnabled && callbacks.isTwoSwitchEnabled) {
				// Already in DIRECTIONAL_TWO_SWITCH mode, don't change
			} else if (Settings.canDrawOverlays(context)) {
				touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.TOUCH_SCREEN_SWITCH)
			} else {
				requestOverlayPermission()
			}
		} else {
			// Touch screen switch is not active - only set to INACTIVE if directional selection is also not active
			if (!callbacks.isDirectionalSelectionEnabled) {
				touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
			}
		}
		updateSwitchBarVisibility()
	}

	fun flashSwitchBar(flashGreen: Boolean, flashRed: Boolean) {
		if (!callbacks.isFlashEnabled) return
		val inlineVisible = switchBarContainer?.visibility == View.VISIBLE
		if (!inlineVisible && !isOverlayBarShowing) return
		flashResetJob?.cancel()
		flashResetJob = scope.launch {
			switchBarDrawable?.flashGreen = flashGreen
			switchBarDrawable?.flashRed = flashRed
			overlayBarDrawable?.flashGreen = flashGreen
			overlayBarDrawable?.flashRed = flashRed
			delay(100)
			switchBarDrawable?.flashGreen = false
			switchBarDrawable?.flashRed = false
			overlayBarDrawable?.flashGreen = false
			overlayBarDrawable?.flashRed = false
		}
	}

	fun updateConfig(newConfig: OverlayConfig) {
		config = newConfig
		applyConfig()
	}

	// -- Lifecycle --

	fun onKeyboardHidden() {
		touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
		touchDetectionOverlay?.hide()
		switchBarContainer?.visibility = View.GONE
		hideSwitchButtonsOverlay()
	}

	fun onKeyboardShown() {
		updateDirectionalSelection()
		updateTouchScreenSwitch()
	}

	fun onWindowFocusLost() {
		touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
		touchDetectionOverlay?.hide()
		hideSwitchButtonsOverlay()
	}

	fun onTrimMemory(level: Int) {
		if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
			callbacks.debugLog("[onTrimMemory] UI hidden (level=$level) - deactivating overlay for app switcher")
			touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
			touchDetectionOverlay?.hide()
			hideSwitchButtonsOverlay()
		}
	}

	fun deactivateOverlay() {
		touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
		touchDetectionOverlay?.hide()
	}

	fun destroy() {
		flashResetJob?.cancel()
		touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
		touchDetectionOverlay?.hide()
		touchDetectionOverlay = null
		hideSwitchButtonsOverlay()
	}

	// -- Internal --

	// Percent of the LONG display edge (the portrait height), so the bar keeps the
	// same physical size after rotation instead of halving with the short landscape
	// height; clamped so extreme percents can't exceed the actual screen.
	private fun switchBarHeightPx(percent: Int): Int {
		val m = context.resources.displayMetrics
		return (maxOf(m.widthPixels, m.heightPixels) * percent / 100f).toInt()
			.coerceIn(8, m.heightPixels)
	}

	private fun ensureOverlayInitialized() {
		if (touchDetectionOverlay != null) return
		if (!Settings.canDrawOverlays(context)) return

		touchDetectionOverlay = TouchDetectionOverlay(context)
		touchDetectionOverlay?.setDirectionalSelectionCallbacks(
			onDirectionDetected = { direction ->
				val surface = callbacks.inputSurface
				if (surface.isReady()) {
					val index = ExternalSwitchHandler.directionToIndex(direction)
					surface.onButtonPressed(index)
				}
			},
			onCloseKeyboard = {
				touchDetectionOverlay?.setMode(TouchDetectionOverlay.Mode.INACTIVE)
				touchDetectionOverlay?.hide()
				callbacks.requestHideSelf()
			},
		)
		touchDetectionOverlay?.setTouchScreenSwitchCallback { role: String, action: Int ->
			if (callbacks.isTwoSwitchEnabled) {
				val mappedRole = if (role == "LeftSwitch") "Green Switch" else "Red Switch"
				if (action == MotionEvent.ACTION_DOWN) {
					callbacks.twoSwitchTouchDown(mappedRole)
				} else if (action == MotionEvent.ACTION_UP) {
					callbacks.twoSwitchTouchUp()
				}
			} else {
				// Single switch scanning flow: treat as scan switch tap
				if (action == MotionEvent.ACTION_DOWN) {
					callbacks.scanSwitchDown(SWITCH_CODE_UNDEFINED)
				} else if (action == MotionEvent.ACTION_UP) {
					callbacks.scanSwitchUp()
				}
			}
		}
		applyConfig()
	}

	private fun applyConfig() {
		touchDetectionOverlay?.setTimeoutSeconds(config.timeoutSec)
		touchDetectionOverlay?.setMinSwipeDistancePercent(config.swipePercent)
		touchDetectionOverlay?.setTouchSwitchDebounce(config.touchSwitchDebounceMs)
		touchDetectionOverlay?.setDirectionalDebounce(config.directionalDebounceMs)
	}

	private fun updateSwitchBarVisibility() {
		val directionalTwoSwitch = callbacks.isDirectionalSelectionEnabled && callbacks.isTwoSwitchEnabled
		val show = callbacks.isInputViewShown &&
			(
				directionalTwoSwitch ||
					(callbacks.isTouchScreenSwitchEnabled && (callbacks.isSingleSwitchEnabled || callbacks.isTwoSwitchEnabled))
				)
		if (show) {
			val single = callbacks.isSingleSwitchEnabled
			switchBarDrawable = SwitchBarDrawable(
				single,
				SwitchBarDrawable.GREEN_COLOR,
				SwitchBarDrawable.RED_COLOR,
				SwitchBarDrawable.FLASH_GREEN_COLOR,
				SwitchBarDrawable.FLASH_RED_COLOR,
			)
			switchBarView?.background = switchBarDrawable
			updateSwitchButtonPlacement()
		} else {
			switchBarContainer?.visibility = View.GONE
			hideSwitchButtonsOverlay()
		}
	}

	private fun updateSwitchButtonPlacement() {
		val displayHeight = context.resources.displayMetrics.heightPixels
		val buttonHeightPx = switchBarHeightPx(config.buttonHeightPercent)
		val alpha = config.overlayOpacityPercent / 100f

		// Estimate IME height: root view height (without switch bar) + button height
		val rootHeight = switchBarContainer?.rootView?.height ?: 0
		val shouldOverlay = config.overlayModeEnabled || (rootHeight + buttonHeightPx > displayHeight * 2 / 3)

		// Always resize the touch detection overlay window to cover only the button region,
		// regardless of overlay mode. Overlay mode only controls where the visual bar renders.
		touchDetectionOverlay?.setTssRegionHeight(buttonHeightPx)

		if (shouldOverlay && Settings.canDrawOverlays(context)) {
			showSwitchButtonsAsOverlay(buttonHeightPx, alpha)
		} else {
			hideSwitchButtonsOverlay()
			showSwitchButtonsInline(buttonHeightPx, alpha)
		}
	}

	private fun showSwitchButtonsInline(heightPx: Int, alpha: Float = 1f) {
		val lp = switchBarView?.layoutParams
		if (lp != null) {
			lp.height = heightPx.coerceAtLeast(8)
			switchBarView?.layoutParams = lp
		}
		switchBarView?.alpha = alpha
		switchBarContainer?.visibility = View.VISIBLE
	}

	private fun showSwitchButtonsAsOverlay(heightPx: Int, alpha: Float = 0.5f) {
		// Hide inline bar
		switchBarContainer?.visibility = View.GONE

		val single = callbacks.isSingleSwitchEnabled

		if (isOverlayBarShowing) {
			// Update existing overlay drawable, size, and alpha
			overlayBarDrawable = SwitchBarDrawable(
				single,
				SwitchBarDrawable.GREEN_COLOR,
				SwitchBarDrawable.RED_COLOR,
				SwitchBarDrawable.FLASH_GREEN_COLOR,
				SwitchBarDrawable.FLASH_RED_COLOR,
			)
			overlayBarView?.background = overlayBarDrawable
			overlayBarView?.alpha = alpha
			try {
				val wm = overlayBarContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
				val params = overlayBarView?.layoutParams as? WindowManager.LayoutParams
				if (params != null) {
					params.height = heightPx
					wm.updateViewLayout(overlayBarView, params)
				}
			} catch (_: Exception) {}
			return
		}

		overlayBarDrawable = SwitchBarDrawable(
			single,
			SwitchBarDrawable.GREEN_COLOR,
			SwitchBarDrawable.RED_COLOR,
			SwitchBarDrawable.FLASH_GREEN_COLOR,
			SwitchBarDrawable.FLASH_RED_COLOR,
		)
		overlayBarView = View(overlayBarContext).apply {
			background = overlayBarDrawable
			contentDescription = context.getString(R.string.content_desc_switch_bar)
			this.alpha = alpha
		}

		val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
		val params = WindowManager.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			heightPx,
			overlayType,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.BOTTOM or Gravity.START
			x = 0
			y = 0
		}

		try {
			val wm = overlayBarContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
			wm.addView(overlayBarView, params)
			isOverlayBarShowing = true
		} catch (_: Exception) {}
	}

	private fun hideSwitchButtonsOverlay() {
		if (!isOverlayBarShowing) return
		try {
			val wm = overlayBarContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
			overlayBarView?.let { wm.removeView(it) }
		} catch (_: Exception) {}
		overlayBarView = null
		overlayBarDrawable = null
		isOverlayBarShowing = false
	}

	private fun requestOverlayPermission() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
			val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
				data = android.net.Uri.parse("package:${context.packageName}")
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}
			try {
				context.startActivity(intent)
			} catch (e: Exception) {
				callbacks.debugLog("[DirectionalSelection] Failed to open overlay permission settings: ${e.message}")
			}
		}
	}
}

/**
 * Drawable for the touch screen switch bar(s) along the bottom of the keyboard.
 * Single mode: one vivid green bar. Two mode: left half green, right half red.
 * Supports flashing to lighter red/green on activation.
 */
class SwitchBarDrawable(
	private val singleMode: Boolean,
	private val greenColor: Int,
	private val redColor: Int,
	private val flashGreenColor: Int,
	private val flashRedColor: Int,
) : android.graphics.drawable.Drawable() {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val rect = RectF()

	@Volatile var flashGreen: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				invalidateSelf()
			}
		}

	@Volatile var flashRed: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				invalidateSelf()
			}
		}

	override fun draw(canvas: Canvas) {
		val b = bounds
		if (b.width() <= 0 || b.height() <= 0) return
		val h = b.height().toFloat()
		if (singleMode) {
			rect.set(0f, 0f, b.width().toFloat(), h)
			paint.style = Paint.Style.FILL
			paint.color = if (flashGreen) flashGreenColor else greenColor
			canvas.drawRect(rect, paint)
		} else {
			val half = b.width() / 2f
			rect.set(0f, 0f, half, h)
			paint.style = Paint.Style.FILL
			paint.color = if (flashGreen) flashGreenColor else greenColor
			canvas.drawRect(rect, paint)
			rect.set(half, 0f, b.width().toFloat(), h)
			paint.color = if (flashRed) flashRedColor else redColor
			canvas.drawRect(rect, paint)
		}
	}

	override fun setAlpha(alpha: Int) {
		paint.alpha = alpha
	}
	override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
		paint.colorFilter = colorFilter
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.OPAQUE

	companion object {
		val FLASH_GREEN_COLOR = Color.argb(255, 210, 255, 210)
		val FLASH_RED_COLOR = Color.argb(255, 255, 210, 210)
		val GREEN_COLOR = Color.argb(255, 0, 200, 0)
		val RED_COLOR = Color.argb(255, 220, 0, 0)
	}
}

/** Configuration values pushed from preferences. */
data class OverlayConfig(
	val timeoutSec: Int = 4,
	val swipePercent: Int = 5,
	val touchSwitchDebounceMs: Int = 120,
	val directionalDebounceMs: Int = 120,
	val overlayModeEnabled: Boolean = false,
	val buttonHeightPercent: Int = 10,
	val overlayOpacityPercent: Int = 50,
)

/**
 * Reverse-communication interface from [OverlayCoordinator] back to JustTypeIME.
 */
interface OverlayCoordinatorCallbacks {
	// -- State queries --
	val isInputViewShown: Boolean
	val isJtuiInitialized: Boolean
	val isDirectionalSelectionEnabled: Boolean
	val isTwoSwitchEnabled: Boolean
	val isSingleSwitchEnabled: Boolean
	val isTouchScreenSwitchEnabled: Boolean
	val isFlashEnabled: Boolean

	// -- Overlay touch routing --
	val inputSurface: InputSurface
	fun requestHideSelf()
	fun scanSwitchDown(keyCode: Int)
	fun scanSwitchUp()
	fun twoSwitchTouchDown(role: String)
	fun twoSwitchTouchUp()

	// -- Debug --
	fun debugLog(message: String)
}
