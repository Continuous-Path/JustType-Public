import { type CSSProperties, type ReactNode, useEffect, useMemo, useRef } from "react";
import {
	type EngineOptions,
	JustTypeEngine,
	type KeyFace,
	type ListItem,
	SLOT_COUNT,
	type Snapshot,
} from "./engine/engine";
import { type FeedbackOptions, errorFeedback, keyFeedback, speak } from "./feedback";
import { DEFAULT_SWITCH_KEYS, type InputMethod, type SwitchKeys, useInputMethod } from "./input/useInputMethod";
import { useEngineEvents, useEngineSnapshot } from "./useJustType";

type EngineProps = Pick<
	EngineOptions,
	| "data"
	| "ngrams"
	| "symbols"
	| "store"
	| "layoutMode"
	| "excludedWords"
	| "autoCapitalize"
	| "speech"
	| "wordSelection"
	| "pagedListedWords"
	| "nextLetterHints"
	| "familyExpand"
	| "familyExpandDelayMs"
	| "pullIn"
	| "accentFallback"
	| "initialText"
>;

export interface JustTypeKeyboardProps extends Partial<EngineProps> {
	/** An engine owned by the host (from `useJustType`); otherwise one is created from `data`. */
	engine?: JustTypeEngine;
	inputMethod?: InputMethod;
	/** Time each key stays highlighted while scanning. */
	scanIntervalMs?: number;
	/** Full scan passes without a press before scanning pauses (0 = never). */
	scanMaxPasses?: number;
	/** Idle time after which a partial two-switch choice resets (0 = never). */
	twoSwitchTimeoutMs?: number;
	switchKeys?: SwitchKeys;
	/** Listen for physical keys and gamepads on the window (default true). */
	captureKeys?: boolean;
	feedback?: FeedbackOptions;
	/** Show the composed text above the keyboard (default true). */
	showText?: boolean;
	/** BCP 47 tag for the default speech voice. */
	speechLang?: string;
	onTextChange?: (text: string, snapshot: Snapshot) => void;
	/** Replaces the built-in Web Speech output. */
	onSpeak?: (text: string) => void;
	/** Replaces the built-in clipboard copy. */
	onCopy?: (text: string) => void;
	className?: string;
	style?: CSSProperties;
}

/** Grid cell order (row-major) -> slot; null is the centre cell. */
const GRID_SLOTS: (number | null)[] = [0, 1, 2, 3, null, 4, 5, 6, 7];
const DEFAULT_FEEDBACK: FeedbackOptions = { beep: true, haptic: true };
const TOKEN = /[\p{L}\p{N}'’-]+|[^\p{L}\p{N}'’-]+/gu;
const WORD = /^[\p{L}\p{N}]/u;
const SCROLL_FRACTION = 0.8;

function keyLabel(face: KeyFace): string {
	if (face.kind === "letters") {
		const letters = (face.grid ?? []).filter(Boolean).join(" ");
		return face.badge ? `${letters}, then Select for ${face.badge}` : letters;
	}
	return (face.label ?? "").replace(/\n/g, " ");
}

function KeyContent({ face }: { face: KeyFace }) {
	if (face.kind === "letters") {
		return (
			<span className="jt-face" aria-hidden="true">
				{(face.grid ?? []).map((cell, i) => {
					const classes = ["jt-cell"];
					if (i === 4 && face.badge) classes.push("jt-badge");
					if (face.hints && cell && !face.hints[i]) classes.push("jt-cell-dim");
					return (
						<span key={GRID_SLOTS[i] ?? "centre"} className={classes.join(" ")}>
							{i === 4 && face.badge ? face.badge : cell}
						</span>
					);
				})}
			</span>
		);
	}
	return (
		<span className={face.kind === "word" ? "jt-label jt-label-word" : "jt-label"} aria-hidden="true">
			{face.label}
		</span>
	);
}

function listItemClass(item: ListItem, selected: boolean, onPage: boolean): string {
	const classes = ["jt-item"];
	if (selected) classes.push("jt-item-selected");
	if (onPage) classes.push("jt-item-paged");
	if (item.type === "page") classes.push("jt-item-page");
	else if (item.type === "phrase") classes.push("jt-item-phrase");
	else if (item.type === "pad") classes.push("jt-item-pad");
	else {
		if (item.predicted) classes.push("jt-item-predicted");
		else if (item.keysRemaining > 0) classes.push("jt-item-partial");
		if (item.family) classes.push("jt-item-family");
		if (item.expandable) classes.push("jt-item-expandable");
	}
	return classes.join(" ");
}

