package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ngb_user tier plumbing on a standalone custom DB: the per-row count cap and
 * language scoping of the (lang, ctx, target) key.
 */
@RunWith(RobolectricTestRunner::class)
class WordDbNgbUserTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var db: WordDb

	private fun openDb(): WordDb {
		db = WordDb.openStandalone(File(tmpDir.root, "custom.db"))
		return db
	}

	@After fun tearDown() {
		runCatching { db.close() }
	}

	@Test fun `bump count caps at 255`() {
		val db = openDb()
		repeat(300) { db.ngbUserBump("English", "hello", "world") }
		assertThat(db.ngbUserRows("English", "hello")).containsExactly("world" to 255)
	}

	@Test fun `bumps are isolated per language`() {
		val db = openDb()
		db.ngbUserBump("English", "hello", "world")
		assertThat(db.ngbUserRows("English", "hello")).containsExactly("world" to 1)
		assertThat(db.ngbUserRows("TiengViet", "hello")).isEmpty()
	}
}
