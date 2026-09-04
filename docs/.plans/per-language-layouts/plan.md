# Per-Language Layouts (Spanish first) — Plan & Status

Reconstructed 2026-07-23 from the "JustType keyboard layout optimizer" session
and the layout-analyzer memory notes; this plan previously existed only in
session context. Companion repo: `~/Documents/GitHub/layout-analyzer`
(private ckushler/layout-analyzer). All work lands on **dev**.

## Goal

Each language DB carries its own optimized ambiguous layout (found by the
layout-analyzer), and JT derives every layout-dependent surface from the
active DB at runtime — no hardcoded per-language layouts in JTUI.

## Phases

**Phase 1 — corpus + optimizer groundwork** ✅
- Spanish corpus cleanup/refinement (dev `9d7c0ca`, Cliff's squash).
- layout-analyzer repo stood up from the 2023 research scripts; evaluator
  regression-locked to legacy E values.

**Phase 2 — choose production layouts** ✅
- Spanish: `dapz / firh / co.lw / tbnqk / guys / vemjx` (E = 0.0859,
  accents fold onto base keys, ñ→n; spell_cell_budget 6 so ñ + accents all
  reach Two-Key Spell level 2). Arrangement + mnemonics:
  layout-analyzer `results/espanol/arrangement.md`.
- English: x reverted to o-key — `gemz / tr-'p / is.kw / lufcy / banq /
  ojvhdx` (E = 0.0427) (dev `5c86544`).

**Phase 3 — build side** ✅ (dev `9ecf873`)
- `{Id}Layout.json` (analyzer `report` output, formatVersion 1: lettersPerKey,
  grids, alpha{lettersPerKey, grids}, fold, spellMode, metric) baked verbatim
  into each DB's `metadata` table as `layoutJson` by `BuildWordDbTask`.
  `EnglishLayout.json` + `EspanolLayout.json` committed.

**Phase 4 — runtime consumption** ✅ code / ⬜ device verification
Landed (dev `0f6f199`, `0dad0b7`):
- `LayoutSpec` parses `layoutJson`; JTUI derives Optimized main/spelling/Spell
  pages + Word-List-Display letters from the active DB's spec.
- `KEY_OPTIMIZED_LAYOUT_SOURCE` pin setting ("match" default = follow active
  language; critical pref → JTUI reinit).
- `LanguageEntry.layoutJson` registry mirror (JTUI.init + LangpackInstaller).
- Per-language Alphabetic via layout JSON `alpha` section (Spanish:
  abcd / nopq / efgh / rstu. / ijklm / vwxyz).

**SMOKE TEST: PASSED 2026-07-24** (Pixel Tablet, dev build). The test earned
its keep — it caught one process failure and three bugs, all fixed on branch
`claude/vietnamese-word-list-resources-19ba3b` (commits b5937a0, ae88545,
de35737; full suite 1342 tests green):
- **Publish step missed** (process): the langpack artifacts on the GitHub
  release (`langpacks-v1` tag, Continuous-Path/JustType-langpacks) were never
  refreshed after Phase 3 baked layoutJson into the DBs — the installer only
  installs from that release, so devices kept getting the stale DB, and
  `Espanol.version` was never bumped so installed devices saw no UPDATE.
  **Lesson → new release checklist: whenever a language's corpus, layout, or
  DB build inputs change: (1) bump `{Id}.version` in langpacks.properties,
  (2) run `packageLanguageArtifacts`, (3) `gh release upload` the new .gz +
  manifest.json to the langpacks release.** Also useful: the Developer
  Settings manifest-URL SAVE button clears the 24 h manifest cache.
- **Stuck layout** (bug): KEY_OPTIMIZED_LAYOUT_SOURCE was never routed in the
  IME's settings-change listener (reinit only on restart), and langpack
  removal left the layout-source pin dangling at the removed language (blank
  settings row, layout stuck). Fixed: key routed; both re-pointing guards
  centralized in `LangpackInstaller.remove` (unit-tested).
- **Two-Key Spell inefficiency** (bug): variant placement required a mapped
  spatially-adjacent free cell, so on 5-letter keys diacritics got grouped
  behind a drill page while cells sat empty; multi-variant letters (u → ú ü)
  grouped even with several free cells. Fixed: spread each variant into its
  own free cell when available (one-press emit).
- **Auto-space failure under cross-language pin**: observed once pre-fix,
  never reproduced after the reinit fix — believed to be a stale-state
  symptom. Watch for recurrence.
- **Unreproduced observation** (2026-07-24): after several hours idle, the
  IME didn't appear on text-field tap despite being the only enabled
  keyboard; toggling Gboard on / JT off-on / Gboard off restored it. Possibly
  an artifact of repeated force-stops during testing. Note if it recurs.
- Tooling: `./jt` hang-recovery kills leaked workers but not a wedged Gradle
  daemon (caused 4 consecutive test-suite hangs; `--stop` cured). Follow-up
  task spawned to recycle the daemon in the sweep.

Original checklist (all items exercised and passing):
1. Install dev build; install Spanish langpack; switch English ↔ Spanish.
2. Optimized main layout matches `dapz/firh/co.lw/tbnqk/guys/vemjx`;
   English still `gemz/tr-'p/is.kw/lufcy/banq/ojvhdx`.
3. Typing with accent folding: `si`/`sí`, `año`/`ano` disambiguate by
   frequency in the Selection List.
4. Two-Key Spell: ñ and all accented vowels reachable at level 2; spelling
   pages match the layout JSON `spellMode`.
5. Alphabetic mode is per-language (Spanish grid above, not English's).
6. `KEY_OPTIMIZED_LAYOUT_SOURCE`: "match" follows language switch; pinning a
   language keeps its layout while typing the other; changing the pref
   reinitializes JTUI cleanly.
7. Langpack REMOVE + reinstall: registry `layoutJson` mirror stays
   consistent (no stale layout after remove).
8. Word-List-Display letters render the Spanish letter set.

## Related follow-on (separate plan)

Vietnamese: `docs/.plans/vietnamese/plan.md` — gated behind the smoke test
above.
