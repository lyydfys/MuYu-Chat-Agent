# One-Shot Verification Preflight — Agent Chat Integration V1

- **Snapshot window:** 2026-10-19 23:37–23:47 (Asia/Shanghai), repo `E:\MAC`
- **Branch / HEAD:** `codex/enterprise-refactor` / `7475fd640e21154dd9543bcae2c83ab112e30361`
- **Mode:** read-only preflight. No Gradle task, native build, test task, APK install, or
  device mutation was performed. The only read-only command executed was
  `python tools/architecture/check_architecture_rules.py` (it reads files and calls
  `git check-ignore`; it does not write).
- **Only file created by this preflight:** this document.
- **Verdict:** the single final gate is **NOT_RUN** and must not be started until the freeze
  conditions in §4 are met. The tree changed *while this preflight was being written*.

> **Do not run the gate now.** At 23:39:25 a foreign DSH test lane took the exclusive build
> lock, and Agent/DSH production and test files were still being written at 23:44:44. A gate
> started in that state is expected to fail with `input-changed-during-run` (verify-code's
> before/after worktree fingerprint), even if every test passes.

---

## 1. Baseline snapshot

### 1.1 Working tree

| Item | Observed |
| --- | --- |
| `git status --porcelain=v1 -uall` | **541 entries at 23:46** (44 modified, 497 untracked, 0 staged, 0 deleted). It was **532** (44 modified, 488 untracked) at 23:37 and **536** at 23:44, so the tree was still moving throughout the preflight. |
| Modified tracked files | 44, including `settings.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`, `scripts/verify-code.ps1`, `tools/architecture/check_architecture_rules.py`, `architecture/architecture-manifest.json`, `core/engine/*`, `feature/chat/*`, `app/.../MainActivity.kt`, `MainViewModel.kt`, `MainRuntimeAssembly.kt`, and six existing app tests. |
| Untracked top-level distribution | 212 `app/`, 112 `core/`, 89 `feature/`, 43 `docs/`, 27 `api/`, 4 `scripts/`, 1 `tools/` |
| New modules present in `settings.gradle.kts` | `:core:agent-contract`, `:core:agent-runtime`, `:core:dsh-contract`, `:core:dsh-runtime`, `:api:dsh-gateway`, `:feature:agent-workspace`, `:feature:dsh-workspace` |
| Submodules | `third_party/llama.cpp` `4ceb1719…`, `third_party/stable-diffusion.cpp` `16b3846a…`, `third_party/tokenizers-cpp` `acbdc5a2…`. `tokenizers-cpp` is **dirty** (5 files, +478/−117): `CMakeLists.txt`, `include/tokenizers_c.h`, `include/tokenizers_cpp.h`, `rust/src/lib.rs`, `src/huggingface_tokenizer.cc`. This dirt is part of the gate fingerprint (hard-lock D08). |
| Local-only guards | `AGENTS.md` and `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` are still in `.git/info/exclude`; architecture Rule 5 passes. |

### 1.2 Build lock and processes

At 23:37 the lock was held by an `agent-verify :app:assembleRelease` lane:
`powershell 25512 → cmd 43584 → gradlew 40972 → daemon 33952`. It completed around
**23:39:17**, producing `app/build/outputs/apk/release/app-release.apk`
(**495,508,924 bytes**) and releasing the lock. `agent-verify.ps1` deletes its log on success,
so no success log was retained for that lane.

At **23:39:25** a different session (`powershell 23412`) acquired the same lock and launched:

```text
gradlew.bat :app:testDebugUnitTest --tests com.muyuchat.mca.dsh.* --console=plain
cmd 30376 → gradlew 15128 → daemon 12508 → test worker 29000
```

That DSH lane was **still running at 23:46**, had written no fresh DSH test XML, and holds the
lock. Lock payload observed:

```json
{
  "schema": "mca.build-lock/1",
  "pid": 23412,
  "kind": "test",
  "startedUtc": "2026-10-19T15:39:25Z",
  "commandLine": ""
}
```

