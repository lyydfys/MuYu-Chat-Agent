# Tool / Package / Memory / Workflow Security & Acceptance Review

- Date: 2026-10-20 (Asia/Shanghai), source snapshot ~12:53.
- Repository: `E:\MAC`, branch `codex/enterprise-refactor`, HEAD `b26806e2a84dc630a32431714cc53d0c12b17602`.
- Scope: P0 approval/invocation closure, tool-package manifest/archive/store, memory selection/store/search, workflow recipe/preflight/launcher and production exposure.
- Mode: **read-only static review**. No production code was edited. No Gradle, test runner, device, model, MCP server or network call was run. All runtime results are `NOT_RUN`.
- Status meanings: `PASS` = source-level invariant holds at this snapshot; `FAIL` = source contradicts or omits the acceptance condition; `PARTIAL` = library piece exists but is not connected; `NOT_RUN` = no execution evidence.
- Warning: other agents were writing this worktree during the review. Line numbers are for the snapshot above; the files are untracked work-in-progress, not a committed baseline.
- Path notation: `app/...` below means `app/src/main/java/com/muyuchat/mca/...`; `core/agent-runtime/...` means `core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime/...`. The top-five blockers use full paths.

## Verdict Summary

| Area | Check | Status | Evidence |
| --- | --- | --- | --- |
| P0 | Invocation row persisted before approval | PASS (static) | `AgentToolCallHandling.kt:102-118`; `RoomAgentToolInvocationLedger.kt:34-117` |
| P0 | Approval is bound to the exact invocation/args/schema/resources/epoch/policy | PASS (static) | `AgentToolPermissionHook.kt:68-96`; `RoomAgentToolInvocationLedger.kt:119-218`; `AgentApprovalBinding` in `AgentApprovalStorePort.kt:11-46` |
| P0 | One-shot atomic consume (`PENDING -> CONSUMED`) | PASS (static) | `AgentRunDaos.kt:291-313`; `RoomAgentApprovalStore.kt:116-176`; `BrokerAgentToolAuthorization.kt:35-144` |
| P0 | Double click / replay cannot execute twice | PASS (static; runtime NOT_RUN) | `BrokerAgentToolAuthorization.kt:119-143`; `RoomAgentToolInvocationLedger.kt:248-274`; `AgentToolLifecycle.kt:330-355` |
| P0 | TOCTOU: changed args/schema/version/scope/epoch/policy cannot execute | PASS (static; runtime NOT_RUN) | `BrokerAgentToolAuthorization.kt:65-126`; `AgentToolLifecycle.kt:306-355`; `AgentFrozenCommandFactory.kt:234-267` |
| P0 | Restart produces a paired terminal observation and no blind execution | **FAIL** | `AgentStartupRecovery.kt:150-205`; `RoomAgentJournal.kt:50-59`; restart test only covers PENDING (`AgentToolApprovalClosureTest.kt:258-315`) |
| P0 | Tool lifecycle events emitted with bounded/sanitized payloads | **PARTIAL/FAIL** | `AgentToolLifecycle.kt:44-127`; `AgentToolCallHandling.kt:140-149,229-278`; success path has no `TOOL_APPROVAL_RESOLVED` (`AgentPromptedLoopCoordinator.kt:130-148`; only NotExecuted path at `:150-182`, failure path at `AgentOwnerInteractionCommands.kt:390-488`) |
| P0 | Event payload secrecy (no content in events) | PARTIAL (content keys redacted; query terms retained) | `AgentToolLifecycle.kt:42-43,158-179`; `AgentEvent.summary` 4 KiB bound in `AgentEvents.kt:27-29`; test `AgentToolLifecycleEventsTest.kt:290-300` (not run) |
| P0 | Production composition injects ledger, broker and authorization seam | PASS (static) | `MainRuntimeAssembly.kt:424-429,450-457`; `AgentRuntimeAssembly.kt:93-141`; `AgentDataAssembly.kt:119-130` |
| Package | Executable/self-authorizing manifest keys rejected | PASS (static) | `AgentToolPackageLimits.kt:93-99`; `AgentToolPackageManifestParser.kt:442-456` |
| Package | Path traversal / absolute path / symlink / duplicate path rejected | PASS (static) | `AgentToolPackagePathPolicy.kt:14-141`; `AgentToolPackageZipArchive.kt:220-331,378` |
| Package | Manifest/resource/archive hashes checked | PASS (static) | `AgentToolPackageManifestParser.kt:78-79`; `AgentToolPackagePreview.kt:221-270`; `AgentToolPackageFileStore.kt:350-475` |
| Package | No package enters the run tool catalog | PASS (static) | `AgentToolPackageFileStore.kt:59-62`; `AgentToolPackagePreview.kt:12-16,44-45`; no production caller of the package store |
| Package | Dependency cycles / missing dependencies rejected | **FAIL** | `AgentToolPackageManifestParser.kt:95-140` validates only self/duplicate/range; no graph resolution |
| Package | Preview/import/admin path is production wired | **FAIL/NOT_RUN** | `AgentToolPackageFileStore` has no caller outside its own package; feature port defaults to `Unavailable` (`AgentWorkspaceRoute.kt:33`) |
| Memory | Selected-space scoping cannot be widened by model | PARTIAL (library PASS, integration FAIL) | `AgentMemoryTool.kt:115-147`; `AgentMemoryStore.kt:274-297`; but no `CreateAgentTask`/`AgentRunPolicy` memory selection |
| Memory | Task memory selection frozen at creation | **FAIL** | `AgentTaskTypes.kt:74-80` has no memory ids; `AgentRunTypes.kt:43-49` has none; `AgentMemorySelectionService.kt:21-110` has no production caller |
| Memory | `memory.search` is local lexical only, no egress | PASS (static library) | `AgentMemoryLexicalRanker.kt:27-37`; `AgentMemoryToolSet.kt:25-35`; no network code in memory package |
| Memory | Memory write requires explicit approval and no model write tool exists | PASS (static) | `AgentMemoryTool.kt:25`; `AgentMemoryStore.kt:57-61,102-108,183-190,218-223`; no `memory.remember` tool |
| Memory | `memory.search` can be frozen and installed in a run catalog | **FAIL** | `AgentToolArgumentNormalizer.kt:45-51` accepts only four built-ins; `AgentFrozenCommandFactory.kt:114-115` injects that normalizer; `MainRuntimeAssembly.kt:437-457` static built-in catalog only |
| Workflow | No second model loop / no direct tool executor | PASS (static) | `AgentWorkflowLauncher.kt:61-72,167-300`; only `createTask`/`startRun` gateway (`:68-71,230,262`) |
| Workflow | Scheduled/unattended write or network action absent | PASS (static; runtime NOT_RUN) | `AgentWorkflowLauncher.kt:213-219` forces `LOCAL_ONLY`/`offlineForced`; `AgentWorkflowPreflight.kt:141-169` rejects network/write; no scheduler/worker in `agent/workflow` |
| Workflow | No exported trigger / broadcast / boot receiver | PASS (static) | `AndroidManifest.xml:195-201` only `MainActivity` exported; no workflow receiver/worker; `AgentWorkflowRecipe.kt:179-192` has no trigger/schedule field |
| Workflow | Manual launch is production wired | PASS (static) | `MainActivity.kt:378-390`; `MainViewModel.kt:189-195`; `MainRuntimeAssembly.kt:525-538` |

