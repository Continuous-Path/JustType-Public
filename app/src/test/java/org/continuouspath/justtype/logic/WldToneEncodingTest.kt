package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tone-keystroke encoding (Vietnamese, LayoutSpec formatVersion 2): a marked character
 * types as its base letter's key and appends the syllable's tone key at the end of the
 * key sequence — the Telex/VNI convention the trie encodes for both insert and lookup.
 */
@RunWith(RobolectricTestRunner::class)
class WldToneEncodingTest {

	@get:Rule val tmpDir = TemporaryFolder()

	// Production Vietnamese layout (TiengVietLayout.json, digits hidden):
	// key0=adkmf@_ (huyền) key1=êotjwz (nặng) key2=côruv#/- (hỏi) key3=eshylă (sắc)
	// key4=bơgưnq key5=âđpxi. (ngã)
	private val letters = listOf("adkmf@_", "êotjwz", "côruv#/-", "eshylă", "bơgưnq", "âđpxi.")
	private val huyenKey = 0
	private val sacKey = 3

	// ó and ế share the sắc tone with carriers on the SAME key (o and ê are both key1):
	// the TAV forms query must surface both when both have live continuations.
	private val toneFold = mapOf(
		'ò' to ('o' to huyenKey),
		'á' to ('a' to sacKey),
		'ó' to ('o' to sacKey),
		'ế' to ('ê' to sacKey),
	)

	private lateinit var wordDb: WordDb

	@Before fun setUp() {
		wordDb = WordDb.openStandalone(File(tmpDir.root, "tone.db"))
	}

	@After fun tearDown() {
		runCatching { wordDb.close() }
	}

	private fun newWld(): WLD = WLD(letters, wordDb, toneFoldToKey = toneFold)

	@Test fun `unmarked syllable encodes with no tone key`() {
		// h=key3 o=key1 a=key0
		assertThat(newWld().translateToKeysOrNull("hoa")).containsExactly(3, 1, 0).inOrder()
	}

	@Test fun `marked syllable appends its tone key at the end`() {
		// hòa = h,o,a keys + huyền key
		assertThat(newWld().translateToKeysOrNull("hòa")).containsExactly(3, 1, 0, huyenKey).inOrder()
		// cá = c,a keys + sắc key
		assertThat(newWld().translateToKeysOrNull("cá")).containsExactly(2, 0, sacKey).inOrder()
	}

	@Test fun `quality letters are ordinary keys — only the tone appends`() {
		// ếch: ê=key1 (marked ế appends sắc), c=key2, h=key3
		assertThat(newWld().translateToKeysOrNull("ếch")).containsExactly(1, 2, 3, sacKey).inOrder()
	}

	@Test fun `uppercase marked characters route like lowercase`() {
		assertThat(newWld().translateToKeysOrNull("Hòa")).containsExactly(3, 1, 0, huyenKey).inOrder()
	}

	@Test fun `marked characters count as word-db characters`() {
		val wld = newWld()
		assertThat(wld.isWordDbChar('ò')).isTrue()
		assertThat(wld.isWordDbChar('á')).isTrue()
	}

	@Test fun `tone key press reaches the toned word in the trie`() {
		val wld = newWld()
		wld.addWords(listOf("hoa;100", "hòa;50"), avoid = emptySet(), classMask = 0L)
		// The two words live at DIFFERENT trie nodes: [3,1,0] vs [3,1,0,huyền].
		val plain = wld.translateToKeysOrNull("hoa")!!
		val toned = wld.translateToKeysOrNull("hòa")!!
		assertThat(plain).isNotEqualTo(toned)
		assertThat(toned).isEqualTo(plain + huyenKey)
	}

