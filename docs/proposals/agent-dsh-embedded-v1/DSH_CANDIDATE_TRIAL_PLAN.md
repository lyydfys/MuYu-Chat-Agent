# DSH Candidate Trial Plan — Candidate Install, Isolated Rehearsal, Switch, Rollback

Status: **READ-ONLY DESIGN / PROPOSAL**. No code, build, Gradle task, test, APK, device operation, DSH launch or payload staging was performed for this document. Every runtime result below is `NOT_RUN`; source statements are `STATIC` at the recorded baseline. Planned tests are `PLANNED`, not executed.

Date: 2026-10-20 (Asia/Shanghai).

| Baseline item | Value |
| --- | --- |
| Repository / branch | `E:\MAC` / `codex/enterprise-refactor` |
| HEAD | `af2489909117edfa88721816a5fa40f5a4a5df9c` |
| Working tree | dirty (82 porcelain entries); `core/dsh-contract/`, `core/dsh-runtime/`, `feature/dsh-workspace/`, `api/dsh-gateway/`, `app/.../dsh/`, `scripts/dsh/` are untracked |
| Submodules | `llama.cpp 4ceb1719…`, `stable-diffusion.cpp 16b3846a…`, `tokenizers-cpp acbdc5a2…` |
| targetSdk / minSdk | `36` / `26`; packaged ABI `arm64-v8a` |
| Only file created | this document |

Sources used:

- [DSH_DATA_AND_RUNTIME_TRIAL.md](DSH_DATA_AND_RUNTIME_TRIAL.md) §5.3–§5.5, §7 R1/R2/R4, §8 M3/M4/M5/M6.
- [DSHA_ADOPTION_GAP_MATRIX.md](DSHA_ADOPTION_GAP_MATRIX.md) §2.3, §3 G7/G8, §4 P1-1/P1-2 and its dependency note.
- [DSHA_MULTI_PROJECT_REVIEW_20261019.md](DSHA_MULTI_PROJECT_REVIEW_20261019.md) §3.6 (`RuntimeTrial` / `ManagedRuntimeTransaction`) and §6 P3 (candidate → isolated rehearsal → stop barrier → switch → health confirmation → commit/rollback).
- targetSdk 36 execution packaging: [libs.versions.toml](../../../gradle/libs.versions.toml), [scripts/dsh/README.md](../../../scripts/dsh/README.md), [app/build.gradle.kts](../../../app/build.gradle.kts), [DshNodeExecStrategy.kt](../../../core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/process/DshNodeExecStrategy.kt).

## 0. Scope, P1 mapping and non-goals

### 0.1 What "P1" means in this plan

This document implements the **candidate-trial P1 slice**, not the whole adoption matrix:

| Source | Item | Included here |
| --- | --- | --- |
| Runtime audit §8 | **M4 (P1)** — isolated candidate trial before promotion | Yes, the core of this plan |
| Runtime audit §8 | **M3 (P0/P1)** — last-known-good payload + post-READY commit | Yes, as the switch/rollback half of this plan |
| Adoption matrix §4 | **P1-1 (G7)** — candidate tree + trial home + random port + real gateway; no business tools; stop/residue check before rename | Yes |
| Adoption matrix §4 | **P1-2 (G8)** — candidate = APK-shipped payload; no downloader | Yes |
| Runtime audit §8 | **M5 (P1)** — version/snapshot and export/import | Only the pre-migration copy; SAF export/import is **not** included |
| Runtime audit §8 | **M6 (P1)** — verify managed runtime before executing | Manifest path match + `libdshnode.so` hash before trial; full-tree reuse sentinel is **not** included |
| DSHA review §6 | **P1** — plugin/tool compatibility fact list | **No.** This is a different track. A green trial must not be cited for plugins, tools, MCP, PTY, native addons or `dsh-web-mobile`; those stay `NOT_RUN`. |

The DSHA review's §6 P3 flow is the reference sequence: `candidate → isolated real trial → stop barrier → switch → health confirmation → commit/rollback`. This plan keeps that sequence but binds it to MCA's existing single owner, epoch and readiness gate.

**Alignment with the report's §6 P1:** the trial deliberately does not install, enable or execute plugins/tools. It therefore neither preempts nor weakens the P1 compatibility-fact work; the P1 matrix (DSH API version, Node, npm/system deps, native ABI, shell, permissions, locks, cancellation) remains the only gate for plugin claims. A green trial must be reported as `plugins=NOT_RUN`.

### 0.2 Non-goals

- No in-app downloader, version negotiation, payload mirror, online candidate or second payload source.
- No second `DshRuntimeOwner`, `:dsh` service, gateway, epoch stream or app-level controller.
- No WebView/Compose trial, renderer probe, screenshot or visual change. A trial cannot see a painted frame.
- No copy of the real DSH home into the trial; no second writer for sessions/storages; no Room/JSONL migration.
- No plugin manager, capability bridge, SAF export/import, user-facing rollback button or reset UI.
- No targetSdk/minSdk/ABI change; no new asset/jniLib; no change to `scripts/dsh/stage.ps1`, `payload-lock.json` or `MANIFEST.json`.
- No automatic APK downgrade; no claim that a payload rollback restores the old Node executable.

### 0.3 targetSdk 36 hard constraints

| Constraint | Consequence |
| --- | --- |
| For DSH payload binaries, only the PackageManager `nativeLibraryDir` ELF is exec-capable (directly or via `/system/bin/linker64`; `DshNodeExecStrategy`; `scripts/dsh/README.md:59-89`; `app/build.gradle.kts:350-356`). | The trial executes the **same** `nativeLibraryDir/libdshnode.so` as production. Never copy Node into `files/dsh`, never `chmod +x`, never `Runtime.exec` on `files/dsh/**`, never execute `payload/runtime/bin/node`. |
| The extracted candidate tree is read/`dlopen`ed, not exec'd. | The trial passes the candidate `dshroot/.../bin.js` as a Node argument and points `LD_LIBRARY_PATH` at the trial `runtime/lib`, exactly like production. |
| Wrapper scripts (`pnpm`, `npm`, `bash` shims) are not executable from app storage. | The trial runs no tools/scripts and does **not** call `prepareRuntimeLinks`; that method mutates the active payload tree. Tool/plugin compatibility is report §6 P1, not this slice. |
| A candidate exists only as an APK asset; installing the new APK already replaced `nativeLibraryDir`. | The trial validates the **new kernel tree with the new Node**; it cannot validate or restore the **old Node**. True Node-level rollback = install the previous signed APK. |
| One packaged ABI (`arm64-v8a`), no device admission list. | The real `node --version` probe remains the authority. No model/chipset/profile gate is added; only concrete runtime conditions fail. |

## 1. Baseline recalibration (do not reuse the older audit blindly)

The 2026-10-19 audit recorded HEAD `7475fd6`. Current source is `af24899`; re-check before implementing:

| Older gap | Current static state | Effect on this plan |
| --- | --- | --- |
| G1: host `cwd` = payload tree | `DshRuntimeOwner.startInternal` launches with `config.workspaceDir`; `DshRuntimeOwnerTest.readyGenerationIsAttachedNotRestartedAndStopsCleanly` asserts it. | P0-1 is **static-fixed**. Trial and real host run in a workspace, never in a verified payload tree. Runtime `NOT_RUN`. |
| G4: client `stop()` masks `STOP_TIMEOUT` | `DshRuntimeServiceClient.stop()` now applies the service reply snapshot and only synthesizes `STOPPED` when no reply exists. | Do not re-implement. The trial still needs its own stop/residue check because a trial host is not the owner's `host`. |
| G3: readiness evidence compressed | `DshStartupStage` / `DshStartupRecorder` exist and are epoch-scoped; the gate still reports most sub-failures as `LOADER_NOT_READY`. | Record the gate's typed stages and `Ready` outcome; do not claim per-probe diagnostics. |
| G7: no candidate trial, health journal or rollback | Still true: one `payload/` slot, previous deleted at swap, no trial home/port/nonce, no rollback API. | This is the slice. |
| G2 / M1: missing home looks like first run | Still true: the seeder treats a missing home as first run; no home state marker. | **Hard prerequisite S0**; without it the migration rules in §6 are unsafe. |

## 2. Reused seams and exact write set

### 2.1 Reuse (no second runtime)

| Existing seam | Reused for |
| --- | --- |
| `DshPayloadInstaller.ensureInstalled()` (`DshPayloadInstaller.kt:57-83,85-177`) | Archive size/SHA-256, extraction, path-traversal rejection, file count, critical-file hashes, marker write. The trial constructs a second installer with a **trial config** and the same `AssetDshPayloadSource`; it writes only into `trial/<nonce>/`, never `payload/`. |
| `DshPayloadManifest` / `DshPayloadMarker` | Candidate identity and active identity; unchanged on-disk format for the active marker. |
| `DshRuntimeConfig` (`:60-65`, paths; `:35-58`, timeouts) | Trial paths via `forTrial(trialRoot)`; production paths and timeouts unchanged. |
| `DshNodeCommand.arguments/environment` (`:26-92`) | Trial argv and isolated env; only the directory roots change. |
| `DshNodeExecStrategy` / `DshProcessRunner` | One real exec strategy choice for both trial and real host; no device-model check. |
| `AndroidDshNodeHost` (`:54-80`) | Trial Node process, auth-line parsing, bounded redacted output, SIGTERM→SIGKILL tree stop. |
| `DshReadinessGate` (`:67-206`) | Auth exchange, cookie/HttpOnly, index marker, bootstrap JS, stability, unauthenticated 401. |
| `DshGatewayConfigSeeder.seed()` (`:41-46`) | Seeds **the trial home** from the candidate's own template and writes the trial overlay. It is constructed with the trial config; it never sees the real home. |
| `DshRuntimeStateMachine` (`beginStart`, `markReady`, `fail`, `markStopTimeout`) | The single epoch/lifecycle authority. The trial is not a generation and never publishes to the app. |
| `DshRuntimeServiceClient.startTimeoutMillis()` / `DshPayloadInstallerTest` / `DshRuntimeOwnerTest` | Budget and test patterns. |

