package org.continuouspath.justtype.hierarchy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VariantLayoutTest {

	private fun items(n: Int): List<String> = (1..n).map { "v$it" }

	private fun List<LayoutSlot>.itemCount(): Int = sumOf {
		when (it) {
			is LayoutSlot.Single -> 1
			is LayoutSlot.Group -> it.values.size
		}
	}

	private fun VariantLayout.totalItems(): Int = primary.itemCount() + neighbor.itemCount()

	private fun VariantLayout.subgroupCount(): Int = primary.count { it is LayoutSlot.Group } + neighbor.count { it is LayoutSlot.Group }

	@Test fun `case A - 6 or fewer all singles on primary`() {
		for (n in 1..6) {
			val l = layoutVariants(items(n), hasNeighbor = true)
			assertThat(l.primary).hasSize(n)
			assertThat(l.primary.all { it is LayoutSlot.Single }).isTrue()
			assertThat(l.neighbor).isEmpty()
			assertThat(l.totalItems()).isEqualTo(n)
		}
	}

	@Test fun `case B - 7 to 12 split as singles across both keys`() {
		for (n in 7..12) {
			val l = layoutVariants(items(n), hasNeighbor = true)
			assertThat(l.primary).hasSize(6)
			assertThat(l.primary.all { it is LayoutSlot.Single }).isTrue()
			assertThat(l.neighbor.all { it is LayoutSlot.Single }).isTrue()
			assertThat(l.subgroupCount()).isEqualTo(0)
			assertThat(l.totalItems()).isEqualTo(n)
		}
	}

	@Test fun `case C - 13 to 21 primary stays 6 singles neighbor gains subgroups`() {
		for (n in 13..21) {
			val l = layoutVariants(items(n), hasNeighbor = true, subgroupSize = 4)
			assertThat(l.primary).hasSize(6)
			assertThat(l.primary.all { it is LayoutSlot.Single }).isTrue()
			assertThat(l.neighbor.count { it is LayoutSlot.Group }).isAtLeast(1)
			assertThat(l.totalItems()).isEqualTo(n)
		}
	}

	@Test fun `case D - over 21 primary also gains subgroups`() {
		for (n in 22..30) {
			val l = layoutVariants(items(n), hasNeighbor = true, subgroupSize = 4)
			assertThat(l.primary.count { it is LayoutSlot.Group }).isAtLeast(1)
			assertThat(l.neighbor.count { it is LayoutSlot.Group }).isEqualTo(3)
			assertThat(l.totalItems()).isEqualTo(n)
		}
	}

	@Test fun `never drops items up to 30 with subgroup size 4`() {
		for (n in 1..30) {
			assertThat(layoutVariants(items(n), hasNeighbor = true, subgroupSize = 4).totalItems()).isEqualTo(n)
		}
	}

	@Test fun `subgroup size 3 shifts C-D boundary to 18`() {
		// At N=18, all sub-groups still fit on neighbor (case C: primary all singles).
		val at18 = layoutVariants(items(18), hasNeighbor = true, subgroupSize = 3)
		assertThat(at18.primary.all { it is LayoutSlot.Single }).isTrue()
		// At N=19, the base key must take a sub-group (case D).
		val at19 = layoutVariants(items(19), hasNeighbor = true, subgroupSize = 3)
		assertThat(at19.primary.count { it is LayoutSlot.Group }).isAtLeast(1)
		assertThat(at19.totalItems()).isEqualTo(19)
	}

	@Test fun `at most three subgroups per key respects row constraint`() {
		for (n in 1..30) {
			val l = layoutVariants(items(n), hasNeighbor = true, subgroupSize = 4)
			assertThat(l.primary.count { it is LayoutSlot.Group }).isAtMost(3)
			assertThat(l.neighbor.count { it is LayoutSlot.Group }).isAtMost(3)
		}
	}

	@Test fun `no-neighbor packs onto a single key without dropping`() {
		val l = layoutVariants(items(12), hasNeighbor = false, subgroupSize = 4)
		assertThat(l.neighbor).isEmpty()
		assertThat(l.primary.itemCount()).isEqualTo(12)
	}

	@Test fun `most common items stay as singles`() {
		// First several items (most common) should be Single, not buried in a group.
		val l = layoutVariants(items(20), hasNeighbor = true, subgroupSize = 4)
		val firstSix = l.primary.take(6)
		assertThat(firstSix.all { it is LayoutSlot.Single }).isTrue()
	}

	@Test fun `preview grid puts base at NW and groups on distinct rows`() {
		val slots = listOf(
			LayoutSlot.Single("u"),
			LayoutSlot.Single("a"),
			LayoutSlot.Group(listOf("x", "y")),
			LayoutSlot.Group(listOf("z", "w")),
		)
		val grid = slots.toPreviewGrid()
		assertThat(grid[0]).isEqualTo("u") // base at NW
		// Group strings on distinct rows: grid rows are {0,1,2},{3,4,5},{6,7,8}.
		val groupCells = (0..8).filter { grid[it] == "xy" || grid[it] == "zw" }
		assertThat(groupCells.map { it / 3 }).containsNoDuplicates()
	}

	@Test fun `assignSpatial places base at NW and matches preview-drill mapping`() {
		val slots = listOf(LayoutSlot.Single("u"), LayoutSlot.Single("a"), LayoutSlot.Single("e"))
		val spatial = slots.assignSpatial()
		assertThat((spatial[0] as LayoutSlot.Single).value).isEqualTo("u") // base → NW (spatial 0)
		// SPATIAL_PAGE_KEYS aligns one-to-one with the 6 spatial slots.
		assertThat(SPATIAL_PAGE_KEYS).hasSize(6)
		assertThat(SPATIAL_PAGE_KEYS).containsNoneOf(1, 6) // 1=CANCEL, 6=SHIFT reserved
	}
}
