package org.continuouspath.justtype.hierarchy

/** Single vs repeated insertion, decided by the page ALL SYMBOLS MODE was entered from. */
enum class InsertMode { SINGLE, MULTI }

/** One slot of the current level, ready for the UI layer to turn into a key. */
sealed class SymbolSlotView {
	object Empty : SymbolSlotView()
	data class Leaf(val char: String) : SymbolSlotView()

	/** A descendable category/branch. [preview] is a 9-cell row-major grid ("" = empty cell). */
	data class Branch(val preview: List<String>, val absIndex: Int) : SymbolSlotView()
}

/**
 * Navigation state for ALL SYMBOLS MODE: the current node, an ancestor stack (each frame remembers
 * the page it was left on), the insert mode, and the page to return to when backing out at the root.
 *
 * Pure and platform-free — no Android or `KeyDef` dependency; the UI layer (JTUI) renders
 * [currentSlots] into keys. The tree is always rendered at [SymbolRaritySetting.UNCOMMON] (every
 * symbol visible); category depth and intra-level page order encode rarity, so there is no
 * user-facing rarity setting. Levels wider than six entries are paged via [more] (Key 6 = MORE).
 */
class AllSymbolsModeController(
	private val root: SymbolBranch,
	val insertMode: InsertMode,
	val entryPage: String,
) {
	private data class Frame(val node: SymbolBranch, var page: Int)

	private val stack = ArrayDeque<Frame>()
	private var current = Frame(root, 0)

	/** The full (un-paged) render of the current level; kept in sync with [current]. */
	private var rendered: List<RenderedSlot> = renderLevel(root, TIER)

	val atRoot: Boolean get() = stack.isEmpty()
	val hasMorePages: Boolean get() = rendered.size > CELLS_PER_PAGE

	/** 0-based index of the current page within the level. */
	val pageIndex: Int get() = current.page

	/** Number of pages at the current level (at least 1). */
	val pageCount: Int get() = maxOf(1, (rendered.size + CELLS_PER_PAGE - 1) / CELLS_PER_PAGE)

	/** Name of the current set of pages: the category label, or a top-level name at the root. */
	val currentSetName: String get() = if (atRoot) ROOT_SET_NAME else current.node.label

	/** The current page's up-to-six views; also refreshes the on-screen render cache. */
	fun currentSlots(): List<SymbolSlotView> {
		rendered = renderLevel(current.node, TIER)
		val start = current.page * CELLS_PER_PAGE
		return rendered.drop(start).take(CELLS_PER_PAGE).mapIndexed { pos, slot ->
			when (slot) {
				is RenderedSlot.Empty -> SymbolSlotView.Empty
				is RenderedSlot.Leaf -> SymbolSlotView.Leaf(slot.char)
				is RenderedSlot.Branch -> SymbolSlotView.Branch(previewGrid(slot.target), start + pos)
			}
		}
	}

	/** Page to the next six entries of the current level, wrapping past the last page. */
	fun more() {
		current.page = (current.page + 1) % pageCount
	}

	/** Descend into child [absIndex] (an absolute index into the level). Returns false if not a branch. */
	fun descend(absIndex: Int): Boolean {
		val target = (rendered.getOrNull(absIndex) as? RenderedSlot.Branch)?.target as? SymbolBranch ?: return false
		stack.addLast(current)
		current = Frame(target, 0)
		rendered = renderLevel(target, TIER)
		return true
	}

	/** Ascend one level, restoring the page it was left on. Returns false if already at the root. */
	fun ascend(): Boolean {
		current = stack.removeLastOrNull() ?: return false
		rendered = renderLevel(current.node, TIER)
		return true
	}

	/** Jump straight back to the root level (used after a multi-insert pick). */
	fun reset() {
		stack.clear()
		current = Frame(root, 0)
		rendered = renderLevel(root, TIER)
	}

	/** 9-cell preview of [node]'s first six children at their spatial cells — matches the child's page 1. */
	private fun previewGrid(node: SymbolNode): List<String> {
		val cells = MutableList(GRID_CELLS) { "" }
		if (node is SymbolBranch) {
			renderLevel(node, TIER).take(CELLS_PER_PAGE).forEachIndexed { j, slot ->
				cells[SPATIAL_GRID_CELLS[j]] = when (slot) {
					is RenderedSlot.Leaf -> slot.char
					is RenderedSlot.Branch -> slot.preview.firstOrNull() ?: ""
					is RenderedSlot.Empty -> ""
				}
			}
		}
		return cells
	}

	private companion object {
		val TIER = SymbolRaritySetting.UNCOMMON
		const val CELLS_PER_PAGE = 6 // == SPATIAL_PAGE_KEYS.size, the selection cells per drill page
		const val GRID_CELLS = 9
		const val ROOT_SET_NAME = "ALL SYMBOLS"
	}
}
