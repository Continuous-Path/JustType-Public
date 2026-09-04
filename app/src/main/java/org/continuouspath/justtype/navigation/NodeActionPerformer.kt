package org.continuouspath.justtype.navigation

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Single gate for node actions. refresh() first so the action targets the node's
 * current state — a dead node fails honestly instead of acting on a stale snapshot.
 */
class NodeActionPerformer {
	fun perform(node: AccessibilityNodeInfo, actionId: Int): Boolean = node.refresh() && node.performAction(actionId)
}