### 1.3 Host resources

| Resource | Observed at 23:42–23:45 |
| --- | --- |
| Physical RAM | 15.64 GiB total; **~1.76–1.85 GiB free** |
| Commit | **~29.99 / 40.66 GiB used** |
| Disk | `C:` **1.9 GiB free** (Gradle home/caches and `%TEMP%`), `E:` 15.4 GiB free |
| Configured heaps | `org.gradle.jvmargs=-Xmx6144m`, `kotlin.compiler.execution.strategy=in-process`, `org.gradle.parallel=false`, `org.gradle.workers.max=1` |
| Gradle daemon 12508 | ~1.5–1.6 GiB working set, 6 GiB max heap |

Previous `%TEMP%\agent-verify-*.log` files already contain the failure modes this configuration
is exposed to: `java.lang.OutOfMemoryError: Java heap space` during
`:core:agent-runtime:compileDebugKotlin`, `Not enough memory to run compilation`,
`app:packageRelease` OOM, and a `Gradle build daemon disappeared unexpectedly` case.

### 1.4 Device state (read-only)

`adb devices -l` (adb is not on `PATH`; use `D:\model\android-sdk\platform-tools\adb.exe`):

| Serial | Product / model | Notes |
| --- | --- | --- |
| `device-b` | `piano` / `25091RP04C` | Android SDK 37, MIUI/HyperOS `V816`; package `com.muyuchat.mca` versionName `0.2.4`, versionCode `8`, `lastUpdateTime=2026-10-19 23:39:54` (same-signature update of the 23:39:17 release APK) |
| `device-a` | `ishtar` / `2304FPN6DC` | A second device is attached; **all adb commands must use `-s`** or they fail with “more than one device/emulator” |

No install, uninstall, clear-data, or instrumentation command was run during this preflight.

---

## 2. The single final gate (exact command)

