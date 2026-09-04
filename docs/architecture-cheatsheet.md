# JustType — Architecture Cheatsheet

Quick-reference for navigating the codebase. All paths relative to
`app/src/main/java/org/continuouspath/justtype/` unless noted.

---

## Directory Structure

```text
app/src/main/
├── java/org/continuouspath/justtype/
│   ├── (root)                      # JustTypeIME + cross-cutting singletons & input-method state machines
│   ├── activity/                   # Settings/Setup Activities, Setup* Fragments, helpers, event interceptors
│   ├── data/                       # Data repositories (PhraseRepository)
│   ├── ime/                        # IME orchestration: subsystems, controllers, bridges, callbacks (~40 files)
│   ├── input/                      # Input plumbing: capture gate, head/switch buses, surfaces
│   ├── layout/                     # Keyboard layout controllers (JT grid / Scan)
│   ├── logging/                    # File-based debug logging + crash/exception reporting
│   ├── logic/                      # Word prediction (WLD/WordDb), core UI logic (JTUI), case handling
│   ├── navigation/                 # Navigation Mode: a11y service + overlay kbd (+ pure engine/ tier)
│   ├── receiver/                   # Broadcast receivers (external input, clear-highlights, package update)
│   ├── settings/                   # Settings registry/defs/defaults, DataStore repo, renderers
│   ├── ui/                         # AccessiblePrompt (system-overlay message)
│   ├── utils/                      # Small shared helpers (AtomicFile)
│   ├── view/                       # Custom Views (SquareButton, KeyHistoryView, …)
│   └── welcome/                    # First-run Welcome Guide (host + step Fragments) + input-method chooser
├── res/
│   ├── layout/                     # XML layouts (ime_input.xml is the keyboard root)
│   ├── drawable/                   # Button backgrounds, icons
│   ├── values/                     # strings, colors, dimens, styles, attrs
│   ├── values-es/ values-sw/       # Spanish / Swahili strings (English-first; translations lag)
│   ├── values-sw600dp/ -sw720dp/   # Tablet dimens
│   └── xml/                        # method.xml (IME config), accessibility service config, locales_config.xml
├── assets/databases/EnglishDb.db   # Prebuilt word DB (do not read — large binary)
├── db/EnglishWordsRaw.txt          # Word DB source (do not read — large)
└── AndroidManifest.xml
```

Other top-level modules:

- `buildSrc/` — `BuildEnglishDbTask.kt`, compiles the word DB from `app/src/main/db/` (wired to `preBuild`).

---

## Core Classes

### IME service + orchestration

`JustTypeIME.kt` (root) is the central `InputMethodService` and entry point, but most
orchestration is delegated to collaborators in `ime/` (the former "God class" has been
decomposed). Roughly by role:

| Concern | Key files (`ime/`) |
| --- | --- |
| Text editing / cursor / undo | `ImeTextController.kt`, `ImeTextCallbacks(Impl).kt`, `TextUtils.kt` |
| Input-method subsystems | `HeadTrackingSubsystem.kt`, `JoystickSubsystem.kt`, `MouseJoystickSubsystem.kt`, `ScanSubsystem.kt`, `TwoSwitchSubsystem.kt`, `ExternalSwitchHandler.kt` |
| Feedback | `KeyFeedbackController.kt` (beep + flash + haptic), `TtsController.kt` |
| View bridges | `ViewBridgeCoordinator.kt`, `HighlightBridge.kt`, `JoystickViewBridge.kt`, `TwoSwitchViewBridge.kt`, `HeadTrackingViewBridge.kt` |
| Settings overlay (on-keyboard) | `SettingsOverlayController.kt` + callbacks |
| Phrases | `PhraseFlowController.kt` + callbacks |
| Startup / state / prefs | `StartupManager.kt`, `IMEState.kt`, `PreferenceCoordinator.kt`, `UiUpdateHandler.kt`, `OverlayCoordinator.kt` |
| Broadcast IPC | `BroadcastBridge.kt` + callbacks |

State machines (root package): `HeadTrackingState.kt`, `JoystickState.kt`, `ScanState.kt`,
`TwoSwitchState.kt`. Direction mapping: `GamepadDirectionDetector.kt`. Full-screen touch
capture: `TouchDetectionOverlay.kt`.

### Layout system (`layout/`)

```
LayoutManager  (delegates to active controller)
├── JTLayoutController    — 3x3 grid, 8 keys around center + side selection list
└── ScanLayoutController  — row-based scanning (1x8 small or 2x4 large)
        both implement KeyboardLayoutController, extend BaseLayoutController
                                  (shared highlight / flash / resting-background capture)
```

### Word prediction & data (`logic/`, `data/`)

