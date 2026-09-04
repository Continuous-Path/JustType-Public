#!/usr/bin/env python3
"""Compute case seeds for EspanolWordsRaw.txt from mid-sentence news counts.

Register correction: the word list's rawFreq is subtitle-register (caseless).
k = median(newsN/rawFreq) over the top-20 list words estimates expected news
count E = rawFreq*k under register parity. For words under-represented in news
(N < E) the missing mass is everyday lowercase usage, so title share is
computed as s_eff = T/max(N, E). Exception: news title share >= 0.90 with
adequate evidence is trusted directly (proper nouns / genuinely-capitalized
words like Dios that news under-samples but never lowercases).

Seed buckets (Vietnamese precedent, totals 3-5):
  t = clamp(round(4*s),1,4); l = 4-t; if l==0 and (1-s)>=0.10 -> l=1
"""
import math
import statistics
import sys

RAW = sys.argv[1]        # EspanolWordsRaw.txt
COUNTS = sys.argv[2]     # case_counts.tsv
MODE = sys.argv[3]       # "report" or output path

MIN_N, MIN_T = 25, 3

counts = {}
with open(COUNTS, encoding="utf-8") as f:
    for line in f:
        w, l, t = line.rstrip("\n").split("\t")
        counts[w] = (int(l), int(t))

entries = []   # (word, rawFreq) in file order, non-comment
lines = []
with open(RAW, encoding="utf-8") as f:
    for line in f:
        line = line.rstrip("\n")
        lines.append(line)
        if not line.startswith("#") and ";" in line:
            fields = line.split(";")
            entries.append((fields[0], int(fields[1])))

top20 = entries[:20]
ratios = [sum(counts.get(w, (0, 0))) / rf for w, rf in top20 if rf > 0]
k = statistics.median(ratios)
print(f"register scale k = {k:.3f} (median over top-20)", file=sys.stderr)

def seed_for(s):
    t = math.floor(4 * s + 0.5)
    t = max(1, min(4, t))
    l = 4 - t
    if l == 0 and (1 - s) >= 0.10:
        l = 1
    return l, t

seeded, demoted = [], []
lines_out = []
for line in lines:
    if line.startswith("#") or ";" not in line:
        lines_out.append(line)
        continue
    fields = line.split(";")
    word, rawfreq = fields[0], int(fields[1])
    L, T = counts.get(word, (0, 0))
    N = L + T
    s_news = T / N if N else 0.0
    if N >= MIN_N and T >= MIN_T:
        if s_news >= 0.90:
            s_eff = s_news
        else:
            s_eff = T / max(N, rawfreq * k)
    else:
        s_eff = 0.0
    if s_eff >= 0.10:
        l, t = seed_for(s_eff)
        seeded.append((word, rawfreq, L, T, s_news, s_eff, l, t))
        while len(fields) < 4:
            fields.append("")
        lines_out.append(";".join(fields[:4] + [f"{l}:{t}"]))
    else:
        if N >= MIN_N and T >= MIN_T and s_news >= 0.10:
            demoted.append((word, rawfreq, L, T, s_news, s_eff))
        lines_out.append(line)

if MODE == "report":
    from collections import Counter
    print(f"seeded: {len(seeded)}   register-demoted (news share >=0.10 but dropped): {len(demoted)}")
    print(Counter(f"{l}:{t}" for *_, l, t in seeded))
    print("\n== top seeded by rawFreq ==")
    for w, rf, L, T, sn, se, l, t in sorted(seeded, key=lambda x: -x[1])[:40]:
        print(f"{w:20s} rf={rf:<8d} L={L:<7d} T={T:<7d} s_news={sn:.2f} s_eff={se:.2f} -> {l}:{t}")
    print("\n== register-demoted, top by rawFreq ==")
    for w, rf, L, T, sn, se in sorted(demoted, key=lambda x: -x[1])[:40]:
        print(f"{w:20s} rf={rf:<8d} L={L:<7d} T={T:<7d} s_news={sn:.2f} s_eff={se:.2f}")
else:
    with open(MODE, "w", encoding="utf-8") as out:
        out.write("\n".join(lines_out) + "\n")
    print(f"wrote {MODE}: {len(seeded)} seeded, {len(demoted)} register-demoted")
