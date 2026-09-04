package org.continuouspath.justtype.activity

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import org.continuouspath.justtype.R

/**
 * Attaches a small "i" info icon to a settings row that, when tapped, shows the
 * setting's INFO PROMPT in a dialog and (if Speak Prompts Aloud is ON) speaks
 * it aloud. The icon is inserted programmatically so existing layout XMLs do
 * not need editing.
 *
 * Placement: the icon sits immediately to the right of the setting's label
 * TextView. The label is identified by matching its text against
 * `getString(titleResId)` — the first matching descendant is wrapped in a
 * horizontal LinearLayout containing the original TextView followed by the
 * info ImageButton. The original layout params (and any width/weight) are
 * preserved on the wrapper so the surrounding row still lays out as before.
 */
@Suppress("TooManyFunctions") // Cohesive helper object: small private methods for one feature.
object SettingsInfoHelper {

	/**
	 * Attach an info icon next to the row's label TextView.
	 *
	 * @param rowContainer the row's container (any [ViewGroup]; LinearLayout is typical)
	 * @param titleResId   the setting's display name — used to locate the label
	 *                     and as the dialog title
	 * @param promptResId  the INFO PROMPT body, used as the dialog message and
	 *                     spoken text
	 */
	fun attachInfoIcon(
		rowContainer: ViewGroup,
		@StringRes titleResId: Int,
		@StringRes promptResId: Int,
	) {
		val context = rowContainer.context
		attachInfoIcon(rowContainer, context.getString(titleResId), context.getString(promptResId))
	}

	/**
	 * Resolved-string overload for registry-driven rendering — the renderer
	 * already has [SettingsDef.label] and [SettingsDef.infoPrompt] as
	 * resolved strings, not resource ids. The icon's click shows an
	 * AlertDialog with [title] / [prompt] verbatim.
	 */
	fun attachInfoIcon(
		rowContainer: ViewGroup,
		title: String,
		prompt: String,
	) {
		val titleView = findFirstTextViewByText(rowContainer, title) ?: return
		wrapTitleAndAttach(titleView, title, prompt)
	}

	/**
	 * TextView-based overload for rows whose label text changes at runtime
	 * (e.g., a slider whose title shows the current value), so the
	 * text-matching variant cannot locate it. The caller passes the label
	 * TextView directly.
	 */
	fun attachInfoIcon(
		label: TextView,
		@StringRes titleResId: Int,
		@StringRes promptResId: Int,
	) {
		val context = label.context
		wrapTitleAndAttach(label, context.getString(titleResId), context.getString(promptResId))
	}

	private fun wrapTitleAndAttach(
		titleView: TextView,
		title: String,
		prompt: String,
	) {
		val context = titleView.context
		val parent = titleView.parent as? ViewGroup ?: return
		val originalIndex = parent.indexOfChild(titleView)
		val originalLp = titleView.layoutParams

		parent.removeViewAt(originalIndex)

		val wrapper = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			layoutParams = originalLp
			clipChildren = false
			clipToPadding = false
		}

		val icon = buildIcon(context) { showInfoDialog(context, title, prompt) }

