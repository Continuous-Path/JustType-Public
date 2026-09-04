package org.continuouspath.justtype

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.continuouspath.justtype.navigation.NavigationModeService
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke: proves JT binds and works on a real framework — the regressions
 * (manifest/service/binding/window) that Robolectric structurally cannot catch.
 * Run via `./jt test-instrumented`. Restores prior IME + accessibility state per test.
 */
@RunWith(AndroidJUnit4::class)
class JustTypeSmokeTest {

	private val instrumentation = InstrumentationRegistry.getInstrumentation()

	private var previousDefaultIme: String? = null
	private var imeWasEnabled = false
	private var previousA11yServices: String? = null
	private var previousA11yEnabled: String? = null
	private var activity: SmokeTestActivity? = null

	@Before
	fun saveDeviceState() {
		// Fresh installs would otherwise route first-run flows (Welcome Guide) over the editor.
		SettingsRepository.getInstance(instrumentation.targetContext)
			.putBoolean(Constants.KEY_WELCOME_GUIDE_SEEN, true)
		previousDefaultIme = shell("settings get secure default_input_method").trim().takeUnless { it == "null" || it.isEmpty() }
		imeWasEnabled = shell("ime list -s").lineSequence().any { it.trim() == IME_ID }
		previousA11yServices = shell("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }
		previousA11yEnabled = shell("settings get secure accessibility_enabled").trim().takeUnless { it == "null" }
	}

	@After
	fun restoreDeviceState() {
		activity?.let { instrumentation.runOnMainSync { it.finish() } }
		activity = null
		previousDefaultIme?.let { shell("ime set $it") }
		if (!imeWasEnabled) shell("ime disable $IME_ID")
		restoreSecureSetting("enabled_accessibility_services", previousA11yServices)
		restoreSecureSetting("accessibility_enabled", previousA11yEnabled)
		waitFor("NavigationModeService stopped") { !NavigationModeService.isRunning }
	}

	@Test
	fun imeBindsAndShowsInputView() {
		activateJustTypeIme()
		launchEditor()
		waitFor("IME service bound", timeoutMs = LONG_TIMEOUT_MS) { JustTypeIME.activeInstance != null }
		waitFor("input view shown", timeoutMs = LONG_TIMEOUT_MS) {
			JustTypeIME.activeInstance?.isInputViewShown == true
		}
	}

	@Test
	fun commitTextRoundTripsThroughRealInputConnection() {
		activateJustTypeIme()
		val editor = launchEditor()
		waitFor("editor connected to IME", timeoutMs = LONG_TIMEOUT_MS) {
			JustTypeIME.activeInstance?.currentInputConnection != null
		}
		instrumentation.runOnMainSync {
			JustTypeIME.activeInstance!!.currentInputConnection.commitText("jt", 1)
		}
		waitFor("committed text visible in editor") { editor.editor.text.toString() == "jt" }
		assertEquals("jt", editor.editor.text.toString())
	}

	@Test
	fun navigationServiceStartsAndStops() {
		val merged = listOfNotNull(previousA11yServices?.takeUnless { it.isEmpty() }, NAV_SERVICE_ID)
			.joinToString(":")
		shell("settings put secure enabled_accessibility_services $merged")
		shell("settings put secure accessibility_enabled 1")
		waitFor("NavigationModeService running", timeoutMs = LONG_TIMEOUT_MS) { NavigationModeService.isRunning }

		restoreSecureSetting("enabled_accessibility_services", previousA11yServices)
		waitFor("NavigationModeService stopped") { !NavigationModeService.isRunning }
	}

	private fun activateJustTypeIme() {
		// Fresh installs race IME registration; retry until the system lists it.
		waitFor("JustType registered and enabled as an IME") {
			shell("ime enable $IME_ID")
			shell("ime list -s").lineSequence().any { it.trim() == IME_ID }
		}
		waitFor("JustType selected as default IME") {
			shell("ime set $IME_ID")
			shell("settings get secure default_input_method").trim() == IME_ID
		}
	}

	private fun launchEditor(): SmokeTestActivity {
		val intent = Intent(instrumentation.targetContext, SmokeTestActivity::class.java)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		val launched = instrumentation.startActivitySync(intent) as SmokeTestActivity
		activity = launched
		instrumentation.runOnMainSync {
			launched.editor.requestFocus()
			val imm = launched.getSystemService(InputMethodManager::class.java)
			imm.showSoftInput(launched.editor, 0)
		}
		return launched
	}

	private fun restoreSecureSetting(key: String, value: String?) {
		if (value == null) {
			shell("settings delete secure $key")
		} else {
			shell("settings put secure $key \"$value\"")
		}
	}

	private fun shell(command: String): String {
		// A plain UiAutomation connection SUPPRESSES every other accessibility service,
		// which would keep NavigationModeService from ever binding mid-test.
		val automation = instrumentation.getUiAutomation(
			android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
		)
		val pfd = automation.executeShellCommand(command)
		return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
	}

	private fun waitFor(what: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, condition: () -> Boolean) {
		val deadline = SystemClock.uptimeMillis() + timeoutMs
		while (SystemClock.uptimeMillis() < deadline) {
			if (condition()) return
			SystemClock.sleep(POLL_MS)
		}
		fail("Timed out after ${timeoutMs}ms waiting for: $what; ${diagnostics()}")
	}

	private fun diagnostics(): String {
		val inst = JustTypeIME.activeInstance
		val imms = shell("dumpsys input_method").lineSequence()
			.filter { line -> listOf("mCurId=", "mInputShown=", "mBoundToMethod=", "mCurClient=").any { it in line } }
			.joinToString(" ") { it.trim() }
		return "activeInstance=${inst != null} inputConnection=${inst?.currentInputConnection != null} " +
			"inputViewShown=${inst?.isInputViewShown} | $imms"
	}

	private companion object {
		const val IME_ID = "org.continuouspath.justtype/.JustTypeIME"

		// Fully-qualified class: raw `settings put` writes don't expand the /.short form.
		const val NAV_SERVICE_ID = "org.continuouspath.justtype/org.continuouspath.justtype.navigation.NavigationModeService"
		const val DEFAULT_TIMEOUT_MS = 5_000L
		const val LONG_TIMEOUT_MS = 15_000L
		const val POLL_MS = 200L
	}
}
