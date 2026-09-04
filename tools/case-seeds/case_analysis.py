#!/usr/bin/env python3
"""Mid-sentence capitalization analysis for Spanish (Leipzig spa_news_2024_1M).

Counts, per lowercase word key, MID-SENTENCE Title-case vs lowercase occurrences.
Skips: sentence-initial tokens; tokens after sentence-final punctuation .!?…:
(optionally followed by closing quotes/brackets); tokens directly preceded by
opening quotes/inverted marks/dashes (they behave sentence-initially in news
prose: dialogue, headlines-in-quotes). ALL-CAPS and mixed-case tokens ignored.

Output: TSV  word<TAB>lower<TAB>title
"""
import re
import sys
from collections import defaultdict

SENT_FILE = sys.argv[1]
OUT_FILE = sys.argv[2]

WORD_RE = re.compile(r"^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+$")
# token ends a "sentence context": .!?…: possibly followed by closing quotes/parens
END_RE = re.compile(r"[.!?…:][\"'»”’)\]]*$")
# leading chars that make the following word sentence-initial-like
LEAD_STRIP = "\"'«“‘(¿¡—–ー﹣-*•"

lower = defaultdict(int)
title = defaultdict(int)

with open(SENT_FILE, encoding="utf-8") as f:
    for line in f:
        try:
            _, sent = line.rstrip("\n").split("\t", 1)
        except ValueError:
            continue
        skip_next = True  # sentence-initial
        for raw in sent.split():
            suspect = skip_next
            skip_next = bool(END_RE.search(raw))
            core = raw.strip(".,;:!?\"'«»“”‘’()[]…%—–ー")
            # opening quote/inverted mark/dash directly attached => positional capital risk
            if raw and raw[0] in LEAD_STRIP:
                suspect = True
            if suspect:
                continue
            if not core or not WORD_RE.match(core):
                continue
            if core.islower():
                lower[core] += 1
            elif core[0].isupper() and core[1:].islower():
                title[core.lower()] += 1
            # ALL-CAPS / mixed: ignore

with open(OUT_FILE, "w", encoding="utf-8") as out:
    keys = set(lower) | set(title)
    for k in sorted(keys):
        out.write(f"{k}\t{lower[k]}\t{title[k]}\n")
print(f"{len(keys)} distinct keys", file=sys.stderr)
