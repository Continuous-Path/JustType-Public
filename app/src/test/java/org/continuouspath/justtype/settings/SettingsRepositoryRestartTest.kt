package org.continuouspath.justtype.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.continuouspath.justtype.testutil.ResetSingletonsRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Restart/corruption family: each [SimulatedProcess] owns a real DataStore file
 * plus its own store scope, so killing one and starting another exercises the
 * true cold-start read path against persisted (or corrupted) disk state — the
 * scenario the singleton reset (which wipes the store) structurally cannot reach.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryRestartTest {

	@get:Rule
	val resetSingletons = ResetSingletonsRule()

	private lateinit var context: Context
	private lateinit var storeFile: File
	private val processes = mutableListOf<SimulatedProcess>()

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		storeFile = File(context.filesDir, "restart-test/settings.preferences_pb")
		storeFile.parentFile!!.mkdirs()
		PrefsSidecar.deleteForTesting(context)
	}

	@After
	fun tearDown() {
		processes.forEach { it.kill() }
		processes.clear()
		PrefsSidecar.deleteForTesting(context)
	}

	@Test
	fun `values survive a simulated process restart`() {
		val first = startProcess()
		first.repo.putBoolean("beep", true)
		first.repo.putInt("slider", 42)
		first.repo.putFloat("scale", 1.5f)
		first.repo.putString("lang", "es")
		first.kill()

		val second = startProcess()
		assertThat(second.repo.getBoolean("beep", false)).isTrue()
		assertThat(second.repo.getInt("slider", -1)).isEqualTo(42)
		assertThat(second.repo.getFloat("scale", -1f)).isEqualTo(1.5f)
		assertThat(second.repo.getString("lang", "")).isEqualTo("es")
	}

	@Test
	fun `rapid same-key burst persists the final value across restart`() {
		// Slider-drag shape: the serial persist queue must land writes in order,
		// or a stale value resurfaces as a silently reverted setting on restart.
		val first = startProcess()
		repeat(50) { first.repo.putInt("slider", it) }
		first.kill()

		assertThat(startProcess().repo.getInt("slider", -1)).isEqualTo(49)
	}

	@Test
	fun `corrupted store restores from sidecar on next start`() {
		val first = startProcess()
		first.repo.putInt("slider", 42)
		first.kill()
		PrefsSidecar.write(
			context,
			preferencesOf(intPreferencesKey("slider") to 42, stringPreferencesKey("lang") to "es"),
		)
		storeFile.writeBytes("not a protobuf".toByteArray())

		val second = startProcess()
		assertThat(second.repo.getInt("slider", -1)).isEqualTo(42)
		assertThat(second.repo.getString("lang", "")).isEqualTo("es")
	}

	@Test
	fun `corrupted store without a sidecar starts empty instead of crashing`() {
		val first = startProcess()
		first.repo.putInt("slider", 42)
		first.kill()
		storeFile.writeBytes("not a protobuf".toByteArray())

		val second = startProcess()
		assertThat(second.repo.getInt("slider", -1)).isEqualTo(-1)
	}

	@Test
	fun `unflushed shutdown never resurrects a value newer than disk`() {
		// Hard-kill semantics: whatever survives must be a value that was actually
		// persisted at some point — never a torn or reordered write.
		val first = startProcess()
		first.repo.putInt("slider", 1)
		first.repo.putInt("slider", 2)
		first.kill(flush = false)

		assertThat(startProcess().repo.getInt("slider", -1)).isIn(listOf(-1, 1, 2))
	}

	private fun startProcess(): SimulatedProcess = SimulatedProcess(context, storeFile).also { processes += it }

	private class SimulatedProcess(context: Context, file: File) {
		private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
		private var killed = false

		val repo: SettingsRepository = SettingsRepository.createForTesting(
			context,
			PreferenceDataStoreFactory.create(
				corruptionHandler = settingsCorruptionHandler(),
				scope = storeScope,
				produceFile = { file },
			),
		)

		fun kill(flush: Boolean = true) {
			if (killed) return
			killed = true
			repo.shutdownForTesting(flushPendingWrites = flush)
			// Join the store scope so DataStore releases its file claim before the
			// next SimulatedProcess opens the same file.
			storeScope.cancel()
			runBlocking { storeScope.coroutineContext[Job]?.join() }
		}
	}
}
