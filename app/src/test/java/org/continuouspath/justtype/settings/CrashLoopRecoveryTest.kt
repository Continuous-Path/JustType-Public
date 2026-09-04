package org.continuouspath.justtype.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashLoopRecoveryTest {

	private lateinit var repo: SettingsRepository

	@Before fun setUp() {
		val context: Context = ApplicationProvider.getApplicationContext()
		SettingsRegistry.reinitialize(context)
		repo = SettingsRepository.getInstance(context)
		repo.clearForTesting()
	}

	@After fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	@Test fun `single crash does not trigger recovery`() {
		var ran = false
		val looped = CrashLoopRecovery.record(repo, crashTime = 1_000L) { ran = true }
		assertThat(looped).isFalse()
		assertThat(ran).isFalse()
		assertThat(repo.getInt(Constants.KEY_CRASH_RECOVERY_COUNT, 0)).isEqualTo(1)
	}

	@Test fun `third crash within the window triggers recovery and resets the counter`() {
		val base = 1_000L
		CrashLoopRecovery.record(repo, base) {}
		CrashLoopRecovery.record(repo, base + 10_000L) {}
		var ran = false
		val looped = CrashLoopRecovery.record(repo, base + 20_000L) { ran = true }

		assertThat(looped).isTrue()
		assertThat(ran).isTrue()
		// Window reset after recovery so the next crash starts a fresh burst.
		assertThat(repo.getInt(Constants.KEY_CRASH_RECOVERY_COUNT, -1)).isEqualTo(0)
		assertThat(repo.getLong(Constants.KEY_CRASH_RECOVERY_WINDOW_START, -1L)).isEqualTo(0L)
	}

	@Test fun `a crash outside the window starts a fresh burst, not recovery`() {
		val base = 1_000L
		CrashLoopRecovery.record(repo, base) {}
		CrashLoopRecovery.record(repo, base + 10_000L) {}
		var ran = false
		// Third crash lands well past the 60s window from the burst start → count restarts at 1.
		val looped = CrashLoopRecovery.record(repo, base + CrashLoopRecovery.CRASH_LOOP_WINDOW_MS + 1_000L) { ran = true }

		assertThat(looped).isFalse()
		assertThat(ran).isFalse()
		assertThat(repo.getInt(Constants.KEY_CRASH_RECOVERY_COUNT, 0)).isEqualTo(1)
	}

	@Test fun `navSafeMode quiets the overlay and forces Direct Selection`() {
		repo.putBoolean(Constants.KEY_NAVIGATION_OVERLAY_REQUESTED, true)

		CrashLoopRecovery.navSafeMode(repo)

		assertThat(repo.getBoolean(Constants.KEY_NAVIGATION_OVERLAY_REQUESTED, true)).isFalse()
		assertThat(repo.getString(Constants.KEY_INPUT_METHOD_PRIMARY, "x")).isEqualTo(Constants.INPUT_METHOD_NONE)
		assertThat(repo.getBoolean(Constants.KEY_INPUT_METHOD_DIRECT_SELECTION_ENABLED, false)).isTrue()
	}
}
