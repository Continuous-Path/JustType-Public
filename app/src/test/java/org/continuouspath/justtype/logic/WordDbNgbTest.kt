package org.continuouspath.justtype.logic

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * NGB table plumbing (docs/.plans/ngram/engine-spec.md): tables exist in every
 * DB, migrate WHOLESALE with the language build (they carry no user state),
 * and the accessors serve the engine's fetch-per-commit pattern.
 */
@RunWith(RobolectricTestRunner::class)
class WordDbNgbTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private fun bareSchema(db: SQLiteDatabase) {
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
		db.execSQL("CREATE TABLE freq_class_counts (freqClass INTEGER PRIMARY KEY, count INTEGER NOT NULL)")
	}

	/** A v2-format source (varint tblob + ngb_words, as BuildWordDbTask now emits). */
	private fun sourceWithNgb(name: String, version: String): File {
		val f = File(tmpDir.root, name)
		SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
			bareSchema(db)
			db.execSQL("INSERT INTO words (word, freqClass) VALUES ('việt', 3)")
			db.execSQL(
				"CREATE TABLE ngb_units (unit_id INTEGER PRIMARY KEY AUTOINCREMENT, first_syl TEXT NOT NULL, " +
					"syls TEXT UNIQUE NOT NULL, marginal INTEGER NOT NULL DEFAULT 0, " +
					"eff1n1 INTEGER NOT NULL DEFAULT 0, flags INTEGER NOT NULL DEFAULT 0, display TEXT)",
			)
			db.execSQL(
				"CREATE TABLE ngb_ctx (ctx TEXT PRIMARY KEY, targets TEXT NOT NULL DEFAULT '', tblob BLOB)",
			)
			db.execSQL("CREATE TABLE ngb_words (id INTEGER PRIMARY KEY, word TEXT NOT NULL)")
			db.execSQL(
				"INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1, display) " +
					"VALUES ('việt', 'việt nam', 100, 5000, 'Việt Nam')",
			)
			db.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('việt', 'việt kiều', 10, 200)")
			db.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('bất', 'bất động sản', 50, 0)")
			db.execSQL("INSERT INTO ngb_words (id, word) VALUES (1, 'mừng')")
			db.execSQL("INSERT INTO ngb_words (id, word) VALUES (2, 'các')")
			db.execSQL(
				"INSERT INTO ngb_ctx (ctx, tblob) VALUES ('chúc', ?)",
				arrayOf(NgbCodec.encode(listOf(1 to 9000L, 2 to 4000L))),
			)
			db.execSQL(
				"INSERT INTO metadata (`key`, value) VALUES (?, ?)",
				arrayOf(WordDb.LANG_BUILD_VERSION_KEY, version),
			)
		}
		return f
	}

	@Test
	fun `migration copies NGB tables wholesale into a pre-NGB active DB`() {
		val src = sourceWithNgb("src.db", "v2")
		val active = File(tmpDir.root, "active.db")
		SQLiteDatabase.openOrCreateDatabase(active, null).use { db ->
			bareSchema(db) // pre-NGB active: no ngb tables at all
			db.execSQL("INSERT INTO words (word, freqClass) VALUES ('việt', 3)")
			db.execSQL("INSERT INTO metadata (`key`, value) VALUES (?, 'v1')", arrayOf(WordDb.LANG_BUILD_VERSION_KEY))
		}

		assertThat(WordDb.migrateLanguageBuild(active, src)).isTrue()

		SQLiteDatabase.openDatabase(active.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
			val units = db.rawQuery("SELECT COUNT(1) FROM ngb_units", null).use {
				it.moveToFirst()
				it.getInt(0)
			}
			assertThat(units).isEqualTo(3)
			// Canonical display orthography survives the wholesale copy.
			val display = db.rawQuery("SELECT display FROM ngb_units WHERE syls='việt nam'", null)
				.use { if (it.moveToFirst()) it.getString(0) else null }
			assertThat(display).isEqualTo("Việt Nam")
		}
		// The migrated row decodes through the standard accessor (blob + ngb_words).
		WordDb.openStandalone(active).use { wordDb ->
			assertThat(wordDb.ngbContextTargets("chúc"))
				.containsExactly("mừng" to 9000L, "các" to 4000L)
				.inOrder()
		}
	}

	@Test
	fun `migration from a legacy text-format source still carries the rows`() {
		// Pre-v2 langpack: ngb_ctx has only the TEXT column, no ngb_words.
		val src = File(tmpDir.root, "legacy-src.db")
		SQLiteDatabase.openOrCreateDatabase(src, null).use { db ->
			bareSchema(db)
			db.execSQL("INSERT INTO words (word, freqClass) VALUES ('việt', 3)")
			db.execSQL(
				"CREATE TABLE ngb_units (unit_id INTEGER PRIMARY KEY AUTOINCREMENT, first_syl TEXT NOT NULL, " +
					"syls TEXT UNIQUE NOT NULL, marginal INTEGER NOT NULL DEFAULT 0, " +
					"eff1n1 INTEGER NOT NULL DEFAULT 0, flags INTEGER NOT NULL DEFAULT 0)",
			)
			db.execSQL("CREATE TABLE ngb_ctx (ctx TEXT PRIMARY KEY, targets TEXT NOT NULL)")
			db.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('chúc', 'mừng:9000|các:4000')")
			db.execSQL(
				"INSERT INTO metadata (`key`, value) VALUES (?, 'legacy1')",
				arrayOf(WordDb.LANG_BUILD_VERSION_KEY),
			)
		}
		val active = File(tmpDir.root, "active-legacy.db")
		SQLiteDatabase.openOrCreateDatabase(active, null).use { db ->
			bareSchema(db)
			db.execSQL("INSERT INTO metadata (`key`, value) VALUES (?, 'v0')", arrayOf(WordDb.LANG_BUILD_VERSION_KEY))
		}

		assertThat(WordDb.migrateLanguageBuild(active, src)).isTrue()

		WordDb.openStandalone(active).use { wordDb ->
			assertThat(wordDb.ngbContextTargets("chúc"))
				.containsExactly("mừng" to 9000L, "các" to 4000L)
				.inOrder()
		}
	}

	@Test
	fun `migration from a pre-NGB source leaves empty NGB tables, no crash`() {
		val src = File(tmpDir.root, "old-src.db")
		SQLiteDatabase.openOrCreateDatabase(src, null).use { db ->
			bareSchema(db)
			db.execSQL("INSERT INTO words (word, freqClass) VALUES ('việt', 3)")
			db.execSQL("INSERT INTO metadata (`key`, value) VALUES (?, 'v3')", arrayOf(WordDb.LANG_BUILD_VERSION_KEY))
		}
		val active = File(tmpDir.root, "active2.db")
		SQLiteDatabase.openOrCreateDatabase(active, null).use { db ->
			bareSchema(db)
			db.execSQL("INSERT INTO metadata (`key`, value) VALUES (?, 'v2')", arrayOf(WordDb.LANG_BUILD_VERSION_KEY))
		}

		assertThat(WordDb.migrateLanguageBuild(active, src)).isTrue()

		SQLiteDatabase.openDatabase(active.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
			val units = db.rawQuery("SELECT COUNT(1) FROM ngb_units", null).use {
				it.moveToFirst()
				it.getInt(0)
			}
			assertThat(units).isEqualTo(0)
		}
	}

	@Test
	fun `accessors serve context rows and weighted first-syllable units`() {
		val dbFile = File(tmpDir.root, "standalone.db")
		val wordDb = WordDb.openStandalone(dbFile)
		try {
			// openStandalone created empty NGB tables; seed through raw SQL.
			SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
				raw.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('việt', 'việt nam', 100, 5000)")
				raw.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('việt', 'việt kiều', 10, 200)")
				raw.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('việt', 'việt vị', 5, 0)")
				// v2 blob row, incl. a multi-syllable target.
				raw.execSQL("INSERT INTO ngb_words (id, word) VALUES (1, 'mừng')")
				raw.execSQL("INSERT INTO ngb_words (id, word) VALUES (2, 'các')")
				raw.execSQL("INSERT INTO ngb_words (id, word) VALUES (3, 'việt nam')")
				raw.execSQL(
					"INSERT INTO ngb_ctx (ctx, tblob) VALUES ('chúc', ?)",
					arrayOf(NgbCodec.encode(listOf(1 to 9000L, 2 to 4000L, 3 to 4000L))),
				)
				// Legacy text row (pre-v2 installed pack): still served.
				raw.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('cũ', 'xưa:70|kỹ:30')")
			}
			assertThat(wordDb.ngbContextTargets("chúc"))
				.containsExactly("mừng" to 9000L, "các" to 4000L, "việt nam" to 4000L)
				.inOrder()
			assertThat(wordDb.ngbContextTargets("cũ"))
				.containsExactly("xưa" to 70L, "kỹ" to 30L)
				.inOrder()
			assertThat(wordDb.ngbContextTargets("xyz")).isNull()
			// eff1n1=0 rows are recognition-only: absent from the prediction query.
			assertThat(wordDb.ngbUnitsByFirstSyl("việt").map { it.syls })
				.containsExactly("việt nam", "việt kiều").inOrder()
			assertThat(wordDb.ngbUnitStrings()).contains("việt vị")
		} finally {
			runCatching { wordDb.close() }
		}
	}

	@Test
	fun `codec round-trips ids and effs including ties and large values`() {
		val pairs = listOf(
			1 to 6_184_413L, // multi-byte eff (VN-scale)
			20_000 to 6_184_413L, // tie: zero delta, multi-byte id
			3 to 500L,
			2 to 500L, // tie again
			70_000 to 0L, // eff 0 floor
		)
		assertThat(NgbCodec.decode(NgbCodec.encode(pairs))).isEqualTo(pairs)
		// Unknown version byte and truncated payloads refuse, not garble.
		assertThat(NgbCodec.decode(byteArrayOf(9, 1, 1))).isNull()
		val truncated = NgbCodec.encode(pairs).let { it.copyOf(it.size - 1) }
		assertThat(NgbCodec.decode(truncated)).isNull()
		assertThat(NgbCodec.decode(ByteArray(0))).isNull()
	}
}
