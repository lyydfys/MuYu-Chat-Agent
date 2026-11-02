# Final Gate Preflight — Agent Chat Integration V1

- **Snapshot window:** 2026-10-20 01:03–01:16 (Asia/Shanghai), repo `E:\MAC`
- **Branch / HEAD:** `codex/enterprise-refactor` / `af2489909117edfa88721816a5fa40f5a4a5df9c`
- **Mode:** read-only preflight. **No Gradle, build, test, native compile, APK install, or device
  mutation was performed.** The only repository file created by this preflight is this document.
- **Verdict: NO-GO.** Do not start the final gate from this state.

> **Top blocker:** a live `:app:assembleRelease` build holds the exclusive build lock
> (`build/.mca-exclusive-build.lock`, holder pid 14092), and concurrent sessions are still writing
> DSH/Agent production, test and documentation files (last observed write 01:15:58). A gate started
> now would first wait up to 30 minutes for the lock, then fail `input-changed-during-run` even if
> every test passed. The tree has not been frozen at any point during this preflight.

---

## 1. Baseline snapshot

### 1.1 Working tree (moving)

| Item | Observed |
| --- | --- |
| `git status --porcelain=v1 -uall` | **556 entries at 01:14:51** — 45 modified tracked, **511 untracked**, 0 staged. It was 542 at 01:10, so the write set grew by 14 entries in under five minutes. |
| Tracked diff | 45 files, `+2316 / −411`, **211,271 bytes**, SHA-256 `5f5f6cd235be706e217b1f388b42d654346f9ca17835ae8ec503bfd3844293da`. |
| New modules present | `:core:agent-contract`, `:core:agent-runtime`, `:core:dsh-contract`, `:core:dsh-runtime`, `:api:dsh-gateway`, `:feature:agent-workspace`, `:feature:dsh-workspace`. |
| Submodules | `third_party/llama.cpp` `4ceb1719…`; `third_party/stable-diffusion.cpp` `16b3846a…`; `third_party/tokenizers-cpp` `acbdc5a2…`. **`tokenizers-cpp` is dirty** (5 files: `CMakeLists.txt`, `include/tokenizers_c.h`, `include/tokenizers_cpp.h`, `rust/src/lib.rs`, `src/huggingface_tokenizer.cc`). This dirt is part of the gate fingerprint (hard-lock D08). |
| Local-only guards | `AGENTS.md` and `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` are still in `.git/info/exclude`. Not re-checked by the architecture script in this preflight. |
| Release APK on disk | `app/build/outputs/apk/release/app-release.apk`, **495,516,800 bytes**, mtime **01:01:44**, SHA-256 `e420e9e1f103c6d8123a7553be4b0dba74a7b292d847c2861330a387b4f4d080`. It predates the 01:11–01:15 DSH/Agent edits, so it is **not bound to the current tree**. |
| Devices | `98a37aa7` (piano / 25091RP04C) and `f9d98aa3` (ishtar / 2304FPN6DC) attached. No install, uninstall, clear-data, instrumentation or UI action was run. |

### 1.2 Host resources

| Resource | Observed ~01:05–01:11 |
| --- | --- |
| Physical RAM | 15.64 GiB total, **1.02 GiB free** |
| Commit | **31.39 / 40.66 GiB** used |
| Disk | `C:` **1.3 GiB free** (Gradle home, `%TEMP%`), `E:` 16.7 GiB free |
| Configured heap | 6 GiB Gradle daemon; `--max-workers=2` on the current build |

Prior logs on this host already record `OutOfMemoryError: Java heap space`,
`Not enough memory to run compilation`, and daemon disappearance during Kotlin/native/package
tasks. The gate has no internal timeout and runs five Gradle invocations; `C:` headroom is the
tightest part.

---

## 2. Active builds and writers — the freeze checks failed

### 2.1 Build lock is held by a live build

Lock payload read at 01:15:14:

```json
{
  "schema": "mca.build-lock/1",
  "pid": 14092,
  "kind": "build",
  "startedUtc": "2026-10-19T17:02:41Z",
  "machine": "…",
  "commandLine": "lead: settings transition experiment"
}
```

