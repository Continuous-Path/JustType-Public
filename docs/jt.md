# `jt` — JustType build wrapper

A wrapper around `./gradlew` that is the **single sanctioned entry point**
for all gradle work on this project. It provides:

- **Project-local flock.** Two parallel sessions (Claude + human, or two
  Claude sessions) can't both launch gradle invocations simultaneously —
  the second waits for the first. Prevents the daemon-contention and
  worker-multiplication that caused CPU-pinning incidents in this project.
- **Post-acquire worker cleanup.** Once the lock is held, any
  `GradleWorkerMain` JVM still alive is by definition a leak — kills them
  all. Covers true orphans (PPID=1) and workers wedged under a still-alive
  daemon. Safe because the lock guarantees no legitimate run is in flight.
- **Hang watchdog.** Each subcommand has a wall-clock budget (e.g.
  `test`=5m, `check`=10m, `raw`=30m). If gradle exceeds it the script
  captures `jstack`/`sample` output for each live `GradleWorkerMain` to
  `.gradle/jt-watchdog/`, SIGKILLs the gradle tree, and surfaces a loud
  warning. Override per-invocation: `JT_TIMEOUT=900 ./jt test`.
- **Trap-on-exit cleanup.** The wrapper's own gradle child is terminated
  on exit (success, failure, Ctrl-C, hangup).
- **Convenience subcommands** so you don't have to remember
  `:app:testDebugUnitTest` vs `testDebugUnitTest`, or `spotlessKotlinCheck`
  vs `:app:spotlessKotlinCheck`.

A `PreToolUse` hook (`.claude/hooks/block-bare-gradle.sh`) rejects bare
`./gradlew` / `gradle` / `gradlew` invocations from Claude. The wrapper is
the only path.

## Quick reference

| Subcommand | What it runs | Notes |
|---|---|---|
| `test [pattern]` | `:app:testDebugUnitTest` | Optional `--tests "<pattern>"` filter |
| `test-release` | `:app:testReleaseUnitTest` | Release-variant unit tests |
| `test-instrumented` | `:app:connectedDebugAndroidTest` | Needs a connected device |
| `detekt` | `:app:detekt` + `:app:detektDebug` | Static analysis; the debug task adds type-resolution rules |
| `detekt-baseline` | `:app:detektBaseline` + all four variant baseline tasks | Regenerate all five baselines (advisory) |
| `spotless` | `spotlessKotlinCheck` | Formatting check (root task) |
| `spotless-fix` | `spotlessApply` | Apply formatting fixes |
| `lint` | `:app:lint` | Android Lint |
| `lint-fix` | `:app:lintFix` | Lint + auto-apply safe fixes |
| `jacoco` | `:app:jacocoTestReport` + `:app:jacocoCoverageVerification` | Tests + HTML/XML coverage reports + line-coverage floor |
| `build` | `:app:assembleDebug` | Debug APK |
| `build-release` | `:app:assembleRelease` | Release APK (R8 + shrink + release signing — see [release.md](release.md)) |
| `build-beta` | `:app:assembleBeta` | Beta APK (release variant, `versionName` suffixed `-beta`) |
| `install` | `:app:installDebug` | Build + install on connected device |
| `install-release` | `:app:installRelease` | Build + install release APK on connected device |
| `install-beta` | `:app:installBeta` | Build + install beta APK on connected device |
| `uninstall` | `:app:uninstallDebug` | |
| `signing-setup [ks]` | keytool + Keychain (no gradle) | One-time per machine: install/generate the release keystore, store its password in the macOS Keychain — see [release.md](release.md) |
| `signing-status` | keytool (no gradle) | Keystore + cert fingerprint/expiry + signed/unsigned verdict |
| `clean` | `clean` | Delete `build/` |
| `english-db` | `:app:buildEnglishDb` | Rebuild SQLite asset |
| **`check`** | tests + spotless + detekt (both tasks) | Pre-commit gate, single gradle call; stamps `.gradle/jt-check-stamp` on success |
| **`check-full`** | check + lint + jacoco (report + coverage floor) | Bigger gate for substep closure; also stamps |
| **`report <kind>`** | Parse last gradle output → terse summary | Kinds: `test`, `detekt`, `spotless`, `lint`, `jacoco`. Auto-runs after failure (jacoco on success too). |
| `tasks [pattern]` | `tasks --all \| grep <pattern>` | Task discovery |
| `stop` | `./gradlew --stop` + orphan sweep | Use when daemons hang |
| `clean-procs` | Orphan sweep without `--stop` | When daemon's still alive but workers are stuck |
| `raw -- <args>` | Pass args straight to `./gradlew` | Escape hatch — still flock-protected |
| `help` | Print built-in help | Same as no args |

## Common workflows

### Reading failures (AI-friendly summaries)

When `./jt test`, `./jt detekt`, `./jt spotless`, or `./jt lint` fails,
the wrapper automatically appends a terse, greppable summary at the
bottom of the gradle output. `./jt jacoco` also auto-prints its coverage
rollup on success.

| Subcommand | Report parser | What it surfaces |
|---|---|---|
| `./jt test` | `utils/jt/report_test.py` | Test class > method, file:line, error message |
| `./jt detekt` | `utils/jt/report_detekt.py` | Per-file issues grouped: line, rule, message |
| `./jt spotless` | `utils/jt/report_spotless.py` | File list + per-file diff line count |
| `./jt lint` | `utils/jt/report_lint.py` | Per-severity issues: file:line, id, message |
| `./jt jacoco` | `utils/jt/report_jacoco.py` | Per-package coverage, sorted low → high |

