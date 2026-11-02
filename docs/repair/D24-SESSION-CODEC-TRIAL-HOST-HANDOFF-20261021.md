# D21 official session codec + D24 closure trial host — handoff

Date: 2026-10-21 · Workspace: `E:\MAC` (uncommitted parallel work) · Owner of this slice: maintenance
subagent. This document is the integration patch list for files this slice could not own
(`DshRuntimeOwner.kt`, `MainRuntimeAssembly.kt`, `DshModeAssembly.kt`, `:core:dsh-contract`,
`:core:dsh-runtime` IPC/service/client). Everything else is implemented and JVM-verified here.

## Delivered in this slice (no further wiring required)

| File | Responsibility |
| --- | --- |
| `core/dsh-maintenance/.../SessionV4Codec.kt` (new) | `DshZstdFrameScanner` (byte-exact port of the official structural scan), `DshNodeSessionCodec` (decode/encode/header/stats via the Node helper), typed header proof + session stats |
| `core/dsh-maintenance/src/main/assets/dsh-maintenance/session-v4-codec.mjs` (new) | Official-format reader/writer: structural frame scan, checksummed frame writes, torn-tail prefix recovery, official v4 message counting. Uses `node:zlib` zstd |
| `core/dsh-maintenance/.../SessionRepair.kt` | Torn-final-frame handling: a torn tail is truncated exactly like the kernel heal; untouched frames stay byte-identical; counts only complete frames |
| `core/dsh-maintenance/.../RuntimeSelfCheck.kt` | `SESSION_READER` now requires a decoded real header; a readable directory is no longer accepted |
| `core/dsh-maintenance/.../ClosureCandidate.kt` (new) | Verified staged candidate source (`closure/incoming/<id>/candidate.json`): containment, component completeness, declared hashes, ABI, schema |
| `core/dsh-maintenance/.../ClosureTrialHost.kt` (new) | `DshHostLifecyclePort` with one isolated trial generation: durable `trial/active.json`, serial production/trial, stop proof, recovery |
| `app/.../composition/DshMaintenanceAssembly.kt` | Wires codec only after a real header decode; read-only health from the header proof; trial host; candidate source |
| `app/.../composition/DshMaintenanceConsoleAdapter.kt` | `REPAIR_SESSION` implemented; accurate message counts; `RUN_CLOSURE_UPGRADE` / `RESUME_CLOSURE_UPGRADE` advertised + dispatched honestly |
| `app/.../composition/DshMaintenanceUpgradeActions.kt` (new) | Upgrade/backup execution split out of the adapter to stay inside the 800-line file budget |
| `app/.../composition/DshSessionV4NodeBridge.kt` (new) | Node helper process runner (nativeLibraryDir exec → linker64 fallback), asset staging, proof gating |

Verification (no Gradle/adb): the whole `:core:dsh-maintenance` main+test source compiles with the
pinned Kotlin compiler and **91/91 JVM tests pass**, including the real Node helper tests
(Node v24.19.0, zstd 1.5.7). The four app composition files also compile against the existing
`:core:dsh-contract` / `:core:dsh-platform` debug class outputs + `android-36/android.jar`.
File budget: largest touched file is `DshMaintenanceConsoleAdapter.kt` at 732 lines (< 800).

Still honest-NULL/FAILED in the built APK until the patches below land:
- the codec is null because the helper asset is only readable through `Context.assets`;
- `startTrial` fails with `"the isolated trial launcher is not connected…"` because the runtime
  owner has no trial generation yet.

## Patch 1 — MainRuntimeAssembly: pass the helper asset

File: `app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt`, in the
`DshMaintenanceAssembly(...)` call (around line 182):

```kotlin
DshMaintenanceAssembly(
    filesDir = application.filesDir,
    runtime = dshRuntimeClient,
    gatewayReady = { dshGatewayAssembly.isRunning },
    bridgeReady = { dshCapabilityAssembly.isRunning },
    nativeLibrary = { File(application.applicationInfo.nativeLibraryDir, "libdshnode.so") },
    expectedArchiveSha256 = { packagedArchiveSha256() },
    expectedFileCount = { packagedPayloadFileCount() },
    installedAbi = { /* unchanged */ },
    // NEW: plain-text helper asset; AGP merges the maintenance module's assets into the APK.
    codecHelperSource = {
        runCatching {
            application.assets.open("dsh-maintenance/session-v4-codec.mjs")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    },
    // NEW: only after Patch 2+3 exist; keep the lambda so a missing seam stays a typed FAILED.
    trialLaunchPort = { dshModeAssembly.trialLaunchPort }
)
```

