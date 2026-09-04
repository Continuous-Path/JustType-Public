package org.continuouspath.justtype.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.input.HeadBoardSwitchBus

/**
 * Single host activity for input-method setup flows.
 *
 * Phase 4.2 in progress: targets convert to fragments one substep at a time.
 * Shim arms route to the legacy `Setup*Activity` for targets not yet
 * converted; once 4.2.5 lands all five conversions, the shim is removed.
 */
class SetupHostActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val target = intent?.getStringExtra(EXTRA_SETUP_TARGET)
		val fragment = fragmentFor(target)
		if (fragment != null) {
			setContentView(R.layout.activity_setup_host)
			if (savedInstanceState == null) {
				supportFragmentManager.beginTransaction()
					.replace(R.id.setup_host_container, fragment)
					.commit()
			}
			return
		}
		val routeTo = SHIM_TARGETS[target]
		if (routeTo == null) {
			Log.w(TAG, "SetupHostActivity launched with missing/unknown target: $target")
			finish()
			return
		}
		startActivity(Intent(this, routeTo))
		finish()
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		val frag = supportFragmentManager.findFragmentById(R.id.setup_host_container)
		if (frag is KeyEventInterceptor && frag.interceptKeyEvent(event)) return true
		return super.dispatchKeyEvent(event)
	}

	// HeadBoard-driven switch events go to the capturing fragment only — performing the bound
	// HeadBoard gesture assigns it as a switch, exactly like pressing a physical key. Events
	// are not fed to the rest of the view hierarchy.
	private val headBoardSwitchConsumer = HeadBoardSwitchBus.Consumer { event ->
		val frag = supportFragmentManager.findFragmentById(R.id.setup_host_container)
		frag is KeyEventInterceptor && frag.interceptKeyEvent(event)
	}

	override fun onStart() {
		super.onStart()
		HeadBoardSwitchBus.addConsumer(this, HeadBoardSwitchBus.Priority.CAPTURE, headBoardSwitchConsumer)
	}

	override fun onStop() {
		HeadBoardSwitchBus.removeConsumer(headBoardSwitchConsumer)
		super.onStop()
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean {
		val frag = supportFragmentManager.findFragmentById(R.id.setup_host_container)
		if (frag is MotionEventInterceptor && frag.interceptMotionEvent(event)) return true
		return super.onGenericMotionEvent(event)
	}

	companion object {
		private const val TAG = "SetupHostActivity"

		const val EXTRA_SETUP_TARGET: String = "setup_target"

		const val TARGET_HEAD_TRACKING: String = "head_tracking"
		const val TARGET_JOYSTICK: String = "joystick"
		const val TARGET_MOUSE_JOYSTICK: String = "mouse_joystick"
		const val TARGET_SINGLE_SWITCH: String = "single_switch"
		const val TARGET_TWO_SWITCH: String = "two_switch"
		const val TARGET_TOUCH_SCREEN_SWITCH: String = "touch_screen_switch"
		const val TARGET_DIRECTIONAL_SELECTION: String = "directional_selection"
		const val TARGET_DIRECT_SELECTION: String = "direct_selection"
		const val TARGET_NAVIGATION_MODE: String = "navigation_mode"

		/**
		 * Fragment-mode targets. Each substep moves one target out of
		 * [SHIM_TARGETS] and adds it here. When [SHIM_TARGETS] empties
		 * at 4.2.5 close, the shim path is deleted.
		 */
		private fun fragmentFor(target: String?): Fragment? = when (target) {
			TARGET_JOYSTICK -> SetupJoystickFragment()
			TARGET_MOUSE_JOYSTICK -> SetupMouseJoystickFragment()
			TARGET_TOUCH_SCREEN_SWITCH -> SetupTouchScreenSwitchFragment()
			TARGET_TWO_SWITCH -> SetupTwoSwitchFragment()
			TARGET_SINGLE_SWITCH -> SetupSingleSwitchFragment()
			TARGET_HEAD_TRACKING -> SetupHeadTrackingFragment()
			TARGET_NAVIGATION_MODE -> SetupNavigationModeFragment()
			else -> null
		}

		/**
		 * Legacy shim routing — only tutorial-only targets remain.
		 * 4.2.6 absorbs these and the shim path is deleted.
		 */
		private val SHIM_TARGETS: Map<String, Class<out AppCompatActivity>> = mapOf(
			TARGET_DIRECTIONAL_SELECTION to SetupDirectionalSelectionActivity::class.java,
			TARGET_DIRECT_SELECTION to SetupDirectSelectionActivity::class.java,
		)

		/** Single launch entry point for callers — encapsulates extras. */
		fun intent(context: Context, target: String): Intent = Intent(context, SetupHostActivity::class.java).apply {
			putExtra(EXTRA_SETUP_TARGET, target)
		}
	}
}
