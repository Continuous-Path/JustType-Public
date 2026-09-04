package org.continuouspath.justtype.logic

import android.content.res.AssetManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Dispatcher-bounded suspend wrapper around [WordDb]. */
class WordDbAccessor @VisibleForTesting internal constructor(
	private val db: WordDb,
	private val dispatcher: CoroutineDispatcher,
) : Closeable {

	fun relativeTime(): Int = db.relativeTime()

	fun absoluteToRelativeTime(absTimeMs: Long?): Int = db.absoluteToRelativeTime(absTimeMs)

	suspend fun getWordByID(wordID: Int): String? = withContext(dispatcher) { db.getWordByID(wordID) }

	suspend fun getWordStatsByID(wordID: Int): DbWordStats? = withContext(dispatcher) { db.getWordStatsByID(wordID) }

	suspend fun getWordIDByWord(word: String): Int? = withContext(dispatcher) { db.getWordIDByWord(word) }

	suspend fun getPosEncoded(word: String): Int = withContext(dispatcher) { db.getPosEncoded(word) }

	suspend fun getCaseCountsByID(wordID: Int): CaseCounts? = withContext(dispatcher) { db.getCaseCountsByID(wordID) }

	suspend fun getPhraseUUIDByID(wordID: Int): String? = withContext(dispatcher) { db.getPhraseUUIDByID(wordID) }

	suspend fun getOrCreateStats(
		word: String,
		defaultFreqClass: Int = 14,
		defaultRawFreq: Int = 0,
		defaultPos1: String? = null,
		defaultPos2: String? = null,
		defaultClassMask: Long = org.continuouspath.justtype.ClassMasks.CLASS_JUSTTYPE_MASK,
	): DbWordStats = withContext(dispatcher) {
		db.getOrCreateStats(word, defaultFreqClass, defaultRawFreq, defaultPos1, defaultPos2, defaultClassMask)
	}

	suspend fun getPhraseUUID(word: String): String? = withContext(dispatcher) { db.getPhraseUUID(word) }

	suspend fun countJustTypeWords(minFreqClass: Int? = null): Int = withContext(dispatcher) { db.countJustTypeWords(minFreqClass) }

	suspend fun countJustTypeWordsByFreqRange(minFreqClass: Int?, maxFreqClass: Int?): Int = withContext(dispatcher) { db.countJustTypeWordsByFreqRange(minFreqClass, maxFreqClass) }

	suspend fun countJustTypeWordsByFilters(
		minFreqClass: Int?,
		maxFreqClass: Int?,
		maxUseCount: Int?,
	): Int = withContext(dispatcher) {
		db.countJustTypeWordsByFilters(minFreqClass, maxFreqClass, maxUseCount)
	}

	suspend fun countUserCustomWords(): Int = withContext(dispatcher) { db.countUserCustomWords() }

	suspend fun getMetadata(key: String): String? = withContext(dispatcher) { db.getMetadata(key) }

	suspend fun getCustomWords(): List<String> = withContext(dispatcher) { db.getCustomWords() }

	suspend fun countForClassMask(mask: Long): Int = withContext(dispatcher) { db.countForClassMask(mask) }

	suspend fun countForMaskAndUseCount(mask: Long, maxUseCount: Int?): Int = withContext(dispatcher) { db.countForMaskAndUseCount(mask, maxUseCount) }

	suspend fun countForMaskUseCountRange(mask: Long, minUseCount: Int?, maxUseCount: Int?): Int = withContext(dispatcher) { db.countForMaskUseCountRange(mask, minUseCount, maxUseCount) }

	suspend fun getWordsForMaskUseCountRange(
		mask: Long,
		minUseCount: Int?,
		maxUseCount: Int?,
	): List<Pair<String, Int>> = withContext(dispatcher) {
		db.getWordsForMaskUseCountRange(mask, minUseCount, maxUseCount)
	}

	suspend fun getWordsForMaskUseCountAndFreqRange(
		mask: Long,
		minUseCount: Int?,
		maxUseCount: Int?,
		minFreqClass: Int?,
		maxFreqClass: Int?,
	): List<Triple<String, Int, Int>> = withContext(dispatcher) {
		db.getWordsForMaskUseCountAndFreqRange(mask, minUseCount, maxUseCount, minFreqClass, maxFreqClass)
	}

	suspend fun countForMaskUseCountAndFreqRange(
		mask: Long,
		minUseCount: Int?,
		maxUseCount: Int?,
		minFreqClass: Int?,
		maxFreqClass: Int?,
	): Int = withContext(dispatcher) {
		db.countForMaskUseCountAndFreqRange(mask, minUseCount, maxUseCount, minFreqClass, maxFreqClass)
	}

	suspend fun getWordsWithMask(mask: Long): List<DbWordEntry> = withContext(dispatcher) { db.getWordsWithMask(mask) }

	suspend fun hasJustTypeWord(word: String): Boolean = withContext(dispatcher) { db.hasJustTypeWord(word) }

	suspend fun updatePosEncoded(word: String, posEncoded: Int) = withContext(dispatcher) { db.updatePosEncoded(word, posEncoded) }

	suspend fun updatePosEncodedByID(wordID: Int, posEncoded: Int) = withContext(dispatcher) { db.updatePosEncodedByID(wordID, posEncoded) }

	suspend fun markWordUsedByID(wordID: Int) = withContext(dispatcher) { db.markWordUsedByID(wordID) }

	suspend fun incrementCaseCountByID(wordID: Int, form: WordCaseForm, amount: Int = 1) = withContext(dispatcher) { db.incrementCaseCountByID(wordID, form, amount) }

	suspend fun decrementFreqClassByID(wordID: Int, minClass: Int) = withContext(dispatcher) { db.decrementFreqClassByID(wordID, minClass) }

	suspend fun markWordUsed(word: String) = withContext(dispatcher) { db.markWordUsed(word) }

	suspend fun decrementFreqClass(word: String, minClass: Int) = withContext(dispatcher) { db.decrementFreqClass(word, minClass) }

	suspend fun ensureCustomWord(
		word: String,
		rawFreq: Int = 1000,
		pos1: String? = "NNP",
		pos2: String? = null,
		posEncoded: Int = 0,
	) = withContext(dispatcher) { db.ensureCustomWord(word, rawFreq, pos1, pos2, posEncoded) }

	suspend fun incrementCaseCount(word: String, form: WordCaseForm, amount: Int = 1) = withContext(dispatcher) { db.incrementCaseCount(word, form, amount) }

	suspend fun ensureCaseCountAtLeast(word: String, form: WordCaseForm) = withContext(dispatcher) { db.ensureCaseCountAtLeast(word, form) }

	suspend fun setPhraseUUID(word: String, phraseUUID: String?): Boolean = withContext(dispatcher) { db.setPhraseUUID(word, phraseUUID) }

	suspend fun setMetadata(key: String, value: String) = withContext(dispatcher) { db.setMetadata(key, value) }

	suspend fun clearClassMaskBits(mask: Long) = withContext(dispatcher) { db.clearClassMaskBits(mask) }

	suspend fun clearClassMask(mask: Long) = withContext(dispatcher) { db.clearClassMask(mask) }

	suspend fun mergeVocabularyMasks(sourceMask: Long, targetMask: Long) = withContext(dispatcher) { db.mergeVocabularyMasks(sourceMask, targetMask) }

	suspend fun deleteImportedWordsForClass(mask: Long): List<String> = withContext(dispatcher) { db.deleteImportedWordsForClass(mask) }

	suspend fun importWord(word: String, classMask: Long, form: WordCaseForm, rawFreq: Int = 1000) = withContext(dispatcher) { db.importWord(word, classMask, form, rawFreq) }

	suspend fun importVocabularyWord(
		word: String,
		vocabMask: Long,
		form: WordCaseForm,
		rawFreq: Int = 1000,
	): Long = withContext(dispatcher) { db.importVocabularyWord(word, vocabMask, form, rawFreq) }

	// Block receives the raw WordDb — calling accessor methods inside would
	// deadlock on the limitedParallelism(1) lane.
	suspend fun <T> transaction(block: suspend (db: WordDb) -> T): T = withContext(dispatcher) {
		db.beginTransaction()
		try {
			val result = block(db)
			db.setTransactionSuccessful()
			result
		} finally {
			db.endTransaction()
		}
	}

	override fun close() {
		db.close()
	}

	companion object {
		fun activeDbName(language: String = "English"): String = WordDb.activeDbName(language)

		fun computeFreqClass(raw: Int): Int = WordDb.computeFreqClass(raw)

		suspend fun open(
			filesDir: File,
			assets: AssetManager,
			language: String = "English",
		): WordDbAccessor = withContext(Dispatchers.IO) {
			val raw = WordDb.open(filesDir, assets, language)
			WordDbAccessor(raw, Dispatchers.IO.limitedParallelism(1))
		}

		suspend fun openStandalone(dbFile: File): WordDbAccessor = withContext(Dispatchers.IO) {
			val raw = WordDb.openStandalone(dbFile)
			WordDbAccessor(raw, Dispatchers.IO.limitedParallelism(1))
		}

		suspend fun resetToDefaults(
			filesDir: File,
			assets: AssetManager,
			language: String = "English",
		): Boolean = withContext(Dispatchers.IO) {
			WordDb.resetToDefaults(filesDir, assets, language)
		}
	}
}
