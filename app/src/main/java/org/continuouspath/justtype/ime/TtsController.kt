package org.continuouspath.justtype.ime

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.continuouspath.justtype.Constants
import org.continuouspath.justtype.LanguageRegistry
import org.continuouspath.justtype.LanguageTtsPreferences
import org.continuouspath.justtype.LiveVoiceInfo
import org.continuouspath.justtype.SpeechIndicatorTracker
import org.continuouspath.justtype.UiVoice
import org.continuouspath.justtype.inferVoiceGender
import org.continuouspath.justtype.isDeviceOnline
import org.continuouspath.justtype.localeForTypingLanguage
import org.continuouspath.justtype.preferredVoiceVariant
import org.continuouspath.justtype.selectGenderedDefault
import org.continuouspath.justtype.settings.SettingsRepository
import java.util.Locale

/**
 * Owns the [TextToSpeech] lifecycle, speech scheduling, and speak-delay logic.
 *
 * All TTS-related state that was previously scattered across [org.continuouspath.justtype.JustTypeIME]
 * is consolidated here. The controller is lifecycle-aware: call [init] from `onCreate()`
 * and [shutdown] from `onDestroy()`.
 *
 * Speech-scheduling (delayed speak after selection change) uses coroutines instead of
 * `Handler.postDelayed`, so callers must provide a service-scoped [CoroutineScope].
 *
 * The spoken voice follows the typing language: [applyForLanguage] resolves the locale from
 * [localeForTypingLanguage] and binds the user's per-language engine + voice (see
 * [LanguageTtsPreferences]). The engine is only recreated when its package actually changes
 * (see [decideReconfigure]); a same-engine switch just re-applies locale + voice.
 *
 * @param context Application or service context for [TextToSpeech] construction
 * @param scope Service-scoped coroutine scope (cancelled in `onDestroy()`)
 * @param getSpeakState Returns true when TTS output is globally enabled (delegates to JTUI)
 * @param getInputConnection Returns the current `InputConnection`, used by [speakLastSelection]
 * @param getRepo Supplies the [SettingsRepository] (per-language voice prefs + active language)
 * @param onSpeakingChanged Fires on background threads when speech starts/stops across both engines
 */
