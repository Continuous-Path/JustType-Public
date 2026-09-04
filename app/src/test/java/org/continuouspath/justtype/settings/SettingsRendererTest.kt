package org.continuouspath.justtype.settings

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Tests for [SettingsRenderer].
 *
 * Renderer launches per-key collectors on the test scope; the test idles the
 * main looper after writes so the flow emissions land before assertions.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRendererTest {
	private lateinit var context: Context
	private lateinit var repo: SettingsRepository
	private lateinit var scope: CoroutineScope
	private lateinit var container: LinearLayout
	private var activityController: ActivityController<AppCompatActivity>? = null

	@Before
	fun setUp() {
		// Use an AppCompat Activity context so themed attrs like
		// ?attr/selectableItemBackground resolve in inflated layouts.
		// We hold the controller so @After can destroy the activity —
		// without that the activity's lifecycleScope leaks across tests.
		activityController = Robolectric.buildActivity(AppCompatActivity::class.java).create()
		context = activityController!!.get()
		repo = SettingsRepository.getInstance(
			ApplicationProvider.getApplicationContext<android.content.Context>(),
		)
		repo.clearForTesting()
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
		container = LinearLayout(context)
	}

	@After
	fun tearDown() {
		scope.coroutineContext[Job]?.cancel()
		activityController?.pause()?.stop()?.destroy()
		activityController = null
		SettingsRepository.resetInstanceForTesting()
	}

	// ── Toggle ────────────────────────────────────────────────────────────

	@Test
	fun `Toggle click flips repo`() {
		val def = SettingsDef.Toggle("k_toggle", "Label", defaultValue = false)
		newRenderer().renderPage(listOf(def), container)

		val row = container.getChildAt(0)
		row.performClick()

		assertThat(repo.getBoolean("k_toggle", false)).isTrue()
	}

	@Test
	fun `Toggle reflects external repo change`() {
		val def = SettingsDef.Toggle("k_toggle", "Label", defaultValue = false)
		newRenderer().renderPage(listOf(def), container)
		val switch: SwitchCompat = container.getChildAt(0).findViewById(
			org.continuouspath.justtype.R.id.rowSwitch,
		)
		assertThat(switch.isChecked).isFalse()

		repo.putBoolean("k_toggle", true)
		shadowOf(android.os.Looper.getMainLooper()).idle()

		assertThat(switch.isChecked).isTrue()
	}

	// ── IntSlider ─────────────────────────────────────────────────────────

	@Test
	fun `IntSlider writes on stop-tracking-touch only`() {
		val def = SettingsDef.IntSlider(
			"k_int",
			"Label",
			defaultValue = 0,
			min = 0,
			max = 100,
			step = 10,
		)
		newRenderer().renderPage(listOf(def), container)
		val seek: SeekBar = container.getChildAt(0).findViewById(
			org.continuouspath.justtype.R.id.rowSeekBar,
		)

		// Simulate drag: onProgressChanged fires while dragging — repo stays at default.
		seek.progress = 5
		assertThat(repo.getInt("k_int", 0)).isEqualTo(0)

		// Robolectric doesn't synthesize touch events for SeekBar; invoke the
		// listener directly to model the stop-tracking event.
		seek.getStopTrackingListener().onStopTrackingTouch(seek)

		assertThat(repo.getInt("k_int", 0)).isEqualTo(50)
	}

	@Test
	fun `IntSlider label updates while dragging`() {
		val def = SettingsDef.IntSlider(
			"k_int",
			"Label",
			defaultValue = 0,
			min = 0,
			max = 100,
			step = 10,
			formatValue = { "$it ms" },
		)
		newRenderer().renderPage(listOf(def), container)
		val seek: SeekBar = container.getChildAt(0).findViewById(
			org.continuouspath.justtype.R.id.rowSeekBar,
		)
		val valueView: TextView = container.getChildAt(0).findViewById(
			org.continuouspath.justtype.R.id.rowValue,
		)

		seek.progress = 3

		assertThat(valueView.text.toString()).isEqualTo("30 ms")
	}

	// ── FloatSlider ───────────────────────────────────────────────────────

	@Test
	fun `FloatSlider writes on stop-tracking-touch`() {
		val def = SettingsDef.FloatSlider(
			"k_float",
			"Label",
			defaultValue = 0f,
			min = 0f,
			max = 1f,
			step = 0.1f,
		)
		newRenderer().renderPage(listOf(def), container)
		val seek: SeekBar = container.getChildAt(0).findViewById(
			org.continuouspath.justtype.R.id.rowSeekBar,
		)

		seek.progress = 5
		seek.getStopTrackingListener().onStopTrackingTouch(seek)

		assertThat(repo.getFloat("k_float", 0f)).isWithin(0.01f).of(0.5f)
	}

	// ── Slider out-of-range guard (min-sliders cache-corruption mitigation) ──

	@Test
	fun `IntSlider renders registry default when stored value is above range`() {
		repo.putInt("k_int", 500)
		val def = SettingsDef.IntSlider("k_int", "Label", defaultValue = 40, min = 0, max = 100, step = 10)
		newRenderer().renderPage(listOf(def), container)

		val seek: SeekBar = container.getChildAt(0).findViewById(org.continuouspath.justtype.R.id.rowSeekBar)
		val valueView: TextView = container.getChildAt(0).findViewById(org.continuouspath.justtype.R.id.rowValue)
		assertThat(seek.progress).isEqualTo(4) // (default 40 - min 0) / step 10 — NOT max, NOT min
		assertThat(valueView.text.toString()).isEqualTo("40")
	}

	@Test
	fun `IntSlider renders registry default when stored value is below range`() {
		repo.putInt("k_int", -3)
		val def = SettingsDef.IntSlider("k_int", "Label", defaultValue = 40, min = 10, max = 100, step = 10)
		newRenderer().renderPage(listOf(def), container)

		val seek: SeekBar = container.getChildAt(0).findViewById(org.continuouspath.justtype.R.id.rowSeekBar)
		assertThat(seek.progress).isEqualTo(3) // (40 - 10) / 10 — the corrupt value must not pin the slider at min
	}

	@Test
	fun `FloatSlider renders registry default when stored value is NaN`() {
		repo.putFloat("k_float", Float.NaN)
		val def = SettingsDef.FloatSlider(
			"k_float",
			"Label",
			defaultValue = 0.5f,
			min = 0f,
			max = 1f,
			step = 0.25f,
			formatValue = { "v:$it" },
		)
		newRenderer().renderPage(listOf(def), container)

		val seek: SeekBar = container.getChildAt(0).findViewById(org.continuouspath.justtype.R.id.rowSeekBar)
		val valueView: TextView = container.getChildAt(0).findViewById(org.continuouspath.justtype.R.id.rowValue)
		assertThat(seek.progress).isEqualTo(2) // (0.5 - 0) / 0.25
		assertThat(valueView.text.toString()).isEqualTo("v:0.5")
	}

	@Test
	fun `FloatSlider renders registry default when stored value is out of range`() {
		repo.putFloat("k_float", 9f)
		val def = SettingsDef.FloatSlider(
			"k_float",
			"Label",
			defaultValue = 0.5f,
			min = 0f,
			max = 1f,
			step = 0.25f,
			formatValue = { "v:$it" },
		)
		newRenderer().renderPage(listOf(def), container)

		val valueView: TextView = container.getChildAt(0).findViewById(org.continuouspath.justtype.R.id.rowValue)
		assertThat(valueView.text.toString()).isEqualTo("v:0.5")
	}

	// ── Choice ────────────────────────────────────────────────────────────

	@Test
	fun `Choice click opens dialog and writes selection`() {
		val def = SettingsDef.Choice(
			"k_choice",
			"Label",
			defaultValue = "a",
			options = listOf("a" to "Alpha", "b" to "Bravo", "c" to "Charlie"),
		)
		newRenderer().renderPage(listOf(def), container)

		container.getChildAt(0).performClick()

		val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
		assertThat(dialog).isNotNull()
		// Select "Bravo" (index 1).
		dialog.listView.performItemClick(null, 1, 1L)

		assertThat(repo.getString("k_choice", "a")).isEqualTo("b")
	}

	@Test
	fun `Choice value view shows current option display label`() {
		val def = SettingsDef.Choice(
			"k_choice",
			"Label",
			defaultValue = "a",
			options = listOf("a" to "Alpha", "b" to "Bravo"),
		)
		repo.putString("k_choice", "b")
		newRenderer().renderPage(listOf(def), container)

		val valueView: TextView = container.getChildAt(0).findViewById(
			org.continuouspath.justtype.R.id.rowValue,
		)

		assertThat(valueView.text.toString()).isEqualTo("Bravo")
	}

	// ── SectionHeader ─────────────────────────────────────────────────────

	@Test
	fun `SectionHeader renders text and is not clickable`() {
		val def = SettingsDef.SectionHeader("k_header", "Section Title")
		newRenderer().renderPage(listOf(def), container)

		val row = container.getChildAt(0) as TextView
		assertThat(row.text.toString()).isEqualTo("Section Title")
		assertThat(row.isClickable).isFalse()
	}

	// ── SubPage ───────────────────────────────────────────────────────────

	@Test
	fun `SubPage click invokes onSubPageClicked with target page id`() {
		var receivedTarget: String? = null
		val renderer = SettingsRenderer(
			context = context,
			repo = repo,
			scope = scope,
			onSubPageClicked = { receivedTarget = it },
		)
		val def = SettingsDef.SubPage("k_subpage", "Open", targetPageId = "page_x")
		renderer.renderPage(listOf(def), container)

		container.getChildAt(0).performClick()

		assertThat(receivedTarget).isEqualTo("page_x")
	}

	// ── InfoText ──────────────────────────────────────────────────────────

	@Test
	fun `InfoText prefers description over label`() {
		val def = SettingsDef.InfoText("k_info", "fallback label", description = "Real text")
		newRenderer().renderPage(listOf(def), container)

		val row = container.getChildAt(0) as TextView
		assertThat(row.text.toString()).isEqualTo("Real text")
	}

	// ── KeyCapture ────────────────────────────────────────────────────────

	@Test
	fun `KeyCapture click invokes onKeyCaptureRequested with key`() {
		var receivedKey: String? = null
		val renderer = SettingsRenderer(
			context = context,
			repo = repo,
			scope = scope,
			onKeyCaptureRequested = { receivedKey = it },
		)
		val def = SettingsDef.KeyCapture("k_capture", "Switch", undefinedValue = -1)
		renderer.renderPage(listOf(def), container)

		container.getChildAt(0).performClick()

		assertThat(receivedKey).isEqualTo("k_capture")
	}

	@Test
	fun `KeyCapture shows not-assigned label when value is undefinedValue`() {
		val def = SettingsDef.KeyCapture("k_capture", "Switch", undefinedValue = -1)
		newRenderer().renderPage(listOf(def), container)

		val valueView: TextView = container.getChildAt(0).findViewById(
			org.continuouspath.justtype.R.id.rowValue,
		)
		assertThat(valueView.text.toString())
			.isEqualTo(context.getString(org.continuouspath.justtype.R.string.sr_key_capture_not_assigned))
	}

	// ── Page extension hooks ──────────────────────────────────────────────

	@Test
	fun `afterKey hook inserts bespoke view after matched item`() {
		val items = listOf(
			SettingsDef.Toggle("k_a", "A", defaultValue = false),
			SettingsDef.Toggle("k_b", "B", defaultValue = false),
			SettingsDef.Toggle("k_c", "C", defaultValue = false),
		)
		val bespoke = TextView(context).apply { text = "BESPOKE" }
		val hooks = PageHooks(afterKey = mapOf("k_b" to { it.addView(bespoke) }))

		newRenderer().renderPage(items, container, hooks)

		// Expected child order: a, b, bespoke, c
		assertThat(container.childCount).isEqualTo(4)
		assertThat((container.getChildAt(2) as TextView).text.toString()).isEqualTo("BESPOKE")
	}

	@Test
	fun `unknown afterKey throws`() {
		val items = listOf(SettingsDef.Toggle("k_a", "A", defaultValue = false))
		val hooks = PageHooks(afterKey = mapOf("k_does_not_exist" to {}))

		val ex = runCatching { newRenderer().renderPage(items, container, hooks) }.exceptionOrNull()

		assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
		assertThat(ex?.message).contains("k_does_not_exist")
	}

	@Test
	fun `multi-item page renders in order`() {
		val items = listOf(
			SettingsDef.SectionHeader("k_h", "Header"),
			SettingsDef.Toggle("k_t", "Toggle", defaultValue = false),
			SettingsDef.InfoText("k_i", "Info"),
		)
		newRenderer().renderPage(items, container)

		assertThat(container.childCount).isEqualTo(3)
		val header = container.getChildAt(0) as TextView
		assertThat(header.text.toString()).isEqualTo("Header")
	}

	// ── RenderResult ──────────────────────────────────────────────────────

	@Test
	fun `RenderResult viewByKey contains entries for every rendered item`() {
		val items = listOf(
			SettingsDef.SectionHeader("k_h", "Header"),
			SettingsDef.Toggle("k_a", "A", defaultValue = false),
			SettingsDef.Toggle("k_b", "B", defaultValue = false),
		)
		val result = newRenderer().renderPage(items, container)

		assertThat(result.viewByKey.keys).containsExactly("k_h", "k_a", "k_b").inOrder()
	}

	// ── skipKeys ──────────────────────────────────────────────────────────

	@Test
	fun `skipKeys omits items from viewByKey and from container children`() {
		val items = listOf(
			SettingsDef.Toggle("k_a", "A", defaultValue = false),
			SettingsDef.Toggle("k_skip", "Skip me", defaultValue = false),
			SettingsDef.Toggle("k_c", "C", defaultValue = false),
		)
		val result = newRenderer().renderPage(items, container, skipKeys = setOf("k_skip"))

		assertThat(result.viewByKey.keys).containsExactly("k_a", "k_c").inOrder()
		assertThat(container.childCount).isEqualTo(2)
	}

	@Test
	fun `skipKeys still fires afterKey hook at the skipped position`() {
		val items = listOf(
			SettingsDef.Toggle("k_a", "A", defaultValue = false),
			SettingsDef.Toggle("k_skip", "Skip me", defaultValue = false),
			SettingsDef.Toggle("k_c", "C", defaultValue = false),
		)
		val bespoke = TextView(context).apply { text = "BESPOKE" }
		val hooks = PageHooks(afterKey = mapOf("k_skip" to { it.addView(bespoke) }))

		newRenderer().renderPage(items, container, hooks, skipKeys = setOf("k_skip"))

		// Expected child order: a, bespoke (anchored at skipped position), c.
		assertThat(container.childCount).isEqualTo(3)
		assertThat((container.getChildAt(1) as TextView).text.toString()).isEqualTo("BESPOKE")
	}

	// ── Accessibility (contentDescription) ────────────────────────────────

	@Test
	fun `Toggle row contentDescription reflects state and updates on click`() {
		val def = SettingsDef.Toggle("k_toggle", "Beep", defaultValue = false)
		newRenderer().renderPage(listOf(def), container)
		val row = container.getChildAt(0)

		assertThat(row.contentDescription.toString()).isEqualTo("Beep, off, toggle")

		row.performClick()

		assertThat(row.contentDescription.toString()).isEqualTo("Beep, on, toggle")
	}

	@Test
	fun `Toggle contentDescription folds in the description`() {
		val def = SettingsDef.Toggle("k_toggle", "Beep", description = "Sound on press", defaultValue = true)
		newRenderer().renderPage(listOf(def), container)
		val row = container.getChildAt(0)

		assertThat(row.contentDescription.toString()).isEqualTo("Beep, on, toggle. Sound on press")
	}

	@Test
	fun `Slider SeekBar contentDescription carries label, value and range`() {
		val def = SettingsDef.IntSlider(
			"k_int",
			"Delay",
			defaultValue = 30,
			min = 0,
			max = 100,
			step = 10,
			formatValue = { "$it ms" },
		)
		newRenderer().renderPage(listOf(def), container)
		val seek: SeekBar = container.getChildAt(0).findViewById(
			org.continuouspath.justtype.R.id.rowSeekBar,
		)

		assertThat(seek.contentDescription.toString()).isEqualTo("Delay, 30 ms, range 0 ms to 100 ms")

		seek.progress = 5

		assertThat(seek.contentDescription.toString()).isEqualTo("Delay, 50 ms, range 0 ms to 100 ms")
	}

	@Test
	fun `Choice row contentDescription reflects current option`() {
		val def = SettingsDef.Choice(
			"k_choice",
			"Mode",
			defaultValue = "a",
			options = listOf("a" to "Alpha", "b" to "Bravo"),
		)
		repo.putString("k_choice", "b")
		newRenderer().renderPage(listOf(def), container)

		assertThat(container.getChildAt(0).contentDescription.toString()).isEqualTo("Mode, current Bravo")
	}

	@Test
	fun `SubPage row contentDescription announces open action`() {
		val def = SettingsDef.SubPage("k_subpage", "Input Methods", targetPageId = "page_x")
		newRenderer().renderPage(listOf(def), container)

		assertThat(container.getChildAt(0).contentDescription.toString()).isEqualTo("Open Input Methods")
	}

	@Test
	fun `KeyCapture row contentDescription reflects assignment`() {
		val def = SettingsDef.KeyCapture("k_capture", "Scan switch", undefinedValue = -1)
		newRenderer().renderPage(listOf(def), container)

		val notAssigned = context.getString(org.continuouspath.justtype.R.string.sr_key_capture_not_assigned)
		assertThat(container.getChildAt(0).contentDescription.toString()).isEqualTo("Scan switch, $notAssigned")
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	private fun newRenderer(): SettingsRenderer = SettingsRenderer(context = context, repo = repo, scope = scope)

	// SeekBar's listener field is private and lives on AbsSeekBar; reach
	// through reflection (walking superclasses) to invoke onStopTrackingTouch
	// without synthesizing touch events.
	private fun SeekBar.getStopTrackingListener(): SeekBar.OnSeekBarChangeListener {
		var cls: Class<*>? = this.javaClass
		while (cls != null) {
			val match = cls.declaredFields.firstOrNull { it.name == "mOnSeekBarChangeListener" }
			if (match != null) {
				match.isAccessible = true
				return match.get(this) as SeekBar.OnSeekBarChangeListener
			}
			cls = cls.superclass
		}
		throw NoSuchFieldException("mOnSeekBarChangeListener not found on $this")
	}
}
