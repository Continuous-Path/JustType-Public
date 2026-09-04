package org.continuouspath.justtype.navigation.engine

/** Fixture DSL: build a [NavTree] declaratively; nesting sets parent indices pre-order. */
fun tree(screen: NavBounds = NavBounds(0, 0, 1080, 2280), block: TreeBuilder.() -> Unit): NavTree {
	val builder = TreeBuilder(screen)
	builder.block()
	return builder.build()
}

class TreeBuilder(private val screen: NavBounds) {
	private val nodes = mutableListOf<NodeSnapshot>()
	private var currentParent = -1

	@Suppress("LongParameterList") // fixture DSL: named args mirror NodeSnapshot's fields
	fun node(
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		clickable: Boolean = false,
		longClickable: Boolean = false,
		focusable: Boolean = false,
		checkable: Boolean = false,
		editable: Boolean = false,
		scrollable: Boolean = false,
		adjustable: Boolean = false,
		visible: Boolean = true,
		enabled: Boolean = true,
		viewId: String? = null,
		className: String? = null,
		text: String = "",
		actionIds: Set<Int> = emptySet(),
		windowId: Int = 1,
		rows: Int = -1,
		cols: Int = -1,
		rtl: Boolean = false,
		children: TreeBuilder.() -> Unit = {},
	): Int {
		val index = nodes.size
		nodes.add(
			NodeSnapshot(
				index = index,
				parent = currentParent,
				windowId = windowId,
				bounds = NavBounds(left, top, right, bottom),
				visible = visible,
				enabled = enabled,
				clickable = clickable,
				longClickable = longClickable,
				checkable = checkable,
				editable = editable,
				focusable = focusable,
				scrollable = scrollable,
				adjustable = adjustable,
				actionIds = actionIds,
				className = className,
				viewId = viewId,
				textFingerprint = text.hashCode(),
				collectionRows = rows,
				collectionCols = cols,
				layoutRtl = rtl,
			),
		)
		val previousParent = currentParent
		currentParent = index
		children()
		currentParent = previousParent
		return index
	}

	fun build(generation: Long = 0L): NavTree = NavTree(nodes.toList(), screen, pinnedWindowId = -1, generation = generation)
}