| File | Role |
| --- | --- |
| `logic/WLD.kt` | Trie-based word lookup / disambiguation engine |
| `logic/WordDb.kt`, `logic/WordDbAccessor.kt` | SQLite word DB (frequency, class masks, usage stats) |
| `logic/JTUI.kt` | Core UI logic — key grids, selection list, undo; `LayoutMode` (Alphabetical/Optimized) |
| `logic/WordCase.kt`, `logic/AutoCapReason.kt` | Case forms + auto-capitalization triggers |
| `data/PhraseRepository.kt` | JSON-backed phrase abbreviation storage |

### Settings system (`settings/`)

```
SettingsRegistry  (SSOT — every user-facing setting defined here)
    → SettingsDef (sealed: Toggle, IntSlider, FloatSlider, Choice, SubPage, SectionHeader, InfoText, KeyCapture, Action)
    → SettingsDefaults (default values + safe-state helpers)
    → SettingsRenderer            — builds the touch settings UI (Activities)
    → KeyboardSettingsController  — on-keyboard settings overlay, operable by the active input method
SettingsRepository  — DataStore-backed store with a single-process in-memory cache (writes
                      notify listeners synchronously); PrefsSidecar mirrors it for crash recovery;
                      RegistryAwareRepo resolves defaults from the registry.
```

`Constants.kt` (root) is the registry of all preference keys + config constants.

### Navigation Mode (`navigation/`)

Drives the whole device with the 8-key grid via an `AccessibilityService`.

Two tiers: `engine/` is pure Kotlin (no `android.*` imports, plain-JUnit tested); everything
else is a thin Android adapter or the service itself.

| File | Role |
| --- | --- |
| `NavigationModeService.kt` | The `AccessibilityService`; wiring, lifecycle, overlay visibility |
| `engine/` | Pure decision layer: `CandidatePolicy`, `FocusGeometry`, `ScrollPlanner`, `SelectionMatcher`, `WindowStatePolicy`, `SwitchRoles`, `NavSettingsRouter`, `GesturePaths`/`DragPaths`, value types (`NavBounds`, `NavTree`, `ScrollReach`, `DragStep`) |
| `NavPageState.kt`, `AvailabilityPolicy.kt` | Page/availability/reach state machine + per-key grey-out derivation |
| `NodeSnapshotter.kt`, `NodeActionPerformer.kt` | Live a11y-tree capture into engine snapshots; node action gate |
| `GestureDispatcher.kt`, `DragController.kt`, `LiveDragSession.kt`, `SelectCursorController.kt` | Gesture injection + drag/pick-up sessions |
| `NavigationOverlayHost.kt`, `NavMinimizedOverlay.kt` | Floating keyboard window + minimized button |
| `NavTouchOverlay.kt`, `NavigationFocusOverlay.kt`, `DragCursorOverlay.kt`, `ScrollLengthArrowOverlay.kt` | Touch capture, focus ring, drag crosshair, reach arrow |
| `NavKeyHandler.kt`, `NavAction.kt` | Key → accessibility action (move focus, tap, Back, …); key maps + page/glyph spec |
| `Nav*Callbacks.kt`, `NavKeyActivationSink.kt`, `NavInputSurface.kt` | Routes switch/joystick/scan/head-tracking input into Nav |
| `VolumeComboDetector.kt`, `HatSwitchEdgeDetector.kt`, `NavScheduler.kt` | Volume-button failsafe, d-pad/hat edges, token-keyed deferred work |

### Input plumbing (`input/`)

`InputCaptureGate.kt`, `InputSurface.kt`, `HeadInputBus.kt`, `HatSwitchCodes.kt`,
`ExitDirection.kt` — shared abstractions so the IME keyboard and the Nav overlay can both
consume external input.

### Custom Views (`view/`) & UI (`ui/`)

| File | Role |
| --- | --- |
| `view/SquareButton.kt` | Key button: 9-cell grid label, centered label/icon, next-letter hints, state-aware text colour |
| `view/SquareTextView.kt`, `view/SquareGridLayout.kt`, `view/MaxHeightTextView.kt` | Aspect-ratio / sizing helpers |
| `view/KeyHistoryView.kt` | Recent key presses as mini 3x3 grids |
| `ui/AccessiblePrompt.kt` | System-overlay message dismissable via the UnDo key |

### Activities, Fragments & helpers (`activity/`, `welcome/`)

- **Activities**: `SettingsActivity`, `InputMethodsActivity`, `SetupHostActivity` (hosts the
  Setup Fragments), `SetupDirectSelectionActivity`, `SetupDirectionalSelectionActivity`,
  `DeveloperSettingsActivity`, `BackupRestoreActivity`, vocabulary screens
  (`VocabularyManagement`, `ManageVocabularies`, `VocabularyImport`, `ExportVocabUsage`),
  `PhraseOverlayActivity`.
- **Setup Fragments** (hosted by `SetupHostActivity`): `SetupHeadTrackingFragment`,
  `SetupJoystickFragment`, `SetupMouseJoystickFragment`, `SetupSingleSwitchFragment`,
  `SetupTwoSwitchFragment`, `SetupTouchScreenSwitchFragment`, `SetupNavigationModeFragment`.
