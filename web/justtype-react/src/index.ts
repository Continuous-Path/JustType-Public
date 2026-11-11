import "./styles.css";

export { JustTypeKeyboard, type JustTypeKeyboardProps } from "./JustTypeKeyboard";
export { useEngineSnapshot, useJustType } from "./useJustType";
export {
	JustTypeEngine,
	SELECT_SLOT,
	SLOT_COUNT,
	UNDO_SLOT,
	type EngineEvents,
	type EngineOptions,
	type ExcludedWords,
	type KeyFace,
	type LanguageData,
	type ListItem,
	type NgramData,
	type PhraseFlowPhase,
	type Snapshot,
	type WordSelection,
} from "./engine/engine";
export type { SymbolData } from "./engine/allSymbols";
export type { LayoutMode } from "./engine/layout";
export { localStorageStore, type EngineStore, type PersistedState, type Phrase } from "./engine/store";
export { errorFeedback, keyFeedback, speak, type FeedbackOptions, type SpeakOptions } from "./feedback";
export { DEFAULT_SWITCH_KEYS, type InputMethod, type SwitchKeys } from "./input/useInputMethod";
export { ZoneSelector, directionToSlot } from "./input/direction";
export { SCAN_ORDER_ALPHA, SCAN_ORDER_OPTIMIZED, ScanState } from "./input/scan";
export { TwoSwitchState, type SwitchSide } from "./input/twoSwitch";
