package org.continuouspath.justtype.logic

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.activity.DeveloperSettingsActivity
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Family expansion (sls.md "family expansion", Cliff 2026-08-13, round 2):
 * Select-then-pause on a long word inserts pale-blue WHOLE page groups of
 * vocabulary words sharing the word's letter stem, directly after the
 * paused row (slot-1 pause -> group at slot 2). Its own enable + delay
 * (Language Options), independent of the capitalized-forms delay; only
 * fires once the user has typed keys; English/Spanish only.
 */
@RunWith(RobolectricTestRunner::class)
class FamilyExpansionTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	private val delayMs = 1000

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { r ->
			r.putBoolean(Constants.KEY_FAMILY_EXPAND_ENABLED, true)
			r.putInt(Constants.KEY_FAMILY_EXPAND_DELAY_MS, delayMs)
			r.putInt(DeveloperSettingsActivity.KEY_FAMILY_EXPAND_MIN_KR, 2)
			r.putInt(DeveloperSettingsActivity.KEY_FAMILY_EXPAND_STEM_BACKOFF, 2)
			// Capitalized-forms delay stays OFF: family expansion carries its
			// own delay and must not depend on the case feature (Cliff, (6)).
			r.putInt(Constants.KEY_CASETYPE_EXPAND_DELAY_MS, 0)
		}
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "English")
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)
	private val selectPos = 6

	private fun type(word: String, keyCount: Int = Int.MAX_VALUE) {
		jtui.wordKeySequence(word)!!.take(keyCount).forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
	}

	private fun list() = jtui.selectionListForTest()

	private fun familyRows() = list().withIndex().filter { it.value["familyExpand"] == true }

	/** Selects row 0 and lets the family pause elapse. Returns the paused-on word. */
	private fun selectAndPause(): String {
		jtui.buttonPressed(selectPos)
		val word = (list()[0]["output"] as? String).orEmpty().lowercase()
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(delayMs + 100L))
		return word
	}

	@Test fun `pause on slot 1 inserts whole family pages at slot 2`() {
		type("understand", keyCount = 6)
		assertThat(familyRows()).isEmpty()
		val word = selectAndPause()
		val fam = familyRows()
		assertThat(fam).isNotEmpty()
		// Directly after the paused row (Cliff round-2 (2)) and contiguous.
		assertThat(fam.first().index).isEqualTo(1)
		assertThat(fam.last().index - fam.first().index).isEqualTo(fam.size - 1)
		// WHOLE pages: pads fill the tail so no ordinary candidate is absorbed
		// into the family window (the "satellite" mixing artifact, round-2 (3)).
		assertThat(fam.size % 6).isEqualTo(0)
		val stem = word.dropLast(2)
		val members = fam.filter { it.value["familyPad"] != true }
			.map { (it.value["output"] as? String).orEmpty().lowercase() }
		members.forEach { out ->
			assertThat(out).startsWith(stem)
			assertThat(out).isNotEqualTo(word)
		}
		// Round-4 ordering (Cliff's "possible" catch): blocks by DESCENDING
		// common-prefix length with the paused word, alphabetical within —
		// closest relatives first, "starts with poss…" words later.
		val keys = members.map { it.commonPrefixWith(word).length to it }
		assertThat(keys.map { -it.first }).isInOrder()
		keys.groupBy({ it.first }, { it.second }).values.forEach { block ->
			assertThat(block).isInOrder()
		}
	}

	@Test fun `selection survives the insertion and an ambiguous key abandons the group`() {
		type("understand", keyCount = 6)
		selectAndPause()
		assertThat(jtui.selectionListForTest().any { it["familyExpand"] == true }).isTrue()
		assertThat(h.lastSnapshot!!.currentSelectionIndex).isEqualTo(0)
		// AK-after-SEL commits the selected word and rebuilds — group gone.
		type("and", keyCount = 1)
		assertThat(familyRows()).isEmpty()
	}

	@Test fun `family expansion fires on its own delay with case expansion off`() {
		// setUp keeps the capitalized-forms delay at 0 (immediate mode): the
		// family timer is independent (round-2 (6)).
		type("understand", keyCount = 6)
		selectAndPause()
		assertThat(familyRows()).isNotEmpty()
	}

	@Test fun `sparse stem broadens until the page fills - adaptive backoff`() {
		// Backoff 0 = strict continuations of the paused word, usually a
		// sparse family (the "differentiate + differential amid four empty
		// slots" report). The adaptive fill shortens the stem one letter at
		// a time until a full page of relatives exists; the affinity sort
		// keeps the tight family first.
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putInt(org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_FAMILY_EXPAND_STEM_BACKOFF, 0)
		type("understand", keyCount = 6)
		selectAndPause()
		val members = familyRows().filter { it.value["familyPad"] != true }
		assertThat(members.size).isAtLeast(6)
	}

	@Test fun `stem never drops below the typed keys - no contradicting members`() {
		// Backoff 8 on "understand" typed to 6 keys would start the stem at
		// 2 letters ("un") — admitting words like "unable" whose keys
		// CONTRADICT keystrokes 3-6. The typed-length floor clamps the stem
		// to the first 6 letters, so every member extends the typed keys.
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putInt(org.continuouspath.justtype.activity.DeveloperSettingsActivity.KEY_FAMILY_EXPAND_STEM_BACKOFF, 8)
		type("understand", keyCount = 6)
		val word = selectAndPause()
		val members = familyRows().filter { it.value["familyPad"] != true }
			.map { (it.value["output"] as? String).orEmpty().lowercase() }
		assertThat(members).isNotEmpty()
		val typedPrefix = word.take(6)
		members.forEach { assertThat(it).startsWith(typedPrefix) }
	}

	@Test fun `disabled switch never inserts`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putBoolean(Constants.KEY_FAMILY_EXPAND_ENABLED, false)
		type("understand", keyCount = 6)
		selectAndPause()
		assertThat(familyRows()).isEmpty()
	}

	@Test fun `short words below the remaining-keys floor do not expand`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putInt(DeveloperSettingsActivity.KEY_FAMILY_EXPAND_MIN_KR, 8)
		type("understand", keyCount = 6)
		selectAndPause()
		assertThat(familyRows()).isEmpty()
	}

	@Test fun `no expansion before any key is typed - resting menus stay plain`() {
		// Zero-K window at BOS: entries meet the keys-remaining floor
		// trivially, but nothing has been typed — no trigger (round-2 (4)).
		jtui.forceUpdateUi()
		jtui.buttonPressed(selectPos)
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(delayMs + 500L))
		assertThat(familyRows()).isEmpty()
	}
}