Run from any shell after the freeze checks in §4 pass:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File E:\MAC\scripts\verify-code.ps1 -AgentTests -Rerun
```

This is the only code-layer gate defined by `docs/proposals/agent-chat-integration-v1/C00_BASELINE.md`
§4 item 3. The script supplies the JDK/QNN defaults itself; if the launching shell overrides
them, set these first:

```powershell
$env:JAVA_HOME         = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1'
$env:MCA_QNN_SDK_ROOT  = 'E:\tools\qairt-sdk\2.45.0.260326'
```

Companion read-only gate (fast; run immediately before the frozen gate, not instead of it):

```powershell
python E:\MAC\tools\architecture\check_architecture_rules.py
```

Do **not** replace `verify-code.ps1` with `agent-verify.ps1` for this gate.
`agent-verify.ps1` defaults to killing every Gradle/Kotlin Java process it can match machine-wide
(a documented problem, P17 in `PROBLEMS_AND_ACCEPTANCE.md`) and does not perform the
before/after worktree fingerprint. `verify-code.ps1` is the controlled lane.

### 2.1 What the command does

1. Imports `tools/build-guards/McaBuildLock.psm1` and fingerprints the **entire dirty/untracked
   worktree** (HEAD, every changed/untracked file content, submodule dirt, the two local-only
   guard files).
2. Refuses to start if a compiler/native process is running with no lock holder
   (`Assert-McaBuildSlot`).
3. Takes `build/.mca-exclusive-build.lock` for each Gradle invocation (30-minute wait limit),
   runs the compile invocation, then each test task separately with `--rerun`.
4. Recomputes the fingerprint at the end. Any change during the run fails the result with
   `input-changed-during-run`.

### 2.2 Expected Gradle task list

| Phase | Invocation (one Gradle call each) | Tasks |
| --- | --- | --- |
| Compile (incremental) | 1 | `:app:compileDebugKotlin`, `:core:engine:compileDebugKotlin`, `:api:local:compileDebugKotlin` |
| Agent tests | 2a | `:core:agent-contract:testDebugUnitTest --rerun` |
| | 2b | `:core:agent-runtime:testDebugUnitTest --rerun` |
| | 2c | `:feature:agent-workspace:testDebugUnitTest --rerun` |
| | 2d | `:app:testDebugUnitTest --rerun` |

All are launched with `-p E:\MAC`, `--console=plain`; the tests also get `--rerun` (not
`--rerun-tasks`, which would rebuild native code). `:app:compileDebugKotlin` pulls the whole app
dependency graph, including the untracked Agent and DSH modules.

`-AgentTests` deliberately does **not** include:

- `:feature:agent:testDebugUnitTest` (5 existing test classes, last run 03:30)
- `:core:dsh-runtime:testDebugUnitTest`, `:api:dsh-gateway:testDebugUnitTest`,
  `:feature:dsh-workspace:testDebugUnitTest`
- `:app:assembleRelease` / `:app:assembleDiagnostic`
- `:app:connectedDebugAndroidTest` or any `compile.*AndroidTestKotlin` task
- any native/model/device test

Those are separate gates or are `NOT_RUN`/`BLOCKED` for this snapshot.

### 2.3 Expected result shape

- Exit code **0**.
- Four `BUILD SUCCESSFUL` test invocations plus one compile invocation.
- Last line: `VERIFY OK (code layer only - this is not the release build)`.
- Aggregate line (observed format):
  `reports (this run only): tests=3348 failures=0 errors=0 skipped=11`.
- **3348 is a historical sum, not a contract**:
  - last app XML: **3079 tests / 0 failures / 11 skipped** at 22:30:37
  - contract **19**, runtime **132**, workspace **118** (separate earlier runs)
  - the app suite currently contains **75** DSH tests and its source changed at 23:38–23:44;
    the fresh number may differ. Record the actual per-directory XML counts, never force 3348.

Note: `verify-code.ps1` counts XML files whose mtime is newer than “20 minutes ago” at the end of
the run. If the gate takes longer than 20 minutes (possible under the memory pressure in §1.3),
early modules can fall outside that window and the printed aggregate can undercount. A fresh
per-module XML check is required; the aggregate is not the whole evidence.

---

## 3. Preflight observations (what was actually run)

| Check | Command | Observed | Status |
| --- | --- | --- | --- |
| HEAD / branch | `git rev-parse HEAD`, `git status` | `7475fd64…`, 541 dirty entries at 23:46 (still increasing) | PASS (snapshot only) |
| Build lock | read `build/.mca-exclusive-build.lock` + `Get-Process -Id <pid>` | live holder 23412, DSH test lane, since 23:39:25 | **BLOCKED** |
| Java/Gradle processes | `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` | daemon 12508 + wrapper 15128 + test worker 29000 | **BLOCKED** (foreign lane) |
| Memory | `Win32_OperatingSystem` | 1.76–1.85 GiB free, 29.99/40.66 GiB commit | FAIL (risk) |
| Disk | `Get-PSDrive` | `C:` 1.9 GiB free | FAIL (risk) |
| Architecture rules | `python tools/architecture/check_architecture_rules.py` | **`ALL 16 CHECKED ARCHITECTURE RULES PASSED (EXIT=0)`** at ~23:41 | PASS for that snapshot, **now stale** (tree changed at 23:42:43) |
| Local-only exclusions | part of architecture Rule 5 | both paths ignored | PASS |
| Single final gate | §2 command | not started | **NOT_RUN** |
| Release APK on current tree | — | APK is 23:39:17; DSH source changed 23:39:34 | **NOT_RUN / stale** |
| Device instrumentation | — | not run | **BLOCKED** |

The architecture result is real, but it was computed before the 23:42–23:44 edits. Re-run it on
the frozen tree; do not carry this preflight PASS into the final report as the final-tree result.

---

## 4. Freeze conditions (mandatory before the gate)

All of the following must be true at the same time; otherwise the gate is not a one-shot gate:

1. **No live lock holder.** Read the payload and verify the recorded PID is alive. If the PID is
   dead, the lock is stale and the next `Enter-McaBuildLock` reclaims it; if alive, wait.
2. **No Gradle/Kotlin/test worker process** for this checkout: no `GradleWrapperMain`,
   `GradleDaemon`, `KotlinCompileDaemon`, `GradleWorkerMain`, no `cmake`/`ninja`/`link`.
3. **No writer.** No Agent/DSH/front-end session is editing files (tracked, untracked, tests, or
   docs). This includes screenshots and evidence files: the fingerprint includes them when they
   are untracked.
4. **No device work is running** against the same APK/build directory (`adb install`,
   instrumentation) while the gate is compiling.
5. **Memory headroom.** Close other editors/agents; do not start with ~1.8 GiB free if it can be
   avoided. Do not edit `gradle.properties` to “fix” this for the gate; that changes the input
   snapshot and masks the configured gate.
6. **Capture the start fingerprint** and keep it outside the repo (`$env:TEMP`).

### 4.1 Read-only freeze checks

```powershell
$repo = 'E:\MAC'
Set-Location $repo

