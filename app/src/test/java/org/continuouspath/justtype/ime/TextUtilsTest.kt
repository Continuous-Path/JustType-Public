package org.continuouspath.justtype.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TextUtilsTest {

	// ── isEosChar ─────────────────────────────────────────────────────────

	@Test
	fun `isEosChar returns true for period, exclamation, question`() {
		assertThat(TextUtils.isEosChar('.')).isTrue()
		assertThat(TextUtils.isEosChar('!')).isTrue()
		assertThat(TextUtils.isEosChar('?')).isTrue()
	}

	@Test
	fun `isEosChar returns false for non-punctuation`() {
		assertThat(TextUtils.isEosChar(',')).isFalse()
		assertThat(TextUtils.isEosChar('a')).isFalse()
		assertThat(TextUtils.isEosChar(' ')).isFalse()
		assertThat(TextUtils.isEosChar('\n')).isFalse()
	}

	// ── isLineBreak ──────────────────────────────────────────────────────

	@Test
	fun `isLineBreak recognizes all Unicode line break characters`() {
		assertThat(TextUtils.isLineBreak('\n')).isTrue()
		assertThat(TextUtils.isLineBreak('\r')).isTrue()
		assertThat(TextUtils.isLineBreak('\u0085')).isTrue() // NEL
		assertThat(TextUtils.isLineBreak('\u2028')).isTrue() // LINE SEPARATOR
		assertThat(TextUtils.isLineBreak('\u2029')).isTrue() // PARAGRAPH SEPARATOR
	}

	@Test
	fun `isLineBreak returns false for non-break whitespace`() {
		assertThat(TextUtils.isLineBreak(' ')).isFalse()
		assertThat(TextUtils.isLineBreak('\t')).isFalse()
	}

	// ── isPasswordEditor ─────────────────────────────────────────────────

	@Test
	fun `isPasswordEditor returns false for null`() {
		assertThat(TextUtils.isPasswordEditor(null)).isFalse()
	}

	@Test
	fun `isPasswordEditor detects text passwords`() {
		assertThat(TextUtils.isPasswordEditor(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))).isTrue()
		assertThat(TextUtils.isPasswordEditor(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))).isTrue()
		assertThat(TextUtils.isPasswordEditor(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))).isTrue()
	}

	@Test
	fun `isPasswordEditor detects number passwords`() {
		assertThat(TextUtils.isPasswordEditor(editorInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))).isTrue()
	}

	@Test
	fun `isPasswordEditor returns false for normal text`() {
		assertThat(TextUtils.isPasswordEditor(editorInfo(InputType.TYPE_CLASS_TEXT))).isFalse()
		assertThat(TextUtils.isPasswordEditor(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))).isFalse()
	}

	// ── isNoAutospaceField ───────────────────────────────────────────────

	@Test
	fun `isNoAutospaceField returns false for null`() {
		assertThat(TextUtils.isNoAutospaceField(null)).isFalse()
	}

	@Test
	fun `isNoAutospaceField suppresses for phone, number, datetime`() {
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_PHONE))).isTrue()
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_NUMBER))).isTrue()
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_DATETIME))).isTrue()
	}

	@Test
	fun `isNoAutospaceField suppresses for password, URI, email, filter`() {
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))).isTrue()
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI))).isTrue()
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))).isTrue()
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))).isTrue()
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER))).isTrue()
	}

	@Test
	fun `isNoAutospaceField allows normal text fields`() {
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_TEXT))).isFalse()
		assertThat(TextUtils.isNoAutospaceField(editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))).isFalse()
	}

	@Test
	fun `isNoAutospaceField allows text with NO_SUGGESTIONS flag`() {
		// This is the autospace bug regression test — TYPE_TEXT_FLAG_NO_SUGGESTIONS
		// should NOT suppress autospace. Google Keep, Search, and many apps set this flag.
		val noSuggestions = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
		assertThat(TextUtils.isNoAutospaceField(editorInfo(noSuggestions))).isFalse()
	}

	@Test
	fun `isNoAutospaceField allows multi-line text`() {
		val multiLine = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
		assertThat(TextUtils.isNoAutospaceField(editorInfo(multiLine))).isFalse()
	}

	// ── shouldShowKeyboard ───────────────────────────────────────────────

	@Test
	fun `shouldShowKeyboard returns false for null and TYPE_NULL`() {
		assertThat(TextUtils.shouldShowKeyboard(null)).isFalse()
		assertThat(TextUtils.shouldShowKeyboard(editorInfo(0))).isFalse()
	}

	@Test
	fun `shouldShowKeyboard returns true for text, number, phone, datetime`() {
		assertThat(TextUtils.shouldShowKeyboard(editorInfo(InputType.TYPE_CLASS_TEXT))).isTrue()
		assertThat(TextUtils.shouldShowKeyboard(editorInfo(InputType.TYPE_CLASS_NUMBER))).isTrue()
		assertThat(TextUtils.shouldShowKeyboard(editorInfo(InputType.TYPE_CLASS_PHONE))).isTrue()
		assertThat(TextUtils.shouldShowKeyboard(editorInfo(InputType.TYPE_CLASS_DATETIME))).isTrue()
	}

	// ── toTitleCase ──────────────────────────────────────────────────────

	@Test
	fun `toTitleCase capitalizes each word`() {
		assertThat(TextUtils.toTitleCase("hello world")).isEqualTo("Hello World")
	}

	@Test
	fun `toTitleCase handles hyphens and apostrophes`() {
		assertThat(TextUtils.toTitleCase("mother-in-law")).isEqualTo("Mother-In-Law")
		assertThat(TextUtils.toTitleCase("it's fine")).isEqualTo("It'S Fine")
	}

	@Test
	fun `toTitleCase lowercases non-initial letters`() {
		assertThat(TextUtils.toTitleCase("HELLO WORLD")).isEqualTo("Hello World")
	}

	// ── toSentenceCase ───────────────────────────────────────────────────

	@Test
	fun `toSentenceCase capitalizes after sentence enders`() {
		assertThat(TextUtils.toSentenceCase("hello. world")).isEqualTo("Hello. World")
		assertThat(TextUtils.toSentenceCase("hi! there")).isEqualTo("Hi! There")
	}

	// ── isLikelyAbbreviation ─────────────────────────────────────────────

	@Test
	fun `isLikelyAbbreviation detects common abbreviations`() {
		assertThat(TextUtils.isLikelyAbbreviation("Mr.", 2)).isTrue()
		assertThat(TextUtils.isLikelyAbbreviation("Dr.", 2)).isTrue()
		assertThat(TextUtils.isLikelyAbbreviation("Inc.", 3)).isTrue()
	}

	@Test
	fun `isLikelyAbbreviation returns false for normal words`() {
		assertThat(TextUtils.isLikelyAbbreviation("Hello.", 5)).isFalse()
	}

	@Test
	fun `isLikelyAbbreviation returns false for period at start`() {
		assertThat(TextUtils.isLikelyAbbreviation(".", 0)).isFalse()
	}

	@Test
	fun `isLikelyAbbreviation uses custom lookup when provided`() {
		assertThat(TextUtils.isLikelyAbbreviation("Xyz.", 3) { it == "xyz" }).isTrue()
		assertThat(TextUtils.isLikelyAbbreviation("Xyz.", 3) { false }).isFalse()
	}

	// ── isNonSentencePeriod ──────────────────────────────────────────────

	@Test
	fun `isNonSentencePeriod detects abbreviation periods`() {
		assertThat(TextUtils.isNonSentencePeriod("Mr. Smith", 2)).isTrue()
	}

	@Test
	fun `isNonSentencePeriod detects domain names via lookup`() {
		assertThat(
			TextUtils.isNonSentencePeriod(
				"visit google.com today",
				12,
				isKnownDomain = { it == "google.com" },
			),
		).isTrue()
	}

	@Test
	fun `isNonSentencePeriod returns false for sentence-ending periods`() {
		assertThat(TextUtils.isNonSentencePeriod("Hello. World", 5)).isFalse()
	}

	// ── isSentenceEnderAt ────────────────────────────────────────────────

	@Test
	fun `isSentenceEnderAt returns true for period followed by space`() {
		assertThat(TextUtils.isSentenceEnderAt("Hello. World", 5)).isTrue()
	}

	@Test
	fun `isSentenceEnderAt returns true for exclamation and question marks`() {
		assertThat(TextUtils.isSentenceEnderAt("Hello! World", 5)).isTrue()
		assertThat(TextUtils.isSentenceEnderAt("Hello? World", 5)).isTrue()
	}

	@Test
	fun `isSentenceEnderAt returns false for abbreviation period`() {
		assertThat(TextUtils.isSentenceEnderAt("Mr. Smith", 2)).isFalse()
	}

	@Test
	fun `isSentenceEnderAt returns false for period inside parentheses`() {
		assertThat(TextUtils.isSentenceEnderAt("(Hello.) World", 6)).isFalse()
	}

	@Test
	fun `isSentenceEnderAt returns false for period followed by letter`() {
		assertThat(TextUtils.isSentenceEnderAt("Hello.World", 5)).isFalse()
	}

	// ── isInsideParentheses ──────────────────────────────────────────────

	@Test
	fun `isInsideParentheses detects content between matched parens`() {
		assertThat(TextUtils.isInsideParentheses("(Hello.)", 6)).isTrue()
	}

	@Test
	fun `isInsideParentheses returns false outside parens`() {
		assertThat(TextUtils.isInsideParentheses("Hello. World", 5)).isFalse()
	}

	@Test
	fun `isInsideParentheses handles nested parens`() {
		assertThat(TextUtils.isInsideParentheses("((Hello.))", 7)).isTrue()
	}

	@Test
	fun `isInsideParentheses stops at line breaks`() {
		assertThat(TextUtils.isInsideParentheses("(\nHello.)", 7)).isFalse()
	}

	// ── findWordBoundaryLeft ─────────────────────────────────────────────

	@Test
	fun `findWordBoundaryLeft finds start of word`() {
		assertThat(TextUtils.findWordBoundaryLeft("hello world", 8)).isEqualTo(6)
	}

	@Test
	fun `findWordBoundaryLeft at start of text returns 0`() {
		assertThat(TextUtils.findWordBoundaryLeft("hello", 3)).isEqualTo(0)
	}

	@Test
	fun `findWordBoundaryLeft skips punctuation`() {
		assertThat(TextUtils.findWordBoundaryLeft("hello. world", 9)).isEqualTo(7)
	}

	// ── findWordBoundaryRight ────────────────────────────────────────────

	@Test
	fun `findWordBoundaryRight finds end of word`() {
		assertThat(TextUtils.findWordBoundaryRight("hello world", 6)).isEqualTo(11)
	}

	@Test
	fun `findWordBoundaryRight at end of text returns length`() {
		assertThat(TextUtils.findWordBoundaryRight("hello", 3)).isEqualTo(5)
	}

	// ── findSentenceStart ────────────────────────────────────────────────

	@Test
	fun `findSentenceStart finds beginning of current sentence`() {
		val text = "Hello. World is great."
		// Cursor at "World" (index 7)
		assertThat(TextUtils.findSentenceStart(text, 12)).isEqualTo(7)
	}

	@Test
	fun `findSentenceStart at beginning returns 0`() {
		assertThat(TextUtils.findSentenceStart("Hello world", 0)).isEqualTo(0)
	}

	// ── findSentenceEnd ──────────────────────────────────────────────────

	@Test
	fun `findSentenceEnd finds end of current sentence`() {
		val text = "Hello. World."
		// From beginning, should find end of first sentence
		assertThat(TextUtils.findSentenceEnd(text, 0)).isEqualTo(7)
	}

	@Test
	fun `findSentenceEnd at end of text returns length`() {
		assertThat(TextUtils.findSentenceEnd("Hello", 5)).isEqualTo(5)
	}

	// ── findParagraphStart ───────────────────────────────────────────────

	@Test
	fun `findParagraphStart finds start of paragraph`() {
		val text = "Hello\nWorld"
		assertThat(TextUtils.findParagraphStart(text, 8)).isEqualTo(6)
	}

	@Test
	fun `findParagraphStart skips blank lines`() {
		val text = "Hello\n\n\nWorld"
		assertThat(TextUtils.findParagraphStart(text, 10)).isEqualTo(8)
	}

	// ── findNextParagraphStart ───────────────────────────────────────────

	@Test
	fun `findNextParagraphStart finds next paragraph`() {
		val text = "Hello\nWorld"
		assertThat(TextUtils.findNextParagraphStart(text, 0)).isEqualTo(6)
	}

	@Test
	fun `findNextParagraphStart skips blank lines`() {
		val text = "Hello\n\n\nWorld"
		assertThat(TextUtils.findNextParagraphStart(text, 0)).isEqualTo(8)
	}

	@Test
	fun `findNextParagraphStart at end returns length`() {
		val text = "Hello"
		assertThat(TextUtils.findNextParagraphStart(text, 3)).isEqualTo(5)
	}

	// ── findParagraphStartSingle ─────────────────────────────────────────

	@Test
	fun `findParagraphStartSingle stops at single newline`() {
		val text = "A\nB\nC"
		// From pos 4 (at 'C'), goes back past '\n' to line containing 'B'
		assertThat(TextUtils.findParagraphStartSingle(text, 4)).isEqualTo(2)
		// From pos 2 (at 'B'), goes back past '\n' to line containing 'A'
		assertThat(TextUtils.findParagraphStartSingle(text, 2)).isEqualTo(0)
	}

	// ── findNextParagraphStartSingle ─────────────────────────────────────

	@Test
	fun `findNextParagraphStartSingle advances by one newline`() {
		val text = "A\nB\nC"
		assertThat(TextUtils.findNextParagraphStartSingle(text, 0)).isEqualTo(2)
	}

	// ── Helper ────────────────────────────────────────────────────────────

	private fun editorInfo(inputType: Int): EditorInfo = EditorInfo().apply {
		this.inputType = inputType
	}
}