## P0 — Invocation, Approval CAS, Authorize, Double Execution, TOCTOU, Restart

### What is now wired

The loop persists a durable step + `PROPOSED` invocation before freeze:

- `core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime/run/AgentToolCallHandling.kt:102-118` builds `AgentToolProposal` and calls `toolLedger.recordProposed`.
- `app/.../agent/data/RoomAgentToolInvocationLedger.kt:34-117` writes the step and invocation in one Room transaction, with run/task ownership checks and idempotency on `(runId, stepId, callOrdinal)`.

Freeze material is persisted as `VALIDATED` before an approval is created:

- `app/.../agent/composition/AgentToolPermissionHook.kt:68-96` calls `invocations.recordFrozen(...)` before `broker.decide`.
- `RoomAgentToolInvocationLedger.kt:119-218` verifies the frozen command against the proposed row, stores the frozen payload, then CASes `PROPOSED -> VALIDATED`.
- `AgentToolPermissionHook.kt:102-118` then asks `AgentPermissionBroker.decide`.

The approval store is now genuinely atomic for the execution gate:

- `AgentRunDaos.kt:291-313` is one conditional `UPDATE` that requires `PENDING`, the full binding, run epoch, policy version and unexpired `expiresAt`, and clears `pendingInvocationKey`.
- `RoomAgentApprovalStore.kt:116-176` re-reads inside one Room transaction and returns `Consumed` only for the one winning row count.
- `BrokerAgentToolAuthorization.kt:119-143` reconstructs the candidate from durable frozen material and calls `AgentPermissionBroker.authorize`, which calls `consumePending`; it does not accept a bare in-memory `approvedCommand`.
- `AgentOwnerInteractionCommands.kt:194-344` asks the authorization seam before launching any resume; on failure it settles the invocation and pauses the run.