`nodeExecutable` already defaults to the existing `nativeLibrary` lambda, so no extra argument is
needed for Node. The asset path does not need a copy: `core/dsh-maintenance/src/main/assets/` is
merged into the APK asset namespace by AGP.

## Patch 2 — contract: narrow cross-process trial client (new file)

File: `core/dsh-contract/src/main/java/com/muyuchat/core/dsh/contract/DshTrialHostContract.kt`

```kotlin
package com.muyuchat.core.dsh.contract

/** Typed trial generation state reported by the single runtime owner. */
enum class DshTrialPhase { RUNNING, STOPPED, FAILED, UNKNOWN }

data class DshTrialSnapshot(
    val phase: DshTrialPhase,
    val pid: Long = 0L,
    val generation: Long = 0L,
    val detail: String = ""
)

data class DshTrialHealthSnapshot(
    val hostReady: Boolean = false,
    val homeReadable: Boolean = false,
    val sessionReaderReady: Boolean = false,
    val detail: String = ""
)

/**
 * App-process client of the runtime owner's isolated trial generation (D24).
 *
 * The trial is a non-publishing generation inside the same `:dsh` owner lock/host slot; it must
 * never become the production READY generation and must be refused while production is not
 * confirmed stopped. [stopTrial] returns STOPPED only with a real stop proof.
 */
interface DshTrialHostClient {
    suspend fun startTrial(
        candidateRoot: String,
        candidateFingerprint: String,
        homeRoot: String,
        workspaceRoot: String,
        tmpRoot: String
    ): DshTrialSnapshot
    suspend fun stopTrial(): DshTrialSnapshot
    suspend fun trialStatus(): DshTrialSnapshot
    suspend fun trialHealth(): DshTrialHealthSnapshot
}
```

## Patch 3 — `:core:dsh-runtime` IPC + service + client

1. `DshRuntimeIpc.kt`: add message ids and keys (next free ints; follow existing style):
   - `MSG_TRIAL_START`, `MSG_TRIAL_STOP`, `MSG_TRIAL_STATUS`, `MSG_TRIAL_HEALTH`
   - `KEY_TRIAL_ROOT`, `KEY_TRIAL_FINGERPRINT`, `KEY_TRIAL_HOME`, `KEY_TRIAL_WORKSPACE`,
     `KEY_TRIAL_TMP`, `KEY_TRIAL_PHASE`, `KEY_TRIAL_PID`, `KEY_TRIAL_GENERATION`,
     `KEY_TRIAL_DETAIL`, `KEY_TRIAL_HOST_READY`, `KEY_TRIAL_HOME_READABLE`,
     `KEY_TRIAL_READER_READY`
   - `trialRequestBundle(...)`, `trialSnapshotFrom(bundle)`, `trialSnapshotBundle(...)`,
     `trialHealthFrom(bundle)`; the detail is `take(200)` and never contains a path or token.

2. `DshRuntimeService.kt` (message handler): add four branches that call the owner and reply with a
   `trialSnapshotBundle` / `trialHealthBundle`; reply `FAILED` when the owner throws. `MSG_TRIAL_STOP`
   must not clear the notification, and no trial message may publish `REPLY_SNAPSHOT`.

3. `DshRuntimeServiceClient.kt`: implement `DshTrialHostClient` by reusing the existing
   `request(messenger, what, data, timeout)` plumbing (timeouts: start = `startTimeoutMillis()`,
   stop = 30 s, status/health = 10 s). A null/timeout reply maps to `DshTrialPhase.UNKNOWN`, never
   to STOPPED.

## Patch 4 — `DshRuntimeOwner`: one non-publishing trial generation

This is the real owner work (review estimate 9–13 engineer-days; split it from the app-side slice).
Mandatory behaviour, not just a method signature:

```kotlin
suspend fun startTrial(
    candidateRoot: File,
    candidateFingerprint: String,
    homeRoot: File,
    workspaceRoot: File,
    tmpRoot: File
): DshTrialSnapshot
suspend fun stopTrial(): DshTrialSnapshot
fun trialStatus(): DshTrialSnapshot
fun trialHealth(): DshTrialHealthSnapshot
```

- Run inside the existing `operationMutex` and the existing stop-fence/orphan-scan path. Extract
  `launch(profile: LaunchProfile)` from `startInternal` where the profile selects
  `business home/config/workspace/tmp`, the kernel entry and whether `markReady` may run. The trial
  profile is `publishReady = false`: `machine.beginStart()` publishes `STARTING` (so production
  `ensureStarted` does not attach/launch a second host), but the READY entry is never recorded.
- Trial home: `homeRoot` (app-provided `files/dsh/trial/home`), **never** the real home. Seed it
  from the candidate closure / template with strict `canonicalFile` containment; refuse a candidate
  root that escapes `files/dsh/closure` and refuse if the trial home already holds real data.
- Payload: the trial must execute the **candidate** payload tree when `candidateRoot` carries one;
  until alternate-root payload support exists, launch the verified installed payload and document
  that only the data half of the closure is trialled. Never copy the candidate over the verified
  payload in place.
- Health: readiness gate + a write/read/delete probe in `tmpRoot` + official session header decode
  (the runtime can reuse `DshNodeSessionCodec` indirectly by reporting `sessionReaderReady` only
  after a real session header exists). Never log in, never replay a turn.
- Stop: SIGTERM → SIGKILL via the existing `DshNodeHost.stop`, then the existing
  `findOrphanedHostProcesses` survivor scan. STOPPED requires both; an unavailable scan is
  `UNKNOWN`, never STOPPED.
- `trial/active.json` is app-owned (`ClosureTrialHost` writes it before calling this method); the
  owner additionally keeps its in-memory trial marker and refuses `startProduction` while a trial
  marker is present. Verify the pid's `/proc/<pid>/cmdline` still references the payload/native
  library before killing on recovery.
- `trialStatus()`/`stopTrial()` on a fresh owner (after process death) must consult the owner's
  `/proc` scan, not an in-memory flag.

## Patch 5 — app adapter + mode assembly

File: `app/src/main/java/com/muyuchat/mca/composition/DshRuntimeTrialLaunchAdapter.kt` (new):

```kotlin
internal class DshRuntimeTrialLaunchAdapter(
    private val client: DshTrialHostClient
) : DshTrialLaunchPort {
    override fun launch(request: DshTrialLaunchRequest): DshTrialLaunchResult = runBlocking {
        when (val snapshot = client.startTrial(
            request.candidateRoot.absolutePath,
            request.candidateFingerprint,
            request.homeRoot.absolutePath,
            request.workspaceRoot.absolutePath,
            request.tmpRoot.absolutePath
        )) {
            // RUNNING is the only success; STOPPED is a refusal, everything else a failure.
            ...
        }
    }
    override fun probeHealth(): DshHostHealth = /* map DshTrialHealthSnapshot */ ...
    override fun stopAndProve(timeoutMillis: Long): DshTrialStopProof = /* STOPPED→Stopped, else Unverified */ ...
    override fun status(): DshTrialRuntimeStatus = /* RUNNING→true, STOPPED→false, else null */ ...
}
```

File: `app/src/main/java/com/muyuchat/mca/composition/DshModeAssembly.kt`:

```kotlin
// after `private val client = DshRuntimeServiceClient(...)`
internal val trialLaunchPort: DshTrialLaunchPort = DshRuntimeTrialLaunchAdapter(
    client as DshTrialHostClient // DshRuntimeServiceClient implements it in Patch 3
)
```

No `Any?`/`as?` fallback is introduced: `DshRuntimeServiceClient` implements the typed interface at
compile time.

## Candidate staging contract (already implemented)

`files/dsh/closure/incoming/<candidateId>/candidate.json`:

```json
{
  "candidateId": "candidate-20261021-01",
  "targetAbi": "arm64-v8a",
  "dataSchemaVersion": 5,
  "minReadableDataSchema": 4,
  "migratesData": false,
  "nativeLibraryReplaced": false,
  "prePackagedSlotVerified": true,
  "abiCompatibleWithPrevious": true,
  "artifacts": {
    "source": "source.json", "payload": "payload.zip", "node": "node.json",
    "nativeAbi": "native.json", "addons": "addons.lock", "dependencies": "deps.lock",
    "androidPatch": "android.patch", "mcaPatch": "mca.patch",
    "profileSchema": "profile.schema.json", "pluginLock": "plugin.lock"
  },
  "hashes": { "payload": "<sha256 of payload.zip>" }
}
```

