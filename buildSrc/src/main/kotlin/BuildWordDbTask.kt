import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Gradle task that reads a `{Language}WordsRaw.txt` corpus (`word;rawFreq[;posTags[;output]]`) and produces
 * a pre-built SQLite database (`{Language}Db.db`) ready to ship as an Android asset. Language-agnostic — the
 * input/output files are configured per registered task (buildEnglishDb, buildEspanolDb, ...).
 *
 * The database schema matches the IME's runtime `words` table exactly, plus `metadata` (including the baked
 * `diacriticSet` row) and `freq_class_counts` helper tables.
 */
abstract class BuildWordDbTask : DefaultTask() {

	@get:InputFile
	lateinit var inputFile: File

	@get:OutputFile
	lateinit var outputFile: File

	/**
	 * Optional `word;ES|LA` regional-skew tags (Spanish only). Each tagged word gets the matching
	 * ClassMasks region bit OR'd into its classMask, so the ranker can soft-demote region-dispreferred
	 * words. Absent for English (no tags applied).
	 */
	@get:InputFile
	@get:Optional
	var regionTagsFile: File? = null

	/**
	 * Optional per-language optimized-layout JSON (src/main/db/{Id}Layout.json, produced by the
	 * layout-analyzer tool). Stored verbatim as the `layoutJson` metadata row; JTUI reads it to
	 * build the Optimized keyboard for this language instead of the built-in English layout.
	 */
	@get:InputFile
	@get:Optional
	var layoutFile: File? = null

	/**
	 * Optional one-word-per-line exclusion list (src/main/db/{Id}WordsAvoid.txt): coarse profanity
	 * and slurs that should never be *predicted*, even though the source corpus (subtitles) is full
	 * of them. Applied at build time only — the list never ships, and users can still add any word
	 * they want to their personal vocabulary on-device.
	 */
	@get:InputFile
	@get:Optional
	var avoidFile: File? = null

	/**
	 * Optional NGB unit inventory (src/main/db/{Id}Units.txt, `syls<TAB>marginal<TAB>eff1n1`):
	 * multi-syllable words for prediction targets, recognition-based learning, and 1N1
	 * remainder weights. See docs/.plans/ngram/engine-spec.md.
	 */
	@get:InputFile
	@get:Optional
	var unitsFile: File? = null

	/**
	 * Optional NGB context-prediction rows (src/main/db/{Id}Ngb.txt,
	 * `ctx<TAB>tgt:eff|tgt:eff|...`, rank-ordered, K-pruned): the 1N2+1N3 table,
	 * one row per context syllable so the runtime fetch is a single seek.
	 */
	@get:InputFile
	@get:Optional
	var ngbFile: File? = null

	@TaskAction
	fun build() {
		if (outputFile.exists()) outputFile.delete()
		outputFile.parentFile.mkdirs()

		val url = "jdbc:sqlite:${outputFile.absolutePath}"
		DriverManager.getConnection(url).use { conn ->
			conn.autoCommit = false
			createSchema(conn)
			val diacritics = sortedSetOf<Char>()
			val count = populateWords(conn, diacritics)
			populateFreqClassCounts(conn)
			// Corpus-derived diacritic set for language-scoped LETTER SPELL MODE (mirrors DiacriticDerivation);
			// baked into the asset so WordDb.open never has to scan the full table at runtime.
			conn.prepareStatement("INSERT INTO metadata (key, value) VALUES ('diacriticSet', ?)").use { m ->
				m.setString(1, diacritics.joinToString(""))
				m.executeUpdate()
			}
			layoutFile?.let { f ->
				val json = f.readText(Charsets.UTF_8).trim()
				require(json.contains("\"lettersPerKey\"") && json.contains("\"grids\"")) {
					"${f.name} does not look like a layout-analyzer layout JSON"
				}
				conn.prepareStatement("INSERT INTO metadata (key, value) VALUES ('layoutJson', ?)").use { m ->
					m.setString(1, json)
					m.executeUpdate()
				}
			}
			// Stamp the inputs that produced this DB. The runtime compares this against the
			// active (user) DB's stamp to decide whether a rebuilt corpus needs migrating in —
			// the active DB is seeded once and otherwise never re-copied, so without this a
			// corpus update never reaches an existing install. A content hash is used rather
			// than a hand-bumped number so it can't be forgotten.
			conn.prepareStatement("INSERT INTO metadata (key, value) VALUES ('langBuildVersion', ?)").use { m ->
				m.setString(1, computeBuildVersion())
				m.executeUpdate()
			}
			val (ngbUnits, ngbCtx) = populateNgb(conn)
			conn.commit()
			val layoutNote = if (layoutFile != null) ", layout baked" else ""
			val ngbNote = if (ngbUnits > 0 || ngbCtx > 0) ", NGB $ngbUnits units/$ngbCtx contexts" else ""
			logger.lifecycle("BuildWordDb: inserted $count words (${diacritics.size} diacritics$layoutNote$ngbNote) into ${outputFile.name}")
		}
	}

