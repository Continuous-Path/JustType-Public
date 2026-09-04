package org.continuouspath.justtype.navigation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.continuouspath.justtype.navigation.engine.NavBounds
import org.continuouspath.justtype.navigation.engine.NavTree
import org.continuouspath.justtype.navigation.engine.NodeSnapshot
import org.continuouspath.justtype.navigation.engine.SelectionFingerprint

/**
 * Walks live accessibility trees into an immutable [NavTree] plus the parallel live-node
 * list (engine indices ↔ live nodes). The only place the tree is traversed.
 */
class NodeSnapshotter {
	class Capture(val tree: NavTree, val live: List<AccessibilityNodeInfo>)

	companion object {
		/** Fingerprint a live node with the same field derivations as [Walker.snapshot]. */
		fun fingerprintOf(node: AccessibilityNodeInfo): SelectionFingerprint {
			val r = Rect().also { node.getBoundsInScreen(it) }
			return SelectionFingerprint(
				windowId = node.windowId,
				viewId = node.viewIdResourceName,
				className = node.className?.toString(),
				textFingerprint = textFingerprint(node),
				bounds = NavBounds(r.left, r.top, r.right, r.bottom),
			)
		}

		private fun textFingerprint(node: AccessibilityNodeInfo): Int = "${node.text}|${node.contentDescription}".hashCode()
	}

	/**
	 * [overlayUp]: a full-screen capture overlay makes the system report app nodes as
	 * isVisibleToUser=false, so visibility falls back to on-screen bounds intersection.
	 */
	fun capture(
		roots: List<AccessibilityNodeInfo>,
		screen: NavBounds,
		overlayUp: Boolean,
		layoutRtl: Boolean,
		generation: Long,
		pinnedWindowId: Int = -1,
	): Capture {
		val walker = Walker(screen, overlayUp, layoutRtl)
		for (root in roots) walker.walk(root, parent = -1)
		return Capture(NavTree(walker.snapshots, screen, pinnedWindowId = pinnedWindowId, generation = generation), walker.live)
	}

	/** Return a spent capture's nodes to the pre-33 pool, keeping the one still referenced (the selection). */
	fun release(capture: Capture, keep: AccessibilityNodeInfo?) {
		if (android.os.Build.VERSION.SDK_INT >= 33) return // recycle() is a no-op from 33 on
		for (node in capture.live) {
			if (node === keep) continue
			@Suppress("DEPRECATION")
			runCatching { node.recycle() }
		}
	}

	private class Walker(
		private val screen: NavBounds,
		private val overlayUp: Boolean,
		private val layoutRtl: Boolean,
	) {
		val snapshots = mutableListOf<NodeSnapshot>()
		val live = mutableListOf<AccessibilityNodeInfo>()

		fun walk(node: AccessibilityNodeInfo, parent: Int) {
			val index = snapshots.size
			snapshots.add(snapshot(node, index, parent))
			live.add(node)
			for (i in 0 until node.childCount) {
				val child = node.getChild(i) ?: continue
				walk(child, index)
			}
		}

		private fun snapshot(node: AccessibilityNodeInfo, index: Int, parent: Int): NodeSnapshot {
			val r = Rect().also { node.getBoundsInScreen(it) }
			val bounds = NavBounds(r.left, r.top, r.right, r.bottom)
			val hasSize = !bounds.isEmpty
			val visible = if (overlayUp) hasSize && bounds.intersects(screen) else node.isVisibleToUser && hasSize
			val collection = node.collectionInfo
			return NodeSnapshot(
				index = index,
				parent = parent,
				windowId = node.windowId,
				bounds = bounds,
				visible = visible,
				enabled = node.isEnabled,
				clickable = node.isClickable,
				longClickable = node.isLongClickable,
				checkable = node.isCheckable,
				editable = node.isEditable,
				focusable = node.isFocusable,
				scrollable = node.isScrollable,
				adjustable = node.rangeInfo != null,
				actionIds = node.actionList.mapTo(mutableSetOf()) { it.id },
				className = node.className?.toString(),
				viewId = node.viewIdResourceName,
				textFingerprint = textFingerprint(node),
				collectionRows = collection?.rowCount ?: -1,
				collectionCols = collection?.columnCount ?: -1,
				layoutRtl = layoutRtl,
			)
		}
	}
}
