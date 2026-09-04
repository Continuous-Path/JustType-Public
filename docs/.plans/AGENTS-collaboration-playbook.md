# JustType Collaboration Playbook (for AI agents)

Read this first when picking up JustType language/layout work. It captures the
working process and the hard-won gotchas so a new thread starts productive.
(Repo conventions live in AGENTS.md at the repo root — this file is the
language-work companion.)

## Two repos, one feature

- **JustType1** (this repo, the Android app). Feature work on short-lived
  branches cut from **dev**; merge back to dev. `main` gets periodic merges
  from dev; **CliffDev** is Cliff's personal branch (fast-forward it to dev
  before using). Cliff merges to main via his own flow — don't.
- **layout-analyzer** (`~/Documents/GitHub/layout-analyzer`, private
  ckushler/layout-analyzer, branch `main`). Python tool that finds optimal
  ambiguous layouts and emits the `layoutJson` contract JT bakes into each
  language DB. Corpora, language TOMLs, and the emitter live here.

A language feature almost always touches BOTH: design/corpus/layout in
layout-analyzer, then consume the emitted JSON in JT.

## State-of-play pointers

- **Plans**: `docs/.plans/` — now TRACKED on dev (was gitignored; that caused
  per-worktree drift — see `per-language-layouts/plan.md` history). Edit plans
  on dev and commit like code; no local-only copies.
  - `vietnamese/plan.md` — Vietnamese status + design decisions.
  - `per-language-layouts/plan.md` — the Spanish "Phase 4" per-language layout
    machinery + smoke-test findings + release checklist.
- **Analyzer artifacts**: layout-analyzer `results/<lang>/arrangement.md`
  (rationale) + `report.txt` (the emitted JSON contract).
- **Auto-memory** (loads each session): `layout-analyzer-repo.md` and
  `jt-per-language-layout-plan.md` carry the running status.

## Build/test (JT) — the essential mechanics

- **Always** `./jt <cmd>` (never bare gradle — a hook rejects it). Prefix with
  `JAVA_HOME=$(/usr/libexec/java_home -v 17)` — default shell JDK 11 fails AGP.
- Pre-commit: `./jt check` (tests + spotless + detekt). `./jt spotless-fix`
  auto-formats. Detekt caps: functions ≤8 returns (split validators).
- **Worktrees lack gitignored files**: copy `local.properties` (SDK path) and
  ensure `jt` exists in a fresh checkout before building.
- **Gradle daemon wedge** (mostly fixed): cold `./jt check` sometimes hung at
  test-JVM init and got watchdog-killed at 600s. The fix (dev): a watchdog
  kill now recycles the daemon on the next run. If a run still hangs,
  `./jt raw -- --stop` then rerun. NEVER write shell `until grep ...; do sleep`
  waiters for build output — they outlive their shell and become immortal
  zombies (happened: five ran 16h). Use Bash `run_in_background` + a Monitor
  with a terminal-state pattern, or just re-run and read the captured log.

## Device smoke test (Pixel Tablet)

- `./jt install` (add `-fp` for fresh install + grant perms). `./jt perms -y`
  grants IME/overlay/accessibility; `adb shell ime set
  org.continuouspath.justtype/.JustTypeIME` makes it active.
- **"device unauthorized"**: approve the USB-debugging dialog on the tablet.
- Watch for crashes with a Monitor on `adb logcat` filtered to
  `FATAL|AndroidRuntime|E/.*(JustType|JTUI|LayoutSpec|LangpackInstaller|WordDb)`.

### After EVERY update install: check the update-boundary audit

Any install over existing data (`./jt install` without `-fp`, `adb install -r`)
must be followed by checking `adb logcat -s SettingsAudit` after the first IME
startup (mechanism: PackageUpdateReceiver snapshots the settings store at
install; StartupManager diffs against it at first startup):
- `update-boundary check: no settings changed across the update` — clean, done.
- `update-boundary ALERT: N setting(s) changed across the update with no user
  action` — a real bug: install/init mutated settings. The user's own pre-update
  changes can never fire this line. STOP and capture the logged key:
  before -> after pairs immediately; this check exists because a historical
  "every switch ON right after an update" event was never root-caused, and the
  alert is the evidence we were missing.
