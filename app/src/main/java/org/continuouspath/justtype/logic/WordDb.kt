package org.continuouspath.justtype.logic

import android.content.ContentValues
import android.content.res.AssetManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.hierarchy.DiacriticDerivation
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import java.io.Closeable
import java.io.File

data class DbWordStats(
	val wordID: Int,
	val freqClass: Int,
	val classMask: Long,
	val useCount: Int,
	val useTime: Long?,
	val lowerCaseCount: Int,
	val titleCaseCount: Int,
	val upperCaseCount: Int,
	val originalCaseCount: Int,
	val posEncoded: Int = 0,
)

data class CaseCounts(
	val lower: Int,
	val title: Int,
	val upper: Int,
	val original: Int,
)

data class DbWordEntry(
	val wordID: Int,
	val word: String,
	val rawFreq: Int,
	val freqClass: Int,
	val pos1: String?,
	val pos2: String?,
	val classMask: Long,
	val useCount: Int,
	val posEncoded: Int = 0,
	val useTime: Long? = null,
)

class WordDb private constructor(private val db: SQLiteDatabase) : Closeable {
	private var jtStartTime: Long = 0L

	private fun loadJtStartTime() {
		val c = db.rawQuery("SELECT value FROM metadata WHERE `key` = 'jtStartTime'", null)
		c.use {
			if (it.moveToFirst()) {
				jtStartTime = it.getString(0).toLongOrNull() ?: System.currentTimeMillis()
			}
		}
	}

	/**
	 * Returns elapsed time since DB creation as an Int (~seconds precision).
	 * Uses right-shift by 10 (~÷1024) so 4 bytes covers ~136 years.
	 */
	fun relativeTime(): Int = ((System.currentTimeMillis() - jtStartTime) ushr 10).toInt()

	/** One NGB unit-inventory row (docs/.plans/ngram/engine-spec.md). */
	data class NgbUnit(
		val syls: String,
		val marginal: Long,
		val eff1n1: Long,
		val flags: Int,
		// Canonical orthography ("Hồ Chí Minh"); equals [syls] for lowercase units.
		val display: String = syls,
	)

	/** Rank-ordered (target, eff) prediction row for a context syllable, or
	 *  null. The engine's one seek per committed word. Prefers the v2 varint
	 *  blob ([NgbCodec] ids resolved through ngb_words); falls back to the
	 *  legacy "tgt:eff|tgt:eff|..." TEXT column for pre-v2 langpacks. */
	fun ngbContextTargets(ctx: String): List<Pair<String, Long>>? {
		val (legacy, blob) = db.rawQuery(
			"SELECT targets, tblob FROM ngb_ctx WHERE ctx = ?",
			arrayOf(ctx),
		).use { c ->
			if (!c.moveToFirst()) return null
			(if (c.isNull(0)) "" else c.getString(0)) to (if (c.isNull(1)) null else c.getBlob(1))
		}
		if (blob != null) {
			NgbCodec.decode(blob)?.let { pairs ->
				val words = ngbWordsFor(pairs.map { it.first })
				return pairs.mapNotNull { (id, eff) -> words[id]?.let { it to eff } }
			}
		}
		if (legacy.isEmpty()) return null
		val out = ArrayList<Pair<String, Long>>()
		for (part in legacy.splitToSequence('|')) {
			val colon = part.lastIndexOf(':')
			val eff = if (colon > 0) part.substring(colon + 1).toLongOrNull() else null
			if (eff != null) out.add(part.substring(0, colon) to eff)
		}
		return out.takeIf { it.isNotEmpty() }
	}

