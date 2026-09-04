package org.continuouspath.justtype

import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.KEY_LAYOUT_MODE
import org.continuouspath.justtype.Constants.MODE_ALPHA
import org.continuouspath.justtype.Constants.MODE_OPT
import org.continuouspath.justtype.ime.IMEState
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * Smoke tests: verify JustTypeIME survives early lifecycle methods without
 * crashing from uninitialized lateinit properties.
 *
 * These catch regressions where extracted subsystems are accessed before
 * onCreateInputView has run (the lateinit initialization boundary).
 */
@RunWith(RobolectricTestRunner::class)
class JustTypeIMELifecycleTest {

	private var controller: ServiceController<JustTypeIME>? = null

	private fun buildIme(): JustTypeIME {
		// serviceScope is cancelled in JustTypeIME.onDestroy(); without
		// tearing the service down, every test leaks coroutines into the
		// shared JVM fork and they accumulate across the suite (eventually
		// pinning Dispatchers.Default workers and starving Robolectric's
		// main thread).
		val c = Robolectric.buildService(JustTypeIME::class.java).create()
		controller = c
		return c.get()
	}

	@After
	fun tearDown() {
		val ime = controller?.get()
		controller?.destroy()
		// The background init is blocking DB work that cancellation can't interrupt;
		// left running, it reads this test's deleted temp DBs and the uncaught
		// exception poisons the next runTest in the shared worker.
		ime?.jtuiInitJob?.let { job ->
			kotlinx.coroutines.runBlocking {
				kotlinx.coroutines.withTimeoutOrNull(10_000) { job.join() }
			}
		}
		controller = null
		SettingsRepository.resetInstanceForTesting()
	}

	@Test
	fun `onCreate does not throw`() {
		buildIme()
	}

	@Test
	fun `onCreate then onStartInput does not throw`() {
		val ime = buildIme()
		// onStartInput runs before onCreateInputView on first launch and on every
		// new input session — it must not access lateinit vars from onCreateInputView.
		ime.onStartInput(EditorInfo(), false)
	}

	@Test
	fun `onCreate then onFinishInput does not throw`() {
		val ime = buildIme()
		ime.onFinishInput()
	}

	@Test
	fun `onWindowShown before onCreateInputView does not throw`() {
		val ime = buildIme()
		ime.onWindowShown()
	}

	@Test
	fun `onWindowHidden before onCreateInputView does not throw`() {
		val ime = buildIme()
		ime.onWindowHidden()
	}

	@Test
	fun `onTrimMemory before onCreateInputView does not throw`() {
		val ime = buildIme()
		ime.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
	}

	@Test
	fun `onUpdateSelection before onCreateInputView does not throw`() {
		val ime = buildIme()
		ime.onUpdateSelection(0, 0, 1, 1, -1, -1)
	}

	@Test
	fun `onCreateInputView does not throw`() {
		val ime = buildIme()
		val view = ime.onCreateInputView()
		assertThat(view).isNotNull()
	}

	// Regression: rebuilds (rotation, critical-setting recreation) replace the
	// OverlayCoordinator; the old instance must be destroyed first or its system
	// windows (TSS bar + touch overlay) orphan and survive IME close.
	@Test
	fun `onCreateInputView twice tears down and rebuilds safely`() {
		val ime = buildIme()
		ime.onCreateInputView()
		val second = ime.onCreateInputView()
		assertThat(second).isNotNull()
	}

	// Regression: a settings write fires listeners synchronously and must not crash
	// when JTUI hasn't been constructed yet. PreferenceCoordinator's on-create listener
	// routes layout-mode and other changes through getJtui(), which must short-circuit
	// before any access reaches uninitialized lateinit fields (wordDb, etc.).
	@Test
	fun `setting change before onCreateInputView does not crash`() {
		buildIme()
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())

		// Toggle the value to guarantee a listener fire.
		val current = repo.getString(KEY_LAYOUT_MODE, MODE_OPT)
		val next = if (current == MODE_OPT) MODE_ALPHA else MODE_OPT
		repo.putString(KEY_LAYOUT_MODE, next)
	}

	// Regression: onCreateInputView replaces the coordinator's onCreate listener with a
	// per-key one. Critical keys without a dedicated branch there (e.g. show_accented_keys
	// written by SettingsActivity) were silently dropped — the change only took effect
	// after an IME process restart. The fallthrough must schedule critical handling.
	@Test
	fun `critical setting write after onCreateInputView schedules critical handling`() {
		val ime = buildIme()
		ime.onCreateInputView()
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())

		val jobField = JustTypeIME::class.java.getDeclaredField("pendingCriticalChangeJob")
		jobField.isAccessible = true
		jobField.set(ime, null)

		val current = repo.getBoolean(Constants.KEY_SHOW_ACCENTED_KEYS, true)
		repo.putBoolean(Constants.KEY_SHOW_ACCENTED_KEYS, !current)

		assertThat(jobField.get(ime)).isNotNull()
	}

	// Regression: the landscape history-placement pref swaps which container the layout
	// controller wires, which only happens in onCreateInputView — so it must be in the
	// critical set and reach the debounced rebuild via the fallthrough.
	@Test
	fun `history placement write after onCreateInputView schedules critical handling`() {
		val ime = buildIme()
		ime.onCreateInputView()
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())

		val jobField = JustTypeIME::class.java.getDeclaredField("pendingCriticalChangeJob")
		jobField.isAccessible = true
		jobField.set(ime, null)

		val current = repo.getBoolean(Constants.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE, true)
		repo.putBoolean(Constants.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE, !current)

		assertThat(jobField.get(ime)).isNotNull()
	}

	// Regression: PreferenceCoordinatorCallbacks' narrow JTUI overrides must no-op
	// when imeState is Loading. Loading covers both "JTUI not yet constructed" and
	// "constructed but background init (wordDb, customDb, vocab) hasn't completed".
	// A synchronous listener fire in that window previously crashed on `wordDb`
	// lateinit access via `it.layoutMode = mode`. After 3.11, `getJtui()` no longer
	// exists on the callback interface — instead, the narrow setLayoutMode override
	// gates internally on `imeState as? IMEState.Ready`.
	@Test
	fun `setLayoutMode does not crash when imeState is Loading`() {
		val ime = buildIme()
		ime.onCreateInputView()
		JustTypeIME::class.java.getDeclaredField("imeState").apply {
			isAccessible = true
			set(ime, IMEState.Loading)
		}

		val coordField = JustTypeIME::class.java.getDeclaredField("preferenceCoordinator")
		coordField.isAccessible = true
		val coordinator = coordField.get(ime)
		val callbacksField = coordinator.javaClass.getDeclaredField("callbacks")
		callbacksField.isAccessible = true
		val callbacks = callbacksField.get(coordinator)
		val setLayoutMode = callbacks.javaClass.getMethod(
			"setLayoutMode",
			org.continuouspath.justtype.logic.LayoutMode::class.java,
		)

		// Must not throw — listener fires in the Loading window must be safe.
		setLayoutMode.invoke(callbacks, org.continuouspath.justtype.logic.LayoutMode.Optimized)
	}
}
