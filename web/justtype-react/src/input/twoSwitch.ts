/** Two-switch selection: each press keeps one half of the remaining keys (4/4 → 2/2 → 1/1). */

export type SwitchSide = "green" | "red";

/** Starting halves from the app: green is the left side plus Select, red the right plus UnDo. */
const START_GREEN = [0, 3, 5, 6];
const START_RED = [1, 2, 4, 7];

export interface TwoSwitchGroups {
	green: number[];
	red: number[];
}

export class TwoSwitchState {
	private groups: TwoSwitchGroups;

	constructor(private validSlots: number[]) {
		this.groups = this.start();
	}

	private start(): TwoSwitchGroups {
		const usable = (slots: number[]) => slots.filter((s) => this.validSlots.includes(s));
		return { green: usable(START_GREEN), red: usable(START_RED) };
	}

	/** The keys each switch currently stands for. */
	get current(): TwoSwitchGroups {
		return this.groups;
	}

	/** Call when the usable keys change (a new page) or a selection times out. */
	reset(validSlots: number[] = this.validSlots): void {
		this.validSlots = validSlots;
		this.groups = this.start();
	}

	/** Returns the slot to press once the choice is down to one key, else null. */
	press(side: SwitchSide): number | null {
		const chosen = this.groups[side];
		if (chosen.length === 0) return null;
		if (chosen.length === 1) {
			this.groups = this.start();
			return chosen[0];
		}
		const half = Math.ceil(chosen.length / 2);
		this.groups = { green: chosen.slice(0, half), red: chosen.slice(half) };
		return null;
	}
}