		// Layout strategy depends on what the title view is:
		//   1. CheckBox (list-item-style multi-select) — keep the box on the
		//      LEFT with its inline text intact, append the info icon at the
		//      FAR RIGHT (after a spacer). This matches the visual convention
		//      of CheckBox lists where users expect to see all the boxes
		//      lined up in the same column. (Distinct from #2 below: Switch
		//      controls are right-aligned toggles, CheckBoxes are left-aligned
		//      list items.)
		//   2. Other CompoundButton with inline text (Switch, ToggleButton) —
		//      the row is "[label text … toggle thumb]" inside one widget. We
		//      restructure to "[label][icon][spacer][toggle thumb]" so the
		//      toggle ends up at the far right of the row, vertically aligned
		//      with toggles on Pattern-B rows where the label is a separate
		//      TextView.
		//   3. Plain Button — the button IS the control; give it weight=1 so
		//      it keeps its full-width appearance with the icon at the far
		//      right of the row, vertically aligned with other buttons.
		//   4. Plain TextView label — wrap_content so the icon hugs the text.
		when {
			titleView is CheckBox && !titleView.text.isNullOrEmpty() -> {
				// CheckBox stays at the LEFT with its inline label intact; the
				// info icon sits in the standard footnote position immediately
				// to the right of the label (and slightly above, via the
				// translationY applied in buildIcon). No spacer — pushing it
				// to the far right would visually orphan it from its label.
				titleView.layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				)
				wrapper.addView(titleView)
				wrapper.addView(icon)
			}
			titleView is CompoundButton && !titleView.text.isNullOrEmpty() -> {
				val originalText: CharSequence = titleView.text
				val originalTextSize = titleView.textSize
				val originalTextColor = titleView.currentTextColor
				val label = TextView(context).apply {
					text = originalText
					setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, originalTextSize)
					setTextColor(originalTextColor)
					layoutParams = LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.WRAP_CONTENT,
						LinearLayout.LayoutParams.WRAP_CONTENT,
					)
					// Originally the user could tap the inline text on the
					// CompoundButton to flip it; restructuring puts that text
					// into its own TextView, so we restore tap-to-toggle by
					// proxying clicks to the underlying control.
					isClickable = true
					setOnClickListener { titleView.toggle() }
				}
				// Strip the inline text so the toggle thumb renders alone at
				// the far right of the wrapper.
				titleView.text = ""
				titleView.layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				)
				val spacer = Space(context).apply {
					layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
				}
				wrapper.addView(label)
				wrapper.addView(icon)
				wrapper.addView(spacer)
				wrapper.addView(titleView)
				clampLabelToWrapperWidth(wrapper, label, icon, reservedEndPx = titleView.measuredWidth)
			}
			titleView is Button -> {
				titleView.layoutParams = LinearLayout.LayoutParams(
					0,
					LinearLayout.LayoutParams.WRAP_CONTENT,
					1f,
				)
				wrapper.addView(titleView)
				wrapper.addView(icon)
			}
			else -> {
				titleView.layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.WRAP_CONTENT,
					LinearLayout.LayoutParams.WRAP_CONTENT,
				)
				wrapper.addView(titleView)
				wrapper.addView(icon)
				clampLabelToWrapperWidth(wrapper, titleView, icon)
			}
		}
		parent.addView(wrapper, originalIndex)

		// The icon's footnote-style translationY pushes it above the wrapper's
		// natural bounds. Default clipChildren=true on the row, the row's
		// container, and the page's ScrollView content would otherwise crop
		// the top of the icon. Walk up the ancestor chain and turn clipping
		// off so the icon renders intact.
		disableClippingUpwards(wrapper)

		// Extend the icon's touch region into the empty space above and around
		// it so users with limited motor accuracy can still hit it. We mount a
		// TouchDelegate on the wrapper's parent (typically the row container)
		// so taps in the row's top padding — currently dead space — route to
		// the icon. The rest of the row remains free for its own click-to-
		// toggle handler.
		expandIconHitRegion(wrapper, icon)
	}

	// clipChildren=false (propagated upward to keep the icon's translationY
	// visible) means an overflowing label pushes the icon past the wrapper's
	// right edge, visually overlapping the sibling control.
	private fun clampLabelToWrapperWidth(
		wrapper: LinearLayout,
		label: TextView,
		icon: View,
		reservedEndPx: Int = 0,
	) {
		val apply = Runnable {
			val iconLp = icon.layoutParams as LinearLayout.LayoutParams
			val iconReserve = icon.measuredWidth + iconLp.marginStart + iconLp.marginEnd
			val available = wrapper.measuredWidth - iconReserve - reservedEndPx
			if (available > 0 && label.maxWidth != available) {
				label.maxWidth = available
			}
		}
		wrapper.post(apply)
		wrapper.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
	}

	private fun expandIconHitRegion(wrapper: LinearLayout, icon: ImageButton) {
		val context = wrapper.context
		wrapper.post {
			val delegateHost = wrapper.parent as? ViewGroup ?: return@post
			val rect = Rect()
			icon.getHitRect(rect)
			rect.offset(wrapper.left, wrapper.top)
			rect.top = 0
			rect.left -= dpToPx(context, 6f)
			rect.right += dpToPx(context, 6f)
			rect.bottom += dpToPx(context, 6f)
			delegateHost.touchDelegate = TouchDelegate(rect, icon)
		}
	}

	private fun disableClippingUpwards(start: View) {
		var current: View? = start
		while (current is ViewGroup) {
			current.clipChildren = false
			current.clipToPadding = false
			current = current.parent as? View
		}
	}

	private fun buildIcon(
		context: Context,
		onClick: () -> Unit,
	): ImageButton {
		val typedValue = TypedValue()
		val borderless = if (
			context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
		) {
			androidx.core.content.ContextCompat.getDrawable(context, typedValue.resourceId)
		} else {
			null
		}
		return ImageButton(context).apply {
			setImageResource(R.drawable.ic_info)
			background = borderless
			contentDescription = context.getString(R.string.info_icon_content_description)
			val sizePx = dpToPx(context, 32f)
			val paddingPx = dpToPx(context, 4f)
			layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
				gravity = Gravity.CENTER_VERTICAL
				marginStart = dpToPx(context, 4f)
			}
			setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
			// Footnote-style placement: shift the icon up so it reads as an
			// annotation on the label rather than a peer control. As a side
			// effect on slider rows, this moves the icon further from the
			// slider track, reducing accidental drag-to-set touches.
			translationY = -dpToPx(context, 8f).toFloat()
			setOnClickListener { onClick() }
		}
	}

	private fun showInfoDialog(
		context: Context,
		title: String,
		prompt: String,
	) {
		AlertDialog.Builder(context)
			.setTitle(title)
			.setMessage(prompt)
			.setPositiveButton(android.R.string.ok, null)
			.setOnDismissListener { SettingsSpeechController.stop() }
			.show()
		SettingsSpeechController.speakIfEnabled(context, "$title. $prompt")
	}

	private fun findFirstTextViewByText(root: View, text: String): TextView? {
		if (root is TextView && root.text?.toString() == text) return root
		if (root is ViewGroup) {
			for (i in 0 until root.childCount) {
				val found = findFirstTextViewByText(root.getChildAt(i), text)
				if (found != null) return found
			}
		}
		return null
	}

	private fun dpToPx(context: Context, dp: Float): Int = TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_DIP,
		dp,
		context.resources.displayMetrics,
	).toInt()
}
