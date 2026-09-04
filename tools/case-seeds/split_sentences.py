#!/usr/bin/env python3
"""Wikiextractor output -> Leipzig-style sentences file (id<TAB>sentence).

Reads all wiki_* files under the given extraction dir (wikiextractor --json).
Splits paragraphs into sentences on terminal punctuation followed by
whitespace + an uppercase/opening char. Filters to lines that look like real
prose sentences: >=5 tokens, ends with terminal punctuation, drops the
title-only lines and list stubs. Emits id<TAB>sentence to stdout.
"""
import glob
import json
import re
import sys
import unicodedata

SRC_DIR = sys.argv[1]

# split after .!?… (+ optional closing quotes/brackets) when followed by space + sentence opener
SPLIT_RE = re.compile(r"(?<=[.!?…])[\"'»”’)\]]*\s+(?=[¿¡\"'«“‘(]*[A-ZÁÉÍÓÚÜÑÀ-Þ0-9])")
END_RE = re.compile(r"[.!?…][\"'»”’)\]]*$")
ABBREV_GUARD = re.compile(r"\b(?:Sr|Sra|Dr|Dra|Prof|etc|p\.ej|EE\.UU|a\.C|d\.C|St|Mr|Mrs|No|vs|TP|Tp)\.$")

sid = 0
for path in sorted(glob.glob(f"{SRC_DIR}/**/wiki_*", recursive=True)):
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                doc = json.loads(line)
            except json.JSONDecodeError:
                continue
            text = unicodedata.normalize("NFC", doc.get("text", ""))
            for para in text.split("\n"):
                para = para.strip()
                if len(para) < 20:
                    continue
                parts = SPLIT_RE.split(para)
                # re-join obvious abbreviation splits
                merged = []
                for p in parts:
                    if merged and ABBREV_GUARD.search(merged[-1]):
                        merged[-1] = merged[-1] + " " + p
                    else:
                        merged.append(p)
                for sent in merged:
                    sent = sent.strip()
                    if len(sent.split()) < 5 or not END_RE.search(sent):
                        continue
                    sid += 1
                    sys.stdout.write(f"{sid}\t{sent}\n")
print(f"{sid} sentences", file=sys.stderr)
