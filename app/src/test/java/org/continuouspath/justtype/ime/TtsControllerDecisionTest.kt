package org.continuouspath.justtype.ime

import android.speech.tts.TextToSpeech
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.ime.TtsController.Reconfigure
import org.continuouspath.justtype.ime.TtsController.TtsAvailability
import org.junit.Test

/** Pure decision-helper tests for [TtsController] (no live TextToSpeech required). */
class TtsControllerDecisionTest {

	@Test fun `no engine yet always recreates`() {
		assertThat(TtsController.decideReconfigure(current = "eng.a", target = "eng.a", hasEngine = false))
			.isEqualTo(Reconfigure.Recreate)
	}

	@Test fun `same engine reuses the live instance`() {
		assertThat(TtsController.decideReconfigure("eng.a", "eng.a", hasEngine = true))
			.isEqualTo(Reconfigure.ReuseSetLocale)
	}

	@Test fun `engine package change recreates`() {
		assertThat(TtsController.decideReconfigure("eng.a", "eng.b", hasEngine = true))
			.isEqualTo(Reconfigure.Recreate)
	}

	@Test fun `null and empty engine are both treated as default`() {
		assertThat(TtsController.decideReconfigure(null, "", hasEngine = true)).isEqualTo(Reconfigure.ReuseSetLocale)
		assertThat(TtsController.decideReconfigure("", null, hasEngine = true)).isEqualTo(Reconfigure.ReuseSetLocale)
		assertThat(TtsController.decideReconfigure(null, "eng.a", hasEngine = true)).isEqualTo(Reconfigure.Recreate)
	}

	@Test fun `availability codes map to buckets`() {
		assertThat(TtsController.classifyAvailability(TextToSpeech.LANG_MISSING_DATA)).isEqualTo(TtsAvailability.MISSING_DATA)
		assertThat(TtsController.classifyAvailability(TextToSpeech.LANG_NOT_SUPPORTED)).isEqualTo(TtsAvailability.NOT_SUPPORTED)
		assertThat(TtsController.classifyAvailability(TextToSpeech.LANG_AVAILABLE)).isEqualTo(TtsAvailability.AVAILABLE)
		assertThat(TtsController.classifyAvailability(TextToSpeech.LANG_COUNTRY_AVAILABLE)).isEqualTo(TtsAvailability.AVAILABLE)
		assertThat(TtsController.classifyAvailability(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE)).isEqualTo(TtsAvailability.AVAILABLE)
	}
}
