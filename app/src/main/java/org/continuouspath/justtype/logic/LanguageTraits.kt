package org.continuouspath.justtype.logic

/**
 * Cross-language behavior flags (engine-spec "LanguageTraits"; Cliff
 * directive 2026-08-08): NGB behavior that forks per language consults
 * these — never language names, never scattered tones-null checks.
 * Derived once at language init from the layout contract plus whether the
 * language DB ships a multi-syllable unit inventory.
 *
 * Vietnamese: syllable-based with tone keys — units carry multi-syllable
 * words, the entry-state gate is structural. English/Spanish: word-based —
 * the unit inventory is empty (until the collocation round), context is
 * the whole previous word, and today every NGB fork degrades to the same
 * code path by DATA alone (empty units => no 1N1 source => gate no-op),
 * which the EN simulator round verified. The traits object is the named
 * home for the day a fork needs more than the data provides.
 */
data class LanguageTraits(
	val syllableBased: Boolean,
	val hasToneKeys: Boolean,
) {
	companion object {
		/** Word-based default (English built-in, unknown langpacks). */
		val WORD_BASED = LanguageTraits(syllableBased = false, hasToneKeys = false)

		fun from(spec: LayoutSpec?, hasUnitInventory: Boolean): LanguageTraits {
			val tones = spec?.tones != null
			return LanguageTraits(
				syllableBased = tones || hasUnitInventory,
				hasToneKeys = tones,
			)
		}
	}
}
