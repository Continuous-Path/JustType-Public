package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionColumnsTest {

	@Test
	fun `single column when everything fits or splitting is disabled`() {
		assertThat(assignSelectionColumns(List(4) { 1 }, unitsPerColumn = 4, maxColumns = 3).toList())
			.containsExactly(0, 0, 0, 0).inOrder()
		assertThat(assignSelectionColumns(List(9) { 1 }, unitsPerColumn = 0, maxColumns = 3).toList())
			.isEqualTo(List(9) { 0 })
		assertThat(assignSelectionColumns(emptyList(), unitsPerColumn = 4, maxColumns = 3).size).isEqualTo(0)
	}

	@Test
	fun `extra columns fill exactly and column 0 absorbs the remainder`() {
		// 10 text rows, 4 per column, up to 3 columns: extras take 4 each from the
		// tail, the scrolling first column keeps the leading 2.
		val cols = assignSelectionColumns(List(10) { 1 }, unitsPerColumn = 4, maxColumns = 3).toList()
		assertThat(cols).containsExactly(0, 0, 1, 1, 1, 1, 2, 2, 2, 2).inOrder()
	}

	@Test
	fun `overflow beyond the column cap stays in the scrolling first column`() {
		// 12 rows, 4 per column, only 2 columns: nothing may be silently dropped —
		// the static column holds 4, the first column scrolls the other 8.
		val cols = assignSelectionColumns(List(12) { 1 }, unitsPerColumn = 4, maxColumns = 2).toList()
		assertThat(cols.count { it == 0 }).isEqualTo(8)
		assertThat(cols.count { it == 1 }).isEqualTo(4)
		assertThat(cols).isInOrder()
	}

	@Test
	fun `image rows split by height so a static column never overflows`() {
		// Image rows weigh 3 lines each; 6 of them at 4 units per column: the
		// static columns take one image each, column 0 scrolls the rest.
		val cols = assignSelectionColumns(List(6) { 3 }, unitsPerColumn = 4, maxColumns = 3).toList()
		assertThat(cols).containsExactly(0, 0, 0, 0, 1, 2).inOrder()
	}

	@Test
	fun `columns never exceed the entry count`() {
		// 2 oversized rows would compute 3+ columns by units; entries cap it.
		val cols = assignSelectionColumns(listOf(5, 5), unitsPerColumn = 2, maxColumns = 3).toList()
		assertThat(cols).containsExactly(0, 1).inOrder()
	}
}
