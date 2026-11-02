# MCA Agent V1.1 — Tool Packages, Memory Spaces, and Workflow/Automation Plan

- Status: **PROPOSAL / READ_ONLY_DESIGN / NOT_IMPLEMENTED**.
- Date: 2026-10-20 (Asia/Shanghai).
- Scope: this file only. No source, build, test, schema, manifest, UI or asset is changed by this task.
- Baseline: `E:\MAC`, branch `codex/enterprise-refactor`, HEAD `959de2182c0e64d6e29af4e6cd5f25b1d0d65715`; the working tree already contained unrelated modified/untracked files at read time. `mca.db` is at Room version 28.
- third-party reference: `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` (LGPL-3.0, reference only; no code or module is copied). The three third-party analyses in this directory are inputs, not implementation authority.
- Authority: [MCA_PROJECT_HARD_LOCK.md](../../architecture/MCA_PROJECT_HARD_LOCK.md), [MCA_AGENT_V1_ARCHITECTURE.md](MCA_AGENT_V1_ARCHITECTURE.md), current source and real tests. Where this proposal conflicts with the hard lock or the frozen Agent contract, the hard lock/contract wins.
- Evidence level: static source reading only. Gradle, `verify-code.ps1`, `agent-verify.ps1`, Room migration tests, Compose tests, device runs, models, MCP servers and workflow workers are all **NOT_RUN**.

---

## 0. One-page decision

Four decisions drive the whole plan:

1. **P0 is a security prerequisite, not a feature phase.** The current production path freezes and decides a tool call but does not persist the `agent_invocations` row, does not resume through the broker's one-shot `authorize`, and directly executes the in-memory `approvedCommand`. No package, memory or workflow feature may be exposed to real tasks until that pipeline is closed and proven.
2. **Tool packages are manifest-first declarative bindings to host operations, never executable runtimes.** A package may declare a tool name, a fixed preset, an exposed parameter subset and a reference to a code-owned host operation. It may not supply code, schemas, hooks, UI, network clients or an executor. No JS, WASM, DEX, reflection, shell or `AgentToolExecutor` handle is accepted in any phase of this plan.
3. **Memory is a separate, local-first domain.** Agent memory spaces are not chat knowledge bases and not character memories. A task explicitly selects spaces at creation; the run freezes the selection; reads are auto-allowed only inside that selection; writes require a fresh `ALLOW_ONCE`. P1 is lexical/offline only. Remote embedding and cloud egress of memory are P2 and require source-level egress provenance, not a prompt instruction.
4. **A workflow is a versioned task recipe plus a trigger, not a node graph and not a second loop.** It launches the existing `AgentTaskRepository` + `AgentRunOwner` path. There is no `ExecuteNode` that calls `executeTool`, no embedded JS node, no second model loop and no exported trigger surface. Scheduled runs are local-only and read-only; anything that would require an approval pauses or is rejected before the task is created.

The planned order is:

```text
P0  approval/invocation closure + manifest contract (no executable packages)
P1  package v1 (read-only, then write) + memory spaces/search (local lexical)
P2  workflow/task templates (manual first) + scheduling gate + memory v2
```

---

## 1. Baseline facts and constraints

These are the source facts this proposal is built on. Line numbers are a snapshot aid, not a contract.

| Fact | Evidence at read time | Consequence |
| --- | --- | --- |
| Only four built-in tools | `app/.../agent/tools/AgentToolCatalog.kt:56-64`; `core/agent-runtime/.../validation/AgentV1ToolSpecs.kt:20-33` | Packages must extend a catalog that currently has one source. |
| Package runtime is absent | No tool-package classes under `app/.../agent/extensions`; only Skills and MCP directories exist | Package format, parser, store, catalog assembly and UI all need new, bounded surfaces. |
| Skills are the closest install pattern | `AgentSkillManager`, `AgentSkillImporter`, `FileAgentSkillStore`, `FileAgentSkillSnapshotRepository`, `AgentSkillAdminService` | Reuse the lifecycle *pattern* (private snapshot, manifest, preview, revoke), not executable semantics. |
| One reusable metadata table already exists | `AgentExtensionEntity`/`AgentExtensionDao` (`agent_extensions`) is created in schema 28 and currently unused | Package registry can use `kind=TOOL_PACKAGE`; no package-specific DB migration is required. |
| Approval is not production-wired | `AgentPermissionBroker.kt:28-34` and `AgentToolPermissionHook.kt:17-26` explicitly say the invocation row is not written and resume does not call `authorize`; MCP/ASK ends in typed `DENIED` | **P0 blocker.** Any new tool source would reproduce this gap at a larger blast radius. |
| Resume executes the approved command directly | `AgentPromptedLoopCoordinator.kt:127-160` executes `resumeState.approvedCommand`; `AgentOwnerInteractionCommands.kt:183-259` consumes the approval and hands the command to the loop | A command can reach an executor without the broker's atomic consume/authorize step. This is the primary P0 repair. |
| Approval request UI has no producer | `AgentEventTypes.TOOL_APPROVAL_REQUESTED` exists (`AgentEvents.kt:47-51`), but static search finds no producer; `AgentApprovalProjection.kt:49-111` falls back to a generic “工具调用” | Even a correct approval row would render without tool/action/target detail. P0 must emit bounded lifecycle events. |
| Production catalog is static | `MainRuntimeAssembly.kt:374-398` passes `AgentRunToolCatalogProvider { _, _ -> toolCatalog }` built once from built-ins + Skills; MCP adapters are not in that path | Packages/MCP need a run-scoped, immutable catalog source. Do not add a second static catalog in parallel. |
| Handoff and source disagree about MCP wiring | `MCA_AGENT_V1_HANDOFF.md:16` describes the run-scoped provider; `MainRuntimeAssembly.kt` at read time still passes the static provider | Treat dynamic MCP/package exposure as **not production-proven**; verify the merged source before implementation. |
| Character memory is assistant-scoped | `MemoryEntity` (`ChatSessionStoreEntities.kt:74-84`), `ChatSessionMemoryDao`, `CharacterLongTermMemory.kt` | It has no space/folder/link model and must not become Agent memory by widening a query. |
| Knowledge retrieval is lexical and offline | `KnowledgeBaseStore.retrieve` (`:143-213`), `KnowledgeChunker`, `KnowledgeLexicalRetriever` (`:56-132`) | Good algorithm source; its tables are chat-owned. Agent memory must have its own ownership and tables. |
| No workflow implementation | No `WorkflowExecutor`, scheduler or workflow tables under Agent code; only manual tasks | Workflow must be designed from the existing owner/command seams, not ported as a third-party executor. |
| WorkManager is already a dependency and configured | `app/build.gradle.kts:542`; `McaApplication.kt:53-61` implements `Configuration.Provider` with no custom `WorkerFactory` | Scheduling is possible, but background launch needs an app-scope Agent owner. See P2.0. |
| Agent runtime is currently view-model scoped | `MainViewModel.kt:101-107` constructs `MainRuntimeAssembly` with `viewModelScope`; `AgentRuntimeAssembly` creates the single owner and scope | WorkManager cannot safely launch a run by constructing a second runtime. P2 scheduling is gated on a dedicated owner-lift ADR. |
| DB version is 28 | `ChatSessionStoreRoomDatabase.kt:50-85`; `AgentDatabaseMigration.kt:27-40` | Memory migration must be a real 28→29; workflow tables would be 29→30. No destructive migration, no blind editing of `28.json`. |

### 1.1 What this plan explicitly does not do

- It does not copy third-party code, modules, QuickJS, `ToolPkg` scripts, `package_proxy`, `use_package`, hooks, UI modules, model-provider plugins, device control, Root/Shizuku or exported workflow triggers.
- It does not add a second Agent loop, a second run owner, a second tool registry, a second execution queue or a second approval mechanism.
- It does not add a model-callable “enable package”, “install package”, “approve tool” or “disable safety” action.
- It does not claim that a manifest, a compile or a JVM test proves a device/runtime path. Those stay `NOT_RUN` until separately evidenced.

---

## 2. Target architecture

