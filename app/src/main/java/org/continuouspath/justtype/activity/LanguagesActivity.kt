package org.continuouspath.justtype.activity

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.continuouspath.justtype.BuildConfig
import org.continuouspath.justtype.CanonicalLanguages
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LanguageRegistry
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.langpack.AssetRequest
import org.continuouspath.justtype.langpack.AssetStatus
import org.continuouspath.justtype.langpack.InstallResult
import org.continuouspath.justtype.langpack.LangpackInstaller
import org.continuouspath.justtype.langpack.LangpackManifest
import org.continuouspath.justtype.langpack.LangpackManifestSource
import org.continuouspath.justtype.langpack.LangpackStore
import org.continuouspath.justtype.langpack.LanguageAssetDownloader
import org.continuouspath.justtype.langpack.ManifestLanguage
import org.continuouspath.justtype.langpack.PendingDownload
import org.continuouspath.justtype.langpack.SystemDownloadManagerDownloader
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository

/**
 * "Languages" screen: lists every language from the server manifest (plus anything installed on the
 * device), and drives download / update / remove of per-language word DBs. Downloads ride the system
 * DownloadManager (its notification is the progress UI when the screen is closed; a polling loop updates
 * rows while it is open). See LangpackInstaller for the verify/install pipeline.
 */
class LanguagesActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	private lateinit var repo: SettingsRepository
	private lateinit var downloader: LanguageAssetDownloader
	private lateinit var container: LinearLayout
	private lateinit var statusText: TextView

	private var manifest: LangpackManifest? = null
	private val installingIds = mutableSetOf<String>()

	private val downloadCompleteReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) reconcilePendingDownloads()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_languages)
		repo = SettingsRepository.getInstance(this)
		downloader = SystemDownloadManagerDownloader(this)
		container = findViewById(R.id.languagesContainer)
		statusText = findViewById(R.id.languagesStatusText)
		findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

		LanguageRegistry.ensureDefaults(repo)
		manifest = LangpackManifestSource.cached(repo)
		renderRows()
		refreshManifest()
	}

	override fun onResume() {
		super.onResume()
		ContextCompat.registerReceiver(
			this,
			downloadCompleteReceiver,
			IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
			ContextCompat.RECEIVER_EXPORTED,
		)
		reconcilePendingDownloads()
		startProgressPolling()
	}

	override fun onPause() {
		super.onPause()
		unregisterReceiver(downloadCompleteReceiver)
	}

	// ── Manifest ────────────────────────────────────────────────────────────

	private fun refreshManifest() {
		if (!isOnline()) {
			showStatus(getString(R.string.languages_offline))
			return
		}
		lifecycleScope.launch(Dispatchers.IO) {
			val fresh = LangpackManifestSource.get(repo, BuildConfig.LANGPACK_MANIFEST_URL)
			withContext(Dispatchers.Main) {
				when {
					fresh == null -> showStatus(getString(R.string.languages_fetch_failed))
					!fresh.isSupported -> showStatus(getString(R.string.languages_update_app))
					else -> {
						manifest = fresh
						hideStatus()
						renderRows()
					}
				}
			}
		}
	}

	// ── Row rendering ───────────────────────────────────────────────────────

	private data class Row(val id: String, val endonym: String, val manifestLang: ManifestLanguage?)

	private fun buildRows(): List<Row> {
		val installed = LanguageRegistry.load(repo).filter { it.present }.map { it.name }.toSet()
		val manifestLangs = manifest?.languages.orEmpty().associateBy { it.id }
		val ids = linkedSetOf<String>()
		ids.add(Constants.TYPING_LANGUAGE_ENGLISH)
		ids.addAll(installed)
		ids.addAll(manifestLangs.keys)
		return ids.map { id ->
			Row(id, manifestLangs[id]?.endonym ?: CanonicalLanguages.endonymFor(id), manifestLangs[id])
		}
	}

	private fun renderRows() {
		container.removeAllViews()
		val registry = LanguageRegistry.load(repo).associateBy { it.name }
		val pending = LangpackStore.pendingDownloads(repo)
		buildRows().forEach { row ->
			val view = LayoutInflater.from(this).inflate(R.layout.row_language, container, false)
			val nameView = view.findViewById<TextView>(R.id.languageName)
			val stateView = view.findViewById<TextView>(R.id.languageState)
			val actionButton = view.findViewById<Button>(R.id.languageAction)
			val secondaryButton = view.findViewById<Button>(R.id.languageSecondaryAction)
			nameView.text = row.endonym
			secondaryButton.visibility = View.GONE

			val entry = registry[row.id]
			val isBundled = row.id == Constants.TYPING_LANGUAGE_ENGLISH
			val isPresent = isBundled || (entry?.present == true)
			val inFlight = pending[row.id]
			val updateAvailable = isPresent &&
				!isBundled &&
				row.manifestLang != null &&
				row.manifestLang.db.version > (entry?.dbVersion ?: 0)

			when {
				row.id in installingIds -> {
					stateView.text = getString(R.string.languages_state_installing)
					actionButton.visibility = View.GONE
				}
				inFlight != null -> {
					stateView.text = getString(R.string.languages_state_downloading)
					actionButton.text = getString(R.string.languages_action_cancel)
					actionButton.setOnClickListener { cancelDownload(inFlight) }
					view.tag = inFlight // progress poller updates this row
				}
				isBundled -> {
					stateView.text = getString(R.string.languages_state_bundled)
					actionButton.visibility = View.GONE
				}
				isPresent -> {
					stateView.text = getString(R.string.languages_state_installed)
					if (updateAvailable) {
						actionButton.text = getString(R.string.languages_action_update)
						actionButton.setOnClickListener { row.manifestLang?.let(::confirmUpdate) }
						secondaryButton.visibility = View.VISIBLE
						secondaryButton.text = getString(R.string.languages_action_remove)
						secondaryButton.setOnClickListener { confirmRemove(row.id, row.endonym) }
					} else {
						actionButton.text = getString(R.string.languages_action_remove)
						actionButton.setOnClickListener { confirmRemove(row.id, row.endonym) }
					}
				}
				row.manifestLang != null -> {
					stateView.text = getString(
						R.string.languages_state_size,
						formatBytes(row.manifestLang.db.bytes),
					)
					actionButton.text = getString(R.string.languages_action_download)
					actionButton.setOnClickListener { startDownload(row.manifestLang) }
				}
				else -> {
					// Installed once but no longer advertised, and not present: nothing to offer.
					stateView.text = getString(R.string.languages_state_unavailable)
					actionButton.visibility = View.GONE
				}
			}
			container.addView(view)
		}
	}

	// ── Download / install ──────────────────────────────────────────────────

	private fun startDownload(lang: ManifestLanguage) {
		if (!isOnline()) {
			Toast.makeText(this, R.string.languages_offline, Toast.LENGTH_SHORT).show()
			return
		}
		if (!LangpackStore.hasSpaceFor(this, filesDir, lang.db.bytes, lang.db.installedBytes)) {
			Toast.makeText(this, R.string.languages_no_space, Toast.LENGTH_LONG).show()
			return
		}
		val request = AssetRequest(
			url = lang.db.url,
			title = getString(R.string.languages_download_title, lang.endonym),
			destSubPath = "${lang.id}Db.db.gz",
			bytes = lang.db.bytes,
		)
		// enqueue throws when the system Downloads provider is disabled or the request is rejected.
		val downloadId = try {
			downloader.enqueue(request)
		} catch (e: IllegalStateException) {
			Toast.makeText(this, getString(R.string.languages_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
			return
		} catch (e: IllegalArgumentException) {
			Toast.makeText(this, getString(R.string.languages_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
			return
		} catch (e: SecurityException) {
			Toast.makeText(this, getString(R.string.languages_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
			return
		}
		LangpackStore.putPending(repo, PendingDownload(lang.id, downloadId, lang.db.version, lang.db.sha256))
		renderRows()
	}

	private fun cancelDownload(pending: PendingDownload) {
		downloader.cancel(pending.downloadId)
		LangpackStore.removePending(repo, pending.languageId)
		renderRows()
	}

	private fun confirmUpdate(lang: ManifestLanguage) {
		AlertDialog.Builder(this)
			.setTitle(getString(R.string.languages_update_confirm_title, lang.endonym))
			.setMessage(getString(R.string.languages_update_confirm_message, lang.endonym))
			.setPositiveButton(R.string.languages_action_update) { _, _ -> startDownload(lang) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun confirmRemove(languageId: String, endonym: String) {
		AlertDialog.Builder(this)
			.setTitle(getString(R.string.languages_remove_confirm_title, endonym))
			.setMessage(getString(R.string.languages_remove_confirm_message, endonym))
			.setPositiveButton(R.string.languages_action_remove) { _, _ -> removeLanguage(languageId) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun removeLanguage(languageId: String) {
		// LangpackInstaller.remove re-points typing language / layout-source pin if needed.
		lifecycleScope.launch(Dispatchers.IO) {
			LangpackInstaller.remove(filesDir, languageId, repo)
			withContext(Dispatchers.Main) {
				SettingsRegistry.reinitialize(this@LanguagesActivity)
				renderRows()
			}
		}
	}

	/** Checks every persisted pending download; installs finished ones, surfaces failed ones. */
	private fun reconcilePendingDownloads() {
		LangpackStore.pendingDownloads(repo).values.forEach { pending ->
			when (val status = downloader.status(pending.downloadId)) {
				is AssetStatus.Success -> installDownloaded(pending, status)
				is AssetStatus.Failed -> {
					LangpackStore.removePending(repo, pending.languageId)
					showStatus(getString(R.string.languages_download_failed, status.reason))
					renderRows()
				}
				is AssetStatus.Unknown -> {
					// Cleared by the system (or cancelled outside the app): forget it.
					LangpackStore.removePending(repo, pending.languageId)
					renderRows()
				}
				else -> Unit // still pending/running
			}
		}
	}

	private fun installDownloaded(pending: PendingDownload, status: AssetStatus.Success) {
		if (!installingIds.add(pending.languageId)) return // already installing
		renderRows()
		lifecycleScope.launch(Dispatchers.IO) {
			val result = LangpackInstaller.install(
				filesDir = filesDir,
				downloadedGz = status.file,
				languageId = pending.languageId,
				expectedSha256 = pending.sha256,
				version = pending.version,
				repo = repo,
			)
			if (result !is InstallResult.Installed) LangpackStore.removePending(repo, pending.languageId)
			withContext(Dispatchers.Main) {
				installingIds.remove(pending.languageId)
				when (result) {
					is InstallResult.Installed -> {
						SettingsRegistry.reinitialize(this@LanguagesActivity)
						renderRows()
						Toast.makeText(
							this@LanguagesActivity,
							getString(R.string.languages_installed_toast, CanonicalLanguages.endonymFor(result.languageId)),
							Toast.LENGTH_SHORT,
						).show()
						VoicePickerLanguageInstalledHook(this@LanguagesActivity, repo)
							.onLanguageInstalled(result.languageId)
					}
					is InstallResult.VerifyFailed -> showInstallError(R.string.languages_verify_failed)
					is InstallResult.CorruptDb -> showInstallError(R.string.languages_verify_failed)
					is InstallResult.IoError -> showInstallError(R.string.languages_install_io_error)
				}
			}
		}
	}

	private fun showInstallError(messageRes: Int) {
		renderRows()
		Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
	}

	// ── Progress polling (only while this screen is foregrounded) ───────────

	private fun startProgressPolling() {
		lifecycleScope.launch {
			while (isActive && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
				val pending = LangpackStore.pendingDownloads(repo)
				if (pending.isNotEmpty()) updateProgressRows(pending)
				delay(1000)
			}
		}
	}

	private fun updateProgressRows(pending: Map<String, PendingDownload>) {
		val rows = (0 until container.childCount)
			.map { container.getChildAt(it) }
			.mapNotNull { view -> (view.tag as? PendingDownload)?.let { view to it } }
		rows.forEach { (view, tagged) ->
			val current = pending[tagged.languageId] ?: return@forEach
			val status = downloader.status(current.downloadId)
			if (status is AssetStatus.Running && status.totalBytes > 0) {
				val pct = (status.downloadedBytes * 100 / status.totalBytes).toInt()
				view.findViewById<TextView>(R.id.languageState).text =
					getString(R.string.languages_state_downloading_pct, pct)
			}
		}
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	private fun isOnline(): Boolean {
		val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
		val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
		return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
	}

	private fun showStatus(message: String) {
		statusText.text = message
		statusText.visibility = View.VISIBLE
	}

	private fun hideStatus() {
		statusText.visibility = View.GONE
	}

	private fun formatBytes(bytes: Long): String = when {
		bytes < 0 -> ""
		bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
		else -> "%d KB".format(bytes / 1000)
	}
}
