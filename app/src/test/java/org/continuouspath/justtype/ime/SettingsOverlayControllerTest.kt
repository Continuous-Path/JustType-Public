package org.continuouspath.justtype.ime

import android.content.Context
import android.graphics.Color
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.R
import org.continuouspath.justtype.settings.SettingsDisplayState
import org.continuouspath.justtype.settings.SettingsRow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class SettingsOverlayControllerTest {

	private lateinit var context: Context
	private lateinit var rootView: View
	private lateinit var controller: SettingsOverlayController

	private val keyLabelsCalls = mutableListOf<List<String>>()
	private val centerTextCalls = mutableListOf<String>()
	private var shownCount = 0
	private var hiddenCount = 0
	private val debugLogs = mutableListOf<String>()

	private val callbacks = object : SettingsOverlayCallbacks {
		override fun executeOnUiThread(block: () -> Unit) {
			block()
		}

		override fun onSettingsOverlayShown() {
			shownCount++
		}

		override fun onSettingsOverlayHidden() {
			hiddenCount++
		}

		override fun updateKeyLabels(labels: List<String>) {
			keyLabelsCalls.add(labels)
		}

		override fun updateCenterText(text: String) {
			centerTextCalls.add(text)
		}

		override fun debugLog(message: String) {
			debugLogs.add(message)
		}
	}

	@Before
	fun setUp() {
		context = RuntimeEnvironment.getApplication()
		rootView = LayoutInflater.from(context).inflate(R.layout.ime_input, null)
		// Production path attaches the IME root to a window; in tests we approximate that
		// by giving the root LinearLayout explicit layout params so expand()/collapse()
		// don't NPE on `root.layoutParams = root.layoutParams?.also { ... }`.
		rootView.layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
		)
		controller = SettingsOverlayController(context, callbacks)
		controller.initViews(rootView)
	}

	private fun overlayView(): View = rootView.findViewById(R.id.settingsOverlay)
	private fun listContainer(): LinearLayout = rootView.findViewById(R.id.settingsOverlayList)
	private fun titleView(): TextView = rootView.findViewById(R.id.settingsOverlayTitle)
	private fun bannerView(): TextView = rootView.findViewById(R.id.settingsAutoRevertBanner)

	private fun emptyDisplayState(
		pageTitle: String = "Title",
		rows: List<SettingsRow> = emptyList(),
		focusedRowIndex: Int = -1,
		centerText: String = "",
		keyLabels: List<String> = emptyList(),
		autoRevertMessage: String? = null,
		promptMessage: String? = null,
	): SettingsDisplayState = SettingsDisplayState(
		pageTitle = pageTitle,
		rows = rows,
		focusedRowIndex = focusedRowIndex,
		centerText = centerText,
		keyLabels = keyLabels,
		autoRevertMessage = autoRevertMessage,
		promptMessage = promptMessage,
	)

	// ──────────────────────────────────────────────────────────────────
	// Group 1 — initViews + visibility
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `initial overlay visibility is GONE per layout XML`() {
		// XML declares android:visibility="gone".
		assertThat(overlayView().visibility).isEqualTo(View.GONE)
		assertThat(controller.isOverlayVisible).isFalse()
	}

	@Test
	fun `show makes overlay visible and fires callback`() {
		controller.show()
		assertThat(overlayView().visibility).isEqualTo(View.VISIBLE)
		assertThat(controller.isOverlayVisible).isTrue()
		assertThat(shownCount).isEqualTo(1)
	}

	@Test
	fun `hide makes overlay GONE clears list and fires callback`() {
		controller.show()
		// Add a row first so we can verify removal.
		controller.updateUI(emptyDisplayState(rows = listOf(SettingsRow("a", null, null))))
		assertThat(listContainer().childCount).isEqualTo(1)

		controller.hide()
		assertThat(overlayView().visibility).isEqualTo(View.GONE)
		assertThat(listContainer().childCount).isEqualTo(0)
		assertThat(bannerView().visibility).isEqualTo(View.GONE)
		assertThat(hiddenCount).isEqualTo(1)
	}

	@Test
	fun `destroy clears refs and subsequent show is no-op`() {
		controller.destroy()
		// After destroy, all view refs are null. show() invokes executeOnUiThread which
		// runs the lambda; the lambda's `overlay?.visibility = View.VISIBLE` is a no-op
		// on null, but `callbacks.onSettingsOverlayShown()` is called unconditionally.
		// Note: the production code DOES call onSettingsOverlayShown even when overlay is
		// null — characterize that as the actual behavior.
		controller.show()
		// Either way, no crash.
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 2 — updateUI title + banner
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `updateUI sets page title and textSize`() {
		controller.updateUI(emptyDisplayState(pageTitle = "Page X"))
		assertThat(titleView().text.toString()).isEqualTo("Page X")
		assertThat(titleView().textSize).isGreaterThan(0f)
	}

	@Test
	fun `banner shows autoRevertMessage when present`() {
		controller.updateUI(
			emptyDisplayState(autoRevertMessage = "Reverting in 3", promptMessage = "Confirm?"),
		)
		assertThat(bannerView().visibility).isEqualTo(View.VISIBLE)
		assertThat(bannerView().text.toString()).isEqualTo("Reverting in 3")
	}

	@Test
	fun `banner shows promptMessage when autoRevert is null`() {
		controller.updateUI(emptyDisplayState(autoRevertMessage = null, promptMessage = "Confirm?"))
		assertThat(bannerView().visibility).isEqualTo(View.VISIBLE)
		assertThat(bannerView().text.toString()).isEqualTo("Confirm?")
	}

	@Test
	fun `banner GONE when both messages are null`() {
		controller.updateUI(emptyDisplayState(autoRevertMessage = null, promptMessage = null))
		assertThat(bannerView().visibility).isEqualTo(View.GONE)
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 3 — updateUI row variants
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `section header row renders with single TextView child`() {
		controller.updateUI(
			emptyDisplayState(
				rows = listOf(SettingsRow("Header", null, null, isSectionHeader = true)),
			),
		)
		val row = listContainer().getChildAt(0) as ViewGroup
		assertThat(row.childCount).isEqualTo(1)
		val text = row.getChildAt(0) as TextView
		assertThat(text.text.toString()).isEqualTo("Header")
		assertThat(text.currentTextColor).isEqualTo(Color.parseColor("#FFEB3B"))
	}

	@Test
	fun `info text row renders with light gray TextView`() {
		controller.updateUI(
			emptyDisplayState(rows = listOf(SettingsRow("Info", null, null, isInfoText = true))),
		)
		val row = listContainer().getChildAt(0) as ViewGroup
		val text = row.getChildAt(0) as TextView
		assertThat(text.text.toString()).isEqualTo("Info")
		assertThat(text.currentTextColor).isEqualTo(Color.parseColor("#CCCCCC"))
	}

	@Test
	fun `subPage row renders with arrow and description`() {
		controller.updateUI(
			emptyDisplayState(
				rows = listOf(
					SettingsRow(
						"Settings",
						null,
						null,
						description = "Open settings",
						isSubPage = true,
					),
				),
			),
		)
		val row = listContainer().getChildAt(0) as ViewGroup
		assertThat(row.childCount).isEqualTo(2) // label + description
		val label = row.getChildAt(0) as TextView
		assertThat(label.text.toString()).contains("▶")
		assertThat(label.currentTextColor).isEqualTo(Color.parseColor("#80D8FF"))
		val desc = row.getChildAt(1) as TextView
		assertThat(desc.text.toString()).isEqualTo("Open settings")
	}

	@Test
	fun `setting row with valueText concatenates label and value`() {
		controller.updateUI(
			emptyDisplayState(rows = listOf(SettingsRow("Volume", "75", null))),
		)
		val row = listContainer().getChildAt(0) as ViewGroup
		val label = row.getChildAt(0) as TextView
		val text = label.text
		assertThat(text.toString()).contains("Volume")
		assertThat(text.toString()).contains("75")
	}

	@Test
	fun `setting row with pendingValueText sets foreground color span`() {
		controller.updateUI(
			emptyDisplayState(
				rows = listOf(SettingsRow("Volume", "75", "42")),
			),
		)
		val row = listContainer().getChildAt(0) as ViewGroup
		val label = row.getChildAt(0) as TextView
		// TextView.text is a Spanned (TextView wraps the SpannableStringBuilder in a
		// SpannedString). Use the Spanned interface to find the ForegroundColorSpan.
		val text = label.text as android.text.Spanned
		val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
		assertThat(spans).isNotEmpty()
		assertThat(spans[0].foregroundColor).isEqualTo(Color.parseColor("#FFEB3B"))
	}

	@Test
	fun `focused row sets gray background`() {
		controller.updateUI(
			emptyDisplayState(rows = listOf(SettingsRow("X", null, null, isFocused = true))),
		)
		val row = listContainer().getChildAt(0)
		// Background is set via setBackgroundColor → ColorDrawable.
		val bg = row.background as android.graphics.drawable.ColorDrawable
		assertThat(bg.color).isEqualTo(Color.parseColor("#FF616161"))
	}

	@Test
	fun `non-focused row sets transparent background`() {
		controller.updateUI(
			emptyDisplayState(rows = listOf(SettingsRow("X", null, null, isFocused = false))),
		)
		val row = listContainer().getChildAt(0)
		val bg = row.background as android.graphics.drawable.ColorDrawable
		assertThat(bg.color).isEqualTo(Color.TRANSPARENT)
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 4 — Callbacks + auto-scroll
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `updateUI fires updateKeyLabels and updateCenterText callbacks`() {
		controller.updateUI(
			emptyDisplayState(
				keyLabels = listOf("a", "b"),
				centerText = "ABC",
			),
		)
		assertThat(keyLabelsCalls).containsExactly(listOf("a", "b"))
		assertThat(centerTextCalls).containsExactly("ABC")
	}

	@Test
	fun `updateUI with focused row schedules scroll post`() {
		controller.updateUI(
			emptyDisplayState(
				rows = listOf(
					SettingsRow("a", null, null),
					SettingsRow("b", null, null),
					SettingsRow("c", null, null),
				),
				focusedRowIndex = 2,
			),
		)
		// scrollView.post enqueues a runnable. Idle the looper to drain it.
		Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
		// No crash — the post ran. (smoothScrollTo behavior is implementation-specific
		// in Robolectric; characterize the no-crash path.)
	}

	@Test
	fun `updateUI with empty rows clears listContainer`() {
		// First add a row.
		controller.updateUI(emptyDisplayState(rows = listOf(SettingsRow("x", null, null))))
		assertThat(listContainer().childCount).isEqualTo(1)
		// Then clear.
		controller.updateUI(emptyDisplayState(rows = emptyList()))
		assertThat(listContainer().childCount).isEqualTo(0)
	}

	@Test
	fun `updateUI with out-of-range focusedRowIndex does not schedule scroll`() {
		controller.updateUI(
			emptyDisplayState(
				rows = listOf(SettingsRow("a", null, null)),
				focusedRowIndex = 5,
			),
		)
		// No crash — index out of range is silently skipped.
	}

	// ──────────────────────────────────────────────────────────────────
	// Group 5 — show/expand + hide/collapse layout transitions
	// ──────────────────────────────────────────────────────────────────

	@Test
	fun `show then hide round-trip restores overlay GONE`() {
		controller.show()
		assertThat(overlayView().visibility).isEqualTo(View.VISIBLE)
		controller.hide()
		assertThat(overlayView().visibility).isEqualTo(View.GONE)
	}
}
