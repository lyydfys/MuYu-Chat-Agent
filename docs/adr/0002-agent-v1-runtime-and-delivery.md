# ADR 0002：Agent V1 的运行时接入、所有权、持久化与交付边界

- 状态：Accepted（V1 实施冻结）
- 日期：2026-10-19
- 决策范围：MCA 原生 Agent V1（K00–K17），不覆盖 V1 之后的浏览器/终端/Android 控制/DSH Host
- 关联文档：[架构合同](../proposals/agent-v1/MCA_AGENT_V1_ARCHITECTURE.md)、[执行包](../proposals/agent-v1/MCA_AGENT_V1_EXECUTION_PACK.md)、[验收矩阵](../proposals/agent-v1/MCA_AGENT_V1_ACCEPTANCE_MATRIX.md)、[硬锁](MCA_PROJECT_HARD_LOCK.md)、[ADR 0001](0001-runtime-ownership-and-identity.md)
- 基线 revision：`5e912ff9ac7bf78f1ed1f452657da642cfbdfead`
- 基线工作区：tracked diff 为空（除 `third_party/tokenizers-cpp` 子模块 dirty，patch SHA-256 `8B6C0D1DA1133E4A231482F80BE967C73D85AA403E5F2A20EBAB97A1EB22EDD6`）；`docs/proposals/agent-v1/` 为未跟踪新增。当前 HEAD 不是干净构建来源。

## 1. 背景与约束

1. MCA 已有一个生产文本执行域：`RuntimeSupervisor`（进程级准入/身份/终态）→ `ChatExecutionFacade` → `ChatExecutionOwner` → `McaInferenceService.streamChat`，实际 native 执行由现有 lifecycle lease 保护（`core/engine/.../ChatExecutionOwner.kt`、`McaInferenceService.kt`、`McaInferenceProfileTransactions.kt`）。
2. engine 的生产创建点在 `MainRuntimeAssembly`，其 scope 是 `MainViewModel.viewModelScope`（`MainRuntimeAssembly.kt:62-66`、`MainViewModel.kt:80-86`）。方案要求 AgentRunOwner 常驻应用作用域，但本轮不允许重写全局运行时所有权（硬锁第 5、14 节）。
3. 现有 `McaAppContainer` 只装配 Application-only 叶子对象；`MainUiState`/`MainCoordinatorHost` 已有大状态/大 Host 债务，Agent 不得继续扩张（硬锁 D01/D02）。
4. 云聊天可直接经 `OpenAiCompatibleChatProvider` 执行，不自动经过 `ChatExecutionOwner`；OpenAI-compatible/Anthropic 存在 EOF-after-Done 风险；Responses 路径已有严格 terminal 校验（见 `evidence/5e912ff9/capability-matrix.md`）。
5. Room 基线版本 27（`ChatSessionStoreRoomDatabase.kt:33`），迁移注册集中在一个文件；现有迁移测试覆盖窄。数据库升级必须前向修复，不得清库。
6. 新页面必须独立于聊天/图片；入口在侧栏“图片”下方；旧 `AppTab.AGENT`（智能调参）行为与视觉冻结。

## 2. 决策

### D1 模型执行接入：复用同一生产 funnel，不新建执行链

```text
AgentRunOwner
  -> app McaAgentLocalModelAdapter (AgentModelPort)
  -> McaAppContainer.chatExecutionFacadeFor(existingEngine)
  -> ChatExecutionOwner / sharedRuntimeSupervisor
  -> McaInferenceService.streamChat
  -> 既有 EngineLifecycleLease / native runner
```

- Agent 不调用 `LocalApiRuntime.streamChatWithContextProvider`，不触发聊天 UI hooks，不新建 engine/supervisor。
- 每次 attempt 使用永不复用的 `agent:<runId>:<stepIndex>:<attemptOrdinal>` requestId；本地 request/session/role/context 身份使用 Agent 自己的值；V1 禁用持久 prefix/session cache。
- 先 `acquireExclusiveLifecycleLease(stopActiveGeneration = false)`（engine 自身没有超时，adapter 必须用 `withTimeout` 包出 120s 上界），再核对 run epoch/绑定/`activeExecutionProfile()`/`parameterSignatureSnapshot()`/待提交调参候选；拒绝时返回 `RESOURCE_BUSY`，不提交/回滚他人调参候选。
- `ChatExecutionFacade.streamChat` 只返回 `Flow`，不返回 identity handle；adapter 在开始收集前/后通过 `facade.snapshot(requestId)` 读取实际 `RequestIdentity` 并记入 attempt。admission 在 `body.stream` 之前且不在 try 内（owner-map §3），因此 adapter 必须立即收集，构造失败必须显式结算，不得把未收集的 Flow 存起来。
- engine lease 通过 `LocalChatExecutionContext.lifecycleLease` 透传；`includeDeviceClockContext=false`；不传 hot override/load authorization。
- 只通过 facade 的 `streamChat` 收集一次 Flow；不使用 `facade.dispatchChat` 作为执行入口。

