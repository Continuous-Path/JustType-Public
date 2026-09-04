# JustType Keyboard — User Guide

## What Is JustType?

JustType is an Android accessibility keyboard designed for people with motor disabilities who cannot use a standard on-screen keyboard. It replaces the conventional QWERTY layout with just eight large keys arranged in a 3×3 grid (with an informational center space). Six of the keys each map to several letters, and built-in word prediction disambiguates the user's intended word — much like the text-entry method on early mobile phones, but with a modern prediction engine and a vocabulary of over 50,000 English words.  The other two keys include an UnDo key and a Select key to select different word candidates.  

JustType is an IME (Input Method Editor). Once installed and enabled, it works in any Android app that accepts text input — messaging, email, web browsers, social media, notes, and more.

> **See also:** the [Settings Reference](SettingsReference.md) is the page-by-page companion covering *what each setting does and when to change it*. The [Beta Tester Quick Start](BetaTesterQuickStart.md) walks through installation, enabling the keyboard, and picking a TTS voice.

---

## Contents

- [What Is JustType?](#what-is-justtype)
- [Why Eight Keys?](#why-eight-keys)
- [How Ambiguous Typing Works](#how-ambiguous-typing-works)
- [Keyboard Layouts](#keyboard-layouts)
- [Input Methods](#input-methods)
- [Keyboard Pages and Modes](#keyboard-pages-and-modes)
- [Word Prediction and the Selection List](#word-prediction-and-the-selection-list)
- [Auto-Spacing](#auto-spacing)
- [Auto-Capitalization](#auto-capitalization)
- [Speech Output](#speech-output)
- [Vocabulary Management](#vocabulary-management)

---

## Why Eight Keys?

A conventional on-screen keyboard presents 26 letter keys plus punctuation, modifiers, and a spacebar. For a user who controls a pointer with limited precision — whether through direct selection, head movement, a joystick, or switch scanning — acquiring one of 30+ small targets can be slow, fatiguing, and error-prone.

JustType reduces the number of targets to eight. The keys are large, widely spaced, and arranged symmetrically. This design offers several advantages:

- **Faster target acquisition.** Larger targets are easier to reach with imprecise control.
- **Fewer movements per word.** A typical five-letter English word requires only five key presses (one per letter), not five precise selections from a full alphabet.  Choosing the selection technique that works best for an individual makes each selection as fast and easy as possible. 
- **Lower fatigue.** Fewer and shorter movements reduce the physical demand of typing.
- **Compatibility with many input methods.** The same eight-key layout works with head tracking, joystick input, switch scanning, and direct touch — so a user's keyboard skills transfer even when their input method changes.

The trade-off is ambiguity: when a user presses a key, the system does not immediately know which of the key's letters was intended. JustType resolves this through word prediction.

## How Ambiguous Typing Works

Each of the eight keys is labeled with a group of letters. For example, in the Optimized layout, one key is labeled **ISKW** and another **BANQ**. When the user presses keys in sequence, JustType builds a list of all dictionary words that could match the sequence and presents them ranked by frequency.

**Example:** To type "the", the user presses:

1. **TRP-'.** (the key containing T)
2. **OJHDVX** (the key containing H)
3. **MEGZ** (the key containing E)

JustType immediately shows "the" as the top prediction, since it is the most common English word matching that key sequence. If the user intended a different word (for example, "toe"), they press **SELECT** to cycle through alternatives.

The word is finalized — committed to the text field — when the user begins typing the next word (presses any letter key) or takes an explicit action such as pressing a punctuation or function key. Spaces are inserted between words automatically.

## Keyboard Layouts

### Optimized Layout

The default layout groups letters to maximize prediction accuracy — common letters are distributed across keys so that ambiguous sequences produce fewer "collisions," so in the vast majority of cases the Select key only needs to be pressed once.  Though the Optimized layout may look unusual at first sight, it is easy to learn and the increase in efficiency is well worth the short time it takes to become familiar.  

| Key               | Letters          |
| ----------------- | ---------------- |
| 0 (top-left)      | M, E, G, Z       |
| 1 (top-center)    | UnDo/Delete      |
| 2 (top-right)     | T, R, P, -, ', . |
| 3 (left)          | I, S, K, W       |
| 4 (right)         | L, U, C, Y, F    |
| 5 (bottom-left)   | B, A, N, Q       |
| 6 (bottom-center) | SELECT           |
| 7 (bottom-right)  | O, J, H, D, V, X |

### Alphabetical Layout

An alternative layout that arranges letters in alphabetical order. This may be easier for users who are still learning the alphabet, though the Optimized layout is generally more efficient.

| Key | Letters |
|-----|---------|
| 0 | A, B, C, D |
| 1 | N, O, P, Q, R |
| 2 | E, F, G, H, ', . |
| 3 | S, T, U |
| 4 | I, J, K, L, M |
| 5 | V, -, W, X, Y, Z |

You can switch between layouts in **Settings > Keyboard**.

## Input Methods

JustType supports multiple ways to press the eight keys. Each input method can be configured independently, and a user can have one touch-screen method and one external method active simultaneously.

### Head Tracking (via HeadBoard)

The user controls a pointer by moving their head. JustType divides the space around the keyboard center into the directional zones corresponding to each of the eight keys. Tilting toward a key highlights it; as soon as it flashes green, the key is pressed and you can move back toward the center and then move to the next key.

Head tracking requires the companion app **HeadBoard** (based on Google's Project Gameface), which uses the device's front-facing camera to track head movement and sends coordinates to JustType.

**Setup parameters** include "dead zone" size, activation threshold, exit delay, pitch scale, response curve, and corner bias — all adjustable to match the user's range of motion.

### Joystick / Gamepad

A physical joystick or gamepad connected via Bluetooth or USB. The user tilts the joystick toward one of the eight key positions. Configurable dead zone, active zone, and corner bias.

### Single-Switch Scanning

The keyboard is scanned automatically, advancing a highlight through the keys one at a time. A single switch (external hardware or an on-screen touch area) is pressed to select the currently highlighted key. Configurable scan speed, debounce, auto-repeat, scan repeat count, and whether to skip keys that have no valid candidates remaining.

The scanning layout displays keys in either two rows of four or one row of eight (to allow more screen space for the text area).

### Two-Switch Selection

Two switches — conventionally colored red and green — can be used to select any key with exactly three switch presses, and without  any timing requirements on when a switch is pressed. At the start, half the keys are highlighted red and the other half green.  All that is needed is to look at the key you want and press the switch that matches the highlighting color on the key.  Doing that three times — each time, watching what color the key changes to — activates the key.  With only a little practice, a user can learn the pattern for each key (e.g. red-green-red) and become faster and faster.  Switches can be external hardware or left/right halves of the touch screen if no hardware is available.

### Direct Selection (Touch)

The user taps keys directly on the touch screen. This method can be active simultaneously with other input methods that do not requires touching the screen. Configurable debounce.

### Directional Selection (Swipe)

Imagine the eight keys sit at the eight points of a compass (where the center of the compass is the center of the keyboard).  The user swipes — anywhere on the screen — in the compass direction of the desired key. A swipe toward the "Northeast" activates the upper-left key, a swipe straight down toward the bottom activates the bottom-center key (SELECT), and so on. Configurable swipe distance and touch timeout.

### Touch Screen Switch

An on-screen switch bar for users who interact with the screen but benefit from a simplified target. Can be configured as a single switch or a two-switch (left/right) bar.  This can be very helpful when external hardware switches are not available. 

## Keyboard Pages and Modes

Beyond the main letter-entry page, JustType provides several additional pages accessed via the same keys used to type words with.  These other pages make it possible to do virtually anything that a full computer keyboard can do — but all just using the same eight keys, each activated by the same easy and familiar input method to which the user is accustomed.  

Before starting to type a word, the three keys along the right side of the keyboard show images of other keyboard pages.  When one of these keys are pressed, the Selection list shows other keyboard pages.  Pressing the Select key then selects one of these other keyboard pages, which include:   

### Symbols Pages (1, 2, 3)

Accessed from the main page. Each page provides common punctuation:

- **Symbols 1:** Period, hyphen, exclamation mark, comma, question mark
- **Symbols 2:** Space, @, colon, opening parenthesis, semicolon, closing parenthesis
- **Symbols 3:** Ampersand, apostrophe, quotation mark, asterisk, forward slash

Usually, these pages output a single symbol and immediately return to the Main keyboard.  An alternate group of Symbol pages allow multiple symbols to be typed, and then return to the Main keyboard when finished.  

### Numeric Mode

Accessed from the Navigation page (123 MODE). Two pages of digit keys:

- **Numbers 1:** Digits 1–5, plus DELETE and DONE
- **Numbers 2:** Digits 6–0, plus symbols +, -, ., /, and a key to return to 1–5 or finish

A **Numeric Punctuation** sub-page provides currency symbols, time notation (am/pm), temperature symbols, mathematical operators, and more.

Auto-spacing is handled automatically: a space is inserted before the first numeric character when entering from text mode, and the normal auto-spacing rules apply when returning to text entry afterward.

### Functions Pages

- **Functions 1:** Caps Lock, Shift, Speech toggle, Enter, and other control functions (performs one function and automatically returns to the Main page)
- **Functions 2:** Scroll Up/Down, Delete character/word, Enter (multiple functions can be performed multiple times, then return to the main page when done)

### Navigation Page

Provides access to: Back, Add Word, Add Phrase, Home, Edit Mode, Menu, and 123 (Numeric Mode).

### Edit Mode Pages

For cursor movement in the text field and text manipulation:

- **Edit Mode 1:** Arrow-key cursor movement (character/line, word/sentence, and paragraph/page levels)
- **Edit Mode 2:** Cut, Copy, Paste, Case conversion
- **Edit Mode 3:** Case adjustment options (Title Case, UPPER CASE, lower case, Sentence case)

### Spelling Mode

For letter-by-letter entry when a word is not in the dictionary. Each key expands to show its individual letters, and the user selects one specific letter at a time. Available in both Optimized and Alphabetical arrangements, and also in some parts of Symbols or Numeric mode.

## Word Prediction and the Selection List

When the user types an ambiguous key sequence, JustType consults its vocabulary database and generates a ranked list of candidate words. The top candidate is automatically displayed in the text field.

- **SELECT key** cycles through candidates in frequency order.
- **Once the intended word is Selected** (usually after the first press of the SELECT key) that word becomes **finalized** once the user starts typing a new word, types punctuation, or takes another action. The word already in the text field remains there.
- **Alternate case forms** (e.g., capitalized variants) can also be chosen from the selection list, which can be simpler than remembering to press the Shift function.

### Abbreviations and Phrases

Users can define custom abbreviations that expand to longer phrases. For example, typing the abbreviation "ADDR" could expand to a full mailing address. Abbreviations are managed through the Navigation page (Add Phrase) or through Settings.  

### Dictionary Abbreviations

The vocabulary database includes common abbreviations (Mr., Dr., etc.) with proper period handling. When an abbreviation ending with a period is selected, JustType correctly inserts a space before the next word and automatically activates the Shift function to capitalize the following name.

### URL Domains and Extensions

The database includes common URL domains (gmail.com, google.com, etc.) and extensions (.com, .org, .pdf, etc.). These entries automatically avoid outputting spaces where they would not be wanted, making it easy to type web addresses and email addresses.

## Auto-Spacing

JustType automatically inserts spaces between words so the user almost never needs to explicitly type a space during normal text entry. Just type what you want to type, and the automatic spacing will almost always be handled correctly.  The auto-spacing system includes several intelligent behaviors:

- **Standard words:** A space is inserted before each new word.
- **Sentence punctuation:** No space is inserted before period, comma, exclamation, or question mark.
- **Abbreviations:** A space IS inserted after abbreviation periods (e.g., "Mr. Jones", not "Mr.Jones").
- **URL/email fields:** Auto-spacing is disabled entirely when the active text field is a URL bar, email address field, password field, phone number field, or numeric input.
- **Domains and extensions:** Auto-space is suppressed before domain and extension entries to allow continuous URL construction.

## Auto-Capitalization

JustType automatically capitalizes the first letter of a new sentence. Sentence boundaries are detected intelligently.  For words that are sometimes capitalized and sometimes not (e.g. "my friend Bill..." or "pay the bill..."), both forms are available in the Selection list, and the system learns which form you usually want, and puts it first in the list.  

## Speech Output

JustType can speak text as it is typed, providing spoken output as desired, or auditory feedback for individuals with any form of visual impairment. Speech options (configurable in Settings) include:

- **Speak each output word** — the word is spoken when finalized
- **Speak each output phrase** — abbreviation expansions are spoken
- **Speak each completed sentence** — the full sentence is spoken when terminal punctuation is typed
- **Speak the name of punctuation characters** — "period", "comma", etc.
- **Max delay to speak selected text** — adjustable delay (0–5 seconds) before automatically speaking Selected text (so that you do not need to start typing a "next word" to "force" the system to speak previously selected text). 

## Vocabulary Management

The built-in vocabulary contains over 50,000 English words with frequency data. Users and teachers or administrators can:

- **Add custom words** directly from the keyboard (Navigation > Add Word)
- **Manage vocabularies** — enable/disable built-in and custom word lists
- **Adjust frequency thresholds** — control which words appear in predictions and next-letter highlighting
- **Spelling "hints" for vocabularies** — support learning to spell specific words in selected vocabulary groups
## Settings Overview

Settings are accessed from the Navigation page (Menu) or from the app's main screen.  Note that the default settings are designed to be optimal for the majority of individuals.  

### Keyboard Settings
- Layout choice (Optimized or Alphabetical)
- Keyboard size (40–95% of screen width)
- Highlight possible next letters
- Show key-press history
- Key history display height and shrink-to-fit behavior

### Speech Settings
- Per-feature speech toggles (words, phrases, sentences, punctuation)
- Speech delay

### Selection List Settings
- Auto-load word at cursor
- Alternate case form behavior and delay
- Abbreviation and phrase display options

### Input Method Settings
- Each input method has its own setup screen with method-specific parameters
- Auditory feedback (beeps, speech)
- Visual feedback (key flash)

### Backup and Restore
- Back up and restore user data including custom words, phrases, and settings

### Developer Settings
- Hidden by default (accessed by tapping the Settings title five times)
- Diagnostic and debugging tools

## Installation

JustType is distributed as an Android APK file.

1. **Install the APK** on an Android device (Android 7.0 / API 24 or later).
2. **Enable JustType** as an input method in the device's system settings (Settings > System > Languages & Input > On-screen keyboard > Manage keyboards).
3. **Select JustType** as the active keyboard when a text field is focused.
4. **Configure input method and settings** through the JustType app or the Navigation > Menu page on the keyboard itself.

If using head tracking, also install the **HeadBoard** companion app.

## Getting Help

JustType is under active development. For questions, feedback, or to participate in beta testing, please contact the development team.