```text
feature:agent-workspace (UI only)
  AgentWorkspaceRoute / new admin ports
        |
        v
core:agent-contract (stable DTOs and ports)
  AgentWorkspaceCommands / Queries / Tool catalog / Invocation / Workflow types
        |
        v
app composition
  AgentRuntimeAssembly (one owner, one loop, one processor)
        |
        v
core:agent-runtime
  AgentRunOwner -> AgentPromptedLoopCoordinator
        |
        v
  strict schema validation
  -> AgentFrozenCommandFactory (immutable args + resolved scope)
  -> AgentPermissionBroker (task grant / approval / deny)
  -> one-shot consume/authorize
  -> AgentToolCatalog executor
        |
        +--> BUILT_IN host operations (workspace / artifacts)
        +--> PACKAGE-BOUND host operations (declarative aliases only)
        +--> MCP adapters (existing external path, still ASK/egress)
        |
        +--> workspace files / memory space retrieval / egress policy
```

The three new capability planes are inputs to this chain, never alternative chains:

| Plane | What it owns | What it must never own |
| --- | --- | --- |
| Tool package | Manifest, package snapshot, dependency order, host-operation binding, enable/disable, preview | Execution, approval, tool schemas supplied by the package, network, UI, hooks, another catalog |
| Memory | Spaces, items, chunks, retrieval index, source provenance, task selection | Chat session memory, character memory, cloud egress, automatic write from task output without approval |
| Workflow/automation | Versioned task definitions, triggers, launch records, schedule adapter | A tool DAG executor, a second model loop, approvals, direct `executeTool`, exported receivers |

### 2.1 third-party reference → MCA decision

The three third-party documents are used as a capability/anti-pattern inventory, not as a code source.

| third-party mechanism | MCA decision |
| --- | --- |
| ToolPkg manifest, `requires`, enable/disable, private package snapshot, source/hash | **Adapt** the lifecycle and dependency discipline into declarative `mca-tool-package.json`; preserve preview, versioning and revocation. |
| ToolPkg `main` JS, QuickJS, Java/Kotlin bridge, `Java.loadDex`, WASM, UI modules, hooks, `package_proxy`, model-controlled `use_package` | **Reject.** No arbitrary code, no proxy, no model activation, no UI/hook surface. |
| MemorySpace, document chunks, lexical/semantic retrieval, links, auto-save windows, remote embedding | **Adapt** spaces/chunks/source provenance; P1 lexical and explicit selection; P2 optional local embedding/links/write-after-approval. Remote embedding is not a default. |
| Workflow DAG (`ExecuteNode` -> `executeTool`), exported trigger, schedule/tasker/intent/speech/app-open triggers | **Replace** with a versioned task recipe and app-internal manual/daily/interval trigger. No node executor, no exported trigger, no unattended write/network. |
| Permission model: global `ALLOW`, `ALWAYS_ALLOW`, model-controlled `deny_tool`, `autoApprove` | **Reject.** MCA keeps `ALLOW_ONCE` + full binding + 5-minute TTL; no persistent/auto/session approval. |
| Chat-native tool rows, compact tool display, approval sheet information grouping | **Adapt only the information architecture** into existing MCA compact timeline/approval surfaces and `McaTheme`; no third-party visual language or “always allow” control. |

---

## 3. Non-negotiable invariants

Every phase must satisfy these. A phase that cannot is not “partially done”; it is stopped.

| ID | Invariant | Enforcement point |
| --- | --- | --- |
| I1 | **Single choke point.** Every tool call, regardless of source, goes through descriptor lookup, strict schema/argument validation, freeze, broker decision, one-shot authorization, executor and result journal. | `AgentPromptedLoopCoordinator.handleToolCall`; `AgentFrozenCommandFactory`; `AgentPermissionBroker`; `AgentToolCatalogAssembler` |
| I2 | **No arbitrary code.** Package and workflow inputs cannot introduce JS/WASM/DEX/JAR/SO, shell commands, reflection, WebView evaluation, a script entry point, a hook, a UI module or an `AgentToolExecutor`. | Package parser/validator; catalog source; P0 negative fixtures |
| I3 | **Approval is one-shot and fully bound.** `ALLOW_ONCE` only; 5-minute TTL; binding includes invocation, args hash, schema hash, resource fingerprint, run epoch and policy version; no persistent/session/auto approval. | `AgentApproval`, `FrozenToolCommand`, `AgentApprovalBinding`, `AgentPermissionBroker`, owner CAS |
| I4 | **Default deny outside explicit scopes.** Workspace reads are allowed only through the task grant. Memory reads are allowed only for spaces selected and frozen for this task. Everything else asks or denies. | `AgentWorkspaceReadGrant`, new `AgentMemoryReadGrant`, `AgentPermissionBroker.decide` |
| I5 | **Egress is independent of tool success.** Tool results carry a data class and source provenance; LOCAL_ONLY/SECRET do not flow to cloud/MCP merely because a tool returned them. | `AgentNetworkEgressPolicy`, `AgentMcpEgressGuard`, future memory source labels |
| I6 | **Package provenance is in the approved object.** A package update changes the descriptor version/hash and invalidates old approvals; disable/uninstall invalidates pending approvals for non-terminal runs. | `AgentToolDescriptor.version/schemaHash`; `AgentToolPackageManager`; `invalidatePendingForRun` |
| I7 | **Memory isolation.** No cross-space query, no task-to-task leak, no chat memory read by widening a query, no remote embedding by default. | `AgentMemoryStore`; `AgentMemoryReadGrant`; P1 local-only catalog rule |
| I8 | **No second owner or loop.** Workflow launches tasks/runs through the existing owner. It never executes tools, never holds a model adapter and never creates a parallel `AgentToolCatalog`. | `AgentWorkflowLauncher` -> `AgentWorkspaceCommands.createTask/startRun`; P2.0 ADR |
| I9 | **Closed trigger surface.** No exported broadcast, Tasker, intent, speech, app-open or external HTTP trigger for workflows in this plan. Schedules are app-internal WorkManager work only. | `AgentWorkflowScheduler`; manifest/exported checks |
| I10 | **Unattended runs cannot create side effects.** Scheduled workflows are local-only and read-only; write/network tools are rejected at preflight. Manual runs use the normal approval sheet. | `AgentWorkflowDefinition`; `AgentWorkflowLauncher`; descriptor side-effect check |
| I11 | **Data continuity.** One `mca.db`; additive Room migrations; no destructive migration; old chat/image/knowledge/assistant data remains readable; imported task packages never restore execution/approvals/grants. | `ChatSessionStoreRoomDatabase`; `AgentDatabaseMigration`; migration tests |
| I12 | **Truthful UI and evidence.** UI renders only contract values; approval rows show actual targets and source; no “completed” without a verified artifact/final; tests use PASS/FAIL/NOT_RUN/BLOCKED, never a claim of runtime proof. | UI ports; approval projection; acceptance matrix |

### 3.1 Approval and egress matrix

| Capability | Default decision | Scope binding | Egress rule |
| --- | --- | --- | --- |
| Built-in workspace read (`workspace.*`) | `ALLOW_FOR_TASK` | Task workspace read grant; logical paths ≤ task root. | Local file only; no network. |
| Built-in artifact write (`artifact.write_text`) | `ASK` (`ALLOW_ONCE`) | Exact target path + `expectedOldHash` + content hash. | Local private workspace only. |
| Package read tool | `ALLOW_FOR_TASK` only if its host operation is read-only and the resolved scope is inside the task grant; otherwise `ASK`/deny | Package descriptor + host scope + workspace grant. | P1 no network host operations; local-only. |
| Package write tool | `ASK` | Package version/hash + exact write path/content/precondition. | Local private workspace only. |
| `memory.search` | `ALLOW_FOR_TASK` only for selected spaces; otherwise deny | Frozen `MemorySpaces` scope + memory read grant. | P1 local-only; network-enabled run does not expose the tool. |
| `memory.remember` (P2) | `ASK` | Exact space/item/diff + source task/run. | Local by default; cloud requires source-level egress grant. |
| MCP tool | `ASK` | `mcp:<serverId>:<name>` + schema snapshot + server/origin. | Run-scoped egress grant, per-call approval, no auto-approve. |
| Cloud model step | Run policy + explicit egress grant | Provider origin/model/data classes/source set. | No LOCAL_ONLY/SECRET; memory/package sources only after P2 provenance tests. |
| Scheduled workflow | No interactive approval at trigger time | Preflight only; effective tools must all be read-only. | LOCAL_ONLY only; write/network tool -> reject, never queue invisible approval. |

This matrix is the implementation checklist for P0/P1/P2. A capability that cannot point to its row is not ready.

