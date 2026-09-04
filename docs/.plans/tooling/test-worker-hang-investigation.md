# Test-Worker Hang Investigation

> Standalone tooling step, slotted **after Phase 4.5, before Phase 5**.
> **Status:** PARTIAL — concrete leaks fixed + watchdog catches the
> remaining ~28%-of-cold-runs hang; root-cause of the residual hang
> identified as JVM-internal (humongous GC / safepoint stall under
> Robolectric + Mockito-inline + JaCoCo-gated). Deferred to a future
> deep-JVM session. 2026-07-27: safepoint-stall mechanism confirmed on a
> live specimen; test JVMs now abort on a stalled safepoint (fail fast,
> name the in-flight test) instead of freezing — see the recurrence
> section below.
> **Risk:** LOW (test-only changes + tooling).

---

## Context

`./jt` ships an orphan-worker sweep at lock acquire — most recently broadened
(commit `25a91483`) to kill every `GradleWorkerMain` it sees, not just true
PPID==1 orphans. That's a **bandage**: it cleans up after a hang, it does
not prevent one.

A worker still wedged at ~95% CPU during this session's `./jt test` (13 min
before user-killed). Same `./jt test` ran clean in 27 s on the next attempt.
Classic timing-dependent leak.

Prior test-infra investigation closed three root causes (see
`memory/project_test_flake_broadcast_bridge.md`):
- JaCoCo agent unconditionally attached → JVM init hang (`a2c7e5b8`).
- JDK 21 ByteBuddy self-attach race → MockMaker cascade (`445df62d`).
- `SettingsRepository.scope` never cancelled → IO dispatcher starvation (`f1e471f8`).

This step investigates whatever leak remains.

---

## Goal

By close:
1. Every Robolectric-backed test that allocates a lifecycle-bound resource
   tears it down in `@After` (controllers destroyed, scopes cancelled,
   listeners removed, Looper idled).
2. Every production-side `CoroutineScope` created at construction time has
   a test-visible cancellation path (`clearForTesting`, etc.).
3. A per-test wall-clock timeout fails the suite in seconds rather than
   minutes when a test hangs.
4. Reproducer documented: either we trip the hang in `--rerun-tasks
   -PstressTest=N` matrix, or we capture a thread dump from a wedged
   worker. If neither, document the gap and rely on (3) as the catch-all.

---

## Hypotheses (ordered by suspicion)

### H1 — New listener subscription not unregistered

`SettingsRenderer.addChangeListener` (4.3) and `SettingsActivity`'s
cross-key state listener (4.4) both subscribe to `SettingsRepository`.
`SettingsActivityTest` destroys the controller in `@After` but does not
assert that the renderer's listeners are unregistered. If `addChangeListener`
isn't matched 1:1 by `removeChangeListener` on `View` detach, listeners
pile up across tests and each gets fired on every `put*`. Not a busy-loop
on its own but could amplify other leaks.

**Check:** grep for `addChangeListener` callsites and confirm each pairs
with a `removeChangeListener` at an `onDetachedFromWindow`,
`onDestroyView`, or scope-cancellation boundary.

### H2 — `lifecycleScope` flow collectors keep collecting after destroy

`SettingsActivity` collects `repo.observe*Flow()` in `lifecycleScope`.
Robolectric's `ActivityController.destroy()` should cancel that scope,
but flow collectors that pump fast (e.g., DataStore writes echoing back
through a `SharedFlow`) can prevent the scope from idling. If the test
runner moves on while the previous test's collector still spins, the
worker JVM never quiesces.

**Check:** confirm the test-side `SettingsRepository.resetInstanceForTesting()`
also cancels any in-flight collectors, not just the repo's own scope.

### H3 — Background coroutines started in `companion object` init

Any `init { scope.launch { ... } }` in a singleton (DataStore wrapper, DB
opener, etc.) survives across all tests in a worker. If that coroutine
polls on a flag or retries indefinitely on failure, it busy-loops.

**Check:** `grep -rn "scope\.launch\|GlobalScope\.launch" app/src/main`
and audit each for a cancellation path.

### H4 — Robolectric main Looper not drained

40/42 tests use `@RunWith(RobolectricTestRunner)`. Some post to the main
Looper without `ShadowLooper.idleMainLooper()` before assertions. If a
`Handler.postDelayed` survives a test and lands during the next test, it
can trigger reentrant work in production code paths that allocate
scopes.

**Check:** instrumented run with the `LooperMode.PAUSED` Robolectric
config; surface any test that fails or hangs under paused-Looper.

### H5 — Mockito-inline static stubs leaking across tests

Mockito-inline rewrites bytecode globally. If a static mock is set in
test A and not unmocked, test B inherits it. Combined with H1/H2, a
mocked `SettingsRepository.getInstance()` returning a stub can cause
production code's `addChangeListener` to land on the real-instance hash
map across tests, accumulating zombie listeners.

**Check:** any `Mockito.mockStatic` / `mockkStatic` in the test sources?

