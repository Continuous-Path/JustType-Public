package org.continuouspath.justtype.settings

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.continuouspath.justtype.R

/**
 * Renders a [List] of [SettingsDef] into a touch-friendly vertical view stack.
 *
 * Reads/writes go through [SettingsRepository]. Live updates use the
 * synchronous [SettingsRepository.addChangeListener] callback (fires on the
 * in-memory cache update before the async DataStore persistence runs); each
 * registered listener is unregistered when [scope] completes.
 *
 * Bespoke sections that don't fit the [SettingsDef] schema attach via
 * [PageHooks] — page-level extension points anchored at well-defined keys.
 *
 * Navigation callbacks ([onSubPageClicked], [onKeyCaptureRequested]) are
 * delegated to the caller — the renderer doesn't know how to navigate or
 * capture hardware keys.
 */
class SettingsRenderer(
	private val context: Context,
	private val repo: SettingsRepository,
	private val scope: CoroutineScope,
	private val onSubPageClicked: (targetPageId: String) -> Unit = {},
	private val onKeyCaptureRequested: (key: String) -> Unit = {},
	private val onActionClicked: (actionId: String) -> Unit = {},
) {
	private val inflater: LayoutInflater = LayoutInflater.from(context)

	// Re-sync callbacks for dependent toggles (enabledWhenKey) on the current page,
	// invoked when any gate toggle on the page turns OFF. Cleared per renderPage.
	private val dependentGateSyncs = mutableListOf<() -> Unit>()

	/**
	 * Register [onChange] to fire whenever the preference at [key] changes.
	 * The listener auto-unregisters when [scope] completes.
	 */
	private fun onKeyChange(key: String, onChange: () -> Unit) {
		val listener = repo.addMainThreadListener { _, changedKey ->
			if (changedKey == key) onChange()
		}
		scope.coroutineContext[Job]?.invokeOnCompletion { repo.removeChangeListener(listener) }
	}

	/**
	 * Render [items] into [container] in order. Optional [hooks] inject bespoke
	 * views at named anchors. Items whose key is in [skipKeys] are NOT rendered
	 * by the default per-type renderer — but their [hooks.afterKey] hook still
	 * fires, anchored at the position where the default rendering would have
	 * gone. This is the standard pattern for replacing a registry item with a
	 * bespoke view (e.g. a Choice with a confirmation dialog).
	 *
	 * Returns a [RenderResult] mapping registry keys to the rendered top-level
	 * row [View], so callers can apply cross-key conditional state (enable /
	 * visibility) after rendering. Skipped keys are absent from [RenderResult.viewByKey].
	 *
	 * An [hooks.afterKey] entry referencing a key not present in [items]
	 * throws — drift guard between the registry and the caller's bespoke-
	 * section wiring.
	 */
	fun renderPage(
		items: List<SettingsDef>,
		container: ViewGroup,
		hooks: PageHooks = PageHooks.NONE,
		skipKeys: Set<String> = emptySet(),
	): RenderResult {
		validateHooks(items, hooks)

		hooks.beforePage?.invoke(container)

		dependentGateSyncs.clear()
		val viewByKey = LinkedHashMap<String, View>()
		for (def in items) {
			if (def.key !in skipKeys) {
				val beforeCount = container.childCount
				renderItem(def, container)
				if (container.childCount > beforeCount) {
					viewByKey[def.key] = container.getChildAt(container.childCount - 1)
				}
			}
			hooks.afterKey[def.key]?.invoke(container)
		}

		hooks.afterPage?.invoke(container)
		return RenderResult(viewByKey)
	}

	private fun validateHooks(items: List<SettingsDef>, hooks: PageHooks) {
		val keys = items.mapTo(HashSet()) { it.key }
		for (hookKey in hooks.afterKey.keys) {
			require(hookKey in keys) {
				"PageHooks.afterKey references unknown key '$hookKey' " +
					"(present keys: ${keys.sorted()})"
			}
		}
	}

	private fun renderItem(def: SettingsDef, container: ViewGroup) {
		when (def) {
			is SettingsDef.Toggle -> renderToggle(def, container)
			is SettingsDef.IntSlider -> renderIntSlider(def, container)
			is SettingsDef.FloatSlider -> renderFloatSlider(def, container)
			is SettingsDef.Choice -> renderChoice(def, container)
			is SettingsDef.SectionHeader -> renderSectionHeader(def, container)
			is SettingsDef.SubPage -> renderSubPage(def, container)
			is SettingsDef.InfoText -> renderInfoText(def, container)
			is SettingsDef.KeyCapture -> renderKeyCapture(def, container)
			is SettingsDef.Action -> renderAction(def, container)
		}
	}

	// ── Per-type renderers ────────────────────────────────────────────────

	private fun renderToggle(def: SettingsDef.Toggle, container: ViewGroup) {
		val row = inflater.inflate(R.layout.row_setting_toggle, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val description: TextView = row.findViewById(R.id.rowDescription)
		val switch: SwitchCompat = row.findViewById(R.id.rowSwitch)
		label.text = def.label
		bindDescription(description, def.description)
		switch.isChecked = repo.getBoolean(def.key, def.defaultValue)
		// Row is the single TalkBack node; suppress the inner switch to avoid double-focus.
		switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		fun syncToggleDescription() {
			row.contentDescription = withDescription(
				context.getString(R.string.sr_a11y_toggle, def.label, a11yOnOff(switch.isChecked)),
				def.description,
			)
		}
		syncToggleDescription()
		// Dependent toggle: disabled (and forced OFF) while its gate key is false.
		fun gateOpen() = def.enabledWhenKey?.let { repo.getBoolean(it, true) } ?: true
		fun syncGate() {
			val open = gateOpen()
			row.alpha = if (open) 1f else 0.45f
			switch.isEnabled = open
			if (!open && switch.isChecked) switch.isChecked = false
		}
		syncGate()
		switch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(def.key, isChecked)
			syncToggleDescription()
			if (!isChecked) dependentGateSyncs.forEach { it() }
		}
		row.setOnClickListener {
			if (!gateOpen()) {
				row.announceForAccessibility(context.getString(R.string.sr_a11y_toggle_unavailable))
				return@setOnClickListener
			}
			switch.isChecked = !switch.isChecked
			row.announceForAccessibility(a11yOnOff(switch.isChecked))
		}
		if (def.enabledWhenKey != null) dependentGateSyncs.add(::syncGate)
		container.addView(row)
		onKeyChange(def.key) {
			val value = repo.getBoolean(def.key, def.defaultValue)
			if (switch.isChecked != value) switch.isChecked = value
		}
	}

	private fun renderIntSlider(def: SettingsDef.IntSlider, container: ViewGroup) {
		val row = inflater.inflate(R.layout.row_setting_slider, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val description: TextView = row.findViewById(R.id.rowDescription)
		val valueView: TextView = row.findViewById(R.id.rowValue)
		val seek: SeekBar = row.findViewById(R.id.rowSeekBar)
		label.text = def.label
		bindDescription(description, def.description)

		seek.max = def.notchCount
		fun sliderDescription(v: Int): String = context.getString(
			R.string.sr_a11y_slider,
			def.label,
			def.formatValue(v),
			def.formatValue(def.min),
			def.formatValue(def.max),
		)
		// Defensive: if the cached value is out of legal range, fall back to the
		// registry default instead of clamping to min. Mitigates a transient
		// cache-corruption bug where sliders rendered at min after an IME-process
		// restart, before the cache was reconciled with disk.
		val raw = repo.getInt(def.key, def.defaultValue)
		val initial = if (raw < def.min || raw > def.max) def.defaultValue else raw
		seek.progress = (initial - def.min) / def.step
		valueView.text = def.formatValue(initial)
		seek.contentDescription = sliderDescription(initial)

		seek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					val raw = def.min + progress * def.step
					valueView.text = def.formatValue(raw)
					seek.contentDescription = sliderDescription(raw)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val raw = def.min + (seekBar?.progress ?: 0) * def.step
					repo.putInt(def.key, raw.coerceIn(def.min, def.max))
				}
			},
		)
		container.addView(row)

		onKeyChange(def.key) {
			if (seek.isPressed) return@onKeyChange
			val value = repo.getInt(def.key, def.defaultValue)
			val progress = (value.coerceIn(def.min, def.max) - def.min) / def.step
			if (seek.progress != progress) seek.progress = progress
			valueView.text = def.formatValue(value)
			seek.contentDescription = sliderDescription(value)
		}
	}

	private fun renderFloatSlider(def: SettingsDef.FloatSlider, container: ViewGroup) {
		val row = inflater.inflate(R.layout.row_setting_slider, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val description: TextView = row.findViewById(R.id.rowDescription)
		val valueView: TextView = row.findViewById(R.id.rowValue)
		val seek: SeekBar = row.findViewById(R.id.rowSeekBar)
		label.text = def.label
		bindDescription(description, def.description)

		seek.max = def.notchCount
		fun sliderDescription(v: Float): String = context.getString(
			R.string.sr_a11y_slider,
			def.label,
			def.formatValue(v),
			def.formatValue(def.min),
			def.formatValue(def.max),
		)
		// Defensive: same as renderIntSlider. NaN compares false to both bounds
		// (NaN.isNaN check) so we explicitly catch it.
		val raw = repo.getFloat(def.key, def.defaultValue)
		val initial = if (raw.isNaN() || raw < def.min || raw > def.max) def.defaultValue else raw
		seek.progress = ((initial - def.min) / def.step).toInt()
		valueView.text = def.formatValue(initial)
		seek.contentDescription = sliderDescription(initial)

		seek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
					val raw = def.min + progress * def.step
					valueView.text = def.formatValue(raw)
					seek.contentDescription = sliderDescription(raw)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val raw = def.min + (seekBar?.progress ?: 0) * def.step
					repo.putFloat(def.key, raw.coerceIn(def.min, def.max))
				}
			},
		)
		container.addView(row)

		onKeyChange(def.key) {
			if (seek.isPressed) return@onKeyChange
			val value = repo.getFloat(def.key, def.defaultValue)
			val progress = ((value.coerceIn(def.min, def.max) - def.min) / def.step).toInt()
			if (seek.progress != progress) seek.progress = progress
			valueView.text = def.formatValue(value)
			seek.contentDescription = sliderDescription(value)
		}
	}

	private fun renderChoice(def: SettingsDef.Choice, container: ViewGroup) {
		val row = inflater.inflate(R.layout.row_setting_choice, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val description: TextView = row.findViewById(R.id.rowDescription)
		val valueView: TextView = row.findViewById(R.id.rowValue)
		label.text = def.label
		bindDescription(description, def.description)

		fun choiceDescription(): String {
			val cur = repo.getString(def.key, def.defaultValue)
			val curLabel = def.options.firstOrNull { it.first == cur }?.second.orEmpty()
			return withDescription(context.getString(R.string.sr_a11y_choice, def.label, curLabel), def.description)
		}
		val current = repo.getString(def.key, def.defaultValue)
		valueView.text = def.options.firstOrNull { it.first == current }?.second.orEmpty()
		row.contentDescription = choiceDescription()

		// Dependent row: disabled while its gate is closed (boolean gate, or a
		// string gate when enabledWhenValue is set).
		fun gateOpen(): Boolean {
			val gateKey = def.enabledWhenKey ?: return true
			return if (def.enabledWhenValue != null) {
				repo.getString(gateKey, "") == def.enabledWhenValue
			} else {
				repo.getBoolean(gateKey, true)
			}
		}
		fun syncGate() {
			row.alpha = if (gateOpen()) 1f else 0.45f
		}
		syncGate()
		if (def.enabledWhenKey != null) dependentGateSyncs.add(::syncGate)

		row.setOnClickListener {
			if (!gateOpen()) {
				row.announceForAccessibility(context.getString(R.string.sr_a11y_toggle_unavailable))
				return@setOnClickListener
			}
			val displayLabels = def.options.map { it.second }.toTypedArray()
			val currentVal = repo.getString(def.key, def.defaultValue)
			val currentIndex = def.options.indexOfFirst { it.first == currentVal }.coerceAtLeast(0)
			AlertDialog.Builder(context)
				.setTitle(def.label)
				.setSingleChoiceItems(displayLabels, currentIndex) { dialog, which ->
					repo.putString(def.key, def.options[which].first)
					dependentGateSyncs.forEach { it() }
					dialog.dismiss()
				}
				.setNegativeButton(android.R.string.cancel, null)
				.show()
		}
		container.addView(row)

		onKeyChange(def.key) {
			val value = repo.getString(def.key, def.defaultValue)
			val newLabel = def.options.firstOrNull { it.first == value }?.second.orEmpty()
			if (valueView.text != newLabel) valueView.text = newLabel
			row.contentDescription = choiceDescription()
		}
	}

	private fun renderSectionHeader(def: SettingsDef.SectionHeader, container: ViewGroup) {
		val row = inflater.inflate(R.layout.row_setting_section_header, container, false) as TextView
		row.text = def.label
		ViewCompat.setAccessibilityHeading(row, true)
		container.addView(row)
	}

	private fun renderSubPage(def: SettingsDef.SubPage, container: ViewGroup) {
		if (def.prominent) {
			val button = inflater.inflate(R.layout.row_setting_subpage_button, container, false) as Button
			button.text = def.label
			button.setOnClickListener { onSubPageClicked(def.targetPageId) }
			container.addView(button)
			return
		}
		val row = inflater.inflate(R.layout.row_setting_subpage, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val description: TextView = row.findViewById(R.id.rowDescription)
		label.text = def.label
		bindDescription(description, def.description)
		row.contentDescription = withDescription(
			context.getString(R.string.sr_a11y_subpage, def.label),
			def.description,
		)
		row.setOnClickListener { onSubPageClicked(def.targetPageId) }
		container.addView(row)
	}

	private fun renderInfoText(def: SettingsDef.InfoText, container: ViewGroup) {
		val row = inflater.inflate(R.layout.row_setting_info_text, container, false) as TextView
		row.text = def.description ?: def.label
		container.addView(row)
	}

	private fun renderAction(def: SettingsDef.Action, container: ViewGroup) {
		// Reuse the SubPage row layout — visually it's the same affordance
		// (label + optional description + clickable row).
		val row = inflater.inflate(R.layout.row_setting_subpage, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val description: TextView = row.findViewById(R.id.rowDescription)
		label.text = def.label
		bindDescription(description, def.description)
		row.contentDescription = withDescription(def.label, def.description)
		row.setOnClickListener { onActionClicked(def.actionId) }
		container.addView(row)
	}

	private fun renderKeyCapture(def: SettingsDef.KeyCapture, container: ViewGroup) {
		val row = inflater.inflate(R.layout.row_setting_key_capture, container, false)
		val label: TextView = row.findViewById(R.id.rowLabel)
		val description: TextView = row.findViewById(R.id.rowDescription)
		val valueView: TextView = row.findViewById(R.id.rowValue)
		label.text = def.label
		bindDescription(description, def.description)

		val initial = repo.getInt(def.key, def.undefinedValue)
		valueView.text = keyCaptureLabel(initial, def.undefinedValue)
		row.contentDescription = withDescription(
			context.getString(R.string.sr_a11y_key_capture, def.label, keyCaptureLabel(initial, def.undefinedValue)),
			def.description,
		)

		row.setOnClickListener { onKeyCaptureRequested(def.key) }
		container.addView(row)

		onKeyChange(def.key) {
			val value = repo.getInt(def.key, def.undefinedValue)
			val newText = keyCaptureLabel(value, def.undefinedValue)
			if (valueView.text != newText) valueView.text = newText
			row.contentDescription = withDescription(
				context.getString(R.string.sr_a11y_key_capture, def.label, newText),
				def.description,
			)
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────

	private fun bindDescription(view: TextView, description: String?) {
		if (description.isNullOrBlank()) {
			view.visibility = View.GONE
		} else {
			view.visibility = View.VISIBLE
			view.text = description
		}
	}

	private fun a11yOnOff(value: Boolean): String = context.getString(
		if (value) R.string.settings_ctrl_on_speech else R.string.settings_ctrl_off_speech,
	)

	/** Fold an optional [description] into a control's [core] contentDescription. */
	private fun withDescription(core: String, description: String?): String = if (description.isNullOrBlank()) core else "$core. $description"

	private fun keyCaptureLabel(value: Int, undefinedValue: Int): String = if (value == undefinedValue) {
		context.getString(R.string.sr_key_capture_not_assigned)
	} else {
		context.getString(R.string.sr_key_capture_assigned, value.toString())
	}
}

/**
 * Output of [SettingsRenderer.renderPage].
 *
 * [viewByKey] maps registry keys to the top-level row view the renderer
 * inserted into the container, preserving insertion order. Callers use this to
 * apply cross-key conditional state (enable / visibility) after rendering.
 * Skipped keys (passed via `renderPage(skipKeys = ...)`) are absent.
 */
data class RenderResult(
	val viewByKey: Map<String, View>,
)

/**
 * Page-level extension points for bespoke views that don't fit the
 * [SettingsDef] schema. All hooks are optional. [afterKey] keys must
 * reference a key actually present in the page's items — unknown keys
 * throw at render time as a drift guard.
 */
class PageHooks(
	val beforePage: ((container: ViewGroup) -> Unit)? = null,
	val afterPage: ((container: ViewGroup) -> Unit)? = null,
	val afterKey: Map<String, (container: ViewGroup) -> Unit> = emptyMap(),
) {
	companion object {
		val NONE = PageHooks()
	}
}
