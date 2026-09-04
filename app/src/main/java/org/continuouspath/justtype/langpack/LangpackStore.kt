package org.continuouspath.justtype.langpack

import android.content.Context
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.settings.SettingsRepository
import org.json.JSONObject
import java.io.File

/** A download in flight (or finished but not yet installed), persisted so it survives process death. */
data class PendingDownload(
	val languageId: String,
	val downloadId: Long,
	val version: Int,
	val sha256: String,
)

/**
 * Paths, disk-space preflight, and pending-download persistence for language packs.
 *
 * Layout: downloaded artifacts stage in the app-specific external dir (DownloadManager requirement);
 * verified pristine DBs live in internal `filesDir/langpacks/{Id}Db.db` (the per-language equivalent of a
 * bundled asset, so resetToDefaults works without re-downloading); the working copy is the usual
 * `filesDir/{Id}DbActive.db`.
 */
object LangpackStore {
	private const val LANGPACKS_DIR = "langpacks"

	/** Free-space headroom beyond the computed need, so we never fill the disk to the brim. */
	private const val HEADROOM_BYTES = 32L * 1024 * 1024

	fun langpacksDir(filesDir: File): File = File(filesDir, LANGPACKS_DIR)

	/** Verified pristine downloaded DB (per-language "asset"). */
	fun installedPackFile(filesDir: File, languageId: String): File = File(langpacksDir(filesDir), "${languageId}Db.db")

	/** In-progress decompression target, promoted atomically on success. */
	fun partFile(filesDir: File, languageId: String): File = File(langpacksDir(filesDir), "${languageId}Db.db.part")

	fun stagingDir(context: Context): File = File(context.getExternalFilesDir(null), SystemDownloadManagerDownloader.STAGING_SUBDIR)

	/**
	 * True if there is room to download ([downloadBytes] on the staging volume) and install
	 * ([installedBytes] × 2 on the internal volume: pristine + active copies).
	 */
	fun hasSpaceFor(context: Context, filesDir: File, downloadBytes: Long, installedBytes: Long): Boolean {
		val stagingFree = (context.getExternalFilesDir(null) ?: filesDir).usableSpace
		val internalFree = filesDir.usableSpace
		val needStaging = downloadBytes.coerceAtLeast(0) + HEADROOM_BYTES
		val needInternal = installedBytes.coerceAtLeast(0) * 2 + HEADROOM_BYTES
		return stagingFree >= needStaging && internalFree >= needInternal
	}

	// ── Pending-download persistence ────────────────────────────────────────

	fun pendingDownloads(repo: SettingsRepository): Map<String, PendingDownload> {
		val raw = repo.getString(Constants.PREFS_KEY_LANGPACK_PENDING, "")
		if (raw.isEmpty()) return emptyMap()
		val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
		val out = mutableMapOf<String, PendingDownload>()
		root.keys().forEach { id ->
			val o = root.optJSONObject(id) ?: return@forEach
			out[id] = PendingDownload(
				languageId = id,
				downloadId = o.optLong("downloadId", -1L),
				version = o.optInt("version", 1),
				sha256 = o.optString("sha256", ""),
			)
		}
		return out
	}

	fun putPending(repo: SettingsRepository, pending: PendingDownload) {
		val all = pendingDownloads(repo).toMutableMap()
		all[pending.languageId] = pending
		savePending(repo, all)
	}

	fun removePending(repo: SettingsRepository, languageId: String) {
		val all = pendingDownloads(repo).toMutableMap()
		if (all.remove(languageId) != null) savePending(repo, all)
	}

	private fun savePending(repo: SettingsRepository, all: Map<String, PendingDownload>) {
		val root = JSONObject()
		all.values.forEach {
			root.put(
				it.languageId,
				JSONObject()
					.put("downloadId", it.downloadId)
					.put("version", it.version)
					.put("sha256", it.sha256),
			)
		}
		repo.putString(Constants.PREFS_KEY_LANGPACK_PENDING, root.toString())
	}
}
