package org.continuouspath.justtype

import android.app.Application
import org.continuouspath.justtype.logging.ExceptionLogWriter
import java.io.File

/**
 * Process-level init that must happen regardless of which component starts first
 * (the IME, the Nav accessibility service, or an Activity). Registering the crash
 * handler here — not in [JustTypeIME.onCreate] — means a crash in ANY component,
 * including a Nav-only session where the IME never started, is caught, logged, and
 * flagged for a report. Android won't auto-restart a crashed AccessibilityService
 * the way it rebinds a crashed IME, but this at least gives Nav crashes the same
 * forensics + report path the IME already had.
 */
class JustTypeApplication : Application() {

	override fun onCreate() {
		super.onCreate()

		// Crash-log sink needs an application Context; wire it before the handler so a
		// very-early crash still writes. Uses DebugLogger's dir or filesDir/logs fallback.
		ExceptionLogWriter.setApplicationContext(this)

		// Migrate away legacy single-file logs superseded by the rolling logs/ format.
		runCatching { File(filesDir, "debug.log").takeIf { it.exists() }?.delete() }
		runCatching { File(filesDir, "crash.log").takeIf { it.exists() }?.delete() }
		runCatching { File(filesDir, "crash.log.old").takeIf { it.exists() }?.delete() }

		// One handler for the whole process, installed at process birth.
		Thread.setDefaultUncaughtExceptionHandler(
			CrashHandler(this, Thread.getDefaultUncaughtExceptionHandler()),
		)
	}
}
