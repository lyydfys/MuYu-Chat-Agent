# MCP Security Review — Agent V1 (run-scoped MCP egress + ASK approvals)

Status: **static source review completed; runtime evidence NOT_RUN** (no Gradle, no build, no device, no
network fixture was run, per instruction). No production code was changed. The only file written by
this review is this report.

## 0. Snapshot and scope

| Item | Value |
| --- | --- |
| Codebase reviewed | `E:\MAC` ("Android Agent V1" tree: `app/src/main/java/com/muyuchat/mca/agent/**`, `feature/agent-workspace/**`, `core/agent-contract/**`, `core/agent-runtime/**`) |
| Branch / HEAD | `codex/enterprise-refactor` / `2368c72cec312f820e2452f2537302dcfc067805` (`2026-10-19T18:23:53+08:00`) |
| Working-tree state | The whole Agent V1 tree is **untracked** in this checkout (`git status --porcelain` reports `?? app/src/main/java/com/muyuchat/mca/agent/`, `?? core/agent-runtime/`, `?? docs/proposals/agent-v1/`, etc.). `MainRuntimeAssembly.kt` is tracked-modified. This review is therefore against the working tree, not a committed revision; line numbers are only valid for the current files. |
| Explicitly read | `RunScopedAgentToolCatalogProvider.kt`, `AgentMcpRegistry.kt`, `AgentMcpServerStore.kt`, `AgentMcpAdminService.kt`, `AgentNetworkEgressPolicy.kt`, `agent/data/egress/**`, the approval broker/port/store, frozen-command factory, tool catalog/assembler, MCP client/transport/adapter, runtime coordinator/owner and the approval UI projection/sheet. |
| Out of scope | MCP server internals, model-provider behavior, Android OS compromise, rooted/backup-restored databases, and the future cloud egress client. Those are named where they change a conclusion. |

**Evidence rule used here:** every claim that a control exists, or that a call site does not exist,
is paired with `path:line` evidence. Negative "no production caller" claims are backed by an
explicit static search over `app`, `core`, and `feature` main sources (Section 5.2). A control is not
counted as present merely because a KDoc says so. Citations mostly use the unique basename of a file
to keep the report readable; the primary reviewed paths and their digests are listed in Section 5.1,
and the basenames are unique under the reviewed tree.

### 0.1 Severity scale used

- **P0** — the security gate is absent, non-functional, or can be bypassed for the feature's primary
  risk (private-data egress / approval integrity). Do not enable MCP beyond a test build until fixed.
- **P1** — a real defense gap or contract violation that needs an additional condition (DNS/TLS,
  admin action, process restart, schema drift) but is part of the intended threat model.
- **P2** — hygiene, future-credential, normalization, evidence, or resource-lifecycle issue.

### 0.2 One-sentence conclusion

The implementation contains good low-level building blocks (origin canonicalization, bounded
transport, generation-fenced registry, fail-closed grant store), but the **human authorization gate
and the run-level egress-consent contract are not actually wired end-to-end**: the ASK path currently
cannot persist an invocation/approval, the approval UI has no request data even if it could, and the
run-scoped MCP grant is minted automatically from a persistent config toggle. Two P0 findings follow.

## 1. Threat model

### 1.1 Assets and trust boundaries

| Asset | Why it matters | Where it lives |
| --- | --- | --- |
| Task inputs (`inputs/**`) | User-selected private content; readable by built-in `workspace.*` tools under the task read grant without ASK | `AgentToolPermissionHook.kt:87-97`, `AgentPermissionTypes.kt:42-65` |
| Workspace artifacts and metadata | Private task content; MCP tool arguments can quote any previously-read content | `McpToolAdapter.kt:131-146` |
| MCP server config / endpoint | Decides where data goes; mutable only through the admin page today | `AgentMcpServerStore.kt:19-30`, `AgentMcpAdminService.kt:34-111` |
| Egress grants | The persisted authorization fact; must be run-scoped and user-authorized | `AgentNetworkEgressPolicy.kt:60-94`, `RoomAgentEgressGrantStore.kt:44-181` |
| Approval + frozen command | One-shot user decision bound to args/schema/run/policy | `AgentApprovalStorePort.kt:7-46`, `AgentPermissionBroker.kt:14-34` |
| Local/private network services | SSRF target if a "remote" MCP origin resolves to loopback/private space | `AgentMcpEndpointPolicy.kt:55-66`, `AgentEgressOrigins.kt:38-56` |
| MCP credentials (future) | No token is stored in V1, but the transport/config types already carry auth seams | `AgentMcpServerStore.kt:11-18,38-45`, `McpProtocolTypes.kt:44-54` |

Trust assumptions: the local user is trusted but **not a security oracle** (the UI must explain what
is being authorized); model output is untrusted for authorization; MCP tool descriptions, schemas,
tool results and server responses are untrusted; the local filesystem task root is private.

### 1.2 Threat table

| ID | Threat | Concrete path in this codebase | Primary asset | Current status |
| --- | --- | --- | --- | --- |
| T1 | Malicious MCP server (tool poisoning, result injection) | Server controls `tools/list` descriptions/schemas and `tools/call` results (`McpClient.kt:177-238,311-348`); descriptions enter the model system prompt (`McpToolAdapter.kt:97-111`); results enter the transcript (`McpToolAdapter.kt:147-161`) | Private data, model behavior | Partial: bounded protocol, all tools ASK-intended; ASK path is broken/blind (F-01) |
| T2 | Confused deputy | Model can read `inputs/**` without ASK (`AgentToolPermissionHook.kt:87-97`), then put that content into an MCP call; the user sees no tool/origin/args in the approval sheet today (`AgentApprovalProjection.kt:54-77,107-109`; `AgentApprovalSheet.kt:82-105`) | Private data | Severe: no informed per-call decision |
| T3 | Origin collision / path confusion | Provider drops the endpoint path before grant/guard (`RunScopedAgentToolCatalogProvider.kt:171-177,190-204`); grant has origin only (`AgentNetworkEgressPolicy.kt:66-94`); a second server on the same origin/path base is connected without a new grant (`RunScopedAgentToolCatalogProvider.kt:106-121`) | Data destination | Partial: origin-level scope, no path/server binding |
| T4 | Redirect to a new origin | `OkHttp` redirects are disabled and 3xx is mapped to `originChanged=true` (`McpHttpTransport.kt:52-60,128-131,242-249`) | Data destination | Controlled; see limit: no revalidation is needed because no follow |
| T5 | Local/private SSRF / DNS rebinding | Literal checks only (`AgentMcpEndpointPolicy.kt:55-66`; `AgentEgressOrigins.kt:38-56`); no resolved-IP check or DNS pin (`McpHttpTransport.kt:242-249`); remote `https://[::ffff:127.0.0.1]` is not classified local | Local services | Partial/latent: TLS makes plain loopback exploitation conditional, but the contract is not met |
| T6 | Grant replay | Run/task/policy version checks (`RoomAgentEgressGrantStore.kt:62-80,122-157`); grant has no expiry (`RunScopedAgentToolCatalogProvider.kt:107-116`); no production revoke call (`AgentEgressGrantStore.kt:22-36`); server delete/re-add same origin can reuse the old grant | Data destination | Partial: runId filter works; no revocation/expiry; same-origin replay |
| T7 | Stale catalog / schema replacement | `McpClient` records `listChanged` but ignores notifications (`McpClient.kt:91,269`); registry listener defaults to NONE (`AgentMcpRegistry.kt:147`) and provider never installs one (`RunScopedAgentToolCatalogProvider.kt:95-100`); schema hash is not persisted (`AgentMcpServerStore.kt:19-30,47-54`); executor checks `toolKey`/JSON parseability but not the schema hash (`McpToolAdapter.kt:135-146`) | Approval integrity | Weak: no drift detection/invalidation |
| T8 | Token leakage | V1 stores no token and rejects query/userinfo (`AgentMcpServerStore.kt:11-18,38-45`; `AgentMcpEndpointPolicy.kt:36-40`); future auth seam exists (`McpProtocolTypes.kt:44-54`); `McpHttpPost` default `toString()` includes `bearerToken`/`bodyJson` (`McpHttpTransport.kt:19-27`) | Credentials (future) | Good for V1 persistence; future logging hazard (F-10) |
| T9 | Pending approval executed after context change | Journal consume checks status/expiry/run binding (`AgentTransitionApplier.kt:344-389,703-720`); no MCP drift invalidation; startup recovery expires pending approvals (`AgentStartupRecovery.kt:150-206`); no invocation row exists to make the path work (F-01) | Private data | Partial: journal CAS is good; MCP-specific invalidation absent; path blocked by F-01 |
| T10 | Shared OkHttp client / cross-run lifecycle | One process-wide client (`AgentMcpRegistry.kt:13-24,137-139`); `McpClient.disconnect` never calls `transport.close` (`McpClient.kt:162-175`); `OkHttpMcpTransport.close` is a no-op unless `closeClient=true` (`McpHttpTransport.kt:61-64,96-98`); no pool eviction | Availability/isolated revocation | P2 gap: no per-run isolation/close, but no cross-origin auth bypass found |

### 1.3 Threat-model gaps

- There is no explicit "user sees origin + tool + data class + args before MCP egress" path in the
  implementation; architecture §11.3/§12.2 requires it (`MCA_AGENT_V1_ARCHITECTURE.md:732-740,782-804`).
