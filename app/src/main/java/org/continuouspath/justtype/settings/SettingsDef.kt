package org.continuouspath.justtype.settings

/**
 * Data model for keyboard-navigable settings.
 *
 * Each concrete subclass describes one item in the settings UI:
 * its type, the SharedPreferences key it maps to, its label,
 * and type-specific metadata (range, step, options, etc.).
 *
 * The keyboard-driven settings controller uses this to render
 * the overlay panel and adapt key labels dynamically.
 */
sealed class SettingsDef {
	/** SharedPreferences key (or synthetic ID for headers/links). */
	abstract val key: String

	/** Human-readable label shown in the settings panel. */
	abstract val label: String

	/** Optional subtitle or description (always visible when non-null). */
	abstract val description: String?

	/** Optional info prompt — shown on demand via SHOW SETTING HELP / SHOW SECTION HELP keys. */
	open val infoPrompt: String? get() = null

	/** Whether this setting is "dangerous" — warrants an auto-revert timer after APPLY. */
	open val dangerous: Boolean get() = false

	/** Whether this setting should offer a TEST mode. */
	open val testable: Boolean get() = false

	// ── Concrete types ─────────────────────────────────────────────

	/** Boolean ON/OFF toggle. */
	data class Toggle(
		override val key: String,
		override val label: String,
		override val description: String? = null,
		val defaultValue: Boolean,
		override val dangerous: Boolean = false,
		override val testable: Boolean = false,
		override val infoPrompt: String? = null,
		// Dependent toggle: only adjustable while this other boolean key is true.
		// When the gate turns OFF, this toggle is forced OFF and rendered disabled.
		val enabledWhenKey: String? = null,
	) : SettingsDef()

	/** Integer slider with discrete steps. */
	data class IntSlider(
		override val key: String,
		override val label: String,
		override val description: String? = null,
		val defaultValue: Int,
		val min: Int,
		val max: Int,
		val step: Int = 1,
		/** Display formatter — receives the raw int value, returns display string (e.g. "120 ms"). */
		val formatValue: (Int) -> String = { it.toString() },
		override val dangerous: Boolean = false,
		override val testable: Boolean = false,
		override val infoPrompt: String? = null,
	) : SettingsDef() {
		/** Total number of discrete notches in this slider's range. */
		val notchCount: Int get() = ((max - min) / step)
	}

	/** Float slider with fine-grained steps. */
	data class FloatSlider(
		override val key: String,
		override val label: String,
		override val description: String? = null,
		val defaultValue: Float,
		val min: Float,
		val max: Float,
		val step: Float = 0.01f,
		/** Display formatter — receives the raw float value, returns display string (e.g. "55%"). */
		val formatValue: (Float) -> String = { "%.2f".format(it) },
		override val dangerous: Boolean = false,
		override val testable: Boolean = false,
		override val infoPrompt: String? = null,
	) : SettingsDef() {
		/** Total number of discrete notches in this slider's range. */
		val notchCount: Int get() = ((max - min) / step).toInt()
	}

	/** String choice from a fixed list of options. */
	data class Choice(
		override val key: String,
		override val label: String,
		override val description: String? = null,
		val defaultValue: String,
		/** Ordered list of (preference-value, display-label) pairs. */
		val options: List<Pair<String, String>>,
		override val dangerous: Boolean = false,
		override val testable: Boolean = false,
		override val infoPrompt: String? = null,
		// Dependent row: only adjustable while gate is open. With enabledWhenValue
		// set, the gate is a String pref equal to that value; otherwise a true Boolean.
		val enabledWhenKey: String? = null,
		val enabledWhenValue: String? = null,
	) : SettingsDef()

	/** Non-focusable section heading — skipped by cursor navigation. */
	data class SectionHeader(
		override val key: String,
		override val label: String,
		override val description: String? = null,
	) : SettingsDef()

	/** Link to a settings sub-page. */
	data class SubPage(
		override val key: String,
		override val label: String,
		override val description: String? = null,
		/** The page ID in [SettingsRegistry.pages] to navigate to. */
		val targetPageId: String,
		/** Render as a full-width button (visually distinct top-level section) instead of a list row. */
		val prominent: Boolean = false,
	) : SettingsDef()

	/** Non-focusable informational text block — used for "How it works" / "Tips" etc. */
	data class InfoText(
		override val key: String,
		override val label: String,
		override val description: String? = null,
	) : SettingsDef()

	/**
	 * Clickable action button. Used for one-shot operations like Submit
	 * Feedback that don't fit Toggle/Slider/Choice semantics. The host
	 * activity resolves [actionId] to a handler at render time.
	 */
	data class Action(
		override val key: String,
		override val label: String,
		override val description: String? = null,
		val actionId: String,
	) : SettingsDef()

	/**
	 * Key-capture setting for switch assignment.
	 *
	 * When the user presses ACTION on this item the controller enters KEY_CAPTURE mode,
	 * which intercepts the next raw hardware key event and stores the Android keyCode
	 * as an Int in SharedPreferences.
	 */
	data class KeyCapture(
		override val key: String,
		override val label: String,
		override val description: String? = null,
		/** Value stored when no switch is assigned. */
		val undefinedValue: Int = -1,
		override val dangerous: Boolean = false,
		override val testable: Boolean = false,
		override val infoPrompt: String? = null,
	) : SettingsDef()
}
