package org.continuouspath.justtype

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanonicalLanguagesTest {

	@Test fun `every id is ASCII and space-free so it is a portable filename`() {
		CanonicalLanguages.ALL.forEach { lang ->
			assertThat(lang.id.all { it.code in 0x21..0x7E }).isTrue() // printable ASCII, no space/control
		}
	}

	@Test fun `English keeps its persisted id and endonym (must match the shipped asset)`() {
		val en = CanonicalLanguages.byId("English")
		assertThat(en).isNotNull()
		assertThat(en!!.endonym).isEqualTo("English")
		assertThat(en.localeCode).isEqualTo("en")
	}

	@Test fun `Spanish is endonym Espanol with an ASCII-folded id`() {
		val es = CanonicalLanguages.byLocale("es")!!
		assertThat(es.id).isEqualTo("Espanol")
		assertThat(es.endonym).isEqualTo("Español")
	}

	@Test fun `lookups resolve and endonymFor falls back to the id`() {
		assertThat(CanonicalLanguages.byEndonym("Français")?.id).isEqualTo("Francais")
		assertThat(CanonicalLanguages.endonymFor("Espanol")).isEqualTo("Español")
		assertThat(CanonicalLanguages.endonymFor("Klingon")).isEqualTo("Klingon") // unknown → returned as-is
	}
}