- There is no explicit state for "MCP egress authorization required" returned to the UI. The provider
  either mints a grant silently or omits the server (`RunScopedAgentToolCatalogProvider.kt:106-125`).
- The model's ability to read task inputs without ASK is a deliberate design choice for local tools,
  but it makes the MCP ASK the only exfiltration barrier. That barrier is currently not functional
  (F-01), and even after a minimal fix it is blind (F-01c).

## 2. Exact controls present, and where each stops

### C1. Run-scoped egress grant object and store

**Present.** `AgentEgressGrant` carries `runId`, `taskId`, `transport`, canonical `origin`,
`allowedDataClasses`, `allowedSourceIds`, `policyVersion`, `revoked`, `expiresAtMillis`
(`AgentNetworkEgressPolicy.kt:60-94`). The Room store:
- rejects already-revoked grants (`RoomAgentEgressGrantStore.kt:51-60`);
- resolves the run/task policy context and requires the exact `policyVersion`
  (`RoomAgentEgressGrantStore.kt:122-133`; `AgentEgressGrantContext.kt:24-43`);
- re-canonicalizes the origin and re-checks local/remote transport class
  (`RoomAgentEgressGrantStore.kt:134-157`);
- rejects expired grants (`RoomAgentEgressGrantStore.kt:158-161`);
- lists only active, non-expired egress rows for the task (`AgentSupportDaos.kt:238-248`).

**Limits.**
- The provider constructs `AgentEgressGrant` directly instead of going through
  `AgentNetworkEgressPolicy.createGrant(...)`; only `canonicalOrigin` is reused
  (`RunScopedAgentToolCatalogProvider.kt:107-116,192`; `AgentNetworkEgressPolicy.kt:275-311` has no
  production caller). The store re-validates, so this is not a bypass today, but the policy object's
  user-authorization contract is bypassed.
- The provider mints grants with `allowedDataClasses = {PRIVATE}`, `allowedSourceIds = emptySet()`,
  and **no** `expiresAtMillis` (`RunScopedAgentToolCatalogProvider.kt:107-116`). There is no source
  coverage for MCP arguments and no expiry.
- No run-end revoke: `release` removes the run's registry and closes it, nothing else
  (`RunScopedAgentToolCatalogProvider.kt:74-77`). The store port has `revoke(grantId)` and
  `revokeTaskScope(taskId)` but no `revokeForRun`
  (`AgentEgressGrantStore.kt:22-36`); static search found no production `revoke(`/`revokeTaskScope`
  call (Section 5.2).

### C2. Canonical origin and transport-class checks

**Present.** `AgentEgressOrigins.canonical` accepts only `https` (or `http` when the caller allows
cleartext for `LOCAL_SERVER`), rejects userinfo/query/fragment and any path other than empty/`/`,
normalizes the default port away, and returns `scheme://host[:port]`
(`AgentEgressOrigins.kt:10-30`). `AgentMcpEndpointPolicy.check` rejects embedded credentials, query,
fragment and non-HTTP(S), and only allows cleartext loopback when `allowLocal=true`
(`AgentMcpEndpointPolicy.kt:24-53`). `RunScopedAgentToolCatalogProvider.canonicalOrigin` drops the
MCP transport path first, then re-checks that `LOCAL_SERVER` is local/private and `REMOTE_MCP` is not
(`RunScopedAgentToolCatalogProvider.kt:171-204`). The policy's `checkTransport` applies the same
class rules to grants (`AgentNetworkEgressPolicy.kt:186-237`).

**Limits.**
- The path is deliberately dropped; the grant is origin-only, so two MCP services on
  `https://host/a` and `https://host/b` share one grant (`RunScopedAgentToolCatalogProvider.kt:106-121`).
- Only **literal** address forms are classified. `AgentEgressOrigins.isLocalOrPrivate` covers
  localhost/`::1`, `10/8`, `127/8`, `192.168/16`, `172.16/12`, `169.254/16`, and the `fc`/`fd`/`fe80`
  IPv6 prefixes (`AgentEgressOrigins.kt:38-56`). It does not classify `::ffff:127.0.0.1`,
  `0.0.0.0`, multicast (`224/4`), or a hostname that resolves to a private address.
- `AgentMcpEndpointPolicy` is weaker than the egress policy: no IPv6 ULA/link-local classification at
  all (`AgentMcpEndpointPolicy.kt:55-66`). The provider's later check catches ULA for the provider
  path, but the store/UI and the public registry `connect` path can disagree.
- `AgentEgressOrigins.canonical` does not trim a trailing dot, while `AgentMcpEndpointPolicy` does
  (`AgentEgressOrigins.kt:23`; `AgentMcpEndpointPolicy.kt:75-76`). Explicit `:443` is normalized away
  by the egress origin but retained by the endpoint policy (`AgentEgressOrigins.kt:25-29`;
  `AgentMcpEndpointPolicy.kt:51-52`). This makes some valid configs silently unconnectable, not
  exploitable, but it is an origin-normalization ambiguity (F-12).

### C3. Connect-time egress guard

**Present.** `AgentMcpRegistry.connectRecord` runs the endpoint policy and then the injected
`AgentNetworkEgressGuard` **before** `clientFactory.create`/`client.connect`
(`AgentMcpRegistry.kt:446-462`). The provider's guard allows only an origin already in the run's
`allowedOrigins` set (`RunScopedAgentToolCatalogProvider.kt:155-166`). The guard request carries
`serverId`, canonical `origin`, and `localServer` (`AgentMcpEgressGuard.kt:10-14`).

**Limits.**
- The guard is connect-only. After `connect`, `McpToolExecutor.execute` calls
  `client.callTool(remoteName, argumentsJson)` with no egress guard, no payload/source check, and no
  origin re-check (`McpToolAdapter.kt:131-146`).
- The provider's guard compares only `request.origin`; it ignores `request.serverId` and
  `request.localServer`, and it does not consult `policyVersion`, `revoked`, `allowedDataClasses`, or
  `allowedSourceIds` (`RunScopedAgentToolCatalogProvider.kt:155-166`).
- The guard interface is too coarse for the contract in architecture §11.3/§12.2/S10: it has no
  runId, tool key, data class, payload hash, or source list (`AgentMcpEgressGuard.kt:10-14`;
  `MCA_AGENT_V1_ACCEPTANCE_MATRIX.md:143`).

### C4. ASK approvals for MCP tools

**Present (intended).** Every MCP tool is registered as
`EXTERNAL_SIDE_EFFECT`/`PRIVATE` (`McpToolAdapter.kt:97-111`); server hints are kept as display
hints only and never lower the side effect (`McpToolAdapter.kt:58-64`). The permission broker's
`decide` path auto-allows only `workspace.*` read tools and otherwise inserts a pending approval
(`AgentPermissionBroker.kt:46-57`). Approvals have a 5-minute TTL
(`core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/AgentContract.kt:29`), are bound
to invocation/args/schema/resource/run epoch/policy version
(`AgentApprovalStorePort.kt:7-46`), and the journal's `consumeApproval` re-checks that binding,
status and expiry (`AgentTransitionApplier.kt:344-389,703-720`). Startup recovery expires pending
approvals and pauses (`AgentStartupRecovery.kt:150-206`). Skill revocation and run supersede
invalidate pending approvals (`MainRuntimeAssembly.kt:239-250`; `AgentTransitionApplier.kt:154-162,620-628`).

**Limits (this is where F-01 lives).**
- There is no writer of the `agent_invocations` row in the tool-call/approval path that
  `insertPending` requires; static search found only `AgentStartupRecovery` constructing
  `UpsertInvocationTransition` (`AgentStartupRecovery.kt:177-195`) and no coordinator/owner
  construction. The coordinator only allocates an `invocationId`
  (`AgentPromptedLoopCoordinator.kt:468`) and freezes
  (`AgentPromptedLoopCoordinator.kt:510-531`) without persisting that invocation.
- `RoomAgentApprovalStore.insertPending` rejects when the invocation row is missing
  (`RoomAgentApprovalStore.kt:52-55`). The broker turns that into `Denied`
  (`AgentPermissionBroker.kt:95-96`), and the hook returns `Denied` (`AgentToolPermissionHook.kt:69-79`).
  By inspection, every MCP tool call ends as a denied tool result instead of an ASK. Runtime
  verification is NOT_RUN in this review, but the static path is deterministic.
- `AgentPermissionBroker.authorize` — the KDoc's mandatory pre-executor CAS
  (`AgentPermissionBroker.kt:23-26,187-242`) — has **no production caller**. The owner resumes with
  the in-memory `approvedCommand` (`AgentOwnerInteractionCommands.kt:183-258`) and the coordinator
  executes it directly (`AgentPromptedLoopCoordinator.kt:127-159,584-620`). Static search found
  `.authorize(` only in tests (Section 5.2). The Room applier does perform a binding check if an
  invocation exists, but it is not the broker's `revalidate`/integrity path and cannot run without
  F-01a.
- The frozen approval has **no MCP origin/network destination**: `AgentFreezeRequest.networkDestinations`
  defaults to empty (`AgentFrozenCommandFactory.kt:53`), the hook does not populate it
  (`AgentToolPermissionHook.kt:37-50`), the MCP descriptor has no origin field
  (`McpToolAdapter.kt:97-111`), `AgentApproval`/`AgentApprovalBinding` have no network field
  (`AgentInvocationTypes.kt:99-125`; `AgentApprovalStorePort.kt:11-45`), and the frozen-command hash
  omits `networkDestinations` (`AgentFrozenCommandFactory.kt:200-216`). Even after approvals work, the
  user cannot see or bind the destination the way architecture §8.3/§12.2 requires.
