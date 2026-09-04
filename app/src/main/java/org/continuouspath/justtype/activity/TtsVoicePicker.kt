package org.continuouspath.justtype.activity

import android.app.Activity
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AlertDialog
import org.continuouspath.justtype.CanonicalLanguages
import org.continuouspath.justtype.LabeledVoice
import org.continuouspath.justtype.LanguageTtsPreferences
import org.continuouspath.justtype.R
import org.continuouspath.justtype.TtsVoiceCandidate
import org.continuouspath.justtype.TtsVoiceNames
import org.continuouspath.justtype.TtsVoiceOption
import org.continuouspath.justtype.TtsVoicePref
import org.continuouspath.justtype.TtsVoicePreview
import org.continuouspath.justtype.TtsVoiceScanner
import org.continuouspath.justtype.UiVoice
import org.continuouspath.justtype.autoSelectVoice
import org.continuouspath.justtype.localeForTypingLanguage
import org.continuouspath.justtype.settings.SettingsRepository
import java.util.Locale
import org.continuouspath.justtype.Constants as C

/**
 * Confirmation-style voice picker for a typing language. Scans installed TTS engines for voices that
 * support the language's locale, annotates each with a best-guess gender, pre-selects one honoring the
 * global voice-type preference ([C.KEY_TTS_VOICE_GENDER]), lets the user Test and Confirm, then persists
 * the choice via [LanguageTtsPreferences] — which the IME picks up and applies automatically.
 *
 * Enumeration/test use temporary [TextToSpeech] instances owned here and shut down on dismiss (the IME
 * controller can't show dialogs, so this lives in the Activity). Gender is best-effort: Android's Voice
 * API has no gender field, so it is inferred from the voice name; the user's Test/Confirm is the truth.
 */
class TtsVoicePicker private constructor(
	private val activity: Activity,
	private val repo: SettingsRepository,
	/** [LanguageTtsPreferences] key the confirmed choice is saved under (typing-language id or ui-{code}). */
	private val prefKey: String,
	private val locale: Locale,
	/** Human name for titles/messages (endonym for a typing language, UI-language display name). */
	private val displayName: String,
) {
	constructor(activity: Activity, repo: SettingsRepository, languageId: String) : this(
		activity = activity,
		repo = repo,
		prefKey = languageId,
		locale = localeForTypingLanguage(languageId),
		displayName = CanonicalLanguages.endonymFor(languageId),
	)

	companion object {
		/** Picker for the UI ("device") voice — prompts/key names spoken to the user (see UiVoice). */
		fun forUiLanguage(activity: Activity, repo: SettingsRepository): TtsVoicePicker = TtsVoicePicker(
			activity = activity,
			repo = repo,
			prefKey = UiVoice.prefKey(repo),
			locale = UiVoice.locale(repo),
			displayName = UiVoice.displayName(repo),
		)
	}
	private val genderPref: String = repo.getString(UiVoice.genderPrefKeyFor(prefKey), C.TTS_GENDER_ANY)
	private val preview = TtsVoicePreview(activity, locale)

	private var defaultEngine: String? = null
	private var scanner: TtsVoiceScanner? = null

	fun show() {
		val progress = AlertDialog.Builder(activity)
			.setTitle(displayTitle())
			.setMessage(activity.getString(R.string.tts_voice_picker_scanning))
			.setCancelable(true)
			.setOnCancelListener { shutdown() }
			.create()
		progress.show()
		scanner = TtsVoiceScanner(activity, locale) { options, engine ->
			defaultEngine = engine
			if (activity.isFinishing || activity.isDestroyed) {
				shutdown()
				return@TtsVoiceScanner
			}
			progress.dismiss()
			if (options.isEmpty()) showNoneFound() else showPicker(TtsVoiceNames.prepareForDisplay(activity, repo, options, prefKey))
		}.also { it.start() }
	}

	// ── Dialogs ──────────────────────────────────────────────────────────

	private fun showPicker(labeled: List<LabeledVoice>) {
		val options = labeled.map { it.option }
		val candidates = options.map { TtsVoiceCandidate(it.enginePackage, it.voiceName, it.gender) }
		val auto = autoSelectVoice(candidates, genderPref, defaultEngine)
		var selected = options
			.indexOfFirst { it.enginePackage == auto?.enginePackage && it.voiceName == auto?.voiceName }
			.coerceAtLeast(0)
		val labels = labeled.map { it.label }.toTypedArray()
		val genderMatched = labeled.any { it.matchesGenderPref }

		val builder = AlertDialog.Builder(activity)
			.setSingleChoiceItems(labels, selected) { _, which -> selected = which }
			.setPositiveButton(R.string.tts_voice_picker_confirm) { _, _ -> confirm(options[selected]) }
			.setNeutralButton(R.string.tts_voice_picker_test, null) // click handler set below so it doesn't dismiss
			.setNegativeButton(android.R.string.cancel) { _, _ -> shutdown() }
			.setOnDismissListener { shutdown() }
		if (genderMatched) {
			builder.setTitle(displayTitle())
		} else {
			// setMessage would REPLACE the single-choice list in AlertDialog's content area,
			// hiding every option; surface the no-match hint as a subtitle so the full voice
			// list always renders.
			builder.setCustomTitle(
				titleWithSubtitle(displayTitle(), activity.getString(R.string.tts_voice_picker_no_gender_match)),
			)
		}

		val dialog = builder.create()
		dialog.show()
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { testVoice(options[selected]) }
	}

	/** Two-line dialog title: the normal title plus a smaller secondary hint. */
	private fun titleWithSubtitle(title: String, subtitle: String): android.widget.LinearLayout {
		val density = activity.resources.displayMetrics.density
		fun dp(v: Int) = (v * density).toInt()
		return android.widget.LinearLayout(activity).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			setPadding(dp(24), dp(18), dp(24), dp(4))
			addView(
				android.widget.TextView(activity).apply {
					text = title
					setTextAppearance(android.R.style.TextAppearance_Material_DialogWindowTitle)
				},
			)
			addView(
				android.widget.TextView(activity).apply {
					text = subtitle
					setTextAppearance(android.R.style.TextAppearance_Material_Body1)
					setPadding(0, dp(6), 0, 0)
				},
			)
		}
	}

	private fun showNoneFound() {
		AlertDialog.Builder(activity)
			.setTitle(displayTitle())
			.setMessage(activity.getString(R.string.tts_voice_picker_none_found, displayName))
			.setPositiveButton(R.string.tts_voice_picker_install) { _, _ -> launchInstall() }
			.setNegativeButton(android.R.string.cancel, null)
			.setOnDismissListener { shutdown() }
			.show()
	}

	// ── Actions ──────────────────────────────────────────────────────────

	private fun confirm(o: TtsVoiceOption) {
		LanguageTtsPreferences.setVoice(repo, prefKey, TtsVoicePref(o.enginePackage, o.voiceName))
	}

	private fun testVoice(o: TtsVoiceOption) = preview.play(o)

	private fun launchInstall() {
		val installed = runCatching {
			activity.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
			true
		}.getOrDefault(false)
		if (!installed) runCatching { activity.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
	}

	fun shutdown() {
		scanner?.cancel()
		scanner = null
		preview.shutdown()
	}

	// ── Labels ───────────────────────────────────────────────────────────

	private fun displayTitle() = activity.getString(R.string.tts_voice_picker_title, displayName)
}
