package org.continuouspath.justtype.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.logging.DebugCategory
import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.utils.AtomicFile
import java.io.File
import java.util.UUID

@Serializable
data class PhraseEntry(
	val phraseUUID: String = "",
	val abbreviation: String = "",
	val phrase: String = "",
	val createdAt: Long = 0,
	val updatedAt: Long = 0,
	val classMask: Long = ClassMasks.CLASS_PHRASES_MASK,
)

@Serializable
private data class PhrasesFileV1(
	val schemaVersion: Int = 1,
	val phrases: List<PhraseEntry> = emptyList(),
)

// TODO: singleton this when multi-Activity write races become observable.
// Today each Activity constructs its own PhraseRepository(File(filesDir, "phrases.json")).
// Last write wins; a concurrent read sees the pre-write snapshot. Single-process, so it's
// the only race shape that exists.
class PhraseRepository(
	private val file: File,
) {
	private val entries = mutableListOf<PhraseEntry>()

	init {
		load()
	}

	@Synchronized
	fun all(): List<PhraseEntry> = entries.toList()

	@Synchronized
	fun findByPhraseUUID(phraseUUID: String): PhraseEntry? = entries.firstOrNull { it.phraseUUID == phraseUUID }

	@Synchronized
	fun add(
		abbreviation: String,
		phrase: String,
	): PhraseEntry {
		val now = System.currentTimeMillis()
		val entry = PhraseEntry(
			phraseUUID = UUID.randomUUID().toString(),
			abbreviation = abbreviation,
			phrase = phrase,
			createdAt = now,
			updatedAt = now,
			classMask = ClassMasks.CLASS_PHRASES_MASK,
		)
		// An abbreviation identifies one phrase: replace any existing entry with the same abbreviation
		// (last-wins) rather than appending a duplicate that the lookup can't disambiguate — two entries
		// sharing an abbreviation make one phrase unreachable and show the other twice.
		entries.removeAll { it.abbreviation.equals(abbreviation, ignoreCase = true) }
		entries.add(entry)
		persist()
		return entry
	}

	@Synchronized
	fun findByKeys(
		keys: List<Int>,
		translator: (String) -> List<Int>?,
	): List<PhraseEntry> = entries.filter { entry ->
		translator(entry.abbreviation)?.let { it == keys } ?: false
	}

	@Synchronized
	fun reload() {
		load()
	}

	@Synchronized
	private fun load() {
		if (!file.exists()) return
		runCatching {
			val text = file.readText()
			val root = json.parseToJsonElement(text)
			val loaded = when {
				root is JsonArray -> parseLegacyArray(root)
				root is JsonObject -> parseVersioned(root)
				else -> null
			}
			if (loaded != null) {
				entries.clear()
				entries.addAll(loaded.filter { it.phraseUUID.isNotEmpty() })
			}
		}.onFailure { e ->
			DebugLogger.log(DebugCategory.Lifecycle) {
				"[PhraseRepository] load failed; keeping in-memory state: ${e.javaClass.simpleName}: ${e.message}"
			}
		}
	}

	private fun parseLegacyArray(arr: JsonArray): List<PhraseEntry> = arr.mapNotNull { element ->
		val obj = element as? JsonObject ?: return@mapNotNull null
		val uuid = obj["phraseUUID"]?.jsonPrimitive?.contentOrNull
			?: obj["id"]?.jsonPrimitive?.contentOrNull
			?: ""
		PhraseEntry(
			phraseUUID = uuid,
			abbreviation = obj["abbreviation"]?.jsonPrimitive?.contentOrNull ?: "",
			phrase = obj["phrase"]?.jsonPrimitive?.contentOrNull ?: "",
			createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
			updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
			classMask = obj["classMask"]?.jsonPrimitive?.longOrNull ?: ClassMasks.CLASS_PHRASES_MASK,
		)
	}

	private fun parseVersioned(obj: JsonObject): List<PhraseEntry>? {
		val version = obj["schemaVersion"]?.jsonPrimitive?.intOrNull
		if (version != CURRENT_SCHEMA_VERSION) {
			DebugLogger.log(DebugCategory.Lifecycle) {
				"[PhraseRepository] rejecting unknown schemaVersion=$version; expected $CURRENT_SCHEMA_VERSION"
			}
			return emptyList()
		}
		return json.decodeFromJsonElement(PhrasesFileV1.serializer(), obj).phrases
	}

	@Synchronized
	private fun persist() {
		runCatching {
			AtomicFile.write(file, append = false) { writer ->
				writer.write(json.encodeToString(PhrasesFileV1.serializer(), PhrasesFileV1(phrases = entries.toList())))
			}
		}.onFailure { e ->
			DebugLogger.log(DebugCategory.Lifecycle) {
				"[PhraseRepository] persist failed: ${e.javaClass.simpleName}: ${e.message}"
			}
		}
	}

	companion object {
		private const val CURRENT_SCHEMA_VERSION = 1
		private val json = Json {
			ignoreUnknownKeys = true
			encodeDefaults = true
			prettyPrint = false
		}
	}
}
