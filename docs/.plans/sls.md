# Selection List Sorting (SLS) — how it actually works

Written 2026-08-10 for Cliff's SLS observability request; code-verified
against CliffDev. Companion: the tiered sortmetric display (Dev settings)
and `jt learned-save/restore/reset`.

## The list is BLOCKS, not one sorted pool

The final list order is: **NGB prediction block** (rank order by
effective count; structural — NOT metric-sorted) → **phrase matches** →
**FTS** (fully typed; every one guaranteed listed) → [TAV synthetic base
rows] → **ITS fill** (letter completions, up to 8 total). The sortmetric
orders candidates only WITHIN the trie-derived section; the certainty
partition (FTS / tone-only / completions) overrides it BETWEEN blocks.
LOWER metric = better (it is a penalty sum).

## The metric (computeSortMetrics, JTUI)

sortMetric = freqMetric + seqMetric + useMetric + recencyMetric
             + lowFreqPenalty + promoteBoost

Each of the four main terms has the shape `(class · add) · mult^class`,
with add/mult LIVE-read from Dev settings prefs:

| term | class driver | add (default) | mult (default) |
|---|---|---|---|
| freq | freqClass−1 (words.freqClass 1..14, log-banded rawFreq) | freq_add_weight 1.0 | freq_mult_weight 1.25 |
| seq | keysRemaining (completion distance in KEYSTROKES) | seq_add_weight 20.0 | seq_mult_weight 2.5 |
| use | useFactor from words.useCount: ≥7→0, 0→3.5, else (7−u)/2 (INTEGER division: u=1→3, 2..3→2, 4..5→1, 6→0) | use_add_weight 3.0 | use_mult_weight 1.15 |
| recency | bucket 0..6 from words.useTime (≤15 min → 0; ×10 per bucket), 7=never | recency_add_weight 0.25 | recency_mult_weight 1.25 |

Interactions: freqClass==1 ZEROES useMetric and halves recencyMetric;
otherwise useMetric is halved. lowFreqPenalty (+1e9) pushes below-the-
vocab-slider entries to the end. promoteBoost (−1e12 imported vocab FTS,
−5e11 past-vocab) hoists module words when Promote Imported is on.

## Where the data lives and how it updates

- **freqClass**: language DB `words.freqClass`, static from corpus counts.
- **useCount / useTime**: language DB (custom words: custom DB), updated
  by `recordWordUsageForSelection` on selection commit — types
  **{X, L, E, 2} ONLY. Type N (NGB predictions) and PH do NOT record
  usage.** This is the single biggest known SLS distortion: a word the
  user repeatedly accepts via predictions never gains useCount/recency,
  so it ranks as never-used when reached through the trie. (Known
  backlog item; now measurable with the tier display.)
- **NGB entries**: ranked by effective count (corpus scale) from ngb_ctx
  + ngb_user (`+count × USER_BOOST(250k)` per learned use). No decay yet
  (backlog).
- **Trie search knobs** (Dev settings, all LIVE): search_bsd 8 (base
  depth), search_sed 7 (expansion), search_mqc 100 (max candidates),
  search_men 5000 (max examined nodes; ignore toggle). These bound WHICH
  candidates exist before sorting.

## Dev sliders status (Cliff Q3)

All 8 weight sliders and 4+1 search sliders are LIVE (read per
keystroke via `getF`/getInt). Their values are legacy hand-set defaults
— never systematically optimized. The NGB simulator did NOT model this
metric (it ranked trie words by raw frequency — a sim-fidelity gap worth
one experiment: replicate computeSortMetrics in ngb_sim and re-measure;
also makes weight A/B sweeps possible offline).

## Observability (Cliff Q4 — shipped 2026-08-10)

Dev settings: "Show sortMetric" switch (master, default OFF) + "detail
tier" slider 1..4. Tags on every word-like entry:
- Source: **F** fully typed, **I** incomplete (look-ahead), **N** n-gram
  block, **P** phrase, **T** TAV base row. (Cliff proposed B for n-gram;
  N is used because "B" is the internal type of TAV base rows.)
- Impact letters, ranked by component contribution: **f** frequency,
  **u** use count, **r** recency, **s** completion distance, plus
  **x** low-freq exclusion, **v** vocab promotion; N entries: **n**
  effective count, **g** learned-tier boost present.
- Tier 1: `word [Fuf]`. Tier 2: + score (%.4g). Tier 3: + raw values in
  impact order (`u3 f2`). Tier 4: + component metric values + the
  search-timing token (the legacy display, now only at tier 4).
- Cliff's tiers 3/4 were textually identical in the spec; implemented
  as raw-stored-values (3) vs +component-breakdown (4).

## Fresh start & snapshots (Cliff Q1)

`jt learned-reset` — Ground 0 for prediction: force-stops the app and
deletes the active language DBs + CustomDb; they reseed pristine on the
next keyboard start. Settings untouched (use `jt install -fp` for a
full wipe). `jt learned-save <name>` / `jt learned-restore <name>` —
snapshot/restore the complete learned state (use counts, case counts,
ngb_user tier, personalized confidence weights) to `.jt-learned/<name>/`.
Also fixed en route: jt's APP_PKG was stale since the package rename
(silently breaking perms helpers).

## Candidate refinements (for Cliff to prioritize)

1. ~~Fold type N into recordWordUsageForSelection~~ **DONE 2026-08-10**
   (single-word predictions: full path incl. case + deeper-pick
   freq-class promotion; multi-word units: markWordUsed per component).
2. Model computeSortMetrics in the simulator → offline weight sweeps
   (now the optimization campaign below).
3. NGB user-tier decay (backlog) — stale learned bigrams currently
   never fade.
4. Recency bucket 0 (≤15 min) is very strong with mult defaults —
   verify it matches intuition in the tier display.

## Sortmetric optimization campaign (strategy, Cliff + Claude 2026-08-10)

Goal: replace the seat-of-the-pants weights with measured optima,
PRE-NGB FIRST (the no-NGB sort must stand on its own — it is the whole
list for languages without NGB data and the fallback when context is
absent), then re-verify with the NGB block on top.

**Correction to the framing**: conditions (i)/(ii)/(iii) are not weight
settings — today's list is condition (i) BY CONSTRUCTION (the certainty
partition hardcodes FTS -> tone-only -> ITS-fill regardless of metric;
within the metric the default weights already let a freq-1 ITS outscore
freq>=10 FTS, but the partition overrides it). The three conditions are
PARTITION POLICIES to sweep:
  (i)   strict blocks (current): FTS always precedes ITS;
  (ii)  interleaved with slot 1 pinned to the top FTS (the FTS analog
        of the NGB anchor experiment);
  (iii) fully metric-interleaved.
INVARIANT that survives all three: every FTS is LISTED (reachability —
(iii) may demote FTS below ITS but never drop one).

**Architecture: emit once, evaluate millions.** Replaying the trie per
weight configuration is hopeless; instead (mfit pattern):
1. Sim-fidelity pass: replicate computeSortMetrics + partition policies
   in ngb_sim exactly (incl. the freqClass==1 interactions and the
   integer-division useFactor quirk).
2. EMIT per commit-event the candidate feature tuples — for every
   candidate alive at each checkpoint depth: (freqClass, keysRemaining,
   useFactor, recencyClass, isTarget). One replay pass per corpus.
3. EVALUATE weight vectors vectorized in numpy over the emitted events:
   expected keystrokes = f(list position of target under the weights +
   select mechanics incl. the mandatory Select + paged costs). Thousands
   of configs/second, no replay.
4. VALIDATE the argmax with a full honest replay (KSPL + KSPLS).

**Dimensionality reduction** (8 -> ~4 free):
- Ranking is scale-invariant: fix freq_add = 1 (7 free).
- Stage A (static, no usage history): useCount=0/recency=never for all
  -> u/r terms are per-candidate CONSTANTS, cancelling in comparisons
  -> only (freq_mult, seq_add, seq_mult) matter, and under policy (i)
  seq only orders WITHIN the ITS block. A 3-D grid is directly
  sweepable per policy. This stage answers Cliff's (i)/(ii)/(iii).
- Stage B (dynamic): replay long session-structured streams (documents
  as sessions) with the usage model LIVE (markWordUsed semantics:
  useCount increments, recency buckets advance) so u/r weights have
  observable effect; coordinate-descent the 4 u/r parameters over the
  Stage-A optimum. Plateau maps reported like mfit (broad plateaus
  expected; narrow ones would themselves be a finding).
- Fixed initially (exposed later only if Stage B says they matter):
  recency bucket boundaries, useFactor shape, lowFreq/promote overrides
  (semantic, not tunable).

**Objectives**: primary = KSPLS under the realistic policy; report fts
and oracle for the range. Secondary diagnostics: mean list position of
the committed word; % of commits where the target was top; scan chars.
Per-language: EN and VN may want different weights — the langpack
layoutJson/DB metadata can carry per-language weights later if the
optima diverge (engine already reads them from prefs; shipping defaults
per language is a small plumbing step).

