package org.continuouspath.justtype.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import org.continuouspath.justtype.R
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles the rolling debug-log files into a single .zip in cache storage
 * and hands it to Android's share-sheet via FileProvider, so the user can
 * send the bundle by email, save to Drive, etc.
 *
 * Used by the Submit Feedback button on the main Settings page. Works even
 * for end users with no developer tooling — the entry point is a single tap.
 */
object DebugLogShareHelper {

	private const val AUTHORITY = "org.continuouspath.justtype.fileprovider"
	private const val SHARE_DIR = "share"

	/**
	 * Email a crash report to the feedback address. Bundles ONLY the `ExceptionLog_*`
	 * files (stack traces — content-safe) plus a metadata note; NEVER `DebugLog_*`,
	 * which can contain user-typed text when content categories are enabled. The
	 * recipient is pre-filled so testers don't need the address; [Intent.createChooser]
	 * keeps it usable if no mail app is installed. Returns false (with a Toast) if there
	 * is no crash log to send. Safe to call from any UI thread.
	 */
	fun emailCrashReport(context: Context): Boolean {
		val logsDir = DebugLogger.getLogsDirectory()
		val crashLogs = logsDir?.listFiles { f ->
			f.isFile && f.name.startsWith("ExceptionLog_") && f.name.endsWith(".log")
		}?.sortedBy { it.name }.orEmpty()

		if (crashLogs.isEmpty()) {
			Toast.makeText(context, R.string.feedback_no_logs_available, Toast.LENGTH_LONG).show()
			return false
		}

		val zipFile = runCatching { createZip(context, crashLogs, "justtype-crash") }.getOrNull()
		val uri = zipFile?.let {
			runCatching { FileProvider.getUriForFile(context, AUTHORITY, it) }.getOrNull()
		}
		if (uri == null) {
			Toast.makeText(context, R.string.feedback_share_failed, Toast.LENGTH_LONG).show()
			return false
		}

		val emailIntent = Intent(Intent.ACTION_SEND).apply {
			type = "application/zip"
			putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.feedback_email)))
			putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedback_crash_subject))
			putExtra(Intent.EXTRA_TEXT, context.getString(R.string.feedback_crash_body))
			putExtra(Intent.EXTRA_STREAM, uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		// If a single email app is the mailto: handler, open it directly (skips the share chooser
		// while keeping the attachment — mailto: itself can't carry one). Otherwise fall back to the
		// chooser so testers with a different/multiple mail apps still work.
		val emailPackage = defaultEmailPackage(context)
		val launch = if (emailPackage != null) {
			emailIntent.setPackage(emailPackage).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
		} else {
			Intent.createChooser(emailIntent, context.getString(R.string.feedback_crash_chooser_title))
				.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
		}
		return runCatching {
			context.startActivity(launch)
			true
		}.getOrElse {
			// The resolved package couldn't actually take the SEND+attachment — retry via chooser.
			runCatching {
				context.startActivity(
					Intent.createChooser(emailIntent, context.getString(R.string.feedback_crash_chooser_title))
						.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
				)
				true
			}.getOrDefault(false)
		}
	}

	/**
	 * The package of the default `mailto:` handler if it's a concrete email app (not the system
	 * resolver/chooser), else null. Needs the `<queries>` mailto entry in the manifest for
	 * package visibility on API 30+.
	 */
	private fun defaultEmailPackage(context: Context): String? {
		val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
		val resolved = context.packageManager.resolveActivity(mailto, 0)?.activityInfo?.packageName
		return resolved?.takeIf { it != "android" && !it.contains("resolver") }
	}

	/**
	 * Prepare and launch the share intent. If no log files exist, shows
	 * a Toast and returns. If zipping fails, shows a Toast and returns.
	 *
	 * Safe to call from any UI thread (Activity / Fragment context).
	 */
	fun shareLogs(context: Context) {
		val logsDir = DebugLogger.getLogsDirectory()
		// Bundle both file families: DebugLog_YYYY-MM-DD.log (tier 1 + 2
		// development chatter) AND ExceptionLog_YYYY-MM-DD.log (all tiers,
		// crash forensics). Either or both may be empty depending on the
		// build tier and what the device has experienced.
		val logFiles = logsDir?.listFiles { f ->
			f.isFile &&
				f.name.endsWith(".log") &&
				(
					f.name.startsWith("DebugLog_") ||
						f.name.startsWith("ExceptionLog_")
					)
		}?.sortedBy { it.name }.orEmpty()

		// Select-behavior counters ride every log share (content-safe:
		// state/outcome weights only) — the beta-tester distribution channel
		// for mechanism A's thresholds (sls.md staging replan).
		val selStats = SelStatsExport.dumpTsv(context)

		if (logFiles.isEmpty() && selStats == null) {
			Toast.makeText(context, R.string.feedback_no_logs_available, Toast.LENGTH_LONG).show()
			return
		}

		val textEntries = selStats?.let { mapOf(SelStatsExport.FILE_NAME to it) }.orEmpty()
		val zipFile = runCatching { createZip(context, logFiles, "justtype-debug", textEntries) }.getOrNull()
		if (zipFile == null) {
			Toast.makeText(context, R.string.feedback_share_failed, Toast.LENGTH_LONG).show()
			return
		}

		val uri: Uri = runCatching {
			FileProvider.getUriForFile(context, AUTHORITY, zipFile)
		}.getOrNull() ?: run {
			Toast.makeText(context, R.string.feedback_share_failed, Toast.LENGTH_LONG).show()
			return
		}

		val sendIntent = Intent(Intent.ACTION_SEND).apply {
			type = "application/zip"
			putExtra(Intent.EXTRA_STREAM, uri)
			putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedback_subject_default))
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		val chooser = Intent.createChooser(sendIntent, context.getString(R.string.feedback_share_chooser_title))
			.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
		context.startActivity(chooser)
	}

	/**
	 * Create the zip under cacheDir/share/, overwriting any prior file.
	 * Cache subdirectory matches the FileProvider's <cache-path> entry.
	 */
	private fun createZip(
		context: Context,
		logFiles: List<File>,
		namePrefix: String,
		textEntries: Map<String, String> = emptyMap(),
	): File {
		val shareDir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
		// Wipe stale zips from previous shares — there should only ever be
		// one current bundle at a time.
		shareDir.listFiles()?.forEach { runCatching { it.delete() } }

		val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
		val zipFile = File(shareDir, "$namePrefix-$stamp.zip")

		ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
			for (logFile in logFiles) {
				zos.putNextEntry(ZipEntry(logFile.name))
				FileInputStream(logFile).use { it.copyTo(zos) }
				zos.closeEntry()
			}
			for ((name, content) in textEntries) {
				zos.putNextEntry(ZipEntry(name))
				zos.write(content.toByteArray(Charsets.UTF_8))
				zos.closeEntry()
			}
		}
		return zipFile
	}
}