Live process chain (all confirmed alive):

```text
powershell 14092  (-File tools\_build_locked.ps1 -LogPath artifacts\build-final6.log, since 00:49:39)
  └─ cmd 34692
      └─ java 28900  org.gradle.wrapper.GradleWrapperMain :app:assembleRelease …
          └─ java 13156  GradleDaemon 8.14.3 (started 01:02:43)
```

The Gradle command is:

```text
:app:assembleRelease -Pmca.abis=arm64-v8a -Pmca.keepNativeSymbols=false
-PmcaQnnSdkRoot=E:\tools\qairt-sdk\2.45.0.260326 --console=plain --max-workers=2 --no-daemon
-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx6144m
```

At the 01:16 snapshot `artifacts/build-final6.log` was 141,818 bytes (last write 01:09:28) with
tail tasks `:app:minifyReleaseWithR8` / `:app:lintVitalAnalyzeRelease`. By 01:15:28 it had advanced
to 142,038 bytes and `lintVitalRelease`, and the daemon's CPU rose from ~1884 s at 01:09 to
~2764 s at 01:15 (3169 s at 01:17), so this is a live R8/lint phase, not an idle daemon. The lock is
held and the build is the **current writer of the build directory**.

### 2.2 Concurrent source writers are still active

Files written after this preflight started (representative, excluding `build`, `.gradle`, `.cxx`,
`.kotlin`, `artifacts`):

| File | Last write |
| --- | --- |
| `docs/proposals/agent-dsh-embedded-v1/DSH_CANDIDATE_TRIAL_PLAN.md` | 01:15:58 |
| `feature/dsh-workspace/.../DshPageLayoutShim.kt` | 01:15:48 |
| `feature/dsh-workspace/.../DshWebViewHost.kt` | 01:15:48 |
| `core/dsh-contract/.../capability/DshCapabilityRequests.kt` | 01:15:25 |
| `core/dsh-contract/src/test/.../FakeDshCapabilityPortTest.kt` | 01:15:12 |
| `app/src/test/.../dsh/McaDshGatewayBackendTest.kt` | **01:15:06** |
| `core/dsh-contract/src/test/.../DshCapabilityContractTest.kt` | 01:14:58 |
| `app/src/test/.../dsh/DshTestFixtures.kt` | 01:14:54 |
| `core/dsh-runtime/src/test/.../DshProcessAndReadinessTest.kt` | 01:14:52 |
| `core/dsh-contract/.../capability/DshCapabilitySecurity.kt` | 01:14:49 |
| `feature/dsh-workspace/.../DshFirstFrameReporter.kt` | 01:14:47 |
| `app/src/test/.../dsh/DshCallRegistryTest.kt` | 01:14:24 |
| `docs/proposals/agent-chat-integration-v1/C00_BASELINE.md` | 01:13:06 |
| `docs/proposals/agent-chat-integration-v1/V1_1_STATUS.md` | 01:12:38 |
| `app/src/main/.../dsh/McaDshGatewayBackend.kt` | 01:12:41 |

The status count grew 542 → 556 between 01:10 and 01:14:51. This is a real, ongoing write set, not
just stale mtimes. After this document was written, `DSH_CANDIDATE_TRIAL_PLAN.md` changed again at
01:17:43 while the build kept running, so the “writers stopped” condition is still false at 01:17.

### 2.3 What `verify-code.ps1 -AgentTests -Rerun` would do now

1. `Assert-McaBuildSlot` would print the live lock holder and return (it does not refuse when a
   holder exists).
2. `Enter-McaBuildLock` would retry for **1800 s** (`-RetrySeconds 1800`, interval 2 s), then fail
   with `MCA build lock is held`.
3. `Invoke-Gradle` would return 1 with no `BUILD SUCCESSFUL`; the script would report
   `BUILD FAILED` / `compile-error` and exit 1. It would not reach the tests.
4. If the lock were somehow free, the before/after fingerprint would differ because writers are
   active, producing `input-changed-during-run` regardless of test results.

The lock module's scope is honest: it serialises only participants that take it. The current
holders do take it; the problem is that they are not finished.

