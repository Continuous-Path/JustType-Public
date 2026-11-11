import { describe, expect, it } from "vitest";
import type { PersistedState } from "../src/engine/store";
import { LETTER_SLOTS, SELECT, Typist, UNDO, englishNgrams, espanol, symbols } from "./helpers";

const plain = { autoCapitalize: false } as const;

describe("word entry", () => {
	it("previews the top candidate and commits a selected word on the next key", () => {
		const t = new Typist().keys("the");
		expect(t.snap.composing).toBe("The");
		expect(t.snap.text).toBe("");
		t.press(SELECT);
		expect(t.snap.selection).toBe(0);
		t.keys("a");
		expect(t.snap.text).toBe("The");
		expect(t.snap.displayText).toBe("The a");
	});

	it("spaces words automatically and capitalises only the sentence start", () => {
		const t = new Typist().word("Hello").word("world").keys("a");
		expect(t.snap.text).toBe("Hello world");
	});

	it("lists every fully-typed word before trimming completions", () => {
		const t = new Typist(plain).keys("he");
		expect(t.outputs).toContain("he");
		expect(t.outputs.length).toBeGreaterThan(1);
	});

	it("refuses a key that no word continues with", () => {
		const t = new Typist(plain);
		for (let i = 0; i < 30; i++) t.press(5);
		expect(t.errors.length).toBeGreaterThan(0);
		expect(t.snap.list.length).toBeGreaterThan(0);
	});

	it("offers stored case forms", () => {
		expect(new Typist(plain).keys("i").snap.list[0]).toMatchObject({ output: "I" });
	});

	it("withholds graded words unless asked", () => {
		expect(new Typist(plain).keys("arse").outputs).not.toContain("arse");
		expect(new Typist({ ...plain, excludedWords: "none" }).keys("arse").outputs).toContain("arse");
	});
});

describe("undo", () => {
	it("steps back through selects and keys", () => {
		const t = new Typist(plain).keys("he").press(SELECT);
		t.press(UNDO);
		expect(t.snap.selection).toBeNull();
		expect(t.snap.keySequence.length).toBe(2);
		t.press(UNDO, UNDO);
		expect(t.snap.keySequence.length).toBe(0);
	});

	it("deletes committed text when pull-in is off", () => {
		const t = new Typist({ ...plain, pullIn: false }).word("the").keys("a").press(UNDO, UNDO);
		expect(t.snap.text).toBe("th");
	});

	it("reports an error with nothing to undo", () => {
		expect(new Typist().press(UNDO).errors.length).toBe(1);
	});
});

describe("paged selection", () => {
	it("pages the rest of the list onto the letter keys after the listed words", () => {
		const t = new Typist(plain).keys("th").press(SELECT, SELECT);
		expect(t.snap.selection).toBe(1);
		t.press(SELECT);
		expect(t.snap.pagedRows).toEqual([2, 8]);
		// Column-major: the left column holds the first three rows.
		expect(t.snap.keys[0]).toMatchObject({ kind: "word", label: t.snap.list[2].display });
		expect(t.snap.keys[3]).toMatchObject({ kind: "word", label: t.snap.list[3].display });
		expect(t.snap.keys[2]).toMatchObject({ kind: "word", label: t.snap.list[5].display });
		const picked = t.outputs[3];
		t.press(3);
		expect(t.snap.text).toBe(picked);
		expect(t.snap.keySequence).toEqual([]);
		expect(t.snap.pagedPage).toBeNull();
	});

	it("advances pages with Select and backs out with UnDo", () => {
		const t = new Typist(plain).keys("th").press(SELECT, SELECT, SELECT, SELECT);
		expect(t.snap.pagedPage).toBe(1);
		t.press(UNDO);
		expect(t.snap.pagedPage).toBe(0);
		t.press(UNDO);
		expect(t.snap.pagedPage).toBeNull();
		expect(t.snap.selection).toBe(1);
	});

	it("hands the first page cell to the first key scanned", () => {
		const t = new Typist(plain);
		t.engine.setScanOrder(true);
		t.keys("th").press(SELECT, SELECT, SELECT);
		// Scan order reaches slot 7 first among the letter keys.
		expect(t.snap.keys[7]).toMatchObject({ kind: "word", label: t.snap.list[2].display });
	});

	it("steps through the whole list in list mode", () => {
		const t = new Typist({ ...plain, wordSelection: "list" }).keys("th").press(SELECT, SELECT, SELECT);
		expect(t.snap.selection).toBe(2);
		expect(t.snap.pagedPage).toBeNull();
	});
});

