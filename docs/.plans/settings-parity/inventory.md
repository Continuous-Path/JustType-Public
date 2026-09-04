# Settings Parity Inventory — System Settings vs Keyboard Settings

**Date:** 2026-08-03 · **Branch:** claude/awesome-poincare-a3fcd5 (dev tip)

> **STATUS: all gaps below were closed on 2026-08-03** (same branch). The
> living contract now lives at [docs/settings-parity.md](../../settings-parity.md)
> — consult that for future check-ups; this file is the historical audit.

Goal: every user setting controllable in the touchscreen **System Settings**
(SettingsActivity + sub-activities/fragments) should also be controllable in
the **Keyboard Settings** (Settings Mode via the Navigation list-function,
`KeyboardSettingsController`), and vice versa.

## Architecture (why drift happens)

- `SettingsRegistry` is the declared single source of truth. **Keyboard
  Settings renders every registry page automatically** (root `main`, SubPage
  links; developer page hidden until revealed; input-method setup subpages
  filtered to enabled methods).
- The touch UI renders **only the `main` page** through `SettingsRenderer`
  (SettingsActivity, with bespoke rows for the same keys). Every sub-screen —
  Input Methods, per-method Setup fragments, Vocabulary, Developer, Backup,
  Navigation Mode — is **hand-built** and must be kept in sync manually
  (the registry header comment says exactly this). All current discrepancies
  are in those hand-built screens or in registry pages that were never
  populated.

## Complete inventory

Legend: ✓ = present · — = absent. "Kbd page" = registry page (keyboard
surface). "Touch screen" = activity/fragment that renders the control.

### main (rendered from registry by BOTH surfaces — in sync by construction)

App language, Typing language, Voice type, Speech voice (picker), Get more
languages, UI voice type, UI voice (picker), Spanish Region*, Show accented
keys*, Require accented keys*, Spell-mode diacritic scope, Letter
Arrangement, Optimized layout source, Word selection mode, Paged listed
words, Tone label style*, Keyboard size, Extra blank line on Enter, Speak
word/phrase/sentence, Speak punctuation names, Speaking indicator icon,
Phrase speak delay, Next-letter hints, Key history (show/height/shrink),
Error beep, Auto-restore selection, Case-type variants/deferred/delay, Show
abbreviations/phrases in selection, Submit feedback.
(* conditionally present — langpack-gated, same gating both surfaces.)

Touch-only extra rows (actions, intentional): Open Welcome Guide, Emergency
Reset.

### input_methods — kbd page `input_methods` / touch `InputMethodsActivity` — IN SYNC

Primary method, Direct/Directional/Touch-screen-switch enables, Flash key,
Beep key, Vibration, Speak selected word/key, Speak punctuation, Speak
settings prompts — all present on both. (Touch additionally shows Error beep,
also on main — fine.)

### head_tracking — kbd page `head_tracking` / touch `SetupHeadTrackingFragment`

| Setting | Kbd | Touch |
|---|---|---|
| Deadzone / Activezone / Exitzone | ✓ | ✓ |
| Key activation threshold / Aim tolerance | ✓ | ✓ |
| Exit delay / Restart delay | ✓ | ✓ |
| Pitch scale / Response curve / Corner bias | ✓ | ✓ |
| Debug overlay / Diag logs / Re-arm in feedback | ✓ | ✓ (on Developer screen, not HT setup) |
| **Correction beep** (`KEY_HEADTRACKING_CORRECTION_BEEP`) | ✓ | **—** |
| **Correction flash red** (`KEY_HEADTRACKING_CORRECTION_FLASH_RED`) | ✓ | **—** |

### joystick — kbd page `joystick` / touch `SetupJoystickFragment`

| Setting | Kbd | Touch |
|---|---|---|
| Deadzone / Activezone / Corner bias | ✓ | ✓ |
| **Accept any device** (`KEY_JOYSTICK_ACCEPT_ANY`) | **—** | ✓ |
| Device binding (name/descriptor picker) | **—** | ✓ (hardware picker flow) |

### mouse_joystick — kbd page `mouse_joystick` / touch `SetupMouseJoystickFragment`

Presets (light/standard/firm) + 6 sliders (sensitivity, deadzone, exit
delay, activezone, corner bias, re-engage hysteresis): present on both.
**BUT: the keyboard page is unreachable** — `filterInputMethodSubPages`
never adds `nav_mouse_joystick` to `visibleSubPageKeys`, even when Mouse
Joystick is the primary method (KeyboardSettingsController.kt ~372-378).

### single_switch — kbd page `single_switch` / touch `SetupSingleSwitchFragment`