	@Test fun `explicit quality letters beat generic diacritic-variant folding`() {
		// ô is an explicit letter (key2) AND a generic variant of o (key1); the variant pass
		// runs while processing o's group and must not clobber ô's own key. Same for ă (key3)
		// vs a (key0). Non-explicit variants (û) still fold to their base's key.
		val variants = mapOf(
			'o' to listOf('ô', 'Ô'),
			'a' to listOf('ă', 'Ă'),
			'u' to listOf('û', 'Û'),
		)
		val wld = WLD(letters, wordDb, diacriticVariantsByBase = variants, toneFoldToKey = toneFold)
		assertThat(wld.translateToKeysOrNull("ô")).containsExactly(2).inOrder()
		assertThat(wld.translateToKeysOrNull("ă")).containsExactly(3).inOrder()
		// hôm: h=key3, ô=key2 (NOT o's key1), m=key0
		assertThat(wld.translateToKeysOrNull("hôm")).containsExactly(3, 2, 0).inOrder()
		// û is not a layout letter → folds to u's key (key2)
		assertThat(wld.translateToKeysOrNull("û")).containsExactly(2).inOrder()
	}

	@Test fun `next-letter hints fold tone-marked chars to their display letter`() {
		val wld = newWld()
		wld.addWords(listOf("hoa;100", "hòa;50", "hên;30"), avoid = emptySet(), classMask = 1L)
		// After the h key: continuations are o (hoa), ò (hòa), ê (hên). The hint set
		// must contain the DISPLAY letters o and ê — never the marked form ò.
		val hints = wld.getNextLettersForKeys(
			keys = listOf(3),
			anyFreqMask = -1L,
			minFreqMask = 0L,
			minFreqClass = null,
		)
		assertThat(hints).containsAtLeast('o', 'ê')
		assertThat(hints).doesNotContain('ò')
	}

	@Test fun `keysRemaining is trie depth, not char-length delta`() {
		val wld = newWld()
		// hòa's encoding is 4 keys (3 letters + huyền); hoan is 4 letter keys.
		// Nonzero classMask so the anyFreqMask=-1 inclusion check passes.
		wld.addWords(listOf("hoa;100", "hòa;50", "hoan;30"), avoid = emptySet(), classMask = 1L)

		fun candidatesFor(keys: List<Int>) = wld.getDisambiguationCandidates(
			keys = keys,
			maxWordCompleteEntries = 20,
			minFreqClass = null,
			includeExcludedAtEnd = true,
			anyFreqMask = -1L,
			minFreqMask = 0L,
			baseSearchDepth = 3,
			searchExpansionDepth = 3,
			maxExaminedNodes = 10_000,
		).candidates.associate { it.lowerWord to it.keysRemaining }

		// After the 3 letter keys: hoa is fully typed; hòa and hoan each need 1 more key.
		val afterLetters = candidatesFor(wld.translateToKeysOrNull("hoa")!!)
		assertThat(afterLetters["hoa"]).isEqualTo(0)
		assertThat(afterLetters["hòa"]).isEqualTo(1)
		assertThat(afterLetters["hoan"]).isEqualTo(1)

		// After the huyền key: hòa is fully typed despite being 3 chars for 4 keys.
		val afterTone = candidatesFor(wld.translateToKeysOrNull("hòa")!!)
		assertThat(afterTone["hòa"]).isEqualTo(0)
	}

	@Test fun `TAV hints skip the tone position and index letters past it`() {
		val wld = WLD(letters, wordDb, toneFoldToKey = toneFold, toneAfterVowel = true)
		// hòa TAV = h,o,huyền,a — chars lag keys by one after the tone.
		wld.addWords(listOf("hòa;50"), avoid = emptySet(), classMask = 1L)
		fun hints(keys: List<Int>) = wld.getNextLettersForKeys(keys, -1L, 0L, null)
		// After h,o: the next KEYSTROKE is the tone — no letter hint from hòa
		// (offering 'a' would light a key the trie doesn't accept yet).
		assertThat(hints(listOf(3, 1))).doesNotContain('a')
		// After h,o,huyền: 'a' is the valid next letter (char index 2, key index 3).
		assertThat(hints(listOf(3, 1, huyenKey))).contains('a')
	}

