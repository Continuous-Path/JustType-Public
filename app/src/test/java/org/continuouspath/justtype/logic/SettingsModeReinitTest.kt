package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression: applying a reinit-class setting (Typing Language, Letter Arrangement) from the
 * keyboard's Settings Mode triggers `JTUI.init()` / the layoutMode setter, which used to reset
 * `currentPage` to the typing keyboard while `isInSettingsMode` stayed true — stranding the user
 * with the settings overlay visible but no settings keys to navigate or exit with (key routing
 * requires BOTH isInSettingsMode and a "Settings*" currentPage).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsModeReinitTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root, autoInit = false)
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	@Test fun `reinit during settings mode keeps the Settings keyboard page`() {
		jtui.enterSettingsMode()
		assertThat(jtui.isInSettingsMode).isTrue()
		assertThat(jtui.getCurrentPage()).isEqualTo("Settings")

		// Simulate the Apply path for a reinit-class setting (e.g. Typing Language).
		jtui.init()

		assertThat(jtui.isInSettingsMode).isTrue()
		assertThat(jtui.getCurrentPage()).isEqualTo("Settings")
	}

	@Test fun `layout mode change during settings mode keeps the Settings keyboard page`() {
		jtui.enterSettingsMode()
		jtui.layoutMode = LayoutMode.Alphabetical // heavy setter runs updateKeysAndSelection

		assertThat(jtui.getCurrentPage()).isEqualTo("Settings")
	}

	@Test fun `reinit outside settings mode still resets to the starting page`() {
		jtui.enterSettingsMode()
		jtui.exitSettingsMode()
		assertThat(jtui.isInSettingsMode).isFalse()

		jtui.init()

		assertThat(jtui.getCurrentPage()).isEqualTo("Main")
	}
}