### 2.2 New collaborators (constructor injection, not `Deps` expansion)

`DshRuntimeOwner.Deps` is already 12 members and must not grow. Add three explicit, narrow constructor collaborators:

```text
DshPayloadSwitch     - payload transaction: inspect/stage/trial/commit/rollback/recover/quarantine
DshHomeState         - small persisted home guard: initialized, last payload version+sha, last ready
DshStartBudget       - pure timeout budget used by the client and tested on JVM
```

`DshPayloadSwitch` is implemented by `DshPayloadTransaction`, which composes:
- the active `DshPayloadInstaller`;
- a trial installer built from `config.forTrial(trialRoot)` + the same payload source;
- `DshRuntimeTrial` (Node launch + readiness, no state machine);
- `DshPayloadEvidence` (atomic JSON records);
- `DshHomeSnapshot` (pre-migration copy);
- `DshHomeState` (shared with the owner).

The trial coordinator itself takes a narrow port: `newNodeHost()`, `readinessGate`, `seedTrialHome(trialConfig)`, `isProcessAlive(pid)`, `log(line)`, `clock`, `nonce`. It does not take `DshRuntimeOwner.Deps`.

The assembly passes the **same** `DshPayloadInstaller` instance to `Deps.payload` and to the transaction. The fast path still calls `Deps.payload.ensureInstalled()` exactly as today; the transaction uses its read-only inspection methods and its own trial-config installer only when a switch is required. There is no second production provisioner rule set.

`DshHomeState` has one synchronous writer per field, called only inside `ensureStarted()`/commit/rollback; there is no background state writer.

### 2.3 Exact source write set

S0 = prerequisite; S1–S4 = main slice. Each is independently revertible.

| Slice | File (exact path) | Responsibility / budget |
| --- | --- | --- |
| S0 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshHomeStateStore.kt` (new) | JSON home guard at `files/dsh-runtime-state.json`; atomic read/write; adoption rule. Target ≤ 160 lines. |
| S0 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt` | First-run seed into `home.seed.tmp.<nonce>` then rename; existing-home files copied via temp+rename; completion marker + adoption check; no silent seed over unknown home. |
| S0 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshRuntimeConfig.kt` | `runtimeStateFile`, `homeSeedMarker`, trial paths/budgets/stale window, snapshot caps, candidate attempt/free-space knobs, `forTrial()`. |
| S0 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt` | Home guard before any Node launch; `DSH_HOME_MISSING` / `DSH_HOME_UNVERIFIED`. |
| S0 | `core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshRuntimeContract.kt` | Add `DSH_HOME_MISSING`, `DSH_HOME_UNVERIFIED`. |
| S0 | `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshFailureProjection.kt` | Messages for S0 codes. |
| S1 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadInstaller.kt` | Read-only `readPackagedManifest()` and `inspectActive()`; no change to install/swap behavior. |
| S1 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadTransaction.kt` (new) | Journaled switch, previous marker, recovery, quarantine. Target ≤ 380 lines. |
| S1 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadEvidence.kt` (new) | Health/rollback/quarantine/switch JSON models; atomic store; no secrets. Target ≤ 240 lines. |
| S1 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshHomeSnapshot.kt` (new) | Bounded pre-migration copy of allowlisted home data; complete marker; retention. Target ≤ 240 lines. |
| S1 | `core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshRuntimeContract.kt` | Add `CANDIDATE_UNHEALTHY`, `ROLLBACK_COMPLETED`, `ROLLBACK_FAILED`, `HOME_SNAPSHOT_FAILED` in the same slice as their first use. |
| S1 | `feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/DshFailureProjection.kt` | Messages/retry semantics for the four codes; no layout change. |
| S2 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/trial/DshRuntimeTrial.kt` (new) | Two-boot trial, stop/residue, evidence. Target ≤ 320 lines. |
| S2 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/trial/DshTrialLayout.kt` (new) | 128-bit nonce, trial dirs, pid lock, bounded home digest, stale sweep. Target ≤ 200 lines. |
| S2 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeeder.kt` | Typed `DshSeedResult` (`Seeded`, `CatalogUnavailable`, `TemplateMissing`, `WriteFailed`); real-home caller keeps Boolean semantics. |
| S3 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwner.kt` | Prepare → commit → real seed/start → markReady / rollback ordering. Keep file ≤ 720 lines (current 569). |
| S3 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshAndroidRuntimeAssembly.kt` | Build transaction/trial/home-state; node hash check; no trial link prep. |
| S3 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeService.kt` | Construct owner with the new collaborators; no IPC/message change. |
| S3 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/service/DshRuntimeServiceClient.kt` | Add trial budget to start timeout via `DshStartBudget`. |
| S3 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/startup/DshStartBudget.kt` (new) | Pure `startTimeoutMillis(config)`; JVM-testable. |
| S4 | `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadTransaction.kt` / `core/dsh-runtime/src/main/java/com/muyuchat/core/dsh/runtime/config/DshHomeSnapshot.kt` (hardening only) | Retention/sweep, recovery ordering, rollback failure paths; no new production file or code surface. |
| All | `core/dsh-runtime/src/test/java/...` and `feature/dsh-workspace/src/test/java/...` | Tests listed in §7. |
| All | this document | The only documentation file in this slice. |

### 2.4 Packaging and data paths: explicitly unchanged

- No new APK asset; the candidate is `assets/dsh/payload.zip` + `assets/dsh/MANIFEST.json` already in the installed APK.
- No new jniLib; Node stays `nativeLibraryDir/libdshnode.so` (PackageManager-owned).
- No `scripts/dsh/*`, `payload-lock.json`, `MANIFEST.json`, `app/build.gradle.kts` or backup XML change.
- Active marker format (`payload.marker.json`) is unchanged; all new records are **additive** files under `files/dsh/`.

## 3. Exact on-device layout

```text
files/
├── dsh-runtime-state.json                 # NEW: MCA home guard (outside files/dsh)
├── dsh/                                   # excluded from Android backup/transfer
│   ├── payload/                           # active verified kernel tree
│   ├── payload.marker.json                # active identity (unchanged format)
│   ├── payload.previous/                  # NEW: last-known-good tree until next switch
│   ├── payload.previous.marker.json       # NEW: previous identity
│   ├── payload.health.json                # NEW: committed active evidence (trusted only when COMMITTED)
│   ├── payload.trial-failure.json         # NEW: last failed trial summary (bounded, no secrets)
│   ├── payload.switch.json                # NEW: crash-consistent switch journal
│   ├── payload.rollback.json              # NEW: last rollback record
│   ├── payload.quarantine.json            # NEW: failed post-switch candidate attempts
│   ├── payload.failed.<nonce>/            # NEW transient: failed post-switch tree (deleted after record)
│   ├── payload.staging.<clock>/           # existing installer transient
│   ├── payload.old.<clock>/               # existing installer transient
│   ├── payload.archive.tmp                # existing installer transient
│   ├── trial/
│   │   └── <32-hex-nonce>/                # NEW, disposable
│   │       ├── payload/                   # staged candidate (verified by DshPayloadInstaller)
│   │       ├── payload.marker.json        # trial-scoped marker
│   │       ├── home/                      # trial DSH_HOME only; never the real home
│   │       ├── workspace/                 # trial cwd
│   │       ├── tmp/                       # trial TMPDIR
│   │       ├── trial.json                 # trial evidence (TRIAL_PASSED; promoted only after real READY)
│   │       └── trial.lock.json            # pid + kernel path for orphan detection
│   ├── backups/
│   │   └── home-<millis>-<fromSha12>-to-<toSha12>/   # NEW, complete-only snapshot
│   │       ├── sessions/...
│   │       ├── storages/...
│   │       ├── settings.yaml
│   │       ├── profiles/web/...           # allowlisted files only
│   │       └── snapshot.json              # written last; complete=true
│   ├── home/                              # real DSH home: untouched by the trial
│   ├── workspace/                         # real tool workspace: untouched by the trial
│   └── tmp/                               # real TMPDIR: untouched by the trial
└── ...
```

New path helpers (exact properties on `DshRuntimeConfig`):

```kotlin
val runtimeStateFile: File        // File(dataRoot.parentFile ?: dataRoot, "dsh-runtime-state.json")
val homeSeedMarker: File          // File(homeDir, ".mca-home-seeded.json")
val trialRootDir: File            // File(dataRoot, "trial")
fun trialDir(nonce: String): File // File(trialRootDir, nonce); require nonce matches [0-9a-f]{32}
val payloadPreviousDir: File      // File(dataRoot, "payload.previous")
val payloadPreviousMarkerFile: File
val payloadHealthFile: File       // File(dataRoot, "payload.health.json")
val payloadSwitchJournalFile: File
val payloadRollbackFile: File
val payloadQuarantineFile: File
val homeBackupRoot: File          // File(dataRoot, "backups")
```

Trial config, exact:

