package org.continuouspath.justtype

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.logging.ExceptionLogWriter
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CrashHandlerTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()
	private lateinit var context: Context
	private lateinit var repo: SettingsRepository
	private lateinit var logsDir: File

	@Before fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()
		// Wire ExceptionLogWriter with this test's Context — the production
		// code calls setApplicationContext from JustTypeIME.onCreate, which
		// doesn't fire under Robolectric unless we drive it explicitly.
		ExceptionLogWriter.setApplicationContext(context)
		// Clean state in logs/.
		logsDir = File(context.filesDir, "logs")
		if (logsDir.exists()) {
			logsDir.listFiles()?.forEach { it.delete() }
		} else {
			logsDir.mkdirs()
		}
		// Also delete legacy artifacts in case a prior test wrote them.
		File(context.filesDir, "crash.log").delete()
		File(context.filesDir, "crash.log.old").delete()
	}

	@After fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	/** Returns the concatenated contents of every ExceptionLog_*.log file. */
	private fun readAllExceptionLogs(): String = logsDir.listFiles { f -> f.name.startsWith("ExceptionLog_") && f.name.endsWith(".log") }
		?.sortedBy { it.lastModified() }
		?.joinToString(separator = "\n") { it.readText() }
		.orEmpty()

	private fun exceptionLogFiles(): List<File> = logsDir.listFiles { f -> f.name.startsWith("ExceptionLog_") && f.name.endsWith(".log") }
		?.sortedBy { it.lastModified() }
		.orEmpty()
		.toList()

	@Test fun `uncaughtException writes recovery flag synchronously`() {
		val handler = CrashHandler(context, defaultHandler = null)
		val ex = RuntimeException("boom")

		handler.uncaughtException(Thread.currentThread(), ex)

		assertThat(repo.getBoolean(Constants.KEY_LAST_SESSION_CRASHED, false)).isTrue()
		assertThat(repo.getString(Constants.KEY_LAST_CRASH_MESSAGE, "")).isEqualTo("boom")
		assertThat(repo.getLong(Constants.KEY_LAST_CRASH_TIME, 0)).isGreaterThan(0)
	}

	@Test fun `uncaughtException sets the crash-report-pending flag for the Settings prompt`() {
		val handler = CrashHandler(context, defaultHandler = null)

		handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

		assertThat(repo.getBoolean(Constants.KEY_CRASH_REPORT_PENDING, false)).isTrue()
	}

	@Test fun `ExceptionLog includes content-safe device and build metadata`() {
		val handler = CrashHandler(context, defaultHandler = null)

		handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

		val log = readAllExceptionLogs()
		assertThat(log).contains("Device:")
		assertThat(log).contains("Android:")
		assertThat(log).contains("App:")
		assertThat(log).contains("Heap:")
	}

	@Test fun `uncaughtException writes ExceptionLog with exception details`() {
		val handler = CrashHandler(context, defaultHandler = null)
		val ex = IllegalStateException("kaboom")

		handler.uncaughtException(Thread.currentThread(), ex)

		val log = readAllExceptionLogs()
		assertThat(log).contains("CRASH REPORT")
		assertThat(log).contains("IllegalStateException")
		assertThat(log).contains("kaboom")
		assertThat(log).contains(Thread.currentThread().name)
	}

	@Test fun `cause chain is written to ExceptionLog`() {
		val handler = CrashHandler(context, defaultHandler = null)
		val root = IllegalArgumentException("root cause")
		val wrapper = RuntimeException("outer", root)

		handler.uncaughtException(Thread.currentThread(), wrapper)

		val log = readAllExceptionLogs()
		assertThat(log).contains("outer")
		assertThat(log).contains("Caused by:")
		assertThat(log).contains("root cause")
	}

	@Test fun `ExceptionLog appends across multiple crashes within size cap`() {
		val handler = CrashHandler(context, defaultHandler = null)

		handler.uncaughtException(Thread.currentThread(), RuntimeException("first"))
		handler.uncaughtException(Thread.currentThread(), RuntimeException("second"))

		val log = readAllExceptionLogs()
		assertThat(log).contains("first")
		assertThat(log).contains("second")
		// Both fit comfortably in a single 500 KB file — only one file should exist.
		assertThat(exceptionLogFiles()).hasSize(1)
	}

	@Test fun `ExceptionLog rolls to second file when size exceeds 500 KB cap`() {
		// Pre-seed an active file just under the cap so the next crash forces rotation.
		val seedName = "ExceptionLog_${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.log"
		val seed = File(logsDir, seedName)
		seed.writeText("x".repeat((ExceptionLogWriter.MAX_EXCEPTION_LOG_BYTES - 100L).toInt()))

		val handler = CrashHandler(context, defaultHandler = null)
		handler.uncaughtException(Thread.currentThread(), RuntimeException("after rotate"))

		val files = exceptionLogFiles()
		assertThat(files.size).isAtLeast(2)
		// Most recent (sorted by mtime) contains the new entry.
		val newest = files.last()
		assertThat(newest.readText()).contains("after rotate")
	}

	@Test fun `ExceptionLog keeps at most 2 files (oldest deleted)`() {
		// Pre-seed two full files so the next rotation triggers cap-enforcement.
		val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
		val first = File(logsDir, "ExceptionLog_$today.log")
		val second = File(logsDir, "ExceptionLog_$today.0001.log")
		first.writeText("x".repeat((ExceptionLogWriter.MAX_EXCEPTION_LOG_BYTES - 50L).toInt()))
		second.writeText("x".repeat((ExceptionLogWriter.MAX_EXCEPTION_LOG_BYTES - 50L).toInt()))
		// Make the second file's mtime strictly newer so the writer considers it active.
		second.setLastModified(first.lastModified() + 1_000)

		val handler = CrashHandler(context, defaultHandler = null)
		handler.uncaughtException(Thread.currentThread(), RuntimeException("forces cap"))

		assertThat(exceptionLogFiles().size).isAtMost(ExceptionLogWriter.MAX_EXCEPTION_LOG_FILES)
	}

	@Test fun `defaultHandler is always invoked`() {
		var captured: Throwable? = null
		val default = Thread.UncaughtExceptionHandler { _, t -> captured = t }
		val handler = CrashHandler(context, default)
		val ex = RuntimeException("forwarded")

		handler.uncaughtException(Thread.currentThread(), ex)

		assertThat(captured).isSameInstanceAs(ex)
	}

	@Test fun `defaultHandler still invoked when log write fails`() {
		// Pre-create a DIRECTORY at the day's ExceptionLog path so AtomicFile.write fails.
		val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
		val sabotage = File(logsDir, "ExceptionLog_$today.log")
		sabotage.delete()
		sabotage.mkdir()

		var captured: Throwable? = null
		val default = Thread.UncaughtExceptionHandler { _, t -> captured = t }
		val handler = CrashHandler(context, default)
		val ex = RuntimeException("still forwarded")

		handler.uncaughtException(Thread.currentThread(), ex)

		assertThat(captured).isSameInstanceAs(ex)
		// Recovery flag still set even though log write failed.
		assertThat(repo.getBoolean(Constants.KEY_LAST_SESSION_CRASHED, false)).isTrue()

		sabotage.delete()
	}
}