	/**
	 * Short content hash of every input that shapes the words table. Any corpus, exclusion-list,
	 * region-tag or layout edit changes it, which is what tells the runtime to migrate.
	 */
	private fun computeBuildVersion(): String {
		val digest = java.security.MessageDigest.getInstance("SHA-256")
		// Storage-format salt: encoder changes must move the stamp even when
		// every input file is byte-identical, or existing installs keep the old
		// on-disk format while the runtime expects the new one.
		digest.update(NGB_FORMAT_SALT.toByteArray(Charsets.UTF_8))
		for (f in listOfNotNull(inputFile, avoidFile, regionTagsFile, layoutFile, unitsFile, ngbFile)) {
			if (f.exists()) digest.update(f.readBytes())
		}
		return digest.digest().take(8).joinToString("") { "%02x".format(it) }
	}

	private fun createSchema(conn: Connection) {
		conn.createStatement().use { stmt ->
			stmt.executeUpdate(
				"""CREATE TABLE words (
                    wordID INTEGER PRIMARY KEY AUTOINCREMENT,
                    word TEXT UNIQUE NOT NULL,
                    freqClass INTEGER NOT NULL,
                    classMask INTEGER NOT NULL DEFAULT 0,
                    useCount INTEGER NOT NULL DEFAULT 0,
                    useTime INTEGER NULL,
                    rawFreq INTEGER NOT NULL DEFAULT 0,
                    PartOfSpeech1 TEXT NULL,
                    PartOfSpeech2 TEXT NULL,
                    lowerCaseCount INTEGER NOT NULL DEFAULT 0,
                    titleCaseCount INTEGER NOT NULL DEFAULT 0,
                    upperCaseCount INTEGER NOT NULL DEFAULT 0,
                    originalCaseCount INTEGER NOT NULL DEFAULT 0,
                    phraseUUID TEXT NULL,
                    posEncoded INTEGER NOT NULL DEFAULT 0
                )""",
			)
			stmt.executeUpdate("CREATE INDEX idx_words_freqClass ON words(freqClass)")
			stmt.executeUpdate(
				"""CREATE TABLE metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""",
			)
			stmt.executeUpdate(
				"""CREATE TABLE freq_class_counts (
                    freqClass INTEGER PRIMARY KEY,
                    count INTEGER NOT NULL
                )""",
			)
			// NGB tables exist in every DB (empty for languages without NGB inputs)
			// so the runtime never has to probe for them. Schema mirrors
			// docs/.plans/ngram/engine-spec.md (v1 text-packed rows).
			stmt.executeUpdate(
				"""CREATE TABLE ngb_units (
                    unit_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    first_syl TEXT NOT NULL,
                    syls TEXT UNIQUE NOT NULL,
                    marginal INTEGER NOT NULL DEFAULT 0,
                    eff1n1 INTEGER NOT NULL DEFAULT 0,
                    flags INTEGER NOT NULL DEFAULT 0,
                    display TEXT
                )""",
			)
			stmt.executeUpdate("CREATE INDEX idx_ngb_units_first ON ngb_units(first_syl)")
			// v2 varint format (docs/.plans/sls.md): targets live in tblob —
			// version byte 0x01, then per target in eff-descending rank order
			// uvarint(ngb_words id) + uvarint(delta), delta = eff for the first
			// pair and prevEff - eff after. The legacy TEXT column stays (empty)
			// so the runtime schema is uniform across old langpacks and new
			// builds. ngb_words is the NGB-private dictionary — ids are NOT
			// words.wordID (which migration reassigns); the table migrates
			// wholesale with ngb_ctx, exactly like ngb_units.
			stmt.executeUpdate(
				"""CREATE TABLE ngb_ctx (
                    ctx TEXT PRIMARY KEY,
                    targets TEXT NOT NULL DEFAULT '',
                    tblob BLOB
                )""",
			)
			stmt.executeUpdate(
				"""CREATE TABLE ngb_words (
                    id INTEGER PRIMARY KEY,
                    word TEXT NOT NULL
                )""",
			)
		}
	}

