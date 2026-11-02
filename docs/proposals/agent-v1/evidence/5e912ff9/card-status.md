# Agent V1 卡片执行状态（滚动台账）

- 设计基线：`5e912ff9ac7bf78f1ed1f452657da642cfbdfead`
- 当前 checkout HEAD：`26a634789ef895ee2ca3732b074b5b68cfa3c293`（`5e912ff9..HEAD` 仅 2 个 benchmark 文件；本功能引用源码字节一致）
- 子模块：`third_party/tokenizers-cpp` dirty，patch SHA-256 `8B6C0D1DA1133E4A231482F80BE967C73D85AA403E5F2A20EBAB97A1EB22EDD6`
- 并行会话：另一会话正在做前端优化，可能修改 `feature/chat`、`MainActivity`、`MainViewModel`、`core/designsystem`。本功能的共享写集（导航/根状态/DB 注册/备份 XML）由 Lead 最后统一接线，接线前重新读取当前文件，绝不回滚他人改动。
- 结果语义：PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE。本文不是发布验收报告。

> **历史证据说明（2026-10-20 01:10 增补，保留下方原表不改写）**：本文件是 `5e912ff9`
> 证据快照（写于 2026-10-19 16:20），表中“3245 / 14/14 / runtime 123”等均为该快照的历史
> 数字，不是当前树结果；下方“仍未完成”清单也只代表当时状态。当前交付基线为 `7475fd6`
> （抓取时仓库 HEAD `af24899`）；架构 **16/16**（2026-10-20 01:05）；contract 19 /
> runtime 132 / workspace 118 为 00:17 的**分模块独立运行**；app 全量未重跑（最新 app
> 记录是 00:30 过滤 DSH 26/1 failed）；full gate **NOT_RUN/BLOCKED**。详见
> `../../../agent-chat-integration-v1/V1_1_STATUS.md`。

## 卡片状态