The loop resume path no longer executes a stored command merely because it exists:

- `AgentPromptedLoopCoordinator.kt:130-148` accepts only `resumeState.toolDecision`; `AuthorizedToolCommand` is executed, `NotExecuted` is paired and never executed.
- `AgentToolLifecycle.kt:291-355` checks run identity, epoch, policy version, expiry, the durable invocation row, current catalog descriptor version/schema, then performs `markRunning` before the executor.
- `RoomAgentToolInvocationLedger.kt:248-274` is the single `RUNNING` CAS; `:276-318` prevents terminal overwrite.

### Restart failure (blocking)

The new design consumes `PENDING` inside `BrokerAgentToolAuthorization.authorize` (`BrokerAgentToolAuthorization.kt:119-143`) and only afterwards writes the run transition in `commitApprovalResume` (`AgentOwnerInteractionCommands.kt:351-382`). These are not one transaction. If the process dies after the approval CAS and before the run transition:

- approval is `CONSUMED`; run remains `WAITING_APPROVAL`; invocation remains `WAITING_APPROVAL`;
- `RoomAgentJournal.readRun` exposes only `findPendingForRun` as `pendingApproval` (`RoomAgentJournal.kt:50-59`; `AgentRunDaos.kt:268-273` only status `PENDING`);
- `AgentStartupRecovery.recoverWaitingApproval` returns `commitReview(... "approval_missing")` when there is no pending approval (`AgentStartupRecovery.kt:156-157`), or `commitReview(... "approval_status_CONSUMED")` for a non-PENDING/EXPIRED row (`:158-162`);
- the invocation settlement block at `AgentStartupRecovery.kt:177-197` is unreachable in that state, so the invocation remains `WAITING_APPROVAL` instead of a paired `NOT_EXECUTED`.

This is fail-closed for execution (no blind execution, no double run), but fails the plan's explicit P0 acceptance: process death before/after the click must produce a paired terminal observation. The restart tests do not cover the consumed-pre-transition window: `AgentApprovalRestartClosureTest.kt:90-105` only asserts no success; `AgentToolApprovalClosureTest.kt:258-315` covers a PENDING approval; both use in-memory fixtures.

### Event lifecycle gap (blocking acceptance)

`AgentToolLifecycle` produces bounded summaries (`AgentToolLifecycle.kt:44-127`) and the call handler emits `TOOL_PROPOSED` (`AgentToolCallHandling.kt:140-149`), `TOOL_APPROVAL_REQUESTED` (`:229-278`), `TOOL_STARTED` and `TOOL_RESULT` (`AgentToolLifecycle.kt:258-441`). However, the authorized-success path executes at `AgentPromptedLoopCoordinator.kt:130-148` and emits `TOOL_STARTED`/`TOOL_RESULT` only; `TOOL_APPROVAL_RESOLVED` is emitted only on the `NotExecuted` branch (`:149-182`) and in `settleApprovalFailure` (`AgentOwnerInteractionCommands.kt:390-488`). The required five-event lifecycle is therefore incomplete for a successful approval.

### Event payload secrecy (PARTIAL)