# 1. tree snapshot
git rev-parse HEAD
(git status --porcelain=v1 -uall | Measure-Object -Line).Lines

# 2. lock holder (file may be absent when free)
$lock = Join-Path $repo 'build\.mca-exclusive-build.lock'
if (Test-Path -LiteralPath $lock) {
    $h = Get-Content -LiteralPath $lock -Raw | ConvertFrom-Json
    "LOCK pid=$($h.pid) kind=$($h.kind) since=$($h.startedUtc) cmd=$($h.commandLine)"
    Get-Process -Id $h.pid -ErrorAction SilentlyContinue
} else { 'LOCK FREE' }

# 3. Gradle/Kotlin/native activity
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'GradleWrapperMain|GradleDaemon|KotlinCompileDaemon|GradleWorkerMain' } |
    Select-Object ProcessId, ParentProcessId, CreationDate, CommandLine
Get-Process cmake,ninja,link -ErrorAction SilentlyContinue

# 4. resources
Get-CimInstance Win32_OperatingSystem |
    Select-Object @{n='FreeGB';e={[math]::Round($_.FreePhysicalMemory/1MB,2)}},
                  @{n='CommitGB';e={[math]::Round(($_.TotalVirtualMemorySize-$_.FreeVirtualMemory)/1MB,2)}}
Get-PSDrive C,E | Select-Object Name,@{n='FreeGB';e={[math]::Round($_.Free/1GB,1)}}

# 5. devices (read-only; -s is mandatory because two devices are attached)
& 'D:\model\android-sdk\platform-tools\adb.exe' devices -l
```

### 4.2 Fingerprint (start / end)

```powershell
powershell.exe -NoProfile -File E:\MAC\tools\build-guards\McaWorktreeFingerprint.ps1 `
  -RepoRoot E:\MAC -EmitJson
```

The gate itself computes this internally; running it before/after independently makes the
one-snapshot claim auditable. The fingerprint must be identical before and after. It excludes
only `.git`, `build`, `.gradle`, `.cxx`, `node_modules`, `artifacts`, `.idea`; **docs, scripts,
tests, screenshots and the staged DSH payload are inputs**.

---

## 5. Known blockers

### B1 — Foreign DSH lane + active writers (immediate)

- At 23:39:25 a foreign DSH verification lane acquired the lock and launched
  `:app:testDebugUnitTest --tests com.muyuchat.mca.dsh.*`; it was still running at 23:45.
  `verify-code.ps1` will wait up to 1800 s for the lock and then fail as `compile-error` if the
  lane does not end.