- No production emitter of `tool.approval_requested`: the constant exists (`AgentEvents.kt:47-51`)
  and the UI projection needs it (`AgentApprovalProjection.kt:54-57,73-77`), but `waitForApproval`
  commits only the approval row and the run state (`AgentOwnerExecution.kt:317-371`). If the
  invocation gap is fixed minimally, the approval sheet still shows `工具调用` with no tool, args,
  read/write targets, or network target (`AgentApprovalProjection.kt:73-77,107-109`;
  `AgentApprovalSheet.kt:82-105,300-328`).

### C5. Generation fence

**Present.** `AgentMcpRegistry` keeps a per-server generation that is bumped on
connect/disconnect/disable/remove/close (`AgentMcpRegistry.kt:736-740` and call sites at
`232,368,394,414,435,467,570,583,699`). A late `initialize`/`tools/list` result is applied only while
its generation is current (`AgentMcpRegistry.kt:520-556`); a stale refresh is dropped and its client
closed (`AgentMcpRegistry.kt:288-295,686-713`); `finish` checks the generation before storing a
status (`AgentMcpRegistry.kt:612-631`). This is a real, well-designed fence.

**Limits.**
- It fences one `AgentMcpRegistry` instance. The provider creates a **new** registry for each
  `catalogFor` resolution (`RunScopedAgentToolCatalogProvider.kt:95-100`), so a schema/tool change
  observed by a previous run/registry cannot fence the new one; `previousWasConnected` is false on a
  fresh registry, so drift is not reported (`AgentMcpRegistry.kt:525-534`).
- It does not fence an already-assembled catalog held by a running execution; the provider caches the
  catalog per run (`RunScopedAgentToolCatalogProvider.kt:67-72,144`) and only releases it at the end
  of an execution (`AgentPromptedLoopCoordinator.kt:82-87`).
- Admin mutations do not reach the registry: `AgentMcpAdminService` only reads/writes the store
  (`AgentMcpAdminService.kt:26-111`) and holds no registry reference. A running execution's cached
  client/catalog is unaffected by a UI disable/remove until the next execution boundary.

### C6. Store hardening

**Present.** `AgentMcpServerStore` is a bounded, versioned JSON file (`schemaVersion=1`, max 16
servers, 256 KiB) (`AgentMcpServerStore.kt:280-285`); a corrupt/oversized/unknown-schema file is
typed `Corrupt` and is never overwritten by a mutation (`AgentMcpServerStore.kt:182-202,203-238`);
writes use temp file + `ATOMIC_MOVE` with a non-atomic fallback (`AgentMcpServerStore.kt:248-275`);
every loaded record is re-validated through `AgentMcpEndpointPolicy` (`AgentMcpServerStore.kt:240-246`);
duplicate IDs are rejected (`AgentMcpServerStore.kt:133-150,234-236`). No token/header/credential is
persisted (`AgentMcpServerStore.kt:11-18,38-45`); `allowAgentNetwork` is strict-boolean decoded and
absent means false (`AgentMcpServerStore.kt:70-99`). The agent root is excluded from backup and
device-transfer (`backup_rules.xml:22-24`; `data_extraction_rules.xml:20-22,51-53`).

**Limits.**
- `enabled` and `allowLocal` use JSON-java's coercing `getBoolean` (`AgentMcpServerStore.kt:76-79`),
  while `allowAgentNetwork` uses the strict path (`AgentMcpServerStore.kt:83-89`). A malformed
  `"allowLocal": "true"` / `"enabled": 1` file can be accepted as `Loaded` instead of `Corrupt`;
  the corrupt fence is weaker for these two flags (P2).
- The lock is an in-process `synchronized` object; multiple store instances/processes are not
  coordinated (`AgentMcpServerStore.kt:127,182-201`). Production uses one instance
  (`MainRuntimeAssembly.kt:254-262`), so this is a future/test concern.
- Endpoint length and scheme checks run only through `AgentMcpEndpointPolicy`; the record constructor
  does not validate `endpoint` (`AgentMcpServerStore.kt:19-36`), so a non-store caller can construct
  a record that bypasses that policy. Production paths go through `add`/`load`.

### C7. Transport hardening

**Present.** `OkHttpMcpTransport.defaultClient` disables redirects and retries, and sets
connect/read/write timeouts (`McpHttpTransport.kt:242-249`). Cleartext is only allowed for an
explicit local server (`McpHttpTransport.kt:220-228`). 3xx is mapped to a typed
`originChanged=true` failure, 401/403 are distinct, and response bodies are bounded
(`McpHttpTransport.kt:120-177,179-189`). SSE messages and total lists are capped
(`McpHttpTransport.kt:154-177`; `McpProtocolTypes.kt:17-25`). Each call registers its own OkHttp
`Call` cancellation (`McpHttpTransport.kt:100-118,230-236`). No `retryOnConnectionFailure` and no
automatic replay of `tools/call` (`McpClient.kt:121-146,240-298`).

**Limits.**
- No `Dns` pinning, no resolved-IP validation, and no `Proxy.NO_PROXY`; the default OkHttp client
  uses the system DNS/proxy (`McpHttpTransport.kt:242-249`). Architecture §11.3 requires validation
  and binding of the actual connection route and says unreviewed proxy/custom-DNS bypasses must not
  be used by Agent (`MCA_AGENT_V1_ARCHITECTURE.md:740-742`).
- `McpHttpPost` is a data class whose generated `toString()` includes `bodyJson` and `bearerToken`
  (`McpHttpTransport.kt:19-27`), and `McpServerConfig`/`McpSession` similarly expose auth/session
  fields (`McpProtocolTypes.kt:60-85`). No logging currently uses them, but future credential
  support makes this a one-line leak.
- `McpClient.disconnect()` clears local state and best-effort DELETEs the session, but never calls
  `transport.close()` (`McpClient.kt:162-175`). Static search found no `transport.close` caller;
  `OkHttpMcpTransport.close` is a no-op with the default `closeClient=false`
  (`McpHttpTransport.kt:61-64,96-98`). The process-wide client's pool/threads therefore live for the
  process, not the run.

### C8. Grant store fail-closed behavior

**Present.** `RoomAgentEgressGrantStore` checks task/run context, exact policy version, canonical
origin, LOCAL_ONLY/transport class, and expiry before upserting
(`RoomAgentEgressGrantStore.kt:122-181`); malformed scope JSON, unknown enum values, invalid ids and
expired rows decode to `null` (`AgentEgressGrantMapper.kt:45-93,113-132`). The active query filters
revoked/expired rows (`AgentSupportDaos.kt:238-248`). Task deletion removes grants
(`RoomAgentTaskDeletionPort.kt:65`). Export excludes grants
(`AgentTaskPackageExporter.kt:32`; `AgentTaskPackageModel.kt:73,118`).

**Limits.** There is no production `revoke`/`revokeTaskScope` call, no `revokeForRun`, and no expiry
set by the provider. A grant survives the end of an execution and stays active; a paused/interrupted
run resumed with the same `runId`, or a same-origin server re-added during that run, can reuse it
until task deletion.

### C9. Startup recovery and process death

**Present.** `AgentStartupRecovery.recoverWaitingApproval` expires a PENDING approval, pauses the run
with `APPROVAL_EXPIRED`, and does not auto-resume (`AgentStartupRecovery.kt:150-206`). This is a good
control for the "pending approval execution after process death" threat.

**Limit.** It relies on `records.findInvocation(approval.invocationId)`; with F-01a there is no
invocation to find, so the recovery branch reports `approval_invocation_missing` rather than a normal
approval recovery (`AgentStartupRecovery.kt:177-178`). This is another symptom of the same missing
invocation row.

### C10. Protocol and result containment

**Present.** Negotiated version is pinned (`McpProtocolTypes.kt:10-14`); initialize/tools/list
pagination and result sizes are bounded (`McpClient.kt:177-238`); JSON-RPC response IDs must match,
multiple responses fail, unsupported server requests are rejected, and notifications are ignored
(`McpClient.kt:240-298`); text results are capped at 16 KiB and `structuredContent` at 256 KiB
(`McpClient.kt:311-348`; `McpToolAdapter.kt:164-182`). Tool schemas are validated against the
supported subset and unsupported tools are disabled individually (`McpToolAdapter.kt:88-95`).
Annotations cannot lower side-effect class (`McpToolAdapter.kt:58-64,101-105`).

**Limits.** Ignoring notifications is also the mechanism that prevents `notifications/tools/list_changed`
from refreshing the catalog (F-06). `McpToolExecutor` returns server-provided text to the model as
low-trust content but with no taint/source marker (`McpToolAdapter.kt:147-161`), which is acceptable
only if the ASK/egress layer works.

## 3. Findings

### 3.1 P0 findings

#### F-01 (P0) — The MCP ASK approval gate is not wired end-to-end, and it cannot bind the destination

**F-01a. No production code creates the `agent_invocations` row on the tool-call path, so
`insertPending` always rejects.**
Evidence chain:
- `AgentPromptedLoopCoordinator` allocates `invocationId` and freezes the command:
  `core/agent-runtime/.../AgentPromptedLoopCoordinator.kt:468` (ID), `:510-531` (freeze request/hook).
  It never commits `UpsertInvocationTransition`.