	/** Loads {Id}Units.txt + {Id}Ngb.txt into the NGB tables. No-op when absent. */
	private fun populateNgb(conn: Connection): Pair<Int, Int> {
		var units = 0
		unitsFile?.takeIf { it.exists() }?.let { f ->
			conn.prepareStatement(
				"INSERT OR IGNORE INTO ngb_units (first_syl, syls, marginal, eff1n1, display) VALUES (?, ?, ?, ?, ?)",
			).use { stmt ->
				f.forEachLine { line ->
					val p = line.trim().split("\t")
					if (p.size < 3 || p[0].isEmpty()) return@forEachLine
					stmt.setString(1, p[0].substringBefore(' '))
					stmt.setString(2, p[0])
					stmt.setLong(3, p[1].toLongOrNull() ?: 0L)
					stmt.setLong(4, p[2].toLongOrNull() ?: 0L)
					// Canonical display case (Kaikki orthography: "Hồ Chí Minh");
					// falls back to the lowercase unit for older 3-column files.
					stmt.setString(5, p.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: p[0])
					stmt.addBatch()
					units++
				}
				stmt.executeBatch()
			}
		}
		var ctxRows = 0
		ngbFile?.takeIf { it.exists() }?.let { f ->
			// Pass 1: NGB target dictionary. Ids by descending row-occurrence
			// count (ties by word) so the most-shared targets get 1-byte varints.
			val occurrences = HashMap<String, Int>()
			f.forEachLine { line ->
				val tab = line.indexOf('\t')
				if (tab <= 0) return@forEachLine
				for (part in line.substring(tab + 1).splitToSequence('|')) {
					val colon = part.lastIndexOf(':')
					if (colon > 0) occurrences.merge(part.substring(0, colon), 1, Int::plus)
				}
			}
			val idOf = HashMap<String, Int>(occurrences.size * 2)
			conn.prepareStatement("INSERT INTO ngb_words (id, word) VALUES (?, ?)").use { stmt ->
				occurrences.entries
					.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
					.forEachIndexed { i, e ->
						idOf[e.key] = i + 1
						stmt.setInt(1, i + 1)
						stmt.setString(2, e.key)
						stmt.addBatch()
					}
				stmt.executeBatch()
			}
			// Pass 2: encode each context row as the v2 varint blob (rank order
			// re-sorted eff-descending, stable, so same-eff ties keep file order
			// — the exact tie semantics of the legacy string path).
			conn.prepareStatement("INSERT OR IGNORE INTO ngb_ctx (ctx, tblob) VALUES (?, ?)").use { stmt ->
				f.forEachLine { line ->
					val tab = line.indexOf('\t')
					if (tab <= 0) return@forEachLine
					val pairs = ArrayList<Pair<Int, Long>>()
					for (part in line.substring(tab + 1).splitToSequence('|')) {
						val colon = part.lastIndexOf(':')
						val eff = if (colon > 0) part.substring(colon + 1).toLongOrNull() else null
						if (eff != null) pairs.add(idOf.getValue(part.substring(0, colon)) to eff)
					}
					if (pairs.isEmpty()) return@forEachLine
					stmt.setString(1, line.substring(0, tab))
					stmt.setBytes(2, encodeCtxBlob(pairs.sortedByDescending { it.second }))
					stmt.addBatch()
					ctxRows++
				}
				stmt.executeBatch()
			}
		}
		return units to ctxRows
	}

	/** v2 ngb_ctx blob (mirror of NgbCodec.decode in the app): version byte
	 *  0x01, then per target uvarint(id) + uvarint(delta); delta = eff for the
	 *  first pair, prevEff - eff after (rank order is eff-descending, so
	 *  deltas are small non-negatives). */
	private fun encodeCtxBlob(pairs: List<Pair<Int, Long>>): ByteArray {
		val out = java.io.ByteArrayOutputStream(pairs.size * 4)
		out.write(1)
		fun uvarint(value: Long) {
			var v = value
			while (v >= 0x80) {
				out.write(((v and 0x7f) or 0x80).toInt())
				v = v ushr 7
			}
			out.write(v.toInt())
		}
		var prev = 0L
		for ((i, pair) in pairs.withIndex()) {
			val (id, eff) = pair
			uvarint(id.toLong())
			uvarint(if (i == 0) eff else prev - eff)
			prev = eff
		}
		return out.toByteArray()
	}

