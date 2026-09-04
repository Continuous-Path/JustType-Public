package org.continuouspath.justtype.langpack

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import org.continuouspath.justtype.CanonicalLanguages
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LanguageEntry
import org.continuouspath.justtype.LanguageRegistry
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

sealed class InstallResult {
	data class Installed(val languageId: String) : InstallResult()
	data class VerifyFailed(val detail: String) : InstallResult()
	data class CorruptDb(val detail: String) : InstallResult()
	data class IoError(val detail: String) : InstallResult()
}

/**
 * Verifies a downloaded language artifact and installs it. All file work; no network — fully
 * unit-testable. Flow: stream the .gz once (SHA-256 over the raw bytes while gunzipping to a .part
 * file) → digest must match the manifest → SQLite sanity-open (words/metadata tables, read
 * diacriticSet) → atomic promote to the pristine pack → copy to {Id}DbActive.db → registry upsert.
 */
object LangpackInstaller {
	private const val TAG = "LangpackInstaller"

	fun install(
		filesDir: File,
		downloadedGz: File,
		languageId: String,
		expectedSha256: String,
		version: Int,
		repo: SettingsRepository,
	): InstallResult {
		val part = LangpackStore.partFile(filesDir, languageId)
		val pack = LangpackStore.installedPackFile(filesDir, languageId)

		// 1+2: single pass — hash the compressed bytes while decompressing to .part.
		val actualSha = try {
			decompressAndHash(downloadedGz, part)
		} catch (e: IOException) {
			part.delete()
			return ioError(downloadedGz, "Decompress failed: ${e.message}")
		}
		if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
			part.delete()
			downloadedGz.delete()
			Log.w(TAG, "$languageId sha mismatch: expected $expectedSha256 got $actualSha")
			return InstallResult.VerifyFailed("Checksum mismatch")
		}

		// 3: sanity-open the decompressed DB and read its diacritic set + optional layout.
		val (diacriticSet, layoutJson) = try {
			readPackMetadata(part)
		} catch (e: SQLiteException) {
			return corruptDb(part, downloadedGz, languageId, e.message)
		} catch (e: IllegalStateException) {
			return corruptDb(part, downloadedGz, languageId, e.message)
		}

		// 4: atomic promote (same volume).
		pack.delete()
		if (!part.renameTo(pack)) {
			part.delete()
			return ioError(downloadedGz, "Could not finalize $pack")
		}

		// 5: seed the active DB from the pristine pack ONLY on a first install. If an active DB already
		// exists this is an update/re-download, and it holds the user's learned stats (useCount, case
		// counts, custom words) — overwriting it would silently wipe them. The pristine pack is still
		// refreshed above (step 4) for future fresh installs and resets. Mirrors WordDb.open's guard.
		// Skipping the copy on update also avoids rewriting a file the IME may hold open for the
		// currently-active language (a live SQLiteDatabase handle from JTUI.init).
		val active = File(filesDir, WordDb.activeDbName(languageId))
		if (!active.exists()) {
			copyPackToActive(pack, active)?.let { return ioError(downloadedGz, it) }
		} else {
			// Update path: user data stays, but the new pack's corpus and build-produced
			// metadata must follow it — else a rebuilt word list or a layout change never
			// lands on a device that already has the language. The migration preserves
			// learned stats, imported vocabularies and phrase links.
			runCatching { WordDb.migrateLanguageBuild(active, pack) }
				.onFailure { Log.w(TAG, "language-build migration failed: ${it.message}") }
			runCatching { WordDb.refreshStaticMetadata(active, pack) }
				.onFailure { Log.w(TAG, "static-metadata refresh failed: ${it.message}") }
		}

		// 6: registry — the language is now present on the device.
		val canonical = CanonicalLanguages.byId(languageId)
		val entry = LanguageEntry(
			name = languageId,
			localeCode = canonical?.localeCode ?: "",
			diacriticSet = diacriticSet,
			present = true,
			dbFileName = WordDb.activeDbName(languageId),
			dbVersion = version,
			layoutJson = layoutJson,
		)
		LanguageRegistry.ensureDefaults(repo)
		LanguageRegistry.save(repo, LanguageRegistry.upsert(LanguageRegistry.load(repo), entry))
		LangpackStore.removePending(repo, languageId)

		downloadedGz.delete()
		Log.i(TAG, "Installed $languageId v$version (${pack.length()} B)")
		return InstallResult.Installed(languageId)
	}

	/**
	 * Removes a downloaded language: active DB, pristine pack, and registry presence. Refuses for the
	 * bundled default language. Also re-points KEY_TYPING_LANGUAGE / KEY_OPTIMIZED_LAYOUT_SOURCE when
	 * they reference the removed language, so no caller can leave a setting dangling.
	 */
	fun remove(filesDir: File, languageId: String, repo: SettingsRepository): Boolean {
		if (languageId == Constants.TYPING_LANGUAGE_ENGLISH) return false
		if (repo.getString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ENGLISH) == languageId) {
			repo.putString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ENGLISH)
		}
		if (repo.getString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, Constants.LAYOUT_SOURCE_MATCH) == languageId) {
			repo.putString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, Constants.LAYOUT_SOURCE_MATCH)
		}
		File(filesDir, WordDb.activeDbName(languageId)).delete()
		LangpackStore.installedPackFile(filesDir, languageId).delete()
		LangpackStore.partFile(filesDir, languageId).delete()
		val items = LanguageRegistry.load(repo)
		val existing = items.firstOrNull { it.name == languageId } ?: return true
		LanguageRegistry.save(repo, LanguageRegistry.upsert(items, existing.copy(present = false, dbVersion = 0)))
		return true
	}

	/** Copies the pristine [pack] to [active]. Returns null on success, or an error detail on failure. */
	private fun copyPackToActive(pack: File, active: File): String? = try {
		pack.inputStream().use { input -> active.outputStream().use { output -> input.copyTo(output) } }
		null
	} catch (e: IOException) {
		active.delete()
		"Could not activate: ${e.message}"
	}

	/** Streams [downloadedGz] once: SHA-256 over the raw bytes while gunzipping into [part]. */
	private fun decompressAndHash(downloadedGz: File, part: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		part.parentFile?.mkdirs()
		downloadedGz.inputStream().use { raw ->
			GZIPInputStream(DigestInputStream(raw, digest)).use { gz ->
				part.outputStream().use { out -> gz.copyTo(out) }
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}

	/**
	 * Opens [part] read-only, asserts the words/metadata tables exist, and returns the diacritic
	 * set plus the optional per-language layout JSON.
	 */
	private fun readPackMetadata(part: File): Pair<String, String> = SQLiteDatabase.openDatabase(part.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
		val tables = mutableSetOf<String>()
		db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
			while (c.moveToNext()) tables.add(c.getString(0))
		}
		check("words" in tables && "metadata" in tables) { "missing tables (found: $tables)" }
		fun metaValue(key: String): String = db.rawQuery(
			"SELECT value FROM metadata WHERE `key` = ? LIMIT 1",
			arrayOf(key),
		).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
		metaValue("diacriticSet") to metaValue("layoutJson")
	}

	private fun corruptDb(part: File, downloadedGz: File, languageId: String, detail: String?): InstallResult {
		part.delete()
		downloadedGz.delete()
		Log.w(TAG, "$languageId failed sanity check: $detail")
		return InstallResult.CorruptDb(detail ?: "unreadable database")
	}

	private fun ioError(downloadedGz: File, detail: String): InstallResult {
		downloadedGz.delete()
		Log.w(TAG, detail)
		return InstallResult.IoError(detail)
	}
}
