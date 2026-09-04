package org.continuouspath.justtype.navigation.engine

/**
 * Plans which node to scroll and with which accessibility action for a requested direction.
 * Pure decision logic — never performs anything; the service executes the returned plan.
 */
object ScrollPlanner {
	// AccessibilityNodeInfo.ACTION_SCROLL_FORWARD / _BACKWARD — mirrored so the engine stays android-free.
	const val ACTION_SCROLL_FORWARD_ID = 0x1000
	const val ACTION_SCROLL_BACKWARD_ID = 0x2000

	/** Resource-based action ids the adapter resolves at runtime; 0 = unavailable on this API level. */
	data class ScrollActionIds(
		val up: Int,
		val down: Int,
		val left: Int,
		val right: Int,
		val pageLeft: Int = 0,
		val pageRight: Int = 0,
	)

	data class NodeAction(val nodeIndex: Int, val actionId: Int)

	/** [fallback] is tried when [primary]'s performAction returns false. */
	data class ScrollPlan(val primary: NodeAction, val fallback: NodeAction?)

	private enum class Axis { VERTICAL, HORIZONTAL, UNKNOWN }

	fun plan(tree: NavTree, selection: Int?, dir: NavDirection, ids: ScrollActionIds): ScrollPlan? = discover(tree, selection) { planFor(it, dir, ids) }

	/** Directions with a viable plan — drives scroll-key greying and honest dispatch results. */
	fun availableDirections(tree: NavTree, selection: Int?, ids: ScrollActionIds): Set<NavDirection> = NavDirection.entries.filterTo(mutableSetOf()) { plan(tree, selection, it, ids) != null }

	/**
	 * Discovery-ordered container that HAS the generic scroll actions but can't serve [dir] because
	 * its axis is unknown (unrecognized carousels) — the injected-swipe fallback's target. Known-axis
	 * containers and merely-exhausted directions never qualify: their "no" is honest.
	 */
	fun axisBlockedTarget(tree: NavTree, selection: Int?, dir: NavDirection, ids: ScrollActionIds): Int? = discover(tree, selection) { node ->
		node.index.takeIf {
			node.enabled &&
				!node.adjustable &&
				fallbackId(dir, node.layoutRtl) in node.actionIds &&
				planFor(node, dir, ids) == null &&
				axisOf(node, ids) == Axis.UNKNOWN
		}
	}

	/**
	 * Target discovery, most specific first:
	 * 1. the selection or an ancestor of it;
	 * 2. smallest visible node containing the selection's center (list under a fixed header);
	 * 3. window-dominant — largest visible match, preferring one containing the screen center.
	 */
	private fun <T : Any> discover(tree: NavTree, selection: Int?, probe: (NodeSnapshot) -> T?): T? {
		if (selection != null) {
			(sequenceOf(tree.nodes[selection]) + tree.ancestors(selection))
				.firstNotNullOfOrNull(probe)?.let { return it }
			val sel = tree.nodes[selection].bounds
			tree.nodes.asSequence()
				.filter { it.index != selection && it.visible && it.bounds.contains(sel.centerX, sel.centerY) }
				.sortedBy { area(it.bounds) }
				.firstNotNullOfOrNull(probe)?.let { return it }
		}
		val viable = tree.nodes.mapNotNull { n -> if (n.visible) probe(n)?.let { n to it } else null }
		val (scx, scy) = tree.screen.centerX to tree.screen.centerY
		return (
			viable.filter { it.first.bounds.contains(scx, scy) }.maxByOrNull { area(it.first.bounds) }
				?: viable.maxByOrNull { area(it.first.bounds) }
			)?.second
	}

	/** The best action pair [dir] can use on [node], or null when the node can't serve it. */
	private fun planFor(node: NodeSnapshot, dir: NavDirection, ids: ScrollActionIds): ScrollPlan? {
		if (!node.enabled || node.adjustable) return null
		val fwdBwd = fallbackId(dir, node.layoutRtl).takeIf { it in node.actionIds && axisAllows(node, dir, ids) }
		val physical = physicalId(node, dir, ids)
		return when {
			physical != null -> ScrollPlan(NodeAction(node.index, physical), fwdBwd?.let { NodeAction(node.index, it) })
			fwdBwd != null -> ScrollPlan(NodeAction(node.index, fwdBwd), null)
			else -> null
		}
	}

	/** Directional action id when the node has one, else a page id for horizontal intents. */
	private fun physicalId(node: NodeSnapshot, dir: NavDirection, ids: ScrollActionIds): Int? {
		val directional = when (dir) {
			NavDirection.UP -> ids.up
			NavDirection.DOWN -> ids.down
			NavDirection.LEFT -> ids.left
			NavDirection.RIGHT -> ids.right
		}
		if (directional != 0 && directional in node.actionIds) return directional
		val page = when (dir) {
			NavDirection.LEFT -> ids.pageLeft
			NavDirection.RIGHT -> ids.pageRight
			else -> 0
		}
		return page.takeIf { it != 0 && it in node.actionIds }
	}

	/**
	 * FORWARD/BACKWARD serve a direction only when the container's axis matches. UNKNOWN axis
	 * serves vertical intents only — guessing horizontal scrolls vertical pages sideways-on-request.
	 */
	private fun axisAllows(node: NodeSnapshot, dir: NavDirection, ids: ScrollActionIds): Boolean = when (axisOf(node, ids)) {
		Axis.HORIZONTAL -> dir == NavDirection.LEFT || dir == NavDirection.RIGHT
		Axis.VERTICAL, Axis.UNKNOWN -> dir == NavDirection.UP || dir == NavDirection.DOWN
	}

	/** Axis signals, strongest first: physical directional actions, collection shape, class-name hints. */
	private fun axisOf(node: NodeSnapshot, ids: ScrollActionIds): Axis {
		val vertical = ids.up in node.actionIds || ids.down in node.actionIds
		val horizontal = ids.left in node.actionIds || ids.right in node.actionIds
		if (vertical != horizontal) return if (vertical) Axis.VERTICAL else Axis.HORIZONTAL
		if (node.collectionRows > 1 && node.collectionCols <= 1) return Axis.VERTICAL
		if (node.collectionCols > 1 && node.collectionRows <= 1) return Axis.HORIZONTAL
		val name = node.className.orEmpty()
		return when {
			name.contains("HorizontalScrollView") || name.contains("ViewPager") -> Axis.HORIZONTAL
			name.contains("ScrollView") || name.contains("ListView") -> Axis.VERTICAL
			else -> Axis.UNKNOWN
		}
	}

	/**
	 * FORWARD advances toward the collection's end; in RTL layouts "end" is to the LEFT, so the
	 * horizontal mapping flips. Vertical is unaffected (top-to-bottom regardless of layout direction).
	 */
	private fun fallbackId(dir: NavDirection, rtl: Boolean = false): Int = when (dir) {
		NavDirection.UP -> ACTION_SCROLL_BACKWARD_ID
		NavDirection.DOWN -> ACTION_SCROLL_FORWARD_ID
		NavDirection.LEFT -> if (rtl) ACTION_SCROLL_FORWARD_ID else ACTION_SCROLL_BACKWARD_ID
		NavDirection.RIGHT -> if (rtl) ACTION_SCROLL_BACKWARD_ID else ACTION_SCROLL_FORWARD_ID
	}
}

private fun area(b: NavBounds): Long = b.width.toLong() * b.height