- More importantly, the tree was modified **after** that lane started and after the architecture
  check:
  - `app/.../agent/inference/AgentStopProofSource.kt` 23:42:43
  - `app/.../agent/inference/McaAgentLocalModelAdapter.kt` 23:43:25
  - `app/.../composition/AgentLeaseRunStopSettlementSource.kt` 23:43:44
  - `app/.../agent/composition/AgentRuntimeAssembly.kt` 23:44:00
  - `app/.../composition/MainRuntimeAssembly.kt` 23:44:13
  - `app/src/test/.../agent/inference/McaAgentLocalModelAdapterTest.kt` 23:44:44
  - `app/.../agent/inference/AgentStopProofSource.kt` 23:45:03 (again)
  - `app/src/test/.../agent/inference/EngineAgentStopProofSourceTest.kt` 23:45:33
  - `app/src/test/.../composition/AgentLeaseRunStopSettlementSourceTest.kt` 23:45:51
  - `core/dsh-runtime/.../DshRuntimeOwner.kt` 23:45:57
  - `core/dsh-runtime/...` and `docs/.../evidence/*.png` at 23:40–23:45
- A gate started now would compile a moving target and then fail `input-changed-during-run`;
  even if the test XML is green, the result belongs to no single snapshot.
- **Action:** finish and stop all Agent/DSH writers, let the DSH lane end (or wait for its lock
  release), re-run the architecture check, then start the gate immediately.

### B2 — Foreign DSH changes are inside the gate's compile/test closure

- DSH is not a separate checkout: `settings.gradle.kts` includes 4 DSH modules and `:app` depends
  on `:core:dsh-contract`, `:core:dsh-runtime`, `:api:dsh-gateway`, `:feature:dsh-workspace`.
- `-AgentTests` runs the **whole** `:app:testDebugUnitTest`, which currently includes 75 DSH app
  tests; a DSH compile or test failure therefore fails the “Agent” gate for a foreign reason.
- `check_architecture_rules.py` has grown from the documented **14** rules to **16** (new Rules
  15/16 for DSH contract/workspace). The current output is `ALL 16 CHECKED ARCHITECTURE RULES PASSED`.
- This is acceptable only if the gate report states the actual scope: code-layer Agent+app+DSH
  compile/test, not DSH device/runtime acceptance, and not an Agent-only suite.
- If the DSH tree must stay out of scope, it cannot be removed by hiding modules or editing the
  checker for this gate; that would change the input and violate the hard lock. The honest option
  is to freeze DSH and report that the gate covers it.

### B3 — Memory / OOM / disk

- 15.64 GiB physical, ~1.8 GiB free, ~30/40.7 GiB commit, `C:` 1.9 GiB free.
- The gate uses a 6 GiB Gradle heap with in-process Kotlin and compiles 352 app unit-test source
  files plus the Agent/DSH graph. Prior logs show heap OOM, `Not enough memory to run
  compilation`, daemon disappearance, and `packageRelease` OOM on this machine.
- The gate has **no internal timeout** and runs 5 Gradle invocations; under paging it can take
  tens of minutes. If the invoking tool has a short timeout it may kill the gate mid-run.
- **Action:** run it with a generous lifetime / managed background job and an external transcript;
  do not run a second Gradle or heavy agent concurrently. A reboot or freeing `C:` is preferable
  but is an environment action, not a gate change.

### B4 — Device install/validation limits

- Two devices are attached; use `-s device-b` (primary) or `-s device-a`, never bare `adb`.
- On the MIUI/HyperOS device, a new package install is blocked by
  `INSTALL_FAILED_USER_RESTRICTED`; only a same-signature **update** of the installed
  `com.muyuchat.mca` is known to work. Do not uninstall, clear data, or change applicationId to
  bypass this.
- The 23:39:17 release APK was already installed at 23:39:54, but DSH source changed at 23:39:34,
  so that APK is **not** bound to the current tree. Do not cite it as the current candidate.
- Instrumentation, real model runs, FGS/notification, SAF, TalkBack and visual checks remain
  `NOT_RUN` / `BLOCKED`. They are outside the single JVM gate and must stay marked that way.

