# Paged Word Selection (hybrid Two-Key Select)

Status: **v1 core implemented 2026-07-27** (settings + state machine + paged key
rendering + undo integration). Remaining: UI polish (below), on-device test.

## Motivation & evidence (layout-analyzer `selectmode.py`, Vietnamese corpus)

Linear Select costs 1+rank selection keystrokes per word; pure Two-Key Select
(each Select pages 6 list rows onto the letter keys, one key press picks) costs
2 + rank//6. Vietnamese production layout: ES1=1.0976 vs ES2=2.0004 — pure
paging nearly doubles selection keystrokes (92.97% of tokens are first choice)
and no layout can help (ES2 floor is 2.0; optimizer confirmed, runs/vn_es2_1).
The **hybrid** — first N Selects step linearly, then pages — keeps linear's
cheap common case and bounds the tail: ESH(2)=1.1052 (+0.7%), ESH(3)=1.0994
(+0.17%), worst case 6 keystrokes vs 16. E is already rank-weighted (E == ES1-1),
so existing layouts stay optimal for the hybrid.

## Decided (Cliff 2026-07-27)

- Page = **6 words on the 6 letter keys**; reading order = page-key columns
  (Keys 0,3,5 down the left, 2,4,7 down the right — same as VNI digit order).
- Pick = set the selection (same semantics as linear Select highlight; commit
  happens on continuation, exactly like list mode).
- **DELETE = true UnDo**: page k → page k−1 → pre-paging state (mobility users
  overshoot Select often). Implemented via the normal undo stack —
  `State.pagedSelectPage` is a constructor property so snapshots restore it.
- **End of list: stay on the last page**, keystroke does nothing + error beep
  (consistent with list-mode end behavior).
- Settings: `Word Selection` (List-based | Page-based; per-language default,
  currently "list" everywhere) and `Number of Listed Words` (0–3, default 2;
  0 = every list paged from the first Select — for users who need one
  consistent response). Any other key during paging exits paging, then
  processes normally.

## v1 implementation map

- `Constants`: KEY_WORD_SELECTION_MODE (list/paged), KEY_PAGED_LISTED_WORDS.
- `JTUI`: `State.pagedSelectPage`; `handlePagedSelectKey` intercept at the top
  of `buttonPressed` (before finalize-on-ambig); KF_Select enters/advances
  pages; `selectWillChange` accounts for paging so no-op presses don't push
  undo snapshots; `renderKeyLabelGrids` shows page words centered (cell 4),
  verbatim (no shift/caps transform). Picking a "P" (page-jump) row behaves
  as linear Select on it.
- `SettingsRegistry`: two rows in the Keyboard section (always visible for
  now). Tests: `PagedSelectScenarioTest`.

## Deferred / UI polish

1. **Grouped selection-list display** (Cliff's sketch): listed words singly,
   then boxed groups of 6 mirroring the pages; on paging, highlight the active
   group. Kills the transition surprise. Needs selection-list render work.
2. Settings visibility: hide `Number of Listed Words` unless Page-based
   (registry rebuild on pref change), and Cliff's option to hide the whole
   group per language.
3. Per-language defaults for mode (data says hybrid n≥2 is near-free for all
   languages; only n=0 is language-sensitive).
4. Speech/accessibility feedback when a page opens (speak the page words?);
   scanning/two-switch interplay — paged mode changes the scan target set.
5. Word-on-key display tuning (center cell may render small for long words).
