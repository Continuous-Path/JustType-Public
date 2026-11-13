import { describe, expect, it } from "vitest";
import { ZoneSelector, directionToSlot } from "../src/input/direction";
import { SCAN_ORDER_OPTIMIZED, ScanState } from "../src/input/scan";
import { TwoSwitchState } from "../src/input/twoSwitch";

const ALL = [0, 1, 2, 3, 4, 5, 6, 7];

describe("directionToSlot", () => {
	it("maps the eight directions to their slots", () => {
		expect(directionToSlot(-1, -1)).toBe(0);
		expect(directionToSlot(0, -1)).toBe(1);
		expect(directionToSlot(1, -1)).toBe(2);
		expect(directionToSlot(-1, 0)).toBe(3);
		expect(directionToSlot(1, 0)).toBe(4);
		expect(directionToSlot(-1, 1)).toBe(5);
		expect(directionToSlot(0, 1)).toBe(6);
		expect(directionToSlot(1, 1)).toBe(7);
	});

	it("gives diagonals the wider sector", () => {
		const at = (deg: number) => directionToSlot(Math.cos((deg * Math.PI) / 180), -Math.sin((deg * Math.PI) / 180));
		expect(at(19)).toBe(4);
		expect(at(21)).toBe(2);
		expect(at(359)).toBe(4);
	});
});

describe("ZoneSelector", () => {
	it("presses on the return to rest, not on deflection", () => {
		const zone = new ZoneSelector();
		expect(zone.update(0.9, 0)).toEqual({ highlight: 4, fire: null });
		expect(zone.update(0.4, 0)).toEqual({ highlight: 4, fire: null });
		expect(zone.update(0, 0)).toEqual({ highlight: null, fire: 4 });
		expect(zone.update(0, 0)).toEqual({ highlight: null, fire: null });
	});

	it("ignores unusable keys", () => {
		const zone = new ZoneSelector((slot) => slot !== 4);
		zone.update(1, 0);
		expect(zone.update(0, 0).fire).toBeNull();
	});
});

describe("ScanState", () => {
	it("walks the scan order and presses the highlighted key", () => {
		const scan = new ScanState({ order: SCAN_ORDER_OPTIMIZED, maxPasses: 0 }, ALL);
		expect(scan.highlight).toBe(6);
		scan.tick();
		scan.tick();
		expect(scan.highlight).toBe(0);
		expect(scan.activate()).toBe(0);
		expect(scan.highlight).toBe(6);
	});

	it("skips unusable keys and pauses after idle passes", () => {
		const scan = new ScanState({ order: SCAN_ORDER_OPTIMIZED, maxPasses: 2 }, [6, 1]);
		scan.tick();
		expect(scan.highlight).toBe(1);
		scan.tick();
		scan.tick();
		scan.tick();
		expect(scan.paused).toBe(true);
		// The first press only wakes the scanner.
		expect(scan.activate()).toBeNull();
		expect(scan.highlight).toBe(6);
	});
});

describe("TwoSwitchState", () => {
	it("reaches any key in three presses", () => {
		const two = new TwoSwitchState(ALL);
		expect(two.press("green")).toBeNull();
		expect(two.current).toEqual({ green: [0, 3], red: [5, 6] });
		expect(two.press("red")).toBeNull();
		expect(two.press("red")).toBe(6);
		expect(two.current.green).toEqual([0, 3, 5, 6]);
	});

	it("needs fewer presses when keys are blank", () => {
		const two = new TwoSwitchState([1, 6]);
		expect(two.press("green")).toBe(6);
	});
});