### B5 — Stale docs and stale test snapshots

The docs below are the main way a *correct* gate gets reported as a failure or, worse, a
historical pass gets copied into the current report:

| File:line | Stale claim | Actual at this snapshot |
| --- | --- | --- |
| `C00_BASELINE.md:8` | Starting HEAD `98eb8e47…` | `7475fd64…` |
| `C00_BASELINE.md:10-12` | 26 modified + 15 untracked, 41 status lines | 44 modified + 492 untracked, 536 lines (and moving) |
| `C00_BASELINE.md:60` | Architecture `14/14` | Checker declares and passes **16** rules (Rules 15/16 are DSH) |
| `V1_1_STATUS.md:20` | `:app:assembleRelease` success 22:42, 949 tasks | Later build wrote a 495 MB APK at 23:39:17; no retained success log |
| `V1_1_STATUS.md:23-24` | “一次性汇总” 3348 (app 3079, workspace 118, runtime 132, contract 19) | Those XMLs are from **separate** runs (16:10, 21:47, 22:19, 22:30); the one-shot gate was never run |
| `V1_1_STATUS.md:25` | `check_architecture_rules.py` exit 0, 14 rules | 16 rules |
| `docs/adr/0002-agent-v1-runtime-and-delivery.md:149,153` | 14/14; 3245 tests / 11 skipped | 16/16; current tree has 3079 app tests (before the 23:38–23:44 edits) |
| `docs/proposals/agent-v1/MCA_AGENT_V1_HANDOFF.md:10` | PASS: 3245 tests, 14/14 | Historical; not current, and not a one-shot run |
| `docs/proposals/agent-v1/evidence/5e912ff9/card-status.md:24,27,29` | 3245; 14/14; “runtime 123” | Current runtime is 132; architecture is 16 |
| `docs/proposals/agent-v1/evidence/5e912ff9/verification-2026-10-19.md:53` | 3245 tests | Historical |

`PROBLEMS_AND_ACCEPTANCE.md:19-20` already labels 3245 as a historical 16:10–16:13 window and is
the correct model: keep it as history, do not promote it. `PROBLEMS_AND_ACCEPTANCE.md:160`
explicitly records the 3121/3168/3245 drift.

**Stale test evidence (not a proven failing test):**

| Suite | Last XML | Inputs changed after that run | Conclusion |
| --- | --- | --- | --- |
| `:app:testDebugUnitTest` | 22:30:37 — 3079 tests, 0 failures, 11 skipped | Agent adapter/test/fixtures 23:42–23:44; DSH app sources/tests 23:38–23:39; feature/chat 22:50–23:30 | **stale snapshot**; re-run required |
| `:feature:agent-workspace` | 22:19:52 — 118 tests, 0 failures | `AgentWorkspace…ui` 23:18:51 | **stale snapshot**; re-run required |
| `:core:agent-runtime` | 21:47:45 — 132 tests, 0 failures | runtime source latest 21:43:36 | fresh relative to that source, but the gate re-runs it |
| `:core:agent-contract` | 16:10:33 — 19 tests, 0 failures | contract source latest 09:15:19 | fresh relative to that source, but the gate re-runs it |
| `:feature:agent` | 03:30 — 5 classes | not in `-AgentTests` | **coverage gap**, not run by this gate |
| `app/src/androidTest` (4 new classes) | never | device blocked | **NOT_RUN/BLOCKED** |

No statically identifiable test currently contradicts the source. The previously failing tests
did pass in the 22:30 XML: `MainShellMotionContractTest` 6/0, `ChatSearchCancellationContractTest`
4/0, `McaAgentLocalModelAdapterTest` 14/0, `ChatStopGenerationOperationsTest` 12/0,
`RoomSchemaContractTest` 7/0, `KnowledgeBaseRoomContractTest` 13/0, `WorldBookStoreTest` 16/0,
`ModuleBoundaryTest` 6/0, `ModuleBoundaryContractTest` 10/0. The problem is that all of those
results predate the current edits, not that a known assertion is wrong.

