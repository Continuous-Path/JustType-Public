package org.continuouspath.justtype.ime

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlinx.coroutines.CoroutineScope
import org.continuouspath.justtype.input.ExitDirection
import org.continuouspath.justtype.input.InputSurface

/**
 * Callbacks that ViewBridgeCoordinator needs from the IME host.
 */
interface ViewBridgeCallbacks {
	val centerLabelView: TextView?
	val selectionListView: TextView?
	var centerLabelOriginalBackground: Drawable?
	fun getButtonsJT(): List<Button>
	fun triggerKeyActivation(index: Int, suppressBeep: Boolean = false)
	fun triggerSelectActivation()
	val isJtuiInitialized: Boolean
	fun buttonPressedOnUiThread(index: Int)
}

/**
 * Consolidates all view-bridge interface implementations that were previously
 * in JustTypeIME. Subsystems receive this instead of the full IME service.
 */
class ViewBridgeCoordinator(
	override val context: Context,
	override val scope: CoroutineScope,
	private val getButtons: () -> List<Button>,
	val buttonOriginalBackgrounds: MutableMap<Button, Drawable>,
	val flashRestores: MutableMap<Button, Runnable>,
	val standingBackgrounds: MutableMap<Button, Drawable>,
	private val callbacks: ViewBridgeCallbacks,
) : HighlightBridge,
	KeyActivationSink,
	InputSurface,
	TwoSwitchViewBridge,
	JoystickViewBridge,
	HeadTrackingViewBridge {

	private val buttons: List<Button> get() = getButtons()
	private val isButtonsReady: Boolean get() = buttons.isNotEmpty()

	/**
	 * Record + paint a standing (non-flash) background. Standing state is what a key settles
	 * back to when a flash ends, so tints/highlights applied mid-flash are never lost.
	 */
	private fun setStandingBackground(btn: Button, drawable: Drawable) {
		standingBackgrounds[btn] = drawable
		btn.background = drawable
	}

	/** Clear a key's standing tint and paint its original background. */
	private fun clearStandingBackground(btn: Button) {
		standingBackgrounds.remove(btn)
		buttonOriginalBackgrounds[btn]?.let { btn.background = it }
	}

	private fun buildTinted(btn: Button, color: Int): Drawable {
		val orig = buttonOriginalBackgrounds[btn]
		return (orig?.constantState?.newDrawable()?.mutate() ?: btn.background.mutate()).let { d ->
			DrawableCompat.wrap(d).also { DrawableCompat.setTint(it, color) }
		}
	}

	// ── HighlightBridge ──────────────────────────────────────────────────

	override fun highlightButton(index: Int, color: Int) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.let { btn ->
			setStandingBackground(btn, buildTinted(btn, color))
		}
	}

	override fun clearHighlights() {
		if (!isButtonsReady) return
		buttons.forEach { btn ->
			clearStandingBackground(btn)
			btn.foreground = null
		}
	}

	override fun flashButton(index: Int, color: Int, durationMs: Long, onComplete: (() -> Unit)?) {
		if (!isButtonsReady) return
		val btn = buttons.getOrNull(index) ?: return
		// Finish any in-flight flash first so its onComplete still runs.
		flashRestores.remove(btn)?.let {
			btn.removeCallbacks(it)
			it.run()
		}
		btn.background = buildTinted(btn, color)
		val restore = Runnable {
			// Settle to the CURRENT standing background, recomputed now — never a stale snapshot.
			(standingBackgrounds[btn] ?: buttonOriginalBackgrounds[btn])?.let { btn.background = it }
			flashRestores.remove(btn)
			onComplete?.invoke()
		}
		flashRestores[btn] = restore
		btn.postDelayed(restore, durationMs)
	}

	override fun highlightButtons(highlights: Map<Int, Int>) {
		if (!isButtonsReady) return
		highlights.forEach { (index, color) ->
			buttons.getOrNull(index)?.let { btn ->
				setStandingBackground(btn, buildTinted(btn, color))
			}
		}
	}

	override fun restoreButton(index: Int) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.let { btn ->
			clearStandingBackground(btn)
			btn.foreground = null
		}
	}

	// ── KeyActivationSink ────────────────────────────────────────────────

	override fun activateKey(index: Int) {
		callbacks.triggerKeyActivation(index)
	}

	override fun activateKeySilent(index: Int) {
		if (!callbacks.isJtuiInitialized) return
		callbacks.buttonPressedOnUiThread(index)
	}

	override fun activateKeyNoBeep(index: Int) {
		callbacks.triggerKeyActivation(index, suppressBeep = true)
	}

	override fun activateSelect() {
		callbacks.triggerSelectActivation()
	}

	override fun isReady(): Boolean = callbacks.isJtuiInitialized

	// ── InputSurface ─────────────────────────────────────────────────────

	override fun onButtonPressed(index: Int): Boolean {
		// The IME always handles a press (types, or produces its own error feedback via the JTUI path).
		callbacks.triggerKeyActivation(index)
		return true
	}

	override fun onSelect() {
		callbacks.triggerSelectActivation()
	}

	override fun onExitGesture(direction: ExitDirection) { /* IME has no exit-gesture handling here yet */ }

	// ── TwoSwitchViewBridge + JoystickViewBridge + HeadTrackingViewBridge ─

	override val buttonCount: Int
		get() = if (isButtonsReady) buttons.size else 0

	override val isViewReady: Boolean
		get() = isButtonsReady

	override fun tintButton(index: Int, color: Int) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.let { btn ->
			setStandingBackground(btn, buildTinted(btn, color))
		}
	}

	override fun restoreButtonBackground(index: Int) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.let { btn ->
			clearStandingBackground(btn)
		}
	}

	override fun setButtonDrawable(index: Int, drawableResId: Int) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.let { btn ->
			if (!buttonOriginalBackgrounds.containsKey(btn)) {
				buttonOriginalBackgrounds[btn] = btn.background
			}
			ContextCompat.getDrawable(context, drawableResId)?.let { setStandingBackground(btn, it) }
		}
	}

	// ── HeadTrackingViewBridge-specific ───────────────────────────────────

	override fun setCenterLabelDrawable(drawableResId: Int) {
		val cl = callbacks.centerLabelView ?: return
		if (callbacks.centerLabelOriginalBackground == null) {
			callbacks.centerLabelOriginalBackground = cl.background
		}
		cl.background = ContextCompat.getDrawable(context, drawableResId)
	}

	override fun restoreCenterLabelBackground() {
		callbacks.centerLabelView?.let { cl ->
			callbacks.centerLabelOriginalBackground?.let { cl.background = it } ?: run { cl.background = null }
		}
	}

	override fun setButtonForeground(index: Int, drawableResId: Int) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.let { btn ->
			btn.foreground = ContextCompat.getDrawable(context, drawableResId)
		}
	}

	override fun setCenterLabelForeground(drawableResId: Int) {
		callbacks.centerLabelView?.foreground = ContextCompat.getDrawable(context, drawableResId)
	}

	override fun getButtonForeground(index: Int): Drawable? {
		if (!isButtonsReady) return null
		return buttons.getOrNull(index)?.foreground
	}

	override fun getCenterLabelForeground(): Drawable? = callbacks.centerLabelView?.foreground

	override fun restoreButtonForeground(index: Int, drawable: Drawable?) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.foreground = drawable
	}

	override fun restoreCenterLabelForeground(drawable: Drawable?) {
		callbacks.centerLabelView?.foreground = drawable
	}

	override fun showKeyboardBorder(show: Boolean) {
		val buttonsJT = callbacks.getButtonsJT()
		if (buttonsJT.isEmpty()) return
		val keyGrid = buttonsJT.firstOrNull()?.parent as? android.view.ViewGroup ?: return
		if (show) {
			val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
				setStroke(4, android.graphics.Color.parseColor("#04DE71"))
				setColor(android.graphics.Color.TRANSPARENT)
			}
			keyGrid.foreground = borderDrawable
		} else {
			keyGrid.foreground = null
		}
	}

	override fun setMousePointerHidden(hidden: Boolean) {
		val buttons = callbacks.getButtonsJT()
		val anchor = buttons.firstOrNull() ?: return
		val type = if (hidden) android.view.PointerIcon.TYPE_NULL else android.view.PointerIcon.TYPE_DEFAULT
		val icon = android.view.PointerIcon.getSystemIcon(anchor.context, type)
		// The pointer resolves its icon on the hovered view first (a button), then its ancestors. Set
		// it on every button plus the grid parent and window root, so whichever the OS consults on our
		// non-focusable window wins — the hovered button can be any of them.
		buttons.forEach { it.pointerIcon = icon }
		(anchor.parent as? android.view.View)?.pointerIcon = icon
		anchor.rootView.pointerIcon = icon
	}

	override fun showSelectionListBorder() {
		val selView = callbacks.selectionListView ?: return
		val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
			setStroke(4, android.graphics.Color.parseColor("#04DE71"))
			setColor(android.graphics.Color.TRANSPARENT)
		}
		selView.foreground = borderDrawable
	}

	override fun hideSelectionListBorder() {
		callbacks.selectionListView?.foreground = null
	}

	override fun showSelectionListPaused(text: String) {
		val selView = callbacks.selectionListView ?: return
		selView.text = text
		selView.gravity = android.view.Gravity.CENTER
		selView.setTypeface(selView.typeface, android.graphics.Typeface.BOLD)
	}

	override fun hideSelectionListPaused() {
		val selView = callbacks.selectionListView ?: return
		selView.gravity = android.view.Gravity.START or android.view.Gravity.TOP
		selView.textAlignment = android.view.View.TEXT_ALIGNMENT_TEXT_START
		selView.setTypeface(null, android.graphics.Typeface.NORMAL)
	}

	override fun resetSelectionListStyling() {
		val selView = callbacks.selectionListView ?: return
		selView.gravity = android.view.Gravity.START or android.view.Gravity.TOP
		selView.textAlignment = android.view.View.TEXT_ALIGNMENT_TEXT_START
		selView.setTypeface(null, android.graphics.Typeface.NORMAL)
	}

	override fun restoreAllBackgrounds() {
		if (!isButtonsReady) return
		buttons.forEach { btn ->
			clearStandingBackground(btn)
		}
	}

	override fun setButtonForeground(index: Int, drawable: Drawable) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.let { it.foreground = drawable }
	}

	override fun clearButtonForeground(index: Int) {
		if (!isButtonsReady) return
		buttons.getOrNull(index)?.let { it.foreground = null }
	}

	override fun clearAllForegrounds() {
		if (!isButtonsReady) return
		buttons.forEach { it.foreground = null }
	}
}
