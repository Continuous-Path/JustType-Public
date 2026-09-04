# Settings Parity Contract

Every user-facing setting must be controllable on **both** surfaces:

- **System Settings** — the touchscreen UI: `SettingsActivity` (renders the
  registry `main` page) + hand-built sub-screens (`InputMethodsActivity`,
  `Setup*Fragment`/`Setup*Activity`, `VocabularyManagementActivity`).
- **Keyboard Settings** — Settings Mode inside the IME (reached via the
  Navigation list-function), driven by `KeyboardSettingsController`, which
  renders **every `SettingsRegistry` page automatically**.

Established after the 2026-08-03 parity audit
(`docs/.plans/settings-parity/inventory.md`), which closed all gaps then
present.

## The rule

1. **Adding a setting to `SettingsRegistry` gives the keyboard side for
   free.** The touch side must be mirrored by hand: `main`-page entries
   render automatically in SettingsActivity; entries on any other page need
   a matching row in the corresponding hand-built screen.
2. **Adding a setting directly to a touch screen is never enough.** Add the
   registry entry too (or consciously list it as an exception below).
3. **All labels/descriptions come from `res/values/strings.xml`** — reuse
   the registry's `sr_*` / `info_prompt_*` strings on both surfaces; never
   hardcode literals. New strings become translation debt automatically.
4. Keep defaults identical: registry `defaultValue` = the fragment's read
   default = `SettingsDefaults`.

## Page ↔ touch-screen mapping

| Registry page | Touch screen |
|---|---|
| main | SettingsActivity (auto-rendered) |
| input_methods | InputMethodsActivity |
| head_tracking | SetupHeadTrackingFragment |
| joystick | SetupJoystickFragment |
| mouse_joystick | SetupMouseJoystickFragment |
| single_switch | SetupSingleSwitchFragment |
| two_switch | SetupTwoSwitchFragment |
| touch_switch | SetupTouchScreenSwitchFragment |
| direct_selection | SetupDirectSelectionActivity |
| directional_selection | SetupDirectionalSelectionActivity |
| vocabulary | VocabularyManagementActivity |
| navigation_mode | SetupNavigationModeFragment |

## Agreed exceptions (single-surface by design)

- **Developer settings** — System Settings only (per Cliff, 2026-08-03).
  The registry `developer` page exists behind the keyboard reveal
  easter-egg but is not held to parity.
- **Actions / flows, not settings**: Welcome Guide, Emergency Reset
  (anti-lockout — must work when the keyboard doesn't), backup/restore file
  operations, vocabulary module management + import/export, langpack
  install screens (keyboard offers launch actions instead).
- **Hardware pickers**: joystick device *binding* (name/descriptor) needs
  plugged-in device enumeration → touch-only. The `joystick_accept_any`
  toggle IS mirrored.
- **Enable/permission plumbing**: `navigation_mode_enabled`,
  `navigation_overlay_requested`, permission-request flags.

## Guardrails

- `SettingsRegistryDriftTest` — `parity-contract keys stay in the registry`
  locks the registry side of every mirrored key that has a hand-built touch
  row; `every registry page renders every key` catches renderer gaps.
- `KeyboardSettingsController.filterInputMethodSubPages` must list a
  `nav_*` entry for **every** input method (mouse joystick was once
  forgotten, making its page unreachable — fixed 2026-08-03).

## Audit recipe (occasional check-up)

1. Extract the registry inventory (keys per page) — e.g. grep
   `SettingsDef\.(Toggle|IntSlider|FloatSlider|Choice|KeyCapture)` in
   `SettingsRegistry.kt`.
2. For each touch screen in the table above, list its pref keys:
   `grep -oE '(C|Constants)\.(KEY|PREFS_KEY)_[A-Z0-9_]+' <file>` (watch for
   raw-string keys too).
3. Diff both directions; classify each mismatch as a real gap, a placement
   mismatch, or a new agreed exception (record it here).
4. Update the parity-contract test when settings are added to both surfaces.
