# Case seeds — mid-sentence capitalization analysis

Derives the optional 5th `{Lang}WordsRaw.txt` field (`lower:title`) that seeds the
runtime case-preference counts (see `BuildWordDbTask.parseCaseSeed`).

**Corpus policy (2026-08):** all evidence comes from Wikipedia dumps (CC BY-SA 4.0) —
the earlier Leipzig news corpora (`spa_news_2024_1M`, `vie_news_2022_1M`) are CC BY-NC
(non-commercial) and every Leipzig-derived value has been regenerated. Do not reintroduce
NC or unlicensed sources; see `docs/.plans/language-resources/plan.md`.

## Pipeline (2026-08 runs: es 34.2M sentences, vi 8.17M)

1. **Extract** — `wikiextractor --json` over `{lang}wiki-latest-pages-articles.xml.bz2`,
   then `split_sentences.py <extract-dir> > sentences.txt` (Leipzig-style `id<TAB>sentence`;
   ≥5 tokens, terminal punctuation, abbreviation-guarded splitting).
2. **Counting** — `case_analysis_gen.py <es|vi> sentences.txt case_counts.tsv [totals.tsv]`:
   per lowercase key, MID-SENTENCE Title-case vs lowercase tokens. Skipped as positionally
   capitalized: sentence-initial tokens, tokens after `.!?…:` (plus trailing closing quotes),
   tokens preceded by opening quotes, `¿ ¡`, or dashes; ALL-CAPS/mixed ignored. `vi` mode
   folds modern tone spelling to traditional (hoà→hòa, thuý→thúy; `qu`-onset excluded) and
   the optional `totals.tsv` emits case-folded token totals (tail attestation).
3. **Seeding** — `make_seeds.py WordsRaw.txt case_counts.tsv <out|report>` with register
   correction: `k = median(corpusN / rawFreq)` over the top-20 list words gives expected
   count `E = rawFreq·k`; effective title share `s_eff = T / max(N, E)` — mass missing from
   the corpus is presumed lowercase conversational use. Title share ≥ 0.90 is trusted
   directly **only when the word is attested at ≥5% of E** (guards against encyclopedic
   collocation bias: Notre-Dame must not seed "dame").
4. **Thresholds** — scale the Leipzig-run bar (`N ≥ 25, T ≥ 3` at 1M sentences) by corpus
   size: es 34.2M → `N ≥ 855, T ≥ 103`; vi 8.17M → `N ≥ 205, T ≥ 25`. Buckets (total ~4,
   min 1 per form with ≥10% share): `3:1`, `2:2`, `1:3`, `1:4`, `0:4`.
5. **Utilities** — `seed_tools.py strip|compare` (remove 5th fields before re-seeding —
   `make_seeds.py` preserves unmatched lines verbatim, so stale seeds would survive);
   `vi_rebuild_counts.py` rebuilds Vietnamese rawFreq from hermitdave (primary, ×1.6865)
   with Wikipedia-total fallback for subtitle-unattested tail syllables.

Run `make_seeds.py … report` first to eyeball the top seeded and register-demoted words.

## 2026-08 results (Wikipedia)

- **Spanish:** 8,343 of 195,005 words seeded (3,983 `0:4`, 2,927 `3:1`, 801 `2:2`,
  563 `1:3`, 69 `1:4`); k=21.686; 495 register-demoted (me, vamos, bueno, tú…).
  vs Leipzig run (5,107): 3,242 identical, 872 one-step shifts, 993 dropped, 4,229 added
  (mostly name coverage). Known regression: `dios` unseeded (encyclopedic lowercase
  mythology use puts s_eff at 0.097) — runtime adaptation recovers it.
- **Vietnamese:** 1,338 of 9,005 seeded; counts rebuilt (8,200 hermitdave / 805
  Wikipedia-fallback / 200 pruned as attested-nowhere). Spot-verified: names/countries →
  `0:4` (Juan, María, México, España, Madrid, Đắk); days/months correctly unseeded.
