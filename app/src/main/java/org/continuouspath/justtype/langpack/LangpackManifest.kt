package org.continuouspath.justtype.langpack

import org.json.JSONObject

/** Downloadable word-DB artifact for one language. [sha256] is over the artifact exactly as downloaded (.gz). */
data class ManifestDb(
	val url: String,
	val bytes: Long,
	val installedBytes: Long,
	val sha256: String,
	val version: Int,
)

/** One language advertised by the server manifest. [id]/[endonym]/[localeCode] follow CanonicalLanguages. */
data class ManifestLanguage(
	val id: String,
	val endonym: String,
	val localeCode: String,
	val db: ManifestDb,
)

/**
 * Parsed server manifest (see docs/langpacks.md for the published schema). Unknown JSON keys are ignored
 * so future additions (e.g. an `llm` block per language) don't break older clients; a `formatVersion`
 * NEWER than [KNOWN_FORMAT_VERSION] must be rejected by callers via [isSupported].
 */
data class LangpackManifest(
	val formatVersion: Int,
	val minAppVersionCode: Int,
	val languages: List<ManifestLanguage>,
) {
	val isSupported: Boolean get() = formatVersion <= KNOWN_FORMAT_VERSION

	companion object {
		const val KNOWN_FORMAT_VERSION = 1

		/** Parse manifest JSON. Returns null if the JSON is malformed or required fields are missing. */
		fun parse(json: String): LangpackManifest? = runCatching {
			val root = JSONObject(json)
			val arr = root.optJSONArray("languages") ?: return null
			val languages = (0 until arr.length()).mapNotNull { i -> parseLanguage(arr.optJSONObject(i)) }
			LangpackManifest(
				formatVersion = root.optInt("formatVersion", -1).also { if (it < 1) return null },
				minAppVersionCode = root.optInt("minAppVersionCode", 1),
				languages = languages,
			)
		}.getOrNull()

		/** One manifest language, or null if its required fields (id, db.url, db.sha256) are missing. */
		private fun parseLanguage(lang: JSONObject?): ManifestLanguage? {
			if (lang == null) return null
			val db = lang.optJSONObject("db") ?: return null
			val id = lang.optString("id", "")
			val url = db.optString("url", "")
			val sha = db.optString("sha256", "")
			if (id.isEmpty() || url.isEmpty() || sha.isEmpty()) return null
			return ManifestLanguage(
				id = id,
				endonym = lang.optString("endonym", id),
				localeCode = lang.optString("localeCode", ""),
				db = ManifestDb(
					url = url,
					bytes = db.optLong("bytes", -1L),
					installedBytes = db.optLong("installedBytes", -1L),
					sha256 = sha.lowercase(),
					version = db.optInt("version", 1),
				),
			)
		}
	}
}
