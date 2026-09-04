# Language Resources — Sourcing Policy & Pipeline

Durable reference for building/maintaining `{Lang}WordsRaw.txt` corpora, case seeds,
region tags, and langpacks. Written 2026-08 during the open-source licensing cleanup.

## Licensing policy (non-negotiable)

JT code is Apache-2.0; language DATA files carry their own licenses. Every data source
must be commercially redistributable:

| Allowed | Examples |
|---|---|
| Public domain / CC0 | HPLT v2 packaging, US-gov text (VOA), ENABLE |
| CC BY | Google Books Ngrams, Global Voices (3.0) |
| CC BY-SA | hermitdave/FrequencyWords, Wikipedia dumps, Wiktionary/Kaikki |
| Permissive (MIT/Apache/BSD) | tooling, taggers, extractors |

**Never:** NC-restricted (Leipzig Corpora = CC BY-NC — verified 2026-08), ND-restricted,
unlicensed lists/gists (default = all rights reserved), scraped news sites, academic
"research-only" corpora (SUBTLEX, COCA). Record source + license + download date in each
data file's header at generation time. Derived wordlists/DBs from CC BY-SA sources are
distributed as CC BY-SA data with attribution (see repo NOTICE work); this does not
affect the Apache-2.0 code license.

History: Leipzig- and hieuthi-derived values were purged from Espanol/TiengViet
2026-08-03 (see `tools/case-seeds/README.md`). The hieuthi 7,184-syllable gist
(unlicensed) contributed only dev-time filtering; no content remains.

## Standard pipeline for a new language

1. **Primary counts:** hermitdave/FrequencyWords `{lang}_full` (OpenSubtitles,
   CC BY-SA) — conversational register matches keyboard use. Filter: language-alphabet
   regex, source count ≥ 10, contamination purge (untranslated-English noise), scale so
   top word ≈ 1.2M (matches `computeFreqClass` thresholds in `BuildWordDbTask`).
2. **Inventory validation:** intersect against a license-clean lexicon (Wiktionary/Kaikki
   for the language) and/or phonotactic rules; prunes typo tail and OCR junk.
3. **Tail attestation & fallback counts:** Wikipedia dump for the language
   (`case_analysis_gen.py` totals mode); syllables/words unattested in subtitles get
   scaled Wikipedia counts (see `vi_rebuild_counts.py`); attested-nowhere entries are
   dropped.
4. **Case seeds:** Wikipedia mid-sentence capitalization → 5th-field `lower:title`
   seeds. Method, register correction, corpus-size-scaled thresholds, and the ≥5%
   representation guard: `tools/case-seeds/README.md`.
5. **Region/variant tags (optional, cf. Spanish):** Wiktionary regional labels via
   Kaikki. TODO: rewrite `parse_wiktionary_regions.py` (lost; referenced by
   `EspanolRegionTags.txt`) as a Kaikki-only extractor so the file is reproducible.
   No corpus "verification" step against non-free sources.
6. **Word-exclusion list (required for every language):** generate
   `app/src/main/db/{Id}WordsAvoid.txt` with `tools/case-seeds/build_avoid_list.py`.
   `BuildWordDbTask` applies it at build time; the file never ships and never restricts
   what a user can add on-device. Subtitle corpora are saturated with profanity, so
   skipping this step silently ships it — see "Exclusion lists" below.
7. **Langpack:** bump `{Id}.version` in `app/src/main/db/langpacks.properties`, run
   `packageLanguageArtifacts`, publish `dist/langpacks/*` (manifest + gz assets).

## Exclusion lists (`{Id}WordsAvoid.txt`)

**Governing principle — asymmetric cost.** JT is an accessibility keyboard. Wrongly
dropping a benign word costs a disabled user real effort to work around; a vulgar word
that survives is only ever shown to someone who already typed its exact key sequence.
**When in doubt, keep the word.** Block only unambiguous coarse profanity and slurs.

Three license-clean evidence sources, combined by `build_avoid_list.py`:

1. **LDNOOBW** (CC BY-4.0) — curated obscenity lists. Has `en` (403) and `es` (68);
   **no `vi`**, so Vietnamese relies on Wiktionary alone and deserves a native-speaker pass.
2. **Wiktionary sense tags via Kaikki** (CC BY-SA 4.0) — share of an entry's senses tagged
   vulgar/offensive/slur/obscene, lemma-aware so inflections inherit. Deliberately excludes
   Wiktionary's `derogatory`/`pejorative`, which cover ordinary informal vocabulary
   (has-been, know-it-all, heavy-handed, landlubber).
3. **Formal-register ratio** — Wikipedia tokens ÷ corpus tokens. A word with real
   encyclopedic use has benign senses. Calibrated on English: profanity ≤ 0.06
   (fuck .009, shit .013, arse .043, cunt .053), false positives ≥ 0.22 (cock .23,
   chink .45, tit .65, nook .88, pros .95, dong 1.4, wang 4.6, tang 5.7) — nothing between.