---

## 4. P0 — close the execution chain, then freeze the package contract

P0 has two deliverables. Neither is optional and neither exposes a new user capability.

### 4.1 P0-A: invocation, approval and tool-lifecycle pipeline

#### Current failure mode

Today the model can propose a tool, the hook can freeze it and the broker can return `NeedsApproval`, but:

- no `agent_steps`/`agent_invocations` row is persisted, so `RoomAgentApprovalStore.insertPending` rejects;
- `AgentToolPermissionHook` only decides; it does not persist or emit an approval-request event;
- `AgentRunOwner.approvalLocked` commits `ConsumeApprovalTransition` and launches the loop with `approvedCommand`;
- the loop's resume branch executes that command without calling `authorize`;
- no `TOOL_PROPOSED` / `TOOL_APPROVAL_REQUESTED` / `TOOL_STARTED` / `TOOL_RESULT` events are emitted, so the approval sheet and timeline lack real tool detail.

#### Required sequence

```text
model tool call
  -> strict schema and argument validation
  -> persist step + invocation (PROPOSED/VALIDATED, idempotency key, bounded args ref)
  -> freeze immutable command + resolved resource scope
  -> broker.decide:
       ALLOW_FOR_TASK (read-only inside a task grant)
       NEEDS_APPROVAL (persist approval row; run -> WAITING_APPROVAL)
       DENIED (record terminal invocation result; model gets typed denial)
  -> user decision
  -> broker/owner revalidate policy, epoch, expiry and binding
  -> atomic one-shot consume (PENDING -> CONSUMED) before the executor
  -> persist invocation RUNNING + TOOL_STARTED event
  -> executor (only the authorized command)
  -> persist invocation terminal + TOOL_RESULT event + result ref
  -> loop continues; a late/replayed approval can never execute twice
```

The on-resume path must not execute `resumeState.approvedCommand` merely because it is present. The owner must produce an authorization for the exact stored approval/command, and the loop must execute only that authorization.

The ledger/authorizer are narrow **runtime-facing ports** declared in `core/agent-runtime` (`run`/`journal` seam); their Room and `AgentPermissionBroker` adapters live in `app/.../agent/composition`. Core runtime must not import app, Room, engine or Android classes.

#### Exact integration points

| Seam | Current file/symbol | Required change |
| --- | --- | --- |
| Proposal persistence | `AgentPromptedLoopCoordinator.handleToolCall` (`:460-587`) | Before `commandHook.freeze`, call an injected `AgentToolInvocationLedger.recordProposed(...)` that writes the step/invocation in one transaction. The ledger must be idempotent on `(runId, stepId, callOrdinal)` and must reject a missing/foreign run. |
| Freeze/decision | `AgentToolPermissionHook.hook/freezeAndDecide` (`:32-83`) | Keep freeze semantics; after `NeedsApproval`, ensure the invocation row exists, then create the approval. Emit a bounded approval summary (`tool`, `action`, `read`, `write`, `network`, `args`, `source`, `path`, `oldHash`, `newHash`). No raw arguments, no secrets. |
| Approval CAS | `AgentApprovalStorePort`; `RoomAgentApprovalStore` | Add a single atomic `consumePending`/equivalent path that accepts only PENDING, verifies the full `AgentApprovalBinding`, checks expiry, and returns `Consumed` exactly once. Do not rely on an in-memory `pendingApprovals` map for security. |
| Resume authorization | `AgentRunOwner.approvalLocked` (`AgentOwnerInteractionCommands.kt:183-259`) | Re-read the run, approval, invocation and frozen catalog; reconstruct the candidate/scope; call the broker's one-shot authorization; on success commit the run state transition and launch the authorized resume. On failure, record `NOT_EXECUTED`/`DENIED` and do not execute. |
| Loop execution | `AgentPromptedLoopCoordinator.executeInternal` resume branch (`:127-160`) | Remove the unverified direct execution path. Accept only an authorization object (or an `AuthorizedCommand`) whose command identity matches the frozen command, then execute. |
| Result journal | `AgentPromptedLoopCoordinator.executeFrozenCommand` (`:589-625`) | Record `RUNNING` before the executor and a terminal invocation result after it. Preserve cancellation propagation; never convert cancellation into a success. |
| Tool events | `AgentEventTypes` (`AgentEvents.kt:47-51`); `AgentTimelineProjection`; `AgentApprovalProjection` | Emit `TOOL_PROPOSED`, `TOOL_APPROVAL_REQUESTED`, `TOOL_APPROVAL_RESOLVED`, `TOOL_STARTED`, `TOOL_RESULT` with bounded, sanitized summaries. Approval summary must include package/MCP/built-in source and target lists. `AgentApprovalProjection` does **not** recognize `source` today; add it to `ParsedApprovalSummary`/`mapApprovalFields` and render a bounded source label. |
| DB | Existing `agent_steps`, `agent_invocations`, `agent_approvals` | No schema change expected. Use existing unique constraints: one running invocation per step, one pending approval per invocation. Verify against the actual exported schema before coding. |
| Error/restart | `AgentStartupRecovery`; `AgentRunOwner` settlement | A pending approval whose invocation never executed becomes `NOT_EXECUTED` on recovery; an approval after process death is not reusable. Do not auto-resume. |

#### P0-A acceptance

- Approve once -> executor runs exactly once; a double click/replay never runs twice.
- Deny -> executor not called; a typed `DENIED` result is paired with the invocation.
- Change arguments, tool version/schema, resource fingerprint, run epoch or policy version after approval -> authorization rejects and no executor call occurs.
- Process death before or after the click -> no blind execution; recovery produces a paired terminal observation.
- No invocation row -> no pending approval and no executor call.
- Approval sheet shows the tool, action, read/write/network targets, bounded args, source and expiry; timeline shows proposed/approval/start/result rows.
- Built-in, package and MCP sources all use the same path; there is no second `executeTool` call site for extension code.

### 4.2 P0-B: manifest-first tool-package contract (preview only)

P0 defines and validates the format, but **does not expose package tools to a run**. This lets the format and its negative tests stabilize without extending the unclosed approval path.

#### Package format

A package is a ZIP or user-selected directory with one root manifest named `mca-tool-package.json`. It is a private immutable snapshot after import, with no executable content.

```json
{
  "schemaVersion": 1,
  "packageId": "research-basics",
  "packageVersion": "1.0.0",
  "apiVersion": 1,
  "displayName": "Research basics",
  "description": "Curated read-only workspace tools and package notes.",
  "requires": [],
  "tools": [
    {
      "name": "read_brief",
      "displayName": "Read the attached brief",
      "description": "Read a bounded UTF-8 window of inputs/brief.txt.",
      "hostOperation": "workspace.read_text",
      "preset": { "path": "inputs/brief.txt" },
      "expose": ["offset", "limit"],
      "timeoutMillis": 10000
    }
  ],
  "resources": [
    {
      "path": "NOTES.md",
      "mediaType": "text/markdown",
      "sha256": "lowercase-hex-of-the-file"
    }
  ]
}
```

Field rules:

| Field | Rule |
| --- | --- |
| `schemaVersion` | Exactly `1`; unknown major rejects. |
| `packageId` | Lowercase `[a-z0-9][a-z0-9-]{1,63}`; stable; cannot be changed by an update. |
| `packageVersion` | Bounded version string; comparison is explicit, not locale/string-order guesswork. |
| `apiVersion` | Exactly `1` for this plan; unsupported API rejects with a typed reason. |
| `requires` | At most 8 dependencies; package IDs/version ranges; cycles and missing dependencies reject. |
| `tools` | 1–32; unique `name`; lower snake case; total installed tools across enabled packages ≤ 32. |
| `tools[].hostOperation` | Must be in the code-owned allowlist. P1 read-only: `workspace.list`, `workspace.read_text`, `workspace.search_text`. P1 late: `artifact.write_text`. No HTTP, MCP, shell, UI, package-proxy or arbitrary executor id. |
| `tools[].preset` | Static JSON values only; validated against the host operation at install; cannot contain `network`, credentials or a model/provider reference. Model cannot override a preset key. |
| `tools[].expose` | Subset of the host operation's model-facing parameters; the package cannot add a parameter or a new schema keyword. |
| `tools[].timeoutMillis` | Optional; `1..hostOperation.timeoutMillis`. It can only lower the host timeout. |
| Resources | Markdown/text/metadata only; normalized relative paths; size/hash checked; no scripts, binaries, symlinks, `..`, duplicate normalized paths, encryption or unknown compression. |
| Unknown fields | Rejected. In particular reject `main`, `entry`, `script`, `code`, `exec`, `wasm`, `dex`, `jar`, `so`, `ui`, `hooks`, `permissions`, `network`, `autoApprove`, `enabledByDefault`. |

