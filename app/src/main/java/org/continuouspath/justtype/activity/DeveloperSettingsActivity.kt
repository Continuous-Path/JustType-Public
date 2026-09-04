package org.continuouspath.justtype.activity

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import org.continuouspath.justtype.BuildConfig
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.Constants.KEY_DEBUG_LOG_CATEGORIES
import org.continuouspath.justtype.Constants.KEY_DEV_FORCE_PRE34_JOYSTICK
import org.continuouspath.justtype.Constants.KEY_DEV_LANGPACK_MANIFEST_URL
import org.continuouspath.justtype.Constants.KEY_DEV_SCROLL_LOGS
import org.continuouspath.justtype.Constants.KEY_DEV_SWITCH_INPUT_LOGS
import org.continuouspath.justtype.Constants.KEY_ENABLE_DEBUG_LOG
import org.continuouspath.justtype.Constants.KEY_ENTER_EXTRA_BLANK_LINE
import org.continuouspath.justtype.Constants.KEY_EXACT_CASE_PULL_IN
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DEBUG_OVERLAY
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_DIAG_LOGS
import org.continuouspath.justtype.Constants.KEY_HEADTRACKING_REARM_IN_FEEDBACK
import org.continuouspath.justtype.Constants.KEY_SHOW_EDIT_OPS
import org.continuouspath.justtype.Constants.PREFS_KEY_LANGPACK_MANIFEST_CACHE
import org.continuouspath.justtype.LocaleHelper
import org.continuouspath.justtype.R
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.logic.WordDb
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import java.io.File
import java.util.Locale