The source refuses: zero/multiple candidates, an id that is not a stable token or differs from the
directory, any artifact outside the candidate tree, symlinks, missing/empty files, a declared hash
mismatch, an unsupported ABI, an incoherent schema, and staging inside the live home/workspace.
It never extracts an archive.

## Console flow (already implemented)

- `REPAIR_SESSION` is advertised only when the proven codec exists; the plan is read-only, apply
  degrades only proven-bad attachments (or truncates a torn tail) and the engine's post-commit
  probe re-decodes every session header.
- `RUN_CLOSURE_UPGRADE` is advertised only when the host is stopped, exactly one verified candidate
  exists and no upgrade is pending. The real-home backup runs as its own maintenance transaction
  **before** the upgrade transaction (the engine holds one lease per transaction), then
  `DshClosureUpgradeManager.run` performs trial → switch → read-only health → commit/rollback.
- `RESUME_CLOSURE_UPGRADE` is advertised for `VERIFIED|TRIAL_RUNNING|TRIAL_PASSED|APPLYING|
  HEALTH_PENDING` and calls `DshClosureUpgradeManager.resume`.
- Session rows carry accurate counts (`user/message` with `source.kind == "user"` + final
  `assistant/attempt` per turn/step); a file the codec cannot count stays `messageCountKnown=false`.

## Risks / rollback

- **Torn-tail truncation** drops only bytes the kernel itself never reads as committed content; the
  original file remains in the engine quarantine and a health failure rolls it back byte-for-byte.
- **Node helper cost**: one process per console snapshot batch (≤50 sessions, cached by file
  identity) and one per repair pass; the helper is bounded (256 MiB input, 120 s, private temp dir).
- **Trial launch from the app process was deliberately not implemented**: it would be a second
  host owner and bypass the stop fence. Until Patch 4 lands, D24 remains typed FAILED / NOT_RUN.
- **Rollback of this slice**: delete the two new app files, revert the two app edits, delete the
  three new core files + asset + fixtures/tests. No schema, prefs, DB or payload bytes changed.

## NOT_RUN (device-only)

- Real `libdshnode.so` exec of the helper on device (native-library exec + linker64 fallback).
- Trial generation launch/stop/orphan recovery on device (blocked on Patch 4).
- Console UI button rendering for the new operations (feature-dsh-workspace owner).

---

# Owner-side trial generation landed (`:core:dsh-runtime` only)

Date: 2026-10-21 · Scope: `core/dsh-runtime` main + JVM tests. No app file, no Gradle/adb run.
Patches 3 and 4 of this handoff are now implemented; Patch 5 needs **no** app change (see below).

## Behaviour delivered