```kotlin
fun forTrial(trialRoot: File): DshRuntimeConfig = DshRuntimeConfig(
    dataRoot = trialRoot,
    payloadAsset = payloadAsset,
    manifestAsset = manifestAsset,
    nodeLibraryName = nodeLibraryName,
    host = host,                  // loopback only
    port = 0,                     // official server chooses the port
    authLineTimeoutMillis = trialAuthLineTimeoutMillis,
    readinessTimeoutMillis = trialReadinessTimeoutMillis,
    stabilityDelayMillis = stabilityDelayMillis,
    stopGraceMillis = stopGraceMillis,
    stopKillMillis = stopKillMillis,
    logRingLines = logRingLines,
    nodeExecProbeTimeoutMillis = nodeExecProbeTimeoutMillis,
    notificationChannelId = notificationChannelId,
    notificationId = notificationId,
    workspaceDirName = workspaceDirName,
    settlePollMillis = settlePollMillis,
    maxLogLineChars = maxLogLineChars
).validated()
```

The trial root is never a parent/child of `home`, `workspace`, `payload` or `tmp`; `DshTrialLayout` rejects any nonce or canonical path outside `files/dsh/trial/`.

New `DshRuntimeConfig` fields and defaults (add range checks in `validated()`):

| Field | Default | Purpose |
| --- | --- | --- |
| `trialAuthLineTimeoutMillis` | `90_000` | Per-boot official launch line wait |
| `trialReadinessTimeoutMillis` | `45_000` | Per-boot readiness gate wait |
| `trialBudgetMillis` | `240_000` | Whole two-boot trial wall clock; also added to the client start timeout |
| `trialStaleAfterMillis` | `86_400_000` (24 h) | Delete only dead, old trial dirs; never an active `starting`/`running` lock |
| `homeSnapshotMaxBytes` | `536_870_912` (512 MB) | Pre-migration snapshot cap |
| `homeSnapshotMaxFiles` | `20_000` | Pre-migration snapshot file cap |
| `maxCandidateAttempts` | `2` | Post-switch failures before quarantine is terminal for that payload sha |
| `candidateFreeSpaceFactor` | `3` | `usableSpace >= factor * manifest.archiveSize` before staging |

## 4. The five-step flow

### 4.0 Existing `startInternal` call sites that must move

The current `DshRuntimeOwner.startInternal` (baseline `af24899`) installs the payload and seeds the real home **before** any runtime health check. The trial cannot be added without moving these calls; leaving any of them in place silently bypasses the gate.

| Current call (baseline lines) | Required placement |
| --- | --- |
| `deps.payload.ensureInstalled()` (`DshRuntimeOwner.kt:242`) | Fast path only. On a candidate path, stage with the **trial config** installer; never call `ensureInstalled()` against the active config before the trial. After commit, the real path may call it and will reuse the active marker. |
| `deps.gatewayEndpoint()` (`:281`) | Before the trial (the trial needs the real endpoint for seeding). Reuse the returned endpoint for the real host; do not fetch a second generation. |
| `deps.seedDshHome(gateway)` (`:287-300`) | **After `switch.commit`**, before the real launch. The trial uses `DshGatewayConfigSeeder(trialConfig)` and never the real home. |
| `deps.execStrategy.resolve(...)` (`:318-345`) | Before the trial; reuse the chosen `DshNodeExecutable` for both trial and real host. |
| `deps.prepareRuntimeLinks(choice.executable)` (`:344`) | Real path only. Never call it for the trial (it mutates `payload/bin` links). |
| `deps.newNodeHost()` / `createdHost.start(...)` (`:360-370`) | Real path only. The trial constructs its own `DshNodeHost` and stops it before this point. |
| `machine.markReady(...)` / `entry = ...` (`:424-428`) | Unchanged. Commit evidence only after this READY; the trial never publishes an epoch/READY. |

### Step 1 — Candidate install (stage only; the active tree is not touched)

**Trigger.** A candidate switch is prepared only when the packaged payload identity differs from the active identity, the active tree is missing/corrupt, or a previous attempt left a recoverable journal. If the active marker matches the packaged manifest and critical files verify, take the existing fast path (`ensureInstalled()` reuse) and skip trial/switch.

**Preflight order** (cheap checks before any 160 MB work). The generation gateway endpoint is obtained before staging; a null endpoint fails `GATEWAY_UNAVAILABLE` without touching disk. The same endpoint is used by the trial and the real host.

1. `DshHomeState.guard()` (S0): unexpected missing/unverified home fails closed before any Node launch.
2. `installer.readPackagedManifest()`: parse `dsh/MANIFEST.json`; `Missing` → `PAYLOAD_MISSING`; parse error → `PAYLOAD_INTEGRITY`.
3. `installer.inspectActive()`: read `payload.marker.json` and verify the critical files/kernel entry/runtime dir. If `payload/` exists but does not fully verify (marker missing/mismatched or bytes corrupt), fail `PAYLOAD_INTEGRITY` and do **not** overwrite an unverified tree; there is no verified rollback point. A verify-able active tree always becomes `payload.previous/`; a first install (no `payload/`) is the only path with `rollbackAvailable=false`.
4. Check `payload.quarantine.json`: if the packaged `archiveSha256` is quarantined and the attempt budget is exhausted, fail `CANDIDATE_UNHEALTHY` (`retryable=false`) without staging. A new APK/payload sha ignores the old record.
5. Check `manifest.nodeLibraryName == config.nodeLibraryName`; fail `PAYLOAD_INTEGRITY` on mismatch instead of verifying one path and executing another.
6. Compute SHA-256 of `nativeLibraryDir/libdshnode.so` and compare `manifest.nodeSha256`; fail `PAYLOAD_INTEGRITY` on mismatch. This is done **only when a trial/switch is required**, not on every fast-path start (49 MB hash cost).
7. Require free space: `dataRoot.usableSpace >= 3 * manifest.archiveSize` (candidate + active + swap headroom). Insufficient → `CANDIDATE_UNHEALTHY` with `retryable=true`, diagnostics `INSUFFICIENT_SPACE`.
8. Generate a fresh 128-bit nonce: `SecureRandom().nextBytes(16)` → 32 lowercase hex. Never reuse a nonce for two health records; never use `Math.random`, timestamps or hashes of user data.

**Stage.** Create `files/dsh/trial/<nonce>/` and call:

```kotlin
DshPayloadInstaller(
    config = config.forTrial(config.trialDir(nonce)),
    source = AssetDshPayloadSource(context, config)   // same bytes
).ensureInstalled()
```

- It verifies archive size/SHA-256, extracts with path-traversal rejection, checks `fileCount`, all `criticalFiles`, the kernel entry and the runtime library dir, and writes `trial/<nonce>/payload.marker.json`.
- It must return `reused=false` for a fresh nonce; `reused=true` is an internal error and fails `CANDIDATE_UNHEALTHY`.
- On any failure: delete `trial/<nonce>/`; the active `payload/`, `home/`, `workspace/` and `tmp/` bytes are unchanged. `Missing`/`Integrity` keep their existing failure codes.
- The trial installer must **not** call `prepareRuntimeLinks`; trial payloads are only read/loaded.

**Result:** `DshPayloadPreparation.Staged(manifest, trialNonce, trialRoot, rollbackAvailable)` or a typed failure. Nothing outside `trial/<nonce>/` was written.

### Step 2 — Isolated trial home, random port, nonce

The trial runs the candidate as the only Node process, twice sequentially on the same trial home; the real host is never running at the same time because `ensureStarted()` has already settled the previous generation under the owner's mutex.

| Item | Exact value |
| --- | --- |
| Trial root | `files/dsh/trial/<nonce>/` |
| `HOME` / `DSH_HOME` | `trial/<nonce>/home` |
| `DSH_CONFIG_DIR` | `trial/<nonce>/home/config` |
| `DSH_WORKSPACE` | `trial/<nonce>/workspace` |
| `TMPDIR` | `trial/<nonce>/tmp` |
| `PATH` | `trial/<nonce>/payload/bin:trial/<nonce>/payload/runtime/bin:/system/bin:/system/xbin` |
| `LD_LIBRARY_PATH` | `trial/<nonce>/payload/runtime/lib` |
| Port | `0` (official server chooses; the real port is parsed from the launch line) |
| Node executable | the already-resolved `DshNodeExecutable` from `nativeLibraryDir` (same object later used by the real host) |
| Working directory | `trial/<nonce>/workspace` (never the payload) |
| Exec probe working dir | `trial/<nonce>/workspace` (created empty) or `config.workspaceDir` after `mkdirs`; never the real home and never the payload |
| Gateway | the real generation endpoint from `Deps.gatewayEndpoint()`; bearer only in the child env, never on disk/logs |
| Seeding | `DshGatewayConfigSeeder(trialConfig).seed(endpoint)`; candidate template → trial home only |
| Config overlay | same `permission` / `llm-pi-ai` overlay as production; `workspace-write` + `approval: ask`; no reference Android tools |
| Nonce | 32 hex in the trial dir, `trial.lock.json`, and the health record; two distinct **boot nonces** (`<nonce>-1`, `<nonce>-2`) |
| Time budget | `trialAuthLineTimeoutMillis` + 2 × `trialReadinessTimeoutMillis`, capped by `trialBudgetMillis`; client start timeout adds `trialBudgetMillis` (see §7.3 TB-01) |

**No business dispatch.** The trial never creates a WebView, never navigates a page, never sends a model completion request, never executes a tool/plugin action and never attaches to the real home. It only boots the official Web profile and performs the readiness gate's HTTP checks. The trial home has no user session and no user prompt.

**Trial launch sequence (per boot):**

