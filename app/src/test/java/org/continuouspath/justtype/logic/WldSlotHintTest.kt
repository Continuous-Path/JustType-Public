package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.ClassMasks
import org.continuouspath.justtype.hierarchy.loadDiacriticTree
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Next-key hints for string-slot characters (Espanol v5): a custom digit string
 * ("206-227-0191") must contribute its digits/puncts to getNextLettersForKeys so
 * the "15_"/"60." slot cells highlight while typing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WldSlotHintTest {

	@get:Rule val tmpDir = TemporaryFolder()

	// Espanol v5 lettersPerKey as shipped (slot chars flattened in).
	private val esLetters = listOf(
		"pazd12345_#/-@+",
		"qñlcok",
		"igwés67890.",
		"túyríó",
		"unmáh",
		"febvjx",
	)

	private lateinit var wordDb: WordDb

	@Before fun setUp() {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		wordDb = WordDb.open(tmpDir.root, app.assets)
	}

	@After fun tearDown() {
		runCatching { wordDb.close() }
	}

	private fun makeWld(): WLD {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		val variantsByBase = loadDiacriticTree(app.assets).entries.associate { (base, group) ->
			base to group.variants.flatMap { v ->
				buildList {
					v.char.firstOrNull()?.let { add(it) }
					v.upper?.firstOrNull()?.let { add(it) }
				}
			}
		}
		return WLD(esLetters, wordDb, diacriticVariantsByBase = variantsByBase).also {
			it.addWords(listOf("aba;1000", "ada;500"), emptySet(), ClassMasks.CLASS_JUSTTYPE_MASK)
		}
	}

	@Test fun `custom digit string maps through slot chars to keys`() {
		val wld = makeWld()
		// 2->key0, 0->key2, 6->key2, ---key0...
		assertThat(wld.translateToKeysOrNull("206-227-0191"))
			.containsExactly(0, 2, 2, 0, 0, 0, 2, 0, 2, 0, 2, 0).inOrder()
	}

	@Test fun `custom digit string contributes digit hints with production masks`() {
		val wld = makeWld()
		wld.addCustomWord("206-227-0191")
		// Production hint mask shape (Include Custom Words ON): custom class rides anyFreqMask.
		val hints = wld.getNextLettersForKeys(
			listOf(0),
			anyFreqMask = ClassMasks.CLASS_JUSTTYPE_MASK or ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK,
			minFreqMask = 0L,
			minFreqClass = null,
		)
		assertThat(hints).contains('0')

		// After "20" (keys 0,2) the next char is '6' — also a 60.-slot char.
		val hints2 = wld.getNextLettersForKeys(
			listOf(0, 2),
			anyFreqMask = ClassMasks.CLASS_JUSTTYPE_MASK or ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK,
			minFreqMask = 0L,
			minFreqClass = null,
		)
		assertThat(hints2).contains('6')
	}

	@Test fun `frequency-filtered masks still admit custom digit hints`() {
		val wld = makeWld()
		wld.addCustomWord("206-227-0191")
		// Freq filter ON: JustType words demoted to minFreqMask, custom stays on anyFreqMask.
		val hints = wld.getNextLettersForKeys(
			listOf(0),
			anyFreqMask = ClassMasks.CLASS_USER_ADDED_CUSTOM_COMBINED_MASK,
			minFreqMask = ClassMasks.CLASS_JUSTTYPE_MASK,
			minFreqClass = 5,
		)
		assertThat(hints).contains('0')
	}
}
