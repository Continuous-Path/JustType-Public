package org.continuouspath.justtype

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/** One selectable TTS voice: engine + named voice, with best-effort gender (from the voice name). */
data class TtsVoiceOption(
	val enginePackage: String,
	val engineLabel: String,
	val voiceName: String,
	val gender: VoiceGender,
	val language: String = "",
	val country: String = "",
)

/**
 * Enumerates installed TTS engines for voices supporting [locale]. Context-based (no Activity/dialog
 * coupling) so it serves both the Activity voice picker and the keyboard-driven settings overlay.
 * Engines are probed sequentially with temporary [TextToSpeech] instances (init callbacks are async),
 * bounded by an overall timeout. [onDone] fires exactly once on the main thread with the options found
 * plus the system default engine package (for auto-selection); a cancelled scan never fires it.
 */
class TtsVoiceScanner(
	private val context: Context,
	private val locale: Locale,
	private val onDone: (options: List<TtsVoiceOption>, defaultEngine: String?) -> Unit,
) {
	private val handler = Handler(Looper.getMainLooper())
	private val results = mutableListOf<TtsVoiceOption>()
	private var defaultEngine: String? = null
	private var timeout: Runnable? = null
	private var finished = false
	private var cancelled = false

	fun start() {
		val t = Runnable { finish() }
		timeout = t
		handler.postDelayed(t, SCAN_TIMEOUT_MS)

		var probe: TextToSpeech? = null
		probe = TextToSpeech(context) { status ->
			val p = probe
			if (status != TextToSpeech.SUCCESS || p == null) {
				p?.shutdown()
				finish()
				return@TextToSpeech
			}
			defaultEngine = runCatching { p.defaultEngine }.getOrNull()
			val engines = runCatching { p.engines }.getOrNull().orEmpty()
			val labels = engines.associate { it.name to (it.label ?: it.name) }
			val packages = engines.map { it.name }.ifEmpty { listOfNotNull(defaultEngine) }
			p.shutdown()
			scanEngines(packages, labels)
		}
	}

	/** Stops the scan; [onDone] will not fire. Safe to call at any point. */
	fun cancel() {
		cancelled = true
		timeout?.let { handler.removeCallbacks(it) }
		timeout = null
	}

	private fun scanEngines(packages: List<String>, labels: Map<String, String>) {
		fun probe(i: Int) {
			if (finished || cancelled) return
			if (i >= packages.size) {
				finish()
				return
			}
			val pkg = packages[i]
			var engine: TextToSpeech? = null
			engine = TextToSpeech(
				context,
				{ status ->
					val e = engine
					if (status == TextToSpeech.SUCCESS && e != null) collectVoices(e, pkg, labels[pkg] ?: pkg)
					e?.shutdown()
					probe(i + 1)
				},
				pkg,
			)
		}
		probe(0)
	}

	private fun collectVoices(engine: TextToSpeech, pkg: String, label: String) {
		val available = runCatching { engine.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
		if (available < TextToSpeech.LANG_AVAILABLE) return
		val voices = runCatching { engine.voices }.getOrNull().orEmpty()
		voices
			.filter { it.locale?.language?.equals(locale.language, ignoreCase = true) == true }
			.forEach {
				val loc = it.locale
				results.add(
					TtsVoiceOption(pkg, label, it.name, inferVoiceGender(pkg, it.name), loc?.language.orEmpty(), loc?.country.orEmpty()),
				)
			}
	}

	private fun finish() {
		if (finished || cancelled) return
		finished = true
		timeout?.let { handler.removeCallbacks(it) }
		timeout = null
		onDone(collapseVoiceTwins(results.toList()), defaultEngine)
	}

	companion object {
		private const val SCAN_TIMEOUT_MS = 8000L
	}
}

/** True when the device has a validated internet connection (network TTS voices can synthesize). */
fun isDeviceOnline(context: Context): Boolean {
	val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
	val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
	return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
		caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Speaks a short sample phrase with a specific candidate voice, using its own short-lived
 * [TextToSpeech] so the IME's live engine binding is never disturbed. Call [shutdown] when done.
 */
class TtsVoicePreview(private val context: Context, private val locale: Locale) {
	private var tts: TextToSpeech? = null

	fun play(option: TtsVoiceOption) {
		tts?.shutdown()
		var t: TextToSpeech? = null
		t = TextToSpeech(
			context,
			{ status ->
				val tt = t
				if (status != TextToSpeech.SUCCESS || tt == null) return@TextToSpeech
				tt.language = locale
				runCatching { tt.voices }.getOrNull()?.let { voices ->
					// Preview the variant that would actually speak right now (see preferredVoiceVariant).
					val wanted = preferredVoiceVariant(option.voiceName, voices.mapTo(mutableSetOf()) { it.name }, isDeviceOnline(context))
					voices.firstOrNull { it.name == wanted }?.let { tt.voice = it }
				}
				tt.speak(samplePhrase(locale), TextToSpeech.QUEUE_FLUSH, null, "tts-voice-preview")
			},
			option.enginePackage,
		)
		tts = t
	}

	fun shutdown() {
		tts?.shutdown()
		tts = null
	}

	companion object {
		/** A short phrase in the target language so previews reflect the voice/locale, not the UI language. */
		fun samplePhrase(locale: Locale): String = when (locale.language.lowercase(Locale.ROOT)) {
			"es" -> "Hola, así suena esta voz."
			else -> "Hello, this is how this voice sounds."
		}
	}
}

/**
 * Seam for the keyboard settings voice picker: scanning and previewing are the only TTS touches the
 * controller makes, injected so unit tests can fake them (real TTS engines don't exist in Robolectric).
 */
interface TtsVoiceServices {
	/** Starts an async voice scan; returns a cancel function. [onDone] fires at most once, on main. */
	fun startScan(locale: java.util.Locale, onDone: (List<TtsVoiceOption>, String?) -> Unit): () -> Unit

	/** Speaks a sample with [option]'s engine+voice. */
	fun preview(option: TtsVoiceOption, locale: java.util.Locale)

	/** Stops and releases any preview engine. */
	fun stopPreview()
}

/** Production [TtsVoiceServices] over [TtsVoiceScanner]/[TtsVoicePreview]. */
class DefaultTtsVoiceServices(private val context: Context) : TtsVoiceServices {
	private var preview: TtsVoicePreview? = null

	override fun startScan(locale: java.util.Locale, onDone: (List<TtsVoiceOption>, String?) -> Unit): () -> Unit {
		val scanner = TtsVoiceScanner(context, locale, onDone).also { it.start() }
		return { scanner.cancel() }
	}

	override fun preview(option: TtsVoiceOption, locale: java.util.Locale) {
		(preview ?: TtsVoicePreview(context, locale).also { preview = it }).play(option)
	}

	override fun stopPreview() {
		preview?.shutdown()
		preview = null
	}
}