The only **doc-level** checks that will “fail” against reality are the hard-coded `14/14` and
`3245` expectations. Do not edit the checker or the tests to make them say 14/3245; update the
evidence text, not the gate.

---

## 6. PASS / NOT_RUN evidence table template

Copy this table into the final report and fill `Actual`, `Status`, `Evidence`, `Notes` from the
frozen run. Allowed statuses: `PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`.

| ID | Check | Command / source | Expected | Actual | Status | Evidence (path + revision/mtime) | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| E01 | Tree frozen / baseline | `git rev-parse HEAD`; `git status --porcelain=v1 -uall` | Same HEAD and entry count before/after; no writer | | NOT_RUN | | Record entry count; fingerprint is authoritative |
| E02 | Build lock free | read `build/.mca-exclusive-build.lock` + check PID | No live holder | | NOT_RUN | | A stale file with dead PID is not a blocker |
| E03 | No foreign Gradle/native activity | `Win32_Process` filter; `Get-Process cmake,ninja,link` | Empty (except idle daemon) | | NOT_RUN | | DSH lane must be gone |
| E04 | Memory/disk headroom | `Win32_OperatingSystem`; `Get-PSDrive C,E` | Enough free RAM/commit and `C:` space | | NOT_RUN | | Report exact values; no config changes |
| E05 | Architecture rules | `python tools/architecture/check_architecture_rules.py` | `ALL 16 CHECKED ARCHITECTURE RULES PASSED`, exit 0 | | NOT_RUN | | Docs saying 14 are stale |
| E06 | Compile | `:app:compileDebugKotlin`, `:core:engine:compileDebugKotlin`, `:api:local:compileDebugKotlin` | `BUILD SUCCESSFUL` | | NOT_RUN | | Includes untracked Agent/DSH graph |
| E07 | Contract tests | `:core:agent-contract:testDebugUnitTest --rerun` | 19 tests, 0 failures/errors | | NOT_RUN | | Fresh XML required |
| E08 | Runtime tests | `:core:agent-runtime:testDebugUnitTest --rerun` | 132 tests, 0 failures/errors | | NOT_RUN | | Fresh XML required |
| E09 | Workspace tests | `:feature:agent-workspace:testDebugUnitTest --rerun` | 118 tests, 0 failures/errors | | NOT_RUN | | Fresh XML required |
| E10 | App tests | `:app:testDebugUnitTest --rerun` | ~3079 tests, 0 failures/errors, 11 skipped (re-derive) | | NOT_RUN | | Includes 75 DSH tests |
| E11 | Aggregate / exit | `verify-code.ps1 -AgentTests -Rerun` | `VERIFY OK`, exit 0, tests > 0 | | NOT_RUN | | Save full transcript outside repo |
| E12 | Input unchanged during run | `McaWorktreeFingerprint.ps1` before/after | Identical fingerprint | | NOT_RUN | | Any file edit ⇒ `input-changed-during-run` |
| E13 | Release candidate APK | `:app:assembleRelease` on frozen tree | APK + manifest/hash bound to fingerprint | | NOT_RUN | | 23:39 APK is stale (DSH edit 23:39:34) |
| E14 | Device install / upgrade | `adb -s device-b …` | Same-signature update; no data loss | | BLOCKED | | MIUI `INSTALL_FAILED_USER_RESTRICTED` for new package |
| E15 | Instrumentation / real UI | connected tests | Actual run on device | | BLOCKED | | 4 new androidTest classes never run |
| E16 | Local model / FGS / SAF / TalkBack | device runtime | Actual evidence | | NOT_RUN | | Out of scope of the JVM gate |
| E17 | Stale-doc correction | review this file and proposal docs | No 14/14, 3245, or 98eb8e4 claims in current report | | NOT_RUN | | Historical evidence stays labeled historical |
| E18 | `feature:agent` suite | `:feature:agent:testDebugUnitTest` | 5 classes, 0 failures (if claimed) | | NOT_RUN | | Not covered by `-AgentTests` |

