#!/usr/bin/env python3
"""Assemble the new EnglishWordsRaw.txt from license-clean sources.

Inputs:
  en_full.txt      hermitdave OpenSubtitles-2018 en (CC BY-SA) `word count`
  kaikki_en.tsv    word<TAB>pos_set<TAB>surfaces  (Wiktionary via Kaikki, CC BY-SA)
  en_stats.tsv     word<TAB>PennTag:cnt,…<TAB>L:T:U:M  (spaCy over enwiki sample)
  old list         current EnglishWordsRaw.txt (curated-row carryover + comparison)

Usage: en_build_list.py <en_full> <kaikki_tsv> <stats_tsv> <old_list> <out> <min_count>
"""
import statistics
import sys
from collections import Counter

EN_FULL, KAIKKI, STATS, OLD, OUT, MIN_COUNT = (
    sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], int(sys.argv[6]),
)

ALLOWED_POS = {
    "NN", "NNS", "NNP", "NNPS", "VB", "VBD", "VBG", "VBN", "VBP", "VBZ",
    "JJ", "JJR", "JJS", "RB", "RBR", "DT", "WDT", "PRP", "PRP$", "WP", "WP$",
    "IN", "CC", "WRB", "MD", "UH", "FW", "RP", "EX", "TO",
}
TAG_REMAP = {"PDT": "DT", "RBS": "RB", "CD": "JJ", "NFP": None, "POS": None,
             "XX": None, "ADD": None, "HYPH": None, "AFX": None, "SYM": None}
KAIKKI_POS_MAP = {"noun": "NN", "verb": "VB", "adj": "JJ", "adv": "RB",
                  "name": "NNP", "pron": "PRP", "det": "DT", "conj": "CC",
                  "prep": "IN", "intj": "UH", "particle": "RP", "num": "JJ"}

# evidence bar: 3M-sentence sample vs Leipzig-1M convention (25/3) -> x3
MIN_N, MIN_T = 75, 9

# OpenSubtitles tokenization splits contractions ("aren't" -> "aren" + "t") and the
# source text carries HTML entities. Wiktionary documents several of these, so the
# Kaikki filter alone doesn't remove them. Real words that merely look like fragments
# (don, won, haven, can, shed, well) are deliberately absent from this set.
ARTIFACTS = {
    "aren", "ain", "isn", "didn", "doesn", "hadn", "wasn", "hasn", "weren",
    "couldn", "wouldn", "shouldn", "mustn", "mightn", "shan", "needn", "oughtn",
    "ll", "ve", "re", "s", "t", "d", "m",
    "lt", "gt", "quot", "nbsp", "apos",  # "amp" excluded: real word (amplifier/ampere)
}


def well_formed(w):
    """Letters plus internal apostrophes/hyphens (e-mail, brother-in-law, don't)."""
    return (
        w.isascii()
        and w.replace("'", "").replace("-", "").isalpha()
        and not w.startswith(("-", "'"))
        and not w.endswith(("-", "'"))
        and "--" not in w
    )

def load():
    hermit = {}
    for line in open(EN_FULL, encoding="utf-8"):
        p = line.split()
        if len(p) == 2 and p[1].isdigit():
            hermit[p[0]] = int(p[1])
    kaikki = {}
    for line in open(KAIKKI, encoding="utf-8"):
        w, pos, surf = line.rstrip("\n").split("\t")
        kaikki[w] = (set(pos.split(",")), surf.split("|"))
    stats = {}
    for line in open(STATS, encoding="utf-8"):
        w, tags, case = line.rstrip("\n").split("\t")
        tc = Counter()
        if tags:
            for t in tags.split(","):
                name, _, cnt = t.rpartition(":")
                if name:
                    tc[name] = int(cnt)
        l, t_, u, m = (int(x) for x in case.split(":"))
        stats[w] = (tc, l, t_, u, m)
    return hermit, kaikki, stats

def carryover_rows(old_lines):
    rows = []
    for line in old_lines:
        if line.startswith("#") or ";" not in line:
            continue
        f = line.split(";")
        tags = f[2].split(",") if len(f) >= 3 else []
        special = any(t in ("AB", "ABP", "ABPS", "EXT", "DOM") for t in tags)
        if special or "'" in f[0] or f[0] in ("I", "JustType"):
            rows.append(line)
    return rows

def pos_tags_for(word, stats_row, kaikki_row):
    if stats_row:
        tc = stats_row[0]
        total = sum(tc.values())
        out = []
        for tag, cnt in tc.most_common():
            tag = TAG_REMAP.get(tag, tag)
            if tag is None or tag not in ALLOWED_POS or tag in out:
                continue
            if cnt >= 0.15 * total or not out:
                out.append(tag)
            if len(out) == 3:
                break
        if out:
            return out
    if kaikki_row:
        out = []
        for p in kaikki_row[0]:
            t = KAIKKI_POS_MAP.get(p)
            if t and t not in out:
                out.append(t)
        return out[:3] or ["NN"]
    return ["NN"]

