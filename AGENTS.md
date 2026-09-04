# JustType (JT) Agent Guidelines

This file provides guidance to AI agents developing JT.

## Overview

- JT is an Android keyboard/**IME (Input Method Editor)** for users with disabilities, primarily for those who struggle with normal touch screen usage difficult. It's an efficient and intuitive alternative to standard keyboard layouts, that supports multiple input methods.
- It's main layout uses an 8-key ambiguous layout (3x3 grid, with the center being empty) and word prediction to maximize keystroke efficiency.
  - Each key maps to multiple letters, and word prediction disambiguates input.

## Additional Documentation

- [README](./README.md) — project layout + quickstart.
- [Build wrapper reference (`jt`)](./docs/jt.md) — full subcommand reference for the gradle wrapper.
- [Input Methods, Switches, and JT's sister/companion app (HeadBoard)](./docs/InputMethods.md)
- [Architecture CheatSheet](./docs/architecture-cheatsheet.md)
- [Settings Parity Contract](./docs/settings-parity.md) — every setting must be controllable in both System Settings and Keyboard Settings; read before adding/moving a setting.

## Plans Directory

All planning docs (meta-plans, sub-plans, considerations, retrospectives) live under `./docs/.plans/`.

## Commands

**Use `./jt` (or `/jt` from Claude) — NOT bare `./gradlew`.** The wrapper provides flock-protected, orphan-cleaning gradle invocations; a `PreToolUse` hook rejects bare gradle calls. On failure, `./jt test`/`detekt`/`spotless`/`lint` auto-print a terse AI-friendly summary.

Full reference: `./jt help`, [`docs/jt.md`](./docs/jt.md), or the `jt` skill. Pre-commit: `./jt check` (tests + spotless + detekt) or `./jt check-full` (+ lint + jacoco).

> **Spotless vs Detekt scoping** — Spotless is configured at the **root** project; Detekt at `:app`. The subcommands hide this, but via `raw --`: `spotlessKotlinCheck` has no `:app:` prefix, `:app:detekt` does.

> **Phase policy** — project-wide format runs only at end of Phase 3. If Spotless flags **pre-existing** violations in unrelated files, leave them; confirm your new files are clean and commit those.

## Key Notes
- In this codebase we write code in a way such that, in the future it will be easy to maintain, understand, and build upon.
- Write testable code.
- Avoid being overly verbose or including unnecessary details in documentation, comments, and commit messages. Keep them tight (short yet descriptive).
- Use comments sparingly (only for non-obvious information).
- JT is not in production or being used by real users yet. We are currently preparing for the first batch of users to test the app.
- Prioritize accessibility! This includes financial accessibility, meaning performance and backwards compatibility are very important (while retaining modern features on modern devices).
