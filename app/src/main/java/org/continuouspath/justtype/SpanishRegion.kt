package org.continuouspath.justtype

/**
 * Spanish regional preference for word ranking. The pooled Spanish word DB contains forms from every
 * region (both Castilian `vosotros` and Rioplatense `voseo`, plus Spain/Latin-American lexical variants).
 * When the user picks a region, region-dispreferred forms are SOFT-DEMOTED in the ambiguous selection list
 * (sunk toward the end, never removed — a user can still type a form they deliberately want).
 *
 * Tier 1 (here) is rule-based morphology: `vosotros` verb forms are Peninsular, so they sink for the
 * `ustedes` regions (Mexican, Latin-American). `voseo` and lexical variants (coche/carro, …) are deferred
 * to Tier 2 (corpus-derived per-word tags), because they can't be detected by rule without demoting
 * legitimate words. The [demote] contract is the stable seam: Tier 2 will OR its per-word tag into it.
 *
 * Region values are the persisted `KEY_SPANISH_REGION` strings (ISO/BCP-47-style so they read cleanly).
 */
object SpanishRegion {
	const val ANY = "any" // pan-Hispanic: no regional preference (default; current behavior)
	const val CASTILIAN = "es-ES" // España / Castellano — uses vosotros
	const val MEXICAN = "es-MX" // México — ustedes, no vosotros
	const val LATAM = "es-419" // neutral Latin America — ustedes, no vosotros

	/**
	 * Endings that are (near-)exclusively `vosotros` verb forms — present (-áis/-éis), imperfect (-abais/
	 * -íais), preterite (-asteis/-isteis), and the future/conditional/subjunctive that collapse into
	 * -éis/-íais. Empirically zero non-verb false positives in the corpus. `-ís` is intentionally excluded
	 * (collides with país, anís, proper nouns).
	 */
	private val VOSOTROS_ENDINGS = listOf("áis", "éis", "íais", "abais", "asteis", "isteis")

	/**
	 * Common irregular `vosotros` forms whose endings are unaccented (-ois/-ais/-eis) and so can't be
	 * matched by rule without also catching non-verbs (e.g. "seis" = the number six). Whitelisted explicitly.
	 */
	private val VOSOTROS_IRREGULAR = setOf("sois", "vais", "veis", "dais", "ibais", "erais")

	/** True if [word] is a Castilian `vosotros` verb form (2nd-person-plural, Spain-only). */
	fun isVosotrosForm(word: String): Boolean {
		val w = word.lowercase()
		return w in VOSOTROS_IRREGULAR || (w.length > 3 && VOSOTROS_ENDINGS.any { w.endsWith(it) })
	}

	/**
	 * True if [word] should be soft-demoted for a user typing in [region]. Caller gates this to Spanish
	 * being the active language.
	 *
	 * Combines Tier 1 (rule-based `vosotros` morphology on [word]) with Tier 2 (per-word regional lexical
	 * skew baked into [classMask]'s ClassMasks region bits): ustedes regions (Mexican/Latin-American) sink
	 * Peninsular vosotros forms AND Spain-preferred words (ordenador, zumo…); Castilian sinks
	 * Latin-America-preferred words (computadora, celular…). ANY = no preference (current pooled behavior).
	 */
	fun demote(word: String, classMask: Long, region: String): Boolean = when (region) {
		MEXICAN, LATAM ->
			isVosotrosForm(word) || (classMask and ClassMasks.CLASS_REGION_ES_SKEW_MASK) != 0L
		CASTILIAN -> (classMask and ClassMasks.CLASS_REGION_LA_SKEW_MASK) != 0L
		else -> false
	}
}
