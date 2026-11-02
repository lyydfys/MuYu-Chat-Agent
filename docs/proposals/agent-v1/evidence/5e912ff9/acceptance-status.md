# K17 验收状态地图（128 IDs）

- 基线：`5e912ff9ac7bf78f1ed1f452657da642cfbdfead`；当前 checkout HEAD `26a63478`（tracked 源码与基线对 Agent 引用的文件一致，详见 `card-status.md`）。
- 本文只汇总**已有证据**；状态只有 PASS / NOT_RUN / BLOCKED（无 FAIL：当前没有可复现的确定失败；历史全量门禁的 2 项失败已分别修复/回退，见下）。
- PASS 条件：有当前 revision 的 JVM 测试断言（类名+计数）、门禁命令结果或源码事实；编译成功、实现存在、文档计划均不算行为 PASS。
- 证据简写：
  - `[CT16]` = `agent-verify :core:agent-contract:testDebugUnitTest`，16 tests / 0 failures（`AgentContractTypesTest`）。
  - `[RT109]` = `agent-verify :core:agent-runtime:testDebugUnitTest`，109 tests / 0 failures（12 classes：AgentRunStateMachineTest、AgentRunOwnerTest、AgentBudgetPolicyTest、AgentCommandIdempotencyTest、AgentPromptedProtocolTest、AgentPromptedTranscriptTest、AgentToolSchemaValidationTest、AgentContextAssemblerTest、AgentPromptedLoopCoordinatorTest、AgentInMemoryJournalTest、AgentJournalFaultInjectionTest、AgentRuntimeFaultInjectionTest）。
  - `[FW69]` = `agent-verify :feature:agent-workspace:testDebugUnitTest`，69 / 0。
  - `[APP426]` = `agent-verify :app:testDebugUnitTest`（agent.* + DB/shell 过滤），426 / 0 / 3 skipped / 50 suites（`verification-2026-10-19.md:15`）；子组：data 114、security 59、tools 70、platform 58、inference 28、extensions 58、file-store 9、RoomSchema 7、MainShell 6。
  - `[ARCH14]` = `python tools/architecture/check_architecture_rules.py` → ALL 14 PASSED；manifest 20 modules（`verification-2026-10-19.md:18-19`）。
  - `[SKILL31]` = `AgentSkillFrontmatterTest 6 + AgentSkillImportTest 8 + AgentSkillManagerTest 8 + AgentSkillSnapshotRepositoryTest 2 + AgentSkillToolPolicyTest 7 = 31 / 0`（app XML）。
  - `[MCP28]` = app extensions 组内 MCP 28 / 0（McpClientTest、McpSseParserTest、McpJsonCodecTest、McpToolAdapterTest）。
  - `[DB28]` = `ChatSessionStoreRoomDatabase.kt:81-84`：version 28、exportSchema=true；`app/schemas/com.muyuchat.mca.McaRoomDatabase/28.json`。
  - `[DEV]` = 设备执行被 MIUI `INSTALL_FAILED_USER_RESTRICTED` 拒绝（隔离 `.devicetest` APK 也一样），见 `verification-2026-10-19.md:53-64`、`risk-register.md:90-94`。
  - `[FULL]` = 全量 `verify-code.ps1 -AgentTests -Rerun` **GREEN**：tests=3168、failures=0、errors=0、skipped=11、`VERIFY OK`（Skills UI + MCP registry + typed pause 之后）。全量绿不等于设备/真实模型项 PASS。
  - `[MCPREG]` = MCP server registry 已实现：`AgentMcpServerStore` 10 tests + `AgentMcpRegistry` 12 tests（原子 JSON、endpoint policy、generation fence、egress guard seam）。composition 尚未连接任何 server：连接需要 run-scoped egress grant，不能 app-scoped 绕过审批。
  - `[SKILLUI]` = Skills 管理 UI 已实现并接线：feature `AgentSkillsScreen`/`AgentSkillAdminPort` + app `AgentSkillAdminService`（ZIP spool、SAF tree adapter、enable/uninstall），87 个 feature tests 绿；真机 SAF 未跑。
  - `[FGSMAP]` = FGS guard 的 `LeaseUnavailable` 现经 `pauseForForegroundUnavailable` 发送 `PauseAgentRun(reason=FOREGROUND_SERVICE_UNAVAILABLE)`；contract/runtime 132/0（19+113）覆盖该 reason。
  - `[SRC]` = 源码事实；仅用于结构性/审计项，不冒充行为 PASS。