Switch capture, debounce, scan step delay, initial delay increase, repeat
count, skip invalid keys, show next key, autorepeat mode/delay, Select
triggers scan, beep each step, scan layout size: ✓ both.
**Keyboard highlight timeout** (`KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC`):
kbd puts it on `single_switch`; touch puts it on the **two-switch** screen —
placement mismatch (each surface's *other* method screen lacks it).

### two_switch — kbd page `two_switch` / touch `SetupTwoSwitchFragment`

Red/green switch capture, autorepeat mode/delay, repeat activations/delay,
beep activation, show band: ✓ both.

| Setting | Kbd | Touch |
|---|---|---|
| **Stuck-switch timeout** (`KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC`) | ✓ | **—** |

### touch_switch — kbd page `touch_switch` / touch `SetupTouchScreenSwitchFragment`

Mode, debounce, overlay timeout, flash, beep, button height, overlay
opacity, overlay mode: ✓ both.

| Setting | Kbd | Touch |
|---|---|---|
| **Show region border** (`KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER`) | **—** | ✓ |

### direct_selection — kbd page / touch `SetupDirectSelectionActivity` — IN SYNC

Debounce, autorepeat mode, autorepeat delay: ✓ both.

### directional_selection — kbd page / touch `SetupDirectionalSelectionActivity`

Debounce, swipe percent: ✓ both.

| Setting | Kbd | Touch |
|---|---|---|
| **Show region border** (`KEY_DIRECTIONAL_SHOW_REGION_BORDER`) | **—** | ✓ |

### vocabulary — kbd page `vocabulary` / touch `VocabularyManagementActivity`

Include JustType/custom/phrases, promote imported, freq filter + min freq,
show excluded at end, accent suggestions (enabled, min/max freq, use-count
maxes): ✓ both.

| Setting | Kbd | Touch |
|---|---|---|
| **Turkish/Azeri case override** (`KEY_TURKISH_AZERI_CASE_OVERRIDE`) | ✓ | **—** |

Touch-only management flows (not scalar settings): vocabulary module
enable/disable masks (`KEY_VOCAB_ACTIVE_MASK`, `KEY_VOCAB_ACCENT_MODULE_MASK`
via ManageVocabulariesActivity), import/export, phrase management.

### navigation_mode — kbd page `navigation_mode` / touch `SetupNavigationModeFragment`

Kbd page is **InfoText-only**. All real Navigation Mode settings are
**touch-only**:

| Setting | Kbd | Touch |
|---|---|---|
| `KEY_NAV_SIZE_PERCENT` (keyboard size) | **—** | ✓ |
| `KEY_NAV_KEY_OPACITY_PERCENT` | **—** | ✓ |
| `KEY_NAV_PANEL_OPACITY_PERCENT` | **—** | ✓ |
| `KEY_NAV_THEME` | **—** | ✓ |
| `KEY_NAV_TRANSPARENCY_MODE` | **—** | ✓ |
| `KEY_NAV_HIDE_DRAG_HANDLE` | **—** | ✓ |
| `KEY_NAV_LIVE_DRAG` | **—** | ✓ |

(`KEY_NAVIGATION_MODE_ENABLED` / `KEY_NAVIGATION_OVERLAY_REQUESTED` are
enable/permission plumbing tied to the accessibility-service flow — likely
excluded deliberately.)

### developer — OUT OF SCOPE

Developer settings are accessed only through System Settings by design
(per Cliff, 2026-08-03) — excluded from the parity inventory. (A registry
`developer` page exists and is keyboard-reachable behind the reveal
easter-egg; its content is not held to parity.)

### backup_info — info-only on kbd; touch BackupRestoreActivity holds the
backup/restore **actions** (file I/O — intentionally touch-only).

## Discrepancy summary

(Developer settings excluded — System Settings only, by design.)

**Missing from touch System Settings (4):**
1. `KEY_HEADTRACKING_CORRECTION_BEEP` (head tracking)
2. `KEY_HEADTRACKING_CORRECTION_FLASH_RED` (head tracking)
3. `KEY_EXTERNAL_SWITCH_STUCK_TIMEOUT_SEC` (two switch)
4. `KEY_TURKISH_AZERI_CASE_OVERRIDE` (vocabulary)

**Missing from Keyboard Settings / registry (10 + reachability bug):**
1-7. Navigation Mode appearance: size, key opacity, panel opacity, theme,
     transparency mode, hide drag handle, live drag
8. `KEY_TOUCH_SCREEN_SWITCH_SHOW_REGION_BORDER`
9. `KEY_DIRECTIONAL_SHOW_REGION_BORDER`
10. `KEY_JOYSTICK_ACCEPT_ANY` (device name/descriptor binding needs a
    hardware picker — may stay touch-only by design)
- **Bug:** `nav_mouse_joystick` never passes `filterInputMethodSubPages` —
  the mouse-joystick page is unreachable in Keyboard Settings even when
  Mouse Joystick is the primary input method.

**Placement mismatch (controllable on both, different page):**
- `KEY_KEYBOARD_HIGHLIGHT_TIMEOUT_SEC`: kbd single_switch vs touch two-switch
- `KEY_HEADTRACKING_DEBUG_OVERLAY` / `_DIAG_LOGS` / `_REARM_IN_FEEDBACK`:
  kbd head_tracking vs touch Developer

**Intentionally single-surface (recommend leaving):** Welcome Guide,
Emergency Reset (anti-lockout — must work when the keyboard doesn't),
backup/restore file operations, vocab module management + import/export,
langpack install (kbd has "Get more languages" launch action).

## Notes for the fix phase

- New touch rows for registry-backed keys need **no new strings** — labels
  and info prompts already exist as `sr_*` / `info_prompt_*` resources.
- New registry entries for touch-only keys (nav mode, region borders,
  accept-any) **do** need new `R.string` entries — all new
  labels/descriptions must go through `res/values/strings.xml` so they enter
  the translation debt (never hardcoded literals).
- Adding keys to the registry auto-surfaces them in Keyboard Settings; the
  touch side can either render its sub-screens from the registry (larger
  refactor, kills this class of drift) or add matching hand-built rows.
