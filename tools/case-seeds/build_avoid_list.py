#!/usr/bin/env python3
"""Build a two-tier {Lang}WordsAvoid.txt exclusion list from license-clean evidence.

Output format:  word;slur   or   word;profanity

  slur      — racial / ethnic / religious / anti-LGBTQ slurs. Excluded from the word DB
              at build time, unconditionally. Never predictable.
  profanity — ordinary coarse language. Kept in the DB but tagged with the
              CLASS_PROFANITY mask bit, so the runtime setting "Exclude potentially
              offensive words" (default ON) hides it without a rebuild.

Neither tier restricts the user: any word can still be added to a personal vocabulary
on-device, and turning the setting off reveals the profanity tier.

GOVERNING PRINCIPLE — asymmetric cost. JT is an accessibility keyboard. Wrongly dropping
a benign word costs a disabled user real effort; a vulgar word that survives is only ever
shown to someone who already typed its exact key sequence. When in doubt, keep the word.

Evidence, all license-clean:
  LDNOOBW                 CC BY-4.0  curated obscenity list (en, es; no vi)
  google-profanity-words  MIT        ~960 en / ~560 es
  cuss                    MIT        1,792 en words rated 0/1/2 for "sureness". Rating 2
                                     is a strong block signal; rating 0 is a curated
                                     NOT-profane list (adult, africa, african, allah,
                                     arab, asian) used as a rescue.
  4troDev/profanity.csv   MIT        multilingual (en, es; no vi)
  Wiktionary via Kaikki   CC BY-SA   vulgar-sense share, lemma-aware, plus sense
                                     CATEGORIES giving the slur taxonomy: "English
                                     ethnic slurs", "English religious slurs",
                                     "English anti-LGBTQ slurs", "Derogatory names for".

Deliberately NOT used: dsojevic/profanity-list and surge-ai/profanity carry no
open-source license (bare copyright), so their data cannot be redistributed here.

Usage:
  build_avoid_list.py <kaikki.jsonl.gz> <WordsRaw.txt> <out.txt> --lang NAME
      [--ldnoobw F] [--gpw F] [--cuss F] [--csv F] [--formal F] [--report]
"""
import argparse
import csv as csvmod
import gzip
import json
import re
import sys
import unicodedata
from collections import defaultdict

# Wiktionary's "derogatory"/"pejorative" are deliberately excluded: they cover mild
# informal usage (has-been, know-it-all, heavy-handed, landlubber), not coarse profanity.
VULG_TAGS = {"vulgar", "offensive", "slur", "ethnic-slur", "obscene"}
# "Derogatory names for places/countries" is deliberately absent: it covers place
# nicknames and mislabels ordinary words (misery, vodka).
SLUR_CAT = re.compile(
    r"(ethnic slurs|religious slurs|anti-LGBTQ slurs|LGBT slurs|racial slurs)", re.I)

VULG_SHARE = 0.50          # vulgar senses must dominate the dictionary entry
BENIGN_RATIO = 0.15        # >= this much formal-register use rescues the word ...
MIN_FORMAL_EVIDENCE = 150  # ... but only when the count behind the ratio is real.
                           # Without this, apeshit (.167) and handjob (.300) were being
                           # rescued on a dozen stray Wikipedia tokens.

# Core vocabulary that must never be dropped, whatever the evidence says.
NEVER_BLOCK = {
    "come", "comes", "coming", "came", "woke", "suck", "sucks", "sucked", "sucking",
    "screw", "screwed", "balls", "ball", "bloody", "damn", "hell", "crap", "piss",
    "panties", "idiot", "stupid", "sex", "sexy", "naked", "nude", "breast", "breasts",
    # benign homographs of vulgar terms
    "snow-white", "scatting", "gussy", "yin-yang", "hard-core", "twinkie", "boob",
    "boobs", "pissed", "love-making", "kick-ass",
    # es: mild insults that are ordinary vocabulary
    "snatch", "snatched", "snatching", "homo", "cocky", "spicy", "bugger",
    "idiota", "imbécil",
    # vi: "thằng" is the everyday male classifier (thằng bé = the boy), not profanity;
    # "phắn" is mild slang for "clear off"
    "thằng", "phắn",
}

