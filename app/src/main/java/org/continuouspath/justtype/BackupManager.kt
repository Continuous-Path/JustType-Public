package org.continuouspath.justtype

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants.KEY_BACKUP_LAST_TS
import org.continuouspath.justtype.Constants.KEY_BACKUP_PROMPTED
import org.continuouspath.justtype.Constants.KEY_BACKUP_TREE_URI
import org.continuouspath.justtype.Constants.KEY_HAS_RUN_BEFORE
import org.continuouspath.justtype.Constants.PREFS_KEY_CLASS_METADATA
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsDefaults
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getString
import org.continuouspath.justtype.utils.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object BackupManager {
	private const val DATA_FORMAT_VERSION = 1
	private const val BACKUP_DEBOUNCE_MS = 2500L

	// Database filename is derived from the typing language preference.
	// Default: "EnglishDbActive.db" — matches WordDb.activeDbName().
	private fun getDbFileName(context: Context): String {
		val repo = SettingsRepository.getInstance(context)
		val lang =
			repo.getString(Constants.KEY_TYPING_LANGUAGE)
		return WordDb.activeDbName(lang)
	}

	private const val FILE_PHRASES = "phrases.json"
	private const val FILE_CUSTOM_WORDS = "customWords.json"
	private const val FILE_PREFS = "prefs.json"
	private const val FILE_CLASS_METADATA = "class_metadata.json"
	private const val FILE_MANIFEST = "backup_manifest.json"

	@VisibleForTesting
	internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var pendingBackup: Job? = null

	private val EXCLUDED_PREF_KEYS =
		setOf(
			KEY_BACKUP_TREE_URI,
			KEY_BACKUP_LAST_TS,
			KEY_BACKUP_PROMPTED,
			KEY_HAS_RUN_BEFORE,
		)

	data class BackupInfo(
		val version: Int,
		val timestamp: Long,
	)

	fun getBackupTreeUri(repo: SettingsRepository): Uri? {
		val raw = repo.getString(KEY_BACKUP_TREE_URI, "")
		if (raw.isEmpty()) return null
		return runCatching { Uri.parse(raw) }.getOrNull()
	}

	fun setBackupTreeUri(
		repo: SettingsRepository,
		uri: Uri,
	) {
		repo.putString(KEY_BACKUP_TREE_URI, uri.toString())
	}

	fun readManifest(
		context: Context,
		treeUri: Uri,
	): BackupInfo? {
		val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
		val manifest = root.findFile(FILE_MANIFEST) ?: return null
		val text =
			context.contentResolver
				.openInputStream(manifest.uri)
				?.bufferedReader()
				?.readText() ?: return null
		val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
		return BackupInfo(
			version = obj.optInt("dataVersion", 0),
			timestamp = obj.optLong("timestamp", 0L),
		)
	}

	fun hasCompatibleBackup(
		context: Context,
		treeUri: Uri,
	): Boolean {
		val manifest = readManifest(context, treeUri) ?: return false
		return manifest.version == DATA_FORMAT_VERSION
	}

	// Debounce seam: the scheduled job writes through this so tests can count writes.
	@VisibleForTesting
	internal var snapshotWriter: (Context, Uri) -> Boolean = { ctx, uri -> writeSnapshot(ctx, uri) }

	fun scheduleBackup(context: Context) {
		val repo = SettingsRepository.getInstance(context)
		val treeUri = getBackupTreeUri(repo) ?: return
		val appContext = context.applicationContext
		pendingBackup?.cancel()
		pendingBackup = scope.launch {
			delay(BACKUP_DEBOUNCE_MS)
			snapshotWriter(appContext, treeUri)
		}
	}

	// pendingBackup is the sole launch site on `scope`, so cancelling it fully
	// drains the scope — no leaked coroutines survive into a shared JVM fork.
	@VisibleForTesting
	internal fun cancelPendingForTesting() {
		pendingBackup?.cancel()
	}

	fun writeSnapshot(
		context: Context,
		treeUri: Uri,
	): Boolean {
		android.os.Trace.beginSection("ime.backup.write")
		try {
			val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
			val repo = SettingsRepository.getInstance(context)
			val dbFileName = getDbFileName(context)
			val dbFile = File(context.filesDir, dbFileName)

			var allOk = true
			if (dbFile.exists()) {
				allOk = writeFileAtomic(context, root, dbFileName, "application/octet-stream") { output ->
					FileInputStream(dbFile).use { input -> input.copyTo(output) }
				} &&
					allOk
			}

			val phrasesFile = File(context.filesDir, FILE_PHRASES)
			allOk = writeFileAtomic(context, root, FILE_PHRASES, "application/json") { output ->
				if (phrasesFile.exists()) {
					FileInputStream(phrasesFile).use { input -> input.copyTo(output) }
				} else {
					output.write(JSONArray().toString().toByteArray())
				}
			} &&
				allOk

			WordDb.openStandalone(File(context.filesDir, "CustomDb.db")).use { customDb ->
				allOk = writeFileAtomic(context, root, FILE_CUSTOM_WORDS, "application/json") { output ->
					val words = customDb.getCustomWords()
					val arr = JSONArray()
					words.forEach { arr.put(it) }
					output.write(arr.toString().toByteArray())
				} &&
					allOk
			}

			allOk = writeFileAtomic(context, root, FILE_CLASS_METADATA, "application/json") { output ->
				val raw = repo.getString(PREFS_KEY_CLASS_METADATA, "")
				if (raw.isNotEmpty()) output.write(raw.toByteArray())
			} &&
				allOk

			allOk = writeFileAtomic(context, root, FILE_PREFS, "application/json") { output ->
				val json = serializePrefs(repo)
				output.write(json.toString().toByteArray())
			} &&
				allOk

			if (!allOk) {
				DebugLogger.log(DebugCategory.Lifecycle) {
					"[BackupManager] aborting before manifest: at least one file write failed"
				}
				return false
			}

			if (!writeManifest(context, root)) return false
			// Best-effort — the manifest's own timestamp is the source of truth for restore.
			repo.putLong(KEY_BACKUP_LAST_TS, System.currentTimeMillis())
			return true
		} finally {
			android.os.Trace.endSection()
		}
	}

	fun restoreSnapshot(
		context: Context,
		treeUri: Uri,
	): Boolean {
		val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
		val repo = SettingsRepository.getInstance(context)
		val keepUri = repo.getString(KEY_BACKUP_TREE_URI, "")
		val keepPrompted = repo.getBoolean(KEY_BACKUP_PROMPTED, false)

		val manifest = readManifest(context, treeUri) ?: return false
		if (manifest.version != DATA_FORMAT_VERSION) return false

		val restoreDbFileName = getDbFileName(context)
		root.findFile(restoreDbFileName)?.let { file ->
			val outFile = File(context.filesDir, restoreDbFileName)
			copyToFileAtomic(context, file, outFile)
		}

		root.findFile(FILE_PHRASES)?.let { file ->
			val outFile = File(context.filesDir, FILE_PHRASES)
			copyToFileAtomic(context, file, outFile)
		}

		root.findFile(FILE_CLASS_METADATA)?.let { file ->
			val raw =
				context.contentResolver
					.openInputStream(file.uri)
					?.bufferedReader()
					?.readText()
			if (raw != null) {
				repo.putString(PREFS_KEY_CLASS_METADATA, raw)
			}
		}

		root.findFile(FILE_PREFS)?.let { file ->
			val text =
				context.contentResolver
					.openInputStream(file.uri)
					?.bufferedReader()
					?.readText()
			if (text != null) {
				applyPrefsSnapshot(repo, text)
			}
		}

		// Custom words live in a standalone CustomDb.db, independent of the language DB — restore them
		// whenever the backup has them, NOT only when the language DB wasn't restored (that guard lost
		// every custom word on a normal restore, since the language DB is present in a full backup).
		root.findFile(FILE_CUSTOM_WORDS)?.let { file ->
			val text =
				context.contentResolver
					.openInputStream(file.uri)
					?.bufferedReader()
					?.readText()
			if (!text.isNullOrBlank()) {
				restoreCustomWords(context, text)
			}
		}

		SettingsDefaults.ensureAll(repo, context.applicationContext)
		ClassMetadataStore.ensureDefaults(repo)
		applyRestoreTail(repo, keepUri, keepPrompted, manifest.timestamp)
		return true
	}

	// Single batched commit() so KEY_HAS_RUN_BEFORE survives immediate process death
	// after restore; losing it would re-trigger first-run flow over the fresh restore.
	@VisibleForTesting
	internal fun applyRestoreTail(
		repo: SettingsRepository,
		keepUri: String,
		keepPrompted: Boolean,
		manifestTimestamp: Long,
	) {
		val editor = repo.edit()
		if (keepUri.isNotEmpty()) {
			editor.putString(KEY_BACKUP_TREE_URI, keepUri)
		}
		editor.putBoolean(KEY_BACKUP_PROMPTED, keepPrompted)
		if (manifestTimestamp > 0L) {
			editor.putLong(KEY_BACKUP_LAST_TS, manifestTimestamp)
		}
		editor.putBoolean(KEY_HAS_RUN_BEFORE, true)
		editor.commit()
	}

	private fun writeManifest(
		context: Context,
		root: DocumentFile,
	): Boolean {
		val obj = JSONObject()
		obj.put("dataVersion", DATA_FORMAT_VERSION)
		obj.put("timestamp", System.currentTimeMillis())
		obj.put("appVersion", BuildConfig.VERSION_NAME)
		obj.put("appBuild", BuildIdentity.oneLine())
		return writeFileAtomic(context, root, FILE_MANIFEST, "application/json") { output ->
			output.write(obj.toString().toByteArray())
		}
	}

	private fun writeFileAtomic(
		context: Context,
		root: DocumentFile,
		name: String,
		mime: String,
		writer: (OutputStream) -> Unit,
	): Boolean {
		val tmpName = "$name.tmp"
		root.findFile(tmpName)?.delete()
		val tmp = root.createFile(mime, tmpName) ?: return false
		val ok = runCatching {
			context.contentResolver.openOutputStream(tmp.uri)?.use { writer(it) }
				?: error("could not open output stream for $tmpName")
		}.isSuccess
		if (!ok) {
			tmp.delete()
			return false
		}
		root.findFile(name)?.delete()
		if (!tmp.renameTo(name)) {
			tmp.delete()
			return false
		}
		return true
	}

	// Streams source bytes through AtomicFile.writeBytes (fsync + rename). Handles both
	// the binary DB blob and JSON/text files — JSON is just UTF-8 bytes from our side.
	private fun copyToFileAtomic(
		context: Context,
		doc: DocumentFile,
		outFile: File,
	): Boolean = runCatching {
		AtomicFile.writeBytes(outFile) { output ->
			context.contentResolver.openInputStream(doc.uri)?.use { input ->
				input.copyTo(output)
			} ?: error("could not open input stream for ${doc.name}")
		}
		true
	}.getOrDefault(false)

	@VisibleForTesting
	internal fun serializePrefs(repo: SettingsRepository): JSONObject {
		val obj = JSONObject()
		repo.all.forEach { (key, value) ->
			if (EXCLUDED_PREF_KEYS.contains(key)) return@forEach
			val entry = JSONObject()
			when (value) {
				is Boolean -> {
					entry.put("type", "boolean")
					entry.put("value", value)
				}

				is Int -> {
					entry.put("type", "int")
					entry.put("value", value)
				}

				is Long -> {
					entry.put("type", "long")
					entry.put("value", value)
				}

				is Float -> {
					entry.put("type", "float")
					entry.put("value", value)
				}

				is String -> {
					entry.put("type", "string")
					entry.put("value", value)
				}

				else -> return@forEach
			}
			obj.put(key, entry)
		}
		return obj
	}

	@VisibleForTesting
	internal fun applyPrefsSnapshot(
		repo: SettingsRepository,
		raw: String,
	) {
		val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
		val editor = repo.edit()
		val keys = obj.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			if (EXCLUDED_PREF_KEYS.contains(key)) continue
			val entry = obj.optJSONObject(key) ?: continue
			val type = entry.optString("type")
			when (type) {
				"boolean" -> editor.putBoolean(key, entry.optBoolean("value"))
				"int" -> editor.putInt(key, entry.optInt("value"))
				"long" -> editor.putLong(key, entry.optLong("value"))
				"float" -> editor.putFloat(key, entry.optDouble("value").toFloat())
				"string" -> editor.putString(key, entry.optString("value"))
			}
		}
		editor.commit()
	}

	@VisibleForTesting
	internal fun restoreCustomWords(
		context: Context,
		raw: String,
	) {
		val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
		WordDb.openStandalone(File(context.filesDir, "CustomDb.db")).use { customDb ->
			for (i in 0 until arr.length()) {
				val word = arr.optString(i)?.trim()
				if (word.isNullOrEmpty()) continue
				customDb.ensureCustomWord(word)
			}
		}
	}
}
