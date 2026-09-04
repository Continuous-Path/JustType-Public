package org.continuouspath.justtype.langpack

import android.util.Log
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.settings.SettingsRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and caches the server manifest. The manifest is a few KB of static JSON, so a plain
 * HttpURLConnection on a background dispatcher is enough (DownloadManager is reserved for the large
 * artifacts). Cached in [SettingsRepository] for [CACHE_TTL_MS] so the Languages screen works offline.
 */
object LangpackManifestSource {
	private const val TAG = "LangpackManifestSource"
	private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
	private const val CONNECT_TIMEOUT_MS = 15_000
	private const val READ_TIMEOUT_MS = 30_000
	private const val MAX_MANIFEST_BYTES = 1_000_000

	/** The manifest URL: developer override if set, else the build-time default. */
	fun manifestUrl(repo: SettingsRepository, buildConfigUrl: String): String = repo.getString(Constants.KEY_DEV_LANGPACK_MANIFEST_URL, "").ifEmpty { buildConfigUrl }

	/** Cached manifest if present (regardless of age), or null. */
	fun cached(repo: SettingsRepository): LangpackManifest? {
		val raw = repo.getString(Constants.PREFS_KEY_LANGPACK_MANIFEST_CACHE, "")
		if (raw.isEmpty()) return null
		val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return null
		return LangpackManifest.parse(envelope.optString("json", ""))
	}

	private fun cacheAgeMs(repo: SettingsRepository): Long {
		val raw = repo.getString(Constants.PREFS_KEY_LANGPACK_MANIFEST_CACHE, "")
		if (raw.isEmpty()) return Long.MAX_VALUE
		val fetchedAt = runCatching { JSONObject(raw).optLong("fetchedAt", 0L) }.getOrNull() ?: 0L
		return System.currentTimeMillis() - fetchedAt
	}

	/**
	 * Returns a fresh-enough cached manifest, or fetches over the network (blocking — call from
	 * Dispatchers.IO). Returns null when offline with no usable cache.
	 */
	fun get(repo: SettingsRepository, buildConfigUrl: String, forceRefresh: Boolean = false): LangpackManifest? {
		if (!forceRefresh && cacheAgeMs(repo) < CACHE_TTL_MS) cached(repo)?.let { return it }
		val fetched = fetch(manifestUrl(repo, buildConfigUrl))
		if (fetched != null) {
			repo.putString(
				Constants.PREFS_KEY_LANGPACK_MANIFEST_CACHE,
				JSONObject().put("fetchedAt", System.currentTimeMillis()).put("json", fetched).toString(),
			)
			return LangpackManifest.parse(fetched)
		}
		return cached(repo)
	}

	private fun fetch(url: String): String? = runCatching {
		val conn = URL(url).openConnection() as HttpURLConnection
		try {
			conn.connectTimeout = CONNECT_TIMEOUT_MS
			conn.readTimeout = READ_TIMEOUT_MS
			conn.instanceFollowRedirects = true
			if (conn.responseCode != HttpURLConnection.HTTP_OK) {
				Log.w(TAG, "Manifest fetch HTTP ${conn.responseCode} from $url")
				return null
			}
			val body = conn.inputStream.use { it.readBytes() }
			if (body.size > MAX_MANIFEST_BYTES) {
				Log.w(TAG, "Manifest suspiciously large (${body.size} B); ignoring")
				return null
			}
			String(body, Charsets.UTF_8)
		} finally {
			conn.disconnect()
		}
	}.onFailure { Log.w(TAG, "Manifest fetch failed: ${it.message}") }.getOrNull()
}
