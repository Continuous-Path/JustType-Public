# Input Methods

## "Switches"

- **Touch Screen Switch** — Full-screen overlay that collects `MotionEvents`. Can be used as one switch, two switches (tapping on left/right half), or for `Directional Selection`.
- External/physical buttons or game controller buttons can be used as switches. (These typically send `KeyEvents`)

## Core Input Methods

- **Head Tracking** — Camera-based; Works by receiving external events (via `BroadcastReceiver`) from JT's sister/companion app `HeadBoard` (HeadBoard's `AccessibilityService` allows users to navigate their device via an on-screen "cursor" controlled by head movement. Facial gestures and bluetooth switches are configured to tap/swipe)
- **Joystick** — Selects key via direction of external joystick input.
- **Mouse Joystick** — Like Joystick, but driven by a mouse / trackpad pointer instead of an external joystick. Pointer movement away from the resting center selects the key in that direction. Tuned via sensitivity (dp), resting/active zones, corner weighting, and exit/reengage delays.
- **Single-Switch Scanning** - Time-based scanning. The 8 keys are arranged in a row. Selects the highlighted key at the time of switch activation. The highlighted key shifts periodically, cycling from left to right. On switch activation, highlighted key is selected.
- **Two-Switch Selection** — 2 Switches: Left/green and Right/red. 8-key grid is split: left side is green, right is red. Selected side is split and the process repeats, until one is selected. (3 switch activations selects one key: 4/4 -> 2/2 -> 1/1)
- **Directional Selection** — Uses `Touch Screen Switch`. Selects key in the direction of a swipe.
- **Direct Selection** - The usual tap-to-select a key. Can be enabled in conjunction with another core input method, but only if Touch Screen Switch is disabled.

## Navigation Mode

A floating overlay keyboard — the same 8-key grid — that drives the **whole device** instead of typing into a text field. It runs as an Android `AccessibilityService` (`navigation/NavigationModeService`) and needs both that service **and** the "display over other apps" (overlay) permission enabled.

- Arrow keys move accessibility focus; other keys perform actions like Tap and Back (`NavKeyHandler` → `NavAction`).
- Accepts the same input methods as the typing keyboard (single/two switch, joystick, head tracking), routed through the Nav input surface.
- Can be minimized to a small movable button, with a volume-button combo as a failsafe to recover it.
- Switch input (keyboard keys, controller face buttons, and the d-pad) works on the Nav keyboard: keys/buttons arrive via the accessibility key-event filter, the d-pad via HAT motion events. The service must declare `canRequestFilterKeyEvents` for key events to be delivered.
- Joystick input *on the Nav keyboard specifically* requires Android 14+.
