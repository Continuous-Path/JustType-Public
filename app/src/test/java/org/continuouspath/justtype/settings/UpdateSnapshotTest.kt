package org.continuouspath.justtype.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateSnapshotTest {
	private lateinit var context: Context

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		UpdateSnapshot.delete(context)
	}

	@After
	fun tearDown() {
		UpdateSnapshot.delete(context)
	}

	@Test
	fun `write then read round-trips`() {
		val prefs = mutablePreferencesOf().apply {
			set(booleanPreferencesKey("flag"), true)
			set(floatPreferencesKey("ratio"), 0.55f)
		}
		UpdateSnapshot.write(context, prefs)
		val restored = UpdateSnapshot.read(context)!!.asMap().mapKeys { it.key.name }
		assertThat(restored).containsExactly("flag", true, "ratio", 0.55f)
	}

	@Test
	fun `read returns null when no snapshot exists`() {
		assertThat(UpdateSnapshot.read(context)).isNull()
	}

	@Test
	fun `delete removes the snapshot`() {
		UpdateSnapshot.write(context, mutablePreferencesOf())
		UpdateSnapshot.delete(context)
		assertThat(UpdateSnapshot.read(context)).isNull()
	}
}
