/** Wires an input method (switches, scanning, joystick, pointer) to key presses and highlights. */
import { type PointerEvent as ReactPointerEvent, useEffect, useMemo, useRef, useState } from "react";
import { ZoneSelector, directionToSlot } from "./direction";
import { SCAN_ORDER_ALPHA, SCAN_ORDER_OPTIMIZED, ScanState } from "./scan";
import { type SwitchSide, TwoSwitchState } from "./twoSwitch";

export type InputMethod = "direct" | "scan" | "twoSwitch" | "joystick" | "mouseJoystick" | "swipe";

export interface SwitchKeys {
	/** `KeyboardEvent.code` values for the single switch / the green switch. */
	primary: string[];
	/** `KeyboardEvent.code` values for the red switch. */
	secondary: string[];
}

export const DEFAULT_SWITCH_KEYS: SwitchKeys = {
	primary: ["Space", "Enter", "KeyF", "ArrowLeft"],
	secondary: ["KeyJ", "ArrowRight"],
};

/** Physical keys that press a slot directly: the numpad and the QWE/AD/ZXC block. */
const DIRECT_KEYS: Record<string, number> = {
	Numpad7: 0,
	Numpad8: 1,
	Numpad9: 2,
	Numpad4: 3,
	Numpad6: 4,
	Numpad1: 5,
	Numpad2: 6,
	Numpad3: 7,
	KeyQ: 0,
	KeyW: 1,
	KeyE: 2,
	KeyA: 3,
	KeyD: 4,
	KeyZ: 5,
	KeyX: 6,
	KeyC: 7,
};

const GAMEPAD_PRIMARY_BUTTON = 0;
const GAMEPAD_SECONDARY_BUTTON = 1;
const SWIPE_THRESHOLD_PX = 48;

export interface InputMethodOptions {
	method: InputMethod;
	/** Slots that currently hold a usable key. */
	validSlots: number[];
	onPress: (slot: number) => void;
	alphaLayout: boolean;
	scanIntervalMs: number;
	scanMaxPasses: number;
	/** Idle time after which a partial two-switch choice resets (0 = never). */
	twoSwitchTimeoutMs: number;
	switchKeys: SwitchKeys;
	/** Listen for physical keys and gamepads on the window. */
	captureKeys: boolean;
}

export interface InputMethodState {
	/** Slots to highlight as the current choice. */
	highlight: number[];
	/** Two-switch mode: which switch each slot belongs to. */
	groups: { green: number[]; red: number[] } | null;
	/** Pointer handlers for the key grid (joystick-style pointer methods). */
	gridHandlers: {
		onPointerDown?: (e: ReactPointerEvent<HTMLElement>) => void;
		onPointerMove?: (e: ReactPointerEvent<HTMLElement>) => void;
		onPointerUp?: (e: ReactPointerEvent<HTMLElement>) => void;
		onPointerLeave?: (e: ReactPointerEvent<HTMLElement>) => void;
		onPointerCancel?: (e: ReactPointerEvent<HTMLElement>) => void;
	};
}

function isEditable(target: EventTarget | null): boolean {
	const el = target as HTMLElement | null;
	return !!el && (el.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName));
}