- `AgentEvent.summary` is bounded to 4 KiB (`AgentEvents.kt:27-29`); `AgentToolLifecycle.encode` independently bounds at 4 KiB (`AgentToolLifecycle.kt:149-156`).
- `AgentToolLifecycle` redacts `content`, `text`, `body`, `bytes`, `base64` (`:42-43,158-179`); `TOOL_PROPOSED` and `TOOL_APPROVAL_REQUESTED` carry only a bounded argument summary, not the normalized JSON body (`:44-86`).
- The redaction is a denylist, not a per-tool allowlist. String args such as `query` (used by `workspace.search_text` and `memory.search`) are retained up to 160 chars in the persisted summary. This is a residual privacy concern for search terms; it is not cross-user and not network egress. `AgentToolLifecycleEventsTest.kt:290-300` only injects a `content` secret, so a query-term leak would not be caught by that test.

## Tool Package

The parser and archive reader are strong on the requested hostile cases:

- Executable/self-authorizing keys: dedicated executable-field set (`AgentToolPackageLimits.kt:93-99`) and rejection before use (`AgentToolPackageManifestParser.kt:442-456`). Unknown fields are rejected at root, tool, resource and dependency levels (`:43,172,402,115`).
- Path traversal: `AgentToolPackagePathPolicy.kt:14-141` rejects `..`, absolute paths, backslashes, drive letters, empty segments and non-NFC segments; ZIP parsing normalizes and deduplicates every entry (`AgentToolPackageZipArchive.kt:264-331`) and rejects symlink/device entries (`:245-263`).
- Archive hardening: encrypted entries (`:93-95,223-225`), ZIP64 (`:169-171,240-244`), unsupported compression (`:233-239`), entry/expanded/compression-ratio limits (`:172-177,286-318`).
- Hash checks: canonical manifest hash (`AgentToolPackageManifestParser.kt:78-79`), declared resource SHA-256 verified against streamed bytes (`AgentToolPackagePreview.kt:221-270`), content hash over manifest + resources (`:111-135`), disk re-verification in `AgentToolPackageFileStore.kt:350-475`.
- Catalog exposure: `AgentToolPackageFileStore` explicitly has no enabled flag, install action or catalog registration (`AgentToolPackageFileStore.kt:59-62`); `AgentToolPackagePreview.previewOnly` is computed `true` (`AgentToolPackagePreview.kt:12-16,44-45`); `AgentToolPackageBinding` is metadata only (`core/agent-contract/.../AgentToolPackageContract.kt:146-197`); no package `AgentToolExecutor` exists.
- Production exposure: no production caller of `AgentToolPackageFileStore` or manifest parser was found outside the `toolpkg` package. The feature port defaults to `Unavailable` (`AgentWorkspaceRoute.kt:33`). P0 therefore does not expose a package tool, which satisfies the "no catalog exposure" safety condition but leaves preview/import/admin acceptance `NOT_RUN`.

Blocking package gap:

- `AgentToolPackageManifestParser.parseRequires` (`:95-140`) only rejects self-reference, duplicate ids and malformed ranges. It does not resolve the dependency graph, detect cycles, or reject a missing required package. The plan's P0-B acceptance explicitly requires cycle/missing-dependency rejection. `AgentToolPackageFileStore.preview` commits such a package as a valid preview because `AgentToolPackageValidator.validate` does not consult dependencies (`AgentToolPackagePreview.kt:189-283`).

## Memory

The standalone memory library is largely well-designed:

- Selection object is sorted/unique/bounded and hashable (`AgentMemoryGrants.kt:19-53`); read grant cannot be widened and has a revoked flag (`:61-73`).
- Selection freeze is insert-only per task, idempotent for the same ids, reject for changed ids, and terminal on revoke (`AgentMemorySelectionService.kt:21-130`).
- `MemorySearchTool` re-resolves the grant at execution, rejects a revoked grant, rejects a `spaceId` outside the grant, and checks the optional frozen `spaceId` scope (`AgentMemoryTool.kt:115-147`).
- `AgentMemoryStore.search` rejects empty/long queries, revoked grants, unselected/deleted spaces, caps candidates/pages/citations/tokens, deduplicates by memory id and returns bounded excerpts (`AgentMemoryStore.kt:251-380`).
- Retrieval is offline lexical (`AgentMemoryLexicalRanker.kt:27-37`, delegating tokenization to `KnowledgeLexicalRetriever`); there is no network, embedding or cloud call in the memory package.
- There is no memory write/delete/link/remember tool (`AgentMemoryTool.kt:25`); all store writes require an `AgentMemoryWriteApproval` value (`AgentMemoryStore.kt:57-61,102-108,183-190,218-223`). P1 therefore has no model-facing write path.

