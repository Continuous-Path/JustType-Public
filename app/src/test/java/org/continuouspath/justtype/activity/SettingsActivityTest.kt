package org.continuouspath.justtype.activity

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.BuildIdentity
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsDef
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {

	private val controllers = mutableListOf<ActivityController<SettingsActivity>>()

	@Before
	fun setUp() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		SettingsRepository.getInstance(ctx).clearForTesting()
	}

	@After
	fun tearDown() {
		controllers.forEach { it.pause().stop().destroy() }
		controllers.clear()
		SettingsRepository.resetInstanceForTesting()
	}

	private fun launchSettings(): SettingsActivity = launchPage(SettingsActivity.PAGE_MAIN)

	/** Sub-pages reuse SettingsActivity, selected by the page-id extra. */
	private fun launchPage(pageId: String): SettingsActivity {
		val intent = android.content.Intent(
			ApplicationProvider.getApplicationContext(),
			SettingsActivity::class.java,
		).putExtra(SettingsActivity.EXTRA_PAGE_ID, pageId)
		val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent)
			.create()
			.start()
			.resume()
		controllers.add(controller)
		return controller.get()
	}

	@Test
	fun `rendered page goes stale when the registry is reinitialized behind the activity`() {
		val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
		val activity = launchSettings()
		assertThat(activity.isRenderedPageStale()).isFalse()

		// Simulate LanguagesActivity installing a langpack while Settings sits in the back
		// stack: the registry singleton is replaced, so options lists rendered from the old
		// instance (typing language) are stale and onResume recreates the activity.
		SettingsRegistry.reinitialize(ctx)

		assertThat(activity.isRenderedPageStale()).isTrue()
	}

	@Test
	fun `every renderable registry main-page key has a row in the container`() {
		val activity = launchSettings()
		val container: LinearLayout = activity.findViewById(R.id.settingsContent)
		val rendered = collectLabelsFromContainer(container)

		val registry = SettingsRegistry.getInstance(
			ApplicationProvider.getApplicationContext(),
		)
		val mainItems = registry.pages["main"].orEmpty()

		// KEY_LAYOUT_MODE is skipped but rendered via a bespoke row; voice_for_language is the
		// keyboard overlay's picker entry — this activity injects its own "Voice for {language}"
		// row instead (injectVoiceForLanguageRow), so the registry label never renders here.
		val skippedNoBespoke = setOf("voice_for_language")

		// InfoText is a static text block, not a labelled row: SettingsRenderer draws its
		// description into a bare TextView with no rowLabel id. It is still rendered on both
		// surfaces — verified separately below — so it is excluded from the label sweep
		// rather than from the parity contract.
		val expectedLabels = mainItems
			.filterNot { it.key in skippedNoBespoke }
			.filterNot { it is SettingsDef.InfoText }
			.map { it.label }
			.toSet()

		assertThat(rendered).containsAtLeastElementsIn(expectedLabels)
	}

	@Test
	fun `letter arrangement bespoke row is present`() {
		val activity = launchPage(SettingsActivity.PAGE_KEYBOARD_SETUP)
		val container: LinearLayout = activity.findViewById(R.id.settingsContent)
		val rendered = collectLabelsFromContainer(container)
		val letterArrangement = activity.getString(R.string.sr_main_letter_arrangement)

		assertThat(rendered).contains(letterArrangement)
	}

	@Test
	fun `typing language is shown (English + Espanol ship)`() {
		val activity = launchPage(SettingsActivity.PAGE_LANGUAGE_OPTIONS)
		val container: LinearLayout = activity.findViewById(R.id.settingsContent)
		val rendered = collectLabelsFromContainer(container)
		val typingLanguageLabel = activity.getString(R.string.sr_main_typing_language)

		assertThat(rendered).contains(typingLanguageLabel)
	}

	@Test
	fun `developer row is hidden until easter egg fires`() {
		val activity = launchSettings()
		val devRow = findRowByLabel(activity, activity.getString(R.string.sr_main_nav_developer))
		assertThat(devRow).isNotNull()
		assertThat(devRow!!.visibility).isEqualTo(View.GONE)
	}

	@Test
	fun `voice rows are clickable and show DEFAULT VOICE when nothing is selected`() {
		val activity = launchPage(SettingsActivity.PAGE_LANGUAGE_OPTIONS)
		val speechLabel = activity.getString(R.string.sr_main_speech_voice)
		val uiLabel = activity.getString(R.string.sr_main_voice_for_ui_language)

		for (label in listOf(speechLabel, uiLabel)) {
			val row = findRowByLabel(activity, label)
			assertThat(row).isNotNull()
			assertThat(row!!.hasOnClickListeners()).isTrue()
			val value = row.findViewById<TextView>(R.id.rowValue)
			assertThat(value.text.toString()).isEqualTo(activity.getString(R.string.tts_default_voice))
		}
	}

	@Test
	fun `spanish region row is hidden until the langpack is installed`() {
		val activity = launchSettings()
		val label = activity.getString(R.string.sr_main_spanish_region)
		// Fresh test repo has only English present, so the row must be absent.
		val container: LinearLayout = activity.findViewById(R.id.settingsContent)
		assertThat(collectLabelsFromContainer(container)).doesNotContain(label)
	}

	@Test
	fun `emergency reset row is present and clickable`() {
		val activity = launchSettings()
		val resetRow = findRowByLabel(activity, activity.getString(R.string.emergency_reset_label))
		assertThat(resetRow).isNotNull()
		assertThat(resetRow!!.hasOnClickListeners()).isTrue()
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	/** Recursively gather every visible label text from rendered rows. */
	@Test
	fun `the About block renders the version and the source revision`() {
		val activity = launchSettings()
		val container: LinearLayout = activity.findViewById(R.id.settingsContent)
		val allText = collectAllTextFromContainer(container)

		assertThat(allText.any { it.contains(BuildIdentity.version) }).isTrue()
		assertThat(allText.any { it.contains(BuildIdentity.detail) }).isTrue()
	}

	private fun collectAllTextFromContainer(root: ViewGroup): List<String> {
		val out = mutableListOf<String>()
		val stack = ArrayDeque<View>()
		stack.addLast(root)
		while (stack.isNotEmpty()) {
			val v = stack.removeLast()
			if (v is TextView) out.addNonEmpty(v.text?.toString())
			if (v is ViewGroup) stack.addChildren(v)
		}
		return out
	}

	private fun MutableList<String>.addNonEmpty(text: String?) {
		if (!text.isNullOrEmpty()) add(text)
	}

	private fun ArrayDeque<View>.addChildren(group: ViewGroup) {
		for (i in 0 until group.childCount) addLast(group.getChildAt(i))
	}

	private fun collectLabelsFromContainer(root: ViewGroup): Set<String> {
		val labels = mutableSetOf<String>()
		val stack = ArrayDeque<View>()
		stack.addLast(root)
		while (stack.isNotEmpty()) {
			val v = stack.removeLast()
			if (v is TextView && v.id == R.id.rowLabel) {
				val text = v.text?.toString().orEmpty()
				if (text.isNotEmpty()) labels.add(text)
			}
			if (v is ViewGroup) {
				for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
			}
		}
		return labels
	}

	private fun findRowByLabel(activity: SettingsActivity, label: String): View? {
		val container: LinearLayout = activity.findViewById(R.id.settingsContent)
		for (i in 0 until container.childCount) {
			val row = container.getChildAt(i)
			val labelView = row.findViewById<TextView>(R.id.rowLabel) ?: continue
			if (labelView.text?.toString() == label) return row
		}
		return null
	}

	private fun findRowWithText(root: View, text: String): View? {
		if (root is TextView && root.text?.toString() == text) return root
		if (root is ViewGroup) {
			for (i in 0 until root.childCount) {
				findRowWithText(root.getChildAt(i), text)?.let { return it }
			}
		}
		return null
	}

	private fun hiddenByAncestor(view: View): Boolean {
		var v: View? = view
		while (v != null) {
			if (v.visibility == View.GONE) return true
			v = v.parent as? View
		}
		return false
	}

	@Test
	fun `show key history off hides only the history rows`() {
		val activity = launchPage(SettingsActivity.PAGE_KEYBOARD_SETUP)
		val root = activity.findViewById<View>(android.R.id.content)
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putBoolean(org.continuouspath.justtype.Constants.KEY_SHOW_BUTTONS_PRESSED, false)
		shadowOf(android.os.Looper.getMainLooper()).idle()

		val historyRow = findRowWithText(root, activity.getString(R.string.sr_main_key_history_height))!!
		val selectionHeader = findRowWithText(root, activity.getString(R.string.sr_main_sec_selection))!!
		val wordSelection = findRowWithText(root, activity.getString(R.string.sr_main_word_selection))!!
		assertThat(hiddenByAncestor(historyRow)).isTrue()
		assertThat(hiddenByAncestor(selectionHeader)).isFalse()
		assertThat(hiddenByAncestor(wordSelection)).isFalse()
	}
}
