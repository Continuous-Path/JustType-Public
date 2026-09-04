package org.continuouspath.justtype.logging

import org.continuouspath.justtype.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.EnumSet
import java.util.Locale

enum class DebugCategory(
	val prefValue: String,
	val label: String,
) {
	Lifecycle("lifecycle", "Lifecycle / UI"),
	UndoFlow("undo", "Undo flow"),
	PullInFlow("pullin", "Pull-in flow"),
	AmbigBuffer("ambig", "Ambiguous buffer"),
	SelectionSync("selection", "Selection / cursor sync"),
	InputConnection("icops", "InputConnection ops"),
	WordDb("worddb", "Word database"),
	ShiftState("shift", "Shift state"),
	TextEditing("textedit", "Text editing"),
	;

	companion object {
		val DEFAULT: Set<DebugCategory> = emptySet()

		fun fromPrefValues(values: Set<String>?): Set<DebugCategory> {
			if (values == null) return emptySet()
			if (values.isEmpty()) return emptySet()
			val resolved = values.mapNotNull { pref -> entries.firstOrNull { it.prefValue == pref } }.toSet()
			return resolved
		}

		fun toPrefValues(categories: Set<DebugCategory>): Set<String> = categories.map { it.prefValue }.toSet()
	}
}

/**
 * File-backed rolling debug logger.
 *
 * Writes one file per local-calendar day under [logsDirectory], naming each
 * `debug-YYYY-MM-DD.log`. The file handle rolls over at local midnight on
 * the first write of the new day. On startup, files older than
 * [retentionDays] are deleted.
 *
 * Daily files (not size-based ring) because:
 * - the user-visible retention semantics ("last 7 days") matches mental model.
 * - one cheap [File.lastModified] scan on startup is sufficient cleanup.
 * - per-file size cap acts as a safety net against runaway loops.
 *
 * BuildConfig.DEBUG_EDITING is the master compile-time switch: when false,
 * `log()` returns early before any work.
 */
object DebugLogger {
	const val DEFAULT_RETENTION_DAYS: Int = 14
	const val MIN_RETENTION_DAYS: Int = 1
	const val MAX_RETENTION_DAYS: Int = 30
	private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L

	// 10 MB per day. Beyond this, a runaway loop has gone wrong — drop further
	// writes for the day rather than fill the device. The user gets a partial
	// log (early-in-the-day events) which is still useful for diagnosis.
	private const val MAX_DAILY_LOG_BYTES: Long = 10L * 1024L * 1024L

	/**
	 * Categories that retain crash-diagnostic value at Tier 1 (beta).
	 * Other categories are compiled away in Tier 1 by the inline
	 * [tierAllowsCategory] gate (which checks [BuildConfig.DEBUG_TIER]).
	 *
	 * `Lifecycle` ships enabled by default at Tier 1. The others are
	 * available in Tier 1 settings so a beta-tester can flip them on per
	 * developer guidance, but default-off to keep the log lean. See
	 * docs/.plans/twinkling-mapping-sketch.md for the rationale.
	 */
	val TIER1_CATEGORIES: Set<DebugCategory> = setOf(
		DebugCategory.Lifecycle,
		DebugCategory.WordDb,
		DebugCategory.ShiftState,
		DebugCategory.PullInFlow,
		DebugCategory.UndoFlow,
		DebugCategory.SelectionSync,
		DebugCategory.TextEditing,
	)

	/**
	 * Default category set seeded on first install at Tier 1. Per the
	 * plan: only `Lifecycle` is on by default — the other Tier-1
	 * categories can be enabled by the user via Developer Settings if a
	 * developer asks them to.
	 */
	val TIER1_DEFAULT_ENABLED: Set<DebugCategory> = setOf(
		DebugCategory.Lifecycle,
	)

	/**
	 * Tier-aware compile-time gate. Returns whether [category] could ever
	 * write at the current build tier:
	 *   Tier 0 → false (always; whole call should be eliminated by R8)
	 *   Tier 1 → true iff [category] ∈ [TIER1_CATEGORIES]
	 *   Tier 2 → true
	 * Public so the inline `debugLog` helpers below can read it. Marked
	 * `internal const` semantics via direct BuildConfig read — kotlinc
	 * inlines the result and constant-folds the gate.
	 */
	@JvmStatic
	fun tierAllowsCategory(category: DebugCategory): Boolean = when (BuildConfig.DEBUG_TIER) {
		0 -> false
		1 -> category in TIER1_CATEGORIES
		else -> true
	}

	private val enabled: MutableSet<DebugCategory> = EnumSet.noneOf(DebugCategory::class.java)
	private val lock = Any()
	private var logsDirectory: File? = null
	private var retentionDays: Int = DEFAULT_RETENTION_DAYS

	// Cached path + date of the file we last opened. When the calendar date
	// changes (cross-midnight write), we re-derive the new path on the next
	// log call.
	private var currentDayKey: String? = null
	private var currentFile: File? = null

	private val timestampFormatter =
		ThreadLocal.withInitial {
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
		}
	private val dayKeyFormatter =
		ThreadLocal.withInitial {
			SimpleDateFormat("yyyy-MM-dd", Locale.US)
		}