- **Welcome Guide** (`welcome/`): `WelcomeGuideActivity` + `WelcomeStepIntro/EnableIme/Done`
  Fragments; `InputMethodChooser` recommends a method on first run.
- **Helpers**: `SettingsInfoHelper`, `SettingsSpeechController`, `KeyEventInterceptor`,
  `MotionEventInterceptor`.

### Utilities & singletons (root, `logging/`)

`BackupManager`, `LocaleHelper`, `ClassMetadataStore`, `ClassMasks`, `PosEncoding`,
`VocabImportHelper`, `CrashHandler`, `utils/AtomicFile`; logging via `logging/DebugLogging.kt`,
`ExceptionLogWriter`, `ExceptionReporter`, `DebugLogShareHelper`.

### Broadcast receivers (`receiver/`)

`ExternalInputReceiver` (joystick/head-tracking input), `ClearHighlightsReceiver` (HeadBoard
clear-highlights), `PackageUpdateReceiver` (reinit on app update/reinstall).

---

## Architecture Patterns

- **No DI framework** — dependencies are manually instantiated and passed.
- **Activities + Fragments** — top-level screens are Activities; the per-method Setup screens
  and the Welcome Guide use Fragments (Setup ones hosted by `SetupHostActivity`).
- **DataStore-backed settings** — config lives in `SettingsRepository` (Jetpack DataStore +
  a shared in-memory cache + `PrefsSidecar` backup); `Constants.kt` is the key registry.
- **Broadcast IPC** — IME, Nav service, and activities coordinate via broadcast intents
  (`receiver/`, `ime/BroadcastBridge`).
- **Strategy pattern** — `KeyboardLayoutController` with JT/Scan implementations.
- **State machines** — `HeadTrackingState`, `JoystickState`, `ScanState`, `TwoSwitchState`.
- **Object singletons** — `BackupManager`, `LocaleHelper`, `ClassMetadataStore`, `DebugLogger`.

---

## Design Decisions

### Pull-in always records a Select — even for the default candidate

Tap-to-pull-in (loading a word at the cursor into the active key sequence) **always** records a
Select activation in the key-sequence buffer, even when the pulled-in word is the default (first)
candidate. Non-default candidates add the additional Selects needed to reach them.

**Trade-off vs UnDo symmetry.** When UnDo deletes backward through a finalized word, the leading
autospace is treated as "deleting the implicit Select that committed the prior word" — no Select
appears in the deletion sequence. Forward typing of an N-letter word that autospace-finalizes
mirrors this. Pull-in deliberately does *not* preserve this symmetry — pulling in the default word
records a Select even though typing it forward to autospace-finalize would not have.

**Why consistency over symmetry.** Pull-in has a clean atomic semantic: *"tap a word → land in
exactly the state you would be in if you had typed it and pressed Select enough times to select
it."* The forward/backward operation-level duality is preserved: N letters + 1 Select forward ≡ 1
Select-deletion + N letter-deletions backward via UnDo.

**Code.** `ime/ImeTextController.runPullInFlow(...)` and its callers; Select recording happens in
JTUI's pull-in replay.

---

## Key XML Layouts

| File | Role |
| --- | --- |
| `layout/ime_input.xml` | Root keyboard container — includes all sub-layouts |
| `layout/include_jt_layout.xml`, `include_jt_key_grid.xml` | JT 3x3 grid + selection list |
| `layout/include_scan_layout.xml`, `include_scan_button_pool.xml` | Scan layout + button pool |
| `layout/include_settings_overlay.xml` | On-keyboard settings overlay |
| `layout/include_phrase_overlay.xml` | Phrase editor overlay |
| `layout/overlay_navigation_mode.xml` | Navigation Mode floating keyboard |
| `layout/activity_welcome_guide.xml`, `fragment_welcome_step_*.xml` | Welcome Guide |
| `layout/row_setting_*.xml` | Settings rows rendered by `SettingsRenderer` |

---

## Build

- **SDK**: compile 34, **min 26**, target 34. **Java/Kotlin target**: JVM 11.
- **Build types**: `debug`, `release` (R8 minify + resource shrink), `beta`, `internal`
  (three-tier debug-logging system — see `app/build.gradle`). Release signs with the debug key
  unless `justtype-release.jks` is present (see `docs/release.md`).
- **Build features**: View Binding + buildConfig enabled.
- **Key deps**: androidx core-ktx, appcompat, material, constraintlayout, documentfile,
  **datastore-preferences**, kotlinx-coroutines, kotlinx-serialization-json.
- **Tests**: JUnit4 + Robolectric (unit) + AndroidJUnitRunner (instrumented); Truth,
  Mockito-Kotlin, kotlinx-coroutines-test, Turbine.
- **Static analysis / format**: detekt (`:app`), Spotless/ktlint (root), Android Lint (baselined).
- Versions live in `gradle/libs.versions.toml`; Gradle via the wrapper. All gradle work goes
  through `./jt` (see `docs/jt.md`).
