# Vietnamese Language Support — Plan

Status: **Phase 2 code COMPLETE 2026-07-25** (dev `12fc872`; TiengViet langpack
v2 published). Landed: corpus 9,205 syllables + case seeds + i/a contamination
caps; LayoutSpec v2 (tones/fold/functionKeys/slot groups); tone-keystroke trie
encoding; tone marks on keys + Tone Key Labels setting (mark/VNI/Telex);
NATURAL TONE ORDER — ngang,sắc,huyền,hỏi,ngã,nặng on page Keys 0,3,5,2,4,7
(VNI digits read sequentially down the columns; pure permutation, E=0.097557);
per-language function keys (Symbols→Key4, Functions→Key5, Navigation→Key7 —
heavy one-key words ư/ô+u/y+e stay function-free); Two-Key Spell tone level
(tone vowel → pick marked form on its tone's key; toneless key = ngang; slot
groups @_ #/- drill to char pick). REMAINING: on-device smoke test (2d).

Phase 1 was completed 2026-07-24 (layout-analyzer main `37cd62f`).
Production layout locked from the 500-seed deep run (seed 441, E=0.096456,
all five tone keys distinct): `2adkm / 5êot / côruv / eshylă / bơgưnq / âđpxi`
— huyền=key0, nặng=key1, hỏi(r)=key2, sắc(s)=key3, ngã(x)=key5. The report
emits the formatVersion-2 JSON contract (tones + labels, punctuation slot
groups, alpha section, toneThirdLevel spell scheme); see layout-analyzer
`results/vietnamese/arrangement.md` and `results/vietnamese/report.txt`.
Next: Phase 2 (JT integration, dev branch).

## Design (decided)

Vietnamese is typed as **syllables** (spaces delimit syllables, not words). The
prediction unit is the syllable; the word DB is a syllable list.

1. **Alphabet = the official 29 Vietnamese letters** (quality vowels ă â ê ô ơ ư
   and đ are distinct letters). f/j/w/z are not Vietnamese but get slots as
   E-neutral free symbols so URLs/loanwords can be added via NEW WORD and typed
   ambiguously. Vietnamese words themselves embed no punctuation
   (corpus-verified — no `'`, no productive `-`), so the remaining two of the
   36 display slots go to **string-building punctuation** for user-added
   words (URLs, emails, handles): proposed set `@ _` (one slot, identity
   chars) and `# / -` (other slot, path/date chars), with `.` keeping its own
   slot — 36/36. Multi-char slots are fine: ambiguous typing only needs
   key identity; precise entry resolves in Two-Key Spell (3rd-keystroke pick,
   same mechanism as tones — Phase 2 detail). Second-tier chars (`: & + = ?`)
   stay spell-only unless feedback demands promotion.
2. **Tones are keystrokes, not letters** (mirrors Telex/VNI, the universal
   Vietnamese input methods). One tone per syllable. Default: tone key typed at
   **syllable end**; **ngang unmarked** (no keystroke, 51% of syllables).
   Tone-after-vowel is a possible future *runtime* preference — same DB, same
   layout (measured cost of serving both: ~5% E), trie encoding choice only.
   Not offered as a separate DB download.
3. **Telex alignment**: each tone lives on the key of its Telex letter — sắc on
   the s-key, hỏi on r, ngã on x; f and j (free symbols) are placed on the
   huyền and nặng keys. Constraint cost measured: E 0.0977 vs 0.0937
   unconstrained (+4%), P(first choice) unchanged at 92.9%. Accepted.
4. **Tone display**: marks render at the bottom of their key, not in letter
   slots. User preference for label style:
   (a) standalone marks ◌̀ ◌́ ◌̉ ◌̃ ◌̣ — default;
   (b) VNI digits (desktop typists);
   (e) Telex letters s´ f` r̉ x~ j. (phone typists);
   (d) [with after-vowel placement, later] dynamic display: the tone forms of
   the previous key's vowels — mid-sequence spelling confirmation. No layout
   constraint needed: display adapts to that key's 1–3 vowels.
5. **Two-Key Spell** (Cliff's scheme): Level 1 = key containing the quality
   letter (e.g. ô); Level 2 = select it (standard cell); Level 3 = pick tone on
   the key carrying that tone mark (6th tone-less key = ngang). Tones consume
   no cells, so 29 letters + f j w z + `.` = 34 ≤ 36 cells — everything
   spellable, incl. diacritics for NEW WORD entry.
6. **Alphabetic mode**: 33-letter sequence = Vietnamese alphabetical order
   with f j w z interspersed at their Latin positions (defensible — modern
   dictionaries do this for foreign words; and Next-Letter Hints gray them
   anyway). The natural 6-way split
   `aăâbcd / đeêfgh / ijklmn / oôơpqr / stuưv / wxyz`
   puts all five Telex tone letters (s f r x j) on five DISTINCT keys with no
   adjustment — the r|s break falls on a key boundary — so Telex-aligned tone
   keys work in Alphabetic mode too (sắc→s-key4, huyền→f-key1, hỏi→r-key3,
   ngã→x-key5, nặng→j-key2). Measured: E = 0.363, P(first) = 81.3% — severe
   vs Optimized 0.098/92.9%, as expected for Alphabetic; user is cautioned.
   Physical key order follows the English convention (Key 0→3→5, then
   2→4→7); punctuation goes on the spare capacity of the last two keys
   (5- and 4-letter), with break-point flexibility available if display
   needs it.
7. **Next-tone-mark prediction (later; Cliff spec 2026-07-25)**: tone key labels
   should mirror Next-Letter Hints — regular (black) when the current selection
   list contains a word the tone can apply to, grayed (pale red) otherwise.
   With tone-after-vowel entry, the engine should also know WHICH letters each
   tone can apply to (per selection-list state) and display only those tone
   forms; both signals are likely cheapest to produce during trie traversal.
   Interim behavior (shipped with 2d fixes): tone-label cell is never grayed —
   the per-CHAR graying was wrong for VNI digits (always gray) and misleading
   for Telex letters (tracked the LETTER's continuations, not the tone's).
   Telex labels also stay lower-case under Shift/Caps.
8. **Selection-list ordering after a tone key (later; Cliff spec 2026-07-25)**:
   after letters + tone-mark key, fully-typed objects come first — the
   prior one-letter/short words matching that tone, plus any completed
   syllables (e.g. ngang-class words completed by length) — sorted by relative
   frequency; not-yet-fully-typed objects (needing more letters and/or a tone)
   sort below them.
9. **Targeted n-gram DB (Phase 4, later)**: promotion-only re-ranking for the
   syllables not first in their collision group — 2,753 syllables, 7.1% of
   tokens, 90% of that mass in ~738 syllables. Frequency order is the fallback;
   n-gram can never make things worse. Generalizes to other languages later.

## Tone-after-vowel (design point 7) — DECIDED 2026-08-05 (Cliff)

- TAE (tone-at-end) vs TAV (tone-after-vowel) are EXCLUSIVE — a user
  setting, never both live at once (list length + confusion). Switching
  rebuilds the trie (critical-pref pattern, like show_accented_keys).
- TAV display: tone-label style options grayed out; tone keys instead show
  the PREVIOUS keystroke's vowels carrying each key's tone mark (dynamic
  display, absorbs design point 4d); existing prediction graying applies.
- TAV FTEFTM arises ONLY for vowel-final syllables (tone key last — where
  TAE and TAV encodings coincide). keysRemaining machinery already covers it.
- Pre-tone display (AMENDED 2026-08-05, Cliff): the one-letter-word
  principle applies to ALL pre-tone states, vowel-final FTEFTM included —
  in TAV, tone families are NEVER enumerated. One unmarked row per letter
  spelling: it IS the ngang FTS word when that exists, or a synthetic
  confirmation row when it doesn't. Selecting the row outputs the unmarked
  string even when it is not a real word (permitted, like any keyboard);
  its display value is keystroke confirmation. Deeper pre-tone ITS need
  not display at all; if shown, same unmarked collapse. The TAE
  enumeration contract is unchanged — the modes are exclusive.
- Sorting unchanged (FTS > FTEFTM > ITS fill-to-8); consider preferring
  tone-established ITS within the ITS block.
- MEASUREMENTS (license-clean corpus, 2026-08-05): shipped vn_optimized
  layout TAE E=0.095893 (new lock) vs TAV E=0.095316 — TAV 0.6% BETTER on
  the SAME layout, so the toggle needs no new layout and no langpack
  change (trie encoding is runtime). Unconstrained 120-seed optimizations:
  TAE 0.093392 vs TAV 0.092301 (1.2%; pre-purge measurement was 2.7%).
  Corpus variant: VietnameseWordsTone5tv.txt (production symbols,
  after-carrier placement), languages/vietnamese_tone5tv.toml.

## TAV round 1 SHIPPED + fixed (dev 67f5e53, 2026-08-05; Cliff-verified)

Setting "Tone Mark Entry Position" (VN-gated Choice, critical pref -> trie
rebuild): end (default) / after_vowel. WLD: toneAfterVowel param (tone key
inserted after carrier in translateToKeys), tonePending on CandidateEntry,
baseFormOf(), nextCharAt(). JTUI: TAV pre-tone collapse (one unmarked row
per letter spelling; synthetic type-"B" rows output verbatim, no stats).
Tone Key Labels row gated via enabledWhenKey/enabledWhenValue (new
choice-value gating in SettingsRenderer + KeyboardSettingsController).
Cliff verified: "Cái này là gì" types correctly; collapse + hints correct.

Post-smoke fixes (all Cliff-reported, all landed):
1. FTEFTM classification REQUIRES tonePending — in TAV, tone-typed words
   with one LETTER remaining also match kr==1 && len==seqLen and were
   mark-stripped into ghost rows (hài -> "hai").
2. Type-"B" rows must be in every word-type filter EXCEPT usage/stats
   recording, and MUST carry caseCount>=1 — applyShiftAndCaps silently
   DROPS word-typed entries with caseCount<=0 (invisible-row trap).
3. Next-letter hints: nextCharAt() — char index lags key index once a
   mid-word tone key is typed; also suppresses letter hints when the next
   keystroke is the tone itself.

## TAV round 2 — CODE COMPLETE 2026-08-05 (per-vowel tone-form display; device smoke pending)

Design (Cliff, 2026-08-05 — supersedes the earlier gray-the-forms sketch):
in TAV, cell 7 shows ONLY viable forms — the previous keystroke's vowel(s)
carrying that key's tone, each backed by a live trie candidate — and is
blank otherwise (root included). Absence IS the feedback: an expected form
not appearing means a keystroke/spelling error, caught mid-word. Rationale:
a vowel-with-tone is a transient functional preview, not a character
resident on the key, so graying it (letter-hint style) would show a
falsehood; and root keyfaces already carry list-function badges. Forms echo
the typed carrier's case (CAPS → upper; shift/pending auto-cap → upper at
word start only — same predicate as applyShiftAndCaps) and follow the
previous key's face order. Display is independent of the Next Letter Hints
toggle (it is the tone key's label in TAV, not a hint). Static tone labels
+ cell-7 graying (nextToneKeys) are now TAE-only; the Tone Key Labels style
row was already gated to TAE in round 1.

Implementation: WLD.getNextToneFormsForKeys (tone-key child subtree walk;
word qualifies iff its char at carrier position keys.size-1 folds to that
tone key — kills Telex letter/tone-key false positives; assumes one tone
per word, true for VN syllables) → JTUI.computeTavToneFormLabels →
renderKeyLabelGrids/withTavToneForms per snapshot AFTER applyShiftCapsToGrid
(current shift state must not distort the echo). Display: the key's CENTER
COLUMN (cells 1/4/7), filled bottom-up, reading top→bottom in key-face
order (Cliff, 2026-08-05: cell 7 alone was cramped). Forms travel as grid
text, neutral color — SquareButton exempts center-column cells from
letter-hint coloring (display zones, never letters). Max forms per key: 3 (Optimized key 2 e/y/ă; alpha keys 0+3).
Tests: WldToneEncodingTest (forms semantics) + VietnameseSelectionTest
(cell-7 end-to-end: forms, blanks, TAE regression, case echo, alpha mode).

## Evidence (analyzer, 5,470-syllable corpus)

| scheme | E | P(1st choice) |
|---|---|---|
| 22-letter full fold, optimized | 0.780 | 70.2% |
| 36-group bằng\|trắc, optimized | 0.471 | 78.4% |
| tone-keystroke, unconstrained | 0.0937 | 92.9% |
| **tone-keystroke, Telex-aligned (production)** | **0.0977** | **92.9%** |
| Alphabetic (33-letter interspersed, Telex tones) | 0.363 | 81.3% |
| (Spanish 0.086 / English 0.044 reference) | | |

Corpus: hermitdave OpenSubtitles-2018 vi (conversational, primary), filtered to
hieuthi's phonotactic syllable inventory (kills EN contamination), tone-spelling
normalized to traditional style by corpus majority, +146 syllables rescued from
Leipzig news 2022; 99.97%/99.95% token coverage of the two registers.

## Phases

**Phase 1 — finalize analyzer artifacts** (layout-analyzer repo)
- Deep run (500+ seeds) on `vietnamese_tone5t.toml`; pick production layout.
- Extend `report`: `tones` section in layout JSON (key→mark + label styles),
  spell pages per the 3-level scheme, free-symbol cell placement (f→huyền
  key, j→nặng key; `.` + punctuation slots `@ _` and `# / -` on least-loaded
  keys), and the `alpha` section with the fixed Alphabetic split above.
