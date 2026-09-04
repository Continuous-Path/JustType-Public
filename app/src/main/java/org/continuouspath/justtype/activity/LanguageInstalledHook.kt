package org.continuouspath.justtype.activity

import android.app.Activity
import org.continuouspath.justtype.settings.SettingsRepository

/**
 * Seam for a future language-download flow. A download flow calls [onLanguageInstalled] after a
 * `{languageId}DbActive.db` lands and `LanguageRegistry.upsert(present = true)` has run, so the app can
 * prompt for that language's TTS voice at install time. No network/manifest code lives here — only the
 * integration point.
 */
fun interface LanguageInstalledHook {
	fun onLanguageInstalled(languageId: String)
}

/**
 * Default [LanguageInstalledHook]: opens the voice picker for the just-installed language (seeded by the
 * global voice-type preference) and persists the chosen voice. Because voice prefs are keyed by id, the
 * choice can be set for a not-yet-active language and auto-applies on the next switch to it.
 */
class VoicePickerLanguageInstalledHook(
	private val activity: Activity,
	private val repo: SettingsRepository,
) : LanguageInstalledHook {
	override fun onLanguageInstalled(languageId: String) {
		TtsVoicePicker(activity, repo, languageId).show()
	}
}
