# On-device smoke test — rebuilt word database

Run after any `{Lang}WordsRaw.txt` rebuild, before shipping. Written for the 2026-08
English rebuild; the sections generalize to any language.

Build + install: `./jt install` (debug variant bundles English + Español).

Each item is **type this → expect this**. A failure means the DB build or the list
generation is wrong, not the IME — note which item and stop.

## 1. The database actually loaded (do this first)

- [ ] Open any text field with JT active. The prediction bar populates.
- [ ] Type the sequence for **the** — appears as a top candidate.
- [ ] Settings → language shows English active, no "database missing" error.
- [ ] If the app was upgraded rather than freshly installed: confirm predictions
      changed (the new DB is 60,096 words vs 50,934 — the old asset must not persist).

## 2. Core frequency ordering

The most common words must rank first; a bad rawFreq scale shows up immediately.

- [ ] **you, I, the, to, a, it, and, that, of, is** — each reachable as a leading
      candidate for its key sequence.
- [ ] No obviously rare word outranks a common one on the same sequence.

## 3. Capitalization (CaseType) — the biggest behavioral change

Verify each presents in the expected case *without* the user pressing shift.

- [ ] **John, Washington, Christmas, Monday, April** → capitalized (TitleOnly).
- [ ] **bill, may, grace, mark, god** → lowercase first, capitalized form still
      reachable (LowerFirst). These are the POS-informed demotions; if any appears
      capital-only, the name-share guard regressed.
- [ ] **FBI, TV, DNA, OK, US** → all-caps (Acronym).
- [ ] **the, and, because** → lowercase, capitalizing only at sentence start.
- [ ] Sentence-initial: start a sentence with **the** → auto-capitalizes to "The".

## 4. Vocabulary the rebuild changed

- [ ] **Hyphenated** (regression-tested): *e-mail, good-bye, uh-huh, no-one,
      brother-in-law, x-ray, t-shirt* — all present. These were absent in the first
      rebuild attempt; their loss is the specific bug this checks.
- [ ] **Contractions**: *I'm, I'll, I've, he's, don't, can't* — present, apostrophe
      renders correctly.
- [ ] **Abbreviations/extensions** (hand-curated carryover): *Mr., Dr., etc / etc.,
      vs, .com* — present, period-forms behave as before.
- [ ] **Technical tail removed**: *apoptosis, polymerase, plasmid, exon* should NOT
      appear. Their absence is intended (microbiology corpus artifacts).
- [ ] **Profanity excluded**: coarse words from the avoid list must NOT be predicted.
      Confirm a few do not appear. (They can still be added manually — see §6.)

## 5. Ambiguous-layout behavior

- [ ] Type a sequence with several valid words; the selection list is ordered
      sensibly and scrolls.
- [ ] Longer words complete correctly (no truncation at the old list's vocabulary).
- [ ] Letter Spell Mode still works (English has no diacritics — the `diacriticSet`
      metadata row is empty, which is correct).

## 6. User vocabulary still overrides everything

- [ ] Add a custom word (including one on the avoid list, to confirm the build-time
      filter does not restrict the user). It is offered afterward.
- [ ] Select a non-default case form of a word twice → that form becomes preferred.
      Confirms seeds are soft biases, not locks.

## 7. Regression sweep

- [ ] Switch to Español, type a few words, switch back — no crash, both DBs load.
- [ ] Spanish region setting (Any / Castilian / Mexican) still changes ranking:
      *ordenador* vs *computadora*, *zumo* vs *jugo*. (Region tags were regenerated;
      1,543 tags carried over unchanged, 315 added.)
- [ ] Background the app, reopen — predictions still work (DB handle survives).
- [ ] Check logcat for `WordDb` / `BuildWordDb` errors: `adb logcat -s JustType:*`.

## Known/expected differences from the previous build

- **god** is lowercase-first now (encyclopedic corpora use lowercase "god" for
  mythology). Selecting "God" twice restores the preference. Acceptable, not a bug.
- ~12k technical words gone, ~22k conversational words added; overall 60,096 words.
- Subtitle-register vocabulary (informal interjections) is more prominent.