describe("next-letter hints", () => {
	it("marks the letters some word continues with", () => {
		const t = new Typist({ ...plain, nextLetterHints: true });
		expect(t.snap.keys[0].hints).toBeUndefined();
		t.keys("xylophon");
		const hinted = t.snap.keys.flatMap((k) => (k.grid ?? []).filter((cell, i) => cell && k.hints?.[i]));
		expect(hinted).toContain("E");
		expect(hinted).not.toContain("Q");
		// A selection ends the sequence, so the hints clear.
		expect(t.press(SELECT).snap.keys[0].hints).toBeUndefined();
	});
});

describe("word families", () => {
	const reachExpandable = (t: Typist) => {
		const index = t.snap.list.findIndex((i) => i.type === "word" && i.expandable);
		if (index < 0) throw new Error("no expandable row");
		while (t.snap.selection !== index) t.press(SELECT);
		return index;
	};

	it("inserts same-stem words after the paused-on word", () => {
		const t = new Typist({ ...plain, familyExpand: true, wordSelection: "list" }).keys("inter");
		const index = reachExpandable(t);
		const anchor = t.outputs[index];
		const before = t.snap.list.length;
		expect(t.engine.expandFamilyNow()).toBe(true);
		const family = t.snap.list.filter((i) => i.type === "word" && i.family);
		expect(t.snap.list.length).toBe(before + family.length);
		expect(family.length).toBeGreaterThan(0);
		expect(t.snap.list[index + 1]).toMatchObject({ family: true });
		// Every member agrees with the keys already typed.
		for (const member of family) expect(member.display.slice(0, 5).toLowerCase()).toBe(anchor.slice(0, 5).toLowerCase());
		expect(t.engine.expandFamilyNow()).toBe(false);
	});

	it("keeps the group on whole pages in paged mode", () => {
		const t = new Typist({ ...plain, familyExpand: true, pagedListedWords: 3 }).keys("inter");
		const index = t.snap.list.findIndex((i) => i.type === "word" && i.expandable);
		expect(index).toBeGreaterThanOrEqual(0);
		expect(index).toBeLessThan(3);
		while (t.snap.selection !== index) t.press(SELECT);
		t.engine.expandFamilyNow();
		const group = t.snap.list.filter((i) => i.type === "pad" || (i.type === "word" && i.family));
		expect(group.length % 6).toBe(0);
		t.press(SELECT);
		expect(t.snap.pagedRows?.[0]).toBe(index + 1);
	});

	it("is off by default", () => {
		const t = new Typist(plain).keys("und");
		expect(t.snap.list.some((i) => i.type === "word" && i.expandable)).toBe(false);
	});
});

describe("phrases", () => {
	it("offers a stored phrase for its abbreviation", () => {
		const t = new Typist(plain);
		expect(t.engine.addPhrase("omw", "On my way!")).not.toBeNull();
		t.keys("omw");
		expect(t.snap.list.find((i) => i.type === "phrase")).toMatchObject({ display: "omw - On my way!" });
		t.choose("On my way!").keys("a");
		expect(t.snap.text).toBe("On my way!");
		t.engine.removePhrase(t.engine.getPhrases()[0].id);
		expect(t.engine.getPhrases()).toEqual([]);
	});

	it("records a new phrase from the keyboard and restores the message", () => {
		let saved: PersistedState | null = null;
		const store = { load: () => saved, save: (s: PersistedState) => (saved = structuredClone(s)) };
		const t = new Typist({ store, initialText: "Dear all" });
		t.open("MENU").fn("ADD NEW PHRASE");
		expect(t.snap.phraseFlow).toMatchObject({ phase: "phrase" });
		expect(t.snap.text).toBe("");
		t.word("Thank").word("you").open("FNS");
		expect(t.snap.page).toBe("Functions0");
		t.fn("DONE WITH PHRASE");
		expect(t.snap.phraseFlow).toEqual({ phase: "abbreviation", phrase: "Thank you" });
		t.spell("ty").fn("<DONE>");
		expect(t.snap.phraseFlow).toBeNull();
		expect(t.snap.text).toBe("Dear all");
		expect(saved!.phrases).toMatchObject([{ abbreviation: "ty", phrase: "Thank you" }]);
		expect(t.keys("ty").outputs).toContain("Thank you");
	});

	it("cancels a phrase without saving", () => {
		const t = new Typist({ initialText: "Dear all" });
		t.open("MENU").fn("ADD NEW PHRASE").word("Thank").open("FNS").fn("CANCEL PHRASE");
		expect(t.snap.text).toBe("Dear all");
		expect(t.engine.getPhrases()).toEqual([]);
	});
});