1. Seed or re-seed the trial home (idempotent; same seeder and endpoint for both boots).
2. Write `trial.lock.json` in `state=starting` **before** launch; update it with `state=running` + `pid` immediately after the process starts: `{schema, nonce, boot, state, pid, kernelEntryPath, startedAtMillis}`.
3. `DshNodeCommand.environment(...)` with the trial roots + `MCA_DSH_GATEWAY_TOKEN`.
4. `DshNodeCommand.arguments(executable, trialConfig.kernelEntry(), trialConfig.host, 0)`.
5. `host.start(executable, argv, env, trialConfig.workspaceDir)`.
6. `host.awaitLaunchEntry(trialConfig.authLineTimeoutMillis, shouldAbort)`; parse only the official `dsh web: ...` line; origin must be a loopback literal accepted by `DshAuthEntryParser` (production default `127.0.0.1`); token stays in memory.
7. `readinessGate.awaitReady(launch, onStage = { record }, shouldAbort)`.
8. Stop with `host.stop(trialConfig.stopGraceMillis, trialConfig.stopKillMillis)`; require `stopped && !fenceRequired`; delete `trial.lock.json` only after the tree is confirmed gone.
9. Persist the boot's evidence fields.

**Crash/startup recovery of a `starting` lock.** A lock in `state=starting` with no pid is not proof that no Node exists. On the next start, scan `/proc` for same-UID processes whose `cmdline` contains the trial kernel path; if any exist, fence (`STOP_TIMEOUT`) and do not start. If none exist and the lock is older than `trialStaleAfterMillis`, delete the trial dir. Do not kill a bare pid.

**Stop barrier.** If the trial tree does not settle:
- Return `STOP_TIMEOUT` with the residue pids; the owner sets its existing `fencedPids` and publishes `markStopTimeout(...)`, exactly like a real-host stop fence.
- Do **not** start the real host, delete the active tree or rename anything. The next start checks `/proc/<pid>` and refuses while the fenced pids live.
- If `stop()` returns `stopped=true` but a descendant is still alive, treat it as fenced (`DshStopOutcome.fenceRequired` / `stopped`).

**Abort/failure cleanup.** On any candidate/trial failure **after the trial tree is confirmed stopped**, write `payload.trial-failure.json` (surviving failure code/stage/boot/nonce/candidate sha; no token/cookie), then delete `trial/<nonce>/`; never leave a 160 MB candidate tree behind. On a stop timeout/fence, do **not** delete the trial dir or lock until every trial pid is confirmed gone; keep the fence and let recovery/sweep clean it. On a user stop (`isStopRequested()`), stop the trial tree, delete `trial/<nonce>/` only after confirmation, return `StopRequested`, and publish the generic `STOPPED` snapshot through the existing `abortForStop()` path; no candidate failure record is written.

### Step 3 — Health evidence: auth, page, storage, session-store reopen

The trial runs **two boots on the same trial home**. Boot 1 proves first-run seeding, auth and page; boot 2 proves the home can be reopened without reinitialization. This is the MCA equivalent of DSHA's "storage/session write then reopen", with an explicit evidence boundary.

| ID | Evidence | Pass criterion | What it does **not** prove |
| --- | --- | --- | --- |
| H1 | Launch entry | Official `dsh web:` line parsed; origin is loopback; port > 0; token never persisted/logged | That a WebView accepted the cookie |
| H2 | Auth exchange | Gate's 303 + `dsh-auth-*` cookie + `HttpOnly` + clean `Location` steps reached; `DshStartupStage.HTTP_AUTHENTICATED` recorded | User identity/authorization beyond the official browser cookie |
| H3 | Page surface | Authenticated index HTTP 200, `text/html`, contains `__DSH_BOOT_READY__`; bootstrap client-modules asset served as JS ≥ 512 B; second stable index fetch; unauthenticated index 401; `PLUGINS_LOADED` recorded | A painted frame, official main conversation UI, plugin activation graph, WebSocket rendering |
| H4 | Storage writable | Trial home exists and is writable; the trial writes `trial/home/.mca-trial-<nonce>` with fixed bytes after boot 1, syncs, and reads it back | That DSH's KV/session store works |
| H5 | Session-store reopen | Boot 2 on the **same** trial home reaches READY; the inventory is taken **after boot-2 seeding and before boot-2 launch**; every pre-boot-2 file still exists; non-session/non-storage files have identical SHA-256; `home/.mca-trial-<nonce>` bytes are identical; DSH-created `sessions/`/`storages/` files are not truncated (existence + size ≥ previous for append-only logs) | A user session content round-trip; real-home session migration |
| H6 | No residue | After boot 2: `stopped=true`, `fenceRequired=false`, no `trial.lock.json`, no live pid whose `/proc/<pid>/cmdline` contains the trial kernel path; trial dir removed after commit | That the real host will boot |

**Explicit non-claims (must be copied into the health record and the report):**

- `renderer = NOT_RUN`: a trial cannot see the WebView; the current `BLOCKER-WHITE-PANE` (official main area blank after onboarding) is **not** detected by this gate.
- `sessionSemanticRoundTrip = NOT_RUN`: the trial home has no user session; no official non-business session API/fixture is used. H5 proves **store reopen / no reinitialization**, not that existing user sessions survive a kernel migration.
- `plugins = NOT_RUN`, `tools = NOT_RUN`, `mcp = NOT_RUN`, `pty = NOT_RUN`: report §6 P1 and later stages; do not infer from H1–H6.
- `homeMigration = NOT_RUN`: the real home is never cloned into the trial; migration is covered only by the pre-migration snapshot and the real start.

**H5 sufficiency.** H5 passes when the inventory/reopen checks hold. If the trial home contains **no** DSH-created `sessions/`/`storages/` files, H5 records `sessionStoreReopen=INSUFFICIENT_EVIDENCE` but still passes the persistence/reopen gate; this is explicitly **not** a semantic session-round-trip pass. Promotion is not blocked by `INSUFFICIENT_EVIDENCE`, but the report must keep `sessionSemanticRoundTrip=NOT_RUN`.

**Health record.** Trial evidence is written to the **trial-scoped** `files/dsh/trial/<nonce>/trial.json` (atomic temp+fsync+rename, ≤ 256 KB, no cookie/token/bearer). It is promoted to `files/dsh/payload.health.json` with `state=COMMITTED` only after the real host reaches READY; the committed file always describes the active payload, never an uncommitted attempt. A failed trial is summarized to `files/dsh/payload.trial-failure.json` (same bounds, no secrets) before the trial dir is deleted.

```json
{
  "schema": 1,
  "state": "TRIAL_PASSED",
  "trialNonce": "<32hex>",
  "candidate": {
    "payloadVersion": "dsh-...",
    "archiveSha256": "<64hex>",
    "fileCount": 31021,
    "nodeSha256": "<64hex>",
    "nodeStrategy": "NATIVE_LIBRARY"
  },
  "previous": {
    "payloadVersion": "dsh-...",
    "archiveSha256": "<64hex>"
  },
  "abi": "arm64-v8a",
  "boots": [
    {
      "boot": 1,
      "bootNonce": "<32hex>-1",
      "origin": "http://127.0.0.1:<port>",
      "stages": ["NODE_PROCESS_ALIVE", "PORT_LISTENING", "HTTP_AUTHENTICATED", "PLUGINS_LOADED"],
      "indexBytes": 37637,
      "pluginAsset": "/plugins/...",
      "storageProbeSha256": "<64hex>",
      "homeDigest": "<64hex>",
      "homeFileCount": 0,
      "stopped": true,
      "stopDetail": "SIGTERM",
      "atMillis": 0
    },
    {
      "boot": 2,
      "bootNonce": "<32hex>-2",
      "origin": "http://127.0.0.1:<port>",
      "stages": ["NODE_PROCESS_ALIVE", "PORT_LISTENING", "HTTP_AUTHENTICATED", "PLUGINS_LOADED"],
      "indexBytes": 37637,
      "pluginAsset": "/plugins/...",
      "storageReopen": "PASS",
      "sessionStoreReopen": "PASS_OR_INSUFFICIENT_EVIDENCE",
      "homeDigestBefore": "<64hex>",
      "homeDigestAfter": "<64hex>",
      "stopped": true,
      "atMillis": 0
    }
  ],
  "nonClaims": ["renderer", "sessionSemanticRoundTrip", "plugins", "tools", "mcp", "pty", "homeMigration"],
  "committed": false
}
```

The object above is `trial.json`. On commit, the same object is promoted to `payload.health.json` with `state="COMMITTED"`, `committed=true`, `commitEpoch` and `committedAtMillis`; `payload.health.json` always describes the currently active payload.

Trust rules:
- `payload.health.json` is trusted only when `state == "COMMITTED"`, `candidate.archiveSha256` equals the active `payload.marker.json` `archiveSha256`, and the active critical files verify. `trial.json` and `payload.trial-failure.json` are advisory and cannot skip or authorize a start.
- A malformed, oversized, unknown-schema health/trial record is ignored with a log line; it never blocks or authorizes a start. A malformed quarantine record is ignored (the candidate is re-trialed). A malformed `payload.switch.json` is never guessed: recovery verifies the active/previous trees and, if it cannot prove a safe state, refuses the start and preserves every tree (`ROLLBACK_FAILED`/`STOP_TIMEOUT`).
- Write `state="COMMITTED"`, `committed=true`, `commitEpoch` and `committedAtMillis` only after the real host reaches READY.
- On any failure before commit, write `payload.trial-failure.json` and delete the trial dir; never overwrite the previous `payload.health.json` with a non-committed attempt.

