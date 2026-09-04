package org.continuouspath.justtype

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import org.continuouspath.justtype.settings.SettingsRepository

/**
 * Small animated corner icon shown over the keyboard while TTS speech is in progress.
 * Decorative only — sighted co-users see that speech is playing — so it is hidden
 * from accessibility and never interactive. Main thread only.
 */
class SpeechIndicatorOverlay(
	private val context: Context,
	private val getHost: () -> FrameLayout?,
) {

	private var view: ImageView? = null
	private var iconResId: Int = 0

	fun show(resId: Int) {
		val host = getHost() ?: return
		var v = view
		if (v == null || v.parent !== host) {
			// Input view was recreated; re-attach to the live host.
			(v?.parent as? ViewGroup)?.removeView(v)
			v = createView(host)
			view = v
			iconResId = 0
		}
		if (iconResId != resId) {
			applyIcon(v, resId)
			iconResId = resId
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			(v.drawable as? AnimatedImageDrawable)?.start()
		}
		v.visibility = View.VISIBLE
	}

	fun hide() {
		val v = view ?: return
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			(v.drawable as? AnimatedImageDrawable)?.stop()
		}
		v.visibility = View.GONE
	}

	private fun createView(host: FrameLayout): ImageView {
		val density = context.resources.displayMetrics.density
		val size = (SIZE_DP * density).toInt()
		val margin = (MARGIN_DP * density).toInt()
		val v = ImageView(context).apply {
			importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			isClickable = false
			isFocusable = false
			elevation = ELEVATION_DP * density
			visibility = View.GONE
			layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
				topMargin = margin
				marginEnd = margin
			}
		}
		host.addView(v)
		return v
	}

	private fun applyIcon(v: ImageView, resId: Int) {
		val animated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeAnimated(resId) else null
		if (animated != null) {
			v.setImageDrawable(animated)
		} else {
			// API 26/27 has no ImageDecoder: static first frame of the APNG.
			v.setImageResource(resId)
		}
	}

	@RequiresApi(Build.VERSION_CODES.P)
	private fun decodeAnimated(resId: Int): Drawable? = runCatching {
		ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, resId)).also {
			(it as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
		}
	}.getOrNull()

	companion object {
		private const val SIZE_DP = 26
		private const val MARGIN_DP = 6
		private const val ELEVATION_DP = 12f

		/** Resolve the user's chosen indicator icon (see Speaking Indicator Icon setting). */
		fun iconResFor(repo: SettingsRepository): Int {
			val choice = repo.getString(Constants.KEY_TTS_SPEAKING_INDICATOR_ICON, Constants.TTS_INDICATOR_ICON_OUTLINED)
			return if (choice == Constants.TTS_INDICATOR_ICON_WAVE) R.drawable.tts_speaking_wave else R.drawable.tts_speaking_outlined
		}
	}
}
