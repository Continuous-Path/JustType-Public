package org.continuouspath.justtype.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.continuouspath.justtype.BackupManager
import org.continuouspath.justtype.ClassMetadata
import org.continuouspath.justtype.ClassMetadataStore
import org.continuouspath.justtype.Constants.ACTION_VOCAB_UPDATED
import org.continuouspath.justtype.Constants.EXTRA_VOCAB_MERGE_SOURCE_MASK
import org.continuouspath.justtype.Constants.EXTRA_VOCAB_MERGE_TARGET_MASK
import org.continuouspath.justtype.Constants.KEY_VOCAB_ACTIVE_MASK
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.VocabImportHelper
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsRepository
import java.util.Locale

class VocabularyImportActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	private var selectedUri: Uri? = null
	private var selectedDisplayName: String? = null
	private var sharedText: String? = null

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
		setContentView(R.layout.activity_vocabulary_import)

		val backButton: ImageButton = findViewById(R.id.backButton)
		backButton.setOnClickListener { finish() }

		val pickFileButton: Button = findViewById(R.id.pickFileButton)
		val selectedFileText: TextView = findViewById(R.id.selectedFileText)
		val vocabNameEdit: EditText = findViewById(R.id.vocabNameEdit)
		val classListContainer: LinearLayout = findViewById(R.id.classListContainer)
		val importButton: Button = findViewById(R.id.importButton)

		val repo = SettingsRepository.get()
		ClassMetadataStore.ensureDefaults(repo)
		renderClassList(classListContainer)

		pickFileButton.setOnClickListener {
			pickFileLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
		}

		importButton.setOnClickListener {
			val uri = selectedUri
			val inlineText = sharedText
			if (uri == null) {
				if (inlineText.isNullOrBlank()) {
					Toast.makeText(this, getString(R.string.toast_choose_file_first), Toast.LENGTH_SHORT).show()
					return@setOnClickListener
				}
			}
			val name = vocabNameEdit.text.toString().trim()
			if (name.isEmpty()) {
				Toast.makeText(this, getString(R.string.toast_enter_vocab_name), Toast.LENGTH_SHORT).show()
				return@setOnClickListener
			}
			val items = ClassMetadataStore.load(repo)
			val freeBit = ClassMetadataStore.findNextFreeBit(items)
			if (freeBit != null) {
				performImport(uri, inlineText, name, freeBit, items)
			} else {
				showReplaceDialog(items) { bit ->
					val selectedItem = items.firstOrNull { it.bitIndex == bit }
					if (selectedItem == null) {
						Toast.makeText(this, getString(R.string.toast_selected_vocab_not_found), Toast.LENGTH_SHORT).show()
						return@showReplaceDialog
					}
					showMergeChoiceDialog(
						selectedItem,
						onDelete = { performImport(uri, inlineText, name, bit, items) },
						onMerge = {
							showMergeTargetDialog(items, bit) { targetBit ->
								mergeAndImport(uri, inlineText, name, bit, targetBit, items)
							}
						},
					)
				}
			}
		}

		if (intent?.action == Intent.ACTION_SEND) {
			handleSharedIntent(intent, selectedFileText, vocabNameEdit)
		} else {
			selectedFileText.text = getString(R.string.no_file_selected)
		}
	}

	private fun onFileSelected(uri: Uri) {
		selectedUri = uri
		selectedDisplayName = queryDisplayName(uri)
		val selectedFileText: TextView = findViewById(R.id.selectedFileText)
		val vocabNameEdit: EditText = findViewById(R.id.vocabNameEdit)
		selectedFileText.text = selectedDisplayName ?: uri.toString()
		if (vocabNameEdit.text.isEmpty()) {
			vocabNameEdit.setText(selectedDisplayName?.substringBeforeLast('.') ?: "")
		}
	}

	private fun handleSharedIntent(
		intent: Intent,
		fileText: TextView,
		vocabNameEdit: EditText,
	) {
		val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
		if (uri != null) {
			onFileSelected(uri)
			return
		}
		val text = intent.getStringExtra(Intent.EXTRA_TEXT)
		if (!text.isNullOrBlank()) {
			fileText.text = getString(R.string.label_shared_text)
			if (vocabNameEdit.text.isEmpty()) {
				vocabNameEdit.setText(getString(R.string.label_shared_vocabulary))
			}
			selectedUri = null
			sharedText = text
		}
	}

	private fun renderClassList(container: LinearLayout) {
		container.removeAllViews()
		val repo = SettingsRepository.get()
		val classes = ClassMetadataStore.load(repo).sortedBy { it.bitIndex }
		classes.forEach { item ->
			val label = "${item.bitIndex + 1}. ${item.name}"
			val tv = TextView(this)
			tv.text = label
			tv.setTextColor(getColor(android.R.color.secondary_text_dark))
			tv.textSize = 14f
			container.addView(tv)
		}
	}

	private fun showReplaceDialog(
		items: List<ClassMetadata>,
		onSelected: (Int) -> Unit,
	) {
		val candidates = items.filter { it.bitIndex >= 5 }.sortedBy { it.bitIndex }
		if (candidates.isEmpty()) {
			Toast.makeText(this, getString(R.string.toast_no_slot_to_replace), Toast.LENGTH_SHORT).show()
			return
		}
		val names = candidates.map { "${it.bitIndex + 1}. ${it.name}" }.toTypedArray()
		var selected = -1
		AlertDialog
			.Builder(this)
			.setTitle(getString(R.string.dialog_all_slots_used))
			.setSingleChoiceItems(names, -1) { _, which -> selected = which }
			.setPositiveButton(getString(R.string.dialog_replace_button)) { _, _ ->
				if (selected >= 0) {
					onSelected(candidates[selected].bitIndex)
				}
			}.setNegativeButton(getString(R.string.label_cancel), null)
			.show()
	}

	private fun showMergeChoiceDialog(
		selected: ClassMetadata,
		onDelete: () -> Unit,
		onMerge: () -> Unit,
	) {
		AlertDialog
			.Builder(this)
			.setTitle(getString(R.string.dialog_replace_vocab_title, selected.bitIndex + 1))
			.setMessage(getString(R.string.dialog_replace_vocab_message, selected.name))
			.setPositiveButton(getString(R.string.dialog_merge_button)) { _, _ -> onMerge() }
			.setNegativeButton(getString(R.string.dialog_delete_button)) { _, _ -> onDelete() }
			.setNeutralButton(getString(R.string.label_cancel), null)
			.show()
	}

	private fun showMergeTargetDialog(
		items: List<ClassMetadata>,
		sourceBit: Int,
		onSelected: (Int) -> Unit,
	) {
		val candidates = items.filter { it.bitIndex >= 5 && it.bitIndex != sourceBit }.sortedBy { it.bitIndex }
		if (candidates.isEmpty()) {
			Toast.makeText(this, getString(R.string.toast_no_merge_target), Toast.LENGTH_SHORT).show()
			return
		}
		val names = candidates.map { "${it.bitIndex + 1}. ${it.name}" }.toTypedArray()
		var selected = -1
		AlertDialog
			.Builder(this)
			.setTitle(getString(R.string.dialog_select_merge_target))
			.setSingleChoiceItems(names, -1) { _, which -> selected = which }
			.setPositiveButton(getString(R.string.dialog_merge_button)) { _, _ ->
				if (selected >= 0) {
					onSelected(candidates[selected].bitIndex)
				}
			}.setNegativeButton(getString(R.string.label_cancel), null)
			.show()
	}

	private fun mergeAndImport(
		uri: Uri?,
		inlineText: String?,
		name: String,
		sourceBit: Int,
		targetBit: Int,
		items: List<ClassMetadata>,
	) {
		if (sourceBit == targetBit) return
		val sourceMask = 1L shl sourceBit
		val targetMask = 1L shl targetBit
		WordDb.open(filesDir, assets).use { wordDb ->
			wordDb.beginTransaction()
			try {
				wordDb.mergeVocabularyMasks(sourceMask, targetMask)
				wordDb.setTransactionSuccessful()
			} finally {
				wordDb.endTransaction()
			}
		}
		val repo = SettingsRepository.get()
		val targetItem = items.firstOrNull { it.bitIndex == targetBit }
		val baseItems = ClassMetadataStore.remove(items, sourceBit)
		val updatedItems =
			if (targetItem != null) {
				ClassMetadataStore.upsert(
					baseItems,
					targetBit,
					targetItem.name,
					targetItem.source,
					targetItem.fileName,
					targetItem.wordCount,
				)
			} else {
				baseItems
			}
		val activeMask = repo.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
		repo.putLong(KEY_VOCAB_ACTIVE_MASK, activeMask and sourceMask.inv())
		performImport(uri, inlineText, name, sourceBit, updatedItems, sourceMask, targetMask)
	}

	private fun performImport(
		uri: Uri?,
		inlineText: String?,
		name: String,
		bit: Int,
		items: List<ClassMetadata>,
		mergeSourceMask: Long? = null,
		mergeTargetMask: Long? = null,
	) {
		val bitMask = 1L shl bit
		val removed = WordDb.open(filesDir, assets).use { wordDb ->
			val r = wordDb.deleteImportedWordsForClass(bitMask)
			wordDb.clearClassMask(bitMask)
			r
		}
		val repo = SettingsRepository.get()
		val updated = ClassMetadataStore.remove(items, bit)
		ClassMetadataStore.save(repo, updated)
		if (removed.isNotEmpty()) {
			Toast.makeText(this, getString(R.string.toast_removed_words, removed.size), Toast.LENGTH_SHORT).show()
		}
		if (uri != null) {
			importVocabulary(uri, name, bit)
		} else if (!inlineText.isNullOrBlank()) {
			importVocabularyText(inlineText, name, bit)
		}
		val importedCount = WordDb.open(filesDir, assets).use { wordDb ->
			val count = wordDb.countForClassMask(bitMask)
			val sample =
				wordDb.getWordsWithMask(bitMask).take(5).joinToString { entry ->
					"${entry.word}:${hexMask(entry.classMask)}"
				}
			DebugLogger.log(DebugCategory.WordDb) {
				"[VocabularyImportActivity] imported bit=${bit + 1} mask=${hexMask(bitMask)} count=$count sample=$sample"
			}
			count
		}
		val nextItems = ClassMetadataStore.load(repo)
		val finalItems =
			ClassMetadataStore.upsert(
				nextItems,
				bit,
				name,
				selectedDisplayName,
				selectedDisplayName,
				importedCount,
			)
		ClassMetadataStore.save(repo, finalItems)
		val activeMask = repo.getLong(KEY_VOCAB_ACTIVE_MASK, 0L)
		repo.putLong(KEY_VOCAB_ACTIVE_MASK, activeMask or bitMask)
		Toast.makeText(this, getString(R.string.toast_imported_vocab, name, bit + 1), Toast.LENGTH_LONG).show()
		val updateIntent = Intent(ACTION_VOCAB_UPDATED)
		if (mergeSourceMask != null && mergeTargetMask != null) {
			updateIntent.putExtra(EXTRA_VOCAB_MERGE_SOURCE_MASK, mergeSourceMask)
			updateIntent.putExtra(EXTRA_VOCAB_MERGE_TARGET_MASK, mergeTargetMask)
		}
		sendBroadcast(updateIntent)
		BackupManager.scheduleBackup(this)
		finish()
	}

	private fun hexMask(mask: Long): String = "0x" + mask.toString(16).uppercase(Locale.getDefault())

	private fun importVocabulary(
		uri: Uri,
		name: String,
		bit: Int,
	) {
		VocabImportHelper.importVocabulary(this, uri, bit)
	}

	private fun importVocabularyText(
		text: String,
		name: String,
		bit: Int,
	) {
		VocabImportHelper.importVocabularyText(this, text, bit)
	}

	private fun queryDisplayName(uri: Uri): String? = VocabImportHelper.queryDisplayName(this, uri)
}