	private fun populateWords(conn: Connection, diacritics: MutableSet<Char>): Int {
		val regionBits = loadRegionBits()
		val avoidRules = loadAvoidRules()
		var dropped = 0
		val levelCounts = IntArray(3)
		var caseFixed = 0
		val insertSql = """INSERT OR REPLACE INTO words
            (word, freqClass, classMask, useCount, useTime, rawFreq,
             PartOfSpeech1, PartOfSpeech2,
             lowerCaseCount, titleCaseCount, upperCaseCount, originalCaseCount,
             posEncoded)
            VALUES (?, ?, ?, 0, NULL, ?, ?, ?, ?, ?, ?, ?, ?)"""

		val stmt = conn.prepareStatement(insertSql)
		var count = 0

		fun insertRow(
			word: String,
			rawFreq: Int,
			posEncoded: Int,
			pos1: String?,
			pos2: String?,
			caseSeed: CaseCounts? = null,
			levelBits: Long = 0L,
		) {
			val fc = computeFreqClass(rawFreq)
			val caseCounts = caseSeed ?: computeInitialCaseCounts(word)
			stmt.clearParameters()
			stmt.setString(1, word)
			stmt.setInt(2, fc)
			stmt.setLong(3, CLASS_JUSTTYPE_MASK or (regionBits[word.lowercase()] ?: 0L) or levelBits)
			stmt.setInt(4, rawFreq)
			if (pos1 != null) stmt.setString(5, pos1) else stmt.setNull(5, java.sql.Types.VARCHAR)
			if (pos2 != null) stmt.setString(6, pos2) else stmt.setNull(6, java.sql.Types.VARCHAR)
			stmt.setInt(7, caseCounts.lower)
			stmt.setInt(8, caseCounts.title)
			stmt.setInt(9, caseCounts.upper)
			stmt.setInt(10, caseCounts.original)
			stmt.setInt(11, posEncoded)
			stmt.executeUpdate()
			for (ch in word) if (ch.code > 0x7F && ch.isLetter()) diacritics.add(ch.lowercaseChar())
			count++
		}

		for (line in inputFile.readLines()) {
			val cleaned = line.removePrefix("\uFEFF").trim()
			if (cleaned.isEmpty()) continue
			val fields = cleaned.split(';')
			if (fields.size < 2) continue

			val rawFreq = fields.getOrNull(1)?.toIntOrNull() ?: continue
			val rule = avoidRules[fields[0].lowercase()]
			if (rule?.exclude == true) {
				dropped++
				continue
			}
			if (rule != null) levelCounts[rule.level]++
			val levelBits = when (rule?.level) {
				1 -> CLASS_OFFENSIVE_MASK
				2 -> CLASS_POTENTIALLY_OFFENSIVE_MASK
				else -> 0L
			}
			val posRaw = fields.getOrNull(2)
			var output = fields.getOrNull(3)?.takeIf { it.isNotBlank() } ?: fields[0]
			// Optional 5th field: corpus-derived case seed "lower:title[:upper[:original]]"
			// (small integers — the runtime increments by 1 per selection, so seeds set the
			// initial Selection List case preference while user choices can still flip it).
			val caseSeed = fields.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { parseCaseSeed(it) }

			val posEncoded: Int
			val pos1: String?
			val pos2: String?
			if (!posRaw.isNullOrBlank()) {
				posEncoded = PosEncodingUtil.encodeFromTagString(posRaw)
				val tags = PosEncodingUtil.posTagList(posEncoded)
				pos1 = tags.getOrNull(0)
				pos2 = tags.getOrNull(1)
			} else {
				posEncoded = 0
				pos1 = null
				pos2 = null
			}

			// Hand-marked PN/ACRONYM entries are false positives that also had the wrong case:
			// restore the surface and pin the CaseType so they present correctly.
			var posEncoded2 = posEncoded
			var caseSeed2 = caseSeed
			if (rule?.properNoun == true || rule?.acronym == true) caseSeed2 = null
			if (rule?.properNoun == true) {
				output = output.replaceFirstChar { it.uppercaseChar() }
				posEncoded2 = PosEncodingUtil.withCaseType(posEncoded2, CASE_TITLE_ONLY)
				caseFixed++
			} else if (rule?.acronym == true) {
				output = output.uppercase()
				posEncoded2 = PosEncodingUtil.withCaseType(posEncoded2, CASE_ACRONYM)
				caseFixed++
			}

			val hasAB = PosEncodingUtil.hasPosTag(posEncoded, "AB")
			val hasABP = PosEncodingUtil.hasPosTag(posEncoded, "ABP")
			val hasABPS = PosEncodingUtil.hasPosTag(posEncoded, "ABPS")
			val hasEXT = PosEncodingUtil.hasPosTag(posEncoded, "EXT")

			when {
				hasABPS || hasABP -> {
					// Period-required abbreviation: insert only "word." form
					insertRow("$output.", rawFreq, posEncoded2, pos1, pos2, levelBits = levelBits)
				}

				hasAB -> {
					// Optional-period abbreviation: insert "word" and "word."
					insertRow(output, rawFreq, posEncoded2, pos1, pos2, levelBits = levelBits)
					insertRow(
						"$output.",
						(rawFreq * 0.9).toInt().coerceAtLeast(1),
						posEncoded2,
						pos1,
						pos2,
						levelBits = levelBits,
					)
				}

				hasEXT -> {
					// Extension: only insert the dot form (e.g. ".com", ".us")
					// Non-period variants are NOT generated to avoid overwriting
					// existing common words (e.g. "us", "me") via INSERT OR REPLACE
					val dotForm = if (output.startsWith(".")) output else ".$output"
					insertRow(dotForm, rawFreq, posEncoded2, pos1, pos2, levelBits = levelBits)
				}

				else -> {
					// DOM and all other entries: insert as-is
					insertRow(output, rawFreq, posEncoded2, pos1, pos2, caseSeed2, levelBits)
				}
			}
		}
		stmt.close()
		if (avoidRules.isNotEmpty()) {
			logger.lifecycle(
				"BuildWordDb: avoid list — dropped $dropped, level0 ${levelCounts[0]}, " +
					"level1 ${levelCounts[1]}, level2 ${levelCounts[2]}, case-fixed $caseFixed",
			)
		}
		return count
	}

