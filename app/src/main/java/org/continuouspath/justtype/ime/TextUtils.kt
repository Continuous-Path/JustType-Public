package org.continuouspath.justtype.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import java.util.Locale

/**
 * Pure text-analysis functions extracted from [org.continuouspath.justtype.JustTypeIME].
 *
 * Every function here is stateless and depends only on its parameters.
 * Functions that need vocabulary lookups (abbreviation/domain detection)
 * accept optional lambdas so callers can inject JTUI-backed checks.
 */
@Suppress("TooManyFunctions") // Intentional: this is a utility object grouping related pure functions
object TextUtils {

	// ── Constants ─────────────────────────────────────────────────────────

	val SENTENCE_ENDERS: Set<Char> = setOf('.', '!', '?')

	val ABBREVIATION_TOKENS: Set<String> = setOf(
		"mr", "mrs", "ms", "dr", "st", "sr", "jr", "prof", "rev", "lt", "sgt", "col",
		"gen", "adm", "sen", "rep", "gov", "pres", "ave", "st.", "dept", "inc", "ltd",
	)

	// ── Character classification ──────────────────────────────────────────

	fun isEosChar(c: Char): Boolean = c == '.' || c == '!' || c == '?'

	fun isLineBreak(char: Char): Boolean = char == '\n' || char == '\r' || char == '\u0085' || char == '\u2028' || char == '\u2029'

	// ── Editor info classification ────────────────────────────────────────

	fun isPasswordEditor(attribute: EditorInfo?): Boolean {
		if (attribute == null) return false
		val it = attribute.inputType
		val cls = it and InputType.TYPE_MASK_CLASS
		val variation = it and InputType.TYPE_MASK_VARIATION
		val isTextPassword = cls == InputType.TYPE_CLASS_TEXT &&
			(
				variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
					variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
					variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
				)
		val isNumberPassword = cls == InputType.TYPE_CLASS_NUMBER &&
			variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
		return isTextPassword || isNumberPassword
	}

	/**
	 * Returns true for fields where automatic space insertion between words
	 * should be suppressed (passwords, URIs, emails, phone/number/datetime, filter fields).
	 *
	 * Note: [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS] is intentionally NOT checked here.
	 * That flag means "don't show spelling suggestions" and is set by many normal text
	 * fields (Google Keep, Search, etc.) that still expect spaces.
	 */
	@Suppress("ReturnCount") // Early-return style is clearer for this kind of classification
	fun isNoAutospaceField(attribute: EditorInfo?): Boolean {
		if (attribute == null) return false
		val it = attribute.inputType
		val cls = it and InputType.TYPE_MASK_CLASS
		val variation = it and InputType.TYPE_MASK_VARIATION

		// Non-text input classes never need autospace
		if (cls == InputType.TYPE_CLASS_PHONE) return true
		if (cls == InputType.TYPE_CLASS_NUMBER) return true
		if (cls == InputType.TYPE_CLASS_DATETIME) return true

		if (cls == InputType.TYPE_CLASS_TEXT) {
			// Passwords — no spaces between characters
			if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD) return true
			if (variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) return true
			if (variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return true
			// Structured single-value fields — spaces would break the value
			if (variation == InputType.TYPE_TEXT_VARIATION_URI) return true
			if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) return true
			if (variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return true
			// Filter fields do character-by-character matching
			if (variation == InputType.TYPE_TEXT_VARIATION_FILTER) return true
		}
		return false
	}

	/**
	 * Whether the keyboard should be shown for the given editor.
	 * Returns false for [InputType.TYPE_NULL] (inputType == 0).
	 */
	fun shouldShowKeyboard(attribute: EditorInfo?): Boolean {
		if (attribute == null) return false
		val inputType = attribute.inputType
		if (inputType == 0) return false
		val cls = inputType and InputType.TYPE_MASK_CLASS
		return cls == InputType.TYPE_CLASS_TEXT ||
			cls == InputType.TYPE_CLASS_NUMBER ||
			cls == InputType.TYPE_CLASS_PHONE ||
			cls == InputType.TYPE_CLASS_DATETIME
	}

	// ── Case conversion ──────────────────────────────────────────────────

	fun toTitleCase(text: String): String {
		val sb = StringBuilder(text.length)
		var capitalizeNext = true
		for (ch in text) {
			if (ch.isWhitespace() || ch in setOf('-', '\'', '\u2019')) {
				sb.append(ch)
				capitalizeNext = true
			} else if (capitalizeNext && ch.isLetter()) {
				sb.append(ch.uppercaseChar())
				capitalizeNext = false
			} else {
				sb.append(ch.lowercaseChar())
				capitalizeNext = false
			}
		}
		return sb.toString()
	}