describe("pull-in", () => {
	it("reopens the word before the cursor on UnDo, minus its Select", () => {
		const t = new Typist(plain).word("the").word("cat").keys("a").press(UNDO);
		expect(t.snap.text).toBe("the cat");
		t.press(UNDO);
		expect(t.snap.text).toBe("the ");
		expect(t.snap.composing).toBe("cat");
		expect(t.snap.keySequence).toEqual(t.engine.keySequenceFor("cat"));
		t.press(UNDO);
		expect(t.snap.keySequence.length).toBe(2);
	});

	it("reloads a tapped word, selected, and puts it back in place", () => {
		const t = new Typist({ ...plain, initialText: "the cat sat" });
		expect(t.engine.pullInAt(5)).toBe(true);
		expect(t.snap.composing).toBe("cat");
		expect(t.snap.selection).not.toBeNull();
		expect(t.snap.displayText).toBe("the cat sat");
		while (t.snap.keySequence.length > 2) t.press(UNDO);
		t.keys("r").choose("car");
		t.engine.setCursor(999);
		expect(t.snap.text).toBe("the car sat");
	});

	it("keeps a capitalised word capitalised", () => {
		const t = new Typist({ initialText: "Hello" });
		expect(t.engine.pullInAt(5)).toBe(true);
		expect(t.snap.composing).toBe("Hello");
		expect(t.snap.shift).toBe(true);
	});

	it("leaves unknown words alone", () => {
		const t = new Typist({ ...plain, initialText: "zzxqj" });
		expect(t.engine.pullInAt(5)).toBe(false);
		expect(t.snap.text).toBe("zzxqj");
	});
});

describe("pages", () => {
	it("ends a sentence snugly and re-arms auto-capitalisation", () => {
		const t = new Typist().word("Hello").open("SYM");
		expect(t.snap.page).toBe("Symbols1");
		t.fn(".");
		expect(t.snap.text).toBe("Hello.");
		expect(t.snap.page).toBe("Main");
		expect(t.snap.shift).toBe(true);
		t.word("The").keys("a");
		expect(t.snap.text).toBe("Hello. The");
	});

	it("pairs quotation marks", () => {
		const quote = (t: Typist) => t.open("SYM").fn("SYMBOL PG. 2").fn("SYMBOL PG. 3").fn('"');
		const t = new Typist(plain).word("say");
		quote(t).word("hello");
		quote(t).word("now").keys("a");
		expect(t.snap.text).toBe('say "hello" now');
	});

	it("keeps the repeated-symbols page open", () => {
		const t = new Typist(plain).word("wow").open("SYM").fn("TYPE REPEATED SYMBOLS").fn("!").fn("!");
		expect(t.snap.text).toBe("wow!!");
		expect(t.snap.page).toBe("SymbolsMulti1");
		t.fn("BACK TO MAIN");
		expect(t.snap.page).toBe("Main");
	});

	it("types numbers as one run", () => {
		const t = new Typist(plain).word("room").open("MENU").fn("123 NUMBER MODE");
		t.fn("1").fn("2").fn("6 - 0 +-./,").fn("7").fn("1 - 5 (OR <DONE>)").fn("<DONE>");
		expect(t.snap.text).toBe("room 127");
		expect(t.snap.page).toBe("Main");
	});

	it("offers number symbols in groups", () => {
		const t = new Typist(plain).open("MENU").fn("123 NUMBER MODE").fn("5").fn("6 - 0 +-./,").fn("+-./,");
		expect(t.snap.keys[0]).toMatchObject({ kind: "letters" });
		t.press(0).fn("€");
		expect(t.snap.text).toBe("5€");
		expect(t.snap.page).toBe("Numbers2");
	});

	it("toggles caps lock from the function pages", () => {
		const t = new Typist(plain).open("FNS").fn("CAPS LOCK ON/OFF");
		expect(t.snap.capsLock).toBe(true);
		t.word("THE").keys("a");
		expect(t.snap.text).toBe("THE");
	});

	it("speaks the current sentence, the last word, and sentences in turn", () => {
		const t = new Typist().word("Hello").open("SYM").fn(".").word("Good").word("morning");
		t.open("FNS").fn("SPEAK SENTENCE");
		t.open("FNS").fn("SPEAK (LAST) SELECTION");
		t.open("FNS").fn("FUNCTION PG. 2").fn("SPEAK NEXT SENTENCE").fn("SPEAK NEXT SENTENCE");
		expect(t.spoken).toEqual(["Good morning", "morning", "Hello.", "Good morning"]);
	});

	it("adds a spelled word to the vocabulary", () => {
		const t = new Typist(plain).open("MENU").fn("ADD NEW WORD").spell("zyxq");
		expect(t.snap.composing).toBe("zyxq");
		t.fn("<DONE>");
		expect(t.snap.text).toBe("zyxq");
		t.word("zyxq").keys("a");
		expect(t.snap.text).toBe("zyxq zyxq");
	});

	it("types letters straight into the message in letter mode", () => {
		const t = new Typist(plain).word("my").open("MENU").fn("LETTER / SYMBOL MODE").fn("LETTER SPELL MODE").spell("xq");
		expect(t.snap.text).toBe("my xq");
		t.fn("<DONE>");
		expect(t.snap.page).toBe("LetterSymbol");
	});

	it("switches to the alphabetic layout", () => {
		const t = new Typist(plain);
		t.engine.setLayoutMode("alpha");
		expect(t.snap.keys[0].grid?.[0]).toBe("A");
		t.word("the").keys("a");
		expect(t.snap.text).toBe("the");
	});
});