Blocking integration gaps:

1. **No task-level selection.** `CreateAgentTask` has no memory field (`AgentTaskTypes.kt:74-80`) and `AgentRunPolicy` has no memory field (`AgentRunTypes.kt:43-49`). `AgentMemorySelectionService.freezeForTask` has no production caller. A task therefore cannot freeze a selected-space scope through the normal creation path.
2. **No catalog installation.** `AgentMemoryToolSet.forRun`/`withMemoryTools` (`AgentMemoryToolSet.kt:25-52`) has no production caller; `MainRuntimeAssembly.kt:437-457` still builds a static built-in + Skill catalog. `memory.search` is never added to a run catalog.
3. **No normalizer/scope for `memory.search`.** `AgentFrozenCommandFactory` defaults to `AgentBuiltInToolArgumentNormalizer` (`AgentFrozenCommandFactory.kt:114-115`), which handles only the four built-in keys and rejects everything else with `toolNotRegistered` (`AgentToolArgumentNormalizer.kt:45-51`). Even if the tool were added to the catalog, the first `memory.search` call would be rejected at freeze; `AgentPermissionCandidate` also requires non-empty workspace paths (`AgentPermissionTypes.kt:19-29`), so the P1.0 sealed memory scope is not implemented.
4. Migration is present and additive: `AgentMemorySchema.kt:18-88` creates four tables/indices, `ChatSessionStoreRoomDatabase.kt:88-93,400-402` registers 28→29 and version 29; `app/schemas/.../29.json` exists. Migration tests were not run.

Net: memory selection scoping and lexical/no-egress behavior are `PASS` at library level, but `FAIL/NOT_RUN` for production acceptance because the feature is unreachable and cannot be frozen.

## Workflow

Static safety checks are good and the manual slice is production-wired:

- `AgentWorkflowLauncher` is a thin manual launcher: one preflight, at most one `createTask`, at most one `startRun` through `AgentWorkflowTaskGateway` (`AgentWorkflowLauncher.kt:61-72,167-300`). It holds no tool executor, model adapter, worker, scheduler or Room writer.
- The launch policy is forced `LOCAL_ONLY` + `offlineForced` and only the recipe's read-only tool keys (`:213-219`); the preflight rejects `NETWORK_ENABLED`, non-read-only/unknown tools and cloud model bindings (`AgentWorkflowPreflight.kt:141-169,182-208`).
- The recipe has no node/script/entry/trigger/schedule field (`AgentWorkflowRecipe.kt:179-192`); steps are declarative checks only.
- Production wiring: `MainRuntimeAssembly.agentWorkflowAdminService` builds `FileAgentWorkflowRecipeStore` + `AgentWorkspaceCommandTaskGateway` + `AgentWorkflowLauncher` (`MainRuntimeAssembly.kt:525-538`); `MainViewModel.kt:189-195` exposes it; `MainActivity.kt:378-390` passes it as both admin and launch ports.
- No exported trigger: the manifest has only `MainActivity` exported (`AndroidManifest.xml:195-201`) and no workflow receiver/service; the `agent/workflow` package contains no `WorkManager`, `Worker`, `Scheduler`, `BroadcastReceiver`, `AlarmManager` or `BOOT_COMPLETED` reference.
- No unattended write/network path exists: there is no scheduler/worker, and the only launch is explicit manual. This is `PASS` by construction/absence; runtime execution is `NOT_RUN`.

Residual risks:

- `ObservationAgentWorkflowCreatedTaskLocator` finds the new task by "unseen id + exact goal" (`AgentWorkflowLauncher.kt:86-101`). Two concurrent launches with the same goal can theoretically anchor the wrong task. Manual UI serialization reduces, but does not prove, this away.
- `AgentWorkflowRecipe` and `FileAgentWorkflowRecipeStore.publish` accept programmatically constructed recipes without re-applying the parser's read-only/network allowlist; the launcher preflight is the only guard. The UI path constructs `LOCAL_ONLY` + read-only keys (`AgentWorkflowAdminService.kt:213-228`), but the store itself is not the enforcement point.
- No scheduler/worker means scheduled/unattended workflow acceptance is `NOT_APPLICABLE`/`NOT_RUN`, not implemented.