**Stage-B data plan** (web survey 2026-08-10; licenses in
resource-licensing.md): FIT shipped defaults only on ledger-(a) data —
OpenSubtitles session files (dialog-shaped, huge) + HPLT sessions;
VALIDATE register on the Vertanen/Kristensson AAC-like corpus
(CC BY 4.0, ~6k communications, downloaded to analyzer
corpora/conversational/aac_comm — THE published AAC-register sample,
including a words list and comm-situation test sets); OBSERVE-ONLY
diagnostics may use DailyDialog / CABank (CC BY-NC-SA) and Santa
Barbara (CC BY-ND) — never fit shipped weights on those (fitted
weights inherit the data's license).

**Terminology generalization (Cliff, 2026-08-10)**: SLS core speaks
only KEYSREMAINING — a candidate with keysRemaining==0 is fully
specified (F); keysRemaining>0 is incomplete (I), whether the pending
keystroke is a letter or a tone. "Tone-pending" is NOT an SLS concept:
the FTEFTM/toneOnly block (letters complete, tone pending, ranked
between FTS and ITS) becomes a per-language PARTITION OPTION carried
by LanguageTraits, and the Stage-A policy sweep measures whether it
earns its place (add a binary: toneOnly promoted vs plain ITS) —
generality preserved, the measured VN feature kept on its merits.

**NGB-head policy family (Cliff + Claude, 2026-08-10; formula
CORRECTED — the first write-up inverted the limits)**: the NGB/FTS
boundary at the LIST HEAD (n=2 list-form slots) is a one-parameter
continuum. Definitions, exactly:
- score_N = the top surviving prediction's EFFECTIVE COUNT: the
  build-time value P(word | context) x WordsTotal (+ user-tier boost)
  — a context-CONDITIONED likelihood expressed in corpus-count units.
- score_F = the top FTS candidate's RAW CORPUS COUNT (words.rawFreq)
  — the context-FREE likelihood in the SAME units. (Stage-B option:
  u/r-boosted count.)
- M_c = the prediction-trust multiplier: the exchange rate between
  contextual and context-free evidence. RULE: slot 1 = N iff
  M_c x score_N >= score_F, else F; the LOSER takes slot 2 whenever
  the words differ (typed word and best prediction both always in
  the head).
- M_c -> infinity = shipped behavior (prediction block always above
  letter-exact; the slot-budget sweep's "anchor=0"); M_c = 0 =
  "anchor=1" (top FTS pinned at slot 1, block below — measured
  -5 pts VN / -1.9 EN, old accounting). Underlying principles:
  anchor=0 asserts context-conditioned evidence dominates whenever
  present; anchor=1 asserts letter-exact certainty owns the head.
  The capture continuum PRICES the exchange instead of choosing
  dogmatically — and mfit is exactly this measurement (success@top-n
  across the M grid: plateau M in [3, 1000], best ~19-30, M=1 within
  2-3 pts), a prior that moderate M_c keeps nearly all of anchor=0's
  KSPL.
This IS the requested "sortmetric valuing NGB data X vs no NGB
data": ONE count scale — predictions enter at M x their conditional
estimate, unigram evidence at face value — sim-estimable, and
already partially estimated by mfit. Below the head, the page-block
structure stands: NGB group page(s), then the metric-sorted tail
pages (whose words, once used, climb via u/r — the long tail
self-corrects).

**New objective term — FTS displacement**: KSPL never priced the
WYSIWYG sting of typing EVERY key of a word while composing shows a
different (prediction) word. Measure per policy: displacement rate =
fraction of fully-typed states where slot 1 != the typed word. Trade
against KSPLS like DKWF; report the frontier over the M_c grid.

**Churn, correctly defined (Cliff correction 2026-08-10)**: list
updating per keystroke is NECESSARY — ~5/6 of candidates die with
each key; survivors rising as others vanish is the expected
"narrowing" model, and block contents everywhere (incl. NGB pages)
refresh the same way. The policy-attributable quantity is GRATUITOUS
SLOT-1 CHURN: slot-1 identity changes where the previous slot-1 word
is STILL ALIVE in the list — the comparator flipped between two
surviving candidates (composing swaps identity without an
elimination). Necessary churn is excluded by construction; head
policies are measured on this, not raw reshuffling.

**Paged long-string layout (Cliff design, 2026-08-10)**: length is a
LAYOUT problem, never a ranking factor — page slots all cost the
same activations, so head capture stays merit-only, and sortmetric
order within a page is a low-priority convention droppable when long
multi-word items are present. Renderer strategy: (a) PAIR long items
with short ones on shared lines (short left, long right, measured
tab); (b) when pairing cannot fit, give long items full-width lines
with ALTERNATING left/right justification, shorts packed after;
(c) the cell->key mapping follows the DISPLAYED grid, so within-page
re-assignment for layout is legitimate. Implementation: extend
buildPagedSelectionBuffer's measured-tab machinery. QUEUED as a UI
work item.

**Other UI notes**: color coding carries provenance (FTS /
prediction / user prediction) so capture-order changes stay legible;
the zero-K window is a pure-N menu, untouched by head competition.

**Unified single-formula sortmetric (Cliff Q1 resolution,
2026-08-10)**: with N-usage now recorded, every candidate — trie word
or prediction — can flow through ONE formula:

  sortMetric(c) = freqMetric(band(sourceScore(c)))
                + seqMetric(keysRemaining(c))
                + useMetric(globalUseCount(c))
                + recencyMetric(globalRecency(c))
                [+ structural overrides]

  sourceScore = rawFreq (trie) | M_c x eff (prediction) | max (dual).

Key insight: "disregard keysRemaining for N-vs-FTS" is not an
exception — it is the GENERAL rule. seq measures keystrokes remaining
to complete THE CANDIDATE AS OFFERED: an N entry, like an FTS entry,
is complete (keysRemaining = 0); only ITS carry a seq penalty. No
special-casing. A prediction's u/r terms use the word's GLOBAL
useCount/recency (its in-context usage is already inside eff via the
ngb_user boost — two different distributions, both integrated, no
double-count). Under this formula the block policies (i)/(ii)/(iii)
become "how much grouping overrides the single sort" — the campaign's
policy sweep and weight sweep unify. Band quantization note: running
M_c x eff through the freqClass log-bands coarsens comparisons; the
optimizer should also try a CONTINUOUS freqMetric from log(count)
(the bands are ~factor-2 log steps already). Invariants unchanged:
every FTS listed; guarantee overrides, never the sort.

**How many N candidates get slots (Cliff Q1-ii)**: TODAY a fixed
count — pool = top 60 by eff at fetch; per keystroke the prefix
survivors rank by eff and the BLOCK shows the top 4 (BLOCK_SIZE,
display choice; slot-budget sweep found 2-6 KSPL-flat); zero-K shows
top 8. Alive predictions beyond 4 are currently NOT shown anywhere
(single-word ones usually reappear below as FTS/ITS via dedup;
multi-word ones simply drop). Under the page-block plan the NGB page
group can hold ALL alive pool entries (<= 60, usually far fewer) or a
score-relative cutoff — a sweep question; pages are cheap but deeper
pages cost more Selects, so ORDER matters more than inclusion.

**Shipped NGB slot mechanics, precisely (Cliff verification,
2026-08-10)** — three layers with three different caps:
1. BUILD: the langpack ngb_ctx row retains the top K targets per
   context (K=200 VN, K=100 EN export) — corpus analysis cap.
2. FETCH (per word commit): static row + 1N1 remainders + user tier
   merge, top 60 by eff -> the in-memory pool (POOL_SIZE).
3. DISPLAY: while typing, the top 4 prefix-survivors (BLOCK_SIZE)
   render as the block at LIST ROWS 1-4 of the flattened list — with
   n=2 listed words that STRADDLES the head (rows 1-2 list-form) and
   the first page group (rows 3-4 = page-1 cells 1-2); it is NOT a
   page-group placement. At the ZERO-K window (before any keystroke)
   up to 8 show (ZERO_K_SIZE) as the whole list (menu semantics).
4. Alive predictions ranked 5+ appear as NGB entries NOWHERE.
   Single-word ones may still appear as ordinary FTS/ITS rows on
   their trie merits ALONE (eff plays no part in that ranking);
   multi-word ones vanish entirely.
5. Dedup is POSITIONAL, not score-based: a word in the block gets its
   duplicate FTS/ITS row REMOVED below (it appears once, at the block
   position — which is structurally the better placement, and its
   FTS-listed guarantee is satisfied by the block row). NO shipped
   code computes max_score(NGB, FTS) anywhere — cross-source score
   comparison exists only in the simulator and in the PROPOSED
   unified formula. That proposal is what would introduce it.

**Pool-cap waste (Cliff catch, 2026-08-10 — AGREED, fix queued)**:
the fetch runs ONCE per committed word (by design — the hot path is
one DB seek); per-keystroke work is prefix-filtering of the fetched
pool. But the pool truncates the merged candidates to the top 60
(POOL_SIZE) BEFORE any keystroke arrives — so DB-row ranks 61..K
(up to 140 VN / 40 EN entries per context) can NEVER surface, even
when later keystrokes eliminate every higher-ranked entry and a
deep-ranked follower matches the prefix exactly. (Reachability is
unharmed — the word still exists via the trie — but its contextual
boost is silently lost.) The fix is CHEAPER than Cliff's suggested
re-fetch: keep the single fetch, DROP the truncation — retain the
whole merged set (~K entries; memory trivial, keySeq translation
~200 rows sub-ms) and let per-keystroke filtering see all of it;
display caps unchanged. NOTE: the simulator replicates the same
top-60 truncation, so all published numbers are CONSISTENT with the
shipped behavior — removing the cap is unmeasured upside; validate
by sim (pool = full row vs 60) before shipping.

**Per-row structure & ID encoding (Cliff, 2026-08-10)**: per-row
TRIES for pool filtering are unnecessary — per-keystroke filtering is
~200 entries x prefix subList compares (~1e3 int ops), noise next to
the trie search's up-to-5000-node walk; the pool is not a hot spot.
BUT the instinct lands where the engine spec already pointed: the
SHIPPED ngb_ctx stores targets as STRINGS ("word:eff|..."), while the
spec's original design was VARINT WORD-IDs (fixed indices into the
words table) — deferred for v1 simplicity. For the STATIC table
(rebuilt with the DB, IDs stable within a build) ID-encoding is safe
and cuts the table substantially (est. 40-60%) — pairs directly with
the discrimination-pruning byte budget. The USER tier stays strings
BY DESIGN (survives langpack swaps; recorded rationale). HAZARD noted
for any baked key-sequence structure: key sequences depend on the
RUNTIME tone-position setting (TAE/TAV) — they must be derived at
fetch (as today), never frozen at build.

**Custom-DB contexts (first_name -> last_name; Cliff, noted for the
build sequence)**: partially ALIVE today — the recognizer's word-
bigram learning writes arbitrary string ctx->target rows into
ngb_user (custom DB), so a repeatedly typed name pair is learned and
USER_BOOSTed already. Missing pieces, queued as one feature family
with the VN ADD-NEW-WORD custom units: (a) SEED ngb_user pairs when a
multi-word custom entry is added (don't wait for repetition);
(b) custom multi-word units for span/collocation behavior (C3-ready).

**Discrimination pruning (Cliff insight, 2026-08-10)**: current
export pruning keeps the top-N contexts by MASS and the top-K pairs
by count — which retains exactly the least informative giant ("the":
huge mass, followers ~ scaled unigram-among-nouns => near-zero
discriminating power) while byte pressure squeezes out mid-frequency
HIGH-information contexts. The principled retention scores:
- context x: count(x) x KL( P(.|x) || P(.) ) — expected information
  gain, i.e. keystrokes the context can actually save;
- pair (x,y): LIFT |log(P(y|x)/P(y))| above a floor — under the
  unified formula with dedup-max (and exactly under unigram
  interpolation), a no-lift pair changes NO ranking and is pure
  bytes; it also crowds pool slots (synergy with the cap removal).
Experiment queued: rebuild the EN table at the SAME byte budget with
KL/lift pruning vs mass pruning -> KSPL comparison. Also answers
K=100-EN provenance honestly: a bundle-size choice validated at the
context level (top-10k sweep) but never separately swept per-context;
the discrimination approach supersedes both knobs with one
byte-budget optimizer.

**Context-vs-unigram evidence (Cliff Q2)**: the engine ALREADY
normalizes by context — eff = P(Y|X) x Total, so a rare X does NOT
depress Y-after-X (if Y nearly always follows X, P(Y|X) is high
regardless of X's rarity); the raw pair count being small does not
carry into eff. And Y's FTS standing is never reduced by appearing in
pairs: dedup keeps Y's best placement, unigram score untouched. The
LEGITIMATE residue of the question is ESTIMATION NOISE: small
count(X) makes P(Y|X) unreliable, and pruning (min-count, top-K
contexts) truncates to pure-unigram fallback. The principled form of
"boost Y by its own frequency" is INTERPOLATION with the unigram
prior, weighted by context evidence mass (Witten-Bell/Kneser-Ney
family): score(Y|X) = lambda(count(X)) x P(Y|X) + (1-lambda) x P(Y).
Today's dedup max(rawFreq, M_c x eff) is the crude max-interpolation;
Cliff's "boost above either" = additive mixing. Both are one knob
(lambda, or context-mass-dependent lambda) in the sweep — measurable,
with max as the safe default. Under the unified formula Y also gets
its global u/r terms in EVERY appearance, which delivers part of the
requested boost automatically.

**Open modeling choice (Stage B)**: the F-side competition score —
raw count vs a u/r-boosted count (with N-usage recording now live,
accepted predictions feed u/r too). Stage A (static) sidesteps it;
revisit with the dynamic model.

**Watch-outs recorded**: (a) usage dynamics are user-dependent — the
corpus session model is a proxy; the tier display + Cliff's field
reports remain the ground truth check; (b) optimizing pre-NGB then
adding the block back could shift optima — re-verify Stage A winners
with NGB on; (c) policy (iii) interacts with paged selection (page
composition changes) — evaluate under both selection modes.

## Campaign log

### Stage A pre-NGB frontier (2026-08-10, analyzer sls_sim.py 1512c00)

Sim-fidelity pass DONE: exact computeSortMetrics replica (float32
weights, integer-division useFactor, fc==1 interactions, banding incl.
raw>8->13), JT trie BFS emission-order tie-break (Kotlin stable sort;
within-node order = corpus line order), mqc/MEN/depth caps, avoid-list
+ case-entry inventory (VN: 1132 multi-bucket seeds shift positions;
positions in ENTRY rows, ITS budget in CANDIDATES). Vectorized
evaluator == honest replay to 1e-9 (19 tests); full-stream defaults
agree to 4 decimals. Accent fallback confirmed ES-only (needs
fallbackKeyOverride) — no fallback entries in EN/VN.

Emit-once/evaluate-many: Stage A static + pre-NGB has no cross-word
state, so the stream collapses to DISTINCT words x occurrence counts
(EN 21,332 / VN 4,328 distinct; whole 1200-config grid in ~2 min).

**Result: the sliders were never the problem — the partition is.**
Weight tuning WITHIN policy (i): EN -1.2%, VN -0.0% KSPLS. The
frontier (full-stream honest replay, check2, weights fm/sa/sm):

  EN                              KSPLS   scan/l  displ  churnG/wd
   shipped  (i,T1)  1.25/20/2.5   1.1221  18.2    4.9%   0.00
   ii-best  (ii,T1) 1.25/1/1      0.9881  15.4    4.9%   0.03
   iii-best (iii,T1) 1.25/1/1     0.9532  15.1    8.3%   0.00
  VN
   shipped  (i,T1)  1.25/20/2.5   1.4818  19.2   14.7%   0.00
   ii-T0    (ii,T0) 2.0/1/1.25    1.3349  15.0   14.7%   0.00
   iii-T0   (iii,T0) 2.0/1/1.25   1.2548  14.7   20.2%   0.00

Readings: (1) policy (ii) — slot 1 pinned to top FTS, rest
metric-interleaved — takes ~75% of the interleave gain at ZERO
displacement/churn cost and lower scan: strictly dominates shipped on
every diagnostic. (2) (iii) adds ~3pts more KSPLS for +3.4 (EN) /
+5.5 (VN) pts displacement. (3) GRATUITOUS slot-1 churn ~0 under all
policies — interleaving does not introduce comparator flips; churn is
eliminations. (4) Under interleave the optimal seq weights collapse to
sa~1, sm~1-1.25 (tie-break scale); freq_mult default 1.25 already
optimal; plateaus broad (fm 1.25-2.0 within 0.005). (5) VN toneOnly:
keep promoted under (i); DEMOTE under (iii) (-4.6pts); under (ii) T0
also wins. (6) Weight tuning matters only jointly with the partition
change (EN (ii) at shipped weights 1.0655 vs 0.9881 retuned).

Recommendation (pre-NGB, pending NGB-on re-verify + Stage B): policy
(ii) with fm=1.25 sa=1 sm=1 both languages (VN toneOnly demoted) as
the safe candidate — pure win, WYSIWYG untouched; (iii) held as the
aggressive option pending the displacement judgment call.

CLIFF RULING (2026-08-10): start with (iii); hold (ii) in reserve;
weights 1.25/1/1 both languages, VN toneOnly demoted; displacement
judged not a serious UI concern (device test will confirm).

### M_c head grid + pool cap + discrimination pruning (2026-08-10,
analyzer ebdca3b, sls_sim mc-grid)

NgbReplay = fidelity list model + NGB head continuum: head slots 1-2 =
{top surviving prediction, top FTS}, slot 1 to N iff M_c x eff >=
rawFreq (dual rows merge; N-first when no FTS), block preds to
BLOCK_SIZE below, then the policy-(iii) trie tail; zero-K menu at
pos<=2; entry-state gate as shipped. check2 KSPLS, full streams:

  M_c        0      0.5      1     2..5    10..inf
  EN      0.8609  0.8498  0.8484  0.8471   0.8464
  VN      1.0929  1.0278  1.0268  1.0268   1.0267
  displ EN 9.2% -> 12.6% (M0 -> Minf); VN 17.9% -> 20.3%

**M_c verdict: the plateau is exactly mfit's** — flat for M_c >= 1;
the anchor (M=0) costs +1.7% EN / +6.4% VN KSPLS and buys only
~2.5-3.4pts displacement (dual rows dominate the head — the surviving
prediction usually IS the typed word). Shipped block-first (M=inf) is
KSPLS-optimal; the M_c dial is NOT worth shipping. lambda knob moot in
this architecture (no cross-source score mixing below the head);
becomes relevant only under a full banded unified formula — dropped.

End-to-end with NGB on (fidelity model, check2 KSPLS):
  shipped (i,T1,defaults,Minf):  EN 0.9590   VN 1.0978
  chosen (iii,T0,1.25/1/1,Minf): EN 0.8464   VN 1.0267
  = -11.7% EN, -6.5% VN on top of today's app (watch-out (b) checked:
  the pre-NGB -15% shrinks with the block on but survives).

**Pool-cap verdict (sim-gated, NO JT change): keep POOL_SIZE=60.**
Full merged row: EN 0.8440 (-0.3%, negligible); VN 1.0403 (+1.3%,
a real LOSS): deep pools nearly double mid-word multi-token unit
commits (6060 -> 11041 per 120k words) and each one trips the
entry-state gate for the next word, cutting zero-K accepts ~10%
(14.2% -> 12.8%). The "silently lost boost" is measured: negative.

**Discrimination pruning (EN, tools/ngb_prune.py)**: lift-floor
(|log lift| >= 0.405) + count*KL context ranking at the SAME eval:
  mass top-10k  16.8MB  KSPLS 0.8464   (shipped)
  KL/lift        9.2MB  KSPLS 0.8424   (BETTER at -45% bytes,
                                        37.9k contexts kept)
  at tight 4.5MB budget: mass 0.8601 vs KL 0.8600 (wash)
The win is the PAIR-level lift floor at generous budgets (no-lift
pairs are pure bytes); context KL ordering adds nothing at
starvation budgets. Production adoption = rebuild EnglishNgb.txt
with lift pruning (bundled DB -> langBuildVersion migration) —
pairs with the varint word-ID encoding; queued for Cliff's call.

### JT change landed (CliffDev 6af19b04, ./jt check green)

Dev settings > Sort Weights: "List Partition Policy"
(Blocks / FTS Pin / Interleave, default Blocks = shipped) +
"Promote Tone-Pending Words" (default on). Purely additive — no
behavior change until flipped. VietnameseSelectionTest +3 policy
tests. TO TEST (iii) ON DEVICE: policy = Interleave, tone promote
OFF (VN), seq_add slider = 1.0, seq_mult = 1.0 (freq sliders stay
default). NOTE: slider DEFAULTS unchanged for now — flipping the
registry defaults to the tuned values is the ship step after the
soak, together with per-language LanguageTraits carriage.

### Stage B dynamic u/r (2026-08-10, sls_sim stageb + probes)

Usage model (code-verified): every commit useCount+=1/useTime=now (N
rows incl., multi-word per component, no promotion for multi-word);
deep-pick (any non-first row) freqClass-1 floor 2, applied at session
boundaries (trie-reload proxy); JT recency buckets; pseudo-sessions
~300 words over the heldout streams (document-order proxy; real
doc-boundary extraction queued — no OpenSubtitles session files or
HPLT shards on disk), 10 wpm clock, 6h gaps. ngb_user learning NOT
modeled (u/r weights only). Coordinate descent (150k words, 2 passes)
on top of (iii)/T0/1.25/1/1 + NGB.

FINDINGS:
1. USE weights are plateau-flat (use_add 0..6 moves KSPLS <0.1-0.2%)
   once recency exists — keep use defaults (3.0/1.15).
2. RECENCY is the lever and shipped defaults UNDERWEIGHT it: both
   languages independently converge to rec_add 2.0 / rec_mult 1.75
   (beyond-grid probes flatten past ra~2-4: not a runaway optimum).
   Full-stream validation (check2 KSPLS):
     EN  defaults 0.8053  tuned-rec 0.7843 (-2.6%)  ur_off 0.8199
     VN  defaults 0.9595  tuned-rec 0.9292 (-3.2%)  ur_off 0.9910
   Robust to session-gap model (26h vs 6h: ~no change).
3. **REGISTER CAVEAT (AAC validation, fits nothing)**: on the
   Vertanen/Kristensson AAC stream (31.5k tokens, 2.4% OOV) the
   recency up-weight does NOT transfer: defaults 0.8511, tuned
   0.8521 (0.1% WORSE), ur_off 0.8558. The HPLT recency win is
   register-specific (web-document topical streaks); AAC comms are
   short and less bursty. u/r personalization overall is worth
   ~0.5% on AAC vs ~2.6-6% on HPLT.
RECOMMENDATION: ship u/r DEFAULTS unchanged for now; hold rec_add
2.0/rec_mult 1.75 (or a middle 0.5-1.0) as a field-judgment call —
Cliff's own repetition patterns (names, recurring phrases) are the
ground truth the corpora disagree on.

**CLIFF RULING (2026-08-10): recommendation REJECTED — the AAC corpus
is pooled cross-user utterances, not a single-user stream, so it
cannot express per-user recency dynamics (a validity flaw in the
check, conceded); and 0.1% there does not outweigh 2.6-3.2%. SHIPPED
AS DEFAULTS (CliffDev 56b7c23b, ./jt check green, tests re-pinned to
their original fixtures + interleave-default canary): partition =
Interleave, tone promote = off, seq 1.0/1.0, recency 2.0/1.75, and
word-selection mode = PAGED. All knobs stay live in Dev settings on
both surfaces. Consistency note: the analyzer's sls_sim
DEFAULT_WEIGHTS still carries the LEGACY values (recorded artifacts +
test goldens depend on them); JT defaults now diverge deliberately.
Follow-up: the paged default means future sim work should use paged
select mechanics as primary (watch-out (c) upgraded from optional to
required for the next campaign iteration).** Cumulative arc (EN KSPLS,
fidelity sim): shipped-static 0.9590 -> (iii)+weights 0.8464 ->
+usage dynamics at defaults 0.8053 -> +tuned recency 0.7843.
VN: 1.0978 -> 1.0267 -> 0.9595 -> 0.9292.

ALSO: settings-parity fix (CliffDev, 2026-08-10): the System-Settings
"developer" page routes to the legacy DeveloperSettingsActivity
(hardcoded layout), NOT the registry renderer — registry-only dev
settings are invisible there. The partition policy + tone toggle now
live on BOTH surfaces. Rule for future dev settings: add to the
registry AND the legacy activity (or migrate the page to the
registry renderer — candidate cleanup).

### Field incident: "i above I" (Cliff, 2026-08-10) — RESOLVED

Typing the one-letter word I put it at flat position 5 (block rows
1-4 above it; the page grid renders COLUMN-major — left column =
ranks 1-3 — so it read as "5th/7th"). Forensics from the live-device
DB snapshot + persisted prefs (analyzer replica): **the sortmetric
was blameless — I ranked 1st in the trie section (metric 0.000)**.
The failure was the NGB head: ngbPreferredCaseForm resolved the
lowercase prediction token "i" with a CASE-SENSITIVE
getOrCreateStats, missing the case-fixed row "I" (corpus stores
`I;1000000;OC,PRP`) and FABRICATING an fc7/rawFreq-0 lowercase orphan
row — 38 such orphans on device (i, us, china, washington, march...).
Consequences: (a) predictions for case-fixed words rendered lowercase
forever (orphan is lower-dominant); (b) the orphan clobbered the real
trie entry's useCount at every rebuild (updateOrAddWord merge
overwrote); (c) block "i" vs trie "I" broke exact-output dedup — the
word appeared twice, correctly cased only mid-list. Fixed (CliffDev
ec4433aa): WLD.caseStatsFor (case-insensitive via trie, never
creates, honors ORIGINAL form), maxOf useCount merge, one-shot orphan
cleanup at DB open, regression tests (but->I pool display + no-orphan;
duplicate-row merge). NOTE ALSO: Cliff's device has recency_add 0.25 /
recency_mult 1.25 PERSISTED (predates the default change) — the new
recency defaults are masked there; clear or move the sliders to feel
rec 2.0/1.75.

