package org.continuouspath.justtype.navigation.engine

/** One node, frozen at capture time. [index] is its position in [NavTree.nodes]; [parent] is an index, -1 for roots. */
data class NodeSnapshot(
	val index: Int,
	val parent: Int,
	val windowId: Int,
	val bounds: NavBounds,
	val visible: Boolean,
	val enabled: Boolean,
	val clickable: Boolean,
	val longClickable: Boolean,
	val checkable: Boolean,
	val editable: Boolean,
	val focusable: Boolean,
	val scrollable: Boolean,
	// RangeInfo widgets (sliders): their FORWARD/BACKWARD adjust the value, not a viewport.
	val adjustable: Boolean,
	val actionIds: Set<Int>,
	val className: String?,
	val viewId: String?,
	val textFingerprint: Int,
	val collectionRows: Int,
	val collectionCols: Int,
	val layoutRtl: Boolean,
)

/** Immutable snapshot of the accessibility tree(s): [nodes] in pre-order, roots first. */
data class NavTree(
	val nodes: List<NodeSnapshot>,
	val screen: NavBounds,
	val pinnedWindowId: Int,
	val generation: Long,
) {
	fun ancestors(index: Int): Sequence<NodeSnapshot> = generateSequence(nodes[index]) { n ->
		if (n.parent >= 0) nodes[n.parent] else null
	}.drop(1)

	fun descendants(index: Int): Sequence<NodeSnapshot> = nodes.asSequence().filter { n -> n.index != index && ancestors(n.index).any { it.index == index } }
}