	/** Resolves ngb_words ids for one decoded row (<= K ids, int-only IN). */
	private fun ngbWordsFor(ids: List<Int>): Map<Int, String> {
		if (ids.isEmpty()) return emptyMap()
		return db.rawQuery(
			"SELECT id, word FROM ngb_words WHERE id IN (${ids.joinToString(",")})",
			null,
		).use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) } }
	}

	/** 1N1 source: units whose first syllable is [firstSyl] with a usable
	 *  prediction weight (eff1n1 > 0), heaviest first. */
	/** User-tier increment: capped count, recognition-driven (engine-spec Phase C). */
	fun ngbUserBump(lang: String, ctx: String, target: String) {
		db.execSQL(
			"INSERT INTO ngb_user (lang, ctx, target, count, last_used) " +
				"VALUES (?, ?, ?, 1, strftime('%s','now')) " +
				"ON CONFLICT(lang, ctx, target) DO UPDATE SET " +
				"count = MIN(count + 1, 255), last_used = strftime('%s','now')",
			arrayOf(lang, ctx, target),
		)
	}

	/** User-tier rows for one context (merged into the pool at fetch time). */
	fun ngbUserRows(lang: String, ctx: String): List<Pair<String, Int>> = db.rawQuery(
		"SELECT target, count FROM ngb_user WHERE lang = ? AND ctx = ?",
		arrayOf(lang, ctx),
	).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }

	/** NGB-D confidence weights for [lang]; empty until the first save. */
	fun ngbConfWeights(lang: String): Map<String, Double> = db.rawQuery(
		"SELECT feature, weight FROM ngb_conf WHERE lang = ?",
		arrayOf(lang),
	).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getDouble(1)) } }

	/** Persist the personalized NGB-D weights (weights only — never text). */
	fun ngbConfSaveWeights(lang: String, weights: Map<String, Double>) {
		weights.forEach { (feature, weight) ->
			db.execSQL(
				"INSERT INTO ngb_conf (lang, feature, weight) VALUES (?, ?, ?) " +
					"ON CONFLICT(lang, feature) DO UPDATE SET weight = excluded.weight",
				arrayOf(lang, feature, weight),
			)
		}
	}

	/** One Select-press episode: decay every bucket for [lang] by [decay], then
	 *  +1 the episode's bucket — so each bucket holds an EWMA count and the sum
	 *  over buckets is the EWMA episode total. */
	fun selStatsRecord(lang: String, bucket: String, decay: Double) {
		db.beginTransaction()
		try {
			db.execSQL("UPDATE sel_stats SET weight = weight * ? WHERE lang = ?", arrayOf(decay, lang))
			db.execSQL(
				"INSERT INTO sel_stats (lang, bucket, weight) VALUES (?, ?, 1.0) " +
					"ON CONFLICT(lang, bucket) DO UPDATE SET weight = weight + 1.0",
				arrayOf(lang, bucket),
			)
			db.setTransactionSuccessful()
		} finally {
			db.endTransaction()
		}
	}

	/** Select-behavior distribution for [lang] (Dev readout + tests). */
	fun selStatsAll(lang: String): Map<String, Double> = db.rawQuery(
		"SELECT bucket, weight FROM sel_stats WHERE lang = ?",
		arrayOf(lang),
	).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getDouble(1)) } }

	/** Every select-behavior row, all languages (Dev readout). */
	fun selStatsDump(): List<Triple<String, String, Double>> = db.rawQuery(
		"SELECT lang, bucket, weight FROM sel_stats ORDER BY lang, weight DESC",
		null,
	).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getDouble(2))) } }

	fun ngbUnitsByFirstSyl(firstSyl: String): List<NgbUnit> = db.rawQuery(
		"SELECT syls, marginal, eff1n1, flags, display FROM ngb_units " +
			"WHERE first_syl = ? AND eff1n1 > 0 ORDER BY eff1n1 DESC",
		arrayOf(firstSyl),
	).use { c ->
		buildList {
			while (c.moveToNext()) {
				add(NgbUnit(c.getString(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getString(4) ?: c.getString(0)))
			}
		}
	}

	/** lowercase unit -> canonical display, for units whose orthography differs
	 *  ("hồ chí minh" -> "Hồ Chí Minh"). Small (proper nouns only). */
	fun ngbUnitDisplayOverrides(): Map<String, String> = db.rawQuery(
		"SELECT syls, display FROM ngb_units WHERE display IS NOT NULL AND display != syls",
		null,
	).use { c ->
		buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
	}

	/** The full unit inventory (recognition matcher load; includes weightless units). */
	fun ngbUnitStrings(): List<String> = db.rawQuery("SELECT syls FROM ngb_units", null).use { c ->
		buildList { while (c.moveToNext()) add(c.getString(0)) }
	}

	/** True when this language DB ships NGB prediction data. */
	fun ngbHasData(): Boolean = db.rawQuery("SELECT 1 FROM ngb_ctx LIMIT 1", null)
		.use { it.moveToFirst() }

	companion object {
		private const val TAG = "WordDb"
		private const val DEFAULT_LANGUAGE = "English"
		private const val LEGACY_DB_NAME = "wordDB.db"

		/** Returns the active database filename for a given language. */
		fun activeDbName(language: String = DEFAULT_LANGUAGE): String = "${language}DbActive.db"

		/** Returns the bundled asset path for a given language. */
		private fun bundledAssetPath(language: String = DEFAULT_LANGUAGE): String = "databases/${language}Db.db"

		/** Thrown when a language has no active DB, no downloaded langpack, and no bundled asset. */
		class MissingLanguageSourceException(language: String, cause: Throwable? = null) : RuntimeException("No word-DB source for language '$language'", cause)

		/**
		 * Open the active language database using a 4-step resolution:
		 * 1. If {Language}DbActive.db exists -> open it (run migrations for legacy upgrades)
		 * 2. Else if legacy wordDB.db exists and language is English -> rename it, open, run migrations
		 * 3. Else if a downloaded langpack (langpacks/{Language}Db.db) exists -> copy it, set jtStartTime
		 * 4. Else -> copy pre-built {Language}Db.db from assets, set jtStartTime
		 *
		 * @throws MissingLanguageSourceException when none of the sources exist (e.g. prefs restored onto a
		 *   device that never downloaded the language) so callers can fall back to English.
		 */
		fun open(filesDir: File, assets: AssetManager, language: String = DEFAULT_LANGUAGE): WordDb {
			val activeName = activeDbName(language)
			val activeFile = File(filesDir, activeName)

			val isFreshCopy: Boolean
			if (activeFile.exists()) {
				isFreshCopy = false
			} else {
				val legacyFile = File(filesDir, LEGACY_DB_NAME)
				if (language == DEFAULT_LANGUAGE && legacyFile.exists()) {
					// Legacy migration only applies to English
					legacyFile.renameTo(activeFile)
					isFreshCopy = false
					Log.i(TAG, "Renamed legacy $LEGACY_DB_NAME -> $activeName")
				} else {
					copyFromPackOrAsset(filesDir, assets, language, activeFile)
					isFreshCopy = true
				}
			}

			val db = SQLiteDatabase.openOrCreateDatabase(activeFile, null)

			ensureSchema(db)
			if (!isFreshCopy) {
				ensureCaseColumns(db)
				ensureClassMaskColumn(db)
				ensurePhraseUUIDColumn(db)
				ensureWordIDColumn(db)
			}
			db.execSQL("CREATE INDEX IF NOT EXISTS idx_words_freqClass ON words(freqClass)")

			db.execSQL(
				"CREATE TABLE IF NOT EXISTS metadata (" +
					"`key` TEXT PRIMARY KEY, " +
					"value TEXT NOT NULL" +
					")",
			)
			initJtStartTime(db)
			ensureFreqClassCounts(db)
			ensureDiacriticSet(db)
			ensureNgbTables(db)
			cleanupCaseOrphanRows(db)

			val instance = WordDb(db)
			instance.loadJtStartTime()
			return instance
		}

		// NGB (n-gram prediction) tables — see docs/.plans/ngram/engine-spec.md.
		// Present in every language DB (empty for languages without NGB data) so the
		// runtime never probes. They carry NO user state: migration takes the new
		// build wholesale; the learned tier lives in the custom DB.
		private const val NGB_UNITS_DDL =
			"CREATE TABLE IF NOT EXISTS ngb_units (" +
				"unit_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
				"first_syl TEXT NOT NULL, " +
				"syls TEXT UNIQUE NOT NULL, " +
				"marginal INTEGER NOT NULL DEFAULT 0, " +
				"eff1n1 INTEGER NOT NULL DEFAULT 0, " +
				"flags INTEGER NOT NULL DEFAULT 0, " +
				"display TEXT" +
				")"
		private const val NGB_CTX_DDL =
			"CREATE TABLE IF NOT EXISTS ngb_ctx (" +
				"ctx TEXT PRIMARY KEY, " +
				"targets TEXT NOT NULL DEFAULT '', " +
				// v2 varint payload (NgbCodec); TEXT column kept for pre-v2 packs.
				"tblob BLOB" +
				")"

		// NGB-private target dictionary for the v2 blob format. Ids are NOT
		// words.wordID (migration reassigns those); this table migrates
		// wholesale with ngb_ctx, exactly like ngb_units.
		private const val NGB_WORDS_DDL =
			"CREATE TABLE IF NOT EXISTS ngb_words (" +
				"id INTEGER PRIMARY KEY, " +
				"word TEXT NOT NULL" +
				")"

		private fun ensureNgbTables(db: SQLiteDatabase) {
			db.execSQL(NGB_UNITS_DDL)
			// Pre-display schemas: CREATE IF NOT EXISTS won't add the column to
			// an existing table; idempotent ALTER keeps SELECTs safe until the
			// langBuildVersion migration reseeds with real display data.
			runCatching { db.execSQL("ALTER TABLE ngb_units ADD COLUMN display TEXT") }
			db.execSQL("CREATE INDEX IF NOT EXISTS idx_ngb_units_first ON ngb_units(first_syl)")
			db.execSQL(NGB_CTX_DDL)
			// Same idempotent-ALTER pattern for pre-v2 ngb_ctx tables.
			runCatching { db.execSQL("ALTER TABLE ngb_ctx ADD COLUMN tblob BLOB") }
			db.execSQL(NGB_WORDS_DDL)
			// User-learned tier (engine-spec Phase C). Targets are stored as
			// STRINGS, not language-DB ids — a deliberate spec deviation: the
			// custom DB survives langpack swaps/rebuilds, and id references
			// would silently corrupt across them.
			db.execSQL(
				"CREATE TABLE IF NOT EXISTS ngb_user (" +
					"lang TEXT NOT NULL, " +
					"ctx TEXT NOT NULL, " +
					"target TEXT NOT NULL, " +
					"count INTEGER NOT NULL DEFAULT 0, " +
					"last_used INTEGER NOT NULL DEFAULT 0, " +
					"PRIMARY KEY (lang, ctx, target)" +
					")",
			)
			// NGB-D personalized confidence weights (custom DB, like ngb_user):
			// a handful of REAL rows per language — no text, no counts of words.
			db.execSQL(
				"CREATE TABLE IF NOT EXISTS ngb_conf (" +
					"lang TEXT NOT NULL, " +
					"feature TEXT NOT NULL, " +
					"weight REAL NOT NULL, " +
					"PRIMARY KEY (lang, feature)" +
					")",
			)
			// Select-behavior substrate (sls.md "Adaptive select-behavior
			// mechanisms"): EWMA-weighted Select-press episode counters, keyed by
			// (signal state x demoted-FTS-present) : (selected kind . depth).
			// Bounded bucket vocabulary — no words, no text, custom DB only.
			db.execSQL(
				"CREATE TABLE IF NOT EXISTS sel_stats (" +
					"lang TEXT NOT NULL, " +
					"bucket TEXT NOT NULL, " +
					"weight REAL NOT NULL DEFAULT 0, " +
					"PRIMARY KEY (lang, bucket)" +
					")",
			)
		}

		/**
		 * One-time backfill: if the DB has no `diacriticSet` metadata row, derive it from the built-in
		 * (JustType) words and store it. Idempotent (guarded by the presence check); covers both a freshly
		 * copied asset and existing installs on upgrade. See [DiacriticDerivation] / LanguageRegistry.
		 */
		private fun ensureDiacriticSet(db: SQLiteDatabase) {
			val has = db.rawQuery("SELECT 1 FROM metadata WHERE `key` = 'diacriticSet' LIMIT 1", null)
				.use { it.moveToFirst() }
			if (has) return
			val set = sortedSetOf<Char>()
			db.rawQuery(
				"SELECT word FROM words WHERE (classMask & ${ClassMasks.CLASS_JUSTTYPE_MASK}) != 0",
				null,
			).use { c ->
				while (c.moveToNext()) DiacriticDerivation.scanWord(c.getString(0), set)
			}
			db.execSQL(
				"INSERT OR REPLACE INTO metadata (`key`, value) VALUES ('diacriticSet', ?)",
				arrayOf(DiacriticDerivation.encode(set)),
			)
		}

		// Metadata rows that come from the language build, not from user activity — safe to
		// refresh in an existing active DB when its pristine source (langpack pack or bundled
		// asset) ships a newer language build.
		private val STATIC_METADATA_KEYS = listOf(LayoutSpec.METADATA_KEY, "diacriticSet")

		/**
		 * Copy build-produced metadata (layoutJson, diacriticSet) from a pristine source DB into
		 * an existing active DB, leaving user words and learned stats untouched. The active DB was
		 * seeded once and never re-copied (it holds user data), so without this a layout change in
		 * an app update or langpack update would never reach the device. Returns the changed keys.
		 */
		fun refreshStaticMetadata(activeFile: File, sourceFile: File): List<String> {
			if (!activeFile.exists() || !sourceFile.exists()) return emptyList()
			val changed = SQLiteDatabase.openDatabase(sourceFile.path, null, SQLiteDatabase.OPEN_READONLY).use { src ->
				SQLiteDatabase.openOrCreateDatabase(activeFile, null).use { active ->
					STATIC_METADATA_KEYS.filter { copyMetadataRow(src, active, it) }
				}
			}
			if (changed.isNotEmpty()) Log.i(TAG, "Refreshed static metadata $changed in ${activeFile.name}")
			return changed
		}

		/** Metadata key holding the content hash of the inputs a language DB was built from. */
		const val LANG_BUILD_VERSION_KEY = "langBuildVersion"

		private const val WORDS_TABLE_DDL =
			"CREATE TABLE IF NOT EXISTS words (" +
				"wordID INTEGER PRIMARY KEY AUTOINCREMENT, " +
				"word TEXT UNIQUE NOT NULL, " +
				"freqClass INTEGER NOT NULL, " +
				"classMask INTEGER NOT NULL DEFAULT 0, " +
				"useCount INTEGER NOT NULL DEFAULT 0, " +
				"useTime INTEGER, " +
				"rawFreq INTEGER NOT NULL DEFAULT 0, " +
				"PartOfSpeech1 TEXT, " +
				"PartOfSpeech2 TEXT, " +
				"lowerCaseCount INTEGER NOT NULL DEFAULT 0, " +
				"titleCaseCount INTEGER NOT NULL DEFAULT 0, " +
				"upperCaseCount INTEGER NOT NULL DEFAULT 0, " +
				"originalCaseCount INTEGER NOT NULL DEFAULT 0, " +
				"phraseUUID TEXT, " +
				"posEncoded INTEGER NOT NULL DEFAULT 0" +
				")"

		/**
		 * Class bits the language build owns and may therefore restate on migration. Everything
		 * else in a row's mask belongs to the user (imported vocabularies) and is carried across.
		 */
		private const val CLASS_BUILD_OWNED_MASK: Long =
			ClassMasks.CLASS_JUSTTYPE_MASK or
				ClassMasks.CLASS_OFFENSIVE_MASK or
				ClassMasks.CLASS_POTENTIALLY_OFFENSIVE_MASK or
				ClassMasks.CLASS_REGION_ES_SKEW_MASK or
				ClassMasks.CLASS_REGION_LA_SKEW_MASK or
				ClassMasks.CLASS_REGION_GB_SKEW_MASK or
				ClassMasks.CLASS_REGION_US_SKEW_MASK

		/** Recompute the freqClass histogram after the words table is rebuilt. */
		private fun rebuildFreqClassCounts(db: SQLiteDatabase) {
			db.beginTransaction()
			try {
				db.execSQL("DELETE FROM freq_class_counts")
				db.execSQL(
					"INSERT OR REPLACE INTO freq_class_counts (freqClass, count) " +
						"SELECT freqClass, COUNT(1) FROM words WHERE (classMask & ?) != 0 " +
						"GROUP BY freqClass",
					arrayOf(ClassMasks.CLASS_JUSTTYPE_MASK.toString()),
				)
				db.setTransactionSuccessful()
			} finally {
				db.endTransaction()
			}
		}

		/**
		 * Migrate a rebuilt corpus into an existing active DB, preserving what the user owns.
		 *
		 * The active DB is seeded from the shipped asset once and never re-copied, because it
		 * accumulates learned state. So a corpus update (new words, re-graded exclusions, fixed
		 * capitalization) would otherwise never reach anyone who already has the language
		 * installed. This rebuilds the words table from [sourceFile] when its
		 * [LANG_BUILD_VERSION_KEY] differs, carrying across:
		 *
		 *  - per-word learned state (use count, last-use time, case counts, the runtime-mutable
		 *    CaseType byte, phrase links) for words the user has actually used;
		 *  - every row belonging to an imported vocabulary, which lives in this same DB under
		 *    its own class bit and is not part of the language build;
		 *  - words dropped by the new corpus that the user has nonetheless used, so an update
		 *    never takes away vocabulary someone relies on.
		 *
		 * Words the user has never used take the new build's ranking, POS and case seeds
		 * wholesale. Returns true when a migration ran.
		 */
		/**
		 * One-shot repair (2026-08-10): the pre-fix NGB display path fabricated
		 * lowercase orphan rows (rawFreq 0, never used) for case-fixed words
		 * ("I" -> "i", "China" -> "china") via a case-sensitive
		 * getOrCreateStats. They shadowed the real rows' stats in the trie
		 * merge and split block/trie dedup. Deletes only untouched fabricated
		 * rows whose real (differently-cased) corpus row exists.
		 */
		private fun cleanupCaseOrphanRows(db: SQLiteDatabase) {
			if (readMetadataRow(db, "caseOrphanCleanup") == "1") return
			// Legacy pre-rawFreq schemas: skip (flag unset, retried once the
			// column-adding migrations have run on a later open).
			runCatching {
				db.execSQL(
					"DELETE FROM words WHERE rawFreq = 0 AND useCount = 0 AND useTime IS NULL " +
						"AND phraseUUID IS NULL " +
						"AND EXISTS (SELECT 1 FROM words w2 WHERE lower(w2.word) = words.word " +
						"AND w2.word != words.word AND w2.rawFreq > 0)",
				)
				db.execSQL(
					"INSERT OR REPLACE INTO metadata (`key`, value) VALUES ('caseOrphanCleanup', '1')",
				)
			}
		}

		fun migrateLanguageBuild(activeFile: File, sourceFile: File): Boolean {
			if (!activeFile.exists() || !sourceFile.exists()) return false
			return SQLiteDatabase.openDatabase(sourceFile.path, null, SQLiteDatabase.OPEN_READONLY).use { src ->
				val srcVersion = readMetadataRow(src, LANG_BUILD_VERSION_KEY)
				if (srcVersion == null) {
					// Pre-stamp asset: nothing to compare against, so leave the user's DB alone.
					return@use false
				}
				SQLiteDatabase.openOrCreateDatabase(activeFile, null).use { active ->
					if (readMetadataRow(active, LANG_BUILD_VERSION_KEY) == srcVersion) return@use false
					rebuildWordsFromSource(active, sourceFile, srcVersion)
					true
				}
			}
		}

		private fun rebuildWordsFromSource(active: SQLiteDatabase, sourceFile: File, srcVersion: String) {
			val before = active.rawQuery("SELECT COUNT(1) FROM words", null)
				.use { if (it.moveToFirst()) it.getInt(0) else 0 }
			active.execSQL("ATTACH DATABASE ? AS src", arrayOf(sourceFile.path))
			try {
				active.beginTransaction()
				// Snapshot the state the user owns before the table is replaced.
				active.execSQL("DROP TABLE IF EXISTS user_state")
				active.execSQL(
					"CREATE TEMP TABLE user_state AS SELECT word, useCount, useTime, " +
						"lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount, " +
						"phraseUUID, posEncoded, classMask, freqClass, rawFreq, " +
						"PartOfSpeech1, PartOfSpeech2 FROM words",
				)
				active.execSQL("DROP TABLE words")
				active.execSQL(WORDS_TABLE_DDL)

				// New build wins on ranking/POS/class; the user's learned state wins wherever
				// they have used the word (useCount > 0), including the mutable CaseType byte.
				active.execSQL(
					"""INSERT INTO words
						(word, freqClass, classMask, useCount, useTime, rawFreq,
						 PartOfSpeech1, PartOfSpeech2, lowerCaseCount, titleCaseCount,
						 upperCaseCount, originalCaseCount, phraseUUID, posEncoded)
					   SELECT s.word, s.freqClass,
					          s.classMask | COALESCE(u.classMask & ~$CLASS_BUILD_OWNED_MASK, 0),
					          COALESCE(u.useCount, 0), u.useTime, s.rawFreq,
					          s.PartOfSpeech1, s.PartOfSpeech2,
					          CASE WHEN COALESCE(u.useCount, 0) > 0
					               THEN u.lowerCaseCount ELSE s.lowerCaseCount END,
					          CASE WHEN COALESCE(u.useCount, 0) > 0
					               THEN u.titleCaseCount ELSE s.titleCaseCount END,
					          CASE WHEN COALESCE(u.useCount, 0) > 0
					               THEN u.upperCaseCount ELSE s.upperCaseCount END,
					          CASE WHEN COALESCE(u.useCount, 0) > 0
					               THEN u.originalCaseCount ELSE s.originalCaseCount END,
					          u.phraseUUID,
					          CASE WHEN COALESCE(u.useCount, 0) > 0
					               THEN (s.posEncoded & ~255) | (u.posEncoded & 255)
					               ELSE s.posEncoded END
					   FROM src.words s LEFT JOIN user_state u ON u.word = s.word""",
				)
				// Rows the new build does not contain: keep imported-vocabulary words (they are
				// not part of the language build) and anything the user has actually used.
				active.execSQL(
					"""INSERT OR IGNORE INTO words
						(word, freqClass, classMask, useCount, useTime, rawFreq,
						 PartOfSpeech1, PartOfSpeech2, lowerCaseCount, titleCaseCount,
						 upperCaseCount, originalCaseCount, phraseUUID, posEncoded)
					   SELECT u.word, u.freqClass, u.classMask, u.useCount, u.useTime, u.rawFreq,
					          u.PartOfSpeech1, u.PartOfSpeech2, u.lowerCaseCount, u.titleCaseCount,
					          u.upperCaseCount, u.originalCaseCount, u.phraseUUID, u.posEncoded
					   FROM user_state u
					   WHERE u.word NOT IN (SELECT word FROM src.words)
					     AND (u.useCount > 0 OR (u.classMask & ~$CLASS_BUILD_OWNED_MASK) != 0)""",
				)
				active.execSQL("DROP TABLE IF EXISTS user_state")
				// NGB tables carry no user state — the new build replaces them wholesale.
				// Guard on the source actually having them (older packs predate NGB),
				// and probe per-column: sources span three formats (pre-display units,
				// pre-v2 text ngb_ctx, v2 varint tblob + ngb_words).
				ensureNgbTables(active)
				fun srcHasTable(name: String): Boolean = active.rawQuery(
					"SELECT 1 FROM src.sqlite_master WHERE type='table' AND name=? LIMIT 1",
					arrayOf(name),
				).use { it.moveToFirst() }
				fun srcHasColumn(table: String, column: String): Boolean = active.rawQuery(
					// Classic PRAGMA form — table names are compile-time constants here.
					"PRAGMA src.table_info($table)",
					null,
				).use { c ->
					val nameIdx = c.getColumnIndexOrThrow("name")
					var found = false
					while (!found && c.moveToNext()) found = c.getString(nameIdx) == column
					found
				}
				active.execSQL("DELETE FROM ngb_units")
				active.execSQL("DELETE FROM ngb_ctx")
				active.execSQL("DELETE FROM ngb_words")
				if (srcHasTable("ngb_ctx")) {
					// display carried when the source has it (older builds predate it).
					val displayCol = if (srcHasColumn("ngb_units", "display")) ", display" else ""
					active.execSQL(
						"INSERT INTO ngb_units (unit_id, first_syl, syls, marginal, eff1n1, flags$displayCol) " +
							"SELECT unit_id, first_syl, syls, marginal, eff1n1, flags$displayCol FROM src.ngb_units",
					)
					if (srcHasColumn("ngb_ctx", "tblob")) {
						active.execSQL(
							"INSERT INTO ngb_ctx (ctx, targets, tblob) SELECT ctx, targets, tblob FROM src.ngb_ctx",
						)
					} else {
						active.execSQL("INSERT INTO ngb_ctx (ctx, targets) SELECT ctx, targets FROM src.ngb_ctx")
					}
					if (srcHasTable("ngb_words")) {
						active.execSQL("INSERT INTO ngb_words (id, word) SELECT id, word FROM src.ngb_words")
					}
				}
				active.execSQL(
					"INSERT OR REPLACE INTO metadata (`key`, value) VALUES (?, ?)",
					arrayOf(LANG_BUILD_VERSION_KEY, srcVersion),
				)
				active.setTransactionSuccessful()
			} finally {
				active.endTransaction()
				active.execSQL("DETACH DATABASE src")
			}
			rebuildFreqClassCounts(active)
			val after = active.rawQuery("SELECT COUNT(1) FROM words", null)
				.use { if (it.moveToFirst()) it.getInt(0) else 0 }
			Log.i(TAG, "Migrated language build -> $srcVersion: $before -> $after words")
		}

		/** Copies one metadata row source -> active; true when the active row changed. */
		private fun copyMetadataRow(src: SQLiteDatabase, active: SQLiteDatabase, key: String): Boolean {
			val value = readMetadataRow(src, key) ?: return false
			if (readMetadataRow(active, key) == value) return false
			active.execSQL(
				"INSERT OR REPLACE INTO metadata (`key`, value) VALUES (?, ?)",
				arrayOf(key, value),
			)
			return true
		}

		private fun readMetadataRow(db: SQLiteDatabase, key: String): String? = try {
			db.rawQuery("SELECT value FROM metadata WHERE `key` = ?", arrayOf(key))
				.use { if (it.moveToFirst()) it.getString(0) else null }
		} catch (_: SQLiteException) {
			null // no metadata table in this source
		}

		/**
		 * Open or create a standalone database with the same schema.
		 * Used for CustomDb.db (no bundled asset, no legacy migration).
		 */
		fun openStandalone(dbFile: File): WordDb {
			val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
			ensureSchema(db)
			db.execSQL("CREATE INDEX IF NOT EXISTS idx_words_freqClass ON words(freqClass)")
			db.execSQL(
				"CREATE TABLE IF NOT EXISTS metadata (" +
					"`key` TEXT PRIMARY KEY, " +
					"value TEXT NOT NULL" +
					")",
			)
			ensureNgbTables(db)
			initJtStartTime(db)
			val instance = WordDb(db)
			instance.loadJtStartTime()
			return instance
		}

		/**
		 * Reset the active language DB by deleting it and re-copying from its pristine source (downloaded
		 * langpack if present, else the bundled asset). Returns true on success.
		 */
		fun resetToDefaults(filesDir: File, assets: AssetManager, language: String = DEFAULT_LANGUAGE): Boolean {
			val activeName = activeDbName(language)
			val activeFile = File(filesDir, activeName)
			if (activeFile.exists()) activeFile.delete()
			return try {
				copyFromPackOrAsset(filesDir, assets, language, activeFile)
				true
			} catch (e: Exception) {
				Log.e(TAG, "Reset failed: ${e.message}")
				false
			}
		}

		/**
		 * Copy a language's pristine DB to [destFile], preferring a downloaded langpack
		 * (filesDir/langpacks/{Language}Db.db) over the bundled asset.
		 */
		private fun copyFromPackOrAsset(filesDir: File, assets: AssetManager, language: String, destFile: File) {
			val pack = org.continuouspath.justtype.langpack.LangpackStore.installedPackFile(filesDir, language)
			if (pack.exists()) {
				destFile.parentFile?.mkdirs()
				pack.inputStream().use { input -> destFile.outputStream().use { output -> input.copyTo(output) } }
				Log.i(TAG, "Copied langpack ${pack.name} -> ${destFile.name}")
				return
			}
			try {
				copyAssetDb(assets, bundledAssetPath(language), destFile)
				Log.i(TAG, "Copied bundled ${bundledAssetPath(language)} -> ${destFile.name}")
			} catch (e: java.io.FileNotFoundException) {
				destFile.delete()
				throw MissingLanguageSourceException(language, e)
			} catch (e: java.io.IOException) {
				destFile.delete()
				throw MissingLanguageSourceException(language, e)
			}
		}

		private fun ensureSchema(db: SQLiteDatabase) {
			db.execSQL(WORDS_TABLE_DDL)
		}

		private fun copyAssetDb(assets: AssetManager, assetPath: String, destFile: File) {
			destFile.parentFile?.mkdirs()
			assets.open(assetPath).use { input ->
				destFile.outputStream().use { output ->
					input.copyTo(output)
				}
			}
		}

		private fun ensureCaseColumns(db: SQLiteDatabase) {
			val required = mapOf(
				"lowerCaseCount" to "ALTER TABLE words ADD COLUMN lowerCaseCount INTEGER NOT NULL DEFAULT 0",
				"titleCaseCount" to "ALTER TABLE words ADD COLUMN titleCaseCount INTEGER NOT NULL DEFAULT 0",
				"upperCaseCount" to "ALTER TABLE words ADD COLUMN upperCaseCount INTEGER NOT NULL DEFAULT 0",
				"originalCaseCount" to "ALTER TABLE words ADD COLUMN originalCaseCount INTEGER NOT NULL DEFAULT 0",
			)
			val existing = mutableSetOf<String>()
			val cursor = db.rawQuery("PRAGMA table_info(words)", null)
			cursor.use {
				while (it.moveToNext()) {
					existing.add(it.getString(1))
				}
			}
			required.forEach { (column, ddl) ->
				if (!existing.contains(column)) {
					try {
						db.execSQL(ddl)
					} catch (_: Exception) {
					}
				}
			}
		}

		private fun ensureClassMaskColumn(db: SQLiteDatabase) {
			val existing = mutableSetOf<String>()
			val cursor = db.rawQuery("PRAGMA table_info(words)", null)
			cursor.use {
				while (it.moveToNext()) {
					existing.add(it.getString(1))
				}
			}
			if (!existing.contains("classMask")) {
				try {
					db.execSQL("ALTER TABLE words ADD COLUMN classMask INTEGER NOT NULL DEFAULT 0")
				} catch (_: Exception) {
				}
			}
			try {
				db.execSQL(
					"UPDATE words SET classMask = classMask | ? WHERE classMask = 0",
					arrayOf(ClassMasks.CLASS_JUSTTYPE_MASK),
				)
			} catch (_: Exception) {
			}
		}

		private fun ensurePhraseUUIDColumn(db: SQLiteDatabase) {
			val existing = mutableSetOf<String>()
			val cursor = db.rawQuery("PRAGMA table_info(words)", null)
			cursor.use {
				while (it.moveToNext()) {
					existing.add(it.getString(1))
				}
			}
			if (!existing.contains("phraseUUID")) {
				try {
					db.execSQL("ALTER TABLE words ADD COLUMN phraseUUID TEXT")
				} catch (_: Exception) {
				}
			}
		}

		/**
		 * Rebuilds the words table to use wordID INTEGER PRIMARY KEY AUTOINCREMENT
		 * and adds the posEncoded column. Only runs when wordID column is absent.
		 */
		private fun ensureWordIDColumn(db: SQLiteDatabase) {
			val existing = mutableSetOf<String>()
			val cursor = db.rawQuery("PRAGMA table_info(words)", null)
			cursor.use {
				while (it.moveToNext()) {
					existing.add(it.getString(1))
				}
			}
			if (existing.contains("wordID")) return

			Log.i(TAG, "Migrating words table: adding wordID AUTOINCREMENT + posEncoded")
			db.beginTransaction()
			try {
				db.execSQL(
					"CREATE TABLE words_new (" +
						"wordID INTEGER PRIMARY KEY AUTOINCREMENT, " +
						"word TEXT UNIQUE NOT NULL, " +
						"freqClass INTEGER NOT NULL, " +
						"classMask INTEGER NOT NULL DEFAULT 0, " +
						"useCount INTEGER NOT NULL DEFAULT 0, " +
						"useTime INTEGER, " +
						"rawFreq INTEGER NOT NULL DEFAULT 0, " +
						"PartOfSpeech1 TEXT, " +
						"PartOfSpeech2 TEXT, " +
						"lowerCaseCount INTEGER NOT NULL DEFAULT 0, " +
						"titleCaseCount INTEGER NOT NULL DEFAULT 0, " +
						"upperCaseCount INTEGER NOT NULL DEFAULT 0, " +
						"originalCaseCount INTEGER NOT NULL DEFAULT 0, " +
						"phraseUUID TEXT, " +
						"posEncoded INTEGER NOT NULL DEFAULT 0" +
						")",
				)
				val colsToCopy = listOf(
					"word", "freqClass", "classMask", "useCount", "useTime", "rawFreq",
					"PartOfSpeech1", "PartOfSpeech2", "lowerCaseCount", "titleCaseCount",
					"upperCaseCount", "originalCaseCount", "phraseUUID",
				).filter { existing.contains(it) }
				val colList = colsToCopy.joinToString(", ")
				db.execSQL("INSERT INTO words_new ($colList) SELECT $colList FROM words")
				db.execSQL("DROP TABLE words")
				db.execSQL("ALTER TABLE words_new RENAME TO words")
				db.setTransactionSuccessful()
				Log.i(TAG, "words table migration complete")
			} catch (e: Exception) {
				Log.e(TAG, "words table migration failed: ${e.message}")
			} finally {
				db.endTransaction()
			}
		}

		private fun initJtStartTime(db: SQLiteDatabase) {
			val c = db.rawQuery(
				/* sql = */ "SELECT value FROM metadata WHERE `key` = 'jtStartTime'", /* selectionArgs = */
				null,
			)
			val exists = c.use { it.moveToFirst() }
			if (!exists) {
				db.execSQL(
					"INSERT INTO metadata (`key`, value) VALUES ('jtStartTime', ?)",
					arrayOf(System.currentTimeMillis().toString()),
				)
			}
		}

		private fun ensureFreqClassCounts(db: SQLiteDatabase) {
			db.execSQL(
				"CREATE TABLE IF NOT EXISTS freq_class_counts (" +
					"freqClass INTEGER PRIMARY KEY, " +
					"count INTEGER NOT NULL" +
					")",
			)
			val countCursor = db.rawQuery("SELECT COUNT(1) FROM freq_class_counts", null)
			val needsPopulate = countCursor.use { it.moveToFirst() && it.getInt(0) == 0 }
			if (!needsPopulate) return
			db.beginTransaction()
			try {
				db.execSQL("DELETE FROM freq_class_counts")
				val cursor = db.rawQuery(
					"SELECT freqClass, COUNT(1) FROM words WHERE (classMask & ?) != 0 GROUP BY freqClass",
					arrayOf(ClassMasks.CLASS_JUSTTYPE_MASK.toString()),
				)
				cursor.use {
					val stmt = db.compileStatement(
						"INSERT OR REPLACE INTO freq_class_counts (freqClass, count) VALUES (?, ?)",
					)
					while (it.moveToNext()) {
						stmt.clearBindings()
						stmt.bindLong(1, it.getInt(0).toLong())
						stmt.bindLong(2, it.getInt(1).toLong())
						stmt.executeInsert()
					}
				}
				db.setTransactionSuccessful()
			} catch (_: Exception) {
			} finally {
				try {
					db.endTransaction()
				} catch (_: Exception) {}
			}
		}
		fun computeFreqClass(raw: Int): Int = when {
			raw >= 40000 -> 1
			raw >= 20000 -> 2
			raw >= 10000 -> 3
			raw >= 5000 -> 4
			raw >= 2500 -> 5
			raw >= 1250 -> 6
			raw >= 625 -> 7
			raw >= 300 -> 8
			raw >= 150 -> 9
			raw >= 75 -> 10
			raw >= 37 -> 11
			raw >= 17 -> 12
			raw > 8 -> 13
			else -> 14
		}
	}

	// ── wordID-based lookups ────────────────────────────────────────────────

	fun getWordByID(wordID: Int): String? {
		val c = db.rawQuery("SELECT word FROM words WHERE wordID = ?", arrayOf(wordID.toString()))
		return c.use { if (it.moveToFirst()) it.getString(0) else null }
	}

	fun getWordStatsByID(wordID: Int): DbWordStats? {
		val c = db.rawQuery(
			"SELECT wordID, freqClass, classMask, useCount, useTime, lowerCaseCount, titleCaseCount, " +
				"upperCaseCount, originalCaseCount, posEncoded FROM words WHERE wordID = ?",
			arrayOf(wordID.toString()),
		)
		return c.use {
			if (!it.moveToFirst()) return null
			DbWordStats(
				wordID = it.getInt(0),
				freqClass = it.getInt(1),
				classMask = it.getLong(2),
				useCount = it.getInt(3),
				useTime = if (it.isNull(4)) null else it.getLong(4),
				lowerCaseCount = it.getInt(5),
				titleCaseCount = it.getInt(6),
				upperCaseCount = it.getInt(7),
				originalCaseCount = it.getInt(8),
				posEncoded = it.getInt(9),
			)
		}
	}

	fun getWordIDByWord(word: String): Int? {
		val c = db.rawQuery("SELECT wordID FROM words WHERE word = ?", arrayOf(word))
		return c.use { if (it.moveToFirst()) it.getInt(0) else null }
	}

	fun getPosEncoded(word: String): Int {
		val c = db.rawQuery("SELECT posEncoded FROM words WHERE word = ?", arrayOf(word))
		return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
	}

	fun updatePosEncoded(word: String, posEncoded: Int) {
		db.execSQL("UPDATE words SET posEncoded = ? WHERE word = ?", arrayOf(posEncoded, word))
	}

	fun updatePosEncodedByID(wordID: Int, posEncoded: Int) {
		db.execSQL("UPDATE words SET posEncoded = ? WHERE wordID = ?", arrayOf(posEncoded, wordID))
	}

	/**
	 * Converts an absolute epoch-millis timestamp to the compact relative form
	 * used by WordEntry.lastUseTime. Returns 0 if the timestamp is null/zero.
	 */
	fun absoluteToRelativeTime(absTimeMs: Long?): Int {
		if (absTimeMs == null || absTimeMs == 0L) return 0
		return ((absTimeMs - jtStartTime) ushr 10).toInt().coerceAtLeast(1)
	}

	fun markWordUsedByID(wordID: Int) {
		val now = System.currentTimeMillis()
		db.execSQL("UPDATE words SET useCount = useCount + 1, useTime = ? WHERE wordID = ?", arrayOf(now, wordID))
	}

	fun incrementCaseCountByID(wordID: Int, form: WordCaseForm, amount: Int = 1) {
		val column = when (form) {
			WordCaseForm.LOWER -> "lowerCaseCount"
			WordCaseForm.TITLE -> "titleCaseCount"
			WordCaseForm.UPPER -> "upperCaseCount"
			WordCaseForm.ORIGINAL -> "originalCaseCount"
		}
		db.execSQL("UPDATE words SET $column = $column + ? WHERE wordID = ?", arrayOf(amount, wordID))
	}

	fun getCaseCountsByID(wordID: Int): CaseCounts? {
		val c = db.rawQuery(
			"SELECT lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount FROM words WHERE wordID = ?",
			arrayOf(wordID.toString()),
		)
		return c.use {
			if (it.moveToFirst()) CaseCounts(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) else null
		}
	}

	fun decrementFreqClassByID(wordID: Int, minClass: Int) {
		var curFc: Int? = null
		val c = db.rawQuery("SELECT freqClass FROM words WHERE wordID = ?", arrayOf(wordID.toString()))
		c.use { if (it.moveToFirst()) curFc = it.getInt(0) }
		if (curFc == null) return
		val newFc = if (curFc!! > minClass) curFc!! - 1 else curFc!!
		db.execSQL("UPDATE words SET freqClass = ? WHERE wordID = ?", arrayOf(newFc, wordID))
	}

	fun getPhraseUUIDByID(wordID: Int): String? {
		val c = db.rawQuery("SELECT phraseUUID FROM words WHERE wordID = ?", arrayOf(wordID.toString()))
		return c.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
	}

	// ── Existing word-keyed lookups ─────────────────────────────────────────

	fun getOrCreateStats(
		word: String,
		defaultFreqClass: Int = 14,
		defaultRawFreq: Int = 0,
		defaultPos1: String? = null,
		defaultPos2: String? = null,
		defaultClassMask: Long = ClassMasks.CLASS_JUSTTYPE_MASK,
	): DbWordStats {
		val cur = db.rawQuery(
			"SELECT wordID, freqClass, classMask, useCount, useTime, lowerCaseCount, titleCaseCount, " +
				"upperCaseCount, originalCaseCount, posEncoded FROM words WHERE word = ?",
			arrayOf(word),
		)
		cur.use {
			if (it.moveToFirst()) {
				val wid = it.getInt(0)
				val fc = it.getInt(1)
				val classMask = it.getLong(2)
				val uc = it.getInt(3)
				val ut = if (it.isNull(4)) null else it.getLong(4)
				var lower = it.getInt(5)
				var title = it.getInt(6)
				var upper = it.getInt(7)
				var original = it.getInt(8)
				val pe = it.getInt(9)
				if (lower + title + upper + original == 0) {
					val defaults = computeInitialCaseCounts(word)
					lower = defaults.lower
					title = defaults.title
					upper = defaults.upper
					original = defaults.original
					replaceCaseCounts(word, defaults)
				}
				return DbWordStats(wid, fc, classMask, uc, ut, lower, title, upper, original, pe)
			}
		}
		// Insert default row (returns the new rowid which equals wordID)
		val insert =
			db.compileStatement("INSERT OR IGNORE INTO words (word, freqClass, classMask, useCount, useTime, rawFreq, PartOfSpeech1, PartOfSpeech2, lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?, ?, ?, ?)")
		val defaultCases = computeInitialCaseCounts(word)
		insert.bindString(1, word)
		insert.bindLong(2, defaultFreqClass.toLong())
		insert.bindLong(3, defaultClassMask)
		insert.bindLong(4, defaultRawFreq.toLong())
		if (defaultPos1 != null) insert.bindString(5, defaultPos1) else insert.bindNull(5)
		if (defaultPos2 != null) insert.bindString(6, defaultPos2) else insert.bindNull(6)
		insert.bindLong(7, defaultCases.lower.toLong())
		insert.bindLong(8, defaultCases.title.toLong())
		insert.bindLong(9, defaultCases.upper.toLong())
		insert.bindLong(10, defaultCases.original.toLong())
		val newWordID = insert.executeInsert().toInt()
		return DbWordStats(newWordID, defaultFreqClass, defaultClassMask, 0, null, defaultCases.lower, defaultCases.title, defaultCases.upper, defaultCases.original)
	}

	fun markWordUsed(word: String) {
		val now = System.currentTimeMillis()
		db.execSQL("UPDATE words SET useCount = useCount + 1, useTime = ? WHERE word = ?", arrayOf(now, word))
		// Ensure row exists in case it was missing
		val changed = db.changedRowCount()
		if (changed == 0) {
			val insert = db.compileStatement("INSERT OR REPLACE INTO words (word, freqClass, classMask, useCount, useTime, rawFreq, PartOfSpeech1, PartOfSpeech2) VALUES (?, ?, ?, 1, ?, 0, NULL, NULL)")
			insert.bindString(1, word)
			insert.bindLong(2, 14L)
			insert.bindLong(3, ClassMasks.CLASS_CUSTOM_WORDS_MASK)
			insert.bindLong(4, now)
			insert.executeInsert()
		}
	}

	fun decrementFreqClass(word: String, minClass: Int) {
		// Read current value
		var curFc: Int? = null
		val c: Cursor = db.rawQuery("SELECT freqClass FROM words WHERE word = ?", arrayOf(word))
		c.use {
			if (it.moveToFirst()) curFc = it.getInt(0)
		}
		if (curFc == null) {
			val stmt = db.compileStatement("INSERT OR REPLACE INTO words (word, freqClass, classMask, useCount, useTime, rawFreq, PartOfSpeech1, PartOfSpeech2) VALUES (?, ?, ?, 0, NULL, 0, NULL, NULL)")
			stmt.bindString(1, word)
			stmt.bindLong(2, minClass.toLong())
			stmt.bindLong(3, ClassMasks.CLASS_JUSTTYPE_MASK)
			stmt.executeInsert()
			return
		}
		val newFc = if (curFc!! > minClass) curFc!! - 1 else curFc!!
		db.execSQL("UPDATE words SET freqClass = ? WHERE word = ?", arrayOf(newFc, word))
	}

	fun ensureCustomWord(word: String, rawFreq: Int = 1000, pos1: String? = "NNP", pos2: String? = null, posEncoded: Int = 0) {
		val c = db.rawQuery("SELECT classMask FROM words WHERE word = ?", arrayOf(word))
		c.use {
			if (it.moveToFirst()) {
				setClassMask(word, ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK)
				return
			}
		}
		val fc = computeFreqClass(rawFreq)
		val stmt =
			db.compileStatement("INSERT OR REPLACE INTO words (word, freqClass, classMask, useCount, useTime, rawFreq, PartOfSpeech1, PartOfSpeech2, lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount, posEncoded) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?, ?, ?, ?, ?)")
		stmt.bindString(1, word)
		stmt.bindLong(2, fc.toLong())
		stmt.bindLong(3, ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK)
		stmt.bindLong(4, rawFreq.toLong())
		if (pos1 != null) stmt.bindString(5, pos1) else stmt.bindNull(5)
		if (pos2 != null) stmt.bindString(6, pos2) else stmt.bindNull(6)
		val cases = computeInitialCaseCounts(word)
		stmt.bindLong(7, cases.lower.toLong())
		stmt.bindLong(8, cases.title.toLong())
		stmt.bindLong(9, cases.upper.toLong())
		stmt.bindLong(10, cases.original.toLong())
		stmt.bindLong(11, posEncoded.toLong())
		stmt.executeInsert()
	}

	fun incrementCaseCount(word: String, form: WordCaseForm, amount: Int = 1) {
		val column = when (form) {
			WordCaseForm.LOWER -> "lowerCaseCount"
			WordCaseForm.TITLE -> "titleCaseCount"
			WordCaseForm.UPPER -> "upperCaseCount"
			WordCaseForm.ORIGINAL -> "originalCaseCount"
		}
		getOrCreateStats(word)
		DebugLogger.log(DebugCategory.ShiftState) {
			"[WordDb.incrementCaseCount] word='$word', column=$column, amount=$amount"
		}
		db.execSQL("UPDATE words SET $column = $column + ? WHERE word = ?", arrayOf(amount, word))
	}

	/**
	 * Ensure the given case bucket for a word is at least as large as the
	 * largest existing bucket. Used when explicitly adding a new case variant
	 * (e.g., an upper-case custom word) so it surfaces alongside existing forms.
	 */
	fun ensureCaseCountAtLeast(word: String, form: WordCaseForm) {
		val stats = getOrCreateStats(word)
		val counts = CaseCounts(
			lower = stats.lowerCaseCount,
			title = stats.titleCaseCount,
			upper = stats.upperCaseCount,
			original = stats.originalCaseCount,
		)
		val maxExisting = listOf(counts.lower, counts.title, counts.upper, counts.original).maxOrNull() ?: 0
		val target = when (form) {
			WordCaseForm.LOWER -> counts.lower
			WordCaseForm.TITLE -> counts.title
			WordCaseForm.UPPER -> counts.upper
			WordCaseForm.ORIGINAL -> counts.original
		}
		if (target >= maxExisting) return

		val updated = when (form) {
			WordCaseForm.LOWER -> counts.copy(lower = maxExisting)
			WordCaseForm.TITLE -> counts.copy(title = maxExisting)
			WordCaseForm.UPPER -> counts.copy(upper = maxExisting)
			WordCaseForm.ORIGINAL -> counts.copy(original = maxExisting)
		}
		DebugLogger.log(DebugCategory.ShiftState) {
			"[WordDb.ensureCaseCountAtLeast] word='$word', form=$form, bumpTo=$maxExisting"
		}
		replaceCaseCounts(word, updated)
	}

	private fun replaceCaseCounts(word: String, counts: CaseCounts) {
		val stmt = db.compileStatement(
			"UPDATE words SET lowerCaseCount = ?, titleCaseCount = ?, upperCaseCount = ?, originalCaseCount = ? WHERE word = ?",
		)
		stmt.bindLong(1, counts.lower.toLong())
		stmt.bindLong(2, counts.title.toLong())
		stmt.bindLong(3, counts.upper.toLong())
		stmt.bindLong(4, counts.original.toLong())
		stmt.bindString(5, word)
		stmt.executeUpdateDelete()
	}

	private fun setClassMask(word: String, mask: Long) {
		db.execSQL(
			"UPDATE words SET classMask = classMask | ? WHERE word = ?",
			arrayOf(mask, word),
		)
	}

	/**
	 * Sets phraseUUID for a word. Returns true if a row was updated, false if no matching row.
	 */
	fun setPhraseUUID(word: String, phraseUUID: String?): Boolean {
		val values = ContentValues().apply {
			if (phraseUUID != null) put("phraseUUID", phraseUUID) else putNull("phraseUUID")
		}
		val affected = db.update("words", values, "word = ?", arrayOf(word))
		return affected > 0
	}

	fun getPhraseUUID(word: String): String? {
		val c = db.rawQuery("SELECT phraseUUID FROM words WHERE word = ?", arrayOf(word))
		c.use {
			if (it.moveToFirst() && !it.isNull(0)) return it.getString(0)
		}
		return null
	}

	fun countJustTypeWords(minFreqClass: Int? = null): Int = if (minFreqClass == null) {
		countForMask(ClassMasks.CLASS_JUSTTYPE_MASK)
	} else {
		countForMaskAndMinFreq(ClassMasks.CLASS_JUSTTYPE_MASK, minFreqClass)
	}

	fun countJustTypeWordsByFreqRange(minFreqClass: Int?, maxFreqClass: Int?): Int {
		if (minFreqClass == null && maxFreqClass == null) {
			return countForMask(ClassMasks.CLASS_JUSTTYPE_MASK)
		}
		val args = mutableListOf<String>()
		val where = StringBuilder("SELECT SUM(count) FROM freq_class_counts WHERE 1=1")
		if (minFreqClass != null) {
			where.append(" AND freqClass <= ?")
			args.add(minFreqClass.toString())
		}
		if (maxFreqClass != null) {
			where.append(" AND freqClass >= ?")
			args.add(maxFreqClass.toString())
		}
		val c = db.rawQuery(where.toString(), args.toTypedArray())
		c.use {
			if (it.moveToFirst()) {
				if (it.isNull(0)) return 0
				return it.getInt(0)
			}
		}
		return 0
	}

	fun countJustTypeWordsByFilters(
		minFreqClass: Int?,
		maxFreqClass: Int?,
		maxUseCount: Int?,
	): Int {
		val args = mutableListOf<String>()
		val where = StringBuilder("SELECT COUNT(1) FROM words WHERE (classMask & ?) != 0")
		args.add(ClassMasks.CLASS_JUSTTYPE_MASK.toString())
		if (minFreqClass != null) {
			where.append(" AND freqClass <= ?")
			args.add(minFreqClass.toString())
		}
		if (maxFreqClass != null) {
			where.append(" AND freqClass >= ?")
			args.add(maxFreqClass.toString())
		}
		if (maxUseCount != null) {
			where.append(" AND useCount <= ?")
			args.add(maxUseCount.toString())
		}
		val c = db.rawQuery(where.toString(), args.toTypedArray())
		c.use {
			return if (it.moveToFirst()) it.getInt(0) else 0
		}
	}

	fun countUserCustomWords(): Int = countForMask(ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK)

	override fun close() {
		db.close()
	}

	fun getMetadata(key: String): String? {
		val c = db.rawQuery("SELECT value FROM metadata WHERE `key` = ?", arrayOf(key))
		return c.use { if (it.moveToFirst()) it.getString(0) else null }
	}

	fun setMetadata(key: String, value: String) {
		db.execSQL(
			"INSERT OR REPLACE INTO metadata (`key`, value) VALUES (?, ?)",
			arrayOf(key, value),
		)
	}

	fun clearClassMaskBits(mask: Long) {
		db.execSQL(
			"UPDATE words SET classMask = classMask & ~? WHERE (classMask & ?) != 0",
			arrayOf(mask, mask),
		)
	}

	fun getCustomWords(): List<String> {
		val out = mutableListOf<String>()
		val c = db.rawQuery(
			"SELECT word FROM words WHERE (classMask & ?) != 0",
			arrayOf(ClassMasks.CLASS_USER_ADDED_CUSTOM_MASK.toString()),
		)
		c.use {
			while (it.moveToNext()) {
				out.add(it.getString(0))
			}
		}
		return out
	}

	fun countForClassMask(mask: Long): Int = countForMask(mask)

	fun countForMaskAndUseCount(mask: Long, maxUseCount: Int?): Int {
		if (maxUseCount == null) return countForMask(mask)
		val c = db.rawQuery(
			"SELECT COUNT(1) FROM words WHERE (classMask & ?) != 0 AND useCount <= ?",
			arrayOf(mask.toString(), maxUseCount.toString()),
		)
		c.use {
			return if (it.moveToFirst()) it.getInt(0) else 0
		}
	}

	fun countForMaskUseCountRange(mask: Long, minUseCount: Int?, maxUseCount: Int?): Int {
		val args = mutableListOf<String>()
		val where = StringBuilder("SELECT COUNT(1) FROM words WHERE (classMask & ?) != 0")
		args.add(mask.toString())
		if (minUseCount != null) {
			where.append(" AND useCount >= ?")
			args.add(minUseCount.toString())
		}
		if (maxUseCount != null) {
			where.append(" AND useCount <= ?")
			args.add(maxUseCount.toString())
		}
		val c = db.rawQuery(where.toString(), args.toTypedArray())
		c.use {
			return if (it.moveToFirst()) it.getInt(0) else 0
		}
	}

	fun getWordsForMaskUseCountRange(
		mask: Long,
		minUseCount: Int?,
		maxUseCount: Int?,
	): List<Pair<String, Int>> {
		val args = mutableListOf<String>()
		val where = StringBuilder("SELECT word, useCount FROM words WHERE (classMask & ?) != 0")
		args.add(mask.toString())
		if (minUseCount != null) {
			where.append(" AND useCount >= ?")
			args.add(minUseCount.toString())
		}
		if (maxUseCount != null) {
			where.append(" AND useCount <= ?")
			args.add(maxUseCount.toString())
		}
		where.append(" ORDER BY useCount ASC, word ASC")
		val c = db.rawQuery(where.toString(), args.toTypedArray())
		val out = mutableListOf<Pair<String, Int>>()
		c.use {
			while (it.moveToNext()) {
				out.add(it.getString(0) to it.getInt(1))
			}
		}
		return out
	}

	fun getWordsForMaskUseCountAndFreqRange(
		mask: Long,
		minUseCount: Int?,
		maxUseCount: Int?,
		minFreqClass: Int?,
		maxFreqClass: Int?,
	): List<Triple<String, Int, Int>> {
		val args = mutableListOf<String>()
		val where = StringBuilder("SELECT word, useCount, freqClass FROM words WHERE (classMask & ?) != 0")
		args.add(mask.toString())
		if (minUseCount != null) {
			where.append(" AND useCount >= ?")
			args.add(minUseCount.toString())
		}
		if (maxUseCount != null) {
			where.append(" AND useCount <= ?")
			args.add(maxUseCount.toString())
		}
		if (minFreqClass != null) {
			where.append(" AND freqClass <= ?")
			args.add(minFreqClass.toString())
		}
		if (maxFreqClass != null) {
			where.append(" AND freqClass >= ?")
			args.add(maxFreqClass.toString())
		}
		where.append(" ORDER BY useCount ASC, word ASC")
		val c = db.rawQuery(where.toString(), args.toTypedArray())
		val out = mutableListOf<Triple<String, Int, Int>>()
		c.use {
			while (it.moveToNext()) {
				out.add(Triple(it.getString(0), it.getInt(1), it.getInt(2)))
			}
		}
		return out
	}

	fun countForMaskUseCountAndFreqRange(
		mask: Long,
		minUseCount: Int?,
		maxUseCount: Int?,
		minFreqClass: Int?,
		maxFreqClass: Int?,
	): Int {
		val args = mutableListOf<String>()
		val where = StringBuilder("SELECT COUNT(1) FROM words WHERE (classMask & ?) != 0")
		args.add(mask.toString())
		if (minUseCount != null) {
			where.append(" AND useCount >= ?")
			args.add(minUseCount.toString())
		}
		if (maxUseCount != null) {
			where.append(" AND useCount <= ?")
			args.add(maxUseCount.toString())
		}
		if (minFreqClass != null) {
			where.append(" AND freqClass <= ?")
			args.add(minFreqClass.toString())
		}
		if (maxFreqClass != null) {
			where.append(" AND freqClass >= ?")
			args.add(maxFreqClass.toString())
		}
		val c = db.rawQuery(where.toString(), args.toTypedArray())
		c.use {
			return if (it.moveToFirst()) it.getInt(0) else 0
		}
	}

	fun beginTransaction() {
		db.beginTransaction()
	}

	fun setTransactionSuccessful() {
		db.setTransactionSuccessful()
	}

	fun endTransaction() {
		db.endTransaction()
	}

	// Collapses N execSQL auto-commits (each its own fsync) into one — the difference
	// between one disk sync and N on slow eMMC storage (cheap tablets: N unbatched
	// writes per keystroke was directly observed causing 700ms-1.3s frame stalls).
	fun <T> runInTransaction(block: () -> T): T {
		db.beginTransaction()
		try {
			val result = block()
			db.setTransactionSuccessful()
			return result
		} finally {
			db.endTransaction()
		}
	}

	fun clearClassMask(mask: Long) {
		db.execSQL(
			"UPDATE words SET classMask = classMask & ?",
			arrayOf(mask.inv().toString()),
		)
	}

	fun mergeVocabularyMasks(sourceMask: Long, targetMask: Long) {
		db.execSQL(
			"UPDATE words SET classMask = (classMask & ?) | ? WHERE (classMask & ?) != 0",
			arrayOf(sourceMask.inv().toString(), targetMask.toString(), sourceMask.toString()),
		)
	}

	fun deleteImportedWordsForClass(mask: Long): List<String> {
		val exactMask = (ClassMasks.CLASS_CUSTOM_WORDS_MASK or mask)
		val words = mutableListOf<String>()
		val c = db.rawQuery(
			"SELECT word FROM words WHERE classMask = ?",
			arrayOf(exactMask.toString()),
		)
		c.use {
			while (it.moveToNext()) {
				words.add(it.getString(0))
			}
		}
		db.execSQL("DELETE FROM words WHERE classMask = ?", arrayOf(exactMask.toString()))
		return words
	}

	fun importWord(word: String, classMask: Long, form: WordCaseForm, rawFreq: Int = 1000) {
		val c = db.rawQuery(
			"SELECT lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount FROM words WHERE word = ?",
			arrayOf(word),
		)
		c.use {
			if (it.moveToFirst()) {
				val lower = it.getInt(0)
				val title = it.getInt(1)
				val upper = it.getInt(2)
				val original = it.getInt(3)
				val maxExisting = listOf(lower, title, upper, original).maxOrNull() ?: 0
				val target = when (form) {
					WordCaseForm.LOWER -> lower + 1
					WordCaseForm.TITLE -> maxExisting + 1
					WordCaseForm.UPPER -> maxExisting + 1
					WordCaseForm.ORIGINAL -> maxExisting + 1
				}
				val updated = when (form) {
					WordCaseForm.LOWER -> CaseCounts(target, title, upper, original)
					WordCaseForm.TITLE -> CaseCounts(lower, target, upper, original)
					WordCaseForm.UPPER -> CaseCounts(lower, title, target, original)
					WordCaseForm.ORIGINAL -> CaseCounts(lower, title, upper, target)
				}
				setClassMask(word, classMask)
				replaceCaseCounts(word, updated)
				return
			}
		}
		val fc = computeFreqClass(rawFreq)
		val cases = when (form) {
			WordCaseForm.LOWER -> CaseCounts(lower = 1, title = 0, upper = 0, original = 0)
			WordCaseForm.TITLE -> CaseCounts(lower = 0, title = 1, upper = 0, original = 0)
			WordCaseForm.UPPER -> CaseCounts(lower = 0, title = 0, upper = 1, original = 0)
			WordCaseForm.ORIGINAL -> CaseCounts(lower = 0, title = 0, upper = 0, original = 1)
		}
		val stmt =
			db.compileStatement("INSERT OR REPLACE INTO words (word, freqClass, classMask, useCount, useTime, rawFreq, PartOfSpeech1, PartOfSpeech2, lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?, ?, ?, ?)")
		stmt.bindString(1, word)
		stmt.bindLong(2, fc.toLong())
		stmt.bindLong(3, classMask)
		stmt.bindLong(4, rawFreq.toLong())
		stmt.bindNull(5)
		stmt.bindNull(6)
		stmt.bindLong(7, cases.lower.toLong())
		stmt.bindLong(8, cases.title.toLong())
		stmt.bindLong(9, cases.upper.toLong())
		stmt.bindLong(10, cases.original.toLong())
		stmt.executeInsert()
	}

	fun importVocabularyWord(word: String, vocabMask: Long, form: WordCaseForm, rawFreq: Int = 1000): Long {
		val c = db.rawQuery(
			"SELECT lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount, classMask FROM words WHERE word = ?",
			arrayOf(word),
		)
		c.use {
			if (it.moveToFirst()) {
				val lower = it.getInt(0)
				val title = it.getInt(1)
				val upper = it.getInt(2)
				val original = it.getInt(3)
				val existingMask = it.getLong(4)
				val maxExisting = listOf(lower, title, upper, original).maxOrNull() ?: 0
				val target = when (form) {
					WordCaseForm.LOWER -> lower + 1
					WordCaseForm.TITLE -> maxExisting + 1
					WordCaseForm.UPPER -> maxExisting + 1
					WordCaseForm.ORIGINAL -> maxExisting + 1
				}
				val updated = when (form) {
					WordCaseForm.LOWER -> CaseCounts(target, title, upper, original)
					WordCaseForm.TITLE -> CaseCounts(lower, target, upper, original)
					WordCaseForm.UPPER -> CaseCounts(lower, title, target, original)
					WordCaseForm.ORIGINAL -> CaseCounts(lower, title, upper, target)
				}
				// Existing JT words should only add the vocab bit (no custom bit).
				setClassMask(word, vocabMask)
				replaceCaseCounts(word, updated)
				return existingMask or vocabMask
			}
		}
		val fc = computeFreqClass(rawFreq)
		val cases = when (form) {
			WordCaseForm.LOWER -> CaseCounts(lower = 1, title = 0, upper = 0, original = 0)
			WordCaseForm.TITLE -> CaseCounts(lower = 0, title = 1, upper = 0, original = 0)
			WordCaseForm.UPPER -> CaseCounts(lower = 0, title = 0, upper = 1, original = 0)
			WordCaseForm.ORIGINAL -> CaseCounts(lower = 0, title = 0, upper = 0, original = 1)
		}
		val newMask = vocabMask or ClassMasks.CLASS_CUSTOM_WORDS_MASK
		val stmt =
			db.compileStatement("INSERT OR REPLACE INTO words (word, freqClass, classMask, useCount, useTime, rawFreq, PartOfSpeech1, PartOfSpeech2, lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount) VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?, ?, ?, ?)")
		stmt.bindString(1, word)
		stmt.bindLong(2, fc.toLong())
		stmt.bindLong(3, newMask)
		stmt.bindLong(4, rawFreq.toLong())
		stmt.bindNull(5)
		stmt.bindNull(6)
		stmt.bindLong(7, cases.lower.toLong())
		stmt.bindLong(8, cases.title.toLong())
		stmt.bindLong(9, cases.upper.toLong())
		stmt.bindLong(10, cases.original.toLong())
		stmt.executeInsert()
		return newMask
	}

	/**
	 * Words whose classMask intersects [mask]. [excludeMask] drops any word carrying one of its
	 * bits — used for the CLASS_OFFENSIVE / CLASS_POTENTIALLY_OFFENSIVE levels selected by the
	 * "Excluded Words" setting, so it takes effect on the next vocabulary reload with no rebuild.
	 */
	// maxFreqClass caps the trie to common-enough words (Battery Saver, lever 4 —
	// docs/.local/plans/battery-saver-mode.md): null = no cap, matching today's behavior.
	fun getWordsWithMask(mask: Long, excludeMask: Long = 0L, maxFreqClass: Int? = null): List<DbWordEntry> {
		val out = mutableListOf<DbWordEntry>()
		val where = StringBuilder("(classMask & ?) != 0 AND (classMask & ?) = 0")
		val args = mutableListOf(mask.toString(), excludeMask.toString())
		if (maxFreqClass != null) {
			where.append(" AND freqClass <= ?")
			args.add(maxFreqClass.toString())
		}
		val c = db.rawQuery(
			"SELECT wordID, word, rawFreq, freqClass, PartOfSpeech1, PartOfSpeech2, classMask, " +
				"useCount, posEncoded, useTime FROM words WHERE $where",
			args.toTypedArray(),
		)
		c.use {
			while (it.moveToNext()) {
				out.add(
					DbWordEntry(
						wordID = it.getInt(0),
						word = it.getString(1),
						rawFreq = it.getInt(2),
						freqClass = it.getInt(3),
						pos1 = if (it.isNull(4)) null else it.getString(4),
						pos2 = if (it.isNull(5)) null else it.getString(5),
						classMask = it.getLong(6),
						useCount = it.getInt(7),
						posEncoded = it.getInt(8),
						useTime = if (it.isNull(9)) null else it.getLong(9),
					),
				)
			}
		}
		return out
	}

	fun hasJustTypeWord(word: String): Boolean {
		val c = db.rawQuery(
			"SELECT classMask FROM words WHERE word = ?",
			arrayOf(word),
		)
		c.use {
			if (it.moveToFirst()) {
				val mask = it.getLong(0)
				return (mask and ClassMasks.CLASS_JUSTTYPE_MASK) != 0L
			}
		}
		return false
	}

	private fun countForMask(mask: Long): Int {
		val c = db.rawQuery(
			"SELECT COUNT(1) FROM words WHERE (classMask & ?) != 0",
			arrayOf(mask.toString()),
		)
		c.use {
			return if (it.moveToFirst()) it.getInt(0) else 0
		}
	}

	private fun countForMaskAndMinFreq(mask: Long, minFreqClass: Int): Int {
		val c = db.rawQuery(
			"SELECT COUNT(1) FROM words WHERE (classMask & ?) != 0 AND freqClass <= ?",
			arrayOf(mask.toString(), minFreqClass.toString()),
		)
		c.use {
			return if (it.moveToFirst()) it.getInt(0) else 0
		}
	}
}

private fun computeInitialCaseCounts(word: String): CaseCounts {
	val letters = word.filter { it.isLetter() }
	val hasLetters = letters.isNotEmpty()
	val allUpper = hasLetters && letters.all { it.isUpperCase() }
	val allLower = hasLetters && letters.all { it.isLowerCase() }
	val titleCase =
		hasLetters && letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() }
	val mixedCase = hasLetters && !allUpper && !allLower && !titleCase
	return when {
		allUpper -> CaseCounts(lower = 0, title = 0, upper = 1, original = 0)
		titleCase -> CaseCounts(lower = 0, title = 1, upper = 0, original = 0)
		allLower -> CaseCounts(lower = 1, title = 0, upper = 0, original = 0)
		mixedCase -> CaseCounts(lower = 0, title = 0, upper = 0, original = 1)
		else -> CaseCounts(lower = 1, title = 0, upper = 0, original = 0)
	}
}

private fun SQLiteDatabase.changedRowCount(): Int {
	val c = rawQuery("SELECT changes()", null)
	c.use {
		return if (it.moveToFirst()) it.getInt(0) else 0
	}
}