### Field incident 2: "would I" (Cliff, 2026-08-10) — RESOLVED,
### unified block ordering SHIPPED

After "or how would" + ISKW: block = [say, it, I, suggest] (raw eff
order: 287k/255k/192k/191k) — fully-typed, recently-used I (raw 1M)
at flat position 3. Cliff's call: re-examine dropping the ITS-vs-FTS
penalty inside the head. This was the deferred half of the unified
single-formula proposal (the M_c grid only tested the top-N/top-F
swap, not per-entry scoring).

SHIPPED (CliffDev, sim-validated): every block entry flows through
the ONE sortmetric — freqMetric over band(max(rawFreq, M_c x eff))
with a CONTINUOUS extension above the top band (-freqAdd x freqMult x
log2(source/40000) per doubling; plain banding SATURATED at band 1
and discarded contextual-evidence gaps — VN chuc->mung lost to anh on
seq); seqMetric over the CURRENT WORD's untyped keys (letter
certainty; a unit's later words are future input — length stays a
layout problem, not a ranking factor, so units are not
length-punished); global use/recency; eff order on ties; M_c=19
(order insensitive across 5..100 — banding+max absorb the scale).
Device numbers: unified puts I first under both weight states.
Sim (Stage-B dynamics, 250k words): strictly better BOTH languages —
EN KSPLS 0.7830->0.7813 pos1 57.9->58.6%; VN 0.8628->0.8603 pos1
57.1->58.2%. First unified-v1 attempt (saturated bands, full-length
seq) REGRESSED VN (0.8645, chuc->mung test caught it) — the
continuous extension + first-syllable seq are load-bearing, not
polish. Zero-K menu left in eff order (pure-N menu semantics,
untouched per spec). Regression tests: would->I heads the block;
chuc->mung/unit-display tests hold. NOTE: this supersedes "M_c dial
not worth shipping" — the dial alone wasn't; per-entry unified
scoring with the continuous band extension is, and it subsumes the
head competition.

### Field incident 3: "also say" (Cliff, 2026-08-10) — RESOLVED;
### pool-cap verdict REVISED

"So would you also" + s-a-y: list = [include, includes, included,
say, ...]. Forensics (harness reproduction + device DB): say sits at
ROW RANK 84 (eff 56k) in the also-context row — cut by the top-60
fetch (POOL_SIZE), so its dual-source block row never existed; the
block showed the only key-2,4,3 survivors of the top 60:
include/includes/included (raw 322/258/173, 4-6 keys away) above
fully-typed say. EXACTLY the "ranks 61..K can never surface" waste
from the original pool-cap catch — the KSPLS sim said keep the cap;
the field incident is the counterexample the corpus stream
underweighted.

MEASURED ALTERNATIVES (Stage-B sim, 250k, both languages):
  full block/trie interleave: EN +0.4% / VN +1.0% KSPLS — the block's
    structural position above completions genuinely earns keystrokes;
  blanket FTS-certainty tier at the head: catastrophic (+30%/+49% —
    anchor=1 amplified: junk short FTS over every prediction);
  untamed deep-singles pool: VN pos1 58.2->52.6% (deep singles flood
    the block; raw-max removal and steeper evidence slopes do not
    recover it).
SHIPPED (strictly better both languages): deep singles (row rank >
POOL_SIZE) retained in the pool, flagged, and ALIVE ONLY ONCE FULLY
TYPED — Cliff's original phrasing verbatim ("a deep-ranked follower
matches the prefix EXACTLY"). Deep units stay excluded (VN
multi-token/gate loss); zero-K filters deep. EN KSPLS 0.7813->0.7807
pos1 58.6->58.8%; VN 0.8603->0.8574 pos1 58.2->58.6%. Regression
test: also->say heads the list at full typing. Also verified: the
unified block ordering behaved correctly in this incident (say would
have scored -1.75 vs include +0.81) — the pool admission, not the
metric, was the failure.

**Morphological-family page grouping (Cliff, 2026-08-11, from the
"also say" list)**: include/includes/included — three declensions of
one stem with similar frequency profiles — usurped the head as three
separate rows. Related forms cry out to share ONE page group: the
group costs one Select to enter, every variant then costs the same,
and head slots stop being consumed by near-duplicates. Detect
shared-stem clusters among adjacent/nearby entries at list-assembly
time (cheap suffix/stem heuristic per language, or build-time family
tags) and coalesce them into a page group. Synergizes with the paged
long-string layout item (within-page re-assignment is legitimate; the
cell->key mapping follows the displayed grid) and with lift pruning
(family members share context evidence). QUEUED as a UI/ranking work
item.

### Head/Block-2 policy evaluation (Cliff straw men, 2026-08-11)

Residual head-miss diagnostic (cold DB, fully-typed states, current
shipped): word in slots 1-2 EN 96.75% / VN 92.60%; say-class residual
(word NOT in context row, preds above) EN 0.84% / VN 3.72%; in-row
metric-loss 0.28%/0.83%; pure-trie displacement 2.13%/2.85%.

Straw-man sims (paged select mechanics 2+6, Stage-B dynamics, 250k):
  shipped                EN 0.7777 / pos1 58.8%   VN 0.8583 / 58.6%
  P1 (top-FTS in head; Block2 = all FTS + NGB fill to 18)
                         EN 0.8665 / 43.8%        VN 0.9535 / 54.4%
  P1 + ITS backfill      EN 0.8666 / 43.7%        VN 0.9707 / 47.8%
  P1 hf-gated (fc1 only) EN 0.8632 / 43.5%        VN 0.9510 / 47.4%
  P2 (FTS guaranteed slot 1) EN 0.8812 / 33.7%    VN 1.0547 / 21.2%

DIAGNOSIS: the ~+11% cost is NOT the head guarantee (hf-gating the
guarantee changes nothing) — it is the BLOCK-2 COMPOSITION: filling
page 1 mostly with predictions (ranks 5-18 of the pool) crowds out
the top ITS completions. Prediction ranks 5+ convert poorly (matches
the original slot-budget sweep: KSPL flat at 2-6 pred slots); the
top-3 ITS completions are load-bearing (how long words finish cheaply
whenever the pool lacks the word). ITS backfill never engages (pool
alive usually >= 16 mid-word). "Ship a big NGB db and don't worry
about ITS" is measured false at current table quality — deep pool
ranks are eff-tail noise, not coverage.

CONCLUSION: the shipped structure (metric head, block <= 4 preds,
metric-interleaved FTS/ITS tail, budget 8) is near-optimal per
corpus; the residual head-misses are a COVERAGE problem (word absent
from the context row), not a structure problem. NEXT: KL/lift+varint
rebuild sizing with larger K (shrinks the no-row residual at equal
bytes); optional cheap formal guarantee: promote any FTS beyond row 8
into page 1 (visibility, not order — rate is tiny so cost ~0).

**Low-p-hat runtime recognition (Cliff, 2026-08-11 — QUEUED, test
soon)**: the NGB-D confidence posterior already estimates P(top item
= intended) per list state. A LOW-p-hat state that contains a
fully-typed high-frequency word is a recognizable "probable head
miss" — a principled runtime gate for a head swap (or other remedial
UI), i.e., recognize the miss before the user does. Design sketch:
reuse the shipped NgbConfidence features; fire only at kr==0 states;
sim first with the calibrated weights (conf artifacts need the
post-correction refresh first).

### Rebuild sizing round 1 (2026-08-11): lift pruning verdict REVISED

Lift-pruned tables (all 37.9k EN contexts, K 100/200/400) LOST to the
shipped mass table under the CURRENT architecture (EN KSPLS +0.15%,
head12 96.9->94.9%, no-row residual 2.8->4.9%): **the "no-lift pairs
are pure bytes" claim was true under the old structure but is FALSE
after the also-say fix** — near-unigram pairs are now the kr0
dual-source RESCUE rows (a word's presence in the row is what lets it
enter the head fully-typed with max(rawFreq, M_c x eff)). Dropping
them removes words from rows entirely = manufactures the exact
residual we're chasing. Coverage now beats discrimination. The
original lift-pruning win (0.8424 vs 0.8464) was measured pre-kr0-
admission and does not carry forward. K depth beyond the lift floor
is not the binding constraint (rows shrink under the floor first).
Round 2 = coverage-first: ALL contexts, NO lift floor, K-capped rows,
varint bytes (EN K400 = 13.6MB tsv / ~2.4MB varint; VN K400 = 31.2MB
/ ~5.9MB).

### Rebuild round 2 (2026-08-11): coverage-first WINS — EN K400 SHIPPED

  EN table                 KSPLS   head12   no-row   tsv     varint
  shipped mass-10k K100    0.7777  96.89%   2.80%    16.8MB  —
  cov all-ctx K100         0.7719  96.88%   2.81%     9.9MB  1.8MB
  cov all-ctx K200         0.7717  97.04%   2.59%    12.0MB  2.1MB
  cov all-ctx K400         0.7720  97.21%   2.39%    13.6MB  2.4MB
  VN cov K400 (vs full@200 0.8583/93.01/6.14):
                           0.8597  93.56%   5.17%    31.2MB  5.9MB

Readings: context expansion (10k->37.9k) buys the KSPLS (-0.75%) and
zero-K (18.1->19.1%); K depth buys the residual (2.80->2.39%) at flat
KSPLS. EN cov-K400 dominates shipped on every metric AND is smaller
as plain strings. SHIPPED (CliffDev): EnglishNgb.txt = all 37.9k
contexts, K=400 (packed asset 6.3->11.3MB; langBuildVersion reseeds
installs; say permanently in-row at rank 84). All incident regression
tests green. VN: HOLD at full@200 (K400 = +0.16% KSPLS for -1pt
residual; 31MB string form heavy for a langpack — revisit with the
varint JT implementation, which cuts EN to ~2.4MB / VN to ~5.9MB and
remains the queued encoding change). Cliff's ship-vs-downloadable
split not needed for EN at these sizes.

### Field incident 4: "only below vayu" (Cliff, 2026-08-11) — RESOLVED

"I'm only": fully-typed only (band 2, raw 37k) at flat rank 9, below
vayu (raw 8). NOT the corpus, NOT the K400 table (i'm has no context
row — apostrophe tokenization; the list was pure trie), NOT usage
(all listed words u=0; Cliff typed none of them), NOT recency (his
rec sliders still 0.25/1.25 persisted), no varint exists. The list
was TWO perfectly metric-sorted groups — the giveaway for the
two-level REGION sort: EnglishRegionTags carried Wiktionary regional
SENSE labels as word-level skews (only;GB, half;GB, abroad;US...
5,318 words incl. core vocabulary) and his english_region=en-US
demoted only/half below everything. An OLD data bug made list-wide by
the interleave partition (under blocks, demoted FTS stayed inside the
FTS block).

FIXED (CliffDev): EnglishRegionTags filtered to spelling-variant
PAIRS — tag survives only if the standard GB<->US transform of the
word exists in the vocab (443 kept: colour/color, agonised/agonized,
amphitheatre/...; 4,875 sense-label contaminants dropped).
langBuildVersion reseeds installs. Regression test: fully-typed
"only" heads its list under en-US. QUEUED: EspanolRegionTags has the
SAME contamination (same generator; absolutamente;LA) — inert while
spanish_region=any, but ES/LA pairs are LEXICAL (coche/carro), not
transform-derivable: regenerate from Wiktionary alternative-form
PAIR data, not sense labels, before any ES region ships. Also note
i'm-class contexts (apostrophe words) are absent from the ngb table
(tokenizer splits them) — a coverage gap worth one look: contexts
like i'm/don't/you're are extremely frequent.

### Head-resort straw man evaluated (Cliff, 2026-08-11)