### D2 取消：只允许 exact stop，禁止全局停止

- Agent 只调用 `facade.stopGenerationIfActive(requestId)` 或按精确 `GenerationStopToken` 停止；**绝不**调用 `facade.stopGeneration()`、`cancelAllActive()`、`client.dispatcher.cancelAll()`。
- 事实校正：`stopGenerationIfActive` 的 `true` 只表示“停止已派发/被接受”，并可能在 500ms 超时后仍返回 true（owner-map §6）；`cancelChat(requestId)` 只标记 supervisor，不触发 engine 停止。因此 Agent 需要独立的 typed stop proof（`AgentStopProofSource`，在现有 engine/worker owner 上增加窄读取/等待面），不能把 Boolean 当 native 已停。
- 四段时间分别记录：UI 反馈、取消命令送达、真实 native/worker 停止、lease 释放；停止未证实保持 `STOPPING` fence。
- 聊天 UI 的全局 stop 会通过共享 supervisor 标记所有 live attempt（含 Agent attempt，bookkeeping only）；Agent 的 run 正确性只依赖自己的 run epoch/exact requestId，不读取 supervisor 的全局取消标志。
- 云 attempt 由 AgentRunOwner 创建独立子 Job，只取消自己的 HTTP Call；HTTP 已取消不等于远端算力/计费停止。

### D3 Agent owner 作用域与 engine 注入（对方案 3.2/14 的具体化）

- 事实校正：`McaAppContainer` 并不挂在 `McaApplication` 上，它的生命周期由 `AppStartupViewModel` → `MainViewModel` 启动链持有；engine 由 `MainRuntimeAssembly` 以 `MainViewModel.viewModelScope` 的亲缘关系创建（`evidence/5e912ff9/owner-map.md` §1/§5）。因此 V1 不宣称已实现语言级 application singleton。
- V1 决策：`AgentRuntimeAssembly` 由 `MainRuntimeAssembly` 构造并挂到当前 `McaAppContainer`；它使用**独立 SupervisorJob**（不是 `viewModelScope`）作为 owner scope，以便页面切换/旋转继续运行；页面 VM 只观察。
- 进程级单例保护：构造时取得一个进程级 typed `AgentProcessLease`（`AtomicReference` + 唯一 token，不缓存 Activity/VM/Composable）。已有 live owner 时拒绝第二个初始化，返回明确错误而不是静默替换。`MainViewModel.onCleared` 先拒绝新命令、把在途 run 结算为 `INTERRUPTED`（或存在未知副作用时 `NEEDS_REVIEW`）、detach 模型绑定、释放该 lease、取消 owner scope；engine 自身的释放仍由现有 owner 负责。
- 模型能力通过**窄类型** `AgentModelRuntimeBinding`（engine、facade、progress/stop 能力）由 `MainRuntimeAssembly` 显式 attach/detach；不得使用 `Any?`、字符串 service locator 或错类型回退真实实例。
- 同一时刻最多一个活动 run、最多 5 个显式排队；`AgentRunOwner` 是 Agent run 的唯一状态推进者。
- 退出条件：若未来把 engine 迁移到真正的 application 作用域，必须另立 ADR 并补齐所有权迁移测试；在那之前 attach 只允许一次、detach 必须完成上述结算。

### D4 持久化：复用 mca.db，N→N+1，命令幂等与产物账本落表

