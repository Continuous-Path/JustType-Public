# On-Device Test Checklist

A short per-feature pass for behavior automation can't reach (real windows, sensors,
capture, companion apps). Run the automated smoke first, then the sections touched by the
change under test; run everything before a release or user-batch handoff.

## Setup

- [ ] `./jt install -fp` — fresh install, permissions granted (overlay + IME + accessibility)
- [ ] `./jt test-instrumented` — automated smoke: IME binds + input view, commitText
      round-trip, NavigationModeService starts/stops (restores device IME/a11y state)

## Keyboard core

- [ ] Keyboard opens in a real editor (messaging app, browser URL bar)
- [ ] Type a word via the 8-key layout; prediction disambiguates; candidate select commits
- [ ] Delete char / delete word / undo behave
- [ ] Spell mode, symbols, and numeric pages reachable and type correctly
- [ ] Settings Mode opens from the keyboard; a slider change applies live to the IME
- [ ] Landscape: keyboard scales (known issue watch: window filling display / grid cut off)
- [ ] Rotate mid-session: layout survives, no stuck overlay

## Input methods (each one that the change touches)

- [ ] Direct / directional selection: tap routing matches the selected mode
- [ ] Touch-screen switch: region taps register per configured mode/keys; region border toggle
- [ ] Single switch: scan highlight cycles; switch press selects
- [ ] Two switch: advance + select; no stuck timers after backgrounding
- [ ] Joystick (gamepad): setup capture finds device; zones activate keys
- [ ] Mouse Joystick: cursor appears, dwell activates keys, barrier keeps hover on-screen,
      screen-edge behavior sane; cursor hides while typing
- [ ] Head tracking: HeadBoard streams only while keyboard is open (gate); stall → fallback
      notice; recovery resumes

## Navigation mode

- [ ] Overlay appears on enable; keys act per boss-spec layout
- [ ] Move/scroll/drag in a real app (e.g., Keep list reorder for live drag)
- [ ] Reach (scroll size) changes take effect
- [ ] Unavailable keys grey out and recover on selection/scroll change
- [ ] Theme/opacity/size settings apply live

## System behavior

- [ ] IME survives process kill (auto-rebind); Nav service state after crash is reported
- [ ] Crash report email path works; report contains no typed text
- [ ] TTS: engine/voice enumeration and speech on a device with a non-default engine
- [ ] Backup/restore round-trip on a device with real data
- [ ] Low-end / older device (API 26-range): startup time and typing latency acceptable