	fun toSentenceCase(
		text: String,
		isKnownAbbreviation: (String) -> Boolean = { false },
		isKnownDomain: (String) -> Boolean = { false },
	): String {
		val sb = StringBuilder(text.length)
		var capitalizeNext = true
		for ((idx, ch) in text.withIndex()) {
			if (capitalizeNext && ch.isLetter()) {
				sb.append(ch.uppercaseChar())
				capitalizeNext = false
			} else if (isSentenceEnderAt(text, idx, isKnownAbbreviation, isKnownDomain)) {
				sb.append(ch)
				capitalizeNext = true
			} else {
				sb.append(ch.lowercaseChar())
			}
		}
		return sb.toString()
	}

	// ── Abbreviation & domain detection ──────────────────────────────────

	/**
	 * Returns true if the token immediately before [periodIndex] looks like
	 * a common abbreviation (e.g. "Mr.", "Dr.", "Inc.").
	 *
	 * @param isKnownAbbreviation optional callback to check against vocabulary
	 */
	fun isLikelyAbbreviation(
		full: CharSequence,
		periodIndex: Int,
		isKnownAbbreviation: (String) -> Boolean = { false },
	): Boolean {
		if (periodIndex <= 0) return false
		var start = periodIndex - 1
		while (start >= 0 && full[start].isLetter()) start--
		if (start >= periodIndex - 1) return false
		val token = full.subSequence(start + 1, periodIndex).toString().lowercase(Locale.getDefault())
		if (token.isEmpty()) return false
		if (ABBREVIATION_TOKENS.contains(token)) return true
		if (isKnownAbbreviation(token)) return true
		if (token.length <= 3 && token.all { it.isUpperCase() }) return true
		return false
	}

	/**
	 * Returns true if the period at [periodIndex] is NOT a sentence-ending period
	 * (i.e., it belongs to an abbreviation or domain name).
	 *
	 * @param isKnownAbbreviation optional vocabulary lookup for abbreviations
	 * @param isKnownDomain optional vocabulary lookup for domain names
	 */
	fun isNonSentencePeriod(
		full: CharSequence,
		periodIndex: Int,
		isKnownAbbreviation: (String) -> Boolean = { false },
		isKnownDomain: (String) -> Boolean = { false },
	): Boolean {
		if (isLikelyAbbreviation(full, periodIndex, isKnownAbbreviation)) return true
		if (periodIndex + 1 < full.length && full[periodIndex + 1].isLetter()) {
			var end = periodIndex + 1
			while (end < full.length && (full[end].isLetterOrDigit() || full[end] == '.')) end++
			var start = periodIndex - 1
			while (start >= 0 && (full[start].isLetterOrDigit() || full[start] == '.')) start--
			start++
			val domainCandidate = full.subSequence(start, end).toString()
			if (isKnownDomain(domainCandidate)) return true
		}
		return false
	}

	// ── Sentence boundary detection ──────────────────────────────────────

	/**
	 * Returns true if the character at [index] is a sentence-ending punctuation mark,
	 * taking into account abbreviations, domains, and parenthetical context.
	 */
	fun isSentenceEnderAt(
		text: CharSequence,
		index: Int,
		isKnownAbbreviation: (String) -> Boolean = { false },
		isKnownDomain: (String) -> Boolean = { false },
	): Boolean {
		val c = text[index]
		if (c !in SENTENCE_ENDERS) return false
		if (c == '.' && isNonSentencePeriod(text, index, isKnownAbbreviation, isKnownDomain)) return false
		if (c == '.') {
			val next = index + 1
			if (next < text.length) {
				val nc = text[next]
				if (!nc.isWhitespace() && nc !in SENTENCE_ENDERS) return false
			}
		}
		if (isInsideParentheses(text, index)) return false
		return true
	}

	/**
	 * Returns true if [index] is inside a matched parenthetical group within the
	 * same paragraph. Scans backward for an unmatched '(' and, if found, forward
	 * for its matching ')'. Scanning stops at line breaks or text boundaries.
	 */
	fun isInsideParentheses(text: CharSequence, index: Int): Boolean {
		var depth = 0
		var i = index - 1
		while (i >= 0) {
			val ch = text[i]
			if (isLineBreak(ch)) break
			if (ch == ')') {
				depth++
			} else if (ch == '(') {
				if (depth > 0) {
					depth--
				} else {
					return hasClosingParenAfter(text, index + 1)
				}
			}
			i--
		}
		return false
	}

