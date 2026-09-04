package org.continuouspath.justtype.welcome

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.R
import org.continuouspath.justtype.activity.InputMethodsActivity

/**
 * "Which input method fits you?" helper shown from the Welcome Guide.
 *
 * Stage 1 asks the caregiver about the user's ability; stage 2 names the
 * recommended method, shows that method's existing info prompt, and opens
 * Input Methods. It does NOT change any preference — the caregiver makes the
 * actual choice on the Input Methods screen.
 */
object InputMethodChooser {

	/** A plain-language situation mapped to its recommended input method. */
	data class Option(
		val situationLabel: Int,
		val methodLabel: Int,
		val infoPrompt: Int,
		val methodKey: String,
	)

	val options: List<Option> = listOf(
		Option(
			R.string.welcome_chooser_opt_tap,
			R.string.input_methods_direct_selection,
			R.string.info_prompt_direct_selection,
			Constants.INPUT_METHOD_DIRECT_SELECTION,
		),
		Option(
			R.string.welcome_chooser_opt_swipe,
			R.string.input_methods_directional_selection,
			R.string.info_prompt_directional_selection,
			Constants.INPUT_METHOD_DIRECTIONAL_SELECTION,
		),
		Option(
			R.string.welcome_chooser_opt_one_switch,
			R.string.input_methods_single_switch,
			R.string.info_prompt_im_single_switch,
			Constants.INPUT_METHOD_SINGLE_SWITCH,
		),
		Option(
			R.string.welcome_chooser_opt_two_switch,
			R.string.input_methods_two_switch,
			R.string.info_prompt_im_two_switch,
			Constants.INPUT_METHOD_TWO_SWITCH,
		),
		Option(
			R.string.welcome_chooser_opt_joystick,
			R.string.input_methods_joystick,
			R.string.info_prompt_im_joystick,
			Constants.INPUT_METHOD_JOYSTICK,
		),
		Option(
			R.string.welcome_chooser_opt_head,
			R.string.input_methods_head_tracking,
			R.string.info_prompt_im_head_tracking,
			Constants.INPUT_METHOD_HEAD_TRACKING,
		),
	)

	fun show(context: Context) {
		val labels = options.map { context.getString(it.situationLabel) }.toTypedArray()
		AlertDialog.Builder(context)
			.setTitle(R.string.welcome_chooser_title)
			.setItems(labels) { _, which -> showRecommendation(context, options[which]) }
			.setNegativeButton(R.string.welcome_chooser_back, null)
			.show()
	}

	private fun showRecommendation(context: Context, option: Option) {
		AlertDialog.Builder(context)
			.setTitle(context.getString(option.methodLabel))
			.setMessage(option.infoPrompt)
			.setPositiveButton(R.string.welcome_chooser_open_input_methods) { _, _ ->
				context.startActivity(Intent(context, InputMethodsActivity::class.java))
			}
			.setNeutralButton(R.string.welcome_chooser_back) { _, _ -> show(context) }
			.show()
	}
}