class DeveloperSettingsActivity : AppCompatActivity() {
	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(LocaleHelper.wrap(newBase))
	}

	companion object {
		const val KEY_FREQ_ADD = "freq_add_weight"
		const val KEY_FREQ_MULT = "freq_mult_weight"
		const val KEY_SEQ_ADD = "seq_add_weight"
		const val KEY_SEQ_MULT = "seq_mult_weight"
		const val KEY_USE_ADD = "use_add_weight"
		const val KEY_USE_MULT = "use_mult_weight"
		const val KEY_REC_ADD = "recency_add_weight"
		const val KEY_REC_MULT = "recency_mult_weight"
		const val KEY_SHOW_SORT_METRIC = "show_sort_metric"
		const val KEY_SLS_PARTITION = "sls_partition_policy"
		const val KEY_SLS_TONE_PROMOTED = "sls_tone_promoted"
		const val KEY_SELECT_BEHAVIOR_MODE = "select_behavior_mode"
		const val KEY_NGB_CONF_THETA_ADAPTIVE = "ngb_conf_theta_adaptive"
		const val KEY_SORT_METRIC_VERBOSITY = "sort_metric_verbosity"
		const val KEY_SEARCH_BSD = "search_bsd"
		const val KEY_SEARCH_SED = "search_sed"
		const val KEY_SEARCH_MQC = "search_mqc"
		const val KEY_SEARCH_MEN = "search_men"
		const val KEY_SEARCH_IGNORE_MEN = "search_ignore_men"
		const val KEY_CASE_SWITCH_MARGIN = "case_switch_margin"

		// Family expansion dials (sls.md "family expansion"); the enable switch
		// and delay are user-facing (Constants, Language Options page).
		const val KEY_FAMILY_EXPAND_MIN_KR = "family_expand_min_kr"
		const val KEY_FAMILY_EXPAND_STEM_BACKOFF = "family_expand_stem_backoff"
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_developer_settings)

		val backButton: ImageButton? = findViewById(R.id.backButton)
		backButton?.setOnClickListener { finish() }

		val reinitBtn: Button = findViewById(R.id.reinitDbButton)
		val saveBtn: Button = findViewById(R.id.saveDbButton)
		val loadBtn: Button = findViewById(R.id.loadDbButton)
		val nameEdit: EditText = findViewById(R.id.dbNameEdit)

		// Language-pack manifest URL override (empty = BuildConfig default). Clears the cached
		// manifest on change so the Languages screen re-fetches from the new source.
		val manifestUrlEdit: EditText = findViewById(R.id.langpackManifestUrlEdit)
		val manifestUrlApply: Button = findViewById(R.id.langpackManifestUrlApply)
		val repoForUrl = SettingsRepository.getInstance(this)
		manifestUrlEdit.setText(repoForUrl.getString(KEY_DEV_LANGPACK_MANIFEST_URL, ""))
		manifestUrlApply.setOnClickListener {
			repoForUrl.putString(KEY_DEV_LANGPACK_MANIFEST_URL, manifestUrlEdit.text.toString().trim())
			repoForUrl.putString(PREFS_KEY_LANGPACK_MANIFEST_CACHE, "")
			Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
		}

		val statsFile = File(filesDir, "EnglishDbActive.db")
		val exportDir = File(filesDir, "worddb").apply { mkdirs() }

		reinitBtn.setOnClickListener {
			try {
				val ok = WordDb.resetToDefaults(filesDir, assets)
				if (ok) {
					Toast.makeText(this, getString(R.string.toast_db_reset_success), Toast.LENGTH_SHORT).show()
				} else {
					Toast.makeText(this, getString(R.string.toast_reset_failed, "copy failed"), Toast.LENGTH_LONG).show()
				}
			} catch (e: Exception) {
				Toast.makeText(this, getString(R.string.toast_reset_failed, e.message), Toast.LENGTH_LONG).show()
			}
		}

		saveBtn.setOnClickListener {
			val name = nameEdit.text.toString().trim()
			if (name.isEmpty()) {
				Toast.makeText(this, getString(R.string.toast_enter_name), Toast.LENGTH_SHORT).show()
				return@setOnClickListener
			}
			try {
				if (!statsFile.exists()) {
					Toast.makeText(this, getString(R.string.toast_no_database_to_save), Toast.LENGTH_SHORT).show()
					return@setOnClickListener
				}
				statsFile.copyTo(File(exportDir, "$name.db"), overwrite = true)
				Toast.makeText(this, getString(R.string.toast_saved_as_db, name), Toast.LENGTH_SHORT).show()
			} catch (e: Exception) {
				Toast.makeText(this, getString(R.string.toast_save_failed, e.message), Toast.LENGTH_LONG).show()
			}
		}

		loadBtn.setOnClickListener {
			val name = nameEdit.text.toString().trim()
			if (name.isEmpty()) {
				Toast.makeText(this, getString(R.string.toast_enter_name), Toast.LENGTH_SHORT).show()
				return@setOnClickListener
			}
			try {
				val src = File(exportDir, "$name.db")
				if (!src.exists()) {
					Toast.makeText(this, getString(R.string.toast_no_file_named, name), Toast.LENGTH_SHORT).show()
					return@setOnClickListener
				}
				src.copyTo(statsFile, overwrite = true)
				Toast.makeText(this, getString(R.string.toast_loaded_db, name), Toast.LENGTH_LONG).show()
			} catch (e: Exception) {
				Toast.makeText(this, getString(R.string.toast_load_failed, e.message), Toast.LENGTH_LONG).show()
			}
		}

		// Weights and toggles
		val prefs = SettingsRepository.getInstance(this)
		val showSortSwitch: SwitchCompat = findViewById(R.id.showSortMetricSwitch)
		showSortSwitch.isChecked = prefs.getBoolean(KEY_SHOW_SORT_METRIC, false)
		showSortSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SHOW_SORT_METRIC, isChecked)
		}
		// Detail tier applied while the switch is ON (Cliff's SLS observability
		// spec, 2026-08-10): 1 source+impact tag, 2 +score, 3 +raw values,
		// 4 full component metrics + search timing (the legacy display).
		setupIntSlider(
			seek = findViewById(R.id.seekSortMetricVerbosity),
			value = findViewById(R.id.valSortMetricVerbosity),
			min = 1,
			max = 4,
			def = 1,
			key = KEY_SORT_METRIC_VERBOSITY,
			prefs = prefs,
		)
		// SLS partition policy: value labels instead of numbers; persists live
		// (the assembly path reads it per keystroke, same as the weight sliders).
		val partitionLabels = arrayOf(
			getString(R.string.sr_developer_sls_partition_blocks),
			getString(R.string.sr_developer_sls_partition_pin),
			getString(R.string.sr_developer_sls_partition_interleave),
		)
		val seekPartition: SeekBar = findViewById(R.id.seekSlsPartition)
		val valPartition: TextView = findViewById(R.id.valSlsPartition)
		seekPartition.max = 2
		val savedPartition = prefs.getInt(KEY_SLS_PARTITION, 2).coerceIn(0, 2)
		seekPartition.progress = savedPartition
		valPartition.text = partitionLabels[savedPartition]
		seekPartition.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
					valPartition.text = partitionLabels[p.coerceIn(0, 2)]
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					prefs.putInt(KEY_SLS_PARTITION, (seekBar?.progress ?: 0).coerceIn(0, 2))
				}
			},
		)
		val tonePromotedSwitch: SwitchCompat = findViewById(R.id.slsTonePromotedSwitch)
		tonePromotedSwitch.isChecked = prefs.getBoolean(KEY_SLS_TONE_PROMOTED, false)
		tonePromotedSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SLS_TONE_PROMOTED, isChecked)
		}
		// Select-behavior force-enable ladder (sls.md "Adaptive select-behavior
		// mechanisms"): Observe records episodes only; the force modes make the
		// FTS promotion feelable now; Adaptive is reserved until the ramp ships.
		val behaviorLabels = arrayOf(
			getString(R.string.sr_developer_select_behavior_observe),
			getString(R.string.sr_developer_select_behavior_force_page1),
			getString(R.string.sr_developer_select_behavior_force_head),
			getString(R.string.sr_developer_select_behavior_adaptive),
		)
		val seekBehavior: SeekBar = findViewById(R.id.seekSelectBehavior)
		val valBehavior: TextView = findViewById(R.id.valSelectBehavior)
		seekBehavior.max = 3
		val savedBehavior = prefs.getInt(KEY_SELECT_BEHAVIOR_MODE, 0).coerceIn(0, 3)
		seekBehavior.progress = savedBehavior
		valBehavior.text = behaviorLabels[savedBehavior]
		seekBehavior.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
					valBehavior.text = behaviorLabels[p.coerceIn(0, 3)]
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					prefs.putInt(KEY_SELECT_BEHAVIOR_MODE, (seekBar?.progress ?: 0).coerceIn(0, 3))
				}
			},
		)
		val statsBtn: Button = findViewById(R.id.btnSelectBehaviorStats)
		statsBtn.setOnClickListener { showSelectBehaviorStats() }
		val shareStatsBtn: Button = findViewById(R.id.btnShareSelectBehaviorStats)
		shareStatsBtn.setOnClickListener { shareSelectBehaviorStats() }
		// Mechanism B (sls.md): adaptive theta_signal walks the raw threshold
		// to the user's precision-target equilibrium; OFF pins it to the
		// slider below (which also seeds a fresh language's starting theta).
		val thetaAdaptiveSwitch: SwitchCompat = findViewById(R.id.ngbConfThetaAdaptiveSwitch)
		thetaAdaptiveSwitch.isChecked = prefs.getBoolean(KEY_NGB_CONF_THETA_ADAPTIVE, true)
		thetaAdaptiveSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_NGB_CONF_THETA_ADAPTIVE, isChecked)
		}
		setupIntSlider(
			seek = findViewById(R.id.seekNgbConfThreshold),
			value = findViewById(R.id.valNgbConfThreshold),
			min = 20,
			max = 95,
			def = 65,
			key = Constants.KEY_NGB_CONFIDENCE_THRESHOLD,
			prefs = prefs,
		)
		setupIntSlider(
			seek = findViewById(R.id.seekSearchBsd),
			value = findViewById(R.id.valSearchBsd),
			min = 3,
			max = 10,
			def = 8,
			key = KEY_SEARCH_BSD,
			prefs = prefs,
		)
		setupIntSlider(
			seek = findViewById(R.id.seekSearchSed),
			value = findViewById(R.id.valSearchSed),
			min = 0,
			max = 10,
			def = 7,
			key = KEY_SEARCH_SED,
			prefs = prefs,
		)
		setupIntSlider(
			seek = findViewById(R.id.seekSearchMqc),
			value = findViewById(R.id.valSearchMqc),
			min = 5,
			max = 1000,
			def = 100,
			key = KEY_SEARCH_MQC,
			prefs = prefs,
		)
		setupIntSlider(
			seek = findViewById(R.id.seekSearchMen),
			value = findViewById(R.id.valSearchMen),
			min = 50,
			max = 10000,
			def = 5000,
			key = KEY_SEARCH_MEN,
			prefs = prefs,
		)
		val ignoreMenSwitch: SwitchCompat =
			findViewById(R.id.ignoreMenSwitch)
		ignoreMenSwitch.isChecked = prefs.getBoolean(KEY_SEARCH_IGNORE_MEN, false)
		ignoreMenSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SEARCH_IGNORE_MEN, isChecked)
		}
		setupSlider(
			seek = findViewById(R.id.seekFreqAdd),
			value = findViewById(R.id.valFreqAdd),
			min = 0.0f,
			max = 2.0f,
			def = 1.0f,
			key = KEY_FREQ_ADD,
			prefs = prefs,
		)
		setupSlider(
			seek = findViewById(R.id.seekFreqMult),
			value = findViewById(R.id.valFreqMult),
			min = 1.0f,
			max = 2.0f,
			def = 1.25f,
			key = KEY_FREQ_MULT,
			prefs = prefs,
		)
		setupSlider(
			seek = findViewById(R.id.seekSeqAdd),
			value = findViewById(R.id.valSeqAdd),
			min = 0.0f,
			max = 50.0f,
			def = 1.0f,
			key = KEY_SEQ_ADD,
			prefs = prefs,
		)
		setupSlider(
			seek = findViewById(R.id.seekSeqMult),
			value = findViewById(R.id.valSeqMult),
			min = 1.0f,
			max = 8.0f,
			def = 1.0f,
			key = KEY_SEQ_MULT,
			prefs = prefs,
		)
		setupSlider(
			seek = findViewById(R.id.seekUseAdd),
			value = findViewById(R.id.valUseAdd),
			min = 0.0f,
			max = 6.0f,
			def = 3.0f,
			key = KEY_USE_ADD,
			prefs = prefs,
		)
		setupSlider(
			seek = findViewById(R.id.seekUseMult),
			value = findViewById(R.id.valUseMult),
			min = 1.0f,
			max = 2.0f,
			def = 1.15f,
			key = KEY_USE_MULT,
			prefs = prefs,
		)
		setupSlider(
			seek = findViewById(R.id.seekRecencyAdd),
			value = findViewById(R.id.valRecencyAdd),
			min = 0.0f,
			max = 4.0f,
			def = 2.0f,
			key = KEY_REC_ADD,
			prefs = prefs,
		)
		setupSlider(
			seek = findViewById(R.id.seekRecencyMult),
			value = findViewById(R.id.valRecencyMult),
			min = 1.0f,
			max = 2.0f,
			def = 1.75f,
			key = KEY_REC_MULT,
			prefs = prefs,
		)
		setupIntSlider(
			seek = findViewById(R.id.seekCaseSwitchMargin),
			value = findViewById(R.id.valCaseSwitchMargin),
			min = 1,
			max = 8,
			def = 2,
			key = KEY_CASE_SWITCH_MARGIN,
			prefs = prefs,
		)

		// Family expansion dials (enable + delay live on Language Options).
		setupIntSlider(
			seek = findViewById(R.id.seekFamilyMinKr),
			value = findViewById(R.id.valFamilyMinKr),
			min = 2,
			max = 8,
			def = 5,
			key = KEY_FAMILY_EXPAND_MIN_KR,
			prefs = prefs,
		)
		setupIntSlider(
			seek = findViewById(R.id.seekFamilyStemBackoff),
			value = findViewById(R.id.valFamilyStemBackoff),
			min = 0,
			max = 8,
			def = 5,
			key = KEY_FAMILY_EXPAND_STEM_BACKOFF,
			prefs = prefs,
		)

		// IME Debug / Timing
		val showEditOpsSwitch: SwitchCompat = findViewById(R.id.switchShowEditOps)
		showEditOpsSwitch.isChecked = prefs.getBoolean(KEY_SHOW_EDIT_OPS)
		showEditOpsSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_SHOW_EDIT_OPS, isChecked)
		}

		val enableDebugLogSwitch: SwitchCompat = findViewById(R.id.switchEnableDebugLog)
		enableDebugLogSwitch.isChecked = prefs.getBoolean(KEY_ENABLE_DEBUG_LOG)
		enableDebugLogSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_ENABLE_DEBUG_LOG, isChecked)
		}

		val enterExtraBlankLineSwitch: SwitchCompat = findViewById(R.id.switchEnterExtraBlankLine)
		enterExtraBlankLineSwitch.isChecked = prefs.getBoolean(KEY_ENTER_EXTRA_BLANK_LINE)
		enterExtraBlankLineSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_ENTER_EXTRA_BLANK_LINE, isChecked)
		}

		val exactCasePullInSwitch: SwitchCompat = findViewById(R.id.switchExactCasePullIn)
		exactCasePullInSwitch.isChecked = prefs.getBoolean(KEY_EXACT_CASE_PULL_IN)
		exactCasePullInSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_EXACT_CASE_PULL_IN, isChecked)
		}

		val headTrackingDebugOverlaySwitch: SwitchCompat = findViewById(R.id.switchHeadTrackingDebugOverlay)
		headTrackingDebugOverlaySwitch.isChecked = prefs.getBoolean(KEY_HEADTRACKING_DEBUG_OVERLAY)
		headTrackingDebugOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_HEADTRACKING_DEBUG_OVERLAY, isChecked)
		}

		val forcePre34JoystickSwitch: SwitchCompat = findViewById(R.id.switchForcePre34Joystick)
		forcePre34JoystickSwitch.isChecked = prefs.getBoolean(KEY_DEV_FORCE_PRE34_JOYSTICK, false)
		forcePre34JoystickSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_DEV_FORCE_PRE34_JOYSTICK, isChecked)
		}

		val switchInputLogsSwitch: SwitchCompat = findViewById(R.id.switchSwitchInputLogs)
		switchInputLogsSwitch.isChecked = prefs.getBoolean(KEY_DEV_SWITCH_INPUT_LOGS, false)
		switchInputLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_DEV_SWITCH_INPUT_LOGS, isChecked)
		}

		val scrollLogsSwitch: SwitchCompat = findViewById(R.id.switchScrollLogs)
		scrollLogsSwitch.isChecked = prefs.getBoolean(KEY_DEV_SCROLL_LOGS, BuildConfig.DEBUG)
		scrollLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_DEV_SCROLL_LOGS, isChecked)
		}

		val headTrackingDiagLogsSwitch: SwitchCompat = findViewById(R.id.switchHeadTrackingDiagLogs)
		headTrackingDiagLogsSwitch.isChecked = prefs.getBoolean(KEY_HEADTRACKING_DIAG_LOGS)
		headTrackingDiagLogsSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_HEADTRACKING_DIAG_LOGS, isChecked)
		}

		val headTrackingRearmSwitch: SwitchCompat = findViewById(R.id.switchHeadTrackingRearmInFeedback)
		headTrackingRearmSwitch.isChecked = prefs.getBoolean(KEY_HEADTRACKING_REARM_IN_FEEDBACK)
		headTrackingRearmSwitch.setOnCheckedChangeListener { _, isChecked ->
			prefs.putBoolean(KEY_HEADTRACKING_REARM_IN_FEEDBACK, isChecked)
		}

		val categoryCheckboxes =
			mapOf(
				DebugCategory.Lifecycle to findViewById<CheckBox>(R.id.cbCategoryLifecycle),
				DebugCategory.UndoFlow to findViewById(R.id.cbCategoryUndo),
				DebugCategory.PullInFlow to findViewById(R.id.cbCategoryPullIn),
				DebugCategory.AmbigBuffer to findViewById(R.id.cbCategoryAmbig),
				DebugCategory.SelectionSync to findViewById(R.id.cbCategorySelection),
				DebugCategory.InputConnection to findViewById(R.id.cbCategoryInput),
				DebugCategory.WordDb to findViewById(R.id.cbCategoryWordDb),
				DebugCategory.ShiftState to findViewById(R.id.cbCategoryShift),
				DebugCategory.TextEditing to findViewById(R.id.cbCategoryTextEdit),
			)

		fun persistCategorySelection() {
			val selected =
				categoryCheckboxes
					.mapNotNull { (category, checkBox) ->
						if (checkBox?.isChecked == true) category else null
					}.toSet()
			val values = HashSet(DebugCategory.toPrefValues(selected))
			prefs.putStringSet(KEY_DEBUG_LOG_CATEGORIES, values)
			DebugLogger.setEnabledCategories(selected)
		}

		val savedCategories =
			DebugCategory.fromPrefValues(prefs.getStringSet(KEY_DEBUG_LOG_CATEGORIES, null)?.toSet())
		categoryCheckboxes.forEach { (category, checkBox) ->
			checkBox?.isChecked = savedCategories.contains(category)
			checkBox?.setOnCheckedChangeListener { _, _ -> persistCategorySelection() }
		}

		// Debug log buttons
		findViewById<Button>(R.id.btnShowLogPath)?.setOnClickListener {
			val f = File(filesDir, "debug.log")
			Toast.makeText(this, getString(R.string.toast_debug_log_path, f.absolutePath), Toast.LENGTH_LONG).show()
		}
		findViewById<Button>(R.id.btnClearLog)?.setOnClickListener {
			try {
				val f = File(filesDir, "debug.log")
				if (f.exists()) f.writeText("")
				Toast.makeText(this, getString(R.string.toast_debug_log_cleared), Toast.LENGTH_SHORT).show()
			} catch (e: Exception) {
				Toast.makeText(this, getString(R.string.toast_failed_to_clear, e.message), Toast.LENGTH_LONG).show()
			}
		}

		findViewById<Button>(R.id.btnToggleLogging)?.setOnClickListener {
			val cur = prefs.getBoolean(KEY_ENABLE_DEBUG_LOG)
			val next = !cur
			prefs.putBoolean(KEY_ENABLE_DEBUG_LOG, next)
			Toast
				.makeText(
					this,
					if (next) getString(R.string.toast_debug_logging_enabled) else getString(R.string.toast_debug_logging_disabled),
					Toast.LENGTH_SHORT,
				).show()
			try {
				val sw: SwitchCompat? = findViewById(R.id.switchEnableDebugLog)
				sw?.isChecked = next
			} catch (_: Exception) {
			}
		}
	}

	/** Dev readout of the select-behavior substrate: the EWMA episode
	 *  distribution per language, grouped by (signal x demoted-FTS) state.
	 *  Reads CustomDb directly — re-reads on every press, no caching. */
	/** One-tap sel_stats distribution share (sls.md staging replan): plain
	 *  text, no file — the counters are tiny and content-safe. The same TSV
	 *  also rides every Submit-Feedback log zip for beta testers. */
	private fun shareSelectBehaviorStats() {
		val tsv = org.continuouspath.justtype.logging.SelStatsExport.dumpTsv(this)
		if (tsv == null) {
			Toast.makeText(this, R.string.dev_select_behavior_stats_empty, Toast.LENGTH_LONG).show()
			return
		}
		val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.dev_share_select_behavior_stats_subject))
			putExtra(android.content.Intent.EXTRA_TEXT, tsv)
		}
		startActivity(
			android.content.Intent.createChooser(send, getString(R.string.dev_share_select_behavior_stats_subject)),
		)
	}

	private fun showSelectBehaviorStats() {
		val rows = runCatching {
			WordDb.openStandalone(File(filesDir, "CustomDb.db")).use { it.selStatsDump() }
		}.getOrElse { e ->
			Toast.makeText(this, getString(R.string.toast_load_failed, e.message), Toast.LENGTH_LONG).show()
			return
		}
		val text = if (rows.isEmpty()) {
			getString(R.string.dev_select_behavior_stats_empty)
		} else {
			buildString {
				rows.groupBy { it.first }.forEach { (lang, langRows) ->
					append(lang.ifEmpty { "?" }).append(" — ")
					append(String.format(Locale.US, "%.1f", langRows.sumOf { it.third }))
					append(" weighted episodes\n")
					langRows.groupBy { it.second.substringBefore(":") }.forEach { (stateKey, group) ->
						val total = group.sumOf { it.third }
						append("  ").append(stateKey).append(" (")
						append(String.format(Locale.US, "%.1f", total)).append("):\n")
						group.sortedByDescending { it.third }.forEach { (_, bucket, w) ->
							val pct = if (total > 0) (100.0 * w / total) else 0.0
							append("    ").append(bucket.substringAfter(":"))
							append(String.format(Locale.US, "  %.2f (%.0f%%)\n", w, pct))
						}
					}
				}
			}
		}
		androidx.appcompat.app.AlertDialog.Builder(this)
			.setTitle(R.string.dev_select_behavior_stats)
			.setMessage(text)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private fun setupSlider(
		seek: SeekBar,
		value: TextView,
		min: Float,
		max: Float,
		def: Float,
		key: String,
		prefs: SettingsRepository,
	) {
		val scale = 100
		val maxProgress = ((max - min) * scale).toInt()
		seek.max = maxProgress
		val saved = prefs.getFloat(key, def)
		val clamped = saved.coerceIn(min, max)
		val progress = ((clamped - min) * scale).toInt()
		seek.progress = progress
		value.text = String.format(Locale.getDefault(), "%.2f", clamped)
		seek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					p: Int,
					fromUser: Boolean,
				) {
					val v = min + (p.toFloat() / scale)
					value.text = String.format(Locale.getDefault(), "%.2f", v)
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val v = min + (seekBar?.progress?.toFloat() ?: 0f) / scale
					prefs.putFloat(key, v)
				}
			},
		)
	}

	private fun setupIntSlider(
		seek: SeekBar,
		value: TextView,
		min: Int,
		max: Int,
		def: Int,
		key: String,
		prefs: SettingsRepository,
	) {
		seek.max = max - min
		val saved = prefs.getInt(key, def).coerceIn(min, max)
		seek.progress = saved - min
		value.text = saved.toString()
		seek.setOnSeekBarChangeListener(
			object : SeekBar.OnSeekBarChangeListener {
				override fun onProgressChanged(
					seekBar: SeekBar?,
					p: Int,
					fromUser: Boolean,
				) {
					val v = min + p
					value.text = v.toString()
				}

				override fun onStartTrackingTouch(seekBar: SeekBar?) {}

				override fun onStopTrackingTouch(seekBar: SeekBar?) {
					val v = min + (seekBar?.progress ?: 0)
					prefs.putInt(key, v)
				}
			},
		)
	}
}