	private fun hasClosingParenAfter(text: CharSequence, start: Int): Boolean {
		var j = start
		while (j < text.length) {
			val jc = text[j]
			if (isLineBreak(jc)) return false
			if (jc == ')') return true
			j++
		}
		return false
	}

	// ��─ Word boundary detection ──────────────────────────────────────────

	fun findWordBoundaryLeft(text: String, pos: Int): Int {
		var i = pos - 1
		while (i > 0 && !text[i - 1].isLetterOrDigit()) i--
		while (i > 0 && text[i - 1].isLetterOrDigit()) i--
		return i.coerceAtLeast(0)
	}

	fun findWordBoundaryRight(text: String, pos: Int): Int {
		var i = pos
		while (i < text.length && !text[i].isLetterOrDigit()) i++
		while (i < text.length && text[i].isLetterOrDigit()) i++
		return i
	}

	// ── Sentence navigation ──────────────────────────────────────────────

	fun findSentenceStart(
		text: String,
		pos: Int,
		isKnownAbbreviation: (String) -> Boolean = { false },
		isKnownDomain: (String) -> Boolean = { false },
	): Int {
		if (pos <= 0) return 0
		var i = pos

		while (i > 0 && isLineBreak(text[i - 1])) i--
		while (i > 0 && text[i - 1].isWhitespace() && !isLineBreak(text[i - 1])) i--
		while (i > 0 && isSentenceEnderAt(text, i - 1, isKnownAbbreviation, isKnownDomain)) i--

		if (i <= 0) return 0

		while (i > 0 && !isSentenceEnderAt(text, i - 1, isKnownAbbreviation, isKnownDomain) && !isLineBreak(text[i - 1])) i--

		if (i <= 0) return 0

		if (isLineBreak(text[i - 1])) return i

		while (i < text.length && isSentenceEnderAt(text, i, isKnownAbbreviation, isKnownDomain)) i++
		while (i < text.length && text[i].isWhitespace() && !isLineBreak(text[i])) i++

		return i
	}

	fun findSentenceEnd(
		text: String,
		pos: Int,
		isKnownAbbreviation: (String) -> Boolean = { false },
		isKnownDomain: (String) -> Boolean = { false },
	): Int {
		val len = text.length
		if (pos >= len) return len
		var i = pos

		while (i < len && isLineBreak(text[i])) i++
		if (i >= len) return len

		var lb = i
		while (lb > 0 && text[lb - 1].let { it.isWhitespace() && !isLineBreak(it) }) lb--
		if (lb > 0 && isSentenceEnderAt(text, lb - 1, isKnownAbbreviation, isKnownDomain)) {
			while (i < len && text[i].isWhitespace() && !isLineBreak(text[i])) i++
			if (i > pos) return i
		}

		while (i < len && !isSentenceEnderAt(text, i, isKnownAbbreviation, isKnownDomain) && !isLineBreak(text[i])) i++

		if (i >= len) return len

		if (isLineBreak(text[i])) return i

		while (i < len && isSentenceEnderAt(text, i, isKnownAbbreviation, isKnownDomain)) i++
		while (i < len && text[i].isWhitespace() && !isLineBreak(text[i])) i++

		return i
	}

	// ── Paragraph navigation ─────────────────────────────────────────────

	fun findParagraphStart(text: String, pos: Int): Int {
		if (pos <= 0) return 0
		var i = pos
		if (i > 0 && text[i - 1] == '\n') i--
		while (i > 0 && text[i - 1] == '\n') i--
		while (i > 0 && text[i - 1] != '\n') i--
		return i.coerceAtLeast(0)
	}

	fun findNextParagraphStart(text: String, pos: Int): Int {
		var i = pos
		while (i < text.length && text[i] != '\n') i++
		while (i < text.length && text[i] == '\n') i++
		return i
	}

	fun findParagraphStartSingle(text: String, pos: Int): Int {
		if (pos <= 0) return 0
		var i = pos
		if (i > 0 && text[i - 1] == '\n') i--
		while (i > 0 && text[i - 1] != '\n') i--
		return i.coerceAtLeast(0)
	}

	fun findNextParagraphStartSingle(text: String, pos: Int): Int {
		var i = pos
		while (i < text.length && text[i] != '\n') i++
		if (i < text.length) i++
		return i
	}
}
