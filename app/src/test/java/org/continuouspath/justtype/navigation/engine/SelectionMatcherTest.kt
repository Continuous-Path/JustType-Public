package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SelectionMatcherTest {

	private fun fp(
		bounds: NavBounds,
		windowId: Int = 1,
		viewId: String? = "app:id/row",
		className: String? = "android.widget.Button",
		text: Int = 42,
	) = SelectionFingerprint(windowId, viewId, className, text, bounds)

	@Test
	fun `same identity shifted a few px still matches`() {
		val held = fp(NavBounds(100, 500, 300, 560))
		val fresh = listOf(fp(NavBounds(100, 498, 300, 558)))
		assertThat(SelectionMatcher.match(held, fresh)).isEqualTo(0)
	}

	@Test
	fun `bounds-identical node with a different identity never masquerades`() {
		val held = fp(NavBounds(100, 500, 300, 560))
		val fresh = listOf(
			fp(NavBounds(100, 500, 300, 560), text = 7),
			fp(NavBounds(100, 500, 300, 560), viewId = "app:id/other"),
			fp(NavBounds(100, 500, 300, 560), className = "android.widget.TextView"),
			fp(NavBounds(100, 500, 300, 560), windowId = 2),
		)
		assertThat(SelectionMatcher.match(held, fresh)).isNull()
	}

	@Test
	fun `identity match beyond center tolerance without containment is lost`() {
		val held = fp(NavBounds(100, 500, 300, 560))
		val fresh = listOf(fp(NavBounds(100, 900, 300, 960)))
		assertThat(SelectionMatcher.match(held, fresh)).isNull()
	}

	@Test
	fun `containment substitutes for center proximity`() {
		// The node expanded in place (e.g. a row growing on selection): old bounds inside new.
		val held = fp(NavBounds(400, 400, 500, 440))
		val fresh = listOf(fp(NavBounds(100, 380, 900, 600)))
		assertThat(SelectionMatcher.match(held, fresh)).isEqualTo(0)
	}

	@Test
	fun `nearest of multiple identity matches wins`() {
		// Repeating list rows share identity fields; proximity picks the right one.
		val held = fp(NavBounds(100, 500, 300, 560))
		val fresh = listOf(
			fp(NavBounds(100, 540, 300, 600)),
			fp(NavBounds(100, 505, 300, 565)),
		)
		assertThat(SelectionMatcher.match(held, fresh)).isEqualTo(1)
	}

	@Test
	fun `nearestToAnchor picks the closest center and handles empty`() {
		val anchor = NavBounds(0, 0, 100, 100)
		val fresh = listOf(
			NavBounds(500, 500, 600, 600),
			NavBounds(0, 90, 100, 190),
		)
		assertThat(SelectionMatcher.nearestToAnchor(anchor, fresh)).isEqualTo(1)
		assertThat(SelectionMatcher.nearestToAnchor(anchor, emptyList())).isNull()
	}

	@Test
	fun `snapshot fingerprint mirrors its identity fields`() {
		val tree = tree {
			node(0, 0, 200, 100, clickable = true, viewId = "app:id/a", className = "X", text = "hello")
		}
		val fp = tree.nodes[0].fingerprint()
		assertThat(fp.windowId).isEqualTo(tree.nodes[0].windowId)
		assertThat(fp.viewId).isEqualTo("app:id/a")
		assertThat(fp.className).isEqualTo("X")
		assertThat(fp.textFingerprint).isEqualTo(tree.nodes[0].textFingerprint)
		assertThat(fp.bounds).isEqualTo(NavBounds(0, 0, 200, 100))
	}
}
