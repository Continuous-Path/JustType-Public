#!/usr/bin/env python3
"""Generalized mid-sentence capitalization analysis (mirrors repo case_analysis.py).

Usage: case_analysis_gen.py <lang: es|vi> <sentences.txt> <case_counts.tsv> [<totals.tsv>]

Identical skip logic to tools/case-seeds/case_analysis.py; adds:
- language-specific word regex (vi covers full Vietnamese alphabet)
- NFC normalization of tokens
- vi: folds modern tone placement (hoà/thuý) to traditional (hòa/thúy) so keys
  match the list's normalized spellings
- optional totals.tsv: case-folded total token counts (for tail attestation)
"""
import re
import sys
import unicodedata
from collections import defaultdict

LANG, SENT_FILE, OUT_FILE = sys.argv[1], sys.argv[2], sys.argv[3]
TOTALS_FILE = sys.argv[4] if len(sys.argv) > 4 else None

if LANG == "es":
    WORD_RE = re.compile(r"^[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]+$")
else:  # vi — any Latin letter incl. Vietnamese precomposed range
    WORD_RE = re.compile(r"^[A-Za-zÀ-ỹĐđ]+$")

END_RE = re.compile(r"[.!?…:][\"'»”’)\]]*$")
LEAD_STRIP = "\"'«“‘(¿¡—–ー﹣-*•"
STRIP_CHARS = ".,;:!?\"'«»“”‘’()[]…%—–ー"

TONES = {"̀", "́", "̃", "̉", "̣"}
FIRST_V = {"o", "u", "O", "U"}
SECOND_V = {"a", "e", "y", "A", "E", "Y"}

def fold_tone_vi(word):
    """Move word-final oa/oe/uy tone from 2nd vowel to 1st (modern -> traditional)."""
    d = unicodedata.normalize("NFD", word)
    chars = list(d)
    for i, c in enumerate(chars):
        if c in TONES and i >= 2:
            base = chars[i - 1]
            prev = chars[i - 2]
            rest = chars[i + 1:]
            if (
                base in SECOND_V
                and prev in FIRST_V
                and all(ch in TONES for ch in rest)
                # "qu" is an onset, not a vowel cluster: tone stays on the 2nd vowel
                and not (prev in "uU" and i >= 3 and chars[i - 3] in "qQ")
            ):
                # move tone onto the preceding vowel
                del chars[i]
                chars.insert(i - 1, c)
                break
    return unicodedata.normalize("NFC", "".join(chars))

lower = defaultdict(int)
title = defaultdict(int)
totals = defaultdict(int)

with open(SENT_FILE, encoding="utf-8") as f:
    for line in f:
        try:
            _, sent = line.rstrip("\n").split("\t", 1)
        except ValueError:
            continue
        skip_next = True
        for raw in sent.split():
            suspect = skip_next
            skip_next = bool(END_RE.search(raw))
            core = raw.strip(STRIP_CHARS)
            if raw and raw[0] in LEAD_STRIP:
                suspect = True
            if not core or not WORD_RE.match(core):
                continue
            core = unicodedata.normalize("NFC", core)
            if LANG == "vi":
                core = fold_tone_vi(core)
            if TOTALS_FILE:
                totals[core.lower()] += 1
            if suspect:
                continue
            if core.islower():
                lower[core] += 1
            elif core[0].isupper() and core[1:].islower():
                title[core.lower()] += 1

with open(OUT_FILE, "w", encoding="utf-8") as out:
    keys = set(lower) | set(title)
    for k in sorted(keys):
        out.write(f"{k}\t{lower[k]}\t{title[k]}\n")
print(f"{len(keys)} distinct mid-sentence keys", file=sys.stderr)

if TOTALS_FILE:
    with open(TOTALS_FILE, "w", encoding="utf-8") as out:
        for k, v in sorted(totals.items(), key=lambda kv: -kv[1]):
            out.write(f"{k}\t{v}\n")
    print(f"{len(totals)} case-folded total keys", file=sys.stderr)