- `results/vietnamese/arrangement.md`; commit; regression-lock E values.

**Phase 2 — JT integration** (dev branch; pattern = Spanish Phase 4)
- BuildWordDbTask: Vietnamese corpus + VietnameseLayout.json → DB.
- LayoutSpec: tone section; trie encodes syllable = letter keys + optional
  tone key at end; Selection List shows full diacritic forms.
- UI: tone glyphs at key bottoms; Next-Letter Hints gray tone keys (and
  f/j/w/z) when no completion; Two-Key Spell 3-level tone entry; NEW WORD.
- Settings: tone label style. Defer: after-vowel toggle + display (d).
- Langpack release (lesson from the Spanish smoke test, see
  per-language-layouts/plan.md): set `Vietnamese.version` in
  langpacks.properties, run `packageLanguageArtifacts`, and `gh release
  upload` the .gz + manifest to the langpacks release — the installer only
  installs from the published release, never from bundled assets.
- On-device smoke test.

**Phase 3 — validation**
- Unit tests; JT-trie vs analyzer E cross-check on shipped layout.
- Native-speaker review: top-1000 sanity, traditional tone-style spelling
  (hòa not hoà), profanity-prediction policy for first user batch.

**Phase 4 — n-gram prediction DB** (post-v1)
- SUPERSEDED by docs/.plans/ngram/plan.md (2026-08-06): cross-language
  design; the Leipzig/OPUS sources named here are license-banned.