| 卡 | 状态 | 已交付 | 证据 | 未完成/风险 |
| --- | --- | --- | --- | --- |
| K00 事实与 ADR | PASS（源码层） | owner-map、capability-matrix、risk-register、dependency-verification、navigation-ui-map、ADR-0002 | `evidence/5e912ff9/*.md`、`docs/adr/0002-agent-v1-runtime-and-delivery.md` | 运行证据全部 NOT_RUN；真实设备/模型/云未验证 |
| K01 模块/契约/基础门禁 | PASS（编译/契约层） | 3 个模块、9 类契约端口、架构 Rule 9–11、manifest 登记、verify-code `-Changed` 映射、契约测试 16 项 | `:core:agent-contract:testDebugUnitTest` 16/16；`check_architecture_rules.py` ALL 11 PASSED | A05/A06 生产接线后验；`-AgentTests` 聚合入口留 K15 |
| K02 严格协议/schema | PASS（JVM 确定性） | Strict Jackson codec、PromptedV1 decoder（动态 tool key）、schema validator、transcript/system prompt、golden/恶意输入 fixture | `:core:agent-runtime:testDebugUnitTest` 16+93 中协议/校验相关类全绿（总 93/0/0） | 真实模型输出/设备 NOT_RUN |
| K03 确定性 Loop/状态机 | PASS（JVM 确定性） | AgentRunOwner 拆分（最大 447 行）、状态机、预算、上下文装配、prompted loop、InMemoryJournal、命令幂等/CAS 并发 | `:core:agent-runtime:testDebugUnitTest` 93 tests / 0 failures / 0 errors | Room 跨重启、真实 engine、审批恢复端到端 NOT_RUN |
| K04 真实模型 Spike | PASS（JVM adapter） | local/cloud adapter、lease retention、exact stop、Responses strict terminal、choice mapper | `app:testDebugUnitTest` inference 28/0（含 120ms 资源等待死锁修复） | 真机 MNN/GGUF/云 NOT_RUN；native stop proof 缺口 |
| K05/K06 Room/文件/产物 | PASS（JVM，编译绿） | 15 entity/DAO、RoomAgentJournal、命令幂等表、Artifact/WorkspaceStore、迁移 27→28、28.json（26 entities）、backup 排除 | data 78/0；RoomSchemaContractTest 7/0；设备迁移 androidTest NOT_RUN | 真实设备迁移/进程崩溃 NOT_RUN |
| K07 权限/审批/egress | PASS（JVM） | FrozenCommandFactory、broker、egress policy、Room approval store、decision 持久化 | security 59/0（含 100 线程 CAS、deny loop、golden hash） | 真实 payload/设备 NOT_RUN |
| K08 生产工具 | PASS（JVM） | 四工具 executor + immutable catalog | tools 53/0 | 真实设备/真实写盘 NOT_RUN |
| K09 导航/Route | PASS（编译+契约测试） | AppTab.AGENT_WORKSPACE、drawer 图片下方 Agent、ChatShellActions 回调、MainActivity 第 5 overlay | `:app:compileDebugKotlin` 绿；MainShellMotionContractTest 6/0 | 真实渲染/无障碍 NOT_RUN |
| K10 页面全状态 | PASS（JVM） | Route/VM/Home/Detail/Approval/Artifacts/Timeline/Preview；DSH 1:1 composer-first Home + drawer + 对话/轨迹 detail + floating composer | feature 118/0；androidTest 8 编译 PASS | 两轮真实渲染/设备 Compose androidTest NOT_RUN |
| K11 FGS/恢复 | PASS（JVM+组件接线） | AgentForegroundLease/NotificationController/StartupRecovery/StopProofSource + guard/router 接线；`ObservingAgentJournal` 驱动 guard；单个 FGS 通知携带“停止”action（runId/epoch）回到 service→router→owner；FGS 失败→`PauseAgentRun(FOREGROUND_SERVICE_UNAVAILABLE)` | platform 58/0；observer 4/0；contract 19/0 + runtime 123/0 覆盖 typed pause | 设备 FGS/通知权限/真实 stop proof NOT_RUN |
| K12 Skills | PASS（JVM+UI+持久化接线） | ZIP/目录导入、frontmatter、manifest/store、allowed-tools 交集、快照、`FileAgentSkillStore`、Skills 管理 UI、SAF ZIP spool + tree adapter；disable/uninstall 使非终态 run 的 PENDING approval 全部失效 | feature 118/0；Skills 39/0；架构 14/14；全量 3245/0 | 真实 SAF provider/设备导入与渲染 NOT_RUN |
| K13 MCP HTTP | PASS（JVM+registry+管理 UI+run-scoped 接线） | JSON-RPC/SSE/transport/client/tool adapter；`AgentMcpServerStore`/`AgentMcpRegistry`；MCP 管理 UI + `AgentMcpAdminService`；`RunScopedAgentToolCatalogProvider` 在联网 run 内 mint grant→connect→assemble，release 关闭 session | MCP 50/0（28 协议 + 22 registry/store）+ provider 10/0；feature 36/0 UI | 真实 server/设备 NOT_RUN；共享 OkHttp client 不按 run close |
| K14 导入导出/配额 | PASS（JVM+编译） | 版本化 `mca.agent.task-package.v1` 导出/导入（确定性 ZIP/manifest、SHA-256/size 复核、zip-slip/bomb/duplicate/encrypted 拒绝）、`RoomAgentPackageStore`（IMPORTED_ARCHIVED 单事务、物理 artifact 原子落盘）、`AgentTaskDeletionPolicy`（先取消后 TOMBSTONED、FK 顺序删子行、文件清理重试）、`AgentWorkspaceQuota`（256MiB/task、1GiB total、低磁盘拒绝）、staging GC、SAF `AgentPackageService` + CreateDocument/OpenDocument 启动器、UI 导入/导出/删除入口、归档筛选/只读详情 | data 114/0（17 suites，含 K14 36 项）；feature 118/0（含归档 UX 9）；`:app:compileDebugKotlin` 绿；架构 14/14 | 真实 SAF provider/设备导入导出、进程中断 NOT_RUN；导入产物无 artifact_commits 行（v28 invocationId NOT NULL，已记录） |
| K15 门禁收口 | PASS（确定性 code-layer） | 架构 14 规则、manifest 20 modules、故障注入、per-run catalog + release、DSH 前端、Skills/MCP 管理 UI、egress grant store、run-scoped MCP、FGS guard/pause/通知停止、`verify-code -AgentTests` 聚合入口 | `verify-code -AgentTests -Rerun` → **3245 / 0 / 0 / 11 skipped，VERIFY OK**；architecture 14/14；contract 19/0；runtime 123/0；feature 118/0 | 真实 MCP server、设备/模型/云/性能仍 NOT_RUN |
| K16 端到端/回归 | BLOCKED（设备） | deviceTest 隔离变体可构建；JVM 全功能回归 3245/0 | 失败仍为 MIUI `INSTALL_FAILED_USER_RESTRICTED` | 未安装：设备“Install via USB”策略；真实模型/云/FGS/渲染全部 NOT_RUN |
| K17 最终审计 | IN_PROGRESS | ADR、card-status、risk-register、verification、acceptance-status（128 IDs：**58 PASS / 63 NOT_RUN / 7 BLOCKED**，0 缺失/重复）、[交接与使用说明](../../MCA_AGENT_V1_HANDOFF.md) | 全量门禁 3245/0/0/11；架构 14/14；diff whitespace/本专用文件/800 行预算审计通过 | 设备真实证据、MCP 真实 server、发布 APK/回退演练待完成 |

