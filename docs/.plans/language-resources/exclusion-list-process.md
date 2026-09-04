# Building an exclusion list for a new language

The procedure for producing `app/src/main/db/{Id}WordsAvoid.txt`. Written after the English
pass (2026-08), which is the reference implementation; Español and Tiếng Việt are still at
step 4 and have not been graded.

Read alongside `plan.md` (sourcing policy) and the header of
`tools/case-seeds/build_avoid_list.py`.

---

## The principle everything else follows from

**The costs are asymmetric.** JT is an accessibility keyboard. A benign word wrongly dropped
costs a disabled user real effort every time they need it. A vulgar word that survives is only
ever shown to someone who already typed its exact key sequence. **When in doubt, keep the word.**

Two consequences that are easy to get backwards:

- Do not block a word because it *has* a vulgar sense. Block it when the vulgar sense
  *dominates* the word. `cock`, `tit`, `coon`, `chink`, `nook`, `dong`, `wang` and `tang` all
  have vulgar senses and all belong in the dictionary.
- Automated scoring gets you a candidate list, not a finished list. Every language needs a
  human pass, and for a language you do not read, that means a native speaker.

## Why the corpus forces this on us

Primary counts come from OpenSubtitles (hermitdave), which is film and television dialogue —
saturated with profanity in a way that no dictionary or encyclopedia is. Skipping this step
does not leave you with a neutral word list; it ships a list where crude terms rank *highly*
because they genuinely are frequent in that register.

---

## Step 1 — Inventory the available sources

Support is uneven and shrinks fast outside major languages. Check each before planning:

| Source | Licence | Coverage | Notes |
|---|---|---|---|
| [LDNOOBW](https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words) | CC BY-4.0 | 28 languages: ar cs da de en eo es fa fi fil fr hi hu it ja ko nl no pl pt ru sv th tlh tr zh | **No Vietnamese.** Precise but narrow. Multiword entries need closing up (`hand job` → `handjob`) |
| [google-profanity-words](https://github.com/coffee-and-fun/google-profanity-words) | MIT | en, es, and a few more | Over-includes (`fool`, `spit`, `bum`) — never use alone |
| [cuss](https://github.com/words/cuss) | MIT | **English only** | Ratings 0/1/2. Rating 2 is a strong block signal; **rating 0 is a curated NOT-profane list** and is the single best false-positive guard we found |
| [4troDev/profanity.csv](https://github.com/4troDev/profanity.csv) | MIT | ar bn zh en fr de hi ja pt ru es ur | Contains leetspeak; filter to pure alphabetic |
| Wiktionary via [Kaikki](https://kaikki.org) | CC BY-SA 4.0 | Any language with a Wiktionary | Sense tags `vulgar/offensive/slur/obscene` plus the slur *categories*. The universal fallback |

**Rejected — do not use:** `dsojevic/profanity-list` and `surge-ai/profanity`. Both carry a bare
copyright with no open-source licence, whatever their data quality. Re-check before adding any
new source; a list circulating on GitHub is not automatically licensed.

Record every source and its licence in the generated file's header and in `NOTICE`.

## Step 2 — Build the formal-register counts

The benign-usage rescue needs a count of how often each word appears in *formal* writing. Take
it from the language's Wikipedia dump using the existing pipeline:

```bash
wikiextractor --json -o {lang}_extracted {lang}wiki-latest-pages-articles.xml.bz2
python3 tools/case-seeds/split_sentences.py {lang}_extracted > {lang}_sentences.txt
python3 tools/case-seeds/case_analysis_gen.py {lang} {lang}_sentences.txt counts.tsv totals.tsv
```

`totals.tsv` is the `--formal` input. Without it the rescue is skipped entirely and the result
is over-blocked — the script warns when this happens, and the warning should be treated as a
blocker, not a note.

## Step 3 — Generate the candidate list

```bash
python3 tools/case-seeds/build_avoid_list.py \
    corpora/kaikki-{language}.jsonl.gz \
    app/src/main/db/{Id}WordsRaw.txt \
    {Id}WordsAvoid.txt --lang {Id} \
    [--ldnoobw ldnoobw_xx.txt] [--gpw gpw_xx.txt] [--cuss cuss.json] [--csv 4tro_xx.csv] \
    --formal totals.tsv --report
```

**How the decision is made, and why:** no single list is trustworthy. LDNOOBW is precise but
narrow; google-profanity-words and 4troDev over-include; the Kaikki slur *category* mislabels
stray entries (it tagged `good` and `vodka`). Requiring independent corroboration separates them
cleanly — measured on English, true positives poll 3–4 votes while false positives poll 0–2:

```
fuck 4    apeshit 3   cocksucker 3   handjob 3   lesbo 3   niggas 3
good 0    vodka 0     fool 1         jeez 1     dumb 2     jerk 2
```

So: block at **≥3 votes** (≥2 where fewer sources exist), or ≥2 votes corroborated by a dominant
vulgar-sense share in Wiktionary. Then three refinements that each fixed a real leak:

- **Evidence floor on the rescue** (`MIN_FORMAL_EVIDENCE`): a ratio computed over a dozen stray
  Wikipedia tokens is noise. Without the floor, `apeshit` and `handjob` were "rescued".
- **Morphology**: lists disagree about which forms they include, so inflections of a blocked
  base are blocked too. `y`/`ies` are excluded — they manufacture `spic`→`spicy`, `cock`→`cocky`.
- **Compound roots** with a Scunthorpe guard (`niggard`, `snigger`, `cockpit`, `cocktail`,
  `assassin`, `shiitake`). Never add a root without extending the guard.

**Languages with no curated list at all** (Vietnamese today) fall back to Wiktionary
vulgar-sense share alone. Expect *under*-blocking: Vietnamese produced 18 entries where English
produced 304 from a five-times-smaller vocabulary. Treat the output as a starting point for a
native speaker, not a result.

## Step 4 — Review the candidate list

Non-negotiable, and it is a language judgement, not an engineering one.

1. **Sort by frequency, read the top 50.** Anything common enough to appear there and not
   obviously coarse is a false positive. This is what caught `come`, `woke`, `suck`, `panties`
   and — in Vietnamese — `thằng`, the everyday male classifier that means roughly "the boy".
2. **Check the language's own homographs.** Every language has them. Ask a speaker for the
   equivalent of "a chink in the armour".
3. **Check identity terms.** Blocking `gays` or `queer` is offensive in its own right; these
   are not profanity.
4. **Check history, medicine and technology.** `reich`, `swastika`, `jihad`, `uzi`, `rectum`
   are needed to write about the world.
5. **Add survivors to `NEVER_BLOCK`** in the script, with a comment saying why, so a
   regeneration cannot silently undo the review.

## Step 5 — Grade into levels

The final file is `word;tier[ marker]`. Markers are hand-added during review:

| Marker | Meaning |
|---|---|
| *(none)* | Level 1, Offensive — in the DB, tagged `CLASS_OFFENSIVE`, hidden by default |
| `0` | Level 0, Non-Offensive — a false positive, kept and untagged |
| `2` | Level 2, Potentially Offensive — hidden only at the strictest setting |
| `PN` | Proper noun: kept, Title-cased (`Yamashita`, `Cumming`) |
| `ACRONYM` | Kept, UPPER-cased (`DOGE`) |
| `slur` | Racial/ethnic/religious/anti-LGBTQ. **Dropped from the DB**; no setting reveals it |
| `X` | Dropped for any other reason; the entry stays in the file to keep it dropped |
| `?` | Undecided — falls through to the tier, so `slur ?` stays excluded |

Markers combine (`0PN`). The split matters: the **Excluded Words** setting governs levels 1 and
2, so a user who wants ordinary profanity flips one switch instead of adding hundreds of custom
words — but slurs are absent from the database entirely and no setting brings them back.

Grading guidance: level 1 is ordinary swearing; level 2 is anatomical, sexual or scatological
vocabulary that is not swearing (`anus`, `dildo`, `masturbate`, `turd`) — a user may reasonably
want these while still not wanting level 1. Ableist slurs (`spaz`, `loony`, `nuthouse`) are
currently kept blocked given JT's users; that is a product call worth revisiting per language.

## Step 6 — Build and verify

```bash
./jt raw -- :app:packageLanguageArtifacts
```

The build logs `avoid list — dropped N, level0 N, level1 N, level2 N, case-fixed N`. Check the
totals equal the file's line count; a mismatch means a marker did not parse.

Then verify against the built DB in both directions — that blocked words are absent or tagged,
**and** that the words rescued during review are present and untagged. `WordDbLanguageBuildMigrationTest`
covers the migration; the list itself is verified by the build log plus a spot query.

Finally bump `{Id}.version` in `langpacks.properties` so existing installs migrate.

## Step 7 — Record it

Add the sources and their licences to `NOTICE`, and note any native-speaker review in the file
header. If nobody has reviewed a language, say so in the header — an unreviewed list is a draft.

---

## Current state

| Language | Entries | Graded? | Reviewed? | Gap |
|---|---|---|---|---|
| English | 304 (44 slur / 14 other dropped / 37 L0 / 173 L1 / 36 L2) | Yes | Yes (Cliff) | `chink`/`chinks` parked as slurs by decision |
| Español | 226 | **No** — all level 1 | No | Needs grading + a Spanish speaker. LDNOOBW `es` (68) + gpw `es` (564) + 4troDev `es` available |
| Tiếng Việt | 18 | **No** — all level 1 | No | No LDNOOBW coverage; Wiktionary-only, so almost certainly under-blocking. Needs a Vietnamese speaker |
