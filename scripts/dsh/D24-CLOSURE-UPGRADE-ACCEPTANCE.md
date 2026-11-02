# D24 closure candidate / trial: device acceptance checklist

Status: **NOT RUN** (this document and `closure-upgrade-acceptance.ps1` are written for a later,
explicit device run; nobody executed them in this slice).

Purpose: prove the D24 candidate -> trial -> stop-proof -> rollback path on a real device **without
hand-crafting app-private files**. The candidate is staged by the app's own debug-only staging
source from this APK's verified payload archive, then verified with the same production
`DshClosureCandidateSource` the console uses to advertise `RUN_CLOSURE_UPGRADE`.

## What is being exercised

| Step | Proves |
| --- | --- |
| PRE-01..04 | adb/device online, debuggable package installed, payload asset present, current selector captured, staging area empty for a fresh run |
| NEG-01..03 | a tampered staged candidate is rejected by the real candidate source (typed code, no RUN), then the throwaway candidate is removed |
| STAGE-01..03 | one same-version candidate is materialised from `dsh/payload.zip` + `dsh/MANIFEST.json`, 10 components, declared hashes match bytes, selector relation captured |
| TRIAL-01..03 | production host stops, the isolated trial starts, the journal moves RUNNING -> STOPPED with a positive stop proof and no orphan process |
| ROLL-01 | an interrupted trial (force-stop while RUNNING) recovers to the previous closure: journal ROLLED_BACK, selector fingerprint unchanged, no orphan |
| CLEAN-01 | optional: the staged acceptance candidate is removed and the console no longer advertises RUN |

## Preconditions

1. A **debug** APK of this build is installed (`applicationId com.muyuchat.mca`), because the
   staging bridge lives in `app/src/debug` and is absent from release variants.
   `applicationInfo.flags` must contain `FLAG_DEBUGGABLE`; the acceptance script refuses otherwise.
2. The APK contains `assets/dsh/payload.zip` and `assets/dsh/MANIFEST.json` (normal staging output
   of `scripts/dsh/stage.ps1`). Without them the instrumentation test is skipped, not silently
   passed.
3. The DSH console is reachable (DSH workspace -> console). The trial is started by the operator
   from the console; this build intentionally has no API/agent entry that starts an upgrade.
4. Exactly one managed DSH host slot exists. The console advertises RUN only when the host is
   STOPPED, exactly one candidate is verified and no upgrade is pending.
5. `adb` on PATH (or `$env:ADB`). One device serial from `adb devices -l`.

## Why this is not hand-crafted

`DshCandidateStager` (`core/dsh-maintenance`) streams the packaged archive once, requires the
manifest-declared size and SHA-256 to match, writes one deterministic identity descriptor per
closure component with a hash computed from the bytes it wrote, promotes the tree atomically into
`files/dsh/closure/incoming/<id>`, and re-verifies it through the production source. The Android
bridge is `app/src/debug/.../DshCandidateStagingDebug.kt`; it refuses when the process is not
debuggable. The core surface is behind an opt-in marker and a JVM-test gate that is rejected inside
an Android (Dalvik/ART) process. No console action, Local API route, DSH owner method or agent tool
references staging.

The staged artifacts are **identity descriptors**, not executable payload bytes. This build's trial
executes the already verified installed payload (documented D24 limitation); alternate-root payload
support is a separate slice. Do not report this checklist as proof that a new payload tree was
executed.

## Option A - scripted run

```powershell
pwsh -NoProfile -File scripts/dsh/closure-upgrade-acceptance.ps1 `
    -Device <adb-serial> `
    -OutDir artifacts/dsh-v2/verification/closure-upgrade-<stamp>
```

Options: `-SkipStage` (verify only), `-SkipTrial` (stage/verify only, trial steps NOT_RUN),
`-CrashRecovery` (force-stop during RUNNING and prove recovery/rollback), `-Cleanup`,
`-PreconditionsOnly`.

The trial step is operator-driven: when the script prints `WAITING FOR TRIAL`, open the DSH console
and tap `RUN_CLOSURE_UPGRADE`. The script polls the app-private journals through
`adb shell run-as com.muyuchat.mca cat ...` and only reports PASS on captured evidence.

## Option B - manual checklist

All app-private paths are read through `run-as`, never created by adb.

### PRE

```powershell
adb devices -l
adb shell dumpsys package com.muyuchat.mca | Select-String 'versionName|flags='
adb shell getprop ro.product.cpu.abi
adb shell run-as com.muyuchat.mca cat files/dsh/closure/selector.json
```

Record: package version, debug flag, ABI, selector fingerprint/schema (or "no selector" on a fresh
install: the candidate then declares schema 5 / minReadable 4).

### NEG - tampered candidate is refused

1. Stage a throwaway candidate and verify it is accepted (see STAGE below with
   `candidateId candidate-acceptance-neg`).
2. Tamper one staged artifact through `run-as` (append one byte):
   `adb shell run-as com.muyuchat.mca sh -c "printf x >> files/dsh/closure/incoming/candidate-acceptance-neg/identity/payload.json"`
3. Re-run the verification instrumentation with `stage=false expect=rejected`:
   `adb shell am instrument -w -e class com.muyuchat.mca.dsh.DshClosureCandidateDeviceStagingTest -e dshCandidateStaging true -e candidateId candidate-acceptance-neg -e stage false -e expect rejected com.muyuchat.mca.test/androidx.test.runner.AndroidJUnitRunner`
   PASS only if `DSH_CANDIDATE_REJECTED code=ARCHIVE_INVALID`.
