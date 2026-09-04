# Select-behavior test recipes — triggering the demoted-FTS state while texting

Companion to sls.md "Adaptive select-behavior mechanisms" / Thread-4.
Source: the residual-diagnostic instance logs (current shipped config,
cold DB) in `layout-analyzer/runs/hplt/sls_residual_failures_{en,vn}.tsv`
— EN 3,567 distinct (context, word) instances (3.07% of fully-typed
states), VN 5,854 (7.26%). Each recipe reproduces a fully-typed state
where the typed word sits BELOW the head; press Select there and the
substrate records an episode. Two state classes (the substrate's
middle bucket axis):

- **m — head miss**: nothing fully typed in the head, your word below
  it. The state mechanism A remedies: force modes PROMOTE here, and
  episodes land in `ns_m:` buckets.
- **d — key ambiguity**: a fully-typed alternative already heads the
  list (us over uk — same keys). Digging here is necessity, not
  strategy; force modes STAND DOWN by design (promoting would re-rank
  two legitimate readings). Episodes land in `ns_d:` buckets —
  observation-only states.

These flows are also pinned as automated regression tests
(SelectBehaviorEndToEndTest replays organs / to / situ / uk on the
bundled DB), so device runs are for feel, not verification.

## Setup

1. **Baseline**: recipes assume a cold learned state. `jt
   learned-save <name>` then `jt learned-reset` before a session
   (restore after). On a warmed DB, recency/usage rescues these words
   into the head — expected, not a bug (see last section).
2. **Ladder** (Dev settings → "Select behavior"): Observe to collect
   field episodes; Force Head / Force Page 1 to feel the promotion on
   the `m` recipes.
3. **Signal**: with the confidence signal OFF (default) every Select
   press is a no-signal state. If it's ON and fires, force modes stand
   down and episodes record under `s_*` — also worth feeling once.
4. **Verify**: Dev settings → "Show select-behavior stats" — `ns_m:`
   buckets grow on the m-recipes (`F.p1`/`F.pd` when you dig in
   Observe; `F.h` under Force Head), `ns_d:` on the d-recipes.

## Flow per recipe

Type the CONTEXT keys → Select (confirm the context word) → type the
TARGET keys → the head now shows OTHER words while your word is fully
typed below → **press Select**:

- **Observe**: step/page down and pick your word.
- **Force Head** (m-recipes): your word jumps to slot 1 on that press.
- **Force Page 1** (m-recipes): it moves to the leading page-1 cells
  (most visible on the deep rows).
- **d-recipes**: no reorder in any mode — you should see the force
  modes deliberately NOT act.

**Each successful pick warms the word** (useCount + recency) — a
recipe stops reproducing after you pick its target once or twice.
Move on, or `jt learned-reset`.

## English (apostrophe K400 table)

| # | Context (keys) | Target (keys) | Head you'll see | Target at | State |
|---|---|---|---|---|---|
| 1 | and (5-5-7) | organs (7-2-0-5-5-3) | organized, organization | pos 3 (page 1) | **m** — promotes |
| 2 | said (3-5-3-7) | to (2-7) | the, that | pos 3 (page 1) | **m** — promotes (dual-source row) |
| 3 | to (2-7) | us (4-3) | use, find | pos 3 (page 1) | **m** — promotes (dual-source row) |
| 4 | the (2-7-0) | sa (3-5) | same, way | pos 5 (page 1) | **m** — promotes |
| 5 | in (3-5) | situ (3-3-2-4) | situation, kitchen | pos 8 (last page-1 cell) | **m** — promotes |
| 6 | the (2-7-0) | uk (4-3) | us, first | pos 3 (page 1) | d — us fully typed too |
| 7 | the (2-7-0) | cap (4-5-2) | car, latest | pos 6 (page 1) | d — car fully typed too |
| 8 | the (2-7-0) | hon (7-7-5) | job, don | pos 3 (page 1) | d — job/don fully typed |
| 9 | the (2-7-0) | zoo (0-7-7) | government, god | pos 3 (page 1) | d — god fully typed |
| 10 | of (7-4) | hbo (7-5-7) | had, dad | pos 9 (**page 2** — deep dig) | d — had/dad fully typed |

## Vietnamese (full@200 table; tone key typed at syllable END — under
## TAV type the tone key right after its carrier vowel instead)

| # | Context (keys) | Target (keys) | Head you'll see | Target at | State |
|---|---|---|---|---|---|
| 1 | của (2-2-5-2) | chúng (2-3-2-0-0-3) | chúng tôi, chúng ta | pos 3 (page 1) | **m** — units above don't count as typed |
| 2 | trên (7-2-7-0) | thị (7-3-4-7) | thị trường, thật | pos 5 (page 1) | **m** — promotes |
| 3 | lượng (3-0-0-0-0-7) | giao (0-4-5-7) | giao dịch, giáo dục | pos 3 (page 1) | **m** — promotes |
| 4 | lệ (3-7-7) | ăn (3-0) | sở hữu, lớn | pos 3 (page 1) | **m** — promotes |
| 5 | mua (5-2-5) | phế (4-3-7-3) | xét, phó | pos 4 (page 1) | d — xét/phó fully typed |
| 6 | in (4-0) | tờ (7-0-5) | tờ rơi, từ | pos 3 (page 1) | d — từ fully typed |

Positions are flat list rows (head = rows 1-2, page 1 = rows 3-8);
the paged grid renders column-major, so pos 3 = page-1 top-left. Head
column shows sim rows 1-2 at the state; small drift on device is fine
— an m-state holds whenever your typed word is below rows 1-2 and
nothing in rows 1-2 is a full-length match of your keys. Thousands
more instances, count-sorted, in the two tsv files.

## Warming is the third thing to feel

Pick an m-recipe target once, retype the same context+word: it should
now head the list (recency bucket 0 under the tuned 2.0/1.75 weights).
That reversal is the premise the EWMA-decayed counters are built on —
and it's also pinned as a test (`warming reverses the demotion`).
