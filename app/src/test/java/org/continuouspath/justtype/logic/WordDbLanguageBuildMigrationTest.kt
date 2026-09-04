package org.continuouspath.justtype.logic

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.ClassMasks
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Coverage for [WordDb.migrateLanguageBuild]: the active DB is seeded once and never re-copied,
 * so a rebuilt corpus only reaches an existing install through this migration. What the user owns
 * (learned stats, imported vocabularies, phrase links, words they have actually used) must survive
 * it; everything else takes the new build.
 */
@RunWith(RobolectricTestRunner::class)
class WordDbLanguageBuildMigrationTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private val importedBit = 1L shl 7

	private fun schema(db: SQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE words (wordID INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT UNIQUE NOT NULL, " +
				"freqClass INTEGER NOT NULL, classMask INTEGER NOT NULL DEFAULT 0, " +
				"useCount INTEGER NOT NULL DEFAULT 0, useTime INTEGER, rawFreq INTEGER NOT NULL DEFAULT 0, " +
				"PartOfSpeech1 TEXT, PartOfSpeech2 TEXT, lowerCaseCount INTEGER NOT NULL DEFAULT 0, " +
				"titleCaseCount INTEGER NOT NULL DEFAULT 0, upperCaseCount INTEGER NOT NULL DEFAULT 0, " +
				"originalCaseCount INTEGER NOT NULL DEFAULT 0, phraseUUID TEXT, " +
				"posEncoded INTEGER NOT NULL DEFAULT 0)",
		)
		db.execSQL("CREATE TABLE metadata (`key` TEXT PRIMARY KEY, value TEXT NOT NULL)")
		db.execSQL("CREATE TABLE IF NOT EXISTS freq_class_counts (freqClass INTEGER PRIMARY KEY, count INTEGER NOT NULL)")
	}

	/** One words-table row; defaults describe an unused, build-owned word. */
	private data class Row(
		val word: String,
		val freqClass: Int = 5,
		val rawFreq: Int = 100,
		val mask: Long = ClassMasks.CLASS_JUSTTYPE_MASK,
		val useCount: Int = 0,
		val lower: Int = 1,
		val title: Int = 0,
		val posEncoded: Int = 0,
		val phraseUUID: String? = null,
	)

	private fun insert(db: SQLiteDatabase, r: Row) {
		db.execSQL(
			"INSERT INTO words (word, freqClass, classMask, useCount, rawFreq, lowerCaseCount, " +
				"titleCaseCount, posEncoded, phraseUUID) VALUES (?,?,?,?,?,?,?,?,?)",
			arrayOf<Any?>(
				r.word, r.freqClass, r.mask, r.useCount, r.rawFreq,
				r.lower, r.title, r.posEncoded, r.phraseUUID,
			),
		)
	}

	/** The shipped asset for a build: only build-owned rows, stamped with its version. */
	private fun buildSource(name: String, version: String, body: (SQLiteDatabase) -> Unit): File {
		val f = File(tmpDir.root, name)
		SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
			schema(db)
			body(db)
			db.execSQL(
				"INSERT INTO metadata (`key`, value) VALUES (?, ?)",
				arrayOf(WordDb.LANG_BUILD_VERSION_KEY, version),
			)
		}
		return f
	}

	private fun row(file: File, word: String): Map<String, Any?>? = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
		db.rawQuery(
			"SELECT freqClass, classMask, useCount, lowerCaseCount, titleCaseCount, " +
				"posEncoded, phraseUUID, rawFreq FROM words WHERE word = ?",
			arrayOf(word),
		).use {
			if (!it.moveToFirst()) return@use null
			mapOf(
				"freqClass" to it.getInt(0),
				"classMask" to it.getLong(1),
				"useCount" to it.getInt(2),
				"lower" to it.getInt(3),
				"title" to it.getInt(4),
				"posEncoded" to it.getInt(5),
				"phraseUUID" to it.getString(6),
				"rawFreq" to it.getInt(7),
			)
		}
	}

	/**
	 * v1 shipped {kept, reranked, dropped-unused, dropped-used}; the user then used some of them,
	 * imported a vocabulary word, and linked a phrase. v2 reranks one word, adds one and drops two.
	 */
	private fun scenario(): Pair<File, File> {
		val active = File(tmpDir.root, WordDb.activeDbName("English"))
		SQLiteDatabase.openOrCreateDatabase(active, null).use { db ->
			schema(db)
			insert(db, Row("kept", freqClass = 5, rawFreq = 100))
			insert(db, Row("reranked", freqClass = 9, rawFreq = 10, useCount = 4, lower = 3, title = 7, posEncoded = 0x0100_0003))
			insert(db, Row("droppedUnused", freqClass = 9, rawFreq = 5))
			insert(db, Row("droppedUsed", freqClass = 9, rawFreq = 5, useCount = 2))
			insert(db, Row("importedWord", mask = importedBit, rawFreq = 1))
			insert(db, Row("phraseAnchor", useCount = 1, phraseUUID = "uuid-1"))
			db.execSQL(
				"INSERT INTO metadata (`key`, value) VALUES (?, ?)",
				arrayOf(WordDb.LANG_BUILD_VERSION_KEY, "v1"),
			)
		}
		val source = buildSource("EnglishDb.db", "v2") { db ->
			insert(db, Row("kept", freqClass = 5, rawFreq = 100))
			// new build promotes it and ships fresh case seeds + a different CaseType byte
			insert(db, Row("reranked", freqClass = 2, rawFreq = 9000, lower = 1, title = 0, posEncoded = 0x0200_0001))
			insert(db, Row("brandNew", freqClass = 3, rawFreq = 900))
			insert(db, Row("phraseAnchor"))
		}
		return active to source
	}

	@Test fun `migration runs only when the build version differs`() {
		val (active, source) = scenario()
		assertThat(WordDb.migrateLanguageBuild(active, source)).isTrue()
		// Second call is a no-op: the stamp now matches.
		assertThat(WordDb.migrateLanguageBuild(active, source)).isFalse()
	}

	@Test fun `an unused word takes the new build's ranking and case seeds`() {
		val (active, source) = scenario()
		WordDb.migrateLanguageBuild(active, source)
		val kept = row(active, "kept")!!
		assertThat(kept["freqClass"]).isEqualTo(5)
		val fresh = row(active, "brandNew")!!
		assertThat(fresh["freqClass"]).isEqualTo(3)
		assertThat(fresh["rawFreq"]).isEqualTo(900)
	}

	@Test fun `a used word keeps its learned stats but takes the new ranking`() {
		val (active, source) = scenario()
		WordDb.migrateLanguageBuild(active, source)
		val r = row(active, "reranked")!!
		// new build's ranking wins ...
		assertThat(r["freqClass"]).isEqualTo(2)
		assertThat(r["rawFreq"]).isEqualTo(9000)
		// ... while the user's learned case counts and use count survive
		assertThat(r["useCount"]).isEqualTo(4)
		assertThat(r["lower"]).isEqualTo(3)
		assertThat(r["title"]).isEqualTo(7)
		// POS bytes come from the new build, the mutable CaseType byte from the user
		assertThat((r["posEncoded"] as Int) and 0xFF).isEqualTo(3)
		assertThat((r["posEncoded"] as Int) ushr 24).isEqualTo(2)
	}

	@Test fun `words the user has used are never taken away by an update`() {
		val (active, source) = scenario()
		WordDb.migrateLanguageBuild(active, source)
		assertThat(row(active, "droppedUsed")).isNotNull()
		assertThat(row(active, "droppedUnused")).isNull()
	}

	@Test fun `imported vocabulary survives and keeps its class bit`() {
		val (active, source) = scenario()
		WordDb.migrateLanguageBuild(active, source)
		val imported = row(active, "importedWord")!!
		assertThat((imported["classMask"] as Long) and importedBit).isEqualTo(importedBit)
	}

	@Test fun `phrase links survive`() {
		val (active, source) = scenario()
		WordDb.migrateLanguageBuild(active, source)
		assertThat(row(active, "phraseAnchor")!!["phraseUUID"]).isEqualTo("uuid-1")
	}

	@Test fun `a source without a build stamp leaves the active DB alone`() {
		val (active, _) = scenario()
		val unstamped = File(tmpDir.root, "Unstamped.db")
		SQLiteDatabase.openOrCreateDatabase(unstamped, null).use { db ->
			schema(db)
			insert(db, Row("kept"))
		}
		assertThat(WordDb.migrateLanguageBuild(active, unstamped)).isFalse()
		assertThat(row(active, "droppedUnused")).isNotNull()
	}

	@Test fun `freq class counts are rebuilt for build-owned words only`() {
		val (active, source) = scenario()
		WordDb.migrateLanguageBuild(active, source)
		val total = SQLiteDatabase.openDatabase(active.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
			db.rawQuery("SELECT SUM(count) FROM freq_class_counts", null)
				.use { if (it.moveToFirst()) it.getInt(0) else 0 }
		}
		// kept, reranked, brandNew, phraseAnchor, droppedUsed — importedWord is not build-owned.
		assertThat(total).isEqualTo(5)
	}
}
