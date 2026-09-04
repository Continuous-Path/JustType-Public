package org.continuouspath.justtype.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.Constants.PREFS_NAME
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

// Captured at first SettingsRepository construction so the DataStore corruption
// handler (which runs in DataStore's IO scope without access to a Context) can
// reach the sidecar file for recovery.
private val sidecarContextRef = AtomicReference<Context?>(null)

// Shared by the production delegate and test-created stores so corruption
// recovery behaves identically against real files in both.
internal fun settingsCorruptionHandler(): ReplaceFileCorruptionHandler<Preferences> = ReplaceFileCorruptionHandler { ex ->
	android.util.Log.e(
		"SettingsInit",
		"DataStore protobuf corrupted; attempting sidecar restore",
		ex,
	)
	val ctx = sidecarContextRef.get()
	val restored = ctx?.let { PrefsSidecar.read(it) }
	if (restored != null) {
		android.util.Log.i(
			"SettingsInit",
			"Restored ${restored.asMap().size} keys from sidecar",
		)
		restored
	} else {
		android.util.Log.e(
			"SettingsInit",
			"No sidecar available; returning empty Preferences (user data lost)",
		)
		emptyPreferences()
	}
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
	name = PREFS_NAME,
	corruptionHandler = settingsCorruptionHandler(),
	produceMigrations = { context ->
		listOf(SharedPreferencesMigration(context, PREFS_NAME))
	},
)

/**
 * Modern [DataStore]-backed repository for JustType settings.
 *
 * Provides synchronous read access via an in-memory cache to support the
 * IME's performance-critical input processing, while using DataStore
 * for persistent, thread-safe storage and reactive updates.
 *
 * Automatically migrates existing [SharedPreferences] on first run.
 *
 * **Cache contract:** the in-memory cache is the process-local source of truth for
 * synchronous reads. It is populated by a one-shot blocking read in [init], then
 * updated synchronously by every write path ([updateCacheAndPersist],
 * [DataStoreEditor.applyToCache], [clearForTesting]) BEFORE the asynchronous DataStore
 * round-trip. Listeners are notified from those write paths — not from a persistent
 * collect on `dataStore.data` — so each write produces exactly one listener fire.
 *
 * Per-key reactive [Flow] accessors ([getBooleanFlow], etc.) read `dataStore.data`
 * directly and are unaffected by the cache. They remain the right tool for callers
 * that need to observe persistent storage rather than the in-process cache.
 */
