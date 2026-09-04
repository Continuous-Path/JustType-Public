package org.continuouspath.justtype.logging

import android.content.Context
import org.continuouspath.justtype.utils.AtomicFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the rolling `ExceptionLog_YYYY-MM-DD.log` file family under
 * `{filesDir}/logs/`. Written by [org.continuouspath.justtype.CrashHandler]
 * (uncaught exceptions, full stack traces) and by
 * [ExceptionReporter] (lightweight notes from silent `catch` blocks).
 *
 * Lifecycle in every build tier (including Tier 0 release) — even a
 * lean release retains crash forensics here.
 *
 * Initialize once at IME startup via [setApplicationContext]; subsequent
 * [append] calls don't require a Context — important for use from
 * deep in IME code where Context is awkward to thread.
 *
 * Rotation rules:
 *   - One active file at any time, named `ExceptionLog_YYYY-MM-DD.log`
 *     using the date the file was *created*. The file persists across
 *     midnight if it hasn't filled.
 *   - When an append would push the active file past
 *     [MAX_EXCEPTION_LOG_BYTES] (500 KB), close it and start a new one
 *     using today's date. If a file with that exact name already exists
 *     (multiple rollovers in one day), append a `.NNNN` suffix.
 *   - At most [MAX_EXCEPTION_LOG_FILES] (2) files exist. When creating
 *     a third would be needed, the OLDEST is deleted first. End state
 *     always: ≤ 2 files holding the most-recent ~500-1000 KB of crash
 *     data.
 */
object ExceptionLogWriter {

	const val MAX_EXCEPTION_LOG_BYTES: Long = 500L * 1024L
	const val MAX_EXCEPTION_LOG_FILES: Int = 2
	const val FILENAME_PREFIX: String = "ExceptionLog_"
	const val FILENAME_EXT: String = ".log"

	private val lock = Any()

	@Volatile private var appContext: Context? = null

	/**
	 * Wire the application Context once at startup. Idempotent — repeated
	 * calls overwrite the prior value (useful in tests). Until called,
	 * [append] is a silent no-op.
	 */
	fun setApplicationContext(context: Context) {
		appContext = context.applicationContext
	}

	/** Back to unwired ([append] no-ops). **Only for use in tests.** */
	internal fun resetForTesting() {
		appContext = null
	}

	/**
	 * Append [content] to the active ExceptionLog. Creates the logs
	 * directory and the active file as needed. Rotates and prunes per
	 * the rules above.
	 *
	 * Best-effort: silently swallows IO failures (we're often called
	 * from a crash handler where escalating an error makes things
	 * worse).
	 */
	@Suppress("TooGenericExceptionCaught")
	fun append(content: String) {
		val ctx = appContext ?: return
		try {
			synchronized(lock) {
				val dir = ensureLogsDirectory(ctx)
				val active = activeFile(dir, content.toByteArray(Charsets.UTF_8).size.toLong())
				AtomicFile.write(active, append = true) { writer ->
					writer.append(content)
				}
				pruneToCap(dir)
			}
		} catch (_: Throwable) {
			// Crash inside crash handler is a black hole; just swallow.
		}
	}

	/**
	 * Find or create the file we'll append to. Caller passes
	 * [pendingBytes] so we can rotate BEFORE writing if the next
	 * append would exceed the cap.
	 */
	private fun activeFile(dir: File, pendingBytes: Long): File {
		val today = todayDateString()
		// Existing ExceptionLog files, newest first by lastModified.
		val existing = dir.listFiles { f ->
			f.isFile && f.name.startsWith(FILENAME_PREFIX) && f.name.endsWith(FILENAME_EXT)
		}?.sortedByDescending { it.lastModified() } ?: emptyList()

		val current = existing.firstOrNull()
		if (current != null && current.length() + pendingBytes <= MAX_EXCEPTION_LOG_BYTES) {
			return current
		}
		// Either no file yet or the active file would overflow → create a new
		// one for today. If today's exact name is the one we're rotating out
		// of, suffix with .NNNN.
		val baseName = "$FILENAME_PREFIX$today$FILENAME_EXT"
		val candidate = File(dir, baseName)
		if (!candidate.exists()) return candidate

		// Multiple rollovers in one day: find the next unused .NNNN suffix.
		var suffix = 1
		while (true) {
			val name = String.format(Locale.US, "%s%s.%04d%s", FILENAME_PREFIX, today, suffix, FILENAME_EXT)
			val file = File(dir, name)
			if (!file.exists()) return file
			suffix++
			if (suffix > MAX_SUFFIX_GUARD) {
				// Defensive: pick today's base file even if it'll overshoot —
				// pruneToCap will clean up next pass.
				return candidate
			}
		}
	}

	/**
	 * Keep at most [MAX_EXCEPTION_LOG_FILES] files. Deletes the oldest
	 * (by lastModified) first.
	 */
	private fun pruneToCap(dir: File) {
		val existing = dir.listFiles { f ->
			f.isFile && f.name.startsWith(FILENAME_PREFIX) && f.name.endsWith(FILENAME_EXT)
		}?.sortedByDescending { it.lastModified() } ?: return
		if (existing.size <= MAX_EXCEPTION_LOG_FILES) return
		existing.drop(MAX_EXCEPTION_LOG_FILES).forEach { runCatching { it.delete() } }
	}

	private fun ensureLogsDirectory(context: Context): File {
		// Mirror DebugLogger's location so the Submit Feedback share helper
		// picks up both DebugLog_*.log and ExceptionLog_*.log files in one
		// pass without any per-source branching.
		val dir = DebugLogger.getLogsDirectory() ?: File(context.filesDir, "logs")
		dir.mkdirs()
		return dir
	}

	private fun todayDateString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

	private const val MAX_SUFFIX_GUARD = 9999
}
