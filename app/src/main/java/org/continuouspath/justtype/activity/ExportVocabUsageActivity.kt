package org.continuouspath.justtype.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.Constants.KEY_VOCAB_FREQ_FILTER_ENABLED
import org.continuouspath.justtype.Constants.KEY_VOCAB_MIN_FREQ_SELECTION
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.data.PhraseRepository
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import org.continuouspath.justtype.settings.getInt
import org.json.JSONArray
import java.io.File
import java.util.Date
import kotlin.math.ceil

class ExportVocabUsageActivity : AppCompatActivity() {

	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	companion object {
		const val EXTRA_MODULES = "extra_modules"
		const val EXTRA_MIN_USE = "extra_min_use"
		const val EXTRA_MAX_USE = "extra_max_use"
	}

	private lateinit var chooseFolderButton: Button
	private lateinit var exportNowButton: Button
	private lateinit var exportFolderStatus: TextView
	private lateinit var exportFileNameEdit: EditText
	private lateinit var exportStatusText: TextView

	private var exportFolderUri: Uri? = null
	private var modules: List<ExportModule> = emptyList()
	private var minUseCount: Int? = null
	private var maxUseCount: Int? = null

	private val pickFolderLauncher =
		registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
			if (uri == null) return@registerForActivityResult
			val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
				Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			contentResolver.takePersistableUriPermission(uri, flags)
			exportFolderUri = uri
			updateUi()
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_export_vocab_usage)

		findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

		chooseFolderButton = findViewById(R.id.chooseExportFolderButton)
		exportNowButton = findViewById(R.id.exportNowButton)
		exportFolderStatus = findViewById(R.id.exportFolderStatus)
		exportFileNameEdit = findViewById(R.id.exportFileNameEdit)
		exportStatusText = findViewById(R.id.exportStatusText)

		chooseFolderButton.setOnClickListener { pickFolderLauncher.launch(null) }
		exportNowButton.setOnClickListener { runExport() }

		loadExtras()
		exportFileNameEdit.setText(buildDefaultFileName())
		updateUi()
	}

	private fun loadExtras() {
		val raw = intent.getStringExtra(EXTRA_MODULES)
		if (!raw.isNullOrBlank()) {
			val arr = JSONArray(raw)
			val list = mutableListOf<ExportModule>()
			for (i in 0 until arr.length()) {
				val obj = arr.optJSONObject(i) ?: continue
				list.add(
					ExportModule(
						name = obj.optString("name"),
						mask = obj.optLong("mask"),
						type = ExportModuleType.valueOf(obj.optString("type")),
					),
				)
			}
			modules = list
		}
		val minRaw = intent.getIntExtra(EXTRA_MIN_USE, -1)
		minUseCount = if (minRaw >= 0) minRaw else null
		val maxRaw = intent.getIntExtra(EXTRA_MAX_USE, -1)
		maxUseCount = if (maxRaw >= 0) maxRaw else null
	}

	private fun buildDefaultFileName(): String {
		val timestamp = DateFormat.format("yyyy-MM-dd_HH-mm-ss", Date())
		return if (modules.size == 1) {
			val safeName = modules.first().name
				.replace(Regex("[^A-Za-z0-9_]+"), "_")
				.trim('_')
			"${safeName}_$timestamp.txt"
		} else {
			"JustType_Vocabularies_$timestamp.txt"
		}
	}

	private fun updateUi() {
		exportFolderStatus.text = exportFolderUri?.toString() ?: getString(R.string.no_folder_selected)
		exportNowButton.isEnabled = exportFolderUri != null
	}

	private fun runExport() {
		val folderUri = exportFolderUri
		if (folderUri == null) {
			Toast.makeText(this, getString(R.string.toast_choose_export_folder), Toast.LENGTH_SHORT).show()
			return
		}
		val fileName = exportFileNameEdit.text.toString().trim().ifEmpty { buildDefaultFileName() }
		exportNowButton.isEnabled = false
		lifecycleScope.launch(Dispatchers.IO) {
			val ok = writeExport(folderUri, fileName)
			withContext(Dispatchers.Main) {
				exportNowButton.isEnabled = true
				if (ok) {
					Toast.makeText(this@ExportVocabUsageActivity, getString(R.string.toast_export_completed), Toast.LENGTH_SHORT).show()
				} else {
					Toast.makeText(this@ExportVocabUsageActivity, getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
				}
				exportStatusText.text = ""
			}
		}
	}

	private fun writeExport(treeUri: Uri, fileName: String): Boolean {
		val root = DocumentFile.fromTreeUri(this, treeUri) ?: return false
		root.findFile(fileName)?.delete()
		val file = root.createFile("text/plain", fileName) ?: return false
		val phraseRepository = PhraseRepository(File(filesDir, "phrases.json"))
		val sb = StringBuilder()
		val repo = SettingsRepository.getInstance(this)
		val justTypeFilters = buildJustTypeExportFilters(repo)
		val maxUseCountSnapshot = maxUseCount
		val orderedModules = modules
		WordDb.open(filesDir, assets).use { wordDb ->
			orderedModules.forEachIndexed { index, module ->
				if (module.type == ExportModuleType.WORDS && module.mask == ClassMasks.CLASS_JUSTTYPE_MASK) {
					val entries = wordDb.getWordsForMaskUseCountAndFreqRange(
						mask = module.mask,
						minUseCount = minUseCount,
						maxUseCount = maxUseCountSnapshot,
						minFreqClass = justTypeFilters.minFreqClass,
						maxFreqClass = justTypeFilters.maxFreqClass,
					)
					val byClass = entries.groupBy({ it.third }, { it.first to it.second })
					val classKeys = byClass.keys.sorted()
					if (classKeys.size <= 1) {
						sb.append(getString(R.string.export_header_usage_counts, module.name))
						sb.append("\n")
						val singleEntries = byClass.values.firstOrNull().orEmpty()
						appendUsageGroups(sb, singleEntries)
					} else {
						classKeys.forEachIndexed { classIndex, freqClass ->
							sb.append(getString(R.string.export_header_freq_class, freqClass))
							sb.append("\n")
							appendUsageGroups(sb, byClass[freqClass].orEmpty())
							if (classIndex < classKeys.lastIndex) {
								repeat(2) { sb.append("\n") }
							}
						}
					}
				} else {
					sb.append(getString(R.string.export_header_usage_counts, module.name))
					sb.append("\n")
					val entries: List<Pair<String, Int>> = when (module.type) {
						ExportModuleType.WORDS -> wordDb.getWordsForMaskUseCountRange(
							module.mask,
							minUseCount,
							maxUseCount,
						)
						ExportModuleType.PHRASES -> {
							val items = phraseRepository.all().map { it.abbreviation to 0 }
							items.filter { passesUseCount(it.second) }
						}
					}
					appendUsageGroups(sb, entries)
				}
				if (index < orderedModules.lastIndex) {
					repeat(3) { sb.append("\n") }
				}
			}
		}
		return runCatching {
			contentResolver.openOutputStream(file.uri)?.use { out ->
				out.write(sb.toString().toByteArray())
			}
			true
		}.getOrDefault(false)
	}

	private fun passesUseCount(count: Int): Boolean {
		if (minUseCount != null && count < minUseCount!!) return false
		if (maxUseCount != null && count > maxUseCount!!) return false
		return true
	}

	private fun appendUsageGroups(sb: StringBuilder, entries: List<Pair<String, Int>>) {
		val grouped = entries.groupBy({ it.second }, { it.first })
		val maxWidth = entries.maxOfOrNull { estimateWordWidth(it.first) } ?: 0.0
		grouped.keys.sorted().forEach { count ->
			sb.append("                                   ${getString(R.string.export_header_words_spelled, count)}")
			val words = grouped[count].orEmpty().sorted()
			formatWordsInColumns(words, columns = 4, maxWidth = maxWidth).forEach { line ->
				sb.append(line).append("\n")
			}
			sb.append("\n")
		}
	}

	private fun buildJustTypeExportFilters(
		repo: SettingsRepository,
	): JustTypeExportFilters {
		val filterEnabled = repo.getBoolean(KEY_VOCAB_FREQ_FILTER_ENABLED)
		val selectionSlider = repo.getInt(KEY_VOCAB_MIN_FREQ_SELECTION).coerceIn(1, 14)
		val minFreqClass = if (filterEnabled && selectionSlider > 1) (15 - selectionSlider) else null
		return JustTypeExportFilters(minFreqClass, null)
	}

	private fun formatWordsInColumns(words: List<String>, columns: Int, maxWidth: Double): List<String> {
		if (words.isEmpty()) return emptyList()
		val rows = (words.size + columns - 1) / columns
		val colWords = Array(columns) { mutableListOf<String>() }
		words.forEachIndexed { index, word ->
			val col = index / rows
			if (col < columns) {
				colWords[col].add(word)
			}
		}
		val lines = mutableListOf<String>()
		for (row in 0 until rows) {
			val parts = mutableListOf<String>()
			for (col in 0 until columns) {
				val word = colWords[col].getOrNull(row).orEmpty()
				val wordWidth = estimateWordWidth(word)
				val gapWidth = (maxWidth - wordWidth).coerceAtLeast(0.0)
				val spaces = ceil(gapWidth / SPACE_WIDTH).toInt()
				if (spaces <= 0) {
					parts.add(word + "\t")
				} else {
					val adjusted = (spaces - 1).coerceAtLeast(0)
					parts.add(word + " ".repeat(adjusted) + "\t" + "\t")
				}
			}
			lines.add(parts.joinToString("").trimEnd())
		}
		return lines
	}

	private fun estimateWordWidth(word: String): Double {
		var width = 0.0
		for (ch in word) {
			width += CHAR_WIDTHS[ch] ?: DEFAULT_CHAR_WIDTH
		}
		return width
	}

	private val DEFAULT_CHAR_WIDTH = 0.6
	private val SPACE_WIDTH = 0.5
	private val CHAR_WIDTHS = mapOf(
		'i' to 0.35, 'l' to 0.35, 'I' to 0.4, 'j' to 0.4, 't' to 0.45,
		'f' to 0.45, 'r' to 0.45,
		' ' to SPACE_WIDTH,
		'm' to 0.9, 'w' to 0.9, 'M' to 0.95, 'W' to 0.95,
		'A' to 0.75, 'B' to 0.7, 'C' to 0.75, 'D' to 0.75, 'E' to 0.7,
		'F' to 0.7, 'G' to 0.8, 'H' to 0.75, 'J' to 0.6, 'K' to 0.75,
		'L' to 0.65, 'N' to 0.8, 'O' to 0.8, 'P' to 0.7, 'Q' to 0.8,
		'R' to 0.75, 'S' to 0.7, 'T' to 0.7, 'U' to 0.8, 'V' to 0.75,
		'X' to 0.75, 'Y' to 0.75, 'Z' to 0.7,
	)

	private data class ExportModule(
		val name: String,
		val mask: Long,
		val type: ExportModuleType,
	)

	private data class JustTypeExportFilters(
		val minFreqClass: Int?,
		val maxFreqClass: Int?,
	)

	private enum class ExportModuleType { WORDS, PHRASES }
}
