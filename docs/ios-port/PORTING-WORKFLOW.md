# Android → iOS Continuous Porting Workflow

Android is the main codebase. iOS never forks engine behavior.

## The rule

1. **Shared logic changes happen only in `:jt-core`** → next `core-vX.Y.Z` release → iOS bumps its pinned version. No engine logic is ever edited in the iOS repo.
2. **Platform-behavior changes iOS must mirror** (new IME feature, changed UI semantics, new setting) get a **port ticket** filed in the iOS repo *before* the Android PR merges.
3. **Android-only changes** (Nav mode, overlays, IME plumbing) need no action.

## Per-PR mechanics (this repo)

- The PR template requires categorizing every PR: `shared-core` / `port-needed` (+ iOS issue link) / `android-only`, and confirming golden fixtures were updated if ranking/case/frequency behavior changed.
- `actions/labeler` auto-applies `shared-core` (paths `jt-core/**`) and `port-review` (IME/view/settings paths). A PR still labeled `port-review` at merge time means a human hasn't decided `port-needed` vs `android-only` — resolve before merging.
- Golden-fixture changes require a MINOR core version bump and a changelog entry. The iOS bump PR will show the fixture diff, making behavior changes reviewable on the iOS side.

## Golden tests (drift detector)

Fixtures in `jt-core/src/commonTest/resources/golden/` (`key_sequences.json`, `case_learning.json`, `freq_lifecycle.json`, `diacritics.json`, `headtracking_traces.json`). Run in three harnesses:
1. commonTest on JVM (every PR),
2. commonTest on iosSimulatorArm64 (PRs touching `jt-core/**`),
3. iOS repo `GoldenTests` XCTest via the released framework (every iOS PR) — catches ObjC-bridging/packaging regressions.

## Release cadence

Core releases on demand; minimum biweekly while both platforms are active. Port sweep after every Android milestone or biweekly, whichever comes first. Sync state = the `ios-sync` tag on this repo.

## Port sweep runbook (agent-executable)

1. `git log ios-sync..HEAD --oneline` in this repo; fetch merged-PR labels via `gh pr list --state merged --search "merged:>=<ios-sync date>"`.
2. Categorize each merged PR:
   - `shared-core` → covered by the next artifact bump. Verify a `core-v*` release including it exists; cut one if not.
   - `port-needed` → create an iOS issue from the template below.
   - `android-only` → ledger entry only.
   - Uncategorizable → escalate to a human; list at the end of the sweep report.
3. iOS repo: open the dependency-bump PR (`Package.swift` URL + checksum, `Dictionaries.lock` if dictionaries changed). Run GoldenTests; attach the fixture diff summary to the PR description.
4. Append the sweep report to `docs/ios-port/PORTING.md` (table: Android PR, category, iOS issue, status) and move the `ios-sync` tag: `git tag -f ios-sync && git push -f origin ios-sync`.
5. Output for humans: new iOS issues created + anything escalated.

### iOS port-ticket template

```
Title: Port: <Android feature/change>
Body:
- Android PR: <link>
- Files touched (Android): <paths>
- Behavior: <quoted from Android PR body>
- Acceptance: matching behavior on iOS; verified by <golden fixture | manual test steps>
Labels: port, from-android
```

## Ledger

`docs/ios-port/PORTING.md` is regenerated/appended by sweeps — humans only fix categorization mistakes. Do not hand-maintain it per-PR.