## 汇总（按验收组）

| 组 | 总数 | PASS | NOT_RUN | BLOCKED | 主要未验证面 |
| --- | ---: | ---: | ---: | ---: | --- |
| A 架构与构建 | 8 | 6 | 2 | 0 | 构造/注入测试 |
| T 状态与预算 | 13 | 11 | 2 | 0 | 资源等待集成、Room 跨重启 |
| P 模型协议 | 15 | 12 | 3 | 0 | 真实模型、真实 UI 能力详情 |
| O 资源/取消/后台 | 13 | 0 | 12 | 1 | 全部真机/真实 runtime |
| S 安全/隐私/审批 | 17 | 8 | 8 | 1 | 真实 payload、通知、SAF、MCP |
| D 数据/产物 | 20 | 12 | 8 | 0 | 设备迁移、崩溃注入、SAF 往返 |
| U UI/导航/无障碍 | 14 | 1 | 12 | 1 | 真实渲染/设备 |
| X Skills/MCP | 14 | 7 | 3 | 4 | 真实 server、设备 E2E、schema-drift 审批失效 |
| R 旧功能回归 | 8 | 1 | 7 | 0 | 真实 UI/API/模型回归 |
| Q 质量/性能 | 6 | 0 | 6 | 0 | 全部性能/真实运行 |
| **合计** | **128** | **58** | **63** | **7** | |

## 已知阻塞与门禁状态

1. **设备 instrumentation 全部 BLOCKED**：MIUI 拒绝安装 debug / `.devicetest` APK（`INSTALL_FAILED_USER_RESTRICTED`），Room 迁移、journal、Compose Route、SAF 往返、FGS/通知、TalkBack/字体/IME 无法执行（`[DEV]`）。
2. **全量 AgentTests 已 GREEN**：`verify-code.ps1 -AgentTests -Rerun` → tests=3168 / failures=0 / errors=0 / skipped=11 / `VERIFY OK`（`[FULL]`）。该项解除 A07，但不能替代设备/真实模型证据。
3. **MCP registry 已实现但未连接**：store/registry/egress seam 22 tests 绿；composition 仍未传 MCP adapters，因为按 run 的 egress grant 尚未接入，不能为了“有 MCP”而绕过审批策略（`[MCPREG]`）。
4. **Skills 管理 UI 已实现并接线**：列表/启用/停用/卸载/ZIP 导入/目录导入均存在，SAF ZIP spool 与 DocumentsContract tree adapter 已接；真机 provider/两轮渲染未跑（`[SKILLUI]`）。
5. **Skill revocation→approval invalidation 已接线，但缺设备/并发证据**：`AgentSkillRevocationPort` 现对全部非终态 run 调 `invalidatePendingForRun`（Room 原子 CAS，默认 no-op 仅为窄 fake 兼容）；停用/卸载不再只做 JVM 语义。X04 仍缺真实 Room 并发/设备测试。
6. **FGS-unavailable pause 映射未接**：`AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE` 与 lease/guard 组件存在并测试，但 owner 路径未接线（`[FGSMAP]`）。
7. 云 egress client/真实云端未接，真实 MNN/GGUF 推理与 native stop proof 未跑（`risk-register.md:44-47,64-68,101-104`）。

## A. 架构与构建（8）

