# justtype-react

JustType's 8-key predictive keyboard as a React component, for AAC web apps (PWAs).

It is a TypeScript port of the parts of the Android app that do not depend on Android: the
word-prediction engine, the key pages, the input methods and key feedback. It types into
**its own message buffer** — a web page cannot act as a system keyboard, so there is no
Navigation Mode, no overlays above other apps and no HeadBoard link.

## Quick start

```sh
npm install
npm run dev     # demo page
npm test
npm run build   # dist/ (library) + data/ (language files)
```

```tsx
import { JustTypeKeyboard, localStorageStore } from "justtype-react";
import "justtype-react/styles.css";

const store = localStorageStore();

function Talker({ data, ngrams, symbols }) {
	return (
		<JustTypeKeyboard
			data={data}          // data/english.json
			ngrams={ngrams}      // data/english.ngrams.json — optional, may arrive later
			symbols={symbols}    // data/symbols.json — optional, enables ALL SYMBOLS MODE
			store={store}        // remembers usage, added words and phrases
			inputMethod="scan"
			onTextChange={(text) => console.log(text)}
		/>
	);
}
```

To drive the engine from your own UI (set the text or cursor, manage phrases, build a custom
layout), create it with `useJustType(options)` and pass it as `engine`.

## Language data

`npm run build:data` compiles `app/src/main/db` into `data/` (not committed):

| File | Size (gzip) | Contents |
| --- | --- | --- |
| `english.json` | 1.0 MB (0.34 MB) | Key layout, 60k words with frequency and case type, diacritic map |
| `english.ngrams.json` | 1.3 MB (0.58 MB) | Next-word table, pruned to 12k contexts × 8 followers |
| `espanol.json` | 3.4 MB (1.0 MB) | Spanish layout and 195k words |
| `espanol.ngrams.json` | 1.2 MB | Spanish next-word table |
| `symbols.json` | 5 kB | ALL SYMBOLS MODE tree |

Fetch them at runtime and cache them in the service worker; load the n-gram file after the
keyboard is usable. Prune differently with `--contexts N --followers N`. The word lists and
n-gram tables derive from CC BY-SA 4.0 sources (see the headers in `app/src/main/db`), so
attribute them when you ship the data.

## Keys

Slots are numbered NW, N, NE, W, E, SW, S, SE (0–7). Six slots hold letters; N is
UnDo/Delete and S is Select.

- Type one key per letter. The top candidate is previewed; **Select** steps down the list.
- After the first two rows, Select **pages** the rest of the list onto the six letter keys;
  a letter key then picks its word outright (`wordSelection="list"` steps instead).
- The next letter key accepts the selected word and starts a new one. Spaces are automatic.
- **UnDo** steps back through Selects and keys. At the end of a finished word it reopens
  that word (**pull-in**), so further UnDos remove its letters. Tapping a word in the
  message, or Select on an empty list, reopens the word at the cursor.
- A function key followed by Select opens its page: `SYM` (punctuation, repeated symbols,
  ALL SYMBOLS MODE), `FNS` (shift, caps lock, speech, delete, scroll), `MENU` (add word, add
  phrase, letter/symbol mode, numbers, edit mode).
- **Phrases:** MENU → ADD NEW PHRASE, type it, FNS → DONE WITH PHRASE, spell an abbreviation.
  Typing the abbreviation then offers the phrase. Also `engine.addPhrase(abbr, phrase)`.
- **Edit mode:** move by character/line, word/sentence, paragraph/all or bookmarks; select,
  cut, copy, paste, change case, undo.

## Options

| Prop | Default | Effect |
| --- | --- | --- |
| `wordSelection` | `"paged"` | `"list"` steps through every row with Select |
| `pagedListedWords` | `2` | Rows stepped one by one before paging starts |
| `nextLetterHints` | `false` | Dims key-face letters that no word continues with |
| `familyExpand` | `false` | Pausing on a long selected word inserts words sharing its stem |
| `pullIn` | `true` | UnDo / Select / tap reopen a finished word |
| `accentFallback` | `true` | Accented words can be typed with base letters, ranked lower |
| `excludedWords` | `"offensive"` | Which graded words are withheld |
| `layoutMode` | `"optimized"` | `"alpha"` for the alphabetic layout (also `engine.setLayoutMode`) |

## Input methods

| `inputMethod` | How a key is chosen | Default bindings |
| --- | --- | --- |
| `direct` | Tap or click | Numpad, or Q W E / A D / Z X C |
| `scan` | Keys highlight in turn; the switch presses the highlighted one | Space, Enter, gamepad A |
| `twoSwitch` | Each press keeps half the keys (4 → 2 → 1) | F / ← green, J / → red, gamepad A / B |
| `joystick` | Push a gamepad stick toward a key; release to press | Either stick |
| `mouseJoystick` | Move the pointer toward a key; return to centre to press | — |
| `swipe` | Swipe on the grid in the key's direction | — |

Switch keys are `KeyboardEvent.code` values (`switchKeys`); set `captureKeys={false}` to
handle input yourself and call `engine.press(slot)`. `ScanState`, `TwoSwitchState` and
`ZoneSelector` are exported for custom input hardware. While scanning, selection pages are
dealt in scan order so the first key reached holds the likeliest word.

## Differences from the Android app

- **Not ported:** tone-keystroke layouts (Vietnamese), diacritic-variant pages in spell
  mode, regional spelling preferences, imported vocabularies, the on-keyboard settings
  pages (use props), System Nav mode, and collapsed "word (Word)" case pairs — each case
  form is its own row.
- **Different by necessity:**
  - Spacing around punctuation follows this package's own rules (`src/engine/text.ts`); the
    app delegates that to the Android editor.
  - Cursor "line" moves use hard line breaks, and "page" moves go to the start or end of
    the message: the engine does not know how the view wraps text.
  - PASTE inserts what was last cut or copied here; call `engine.setClipboard(text)` to
    feed it the system clipboard (browsers require a user gesture to read it).
  - A new phrase is typed in the message area (the message is set aside and restored)
    rather than in a separate screen.
  - A key that no word continues with is refused with the error beep.
- **Same:** trie search limits, frequency classes, the sort metric and its weights, the
  "every fully-typed word is listed" rule, next-word block ordering, paging geometry,
  family stem rules, accent-fallback penalty, page arrangements, scan order, two-switch
  halves and joystick sector widths.

## Layout

```text
scripts/build-data.mjs   language sources -> data/
src/engine/              pure TypeScript, no DOM
  engine.ts              state machine: keys, list, paging, pages, phrases, pull-in
  wld.ts ranking.ts ngram.ts   trie search, ordering, next-word pool
  buffer.ts text.ts      message buffer with cursor/selection; spacing and case rules
  allSymbols.ts layout.ts store.ts
src/input/               scan / two-switch / direction state machines + the React wiring
src/JustTypeKeyboard.tsx component;  src/styles.css  theme via --jt-* custom properties
```
