package org.continuouspath.justtype.ime

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.INPUT_METHOD_HEAD_TRACKING
import org.continuouspath.justtype.Constants.INPUT_METHOD_NONE
import org.continuouspath.justtype.Constants.KEY_CRASH_RECOVERY_COUNT
import org.continuouspath.justtype.Constants.KEY_CRASH_RECOVERY_WINDOW_START
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_SWIPE_DISTANCE_DP
import org.continuouspath.justtype.Constants.KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED
import org.continuouspath.justtype.Constants.KEY_INPUT_METHOD_PRIMARY
import org.continuouspath.justtype.Constants.KEY_KEYBOARD_SIZE_RATIO
import org.continuouspath.justtype.Constants.KEY_KEY_HISTORY_HEIGHT_DP
import org.continuouspath.justtype.Constants.KEY_KEY_HISTORY_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_MESSAGE
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_THREAD
import org.continuouspath.justtype.Constants.KEY_LAST_CRASH_TIME
import org.continuouspath.justtype.Constants.KEY_LAST_RUN_VERSION
import org.continuouspath.justtype.Constants.KEY_LAST_SESSION_CRASHED
import org.continuouspath.justtype.Constants.KEY_LAST_UPDATE_TIME
import org.continuouspath.justtype.Constants.KEY_NEEDS_FULL_REINIT
import org.continuouspath.justtype.Constants.KEY_TSS_BUTTON_HEIGHT_DP
import org.continuouspath.justtype.Constants.KEY_TSS_BUTTON_HEIGHT_PERCENT
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_BUTTONS
import org.continuouspath.justtype.Constants.KEY_TSS_OVERLAY_MODE
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Characterization tests for [StartupManager].
 *
 * Locks down the package-update / crash-recovery / version-migration
 * behavior of [StartupManager.runStartupChecks]. Production code is
 * unchanged — these tests assert *current* behavior so future refactors
 * don't drift.
 */
@RunWith(RobolectricTestRunner::class)
class StartupManagerTest {

	private lateinit var repo: SettingsRepository
	private lateinit var subject: StartupManager
	private var currentVersion: Long = 0L

	@Before
	fun setUp() {
		val context = RuntimeEnvironment.getApplication()
		// applySafeKeyboardDefaults (in crash-loop recovery) resolves the size range from the registry.
		SettingsRegistry.getInstance(context)
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()
		subject = StartupManager(context)
		currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
	}

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	// ── Group 1 — Update / crash flag handling ──────────────────────────────

