package org.continuouspath.justtype.layout

import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class JTLayoutControllerTest {

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	private fun build(configureRepo: (SettingsRepository) -> Unit = {}): JTLayoutController {
		val ctx = RuntimeEnvironment.getApplication()
		configureRepo(SettingsRepository.getInstance(ctx))
		val root = LayoutInflater.from(ctx).inflate(R.layout.ime_input, null)
		return JTLayoutController(root).apply { initialize() }
	}

	@Test
	fun `portrait key history is a horizontal bar`() {
		val c = build()
		assertThat(c.keyHistoryScrollView).isInstanceOf(HorizontalScrollView::class.java)
		assertThat(c.keyHistoryView.isVertical).isFalse()
	}

	@Test
	@Config(qualifiers = "land")
	fun `landscape key history is a vertical side column by default`() {
		val c = build()
		assertThat(c.keyHistoryScrollView).isInstanceOf(ScrollView::class.java)
		assertThat(c.keyHistoryView.isVertical).isTrue()
	}

	@Test
	@Config(qualifiers = "land")
	fun `landscape pref off keeps the horizontal top bar`() {
		val c = build { it.putBoolean(Constants.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE, false) }
		assertThat(c.keyHistoryScrollView).isInstanceOf(HorizontalScrollView::class.java)
		assertThat(c.keyHistoryView.isVertical).isFalse()
	}

	@Test
	@Config(qualifiers = "land")
	fun `landscape overflow buffers render as extra columns and retract`() {
		val c = build()
		val container = c.columnsContainer!!
		assertThat(container.childCount).isEqualTo(1)

		c.updateColumnViews(listOf("one", "two", "three"))
		assertThat(container.childCount).isEqualTo(3)

		c.updateColumnViews(listOf("only"))
		assertThat(container.childCount).isEqualTo(1)
	}

	@Test
	fun `portrait has no columns container and column updates no-op`() {
		val c = build()
		assertThat(c.columnsContainer).isNull()
		c.updateColumnViews(listOf("one", "two"))
	}

	@Test
	fun `portrait ignores the vertical pref`() {
		val c = build { it.putBoolean(Constants.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE, true) }
		assertThat(c.keyHistoryScrollView).isInstanceOf(HorizontalScrollView::class.java)
		assertThat(c.keyHistoryView.isVertical).isFalse()
	}
}
