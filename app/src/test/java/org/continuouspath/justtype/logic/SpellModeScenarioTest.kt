package org.continuouspath.justtype.logic

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LanguageEntry
import org.continuouspath.justtype.LanguageRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests mirroring the Two-Key Spell Mode bug scenarios. Drives JTUI.buttonPressed
 * through the Letter/Symbol → Two-Key Spell → variant-drill flow and asserts page navigation.
 *
 * BUG #1: after selecting a diacritic variant, the mode must return to the *Optimized* spell base
 *         layer ("Spelling"), not the Alphabetic one ("SpellingAlpha").
 * BUG #2: DONE on the spell base layer (Letter/Symbol context) must exit to the caller.
 */
@RunWith(RobolectricTestRunner::class)
class SpellModeScenarioTest {

	@get:Rule val tmpDir = TemporaryFolder()

	private lateinit var h: TestJtui
	private val jtui get() = h.jtui
	private val committed get() = h.committed
	private val autospaceSuppressed get() = h.autospaceSuppressed
	private val lastSnapshot get() = h.lastSnapshot

	@Before fun setUp() {
		h = TestJtui(tmpDir.root) { repo ->
			// Deterministic: all variants drill.
			repo.putString(Constants.KEY_SPELL_DIACRITIC_SCOPE, Constants.DIACRITIC_SCOPE_ALL)
		}
	}

	@After fun tearDown() {
		h.tearDown()
	}

	/** Letter/Symbol page key 0 = LETTER SPELL MODE → enters the Optimized spell base layer. */
	private fun enterLetterSymbolSpell() {
		jtui.setCurrentPage("LetterSymbol")
		jtui.buttonPressed(0)
	}

	@Test fun `entering Two-Key Spell from Letter-Symbol shows the Optimized base layer`() {
		enterLetterSymbolSpell()
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling")
	}

	@Test fun `selecting a u-variant returns to the Optimized base layer not Alphabetic (BUG 1)`() {
		enterLetterSymbolSpell()
		jtui.buttonPressed(4) // LUFCY group → Spell4
		assertThat(jtui.getCurrentPage()).isEqualTo("Spell4")
		jtui.buttonPressed(2) // 'u' variants preview key → SpellVar_Spelling_u_p
		assertThat(jtui.getCurrentPage()).isEqualTo("SpellVar_Spelling_u_p")
		jtui.buttonPressed(0) // base 'u' at NW emits and returns to the base layer
		assertThat(committed.toString().lowercase()).contains("u") // case depends on shift state
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling") // NOT "SpellingAlpha"
	}

	@Test fun `DONE on the spell base layer exits to the Letter-Symbol caller (BUG 2)`() {
		enterLetterSymbolSpell()
		jtui.buttonPressed(4) // LUFCY → Spell4
		jtui.buttonPressed(2) // u variants → SpellVar_Spelling_u_p
		jtui.buttonPressed(0) // select base 'u' → back to "Spelling"
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling")
		jtui.buttonPressed(6) // DONE
		assertThat(jtui.getCurrentPage()).isEqualTo("LetterSymbol")
	}

	@Test fun `DONE in Letter-Symbol mode emits no trailing autospace`() {
		enterLetterSymbolSpell()
		jtui.buttonPressed(4) // LUFCY → Spell4
		jtui.buttonPressed(2) // u variants
		jtui.buttonPressed(0) // emit base 'u'
		val afterChar = committed.toString()
		jtui.buttonPressed(6) // DONE — must NOT append a space
		assertThat(committed.toString()).isEqualTo(afterChar)
		assertThat(committed.toString()).doesNotContain(" ")
	}

	@Test fun `autospace is suppressed across the whole Letter-Symbol session including 123 Numbers`() {
		assertThat(autospaceSuppressed).isFalse()
		jtui.setCurrentPage("LetterSymbol")
		assertThat(autospaceSuppressed).isTrue()
		jtui.buttonPressed(0) // → Two-Key Spell
		assertThat(autospaceSuppressed).isTrue()
		jtui.setCurrentPage("Numbers1") // switch to 123 Numbers sub-mode — still suppressed
		assertThat(autospaceSuppressed).isTrue()
		jtui.setCurrentPage("Main") // exit to Main — autospacing resumes
		assertThat(autospaceSuppressed).isFalse()
	}

