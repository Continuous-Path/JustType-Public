package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.SpanishRegion
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Spanish regional tags, pair-grounded (2026-08-11, the absolutamente;LA
 * cleanup): a tag survives generation only as half of a reciprocal lexical
 * variant pair (ordenador<->computadora), never as a regional SENSE label on a
 * universal word — the ES analog of the English only;GB incident. Verified at
 * the data level (classMask region bits on the built DB) and end-to-end
 * (typed neutral word heads its list under a region).
 */
@RunWith(RobolectricTestRunner::class)
class EspanolRegionTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui

	@Before fun setUp() {
		h = TestJtui(tmpDir.root)
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, "Espanol")
		repo.putString(Constants.KEY_WORD_SELECTION_MODE, Constants.WORD_SELECTION_LIST)
		jtui.init()
	}

	@After fun tearDown() {
		h.tearDown()
	}

	private val pagePos = intArrayOf(0, 2, 3, 4, 5, 7)

	private fun maskOf(word: String): Long {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		return WordDb.open(tmpDir.root, app.assets, "Espanol").use { db ->
			db.getOrCreateStats(word, 7).classMask
		}
	}

	@Test fun `variant pairs carry their region bits`() {
		assertThat(maskOf("ordenador") and ClassMasks.CLASS_REGION_ES_SKEW_MASK).isNotEqualTo(0L)
		assertThat(maskOf("zumo") and ClassMasks.CLASS_REGION_ES_SKEW_MASK).isNotEqualTo(0L)
		assertThat(maskOf("computadora") and ClassMasks.CLASS_REGION_LA_SKEW_MASK).isNotEqualTo(0L)
		assertThat(maskOf("carro") and ClassMasks.CLASS_REGION_LA_SKEW_MASK).isNotEqualTo(0L)
		assertThat(maskOf("celular") and ClassMasks.CLASS_REGION_LA_SKEW_MASK).isNotEqualTo(0L)
	}

	@Test fun `universal words carry no region bits - the sense-label contamination classes`() {
		val regionBits = ClassMasks.CLASS_REGION_ES_SKEW_MASK or ClassMasks.CLASS_REGION_LA_SKEW_MASK
		// Sense-label contaminants (absolutamente;LA class), polysemy near-misses
		// (esto, gato "jack", control "remote", camión "bus"), slang-sense words
		// (aceitar "bribe"), and both-bloc words (frijol, agua) — all neutral.
		for (w in listOf(
			"absolutamente", "esto", "gato", "control", "vez", "sitio",
			"profesor", "camión", "aceitar", "frijol", "agua", "coche",
		)) {
			assertThat(maskOf(w) and regionBits).isEqualTo(0L)
		}
	}

	@Test fun `typed universal word heads its list under a Latin-American region`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_SPANISH_REGION, SpanishRegion.LATAM)
		val keys = jtui.wordKeySequence("esto")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		val words = h.lastSnapshot!!.selectionListBuffers
			.joinToString("\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
		assertThat(words.first().lowercase()).isEqualTo("esto")
	}

	@Test fun `demoted variant stays listed - reachable, never deleted`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_SPANISH_REGION, SpanishRegion.LATAM)
		val keys = jtui.wordKeySequence("zumo")!!
		keys.forEach { jtui.buttonPressed(pagePos[it]) }
		jtui.forceUpdateUi()
		val words = h.lastSnapshot!!.selectionListBuffers
			.joinToString("\n").split("\n").map { it.trim().lowercase() }
		assertThat(words.any { it.startsWith("zumo") }).isTrue()
	}
}
