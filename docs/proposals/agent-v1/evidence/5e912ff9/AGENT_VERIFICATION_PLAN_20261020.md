# Agent Page Verification Plan — 2026-10-20

Status: **PLAN / NOT_RUN** (read-only preparation; no Gradle invocation was executed by this session)
Prepared: 2026-10-20 02:22 +08 (Asia/Shanghai)
Repository: `E:\MAC`
Branch / HEAD: `codex/enterprise-refactor` / `959de21`
Scope: Agent page — `:feature:agent-workspace`, `:core:agent-contract`, `:core:agent-runtime`, and the app-side `com.muyuchat.mca.agent.**` unit tests.
Out of scope: DSH modules, the old `:feature:agent` tuning page, device instrumentation, release-APK acceptance, real models/cloud.

> Method note: this plan was prepared by reading source, build scripts, lock state, logs and JUnit XML. The only command executed was the lock-free static check `python tools\architecture\check_architecture_rules.py` (twice). No Gradle command was run. Floors and counts are a snapshot; Agent sources were still being edited while this file was written (latest observed edit 02:21:40), so re-snapshot immediately before running.

---

## 1. Verdict (GO / NO-GO)

| Decision | Verdict | Reason |
| --- | --- | --- |
| Run **any Gradle task** while the DSH build holds the lock | **NO-GO** | The MCA build lock is one global exclusive file lock. `agent-verify.ps1`, `verify-code.ps1`, `build.ps1`, `release.ps1` all take the same lock. Even `:feature:agent-workspace:testDebugUnitTest` cannot start; it waits, then fails. |
| Run the **lock-free static architecture check** while DSH holds the lock | **GO** | Pure Python, no Gradle, no build outputs. It passed 16/16 at 02:11 and 02:20:05. It does not prove compilation or test behavior. |
| Run the **minimal 3-module Agent lane now** (lock is free, but Agent sources still moving) | **NO-GO** | Agent workspace was edited at 02:14:48–02:21:40; a run now binds a transient, un-frozen snapshot. |
| Run the **minimal 3-module Agent lane after the Agent write set is frozen** | **GO** | This is the only lane that is DSH-free by task dependency graph. Exact command in §3. |
| Run the **app Agent-focused lane now** | **NO-GO** | `:app:testDebugUnitTest` compiles `:app` main plus `:core:dsh-*`, `:api:dsh-gateway`, `:feature:dsh-workspace`. New app Agent code (`AgentLocalModelCatalog.kt`, 02:19:20) has not been compiled at all, and DSH debug outputs predate DSH edits at 02:12:40. |
| Run the **app Agent-focused lane after DSH handoff + Agent freeze** | **GO — conditional** | Still compiles DSH; only run after DSH owner confirms its sources are frozen/handed off. Exact command in §4. |
| Treat 124 workspace tests / 275 Agent-module tests as current | **NO-GO** | The workspace XML (01:40:15) predates 15 Agent source/test files, including 2 new test classes. Only contract (19) + runtime (132) are current. |
| Treat the 01:44 app full run (3110/1 failed) as an Agent PASS | **NO-GO** | The run is red (local-only policy test), and its XML was overwritten at 01:44:50 by a 6-class/74-test DSH-filtered run. Raw log only; not per-class Agent evidence. |

**Overall verdict: NO-GO to run or claim Agent verification right now. GO for the 3-module lane immediately after the Agent write set freezes; GO for the app Agent lane only after the DSH owner hands off/freezes.**

---

## 2. Ownership and build-lock behaviour

### 2.1 Split ownership (as of this snapshot)

