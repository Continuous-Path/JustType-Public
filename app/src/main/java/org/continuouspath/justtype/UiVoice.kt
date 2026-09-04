package org.continuouspath.justtype

import org.continuouspath.justtype.settings.SettingsRepository
import java.util.Locale

/**
 * The "device voice": the TTS voice used when JustType addresses the USER — spoken settings prompts,
 * key names, and other UI announcements — as opposed to the typing-language voice, which speaks the
 * user's own composed text to the world. Keeping them distinct makes prompts recognizably "system"
 * speech, and lets the UI voice follow the UI language rather than the typing language.
 *
 * The chosen voice is stored per UI language in [LanguageTtsPreferences] under a synthetic
 * `ui-{code}` key, so switching UI languages remembers each language's device voice.
 */
object UiVoice {
	private const val PREF_KEY_PREFIX = "ui-"

	/** The UI language code in effect ("en", "es", …): the app-language pref, or the system locale. */
	fun resolvedLocaleCode(repo: SettingsRepository): String {
		val pref = repo.getString(Constants.KEY_APP_LANGUAGE, Constants.LANGUAGE_SYSTEM)
		return if (pref == Constants.LANGUAGE_SYSTEM) Locale.getDefault().language else pref
	}

	fun locale(repo: SettingsRepository): Locale = Locale(resolvedLocaleCode(repo))

	/** [LanguageTtsPreferences] key for the UI voice of the given UI-language code. */
	fun prefKey(localeCode: String): String = PREF_KEY_PREFIX + localeCode

	/** [LanguageTtsPreferences] key for the UI voice of the CURRENT UI language. */
	fun prefKey(repo: SettingsRepository): String = prefKey(resolvedLocaleCode(repo))

	/** The voice-type preference governing [voicePrefKey]: UI feedback voices have their own. */
	fun genderPrefKeyFor(voicePrefKey: String): String = if (voicePrefKey.startsWith(PREF_KEY_PREFIX)) Constants.KEY_TTS_UI_VOICE_GENDER else Constants.KEY_TTS_VOICE_GENDER

	/** Display name of the current UI language, in that language (e.g. "Español"). */
	fun displayName(repo: SettingsRepository): String {
		val locale = locale(repo)
		return locale.getDisplayLanguage(locale).replaceFirstChar { it.uppercase(locale) }
	}
}