**Home digest bounds:** walk `trial/home` without following symlinks; exclude `tmp`, `logs`, `cache`, `*.log`, `*.tmp`, `*.lock` and the `.mca-trial-<nonce>` probe (verified separately); cap 2,000 files / 64 MB. If the cap is exceeded, record `homeDigestTruncated=true` and do not fail the trial solely for that; the digest is advisory, while H4/H5 existence checks remain. A symlink at a checked path fails the trial (`CANDIDATE_UNHEALTHY`) rather than following it.

### Step 4 — Switch (stop barrier → pre-migration snapshot → journaled rename → real start → commit)

**Switch preconditions (all must hold):**

1. Trial PASSED with both boots; H1–H6 satisfied.
2. Trial tree stopped and confirmed gone (`trial.lock.json` removed, no live trial pid).
3. Trial evidence (`trial.json`) written and bound to the candidate `archiveSha256` + trial nonce.
4. Packaged manifest still matches the staged candidate (no asset drift during the trial), and the staged candidate's critical files still verify immediately before the rename. If the trial's own writes changed a critical file, fail `PAYLOAD_INTEGRITY` and delete the trial dir; do not commit.
5. Home guard is `FirstRun` or `Existing`; `home` is readable or absent as expected.
6. `payload.previous` decision is safe: if an active payload exists, it verifies and its marker can become the previous marker; if no active payload exists, `rollbackAvailable=false` and rollback is reported as "no previous", not as a failure.
7. Free space and snapshot caps hold (§6).
8. No stop requested.

**Pre-migration snapshot.** If a real `home` exists and `DshHomeState.lastHomePayloadSha256 != candidate.archiveSha256` (or the version is unknown), create a complete snapshot under `files/dsh/backups/home-<millis>-<fromSha12>-to-<toSha12>/` (§6). Snapshot failure aborts the switch with `HOME_SNAPSHOT_FAILED`; the active tree and home are unchanged.

**Journaled rename sequence** (journal `files/dsh/payload.switch.json`, written before each step, atomic):

```text
phase=PREPARED          candidate sha, previous sha, nonce, trial evidence path
  -> verify the active/previous tree critical files first
  -> copy payload.marker.json -> payload.previous.marker.json
  -> phase=ACTIVE_MOVED
  -> rename payload/ -> payload.previous/           (only if an active payload exists)
  -> phase=CANDIDATE_MOVED
  -> rename trial/<nonce>/payload/ -> payload/
  -> write payload.marker.json (candidate identity)
  -> phase=MARKER_WRITTEN
  -> seed the real home from the now-active candidate template; create dirs
  -> launch the real host + readiness gate
  -> on READY: phase=COMMITTED; write health record COMMITTED; update DshHomeState
  -> on failure before READY: phase=ROLLING_BACK; see Step 5
```

- The active tree is moved to `payload.previous/` **only after** the trial passed and the snapshot completed. There is no window in which the only verified tree is the unproven candidate.
- Before `ACTIVE_MOVED`, if a stale `payload.previous/` already exists, delete it **only after the current active tree verifies**; if deletion fails, abort the switch (no rename). The current active, not the older previous, becomes the new rollback point.
- The candidate tree is moved out of `trial/<nonce>/` by rename; no re-extraction and no copy of 160 MB.
- The trial home/workspace/tmp are deleted only after the real host reaches READY. Before that, they are evidence. On a real-start failure, keep them until the rollback record is written; then delete or leave them for the bounded stale sweep.
- `payload.previous/` is retained after commit as the next rollback point. It is deleted only during a later switch's preflight after the new active tree verifies, or by the S4 bounded stale sweep.
- A crash between phases is repaired by `DshPayloadTransaction.recover()` at the start of the next `ensureStarted()` (Step 5 recovery table).

**Real start.** Seed the real home only after the candidate is active, using `DshGatewayConfigSeeder(config).seed(endpoint)`; this now reads the candidate's `dshhome` template from `payload/` and writes the real home/overlay. Then the existing owner path (`DshNodeCommand`, `DshNodeExecStrategy`, `AndroidDshNodeHost`, `DshReadinessGate`) runs unchanged.

**Commit.** Commit is **only** `DshRuntimePhase.READY`:
- promote `trial/<nonce>/trial.json` to `payload.health.json` with `state=COMMITTED` and `commitEpoch`;
- write `DshHomeState.lastHomePayloadVersion/Sha256` and `lastReadyAtMillis`;
- update `payload.switch.json` `phase=COMMITTED`;
- clear the candidate quarantine/failure record and `payload.trial-failure.json`;
- delete `trial/<nonce>/` (best effort; stale sweep if deletion fails);
- keep `payload.previous/` and its marker.

If the evidence/state write fails after READY: log `PAYLOAD_EVIDENCE_WRITE_FAILED`, keep `payload.previous/`, do **not** tear down a working host, and do not claim committed evidence in later reports. A later start sees the active marker matching the packaged manifest and takes the fast path.

**No rollback after READY.** A later `PROCESS_EXITED` remains the existing restart path; it is not a candidate-switch failure and must not auto-restore the previous payload.

### Step 5 — Rollback (tree restore only; Node and home are not restored)

**When rollback runs:**

- Only for a failure **after** `commit` (the candidate is active) and **before** the real host reaches READY.
- Only after the real process tree is confirmed stopped (`stopped=true`, `fenceRequired=false`). If the fence is raised, stop: report `STOP_TIMEOUT`, do not rename files.
- Not for trial failures (active tree was never changed), not for `GATEWAY_UNAVAILABLE` before commit, and not for `PROCESS_EXITED` after READY.

**Rollback sequence:**

```text
phase=ROLLING_BACK
  -> if no payload.previous/: NO_PREVIOUS; keep candidate active; report original failure
  -> verify payload.previous/ + payload.previous.marker.json critical files
  -> move the failed active candidate aside:
     rename payload/ -> payload.failed.<nonce>/          (diagnostic; delete after record)
  -> rename payload.previous/ -> payload/
  -> restore payload.marker.json from payload.previous.marker.json
  -> do not update DshHomeState.lastHomePayload*; record homeVersionUncertain when the real Node launched
  -> write payload.rollback.json
  -> delete payload.failed.<nonce>/ (after the record is complete)
  -> write quarantine record for the failed candidate
  -> phase=ROLLED_BACK
```

- **No automatic relaunch.** The host reports `ROLLBACK_COMPLETED` (`retryable` depends on the attempt budget) or `ROLLBACK_FAILED`. The next start sees the packaged manifest differing from the restored previous marker; the quarantine prevents an immediate re-trial loop for the same payload sha.
- If the previous tree cannot be verified, or either rename/marker restore fails: stop, leave every directory in place (no deletion), report `ROLLBACK_FAILED` (`retryable=false`). Do not delete the failed or previous trees; this is the recovery evidence.
- If there was **no previous payload** (first install): do not call this a rollback. Keep the candidate active, report the original failure code, and leave `retryable=true`; there is no older tree to restore.
- **Home is not rolled back.** The new kernel may have touched the home before failing. `payload.rollback.json` records `homeVersionUncertain=true` when the real Node was launched. The pre-migration snapshot is the recovery artifact; automatic home restore is out of scope (DSH is the only writer; restoring under an old kernel may be invalid). Do not delete or rewrite the home during rollback.
- **Node is not rolled back.** `nativeLibraryDir` belongs to the new APK. The restored previous kernel may not run with the new Node. `ROLLBACK_COMPLETED` means "bytes restored and verified", not "DSH is running". True Node-level rollback = install the previous signed APK (same signer; `adb install -r -d` for maintainers only; no in-app downgrade).

**Quarantine (`payload.quarantine.json`):**

```json
{
  "schema": 1,
  "candidateSha256": "<64hex>",
  "payloadVersion": "dsh-...",
  "attempts": 1,
  "maxAttempts": 2,
  "firstFailedAtMillis": 0,
  "lastFailedAtMillis": 0,
  "lastFailureCode": "LOADER_NOT_READY",
  "previousSha256": "<64hex>"
}
```

- Written only after a post-switch failure + successful rollback. Trial-only failures do not quarantine; the active tree is unchanged and a user retry is safe.
- Same payload sha and `attempts >= maxAttempts` → next start fails fast with `CANDIDATE_UNHEALTHY` (`retryable=false`), no staging/trial. Same payload sha and attempts remain → the user may retry; each retry increments the counter after another post-switch failure.
- A new packaged payload sha ignores the old record (keyed by sha); the record is not cleared by a simple app restart.
- No UI to clear quarantine in this slice; a maintainer can remove the file only after installing a new/previous APK.

**Crash recovery (`DshPayloadTransaction.recover()`, idempotent, run once per service process before the first start):**

| Journal phase found | Recovery action |
| --- | --- |
| none / `COMMITTED` | Nothing to repair; sweep stale `trial/*` dirs older than `trialStaleAfterMillis` and stale `.snapshot.tmp.*`; keep active/previous. |
| `PREPARED` | No active rename happened; delete the stale `trial/<nonce>/`, keep active. |
| `ACTIVE_MOVED` | If `payload/` is missing and `payload.previous/` verifies → rename previous back; restore marker; mark `ROLLED_BACK`; quarantine candidate. If `payload/` exists → continue as `CANDIDATE_MOVED`. |
| `CANDIDATE_MOVED` | If `payload/` verifies as the candidate and the marker is missing/mismatched → write the candidate marker (the candidate was already trial-passed); continue. If it does not verify → move it aside, restore previous, quarantine. |
| `REAL_STARTED` | If `payload.health.json` has a `COMMITTED` record matching the active candidate marker, finish as committed and keep the active tree. Otherwise, do not rename until `/proc` confirms no trial/real pid owns the tree. If no live owner: treat as a post-switch failure → restore previous, quarantine, write rollback record. If a live owner exists: fence (`STOP_TIMEOUT`) and refuse starts. |
| `ROLLING_BACK` | If `payload/` is missing and previous verifies → finish restore. Otherwise leave trees in place, write `ROLLBACK_FAILED`, refuse starts; no deletion. |
| `ROLLED_BACK` | Ensure marker matches previous; delete temp failed tree only if the record is complete; quarantine remains. |

