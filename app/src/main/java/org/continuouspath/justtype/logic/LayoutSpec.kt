package org.continuouspath.justtype.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * A per-language keyboard layout, parsed from the `layoutJson` metadata row of the language's
 * word DB (produced by the layout-analyzer tool and baked in by BuildWordDbTask).
 *
 * `lettersPerKey[k]` is keyNum k's symbols in display-reading order; `grids[k]` is its explicit
 * 9-cell row-major key-face layout (lowercase, "" = empty cell). A grid cell may hold MORE than
 * one character (a "slot group", e.g. "@_"): all its characters type ambiguously on that key and
 * share the one display cell. The grid's non-empty cell characters are exactly the key's symbols,
 * so the main page, spelling mirrors, and Phase-2 spell pages can all be derived from one source.
 * The optional `alpha` section supplies the language's Alphabetic layout the same way (English's
 * built-ins are the fallback for either section).
 *
 * formatVersion 2 adds tone-keystroke languages (Vietnamese): a [ToneSpec] describing which key
 * carries each tone mark, the display-label styles, and the fold from marked characters ("ò") to
 * (base letter, tone id) — which is how the trie encodes DB words into key sequences. Readers
 * MUST reject versions they don't understand: a v2 layout half-loaded by a v1 reader would
 * render keys but be unable to type tones.
 */
