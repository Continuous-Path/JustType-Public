package org.continuouspath.justtype.logic

import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.PosEncoding
import org.continuouspath.justtype.data.PhraseEntry
import org.continuouspath.justtype.logging.DebugCategory
import java.util.ArrayDeque
import java.util.Locale

// Hybrid accent fallback rank discount: +3 freqClass steps = x0.125 effective frequency,
// the class-granularity match for the d=0.1 analysis in
// docs/.plans/enhanced-analyzer/plan.md ("Strict vs forgiving lookup").
private const val FALLBACK_FREQ_CLASS_PENALTY = 3
private const val MAX_FREQ_CLASS = 14

class WLD(
	lettersPerKey: List<String>,
	private val wordDb: WordDb,
	private var customDb: WordDb? = null,
	private val log: (DebugCategory, String) -> Unit = { _, _ -> },
	// base letter (lowercase) → all diacritic-variant char forms (both cases) that should map to
	// the same ambiguous key as the base. Lets diacritic words (e.g. "ûti") be added to and
	// recalled from the word DB, with each variant routed to its base letter's key.
	diacriticVariantsByBase: Map<Char, List<Char>> = emptyMap(),
	// Tone-keystroke languages (LayoutSpec formatVersion 2): marked char (lowercase) →
	// (base letter, tone key number). A marked char types as its base letter's key, and the
	// syllable's tone key is APPENDED to the key sequence (Telex/VNI convention: tone at end).
	private val toneFoldToKey: Map<Char, Pair<Char, Int>> = emptyMap(),
	// Tone-after-vowel (TAV) entry: the tone key is inserted immediately AFTER the carrier
	// vowel instead of appended at syllable end. Exclusive with tone-at-end (user setting);
	// the trie rebuilds on switch, so one encoding is live at a time.
	private val toneAfterVowel: Boolean = false,
	// Hybrid accent fallback (mixed-mapping layouts, e.g. Espanol v5): a word containing an
	// explicit variant letter (á on its own key) is ALSO reachable via its base-folded key
	// sequence, at a discounted rank — typing "que" still surfaces "qué". Self-disabling on
	// layouts without explicit variant letters (English, Alphabetic, tone languages).
	private val accentFallbackEnabled: Boolean = false,
) {
	private val symbolKeys = 6
	private val letterToKey = IntArray(256) { -1 }

	// Code points >= 256 (most diacritics) don't fit the fast-path ASCII array.
	private val highLetterToKey = HashMap<Int, Int>()

	// Explicit variant letter -> its base letter's key / base char (accent fallback).
	private val fallbackKeyOverride = HashMap<Int, Int>()
	private val fallbackBaseChar = HashMap<Char, Char>()

	private fun setKey(code: Int, keyNumber: Int) {
		if (code in letterToKey.indices) letterToKey[code] = keyNumber else highLetterToKey[code] = keyNumber
	}

	private fun keyOf(code: Int): Int = if (code in letterToKey.indices) letterToKey[code] else (highLetterToKey[code] ?: -1)

	private fun dbForEntry(entry: WordEntry): WordDb = if (customDb != null && (entry.classMask and ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK) != 0L) {
		customDb!!
	} else {
		wordDb
	}

	private data class WordEntry(
		val wordID: Int,
		val lowerWord: String,
		val freqClass: Int,
		var useCount: Int,
		var lastUseTime: Int,
		var classMask: Long,
		var posEncoded: Int,
		// Raw corpus count (WordsRaw scale) — the NGB-D confidence posterior
		// needs real counts: freqClass band 1 spans 40k..1.2M (30x), far too
		// coarse to normalize against prediction effective counts.
		val rawFreq: Int = 0,
	)

	// Trie nodes
	private data class NextNode(var index: Int, var count: Int)
	private data class Node(
		var exact: MutableList<WordEntry>? = null,
		var next: Array<NextNode?>? = null,
		// Accent-fallback terminals: the SAME WordEntry objects as their exact-path node (so
		// use-count promotion stays shared); the rank discount is applied at candidate time.
		var fallback: MutableList<WordEntry>? = null,
	) {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false

			other as Node

			if (exact != other.exact) return false
			if (!next.contentEquals(other.next)) return false

			return true
		}

		override fun hashCode(): Int {
			var result = exact?.hashCode() ?: 0
			result = 31 * result + (next?.contentHashCode() ?: 0)
			return result
		}
	}

	private val trieDB = mutableListOf(Node())
	private val rootLetterSet: MutableSet<Char> = mutableSetOf()
	private val rootWordEntries: MutableMap<Char, MutableList<WordEntry>> = mutableMapOf()
	private val phraseIds: MutableSet<String> = mutableSetOf()
	private val wordIDToPhraseUUID: MutableMap<Int, String> = mutableMapOf()
	private val abbreviationTokens: MutableSet<String> = mutableSetOf()
	private val domainTokens: MutableSet<String> = mutableSetOf()

	private fun trackAbbreviationOrDomain(lowerWord: String, posEncoded: Int) {
		if (PosEncoding.hasPosTag(posEncoded, "AB") ||
			PosEncoding.hasPosTag(posEncoded, "ABP") ||
			PosEncoding.hasPosTag(posEncoded, "ABPS")
		) {
			val base = lowerWord.removeSuffix(".")
			if (base.isNotEmpty()) abbreviationTokens.add(base)
		}
		if (PosEncoding.hasPosTag(posEncoded, "DOM")) {
			domainTokens.add(lowerWord)
		}
	}

	fun isKnownAbbreviation(token: String): Boolean = abbreviationTokens.contains(token.lowercase(Locale.getDefault()))

	fun isKnownDomain(token: String): Boolean = domainTokens.contains(token.lowercase(Locale.getDefault()))

	data class CandidateEntry(
		val wordID: Int,
		val lowerWord: String,
		val freqClass: Int,
		val useCount: Int,
		val lastUseTime: Int,
		val classMask: Long,
		val posEncoded: Int,
		val isLowFrequency: Boolean,
		// Trie keystrokes still needed to reach this word (0 = fully typed). Char count is NOT a
		// proxy: a tone-marked word encodes extra tone keystrokes beyond its letters.
		val keysRemaining: Int = 0,
		// Toned word whose tone KEYSTROKE has not been typed yet (its vowel form is
		// therefore still unspecified). Drives the TAV pre-tone display collapse.
		val tonePending: Boolean = false,
		// Raw corpus count for the NGB-D confidence posterior (see WordEntry.rawFreq).
		val rawFreq: Int = 0,
	)

	data class DisambiguationResult(
		val candidates: List<CandidateEntry>,
		val termination: String,
		val maxDepth: Int,
		val examinedNodes: Int,
	)

	data class PhraseMatch(
		val phraseUUID: String,
		val abbrev: String,
		val classMask: Long,
	)

	init {
		// Explicit layout letters always win over generic diacritic-variant folding: Vietnamese
		// quality letters (ô ê ă â ơ ư đ) are layout letters in their own right AND variants of a
		// base vowel whose key group may be processed later — the variant pass must not clobber them.
		val explicitLetters = lettersPerKey.flatMapTo(mutableSetOf()) { it.map(Char::lowercaseChar) }
		var keyNumber = 0
		lettersPerKey.forEach { keys ->
			keys.forEach { ch ->
				setKey(ch.lowercaseChar().code, keyNumber)
				setKey(ch.uppercaseChar().code, keyNumber)
				// Map every diacritic variant of this base letter to the same key.
				diacriticVariantsByBase[ch.lowercaseChar()]?.forEach { variant ->
					if (variant.lowercaseChar() !in explicitLetters) setKey(variant.code, keyNumber)
				}
			}
			keyNumber += 1
		}
		// Marked (toned) characters route to their base letter's key; the tone itself is
		// handled as an appended keystroke in translateToKeys.
		toneFoldToKey.forEach { (marked, baseAndToneKey) ->
			val baseKey = keyOf(baseAndToneKey.first.lowercaseChar().code)
			if (baseKey != -1) {
				setKey(marked.lowercaseChar().code, baseKey)
				setKey(marked.uppercaseChar().code, baseKey)
			}
		}
		if (accentFallbackEnabled) {
			diacriticVariantsByBase.forEach { (base, variants) ->
				val baseKey = keyOf(base.code)
				if (baseKey == -1) return@forEach
				variants.forEach { variant ->
					val lower = variant.lowercaseChar()
					if (lower in explicitLetters && keyOf(lower.code) != baseKey) {
						fallbackKeyOverride[lower.code] = baseKey
						fallbackKeyOverride[lower.uppercaseChar().code] = baseKey
						fallbackBaseChar[lower] = base
					}
				}
			}
		}
	}

	private fun computeFreqClass(raw: Int): Int = when {
		raw >= 40000 -> 1
		raw >= 20000 -> 2
		raw >= 10000 -> 3
		raw >= 5000 -> 4
		raw >= 2500 -> 5
		raw >= 1250 -> 6
		raw >= 625 -> 7
		raw >= 300 -> 8
		raw >= 150 -> 9
		raw >= 75 -> 10
		raw >= 37 -> 11
		raw >= 17 -> 12
		raw > 8 -> 13
		else -> 14
	}

	fun isWordDbChar(c: Char): Boolean {
		val k = keyOf(c.code)
		if (k == -1) {
			log(DebugCategory.WordDb, "[isWordDbChar 2] Invalid character: '$c'")
			return false
		}
		return true
	}

	private fun translateToKeys(symbolSequence: String): List<Int>? {
		val out = mutableListOf<Int>()
		var toneKey = -1
		for (ch in symbolSequence) {
			val k = keyOf(ch.code)
			if (k == -1) {
				log(DebugCategory.WordDb, "[translateToKeys] Invalid character: '$ch'")
				return null
			}
			out.add(k)
			// One tone per syllable: a marked char contributes its tone key — right after
			// its carrier in TAV mode, at syllable end otherwise.
			toneFoldToKey[ch.lowercaseChar()]?.let {
				if (toneAfterVowel) out.add(it.second) else toneKey = it.second
			}
		}
		if (toneKey != -1) out.add(toneKey)
		return out
	}

	fun translateToKeysOrNull(symbolSequence: String): List<Int>? = translateToKeys(symbolSequence)

	/** Base-folded key sequence for the accent fallback, or null when it would equal the exact
	 *  sequence (word carries no explicit variant letter, or the fallback is disabled). */
	private fun translateToFoldedKeys(symbolSequence: String): List<Int>? {
		if (fallbackKeyOverride.isEmpty()) return null
		var differs = false
		val out = mutableListOf<Int>()
		var toneKey = -1
		for (ch in symbolSequence) {
			val folded = fallbackKeyOverride[ch.code]
			val k = folded ?: keyOf(ch.code)
			if (k == -1) return null
			if (folded != null) differs = true
			out.add(k)
			toneFoldToKey[ch.lowercaseChar()]?.let { toneKey = it.second }
		}
		if (toneKey != -1) out.add(toneKey)
		return if (differs) out else null
	}

	/** Registers `entry` (the exact-path object, shared) at its base-folded sequence. */
	private fun insertFallbackEntry(symbolSequence: String, entry: WordEntry, rawFreq: Int) {
		val keys = translateToFoldedKeys(symbolSequence) ?: return
		var nodeIndex = 0
		keys.forEachIndexed { idx, key ->
			val node = trieDB[nodeIndex]
			if (node.next == null) node.next = arrayOfNulls(symbolKeys)
			if (node.next!![key] == null) {
				trieDB.add(Node())
				node.next!![key] = NextNode(trieDB.lastIndex, 0)
			}
			node.next!![key]!!.count += (rawFreq / 10).coerceAtLeast(1)
			nodeIndex = node.next!![key]!!.index
			if (idx == keys.lastIndex) {
				val list = trieDB[nodeIndex].fallback ?: mutableListOf<WordEntry>().also {
					trieDB[nodeIndex].fallback = it
				}
				if (list.none { it.wordID == entry.wordID && it.lowerWord == entry.lowerWord }) list.add(entry)
			}
		}
	}

	private fun fallbackFreqClass(entry: WordEntry): Int = (entry.freqClass + FALLBACK_FREQ_CLASS_PENALTY).coerceAtMost(MAX_FREQ_CLASS)

	fun getNextLettersForKeys(
		keys: List<Int>,
		anyFreqMask: Long,
		minFreqMask: Long,
		minFreqClass: Int?,
	): Set<Char> {
		if (keys.isEmpty()) return emptySet()
		var nodeIndex = 0
		keys.forEach { key ->
			val node = trieDB.getOrNull(nodeIndex) ?: return emptySet()
			val next = node.next ?: return emptySet()
			val child = next.getOrNull(key) ?: return emptySet()
			nodeIndex = child.index
		}
		val depth = keys.size
		val letters = mutableSetOf<Char>()
		fun collect(nodeIdx: Int) {
			val node = trieDB.getOrNull(nodeIdx) ?: return
			node.exact?.forEach { word ->
				val include = shouldIncludeByMask(word, anyFreqMask, minFreqMask, minFreqClass)
				if (!include) return@forEach
				nextCharAt(word, depth)?.let { ch ->
					// Fold tone-marked chars to their display letter (ọ→o, ố→ô): key faces
					// show base letters, and unfolded marks could saturate the 64-char cap
					// below — silently dropping late-iterated keys' letters (page Key 7).
					letters.add(toneFoldToKey[ch]?.first ?: ch)
				}
			}
			// Accent-fallback continuations: the user on this path types BASE letters, so the
			// hint char is the variant's base form (qué via "qu" lights the e cell).
			node.fallback?.forEach { word ->
				val include = shouldIncludeByMask(word, anyFreqMask, minFreqMask, minFreqClass, fallbackFreqClass(word))
				if (!include) return@forEach
				if (word.lowerWord.length > depth) {
					val ch = word.lowerWord[depth]
					val display = toneFoldToKey[ch]?.first ?: ch
					letters.add(fallbackBaseChar[display] ?: display)
				}
			}
			val next = node.next ?: return
			next.forEach { child ->
				if (child != null && letters.size < 64) {
					collect(child.index)
				}
			}
		}
		collect(nodeIndex)
		return letters
	}

	/**
	 * The character of [w] that the NEXT keystroke after [depth] typed keys would
	 * select — or null when that keystroke is the word's TONE key (which is not a
	 * letter) or the word is complete. Char index equals key index ONLY until a
	 * mid-word tone key (tone-after-vowel entry) enters the typed prefix; after
	 * it, chars lag keys by one (c,a,sắc typed -> next char is 'i' of "cái", at
	 * index 2, not 3).
	 */
	private fun nextCharAt(w: WordEntry, depth: Int): Char? {
		var toneKeyIdx = -1
		if (toneFoldToKey.isNotEmpty()) {
			var markedIdx = -1
			for (i in w.lowerWord.indices) {
				if (toneFoldToKey.containsKey(w.lowerWord[i].lowercaseChar())) {
					markedIdx = i
					break
				}
			}
			if (markedIdx >= 0) toneKeyIdx = if (toneAfterVowel) markedIdx + 1 else w.lowerWord.length
		}
		if (depth == toneKeyIdx) return null
		val charIdx = if (toneKeyIdx in 0 until depth) depth - 1 else depth
		return w.lowerWord.getOrNull(charIdx)
	}

	/** Toned word whose tone keystroke lies at or beyond the typed prefix. */
	private fun tonePendingFor(w: WordEntry, keysRemaining: Int): Boolean {
		if (toneFoldToKey.isEmpty() || keysRemaining <= 0) return false
		var markedIdx = -1
		for (i in w.lowerWord.indices) {
			if (toneFoldToKey.containsKey(w.lowerWord[i].lowercaseChar())) {
				markedIdx = i
				break
			}
		}
		if (markedIdx < 0) return false
		val totalKeys = w.lowerWord.length + 1
		val toneIdx = if (toneAfterVowel) markedIdx + 1 else totalKeys - 1
		val typed = totalKeys - keysRemaining
		return toneIdx >= typed
	}

	/** The word with tone marks stripped to base (quality) letters — the TAV
	 *  pre-tone display form ("chú" -> "chu", "việt" -> "viêt"). */
	fun baseFormOf(word: String): String {
		if (toneFoldToKey.isEmpty()) return word
		val sb = StringBuilder(word.length)
		for (ch in word) {
			val fold = toneFoldToKey[ch.lowercaseChar()]
			sb.append(
				when {
					fold == null -> ch
					ch.isUpperCase() -> fold.first.uppercaseChar()
					else -> fold.first
				},
			)
		}
		return sb.toString()
	}

	/**
	 * Next-tone-mark prediction: the tone KEY numbers that would complete a
	 * fully-lettered word at the current node — words whose one remaining
	 * keystroke is exactly their tone key (lowerWord.length == keys.size, so the
	 * tone-key child was reached as a TONE, not as a further letter). Empty for
	 * tone-less layouts. Same inclusion masks as next-letter hints.
	 */
	fun getNextToneKeysForKeys(
		keys: List<Int>,
		anyFreqMask: Long,
		minFreqMask: Long,
		minFreqClass: Int?,
	): Set<Int> {
		if (toneFoldToKey.isEmpty() || keys.isEmpty()) return emptySet()
		var nodeIndex = 0
		keys.forEach { key ->
			val node = trieDB.getOrNull(nodeIndex) ?: return emptySet()
			val next = node.next ?: return emptySet()
			val child = next.getOrNull(key) ?: return emptySet()
			nodeIndex = child.index
		}
		val node = trieDB.getOrNull(nodeIndex) ?: return emptySet()
		val toneKeys = toneFoldToKey.values.mapTo(mutableSetOf()) { it.second }
		val out = mutableSetOf<Int>()
		for (toneKey in toneKeys) {
			val child = node.next?.getOrNull(toneKey) ?: continue
			val exact = trieDB.getOrNull(child.index)?.exact ?: continue
			val completes = exact.any { w ->
				(w.classMask and ClassMasks.CLASS_PHRASES_MASK) == 0L &&
					w.lowerWord.length == keys.size &&
					shouldIncludeByMask(w, anyFreqMask, minFreqMask, minFreqClass)
			}
			if (completes) out.add(toneKey)
		}
		return out
	}

	/**
	 * TAV per-vowel tone-form display: for each tone key, the marked chars
	 * (previous keystroke's vowel + that key's tone) with at least one live
	 * candidate when the tone is the very NEXT keystroke. A word in the
	 * tone-key child's subtree qualifies iff its char at the carrier position
	 * (keys.size - 1) folds to that tone key — a word reached through the same
	 * key AS A LETTER has no marked char there, so Telex-style letter/tone key
	 * doubling can't false-positive. One tone per word (Vietnamese syllables):
	 * with no earlier tone in the prefix, char index == key index below the
	 * tone. Empty when TAV is off; same inclusion masks as next-letter hints.
	 */
	fun getNextToneFormsForKeys(
		keys: List<Int>,
		anyFreqMask: Long,
		minFreqMask: Long,
		minFreqClass: Int?,
	): Map<Int, Set<Char>> {
		if (!toneAfterVowel || toneFoldToKey.isEmpty() || keys.isEmpty()) return emptyMap()
		var nodeIndex = 0
		keys.forEach { key ->
			val child = trieDB.getOrNull(nodeIndex)?.next?.getOrNull(key) ?: return emptyMap()
			nodeIndex = child.index
		}
		val node = trieDB.getOrNull(nodeIndex) ?: return emptyMap()
		val carrierIdx = keys.size - 1
		val prevKey = keys.last()
		val out = mutableMapOf<Int, Set<Char>>()
		toneFoldToKey.values.mapTo(mutableSetOf()) { it.second }.forEach { toneKey ->
			val child = node.next?.getOrNull(toneKey) ?: return@forEach
			// Walk cap = the theoretical max: marked chars of this tone whose carrier
			// letter lives on the previous key (0 → the subtree can't contribute).
			val bound = toneFoldToKey.count { (m, bt) -> bt.second == toneKey && keyOf(m.code) == prevKey }
			if (bound == 0) return@forEach
			val forms = mutableSetOf<Char>()
			fun collect(nodeIdx: Int) {
				if (forms.size >= bound) return
				val n = trieDB.getOrNull(nodeIdx) ?: return
				n.exact?.forEach { w ->
					if ((w.classMask and ClassMasks.CLASS_PHRASES_MASK) != 0L) return@forEach
					val ch = w.lowerWord.getOrNull(carrierIdx) ?: return@forEach
					if (toneFoldToKey[ch]?.second != toneKey) return@forEach
					if (shouldIncludeByMask(w, anyFreqMask, minFreqMask, minFreqClass)) forms.add(ch)
				}
				n.next?.forEach { c -> if (c != null) collect(c.index) }
			}
			collect(child.index)
			if (forms.isNotEmpty()) out[toneKey] = forms
		}
		return out
	}

	fun getNextLettersAccented(
		keys: List<Int>,
		accentMask: Long,
		minFreqClass: Int?,
		maxFreqClass: Int?,
		maxUseCountSystem: Int?,
		maxUseCountImported: Int?,
	): Set<Char> {
		if (keys.isEmpty() || accentMask == 0L) return emptySet()
		var nodeIndex = 0
		keys.forEach { key ->
			val node = trieDB.getOrNull(nodeIndex) ?: return emptySet()
			val next = node.next ?: return emptySet()
			val child = next.getOrNull(key) ?: return emptySet()
			nodeIndex = child.index
		}
		val depth = keys.size
		val letters = mutableSetOf<Char>()
		fun collect(nodeIdx: Int) {
			val node = trieDB.getOrNull(nodeIdx) ?: return
			node.exact?.forEach { word ->
				val include = shouldIncludeByAccent(
					word,
					accentMask,
					minFreqClass,
					maxFreqClass,
					maxUseCountSystem,
					maxUseCountImported,
				)
				if (!include) return@forEach
				nextCharAt(word, depth)?.let { letters.add(it) }
			}
			val next = node.next ?: return
			next.forEach { child ->
				if (child != null && letters.size < 64) {
					collect(child.index)
				}
			}
		}
		collect(nodeIndex)
		return letters
	}

	fun addWords(lines: List<String>, avoid: Set<String>, classMask: Long) {
		for (line in lines) {
			val cleaned = line.removePrefix("\uFEFF").trim()
			val fields = cleaned.split(';')
			if (fields.size < 2) continue
			val symbolSequence = fields[0]
			if (avoid.contains(symbolSequence.lowercase(Locale.getDefault()))) continue
			val rawFreq = fields.getOrNull(1)?.toIntOrNull() ?: continue
			val posRaw = fields.getOrNull(2)
			val output = fields.getOrNull(3) ?: symbolSequence
			val freqClass = computeFreqClass(rawFreq)
			val posEncoded = if (!posRaw.isNullOrBlank()) PosEncoding.encodeFromTagString(posRaw) else 0
			val posTags = PosEncoding.posTagList(posEncoded)
			val stats = wordDb.getOrCreateStats(
				output,
				defaultFreqClass = freqClass,
				defaultRawFreq = rawFreq,
				defaultPos1 = posTags.getOrNull(0),
				defaultPos2 = posTags.getOrNull(1),
				defaultClassMask = classMask,
			)
			val lowerWord = output.lowercase(Locale.getDefault())
			val keys = translateToKeys(symbolSequence) ?: continue

			var nodeIndex = 0
			keys.forEachIndexed { idx, key ->
				val node = trieDB[nodeIndex]
				if (node.next == null) node.next = arrayOfNulls(symbolKeys)
				if (node.next!![key] == null) {
					trieDB.add(Node())
					node.next!![key] = NextNode(trieDB.lastIndex, 0)
				}
				node.next!![key]!!.count += rawFreq
				nodeIndex = node.next!![key]!!.index
				if (idx == keys.lastIndex) {
					val word = WordEntry(
						wordID = stats.wordID,
						lowerWord = lowerWord,
						freqClass = freqClass,
						useCount = stats.useCount,
						lastUseTime = wordDb.absoluteToRelativeTime(stats.useTime),
						classMask = classMask,
						posEncoded = posEncoded,
						rawFreq = rawFreq,
					)
					val exact = trieDB[nodeIndex].exact
					if (exact == null) trieDB[nodeIndex].exact = mutableListOf(word) else exact.add(word)
					if (lowerWord.isNotEmpty()) {
						rootLetterSet.add(lowerWord.first().uppercaseChar())
						addRootWordEntry(word)
					}
					trackAbbreviationOrDomain(lowerWord, posEncoded)
					insertFallbackEntry(symbolSequence, word, rawFreq)
				}
			}
		}
	}

	fun addPhraseEntries(entries: List<PhraseEntry>) {
		entries.forEach { addPhraseEntry(it) }
	}

	fun addPhraseEntry(entry: PhraseEntry) {
		if (entry.abbreviation.isBlank() || entry.phraseUUID.isBlank()) return
		if (!phraseIds.add(entry.phraseUUID)) return
		val keys = translateToKeys(entry.abbreviation) ?: return
		val rawFreq = 1000
		val freqClass = computeFreqClass(rawFreq)
		val lowerAbbrev = entry.abbreviation.lowercase(Locale.getDefault())
		val stats = wordDb.getOrCreateStats(
			entry.abbreviation,
			defaultFreqClass = freqClass,
			defaultRawFreq = rawFreq,
			defaultClassMask = entry.classMask,
		)
		wordDb.setPhraseUUID(entry.abbreviation, entry.phraseUUID)
		wordIDToPhraseUUID[stats.wordID] = entry.phraseUUID
		var nodeIndex = 0
		keys.forEachIndexed { idx, key ->
			val node = trieDB[nodeIndex]
			if (node.next == null) node.next = arrayOfNulls(symbolKeys)
			if (node.next!![key] == null) {
				trieDB.add(Node())
				node.next!![key] = NextNode(trieDB.lastIndex, 0)
			}
			node.next!![key]!!.count += rawFreq
			nodeIndex = node.next!![key]!!.index
			if (idx == keys.lastIndex) {
				val we = WordEntry(
					wordID = stats.wordID,
					lowerWord = lowerAbbrev,
					freqClass = freqClass,
					useCount = 0,
					lastUseTime = 0,
					classMask = entry.classMask or ClassMasks.CLASS_PHRASES_MASK,
					posEncoded = 0,
					rawFreq = rawFreq,
				)
				// An abbreviation maps to exactly one wordID, so re-adding the same abbreviation (a
				// duplicate/updated phrase) must NOT append a second leaf entry — that would surface the
				// abbreviation twice in the selection list. Replace the existing same-wordID entry; the
				// wordIDToPhraseUUID mapping above already points at the newest phrase (last-wins).
				val exact = trieDB[nodeIndex].exact
				if (exact == null) {
					trieDB[nodeIndex].exact = mutableListOf(we)
				} else {
					val existing = exact.indexOfFirst { it.wordID == we.wordID }
					if (existing >= 0) exact[existing] = we else exact.add(we)
				}
				if (lowerAbbrev.isNotEmpty()) {
					rootLetterSet.add(lowerAbbrev.first().uppercaseChar())
					addRootWordEntry(we)
				}
			}
		}
	}

	fun getRootLetterSet(): Set<Char> = rootLetterSet

	fun getRootLettersFiltered(
		anyFreqMask: Long,
		minFreqMask: Long,
		minFreqClass: Int?,
	): Set<Char> {
		if (anyFreqMask == 0L && minFreqMask == 0L) return emptySet()
		val out = mutableSetOf<Char>()
		rootWordEntries.forEach { (letter, entries) ->
			for (entry in entries) {
				if (!shouldIncludeByMask(entry, anyFreqMask, minFreqMask, minFreqClass)) continue
				out.add(letter)
				break
			}
		}
		return out
	}

	fun getRootLettersAccented(
		accentMask: Long,
		minFreqClass: Int?,
		maxFreqClass: Int?,
		maxUseCountSystem: Int?,
		maxUseCountImported: Int?,
	): Set<Char> {
		if (accentMask == 0L) return emptySet()
		val out = mutableSetOf<Char>()
		var matched = 0
		val sample = mutableListOf<String>()
		rootWordEntries.forEach { (letter, entries) ->
			for (entry in entries) {
				if (!shouldIncludeByAccent(
						entry,
						accentMask,
						minFreqClass,
						maxFreqClass,
						maxUseCountSystem,
						maxUseCountImported,
					)
				) {
					continue
				}
				out.add(letter)
				matched += 1
				if (sample.size < 10) {
					sample.add("${entry.lowerWord}:${hexMask(entry.classMask)}:fc=${entry.freqClass}:use=${entry.useCount}")
				}
				break
			}
		}
		log(
			DebugCategory.WordDb,
			"[accentHints] rootLetters size=${out.size} matchedEntries=$matched sample=${sample.joinToString()}",
		)
		return out
	}

	fun updateOrAddWord(
		wordID: Int,
		word: String,
		freqClass: Int,
		useCount: Int,
		lastUseTime: Int,
		classMask: Long,
		posEncoded: Int,
		rawFreq: Int = 0,
	) {
		val keys = translateToKeys(word) ?: return
		val lower = word.lowercase(Locale.getDefault())
		var nodeIndex = 0
		keys.forEachIndexed { idx, key ->
			val node = trieDB[nodeIndex]
			if (node.next == null) node.next = arrayOfNulls(symbolKeys)
			if (node.next!![key] == null) {
				trieDB.add(Node())
				node.next!![key] = NextNode(trieDB.lastIndex, 0)
			}
			node.next!![key]!!.count += rawFreq.coerceAtLeast(1)
			nodeIndex = node.next!![key]!!.index
			if (idx == keys.lastIndex) {
				val exact = trieDB[nodeIndex].exact ?: mutableListOf<WordEntry>().also {
					trieDB[nodeIndex].exact = it
				}
				val existing = exact.firstOrNull {
					(it.classMask and ClassMasks.CLASS_PHRASES_MASK) == 0L && it.lowerWord == lower
				}
				if (existing != null) {
					existing.classMask = existing.classMask or classMask
					// Case-variant DB rows merge into one trie entry; a stale
					// duplicate must never zero the real row's usage.
					existing.useCount = maxOf(existing.useCount, useCount)
				} else {
					val we = WordEntry(
						wordID = wordID,
						lowerWord = lower,
						freqClass = freqClass,
						useCount = useCount,
						lastUseTime = lastUseTime,
						classMask = classMask,
						posEncoded = posEncoded,
						rawFreq = rawFreq,
					)
					exact.add(we)
					if (lower.isNotEmpty()) {
						rootLetterSet.add(lower.first().uppercaseChar())
						addRootWordEntry(we)
					}
					trackAbbreviationOrDomain(lower, posEncoded)
					insertFallbackEntry(word, we, rawFreq.coerceAtLeast(1))
				}
			}
		}
	}

	fun mergeClassMasks(sourceMask: Long, targetMask: Long) {
		if (sourceMask == 0L) return
		trieDB.forEach { node ->
			node.exact?.forEach { entry ->
				if ((entry.classMask and sourceMask) != 0L) {
					entry.classMask = (entry.classMask and sourceMask.inv()) or targetMask
				}
			}
		}
	}

	fun clearClassMasks(mask: Long) {
		if (mask == 0L) return
		trieDB.forEach { node ->
			node.exact?.forEach { entry ->
				if ((entry.classMask and mask) != 0L) {
					entry.classMask = entry.classMask and mask.inv()
				}
			}
		}
	}

	fun addCustomWord(word: String) {
		val db = customDb ?: wordDb
		val rawFreq = 1000
		val freqClass = computeFreqClass(rawFreq)
		val lower = word.lowercase(Locale.getDefault())
		val caseType = PosEncoding.caseTypeForWord(word)
		db.ensureCustomWord(word, rawFreq, "NNP", null, posEncoded = caseType)
		val dbWordID = db.getWordIDByWord(word) ?: return
		val keys = translateToKeys(word) ?: return
		// Check if a case variant already exists in the trie at this node
		val existingLower = findTrieEntry(lower)
		var nodeIndex = 0
		keys.forEachIndexed { idx, key ->
			val node = trieDB[nodeIndex]
			if (node.next == null) node.next = arrayOfNulls(symbolKeys)
			if (node.next!![key] == null) {
				trieDB.add(Node())
				node.next!![key] = NextNode(trieDB.lastIndex, 0)
			}
			node.next!![key]!!.count += rawFreq
			nodeIndex = node.next!![key]!!.index
			if (idx == keys.lastIndex) {
				val exact = trieDB[nodeIndex].exact
				val we = WordEntry(
					wordID = dbWordID,
					lowerWord = lower,
					freqClass = freqClass,
					useCount = 0,
					lastUseTime = 0,
					classMask = ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK,
					posEncoded = caseType,
					rawFreq = rawFreq,
				)
				if (exact == null) trieDB[nodeIndex].exact = mutableListOf(we) else exact.add(we)
				if (lower.isNotEmpty()) {
					rootLetterSet.add(lower.first().uppercaseChar())
					addRootWordEntry(we)
				}
				insertFallbackEntry(word, we, rawFreq)
				if (existingLower != null) {
					val letters = word.filter { it.isLetter() }
					val hasLetters = letters.isNotEmpty()
					val newForm = when {
						hasLetters && letters.all { it.isUpperCase() } -> WordCaseForm.UPPER
						hasLetters && letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() } -> WordCaseForm.TITLE
						hasLetters && letters.all { it.isLowerCase() } -> WordCaseForm.LOWER
						else -> WordCaseForm.ORIGINAL
					}
					db.ensureCaseCountAtLeast(word, newForm)
				}
			}
		}
	}

	fun getPhraseMatches(
		keys: List<Int>,
		anyFreqMask: Long,
		minFreqMask: Long,
		minFreqClass: Int?,
	): List<PhraseMatch> {
		if (keys.isEmpty()) return emptyList()
		var nodeIndex = 0
		for (k in keys) {
			val node = trieDB[nodeIndex]
			val next = node.next ?: return emptyList()
			val nn = next.getOrNull(k) ?: return emptyList()
			nodeIndex = nn.index
		}
		val node = trieDB[nodeIndex]
		val exact = node.exact ?: return emptyList()
		return exact.mapNotNull { w ->
			if ((w.classMask and ClassMasks.CLASS_PHRASES_MASK) == 0L) return@mapNotNull null
			if (!shouldIncludeByMask(w, anyFreqMask, minFreqMask, minFreqClass)) return@mapNotNull null
			val uuid = wordIDToPhraseUUID[w.wordID] ?: return@mapNotNull null
			PhraseMatch(uuid, w.lowerWord, w.classMask)
		}
	}

	/**
	 * Phase 1: Trie-only candidate collection. No DB lookups.
	 * Returns compact CandidateEntry objects suitable for sorting by metrics.
	 */
	fun getDisambiguationCandidates(
		keys: List<Int>,
		maxWordCompleteEntries: Int,
		minFreqClass: Int?,
		includeExcludedAtEnd: Boolean,
		anyFreqMask: Long,
		minFreqMask: Long,
		baseSearchDepth: Int,
		searchExpansionDepth: Int,
		maxExaminedNodes: Int,
		shouldAbort: (() -> Boolean)? = null,
		applyPendingHighlight: (() -> Unit)? = null,
	): DisambiguationResult {
		log(
			DebugCategory.WordDb,
			"[getDisambiguationCandidates] keys=$keys anyMask=${hexMask(anyFreqMask)} minMask=${hexMask(minFreqMask)} minFreqClass=$minFreqClass",
		)
		var nodeIndex = 0
		for (k in keys) {
			val node = trieDB[nodeIndex]
			val next = node.next ?: run {
				log(DebugCategory.WordDb, "[getDisambiguationCandidates] terminate=BOT missing child list for key=$k")
				return DisambiguationResult(emptyList(), "BOT", 0, 0)
			}
			val nn = next.getOrNull(k) ?: run {
				log(DebugCategory.WordDb, "[getDisambiguationCandidates] terminate=BOT missing child for key=$k")
				return DisambiguationResult(emptyList(), "BOT", 0, 0)
			}
			nodeIndex = nn.index
		}
		val candidates = mutableListOf<CandidateEntry>()
		val node = trieDB[nodeIndex]
		var bfsCollected = 0
		var nonEmptyNodes = 0
		var maxDepthReached = 0

		// One list slot per word: a variant word can be reachable via both its exact node and an
		// accent-fallback node inside the same BFS (e.g. prefix "qu" descends both e and é).
		val seenWordIds = HashSet<Int>()

		fun addCandidate(w: WordEntry, countTowardN: Boolean, keysRemaining: Int, fallback: Boolean = false): Boolean {
			if ((w.classMask and ClassMasks.CLASS_PHRASES_MASK) != 0L) return false
			val effClass = if (fallback) fallbackFreqClass(w) else w.freqClass
			val include = shouldIncludeByMask(w, anyFreqMask, minFreqMask, minFreqClass, effClass)
			if (!include && !includeExcludedAtEnd) return false
			val isLowFrequency = includeExcludedAtEnd &&
				minFreqClass != null &&
				!include &&
				(w.classMask and minFreqMask) != 0L &&
				effClass > minFreqClass
			if (!include && !isLowFrequency) return false
			if (!seenWordIds.add(w.wordID)) return false
			candidates.add(
				CandidateEntry(
					wordID = w.wordID,
					lowerWord = w.lowerWord,
					tonePending = tonePendingFor(w, keysRemaining),
					freqClass = effClass,
					useCount = w.useCount,
					lastUseTime = w.lastUseTime,
					classMask = w.classMask,
					posEncoded = w.posEncoded,
					isLowFrequency = isLowFrequency,
					keysRemaining = keysRemaining,
					rawFreq = w.rawFreq,
				),
			)
			if (countTowardN) {
				bfsCollected += 1
				if (bfsCollected >= maxWordCompleteEntries) return true
			}
			return false
		}

		node.exact?.forEach { w -> addCandidate(w, countTowardN = false, keysRemaining = 0) }
		node.fallback?.forEach { w -> addCandidate(w, countTowardN = false, keysRemaining = 0, fallback = true) }
		val queue: ArrayDeque<Pair<Int, Int>> = ArrayDeque()
		node.next?.filterNotNull()?.forEach { child -> queue.add(child.index to 1) }

		val baseDepth = baseSearchDepth.coerceIn(0, 10)
		val maxDepth = (baseDepth + searchExpansionDepth.coerceIn(0, 10)).coerceIn(0, 20)
		var termination = "MAXD"

		fun processNode(nIdx: Int, depth: Int): Boolean {
			if (depth > maxDepthReached) maxDepthReached = depth
			val n = trieDB[nIdx]
			val exact = n.exact
			if (exact != null && exact.isNotEmpty()) {
				nonEmptyNodes += 1
				for (w in exact) {
					if (addCandidate(w, countTowardN = true, keysRemaining = depth)) return true
				}
			}
			n.fallback?.forEach { w ->
				if (addCandidate(w, countTowardN = true, keysRemaining = depth, fallback = true)) return true
			}
			if (depth < maxDepth) {
				n.next?.filterNotNull()?.forEach { child -> queue.add(child.index to (depth + 1)) }
			}
			return false
		}

		if (queue.isEmpty()) {
			return DisambiguationResult(candidates, "BOT", maxDepthReached, nonEmptyNodes)
		}

		while (queue.isNotEmpty()) {
			val (nIdx, depth) = queue.removeFirst()
			if (depth > baseDepth) {
				queue.addFirst(nIdx to depth)
				break
			}
			if (processNode(nIdx, depth)) {
				termination = "MQC"
				break
			}
			if (nonEmptyNodes >= maxExaminedNodes) {
				termination = "MEN"
				break
			}
			// Apply pending highlight + abort check every 50 nodes
			if (nonEmptyNodes % 50 == 0) {
				applyPendingHighlight?.invoke()
				if (shouldAbort?.invoke() == true) {
					termination = "ABORT"
					break
				}
			}
		}

		if (termination == "MAXD") {
			while (queue.isNotEmpty()) {
				val (nIdx, depth) = queue.removeFirst()
				if (depth > maxDepth) {
					termination = "MAXD"
					break
				}
				if (processNode(nIdx, depth)) {
					termination = "MQC"
					break
				}
				if (nonEmptyNodes >= maxExaminedNodes) {
					termination = "MEN"
					break
				}
				if (nonEmptyNodes % 50 == 0) {
					applyPendingHighlight?.invoke()
					if (shouldAbort?.invoke() == true) {
						termination = "ABORT"
						break
					}
				}
			}
		}

		if (candidates.isEmpty()) termination = "BOT"
		return DisambiguationResult(candidates, termination, maxDepthReached, nonEmptyNodes)
	}

	/**
	 * (stored word, its stats) for a word resolved CASE-INSENSITIVELY through
	 * the trie — the stored row may be case-fixed ("I", "China") while callers
	 * hold lowercase corpus tokens. Read-only: never creates rows (the display
	 * path once fabricated fc7 lowercase orphan rows via getOrCreateStats,
	 * which clobbered real stats and split block/trie dedup — Cliff's
	 * "i above I" failure, 2026-08-10).
	 */
	fun caseStatsFor(word: String): Pair<String, DbWordStats>? {
		val entry = findTrieEntry(word.lowercase(Locale.getDefault())) ?: return null
		val db = dbForEntry(entry)
		val stats = db.getWordStatsByID(entry.wordID) ?: return null
		val stored = db.getWordByID(entry.wordID) ?: return null
		return stored to stats
	}

	data class RankingStats(
		val wordID: Int,
		val rawFreq: Int,
		val useCount: Int,
		val lastUseTime: Int,
	)

	/** Live trie stats for a word (case-insensitive), read-only — the
	 *  in-memory rawFreq/useCount/lastUseTime the ranking paths use. */
	fun rankingStatsFor(word: String): RankingStats? = findTrieEntry(word.lowercase(Locale.getDefault()))?.let {
		RankingStats(it.wordID, it.rawFreq, it.useCount, it.lastUseTime)
	}

	fun markWordUsed(output: String) {
		val lower = output.lowercase(Locale.getDefault())
		val entry = findTrieEntry(lower)
		if (entry != null) {
			entry.useCount += 1
			val db = dbForEntry(entry)
			entry.lastUseTime = db.relativeTime()
			db.markWordUsedByID(entry.wordID)
		} else {
			wordDb.markWordUsed(output)
		}
	}

	/**
	 * Increment a case count with adaptive capping and CaseType mutation.
	 *
	 * @param margin  Minimum number of non-preferred usages required before the
	 *                preferred form switches (from Developer Settings slider,
	 *                default 2): the flip fires when the new form's count reaches
	 *                max(rival count, margin). Also the cap: a count stops
	 *                incrementing once it exceeds the rival count by this margin.
	 *                Note: counts are cumulative, so flipping *back* after a flip
	 *                can take fewer than margin usages once counts are tied.
	 */
	fun incrementCaseCount(output: String, form: WordCaseForm, margin: Int = 2, amount: Int = 1) {
		val lower = output.lowercase(Locale.getDefault())
		val entry = findTrieEntry(lower)
		if (entry == null) {
			wordDb.incrementCaseCount(output, form, amount)
			return
		}

		val db = dbForEntry(entry)
		val counts = db.getCaseCountsByID(entry.wordID)
		if (counts == null) {
			db.incrementCaseCountByID(entry.wordID, form, amount)
			return
		}

		val ct = PosEncoding.caseType(entry.posEncoded)
		val isLfOrTf = ct == PosEncoding.CASE_LOWER_FIRST || ct == PosEncoding.CASE_TITLE_FIRST

		if (!isLfOrTf || (form != WordCaseForm.LOWER && form != WordCaseForm.TITLE)) {
			db.incrementCaseCountByID(entry.wordID, form, amount)
			return
		}

		val myCount = if (form == WordCaseForm.LOWER) counts.lower else counts.title
		val rivalCount = if (form == WordCaseForm.LOWER) counts.title else counts.lower

		if (myCount >= rivalCount + margin) return

		db.incrementCaseCountByID(entry.wordID, form, amount)

		val newMyCount = myCount + amount
		if (newMyCount >= maxOf(rivalCount, margin)) {
			val newCt = if (form == WordCaseForm.LOWER) {
				PosEncoding.CASE_LOWER_FIRST
			} else {
				PosEncoding.CASE_TITLE_FIRST
			}
			if (newCt != ct) {
				val newEncoded = PosEncoding.withCaseType(entry.posEncoded, newCt)
				entry.posEncoded = newEncoded
				db.updatePosEncodedByID(entry.wordID, newEncoded)
				log(
					DebugCategory.ShiftState,
					"[WLD] CaseType mutation: word='${entry.lowerWord}', ${PosEncoding.caseTypeTag(ct)}->${PosEncoding.caseTypeTag(newCt)}, lower=${counts.lower}, title=${counts.title}, form=$form",
				)
			}
		}
	}

	fun decrementFreqClass(output: String, minClass: Int = 2) {
		val lower = output.lowercase(Locale.getDefault())
		val entry = findTrieEntry(lower)
		if (entry != null) {
			dbForEntry(entry).decrementFreqClassByID(entry.wordID, minClass)
		} else {
			wordDb.decrementFreqClass(output, minClass)
		}
	}

	// Runs `block` (a sequence of markWordUsed/incrementCaseCount/decrementFreqClass calls for a
	// single commit) inside one DB transaction on the db `output` resolves to, instead of each
	// issuing its own auto-commit fsync — collapses N disk syncs per keystroke into 1.
	fun <T> inUsageTransaction(output: String, block: () -> T): T {
		val entry = findTrieEntry(output.lowercase(Locale.getDefault()))
		val db = entry?.let { dbForEntry(it) } ?: wordDb
		return db.runInTransaction(block)
	}

	/**
	 * Traverses the trie by key sequence to find the WordEntry matching [lowerWord].
	 * Returns null if no match is found.
	 */
	/**
	 * Family expansion (sls.md "family expansion"): vocabulary words whose
	 * LETTERS start with [stem]. Walks the trie down the stem's key sequence,
	 * then collects subtree terminals whose lowerWord carries the stem as a
	 * literal prefix — the letter filter disambiguates within the
	 * ambiguous-key subtree ("intellect…" only, not every word on those
	 * keys). Phrase rows and accent-fallback duplicates are excluded.
	 *
	 * ORDER (Cliff, round 4 — supersedes the length/discovery order, whose
	 * "possible" family led with poss/posse/possum): blocks by DESCENDING
	 * length of the common letter prefix with [sourceWord] — the word the
	 * user anchored on ("There! That's close to what I want") — then
	 * alphabetical within each block. possibles(8) > possibly(7) >
	 * possibility/possibilities(6) > the possess/possum crowd(4). The
	 * closest relatives reliably surface on page 1; "starts with poss…"
	 * words stay reachable on later pages; alphabetical blocks read
	 * predictably and group morphological siblings.
	 *
	 * keysRemaining is relative to [typedKeys] (the live sequence length),
	 * so inserted entries carry honest metric fields.
	 */
	fun wordsWithLetterPrefix(stem: String, sourceWord: String, limit: Int, typedKeys: Int): List<CandidateEntry> {
		if (stem.isEmpty() || limit <= 0) return emptyList()
		val keys = translateToKeys(stem) ?: return emptyList()
		var nodeIndex = 0
		for (key in keys) {
			val child = trieDB.getOrNull(nodeIndex)?.next?.getOrNull(key) ?: return emptyList()
			nodeIndex = child.index
		}
		val found = LinkedHashMap<Int, WordEntry>() // wordID -> entry, discovery order
		val stack = ArrayDeque<Int>()
		stack.addLast(nodeIndex)
		while (stack.isNotEmpty()) {
			val node = trieDB.getOrNull(stack.removeLast()) ?: continue
			node.exact?.forEach { e ->
				if ((e.classMask and ClassMasks.CLASS_PHRASES_MASK) == 0L &&
					e.lowerWord.startsWith(stem) &&
					!found.containsKey(e.wordID)
				) {
					found[e.wordID] = e
				}
			}
			val children = node.next
			children?.indices?.reversed()?.forEach { k ->
				children[k]?.let { stack.addLast(it.index) }
			}
		}
		val source = sourceWord.lowercase(Locale.getDefault())
		return found.values
			.sortedWith(
				compareByDescending<WordEntry> { it.lowerWord.commonPrefixWith(source).length }
					.thenBy { it.lowerWord },
			)
			.take(limit)
			.map { e ->
				val kr = translateToKeys(e.lowerWord)?.let { (it.size - typedKeys).coerceAtLeast(0) } ?: 0
				CandidateEntry(
					wordID = e.wordID,
					lowerWord = e.lowerWord,
					freqClass = e.freqClass,
					useCount = e.useCount,
					lastUseTime = e.lastUseTime,
					classMask = e.classMask,
					posEncoded = e.posEncoded,
					isLowFrequency = false,
					keysRemaining = kr,
					rawFreq = e.rawFreq,
				)
			}
	}

	private fun findTrieEntry(lowerWord: String): WordEntry? {
		val keys = translateToKeys(lowerWord) ?: return null
		var nodeIndex = 0
		for (key in keys) {
			val node = trieDB.getOrNull(nodeIndex) ?: return null
			val next = node.next ?: return null
			val child = next.getOrNull(key) ?: return null
			nodeIndex = child.index
		}
		val exact = trieDB.getOrNull(nodeIndex)?.exact ?: return null
		return exact.firstOrNull {
			(it.classMask and ClassMasks.CLASS_PHRASES_MASK) == 0L && it.lowerWord == lowerWord
		}
	}

	private fun addRootWordEntry(entry: WordEntry) {
		val first = entry.lowerWord.firstOrNull()?.uppercaseChar() ?: return
		val list = rootWordEntries.getOrPut(first) { mutableListOf() }
		list.add(entry)
	}

	private fun shouldIncludeByMask(
		entry: WordEntry,
		anyFreqMask: Long,
		minFreqMask: Long,
		minFreqClass: Int?,
		effectiveFreqClass: Int = entry.freqClass,
	): Boolean {
		val mask = entry.classMask
		if ((mask and anyFreqMask) != 0L) return true
		if (minFreqClass == null) return false
		if ((mask and minFreqMask) == 0L) return false
		return effectiveFreqClass <= minFreqClass
	}

	private fun shouldIncludeByAccent(
		entry: WordEntry,
		accentMask: Long,
		minFreqClass: Int?,
		maxFreqClass: Int?,
		maxUseCountSystem: Int?,
		maxUseCountImported: Int?,
	): Boolean {
		if ((entry.classMask and accentMask) == 0L) return false
		val systemMask = ClassMasks.CLASS_JUSTTYPE_MASK or ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK
		val phraseMask = ClassMasks.CLASS_PHRASES_MASK
		val otherMask = accentMask and systemMask.inv() and phraseMask.inv()
		val activeSystemMask = accentMask and systemMask
		val activePhraseMask = accentMask and phraseMask

		fun passes(
			mask: Long,
			applyFreqFilters: Boolean,
			maxUseCount: Int?,
		): Boolean {
			if ((entry.classMask and mask) == 0L) return false
			if (applyFreqFilters) {
				if (minFreqClass != null && entry.freqClass > minFreqClass) return false
				if (maxFreqClass != null && entry.freqClass < maxFreqClass) return false
			}
			if (maxUseCount != null && entry.useCount > maxUseCount) return false
			return true
		}

		if (activeSystemMask != 0L && passes(ClassMasks.CLASS_JUSTTYPE_MASK, true, maxUseCountSystem)) return true
		if (activeSystemMask != 0L && passes(ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK, false, maxUseCountSystem)) return true
		if (activePhraseMask != 0L && passes(ClassMasks.CLASS_PHRASES_MASK, false, null)) return true
		if (otherMask != 0L && passes(otherMask, false, maxUseCountImported)) return true
		return false
	}

	private fun hexMask(mask: Long): String = "0x" + mask.toString(16).uppercase(Locale.getDefault())
}