#### No-JS / no-bypass rules

- The package supplies **no schema**. The run-facing schema is derived from the trusted host operation: remove preset keys, intersect with `expose`, preserve required fields that remain exposed, set root `additionalProperties=false`, and canonicalize/hash through `SupportedToolSchemaValidator`.
- The package supplies **no executor**. It selects a code-owned `hostOperation`; the actual executor is constructed by the app from the same host implementation used by built-in tools.
- The package cannot call `AgentToolExecutor`, obtain a `Context`, open a socket, access Room, read another package's snapshot or ask the model to auto-enable it.
- A ZIP containing `.js`, `.mjs`, `.cjs`, `.ts`, `.wasm`, `.dex`, `.jar`, `.so`, `.apk` or an executable entry point is rejected as a whole. “Preview-only” is not sufficient for a tool package.
- A package is enabled only by an explicit user toggle. The model never sees an `enable_package`/`use_package` tool and cannot cause a package to become active mid-run.
- Package resources are metadata in P0/P1. They do not enter the model context automatically; P2 may add a bounded `package.read_text` host operation with its own resource scope.

#### Exact integration points

| Seam | Proposed location | Responsibility |
| --- | --- | --- |
| Manifest model/validation | `app/.../agent/extensions/packages/AgentToolPackageManifest.kt`, `AgentToolPackageParser.kt` | Strict parser; typed errors; no Android types. |
| Archive/tree ingestion | `AgentToolPackageArchive.kt`, `AgentToolPackageImporter.kt` | Bounded ZIP/tree copy, path/hash/size checks, private snapshot. |
| Registry | `AgentToolPackageStore.kt` over existing `AgentExtensionDao`; `kind="TOOL_PACKAGE"` | Durable manifest row; no credentials; `manifestRef` points to a private payload/snapshot. |
| Preview | `AgentToolPackagePreview.kt`; future `AgentPackageAdminPort` | User sees package ID/version, tools, host operations, side effects, dependencies, resources, rejected files. |
| Tests | `app/src/test/.../agent/extensions/packages/*` | Parser/archive/security fixtures; no network/model/device. |

#### P0-B acceptance

- A manifest with a script/entry/wasm field is rejected before any file is committed.
- A package with a path escape, duplicate normalized path, symlink, zip bomb or an unsupported resource type is rejected.
- A cycle/missing dependency/unsupported API version is rejected with a stable, content-free error code.
- A package tool whose `hostOperation` is not allowlisted, or whose `preset`/`expose` cannot be validated, is rejected.
- Preview lists every installed file and every tool but offers no “run” or “enable for this run” action until P1.
- No package tool can reach `AgentToolCatalog` in P0; a stored manifest row is not a catalog entry.

### 4.3 P0 exit gate

P0 is complete only when all of the following are true:

- the approval/invocation pipeline is wired for built-ins and has end-to-end tests (approve/deny/replay/restart/TOCTOU);
- tool lifecycle events render real approval and timeline content;
- a package can be parsed/previewed/rejected, but no package tool is in a production run catalog;
- `AgentToolSource`/catalog conflicts are defined for the future package source;
- no new Room migration, no package UI enable toggle, no workflow code and no memory code have shipped.

If P0 cannot be closed, the correct fallback is to keep the current fail-closed behavior and not expose packages, memory tools or workflows.

---

## 5. P1.0 — generalize the frozen resource scope (no user-visible feature)

Memory and package tools need a scope that is not always a task workspace path. The current `AgentResolvedToolScope` hard-codes `taskRoot` plus logical paths and `AgentPermissionCandidate` requires non-empty paths. That cannot safely express “selected memory spaces”.

This is a **prerequisite slice** before package read tools or memory search. It must be behavior-equivalent for the four existing tools.

### 5.1 Contract shape

Introduce a sealed scope, owned by the app security layer (not feature/UI):

```kotlin
sealed interface AgentResolvedToolScope {
    val networkDestinations: List<String>

    data class TaskWorkspace(
        val taskRoot: String,
        val logicalPaths: List<String>,
        val expectedOldHash: String?,
        override val networkDestinations: List<String> = emptyList()
    ) : AgentResolvedToolScope

    data class MemorySpaces(
        val spaceIds: List<String>,
        override val networkDestinations: List<String> = emptyList()
    ) : AgentResolvedToolScope
}
```

Rules:

- `TaskWorkspace` preserves today's canonical path/precondition behavior exactly.
- `MemorySpaces.spaceIds` is sorted/unique, bounded (for example ≤ 16 selected spaces), and every ID must be in the task's frozen selection; a normalizer never accepts a space ID from the model that is not in the run policy.
- `AgentArgumentNormalization.Normalized` returns the sealed scope instead of separate paths/expected hash.
- `AgentFrozenCommandFactory` serializes scope into `resourcePreconditionsJson`, computes `resourceFingerprint`, and never re-derives after approval.
- `AgentFrozenIntegrity.matches` dispatches by scope type; for memory it rechecks canonical space IDs and the same precondition JSON.
- `AgentPermissionBroker.decide` accepts the workspace grant and optional memory grant; `AgentPermissionCandidate` no longer requires non-empty logical paths for a memory scope.
- `AgentToolDescriptor.isWorkspaceRead()` becomes scope-based (`sideEffect == READ_ONLY && scope is TaskWorkspace`), not a `toolKey.startsWith("workspace.")` string check.
- The old `AgentFrozenCommandFactory` default constructor remains a built-in-only normalizer for tests; production injects a composite normalizer that dispatches by tool key/provider.

### 5.2 Acceptance

- All existing four-tool tests still pass with unchanged hashes for the same inputs.
- Existing workspace grant behavior is unchanged; a read outside the grant still denies.
- A memory scope with an unselected/foreign/duplicate/over-limit space ID is rejected before approval.
- A forged or mutated scope cannot pass `AgentFrozenIntegrity`.
- No memory tool is registered yet.

---

## 6. P1 — tool packages v1

### 6.1 Product value and limits

A package is a **curated tool surface**, not a plugin runtime. It can:

- expose a smaller, named tool set for a task type;
- fix safe defaults such as `inputs/brief.txt`;
- reduce prompt/schema noise and accidental tool selection;
- carry Markdown notes/metadata for the user;
- be versioned, previewed, enabled, disabled and removed.

It cannot:

- add a new algorithm or network endpoint;
- run code or UI;
- change its own permission/data class;
- enable itself, MCP, a provider or another package;
- create workflows that auto-enable.

### 6.2 Packaging and host binding

A package tool becomes an `AgentToolExecutor` only through a code-owned binding:

```text
package manifest tool
  -> PackageToolBinding(
       packageId, packageVersion, toolName,
       hostOperation (allowlisted),
       preset (static, validated),
       exposedParameters (subset),
       timeout (min of host and requested),
       packageContentHash
     )
  -> derived AgentToolDescriptor(
       toolKey       = "pkg.<packageId>.<toolName>",
       version       = "<packageVersion>+<packageContentHash.take(16)>",
       schemaJson    = derived subset of host schema,
       schemaHash    = canonical hash,
       sideEffect    = host sideEffect (never lower),
       dataClass     = host dataClass (never lower)
     )
  -> PackageBoundToolExecutor
       -> HostOperationPort.execute(normalizedArgsJson, context)
       -> returns a normal AgentToolResult
```

Key rules:

- The binding is resolved from the installed snapshot at run-catalog build time and is immutable for the run.
- `ToolInvocation`/approval binding uses the **package descriptor** and its schema hash/version, so a package update cannot ride an old approval.
- The package wrapper does not call the broker and does not receive a second approval. It receives only an already-authorized frozen command; it maps normalized arguments to the same host operation implementation used by built-ins.
- The host operation never reads the model's raw string; it receives the frozen normalized arguments.
- `preset` keys are removed from the run-facing schema; if the model supplies one, strict validation rejects it as an extra field.
- `expose` cannot add a parameter. If a required host parameter is neither preset nor exposed, installation rejects the tool.
- No package can bind to another package tool, MCP tool, proxy, shell, HTTP, UI or model provider.