data class LayoutSpec(
	val language: String,
	val lettersPerKey: List<String>,
	val grids: List<List<String>>,
	val alphaLettersPerKey: List<String>? = null,
	val alphaGrids: List<List<String>>? = null,
	val formatVersion: Int = 1,
	val tones: ToneSpec? = null,
	val alphaToneKeys: Map<String, Int>? = null,
	val spellToneVowels: String? = null,
	val slotGroups: List<SlotGroup> = emptyList(),
	/** Per-language list-function placement (keyNum), e.g. {"symbols": 3}; null = JT defaults. */
	val functionKeys: Map<String, Int>? = null,
) {
	/** Tone-keystroke description (formatVersion 2). */
	data class ToneSpec(
		val position: String,
		val keys: Map<String, Int>,
		val labels: Map<String, Map<String, String>>,
		/** Marked char -> (base letter, tone id), e.g. 'ò' -> ('o', "huyen"). */
		val fold: Map<Char, Pair<Char, String>>,
	)

	/**
	 * A multi-character display cell (e.g. "@_"): keyNum + 9-cell index + its characters.
	 * [display] optionally overrides the cell label (may contain \n for a stacked
	 * two-row block, or an elided form like "15_" whose chars are 1-5 and _).
	 */
	data class SlotGroup(val keyNum: Int, val cell: Int, val chars: List<String>, val display: String? = null)

	companion object {
		const val METADATA_KEY = "layoutJson"
		private const val MAX_FORMAT_VERSION = 2
		private const val EXPECTED_KEYS = 6
		private const val GRID_CELLS = 9

		/** Parses and validates; returns null (logging via [onError]) on any malformed input. */
		fun parse(json: String, onError: (String) -> Unit = {}): LayoutSpec? = try {
			val o = JSONObject(json)
			val version = o.optInt("formatVersion", 1)
			if (version > MAX_FORMAT_VERSION) {
				onError("layoutJson formatVersion $version unsupported (max $MAX_FORMAT_VERSION)")
				null
			} else {
				val alpha = o.optJSONObject("alpha")
				val spell = o.optJSONObject("spellMode")
				val spec = LayoutSpec(
					language = o.optString("language", ""),
					lettersPerKey = stringList(o.getJSONArray("lettersPerKey")),
					grids = gridList(o.getJSONArray("grids")),
					alphaLettersPerKey = alpha?.let { stringList(it.getJSONArray("lettersPerKey")) },
					alphaGrids = alpha?.let { gridList(it.getJSONArray("grids")) },
					formatVersion = version,
					tones = o.optJSONObject("tones")?.let { parseTones(it) },
					alphaToneKeys = alpha?.optJSONObject("tones")?.optJSONObject("keys")?.let { keysMap(it) },
					spellToneVowels = spell?.optString("toneVowels")?.takeIf { it.isNotEmpty() },
					slotGroups = spell?.optJSONArray("slotGroups")?.let { parseSlotGroups(it) } ?: emptyList(),
					functionKeys = o.optJSONObject("functionKeys")?.let { keysMap(it) },
				)
				val problem = spec.validate()
				if (problem != null) {
					onError("layoutJson invalid: $problem")
					null
				} else {
					spec
				}
			}
		} catch (e: org.json.JSONException) {
			onError("layoutJson unparseable: ${e.message}")
			null
		}

		private fun stringList(arr: JSONArray): List<String> = List(arr.length()) { arr.getString(it) }

		private fun gridList(arr: JSONArray): List<List<String>> = List(arr.length()) { i -> stringList(arr.getJSONArray(i)) }

		private fun keysMap(o: JSONObject): Map<String, Int> = o.keys().asSequence().associateWith { o.getInt(it) }

		private fun parseTones(o: JSONObject): ToneSpec {
			val labels = o.optJSONObject("labels") ?: JSONObject()
			val fold = o.optJSONObject("fold") ?: JSONObject()
			return ToneSpec(
				position = o.optString("position", "end"),
				keys = keysMap(o.getJSONObject("keys")),
				labels = labels.keys().asSequence().associateWith { style ->
					val styleObj = labels.getJSONObject(style)
					styleObj.keys().asSequence().associateWith { styleObj.getString(it) }
				},
				fold = fold.keys().asSequence().associate { marked ->
					val pair = fold.getJSONArray(marked)
					marked.single() to (pair.getString(0).single() to pair.getString(1))
				},
			)
		}

		private fun parseSlotGroups(arr: JSONArray): List<SlotGroup> = List(arr.length()) { i ->
			val g = arr.getJSONObject(i)
			val chars = g.getJSONArray("chars")
			SlotGroup(
				g.getInt("keyNum"),
				g.getInt("cell"),
				List(chars.length()) { chars.getString(it) },
				g.optString("display").takeIf { it.isNotEmpty() },
			)
		}

		private fun validatePair(
			what: String,
			letters: List<String>,
			grids: List<List<String>>,
			slotCharsByLabel: Map<String, String> = emptyMap(),
		): String? {
			if (letters.size != EXPECTED_KEYS) return "$what: expected $EXPECTED_KEYS keys, got ${letters.size}"
			if (grids.size != EXPECTED_KEYS) return "$what: expected $EXPECTED_KEYS grids, got ${grids.size}"
			for (k in 0 until EXPECTED_KEYS) {
				if (grids[k].size != GRID_CELLS) return "$what: grid $k has ${grids[k].size} cells"
				// A cell may hold several characters (slot group); compare flattened characters.
				// Slot cells may carry an elided/stacked display ("15_") — expand via slotGroups.
				val gridChars = grids[k].flatMap { cell ->
					(slotCharsByLabel[cell] ?: cell.filter { it != '\n' }).map { it.toString() }
				}.sorted()
				val keyChars = letters[k].map { it.toString() }.sorted()
				if (gridChars != keyChars) return "$what: grid $k cells $gridChars != key '${letters[k]}'"
			}
			return null
		}
	}

	/** Slot-cell label (display or joined chars) -> the slot's full character string. */
	val slotCharsByLabel: Map<String, String>
		get() = slotGroups.associate { (it.display ?: it.chars.joinToString("")) to it.chars.joinToString("") }

	private fun validate(): String? = validatePair("optimized", lettersPerKey, grids, slotCharsByLabel)
		?: validateAlpha()
		?: validateTones()
		?: validateFunctionKeys()

	private fun validateFunctionKeys(): String? {
		val keys = functionKeys ?: return null
		if (!setOf("symbols", "functions", "navigation").containsAll(keys.keys)) return "functionKeys: unknown entry"
		if (keys.values.any { it !in 0 until EXPECTED_KEYS } || keys.values.toSet().size != keys.size) {
			return "functionKeys: invalid key indices"
		}
		return null
	}

	private fun validateAlpha(): String? {
		if ((alphaLettersPerKey == null) != (alphaGrids == null)) {
			return "alpha: lettersPerKey and grids must both be present"
		}
		if (alphaLettersPerKey != null && alphaGrids != null) {
			validatePair("alpha", alphaLettersPerKey, alphaGrids, slotCharsByLabel)?.let { return it }
		}
		return alphaToneKeys
			?.takeIf { keys -> keys.values.any { it !in 0 until EXPECTED_KEYS } }
			?.let { "alpha.tones: key index out of range" }
	}

	private fun validateTones(): String? {
		if (formatVersion >= 2 && tones == null) return "formatVersion $formatVersion requires a tones section"
		val t = tones ?: return null
		if (t.keys.values.any { it !in 0 until EXPECTED_KEYS }) return "tones: key index out of range"
		if (t.keys.values.toSet().size != t.keys.size) return "tones: two tones share a key"
		for ((marked, pair) in t.fold) {
			val (base, tone) = pair
			if (tone !in t.keys) return "tones.fold: unknown tone '$tone' for '$marked'"
			if (lettersPerKey.none { base in it }) return "tones.fold: base '$base' not on any key"
		}
		return null
	}
}