Recovery never deletes the real home/workspace and never seeds a home. A recovered rollback surfaces on the next start as `CANDIDATE_UNHEALTHY`/`ROLLBACK_COMPLETED` through the persisted records, not as a fabricated READY.

## 5. Failure and rollback matrix

| ID | Trigger | Action | User-visible result |
| --- | --- | --- | --- |
| F1 | Packaged manifest missing/unparseable | No staging; active untouched | `PAYLOAD_MISSING` / `PAYLOAD_INTEGRITY` |
| F2 | Candidate archive size/SHA/file-count/critical-file mismatch | Delete trial dir; active untouched | `PAYLOAD_INTEGRITY` |
| F3 | `manifest.nodeLibraryName` mismatch or `libdshnode.so` sha mismatch | No trial launch; active untouched | `PAYLOAD_INTEGRITY` |
| F4 | Device ABI/exec probe fails | No trial launch | `ABI_UNSUPPORTED` / `NODE_NOT_EXECUTABLE` |
| F5a | Trial seeder cannot read the gateway catalog | Delete trial dir; active untouched | `GATEWAY_UNAVAILABLE` (existing code; retryable=true) |
| F5b | Trial seeder cannot find/seed the candidate template or write the trial home | Delete trial dir; active untouched | `CANDIDATE_UNHEALTHY` (`retryable=true`, diagnostics `TRIAL_SEED_FAILED`) |
| F6 | Trial launch/auth line missing | Stop trial; delete trial dir; active untouched | `CANDIDATE_UNHEALTHY` (`retryable=true`) |
| F7 | Trial readiness gate failure (auth/page/bootstrap/stability/401) | Stop trial; delete trial dir; active untouched | `CANDIDATE_UNHEALTHY` (`retryable=true`) |
| F8 | Trial storage/reopen (H4/H5) failure | Stop trial; delete trial dir; active untouched | `CANDIDATE_UNHEALTHY` (`retryable=true`) |
| F9 | Trial stop timeout / residue | Raise the existing fence with residue pids; do not switch | `STOP_TIMEOUT` (`retryable=false`) |
| F10 | User stop during trial | Stop trial; delete trial dir | `STOPPED` (abort path) |
| F11 | Pre-migration snapshot fails/caps exceeded | Abort switch; delete trial dir; active/home untouched | `HOME_SNAPSHOT_FAILED` (`retryable=true`) |
| F12 | Commit rename/marker failure | Restore active from previous; delete trial dir | `PAYLOAD_INTEGRITY` |
| F13 | Real start fails after commit, before any Node launch (e.g. seed) | Restore previous; no quarantine | Original code (`GATEWAY_UNAVAILABLE`, etc.) |
| F14 | Real Node starts but never reaches READY | Stop tree; restore previous; quarantine candidate | `ROLLBACK_COMPLETED` (`retryable` by attempts) |
| F15 | Previous tree missing/corrupt, or rollback rename fails | Keep all trees; no deletion | `ROLLBACK_FAILED` (`retryable=false`) |
| F16 | First install candidate fails after commit (no previous) | Keep candidate; no rollback | Original failure code (`retryable=true`) |
| F17 | Stop fence after real failure | No renames until tree confirmed gone | `STOP_TIMEOUT` (`retryable=false`) |
| F18 | Crash between switch phases | Journal recovery; restore or finish; quarantine as appropriate | Existing `FAILED` snapshot after restart, with the persisted reason |
| F19 | Trial orphan after service death (`trial.lock.json` live pid) | Refuse start; do not delete its dir | `STOP_TIMEOUT` (`retryable=false`) |
| F20 | Evidence write fails after READY | Keep host READY; log; keep previous; no committed evidence claim | No failure (host is working); committed evidence is `NOT_RECORDED` |

### 5.1 User-facing projection (text only; no layout change)

`DshFailureProjectionMapper` remains the only writer of these strings. `primaryActionLabel` stays `重试` for retryable codes; the screen already hides the action when `retryable=false`.

| Code | Title | Message | Retryable |
| --- | --- | --- | --- |
| `DSH_HOME_MISSING` | 用户数据缺失 | 检测到 DSH 曾经初始化，但数据目录不存在。应用不会自动新建空库；请先恢复 DSH 数据目录后再试。 | false |
| `DSH_HOME_UNVERIFIED` | 用户数据未验证 | 发现无法确认归属的 DSH 数据目录。为避免覆盖或丢失已有数据，已停止启动。请先确认该目录归属；应用不会自动覆盖或删除它。 | false |
| `CANDIDATE_UNHEALTHY` | 候选运行组件未通过试运行 | 新的 DSH 运行组件在隔离环境中启动失败，现有运行组件和用户数据未被替换。可重试；若再次失败，请安装更新或上一版 APK。 | true until quarantine, then false |
| `ROLLBACK_COMPLETED` | 已回退运行组件 | 新运行组件启动失败，已恢复上一版运行组件字节；DSH 用户数据未被删除，但页面未启动。请安装修复后的 APK。 | by attempt budget |
| `ROLLBACK_FAILED` | 运行组件回退失败 | 无法恢复上一版 DSH 运行组件，所有运行目录均已保留。请安装更新或上一版 APK，不要清数据。 | false |
| `HOME_SNAPSHOT_FAILED` | 用户数据快照失败，已取消切换 | 升级前无法完成 DSH 用户数据快照，因此没有启动新运行组件。请释放存储空间后重试。 | true |

These are additive failure cases; no new controls, routes, dialogs or visual tokens. Every new code needs an **explicit** `titleFor`/`messageFor` branch; do not let it fall into the generic `else -> 启动失败`. The existing retry affordance follows `retryable`; the current `DshFailureProjectionTest.everyFailureCodeHasASpecificPromptFreeMessage` loop must pass for every new code. Titles in the table are the `titleFor` suffix; the mapper renders them as `DSH <title>`.

## 6. Data migration rules

| ID | Rule |
| --- | --- |
| D1 | The trial home is always new/empty and disposable. Never copy, bind, symlink, hardlink or point it at the real `home`, `workspace` or `tmp`. |
| D2 | On a candidate switch, the real home is seeded only after the candidate is active and only after the trial passed. The trial never writes the real home; the normal fast path keeps today's seeding behavior. |
| D3 | MCA does not migrate DSH sessions/storages/plugin data; DSH is the only writer. MCA only snapshots and starts the official kernel. |
| D4 | When the real `home` exists and the payload identity changes (`lastHomePayloadSha256 != candidate.archiveSha256`), copy the allowlisted home set to `files/dsh/backups/home-<millis>-<fromSha12>-to-<toSha12>/` before the real start. Copy, never move; per-file temp+fsync+rename; write `snapshot.json` last with `complete=true`, file count/size/SHA-256. First install (no home) skips the snapshot. |
| D5 | Snapshot allowlist: `home/sessions/**`, `home/storages/**`, `home/settings.yaml`, `home/profiles/web/cordis.yml`, `home/profiles/web/cordis.patch.yml`, `home/profiles/web/package.json`, `home/profiles/web/pnpm-workspace.yaml`, `home/profiles/web/pnpm-lock.yaml` (if present). Exclude `node_modules/**`, logs/cache/tmp, credentials/secret files and unknown paths. This is a **recovery artifact**, not a full backup or user export. |
| D6 | Snapshot caps: 512 MB / 20,000 files; if exceeded, or a required path is unreadable/a symlink, fail `HOME_SNAPSHOT_FAILED` and do not switch. Do not silently produce a partial backup labelled complete. |
| D7 | Snapshot retention: at most 2 complete snapshots. Delete the oldest **complete** snapshot only after a newer complete snapshot exists and the corresponding switch reached READY. Never delete an incomplete snapshot, a `ROLLING_BACK` snapshot or the only snapshot for a failed switch. |
| D8 | On a post-switch failure, do not automatically restore the snapshot. Mark `homeVersionUncertain=true` when the real Node was launched; the snapshot path is recorded in `payload.rollback.json` for manual recovery/maintainer forward-fix. |
| D9 | `DshHomeState.lastHomePayloadVersion/Sha256` is updated only after real READY. `homeInitialized` is set after the real home seed completes (S0 atomic seed + completion marker), so a Node failure after a successful seed does not lock the next retry out as "unverified". |
| D10 | Credentials/secret files are never copied into the trial, snapshot or health record. The gateway bearer stays in the child environment only; no cookie, launch token, API key or full user prompt is persisted. |
| D11 | The real `workspace/` is never copied or migrated by this slice. The trial workspace is disposable; after commit the real workspace is used by the real host as today. |
| D12 | Whole `files/dsh` remains excluded from Android cloud backup and device transfer (`backup_rules.xml:25-28`, `data_extraction_rules.xml:23-25,57-59`). The new `files/dsh-runtime-state.json` is intentionally **outside** `dsh` and therefore included in backup/transfer: after a restore without the DSH tree, `homeInitialized=true` + missing home must produce `DSH_HOME_MISSING`, not an empty library. Do not add this file to the backup exclusions without replacing that guard. |
| D13 | Home guard decision (S0): `!initialized && !home` → first run; `initialized && !home` → `DSH_HOME_MISSING`; `!initialized && home` → adopt only if the MCA overlay header + seeded profile template prove prior MCA ownership, otherwise `DSH_HOME_UNVERIFIED`; `initialized && home` → proceed; never seed over user data. Effective initialization is `state.homeInitialized || homeSeedMarker.isFile`; the in-home completion marker closes the crash window between a successful seed and the state-file write. |
| D14 | No targetSdk/minSdk/ABI downgrade, no clearing app data, no changing `applicationId` and no uninstall/reinstall is a migration strategy. A lower-version APK downgrade is a maintainer procedure with `adb install -r -d` and a same-signer APK; it is not offered in-app. |