describe("ALL SYMBOLS MODE", () => {
	const enter = (t: Typist) => t.open("SYM").fn("SYMBOL PG. 2").fn("SYMBOL PG. 3").fn("ALL SYMBOLS MODE");

	it("browses categories and inserts a symbol verbatim", () => {
		const t = enter(new Typist({ ...plain, symbols }).word("yes"));
		expect(t.snap.centerText).toBe("ALL SYMBOLS");
		expect(t.snap.keys[SELECT].label).toBe("PAGE 2");
		t.press(LETTER_SLOTS[0]);
		expect(t.snap.centerText).toBe("PUNCTUATION");
		t.fn("PAGE 2").fn("…");
		expect(t.snap.text).toBe("yes…");
		expect(t.snap.page).toBe("Main");
	});

	it("climbs back out", () => {
		const t = enter(new Typist({ ...plain, symbols })).press(LETTER_SLOTS[1]).fn("UP");
		expect(t.snap.centerText).toBe("ALL SYMBOLS");
		t.fn("BACK");
		expect(t.snap.page).toBe("Symbols3");
	});

	it("is absent without symbol data", () => {
		const t = new Typist(plain).open("SYM").fn("SYMBOL PG. 2").fn("SYMBOL PG. 3");
		expect(t.snap.keys[0].kind).toBe("blank");
	});
});