	/**
	 * How one `{Lang}WordsAvoid.txt` entry should affect the build.
	 *
	 * @param level 0 = non-offensive (no mask bit), 1 = offensive, 2 = potentially offensive.
	 * @param exclude drop the word from the DB entirely (slurs, and hand-marked `X` entries).
	 * @param properNoun store Title-cased with CaseType TITLE_ONLY (Yamashita, Cumming).
	 * @param acronym store UPPER-cased with CaseType ACRONYM (DOGE).
	 */
	data class AvoidRule(
		val level: Int,
		val exclude: Boolean,
		val properNoun: Boolean,
		val acronym: Boolean,
	)

	/**
	 * Parse `word;tier[ marker]`, where tier is `profanity` or `slur` and the optional
	 * hand-added marker refines it:
	 *
	 *   0 / 2      level override (default 1); `0PN`, `0ACRONYM` combine a level with a case rule
	 *   PN         proper noun — include, Title-cased
	 *   ACRONYM    acronym — include, UPPER-cased
	 *   slur       reclassify as a slur; drop from the DB
	 *   X          drop from the DB (kept in the file so it stays dropped)
	 *   ?          undecided — falls through to the tier, so a `slur ?` entry stays excluded
	 *
	 * Whitespace is tolerated loosely because this file is hand-maintained.
	 */
	private fun loadAvoidRules(): Map<String, AvoidRule> {
		val f = avoidFile ?: return emptyMap()
		val out = HashMap<String, AvoidRule>()
		for (line in f.readLines()) {
			val c = line.trim()
			if (c.isEmpty() || c.startsWith("#")) continue
			val parts = c.split(';')
			val word = parts[0].trim().lowercase()
			if (word.isEmpty()) continue
			val tokens = (parts.getOrNull(1) ?: "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
			val tier = tokens.firstOrNull()?.lowercase() ?: "profanity"
			val marker = tokens.drop(1).joinToString(" ").uppercase()

			val levelOverride = Regex("[012]").find(marker)?.value?.toInt()
			val exclude = marker.contains("SLUR") ||
				marker.trim() == "X" ||
				(tier == "slur" && levelOverride == null)
			out[word] = AvoidRule(
				level = levelOverride ?: 1,
				exclude = exclude,
				properNoun = marker.contains("PN"),
				acronym = marker.contains("ACRONYM"),
			)
		}
		return out
	}

	/** Load `word;ES|LA` region tags into word -> region classMask bit (empty if no tags file set). */
	private fun loadRegionBits(): Map<String, Long> {
		val f = regionTagsFile ?: return emptyMap()
		val map = HashMap<String, Long>()
		for (line in f.readLines()) {
			val c = line.trim()
			if (c.isEmpty() || c.startsWith("#")) continue
			val parts = c.split(';')
			if (parts.size < 2) continue
			val bit = when (parts[1].trim()) {
				"ES" -> CLASS_REGION_ES_SKEW_MASK
				"LA" -> CLASS_REGION_LA_SKEW_MASK
				"GB" -> CLASS_REGION_GB_SKEW_MASK
				"US" -> CLASS_REGION_US_SKEW_MASK
				else -> 0L
			}
			if (bit != 0L) map[parts[0].trim().lowercase()] = bit
		}
		return map
	}

	private fun populateFreqClassCounts(conn: Connection) {
		val query = """SELECT freqClass, COUNT(1)
                       FROM words WHERE (classMask & ?) != 0
                       GROUP BY freqClass"""
		val sel = conn.prepareStatement(query)
		sel.setLong(1, CLASS_JUSTTYPE_MASK)
		val rs = sel.executeQuery()

		val ins = conn.prepareStatement(
			"INSERT OR REPLACE INTO freq_class_counts (freqClass, count) VALUES (?, ?)",
		)
		while (rs.next()) {
			ins.setInt(1, rs.getInt(1))
			ins.setInt(2, rs.getInt(2))
			ins.executeUpdate()
		}
		rs.close()
		sel.close()
		ins.close()
	}

	// ── Duplicated pure logic (matches app runtime exactly) ─────────────────

	companion object {
		// Bump when the on-disk NGB encoding changes (v2 = varint tblob +
		// ngb_words dictionary); folded into langBuildVersion so encoder
		// changes reseed installs even with byte-identical inputs.
		private const val NGB_FORMAT_SALT = "ngb-format-v2"

		private const val CLASS_JUSTTYPE_MASK = 1L // bit 0

		// Mirror of ClassMasks offensive-level bits (buildSrc can't import app code).
		private const val CLASS_OFFENSIVE_MASK: Long = 1L shl 60
		private const val CLASS_POTENTIALLY_OFFENSIVE_MASK: Long = 1L shl 59

		// Mirror of PosEncoding CaseType values.
		private const val CASE_TITLE_ONLY = 1
		private const val CASE_ACRONYM = 5

		// Mirror of ClassMasks region-skew bits (buildSrc can't import app code).
		private const val CLASS_REGION_GB_SKEW_MASK = 1L shl 58
		private const val CLASS_REGION_US_SKEW_MASK = 1L shl 57
		private const val CLASS_REGION_ES_SKEW_MASK = 1L shl 62
		private const val CLASS_REGION_LA_SKEW_MASK = 1L shl 61

		private fun computeFreqClass(raw: Int): Int = when {
			raw >= 40000 -> 1
			raw >= 20000 -> 2
			raw >= 10000 -> 3
			raw >= 5000 -> 4
			raw >= 2500 -> 5
			raw >= 1250 -> 6
			raw >= 625 -> 7
			raw >= 300 -> 8
			raw >= 150 -> 9
			raw >= 75 -> 10
			raw >= 37 -> 11
			raw >= 17 -> 12
			raw > 8 -> 13
			else -> 14
		}

		private data class CaseCounts(val lower: Int, val title: Int, val upper: Int, val original: Int)

		private fun parseCaseSeed(raw: String): CaseCounts? {
			val parts = raw.split(':').map { it.trim().toIntOrNull() ?: return null }
			if (parts.size !in 2..4 || parts.all { it == 0 }) return null
			return CaseCounts(
				lower = parts[0],
				title = parts[1],
				upper = parts.getOrElse(2) { 0 },
				original = parts.getOrElse(3) { 0 },
			)
		}

		private fun computeInitialCaseCounts(word: String): CaseCounts {
			val letters = word.filter { it.isLetter() }
			val hasLetters = letters.isNotEmpty()
			val allUpper = hasLetters && letters.all { it.isUpperCase() }
			val allLower = hasLetters && letters.all { it.isLowerCase() }
			val titleCase =
				hasLetters && letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() }
			val mixedCase = hasLetters && !allUpper && !allLower && !titleCase
			return when {
				allUpper -> CaseCounts(lower = 0, title = 0, upper = 1, original = 0)
				titleCase -> CaseCounts(lower = 0, title = 1, upper = 0, original = 0)
				allLower -> CaseCounts(lower = 1, title = 0, upper = 0, original = 0)
				mixedCase -> CaseCounts(lower = 0, title = 0, upper = 0, original = 1)
				else -> CaseCounts(lower = 1, title = 0, upper = 0, original = 0)
			}
		}
	}
}

