# N-gram Prediction Database (NGB) — Plan

Status: **DESIGN (started 2026-08-06)**. Cross-language mechanism, Vietnamese
first. No JT code until the simulator picks a design (Phase 3 gate).
Supersedes the "Phase 4 — targeted bigram promotion DB" sketch in
vietnamese/plan.md (which named Leipzig — CC BY-NC, banned).

## Decisions (design thread, 2026-08-05/06)

**Model.** Ranking, not probabilities: bigram counts + stupid backoff
(context row hit → bigram count; miss → unigram score × constant penalty).
No trigrams: the combinatorial cost buys little under 8-key disambiguation;
multi-syllable coverage comes from the target side instead (below).

**Table.** One schema, two row kinds: `(context_id, target_id, count)`,
context always a SINGLE committed unit.
- Inter-word rows: context = committed unit, target = following unit.
- Intra-word rows (Cliff, 2026-08-06): context = FIRST syllable of a
  multi-syllable word, target = its REMAINDER (W1 => W2, W1 => W2-W3,
  W1 => W2-W3-W4), counted from EVERY corpus occurrence of the word
  regardless of preceding context. Captures Vietnamese từ ghép without
  trigram context explosion (growth is linear in lexicon size); avoids the
  bigram-split distortion (W2=>W3 at the word's frequency where W2-W3 may
  not exist as free text).
Both kinds compete on count at the same prediction moment; targets can be
multi-syllable (one selection = whole word = biggest AKPL win).

**Two tiers.** Shipped static table in the langpack SQLite (flows through
BuildWordDbTask → packageLanguageArtifacts → release, no new formats) + a
user-learned table in the custom DB (capped, decayed, incremented on
selection — same adaptation philosophy as use/case counts). Interpolate at
query time. Learning framing (Cliff): do it, with user buy-in/transparency —
not abstinence. Baseline hygiene only: skip password inputTypes; provide a
"clear learned data" control.

**Query pattern.** Context changes once per committed word: ONE indexed
top-K fetch (K≈100–200) at commit, held in RAM while the next word is
typed; per-keystroke work is an in-memory intersection. Prediction
filtering by typed keys happens in KEY-SEQUENCE space (translateToKeys
prefix match) — TAV mid-word tone keys work for free.

**Context rule (fail-soft).** Context = last committed token iff the cursor
hasn't moved since commit; otherwise no context → silent unigram fallback.
Occasional wrong promotion is an accepted cost (~1 skip keystroke, Cliff);
no heavier machinery.

**Certainty contract evolution.** Reachability guarantees are inviolable
(every FTS reachable; the letter-exact interpretation never vanishes).
ORDER becomes contextual: ITS-above-FTS promotion is UN-DEFERRED for
prediction slots. The top FTS gets an ANCHORED slot (C1 or C2 — simulate)
so the letter-exact reading has a predictable home. Bounded interleave of
predicted candidates into fixed slots — NOT a blended re-sort — so the
unpromoted remainder keeps familiar frequency order. No key ever changes
function because of context.

**UI scenarios to simulate** (Cliff, 2026-08-06; full text in design
thread): hybrid selection list, n = 1 or 2 list-mode slots then page mode.
Terminology: C1/C2 = top list slots; W0 = preceding word; IW0/IW1 =
intended words; FTS as usual. Path (a): SEL-finalize → next AK both starts
IW1 and confirms IW0 → predictions first appear AK1-filtered. Path (b):
page-mode finalize is instant → a zero-keystroke prediction window fills
the list before any AK. Open option (display-only): while a candidate is
highlighted post-SEL, show its top followers below the list (previews path
(b) behavior in path (a); confirmation feedback vs visual churn — judge on
device).

## Simulator (layout-analyzer repo — Phase 3 gate)

Session replay over a held-out token stream; maintain context; per target
word run the candidate-list algorithm under test keystroke by keystroke.
User policies: ORACLE (cheapest path — design ceiling) + simple consistent
strategies ("type until FTS unless target in C1/C2", "type 2 then check").
The oracle-vs-best-simple gap is the measurable half of "intuitiveness":
small gap = efficiency without clairvoyance.

Metrics: AKPL/KSPC; SEL count; scan cost = characters PRECEDING the target
summed over examined list states (reading stops at recognition); p50/p95
per word (worst cases dominate feel); P(target at C1 / top-n / page 1 |
context); surprise rate (target rank DROPS after a keystroke — must be
~never); ITS-above-FTS flip frequency; degradation runs with context
absent for ~5% of words. Baseline: current no-prediction UI.

## Data & licensing (surveyed 2026-08-06)

Corpus (running text at bigram scale; NO Leipzig/NC):
- **HPLT v2 Vietnamese** — CC0 packaging, web-crawl register, huge.
  Primary candidate.
- **Wikipedia vi** — CC BY-SA (attribution + share-alike on the derived
  table); formal register; secondary/register-check.
- Conversational register source TBD — match whatever the post-purge
  unigram corpus uses (check analyzer repo vendoring).

Multi-syllable unit lexicon:
- Hồ Ngọc Đức-derived lists (duyet/vietnamese-wordlist, underthesea
  dictionary) are **GPL** → evaluation yardstick ONLY, never shipped.
- Shipped unit inventory: statistical extraction (association + frequency)
  from the CC0 corpus, top units native-reviewed (VN plan Phase 3 pattern);
  Wiktionary (CC BY-SA) as fallback if SA is acceptable.

## Device resources

See docs/.plans/device-resources.md — floor analysis, NGB is
storage-only by construction, lite-variant plan + device-aware
langpack recommendation feature.

## Measurements (HPLT v2 vie_Latn shard 5, 2026-08-06)

Full-shard count (analyzer tools/ngram_counts.py, JT 9,015-syllable vocab,
OOV breaks context): 4.14M docs, 3.48B tokens, **93.1% in-vocab coverage**
on raw web text. 10.8M distinct bigrams; 8.36M rows at count>=2 (120MB
TSV, runs/hplt/bigrams_5.tsv, not committed). 8,950 of 9,015 syllables
occur as contexts. Top of table = the expected multi-syllable words
(có thể 7.2M, việt nam 4.4M, là một 3.7M, sử dụng 3.3M...).

Top-K-per-context pruning (bigram-mass coverage as hit-rate proxy;
simulator decides K):
| K | rows | mass |
|---|---|---|
| 50 | 398k | 61.0% |
| 100 | 737k | 71.2% |
| 200 | 1.33M | 81.1% |

Shard 5 (smallest of 5, CC0) is sufficient for design/simulation; more
shards only refine the tail.

## Unit extraction + table SHIPPED to runs/ (2026-08-06, analyzer main a35f568+)

Five-signal recipe, each signal added after an observed failure mode:
NPMI (unit vs grammatical collocation; Viet74K-validated: 90%/72%/60%
precision at 0.7/0.5/0.4, count>=1000), 3-gram coherence ratio
min(c(abc)/c(ab), c(abc)/c(bc)) ("bất động sản" .94 vs "tại việt nam"
.10), host diversity >=20 (kills SEO-farm junk that token-df and doc-df
CANNOT catch — template text appears once per page across 24k pages of
2-3 sites), edge concentration >=0.7 (fragment suppression + exact
4/5-gram promotion: "ty cổ phần" -> "công ty cổ phần" 340k), gap-aware
adjacency (tokens separated by anything but spaces are NOT adjacent —
"trước. Xem" bug produced phantom trigrams).

