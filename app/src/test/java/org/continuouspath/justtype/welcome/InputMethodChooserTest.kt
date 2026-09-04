package org.continuouspath.justtype.welcome

import com.google.common.truth.Truth.assertThat
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.R
import org.junit.Test

class InputMethodChooserTest {

	@Test
	fun `offers one option per supported input method, in order`() {
		assertThat(InputMethodChooser.options.map { it.methodKey }).containsExactly(
			Constants.INPUT_METHOD_DIRECT_SELECTION,
			Constants.INPUT_METHOD_DIRECTIONAL_SELECTION,
			Constants.INPUT_METHOD_SINGLE_SWITCH,
			Constants.INPUT_METHOD_TWO_SWITCH,
			Constants.INPUT_METHOD_JOYSTICK,
			Constants.INPUT_METHOD_HEAD_TRACKING,
		).inOrder()
	}

	@Test
	fun `single-switch situation maps to its method label and info prompt`() {
		val option = InputMethodChooser.options.first { it.methodKey == Constants.INPUT_METHOD_SINGLE_SWITCH }
		assertThat(option.methodLabel).isEqualTo(R.string.input_methods_single_switch)
		assertThat(option.infoPrompt).isEqualTo(R.string.info_prompt_im_single_switch)
	}
}