### 6.3 Catalog assembly

| Seam | Current | P1 change |
| --- | --- | --- |
| Source enum | `AgentToolSource { BUILT_IN, MCP }` in `AgentToolCatalogAssembler.kt:11` | Add `PACKAGE`. |
| Assembler | `assemble(builtInCatalog, enabledSkills, mcpTools)` | Add `packageTools: List<AgentToolExecutor>`; verify every package tool key/version/source binding; duplicate/schema conflicts reject the whole assembly as today. |
| Runtime source | `RunScopedAgentToolCatalogProvider` in `app/.../agent/composition` | Resolve enabled package snapshots for this run, build package executors, pass them to the assembler with built-ins and MCP. The snapshot is immutable for the run. |
| Production wiring | `MainRuntimeAssembly.agentRuntimeAssembly()` (`:374-398`) currently uses a static catalog | Replace with the run-scoped provider plus package/MCP sources only after P0/P1 gates; do not leave two providers. |
| Model prompt | `AgentPromptedSystemPromptBuilder` lists tools from the catalog | No package-specific prompt channel; derived descriptors are the only visibility. |
| Approval UI | `AgentApprovalProjection` currently maps only tool/action/read/write/network/args/path/hashes; unknown keys become args text | Add explicit `source` parsing and `AgentApprovalUi.sourceLabel`; write `source=package:<id>@<version>` and show it in the sheet/timeline. |

### 6.4 Lifecycle, updates and revocation

- Install: preview → user confirms → private snapshot → DB manifest row (`agent_extensions`, `kind=TOOL_PACKAGE`). If DB write fails, delete the new snapshot.
- Update: new snapshot + new DB row version; old snapshot retained while any run/task references it. Old approvals are invalidated for non-terminal runs. A current run keeps its frozen catalog; a resumed execution resolves the new catalog and cannot use the old tool version without a new freeze.
- Disable/uninstall: explicit user action; invalidate pending approvals for all non-terminal runs whose catalog contained the package’s tool keys; do not cancel already executing tools blindly. A running tool is allowed to settle; its result is recorded. The package cannot reappear in a later catalog.
- Enable cap: at most 8 enabled packages and 32 total package tools (or a stricter measured prompt budget). A package cannot be enabled if its dependency is missing/disabled.
- No package download/market in P1. Only local ZIP/directory import.
- Package resources are not executable and not auto-injected. P2 may add `package.read_text` with a dedicated package-resource scope.

### 6.5 UI surfaces

- `AgentWorkspaceSurface.PACKAGES` and `ui/AgentPackagesScreen.kt`.
- Drawer “插件” expands to **工具包 / Skills / MCP 服务器**.
- Package row: display name, ID@version, tool count, side-effect summary, enabled switch, uninstall.
- Import flow: document picker → preview screen showing tools/host operations/side effects/dependencies/resources; confirm install. No silent install from a task.
- Task detail approval sheet: source line `工具包 · <name> @ <version>`; no “always allow”.
- Task timeline: package tool rows use the same compact row as built-ins; package source in details.
- No package UI modules, routes, widgets, hooks or model provider registration.

### 6.6 Tests and rollback

Required tests:

- manifest valid/invalid matrix (unknown field, script, wasm, path escape, cycles, missing dependency, API version, over-limit, duplicate tool);
- archive/tree security (zip slip, symlink, compression ratio, duplicate normalized path, file count/size);
- derived schema equals canonical host subset; preset cannot be overridden; extra parameter rejected;
- package descriptor version changes when content changes;
- package tool executes through the same broker and records the same `agent_invocations` row;
- approval on a package write binds package version/schema and fails after update;
- disable/uninstall invalidates pending approvals and removes the tool from the next run;
- duplicate/conflict with built-in/MCP is rejected;
- Compose tests for package list/preview/approval source; visual check that only the existing design system is used.

Rollback: disable the package source at composition (same as P0) and keep installed manifests/snapshots for a later re-enable; do not delete user snapshots on code rollback.

---

## 7. P1 — memory spaces and retrieval

### 7.1 Ownership and data model

Memory is a new Agent-owned domain. It does **not** reuse `knowledge_*` tables, `memories`, or `assistant_memory_turns` as its write target. Existing knowledge retrieval may donate pure algorithms only.

New tables (Room 29, one additive migration owned by `AgentDatabaseMigration`):

| Table | Key fields | Purpose |
| --- | --- | --- |
| `agent_memory_spaces` | `spaceId` PK, `displayName`, `description`, `egressClass` (`LOCAL_ONLY` default), `revision`, timestamps, `schemaVersion` | Named spaces the user creates/selects. |
| `agent_memory_items` | `memoryId` PK, `spaceId` FK, `title`, `content` (bounded), `contentHash`, `sourceKind`, `sourceRef`, `revision`, `tombstonedAt`, timestamps | One durable memory/note. |
| `agent_memory_chunks` | `chunkId` PK, `spaceId`/`memoryId` FKs, `position`, `content`, `contentHash`, `estimatedTokens` | Searchable chunks (reuse `KnowledgeChunker` algorithm or a bounded equivalent). |

Rules:

- `spaceId`, `memoryId` and chunk IDs are stable generated IDs; no absolute path in a model-visible ID.
- Memory text is `PRIVATE` by default; `LOCAL_ONLY` spaces cannot be sent to cloud/MCP even in P2 without a new explicit egress grant.
- Tombstone/delete removes the item/chunks from retrieval; historical task events may retain source hashes but not content.
- No cross-space query and no “search all” across unselected spaces. A space must be selected for the task.
- Import from chat/knowledge/character memory is P2 and copy-on-write with a preview; never a direct cross-table read.

#### Exact integration points

| Seam | Proposed location | Responsibility |
| --- | --- | --- |
| Entities/DAO | `app/.../agent/data/AgentMemoryEntities.kt`, `AgentMemoryDao.kt` | Space/item/chunk rows and bounded queries. |
| Migration | `AgentDatabaseMigration.kt` (28→29) + `McaRoomDatabase` registration | Additive tables/FKs/indices; export `29.json`; no destructive migration. |
| Store/retrieval | `app/.../agent/memory/AgentMemoryStore.kt`, `AgentMemoryRetriever.kt` | CRUD, chunking, lexical search, source construction, limits. |
| Tool | `app/.../agent/tools/MemorySearchTool.kt` | `memory.search` executor; uses `AgentMemoryStore`; never reads another space. |
| Normalizer/grant | `app/.../agent/security/AgentMemoryToolArgumentNormalizer.kt`, `AgentMemoryReadGrant.kt` | Resolve selected spaces into a frozen scope; grant coverage check. |
| Admin/UI | `app/.../presentation/AgentMemoryAdminService.kt`; `feature/.../ui/AgentMemoryScreen.kt` | SAF text import, space/item CRUD, search preview; no Android types in feature. |
| Composition | `AgentRuntimeAssembly`/`MainRuntimeAssembly` | Add memory tool catalog only for selected local-only runs; share one store. |

### 7.2 Task selection and grant

Add `memorySpaceIds: Set<String> = emptySet()` to:

- `CreateAgentTask` (optional, default empty);
- `AgentRunPolicy` (frozen at task creation, persisted in policy JSON; old rows decode to empty);
- `AgentTaskRepository` validation: every ID exists, belongs to the local user, and is not tombstoned.

A task with no selected spaces has no memory tools. The UI must show the selection before creation and in task detail.

`AgentMemoryReadGrant`:

```kotlin
data class AgentMemoryReadGrant(
    val spaceIds: List<String>, // sorted unique, from run policy
    val revoked: Boolean = false
) {
    fun covers(scope: AgentResolvedToolScope.MemorySpaces): Boolean =
        !revoked && scope.spaceIds.all { it in spaceIds }
}
```

`AgentPermissionBroker.decide` may auto-allow a read-only `memory.search` only when this grant covers the frozen scope and the run policy is local-only. It must not auto-allow:

- writes,
- a query outside selected spaces,
- a query after the space is deleted/revoked,
- a network-enabled run in P1 (see 7.4).

### 7.3 Retrieval

P1 uses deterministic offline retrieval:

