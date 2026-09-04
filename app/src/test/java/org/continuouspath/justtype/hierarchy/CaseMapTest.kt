package org.continuouspath.justtype.hierarchy

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CaseMapTest {

	private lateinit var caseMap: CaseMap

	@Before fun setUp() {
		val app = ApplicationProvider.getApplicationContext<android.content.Context>()
		caseMap = loadCaseMap(app.assets)
	}

	@Test fun `english i uppercases to plain I`() {
		assertThat(caseMap.toUpper('i', Locale.ENGLISH)).isEqualTo('I')
	}

	@Test fun `turkish i uppercases to dotted capital`() {
		assertThat(caseMap.toUpper('i', Locale("tr"))).isEqualTo('İ')
	}

	@Test fun `turkish dotless i uppercases to plain I`() {
		assertThat(caseMap.toUpper('ı', Locale("tr"))).isEqualTo('I')
	}

	@Test fun `azerbaijani i uppercases to dotted capital`() {
		assertThat(caseMap.toUpper('i', Locale("az"))).isEqualTo('İ')
	}

	@Test fun `accented vowels use default mapping outside locale override`() {
		assertThat(caseMap.toUpper('á', Locale.ENGLISH)).isEqualTo('Á')
		assertThat(caseMap.toUpper('ñ', Locale("es"))).isEqualTo('Ñ')
	}

	@Test fun `unmapped letter falls through to JDK uppercase`() {
		assertThat(caseMap.toUpper('z', Locale.ENGLISH)).isEqualTo('Z')
	}

	@Test fun `string overload uppercases each character`() {
		assertThat(caseMap.toUpper("istanbul", Locale("tr"))).isEqualTo("İSTANBUL")
		assertThat(caseMap.toUpper("istanbul", Locale.ENGLISH)).isEqualTo("ISTANBUL")
	}
}