# Obscene roots safe to match inside compounds, guarded by the exception set below
# (the Scunthorpe problem: naive substring matching eats legitimate words).
COMPOUND_ROOTS = [
    "fuck", "shit", "cunt", "cocksuck", "motherfuck", "wank", "whore", "slut",
    "bollock", "arsehole", "asshole", "dickhead", "blowjob", "handjob", "jerkoff",
    "nigger", "nigga", "faggot",
]
COMPOUND_EXCEPTIONS = {
    "scunthorpe", "niggard", "niggardly", "snigger", "sniggered", "sniggering",
    "shiitake", "shitake", "assassin", "assassins", "assassinate", "assassinated",
    "assassination", "cockatoo", "cocktail", "cocktails", "cockpit", "cockroach",
    "penistone", "clitheroe", "sluice",
}


def norm(w):
    return unicodedata.normalize("NFC", w).strip().lower()


def load_lines(path):
    if not path:
        return set()
    return {norm(l) for l in open(path, encoding="utf-8")
            if l.strip() and not l.startswith("#")}


def load_formal(path):
    out = {}
    if not path:
        return out
    for line in open(path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 2:
            continue
        out[norm(p[0])] = sum(int(x) for x in p[1:] if x.isdigit())
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kaikki")
    ap.add_argument("words_raw")
    ap.add_argument("out")
    ap.add_argument("--lang", required=True)
    ap.add_argument("--ldnoobw")
    ap.add_argument("--gpw")
    ap.add_argument("--cuss")
    ap.add_argument("--csv")
    ap.add_argument("--formal")
    ap.add_argument("--report", action="store_true")
    a = ap.parse_args()

    vocab = {}
    for line in open(a.words_raw, encoding="utf-8"):
        if line.startswith("#") or ";" not in line:
            continue
        f = line.rstrip("\n").split(";")
        vocab[norm(f[0])] = int(f[1])

    # --- curated lists -------------------------------------------------------
    ldn = load_lines(a.ldnoobw)
    # a multiword entry ("hand job") must also block its closed forms
    ldn |= {w.replace(" ", "") for w in ldn if " " in w}
    ldn |= {w.replace(" ", "-") for w in ldn if " " in w}
    gpw = load_lines(a.gpw)

    cuss = {}
    if a.cuss:
        cuss = {norm(k): int(v) for k, v in json.load(open(a.cuss)).items()}

    csv_words = set()
    if a.csv:
        with open(a.csv, encoding="utf-8", errors="replace") as f:
            for row in csvmod.reader(f):
                if not row:
                    continue
                w = norm(row[0])
                if w and w.replace("-", "").replace(" ", "").isalpha():
                    csv_words.add(w)

    formal = load_formal(a.formal)

    # --- Wiktionary ----------------------------------------------------------
    vul, tot = defaultdict(int), defaultdict(int)
    slur, lemma_of = set(), {}
    with gzip.open(a.kaikki, "rt", encoding="utf-8") as f:
        for line in f:
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                continue
            w = norm(o.get("word", ""))
            if not w:
                continue
            for s in o.get("senses", []):
                tot[w] += 1
                if {t.lower() for t in (s.get("tags") or [])} & VULG_TAGS:
                    vul[w] += 1
                for c in (s.get("categories") or []):
                    nm = c.get("name", "") if isinstance(c, dict) else str(c)
                    if SLUR_CAT.search(nm):
                        slur.add(w)
                # Only sense-level "plural of X" / "alternative form of X" links are
                # trustworthy; the entry-level forms[] list is inflection-table noise
                # and previously linked "i"/"we"/"good" to vulgar lemmas.
                for link in (s.get("form_of") or []) + (s.get("alt_of") or []):
                    tgt = norm(link.get("word", "")) if isinstance(link, dict) else ""
                    if tgt and tgt != w:
                        lemma_of.setdefault(w, tgt)

    def lemma(w):
        return lemma_of.get(w, "")

    def vshare(w):
        for k in (w, lemma(w)):
            if tot.get(k):
                return vul[k] / tot[k]
        return None

    def is_slur(w):
        return w in slur or lemma(w) in slur

    def exempt(w):
        return (w in NEVER_BLOCK or lemma(w) in NEVER_BLOCK
                or cuss.get(w) == 0 or cuss.get(lemma(w)) == 0)

    # No single list is trustworthy on its own: LDNOOBW is precise but narrow,
    # google-profanity-words and 4troDev over-include (fool, spit, bum, jerk), and the
    # Kaikki slur CATEGORY mislabels stray entries (good, vodka). Requiring independent
    # corroboration separates them cleanly — measured on English, true positives poll
    # 3-4 votes (fuck 4, apeshit/cocksucker/handjob/lesbo/niggas 3) while false
    # positives poll 0-2 (good 0, vodka 0, fool 1, jeez 1, dumb 2, jerk 2).
    n_sources = sum(bool(x) for x in (ldn, gpw, cuss, csv_words))
    MIN_VOTES = 3 if n_sources >= 4 else 2

    def votes(w):
        lm = lemma(w)
        return sum([
            w in ldn or lm in ldn,
            w in gpw or lm in gpw,
            cuss.get(w) == 2 or cuss.get(lm) == 2,
            w in csv_words or lm in csv_words,
        ])

    def flagged(w):
        if exempt(w):
            return False
        sh = vshare(w)
        strong = sh is not None and sh >= VULG_SHARE
        v = votes(w)
        if n_sources == 0:
            return strong          # e.g. Vietnamese: Wiktionary is the only evidence
        return v >= MIN_VOTES or (v >= MIN_VOTES - 1 and strong)

    def rescued(w):
        """Real benign usage in formal writing — but only on adequate evidence."""
        n, freq = formal.get(w, 0), vocab.get(w, 0)
        return bool(formal) and n >= MIN_FORMAL_EVIDENCE and freq and \
            (n / freq) >= BENIGN_RATIO

    # pass 1 — direct evidence
    blocked = {w for w in vocab if flagged(w) and not rescued(w)}

    # pass 2 — inflections of a blocked base (cocksucker -> cocksuckers, nigga -> niggas)
    # No "y"/"ies": they manufacture false positives (spic -> spicy, cock -> cocky).
    SUFFIXES = ("s", "es", "ed", "d", "ing", "er", "ers", "in")
    changed = True
    while changed:
        changed = False
        for w in vocab:
            if w in blocked or exempt(w) or rescued(w):
                continue
            if lemma(w) in blocked or any(
                w.endswith(s) and len(w) - len(s) >= 4 and w[: -len(s)] in blocked
                for s in SUFFIXES
            ):
                blocked.add(w)
                changed = True

    # pass 3 — compounds built on an unambiguous obscene root
    for w in vocab:
        if w in blocked or w in COMPOUND_EXCEPTIONS or exempt(w) or rescued(w):
            continue
        bare = w.replace("-", "").replace("'", "")
        if any(r in bare for r in COMPOUND_ROOTS):
            blocked.add(w)

    rows = sorted((w, "slur" if is_slur(w) else "profanity") for w in blocked)
    n_slur = sum(1 for _, t in rows if t == "slur")

    header = f"""\
# {a.lang}WordsAvoid.txt — two-tier exclusion list applied by BuildWordDbTask.
#
#   word;slur       racial / ethnic / religious / anti-LGBTQ slurs. Dropped from the
#                   word DB entirely — never predictable, no setting reveals them.
#   word;profanity  ordinary coarse language. Kept in the DB but tagged with the
#                   CLASS_PROFANITY bit; the "Exclude potentially offensive words"
#                   setting (default ON) hides it at runtime.
#
# Neither tier restricts the user: any word can still be added to a personal vocabulary
# on-device. This file never ships to the device.
#
# Generated by tools/case-seeds/build_avoid_list.py from LDNOOBW (CC BY-4.0),
# google-profanity-words (MIT), cuss (MIT), 4troDev/profanity.csv (MIT) and Wiktionary
# sense tags + slur categories via Kaikki wiktextract (CC BY-SA 4.0). Words with real
# formal-register use are deliberately KEPT (tang, rump, flaps, cock, nook, booby, pros,
# dong, wang, tit, coon, chink) — rationale in the script header and
# docs/.plans/language-resources/plan.md.
#
# {len(rows)} entries: {n_slur} slur, {len(rows) - n_slur} profanity.
"""
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(header)
        for w, t in rows:
            f.write(f"{w};{t}\n")

    print(f"{a.lang}: vocab {len(vocab)} -> {len(rows)} blocked "
          f"({n_slur} slur / {len(rows) - n_slur} profanity)", file=sys.stderr)
    if a.report:
        resc = sorted((w for w in vocab if flagged(w) and rescued(w)),
                      key=lambda w: -formal.get(w, 0))
        print(f"  rescued by formal usage ({len(resc)}): {', '.join(resc[:30])}",
              file=sys.stderr)


if __name__ == "__main__":
    main()
