/** Eight-way direction selection shared by joystick, mouse-joystick and swipe input. */

const CARDINAL_WIDTH_DEG = 40;
// Slot for each 45° sector, counter-clockwise from east: E, NE, N, NW, W, SW, S, SE.
const SECTOR_SLOTS = [4, 2, 1, 0, 3, 5, 6, 7];

/**
 * The key slot in the direction of (dx, dy), screen coordinates (y grows downward).
 * Cardinals span 40° and diagonals 50°, matching the app's joystick tuning.
 */
export function directionToSlot(dx: number, dy: number, cardinalWidthDeg = CARDINAL_WIDTH_DEG): number {
	const angle = ((Math.atan2(-dy, dx) * 180) / Math.PI + 360) % 360;
	const nearestCardinal = Math.round(angle / 90) * 90;
	if (Math.abs(angle - nearestCardinal) <= cardinalWidthDeg / 2) {
		return SECTOR_SLOTS[((nearestCardinal / 90) % 4) * 2];
	}
	return SECTOR_SLOTS[Math.floor(angle / 90) * 2 + 1];
}

export interface ZoneConfig {
	/** Magnitude (0..1) inside which the stick counts as at rest. */
	restZone: number;
	/** Magnitude (0..1) beyond which a direction is chosen. */
	activeZone: number;
}

export const DEFAULT_ZONES: ZoneConfig = { restZone: 0.25, activeZone: 0.6 };

export interface ZoneUpdate {
	/** The slot currently pointed at, or null at rest. */
	highlight: number | null;
	/** The slot to press on this update, or null. */
	fire: number | null;
}

/**
 * Rest → active → rest selection: pushing past the active zone highlights a key, and
 * returning to rest presses it. Between the zones the last highlight is held, so a
 * wavering stick neither fires early nor loses its choice.
 */
export class ZoneSelector {
	private highlight: number | null = null;

	constructor(
		private readonly isValid: (slot: number) => boolean = () => true,
		private readonly zones: ZoneConfig = DEFAULT_ZONES,
	) {}

	/** `x`, `y` are normalised so that magnitude 1 is full deflection. */
	update(x: number, y: number): ZoneUpdate {
		const magnitude = Math.hypot(x, y);
		if (magnitude >= this.zones.activeZone) {
			const slot = directionToSlot(x, y);
			this.highlight = this.isValid(slot) ? slot : null;
			return { highlight: this.highlight, fire: null };
		}
		if (magnitude <= this.zones.restZone) {
			const fire = this.highlight;
			this.highlight = null;
			return { highlight: null, fire };
		}
		return { highlight: this.highlight, fire: null };
	}

	/** Drops any pending choice without pressing it. */
	cancel(): void {
		this.highlight = null;
	}
}