def main():
    hermit, kaikki, stats = load()
    old_lines = [ln.rstrip("\n") for ln in open(OLD, encoding="utf-8")]
    carried = carryover_rows(old_lines)
    carried_words = {ln.split(";")[0].lower() for ln in carried}

    top = max(hermit.values())
    scale = 1_200_000 / top

    # candidates: hermitdave, alpha/apostrophe, >= min count, Kaikki-known
    cand = []
    dropped_not_kaikki = dropped_artifact = 0
    for w, c in hermit.items():
        if c < MIN_COUNT or not well_formed(w):
            continue
        if w.lower() in carried_words:
            continue
        lw = w.lower()
        if lw in ARTIFACTS or (len(lw) == 1 and lw not in ("a", "i")):
            dropped_artifact += 1
            continue
        if w not in kaikki:
            dropped_not_kaikki += 1
            continue
        cand.append((w, c))
    cand.sort(key=lambda x: -x[1])

    # register scale for case correction: stats mass vs scaled subtitle freq
    ratios = []
    for w, c in cand[:20]:
        srow = stats.get(w)
        if srow:
            n = srow[1] + srow[2] + srow[3] + srow[4]
            if c * scale > 0:
                ratios.append(n / (c * scale))
    k = statistics.median(ratios)
    print(f"candidates {len(cand)} (dropped non-Kaikki {dropped_not_kaikki}, artifacts {dropped_artifact}); "
          f"register k={k:.3f}", file=sys.stderr)

    case_dist = Counter()
    out_rows = []
    for w, c in cand:
        raw = max(1, round(c * scale))
        srow = stats.get(w)
        surface, ct = w, "LO"
        if srow:
            _, l, t_, u, m = srow
            n = l + t_ + u + m
            if n >= MIN_N:
                if u >= 0.6 * n:
                    ct, surface = "AC", w.upper()
                elif m >= 0.5 * n:
                    mixed = [s for s in kaikki[w][1]
                             if s.lower() == w and not s.islower()
                             and not s.istitle() and not s.isupper()]
                    if mixed:
                        ct, surface = "OC", mixed[0]
                elif t_ >= MIN_T and (l + t_) > 0:
                    s_news = t_ / (l + t_)
                    e = raw * k
                    if s_news >= 0.90 and (l + t_) >= 0.05 * e:
                        s_eff = s_news
                    else:
                        s_eff = t_ / max(l + t_, e)
                    if s_eff >= 0.90:
                        ct, surface = "TO", w.capitalize()
                    elif s_eff >= 0.50:
                        ct, surface = "TF", w.capitalize()
                    elif s_eff >= 0.10:
                        ct = "LF"
                    # Wikipedia is name-heavy: when the tag distribution shows real
                    # non-name usage (bill/NN, may/MD), keep the lowercase form reachable.
                    if ct in ("TO", "TF"):
                        tag_total = sum(srow[0].values())
                        if tag_total:
                            name_share = (srow[0]["NNP"] + srow[0]["NNPS"]) / tag_total
                            non_name = 1 - name_share
                            if non_name >= 0.30:
                                ct, surface = "LF", w
                            elif ct == "TO" and non_name >= 0.15:
                                ct = "TF"
        elif kaikki[w][0] == {"name"}:
            ct, surface = "TO", w.capitalize()
        case_dist[ct] += 1
        tags = pos_tags_for(w, srow, kaikki.get(w))
        out_rows.append((surface, raw, ct, tags))

    header = [
        "# EnglishWordsRaw.txt — English word list, rebuilt 2026-08 from license-clean sources.",
        "# Counts: hermitdave/FrequencyWords OpenSubtitles-2018 en_full (CC BY-SA 4.0),",
        f"# count >= {MIN_COUNT}, scaled so top ~1.2M. Inventory filter: English Wiktionary via",
        "# Kaikki wiktextract (CC BY-SA 4.0) membership — drops subtitle OCR junk and typos.",
        "# POS + CaseType: spaCy (MIT) Penn tags + MID-SENTENCE case shares over a 3M-sentence",
        "# English Wikipedia sample (CC BY-SA 4.0, 2026-08 dump); register correction and the",
        "# >=5%-representation guard mirror tools/case-seeds (see README there). CaseType from",
        "# title share s_eff: <0.10 LO, <0.50 LF, <0.90 TF, >=0.90 TO; ALL-CAPS>=60% AC;",
        "# mixed>=50% OC (surface from Wiktionary). Curated rows (abbreviations, extensions,",
        "# contractions, I-forms) carried over from the previous hand-revised list.",
        "# Fields: word;rawFreq;CASETYPE,POS1[,POS2[,POS3]]",
    ]
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("\n".join(header) + "\n")
        for surface, raw, ct, tags in sorted(out_rows, key=lambda r: -r[1]):
            f.write(f"{surface};{raw};{','.join([ct] + tags)}\n")
        f.write("# ── curated carryover (project-authored) ──\n")
        for ln in carried:
            f.write(ln + "\n")

    print(f"wrote {OUT}: {len(out_rows)} corpus rows + {len(carried)} carried; "
          f"case dist {dict(case_dist)}", file=sys.stderr)

main()
