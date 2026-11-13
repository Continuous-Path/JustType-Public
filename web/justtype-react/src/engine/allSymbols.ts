/** ALL SYMBOLS MODE: a browsable tree of symbol categories, six entries per page. */

export type SymbolNode = { k: "leaf"; char: string } | { k: "branch"; label: string; children: SymbolNode[] };

export interface SymbolData {
	format: number;
	symbol_hierarchy: SymbolNode[];
}

export type SymbolSlot =
	| { kind: "leaf"; char: string }
	| { kind: "branch"; label: string; preview: string[]; index: number };

export const CELLS_PER_PAGE = 6;
/** Key-face cells used to preview a branch's first page. */
const PREVIEW_CELLS = [0, 2, 3, 5, 6, 8];
const ROOT_NAME = "ALL SYMBOLS";

type Branch = Extract<SymbolNode, { k: "branch" }>;

function preview(branch: Branch): string[] {
	const cells = new Array(9).fill("");
	branch.children.slice(0, CELLS_PER_PAGE).forEach((child, i) => {
		cells[PREVIEW_CELLS[i]] = child.k === "leaf" ? child.char : (firstLeaf(child) ?? "");
	});
	return cells;
}

function firstLeaf(node: SymbolNode): string | undefined {
	if (node.k === "leaf") return node.char;
	for (const child of node.children) {
		const found = firstLeaf(child);
		if (found) return found;
	}
	return undefined;
}

export class AllSymbolsController {
	private readonly root: Branch;
	private stack: { node: Branch; page: number }[] = [];
	private current: { node: Branch; page: number };

	constructor(
		data: SymbolData,
		/** Stay in the browser after a pick (repeated-symbols entry). */
		readonly multi: boolean,
	) {
		this.root = { k: "branch", label: ROOT_NAME, children: data.symbol_hierarchy };
		this.current = { node: this.root, page: 0 };
	}

	get atRoot(): boolean {
		return this.stack.length === 0;
	}

	get setName(): string {
		return this.current.node.label;
	}

	get pageIndex(): number {
		return this.current.page;
	}

	get pageCount(): number {
		return Math.max(1, Math.ceil(this.current.node.children.length / CELLS_PER_PAGE));
	}

	/** The entries on the current page (fewer than six on a last page). */
	slots(): SymbolSlot[] {
		const start = this.current.page * CELLS_PER_PAGE;
		return this.current.node.children.slice(start, start + CELLS_PER_PAGE).map((child, i) =>
			child.k === "leaf"
				? { kind: "leaf", char: child.char }
				: { kind: "branch", label: child.label, preview: preview(child), index: start + i },
		);
	}

	/** Next page, wrapping to the first. */
	more(): void {
		this.current.page = (this.current.page + 1) % this.pageCount;
	}

	descend(index: number): boolean {
		const target = this.current.node.children[index];
		if (target?.k !== "branch") return false;
		this.stack.push(this.current);
		this.current = { node: target, page: 0 };
		return true;
	}

	/** Up one level; false at the root. */
	ascend(): boolean {
		const parent = this.stack.pop();
		if (!parent) return false;
		this.current = parent;
		return true;
	}

	reset(): void {
		this.stack = [];
		this.current = { node: this.root, page: 0 };
	}
}