## 7. Tests

All tests are JVM unit tests unless marked `PLANNED / NOT_RUN`. They use temp directories, fake hosts/probes and fake gateway endpoints; no real DSH, APK, network or device.

### 7.1 Payload candidate/transaction

File: `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/payload/DshPayloadTransactionTest.kt` (new)

| ID | Case | Assertion |
| --- | --- | --- |
| TR-01 | `candidateStageDoesNotTouchActiveTree` | Pre-populate active `payload/` + marker; stage a different candidate into `trial/<nonce>/`; active file hashes and marker are byte-identical; only trial dir exists. |
| TR-02 | `candidateArchiveMismatchLeavesActiveAndHomeUntouched` | Tampered archive → `Integrity`; active/home sentinel hashes unchanged; trial dir removed. |
| TR-03 | `candidateNodeShaMismatchFailsBeforeLaunch` | Fake `nativeLibraryDir/libdshnode.so` hash ≠ manifest; no `newNodeHost()` call; `PAYLOAD_INTEGRITY`. |
| TR-04 | `commitSwapsTreesAndKeepsPreviousMarker` | Commit: active → previous, trial payload → active, candidate marker written, previous marker readable; `payload.previous/` critical files verify. |
| TR-05 | `failedCommitRestoresActiveTree` | Inject rename/marker failure; active bytes/marker restored; previous unchanged; `Integrity`. |
| TR-06 | `rollbackRestoresPreviousAndQuarantines` | Post-switch failure → rollback: previous bytes/marker active; failed candidate moved/deleted; quarantine attempts=1. |
| TR-07 | `rollbackWithMissingPreviousReportsRollbackFailedWithoutDeletion` | An active candidate exists but `payload.previous/` is missing; no renames; both surviving trees untouched; `ROLLBACK_FAILED`. |
| TR-08 | `quarantineBlocksSameShaButNotNewSha` | attempts=max → same sha fails fast; a new manifest sha stages/trials. |
| TR-09 | `switchJournalRecoversBetweenRenames` | Write journal phase `ACTIVE_MOVED` with `payload/` missing; recovery restores previous, writes quarantine/rollback; active bytes match. |
| TR-10 | `recoveryRefusesLivePid` | Fake `isProcessAlive(pid)=true` and cmdline match; recovery does not rename/delete; owner reports stop fence. |
| TR-11 | `fullTrialStagingCountsEveryFile` | Trial installer verifies `fileCount` and all critical files; a missing non-critical file does not fake the count (existing installer behavior). |

### 7.2 Trial coordinator

File: `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/trial/DshRuntimeTrialTest.kt` (new)

| ID | Case | Assertion |
| --- | --- | --- |
| TT-01 | `passingTrialRecordsTwoBoots` | Fake host returns a valid launch entry and fake gate READY both boots; evidence has boot 1/2, distinct boot nonces, same trial nonce; no real generation/epoch is published. |
| TT-02 | `trialAuthFailureLeavesRealHomeByteIdentical` | Pre-populate a sentinel real home; fake host exits before auth; trial fails `CANDIDATE_UNHEALTHY`; real-home sentinel hashes unchanged; trial dir removed. |
| TT-03 | `trialReadinessFailureMapsToCandidateUnhealthy` | Gate returns `LOADER_NOT_READY`; trial result carries `CANDIDATE_UNHEALTHY` + diagnostics, no real Node launch. |
| TT-04 | `trialStorageProbeMustPersist` | Boot 1 write is removed before boot 2; H4/H5 fails; `CANDIDATE_UNHEALTHY`. |
| TT-05 | `trialReopenDetectsTruncatedStoreFile` | Pre-create a file under `trial/home/storages`; boot 2 truncates it; H5 fails. |
| TT-06 | `trialStopTimeoutReturnsResiduePids` | Fake stop returns `stopped=false`/`fenceRequired=true`; result `STOP_TIMEOUT` with pid; no real start. |
| TT-07 | `trialAbortOnStopRequest` | `shouldAbort` true; trial host stopped; `StopRequested` returned; no evidence committed. |
| TT-08 | `trialUsesPortZeroAndLoopbackOrigin` | Arguments contain `--port 0`; a non-loopback parsed origin is rejected. |
| TT-09 | `trialDoesNotPrepareRuntimeLinks` | Fake ports record zero `prepareRuntimeLinks` calls; active `payload/bin` links untouched. |
| TT-10 | `staleNonceCannotSkipTrial` | A `COMMITTED` record with a different nonce/sha is ignored; the new candidate is trialed. |

### 7.3 Owner ordering / failure projection / budget

Files:
- `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/DshRuntimeOwnerTest.kt` (extend)
- `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/config/DshHomeStateStoreTest.kt` (new, S0)
- `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/config/DshHomeSnapshotTest.kt` (new)
- `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/config/DshGatewayConfigSeederTest.kt` (extend)
- `core/dsh-runtime/src/test/java/com/muyuchat/core/dsh/runtime/startup/DshStartBudgetTest.kt` (new)
- `feature/dsh-workspace/src/test/java/com/muyuchat/feature/dsh/workspace/DshFailureProjectionTest.kt` (extend)

| ID | Case | Assertion |
| --- | --- | --- |
| TO-01 | `candidateFailurePublishesCandidateUnhealthyOnce` | Trial failure → FAILED/CANDIDATE_UNHEALTHY; epoch allocated once; no real `newNodeHost()`; active/home untouched. |
| TO-02 | `noRealHomeSeedBeforeTrialPass` | Fake transaction: seed call order is trial-home seed before real-home seed; on trial failure, real seed count = 0. |
| TO-03 | `readyCommitsAndUpdatesHomeVersion` | Trial passes, then real READY; `markReady`/commit called with the same epoch; `lastHomePayloadSha256` updates; trial dir cleanup requested. |
| TO-04 | `postSwitchFailureRollsBackBeforeReturn` | Real gate fails before READY; rollback called; snapshot exposes `ROLLBACK_COMPLETED`, not READY. |
| TO-05 | `firstInstallHasNoPreviousAndKeepsCandidate` | No `payload.previous/`; original failure code returned; candidate remains active; retryable true. |
| TO-06 | `fencedTrialStopPublishesStopTimeout` | Trial residue pids → owner `fencedPids` + FAILED/STOP_TIMEOUT; no real start. |
| TH-01 | `firstRunSeedsOnlyAfterTrial` | S0 state absent + home absent → trial then seed; completion marker written; `homeInitialized=true`. |
| TH-02 | `initializedMissingHomeFailsClosed` | state initialized + home absent → `DSH_HOME_MISSING`; no seed, no Node. |
| TH-03 | `unknownHomeFailsClosed` | state absent + home with user content but no MCA overlay header → `DSH_HOME_UNVERIFIED`; bytes unchanged. |
| TH-04 | `mcaOwnedHomeIsAdoptedOnce` | state absent + overlay header + seeded profile → adopted `Existing`, state written once; no data loss. |
| TH-05 | `stateFileIsOutsideDshTree` | `runtimeStateFile` is a sibling of `dsh/`, not under it; contains no token/user content. |
| TS-01 | `snapshotCopiesAllowlistAndWritesCompleteMarker` | Sessions/storages/settings/profile files copied with hashes; `node_modules`/logs/credentials excluded; `complete=true`. |
| TS-02 | `snapshotCapFailureBlocks` | Exceed cap → `HOME_SNAPSHOT_FAILED`; no switch; incomplete temp not labelled complete. |
| TS-03 | `snapshotSymlinkRejected` | Symlink at a required path → failure, target not copied (test guarded with `Assumptions` on Windows symlink support). |
| TSEED-01 | `seedResultDistinguishesCatalogFromWrite` | Catalog HTTP failure → `CatalogUnavailable` → trial `GATEWAY_UNAVAILABLE`; missing template/write failure → `TemplateMissing`/`WriteFailed` → trial `CANDIDATE_UNHEALTHY`; real-home caller still gets Boolean false. |
| TB-01 | `startBudgetIncludesTrial` | `DshStartBudget.startTimeoutMillis(config)` = existing components + `trialBudgetMillis`; the client uses it. |
| TP-01 | `everyNewFailureCodeHasSpecificMessage` | Existing all-codes loop covers new codes; the projection reports the configured retryable value; no diagnostics leak. |
| TP-02 | `newCodesHaveExplicitTitlesNotTheGenericFallback` | Each new code maps to its expected `titleFor`/`messageFor` branch, never the generic `启动失败`/`else`; `retryable=false` codes expose no retry action. |

### 7.4 Static, integration and device acceptance