## Top 5 Blocking Findings

1. **P0 restart settlement is not atomic.** The approval is consumed before the run transition (`app/src/main/java/com/muyuchat/mca/agent/composition/BrokerAgentToolAuthorization.kt:119-143` → `core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime/run/AgentOwnerInteractionCommands.kt:351-382`), and `app/src/main/java/com/muyuchat/mca/agent/platform/AgentStartupRecovery.kt:150-162` only handles `PENDING`/`EXPIRED`. A crash in the consume→transition window leaves a `CONSUMED` approval + `WAITING_APPROVAL` invocation; recovery reports review/missing instead of a paired `NOT_EXECUTED`. This fails the plan's explicit process-death acceptance even though it remains fail-closed.

2. **Memory cannot reach a run.** There is no memory selection on `CreateAgentTask`/`AgentRunPolicy` (`core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/AgentTaskTypes.kt:74-80`; `core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/AgentRunTypes.kt:43-49`), no production caller of `app/src/main/java/com/muyuchat/mca/agent/memory/AgentMemorySelectionService.freezeForTask`, and `memory.search` is neither added to the static catalog (`app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt:437-457`) nor freezable by the built-in-only normalizer (`app/src/main/java/com/muyuchat/mca/agent/security/AgentToolArgumentNormalizer.kt:45-51`; `app/src/main/java/com/muyuchat/mca/agent/security/AgentFrozenCommandFactory.kt:114-115`). Selection scoping/lexical/no-egress are only library-level claims.

3. **Tool-package dependency acceptance is incomplete.** `app/src/main/java/com/muyuchat/mca/agent/extensions/toolpkg/AgentToolPackageManifestParser.kt:95-140` rejects only self/duplicate/range errors; cycles and missing required packages are accepted into a preview. There is also no production preview/import/admin caller (`AgentToolPackageFileStore` has no caller; feature port defaults to `Unavailable` at `feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/AgentWorkspaceRoute.kt:33`), so P0-B's preview acceptance is not runnable.

4. **P0 lifecycle events are incomplete on the success path.** `TOOL_APPROVAL_RESOLVED` is emitted only for `NotExecuted` (`core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime/run/AgentPromptedLoopCoordinator.kt:149-182`) and in failure settlement (`core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime/run/AgentOwnerInteractionCommands.kt:390-488`); the authorized-success resume at `AgentPromptedLoopCoordinator.kt:130-148` goes straight to `TOOL_STARTED`/`TOOL_RESULT`. The plan requires all five tool lifecycle events; the approval timeline cannot show a resolved row for a successful approval.

5. **P0 security proof is still in-memory only.** The app-level closure tests use `InMemoryAgentApprovalStore` and `InMemoryAgentJournal` (`app/src/test/java/com/muyuchat/mca/agent/composition/AgentToolApprovalClosureTest.kt:104,123,258-262,317-319`); the broker matrix uses `P0ClosureApprovalStore` (`app/src/test/java/com/muyuchat/mca/agent/security/AgentApprovalConsumeClosureTest.kt:28-46`), whose own KDoc says the Room adapter's conditional UPDATE remains separate (`:28-30`); `AgentInvocationApprovalSchemaClosureTest` validates SQL constraints, not `RoomAgentApprovalStore` CAS. No test instantiates the production Room approval store with the real owner and authorization adapter, and no Gradle/device run was performed. The P0 exit gate's "end-to-end tests" evidence is therefore `NOT_RUN`, not `PASS`.

## Verification Status

- `PASS` above means static source inspection at the stated snapshot, not runtime proof.
- Gradle, `verify-code.ps1`, `agent-verify.ps1`, Room migration tests, Compose tests, device/UI, local model execution, MCP servers and workflow workers: **NOT_RUN**, per the task constraint.
- No test result is claimed. The test files listed above are source evidence of intended coverage only.
- No production code, build file, schema JSON or test was edited by this review; this document is the only write.