### H6 — No per-test timeout, so a single hang stalls the worker
indefinitely

Even if everything above is fixed, future leaks will re-occur. The
catch-all is `@Rule Timeout` (global, set in a base class or via gradle).

**Investigation outcome:** per-test JUnit4 `Timeout` rule is incompatible
with Robolectric — JUnit moves the test body to a watcher thread, but
Robolectric requires `buildActivity` to run on the SDK-Main looper thread.
Tests fail with `IllegalStateException: buildActivity must be called on
main Looper thread`. Per-test timeout abandoned.

**Shipped instead:** `./jt` watchdog at the **script level** (commit
`4b2010ec`). Each subcommand has a wall-clock budget (5–15 min); on
expiry the watchdog captures jstack / `sample` then SIGKILLs the gradle
tree. Override per-invocation via `JT_TIMEOUT=N ./jt …`. This catches the
remaining intermittent hang without needing per-test instrumentation.

### Residual hang — JVM-level, not test-level

After fixing H1-H3 and shipping the watchdog (H6), cold stress shake at
7 runs:

| Result | Count |
|---|---|
| Clean (38–55 s) | 5 |
| Hung → watchdog killed at 10 min | 2 |

The two hangs left `sample`-captured dumps in `.gradle/jt-watchdog/`.
Recurring signature:

- Test worker thread spinning in JIT-compiled code (`???` in
  `<unknown binary>`) with two address-pair recursion (e.g.
  `0x10f41d17c ↔ 0x10f41d62c`).
- One run also showed `DefaultDispatcher-worker @coroutine#180` stuck
  in `G1CollectedHeap::attempt_allocation_humongous` →
  `try_collect_concurrently` → `VMThread::wait_until_executed` →
  blocked on a safepoint another thread wasn't yielding.

Switching `-XX:+UseParallelGC` + `maxHeapSize=4g` + `forkEvery=40` (from
G1 / 2g / 80) did not reduce the rate measurably (still ~28%). This
points at runtime-level interactions (Robolectric ShadowLooper +
Mockito-inline ByteBuddy + kotlinx-coroutines) rather than our code.

**Further investigation requires:** recompile production + Kotlin stdlib
with line-numbers, run with `-Xlog:safepoint=info`, JFR profile, or
async-profiler. Out of scope for routine work.

### Wedged daemon — a now-handled cause of the residual hang pattern

**2026-07-24.** Four *consecutive* full-suite hangs at test-JVM init, each
watchdog-killed at 600 s. The worker sweep at lock-acquire ran every time
and did not help — because the wedge lived in the **gradle daemon**, not
the workers. `./gradlew --stop` fixed it instantly; the very next run
passed in 39 s (1342 tests).

Lesson: a one-off hang and a *repeating* hang have different shapes. The
residual JVM-level hang above is probabilistic (~28% of cold runs); a
wedged daemon is deterministic — it reproduces the hang on **every**
subsequent run until the daemon is restarted, because each run's test JVM
is forked from the same broken daemon state.

**Handled since:** the `jt` watchdogs (budget + hang-detect) now drop a
marker file (`.gradle/jt-watchdog-killed`) when they kill a run. The next
invocation sees the marker at lock-acquire and stops the daemon
(`gradlew --stop`, with a 30 s budget and a force-kill of `GradleDaemon`
JVMs as fallback) before running. `./jt stop` clears the marker too. So a
watchdog kill can no longer cascade into a hang loop; at most one run is
lost to a wedged daemon.

### 2026-07-27 recurrence — the stall is in the worker; daemon recycle can't help

Six consecutive `./jt check` runs in a worktree were watchdog-killed at
600 s **despite the wedged-daemon guard**. Daemon logs
(`~/.gradle/daemon/8.10.2/daemon-*.out.log`) prove a **fresh daemon served
every run** — the guard did its job and the hang recurred anyway. This
repeating hang was worker-level, not daemon-level.

A still-live specimen (worker pid 41455, 35+ min at 100% CPU) settled the
mechanism:

- VM Thread pinned in `SafepointSynchronize::synchronize_threads()` for an
  entire 3 s sample — a safepoint sync that never completes.
- Robolectric's "SDK 34 Main Thread" with 100% of samples in one
  JIT-compiled frame: a compiled loop with no safepoint poll, **mid-test**.
- The whole JVM freezes as a result: jstack/attach need a safepoint, so
  they fail ("target process doesn't respond"), which is why dumps come
  from macOS `sample` — and `sample` cannot symbolize JIT-compiled Java
  code. The earlier "zero com.justtype frames ⇒ tests never start" reading
  was **wrong**: a test WAS running; its frames are just invisible.

`UseCountedLoopSafepoints` + `LoopStripMiningIter=1000` (the mitigation
above) were present in the wedged worker's args — insufficient.

Hardening shipped:

- **`app/build.gradle`** test JVMs add `-XX:+SafepointTimeout
  -XX:SafepointTimeoutDelay=15000 -XX:+AbortVMOnSafepointTimeout`
  (+`UnlockDiagnosticVMOptions`): a safepoint stalled >15 s aborts the
  worker. Gradle then fails within seconds and **names the in-flight
  test** — the identity a frozen JVM can never give up — instead of
  wedging for 600 s.
- **`jt`** watchdogs now SIGKILL wedged workers after dumping them
  (previously they kept spinning at 100% CPU until the next run's sweep —
  or forever, if no next run came).
- **`jt`** worker sweep + dumps are scoped to this project via the project
  path embedded in worker cmdlines. The old machine-wide
  `pgrep GradleWorkerMain` would kill a legitimate concurrent run in
  another worktree or the main checkout.

Note on "`--stop` fixed it" folklore: with fresh daemons proven for every
wedged run, daemon state was not the variable on 07-27. Treat a manual
`--stop` recovery as coincidence (e.g. code changed between attempts)
unless the wedge reproduces against an idle warm daemon.

---

## Verification

### Step 1 — Audit Robolectric test teardowns

```bash
# List Robolectric tests without an @After block
grep -L "@After" $(grep -rl "@RunWith(RobolectricTestRunner" \
  app/src/test/java/com/justtype/nativeapp/)
```

For each result, decide: does this test allocate a controller, register
a listener, launch a coroutine, or open a resource? If yes, add @After
teardown. If no (pure-logic test), document and skip.

### Step 2 — Audit production scopes

```bash
grep -rn "CoroutineScope\|scope\.launch\|GlobalScope" \
  app/src/main/java/com/justtype/nativeapp/
```

For each `CoroutineScope` allocation, verify a test-visible cancellation
exists (e.g., `clearForTesting`, `onDestroy`, fragment `onDestroyView`).

### Step 3 — Audit listener registrations

```bash
grep -rn "addChangeListener\|registerReceiver\|setOnSharedPreferenceChangeListener" \
  app/src/main/java/com/justtype/nativeapp/
```

For each, confirm a 1:1 unregister on the corresponding lifecycle exit.

### Step 4 — Add per-test timeout

Add to `app/src/test/java/com/justtype/nativeapp/TestTimeoutRule.kt`:

```kotlin
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
val testTimeoutRule = Timeout(30, TimeUnit.SECONDS)
```

Wire via a base class or via a `@get:Rule` on each `@RunWith` test.
Decide: base class (one edit point, but all tests must extend) vs.
per-test rule (more boilerplate, more flexible).

### Step 5 — Stress-shake

```bash
# Run the suite 10 times back-to-back, no cache, fail-fast on first hang
for i in {1..10}; do
  ./jt test --rerun-tasks || break
done
```

If any iteration hangs longer than 60 s, kill it and capture:

```bash
jstack <worker-pid> > /tmp/worker-jstack-$i.txt
pkill -9 -f GradleWorkerMain
```

Stash the jstack output in `docs/.plans/tooling/jstack-evidence/`.

### Step 6 — Triage and fix

Per audit findings and per stress-shake evidence, file individual
fix commits. Each commit:

```
Tooling: test infra — <one-line description>

- What was leaking.
- Where it leaked.
- How it's fixed.
```

### Step 7 — Re-run stress matrix

After all fixes, repeat Step 5 with `for i in {1..20}`. Goal: 20 clean
runs at < 60 s each.

---

## File Layout

| File | Change |
|---|---|
| `app/build.gradle` | Possibly `forkEvery = 1` for diagnostics (revert at end). |
| `app/src/test/.../TestTimeoutRule.kt` | New. Per-test wall-clock timeout. |
| `app/src/test/.../*Test.kt` | Add `@After` cleanup where missing. |
| `app/src/main/.../*.kt` | Add `cancel()` paths to long-lived scopes. |
| `docs/.plans/tooling/jstack-evidence/` | Captured worker stack dumps. |
| `memory/project_test_flake_broadcast_bridge.md` | Update with new root causes found. |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Cannot reproduce the hang in the stress matrix | MEDIUM | LOW | Step 4 timeout is the catch-all; ship it regardless. |
| Adding `@After` cleanup breaks a test that was relying on state leak | LOW | LOW | One commit per file; revert on red. |
| `Timeout(30)` is too tight for slow Robolectric tests | LOW | LOW | Raise to 60 s if a few flake; the goal is fail-fast, not aggressive limits. |
| Stress matrix reveals a production bug, not a test bug | LOW | MEDIUM | Surface it; fix becomes its own modernization step, not part of this tooling work. |

---

## Estimated Effort

~2-3 hours:
- Audits 1-3: ~30 min
- Timeout rule + wiring: ~30 min
- Stress shake + jstack capture: ~30 min (background)
- Fix commits: ~60-90 min (depends on findings)

One or more commits:
- `Tooling: per-test wall-clock timeout (catch-all for hangs)`
- `Tooling: test infra — <each specific leak fix>`
- Final commit if findings warrant: update memory note.
