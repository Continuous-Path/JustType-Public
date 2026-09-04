package org.continuouspath.justtype.langpack

import java.io.File

/** A single asset to download. [destSubPath] is relative to the downloader's staging directory. */
data class AssetRequest(
	val url: String,
	val title: String,
	val destSubPath: String,
	val bytes: Long,
)

sealed class AssetStatus {
	object Pending : AssetStatus()

	/** [downloadedBytes]/[totalBytes] for a determinate progress display; total may be -1 while unknown. */
	data class Running(val downloadedBytes: Long, val totalBytes: Long) : AssetStatus()

	data class Success(val file: File) : AssetStatus()

	data class Failed(val reason: String) : AssetStatus()

	/** The id is unknown to the transport (cleared by the system, cancelled, or from a stale record). */
	object Unknown : AssetStatus()
}

/**
 * Transport seam for language-asset downloads (word DBs now; asset-generic so LLM files can reuse it).
 * The default implementation rides Android's system DownloadManager; tests use a synchronous fake.
 */
interface LanguageAssetDownloader {
	/** Enqueue a download; returns an id usable with [status]/[cancel]. */
	fun enqueue(request: AssetRequest): Long

	fun status(downloadId: Long): AssetStatus

	fun cancel(downloadId: Long)
}