## Shipped 2026-08-04: FTS ordering fix + next-tone-mark prediction (design point 8)

Selection-list FTS/ITS partition, completion-distance metric, promote boost,
and the over-8 tail filter all compared CHAR length to key-sequence length —
tone-marked words still awaiting their tone key ranked as fully typed
(Viet-4-letters outranked genuine 4-key words). All four now use the trie's
keysRemaining (dev 8607c9b). Next-tone-mark prediction shipped in the same
round: WLD.getNextToneKeysForKeys -> snapshot.nextToneKeys -> tone-mark label
(cell 7) highlights when its tone completes a fully-lettered candidate, grays
otherwise (a tone-key child counts only via words with letters == sequence
length, so tone keys doubling as Telex letter keys can't false-positive).
Verified on the Pixel Tablet with the coruv-adpxi-eotjwz-eotjwz sequence.
Design point 7 (tone-after-vowel entry) remains open.

Selection-list certainty contract (Cliff, 2026-08-04, dev):
1. FTS (keysRemaining==0) — complete, never capped, always first. 82 VN
   sequences carry >8 FTS (max 16); dropping one makes it unreachable.
2. FTEFTM (all letters typed, only the tone keystroke pending;
   keysRemaining==1 && len==seqLen — structurally empty for v1 layouts) —
   complete, never capped, ranked above ANY letter-incomplete candidate
   regardless of frequency (chụy above chuyện at c-h-u-y).
3. Letter-ITS — best-effort FILL only (revised 2026-08-04): with 8+
   guaranteed entries no ITS are shown; below 8, ITS top the list up to 8
   total. (Supersedes the brief append-8-always rule.)
ITS-above-FTS promotion deliberately deferred (UN-DEFERRED 2026-08-06 for
n-gram prediction slots — see docs/.plans/ngram/plan.md; reachability
guarantees unchanged). Selection-list scrolling
(list + paged): midpoint anticipatory algorithm, selection located by its
highlight span, scroll pinned against TextView's internal reset
(SelectionListView.pinnedScrollY) — verified on device on the 27-item
eshylă-côruv-eshylă group. TODO (parked 2026-08-04): verify SCAN-layout
display of monster lists — overflow beyond itemsPerColumn x maxColumns
appends into the LAST column (nothing dropped) but visual clipping there
is unverified; check with the 27-item group when scan input is next
under test.