### 6.1 Minimum acceptable final statement

The strongest honest statement after a successful run is:

> Code-layer gate PASS for the frozen fingerprint `<sha256>` at HEAD `<sha>`:
> compile + contract/runtime/workspace/app tests, 0 failures/errors, architecture 16/16.
> Device instrumentation, release-candidate binding, real models, FGS/SAF/TalkBack/visual
> evidence: `NOT_RUN`/`BLOCKED`. This is not a release or production PASS.

If the fingerprint changed, the statement must be `FAIL — input-changed-during-run`, regardless
of test counts.

---

## 7. Runbook (one shot)

1. **Stop the writers.** Ask the DSH/Agent/front-end sessions to pause; wait for the current DSH
   lane to release `build/.mca-exclusive-build.lock`; confirm no Gradle/Kotlin/native process.
2. **Free resources.** Close heavy applications; confirm ~memory/disk headroom. Do not edit
   `gradle.properties` or scripts for this run.
3. **Freeze and record.** Run §4.1 checks; run the fingerprint CLI and save its JSON under
   `%TEMP%`; record `git rev-parse HEAD`, `git status` count, lock payload, process list.
4. **Run the architecture gate** on the frozen tree and save stdout.
5. **Run the single final gate** exactly as §2, with an external transcript:

   ```powershell
   $log = Join-Path $env:TEMP ('mca-agent-final-gate-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
   powershell.exe -NoProfile -ExecutionPolicy Bypass `
     -File E:\MAC\scripts\verify-code.ps1 -AgentTests -Rerun *>&1 |
     Tee-Object -FilePath $log
   $code = $LASTEXITCODE
   "EXIT=$code LOG=$log"
   ```

   `verify-code.ps1` deletes its internal `%TEMP%\mca-verify-*.log` on success, so this external
   transcript is the only durable log.
6. **Post-check.** Confirm exit 0, final `VERIFY OK`, fresh XML timestamps, and an identical
   fingerprint. If the fingerprint differs, mark FAIL/`input-changed-during-run`; do not
   cherry-pick green XML.
7. **Fill §6** from raw XML per module, not only from the aggregate line. Record `tests`,
   `failures`, `errors`, `skipped`, and the XML directory mtimes.
8. **Keep the device rows separate.** The JVM gate does not make E14–E16 PASS.

---

## 8. Explicit NOT_RUN / BLOCKED list for this preflight

- `scripts/verify-code.ps1 -AgentTests -Rerun`: **NOT_RUN** (forbidden in this preflight and
  currently blocked by the live DSH lock + moving tree).
- Any Gradle compile/test/assemble: **NOT_RUN**.
- Any native/CMake/model run: **NOT_RUN**.
- Any APK install/uninstall/clear-data/instrumentation: **NOT_RUN**; device upgrade remains
  **BLOCKED** by MIUI install policy for new packages.
- Real local model, cloud, MCP server, FGS/notification, SAF, TalkBack, visual/performance:
  **NOT_RUN**.
- The 23:41 architecture PASS: valid for that instant only; after the 23:42:43+ edits it is
  **stale** and must be re-run on the frozen tree.
- The 22:30 app XML (3079/0/0/11) and the earlier module XMLs: **historical evidence**, not a
  result for the current fingerprint.

---

## 9. Bottom line

- The gate command and task list are stable and known.
- The tree is **not frozen** and a foreign DSH test lane holds the build lock.
- Memory and `C:` disk are tight enough that OOM/daemon loss is a realistic failure mode.
- Device validation cannot pass on this host as configured; it must remain `NOT_RUN/BLOCKED`.
- The documentation currently mixes 14 vs 16 rules and 3245 vs 3348 vs 3079 counts; only a fresh
  frozen run can produce the final numbers.
