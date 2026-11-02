# Agent V1 verification log — 2026-10-19 (Lead)

All runs used the shared MCA build lock. Environment note: while a parallel frontend session was
building without the lock, the Agent lane used `scripts/agent-verify.ps1` with a single-use Gradle
daemon + `-Pkotlin.compiler.execution.strategy=in-process`; tasks and assertions are unchanged.

## Deterministic JVM evidence

| Command | Result |
| --- | --- |
| `agent-verify :core:agent-contract:testDebugUnitTest` | 19 tests / 0 failures |
| `agent-verify :core:agent-runtime:testDebugUnitTest` | 123 tests / 0 failures / 15 classes |
| `agent-verify :feature:agent-workspace:testDebugUnitTest` | 118 tests / 0 failures (DSH Home/Detail, Skills/MCP UI, archive UX) |
| `agent-verify :feature:agent-workspace:compileDebugAndroidTestKotlin` | PASS |
| `agent-verify :app:testDebugUnitTest` with egress/security filters | 70 tests / 0 failures / 6 suites |
| `agent-verify :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `agent-verify :app:compileDebugAndroidTestKotlin` (debug + feature) | BUILD SUCCESSFUL |
| `python tools/architecture/check_architecture_rules.py` | ALL 14 CHECKED RULES PASSED |
| `python tools/architecture/generate_architecture_manifest.py` | 20 modules registered |

Filtered app lane coverage: agent.data 114+4 observer (K05/K06 + K14), agent.security 59, agent.tools 70
(53 K08 + 17 K15 catalog), agent.platform 58 (35 K11 + 23 guard/router), agent.inference 28,
agent.extensions 58+9 file store (Skills 39 + MCP 28), RoomSchemaContractTest 7,
MainShellMotionContractTest 6, KnowledgeBaseRoomContractTest 13.

## Runtime wiring added this round

- `ObservingAgentJournal` decorates the Room journal and reports every committed `CreateRun`/`UpdateRun`
  transition after the delegate transaction succeeds; observer failures never fail a committed command.
- `MainRuntimeAssembly` owns `AgentRunProcessGuard` + `AgentRunStopActionRouter`, passes the observer
  into `AgentRuntimeAssembly`, installs `McaGenerationForegroundService.agentStopActionHandler`, and
  clears/releases on close. The service routes `ACTION_STOP_RUN` directly to the owner and never
  promotes a generation lease for a control action.
- Skills: `FileAgentSkillStore` (atomic JSON, CAS, fail-closed on corruption) + immutable file snapshot
  repository + `AgentSkillManager` are now constructed in composition; the per-run catalog is assembled
  from built-ins + enabled Skills via `AgentToolCatalogAssembler` (Skills can only narrow). MCP tools
  list is intentionally empty until a server registry exists (documented).
- Skill revocation now fails pending approvals closed: disabling/uninstalling a Skill invalidates every
  PENDING approval of every non-terminal run through `AgentApprovalStorePort.invalidatePendingForRun`
  (Room DAO CAS, default no-op for narrow test fakes); runs are not cancelled and the next step
  re-freezes against the current catalog.
- MCP management UI: durable `AgentMcpServerStore` is wired through `AgentMcpAdminService`
  (list/add/enable/remove only, no socket, no token). `AgentMcpRegistry` remains gated on a
  run-scoped egress grant, so composition still passes no MCP adapters.
- FGS notification stop action: the Agent lease now carries `(runId, epoch)` into
  `GenerationForegroundLeaseRegistry.Snapshot`; `buildNotification` adds a “停止” action whose
  PendingIntent goes back to `McaGenerationForegroundService.ACTION_STOP_RUN` and then the typed
  router/owner cancel path. Chat/image leases get no action (null stop action).
- Added 4 `ObservingAgentJournalTest` + 9 `FileAgentSkillStoreTest`.

## Full AgentTests gate (no filter)

`scripts/verify-code.ps1 -AgentTests -Rerun` → **3245 tests, 0 failures, 0 errors, 11 skipped**,
`VERIFY OK (code layer only - this is not the release build)`.

This final run includes run-scoped MCP catalog wiring (10 provider tests: LOCAL_ONLY no socket,
NETWORK_ENABLED grant→connect→assembled catalog, grant rejection fail-closed, release closes the
MCP session, existing-grant reuse, origin/local-server rules), the per-run catalog release hook
(runtime 123), the DSH 1:1 composer-first Home/drawer and 对话/轨迹 detail, Skills/MCP management
UI, the MCP store/registry + schema-drift callback, the egress grant store, archived import UX, the
typed pause reason, FGS stop-action notification, and the Skill-revocation/approval-invalidation
wiring.

The two earlier failures are resolved:
1. `KnowledgeBaseRoomContractTest.migrationChainFrom16To22...` hard-coded `version = 27`; updated to
   28 with the schema upgrade.
2. `McaGenerationForegroundServiceContractTest` expected exactly one `START_NOT_STICKY`; the new
   Agent stop-action control path legitimately adds a second return. The test now also asserts the
   `ACTION_STOP_RUN` branch and the process-local handler.
3. The parallel frontend session's `LocalImageModelIdentity` cache regression was reverted by that
   session; the previously failing native-proof test passes again.

This gate is code-layer only. Device/model/cloud/visual evidence is still NOT_RUN/BLOCKED.

## Device evidence

- Connected: `f9d98aa3`, model 2304FPN6DC, arm64-v8a, API 36.
- Current debug candidate: `app-debug.apk` 372 MB, SHA-256
  `aece2f07f46d078792561005142d42ffcf34bb80ba87248f3fb42b377b12c21e` (built 2026-10-19 16:17,
  includes run-scoped MCP wiring, DSH-style Agent home/detail and the FGS stop action).
- Built isolated installable variant `deviceTest` (`-PmcaDeviceTestVariant=true`, applicationId
  suffix `.devicetest`, debug-signed, `testBuildType = deviceTest`) so the existing signed app and
  its data are not touched. Current artifacts:
  `app-deviceTest.apk` SHA-256 `3920ee496c6b1602e688f090372bad26669bf92cea9b12152732d39f9656a847`;
  test APK SHA-256 `5042b9630604615b443fad9991d8b044f2907a053c863029eb0625fba9c618de`.
- `assembleDeviceTest` + `assembleDeviceTestAndroidTest` BUILD SUCCESSFUL.
- Install BLOCKED: both `adb install -r` and `adb push` + `pm install` fail with
  `INSTALL_FAILED_USER_RESTRICTED` ("Install canceled by user") — this MIUI device requires the
  developer-options “Install via USB” permission, which cannot be toggled from adb. No test was
  falsely reported as passing.

## NOT_RUN / BLOCKED

交接与使用说明见 `../../MCA_AGENT_V1_HANDOFF.md`（含设备安装步骤、验收层级、回退边界与已知限制）。

- Room 27→28 instrumentation migration, Room journal, Compose Agent route, K14 SAF round-trip on
  device: NOT_RUN (install permission).
- Real MNN/GGUF/cloud model calls, native stop proof, FGS runtime, notification stop action wiring,
  TalkBack/font/IME visual rounds: NOT_RUN.
- Skills/MCP are not yet merged into the production per-run catalog (assembler exists and is tested;
  composition still passes built-ins only). Cloud adapter remains hidden without the egress client.
- Imported artifacts have no `agent_artifact_commits` rows because v28 requires a non-null
  `invocationId` FK; a v29 migration is only needed if that audit row becomes mandatory.
