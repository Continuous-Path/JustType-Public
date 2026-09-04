package org.continuouspath.justtype.hierarchy

import android.content.res.AssetManager

/** Holder for all three hierarchy data sets, loaded once at IME startup. */
data class Hierarchies(
	val symbolTree: SymbolBranch,
	val diacriticTree: Map<Char, DiacriticGroup>,
	val caseMap: CaseMap,
)

object HierarchyLoader {
	@Volatile private var cached: Hierarchies? = null

	fun get(assets: AssetManager): Hierarchies = cached ?: synchronized(this) {
		cached ?: load(assets).also { cached = it }
	}

	private fun load(assets: AssetManager): Hierarchies = Hierarchies(
		symbolTree = loadSymbolTree(assets),
		diacriticTree = loadDiacriticTree(assets),
		caseMap = loadCaseMap(assets),
	)
}
