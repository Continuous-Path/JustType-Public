package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayoutSpecTest {

	private fun json(letters: String, grids: String) = """{"formatVersion":1,"language":"espanol","lettersPerKey":$letters,"grids":$grids}"""

	private val validLetters = """["da","fi","co","tb","gu","ve"]"""
	private val validGrids =
		"""[["d","","a","","","","","",""],["f","","i","","","","","",""],
		    ["c","","o","","","","","",""],["t","","b","","","","","",""],
		    ["g","","u","","","","","",""],["v","","e","","","","","",""]]"""

	@Test fun `parses a valid spec`() {
		val spec = LayoutSpec.parse(json(validLetters, validGrids))!!
		assertThat(spec.language).isEqualTo("espanol")
		assertThat(spec.lettersPerKey).containsExactly("da", "fi", "co", "tb", "gu", "ve").inOrder()
		assertThat(spec.grids[0][2]).isEqualTo("a")
	}

	@Test fun `rejects wrong key count`() {
		val errors = mutableListOf<String>()
		val spec = LayoutSpec.parse(json("""["ab","cd"]""", validGrids)) { errors.add(it) }
		assertThat(spec).isNull()
		assertThat(errors.single()).contains("expected 6 keys")
	}

	@Test fun `rejects grid that disagrees with its key`() {
		val badGrids = validGrids.replaceFirst("\"a\"", "\"x\"")
		val spec = LayoutSpec.parse(json(validLetters, badGrids)) {}
		assertThat(spec).isNull()
	}

	@Test fun `rejects malformed json`() {
		val errors = mutableListOf<String>()
		assertThat(LayoutSpec.parse("not json") { errors.add(it) }).isNull()
		assertThat(errors.single()).contains("unparseable")
	}

	@Test fun `parses an alpha section and rejects a mismatched one`() {
		val alpha = ""","alpha":{"lettersPerKey":["ab","cd","ef","gh","ij","kl"],
			"grids":[["a","","b","","","","","",""],["c","","d","","","","","",""],
			         ["e","","f","","","","","",""],["g","","h","","","","","",""],
			         ["i","","j","","","","","",""],["k","","l","","","","","",""]]}"""
		val ok = json(validLetters, validGrids).dropLast(1) + alpha + "}"
		val spec = LayoutSpec.parse(ok)!!
		assertThat(spec.alphaLettersPerKey!![0]).isEqualTo("ab")
		assertThat(spec.alphaGrids!![5][0]).isEqualTo("k")

		val bad = ok.replaceFirst("\"k\",\"\",\"l\"", "\"k\",\"\",\"x\"")
		assertThat(LayoutSpec.parse(bad) {}).isNull()
	}

	@Test fun `parses the real baked contract shape`() {
		// Shape mirror of EspanolLayout.json (extra fields like fold/spellMode are ignored).
		val real = """{
			"formatVersion": 1, "language": "espanol", "keyCount": 6,
			"lettersPerKey": ["dapz","firh","co.lw","tbnqk","guys","vemjx"],
			"grids": [
				["d","","a","","","","p","","z"], ["f","","i","","","","r","","h"],
				["c","","o","","",".","l","","w"], ["t","","b","","","n","q","","k"],
				["g","","u","","","","y","","s"], ["v","","e","","","m","j","","x"]],
			"fold": {"á":"a"}, "spellMode": {"keys": [], "needsReview": false}
		}"""
		val spec = LayoutSpec.parse(real)!!
		assertThat(spec.lettersPerKey[2]).isEqualTo("co.lw")
		assertThat(spec.grids[3]).containsExactly("t", "", "b", "", "", "n", "q", "", "k").inOrder()
	}

	// ── formatVersion 2 (tone-keystroke languages) ──────────────────────

	@Test fun `rejects an unknown future formatVersion`() {
		val errors = mutableListOf<String>()
		val v3 = json(validLetters, validGrids).replaceFirst("\"formatVersion\":1", "\"formatVersion\":3")
		assertThat(LayoutSpec.parse(v3) { errors.add(it) }).isNull()
		assertThat(errors.single()).contains("formatVersion 3 unsupported")
	}

	@Test fun `rejects v2 without a tones section`() {
		val v2 = json(validLetters, validGrids).replaceFirst("\"formatVersion\":1", "\"formatVersion\":2")
		val errors = mutableListOf<String>()
		assertThat(LayoutSpec.parse(v2) { errors.add(it) }).isNull()
		assertThat(errors.single()).contains("requires a tones section")
	}

	@Test fun `parses the real TiengViet v2 contract`() {
		val file = mainDbFile("TiengVietLayout.json")
		val spec = LayoutSpec.parse(file.readText()) { error("unexpected: $it") }!!
		assertThat(spec.formatVersion).isEqualTo(2)
		assertThat(spec.language).isEqualTo("TiengViet")
		// tone keys: all five distinct, sắc on the s-key
		val tones = spec.tones!!
		assertThat(tones.keys.keys).containsExactly("sac", "huyen", "hoi", "nga", "nang")
		assertThat(tones.keys.values.toSet()).hasSize(5)
		assertThat(spec.lettersPerKey[tones.keys["sac"]!!]).contains("s")
		// fold covers all 60 marked vowels and routes ò -> o + huyền
		assertThat(tones.fold).hasSize(60)
		assertThat(tones.fold['ò']).isEqualTo('o' to "huyen")
		// label styles
		assertThat(tones.labels["telex"]!!["nang"]).isEqualTo("j")
		assertThat(tones.labels["vni"]!!["sac"]).isEqualTo("1")
		// multi-char slot-group cells validate and are exposed
		// String-building slots: digit slots on internal keys 1/3, punct slot on 4,
		// with elided/stacked display labels (docs/.plans/string-slots/plan.md).
		assertThat(spec.slotGroups.map { it.chars }).containsExactly(
			listOf("1", "2", "3", "4", "5", "_"),
			listOf("6", "7", "8", "9", "0", "."),
			listOf("#", "/", "-", "@", "+"),
		)
		assertThat(spec.slotGroups.map { it.display }).containsExactly("15_", "60.", "#/-\n@+")
		assertThat(spec.spellToneVowels).isEqualTo("aăâeêioôơuưy")
		// alpha section with its own tone keys
		assertThat(spec.alphaLettersPerKey!![0]).isEqualTo("aăâbcd")
		assertThat(spec.alphaToneKeys!!["sac"]).isEqualTo(4)
		// natural tone order: page-reading sequence 0,3,5,2,4,7 = keyNums 0,2,4,1,3,5
		assertThat(tones.keys).containsExactlyEntriesIn(
			mapOf("sac" to 2, "huyen" to 4, "hoi" to 1, "nga" to 3, "nang" to 5),
		)
		// per-language list-function placement (page Keys 4/5/7 = keyNums 3/4/5)
		assertThat(spec.functionKeys).containsExactlyEntriesIn(
			mapOf("symbols" to 3, "functions" to 4, "navigation" to 5),
		)
	}

	@Test fun `rejects tones fold that references a letter on no key`() {
		val tones = ""","tones":{"position":"end","keys":{"sac":0},"labels":{},"fold":{"á":["q","sac"]}}"""
		val v2 = json(validLetters, validGrids)
			.replaceFirst("\"formatVersion\":1", "\"formatVersion\":2")
			.dropLast(1) + tones + "}"
		val errors = mutableListOf<String>()
		assertThat(LayoutSpec.parse(v2) { errors.add(it) }).isNull()
		assertThat(errors.single()).contains("base 'q' not on any key")
	}

	/** Walks up from user.dir so the read works regardless of the runner's working dir. */
	private fun mainDbFile(name: String): java.io.File {
		var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
		while (dir != null) {
			val direct = java.io.File(dir, "src/main/db/$name")
			if (direct.isFile) return direct
			val viaApp = java.io.File(dir, "app/src/main/db/$name")
			if (viaApp.isFile) return viaApp
			dir = dir.parentFile
		}
		error("Could not locate src/main/db/$name from ${System.getProperty("user.dir")}")
	}
}
