package org.continuouspath.justtype.layout

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.R
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ScanLayoutControllerTest {

	@After
	fun tearDown() {
		org.continuouspath.justtype.settings.SettingsRepository.resetInstanceForTesting()
	}

	private fun buildAndApply(): ScanLayoutController {
		val ctx = RuntimeEnvironment.getApplication()
		org.continuouspath.justtype.settings.SettingsRepository.getInstance(ctx)
		val root = LayoutInflater.from(ctx).inflate(R.layout.ime_input, null)
		val controller = ScanLayoutController(root, ctx)
		controller.initialize()
		root.findViewById<View>(R.id.scanLayoutContainer).visibility = View.VISIBLE
		// applyTopRowSize defers via View.post, which only runs once attached — host in an Activity.
		val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
		activity.setContentView(root)
		shadowOf(android.os.Looper.getMainLooper()).idle()
		controller.applyTopRowSize()
		shadowOf(android.os.Looper.getMainLooper()).idle()
		return controller
	}

	private fun buttonHeights(c: ScanLayoutController): List<Int> = c.buttons.map { (it.layoutParams as LinearLayout.LayoutParams).height }

	@Test
	fun `portrait top row stays width-driven`() {
		val c = buildAndApply()
		val keysWidth = c.keysContainer.width
		assertThat(c.topRow.layoutParams.height).isEqualTo((keysWidth * 0.25f).toInt())
	}

	@Test
	fun `portrait key rows keep their natural width-driven size`() {
		val c = buildAndApply()
		val naturalKey = c.keysContainer.width / 4
		assertThat(buttonHeights(c).distinct()).containsExactly(naturalKey)
	}

	@Test
	@Config(qualifiers = "land")
	fun `landscape uses the side-list variant with label at key size`() {
		val c = buildAndApply()
		// Land resource variant inflated: left column present, selection list beside it.
		val root = c.topRow.rootView
		assertThat(root.findViewById<View>(R.id.scanLeftColumn)).isNotNull()
		// Center label renders at key size — same height as every key.
		val keyH = c.topRow.layoutParams.height
		assertThat(buttonHeights(c).distinct()).containsExactly(keyH)
	}

	@Test
	@Config(qualifiers = "land")
	fun `landscape label plus key rows fit the height budget`() {
		val c = buildAndApply()
		val m = RuntimeEnvironment.getApplication().resources.displayMetrics
		val budget = (m.heightPixels * 0.85f).toInt()
		val keyH = c.topRow.layoutParams.height
		val historyH = c.keyHistoryScrollView.height
		// Label + two key rows + history bar within budget; keys never hog the width.
		assertThat(3 * keyH + historyH).isAtMost(budget)
		assertThat(keyH).isAtMost((m.widthPixels * 0.6f / 4f).toInt())
	}
}
