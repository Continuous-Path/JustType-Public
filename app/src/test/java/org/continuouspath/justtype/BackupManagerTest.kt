package org.continuouspath.justtype

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

	private lateinit var context: Context
	private lateinit var repo: SettingsRepository
	private lateinit var testScope: TestScope
	private lateinit var originalScope: CoroutineScope
	private lateinit var originalWriter: (Context, android.net.Uri) -> Boolean
	private var writes = 0

	@Before fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()
		testScope = TestScope(StandardTestDispatcher())
		originalScope = BackupManager.scope
		BackupManager.scope = testScope
		originalWriter = BackupManager.snapshotWriter
		writes = 0
		BackupManager.snapshotWriter = { _, _ ->
			writes++
			true
		}
	}

	@After fun tearDown() {
		BackupManager.cancelPendingForTesting()
		BackupManager.snapshotWriter = originalWriter
		testScope.coroutineContext[Job]?.cancel()
		BackupManager.scope = originalScope
		SettingsRepository.resetInstanceForTesting()
	}

	// ── Debounce ──────────────────────────────────────────────────────────

	@Test fun `rapid scheduleBackup calls coalesce into one write after the debounce window`() = testScope.runTest {
		repo.putString(Constants.KEY_BACKUP_TREE_URI, "content://test/uri")
		repeat(5) { BackupManager.scheduleBackup(context) }
		advanceTimeBy(2400)
		runCurrent()
		assertThat(writes).isEqualTo(0) // still inside the debounce window

		advanceTimeBy(200)
		runCurrent()
		assertThat(writes).isEqualTo(1) // exactly one coalesced write

		advanceUntilIdle()
		assertThat(writes).isEqualTo(1)
	}

	@Test fun `cancelPendingForTesting prevents the scheduled write from firing`() = testScope.runTest {
		repo.putString(Constants.KEY_BACKUP_TREE_URI, "content://test/uri")
		BackupManager.scheduleBackup(context)
		BackupManager.cancelPendingForTesting()
		advanceUntilIdle()
		assertThat(writes).isEqualTo(0)
	}

	@Test fun `scheduleBackup never writes when no backup tree URI is set`() = testScope.runTest {
		BackupManager.scheduleBackup(context)
		advanceUntilIdle()
		assertThat(writes).isEqualTo(0)
	}

	// ── Prefs round-trip ──────────────────────────────────────────────────

	@Test fun `serializePrefs and applyPrefsSnapshot round-trip every type`() {
		repo.putBoolean("test.bool", true)
		repo.putInt("test.int", 42)
		repo.putLong("test.long", 1234567890123L)
		repo.putFloat("test.float", 1.5f)
		repo.putString("test.string", "hello")

		val serialized = BackupManager.serializePrefs(repo).toString()

		repo.clearForTesting()
		assertThat(repo.contains("test.bool")).isFalse()

		BackupManager.applyPrefsSnapshot(repo, serialized)

		assertThat(repo.getBoolean("test.bool", false)).isTrue()
		assertThat(repo.getInt("test.int", 0)).isEqualTo(42)
		assertThat(repo.getLong("test.long", 0)).isEqualTo(1234567890123L)
		assertThat(repo.getFloat("test.float", 0f)).isEqualTo(1.5f)
		assertThat(repo.getString("test.string", "")).isEqualTo("hello")
	}

	@Test fun `serializePrefs omits EXCLUDED_PREF_KEYS`() {
		repo.putString(Constants.KEY_BACKUP_TREE_URI, "content://something")
		repo.putLong(Constants.KEY_BACKUP_LAST_TS, 999L)
		repo.putBoolean(Constants.KEY_BACKUP_PROMPTED, true)
		repo.putBoolean(Constants.KEY_HAS_RUN_BEFORE, true)
		repo.putString("test.keep", "kept")

		val json = BackupManager.serializePrefs(repo)

		assertThat(json.has("test.keep")).isTrue()
		assertThat(json.has(Constants.KEY_BACKUP_TREE_URI)).isFalse()
		assertThat(json.has(Constants.KEY_BACKUP_LAST_TS)).isFalse()
		assertThat(json.has(Constants.KEY_BACKUP_PROMPTED)).isFalse()
		assertThat(json.has(Constants.KEY_HAS_RUN_BEFORE)).isFalse()
	}

	@Test fun `applyPrefsSnapshot skips EXCLUDED_PREF_KEYS even when present in input`() {
		val payload = """
			{
			  "${Constants.KEY_BACKUP_TREE_URI}": {"type":"string","value":"content://hostile"},
			  "${Constants.KEY_HAS_RUN_BEFORE}":  {"type":"boolean","value":true},
			  "user.color":                       {"type":"string","value":"blue"}
			}
		""".trimIndent()

		BackupManager.applyPrefsSnapshot(repo, payload)

		assertThat(repo.getString("user.color", "")).isEqualTo("blue")
		assertThat(repo.contains(Constants.KEY_BACKUP_TREE_URI)).isFalse()
		assertThat(repo.contains(Constants.KEY_HAS_RUN_BEFORE)).isFalse()
	}

	@Test fun `applyPrefsSnapshot ignores malformed JSON without crashing`() {
		repo.putString("preserved", "yes")
		BackupManager.applyPrefsSnapshot(repo, "not json at all")
		assertThat(repo.getString("preserved", "")).isEqualTo("yes")
	}

	// ── Restore-tail round-trip ───────────────────────────────────────────

	@Test fun `applyRestoreTail preserves local URI and prompt, writes manifest timestamp, marks HAS_RUN`() {
		BackupManager.applyRestoreTail(
			repo,
			keepUri = "content://kept",
			keepPrompted = true,
			manifestTimestamp = 12345L,
		)
		assertThat(repo.getString(Constants.KEY_BACKUP_TREE_URI, "")).isEqualTo("content://kept")
		assertThat(repo.getBoolean(Constants.KEY_BACKUP_PROMPTED, false)).isTrue()
		assertThat(repo.getLong(Constants.KEY_BACKUP_LAST_TS, 0L)).isEqualTo(12345L)
		assertThat(repo.getBoolean(Constants.KEY_HAS_RUN_BEFORE, false)).isTrue()
	}

	@Test fun `applyRestoreTail skips URI write when keepUri is empty`() {
		// Pre-condition: no URI persisted before the call.
		assertThat(repo.contains(Constants.KEY_BACKUP_TREE_URI)).isFalse()
		BackupManager.applyRestoreTail(repo, keepUri = "", keepPrompted = false, manifestTimestamp = 1L)
		assertThat(repo.contains(Constants.KEY_BACKUP_TREE_URI)).isFalse()
		assertThat(repo.getBoolean(Constants.KEY_HAS_RUN_BEFORE, false)).isTrue()
	}

	@Test fun `applyRestoreTail skips timestamp write when manifest timestamp is zero`() {
		BackupManager.applyRestoreTail(repo, keepUri = "", keepPrompted = false, manifestTimestamp = 0L)
		assertThat(repo.contains(Constants.KEY_BACKUP_LAST_TS)).isFalse()
	}

	@Test fun `applyPrefsSnapshot cache reflects restored values synchronously`() {
		// applyPrefsSnapshot uses editor.commit() — post-call, the in-memory cache must reflect
		// every restored key. (Disk durability cannot be unit-tested cleanly; see step5.3 plan.)
		val payload = """
			{"speed":{"type":"int","value":7},"name":{"type":"string","value":"alice"}}
		""".trimIndent()
		BackupManager.applyPrefsSnapshot(repo, payload)
		assertThat(repo.getInt("speed", 0)).isEqualTo(7)
		assertThat(repo.getString("name", "")).isEqualTo("alice")
	}

	@Test fun `restoreCustomWords populates the standalone custom db from the json list`() {
		// Regression: custom-word restore was gated behind !restoredDb and skipped on a normal restore.
		// The mechanism itself must load the backed-up words into CustomDb.db.
		BackupManager.restoreCustomWords(context, """["hola", "mundo", "  ", "adios"]""")
		WordDb.openStandalone(java.io.File(context.filesDir, "CustomDb.db")).use { db ->
			assertThat(db.getWordIDByWord("hola")).isNotNull()
			assertThat(db.getWordIDByWord("mundo")).isNotNull()
			assertThat(db.getWordIDByWord("adios")).isNotNull()
		}
	}
}