- Reuse the tokenization/ranking behavior of `KnowledgeLexicalRetriever` (CJK bigrams, Latin terms, deterministic order). Either delegate from the existing class or promote the pure ranker into a shared runtime helper; behavior-equivalence tests are mandatory.
- Page chunks by `(spaceId, memoryId, position)` with a bounded page size; keep top candidates bounded (for example 64), then select at most 4–6 chunks/1,024 estimated tokens.
- Return a bounded JSON result containing `sources[]` with `spaceId`, `memoryId`, `chunkId`, `contentHash`, excerpt, score and `estimatedTokens`. The model never receives an unbounded dump.
- Deduplicate by memory ID; preserve source order; mark `truncated` when the budget cut candidates.
- No regex, no remote embedding, no HNSW/vector index in P1. A local embedding worker is P2 and must be optional, rebuildable and dimension-isolated.

### 7.4 Tool: `memory.search`

Descriptor:

- `toolKey = "memory.search"` (add a built-in/source rule; package keys remain `pkg.*`);
- `sideEffect = READ_ONLY`; `dataClass = PRIVATE`; timeout 10s;
- schema: `query` (1–256 chars, required), `spaceId` (optional, must be selected), `maxItems` (1–10);
- no `write`, `delete`, `link` or `remember` tool in P1.

Execution:

- check that the frozen scope is `MemorySpaces` and every space is still live;
- reject a `spaceId` outside the grant;
- call `AgentMemoryStore.search(scope.spaceIds, query, limits)`;
- return `SUCCEEDED` with an empty bounded `sources` list when there are no matches (not a fake failure);
- record only IDs/hashes/counts in logs and events; never memory content.

Catalog rule: add the memory tool only when `run.policy.memorySpaceIds` is non-empty **and** `run.policy.networkPolicy == LOCAL_ONLY`. In P1, a network-enabled run does not expose memory search. This is deliberate until egress source tracking is proven; do not leave memory data in a cloud prompt because `memory.search` returned it.

### 7.5 UI surfaces

- `AgentWorkspaceSurface.MEMORY` + `ui/AgentMemoryScreen.kt`: spaces list, create/rename/delete, item list, single-document text/Markdown import, search preview, source/hash detail. No automatic chat ingestion.
- Drawer gets a **记忆空间** row (not nested under “插件”); the plugin group remains 工具包/Skills/MCP.
- `AgentMemoryPickerSheet` from the home plus sheet or composer context row: select spaces for the next task; selected chips above composer; disabled/empty-state explanation.
- If `networkEnabled` is on and spaces are selected, the composer shows “记忆检索仅支持本地模式” and blocks start until the user switches local-only or clears the selection; the tool is also absent by catalog rule, so UI and contract agree.
- Task detail: memory search activity uses the compact timeline row; expanded detail shows source space/memory/chunk IDs and hashes; no raw memory content outside the tool result summary.
- Approval sheet: memory writes (P2 only) show exact space/item/diff; P1 has no memory write tool.
- Existing `McaTheme`/design system only; one LazyColumn; 48dp targets; no new visual language.

### 7.6 Tests and rollback

- Room 28→29 migration with old chat/assistant/knowledge rows intact; export `29.json`; `MigrationTestHelper` + schema contract tests.
- Space CRUD and tombstone; item/chunk transaction; content hash; bounded content/size.
- Retrieval determinism, CJK terms, no cross-space result, empty result, token/result caps, duplicate removal.
- `memory.search` frozen scope: selected space accepted; unselected/foreign/deleted/over-limit rejected; no invocation row -> no execution.
- Grant revocation/policy change invalidates pending approvals; network-enabled run has no memory tool.
- UI tests for picker, empty state, citation rows, delete confirmation, large text/TalkBack.
- Rollback: disable the memory tool source and keep tables/data; do not drop tables or rewrite chat memory. Room migration is forward-only; code rollback must not downgrade DB.

---

## 8. P2 — workflow/automation

### 8.1 Shape: a task recipe, not a DAG executor

A workflow definition is versioned data:

```text
AgentWorkflowDefinition v1
  name, description
  goalTemplate (bounded; no credentials)
  modelBinding snapshot (explicit earlier choice; missing asset = preflight failure, never silent switch)
  policy: LOCAL_ONLY + explicit enabledToolKeys + memorySpaceIds + skillIds
  requirements: required package IDs/versions (preflight only; never a grant)
  budget
  trigger: MANUAL | DAILY | INTERVAL (P2)
  unattendedPolicy: READ_ONLY_ONLY (required for scheduled triggers)
```

There is no `TriggerNode`/`ExecuteNode`/`ConditionNode`/`ExtractNode` in P2. If MCA later needs deterministic extraction, that is a separate ADR and cannot become a second tool executor hidden inside a workflow.

### 8.2 Manual workflows first

P2.1 delivers:

- create/edit a task recipe from the Agent workspace;
- “Run now” creates a task and starts one run through `AgentWorkspaceCommands.createTask` + `startRun`;
- the task detail shows `自动化 · <name> · 手动` and links back to the definition;
- normal approvals, stop, budgets and artifacts apply unchanged;
- no schedule, no background launch.

This slice can ship even if the owner-lift ADR in 8.3 is not approved.

### 8.3 Scheduling gate: owner lift ADR

Today the single `AgentRuntimeAssembly` is constructed in `MainViewModel` with `viewModelScope` (`MainViewModel.kt:101-107`). A WorkManager worker cannot construct a second runtime, and a new process has no view-model scope. Therefore scheduled launch requires an explicit architecture ADR and slice:

- move only the Agent subtree into a process-scope holder (one `AgentRuntimeAssembly`, one owner, one processor) used by both `MainViewModel` and the workflow worker;
- keep chat/image/tuning runtime ownership unchanged;
- define creation/close, application background/foreground, process death, FGS lease and notification behavior;
- add a custom `WorkerFactory` in `McaApplication`'s existing `Configuration.Provider` (no global service locator, no worker-created engine);
- prove two callers share the same owner and that page exit does not cancel a scheduled run accidentally.

If this ADR is rejected, scheduled workflows are **not implemented** as unattended runs. The fallback is a notification-only reminder that requires a user tap; it must not be labeled “automation completed”. Do not fake the gate.

### 8.4 Schedule adapter (only after 8.3)

- WorkManager unique work per workflow: `agent-workflow-<workflowId>`; periodic minimum 15 minutes or a one-time request for the next daily slot.
- No exported receiver, no `BOOT_COMPLETED` receiver, no Tasker/intent/speech/app-open trigger, no external HTTP trigger.
- A missed slot runs at most once on the next wake; do not replay a storm of missed slots.
- Worker input contains only workflow ID/definition version/slot key. It re-reads the definition and preflights:
  - workflow enabled and version matches;
  - no active task/run for this workflow;
  - model asset/protocol still available;
  - memory spaces still exist and policy is local-only;
  - all effective tools are `READ_ONLY`; no write/network tool;
  - required packages/Skills are enabled and versions match; otherwise fail closed with `WORKFLOW_STALE` and do not create a task.
- After preflight, create task with a deterministic idempotency key `wf:<workflowId>:<version>:<slot>`; start run once. If the process dies before/after task creation, a unique `agent_workflow_runs.idempotencyKey` and existing task/run idempotency prevent duplicates.
- Once a task is created, WorkManager never retries the launch. Runtime failures are the run's problem, not a workflow replay.
- No approvals are auto-resolved. A scheduled run that would need approval is rejected at preflight; it never queues an invisible pending approval.

### 8.5 Data model (Room 30)

| Table | Key fields | Purpose |
| --- | --- | --- |
| `agent_workflows` | `workflowId` PK, `name`, `definitionVersion`, `definitionJson`, `definitionHash`, `enabled`, `triggerKind`, `scheduleSpec`, `nextRunAt`, `revision`, timestamps | Durable recipe and schedule. |
| `agent_workflow_runs` | `workflowRunId` PK, `workflowId`, `definitionVersion`, `triggerKind`, `scheduledFor`, `idempotencyKey` UNIQUE, `launchState`, `taskId`, `errorCode`, timestamps | One launch attempt per slot; links to the existing task. This is not a second run state machine. |

`agent_workflow_runs` does not duplicate `AgentRun` terminal states. It records only launch/preflight outcomes; execution state remains `agent_runs`/`agent_tasks`.

#### Exact integration points

