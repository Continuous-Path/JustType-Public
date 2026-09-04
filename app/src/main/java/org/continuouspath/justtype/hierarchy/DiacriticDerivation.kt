package org.continuouspath.justtype.hierarchy

/**
 * Derives a language's diacritic set from its corpus: the distinct non-ASCII letters (lowercased) that
 * appear in its words. Self-maintaining — no hand-authored per-language list. Used at DB-build time, at
 * vocabulary-import time, and as a one-time backfill migration.
 *
 * NOTE: `buildSrc` cannot import app code, so [BuildEnglishDbTask] keeps a mirrored copy of [scanWord];
 * keep the two in sync (same convention as `PosEncodingUtil`).
 */
object DiacriticDerivation {
	/** Add each non-ASCII letter of [word], lowercased, into [into]. ASCII (incl. a–z) and non-letters skipped. */
	fun scanWord(word: String, into: MutableSet<Char>) {
		for (ch in word) {
			if (ch.code > 0x7F && ch.isLetter()) into.add(ch.lowercaseChar())
		}
	}

	fun scanWords(words: Iterable<String>): Set<Char> = sortedSetOf<Char>().also { set -> words.forEach { scanWord(it, set) } }

	/** Compact, order-stable string form for storage in DB `metadata` and the [LanguageRegistry]. */
	fun encode(set: Set<Char>): String = set.sorted().joinToString("")

	fun decode(s: String): Set<Char> = s.toSet()
}