| Check | Command / evidence | Result today |
| --- | --- | --- |
| Architecture rules | `python tools/architecture/check_architecture_rules.py`; expect no new feature→runtime dependency, no platform type in `:core:dsh-contract`, no DSH owner duplication. | `NOT_RUN` (planned) |
| Core DSH JVM tests | `.\gradlew.bat :core:dsh-runtime:testDebugUnitTest --tests "com.muyuchat.core.dsh.runtime.trial.*" --tests "com.muyuchat.core.dsh.runtime.payload.*"` under the MCA build lock. | `NOT_RUN` |
| Workspace tests | `.\gradlew.bat :feature:dsh-workspace:testDebugUnitTest --tests "com.muyuchat.feature.dsh.workspace.DshFailureProjectionTest"` + the existing workspace suite. | `NOT_RUN` |
| Gateway regression | `.\gradlew.bat :app:testDebugUnitTest --tests "com.muyuchat.mca.dsh.*"` (gateway/auth/cancel unaffected). | `NOT_RUN` |
| Compile | `.\scripts\verify-code.ps1 -SkipTests` covers app/engine/local only; it does **not** compile DSH modules. Use the Gradle tasks above under the same lock; do not run concurrently with `build.ps1`/`verify-code.ps1`. | `NOT_RUN` |
| Device forced-trial | On a disposable debug/diagnostic build: stop app, remove `files/dsh/payload` + `payload.marker.json` via `run-as`, open DSH. Expected: trial logs, `payload.health.json` with two boots, switch, real READY. | `NOT_RUN`; not a production-data test |
| Device rollback | A candidate that passes trial but fails the real start requires a deliberately bad second payload or a fault-injection hook. **No production test hook will be added**; this is `BLOCKED` until a test-only APK/fixture exists. | `BLOCKED` |
| Renderer / page | Separate WebView E2E (R07 E1/E2/E3/E5/E6 + DSH chat). A green trial cannot close `BLOCKER-WHITE-PANE`. | `NOT_RUN` |
| Real-home migration | The trial home is empty; real session migration is `NOT_RUN` by design. Snapshot files are the only recovery evidence. | `NOT_RUN` |

## 8. Implementation order (independently revertible slices)

| Slice | Content | Exit condition | Revert |
| --- | --- | --- | --- |
| S0 | Home guard + atomic seed + adoption + `DSH_HOME_MISSING`/`UNVERIFIED` | Existing MCA homes adopt once; missing/unreadable home fails closed; no silent empty library. | Remove the state store/branch; home files are untouched. |
| S1 | `DshPayloadTransaction` + evidence/journal/quarantine + `DshHomeSnapshot` + switch codes/projection; `readPackagedManifest`/`inspectActive` | Active tree never changes before commit; failed commit restores previous; snapshot caps/failures block; recovery tests pass. | Keep existing `ensureInstalled()` path; delete new files; the four new codes/UI text are additive and can be removed with their first caller. |
| S2 | `DshRuntimeTrial` + `DshTrialLayout` + typed `DshSeedResult` | Two-boot evidence; trial never touches real home; stop residue fences; catalog vs seed-write failures are distinguishable. | Unused coordinator; no production behavior change until wired. |
| S3 | Owner ordering + service wiring + budget | Trial before seed; commit only on READY; epoch/stop fence intact; client timeout covers trial. | Revert owner/assembly/service; existing direct-install path remains. |
| S4 | Snapshot retention/sweep + rollback/recovery hardening | Snapshot failures block switch; post-switch failures restore previous or report `ROLLBACK_FAILED`; recovery ordering is idempotent; no orphan trial dirs. | Remove hardening call sites; active marker format unchanged. |

Do not combine S0 with the trial wiring in one review. S0 changes data-loss behavior; S1–S4 change switch behavior. Each must be reviewed and verified separately per the hard lock's slicing rules.

## 9. Risks and limits

| ID | Severity | Risk | Mitigation / honest limit |
| --- | --- | --- | --- |
| **R-T1** | **Critical** | **A green trial is not a working DSH.** The trial proves auth/index/bootstrap/storage-reopen only. It cannot see the WebView renderer, so the current `BLOCKER-WHITE-PANE` can still occur after a green trial. Rollback restores the kernel tree but not the Node ELF, and the real home migration is not exercised. | Treat the trial as a switch-safety gate, not a release gate. Keep the WebView E2E matrix separate and mandatory. State `renderer=NOT_RUN`, `sessionSemanticRoundTrip=NOT_RUN`, `homeMigration=NOT_RUN` in every evidence report. Do not advertise "rollback" as a full restore. |
| R-T2 | High | Disk pressure: active + previous + candidate + snapshot can exceed free space. | 3× archive precondition; snapshot caps; delete a redundant previous only after the active tree verifies; fail closed with a clear code. |
| R-T3 | High | Trial orphan after `:dsh` process death; a stale `trial.lock.json` pid could be reused. | `/proc/<pid>/cmdline` must contain the trial kernel path; if unreadable, fence; never kill by a bare pid; force-stop is the operator recovery. |
| R-T4 | Medium-high | The trial boots against the real gateway; a booting kernel/plugin could theoretically dispatch a model/tool action. | No WebView, no session, no user prompt, no MCA model request; trial overlay has no extra tools. If observed, move to a restricted trial gateway (future slice) rather than widening this one. |
| R-T5 | Medium-high | Snapshot allowlist is not a full home backup (node_modules, unknown plugin data, credentials excluded). | Label it a recovery artifact; keep SAF export/import (M5) open; never auto-restore from it. |
| R-T6 | Medium | Quarantine keeps DSH unavailable after a failed switch until an APK change. | Product decision recorded; message tells the user to install a new/previous APK; other MCA modes are unaffected. |
| R-T7 | Medium | New failure codes require exhaustive UI mapping; a missed branch fails compile/tests. | `DshFailureProjection` `when` is exhaustive; `DshFailureProjectionTest.everyFailureCodeHasASpecificPromptFreeMessage` loops all codes. No layout change; text-only. |
| R-T8 | Medium | JVM fakes do not prove real Node/loader/WebView behavior. | Device acceptance is explicit and `NOT_RUN`; no mock result is reported as a device PASS. |
| R-T9 | Medium | S0 home guard changes first-run behavior and can lock an unknown existing home. | Adoption only for an MCA-owned overlay/seed; otherwise fail closed with a clear code; dedicated S0 review and tests. |
| R-T10 | Medium | The 49 MB Node hash and two trial boots add startup latency. | Hash only when a switch is required; budget added to the client timeout; trial skips the fast path. Measure on device before claiming acceptable. |
| R-T11 | Medium | `DshRuntimeOwner` grows toward its 800-line review limit. | New logic lives in transaction/trial/snapshot files; owner only sequences; target ≤ 720 lines and re-check the file budget. |
| R-T12 | Low-medium | `payload.previous/` doubles payload disk usage. | Retention is one previous tree; delete it only on a later verified switch or explicit stale sweep; never delete it while a switch journal is not `COMMITTED`. |
| R-T13 | High | An existing home keeps its old seeded profile templates: `DshGatewayConfigSeeder` copies `cordis.yml`/`package.json`/`pnpm-workspace.yaml` only when missing, so a new payload's changed template does not refresh an existing home. The trial's fresh home cannot catch this. | Record `realHomeMigration=NOT_RUN`; keep the pre-migration snapshot; template versioning/migration belongs to the M5/G2 follow-up, not this slice. Never delete or overwrite the existing profile files during the switch. |

## 10. Acceptance criteria

A candidate trial is complete only when all of the following are true:

1. A failed candidate never becomes active: active `payload/` bytes/marker and real `home/` bytes are unchanged on every trial failure.
2. Trial/health/rollback records are bound to `archiveSha256` + trial nonce + two boot nonces and contain no cookie, token, bearer, user prompt or file content. The separate pre-migration snapshot is the only home-data copy; it is allowlisted, capped and never labelled a full backup.
3. The switch happens only after the trial passes and the trial tree is confirmed stopped; no two Node generations run concurrently.
4. The real host commits only at READY; when an active payload exists, `payload.previous/` is the verified rollback point before the candidate becomes active.
5. A post-switch, pre-READY failure restores the previous tree/marker or reports `ROLLBACK_FAILED` without deleting anything.
6. A first install has no rollback claim; it keeps the candidate and reports the original failure.
7. Quarantine prevents a same-sha switch loop; a new sha is not blocked.
8. No targetSdk/ABI change, no exec from `files/dsh/**`, no copied Node, no second runtime owner.
9. `DshRuntimeOwner` epoch remains monotonic; the trial publishes no app generation/READY; stop fences still apply.
10. The health record explicitly says `renderer=NOT_RUN`, `sessionSemanticRoundTrip=NOT_RUN`, `homeMigration=NOT_RUN`; those remain separate evidence gates.

## 11. Delivery evidence (per hard lock §16)

```text
Task / scope: candidate payload trial -> isolated trial home/port/nonce -> health evidence -> switch -> rollback plan
Start revision + working tree/submodules: af2489909117edfa88721816a5fa40f5a4a5df9c; 82 dirty entries; DSH modules untracked; submodules as recorded above
Changed files and responsibility: only this document (read-only design); no code/build/resource/test change
Function/data/protocol/visual impact: none executed; proposed additive JSON files under files/dsh and one state file outside it; no active marker format change; no visual layout change
Owner/state/resource boundary: one DshRuntimeOwner/epoch; trial is a short-lived coordinator, not a second generation; no second service/gateway
Checks, results, raw evidence: no Gradle/test/APK/device command run; all runtime results NOT_RUN; planned tests listed in §7
NOT_RUN / BLOCKED / existing / new: renderer, semantic session reopen, real-home migration, device rollback (BLOCKED), device happy path; no runtime PASS
Temporary paths/exceptions: trial/<nonce>, payload.previous, backups/...; no exception request
Rollback point / next slice: delete this document only; first implementation slice is S0 (home guard), then S1 transaction/snapshot, S2 trial, S3 wiring, S4 hardening
```
