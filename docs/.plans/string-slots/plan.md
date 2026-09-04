# String-Building Slots + Ambiguous Digits

Status: **SHIPPED IN ALL THREE LANGUAGES** (dev a8bbfb8; VN langpack v4,
Espanol langpack v4, English bundled). ES smoke passed; EN smoke found NO
digit slots on device (bundled-DB refresh suspected — tracked in
../enhanced-analyzer/plan.md Phase 3). Analyzer TOML slot-definition backfill
for EN/ES is subsumed by the enhanced-analyzer pipeline (same plan).

## Motivation

Personal numeric strings (phone numbers, addresses, zips) and identity strings
(emails, handles) are a small, closed, high-frequency set. 123 Numeric mode costs
two mode switches + a keystroke per digit EVERY time (~13-15 strokes for a phone
number); as a custom word the same string costs its key count once, ambiguously,
and becomes first-choice with use. For JT's users every keystroke has real motor
cost. Digits carry zero corpus mass, so E is untouched for all languages;
ambiguity materializes only for the user who added a digit string, only on that
sequence. 123 mode remains the untaught fallback. ABBREVIATED PHRASES remains
the path for strings using characters beyond the slot set.

## Decisions (Cliff)

1. **Guaranteed ambiguous char set, all languages**: `. @ - _ / + #` plus digits
   `0-9`. Tail chars (`: = ? & % ~`) stay Symbols/spell-only.
2. **Digit clustering 1-5 / 6-0** (the physical keyboard top row split in half;
   0 last, where users expect it). Not 0-4/5-9.
3. **Digit slots on page Keys 2 and 4 in EVERY language** (vertically adjacent,
   1-5 above 6-0) — uniform cross-language muscle memory.
4. **Slot labels digit-first**: `15_` and `60.` (punct last, matching its L3 key).
5. **Two-Key Spell level 3 for digit slots (6 chars)**: fixed COLUMN reading
   order — the same direction as natural tone order and paged-selection fill:
   Key 0: 1|6, Key 3: 2|7, Key 5: 3|8, Key 2: 4|9, Key 4: 5|0, **Key 7: the
   slot's punctuation char** (_ or .). Smaller slots (≤5 chars, e.g. `#/-@+`)
   keep the shipped anchored last-N rule.
6. **Stacked slot display**: slots with 4+ chars render as two tight rows in
   one cell block (e.g. `#/-` over `@+`), highlighted/grayed as a unit.
7. Accepted trade-off: Vietnamese `.` merges into the Key 4 digit slot — precise
   `.` entry in Two-Key Spell goes from 2 to 3 keystrokes (ambiguous typing of
   `.` unaffected).
8. **Digit slots double-tap their host key** (Cliff, post-smoke): the slot is
   pinned to the grid cell whose Two-Key Spell level-2 position maps back to
   the host key — `15_` at Key 2's upper-right cell, `60.` at Key 4's
   middle-right — so digit entry is "double-tap the key, then pick." This is
   the ALL-LANGUAGE rule (SlotDef.cell in the analyzer). Displaced letters
   reflow to remaining cells (VN: ô -> cell 3, x -> cell 6).
9. **Digit-slot L2 key face** renders its six chars as a spatial map of the
   L3 pick keys (left column 1,2,3 = Keys 0/3/5; right column 4,5,punct =
   Keys 2/4/7) instead of the elided label.

## Per-language slot compositions

| page key | Vietnamese | English | Spanish |
|---|---|---|---|
| Key 2 | `15_` (1-5, _) replacing `#/-` cell | `15_` new cell | `15_` new cell |
| Key 4 | `60.` (6-0, .) absorbing the `.` cell | `60.` new cell | `60.` absorbing `.` if on-key, else new |
| Key 5 | `#/-` + `@+` stacked (was `@_`) | `#/@+` new cell (has `-` `'` already) | `#/-@+` new cell |

(Exact English/Spanish key choices finalized at implementation against their
free cells — Key 2/4 for digits is fixed; the punctuation slot goes on the
lightest key with a free cell. English: 7 free cells; Spanish: 9; Vietnamese: 0
— all fits by slot merging, verified.)

## Implementation phases

**A — contract + JT rendering**
- layoutJson v2 `slotGroups` entries gain optional `display` (may contain `\n`
  for the stacked form); `chars` stays the flat list. LayoutSpec parse + tests.
- SquareButton: two-baseline rendering for cells containing `\n`; block grays/
  highlights as a unit (any-char rule already shipped).
- JTUI spell builder: digit-slot (6-char) L3 pages use column order with punct
  on Key 7; smaller slots keep anchored last-N.

**B — analyzer emission** (layout-analyzer)
- Language TOMLs: slot definitions above; report emits chars + display.
- Regenerate the three layout JSONs; regression-lock E (must be unchanged —
  slot chars are zero-mass).

**C — DB + langpack release** (REQUIRED — installer only reads the release)
- Rebake DBs; bump `TiengViet.version` -> 3, `Espanol.version` -> 4 in
  langpacks.properties; `packageLanguageArtifacts`; `gh release upload` .gz +
  manifest. English is bundled (no release).

**D — on-device smoke**
- Slot labels render (stacked block on Key 5 VN); L3 digit picks land per the
  column map; NEW WORD: add a phone number (+84...) and an email with digits;
  both then type ambiguously and rank up with use; digit slots gray correctly
  (black only when a custom word continues with a digit); `.` still reachable
  in spell at 3 keystrokes; 123 mode unchanged.

## Onboarding note

Steer users toward adding WHOLE personal strings (full phone number, full
email) as NEW WORDs, not fragments ("27"). Damage from fragments is localized
(one extra candidate on one short sequence) but list clutter is avoidable.
