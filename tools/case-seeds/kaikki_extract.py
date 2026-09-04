#!/usr/bin/env python3
"""Extract English inventory + POS sets from Kaikki wiktextract jsonl.gz.

Output TSV: lower_word <TAB> pos1,pos2,... <TAB> surfaces (distinct casings seen)
Only entries whose surface matches ^[A-Za-z][A-Za-z'-]*$ are kept.
"""
import gzip
import json
import re
import sys
from collections import defaultdict

SRC, OUT = sys.argv[1], sys.argv[2]
WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'\-]*$")

pos_sets = defaultdict(set)
surfaces = defaultdict(set)

n = 0
with gzip.open(SRC, "rt", encoding="utf-8") as f:
    for line in f:
        n += 1
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        w = obj.get("word", "")
        pos = obj.get("pos", "")
        if not w or not pos or not WORD_RE.match(w):
            continue
        key = w.lower()
        pos_sets[key].add(pos)
        surfaces[key].add(w)

with open(OUT, "w", encoding="utf-8") as out:
    for k in sorted(pos_sets):
        out.write(f"{k}\t{','.join(sorted(pos_sets[k]))}\t{'|'.join(sorted(surfaces[k]))}\n")
print(f"{n} kaikki lines -> {len(pos_sets)} words", file=sys.stderr)