- Room 基线 `N = 27`，实施 K05 再读实际 `@Database`；按真实 N→N+1（预期 28），schema 由 KSP 真实导出，禁止手改 `27.json`、禁止 `fallbackToDestructiveMigration`。
- 新增表：workspaces、tasks、runs、model_attempts、steps、invocations、approvals、events、artifacts、extensions、commands、grants、artifact_commits；稳定 enum 字符串、UUID 文本 id、`schemaVersion=1`。
- 命令幂等记录（commandId + 内容 hash + 原结果 + appliedRevision）与业务变化同事务；事件 sequence 与状态摘要同事务；`runs(task_id, ordinal)`、`events(run_id, sequence)`、`invocations(step_id, call_ordinal)` 等唯一约束落库。
- task.lifecycle（ACTIVE/IMPORTED_ARCHIVED/TOMBSTONED）独立于 run 执行状态；恢复扫描所有非终态，`IMPORTED_ARCHIVED` 不进入可执行查询。
- Agent 私有根目录 `files/agent/workspaces/...` 在 K06/K14 同步加入 `backup_rules.xml` 与 `data_extraction_rules.xml` 排除（cloud backup + device transfer）；凭据不进 Room。

### D5 协议：本地 PROMPTED_V1，云端仅已验证 Responses NATIVE

- 本地 MNN/GGUF 默认 `PROMPTED_V1`：专属 SYSTEM、USER 观察 JSON、ASSISTANT envelope 的完整配对 transcript；`tools/toolExchanges` 为空；只解码当前 assistant 的完整 visible 完成输出。
- 严格解码：Jackson Core 2.22.4 streaming parser（无 databind、无 lenient），重复 key/注释/单引号/尾逗号/NaN/多 root/尾随 token/深度>16/控制响应>64KiB 全部拒绝；每次 run 最多 1 次 repair。
- 云端：只有 `supportsTools=true` 且通过严格 terminal 校验的 Responses 路径可 NATIVE；OpenAI-compatible/Anthropic 在没有 `STRICT_AGENT_TERMINAL` 前只允许 prompted/NONE；EOF/length/max_tokens 不得当作成功。
- 依赖固定：`com.fasterxml.jackson.core:jackson-core:2.22.4`（Apache-2.0，零运行时依赖，jar sha256 `2d30c375…312d1a`，无影响该版本的已知 CVE；首次构建需 Central）；`kotlinx-coroutines-core:1.9.0`；不使用动态版本。
- MCP V1 固定 MCP 2025-06-18 Streamable HTTP tools-only 子集（明确 pin，不是 latest）；stdio/legacy HTTP+SSE/OAuth 不声称支持。

### D6 权限、审批与 egress

- `AgentPermissionBroker` 在 app/security：`ALLOW_FOR_TASK` 只适用于任务创建时明确授予的私有 workspace 读取；写入/外部副作用一律 ASK；审批绑定 invocationId+argsHash+schemaHash+resourceFingerprint+runEpoch+policyVersion，TTL 5 分钟、一次性 CAS 消费。
- 模型文本、工具声明、MCP annotations、Skill allowed-tools 都不是授权；DENY 生成配对 DENIED 结果；连续 2 次相同拒绝转 `PAUSED(PERMISSION_DENIED_LOOP)`。
- `LOCAL_ONLY` 由 Agent transport/egress 层强制，不靠 prompt；云端首次步骤前展示 payload 来源清单；运行中切离线递增 policyVersion、撤销未消费网络审批、取消自己的在途请求。
- 路径全部为任务根相对逻辑路径；拒绝绝对路径、盘符、NUL、`..`、符号链接逃逸、规范化前缀碰撞；SAF 只用于导入/显式导出。

### D7 内置工具与 V1 范围

- V1 内置工具严格四个：`workspace.list`、`workspace.read_text`、`workspace.search_text`、`artifact.write_text`；用户澄清只走 `kind=ask_user` 协议路径。
- `knowledge.search`、本地生图、浏览器、终端、Android 控制、子 Agent、工作流、DSH Host 均不在 V1；接口只保留真实调用者需要的扩展点，不预建空 port。
- V1 不新增 Agent HTTP/Local API 入口；现有 Local API 回归仍必测。

### D8 页面、导航与状态

- `AppTab` 新增 `AGENT_WORKSPACE`，追加在枚举末尾（避免既有 ordinal 位移）；旧 `AppTab.AGENT` 继续表示智能调参，`onOpenAgent` 语义不变。
- 新入口位于侧栏“图片”按钮下一行（`ChatHistoryDrawerScreens` 的 `AppFeatureButton` 范式），测试 tag `drawer.agent_workspace`，可访问名称“打开 Agent 工作区”。
- `MainUiState` 不增加 task/run/event/approval/model-selection 字段；`MainCoordinatorHost` 不增加 Agent 业务能力；`MainActivity` 只做 Route 装配；`ChatViewModel` 只增加一个具名导航转发。
- 新页面在 `feature:agent-workspace`：独立 Route/VM/投影；只依赖 contract + designsystem；复用 `McaTheme`，不改聊天/图片/调参布局与视觉；每个主页面单一 LazyColumn；审批默认焦点不在破坏性操作上。
- 返回顺序：sheet → 详情 → 列表 → 来处；返回/旋转不启动/取消任务；进程恢复先核账（PAUSED/NEEDS_REVIEW），不自动续跑。

