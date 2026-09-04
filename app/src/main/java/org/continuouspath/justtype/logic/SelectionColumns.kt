package org.continuouspath.justtype.logic

/**
 * Assigns selection-list entries to display columns.
 *
 * [unitWeights] is each entry's height in line units (text row = 1, image row =
 * its pixel height in lines), so mixed pages split by real height instead of row
 * count. Extra columns are static views that must fit exactly, so they are filled
 * from the tail at up to [unitsPerColumn] each; the first column — the scrolling,
 * selection-pinned view — keeps the remainder, where overflow stays reachable.
 *
 * Returns the column index per entry (non-decreasing). All zeros when a single
 * column suffices or [unitsPerColumn] <= 0.
 */
internal fun assignSelectionColumns(
	unitWeights: List<Int>,
	unitsPerColumn: Int,
	maxColumns: Int,
): IntArray {
	val n = unitWeights.size
	val columnOf = IntArray(n)
	if (n == 0 || unitsPerColumn <= 0) return columnOf
	val total = unitWeights.sum()
	if (total <= unitsPerColumn) return columnOf

	val cap = if (maxColumns > 0) maxColumns else Int.MAX_VALUE
	val columns = ((total + unitsPerColumn - 1) / unitsPerColumn).coerceAtMost(cap).coerceAtMost(n)
	if (columns <= 1) return columnOf

	var col = columns - 1
	var used = 0
	var i = n - 1
	while (i >= 0 && col > 0) {
		val u = unitWeights[i]
		if (used > 0 && used + u > unitsPerColumn) {
			col--
			used = 0
			continue
		}
		columnOf[i] = col
		used += u
		i--
	}
	// Entries 0..i remain in column 0.
	return columnOf
}
