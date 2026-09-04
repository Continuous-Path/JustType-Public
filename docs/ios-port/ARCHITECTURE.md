# Cross-Platform Architecture (Android + iOS)

JT ships on Android (main codebase, this repo) and iOS (`JustType-iOS`, separate repo). Shared typing-engine logic lives in the `:jt-core` Kotlin Multiplatform module in this repo, published to iOS as the `JTCore` XCFramework. All iOS UI is Swift.

## Module map

```
Android repo                              iOS repo (JustType-iOS)
┌─────────────────────────────┐           ┌──────────────────────────────┐
│ :app  (Android IME + UI)    │           │ JustType (container, SwiftUI)│
│   JustTypeIME, ime/*, view/*│           │   Speech Board, settings,    │
│   NavigationModeService     │           │   vocab/phrases, onboarding  │
│   activities, TTS, backup   │           │ JustTypeKeyboard (ext, UIKit)│
│         │ depends on        │           │ JTShared (Swift pkg: keyboard│
│         ▼                   │           │   view, CoreBridge, settings │
│ :jt-core (KMP)  ────────────┼──────────▶│   store, provisioner)        │
│   commonMain: engine        │ XCFramework│        │ SPM binaryTarget    │
│   androidMain/iosMain seams │ + dict zip │        ▼                    │
└─────────────────────────────┘  (GitHub  │      JTCore                  │
                                  Release) └──────────────────────────────┘
```

## `:jt-core` contents (commonMain)

| Package | Contents |
|---|---|
| `logic` | WLD (trie disambiguation), WordDb/WordDbAccessor (over `JtSql`), WordCase, AutoCapReason, KeySequenceModel, SelectionListModel, GridStateModel, TextComposer (over `PlatformTextIO`) |
| `hierarchy` | SymbolTree, DiacriticTree, DiacriticDerivation, CaseMap, VariantLayout, AllSymbolsModeController, HierarchyLoader (over `AssetSource`) |
| `input` | ScanState, TwoSwitchState, JoystickState, HeadTrackingState, GamepadDirectionDetector |
| `lang` | LanguageRegistry, CanonicalLanguages, SpanishRegion, ClassMasks, PosEncoding |
| `settings` | SettingsDef sealed types, SettingsRegistry, defaults (per-def `platforms` field) |
| `headtracking` | Smoothing filters, pitch/yaw matrix math, dwell/hover, blendshape hysteresis (ported from HeadBoard; shared with HeadBoard-Android via AAR) |

Package names stay `org.continuouspath.justtype.*` (no import churn in `:app`).

## expect/actual seams (keep this list minimal)

| expect | androidMain | iosMain |
|---|---|---|
| `JtSql` | wraps `SQLiteDatabase` | SQLDelight `native-driver` used raw (no `.sq` codegen) |
| `AssetSource` | `AssetManager` | `NSBundle` |
| `JtFileSystem` | `java.io.File` | `NSFileManager` + App Group container |
| `JtLog` | `android.util.Log` | `os_log` |
| `EpochClock` | `System.currentTimeMillis` | `NSDate` |
| `ioDispatcher()` | `Dispatchers.IO` | native IO dispatcher |

## Behavior invariants (must be identical on both platforms)

- Case-learning adaptive margin (=2) and CaseType mutation rules.
- freqClass bucketing: 14 tiers (rawFreq ≥40000→1 … <9→14).
- Trie BFS termination: baseSearchDepth / searchExpansionDepth / maxExaminedNodes.
- Diacritic variant → base-key mapping and diacritic tree contents.
- Candidate ranking: freqClass → useCount → lastUseTime → case preference.

Enforced by golden fixtures (`jt-core/src/commonTest/resources/golden/*.json`) run in three harnesses: JVM commonTest, iosSimulatorArm64 commonTest, and the iOS repo's XCTest through the shipped framework.

## iOS platform notes

- **Text I/O**: `UITextDocumentProxy` has no composing region → iOS uses commit-as-you-go with a shadow buffer (`TextProxyController` implements `PlatformTextIO`). Divergences are cataloged in the iOS repo's `docs/BEHAVIOR-DELTAS.md`.
- **Extension memory**: ~60-70MB jetsam cap; steady-state budget ~25-35MB. One language trie loaded at a time.
- **Dictionaries**: same `BuildWordDbTask` SQLite output, shipped as `dictionaries-vN.zip` on core releases; container app provisions App Group copies; extension reads/writes only App Group.

## Never on iOS (do not re-litigate)

- **Nav mode** — iOS has no third-party AccessibilityService/overlay equivalent.
- **Cross-app head-tracking cursor** (HeadBoard's system-cursor mode) — no cross-app input injection on iOS.
- **Camera in the keyboard extension** — prohibited by iOS.

Head tracking on iOS lives in the container app's Speech Board via ARKit `ARFaceAnchor` (same 52-blendshape vocabulary MediaPipe uses) feeding `headtracking` + `HeadTrackingState`. OS-level Switch Control / head tracking drives the keyboard extension in other apps.

## Versioning

`:jt-core` releases as `core-vX.Y.Z` tags (independent of app versionCode): MAJOR = breaking API/schema; MINOR = behavior change (golden fixtures updated); PATCH = fixtures unchanged. Release assets: `JTCore.xcframework.zip` + SPM checksum + `dictionaries-vN.zip`.

See [PORTING-WORKFLOW.md](./PORTING-WORKFLOW.md) for how changes flow to iOS.
