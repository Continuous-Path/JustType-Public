package org.continuouspath.justtype

/**
 * Packs 3 POS tag indices + 1 CaseType flag into a single Int (4 bytes).
 *
 * Layout: (pos1 shl 24) or (pos2 shl 16) or (pos3 shl 8) or caseType
 *
 * POS indices use 0 for "empty slot". CaseType values 0-5 are defined below.
 * The CaseType byte is mutable at runtime (LF<->TF transitions based on user behavior).
 */
object PosEncoding {
	// ── CaseType constants (byte 0) ────────────────────────────────────────────

	const val CASE_LOWER_ONLY = 0 // "the" — only capitalized when sentence-initial
	const val CASE_TITLE_ONLY = 1 // "Washington" — always capitalized
	const val CASE_LOWER_FIRST = 2 // "cliff"/"Cliff" — lowercase preferred, both shown
	const val CASE_TITLE_FIRST = 3 // "Smith"/"smith" — TitleCase preferred, both shown
	const val CASE_ORIGINAL = 4 // "McDonald's" — original case only
	const val CASE_ACRONYM = 5 // "FBI" — all-uppercase

	// ── POS tag indices (bytes 1-3, 0 = empty) ────────────────────────────────

	private const val POS_NONE = 0

	private val TAG_TO_INDEX: Map<String, Int> =
		mapOf(
			// Nouns
			"NN" to 1,
			"NNS" to 2,
			"NNP" to 3,
			"NNPS" to 4,
			// Verbs
			"VB" to 5,
			"VBD" to 6,
			"VBG" to 7,
			"VBN" to 8,
			"VBP" to 9,
			"VBZ" to 10,
			// Adjectives
			"JJ" to 11,
			"JJR" to 12,
			"JJS" to 13,
			// Adverbs
			"RB" to 14,
			"RBR" to 15,
			// Determiners
			"DT" to 16,
			"WDT" to 17,
			// Pronouns
			"PRP" to 18,
			"PRP$" to 19,
			"WP" to 20,
			"WP$" to 21,
			// Prepositions / Conjunctions
			"IN" to 22,
			"CC" to 23,
			// Wh-adverb
			"WRB" to 24,
			// Modal
			"MD" to 25,
			// Interjection
			"UH" to 26,
			// Foreign word
			"FW" to 27,
			// Abbreviation — optional period (both "mm" and "mm." added)
			"AB" to 28,
			// Particle
			"RP" to 29,
			// Existential "there"
			"EX" to 30,
			// Extension — URL/file extension with optional leading period (".com" and "com")
			"EXT" to 31,
			// Uncertain / unknown
			"UNC" to 32,
			// Penn Treebank "to" preposition
			"TO" to 33,
			// Abbreviation — period required ("etc." only)
			"ABP" to 34,
			// Abbreviation — period required + auto-shift next word ("Mr." only)
			"ABPS" to 35,
			// URL domain — internal period, not optional ("gmail.com")
			"DOM" to 36,
		)

	private val INDEX_TO_TAG: Map<Int, String> = TAG_TO_INDEX.entries.associate { (k, v) -> v to k }

	private val CASETYPE_TAG_TO_VALUE: Map<String, Int> =
		mapOf(
			"LO" to CASE_LOWER_ONLY,
			"TX" to CASE_TITLE_ONLY,
			"TO" to CASE_TITLE_ONLY, // backward compat; TX is the canonical tag
			"LF" to CASE_LOWER_FIRST,
			"TF" to CASE_TITLE_FIRST,
			"OC" to CASE_ORIGINAL,
			"AC" to CASE_ACRONYM,
		)

	private val CASETYPE_VALUE_TO_TAG: Map<Int, String> =
		CASETYPE_TAG_TO_VALUE.entries.associate { (k, v) -> v to k }

	// ── Packing ────────────────────────────────────────────────────────────────

