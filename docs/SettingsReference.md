# JustType Settings Reference

A page-by-page guide to every JustType setting, written in plain prose for end users, teachers, and clinicians. This document is a companion to the [User Guide](UserGuide.md): the User Guide explains *what JustType is and how it works*; this Reference explains *what each setting does and when to change it*.

For each setting, this guide describes:

- **What it does** — the behaviour controlled by the setting.
- **Why it matters** — when changing it is useful, and when it usually is not.
- **Strategic guidance** — for selected settings only, role-flagged callouts that help users, teachers, and clinicians decide *how* to set it for a particular person. These appear under the headings **▶ For device users**, **▶ For teachers**, and **▶ For clinicians**.

The settings appear here in the same order as they appear in the JustType **System Settings** screens, and in the on-keyboard **Keyboard Settings** overlay. The two surfaces are kept in parity: every setting in this document is reachable from both.

> **See also:** the [User Guide](UserGuide.md) explains how the keyboard works conceptually; the [Beta Tester Quick Start](BetaTesterQuickStart.md) covers installation and first-day setup.

---

## Contents

1. [JustType Settings (Main)](#1-justtype-settings-main)
2. [Input Methods](#2-input-methods)
3. [Set Up Head Tracking](#3-set-up-head-tracking)
4. [Set Up Joystick](#4-set-up-joystick)
5. [Set Up Single-Switch Scanning](#5-set-up-single-switch-scanning)
6. [Two-Switch Selection Set-Up](#6-two-switch-selection-set-up)
7. [Set Up Touchscreen Switch](#7-set-up-touchscreen-switch)
8. [Direct Selection](#8-direct-selection)
9. [Directional Selection](#9-directional-selection)
10. [Vocabulary Management](#10-vocabulary-management)
    - [10.1 Select Active Vocabularies](#101-select-active-vocabularies)
    - [10.2 Manage Vocabularies](#102-manage-vocabularies)
    - [10.3 Export Vocabulary Usage](#103-export-vocabulary-usage)
11. [Backup & Restore](#11-backup--restore)

---

## 1. JustType Settings (Main)

The main Settings page is the top of the Settings tree. It groups the settings most users adjust day to day — language, keyboard appearance, speech output, on-screen feedback, and selection-list behaviour — and provides links to the more specialised pages (Input Methods, Vocabulary Management, Backup & Restore).

### UI Language

**UI Language** controls the language used for everything JustType displays *about itself*: key labels, menu items, help text, error messages, and spoken setting prompts. The default, **System Default**, follows the language set on the device. Choosing a specific language pins JustType's interface to that language even if the device language changes.

**▶ For device users.** If you read English most comfortably, leave this on System Default — most Android devices are already set to English. Change it only if you want JustType's menus in a different language than the rest of the device.

**▶ For teachers and clinicians.** UI Language is independent of **Typing Language** below: a multilingual user can read JustType's menus in their stronger language while typing in another. Set UI Language first, before walking the rest of the Settings — every label below will then be in the user's preferred language.

### Typing Language

**Typing Language** selects the word database JustType uses for prediction and disambiguation. Currently only English is shipped, so this setting has only one option in the present build; it is exposed now so the surrounding Settings layout will not change when additional languages are added.

**▶ For clinicians.** Multilingual users may eventually want more than one Typing Language active, switching between them as they switch contexts. The data model already anticipates this; today, plan around the single-language constraint.

### Links: Input Methods · Vocabulary Management · Backup & Restore

These three buttons jump to dedicated pages described in their own chapters below. They are not settings themselves; their position on the main page reflects how often most users visit them — Input Methods most often (anchored to a person's selection technique), Vocabulary Management occasionally (when adding words or sharing a vocabulary), and Backup & Restore rarely but importantly (before a device change or after a problem).

### Keyboard

#### Letter Arrangement

JustType offers two arrangements of letters across the eight keys: **Optimized** and **Alphabetical**. Optimized groups letters so that ambiguous key sequences produce as few competing words as possible — in practice, the intended word is the first prediction the vast majority of the time, so the Select key is needed less often. Alphabetical lays the letters out in A–Z order, which is more familiar at first glance but produces more collisions.

Changing this setting is reversible and offers a *test mode* before confirming, so users can try the new arrangement without losing their preferred one if they decide to change it back.

**▶ For device users.** If you are new to JustType, start with Optimized. The look is unfamiliar, but the typing efficiency is significantly better and the layout becomes natural within a few sessions.

**▶ For teachers.** Alphabetical can be a useful stepping stone for early learners who are still building letter-recognition skills, but it should be treated as a transitional choice rather than a permanent one. Plan a transition to Optimized once the learner is comfortable.

**▶ For clinicians.** When evaluating a new user, default the demo to Optimized. Switch to Alphabetical only when literacy support, not typing efficiency, is the active goal of the session.

#### Optimized Letter Arrangement Language

For users who type in more than one language, JustType can keep the letter arrangement consistent (using the arrangement of the user's *most familiar* language across all typing sessions) or follow whichever language is currently selected as the Typing Language. This setting controls which behaviour applies.

Choosing a specific language locks the Optimized arrangement to that language, which is usually the right choice for someone who has invested time learning a particular layout. Choosing **Use Native Keyboard** instead means JustType will switch the optimised arrangement to match each Typing Language as it changes.

When the current Typing Language uses a different alphabet (e.g., a non-Latin script), JustType will automatically force this setting to the Typing Language's arrangement; this is not user-configurable in that case.

**▶ For device users.** If you only ever type in one language, you do not need to think about this — the default will do the right thing.

**▶ For multilingual users and their teachers.** If a user has built up speed and muscle memory in one language, changing the letter arrangement when the Typing Language changes is disruptive. Pin Optimized Letter Arrangement Language to the user's strongest language. The trade-off is a small loss of typing efficiency in the secondary language, which is almost always worth it.

#### Keyboard Size

**Keyboard Size** sets the keyboard's height as a percentage of the screen. Larger values give bigger, easier-to-read keys at the cost of leaving less room for the text field above the keyboard. Smaller values reverse the trade-off.

Most users settle on a value once and rarely revisit it. Adjust it after changing the device's display zoom or when switching between phone and tablet form factors.

#### Extra Blank Line on Enter

When **ON**, pressing Enter inserts two line breaks instead of one, producing a visible blank line between paragraphs. This is helpful when drafting documents or emails that use paragraph spacing rather than indentation. When **OFF** (the default), Enter inserts a single line break.

### Speech Output

These settings control when JustType speaks aloud. They work together — turning several on at once will produce overlapping speech — so consider the user's listening preferences before enabling more than one or two.

#### Speak Each Output Word

When **ON**, each word is spoken aloud as soon as it is *finalized* — that is, as soon as the user begins typing the next word, or sooner if the **Auto-Speak Delay** has been set and elapses first. This gives the user audible confirmation that the right word was selected without needing to read the text field.

**▶ For device users.** If you find yourself constantly checking the screen to see whether the prediction was right, turn this ON.

**▶ For teachers and clinicians.** This is often the single most empowering toggle for a new user. Audible word-by-word confirmation lets the user keep their visual attention on the keyboard, reducing the eye-darting that fatigues many users. Pair it with **Auto-Speak Delay** to control timing.

#### Speak Each Abbreviated Phrase

When **ON**, JustType also speaks aloud any *stored phrase* that is selected from the Selection List — for example, a phrase like "Call me back later." that the user has stored under the abbreviation `CMBL`. As with single words, the speech fires after the **Auto-Speak Delay** elapses, so a user can speak a stored phrase without having to type a following key.

#### Speak Each Completed Sentence

When **ON**, each completed sentence is spoken aloud as soon as the user types a sentence-ending punctuation mark (period, exclamation point, or question mark). The whole sentence is read at once, which can be more natural for conversational use than word-by-word speech.

#### Speak the Name of Punctuation Characters

When **ON**, punctuation characters are spoken by name — "period", "comma", and so on — when they are output. This is useful as a verification aid; it makes silent typos in punctuation audible. It applies to the regular speech output, not to the per-key speech under Input Methods.

#### Auto-Speak Delay

**Auto-Speak Delay** is the number of seconds JustType waits after a word or abbreviated phrase is selected before automatically speaking it. The range is **OFF (0)** through approximately twenty seconds.

When the delay is set to **OFF**, automatic speaking is disabled and the selected word or phrase will only be spoken when the user begins typing the next word.

A short non-zero delay lets the user use the keyboard in conversational pacing without speaking every Selection-List candidate as they cycle through it.

### JustType Display

#### Next-Letter Hints

This is one of JustType's most distinctive features. As the user types, JustType continuously computes which letters could come next in any word that matches the keys typed so far. With **Next-Letter Hints ON**, JustType shows those candidate letters in **bold** on the keyboard, while letters that cannot continue any matching word are **greyed out**.

Two practical consequences:

- If a user's next intended letter is greyed out, they have made a typo or they are trying to type a word that is not yet in the database — and they should back up and fix it (or add the word).
- When *every* letter on a key is greyed out, pressing that key has no effect. If **Error Beep** (under Input Methods → Auditory Feedback) is ON, JustType will produce a short double-beep on such presses.

**▶ For device users.** Turn this ON. Once you are used to seeing the hint pattern, you will notice typos one keystroke earlier than you otherwise would.

**▶ For teachers.** Next-Letter Hints is a powerful spelling support — it gives the learner immediate, non-judgmental feedback that "this letter cannot come next." Use it to scaffold spelling practice; the visual cue is easier to internalise than after-the-fact correction.

**▶ For clinicians.** For users with cognitive load constraints, Next-Letter Hints can be either a help (constraining the visual field to plausible letters) or a distraction (a flickering display every keypress). Try it both ways during evaluation.

#### Show Key History

When **ON**, a strip just above the keyboard shows the sequence of keys pressed for the current word. This helps users track their progress through a long word and spot where a typo entered the sequence.

#### Key History Height

Sets the height of the key history strip as a multiple of the regular key height, from **0.25** to **1.0**. A taller strip is more readable; a shorter strip leaves more room for the text field. Only meaningful when **Show Key History** is ON.

#### Shrink Key History to Fit

When **ON**, the key history strip automatically reduces its individual key size for longer words so that all the keys pressed remain visible. When **OFF**, the strip uses the full configured height and earlier keys scroll off to the left as the word grows.

> **Error Beep** lives on the [Input Methods → Auditory Feedback](#auditory-feedback) page in the current spec; it is no longer rendered here.

### Selection List

These settings control how the row of word candidates above the keyboard behaves.

#### Auto-Load Word at Cursor

When **ON**, tapping (or moving the cursor to) an existing word in the text field automatically loads it into the Selection List, so the user can cycle through alternate forms, extend it by typing more letters, or shorten it by pressing UnDo. When **OFF**, editing an existing word requires explicitly pressing the Select key.

When Auto-Load is ON and Edit Mode is used to move the cursor into a word, that word will be loaded automatically when the user returns to the Main keyboard via **BACK TO MAIN**.

#### Automatically Add Capitalized Word Forms to Selection List

When **ON**, the Selection List includes Capitalized Forms of each candidate word — for example, both "brown" (the colour) and "Brown" (the surname). When **OFF**, only the default capitalisation appears, and the user invokes SHIFT or CAPS LOCK to produce Capitalized Forms when wanted.

Turning this ON makes Capitalized Forms one Select-press away; turning it OFF keeps the Selection List shorter.

#### Wait Until Word is Selected to Add Capitalized Forms

When **ON**, Capitalized Forms do not appear in the Selection List immediately. They are only added once the user has *selected* the word and the **Delay to Add Capitalized Forms** has elapsed. This keeps the Selection List short during normal typing while still making Capitalized Forms available when wanted.

This setting only has an effect when **Automatically Add Capitalized Word Forms to Selection List** is ON.

#### Delay to Add Capitalized Forms

The number of seconds to wait, after a word is selected, before adding Capitalized Forms to the Selection List. This delay only applies when **Wait Until Word is Selected to Add Capitalized Forms** is ON, and only when the selected word actually has variant Capitalized Forms.

When set to **OFF (0)**, Capitalized Forms are shown immediately on selection.

#### Show Phrase Abbreviation in Selection List

When **ON**, the abbreviation associated with a stored phrase (e.g., `CMBL` for "Call me back later.") appears in the Selection List alongside the full phrase, if **Show Expanded Phrase in Selection List** is also ON. When **OFF**, abbreviations are not shown in the Selection List.

#### Show Expanded Phrase in Selection List

When **ON**, the full text of a stored phrase (e.g., the literal "Call me back later.") appears in the Selection List. When **OFF**, only the abbreviation appears.

The two phrase-display settings combine to give users four ways of presenting stored phrases — abbreviation only, expansion only, both, or neither — depending on whether the user is more confident recognising their phrases by abbreviation or by content.

---

## 2. Input Methods

This page chooses *how* the user activates keys. JustType always runs at least one **touchscreen** input method (Direct Selection or Directional Selection); on top of that, the user can pick exactly one **alternative** input method — head tracking, joystick, single-switch scanning, or two-switch selection — for users who cannot reliably touch the screen. The page also gathers the auditory and visual feedback toggles that confirm each key press.

### Touchscreen Input Methods

> JustType captures touches on the keyboard area whenever Directional Selection or the Touchscreen Switch is ON. While that capture is active, you cannot interact with anything *behind* the captured area. To free the screen up temporarily, open the Functions keyboard and press **HIDE KEYBOARD** — the keyboard goes away, the capture pauses, and the screen returns to normal until you tap a text field to bring the keyboard back. If you need to type while a captured method is paused, hide the keyboard first, go into JustType's settings, and turn the captured method OFF; turn it back ON when you are done.

**▶ For teachers and clinicians.** A user who shares a device with someone else (or with a caregiver) is the main audience for the HIDE KEYBOARD escape hatch. Walk a new user through it the first time you turn Directional Selection ON, so they know the door exists before they need it.

#### Direct Selection

When **ON**, each key activates by a direct tap. This is the default touch-screen method and is on by default. JustType requires *at least one* of Direct Selection or Directional Selection to remain on whenever no Alternative Input Method is set, so the keyboard always has *some* way to receive input.

#### Directional Selection

When **ON**, keys activate by a *swipe* in one of eight compass directions, anywhere on the keyboard area. The length of the swipe does not matter; only its direction relative to the centre of the keyboard. For users who have trouble landing precisely on a small key, a directional swipe is often dramatically easier and faster than a tap. As above, at least one of Direct Selection or Directional Selection must be on whenever no Alternative Input Method is set.

#### Touchscreen Switch

When **ON**, a strip of the screen below the keyboard becomes a virtual switch — no external hardware required. It can act as a single switch (for Single-Switch Scanning, or as the selection switch for Head Tracking or Joystick) or as a pair of side-by-side switches (for Two-Switch Selection). Size and behaviour are configured on the [Set Up Touchscreen Switch](#7-set-up-touchscreen-switch) page.

### Alternative Input Method

#### Alternative Input Method

Selects an additional way of activating keys that runs *alongside* whichever touchscreen method is active. The options are:

1. **Head Tracking** — a cursor on the keyboard moves in response to the user's head movements (front camera).
2. **Joystick** — an external joystick is used to nudge the cursor.
3. **Single-Switch Scanning** — JustType scans through the keys one at a time and the user closes a switch when the desired key is highlighted.
4. **Two-Switch Selection** — three closures of two switches, in a planned sequence, jump straight to the desired key.
5. **None** — no alternative method; the user types only with the touchscreen.

Each option has its own setup page (chapters 3–7 below).

**▶ For clinicians.** Choosing the Alternative Input Method is the most consequential decision you will make for a switch-using client. Direct/Directional touch is fastest if motor access permits; head tracking is the best fit when neck movement is reliable but limb access is not; switch scanning is the floor — slower, but reachable for almost everyone. Trial each option for a complete typing task (a sentence or two), not just key activations, before committing.

### Auditory Feedback

#### Beep when key is activated

When **ON**, a short single beep sounds each time a key is activated — useful confirmation that a key press registered.

#### Beep When Keystroke Has No Effect (Error Beep)

When **ON**, a short *double*-beep sounds when the user activates a key that has no valid letters left under the current word context (i.e., every letter on it is greyed out by Next-Letter Hints). The double-beep flags an audible "that key cannot come next here," catching typos one keystroke earlier.

#### Speak Each Selected Key

When **ON**, the *contents* of each activated key — the letters on it (or the function name if it is a function key) — are spoken aloud at the moment the key activates. Useful when looking at the keyboard is difficult or when a user is working on letter-by-letter spelling.

#### Speak Punctuation on Key

When **ON**, punctuation symbols are *named* when they appear in a spoken key. For example, the key reads as "E F G H apostrophe period" rather than "E F G H." Only meaningful when **Speak Each Selected Key** is ON.

#### Speak Each Selected Word

When **ON**, the word that is selected when the user presses the Select key is spoken aloud at that moment. Because the intended word is the first item in the Selection List the vast majority of the time, this lets the user keep their eyes on the keyboard while spelling — they hear confirmation as soon as they finish the word.

The closely related **Speak Each Output Word** setting on the main JustType Settings page does *not* speak when the word is selected; it waits until the user starts typing the next word. Selecting the on-page version (this one) gives faster confirmation; selecting the main-page version avoids speaking words you decide to step over to reach a lower Selection List entry.

**▶ For teachers and clinicians.** **Speak Each Selected Key** plus **Speak Each Selected Word** is a powerful combo for early learners — letter-by-letter spelling support plus word-level confirmation. As the user builds speed, you can usually trade Speak Each Selected Key off and keep only the word-level speech, which keeps the audio uncluttered.

#### Speak Prompts Aloud

When **ON**, settings-page navigation prompts (page titles, section headings, setting names) are spoken aloud as the user moves through Settings via the keyboard, on top of being shown on screen. Turn it OFF for a visual-only experience.

### Visual Feedback

#### Flash Key When Activated

When **ON**, each activated key briefly flashes with a visual highlight — a strong visual confirmation that pairs well with the audible beep above.

---

## 3. Set Up Head Tracking

Head Tracking is one of JustType's flagship Alternative Input Methods. The front camera tracks head pose, a cursor moves over the keyboard in response, and keys are activated by moving into a "slice" of the keyboard, watching it turn green, then moving back toward the centre. This page configures the camera source, the geometry of the regions that the cursor can occupy, and the tuning that controls how head movement maps to cursor movement.

### Head Tracking Source

Selects the camera or external head-tracking input that drives the cursor. Most users will use the device's front camera, which requires no extra hardware. Other entries appear here if you have configured a head-tracking source in the companion HeadBoard app.

### Resting Zone

#### Resting Zone

The size of a "no movement" region in the centre of the keyboard. Inside it, head movement does not affect the cursor — it is where the user can rest their head between key activations without unintentionally triggering a key. The size is set as a fraction from **0.0** (no resting zone) to **1.0** (the entire keyboard); the default is somewhere in the middle. Increase the size if keys keep activating when the user is trying to rest.

**▶ For clinicians.** A larger Resting Zone is forgiving but slower; a smaller one is fast but unforgiving. During evaluation, watch for *unintended activations* — that is the signal to enlarge the zone — and *missed activations* (cursor never reaches the green ring) — that is the signal to shrink it. Tune one variable at a time.

### Activation Zone

#### Active Zone

The fraction of the keyboard between the Resting Zone and the Exit Zone. When the cursor enters the Active Zone for a key, that key turns green, *armed* for activation. The user then moves the cursor back toward the centre to actually activate the key. Decreasing the Active Zone makes keys arm sooner (less head movement required) at the cost of more accidental activations. The maximum value is **0.75**.

**▶ For clinicians.** Active Zone should be set in concert with Resting Zone. The two together define how far the user must move from rest to arm a key — that distance is the *workload* of typing. Set Resting Zone to comfortably large; then shrink Active Zone until the user is just inside their reliable head-movement range.

### Zone Thresholds

#### Exit Zone

The outer boundary for cursor motion within the keyboard. When the cursor leaves the Active Zone *outward*, into the Exit Zone, the keyboard border begins to flash. If the user keeps the cursor in the Exit Zone for the **Exit/Pause Keyboard Delay Time** below, JustType either pauses head tracking (when the user moved right) or releases the cursor into the region above the keyboard (when the user moved up). To resume, the user reverses the motion. Decrease the Exit Zone if pausing or exiting the keyboard requires too large a head turn.

#### Key Activation Threshold

The percentage that controls *when in the return motion* a key actually activates. After a key has armed (turned green) in the Active Zone, the user moves back toward the centre; activation happens when they have moved back this much of the way. **0%** activates the key the moment the cursor begins moving back; **100%** requires the cursor to fully re-enter the start of the Active Zone before activating. Lower values are faster but more error-prone.

#### Exit/Pause Keyboard Delay Time

The number of seconds the cursor must dwell in the Exit Zone (with the keyboard border flashing) before JustType actually pauses or releases the cursor. Range is **2–5 seconds**. If the border starts flashing but the user does not actually want to exit, they simply move back toward the centre.

### Tuning and Feedback

#### Corner Weighting

Adjusts the apparent size of the four diagonal "slices" (NE, SE, SW, NW) relative to the four straight ones (N, S, E, W). Some users find diagonals harder to reach than straights — others, the opposite. Values **above 1.0** make corners bigger targets; values **below 1.0** favour the cardinal directions.

**▶ For teachers.** This is one of the underused tuning knobs. If a user is hitting wrong keys consistently in the same direction (e.g., always landing on N when they aim for NE), Corner Weighting is usually the right adjustment.

#### Vertical Sensitivity

The relative ease of vertical vs horizontal cursor motion. Values **above 1.0** amplify vertical motion (good when up/down head movement is more limited than side-to-side); values **below 1.0** amplify horizontal motion. Default 1.0 treats them equally.

**▶ For clinicians.** For users with cervical range-of-motion asymmetries — common in many neuromuscular conditions — Vertical Sensitivity is the single highest-leverage knob. Set it once, with the user, by having them sweep their gaze through the four cardinal directions and watching the cursor: if vertical sweeps reach less far than horizontal, raise the setting until they match.

#### Responsiveness

How much cursor distance the cursor covers per unit of head motion. Values **above 1.0** make the cursor faster (easier to reach the edges; harder to land precisely); values **below 1.0** make the cursor slower (more precise; more head movement required). Default 1.0.

**▶ For clinicians.** Combine Responsiveness with Vertical Sensitivity to fit the user's actual range of motion to the on-screen keyboard. The goal is that *reaching every corner of the keyboard* requires somewhere around 75% of their available head range — close enough to the edge to be reliable, but not exhausting.

#### Debug Overlay

When **ON**, JustType draws a diagnostic overlay on the keyboard showing zone boundaries, the live cursor position, and frame-rate information. Useful when troubleshooting unexplained behaviour. Off in normal use.

---

## 4. Set Up Joystick

The Joystick input method is broadly similar to Head Tracking — there is a cursor that moves over the keyboard, the same Resting / Active / Exit zone concept, and the same "move out, then come back" key-activation flow — but the cursor is driven by an external joystick rather than the camera. This page sets up the joystick source and tunes the same zone geometry as the head-tracking page.

> **Note.** Joystick input by itself does not *click* — it only points. To click (or double-click) at the joystick cursor location, you also need to configure a switch in HeadBoard.

### Joystick Source

Selects the connected joystick to use. Joystick connections are typically Bluetooth or USB-OTG; pair the device first in Android system settings. (The set of available sources here depends on what is connected.)

### Resting Zone

#### Resting Zone

The size of the "no movement" region in the centre. Inside it, joystick deflection has no effect on the cursor — it is where the user can rest the stick between key activations. Range **0.10–0.80**, expressed as a fraction of the keyboard radius. Increase if keys activate while the user is at rest.

**▶ For clinicians.** Joystick users are particularly prone to unintended drift — the stick sits a little off-centre at rest, especially with low-tone hands. A generous Resting Zone covers that drift without slowing down typing.

### Active Zone

#### Active Zone

The amount of joystick deflection that arms a key. When the user pushes into the Active Zone for a key, that key turns green; releasing the stick toward centre activates the key. Maximum value **0.90**. Decreasing makes keys arm sooner (less deflection) at the cost of more accidental activations.

**▶ For clinicians.** Active Zone is the joystick analogue of head-tracking's. Set it small enough that the user can comfortably reach every key; set it large enough that the natural travel of their hand does not stray into it.

### Exit Zone

#### Exit Zone

The outer boundary for joystick deflection within the keyboard. When the user pushes the stick all the way to the edge — past the Active Zone — the keyboard border begins to flash. If the deflection is held for the **Exit/Pause Keyboard Delay Time** below, JustType either releases the cursor into the region above the keyboard (deflection upward) or pauses joystick input (deflection right). To return, the user reverses the deflection. Decrease the Exit Zone to require less deflection to exit.

#### Exit/Pause Keyboard Delay Time

The number of seconds the joystick must remain in the Exit Zone (border flashing) before JustType pauses or exits. Range **2–5 seconds**. If the border flashes but the user does not actually want to exit, they pull back toward centre and continue typing.

**▶ For clinicians.** The Exit Zone + Exit/Pause Delay together govern how easy it is to *escape* the keyboard. For users who want to drive cursor input across the rest of the device with the same joystick, this needs to be quick. For users who never leave the keyboard, you can effectively disable exit by setting the delay to its maximum and the Exit Zone close to 1.0.

### Tuning

#### Corner Weighting

Adjusts the relative "size" of the four diagonal slices vs the four cardinal ones. Same semantics as the equivalent setting on the Head Tracking page: values **above 1.0** make corners easier targets; values **below 1.0** favour the cardinals.

---

## 5. Set Up Single-Switch Scanning

Single-Switch Scanning is the slowest but most accessible Alternative Input Method: the eight keys are highlighted one at a time in a fixed sequence, and the user closes a single switch when the desired key is highlighted. This page sets up the switch, the scanning rate, the auditory tracking aids, and the auto-repeat behaviour that makes scanning faster for experienced users.

### Switch Assignment

#### ACTIVATE SCANNING SWITCH

Press this button to enter switch-assignment mode, then press the physical switch (or perform the HeadBoard gesture) you want to use for scanning. JustType records that switch identity. The line below the button shows what is currently configured: **"Scanning Switch configured as: ___________"** (or "No switch selected").

If you want a Bluetooth switch, pair it in Android system settings first. If you want a HeadBoard-defined switch (a head-movement switch or a facial gesture), configure and enable it in HeadBoard before pressing this button.

**▶ For clinicians.** The first job in evaluating a switch user is *finding a switch they can reliably activate and release*. JustType is agnostic about which switch — that is determined by the hardware that lives on the user's setup. Bring evaluation switches; ACTIVATE SCANNING SWITCH lets you swap them in seconds.

#### Use Touchscreen Switch

When **ON**, JustType's built-in Touchscreen Switch acts as the scanning switch (instead of, or in addition to, an external switch). Behaviour and size of the Touchscreen Switch are configured on the [Set Up Touchscreen Switch](#7-set-up-touchscreen-switch) page.

**▶ For teachers.** The Touchscreen Switch is a useful evaluation tool — it lets you set up scanning without any extra hardware, so a learner can try the input method before you commit to buying or fitting a physical switch.

### Scan Behaviour

#### Scanning Layout Key Size

Choose **Large** (bigger keys, easier to read) or **Small** (more compact, leaves more room above for other content). Larger keys are usually preferred unless screen real estate is scarce.

#### Scan Step Delay Time

The number of seconds the highlight rests on each key before moving to the next. Lower values give faster scanning; higher values give the user more time to react. This is the single most important tuning knob on this page.

**▶ For clinicians.** Set Scan Step Delay during evaluation by timing the user's *response time* — how long after seeing the target key they actually fire the switch. Set the delay to that response time plus a comfortable margin. Reduce the margin gradually as the user builds familiarity.

#### Add Extra Delay on First Scanned Key

Extra time (in seconds) added to the *first* scan step after the user closes the switch (or after scanning re-starts). Gives the user a moment to release the switch before the next scan begins. Set to **OFF (0)** for no extra delay.

**▶ For teachers.** This setting solves a specific problem: a user activates a key, then their switch lingers closed slightly longer than expected, and the *next* scan immediately registers a second activation. The extra delay on the first key absorbs that lag.

### Selection

#### Beep at Each Scanning Step

When **ON**, a short beep sounds each time the highlight moves to the next key. Provides an auditory rhythm that helps users track scanning progress without staring at the keyboard.

#### Skip Keys with No Valid Next-Letters

When **ON**, the scan automatically skips keys whose every letter has been greyed out by Next-Letter Hints — there is no point dwelling on a key that cannot produce a valid next letter. This significantly speeds up scanning. The trade-off is that the scan rhythm becomes less predictable, so you will probably also want **Show Next Key to be Scanned** (next setting) ON to compensate.

**▶ For teachers and clinicians.** Skip Keys with No Valid Next-Letters is a high-leverage feature for fluent scanning users. It pairs naturally with Next-Letter Hints (already a flagship feature on the JustType Display page), which a skilled scanner watches anyway.

#### Show Next Key to be Scanned (when any key will be skipped)

When **ON**, and when the next scan step will involve a skip, JustType pre-highlights the *upcoming* target key with a pale yellow tint before it actually arrives. The pale yellow gives the user visual warning ("you are about to need to fire the switch") on top of the bright yellow current highlight. Only meaningful when **Skip Keys with No Valid Next-Letters** is ON.

**▶ For teachers.** Without this setting, users can miss the bright-yellow highlight on a skipped sequence because they were not expecting the skip. With it, the eye lands on the upcoming key in advance.

#### Key Activation Auto-Repeat Mode

When **ON**, holding the switch closed *after* an initial activation continues to fire that same key repeatedly at the **Auto-Repeat Delay Time** interval. This is significantly faster than waiting for a full scan cycle to come around when the same key is needed twice in a row, which happens roughly 17% of the time in typical English typing.

**▶ For clinicians.** If holding-and-releasing the switch is mechanically feasible for the user, Auto-Repeat Mode is one of the most consequential efficiency wins available in scanning. Test it during evaluation; some users find the holding-action harder than repeated taps, in which case turn it OFF.

#### Auto-Repeat Delay Time

The number of seconds between repeated activations when the switch is held in **Key Activation Auto-Repeat Mode**. A practical default is to set this to the same value as the **Scan Step Delay Time** above.

#### Select Key Triggers Scan of Selection List

When **ON**, pressing the switch on the Select key automatically begins a scan of the Selection List (where the predicted words are shown). Selecting a word then requires a *second* switch closure, when the desired word is scanned.

**▶ For clinicians.** This setting trades efficiency for accessibility. For most users, holding the switch on the Select key (with **Key Activation Auto-Repeat Mode** ON) accomplishes the same thing more efficiently. Turn this ON only when (a) Auto-Repeat Mode is not feasible, (b) Scan Step Delay needs to be unusually long (so typing extra letters to shorten the Selection List is too costly), and (c) the user can keep eyes on the Selection List.

#### Number of Times to Repeat Scan if No Key is Selected

How many times the scan cycles through the eight keys before stopping automatically when no key is selected. **0** means infinite (the scan only stops when the user selects a key). Reduce if the cycling becomes distracting; pressing the switch once re-starts a stopped scan.

#### Ignore Rapid Switch Hits (Debounce)

A short cool-off after each switch closure during which subsequent closures are ignored. Default **120 ms**. Increase if the system is registering multiple presses for what the user perceives as a single activation — this is common with noisy mechanical switches.

> **Highlight Timeout** appears on the [Two-Switch Selection Set-Up](#6-two-switch-selection-set-up) page (renamed there to **Two-Switch Selection Reset Delay**); it does not apply to single-switch scanning.

### Auditory Feedback

(Auditory feedback for switch input lives in the **Beep at Each Scanning Step** setting above and in the global Input Methods → Auditory Feedback section.)

---

## 6. Two-Switch Selection Set-Up

Two-Switch Selection encodes each key as a sequence of *three* switch closures, alternating between two switches (typically labelled green and red). It is dramatically faster than scanning once the user has memorised the sequences — every key takes the same three closures, with no waiting for a scan to come around. This page assigns the two switches and configures the selection behaviour and feedback.

### Switch Assignments

#### ACTIVATE GREEN SWITCH / ACTIVATE RED SWITCH

Two parallel buttons assign the two switches. Press a button, then activate the corresponding physical switch (or HeadBoard gesture) to record it. The line below each button shows what is currently configured.

If you want Bluetooth switches, pair them in Android system settings first. If you want HeadBoard-defined switches (head-movement or facial gesture), configure and enable them in HeadBoard before assignment.

**▶ For teachers.** The convention is **green on the left, red on the right** — both physically (when the user has two side-by-side switches) and visually (when reading the colour-coded sequences on the keyboard). Plan training and seating around this convention.

#### Use Touchscreen Switch

When **ON**, JustType's Touchscreen Switch provides the two switches (left half = green, right half = red) — useful as an evaluation or no-hardware fallback. Size and behaviour are on the [Set Up Touchscreen Switch](#7-set-up-touchscreen-switch) page.

### Selection Behaviour

#### Auto-Repeat Switch Activation

When **ON**, holding either switch closed produces repeated *switch activations* at the **Auto-Repeat Switch Activation Delay Time** interval — useful when holding-and-releasing a switch is easier than re-pressing it. Activating the Select key, for example, requires three green-switch activations in a row; with Auto-Repeat Switch Activation ON, holding the green switch alone is enough.

**▶ For clinicians.** This setting and the next two sit on a small ladder of related switch-stamina trade-offs. Test all three combinations during evaluation: (a) both off — fast users with reliable taps; (b) Auto-Repeat Switch Activation only — users who prefer holding to tapping; (c) both auto-repeats on — experienced users who want maximum throughput.

#### Auto-Repeat Switch Activation Delay Time

Number of seconds between repeated switch activations when a switch is held. Tune by feel — too fast and three-tap sequences over-fire; too slow and holding becomes inefficient.

#### Auto-Repeat Key Activation

When **ON**, the *third* closure of a sequence — the one that actually selects a key — can be held closed to repeat the same key activation. Combined with **Auto-Repeat Switch Activation**, holding the third switch closure both completes the sequence and continues firing that key.

#### Auto-Repeat Key Activation Delay Time

Number of seconds between repeated key activations when the third closure is held in Auto-Repeat Key Activation mode.

### Auditory Feedback

#### Beep on Each Switch Activation

When **ON**, a short beep sounds for *every* switch closure (not just every key activation). Provides an immediate confirmation rhythm — "tap-beep, tap-beep, tap-beep" through a three-closure sequence.

**▶ For teachers.** New Two-Switch users often find it hard to track *where they are* in a three-closure sequence, especially if their attention drifts between closures. The per-closure beep is a strong scaffolding aid; turn it on early and let the user turn it off later when they have internalised the sequences.

### Visual Feedback

#### Show Color Band

When **ON**, each key shows a small horizontal band beneath it with three coloured segments — the green/red sequence that selects that key. Helps users learn and recall sequences without waiting for the live red/green key highlighting to change.

**▶ For teachers.** The colour band turns the keyboard into a self-cueing chart: a learner can plan ahead by reading sequences off the bands rather than memorising. Plan to leave it on during early training, and have the user turn it off as memorisation cements.

> **Disable Highlight** has been removed: in Two-Switch Selection, the red/green key-highlighting is always on, since it is what makes the sequence visible in real time.

#### Two-Switch Selection Reset Delay

Each key activation requires three switch closures. If the user starts a sequence but pauses before finishing — perhaps their attention shifts elsewhere — the partial sequence will sit there unfinished until they remember it. This setting controls how long an unfinished sequence is held: when set to a non-zero number of seconds, an unfinished sequence is automatically *cancelled* after that pause, and the next switch closure starts a fresh one. **OFF (0)** disables cancellation entirely.

**▶ For clinicians.** This setting addresses a real ergonomic problem in shared-attention contexts (e.g., classroom use). Without it, a user returning to typing after an interruption can produce the wrong key. With it, returning to typing always feels fresh.

#### Stuck Switch Timeout

If the system fails to detect a switch *release*, this is the number of seconds it will wait before deciding the switch is stuck and ignoring it. Prevents runaway input when a switch jams or is accidentally held closed. Increase if it triggers when the user is intentionally holding a switch for long stretches.

---

## 7. Set Up Touchscreen Switch

The Touchscreen Switch turns part of the screen into a virtual switch — useful as a no-hardware option for switch-based input methods, and as an evaluation tool when sourcing physical switches. It can run in single-switch mode (the entire bottom strip is one switch) or two-switch mode (left half / right half). This page configures the geometry, mode, and feedback of the touchscreen switch area.

### How Touchscreen Switch Works

The Touchscreen Switch occupies the bottom of the screen, full-width. In single-switch mode it is one solid (green) area. In two-switch mode it is split into two side-by-side halves: **green on the left, red on the right** — matching the convention used in Two-Switch Selection.

```
Single-Switch:                          Two-Switch:
┌───────────────────────────┐           ┌─────────────┐ ┌─────────────┐
│  Switch (Green / single)  │           │ Green (LEFT)│ │  Red (RIGHT)│
└───────────────────────────┘           └─────────────┘ └─────────────┘
```

**▶ For teachers and clinicians.** The Touchscreen Switch is the first thing to reach for during evaluation. It lets a new user try Single-Switch Scanning or Two-Switch Selection on the day you meet them, with no additional hardware. Once a method has been validated, transition the user to a physical switch that suits their motor profile.

### Switch Mode

#### Touchscreen Switch Mode

Choose between **Single-Switch** (one full-width green area, suitable for Single-Switch Scanning, or as the selection switch for Head Tracking or Joystick) and **Two-Switch** (the area is split into a left/right pair for Two-Switch Selection).

### Touchscreen Switch Geometry

#### Touchscreen Switch Height

The vertical height of the touchscreen switch area, expressed as a multiple of the keyboard's row height. **1.0** is the height of one keyboard row; **3.0** matches the full keyboard. Range **0.5–4.0**. Use a tall switch when the user needs a generous target; use a short one to leave more screen space free.

**▶ For clinicians.** For users with limited motor accuracy (the most common case for a Touchscreen-Switch user), set the height generously — 2.0 or higher is typical. Reduce later only if the user is reliably hitting the switch and screen real estate is at a premium.

### Visual & Auditory Feedback

#### Flash Switch Bar on Activation

When **ON**, the touchscreen switch area briefly flashes when tapped — visual confirmation that the touch was registered.

#### Beep on Switch Activation

When **ON**, a short beep sounds when the touchscreen switch area is tapped.

#### Ignore Rapid Switch Hits (Debounce)

A short cool-off after each touch during which subsequent touches are ignored. Default **120 ms**. Increase if the system is registering multiple activations for what feels like a single tap.

### Overlay

#### Overlay Mode

When **ON**, the Touchscreen Switch displays as a *translucent overlay* — its area still captures touches, but the screen contents below remain visible. The keyboard appears in its usual place at the bottom of the screen. This is useful when the user wants the touchscreen switch in addition to a normal-height keyboard, without the switch taking dedicated vertical space.

When you do *not* explicitly turn Overlay Mode on, JustType will automatically turn it on when the combined height of the touchscreen switch and the keyboard (including Key History, if shown) would exceed two-thirds of the available screen — this prevents the keyboard area from squeezing the rest of the device's UI.

> The Overlay Mode feature itself is not yet implemented in code; the settings UI is in place so that the page does not need to be re-arranged when the feature lands.

**▶ For teachers and clinicians.** Overlay Mode is the right choice for users on small screens (e.g., phones) who use the Touchscreen Switch with a non-trivial keyboard size. It avoids the unpleasant trade-off of "shrink the keyboard to make room for the switch."

#### Overlay Mode Display Timeout

The number of seconds the touchscreen switch overlay remains *visible* after activity stops. After the timeout, the overlay becomes fully transparent — its switch areas are no longer drawn, but they remain fully active. The user can still tap them; they just no longer see them.

---

## 8. Direct Selection

This is the simplest input method: each key is activated by a tap. There is exactly one tunable parameter.

### Tap Behaviour

#### Ignore Rapid Key Hits (Debounce)

A short cool-off after each tap during which a second tap on the same area is ignored. Default **120 ms**. Increase if a single tap is sometimes registered as a double tap — common with users who have tremor or hand-rest issues, and on some screen-protector materials that produce double-touch artefacts.

**▶ For clinicians.** Debounce is a cheap, almost-free way to make Direct Selection work for users who would otherwise need to switch to a different input method. Try increasing it to 200–300 ms before concluding that a user cannot use Direct Selection.

---

## 9. Directional Selection

Directional Selection activates keys by *direction of swipe* rather than *location of tap*. A short swipe in any direction on the keyboard activates the key in that direction — N, NE, E, SE, S, SW, W, NW. This page tunes how easily a swipe registers and how a swipe is debounced from accidental repeats.

### Swipe Behaviour

#### Ignore Rapid Screen Swipes (Debounce)

A short cool-off after each swipe during which subsequent swipes are ignored. Default **120 ms**. Increase if a single swipe is being registered as multiple inputs — sometimes happens on fingers that drag slightly at the end of a swipe.

#### Swipe Sensitivity (Minimum Swipe Length)

The minimum distance a finger must travel on the screen to register as a swipe. Adjust the slider so that the on-screen slider length matches the desired minimum swipe length. Lower values make swipes easier to trigger with smaller movements; higher values prevent accidental swipes from incidental finger contact.

**▶ For clinicians.** Swipe Sensitivity is the central knob for matching Directional Selection to a user's motor range. Set it during evaluation by having the user produce their *most comfortable* swipe — short or long — and tune the threshold to be just inside that. The eight directional targets become reliable as soon as the threshold is right, even for users with very limited range.

---

## 10. Vocabulary Management

Vocabulary Management controls *which words JustType can spell* and *which of those words receive special treatment*. JustType understands several types of vocabulary: a built-in main word database, custom user-added words, abbreviation/phrase pairs, and any **Imported Vocabulary Modules** the user has loaded from text files. This page configures global vocabulary toggles, frequency-based filtering, and the **Accented Next-Letter Hints** feature that highlights words from selected vocabulary modules.

### Active Vocabularies Overview

#### Include JustType Dictionary

When **ON**, the built-in JustType main database (the standard ~51,000-word English dictionary, in the case of English) is included in JustType's spelling repertoire. Turn this OFF only when you specifically want JustType limited to *just* the words in selected Imported Vocabulary modules and Custom Words — for example, in a literacy-training session focused on a specific word list.

**▶ For teachers and clinicians.** Turning Include JustType Dictionary OFF is a significant intervention: it dramatically narrows what the user can type. Use it only for short, focused practice sessions where the constraint is the goal. Restore it for everyday use.

#### Include Custom Words

When **ON**, words the user has manually added (via the **Add New Word** function on the keyboard's Navigation page) are included in the words JustType can spell. These typically include personal names, places, jargon, or any word missing from the main database.

#### Include Abbreviation/Phrase Pairs

When **ON**, multi-word abbreviation/phrase pairs added via **Add New Phrase** appear in the Selection List when the user types the associated abbreviation. For example, a phrase "Call me back later." stored under abbreviation `CMBL` will appear in the Selection List when the user types `CMBL`.

### Limit Uncommon Words

This section restricts the words JustType displays or generates to *more frequent* ones, on the rationale that less common words add clutter and selection-list noise. The settings here only take effect when **Restrict Uncommon Words** is on; the individual filters are independent within that umbrella.

#### Restrict Uncommon Words

When **ON**, the four filter settings below are available; the umbrella switch lets the user disable all the filters at once without losing their individual settings.

**▶ For clinicians.** Restrict Uncommon Words is the central knob for *literacy scaffolding*. It lets you constrain JustType's behaviour to a chosen vocabulary tier (very common words only, or common-and-medium, etc.) while a learner builds spelling fluency, then loosen the constraint as the learner progresses.

#### Limit Next-Letter Hints to Common Words

When **ON**, **Next-Letter Hints** are no longer shown for words below a chosen frequency threshold. Higher thresholds show hints for fewer (more-common) words. The intent is to make the surviving hints stand out more — fewer letters bolded, but the ones that are bolded carry more weight.

**▶ For teachers.** This is one of the most powerful literacy tools in JustType. By raising the threshold, you turn Next-Letter Hints into a *guided spelling tour* of the most-common words; users see fewer suggestions but the suggestions they do see are higher-confidence.

#### Limit Selection List to Common Words

When **ON**, words below the chosen frequency threshold are excluded from the Selection List entirely. Useful for pruning rare words during conversational typing, or for restricting the list to *very* common words during early literacy practice.

**▶ For clinicians.** Limit Selection List to Common Words is more aggressive than Limit Next-Letter Hints to Common Words — it makes uncommon words *unreachable* through normal typing, not just unflagged. Use it sparingly outside of training sessions; pair it with **Add Restricted Words to End of List** if you want to retain access without giving up the headline filtering.

#### Add Restricted Words to End of List

When **ON**, words filtered out by the Selection List filter still appear at the *end* of the Selection List, after the qualifying words. This lets the user reach a less-common word when needed, without it cluttering the top of the list during normal typing.

### Accented Next-Letter Hints

This section enables a *second*, distinct kind of Next-Letter Hint that appears in **bold dark red** rather than the regular hint bold. Accented Hints are configured to fire only for words in selected vocabulary modules (or selected frequency classes of the main database). The visual contrast means they really stand out when typing — which is the point.

**▶ For teachers and clinicians.** Accented Hints are JustType's flagship *targeted-vocabulary scaffolding* feature. They let you highlight a learner's current word list — say, twenty new vocabulary words for the week — without removing the rest of the dictionary. The learner sees the highlighted hints, internalises the spelling pattern, and gradually moves to the next word list as fluency builds.

#### Enable Accented Next-Letter Hints

The umbrella switch for Accented Hints. When **ON**, the page shows a calculated count of how many words across all enabled modules are currently flagged for accented display, so the user has immediate feedback on how aggressive the configuration is.

#### Turn OFF Accented Hints for Vocabulary Words Spelled More Than ___ Times

JustType keeps a per-word counter of how many times each word has been typed in JustType. This setting uses that counter as a proxy for *learnedness*: once a word has been typed N times, JustType assumes the user knows its spelling and stops drawing accented hints for it. Range **0 (disabled — accented hints never expire)** through **20**.

**▶ For teachers.** This setting lets you build *automatic literacy graduation* into the user's daily typing — words that have been practised enough automatically stop being flagged, freeing up the visual space for newer words. A typical setting is somewhere in the 5–10 range, depending on how many practice repetitions the user needs.

### Limit Accented Hints for Main JustType Words by Frequency

This section narrows down which *main-database* words get accented hints. Most users configure Accented Hints around their Imported Vocabulary modules; this section is for the case where you also want to tier the main database by frequency. The two settings below define a frequency *band* (a minimum and maximum frequency class), and main-database words in that band become candidates for accented hints.

#### Minimum Frequency for Accented Hints

When set to a frequency class (1–14 in the standard frequency banding), accented hints will only fire for main-database words at least that frequent. Set the slider to its maximum to disable this filter (no minimum — all words qualify).

#### Maximum Frequency for Accented Hints

When set to **0**, this filter is OFF (no maximum — all words qualify). When set to a frequency class (1–14), only main-database words *less* frequent than that class qualify. Combining this with **Minimum Frequency for Accented Hints** lets you target a specific frequency band — say, "words rarer than the top 1,000 but more common than the rarest 10,000."

**▶ For teachers.** Used together, Minimum and Maximum Frequency define a sliding *practice band*. As the user masters a band, you can shift the band wider (lower the minimum) or shift it deeper (lower the maximum) to keep the accented hints meaningful.

#### Turn OFF Accented Hints for Main Database Words Spelled More Than ___ Times

The same automatic-graduation logic as the Imported-Vocabulary version above, but applied to main-database words in the configured frequency band. Range **0 (disabled — accented hints never expire)** through **20**.

### 10.1 Select Active Vocabularies

This sub-page is where you turn individual vocabulary modules on or off, and where you configure which modules drive Accented Hints. JustType supports up to **28** vocabulary modules at once — three "standard" modules (the main database, custom words, and abbreviation/phrase pairs) plus up to 25 imported ones. A special **(Past Vocabularies)** module exists as a single bucket for retired imports.

#### Promote Words from Active Imported Vocabularies

When **ON**, words contained in any *enabled* Imported Vocabulary receive a ranking boost in the Selection List, appearing higher than equally-ranked main-database words. This makes a learner's current word list reach the front of the Selection List with one Select-press, rather than being buried under more-common but less-relevant words.

**▶ For teachers and clinicians.** Promote Words from Active Imported Vocabularies is the simplest form of *vocabulary targeting* in JustType. If you have built a custom imported vocabulary for a particular user and the user is doing real typing (not just literacy practice), this toggle alone changes the Selection List dynamics dramatically. Default ON for any user with active Imported Vocabularies.

#### Select Active Vocabulary Modules

A table that lists every Vocabulary Module currently in JustType, with one row per module and two checkboxes per row. The three standard modules (JustType Main Database, Custom User Words, Abbreviations/Phrases) are always present; Imported Vocabularies fill remaining slots up to the 28-slot maximum.

> When all 28 slots are used and you want to import a new vocabulary, free a slot by going to **Manage Vocabularies** and either deleting an unused module or *merging* it into another module. Merging into the special **(Past Vocabularies)** bucket retains the words at the database level; the merged module no longer occupies a slot.

**▶ For teachers and clinicians.** The 28-slot constraint exists to keep the table navigable and the *active* word set tractable. In practice, most users use far fewer; merging old modules into (Past Vocabularies) as you go keeps the table tidy and the user's vocabulary visible at a glance.

##### Module rows

- **JustType Main Database** — the built-in dictionary, ~51,000 English words. Turn it off only to limit the user's vocabulary to selected imports for a focused session.
- **Custom User Words** — words the user has added via **Add New Word** (typically personal names, places, jargon).
- **Abbreviations/Phrases** — multi-word phrases added via **Add New Phrase**, recallable by typing their abbreviation.
- **(Past Vocabularies)** — the merged-and-retired bucket. Words here are still typable when the row is enabled, but they are not visible as a separate module.

  **▶ For teachers.** (Past Vocabularies) is a "graveyard with a switch": it lets you retire a learner's old word list to free a slot without throwing the words away, and lets you turn the entire graveyard on at once if the learner needs broader access (typically less overwhelming than re-enabling the main database).

##### Module columns

- **Active Word Count** — how many words from this module are currently typable, given the filters configured on the parent Vocabulary Management page. Restricting the number of active words can make the active words easier to type.
- **Date Added** — when each Imported Vocabulary was imported. Useful for managing turnover among many imports.
- **Enable Module** — when checked, the module's words are typable. When unchecked, the words are skipped during prediction and Selection.
- **Show Accented Hints** — when checked, words in this module qualify for Accented Hints when typed. Only meaningful when **Enable Module** is also checked.
- **Accented Word Count** — how many words from this module currently qualify for Accented Hints, given the Vocabulary Management filters.

### 10.2 Manage Vocabularies

This sub-page handles *structural* operations on vocabulary modules: importing a new module from a text file, merging two existing modules, and deleting a module to free a slot. Three action sections live on this page; each is described separately.

#### Import a New Vocabulary Module

To create a new module:

1. Prepare a plain text file containing the words you want — one per line works, as does any whitespace-separated format. The file can live on the device or in an accessible Google Drive folder.
2. Press **CHOOSE FILE TO IMPORT**. (This button is greyed out when all 28 slots are filled — merge or delete a module first.) Pick the text file.
3. The proposed module name is the file name; you can edit it.
4. Press **IMPORT SELECTED FILE** to create the module.

#### Merge Two Vocabulary Modules

To free a slot by merging:

1. Choose the *target* module — the one whose slot will remain. **(Past Vocabularies)** is selected by default; you can pick any other custom module.
2. Choose one or more source modules to merge in, by checking their **Merge From** column boxes.
3. Press **MERGE SELECTED VOCABULARIES**.

The merged words become part of the target module; the source modules vanish, freeing their slots.

#### Delete a Vocabulary Module

To free a slot by deleting:

1. Choose one or more modules to delete by checking their **Delete Vocabulary** column boxes.
2. Press **DELETE SELECTED VOCABULARIES**.

> Deleting a module frees its slot, but the words themselves remain in the underlying database — they are still typable. Deleting only removes the module-level grouping. To actually purge words, edit them out of the database directly or merge into (Past Vocabularies) instead.

### 10.3 Export Vocabulary Usage

This sub-page exports the words contained in selected vocabulary modules to a text file, along with each word's typing-count. Useful for reviewing what a user actually types, for sharing word lists between teachers, or for archiving a module before deleting it.

#### Configure the Export

The exported modules are *all enabled modules* in the **Select Active Vocabulary Modules** table (sub-page 10.1). To narrow the export, change the enabled set there before coming here.

Within those modules, you can filter the exported words by usage count using one of four options:

1. **Never** — only words that have never been typed.
2. **At Least Once** — only words that have been typed one or more times.
3. **At Least ___ Times** — only words typed at least the number of times you select with the slider.
4. **All Words** — every word in the enabled modules, regardless of typing count.

#### Run the Export

1. Press **CHOOSE EXPORT FOLDER** to pick (or create) a folder on the device or a Google Drive folder. JustType pre-suggests a file name based on the modules selected and the current date/time; you can edit it before exporting.
2. Press **EXPORT USAGE DATA FOR ENABLED VOCABULARIES** (called **EXPORT USAGE DATA** on the page after the folder is chosen) to write the file.

For each word, the file shows the word and the number of times it has been typed in JustType.

---

## 11. Backup & Restore

JustType lets you back up *everything* that is specific to a user's setup — vocabulary modules, custom words, abbreviation/phrase pairs, all settings, and the per-word usage counters that personalise the main database — to a folder on the device or in a Google Drive. Restoring from a backup brings a fresh JustType install (or a different device) to exactly the saved state.

The page has two modes: a **Simple** mode for everyday users with one device and one backup destination, and an **Advanced** mode for teachers and clinicians who maintain multiple users' backups across multiple folders.

### Simple Backup & Restore

In Simple mode (the default — **Use Advanced Backup & Restore** is OFF), JustType stores a single backup destination. The main page presents two buttons: **CREATE BACKUP NOW** and **RESTORE FROM BACKUP**.

> **Heads-up.** Restoring overwrites your current JustType data. Any changes you have made since the last backup will be lost.

**▶ For device users.** Create a backup before doing anything risky — switching devices, factory-resetting, accepting a major Android update, or letting someone else fiddle with JustType's settings. The backup is the cheap insurance you wish you had after the fact.

#### Create a Backup

1. Press **CREATE BACKUP NOW**. The first time you do this, you will be sent to a standard Android folder picker — navigate to a folder on the device or in Google Drive, then press **Use this folder**. If a permissions prompt appears asking to allow JustType access, press **Allow**.
2. The page reloads with the chosen folder and the date/time of the most recent backup in it.
3. Press **CREATE BACKUP NOW** again. JustType writes the full backup. Subsequent backups simply update the same folder with current data; you do not need to re-pick the folder.

**▶ For teachers and clinicians.** A Google Drive backup folder is portable across devices and survives device replacement. Use one when there is any chance the user will move JustType to a different device.

#### Restore from a Backup

1. Press **RESTORE FROM BACKUP**. (Greyed out until at least one backup exists.)
2. Confirm at the prompt: *Restore backup? This will overwrite current data. A restart may be needed to reload data.*
3. Press **RESTORE**.

#### Use Advanced Backup & Restore

When **ON**, JustType allows multiple backup folders, each holding its own backup, with the page rearranged to expose them. When **OFF** (the default), the page operates in the simple, single-folder mode described above.

**▶ For teachers and clinicians.** Turn this ON when you support more than one user, or when you maintain stages of a single user's setup (e.g., "before vocab module change", "after vocab module change"). Leave it OFF for everyday personal use — Simple mode is faster and harder to mis-operate.

### Advanced Backup & Restore

When **Use Advanced Backup & Restore** is ON, the page is rearranged to expose a **Previous Backups** list at the top — every folder JustType has ever created or used a backup in is preserved across sessions and across restores. The **CREATE BACKUP NOW** and **RESTORE FROM BACKUP** buttons act on whichever folder is currently selected in the list.

The original page-level explanation is replaced by a longer one that describes the multi-folder workflow — particularly useful for teachers, clinicians, or technical-support staff setting up JustType on more than one person's device.

#### Previous Backups

A list of every backup folder JustType has used, sorted with the most recent at the top. Each row shows the folder location, the date and time of the most recent backup in that folder, and a checkbox that selects the row as the current target. The most recent backup is selected by default.

When the list is empty (no folders have been chosen yet), both **CREATE BACKUP NOW** and **RESTORE FROM BACKUP** are greyed out until you add a folder.

**▶ For teachers and clinicians.** The multi-folder list is the heart of the Advanced workflow. A row per user (or per setup stage) lets you switch between backups in seconds, without re-navigating the Android folder picker. Treat the list itself as a kind of *case-management view* of the users you support.

#### CHOOSE/CREATE NEW BACKUP FOLDER

(In Simple mode this button is labelled simply **CHOOSE BACKUP FOLDER**.) Press it to add a new folder — either a fresh one or an existing one not yet known to JustType. The standard Android folder picker appears; navigate to the folder and press **Use this folder**. If a permissions prompt appears, press **Allow**.

The folder is added to the Previous Backups list and selected as the current target. Press **CREATE BACKUP NOW** to write a fresh backup into it. Earlier backups in other folders are *not* touched.

This button is always available in Advanced mode, so you can add folders any time without disturbing existing backups.

#### CREATE BACKUP NOW (Advanced mode)

In Advanced mode, this button updates the backup in the folder *currently selected* in the Previous Backups list. All current data — vocabulary modules, custom words, abbreviation/phrase pairs, settings, and per-word usage counters — is written into the selected folder, replacing whatever was there. Other folders' backups remain untouched.

The button is greyed out until a folder is selected; the list is empty until you press CHOOSE/CREATE NEW BACKUP FOLDER for the first time.

#### RESTORE FROM BACKUP (Advanced mode)

In Advanced mode, this button restores from the folder currently selected in the Previous Backups list. The same confirmation prompt appears as in Simple mode — *Restore backup? This will overwrite current data. A restart may be needed to reload data.* — and the same overwrite warning applies.

The button is greyed out until a folder *containing a completed backup* is selected — a folder you just added with CHOOSE/CREATE NEW BACKUP FOLDER but have not yet backed up to is not yet restorable.

### Backup History

JustType records every backup folder it has used in a backup-history file alongside the main backup manifest. This history is what powers the Previous Backups list above; it persists across sessions, across reboots, and across restores from backups (so restoring on a new device brings the full history along).

---

*See also: [User Guide](UserGuide.md) · [Beta Tester Quick Start](BetaTesterQuickStart.md)*