- The transition handler exists (`app/.../AgentTransitionApplier.kt:280-307`) and the contract type
  exists (`core/agent-contract/.../AgentJournal.kt:77-84`), but the only production construction of
  `UpsertInvocationTransition` found is startup recovery, which runs only after it has found an
  existing invocation (`app/.../AgentStartupRecovery.kt:177-195`).
- `AgentPermissionBroker.decide` reaches `approvalStore.insertPending` for a valid MCP command
  (unless `revalidate` already denied it) (`AgentPermissionBroker.kt:46-57,65`).
- `RoomAgentApprovalStore.insertPending` requires `invocationDao.find(approval.invocationId)` and
  returns `Rejected` when it is null (`RoomAgentApprovalStore.kt:36-58`, especially `:52-55`).
- The broker maps that rejection to `Denied` (`AgentPermissionBroker.kt:95-96`); the hook surfaces
  `Denied` to the coordinator (`AgentToolPermissionHook.kt:69-79`); the coordinator records a DENIED
  tool result and continues (`AgentPromptedLoopCoordinator.kt:532-543`).

**Impact.** By inspection, all MCP tool calls are denied before the user can approve anything. This
is fail-closed, but it means the feature's only human control is unexercised and cannot be claimed as
implemented. The existing provider tests exercise a fake grant store/registry (Section 5.2), not this
Room approval path.

**F-01b. The documented pre-executor one-shot authorization is dead code.**
- The broker KDoc says "Only `AgentExecutionAuthorization.Authorized` may run the executor" and that
  the caller must consume immediately before the executor (`AgentPermissionBroker.kt:23-26,187-242`).
- Production `AgentToolPermissionHook` only calls `broker.decide` (`AgentToolPermissionHook.kt:69`);
  it never calls `authorize`.
- The owner resolves an approval by committing `ConsumeApprovalTransition`, removes the in-memory
  pending command, and relaunches with `approvedCommand = pending.command`
  (`AgentOwnerInteractionCommands.kt:183-258`, especially `:229-256`).
- The coordinator executes `resumeState.approvedCommand` directly without calling the broker
  (`AgentPromptedLoopCoordinator.kt:127-159`, `:584-620`).
- Static search found `.authorize(` only in test sources; no production `approvalStore.consume` call
  either (Section 5.2).

The Room applier's `consumeApproval` independently checks approval↔invocation binding and expiry
(`AgentTransitionApplier.kt:344-389,703-720`), which is a useful partial control. It is not the
broker's path (`AgentFrozenIntegrity.matches`, command expiry, offline/network-policy revalidation
are in `AgentPermissionBroker.kt:244-272`), and it cannot run without F-01a's invocation row. The net
effect is that the documented **broker-layer** pre-executor CAS does not exist in production; the
journal-layer CAS is the only consume, and it cannot run without F-01a's invocation row.

**F-01c. No `tool.approval_requested` is emitted, so the approval UI would be blind even after F-01a
is fixed.**
- The event type is defined (`AgentEvents.kt:47-51`), and the approval projection requires its
  summary to show tool/action/args/targets (`AgentApprovalProjection.kt:10-20,54-57`).
- `AgentOwnerExecution.waitForApproval` commits only `UpsertApprovalTransition` and
  `UpdateRunTransition`; it appends no tool event (`AgentOwnerExecution.kt:317-371`). Static search
  found `TOOL_APPROVAL_REQUESTED` only in the contract, UI projection/previews/tests, and no
  coordinator/owner emitter (Section 5.2).
- With no event, `projectApprovalUi` falls back to `工具调用` for the title, empty read/write/network
  target lists, and no argument summary (`AgentApprovalProjection.kt:73-77,89-110`). If an approval
  row existed, the sheet would show only the generic "运行器未提供结构化目标摘要" line and hash
  strings (`AgentApprovalSheet.kt:82-105,300-328`).
- The frozen `AgentApproval` record itself contains only hashes and binding fields, not the tool key
  or targets (`AgentInvocationTypes.kt:99-125`; `AgentApprovalProjection.kt:13-14`). A last-minute UI
  fix cannot recover the tool/args from the approval row.

**Impact.** The only per-call human gate for private-data egress is a click on "工具调用" with no
indication of which MCP server, tool, arguments, or data class is being sent. A prompt-injected model
can use built-in read tools on `inputs/**` without ASK, then probe for a blind MCP approval. If F-01a
is fixed without F-01c, the control becomes an uninformed rubber stamp.

**F-01d. The MCP origin is not represented in the frozen command or the approval binding.**
- `AgentFreezeRequest.networkDestinations` defaults to empty (`AgentFrozenCommandFactory.kt:53`).
- The hook builds the request without setting it (`AgentToolPermissionHook.kt:37-50`), even though
  `FrozenToolCommand.networkDestinations` exists (`AgentInvocationTypes.kt:9-25`).
- MCP descriptors have no origin field (`McpToolAdapter.kt:97-111`); the origin is known only to the
  registry/client (`AgentMcpRegistry.kt:85,541`; `McpClient.kt:384-388`).
- `AgentApprovalBinding` and `AgentApproval` have no network field
  (`AgentApprovalStorePort.kt:11-45`; `AgentInvocationTypes.kt:99-125`), and
  `AgentFrozenHashing.commandPreimage` omits `networkDestinations`
  (`AgentFrozenCommandFactory.kt:200-216`).
- The approval sheet's "网络目标" card is therefore necessarily empty for MCP
  (`AgentApprovalSheet.kt:84`), even if an approval event is later added from the current command.

**Impact.** The user cannot approve "send this tool call to origin X"; the approval does not bind the
server identity/origin, contrary to architecture §8.3/§12.2 ("server identity、URL origin、tool、数据类别";
schema/version/server-origin change must invalidate old approvals;
`MCA_AGENT_V1_ARCHITECTURE.md:554,565,802-804`).

#### F-02 (P0) — Run-scoped MCP egress is auto-granted from a standing config toggle

**What the code does.**
- The MCP admin page exposes a persistent switch "允许 Agent 联网任务访问该服务器" with the hint
  "仅在该任务网络策略开启并取得出站授权时生效，不是长期 token"
  (`feature/agent-workspace/.../ui/AgentMcpScreen.kt:266-291`).
- The home composer says online mode "需要任务级出站授权"
  (`feature/agent-workspace/.../ui/AgentHomeComposer.kt:134-143`).
- The switch only persists `allowAgentNetwork` in `servers.json`; the store explicitly documents it
  as "not itself a grant" (`AgentMcpServerStore.kt:26-30,160-172`;
  `AgentMcpAdminService.kt:80-97`).
- At the first `catalogFor` of a run whose policy is `NETWORK_ENABLED` and not offline-forced, the
  provider selects enabled servers with `allowAgentNetwork` and, for each origin not already in
  `allowedOrigins`, **constructs and persists** an `AgentEgressGrant` itself:
  `RunScopedAgentToolCatalogProvider.kt:80-89,91-94,106-121`. The grant uses
  `allowedDataClasses = {PRIVATE}`, `allowedSourceIds = emptySet()`, and no `expiresAtMillis`
  (`:107-116`). There is no user interaction between the config toggle and this persistence.
- The store's contract says it persists only after explicit user authorization
  (`AgentEgressGrantStore.kt:14-20`), and the policy says grants/classes/source ids are host facts from
  the UI authorization path (`AgentNetworkEgressPolicy.kt:179-183`). The policy's own
  `createGrant` (which enforces the typed request shape) has no production caller
  (`AgentNetworkEgressPolicy.kt:275-311`; Section 5.2).
- There is no expiry in the grant the provider creates, no run-end revoke, and no production
  `revoke` call (`RunScopedAgentToolCatalogProvider.kt:74-77`; `AgentEgressGrantStore.kt:22-36`;
  Section 5.2). The grant survives the run and is reused by a later resume if the same run id is
  resumed (`RunScopedAgentToolCatalogProvider.kt:91-94,106-108`).

**Why this is P0.** The "run-scoped grant" is run-keyed in the database, but the authorization decision
is a standing boolean set earlier in the admin page. That is exactly the confused-deputy pattern the
policy KDoc claims to prevent: the server config (which may have been added once, or may be a
re-added same-origin server) becomes authorized for every subsequent online run, with `PRIVATE` data
class and no expiry, without a per-run origin/data-class display. The UI's claim that a task-level
egress authorization is required is not true in the code: no such UI/use case exists.

**Related scope issue.** The network mode is a page-level `SavedStateHandle` boolean
(`AgentWorkspaceViewModel.kt:82,391-393,729-739`), not a per-task/per-server choice. Once it is on,
all newly created tasks freeze `NETWORK_ENABLED`; every server with `allowAgentNetwork` is eligible.
This broadens the standing-consent problem beyond a single task.

**Combined path, assuming F-01 is minimally repaired.** A malicious/compromised MCP server is enabled
and marked `allowAgentNetwork`; the user turns on online mode. A task reads `inputs/**` without ASK
(`AgentToolPermissionHook.kt:87-97`). Prompt-injected server descriptions lead the model to call an
MCP tool with the input content. If the approval is repaired only enough to show, the user sees a
generic "工具调用" sheet with no origin/args (F-01c/d) and can click ALLOW_ONCE; the provider has
already minted the `PRIVATE` grant (F-02) and the tool call leaves. This is the main reason F-01 and
F-02 are both P0 rather than "one functional blocker".

