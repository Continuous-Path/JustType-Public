/** Key feedback (beep, haptic) and text-to-speech through standard web APIs. */

export interface FeedbackOptions {
	beep?: boolean;
	haptic?: boolean;
}

let audio: AudioContext | null = null;

function tone(frequency: number, durationMs: number): void {
	try {
		audio ??= new AudioContext();
		// Browsers start the context suspended until a user gesture.
		if (audio.state === "suspended") void audio.resume();
		const oscillator = audio.createOscillator();
		const gain = audio.createGain();
		const end = audio.currentTime + durationMs / 1000;
		oscillator.frequency.value = frequency;
		gain.gain.setValueAtTime(0.08, audio.currentTime);
		gain.gain.exponentialRampToValueAtTime(0.0001, end);
		oscillator.connect(gain).connect(audio.destination);
		oscillator.start();
		oscillator.stop(end);
	} catch {
		// No audio output available.
	}
}

function vibrate(pattern: number | number[]): void {
	// Not implemented on iOS Safari.
	if (typeof navigator !== "undefined" && "vibrate" in navigator) navigator.vibrate(pattern);
}

export function keyFeedback(options: FeedbackOptions): void {
	if (options.beep) tone(880, 40);
	if (options.haptic) vibrate(15);
}

export function errorFeedback(options: FeedbackOptions): void {
	if (options.beep) tone(220, 160);
	if (options.haptic) vibrate([40, 60, 40]);
}

export interface SpeakOptions {
	voice?: SpeechSynthesisVoice | null;
	lang?: string;
	rate?: number;
}

/** Speaks `text`, interrupting anything already being spoken. */
export function speak(text: string, options: SpeakOptions = {}): void {
	if (typeof speechSynthesis === "undefined" || !text) return;
	speechSynthesis.cancel();
	const utterance = new SpeechSynthesisUtterance(text);
	if (options.voice) utterance.voice = options.voice;
	if (options.lang) utterance.lang = options.lang;
	if (options.rate) utterance.rate = options.rate;
	speechSynthesis.speak(utterance);
}
