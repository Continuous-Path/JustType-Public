package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [EnglishRegion] soft-demotes the other variety's spelling. Both spellings stay in the DB
 * whichever region is chosen, so this only ever changes ranking — a demoted word is still
 * reachable, which matters when a user quotes text in the other variety.
 */
class EnglishRegionTest {

	private val gb = ClassMasks.CLASS_REGION_GB_SKEW_MASK
	private val us = ClassMasks.CLASS_REGION_US_SKEW_MASK
	private val neutral = ClassMasks.CLASS_JUSTTYPE_MASK

	@Test fun `no preference demotes nothing`() {
		for (mask in listOf(gb, us, neutral)) {
			assertThat(EnglishRegion.demote(mask, EnglishRegion.ANY)).isFalse()
		}
	}

	@Test fun `a British user sinks American spellings and vice versa`() {
		assertThat(EnglishRegion.demote(us, EnglishRegion.UK)).isTrue()
		assertThat(EnglishRegion.demote(gb, EnglishRegion.UK)).isFalse()

		assertThat(EnglishRegion.demote(gb, EnglishRegion.US)).isTrue()
		assertThat(EnglishRegion.demote(us, EnglishRegion.US)).isFalse()
	}

	@Test fun `region-neutral words are never demoted`() {
		assertThat(EnglishRegion.demote(neutral, EnglishRegion.UK)).isFalse()
		assertThat(EnglishRegion.demote(neutral, EnglishRegion.US)).isFalse()
	}

	@Test fun `the region bits do not collide with the offensive-level bits`() {
		val offensive = ClassMasks.CLASS_OFFENSIVE_MASK or ClassMasks.CLASS_POTENTIALLY_OFFENSIVE_MASK
		assertThat(gb and offensive).isEqualTo(0L)
		assertThat(us and offensive).isEqualTo(0L)
		// ...nor with the Spanish pair, so a word may legitimately carry both languages' tags.
		val spanish = ClassMasks.CLASS_REGION_ES_SKEW_MASK or ClassMasks.CLASS_REGION_LA_SKEW_MASK
		assertThat((gb or us) and spanish).isEqualTo(0L)
	}

	@Test fun `an offensive-tagged word is still demotable by region`() {
		val britishAndOffensive = gb or ClassMasks.CLASS_OFFENSIVE_MASK
		assertThat(EnglishRegion.demote(britishAndOffensive, EnglishRegion.US)).isTrue()
	}
}
