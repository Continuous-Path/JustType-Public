package org.continuouspath.justtype

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.logic.WordCaseForm
import org.continuouspath.justtype.logic.WordDb
import java.io.BufferedReader
import java.util.Locale

object VocabImportHelper {
	fun queryDisplayName(
		context: Context,
		uri: Uri,
	): String? {
		val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
		cursor.use {
			val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
			if (nameIndex >= 0 && it.moveToFirst()) {
				return it.getString(nameIndex)
			}
		}
		return null
	}

	fun importVocabulary(
		context: Context,
		uri: Uri,
		bit: Int,
	): List<String> {
		val wordDb = WordDb.open(context.filesDir, context.assets)
		try {
			val mask = 1L shl bit
			// use{} closes the reader — tokenizeStream's readLines() does not — and openInputStream can
			// return null for a stale/revoked Uri, so guard it instead of NPE-ing on a null stream.
			val stream = context.contentResolver.openInputStream(uri) ?: return emptyList()
			val tokens = stream.bufferedReader().use { tokenizeStream(it) }
			val words = importTokens(wordDb, tokens, mask)
			writeImportedWordList(context, uri, words)
			return words
		} finally {
			wordDb.close()
		}
	}

	fun importVocabularyText(
		context: Context,
		text: String,
		bit: Int,
	) {
		val wordDb = WordDb.open(context.filesDir, context.assets)
		try {
			val mask = 1L shl bit
			val tokens = text.reader().buffered().use { tokenizeStream(it) }
			importTokens(wordDb, tokens, mask)
		} finally {
			wordDb.close()
		}
	}

	fun importTokens(
		wordDb: WordDb,
		tokens: List<TokenInfo>,
		mask: Long,
	): List<String> {
		val isAllUpper = tokens.isNotEmpty() && tokens.all { it.original == it.original.uppercase(Locale.getDefault()) }
		val imported = LinkedHashSet<String>()
		wordDb.beginTransaction()
		try {
			tokens.forEach { token ->
				val normalized = token.normalized.lowercase(Locale.getDefault())
				val form = if (isAllUpper) WordCaseForm.LOWER else determineCaseForm(token, token.sentenceStart)
				val words = splitHyphenatedWords(normalized, wordDb)
				words.forEach { word ->
					if (word.length == 1 && !wordDb.hasJustTypeWord(word)) return@forEach
					if (!imported.add(word)) return@forEach
					val storedMask = wordDb.importVocabularyWord(word, mask, form)
					DebugLogger.log(DebugCategory.WordDb) {
						"[VocabImportHelper] imported word='$word' storedMask=${hexMask(storedMask)}"
					}
				}
			}
			wordDb.setTransactionSuccessful()
		} finally {
			wordDb.endTransaction()
		}
		return imported.toList()
	}

	fun tokenizeStream(reader: BufferedReader): List<TokenInfo> {
		val regex = Regex("[A-Za-z][A-Za-z'\\-]*")
		val out = mutableListOf<TokenInfo>()
		val lines = reader.readLines()
		val firstNonEmpty = lines.firstOrNull { it.isNotBlank() } ?: ""
		val isRtf = firstNonEmpty.trimStart().startsWith("{\\rtf", ignoreCase = true)
		val rtfKeywords =
			setOf(
				"rtf",
				"ansi",
				"ansicpg",
				"cocoartf",
				"cocoatextscaling",
				"cocoaplatform",
				"fonttbl",
				"fswiss",
				"fcharset",
				"colortbl",
				"expandedcolortbl",
				"margl",
				"margr",
				"vieww",
				"viewh",
				"viewkind",
				"pard",
				"pardirnatural",
				"partightenfactor",
				"tx",
				"fs",
				"cf",
			)
		var filteredRtfTokens = 0
		var sentenceStart = true
		lines.forEach { rawLine ->
			val line = normalizeImportLine(rawLine, isRtf)
			val matches = regex.findAll(line).toList()
			for (i in matches.indices) {
				val match = matches[i]
				val rawToken = match.value
				if (shouldSkipSingleLetterAfterApostrophe(line, match, rawToken)) {
					continue
				}
				val normalized = normalizeImportToken(rawToken)
				if (isRtf && rtfKeywords.contains(normalized.lowercase(Locale.getDefault()))) {
					filteredRtfTokens += 1
					continue
				}
				out.add(TokenInfo(normalized, rawToken, sentenceStart))
				val end = match.range.last + 1
				val nextStart = if (i + 1 < matches.size) matches[i + 1].range.first else line.length
				val between = line.substring(end, nextStart)
				sentenceStart = between.any { it == '.' || it == '!' || it == '?' }
			}
			sentenceStart = true
		}
		if (isRtf) {
			DebugLogger.log(DebugCategory.WordDb) {
				"[VocabImportHelper] RTF detected: filteredTokens=$filteredRtfTokens totalTokens=${out.size}"
			}
		}
		return out
	}

