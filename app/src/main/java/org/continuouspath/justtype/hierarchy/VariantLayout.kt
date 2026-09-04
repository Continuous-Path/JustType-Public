package org.continuouspath.justtype.hierarchy

/**
 * Partitioning of a list of variant strings across a primary key and an optional adjacent
 * (neighbor) key, for hierarchical display in Spell Mode (and later ALL SYMBOLS MODE).
 *
 * Strategy (N = total items, including the base char as item 0; S = [subgroupSize]):
 *  - **A** N ≤ 6 — all on the primary key, one per cell.
 *  - **B** 7 ≤ N ≤ 12 — 6 on primary, remainder on neighbor, all singles.
 *  - **C** 13 ≤ N ≤ 6 + (3 + 3·S) — primary 6 singles; neighbor (6−k) singles + k sub-groups.
 *  - **D** larger — the primary key also takes sub-groups (1..3 of them).
 *
 * Sub-groups hold up to S items; at most 3 per key (the row constraint — see
 * [VariantLayout.toPreviewGrid]). For S=4 this accommodates up to 30 variants without dropping
 * any; for S=3, up to 24. No variants are ever dropped: if the configured sub-group size can't
 * absorb everything, the sub-groups grow rather than truncate.
 */
sealed class LayoutSlot {
	data class Single(val value: String) : LayoutSlot()
	data class Group(val values: List<String>) : LayoutSlot()
}

data class VariantLayout(
	val primary: List<LayoutSlot>,
	val neighbor: List<LayoutSlot>,
) {
	companion object {
		const val CELLS_PER_KEY = 6
		const val MAX_SUBGROUPS_PER_KEY = 3
	}
}

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

private fun packKey(items: List<String>, numSubgroups: Int, subgroupSize: Int): List<LayoutSlot> {
	if (numSubgroups <= 0 || items.size <= VariantLayout.CELLS_PER_KEY) {
		return items.map { LayoutSlot.Single(it) }
	}
	val singlesCount = VariantLayout.CELLS_PER_KEY - numSubgroups
	val singles = items.take(singlesCount).map { LayoutSlot.Single(it) }
	val rest = items.drop(singlesCount)
	// Grow sub-groups beyond subgroupSize only if forced (no-neighbor overflow), never drop.
	val perGroup = maxOf(subgroupSize, ceilDiv(rest.size, numSubgroups))
	val groups = rest.chunked(perGroup).map { LayoutSlot.Group(it) }
	return singles + groups
}

/**
 * Partition [items] (most-common first; item 0 is the base character) across a primary key and,
 * when [hasNeighbor] is true, an adjacent key. See [VariantLayout] for the strategy.
 */
fun layoutVariants(items: List<String>, hasNeighbor: Boolean, subgroupSize: Int = 4): VariantLayout {
	val n = items.size
	val cells = VariantLayout.CELLS_PER_KEY
	val maxSub = VariantLayout.MAX_SUBGROUPS_PER_KEY

	if (n <= cells) {
		return VariantLayout(items.map { LayoutSlot.Single(it) }, emptyList())
	}
	if (!hasNeighbor) {
		val k = ceilDiv(n - cells, subgroupSize - 1).coerceIn(1, maxSub)
		return VariantLayout(packKey(items, k, subgroupSize), emptyList())
	}
	if (n <= 2 * cells) {
		return VariantLayout(
			items.take(cells).map { LayoutSlot.Single(it) },
			items.drop(cells).map { LayoutSlot.Single(it) },
		)
	}
	// Cases C / D — sub-groups required. Fill neighbor sub-groups before the base key's.
	val totalSubgroups = ceilDiv(n - 2 * cells, subgroupSize - 1)
	val kn = totalSubgroups.coerceAtMost(maxSub)
	val kb = (totalSubgroups - kn).coerceAtMost(maxSub)
	val baseCap = (cells - kb) + kb * subgroupSize
	return VariantLayout(
		packKey(items.take(baseCap), kb, subgroupSize),
		packKey(items.drop(baseCap), kn, subgroupSize),
	)
}

// Spatial positions 0..5 = NW, NE, W, E, SW, SE (row = index / 2). These map a slot's spatial
// position to BOTH a 9-cell key-face preview index and an 8-key drill-page key index, so the
// glyph shown in a preview cell appears on the matching drill-page key.
internal val SPATIAL_GRID_CELLS = listOf(0, 2, 3, 5, 6, 8)
val SPATIAL_PAGE_KEYS = listOf(0, 2, 3, 4, 5, 7)

/**
 * Assign this key's slots to the six spatial positions (NW, NE, W, E, SW, SE). The base
 * character (first Single) always lands at NW; Group slots take one cell per row so no two group
 * strings share a horizontal row; remaining singles fill what's left. Returns a 6-element list
 * indexed by spatial position (null = empty).
 */
fun List<LayoutSlot>.assignSpatial(): List<LayoutSlot?> {
	val out = arrayOfNulls<LayoutSlot>(6)
	val singles = ArrayDeque(filterIsInstance<LayoutSlot.Single>())
	val groups = ArrayDeque(filterIsInstance<LayoutSlot.Group>())
	if (singles.isNotEmpty()) out[0] = singles.removeFirst() // base → NW
	// One group per row (rows: {0,1},{2,3},{4,5}) into that row's free cell — keeps group strings
	// off the same horizontal row.
	for (row in 0..2) {
		val freeCell = listOf(row * 2, row * 2 + 1).firstOrNull { out[it] == null }
		if (groups.isNotEmpty() && freeCell != null) out[freeCell] = groups.removeFirst()
	}
	for (i in 0 until 6) if (out[i] == null && singles.isNotEmpty()) out[i] = singles.removeFirst()
	for (i in 0 until 6) if (out[i] == null && groups.isNotEmpty()) out[i] = groups.removeFirst()
	return out.toList()
}

/**
 * Render this key's slots into a 9-cell preview grid (row-major, "" for empty), using the same
 * spatial assignment as the drill page so preview cells line up with drill keys.
 */
fun List<LayoutSlot>.toPreviewGrid(): List<String> {
	val cells = MutableList(9) { "" }
	assignSpatial().forEachIndexed { i, slot ->
		if (slot != null) {
			cells[SPATIAL_GRID_CELLS[i]] = when (slot) {
				is LayoutSlot.Single -> slot.value
				is LayoutSlot.Group -> slot.values.joinToString("")
			}
		}
	}
	return cells
}