Rule: block if (LDNOOBW member **or** vulgar-sense share ≥ 0.50) **and** formal ratio < 0.15.
Plus a hand-maintained `NEVER_BLOCK` set for words the evidence can't save (come, woke,
suck, panties, idiota, and Vietnamese `thằng`, the everyday male classifier).

### Two tiers, and the "Exclude potentially offensive words" setting

`{Id}WordsAvoid.txt` rows are `word;slur` or `word;profanity`:

- **slur** — racial/ethnic/religious/anti-LGBTQ. `BuildWordDbTask` drops these rows
  entirely; no setting reveals them.
- **profanity** — ordinary coarse language. The row is kept but its `classMask` gets
  `ClassMasks.CLASS_PROFANITY` (bit 60; `findNextFreeBit` now caps at 59). At runtime
  `KEY_EXCLUDE_OFFENSIVE_WORDS` (**default ON**) adds that bit to the exclusion mask
  passed to `WordDb.getWordsWithMask`, so toggling it re-filters the vocabulary on the
  next reload — no rebuild, no long list of custom words for a user who wants them.

Neither tier restricts the user: personal vocabulary always wins.

### Source quality — what actually worked

No single list is trustworthy alone. LDNOOBW is precise but narrow;
google-profanity-words and 4troDev over-include (fool, spit, bum, jerk); the Kaikki slur
CATEGORY mislabels stray entries (good, vodka). **Requiring independent corroboration is
what separated them**: on English, true positives poll 3–4 votes (fuck 4;
apeshit/cocksucker/handjob/lesbo/niggas 3) while false positives poll 0–2 (good 0,
vodka 0, fool 1, jeez 1, dumb 2, jerk 2). Rule: block at ≥3 votes (≥2 where fewer
sources exist), or ≥2 votes corroborated by a dominant vulgar-sense share.

Three further passes were needed:
- **Evidence floor on the rescue** (`MIN_FORMAL_EVIDENCE = 150`): without it, apeshit
  (ratio .167) and handjob (.300) were rescued on a dozen stray Wikipedia tokens.
- **Morphology**: cocksucker/cocksuckers and nigga/niggas were split across lists.
  Suffixes exclude `y`/`ies` — they manufacture spic→spicy and cock→cocky.
- **Compound roots** with a Scunthorpe guard (niggard, snigger, cockpit, cocktail,
  assassin, shiitake…), plus LDNOOBW multiword entries closed up ("hand job"→"handjob").

**Rejected for licensing:** `dsojevic/profanity-list` (severity + category tags, exactly
the taxonomy we wanted) and `surge-ai/profanity` carry no open-source license — bare
copyright only. Not usable, however convenient.

**2026-08 revision.** The inherited list excluded 465 English words, ~339 of them wrongly:
homographs (tang, rump, flaps, wang, dong, nook, booby, pollock, cox), ordinary vocabulary
(come, woke, suck, pros, panties, idiot), identity terms (gays, queer), and history/technical
terms (reich, swastika, jihad, uzi, tnt). Now English 304 (38 slur / 266 profanity), Español 226, Tiếng Việt 18.
Ableist slurs (spaz, loony, nuthouse, deaf-mute, weak-minded) are deliberately **kept
blocked** given JT's user base — revisit if that reads wrong.

## English rebuild (2026-08, done — provenance answer still pending)

Rationale: the inherited list (Kivy-era `pwl_clean.txt`, provenance unknown — a
microbiology-heavy corpus originally, heavily hand-revised since) ships in every APK and
cannot be Apache-cleared. Replaced from scratch with the standard pipeline; the old file
is archived at `archive/EnglishWordsRaw.PRE-2026-08.txt.keep` (reference only, never ship).

Sources and method (scripts in `tools/case-seeds/`):

- Counts: hermitdave en 2018 `en_full` (CC BY-SA), count ≥ 80, scaled so top ≈ 1.2M.
- Inventory: Kaikki English Wiktionary extract (CC BY-SA), 1.01M words —
  `kaikki_extract.py`. This membership filter is what prevents technical-vocabulary skew.
- POS + CaseType: `en_tag_and_case.py` — spaCy (MIT) Penn tags + mid-sentence case shares
  over a 3M-sentence English Wikipedia sample. **The spaCy `n_process` path needs an
  `if __name__ == "__main__":` guard**; without it macOS spawn workers re-import the
  module and the run deadlocks silently (cost ~45 h once).
- Assembly: `en_build_list.py` — CaseType from corrected title share (LO/LF/TF/TO
  thresholds .10/.50/.90, AC when ALL-CAPS ≥60%, OC when mixed ≥50% with a Wiktionary
  surface), plus two guards learned here: the ≥5%-representation check on the .90 trust
  rule, and a POS-informed demotion (name-share <70% ⇒ LF, <85% ⇒ TF) so Wikipedia's
  name-heavy register can't lock `may`/`bill`/`grace` into capitalized-only forms.