| Requirement | Implementation |
| --- | --- |
| Private non-publishing launcher profile | `launch/DshLaunchProfile.kt` + `launch/DshHostLauncher.kt`: the whole start sequence (ABI, payload, Node exec strategy, env, process start, authenticated entry, readiness gate) is now one `launch(request)` used by both profiles. The trial profile sets `publishReady=false`, `verifyPayload=false`, `prepareCommandLinks=false`, `watchExit=false`, `bindHost=127.0.0.1`, `port=0`, roots `dataRoot/trial/{home,workspace,tmp}`, payload `= production payloadDir` (same verified tree, never re-installed or link-rewritten). |
| Isolated home/workspace/tmp | `trial/DshTrialRoots.kt`: candidate must be a directory below `files/dsh/closure` carrying `candidate.json`; the three data roots must share one trial root below `files/dsh/trial`, must be distinct, must not be the production home/workspace/tmp/payload, and must not traverse a symlink. Reasons are categorical and path-free (they cross Binder). |
| `STARTING` published, READY never | `startTrial` runs under the existing `operationMutex`, then `machine.beginStart()` → `STARTING` (production `ensureStarted`/`apply` refuse, and `startInternal` returns early while a trial marker exists so it cannot kill the trial). The trial path never calls `markReady`, never stores `entry`, `lastReadyConfig`/`lastReadyId`, and never arms the exit watcher. |
| Single slot / fence | `state/DshStopFence.kt` now owns the fenced PID set, the bounded survivor scan, `survivorsAfter` and the one pre-start decision used by production and trial alike. `stop()`/`shutdown()` release the trial too (single host slot) and keep the fence when the stop is unproven. |
| Trial home seeding | Account-only overlay from the **verified official template** via the real `DshGatewayConfigSeeder`, with `dataRoot = trialRoot` so its sibling marker cannot land on the production record. No MCA provider, no bearer token, no capability bridge in the child environment. |
| Data half | `trial/DshTrialDataSeed.kt`: bounded (128 logs / 64 MiB), read-only copy of `home/sessions/**/session*.jsonl.zstd` into the trial home, atomic per file, previous copy reset, credentials/attachments never copied. Only the data half is trialled because the candidate payload is not swapped in this build. |
| Health | `trial/DshTrialHealthProbe.kt`: home proof (real read of `profiles/web/cordis.yml` + `config/`) and session proof (official session-store layout + the official **frame-container** scan port). The detail says "container verified" and never claims a decoded header — the header decode stays with the maintenance codec. Never reports provider/auth. `hostReady` = readiness gate proved the surface **and** the process is alive. TMPDIR write/read/delete probe recorded at launch. |
| IPC | `MSG_TRIAL_START=9`, `MSG_TRIAL_STOP=13`, `MSG_TRIAL_STATUS=14`, `MSG_TRIAL_HEALTH=15`, dedicated `REPLY_TRIAL=16` (never `REPLY_SNAPSHOT`), 12 keys, `trialRequestBundle/From`, `trialSnapshotBundle/From`, `trialHealthBundle/From`, `trialPhaseFrom` (missing/unknown → UNKNOWN), `sanitizeTrialDetail` (200 chars, printable only). The service dispatches all four without touching the notification or `stopSelf`. |
| Client | `DshRuntimeServiceClient : DshRuntimeClient, DshTrialHostClient`. Start/stop answer `UNKNOWN` on unavailable service/timeout/malformed reply (`DshTrialClientPolicy`); status/health never bind (no foreground-service side effect) and answer UNKNOWN when unbound; trial replies never write the frozen production `snapshot`. Timeouts: start = `startTimeoutMillis()`, stop = 30 s, status/health = 10 s. |
| Stop proof | In-memory trial: `host.stop()` SIGTERM→SIGKILL + `fence.survivorsAfter`; `Stopped` needs `outcome.stopped && !fenceRequired && survivors.isEmpty()`; survivors → `FAILED` + fence; scan unavailable → `UNKNOWN` + fence. Fresh owner: durable `trial/active.json` (read-only, app-owned) → `/proc/<pid>/cmdline` must reference this installation's payload or node library before SIGTERM/SIGKILL (`trial/DshTrialRecovery.kt`, via new `Deps.signalProcess`); no journal → an empty same-UID payload scan is the only STOPPED proof. `trialStatus()` re-reads journal + `/proc` on every call (no cached StateFlow). |

## App-side integration contract (no app edit required)

`app/.../composition/DshModeAssembly.kt` already builds
`DshRuntimeTrialLaunchAdapter.fromRuntimeClient(client)` and `MainRuntimeAssembly` already passes
`trialLaunchPort = { dshTrialLaunchPort }`. Because `DshRuntimeServiceClient` now implements
`com.muyuchat.core.dsh.contract.DshTrialHostClient` at compile time, the existing
`runtimeClient as? DshTrialHostClient` resolves, so the trial port is **live** with no app change.
The adapter's mappings line up with the owner's phases:

| Client method (called by the adapter) | Owner behaviour | Adapter result |
| --- | --- | --- |
| `startTrial(candidateRoot, fingerprint, homeRoot, workspaceRoot, tmpRoot)` | refuses (containment/fingerprint/production-not-stopped/busy) → `STOPPED`; launch failed → `FAILED`; settled doubt → `UNKNOWN`; success → `RUNNING` + pid + generation | `STOPPED`→`Refused`, `FAILED`/`UNKNOWN`→`Failed`, `RUNNING`→`Started(pid, generation)` |
| `trialHealth()` | positive-observation only | `providerConfigured=false`, `authReady=false` forced by the adapter |
| `stopTrial()` | positive proof → `STOPPED`; survivors → `FAILED`; doubt → `UNKNOWN` | only `STOPPED`→`Stopped`, all else `Unverified` → journal `RECOVERY_REQUIRED` |
| `trialStatus()` | in-memory host, else journal + `/proc`, fresh each call | `RUNNING`→true, `STOPPED`→false, `FAILED`/`UNKNOWN`→null |