### D9 前台/后台与恢复

- 优先复用现有 `specialUse` 生成 FGS 路径，增加 Agent 用途/通知必须核对 targetSdk 36 限制；不降 targetSdk，不用 dataSync 制造无限执行。
- 等待审批/资源/用户时不持 native lease；通知仅通用文本，带 runId/epoch，陈旧 action 不能停止新任务。
- 启动恢复扫描所有非终态；旧审批过期；RUNNING 外部副作用记 UNKNOWN；不自动恢复模型或网络。

### D10 工程门禁与依赖方向

- `:core:agent-contract`：平台无关 DTO/错误/能力/端口；`api` coroutines-core；禁止 Android/Compose/Room/OkHttp/app/feature 类型。
- `:core:agent-runtime`：纯 Kotlin 编排；只依赖 contract + coroutines + Jackson Core；禁止 app/engine/native/Room/UI。
- `:feature:agent-workspace`：只依赖 contract + designsystem + AndroidX/Compose；禁止 runtime/engine/native/app/chat。
- `tools/architecture/check_architecture_rules.py` 新增 Rule 9–14（平台纯度、依赖方向、workspace feature import 边界、app/agent 边界、MainUiState/Host Agent 冻结、feature 依赖边界）并纳入 agent 目录的 LOC/LF 检查；`generate_architecture_manifest.py` 登记三个新模块。
- 构建/测试统一受控入口与共享 build lock：`scripts/agent-verify.ps1` 仅串行执行指定 Gradle task，不放宽既有 `build.ps1`/`verify-code.ps1` 门禁。新增 `-AgentTests` 聚合入口由 K15 完成；在此之前不得声称 Agent 全量门禁已存在。

## 3. 被否决的替代方案

| 替代 | 否决理由 |
| --- | --- |
| Agent 直接调用 `McaInferenceService.streamChat` 或自建 supervisor | 绕过生产 owner/身份/终态，制造第二执行链（违反硬锁 5） |
| 为 Agent 单独创建 engine/native runtime | 资源竞争、双份内存与不可核账取消 |
| 复用一个全局 `stopGeneration()` 作为取消 | 会误停聊天/图片/其他 attempt |
| Agent 状态写入 `MainUiState` 或扩 `MainCoordinatorHost` | 扩大已有债务，制造第二 UI writer |
| 直接把 engine 迁到 Application 作用域 | 超出本任务授权，影响所有旧入口；需独立 ADR |
| 云端所有 provider 一律 NATIVE 工具调用 | 现有 EOF-after-Done 会伪造成功 |
| 手写/宽松 JSON 解析 | 无法满足严格协议与可维护性；Jackson Core 已验证 |
| 预建浏览器/终端/DSH 空接口 | 无生产调用者，违反“不建装饰性接口” |

## 4. 后果与风险

- **正面**：Agent 复用真实 owner/lease/engine；状态与数据独立可恢复；协议与权限可确定性测试；模块依赖可静态门禁；旧功能保持冻结。
- **代价**：engine 仍由 VM 作用域创建，Agent owner 与 engine 生命周期需要窄 attach/detach（RISK11）；K04 真实 MNN/GGUF/云验证依赖设备与模型资产（RISK14），缺证据只能 BLOCKED/NOT_RUN。
- **不得**：降级 targetSdk、清库、全局停止、伪造 PASS、把 Skills/MCP 文本导入宣称完整执行兼容。
- 回退：关闭 Agent 入口/停止新 run；保留 Agent 表与文件可导出；数据库升级后禁止指导安装旧 APK 清库，按前向修复处理。

## 5. 验证与证据

- K00 证据：`docs/proposals/agent-v1/evidence/5e912ff9/`（owner-map、capability-matrix、risk-register、dependency-verification、navigation-ui-map）。
- 本 ADR 的确定性门禁：`python tools/architecture/check_architecture_rules.py`（Rule 9–11）、`:core:agent-contract:testDebugUnitTest`、`:core:agent-runtime:testDebugUnitTest`。
- 真实设备/模型/云/DB 升级/UI 渲染证据按验收矩阵分卡积累；本文不产生任何运行 PASS。

