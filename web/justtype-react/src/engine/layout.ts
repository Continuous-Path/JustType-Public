/** Per-language key layout — the web twin of the app's `LayoutSpec` (formatVersion 1 only). */

export const AMBIG_KEYS = 6;
export const GRID_CELLS = 9;

export interface SlotGroup {
	keyNum: number;
	cell: number;
	chars: string[];
	display?: string;
}

export interface KeyLayout {
	/** Per keyNum: every character typed by that key. */
	lettersPerKey: string[];
	/** Per keyNum: the 9-cell row-major key face ("" = empty cell). */
	grids: string[][];
}

export interface LayoutSpec {
	language: string;
	optimized: KeyLayout;
	alpha: KeyLayout | null;
	slotGroups: SlotGroup[];
	/** keyNum whose key + Select opens each list function. */
	functionKeys: { symbols: number; functions: number; navigation: number };
}

export type LayoutMode = "optimized" | "alpha";

function slotLabel(g: SlotGroup): string {
	return g.display ?? g.chars.join("");
}

function validate(what: string, layout: KeyLayout, slotGroups: SlotGroup[]): void {
	const slotChars = new Map(slotGroups.map((g) => [slotLabel(g), g.chars.join("")]));
	if (layout.lettersPerKey.length !== AMBIG_KEYS || layout.grids.length !== AMBIG_KEYS) {
		throw new Error(`${what}: expected ${AMBIG_KEYS} keys`);
	}
	layout.grids.forEach((grid, k) => {
		if (grid.length !== GRID_CELLS) throw new Error(`${what}: grid ${k} has ${grid.length} cells`);
		const gridChars = grid.flatMap((cell) => [...(slotChars.get(cell) ?? cell.replace(/\n/g, ""))]).sort();
		const keyChars = [...layout.lettersPerKey[k]].sort();
		if (gridChars.join("") !== keyChars.join("")) {
			throw new Error(`${what}: grid ${k} cells do not match key '${layout.lettersPerKey[k]}'`);
		}
	});
}

/** Parses and validates a `<Lang>Layout.json` object. Throws on malformed or unsupported input. */
export function parseLayout(json: unknown): LayoutSpec {
	const o = json as Record<string, any>;
	const version = o.formatVersion ?? 1;
	// v2 (tone-keystroke languages) needs tone handling this port does not have.
	if (version !== 1) throw new Error(`layout formatVersion ${version} unsupported`);
	const slotGroups: SlotGroup[] = o.spellMode?.slotGroups ?? [];
	const optimized: KeyLayout = { lettersPerKey: o.lettersPerKey, grids: o.grids };
	const alpha: KeyLayout | null = o.alpha ? { lettersPerKey: o.alpha.lettersPerKey, grids: o.alpha.grids } : null;
	validate("optimized", optimized, slotGroups);
	if (alpha) validate("alpha", alpha, slotGroups);
	const functionKeys = { symbols: 1, functions: 3, navigation: 5, ...(o.functionKeys ?? {}) };
	const fnKeys = Object.values(functionKeys) as number[];
	if (fnKeys.some((k) => !(k >= 0 && k < AMBIG_KEYS)) || new Set(fnKeys).size !== fnKeys.length) {
		throw new Error("functionKeys: invalid key indices");
	}
	return { language: o.language ?? "", optimized, alpha, slotGroups, functionKeys };
}

/** The characters behind a grid cell: a slot group's members, or the cell's own characters. */
export function cellChars(spec: LayoutSpec, cell: string): string[] {
	const group = spec.slotGroups.find((g) => slotLabel(g) === cell);
	return group ? group.chars : [...cell.replace(/\n/g, "")];
}
