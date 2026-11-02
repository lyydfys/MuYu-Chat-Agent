# 原生 Agent 补齐：实施协调、基线（N00）与写集

日期：2026-10-20 建立；2026-10-21 更新
状态：实施中。本文是本次并行实施的协调事实源；能力实现完成度以源码与运行证据为准，不以本文宣称 PASS。

> **2026-10-21 进展**：证据目录与状态登记已建立（`artifacts/agent-native-thirdparty/`），N01、N02a/B06、
> N02b/B08、N02c/B07 有代码与 JVM 证据；设备层因 MIUI USB 安装限制 `BLOCKED`（见
> `artifacts/agent-native-thirdparty/ISSUES.md` 的 DEVICE-01）。逐轮记录见
> [ROUND-LOG](../../../artifacts/agent-native-thirdparty/ROUND-LOG.md)。同一 checkout 仍有另一条 DSH V2
> 线索在写入，`app` 依赖链会间歇性处于其中间态；构建/验证需按实际状态重试。

## 1. N00 基线（2026-10-20 23:5x 读取）

| 项 | 值 |
| --- | --- |
| 分支 | `codex/enterprise-refactor` |
| HEAD | `68b6eef1e67870b02bfef6afda2e625d36663da9` |
| tracked diff | 57 files，`trackedDiffSha256=4fe4285159d7f365b7bcb35710e8714a2a6fced01b11deaba31c42115d7a43a0` |
| untracked | 691 files，`untrackedManifestSha256=60a6972ce067cb37ef53530df6f4d9b866063b28275ee22f1ca870b87f87b390` |
| llama.cpp | `4ceb1719101f32637b841206c172f3f058ffc182`，20 dirty，`diffSha256=61076f0b422ec64f749193ac4667d574827283c31fb75c7adbf464bf3a833007` |
| stable-diffusion.cpp | `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0`，18 dirty，`diffSha256=dd5db85e21dc7c8905c9a5f9678d7f901b2d34561d92acd022f92618674ff912` |
| tokenizers-cpp | `acbdc5a27ae01ba74cda756f94da698d40f11dfe`，5 dirty，`diffSha256=9054faa69ad2827cd853f2dc4b62fba88dd8e1e3830f8157e629e6ce159ce65f` |
| Room | `@Database` 当前 29；新版本由整合者按迁移账本分配，禁止照抄文档“预期版本” |
| 设备 | `adb devices` 无设备；D/M/O/V 层验收统一 `NOT_RUN/BLOCKED`，禁止伪造 PASS |
| 并行会话 | 同一 checkout 另有 DSH/前端会话在改 `app/.../dsh`、`core/dsh-*`、`feature/dsh-workspace`、聊天/图片/设计系统与 `MainActivity/MainViewModel/MainRuntimeAssembly`。本工作流不得触碰这些写集。 |

基线漂移规则：并行改动出现时刷新相关文件指纹，不覆盖、不 reset 他人工作；交付报告记录自己的写集与指纹。

## 2. 能力 manifest（N00 初始，默认 UNKNOWN/NOT_RUN）

| 域 | 当前事实（源码读取） | 本方案状态 |
| --- | --- | --- |
| 工具调用 | V1 prompted 单调用、审批、ledger、4 内置工具已存在；NATIVE 标签与真实 wire 不一致（B03）；无条件化批调用 | `PARTIAL` → 目标 N03/N05/N06 后重评 |
| MCP/Skills | 管理/registry/run-scoped provider 存在；生产组合根未注入 mcpTools（B02）；Skill 构造时快照 | `PARTIAL` → N04/N07 |
| 工具包 | manifest/ZIP/预览/inert v1 存在；无可执行 v2、无隔离脚本运行时 | `NOT_STARTED` → N09–N12 |
| 记忆 | Room 29 空间/条目/分块、词法、选择授权、管理页存在；B05/B06/B07/B08 未修；无语义索引/链接/提取 | `PARTIAL` → N02/N13–N16 |
| Workflow | 文件 recipe、预检、手动创建普通任务；无图定义/节点执行/触发/恢复 | `NOT_STARTED` → N17–N22 |
| 后台执行根 | Agent Job 仍 parent 到传入 VM scope（B09） | `NOT_STARTED` → N21 |
| 验收 | 历史 V1 设备证据不适用于本候选；121 项全部从 `NOT_RUN` 起算 | `NOT_RUN` |

工具实际注册、原生协议 wire、MCP 生产接线、包执行能力必须在对应卡完成后逐项重测并更新，不能由文件存在推导可用。

## 3. 迁移账本（整合者分配；实现者可先写独立 migration provider）

| 版本 | 归属卡 | 内容 | 状态 |
| --- | --- | --- | --- |
| 30 | N13 | memory item revision/metadata/proposal/link/FTS 基础表 | 预留 |
| 31 | N19 | workflow definition/run/node attempt/checkpoint 表 | 预留 |
| 32 | N09/N12 | tool package version/install journal/resource 表 | 预留 |
| 33 | N22 | trigger inbox/slot/outbox 表 | 预留 |
| 34 | N16/N21 | memory job/egress provenance、执行根恢复元数据（按需） | 预留 |

规则：每个域把 migration 函数写在自己的文件（`...MigrationN.kt`），实体/DAO 也放自己目录；`@Database` 注解、`AgentDatabaseMigration.kt` 与 schema JSON 由整合者统一登记。升级只允许前向修复，不 destructive fallback。

## 4. 五项增量 ADR（N00 草案索引）