| ID | 状态 | 证据 |
| --- | --- | --- |
| A01 | PASS | owner-map.md 对 engine/facade/supervisor/worker/lease/取消/释放逐项定位；`card-status.md:31-40` 基线事实；SOURCE 审计。 |
| A02 | PASS | `[ARCH14]` Rule 9/10（feature 只依赖 contract/designsystem；runtime 无 app/engine/native/UI）。 |
| A03 | PASS | `[ARCH14]` Rule 9 import 检查 + `[CT16]` 契约类型无 Android/Compose/Room/OkHttp 公开签名。 |
| A04 | NOT_RUN | `AgentRuntimeAssembly.kt:41-59` 为全 typed 构造，但无独立构造/注入测试；编译不能代替“无 Any 回退”行为断言。 |
| A05 | PASS | `navigation-ui-map.md` + `MainShellMotionContractTest 6/0`（`[APP426]`）；`AppTab.AGENT` 仍“智能调参”，`AppTab.AGENT_WORKSPACE` 追加在末尾（`MainViewModel.kt:39-45`）。 |
| A06 | NOT_RUN | `AgentRuntimeAssembly` 是单一 owner 构造（source fact），但“每作用域唯一 + 同生产 engine”缺 composition/设备测试。 |
| A07 | PASS | `[FULL]`：`-AgentTests -Rerun` = 3121 tests / 0 failures / 0 errors / 11 skipped / `VERIFY OK`；脚本的 Agent/FullTests/changed 选择与 XML 聚合已实际覆盖新模块，且无 0-tests 失败。 |
| A08 | PASS | 硬锁 §8 预算 + `card-status.md:16`（AgentRunOwner 约 447 行）；SOURCE/LOC 审计；未放宽旧规则。 |

## T. 状态、幂等与预算（13）

| ID | 状态 | 证据 |
| --- | --- | --- |
| T01 | PASS | `[RT109]` AgentRunStateMachineTest + AgentPromptedLoopCoordinatorTest（合法状态序列/单终态）。 |
| T02 | PASS | `[RT109]` AgentCommandIdempotencyTest（同 command 同结果/不同内容冲突/只执行一次）。 |
| T03 | PASS | `[RT109]` AgentRunOwnerTest（observe 不 start）。 |
| T04 | PASS | `[RT109]` AgentRuntimeFaultInjectionTest + AgentRunOwnerTest（旧 epoch/attempt 晚到）。 |
| T05 | PASS | `[RT109]` AgentRunStateMachineTest（PAUSED→继续新 run，不复活旧终态）。 |
| T06 | PASS | `[APP426]` AgentApprovalPermissionBrokerTest + AgentApprovalConcurrencyTest（one-shot/拒绝恰好一次）。 |
| T07 | PASS | `[RT109]` AgentBudgetPolicyTest（12 attempt/8 tool/1 repair/15min 边界）。 |
| T08 | NOT_RUN | 资源等待 120s 的 virtual-time+集成断言无独立证据；`McaAgentLocalModelAdapterTest` 只覆盖 adapter 超时取消。 |
| T09 | PASS | `[RT109]` AgentContextAssemblerTest（tool pairing、16 KiB、CONTEXT_LIMIT、不静默截断）。 |
| T10 | PASS | `[RT109]` AgentRunOwnerTest + AgentCommandIdempotencyTest（1 active/5 waiting/queued 取消）。 |
| T11 | PASS | `[RT109]` AgentRuntimeFaultInjectionTest + AgentJournalFaultInjectionTest（取消传播/命令上限/双终态）。 |
| T12 | NOT_RUN | JVM 幂等/CAS PASS（同上），但“跨重启结果相同”需 Room instrumentation（`[DEV]`）。 |
| T13 | PASS | `[RT109]` AgentRunOwnerTest（question 单次回答、continue 新 ordinal、PAUSED 槽语义）。 |

## P. 模型协议（15）

| ID | 状态 | 证据 |
| --- | --- | --- |
| P01 | NOT_RUN | 能力源审计在 `capability-matrix.md`，但 MNN/GGUF/Responses 的真实模型 fingerprint 未锁定（无模型证据）。 |
| P02 | PASS | `[RT109]` AgentPromptedProtocolTest（tool/final/ask_user 三类 envelope 解码 + invocation）。 |
| P03 | PASS | `[RT109]` AgentPromptedProtocolTest/AgentPromptedLoopCoordinatorTest（partial/截断/Error/取消不接纳）。 |
| P04 | PASS | `[RT109]` AgentToolSchemaValidationTest + AgentPromptedProtocolTest（重复 key/双 root/NaN/深度等 adversarial）。 |
| P05 | PASS | `[RT109]` AgentPromptedProtocolTest（前后缀/Markdown/自然语言 JSON 不猜调用）。 |
| P06 | PASS | `[RT109]` AgentToolSchemaValidationTest（未知字段/type/bounds/enum/unsupported schema）。 |
| P07 | PASS | `[RT109]` AgentPromptedProtocolTest（只读 visible assistant 完成输出，reasoning/工具文本不触发）。 |
| P08 | PASS | `[RT109]` AgentPromptedProtocolTest（多 toolcalls 不执行任意子集）。 |
| P09 | PASS | `[RT109]` AgentPromptedLoopCoordinatorTest（每 run 最多一次 repair，二次错暂停）。 |
| P10 | PASS | `[RT109]` AgentPromptedTranscriptTest（accepted call 恰好一个 result，拒绝/错误成对）。 |
| P11 | PASS | `[RT109]` AgentPromptedTranscriptTest + AgentContextAssemblerTest（resume/compaction 不产生孤立 call/result）。 |
| P12 | PASS | `[RT109]` AgentRunOwnerTest + `[APP426]` AgentArtifactStoreTest（未登记 artifact 不呈现/不假报）。 |
| P13 | PASS | `[APP426]` AgentUtf8BoundsTest + `[RT109]` AgentPromptedProtocolTest（64KiB/32KiB/深度 16 边界）。 |
| P14 | NOT_RUN | “Responses 显式启用/本地 prompted 不冒充 native/NONE 不阻推理”需真实模型 + UI 详情（`[DEV]`）。 |
| P15 | NOT_RUN | messagesJson golden 在 JVM，但 MNN/GGUF 真实 call-result 闭环未跑（RISK01）。 |