## 6. 变更控制

- 修改 D1/D2/D3/D4 中任一所有权、取消、持久化或 engine 接入决定，必须先提交新的差异说明和 ADR 增补，不得在实现中静默放宽。
- 本 ADR 不修改硬锁、不新增本地专用规则、不改变现有聊天/图片/调参/Local API 的对外合同。

## 7. 实施记录（Lead，2026-10-19）

> **历史证据说明（2026-10-20 01:10 增补，不改写下方原始记录）**：本章是 K00–K13 完成时的
> 历史实施记录（设计基线 `5e912ff9`）。其中“3245 tests / 14/14 rules”是当时快照，不是当前
> 树结果。当前交付基线为 `7475fd6`（抓取时仓库 HEAD `af24899`；其后提交只触及 audit 文档
> 与 `scripts/measure-frames.ps1`，但工作区另有 45 个 tracked 未提交修改）；2026-10-20 01:05 架构检查
> 为 **16/16**（含 DSH Rules 15/16）；contract 19 / runtime 132 / workspace 118 是 00:17
> **分模块独立运行**，不是一次性汇总；app 全量未重跑（最新 app 记录为 00:30 过滤 DSH
> 26/1 failed），full gate 仍 **NOT_RUN/BLOCKED**。详见
> `docs/proposals/agent-chat-integration-v1/V1_1_STATUS.md`。

本轮按 K00–K13 完成源码集成与 JVM 验证，新增以下实现约束：