	@Test
	fun `KEY_NEEDS_FULL_REINIT set then run clears reinit and update time keys`() {
		repo.edit()
			.putBoolean(KEY_NEEDS_FULL_REINIT, true)
			.putLong(KEY_LAST_UPDATE_TIME, 12345L)
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.contains(KEY_NEEDS_FULL_REINIT)).isFalse()
		assertThat(repo.contains(KEY_LAST_UPDATE_TIME)).isFalse()
	}

	@Test
	fun `KEY_NEEDS_FULL_REINIT not set leaves update time key untouched`() {
		repo.edit().putLong(KEY_LAST_UPDATE_TIME, 99999L).commit()

		subject.runStartupChecks(repo)

		assertThat(repo.contains(KEY_LAST_UPDATE_TIME)).isTrue()
		assertThat(repo.getLong(KEY_LAST_UPDATE_TIME, 0)).isEqualTo(99999L)
	}

	@Test
	fun `KEY_LAST_SESSION_CRASHED set then run clears all crash keys`() {
		repo.edit()
			.putBoolean(KEY_LAST_SESSION_CRASHED, true)
			.putLong(KEY_LAST_CRASH_TIME, 11111L)
			.putString(KEY_LAST_CRASH_MESSAGE, "boom")
			.putString(KEY_LAST_CRASH_THREAD, "main")
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.contains(KEY_LAST_SESSION_CRASHED)).isFalse()
		assertThat(repo.contains(KEY_LAST_CRASH_TIME)).isFalse()
		assertThat(repo.contains(KEY_LAST_CRASH_MESSAGE)).isFalse()
		assertThat(repo.contains(KEY_LAST_CRASH_THREAD)).isFalse()
	}

	@Test
	fun `KEY_LAST_SESSION_CRASHED not set preserves crash detail keys`() {
		repo.edit()
			.putLong(KEY_LAST_CRASH_TIME, 22222L)
			.putString(KEY_LAST_CRASH_MESSAGE, "still here")
			.putString(KEY_LAST_CRASH_THREAD, "io")
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.contains(KEY_LAST_CRASH_TIME)).isTrue()
		assertThat(repo.getString(KEY_LAST_CRASH_MESSAGE, "")).isEqualTo("still here")
		assertThat(repo.getString(KEY_LAST_CRASH_THREAD, "")).isEqualTo("io")
	}

	// ── Group 1b — Crash-loop recovery ──────────────────────────────────────

	@Test
	fun `single crash does not change the input method`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion) // isolate from migrations
			.putBoolean(KEY_LAST_SESSION_CRASHED, true)
			.putLong(KEY_LAST_CRASH_TIME, 5_000L)
			.putString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_HEAD_TRACKING)
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.getString(KEY_INPUT_METHOD_PRIMARY, "")).isEqualTo(INPUT_METHOD_HEAD_TRACKING)
		assertThat(repo.getInt(KEY_CRASH_RECOVERY_COUNT, -1)).isEqualTo(1)
	}

	@Test
	fun `third crash within the window forces Direct Selection safe mode`() {
		val base = 1_000_000L
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion)
			.putBoolean(KEY_LAST_SESSION_CRASHED, true)
			.putLong(KEY_LAST_CRASH_TIME, base + 1_000L) // within 60s of the burst start
			.putInt(KEY_CRASH_RECOVERY_COUNT, 2)
			.putLong(KEY_CRASH_RECOVERY_WINDOW_START, base)
			.putString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_HEAD_TRACKING)
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.getString(KEY_INPUT_METHOD_PRIMARY, "x")).isEqualTo(INPUT_METHOD_NONE)
		assertThat(repo.getBoolean(KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, false)).isTrue()
		assertThat(repo.getInt(KEY_CRASH_RECOVERY_COUNT, -1)).isEqualTo(0)
	}

	@Test
	fun `crash outside the window restarts the burst without fallback`() {
		val base = 1_000_000L
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion)
			.putBoolean(KEY_LAST_SESSION_CRASHED, true)
			.putLong(KEY_LAST_CRASH_TIME, base + 200_000L) // > 60s after the burst start
			.putInt(KEY_CRASH_RECOVERY_COUNT, 2)
			.putLong(KEY_CRASH_RECOVERY_WINDOW_START, base)
			.putString(KEY_INPUT_METHOD_PRIMARY, INPUT_METHOD_HEAD_TRACKING)
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.getInt(KEY_CRASH_RECOVERY_COUNT, -1)).isEqualTo(1)
		assertThat(repo.getString(KEY_INPUT_METHOD_PRIMARY, "")).isEqualTo(INPUT_METHOD_HEAD_TRACKING)
	}

	// ── Group 2 — Version migration trigger ─────────────────────────────────

	@Test
	fun `version change triggers performMigrations`() {
		// Force "stored != current" by setting stored to a different value.
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion - 1)
			.putFloat(KEY_KEY_HISTORY_HEIGHT_DP, 96f)
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.contains(KEY_KEY_HISTORY_HEIGHT_PERCENT)).isTrue()
	}

	@Test
	fun `same stored and current version skips migrations`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion)
			.putFloat(KEY_KEY_HISTORY_HEIGHT_DP, 96f)
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.contains(KEY_KEY_HISTORY_HEIGHT_PERCENT)).isFalse()
	}

	@Test
	fun `runStartupChecks updates KEY_LAST_RUN_VERSION to current`() {
		repo.edit().putLong(KEY_LAST_RUN_VERSION, currentVersion - 1).commit()

		subject.runStartupChecks(repo)

		assertThat(repo.getLong(KEY_LAST_RUN_VERSION, -1)).isEqualTo(currentVersion)
	}

	@Test
	fun `stored version greater than current updates version key but skips migrations`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion + 5)
			.putFloat(KEY_KEY_HISTORY_HEIGHT_DP, 96f)
			.commit()

		subject.runStartupChecks(repo)

		// Version key updated to current (storedVersion != currentVersion branch).
		assertThat(repo.getLong(KEY_LAST_RUN_VERSION, -1)).isEqualTo(currentVersion)
		// But migrations did NOT run (the storedVersion < currentVersion branch was false).
		assertThat(repo.contains(KEY_KEY_HISTORY_HEIGHT_PERCENT)).isFalse()
	}

	// ── Group 3 — Per-migration math ────────────────────────────────────────

	@Test
	fun `key history dp migrates to percent using keyboard ratio`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion - 1)
			.putFloat(KEY_KEY_HISTORY_HEIGHT_DP, 96f)
			.putFloat(KEY_KEYBOARD_SIZE_RATIO, 0.55f)
			.commit()

		subject.runStartupChecks(repo)

		val context = RuntimeEnvironment.getApplication()
		val dm = context.resources.displayMetrics
		val screenWidthDp = dm.widthPixels / dm.density
		val oneKeyHeightDp = (0.55f * screenWidthDp) / 3f
		val expected = if (oneKeyHeightDp > 0f) {
			(96f / oneKeyHeightDp).coerceIn(0.25f, 1.0f)
		} else {
			1.0f
		}
		assertThat(repo.getFloat(KEY_KEY_HISTORY_HEIGHT_PERCENT, -1f)).isWithin(0.0001f).of(expected)
	}

	@Test
	fun `swipe distance dp migrates to percent clamped between 2 and 20`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion - 1)
			.putInt(KEY_DIRECTIONAL_SELECTION_SWIPE_DISTANCE_DP, 100)
			.commit()

		subject.runStartupChecks(repo)

		val percent = repo.getInt(KEY_DIRECTIONAL_SELECTION_SWIPE_PERCENT, -1)
		assertThat(percent).isAtLeast(2)
		assertThat(percent).isAtMost(20)
	}

	@Test
	fun `tss button dp migrates to percent clamped between 5 and 100`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion - 1)
			.putInt(KEY_TSS_BUTTON_HEIGHT_DP, 48)
			.commit()

		subject.runStartupChecks(repo)

		val percent = repo.getInt(KEY_TSS_BUTTON_HEIGHT_PERCENT, -1)
		assertThat(percent).isAtLeast(5)
		assertThat(percent).isAtMost(100)
	}

	@Test
	fun `tss overlay buttons migrates to overlay mode preserving boolean value`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion - 1)
			.putBoolean(KEY_TSS_OVERLAY_BUTTONS, true)
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.contains(KEY_TSS_OVERLAY_MODE)).isTrue()
		assertThat(repo.getBoolean(KEY_TSS_OVERLAY_MODE, false)).isTrue()
	}

	@Test
	fun `tss overlay buttons false migrates to overlay mode false`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion - 1)
			.putBoolean(KEY_TSS_OVERLAY_BUTTONS, false)
			.commit()

		subject.runStartupChecks(repo)

		assertThat(repo.contains(KEY_TSS_OVERLAY_MODE)).isTrue()
		assertThat(repo.getBoolean(KEY_TSS_OVERLAY_MODE, true)).isFalse()
	}

	@Test
	fun `migration is skipped when target percent key already exists`() {
		repo.edit()
			.putLong(KEY_LAST_RUN_VERSION, currentVersion - 1)
			.putFloat(KEY_KEY_HISTORY_HEIGHT_DP, 96f)
			.putFloat(KEY_KEY_HISTORY_HEIGHT_PERCENT, 0.42f)
			.commit()

		subject.runStartupChecks(repo)

		// Pre-existing percent value is preserved.
		assertThat(repo.getFloat(KEY_KEY_HISTORY_HEIGHT_PERCENT, -1f)).isWithin(0.0001f).of(0.42f)
	}
}