| Seam | Proposed location | Responsibility |
| --- | --- | --- |
| Types | `core/agent-contract/.../AgentWorkflowTypes.kt` | Versioned definition, trigger/schedule, launch outcome; no executor/script fields. |
| Store/migration | `app/.../agent/workflow/AgentWorkflowStore.kt`, `AgentWorkflowEntities.kt`; `AgentDatabaseMigration.kt` (29→30) | Definitions, versions, launch records, idempotency key; additive tables. |
| Launcher | `app/.../agent/workflow/AgentWorkflowLauncher.kt` | Preflight + `AgentWorkspaceCommands.createTask/startRun`; no tool executor, no model adapter. |
| Scheduler/worker | `app/.../agent/workflow/AgentWorkflowScheduler.kt`, `AgentWorkflowWorker.kt` | Unique WorkManager work; reads definition/slot; calls launcher; no exported receiver. |
| Worker factory | `McaApplication.kt` `workManagerConfiguration`; new `AgentWorkerFactory` | Inject the one process-scope Agent runtime after P2.0; never construct a runtime in the worker. |
| Admin/UI | `app/.../presentation/AgentWorkflowAdminService.kt`; `feature/.../ui/AgentWorkflowsScreen.kt`, `AgentWorkflowEditorSheet.kt` | List/editor/manual run/schedule state; contract-only. |
| Detail origin | `AgentWorkspaceProjection` / `AgentTaskDetailUiState` | Read-only workflow origin chip; no second execution path. |

### 8.6 UI surfaces

- Drawer “自动化任务” expands to **自动化 / 归档 / 导入任务包** (existing archived/import entries preserved).
- `AgentWorkspaceSurface.WORKFLOWS` + `ui/AgentWorkflowsScreen.kt`: list, enabled switch, next run, last run/result, manual “运行”.
- `ui/AgentWorkflowEditorSheet.kt`: name, goal template, model, local-only badge, memory spaces, tool scope, schedule (daily/interval). No node canvas, no JS field, no arbitrary tool chain.
- Task detail origin chip: `自动化 · <name> · 手动/定时`; scheduled runs show scheduled slot, not “live”.
- If owner-lift ADR is not approved, schedule controls are disabled with an explicit “需要后台运行能力” explanation; a reminder-only mode is separate and clearly labeled.

### 8.7 Tests and rollback

- Definition validation/versioning/hash; editing creates a new version; old runs keep old version.
- Manual run uses the same owner and normal approvals; no second executor/loop.
- Scheduled preflight rejects write/network tools, stale model, missing memory/package, disabled workflow, active duplicate.
- Idempotency: repeated worker calls for the same slot create at most one task/run; process death before/after task creation does not duplicate; no replay of side effects.
- Approval expiry/process restart: no scheduled run auto-approves or reuses a grant.
- WorkManager test: unique work, interval/daily boundary, missed slot coalescing, no exported receiver.
- UI tests: disabled schedule state, manual run, task origin chip, delete/disable confirmation.
- Rollback: disable scheduler registration/worker; keep definitions and launch history read-only; do not delete tasks or downgrade DB.

### 8.8 Optional P2 extensions (not required for M5)

Only after M4 and the relevant egress tests:

- **Memory write:** `memory.remember` with `ALLOW_ONCE`, content bound to the exact proposed diff, source task/run, and a user-visible “保存到记忆” action. No automatic extraction from every run.
- **Memory links/import:** links between items and copy-on-write import from chat knowledge/character memory with preview. Still no cross-domain live query.
- **Local embedding:** optional local model with a fingerprint, rebuildable index, dimension isolation and offline fallback; remote embedding is not a default and requires explicit egress/source approval.
- **Package resources:** `package.read_text` with a package-resource scope; read-only, path-normalized, bounded, and available only for enabled packages. No executable resource.

Each item is a separate ADR/slice; none may weaken P1’s local-only memory rule or P0’s approval invariants.

---

## 9. UI surface map

All UI stays inside `feature:agent-workspace` (or `core:designsystem` for genuinely shared primitives). No feature→feature dependency and no new business writer.

| Surface | Entry | New state/port | Behavior |
| --- | --- | --- | --- |
| Packages | Drawer → 插件 → 工具包 | `AgentWorkspaceSurface.PACKAGES`, `AgentPackageAdminPort` | List/import preview/enable/disable/uninstall; source/tool/effect summary. |
| Memory | Drawer → 记忆空间 (top-level, after 插件); home plus “记忆” picker | `AgentWorkspaceSurface.MEMORY`, `AgentMemoryAdminPort`, `AgentMemoryPicker` | Space/item CRUD, import text, search preview, task selection chips; network-enabled task cannot use memory in P1. |
| Workflows | Drawer → 自动化任务 → 自动化 | `AgentWorkspaceSurface.WORKFLOWS`, `AgentWorkflowAdminPort` | List/editor/manual run; schedule only after owner-lift ADR. |
| Approval sheet | existing inline/sheet | `AgentApprovalUi.sourceLabel` (new field, parsed from summary `source`) | Shows built-in/package/MCP source, action, read/write/network targets, args, overwrite, expiry, hashes. No “always allow”. |
| Task timeline | detail | existing `AgentTimelineProjection` | Tool proposed/requested/started/result rows; memory/package/workflow source in compact details. |
| Detail origin | top status/status row | workflow origin projection | `自动化 · name · manual/scheduled`; no new detail chrome. |
| Skills/MCP | existing | unchanged | Package screens follow the same list/empty/error/48dp patterns; no restyle. |

Accessibility/visual contract:

- existing `McaTheme`, typography, spacing, icon family and motion only;
- 48dp minimum touch targets, explicit content descriptions, TalkBack order; approval actions default focus not on “allow”;
- large text 1.0/1.5/2.0; long package/memory/workflow names ellipsize without hiding actions;
- no new brand colors, cards-within-cards, glass effects or decorative animation;
- two-round real rendering is required for implementation; this proposal does not claim it.

---

## 10. Test and evidence plan

No test below has been run for this proposal. All are planned.

### 10.1 P0

| Test focus | Proposed test location | Assertion |
| --- | --- | --- |
| Invocation persistence | `app/src/test/.../agent/composition/AgentToolInvocationLedgerTest.kt` | step+invocation written before approval; idempotent; unique running step. |
| Approval/authorize | `app/src/test/.../agent/security/AgentApprovalPermissionBrokerTest.kt`; `AgentApprovalConcurrencyTest.kt` | PENDING->CONSUMED exactly once; replay/parallel 100 rounds -> one execution. |
| Resume safety | `app/src/test/.../agent/security/AgentApprovalResumeTest.kt` | no `authorize` -> no executor; changed args/schema/epoch/policy -> deny. |
| Owner/loop integration | `core/agent-runtime/src/test/.../run/AgentRunOwnerTest.kt`; `AgentPromptedLoopCoordinatorTest.kt` | only authorized command executes; result paired; no blind approvedCommand path. |
| Lifecycle events | `feature/agent-workspace/src/test/.../AgentTimelineProjectionTest.kt`; `AgentApprovalProjectionTest.kt` | proposed/requested/started/result rows; approval source/targets visible. |
| Package negative corpus | `app/src/test/.../agent/extensions/packages/AgentToolPackageSecurityTest.kt` | script/wasm/dex/entry/hook/path/zip bomb/cycle all reject. |
| DB restart | `AgentStartupRecoveryTest.kt` | pending approval after death is not executable; invocation settles `NOT_EXECUTED`. |

### 10.2 P1 packages

- `AgentToolPackageManifestTest` (valid/invalid matrix, derived schema, preset/expose).
- `AgentToolPackageArchiveTest` (ZIP/tree limits, symlink, duplicate, compression).
- `AgentPackageToolCatalogTest` (source `PACKAGE`, conflicts, immutable snapshot, conditional enable).
- `AgentPackageBoundToolTest` (same broker/invocation rows; preset merge; no override; timeout clamp; result mapping).
- `AgentPackageLifecycleTest` (update/disable/uninstall, snapshot retention, approval invalidation).
- `AgentPackageAdminScreenTest` (Compose list/preview/enable states, 48dp, content descriptions).
- Security: package cannot reference MCP/HTTP/shell/UI/hook/executor; model cannot enable package.

### 10.3 P1 memory