/**
 * Renders `text` (which starts at `offset` in the message) as tokens: words are tappable to
 * reload them for editing, anything else moves the cursor. The selected range is marked.
 */
function renderTokens(
	text: string,
	offset: number,
	selection: [number, number] | null,
	onWord: (index: number) => void,
	onGap: (index: number) => void,
): ReactNode[] {
	const out: ReactNode[] = [];
	for (const match of text.matchAll(TOKEN)) {
		const start = offset + (match.index ?? 0);
		const end = start + match[0].length;
		const isWord = WORD.test(match[0]);
		// Split the token where the selection starts or ends inside it.
		const cuts = [start, ...(selection ?? []).filter((edge) => edge > start && edge < end), end];
		for (let i = 0; i < cuts.length - 1; i++) {
			const selected = !!selection && cuts[i] >= selection[0] && cuts[i + 1] <= selection[1];
			out.push(
				<span
					key={cuts[i]}
					className={selected ? "jt-token jt-token-selected" : "jt-token"}
					data-word={isWord || undefined}
					onClick={() => (isWord ? onWord(end) : onGap(end))}
				>
					{text.slice(cuts[i] - offset, cuts[i + 1] - offset)}
				</span>,
			);
		}
	}
	return out;
}

export function JustTypeKeyboard(props: JustTypeKeyboardProps) {
	const {
		inputMethod = "direct",
		scanIntervalMs = 1200,
		scanMaxPasses = 3,
		twoSwitchTimeoutMs = 0,
		switchKeys = DEFAULT_SWITCH_KEYS,
		captureKeys = true,
		feedback = DEFAULT_FEEDBACK,
		showText = true,
	} = props;

	const engine = useMemo(() => {
		if (props.engine) return props.engine;
		if (!props.data) throw new Error("JustTypeKeyboard needs either `engine` or `data`.");
		return new JustTypeEngine({ ...props, data: props.data });
	}, [props.engine, props.data, props.store]);
	const snapshot = useEngineSnapshot(engine);

	// A host-owned engine manages its own data and options.
	const ownsEngine = !props.engine;
	useEffect(() => {
		if (ownsEngine) engine.setNgrams(props.ngrams);
	}, [engine, ownsEngine, props.ngrams]);
	useEffect(() => {
		if (ownsEngine) engine.setSymbols(props.symbols);
	}, [engine, ownsEngine, props.symbols]);
	useEffect(() => {
		if (ownsEngine && props.wordSelection) engine.setWordSelection(props.wordSelection);
	}, [engine, ownsEngine, props.wordSelection]);
	useEffect(() => {
		if (ownsEngine && props.nextLetterHints !== undefined) engine.setNextLetterHints(props.nextLetterHints);
	}, [engine, ownsEngine, props.nextLetterHints]);
	useEffect(() => {
		if (ownsEngine && props.familyExpand !== undefined) engine.setFamilyExpand(props.familyExpand);
	}, [engine, ownsEngine, props.familyExpand]);
	useEffect(() => {
		engine.setScanOrder(inputMethod === "scan");
	}, [engine, inputMethod]);

	const textRef = useRef<HTMLDivElement>(null);
	const caretRef = useRef<HTMLSpanElement>(null);
	const { onSpeak, onCopy, onTextChange, speechLang } = props;
	useEngineEvents(engine, {
		onSpeak: (text) => (onSpeak ? onSpeak(text) : speak(text, { lang: speechLang })),
		onCopy: (text) => (onCopy ? onCopy(text) : void navigator.clipboard?.writeText(text)),
		onError: () => errorFeedback(feedback),
		onScroll: (direction) => {
			const box = textRef.current;
			box?.scrollBy({ top: (direction === "up" ? -1 : 1) * box.clientHeight * SCROLL_FRACTION });
		},
	});

	const textChange = useRef(onTextChange);
	textChange.current = onTextChange;
	useEffect(() => {
		textChange.current?.(snapshot.text, snapshot);
	}, [snapshot.text]);

	useEffect(() => {
		caretRef.current?.scrollIntoView?.({ block: "nearest" });
	}, [snapshot.cursor, snapshot.composing]);

	const validSlots = useMemo(
		() => snapshot.keys.flatMap((key, slot) => (key.kind === "blank" ? [] : [slot])),
		[snapshot.keys],
	);
	const press = (slot: number) => {
		keyFeedback(feedback);
		engine.press(slot);
	};
	const input = useInputMethod({
		method: inputMethod,
		validSlots,
		onPress: press,
		alphaLayout: snapshot.layoutMode === "alpha",
		scanIntervalMs,
		scanMaxPasses,
		twoSwitchTimeoutMs,
		switchKeys,
		captureKeys,
	});

	const selectedRef = useRef<HTMLLIElement>(null);
	useEffect(() => {
		selectedRef.current?.scrollIntoView?.({ block: "nearest" });
	}, [snapshot.selection, snapshot.pagedPage]);

	const status = [snapshot.capsLock ? "CAPS" : snapshot.shift ? "SHIFT" : "", snapshot.speech ? "SPEECH" : ""]
		.filter(Boolean)
		.join(" · ");
	const tokens = (text: string, offset: number) =>
		renderTokens(
			text,
			offset,
			snapshot.textSelection,
			(index) => engine.pullInAt(index),
			(index) => engine.setCursor(index),
		);
	const flow = snapshot.phraseFlow;
	// In a selection page the follow target is the page's first row.
	const followRow = snapshot.pagedRows ? snapshot.pagedRows[0] : snapshot.selection;

	return (
		<div className={["jt", props.className].filter(Boolean).join(" ")} style={props.style} data-method={inputMethod}>
			{flow && (
				<div className="jt-banner" role="status">
					{flow.phase === "phrase"
						? "NEW PHRASE — type it, then FNS + Select → DONE WITH PHRASE"
						: `ABBREVIATION for “${flow.phrase}” — spell it, then <DONE>`}
				</div>
			)}
			{showText && (
				<div className="jt-text" ref={textRef} aria-live="polite" aria-label="Message">
					{tokens(snapshot.before, 0)}
					{snapshot.composing && (
						<span className="jt-composing">
							{snapshot.displayText.slice(snapshot.before.length, snapshot.displayText.length - snapshot.after.length)}
						</span>
					)}
					<span className="jt-caret" ref={caretRef} aria-hidden="true" />
					{tokens(snapshot.after, snapshot.cursor)}
				</div>
			)}
			<div className="jt-body">
				<ol className="jt-list" aria-label="Word choices">
					{snapshot.list.map((item, i) => {
						const selected = snapshot.selection === i;
						const onPage = !!snapshot.pagedRows && i >= snapshot.pagedRows[0] && i < snapshot.pagedRows[1];
						return (
							<li
								key={i}
								ref={i === followRow ? selectedRef : undefined}
								className={listItemClass(item, selected, onPage)}
								aria-current={selected || undefined}
								aria-hidden={item.type === "pad" || undefined}
							>
								{item.display}
							</li>
						);
					})}
				</ol>
				<div className="jt-grid" {...input.gridHandlers}>
					{GRID_SLOTS.map((slot) => {
						if (slot === null) {
							return (
								<div key="centre" className="jt-centre" aria-hidden="true">
									<span className={snapshot.centerText ? "jt-centre-mode" : "jt-centre-word"}>
										{snapshot.centerText || snapshot.composing}
									</span>
									<span className="jt-centre-status">{status}</span>
								</div>
							);
						}
						const face = snapshot.keys[slot];
						const group = input.groups?.green.includes(slot) ? "green" : input.groups?.red.includes(slot) ? "red" : undefined;
						return (
							<button
								key={slot}
								type="button"
								className="jt-key"
								data-kind={face.kind}
								data-highlight={input.highlight.includes(slot) || undefined}
								data-group={group}
								data-active={face.active || undefined}
								aria-label={keyLabel(face) || undefined}
								aria-pressed={face.active === undefined ? undefined : face.active}
								disabled={face.kind === "blank"}
								tabIndex={inputMethod === "direct" ? 0 : -1}
								onClick={inputMethod === "direct" ? () => press(slot) : undefined}
							>
								<KeyContent face={face} />
							</button>
						);
					})}
				</div>
			</div>
		</div>
	);
}

export { SLOT_COUNT };