## 已确认的关键基线事实

1. Room `@Database` version = 27，11 entities，`exportSchema=true`；迁移注册集中在 `ChatSessionStoreRoomDatabase.kt`；无 destructive fallback；现有迁移测试只覆盖 23→24→25 且未用 room-testing。
2. engine 由 `MainRuntimeAssembly` 创建（VM 亲缘），`McaAppContainer` 不挂 Application；facade 首次绑定的 engine 生效，后续 engine 参数被忽略。
3. `ChatExecutionFacade.streamChat` 只返回 Flow；identity 需 `snapshot(requestId)`；admission 在 body.stream 前且不在 try 内 → adapter 必须立即收集/失败显式结算。
4. `stopGenerationIfActive=true` 仅表示派发/接受（500ms 超时也返回 true）；`cancelChat` 不触发 engine 停止；需要独立 stop proof。
5. `acquireExclusiveLifecycleLease(false)` 无超时，调用方必须 `withTimeout(120s)`。
6. 云端仅 Responses 有严格 terminal；OpenAI-compatible/Anthropic EOF-after-Done 风险确认；supportsTools 默认 false。
7. FGS：现有 `specialUse` 生成服务、通用通知、无 targetSdk36 专项代码；Local API FGS 在主进程。
8. Jackson Core 2.22.4 已核验（Apache-2.0、零运行时依赖、jar sha256、无已知影响 CVE）；MCP 2025-06-18 是明确 pin 而非 latest。

## 集成与官方证据（2026-10-19 06:0x）

已完成的真实 Gradle 任务（统一经 `scripts/agent-verify.ps1`，串行 build lock；本轮环境用 `--no-daemon` + `-Pkotlin.compiler.execution.strategy=in-process` 规避并行会话造成的 Kotlin daemon/文件锁损坏，任务与断言不变）：

| 任务 | 结果 | 证据 |
| --- | --- | --- |
| `:core:agent-contract:testDebugUnitTest` | PASS | 16 tests / 0 failures |
| `:core:agent-runtime:testDebugUnitTest` | PASS | 93 tests / 0 failures |
| `:feature:agent-workspace:testDebugUnitTest` | PASS | 51 tests / 0 failures |
| `:app:testDebugUnitTest`（agent.* + RoomSchema + MainShell） | PASS | 324 tests / 0 failures / 37 suites；data 78、extensions 58、inference 28、platform 35、security 59、tools 53、shell 6、schema 7 |
| `:app:compileDebugKotlin` | PASS | BUILD SUCCESSFUL（含 K09 导航/组合根） |
| `:app:compileDebugAndroidTestKotlin` + `:feature:agent-workspace:compileDebugAndroidTestKotlin` | PASS | 仪器测试源码可编译；执行 NOT_RUN |
| `:app:assembleDebug` + `:app:assembleDebugAndroidTest` | PASS | 生成 app-debug.apk 385 MB / androidTest APK |
| `python tools/architecture/check_architecture_rules.py` | PASS | ALL 14 CHECKED ARCHITECTURE RULES PASSED |
| `python tools/architecture/generate_architecture_manifest.py` | PASS | 20 modules 登记 |

