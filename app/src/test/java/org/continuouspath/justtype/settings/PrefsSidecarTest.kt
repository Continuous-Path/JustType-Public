package org.continuouspath.justtype.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsSidecarTest {
	private lateinit var context: Context

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		PrefsSidecar.deleteForTesting(context)
	}

	@After
	fun tearDown() {
		PrefsSidecar.deleteForTesting(context)
	}

	@Test
	fun `write then read round-trips typed values`() {
		val prefs = mutablePreferencesOf().apply {
			set(booleanPreferencesKey("flag"), true)
			set(intPreferencesKey("count"), 42)
			set(longPreferencesKey("ts"), 1_700_000_000_000L)
			set(floatPreferencesKey("ratio"), 0.75f)
			set(stringPreferencesKey("mode"), "alpha")
		}
		PrefsSidecar.write(context, prefs)

		val restored = PrefsSidecar.read(context)
		assertThat(restored).isNotNull()
		assertThat(restored!![booleanPreferencesKey("flag")]).isTrue()
		assertThat(restored[intPreferencesKey("count")]).isEqualTo(42)
		assertThat(restored[longPreferencesKey("ts")]).isEqualTo(1_700_000_000_000L)
		assertThat(restored[floatPreferencesKey("ratio")]).isEqualTo(0.75f)
		assertThat(restored[stringPreferencesKey("mode")]).isEqualTo("alpha")
	}

	@Test
	fun `read returns null when sidecar is missing`() {
		assertThat(PrefsSidecar.read(context)).isNull()
	}

	@Test
	fun `read returns null when sidecar is malformed JSON`() {
		PrefsSidecar.file(context).writeText("{not json")
		assertThat(PrefsSidecar.read(context)).isNull()
	}

	@Test
	fun `read returns null when sidecar is zero bytes`() {
		PrefsSidecar.file(context).writeBytes(byteArrayOf())
		assertThat(PrefsSidecar.read(context)).isNull()
	}

	@Test
	fun `write empty preferences produces empty JSON object`() {
		PrefsSidecar.write(context, emptyPreferences())
		val restored = PrefsSidecar.read(context)
		assertThat(restored).isNotNull()
		assertThat(restored!!.asMap()).isEmpty()
	}

	@Test
	fun `write overwrites previous sidecar atomically`() {
		val first = mutablePreferencesOf().apply { set(intPreferencesKey("k"), 1) }
		PrefsSidecar.write(context, first)

		val second = mutablePreferencesOf().apply { set(intPreferencesKey("k"), 999) }
		PrefsSidecar.write(context, second)

		val restored = PrefsSidecar.read(context)
		assertThat(restored!![intPreferencesKey("k")]).isEqualTo(999)
	}
}
