package org.continuouspath.justtype.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Recognition matcher semantics: transitions on the SHIPPED TABLE's own
 * greedy-segmentation basis, with lazy sealing so nested sub-transitions of a
 * unit typed syllable-by-syllable are never emitted (Cliff's no-flooding rule).
 */
class NgbRecognizerTest {

	private val units = setOf(
		listOf("việt", "nam"),
		listOf("chúc", "mừng"),
		listOf("chúc", "mừng", "năm", "mới"),
		listOf("sức", "khỏe"),
	)

	private fun rec() = NgbRecognizer(units)

	@Test fun `deriveContext reads the trailing segment (C2 reconstruction)`() {
		val r = rec()
		// Bare trailing syllable: context = that syllable, gate OPEN.
		assertThat(r.deriveContext(listOf("tôi", "đi"))).isEqualTo("đi" to true)
		// Trailing multi-syllable unit: context = its last syllable, gate CLOSED.
		assertThat(r.deriveContext(listOf("tôi", "việt", "nam"))).isEqualTo("nam" to false)
		// Longest match wins: the 4-syllable unit, not its 2-syllable prefix.
		assertThat(r.deriveContext(listOf("chúc", "mừng", "năm", "mới")))
			.isEqualTo("mới" to false)
		// Unit followed by a bare syllable: the FINAL segment decides.
		assertThat(r.deriveContext(listOf("việt", "nam", "ơi"))).isEqualTo("ơi" to true)
		assertThat(r.deriveContext(emptyList())).isNull()
		// Pure query: the learning stream is untouched by derivation.
		r.commit(listOf("tôi"))
		r.deriveContext(listOf("việt", "nam"))
		assertThat(r.commit(listOf("đi"))).containsExactly("tôi" to listOf("đi")).inOrder()
	}

	@Test fun `single syllables emit simple transitions`() {
		val r = rec()
		assertThat(r.commit(listOf("tôi"))).isEmpty()
		assertThat(r.commit(listOf("đi"))).containsExactly("tôi" to listOf("đi")).inOrder()
		assertThat(r.flush()).isEmpty()
	}

	@Test fun `unit typed syllable-by-syllable emits ONE whole-unit transition`() {
		val r = rec()
		r.commit(listOf("tôi"))
		r.commit(listOf("việt"))
		val sealed = r.commit(listOf("nam"))
		// việt is still open while it could extend; nothing interim emitted.
		val all = sealed + r.flush()
		assertThat(all).contains("tôi" to listOf("việt", "nam"))
		assertThat(all).doesNotContain("tôi" to listOf("việt"))
		assertThat(all).doesNotContain("việt" to listOf("nam"))
	}

	@Test fun `longest unit wins - four-syllable greeting`() {
		val r = rec()
		r.commit(listOf("xin"))
		r.commit(listOf("chúc"))
		r.commit(listOf("mừng"))
		r.commit(listOf("năm"))
		val all = r.commit(listOf("mới")) + r.flush()
		// chúc mừng (2-unit) must NOT be sealed early: the 4-syllable unit wins.
		assertThat(all).contains("xin" to listOf("chúc", "mừng", "năm", "mới"))
		assertThat(all).doesNotContain("xin" to listOf("chúc", "mừng"))
	}

	@Test fun `unit selected whole behaves identically to typed syllables`() {
		val r1 = rec()
		r1.commit(listOf("chúc"))
		val a = r1.commit(listOf("sức", "khỏe")) + r1.flush()
		val r2 = rec()
		r2.commit(listOf("chúc"))
		r2.commit(listOf("sức"))
		val b = r2.commit(listOf("khỏe")) + r2.flush()
		assertThat(a).isEqualTo(b)
	}

	@Test fun `clear drops unsealed state without emitting`() {
		val r = rec()
		r.commit(listOf("tôi"))
		r.commit(listOf("việt"))
		r.clear()
		assertThat(r.commit(listOf("nam"))).isEmpty()
	}
}