	private fun splitHyphenatedWords(
		token: String,
		wordDb: WordDb,
	): List<String> {
		if (!token.contains('-')) return listOf(token)
		val parts = token.split('-').filter { it.isNotBlank() }
		val out = mutableListOf(token)
		parts.forEach { part ->
			if (part.length == 1) {
				if (wordDb.hasJustTypeWord(part)) out.add(part)
			} else {
				out.add(part)
			}
		}
		return out
	}

	private fun writeImportedWordList(
		context: Context,
		uri: Uri,
		words: List<String>,
	) {
		if (words.isEmpty()) return
		if (!android.provider.DocumentsContract.isDocumentUri(context, uri)) {
			Toast.makeText(context, context.getString(R.string.toast_could_not_write_word_list), Toast.LENGTH_SHORT).show()
			return
		}
		val displayName = queryDisplayName(context, uri) ?: return
		val baseName = displayName.substringBeforeLast('.')
		val outputName = "$baseName.WordList.txt"
		val parentUri =
			queryParentDocumentUri(context, uri) ?: run {
				Toast.makeText(context, context.getString(R.string.toast_could_not_locate_parent), Toast.LENGTH_SHORT).show()
				return
			}
		val outputUri =
			runCatching {
				android.provider.DocumentsContract.createDocument(
					context.contentResolver,
					parentUri,
					"text/plain",
					outputName,
				)
			}.getOrNull()
		if (outputUri == null) {
			Toast.makeText(context, context.getString(R.string.toast_unable_create_word_list), Toast.LENGTH_SHORT).show()
			return
		}
		runCatching {
			context.contentResolver.openOutputStream(outputUri)?.bufferedWriter().use { writer ->
				words.forEach { word ->
					writer?.write(word)
					writer?.newLine()
				}
			}
		}.onFailure {
			Toast.makeText(context, context.getString(R.string.toast_failed_writing_word_list, it.message ?: ""), Toast.LENGTH_LONG).show()
		}
	}

	private fun queryParentDocumentUri(
		context: Context,
		uri: Uri,
	): Uri? {
		return runCatching {
			val projection = arrayOf("parentDocumentId")
			context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
				val idx = cursor.getColumnIndex("parentDocumentId")
				if (idx >= 0 && cursor.moveToFirst()) {
					val parentId = cursor.getString(idx)
					return android.provider.DocumentsContract.buildDocumentUri(uri.authority, parentId)
				}
			}
			null
		}.getOrNull()
	}

	private fun normalizeImportLine(
		line: String,
		isRtf: Boolean,
	): String {
		val normalized =
			line
				.replace('\u2019', '\'')
				.replace('\u2018', '\'')
				.replace('\u201B', '\'')
				.replace('\u2032', '\'')
				.replace('\u02BC', '\'')
				.replace('\u2010', '-')
				.replace('\u2011', '-')
				.replace('\u2012', '-')
				.replace('\u2013', '-')
				.replace('\u2014', '-')
				.replace('\u2212', '-')
		if (!isRtf) return normalized
		return normalized
			.replace(Regex("\\\\[a-z]+-?\\d*\\s?"), " ")
			.replace("{", " ")
			.replace("}", " ")
	}

	private fun normalizeImportToken(token: String): String = token
		.replace('\u2019', '\'')
		.replace('\u2018', '\'')
		.replace('\u201B', '\'')
		.replace('\u2032', '\'')
		.replace('\u02BC', '\'')
		.replace('\uFF07', '\'')
		.replace('\u2010', '-')
		.replace('\u2011', '-')
		.replace('\u2012', '-')
		.replace('\u2013', '-')
		.replace('\u2014', '-')
		.replace('\u2212', '-')

	private fun determineCaseForm(
		token: TokenInfo,
		isSentenceStart: Boolean,
	): WordCaseForm {
		val raw = token.original
		val letters = raw.filter { it.isLetter() }
		if (letters.isEmpty()) return WordCaseForm.LOWER
		val allUpper = letters.all { it.isUpperCase() }
		val titleCase = letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() }
		return when {
			allUpper -> WordCaseForm.UPPER
			titleCase && !isSentenceStart -> WordCaseForm.TITLE
			else -> WordCaseForm.LOWER
		}
	}

	data class TokenInfo(
		val normalized: String,
		val original: String,
		val sentenceStart: Boolean,
	)

	private fun shouldSkipSingleLetterAfterApostrophe(
		line: String,
		match: MatchResult,
		rawToken: String,
	): Boolean {
		if (rawToken.length != 1) return false
		val idx = match.range.first
		if (idx <= 0) return false
		val prev = line[idx - 1]
		if (!isApostropheChar(prev)) return false
		val prevPrevIdx = idx - 2
		return prevPrevIdx >= 0 && line[prevPrevIdx].isLetter()
	}

	private fun isApostropheChar(ch: Char): Boolean = ch == '\'' ||
		ch == '\u2019' ||
		ch == '\u2018' ||
		ch == '\u201B' ||
		ch == '\u2032' ||
		ch == '\u02BC' ||
		ch == '\uFF07'

	private fun hexMask(mask: Long): String = "0x" + mask.toString(16).uppercase(Locale.getDefault())
}
