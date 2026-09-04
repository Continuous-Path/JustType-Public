package org.continuouspath.justtype.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class DebugLogShareHelperTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private lateinit var context: Context
	private lateinit var logsDir: File

	@Before fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		DebugLogger.setLogDirectory(File(context.filesDir, "logs"))
		logsDir = DebugLogger.getLogsDirectory()!!
		logsDir.listFiles()?.forEach { it.delete() }
		File(context.cacheDir, "share").listFiles()?.forEach { it.delete() }
	}

	private fun zipEntryNames(): List<String> {
		val zip = File(context.cacheDir, "share").listFiles()?.firstOrNull { it.name.endsWith(".zip") }
			?: return emptyList()
		return ZipInputStream(zip.inputStream()).use { zis ->
			generateSequence { zis.nextEntry }.map { it.name }.toList()
		}
	}

	@Test fun `crash email bundles ExceptionLog and NEVER DebugLog`() {
		// DebugLog can contain user-typed text; the crash bundle must exclude it.
		File(logsDir, "DebugLog_2026-07-23.log").writeText("committed text: 'my private message'")
		File(logsDir, "ExceptionLog_2026-07-23.log").writeText("CRASH REPORT\nNullPointerException")

		val sent = DebugLogShareHelper.emailCrashReport(context)

		assertThat(sent).isTrue()
		val names = zipEntryNames()
		assertThat(names).contains("ExceptionLog_2026-07-23.log")
		assertThat(names.none { it.startsWith("DebugLog_") }).isTrue()
	}

	@Test fun `crash email returns false when there is no crash log`() {
		// Only a DebugLog present — nothing content-safe to send.
		File(logsDir, "DebugLog_2026-07-23.log").writeText("some text")

		val sent = DebugLogShareHelper.emailCrashReport(context)

		assertThat(sent).isFalse()
		assertThat(zipEntryNames()).isEmpty()
	}
}
