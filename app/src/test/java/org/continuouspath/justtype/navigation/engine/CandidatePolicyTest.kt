package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CandidatePolicyTest {

	private fun picked(t: NavTree): List<String?> = CandidatePolicy.candidates(t).map { t.nodes[it].viewId }

	@Test
	fun `keeps visible actionable nodes, drops invisible and inert ones`() {
		val t = tree {
			node(0, 0, 1080, 2280) {
				node(0, 0, 100, 100, clickable = true, viewId = "clickable")
				node(0, 100, 100, 200, focusable = true, viewId = "focusable")
				node(0, 200, 100, 300, checkable = true, viewId = "checkable")
				node(0, 300, 100, 400, editable = true, viewId = "editable")
				node(0, 400, 100, 500, clickable = true, visible = false, viewId = "offscreen")
				node(0, 500, 100, 600, viewId = "inert")
			}
		}
		assertThat(picked(t)).containsExactly("clickable", "focusable", "checkable", "editable")
	}

	@Test
	fun `clickable row keeps itself and its checkable toggle, drops its focusable title`() {
		val t = tree {
			node(0, 200, 1080, 400, clickable = true, viewId = "row") {
				node(20, 250, 700, 350, focusable = true, viewId = "title")
				node(900, 250, 1050, 350, checkable = true, viewId = "toggle")
			}
		}
		assertThat(picked(t)).containsExactly("row", "toggle")
	}

	@Test
	fun `focusable title survives when its click target is not a candidate`() {
		val t = tree {
			// The clickable row is disabled, so it drops out — its title must stay reachable.
			node(0, 200, 1080, 400, clickable = true, enabled = false, viewId = "row") {
				node(20, 250, 700, 350, focusable = true, viewId = "title")
			}
		}
		assertThat(picked(t)).containsExactly("title")
	}

	@Test
	fun `scrollable and oversized containers are never candidates`() {
		val t = tree {
			node(0, 0, 1080, 2280, scrollable = true, focusable = true, viewId = "list") {
				node(0, 0, 1080, 200, clickable = true, viewId = "row0")
				node(0, 0, 1080, 2000, clickable = true, viewId = "huge") // > 70% of screen
			}
		}
		assertThat(picked(t)).containsExactly("row0")
	}

	@Test
	fun `focusable grouping container is dropped, bare focusable leaf is kept`() {
		val t = tree {
			node(0, 0, 1080, 800, focusable = true, viewId = "group") {
				node(0, 0, 500, 200, clickable = true, viewId = "child")
			}
			node(0, 900, 500, 1000, focusable = true, viewId = "leaf")
		}
		assertThat(picked(t)).containsExactly("child", "leaf")
	}

	@Test
	fun `equal-bounds clickable wrapper chain keeps only the outermost clickable`() {
		val t = tree {
			node(100, 100, 500, 200, clickable = true, viewId = "wrapper") {
				node(100, 100, 500, 200, clickable = true, viewId = "link")
			}
		}
		assertThat(picked(t)).containsExactly("wrapper")
	}

	@Test
	fun `focusable wrapper over an equal-bounds clickable link keeps the link`() {
		val t = tree {
			node(100, 100, 500, 200, focusable = true, viewId = "wrapper") {
				node(100, 100, 500, 200, clickable = true, viewId = "link")
			}
		}
		assertThat(picked(t)).containsExactly("link")
	}

	@Test
	fun `disabled nodes are skipped`() {
		val t = tree {
			node(0, 0, 500, 100, clickable = true, enabled = false, viewId = "disabled")
			node(0, 100, 500, 200, clickable = true, viewId = "enabled")
		}
		assertThat(picked(t)).containsExactly("enabled")
	}

	@Test
	fun `plain containers are not candidates even when their children are`() {
		val t = tree {
			node(0, 0, 1080, 2280, viewId = "root") {
				node(0, 0, 1080, 300, viewId = "container") {
					node(10, 10, 200, 100, clickable = true, viewId = "button")
				}
			}
		}
		assertThat(picked(t)).containsExactly("button")
	}

	@Test
	fun `effectiveClickTarget resolves self for actionable, nearest clickable ancestor otherwise`() {
		val t = tree {
			node(0, 0, 1080, 400, clickable = true, viewId = "row") {
				node(0, 0, 500, 200, viewId = "label")
				node(600, 0, 800, 200, checkable = true, viewId = "toggle")
			}
		}
		val byId = t.nodes.associateBy { it.viewId }
		assertThat(CandidatePolicy.effectiveClickTarget(t, byId["label"]!!.index)).isEqualTo(byId["row"]!!.index)
		assertThat(CandidatePolicy.effectiveClickTarget(t, byId["toggle"]!!.index)).isEqualTo(byId["toggle"]!!.index)
		assertThat(CandidatePolicy.effectiveClickTarget(t, byId["row"]!!.index)).isEqualTo(byId["row"]!!.index)
	}
}