export function useInputMethod(options: InputMethodOptions): InputMethodState {
	const { method, validSlots, alphaLayout, scanIntervalMs, scanMaxPasses, twoSwitchTimeoutMs, captureKeys } = options;
	const validKey = validSlots.join(",");
	const latest = useRef(options);
	latest.current = options;

	const [, rerender] = useState(0);
	const [pointing, setPointing] = useState<number | null>(null);
	// Bumped on each switch press so the scan clock restarts with a full interval.
	const [scanEpoch, setScanEpoch] = useState(0);

	const scan = useMemo(
		() =>
			method === "scan"
				? new ScanState(
						{ order: alphaLayout ? SCAN_ORDER_ALPHA : SCAN_ORDER_OPTIMIZED, maxPasses: scanMaxPasses },
						validSlots,
					)
				: null,
		[method, alphaLayout, scanMaxPasses, validKey],
	);
	const twoSwitch = useMemo(() => (method === "twoSwitch" ? new TwoSwitchState(validSlots) : null), [method, validKey]);
	const zone = useMemo(() => new ZoneSelector((slot) => latest.current.validSlots.includes(slot)), [method, validKey]);

	const press = (slot: number | null) => {
		if (slot !== null && latest.current.validSlots.includes(slot)) latest.current.onPress(slot);
	};

	const activate = (side: SwitchSide) => {
		if (scan) press(scan.activate());
		else if (twoSwitch) press(twoSwitch.press(side));
		setScanEpoch((n) => n + 1);
	};
	const activateRef = useRef(activate);
	activateRef.current = activate;

	const applyZone = (x: number, y: number) => {
		const { highlight, fire } = zone.update(x, y);
		setPointing(highlight);
		press(fire);
	};
	const applyZoneRef = useRef(applyZone);
	applyZoneRef.current = applyZone;

	// Scan clock.
	useEffect(() => {
		if (!scan) return;
		const timer = setInterval(() => {
			scan.tick();
			rerender((n) => n + 1);
		}, scanIntervalMs);
		return () => clearInterval(timer);
	}, [scan, scanIntervalMs, scanEpoch]);

	// Two-switch idle reset.
	const partial = twoSwitch ? twoSwitch.current.green.length + twoSwitch.current.red.length : 0;
	useEffect(() => {
		if (!twoSwitch || twoSwitchTimeoutMs <= 0) return;
		const timer = setTimeout(() => {
			twoSwitch.reset();
			rerender((n) => n + 1);
		}, twoSwitchTimeoutMs);
		return () => clearTimeout(timer);
	}, [twoSwitch, twoSwitchTimeoutMs, partial]);

	// Physical keys.
	useEffect(() => {
		if (!captureKeys) return;
		const onKeyDown = (e: KeyboardEvent) => {
			if (e.repeat || e.ctrlKey || e.metaKey || e.altKey || isEditable(e.target)) return;
			const { method: m, switchKeys } = latest.current;
			if (m === "scan" || m === "twoSwitch") {
				const side = switchKeys.primary.includes(e.code) ? "green" : switchKeys.secondary.includes(e.code) ? "red" : null;
				if (!side) return;
				e.preventDefault();
				activateRef.current(side);
			} else if (m === "direct" && e.code in DIRECT_KEYS) {
				e.preventDefault();
				press(DIRECT_KEYS[e.code]);
			}
		};
		window.addEventListener("keydown", onKeyDown);
		return () => window.removeEventListener("keydown", onKeyDown);
	}, [captureKeys]);

	// Gamepad: the stick for joystick mode, face buttons as switches otherwise.
	useEffect(() => {
		const usesGamepad = method === "joystick" || method === "scan" || method === "twoSwitch";
		if (!captureKeys || !usesGamepad || typeof navigator === "undefined" || !navigator.getGamepads) return;
		let frame = 0;
		const down = [false, false];
		const poll = () => {
			const pad = [...navigator.getGamepads()].find((p) => p?.connected);
			if (pad) {
				if (method === "joystick") {
					const [lx = 0, ly = 0, rx = 0, ry = 0] = pad.axes;
					// Whichever stick is deflected further.
					const useRight = Math.hypot(rx, ry) > Math.hypot(lx, ly);
					applyZoneRef.current(useRight ? rx : lx, useRight ? ry : ly);
				} else {
					[GAMEPAD_PRIMARY_BUTTON, GAMEPAD_SECONDARY_BUTTON].forEach((button, i) => {
						const pressed = !!pad.buttons[button]?.pressed;
						if (pressed && !down[i]) activateRef.current(i === 0 ? "green" : "red");
						down[i] = pressed;
					});
				}
			}
			frame = requestAnimationFrame(poll);
		};
		frame = requestAnimationFrame(poll);
		return () => cancelAnimationFrame(frame);
	}, [method, captureKeys]);

	// Pointer methods.
	const swipeStart = useRef<{ x: number; y: number } | null>(null);
	const gridHandlers: InputMethodState["gridHandlers"] = {};
	if (method === "mouseJoystick") {
		gridHandlers.onPointerMove = (e) => {
			const rect = e.currentTarget.getBoundingClientRect();
			const radius = Math.min(rect.width, rect.height) / 2;
			applyZone((e.clientX - rect.left - rect.width / 2) / radius, (e.clientY - rect.top - rect.height / 2) / radius);
		};
		gridHandlers.onPointerLeave = () => {
			zone.cancel();
			setPointing(null);
		};
	} else if (method === "swipe") {
		const swipeSlot = (e: ReactPointerEvent<HTMLElement>): number | null => {
			const start = swipeStart.current;
			if (!start) return null;
			const dx = e.clientX - start.x;
			const dy = e.clientY - start.y;
			if (Math.hypot(dx, dy) < SWIPE_THRESHOLD_PX) return null;
			const slot = directionToSlot(dx, dy);
			return validSlots.includes(slot) ? slot : null;
		};
		const end = () => {
			swipeStart.current = null;
			setPointing(null);
		};
		gridHandlers.onPointerDown = (e) => {
			e.currentTarget.setPointerCapture(e.pointerId);
			swipeStart.current = { x: e.clientX, y: e.clientY };
		};
		gridHandlers.onPointerMove = (e) => setPointing(swipeSlot(e));
		gridHandlers.onPointerUp = (e) => {
			const slot = swipeSlot(e);
			end();
			press(slot);
		};
		gridHandlers.onPointerCancel = end;
	}

	let highlight: number[] = [];
	if (scan) highlight = scan.highlight === null ? [] : [scan.highlight];
	else if (method === "joystick" || method === "mouseJoystick" || method === "swipe") {
		highlight = pointing === null ? [] : [pointing];
	}

	return { highlight, groups: twoSwitch ? twoSwitch.current : null, gridHandlers };
}
