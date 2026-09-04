package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Root-letter hint caches must drop when the trie gains a custom word: the
 * added word's first letter appears in the next hint snapshot without re-init.
 */
@RunWith(RobolectricTestRunner::class)
class RootHintCustomWordTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@After fun tearDown() {
		h.tearDown()
	}

	private fun hintsNow(): Set<Char> {
		jtui.forceUpdateUi()
		return h.lastSnapshot!!.nextLetterHints
	}

	@Test fun `adding a custom word refreshes the hints without re-init`() {
		h = TestJtui(tmpDir.root) { r ->
			// Custom-words-only vocab: the hint set starts empty, so the added
			// word's letter is observable against the cached snapshot.
			r.putBoolean(Constants.KEY_VOCAB_INCLUDE_JUSTTYPE, false)
			r.putBoolean(Constants.KEY_VOCAB_INCLUDE_PHRASES, false)
			r.putString(Constants.KEY_SPELL_DIACRITIC_SCOPE, Constants.DIACRITIC_SCOPE_ALL)
		}
		jtui.showNextLetterHints = true
		assertThat(hintsNow()).doesNotContain('U')

		// ADD NEW WORD: spell "u" and DONE — the commit path that invalidates
		// the root-hint caches alongside wld.addCustomWord.
		jtui.setCurrentPage("Navigation")
		jtui.buttonPressed(0) // → Spelling (accumulate)
		jtui.buttonPressed(4) // LUFCY → Spell4
		jtui.buttonPressed(2) // u variants → drill page
		jtui.buttonPressed(0) // base 'u'
		jtui.buttonPressed(6) // DONE: adds the word, returns to Main

		assertThat(hintsNow()).contains('U')
	}
}
