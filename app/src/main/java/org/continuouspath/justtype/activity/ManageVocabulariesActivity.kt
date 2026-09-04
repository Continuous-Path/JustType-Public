package org.continuouspath.justtype.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.continuouspath.justtype.BackupManager
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.ClassMetadata
import org.continuouspath.justtype.ClassMetadataStore
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.Constants.KEY_EXPORT_USAGE_AT_LEAST_COUNT
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACCENT_MODULE_MASK
import org.continuouspath.justtype.Constants.KEY_VOCAB_FREQ_FILTER_ENABLED
import org.continuouspath.justtype.Constants.KEY_VOCAB_MIN_FREQ_SELECTION
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.VocabImportHelper
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getInt
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ManageVocabulariesActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	private var selectedUri: Uri? = null
	private var selectedDisplayName: String? = null
	private var currentTargetBit: Int = ClassMasks.CLASS_PAST_VOCABULARIES_BIT
	private var hasFreeSlot: Boolean = true
	private val rowStates = mutableListOf<RowState>()

	private lateinit var chooseFileButton: Button
	private lateinit var importButton: Button
	private lateinit var selectedFileText: TextView
	private lateinit var noSlotsMessage: TextView
	private lateinit var vocabNameEdit: EditText
	private lateinit var mergeListContainer: LinearLayout
	private lateinit var mergeButton: Button
	private lateinit var deleteButton: Button

	// Phase 3C-C: Export Usage section migrated from SelectActiveVocabularies.
	private lateinit var exportUsageGroup: RadioGroup
	private lateinit var exportUsageAtLeast: RadioButton
	private lateinit var exportUsageButton: Button
	private lateinit var exportUsageAtLeastSeek: SeekBar
	private lateinit var exportUsageAtLeastValue: TextView

	private val pickFileLauncher =
		registerForActivityResult(
			ActivityResultContracts.OpenDocument(),
		) { uri ->
			if (uri != null) {
				contentResolver.takePersistableUriPermission(
					uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION,
				)
				onFileSelected(uri)
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_manage_vocabularies)

		val backButton: ImageButton = findViewById(R.id.backButton)
		backButton.setOnClickListener { finish() }

		chooseFileButton = findViewById(R.id.chooseFileButton)
		importButton = findViewById(R.id.importButton)
		selectedFileText = findViewById(R.id.selectedFileText)
		noSlotsMessage = findViewById(R.id.noSlotsMessage)
		vocabNameEdit = findViewById(R.id.vocabNameEdit)
		mergeListContainer = findViewById(R.id.mergeListContainer)
		mergeButton = findViewById(R.id.mergeButton)
		deleteButton = findViewById(R.id.deleteButton)

		chooseFileButton.setOnClickListener {
			pickFileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
		}
		importButton.setOnClickListener { performImport() }
		mergeButton.setOnClickListener { handleMergeAction() }
		deleteButton.setOnClickListener { handleDeleteAction() }

		importButton.isEnabled = false

		// Phase 3C-C: Export Usage section bindings.
		exportUsageGroup = findViewById(R.id.exportUsageGroup)
		exportUsageAtLeast = findViewById(R.id.exportUsageAtLeast)
		exportUsageButton = findViewById(R.id.exportUsageButton)
		exportUsageAtLeastSeek = findViewById(R.id.exportUsageAtLeastSeek)
		exportUsageAtLeastValue = findViewById(R.id.exportUsageAtLeastValue)
		exportUsageGroup.check(R.id.exportUsageNever)
		exportUsageButton.setOnClickListener { handleExportUsage() }
	}

	override fun onResume() {
		super.onResume()
		refreshSlotAvailability()
		renderMergeList()
		updateActionButtons()

		// Phase 3C-C: Export Usage slider wiring (was set up in
		// SelectActiveVocabulariesActivity.onResume previously).
		val repo = SettingsRepository.get()
		val threshold = repo.getInt(KEY_EXPORT_USAGE_AT_LEAST_COUNT, 0).coerceIn(0, 15)
		exportUsageAtLeastSeek.max = 15
		exportUsageAtLeastSeek.progress = threshold
		updateExportUsageLabel()
		updateExportUsageAtLeastEnabled(threshold)

		exportUsageAtLeastSeek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					progress: Int,
					fromUser: Boolean,
				) {
					val value = progress.coerceIn(0, 15)
					repo.putInt(KEY_EXPORT_USAGE_AT_LEAST_COUNT, value)
					updateExportUsageLabel()
					updateExportUsageAtLeastEnabled(value)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) { /* no-op */ }

				override fun onStopTrackingTouch(seekBar: SeekBar?) { /* no-op */ }
			},
		)
	}

	private fun refreshSlotAvailability() {
		val repo = SettingsRepository.get()
		ClassMetadataStore.ensureDefaults(repo)
		val items = ClassMetadataStore.load(repo)
		hasFreeSlot = ClassMetadataStore.findNextFreeBit(items) != null
		chooseFileButton.isEnabled = hasFreeSlot
		noSlotsMessage.visibility = if (hasFreeSlot) View.GONE else View.VISIBLE
		importButton.isEnabled = selectedUri != null && hasFreeSlot
	}

	private fun onFileSelected(uri: Uri) {
		selectedUri = uri
		selectedDisplayName = VocabImportHelper.queryDisplayName(this, uri)
		selectedFileText.text = selectedDisplayName ?: uri.toString()
		if (vocabNameEdit.text.isEmpty()) {
			vocabNameEdit.setText(selectedDisplayName?.substringBeforeLast('.') ?: "")
		}
		importButton.isEnabled = hasFreeSlot
	}

	private fun renderMergeList() {
		mergeListContainer.removeAllViews()
		rowStates.clear()

		val repo = SettingsRepository.get()
		ClassMetadataStore.ensureDefaults(repo)
		val items = ClassMetadataStore.load(repo)
		// Phase 3C-C: counts for the system-module rows. JustType respects
		// the Selection-List "List Cutoff" filter (active-words count);
		// Custom & Phrases are unfiltered.
		val justTypeMinFreqClass = getJustTypeMinFreqClassForExport(repo)
		val (justTypeCount, pastCount) = WordDb.open(filesDir, assets).use { wordDb ->
			val jt = if (justTypeMinFreqClass == null) {
				wordDb.countJustTypeWords()
			} else {
				wordDb.countJustTypeWordsByFreqRange(justTypeMinFreqClass, null)
			}
			val past = wordDb.countForClassMask(ClassMasks.CLASS_PAST_VOCABULARIES_MASK)
			jt to past
		}
		val customCount = WordDb.openStandalone(java.io.File(filesDir, "CustomDb.db")).use {
			it.countUserCustomWords()
		}
		val phraseRepository =
			org.continuouspath.justtype.data.PhraseRepository(java.io.File(filesDir, "phrases.json"))
		val phraseCount = phraseRepository.all().size

		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
		val notApplicable = getString(R.string.label_not_applicable)

		// Phase 3C-C: system-module rows for the new "Export Vocabulary"
		// column. They cannot be merged-from, merged-into, or deleted, so
		// those columns render as "—". Order: JustType, Custom, Phrases,
		// Past Vocabularies.
		mergeListContainer.addView(
			createRow(
				name = getString(R.string.vocab_module_justtype_main),
				dateLabel = notApplicable,
				wordCountLabel = justTypeCount.toString(),
				bitIndex = ClassMasks.CLASS_JUSTTYPE_BIT,
				showMergeFrom = false,
				showDelete = false,
				showMergeInto = false,
			),
		)
		mergeListContainer.addView(
			createRow(
				name = getString(R.string.vocab_module_custom_words),
				dateLabel = notApplicable,
				wordCountLabel = customCount.toString(),
				bitIndex = ClassMasks.CLASS_CUSTOM_WORDS_BIT,
				showMergeFrom = false,
				showDelete = false,
				showMergeInto = false,
			),
		)
		mergeListContainer.addView(
			createRow(
				name = getString(R.string.vocab_module_phrases),
				dateLabel = notApplicable,
				wordCountLabel = phraseCount.toString(),
				bitIndex = ClassMasks.CLASS_PHRASES_BIT,
				showMergeFrom = false,
				showDelete = false,
				showMergeInto = false,
			),
		)
		val pastLabel = getString(R.string.label_past_vocabularies)
		val pastRow =
			createRow(
				name = pastLabel,
				dateLabel = notApplicable,
				wordCountLabel = pastCount.toString(),
				bitIndex = ClassMasks.CLASS_PAST_VOCABULARIES_BIT,
				showMergeFrom = false,
				showDelete = false,
			)
		mergeListContainer.addView(pastRow)

		val userModules =
			items
				.filter { it.bitIndex >= 5 }
				.sortedWith(
					compareBy<ClassMetadata>(
						{ -dayBucket(it.createdAt) },
						{ it.createdAt },
					),
				)

		userModules.forEach { item ->
			val row =
				createRow(
					name = item.name,
					dateLabel = formatDate(item.createdAt, dateFormat),
					wordCountLabel = item.wordCount.toString(),
					bitIndex = item.bitIndex,
					showMergeFrom = true,
					showDelete = true,
				)
			mergeListContainer.addView(row)
		}

		setMergeTarget(currentTargetBit)
		updateActionButtons()
	}

	private fun updateActionButtons() {
		val anyMergeFrom = rowStates.any { it.mergeFrom?.isChecked == true }
		val anyDelete = rowStates.any { it.deleteBox?.isChecked == true }
		mergeButton.isEnabled = anyMergeFrom
		deleteButton.isEnabled = anyDelete
		importButton.isEnabled = selectedUri != null && hasFreeSlot
	}

	private fun createRow(
		name: String,
		dateLabel: String,
		wordCountLabel: String,
		bitIndex: Int,
		showMergeFrom: Boolean,
		showDelete: Boolean,
		// Phase 3C-C: system-module rows (JustType DB / Custom Words /
		// Phrases) cannot be merged into either, so they pass false here
		// and the cell renders as "—".
		showMergeInto: Boolean = true,
	): LinearLayout {
		val row = LinearLayout(this)
		row.orientation = LinearLayout.HORIZONTAL
		row.gravity = Gravity.CENTER_VERTICAL
		row.layoutParams =
			LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT,
			)
		val padding = dp(6)
		row.setPadding(0, padding, 0, padding)

		val nameView = TextView(this)
		nameView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.50f)
		nameView.text = name
		nameView.textSize = 14f

		val dateView = TextView(this)
		dateView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.78f)
		// Phase 3C-C alignment fix: column header is gravity=center; match it
		// here so the date text doesn't sit flush-left under a centered header.
		dateView.gravity = Gravity.CENTER
		dateView.text = dateLabel
		dateView.textSize = 12f
		dateView.setTextColor(getColor(android.R.color.secondary_text_dark))

		val countView = TextView(this)
		countView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.50f)
		countView.gravity = Gravity.CENTER
		countView.text = wordCountLabel
		countView.textSize = 12f
		countView.setTextColor(getColor(android.R.color.secondary_text_dark))

		// Phase 3C-C alignment fix: every action cell wraps its widget (or a
		// "—" placeholder) in a horizontal LinearLayout with gravity=CENTER
		// and uses `width=0 + weight` for the wrapper. Previously each
		// CheckBox/RadioButton had its own `marginStart=12dp` which ate into
		// the row's weight-distributable width — system-module rows (which
		// render TextView "—" instead of a CheckBox) had less margin
		// reduction than user-imported rows, so the same weights produced
		// different column widths per row, drifting the columns. With a
		// uniform wrapper-per-cell, every row has the same total margin
		// budget and weights resolve to identical column widths across all
		// row types.

		val mergeFromCheck: CheckBox? =
			if (showMergeFrom) {
				CheckBox(this).apply { setOnCheckedChangeListener { _, _ -> updateActionButtons() } }
			} else {
				null
			}
		val mergeFromCell: View =
			if (mergeFromCheck != null) {
				centeredCell(weight = 0.48f, content = mergeFromCheck)
			} else {
				dashCell(weight = 0.48f)
			}

		val mergeIntoRadio: RadioButton? =
			if (showMergeInto) {
				RadioButton(this).apply { setOnClickListener { setMergeTarget(bitIndex) } }
			} else {
				null
			}
		val mergeIntoCell: View =
			if (mergeIntoRadio != null) {
				centeredCell(weight = 0.48f, content = mergeIntoRadio)
			} else {
				dashCell(weight = 0.48f)
			}

		val deleteCheck: CheckBox? =
			if (showDelete) {
				CheckBox(this).apply { setOnCheckedChangeListener { _, _ -> updateActionButtons() } }
			} else {
				null
			}
		val deleteCell: View =
			if (deleteCheck != null) {
				centeredCell(weight = 0.78f, content = deleteCheck)
			} else {
				dashCell(weight = 0.78f)
			}

		// Phase 3C-C: Export Vocabulary column — always interactive, so
		// always wrapped (no "—" branch).
		val exportCheck = CheckBox(this)
		val exportCell = centeredCell(weight = 0.78f, content = exportCheck)

		row.addView(nameView)
		row.addView(dateView)
		row.addView(countView)
		row.addView(mergeFromCell)
		row.addView(mergeIntoCell)
		row.addView(deleteCell)
		row.addView(exportCell)

		rowStates.add(
			RowState(
				bitIndex = bitIndex,
				mergeFrom = mergeFromCheck,
				mergeInto = mergeIntoRadio,
				deleteBox = deleteCheck,
				exportBox = exportCheck,
				label = name,
			),
		)
		return row
	}

	/**
	 * Phase 3C-C: A column cell that centers the given control horizontally.
	 * The wrapper takes the column weight; the control inside is
	 * wrap_content. Used for every CheckBox/RadioButton in the table so
	 * they sit under their column headers regardless of which row type
	 * the cell belongs to (system module / Past Vocabularies / user-imported).
	 */
	private fun centeredCell(weight: Float, content: View): LinearLayout {
		val wrapper = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER
			layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
		}
		content.layoutParams = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT,
			LinearLayout.LayoutParams.WRAP_CONTENT,
		)
		wrapper.addView(content)
		return wrapper
	}

	/**
	 * Phase 3C-C: A "—" placeholder cell used where an action doesn't apply
	 * to a given row (e.g. Merge From / Delete on the Past Vocabularies row).
	 */
	private fun dashCell(weight: Float): TextView = TextView(this).apply {
		layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
		gravity = Gravity.CENTER
		text = getString(R.string.label_em_dash)
		textSize = 12f
		setTextColor(getColor(android.R.color.secondary_text_dark))
	}

	private fun setMergeTarget(bitIndex: Int) {
		currentTargetBit = bitIndex
		rowStates.forEach { row ->
			// System-module rows have mergeInto = null; skip them.
			row.mergeInto?.isChecked = row.bitIndex == bitIndex
			row.mergeFrom?.let { mergeFrom ->
				val shouldEnable = row.bitIndex != bitIndex
				mergeFrom.isEnabled = shouldEnable
				if (!shouldEnable) mergeFrom.isChecked = false
			}
		}
		updateActionButtons()
	}

	private fun performImport() {
		val uri = selectedUri
		if (uri == null) {
			Toast.makeText(this, getString(R.string.toast_choose_file_first), Toast.LENGTH_SHORT).show()
			return
		}
		val name = vocabNameEdit.text.toString().trim()
		if (name.isEmpty()) {
			Toast.makeText(this, getString(R.string.toast_enter_vocab_name), Toast.LENGTH_SHORT).show()
			return
		}
		val repo = SettingsRepository.get()
		val items = ClassMetadataStore.load(repo)
		val freeBit = ClassMetadataStore.findNextFreeBit(items)
		if (freeBit == null) {
			refreshSlotAvailability()
			Toast.makeText(this, getString(R.string.toast_no_available_slot), Toast.LENGTH_SHORT).show()
			return
		}

		val bitMask = 1L shl freeBit
		val importedCount = WordDb.open(filesDir, assets).use { wordDb ->
			wordDb.deleteImportedWordsForClass(bitMask)
			wordDb.clearClassMask(bitMask)
			VocabImportHelper.importVocabulary(this, uri, freeBit)
			wordDb.countForClassMask(bitMask)
		}

		val updated = ClassMetadataStore.remove(items, freeBit)
		val finalItems =
			ClassMetadataStore.upsert(
				updated,
				freeBit,
				name,
				selectedDisplayName,
				selectedDisplayName,
				importedCount,
			)
		ClassMetadataStore.save(repo, finalItems)

		val activeMask = repo.getLong(Constants.KEY_VOCAB_ACTIVE_MASK, 0L)
		repo.putLong(Constants.KEY_VOCAB_ACTIVE_MASK, activeMask or bitMask)
		sendBroadcast(Intent(Constants.ACTION_VOCAB_UPDATED))
		BackupManager.scheduleBackup(this)
		Toast.makeText(this, getString(R.string.toast_imported_vocab, name, freeBit + 1), Toast.LENGTH_LONG).show()

		selectedUri = null
		selectedDisplayName = null
		selectedFileText.text = getString(R.string.no_file_selected)
		vocabNameEdit.text?.clear()
		importButton.isEnabled = false

		refreshSlotAvailability()
		renderMergeList()
	}

	private fun handleMergeAction() {
		val mergeFromBits =
			rowStates
				.filter { it.mergeFrom?.isChecked == true }
				.map { it.bitIndex }
		if (mergeFromBits.isEmpty()) return
		if (mergeFromBits.contains(currentTargetBit)) {
			Toast.makeText(this, getString(R.string.toast_merge_target_conflict), Toast.LENGTH_SHORT).show()
			return
		}

		if (currentTargetBit == ClassMasks.CLASS_PAST_VOCABULARIES_BIT) {
			performMerge(null, mergeFromBits)
			return
		}

		val currentTarget = rowStates.firstOrNull { it.bitIndex == currentTargetBit }?.label ?: ""
		val input = EditText(this)
		input.setText(currentTarget)
		AlertDialog
			.Builder(this)
			.setTitle(getString(R.string.dialog_rename_merge_target))
			.setMessage(getString(R.string.dialog_rename_merge_message))
			.setView(input)
			.setPositiveButton(getString(R.string.label_save)) { _, _ ->
				val updatedName =
					input.text
						.toString()
						.trim()
						.ifEmpty { currentTarget }
				performMerge(updatedName, mergeFromBits)
			}.setNegativeButton(getString(R.string.label_cancel), null)
			.show()
	}

	private fun performMerge(
		updatedTargetName: String?,
		mergeFromBits: List<Int>,
	) {
		val repo = SettingsRepository.get()
		val targetMask = 1L shl currentTargetBit
		var sourceMask = 0L
		mergeFromBits.forEach { bit -> sourceMask = sourceMask or (1L shl bit) }

		val items = ClassMetadataStore.load(repo)
		val baseItems = items.filterNot { mergeFromBits.contains(it.bitIndex) }
		val targetItem = baseItems.firstOrNull { it.bitIndex == currentTargetBit }
		val newName = updatedTargetName ?: targetItem?.name ?: getString(R.string.label_past_vocabularies)
		val newWordCount = WordDb.open(filesDir, assets).use { wordDb ->
			wordDb.mergeVocabularyMasks(sourceMask, targetMask)
			wordDb.countForClassMask(targetMask)
		}
		val updatedItems =
			ClassMetadataStore.upsert(
				baseItems,
				currentTargetBit,
				newName,
				targetItem?.source,
				targetItem?.fileName,
				newWordCount,
			)
		ClassMetadataStore.save(repo, updatedItems)

		val activeMask = repo.getLong(Constants.KEY_VOCAB_ACTIVE_MASK, 0L)
		val accentMask = repo.getLong(Constants.KEY_VOCAB_ACCENT_MODULE_MASK, 0L)
		repo.edit()
			.putLong(Constants.KEY_VOCAB_ACTIVE_MASK, activeMask and sourceMask.inv())
			.putLong(Constants.KEY_VOCAB_ACCENT_MODULE_MASK, accentMask and sourceMask.inv())
			.apply()

		val updateIntent = Intent(Constants.ACTION_VOCAB_UPDATED)
		updateIntent.putExtra(Constants.EXTRA_VOCAB_MERGE_SOURCE_MASK, sourceMask)
		updateIntent.putExtra(Constants.EXTRA_VOCAB_MERGE_TARGET_MASK, targetMask)
		sendBroadcast(updateIntent)
		BackupManager.scheduleBackup(this)

		renderMergeList()
		Toast.makeText(this, getString(R.string.toast_merged_vocabs, mergeFromBits.size), Toast.LENGTH_SHORT).show()
	}

	private fun handleDeleteAction() {
		val deleteBits =
			rowStates
				.filter { it.deleteBox?.isChecked == true }
				.map { it.bitIndex }
		if (deleteBits.isEmpty()) return
		val label =
			if (deleteBits.size ==
				1
			) {
				getString(R.string.dialog_confirm_delete_single)
			} else {
				getString(R.string.dialog_confirm_delete_plural, deleteBits.size)
			}
		AlertDialog
			.Builder(this)
			.setTitle(getString(R.string.dialog_confirm_delete_title))
			.setMessage(getString(R.string.dialog_confirm_delete_message, label))
			.setPositiveButton(getString(R.string.dialog_delete_button)) { _, _ -> performDelete(deleteBits) }
			.setNegativeButton(getString(R.string.label_cancel), null)
			.show()
	}

	private fun performDelete(deleteBits: List<Int>) {
		val deleteMask = deleteBits.fold(0L) { acc, bit -> acc or (1L shl bit) }
		WordDb.open(filesDir, assets).use { it.clearClassMask(deleteMask) }

		val repo = SettingsRepository.get()
		val items = ClassMetadataStore.load(repo)
		val updatedItems = items.filterNot { deleteBits.contains(it.bitIndex) }
		ClassMetadataStore.save(repo, updatedItems)

		val activeMask = repo.getLong(Constants.KEY_VOCAB_ACTIVE_MASK, 0L)
		val accentMask = repo.getLong(Constants.KEY_VOCAB_ACCENT_MODULE_MASK, 0L)
		repo.edit()
			.putLong(Constants.KEY_VOCAB_ACTIVE_MASK, activeMask and deleteMask.inv())
			.putLong(Constants.KEY_VOCAB_ACCENT_MODULE_MASK, accentMask and deleteMask.inv())
			.apply()

		val updateIntent = Intent(Constants.ACTION_VOCAB_UPDATED)
		updateIntent.putExtra(Constants.EXTRA_VOCAB_DELETE_MASK, deleteMask)
		sendBroadcast(updateIntent)
		BackupManager.scheduleBackup(this)

		renderMergeList()
		Toast.makeText(this, getString(R.string.toast_deleted_vocabs, deleteBits.size), Toast.LENGTH_SHORT).show()
	}

	private fun formatDate(
		timestamp: Long,
		formatter: SimpleDateFormat,
	): String {
		if (timestamp <= 0L) return getString(R.string.label_unknown)
		return formatter.format(Date(timestamp))
	}

	private fun dayBucket(timestamp: Long): Int {
		if (timestamp <= 0L) return 0
		val cal = Calendar.getInstance()
		cal.timeInMillis = timestamp
		val year = cal.get(Calendar.YEAR)
		val month = cal.get(Calendar.MONTH) + 1
		val day = cal.get(Calendar.DAY_OF_MONTH)
		return year * 10000 + month * 100 + day
	}

	private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

	private data class RowState(
		val bitIndex: Int,
		val mergeFrom: CheckBox?,
		// Phase 3C-C: nullable now — system-module rows (JustType DB /
		// Custom Words / Phrases) can't be merge targets so they render
		// "—" instead of a RadioButton.
		val mergeInto: RadioButton?,
		val deleteBox: CheckBox?,
		// Phase 3C-C: Export Vocabulary checkbox per row — selects which
		// modules to include in Export Usage Data.
		val exportBox: CheckBox?,
		val label: String,
	)

	// ───────────────────────────────────────────────────────────────────────
	// Phase 3C-C: Export Usage section (migrated from
	// SelectActiveVocabulariesActivity).
	// ───────────────────────────────────────────────────────────────────────
	// The button operates on whichever rows in the table above have the
	// "Export Vocabulary" column checked. Note: only Past Vocabularies and
	// user-imported vocabularies appear in this table, so the system
	// modules (JustType Main Database / Custom Words / Phrases) cannot be
	// exported from this page. If exporting those is needed, it would
	// require either a separate flow or extending the table to include
	// system-module rows.

	private fun updateExportUsageLabel() {
		val repo = SettingsRepository.get()
		val value = repo.getInt(KEY_EXPORT_USAGE_AT_LEAST_COUNT, 0).coerceIn(0, 15)
		val label = if (value <= 0) getString(R.string.label_disabled) else value.toString()
		exportUsageAtLeast.text = getString(R.string.format_at_least_times, label)
		exportUsageAtLeastValue.text = label
		val accentFiltersApplied = false
		exportUsageButton.text =
			if (accentFiltersApplied) {
				getString(R.string.export_button_with_accent)
			} else {
				getString(R.string.select_vocab_export_button)
			}
	}

	private fun handleExportUsage() {
		val repo = SettingsRepository.get()
		val items = ClassMetadataStore.load(repo)

		val selectedModules = mutableListOf<ExportModule>()

		// Walk every row in display order and include any module whose
		// Export Vocabulary checkbox is on.
		rowStates.forEach { state ->
			if (state.exportBox?.isChecked != true) return@forEach
			when (state.bitIndex) {
				ClassMasks.CLASS_JUSTTYPE_BIT ->
					selectedModules.add(
						ExportModule(
							name = getString(R.string.export_module_justtype),
							mask = ClassMasks.CLASS_JUSTTYPE_MASK,
							type = ExportModuleType.WORDS,
						),
					)
				ClassMasks.CLASS_CUSTOM_WORDS_BIT ->
					selectedModules.add(
						ExportModule(
							name = getString(R.string.export_module_custom),
							mask = ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK,
							type = ExportModuleType.WORDS,
						),
					)
				ClassMasks.CLASS_PHRASES_BIT ->
					selectedModules.add(
						ExportModule(
							name = getString(R.string.export_module_phrases),
							mask = ClassMasks.CLASS_PHRASES_MASK,
							type = ExportModuleType.PHRASES,
						),
					)
				ClassMasks.CLASS_PAST_VOCABULARIES_BIT ->
					selectedModules.add(
						ExportModule(
							name = getString(R.string.vocab_module_past_vocabs),
							mask = ClassMasks.CLASS_PAST_VOCABULARIES_MASK,
							type = ExportModuleType.WORDS,
						),
					)
				else -> {
					if (state.bitIndex >= 5) {
						val item = items.firstOrNull { it.bitIndex == state.bitIndex } ?: return@forEach
						selectedModules.add(
							ExportModule(
								name = item.name,
								mask = ClassMasks.maskForBit(item.bitIndex),
								type = ExportModuleType.WORDS,
							),
						)
					}
				}
			}
		}

		if (selectedModules.isEmpty()) {
			Toast.makeText(this, getString(R.string.toast_no_vocabs_to_export), Toast.LENGTH_SHORT).show()
			return
		}

		val (minUse, maxUse) = getUseCountRange(repo)
		// Phase 3C-C: estimate the total word count and warn the user if
		// the export will be large (e.g. selecting the JustType Main
		// Database with "All Words" can pull ~50k words). 200 is the
		// arbitrary threshold above which we surface a confirmation
		// dialog so the user can cancel.
		val estimated = estimateExportWordCount(repo, selectedModules, minUse, maxUse)
		if (estimated > EXPORT_WARNING_THRESHOLD) {
			val msg = getString(R.string.dialog_continue_export_message, estimated)
			Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
			AlertDialog.Builder(this)
				.setTitle(getString(R.string.dialog_continue_export_title))
				.setMessage(msg)
				.setPositiveButton(getString(R.string.dialog_continue_button)) { _, _ ->
					launchExportActivity(selectedModules, minUse, maxUse)
				}
				.setNegativeButton(getString(R.string.label_cancel), null)
				.show()
		} else {
			launchExportActivity(selectedModules, minUse, maxUse)
		}
	}

	companion object {
		// Phase 3C-C: above this many words, the Export Usage flow shows a
		// confirmation dialog so the user can cancel before the export runs.
		// 200 is an arbitrary "this will probably take a moment" threshold;
		// the JustType Main Database alone can pull ~50k words.
		private const val EXPORT_WARNING_THRESHOLD = 200
	}

	private fun getJustTypeMinFreqClassForExport(repo: SettingsRepository): Int? {
		val filterEnabled = repo.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)
		val slider = repo.getInt(KEY_VOCAB_MIN_FREQ_SELECTION).coerceIn(1, 14)
		return if (filterEnabled && slider > 1) (15 - slider) else null
	}

	private fun estimateExportWordCount(
		repo: SettingsRepository,
		modules: List<ExportModule>,
		minUseCount: Int?,
		maxUseCount: Int?,
	): Int {
		val justTypeMinFreqClass = getJustTypeMinFreqClassForExport(repo)
		return WordDb.open(filesDir, assets).use { wordDb ->
			var total = 0
			modules.forEach { mod ->
				total += when {
					mod.type == ExportModuleType.PHRASES -> {
						// Phrases are stored separately; the warning estimate
						// uses the total phrase count rather than per-phrase
						// use-count filtering (which Phrases don't track).
						org.continuouspath.justtype.data
							.PhraseRepository(java.io.File(filesDir, "phrases.json"))
							.all()
							.size
					}
					mod.mask == ClassMasks.CLASS_JUSTTYPE_MASK ->
						wordDb.countForMaskUseCountAndFreqRange(
							mask = ClassMasks.CLASS_JUSTTYPE_MASK,
							minUseCount = minUseCount,
							maxUseCount = maxUseCount,
							minFreqClass = justTypeMinFreqClass,
							maxFreqClass = null,
						)
					else ->
						wordDb.countForMaskUseCountAndFreqRange(
							mask = mod.mask,
							minUseCount = minUseCount,
							maxUseCount = maxUseCount,
							minFreqClass = null,
							maxFreqClass = null,
						)
				}
			}
			total
		}
	}

	private fun getUseCountRange(repo: SettingsRepository): Pair<Int?, Int?> {
		val threshold = repo.getInt(KEY_EXPORT_USAGE_AT_LEAST_COUNT, 0).coerceIn(0, 15)
		return when (exportUsageGroup.checkedRadioButtonId) {
			R.id.exportUsageNever -> 0 to 0
			R.id.exportUsageOnce -> 1 to null
			R.id.exportUsageAtLeast -> if (threshold <= 0) 0 to null else threshold to null
			R.id.exportUsageAll -> null to null
			else -> null to null
		}
	}

	private fun updateExportUsageAtLeastEnabled(value: Int) {
		val enabled = value > 0
		exportUsageAtLeast.isEnabled = enabled
		exportUsageAtLeast.alpha = if (enabled) 1.0f else 0.4f
		if (!enabled && exportUsageGroup.checkedRadioButtonId == R.id.exportUsageAtLeast) {
			exportUsageGroup.check(R.id.exportUsageAll)
		}
	}

	private fun launchExportActivity(
		modules: List<ExportModule>,
		minUseCount: Int?,
		maxUseCount: Int?,
	) {
		val arr = JSONArray()
		modules.forEach { mod ->
			val obj = JSONObject()
			obj.put("name", mod.name)
			obj.put("mask", mod.mask)
			obj.put("type", mod.type.name)
			arr.put(obj)
		}
		val intent = Intent(this, ExportVocabUsageActivity::class.java).apply {
			putExtra(ExportVocabUsageActivity.EXTRA_MODULES, arr.toString())
			putExtra(ExportVocabUsageActivity.EXTRA_MIN_USE, minUseCount ?: -1)
			putExtra(ExportVocabUsageActivity.EXTRA_MAX_USE, maxUseCount ?: -1)
		}
		startActivity(intent)
	}

	private data class ExportModule(
		val name: String,
		val mask: Long,
		val type: ExportModuleType,
	)

	private enum class ExportModuleType { WORDS, PHRASES }
}
