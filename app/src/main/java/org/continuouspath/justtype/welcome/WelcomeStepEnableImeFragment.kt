package org.continuouspath.justtype.welcome

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.R

class WelcomeStepEnableImeFragment : Fragment() {
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = inflater.inflate(R.layout.fragment_welcome_step_enable_ime, container, false)

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.findViewById<Button>(R.id.openImeSettingsButton).setOnClickListener {
			// Some OEM ROMs lack this settings screen; fail quietly rather than crash.
			runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
		}
		view.findViewById<Button>(R.id.switchImeButton).setOnClickListener {
			imeManager()?.showInputMethodPicker()
		}
	}

	override fun onResume() {
		super.onResume()
		val status = view?.findViewById<TextView>(R.id.imeStatus) ?: return
		status.setText(
			if (isJustTypeImeEnabled()) {
				R.string.welcome_enable_status_enabled
			} else {
				R.string.welcome_enable_status_not_enabled
			},
		)
	}

	private fun imeManager(): InputMethodManager? = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

	private fun isJustTypeImeEnabled(): Boolean = try {
		imeManager()?.enabledInputMethodList?.any { it.packageName == context?.packageName } == true
	} catch (_: Exception) {
		false
	}
}