- Artifact stoplist: OpenSubtitles splits contractions (`aren't` → `aren` + `t`) and
  carries HTML entities; Wiktionary documents several, so Kaikki alone won't drop them.
- Carried over: 151 hand-curated rows (AB/ABP/ABPS/EXT/DOM abbreviations, extensions,
  contractions, I-forms) — project-authored, not corpus-derived.

Result: 60,531 words (was 50,934); 76.1% of the old list retained; ~12k dropped
(including the microbiology tail: apoptosis, polymerase, plasmid, exon, microarray…),
~22k added (conversational vocabulary the old list lacked). `./jt test` green.

### Provenance of the archived list — resolved (2026-08)

The original developer identified the American National Corpus, and this was verified
empirically: `ANC-all-count.zip` (7.9 MB, dated 9/17 — matching their description) has
counts that match the archived list **exactly** for 25,555 of its 50,937 entries and
within 2% for 15,760 more (the residue is dominant-POS-row vs summed-POS selection, plus
a few hand-set values). Only 111 entries are absent from ANC. The ANC Second Release's
biomedical/technical section is the source of the microbiology skew.

**Licensing caveat — the "fully open" belief is only half right.** The *Open* ANC (OANC,
15M-word subset) is "downloadable for any use". But `ANC-all-count.*` is derived from the
22M-word **Second Release**, which anc.org states is "available for research and education
for a nominal licensing fee from the LDC" with commercial rights obtained separately; the
site footer asserts all rights reserved. Practical risk is low (only frequency *counts*
were extracted — facts, uncopyrightable under Feist; no corpus text was redistributed; a
US-origin database gets no EU sui-generis right), but it is not the clean grant the OANC
would give. **Policy: ANC data stays out of shipped artifacts.** Use the archived list as
a QA yardstick only; if ANC-family written-register data is ever wanted in the product,
derive it from the OANC, not the Second Release frequency files.

### Cross-validation against the archived list

Comparing registers (ANC = written/formal, new = subtitles + Wikipedia) caught one real
regression: the initial filter dropped **all hyphenated words** (the old list had 2,326:
e-mail, so-called, brother-in-law, x-ray…). Fixed by allowing internal hyphens/apostrophes;
the Kaikki filter cleanly separates real compounds from subtitle stutters (i-i, wh-what,
the-the), recovering 2,247. Beyond that, only 25 non-hyphenated gaps had strong Wikipedia
evidence, nearly all encyclopedic proper nouns (Roberto, Buenos, Janeiro) or sports/taxonomy
jargon — no action taken. Cross-comparison is the standard acceptance step for any future
language rebuild.

Open follow-ups: on-device smoke test; `EnglishWordsAvoid.txt` (likely LDNOOBW lineage,
CC BY 4.0 — attribute or replace).

## Distribution hygiene

- `dist/langpacks/` holds only CURRENT versions; superseded artifacts are deleted from
  the working tree (git history retains them locally — public sharing uses a fresh
  snapshot, so old blobs don't travel).
- Never publish langpacks or DBs containing data whose source/license isn't recorded.
- Debug DBs (`app/src/debug/assets/databases/`) regenerate automatically via
  `preDebugBuild`; release bundles English only (`bundledLanguages`).

## Parallel corpus jobs — the preferred pattern (Cliff, 2026-08-12)

Corpus-scale streaming jobs (n-gram counts, case analysis, heldout
extraction) are CPU-bound (JSON parse + tokenization), and HPLT ships as
numbered shards — natural sub-files. The preferred approach for any
time-consuming corpus pass:

1. Launch one streaming worker PER SHARD in parallel (curl | zstdcat |
   counter --max-docs N), each writing its own output file. No bulk
   download — prefix-stream and let the pipe close.
2. Merge with `layout-analyzer tools/merge_counts.py`: counts and
   doc-frequencies are ADDITIVE across disjoint shards, so the merge is
   exact, not approximate. Apply min-count AFTER the merge (per-shard
   pruning loses cross-shard mass) — run workers with --min-count 1.
3. Keep one shard out of training as the heldout source
   (tools/hplt_heldout.py, sentence per line).

Requirements for the pattern: per-shard outputs must be additive (counts,
dfs, token tallies — yes; anything rank- or threshold-dependent — apply
after merge). First use: the ES NGB build (shards 1/3/4 counted in
parallel, shard 2 heldout, 2026-08-12).

Gotcha, learned the hard way: `cmd | python3 - <<EOF` does NOT work —
the heredoc steals stdin from the pipe (the script trying to read the
piped data reads nothing and exits cleanly). Stream-processing scripts
must live in files.