	/**
	 * Pack 3 POS tag strings + CaseType string into a single Int.
	 * [caseTypeTag] is one of "LO","TX","TO","LF","TF","OC","AC".
	 * [posTags] contains 0-3 POS tag strings (extras beyond 3 are silently dropped).
	 * Unknown POS tags are mapped to POS_NONE.
	 */
	fun encode(
		caseTypeTag: String,
		posTags: List<String>,
	): Int {
		val ct = CASETYPE_TAG_TO_VALUE[caseTypeTag] ?: CASE_LOWER_ONLY
		val p1 = if (posTags.isNotEmpty()) TAG_TO_INDEX[posTags[0]] ?: POS_NONE else POS_NONE
		val p2 = if (posTags.size > 1) TAG_TO_INDEX[posTags[1]] ?: POS_NONE else POS_NONE
		val p3 = if (posTags.size > 2) TAG_TO_INDEX[posTags[2]] ?: POS_NONE else POS_NONE
		return (p1 shl 24) or (p2 shl 16) or (p3 shl 8) or ct
	}

	/**
	 * Parse a tag list from EnglishWordsRaw.txt format (e.g., "LO,NN,VB")
	 * where the first element is the CaseType tag and the rest are POS tags.
	 */
	fun encodeFromTagString(tagString: String): Int {
		val tags = tagString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
		if (tags.isEmpty()) return 0
		val caseTypeTag = tags[0]
		val posTags = tags.drop(1)
		return encode(caseTypeTag, posTags)
	}

	// ── Unpacking ──────────────────────────────────────────────────────────────

	fun pos1Index(encoded: Int): Int = (encoded ushr 24) and 0xFF

	fun pos2Index(encoded: Int): Int = (encoded ushr 16) and 0xFF

	fun pos3Index(encoded: Int): Int = (encoded ushr 8) and 0xFF

	fun caseType(encoded: Int): Int = encoded and 0xFF

	fun pos1Tag(encoded: Int): String? = INDEX_TO_TAG[pos1Index(encoded)]

	fun pos2Tag(encoded: Int): String? = INDEX_TO_TAG[pos2Index(encoded)]

	fun pos3Tag(encoded: Int): String? = INDEX_TO_TAG[pos3Index(encoded)]

	fun caseTypeTag(encoded: Int): String = CASETYPE_VALUE_TO_TAG[caseType(encoded)] ?: "LO"

	fun posTagList(encoded: Int): List<String> = buildList {
		pos1Tag(encoded)?.let { add(it) }
		pos2Tag(encoded)?.let { add(it) }
		pos3Tag(encoded)?.let { add(it) }
	}

	// ── CaseType mutation ──────────────────────────────────────────────────────

	/** Replace the CaseType byte while preserving POS bytes. */
	fun withCaseType(
		encoded: Int,
		newCaseType: Int,
	): Int = (encoded and 0xFFFFFF00.toInt()) or (newCaseType and 0xFF)

	// ── Queries ────────────────────────────────────────────────────────────────

	fun hasPosTag(
		encoded: Int,
		tag: String,
	): Boolean {
		val idx = TAG_TO_INDEX[tag] ?: return false
		return pos1Index(encoded) == idx || pos2Index(encoded) == idx || pos3Index(encoded) == idx
	}

	fun hasNNP(encoded: Int): Boolean = hasPosTag(encoded, "NNP") || hasPosTag(encoded, "NNPS")

	fun tagToIndex(tag: String): Int = TAG_TO_INDEX[tag] ?: POS_NONE

	fun indexToTag(index: Int): String? = INDEX_TO_TAG[index]

	/** Determine the initial caseType for a user-spelled custom word. */
	fun caseTypeForWord(word: String): Int {
		val letters = word.filter { it.isLetter() }
		if (letters.isEmpty()) return CASE_LOWER_FIRST
		return when {
			letters.all { it.isUpperCase() } -> CASE_ACRONYM
			letters.first().isUpperCase() &&
				letters.drop(1).all { it.isLowerCase() } -> CASE_TITLE_FIRST
			letters.all { it.isLowerCase() } -> CASE_LOWER_FIRST
			else -> CASE_ORIGINAL
		}
	}
}
