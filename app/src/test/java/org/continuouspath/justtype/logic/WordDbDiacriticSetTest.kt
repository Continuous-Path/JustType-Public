package org.continuouspath.justtype.logic

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.ClassMasks
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Upgrade-path coverage for [WordDb.open]: an existing active DB that predates the diacritic feature has no
 * `diacriticSet` metadata row, so open() must backfill it by deriving from the built-in (JustType) words —
 * and must leave an existing row untouched (idempotent). The shipped asset already carries the row (baked in
 * by BuildEnglishDbTask), so this only exercises the migration branch.
 */
@RunWith(RobolectricTestRunner::class)
class WordDbDiacriticSetTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private fun seedActiveDbWithoutDiacriticRow(words: List<String>): File {
		val file = File(tmpDir.root, WordDb.activeDbName("English"))
		SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
			db.execSQL(
				"CREATE TABLE words (wordID INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT UNIQUE NOT NULL, " +
					"freqClass INTEGER NOT NULL, classMask INTEGER NOT NULL DEFAULT 0)",
			)
			db.execSQL("CREATE TABLE metadata (`key` TEXT PRIMARY KEY, value TEXT NOT NULL)")
			words.forEach {
				db.execSQL(
					"INSERT INTO words (word, freqClass, classMask) VALUES (?, 1, ?)",
					arrayOf<Any>(it, ClassMasks.CLASS_JUSTTYPE_MASK),
				)
			}
		}
		return file
	}

	@Test fun `open backfills diacriticSet derived from JustType words when the row is absent`() {
		seedActiveDbWithoutDiacriticRow(listOf("café", "naïve", "hello"))
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		WordDb.open(tmpDir.root, app.assets, "English").use { db ->
			assertThat(db.getMetadata("diacriticSet")).isEqualTo("éï") // é, ï — codepoint-sorted
		}
	}

	@Test fun `open leaves an existing diacriticSet row untouched (idempotent)`() {
		val file = seedActiveDbWithoutDiacriticRow(listOf("café"))
		SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
			db.execSQL("INSERT INTO metadata (`key`, value) VALUES ('diacriticSet', 'preexisting')")
		}
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		WordDb.open(tmpDir.root, app.assets, "English").use { db ->
			assertThat(db.getMetadata("diacriticSet")).isEqualTo("preexisting") // not re-derived
		}
	}
}
