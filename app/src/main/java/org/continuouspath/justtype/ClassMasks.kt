package org.continuouspath.justtype

object ClassMasks {
	const val CLASS_JUSTTYPE_BIT = 0
	const val CLASS_CUSTOM_WORDS_BIT = 1
	const val CLASS_PHRASES_BIT = 2
	const val CLASS_USER_ADDED_CUSTOM_BIT = 3
	const val CLASS_PAST_VOCABULARIES_BIT = 4

	const val CLASS_JUSTTYPE_MASK: Long = 1L shl CLASS_JUSTTYPE_BIT
	const val CLASS_CUSTOM_WORDS_MASK: Long = 1L shl CLASS_CUSTOM_WORDS_BIT
	const val CLASS_PHRASES_MASK: Long = 1L shl CLASS_PHRASES_BIT
	const val CLASS_USER_ADDED_CUSTOM_MASK: Long = 1L shl CLASS_USER_ADDED_CUSTOM_BIT
	const val CLASS_PAST_VOCABULARIES_MASK: Long = 1L shl CLASS_PAST_VOCABULARIES_BIT
	const val CLASS_USER_ADDED_CUSTOM_COMBINED_MASK: Long =
		CLASS_CUSTOM_WORDS_MASK or CLASS_USER_ADDED_CUSTOM_MASK

	// Coarse-language markers set at build time from {Lang}WordsAvoid.txt. Level 0
	// (non-offensive) carries neither bit; slurs and `X` entries are dropped from the DB
	// entirely and never reach either bit. KEY_EXCLUDED_WORDS selects which bits are filtered
	// out of the vocabulary fetch, so the user changes what is offered without a rebuild.
	const val CLASS_OFFENSIVE_BIT = 60 // level 1 — excluded by default
	const val CLASS_POTENTIALLY_OFFENSIVE_BIT = 59 // level 2 — excluded only at the strictest setting
	const val CLASS_OFFENSIVE_MASK: Long = 1L shl CLASS_OFFENSIVE_BIT
	const val CLASS_POTENTIALLY_OFFENSIVE_MASK: Long = 1L shl CLASS_POTENTIALLY_OFFENSIVE_BIT

	// Reserved TOP bits (NOT handed out by ClassMetadataStore.findNextFreeBit, which caps at 59) carrying
	// Spanish regional lexical skew (Tier 2). Set on baked Spanish words at build time; read by the ranker
	// only when a Spanish region is chosen. A word carries at most one (or neither = region-neutral). These
	// never enter vocab fetch masks, so they don't affect word selection — only ranking.
	const val CLASS_REGION_GB_SKEW_BIT = 58 // British-preferred word (sinks for US users)
	const val CLASS_REGION_US_SKEW_BIT = 57 // American-preferred word (sinks for UK users)
	const val CLASS_REGION_GB_SKEW_MASK: Long = 1L shl CLASS_REGION_GB_SKEW_BIT
	const val CLASS_REGION_US_SKEW_MASK: Long = 1L shl CLASS_REGION_US_SKEW_BIT

	const val CLASS_REGION_ES_SKEW_BIT = 62 // Spain-preferred word (sinks for Mexican / Latin-American users)
	const val CLASS_REGION_LA_SKEW_BIT = 61 // Latin-America-preferred word (sinks for Castilian users)
	const val CLASS_REGION_ES_SKEW_MASK: Long = 1L shl CLASS_REGION_ES_SKEW_BIT
	const val CLASS_REGION_LA_SKEW_MASK: Long = 1L shl CLASS_REGION_LA_SKEW_BIT

	fun maskForBit(bit: Int): Long = 1L shl bit
}
