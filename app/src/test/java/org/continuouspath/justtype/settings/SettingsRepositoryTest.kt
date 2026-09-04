package org.continuouspath.justtype.settings

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
	private lateinit var repo: SettingsRepository

	@Before
	fun setUp() {
		val context = ApplicationProvider.getApplicationContext<android.app.Application>()
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	@Test
	fun `getBoolean returns default when key is absent`() {
		assertEquals(true, repo.getBoolean("absent_key", true))
		assertEquals(false, repo.getBoolean("absent_key2", false))
	}

	@Test
	fun `putBoolean then getBoolean round-trips`() {
		repo.putBoolean("test_bool", true)
		assertTrue(repo.getBoolean("test_bool", false))
		repo.putBoolean("test_bool", false)
		assertFalse(repo.getBoolean("test_bool", true))
	}

	@Test
	fun `editor putStringSet then getStringSet round-trips`() {
		// Regression: putStringSet was a silent no-op, so this never persisted.
		repo.edit().putStringSet("test_set", mutableSetOf("a", "b", "c")).apply()
		assertEquals(setOf("a", "b", "c"), repo.getStringSet("test_set", emptySet()))
	}

	@Test
	fun `editor clear resets the in-memory cache synchronously`() {
		repo.putBoolean("kept_bool", true)
		repo.edit().clear().apply()
		assertFalse(repo.getBoolean("kept_bool", false)) // cache no longer returns the pre-clear value
	}

	@Test
	fun `getInt returns default when key is absent`() {
		assertEquals(42, repo.getInt("absent_int", 42))
	}

	@Test
	fun `putInt then getInt round-trips`() {
		repo.putInt("test_int", 123)
		assertEquals(123, repo.getInt("test_int", 0))
	}

	@Test
	fun `getFloat returns default when key is absent`() {
		assertEquals(0.5f, repo.getFloat("absent_float", 0.5f), 0.001f)
	}

	@Test
	fun `putFloat then getFloat round-trips`() {
		repo.putFloat("test_float", 3.14f)
		assertEquals(3.14f, repo.getFloat("test_float", 0f), 0.001f)
	}

	@Test
	fun `getString returns default when key is absent`() {
		assertEquals("default", repo.getString("absent_str", "default"))
	}

	@Test
	fun `putString then getString round-trips`() {
		repo.putString("test_str", "hello")
		assertEquals("hello", repo.getString("test_str", ""))
	}

	@Test
	fun `getLong returns default when key is absent`() {
		assertEquals(99L, repo.getLong("absent_long", 99L))
	}

	@Test
	fun `putLong then getLong round-trips`() {
		repo.putLong("test_long", 1234567890L)
		assertEquals(1234567890L, repo.getLong("test_long", 0L))
	}

	@Test
	fun `addChangeListener is notified when key changes`() {
		val notified = AtomicBoolean(false)
		val changedKey = AtomicReference<String?>(null)
		val listener =
			SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
				notified.set(true)
				changedKey.set(key)
			}
		repo.addChangeListener(listener)
		repo.putBoolean("some_key", true)
		// Listener is triggered synchronously by the optimistic cache update
		assertTrue("Listener should have been notified", notified.get())
		assertEquals("some_key", changedKey.get())
		repo.removeChangeListener(listener)
	}

	@Test
	fun `removeChangeListener is not notified after removal`() {
		val notified = AtomicBoolean(false)
		val listener =
			SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
				notified.set(true)
			}
		repo.addChangeListener(listener)
		repo.removeChangeListener(listener)
		repo.putBoolean("another_key", false)
		assertFalse("Listener should NOT be notified after removal", notified.get())
	}

	@Test
	fun `contains returns false for absent key`() {
		assertFalse(repo.contains("definitely_absent_key_xyz"))
	}

	@Test
	fun `contains returns true after put`() {
		repo.putString("present_key", "value")
		assertTrue(repo.contains("present_key"))
	}

	// ── Persist ordering ──────────────────────────────────────────────────────
	// setUp's clearForTesting cancels the persist scope, so these tests build a
	// fresh instance whose async persist queue is live.

	private fun freshRepoWithLivePersistScope(): SettingsRepository {
		SettingsRepository.resetInstanceForTesting()
		repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		return repo
	}

	private fun persistedInt(key: String): Int = runBlocking {
		repo.getIntFlow(key, -1).first()
	}

	@Test
	fun `rapid same-key writes persist the last value`() {
		freshRepoWithLivePersistScope()
		repeat(100) { repo.putInt("persist_order_key", it) }
		repo.edit().commit() // fence: drains the persist queue
		assertEquals(99, persistedInt("persist_order_key"))
	}

	@Test
	fun `blocking commit lands after queued async writes`() {
		freshRepoWithLivePersistScope()
		repeat(50) { repo.putInt("persist_commit_key", it) }
		repo.edit().putInt("persist_commit_key", 777).commit()
		assertEquals(777, persistedInt("persist_commit_key"))
	}

	@Test
	fun `get() throws before getInstance is called`() {
		SettingsRepository.resetInstanceForTesting()
		try {
			SettingsRepository.get()
			assert(false) { "Should have thrown" }
		} catch (_: IllegalStateException) {
			// expected
		}
		// Restore for subsequent tests in this class
		repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
	}
}