/**
 * Standalone POS encoding — mirrors app/src/.../PosEncoding.kt exactly.
 * Runs on JVM (no Android dependencies).
 */
object PosEncodingUtil {
	private const val POS_NONE = 0

	private val TAG_TO_INDEX: Map<String, Int> = mapOf(
		"NN" to 1, "NNS" to 2, "NNP" to 3, "NNPS" to 4,
		"VB" to 5, "VBD" to 6, "VBG" to 7, "VBN" to 8, "VBP" to 9, "VBZ" to 10,
		"JJ" to 11, "JJR" to 12, "JJS" to 13,
		"RB" to 14, "RBR" to 15,
		"DT" to 16, "WDT" to 17,
		"PRP" to 18, "PRP\$" to 19, "WP" to 20, "WP\$" to 21,
		"IN" to 22, "CC" to 23,
		"WRB" to 24, "MD" to 25, "UH" to 26, "FW" to 27,
		"AB" to 28, "RP" to 29, "EX" to 30, "EXT" to 31, "UNC" to 32,
		"TO" to 33, "ABP" to 34, "ABPS" to 35, "DOM" to 36,
	)

	private val INDEX_TO_TAG: Map<Int, String> = TAG_TO_INDEX.entries.associate { (k, v) -> v to k }

	private val CASETYPE_TAG_TO_VALUE: Map<String, Int> = mapOf(
		"LO" to 0,
		"TX" to 1,
		"TO" to 1,
		"LF" to 2,
		"TF" to 3,
		"OC" to 4,
		"AC" to 5,
	)