Inventory "mid": 19,921 units = ~12.3k 2-syl (npmi>=0.4) + 537 3-syl
standalone (ratio>=0.5) + ~7.1k 4/5-syl. Known residuals for the
simulator to price: web-register skew (commerce terms, "vui lòng liên
hệ"-class polite boilerplate) vs JT's conversational target register.

Table (greedy-segmented stream, context = last syllable of preceding
segment): 2.57B segments -> 8.6M rows (count>=3), 8,851 contexts.
**22.1% of transition mass goes to multi-syllable-unit targets** — the
AKPL payload. Top-100/context = 639k rows (63.3% mass); top-200 = 1.12M
(73.7%).

Query semantics (IMPORTANT, from the "việt"-context sample): after a
user commits standalone "việt", the table's own followers are the rare
cases (linh, cộng) because "việt nam" mass lives on the UNIT target
offered a keystroke earlier. The engine must merge TWO sources: (a)
table rows for the context, (b) REMAINDERS of units starting with the
committed syllable(s), weighted by unit marginals ("nam" via "việt nam"
4.4M). Both come from shipped data; no extra pass.

## Simulator v1 results (2026-08-06, 500k held-out tokens from shard 1)

tools/ngb_sim.py (analyzer): held-out replay, certainty-contract list
model, Cliff's hybrid selection (n list slots then 6-candidate pages),
bounded prediction slots below an anchored top-FTS, zero-keystroke
follower list, exact-match acceptance of multi-syllable predictions.
Policies: fts (today's type-everything habit), check2 (read list from
2nd keystroke), true per-word min-cost oracle.

KSPL = (AK+SEL) keystrokes per letter; TAE encoding:

| config | fts | check2 | oracle |
|---|---|---|---|
| baseline (today) | 1.3081 | 1.9509 | 1.3008 |
| NGB hybrid n=1 | 1.1837 | 1.2602 | 1.0939 |
| NGB hybrid n=2 | 1.1568 | **1.1493** | 1.0910 |

Conclusions:
1. NGB ceiling = **16.1% keystroke reduction** (oracle vs oracle).
2. Best simple strategy (n=2, check-from-2nd-keystroke) realizes 12.1%
   — within 5.3% of oracle: efficiency without clairvoyance.
3. Users who keep the type-to-FTS habit still get 11.6% (promotions +
   zero-K at word boundaries) — no behavior change required for most
   of the win.
4. Early list-checking WITHOUT context is a losing strategy (baseline
   check2 = 1.95): predictions are what make reading early pay.
5. n=2 beats n=1 across every policy — second list slot earns its keep.

v1 model caveats (refine next): TAE only (TAV variant pending); scan
model coarse (check2 charges a full top-8 read per keystroke — real
users saccade less; scan/letter 10.2 vs baseline-fts 0.33 overstates);
per-word greedy oracle (full-run DP would be slightly lower); web
register held-out (conversational replay when a clean source exists).

## User tier + custom units (design captured 2026-08-06/07)

Learning is RECOGNITION-based, not acceptance-based: the engine runs the
same greedy longest-match segmenter as ngb_table over the committed
output stream (window <= 5 syllables) and increments user-tier rows
`(context_id, target_id, count, last_used)` for every segment transition
— one row per boundary, no nested redundancy, and the SAME counting
basis as the system tier (required for sane interpolation). Rows are
pointers into the system target vocabulary (~16 B/row). Manual typing of
a known unit therefore promotes it exactly like accepting it would.
Consequence: prediction ROWS are K-pruned for shipping, but the unit
INVENTORY ships whole (19.9k units, few hundred KB) — a unit never
displayed can still be recognized, learned, and thereafter predicted.
User counts get a strong multiplier in the merged fetch (beta, simulator
to tune); previously-used predictions get a subtle third visual marking
(letter-exact FTS / prediction / YOUR prediction).

Custom units — deliberate addition (v1; names are the primary case):
- VN "ADD NEW WORD" = a phrase-style capture session using NORMAL
  ambiguous typing (not spell mode — tone entry there is unfamiliar),
  restricted to sequences of existing syllables (no punctuation), SHIFT
  for Title Case; saves a flagged custom row into the local unit
  inventory. Case is authoritative as typed (names bypass case prefs).
  ADD NEW PHRASE keeps its abbreviation-expansion role unchanged.
- Spell mode remains only for genuinely new SYLLABLES/strings (VN label:
  "ADD NEW SYLLABLE/STRING" — onomatopoeia, phone numbers, URLs). Mixed
  names with foreign elements take the two-step path: add the syllable,
  then the unit.
- Ranking: custom units get a GUARANTEE, not a tuned weight — a
  key-compatible (or context-matched at zero keystrokes) custom unit
  always appears in the top slots, capped at the top M (~2) by decayed
  use count (family-name stress test: surname-first means several
  "nguyễn *" units are alive after one syllable; decayed use orders
  them and the next keystroke disambiguates).
- Sunset by DEMOTION, never deletion: below a decayed-count threshold
  the guarantee lapses and the unit competes as an ordinary prediction;
  it stays recognizable and revives through the recognition loop when
  used. Deletion is a user action in a management surface (Excluded
  Words pattern). Lifecycle: guaranteed -> competing -> dormant.

Implicit discovery of NOVEL units (not in inventory): DEFERRED.
Captured discriminator signals for when we take it up: inter-syllable
pause timing, same-TTS-utterance grouping, punctuated-sentence
membership; start such units in a discounted state.

Simulator queue: (1) compete config — unified class-scale ranking
(P(t|ctx) x WordsRaw-mass -> computeFreqClass; stupid backoff penalty
delta, register-bridge scale lambda — both swept), letter-exact kept as
a color-coded GUARANTEE constraint, color-aware scan model (policy
scans only its target's color class). Measured vs ngb_n2 fixed slots.
(2) Learning experiment — user tier accumulates within-replay; measure
how fast repeated patterns reach top-2 (beta sweep). Context: FreqClass
mapping worked examples — P(mừng|chúc)=3.0% -> class 1 vs unigram 3
(promotion); P(xanh|không) -> class 7 vs 4 (demotion: absence of a row
is evidence too). Hit rates (held-out, merged predictions): top-2 24.6%
at zero keys, 60.4% after 2 keys; pool ceiling 65.9% at K=200.

## M-metric fit + compete-vs-fixed verdict (2026-08-07)

Cliff's min/max-M design implemented as ngb_sim mfit: per-event success
vectors over a log M-grid, score = max(unigram_count, M x P(t|ctx) x
WordsRaw mass), letter-exact slot guaranteed. 120k held-out transitions:

- Success is a PLATEAU, not a peak: rises to saturation at M~3 and never
  declines to M->inf (depth-2 top-2: 54.4% best; 51.4% at M=1; 15.7% at
  M->0). M=1 (face-value conditional) is within 1.5-3 points of optimal
  everywhere -> the register bridge needs no delicate tuning; pick
  mid-plateau M=5.
- No table row => rarely in unigram top-n either, so boosting
  predictions costs ~nothing INSIDE the top-n bar.

BUT full-KSPL validation reversed the architectural conclusion:
unbounded competition (all alive predictions above the unigram tail)
LOSES to the bounded fixed-slot design — ngb_n2/check2 1.152 vs
compete_M5/check2 1.196; oracle 1.094 vs 1.108. The cost lives exactly
where the slot-success metric doesn't look: targets at ranks 5-14
(no-row unigram candidates) get displaced below dozens of predictions
into deep pages. Metric myopia, documented deliberately: slot-success
tuned what it measured; KSPL adjudicated the tail.

VERDICT: the shipping design is the bounded block — guaranteed
letter-exact slot 1, prediction block (<=4) ORDERED BY CONDITIONAL
EFFECTIVE COUNT (that part of the M-fit survives), unigram fill from
slot ~6 protecting no-row targets. The M=5 count-scale unification is
retained where it genuinely matters: merging user-tier counts with
system counts on one scale, and any future within-block competition.
Predictor now emits proper conditionals (table rows / ctx mass;
remainders / s-initial mass) — required for the user-tier merge.

## Word-inventory verdict + entry-state gate (2026-08-07)

Cliff's 1N1/1N2/1N3 taxonomy adopted (plan-wide): 1N1 = word's own
first-syllable -> remainder; 1N2 = context -> complete multi-syl word;
1N3 = context -> single syllable. The greedy-segmentation counting basis
IS Cliff's "option one" 1N3 rule (X->Y counted only where Y stood alone).

Kaikki Vietnamese (CC BY-SA, attribution + share-alike note for the
langpack) -> 21,343 in-vocab multi-syl words (18.4k/1.9k/1.0k/0.1k by
length). Verdict vs the statistical inventory — words win EVERY axis:
- Coverage: 44.3% of syllable mass in multi-syl segments (stat: 39.2%);
  max keystroke-free share 22.4% (stat: 20.7%). The stat inventory's
  count floors dropped mid-frequency real words while keeping boilerplate.
- KSPL (500k held-out): n2/check2 1.1497 vs stat 1.1521; oracle 1.0914
  vs 1.0938.
- Mind-map: non-word exposure falls from small (stat: 0.4% of top-4
  slots, >=1 non-word in 1.6% of transitions) to zero.
Frequency-weighted mean word length 1.29 syllables; 76.6%/22.0%/1.4%
segments at 1/2/3+ syllables.

Entry-state gate (Cliff's 1N1-exclusion insight) implemented: after a
complete-word commit, its final syllable cannot start the next word, so
1N1 remainders are excluded from the pool. Small consistent KSPL gain
(check2 1.1497 -> 1.1486, oracle 1.0914 -> 1.0902), structurally
correct, free at runtime — ADOPTED.

Running totals on the word inventory: realistic 12.2% keystroke
reduction (1.3081 -> 1.1486), oracle ceiling 16.2% (1.3008 -> 1.0902).

## Slot-budget sweep (2026-08-07) — assumptions replaced by measurements

pred_slots x anchor_fts grid + zero-K ablation (words table, gate on):
- **anchor=0 wins decisively**: check2 1.0982 vs 1.1486 (anchor=1) vs
  1.1721 (anchor=2). The anchored FTS slot was costing 5 KSPL points —
  Cliff's certainty-contract override (ITS-above-FTS for predictions)
  is vindicated by data. Letter-exact stays present + color-coded at
  the first post-block slot; block-above-FTS FEEL is the one open
  question left for device testing.
- pred_slots 2..6: KSPL-flat (0.0002 spread) — block size is a
  scan/visual decision, not an efficiency one. Default 4.
- **zero-K ablation: the window carries most of the win** — fts policy
  1.1600 with vs 1.3045 (≈baseline) without. The word-start moment is
  where the NGB pays; ship it in v1, not as polish.
New standing results: realistic 1.0982 (16.0% cut), oracle 1.0532
(19.0%), gap 4.3%. Engine spec: docs/.plans/ngram/engine-spec.md.

## Device review round 1 (Cliff, 2026-08-08) — seven issues

FIXED in claude/ngb-engine:
1. Select key ALWAYS previews what pressing Select now produces —
   type filter gained N (and B, latent since TAV round 1); at page
   transitions it previews the incoming page's TOP item (4b).
2. Composing preview is WYSIWYG: the top/current list item INCLUDING
   predictions. Consequence, accepted: a terminator without Select
   commits exactly what composing shows (prediction included).
3. Paged selection composing = current page's top item (option a).
4. Select key at page transition = next page's top item (option b);
   the page-icon variant (4c) noted as a possible polish.
6. NGB context rides the UnDo stack (State fields ngbContext/
   ngbGateOpen; pool refetched on restore): deleting a keystroke keeps
   the word's context; rewinding past the commit restores pre-commit.
7. First SELECT returns the keyboard to its initial state: badges on,
   TAV tone-form columns cleared, tone-key hints off (postSelectionState
   gates renderKeyLabelGrids + TAV forms + computeNextToneKeyHints).

REJECTED (Cliff, 2026-08-08):
5. Auto-reverting page mode to list mode for a 1-2 item tail — the
   1-SEL saving is small and rare; a mid-selection mode flip is exactly
   the "key functions change unpredictably" hazard. Page mode stays
   uniform to the end of the list.

ADOPTED instead — first-page minimum (Cliff, 2026-08-08, implemented):
paging engages only when the first page would hold >= 4 items
(PAGED_MIN_FIRST_PAGE; with 2 listed slots: lists of <= 5 stay pure
list mode). Kills the degenerate 2-item page (object 3 cost 4
activations vs 3 in list mode) at the head instead of patching the
tail: frequency-weighted keystrokes favor it (win on object 3, tie on
4, lose only on lowest-frequency object 5), and a >= 4-item first page
always renders its second column — visibly a page group, never an
inexplicable gap. Simulator select_cost model to be updated to match.

Round-1 follow-ups (Cliff, 2026-08-08):
8. FIXED — pull-in replay of toned words: mapWordToKeyIndices was a
   pre-tone letters-only map (dropped tone keystrokes in BOTH modes);
   now delegates to wld.translateToKeysOrNull, so replay honors the
   tone-position setting AT RESTORATION TIME (not entry time) by
   construction.
9. Phase C scope — DELETE-driven context reconstruction (Cliff
   clarified 2026-08-08: covers ALL pull-in triggers, and the fix
   belongs in the triggered pull-in PROCESS itself — context
   re-derivation on completion of any pull-in — not per-trigger
   detection): deleting a
   whole word (space remains) must invisibly re-derive context from
   the text before the cursor (greedy-match the trailing committed
   syllables against the unit inventory: trailing multi-syl unit =>
   gate CLOSED, bare syllable => OPEN — the same recognition machinery
   as learning). A further DELETE across the space actively pulls in
   the preceding word and re-derives context from the object before
   THAT. Belongs with Phase C recognition; capture here so the
   machinery is designed once for both uses.

WYSIWYG terminator note (Cliff question 2026-08-08): within JT's own
key surface, no terminator fires without a prior Select (badges/
list-functions trigger only between words or via AK-after-SEL; KF_Term
has no implicit fallback). The exceptions reach KF_Enter's implicit
first-word fallback or the app's own Send/editor action finalizing
composing: hardware/switch-mapped ENTER and the host app's Send button.
Both commit exactly what composing shows — WYSIWYG-consistent.

## Language-traits directive (Cliff, 2026-08-08)

English/word-based NGB is next after VN. Prediction behavior will fork
on language characteristics — introduce flags set at language switch
(e.g. is_syllable_based / is_word_based; likely also has_tone_keys,
space_separates_syllables) rather than testing language names or
tones!=null scattered through the code. Add a LanguageTraits object
derived from LayoutSpec + langpack metadata; engine-spec updated.

## Round-2 residuals (2026-08-08)

- Zero-K window is a MENU: topCandidate (and therefore IME composing)
  stays null at empty-sequence-no-selection — without this guard every
  paged commit injected the top follower as phantom composing text.
  Fixed alongside round 2; latent until paged-final-commit existed.
- Case-learning gap (backlog): selecting a type-N prediction bypasses
  recordWordUsageForSelection, so case counts never update from
  prediction selections — user case preference can't flip via
  predictions. Fold N into usage/case recording (per-syllable for
  units) in the Phase C polish pass.
- DB case counts verified present (nam 3T/1L, hà 4T/1L, việt 4T/0L) —
  the case-preferred fix has the data it needs. Tie counts (nội 2/2)
  currently resolve LOWER; user learning flips them once N-type
  selections record case (see gap above).

## NGB-D: selection-confidence signal (Cliff, 2026-08-09 — FEASIBLE, queued)

The oracle gap (12.2% realistic vs 16.2% ceiling) is precisely "the user
doesn't know when to stop typing." Cliff's proposal: estimate P(top
list item == intended word), let the user set a notification threshold
(beep / flash / translucent overlay — selectable action), self-refining
over time.

Why this is unusually feasible:
1. The core estimate is nearly free: the list is already ranked on ONE
   count scale (M-fit), so p-hat = score(top) / sum(score(alive
   candidates)) is a normalized posterior over the current candidate
   set — computable at every keystroke from numbers already in hand.
2. Every word entry generates a FREE labeled example: the features at
   each keystroke + whether the user's final commit matched the top.
   Textbook online supervised learning with automatic ground truth.
3. Cliff's refinement loop = a tiny on-device logistic model (~8
   weights: posterior, top-1/top-2 margin, keystroke count, top type
   FTS/pred/user-used, context validity, keys remaining), SGD update
   per word. Weights only — no text stored; zero privacy surface.
4. Ship it pre-calibrated: the SIMULATOR can generate millions of
   labeled examples from held-out replay and fit initial weights
   offline; on-device learning then personalizes. Sim experiment
   queued: policy "stop when p-hat > theta" -> KSPL vs theta curve =
   the feature's value measured BEFORE any Android code.
5. False-positive control = threshold slider; off = signal hidden but
   learning + could-have-saved counter continue; periodic "N keystrokes
   could have been saved — try again?" prompt (Cliff's design).

Implementation order: sim experiment (calibration + value curve) ->
engine p-hat + logging -> notification actions + settings -> on-device
weight updates.

### NGB-D sim results (2026-08-09 — calibration + value curve DONE)

Tool: layout-analyzer tools/ngb_confidence.py (emit / fit / sweep),
analyzer main 107c248. Artifacts in runs/hplt/: conf_train.tsv,
conf_eval.tsv, conf_weights.json, conf_fit_report.txt, conf_sweep.txt.
Harness validated: eval-shard references reproduce the standing numbers
(check2 1.0976, oracle 1.0539, fts 1.1638).

Two methodology gotchas paid for (do not relearn):
- Sim --vocab must be corpora/VietnameseWordsRaw.txt (MARKED forms;
  Layout does TAE tone folding itself). The Tone5t-encoded corpus
  silently OOVs every toned syllable — 75% of tokens — and every
  downstream number looks plausible but is garbage. Symptom to watch:
  words/tokens ratio far from ~1.0 in run output.
- Emission distribution must be FTS replay (label = "would the top be
  right if the user stopped NOW"). Emitting under check2 replay poisons
  the high-confidence region: check2's own checks consume every correct
  top at first sight, so surviving high-p-hat states are almost all
  rejected tops (measured: 5% empirical accuracy at p-hat 0.65). NGB-D's
  premise is precisely that the user does NOT check mid-word.

Fitted model (7 weights, train shard 610k states, base rate 25.4%):
posterior +2.81, margin -0.22, kcount +0.80, is_pred +5.23,
ctx_valid +0.40, keys_rem -0.06, bias -8.78. Eval AUC 0.886 (raw
posterior share alone: 0.848 and badly miscalibrated). Calibration is
excellent: p-hat tracks empirical accuracy within ~2 points across
[0, 0.99] — the threshold slider maps to a REAL probability. margin
and keys_rem carry ~nothing; a 5-weight JT model (posterior, kcount,
is_pred, ctx_valid, bias) is fine.

KSPL-vs-theta (eval shard, 251k tokens; notif/w = notifications per
word, top-hit = P(top==intended | notification)):

  policy      KSPL   scan/l  notif/w  top-hit%  accept%
  fts        1.1638   0.48      -        -        -
  check2     1.0976   9.77      -        -        -
  oracle     1.0539   0.87      -        -        -
  theta=0.20 1.0920   4.00    0.669     35.5     48.8
  theta=0.30 1.1059   2.57    0.500     47.5     60.2
  theta=0.40 1.1167   1.86    0.400     57.4     68.0
  theta=0.50 1.1259   1.39    0.326     65.7     74.4
  theta=0.60 1.1339   1.07    0.269     73.7     79.9
  theta=0.70 1.1416   0.84    0.218     80.3     84.4
  theta=0.80 1.1523   0.66    0.159     87.1     89.3
  theta=0.85 1.1584   0.56    0.107     90.8     92.5
  theta=0.90 1.1607   0.52    0.078     93.6     94.7
  theta=0.95 1.1634   0.48    0.020     95.4     96.1

Readings:
- theta=0.20 BEATS check-every-keystroke on KSPL (1.0920 vs 1.0976)
  with 2.4x less scanning — the signal replaces vigilance.
- Against the honest no-signal baseline (fts): theta=0.50 cuts
  keystrokes 3.3% at one notification per ~3 words (74% acceptance);
  theta=0.20 cuts 6.2% and recovers 65% of the fts->oracle gap.
- Keystrokes saved per notification is roughly CONSTANT (~0.35-0.4)
  across theta — the slider trades interruption rate against total
  savings, not per-beep value. False-positive control works as Cliff
  designed: precision rises smoothly 36% -> 95% with theta.
- Recommend shipping default theta ~0.6-0.7 (high precision builds
  trust in the beep; ~1 notification per 4 words, 80-84% acceptance,
  ~2% keystroke cut), slider range 0.2-0.95.

v2 refinement (recorded): policy-consistent re-emission (emit under the
conf policy with the fitted model, refit — DAgger-style) would sharpen
calibration for post-notification states; suppression (quiet until the
top changes) already bounds that error in the sweep. Sim still models
select_cost pre-PAGED_MIN_FIRST_PAGE (backlog item unchanged).

### NGB-D JT side (2026-08-09 — LANDED, CliffDev facfafc)

Shipped in one pass (feature branch merged --no-ff, both remotes):
- NgbConfidence (logic/, pure Kotlin): 5-weight logistic with the
  sim-fitted initials (posterior 2.7712, kcount 0.8157, is_pred 5.1256,
  ctx_valid 0.3902, bias -9.0057), per-word snapshots, fire rule
  p-hat >= theta with quiet-until-top-changes suppression, commit
  labeling, bounded SGD (lr 0.01, weights clamped to fitted +/- 3).
- Features computed in wldSelectionInternal beside the list build
  (posterior = top's share of the alive field: displayCandidates by NEW
  CandidateEntry.rawFreq plumbing, alive pool preds by eff, dual-source
  max — mirrors the sim's scored_field), carried on WldSelectionResult,
  consumed in updateUi post-top-determination (stale async results are
  discarded before the observation, zero-K/selection states skipped).
- Signal actions in KeyFeedbackController.confidenceSignal(): rising
  PROMPT tone (distinct from ACK/NACK timbres) and/or green Select-key
  flash. Translucent-overlay action deferred to the visual/color pass.
- Learning: labels on every commit via the ngbOnWordCommitted funnel;
  suppressed with ngbLearningSuppressed (password fields); weights
  persist per-language in custom-DB ngb_conf (survives langpack swaps,
  no langpack release needed); loaded at language init.
- Could-have-saved counter (runs while the signal is OFF, per design):
  batched into prefs, periodic rate-limited AccessiblePrompt invitation
  (>=300 saved keys AND >=3 days apart).
- Settings (sec_selection, main page -> parity free): enable toggle
  (gated on Context Predictions), action choice, threshold IntSlider
  20-95% step 5 default 65%. ALSO fixed: KEY_NGB_PREDICTIONS had
  strings + a reader but NO registry entry — the NGB kill-switch was
  unreachable on both surfaces until now.
- Tests: NgbConfidenceTest (9, pure), VietnameseNgbConfidenceTest (4,
  real TiengViet DB — signal fires on chuc->mung at theta=0.2, toggle
  gates signal but not learning, saved-counter accumulates, weights
  persist + reload). ./jt check green.

Remaining NGB-D backlog: on-device weight telemetry surface (Dev
Settings readout), overlay action, DAgger-style recalibration, sim
select_cost update for PAGED_MIN_FIRST_PAGE. Device smoke: signal feel
at theta 0.65, beep audibility, flash visibility in scan mode.

### NGB-D extensions + forward explorations (Cliff, 2026-08-09)

**1. Would-have-been-distracted counter — LANDED (CliffDev 798801e).**
Full-disclosure pair to could-have-saved: every fired signal is
ledgered per word; fires whose top != the committed word count as
distractions (KEY_NGB_CONF_DISTRACTED). Both counters accumulate in
ALL states (off = hypothetical, on = actual; saved self-corrects when
a correct signal is accepted). Sim-expected ratio at the theta=0.65
default: ~80% of fires correct -> roughly 1 distraction per 4 saves.
Future: the periodic prompt should quote BOTH numbers.

**1b. DKWF theta-optimization (Cliff, 2026-08-09) — DONE.** Net
utility per word = keystrokes saved (vs no-signal fts) − DKWF x
distractions (fires whose top wasn't committed — the on-device
counter's definition). Sweep columns added to ngb_confidence.py
(--dkwf); dense theta grid on the eval shard
(runs/hplt/conf_sweep_dkwf.txt):

  DKWF 0.5: optimum theta ~0.40 (net +0.075 keys/word; broad
            plateau 0.35-0.55)
  DKWF 1.0: optimum theta = 0.65 (net +0.0345; 0.60-0.70 within
            10%) — THE SHIPPED DEFAULT IS THE DKWF=1 OPTIMUM.
  DKWF 2.0: only theta=0.90 is net-positive (+0.001) — distraction-
            averse users should sit at 90% or turn it off.

At theta=0.65: 0.089 keys/word saved, 0.055 distractions/word (~1 per
18 words). Design consequence: the threshold slider IS a DKWF dial —
40% ~ "distractions barely bother me", 65% ~ neutral, 90% ~
"distraction-averse". Future settings copy can present it that way.
No code change needed: default 65 confirmed optimal.

**2. Either-of-top-two firing rule — SIMULATED; recorded, deferred.**
tools/ngb_confidence.py now emits label2/posterior2, fits a top2 model
(eval AUC 0.871, calibrated), and sweeps --rule top2 (fire on
P(intended in first two slots) >= theta; suppression on the top-pair).
Eval shard, vs the standing top1 curve:

  rule theta   KSPL    notif/w  hit%      rule theta   KSPL    notif/w  hit%
  top1 0.50   1.1259   0.326    65.7      top2 0.50   1.1134   0.481    62.0
  top1 0.70   1.1416   0.218    80.3      top2 0.70   1.1302   0.294    76.5
  top1 0.90   1.1607   0.078    93.6      top2 0.90   1.1592   0.103    92.7

Read: at matched theta, top2 saves ~1.2 KSPL points more — but by
firing ~40% more often. At MATCHED notification rate the frontiers
overlap (top2 at 0.326 fires/word interpolates to ~1.127 vs top1's
1.1259): per-interruption value is the same; the rule just relabels
the slider. One real advantage the numbers understate: with both words
displayed on the Select key, slot-2 accepts become visible that a
top1-only display would miss. Verdict: optional, default-OFF feature
(Cliff's distraction concern is confirmed by the economics — each fire
now asks the user to read and choose between two words for the same
per-fire value); build only after the base signal proves itself on
device. UI when built: word1 + word2 on the Select key.

**3. EN multi-word contexts (cherry-picked n-grams) — experiment
design, queued behind the EN NGB round.** Base EN NGB = last word ->
followers (the VN schema with word IDs, per LanguageTraits). The
extension: a second context tier keyed by the last TWO words, kept
only for cherry-picked contexts where the trigram materially beats the
bigram. Selection metric per candidate context (w2,w1): expected
keystrokes saved = count(w2,w1) x [KSPL gain of P(.|w2,w1) over
P(.|w1) under the sim's policy] per DB byte. Sweep the kept-context
budget (e.g. top 50k / 200k / 1M contexts) -> KSPL vs DB-size curve,
one curve per corpus register. CPU cost is flat: 2 seeks per commit
(trigram row, fallback bigram row) — the fetch-per-commit architecture
absorbs it; the cost axis that matters is DB size, which maps directly
onto Cliff's light/full langpack tiers. Prereq: EN corpus through the
resource-licensing ledgers (docs/.plans/resource-licensing.md).
Deliverable: the marginal-value curve, so the light-pack cut point is
a measurement, not a guess.

**4. On-device LLM exploration — assessment recorded, sim-first.**
Realistic shape: the LLM is a RERANKER of the NGB pool, not a
generator — once per word commit (never per keystroke), score the
pool's ~60 candidates against sentence context; the list contract,
key-prefix filtering, and reachability guarantees stay untouched.
That bounds latency (one short prefill + 60 candidate scores at word
boundaries) and degrades cleanly: no model -> pure NGB. Hardware
reality: Gemma-class edge models (3n E2B/E4B; check the current
generation at build time) need ~2-3 GB RAM and want NPU/GPU — fine
for mid/new devices, impossible for the low-cost floor -> this is
exactly the light/full langpack split again (full pack ships the
model; light pack = NGB only). License note: Gemma terms are custom,
commercial-allowed-with-conditions -> ledger (a) with conditions;
Apache/MIT alternatives exist (Qwen, OLMo, SmolLM families; llama.cpp
/ LiteRT runtimes) — run all through the licensing ledgers.
MEASURE FIRST in the analyzer: replay held-out text, rerank each
commit's pool with (i) an ORACLE reranker (upper bound: intended word
to pool-top whenever in pool) and (ii) a real small LM scoring — KSPL
delta vs NGB tells us the ceiling and the realized gain before any
Android work. Sentence-level context ("I think I left my phone in
the ___") is where the gain should concentrate; the oracle run
quantifies it.
Conversation-level context: (a) own prior text on device — feasible,
on-device only, extend the existing consent framing + clear-data
control; (b) hearing a physically present partner = recording third
parties: technically possible (on-device ASR) but a consent/legal
minefield (two-party-consent jurisdictions), battery + mic conflicts
with TTS. Record as far-future; explicit-per-session consent at
minimum; not before the LLM reranker itself proves out.

**5. Resource licensing** — dual-track evaluation policy memorialized
in docs/.plans/resource-licensing.md (ledger (a) commercial-compatible
with conditions; ledger (b) non-commercial with value + reconstruction
difficulty; Foundation free build may use (b)).

### C2 delete-context reconstruction (2026-08-09 — LANDED, CliffDev 6233161)

Plan item 9 delivered in the pull-in PROCESS, as specified:
- NgbRecognizer.deriveContext: pure greedy query over trailing
  syllables on the learning-segmentation basis; final segment decides
  (multi-syl unit => gate CLOSED, bare syllable => OPEN).
- JTUI.ngbReconstructContext: fail-soft (punctuation/digit boundary,
  empty, no recognizer => contextless), 8-syllable window; reopens the
  ZERO-K follower window when the sequence is empty — after a word
  delete the replacement word's predictions are visible before any
  keystroke.
- Wired at the two funnels ALL triggers converge on (mapped by
  exploration): ImeTextController.runPullInFlow (covers manual/
  field-start/resume/cursor-relocation/UnDo-context-8 DELETE-across-
  space pull-ins; preceding text taken from the already-fetched
  extract; reconstruction runs between the replay's reset and its key
  presses, so the replayed list carries the block and the dynamic
  targetIndex stays correct) and handleDeleteWord (whole-word +
  composing branches, getTextBeforeCursor(200)).
- Learning stream deliberately NOT reseeded: those transitions sealed
  at their original commits; reconstruction is prediction state only.
- Free upgrade discovered: field-start pull-in now derives context
  when focusing INTO existing text mid-document.
- Tests: recognizer derivation suite, controller reconstruction-call
  assertions (real EditText), JTUI end-to-end (zero-K reopen + block
  after reconstruct, gate + fail-soft). Gotcha re-paid: :app:clean
  fixed phantom "No value passed for parameter" errors at untouched
  lines.

NGB v1 -> dev gate: C2 DONE; remaining condition is Cliff's soak days.

### C3: pull-in NGB-span expansion — LANDED (CliffDev 001e274)

Built to the v2 design below; Cliff approved rulings (a) collapse-first
on AK and (b) unchanged-span commit = learning no-op (structural — the
recognizer stream is never reseeded at pull-in). Implementation notes:
- JTUI span session: NONE/PENDING/ACTIVE/COLLAPSED. Probe (unit
  inventory by first syllable + C2-pool remainders, verbatim
  following-text match, word boundary required) -> PENDING; the
  pull-in replay runs with the block hidden and NO select-stepping;
  activation rebuilds the list (spans longest-first at head), pushes a
  pre-selection undo snapshot, selects index 0.
- Collapse triggers: UnDo popping to a null selection, Select stepping
  past the last span entry, or an incoming ambiguous key. COLLAPSED
  suppresses ALL NGB entries for the rest of the word's edit (spec 7).
  Paged entry deferred while the group is open.
- IME: runPullInFlow STEP 0.5 probes + widens the deleted range to the
  longest span; STEP 2.5 activates; collapse restores the span via
  setComposingText -> finishComposingText -> setComposingRegion(tapped
  word), so the tail returns to committed text ("replicate that
  initial state").
- Span entries render with the field's ORIGINAL casing
  (preserveOriginalCase) and Int.MAX count (head placement is
  structural). Tests: 6 scenario tests on the real TiengViet DB
  (probe/longest-select, mid-unit remainder, boundary rejection, both
  collapse directions, AK collapse-then-commit).
- Device smoke pending: Cliff's original "Thành phố Hồ Chí Minh"
  sequence end-to-end.

#### Original design record (Cliff spec, 2026-08-09)

Trigger observation (tablet, "Thành [phố] Hồ Chí Minh."): tapping the
second "phố" pulls in the syllable; the list shows the block entries
"phố" and "phố Hồ Chí Minh" (1N1 remainders of ctx "thành" — C2
reconstruction working as built); UnDo past the selection back-out
strips keystrokes while the block persists (prefix-compatible) and
composing stays "phố" (WYSIWYG top). Consistent mechanics, confusing
UX at pull-in. Cliff's spec (his numbering):
1. Pull in the tapped word + FTS sequence (standard).
2. Generate the list incl. NGB entries (standard).
3. Probe the SURROUNDING TEXT for a match of any NGB entry; no match
   -> standard pull-in behavior.
4. On match: (i) the full matched span becomes composing text;
   (ii) list shows NGB entries at TOP with the text-field match
   SELECTED; (iii) Select/UnDo navigate, composing follows (WYSIWYG).
5. UnDo stepping backward OFF the first (NGB) object: ALL NGB entries
   collapse out; list redraws as the ordinary reconstructed-sequence
   list; the entry matching the ORIGINALLY TAPPED word is highlighted
   (not necessarily first — replicates the original entry state);
   composing reverts to the tapped word alone.
6. Select stepping forward off the LAST NGB object: identical collapse.
7. After collapse: standard behavior.

Claude design notes (v2 after Cliff's anchoring question, 2026-08-09;
the earlier segmentation-based matching note is WITHDRAWN — a segment
containing the tap can extend BACKWARD past it, forcing exactly the
"chí-keys with Hồ-Chí-Minh-composing" incoherence Cliff flagged):
- ANCHOR PRINCIPLE: the pull-in is ALWAYS anchored at the tapped word;
  the key sequence is the tapped word's; spans grow FORWARD only.
  Every offered span STARTS with the tapped word, so every span is
  prefix-compatible with the restored sequence — identical semantics
  to the shipped prediction block (the sequence is the FILTER, not the
  identity, of a block entry). The problematic pairing never occurs.
- Mid-unit taps fall out naturally: tapping "Chí" in "Hồ Chí Minh"
  reconstructs ctx "hồ" (C2), whose pool holds the REMAINDER
  "chí minh" — a span starting at the tap, matching the following
  text. The user is offered the remainder-from-here, never a span
  that swallows preceding words; preceding words remain committed and
  individually tappable (Cliff's finer-granularity concern is thereby
  structural, not a mode).
- LARGEST-vs-SMALLEST resolved: among spans starting at the tap that
  match the following text verbatim, the LONGEST match is initially
  SELECTED (it names what is actually in the field); shorter matches
  remain in the group as steppable alternatives; collapse lands on
  the tapped word alone. Whole-phrase editing = tap the phrase's
  first word ("tap the start of a phrase to work with the whole
  phrase" — one explainable rule).
- MATCH SOURCES (both cheap, both filtered by verbatim following-text
  match on canonical lowercase): (i) ngbUnitsByFirstSyl(tappedWord) —
  direct inventory, context-independent, covers taps at document
  start where no ctx exists; (ii) pool remainders under the C2-
  reconstructed context — covers mid-unit taps.
- "What did the user originally type" is MOOT by existing design:
  pull-in has never reconstructed history — it replays the canonical
  FTS sequence to an equivalent state (a word originally produced by
  2 keys + Select still replays as full FTS today). C3 keeps that
  contract: equivalent-state reconstruction, anchored at the tap.
- Span pull-in = runPullInFlow with widened FORWARD bounds; collapse =
  standard replay of the tapped word alone.
- OPEN QUESTION (a): AK press while a span entry is selected —
  proposal: collapse first (as 5/6), then apply the key normally.
- OPEN QUESTION (b): commit of a span identical to the pre-edit text
  = no-op for learning (no user-tier double bump).

### Side fix (2026-08-09): suppressed first page rendered a phantom gap

wouldEnterPagedSelection's 4-item first-page minimum suppresses paged
ENTRY, but buildPagedSelectionBuffer still rendered rows beyond the
listed head as page-styled groups with a blank separator — visually a
page element the user could never enter. Fixed: when the list is too
short for paging to engage, everything renders as one continuous list
(regression test on khoẻ's 3-candidate list).

**6. Savings representativeness (Cliff device observation, 2026-08-09).**
One highly predictable sentence ("Hôm nay trời đẹp quá") measured 7/23
keystrokes saved (30%) vs the sim's 16% whole-stack average. Both are
right: the held-out corpus is HPLT WEB register (news-adjacent, varied)
— a conservative FLOOR for AAC usage, which is conversational,
repetitive, and personal; and the sim runs with the user-learned tier
EMPTY, while on-device the ngb_user tier (USER_BOOST) quickly promotes
repeated personal phrasing to zero-K/top-1. Two queued sims sharpen
this: (a) conversational-register validation corpus (already in
deferred), (b) a LEARNING-LOOP replay (user tier updating during the
run, repeated-utterance distribution) to quantify steady-state
personal savings. Honest public claim until then: "~16% typical on
varied text, more on routine phrases — and the app shows YOUR measured
number" (the saved/distracted counters are ground truth per user).

**7. Spoken confidence feedback (Cliff, 2026-08-09 — exploration
recorded; transformative for visually impaired users, likely very low
theta there).** Speak the top word as the notification. Android
findings:
- Two SIMULTANEOUS Bluetooth audio sinks: NOT portable via classic
  A2DP (one active media sink). LE Audio/Auracast (Android 13+/15,
  hardware-dependent) and OEM dual-audio exist but can't be the
  baseline. Stereo L/R panning is one sink — only separates channels
  within the same device, not private-vs-public.
- The RELIABLE private/public split: feedback speech -> the connected
  BT/wired earpiece (default media route); the user's speech acts ->
  built-in speaker, pinned per-stream via AudioTrack/MediaPlayer
  .setPreferredDevice (API 28+). TTS can be routed by synthesizing to
  buffer/file and playing through a routed AudioTrack. Hearing-aid
  (ASHA/LE) routes need a device test matrix.
- Two voices + volumes: directly supported — two TextToSpeech
  instances (or setVoice per utterance), per-utterance volume via
  KEY_PARAM_VOLUME, distinct AudioAttributes; feedback voice quiet +
  distinct from the user's "voice". JT already owns TTS plumbing
  (sayInterruptible/sayQueued) — a third "feedback" channel slots in.
- Design sketch: action option "Speak" alongside beep/flash; speaks
  the top candidate on fire (rate-limited); at low theta this becomes
  continuous audible prediction (screen-optional typing). Sequencing:
  after the EN round; needs the routing test matrix on real hardware.
- Conversation-partner context (recorded earlier in #4): Cliff's
  framing sharpens the consent story — AAC users' conversation pool is
  typically a small stable set (family/caregivers), so EXPLICIT
  per-person standing consent is practical, unlike general-public
  recording. Still far-future; revisit after the LLM reranker.

## Literacy vocabulary modules x NGB (Cliff, 2026-08-08 — future)

JT's literacy-acquisition feature imports text files as vocabulary
modules (flagging existing words / adding custom ones) to sharpen
next-letter prediction. With the NGB, an imported STORY can also yield
a companion n-gram module: run the same segmentation + counting over
the imported text on-device (a story is KBs — trivial), store as a
module-scoped tier, and merge it at fetch while the module is active
(third tier alongside static + user, module counts strongly boosted).
A student retyping the story's sentences then gets ample prediction
support end-to-end. Design once with the module-mask machinery the
vocabulary importer already has. RETURN TO THIS after Phase C and the
EN round (module tier slots naturally into the same fetch merge).

## Thread-1 close-out (2026-08-09)

Landed: CliffDev 96ef0a1 (both remotes) = NGB Phases A-C1 complete +
device-review rounds 1-2 + TAV rounds + all plan docs. dev deliberately
excluded until C2 lands + a few days' soak (then CliffDev -> dev as
"NGB v1"). TiengViet langpack v9 PUBLISHED (NGB tables + canonical
case). Tablet verified working end-to-end (zero-K followers, canonical
case, paged final commit).

Process gotchas paid for in this thread — do not relearn:
- Langpack release is part of DB-change definition-of-done: the in-app
  installer NEVER uses bundled assets; devices silently keep old DBs.
- GitHub release-asset CDN serves re-uploaded (--clobber) assets STALE
  for a while: after publishing, verify with a direct curl of
  manifest.json before on-device testing (Cliff lost a session to a
  528KB relic pack served from cache).
- ImeTextController.onFinalizeText seals the CURRENT composing text;
  it now composes the passed text first when it differs.
- JTUI.wldSelection() RETURNS the list; it never applies it
  (updateSelectionList does).
- TestJtui harness: single-key searches stay deferred (assert at 2+
  keys); paged selection is settings-gated (set WORD_SELECTION_PAGED).
- Per-syllable case counts cannot reconstruct proper-noun caps —
  canonical orthography rides ngb_units.display (Kaikki).

## EN base round (2026-08-10 — SIM FIRST, in progress)

VN stable point tagged **ngb-vn-stable-1** (CliffDev afe0e13, both
remotes); CliffDev remains the line; dev merge rides the (now English)
soak. Data provenance: analyzer corpus replaced with JT's license-clean
60,543-word rebuild; tables from an HPLT eng_Latn shard-1 stream prefix
(CC0, ledger (a); 87k docs, 51M segments -> 1.2M bigram rows at
min-count 3, 19 MB TSV); heldout 500,075 tokens from a DISJOINT shard-2
prefix. ngb_sim heldout gained --lang; ngb_table was already general.

First EN numbers (word-based: empty unit inventory, context = previous
word; gate verified a structural no-op without units):

  baseline   fts 1.0286   check2 1.2340   oracle 0.9593
  ngb_n2     fts 0.9685   check2 0.9188   oracle 0.8699

Readings: realistic-policy cut 10.7% vs the fts baseline — smaller in
RELATIVE terms than VN's 16% because English word completion already
gives a strong baseline (longer words, richer ITS) — but **KSPL drops
BELOW 1.0**: with NGB, JustType produces English at under one keystroke
per letter. Milestone worth stating exactly that way.

Slot-budget sweep DONE (runs/hplt/en_sim_sweep.txt): **every VN design
constant holds for English** — anchor=0 wins (check2 0.9002 vs 0.9188
a1 / 0.9248 a2), block size 2-6 is KSPL-flat (display choice), and the
zero-K window again carries most of the win (no-zk 0.9888 ≈ baseline).
Best EN realistic: **0.9002 KSPL, a 12.5% cut** vs baseline fts, oracle
0.8667. Consequence: the engine ships to English UNCHANGED — the
LanguageTraits fork is pure data semantics (no units, word context).
Also fixed en route: stale root-hint cache across language switch
(CliffDev 2851f29 — VN hint set grayed f/j/w/z on the EN layout).

M-fit DONE (runs/hplt/en_mfit.txt): same shape as VN — plateau
M ∈ [~3, 1000] at both depths, M=1 within 2-3 points of the plateau
top (depth-2 top-4: 54.1% at M=1 vs 56.1% best at M≈19). Zero-K top-2
21.9% (lower than VN's — English next-word entropy is higher; the
completion trie compensates, hence the strong baseline). DECISION:
ship EN exactly like VN — static tier at face value, USER_BOOST
unchanged; a shared ~M=5 static-tier boost is a recorded cross-language
tuning knob worth one future sweep (+2-3 pts hit rate on the table).

SIM CAMPAIGN COMPLETE. Every EN design decision is now measurement-
backed and IDENTICAL to VN's.

### EN JT build — LANDED (CliffDev 0560408, 2026-08-10, ON DEVICE)

- English is BUNDLED, not a langpack: EnglishNgb.txt in db/ ->
  buildEnglishDb picks it up by convention; the langBuildVersion
  content hash reseeds installed devices (verified on the tablet:
  "language build migrated for English", active DB 4.2 -> 11 MB, audit
  clean). NO CDN release for English — the release ritual applies to
  Espanol/TiengViet only.
- Table budget MEASURED (runs/hplt/en_prune_sweep.txt): full 37.9k
  contexts 7.9MB = 0.9188 check2; top-10k 6.1MB = 0.9233; top-5k
  4.5MB = 0.9282. SHIPPED top-10k (97.6% of transition mass, -0.45
  pts) as the every-APK bundle trade; curve recorded for revisiting
  (light/full tiering applies to langpack languages).
- LanguageTraits landed per the 2026-08-08 directive: derived at
  language init from LayoutSpec.tones + unit-inventory presence; EN =
  WORD_BASED, VN = syllable-based + tone keys (both test-asserted).
  Truth in labeling: no NGB fork consults it YET — the EN round
  proved every fork degrades correctly by data alone (empty units ->
  no 1N1 -> gate no-op); traits is the named home for future
  divergence. NgbRecognizer with an empty inventory = word-bigram
  learning (asserted: we->should lands in ngb_user).
- EnglishNgbTest: 5 end-to-end tests on the built DB (traits, data
  live, of->the in block, confidence FIRING IN ENGLISH, learning).

NGB v1 -> dev gate: Cliff's thorough ENGLISH soak, at last in a
language he reads. Behind it: Kaikki EN collocations (increment 2,
C3-ready), multi-word context experiment, LLM oracle sim.

### Sim accounting corrections (Cliff catch, 2026-08-10) — RESTATED

Cliff's "always at least once" Select assumption exposed a sim bug:
the fully-typed top-position finish charged ZERO Selects, assuming a
commit-by-continuation JT does not have (no terminator fires without a
prior Select; an AK without a selection EXTENDS the sequence). Fixed
in ngb_sim + ngb_confidence: every commit costs >=1 Select. Also
added KSPLS: denominator = letters + words, crediting the auto-emitted
space — the like-for-like basis vs keyboards where space costs a
keystroke (and the standard 5-chars-per-word WPM convention).

CORRECTED reference numbers (all prior absolute KSPL was optimistic;
RELATIVE conclusions — anchor=0, zero-K, theta curve shape, pruning
knee — survive, shifting near-uniformly):

              VN baseline  VN ngb     EN baseline  EN ngb(top10k)
  fts KSPL      1.5657      1.3755      1.2295       1.1360
  check2 KSPL   2.0687      1.2771      1.3462       1.0044
  check2 KSPLS  1.5887      0.9866      1.1114       0.8292
  oracle KSPL   1.5500      1.2423      1.0786       0.9621

Readings: the honest accounting makes the RELATIVE story stronger —
the realistic cut vs baseline-fts is now ~18.4% (VN) and ~18.3% (EN),
up from 16%/10.7%, because a prediction acceptance also saves the
committed words' mandatory Selects. Headlines, correctly stated:
**EN 0.83 keystrokes per character including spaces (KSPL ~1.00);
VN KSPLS 0.99.** The DKWF theta=0.65 optimum and confidence curves
shift near-uniformly (fts and conf policies both gained the same
per-commit Select) — re-verification queued, not urgent. NGB-D
fit/sweep artifacts predate the correction; refresh alongside the
next confidence iteration. After the
base round: collocation inventory (Kaikki EN multi-word entries) as
increment 2 — reuses the VN units machinery wholesale and lights up C3
span pull-in for English phrases — then the cherry-picked multi-word
context experiment (plan "NGB-D extensions" item 3).

## Sequencing

1. Corpus acquisition + register check (analyzer repo).
2. Unit inventory + `(context, target, count)` build with variable-length
   targets; prune (count ≥ 3 / top-K per context); size check (VN estimate
   2–6 MB SQLite, less gzipped).
3. Simulator core; baseline current UI; then sweep Scenario 1/2 (n=1 vs 2),
   anchoring choices, prediction-slot counts, promotion bounds.
4. Numbers pick the UI design → only then JT integration (schema in
   langpack, WLD/JTUI wiring, learned tier).

## Thread-2 close-out (2026-08-10)

One thread carried: NGB-D sim (calibration, theta curve, DKWF optimum =
shipped 65% default) -> NGB-D JT build (signal + honesty counters) ->
C2 context reconstruction -> C3 span pull-in -> paged-gap fix -> EN sim
campaign (all VN constants hold) -> EN JT build (bundled top-10k table,
LanguageTraits, migration-verified on device) -> root-hint cache fix ->
SLS observability (tiered display, jt learned-save/restore/reset,
stale APP_PKG fix) -> sim accounting corrections (mandatory Select,
KSPLS; corrected refs: VN 18.4% cut KSPLS 0.9866, EN 18.3% KSPLS
0.8292) -> N-usage recording fix -> the FULL SLS-optimization design
round (docs/.plans/sls.md is the authoritative spec — read it first).

NEXT THREAD = BUILD THE SLS OPTIMIZATION CAMPAIGN (Cliff: GO).
Everything is specced in sls.md; sequence there. Standing state:
CliffDev = the line (tag ngb-vn-stable-1 = VN stable point); dev merge
gated on Cliff's English soak; tablet on English, all features live,
learned-state baseline snapshot in .jt-learned/baseline-2026-08-10.
