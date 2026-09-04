package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpanishRegionTest {

	@Test fun `recognizes vosotros verb forms across tenses`() {
		listOf(
			"habláis", "tenéis", "estáis", "habéis", "sabéis", // present (-áis/-éis)
			"sois", "vais", "veis", "dais", // irregular present
			"teníais", "podríais", "deberíais", // imperfect / conditional (-íais)
			"hablabais", "estabais", "ibais", "erais", // imperfect (-abais + irregular)
			"hablasteis", "comisteis", "fuisteis", "dijisteis", // preterite (-asteis/-isteis)
		).forEach { assertThat(SpanishRegion.isVosotrosForm(it)).isTrue() }
	}

	@Test fun `does not flag non-vosotros words (no false positives)`() {
		listOf(
			"hablan", "tienen", "están", "hablaron", // the ustedes forms it competes with
			"país", "seis", "después", "inglés", "francés", "además", "quizás", "estás", // tricky look-alikes
			"casa", "general", "corazón", "niño", "café", // ordinary vocabulary
		).forEach { assertThat(SpanishRegion.isVosotrosForm(it)).isFalse() }
	}

	@Test fun `Tier 1 - ustedes regions demote vosotros forms - Castilian and neutral keep them`() {
		val vos = "tenéis"
		val plain = ClassMasks.CLASS_JUSTTYPE_MASK // no lexical skew bits
		assertThat(SpanishRegion.demote(vos, plain, SpanishRegion.MEXICAN)).isTrue()
		assertThat(SpanishRegion.demote(vos, plain, SpanishRegion.LATAM)).isTrue()
		assertThat(SpanishRegion.demote(vos, plain, SpanishRegion.CASTILIAN)).isFalse() // vosotros is native there
		assertThat(SpanishRegion.demote(vos, plain, SpanishRegion.ANY)).isFalse() // neutral: no preference
	}

	@Test fun `non-vosotros, region-neutral words are never demoted`() {
		val plain = ClassMasks.CLASS_JUSTTYPE_MASK
		listOf(SpanishRegion.ANY, SpanishRegion.CASTILIAN, SpanishRegion.MEXICAN, SpanishRegion.LATAM).forEach { r ->
			assertThat(SpanishRegion.demote("tienen", plain, r)).isFalse()
			assertThat(SpanishRegion.demote("país", plain, r)).isFalse()
		}
	}

	@Test fun `Tier 2 - lexical skew (classMask bits) demotes by region`() {
		val esWord = ClassMasks.CLASS_JUSTTYPE_MASK or ClassMasks.CLASS_REGION_ES_SKEW_MASK // e.g. ordenador
		val laWord = ClassMasks.CLASS_JUSTTYPE_MASK or ClassMasks.CLASS_REGION_LA_SKEW_MASK // e.g. computadora
		// ustedes regions sink Spain-preferred words, keep LatAm-preferred
		assertThat(SpanishRegion.demote("ordenador", esWord, SpanishRegion.MEXICAN)).isTrue()
		assertThat(SpanishRegion.demote("computadora", laWord, SpanishRegion.MEXICAN)).isFalse()
		// Castilian sinks LatAm-preferred, keeps Spain-preferred
		assertThat(SpanishRegion.demote("computadora", laWord, SpanishRegion.CASTILIAN)).isTrue()
		assertThat(SpanishRegion.demote("ordenador", esWord, SpanishRegion.CASTILIAN)).isFalse()
		// neutral: nothing sinks
		assertThat(SpanishRegion.demote("ordenador", esWord, SpanishRegion.ANY)).isFalse()
		assertThat(SpanishRegion.demote("computadora", laWord, SpanishRegion.ANY)).isFalse()
	}
}