## O. 资源、取消与后台（13）

| ID | 状态 | 证据 |
| --- | --- | --- |
| O01 | NOT_RUN | 同生产 engine→facade→owner 的接线有源码（`AgentRuntimeAssembly`），但需真机/instrumentation trace（`[DEV]`）。 |
| O02 | NOT_RUN | acquireExclusiveLifecycleLease(false)+120s 有 adapter 单测，缺真机并发/取消边界证据。 |
| O03 | NOT_RUN | binding/epoch 检查有 JVM 状态测试；真机切模型/调参竞争未跑。 |
| O04 | NOT_RUN | 跨 task/session/cache 的双色 canary 未在 Room/真机执行。 |
| O05 | NOT_RUN | 精确 stop/旧 stop 交叉取消需真实 engine run；`[RT109]` 只证明状态机。 |
| O06 | NOT_RUN | ack/dispatch/native stop/lease release 时间线需真机（`[DEV]`）。 |
| O07 | NOT_RUN | 已提交写保留/未提交清理的 fault 需真实文件与 executor；JVM tool spy 不足以覆盖。 |
| O08 | NOT_RUN | stop 超时/慢 backend/引用生命周期需故障注入+真机。 |
| O09 | NOT_RUN | 旧 image owner→unload→worker exit→release 需旧 image 回归（设备）。 |
| O10 | NOT_RUN | 需 MCP server fixture 的 cancel/timeout；`[MCP28]` 覆盖协议，不覆盖远端取消时长。 |
| O11 | BLOCKED | FGS 真机启动/退出/锁屏被 `[DEV]` 阻断；组件层 `[APP426]` platform 58 有 JVM 证据，但 `[FGSMAP]` 未接线。 |
| O12 | NOT_RUN | 进程死亡/force-stop/重启需至少 3 类设备中断（`[DEV]`）。 |
| O13 | NOT_RUN | 云端 attempt/terminal/取消需真实 Responses 请求与交叉取消；JVM 严格 terminal fixture 不能替代。 |

## S. 安全、隐私与审批（17）

