package org.continuouspath.justtype.hierarchy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiacriticDerivationTest {

	@Test fun `scan collects non-ASCII letters lowercased`() {
		assertThat(DiacriticDerivation.scanWords(listOf("café", "naïve", "hello"))).containsExactly('é', 'ï')
	}

	@Test fun `scan excludes ASCII letters, digits and punctuation`() {
		assertThat(DiacriticDerivation.scanWords(listOf("hello", "1234", "a-b_c", "don't"))).isEmpty()
	}

	@Test fun `scan case-folds accented capitals`() {
		assertThat(DiacriticDerivation.scanWords(listOf("CAFÉ", "Über"))).containsExactly('é', 'ü')
	}

	@Test fun `scan captures Vietnamese multi-mark letters`() {
		assertThat(DiacriticDerivation.scanWords(listOf("Việt", "Tiếng"))).containsAtLeast('ệ', 'ế')
	}

	@Test fun `encode is codepoint-sorted and decode round-trips`() {
		val set = setOf('ï', 'é', 'à')
		val encoded = DiacriticDerivation.encode(set)
		assertThat(encoded).isEqualTo("àéï")
		assertThat(DiacriticDerivation.decode(encoded)).isEqualTo(set)
	}
}
