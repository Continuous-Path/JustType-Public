package org.continuouspath.justtype.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight reporter for *silent* exception swallows — `catch
 * (_: Exception) {}` blocks that today vanish without a trace.
 *
 * Writes one-line entries to the active `ExceptionLog_YYYY-MM-DD.log`
 * file regardless of `BuildConfig.DEBUG_TIER`, so even a Tier 0 lean
 * release retains a breadcrumb trail for diagnosing puzzling field
 * reports. Unlike [org.continuouspath.justtype.CrashHandler] (which writes a
 * full stack trace for uncaught exceptions), this writes only the top
 * frames — enough to identify the site without bloating the log.
 *
 * Tag convention: `"ClassName:methodName"` — short, greppable, no PII.
 *
 * Example usage:
 * ```
 * try { ic.getExtractedText(...) }
 * catch (e: Exception) {
 *     ExceptionReporter.reportSilent("ImeTextController:getCursorOffset", e)
 *     return -1
 * }
 * ```
 *
 * Notes appended after the standard line are optional — useful when the
 * catch site has local context (e.g., "buttonIndex=$idx"). The Context
 * is obtained from [ExceptionLogWriter] — call its
 * [ExceptionLogWriter.setApplicationContext] once at startup.
 */
object ExceptionReporter {

	private const val MAX_STACK_FRAMES_LOGGED = 3

	/**
	 * Append a single-line silent-exception entry to ExceptionLog.
	 * Best-effort — IO failures are swallowed (we're already in an
	 * error path).
	 */
	@Suppress("TooGenericExceptionCaught")
	fun reportSilent(
		tag: String,
		throwable: Throwable,
		notes: String? = null,
	) {
		try {
			val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
			val excType = throwable.javaClass.simpleName
			val excMsg = throwable.message?.replace("\n", " ")?.take(MAX_MESSAGE_CHARS) ?: ""
			val notesPart = if (notes != null) "  notes=$notes" else ""
			val topFrames = throwable.stackTrace
				.take(MAX_STACK_FRAMES_LOGGED)
				.joinToString(separator = " <- ") { "${it.fileName}:${it.lineNumber}" }
			val line = "$timestamp | [SILENT] $tag  $excType: $excMsg$notesPart  at $topFrames\n"
			ExceptionLogWriter.append(line)
		} catch (_: Throwable) {
			// In an error path inside an error path; just swallow.
		}
	}

	private const val MAX_MESSAGE_CHARS: Int = 200
}
