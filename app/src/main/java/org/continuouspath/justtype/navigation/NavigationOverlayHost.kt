package org.continuouspath.justtype.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.R
import org.continuouspath.justtype.ime.HeadTrackingViewBridge
import org.continuouspath.justtype.ime.HighlightBridge
import org.continuouspath.justtype.ime.JoystickViewBridge
import org.continuouspath.justtype.ime.TwoSwitchViewBridge
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getFloat
import org.continuouspath.justtype.view.SquareButton

/**
 * Floating-window host for the navigation 8-key grid.
 *
 * Touches inside the grid reach the buttons; touches outside pass through to
 * the underlying app (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL, no NOT_TOUCHABLE).
 */
class NavigationOverlayHost(
	private val context: Context,
	private val dispatcher: NavActionDispatcher,
	private val vibrator: android.os.Vibrator? = null,
	private val onMinimizeRequested: () -> Unit = {},
) : HighlightBridge,
	TwoSwitchViewBridge,
	HeadTrackingViewBridge,
	JoystickViewBridge {
	// TYPE_ACCESSIBILITY_OVERLAY windows must be added by the accessibility service's own
	// (UI) context — a window context made from applicationContext can't add that type.
	private val windowContext: Context get() = context

	private var windowManager: WindowManager? = null
	private var rootView: View? = null
	private var isShowing = false

	// While a live-held drag runs, the drag handle ignores touches so an injected drag stroke landing
	// on it can't move the whole window (the keys stay live for arrow presses). No way to tell an
	// injected touch from a real one, so the handle opts out entirely for the drag's duration.
	private var handleTouchLocked = false

	// Audio + haptic feedback for direct taps on the overlay (subsystems own their own).
	private var feedback: NavSubsystemFeedback? = null
	private var layoutParams: WindowManager.LayoutParams? = null
	private val buttonOriginalBackgrounds = mutableMapOf<Int, Drawable?>()

	// Pristine per-button backgrounds captured at inflation, so appearance is re-applied losslessly.
	private val pristineBackgrounds = mutableMapOf<Int, Drawable?>()

	// Standing (non-flash) key state: the logical tint/drawable a key settles back to when a
	// flash ends. Stored abstractly (not as a painted Drawable) so it survives theme/appearance
	// rebuilds and dark-mode remapping happens at paint time.
	private sealed interface Standing {
		data class Tint(val color: Int) : Standing
		data class Res(val resId: Int) : Standing
	}

	private val standingState = mutableMapOf<Int, Standing>()
	private val flashRestores = mutableMapOf<Int, Runnable>()

	// Direct-tap hold-to-repeat runnables, keyed by button index (see render()).
	private val keyRepeatRunnables = mutableMapOf<Int, Runnable>()

	fun show() {
		if (isShowing) return
		val wm = windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
		val view = LayoutInflater.from(windowContext)
			.inflate(R.layout.overlay_navigation_mode, null, false)
		val params = createLayoutParams()
		try {
			wm.addView(view, params)
			windowManager = wm
			rootView = view
			layoutParams = params
			isShowing = true
			feedback = NavSubsystemFeedback(vibrator)
			snapshotOriginals(view)
			applyAppearance()
			attachDragHandle(view, params, wm)
			render(OverlayPage.Nav, emptyMap())
		} catch (_: Exception) {
			windowManager = null
			rootView = null
			layoutParams = null
		}
	}

	/** Drag the whole overlay window by the handle, clamped on-screen. */
	private fun attachDragHandle(view: View, params: WindowManager.LayoutParams, wm: WindowManager) {
		val handle = view.findViewById<View>(R.id.dragHandle) ?: return
		var startX = 0
		var startY = 0
		var touchRawX = 0f
		var touchRawY = 0f
		var minimizeFired = false
		val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
		// Long-press the handle to minimize; cancelled by a drag (movement past slop) or an early lift.
		val longPressMinimize = Runnable {
			minimizeFired = true
			onMinimizeRequested()
		}
		handle.setOnTouchListener { v, event ->
			if (handleTouchLocked) return@setOnTouchListener false // a live drag owns the pointer; don't move the window
			when (event.actionMasked) {
				android.view.MotionEvent.ACTION_DOWN -> {
					startX = params.x
					startY = params.y
					touchRawX = event.rawX
					touchRawY = event.rawY
					minimizeFired = false
					v.postDelayed(longPressMinimize, android.view.ViewConfiguration.getLongPressTimeout().toLong())
					true
				}
				android.view.MotionEvent.ACTION_MOVE -> {
					if (minimizeFired) return@setOnTouchListener true
					// Past slop = a drag, not a long-press: cancel the pending minimize.
					if (kotlin.math.hypot(event.rawX - touchRawX, event.rawY - touchRawY) > touchSlop) {
						v.removeCallbacks(longPressMinimize)
					}
					val metrics = context.resources.displayMetrics
					val maxX = (metrics.widthPixels - v.rootView.width).coerceAtLeast(0)
					val maxY = (metrics.heightPixels - v.rootView.height).coerceAtLeast(0)
					params.x = (startX + (event.rawX - touchRawX).toInt()).coerceIn(0, maxX)
					params.y = (startY + (event.rawY - touchRawY).toInt()).coerceIn(0, maxY)
					runCatching { wm.updateViewLayout(view, params) }
					true
				}
				android.view.MotionEvent.ACTION_UP -> {
					v.removeCallbacks(longPressMinimize) // lifted before the long-press fired
					v.performClick() // satisfy accessibility click contract; no-op action
					true
				}
				android.view.MotionEvent.ACTION_CANCEL -> {
					v.removeCallbacks(longPressMinimize)
					true
				}
				else -> false
			}
		}
	}

	/**
	 * Apply user appearance prefs (opacity, size, transparency mode, handle) live.
	 * Re-runnable: each call rebuilds the working button backgrounds from the
	 * pristine snapshot, so switching transparency modes back and forth is lossless.
	 */
	fun applyAppearance() {
		val root = rootView ?: return
		val prefs = SettingsRepository.getInstance(context)
		val keyOpacity = prefs.getInt(Constants.KEY_NAV_KEY_OPACITY_PERCENT, Constants.NAV_KEY_OPACITY_DEFAULT)
			.coerceIn(0, 100)
		val panelOpacity = prefs.getInt(Constants.KEY_NAV_PANEL_OPACITY_PERCENT, Constants.NAV_PANEL_OPACITY_DEFAULT)
			.coerceIn(0, 100)
		val sizePercent = prefs.getInt(Constants.KEY_NAV_SIZE_PERCENT, Constants.NAV_SIZE_PERCENT_DEFAULT)
			.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)
		val mode = prefs.getString(Constants.KEY_NAV_TRANSPARENCY_MODE, Constants.NAV_TRANSPARENCY_GLYPH_OUTLINE)
		val hideHandle = prefs.getBoolean(Constants.KEY_NAV_HIDE_DRAG_HANDLE, false)

		// Panel (the rounded background behind the grid).
		(root as? LinearLayout)?.background?.mutate()?.alpha = pctToAlpha(panelOpacity)

		// Size: scale the grid's square footprint, and match the handle to its width.
		val side = dpToPx(BASE_GRID_DP) * sizePercent / 100
		root.findViewById<View>(R.id.keyGrid)?.let { grid ->
			grid.layoutParams = grid.layoutParams.apply {
				width = side
				height = side
			}
		}

		// Drag handle: visibility + width tracks the scaled grid.
		root.findViewById<View>(R.id.dragHandle)?.let { handle ->
			handle.visibility = if (hideHandle) View.GONE else View.VISIBLE
			handle.layoutParams = handle.layoutParams.apply { width = side }
		}

		// Keys: rebuild each from its pristine background, then apply the mode.
		val keyAlpha = pctToAlpha(keyOpacity)
		val dark = navIsDark()
		for (index in BUTTON_IDS.indices) {
			val btn = buttonAt(index) ?: continue
			btn.alpha = enabledKeyAlpha()
			when (mode) {
				Constants.NAV_TRANSPARENCY_UNIFORM -> {
					// Glyph + fill + border fade together; keep the full background for restore.
					buttonOriginalBackgrounds[index] = freshPristine(index)
					btn.background = freshPristine(index)
				}
				Constants.NAV_TRANSPARENCY_GLYPH_ONLY -> {
					// Only the glyph remains; restore target is null (transparent).
					buttonOriginalBackgrounds[index] = null
					btn.background = null
				}
				else -> {
					// GLYPH_OUTLINE (default): glyph opaque, fill alpha follows the slider.
					val bg = freshPristine(index)?.also { it.alpha = keyAlpha }
					buttonOriginalBackgrounds[index] = bg
					btn.background = bg
				}
			}
			// Dark theme: tint the light key face dark so the white glyph contrasts.
			if (dark) {
				btn.background?.setTint(NAV_KEY_FACE_DARK)
				buttonOriginalBackgrounds[index]?.setTint(NAV_KEY_FACE_DARK)
			}
			// Re-apply any standing subsystem tint (two-switch split, scan highlight) over the
			// rebuilt base, resolved against the new theme — a theme flip mid-cycle keeps it.
			if (standingState.containsKey(index)) paintStanding(index)
		}
	}

	/** A fresh mutable copy of the pristine background for [index], or null. */
	private fun freshPristine(index: Int): Drawable? = pristineBackgrounds[index]?.constantState?.newDrawable()?.mutate()

	private fun pctToAlpha(pct: Int): Int = (pct * 255 / 100).coerceIn(0, 255)

	/**
	 * View alpha for an enabled key. UNIFORM mode fades the whole key (glyph + fill);
	 * other modes keep the glyph opaque. [render] and [applyAppearance] both use this
	 * so a page re-render can't clobber the appearance-driven opacity.
	 */
	private fun enabledKeyAlpha(): Float {
		val prefs = SettingsRepository.getInstance(context)
		val mode = prefs.getString(Constants.KEY_NAV_TRANSPARENCY_MODE, Constants.NAV_TRANSPARENCY_GLYPH_OUTLINE)
		return if (mode == Constants.NAV_TRANSPARENCY_UNIFORM) {
			prefs.getInt(Constants.KEY_NAV_KEY_OPACITY_PERCENT, Constants.NAV_KEY_OPACITY_DEFAULT)
				.coerceIn(0, 100) / 100f
		} else {
			1f
		}
	}

	/** Resolve the Nav keyboard theme to dark/light (System follows the device night mode). */
	private fun navIsDark(): Boolean {
		val theme = SettingsRepository.getInstance(context)
			.getString(Constants.KEY_NAV_THEME, Constants.NAV_THEME_SYSTEM)
		return when (theme) {
			Constants.NAV_THEME_DARK -> true
			Constants.NAV_THEME_LIGHT -> false
			else -> {
				val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
				night == Configuration.UI_MODE_NIGHT_YES
			}
		}
	}

	/** Glyph/icon colour for the current theme: white on a dark keyboard, black on light. */
	private fun navGlyphColor(): Int = if (navIsDark()) Color.WHITE else Color.BLACK

	private fun snapshotOriginals(root: View) {
		buttonOriginalBackgrounds.clear()
		pristineBackgrounds.clear()
		standingState.clear()
		flashRestores.clear()
		for ((index, id) in BUTTON_IDS.withIndex()) {
			val btn = root.findViewById<SquareButton>(id) ?: continue
			buttonOriginalBackgrounds[index] = btn.background
			pristineBackgrounds[index] = btn.background
		}
	}

	fun hide() {
		val view = rootView
		val wm = windowManager
		if (view != null && wm != null) {
			try {
				wm.removeView(view)
			} catch (_: Exception) {
				// already removed or window leaked — best-effort cleanup
			}
		}
		rootView = null
		windowManager = null
		isShowing = false
		standingState.clear()
		flashRestores.clear()
		feedback?.release()
		feedback = null
	}

	/** Current window top-left (x, y), or null when not shown. */
	fun windowPosition(): Pair<Int, Int>? = layoutParams?.let { it.x to it.y }

	/**
	 * Make the grid window ignore touches (or resume handling them). Used to let the service's own
	 * injected drag gesture pass THROUGH the grid to the app when the pickup or drop path sits under
	 * it — otherwise the grid (its drag handle) grabs the injected touch and moves the window instead.
	 * There is no way to tell an injected touch from a real one at the view layer, so the window opts
	 * out entirely for the gesture's duration. No-op if unchanged.
	 */
	fun setTouchable(touchable: Boolean) {
		val wm = windowManager ?: return
		val view = rootView ?: return
		val params = layoutParams ?: return
		val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
		val next = if (touchable) params.flags and notTouchable.inv() else params.flags or notTouchable
		if (next == params.flags) return
		params.flags = next
		runCatching { wm.updateViewLayout(view, params) }
	}

	/**
	 * Lock/unlock the window-drag handle. Locked while a live-held drag runs so an injected stroke on
	 * the handle can't move the window; the keys stay touchable so arrow presses still land.
	 */
	fun setHandleTouchLocked(locked: Boolean) {
		handleTouchLocked = locked
	}

	/** The grid window's on-screen bounds, or null when hidden — injected gestures must avoid it. */
	fun windowBoundsOnScreen(): android.graphics.Rect? = rootView?.let { v ->
		val loc = IntArray(2).also(v::getLocationOnScreen)
		android.graphics.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
	}

	/**
	 * Snap the window to a screen region (TOP|START origin). Lets users reposition the
	 * keyboard without dragging — essential for those who can't touch-drag the window.
	 */
	fun snapToRegion(region: NavRegion) {
		val wm = windowManager ?: return
		val view = rootView ?: return
		val params = layoutParams ?: return
		val metrics = context.resources.displayMetrics
		val margin = dpToPx(MARGIN_DP)
		val w = if (view.width > 0) view.width else dpToPx(CONTENT_WIDTH_DP)
		val h = if (view.height > 0) view.height else dpToPx(CONTENT_HEIGHT_DP)

		val left = margin
		val centerX = ((metrics.widthPixels - w) / 2).coerceAtLeast(0)
		val right = (metrics.widthPixels - w - margin).coerceAtLeast(0)
		val top = margin
		val centerY = ((metrics.heightPixels - h) / 2).coerceAtLeast(0)
		val bottom = (metrics.heightPixels - h - margin).coerceAtLeast(0)

		val (x, y) = when (region) {
			NavRegion.TOP_LEFT -> left to top
			NavRegion.TOP_CENTER -> centerX to top
			NavRegion.TOP_RIGHT -> right to top
			NavRegion.CENTER_LEFT -> left to centerY
			NavRegion.CENTER_RIGHT -> right to centerY
			NavRegion.BOTTOM_LEFT -> left to bottom
			NavRegion.BOTTOM_CENTER -> centerX to bottom
			NavRegion.BOTTOM_RIGHT -> right to bottom
		}
		params.x = x
		params.y = y
		runCatching { wm.updateViewLayout(view, params) }
	}

	/** Re-render the 8 keys for [page]. Greys out actions where [availability] is false. */
	fun render(page: OverlayPage, availability: Map<NavAction, Boolean>) {
		val root = rootView ?: return
		val mapping = NavKeyHandler.mappingFor(page)
		val handler = NavKeyHandler(dispatcher, mapping)
		val glyphColor = navGlyphColor()
		val dark = navIsDark()
		val prefs = SettingsRepository.getInstance(context)
		val flashEnabled = prefs.getBoolean(Constants.KEY_FLASH_KEY_FEEDBACK, true)
		val flashColor = ContextCompat.getColor(context, R.color.key_flash)
		renderCenterLabel(root, page, glyphColor)
		for ((index, id) in BUTTON_IDS.withIndex()) {
			val btn = root.findViewById<SquareButton>(id) ?: continue
			val action = mapping[index]
			val enabled = action != null &&
				action !is NavAction.Empty &&
				availability[action] != false
			btn.setTextColor(glyphColor)
			val iconRes = when {
				dark && action?.iconResDark != 0 -> action?.iconResDark ?: 0
				else -> action?.iconRes ?: 0
			}
			val captionRes = keyCaptionRes(action)
			if (iconRes != 0) {
				btn.text = ""
				// The final icon set is multi-color with its own dark variants — never tint.
				val icon = ContextCompat.getDrawable(btn.context, iconRes)
				if (captionRes != 0) {
					btn.setCaptionedIcon(icon, context.getString(captionRes), tintWithTextColor = false)
				} else {
					btn.setCenteredIcon(icon, tintWithTextColor = false)
				}
			} else {
				btn.setCenteredIcon(null)
				btn.typeface = Typeface.DEFAULT_BOLD
				val glyph = if (action == NavAction.SpeedAdjustTbd) context.getString(R.string.nav_scroll_speed_tbd) else action?.glyph.orEmpty()
				btn.text = glyph
				// Word labels (icon placeholders) need a smaller size to fit the square key.
				btn.textSize = if (glyph.length > 2) KEY_WORD_SP else KEY_GLYPH_SP
			}
			btn.alpha = if (enabled) enabledKeyAlpha() else DISABLED_ALPHA
			btn.isEnabled = enabled
			wireKey(btn, index, if (enabled) action else null, handler, flashEnabled, flashColor, prefs)
		}
	}

	/** Word caption drawn under a key's icon (per the layout spec). Arrow keys get no caption. */
	private fun keyCaptionRes(action: NavAction?): Int = when (action) {
		NavAction.OpenMenu -> R.string.nav_key_more
		NavAction.Tap -> R.string.nav_key_tap
		NavAction.DoubleTap -> R.string.nav_key_double_tap
		NavAction.LongPress -> R.string.nav_key_long_tap
		NavAction.BackToNav, NavAction.PrevMenuPage -> R.string.nav_key_back
		NavAction.EnterReposition -> R.string.nav_key_move
		NavAction.OpenScroll -> R.string.nav_key_scroll
		NavAction.OpenDrag -> R.string.nav_key_drag
		NavAction.Back -> R.string.nav_key_back
		NavAction.Home -> R.string.nav_key_home
		NavAction.Recents -> R.string.nav_key_recents
		NavAction.PathLonger -> R.string.nav_scroll_longer
		NavAction.PathShorter -> R.string.nav_scroll_shorter
		NavAction.PickUp -> R.string.nav_drag_select
		else -> 0
	}

	/** Mode label in the grid's empty center cell (per the icon-proposal layer mockups). */
	private fun renderCenterLabel(root: View, page: OverlayPage, glyphColor: Int) {
		val label = root.findViewById<TextView>(R.id.centerLabel) ?: return
		val textRes = when (page) {
			OverlayPage.Nav -> R.string.nav_center_layer_1
			OverlayPage.MenuPage1 -> R.string.nav_center_layer_2
			OverlayPage.ScrollMode -> R.string.nav_center_scroll
			OverlayPage.DragMode -> R.string.nav_center_drag_select
			OverlayPage.DragMoveMode -> R.string.nav_center_drag_move
			OverlayPage.RepositionMode -> R.string.nav_center_move_keyboard
			OverlayPage.MenuPage2 -> 0
		}
		label.text = if (textRes == 0) "" else context.getString(textRes)
		label.gravity = Gravity.CENTER
		label.textSize = CENTER_LABEL_SP
		label.typeface = Typeface.DEFAULT_BOLD
		label.setTextColor(ColorUtils.setAlphaComponent(glyphColor, CENTER_LABEL_ALPHA))
	}

	/** (Re)wire a key's tap + hold-to-repeat listeners; [action] is null for a disabled key. */
	@SuppressLint("ClickableViewAccessibility") // touch listener never consumes; clicks stay native
	private fun wireKey(
		btn: SquareButton,
		index: Int,
		action: NavAction?,
		handler: NavKeyHandler,
		flashEnabled: Boolean,
		flashColor: Int,
		prefs: SettingsRepository,
	) {
		// A re-render swaps the listeners out from under an in-flight hold — cancel its repeat.
		keyRepeatRunnables.remove(index)?.let(btn::removeCallbacks)
		btn.setOnTouchListener(null)
		if (action == null) {
			btn.setOnClickListener(null)
			return
		}
		var repeatFired = false
		btn.setOnClickListener {
			// A hold that already auto-repeated consumes its release.
			if (repeatFired) {
				repeatFired = false
			} else if (handler.onKeyPressed(index)) {
				// Direct-tap feedback parity with switch/scan input.
				if (flashEnabled) flashButton(index, flashColor, NAV_FLASH_DURATION_MS, null)
				feedback?.activationFeedback()
			} else {
				// Failed/no-op action gets the same error cue as non-direct methods.
				flashButton(index, ERROR_FLASH_COLOR, NAV_FLASH_DURATION_MS, null)
				feedback?.errorFeedback()
			}
		}
		if (!action.isRepeatable) return
		// Direct-selection auto-repeat: hold a repeatable key to re-fire it. Replays the
		// captured action (not the index) so a repeat never re-resolves on a changed page.
		var repeatDelayMs = 0L
		val repeat = object : Runnable {
			override fun run() {
				if (!btn.isAttachedToWindow) return
				// A re-render (e.g. availability change mid-hold) swapped listeners and untracked
				// this runnable — stop, or it would repeat forever with no release to cancel it.
				if (keyRepeatRunnables[index] !== this) return
				repeatFired = true
				if (dispatcher.dispatch(action)) {
					if (flashEnabled) flashButton(index, flashColor, NAV_FLASH_DURATION_MS, null)
					feedback?.activationFeedback()
				}
				// dispatch() itself can re-render (scroll re-grey) — re-check before re-arming.
				if (keyRepeatRunnables[index] === this) btn.postDelayed(this, repeatDelayMs)
			}
		}
		btn.setOnTouchListener { _, event ->
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					repeatFired = false
					if (prefs.getBoolean(Constants.KEY_DIRECT_AUTOREPEAT_MODE)) {
						repeatDelayMs =
							(prefs.getFloat(Constants.KEY_DIRECT_AUTOREPEAT_DELAY_SEC).coerceIn(0.25f, 3.0f) * 1000).toLong()
						keyRepeatRunnables[index] = repeat
						btn.postDelayed(repeat, repeatDelayMs)
					}
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					keyRepeatRunnables.remove(index)
					btn.removeCallbacks(repeat)
				}
			}
			false // never consume — the button's own click handling stays intact
		}
	}

	private fun createLayoutParams(): WindowManager.LayoutParams {
		val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			type,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
				WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
				WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
			PixelFormat.TRANSLUCENT,
		)
		// TOP|START so drag math uses a top-left origin. Seed near the bottom-right
		// (its prior anchor) using the known content footprint.
		params.gravity = Gravity.TOP or Gravity.START
		val metrics = context.resources.displayMetrics
		val margin = dpToPx(MARGIN_DP)
		params.x = (metrics.widthPixels - dpToPx(CONTENT_WIDTH_DP) - margin).coerceAtLeast(0)
		params.y = (metrics.heightPixels - dpToPx(CONTENT_HEIGHT_DP) - margin).coerceAtLeast(0)
		return params
	}

	private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_DIP,
		dp.toFloat(),
		context.resources.displayMetrics,
	).toInt()

	// ── HighlightBridge ──────────────────────────────────────────────────

	private fun buttonAt(index: Int): SquareButton? {
		val id = BUTTON_IDS.getOrNull(index) ?: return null
		return rootView?.findViewById(id)
	}

	/** Paint a tint over a fresh copy of the key's original background (theme-resolved). */
	private fun paintTint(index: Int, color: Int) {
		val btn = buttonAt(index) ?: return
		val resolved = if (navIsDark()) darkTwoSwitchTint(color) else color
		// In glyph-only mode the original background is null; synthesize a highlight
		// drawable so the highlight is still visible during scan/two-switch cycles.
		val base = buttonOriginalBackgrounds[index]?.constantState?.newDrawable()?.mutate()
			?: btn.background?.mutate()
			?: android.graphics.drawable.GradientDrawable().apply { setColor(resolved) }
		btn.background = DrawableCompat.wrap(base).also { DrawableCompat.setTint(it, resolved) }
	}

	/**
	 * Repaint a key from its CURRENT standing state — the single restore path for flash ends,
	 * appearance/theme rebuilds, and explicit restores. Never restores a snapshot.
	 */
	private fun paintStanding(index: Int) {
		when (val standing = standingState[index]) {
			is Standing.Tint -> paintTint(index, standing.color)
			is Standing.Res -> buttonAt(index)?.background = ContextCompat.getDrawable(context, standing.resId)
			null -> buttonAt(index)?.background = buttonOriginalBackgrounds[index]
		}
	}

	override fun highlightButton(index: Int, color: Int) {
		standingState[index] = Standing.Tint(color)
		paintTint(index, color)
	}

	override fun clearHighlights() {
		for (index in BUTTON_IDS.indices) restoreButton(index)
	}

	override fun flashButton(index: Int, color: Int, durationMs: Long, onComplete: (() -> Unit)?) {
		val btn = buttonAt(index) ?: return
		// Finish any in-flight flash on this key first so its onComplete still runs.
		flashRestores.remove(index)?.let {
			btn.removeCallbacks(it)
			it.run()
		}
		paintTint(index, color)
		val restore = Runnable {
			// Settle to the CURRENT standing background, recomputed now — never a stale snapshot.
			flashRestores.remove(index)
			paintStanding(index)
			onComplete?.invoke()
		}
		flashRestores[index] = restore
		btn.postDelayed(restore, durationMs)
	}

	override fun highlightButtons(highlights: Map<Int, Int>) {
		highlights.forEach { (index, color) -> highlightButton(index, color) }
	}

	override fun restoreButton(index: Int) {
		standingState.remove(index)
		// Assign the stored original, including null (glyph-only mode → transparent).
		buttonAt(index)?.background = buttonOriginalBackgrounds[index]
	}

	// ── TwoSwitchViewBridge ──────────────────────────────────────────────

	override val buttonCount: Int = BUTTON_IDS.size
	override val isViewReady: Boolean get() = rootView != null

	override fun tintButton(index: Int, color: Int) = highlightButton(index, color)

	/** True when the Nav keyboard is rendering its dark theme (used by the service for flash colors). */
	val isDarkTheme: Boolean get() = navIsDark()

	/**
	 * Remap the shared TwoSwitchSubsystem's light red/green/flash tints to darker variants for dark
	 * mode, so the white key glyphs stay legible. The IME uses black glyphs and is unaffected.
	 */
	private fun darkTwoSwitchTint(light: Int): Int = when (light) {
		Color.argb(255, 210, 255, 210) -> NAV_TS_GREEN_DARK // greenTint
		Color.argb(255, 255, 210, 210) -> NAV_TS_RED_DARK // redTint
		Color.argb(255, 100, 255, 100) -> NAV_TS_GREEN_PENDING_DARK // pendingGreenTint
		Color.argb(255, 255, 100, 100) -> NAV_TS_RED_PENDING_DARK // pendingRedTint
		Color.argb(255, 179, 229, 252) -> NAV_TS_GROUP_FLASH_DARK // groupFlashTint
		else -> light
	}

	override fun restoreButtonBackground(index: Int) = restoreButton(index)

	override fun restoreAllBackgrounds() {
		for (index in BUTTON_IDS.indices) restoreButton(index)
	}

	override fun setButtonForeground(index: Int, drawable: Drawable) {
		buttonAt(index)?.foreground = drawable
	}

	override fun clearButtonForeground(index: Int) {
		buttonAt(index)?.foreground = null
	}

	override fun clearAllForegrounds() {
		for (index in BUTTON_IDS.indices) clearButtonForeground(index)
	}

	/** Reset all per-method visual state (highlights + foreground strips) on an input-layer rebuild. */
	fun clearAllDecorations() {
		restoreAllBackgrounds()
		clearAllForegrounds()
	}

	// ── HeadTrackingViewBridge ────────────────────────────────────────────
	// Buttons only. Nav has no center label, keyboard border, or selection list,
	// so those members are no-ops (HT pause/exit on Nav shows via button highlights).

	override fun setButtonDrawable(index: Int, drawableResId: Int) {
		val btn = buttonAt(index) ?: return
		standingState[index] = Standing.Res(drawableResId)
		btn.background = ContextCompat.getDrawable(context, drawableResId)
	}

	override fun setButtonForeground(index: Int, drawableResId: Int) {
		buttonAt(index)?.foreground = ContextCompat.getDrawable(context, drawableResId)
	}

	override fun getButtonForeground(index: Int): Drawable? = buttonAt(index)?.foreground

	override fun restoreButtonForeground(index: Int, drawable: Drawable?) {
		buttonAt(index)?.foreground = drawable
	}

	override fun setCenterLabelDrawable(drawableResId: Int) { /* no center label */ }
	override fun restoreCenterLabelBackground() { /* no center label */ }
	override fun setCenterLabelForeground(drawableResId: Int) { /* no center label */ }
	override fun getCenterLabelForeground(): Drawable? = null
	override fun restoreCenterLabelForeground(drawable: Drawable?) { /* no center label */ }
	override fun showKeyboardBorder(show: Boolean) { /* no border view */ }

	override fun setMousePointerHidden(hidden: Boolean) { /* Nav has no OS mouse pointer */ }
	override fun showSelectionListBorder() { /* no selection list */ }
	override fun hideSelectionListBorder() { /* no selection list */ }
	override fun showSelectionListPaused(text: String) { /* no selection list */ }
	override fun hideSelectionListPaused() { /* no selection list */ }
	override fun resetSelectionListStyling() { /* no selection list */ }

	companion object {
		private const val MARGIN_DP = 16
		private const val CONTENT_WIDTH_DP = 256 // 8dp padding + 240dp grid + 8dp padding
		private const val CONTENT_HEIGHT_DP = 284 // padding + 28dp handle + 240dp grid + padding
		private const val BASE_GRID_DP = 240 // grid footprint at 100% size
		private const val MIN_SIZE_PERCENT = 50
		private const val MAX_SIZE_PERCENT = 150
		private const val KEY_GLYPH_SP = 28f
		private const val KEY_WORD_SP = 11f

		// Slightly larger than the key captions; still small enough that "KEYBOARD"
		// (the longest label word, on its own line) never wraps mid-word.
		private const val CENTER_LABEL_SP = 12f
		private const val CENTER_LABEL_ALPHA = 0x99
		private const val DISABLED_ALPHA = 0.4f
		private const val NAV_FLASH_DURATION_MS = 200L
		private val NAV_KEY_FACE_DARK = Color.parseColor("#424242")

		// Same red as the service's signalNavError, so direct taps and subsystems match.
		private val ERROR_FLASH_COLOR = Color.parseColor("#E53935")

		// Dark-mode two-switch tints — dark enough for the white key glyphs to stay legible.
		private val NAV_TS_GREEN_DARK = Color.parseColor("#2E7D32")
		private val NAV_TS_RED_DARK = Color.parseColor("#C62828")
		private val NAV_TS_GREEN_PENDING_DARK = Color.parseColor("#43A047")
		private val NAV_TS_RED_PENDING_DARK = Color.parseColor("#EF5350")
		private val NAV_TS_GROUP_FLASH_DARK = Color.parseColor("#1E88E5")
		private val BUTTON_IDS = intArrayOf(
			R.id.btn0,
			R.id.btn1,
			R.id.btn2,
			R.id.btn3,
			R.id.btn4,
			R.id.btn5,
			R.id.btn6,
			R.id.btn7,
		)
	}
}
