package org.continuouspath.justtype

import android.content.Context
import android.os.Build
import org.continuouspath.justtype.Constants.KEY_CRASH_REPORT_PENDING
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_MESSAGE
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_THREAD
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_TIME
import org.continuouspath.justtype.Constants.KEY_LAST_SESSION_CRASHED
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.logging.ExceptionLogWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches uncaught exceptions, persists a recovery flag, and appends a
 * detailed report to the rolling ExceptionLog file under {filesDir}/logs/.
 *
 * Lives in all build tiers (including Tier 0 release) — even a lean
 * release build retains forensic value for severe crashes.
 *
 * File layout owned by [ExceptionLogWriter]:
 *   logs/ExceptionLog_YYYY-MM-DD.log   ← active file, ≤ 500 KB
 *   logs/ExceptionLog_YYYY-MM-DD.log   ← previous file (different date or .NNNN suffix)
 * Rotation deletes the oldest when a 3rd file would be created.
 */
class CrashHandler(
	private val context: Context,
	private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

	@Suppress("TooGenericExceptionCaught") // any throwable here must not block the default handler.
	override fun uncaughtException(
		thread: Thread,
		throwable: Throwable,
	) {
		try {
			safeLog {
				"[CrashHandler] Uncaught exception in thread '${thread.name}': ${throwable.message}"
			}
			val repo = org.continuouspath.justtype.settings.SettingsRepository.getInstance(context)
			repo.edit()
				.putBoolean(KEY_LAST_SESSION_CRASHED, true)
				.putBoolean(KEY_CRASH_REPORT_PENDING, true)
				.putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
				.putString(KEY_LAST_CRASH_MESSAGE, throwable.message ?: "Unknown error")
				.putString(KEY_LAST_CRASH_THREAD, thread.name)
				.commit()
			writeCrashReport(thread, throwable)
		} catch (e: Throwable) {
			safeLog { "[CrashHandler] crash handling failed: ${e.javaClass.simpleName}: ${e.message}" }
		}
		defaultHandler?.uncaughtException(thread, throwable)
	}

	@Suppress("TooGenericExceptionCaught") // best-effort log write; any failure falls back to safeLog.
	private fun writeCrashReport(
		thread: Thread,
		throwable: Throwable,
	) {
		try {
			val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
			val report = buildString {
				append('\n')
				append("=".repeat(60))
				append('\n')
				append("CRASH REPORT - ").append(timestamp).append('\n')
				append("=".repeat(60))
				append('\n')
				append(deviceMetadata())
				append("Thread: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
				append("Exception: ").append(throwable.javaClass.name).append('\n')
				append("Message: ").append(throwable.message).append('\n')
				append("\nStack Trace:\n")
				val sw = StringWriter()
				val pw = PrintWriter(sw)
				throwable.printStackTrace(pw)
				var cause = throwable.cause
				while (cause != null) {
					pw.append("\nCaused by: ").append(cause.javaClass.name).append(": ").append(cause.message).append('\n')
					cause.printStackTrace(pw)
					cause = cause.cause
				}
				pw.flush()
				append(sw.toString())
				append('\n')
			}
			ExceptionLogWriter.append(report)
		} catch (e: Throwable) {
			safeLog { "[CrashHandler] writeCrashReport failed: ${e.javaClass.simpleName}: ${e.message}" }
		}
	}

	/** Content-safe device/build/memory context for triage. Never includes user-typed text. */
	private fun deviceMetadata(): String {
		val rt = Runtime.getRuntime()
		val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
		val maxMb = rt.maxMemory() / (1024 * 1024)
		return buildString {
			append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
			append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
			append("App: ").append(BuildIdentity.oneLine())
				.append(" (tier ").append(BuildConfig.DEBUG_TIER).append(")\n")
			append("Heap: ").append(usedMb).append('/').append(maxMb).append(" MB\n")
		}
	}

	@Suppress("TooGenericExceptionCaught") // last-chance log; swallow anything so the default handler still fires.
	private fun safeLog(messageBuilder: () -> String) {
		try {
			DebugLogger.log(DebugCategory.Lifecycle, messageBuilder)
		} catch (_: Throwable) {
		}
	}
}
