package org.continuouspath.justtype.activity

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.FrameLayout
import org.continuouspath.justtype.SpeechIndicatorOverlay
import org.continuouspath.justtype.SpeechIndicatorTracker
import org.continuouspath.justtype.settings.SettingsRepository
import org.continuouspath.justtype.settings.getBoolean
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import org.continuouspath.justtype.Constants as C

/**
 * Lightweight, process-scoped TTS for spoken settings prompts shown in System
 * Settings activities. Initializes lazily on first use. The IME service uses
 * [org.continuouspath.justtype.ime.TtsController] for keyboard-side TTS; that
 * controller depends on the IME lifecycle and is not reusable from activities,
 * so this companion exists for the activity surface.
 *
 * Speech is gated by the [C.KEY_SPEAK_SETTINGS_PROMPTS] preference (default ON).
 * While speaking, the shared [SpeechIndicatorOverlay] icon shows on the activity
 * the prompt was spoken from.
 */
object SettingsSpeechController {

	private const val UTTERANCE_PREFIX = "settings_prompt:"

	private var tts: TextToSpeech? = null

	@Volatile
	private var isReady = false
	private val pending = mutableListOf<String>()

	private val mainHandler = Handler(Looper.getMainLooper())
	private val utteranceSerial = AtomicLong()
	private var hostActivityRef: WeakReference<Activity>? = null
	private var overlay: SpeechIndicatorOverlay? = null
	private var appContext: Context? = null

	private val tracker = SpeechIndicatorTracker { speaking ->
		mainHandler.post { updateIndicator(speaking) }
	}

	/**
	 * Speak [text] if the user has Speak Prompts Aloud enabled. Otherwise no-op.
	 * Safe to call before TTS finishes initializing — the utterance is queued
	 * and spoken once the engine is ready. Subsequent calls flush any in-flight
	 * speech so the most recent prompt is what the user hears.
	 */
	fun speakIfEnabled(context: Context, text: String) {
		val appCtx = context.applicationContext
		val repo = SettingsRepository.getInstance(appCtx)
		if (!repo.getBoolean(C.KEY_SPEAK_SETTINGS_PROMPTS)) return
		appContext = appCtx
		hostActivityRef = findActivity(context)?.let { WeakReference(it) }
		ensureInit(appCtx)
		val engine = tts
		if (isReady && engine != null) {
			speakTracked(engine, text, TextToSpeech.QUEUE_FLUSH)
		} else {
			synchronized(pending) {
				pending.clear()
				pending += text
			}
		}
	}

	/**
	 * Cancel any in-flight or queued utterance. Called when a dialog is
	 * dismissed or the user leaves a settings activity, so spoken help text
	 * does not continue past the moment it stops being relevant.
	 */
	fun stop() {
		synchronized(pending) { pending.clear() }
		tts?.stop()
		tracker.reset()
	}

	/**
	 * Release the TTS engine and its service connection. Called when settings is fully torn down
	 * (activity onDestroy); the engine re-inits lazily on the next [speakIfEnabled]. Without this the
	 * process-scoped engine (and its OnInitListener / binder) leaks for the process lifetime.
	 */
	fun shutdown() {
		synchronized(pending) { pending.clear() }
		tts?.shutdown()
		tts = null
		isReady = false
		tracker.reset()
		// Drop the overlay so its cached view can't outlive the activity it was attached to.
		mainHandler.post {
			overlay?.hide()
			overlay = null
			hostActivityRef = null
		}
	}

	private fun ensureInit(appContext: Context) {
		if (tts != null) return
		tts = TextToSpeech(appContext) { status ->
			if (status == TextToSpeech.SUCCESS) {
				tts?.language = Locale.getDefault()
				tts?.setOnUtteranceProgressListener(progressListener)
				isReady = true
				val toSpeak = synchronized(pending) {
					val copy = pending.toList()
					pending.clear()
					copy
				}
				toSpeak.forEach { text -> tts?.let { speakTracked(it, text, TextToSpeech.QUEUE_ADD) } }
			}
		}
	}

	private fun speakTracked(engine: TextToSpeech, text: String, queueMode: Int) {
		val id = UTTERANCE_PREFIX + utteranceSerial.incrementAndGet()
		if (engine.speak(text, queueMode, null, id) == TextToSpeech.SUCCESS) {
			val flushed = if (queueMode == TextToSpeech.QUEUE_FLUSH) listOf(UTTERANCE_PREFIX) else emptyList()
			tracker.onEnqueued(id, flushedPrefixes = flushed)
		}
	}

	private val progressListener = object : UtteranceProgressListener() {
		override fun onStart(utteranceId: String?) = tracker.onUtteranceStarted()
		override fun onDone(utteranceId: String?) = tracker.onUtteranceFinished(utteranceId)

		@Deprecated("Deprecated in Java")
		override fun onError(utteranceId: String?) = tracker.onUtteranceFinished(utteranceId)
		override fun onStop(utteranceId: String?, interrupted: Boolean) = tracker.onUtteranceFinished(utteranceId)
	}

	private fun updateIndicator(speaking: Boolean) {
		if (!speaking) {
			overlay?.hide()
			return
		}
		val ctx = appContext ?: return
		val current = overlay ?: SpeechIndicatorOverlay(ctx) {
			hostActivityRef?.get()
				?.takeUnless { it.isFinishing || it.isDestroyed }
				?.findViewById<FrameLayout>(android.R.id.content)
		}.also { overlay = it }
		current.show(SpeechIndicatorOverlay.iconResFor(SettingsRepository.getInstance(ctx)))
	}

	private fun findActivity(context: Context): Activity? = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
		.filterIsInstance<Activity>()
		.firstOrNull()
}
