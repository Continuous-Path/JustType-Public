package org.continuouspath.justtype.data

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.ClassMasks
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhraseRepositoryTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private fun loadFixture(name: String): String = javaClass.classLoader!!.getResource("phrase-fixtures/$name")!!.readText()

	private fun writeFixtureTo(file: File, name: String) {
		file.writeText(loadFixture(name))
	}

	private fun repo(fixture: String? = null): Pair<PhraseRepository, File> {
		val file = File(tmpDir.root, "phrases.json")
		if (fixture != null) writeFixtureTo(file, fixture)
		return PhraseRepository(file) to file
	}

	@Test fun `empty fixture loads zero entries`() {
		val (r, _) = repo("empty.json")
		assertThat(r.all()).isEmpty()
	}

	@Test fun `single fixture loads one entry`() {
		val (r, _) = repo("single.json")
		val entries = r.all()
		assertThat(entries).hasSize(1)
		assertThat(entries[0].phraseUUID).isEqualTo("11111111-1111-1111-1111-111111111111")
		assertThat(entries[0].abbreviation).isEqualTo("hru")
		assertThat(entries[0].phrase).isEqualTo("how are you")
	}

	@Test fun `multi-unicode fixture preserves unicode and emoji`() {
		val (r, _) = repo("multi-unicode.json")
		val entries = r.all()
		assertThat(entries).hasSize(3)
		assertThat(entries[1].phrase).isEqualTo("thanks 🙏")
		assertThat(entries[2].phrase).isEqualTo("café — naïve résumé")
	}

	@Test fun `legacy array fixture loads as v1 entries`() {
		val (r, _) = repo("legacy-array.json")
		val entries = r.all()
		assertThat(entries).hasSize(1)
		assertThat(entries[0].phraseUUID).isEqualTo("legacy-uuid-1")
		assertThat(entries[0].abbreviation).isEqualTo("lol")
	}

	@Test fun `legacy id field substitutes for missing phraseUUID`() {
		val (r, _) = repo("legacy-id.json")
		val entries = r.all()
		assertThat(entries).hasSize(1)
		assertThat(entries[0].phraseUUID).isEqualTo("old-id-field")
		assertThat(entries[0].phrase).isEqualTo("be right back")
	}

	@Test fun `extra unknown fields are ignored`() {
		val (r, _) = repo("extra-fields.json")
		assertThat(r.all()).hasSize(1)
		assertThat(r.all()[0].phraseUUID).isEqualTo("x")
	}

	@Test fun `missing required fields default and empty-UUID entries are filtered`() {
		val (r, _) = repo("missing-fields.json")
		val entries = r.all()
		assertThat(entries).hasSize(1)
		assertThat(entries[0].phraseUUID).isEqualTo("good")
		assertThat(entries[0].createdAt).isEqualTo(0L)
		assertThat(entries[0].classMask).isEqualTo(ClassMasks.CLASS_PHRASES_MASK)
	}

	@Test fun `garbage file preserves existing in-memory state`() {
		val (r, file) = repo("single.json")
		val before = r.all()
		file.writeText("{this is not valid json")
		r.reload()
		assertThat(r.all()).isEqualTo(before)
	}

	@Test fun `unknown schema version empties state`() {
		val (r, _) = repo("unknown-version.json")
		assertThat(r.all()).isEmpty()
	}

	@Test fun `add persists and round-trips`() {
		val (r, file) = repo()
		r.add("brb", "be right back")
		val reloaded = PhraseRepository(file).all()
		assertThat(reloaded).hasSize(1)
		assertThat(reloaded[0].abbreviation).isEqualTo("brb")
		assertThat(reloaded[0].phrase).isEqualTo("be right back")
	}

	@Test fun `add with an existing abbreviation replaces the old entry (no duplicates)`() {
		// Regression: two phrases sharing an abbreviation made one unreachable and showed the other
		// twice. An abbreviation identifies one phrase — last write wins.
		val (r, _) = repo()
		r.add("brb", "be right back")
		r.add("BRB", "bathroom break") // same abbreviation, different case
		val entries = r.all()
		assertThat(entries).hasSize(1)
		assertThat(entries[0].phrase).isEqualTo("bathroom break")
	}

	@Test fun `legacy array migrates to v1 on first save`() {
		val (r, file) = repo("legacy-array.json")
		r.add("new", "freshly added")
		val onDisk = file.readText()
		assertThat(onDisk).startsWith("{")
		assertThat(onDisk).contains("\"schemaVersion\":1")
		assertThat(onDisk).contains("legacy-uuid-1")
		assertThat(onDisk).contains("freshly added")
	}

	@Test fun `persisted v1 file is loadable by a fresh repo`() {
		val (r1, file) = repo()
		r1.add("a", "alpha")
		r1.add("b", "beta")
		val r2 = PhraseRepository(file)
		assertThat(r2.all()).hasSize(2)
		assertThat(r2.all().map { it.abbreviation }).containsExactly("a", "b")
	}

	@Test fun `findByPhraseUUID returns matching entry`() {
		val (r, _) = repo("single.json")
		val found = r.findByPhraseUUID("11111111-1111-1111-1111-111111111111")
		assertThat(found).isNotNull()
		assertThat(found!!.phrase).isEqualTo("how are you")
	}

	@Test fun `findByKeys filters via translator`() {
		val (r, _) = repo("multi-unicode.json")
		val matches = r.findByKeys(listOf(1, 2)) { abbrev ->
			if (abbrev == "hi") listOf(1, 2) else null
		}
		assertThat(matches.map { it.abbreviation }).containsExactly("hi")
	}
}