Example test-failure summary:

```
──── ./jt report test ────
Test results: 804 total, 1 failed, 0 skipped, 803 passed (12.4s)

FAILED:
  SetupJoystickFragmentTest > fragment inflates and is attached to the host
    SetupJoystickFragmentTest.kt:40
    expected: 2 but was : 1
```

Example detekt summary:

```
──── ./jt report detekt ────
Detekt: 15 issues across 7 files

app/src/main/java/com/justtype/nativeapp/activity/InputMethodsActivity.kt
  77: LongMethod — The function onCreate is too long (239). Max is 200.

app/src/main/java/com/justtype/nativeapp/activity/VocabularyManagementActivity.kt
  743: CyclomaticComplexMethod — renderVocabListLocked has complexity 55 (max 30)
```

Each report has flags for fuller output:

```sh
./jt report test --verbose      # include full stack traces
./jt report test --all          # show every failure (default cap: 20)
./jt report detekt --all        # show every issue (default cap: 30)
./jt report spotless --verbose  # include unified-diff blocks
./jt report lint --errors-only  # only Error severity
./jt report lint --all          # include Information severity
./jt report jacoco --all        # also list every class
./jt report jacoco --package org.continuouspath.justtype.ime  # filter
```

The auto-report fires only on failure (jacoco is the exception — coverage
numbers are the whole point so they always print). Call `./jt report
<kind>` explicitly to re-print without re-running.

All parsers are stdlib-only Python — no venv, no extra deps.

#### Spotless caveat

Spotless writes violations to gradle's stdout, not to an on-disk report.
The wrapper captures that output to `.gradle/jt-last-spotless.log` and
the parser reads from there. Running spotless outside the wrapper means
no log is captured and the report subcommand will say so.

#### Lint XML caveat

AGP's lint task emits HTML by default; XML is opt-in. The build is
already configured (`xmlReport = true` in `app/build.gradle`) so this
should Just Work. If lint says "no report found", check that
`xmlReport = true` is still in the lint block.

### Pre-commit gate

```sh
./jt check
```

Runs tests + spotless + detekt (plain and type-resolution) in a single
gradle invocation. Gradle parallelizes the tasks internally where safe
(much faster than calling them in separate invocations, and safer than
two terminals running them in parallel).

### Filtered tests during development

```sh
./jt test "*SetupHostActivityTest*"
./jt test "org.continuouspath.justtype.ime.*"
```

The pattern is passed to gradle's `--tests` flag. Glob support: `*` and
prefix/suffix matching.

### When tests cascade-fail due to BroadcastBridgeCallbacksImplTest

The Mockito init flake in that test (documented in memory) sometimes
cascades 260+ failures through its JVM fork. If that happens, the wrapper
itself is fine — the flake is in the test code, not the gradle layer.
Re-run with the same command. If it persists, run `./jt
stop` to reset the daemon, then try again.

### Daemons or workers stuck

```sh
./jt stop          # cleanly stops daemon + orphan sweep
./jt clean-procs   # orphan sweep only (daemon stays)
```

The flock is at `.gradle/jt.lock` (project-local). If you ever need
to manually release it, just delete the file — but only when you're
certain no gradle is actually running.

### Escape hatch

```sh
./jt raw -- :app:tasks --all --console=verbose
./jt raw -- :app:dependencies --configuration debugRuntimeClasspath
```

Everything after `--` is passed to `./gradlew`. Still goes through the
flock + cleanup machinery, so this is safe to use freely.

## Implementation notes

- **Lock path:** `${PROJECT_ROOT}/.gradle/jt.lock` (project-local;
  multiple repos don't share locks).
- **Lock timeout:** 10 minutes. If you wait longer, something's hung.
- **Worker cleanup heuristic:** `GradleWorkerMain` JVMs whose PPID is `1`
  (orphaned, parent dead) get a SIGTERM, then SIGKILL after 1 second if
  they didn't honor it. The daemon (PPID != 1) is never touched by
  cleanup.
- **`jt check` task ordering:** `:app:testDebugUnitTest spotlessKotlinCheck
  :app:detekt :app:detektDebug`. Gradle parallelizes where it can. If a
  downstream tool fails, you'll still see the other results in the same run.

## When to call gradle directly

You shouldn't. But if you must (e.g. an interactive Gradle wizard that
the wrapper doesn't pass through cleanly):

1. Stop other gradle work first: `./jt stop`.
2. Run your direct invocation in a fresh terminal.
3. Be aware: the hook will reject this from inside Claude. From a human
   terminal it's allowed but you'll be flying without the flock or
   cleanup.

For Claude sessions: this is forbidden. Use `raw --` instead.

## Commit & push gates

Two enforcement layers keep unchecked code off `dev` (and CI minutes down):

1. **Claude Code agents** — the tracked `.claude/settings.json` wires a `PreToolUse`
   hook (`require-check-before-commit.sh`): `git commit` is rejected unless a
   `./jt check` finished AFTER the newest staged code file changed (via
   `.gradle/jt-check-stamp`). Docs-only commits (`docs/`, `*.md`, `.claude/`,
   `.github/`) are not gated. Emergency bypass: `JT_SKIP_CHECK=1 git commit …`.
2. **Any `git push` (human or agent)** — opt-in git hook, one-time install per clone:

   ```sh
   git config core.hooksPath utils/git-hooks
   ```

   `pre-push` passes silently if a check finished in the last 15 minutes;
   otherwise it runs `./jt check` right there and blocks the push on failure.
   Bypass: `git push --no-verify`.