	@Test fun `next-tone-key prediction offers only tones that complete an n-letter word`() {
		val wld = newWld()
		// hò (h,o + huyền = keys 3,1,0) shares its key sequence with hoa (h,o,a —
		// key 0 is ALSO a's letter key); há = h,a + sắc (keys 3,0,3).
		wld.addWords(listOf("hoa;100", "hòa;50", "hò;40", "há;30"), avoid = emptySet(), classMask = 1L)

		fun tones(keys: List<Int>) = wld.getNextToneKeysForKeys(keys, -1L, 0L, null)

		// After h,o: huyền completes "hò" — the child node holds BOTH hò (2 letters,
		// tone path) and hoa (3 letters, key 0 as LETTER a); only hò makes the tone apply.
		assertThat(tones(listOf(3, 1))).containsExactly(huyenKey)
		// After h,o,a: huyền completes "hòa".
		assertThat(tones(listOf(3, 1, 0))).containsExactly(huyenKey)
		// After h,a: sắc completes "há".
		assertThat(tones(listOf(3, 0))).containsExactly(sacKey)
		// After h alone no 1-letter toned word exists; after a complete toned word, nothing.
		assertThat(tones(listOf(3))).isEmpty()
		assertThat(tones(wld.translateToKeysOrNull("hòa")!!)).isEmpty()
	}

	private fun newTavWld(): WLD = WLD(letters, wordDb, toneFoldToKey = toneFold, toneAfterVowel = true)

	private fun WLD.forms(keys: List<Int>) = getNextToneFormsForKeys(keys, -1L, 0L, null)

	@Test fun `TAV tone forms include deeper continuations, unlike TAE prediction`() {
		val wld = newTavWld()
		// hòa TAV = h,o,huyền,a — after h,o the tone does NOT complete a word,
		// but it is the valid next keystroke and its form is o+huyền = ò.
		wld.addWords(listOf("hòa;50"), avoid = emptySet(), classMask = 1L)
		assertThat(wld.forms(listOf(3, 1))).containsExactly(huyenKey, setOf('ò'))
	}

	@Test fun `TAV tone forms exclude words reaching the tone key as a letter`() {
		val wld = newTavWld()
		// hoa = h,o,a where a's key IS huyền's key — the child exists but holds no
		// word whose char at the carrier position folds to huyền, so no form shows.
		wld.addWords(listOf("hoa;100"), avoid = emptySet(), classMask = 1L)
		assertThat(wld.forms(listOf(3, 1))).isEmpty()
	}

	@Test fun `TAV tone forms collect every viable carrier on the previous key`() {
		val wld = newTavWld()
		// có (c,o+sắc) and hếp (h,ê+sắc,p): after c... no — separate prefixes;
		// share the prefix instead: hó (h,o+sắc) and hếp (h,ê+sắc,p) both sit at
		// keys h,key1 with sắc next; carriers o and ê are BOTH on key1.
		wld.addWords(listOf("hó;40", "hếp;30"), avoid = emptySet(), classMask = 1L)
		assertThat(wld.forms(listOf(3, 1))).containsExactly(sacKey, setOf('ó', 'ế'))
	}

	@Test fun `TAV tone forms end after the word's single tone`() {
		val wld = newTavWld()
		wld.addWords(listOf("hòa;50"), avoid = emptySet(), classMask = 1L)
		// After h,o,huyền,a the word is complete — no further tone is ever valid.
		assertThat(wld.forms(wld.translateToKeysOrNull("hòa")!!)).isEmpty()
	}

	@Test fun `TAV tone forms respect the inclusion masks`() {
		val wld = newTavWld()
		wld.addWords(listOf("hòa;50"), avoid = emptySet(), classMask = 2L)
		// anyFreqMask selects class bits: mask 2 matches, mask 1 excludes the word.
		assertThat(wld.getNextToneFormsForKeys(listOf(3, 1), 2L, 0L, null)).isNotEmpty()
		assertThat(wld.getNextToneFormsForKeys(listOf(3, 1), 1L, 0L, null)).isEmpty()
	}

	@Test fun `tone forms query is inert in TAE mode`() {
		val wld = newWld()
		wld.addWords(listOf("hòa;50"), avoid = emptySet(), classMask = 1L)
		assertThat(wld.forms(listOf(3, 1))).isEmpty()
	}
}
