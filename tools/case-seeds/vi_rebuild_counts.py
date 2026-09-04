#!/usr/bin/env python3
"""Rebuild TiengVietWordsRaw counts from hermitdave + Wikipedia only.

Eliminates Leipzig tail-rescue and hieuthi rank-interpolated counts:
  count = scaled hermitdave count (primary, CC-BY-SA)
        | scaled Wikipedia count (fallback for hermitdave gaps, CC BY-SA)
  syllables attested in neither are pruned (reported).
Case seeds (5th field) carried over from the reseeded file. Output sorted by
new count desc. Top count scaled to ~1.2M (existing convention).

Usage: vi_rebuild_counts.py <vi_final.txt> <vi_full.txt> <totals.tsv> <out>
"""
import statistics
import sys
import unicodedata

sys.path.insert(0, "/private/tmp/claude-502/-Users-cliffkushler-Documents-GitHub-JustType1--claude-worktrees-experimental-work-tree-2a0b61/84a056d6-e8fd-4028-8ac5-731d5ad1edd8/scratchpad")

TONES = {"̀", "́", "̃", "̉", "̣"}
FIRST_V = {"o", "u", "O", "U"}
SECOND_V = {"a", "e", "y", "A", "E", "Y"}

def fold_tone_vi(word):
    d = unicodedata.normalize("NFD", word)
    chars = list(d)
    for i, c in enumerate(chars):
        if c in TONES and i >= 2:
            base, prev = chars[i - 1], chars[i - 2]
            rest = chars[i + 1:]
            if (
                base in SECOND_V and prev in FIRST_V
                and all(ch in TONES for ch in rest)
                and not (prev in "uU" and i >= 3 and chars[i - 3] in "qQ")
            ):
                del chars[i]
                chars.insert(i - 1, c)
                break
    return unicodedata.normalize("NFC", "".join(chars))

LIST_FILE, HERMIT_FILE, TOTALS_FILE, OUT = sys.argv[1:5]

hermit = {}
for line in open(HERMIT_FILE, encoding="utf-8"):
    parts = line.split()
    if len(parts) != 2:
        continue
    w, c = parts
    key = fold_tone_vi(unicodedata.normalize("NFC", w)).lower()
    hermit[key] = hermit.get(key, 0) + int(c)

wiki = {}
for line in open(TOTALS_FILE, encoding="utf-8"):
    k, v = line.rstrip("\n").split("\t")
    wiki[k] = int(v)

entries = []  # (syllable, seed)
for line in open(LIST_FILE, encoding="utf-8"):
    line = line.rstrip("\n")
    if line.startswith("#") or ";" not in line:
        continue
    f = line.split(";")
    entries.append((f[0], f[4] if len(f) >= 5 and f[4] else None))

max_h = max(hermit.get(s, 0) for s, _ in entries)
k_h = 1_200_000 / max_h

# wiki->list scale: median over syllables attested in both
ratios = [
    hermit[s] * k_h / wiki[s]
    for s, _ in entries
    if hermit.get(s, 0) > 0 and wiki.get(s, 0) >= 100
]
k_w = statistics.median(ratios)
print(f"hermitdave keys {len(hermit)}  k_h={k_h:.4f}  k_w={k_w:.4f} "
      f"(median over {len(ratios)})", file=sys.stderr)

rebuilt, from_wiki, pruned = [], [], []
for s, seed in entries:
    h, w = hermit.get(s, 0), wiki.get(s, 0)
    if h > 0:
        rebuilt.append((s, max(1, round(h * k_h)), seed, "h"))
    elif w > 0:
        rebuilt.append((s, max(1, round(w * k_w)), seed, "w"))
        from_wiki.append((s, w))
    else:
        pruned.append(s)

rebuilt.sort(key=lambda r: -r[1])

HEADER = """\
# TiengVietWordsRaw.txt — Vietnamese SYLLABLE list (spaces delimit syllables, not
# words). Sources: hermitdave/FrequencyWords OpenSubtitles-2018 vi (CC-BY-SA 4.0,
# primary counts, scaled so top ~1.2M) and Vietnamese Wikipedia (CC BY-SA 4.0,
# 2026-08 dump: tail-count fallback for syllables unattested in subtitles, and the
# mid-sentence capitalization evidence behind the optional 5th field). Filtered to
# the phonotactic syllable inventory; tone spelling normalized to the traditional
# (corpus-majority) style. All surfaces lowercase; the optional 5th field seeds the
# runtime case-preference counts (lower:title) from MID-SENTENCE capitalization, so
# names like đắk present as "Đắk" first while a user's own selections can still
# flip any preference. Fields: syllable;rawFreq[;;;L:T]"""

with open(OUT, "w", encoding="utf-8") as out:
    out.write(HEADER + "\n")
    for s, c, seed, _ in rebuilt:
        out.write(f"{s};{c};;;{seed}\n" if seed else f"{s};{c}\n")

print(f"rebuilt {len(rebuilt)} (hermitdave {sum(1 for r in rebuilt if r[3]=='h')}, "
      f"wiki-fallback {len(from_wiki)}), pruned {len(pruned)}")
print("pruned:", ", ".join(pruned))
print("wiki-fallback sample:", ", ".join(f"{s}({w})" for s, w in from_wiki[:25]))