4. Delete the throwaway candidate:
   `adb shell run-as com.muyuchat.mca rm -rf files/dsh/closure/incoming/candidate-acceptance-neg`
   and re-check `incoming` holds zero or one candidate.

### STAGE - stage one same-version candidate

```powershell
adb shell am instrument -w `
  -e class com.muyuchat.mca.dsh.DshClosureCandidateDeviceStagingTest `
  -e dshCandidateStaging true `
  -e candidateId candidate-acceptance-01 `
  -e stage true -e expect available `
  com.muyuchat.mca.test/androidx.test.runner.AndroidJUnitRunner
```

PASS requires `OK (1 test)` plus `DSH_CANDIDATE_ACCEPTED ... components=10`. Then:

```powershell
adb shell run-as com.muyuchat.mca ls files/dsh/closure/incoming/candidate-acceptance-01
adb shell run-as com.muyuchat.mca cat files/dsh/closure/incoming/candidate-acceptance-01/candidate.json
```

Verify: 10 artifacts under `identity/`, a hash for every component, `targetAbi` = device ABI,
`dataSchemaVersion` equal to the live selector (same-version), no second candidate directory.
The console now advertises `RUN_CLOSURE_UPGRADE`.

### TRIAL - run, stop proof, orphan

1. Capture pre-trial process list:
   `adb shell ps -A -o PID,ARGS | Select-String 'files/dsh/payload|libdshnode.so'`
2. In the DSH console run `RUN_CLOSURE_UPGRADE`.
3. While it runs, capture the journal (a few samples):
   `adb shell run-as com.muyuchat.mca cat files/dsh/trial/active.json`
   `adb shell run-as com.muyuchat.mca cat files/dsh/maintenance/upgrade.json`
4. After the action returns, require: `trial/active.json` phase `STOPPED`; `upgrade.json` phase
   `COMMITTED` (or `ROLLED_BACK` with a typed failure); no process with the trial `pid` recorded in
   the journal. A `COMMITTED` upgrade legitimately leaves the single production host running, so the
   post-trial process count is recorded, not compared for equality.
   `adb shell ps -A -o PID,ARGS | Select-String 'files/dsh/payload|libdshnode.so'`
5. Record `closure/selector.json` (fingerprint/root) and confirm it equals the candidate only when
   `upgrade.json` is `COMMITTED`.

### ROLL - interrupted trial recovers to the previous closure

Do this on a second run (or on a fresh install with the same preconditions):

1. Snapshot the selector fingerprint.
2. Start `RUN_CLOSURE_UPGRADE`; as soon as `trial/active.json` shows `RUNNING`, force-stop:
   `adb shell am force-stop com.muyuchat.mca`
3. Relaunch the app and open the DSH workspace so startup recovery runs:
   `adb shell monkey -p com.muyuchat.mca -c android.intent.category.LAUNCHER 1`
4. PASS requires: `trial/active.json` phase `STOPPED`, `upgrade.json` phase `ROLLED_BACK`
   (interrupted before apply) with no selector switch, selector fingerprint unchanged, and no orphan
   trial process.
5. If `upgrade.json` was already `HEALTH_PENDING` when the process died, the resume path may commit
   or roll back per health; record the actual phase and do not call it PASS unless the selector and
   phase agree with the resume policy.

### CLEAN

```powershell
adb shell run-as com.muyuchat.mca rm -rf files/dsh/closure/incoming/candidate-acceptance-01
adb shell run-as com.muyuchat.mca cat files/dsh/closure/incoming 2>$null
```

The console must stop advertising RUN. If the upgrade committed, remove the candidate only after
capturing the selector and upgrade evidence; deleting a committed candidate does not roll back the
selector.

## Evidence layout

`<OutDir>/` receives `result.json`, `summary.md`, `steps.log`, `commands.log`,
`journals/` (candidate.json, active.json, upgrade.json, selector.json samples), `ps/` (process
lists), `instrumentation/` (raw instrumentation output) and `app/` (package/version/ABI dumps).
Every step lists the evidence files it used.

## Status rules

- `PASS` only for captured, re-checkable evidence.
- `FAIL` for a wrong phase/code, an orphan process, a selector switch without `COMMITTED`, or a
  second staged candidate.
- `NOT_RUN` for skipped operator steps (`-SkipTrial`, missed RUNNING window, no payload asset).
- `BLOCKED` for no adb/device/package or a non-debuggable install.
- `NOT_RUN`/`BLOCKED` never count as success and the harness exits non-zero.

## Known limits (do not over-claim)

- Identity descriptors only; the candidate payload bytes are not executed in this build. The
  candidate fingerprint is derived from verified-archive descriptors, not from a byte-for-byte copy
  of the payload tree, so a candidate staged this way is an acceptance fixture - never a shippable
  payload upgrade artifact.
- A deterministic *post-switch* production health failure is not inducible from adb; the
  rollback-after-health-failure branch is proven by JVM tests
  (`UpgradeTest.productionHealthFailureRollsBackToThePreviousSelector` and
  `UpgradeTest.unverifiableBinaryRollbackBecomesRecoveryRequired`), not by this checklist.
- The trial trigger is a console action. That is intentional: no agent/tool can start an upgrade.
- A missed RUNNING window (very fast trial) makes ROLL-01 `NOT_RUN`, not PASS.
