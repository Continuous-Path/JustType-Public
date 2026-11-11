import { describe, expect, it } from "vitest";
import { MoveMode, TextBuffer } from "../src/engine/buffer";

describe("TextBuffer", () => {
	it("moves by hard line, keeping the column", () => {
		const b = new TextBuffer("abcdef\nxy\nlonger line");
		b.moveTo(4);
		b.move("down", MoveMode.CharacterLine);
		expect(b.cursor).toBe(9);
		b.move("down", MoveMode.CharacterLine);
		expect(b.cursor).toBe(12);
		b.move("up", MoveMode.CharacterLine);
		b.move("up", MoveMode.CharacterLine);
		expect(b.cursor).toBe(2);
		b.move("up", MoveMode.CharacterLine);
		expect(b.cursor).toBe(0);
	});

	it("moves by paragraph", () => {
		const b = new TextBuffer("one\ntwo\nthree");
		b.move("left", MoveMode.ParagraphAll);
		expect(b.cursor).toBe(8);
		b.move("left", MoveMode.ParagraphAll);
		expect(b.cursor).toBe(4);
		b.move("right", MoveMode.ParagraphAll);
		expect(b.cursor).toBe(8);
	});

	it("extends and replaces a selection", () => {
		const b = new TextBuffer("hello world");
		b.move("left", MoveMode.WordSentence, true);
		expect(b.selectedText()).toBe("world");
		b.insert("there");
		expect(b.text).toBe("hello there");
		expect(b.selection()).toBeNull();
	});

	it("finds the word and sentence at the cursor", () => {
		const b = new TextBuffer("Hi there. How are you? Fine");
		b.moveTo(15);
		expect(b.wordRangeAtCursor()).toEqual([14, 17]);
		expect(b.sentenceRangeAtCursor()).toEqual([10, 22]);
		b.moveTo(9);
		expect(b.sentenceRangeAtCursor()).toEqual([0, 9]);
	});

	it("handles characters outside the BMP", () => {
		const b = new TextBuffer("ok 😀");
		b.move("left", MoveMode.CharacterLine);
		expect(b.cursor).toBe(3);
		b.moveTo(5);
		b.backspace();
		expect(b.text).toBe("ok ");
	});
});
