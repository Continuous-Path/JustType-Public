package org.continuouspath.justtype.layout

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import androidx.core.graphics.drawable.DrawableCompat
import org.continuouspath.justtype.view.SquareButton

/**
 * Base abstract implementation of KeyboardLayoutController with common functionality.
 */
abstract class BaseLayoutController : KeyboardLayoutController {
	protected val mainHandler = Handler(Looper.getMainLooper())
	protected val flashRestores = mutableMapOf<Button, Runnable>()
	override val buttonOriginalBackgrounds = mutableMapOf<Button, Drawable?>()

	protected var highlightedIndex: Int? = null

	override fun setVisible(visible: Boolean) {
		rootView.visibility = if (visible) View.VISIBLE else View.GONE
	}

	override fun highlightButton(
		index: Int,
		color: Int,
	) {
		// Clear previous highlight
		highlightedIndex?.let { prev ->
			buttons.getOrNull(prev)?.let { btn ->
				buttonOriginalBackgrounds[btn]?.let { btn.background = it }
			}
		}

		// Apply new highlight
		buttons.getOrNull(index)?.let { btn ->
			val orig = buttonOriginalBackgrounds[btn]
			val tinted =
				(orig?.constantState?.newDrawable()?.mutate() ?: btn.background.mutate()).let { d ->
					DrawableCompat.wrap(d).also { DrawableCompat.setTint(it, color) }
				}
			btn.background = tinted
		}

		highlightedIndex = index
	}

	override fun clearHighlights() {
		highlightedIndex?.let { prev ->
			buttons.getOrNull(prev)?.let { btn ->
				buttonOriginalBackgrounds[btn]?.let { btn.background = it }
			}
		}
		highlightedIndex = null
	}

	override fun flashButton(
		index: Int,
		color: Int,
		durationMs: Long,
		onComplete: (() -> Unit)?,
	) {
		buttons.getOrNull(index)?.let { btn ->
			// Cancel any pending restore
			flashRestores.remove(btn)?.let { btn.removeCallbacks(it) }
			btn.animate().cancel()

			val origBg = buttonOriginalBackgrounds[btn]
			val tinted =
				(origBg?.constantState?.newDrawable()?.mutate() ?: btn.background.mutate()).let { d ->
					DrawableCompat.wrap(d).also { DrawableCompat.setTint(it, color) }
				}
			btn.background = tinted

			val restore =
				Runnable {
					buttonOriginalBackgrounds[btn]?.let { btn.background = it }
					flashRestores.remove(btn)
					onComplete?.invoke()
				}
			flashRestores[btn] = restore
			btn.postDelayed(restore, durationMs)
		}
	}

	override fun setKeyHistoryVisible(showKeyHistory: Boolean) {
		keyHistoryScrollView.visibility = if (showKeyHistory) View.VISIBLE else View.GONE
	}

	override fun setKeyHistoryMarkLatest(enabled: Boolean) {
		keyHistoryView.setMarkLatest(enabled)
	}

	override fun setKeyHistoryShrinkToFit(enabled: Boolean) {
		keyHistoryView.setShrinkToFitEnabled(enabled)

		// Push the current available extent if the scroll view has already been laid out.
		// If it hasn't been laid out yet, the OnLayoutChangeListener installed in
		// JtLayoutController/ScanLayoutController will call setAvailableExtent as soon
		// as the first layout pass completes. The previous `post { ... }` here was racy:
		// the posted runnable could fire before the layout pass, and its `extent > 0`
		// guard would silently no-op, leaving availableExtentPx at 0.
		val sv = keyHistoryScrollView
		val extent =
			if (keyHistoryView.isVertical) {
				sv.height - sv.paddingTop - sv.paddingBottom
			} else {
				sv.width - sv.paddingLeft - sv.paddingRight
			}
		if (extent > 0) {
			keyHistoryView.setAvailableExtent(extent)
		}
	}

	override fun cleanup() {
		// Cancel all pending flash restores
		flashRestores.forEach { (btn, runnable) ->
			btn.removeCallbacks(runnable)
			btn.animate().cancel()
			buttonOriginalBackgrounds[btn]?.let { btn.background = it }
		}
		flashRestores.clear()
		clearHighlights()
	}

	/**
	 * Store original backgrounds for all buttons.
	 * Should be called after buttons are initialized.
	 */
	protected fun storeOriginalBackgrounds() {
		buttons.forEach { btn ->
			buttonOriginalBackgrounds[btn] = btn.background
			// SquareButton compares its live background against this resting instance by
			// identity to choose text colour (dark on light highlights, light at rest).
			(btn as? SquareButton)?.setRestingBackground(btn.background)
		}
	}

	/**
	 * Apply common button styling (typeface, alignment).
	 */
	protected fun applyButtonStyling() {
		buttons.forEach { btn ->
			btn.typeface = android.graphics.Typeface.MONOSPACE
			btn.textAlignment = View.TEXT_ALIGNMENT_CENTER
		}
	}
}
