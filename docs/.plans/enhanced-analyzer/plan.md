# Enhanced Analyzer: Mixed-Mapping Diacritics + Placement Pipeline

Status: **ALL PHASES SHIPPED** (2026-08-03). Espanol v5 mixed-mapping live on
device (langpack v5), hybrid accent fallback (d=0.1, +3 freqClass steps)
implemented with the accent_fallback setting (default ON) and VERIFIED by
Cliff; per-language settings scoping shipped (registry languages= gating).
Post-smoke fix round landed (dev, 2026-08-03): list-function badges follow
functionKeys; key history elides slot labels ("#/-", max 3 glyphs, per-cell
width fit); next-key hint graying matches slots' FULL char sets
(slotCharsByLabel -> snapshot.slotCellChars -> SquareButton) — all three
verified on the Pixel Tablet. NOTE for future hint debugging: after SELECT
the display shows ROOT hints (first letters of all words) — 15_ lit / 60.
dim with a stored phone number is CORRECT there; mid-sequence hints are the
state that exercises slot expansion. Remaining: native-speaker sessions;
mnemonic file results/espanol/mnemonic_candidates.txt for those sessions.

## Motivation (Cliff, 2026-07-31)

Base-folding (every diacritic variant on its base letter's key) was a
simplifying assumption, not a measured choice. Unfolding variants onto their
own keys removes fold collisions entirely — the user types the exact form and
the sequence is distinct.

**Phase 0 data (Spanish, runs/es_unfolded, 120 seeds):**

| model | E |
|---|---|
| shipped base-folding (Espanol v4) | 0.0859 |
| fold floor (perfect layout, folds kept) | ~0.043 |
| **unfolded (á é í ó ú ñ placed; only ü folds)** | **0.0419** |
| English, for scale | 0.0427 |

Half of Spanish ambiguity was fold collisions; unfolding erases the entire
Spanish-vs-English gap. In every top-10 layout the optimizer separated ALL
variants from their bases (no de-facto folds) and tended to cluster accent
variants together on keys. Dominant recovered collisions are the
tilde-diacrítica pairs (qué/que 1.0% of corpus mass, sí/si, él/el, sé/se,
tú/tu, cómo, está...) — precisely the accents native speakers know.

## Strict vs forgiving lookup (hybrid)

Unfolding is strict: typing the base sequence no longer surfaces the accented
word. Hybrid lookup re-inserts each accented word under its base-folded
sequence as a fallback at discounted rank (effective freq × d). Measured on
the winning layout (accent-typer E / mean list position of an accented word
typed accent-lessly):

| d (fallback discount) | E | skipper position |
|---|---|---|
| 1.0 (raw interleave) | 0.0646 | 1.28 |
| 0.3 | 0.0497 | 1.51 |
| **0.1 (recommended)** | **0.0467** | **1.64** |
| 0 (below all exact matches) | 0.0419 | 8.78 |

d=0.1 keeps 94% of the unfolding win while accent-skippers still find their
word at mean position 1.6; JT's use-promotion then personalizes. Cliff wants
hybrid at least as an option; d lives in one tunable place. A user setting to
revert fully to base-folding stays on the table if testing demands it.

## Placement decisions (Cliff, 2026-07-31)

- **Digit-host adjacency tiers** (page-key space; search exhausts a tier
  before falling to the next): T1 vertical pairs (0-3, 3-5, 2-4, 4-7), `15_`
  above `60.`; T2 horizontal pairs flanking a small key (0-2 over DELETE,
  5-7 over SELECT), `15_` left; T3 the center-spanning pair (3-4).
- **Key-matching cell** (internal key k -> 9-grid cell): 0->0, 1->2, 2->3,
  3->5, 4->6, 5->8. Digit slots hard-pin to their host's matching cell
  (double-tap = slot). Groups placed on digit-host keys therefore forfeit
  their matching cell.
- **Slot-match priority** (which slot earns a key's matching cell): corpus
  mass of all characters entered THROUGH that cell in Two-Key Spell (the
  char itself + its drill children). Free symbols/slot chars have zero mass
  and never claim matching cells. Digit slots don't rank — they pin.
- **Position assignment is exact, not greedy**: E is invariant under key
  permutation, so groups->positions is a free 720-permutation enumeration
  (× digit-pair × punct-absorption × extra-slot host choices), scored
  lexicographically (adjacency tier, then summed matching-cell priority).
- **Punct absorption couples slots to positions**: an in-language punct
  (e.g. Spanish free-symbol ".", English corpus-char ".") chosen for the
  6-0 slot forces that slot's host to the punct's key; the punct's own cell
  is subsumed by the slot.
- **List-functions** auto-assign by one-letter-word frequency load per group:
  SYM (3 pages) -> lightest, then FNS, then NAV. Emitted as functionKeys.
- **Badge/slot separation** (Cliff 2026-08-03): a multi-char slot on a
  TOP-CORNER cell (0/2) is edge-justified toward the top-center badge zone
  and would overlap the list-function badge shown at sequence start. Keys
  with a top-corner slot are excluded from function assignment (analyzer
  enforces; hand-reviewed grids get flagged). ES v5 already satisfies this
  (Key 0 carries both digit+extra slots and no badge).
- **Mnemonic letter order within a key** is demoted below matching-cell
  choice: matching-cell occupant first, remaining cells in deterministic
  order; Cliff hand-reviews via the report (per-key order override in config).
- **Family-consistency penalty (λ)** for partially-split variant families:
  DEFERRED — Spanish's optimum fully separates every family, so nothing
  exercises it. Revisit with the next diacritic language.
- Accent-as-keystroke (reusing v2 tone machinery for Spanish) was considered
  and rejected: +0.12 keystrokes/word to save ~0.04 Selects/word — the tone6
  trade at worse odds; unfolding costs zero extra keystrokes.

## Pipeline (maps to Cliff's stages 1-6)

A. **Grouping optimization** (exists): full alphabet incl. unfolded variants;
   capacity/spell constraints as today. Variants landing on their base's key
   = de-facto fold, optimizer's choice.
B. **Fold/pack resolution**: true folds ([fold], e.g. ü->u) drill-pack into
   their base's cell as today.
C. **Function-key assignment** (new, auto): one-letter-word load per group.
D. **Position assignment** (new): exact enumeration per the decisions above.
E. **Cell arrangement + emission** (new for v1 path): matching-cell occupant,
   remaining cells deterministic, slots into spellMode.slotGroups (shipped
   v1 contract: keyNum/cell/chars/display), lettersPerKey flattened.

All new stages are OPT-IN via a [placement] config section — existing
VN/EN/ES configs and outputs are untouched (regression gate: existing tests
plus VN reproduction E=0.097557).

## Phases

1. **Analyzer pipeline** (layout-analyzer main): config schema ([placement],
   optional `variants` family table), placement.py stages C/D/E, report/CLI
   integration, tests. IN PROGRESS.
2. **Spanish mixed-mapping layout**: deep run (500 seeds), placement, Cliff
   mnemonic review, lock E. Also regenerate English through the same pipeline
   (retires the hand-edited-JSON debt; subsumes the pending EN/ES TOML
   slot-definition backfill).
3. **JT + release**: hybrid fallback lookup (d in one tunable), DB rebake,
   Espanol v5 langpack + English bundled, smoke incl. EN digit slots.
   KNOWN ISSUE to fix here: EN v4 smoke found no digit slots on device —
   bundled English DB likely needs a version bump to re-extract over the
   stale copy (Spanish, installed via langpack release, passed).

## Accent display model (Option B, shipped 2026-08-03)

Native-speaker feedback: some users want a base-letters-only keyboard.
Settings renamed per Cliff: "Show accented characters on keys" (default ON)
and "Require use of accented character keys" (default OFF, replaces
accent_fallback with inverted sense; migrated + old key removed). Require is
gated on Show (enabledWhenKey dependent-toggle support in both settings UIs).
OFF derives the base view AT RUNTIME from the one v5 layout (accents
stripped from faces/groups; WLD variant routing folds them; spell drill
offers them under the base) — measured consistency cost vs a dedicated
folded-optimal layout: E 0.0880 vs 0.0859 (+2.4%), judged worth the single-
layout simplicity (base letters keep identical positions in both modes; no
extra layout resources, no contract/DB change). KNOWN pre-existing gap:
critical settings changed in the standalone Settings ACTIVITY don't reach a
live IME until process restart (in-keyboard settings overlay unaffected) —
spawned as its own task.

## Pending: English regeneration (corpus re-vendor DONE 2026-08-05)

Corpus re-vendor COMPLETE (analyzer d20e06c): VN+ES corpora mirrored from
JT post-purge; six tone corpora regenerated; VN production E re-locked at
0.095893 (was 0.097557); Spanish E unchanged (0.041876). TAV measured on
the SHIPPED layout: E 0.095316 (0.6% better than TAE) — no layout change.
Still pending below: English regeneration through the placement pipeline.

## Original notes (confirmed 2026-08-04)

- english.toml has NO slot/placement config; the bundled EnglishLayout.json
  is the 2026-07-22 emission HAND-EDITED to add digit slots (generator date
  predates the slots it contains). Retire the debt by regenerating English
  through the placement pipeline (same as Espanol v5).
- The licensing purge (JT dev e77e9d5) regenerated es/vi corpus inputs
  (Wikipedia case seeds; REBUILT Vietnamese frequency counts). The
  analyzer's vendored corpora predate it: re-vendor from JT and re-baseline
  the regression-locked E values BEFORE any future optimization or
  E-comparison run. Vietnamese E numbers will shift (counts changed);
  Spanish E is unaffected (only the case-seed field changed).
- Do both together: EN regeneration on freshly vendored corpora.

## Open questions

- Hybrid d value: 0.1 pending Cliff's sign-off after on-device feel.
- Whether accent variants should get a visual cue on key faces (they cluster
  on few keys — possibly a learnability aid worth surfacing in UI).