	/**
	 * Configure the rolling-log directory and prune old files.
	 *
	 * @param directory destination directory (typically `{filesDir}/logs/`).
	 *                  Created if it doesn't exist.
	 * @param retentionDays delete files older than this many days. Clamped
	 *                      to [MIN_RETENTION_DAYS]..[MAX_RETENTION_DAYS].
	 */
	fun setLogDirectory(directory: File, retentionDays: Int = DEFAULT_RETENTION_DAYS) {
		val clampedRetention = retentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
		synchronized(lock) {
			directory.mkdirs()
			logsDirectory = directory
			this.retentionDays = clampedRetention
			// Invalidate cached file handle — caller may be re-pointing to a
			// different directory (tests, migrations).
			currentDayKey = null
			currentFile = null
		}
		pruneOldFiles()
	}

	fun setRetentionDays(days: Int) {
		val clamped = days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
		synchronized(lock) { retentionDays = clamped }
		pruneOldFiles()
	}

	/** Back to unconfigured (logging no-ops). **Only for use in tests.** */
	internal fun resetForTesting() {
		synchronized(lock) {
			logsDirectory = null
			retentionDays = DEFAULT_RETENTION_DAYS
			currentDayKey = null
			currentFile = null
		}
	}

	fun setEnabledCategories(categories: Set<DebugCategory>?) {
		synchronized(lock) {
			enabled.clear()
			if (categories == null) {
				enabled.addAll(DebugCategory.DEFAULT)
			} else {
				enabled.addAll(categories)
			}
		}
	}

	fun getEnabledCategories(): Set<DebugCategory> = synchronized(lock) { HashSet(enabled) }

	/**
	 * Returns the directory holding the rolling log files, or null if not
	 * initialized. The share helper uses this to zip the contents.
	 */
	fun getLogsDirectory(): File? = synchronized(lock) { logsDirectory }

	fun log(
		category: DebugCategory,
		messageBuilder: () -> String,
	): Boolean {
		if (!BuildConfig.DEBUG_EDITING) return false
		val now = Date()
		val dayKey = dayKeyFormatter.get().format(now)
		val targetFile: File = synchronized(lock) {
			if (!enabled.contains(category)) return false
			val dir = logsDirectory ?: return false
			// Re-derive the current file if the day has rolled over (or it's
			// the first write since init).
			if (currentDayKey != dayKey || currentFile == null) {
				currentDayKey = dayKey
				currentFile = File(dir, "DebugLog_$dayKey.log")
			}
			currentFile!!
		}
		val timestamp = timestampFormatter.get().format(now)
		val entry = "$timestamp | [${category.name}] ${messageBuilder()}\n"
		synchronized(lock) {
			// Safety net: stop appending if a single day's log exceeds the
			// hard ceiling. Prevents pathological growth from a runaway loop.
			if (targetFile.exists() && targetFile.length() >= MAX_DAILY_LOG_BYTES) {
				return false
			}
			targetFile.appendText(entry)
		}
		return true
	}

	private fun pruneOldFiles() {
		val (dir, retention) = synchronized(lock) {
			val d = logsDirectory ?: return
			d to retentionDays
		}
		val cutoffMs = System.currentTimeMillis() - retention.toLong() * MS_PER_DAY
		dir.listFiles { f -> f.isFile && f.name.startsWith("DebugLog_") && f.name.endsWith(".log") }
			?.filter { it.lastModified() < cutoffMs }
			?.forEach { runCatching { it.delete() } }
		// Also clean up any debug-YYYY-MM-DD.log files from the previous naming
		// scheme. They become unreachable after this rename; deleting them
		// reclaims the disk space without confusing the share-helper.
		dir.listFiles { f -> f.isFile && f.name.startsWith("debug-") && f.name.endsWith(".log") }
			?.forEach { runCatching { it.delete() } }
	}

	// Retained for backward compatibility — older code paths may still call
	// setLogFile(File("...debug.log")). Convert by treating the parent as
	// the logs directory.
	@Deprecated("Use setLogDirectory(File, Int) instead.", ReplaceWith("setLogDirectory(file.parentFile)"))
	fun setLogFile(file: File) {
		val parent = file.parentFile ?: return
		setLogDirectory(parent)
	}

	// Suppress "unused" warning for Calendar import retained for future
	// timezone-aware logic; the current implementation uses Date.
	@Suppress("unused")
	private val tzAnchor: Calendar = Calendar.getInstance()
}

// ── Inline lazy log helpers (Phase D4) ──────────────────────────────
//
// These are the new call-site shape for the inline-lambda rewrite.
// Callers migrate from:
//     debugLog("[foo] bar=$bar")           // string arg, eager interpolation
// to:
//     debugLogLazy(DebugCategory.PullInFlow) { "[foo] bar=$bar" }
//
// With BuildConfig.DEBUG_EDITING = false (Tier 0), kotlinc inlines this
// function body at every call site, sees the compile-time-constant
// gate, and emits zero bytecode for the entire `{ "..." }` lambda —
// including the string interpolation.
//
// At Tier 1, the category-tier gate (compile-time fold of
// DebugLogger.tierAllowsCategory) eliminates calls into Tier-2-only
// categories at every call site. The remaining runtime check is the
// user's enable-debug-log toggle + per-category multi-select.
//
// `inline` is what makes the gate compile-time; `crossinline` lets
// DebugLogger.log capture the lambda without allowing non-local returns
// from inside it.

@Suppress("NOTHING_TO_INLINE")
inline fun debugLogLazy(category: DebugCategory, crossinline messageBuilder: () -> String) {
	if (!BuildConfig.DEBUG_EDITING) return
	if (!DebugLogger.tierAllowsCategory(category)) return
	DebugLogger.log(category) { messageBuilder() }
}
