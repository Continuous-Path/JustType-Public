/** Single-switch scanning: keys are highlighted in turn; the switch presses the highlighted one. */

/** Scan orders from the app: Select first, UnDo last. */
export const SCAN_ORDER_OPTIMIZED = [6, 7, 0, 3, 2, 5, 4, 1];
export const SCAN_ORDER_ALPHA = [6, 0, 3, 5, 2, 4, 7, 1];

export interface ScanConfig {
	order: number[];
	/** Full passes without a switch press before scanning pauses (0 = never pause). */
	maxPasses: number;
}

export class ScanState {
	private index = 0;
	private passes = 0;
	private running = true;
	private valid: number[];

	constructor(
		private readonly config: ScanConfig,
		validSlots: number[],
	) {
		this.valid = this.filter(validSlots);
	}

	private filter(validSlots: number[]): number[] {
		return this.config.order.filter((slot) => validSlots.includes(slot));
	}

	/** The highlighted slot, or null while paused or when no key is usable. */
	get highlight(): number | null {
		return this.running ? (this.valid[this.index] ?? null) : null;
	}

	get paused(): boolean {
		return !this.running;
	}

	/** Call when the usable keys change (a new page); scanning restarts from the top. */
	setValidSlots(validSlots: number[]): void {
		this.valid = this.filter(validSlots);
		this.restart();
	}

	/** Advances the highlight; call once per scan interval. */
	tick(): void {
		if (!this.running || this.valid.length === 0) return;
		this.index += 1;
		if (this.index >= this.valid.length) {
			this.index = 0;
			this.passes += 1;
			if (this.config.maxPasses > 0 && this.passes >= this.config.maxPasses) this.running = false;
		}
	}

	/** The switch was activated: returns the slot to press, or null if it only resumed scanning. */
	activate(): number | null {
		if (!this.running) {
			this.restart();
			return null;
		}
		const slot = this.highlight;
		this.restart();
		return slot;
	}

	private restart(): void {
		this.index = 0;
		this.passes = 0;
		this.running = true;
	}
}
