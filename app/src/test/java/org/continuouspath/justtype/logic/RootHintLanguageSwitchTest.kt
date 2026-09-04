package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Root-letter hints are trie state and must not survive a language switch:
 * the hint caches are keyed only by the vocab masks (identical across
 * languages), so the Vietnamese hint set — which legitimately lacks
 * f/j/w/z, the four letters absent from the Vietnamese alphabet — was
 * served on the English layout after a switch (Cliff, 2026-08-10).
 */
@RunWith(RobolectricTestRunner::class)
class RootHintLanguageSwitchTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val repo get() = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())

	@After fun tearDown() {
		h.tearDown()
	}

	private fun hintsNow(): Set<Char> {
		jtui.forceUpdateUi()
		return h.lastSnapshot!!.nextLetterHints
	}

	@Test fun `hints refresh when switching from Vietnamese to English`() {
		h = TestJtui(tmpDir.root) { r ->
			r.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
		}
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "TiengViet")
		jtui.init()
		jtui.showNextLetterHints = true
		val vnHints = hintsNow()
		assertThat(vnHints).isNotEmpty()
		// Vietnamese words never start with these — correctly absent here.
		assertThat(vnHints).containsNoneOf('F', 'J', 'W', 'Z')

		// Switch to English (rebuilds the trie) — the hints must follow.
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "English")
		jtui.init()
		jtui.showNextLetterHints = true
		val enHints = hintsNow()
		assertThat(enHints).containsAtLeast('F', 'J', 'W', 'Z')
	}
}