class TtsController(
	private val context: Context,
	private val scope: CoroutineScope,
	private val getSpeakState: () -> Boolean,
	private val getInputConnection: () -> android.view.inputmethod.InputConnection?,
	private val getRepo: () -> SettingsRepository = { SettingsRepository.get() },
	onSpeakingChanged: (Boolean) -> Unit = {},
) {

	// ── Data types ───────────────────────────────────────────────────────

	data class PendingSelection(var text: String, val type: String, var spoken: Boolean = false)

	// ── TTS engine ──────────────────────────────────────────────────────

	private var tts: TextToSpeech? = null

	// Engine/locale/voice currently bound (or pending until onInit after a recreate). The engine
	// can't be swapped on a live TextToSpeech, so a package change forces a shutdown + recreate;
	// the target locale/voice are stashed here and applied from onTtsInit once the new engine is up.
	@Volatile
	private var currentEnginePackage: String? = null

	@Volatile
	private var pendingLocale: Locale? = null

	@Volatile
	private var pendingVoiceName: String? = null

	/** True when the last locale bind reported LANG_NOT_SUPPORTED (speech no-ops rather than crashes). */
	@Volatile
	var isLanguageDegraded: Boolean = false
		private set

	@Volatile
	private var isNonInterruptibleActive: Boolean = false

	// ── UI ("device") voice engine ──────────────────────────────────────
	// Speaks prompts / key names / UI announcements in the UI language's chosen voice, distinct
	// from the typing-language voice that speaks the user's own text (see UiVoice).

	private var uiTts: TextToSpeech? = null

	@Volatile
	private var uiEnginePackage: String? = null

	@Volatile
	private var uiPendingLocale: Locale? = null

	@Volatile
	private var uiPendingVoiceName: String? = null

	@Volatile
	private var uiBoundLocaleCode: String? = null

	@Volatile
	private var pendingAmbiguous: String? = null

	// ── Speaking indicator ──────────────────────────────────────────────

	internal val speechTracker = SpeechIndicatorTracker(onSpeakingChanged)

	// uptimeMillis alone can collide for back-to-back utterances; the serial keeps ids unique.
	private val utteranceSerial = java.util.concurrent.atomic.AtomicLong()

	private fun newUtteranceId(prefix: String): String = prefix + SystemClock.uptimeMillis() + "." + utteranceSerial.incrementAndGet()

	/** Shared by both engines: speaking-indicator bookkeeping + non-interruptible gating. */
	private val progressListener = object : UtteranceProgressListener() {
		override fun onStart(utteranceId: String?) {
			this@TtsController.speechTracker.onUtteranceStarted()
			if (utteranceId != null && utteranceId.startsWith(PREFIX_NONINT)) {
				isNonInterruptibleActive = true
			}
		}

		override fun onDone(utteranceId: String?) {
			this@TtsController.speechTracker.onUtteranceFinished(utteranceId)
			if (utteranceId != null && utteranceId.startsWith(PREFIX_NONINT)) {
				isNonInterruptibleActive = false
				val pending = pendingAmbiguous
				if (pending != null) {
					pendingAmbiguous = null
					this@TtsController.speakInterruptible(pending)
				}
			}
		}

		@Deprecated("Deprecated in Java")
		override fun onError(utteranceId: String?) {
			this@TtsController.speechTracker.onUtteranceFinished(utteranceId)
			if (utteranceId != null && utteranceId.startsWith(PREFIX_NONINT)) {
				isNonInterruptibleActive = false
				pendingAmbiguous = null
			}
		}

		// QUEUE_FLUSH interruptions and stop() land here; the indicator must hide for them too.
		override fun onStop(utteranceId: String?, interrupted: Boolean) {
			this@TtsController.speechTracker.onUtteranceFinished(utteranceId)
			if (utteranceId != null && utteranceId.startsWith(PREFIX_NONINT)) {
				isNonInterruptibleActive = false
				pendingAmbiguous = null
			}
		}
	}

	// ── Speech scheduling state ─────────────────────────────────────────

	var speakDelayMs: Long = 0L
	var speakOutputWordEnabled: Boolean = false
	var speakOutputPhraseEnabled: Boolean = false
	var speakOutputSentenceEnabled: Boolean = false
	var speakPunctNamesEnabled: Boolean = false

	var pendingSelection: PendingSelection? = null
		private set

	private var scheduledSpeakJob: Job? = null
	private var lastScheduledSelectionText: String? = null
	private var lastScheduledSelectionType: String? = null
	var lastSpokenSelectionText: String? = null
		private set
	var lastSpokenSelectionType: String? = null
		private set

	// Spell/numeric accumulation for character-by-character modes
	private val pendingSpellNumericBuffer: StringBuilder = StringBuilder()
	private var pendingSpellNumericSpoken: Boolean = false

	// ── Lifecycle ────────────────────────────────────────────────────────

	fun init() {
		applyForActiveLanguage()
		applyUiVoice()
		registerConnectivityListener()
	}

	fun shutdown() {
		unregisterConnectivityListener()
		scheduledSpeakJob?.cancel()
		scheduledSpeakJob = null
		tts?.shutdown()
		tts = null
		uiTts?.shutdown()
		uiTts = null
		speechTracker.reset()
	}

	/**
	 * Clear per-field remembered speech at the end of an input session (onFinishInput). Without this,
	 * speak-last-selection in a new field falls back to text captured in a PREVIOUS field — a stale,
	 * potentially sensitive read-aloud (e.g. a prior password field's content).
	 */
	fun clearSessionState() {
		lastSpokenSelectionText = null
		lastSpokenSelectionType = null
		pendingSelection = null
		cancelScheduledSpeak(clearPending = true)
	}

	// ── Connectivity (network/local voice-variant switching) ────────────

	private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

	/** Rebind on connectivity changes so the spoken variant tracks online state (network ⇄ local). */
	private fun registerConnectivityListener() {
		val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
		val cb = object : android.net.ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: android.net.Network) = rebindForConnectivity()
			override fun onLost(network: android.net.Network) = rebindForConnectivity()
		}
		runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
	}

	private fun unregisterConnectivityListener() {
		val cb = networkCallback ?: return
		networkCallback = null
		val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
		runCatching { cm.unregisterNetworkCallback(cb) }
	}

	private fun rebindForConnectivity() {
		applyForActiveLanguage()
		applyUiVoice()
	}

	// ── Per-language engine/voice binding ───────────────────────────────

	/**
	 * Bind TTS to [languageId]'s locale + preferred engine/voice. Recreates the [TextToSpeech]
	 * instance only when the engine package changes (or none exists yet); otherwise re-applies
	 * locale + voice on the live instance. Safe to call from any thread.
	 */
	fun applyForLanguage(languageId: String) {
		val targetLocale = localeForTypingLanguage(languageId)
		val pref = runCatching { LanguageTtsPreferences.voiceFor(getRepo(), languageId) }.getOrNull()
		val targetEngine = pref?.enginePackage?.ifEmpty { null }
		val targetVoice = pref?.voiceName?.ifEmpty { null }

		when (decideReconfigure(currentEnginePackage, targetEngine, tts != null)) {
			Reconfigure.Recreate -> {
				pendingLocale = targetLocale
				pendingVoiceName = targetVoice
				currentEnginePackage = targetEngine
				tts?.shutdown()
				speechTracker.dropPrefixes(TYPING_ENGINE_PREFIXES)
				tts = if (targetEngine == null) {
					TextToSpeech(context) { onTtsInit() }
				} else {
					TextToSpeech(context, { onTtsInit() }, targetEngine)
				}
			}
			Reconfigure.ReuseSetLocale -> {
				pendingLocale = targetLocale
				pendingVoiceName = targetVoice
				applyLocaleAndVoice(targetLocale, targetVoice)
			}
		}
	}

	/** Bind TTS to the currently-active typing language (reads the registry). */
	fun applyForActiveLanguage() = applyForLanguage(currentTypingLanguageId())

	/**
	 * Bind the UI ("device") engine to the current UI language + its chosen voice. Mirrors
	 * [applyForLanguage]'s recreate-vs-reuse logic on the separate [uiTts] instance.
	 */
	fun applyUiVoice() {
		val repo = runCatching { getRepo() }.getOrNull() ?: return
		val targetLocale = UiVoice.locale(repo)
		uiBoundLocaleCode = targetLocale.language
		val pref = runCatching { LanguageTtsPreferences.voiceFor(repo, UiVoice.prefKey(repo)) }.getOrNull()
		val targetEngine = pref?.enginePackage?.ifEmpty { null }
		val targetVoice = pref?.voiceName?.ifEmpty { null }

		when (decideReconfigure(uiEnginePackage, targetEngine, uiTts != null)) {
			Reconfigure.Recreate -> {
				uiPendingLocale = targetLocale
				uiPendingVoiceName = targetVoice
				uiEnginePackage = targetEngine
				uiTts?.shutdown()
				speechTracker.dropPrefixes(listOf(PREFIX_UI))
				uiTts = if (targetEngine == null) {
					TextToSpeech(context) { onUiTtsInit() }
				} else {
					TextToSpeech(context, { onUiTtsInit() }, targetEngine)
				}
			}
			Reconfigure.ReuseSetLocale -> {
				uiPendingLocale = targetLocale
				uiPendingVoiceName = targetVoice
				applyLocaleAndVoiceTo(uiTts, targetLocale, targetVoice)
			}
		}
	}

	private fun onUiTtsInit() {
		applyLocaleAndVoiceTo(uiTts, uiPendingLocale, uiPendingVoiceName)
		uiTts?.setOnUtteranceProgressListener(progressListener)
	}

	/** Active typing language id, falling back to English if the registry read fails. */
	private fun currentTypingLanguageId(): String = runCatching {
		LanguageRegistry.activeLanguageNames(getRepo()).first()
	}.getOrElse { Constants.TYPING_LANGUAGE_ENGLISH }

	/**
	 * Apply [locale] then [voiceName] to the live engine. Degrades gracefully: an unsupported
	 * locale is logged and flagged (speech no-ops), a missing named voice falls back to the
	 * language default. Never throws.
	 */
	private fun applyLocaleAndVoice(locale: Locale?, voiceName: String?) {
		val engine = tts ?: return
		applyLocaleAndVoiceImpl(
			engine,
			locale,
			voiceName,
			trackDegraded = true,
			enginePackage = currentEnginePackage,
			genderPrefKey = Constants.KEY_TTS_VOICE_GENDER,
		)
	}

	/** [applyLocaleAndVoice] for the UI ("feedback") engine; does not touch [isLanguageDegraded]. */
	private fun applyLocaleAndVoiceTo(engine: TextToSpeech?, locale: Locale?, voiceName: String?) {
		applyLocaleAndVoiceImpl(
			engine ?: return,
			locale,
			voiceName,
			trackDegraded = false,
			enginePackage = uiEnginePackage,
			genderPrefKey = Constants.KEY_TTS_UI_VOICE_GENDER,
		)
	}

	private fun applyLocaleAndVoiceImpl(
		engine: TextToSpeech,
		locale: Locale?,
		voiceName: String?,
		trackDegraded: Boolean,
		enginePackage: String?,
		genderPrefKey: String,
	) {
		val target = locale ?: Locale.getDefault()
		when (classifyAvailability(engine.setLanguage(target))) {
			TtsAvailability.AVAILABLE -> if (trackDegraded) isLanguageDegraded = false
			TtsAvailability.MISSING_DATA -> {
				if (trackDegraded) isLanguageDegraded = false
				Log.w(TAG, "TTS voice data missing for $target; a voice download may be needed.")
			}
			TtsAvailability.NOT_SUPPORTED -> {
				if (trackDegraded) isLanguageDegraded = true
				Log.w(TAG, "TTS language not supported: $target; speech degraded until a voice is installed.")
			}
		}
		if (!voiceName.isNullOrEmpty()) {
			val voices = runCatching { engine.voices }.getOrNull()
			// A stored voice represents a persona; speak with its network twin while online, its
			// offline-capable local twin otherwise (see preferredVoiceVariant).
			val wantedName = preferredVoiceVariant(
				voiceName,
				voices?.mapTo(mutableSetOf()) { it.name } ?: emptySet(),
				isDeviceOnline(context),
			)
			val match = voices?.firstOrNull { it.name == wantedName }
			if (match != null) {
				engine.voice = match
			} else {
				Log.w(TAG, "TTS voice '$voiceName' not found for $target; keeping language default.")
			}
		} else {
			autoBindGenderedDefault(engine, target, enginePackage, genderPrefKey)
		}
	}

	/**
	 * With no explicit voice selection, honor the voice-type preference by binding a gender-matched
	 * voice from the live engine. Runtime-only: nothing is persisted, so settings rows still read
	 * "DEFAULT … VOICE". No-op when the preference is "any" or no voice's gender is recognizable.
	 */
	private fun autoBindGenderedDefault(engine: TextToSpeech, target: Locale, enginePackage: String?, genderPrefKey: String) {
		val genderPref = runCatching { getRepo().getString(genderPrefKey, Constants.TTS_GENDER_ANY) }
			.getOrDefault(Constants.TTS_GENDER_ANY)
		if (genderPref == Constants.TTS_GENDER_ANY) return
		val pkg = enginePackage ?: runCatching { engine.defaultEngine }.getOrNull().orEmpty()
		val voices = runCatching { engine.voices }.getOrNull() ?: return
		val infos = voices.mapNotNull { v ->
			val loc = v.locale ?: return@mapNotNull null
			LiveVoiceInfo(v.name, loc.language, loc.country, inferVoiceGender(pkg, v.name))
		}
		val chosen = selectGenderedDefault(infos, target, genderPref) ?: return
		voices.firstOrNull { it.name == chosen.name }?.let { engine.voice = it }
	}

	private fun onTtsInit() {
		applyLocaleAndVoice(pendingLocale, pendingVoiceName)
		tts?.setOnUtteranceProgressListener(progressListener)
	}

	// ── Core speech methods ─────────────────────────────────────────────

	fun speakQueued(text: String) {
		val id = newUtteranceId(PREFIX_NONINT)
		isNonInterruptibleActive = true
		if (tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) {
			speechTracker.onEnqueued(id)
		}
	}

	fun speakInterruptible(text: String) {
		if (isNonInterruptibleActive) {
			pendingAmbiguous = text
			return
		}
		val id = newUtteranceId(PREFIX_AMB)
		if (getSpeakState() && speakOutputSentenceEnabled) {
			if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) {
				speechTracker.onEnqueued(id, flushedPrefixes = TYPING_ENGINE_PREFIXES)
			}
			rememberLastSpoken(text, "sentence")
		}
	}

	/**
	 * UI ("device") speech: prompts, key names, and other announcements addressed TO the user,
	 * spoken in the UI language's chosen voice. Gated only by the global speak state — feature
	 * gates (speak-prompts / speak-selected-key) live at the call sites.
	 */
	fun speakUiInterruptible(text: String) {
		if (!getSpeakState()) return
		ensureUiVoiceCurrent()
		val engine = uiTts ?: tts ?: return
		val id = newUtteranceId(PREFIX_UI)
		// A flush only empties the queue of the engine it runs on (uiTts, or tts when falling back).
		val flushed = if (engine === uiTts) listOf(PREFIX_UI) else listOf(PREFIX_UI) + TYPING_ENGINE_PREFIXES
		if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) {
			speechTracker.onEnqueued(id, flushedPrefixes = flushed)
		}
	}

	/** Queued variant of [speakUiInterruptible] (key names arriving in sequence). */
	fun speakUiQueued(text: String) {
		if (!getSpeakState()) return
		ensureUiVoiceCurrent()
		val engine = uiTts ?: tts ?: return
		val id = newUtteranceId(PREFIX_UI)
		if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) {
			speechTracker.onEnqueued(id)
		}
	}

	/**
	 * Lazily rebind when the UI language changed without [applyUiVoice] having run (e.g. the
	 * preference flipped while this process's listeners weren't active). setLanguage on a live
	 * engine is synchronous, so even with no explicitly chosen voice the first prompt after a
	 * UI-language switch speaks in the new language's default voice — not an Anglified carryover.
	 */
	private fun ensureUiVoiceCurrent() {
		val repo = runCatching { getRepo() }.getOrNull() ?: return
		if (UiVoice.locale(repo).language != uiBoundLocaleCode) applyUiVoice()
	}

	// ── Conditional speech ──────────────────────────────────────────────

	/**
	 * Speaks the pending selection if speech is enabled and the selection type
	 * is allowed by current settings. Returns true if speech was triggered.
	 *
	 * Side-effect: if the spoken text ends with terminal punctuation + whitespace,
	 * the caller's [onAutoCapAfterSpeak] callback is invoked.
	 */
	fun speakIfEnabled(
		pending: PendingSelection?,
		onAutoCapAfterSpeak: (() -> Unit)? = null,
	): Boolean {
		if (pending == null) return false
		val t = pending.text.takeIf { it.isNotBlank() } ?: return false
		if (!getSpeakState()) return false
		// If punctuation-only and toggle is off, skip
		if (!speakPunctNamesEnabled && t.all { it.isWhitespace() || (!it.isLetterOrDigit()) }) return false
		if (pending.spoken) return false
		val allow = when (pending.type) {
			"PH" -> speakOutputPhraseEnabled
			"X", "L", "E", "2" -> speakOutputWordEnabled
			"sentence" -> speakOutputSentenceEnabled
			else -> speakOutputWordEnabled
		}
		if (!allow) return false
		speakQueued(t)
		pending.spoken = true
		rememberLastSpoken(pending.text, pending.type)
		// If the spoken text ends in terminal punctuation + whitespace, arm shift for next word
		if (t.matches(Regex(".*[.!?]\\s+$"))) {
			onAutoCapAfterSpeak?.invoke()
		}
		return true
	}

	fun speakLastSelection() {
		val ic = getInputConnection()
		val highlighted = try {
			ic?.getSelectedText(0)?.toString()
		} catch (_: Exception) {
			null
		}
		val candidate = highlighted?.takeIf { it.isNotBlank() } ?: lastSpokenSelectionText
		if (!candidate.isNullOrBlank()) {
			speakQueued(candidate)
			rememberLastSpoken(
				candidate,
				highlighted?.let { "highlight" } ?: (lastSpokenSelectionType ?: "highlight"),
				markPending = false,
			)
		}
	}

	// ── Scheduling ──────────────────────────────────────────────────────

	fun scheduleSpeakSelected(text: String?, type: String?) {
		scheduledSpeakJob?.cancel()
		scheduledSpeakJob = null
		if (text.isNullOrBlank() || type.isNullOrBlank()) return
		// Respect word/phrase toggles
		val allow = when (type) {
			"PH" -> speakOutputPhraseEnabled
			else -> speakOutputWordEnabled
		}
		if (!allow) return
		if (speakDelayMs <= 0L) return
		pendingSelection = PendingSelection(text, type, spoken = false)
		lastScheduledSelectionText = text
		lastScheduledSelectionType = type
		scheduledSpeakJob = scope.launch {
			delay(speakDelayMs)
			pendingSelection?.let { pending ->
				if (speakIfEnabled(pending)) {
					// Keep the pending entry with spoken=true so later ambiguous keys see it as already spoken
					pendingSelection = pending
				}
			}
		}
	}

	fun cancelScheduledSpeak(clearPending: Boolean = false) {
		scheduledSpeakJob?.cancel()
		scheduledSpeakJob = null
		if (clearPending) {
			pendingSelection = null
			lastScheduledSelectionText = null
			lastScheduledSelectionType = null
		}
	}

	/**
	 * Handles selection-changed speech scheduling from JTUI's onUiUpdate callback.
	 * Encapsulates the speak scheduling logic that was previously inline in the IME.
	 */
	fun handleSelectionSpeech(
		speakState: Boolean,
		selText: String,
		selType: String,
	) {
		if (!speakState) return
		val isSpeakableType = selType in listOf("X", "L", "E", "2", "PH")
		val selectionChanged = selText != lastScheduledSelectionText || selType != lastScheduledSelectionType
		if (selectionChanged) {
			cancelScheduledSpeak(clearPending = true)
			lastScheduledSelectionText = selText
			lastScheduledSelectionType = selType
		}
		if (isSpeakableType && selText.isNotBlank()) {
			val pending = pendingSelection
			if (pending != null && pending.text == selText && pending.type == selType && pending.spoken) {
				// Already spoken for this selection; ensure no timer remains
				cancelScheduledSpeak(clearPending = false)
			} else {
				scheduleSpeakSelected(selText, selType)
			}
		} else {
			cancelScheduledSpeak(clearPending = true)
		}
	}

	// ── Pending selection helpers ────────────────────────────────────────

	fun reuseOrCreatePendingSelection(text: String, defaultType: String): PendingSelection {
		val existing = pendingSelection
		if (existing != null && existing.text == text) return existing
		return PendingSelection(text, defaultType, spoken = false)
	}

	fun setPendingSelection(pending: PendingSelection?) {
		pendingSelection = pending
	}

	fun rememberLastSpoken(text: String, type: String, markPending: Boolean = true) {
		lastSpokenSelectionText = text
		lastSpokenSelectionType = type
		if (markPending) {
			pendingSelection = PendingSelection(text, type, spoken = true)
		}
	}

	// ── Spell/numeric accumulation ──────────────────────────────────────

	@Suppress("UnusedParameter") // trigger kept for future debug logging
	fun flushSpellNumericIfNeeded(trigger: String) {
		if (pendingSpellNumericSpoken) return
		val text = pendingSpellNumericBuffer.toString()
		if (text.isNotBlank()) {
			if (speakIfEnabled(PendingSelection(text, "X", spoken = false))) {
				pendingSpellNumericSpoken = true
				lastSpokenSelectionText = text
				lastSpokenSelectionType = "X"
			}
		}
		pendingSpellNumericBuffer.clear()
	}

	fun recordSpellNumeric(text: String) {
		if (text.isBlank()) return
		pendingSpellNumericBuffer.append(text)
		pendingSpellNumericSpoken = false
	}

	// ── Pure decision helpers (unit-testable, no live engine needed) ─────

	/** Whether a language switch needs a fresh engine (package change / none yet) or can reuse the live one. */
	enum class Reconfigure { Recreate, ReuseSetLocale }

	/** Coarse result of [TextToSpeech.setLanguage]. */
	enum class TtsAvailability { AVAILABLE, MISSING_DATA, NOT_SUPPORTED }

	companion object {
		private const val TAG = "TtsController"
		private const val PREFIX_NONINT = "nonint:"
		private const val PREFIX_AMB = "amb:"
		private const val PREFIX_UI = "ui:"
		private val TYPING_ENGINE_PREFIXES = listOf(PREFIX_NONINT, PREFIX_AMB)

		/**
		 * A [TextToSpeech] engine can't be swapped in place, so any change in engine package (or no
		 * engine yet) forces [Reconfigure.Recreate]; an unchanged package reuses the live instance.
		 * Empty strings are normalized to null ("use the default engine").
		 */
		fun decideReconfigure(current: String?, target: String?, hasEngine: Boolean): Reconfigure {
			if (!hasEngine) return Reconfigure.Recreate
			return if (current?.ifEmpty { null } != target?.ifEmpty { null }) {
				Reconfigure.Recreate
			} else {
				Reconfigure.ReuseSetLocale
			}
		}

		/** Maps a [TextToSpeech.setLanguage] result code to a coarse availability bucket. */
		fun classifyAvailability(code: Int): TtsAvailability = when (code) {
			TextToSpeech.LANG_MISSING_DATA -> TtsAvailability.MISSING_DATA
			TextToSpeech.LANG_NOT_SUPPORTED -> TtsAvailability.NOT_SUPPORTED
			else -> TtsAvailability.AVAILABLE // LANG_AVAILABLE / LANG_COUNTRY_AVAILABLE / LANG_COUNTRY_VAR_AVAILABLE
		}
	}
}