	fun encodeFromTagString(tagString: String): Int {
		val tags = tagString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
		if (tags.isEmpty()) return 0
		val ct = CASETYPE_TAG_TO_VALUE[tags[0]] ?: 0
		val p1 = if (tags.size > 1) TAG_TO_INDEX[tags[1]] ?: POS_NONE else POS_NONE
		val p2 = if (tags.size > 2) TAG_TO_INDEX[tags[2]] ?: POS_NONE else POS_NONE
		val p3 = if (tags.size > 3) TAG_TO_INDEX[tags[3]] ?: POS_NONE else POS_NONE
		return (p1 shl 24) or (p2 shl 16) or (p3 shl 8) or ct
	}

	fun posTagList(encoded: Int): List<String> = buildList {
		INDEX_TO_TAG[(encoded ushr 24) and 0xFF]?.let { add(it) }
		INDEX_TO_TAG[(encoded ushr 16) and 0xFF]?.let { add(it) }
		INDEX_TO_TAG[(encoded ushr 8) and 0xFF]?.let { add(it) }
	}

	/** Replace the CaseType byte, preserving the POS bytes (mirrors PosEncoding.withCaseType). */
	fun withCaseType(encoded: Int, caseType: Int): Int = (encoded and 0xFFFFFF00.toInt()) or (caseType and 0xFF)

	fun hasPosTag(encoded: Int, tag: String): Boolean {
		val idx = TAG_TO_INDEX[tag] ?: return false
		return ((encoded ushr 24) and 0xFF) == idx ||
			((encoded ushr 16) and 0xFF) == idx ||
			((encoded ushr 8) and 0xFF) == idx
	}
}
