package org.continuouspath.justtype.hierarchy

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Lower→upper case map with locale-specific overrides.
 *
 * Built from `assets/hierarchies/case_pairs.csv`. Locale codes in the CSV's `locale` column
 * may be `default` or a slash-separated list (e.g. `tr / az`); rows with anything other
 * than `default` are stored as overrides keyed by `(lower, lang)`.
 */
class CaseMap internal constructor(
	private val default: Map<String, String>,
	private val overrides: Map<Pair<String, String>, String>,
) {
	/** Locale-aware uppercase for a single character. Falls back to JDK [Char.uppercaseChar] for unmapped letters. */
	fun toUpper(c: Char, locale: Locale): Char {
		val s = c.toString()
		val lang = locale.language
		overrides[s to lang]?.let { return it.single() }
		default[s]?.let { return it.single() }
		return c.uppercaseChar()
	}

	fun toUpper(s: String, locale: Locale): String = buildString(s.length) {
		s.forEach { append(toUpper(it, locale)) }
	}
}

fun loadCaseMap(assets: AssetManager): CaseMap {
	val default = mutableMapOf<String, String>()
	val overrides = mutableMapOf<Pair<String, String>, String>()
	assets.open("hierarchies/case_pairs.csv").use { input ->
		BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
			lines.drop(1).forEach { line ->
				if (line.isBlank()) return@forEach
				val cells = line.split(",", limit = 6)
				if (cells.size < 5) return@forEach
				val lower = cells[0]
				val upper = cells[2]
				val localeField = cells[4].trim()
				if (lower.isBlank() || upper.isBlank()) return@forEach
				if (localeField == "default") {
					default[lower] = upper
				} else {
					localeField.split('/').map { it.trim() }.filter { it.isNotEmpty() }.forEach { lang ->
						overrides[lower to lang] = upper
					}
				}
			}
		}
	}
	return CaseMap(default, overrides)
}
