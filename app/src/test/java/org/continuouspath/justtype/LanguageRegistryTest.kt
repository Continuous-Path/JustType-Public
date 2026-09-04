package org.continuouspath.justtype

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LanguageRegistryTest {

	private lateinit var repo: SettingsRepository

	@Before fun setUp() {
		SettingsRepository.resetInstanceForTesting()
		repo = SettingsRepository.getInstance(ApplicationProvider.getApplicationContext())
	}

	@After fun tearDown() {
		SettingsRepository.resetInstanceForTesting()
	}

	private val french = "àâçéèêëîïôûùü"
	private val polish = "ąćęłńóśźż"

	private fun seed() {
		LanguageRegistry.save(
			repo,
			listOf(
				LanguageEntry("English", "en", "", present = true, dbFileName = "EnglishDbActive.db"),
				LanguageEntry("French", "fr", french, present = true, dbFileName = "FrenchDbActive.db"),
				LanguageEntry("Polish", "pl", polish, present = false, dbFileName = "PolishDbActive.db"),
			),
		)
	}

	private fun allowed(scope: String, active: Set<String>) = LanguageRegistry.allowedCharsFor(LanguageRegistry.load(repo), scope, active)

	@Test fun `ensureDefaults seeds a present English entry`() {
		LanguageRegistry.ensureDefaults(repo)
		val items = LanguageRegistry.load(repo)
		assertThat(items.map { it.name }).containsExactly("English")
		assertThat(items.single().present).isTrue()
	}

	@Test fun `all scope is null (no filter)`() {
		seed()
		assertThat(allowed(Constants.DIACRITIC_SCOPE_ALL, setOf("English"))).isNull()
	}

	@Test fun `off scope is empty`() {
		seed()
		assertThat(allowed(Constants.DIACRITIC_SCOPE_OFF, setOf("English"))).isEmpty()
	}

	@Test fun `current scope unions only the active languages`() {
		seed()
		assertThat(allowed(Constants.DIACRITIC_SCOPE_CURRENT, setOf("French"))).containsExactlyElementsIn(french.toSet())
	}

	@Test fun `loaded scope unions present languages and excludes absent ones`() {
		seed()
		val a = allowed(Constants.DIACRITIC_SCOPE_LOADED, setOf("English"))
		assertThat(a).containsExactlyElementsIn(french.toSet()) // English is empty, French present; Polish absent
		assertThat(a).containsNoneIn(polish.toSet())
	}

	@Test fun `setDiacriticSet and mergeDiacriticSet update the entry`() {
		LanguageRegistry.setDiacriticSet(repo, "English", setOf('é'))
		assertThat(LanguageRegistry.load(repo).single { it.name == "English" }.diacriticSet).isEqualTo("é")
		LanguageRegistry.mergeDiacriticSet(repo, "English", setOf('ñ', 'é'))
		assertThat(LanguageRegistry.load(repo).single { it.name == "English" }.diacriticSet).isEqualTo("éñ")
	}

	@Test fun `activeLanguageNames defaults to the typing language`() {
		assertThat(LanguageRegistry.activeLanguageNames(repo)).containsExactly("English")
	}

	@Test fun `layoutJson round-trips and setters preserve other fields`() {
		LanguageRegistry.save(
			repo,
			listOf(LanguageEntry("Espanol", "es", "áé", present = true, dbFileName = "EspanolDbActive.db", dbVersion = 3)),
		)
		LanguageRegistry.setLayoutJson(repo, "Espanol", """{"lettersPerKey":[]}""")
		var es = LanguageRegistry.load(repo).single()
		assertThat(es.layoutJson).contains("lettersPerKey")
		assertThat(es.dbVersion).isEqualTo(3) // preserved

		LanguageRegistry.setDiacriticSet(repo, "Espanol", setOf('ñ'))
		es = LanguageRegistry.load(repo).single()
		assertThat(es.diacriticSet).isEqualTo("ñ")
		assertThat(es.layoutJson).contains("lettersPerKey") // preserved
		assertThat(es.dbVersion).isEqualTo(3) // preserved

		LanguageRegistry.setLayoutJson(repo, "Francais", "{}") // creates a default entry
		assertThat(LanguageRegistry.load(repo).first { it.name == "Francais" }.layoutJson).isEqualTo("{}")
	}
}
