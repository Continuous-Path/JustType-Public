package org.continuouspath.justtype

import android.util.Log
import org.continuouspath.justtype.hierarchy.DiacriticDerivation
import org.continuouspath.justtype.settings.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/** One language present on the device, with its corpus-derived diacritic set (the LETTER SPELL MODE filter). */
data class LanguageEntry(
	val name: String,
	val localeCode: String,
	val diacriticSet: String, // DiacriticDerivation.encode() form: sorted lowercase non-ASCII letters
	val present: Boolean, // a {name}DbActive.db exists on the device
	val dbFileName: String,
	val dbVersion: Int = 0, // installed langpack version (0 = bundled/unknown); drives "update available"
	val layoutJson: String = "", // the DB's layoutJson metadata row (per-language Optimized layout); "" = none
)

/**
 * Read-model of the languages on the device, persisted as JSON in [SettingsRepository] (mirrors
 * [ClassMetadataStore]). The source of truth for each language's diacritic set is its word DB's
 * `metadata.diacriticSet` row; this registry is a fast, DB-free copy that LETTER SPELL MODE reads on the
 * layout path (see Constants.KEY_SPELL_DIACRITIC_SCOPE).
 */
object LanguageRegistry {
	private const val TAG = "LanguageRegistry"

	/** Seed the built-in English entry if the registry is empty. */
	fun ensureDefaults(repo: SettingsRepository) {
		if (repo.getString(Constants.PREFS_KEY_LANGUAGE_REGISTRY, "").isNotEmpty()) return
		val en = Constants.TYPING_LANGUAGE_ENGLISH
		save(repo, listOf(LanguageEntry(en, CanonicalLanguages.byId(en)?.localeCode ?: "en", "", true, "${en}DbActive.db")))
	}

	fun load(repo: SettingsRepository): List<LanguageEntry> {
		val raw = repo.getString(Constants.PREFS_KEY_LANGUAGE_REGISTRY, "")
		if (raw.isEmpty()) return emptyList()
		val arr = runCatching { JSONArray(raw) }.getOrNull() ?: run {
			Log.w(TAG, "Failed to parse language registry JSON; treating as empty.")
			return emptyList()
		}
		val out = mutableListOf<LanguageEntry>()
		for (i in 0 until arr.length()) {
			val o = arr.optJSONObject(i) ?: continue
			out.add(
				LanguageEntry(
					name = o.optString("name", ""),
					localeCode = o.optString("locale", ""),
					diacriticSet = o.optString("diacritics", ""),
					present = o.optBoolean("present", false),
					dbFileName = o.optString("dbFileName", ""),
					dbVersion = o.optInt("dbVersion", 0),
					layoutJson = o.optString("layout", ""),
				),
			)
		}
		return out
	}

	fun save(repo: SettingsRepository, items: List<LanguageEntry>) {
		val arr = JSONArray()
		items.forEach {
			arr.put(
				JSONObject()
					.put("name", it.name)
					.put("locale", it.localeCode)
					.put("diacritics", it.diacriticSet)
					.put("present", it.present)
					.put("dbFileName", it.dbFileName)
					.put("dbVersion", it.dbVersion)
					.put("layout", it.layoutJson),
			)
		}
		repo.putString(Constants.PREFS_KEY_LANGUAGE_REGISTRY, arr.toString())
	}

	fun upsert(items: List<LanguageEntry>, entry: LanguageEntry): List<LanguageEntry> {
		val out = items.toMutableList()
		val i = out.indexOfFirst { it.name == entry.name }
		if (i >= 0) out[i] = entry else out.add(entry)
		return out
	}

	/** A default entry for [name], used when setters touch a language not yet registered. */
	private fun defaultEntry(name: String) = LanguageEntry(
		name = name,
		localeCode = CanonicalLanguages.byId(name)?.localeCode ?: "",
		diacriticSet = "",
		present = true,
		dbFileName = "${name}DbActive.db",
	)

	/** Replace a language's diacritic set (creating the entry if absent); other fields preserved. */
	fun setDiacriticSet(repo: SettingsRepository, name: String, set: Set<Char>) {
		val items = load(repo)
		val entry = (items.firstOrNull { it.name == name } ?: defaultEntry(name))
			.copy(diacriticSet = DiacriticDerivation.encode(set))
		save(repo, upsert(items, entry))
	}

	/** Replace a language's cached Optimized-layout JSON (creating the entry if absent). */
	fun setLayoutJson(repo: SettingsRepository, name: String, json: String) {
		val items = load(repo)
		val entry = (items.firstOrNull { it.name == name } ?: defaultEntry(name))
			.copy(layoutJson = json)
		save(repo, upsert(items, entry))
	}

	/** Union additional chars into a language's diacritic set (used when merging vocabularies / DBs). */
	fun mergeDiacriticSet(repo: SettingsRepository, name: String, addition: Set<Char>) {
		val existing = load(repo).firstOrNull { it.name == name }?.diacriticSet ?: ""
		setDiacriticSet(repo, name, DiacriticDerivation.decode(existing) + addition)
	}

	/** The language(s) currently in use. Phase 1: the single typing language. Phase 2 seam: a set. */
	fun activeLanguageNames(repo: SettingsRepository): Set<String> = setOf(repo.getString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ENGLISH))

	/**
	 * Allowed diacritic characters for [scope]: `null` = "All variants" (no filter); empty set = Off / no
	 * in-scope diacritics; otherwise the union of the relevant languages' sets.
	 */
	fun allowedCharsFor(items: List<LanguageEntry>, scope: String, active: Set<String>): Set<Char>? = when (scope) {
		Constants.DIACRITIC_SCOPE_ALL -> null
		Constants.DIACRITIC_SCOPE_OFF -> emptySet()
		Constants.DIACRITIC_SCOPE_LOADED ->
			items.filter { it.present }.flatMapTo(sortedSetOf<Char>()) { DiacriticDerivation.decode(it.diacriticSet) }
		else -> // DIACRITIC_SCOPE_CURRENT
			items.filter { it.name in active }.flatMapTo(sortedSetOf<Char>()) { DiacriticDerivation.decode(it.diacriticSet) }
	}
}