Design: after shipped assembly, if the 1-2 trie rows below the block
are FTS, re-sort rows[0:LIMIT] (block + FTS run). Two metrics tested,
with slot-1/2 redefinition logging (help = target gained slot 1,
hurt = target lost it):

  EN zeroseq (literal straw man): KSPLS +0.05%, 45.3k changed states,
     helped 3,095 / hurt 3,364 (NET NEGATIVE) — and it demotes the
     fully-typed target in the exact protected scenario (ctx 'to',
     typing 'it': [it, its, stop..] -> [stop, it, ..] — zeroing seq
     removes the ONLY term that handicaps incomplete predictions);
  EN withseq (pure merge): KSPLS +0.36%, helped 697 / hurt 2,196
     (3:1 hurt — promoting mid-word FTS: 'a' + typing 'young' pulls
     'you' over the correct 'young' completion);
  VN zeroseq: helped 2,060 / hurt 1,587 (small net positive), KSPLS
     flat; VN withseq neutral.

VERDICT: no head re-sort metric can protect a NO-ROW word — its
score is its score (raw-based vs M x eff evidence), and the re-sort's
collateral churn costs as much as it saves (EN net-negative). The
protection that fits the goal without blanket cost is STATE
RECOGNITION, not re-ranking: the queued low-p-hat runtime experiment
(fire only at fully-typed states where confidence is low and a
high-band FTS sits below the head) is now THE mechanism, promoted
from nice-idea. Meanwhile the highest-value coverage closure is the
APOSTROPHE-CONTEXT gap (i'm/don't/you're — top-frequency contexts
entirely absent from the ngb table due to build tokenization).

### The user-strategy model (Cliff, 2026-08-11) — UI north star

Four states, FTS/ITS (finished typing or not) x CS/NS (confidence
signal or not); the optimal strategy we design toward and want to
inculcate:
  FTS/CS: hit Select; quick glance at the Select-key word to confirm
          as you move on.
  ITS/CS: check the Select key; if it shows your word, hit it and
          move on; else keep typing.
  FTS/NS: hit Select; check the highlighted top item; if yours, move
          on; else scan and Select.
  ITS/NS: keep typing.
Very slow typists (e.g. single-switch scanning) may rationally scan
the list at ITS/CS and even ITS/NS. NS mid-word does NOT mean "scan"
— it means "keep typing"; the scan instruction only attaches at
FTS/NS. UI decisions (incl. the low-p-hat bubble-up: silent, no cue,
high-confidence signal naturally absent) must stay consistent with
this matrix; look for opportunities to teach it (onboarding, docs,
possibly adaptive hints).

### Harvest round (2026-08-11): apostrophe contexts SHIPPED;
### p-hat-gated bubble-up measured NEGATIVE

**Apostrophe table SHIPPED** (CliffDev): re-streamed HPLT eng_Latn
shard-1 prefix (87k docs) with an apostrophe-preserving tokenizer
(TOKEN_RE_AP; curly-quote normalized); cov-K400 all-contexts rebuild
= 37,856 contexts / 854k pairs, i'm row 785 targets (i'm not 6368...).
Disjoint shard-2 apostrophe heldout: KSPLS -0.14% (web register),
**-0.53% AAC conversational** with stream token loss 2.4% -> 0.3%.
The i'm-class no-context gap is closed.

