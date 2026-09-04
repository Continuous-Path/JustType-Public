# NGB Engine Spec v1 (JT-side) — Vietnamese first, language-agnostic core

Status: DRAFT 2026-08-07, all constants measurement-backed (see plan.md).
Terms per Cliff's taxonomy: 1N1 = word's first syllable -> remainder;
1N2 = context -> complete multi-syllable word; 1N3 = context -> syllable.

## Design constants (from the simulator, 500k held-out words)

| decision | value | evidence |
|---|---|---|
| inventory | dictionary words (Kaikki, CC BY-SA) | wins coverage 44.3% vs 39.2%, KSPL, and mind-map |
| prediction block position | ABOVE letter-exact (anchor=0) | check2 1.0982 vs 1.1486 anchored — the anchored slot cost 5 KSPL points |
| block size | display choice 2–6 (default 4) | KSPL-insensitive within 0.0002; scan/visuals decide |
| zero-K window | REQUIRED in v1 | carries most of the win: fts-policy 1.1600 with, 1.3045 (≈baseline) without |
| entry-state gate | ON | free, structurally correct, +0.1 pt |
| user-tier scale | M=5 on the count scale | M-fit plateau [3, ∞); M=1 within 3 pts |
| pool size | ≤60 (fetch K=200/context) | hit-rate ceiling 65.9% at K=200 |

Standing results: realistic policy 1.0982 KSPL (16.0% under baseline);
oracle ceiling 1.0532 (19.0%); realistic-oracle gap 4.3%.
Letter-exact FTS is NEVER dropped: with anchor=0 it sits at the first
post-block slot, color-coded (Cliff: FTS / prediction / YOUR prediction).
Intuitiveness of block-above-FTS is the ONE constant deliberately left
for on-device verification.

## Data artifacts (langpack build, per language)

Unified target-ID space: syllable IDs = word DB IDs; unit IDs offset
above them. All counts on the WordsRaw scale.

1. `ngb_units` — the unit inventory (Cliff's 1N1 structure; also the
   recognition matcher's inventory and the custom-unit home):
   `unit_id INTEGER PRIMARY KEY, first_syl_id INT, syls TEXT,
   marginal INT, flags INT` + INDEX(first_syl_id).
   ~21.3k rows VN (~500 KB). flags: custom, user-added.
2. `ngb_ctx` — static predictions (1N2+1N3 merged, Cliff's row-per-
   context): `ctx_syl_id INTEGER PRIMARY KEY, packed BLOB`.
   packed = varint (target_id, count) pairs, RANK-MERGED at build time,
   K<=200 per context. ~8,850 rows / ~1.1M pairs / ~9 MB VN.
   One B-tree seek per committed word — the entire hot path.
3. `ngb_user` — learned tier, in the custom DB (survives langpack
   swaps): `ctx_id INT, target_id INT, count INT, last_used INT,
   PRIMARY KEY(ctx_id, target_id)`. Row-per-pair (mutable; tiny).
   Custom units live in a mirrored `ngb_units_custom` there.

## Query contract

ON WORD COMMIT (once per word — never per keystroke):
1. ctx = last syllable of committed output; if entry was a COMPLETE
   word (unit selection, or recognition says the commit closed a word),
   set gate=CLOSED, else OPEN (Cliff's 1N1 exclusion).
2. Fetch: ngb_ctx[ctx] (1 seek) + if gate OPEN, 1N1 candidates from
   ngb_units WHERE first_syl_id=ctx weighted by marginal/s-initial mass
   + ngb_user rows for ctx + custom units for ctx.
3. Merge on the count scale: eff = static + 5*user_count (+custom
   guarantee flags). Keep top 60 as the word's prediction pool. Zero
   I/O afterward until the next commit.

PER KEYSTROKE: filter pool by key-sequence prefix compatibility
(concatenated per-syllable keys; tone-position aware, so TAV works
unchanged). List build: prediction block (top 2–6 of filtered pool,
color-coded, custom-guarantee entries capped at top-2 by decayed use)
-> letter-exact FTS block (guaranteed present, distinct color) -> ITS
fill per existing contract. All existing reachability guarantees hold.

ZERO-K WINDOW: after every commit the list shows the pool's top 8
before any keystroke; selection uses standard mechanics. This window
delivers most of the NGB's value — it is not an optional polish.

LEARNING (consent-framed, no password/sensitive fields, clear-data
control): on commit, greedy-match the trailing <=5 committed syllables
against ngb_units; increment ngb_user for the segment transition
(same counting basis as the shipped table). Caps + decay; decayed
count is also the custom-unit guarantee/demotion driver.

CONTEXT VALIDITY: fail-soft — cursor relocation, field change, or any
doubt -> no context (unigram behavior). Never guess.

## Sizing & floor

VN total: ~10 MB installed, ~3 MB gzipped langpack delta; RAM = one
60-entry pool (~KB); CPU = 1 seek + merge per word. No device-floor
impact (see device-resources.md). English later: same schema, word IDs
as context, context-count pruning (top-N frequent contexts).

## Test plan

- WLD/engine unit tests: gate states, pool merge scales, custom
  guarantee, decay lifecycle, key-prefix filtering incl. TAV.
- Cross-check harness: identical (context, typed-keys) inputs -> JT
  engine list == python simulator list (the sim is the reference).
- Device smoke: zero-K window feel, block-above-FTS intuitiveness
  (the deliberate open question), color scheme legibility.

## LanguageTraits (added 2026-08-08)

Cross-language readiness: a LanguageTraits value derived at language
switch (from LayoutSpec formatVersion/tones + langpack metadata), e.g.
is_syllable_based, has_tone_keys, space_separates_syllables. All NGB
behavior that forks per language consults traits — never language
names, never scattered tones!=null checks. VN: syllable_based=true;
EN/ES: word_based, context = whole previous word, no unit remainders
(1N1 empty), zero-K + block semantics unchanged.

## Deferred (recorded, not lost)

TAV-encoding sim variant; scan-model refinement; conversational-register
validation corpus; straight-through compound typing (names); implicit
novel-unit discovery (pause/TTS/punctuation signals, discounted seeds);
trigram/cross-word context; ES/EN NGB builds.
