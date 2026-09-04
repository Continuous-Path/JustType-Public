package org.continuouspath.justtype.logic

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * NgbEngine.poolFor on seeded standalone DBs: the entry-state gate, dual-source
 * max-merge, corrupt-row tolerance, ordering/capping, and the user tier.
 */
@RunWith(RobolectricTestRunner::class)
class NgbEngineTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private val openDbs = mutableListOf<WordDb>()

	@After fun tearDown() {
		openDbs.forEach { runCatching { it.close() } }
	}

	/** openStandalone creates empty NGB tables; [seed] runs raw SQL on top. */
	private fun standaloneDb(name: String, seed: (SQLiteDatabase) -> Unit = {}): WordDb {
		val f = File(tmpDir.root, name)
		val wordDb = WordDb.openStandalone(f)
		openDbs.add(wordDb)
		SQLiteDatabase.openOrCreateDatabase(f, null).use(seed)
		return wordDb
	}

	@Test fun `closed gate drops 1N1 remainders but keeps ctx-row targets`() {
		val db = standaloneDb("gate.db") { raw ->
			raw.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('chúc', 'mừng:9000')")
			raw.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('chúc', 'chúc phúc', 10, 5000)")
		}
		val engine = NgbEngine(db)
		val open = engine.poolFor("chúc", gateOpen = true)
		assertThat(open.map { it.syls }).containsExactly(listOf("mừng"), listOf("phúc"))
		val closed = engine.poolFor("chúc", gateOpen = false)
		assertThat(closed.map { it.syls }).containsExactly(listOf("mừng"))
	}

	@Test fun `dual-source target takes the max of ctx and 1N1 weights`() {
		val db = standaloneDb("max.db") { raw ->
			raw.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('chúc', 'phúc:100|tụng:8000')")
			raw.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('chúc', 'chúc phúc', 10, 5000)")
			raw.execSQL("INSERT INTO ngb_units (first_syl, syls, marginal, eff1n1) VALUES ('chúc', 'chúc tụng', 10, 300)")
		}
		val pool = NgbEngine(db).poolFor("chúc", gateOpen = true)
		val byTarget = pool.associate { it.syls.single() to it.eff }
		assertThat(byTarget["phúc"]).isEqualTo(5000L)
		assertThat(byTarget["tụng"]).isEqualTo(8000L)
	}

	@Test fun `corrupt packed parts are skipped without crashing`() {
		val db = standaloneDb("corrupt.db") { raw ->
			raw.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('ctx', 'good:100|garbage|a:b:notanumber|:50|ok:50')")
		}
		val pool = NgbEngine(db).poolFor("ctx", gateOpen = true)
		assertThat(pool.map { it.syls.single() to it.eff }).containsExactly("good" to 100L, "ok" to 50L)
	}

	@Test fun `pool is sorted descending, singles beyond POOL_SIZE retained as deep`() {
		val packed = (1..70).joinToString("|") { "w$it:${it * 10}" }
		val db = standaloneDb("cap.db") { raw ->
			raw.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('ctx', ?)", arrayOf(packed))
		}
		val pool = NgbEngine(db).poolFor("ctx", gateOpen = true)
		assertThat(pool).hasSize(70)
		assertThat(pool.first().syls).containsExactly("w70")
		val effs = pool.map { it.eff }
		assertThat(effs).isEqualTo(effs.sortedDescending())
		assertThat(pool.take(NgbEngine.POOL_SIZE).none { it.deep }).isTrue()
		assertThat(pool.drop(NgbEngine.POOL_SIZE).all { it.deep }).isTrue()
	}

	@Test fun `a user-tier row lifts a low-eff target above a high-eff one`() {
		val db = standaloneDb("user.db") { raw ->
			raw.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('ctx', 'high:9000|low:10')")
		}
		val custom = standaloneDb("custom.db")
		custom.ngbUserBump("TestLang", "ctx", "low")
		val pool = NgbEngine(db, custom, "TestLang").poolFor("ctx", gateOpen = true)
		assertThat(pool.first().syls).containsExactly("low")
		assertThat(pool.first().eff).isEqualTo(10L + NgbEngine.USER_BOOST)
		assertThat(pool.first().userUsed).isTrue()
		assertThat(pool.single { it.syls == listOf("high") }.userUsed).isFalse()
	}

	@Test fun `a multi-syllable user target merges with its static pool entry`() {
		val db = standaloneDb("user2.db") { raw ->
			raw.execSQL("INSERT INTO ngb_ctx (ctx, targets) VALUES ('ctx', 'năm mới:20|big:9000')")
		}
		val custom = standaloneDb("custom2.db")
		custom.ngbUserBump("TestLang", "ctx", "năm mới")
		val pool = NgbEngine(db, custom, "TestLang").poolFor("ctx", gateOpen = true)
		assertThat(pool).hasSize(2) // merged with the static entry, not duplicated
		assertThat(pool.first().syls).isEqualTo(listOf("năm", "mới"))
		assertThat(pool.first().eff).isEqualTo(20L + NgbEngine.USER_BOOST)
		assertThat(pool.first().multi).isTrue()
		assertThat(pool.first().userUsed).isTrue()
	}
}
