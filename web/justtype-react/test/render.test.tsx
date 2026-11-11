import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { JustTypeKeyboard } from "../src/JustTypeKeyboard";
import { Typist, english } from "./helpers";

describe("JustTypeKeyboard", () => {
	it("renders eight labelled keys, the word list and the message", () => {
		const { engine } = new Typist({ autoCapitalize: false }).word("the").keys("qu");
		const html = renderToStaticMarkup(<JustTypeKeyboard engine={engine} inputMethod="twoSwitch" />);
		expect(html.match(/class="jt-key"/g)?.length).toBe(8);
		expect(html).toContain('aria-label="SELECT"');
		expect(html).toContain('aria-label="T R - &#x27; P #, then Select for SYM"');
		expect(html).toContain('data-group="green"');
		expect(html).toContain('<span class="jt-composing"> by</span>');
		expect(html).toMatch(/<li class="jt-item[^"]*">by<\/li>/);
	});

	it("builds its own engine from language data", () => {
		const html = renderToStaticMarkup(<JustTypeKeyboard data={english} showText={false} />);
		expect(html).not.toContain("jt-text");
		expect(html).toContain("jt-grid");
	});
});