1. **预算时钟**：`AgentBudgetPolicy` 必须注入 `AgentMonotonicClock`，不得在策略内部读系统时钟；组合根传同一 clock。
2. **审批决策持久化**：`ConsumeApprovalTransition` 增加 `decision: AgentApprovalDecision`（默认 `ALLOW_ONCE` 兼容旧 fixture）；owner 提交时传 `command.decision`，Room applier 对 DENY 写 `DENIED` + invocation `DENIED`，对 ALLOW_ONCE 写 `CONSUMED` + invocation `RUNNING`。`AgentCommandFingerprint` 包含 decision，重放不会串结果。
3. **审批所有权**：组合根不得先经 broker resolve/consume 再交给 owner；owner 以 PENDING 为前置，在一个 journal transition 内完成 PENDING→CONSUMED 并恢复执行。重复点击看到 CONSUMED 被拒绝。
4. **Room v28**：15 个 Agent entity + `agent_command_results`，迁移字面量 `Migration(27, 28)`，KSP 生成 `28.json`；禁止手改 schema/migration，禁止 destructive fallback。
5. **启动恢复**：`MainViewModel.init` 在 IO 上调用一次幂等 `agentRuntime.reconcileStartup()`；只落 PAUSED/NEEDS_REVIEW，绝不自动恢复模型/网络。
6. **FGS**：复用现有 `McaGenerationForegroundService`，新增 `KIND_AGENT` 通用文案；`ObservingAgentJournal` 在每次成功提交 CreateRun/UpdateRun 后驱动 `AgentRunProcessGuard`（仅 RUNNING 获取租约，等待/暂停/终态释放，PAUSING/STOPPING 保留），service 把 `ACTION_STOP_RUN` 直接交给 `AgentRunStopActionRouter`，stale/different run 不触达 owner。`AgentRunNotificationController` 的主动 update 仍未接线。
7. **构建拓扑**：受控 lane 统一 `scripts/agent-verify.ps1`（加锁后清理 stale Gradle/Kotlin daemon；单次 daemon + in-process Kotlin）。这是环境隔离，不改变任何 Gradle task 或测试断言。
8. **JVM 证据**：contract 16、runtime 109、feature 69、app Agent/shell/schema 426，全 0 failure；`compileDebugKotlin` 与两个 androidTest 编译任务 PASS；architecture 14/14 PASS。
9. **K14 已实现并接线**：版本化任务包导出/导入（确定性 ZIP/manifest、hash/size 复核、zip-slip/bomb/duplicate/encrypted 拒绝）、`IMPORTED_ARCHIVED` 单事务持久化、删除先精确取消→TOMBSTONED→FK 顺序清理、软配额/低磁盘拒绝、staging GC、SAF `AgentPackageService` 与 CreateDocument/OpenDocument 启动器、页面导入/导出/删除入口。导入产物无 artifact_commits 行（v28 `invocationId` NOT NULL），作为已知取舍记录。
10. **设备验证变体**：`-PmcaDeviceTestVariant=true` 生成 `.devicetest` 隔离 APK，避免卸载既有签名应用；本机安装被 MIUI `INSTALL_FAILED_USER_RESTRICTED` 阻止，设备项保持 NOT_RUN/BLOCKED。
11. **Skills per-run catalog**：`FileAgentSkillStore`（原子 JSON、版本 CAS、损坏 fail-closed）+ 不可变文件快照 + `AgentSkillManager`；组合根在创建 runtime 时构建 enabled-skill 快照并经 `AgentToolCatalogAssembler` 与 built-ins 取交集，Skills 只能收窄不能授权。Skill 导入/管理 UI、revocation→approval invalidation 已接（见 13/14）；MCP 仍未注入 per-run catalog。
12. **全量门禁现状**：`verify-code -AgentTests -Rerun` → **3245 tests / 0 failures / 0 errors / 11 skipped，VERIFY OK**（含 DSH 前端、run-scoped MCP 接线、Skills/MCP UI、egress grant store、schema-drift callback、archived UX 后的干净重跑）。该结果仅是 code layer，不等于设备/模型/发布验收。
13. **Skill 撤销与审批失效**：`AgentApprovalStorePort.invalidatePendingForRun`（默认 no-op，Room 原子 CAS）接入 `AgentSkillRevocationPort`；disable/uninstall 时对全部非终态 run 的 PENDING approval fail-closed，不取消 run，下一步重新冻结 catalog。
14. **Skills 管理 UI / MCP registry**：feature 增加 Skills 列表/启停/卸载/ZIP+目录导入，app 提供 ZIP spool、SAF tree adapter 与 manager 适配；MCP store/registry/egress seam 与 MCP 管理 UI 完成（32 tests）；连接仍必须拿 run-scoped egress grant，不能 app-scoped 绕过审批。
15. **FGS 失败暂停**：`PauseAgentRun` 增加 `reason`（默认 USER_REQUESTED，仅 USER_REQUESTED/FOREGROUND_SERVICE_UNAVAILABLE 可命令触发）；guard 的 `LeaseUnavailable` 走 `pauseForForegroundUnavailable`，不再静默无保护继续。
16. **MCP 管理 UI 与通知停止**：MCP 服务配置页（list/add/enable/remove，无 token/无 socket）经 `AgentMcpAdminService` 接入 `AgentMcpServerStore`；Agent FGS 租约把 `(runId, epoch)` 带入通知，`buildNotification` 生成“停止”action 回到 `ACTION_STOP_RUN`，再由 router/owner 校验后取消；chat/image 租约 action 为空。
17. **run-scoped egress 持久化与 X09**：新增 `AgentEgressGrantStore`（security port）+ `RoomAgentEgressGrantStore`（复用 agent_grants，task-scope 模板/run-scope 复制、stale/expired/origin 校验、CAS revoke，11 tests）；`AgentMcpRegistry` 增加 schema-drift `AgentMcpCatalogChange` 回调（10 tests），用于把旧审批 fail-closed。composition 尚未创建 grants/连接 MCP：必须由用户为每个 run 授权。
18. **归档导入可见性**：Home 增加 Saveable “显示已导入归档”，归档任务只读（无 composer/start/continue/stop，可导出/删除），feature 114 tests。
19. **DSH 1:1 前端**：Home 改为 composer-first（品牌 mark + 探索未至之境 + 预览版、工作区/网络选择器、28dp 输入卡 + 文件/权限/模型/发送），历史与工具移入左侧 drawer；Detail 改为对话/轨迹双 tab、用户目标气泡、assistant 活动块、内联审批/问题、底部 DSH composer；VM 793 行，feature 118 tests。未做真实设备两轮渲染，视觉证据仍 NOT_RUN。
20. **run-scoped MCP 接线**：`RunScopedAgentToolCatalogProvider` 仅在 `NETWORK_ENABLED && !offlineForced` 时，对 enabled 且 `allowAgentNetwork` 的 server 在每个 run 内建 `REMOTE_MCP`/`LOCAL_SERVER` grant（PRIVATE、runId/taskId/policyVersion），再以只允许这些 canonical origin 的 guard 连接 registry；grant 拒绝/连接失败/装配冲突都回退 base catalog（fail closed）。MCP tools 全部 EXTERNAL_SIDE_EFFECT/ASK，冻结参数逐次审批。`release` 关闭 session；默认 OkHttp client 为进程共享，不按 run close 底层 client。10 provider tests + runtime release hook 5 tests。
