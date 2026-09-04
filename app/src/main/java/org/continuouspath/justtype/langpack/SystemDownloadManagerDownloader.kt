package org.continuouspath.justtype.langpack

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * [LanguageAssetDownloader] over Android's system DownloadManager: zero dependencies, and downloads
 * resume/retry across process death and flaky networks — the right behavior for cheap devices on poor
 * connections. Constraint: DownloadManager cannot write to internal filesDir, so artifacts land in the
 * app-specific external dir ([stagingDir]); LangpackInstaller verifies and moves them to internal storage.
 */
class SystemDownloadManagerDownloader(private val context: Context) : LanguageAssetDownloader {

	private val dm: DownloadManager?
		get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

	override fun enqueue(request: AssetRequest): Long {
		val manager = dm ?: error("DownloadManager unavailable")
		val dmRequest = DownloadManager.Request(Uri.parse(request.url))
			.setTitle(request.title)
			.setDestinationInExternalFilesDir(context, null, "$STAGING_SUBDIR/${request.destSubPath}")
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
			.setAllowedOverMetered(true)
			.setAllowedOverRoaming(false)
		return manager.enqueue(dmRequest)
	}

	override fun status(downloadId: Long): AssetStatus {
		val manager = dm ?: return AssetStatus.Failed("DownloadManager unavailable")
		manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
			if (cursor == null || !cursor.moveToFirst()) return AssetStatus.Unknown
			fun col(name: String) = cursor.getColumnIndex(name)
			return when (cursor.getInt(col(DownloadManager.COLUMN_STATUS))) {
				DownloadManager.STATUS_PENDING -> AssetStatus.Pending
				DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> AssetStatus.Running(
					downloadedBytes = cursor.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
					totalBytes = cursor.getLong(col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
				)
				DownloadManager.STATUS_SUCCESSFUL -> {
					val localUri = cursor.getString(col(DownloadManager.COLUMN_LOCAL_URI))
					val file = localUri?.let { runCatching { Uri.parse(it).path?.let(::File) }.getOrNull() }
					if (file != null && file.exists()) {
						AssetStatus.Success(file)
					} else {
						AssetStatus.Failed("Downloaded file missing")
					}
				}
				DownloadManager.STATUS_FAILED ->
					AssetStatus.Failed(reasonText(cursor.getInt(col(DownloadManager.COLUMN_REASON))))
				else -> AssetStatus.Unknown
			}
		}
	}

	override fun cancel(downloadId: Long) {
		dm?.remove(downloadId)
	}

	private fun reasonText(reason: Int): String = when (reason) {
		DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
		DownloadManager.ERROR_CANNOT_RESUME -> "Download could not be resumed"
		DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage unavailable"
		DownloadManager.ERROR_FILE_ERROR -> "File error"
		DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Network error"
		DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
		else -> "Download failed (HTTP $reason)"
	}

	companion object {
		const val STAGING_SUBDIR = "langpack-downloads"
	}
}
