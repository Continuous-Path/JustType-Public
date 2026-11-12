#!/usr/bin/env node
// Compiles the Android app's language sources (app/src/main/db) into the compact
// files the web engine loads: data/<lang>.json (layout + words + diacritics),
// data/<lang>.ngrams.json (pruned next-word table) and data/symbols.json (ALL SYMBOLS tree).
//
// Usage: node scripts/build-data.mjs [English|Espanol] [--contexts 12000] [--followers 8]
import { createReadStream, mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { createInterface } from "node:readline";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const dbDir = join(here, "../../../app/src/main/db");
const hierarchyDir = join(here, "../../../app/src/main/assets/hierarchies");
const outDir = join(here, "../data");

const args = process.argv.slice(2);
const flag = (name, def) => {
	const i = args.indexOf(`--${name}`);
	return i >= 0 ? Number(args[i + 1]) : def;
};
const lang = args.find((a, i) => !a.startsWith("--") && !args[i - 1]?.startsWith("--")) ?? "English";
const maxContexts = flag("contexts", 12000);
const maxFollowers = flag("followers", 8);

/** word -> { drop, level (0|1|2), caseType? } from <Lang>WordsAvoid.txt. */
function loadAvoid() {
	const path = join(dbDir, `${lang}WordsAvoid.txt`);
	const out = new Map();
	if (!existsSync(path)) return out;
	for (const raw of readFileSync(path, "utf8").split("\n")) {
		const line = raw.trim();
		if (!line || line.startsWith("#")) continue;
		const [word, rest = ""] = line.split(";");
		const [tier = "", ...markerParts] = rest.trim().split(/\s+/);
		const marker = markerParts.join("");
		const excluded = marker.includes("slur") || /(^|[^A-Z])X/.test(marker);
		let level;
		if (excluded) level = -1;
		else if (marker.includes("0")) level = 0;
		else if (marker.includes("2")) level = 2;
		else level = tier === "slur" ? -1 : 1;
		const caseType = marker.includes("ACRONYM") ? "AC" : marker.includes("PN") ? "TO" : undefined;
		out.set(word.trim().toLowerCase(), { drop: level < 0, level: Math.max(level, 0), caseType });
	}
	return out;
}

function buildWords(avoid) {
	const lines = readFileSync(join(dbDir, `${lang}WordsRaw.txt`), "utf8").split("\n");
	const rows = [];
	const vocab = new Map(); // lower -> { freq, level }
	for (const raw of lines) {
		const line = raw.replace(/^﻿/, "").trim();
		if (!line || line.startsWith("#")) continue;
		const fields = line.split(";");
		const freq = Number.parseInt(fields[1], 10);
		if (fields.length < 2 || !Number.isFinite(freq)) continue;
		// Field 4 is an optional output form; later fields (region tags) are not used here.
		let surface = fields[3] || fields[0];
		const lower = surface.toLowerCase();
		const graded = avoid.get(lower);
		if (graded?.drop) continue;
		let caseType = (fields[2] ?? "").split(",")[0] || "LO";
		if (graded?.caseType === "TO") {
			caseType = "TO";
			surface = lower[0].toUpperCase() + lower.slice(1);
		} else if (graded?.caseType === "AC") {
			caseType = "AC";
			surface = lower.toUpperCase();
		}
		const level = graded?.level ?? 0;
		rows.push(level ? `${surface}\t${freq}\t${caseType}\t${level}` : `${surface}\t${freq}\t${caseType}`);
		const prev = vocab.get(lower);
		if (!prev || prev.freq < freq) vocab.set(lower, { freq, level });
	}
	return { rows, vocab };
}

async function buildNgrams(vocab) {
	const path = join(dbDir, `${lang}Ngb.txt`);
	if (!existsSync(path)) return null;
	const allowedContexts = new Set(
		[...vocab.entries()]
			.sort((a, b) => b[1].freq - a[1].freq)
			.slice(0, maxContexts)
			.map(([w]) => w),
	);
	allowedContexts.add("\\n"); // sentence start
	const rows = [];
	const rl = createInterface({ input: createReadStream(path, "utf8"), crlfDelay: Infinity });
	for await (const line of rl) {
		const tab = line.indexOf("\t");
		if (tab <= 0) continue;
		const ctx = line.slice(0, tab);
		if (!allowedContexts.has(ctx)) continue;
		const kept = [];
		for (const part of line.slice(tab + 1).split("|")) {
			const colon = part.lastIndexOf(":");
			if (colon <= 0) continue;
			const target = part.slice(0, colon);
			// Single in-vocabulary words only; graded words never get predicted.
			const known = vocab.get(target);
			if (!known || known.level > 0) continue;
			kept.push(part);
			if (kept.length >= maxFollowers) break;
		}
		if (kept.length) rows.push(`${ctx}\t${kept.join("|")}`);
	}
	return rows;
}

/** Base letter -> its diacritic variants, for typing accented words on base-letter keys. */
function loadDiacritics() {
	const tree = JSON.parse(readFileSync(join(hierarchyDir, "character_hierarchy.json"), "utf8"));
	const out = {};
	for (const group of tree.diacritic_groups) {
		// Single code units only: the engine maps one character to one key.
		const variants = group.variants.map((v) => v.char).filter((c) => c.length === 1);
		if (group.base.length === 1 && variants.length) out[group.base] = variants.join("");
	}
	return out;
}

/** The symbol tree without its advisory fields. */
function loadSymbols() {
	const strip = (node) =>
		node.k === "leaf" ? { k: "leaf", char: node.char } : { k: "branch", label: node.label, children: node.children.map(strip) };
	const tree = JSON.parse(readFileSync(join(hierarchyDir, "symbol_hierarchy.json"), "utf8"));
	return { format: 1, symbol_hierarchy: tree.symbol_hierarchy.map(strip) };
}

const avoid = loadAvoid();
const { rows, vocab } = buildWords(avoid);
const layout = JSON.parse(readFileSync(join(dbDir, `${lang}Layout.json`), "utf8"));
delete layout.metric;
delete layout.generator;

mkdirSync(outDir, { recursive: true });
const id = lang.toLowerCase();
const wordsPath = join(outDir, `${id}.json`);
writeFileSync(
	wordsPath,
	JSON.stringify({ format: 1, language: id, layout, diacritics: loadDiacritics(), words: rows.join("\n") }),
);
console.log(`${wordsPath}: ${rows.length} words`);

writeFileSync(join(outDir, "symbols.json"), JSON.stringify(loadSymbols()));

const ngramRows = await buildNgrams(vocab);
if (ngramRows) {
	const ngramPath = join(outDir, `${id}.ngrams.json`);
	writeFileSync(ngramPath, JSON.stringify({ format: 1, language: id, rows: ngramRows.join("\n") }));
	console.log(`${ngramPath}: ${ngramRows.length} contexts`);
}
