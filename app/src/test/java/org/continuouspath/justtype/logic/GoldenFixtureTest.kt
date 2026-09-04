package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Replays golden/key_sequences.json against the current engine: every fixture's candidate
 * list must be reproduced EXACTLY (contents and order) along with its termination code.
 * A failure here means engine behavior changed — either fix the regression or consciously
 * regenerate via [GoldenFixtureGenerator] and review the diff.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenFixtureTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private fun loadFixtures(): List<GoldenFixtures.GoldenFixture> {
		val text = javaClass.classLoader!!.getResource(GoldenFixtures.RESOURCE_PATH)!!.readText()
		return GoldenFixtures.json.decodeFromString(text)
	}

	@Test fun `engine reproduces every golden candidate list exactly`() {
		val fixtures = loadFixtures()
		assertThat(fixtures.size).isAtLeast(20)

		val wlds = fixtures.map { it.layout }.distinct().associateWith { layout ->
			GoldenFixtures.buildWld(layout, WordDb.openStandalone(File(tmpDir.root, "golden-$layout.db")))
		}
		fixtures.forEachIndexed { idx, fixture ->
			val result = GoldenFixtures.run(wlds.getValue(fixture.layout), fixture)
			val label = "fixture[$idx] layout=${fixture.layout} keys=${fixture.keys} params=${fixture.params}"
			assertWithMessage(label)
				.that(result.candidates.map { it.lowerWord })
				.containsExactlyElementsIn(fixture.expected)
				.inOrder()
			assertWithMessage("$label termination")
				.that(result.termination)
				.isEqualTo(fixture.termination)
		}
	}

	@Test fun `fixtures cover both layouts and at least one filtered variation`() {
		val fixtures = loadFixtures()
		assertThat(fixtures.map { it.layout }.distinct())
			.containsExactly(GoldenFixtures.LAYOUT_OPTIMIZED, GoldenFixtures.LAYOUT_ALPHABETICAL)
		assertThat(fixtures.any { it.params.minFreqClass != null }).isTrue()
		assertThat(fixtures.any { it.params.includeExcludedAtEnd }).isTrue()
	}
}
