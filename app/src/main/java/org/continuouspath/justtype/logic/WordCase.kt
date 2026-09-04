package org.continuouspath.justtype.logic

enum class WordCaseForm {
	LOWER,
	TITLE,
	UPPER,
	ORIGINAL,
}

enum class CaseSource {
	DEFAULT,
	USER_PREFERENCE,
	MANUAL_SHIFT,
	AUTO_SENTENCE,
	AUTO_ABBREVIATION,
	CAPS_LOCK,
}

data class CasePreference(
	val preferredForm: WordCaseForm,
	val lowerCount: Int,
	val titleCount: Int,
	val upperCount: Int,
	val originalCount: Int,
)