	@Test fun `ADD NEW WORD does not suppress autospace`() {
		jtui.setCurrentPage("Navigation")
		jtui.buttonPressed(0) // ADD NEW WORD → Spelling (accumulate); not a Letter/Symbol session
		assertThat(autospaceSuppressed).isFalse()
	}

	@Test fun `Letter-Symbol center label is the uppercase mode string`() {
		jtui.setCurrentPage("LetterSymbol")
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.centerSpace).isEqualTo("LETTERS /\nSYMBOLS")
	}

	@Test fun `Symbols back key is BACK when entered from Letter-Symbol, BACK TO MAIN from Main`() {
		// From Letter/Symbol mode → key 1 (the KF_BackToCaller key) reads "BACK".
		jtui.setCurrentPage("LetterSymbol")
		jtui.setCurrentPage("Symbols1")
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.keyLabels?.get(1)).isEqualTo("BACK")
		// From Main → the same key reads "BACK\nTO\nMAIN".
		jtui.setCurrentPage("Main")
		jtui.setCurrentPage("Symbols1")
		jtui.forceUpdateUi()
		assertThat(lastSnapshot?.keyLabels?.get(1)).isEqualTo("BACK\nTO\nMAIN")
	}

	@Test fun `ADD NEW WORD accumulates the spelled string and DONE exits to Main`() {
		// Navigation page key 0 = ADD NEW WORD → Optimized spell base in accumulate mode.
		jtui.setCurrentPage("Navigation")
		jtui.buttonPressed(0)
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling")
		jtui.buttonPressed(4) // LUFCY → Spell4
		jtui.buttonPressed(2) // u variants → SpellVar_Spelling_u_p
		jtui.buttonPressed(0) // select base 'u'
		// Accumulate mode tracks the word (customWordString) rather than committing each char.
		assertThat(jtui.getCurrentCustomWord().lowercase()).contains("u")
		assertThat(committed.toString()).isEmpty()
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling")
		jtui.buttonPressed(6) // DONE → adds word, returns to Main (caller was Navigation)
		assertThat(jtui.getCurrentPage()).isEqualTo("Main")
	}

	@Test fun `Off diacritic scope makes a vowel key emit directly with no variant page`() {
		SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
			.putString(Constants.KEY_SPELL_DIACRITIC_SCOPE, Constants.DIACRITIC_SCOPE_OFF)
		enterLetterSymbolSpell() // rebuilds the spell pages under the Off scope
		jtui.buttonPressed(4) // LUFCY → Spell4
		jtui.buttonPressed(2) // 'u' key: no in-scope variants → emits directly, returns to the base layer
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling") // NOT "SpellVar_Spelling_u_p"
		assertThat(committed.toString().lowercase()).contains("u")
	}

	/** Constrain to a Spanish set (via the registry read-model) so each vowel has few (<= 6) in-scope variants. */
	private fun useSpanishScope() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		LanguageRegistry.save(
			repo,
			listOf(LanguageEntry("Espanol", "es", "áéíóúüñ", present = true, dbFileName = "EspanolDbActive.db")),
		)
		repo.putString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ESPANOL)
		repo.putString(Constants.KEY_SPELL_DIACRITIC_SCOPE, Constants.DIACRITIC_SCOPE_CURRENT)
	}

	@Test fun `hiding accented keys derives the base-only Spanish view`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ESPANOL)
		repo.putBoolean(Constants.KEY_SHOW_ACCENTED_KEYS, false)
		jtui.init()

		// Accents fold onto their base letters' keys: qué types on the base sequence.
		assertThat(jtui.mapWordToKeyIndices("qué")).isEqualTo(listOf(1, 4, 5))
		assertThat(jtui.mapWordToKeyIndices("qué")).isEqualTo(jtui.mapWordToKeyIndices("que"))

		// Key faces show only base letters (the simpler-appearing keyboard).
		jtui.setCurrentPage("Main")
		jtui.forceUpdateUi()
		val faces = lastSnapshot!!.keyLabelGrids.flatten().joinToString("").lowercase()
		for (accent in "áéíóúñ") {
			assertThat(faces).doesNotContain(accent.toString())
		}

		// Toggling back ON restores the mixed-mapping layout (é on its own key).
		repo.putBoolean(Constants.KEY_SHOW_ACCENTED_KEYS, true)
		jtui.init()
		assertThat(jtui.mapWordToKeyIndices("qué")).isEqualTo(listOf(1, 4, 2))
	}

	@Test fun `switching typing language to Espanol loads the Spanish DB and its diacritics end-to-end`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		repo.putString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ESPANOL)
		repo.putString(Constants.KEY_SPELL_DIACRITIC_SCOPE, Constants.DIACRITIC_SCOPE_CURRENT)
		jtui.init() // re-open on the real EspanolDb asset + re-sync the registry + rebuild pages

		// The registry bridge derived Español's diacritic set from the shipped Spanish DB.
		val es = LanguageRegistry.load(repo).firstOrNull { it.name == Constants.TYPING_LANGUAGE_ESPANOL }
		assertThat(es).isNotNull()
		assertThat(es!!.present).isTrue()
		assertThat(es.diacriticSet.toSet()).containsAtLeast('ñ', 'ú', 'á', 'ü')

		// The Spanish DB's baked layoutJson replaced the English Optimized layout — v5 MIXED
		// MAPPING: PAZD on page Key 0, and accents are first-class letters on their own keys
		// (é on the i-key, ú on the accent key), so qué spans three keys.
		assertThat(jtui.mapWordToKeyIndices("paz")).isEqualTo(listOf(0, 0, 0))
		assertThat(jtui.mapWordToKeyIndices("qué")).isEqualTo(listOf(1, 4, 2))

		// List-function badges follow the language's functionKeys (symbols=internal 2 -> page
		// Key 3), not the English default positions (page Keys 2/4/7).
		jtui.setCurrentPage("Main")
		jtui.forceUpdateUi()
		val grids = lastSnapshot!!.keyLabelGrids
		assertThat(grids[3][1]).isEqualTo("SYM")
		assertThat(grids[5][1]).isEqualTo("FNS")
		assertThat(grids[7][1]).isEqualTo("NAV")
		assertThat(grids[2][1]).isNotEqualTo("SYM")
		// Hint graying gets the slot label -> full-chars map (digits 2-4 must light "15_").
		assertThat(lastSnapshot!!.slotCellChars["15_"]).isEqualTo("12345_")
		assertThat(lastSnapshot!!.slotCellChars["60."]).isEqualTo("67890.")

		// LETTER SPELL MODE (scope = current language): ú is an explicit layout letter, so u's
		// drill offers only the still-folded ü — ú never appears behind its base.
		enterLetterSymbolSpell()
		jtui.buttonPressed(5) // UNMÁH → Spell5 (contains 'u' → in-scope variant ü only)
		jtui.forceUpdateUi()
		assertThat(lastSnapshot!!.keyLabels.any { it.lowercase().contains("ü") }).isTrue()
		assertThat(lastSnapshot!!.keyLabels.none { it.lowercase().contains("ú") }).isTrue()
	}

	@Test fun `pinning the English optimized layout keeps it while typing Espanol`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		// setUp's init() ran on English, so its layoutJson is already mirrored in the registry.
		repo.putString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, Constants.TYPING_LANGUAGE_ENGLISH)
		repo.putString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ESPANOL)
		jtui.init()

		// Spanish word DB, English key groups: u stays on LUFCY (key 3), not on UNMÁH (key 4).
		assertThat(jtui.mapWordToKeyIndices("lufcy")).isEqualTo(listOf(3, 3, 3, 3, 3))
		assertThat(jtui.mapWordToKeyIndices("paz")).isNotEqualTo(listOf(0, 0, 0))

		// Unpin: back to matching the typing language — the Spanish layout returns.
		repo.putString(Constants.KEY_OPTIMIZED_LAYOUT_SOURCE, Constants.LAYOUT_SOURCE_MATCH)
		jtui.init()
		assertThat(jtui.mapWordToKeyIndices("paz")).isEqualTo(listOf(0, 0, 0))
	}

	// The 60+ digit slot owns lufcy's former free cell, so 'u' variants use the
	// packed flow: base+variants preview together on u's key, drill to select.
	@Test fun `u variants use the packed preview and drill (digit slot owns the neighbor cell)`() {
		useSpanishScope()
		enterLetterSymbolSpell()
		jtui.buttonPressed(4) // LUFCY → Spell4
		jtui.forceUpdateUi()
		val previewPos = lastSnapshot!!.keyLabels.indexOfFirst { it.lowercase().contains("ú") }
		assertThat(previewPos).isAtLeast(0)
		assertThat(lastSnapshot!!.keyLabels[previewPos].lowercase()).contains("u") // base rides with variants
		jtui.buttonPressed(previewPos)
		assertThat(jtui.getCurrentPage()).isEqualTo("SpellVar_Spelling_u_p")
		jtui.forceUpdateUi()
		val basePos = lastSnapshot!!.keyLabels.indexOfFirst { it.equals("u", ignoreCase = true) }
		assertThat(basePos).isAtLeast(0)
		jtui.buttonPressed(basePos) // base emits from the drill page
		assertThat(committed.toString().lowercase()).contains("u")
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling")
	}

	@Test fun `packed drill emits the chosen accent`() {
		useSpanishScope()
		enterLetterSymbolSpell()
		jtui.buttonPressed(4) // LUFCY → Spell4
		jtui.forceUpdateUi()
		val previewPos = lastSnapshot!!.keyLabels.indexOfFirst { it.lowercase().contains("ú") }
		jtui.buttonPressed(previewPos)
		assertThat(jtui.getCurrentPage()).isEqualTo("SpellVar_Spelling_u_p")
		jtui.forceUpdateUi()
		val accentPos = lastSnapshot!!.keyLabels.indexOfFirst { it.equals("ú", ignoreCase = true) }
		assertThat(accentPos).isAtLeast(0)
		jtui.buttonPressed(accentPos)
		assertThat(committed.toString().lowercase()).contains("ú")
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling")
	}

	@Test fun `single in-scope variant also drills via the packed preview`() {
		val repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
		LanguageRegistry.save(
			repo,
			listOf(LanguageEntry("Espanol", "es", "áéíóúñ", present = true, dbFileName = "EspanolDbActive.db")),
		)
		repo.putString(Constants.KEY_TYPING_LANGUAGE, Constants.TYPING_LANGUAGE_ESPANOL)
		repo.putString(Constants.KEY_SPELL_DIACRITIC_SCOPE, Constants.DIACRITIC_SCOPE_CURRENT)
		enterLetterSymbolSpell()
		jtui.buttonPressed(4) // LUFCY → Spell4
		jtui.forceUpdateUi()
		val previewPos = lastSnapshot!!.keyLabels.indexOfFirst { it.lowercase().contains("ú") }
		assertThat(previewPos).isAtLeast(0)
		jtui.buttonPressed(previewPos)
		assertThat(jtui.getCurrentPage()).isEqualTo("SpellVar_Spelling_u_p")
		jtui.forceUpdateUi()
		val accentPos = lastSnapshot!!.keyLabels.indexOfFirst { it.equals("ú", ignoreCase = true) }
		jtui.buttonPressed(accentPos)
		assertThat(committed.toString().lowercase()).contains("ú")
		assertThat(jtui.getCurrentPage()).isEqualTo("Spelling")
	}
}
