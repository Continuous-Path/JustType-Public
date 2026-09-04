package org.continuouspath.justtype.logging

import android.content.Context
import org.continuouspath.justtype.logic.WordDb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Machine-readable export of the select-behavior counters (sls.md "Adaptive
 * select-behavior mechanisms", staging replan): beta testers send their
 * sel_stats distributions with one tap so mechanism A's detection thresholds
 * can be set from real field data.
 *
 * Content-safe by construction: the counters are (state, outcome) EWMA
 * weights — no typed text ever appears in them.
 */
object SelStatsExport {

	const val FILE_NAME = "SelStats.tsv"

	/** TSV: header comments (timestamp + app version), then
	 *  lang<TAB>bucket<TAB>weight rows — selStatsDump order. */
	fun tsv(rows: List<Triple<String, String, Double>>, versionName: String?, now: Date = Date()): String = buildString {
		append("# JustType sel_stats export ")
		append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now))
		append("\n# app ").append(versionName ?: "?").append("\n")
		append("lang\tbucket\tweight\n")
		for ((lang, bucket, weight) in rows) {
			append(lang).append('\t').append(bucket).append('\t')
			append(String.format(Locale.US, "%.4f", weight)).append('\n')
		}
	}

	/** Read the counters from the custom DB and format them, or null when
	 *  none exist (or the DB is unreadable — never worth failing a share). */
	fun dumpTsv(context: Context): String? {
		val rows = runCatching {
			WordDb.openStandalone(File(context.filesDir, "CustomDb.db")).use { it.selStatsDump() }
		}.getOrDefault(emptyList())
		if (rows.isEmpty()) return null
		val version = runCatching {
			context.packageManager.getPackageInfo(context.packageName, 0).versionName
		}.getOrNull()
		return tsv(rows, version)
	}
}
