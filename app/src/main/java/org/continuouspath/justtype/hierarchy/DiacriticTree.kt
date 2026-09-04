package org.continuouspath.justtype.hierarchy

import android.content.res.AssetManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** Retained as advisory ordering metadata (common < uncommon < vietnamese); no longer a user setting. */
enum class DiacriticTier { COMMON, UNCOMMON, VIETNAMESE }

data class DiacriticVariant(
	val char: String,
	val upper: String?,
	val tier: DiacriticTier,
	val usedInVietnamese: Boolean,
	val rankInTier: Int,
)

data class DiacriticGroup(val base: Char, val variants: List<DiacriticVariant>)

private fun parseTier(s: String): DiacriticTier = when (s) {
	"common" -> DiacriticTier.COMMON
	"uncommon" -> DiacriticTier.UNCOMMON
	"vietnamese" -> DiacriticTier.VIETNAMESE
	else -> throw IllegalArgumentException("Unknown diacritic tier: $s")
}

fun loadDiacriticTree(assets: AssetManager): Map<Char, DiacriticGroup> {
	val text = assets.open("hierarchies/character_hierarchy.json").use { input ->
		BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
	}
	val groups = JSONObject(text).getJSONArray("diacritic_groups")
	val out = mutableMapOf<Char, DiacriticGroup>()
	for (i in 0 until groups.length()) {
		val g = groups.getJSONObject(i)
		val base = g.getString("base").single()
		val variantsArr = g.getJSONArray("variants")
		val variants = (0 until variantsArr.length()).map { idx ->
			val v = variantsArr.getJSONObject(idx)
			DiacriticVariant(
				char = v.getString("char"),
				upper = if (v.isNull("upper")) null else v.optString("upper").ifBlank { null },
				tier = parseTier(v.getString("tier")),
				usedInVietnamese = v.optBoolean("used_in_vietnamese", false),
				rankInTier = v.getInt("rank_in_tier"),
			)
		}
		out[base] = DiacriticGroup(base, variants)
	}
	return out
}

/**
 * Variants of [group] to display in LETTER SPELL MODE, filtered by the active language(s)' diacritics.
 *
 * [allowed] is the union of the in-scope languages' diacritic characters (see LanguageRegistry):
 *  - `null` = no filter (the "All variants" scope) — every variant.
 *  - empty set = no variants (the "Off" scope, or a language with no diacritics).
 *  - otherwise = variants whose (lowercased) glyph is in the set.
 *
 * Ordered by tier then rank-in-tier — a stable, language-independent display order.
 */
fun variantsForCharSet(group: DiacriticGroup, allowed: Set<Char>?): List<DiacriticVariant> {
	val filtered = if (allowed == null) {
		group.variants
	} else {
		group.variants.filter { v -> v.char.firstOrNull()?.lowercaseChar() in allowed }
	}
	return filtered.sortedWith(compareBy({ it.tier.ordinal }, { it.rankInTier }))
}

/** Set of base letters that have at least one diacritic variant. Used by MainLayoutTest. */
fun diacriticBearingLetters(tree: Map<Char, DiacriticGroup>): Set<Char> = tree.keys.filter { tree[it]?.variants?.isNotEmpty() == true }.toSet()