### 3.2 P1 findings

#### F-03 (P1) — Address-class and DNS validation are incomplete (local/private SSRF contract gap)

- Architecture §11.3 requires validation of IPv4/IPv6/IPv4-mapped, loopback/private/link-local/
  multicast/unspecified, DNS address binding, and a re-check on every new connection
  (`MCA_AGENT_V1_ARCHITECTURE.md:740-742`).
- `AgentMcpEndpointPolicy.isLocalOrPrivate` checks only literal IPv4 ranges plus `localhost`/`::1`
  (`AgentMcpEndpointPolicy.kt:55-66`); it does not check IPv6 ULA/link-local, `0.0.0.0`, multicast,
  or IPv4-mapped.
- `AgentEgressOrigins.isLocalOrPrivate` adds `::`/`::1` and `fc`/`fd`/`fe80` string prefixes but still
  misses IPv4-mapped (`::ffff:127.0.0.1`), unspecified and multicast (`AgentEgressOrigins.kt:38-56`).
- `McpClient.validateEndpoint` repeats the literal-only check (`McpClient.kt:362-379,390-401`).
- `OkHttpMcpTransport.defaultClient` sets redirect/timeout policy but no `Dns` implementation and no
  `proxy(Proxy.NO_PROXY)` (`McpHttpTransport.kt:242-249`), so OkHttp resolves the hostname at call
  time and may use a system proxy. There is no post-resolution IP check or pin.
- Concrete bypass: `https://[::ffff:127.0.0.1]/mcp` with `allowLocal=false` is not classified local
  by either policy; a hostname that resolves to a private address is likewise accepted as a remote
  origin. Cleartext remote is blocked (`AgentMcpEndpointPolicy.kt:46-50`;
  `McpHttpTransport.kt:220-228`), and TLS hostname/cert verification usually blocks a plain local
  service, which is why this is P1 rather than P0. It is still a contract violation and a latent SSRF
  path if a trusted-cert local endpoint or a permissive network security config exists.

#### F-04 (P1) — No per-call egress/payload check; `checkPayload` is test-only

- `AgentNetworkEgressPolicy.checkPayload` implements the typed source/data-class coverage check
  (`AgentNetworkEgressPolicy.kt:239-273`), but static search found callers only in tests; production
  uses only `canonicalOrigin` (`AgentNetworkEgressPolicy.kt:192`; Section 5.2).
- `McpToolExecutor.execute` passes `command.normalizedArgumentsJson` directly to
  `client.callTool` after checking only the tool key and JSON parseability
  (`McpToolAdapter.kt:131-146`). `McpClient.callTool` then sends the args with no egress guard
  (`McpClient.kt:121-146`).
- The only grant producer in the MCP path emits `PRIVATE`/empty source ids
  (`RunScopedAgentToolCatalogProvider.kt:113-114`), so even if `checkPayload` were called, every source
  would be "missing" and the design has no MCP source projection to approve.
- Acceptance matrix S10 explicitly requires actual `tools/call` arguments to be checked by
  server/tool/origin/source, with unauthorized source/LOCAL_ONLY/SECRET blocked
  (`MCA_AGENT_V1_ACCEPTANCE_MATRIX.md:143`). This is not implemented.
- Impact: once a connection exists, the only remaining barrier is the broken/blind ASK; arbitrary
  content read from `inputs/**` can be placed in MCP arguments without a data-class/source gate. This
  would be P0 if a `LOCAL_ONLY`/`SECRET` source producer were reachable in V1; none was found in this
  review, so it is P1.

#### F-05 (P1) — Disable/remove/consent-off is not a revocation; grants survive and can be replayed

- The admin service writes only the JSON store; it has no registry/grant reference
  (`AgentMcpAdminService.kt:26-111`). `remove` deletes the config row only
  (`AgentMcpServerStore.kt:174-177`); `setAgentNetworkAllowed(false)` only flips the boolean
  (`AgentMcpServerStore.kt:160-172`). There is also no `allowLocal` edit/clear operation: the store
  exposes only `setEnabled`/`setAgentNetworkAllowed`/`remove` (`AgentMcpServerStore.kt:152-177`) and
  the admin port mirrors that (`AgentMcpAdminService.kt:63-111`). A mislabeled local server can only
  be removed and re-added under a new `serverId`, which does not revoke the old grants.
