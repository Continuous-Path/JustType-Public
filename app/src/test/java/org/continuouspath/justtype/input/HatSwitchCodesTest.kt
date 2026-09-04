package org.continuouspath.justtype.input

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants.SWITCH_CODE_UNDEFINED
import org.junit.Test

class HatSwitchCodesTest {

	@Test
	fun `cardinals map to dpad keycodes`() {
		assertThat(HatSwitchCodes.hatToDpadKeyCode(0f, -1f)).isEqualTo(KeyEvent.KEYCODE_DPAD_UP)
		assertThat(HatSwitchCodes.hatToDpadKeyCode(0f, 1f)).isEqualTo(KeyEvent.KEYCODE_DPAD_DOWN)
		assertThat(HatSwitchCodes.hatToDpadKeyCode(-1f, 0f)).isEqualTo(KeyEvent.KEYCODE_DPAD_LEFT)
		assertThat(HatSwitchCodes.hatToDpadKeyCode(1f, 0f)).isEqualTo(KeyEvent.KEYCODE_DPAD_RIGHT)
	}

	@Test
	fun `center resolves to undefined`() {
		assertThat(HatSwitchCodes.hatToDpadKeyCode(0f, 0f)).isEqualTo(SWITCH_CODE_UNDEFINED)
	}

	@Test
	fun `diagonals resolve to undefined`() {
		assertThat(HatSwitchCodes.hatToDpadKeyCode(1f, 1f)).isEqualTo(SWITCH_CODE_UNDEFINED)
		assertThat(HatSwitchCodes.hatToDpadKeyCode(-1f, -1f)).isEqualTo(SWITCH_CODE_UNDEFINED)
		assertThat(HatSwitchCodes.hatToDpadKeyCode(1f, -1f)).isEqualTo(SWITCH_CODE_UNDEFINED)
		assertThat(HatSwitchCodes.hatToDpadKeyCode(-1f, 1f)).isEqualTo(SWITCH_CODE_UNDEFINED)
	}
}