class SettingsRepository private constructor(
	context: Context,
	store: DataStore<Preferences>? = null,
) {
	private val appContext = context.applicationContext
	private val dataStore = store ?: appContext.dataStore

	// Serial persist queue: async DataStore edits must reach the store in write
	// order. On an unordered dispatcher, a rapid same-key burst (e.g. a slider
	// drag) can persist a stale value that resurfaces as a silently "reverted"
	// setting on the next process start.
	@OptIn(ExperimentalCoroutinesApi::class)
	private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

	init {
		// Make the application context available to the static corruption handler.
		sidecarContextRef.set(appContext)
	}

	private var sidecarRefreshJob: Job? = null

	// In-memory cache for synchronous reads
	private val cache = AtomicReference<Preferences>(emptyPreferences())

	// Legacy listener support
	private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

	init {
		// Initial blocking read populates the cache for immediate IME startup use.
		// DataStore migration from SharedPreferences also happens during this first access.
		runBlocking {
			try {
				cache.set(dataStore.data.first())
			} catch (e: IOException) {
				android.util.Log.e(
					"SettingsInit",
					"DataStore read failed at init; cache will be empty until next write",
					e,
				)
				cache.set(emptyPreferences())
			}
		}
		// Diagnostic snapshot — investigating a transient bug where the settings UI
		// renders sliders at min after an IME process restart. Filter with:
		// adb logcat -s SettingsInit
		run {
			val snapshot = cache.get().asMap()
			val sample = snapshot.entries.take(8).joinToString(", ") { "${it.key.name}=${it.value}" }
			android.util.Log.d(
				"SettingsInit",
				"cache loaded: ${snapshot.size} keys, sample: [$sample]",
			)
		}
		// All in-process writes update the cache synchronously before persisting (see
		// updateCacheAndPersist / DataStoreEditor.applyToCache). Listeners fire once per
		// write from those paths. We deliberately do NOT collect dataStore.data after init
		// because that would (a) re-fire listeners on every async DataStore round-trip and
		// (b) cause cross-test cache stomps from a prior test's pending writes landing
		// during the next test's setup.
	}

	private fun notifyListeners(old: Preferences, new: Preferences) {
		if (listeners.isEmpty()) return
		// Identify which keys changed
		val allKeys = old.asMap().keys + new.asMap().keys
		for (key in allKeys) {
			if (old[key] != new[key]) {
				for (listener in listeners) {
					// We pass null for the SharedPreferences parameter as we are migrating away,
					// but most listeners only care about the key.
					listener.onSharedPreferenceChanged(null, key.name)
				}
			}
		}
	}

	// ── Synchronous Reads (from Cache) ────────────────────────────────────────

	fun getBoolean(key: String, default: Boolean): Boolean = cache.get()[booleanPreferencesKey(key)] ?: default

	fun getInt(key: String, default: Int): Int = cache.get()[intPreferencesKey(key)] ?: default

	fun getFloat(key: String, default: Float): Float = cache.get()[floatPreferencesKey(key)] ?: default

	fun getString(key: String, default: String): String = cache.get()[stringPreferencesKey(key)] ?: default

	fun getLong(key: String, default: Long): Long = cache.get()[longPreferencesKey(key)] ?: default

	fun getStringSet(key: String, default: Set<String>?): Set<String>? = cache.get()[stringSetPreferencesKey(key)] ?: default

	fun contains(key: String): Boolean = cache.get().contains(stringPreferencesKey(key)) ||
		cache.get().contains(booleanPreferencesKey(key)) ||
		cache.get().contains(intPreferencesKey(key)) ||
		cache.get().contains(floatPreferencesKey(key)) ||
		cache.get().contains(longPreferencesKey(key)) ||
		cache.get().contains(stringSetPreferencesKey(key))

	/** Full snapshot of all preferences (e.g. for backup serialization). */
	val all: Map<String, *>
		get() = cache.get().asMap().mapKeys { it.key.name }

	/** Immutable snapshot of the cache as [Preferences] (e.g. for UpdateSnapshot). */
	internal fun snapshotPreferences(): Preferences = cache.get()

	// ── Writes ───────────────────────────────────────────────────────────────
	// Each write updates the in-memory cache immediately (so subsequent reads
	// are consistent) and then persists to DataStore asynchronously.

	/**
	 * Keys to watch for diagnostic logging of every write. Investigating user
	 * reports of certain settings changing to unexpected values. Filter logcat
	 * with: adb logcat -s SettingsWrite
	 */
	private val watchedKeysForLogging = setOf(
		"headtracking_exit_delay_ms",
		"headtracking_response_curve",
		"headtracking_pitch_scale",
		"headtracking_aim_tolerance",
		"headtracking_key_act_threshold",
	)

	private fun logIfWatched(keyName: String, value: Any?) {
		if (keyName !in watchedKeysForLogging) return
		val stack = Throwable().stackTrace
			.drop(2) // skip this method + caller
			.take(8)
			.joinToString(separator = "\n    ") { "$it" }
		android.util.Log.d(
			"SettingsWrite",
			"$keyName = $value  thread=${Thread.currentThread().name}\n    $stack",
		)
	}

	private fun <T> updateCacheAndPersist(key: Preferences.Key<T>, value: T) {
		logIfWatched(key.name, value)
		val oldPrefs = cache.getAndUpdate { current ->
			current.toMutablePreferences().apply { set(key, value) }
		}
		notifyListeners(oldPrefs, cache.get())
		scope.launch { dataStore.edit { it[key] = value } }
		scheduleSidecarRefresh()
	}

	// Debounced JSON mirror of the cache to filesDir/prefs_sidecar.json.
	// Read by the DataStore corruption handler on init to recover user data
	// instead of falling back to registry defaults.
	private fun scheduleSidecarRefresh() {
		sidecarRefreshJob?.cancel()
		sidecarRefreshJob = scope.launch {
			delay(SIDECAR_DEBOUNCE_MS)
			PrefsSidecar.write(appContext, cache.get())
		}
	}

	fun putBoolean(key: String, value: Boolean) {
		updateCacheAndPersist(booleanPreferencesKey(key), value)
	}

	fun putInt(key: String, value: Int) {
		updateCacheAndPersist(intPreferencesKey(key), value)
	}

	fun putFloat(key: String, value: Float) {
		updateCacheAndPersist(floatPreferencesKey(key), value)
	}

	fun putString(key: String, value: String) {
		updateCacheAndPersist(stringPreferencesKey(key), value)
		// Word-list-style write-through: KEY_NGB_PREDICTIONS is DERIVED
		// (classic == predictions off) so every internal read and
		// enabledWhenKey gate keeps working unchanged. Single choke point —
		// both settings surfaces and backup restore write through here.
		if (key == Constants.KEY_WORD_LIST_STYLE) {
			putBoolean(Constants.KEY_NGB_PREDICTIONS, value != Constants.WORD_LIST_STYLE_CLASSIC)
		}
	}

	fun putLong(key: String, value: Long) {
		updateCacheAndPersist(longPreferencesKey(key), value)
	}

	fun putStringSet(key: String, value: Set<String>) {
		updateCacheAndPersist(stringSetPreferencesKey(key), value)
	}

	// ── Reactive Updates ──────────────────────────────────────────────────────

	fun getBooleanFlow(key: String, default: Boolean): Flow<Boolean> = dataStore.data.map { it[booleanPreferencesKey(key)] ?: default }

	fun getStringFlow(key: String, default: String): Flow<String> = dataStore.data.map { it[stringPreferencesKey(key)] ?: default }

	fun getIntFlow(key: String, default: Int): Flow<Int> = dataStore.data.map { it[intPreferencesKey(key)] ?: default }

	fun getFloatFlow(key: String, default: Float): Flow<Float> = dataStore.data.map { it[floatPreferencesKey(key)] ?: default }

	fun getStringSetFlow(key: String, default: Set<String>?): Flow<Set<String>?> = dataStore.data.map { it[stringSetPreferencesKey(key)] ?: default }

	// ── Legacy Compatibility ──────────────────────────────────────────────────

	/**
	 * Returns a compatibility editor for batch updates.
	 * Used by legacy setup code and DebugSettingsPreset.
	 */
	fun edit(): SharedPreferences.Editor = DataStoreEditor()

	fun addChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
		listeners.add(listener)
	}

	fun removeChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
		listeners.remove(listener)
	}

	// Wraps a UI-touching listener so off-Main fires (e.g. BackupRestoreActivity's
	// worker Thread writing prefs) marshal onto Main before invoking it.
	// Returns the wrapper for symmetric removeChangeListener.
	fun addMainThreadListener(
		listener: SharedPreferences.OnSharedPreferenceChangeListener,
	): SharedPreferences.OnSharedPreferenceChangeListener {
		val mainHandler = Handler(Looper.getMainLooper())
		val wrapped = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
			if (Looper.myLooper() == Looper.getMainLooper()) {
				listener.onSharedPreferenceChanged(sp, key)
			} else {
				mainHandler.post { listener.onSharedPreferenceChanged(sp, key) }
			}
		}
		listeners.add(wrapped)
		return wrapped
	}

	// ── Internal Helper Classes ───────────────────────────────────────────────

	/**
	 * Minimal implementation of [SharedPreferences.Editor] that proxies to [DataStore].
	 * Only implements the methods actually used in the codebase.
	 */
	private inner class DataStoreEditor : SharedPreferences.Editor {
		private val pendingChanges = mutableMapOf<Preferences.Key<*>, Any?>()

		override fun putString(key: String, value: String?): SharedPreferences.Editor {
			if (value == null) {
				pendingChanges.remove(stringPreferencesKey(key))
			} else {
				pendingChanges[stringPreferencesKey(key)] = value
			}
			return this
		}

		override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
			pendingChanges[booleanPreferencesKey(key)] = value
			return this
		}

		override fun putInt(key: String, value: Int): SharedPreferences.Editor {
			pendingChanges[intPreferencesKey(key)] = value
			return this
		}

		override fun putLong(key: String, value: Long): SharedPreferences.Editor {
			pendingChanges[longPreferencesKey(key)] = value
			return this
		}

		override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
			pendingChanges[floatPreferencesKey(key)] = value
			return this
		}

		// Keys are type-erased in the pending map; each value was stored via the
		// matching typed put, so the cast is safe by construction.
		private fun MutablePreferences.applyChanges(changes: Map<Preferences.Key<*>, Any?>) {
			changes.forEach { (key, value) ->
				if (value == null) {
					remove(key)
				} else {
					@Suppress("UNCHECKED_CAST")
					set(key as Preferences.Key<Any>, value)
				}
			}
		}

		private fun applyToCache(changes: Map<Preferences.Key<*>, Any?>) {
			// Diagnostic: log writes to watched keys before they hit the cache.
			changes.forEach { (k, v) -> logIfWatched(k.name, v) }
			val oldPrefs = cache.getAndUpdate { current ->
				current.toMutablePreferences().apply { applyChanges(changes) }
			}
			notifyListeners(oldPrefs, cache.get())
		}

		override fun apply() {
			val changes = pendingChanges.toMap()
			applyToCache(changes)
			scope.launch {
				dataStore.edit { prefs -> prefs.applyChanges(changes) }
			}
			scheduleSidecarRefresh()
		}

		override fun commit(): Boolean {
			val changes = pendingChanges.toMap()
			applyToCache(changes)
			// Fence: let queued async persists land first, so this blocking write
			// can't be overwritten by an older in-flight edit. On a dead scope
			// (tests after clearForTesting) the fence is a no-op and the direct
			// edit below still runs.
			val fence = scope.launch {}
			val result = runBlocking {
				fence.join()
				try {
					dataStore.edit { prefs -> prefs.applyChanges(changes) }
					true
				} catch (_: Exception) {
					false
				}
			}
			scheduleSidecarRefresh()
			return result
		}

		override fun remove(key: String): SharedPreferences.Editor {
			// Mark for removal — find the actual key type from the current cache
			val snapshot = cache.get().asMap()
			val matchingKey = snapshot.keys.firstOrNull { it.name == key }
			if (matchingKey != null) {
				pendingChanges[matchingKey] = null
			}
			return this
		}

		override fun clear(): SharedPreferences.Editor {
			// Reset the in-memory cache too, else synchronous getX would keep returning pre-clear
			// values until process restart (the DataStore clear below is async).
			val old = cache.getAndSet(emptyPreferences())
			scope.launch { dataStore.edit { it.clear() } }
			notifyListeners(old, cache.get())
			return this
		}

		override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
			// Was a silent no-op: edit().putStringSet(...).apply() never persisted. Record it like the
			// other put* so string-set defaults (e.g. tier debug-log categories) actually take effect.
			// (Null follows the same convention as putString: drops any pending write for this key.)
			if (values == null) {
				pendingChanges.remove(stringSetPreferencesKey(key))
			} else {
				pendingChanges[stringSetPreferencesKey(key)] = values.toSet()
			}
			return this
		}
	}

	// ── Testing Support ───────────────────────────────────────────────────────

	/**
	 * Synchronously clears all data in the DataStore and resets the in-memory cache.
	 * **Only for use in tests** — calling this in production code will wipe all settings.
	 */
	internal fun clearForTesting() {
		listeners.clear()
		// Cancel and JOIN in-flight persists BEFORE wiping: a queued edit landing
		// after the clear resurrects stale values into the next test. The cancel
		// also keeps IO coroutines from leaking into the shared JVM fork (they
		// pile up and starve the dispatcher pool on cold-start full-suite runs).
		sidecarRefreshJob?.cancel()
		scope.coroutineContext[Job]?.cancel()
		runBlocking {
			scope.coroutineContext[Job]?.children?.forEach { it.join() }
			dataStore.edit { it.clear() }
		}
		cache.set(emptyPreferences())
	}

	/**
	 * Simulates process death for tests WITHOUT wiping persisted data (unlike
	 * [clearForTesting]): optionally lets queued persists land, then cancels this
	 * instance's write scope. The DataStore contents survive for the next
	 * instance's cold-start read.
	 */
	internal fun shutdownForTesting(flushPendingWrites: Boolean = true) {
		// The debounced sidecar refresh is a child of the same scope; drop it so a
		// flush joins only real persist edits (and tests control sidecar contents).
		sidecarRefreshJob?.cancel()
		if (flushPendingWrites) {
			runBlocking { scope.coroutineContext[Job]?.children?.forEach { it.join() } }
		}
		listeners.clear()
		scope.coroutineContext[Job]?.cancel()
	}

	// ── Singleton ─────────────────────────────────────────────────────────────

	companion object {
		private const val SIDECAR_DEBOUNCE_MS = 1000L

		@Volatile private var instance: SettingsRepository? = null

		fun getInstance(context: Context): SettingsRepository = instance ?: synchronized(this) {
			instance ?: SettingsRepository(context).also { instance = it }
		}

		/**
		 * Returns the already-initialized instance.
		 * @throws IllegalStateException if [getInstance] has not been called yet.
		 */
		fun get(): SettingsRepository = instance ?: error("SettingsRepository not initialized. Call getInstance(context) first.")

		/**
		 * Resets the singleton for test isolation. Clears all data and nulls
		 * the instance so the next [getInstance] creates a fresh repository.
		 * **Only for use in tests.**
		 */
		@JvmStatic
		internal fun resetInstanceForTesting() {
			synchronized(this) {
				instance?.clearForTesting()
				instance = null
			}
		}

		/**
		 * Builds a repository on a caller-owned [DataStore] outside the singleton,
		 * so tests can exercise restart/corruption behavior against real files.
		 * **Only for use in tests.**
		 */
		internal fun createForTesting(context: Context, store: DataStore<Preferences>): SettingsRepository = SettingsRepository(context, store)
	}
}