| Owner | Write set (representative) | Verification |
| --- | --- | --- |
| **Agent page (this session's side)** | `feature/agent-workspace/**`, `core/agent-contract/**`, `core/agent-runtime/**`, `app/src/*/java/com/muyuchat/mca/agent/**`, `app/src/main/java/com/muyuchat/mca/presentation/AgentModelCatalogAdapter.kt`, `docs/proposals/agent-v1/**` | 3-module lane (§3); app Agent lane only after DSH handoff (§4) |
| **DSH (other session)** | `core/dsh-contract/**`, `core/dsh-runtime/**`, `api/dsh-gateway/**`, `feature/dsh-workspace/**`, `app/src/main/java/com/muyuchat/mca/dsh/**`, `app/src/main/java/com/muyuchat/mca/composition/Dsh*Assembly.kt`, `scripts/dsh/**`, DSH evidence docs | DSH owner's own gates |
| **Shared (one writer at a time)** | `settings.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `scripts/verify-code.ps1`, `scripts/agent-verify.ps1`, `tools/architecture/**`, `architecture/architecture-manifest.json`, `MainActivity.kt`, `MainViewModel.kt`, `composition/MainRuntimeAssembly.kt` | Coordinator decides; the lock serialises processes, not source ownership |

The current worktree is dirty for both owners. Do not use `verify-code.ps1 -Changed` for this split: it selects modules from **tracked** diff only and cannot see the untracked new Agent/DSH modules (hard lock §12.2). Do not revert, clean, or stage another owner's files.

### 2.2 How the lock actually behaves

- Lock path: `E:\MAC\build\.mca-exclusive-build.lock` (`MCA_BUILD_LOCK_PATH` is unset).
- Mechanism: an exclusive OS file handle (`[System.IO.File]::Open(..., FileShare::Read)`). A second writer waits; a live holder is never displaced. A dead holder's PID is reclaimed; `Exit-McaBuildLock` deletes the file.
- All sanctioned Gradle entry points take it: `scripts/agent-verify.ps1` (kind `test`), `scripts/verify-code.ps1` (kind `test`, retries 1800 s per invocation), `scripts/build.ps1` (kind `build`), `scripts/release.ps1` (kind `build`, retries 3600 s), `scripts/verify-ui-quality.ps1`, `tools/architecture/slice.ps1`.
- `agent-verify.ps1` wait: `RetrySeconds = max(60, TimeoutMinutes * 60)`; on timeout it exits 3 with the holder name. `-TimeoutMinutes` changes **only lock wait**, not build/test time.
- A free lock file is not the same as "a build is running". Read the payload and check that PID; do not delete the lock, do not kill a live PID, do not point `MCA_BUILD_LOCK_PATH` at a side lock to bypass serialisation.

Read-only holder check (no Gradle):

```powershell
cd E:\MAC
Import-Module .\tools\build-guards\McaBuildLock.psm1 -Force
$h = Get-McaLockHolder
if ($h) { "HELD pid=$($h.pid) kind=$($h.kind) started=$($h.startedUtc) cmd=$($h.commandLine)" }
else { 'FREE (no live holder)' }
```

### 2.3 Observed history during preparation

- At 02:01:37 the lock was held by pid **47340** (`powershell -File tools\_build_locked.ps1 -LogPath artifacts\build-final8.log`, kind `build`, command line `lead: release notes collapse`), with Gradle wrapper pid **12708** running `:app:assembleRelease -Pmca.abis=arm64-v8a ...`.
- The build finished: `artifacts\build-final8.log` ends `BUILD SUCCESSFUL in 13m 44s` (945 tasks: 41 executed, 904 up-to-date), log mtime 02:15:22. The APK is `app\build\outputs\apk\release\app-release.apk`, 393,695,951 bytes, mtime 02:15:17.
- The lock was absent when checked at 02:19:25, 02:19:50, 02:21:21 and 02:22; no live holder. The exact release instant was between 02:15:22 and 02:19:25.
- **The APK is stale for the current Agent page**: Agent workspace main/test files were edited at 02:14:48–02:21:40, i.e. during/after the build. New classes `AgentRunStatsProjection` and `AgentRunStatsBar` are absent from `feature/agent-workspace/build/tmp/kotlin-classes/release`; new app Agent `AgentLocalModelCatalog` is absent from `app/build`; `AgentModelCatalogAdapter.class` is from 2026-10-19 19:31 (release) / 20:59 (debug). Do not use this APK as the current Agent candidate.

**Answer to "can it run while DSH owns the lock?":** no Gradle command can. Only the static architecture check and read-only XML/source inspection can. If the lock is free but DSH is still editing sources, the app lane is still not safe; the lock only serialises processes.

### 2.4 Post-snapshot update — lock held again at 02:22:35

At **02:22:35**, seconds after this plan's first snapshot, DSH acquired the global lock again for a second release build:

- lock payload: pid **43076**, kind `build`, started `2026-10-19T18:22:36Z` (02:22:36 +08), command line **`DSH final release APK`**;
- wrapper pid **20912** running `:app:assembleRelease -Pmca.abis=arm64-v8a -Pmca.keepNativeSymbols=true`, log `E:\tmp\dsh-final-gates\release3.log`;
- the holder retries the lock for 1200 s and releases in `finally`; do not start any Gradle lane while it is alive.

Agent workspace was still being edited at the same moment (`AgentHomeComposer.kt` 02:22:35), so this second DSH build is **not** guaranteed to contain a coherent Agent page snapshot either. Re-check `Get-McaLockHolder` immediately before every run; the lock may be reacquired again after release. The commands below remain correct for the first moment the lock is genuinely free **and** both owners have frozen their write sets.

---

## 3. Lane A — minimal Agent-only, DSH-free lane

### 3.1 Why these three modules are DSH-free

| Module | Declared project dependencies | Closure | DSH involved? |
| --- | --- | --- | --- |
| `:feature:agent-workspace` | `:core:agent-contract`, `:core:designsystem` | contract + designsystem + external Compose/lifecycle libs | No |
| `:core:agent-runtime` | `:core:agent-contract` | contract + coroutines/Jackson | No |
| `:core:agent-contract` | none | coroutines core | No |
| `:core:designsystem` | no project dependencies | external AndroidX/Compose only | No |

Confirmed by `feature/agent-workspace/build.gradle.kts`, `core/agent-runtime/build.gradle.kts`, `core/agent-contract/build.gradle.kts`, `core/designsystem/build.gradle.kts`, and by a source-import scan: no `com.muyuchat.core.dsh`, `com.muyuchat.api.dsh`, `com.muyuchat.feature.dsh`, or `com.muyuchat.mca` imports in the three Agent modules. Architecture Rule 10 enforces the same allow-list.

Honest residual coupling: Gradle still **configures** every included project, including the DSH build scripts. That is configuration, not compilation or test execution. A broken DSH `build.gradle.kts`/`settings.gradle.kts` can fail Lane A at configuration time; attribute that failure to DSH, do not "fix" it from the Agent side. Do not add `--configure-on-demand` just to hide this: the project gates do not use it, and it is unvalidated for this AGP/Kotlin setup.

### 3.2 Exact command (one lock acquisition)

Run only after **all** of these are true:
1. `Get-McaLockHolder` reports `FREE`.
2. DSH owner has confirmed its build is done (lock alone is not source ownership).
3. The Agent write set has been frozen: no new `feature/agent-workspace/**`, `core/agent-*` or app `agent/**` write for the freeze window; record the latest source mtimes.

```powershell
cd E:\MAC
.\scripts\agent-verify.ps1 -Tasks ':core:agent-contract:testDebugUnitTest',':core:agent-runtime:testDebugUnitTest',':feature:agent-workspace:testDebugUnitTest' -TimeoutMinutes 30 -KeepLog
```

What this compiles/runs: `:core:agent-contract`, `:core:agent-runtime`, `:feature:agent-workspace` debug unit tests, plus `:core:designsystem` compilation. It does **not** compile `:app`, `:feature:chat`, `:core:engine`, `:core:native`, or any `:core:dsh-*` / `:api:dsh-gateway` / `:feature:dsh-workspace` module.

Expected current scope (not an assertion of runtime counts):
- contract: 19 tests, current XML at 01:39:43;
- runtime: 132 tests, current XML at 01:39:54;
- workspace: 17 test classes / 142 static `@Test` methods in source; the XML still says 15 suites / 124 tests at 01:40:15 and is stale (§5–§6). The fresh run will report the real count.

If the workspace task is not up-to-date (it should not be, because sources changed), the XML must be newer than the run start. Contract/runtime may legitimately be UP-TO-DATE; their 01:39 XML is current because no source changed after it.

### 3.3 Fresh-evidence variant (3 lock acquisitions)

The repo already observed that `--rerun` with several test tasks in one invocation re-executed only one task (`scripts/verify-code.ps1` comments around the `-Rerun` lane). For a frozen gate where every XML must be fresh, run one task per invocation:

```powershell
cd E:\MAC
.\scripts\agent-verify.ps1 -Tasks ':core:agent-contract:testDebugUnitTest' -GradleArgs '--rerun' -TimeoutMinutes 20 -KeepLog
.\scripts\agent-verify.ps1 -Tasks ':core:agent-runtime:testDebugUnitTest' -GradleArgs '--rerun' -TimeoutMinutes 25 -KeepLog
.\scripts\agent-verify.ps1 -Tasks ':feature:agent-workspace:testDebugUnitTest' -GradleArgs '--rerun' -TimeoutMinutes 30 -KeepLog
```

Use §3.2 for the minimal post-edit smoke; use §3.3 when the result must be a frozen gate with fresh XML for all three modules.

### 3.4 Optional Agent-only instrumentation compile

This compiles the Agent workspace `androidTest` source set, not a device run. It is DSH-free (same module closure), but it is not part of the minimal JVM gate:

```powershell
.\scripts\agent-verify.ps1 -Tasks ':feature:agent-workspace:compileDebugAndroidTestKotlin' -TimeoutMinutes 25 -KeepLog
```

Device execution of `AgentWorkspaceRouteTest` / `AgentHomeHeaderTest` remains **BLOCKED** (no device run in this session); compiling is not a UI pass.

---

## 4. Lane B — app Agent-focused tests (not Agent-only)

### 4.1 Exact command (only after DSH handoff)

```powershell
cd E:\MAC
.\scripts\agent-verify.ps1 -Tasks ':app:testDebugUnitTest' -Tests 'com.muyuchat.mca.agent.*' -GradleArgs '--rerun' -TimeoutMinutes 45 -KeepLog
```

This runs all 55 app Agent test classes (484 static `@Test` methods at the snapshot), including the new `com.muyuchat.mca.agent.inference.AgentLocalModelCatalogTest` (7 methods, 02:19:50). The real executed count must come from the fresh XML; parameterized/assumption-based cases can differ from the static count.

### 4.2 Why it is not an Agent-only command

`:app:testDebugUnitTest` depends on the whole `:app` main compile and its project dependencies. From `app/build.gradle.kts` (DSH block):

- `:core:dsh-contract`, `:core:dsh-runtime`, `:api:dsh-gateway`, `:feature:dsh-workspace` are `implementation` dependencies of `:app`;
- `app/src/main/java/com/muyuchat/mca/dsh/**` and `composition/Dsh*Assembly.kt` are part of the app main source set;
- `--tests 'com.muyuchat.mca.agent.*'` is an **execution filter**, not a compile filter. Gradle still compiles the app main and every app dependency.

There is no supported task-selection way to remove DSH from that graph. `-x` would skip a required classpath producer and break the task; running the compiled test classes with a hand-built JUnit classpath is not reproducible and is rejected as a gate.

Therefore:
- while DSH is still editing, this lane can fail on DSH compile errors and validate DSH code under an Agent label;
- after DSH handoff, it is acceptable **only if** the kept log shows the DSH compile tasks are `UP-TO-DATE` (or the DSH owner explicitly accepts recompiling them). Check `:core:dsh-*:compileDebugKotlin`, `:api:dsh-gateway:compileDebugKotlin`, `:feature:dsh-workspace:compileDebugKotlin` in the `-KeepLog` log.
- Current risk: `feature/dsh-workspace` main was edited at 02:12:40, after the last debug app compile (newest debug class 01:28:54), so the app lane today would recompile DSH-owned code.

### 4.3 App full-lane red gate — do not use as Agent PASS

Raw log `%TEMP%\mca-verify-test-20976-013018613.log` (ended 01:44:01) records a full app unit-test run:

- `3110 tests completed, 1 failed, 11 skipped`;
- the only failure: `UniversalDeviceAdmissionPolicyTest > firstPartyMainSourcesDoNotGateUsersByDeviceValidation`, `AssertionError` at line 102 (local-only device-admission scan);
- the run wrote many XMLs, but at 01:44:50 a 6-class/74-test DSH-filtered run overwrote `app\build\test-results\testDebugUnitTest` with only `com.muyuchat.mca.dsh.*` XMLs.

This is **raw-log evidence of a red full run**, not per-class Agent evidence. A read-only Python port of the policy scan over the current main sources found 0 violations, but that port is not the real test and cannot close the item. The app full gate stays **FAIL / NOT_RUN for current tree** until a green rerun produces XML. The filtered Lane B can be green independently, but it does not make the full app gate green.

---

## 5. Architecture rules (lock-free, GO now)

```powershell
cd E:\MAC
python tools\architecture\check_architecture_rules.py
```

- Current result at 02:20:05 (and 02:11): **ALL 16 CHECKED ARCHITECTURE RULES PASSED**, exit 0. This ran while the tree had the latest Agent edits and did not use Gradle.
- Agent-relevant rules: **9** agent contract/runtime platform purity, **10** agent runtime/workspace dependency direction, **11** workspace imports, **12** app Agent boundary (no global UI/chat types, `GlobalScope`, `Any?` injection), **13** `MainUiState` / `MainCoordinatorHost` Agent freeze, **14** feature dependency boundaries.
- DSH-relevant rules: **15** DSH contract purity, **16** DSH workspace boundary. Attribute a 15/16 failure to DSH; do not fold it into an Agent verdict.
- Shared rules **1–8** cover the inference contract, private native deps, LOC/LF, local-only excludes, device allow-list keywords, and API/Worker UI decoupling.
- Scope limits: the script checks only the directories it prints, does not compile, does not run tests, and is not a whole-repo dependency proof. It also reads `.git/info/exclude` for Rule 5.
- Do **not** run `python tools\architecture\generate_architecture_manifest.py` as part of this plan: it writes `architecture/architecture-manifest.json` and would create an extra worktree change outside the approved deliverable.

---

## 6. Current JUnit XML inventory (snapshot at 02:22)

Counts are read from the outermost `<testsuite>` in each `TEST-*.xml`. "Newest source" is the newest `.kt` under that module's `src`.

| Module / task | XML suites | Tests | Fail | Err | Skip | XML newest | Newest source/test | Status |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |
| `:core:agent-contract:testDebugUnitTest` | 1 | 19 | 0 | 0 | 0 | 2026-10-20 01:39:43 | 2026-10-19 09:15 | **CURRENT** |
| `:core:agent-runtime:testDebugUnitTest` | 15 | 132 | 0 | 0 | 0 | 2026-10-20 01:39:54 | 2026-10-19 21:43 | **CURRENT** |
| `:feature:agent-workspace:testDebugUnitTest` | 15 | 124 | 0 | 0 | 0 | 2026-10-20 01:40:15 | 2026-10-20 02:21:40 | **STALE** |
| `:app:testDebugUnitTest` | 6 | 74 | 0 | 0 | 0 | 2026-10-20 01:44:50 | app Agent test 2026-10-20 02:19:50 | **STALE / no Agent XML** |
| `:feature:agent:testDebugUnitTest` (old tuning page, out of scope) | 5 | 22 | 0 | 0 | 0 | 2026-10-19 03:30:01 | 2026-10-18/19 | stale, excluded |

Context for ownership, not Agent evidence:

| DSH task | XML suites | Tests | XML newest |
| --- | ---: | ---: | --- |
| `:core:dsh-runtime:testDebugUnitTest` | 16 | 77 | 2026-10-20 01:44:20 |
| `:api:dsh-gateway:testDebugUnitTest` | 12 | 77 | 2026-10-20 01:44:34 |
| `:feature:dsh-workspace:testDebugUnitTest` | 11 | 68 | 2026-10-20 01:44:29 |
| `:app:testDebugUnitTest` (filtered `com.muyuchat.mca.dsh.*`) | 6 | 74 | 2026-10-20 01:44:50 |

**Aggregates:** 19 + 132 + 124 = 275 across 31 suites is a *mixed snapshot*, not one run. Only 19 + 132 = **151** are current. The 124 workspace tests are stale. The app 74 tests are DSH, not Agent; app Agent XML count is **0**.

---

## 7. Stale Agent tests (exact list)

Definition used here: a test is stale if (a) its XML is older than the newest source/test input, (b) its latest run's XML was overwritten by a filtered run, or (c) the class has no XML at all.

### 7.1 Agent workspace — stale (15 files newer than the 01:40 XML)

| File | `@Test` count | Mtime | Evidence |
| --- | ---: | --- | --- |
| `src/test/.../AgentRunStatsProjectionTest.kt` | 9 | 02:18:49 | new; **no XML** |
| `src/test/.../AgentRunStatsProjectionContractTest.kt` | 7 | 02:19:17 | new; **no XML** |
| `src/test/.../AgentWorkspaceProjectionTest.kt` | 18 | 02:16:48 | XML at 01:40 has 16 tests → stale |
| `src/test/.../AgentHomeHeaderContractTest.kt` | 6 | 02:17:49 | XML at 01:40 predates this edit |
| `src/test/.../AgentWorkspaceTestFixtures.kt` | support | 02:16:35 | affects the projection/header suites |
| `src/main/.../AgentQuestionCard.kt` | — | 02:18:29 | UI input changed after XML |
| `src/main/.../AgentTimelineRowItem.kt` | — | 02:18:07 | UI input changed after XML |
| `src/main/.../AgentTimelineCategory.kt` | — | 02:17:52 | UI input changed after XML |
| `src/main/.../AgentWorkspacePreviews.kt` | — | 02:16:51 | UI input changed after XML |
| `src/main/.../AgentRunStatsBar.kt` | — | 02:21:15 | new UI, changed during preparation |
| `src/main/.../AgentWorkspaceProjection.kt` | — | 02:16:00 | projection input changed |
| `src/main/.../AgentRunStatsProjection.kt` | — | 02:15:57 | new production projection |
| `src/main/.../AgentWorkspaceModels.kt` | — | 02:21:40 | model input changed |
| `src/main/.../AgentTaskDetailScreen.kt` | — | 02:15:06 | UI input changed |
| `src/main/.../AgentWorkspaceHomeScreen.kt` | — | 02:14:48 | UI input changed |

Current source has **17 test classes / 142 static `@Test` methods**; the XML has **15 suites / 124 tests**. A file named `AgentRunStatsProjectionSpecTest.kt` existed briefly at 02:15:27 and was gone by 02:19 — additional evidence that the workspace was actively churning.

### 7.2 App Agent-focused — stale / unexecuted

- Current app Agent source: **55 test classes / 484 static `@Test` methods** under `app/src/test/java/com/muyuchat/mca/agent/**`.
- Current app Agent XML: **0**. The only app XML is 6 suites / 74 tests, all `com.muyuchat.mca.dsh.*` at 01:44:50.
- New production since the last app run/APK: `app/src/main/java/com/muyuchat/mca/agent/inference/AgentLocalModelCatalog.kt` (02:19:20) and `app/src/main/java/com/muyuchat/mca/presentation/AgentModelCatalogAdapter.kt` (02:19:27). `AgentLocalModelCatalog.class` is **absent** from `app/build`; `AgentModelCatalogAdapter.class` is from 10-19 19:31 (release) / 20:59 (debug) — both older than the 02:19 source. Current app Agent code has not been compiled.
- New test: `app/src/test/.../AgentLocalModelCatalogTest.kt` (7 `@Test`, 02:19:50) — never executed.
- Two classes were edited after the last full app run at 22:30 and before the 01:44 full run: `EngineAgentStopProofSourceTest.kt` (23:52:40) and `McaAgentLocalModelAdapterTest.kt` (23:44:44). The 01:44 full run did execute the suite (3110 tests) and the only failure was the policy test, but that run's per-class XML is gone and the run is red; do not mark these PASS.
- The old 3079-tests / 11-skipped app evidence at 22:30 is expired and its XML was overwritten. Do not cite it as current.

### 7.3 Instrumentation — absent, not stale-by-XML

- `feature/agent-workspace/src/androidTest/.../AgentWorkspaceRouteTest.kt` (22:17:32) and `AgentHomeHeaderTest.kt` (01:09:45) have no device result. Compiling them (§3.4) is not a UI pass. Device evidence remains **BLOCKED**.

### 7.4 Out of scope but noted

- `:feature:agent` (old “智能调参” page): 5 suites / 22 tests, XML 2026-10-19 03:30:01. It is not the Agent workspace and its build deps include `:core:engine`, `:core:advisor`, `:core:benchmark`, `:core:deviceprofile`, `:core:tuning`. Do not add it to the Agent lane without an explicit scope decision.

---

## 8. Execution record (fill after each real run)

| Step | Command | Required evidence | Result |
| --- | --- | --- | --- |
| S0 lock/holder | §2.2 holder check | `FREE`, no live PID; DSH owner confirmed done | NOT_RUN |
| S1 freeze | `git status --porcelain`, newest Agent mtime | no Agent write during freeze; record HEAD + dirty set | NOT_RUN |
| S2 architecture | `python tools\architecture\check_architecture_rules.py` | exit 0, 16/16; attribute 15/16 to DSH | **PASS 16/16 @02:20:05**; rerun after freeze |
| S3 Lane A (minimal) | §3.2 | exit 0; fresh workspace XML; tests > 0; 0 failures/errors | NOT_RUN |
| S4 Lane A (fresh) | §3.3 | three fresh XMLs newer than run start | NOT_RUN |
| S5 optional androidTest compile | §3.4 | BUILD SUCCESSFUL; compile only | NOT_RUN |
| S6 Lane B | §4.1 | 55 classes / actual XML; DSH task states in kept log | NOT_RUN / blocked on DSH handoff |
| S7 XML count | §8.1 | suites/tests/failures/errors/skipped per module; mtimes | NOT_RUN |
| S8 app full gate | not this plan | green full app XML + policy test resolution | **FAIL** at 01:44 (policy test); current NOT_RUN |

### 8.1 Lock-free XML count command

```powershell
cd E:\MAC
Get-ChildItem core\agent-contract\build\test-results\testDebugUnitTest,
              core\agent-runtime\build\test-results\testDebugUnitTest,
              feature\agent-workspace\build\test-results\testDebugUnitTest -Filter '*.xml' |
  ForEach-Object {
    [xml]$x = Get-Content -LiteralPath $_.FullName -Raw
    [pscustomobject]@{
      Suite    = $x.testsuite.name
      Tests    = [int]$x.testsuite.tests
      Failures = [int]$x.testsuite.failures
      Errors   = [int]$x.testsuite.errors
      Skipped  = [int]$x.testsuite.skipped
      MTime    = $_.LastWriteTime
    }
  } | Sort-Object MTime -Descending | Format-Table -AutoSize
```

For the app lane, change the path to `app\build\test-results\testDebugUnitTest` and filter `TEST-com.muyuchat.mca.agent.*.xml`.

### 8.2 Honest pass criteria

- PASS only when the Gradle exit code is 0, the XML is newer than the run start, `tests > 0`, and `failures = errors = 0`; skipped tests are reported separately, not hidden.
- An UP-TO-DATE task with no fresh XML is not a new run. Either cite the existing XML only when it is newer than every input (contract/runtime), or use §3.3 `--rerun` for a fresh gate.
- Do not combine 19/132/124/3079/3348/3245 into one “all Agent passed” claim; those are different snapshots.
- Do not refresh golden files, delete tests, or widen suppressions to make the app full gate green.
- `verify-code.ps1 -AgentTests` is **not** usable as an Agent-only command: it includes `:app:testDebugUnitTest` (DSH-coupled) and its final app full gate is currently red. Use §3 for the DSH-free lane, §4 for the filtered app lane.
- `verify-code.ps1` fingerprints docs and all dirty/untracked files before/after a run. Finish all agent-v1 documentation edits before starting a `verify-code` gate, otherwise it will report `input-changed-during-run`.

---

## 9. Risks and open items

1. **Agent editing is still active.** Latest observed edit 02:21:40 (`AgentWorkspaceModels.kt`), and `AgentRunStatsBar.kt` at 02:21:15. The counts in §6–§7 will change again; re-run the freeze snapshot and XML inventory before executing.
2. **DSH/release build snapshot.** `BUILD SUCCESSFUL` at 02:15:22 is real for the inputs Gradle saw, but the Agent page changed at 02:14–02:21. The APK/compile outputs are not a current Agent candidate. Record this in any handoff; do not bind device evidence to this APK.
3. **Global lock is not source ownership.** A free lock does not mean DSH is done editing. Lane B compiles DSH code; get explicit DSH handoff and check DSH compile task states.
4. **App policy gate red.** The 01:44 full app run failed the local-only `UniversalDeviceAdmissionPolicyTest` scan. The current-tree Python port found 0 violations, but only a real green app run closes it. Assign an owner; it is not an Agent-module failure and not a reason to weaken the guard.
5. **Configuration-phase coupling.** Lane A still configures DSH projects (build scripts), though it does not compile them. If a DSH build-script edit breaks configuration, attribute it to DSH; do not add unvalidated Gradle flags to mask it.
6. **Device/model/visual evidence** remains NOT_RUN/BLOCKED; this plan only covers JVM unit tests + static architecture.

---

## 10. Copy-paste block (after freeze and DSH handoff)

```powershell
cd E:\MAC

# 0) lock must be free and DSH must have confirmed its build is done
Import-Module .\tools\build-guards\McaBuildLock.psm1 -Force
$h = Get-McaLockHolder
if ($h) { throw "build lock held: pid=$($h.pid) kind=$($h.kind) cmd=$($h.commandLine)" }

# 1) static architecture (no Gradle, safe while any lock is held)
python tools\architecture\check_architecture_rules.py

# 2) minimal DSH-free Agent lane (one lock acquisition)
.\scripts\agent-verify.ps1 -Tasks ':core:agent-contract:testDebugUnitTest',':core:agent-runtime:testDebugUnitTest',':feature:agent-workspace:testDebugUnitTest' -TimeoutMinutes 30 -KeepLog

# 3) only after DSH handoff: app Agent-filtered lane (compiles app + DSH)
.\scripts\agent-verify.ps1 -Tasks ':app:testDebugUnitTest' -Tests 'com.muyuchat.mca.agent.*' -GradleArgs '--rerun' -TimeoutMinutes 45 -KeepLog
```

If a frozen gate needs fresh XML for every module, replace step 2 with the three one-task `--rerun` invocations in §3.3.
