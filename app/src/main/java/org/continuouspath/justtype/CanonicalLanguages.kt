package org.continuouspath.justtype

/**
 * A language JustType can load.
 *
 * Naming principle: a language is referred to by its own name for itself (the [endonym]) everywhere the
 * user sees it. The [id] is that endonym folded to ASCII (accents stripped, spaces removed) so it stays
 * portable as a filename and persistence key across filesystems and the Android asset pipeline — e.g.
 * endonym "Español" → id "Espanol", "Türkçe" → id "Turkce", "Tiếng Việt" → id "TiengViet".
 *
 * - [id]: ASCII-safe identifier. Used for DB files (`{id}Db.db` asset → `{id}DbActive.db`), for
 *   KEY_TYPING_LANGUAGE, and as the [LanguageRegistry] entry key. English's id is "English".
 * - [endonym]: the display name shown to the user (the language's name for itself).
 * - [localeCode]: ISO 639-1 code.
 */
data class CanonicalLanguage(val id: String, val endonym: String, val localeCode: String)

/**
 * Canonical language table so manually-created and official databases use consistent naming. Extend as
 * databases are produced. Endonyms are intentionally NOT translated (they are already in their own
 * language); the ASCII ids are what the filesystem and prefs see.
 */
object CanonicalLanguages {
	val ALL: List<CanonicalLanguage> = listOf(
		CanonicalLanguage("English", "English", "en"),
		CanonicalLanguage("Espanol", "Español", "es"),
		CanonicalLanguage("Francais", "Français", "fr"),
		CanonicalLanguage("Deutsch", "Deutsch", "de"),
		CanonicalLanguage("Portugues", "Português", "pt"),
		CanonicalLanguage("Italiano", "Italiano", "it"),
		CanonicalLanguage("Nederlands", "Nederlands", "nl"),
		CanonicalLanguage("Polski", "Polski", "pl"),
		CanonicalLanguage("Turkce", "Türkçe", "tr"),
		CanonicalLanguage("Azerbaycan", "Azərbaycan", "az"),
		CanonicalLanguage("TiengViet", "Tiếng Việt", "vi"),
	)

	fun byId(id: String): CanonicalLanguage? = ALL.firstOrNull { it.id == id }

	fun byEndonym(endonym: String): CanonicalLanguage? = ALL.firstOrNull { it.endonym == endonym }

	fun byLocale(code: String): CanonicalLanguage? = ALL.firstOrNull { it.localeCode == code }

	/** Display name for a stored language id, falling back to the id itself for unknown/custom languages. */
	fun endonymFor(id: String): String = byId(id)?.endonym ?: id
}
