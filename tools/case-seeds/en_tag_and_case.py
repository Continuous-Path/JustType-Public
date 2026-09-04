#!/usr/bin/env python3
"""spaCy Penn-tag + mid-sentence case statistics over English Wikipedia sentences.

Usage: en_tag_and_case.py <sentences.txt> <out_stats.tsv> <target_sentences> [total_lines]

Samples evenly across the file (stride). For each lowercase alpha token key:
- Penn tag counts (all occurrences)
- mid-sentence case counts (lower/title/upper/mixed), skipping sentence-initial
  tokens and tokens following .!?…: or opening quotes/dashes (mirrors
  case_analysis_gen.py logic).
Output: word<TAB>tag:cnt,tag:cnt,…<TAB>L:T:U:M

NOTE: guarded main is REQUIRED — spaCy n_process uses multiprocessing, and on
macOS (spawn start method) workers re-import this module; unguarded module-level
work deadlocks the run.
"""
import re
import sys
from collections import Counter, defaultdict

ALPHA_RE = re.compile(r"^[A-Za-z][A-Za-z'\-]*$")
END_RE = re.compile(r"[.!?…:][\"'»”’)\]]*$")
LEAD = set("\"'«“‘(—–-*•")


def sentence_iter(path, stride):
    with open(path, encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i % stride:
                continue
            try:
                _, sent = line.rstrip("\n").split("\t", 1)
            except ValueError:
                continue
            yield sent


def main():
    import spacy

    sent_file, out_file, target = sys.argv[1], sys.argv[2], int(sys.argv[3])
    if len(sys.argv) > 4:
        total = int(sys.argv[4])
    else:
        with open(sent_file, encoding="utf-8") as f:
            total = sum(1 for _ in f)
    stride = max(1, total // target)
    print(f"{total} sentences, stride {stride}", file=sys.stderr, flush=True)

    nlp = spacy.load("en_core_web_sm", exclude=["parser", "ner", "lemmatizer", "attribute_ruler"])

    tags = defaultdict(Counter)
    cases = defaultdict(lambda: [0, 0, 0, 0])  # L T U M

    done = 0
    for doc in nlp.pipe(sentence_iter(sent_file, stride), batch_size=800, n_process=8):
        prev_text = None
        for idx, tok in enumerate(doc):
            text = tok.text
            if ALPHA_RE.match(text):
                key = text.lower()
                if tok.tag_ and tok.tag_[0].isalpha():
                    tags[key][tok.tag_] += 1
                suspect = (
                    idx == 0
                    or (prev_text is not None and (END_RE.search(prev_text) or prev_text in LEAD))
                )
                if not suspect:
                    letters = [c for c in text if c.isalpha()]
                    if all(c.islower() for c in letters):
                        cases[key][0] += 1
                    elif text[0].isupper() and all(c.islower() for c in letters[1:]):
                        cases[key][1] += 1
                    elif all(c.isupper() for c in letters):
                        cases[key][2] += 1
                    else:
                        cases[key][3] += 1
            prev_text = text
        done += 1
        if done % 50000 == 0:
            print(f"{done} sentences tagged, {len(tags)} keys", file=sys.stderr, flush=True)

    with open(out_file, "w", encoding="utf-8") as out:
        for k in sorted(set(tags) | set(cases)):
            tag_str = ",".join(f"{t}:{c}" for t, c in tags[k].most_common(6))
            c = cases[k]
            out.write(f"{k}\t{tag_str}\t{c[0]}:{c[1]}:{c[2]}:{c[3]}\n")
    print(f"{len(tags)} keys written after {done} sentences", file=sys.stderr, flush=True)


if __name__ == "__main__":
    main()
