package org.continuouspath.justtype.settings

import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRendererMappingProbe {

	@After
	fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
		SettingsRegistry.resetInstanceForTesting()
	}

	@Test
	fun `history keys map to their own rows, not selection-list rows`() {
		val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
		SettingsRegistry.getInstance(activity)
		val repo = SettingsRepository.getInstance(activity)
		val renderer = SettingsRenderer(activity, repo, kotlinx.coroutines.MainScope())
		val container = LinearLayout(activity)
		val items = SettingsRegistry.getInstance(activity).pages.getValue("keyboard_setup")
		val result = renderer.renderPage(items, container)

		val historyKeys = listOf(
			Constants.KEY_KEY_HISTORY_HEIGHT_PERCENT,
			Constants.KEY_KEY_HISTORY_SHRINK_TO_FIT,
			Constants.KEY_KEY_HISTORY_HIGHLIGHT,
			Constants.KEY_KEY_HISTORY_VERTICAL_LANDSCAPE,
			Constants.KEY_KEY_HISTORY_MARK_LATEST,
		)
		val historyViews = historyKeys.map { result.viewByKey[it] }
		val selectionHeader = result.viewByKey["sec_selection"]
		val wordSelection = result.viewByKey[Constants.KEY_WORD_SELECTION_MODE]

		// Each key must own a distinct row; none may alias the selection-list rows.
		assertThat(historyViews).doesNotContain(null)
		assertThat(historyViews.toSet()).hasSize(historyKeys.size)
		assertThat(historyViews).doesNotContain(selectionHeader)
		assertThat(historyViews).doesNotContain(wordSelection)
	}
}
