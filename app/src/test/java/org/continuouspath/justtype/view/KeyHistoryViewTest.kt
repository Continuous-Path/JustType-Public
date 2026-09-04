package org.continuouspath.justtype.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KeyHistoryViewTest {

	private val labels = List(3) { List(9) { "A" } }

	private fun measured(view: KeyHistoryView): Pair<Int, Int> {
		val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		view.measure(unspecified, unspecified)
		return view.measuredWidth to view.measuredHeight
	}

	@Test
	fun `horizontal bar lays keys out wider than tall`() {
		val view = KeyHistoryView(RuntimeEnvironment.getApplication())
		view.setKeyHistory(labels)
		val (w, h) = measured(view)
		assertThat(w).isGreaterThan(h)
	}

	@Test
	fun `vertical column mirrors the horizontal measurement`() {
		val ctx = RuntimeEnvironment.getApplication()
		val horizontal = KeyHistoryView(ctx).apply { setKeyHistory(labels) }
		val vertical = KeyHistoryView(ctx).apply {
			setKeyHistory(labels)
			setVertical(true)
		}
		val (hw, hh) = measured(horizontal)
		val (vw, vh) = measured(vertical)
		assertThat(vw).isEqualTo(hh)
		assertThat(vh).isEqualTo(hw)
	}

	@Test
	fun `highlight word maps each char to its cell on the matching key`() {
		val view = KeyHistoryView(RuntimeEnvironment.getApplication())
		val grids = listOf(
			listOf("A", "B", "C", "D", "E", "F", "G", "H", "I"),
			listOf("J", "K", "L", "M", "N", "O", "P", "Q", "R"),
		)
		// Lowercase word matches the uppercase grid cells, per key position.
		view.setKeyHistory(grids, "ak")
		assertThat(view.highlightCellIndex(grids[0], 0)).isEqualTo(0)
		assertThat(view.highlightCellIndex(grids[1], 1)).isEqualTo(1)
	}

	@Test
	fun `keys beyond the word or without the char get no highlight`() {
		val view = KeyHistoryView(RuntimeEnvironment.getApplication())
		val grid = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I")
		view.setKeyHistory(listOf(grid, grid), "z")
		// 'Z' is not on the key; key 1 is past the word's end; no word at all.
		assertThat(view.highlightCellIndex(grid, 0)).isEqualTo(-1)
		assertThat(view.highlightCellIndex(grid, 1)).isEqualTo(-1)
		view.setKeyHistory(listOf(grid), null)
		assertThat(view.highlightCellIndex(grid, 0)).isEqualTo(-1)
	}

	@Test
	fun `stacked slot cells match on their unelided label`() {
		val view = KeyHistoryView(RuntimeEnvironment.getApplication())
		// "#/-\n@+" elides to "#/-" for display, but '@' must still match the cell.
		val grid = listOf("#/-\n@+", "", "", "", "", "", "", "", "")
		view.setKeyHistory(listOf(grid), "@")
		assertThat(view.highlightCellIndex(grid, 0)).isEqualTo(0)
	}

	@Test
	fun `vertical column draws without crashing`() {
		val view = KeyHistoryView(RuntimeEnvironment.getApplication())
		view.setKeyHistory(labels)
		view.setVertical(true)
		val (w, h) = measured(view)
		view.layout(0, 0, w, h)
		view.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
	}

	@Test
	fun `newest-key marker draws without crashing in both orientations`() {
		for (vertical in listOf(false, true)) {
			val view = KeyHistoryView(RuntimeEnvironment.getApplication())
			view.setKeyHistory(labels, "abc")
			view.setMarkLatest(true)
			view.setVertical(vertical)
			val (w, h) = measured(view)
			view.layout(0, 0, w, h)
			view.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
		}
	}
}