**p-hat-gated bubble-up: does NOT flip the help/hurt ratio.** Sweep
(theta 0.15/0.3/0.5, conf_weights parity features): EN helped/hurt =
3/19 at 0.7% firing, 83/699 at 22.5%, 227/1489 at 47.2%; VN mildly
positive (70/58 at 28%) but KSPLS flat-to-worse everywhere. MECHANISM:
p-hat estimates P(top = intended), which is LOW at ordinary mid-word
states of longer words — the gate opens predominantly MID-WORD, where
bubbling kr0 homophones is exactly wrong. The gate the design needs is
"user is DONE typing" (Cliff's FTS/ITS axis), which p-hat does not
measure and the engine cannot observe... except at the moment the user
presses SELECT — the user's own done-declaration. DESIGN CANDIDATE
(not built): first-Select-press re-rank — apply the bubble-up when
Select is pressed at a no-signal state (FTS/NS in the strategy
matrix: the user is about to scan anyway; the list they scan is the
corrected one). Interacts with composing-preview WYSIWYG (preview
shows slot 1 pre-Select) — needs Cliff's UI ruling before any build.
Six bubble-up variants now measured (zeroseq/withseq x ungated,
gated x3 theta): none pay at typing time; the user-level FTS/NS scan
strategy plus coverage remains the working answer.

### Adaptive select-behavior mechanisms — FINAL PROPOSAL
### (Cliff + Claude, 2026-08-11; design stage, not yet built)

Shared substrate (ships FIRST, invisible): per-user counters in the
custom DB recording, at each Select-press, (signal state, whether a
fully-typed candidate sat below the head, what was ultimately
selected: kind + depth). Same pattern as the NGB-D honesty counters;
Dev-readable; field distributions BEFORE any behavior changes.
Evidence is EWMA-decayed — user motor strategies change with
progression, so adaptation must be reversible by the same mechanism.

A. ADAPTIVE FTS PROMOTION AT SELECT-PRESS. The Select press at a
no-signal state is the only observable done-declaration (FTS/NS in
the strategy matrix) — but scanners/slow typists press Select
MID-SCAN to harvest a spotted prediction, and re-ranking under them
is exactly wrong. Resolution: observe first, adapt per user,
conditioned on state (no-signal AND a demoted fully-typed candidate
present below the head). If the user routinely selects lower
predictions: never activate. If they routinely dig down to demoted
FTS objects (Cliff's staging, verbatim): first, a pattern is
detected; once detected, the first behavior change is that at future
Select presses in no-signal states, any demoted FTS entries in the
current list are promoted ONLY onto Page 1; only sustained evidence
results in promoting FTS entries into the head. Internal testing:
Dev settings force-enable ladder (observe-only / force-page1 /
force-head / adaptive) so the behavior is feelable immediately; the
observe-before-activate discipline applies to AUTOMATIC changes on
release-version devices. The strategy matrix is designed toward and
taught, never assumed — the observation gate adapts to what each
user actually does.
For the FTS/NS flow the timing is safe by construction (that user
presses, THEN reads; the corrected list is what they read); the
observation gate is what protects the read-first scanner. Corpus sim
can bound the promotion mechanics; the adaptation itself is
field-data by design.

B. ADAPTIVE THETA_SIGNAL. On signal FIRE: (1) user accepts top ->
decrement theta; (2) user keeps typing (no accept) -> increment;
(3) user accepts a lower word -> neutral (the fire still harvested
the word nearby; revisit with field counters). KEY MATH: equilibrium
precision = inc/(inc+dec) — the step-size RATIO is the precision
target. USER CONTROL (Cliff): the precision target IS the user-facing
slider — "if you're going to distract me to get my attention, I want
you to be right __% of the time" (range ~60-95%; info text states
plainly that higher = more trustworthy but rarer). dec fixed small;
inc = dec x p*/(1-p*). Measured precision is strict-top: case (1) vs
(2), case (3) neutral (harvested nearby — outside both numerator and
denominator). Soft-clamp theta in ~[0.45, 0.90], start at the DKWF
default (0.65; re-sweep on corrected accounting pending). Caveat for
future info text: high targets fire rarely -> slower adaptation.
Freeze (or heavily slow) the per-user confidence-weight SGD when
theta-adaptation ships so drift is attributable to one mechanism.
UI: user-facing = ON/OFF + the precision slider; the raw theta
slider moves to Dev settings (both surfaces, parity),
mode=adaptive default.

Staging: (i) substrate + counters; (ii) analyze field distributions
(Cliff's device first); (iii) enable B (simpler, self-limiting);
(iv) enable A's ramp. Each stage behind a Dev toggle.

REMAINING backlog: real document-boundary session extraction +
OpenSubtitles ledger-(a) session fitting (Stage-B refinement); varint
word-ID ngb_ctx encoding (+lift pruning rebuild); paged long-string
layout; custom-context seeding; Kaikki EN collocations; NGB decay;
confidence-artifact refresh. Watch-out (c) still open: re-check (iii)
under WORD_SELECTION_PAGED select mechanics (sim used list mode n=8).

## Thread-3 close-out (2026-08-11)

One thread carried the whole sortmetric campaign: sim-fidelity pass ->
Stage-A frontier (partition beats weights; interleave shipped as
default with tuned weights + paged mode, Cliff ruling) -> Stage-B
(recency 2.0/1.75 shipped over the AAC caveat, Cliff ruling) -> M_c
plateau (block-first kept) -> pool-cap verdict (keep 60; later revised
by incident 3) -> five field incidents, each a different layer, each
fixed + regression-tested + landed: (1) "i above I" = NGB case
resolution fabricating orphan rows; (2) "would I" = unified block
ordering (continuous top-band extension + current-word seq);
(3) "also say" = deep-single kr0 pool admission (pool-cap revised);
(4) coverage-first K400 EN table (lift pruning revised: no-lift pairs
are kr0 rescue rows post-fix-3); (5) "only below vayu" = region-tag
sense-label contamination (pair-filtered). Then: apostrophe-context
EN table shipped (i'm row restored, -0.53% AAC); head/Block-2 straw
men + six bubble-up variants measured dead (gate opens mid-word);
the user-strategy matrix recorded; adaptive select-behavior FINAL
PROPOSAL v2 (Cliff staging phrasing, Dev force-enable ladder, user
precision slider) ready to BUILD.

NEXT THREAD STARTS WITH: build the adaptive substrate + Dev
force-enable ladder (spec in "Adaptive select-behavior mechanisms"
above). [DONE — see Thread-4 below.] Then, plan order: ES region tags (needs Kaikki es pair data),
varint ngb_ctx encoding (unlocks VN K400 at ~5.9MB), theta re-sweep
on corrected accounting, doc-boundary Stage-B refinement,
morphological-family page grouping, NGB user-tier decay, Kaikki EN
collocations. Standing state: CliffDev = the line (origin =
Continuous-Path/JustType, renamed; mirror DELETED); tablet runs the
full stack (all five fixes + apostrophe K400 table + shipped-optima
defaults); NOTE Cliff's device has recency 0.25/1.25 PERSISTED from
before the default change — clear or move sliders to feel the tuned
recency. Analyzer harness: tools/sls_sim.py (fidelity replica +
StageBReplay with every variant of this campaign).

## Thread-4 (2026-08-11): adaptive substrate + Dev ladder SHIPPED

Stage (i) of the FINAL PROPOSAL, plus mechanism A's force modes. All
`./jt check` green; SelectBehaviorScenarioTest x8.

**Substrate (always on, invisible)**: `sel_stats` table in the custom
DB (created in ensureNgbTables, both open paths — no migration
needed). One EPISODE per list engagement: state captured at the FIRST
Select press — (signal fired for this list state) x FTS-state axis —
outcome at commit: kind (F/I/N/PH/B) x depth (h / p1 / pd), or
`abandon` when the list rebuilds under an open episode (user kept
typing); list-function ("P") picks drop the episode silently. Bucket
key `(s|ns)_(m|d|n):kind.depth`. EWMA: every episode decays ALL of
the language's buckets by 0.99 (half-life ~69 episodes — reversible
by construction) then +1s its own, so the bucket sum IS the EWMA
episode total. Commit funnel = recordWordUsageForSelection top
(covers paged pick, finalize-on-ambig, Term, Enter — incl. PH/B which
the usage gate excludes). "Signal fired" is now a real field
(`ngbConfLastFired`, set at the updateUi observation, reset per
rebuild) — the fire state was previously consumed invisibly.

**FTS-state axis refined (2026-08-11, from the residual instance
files)**: "fully typed" is sequence-relative, NOT provenance-relative
— a dual-source word whose trie row was deduped into the block (the
in-row class: `the->uk` shows uk as an N row) counts, via a new
`ngbKeySeqLen` field on N entries (full key length == typed length,
single-word only). Three states, because the residual files split on
whether the HEAD also holds a fully-typed word:
  `m` = HEAD MISS: nothing fully typed in the head, a fully-typed
        word demoted below (the `and->organs` / `said->to` class) —
        the state mechanism A remedies;
  `d` = AMBIGUITY: a fully-typed alternative already heads the list
        (`the->uk` vs us — same key sequence). Digging here is
        necessity, not strategy, and promotion would be WRONG (it
        re-ranks between two legitimate readings of the keys);
  `n` = no demoted fully-typed word.
Outcome kind F likewise means "fully specified as committed" (trie or
dual-source block row).

**Dev force-enable ladder** (`select_behavior_mode`, BOTH settings
surfaces + ES/SW strings): Observe (default, record only) /
Force Page 1 / Force Head / Adaptive (RESERVED — observes only until
the ramp ships at staging (iv)). Force modes act at the episode-start
Select press ONLY in no-signal HEAD-MISS (`m`) states
(mechanism-faithful: A never acts where the signal guided the user,
nor where the head already offers a fully-typed word — the blanket
FTS tier at the head measured catastrophic (+30%/+49%) in the
straw-man sims, and an early unconditioned force build re-created it
verbatim: on the "the" context list it hoisted toe-class junk above
the typed word; span sessions excluded). Promotion is a pure
reorder — page1 target inserts at pagedFirstRow() (leading page-1
cells), head target after the last list-function row (slot 1; head
rows spill down naturally); every-FTS-listed invariant untouched;
paged-region alternate expansion re-applied post-reorder. First press
then selects the promoted word — the corrected list is what the
FTS/NS scanner reads.

**Dev readout**: "Show select-behavior stats" (legacy Dev activity)
— per-language grouped distribution (state groups, weights + %) read
fresh from CustomDb each press. `jtui.selStatsForTest()` /
`WordDb.selStatsDump()` for harness access.

**End-to-end tests (SelectBehaviorEndToEndTest, 7; + scenario suite
8)**: replay residual-file recipes on the bundled EN DB through the
real key/commit paths — observe dig records `ns_m:F.p1` (+ the
context commit's own decayed `:F.h` episode); force-head lifts the
dual-source `said->to` row to slot 1 and commits it as `ns_m:F.h`;
force-page1 advances deep `in->situ`; `the->uk` ambiguity stands
down (no `ns_m` bucket ever); signal-fired head-miss stands down and
records `s_m:F.p1`; EWMA sum == decayed episode total through the
standalone Dev-readout connection; and one warming pick rescues
`organs` into the head (recipes self-destruct by design — the
reversibility premise). Two-key head-miss states don't exist (a
demoted 2-letter kr0 word always has a likelier kr0 sibling in the
head) — head-miss needs longer words; the crib-sheet doc marks which
recipes are `m` (promotion fires) vs `d` (episode-only).

NEXT (per staging): (ii) field distributions from Cliff's device
BEFORE any automatic behavior; then (iii) adaptive theta_signal
(mechanism B: precision-ratio math, user precision slider, raw theta
to Dev); then (iv) A's ramp (detection thresholds from the observed
distributions), wiring ADAPTIVE mode to the counters. Then the
standing plan order: ES region tags, varint ngb_ctx, theta re-sweep,
doc-boundary Stage-B, morphological-family grouping, NGB decay,
Kaikki EN collocations.

## Varint ngb_ctx SHIPPED + VN K400 default (2026-08-11)

The queued encoding change, plus the table it unlocks. All NGB suites
(63 tests) + ./jt check green; the e2e suites running on the real
rebuilt assets are the cross-check that the buildSrc encoder and app
decoder agree.

**Format (v2)**: ngb_ctx gains `tblob` BLOB — version byte 0x01, then
per target in eff-descending rank order uvarint(id) + uvarint(delta),
delta = eff for the first pair and prevEff − eff after (stable sort:
same-eff ties keep file order, the legacy tie semantics). Ids index a
new NGB-PRIVATE dictionary `ngb_words(id, word)` — ids assigned by
descending row-occurrence count so shared targets get 1-byte varints.
DELIBERATELY NOT words.wordID: rebuildWordsFromSource reassigns
wordIDs (DROP + AUTOINCREMENT, user carry-over rows appended), so
words-table ids are unstable across migration; ngb_words migrates
wholesale with ngb_ctx exactly like ngb_units. Multi-syllable targets
stored space-joined in ngb_words and split at decode — key sequences
stay derived per syllable at fetch (the TAV/TAE hazard). Decoder in
app NgbCodec (encoder mirrored in BuildWordDbTask; NgbCodec.encode
kept for tests/fixtures). Runtime resolution = one int-only IN query
per fetch (<= K ids, word-commit frequency — no resident map).

**Compat**: legacy TEXT `targets` column kept (empty in new builds);
WordDb.ngbContextTargets prefers the blob and falls back to text, so
pre-v2 installed langpacks keep working without reseed. ensureNgbTables
idempotently ALTERs tblob onto old active tables; migration probes
source schema per column (three source generations: pre-display units,
pre-v2 text, v2 blob). Fixed en route: migration was silently dropping
ngb_units.display (canonical unit orthography lost on every langpack
update since the display column shipped).

**Reseed trigger**: computeBuildVersion now folds in an NGB_FORMAT_SALT
("ngb-format-v2") — encoder changes move langBuildVersion even with
byte-identical inputs (without this, installs would keep text-format
active DBs while a future encoder-only change expected blobs).

**VN K400 shipped as default**: TiengVietNgb.txt regenerated from
table_words.tsv at K=400 (ngb_export_jt.py; 8,849 contexts, 1,901,797
pairs — pair count matches the sim's vn_cov_k400 exactly, and every
old K200 row is an exact PREFIX of its K400 row, so top-rank
behavior/pinned tests are unchanged; 4,418 rows deepened). Sim-
validated (rebuild round 2): KSPLS 0.8597, head12 93.56%, no-row
residual 6.14→5.17%. The size objection that HELD it is gone:

  asset                      before          after
  EnglishDb.db (bundled)     18.0 MB         8.9 MB   (same content)
  TiengVietDb.db             24.0 MB        10.0 MB   (K200 → K400!)
  TiengVietDb langpack gz    v9 (K200)       6.5 MB   (v10)
  EspanolDb langpack gz      v11            5.1 MB    (v12, format only)

Catalog bumps: TiengViet.version=10 (K400 + format), Espanol.version=12
(format only, keeps published bytes/manifest coherent). Byte budget
now open for future growth (larger K, EN collocations, ES bigrams).
PUBLISH PENDING: dist/langpacks v10/v12 + manifest need the gh release
upload to langpacks-v1 (Cliff's call, as before).

## Field incident 6: page-pick left composing (Cliff, 2026-08-11) —
## RESOLVED, finalized flag + editor-level harness SHIPPED

Symptom (found while running the crib-sheet recipes): a word picked
from a page-list menu appeared in the field as COMPOSING text; the
next word's output REPLACED it instead of autospacing after it.

Diagnosis (Cliff's): the pick fully resolves its sequence and CLEARS
the key buffer — so the incoming ambiguous keystroke finds no Select
activation and finalize-on-ambig (the normal "seal the previous
composing" trigger) never fires. When the editor still holds the
picked word as a composing region, the next preview's autospace
commitText(" ") replaces the region — the word vanishes. Mechanism
confirmed in the new editor-level harness: without the fix the editor
ends " to" (picked word gone), byte-for-byte the symptom.

Fix (Cliff's design): `pagedPickFinalizedWord` armed by the pick; an
incoming AK with no active sequence/selection re-finalizes that word
(idempotent when the pick already sealed — finishComposingText no-ops;
spacePossible recomputed from the same word). Cleared on consume,
UnDo (pick undone -> re-seal would re-commit), and resetJTUI.

NEW HARNESS (PagedPickEditorIntegrationTest, 7 tests): real JTUI +
ImeTextController + UiUpdateHandler editing a real EditText through a
BaseInputConnection (with synthesized getExtractedText — Base returns
null and the autospace block silently skips without it). Covers: plain
dig-pick + next word, context-commit + pick, zero-K follower pick,
WAKE PULL-IN then page-pick replacement (the un-smoke-tested
paged-selection flow, now pinned), force-head and force-page1 commits
at the editor level, and the lingering-composing reproduction.
Also learned en route: a single press of the NAV-hosting key after a
commit legitimately shows only the P:Navigation row (no word preview
until the second key) — by design, not a bug.

## ES region tags: pair-grounded rebuild SHIPPED (2026-08-11)

The queued cleanup from incident 5. EspanolRegionTags regenerated from
the Kaikki Spanish dump (kaikki-es.jsonl.gz, 91MB, cached in
layout-analyzer/runs/): 1,961 sense-label-contaminated entries -> 179
verified lexical variant pairs (40 ES / 139 LA).

Generation criterion (parse_wiktionary_regions.py, es path): a tag
survives only as half of a RECIPROCAL lexical pair — a clean-register
single-bloc sense of W naming a single-word in-vocab counterpart C,
with C naming W back (ordenador<->computadora, zumo<->jugo; sobornar
does not list aceitar back). Three polysemy guards on top, each built
against a labeled bad-class from the frequency-ranked review:
(1) the grounding sense must LEAD its entry (kills gato "jack", esto,
control "remote", camion "bus", departamento); (2) forms of another
lemma never tagged, plural-of exempt (kills bebe->beber, mas->mas
accented; keeps anteojos/lentes); (3) pooled-frequency cap 2500
(kills cual, dale). Register senses (slang/colloquial/vulgar/...)
never ground evidence — aceitar "bribe", acabada, tio "dude".
Curated: carro;LA allowed back (cart sense leads its entry; the
marquee Tier-2 pair named in SpanishRegion.kt); rostro denied
(register-not-region mislabel; formal "face" is universal).
gafas lost to the plural/form-of interplay — pure recall miss:
lentes;LA + anteojos;LA carry the pair for es-ES users, and LA users
simply never see gafas demoted.

Espanol.version=13; langBuildVersion moves via the regionTags input
hash, so installs reseed. EspanolRegionTest (4): region bits on the
built DB for variant pairs, NO bits for the contamination classes
(absolutamente, esto, gato, aceitar, frijol, coche...), typed "esto"
heads its list under es-419, demoted zumo stays listed. ./jt check
green. NOTE: coche is deliberately NEUTRAL — Wiktionary tags its car
sense [Mexico, Philippines, Spain]; tagging it ES would demote it for
the Mexican users who use it.

## Staging replan: no waiting on Cliff's device (Cliff, 2026-08-11)

Stage (ii) as written assumed Cliff's own field distributions; he does
not use the device daily, and beta testers who DO type extensively are
expected fairly soon and amenable to sharing data. REVISED staging:

- (ii) becomes BETA-TESTER field data, not a gate. Two consequences:
  (a) build a sel_stats EXPORT affordance (ride the existing
  DebugLogShareHelper zip, or a Dev "share stats" action) so testers
  can send distributions with one tap; (b) proceed to (iii)/(iv) NOW
  on sim + Dev force modes.
- KEY REALIZATION making this safe: mechanism A never needed global
  field data to FUNCTION — the ramp is gated per-user on that user's
  OWN counters (pattern detected in their sel_stats -> page-1
  promotion; sustained -> head). Field distributions were only ever
  for setting the DETECTION THRESHOLDS confidently. So: ship the ramp
  with conservative provisional thresholds (sim-bounded + Cliff's
  force-mode feel), Dev-tunable; refine defaults when beta
  distributions arrive.
- Mechanism B is self-limiting by design (equilibrium = inc/(inc+dec))
  and needs no field priors at all — buildable immediately behind its
  Dev toggle, after the theta re-sweep on corrected accounting.

## BOS ("NULL context") prediction row (Cliff, 2026-08-11) — AGREED,
## queued as part of every table build from here on

Cliff's proposal: gather n-gram data for the NULL context — following
a newline/EOS — predicting frequent sentence-STARTING words/phrases.
This context arises at EVERY sentence; arguably the most valuable
n-gram data in the system.

AGREED, and the current behavior makes it strictly additive: TODAY
sentence starts are the prediction DEAD ZONE — ngbDeriveFromText
returns null when preceding text ends in punctuation/empty (no zero-K
window, no block, pure trie), and the in-flow path can carry STALE
cross-sentence context (last word of the previous sentence) until a
reset. Measured opener concentration (2026-08-11):

  register              top-8 window   top-60 pool
  HPLT web (EN, 265k)      24.8%          51.8%
  AAC conversational        43.1%          79.0%

i.e. for the AAC register the intended first word would sit in the
zero-K window at nearly half of all sentence starts ("i" alone is
21.8%). No other single context row approaches this arrival rate.

Design sketch:
- BUILD: one reserved ctx row per language, key "\n" (never a token).
  Targets = sentence-initial word distribution, eff = P(w|BOS) x
  WordsTotal, same coverage-first K cap, varint-encoded like any row.
  Requires sentence-boundary-aware re-streaming (split on .!?\n) —
  which ALSO enables excluding cross-boundary bigrams from ordinary
  rows (separate sim question: measure before dropping them).
- RUNTIME: ngbDeriveFromText returns ("\n", gateOpen=true) instead of
  null when the preceding text is empty or ends in sentence-final
  punctuation/newline (fail-soft rule unchanged: UNKNOWN editor state
  stays null; only KNOWN boundaries get BOS). Enter key + post-Term
  sentence punctuation set it in-flow. Zero-K window then serves BOS
  predictions with sentence-start auto-cap applied by the existing
  case machinery.
- LEARNING: ngb_user works UNCHANGED — ctx "\n" rows learn the user's
  habitual openers (greetings, names); with USER_BOOST this
  personalizes the most-frequent window in the system. Multi-word
  units at BOS (VN greetings, future EN collocations) ride the same
  row format.
- SIM: heldout streams need sentence structure (AAC utterance-per-line
  already has it; HPLT needs the re-stream); measure KSPLS delta +
  zero-K accept rate at BOS states.
- Comma nuance noted, out of scope: mid-sentence punctuation also
  breaks context today; a comma is NOT a sentence boundary and
  arguably should preserve the word context — separate measurement.

ORDER: ES NGB table (next up) builds WITH the BOS row from day one;
EN/VN gain it at their next table rebuild (EN shard already on disk).

## ES NGB table + BOS row SHIPPED (2026-08-12, overnight run)

Spanish gets its first prediction table — and the system gets its
first BOS row. All suites + ./jt check green; EspanolNgbTest (5) and
the EnglishNgbTest BOS-context test pin the behavior.

**Build**: HPLT v2 spa_Latn, shards 1/3/4 counted IN PARALLEL (85k
docs each, the new preferred pattern — language-resources plan) and
merged additively; shard 2 held out (30k docs -> 1.39M sentences,
sentence per line). 160M tokens counted at 95% vocab coverage.
EspanolNgb.txt = coverage-first all-contexts K400: 117,926 contexts /
2.76M pairs incl. the "\n" BOS row (the highest-mass context in the
table: el/la/en/y/no/por...). Header-label colon guard added to the
counter en route ("Re:" was the top raw opener at 601k — forum-reply
boilerplate, not language). EspanolDb.db 12.2 -> 27.7MB (varint;
K200 measured IDENTICAL to K400 over 250k words — K400 kept for the
kr0-residual insurance, same policy as EN/VN).

**Sim (stageb fidelity, ES layout v5, 250k words, shipped weights)**:
  no table (today's ES):   KSPL 1.1125  KSPLS 0.9246  zeroK  0.00%
  table, no BOS:           KSPL 0.9624  KSPLS 0.7999  zeroK 20.10%
  table + BOS row:         KSPL 0.9588  KSPLS 0.7970  zeroK 20.78%
= table -13.5% KSPLS, BOS a further -0.36% (web register; the AAC
opener concentration is ~2x web, so field value should be higher).
TOTAL -13.8% — the largest single-language NGB gain measured
(EN was -11.7%). sls_sim --bos = line-start BOS context.

**JT runtime BOS (all languages)**: ngbDeriveFromText serves the
reserved "\n" context at KNOWN sentence starts (empty field, trailing
newline, sentence-final .!?… — comma-class and unknown state stay
fail-soft null); in-flow boundaries via sentence-final immediate
output (KF_Immed/NoSpace/AllSymbolsPick) and KF_Enter; ¿¡ leave a
standing BOS untouched. BOS commits bump ngb_user ("\n" -> word)
directly — the recognizer's stream has no BOS notion — so habitual
openers personalize the most frequent window in the system. EN/VN
serve an EMPTY pool at BOS until their next table rebuild (queued):
same dead zone as before, never an error.

Espanol.version=14 built; PUBLISH PENDING (Cliff's call, morning).
EN/VN BOS re-streams queued as the next rebuild round (EN shard on
disk; use the parallel pattern + colon guard).

## Thread-5 close-out (2026-08-12)

This thread carried: (1) adaptive substrate + Dev force-enable ladder
(sel_stats EWMA counters, m/d/n state axis, force modes gated to
no-signal head-miss); (2) field incident 6 = page-pick finalized flag
+ the PagedPickEditorIntegrationTest editor-level harness (real
JTUI+ImeTextController+UiUpdateHandler on an EditText — USE IT for
any composing/autospace bug); (3) varint ngb_ctx v2 + ngb_words dict,
EnglishDb 18->8.9MB, VN K400 default at 24->10MB, langpacks v10/v12
PUBLISHED; (4) pair-grounded ES region tags (1,961 contaminants ->
179 reciprocal pairs, v13 PUBLISHED); (5) staging replan — no waiting
on Cliff's device; beta testers + per-user ramp gating; sel_stats
export affordance queued; (6) BOS row design + measurements (AAC 43%
top-8 openers) + runtime serving "\n" at known sentence starts (ALL
languages) + ngb_user opener personalization; (7) first ES NGB table
(coverage-first K400 + BOS, parallel-shard counted): KSPLS 0.9246 ->
0.7970 (-13.8%, the largest single-language gain), zero-K 0 -> 20.8%;
EspanolNgbTest x5. Parallel shard-streaming memorialized as the
preferred corpus-job pattern (language-resources plan).

STANDING STATE: CliffDev = the line, tip a21ca368 pushed. Tablet
loaded with it 2026-08-12 (ES table + BOS live after first IME
start). Espanol v14 langpack BUILT in dist/langpacks, NOT published
(Cliff's call). Kaikki-es dump + all count/heldout artifacts in
layout-analyzer runs/hplt (analyzer tip 5a0e11a: --bos sim,
merge_counts, hplt_heldout, colon guard).

NEXT THREAD STARTS WITH: the EN/VN BOS re-streams are COMPLETE and
VERIFIED (all six tsvs non-empty, BOS rows present: EN "\\n the"
304k, VN "\\n cac" 100k; ~94% vocab coverage; en_bos_s{1,3,4}.tsv
--apostrophes 87k docs each, vn_bos_s{1,3,4}.tsv 120k docs each).
Pick up with:
1. merge_counts.py --min-count 2 per language;
2. export: EN = ngb_export_jt --k 400 vocab EnglishWordsRaw ->
   EnglishNgb.txt (empty units/marginals); VN = same but WITH the
   existing TiengVietUnits.txt-consistent marginals/units inputs
   (targets_words.tsv + units_kaikki.tsv, --k 400, vocab
   TiengVietWordsRaw) -> TiengVietNgb.txt;
3. sanity: "\n" row present, colon guard held (no "re"-class top
   opener), old-row prefix drift EXPECTED this time (new corpus
   sample — re-pin block tests if positions shift);
4. sim stageb --bos both languages vs current tables (en/vn heldouts
   exist; VN needs a sentence-per-line re-stream via hplt_heldout.py
   if only doc-lines exist);
5. JT rebuild + langBuildVersion moves; bump TiengViet v11 +
   English bundled; ./jt check; land on CliffDev; publish v14 (ES)
   + v11 (VN) together on Cliff's go.
THEN the standing queue: mechanism B (theta re-sweep on corrected
accounting first), sel_stats export affordance, A's ramp with
provisional thresholds, EN collocations, doc-boundary Stage-B,
morphological grouping, NGB decay, paged long-string layout.

## EN/VN BOS tables SHIPPED (2026-08-12, Thread-5 close-out executed)

Merges: merge_counts --min-count 2 -> en_bos_merged (4.50M pairs,
"\n the" 914k) / vn_bos_merged (2.66M pairs, "\n các" 251k). Colon
guard HELD ("re" absent from the EN BOS row entirely).

**EN**: wholesale table refresh, raw-word basis as always.
EnglishNgb.txt = 53,788 contexts / 2.80M pairs (was 37,856/854k —
3 shards vs 1). Expected prefix drift, and the new sample is cleaner
(old "the -> driver/car" web artifacts gone). Pinned rows held
(of->the, but->i top; would->i rank 21; also->say rank 113, still a
deep single). EnglishNgbTest BOS test re-pinned: known starts now
serve the opener pool ("the" leads).

**VN — units-basis catch (the important find)**: the hand-off's
"export vn_bos_merged directly" would have DROPPED every
multi-syllable unit target (chúc->"sức khỏe", thành->"phố Hồ Chí
Minh"): ngram_counts emits raw syllable bigrams, but VN tables are
built on ngb_table.py's SEGMENTED stream (ngram_counts has BOS but
no segmentation; ngb_table has segmentation but no BOS). Shipped the
no-regression splice instead: BOS row from the re-stream + the v10
segmented table unchanged — per-row normalization makes cross-corpus
row mixing safe in the exporter. Export verified BYTE-IDENTICAL to
shipped TiengVietNgb.txt + one "\n" row (8,850 contexts / 1.90M
pairs); zero drift, no VN re-pins. TiengVietUnits.txt untouched
(marginals/eff1n1 reproduce exactly; exporter doesn't emit the
display column — do NOT overwrite the shipped file).

**Sim (stageb fidelity, shipped weights, 250k words, K400)**; VN
heldout = NEW sentence-per-line stream, local vie shard 5 (disjoint
from count shards 1/3/4), 30k docs -> 1.62M sentences:
  EN old table:      KSPL 0.9632  KSPLS 0.7943  zeroK 19.14%
  EN new + BOS:      KSPL 0.9471  KSPLS 0.7811  zeroK 21.01%  (-1.66%)
  VN v10 table:      KSPL 1.1957  KSPLS 0.9304  zeroK 17.46%
  VN spliced + BOS:  KSPL 1.1906  KSPLS 0.9264  zeroK 17.66%  (-0.43%)
(Full raw-basis VN rebuild measured 0.9242 (-0.67%) but loses unit
targets — not shippable. The residual -0.24% needs BOS support in
ngb_table.py + a segmented re-stream of vie 1/3/4: QUEUED.)

Catalog: TiengViet.version=11 (BOS row); English bundled (Ngb.txt
content hash moves langBuildVersion automatically). All *NgbTest
suites green. PUBLISH PENDING (Cliff): v14 (ES) + v11 (VN) together.

## VN segmented BOS rebuild = TiengViet v12; shard-5 heldout was
## CONTAMINATED (2026-08-12, same day)

Cliff's call: make the tools sound rather than keep the splice. Done:

**Tooling (analyzer 08c97d8)**: ngb_table.py gained the BOS opener row
on the segmented basis (ngram_counts semantics; colon guard widened to
the opener SEGMENT — "Thể loại:" boilerplate 579 -> 8 in smoke) +
--max-docs; merge_counts gained --marginals (2-col additive). Smoke:
ordinary rows + marginals byte-identical to the old tool.

**Re-stream**: vie shards 1/3/4 x 120k docs through ngb_table.py in
parallel; merged min-count 2 = 4.11M segmented rows incl. BOS. Export
(existing targets_words + units_kaikki -> units eff1n1 reproduce
EXACTLY; TiengVietUnits.txt untouched) = 8,314 contexts / 1.27M pairs.
BOS row now credits unit openers ("tuy nhiên" top unit at 286k).

**THE EVAL CATCH — worth remembering**: the first sim run put the
rebuild at 0.9322, WORSE than the splice (0.9264). Root cause: the
vn heldout I'd cut this morning came from shard 5 — and ngram/plan.md
records the v10 table as a FULL-SHARD-5 count (4.14M docs, 1.96B
transitions vs the rebuild's 195M). Train/test overlap was inflating
every old-table number by ~1-2%. Clean shard-2 heldout (30k docs,
1.23M sentences, runs/hplt/vn_heldout_s2.txt — disjoint from BOTH
generations), shipped weights, 250k words:
  v10 (no BOS):        KSPLS 0.9240
  v11 splice:          KSPLS 0.9207   (-0.36%)
  v12 segmented+BOS:   KSPLS 0.9166   (-0.80% vs v10)
The 360k-doc 3-shard segmented table BEATS the 4.14M-doc single-shard
v10: shard diversity + one basis > 10x volume. The full-shard-5
segmented recount considered as a fallback is NOT needed.

All *NgbTest suites green unchanged (chúc->mừng etc. ride the unit
remainders, same as v10). TiengViet.version=12. VN sim heldout
STANDARD from here: vn_heldout_s2.txt (shard 2, like EN/ES).
v12 langpack build + publish: pending Cliff (v11 published this
morning is superseded).

## Theta re-sweep (corrected accounting) + mechanism B BUILT
## (2026-08-12, same thread)

**Re-sweep** (v12 table, clean shard-2 stream, corrected Select
accounting; analyzer runs/hplt/conf_sweep_v12.txt): the DKWF ladder
shifted one notch DOWN vs the pre-correction sweep — DKWF=1.0 optimum
now θ≈0.45 (was 0.65), DKWF=2.0 optimum now θ=0.65 (was 0.90-or-off).
θ=0.65 STAYS the default, now as the DKWF=2.0 (distraction = 2
keystrokes) conservative read; the [0.45, 0.90] clamp is now measured
(0.45 = DKWF=1 optimum, 0.90 near-silent 0.087 notif/w). At the 85%
precision default the equilibrium sits ≈0.77 (top-hit 84–87%,
~0.2 notif/w). Model refit on the v12 emission (conf_train/eval_v12,
1.3M states/shard): eval AUC 0.8775, calibrated; JT DEFAULT_WEIGHTS
updated (conf_weights_5_v12.json — margin/keys_rem still add nothing).

**Mechanism B (adaptive theta_signal) BUILT** per the final proposal:
- NgbConfidence owns theta; fire events resolve at commit — typed
  PAST the fire -> +inc (even if the same word commits later: the
  fire didn't stop them); accepted the fired top at the fire state ->
  -dec; picked a LOWER word there -> neutral. inc = dec·p*/(1-p*),
  dec = 0.005, clamp [0.45, 0.90]. Weight-SGD FROZEN while adapting
  (drift attributable to one mechanism). Theta persists as a "theta"
  row in ngb_conf (per-language, rides importWeights/exportWeights;
  fresh language seeds from the Dev slider).
- Theta only adapts when the signal is USER-VISIBLE (enabled +
  adaptive): with it hidden, "typed past the fire" carries no
  information.
- Settings: user-facing "Signal Accuracy Target" slider (60–95%,
  default 85, Cliff's phrasing) on main (both surfaces via registry);
  raw threshold slider MOVED to Dev (registry developer page + the
  legacy activity, the parity-gotcha pair) + "Adaptive confidence
  threshold" Dev toggle, default ON.
- Tests: NgbConfidenceTest +7 (step math, ratio, clamp, freeze,
  no-fire inertia, persistence round-trip); threshold-20 fixtures pin
  adaptive OFF (the seed would clamp 0.20 -> 0.45 otherwise).

### Mechanism B theta rules CORRECTED (Cliff, 2026-08-12, same day)

The as-built "typed past the fire -> +inc" rule was WRONG (Cliff was
explicit): typing past a fire proves nothing — too fast to react,
distracted, who knows. Theta moves ONLY on confirmed evidence, each
fire resolved against the word the user FINALLY COMMITS:
  (a) committed == the fired prediction -> theta down (dec), even if
      the user typed past the fire first;
  (b) committed != the fired prediction -> theta up (inc) — a
      confirmed misfire (this also supersedes the old proposal's
      "lower-word pick = neutral" case);
  no commit (abandon/delete/field change) -> no adjustment.
Multiple fires in one word each resolve independently (misfire then
correct fire = one inc + one dec). Equilibrium math unchanged:
P(correct|fire) settles at inc/(inc+dec) = p*. Implementation now
resolves against signaledTops directly (the distracted-counter
ledger) — no keystroke-position logic. Tests re-pinned (+abandon,
+two-fires cases).

## sel_stats export affordance SHIPPED (2026-08-12, same thread)

Both channels from the staging replan, one implementation
(SelStatsExport, content-safe by construction — counters carry no
typed text): (1) SelStats.tsv now rides EVERY Submit-Feedback debug
zip (DebugLogShareHelper gained text entries; stats-only shares work
even with no logs on disk), so beta testers send distributions with
the tap they already know; (2) Dev "Share" button beside the stats
readout shares the same TSV as plain text (tiny payload, no file).
Format: timestamp + app-version headers, then lang/bucket/weight
rows (raw EWMA — the analyzer aggregates). SelStatsExportTest x2.
NEXT in the standing queue: mechanism A's ramp with provisional
thresholds (Dev ladder's "Adaptive" rung), EN collocations,
doc-boundary Stage-B, morphological grouping, NGB decay, paged
long-string layout.

### Mechanism B v2: SHADOW-THETA PLACEMENT replaces the walk
### (Cliff + Claude, 2026-08-12, same thread)

Cliff's prediction-history idea, refined past its one-sided-evidence
flaw (the committed word is top at sub-theta p-hat in nearly every
word — naive "could have fired earlier -> reduce theta" would drag
theta to the floor). Instead: at every learning commit the word's
snapshots are replayed against the WHOLE candidate grid (0.45..0.90
step .05) with the live fire-suppression rule (first crossing fires,
quiet until the top changes), and each would-fire is CONFIRMED
correct/incorrect by the committed word. Per-bin EWMA (fires,
correct) masses (decay .999 ~ 1000-word window) are THE state; theta
is DERIVED at read time: lowest bin with >=25 weighted fires whose
observed precision meets the user's target; evidenced-but-all-failing
-> quiet top (0.90); no evidence -> seed from the sweep-measured
precision-per-threshold table (p*=85% -> 0.80).

Why it beats the walk (retired same day it shipped — clean & simple
per Cliff): evidence needs NO user reaction (the commit itself
confirms every would-fire), so counters accrue with the signal
HIDDEN — placement is often already personalized when the user first
enables the feature; every word contributes (the walk learned only
from ~0.1-0.2 fires/word — the high-target slow-settling caveat is
gone); precision-target changes re-place INSTANTLY (theta computed at
read time, no reseed). SGD freeze rule unchanged (frozen while
adaptive ON). Persistence: counters ride ngb_conf as sh{45..90}_{f,c}
rows per language; old "theta" rows orphaned harmlessly. Dev raw
slider = fixed override when adaptive OFF (no longer seeds anything).
NgbConfidenceTest: placement suite (seed, floor, ceiling, target
re-place, suppression replay, abandon-inertia, persistence).

## Mechanism A RETIRED -> Word-list-style modes SHIPPED
## (Cliff's redesign, 2026-08-12)

Cliff's ruling: DDP-vs-WYTIWYG is a PREFERENCE AXIS, not a hidden
state to infer — adaptive FTS promotion would be "unpredictable,
confusing, unsatisfactory" (the measurement history agreed: blanket
FTS tier +30-49%, gated bubble-up negative). Replaced by four
DETERMINISTIC user-chosen modes ("Word List Style", registry Choice
on main, both surfaces, default predictive):

  mode                      EN KSPLS   vs (d)    VN KSPLS   vs (d)
  (d) predictive (default)  0.7827     —         0.9166     —
  (c) steady first word     0.8369     +6.9%     1.0515     +14.7%
  (b) steady start (2)      0.9105     +16.3%    1.2149     +32.5%
  (a) classic (fixed SLS)   1.0985     +40.3%    1.5447     +68.5%
(sls_sim --fsls-anchors + --block-order interleaved, clean heldouts,
250k words; VN costs more: short syllables make 1-2-key FTS
ubiquitous. Old policy-(ii) 3.5% figure was PRE-NGB — with
predictions live, anchors displace real value.)

Mechanics: steady N = the top-N rows of the CLASSIC order (FTS first
then partial, shared-metric within groups) pinned ABOVE the ngb
block at the final merge; anchors beat block duplicates (the trie
row is kept, the block entry yields). Classic = strict certainty-
block partition FORCED + predictions off. KEY_NGB_PREDICTIONS is now
DERIVED (style != classic) via a SettingsRepository.putString
write-through + an ensureAll reconcile — every internal read, test
fixture, and enabledWhenKey gate (the Classic graying Cliff asked
for) works unchanged; the old Word Predictions toggle is replaced by
the drop-down. Dev partition slider stays live for non-classic
styles. GRAYING NUANCE (flagged to Cliff): confidence enable grays
under Classic; the action/accuracy rows gate on confidence-enable
(single-gate framework), so they gray only when that toggle is off.
Zero-K window untouched by anchors (no sequence = no classic order).
WordListStyleTest x4 (derived key, block-vs-anchor structure via
sortmetric tags, classic silence audit, FTS-first anchors). The Dev
select-behavior ladder stays as a Dev tool; its "Adaptive" rung is
permanently a no-op. Residual mechanism-A idea (behavior-triggered
settings SUGGESTION toast) deferred: manual-first; sel_stats export
data decides if it's ever worth building.

## Cold-start FTS floor SHIPPED (Cliff's "a below and", 2026-08-12)

First-run report: cold "a" buried under once-used "and", cold "to"
under "the". DIAGNOSIS (probe-verified): the ~352-point NEVER-USED
recency gap (r7 vs r0 — the once-used word's edge; Cliff's actual
case) plus the within-band continuous extension (up to ~17 points —
the BOS "the over to" case). Cliff's proposed useCount seeding maps
to the wrong term (useMetric is zeroed for fc1); the operative term
is recency.

THE RULE (precise definitions from existing machinery): a word with
freqClass 1 (own unigram band, raw > 40k) + keysRemaining 0 (fully
typed) + useCount 0 (never used) gets a -380 floor (dominates
recency 351.9 + max extension ~17). Self-retires per word at first
real use — from then on the user's own data rules. One site
(computeSortMetrics) covers trie + block; GOTCHA fixed en route: the
block's synthesized freqClass is CONTEXTUALLY banded (M_c x eff), so
eligibility checks the word's OWN band or junk openers like
BOS->"th" (eff-banded fc1, raw f9) ride the floor to slot 1.
Sim cost (sls_sim --cold-fts-floor 380): EN 0.7827->0.7828, VN
0.9166->0.9170 KSPLS (<=0.04%) — free. KNOWN TRADE (accepted): a
cold fc1 FTS word holds slot 1 at its exact sequence even against a
heavily-used longer word until ITS first use; revisit only if field
feedback objects. HARNESS GAP noted: Robolectric clock frozen at 0
-> lastUseTime 0 == "never" -> recency effects invisible in tests
(the used-once burial can't be unit-tested; the extension case can).
ColdStartFtsFloorTest x3; SelectBehaviorEndToEndTest head-miss
fixtures now warm their target word first (floored cold words head
their lists — no miss exists to exercise).

### Field-entry NGB context funnel (Cliff's "the never recovers at
### BOS", 2026-08-12 evening) — FIXED

Device symptom: at sentence start, To + trie junk (Th/Ro/Po...) and
"the" NOWHERE, however often used; mid-sentence fine. Signature =
NULL context (empty pool; "the" has no trie row at 2 keys — MQC
starves it; its list presence always came from the pool). Root
cause: context reconstruction ran only at the pull-in and
delete-word funnels — a field entry with NO producible word at the
cursor (empty field, cursor after a space / sentence-final ". ")
returned early from the pull-in attempt and left the context null.
Every fresh-field sentence start was a dead zone; in-flow BOS only
covered boundaries typed within the session. Fix: field-entry
funnel (ImeTextController.reconstructNgbContextAtCursor) called
from the onStartInput no-word branch and both deferred-first-update
non-pull-in branches — BOS at empty/post-sentence entry, previous
word at mid-text entry, fail-soft null unchanged (no IC, comma).
Editor-level test in PagedPickEditorIntegrationTest (BOS, empty,
mid-text, comma). NOTE: the earlier BOS probes all called
ngbReconstructContext directly — the funnel gap was invisible to
every unit test; only device use surfaced it.

### BOS dead zone round 2: EDITOR-CHURN resets were wiping the
### context everywhere (Cliff device session, 2026-08-12 evening)

Even after the field-entry funnel, Cliff's BOS lists stayed dead —
after ENTER and after a PERIOD (both in-flow paths DO set BOS).
Device forensics (pulled the ACTIVE DBs): static "\n" row healthy
(1,257-byte blob); CustomDb ngb_user had ZERO "\n" rows and the
recognizer chains crossed his sentence boundaries — the context was
never BOS at any commit. Root cause: resetJTUI -> ngbClearContext()
nulls the context, and on a REAL device editor churn resets
constantly: (a) handleSameFieldResume (screen wake, app switch,
editors restarting input — the COMMON entry) reset without
reconstruction; (b) processSelectionChange's external-relocation and
composing-cleared branches reset after the IME'S OWN edit (the
newline/period moves the cursor) — in-flow BOS was set and wiped
within milliseconds. None of this is visible in Robolectric: no real
editor churn — the funnel probes all passed while the device stayed
broken.

Fix: reconstructNgbContextAtCursor() (text-derived, idempotent,
fail-soft) now runs at EVERY reset-without-pull-in exit: same-field
resume, onStartInput no-word + deferred branches, deferred-retry
fail/skip, and all four processSelectionChange reset exits. The
derivation is stateless from text, so over-calling is harmless.
LESSON (recorded twice now, stronger): the NGB context must be a
DERIVED value from editor text at every stabilization point, never
state that must survive churn. If another dead-zone report arrives,
audit remaining resetJTUI callers.

### BOS dead zone round 3 — CLOSED, device-verified (2026-08-12 late)

Round 2's churn fixes were still not enough on device. Live NGB_TRACE
forensics (logcat, debug builds — JTUI.ngbTrace + caller chains)
found three more killers, each invisible in Robolectric:
(1) STARTUP RACE: the editor attaches while JTUI is initializing —
onStartInput takes its not-ready branch, sets flags, and never
re-fires while the user stays in the field: ctx null for the whole
session (this alone explained every "fresh install still broken"
round — each reinstall restarts the process and re-races). Fix:
deferred field attach — reconstruct at the Ready transition when
currentInputStarted.
(2) onWindowHidden's reset (Enter in single-line fields hides the
window) with no re-derive on re-show. Fix: reconstruct in
onWindowShown.
(3) processSelectionChange's BOTTOM branches (4skip-noauto / 4aa
cursor-not-touching-word / 4c2) reset-and-return with no repair —
4aa fires ~130ms after the IME's OWN period/Enter output lands,
wiping the just-set in-flow BOS. Fix: reconstruct at those three
exits too.

Verified live on Cliff's tablet: boundary -> orphan reset ->
re-derive within 1ms, every cycle; committed To/The/And/... from BOS
lists; closing trace: "all I can say is whoopee!". The NGB_TRACE
instrumentation is kept (debug-gated, logcat-only) — it took three
rounds of guessing to one evening of seeing. Invariant now enforced
in practice: EVERY reset-without-pull-in path re-derives the context
from editor text.

## Thread-6 close-out (2026-08-13)

TERMINOLOGY (Cliff): the primary statistic is now called **KPC —
Keystrokes Per Character** (identical definition to the old KSPLS:
(ambiguous keys + Selects) / (letters + the auto-emitted space per
word); corrected accounting, every commit >= 1 Select). Old logs/
artifacts that say KSPLS are the same number. sls_sim stageb now
prints a kpc header + a pos1 column (share of typed-word commits at
slot 1).

**THE HEADLINE TABLE** — classic fixed-SLS (predictions off, strict
blocks: where JT started) vs today's full predictive stack (v-latest
tables + BOS + interleave + unified metric + shipped weights), same
clean heldouts, 250k words each:

  lang  KPC classic  KPC predictive  saved   (classic costs)
  EN       1.0985        0.7827      -28.7%     (+40.3%)
  VN       1.5447        0.9166      -40.7%     (+68.5%)

  KSPL: EN 1.3321 -> 0.9491 (-28.8%); VN 2.0028 -> 1.1746 (-41.4%).
  Zero-keystroke commits: EN 21.0%, VN 17.7% of words (0% classic).
  pos1 (typed commits at slot 1): EN 56.5 -> 50.8%, VN 48.9 -> 51.6%
  — roughly flat by design: the win is committing EARLIER (fewer
  keys, zero-K window), not deeper list positions.

**THREAD-6 DELIVERED** (all on CliffDev, all device-verified):
EN/VN BOS tables (EN 3-shard rebuild; VN v12 segmented-basis rebuild
after the ngb_table BOS work + the heldout-contamination catch);
ES v14 + VN v12 langpacks PUBLISHED; theta re-sweep on corrected
accounting + mechanism B shipped twice (walk -> shadow-theta
placement, confirmed-evidence rules); sel_stats export affordance;
word-list-style modes (Classic/Steady1/Steady2/Predictive) replacing
Mechanism A; cold-start FTS floor; the three-round BOS dead-zone hunt
(startup race / window re-show / bare selection-change resets) closed
with NGB_TRACE live-forensics instrumentation kept in debug builds.

**NGB v1 IS ON dev** (a7334f8f): CliffDev merged into dev clean
(textually zero conflicts; two semantic catches fixed en route:
dev's UiUpdateCallbacks gained updateJtColumnViews — stubbed in
PagedPickEditorIntegrationTest — and dev-only NgbLearnedRankTest
re-pinned chúc->thọ to chúc->thầy, a v12 table-drift re-pin). Full
./jt check green on the merge. dev merged back into CliffDev: BOTH
BRANCHES IDENTICAL at a7334f8f — dev demonstrates the NGB stack;
CliffDev continues as the experiment line.

STANDING QUEUE (next thread): EN collocations (Kaikki multi-word
units) is the recommended next engine item; then doc-boundary
Stage-B, morphological grouping, NGB decay, paged long-string
layout. Deferred-awaiting-field-data: mode-change nudge, word-list-
style wording pass, recency defaults, ES region-tag rebuild (needs
ES/LA pair data). Older backlog: VN add-new-word custom units,
type-N case-learning gap, visual pass, TAV sim variant, multi-word
ctx + LLM-oracle campaigns, literacy modules, on-device DKWF
(shadow-theta v2). Open-source track: Leipzig CC BY-NC replacement,
EN DB provenance, OpenBoard split-out.

## Thread-7 close-out (2026-08-13) — EN collocations SHIPPED

English now has the multi-word unit machinery Vietnamese has:
**EnglishUnits.txt** (6,273 Kaikki collocations + displays) and
**EnglishNgb.txt** rebuilt on the SEGMENTED basis (ngb_table.py
--apostrophes, BOS, colon guard; same 3 x 87k-doc HPLT shards as the
BOS table; 53,770 contexts / 2.81M pairs K400). Bundled — reaches
installs via the langBuildVersion content hash. EnglishDb.db 16.47 ->
17.22MB (+4.6%, inside the ~18MB tolerance).

**PIPELINE** (analyzer 74672b0, all steps are tools now — the VN
inventory's extraction script was never kept): kaikki_units.py
`extract` (kaikki-en.jsonl.gz, 502MB, cached in analyzer runs/; 1.49M
entries -> 220,904 candidates: 2..5 space-separated components, each
an in-vocab tokenizer-clean word) -> `filter` grounds candidates
against the raw bigram shard TSVs (adjacent-pair count/df/NPMI floors;
for 2-word units the pair count is exact, for longer it is an upper
bound — "at the first" 96k is really the "the first" pair). Extractor
regression-checked against shipped VN units_kaikki.tsv (clean
superset, +352 proverbs).

**THE INVENTORY FINDING — scaffolding units hurt, measured** (stageb
--bos --block-order interleaved, topk 400, init-defaults row; web =
en_heldout_ap 250k words, AAC = aac_heldout_ap):

  inventory                        web KPC     AAC KPC    zeroK(web)
  baseline (no units, BOS table)   0.7828      0.8261     21.01
  mod   15,527 (npmi>=0.05)        0.7848 +0.26%  0.8370 +1.3%   20.43
  cons   7,433 (npmi>=0.2)         0.7842 +0.18%     —           20.51
  lex    6,273 (cons minus cheap)  0.7834 +0.08%  0.8262 +0.01%  20.74

(baseline re-run this thread = 0.7828 vs recorded 0.7827 — tool drift
since the kpc rename; all comparisons same-tool.) Function-pair units
("to the", "of a", "i am", "there is", "has been") steal zero-K window
slots and follower-row mass wherever the unit does NOT match — worst
on the conversational register (AAC zeroK 18.6 -> 16.3 under mod).
**lex cut**: drop a unit iff EVERY remainder word is a top-125 vocab
word (cheap continuations — the fused commit saves nothing "am"/"been"
cost 1-2 keys anyway); the 1,160 dropped units carried 2.09% of stream
mass, nearly half of all unit mass. mod/cons REJECTED by measurement.

**SHIP RATIONALE**: lex is KPC-NEUTRAL (+0.08% web / +0.01% AAC, pos1
+0.1/+0.3pt BETTER) and buys the machinery: 2-for-1 phrase commits,
1N1 remainders (type "united" -> "states" tops the pool), canonical
proper-noun display, C3 span pull-in for phrases, recognition learning
of the user's own phrases (ngb_user units). KPC never priced those.
Units are lexical value, not corpus-KPC value — VN's KPC win came from
units being WORDS; EN was never going to repeat it.

**DISPLAY DECISION** (the exporter display-column question): JTUI
display==output for overrides, so a bad display CORRUPTS commits.
Kaikki canonical case kept ONLY when it capitalizes >=2 non-article
words (1,110 units — New York / White House / United Nations class);
everything else falls back to per-word case stats, which EN already
reconstructs well ("I am", "the Hague", "the UK") — and which defuses
proper-noun hijacks of common strings ("The Game", "A team" were
Kaikki-canonical for "the game"/"a team"). ngb_export_jt.py passes
units col 5 -> Units.txt col 4; BuildWordDbTask already read it.

**JT SIDE**: LanguageTraits.syllableBased RENAMED hasUnits (nothing
gated on it; it would have flipped TRUE for English and lied to the
first fork that trusts it). EN traits now (hasUnits, no tone keys);
EnglishNgbTest pin updated + 3 unit tests added (canonical display
in-pool, 1N1 remainder after AK-commit, per-component usage through a
case-fixed row). All NGB machinery ran EN units UNCHANGED — zero
runtime code changes beyond the rename.

**RE-PIN + fixture gotchas paid for**: (1) SelectBehaviorEndToEndTest
EN-5 (in/situ): "in situ" is a UNIT now — its fully-typed 1N1
remainder heads the "in" list, the head-miss state no longer exists
(the data does what force-page1 wanted); re-pinned to ctx "the".
Recipes doc positions predate the segmented table — re-derive next
residual run. (2) Select only PREVIEWS in fixtures — the commit lands
on AK-after-SEL; nextKeyCount=0 never commits. (3) wordUseCountForTest
reads getOrCreateStats CASE-EXACTLY: for case-fixed rows assert the
stored form ("York"), or the helper itself fabricates the lowercase
orphan. (4) Selection stepping needs LIST mode (paged renders
column-major).

NOTICE gained EN collocations attribution (Wiktionary CC BY-SA +
HPLT CC0) and the same for VN units — that gap predated this thread.
./jt check green. Device smoke pending device availability.

STANDING QUEUE (unchanged order): doc-boundary Stage-B, morphological
grouping, NGB decay, paged long-string layout; deferred-awaiting-field
items and older backlog as in Thread-6.

## Thread-7 post-script (Cliff, 2026-08-13): PARKED, CliffDev reset

Cliff's review of the KPC numbers: PARK the collocation additions.
CliffDev was reset to 34216328 (= dev, byte-identical parity) so
subsequent CliffDev work merges to dev WITHOUT the collocations. The
complete work (units + segmented table + traits rename + tests +
NOTICE) is preserved on branch **en-collocations-thread7** (3a00ca9e)
— restore later by merging that branch. The analyzer tooling
(kaikki_units.py etc., analyzer 74672b0) stays — it is inert without
the JT data files. The select-recipes doc note about EN-5 no longer
applies (the shipped apostrophe K400 table is back; in/situ
reproduces again).

Cliff's reading, confirmed: (1) NGB context keys are SINGLE words —
collocations were single-word context -> multi-word target (plus the
1N1 remainder source and BOS unit openers). (2) The numbers say
storing [word1=>word2] + [word2=>word3] beats [word1=>"word2 word3"]
(at least for now): the fused targets split follower-row mass and
crowd the zero-K window wherever the unit does NOT match.

**HYPOTHESIS TO EXPLORE (Cliff) — move the split point into the
context, not the target**: from "salt and pepper" / "salt of the
earth" / "salt in the wound", store [salt and => pepper],
[salt of => the earth], [salt in => the wound] rather than
[salt => and pepper] / [salt => of the earth] / [salt => in the
wound]. The cheap connective belongs in the CONTEXT KEY (it is
nearly free to type and highly ambiguous as a prediction), and the
expensive content tail is the prediction. This refines the queued
multi-word-context experiment (ngram plan "NGB-D extensions" item 3):
context = last TWO words where the pair is a collocation head, target
= the tail (possibly still multi-word). The Kaikki inventory,
kaikki_units.py, and the segmented ngb_table tooling all reuse for
this — only the table keying changes. Also consonant with the lex-cut
finding: units whose REMAINDER was cheap hurt; here the cheap words
move into the key instead of being predicted.

## Center-square surface + resting-state paging (Cliff, 2026-08-13)

Two UI directions from Cliff's demo-prep review. (A) is BUILDING NOW;
(B) is CAPTURED for a later sim round.

**(A) Center square = live provisional-text surface** (in progress,
this thread): the Main page's center square drops the static "Main"
label and mirrors EXACTLY what is provisionally committed at the
insertion point (WYSIWYG applied case), one surface, four states:
(EMPTY) composing null — BOS, page-list display, post-page-pick,
slot-1-is-function-icon; (NEUTRAL) typing, provisional word shown;
(SIGNAL) confidence fired — dramatic styling (straw man: light-gray
bg, max-fit font, dark-green text) — the fired word IS the surface's
word, so the notification is a styling escalation, not a new widget;
(ARMED) Select pressed >=1, no page display (straw man: light-green
bg; pair color with a non-hue cue; theme-relative colors, join the
queued visual pass). Render state must be DERIVED at update time
(BOS-dead-zone lesson — no maintained flag). Scope: main-page typing
states only (center square keeps its role on other pages). Signal
economics note: a glanceable persistent cue is cheaper to check than
beep/flash — expect the precision/theta feel to shift; shadow-theta
self-places, sel_stats is the instrument. Select-key page-group icon
(2x3 dash grid when the next Selection-list object is a page group):
Cliff is producing the icon in a separate thread; wire it when it
lands.

**(B) Resting-state paging — NOTED, sim before build.** The two
resting zero-K states (BOS; post-page-pick — after AK-after-SEL the
user is already mid-word, so these are the ONLY resting states) could
open directly as page groups ("all-pages": slots 1-6 at 2 keystrokes,
7-12 at 3) instead of two list slots + pages. Measured cold (opener
slot distribution vs the shipped BOS row, sel_cost approximation):
top-8 covers AAC 25.7% / web 22.9% of sentence openers; E[keys|top-8
opener] current 2.17/2.84 (AAC/web) vs all-pages 2.00/2.27, plus
slots 9-12 become reachable (~+3.5pt coverage). Order ~0.3-0.5% KPC —
worth a real sim. CAVEATS THE SIM MUST MODEL: (1) personalization —
AAC "i" = 21.8% of openers sits at slot 2 cold; ngb_user opener
learning pulls a user's dominant opener to slot 1 where current cost
is 1 keystroke vs all-pages 2 — run WITH the learning loop, cold
numbers flatter all-pages; consider the hybrid (slot 1 stays a list
entry, pages from slot 2); (2) rank pages by P x KEYSTROKES-SAVED,
not raw P (the collocation-round lesson: predicting cheap words is
worthless) — Cliff's "seed with expensive words"; (3) fixed/rote page
option belongs under Word List Style (predictive = live-ranked,
steady = frozen order; rote motor memory vs adaptive reordering is
the DDP/WYTIWYG axis again); (4) SWITCH/SCANNING users: Select is
always FIRST in the scan, so sequential Select-stepping is
disproportionately cheap while a 6-way page pick needs targeting —
KPC's equal-keystroke assumption breaks; there is an adjustable
"extra scan delay" on the first key scanned which may need extending
so the user can read the whole list before acting. Mode, not global.
Sim shape: sls_sim bos_page_mode variant in list assembly + sel_cost,
AAC heldout, learning on, {current, all-pages, slot1+pages} x
{P-ranked, savings-ranked}. An AK before any Select reverts to the
standard format at zero cost either way.

## Family expansion — Select-then-pause page group (Cliff, 2026-08-13; BUILT)

The long-word inflection problem: NGB/trie offers "intellect", the user
wants "intellectuals" — reaching it costs typing nearly the whole word
(the old workaround: commit, emit autospace, double-UnDo pull-in, edit
the sequence — unacceptable). SOLUTION shipped this thread, riding the
deferred-case-expansion pause (the established Select-then-pause
gesture): pausing on a selected word whose completion still needs >=
min-kr keystrokes inserts a PAGE GROUP of "words that start like this
but end differently" — the frequency-ranked vocabulary words sharing
the letter stem (word minus its last M letters). Not-strictly-
grammatical family members are a feature, not a bug (Cliff: the goal
is "long word, same start, different end"). The word's own collapsed
alternate case form leads the group, so case costs one extra
keystroke ONLY when the word is also family-expandable; an
unintentional pause costs one Select to step past the group —
identical shape to the old single-row case insertion.

MECHANICS: WLD.wordsWithLetterPrefix walks the trie down the stem's
key sequence and letter-filters the ambiguous subtree (phrases and
accent-fallback duplicates excluded); entries build through the
standard buildDisplayEntries path (case prefs, POS, preserve-case).
In paged mode the group splices at the PAGED-REGION START, so it
renders as a clean page-1 group and later pages shift by exactly one
Select; in list mode it splices directly after the paused row. Pale
blue binds trigger to result: expandable rows tint pale blue in the
list region, the inserted members carry the same tint (selection
green draws over; dark-mode variant included). Eligibility: single
words only (X/L/E/2/B + single-word N; units and functions excluded),
no open page display, family expansion NEVER fires without a nonzero
pause delay (unlike case expansion, which keeps its immediate mode).

DEV DIALS (registry developer page + legacy activity, both surfaces):
"Family Expansion" enable (default OFF; separate switch from deferred
case per Cliff's vote), "Required Remaining Keys" (2-8, default 5),
"Ignored Trailing Letters" (0-8, default 5); the pause reuses the
Case Expansion Delay slider. FamilyExpansionTest x5 (insert + stem
membership + page-boundary position, selection survival + AK
abandonment, disabled, kr floor, no-delay).

OPEN QUESTIONS for the dial-twiddling: (1) stem breadth — backoff 5
on a 9-letter word gives a 4-letter stem ("inte" -> hundreds of
candidates; only the top 6 by frequency show; smaller backoff = 
tighter family); (2) >6 candidates currently truncate to one page —
second page group deferred pending Cliff's field feel; (3) candidates
bypass the vocabulary-mask/min-frequency filters (trie membership
only) — revisit if junk surfaces; (4) VN: stems cross syllable
boundaries untested — language-dependent dials anticipated.

## Family expansion round 2 (Cliff's field report, 2026-08-13; BUILT)

Cliff's first device session ("working better than I have any right to
expect") produced six refinements, all shipped:

(1+3) THE "satellite" REPORT WAS A STRUCTURAL ARTIFACT, NOT A FILTER
BUG: with fewer than 6 family members, the partial page window
absorbed ordinary same-key candidates from the old list ("satellite"
shares every typed key with "intellectuals" — i/s, a/n one key each;
"intellect(ual)" likewise vs "intelligence") and the line tint spilled
onto them. Fix: ALL stem matches are presented (as many page groups as
needed, 60-candidate pathological-stem guard) and the group is padded
to WHOLE pages with inert blue pads (picking a pad beeps). Letter
filtering itself was verified correct.
(2) Pausing on slot 1 inserts the group AT SLOT 2 for immediate
selection — the listed region ends at the paused row while the group
is open (familyListedRows override on pagedFirstRow; cleared on any
rebuild), so the old slot-2 word is pushed below the inserted pages.
(4) Eligibility (trigger AND blue advertisement) now requires a
non-empty typed sequence — resting menus (BOS, startup context) meet
the keys-remaining floor trivially and stay plain.
(5) English/Spanish only (language gate in eligibility); VN stem
semantics across syllable boundaries deferred.
(6) DECOUPLED from the case feature: "Family Expansion" enable +
"Family Expansion Delay" slider (1.0-3.0s, default 1.5s) live on the
LANGUAGE OPTIONS page (registry, both surfaces); the capitalized-forms
delay in Keyboard Setup is untouched and the two timers are
independent (family-eligible words wait the family delay with the
case form riding the group; case-only words keep the case delay
including immediate mode). Dev keeps the min-kr + stem-backoff dials.
FamilyExpansionTest x6 (whole-page pads, slot-2 position, own-delay
decoupling, disabled, kr floor, resting-menu gate).

## Family expansion round 3 (Cliff, 2026-08-13; BUILT)

(1) PAGE ORDER = word length shortest-first, ties by trie DISCOVERY
order (replaces frequency ranking). Cliff's positional-consistency
theory CONFIRMED for this trie: children sit in fixed key-number
order, so the relative order of two suffixes ("-ed" vs "-ing") is
decided by the suffix letters' own key numbers, independent of the
stem — parallel families land their forms in the same relative
positions. Any FIXED child order suffices (Cliff's note); the
traversal now walks forward key order (matches the key faces) and
kotlin's stable sort preserves discovery order within a length.
(2) NEW-MEMBER GATE: the blue flag (and the trigger behind it) now
runs the expansion search first and requires at least one family
member NOT already present in the selection list — "horticulture/
horticultural both listed, both blue, expansion buys nothing" is
gone. Cached per word per list generation (cleared on rebuild), so
the render path pays the trie walk once per word per list.

## Page groups are MENUS — provisional text unchanged while paging
## (Cliff, 2026-08-13; SUPERSEDES issue-3 option (a))

Cliff's re-derivation: the "page top = most likely on the page" claim
was always technically-true-but-negligible for ordinary pages, and for
FAMILY pages it is false outright (first position is chance + length).
NEW RULE: selecting/advancing ANY page group leaves the provisional
composing text UNCHANGED — the previously selected list row stands
(it is also what a post-exit terminator commits: currentSelection
persists through paging, and any non-Select/non-ambig key EXITS paging
before processing, so there is no mid-page commit path — Cliff). A
BOS/list-restart page keeps provisional NULL. Implementation: the
pagedSelectPage branch of centerPreview is REMOVED (fall-through to
selectedEntry); page-pick commits still replace composing via
onFinalizeText's compose-if-different. CENTER SQUARE follows its
mirror invariant: during paging it keeps showing the prior word in
ARMED (the original "empty on page display" rule was derived from
"provisional is null there", a premise this spec removes); paging a
resting menu stays empty. Side benefit: no composing churn per page
step (less IME editor traffic). Zero test re-pins needed beyond the
CenterSquareTest page test — nothing else pinned page-top composing.

**Center-square-while-paging RULING (Cliff, same day)**: EMPTY, as a
dedicated page-mode signal — the field keeps the prior word (page-menu
rule stands) but stepping into a page declares intent to move OFF it;
keeping it green in the square "almost feels as if the keyboard is
threatening to force that word on the user". The mirror invariant now
reads: the square mirrors the provisional text EXCEPT while a page is
open, where empty = page mode.

**Family ordering round 4 (Cliff's "possible" catch, 2026-08-13; on
dev)**: the round-3 length/discovery order led the "possible" family
with poss/posse/possum — length is not affinity. REPLACED with
prefix-affinity blocks: DESCENDING common-letter-prefix length vs the
SOURCE word (the one the user anchored on), alphabetical within each
block — possibles(8), possibly(7), possibility/possibilities(6), then
the possess/possum crowd(4) alphabetized. Consistent case to case,
closest relatives reliably on page 1, "starts with poss…" words kept
but later. The trie-discovery-order machinery is retired (traversal
order no longer matters); no candidate is eliminated — sorting only.

**Family expansion round 5 — ADAPTIVE STEM FILL (Cliff, 2026-08-14)**:
the configured stem backoff is now the STARTING point, not the answer.
Field report: "differentiation" at backoff 5 (stem "differenti")
offered only differentiate/differential amid four empty slots — the
desired "difference" lived one letter of stem away. When the stem
yields < 6 candidates, it shortens one letter at a time until a page
fills (2-letter floor); prefix families are NESTED so shortening only
ADDS, and the prefix-affinity sort keeps the tight family on page 1
with the broadened cohort behind. All matches kept (page 2+ partial
ok). The blue-flag eligibility gate runs the SAME adaptive search, so
the tint promises exactly what the pause delivers. Cliff's second-
pause-re-broadens idea judged redundant by both of us — adaptive fill
covers it. Icon note: R2 foreground/monochrome inset finalized at
0.81 (0.86 = exact corner fit vs the circular mask's inscribed ~33%
radius; 0.81 lets the square keyboard aspect read); mirrored into the
Codex package (android drawables + README derivation).

**Family round 5b — typed-key consistency floor (Cliff, 2026-08-14)**:
the adaptive stem never drops below the TYPED sequence length (and a
large backoff dial can no longer START it there): a stem shorter than
N admits family members whose keys contradict keystrokes already
typed ("un" offering "unable" after six keys of "understand"). Floor
= max(2, N); since the anchor is a live candidate, its first N keys
ARE the typed sequence, so every member extends it. Not overworry —
the hole was real in both the loop and the initial stem.

## Thread-8 hand-off (2026-08-14) — READ FIRST NEXT THREAD

STATE: **dev is the working line** (64ab3dc5, pushed; CliffDev is
BEHIND — resync or retire on Cliff's call; land new work on dev,
branch scratch worktrees FROM dev now, copy local.properties). 1.2.0
(versionCode 3) release prep merged. Everything below is
device-verified on the tablet except where marked.

SHIPPED THIS THREAD: center-square live surface (empty-while-paging
ruling; SIGNAL state untested in the field until the confidence
toggle is on), R2 icon (final inset 0.81, mirrored to Codex package),
family expansion rounds 1-5b (Select-then-pause page group;
prefix-affinity sort; adaptive stem fill; typed-key floor; Dev-gated,
default OFF, EN/ES only), Select-key page-group glyph, scan-layout
frequency scan order + row-major page mapping, page-menu
provisional-text rule, pull-in page-group relocation (pinned across
async rebuilds), UnDo-pulls-in-whole-first, zombie-region seals.

OPEN QUEUE (rough priority): (1) resting-state paging sim — spec in
"Center-square surface" section (B): learning-on, {current,
all-pages, slot1+pages} x {P-ranked, savings-ranked}, AAC heldout,
switch-scanning caveat; (2) family expansion graduation decisions —
defaults, main-settings promotion, per-language dials, VN inclusion;
(3) EN collocations PARKED on en-collocations-thread7 — next attempt
= multi-word CONTEXT keys ([salt and => pepper], post-script above);
(4) engine queue: doc-boundary Stage-B, NGB user-tier decay, paged
long-string layout (morphological grouping is substantially COVERED
by family expansion — confirm and retire or narrow the queue item);
(5) per-language scan orders (current = EN-frequency-derived);
(6) sel_stats field review once Cliff has mileage — m/d/n
distributions + shadow-theta placements vs the 85% target (signal
fires ~1 keystroke after recognition; the shadow counters hold the
would-fire evidence); (7) deferred-awaiting-field + open-source
track items unchanged (Leipzig CC BY-NC replacement, EN DB
provenance, OpenBoard split-out); verify ES v14 langpack publish
state (records conflict).

GOTCHAS THAT COST TIME THIS THREAD: PagedPickEditorIntegrationTest
carries its OWN replayWordInJtui fake — mirror ImeTextCallbacksImpl
changes there or fixes silently don't run; gate scripted builds on
"BUILD SUCCESSFUL", never a grep that also matches FAILED; a Kotlin
daemon crash leaves phantom unresolved-reference errors in untouched
files — ./jt clean fixes; JDK 17 via JAVA_HOME for every ./jt call;
analyzer sims need .venv/bin/python; check the ACTIVE db, never the
asset; a paged pick PUSHES an undo snapshot (UnDo-after-pick =
page-restore, distinct from empty-stack backspace).

### Thread-6 close-out amendment: the ES row (2026-08-13)

Spanish measured under the identical protocol (shipped v5 layout,
classic = no table + full FSLS order, predictive = v14 table + BOS +
interleave, 250k words, canonical shard-2 heldout REGENERATED —
another thread had overwritten es_heldout.txt with a truncated
partial; the standard recipe reproduced the original 1,390,846
sentences exactly, and a truncated-heldout pass agreed within ~1%).
THE COMPLETE TABLE:

  lang  KPC classic  KPC predictive  saved   (classic costs)
  EN       1.0985        0.7827      -28.7%     (+40.3%)
  ES       1.0857        0.8015      -26.2%     (+35.5%)
  VN       1.5447        0.9166      -40.7%     (+68.5%)

  ES detail: KSPL 1.3062 -> 0.9644 (-26.2%); zero-K 20.8% of words;
  pos1 58.9 -> 51.7% (flat-by-design, same as EN/VN: the win is
  committing earlier, not deeper).
  TRAP flagged by the other thread: results/espanol/report.txt
  carries a stale pre-v5 layout — runs/hplt/es_layout.json is the
  shipped v5 extract.
