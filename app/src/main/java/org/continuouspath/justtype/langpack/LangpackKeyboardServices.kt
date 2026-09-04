package org.continuouspath.justtype.langpack

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.continuouspath.justtype.BuildConfig
import org.continuouspath.justtype.CanonicalLanguages
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LanguageRegistry
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository

/** One row of the language catalog, as shown by either surface. */
data class CatalogEntry(
	val id: String,
	val endonym: String,
	val state: State,
	/** Compressed download size in bytes; -1 when unknown/not applicable. */
	val downloadBytes: Long = -1L,
	/** Download progress 0–100 while [State.DOWNLOADING]; -1 when indeterminate. */
	val progressPct: Int = -1,
	internal val manifestLang: ManifestLanguage? = null,
) {
	enum class State { BUILT_IN, INSTALLED, AVAILABLE, DOWNLOADING, INSTALLING, UNAVAILABLE }
}

/**
 * Seam for the keyboard-driven language manager (LANG_MANAGE mode): catalog loading and
 * download/remove actions, injected so unit tests can fake them. All callbacks fire on main.
 */
interface LangpackKeyboardServices {
	/** Loads the current catalog (manifest + on-device state). Returns a cancel function. */
	fun loadCatalog(onResult: (List<CatalogEntry>) -> Unit): () -> Unit

	/** Starts downloading [entry] (must be [CatalogEntry.State.AVAILABLE]). */
	fun download(entry: CatalogEntry)

	/** Removes a downloaded language; [onDone] fires after registry/settings refresh. */
	fun remove(languageId: String, onDone: () -> Unit)
}

/**
 * Production [LangpackKeyboardServices] for the IME: mirrors LanguagesActivity's flows without any
 * Activity/dialog dependency. Downloads ride the system DownloadManager; finished downloads are
 * verified+installed by [loadCatalog] reconciliation (the manager page polls it while open, and
 * LanguagesActivity reconciles too, so a download finishing after the page closes still lands).
 */
class ImeLangpackServices(
	private val context: Context,
	private val scope: CoroutineScope,
	private val repo: SettingsRepository,
) : LangpackKeyboardServices {
	private val downloader = SystemDownloadManagerDownloader(context)
	private val installing = mutableSetOf<String>()

	override fun loadCatalog(onResult: (List<CatalogEntry>) -> Unit): () -> Unit {
		var cancelled = false
		scope.launch(Dispatchers.IO) {
			reconcilePendingDownloads()
			val manifest = LangpackManifestSource.get(repo, BuildConfig.LANGPACK_MANIFEST_URL)
			val entries = buildCatalog(manifest)
			withContext(Dispatchers.Main) { if (!cancelled) onResult(entries) }
		}
		return { cancelled = true }
	}

	override fun download(entry: CatalogEntry) {
		val lang = entry.manifestLang ?: return
		if (!LangpackStore.hasSpaceFor(context, context.filesDir, lang.db.bytes, lang.db.installedBytes)) return
		val request = AssetRequest(
			url = lang.db.url,
			title = lang.endonym,
			destSubPath = "${lang.id}Db.db.gz",
			bytes = lang.db.bytes,
		)
		val downloadId = runCatching { downloader.enqueue(request) }.getOrNull() ?: return
		LangpackStore.putPending(repo, PendingDownload(lang.id, downloadId, lang.db.version, lang.db.sha256))
	}

	override fun remove(languageId: String, onDone: () -> Unit) {
		scope.launch(Dispatchers.IO) {
			// LangpackInstaller.remove re-points typing language / layout-source pin if needed.
			LangpackInstaller.remove(context.filesDir, languageId, repo)
			withContext(Dispatchers.Main) {
				SettingsRegistry.reinitialize(context)
				onDone()
			}
		}
	}

	/** Installs any finished downloads; drops failed/vanished ones. Runs on the calling (IO) thread. */
	private fun reconcilePendingDownloads() {
		LangpackStore.pendingDownloads(repo).values.forEach { pending ->
			when (val status = downloader.status(pending.downloadId)) {
				is AssetStatus.Success -> installDownloaded(pending, status)
				is AssetStatus.Failed, is AssetStatus.Unknown ->
					LangpackStore.removePending(repo, pending.languageId)
				else -> Unit // still pending/running
			}
		}
	}

	private fun installDownloaded(pending: PendingDownload, status: AssetStatus.Success) {
		if (!installing.add(pending.languageId)) return
		try {
			val result = LangpackInstaller.install(
				filesDir = context.filesDir,
				downloadedGz = status.file,
				languageId = pending.languageId,
				expectedSha256 = pending.sha256,
				version = pending.version,
				repo = repo,
			)
			if (result !is InstallResult.Installed) LangpackStore.removePending(repo, pending.languageId)
			if (result is InstallResult.Installed) {
				scope.launch(Dispatchers.Main) { SettingsRegistry.reinitialize(context) }
			}
		} finally {
			installing.remove(pending.languageId)
		}
	}

	private fun buildCatalog(manifest: LangpackManifest?): List<CatalogEntry> {
		val registry = LanguageRegistry.load(repo).associateBy { it.name }
		val pending = LangpackStore.pendingDownloads(repo)
		val manifestLangs = manifest?.languages.orEmpty().associateBy { it.id }
		val ids = linkedSetOf(Constants.TYPING_LANGUAGE_ENGLISH)
		ids.addAll(registry.filter { it.value.present }.keys)
		ids.addAll(manifestLangs.keys)
		return ids.map { id ->
			val lang = manifestLangs[id]
			val endonym = lang?.endonym ?: CanonicalLanguages.endonymFor(id)
			val inFlight = pending[id]
			val present = id == Constants.TYPING_LANGUAGE_ENGLISH || registry[id]?.present == true
			when {
				id == Constants.TYPING_LANGUAGE_ENGLISH ->
					CatalogEntry(id, endonym, CatalogEntry.State.BUILT_IN)
				id in installing ->
					CatalogEntry(id, endonym, CatalogEntry.State.INSTALLING)
				inFlight != null -> {
					val pct = (downloader.status(inFlight.downloadId) as? AssetStatus.Running)
						?.takeIf { it.totalBytes > 0 }
						?.let { (it.downloadedBytes * 100 / it.totalBytes).toInt() } ?: -1
					CatalogEntry(id, endonym, CatalogEntry.State.DOWNLOADING, progressPct = pct, manifestLang = lang)
				}
				present -> CatalogEntry(id, endonym, CatalogEntry.State.INSTALLED, manifestLang = lang)
				lang != null ->
					CatalogEntry(id, endonym, CatalogEntry.State.AVAILABLE, downloadBytes = lang.db.bytes, manifestLang = lang)
				else -> CatalogEntry(id, endonym, CatalogEntry.State.UNAVAILABLE)
			}
		}
	}
}