| ADR | 主题 | 状态 |
| --- | --- | --- |
| `docs/adr/0004-agent-tool-protocol-and-invocation-pipeline.md` | 结构化回合 + 单一 invocation pipeline | 草案 |
| `docs/adr/0005-agent-tool-package-execution.md` | 可执行 v2、隔离脚本、third-party 转译边界 | 草案 |
| `docs/adr/0006-agent-memory-v2.md` | 可审计写入、语义/链接、来源级外发 | 草案 |
| `docs/adr/0007-agent-workflow-graph-and-recovery.md` | 图版本、节点检查点、触发 inbox | 草案 |
| `docs/adr/0008-agent-cold-start-execution-root.md` | 进程级执行根迁移 | 草案 |

## 5. 并行工作流与写集

共享写集（仅整合者/Lead）：`core/agent-contract` 既有核心文件（`AgentContract.kt`、`AgentErrors.kt`、`AgentEvents.kt`、`AgentRunTypes.kt`、`AgentTaskTypes.kt`、`AgentWorkspacePorts.kt`、`AgentJournal.kt`、`AgentAttemptTypes.kt`、`AgentWorkspaceFilePort.kt`）、`core/agent-runtime/run/AgentRunOwner*.kt`、`AgentDatabaseMigration.kt`、`ChatSessionStoreRoomDatabase.kt`、`app/schemas/**`、`AndroidManifest.xml`、`settings.gradle.kts`、`gradle/libs.versions.toml`、`MainRuntimeAssembly.kt`、`MainActivity/MainViewModel`、`docs/adr/**`、`docs/architecture/**`。

| 工作流 | 卡 | 写集（互斥） | 负责人 |
| --- | --- | --- | --- |
| core-runtime | N01、N03、N04（后续 N06 协调） | `core/agent-contract` 新核心类型（创建回执/调用流水线端口）、`core/agent-runtime/{run,journal,validation}`（owner 文件除外）、`app/.../agent/data/{AgentCommand*,AgentTaskRepository,AgentRunDaos,AgentExecutionEntities,...}`、`app/.../agent/composition/**`、`app/.../presentation/Agent{Run,Task,ModelCatalog}*`、对应 test | `core-runtime` |
| memory | N02、N13–N16 | `core/agent-contract/AgentResourceScope.kt`、`app/.../agent/memory/**`、`app/.../presentation/AgentMemory*`、`feature/agent-workspace` 记忆相关投影（与 UI 协调）、对应 test | `memory` |
| model-protocol | N05、N06 | 新 `AgentModelTurn*.kt`、`core/agent-runtime/{protocol,context}`、`run/AgentPromptedLoopCoordinator.kt`、`run/AgentLoopTypes.kt`、`app/.../agent/inference/**`、对应 test | `model-protocol` |
| extensions | N07、N09–N12 | `app/.../agent/extensions/**`、新 `core/agent-script/**`、`app/.../agent/platform/script/**`、`app/.../presentation/Agent{Mcp,Skill,ToolPackage}*`、对应 test | `extensions` |
| workflow | N17–N22 | 新 `core/agent-contract/workflow/**`、新 `core/agent-runtime/workflow/**`、`app/.../agent/workflow/**`、新 trigger/scheduler 文件、对应 Room 实体/DAO/migration 文件、对应 test | `workflow` |
| tools-platform | N08 | `app/.../agent/tools/**`、新 `app/.../agent/platform/{device,http,document,terminal,...}`（script 为 extensions 所有）、对应 test | `tools-platform` |
| ui | N20、N24 UI | `feature/agent-workspace/**`（VM 创建协调除外，与 core-runtime 交接）、`app/.../presentation/AgentWorkflowAdminService.kt` 等 UI 管理服务 | `ui` |
| integration | N00、N21、N23–N25、共享写集 | 见上共享写集、`artifacts/agent-native-thirdparty/**`、`scripts/**` | Lead |

规则：一个文件同一时刻只有一个 owner；需要跨写集时先完成可独立部分，用 `send_message` 向 Lead 报告具体最小扩展，由 Lead 串行合入。禁止 `git add .`、revert、覆盖、删除他人文件；编辑前重新读取文件。

## 6. 构建与验证协议

```powershell
# 唯一允许的构建入口（内部持独占构建锁；另一会话持锁时等待）
.\scripts\agent-verify.ps1 -Tasks ':core:agent-contract:testDebugUnitTest' -TimeoutMinutes 40
.\scripts\agent-verify.ps1 -Tasks ':core:agent-runtime:compileDebugKotlin' -TimeoutMinutes 40
```

- 禁止直接 `gradlew`、`clean`、并行构建、全量 `:app:test`；模块级 compile/test 在切片结束运行一次，保留日志/测试数与退出码。
- 只做 JVM 可验证的 U/I 行为；D/M/O/V 记录 `NOT_RUN` 及缺设备/模型/服务原因。
- 新增测试必须是真实行为断言（状态、账本、事务、取消、迁移、拒绝），不能只搜索源码字符串；过滤运行后核实执行数不为 0。
- 最终一次性验证由 Lead 执行：架构检查 + 全模块 JVM 测试 + APK 编译（若工具链允许）；结果写入 `artifacts/agent-native-thirdparty/`。

## 7. 完成门槛与交接

每卡按 EXECUTION_PACK 1.2：生产调用者、旧数据兼容、失败/取消/重复/重启行为、单 writer/无平行 engine、资源 owner 可回答五问、真实检查数与退出码、NOT_RUN 清单、回退点。没有生产调用者的接口不算基础完成；fake 通过不等于生产链路通过。

交接报告模板：

```text
卡号/子切片 / 验收ID：
变更文件（写集）：
生产入口 / 唯一 writer / 资源 owner：
数据协议与兼容（迁移、upcast、旧 fixture）：
已运行命令 + 真实测试数量 + 退出码：
NOT_RUN / BLOCKED（原因）：
已知风险 / 临时 adapter 退出条件：
回退点 / 下一片：
```
