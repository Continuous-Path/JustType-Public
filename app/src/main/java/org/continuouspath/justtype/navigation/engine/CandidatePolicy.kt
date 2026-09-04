package org.continuouspath.justtype.navigation.engine

/**
 * Which nodes are selectable: actionable elements only, deduped so nested wrappers of
 * the same tap target never appear as separate selection stops.
 */
object CandidatePolicy {
	// AccessibilityNodeInfo.ACTION_CLICK — mirrored so the engine stays android-free.
	private const val ACTION_CLICK_ID = 0x10

	// Bigger than this fraction of the screen is a scroll surface, not a selection stop.
	private const val MAX_SCREEN_AREA_FRACTION = 0.7

	fun candidates(tree: NavTree): List<Int> {
		val count = tree.nodes.size
		if (count == 0) return emptyList()
		val actionable = BooleanArray(count) { directlyActionable(tree.nodes[it]) }
		// Pre-order guarantees parent < child, so one reverse pass fills descendant info.
		val hasActionableDesc = BooleanArray(count)
		for (i in count - 1 downTo 0) {
			val parent = tree.nodes[i].parent
			if (parent >= 0 && (actionable[i] || hasActionableDesc[i])) hasActionableDesc[parent] = true
		}
		val maxArea = (tree.screen.area * MAX_SCREEN_AREA_FRACTION).toLong()
		val eligible = tree.nodes.filter { node ->
			node.visible &&
				node.enabled &&
				!node.bounds.isEmpty &&
				!node.scrollable &&
				node.bounds.area <= maxArea &&
				(actionable[node.index] || (node.focusable && !hasActionableDesc[node.index]))
		}.map { it.index }
		val inSet = BooleanArray(count)
		for (i in eligible) inSet[i] = true
		return eligible.filter { !droppedAsGroupMember(tree, it, inSet, actionable) && !droppedAsWrapper(tree, it, inSet, actionable) }
	}

	/** The node a tap on [index] would trigger: itself when directly actionable, else the nearest clickable ancestor. */
	fun effectiveClickTarget(tree: NavTree, index: Int): Int? {
		if (directlyActionable(tree.nodes[index])) return index
		return tree.ancestors(index).firstOrNull { it.clickable }?.index
	}

	private fun directlyActionable(node: NodeSnapshot): Boolean = node.clickable || node.longClickable || node.checkable || node.editable || ACTION_CLICK_ID in node.actionIds

	/** A focusable-only member is represented by its click target whenever that target is itself a candidate. */
	private fun droppedAsGroupMember(tree: NavTree, index: Int, inSet: BooleanArray, actionable: BooleanArray): Boolean {
		if (actionable[index]) return false
		val target = effectiveClickTarget(tree, index) ?: return false
		return target != index && inSet[target]
	}

	/**
	 * Equal-bounds wrapper chains (WebView link-in-container, interop wrappers) keep exactly
	 * one member: the outermost directly-actionable one, or the outermost overall.
	 */
	private fun droppedAsWrapper(tree: NavTree, index: Int, inSet: BooleanArray, actionable: BooleanArray): Boolean {
		val bounds = tree.nodes[index].bounds
		val equalAncestor = tree.ancestors(index).any { inSet[it.index] && it.bounds == bounds && (actionable[it.index] || !actionable[index]) }
		if (equalAncestor) return true
		if (actionable[index]) return false
		return tree.descendants(index).any { inSet[it.index] && it.bounds == bounds && actionable[it.index] }
	}
}
