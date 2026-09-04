package org.continuouspath.justtype.navigation.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollPlannerTest {
	private val ids = ScrollPlanner.ScrollActionIds(up = 101, down = 102, left = 103, right = 104, pageLeft = 105, pageRight = 106)
	private val fwd = ScrollPlanner.ACTION_SCROLL_FORWARD_ID
	private val bwd = ScrollPlanner.ACTION_SCROLL_BACKWARD_ID

	@Test
	fun `ancestor list preferred over larger window scrollable`() {
		var selection = -1
		var innerList = -1
		val t = tree {
			node(0, 0, 1080, 2280, scrollable = true, actionIds = setOf(fwd, bwd), className = "android.widget.ScrollView") {
				innerList = node(0, 1000, 1080, 2000, scrollable = true, actionIds = setOf(fwd), className = "android.widget.ListView") {
					selection = node(0, 1000, 1080, 1100, clickable = true)
				}
			}
		}
		val plan = ScrollPlanner.plan(t, selection, NavDirection.DOWN, ids)
		assertThat(plan!!.primary).isEqualTo(ScrollPlanner.NodeAction(innerList, fwd))
	}

	@Test
	fun `window-dominant target found from a non-scrollable selection and from no selection`() {
		var toolbarBtn = -1
		var list = -1
		val t = tree {
			node(0, 0, 1080, 2280) {
				node(0, 0, 1080, 200) {
					toolbarBtn = node(20, 50, 120, 150, clickable = true)
				}
				list = node(0, 200, 1080, 2280, scrollable = true, actionIds = setOf(fwd), className = "android.widget.ListView")
			}
		}
		assertThat(ScrollPlanner.plan(t, toolbarBtn, NavDirection.DOWN, ids)!!.primary.nodeIndex).isEqualTo(list)
		assertThat(ScrollPlanner.plan(t, null, NavDirection.DOWN, ids)!!.primary.nodeIndex).isEqualTo(list)
	}

	@Test
	fun `scroll actions qualify a target without the scrollable flag`() {
		var webArea = -1
		val t = tree {
			webArea = node(0, 0, 1080, 2000, actionIds = setOf(fwd, bwd), className = "android.webkit.WebView")
		}
		assertThat(ScrollPlanner.plan(t, null, NavDirection.DOWN, ids)!!.primary.nodeIndex).isEqualTo(webArea)
	}

	@Test
	fun `forward-only list scrolls down but not up`() {
		val t = tree {
			node(0, 0, 1080, 2000, scrollable = true, actionIds = setOf(fwd), className = "android.widget.ListView")
		}
		val dirs = ScrollPlanner.availableDirections(t, null, ids)
		assertThat(dirs).containsExactly(NavDirection.DOWN)
	}

	@Test
	fun `unknown axis never serves horizontal intents`() {
		val t = tree {
			node(0, 0, 1080, 2000, scrollable = true, actionIds = setOf(fwd, bwd))
		}
		assertThat(ScrollPlanner.plan(t, null, NavDirection.RIGHT, ids)).isNull()
		assertThat(ScrollPlanner.plan(t, null, NavDirection.LEFT, ids)).isNull()
		assertThat(ScrollPlanner.plan(t, null, NavDirection.DOWN, ids)).isNotNull()
		assertThat(ScrollPlanner.plan(t, null, NavDirection.UP, ids)).isNotNull()
	}

	@Test
	fun `horizontal class name maps left-right onto backward-forward`() {
		val t = tree {
			node(0, 0, 1080, 400, scrollable = true, actionIds = setOf(fwd, bwd), className = "android.widget.HorizontalScrollView")
		}
		assertThat(ScrollPlanner.plan(t, null, NavDirection.RIGHT, ids)!!.primary.actionId).isEqualTo(fwd)
		assertThat(ScrollPlanner.plan(t, null, NavDirection.LEFT, ids)!!.primary.actionId).isEqualTo(bwd)
		assertThat(ScrollPlanner.plan(t, null, NavDirection.UP, ids)).isNull()
	}

	@Test
	fun `RTL horizontal flips left-right onto forward-backward`() {
		val t = tree {
			node(0, 0, 1080, 400, scrollable = true, actionIds = setOf(fwd, bwd), className = "android.widget.HorizontalScrollView", rtl = true)
		}
		// In RTL, FORWARD advances toward the (left) end, so RIGHT→BACKWARD and LEFT→FORWARD.
		assertThat(ScrollPlanner.plan(t, null, NavDirection.RIGHT, ids)!!.primary.actionId).isEqualTo(bwd)
		assertThat(ScrollPlanner.plan(t, null, NavDirection.LEFT, ids)!!.primary.actionId).isEqualTo(fwd)
	}

	@Test
	fun `RTL does not affect vertical forward-backward mapping`() {
		val t = tree {
			node(0, 0, 1080, 2000, scrollable = true, actionIds = setOf(fwd, bwd), className = "android.widget.ListView", rtl = true)
		}
		assertThat(ScrollPlanner.plan(t, null, NavDirection.DOWN, ids)!!.primary.actionId).isEqualTo(fwd)
		assertThat(ScrollPlanner.plan(t, null, NavDirection.UP, ids)!!.primary.actionId).isEqualTo(bwd)
	}

	@Test
	fun `physical directional action preferred with generic fallback`() {
		val t = tree {
			node(0, 0, 1080, 2000, scrollable = true, actionIds = setOf(ids.up, bwd))
		}
		val plan = ScrollPlanner.plan(t, null, NavDirection.UP, ids)!!
		assertThat(plan.primary.actionId).isEqualTo(ids.up)
		assertThat(plan.fallback!!.actionId).isEqualTo(bwd)
	}

	@Test
	fun `page actions serve horizontal when no directional id exists`() {
		val t = tree {
			node(0, 0, 1080, 400, scrollable = true, actionIds = setOf(ids.pageLeft, ids.pageRight, fwd, bwd), className = "androidx.viewpager.widget.ViewPager")
		}
		val plan = ScrollPlanner.plan(t, null, NavDirection.LEFT, ids)!!
		assertThat(plan.primary.actionId).isEqualTo(ids.pageLeft)
		assertThat(plan.fallback!!.actionId).isEqualTo(bwd)
	}

	@Test
	fun `list under a fixed header found by selection-center containment`() {
		var headerBtn = -1
		var list = -1
		val t = tree {
			node(0, 0, 1080, 2280) {
				headerBtn = node(0, 300, 1080, 400, clickable = true)
				list = node(0, 200, 1080, 1500, scrollable = true, actionIds = setOf(fwd), className = "android.widget.ListView")
				node(0, 1500, 1080, 2280, scrollable = true, actionIds = setOf(fwd), className = "android.widget.ListView")
			}
		}
		assertThat(ScrollPlanner.plan(t, headerBtn, NavDirection.DOWN, ids)!!.primary.nodeIndex).isEqualTo(list)
	}

	@Test
	fun `collection shape decides axis`() {
		val t = tree {
			node(0, 0, 1080, 2000, scrollable = true, actionIds = setOf(fwd, bwd), rows = 20, cols = 1)
		}
		assertThat(ScrollPlanner.plan(t, null, NavDirection.LEFT, ids)).isNull()
		assertThat(ScrollPlanner.plan(t, null, NavDirection.UP, ids)!!.primary.actionId).isEqualTo(bwd)
	}

	@Test
	fun `window-dominant prefers the screen-center container over a larger off-center one`() {
		var centered = -1
		val t = tree {
			node(0, 0, 1080, 2280) {
				node(0, 0, 1080, 900, scrollable = true, actionIds = setOf(fwd), className = "android.widget.ListView")
				centered = node(200, 1000, 900, 1600, scrollable = true, actionIds = setOf(fwd), className = "android.widget.ListView")
			}
		}
		assertThat(ScrollPlanner.plan(t, null, NavDirection.DOWN, ids)!!.primary.nodeIndex).isEqualTo(centered)
	}

	@Test
	fun `disabled and invisible containers are never targets`() {
		val t = tree {
			node(0, 0, 1080, 1000, scrollable = true, enabled = false, actionIds = setOf(fwd), className = "android.widget.ListView")
			node(0, 1000, 1080, 2000, scrollable = true, visible = false, actionIds = setOf(fwd), className = "android.widget.ListView")
		}
		assertThat(ScrollPlanner.plan(t, null, NavDirection.DOWN, ids)).isNull()
	}

	@Test
	fun `unknown-axis container with generic actions is the horizontal gesture target even beside a vertical list`() {
		var carousel = -1
		val t = tree {
			node(0, 0, 1080, 2280) {
				carousel = node(0, 0, 1080, 400, scrollable = true, actionIds = setOf(fwd, bwd), className = "com.app.CardPager")
				node(0, 400, 1080, 2280, scrollable = true, actionIds = setOf(fwd), className = "android.widget.ListView")
			}
		}
		assertThat(ScrollPlanner.axisBlockedTarget(t, null, NavDirection.LEFT, ids)).isEqualTo(carousel)
		assertThat(ScrollPlanner.axisBlockedTarget(t, carousel, NavDirection.RIGHT, ids)).isEqualTo(carousel)
	}

	@Test
	fun `known-vertical page is never a horizontal gesture target`() {
		val t = tree {
			node(0, 0, 1080, 2280, scrollable = true, actionIds = setOf(fwd, bwd), className = "android.widget.ScrollView")
		}
		assertThat(ScrollPlanner.axisBlockedTarget(t, null, NavDirection.RIGHT, ids)).isNull()
	}

	@Test
	fun `exhausted direction on a served container is not axis-blocked`() {
		val t = tree {
			node(0, 0, 1080, 2000, scrollable = true, actionIds = setOf(fwd))
		}
		assertThat(ScrollPlanner.axisBlockedTarget(t, null, NavDirection.UP, ids)).isNull()
	}

	@Test
	fun `selected slider is passed over for the page it sits in`() {
		var page = -1
		var slider = -1
		val t = tree {
			page = node(0, 0, 1080, 2280, scrollable = true, actionIds = setOf(fwd, bwd), className = "android.widget.ScrollView") {
				node(0, 2000, 1080, 2200) {
					slider = node(40, 2050, 1040, 2150, focusable = true, adjustable = true, actionIds = setOf(fwd, bwd), className = "android.widget.SeekBar")
				}
			}
		}
		// Down at the screen bottom scrolls the page — never bumps the slider's value.
		assertThat(ScrollPlanner.plan(t, slider, NavDirection.DOWN, ids)!!.primary.nodeIndex).isEqualTo(page)
		// And a horizontal press never swipes across the slider's thumb.
		assertThat(ScrollPlanner.axisBlockedTarget(t, slider, NavDirection.RIGHT, ids)).isNull()
	}

	@Test
	fun `availability equals plan existence for every direction`() {
		val t = tree {
			node(0, 0, 1080, 400, scrollable = true, actionIds = setOf(ids.left, ids.right)) {
				node(0, 500, 1080, 2000, scrollable = true, actionIds = setOf(fwd, bwd), className = "android.widget.ScrollView")
			}
		}
		val dirs = ScrollPlanner.availableDirections(t, null, ids)
		for (dir in NavDirection.entries) {
			assertThat(dirs.contains(dir)).isEqualTo(ScrollPlanner.plan(t, null, dir, ids) != null)
		}
		assertThat(dirs).containsExactly(NavDirection.LEFT, NavDirection.RIGHT, NavDirection.UP, NavDirection.DOWN)
	}
}
