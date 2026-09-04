package org.continuouspath.justtype.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.continuouspath.justtype.BackupManager
import org.continuouspath.justtype.Constants.ACTION_DATA_RESTORED
import org.continuouspath.justtype.Constants.KEY_BACKUP_LAST_TS
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository

class BackupRestoreActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	companion object {
		const val EXTRA_PROMPT_RESTORE = "prompt_restore"
	}

	private lateinit var chooseFolderButton: Button
	private lateinit var backupNowButton: Button
	private lateinit var restoreNowButton: Button
	private lateinit var backupFolderStatus: TextView
	private lateinit var backupLastRunText: TextView
	private lateinit var backupStatusText: TextView

	private val pickFolderLauncher =
		registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
			if (uri == null) return@registerForActivityResult
			val flags =
				Intent.FLAG_GRANT_READ_URI_PERMISSION or
					Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			contentResolver.takePersistableUriPermission(uri, flags)
			val repo = SettingsRepository.get()
			BackupManager.setBackupTreeUri(repo, uri)
			updateUi()
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_backup_restore)

		findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

		chooseFolderButton = findViewById(R.id.chooseBackupFolderButton)
		backupNowButton = findViewById(R.id.backupNowButton)
		restoreNowButton = findViewById(R.id.restoreNowButton)
		backupFolderStatus = findViewById(R.id.backupFolderStatus)
		backupLastRunText = findViewById(R.id.backupLastRunText)
		backupStatusText = findViewById(R.id.backupStatusText)

		chooseFolderButton.setOnClickListener { pickFolderLauncher.launch(null) }
		backupNowButton.setOnClickListener { runBackup() }
		restoreNowButton.setOnClickListener { confirmRestore() }

		updateUi()
		if (intent.getBooleanExtra(EXTRA_PROMPT_RESTORE, false)) {
			maybePromptRestore()
		}

		// Phase 3B: attach INFO PROMPT icons. The backup_heading appears twice
		// (once in the toolbar, once in the page body); the helper finds the
		// first occurrence — that lands on the toolbar title which is fine for
		// a page-level overview prompt. The advanced-mode toggle and the
		// per-button restore prompt come in Phase 3E.
		val root: ViewGroup = findViewById(android.R.id.content)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.backup_heading,
			R.string.info_prompt_page_backup_simple,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.backup_restore_now,
			R.string.info_prompt_br_restore_simple,
		)
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}

	private fun updateUi() {
		val repo = SettingsRepository.get()
		val treeUri = BackupManager.getBackupTreeUri(repo)
		backupFolderStatus.text = treeUri?.toString() ?: getString(R.string.no_folder_selected)

		val manifestTs = treeUri?.let { BackupManager.readManifest(this, it)?.timestamp } ?: 0L
		val prefTs = repo.getLong(KEY_BACKUP_LAST_TS, 0L)
		val lastTs =
			if (manifestTs > 0L) {
				if (prefTs != manifestTs) {
					repo.putLong(KEY_BACKUP_LAST_TS, manifestTs)
				}
				manifestTs
			} else {
				prefTs
			}
		backupLastRunText.text =
			if (lastTs > 0L) {
				val formatted = DateFormat.format("yyyy-MM-dd h:mm a", lastTs)
				getString(R.string.backup_last_run_format, formatted)
			} else {
				getString(R.string.backup_last_run_none)
			}

		val hasFolder = treeUri != null
		backupNowButton.isEnabled = hasFolder

		var status = ""
		var restoreEnabled = false
		if (treeUri != null) {
			val manifest = BackupManager.readManifest(this, treeUri)
			val compatible = manifest != null && BackupManager.hasCompatibleBackup(this, treeUri)
			restoreEnabled = compatible
			status =
				when {
					manifest == null -> getString(R.string.backup_status_no_backup)
					compatible -> ""
					else -> getString(R.string.backup_status_incompatible)
				}
		}
		backupStatusText.text = status
		restoreNowButton.isEnabled = restoreEnabled
	}

	private fun runBackup() {
		val repo = SettingsRepository.get()
		val treeUri = BackupManager.getBackupTreeUri(repo)
		if (treeUri == null) {
			Toast.makeText(this, getString(R.string.toast_choose_backup_folder), Toast.LENGTH_SHORT).show()
			return
		}
		backupNowButton.isEnabled = false
		lifecycleScope.launch(Dispatchers.IO) {
			val ok = BackupManager.writeSnapshot(this@BackupRestoreActivity, treeUri)
			withContext(Dispatchers.Main) {
				backupNowButton.isEnabled = true
				if (ok) {
					Toast.makeText(this@BackupRestoreActivity, getString(R.string.toast_backup_completed), Toast.LENGTH_SHORT).show()
				} else {
					Toast.makeText(this@BackupRestoreActivity, getString(R.string.toast_backup_failed), Toast.LENGTH_SHORT).show()
				}
				updateUi()
			}
		}
	}

	private fun confirmRestore() {
		val repo = SettingsRepository.get()
		val treeUri = BackupManager.getBackupTreeUri(repo)
		if (treeUri == null) {
			Toast.makeText(this, getString(R.string.toast_choose_backup_folder), Toast.LENGTH_SHORT).show()
			return
		}
		AlertDialog
			.Builder(this)
			.setTitle(getString(R.string.dialog_restore_title))
			.setMessage(getString(R.string.dialog_restore_message))
			.setPositiveButton(getString(R.string.dialog_restore_button)) { _, _ -> runRestore(treeUri) }
			.setNegativeButton(getString(R.string.label_cancel), null)
			.show()
	}

	private fun runRestore(treeUri: Uri) {
		restoreNowButton.isEnabled = false
		lifecycleScope.launch(Dispatchers.IO) {
			val ok = BackupManager.restoreSnapshot(this@BackupRestoreActivity, treeUri)
			withContext(Dispatchers.Main) {
				restoreNowButton.isEnabled = true
				if (ok) {
					Toast.makeText(this@BackupRestoreActivity, getString(R.string.toast_restore_completed), Toast.LENGTH_SHORT).show()
					sendBroadcast(Intent(ACTION_DATA_RESTORED))
				} else {
					Toast.makeText(this@BackupRestoreActivity, getString(R.string.toast_restore_failed), Toast.LENGTH_SHORT).show()
				}
				updateUi()
			}
		}
	}

	private fun maybePromptRestore() {
		val repo = SettingsRepository.get()
		val treeUri = BackupManager.getBackupTreeUri(repo) ?: return
		if (!BackupManager.hasCompatibleBackup(this, treeUri)) return
		AlertDialog
			.Builder(this)
			.setTitle(getString(R.string.dialog_restore_previous_title))
			.setMessage(getString(R.string.dialog_restore_previous_message))
			.setPositiveButton(getString(R.string.dialog_restore_button)) { _, _ -> runRestore(treeUri) }
			.setNegativeButton(getString(R.string.dialog_later_button), null)
			.show()
	}
}
