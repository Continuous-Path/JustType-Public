package org.continuouspath.justtype.langpack

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LangpackManifestTest {

	private fun manifestJson(formatVersion: Int = 1, extraLanguageFields: String = "") = """
		{
		  "formatVersion": $formatVersion,
		  "minAppVersionCode": 1,
		  "generated": "2026-07-13T00:00:00Z",
		  "languages": [
		    {
		      "id": "Espanol", "endonym": "Español", "localeCode": "es"$extraLanguageFields,
		      "db": {
		        "url": "https://example.org/EspanolDb-v3.db.gz",
		        "bytes": 4183210, "installedBytes": 12058624,
		        "sha256": "AB12cd34", "version": 3
		      }
		    }
		  ]
		}
	""".trimIndent()

	@Test fun `parses a valid manifest`() {
		val m = LangpackManifest.parse(manifestJson())
		assertThat(m).isNotNull()
		assertThat(m!!.isSupported).isTrue()
		val lang = m.languages.single()
		assertThat(lang.id).isEqualTo("Espanol")
		assertThat(lang.endonym).isEqualTo("Español")
		assertThat(lang.db.version).isEqualTo(3)
		assertThat(lang.db.bytes).isEqualTo(4183210L)
		assertThat(lang.db.sha256).isEqualTo("ab12cd34") // normalized to lowercase
	}

	@Test fun `ignores unknown keys for forward compatibility`() {
		val m = LangpackManifest.parse(manifestJson(extraLanguageFields = ""","llm": {"url": "x"}, "futureField": 7"""))
		assertThat(m).isNotNull()
		assertThat(m!!.languages).hasSize(1)
	}

	@Test fun `newer formatVersion is parsed but unsupported`() {
		val m = LangpackManifest.parse(manifestJson(formatVersion = 99))
		assertThat(m).isNotNull()
		assertThat(m!!.isSupported).isFalse()
	}

	@Test fun `malformed json returns null`() {
		assertThat(LangpackManifest.parse("not json at all")).isNull()
		assertThat(LangpackManifest.parse("""{"languages": "wrong type"}""")).isNull()
	}

	@Test fun `language entries missing required fields are skipped`() {
		val json = """
			{
			  "formatVersion": 1,
			  "languages": [
			    {"id": "NoDb", "endonym": "X"},
			    {"id": "", "db": {"url": "u", "sha256": "s"}},
			    {"id": "Ok", "db": {"url": "https://x/y.gz", "sha256": "abc"}}
			  ]
			}
		""".trimIndent()
		val m = LangpackManifest.parse(json)
		assertThat(m).isNotNull()
		assertThat(m!!.languages.map { it.id }).containsExactly("Ok")
	}

	@Test fun `missing formatVersion returns null`() {
		assertThat(LangpackManifest.parse("""{"languages": []}""")).isNull()
	}
}
