package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavTreeTest {

	private fun fixture(): NavTree = tree {
		node(0, 0, 1080, 200, viewId = "toolbar") {
			node(0, 0, 200, 200, clickable = true, viewId = "back")
		}
		node(0, 200, 1080, 2280, scrollable = true, viewId = "list") {
			node(0, 200, 1080, 400, clickable = true, viewId = "row0") {
				node(900, 250, 1050, 350, checkable = true, viewId = "toggle0")
			}
			node(0, 400, 1080, 600, clickable = true, viewId = "row1")
		}
	}

	@Test
	fun `dsl assigns pre-order indices and parent links`() {
		val t = fixture()
		assertThat(t.nodes.map { it.viewId }).containsExactly("toolbar", "back", "list", "row0", "toggle0", "row1").inOrder()
		assertThat(t.nodes.map { it.parent }).containsExactly(-1, 0, -1, 2, 3, 2).inOrder()
	}

	@Test
	fun `ancestors walks to the root, excluding self`() {
		val t = fixture()
		val toggle = t.nodes.first { it.viewId == "toggle0" }
		assertThat(t.ancestors(toggle.index).map { it.viewId }.toList()).containsExactly("row0", "list").inOrder()
		assertThat(t.ancestors(0).toList()).isEmpty()
	}

	@Test
	fun `descendants returns the full subtree, excluding self`() {
		val t = fixture()
		val list = t.nodes.first { it.viewId == "list" }
		assertThat(t.descendants(list.index).map { it.viewId }.toList()).containsExactly("row0", "toggle0", "row1")
		val back = t.nodes.first { it.viewId == "back" }
		assertThat(t.descendants(back.index).toList()).isEmpty()
	}
}
