import { StrictMode, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
	type InputMethod,
	JustTypeKeyboard,
	type LanguageData,
	type NgramData,
	type SymbolData,
	localStorageStore,
} from "../src";

const METHODS: Record<InputMethod, string> = {
	direct: "Direct selection — tap keys, or use the numpad / QWE·AD·ZXC. Tap a word in the message to edit it.",
	scan: "Single-switch scanning — Space or Enter (or gamepad A) picks the highlighted key",
	twoSwitch: "Two-switch — F / ← is green, J / → is red (gamepad A / B)",
	joystick: "Joystick — push a gamepad stick toward a key, release to press",
	mouseJoystick: "Mouse joystick — move the pointer toward a key, return to centre to press",
	swipe: "Directional selection — swipe on the grid in a key's direction",
};

const LANGUAGES = { english: { label: "English", speech: "en" }, espanol: { label: "Español", speech: "es" } };
type Language = keyof typeof LANGUAGES;

const stores = { english: localStorageStore("justtype-demo-en"), espanol: localStorageStore("justtype-demo-es") };
// Vite needs literal paths to split these into their own chunks.
const loaders: Record<Language, { words: () => Promise<unknown>; ngrams: () => Promise<unknown> }> = {
	english: {
		words: () => import("../data/english.json"),
		ngrams: () => import("../data/english.ngrams.json"),
	},
	espanol: {
		words: () => import("../data/espanol.json"),
		ngrams: () => import("../data/espanol.ngrams.json"),
	},
};
const unwrap = <T,>(module: unknown) => (module as { default: T }).default;

function Demo() {
	const [language, setLanguage] = useState<Language>("english");
	const [data, setData] = useState<LanguageData | null>(null);
	const [ngrams, setNgrams] = useState<NgramData | null>(null);
	const [symbols, setSymbols] = useState<SymbolData | null>(null);
	const [method, setMethod] = useState<InputMethod>("direct");
	const [hints, setHints] = useState(false);
	const [families, setFamilies] = useState(false);
	const [paged, setPaged] = useState(true);

	useEffect(() => {
		let current = true;
		setData(null);
		setNgrams(null);
		void loaders[language].words().then((m) => current && setData(unwrap<LanguageData>(m)));
		// The next-word table is optional; it is attached whenever it arrives.
		void loaders[language].ngrams().then((m) => current && setNgrams(unwrap<NgramData>(m)));
		return () => {
			current = false;
		};
	}, [language]);
	useEffect(() => {
		void import("../data/symbols.json").then((m) => setSymbols(unwrap<SymbolData>(m)));
	}, []);

	return (
		<>
			<div className="controls">
				<label>
					Language{" "}
					<select value={language} onChange={(e) => setLanguage(e.target.value as Language)}>
						{Object.entries(LANGUAGES).map(([id, { label }]) => (
							<option key={id} value={id}>
								{label}
							</option>
						))}
					</select>
				</label>
				<label>
					Input method{" "}
					<select value={method} onChange={(e) => setMethod(e.target.value as InputMethod)}>
						{Object.keys(METHODS).map((m) => (
							<option key={m}>{m}</option>
						))}
					</select>
				</label>
				<label>
					<input type="checkbox" checked={paged} onChange={(e) => setPaged(e.target.checked)} /> Paged selection
				</label>
				<label>
					<input type="checkbox" checked={hints} onChange={(e) => setHints(e.target.checked)} /> Next-letter hints
				</label>
				<label>
					<input type="checkbox" checked={families} onChange={(e) => setFamilies(e.target.checked)} /> Word families
				</label>
			</div>
			{data ? (
				<JustTypeKeyboard
					data={data}
					ngrams={ngrams}
					symbols={symbols}
					store={stores[language]}
					inputMethod={method}
					wordSelection={paged ? "paged" : "list"}
					nextLetterHints={hints}
					familyExpand={families}
					speechLang={LANGUAGES[language].speech}
				/>
			) : (
				<p>Loading vocabulary…</p>
			)}
			<p className="hint">{METHODS[method]}</p>
		</>
	);
}

createRoot(document.getElementById("root") as HTMLElement).render(
	<StrictMode>
		<Demo />
	</StrictMode>,
);
