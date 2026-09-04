package org.continuouspath.justtype

import android.content.Context
import android.content.res.Configuration
import org.continuouspath.justtype.LocaleHelper.wrap
import java.util.Locale

/**
 * Utility for applying the user's chosen UI language to a Context.
 *
 * Call [wrap] from every Activity's and Service's attachBaseContext()
 * to ensure string resources are resolved in the preferred locale.
 */
object LocaleHelper {
	/**
	 * Returns the user's chosen UI language code ("en", "es", "sw")
	 * or "system" to follow the device locale.
	 */
	fun getLanguage(context: Context): String {
		val repo = org.continuouspath.justtype.settings.SettingsRepository.getInstance(context)
		return repo.getString(Constants.KEY_APP_LANGUAGE, Constants.LANGUAGE_SYSTEM)
	}

	/**
	 * Wraps [base] with the user's preferred locale, returning a
	 * locale-overridden Context.  If the preference is "system",
	 * returns the base context unchanged.
	 */
	fun wrap(base: Context): Context {
		val lang = getLanguage(base)
		if (lang == Constants.LANGUAGE_SYSTEM) return base

		val locale = Locale(lang)
		val config = Configuration(base.resources.configuration)
		config.setLocale(locale)
		return base.createConfigurationContext(config)
	}
}