## Verification (no Gradle/adb; Lead owns the build lock)

- Whole module (main + test) compiles with the pinned Kotlin 2.3.21 compiler against
  `android-36/android.jar` + the existing `:core:dsh-contract` debug classes: **exit 0**.
- **402/402 JVM tests pass** (`org.junit.runner.JUnitCore` over every `*Test` class in
  `core/dsh-runtime`), including 41 new ones:
  `DshRuntimeOwnerTrialTest` (13), `DshTrialRootsTest` (8), `DshTrialHealthProbeTest` (8),
  `DshTrialRecoveryTest` (8 + stop-policy/journal), `DshStopFenceTest` (6), `DshTrialIpcPolicyTest` (3).
  Test classpath notes: `org.json` and a local `android.util.Log` stub must precede `android.jar`
  (android.jar ships throwing stubs of both); `unitTests.isReturnDefaultValues` is not enough for
  `org.json`.
- `python tools/architecture/check_architecture_rules.py` → **ALL 16 CHECKED ARCHITECTURE RULES
  PASSED (EXIT=0)**.
- All touched files are valid UTF-8, pure LF, with a trailing newline.

## Risks / exceptions

- **EX-D24-01 (file budget, needs Lead approval):** `DshRuntimeOwner.kt` is 1 215 lines, over the
  800-line owner ceiling (§8). It was already 799 before this slice. Adding the trial generation
  there was chosen over a split that would have introduced a second writer of the one lifecycle
  state machine (or a ≥12-member "host port" that §7 forbids as a nested universal object). Exit
  condition: once `DshRuntimeStateMachine` exposes a typed trial transition, move
  `startTrial`/`stopTrial`/`trialStatus`/`trialHealth` into `trial/DshTrialGenerationHost.kt`
  behind a ≤6-member port. The launch sequence (−~210 lines) and the stop fence (−~90 lines) were
  already extracted in this slice.
- **`sessionReaderReady` needs real data.** The trial health can only report `sessionReaderReady`
  when at least one committed session log was copied into the trial home. With a zero-session
  production home the closure upgrade trial reports `TRIAL_FAILED` ("the isolated home holds no
  committed session log") — honest, but it means the upgrade cannot pass for a fresh user. Product
  decision for the Lead: either the app treats "no committed session" as vacuously ready, or the
  data-half copy stays a precondition of `RUN_CLOSURE_UPGRADE`.
- **Container-level session proof, not a header decode.** `:core:dsh-runtime` may not depend on
  `:core:dsh-maintenance`, so it verifies the official frame container (byte-exact port of the
  official scan) and says so in the detail. If the Lead wants the full header decode in trial
  health, the app-side codec proof has to be projected into `DshTrialHealthSnapshot` (app edit, not
  in this scope).
- **Recovery needs `Deps.signalProcess`** (implemented in `DshAndroidRuntimeAssembly` with
  `android.os.Process.sendSignal`). A composition that leaves the default (`false`) keeps recovered
  trials `UNKNOWN` — by design, never STOPPED.
- **Data duplication:** up to 64 MiB of session logs may be copied into `files/dsh/trial/home` per
  trial; they are reset on the next trial but not deleted at the end. If that matters, the app
  transaction should clear `trial/home/sessions` after `stopTrial`.

## NOT_RUN

- Gradle compile/test and the real APK build (Lead owns the build lock).
- Device run of the trial generation: real Node start under `--port 0`, real readiness gate,
  SIGTERM/SIGKILL against a live `libdshnode.so` tree, `/proc` attribution, and the survivor scan.
- `DshRuntimeService` / `DshRuntimeServiceClient` Binder round-trip (needs an Android runtime; only
  the pure IPC/policy mapping is unit-tested).
- Console UI rendering of the trial operations (feature-dsh-workspace owner).