- `AgentDatabaseMigrationTest` 28→29; exported `29.json`; `RoomSchemaContractTest`.
- `AgentMemoryStoreTest` (space/item/chunk transaction, hash, tombstone, limits).
- `AgentMemoryRetrievalTest` (CJK/English, determinism, cross-space leakage, empty, truncation).
- `AgentFrozenCommandFactoryTest` memory scope; `AgentPermissionBrokerTest` selected/unselected/deleted/revoked.
- `AgentMemorySearchToolTest` (bounded result, no content in logs, no invocation row -> no execution).
- `AgentMemoryScreenTest` (picker, citations, delete, large text, TalkBack semantics).
- Policy test: network-enabled run exposes no memory tool in P1.

### 10.4 P2 workflows

- `AgentWorkflowDefinitionTest` (version/hash/limits/no node fields/no JS).
- `AgentWorkflowLauncherTest` (same owner; idempotent create/start; no second executor).
- `AgentWorkflowUnattendedPolicyTest` (write/network rejected; stale model/memory/package rejected).
- `AgentWorkflowSchedulerTest` (unique work, daily/interval, missed slot, no exported trigger).
- `AgentWorkflowIdempotencyTest` (100 repeated slots -> one task/run; crash points).
- Room 29→30 migration; launch history not a second run state.
- Compose tests for list/editor/manual/disabled schedule/origin chip.
- Device/real-run tests: local model, local-only read-only scheduled workflow, process restart, FGS/notification, no duplicate side effects. These remain `NOT_RUN` until hardware/model evidence exists.

### 10.5 Commands (planning only; not executed)

```powershell
# Affected module tests after implementation; adapt filters to real names.
.\scripts\agent-verify.ps1 -Tasks ':core:agent-contract:testDebugUnitTest',':core:agent-runtime:testDebugUnitTest'
.\scripts\agent-verify.ps1 -Tasks ':app:testDebugUnitTest' -Tests 'com.muyuchat.mca.agent.*'
.\scripts\agent-verify.ps1 -Tasks ':feature:agent-workspace:testDebugUnitTest'
python tools\architecture\check_architecture_rules.py
```

For a documentation-only task like this one, the required evidence is link/rule consistency, diff review and a sensitive-data check; no build is run. The commands above are for the implementation slices, not a claim about this proposal.

---

## 11. Risks, stop conditions and open decisions

| Risk | Impact | Mitigation / stop condition |
| --- | --- | --- |
| Approval pipeline remains half-wired | Package/memory/workflow features would multiply a bypass or fail-closed surface | P0 gate; no package tool in production catalog until tests pass. |
| Scope generalization regresses built-ins | Wrong path/permission behavior | P1.0 must prove byte-identical normalized args/hashes for the four existing tools; golden tests. |
| Package “preset” is too limited | Low user value or pressure to add JS | Accept curated tool surfaces + resources; JS/WASM is not in this plan and requires a separate isolated-runtime ADR. |
| Package update races an active run | Old approval executes new binding | Descriptor version includes content hash; disable/update invalidates pending approvals; run catalog immutable. |
| Memory lexical quality is low | Poor recall; pressure for remote embeddings | P1 is deterministic/offline; add optional local embedding in P2 with rebuild/dimension tests. |
| Memory leakage across spaces/tasks | Privacy incident | Store enforces sorted selected IDs; broker grant; negative cross-space tests; no “search all”. |
| Cloud egress receives memory/package content | Data loss | P1 exposes memory tools only on LOCAL_ONLY runs; P2 requires source-level egress provenance and S10 tests. |
| Workflow becomes a second executor | Duplicate owner/approval bypass | P2 forbids node executors; every launch goes through `AgentWorkspaceCommands`/owner. |
| WorkManager cannot reach the view-model-owned runtime | Scheduled runs fail or duplicate runtime | P2.0 owner-lift ADR + custom `WorkerFactory`; otherwise schedule is disabled or reminder-only. |
| Migration/data loss | User history loss | Additive 28→29→30; no destructive migration; export schema; migration tests; code rollback must not downgrade DB. |
| Handoff/source drift | Implementing against stale claims | Re-read source at each slice; the handoff is background evidence, current code wins. |

Open decisions requiring product/architecture sign-off before implementation:

1. Package enable cap (8 packages / 32 tools proposed) and whether resources are P1-visible only.
2. Whether P1 package tools are limited to read-only, with write bindings delayed until after memory; this plan puts write at P1 late.
3. Memory space limit and whether `LOCAL_ONLY` is the only egress class in P1 (recommended yes).
4. P2 owner-lift scope/ADR; without it, no unattended scheduling.
5. Workflow manual-only vs schedule; schedule is local-only/read-only in this plan.
6. Whether any package/memory UI needs a new navigation tab or stays in the drawer (recommended drawer; no new top-level chrome).

Stop immediately if:

- a package can reach an executor, network, hook, UI or another package without the normal broker chain;
- a workflow node can call a tool directly or create a second loop;
- a memory query can return a space not selected for the task;
- a scheduled workflow can perform a write/network action or auto-use an approval;
- a migration is destructive or a schema JSON is hand-edited;
- a UI surface labels an unverified path as completed/automated.

---

## 12. Milestones and P0/P1/P2 order

| Milestone | Phase | Deliverable | Exit criteria | Rollback |
| --- | --- | --- | --- | --- |
| **M1** | P0 | Approval/invocation closure + tool lifecycle events | Approve/deny/replay/restart tests; invocation row before approval; no direct `approvedCommand` execution; timeline/approval show real source/targets. | Revert the wiring; current fail-closed behavior remains. |
| **M2** | P0 | Manifest contract + parser/preview; no executable packages | Negative package corpus rejects; preview only; no package in run catalog; no migration. | Delete package preview surface; keep format tests. |
| **M3** | P1 | Package v1: read-only then write, catalog/UI/revocation | Package tool goes through same broker/invocation; approval binds package version; update/disable invalidates; no JS/WASM; tests. | Disable package source; retain manifests/snapshots. |
| **M4** | P1 | Memory spaces + `memory.search` + UI | Room 29 migration; selected-space grant; local lexical retrieval; cross-space negative tests; network-enabled runs exclude memory tools. | Disable memory tool source; keep tables/data read-only. |
| **M5** | P2 | Workflow/task templates + manual run; scheduling gate | Manual workflow uses same owner/approvals; scheduled preflight is local-only/read-only; idempotency/restart tests; owner-lift ADR approved before any worker launch. | Disable scheduler/worker; keep definitions and history read-only. |

### 12.1 Dependency graph

```text
P0-A approval closure
  ├─ P0-B manifest contract
  │    └─ P1.0 scope generalization
  │         ├─ P1 packages (read-only -> write)
  │         └─ P1 memory (spaces/search)
  │              └─ P2.3 memory v2 / package resources (optional)
  └─ P2.0 owner-lift ADR (only for schedule)
       └─ P2.1 workflow definitions + manual launch
            └─ P2.2 scheduler (local-only/read-only)
```

No P1 package, memory or P2 workflow code may be merged before M1's exit criteria are met. P1.0 starts only after M1; it may overlap P0-B documentation/preview work but must not be mixed into the approval-wiring change.

### 12.2 What not to do to “go faster”

- Do not add a temporary `executeTool` entry for packages or workflows.
- Do not add a persistent `ALLOW`, `autoApprove`, “don’t ask again” or package-level grant.
- Do not let the model enable a package, install a package, edit a workflow or approve a tool.
- Do not copy scripts into a package and call the Skill importer; Skills and tool packages are different formats with different trust.
- Do not port third-party’s node graph, QuickJS, proxy tool, exported broadcast or hook system.
- Do not migrate the database destructively or edit `28.json` by hand.

---

## 13. Handoff evidence for this proposal

- **Task type:** documentation/design only. No behavior change.
- **Files written:** this file only.
- **Baseline:** branch/HEAD and dirty working tree recorded above; existing changes were not touched, reverted or stashed.
- **Read evidence:** hard lock; third-party feature map/tools automation/chat UI mapping; architecture/acceptance/execution pack/handoff; current Agent contract/runtime/data/security/tools, UI surfaces and main composition; memory/knowledge code; WorkManager setup.
- **Not run:** Gradle, `verify-code.ps1`, `agent-verify.ps1`, Room migration tests, Compose tests, device, model, MCP, workflow worker. No runtime or parity claim.
- **Known source drift:** handoff claims run-scoped MCP wiring; current `MainRuntimeAssembly` shows a static provider at read time. Re-read merged source before implementation.
- **Rollback:** delete this file. No code, schema, asset or user data was changed.
