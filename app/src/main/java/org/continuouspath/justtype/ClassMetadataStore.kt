package org.continuouspath.justtype

import android.util.Log
import org.continuouspath.justtype.Constants.PREFS_KEY_CLASS_METADATA
import org.continuouspath.justtype.settings.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject

data class ClassMetadata(
	val bitIndex: Int,
	val name: String,
	val source: String?,
	val fileName: String?,
	val wordCount: Int,
	val createdAt: Long,
	val updatedAt: Long,
)

object ClassMetadataStore {
	// Persistent module metadata stored in DataStore via SettingsRepository under PREFS_KEY_CLASS_METADATA.
	private const val TAG = "ClassMetadataStore"

	fun ensureDefaults(repo: SettingsRepository) {
		val now = System.currentTimeMillis()
		if (!repo.contains(PREFS_KEY_CLASS_METADATA)) {
			val arr = JSONArray()
			arr.put(newDefaultItem(ClassMasks.CLASS_JUSTTYPE_BIT, "JustType Database", now))
			arr.put(newDefaultItem(ClassMasks.CLASS_CUSTOM_WORDS_BIT, "Custom Words", now))
			arr.put(newDefaultItem(ClassMasks.CLASS_PHRASES_BIT, "Abbreviated Phrases", now))
			arr.put(newDefaultItem(ClassMasks.CLASS_PAST_VOCABULARIES_BIT, "(Past Vocabularies)", now))
			repo.putString(PREFS_KEY_CLASS_METADATA, arr.toString())
			return
		}

		val items = load(repo).toMutableList()
		val existing = items.map { it.bitIndex }.toSet()
		val required =
			listOf(
				ClassMasks.CLASS_JUSTTYPE_BIT to "JustType Database",
				ClassMasks.CLASS_CUSTOM_WORDS_BIT to "Custom Words",
				ClassMasks.CLASS_PHRASES_BIT to "Abbreviated Phrases",
				ClassMasks.CLASS_PAST_VOCABULARIES_BIT to "(Past Vocabularies)",
			)
		var updated = false
		required.forEach { (bit, name) ->
			if (!existing.contains(bit)) {
				items.add(ClassMetadata(bit, name, null, null, 0, now, now))
				updated = true
			}
		}
		if (updated) save(repo, items)
	}

	fun load(repo: SettingsRepository): List<ClassMetadata> {
		val raw = repo.getString(PREFS_KEY_CLASS_METADATA, "")
		if (raw.isEmpty()) return emptyList()
		val arr = runCatching { JSONArray(raw) }.getOrNull()
		if (arr == null) {
			Log.w(TAG, "Failed to parse class metadata JSON; resetting to defaults.")
			return emptyList()
		}
		val out = mutableListOf<ClassMetadata>()
		for (i in 0 until arr.length()) {
			val obj = arr.optJSONObject(i) ?: continue
			out.add(
				ClassMetadata(
					bitIndex = obj.optInt("bit", 0),
					name = obj.optString("name", ""),
					source = obj.optString("source", "").takeIf { it.isNotEmpty() },
					fileName = obj.optString("fileName", "").takeIf { it.isNotEmpty() },
					wordCount = obj.optInt("wordCount", 0),
					createdAt = obj.optLong("createdAt", 0L),
					updatedAt = obj.optLong("updatedAt", 0L),
				),
			)
		}
		return out
	}

	fun save(
		repo: SettingsRepository,
		items: List<ClassMetadata>,
	) {
		val arr = JSONArray()
		items.forEach {
			val obj = JSONObject()
			obj.put("bit", it.bitIndex)
			obj.put("name", it.name)
			obj.put("source", it.source ?: "")
			obj.put("fileName", it.fileName ?: "")
			obj.put("wordCount", it.wordCount)
			obj.put("createdAt", it.createdAt)
			obj.put("updatedAt", it.updatedAt)
			arr.put(obj)
		}
		repo.putString(PREFS_KEY_CLASS_METADATA, arr.toString())
	}

	fun findNextFreeBit(items: List<ClassMetadata>): Int? {
		val used = items.map { it.bitIndex }.toSet()
		// Bits 0-4 are reserved for system classes; 57-58 for English regional skew, 59-60 for
		// the offensive-word levels and 61-63 for Spanish regional skew (see ClassMasks).
		// Imported vocabularies use 5..56 (52 slots).
		for (i in 5..56) {
			if (!used.contains(i)) return i
		}
		return null
	}

	fun upsert(
		items: List<ClassMetadata>,
		bit: Int,
		name: String,
		source: String?,
		fileName: String?,
		wordCount: Int,
	): List<ClassMetadata> {
		val out = items.toMutableList()
		val existingIndex = out.indexOfFirst { it.bitIndex == bit }
		val now = System.currentTimeMillis()
		if (existingIndex >= 0) {
			val old = out[existingIndex]
			out[existingIndex] =
				ClassMetadata(
					bitIndex = bit,
					name = name,
					source = source ?: old.source,
					fileName = fileName ?: old.fileName,
					wordCount = wordCount,
					createdAt = old.createdAt,
					updatedAt = now,
				)
		} else {
			out.add(
				ClassMetadata(
					bitIndex = bit,
					name = name,
					source = source,
					fileName = fileName,
					wordCount = wordCount,
					createdAt = now,
					updatedAt = now,
				),
			)
		}
		return out
	}

	fun remove(
		items: List<ClassMetadata>,
		bit: Int,
	): List<ClassMetadata> = items.filter { it.bitIndex != bit }

	private fun newDefaultItem(
		bit: Int,
		name: String,
		ts: Long,
	): JSONObject {
		val obj = JSONObject()
		obj.put("bit", bit)
		obj.put("name", name)
		obj.put("createdAt", ts)
		obj.put("updatedAt", ts)
		return obj
	}
}
