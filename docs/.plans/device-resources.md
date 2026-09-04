# Device Resources & Lite Versions — Analysis + Plan

Status: analysis 2026-08-06 (Fable + Cliff). Live PSS measurement pending
(tablet was detached); estimates below are computed from real corpora/layouts.

## Principles (Cliff, 2026-08-06)

1. JT is the device's PRIMARY purpose for its users — it may consume the
   majority of device resources.
2. No economic barrier: if the cheapest obtainable devices can't run JT,
   that is a serious problem requiring Lite variants — the langpack
   download system is the delivery mechanism for them.

## The floor: cheapest-device landscape (early 2026)

- New ultra-budget ($40–90): Android Go (mandatory <=2GB RAM). Quad/octa
  Cortex-A53 1.4–2.0GHz, 1–2GB RAM, 32GB storage, Android 12–14 Go.
  **Per-app Java heap limit typically 96–128MB** — this is THE binding
  constraint. ~600MB–1GB RAM serves all apps; aggressive LMK.
- Used/refurb ($30–80): usually better value — 3–4GB RAM, 256MB heap,
  Android 9–12. JT's minSdk 26 (Android 8.0) already covers this market.
- Budget tablets ($60–120, relevant for switch/head-tracking users):
  mostly 3–4GB RAM; 2GB Fire-class is the tightest realistic case.

Planning floor: **2GB RAM / ~128MB app heap / quad A53 / 32GB storage.**
Comfort tier (3–4GB / 256MB heap) starts ~$60 used.

## JT's footprint (exact node/entry counts; heap estimated at
## ~100–130B per node/entry as Kotlin objects)

| Language | entries | trie nodes | est. WLD Java heap |
|---|---|---|---|
| TiengViet | 9,005 | 3,912 | ~2–3 MB (runs on anything) |
| English | 50,948 | 103,304 | ~20–30 MB |
| Espanol | 194,897 | 278,020 | **~60–90 MB — already tight on a 128MB-heap Go device, pre-NGB** |

Storage (langpacks 0.2–5.1MB gz) and CPU (bounded per-keystroke search,
single-digit ms on A53; TTS costs more) are non-issues everywhere.

## NGB impact: storage-only by construction

Fetch-per-commit design: bigram table stays in SQLite; RAM holds only the
current context's top-K rows (~tens of KB); one indexed query per
COMMITTED WORD (not per keystroke), sub-ms. VN K=200: ~25–35MB installed,
~0 RAM, ~0 CPU. **The NGB does not move the device floor.** Large-vocab
languages stay linear via the two dials: K per context + restrict
contexts to top-N frequent words.

## Recommendations (agreed 2026-08-06)

1. Proceed with the NGB with no resource anxiety (storage-only).
2. Measure real PSS via `dumpsys meminfo` on the Pixel Tablet (pending —
   do at next attach) to pin the heap estimates.
3. **Acquire one Go-class reference device** (~$50, 2GB: Nokia C-series /
   Galaxy A0x Core) before the first user batch — turns this analysis
   into a pass/fail bench.
4. **ES-lite langpack** when Spanish targets low-end devices: prune
   ~195k -> 60–80k words (~99% token coverage), roughly halving the trie.
   This is today's real pressure point, not n-grams.
5. Consider `android:largeHeap="true"` (currently unset) — roughly
   doubles heap where granted; aligns with principle 1 on comfortable
   devices without hurting the floor.
6. Longer-term, if Go-tier + big-vocab matters: rebuild WLD trie as
   primitive arrays instead of an object graph (~5–10x heap cut). An
   optimization, not a redesign.

## Planned feature: device-aware langpack recommendation

On the language download page, assess the device and recommend a variant
(Cliff, 2026-08-06):

- Signals (all public APIs, no permissions): `ActivityManager.
  isLowRamDevice()` (the Go flag), `getMemoryClass()` (the per-app heap
  limit in MB — the binding constraint directly), `MemoryInfo.totalMem`,
  `StatFs` free storage, core count.
- Manifest schema: langpack entries gain optional `variant` ("full" /
  "lite") + `minHeapMB` (+ sizes already present). Old clients ignore
  unknown fields; entries without variants behave as today.
- Client rule: if `getMemoryClass()` < the full variant's `minHeapMB`
  (or `isLowRamDevice()`), default-select the Lite variant with a short
  explanation; the user can always override (principle 1: it's their
  device and their call). Warn separately on low free storage.
- Fires only when a language actually has variants; the machinery can
  land with the manifest schema at any time and stays dormant until the
  first Lite langpack (ES-lite) exists.
- Both settings surfaces get the same recommendation logic (settings
  parity contract applies to the installer UI).

Related: docs/.plans/ngram/plan.md (NGB design + measurements).
