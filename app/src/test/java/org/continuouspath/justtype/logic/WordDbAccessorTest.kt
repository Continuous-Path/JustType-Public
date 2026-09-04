package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WordDbAccessorTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var accessor: WordDbAccessor

	@Before fun setUp() = runBlocking {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		accessor = WordDbAccessor.open(tmpDir.root, app.assets)
	}

	@After fun tearDown() {
		accessor.close()
	}

	// 1. Read pass-through.
	@Test fun `getWordByID returns null for unknown id`() = runTest {
		assertThat(accessor.getWordByID(-1)).isNull()
	}

	// 2. Write pass-through with observable post-condition.
	@Test fun `markWordUsed creates a row whose useCount is at least 1`() = runTest {
		val word = "testdelegateword"
		accessor.markWordUsed(word)
		val id = accessor.getWordIDByWord(word)
		assertThat(id).isNotNull()
		val stats = accessor.getWordStatsByID(id!!)
		assertThat(stats).isNotNull()
		assertThat(stats!!.useCount).isAtLeast(1)
	}

	// 3. Transaction success commits side effects.
	@Test fun `transaction commits when block succeeds`() = runTest {
		val word = "transactioncommitword"
		val result = accessor.transaction { db ->
			db.markWordUsed(word)
			"ok"
		}
		assertThat(result).isEqualTo("ok")
		assertThat(accessor.getWordIDByWord(word)).isNotNull()
	}

	// 4. Transaction rollback when block throws.
	@Test fun `transaction rolls back when block throws`() = runTest {
		val word = "transactionrollbackword"
		runCatching {
			accessor.transaction { db ->
				db.markWordUsed(word)
				error("intentional failure")
			}
		}
		assertThat(accessor.getWordIDByWord(word)).isNull()
	}

	// 5. Concurrent reads all complete (the dispatcher serializes them — none deadlock).
	@Test fun `concurrent reads all complete under limitedParallelism(1)`() = runBlocking {
		val completed = mutableListOf<Int>()
		val mutex = Any()
		val jobs = (0 until 20).map { i ->
			launch {
				accessor.getWordByID(i)
				synchronized(mutex) { completed.add(i) }
			}
		}
		jobs.forEach { it.join() }
		assertThat(completed).hasSize(20)
	}

	// 6. Single-writer ordering — `limitedParallelism(1)` serializes non-suspending
	// work on the dispatcher thread. If two transactions both hold the thread (via a
	// blocking sleep), peak observed concurrency must be 1.
	@Test fun `concurrent transactions never run simultaneously`() = runBlocking {
		val active = AtomicInteger(0)
		val peak = AtomicInteger(0)
		val jobs = (0 until 5).map {
			launch {
				accessor.transaction { _ ->
					val now = active.incrementAndGet()
					peak.updateAndGet { it.coerceAtLeast(now) }
					Thread.sleep(25)
					active.decrementAndGet()
				}
			}
		}
		jobs.forEach { it.join() }
		assertThat(peak.get()).isEqualTo(1)
	}

	// 7. close() forwards — re-opening on the same path doesn't blow up.
	@Test fun `close releases the inner db so re-open succeeds`() = runBlocking {
		accessor.close()
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		val reopened = WordDbAccessor.open(tmpDir.root, app.assets)
		reopened.close()
	}

	// 8. relativeTime is non-suspend — callable from a non-coroutine test body.
	@Test fun `relativeTime is callable without coroutine context`() {
		val t = accessor.relativeTime()
		assertThat(t).isAtLeast(0)
	}

	// 9. absoluteToRelativeTime is non-suspend and handles null + zero.
	@Test fun `absoluteToRelativeTime returns 0 for null and zero`() {
		assertThat(accessor.absoluteToRelativeTime(null)).isEqualTo(0)
		assertThat(accessor.absoluteToRelativeTime(0L)).isEqualTo(0)
	}

	// 10. activeDbName / computeFreqClass — companion pass-throughs.
	@Test fun `companion pass-throughs match WordDb directly`() {
		assertThat(WordDbAccessor.activeDbName("English")).isEqualTo("EnglishDbActive.db")
		assertThat(WordDbAccessor.computeFreqClass(40000)).isEqualTo(1)
		assertThat(WordDbAccessor.computeFreqClass(0)).isEqualTo(14)
	}
}