---

## 3. The single final gate (exact command)

Run from any shell **after** the freeze conditions in §7 pass:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File E:\MAC\scripts\verify-code.ps1 -AgentTests -Rerun
```

The script supplies JDK/QNN defaults; if the launching shell overrides them, set:

```powershell
$env:JAVA_HOME        = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1'
$env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326'
```

Do **not** substitute `scripts/agent-verify.ps1` for this gate: it defaults to killing Gradle/Kotlin
Java processes machine-wide and does not perform the before/after worktree fingerprint.
`verify-code.ps1` is the controlled lane.

### 3.1 Expected Gradle task list

Five separate Gradle invocations, each acquiring the shared lock:

| # | Invocation | Tasks |
| --- | --- | --- |
| 1 | compile | `:app:compileDebugKotlin`, `:core:engine:compileDebugKotlin`, `:api:local:compileDebugKotlin` |
| 2a | contract tests | `:core:agent-contract:testDebugUnitTest --rerun` |
| 2b | runtime tests | `:core:agent-runtime:testDebugUnitTest --rerun` |
| 2c | workspace tests | `:feature:agent-workspace:testDebugUnitTest --rerun` |
| 2d | app tests | `:app:testDebugUnitTest --rerun` |

All are launched with `-p E:\MAC --console=plain`. `--rerun` re-executes only the selected task;
`--rerun-tasks` is deliberately not used because it would rebuild native code.

`-AgentTests` does **not** include:

- `:core:dsh-contract:testDebugUnitTest`, `:core:dsh-runtime:testDebugUnitTest`,
  `:api:dsh-gateway:testDebugUnitTest`, `:feature:dsh-workspace:testDebugUnitTest`
- `:feature:agent:testDebugUnitTest`
- `:app:assembleRelease` / `:app:assembleDiagnostic`
- any `connected*AndroidTest` or instrumentation task
- any native/model/device test

However, `:app:testDebugUnitTest --rerun` runs the **whole app unit-test source set**, including
`app/src/test/java/com/muyuchat/mca/dsh/**` (the DSH gateway tests). A DSH app-test failure or hang
therefore fails/stalls the “Agent” lane for a DSH reason.

### 3.2 Expected result shape

- Exit code **0**.
- One successful compile invocation and four successful test invocations (`BUILD SUCCESSFUL`
  each).
- Last line: `VERIFY OK (code layer only - this is not the release build)`.
- Aggregate: `reports (this run only): tests=<N> failures=0 errors=0 skipped=<S>` with `N > 0`.
- **Do not force a number.** The historical 3348 is not a contract:
  - contract 19, runtime 132, workspace 118 are the last separate XML counts;
  - the last full-app XML (3079 / 0 / 0 / 11 at 22:30) is no longer on disk;
  - the current `McaDshGatewayBackendTest` source has 24 tests, not the 26 in the 00:30 XML.

Note: `verify-code.ps1` counts only XML files whose mtime is newer than “20 minutes ago” at the end
of the run. Under memory pressure a >20-minute gate can undercount early modules. Read fresh
per-directory XML counts afterwards; the printed aggregate alone is not sufficient evidence.

---

## 4. Latest test XML counts (all separate runs — no one-shot gate)

| Suite / directory | Files | Tests | Failures | Errors | Skipped | Newest XML | Status |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| `:core:agent-contract` | 1 | 19 | 0 | 0 | 0 | 00:17:21 | green, but from a separate run |
| `:core:agent-runtime` | 15 | 132 | 0 | 0 | 0 | 00:17:33 | green, but from a separate run |
| `:feature:agent-workspace` | 14 | 118 | 0 | 0 | 0 | 00:17:44 | green, but from a separate run |
| `:app` (only filtered DSH XML present) | 1 | **26** | **1** | 0 | 0 | **00:30:37** | **FAIL**, stale, filtered class only |
| `:core:dsh-runtime` | 14 | 59 | 0 | 0 | 0 | 01:02:13 | green; **not in `-AgentTests`** |
| `:feature:dsh-workspace` | 10 | 57 | 0 | 0 | 0 | 01:02:35 | green; **not in `-AgentTests`** |
| `:api:dsh-gateway` | 12 | 77 | 0 | 0 | 0 | 00:07:00 | green; **not in `-AgentTests`** |
| `:feature:agent` | 5 | 22 | 0 | 0 | 0 | 03:30:01 | green; **not in `-AgentTests`** |
| `:app` full suite (historical) | — | 3079 | 0 | 0 | 11 | 22:30 (doc record) | historical only; XML not on disk |

The three `-AgentTests` library modules are green at 00:17, but their XML predates the current app
DSH test/fixture edits and the current `:app` dependency graph. They must be re-run by the gate.
The only app XML is a **filtered DSH class** run, not the whole app suite; it must not be reported
as an app-suite result.

---

## 5. `McaDshGatewayBackendTest` and the empty-next-call failure

### 5.1 What failed at 00:30

The 00:30 XML records:

```text
testsuite tests=26 failures=1 errors=0 skipped=0 time=1.45
testcase  gateway generation stop structurally cancels its call without a second terminal  time=0.084
failure   java.util.NoSuchElementException: List is empty.
          at kotlin.collections.CollectionsKt___CollectionsKt.single(…)
          at …McaDshGatewayBackendTest$gateway generation stop structurally cancels its call without a second terminal$1.invokeSuspend
          at …McaDshGatewayBackendTest.kt:499
```

This was a **fast assertion failure (1.45 s total), not a hang**. The failing statement was a
`single()` on a collected-event list that was empty.

### 5.2 Root cause (old test, from the compiled 26-test class)

The old test installed a fake `flowFactory` that emitted one chunk and then suspended forever. It
completed its `started` latch **inside the fake flow producer, immediately after `emit` returned**,
then awaited `started`, cancelled the call and cancelled the collector job. The gateway pumps the
engine flow through a buffered `Channel` (`collectLocal`, capacity 64): the producer's `emit`
returns once the chunk is in the channel, *before* the gateway consumer appends it to the test's
`stoppedCallEvents` list. The test could therefore observe `started` and run
`stoppedCallEvents.single()` while the list was still empty. The `NoSuchElementException` is this
race, not a missing terminal from the adapter.

The old class also had one shared `flowFactory` for every request id, so a blocking cancellation
flow could leak into the next, unrelated call. The three tests removed from the 00:30 class were:

- `catalog is read live so a model loaded after backend creation is discoverable`
- `cancelling one in-flight call leaves a concurrent call untouched`
- `local model unloaded mid-call ends exactly once and the next call fails honestly`

### 5.3 Current source at 01:15 (fixed, not verified)

Current files (may already be stale; hashes are the observed snapshot):

| File | mtime | SHA-256 | Content observed |
| --- | --- | --- | --- |
| `app/src/test/.../dsh/McaDshGatewayBackendTest.kt` | 01:15:06 | `7a2046014de8796717b16672eac43d1367e8e95864ebcae22ae00414c1eecb27` | **24 tests**; the failing test is re-added; one new test added |
| `app/src/main/.../dsh/McaDshGatewayBackend.kt` | 01:12:41 | `b0d1f1ce97fdcbaf7819dec7ed5b2731868d7b0b046ae3ba742451540d810aba` | structural cancellation still emits **no second terminal** (explicit contract comment); test-injectable `DshCallRegistry` added with a default |
| `app/src/test/.../dsh/DshTestFixtures.kt` | 01:14:54 | `7819c1b7ba074c32f79221522a223d20556f1a84dcc15b7bb97e13c936103fdc` | `flowByRequestId` added; blocking flows are scoped to one request id, not the next call |

What the current source does differently:

- The structural-cancellation test now completes `started` **inside the gateway collector**
  (`stoppedCallEvents += event; started.complete(Unit)`), so the first delta is guaranteed to be
  appended before cancellation.
- `FakeLocalChatPort.flowByRequestId` serves a blocking flow only to `dsh:6:1`; `dsh:7:1` gets a
  normal `chunk + done` flow, so the successor call cannot inherit the stopped call’s suspended
  flow.
- The re-added test asserts: exactly `TextDelta("partial")`, **no terminal** for the structurally
  cancelled call, exact cancel set, no late cancel, and a clean successor call
  (`TextDelta("next")` + `Completed(STOP)`, 10 s bound).
- A second new test, `structural cancellation alone stops exactly its own engine request`, asserts
  that cancelling the collector without `cancelCall` still stops exactly `dsh:8:1` once.

**Verdict:** the empty-next-call failure is **fixed in source** (the previous deletion was replaced
by a deterministic test plus fixture isolation), but it is **not verified** by any execution. The
current test source is still being edited (mtime 01:15:06, after the last XML at 00:30).

### 5.4 Would the gate still hang on this class?

- The 00:30 failure was a **1.45 s `NoSuchElementException`**, so the original report of a “hang”
  is not supported by that XML.
- The current 24-test class has no unbounded wait in its cancellation tests: the producer/collector
  handshakes and successor call are bounded by `withTimeoutOrNull(10_000)`; the busy-admission test
  is bounded at 1 s. Static inspection finds no `awaitCancellation()` left without a paired
  cancel/join timeout.
- **But:** the current 24-test source has **never been compiled**. The only compiled class set is
  from **00:32:40** and still contains the old 26-test inner classes, including the failing test at
  its old line numbers. The current source is newer than every compiled artifact.
- `:app:testDebugUnitTest` has **no test timeout** in `app/build.gradle.kts` (only
  `unitTests.isReturnDefaultValues = true`). If any app test hangs, the whole lane hangs
  indefinitely; `verify-code.ps1` has no timeout either.
- Therefore: **no static evidence of a hang in this class; no execution evidence that it does not
  hang.** The correct status is `UNVERIFIED`, not `PASS` and not `BLOCKED by a proven hang`.

### 5.5 Coverage caveat

The current class has 24 tests versus 26 in the 00:30 XML: three old tests were removed, one new
test was added. The missing `catalog is read live…` and `cancelling one in-flight call leaves a
concurrent call untouched` cases are coverage gaps, not proven regressions. The “next call” behavior
is now covered inside the structural-cancellation test, but the “model unloaded mid-call” scenario
is not.

---

## 6. Fingerprint (not frozen — no freeze was possible)

Two read-only measurements with
`tools/build-guards/McaWorktreeFingerprint.ps1 -RepoRoot E:\MAC -EmitJson`:

| Measured (UTC) | Fingerprint | Files | HEAD |
| --- | --- | ---: | --- |
| 2026-10-19T17:08:14Z (01:08:14 CST) | `c54b00fc49b58ef4dbf20937ceece1278228303d3725c656b3f4c17e72d74fa5` | 590 | `af24899…` |
| 2026-10-19T17:14:51Z (01:14:51 CST) | `e675676b872c9605719e0563d91920f820b1ec4a06e8f897c37fd97fcf7bd168` | 600 | `af24899…` |

Neither value is a frozen fingerprint:

- Between the two measurements the status count moved 542 → 556 and the DSH writer continued
  (`McaDshGatewayBackendTest.kt` 01:15:06, `DshTestFixtures.kt` 01:14:54, `DSH_CANDIDATE_TRIAL_PLAN.md`
  01:15:58).
- This document itself is an untracked input and changes the fingerprint when created.
- `verify-code.ps1` must compute its **own** start fingerprint after the freeze and compare it at
  the end. Any difference is `FAIL — input-changed-during-run`, regardless of test counts.

**Freeze candidate: NONE.** A fingerprint can only be declared frozen after the build lock is free,
all writers have stopped, and the tree has been stable across the start/end of the gate.

Reference command:

```powershell
powershell.exe -NoProfile -File E:\MAC\tools\build-guards\McaWorktreeFingerprint.ps1 `
  -RepoRoot E:\MAC -EmitJson
```

---

## 7. Current blockers and GO conditions

| ID | Blocker | Observed | Status |
| --- | --- | --- | --- |
| B1 | Live `:app:assembleRelease` holds the build lock | pid 14092 → gradlew 28900 → daemon 13156; started 01:02:41; R8/lint still running at 01:15 | **BLOCKING** |
| B2 | Concurrent DSH/Agent writers | production, test, docs modified through 01:15:58; status 542 → 556; two different fingerprints already | **BLOCKING** |
| B3 | Current `McaDshGatewayBackendTest` never compiled/run | source 01:15:06 (24 tests); compiled classes 00:32:40 (old 26 tests); last XML 00:30:37 / 1 failure | **BLOCKING (evidence)** |
| B4 | Memory / disk | 1.02 GiB free RAM, 31.39/40.66 GiB commit, `C:` 1.3 GiB free | **HIGH RISK** |
| B5 | DSH official UI white pane | R07 evidence 00:16–00:47: official main area permanently blank after onboarding; E3/E5/E6 FAIL; DSH chat NOT_RUN | **RELEASE BLOCKER** (outside this JVM gate) |
| B6 | Device validation | two devices attached; MIUI new-package install blocked; instrumentation/handheld UI not run | **NOT_RUN / BLOCKED** |
| B7 | Architecture rules on frozen tree | not run in this preflight; previous 16-rule pass is stale | **NOT_RUN** |

### 7.1 Conditions required to flip to GO

1. Stop every Agent/DSH/front-end writer. Confirm no file write in the gate input set for a
   meaningful quiet window.
2. Let the current build finish and release `build/.mca-exclusive-build.lock`; confirm no
   `GradleWrapperMain`, `GradleDaemon`, `KotlinCompileDaemon`, `GradleWorkerMain`, `cmake`, `ninja`
   or link process for this checkout.
3. Free memory/`C:` if possible (close editors/agents). Do not edit `gradle.properties` or scripts
   to “fix” the gate; that changes the input snapshot.
4. Capture the start fingerprint outside the repo and record HEAD / status count / lock payload.
5. Run `python tools/architecture/check_architecture_rules.py` on the frozen tree and save stdout.
6. Run the exact command in §3 with an external transcript, then verify exit 0, four fresh
   `BUILD SUCCESSFUL` test invocations, and per-directory XML counts (including the current
   24-test `McaDshGatewayBackendTest`).
7. Recompute the fingerprint. If it differs, report `FAIL — input-changed-during-run`; do not
   cherry-pick green XML.

Only then may the code-layer gate be called PASS. The DSH white-pane blocker (B5) remains a
separate release blocker even if the JVM gate passes.

---

## 8. Explicit NOT_RUN / BLOCKED list

- `scripts/verify-code.ps1 -AgentTests -Rerun`: **NOT_RUN** (forbidden in this preflight; currently
  blocked by the live build + writers).
- Any Gradle compile/test/assemble: **NOT_RUN**.
- Any native/CMake/model execution: **NOT_RUN**.
- `check_architecture_rules.py`: **NOT_RUN** in this preflight.
- Any APK install/uninstall/clear-data/instrumentation: **NOT_RUN**.
- Real local model, cloud, MCP, FGS/notification, SAF, TalkBack, visual/performance: **NOT_RUN**.
- 00:17 library XML, 00:30 app XML, 00:07/01:02 DSH XML: **separate historical runs**, not a
  one-shot result for the current fingerprint.
- The 01:01:44 APK: **stale** relative to the 01:11–01:15 source edits; not a current release
  candidate.

---

## 9. Bottom line

- **NO-GO.** The tree is not frozen and the build slot is occupied by the lead’s
  `:app:assembleRelease`; concurrent writers are still editing DSH/Agent production and tests.
- **Top blocker:** the live build lock plus active writers (B1+B2), not a test assertion.
- The known empty-next-call failure is **fixed in source at 01:12–01:15** (collector-side latch,
  per-request flows, re-added structural-cancellation tests) but **unverified**: the current
  24-test source has never been compiled, and the last app XML is the 00:30 failure.
- The old 00:30 failure was a fast `NoSuchElementException`, not a hang. The current class has no
  static unbounded wait, but the app test task has no timeout, so a clean “no hang” claim still
  requires a fresh run.
- Run the gate only after §7.1, and report the actual per-module XML counts rather than the
  historical 3348.