- No production code calls `AgentEgressGrantStore.revoke`/`revokeTaskScope` (Section 5.2), so a
  server removal does not revoke its run grants, contrary to architecture §12.2 ("删除 server 撤销连接、
  token 和 grants"; `MCA_AGENT_V1_ARCHITECTURE.md:802-804`) and the execution pack
  (`MCA_AGENT_V1_EXECUTION_PACK.md:323`).
- A grant has no expiry (`RunScopedAgentToolCatalogProvider.kt:107-116`); after a delete, a same-origin
  server re-added under a new `serverId` can be connected by a resumed run because the provider looks
  up only the canonical origin (`RunScopedAgentToolCatalogProvider.kt:91-94,106-108`). This is grant
  replay across server identity.
- Live clients are not canceled by the admin action. The registry is private to the provider
  (`RunScopedAgentToolCatalogProvider.kt:95-100`), and the provider only closes it at an execution
  boundary (`:74-77`; `AgentPromptedLoopCoordinator.kt:82-87`). `McpClient.cancel` exists
  (`McpClient.kt:148-156`) but is never called by the executor; only coroutine-driven OkHttp cancel is
  wired (`McpHttpTransport.kt:100-118,230-236`).
- Every MCP call is supposed to pause for ASK, which accidentally limits the live-client window, but
  the approval path itself does not invalidate on admin changes and the grant remains active.

#### F-06 (P1) — Stale catalog/schema: no notification handling, no schema persistence, no approval invalidation

- `McpClient` stores `listChanged` but `rpcExchange` ignores all notifications
  (`McpClient.kt:91,264-292`, especially `:269`). There is no GET stream and no dispatch of
  `notifications/tools/list_changed`.
- `AgentMcpRegistry` has a catalog-change listener, but its default is `NONE`
  (`AgentMcpRegistry.kt:44-55,147`), and the provider creates the registry without a listener
  (`RunScopedAgentToolCatalogProvider.kt:95-100`). `refreshTools`/`refreshConnectedTools`/
  `setCatalogChangeListener` have no production caller (Section 5.2).
- The store persists endpoint/displayName/flags but **not** a schema hash or tool version
  (`AgentMcpServerStore.kt:19-30,47-54`), contrary to architecture "server config 和 schema hash 持久化"
  and "schema/version/server origin 改变使旧审批失效" (`MCA_AGENT_V1_ARCHITECTURE.md:802`).
- The provider caches one catalog per run (`RunScopedAgentToolCatalogProvider.kt:67-72,144`); on
  resume it creates a new registry and reconnects (`:95-100`). A fresh registry has
  `previousWasConnected=false`, so it does not report drift (`AgentMcpRegistry.kt:525-534`). Once
  F-01 is fixed, a pending approval resolved after the server changed its schema could execute the
  old frozen command against a new adapter: `McpToolExecutor.execute` checks `toolKey` and JSON
  parseability (`McpToolAdapter.kt:135-146`), not `toolVersion`/`schemaHash`/`argumentsHash`.
- MCP drift does not call `invalidatePendingForRun`; the only production invalidation triggers found
  are Skill revocation and run supersede (`MainRuntimeAssembly.kt:239-250`;
  `AgentTransitionApplier.kt:154-162,620-628`). The architecture explicitly lists MCP schema change
  as an approval-invalidating event (`MCA_AGENT_V1_ARCHITECTURE.md:554`).

#### F-07 (P1) — Origin/path/transport collisions are not bound to a server identity

- Provider canonicalization drops the path (`RunScopedAgentToolCatalogProvider.kt:171-177,190-204`);
  `AgentMcpEndpointPolicy.check` returns an origin without path (`AgentMcpEndpointPolicy.kt:51-52`);
  the grant record has only origin/transport/data classes/source ids (`AgentNetworkEgressPolicy.kt:66-94`).
- The provider skips creating a grant when the canonical origin is already in `allowedOrigins`
  (`RunScopedAgentToolCatalogProvider.kt:106-108`). Two configs on the same origin but different paths
  therefore share one grant; there is no UI/approval distinction between them.
- The guard request includes `serverId` and `localServer` (`AgentMcpEgressGuard.kt:10-14`), but the
  provider's guard ignores both and compares only `origin`
  (`RunScopedAgentToolCatalogProvider.kt:155-166`). The grant's `transport` is not checked at the
  guard either; the store's canonicalization is the only class check.
- Impact: consent is origin-level, not "this MCP server at this path". A shared host/tenant or a
  re-added server at the same origin inherits the authorization.

#### F-08 (P1) — Run policy does not narrow the MCP catalog

- `AgentRunPolicy.enabledToolKeys` is documented as the enabled tool set
  (`AgentRunTypes.kt:46-47`), but the provider assembles all built-ins, all `enabledSkills`, and all
  connected MCP adapters (`RunScopedAgentToolCatalogProvider.kt:135-139`;
  `AgentToolCatalogAssembler.kt:112-160`). No production code intersects `enabledToolKeys`; it is
  only serialized/deserialized (`AgentRecordJson.kt:56-70`; `AgentOwnerSupport.kt:228-230`).
- `enabledSkills` is captured once when the runtime assembly is created
  (`MainRuntimeAssembly.kt:314-340`), not per run from `policy.skillIds`
  (`AgentRunTypes.kt:48`). A run whose policy is narrower still receives the startup skill/MCP set.
- Impact: a task policy cannot disable MCP tools or a specific server; the only way to remove them is
  the admin store. This is a policy-enforcement gap, not a remote bypass.

### 3.3 P2 findings

#### F-09 (P2) — Shared OkHttp lifecycle and no transport close

- `AgentMcpClientFactory.default` creates one `sharedClient` and reuses it for every `McpClient`
  (`AgentMcpRegistry.kt:13-24`); `AgentMcpRegistry` defaults to it (`:137-139`), and the provider's
  default factory is also created from it (`RunScopedAgentToolCatalogProvider.kt:56`).
- `McpClient.disconnect` never calls `transport.close` (`McpClient.kt:162-175`); static search found
  no `transport.close` caller. `OkHttpMcpTransport.close` shuts down the shared dispatcher only when
  `closeClient=true`, which production never passes (`McpHttpTransport.kt:61-64,96-98`).
- Impact: sockets/threads and pooled connections persist for the process; a run release is not a
  definitive network close. No cross-origin auth bypass was found because the pool is keyed by the
  OkHttp address and the guard is per origin; still, revocation/cleanup semantics are weaker than the
  KDoc claims (`AgentMcpRegistry.kt:122-139`).

#### F-10 (P2) — Token-leak hazard in future auth seams

- V1 is good: the store persists no token/header (`AgentMcpServerStore.kt:11-18,38-45`), endpoint
  query/userinfo are rejected (`AgentMcpEndpointPolicy.kt:36-40`), and no logging of
  `McpHttpPost`/`McpServerConfig` was found.
- However, `McpHttpPost` is a data class with `bodyJson` and `bearerToken`
  (`McpHttpTransport.kt:19-27`), `McpServerConfig` includes `auth`
  (`McpProtocolTypes.kt:60-75`), and `McpAuth.SecretReference` is already modeled
  (`McpProtocolTypes.kt:44-54`). The default `toString()` of these types would disclose a token if
  any future diagnostic logs the object. Fix before adding credentials, not after.

#### F-11 (P2) — Documentation/evidence drift creates false confidence

- `AgentMcpAdminService` says "connectivity still requires a run-scoped egress grant in
  `AgentMcpRegistry`, which composition deliberately does not provide yet"
  (`AgentMcpAdminService.kt:15-19`), but composition now does provide the provider and grant store
  (`MainRuntimeAssembly.kt:334-340`).
- `MainRuntimeAssembly` says "No MCP server registry exists yet, so the list is intentionally empty;
  a connected adapter would be added here" (`MainRuntimeAssembly.kt:311-313`), while the same method
  passes `RunScopedAgentToolCatalogProvider` (`:334-340`).
- The existing evidence document says MCP is "intentionally empty until a server registry exists" and
  "Skills/MCP are not yet merged into the production per-run catalog"
  (`verification-2026-10-19.md:36-44,100-101`), while the current working tree wires the provider;
  the handoff note says the provider auto-mints grants and the client is process-shared
  (`MCA_AGENT_V1_HANDOFF.md:53,59`). These statements cannot all be true for one snapshot.
- Impact: reviewers/integrators may either over-trust the ASK path or under-test it. Fix the comments
  and add one authoritative wiring section to the evidence index.

#### F-12 (P2) — Minor normalization, strictness and state-machine gaps

- Origin normalization differs between `AgentEgressOrigins.canonical` and
  `AgentMcpEndpointPolicy.check` for default ports and trailing dots
  (`AgentEgressOrigins.kt:23-29`; `AgentMcpEndpointPolicy.kt:51-52,75-76`). Valid configs can be
  skipped by the guard (fail closed) and users see a generic failure.
- `AgentMcpServerStore.fromJson` uses coercing `getBoolean` for `enabled`/`allowLocal` while using strict
  type checking for `allowAgentNetwork` (`AgentMcpServerStore.kt:76-89`). Make all security flags
  strict.
- `AgentMcpRegistry.refreshAndConnectEnabled` returns the current snapshot on a corrupt store without
  closing already-connected clients (`AgentMcpRegistry.kt:207-212`); `setEnabled`/`remove` rejection
  paths similarly leave live entries (`:384-409`). No production caller was found for
  `refreshAndConnectEnabled`, but the public methods should close/fence on store failure.
- `AgentMcpAdminService` takes an unused `Context` (`AgentMcpAdminService.kt:20-23`). Cosmetic.
- `AgentMcpCatalogChange` has no `runId` (`AgentMcpRegistry.kt:33-42`), so a listener cannot directly
  call `invalidatePendingForRun`; add a run-scoped invalidation hook when wiring drift (F-06).

### 3.4 Ambiguities that should be resolved explicitly

1. **What is "explicit connect authorization" for MCP?** Architecture §12.2 lists "用户创建 server
   配置 → 明确连接授权" (`MCA_AGENT_V1_ARCHITECTURE.md:782`) and §8.3 lists server identity/origin/tool/
   data class (`:565`). The code treats the persistent `allowAgentNetwork` switch as that authorization
   (`AgentMcpServerStore.kt:26-30`; `RunScopedAgentToolCatalogProvider.kt:83-121`). Is that intended,
   or is a per-run/per-server confirmation missing? The UI says the latter
   (`AgentMcpScreen.kt:268-276`; `AgentHomeComposer.kt:134-143`).
2. **Should `LOCAL_ONLY` allow an explicitly granted `LOCAL_SERVER` MCP?** The transport enum says
   `LOCAL_SERVER` is "the only transport that may still exist under LOCAL_ONLY"
   (`AgentNetworkEgressPolicy.kt:8-14`), and `checkTransport` allows it with a grant (`:212-221`), but
   the provider returns `baseCatalog` for every `LOCAL_ONLY` run, so no local MCP ever connects
   (`RunScopedAgentToolCatalogProvider.kt:80-82`). Decide which behavior is contractual; today the
   architecture text and the implementation disagree.
3. **Should the MCP origin be part of the frozen command/approval hash?** `networkDestinations` is a
   `FrozenToolCommand` field (`AgentInvocationTypes.kt:9-25`) but is not populated for MCP and is not
   hashed (`AgentFrozenCommandFactory.kt:200-216`). If origin is not bound, the approval cannot mean
   "send this to server X".
4. **What is the source/data-class projection for MCP arguments?** The provider grants `PRIVATE` with
   an empty source set (`RunScopedAgentToolCatalogProvider.kt:113-114`), while S10 requires
   server/tool/origin/source coverage (`MCA_AGENT_V1_ACCEPTANCE_MATRIX.md:143`). Without a defined
   projection, `checkPayload` cannot be applied consistently.
5. **Is `AgentPermissionBroker.authorize` the intended single consume point, or is the journal's
   `ConsumeApprovalTransition`?** The KDoc and the production owner disagree
   (`AgentPermissionBroker.kt:23-26,187-242`; `AgentOwnerInteractionCommands.kt:183-258`;
   `AgentRuntimeAssembly.kt:150-157`). Pick one and make the other impossible or explicitly
   validation-only.
6. **What should happen to a live run when a server is disabled/removed or `allowAgentNetwork` is
   turned off?** Architecture says cancel in-flight calls, invalidate approvals and grants, and fence
   late callbacks (`MCA_AGENT_V1_ARCHITECTURE.md:802-804`; `MCA_AGENT_V1_EXECUTION_PACK.md:323`).
   The current admin path only rewrites JSON (`AgentMcpAdminService.kt:26-111`).

## 4. Recommended fixes (ranked)

> These are recommendations only. No production code was edited. Commands are acceptance targets to
> run later; this review did not run Gradle, tests, or a device.

### P0 — must be fixed before MCP is enabled for real tasks

#### FIX-01 (P0) — Persist the frozen invocation and make the ASK path executable

**Targets:** `core/agent-runtime/.../AgentPromptedLoopCoordinator.kt`,
`core/agent-runtime/.../AgentOwnerExecution.kt`,
`core/agent-contract/.../AgentJournal.kt`,
`app/.../agent/data/AgentTransitionApplier.kt`,
`app/.../agent/security/AgentPermissionBroker.kt`,
`app/.../agent/composition/AgentToolPermissionHook.kt`.

**Required behavior**
1. Before `AgentPermissionBroker.decide` is asked for approval, persist a `ToolInvocation` (status
   `WAITING_APPROVAL`) with `invocationId`, `stepId`, `callOrdinal`, toolKey/version, `schemaHash`,
   `argumentsHash`, `resourceFingerprint`, and a stable idempotency key, using
   `UpsertInvocationTransition` (`AgentJournal.kt:77-84`; handler
   `AgentTransitionApplier.kt:280-307`). The simplest location is
   `AgentOwnerExecution.waitForApproval` (it already has `result.frozenCommand`) or an extended
   freeze hook that returns the frozen command to the coordinator. The write must be atomic with the
   approval's PENDING insert (extend the journal transition or use a dedicated transaction) so a
   crash cannot leave a "pending approval with no invocation" row.
2. Wire the one-shot authorization. Either:
   - make `AgentPermissionBroker.authorize` the single consume point and remove/replace the journal's
     duplicate consume, or
   - keep the journal CAS but add a pre-executor `AgentFrozenIntegrity`/policy check with the same
     `revalidate` semantics (`AgentPermissionBroker.kt:244-272`) and update the KDoc so the claimed
     invariant is true. The current state (KDoc says CAS, production calls neither `authorize` nor
     `approvalStore.consume`) is not acceptable.
3. Ensure the resume path calls the same authorization check before `executor.execute`; it currently
   goes straight from `approvedCommand` to the executor (`AgentPromptedLoopCoordinator.kt:127-159`).
4. Add an integration test using the real `RoomAgentApprovalStore` and journal: freeze an MCP command →
   pending approval exists → resolve ALLOW_ONCE → exactly one execution → second resolve/consume is
   rejected. The existing tests use fakes; they do not catch F-01a.

#### FIX-02 (P0) — Emit the approval request event and bind the destination

**Targets:** `core/agent-runtime/.../AgentOwnerExecution.kt`,
`core/agent-contract/.../AgentEvents.kt`,
`app/.../agent/security/AgentFrozenCommandFactory.kt`,
`app/.../agent/composition/AgentToolPermissionHook.kt`,
`app/.../agent/extensions/mcp/McpToolAdapter.kt`,
`app/.../agent/security/AgentPermissionTypes.kt`, `feature/.../AgentApprovalProjection.kt`.

**Required behavior**
1. When a tool call enters `WAITING_APPROVAL`, append one `TOOL_APPROVAL_REQUESTED` event with
   `causationId = invocationId` (or `commandId = invocationId`) and a bounded JSON summary containing
   at least `tool`, `action`, `args` (bounded/redacted), and `network` (canonical origin). The event
   type already exists (`AgentEvents.kt:47-51`); the projection already accepts this shape
   (`AgentApprovalProjection.kt:114-172`). Commit it in the same transaction as the state/approval
   write where possible.
2. Add the MCP canonical origin to the frozen command's `networkDestinations` before hashing:
   - populate `AgentFreezeRequest.networkDestinations` from a host-supplied MCP descriptor/registry
     binding, not from model text;
   - include the destinations in `AgentFrozenHashing.commandPreimage` and in
     `AgentApprovalBinding`/`AgentApproval` so changing the origin/schema invalidates the approval;
     because this changes the frozen-command hash, bump the domain (`mca.frozen-command.v1` -> `v2` or
     a new schema version) and make old approvals explicitly non-consumable rather than silently
     reinterpreting persisted hashes;
   - expose `toolKey`, sideEffect, origin and dataClass in the projection; the sheet already renders
     "网络目标" (`AgentApprovalSheet.kt:84`).
3. Test with a fake transport that the sheet receives `toolKey`, `args`, and `origin`; assert no
   secret/oversized content leaks into the 4 KiB event summary (`AgentEvents.kt:27-29`).

#### FIX-03 (P0) — Replace auto-mint with explicit per-run MCP egress authorization

**Targets:** `app/.../agent/composition/RunScopedAgentToolCatalogProvider.kt`,
`app/.../agent/security/AgentNetworkEgressPolicy.kt`,
`app/.../agent/security/AgentEgressGrantStore.kt`,
`app/.../agent/data/egress/RoomAgentEgressGrantStore.kt`,
`feature/agent-workspace/**`, `app/.../presentation/AgentMcpAdminService.kt`,
`app/.../composition/AgentRuntimeAssembly.kt`.

**Required behavior**
1. Do not construct `AgentEgressGrant` in the provider. Remove
   `RunScopedAgentToolCatalogProvider.kt:106-121`; instead require a grant that was created through
   `AgentNetworkEgressPolicy.createGrant` after an explicit user action showing server identity,
   canonical origin, tool/data class, and scope. The provider should fail closed (omit the server)
   with a typed "MCP egress authorization required" result, not mint silently.
2. Add a per-run authorization use case/port (e.g. `AgentMcpEgressAuthorizationPort`) that the agent
   workspace UI invokes for a specific `serverId`/`origin` and receives a `grantId`; the grant must be
   bound to `serverId` (add the field to a versioned grant scope JSON, not an undeclared extra key)
   and, where feasible, the endpoint path identity. `createGrant` already validates LOCAL_ONLY,
   transport class and canonical origin (`AgentNetworkEgressPolicy.kt:275-311`). Old scope JSON
   without `serverId` must decode as legacy (not silently upgraded to a server-bound grant) and be
   revoked/re-requested rather than reused for a new approval.
3. Set an expiry (at least task/run end; better a short TTL for new origins) and add
   `AgentEgressGrantStore.revokeForRun(runId)`; call it from provider `release` and from server
   disable/remove. Update the Room adapter to CAS-revoke by run id. Do not leave grants indefinitely
   active after their run.
4. Make the UI truthful: either implement the "任务级出站授权" it advertises
   (`AgentMcpScreen.kt:268-276`; `AgentHomeComposer.kt:134-143`) or change the copy to state that the
   persistent switch is the only consent. The former is required by architecture §8.3/§12.2.
5. Tests: no grant → no socket/no adapter; explicit grant → connect; release/disable → grant revoked
   and client closed; same origin different path/serverId does not reuse a grant.

### P1 — required before treating MCP as a production capability

#### FIX-04 (P1) — Address-class/DNS/proxy policy

`AgentMcpEndpointPolicy.kt`, `AgentEgressOrigins.kt`, `McpClient.kt`, `McpHttpTransport.kt`,
`AgentMcpRegistry.kt`:
- one shared classifier for IPv4, IPv6, IPv4-mapped, loopback/private/link-local/multicast/
  unspecified (`0.0.0.0`, `::`, `224/4`, `ff00::/8`, `::ffff:0:0/96`, ULA, link-local);
- resolve `InetAddress.getAllByName` before connect, reject any private result, reject
  public/private mixed answers, and pass a custom OkHttp `Dns` that returns only the verified
  addresses (pin the route) for the actual call;
- re-check on every new connection/reconnect; do not rely on a prior approval/DNS answer;
- set `proxy(Proxy.NO_PROXY)` or explicitly reject a system proxy for Agent MCP;
- tests for `[::ffff:127.0.0.1]`, `0.0.0.0`, `224.0.0.1`, `[fd00::1]`, `[fe80::1]`,
  a hostname resolving to `127.0.0.1`, and a two-answer DNS rebinding fixture.

#### FIX-05 (P1) — Per-call payload/source authorization

`McpToolAdapter.kt`, `McpClient.kt`, `AgentNetworkEgressPolicy.kt`,
`RunScopedAgentToolCatalogProvider.kt`:
- before `client.callTool`, build an `AgentEgressPayload` for `tools/call` with the actual server
  origin, tool key and a bounded source projection of the frozen arguments (hash + data class; never
  log content), and call `checkPayload` with the run's scope and grants;
- map `AuthorizationRequired` to an ASK for the missing sources/origin (or NOT_EXECUTED if the run
  cannot ask) and `Denied` to a typed tool error; do not send unapproved content;
- define the source id/hash projection for MCP arguments and ensure `allowedSourceIds` is meaningful;
  today the provider grants an empty set (`RunScopedAgentToolCatalogProvider.kt:113-114`).

#### FIX-06 (P1) — Drift and revocation wiring

`McpClient.kt` (notifications), `AgentMcpRegistry.kt`, `RunScopedAgentToolCatalogProvider.kt`,
`AgentMcpAdminService.kt`, `AgentEgressGrantStore.kt`, `RoomAgentEgressGrantStore.kt`:
- handle `notifications/tools/list_changed` and refresh `tools/list`; persist the last schema hash per
  server (extend `servers.json` schema with a version, or a separate table) and compare on every
  connect/resume;
- on schema/version/origin change: close/fence the client, invalidate PENDING approvals for affected
  runs, revoke the origin grants, and require a new ASK;
- on server disable/remove or `allowAgentNetwork=false`: cancel owned in-flight calls, close the
  live client, invalidate approvals and revoke grants. The admin service needs a registry/command
  path, not just the JSON store. A listener with `runId` is needed because
  `AgentMcpCatalogChange` currently has no run identity (`AgentMcpRegistry.kt:33-42`).

#### FIX-07 (P1) — Bind execution to the approved descriptor/schema

`McpToolAdapter.kt`, `AgentPromptedLoopCoordinator.kt`, `AgentRuntimeAssembly.kt`:
- in `McpToolExecutor.execute`, require `command.toolVersion == descriptor.version`,
  `command.schemaHash == descriptor.schemaHash` and, where feasible, recompute/compare
  `command.argumentsHash` and `resourceFingerprint`; a mismatch must return NOT_EXECUTED;
- on approval resume, compare the pending command's descriptor hash to the current catalog entry; if
  it changed, invalidate and refreeze instead of executing the old command against a new adapter;
- add a test where the server changes `tools/list` schema between approval and resume and assert no
  execution.

#### FIX-08 (P1) — Enforce run policy and bind origins/servers

`RunScopedAgentToolCatalogProvider.kt`, `AgentToolCatalogAssembler.kt`,
`MainRuntimeAssembly.kt`, `AgentNetworkEgressPolicy.kt`, `AgentMcpEgressGuard.kt`:
- intersect the assembled catalog with `run.policy.enabledToolKeys` and build enabled Skills per run
  from `policy.skillIds`, not once at runtime-assembly creation;
- include `serverId` in the grant scope JSON (versioned) and require the guard to match both server id
  and canonical origin; decide whether path must be bound or documented as intentionally origin-only;
- extend `AgentMcpEgressRequest` with runId/toolKey/dataClass/source ids and make the provider guard use
  them, or replace the closure with an `AgentNetworkEgressPolicy` call.

### P2 — hardening/hygiene

#### FIX-09 (P2) — Transport lifecycle

`McpClient.kt`, `McpHttpTransport.kt`, `AgentMcpRegistry.kt`,
`AgentMcpClientFactory`: add `McpClient.close()` that calls `transport.close()`; use a per-run
`OkHttpClient` (or a shared client with `closeClient=false` and explicit pool eviction per origin) and
never shut down the shared dispatcher from a run. Cancel in-flight `Call`s on release/admin revoke.
Document which sockets are expected to survive a run.

#### FIX-10 (P2) — Token-safe types

`McpHttpTransport.kt`, `McpProtocolTypes.kt`: override `toString()` on `McpHttpPost`, `McpServerConfig`
and `McpSession` to redact `bearerToken`/`auth`/`sessionId`; add a log-scan test. If credentials are
added, use the Keystore-backed reference only (`McpAuth.SecretReference`) and keep export/backup
exclusions (`backup_rules.xml:22-24`; `data_extraction_rules.xml:20-22,51-53`).

#### FIX-11 (P2) — Strict config decoding and close-on-corrupt

`AgentMcpServerStore.kt`: use strict boolean parsing for `enabled`/`allowLocal` (mirror
`allowAgentNetwork`, `:83-89`); make `refreshAndConnectEnabled`/`setEnabled`/`remove` close/fence
existing clients on `Corrupt` or store rejection (`AgentMcpRegistry.kt:207-212,384-409`).

#### FIX-12 (P2) — One canonicalizer and documentation truth

`AgentEgressOrigins.kt`, `AgentMcpEndpointPolicy.kt`: share one canonicalizer for default ports,
trailing dots, IPv6 forms and path rejection; add tests for `https://host:443` vs `https://host` and
`https://host.`. Fix the stale comments in `AgentMcpAdminService.kt:15-19`,
`MainRuntimeAssembly.kt:311-313`, and the evidence/handoff contradiction
(`verification-2026-10-19.md:36-44,100-101`; `MCA_AGENT_V1_HANDOFF.md:53,59`).

## 5. Evidence and verification status

### 5.1 Primary source digests (SHA-256 of the reviewed working-tree files)

These digests let a later reviewer confirm the exact snapshot even though the files are untracked.

| File | SHA-256 |
| --- | --- |
| `RunScopedAgentToolCatalogProvider.kt` | `4a7f177fa01851f2867e9a872aa25d2eb8b4e7f5140aad576541c32304abb6bf` |
| `AgentMcpRegistry.kt` | `1aab74f4b746ea3c16cdbf69406363ff4b75e92f4df64f316e305975bf6316bd` |
| `AgentMcpServerStore.kt` | `2e75dbd02d2a51d13b0b98d1647bb5f3806da817d92946367986f3de26c0a738` |
| `AgentMcpAdminService.kt` | `f15451eb4c8cb31e21d5c5972158022d3cdc55938f274116c8464d410e3834b4` |
| `AgentNetworkEgressPolicy.kt` | `fd7727c584447eeb04c448d83654b4658fa48e4042cd2c1af9e81c5c08547a45` |
| `AgentMcpEndpointPolicy.kt` | `9f99107d099ef328167fb8b0cfff4899db14764b03f2dc18e4501ba5117ada9c` |
| `McpHttpTransport.kt` | `dc59c7d2eef1207782012fbc7c570f77b02b401159358fb603297eca19662cf7` |
| `McpClient.kt` | `d5a0f8d7fda85cf08ec2ec5302e01a7e15f646791aac680910c2b4af33874cd8` |
| `McpToolAdapter.kt` | `aea60633dca54881d2db7ad5ce925f0ff7982949bfcea9299152f8d5cf006cc2` |
| `AgentToolPermissionHook.kt` | `c48045eb10246e8e652234df48b2d4f92d2014f22ffb05c1b446113eaf7862ac` |
| `AgentPermissionBroker.kt` | `4dbcc8cf4cb9e17f0be558ff7f4a13a3816fc8418221896d2d66281cf990d887` |
| `AgentPromptedLoopCoordinator.kt` | `31672c625161ececc91fb7d176b98cf1bd4047377e8aa853e60aecf5ac7e6535` |
| `AgentOwnerExecution.kt` | `03d0522c6e7ab98440aa12ec8870e6f1ee1d4ed7988730c58e377cfaa715f15e` |
| `AgentOwnerInteractionCommands.kt` | `d789d168c542d289fdc4a9ce7ea5800aaea2d72e355748361e7bbd8930b0a1df` |
| `AgentTransitionApplier.kt` | `efcbc2ff53b124abf164762447d56c04697871b16a2a9e1166d059edff582a7a` |
| `RoomAgentEgressGrantStore.kt` | `f24937d1725145d867ec9ddd6ca711ad0e71ba50c5ec5cc1728119131d54ab30` |

### 5.2 Negative-evidence searches (read-only, no Gradle)

Search scope: `app/src/main/java`, `core`, `feature` (`*.kt`). These are the exact negative checks
behind "no production caller" statements:

| Search | Result | Used for |
| --- | --- | --- |
| `TOOL_APPROVAL_REQUESTED` | Only `AgentEvents.kt:48`, UI projection `AgentApprovalProjection.kt`, previews/tests; no coordinator/owner emitter | F-01c |
| `UpsertInvocationTransition(` | Only `AgentJournal.kt`, `AgentStartupRecovery.kt:185`, tests; no coordinator/owner writer | F-01a |
| `.authorize(` / `AgentExecutionAuthorization` | Only broker definition and tests; no production caller | F-01b |
| `approvalStore.consume` / `.consume(` | Broker only (`AgentPermissionBroker.kt:198`); no production call; journal uses `consumeCas` separately | F-01b |
| `checkPayload(` / `checkTransport(` / `createGrant(` / `.revoke(` | Policy definitions and tests; production only `canonicalOrigin` (`RunScopedAgentToolCatalogProvider.kt:192`; `RoomAgentEgressGrantStore.kt:135`) | F-02, F-04 |
| `refreshTools(` / `refreshConnectedTools(` / `setCatalogChangeListener(` | Registry definitions and tests; no production caller | F-06 |
| `transport.close` | No caller in `agent/extensions/mcp` main sources | F-09 |
| `AgentMcpRegistry(` | Provider construction only (`RunScopedAgentToolCatalogProvider.kt:95-100`) plus tests | control scope |
| `enabledToolKeys` | Contract/JSON/serialization only; no provider/assembler intersection | F-08 |
| `revokeTaskScope` / `.revoke(` | Store/DAO/tests only; no production caller | F-05 |

Caveat: these are static searches over the current working tree. If code generation, reflection, or
a caller outside `app/core/feature` exists, re-run the searches before treating a negative as final.

### 5.3 Result status

| Item | Status | Scope |
| --- | --- | --- |
| Source review of the named files | PASS (static) | Working tree at HEAD `2368c72` + untracked Agent tree |
| Build / unit tests / instrumentation | NOT_RUN | Explicitly excluded ("No Gradle") |
| Real MCP server / DNS / TLS fixture | NOT_RUN | No controlled server in this review |
| Device UI approval flow | NOT_RUN | No device run |
| Production code changes | NONE | Only this report was written |

### 5.4 What would change the P0 conclusion

- If another production writer creates `agent_invocations` rows before the permission hook (outside
  the searched sources), F-01a's runtime consequence changes, but F-01b/F-01c/F-01d still stand.
- If the approval event is emitted by a path not found here (e.g., a journal decorator outside
  `app/core/feature`), F-01c must be re-checked; `AgentOwnerExecution.waitForApproval` currently has
  no such call.
- If `allowAgentNetwork` is redefined in the product as the explicit per-server connect authorization,
  F-02 is reduced to a UI/expiry/revocation problem; it is still not a per-run data-class authorization
  and does not meet architecture §8.3/§12.2.

## 6. Bottom line

The code has the right shape for a secure MCP V1: origin canonicalization, a connect-time guard, a
generation fence, a bounded transport, a fail-closed grant store and a one-shot journal CAS. But the
two controls that make those pieces meaningful are not in the execution path:

1. **F-01 (P0):** no frozen invocation is persisted, no `tool.approval_requested` event is emitted,
   the broker's one-shot `authorize` is dead, and the MCP origin is absent from the approval binding.
   The ASK gate is currently non-functional; if repaired minimally, it would be blind.
2. **F-02 (P0):** the run-scoped grant is auto-minted from a persistent admin toggle, with `PRIVATE`
   data class, no expiry, no per-run user prompt and no revocation. The UI claims a task-level
   authorization that does not exist.

Do not expose MCP to real user tasks (or advertise "MCP tools with per-call approval") until P0
fixes land and are proven with a real Room approval integration test plus a controlled MCP fixture.
The P1 items then determine whether the feature is defensible against a malicious server, DNS/SSRF
and catalog drift.