- `inventory: ...` lines list toggles off their registry defaults — deliberate
  user choices land there by design. Context only, NOT an alarm.

## Langpack release — REQUIRED after any DB-affecting change

The in-app installer installs ONLY from the GitHub release, never bundled
assets (bundled DBs are for unit tests). So after changing a language's corpus,
layout, or DB build inputs:
1. Bump `{Id}.version` in `app/src/main/db/langpacks.properties`.
2. `./jt raw -- :app:packageLanguageArtifacts` (writes `dist/langpacks/`).
3. `cd dist/langpacks && gh release upload langpacks-v1 <Id>Db-v<n>.db.gz
   manifest.json --clobber --repo Continuous-Path/JustType-langpacks`.
Skip this and installed devices silently keep the old DB (cost us a smoke-test
session to diagnose). The Dev Settings "manifest URL" SAVE button clears the
24h manifest cache to force a re-fetch.

## The layoutJson contract (analyzer → JT)

`LayoutSpec.parse` (JT) consumes what the analyzer emits. formatVersion 1 =
letter languages; **formatVersion 2** adds tone-keystroke languages. v2 fields:
`tones` (position, keys{toneId→keyNum}, labels{style→{toneId→glyph}},
fold{markedChar→[base,toneId]}), `functionKeys` (list-function placement),
`alpha.tones`, `spellMode.slotGroups`/`toneVowels`. A reader MUST reject
formatVersions it doesn't know (a v1 reader half-loading v2 can't type tones).
Internal keyNums 0–5 map to page Keys 0,2,3,4,5,7 (page Key 1=DELETE, 6=SELECT).

## Case handling (dual counts)

DB rows carry lower/title/upper/original case counts; the Selection List shows
one entry per non-zero bucket, and each selection increments the chosen form
(so preference flips with use). Seed them from corpus evidence via the
**5th corpus field** `syllable;freq;;;lower:title[:upper:original]`
(BuildWordDbTask parses it). Seeds are SMALL (budget ~4) so user choices still
flip them. Derive from MID-SENTENCE capitalization only (skip sentence-initial
tokens) — else interjections that open sentences look like proper nouns.

## Language-build gotchas (recurring, will bite the next language too)

- **Pre-lowercased subtitle corpora** (OpenSubtitles): English "I"/"a" and
  other foreign tokens are valid in many languages → contaminate single-letter
  and short-word counts. Cap against a case-sensitive news corpus (Leipzig
  `<lang>_news_*`). Same corpora give the mid-sentence case evidence.
- **Register bias**: news over-represents place/person names; subtitles
  over-represent interjections. Cross-check both; don't trust one alone.
- **Leipzig download**: web UI is bot-blocked; the direct URL works —
  `https://downloads.wortschatz-leipzig.de/corpora/<name>.tar.gz`.
- **macOS case-insensitive FS**: filenames differing only in case collide
  silently (bit us: `...Tone5T` clobbered `...Tone5t`). Avoid case-only-
  distinct filenames.
- **One-key words vs list-functions**: a single-letter word on a key that also
  carries a list-function (Symbols=3 pages, Functions=2, Navigation=1) costs
  extra Selects. Use per-language `functionKeys` to keep frequent one-key words
  off those keys (Symbols is worst — put it on the lightest).
- **E is invariant under key renumbering**: you can permute letter groups to
  any key positions (e.g. to order tone keys naturally) with zero E cost — no
  re-optimization needed, just re-evaluate to confirm.

## Working style Cliff prefers

- He reasons about design deeply and asks precise questions — answer with DATA
  (run the numbers), propose a recommendation, and flag structural changes
  before making them. He uses JT's internal key numbering (0–7, page order;
  Key 1=DELETE, 6=SELECT) — use it consistently.
- State WHERE work happens and HOW it reaches dev, up front, every time.
- "Merged to dev and pushed" is part of done — don't leave spawned-session
  work stranded on a branch; own the landing.
