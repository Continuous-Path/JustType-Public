package org.continuouspath.justtype.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.ClassMetadataStore
import org.continuouspath.justtype.Constants.KEY_NEXT_LETTER_HINTS
import org.continuouspath.justtype.Constants.KEY_TURKISH_AZERI_CASE_OVERRIDE
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_ENABLED
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_MAX_FREQ
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_MIN_FREQ
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_MODULE_MASK
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_USECOUNT_MAX
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_USECOUNT_MAX_JT
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACTIVE_MASK
import org.continuouspath.justtype.Constants.KEY_VOCAB_FREQ_FILTER_ENABLED
import org.continuouspath.justtype.Constants.KEY_VOCAB_INCLUDE_CUSTOM_WORDS
import org.continuouspath.justtype.Constants.KEY_VOCAB_INCLUDE_JUSTTYPE
import org.continuouspath.justtype.Constants.KEY_VOCAB_INCLUDE_PHRASES
import org.continuouspath.justtype.Constants.KEY_VOCAB_MIN_FREQ_SELECTION
import org.continuouspath.justtype.Constants.KEY_VOCAB_PROMOTE_IMPORTED
import org.continuouspath.justtype.Constants.KEY_VOCAB_SHOW_EXCLUDED_AT_END
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.data.PhraseRepository
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VocabularyManagementActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	private lateinit var selectionSeek: SeekBar
	private lateinit var nextLetterSeek: SeekBar
	private lateinit var nextLetterMaxSeek: SeekBar
	private lateinit var accentUseCountSeek: SeekBar
	private lateinit var accentEnableSwitch: SwitchCompat
	private lateinit var accentEnableRow: LinearLayout
	private lateinit var accentUseCountRow: LinearLayout
	private lateinit var accentUseCountSystemRow: LinearLayout
	private lateinit var nextLetterRow: LinearLayout
	private lateinit var nextLetterMaxRow: LinearLayout
	private lateinit var accentUseCountTitle: TextView
	private lateinit var accentUseCountValue: TextView
	private lateinit var accentUseCountSystemTitle: TextView
	private lateinit var accentUseCountSystemValue: TextView
	private lateinit var accentTotalText: TextView
	private lateinit var accentUseCountSystemSeek: SeekBar
	private lateinit var nextLetterValue: TextView
	private lateinit var nextLetterMaxValue: TextView

	// Phase 3C-B: container for the Active Vocabularies table (now inlined
	// from the former Select Active Vocabularies page).
	private lateinit var vocabListContainer: LinearLayout
	private var accentMask: Long = 0L

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_vocabulary_management)

		val backButton: ImageButton = findViewById(R.id.backButton)
		backButton.setOnClickListener { finish() }

		val repo = SettingsRepository.get()
		ClassMetadataStore.ensureDefaults(repo)
		val freqFilterRow: LinearLayout = findViewById(R.id.freqFilterRow)
		val freqFilterSwitch: SwitchCompat = findViewById(R.id.freqFilterSwitch)
		nextLetterRow = findViewById(R.id.nextLetterRow)
		nextLetterSeek = findViewById(R.id.nextLetterSeek)
		nextLetterValue = findViewById(R.id.nextLetterValue)
		nextLetterMaxRow = findViewById(R.id.nextLetterMaxRow)
		nextLetterMaxSeek = findViewById(R.id.nextLetterMaxSeek)
		nextLetterMaxValue = findViewById(R.id.nextLetterMaxValue)
		val selectionRow: LinearLayout = findViewById(R.id.selectionRow)
		selectionSeek = findViewById(R.id.selectionSeek)
		val selectionValue: TextView = findViewById(R.id.selectionValue)
		val excludedRow: LinearLayout = findViewById(R.id.excludedRow)
		val excludedSwitch: SwitchCompat = findViewById(R.id.excludedSwitch)
		vocabListContainer = findViewById(R.id.vocabListContainer)
		accentEnableRow = findViewById(R.id.accentEnableRow)
		accentEnableSwitch = findViewById(R.id.accentEnableSwitch)
		accentTotalText = findViewById(R.id.accentTotalText)
		accentUseCountRow = findViewById(R.id.accentUseCountRow)
		accentUseCountTitle = findViewById(R.id.accentUseCountTitle)
		accentUseCountSeek = findViewById(R.id.accentUseCountSeek)
		accentUseCountValue = findViewById(R.id.accentUseCountValue)
		accentUseCountSystemRow = findViewById(R.id.accentUseCountSystemRow)
		accentUseCountSystemTitle = findViewById(R.id.accentUseCountSystemTitle)
		accentUseCountSystemSeek = findViewById(R.id.accentUseCountSystemSeek)
		accentUseCountSystemValue = findViewById(R.id.accentUseCountSystemValue)

		// Phase 3C-B: SelectActiveVocabularies button removed; table now
		// inlined into this page (rendered in onResume).
		val manageButton: Button = findViewById(R.id.manageVocabulariesButton)
		manageButton.setOnClickListener {
			startActivity(Intent(this, ManageVocabulariesActivity::class.java))
		}

		// Phase 3C: Next-Letter Hints lives here (next to the Accented switch);
		// Promote Imported moved here from the Select Active Vocabularies page.
		bindToggleRow(R.id.nextLetterHintsLayout, R.id.nextLetterHintsSwitch, KEY_NEXT_LETTER_HINTS)
		bindToggleRow(R.id.promoteImportedRow, R.id.promoteImportedSwitch, KEY_VOCAB_PROMOTE_IMPORTED)

		freqFilterSwitch.isChecked = repo.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)
		val savedNextLetter = repo.getInt(KEY_VOCAB_ACCENT_MIN_FREQ).coerceIn(1, 15)
		val savedNextLetterMax = repo.getInt(KEY_VOCAB_ACCENT_MAX_FREQ).coerceIn(0, 14)
		val savedSelection = repo.getInt(KEY_VOCAB_MIN_FREQ_SELECTION).coerceIn(1, 14)
		val savedExcluded = repo.getBoolean(KEY_VOCAB_SHOW_EXCLUDED_AT_END)
		val savedAccentEnabled = repo.getBoolean(KEY_VOCAB_ACCENT_ENABLED)
		// Phase 3D (Δ-35): valid range tightened to 0..20.
		val savedAccentUseCount = repo.getInt(KEY_VOCAB_ACCENT_USECOUNT_MAX).coerceIn(0, 20)
		val savedAccentUseCountSystem =
			repo.getInt(KEY_VOCAB_ACCENT_USECOUNT_MAX_JT).coerceIn(0, 20)
		accentMask = repo.getLong(KEY_VOCAB_ACCENT_MODULE_MASK, 0L)

		nextLetterSeek.max = 14
		nextLetterSeek.progress = savedNextLetter - 1
		nextLetterMaxSeek.max = 14
		nextLetterMaxSeek.progress = savedNextLetterMax
		// Phase 3D (Δ-35): use-count sliders invert old "15 = OFF on the
		// right" convention to "0 = OFF on the left, 1..20 = valid". The
		// pref value semantic was already 0 = OFF, so progress == value.
		accentUseCountSeek.max = 20
		accentUseCountSeek.progress = savedAccentUseCount.coerceIn(0, 20)
		accentUseCountSystemSeek.max = 20
		accentUseCountSystemSeek.progress = savedAccentUseCountSystem.coerceIn(0, 20)
		selectionSeek.max = 13
		selectionSeek.progress = savedSelection - 1
		excludedSwitch.isChecked = savedExcluded
		accentEnableSwitch.isChecked = savedAccentEnabled

		freqFilterRow.setOnClickListener {
			freqFilterSwitch.isChecked = !freqFilterSwitch.isChecked
		}
		freqFilterSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED, isChecked)
			updateEnabledStates(
				freqFilterSwitch,
				selectionRow,
				selectionSeek,
				excludedRow,
				excludedSwitch,
			)
			enforceAccentMinForJustType()
			updateSystemCounts()
			renderVocabList()
		}

		nextLetterSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = progress + 1
					nextLetterValue.text = formatAccentMinValue(value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = (nextLetterSeek.progress + 1).coerceIn(1, 15)
					repo.putInt(KEY_VOCAB_ACCENT_MIN_FREQ, value)
					validateAccentRange(changedMin = true)
					updateSystemCounts()
					renderVocabList()
				}
			},
		)

		nextLetterMaxSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					nextLetterMaxValue.text = formatAccentMaxValue(progress)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = nextLetterMaxSeek.progress.coerceIn(0, 14)
					repo.putInt(KEY_VOCAB_ACCENT_MAX_FREQ, value)
					validateAccentRange(changedMin = false)
					updateSystemCounts()
					renderVocabList()
				}
			},
		)

		selectionSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = progress + 1
					selectionValue.text = formatFreqValue(value)
					updateEnabledStates(
						freqFilterSwitch,
						selectionRow,
						selectionSeek,
						excludedRow,
						excludedSwitch,
					)
					// Refresh accent Min/Max displays so the
					// "(BELOW LIST CUTOFF)" suffix updates live as the user
					// drags the cutoff past the Min/Max values.
					enforceAccentMinForJustType()
					updateSystemCounts()
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val value = (selectionSeek.progress + 1).coerceIn(1, 14)
					repo.putInt(KEY_VOCAB_MIN_FREQ_SELECTION, value)
					enforceAccentMinForJustType()
					updateSystemCounts()
					renderVocabList()
				}
			},
		)

		excludedRow.setOnClickListener {
			if (excludedSwitch.isEnabled) excludedSwitch.isChecked = !excludedSwitch.isChecked
		}
		excludedSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_VOCAB_SHOW_EXCLUDED_AT_END, isChecked)
		}
		// Turkish/Azeri case override (mirrors the Keyboard Settings vocabulary page)
		bindToggleRow(R.id.turkishAzeriRow, R.id.turkishAzeriSwitch, KEY_TURKISH_AZERI_CASE_OVERRIDE)

		accentEnableRow.setOnClickListener {
			accentEnableSwitch.isChecked = !accentEnableSwitch.isChecked
		}
		accentEnableSwitch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(KEY_VOCAB_ACCENT_ENABLED, isChecked)
			updateAccentEnabledStates(isChecked)
			enforceAccentMinForJustType()
			updateSystemCounts()
			renderVocabList()
		}
		accentUseCountSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					accentUseCountValue.text = formatAccentUseCountValue(progress)
					updateAccentUseCountTitle(progress)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					// Phase 3D (Δ-35): progress IS the value (0=OFF, 1..20).
					val value = accentUseCountSeek.progress.coerceIn(0, 20)
					repo.putInt(KEY_VOCAB_ACCENT_USECOUNT_MAX, value)
					updateSystemCounts()
					renderVocabList()
				}
			},
		)
		accentUseCountSystemSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					accentUseCountSystemValue.text = formatAccentUseCountValue(progress)
					updateAccentUseCountSystemTitle(progress)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					// Phase 3D (Δ-35): progress IS the value (0=OFF, 1..20).
					val value = accentUseCountSystemSeek.progress.coerceIn(0, 20)
					repo.putInt(KEY_VOCAB_ACCENT_USECOUNT_MAX_JT, value)
					updateSystemCounts()
					renderVocabList()
				}
			},
		)

		// Phase 3C: include/accent toggle listeners removed along with the
		// duplicate Main Vocab Modules table; the underlying preferences are
		// now managed exclusively from Select Active Vocabularies, and this
		// activity reads them in updateSystemCounts() and onResume().

		accentUseCountValue.text = formatAccentUseCountValue(accentUseCountSeek.progress)
		accentUseCountSystemValue.text = formatAccentUseCountValue(accentUseCountSystemSeek.progress)
		nextLetterValue.text = formatAccentMinValue(savedNextLetter)
		nextLetterMaxValue.text = formatAccentMaxValue(savedNextLetterMax)
		updateAccentUseCountTitle(accentUseCountSeek.progress)
		updateAccentUseCountSystemTitle(accentUseCountSystemSeek.progress)
		selectionValue.text = formatFreqValue(savedSelection)
		updateEnabledStates(
			freqFilterSwitch,
			selectionRow,
			selectionSeek,
			excludedRow,
			excludedSwitch,
		)
		updateAccentEnabledStates(savedAccentEnabled)
		updateAccentUseCountSystemEnabled()
		updateAccentFilterEnabledStates()
		enforceAccentMinForJustType()
		updateSystemCounts()

		// Phase 3B: attach INFO PROMPT icons. The accent_use_count label is
		// shared across two rows (vocab and main DB); the helper finds the
		// first occurrence — the second row's icon will be added by Phase 3C
		// once the rows are re-named to disambiguate.
		val root: ViewGroup = findViewById(android.R.id.content)
		// Phase 3C: Standard Next-Letter Hints icon (moved here from JustType
		// Settings).
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.settings_next_letter_hints,
			R.string.info_prompt_next_letter_hints,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.vocab_mgmt_accent_enable,
			R.string.info_prompt_vm_enable_accent,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.vocab_mgmt_accent_use_count,
			R.string.info_prompt_vm_accent_use_count,
		)
		// Phase 3C-B: the "Limit Uncommon Words" SECTION prompt was previously
		// attached to the heading; move it to the "Restrict Uncommon Words"
		// SETTING that lives under that section.
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.vocab_mgmt_enable_freq_filter,
			R.string.info_prompt_vm_restrict_uncommon,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.vocab_mgmt_min_freq_include,
			R.string.info_prompt_vm_min_freq_selection,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.vocab_mgmt_display_excluded,
			R.string.info_prompt_vm_show_excluded_at_end,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.vocab_mgmt_min_freq_accent,
			R.string.info_prompt_vm_min_freq_accent,
		)
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.vocab_mgmt_max_freq_accent,
			R.string.info_prompt_vm_max_freq_accent,
		)
		// Phase 3C-B: Promote Imported icon (moved here from Select Active).
		SettingsInfoHelper.attachInfoIcon(
			root,
			R.string.select_vocab_promote_imported,
			R.string.info_prompt_sav_promote_imported,
		)
		// Phase 3C: JT-side accent-use-count title is dynamic (filled in by
		// updateAccentUseCountSystemTitle) so we attach via the TextView
		// overload rather than text-matching. The dialog uses the static
		// _jt label so the %s placeholder doesn't leak into the dialog.
		SettingsInfoHelper.attachInfoIcon(
			accentUseCountSystemTitle,
			R.string.vocab_mgmt_accent_use_count_jt,
			R.string.info_prompt_vm_accent_use_count_main,
		)
	}

	override fun onPause() {
		super.onPause()
		SettingsSpeechController.stop()
	}

	override fun onResume() {
		super.onResume()
		// Refresh the cached accentMask in case it was changed elsewhere
		// (e.g. Manage Vocabularies' import/merge/delete flow updates the
		// active mask). renderVocabList rebuilds the table fresh from
		// SharedPreferences each pass, picking up any out-of-band changes.
		accentMask = SettingsRepository.get().getLong(KEY_VOCAB_ACCENT_MODULE_MASK, 0L)
		updateAccentUseCountSystemEnabled()
		updateAccentFilterEnabledStates()
		enforceAccentMinForJustType()
		updateSystemCounts()
		renderVocabList()
	}

	/** Wires a row+switch pair to a boolean pref (tapping the row toggles the switch). */
	private fun bindToggleRow(rowId: Int, switchId: Int, prefKey: String) {
		val repo = SettingsRepository.get()
		val row: android.view.View = findViewById(rowId)
		val switch: SwitchCompat = findViewById(switchId)
		switch.isChecked = repo.getBoolean(prefKey)
		row.setOnClickListener { switch.isChecked = !switch.isChecked }
		switch.setOnCheckedChangeListener { _, isChecked ->
			repo.putBoolean(prefKey, isChecked)
		}
	}

	private fun updateEnabledStates(
		freqFilterSwitch: SwitchCompat,
		selectionRow: LinearLayout,
		selectionSeek: SeekBar,
		excludedRow: LinearLayout,
		excludedSwitch: SwitchCompat,
	) {
		val filterEnabled = freqFilterSwitch.isChecked
		setEnabledWithAlpha(selectionRow, filterEnabled)
		selectionSeek.isEnabled = filterEnabled
		val selectionValue = selectionSeek.progress + 1
		val allowExcludedToggle = filterEnabled && selectionValue > 1
		excludedSwitch.isEnabled = allowExcludedToggle
		setEnabledWithAlpha(excludedRow, allowExcludedToggle)
	}

	private fun formatFreqValue(value: Int): String = if (value <= 1) getString(R.string.format_freq_off) else value.toString()

	// Phase 3D (Δ-35): the use-count slider used to represent "OFF" at the
	// rightmost position (progress=15, value=0) with 1..15 as the valid
	// range. The spec requires "OFF" at the leftmost position (progress=0,
	// value=0) with 1..20 as the valid range — so progress and value are
	// now identical and no mapping is needed.

	private fun formatAccentUseCountValue(progress: Int): String = if (progress <= 0) getString(R.string.format_accent_usecount_disabled) else progress.toString()

	private fun formatAccentMinValue(value: Int): String {
		if (value >= 15) return getString(R.string.format_freq_no_minimum)
		val cutoff = listCutoffOrNull()
		return if (cutoff != null && value < cutoff) {
			getString(R.string.format_freq_below_list_cutoff, value.toString())
		} else {
			value.toString()
		}
	}

	private fun formatAccentMaxValue(value: Int): String {
		if (value <= 0) return getString(R.string.format_freq_no_maximum)
		val cutoff = listCutoffOrNull()
		return if (cutoff != null && value < cutoff) {
			getString(R.string.format_freq_below_list_cutoff, value.toString())
		} else {
			value.toString()
		}
	}

	/**
	 * Returns the Selection-List frequency cutoff (slider value 2..14) when the
	 * "Restrict Uncommon Words" filter is ON; null otherwise. Used by the
	 * accent Min/Max formatters to flag values that fall below the cutoff
	 * with "(BELOW LIST CUTOFF)" — words that wouldn't appear in the
	 * Selection List anyway, so Accented Hints for them have no effect.
	 *
	 * Reads `selectionSeek.progress` directly (rather than the persisted
	 * pref value) so the cutoff suffix on the accent Min/Max sliders
	 * updates live while the user drags the Selection-List slider — the
	 * persisted value lags until onStopTrackingTouch.
	 */
	private fun listCutoffOrNull(): Int? {
		val repo = SettingsRepository.get()
		if (!repo.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)) return null
		val sel = (selectionSeek.progress + 1).coerceIn(1, 14)
		return if (sel > 1) sel else null
	}

	private fun updateAccentEnabledStates(enabled: Boolean) {
		setEnabledWithAlpha(accentUseCountRow, enabled)
		accentUseCountSeek.isEnabled = enabled
		updateModuleAccentEnabledStates()
		updateAccentUseCountSystemEnabled()
		updateAccentFilterEnabledStates()
	}

	// Phase 3C: the per-module Include/Accent CheckBoxes were removed along
	// with the duplicate Main Vocab Modules table. The enabled-state helpers
	// now read directly from SharedPreferences (managed on Select Active
	// Vocabularies) and the cached accentMask.
	private fun updateModuleAccentEnabledStates() {
		// No per-module CheckBoxes to update on this page anymore; just
		// propagate to the system-counts row enabled-state.
		updateAccentUseCountSystemEnabled()
	}

	private fun updateAccentUseCountSystemEnabled() {
		val justtypeAccent = (accentMask and ClassMasks.CLASS_JUSTTYPE_MASK) != 0L
		val customAccent = (accentMask and ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK) != 0L
		val enabled = accentEnableSwitch.isChecked && (justtypeAccent || customAccent)
		setEnabledWithAlpha(accentUseCountSystemRow, enabled)
		accentUseCountSystemSeek.isEnabled = enabled
	}

	private fun updateAccentFilterEnabledStates() {
		val repo = SettingsRepository.get()
		val includeJustType = repo.getBoolean(KEY_VOCAB_INCLUDE_JUSTTYPE)
		val justtypeAccent = (accentMask and ClassMasks.CLASS_JUSTTYPE_MASK) != 0L
		val enabled = accentEnableSwitch.isChecked && includeJustType && justtypeAccent
		setEnabledWithAlpha(nextLetterRow, enabled)
		nextLetterSeek.isEnabled = enabled
		setEnabledWithAlpha(nextLetterMaxRow, enabled)
		nextLetterMaxSeek.isEnabled = enabled
	}

	private fun updateSystemCounts() {
		val repo = SettingsRepository.get()
		WordDb.open(filesDir, assets).use { wordDb ->
			updateSystemCountsLocked(wordDb, repo)
		}
	}

	private fun updateSystemCountsLocked(wordDb: WordDb, repo: SettingsRepository) {
		val phraseRepository = PhraseRepository(File(filesDir, "phrases.json"))
		val accentEnabled = repo.getBoolean(KEY_VOCAB_ACCENT_ENABLED)
		val accentMask = repo.getLong(KEY_VOCAB_ACCENT_MODULE_MASK, 0L)
		val activeMask = repo.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
		val includeJustType = repo.getBoolean(KEY_VOCAB_INCLUDE_JUSTTYPE)
		val includeCustom = repo.getBoolean(KEY_VOCAB_INCLUDE_CUSTOM_WORDS)
		val includePhrases = repo.getBoolean(KEY_VOCAB_INCLUDE_PHRASES)
		val filterEnabled = repo.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)
		val slider = repo.getInt(KEY_VOCAB_MIN_FREQ_SELECTION).coerceIn(1, 14)
		val minFreqClass = if (filterEnabled && slider > 1) (15 - slider) else null
		val activeJustTypeCount =
			if (minFreqClass == null) {
				wordDb.countJustTypeWords()
			} else {
				wordDb.countJustTypeWordsByFreqRange(minFreqClass, null)
			}
		// Phase 3C: per-module count TextViews removed along with the duplicate
		// Main Vocab Modules table; the per-module Active Word Count display
		// lives on the Select Active Vocabularies page.

		val maxUseCountImported =
			repo
				.getInt(KEY_VOCAB_ACCENT_USECOUNT_MAX)
				.coerceIn(0, 15)
				.takeIf { it > 0 }
				?.let { (it - 1).coerceAtLeast(0) }
		val maxUseCountSystem =
			repo
				.getInt(KEY_VOCAB_ACCENT_USECOUNT_MAX_JT)
				.coerceIn(0, 15)
				.takeIf { it > 0 }
				?.let { (it - 1).coerceAtLeast(0) }
		val accentMinRaw = repo.getInt(KEY_VOCAB_ACCENT_MIN_FREQ).coerceIn(1, 15)
		val accentMaxRaw = repo.getInt(KEY_VOCAB_ACCENT_MAX_FREQ).coerceIn(0, 14)
		val accentMin =
			if (
				filterEnabled &&
				slider > 1 &&
				(accentMask and ClassMasks.CLASS_JUSTTYPE_MASK) != 0L
			) {
				maxOf(accentMinRaw, slider)
			} else {
				accentMinRaw
			}
		val accentMinClass = if (accentMin >= 15) null else 15 - accentMin
		val accentMaxClass = if (accentMaxRaw <= 0) null else 15 - accentMaxRaw

		val justtypeAccentCount =
			if (accentEnabled && includeJustType && (accentMask and ClassMasks.CLASS_JUSTTYPE_MASK) != 0L) {
				if (maxUseCountSystem == null && accentMinClass == null && accentMaxClass == null) {
					activeJustTypeCount
				} else {
					wordDb.countJustTypeWordsByFilters(accentMinClass, accentMaxClass, maxUseCountSystem)
				}
			} else {
				0
			}
		val customAccentCount =
			if (accentEnabled && includeCustom && (accentMask and ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK) != 0L) {
				wordDb.countForMaskAndUseCount(ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK, maxUseCountSystem)
			} else {
				0
			}
		val phraseAccentCount =
			if (accentEnabled && includePhrases && (accentMask and ClassMasks.CLASS_PHRASES_MASK) != 0L) {
				phraseRepository.all().size
			} else {
				0
			}
		// Phase 3C: per-module Accented Word Count TextViews removed; the
		// values are still computed because they feed into the page-level
		// "Total active words shown with Accented Hints" total below.

		var totalAccent = justtypeAccentCount + customAccentCount + phraseAccentCount
		val pastBit = ClassMasks.CLASS_PAST_VOCABULARIES_MASK
		if (accentEnabled && (activeMask and pastBit) != 0L && (accentMask and pastBit) != 0L) {
			totalAccent += wordDb.countForMaskAndUseCount(pastBit, maxUseCountImported)
		}
		val imported = ClassMetadataStore.load(repo).filter { it.bitIndex >= 5 }
		imported.forEach { item ->
			val bitMask = ClassMasks.maskForBit(item.bitIndex)
			if ((activeMask and bitMask) != 0L && (accentMask and bitMask) != 0L) {
				totalAccent += wordDb.countForMaskAndUseCount(bitMask, maxUseCountImported)
			}
		}
		accentTotalText.text = getString(R.string.format_accent_total, totalAccent)
	}

	private fun updateAccentUseCountTitle(progress: Int) {
		// Phase 3D: when slider is at 0 (OFF), show a clean message instead
		// of the awkward "Spelled More Than DISABLED Times" template fill-in.
		accentUseCountTitle.text = if (progress <= 0) {
			getString(R.string.format_accent_usecount_title_off)
		} else {
			getString(R.string.format_accent_usecount_title, progress.toString())
		}
	}

	private fun updateAccentUseCountSystemTitle(progress: Int) {
		// Phase 3C: distinct format string so the JustType-Words slider says
		// "JustType Words" while the imported-vocab slider above says
		// "Vocabulary Words".
		// Phase 3D: when slider is at 0 (OFF), show a clean message.
		accentUseCountSystemTitle.text = if (progress <= 0) {
			getString(R.string.format_accent_usecount_title_off_jt)
		} else {
			getString(R.string.format_accent_usecount_title_jt, progress.toString())
		}
	}

	/**
	 * Refresh the Min/Max accent slider value displays so the
	 * "(BELOW LIST CUTOFF)" suffix appears or disappears based on the
	 * current Selection-List cutoff. The slider values themselves are NOT
	 * modified — the user keeps whatever Min/Max they configured, even if
	 * those values fall below the Selection-List cutoff (which means
	 * Accented Hints for those classes won't have a visible effect because
	 * the Selection List would already filter the words out). Surfacing the
	 * conflict is preferable to silently mutating the user's choice.
	 */
	private fun enforceAccentMinForJustType() {
		val minAccentValue = (nextLetterSeek.progress + 1).coerceIn(1, 15)
		val maxAccentValue = nextLetterMaxSeek.progress.coerceIn(0, 14)
		nextLetterValue.text = formatAccentMinValue(minAccentValue)
		nextLetterMaxValue.text = formatAccentMaxValue(maxAccentValue)
	}

	private fun validateAccentRange(changedMin: Boolean) {
		val repo = SettingsRepository.get()
		val minValue = (nextLetterSeek.progress + 1).coerceIn(1, 15)
		val maxValue = nextLetterMaxSeek.progress.coerceIn(0, 14)
		if (minValue >= 15 || maxValue <= 0) return
		if (maxValue < minValue) {
			Toast.makeText(this, getString(R.string.toast_accent_range_error), Toast.LENGTH_SHORT).show()
			if (changedMin) {
				nextLetterMaxSeek.progress = minValue
				nextLetterMaxValue.text = formatAccentMaxValue(minValue)
				repo.putInt(KEY_VOCAB_ACCENT_MAX_FREQ, minValue)
			} else {
				nextLetterSeek.progress = (maxValue - 1).coerceAtLeast(0)
				nextLetterValue.text = formatAccentMinValue(maxValue)
				repo.putInt(KEY_VOCAB_ACCENT_MIN_FREQ, maxValue)
			}
		}
	}

	private fun setEnabledWithAlpha(
		view: View,
		enabled: Boolean,
	) {
		// Alpha is only set on the outer view — alpha cascades through child
		// composition automatically, and applying it again at each level
		// would multiply it (0.4 * 0.4 * 0.4 ≈ 6% instead of 40%).
		view.alpha = if (enabled) 1.0f else 0.4f
		setEnabledRecursively(view, enabled)
	}

	private fun setEnabledRecursively(view: View, enabled: Boolean) {
		view.isEnabled = enabled
		// isEnabled does NOT cascade in Android — each widget's drawable
		// state is computed from its own isEnabled. Phase 3B's icon helper
		// wraps each row's label TextView inside a new LinearLayout, so we
		// have to walk the full subtree to reach the inner TextView/icon
		// — otherwise the inner widgets stay in their captured-at-wrap-time
		// state and render grayed out even after the row is re-enabled.
		if (view is ViewGroup) {
			for (i in 0 until view.childCount) {
				setEnabledRecursively(view.getChildAt(i), enabled)
			}
		}
	}

	// ───────────────────────────────────────────────────────────────────────
	// Phase 3C-B: Active Vocabularies table rendering
	// ───────────────────────────────────────────────────────────────────────
	// Migrated verbatim from SelectActiveVocabulariesActivity. Each call to
	// renderVocabList() rebuilds the rows under vocabListContainer based on
	// the current state of the active mask, accent mask, and filter prefs.
	// Triggered from onResume so the table refreshes whenever the user
	// returns to the page (e.g. after merging/deleting vocabularies on the
	// Manage Vocabularies page).

	private fun renderVocabList() {
		vocabListContainer.removeAllViews()
		val repo = SettingsRepository.get()
		ClassMetadataStore.ensureDefaults(repo)
		WordDb.open(filesDir, assets).use { wordDb ->
			renderVocabListLocked(wordDb, repo)
		}
	}

	private fun renderVocabListLocked(wordDb: WordDb, repo: SettingsRepository) {
		val accentEnabled = repo.getBoolean(KEY_VOCAB_ACCENT_ENABLED)
		var accentMaskLocal = repo.getLong(KEY_VOCAB_ACCENT_MODULE_MASK, 0L)
		var activeMask = repo.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)

		val phraseRepository = PhraseRepository(File(filesDir, "phrases.json"))
		val minFreqClass = getJustTypeMinFreqClass(repo)
		val justTypeCount =
			if (minFreqClass == null) {
				wordDb.countJustTypeWords()
			} else {
				wordDb.countJustTypeWordsByFreqRange(minFreqClass, null)
			}
		val customCount = WordDb.openStandalone(File(filesDir, "CustomDb.db")).use {
			it.countUserCustomWords()
		}
		val phraseCount = phraseRepository.all().size
		val pastCount = wordDb.countForClassMask(ClassMasks.CLASS_PAST_VOCABULARIES_MASK)

		val maxUseCountImported =
			repo
				.getInt(KEY_VOCAB_ACCENT_USECOUNT_MAX)
				.coerceIn(0, 15)
				.takeIf { it > 0 }
				?.let { (it - 1).coerceAtLeast(0) }
		val maxUseCountSystem =
			repo
				.getInt(KEY_VOCAB_ACCENT_USECOUNT_MAX_JT)
				.coerceIn(0, 15)
				.takeIf { it > 0 }
				?.let { (it - 1).coerceAtLeast(0) }
		val accentMinRaw = repo.getInt(KEY_VOCAB_ACCENT_MIN_FREQ).coerceIn(1, 15)
		val accentMaxRaw = repo.getInt(KEY_VOCAB_ACCENT_MAX_FREQ).coerceIn(0, 14)
		val accentMin =
			if (
				repo.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED) &&
				repo.getInt(KEY_VOCAB_MIN_FREQ_SELECTION).coerceIn(1, 14) > 1 &&
				(accentMaskLocal and ClassMasks.CLASS_JUSTTYPE_MASK) != 0L
			) {
				maxOf(accentMinRaw, repo.getInt(KEY_VOCAB_MIN_FREQ_SELECTION).coerceIn(1, 14))
			} else {
				accentMinRaw
			}
		val accentMinClass = if (accentMin >= 15) null else 15 - accentMin
		val accentMaxClass = if (accentMaxRaw <= 0) null else 15 - accentMaxRaw

		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

		val systemModules =
			listOf(
				SystemModule(ClassMasks.CLASS_JUSTTYPE_BIT, getString(R.string.vocab_module_justtype_main)),
				SystemModule(ClassMasks.CLASS_CUSTOM_WORDS_BIT, getString(R.string.vocab_module_custom_words)),
				SystemModule(ClassMasks.CLASS_PHRASES_BIT, getString(R.string.vocab_module_phrases)),
				SystemModule(ClassMasks.CLASS_PAST_VOCABULARIES_BIT, getString(R.string.vocab_module_past_vocabs)),
			)

		// Phase 3C-B fix (Issue 1b): when a row's enable/accent box toggles,
		// the user expects dependent settings outside the table (Min/Max
		// Frequency for Accented Hints, JT-side use-count slider, accent
		// total) to reflect the change immediately. Each row callback below
		// not only persists the new mask but also syncs the activity-side
		// accentMask/activeMask fields and triggers the same dependent-
		// update helpers the master toggle uses.
		val syncAccentMaskAndDependents = { newMask: Long ->
			accentMask = newMask
			updateAccentFilterEnabledStates()
			updateAccentUseCountSystemEnabled()
			updateSystemCounts()
		}
		val syncActiveMaskAndDependents = {
			updateAccentFilterEnabledStates()
			updateAccentUseCountSystemEnabled()
			updateSystemCounts()
		}

		systemModules.forEach { module ->
			val countLabel =
				when (module.bitIndex) {
					ClassMasks.CLASS_JUSTTYPE_BIT -> justTypeCount.toString()
					ClassMasks.CLASS_CUSTOM_WORDS_BIT -> customCount.toString()
					ClassMasks.CLASS_PHRASES_BIT -> phraseCount.toString()
					ClassMasks.CLASS_PAST_VOCABULARIES_BIT -> pastCount.toString()
					else -> "0"
				}
			val accentedCount =
				when (module.bitIndex) {
					ClassMasks.CLASS_JUSTTYPE_BIT ->
						if (accentEnabled && (accentMaskLocal and ClassMasks.CLASS_JUSTTYPE_MASK) != 0L) {
							if (maxUseCountSystem == null && accentMinClass == null && accentMaxClass == null) {
								justTypeCount
							} else {
								wordDb.countJustTypeWordsByFilters(accentMinClass, accentMaxClass, maxUseCountSystem)
							}
						} else {
							0
						}
					ClassMasks.CLASS_CUSTOM_WORDS_BIT ->
						if (accentEnabled && (accentMaskLocal and ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK) != 0L) {
							wordDb.countForMaskAndUseCount(ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK, maxUseCountSystem)
						} else {
							0
						}
					ClassMasks.CLASS_PHRASES_BIT ->
						if (accentEnabled && (accentMaskLocal and ClassMasks.CLASS_PHRASES_MASK) != 0L) {
							phraseRepository.all().size
						} else {
							0
						}
					ClassMasks.CLASS_PAST_VOCABULARIES_BIT ->
						if (accentEnabled && (accentMaskLocal and ClassMasks.CLASS_PAST_VOCABULARIES_MASK) != 0L) {
							wordDb.countForMaskAndUseCount(ClassMasks.CLASS_PAST_VOCABULARIES_MASK, maxUseCountImported)
						} else {
							0
						}
					else -> 0
				}
			val enableProvider =
				when (module.bitIndex) {
					ClassMasks.CLASS_JUSTTYPE_BIT -> BooleanPrefProvider(KEY_VOCAB_INCLUDE_JUSTTYPE)
					ClassMasks.CLASS_CUSTOM_WORDS_BIT -> BooleanPrefProvider(KEY_VOCAB_INCLUDE_CUSTOM_WORDS)
					ClassMasks.CLASS_PHRASES_BIT -> BooleanPrefProvider(KEY_VOCAB_INCLUDE_PHRASES)
					ClassMasks.CLASS_PAST_VOCABULARIES_BIT -> MaskPrefProvider(ClassMasks.CLASS_PAST_VOCABULARIES_MASK)
					else -> MaskPrefProvider(ClassMasks.maskForBit(module.bitIndex))
				}
			val accentBit =
				when (module.bitIndex) {
					ClassMasks.CLASS_JUSTTYPE_BIT -> ClassMasks.CLASS_JUSTTYPE_MASK
					ClassMasks.CLASS_CUSTOM_WORDS_BIT -> ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK
					ClassMasks.CLASS_PHRASES_BIT -> ClassMasks.CLASS_PHRASES_MASK
					ClassMasks.CLASS_PAST_VOCABULARIES_BIT -> ClassMasks.CLASS_PAST_VOCABULARIES_MASK
					else -> ClassMasks.maskForBit(module.bitIndex)
				}

			val row =
				createVocabRow(
					name = module.label,
					wordCountLabel = countLabel,
					accentedCountLabel =
					if (
						accentEnabled &&
						enableProvider.isEnabled(repo, activeMask) &&
						(accentMaskLocal and accentBit) != 0L
					) {
						accentedCount.toString()
					} else {
						"0"
					},
					dateLabel = getString(R.string.label_not_applicable),
					isEnabled = enableProvider.isEnabled(repo, activeMask),
					isAccented = (accentMaskLocal and accentBit) != 0L,
					accentEnabled = accentEnabled,
					onEnabledChanged = { checked ->
						enableProvider.setEnabled(repo, checked, activeMask).also { newMask ->
							activeMask = newMask
						}
						syncActiveMaskAndDependents()
					},
					onAccentedChanged = { checked ->
						accentMaskLocal = if (checked) accentMaskLocal or accentBit else accentMaskLocal and accentBit.inv()
						repo.putLong(KEY_VOCAB_ACCENT_MODULE_MASK, accentMaskLocal)
						syncAccentMaskAndDependents(accentMaskLocal)
					},
				)
			vocabListContainer.addView(row)
		}

		val userModules =
			ClassMetadataStore
				.load(repo)
				.filter { it.bitIndex >= 5 }
				.sortedByDescending { it.createdAt }

		userModules.forEach { item ->
			val bitMask = ClassMasks.maskForBit(item.bitIndex)
			val accentedCount =
				if (accentEnabled && (accentMaskLocal and bitMask) != 0L) {
					wordDb.countForMaskAndUseCount(bitMask, maxUseCountImported)
				} else {
					0
				}
			val row =
				createVocabRow(
					name = item.name,
					wordCountLabel = item.wordCount.toString(),
					accentedCountLabel =
					if (
						accentEnabled &&
						(activeMask and bitMask) != 0L &&
						(accentMaskLocal and bitMask) != 0L
					) {
						accentedCount.toString()
					} else {
						"0"
					},
					dateLabel = formatDate(item.createdAt, dateFormat),
					isEnabled = (activeMask and bitMask) != 0L,
					isAccented = (accentMaskLocal and bitMask) != 0L,
					accentEnabled = accentEnabled,
					onEnabledChanged = { checked ->
						activeMask = if (checked) activeMask or bitMask else activeMask and bitMask.inv()
						repo.putLong(KEY_VOCAB_ACTIVE_MASK, activeMask)
						syncActiveMaskAndDependents()
					},
					onAccentedChanged = { checked ->
						accentMaskLocal = if (checked) accentMaskLocal or bitMask else accentMaskLocal and bitMask.inv()
						repo.putLong(KEY_VOCAB_ACCENT_MODULE_MASK, accentMaskLocal)
						syncAccentMaskAndDependents(accentMaskLocal)
					},
				)
			vocabListContainer.addView(row)
		}
	}

	private fun createVocabRow(
		name: String,
		wordCountLabel: String,
		accentedCountLabel: String,
		dateLabel: String,
		isEnabled: Boolean,
		isAccented: Boolean,
		accentEnabled: Boolean,
		onEnabledChanged: (Boolean) -> Unit,
		onAccentedChanged: (Boolean) -> Unit,
	): View {
		val row = LinearLayout(this)
		row.orientation = LinearLayout.HORIZONTAL
		row.gravity = Gravity.CENTER_VERTICAL
		row.layoutParams =
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			)
		val padding = dp(8)
		row.setPadding(0, padding, 0, padding)

		val nameView = TextView(this)
		nameView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
		nameView.text = name
		nameView.textSize = 14f

		val countView = TextView(this)
		countView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
		countView.gravity = Gravity.CENTER
		countView.text = wordCountLabel
		countView.textSize = 12f
		countView.setTextColor(getColor(android.R.color.secondary_text_dark))

		val dateView = TextView(this)
		dateView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
		// Phase 3C-B fix (Issue 2): Date Added column header is gravity=center,
		// so center the cell text too.
		dateView.gravity = Gravity.CENTER
		dateView.text = dateLabel
		dateView.textSize = 12f
		dateView.setTextColor(getColor(android.R.color.secondary_text_dark))

		// Phase 3C-B fix (Issue 2): A CheckBox's `gravity` property centers
		// the *text* within the widget, not the checkbox graphic within its
		// layout cell — so the graphic ends up flush-left under a centered
		// header. Wrap each CheckBox in a centering container so the graphic
		// sits under the column header.
		val enableBox = CheckBox(this)
		enableBox.layoutParams = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT,
			LinearLayout.LayoutParams.WRAP_CONTENT,
		)
		enableBox.isChecked = isEnabled
		val enableContainer = LinearLayout(this)
		enableContainer.orientation = LinearLayout.HORIZONTAL
		enableContainer.gravity = Gravity.CENTER
		enableContainer.layoutParams =
			LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.55f)
		enableContainer.addView(enableBox)

		val accentBox = CheckBox(this)
		accentBox.layoutParams = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT,
			LinearLayout.LayoutParams.WRAP_CONTENT,
		)
		accentBox.isChecked = isAccented
		val accentContainer = LinearLayout(this)
		accentContainer.orientation = LinearLayout.HORIZONTAL
		accentContainer.gravity = Gravity.CENTER
		accentContainer.layoutParams =
			LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
		accentContainer.addView(accentBox)

		val accentCountView = TextView(this)
		accentCountView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
		accentCountView.gravity = Gravity.CENTER
		accentCountView.text = accentedCountLabel
		accentCountView.textSize = 12f
		accentCountView.setTextColor(getColor(android.R.color.secondary_text_dark))

		setAccentCheckboxEnabled(accentBox, accentEnabled && enableBox.isChecked)

		enableBox.setOnCheckedChangeListener { _, checked ->
			onEnabledChanged(checked)
			setAccentCheckboxEnabled(accentBox, accentEnabled && checked)
			accentCountView.text =
				if (accentEnabled && checked && accentBox.isChecked) accentedCountLabel else "0"
		}
		accentBox.setOnCheckedChangeListener { _, checked ->
			onAccentedChanged(checked)
			accentCountView.text =
				if (accentEnabled && checked && enableBox.isChecked) accentedCountLabel else "0"
		}

		row.addView(nameView)
		row.addView(countView)
		row.addView(dateView)
		row.addView(enableContainer)
		row.addView(accentContainer)
		row.addView(accentCountView)
		return row
	}

	private fun setAccentCheckboxEnabled(checkBox: CheckBox, enabled: Boolean) {
		checkBox.isEnabled = enabled
		checkBox.alpha = if (enabled) 1.0f else 0.4f
	}

	private fun formatDate(timestamp: Long, formatter: SimpleDateFormat): String {
		if (timestamp <= 0L) return getString(R.string.label_unknown)
		return formatter.format(Date(timestamp))
	}

	private fun getJustTypeMinFreqClass(repo: SettingsRepository): Int? {
		val filterEnabled = repo.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)
		val slider = repo.getInt(KEY_VOCAB_MIN_FREQ_SELECTION).coerceIn(1, 14)
		return if (filterEnabled && slider > 1) (15 - slider) else null
	}

	private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

	private data class SystemModule(
		val bitIndex: Int,
		val label: String,
	)

	private interface EnableProvider {
		fun isEnabled(repo: SettingsRepository, activeMask: Long): Boolean
		fun setEnabled(repo: SettingsRepository, enabled: Boolean, activeMask: Long): Long
	}

	private class BooleanPrefProvider(private val key: String) : EnableProvider {
		override fun isEnabled(repo: SettingsRepository, activeMask: Long): Boolean = repo.getBoolean(key, true)

		override fun setEnabled(repo: SettingsRepository, enabled: Boolean, activeMask: Long): Long {
			repo.putBoolean(key, enabled)
			return activeMask
		}
	}

	private class MaskPrefProvider(private val bitMask: Long) : EnableProvider {
		override fun isEnabled(repo: SettingsRepository, activeMask: Long): Boolean = (activeMask and bitMask) != 0L

		override fun setEnabled(repo: SettingsRepository, enabled: Boolean, activeMask: Long): Long {
			val nextMask = if (enabled) activeMask or bitMask else activeMask and bitMask.inv()
			repo.putLong(KEY_VOCAB_ACTIVE_MASK, nextMask)
			return nextMask
		}
	}
}
