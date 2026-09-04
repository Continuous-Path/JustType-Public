# Android Codebase Audit — Improvement Opportunities

Findings from a full-codebase audit (2026-07-06) ahead of the iOS port. Speed and quality are paramount for AAC users; items below are ranked by severity. Items marked **[issue]** are filed as GitHub issues; others are tracked here.

## Critical

1. **[issue] Main-thread blocking in SettingsRepository** — `SettingsRepository.kt:112` `runBlocking { cache.set(dataStore.data.first()) }` at construction (IME startup path) and `:380` `runBlocking` inside `DataStoreEditor.commit()`. ANR risk; settings writes from UI/IME threads stall. Fix: async warm-up with synchronous snapshot cache (fold into KMP Phase 4.1).
2. **[issue] PhraseRepository write races** — `data/PhraseRepository.kt:34` known TODO: non-singleton, "last write wins"; concurrent Activity writes can corrupt `phrases.json`. Sync file IO in `load()`/`persist()` under `@Synchronized` can also stall callers. Fix before the schema is shared with iOS.
3. **[issue] Static context leak** — `SettingsRepository.kt:38` top-level `sidecarContextRef` AtomicReference holds a Context forever; corruption-recovery may use a dead context and fail silently.

## High

4. **[issue] Silent subsystem-init failures** — `JustTypeIME.onCreateInputView()` has multiple `catch (_: Exception) {}` around HeadTracking/Joystick/ExternalSwitch init; failures leave subsystems broken with no log. Add logging + a degraded-mode state.
5. **[issue] Missing API guards** — `requestShowSelf()` (API 28) and `createWindowContext()` (API 31, `TouchDetectionOverlay.kt:59` falls back to an unsafe context) on minSdk-26 paths. Lint baseline suppresses the warnings.
6. **BroadcastBridge.onReceive does synchronous work** (`ime/BroadcastBridge.kt:62-68`) — heavy callbacks block the broadcast thread; post to a Handler.
7. **[issue] Lint baseline is 5,955 lines** (`app/lint-baseline.xml`) — hides InlinedApi/NewApi and real bugs (e.g. CutPasteId in DeveloperSettingsActivity). Ratchet: forbid growth in CI, burn down by package.

## Engine (logic/)

8. **[issue] WLD god class** (886 LOC) — trie management + learning + phrases + diacritics in one file; private nested types untestable in isolation. Split TrieSearcher / learning after Phase 0 tests exist.
9. **[issue] JTUI god class** (5,052 LOC) — pure state entangled with android.graphics. Split tracked as KMP Phase 3 (KeySequenceModel / SelectionListModel / GridStateModel / TextComposer).
10. **[issue] Learning writes not batched** — per-selection UPDATEs (`markWordUsedByID`, `incrementCaseCountByID`) issued individually; batch per flush transaction (fold into KMP Phase 2.2).
11. **getOrCreateStats N+1 pattern** (`WordDb.kt:510-562`) — up to 3 queries per new word; acceptable today (async), consolidate during the JtSql port.
12. **Silent failure in translateToKeys** (`WLD.kt:171-182`) — unmappable char (emoji, rare diacritic) returns null; `addCustomWord` fails without user feedback.
13. **Prepared statements not reused** for hot single-row queries (`WordDb.kt:414-416`).

## Engine behaviors locked in by Phase 0 characterization tests

These are *current* behaviors now pinned by tests (`WldEngineInvariantTest`, `CandidateRankingCharacterizationTest`, `GoldenFixtureTest`). Any change requires updating fixtures + a MINOR core bump — and identical treatment on iOS.

- **freqClass 13/14 boundary is asymmetric** — `raw > 8 → 13` (strict) while all other tiers use `>=`; so 9→13, 8→14. Additionally, WLD keeps a private byte-for-byte duplicate of `WordDb.computeFreqClass` — deduplicate during the KMP move.
- **[issue — FIXED] Case-learning "margin" did not gate the CaseType flip** (#21) — flip fired on mere count equality, so a fresh lowercase word flipped LOWER→TITLE after a single title-case use. Fixed: flip now requires `newMyCount >= max(rivalCount, margin)` — exactly `margin` non-preferred usages from any pristine state. Known residual (documented in the KDoc): counts are cumulative, so flipping *back* after a flip can take fewer than `margin` usages once counts are tied; true two-way hysteresis would need streak tracking (deliberately out of scope — raw counts also drive case-variant display in `JTUI.buildDisplayEntries`).
- **Termination codes are loose** — `MAXD` is returned even when the trie was exhausted before the depth cap; `BOT` is reported for empty results even when the branch exists but was fully mask-filtered.
- **Start-node exact matches don't count toward `maxWordCompleteEntries`** — only BFS-collected completions do.

## Test gaps

14. **[issue] No direct engine tests** for BFS termination, case-learning mutation, freq demotion, mask filtering (being addressed in Phase 0.1/0.2 with invariant tests + golden fixtures).
15. No concurrency tests for PhraseRepository / SettingsRepository; 1 instrumented test total; no perf/stress tests (100k-word trie, concurrent DB writes).

## Medium / polish

16. Hardcoded `Color.parseColor` in ScanSubsystem, SettingsOverlayController, KeyFeedbackController — move to color resources (dark-mode/contrast support).
17. `requestLayout()` + `invalidate()` chained in three KeyHistoryView update methods — consolidate.
18. Overlay add/remove races: `AccessiblePrompt` permission-check-then-add; NavigationModeService overlay reconciliation polling.
19. Hardcoded UI strings in settings/debug overlays — i18n gap (en/es/sw are otherwise supported).
20. `Dispatchers.IO` hardcoded in several launch blocks (InjectDispatcher detekt finding) — inject for testability.

## HeadBoard repo (Continuous-Path/HeadBoard)

21. **[issue] 11+ unmanaged `new Thread()`** in `CursorAccessibilityService` (lines 281, 2027, 2072, …) — no pool, no lifecycle scoping; leak/race risk on service destroy.
22. **[issue] Unsynchronized ML-callback state** — `FaceLandmarkerHelper.postProcessLandmarks()` writes `currHeadX/Y`/blendshape arrays read by the main-thread tick loop without synchronization.
23. **[issue] Per-frame Bitmap allocation** (~14MB/s at 30fps, `FaceLandmarkerHelper.detectLiveStream()`) — reuse buffers.
24. ArrayDeque race in `CursorController.cursorPositionHistory`; Handler callbacks may fire post-destroy; blendshape hysteresis lacks a timeout fallback.