describe("edit mode", () => {
	const edit = (text: string) => new Typist({ ...plain, initialText: text }).open("MENU").fn("EDIT MODE");
	const cycle = (t: Typist) => t.press(5);

	it("moves by character, word and whole text", () => {
		const t = edit("one two. Three four").fn("←");
		expect(t.snap.cursor).toBe(18);
		cycle(t).fn("←");
		expect(t.snap.cursor).toBe(15);
		t.fn("↑");
		expect(t.snap.cursor).toBe(9);
		cycle(t).fn("↑");
		expect(t.snap.cursor).toBe(0);
		t.fn("↓");
		expect(t.snap.cursor).toBe(19);
	});

	it("selects, cuts, pastes and undoes", () => {
		const t = cycle(edit("hello brave world")).fn("SELECT TEXT").fn("←");
		expect(t.snap.textSelection).toEqual([12, 17]);
		t.press(2).fn("CUT");
		expect(t.copied).toEqual(["world"]);
		expect(t.snap.text).toBe("hello brave ");
		t.fn("BACK TO CURSOR MODE").fn("←").fn("←").press(2).fn("PASTE");
		expect(t.snap.text).toBe("worldhello brave ");
		t.fn("UNDO").fn("UNDO");
		expect(t.snap.text).toBe("hello brave world");
		t.fn("UNDO");
		expect(t.errors.length).toBe(1);
	});

	it("recases the word or sentence at the cursor", () => {
		const t = edit("hello. the BIG day").press(2).fn("ADJUST CASE MODE").fn("word ↓ WORD");
		expect(t.snap.text).toBe("hello. the BIG DAY");
		t.fn("APPLY Sentence case…");
		expect(t.snap.text).toBe("hello. The big day");
		t.fn("UNDO");
		expect(t.snap.text).toBe("hello. the BIG DAY");
	});

	it("jumps between bookmarks", () => {
		const t = edit("one two three");
		cycle(cycle(cycle(t)));
		expect(t.snap.page).toBe("EditBookmark");
		t.fn("SET MARK A").fn("BACK TO MAIN");
		t.engine.setCursor(3);
		t.open("MENU").fn("EDIT MODE");
		cycle(cycle(cycle(t))).fn("JUMP TO MARK A");
		expect(t.snap.cursor).toBe(13);
		t.fn("JUMP TO MARK B");
		expect(t.errors.length).toBe(1);
	});

	it("types in the middle of the message with spacing on both sides", () => {
		const t = new Typist({ ...plain, initialText: "the cat" });
		t.engine.setCursor(3);
		t.word("big").keys("a");
		expect(t.snap.text).toBe("the big cat");
		expect(t.snap.displayText).toBe("the big a cat");
	});
});

describe("learning", () => {
	it("persists usage and promotes a used word", () => {
		let saved: PersistedState | null = null;
		const store = { load: () => saved, save: (s: PersistedState) => (saved = structuredClone(s)) };
		const rank = (t: Typist, word: string) => t.outputs.indexOf(word);

		const first = new Typist({ ...plain, store }).keys("he");
		const rare = first.snap.list.filter((i) => i.type === "word" && i.keysRemaining === 0).at(-1);
		if (rare?.type !== "word") throw new Error("expected a fully-typed word");
		const rankBefore = rank(first, rare.output);
		first.choose(rare.output).keys("a");
		expect(saved!.usage[rare.output]?.[0]).toBe(1);

		const second = new Typist({ ...plain, store }).keys("he");
		expect(rank(second, rare.output)).toBeLessThan(rankBefore);
	});
});

describe("next-word prediction", () => {
	it("offers sentence openers before any key and followers after a word", () => {
		const t = new Typist({ ngrams: englishNgrams });
		expect(t.outputs).toContain("The");
		t.press(SELECT, SELECT);
		const picked = t.snap.composing;
		expect(picked).not.toBe("");
		t.keys("a");
		expect(t.snap.text).toBe(picked);
	});

	it("puts a matching follower ahead of plain completions", () => {
		const t = new Typist({ ...plain, ngrams: englishNgrams }).word("a").keys("lo");
		expect(t.snap.text).toBe("a");
		expect(t.snap.list[0]).toMatchObject({ output: "lot", predicted: true });
	});
});

describe("Spanish", () => {
	const spanish = { ...plain, data: espanol };

	it("places the list functions where the layout says", () => {
		const t = new Typist(spanish);
		expect(t.snap.keys[LETTER_SLOTS[2]].badge).toBe("SYM");
		expect(t.snap.keys[LETTER_SLOTS[4]].badge).toBe("FNS");
		t.open("SYM");
		expect(t.snap.page).toBe("Symbols1");
	});

	it("reaches an accented word through its base letters, ranked below the exact word", () => {
		const t = new Typist(spanish).keys("que");
		expect(t.outputs.indexOf("que")).toBe(0);
		expect(t.outputs).toContain("qué");
		expect(new Typist({ ...spanish, accentFallback: false }).keys("que").outputs).not.toContain("qué");
	});

	it("types an accented word with its own keys too", () => {
		const t = new Typist(spanish).word("qué").keys("a");
		expect(t.snap.text).toBe("qué");
	});

	it("folds letters that have no key onto their base letter", () => {
		const t = new Typist(spanish).keys("pingüino");
		expect(t.outputs).toContain("pingüino");
	});
});
