package org.continuouspath.justtype

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VocabImportHelperTest {
	private val appContext: Context = ApplicationProvider.getApplicationContext()

	@Test fun `importVocabulary returns empty and does not crash when the stream is null`() {
		// Regression: a stale/revoked SAF Uri makes openInputStream return null; importVocabulary
		// must return an empty list instead of NPE-ing on the null stream (and still close the db).
		val uri = Uri.parse("content://com.example.stale/doc/1")
		val resolver = mock<ContentResolver> { on { openInputStream(uri) } doReturn null }
		val context = spy(appContext) { on { contentResolver } doReturn resolver }

		val result = VocabImportHelper.importVocabulary(context, uri, bit = 5)

		assertThat(result).isEmpty()
	}

	@Test fun `tokenizeStream extracts words, folds smart punctuation, and skips non-letter tokens`() {
		// An em-dash between two words folds to a hyphen and joins them into one hyphenated token.
		val text = "Hello, world! It’s a co‑op test—of 123 tokens."
		val tokens = VocabImportHelper.tokenizeStream(text.reader().buffered())
		assertThat(tokens.map { it.original }).containsExactly("Hello", "world", "It's", "a", "co-op", "test-of", "tokens").inOrder()
		// Smart apostrophe and non-ASCII hyphen are folded to ASCII in the normalized form.
		val byOriginal = tokens.associateBy { it.original }
		assertThat(byOriginal["It's"]!!.normalized).isEqualTo("It's")
		assertThat(byOriginal["co-op"]!!.normalized).isEqualTo("co-op")
	}

	@Test fun `tokenizeStream marks the token after sentence-ending punctuation as a sentence start`() {
		val tokens = VocabImportHelper.tokenizeStream("alpha. beta gamma".reader().buffered())
		val byWord = tokens.associateBy { it.normalized }
		assertThat(byWord["beta"]!!.sentenceStart).isTrue() // follows the period
		assertThat(byWord["gamma"]!!.sentenceStart).isFalse()
	}
}
