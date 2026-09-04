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
 * Langpack migration must carry ngb_units.display across builds — proper-noun
 * overrides ("hồ chí minh" -> "Hồ Chí Minh") live only there — while still
 * accepting older source packs whose ngb_units predate the column.
 */
@RunWith(RobolectricTestRunner::class)
class WordDbNgbMigrationDisplayTest {

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

	private fun source(name: String, version: String, withDisplay: Boolean): File {
		val f = File(tmpDir.root, name)
		SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
			bareSchema(db)
			db.execSQL("INSERT INTO words (word, freqClass) VALUES ('hồ', 3)")
			val displayCol = if (withDisplay) ", display TEXT" else ""
			db.execSQL(
				"CREATE TABLE ngb_units (unit_id INTEGER PRIMARY KEY AUTOINCREMENT, first_syl TEXT NOT NULL, " +
					"syls TEXT UNIQUE NOT NULL, marginal INTEGER NOT NULL DEFAULT 0, " +
					"eff1n1 INTEGER NOT NULL DEFAULT 0, flags INTEGER NOT NULL DEFAULT 0$displayCol)",
			)
			db.execSQL("CREATE TABLE ngb_ctx (ctx TEXT PRIMARY KEY, targets TEXT NOT NULL)")
			if (withDisplay) {
				db.execSQL(
					"INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1, display) " +
						"VALUES ('hồ', 'hồ chí minh', 10, 500, 'Hồ Chí Minh')",
				)
			} else {
				db.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('hồ', 'hồ chí minh', 10, 500)")
			}
			db.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('chủ tịch', 'hồ chí minh:9000')")
			db.execSQL(
				"INSERT INTO metadata (`key`, value) VALUES (?, ?)",
				arrayOf(WordDb.LANG_BUILD_VERSION_KEY, version),
			)
		}
		return f
	}

	private fun activeAtV1(): File {
		val f = File(tmpDir.root, "active.db")
		SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
			bareSchema(db)
			db.execSQL("INSERT INTO metadata (`key`, value) VALUES (?, 'v1')", arrayOf(WordDb.LANG_BUILD_VERSION_KEY))
		}
		return f
	}

	@Test
	fun `migration preserves proper-noun display overrides`() {
		val active = activeAtV1()
		assertThat(WordDb.migrateLanguageBuild(active, source("src.db", "v2", withDisplay = true))).isTrue()

		WordDb.openStandalone(active).use { db ->
			assertThat(db.ngbUnitDisplayOverrides()).containsEntry("hồ chí minh", "Hồ Chí Minh")
		}
	}

	@Test
	fun `pre-display source pack still migrates with empty overrides`() {
		val active = activeAtV1()
		assertThat(WordDb.migrateLanguageBuild(active, source("src.db", "v2", withDisplay = false))).isTrue()

		WordDb.openStandalone(active).use { db ->
			assertThat(db.ngbUnitDisplayOverrides()).isEmpty()
			assertThat(db.ngbUnitStrings()).containsExactly("hồ chí minh")
		}
	}
}