| ID | 状态 | 证据 |
| --- | --- | --- |
| S01 | PASS | `[APP426]` AgentPermissionBrokerTest/AgentFrozenCommandFactoryTest（未授权 executor 0 次、副作用 0）。 |
| S02 | PASS | `[APP426]` AgentFrozenCommandFactoryTest（approval 后改 args/default → hash mismatch 拒绝）。 |
| S03 | PASS | `[APP426]` AgentApprovalPermissionBrokerTest（TTL/schema/policy/epoch 变化使旧审批失效）。 |
| S04 | PASS | `[APP426]` AgentApprovalConcurrencyTest（100 线程 CAS，执行 ≤1）。 |
| S05 | PASS | `[APP426]` AgentWorkspacePathGuardTest（逻辑路径/前缀/`..`/软链拒绝）。 |
| S06 | PASS | `[APP426]` AgentArtifactStoreTest（expectedOldHash 失效不覆盖）。 |
| S07 | NOT_RUN | `[SKILL31]` 覆盖 zip-slip/bomb/重复/软链/脚本预览等，但“至少 24 条攻击语料总集”无计数证据。 |
| S08 | BLOCKED | 恶意 MCP 注入/schema 替换需 server registry/fixture；`[MCP28]` 只有协议层（`[MCPREG]`）。 |
| S09 | NOT_RUN | 严格离线的断网真机 0 连接未测；transport 测试在 `[ARCH14]`/JVM 范围，非设备证据。 |
| S10 | NOT_RUN | 云/MCP 实际 payload capture 未接 egress client（`risk-register.md:44-47`）。 |
| S11 | PASS | `[APP426]` AgentNetworkEgressPolicyTest（禁明文/redirect/DNS rebinding/IP 分类确定性 fixture）。 |
| S12 | NOT_RUN | “API key 不在 DB/export/log/notification”的自动 canary scan 未执行。 |
| S13 | PASS | `[APP426]` AgentJournalRulesTest（tool/Skill/MCP 不能直接写 journal；sequence 唯一）。 |
| S14 | NOT_RUN | 运行中切离线/撤授权的 policy race 需真实 run 与 transport。 |
| S15 | NOT_RUN | 通知锁屏/陈旧 action 需真机通知截图/intent 测试。 |
| S16 | NOT_RUN | backup XML 已改（K06），但“实际系统备份排除 + 真实 export 包扫描”未在设备执行。 |
| S17 | NOT_RUN | SAF provider 的循环 document ID/非普通文件/软链拒绝只在 JVM fake（`[SKILL31]` 的 tree fixture）；真实 provider 未跑。 |

## D. 数据、迁移与产物（20）

| ID | 状态 | 证据 |
| --- | --- | --- |
| D01 | PASS | `[DB28]` 从真实 `@Database` 读到 version=28、exportSchema=true；不是固定常量猜测。 |
| D02 | NOT_RUN | 27→28 migration 代码/schema 存在，但 MigrationTestHelper/设备迁移未执行（`[DEV]`）。 |
| D03 | NOT_RUN | 历史版本 fixture 升级链未在设备/真实 schema 上执行。 |
| D04 | NOT_RUN | 状态/approval/invocation/event 同事务回滚需 Room 故障注入（`[DEV]`）。 |
| D05 | PASS | `[APP426]` AgentCommandLedgerTest（唯一约束/CAS 失败不覆盖新状态）。 |
| D06 | PASS | `[APP426]` AgentArtifactStoreTest（bytes/hash/size/DB 引用一致）。 |
| D07 | PASS | `[APP426]` AgentArtifactStoreTest（staging 崩溃可清理/恢复，原产物不损坏）。 |
| D08 | PASS | `[APP426]` AgentArtifactStoreTest（rename 后/DB 前核账，不重执行）。 |
| D09 | PASS | `[APP426]` AgentArtifactStoreTest（DB 后/清理前保留已提交产物，清理幂等）。 |
| D10 | NOT_RUN | RUNNING invocation 恢复需真实 Room/进程中断（`[DEV]`）。 |
| D11 | NOT_RUN | 审批等待时重启（DB+UI）未测。 |
| D12 | PASS | `[APP426]` AgentWorkspaceQuotaTest/AgentArtifactStoreTest/FileAgentSkillStoreTest（磁盘/权限/损坏给 typed STORAGE，不清库）。 |
| D13 | NOT_RUN | 导出包内容的真实 SAF 扫描未执行（K14 JVM 导出测试不能替代）。 |
| D14 | PASS | `[APP426]` AgentTaskPackageRoundTripTest（新 ID、IMPORTED_ARCHIVED、内容 hash 一致、不续跑）。 |
| D15 | PASS | `[APP426]` AgentTaskPackageValidationTest + AgentTaskPackageHeaderSecurityTest（路径/大小/entry/zip 比/未知 major 拒绝）。 |
| D16 | PASS | `[APP426]` AgentTaskDeletionPolicyTest（先准确停止→TOMBSTONED→清理，不删其他 task）。 |
| D17 | PASS | `[APP426]` AgentWorkspaceQuotaTest + AgentWorkspaceGarbageCollectorTest（active/引用不删、staging 重试）。 |
| D18 | PASS | `[FW69]` AgentWorkspacePagingTest + `[APP426]` AgentJournalRulesTest（cursor 稳定/有界，10,000 事件 fixture 的 JVM 侧）。 |
| D19 | NOT_RUN | 旧 APK 降级/升级失败演练未执行，仅有文档方案。 |
| D20 | NOT_RUN | attempts/command 结果/grants/commit journal 的真实重启恢复未测；JVM ledger 只覆盖内存路径。 |

