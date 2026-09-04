---
description: Run jt subcommands (test, detekt, check, build, etc.)
allowed-tools: Bash
---

Run the JustType build wrapper. The wrapper provides flock-protected,
orphan-cleaning gradle invocations — this is the only sanctioned way to
invoke gradle on this project.

Subcommands (run `/jt help` for full reference):
- `test [pattern]` — debug unit tests, optionally filtered
- `detekt` — static analysis
- `spotless` — formatting check
- `lint` — Android lint
- `jacoco` — coverage report
- `build` — debug APK
- `check` — pre-commit gate (tests + spotless + detekt in one gradle call)
- `check-full` — check + lint + jacoco
- `stop` — stop gradle daemon and sweep orphan workers
- `raw -- <gradle args>` — escape hatch

Usage:
- `/jt test` — run all debug unit tests
- `/jt test "*SetupHostActivityTest*"` — filter tests by pattern
- `/jt check` — run the pre-commit gate

The args after `/jt` are passed straight to the wrapper. Run
`./jt $ARGUMENTS`.
