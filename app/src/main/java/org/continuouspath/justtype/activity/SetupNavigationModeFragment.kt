package org.continuouspath.justtype.activity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.R
import org.continuouspath.justtype.navigation.NavigationModeService
import org.continuouspath.justtype.settings.SettingsRepository

class SetupNavigationModeFragment : Fragment() {

	private lateinit var prefs: SettingsRepository
	private lateinit var serviceStatus: TextView
	private lateinit var overlayStatus: TextView

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.fragment_setup_navigation_mode, container, false)

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		prefs = SettingsRepository.getInstance(requireContext())

		view.findViewById<ImageButton>(R.id.backButton).setOnClickListener {
			activity?.takeIf { !it.isFinishing }?.finish()
		}

		val toggle: SwitchCompat = view.findViewById(R.id.navigationModeToggle)
		toggle.isChecked = prefs.getBoolean(Constants.KEY_NAVIGATION_MODE_ENABLED, false)
		toggle.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(Constants.KEY_NAVIGATION_MODE_ENABLED, isChecked)
			if (!isChecked) {
				prefs.putBoolean(Constants.KEY_NAVIGATION_OVERLAY_REQUESTED, false)
			}
			refreshStatus()
		}

		serviceStatus = view.findViewById(R.id.serviceStatus)
		overlayStatus = view.findViewById(R.id.overlayStatus)

		view.findViewById<Button>(R.id.openAccessibilitySettingsButton).setOnClickListener {
			runCatching {
				startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
			}
		}

		view.findViewById<Button>(R.id.showOverlayNowButton).setOnClickListener {
			// Reinstalls reset the overlay app-op; route to the grant screen if it's missing
			// rather than requesting an overlay that silently can't appear.
			if (!Settings.canDrawOverlays(requireContext())) {
				requestOverlayPermission()
			} else {
				prefs.putBoolean(Constants.KEY_NAVIGATION_OVERLAY_REQUESTED, true)
			}
		}

		val liveDrag: SwitchCompat = view.findViewById(R.id.liveDragToggle)
		liveDrag.isChecked = prefs.getBoolean(Constants.KEY_NAV_LIVE_DRAG, false)
		liveDrag.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(Constants.KEY_NAV_LIVE_DRAG, isChecked)
		}

		bindAppearanceControls(view)
		refreshStatus()
	}

	private fun bindAppearanceControls(view: View) {
		val themeGroup: RadioGroup = view.findViewById(R.id.navThemeGroup)
		when (prefs.getString(Constants.KEY_NAV_THEME, Constants.NAV_THEME_SYSTEM)) {
			Constants.NAV_THEME_LIGHT -> themeGroup.check(R.id.themeLight)
			Constants.NAV_THEME_DARK -> themeGroup.check(R.id.themeDark)
			else -> themeGroup.check(R.id.themeSystem)
		}
		themeGroup.setOnCheckedChangeListener { _, checkedId ->
			val theme = when (checkedId) {
				R.id.themeLight -> Constants.NAV_THEME_LIGHT
				R.id.themeDark -> Constants.NAV_THEME_DARK
				else -> Constants.NAV_THEME_SYSTEM
			}
			prefs.putString(Constants.KEY_NAV_THEME, theme)
		}
		bindPercentSeek(
			view.findViewById(R.id.keyOpacitySeek),
			view.findViewById(R.id.keyOpacityValue),
			Constants.KEY_NAV_KEY_OPACITY_PERCENT,
			Constants.NAV_KEY_OPACITY_DEFAULT,
			min = 0,
		)
		bindPercentSeek(
			view.findViewById(R.id.panelOpacitySeek),
			view.findViewById(R.id.panelOpacityValue),
			Constants.KEY_NAV_PANEL_OPACITY_PERCENT,
			Constants.NAV_PANEL_OPACITY_DEFAULT,
			min = 0,
		)
		bindPercentSeek(
			view.findViewById(R.id.sizeSeek),
			view.findViewById(R.id.sizeValue),
			Constants.KEY_NAV_SIZE_PERCENT,
			Constants.NAV_SIZE_PERCENT_DEFAULT,
			min = SIZE_MIN_PERCENT,
		)

		val modeGroup: RadioGroup = view.findViewById(R.id.transparencyModeGroup)
		when (prefs.getString(Constants.KEY_NAV_TRANSPARENCY_MODE, Constants.NAV_TRANSPARENCY_GLYPH_OUTLINE)) {
			Constants.NAV_TRANSPARENCY_GLYPH_ONLY -> modeGroup.check(R.id.modeGlyphOnly)
			Constants.NAV_TRANSPARENCY_UNIFORM -> modeGroup.check(R.id.modeUniform)
			else -> modeGroup.check(R.id.modeGlyphOutline)
		}
		modeGroup.setOnCheckedChangeListener { _, checkedId ->
			val mode = when (checkedId) {
				R.id.modeGlyphOnly -> Constants.NAV_TRANSPARENCY_GLYPH_ONLY
				R.id.modeUniform -> Constants.NAV_TRANSPARENCY_UNIFORM
				else -> Constants.NAV_TRANSPARENCY_GLYPH_OUTLINE
			}
			prefs.putString(Constants.KEY_NAV_TRANSPARENCY_MODE, mode)
		}

		val hideHandle: SwitchCompat = view.findViewById(R.id.hideHandleToggle)
		hideHandle.isChecked = prefs.getBoolean(Constants.KEY_NAV_HIDE_DRAG_HANDLE, false)
		hideHandle.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(Constants.KEY_NAV_HIDE_DRAG_HANDLE, isChecked)
		}
	}

	/** SeekBar over [min]..[min]+100. Persists the absolute percent; label shows it. */
	private fun bindPercentSeek(seek: SeekBar, label: TextView, key: String, default: Int, min: Int) {
		val saved = prefs.getInt(key, default).coerceIn(min, min + seek.max)
		seek.progress = saved - min
		label.text = getString(R.string.format_tss_button_height_percent, saved)
		seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
				label.text = getString(R.string.format_tss_button_height_percent, min + progress)
			}

			override fun onStartTrackingTouch(s: SeekBar?) { /* no-op */ }

			override fun onStopTrackingTouch(s: SeekBar?) {
				prefs.putInt(key, min + seek.progress)
			}
		})
	}

	override fun onResume() {
		super.onResume()
		refreshStatus()
	}

	private fun refreshStatus() {
		serviceStatus.text = getString(
			if (NavigationModeService.isRunning) {
				R.string.navigation_mode_status_service_running
			} else {
				R.string.navigation_mode_status_service_not_running
			},
		)
		overlayStatus.text = getString(
			if (Settings.canDrawOverlays(requireContext())) {
				R.string.navigation_mode_status_overlay_granted
			} else {
				R.string.navigation_mode_status_overlay_missing
			},
		)
	}

	private fun requestOverlayPermission() {
		runCatching {
			startActivity(
				Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
					data = android.net.Uri.parse("package:${requireContext().packageName}")
				},
			)
		}
	}

	private companion object {
		const val SIZE_MIN_PERCENT = 50 // size SeekBar (max 100) -> 50..150%
	}
}