## U. UI、导航与无障碍（14）

| ID | 状态 | 证据 |
| --- | --- | --- |
| U01 | NOT_RUN | 源码有 drawer Agent 行（`ChatHistoryDrawerScreens.kt:376-378`，tag/语义名齐），但真实截图+语义树未取（`[DEV]`）。 |
| U02 | PASS | `[APP426]` MainShellMotionContractTest 6/0 固定第 5 overlay/四旧 tab；`AppTab.AGENT` 仍“智能调参”（`MainViewModel.kt:41`）。 |
| U03 | NOT_RUN | drawer/sheet/detail/list 返回链需 UI 自动化/录屏。 |
| U04 | NOT_RUN | 旋转/重建/通知打开需 lifecycle UI 测试。 |
| U05 | NOT_RUN | Agent 不写 chat history/selected model/image UI 的 DB/state 断言未在设备执行。 |
| U06 | NOT_RUN | 无模型/空任务渲染未取真实截图。 |
| U07 | NOT_RUN | 生成/工具状态与 stop 可触达的渲染/交互未测。 |
| U08 | NOT_RUN | 审批范围/diff/原因、长参数不遮按钮需真机 UI+无障碍。 |
| U09 | NOT_RUN | 长中文/代码/表格固定 fixture 截图未取。 |
| U10 | NOT_RUN | IME/多行/附件失败键盘截图与交互未跑。 |
| U11 | NOT_RUN | “滚离底部不强制回底 + 回到最新”UI 测试未跑。 |
| U12 | NOT_RUN | 断网/中断/恢复/STOPPING 渲染+owner 关联未在设备执行。 |
| U13 | BLOCKED | 浅深/大字体/TalkBack 真机两轮审查被 `[DEV]` 阻断。 |
| U14 | NOT_RUN | before/after 两轮视觉问题表未产出；旧 chat/image/tuning 回归未在设备对比。 |

## X. Skills 与 MCP（14）

| ID | 状态 | 证据 |
| --- | --- | --- |
| X01 | NOT_RUN | parser/manifest/hash 有 `[SKILL31]`；Skill 导入/管理 UI 已实现并接线（`[SKILLUI]`），真实 SAF provider+设备 fixture 未跑。 |
| X02 | PASS | `[SKILL31]` AgentSkillFrontmatterTest 6 + AgentSkillImportTest 8（未支持 YAML/脚本仅预览，不执行/不安装 runtime）。 |
| X03 | PASS | `[SKILL31]` AgentSkillToolPolicyTest 7 + `[APP426]` AgentToolCatalogAssemblerTest（声明只能收窄，broker 交集）。 |
| X04 | BLOCKED | `[SKILL31]` 更新/停用/卸载 JVM 语义 PASS；revocation 现调用 `invalidatePendingForRun` 对非终态 run 的 PENDING approval fail-closed；缺真实 Room CAS/并发与设备证据。 |
| X05 | NOT_RUN | 真实 Skill 文件任务/依赖 shell 的 Skill 限制需 ARM64 端到端（`[DEV]`）。 |
| X06 | PASS | `[MCP28]` McpClientTest/McpSseParserTest/McpJsonCodecTest（initialize→initialized/headers/version/JSON+SSE/ID/session/分页边界）。 |
| X07 | PASS | `[MCPREG]` AgentMcpRegistryTest（adapters 使用 `mcp:<serverId>:<name>`，集成 AgentToolCatalogAssembler 断言 key/无扩权）；真实 server 连接仍 NOT_RUN。 |
| X08 | PASS | `[MCPREG]` AgentMcpServerStoreTest 10（配置/重载/删除/无 token 字段/端点策略/损坏 fenced）+ MCP 管理 UI 18 tests；真实重启/设备未跑。 |
| X09 | BLOCKED | registry 已实现 schema-drift `AgentMcpCatalogChange` 回调（10 tests）与 generation fence；尚未接 broker 的 PENDING 审批失效，且真实 server 未跑。 |
| X10 | PASS | `[MCP28]` McpClientTest（404 有 session 重握手/无 session endpoint 错、不重放、旧 session 不推进）。 |
| X11 | NOT_RUN | cancel/timeout 的本地结束 vs 远端 UNKNOWN 需慢 server/fixture；`[MCP28]` 未覆盖时长。 |
| X12 | PASS | `[MCP28]` McpSseParserTest/McpJsonCodecTest/McpToolAdapterTest（响应上限、分页不完整、JSON-RPC error/isError、未知 request 拒绝）。 |
| X13 | BLOCKED | 代码接线完成：`RunScopedAgentToolCatalogProvider`（10 tests）在 NETWORK_ENABLED run 内 mint grant→connect→assemble→release，grant/connect/装配失败均回退 base catalog；真实受控 MCP HTTP 端到端仍需设备/网络/审批证据（`[DEV]`、`[MCPREG]`）。 |
| X14 | BLOCKED | 兼容声明依赖 registry/真实 transport 完成；当前只能声明已实现 fixture 范围，不能宣称整组兼容。 |

