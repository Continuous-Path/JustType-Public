package org.continuouspath.justtype.ime

import android.content.Context
import android.view.View
import android.widget.TextView
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsDisplayState

/**
 * Manages the settings overlay UI: view lifecycle (show/hide), full-screen expansion
 * above the keyboard, and rendering [SettingsDisplayState] rows (headers, info text,
 * sub-pages, settings with values/descriptions, focused-row highlighting, auto-scroll,
 * banner messages).
 *
 * Extracted from JustTypeIME (Phase 2 Step 11).
 */
class SettingsOverlayController(
	private val context: Context,
	private val callbacks: SettingsOverlayCallbacks,
) {
	// -- View references (set via initViews) --
	private var overlay: View? = null
	private var titleView: TextView? = null
	private var scrollView: android.widget.ScrollView? = null
	private var listContainer: android.widget.LinearLayout? = null
	private var bannerView: TextView? = null

	val isOverlayVisible: Boolean get() = overlay?.visibility == View.VISIBLE

	// -- Public API --

	fun initViews(root: View) {
		overlay = root.findViewById(R.id.settingsOverlay)
		titleView = root.findViewById(R.id.settingsOverlayTitle)
		scrollView = root.findViewById(R.id.settingsOverlayScroll)
		listContainer = root.findViewById(R.id.settingsOverlayList)
		bannerView = root.findViewById(R.id.settingsAutoRevertBanner)
	}

	fun show() {
		callbacks.executeOnUiThread {
			overlay?.visibility = View.VISIBLE
			callbacks.onSettingsOverlayShown()
			expand()
		}
	}

	fun hide() {
		callbacks.executeOnUiThread {
			collapse()
			overlay?.visibility = View.GONE
			listContainer?.removeAllViews()
			bannerView?.visibility = View.GONE
			callbacks.onSettingsOverlayHidden()
		}
	}

	fun updateUI(displayState: SettingsDisplayState) {
		callbacks.executeOnUiThread {
			// Update title
			titleView?.text = displayState.pageTitle
			titleView?.textSize = 20f

			// Update banner — auto-revert message takes priority, then prompt message
			val banner = bannerView
			val bannerText = displayState.autoRevertMessage ?: displayState.promptMessage
			if (bannerText != null && banner != null) {
				banner.text = bannerText
				banner.textSize = 17f
				banner.visibility = View.VISIBLE
			} else {
				banner?.visibility = View.GONE
			}

			// Rebuild the settings list
			val container = listContainer ?: return@executeOnUiThread
			container.removeAllViews()

			val density = context.resources.displayMetrics.density
			val dp10 = (10 * density).toInt()
			val dp6 = (6 * density).toInt()

			for (row in displayState.rows) {
				val rowContainer = android.widget.LinearLayout(context).apply {
					orientation = android.widget.LinearLayout.VERTICAL
					setPadding(dp10, dp6, dp10, dp6)
					// Highlight focused row
					if (row.isFocused) {
						setBackgroundColor(android.graphics.Color.parseColor("#FF616161"))
					} else {
						setBackgroundColor(android.graphics.Color.TRANSPARENT)
					}
				}

				if (row.isSectionHeader) {
					// Section header styling
					val headerView = TextView(context).apply {
						textSize = 17f
						setTextColor(android.graphics.Color.parseColor("#FFEB3B")) // Yellow
						setTypeface(typeface, android.graphics.Typeface.BOLD)
						text = row.label
					}
					rowContainer.addView(headerView)
				} else if (row.isInfoText) {
					// Informational text block (non-focusable body text)
					val infoView = TextView(context).apply {
						textSize = 15f
						setTextColor(android.graphics.Color.parseColor("#CCCCCC")) // Light gray
						text = row.label
						setLineSpacing(0f, 1.2f)
					}
					rowContainer.addView(infoView)
				} else if (row.isSubPage) {
					// SubPage/link styling — distinct color + arrow to cue navigation
					val labelView = TextView(context).apply {
						textSize = 19f
						text = "${row.label} \u25B6" // ▶ arrow
						setTextColor(android.graphics.Color.parseColor("#80D8FF")) // Light cyan
					}
					rowContainer.addView(labelView)

					// Description sub-text (if present)
					if (row.description != null) {
						val descView = TextView(context).apply {
							textSize = 14f
							setTextColor(android.graphics.Color.parseColor("#AAAAAA")) // Light gray
							text = row.description
							setPadding(0, (2 * density).toInt(), 0, 0)
						}
						rowContainer.addView(descView)
					}
				} else {
					// Setting label + value line
					val labelView = TextView(context).apply {
						textSize = 19f
						val sb = android.text.SpannableStringBuilder()
						sb.append(row.label)

						if (row.valueText != null) {
							sb.append("  ")
							val valueStart = sb.length
							if (row.pendingValueText != null) {
								// Show pending value in yellow
								sb.append(row.pendingValueText)
								sb.setSpan(
									android.text.style.ForegroundColorSpan(
										android.graphics.Color.parseColor("#FFEB3B"),
									),
									valueStart,
									sb.length,
									android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
								)
							} else {
								sb.append(row.valueText)
							}
						}

						text = sb
						setTextColor(android.graphics.Color.WHITE)
					}
					rowContainer.addView(labelView)

					// Description sub-text (if present)
					if (row.description != null) {
						val descView = TextView(context).apply {
							textSize = 14f
							setTextColor(android.graphics.Color.parseColor("#AAAAAA")) // Light gray
							text = row.description
							setPadding(0, (2 * density).toInt(), 0, 0)
						}
						rowContainer.addView(descView)
					}
				}

				container.addView(rowContainer)
			}

			// Auto-scroll to focused row
			if (displayState.focusedRowIndex in 0 until container.childCount) {
				val focusedView = container.getChildAt(displayState.focusedRowIndex)
				scrollView?.post {
					scrollView?.smoothScrollTo(0, focusedView.top - (scrollView?.height ?: 0) / 3)
				}
			}

			// Update key labels and center text on the keyboard
			callbacks.updateKeyLabels(displayState.keyLabels)
			callbacks.updateCenterText(displayState.centerText)
		}
	}

	fun destroy() {
		overlay = null
		titleView = null
		scrollView = null
		listContainer = null
		bannerView = null
	}

	// -- Internal --

	/**
	 * Expand the settings overlay to fill all available screen space above the keyboard.
	 * Sets the IME root to the full screen height and gives the overlay weight=1
	 * so it expands to fill the remaining space.
	 */
	private fun expand() {
		val ov = overlay ?: return
		val root = ov.parent as? android.widget.LinearLayout ?: return
		// Use the visible display frame to account for navigation bar and status bar.
		val visibleHeight = run {
			val rect = android.graphics.Rect()
			root.rootView?.getWindowVisibleDisplayFrame(rect)
			if (rect.height() > 0) rect.height() else context.resources.displayMetrics.heightPixels
		}
		root.layoutParams = root.layoutParams?.also {
			it.height = visibleHeight
		}
		// Give the settings overlay FrameLayout weight=1 so it fills remaining space
		val overlayLp = ov.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
		overlayLp.height = 0
		overlayLp.weight = 1f
		ov.layoutParams = overlayLp
		// Set background on the overlay itself so it covers the full expanded area
		ov.setBackgroundColor(android.graphics.Color.parseColor("#DD212121"))
		// Make the ScrollView fill the overlay (instead of fixed 160dp)
		val scroll = scrollView ?: return
		val scrollLp = scroll.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
		scrollLp.height = 0
		scrollLp.weight = 1f
		scroll.layoutParams = scrollLp
		// fillViewport ensures content is top-aligned with blank padding at the bottom
		scroll.isFillViewport = true
	}

	/**
	 * Restore the settings overlay and IME root to normal wrap_content sizing.
	 */
	private fun collapse() {
		val ov = overlay ?: return
		// Restore the IME root to wrap_content
		val root = ov.parent as? android.widget.LinearLayout ?: return
		root.layoutParams = root.layoutParams?.also {
			it.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
		}
		// Restore overlay to wrap_content with no weight
		val overlayLp = ov.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
		overlayLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
		overlayLp.weight = 0f
		ov.layoutParams = overlayLp
		// Clear overlay background (inner LinearLayout has its own background)
		ov.setBackgroundColor(android.graphics.Color.TRANSPARENT)
		// Restore ScrollView to fixed 160dp height
		val scroll = scrollView ?: return
		val scrollLp = scroll.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
		scrollLp.height = (160 * context.resources.displayMetrics.density).toInt()
		scrollLp.weight = 0f
		scroll.layoutParams = scrollLp
		scroll.isFillViewport = false
	}
}

/**
 * Reverse-communication interface from [SettingsOverlayController] back to JustTypeIME.
 */
interface SettingsOverlayCallbacks {
	fun executeOnUiThread(block: () -> Unit)
	fun onSettingsOverlayShown()
	fun onSettingsOverlayHidden()
	fun updateKeyLabels(labels: List<String>)
	fun updateCenterText(text: String)
	fun debugLog(message: String)
}
