package org.continuouspath.justtype.logic

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Characterization of candidate RANKING (Phase 0.1). The sort itself lives above WLD in
 * JTUI.computeSortMetrics, so this test drives the public path: seed words with controlled
 * freqClass/useCount/useTime into the word DB, press ambiguous keys on the Main page, and
 * assert the relative order in the selection list. Seeded words are nonsense letter runs
 * confined to a single ambiguous key so real dictionary words can't perturb the comparison.
 *
 * Locked-in ordering (all else equal): lower freqClass first; then higher useCount first;
 * then more recent last-use first.
 */
@RunWith(RobolectricTestRunner::class)
class CandidateRankingCharacterizationTest {

	@get:Rule val tmpDir = TemporaryFolder()

	// Main page (Optimized): button 0 = GEMZ (key 0), button 5 = BANQ (key 4).
	private val btnGemz = 0
	private val btnBanq = 5

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { _ ->
			seedControlledWords(ApplicationProvider.getApplicationContext())
		}
	}

	@After fun tearDown() {
		h.tearDown()
	}

	/**
	 * Seeds the active English DB (before JTUI.init() loads it into the trie):
	 * - freqClass trio (never used): "zemgz"=2, "zemge"=5, "zemgm"=9 — all key-0 letters.
	 * - useCount pair (same freqClass, both used just now): "qabnb"×6 vs "qabnn"×1.
	 * - recency pair (same freqClass, same useCount): "nqbanb" used now, "nqbanq" aged 40 days.
	 * jtStartTime is rewound 90 days so relative-time recency classes can actually differ.
	 */
	private fun seedControlledWords(app: Context) {
		val seedDb = WordDb.open(tmpDir.root, app.assets)
		seedDb.getOrCreateStats("zemgz", defaultFreqClass = 2)
		seedDb.getOrCreateStats("zemge", defaultFreqClass = 5)
		seedDb.getOrCreateStats("zemgm", defaultFreqClass = 9)
		val heavyUse = seedDb.getOrCreateStats("qabnb", defaultFreqClass = 5).wordID
		val lightUse = seedDb.getOrCreateStats("qabnn", defaultFreqClass = 5).wordID
		repeat(6) { seedDb.markWordUsedByID(heavyUse) }
		seedDb.markWordUsedByID(lightUse)
		val recentUse = seedDb.getOrCreateStats("nqbanb", defaultFreqClass = 5).wordID
		val staleUse = seedDb.getOrCreateStats("nqbanq", defaultFreqClass = 5).wordID
		seedDb.markWordUsedByID(recentUse)
		seedDb.markWordUsedByID(staleUse)
		seedDb.close()

		val now = System.currentTimeMillis()
		val day = 24L * 60 * 60 * 1000
		val dbFile = File(tmpDir.root, WordDb.activeDbName())
		val raw = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
		raw.use {
			it.execSQL(
				"UPDATE metadata SET value = ? WHERE `key` = 'jtStartTime'",
				arrayOf<Any>((now - 90 * day).toString()),
			)
			it.execSQL(
				"UPDATE words SET useTime = ? WHERE word = 'nqbanq'",
				arrayOf<Any>(now - 40 * day),
			)
		}
	}

	/** Presses [button] [times] times and returns seeded words in selection-list order. */
	private fun selectionOrderOf(button: Int, times: Int, words: Set<String>): List<String> {
		repeat(times) { jtui.buttonPressed(button) }
		return jtui.getSelectionOutputs()
			.map { it.lowercase() }
			.filter { it in words }
			.distinct() // case-variant display entries collapse to one slot per word
	}

	@Test fun `lower freqClass ranks first when useCount and recency are equal`() {
		val order = selectionOrderOf(btnGemz, times = 5, words = setOf("zemgz", "zemge", "zemgm"))
		assertThat(order).containsExactly("zemgz", "zemge", "zemgm").inOrder()
	}

	@Test fun `higher useCount ranks first when freqClass and recency are equal`() {
		val order = selectionOrderOf(btnBanq, times = 5, words = setOf("qabnb", "qabnn"))
		assertThat(order).containsExactly("qabnb", "qabnn").inOrder()
	}

	@Test fun `more recent use ranks first when freqClass and useCount are equal`() {
		val order = selectionOrderOf(btnBanq, times = 6, words = setOf("nqbanb", "nqbanq"))
		assertThat(order).containsExactly("nqbanb", "nqbanq").inOrder()
	}
}