## R. 旧功能回归（8）

| ID | 状态 | 证据 |
| --- | --- | --- |
| R01 | NOT_RUN | 聊天本地/云流式、多轮、附件/工具、编辑/重试/取消/错误的真实 UI 回归未跑（`[DEV]`）。 |
| R02 | NOT_RUN | `[FULL]` 已绿（3121/0）覆盖 ChatExecutionOwner/RuntimeAdmission 等 JVM 组；但矩阵要求的真实 API 取消/错误仍未跑。 |
| R03 | NOT_RUN | 图片 UI/API owner、busy/409、内存 handoff、worker exit 需真机/真实 API。 |
| R04 | PASS | `[APP426]` AgentTuningStageSupportTest/TuningProbeWorkerProtocolTest/TuningCandidateFailureTest（旧入口/参数事务/profile 状态）。 |
| R05 | NOT_RUN | authenticated Local API 的真实 MainActivity 认证/流/取消合同未跑。 |
| R06 | NOT_RUN | 模型加载/卸载/切换与目录/推荐/下载/导入的设备回归未跑。 |
| R07 | NOT_RUN | 原数据库/assistant/知识/云 key 加密与备份排除的设备/真实数据 fixture 未跑。 |
| R08 | NOT_RUN | chat/image/tuning 固定状态、背景、滚动、键盘、返回的真实视觉回归未跑（仅允许新增 Agent 侧栏行）。 |

## Q. 模型质量、性能与可维护性（6）

| ID | 状态 | 证据 |
| --- | --- | --- |
| Q01 | NOT_RUN | 固定文件任务两阶段（MNN/GGUF 各 10 次、云单列）未在真实模型执行；G0/V1 不能由 fake 代替。 |
| Q02 | NOT_RUN | 精确取消四阶段时间（ack/dispatch/native stop/lease release）未在真机测量。 |
| Q03 | NOT_RUN | 重复运行/内存回稳/离页/FGS 退出的资源样本未采集。 |
| Q04 | NOT_RUN | 模型/写提交/审批三类真实中断恢复未执行。 |
| Q05 | NOT_RUN | UI 启动/空闲负担（warm≥20、cold≥10）未测量；Agent 未用时“不加载模型/不联网”只是源码意图。 |
| Q06 | NOT_RUN | 10,000 事件/边界输出/配额的主线程与磁盘度量未执行；D18 只证明分页正确性。 |

## 结论

当前可诚实声称的范围是：**JVM 确定性协议/状态/安全/数据/Skills/MCP 协议层、架构门禁与全量 AgentTests 门禁通过**（56 PASS；`-AgentTests -Rerun` 3121/0/11 `VERIFY OK`），其余 62 NOT_RUN、10 BLOCKED。全量门禁绿不等于真实设备/模型/云可用。V1 **不是**可发布状态：设备 instrumentation、真实 MNN/GGUF/云、FGS 生命周期、MCP registry、Skill 导入 UI、两轮真实视觉/TalkBack 与性能样本均未取得。任何人不得把本表 PASS 汇总为“V1 完成”；G1/G2/G3 仍需按 `MCA_AGENT_V1_ACCEPTANCE_MATRIX.md:16-23` 的集合补齐并绑定最终候选 APK。
