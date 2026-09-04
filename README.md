# JustType (JT)

JustType is an Android keyboard / **IME (Input Method Editor)** for users with motor or
communication disabilities. The main layout uses an 8-key ambiguous grid (3×3, center
empty) plus word prediction to maximize keystroke efficiency, and supports a range of
alternative input methods (direct selection, directional selection, touch screen switch,
single switch, two switch, joystick, mouse joystick, head tracking). A separate Navigation
Mode drives the whole device with the same 8-key grid via an accessibility service.

JT is **pre-launch** — preparing for its first batch of test users. It is not yet shipping
to real users.

For project conventions, planning workflow, and AI-agent guidelines, see [AGENTS.md](./AGENTS.md).
For a deeper architecture tour, see [docs/architecture-cheatsheet.md](./docs/architecture-cheatsheet.md)
and [docs/InputMethods.md](./docs/InputMethods.md).

---

## Project Layout

```
.
├── app/                        Android app module (the IME + activities + tests)
│   ├── src/main/java/...       Production sources
│   ├── src/test/java/...       Robolectric unit tests
│   ├── src/androidTest/java/.. Instrumented (on-device) tests
│   └── src/main/res/layout/    XML layouts (`ime_input.xml` is the IME root)
├── buildSrc/                   Custom Gradle tasks (BuildEnglishDbTask)
├── config/detekt/              Detekt config + baseline
├── docs/                       Architecture docs, plans (`.plans/`), retrospectives
├── jt                          Project gradle wrapper (./jt — flock+cleanup)
├── tools/                      Repo scripts (e.g. feature catalog builder)
├── build.gradle                Root build (Spotless config)
├── app/build.gradle            App build (Android, detekt, jacoco, English DB task)
└── gradle/libs.versions.toml   Version catalog
```

---

## Build Commands

All gradle work goes through **`./jt`** (or `/jt` from a Claude session).
The wrapper provides flock-protected, orphan-cleaning gradle invocations — direct
`./gradlew` calls are blocked by a PreToolUse hook in Claude and discouraged for humans.

Full reference: **[`docs/jt.md`](./docs/jt.md)**.

### Quickstart

```sh
./jt build           # build debug APK
./jt install         # build + install on connected device
./jt test            # all debug unit tests
./jt test "*Foo*"    # filtered unit tests
./jt check           # pre-commit gate (tests + spotless + detekt)
./jt help            # full subcommand reference
```

### Common subcommands

| Subcommand | What it runs |
|---|---|
| `test [pattern]` | `:app:testDebugUnitTest`, optionally filtered |
| `detekt` | `:app:detekt` |
| `spotless` / `spotless-fix` | Formatting check / auto-fix |
| `lint` / `lint-fix` | Android Lint / auto-fix safe suggestions |
| `jacoco` | Tests + JaCoCo HTML/XML coverage |
| `build` / `build-release` | Debug / release APK |
| `install` / `uninstall` | Install / uninstall debug APK |
| **`check`** | Pre-commit gate (tests + spotless + detekt in one call) |
| **`check-full`** | `check` + lint + jacoco |
| `stop` | Stop gradle daemon + sweep orphan workers |
| `raw -- <args>` | Escape hatch — pass args directly to `./gradlew` |

Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`.
Coverage HTML: `app/build/reports/jacoco/jacocoTestReport/html/index.html`.
Coverage XML: `app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml` (used by plans for coverage gates).

### After installing

You must **enable JustType in Android Settings → System → Languages & input → On-screen
keyboard** before it appears as an IME.

### Pre-commit checklist (modernization)

```sh
./jt check
```

`check` runs `:app:testDebugUnitTest`, `spotlessKotlinCheck`, and `:app:detekt` in a single
gradle invocation — gradle parallelizes them internally. If you're claiming coverage in a
plan, run `check-full` instead.

If Spotless flags carry-over violations in files unrelated to your change, **do not bundle
the fixes into your commit** — the Phase 3 policy is to project-wide format only after the
phase finishes. Confirm your *new* files are clean and leave the rest.

### Auxiliary scripts

| Command | Purpose |
|---|---|
| `tools/.venv/bin/python tools/build_feature_catalog.py` | Build the JustType feature catalog spreadsheet (`docs/JustType_Feature_Catalog.xlsx`) from `SETTINGS_INFO_PROMPTS.txt` and `docs/Phase1SettingsWork.md`. Requires the local Python venv at `tools/.venv/` with `openpyxl`. |

---

## Build Configuration Quick Reference

| Setting | Value |
|---|---|
| `compileSdk` / `targetSdk` | 34 |
| `minSdk` | 26 (API 26 = Android 8.0 Oreo) |
| Java / Kotlin target | JVM 11 |
| Gradle | 8.10.2 via `gradle/wrapper/` |
| AGP | 8.5.2 (see `gradle/libs.versions.toml`) |
| Kotlin | 1.9.24 |
| Test runner | JUnit 4 + Robolectric 4.12.2 (unit) + AndroidJUnitRunner (instrumented) |
| Test libs | Truth, Mockito-Kotlin 5.x, kotlinx-coroutines-test, Turbine |

Release builds run R8 (minification + resource shrinking). A three-tier debug-logging
system spans the `debug`, `release`, `beta`, and `internal` build types — see the
`buildTypes` block in `app/build.gradle`. Release signs with the debug key unless a
`justtype-release.jks` upgrade key is present (`docs/release.md`).

---

## Plans Directory

All planning docs live under `docs/.plans/`. Do **not** write new plans to
`.claude/plans/` — that's a legacy location.

## License

JustType is licensed under the [Apache License 2.0](./LICENSE).

**Language data is licensed separately.** The word lists, region tags, generated
`*Db.db` assets, and published language packs are derived from CC BY-SA sources
(hermitdave/FrequencyWords, Wikipedia, Wiktionary via Kaikki) and are distributed
under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). See
[NOTICE](./NOTICE) for the full attribution list, and
[docs/.plans/language-resources/plan.md](./docs/.plans/language-resources/plan.md)
for the sourcing policy governing new language data.
