package org.continuouspath.justtype.view

import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SquareGridLayoutTest {
	private fun grid() = SquareGridLayout(ApplicationProvider.getApplicationContext())

	private fun measure(grid: SquareGridLayout, widthPx: Int, heightPx: Int = widthPx * 4) {
		grid.measure(
			MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
			MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.AT_MOST),
		)
	}

	@Test fun `uncapped grid is a square of its width`() {
		val g = grid()
		measure(g, widthPx = 800)
		assertThat(g.measuredWidth).isEqualTo(800)
		assertThat(g.measuredHeight).isEqualTo(800) // height follows width
	}

	@Test fun `cap shrinks the square when width exceeds it (landscape case)`() {
		val g = grid()
		g.maxSquarePx = 500
		measure(g, widthPx = 1800) // wide landscape width
		assertThat(g.measuredWidth).isEqualTo(500)
		assertThat(g.measuredHeight).isEqualTo(500) // stays square, but bounded
	}

	@Test fun `cap is inert when the natural square already fits`() {
		val g = grid()
		g.maxSquarePx = 1000
		measure(g, widthPx = 600) // portrait: width below the cap
		assertThat(g.measuredWidth).isEqualTo(600) // unchanged
		assertThat(g.measuredHeight).isEqualTo(600)
	}

	@Test fun `zero cap means unbounded`() {
		val g = grid()
		g.maxSquarePx = 0
		measure(g, widthPx = 1500)
		assertThat(g.measuredWidth).isEqualTo(1500)
		assertThat(g.measuredHeight).isEqualTo(1500)
	}
}