修复的真实缺陷（均有测试/编译复验）：Room 迁移正则需字面量 `Migration(27, 28)`；approval decision 需持久化（`ConsumeApprovalTransition.decision`，DENY→DENIED）；MCP 404 刷新需清 session；frontmatter block scalar 不能 trim 末尾换行；Skills 树 fixture 不得把 `docs/new.md` 当 leaf；`McaAgentLocalModelAdapter` 超时分支必须先 cancel acquire child，否则 `coroutineScope` 永不返回；`AgentSkillToolPattern` 两个 erase 同签名需改名；启动恢复 fake 的 `failCommitForRunId=null` 误匹配 NULL runId。

设备执行（`app/src/androidTest/.../agent/data/AgentRoomMigrationTest`、`AgentRoomJournalTest`）当前 **BLOCKED**：连接的 `f9d98aa3`（2304FPN6DC / API 36 / arm64）已安装 `com.muyuchat.mca`，但与本次 debug APK 签名不一致；`adb install -r` 被 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝，只能卸载重装。卸载会清空设备上既有 MCA 数据，故本轮不做破坏性操作，保持 NOT_RUN/BLOCKED，待用户授权或提供匹配签名/独立测试设备。

## 工程环境事件

1. 并行前端会话曾运行原生 `gradlew`（未走 build lock），与受控构建并发，导致 Kotlin 增量缓存损坏、`classes.jar` 文件占用与多次 `BUILD FAILED`；处置为在 build lock 内清理 stale Gradle/Kotlin daemon，并用单次 daemon + in-process Kotlin 串行构建。
2. `gradle.properties` 调整为 Gradle 4 GiB / Kotlin daemon 3 GiB / workers 6（16 GiB 机器）；这是构建容量而非门禁放宽。
3. `scripts/agent-verify.ps1` 增加 `MCA_QNN_SDK_ROOT` 默认值、`-Tests` 改为 `[string[]]`、`-KeepDaemons` 开关、加锁后清理 stale daemon；`_Tests` 不能由调用方传会被 shell 展开的 `*Agent*` 通配符（会被展开成 `AGENTS.md`），本轮改用 `com.muyuchat.mca.agent.*` 等不会命中文件的 filter。

## 仍未完成（不得宣称 V1 完成）

1. **K14 导入/导出/删除/配额**：未实现（数据/SAF/feature intent 均 PENDING）。
2. **真实设备执行**：Room 27→28 迁移、Room journal、6 个 Compose Agent androidTest、FGS/通知/stop、SAF、TalkBack/字体/IME 两轮渲染全部 NOT_RUN。
3. **真实模型**：MNN/GGUF/真实云端、native stop proof、云端 egress client 未接；云模型选择暂时隐藏。
4. **Skills/MCP 运行时接线**：仍未合并进 per-run tool catalog；MCP 未接 egress/registry；Skill revocation→approval invalidation 未接。
5. **K15/K16/K17**：故障注入收口、端到端回归、最终审计与交接文档未完成。

## 下一步（Lead）

1. 用户授权后在独立设备/匹配签名上执行 instrumentation（迁移 + journal + Compose Route）并取 logcat/XML。
2. 实现 K14：版本化任务包导出（manifest/hash/排除 secrets/grants）、SAF CreateDocument、staging 导入校验、IMPORTED_ARCHIVED、删除先取消后 TOMBSTONED、软配额警告。
3. 完成 Skills/MCP → per-run catalog → broker 的接线与 approval invalidation。
4. K15 故障注入（approval/commit/stop）+ `verify-code -AgentTests` 聚合门禁；K17 审计。
