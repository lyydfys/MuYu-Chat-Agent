# third-party Agent 能力地图（third-party Feature Map）

- 文档类型：**只读源码能力盘点**。它是 MCA Agent 后续能力的参照材料，**不是实现计划、不是验收报告、不宣称 parity**。
- 分析日期：2026-10-19（Asia/Shanghai）。
- third-party 参照：只读克隆 `C:\Users\example\AppData\Local\Temp\thirdparty-src`，HEAD `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`（2026-09-18T10:30:36+08:00，`chore(release): sync dev to main for 1.12.2`），工作区干净、**未构建、未安装、未修改**。仓库 `LICENSE` 为 GNU LGPL-3.0（`LICENSE:1-3`），因此只作为**参考实现**，不复制代码、不引入其模块。
- MCA 基线：`E:\MAC`，HEAD `98eb8e4`（2026-10-19T19:03:19+08:00），工作区含未提交改动与未跟踪目录（数量随并行任务变化；本次**未触碰、未回滚、未 stash**）；`docs/proposals/agent-v1/` 整体为未跟踪目录。
- 本次执行：仅静态阅读源码/文档 + 只读列目录；**未**运行 Gradle、`verify-code.ps1`、`agent-verify.ps1`、`adb`、任何测试、APK、模拟器、真机、模型推理、MCP server 或 third-party 构建。
- 阅读范围：third-party `app/src/main/java/com/ai/assistance/thirdparty/{api,core,data,plugins,services,integrations,ui}` 中与 Agent/工具/记忆/工作流/MCP/权限/会话/插件直接相关的文件，以及 `app/build.gradle.kts`、`settings.gradle.kts`、`quickjs/`、`docs/doc-src/**`、`examples/**`。**不是** 1,300+ Kotlin 文件的逐行 100% 覆盖；结论以本文引用的 `path:line` 为界。

> **English summary.** This is a source-level (static) inventory of third-party's agent stack against the current MCA checkout. Nothing was built or run: every runtime/device/model conclusion is `NOT_RUN`. third-party is stronger than MCA in breadth — chat-native agent loop, an installable ToolPkg/QuickJS extension runtime, workflows with triggers, bidirectional MCP, semantic memory, 39 model-provider branches, and a 5-level device-permission ladder (standard/accessibility/debugger/admin/root + Shizuku). MCA is stronger in durable task semantics — task→run→attempt journal, frozen commands, one-shot approvals, egress grants, quotas, task-package export. Gaps and MCA priorities are ranked per capability in §2–§12; the Top 8 adoption order is §15. third-party is LGPL-3.0 and reference-only.

> 结论先行：third-party 的 Agent 不是“聊天里外挂一个循环”，而是**聊天即 Agent**——工具调用、工具结果、回合重开、记忆写入都在同一条消息流内完成；扩展性来自 **ToolPkg（QuickJS 包）** 这一层；自动化来自**工作流 + 触发器**；设备能力来自**五级权限阶梯**。MCA 已有更严谨的任务/审批/egress 内核，但**能力面窄**：4 个内置工具、无 JS 运行时、无工作流、无语义记忆、无设备控制。差距是“广度 + 运行时”，不是“安全”。

---

## 0. 证据等级、判定词与优先级

| 标记 | 含义 | 可信度 |
| --- | --- | --- |
| `[OPR-SRC]` | third-party 只读克隆中的源码事实，给出 `path:line`（除非另注，路径相对 `app/src/main/java/com/ai/assistance/thirdparty/`） | 高（静态） |
| `[OPR-DOC]` | third-party 仓库内文档（`docs/doc-src/**`、`README*.md`）；可能与源码漂移，冲突时以源码为准 | 中 |
| `[MCA-SRC]` | 当前 `E:\MAC` checkout 的源码事实，给出 `path:line` | 高（静态） |
| `[MCA-DOC]` | 既有 MCA 方案/交接文档的声明；可能与源码漂移 | 中 |
| `[UNKNOWN]` | 只读材料无法判定；不推测、不补全 | 低 |

判定词（只回答“机制是否存在/是否同构”，不代表行为已验证）：

- `EQUIVALENT`：机制结构、状态归属、边界基本同构。
- `PARTIAL`：有机制，但缺决定语义的关键部分。
- `MISSING`：MCA 源码中没有对应机制。
- `DIFFERENT`：两侧都成立，但模型不同，不能互称等价。
- `UNKNOWN`：至少一侧证据不足。

MCA parity 优先级（本文件唯一排序口径）：

| 优先级 | 含义 |
| --- | --- |
| `P0` | 不做则 MCA Agent 的产品闭环不成立（用户可见的主路径缺口） |
| `P1` | 下一个里程碑应补齐；有清晰复用点，不破坏既有 owner/审批模型 |
| `P2` | 有明确用户价值，但成本/风险高，须单独立项与门禁 |
| `P3` | 仅作参考，不建议本轮或近期采纳（合规/安全/维护成本不划算） |

**运行时声明（全文有效）**：third-party 的任何行为——工具执行、QuickJS 沙箱、工作流触发、MCP 连接、记忆检索、Root/Shizuku 授权——在本任务中一律 `NOT_RUN`。本文只证明“源码里存在该机制及其形态”，不证明“它在真机上按描述工作”。MCA 侧同样只做静态对照，未运行其测试或设备链路。

---

## 1. 执行摘要

1. **形态差异**：third-party 是“聊天 + 工具 + 扩展 + 自动化 + 设备控制”一体化的通用 Agent 平台；MCA 是“聊天/图片 + 独立 Agent 任务工作区”的双入口产品。third-party 的 Agent 循环直接长在聊天流里（`EnhancedAIService.sendMessage`），MCA 的 Agent 循环在 `core/agent-runtime`，与聊天生成是两套链路。
2. **能力面差异**：third-party 静态可见约 **180 个默认工具注册项**、**39 个模型提供方分支**、**十余类 Hook + 4 类 UI 扩展点**、**6 种工作流触发器**、**双向 MCP**、**语义记忆（远端 embedding + HNSW）**、**五级 Android 权限（含 Root/Shizuku）**。MCA 当前是 **4 个内置工具 + MCP 客户端 + Skills**，无 JS/包运行时、无工作流、无语义记忆、无设备控制。
3. **真正的差距不在安全**：MCA 的“任务→run→attempt 日志 + 冻结命令 + 一次性审批 + egress grant + 配额 + 任务包导出”在可核验范围内比 third-party 的“全局/单工具三档权限 + 运行中弹窗”更细。**差距在“Agent 能接触到的世界有多宽”**：工具生态、扩展运行时、自动化、记忆、设备通道。

### 1.1 能力总表（仅结构判定）

| # | 能力域 | third-party 证据强度 | MCA 当前对应 | 判定 | MCA parity 优先级 |
| --- | --- | --- | --- | --- | --- |
| 1 | Chat agent loop | 强 | 有独立 Agent loop；聊天无工具循环 | `PARTIAL` | **P0** |
| 2 | Tool package system | 强 | 无包运行时（Skills/MCP 属不同层） | `MISSING` | **P1** |
| 3 | JS / QuickJS tools | 强 | 无 JS 运行时 | `MISSING` | **P2**（战略项） |
| 4 | Automation / workflows | 强 | 无 | `MISSING` | **P2** |
| 5 | MCP | 强 | Streamable HTTP 客户端（仅出站） | `PARTIAL` | **P1** |
| 6 | Memory / library | 强 | 角色级长期记忆，无检索/组织/跨任务 | `PARTIAL` | **P1** |
| 7 | Model providers | 强（39 个预设） | 3 种 API 格式 + 本地 MNN/GGUF | `PARTIAL` | **P2** |
| 8 | Permissions / root / Shizuku | 强 | 审批 + egress 更强；无设备执行层 | `PARTIAL` | **P2**（A11y）/ **P3**（Root/Shizuku） |
| 9 | UI chat/agent surfaces | 强 | 聊天 UI 完整 + 独立 Agent 工作区；无悬浮/插件 UI | `PARTIAL` | **P1**（整合）/ **P2**（悬浮） |
| 10 | Task / session model | 中 | 任务/run/journal 更强；聊天会话分支弱 | `DIFFERENT` | **P2** |
| 11 | Plugins / hooks | 强 | 无插件 host（仅 Skills/MCP 扩展点） | `MISSING` | **P3** |
| 12 | 周边（语音/虚拟屏/终端/浏览器/A2A） | 强 | 部分具备或与 Agent 无关 | `MISSING` / `PARTIAL` | **P3** |

> 这张表不是评分，也不是完成度；只回答“机制是否存在、是否同构”。

---

## 2. Chat agent loop（聊天 Agent 循环）

### 2.1 它做什么

- 一次用户发送 = 一个 `Stream<String>`，循环内完成：准备历史/系统提示 → 取可用工具 → 调模型 → 流式收集 → 检测工具调用 → 执行工具 → 写回工具结果 → **重开一轮**继续调模型，直到无工具调用或命中 token 阈值/取消。
- 工具协议内部统一为 XML：assistant 输出 `<tool name="..."><param ...>`，结果写回 `<tool_result name="..." status="...">`；`enableToolCall=true` 只是“XML ↔ Provider 原生 tool_calls”的**对外协议转换开关**，上层始终看到 XML。`[OPR-DOC] docs/doc-src/feature-protocol/toolcall_xml_conversion.md:5-7,35-42`
- 对模型输出做健壮性修复：纯思考输出（`removeThinkingContent` 后为空）会注入告警继续生成；未闭合/截断的工具调用会被修复或整轮作废；工具 XML 通过流式增强检测。
- 工具结果按**原始调用顺序**聚合输出；只读工具走并行白名单，其余串行。`[OPR-SRC] api/chat/enhance/ToolExecutionManager.kt:621-660`

### 2.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 聊天入口（对外） | `api/chat/AIMessageManager.kt:346`（`sendMessage`）、`:536`（`calculateStableContextWindow`）、`:1281`（`shouldGenerateSummary`） |
| 循环与流编排 | `api/chat/EnhancedAIService.kt:903`（`sendMessage`）、`:1712`（`processStreamCompletion`）、`:2072`（`handleToolInvocation`）、`:2192`（`processToolResults`）、`:2829`（`cancelConversation`） |
| 工具检测/执行 | `api/chat/enhance/ToolExecutionManager.kt:306`（`extractToolInvocations`）、`:504`（`executeInvocations`）、`:429`（`checkToolPermission`） |
| 历史/系统提示 | `api/chat/enhance/ConversationService.kt:447`（`prepareConversationHistory`）、`:678`（`normalizeConversationHistoryForModel`） |
| 回合（多轮气泡） | `api/chat/enhance/ConversationRoundManager.kt:58`（`startNewRound`） |
| 工具 schema/提示词 | `core/config/SystemToolPrompts.kt`、`SystemToolPromptsInternal.kt` |
| 工具模型 | `data/model/AITool.kt:12`（`AITool`）、`:20`（`ToolInvocation`）、`:29`（`ToolResult`） |

### 2.3 循环语义要点

- 递归式推进：`processStreamCompletion` → `handleToolInvocation` → `processToolResults` → 再次 `serviceForFunction.sendMessage` → `processStreamCompletion`。`[OPR-SRC] api/chat/EnhancedAIService.kt:1950-1982,2337-2482`
- 取消按 `MessageExecutionContext` 隔离：`invalidateExecutionContext` + `cancelConversation` + `toolProcessingScope` 取消。`[OPR-SRC] api/chat/EnhancedAIService.kt:1249-1251,2829-2881`
- 未在本次阅读范围内发现与 MCA 等价的显式“模型尝试数/工具调用数/修复次数”预算；循环靠 token 阈值、用户取消与异常收敛。`[OPR-SRC] api/chat/EnhancedAIService.kt:2309-2323`

### 2.4 MCA 对应

- `core/agent-runtime/.../run/AgentPromptedLoopCoordinator.kt:57`：确定性 PROMPTED_V1 循环，单 run 串行、每响应最多 1 个工具、显式预算/暂停/终态。
- `core/agent-runtime/.../run/AgentRunOwner.kt`、`AgentRunStateMachine.kt`、`AgentBudgetPolicy.kt`：唯一 run 状态写入者 + 预算。
- `app/.../agent/composition/AgentRuntimeAssembly.kt:55`：装配 loop/owner/port。
- 聊天侧：`feature/chat/.../ChatViewModel.kt` + `app/.../ChatExecutionFacade`（无工具循环）。

### 2.5 差距

- **聊天不承载 Agent**：MCA 的聊天生成与 Agent 任务工作区是两套入口/两套状态；third-party 的聊天就是 Agent 的输入输出面。
- **多工具/并行缺失**：MCA 每轮 1 个工具、串行；third-party 单轮可含多个调用，并对只读工具并行。
- **协议健壮性**：MCA 有严格 JSON 解码/修复预算；third-party 有 XML 截断修复、纯思考防护、Gemini thought-signature 清洗等“脏输出生存”逻辑。
- **历史写回**：third-party 把 `TOOL_RESULT` 作为一等 turn 写回并立即用于下一轮；MCA 走自有 transcript 序列化（更严格，但与聊天 transcript 不共享）。

### 2.6 优先级与采纳建议

**P0**。MCA 应先把 Agent run 投影进聊天（同一会话、同一 timeline），再谈工具广度；否则“聊天里能干活”这一主路径始终弱于 third-party。

---

## 3. Tool package system（工具包系统）

### 3.1 它做什么

- 可安装的 **ToolPkg**：ZIP/目录 + manifest，提供工具、子包、资源、WASM 模块、UI 模块/路由/导航入口、工作流模板、工作区模板；可声明对其它包的 `requires` 与版本范围，按依赖排序加载。
- 启用/停用、加载顺序、运行态监控、调试安装（ADB/广播）、市场来源标注；工具首次被调用时可按 `packName:toolName` 自动激活包。
- 工具解析支持 `packName:toolName` 命名空间；执行前会自动 `usePackage` 激活。

### 3.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 包模型/执行器 | `core/tools/ToolPackage.kt:301`（`ToolPackage`）、`:340`（`ToolPackageState`）、`:352`（`PackageTool`）、`:374`（`PackageToolExecutor`） |
| 包管理（安装/启用/加载） | `core/tools/packTool/PackageManager.kt:62`、`:121`（子包）、`:160`（WASM）、`:202/215/228`（UI/路由/导航）、`:396`（插件顺序）、`:453`（执行引擎） |
| manifest 解析 | `core/tools/packTool/ToolPkgParser.kt:201-221`（`schema_version/toolpkg_id/version/api_version/requires/main/display_name/logo/author/enabled_by_default/subpackages/resources/wasm_modules/workflow_templates/workspace_templates`） |
| 生命周期 | `ToolPkgManager.kt`、`ToolPkgLoadOrder.kt`、`ToolPkgRuntimeMonitor.kt`、`ToolPkgApiVersion.kt`、`ToolPkgMarketOrigin.kt` |
| 调试/安装入口 | `ToolPkgDebugInstallReceiver.kt`、`PackageDebugRefreshReceiver.kt`、`PackageManagerToolPkgFacade.kt` |
| 注册/解析兜底 | `core/tools/AIToolHandler.kt:303-359`（`getToolExecutorOrActivate` 自动激活包） |

### 3.3 与 Skill 的边界（third-party 自身）

- Skill（`core/tools/skill/SkillManager.kt`、`SkillPackage.kt`）是“提示词/能力说明书”层；ToolPkg 是“可执行扩展”层；MCP 是“外部 server”层。三者互不替代。`[OPR-DOC] README.zh-CN.md:115,124-126`

### 3.4 MCA 对应

- 无对应：MCA 的扩展面只有 `AgentSkillManager`（`app/.../agent/extensions/skills/AgentSkillManager.kt:16-104`，SKILL.md + 资源，非可执行工具包）与 MCP。
- 内置工具是硬编码目录：`app/.../agent/tools/AgentV1ToolSpecs.kt:19-59`（4 个 key）、`AgentToolCatalog.kt:17,56`（`builtInAgentToolCatalog`）。

### 3.5 差距

- 没有“可安装、可启用、可声明依赖、可版本化”的工具包概念；工具集扩容只能改代码。
- 没有包级资源/子包/UI 模块/模板；没有加载顺序与运行态监控。
- 没有从“包”到“工具 schema/执行器/权限描述”的统一装配层。

### 3.6 优先级与采纳建议

**P1**。先做**无 JS 的声明式工具包**（manifest + 工具定义 + 资源 + 依赖 + 启停 + 市场/导入），把“4 个硬编码工具”变成“可扩展目录”；JS 执行器作为后续子阶段（见 §4）。

---

## 4. JS / QuickJS tools（脚本运行时）

### 4.1 它做什么

- 应用内嵌 **QuickJS（JNI）** 运行时（`app/build.gradle.kts:587` 依赖 `:quickjs`），Kotlin 侧 `JsEngine` 负责初始化、执行、超时/中断、执行会话与回调泵。
- ToolPkg 的 `main` 脚本在 QuickJS 中注册 `ToolPkg.*`、`Tools.*`、`Java`/`Kotlin` 桥、Compose DSL，向宿主注册工具、Hook、UI 模块、路由；脚本可调用宿主工具、读写包资源、调用 WASM 模块（AssemblyScript ABI）、发起 IPC。
- 内置库：`Tools`（files/system/chat/memory/workflow/browser/…）、`ToolPkg` 注册 API、CryptoJS、Jimp、UINode、Compose DSL。`[OPR-SRC] core/tools/javascript/JsLibraries.kt:11`（bootstrap 模块清单）
- 超时：脚本工具调用超时 1800s，`QuickJS` 可被宿主中断。`[OPR-SRC] core/tools/javascript/JsTimeoutConfig.kt:3-7`

### 4.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| JS 引擎 | `core/tools/javascript/JsEngine.kt:49`、`:178`（`ensureQuickJs`）、`:226`（`interruptQuickJs`）、`:272`（`launchQuickJsEvaluation`）、`:788`（`executeScriptFunction`）、`:1036`（`executeScriptCode`）、`:1307`（`invokeToolPkgIpc`）、`:1500`（取消）、`:1720`（WASM）、`:1745`（异步 IPC） |
| 工具适配 | `core/tools/javascript/JsToolManager.kt:18,319,351` |
| 桥/兼容层 | `JsJavaBridge.kt`、`JsJavaBridgeDelegates.kt`、`JsNativeInterfaceDelegates.kt`、`JsExternalJavaCodeLoader.kt` |
| 执行协议/追踪 | `JsExecutionResultProtocol.kt`、`JsExecutionTrace.kt`、`JsExecutionScriptBuilder.kt` |
| UI DSL | `JsComposeDslBridge.kt`、`JsComposeDslRuntimeScript.kt`、`core/tools/packTool/ToolPkgComposeDslParser.kt` |
| 原生模块 | `quickjs/src/main/cpp/quickjs_jni.cpp`、`ThirdPartyQuickJsEngine.kt`、`QuickJsNativeRuntime.kt`、`QuickJsNativeHostDispatcher.kt` |
| 脚本/示例契约 | `examples/**`（约 70+ JS/TS 示例）、`examples/types/*.d.ts`、`docs/doc-src/package-dev/*.md` |

### 4.3 MCA 对应

- 无。本任务扫描的 1,442 个 MCA Kotlin 文件中 `QuickJS`/`quickjs` 命中 0；无脚本宿主、无 JS 桥、无 WASM。
- 唯一“可执行扩展”是 MCP 远端工具（`McpToolAdapter.kt`）与 Skills（纯文本）。

### 4.4 差距

- 扩展能力相差一个数量级：third-party 的生态（工具包/UI/主题/Hook/模型提供方）都由 JS 层长出；MCA 只有编译期硬编码。
- 成本/风险同样高：原生库、ABI、沙箱逃逸面、1800s 长超时、Java 反射桥；MCA 现有审批/冻结命令模型**不能直接套用**到“脚本内任意调用”。

### 4.5 优先级与采纳建议

**P2（战略项）**。若做，必须：独立进程/线程 + 能力白名单桥 + 每次副作用走现有 approval/egress；**不允许**脚本绕过 `AgentToolPort` 直接触达 engine/DB。先以“无 UI、无 Java 反射”的最小 ToolPkg 子集验证。

---

## 5. Automation / workflows（自动化与工作流）

### 5.1 它做什么

- 可视化节点图：`trigger` / `execute` / `condition` / `logic` / `extract`；执行节点可调任意已注册工具，也可直接执行 JS；参数支持静态值或节点引用；连接支持 `on_success/on_error/true/false/正则`。
- 触发器：`manual / schedule / tasker / intent / speech / app_open`（冷启动）。调度支持一次性、间隔、cron、每日；用 WorkManager 持久化。`[OPR-SRC] data/model/Workflow.kt:61`、`core/workflow/WorkflowScheduler.kt:54-189`
- 执行：依赖图构建 + 环检测 + 节点状态机（Pending/Running/Success/Skipped/Failed）+ 执行日志（`WorkflowExecutionRecord`）。
- 与 Agent/工具/JS 同源：工作流节点直接复用工具注册表；ToolPkg 可携带工作流模板。

### 5.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 模型 | `data/model/Workflow.kt:11`（`Workflow`）、`:55`（`TriggerNode`）、`:70`（`ExecuteNode`，含 `jsCode`）、`:96`（`ConditionNode`）、`:114`（`LogicNode`）、`:134`（`ExtractNode`） |
| 执行器 | `core/workflow/WorkflowExecutor.kt:79`、`:527`（`executeWorkflow`）、`:726`（`buildDependencyGraph`）、`:760`（`detectCycle`） |
| 调度 | `WorkflowScheduler.kt:22`、`:87/134/189/258`（interval/one-time/cron/delay）、`:290`（取消）、`:349`（cron 计算）；`WorkflowWorker.kt`、`WorkflowSchedulerInitializer.kt` |
| 持久化/日志 | `data/repository/WorkflowRepository.kt`、`data/model/WorkflowExecutionLog.kt` |
| UI | `ui/features/workflow/screens/WorkflowListScreen.kt`、`WorkflowDetailScreen.kt`、`viewmodel/WorkflowViewModel.kt` |
| 外部触发 | `integrations/tasker/WorkflowTaskerReceiver.kt`、`AIAgentTasker.kt`、`plugins/workflow/WorkflowLifecyclePlugin.kt` |
| 脚本契约 | `Tools.Workflow.*`（`docs/doc-src/package-dev/workflow.md:31-46,99-120`） |

### 5.3 MCA 对应

- 无。本任务扫描的 1,442 个 MCA Kotlin 文件中 `WorkflowScheduler`/`CronExpression` 命中 0；只有 Agent 的**手动任务**（`AgentTaskRepository`）与聊天生成。
- 最接近的替代：Local API 可被外部脚本轮询/触发（`api/local/...`），但没有调度器、触发器、节点图。

### 5.4 差距

- 缺“无人值守”能力：定时/事件触发、条件分支、失败重试、执行历史。
- 缺“把 Agent 产物编排成可重复流程”的载体（third-party 的 workflow template 还能随 ToolPkg 分发）。
- 工作流与 Agent 的边界：third-party 用工作流编排**工具**；MCA 更应把工作流编排成“Agent 任务模板”，复用现有 run 预算/审批，而不是新造第二套执行器。

### 5.5 优先级与采纳建议

**P2**。先做“任务模板 + 定时触发（WorkManager）”最小闭环，节点只允许调用 `AgentToolPort` 内的工具；不引入任意 JS 节点，直到 §4 的沙箱成立。

---

## 6. MCP

### 6.1 它做什么

- **出站（third-party 作为 MCP client）**：MCP server 被当作“包”管理，工具进入统一工具表；`MCPManager` 维护 transport-neutral session 缓存、重连、失败原因；支持本地 bridge 与远端两种运行描述。
- **入站（third-party 作为 MCP server / bridge）**：`MCPLocalServer` 统一管理官方 `mcp_config.json`、插件配置、server 状态；`MCPBridge`/`MCPStarter`/`MCPDeployer` 可从项目分析、生成配置、部署/启动 MCP server（终端 + pnpm/Node 场景）。
- 配置导入解析（Claude/Cursor 风格 `mcpServers`）；MCP 包安装进度、详情、命令编辑、部署确认等 UI。
- 宿主工具可通过本地 server 暴露给外部客户端（`MCPLocalServer.kt:964-995` 调 `AIToolHandler.executeTool`）。

### 6.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| Client/session | `core/tools/mcp/MCPToolExecutor.kt:465`（`MCPManager`）、`:519`（`getOrCreateSession`）、`:545`（Local/Remote 分派）；`McpRuntimeSession.kt`、`MCPServerConfig.kt`、`MCPPackage.kt` |
| 本地 server/配置中心 | `data/mcp/MCPLocalServer.kt:47`、`data/mcp/MCPRepository.kt`、`McpConfigImportParser.kt` |
| Bridge/部署 | `data/mcp/plugins/MCPBridge.kt`、`MCPBridgeClient.kt`、`MCPStarter.kt`、`MCPDeployer.kt`、`MCPProjectAnalyzer.kt`、`MCPConfigGenerator.kt`、`RemoteMcpRuntimeSession.kt` |
| UI | `ui/features/packages/screens/MCPConfigScreen.kt`、`components/dialogs/MCPServerDetailsDialog.kt`、`screens/mcp/components/MCPDeployConfirmDialog.kt` |

### 6.3 MCA 对应

- `app/.../agent/extensions/mcp/McpClient.kt:32`：**tools-only Streamable HTTP** 客户端（2025-06-18），`initialize → notifications/initialized → tools/list`，JSON/POST-SSE，不启用独立 GET stream/legacy HTTP+SSE，404 视为 session 过期且不重放 `tools/call`。
- `AgentMcpRegistry.kt`（catalog、schema hash、drift 回调）、`AgentMcpServerStore.kt`（HTTPS endpoint + 本地 toggle）、`AgentMcpEgressGuard.kt`、`McpToolAdapter.kt`。
- UI：`feature/agent-workspace/ui/AgentMcpScreen.kt`、`AgentMcpCoordinator.kt`。

### 6.4 差距

- **只有出站、只有 HTTP**：无 stdio/本地进程、无 inbound server（MCA 不能被别的 Agent 当 MCP server 用）、无配置导入、无部署/启动、无 server 日志。
- **能力面对齐度**：third-party 的 MCP 是“包系统的一等公民”（安装/启停/部署/本地 server）；MCA 是“注册表 + 连接器”。
- **安全模型**：MCA 的 egress grant/审批更严格，这是优势，不应为 parity 降级。

### 6.5 优先级与采纳建议

**P1**。优先补：`mcpServers` 配置导入（只读预览 → 用户确认）、stdio 本地 server（受限工作目录 + 超时 + 逐次审批）、以及“MCA 作为 MCP server 暴露只读工具”的入站端点。部署/市场类能力放 P2。

---

## 7. Memory / library（记忆库）

### 7.1 它做什么

- **多记忆空间**（`MemorySpace`）+ 文件夹 + 标签 + 来源 + 可信度/重要性；记忆之间可建**链接**（图谱），支持查询/更新/删除链接。
- **语义检索**：`MemoryRepository` 用远端 embedding（`CloudEmbeddingConfig`：endpoint/apiKey/model）+ HNSW 向量索引；混合打分（语义 + 关键词 + RRF），支持阈值、时间过滤、分页快照去重。`[OPR-SRC] data/repository/MemoryRepository.kt:97-130,247-270,293-298`
- **文档型记忆**：导入文档 → 分块（`DocumentChunk`）→ 块级 embedding/检索；`DocumentChunk`/`chunkInfo` 出现在查询结果中。
- **自动记忆**：`MemoryAutoSaveScheduler` 定时从对话抽取候选，`ChatMemoryRebuildManager`/`ChatMemoryWindowPlanner` 做窗口与重建；`MemoryLibrary.saveMemoryWindowNow` 落库。
- **工具与 UI**：`query_memory`/`create_memory`/`update_memory`/`link_memories` 等一组默认工具；记忆页、搜索设置、模拟检索、文件夹选择；SAF `MemoryDocumentsProvider` 导出。

### 7.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 记忆门面 | `api/chat/library/MemoryLibrary.kt:35`、`:113`（`saveMemoryAsync`）、`:145`（`saveMemoryNow`）、`:165`（`saveMemoryWindowNow`） |
| 仓库/检索 | `data/repository/MemoryRepository.kt:97`（embedding）、`:106`（cosine）、`:247`（RRF）、`:293`（HNSW 文件）、`:355`（重建索引） |
| 向量索引 | `util/vector/VectorIndexManager.kt:14-88` |
| Embedding | `services/CloudEmbeddingService.kt:19`、`data/model/CloudEmbeddingConfig.kt`、`Embedding.kt` |
| 自动保存 | `api/chat/library/MemoryAutoSaveScheduler.kt:24,48,70`、`ChatMemoryRebuildManager.kt`、`ChatMemoryWindowPlanner.kt:11` |
| 工具 | `core/tools/defaultTool/standard/MemoryQueryToolExecutor.kt`、`core/tools/ToolRegistration.kt:808-999` |
| 模型/空间 | `data/model/Memory.kt`、`MemorySpace.kt`、`DocumentChunk.kt`、`MemorySearchConfig.kt` |
| UI/导出 | `ui/features/memory/**`、`provider/MemoryDocumentsProvider.kt` |

### 7.3 MCA 对应

- 角色级长期记忆：`app/.../AssistantMemoryOperations.kt:130`（`upsertAssistantMemoryFor`）、`CharacterLongTermMemory.kt:27`（`buildCharacterMemoryContext`）、`AssistantMemoryTurnRecord.kt`、`ChatSessionMemoryDao.kt`。
- 存储：`PersistedRecords.kt:26`（`MemoryRecord`），按角色/会话归属，自动/手动来源区分。
- 只有“摘要 + 最近条目 + 优先级”注入；无向量、无跨角色/跨任务检索、无文件夹/链接/空间。

### 7.4 差距

- **检索方式**：MCA 是“按角色取最新/重要条目拼接”；third-party 是“语义 + 关键词混合检索 + 分页去重 + 文档分块”。
- **组织方式**：MCA 无空间/文件夹/链接/标签；无法表达“这个角色的知识库”与“全局知识”的分层。
- **写入时机**：MCA 有 turn 级自动记忆与摘要；third-party 有定时窗口抽取 + 重建，且与附件/文档打通。
- **隐私前提**：third-party 语义检索依赖**远端 embedding 服务**；MCA 若采纳必须提供“本地 embedding 或用户显式授权端点”，不能默认出网。

### 7.5 优先级与采纳建议

**P1**。先做“本地可用的检索型记忆”（FTS/关键词 + 可选本地 embedding），再加空间/文件夹/链接；远端 embedding 必须走现有 egress/审批，且可关闭。不要照搬 third-party 的 HNSW 实现，只需保留“可重建索引 + 维度隔离”的语义。

---

## 8. Model providers（模型提供方）

### 8.1 它做什么

- 39 个 `ApiProviderType` 分支（OpenAI/xAI/Responses/Codex OAuth/Claude/Gemini/百度/阿里/讯飞/智谱/百川/Moonshot/MiMo/DeepSeek/Mistral/SiliconFlow/iFlow/OpenRouter/OpenCode/4Router/Nous/InfiniAI/百灵/豆包/NVIDIA/LM Studio/Ollama/OpenAI 本地/MNN/llama.cpp/PPInfra/Novita/MiniMax/Other…）。`[OPR-SRC] data/model/ModelConfigData.kt:7-45`
- 统一 `AIService` 接口：`sendMessage`（流式）、token 统计、模型列表、连接测试、取消。`[OPR-SRC] api/chat/llmprovider/AIService.kt:12-101`
- 工程化配套：多 API Key 池 + 轮询/随机、滑动窗口限流、请求并发注册表、重试策略、token 追踪、模型列表抓取、连接测试、thinking 质量映射、结构化 tool call 桥（XML↔JSON）、Gemini thought-signature 处理、Codex OAuth/用量。
- **按功能分配模型**：`FunctionType` 11 种（CHAT/SUMMARY/TITLE_GENERATION/MEMORY/UI_CONTROLLER/TRANSLATION/GREP/ROLE_RESPONSE_PLANNER/IMAGE_RECOGNITION/AUDIO_RECOGNITION/VIDEO_RECOGNITION）。`[OPR-SRC] data/model/FunctionType.kt:6-17`
- 本地模型：MNN / llama.cpp 作为一等 provider；本地模型自动切 CLI 工具暴露模式（`ToolExposureMode.resolve`）。`[OPR-SRC] core/tools/climode/CliToolModeSupport.kt:19`

### 8.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 配置/枚举 | `data/model/ModelConfigData.kt:7-45`、`:64`（默认 `enableToolCall=true`） |
| 工厂/分派 | `api/chat/llmprovider/AIServiceFactory.kt:269`（`createService`）、`:317`（39 分支 `when`） |
| Provider 实现 | `OpenAIProvider.kt`、`OpenAIResponsesProvider.kt`、`ClaudeProvider.kt`、`GeminiProvider.kt`、`DeepseekProvider.kt`、`QwenAIProvider.kt`、`OllamaProvider.kt`、`MNNProvider.kt`、`LlamaProvider.kt`、`CodexProvider.kt` 等 |
| 可靠性 | `RateLimitedAIService.kt`、`LlmRetryPolicy.kt`、`TokenTrackingAIService.kt`、`ApiKeyPoolAvailabilityTester.kt`、`RequestConcurrencyRegistry.kt` |
| 工具桥 | `StructuredToolCallBridge.kt`、`ThinkingQualityMapping.kt` |
| 模型发现 | `ModelListFetcher.kt`、`ModelConfigConnectionTester.kt`、`MediaCapabilityProbe.kt` |
| 脚本提供方 | `ToolPkgJsAiProviderService.kt`（ToolPkg 可注册模型提供方） |

### 8.3 MCA 对应

- 云：`app/.../CloudChatProvider.kt:58-72` 仅 3 种 `CloudApiFormat`（OpenAI-compatible / Anthropic Messages / OpenAI Responses），图片 3 种（OpenAI Images / DashScope / 自定义路径）。
- 本地：`McaAgentLocalModelAdapter.kt`、`AgentCloudModelAdapter.kt`、`AgentModelChoiceMapper.kt`、`core/inference-contract`。
- 模型中心：`feature/modelhub/**`（推荐、下载、市场）。
- 按功能分配：只有 chat/cloud 与 image 的后端切换，无 summary/title/memory/translation 等功能级路由。

### 8.4 差距

- **协议广度**：Gemini 原生、Codex OAuth、本地 Ollama/LM Studio 预设缺失；39 个预设中多数是 OpenAI-compatible 的 endpoint 元数据，对齐成本主要是“预设目录 + 协议适配”，不是 39 套实现。
- **功能级路由**：third-party 可为总结/记忆/翻译/UI 控制分别指定模型；MCA 只有单一 cloud/local 选择。
- **可靠性设施**：多 Key 池、限流、重试、thinking 映射在 MCA 侧没有对等物（部分可能由 OkHttp/上层处理，但静态未见）。
- **工具调用桥**：third-party 的 XML↔原生 tool_calls 双向桥是其“任何 provider 都能用工具”的关键；MCA 的 PROMPTED_V1 是文本协议，已有等价目标（不依赖 provider 原生 tool calling），但缺少原生 tool-calling 路径的完整适配。

### 8.5 优先级与采纳建议

**P2**。先做“provider 预设目录 + 功能级模型绑定（summary/title/translation/memory）”；Gemini 原生与 Codex OAuth 单独评估。不要把 39 个预设当 KPI。

---

## 9. Permissions / root / Shizuku（权限与设备通道）

### 9.1 它做什么

- **工具权限三档**：`ALLOW`（自动）/`ASK`（每次询问）/`FORBID`（禁止）；全局默认 + 单工具例外；默认 `ASK`。`[OPR-SRC] ui/permissions/ToolPermissionSystem.kt:34-50`、`[OPR-DOC] docs/doc-src/feature-protocol/tool_permissions.md:9-56`
- 运行中权限弹窗/悬浮遮罩；权限检查结果类型化；未决请求可刷新/恢复。
- **五级 Android 权限阶梯**：`STANDARD / ACCESSIBILITY / DEBUGGER / ADMIN / ROOT`，`ToolGetter` 按首选级别选择文件/UI/系统/设备信息工具实现。`[OPR-SRC] core/tools/system/AndroidPermissionLevel.kt`、`core/tools/defaultTool/ToolGetter.kt:20-86`
- **Root**：libsu + `su -c`（可配自定义 su 命令、AUTO/FORCE_EXEC 模式），Root 状态 Flow + 监听器。`[OPR-SRC] core/tools/system/RootAuthorizer.kt:20,30,88-101`
- **Shizuku**：`rikka.shizuku` binder 授权，作为 ADB 级调试通道；配合 `DebuggerShellExecutor`/`AdminShellExecutor`。`[OPR-SRC] core/tools/system/ShizukuAuthorizer.kt:15-80`
- Shell 执行器工厂按级别分派；无障碍动作监听器（tap/swipe/click/input）同构分派。
- 角色卡可进一步限制工具集合（`CharacterCardToolAccessResolver`）。`[OPR-SRC] api/chat/enhance/ToolExecutionManager.kt:524-534`

### 9.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 工具权限 | `ui/permissions/ToolPermissionSystem.kt:55`、`:169`、`:192`；`ToolPermissionDialog.kt`、`PermissionRequestOverlay.kt`、`ToolPermissionCheckResult.kt` |
| 权限级别 | `core/tools/system/AndroidPermissionLevel.kt`、`ToolGetter.kt` |
| Root | `RootAuthorizer.kt`、`shell/RootShellExecutor.kt`、`action/RootActionListener.kt` |
| Shizuku | `ShizukuAuthorizer.kt`、`ShizukuInstaller.kt`、`shell/DebuggerShellExecutor.kt`、`shell/AdminShellExecutor.kt` |
| 无障碍 | `shell/AccessibilityShellExecutor.kt`、`action/AccessibilityActionListener.kt`、`AccessibilityProviderInstaller.kt` |
| 系统执行 | `shell/ShellExecutorFactory.kt`、`ShellExecutor.kt`、`StandardShellExecutor.kt`、`AndroidShellExecutor.kt` |

### 9.3 MCA 对应

- `app/.../agent/security/AgentPermissionBroker.kt:43`（`decide`/`authorize`/`resolveApproval`）、`RoomAgentApprovalStore.kt`、`AgentApprovalStorePort.kt`。
- `AgentNetworkEgressPolicy.kt:187` + `AgentEgressGrantStore` + `AgentFrozenCommandFactory`：网络/工具副作用的数据分级与 run-scoped grant。
- **没有** Android 设备执行层：无 AccessibilityService、无 Shizuku、无 libsu，未发现 Root/Shell 执行层（本任务扫描的 1,442 个 Kotlin 文件中上述符号命中均为 0）。

### 9.4 差距

- **设备通道**：third-party 能真正操作手机（点击/输入/截图/装应用/改设置/蓝牙）；MCA 只能读写任务工作区 + 联网 MCP。
- **权限表达**：third-party 用“权限等级 + 工具三档”表达“这个工具需要什么通道”；MCA 用“工具声明 sideEffect + 审批”表达“这次调用要不要放行”。两者可组合，但 MCA 缺“通道”这一维。
- **风险对比**：third-party 的 Root/Shizuku/无障碍组合权限极大；MCA 现有的冻结命令 + 一次性审批 + egress 更符合移动端最小授权。**不应为 parity 降级 MCA 的安全模型。**

### 9.5 优先级与采纳建议

- **Accessibility（截图/点击/输入/返回）**：**P2**。必须逐动作审批 + 可见的“正在控制设备”状态 + 可一键停止；默认关闭。
- **Root / Shizuku**：**P3**。不建议采纳：与 MCA 目标设备/合规/支持成本冲突，且会破坏现有“无特权执行”叙事。
- 若未来做设备控制，应新增“通道能力”到 `AgentToolDescriptor`（而非复用 sideEffect 字符串），并让 broker 按通道授权。

---

## 10. UI chat / agent surfaces（聊天与 Agent 界面）

### 10.1 它做什么

- **主界面**：手机/平板两套布局，抽屉导航 + 路由目录；聊天、助手/角色、记忆、工作流、工具包/市场、设置、权限、Codex、公告等页面。
- **聊天页**：单屏 1,990 行的 `AIChatScreen`，含附件、工作区、工具调用 XML 渲染、工具结果展示、消息编辑/重生成（消息变体）、token 信息、滚动导航、导出。
- **悬浮形态**：悬浮球（SiriBall）/悬浮聊天窗/全屏语音模式/屏幕 OCR 模式；前台服务保活。
- **扩展 UI**：ToolPkg 可注册工具箱 UI 模块、路由、导航入口、桌面小组件。
- **外部界面**：`web-chat/`（React）通过本地 HTTP 与 App 交互；外部 HTTP chat / A2A / Intent 入口。

### 10.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 主壳/导航 | `ui/main/MainActivity.kt`、`components/AppContent.kt`、`DrawerContent.kt`、`screens/ThirdPartyScreens.kt`、`navigation/AppRouteCatalog.kt` |
| 聊天页 | `ui/features/chat/screens/AIChatScreen.kt`、`components/ChatArea.kt`、`components/part/CustomXmlRenderer.kt`、`ToolResultDisplay.kt` |
| 悬浮 | `ui/floating/FloatingChatWindow.kt`、`ui/ball/SiriBall.kt`、`ui/fullscreen/**`、`ui/screenocr/**`、`services/FloatingChatService.kt` |
| 扩展 UI | `core/tools/packTool/PackageManager.kt:202-245`（UI 模块/路由/导航/小组件） |
| Web/外部 | `web-chat/`、`integrations/http/WebChatHttpServer.kt`、`integrations/http/ExternalChatHttpServer.kt`、`integrations/a2a/A2aHttpHandler.kt` |
| 聊天渲染协议 | `ui/common/displays/**`、`api/chat/enhance/ConversationMarkupManager.kt` |

### 10.3 MCA 对应

- 聊天：`feature/chat/**`（`ChatScreen.kt`、`ChatViewModel.kt`、`ChatBubbleMessageBlocks.kt`、`ChatComposer*` 等），独立 Agent 工作区：`feature/agent-workspace/AgentWorkspaceRoute.kt`、`ui/AgentTaskDetailScreen.kt`、`AgentTimelineRowItem.kt`、`AgentHomeComposer.kt`、`AgentApprovalSheet.kt`；入口 `ChatHistoryDrawerScreens.kt:371-378`（“Agent”）。
- 独立调参页：`feature/agent/AgentScreen.kt`（`MainActivity.kt:338`）。
- 无悬浮窗、无插件 UI 模块、无入站 web chat/A2A。

### 10.4 差距

- **Agent 与聊天割裂**：third-party 在聊天消息里直接看到工具调用/结果/diff；MCA 要跳到独立工作区看轨迹。
- **无悬浮/全局唤起**：third-party 有悬浮球/全屏语音/OCR；MCA 无。
- **无 UI 扩展点**：third-party 的 ToolPkg 能装“页面”；MCA 的 Skills 只能给提示词。
- **无入站通道**：third-party 可被 Web/HTTP/A2A/Intent 调用；MCA 的 Local API 只暴露 chat/images，不暴露 agent。

### 10.5 优先级与采纳建议

**P1**（聊天内 Agent 投影 + 工具时间线）；**P2**（悬浮窗/通知内继续）；**P3**（插件 UI 模块）。入站 Agent API 建议在 V1 之后单独设计，不混入本轮。

---

## 11. Task / session model（任务与会话模型）

### 11.1 它做什么

- **会话 = 聊天**：`ChatEntity`（Room）保存标题、token、workspace/workspaceEnv、`parentChatId`（子会话）、角色卡绑定、分组、置顶/锁定；`ChatHistory` 是 UI 侧模型。`[OPR-SRC] data/model/ChatEntity.kt:11-28`
- 会话操作：新建/切换/删除/清空/重命名、加载更早/更新消息（分页）、取消当前/指定会话、token 统计。
- 消息变体：编辑/重生成产生 `MessageVariantEntity`，可切换；对话导出（Markdown/HTML/CSV/JSON）。
- 子任务：`parentChatId` + `isSubTask` 参数 + “子聊天”语义（同一 workspace）；UI 自动化子代理通过 `run_ui_subagent` 工具在内部跑独立循环。
- 任务化：A2A `A2aTaskManager`（外部任务生命周期）、Tasker 集成（外部触发）。**没有** MCA 式的 run/attempt 日志、预算、终态审计。
- 前台服务：`AIForegroundService`、`ChatRuntimeSlot`/`ChatRuntimeHolder` 按 chatId 隔离运行态。

### 11.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 会话实体 | `data/model/ChatEntity.kt:11`、`data/model/ChatHistory.kt` |
| 会话服务 | `services/ChatServiceCore.kt:35`、`:309`（`createNewChat`）、`:326`（`switchChat`）、`:347`（`deleteChatHistory`）、`:536`（`reloadChatMessagesSmart`） |
| 运行态 | `api/chat/ChatRuntimeHolder.kt`、`ChatRuntimeSlot.kt`、`api/chat/AIForegroundService.kt` |
| 变体/历史 | `data/model/MessageVariantEntity.kt`、`data/dao/MessageVariantDao.kt`、`data/repository/ChatHistoryManager.kt` |
| 子任务 | `EnhancedAIService.kt:918`（`isSubTask`）、`core/tools/defaultTool/standard/StandardUITools.kt:407`（`runUiSubAgent`） |
| 外部任务 | `integrations/a2a/A2aTaskManager.kt`、`integrations/tasker/AIAgentTasker.kt` |

### 11.3 MCA 对应

- **MCA 更强**：`AgentTaskRepository`、`RoomAgentJournal`（append-only 事件）、`AgentRunOwner`/`AgentRunStateMachine`、`AgentBudgetPolicy`、attempt 注册表、artifact ledger、workspace 配额/GC、任务包导入导出（`agent/data/export/**`）、启动恢复（`AgentStartupRecovery.kt`）。
- 聊天会话：`ChatSessionStore` + `ChatSessionStoreRoomDatabase` + `ChatUiProjectionCache`；本次静态检索未见“分支/变体/子会话/每会话 workspace”语义（不排除以其它命名存在）。

### 11.4 差距

- MCA 在“任务可恢复/可审计/可导出”上明显领先；third-party 在“会话内的分支/变体/工作区绑定/多角色”上更贴近聊天产品。
- third-party 没有 MCA 式“预算/审批/终态”的 run 模型；MCA 没有 third-party 式“消息变体 + 子会话 + workspace 绑定聊天”。
- 两者都没有把“Agent 任务”与“聊天会话”做成同一对象：third-party 用 chatId 近似任务，MCA 用 taskId 严格任务。

### 11.5 优先级与采纳建议

**P2**。补“聊天会话 ↔ Agent 任务”的显式关联（一个会话可挂多个任务/run，任务详情可回到会话位置），以及消息变体/重生成；不要为了像 third-party 而放弃 run journal。

---

## 12. Plugins / hooks（插件与钩子）

### 12.1 它做什么

- **宿主插件注册表**：`ThirdPartyPlugin`（id + register）+ `PluginRegistry`，内置 `ToolboxPlugin`、`ToolPkgCommonBridgePlugin`、`WorkflowLifecyclePlugin`。`[OPR-SRC] plugins/PluginRegistry.kt:9-46`
- **ToolPkg Hook 面**（JS 注册，宿主分发）：应用生命周期、消息处理（可接管回复）、XML 渲染、输入菜单开关、聊天输入/消息持久化/长按菜单/运行态、工具生命周期、Prompt 输入/历史/估算/系统提示词/工具提示词/最终发送、摘要生成。
- **Hook 超时门禁**：前置 Hook 默认 10s（1–60s 可配），超时中断 QuickJS 并忽略返回值。`[OPR-DOC] docs/doc-src/package-dev/toolpkg.md:69-79`
- **Hook 执行预算/取消**：`ToolPkgHookExecutionBudget.kt`、`ToolPkgMessageProcessingCancellationRegistry.kt`。
- **桥接层**：10 个 `ToolPkg*Bridge`/`BridgePlugin` + 3 个 UI Hook Registry，把 JS 能力映射到 Kotlin 注册表。
- **模型提供方插件**：ToolPkg 可注册 AI provider（`ToolPkgAiProviderRegistry.kt`）。

### 12.2 关键类 / 文件

| 角色 | 位置 |
| --- | --- |
| 插件注册表 | `plugins/PluginRegistry.kt:15`、`:29`（builtins）、`:40`（installAll） |
| Hook 注册表 | `plugins/chatmessage/ChatMessageMenuItemRegistry.kt`、`plugins/chatview/ChatViewHookPluginRegistry.kt`、`plugins/lifecycle/AppLifecycleHookPluginRegistry.kt` |
| ToolPkg 桥 | `plugins/toolpkg/ToolPkgCommonBridgePlugin.kt`、`ToolPkgPromptHookBridge.kt`、`ToolPkgChatRuntimeHookBridge.kt`、`ToolPkgToolLifecycleBridge.kt`、`ToolPkgSummaryHookBridge.kt`、`ToolPkgMessageProcessingCancellationRegistry.kt` 等 |
| 预算/顺序 | `ToolPkgHookExecutionBudget.kt`、`ToolPkgHookModels.kt` |
| 内核 Hook 注册表 | `core/chat/hooks/PromptHookRegistry.kt`、`ChatRuntimeHookRegistry.kt`、`SummaryHookRegistry.kt`、`core/chat/plugins/MessageProcessingPluginRegistry.kt` |

### 12.3 MCA 对应

- 无通用插件/hook host；扩展只有 Skills（提示词 + 资源）与 MCP（外部工具）。
- 有明确的“扩展 seam”但刻意收窄：`AgentToolCatalog`、`AgentModelPort`、`AgentToolPort`、`AgentSkillManager`。

### 12.4 差距

- third-party 的插件能改**提示词/历史/工具提示词/最终请求/摘要/渲染/输入菜单**——等于把产品行为开放给第三方；MCA 目前全部编译期固定。
- 这类能力对安全/可维护性冲击最大（插件可绕过策略、改变模型输入）；MCA 的架构硬锁要求“插件不能绕过核心策略”。

### 12.5 优先级与采纳建议

**P3**。近期不建议做通用 Hook host；若未来做，只允许“纯声明式、不可执行代码”的扩展点（如提示词片段/工具白名单），且必须经 `AgentToolCatalog`/approval 层。

---

## 13. 周边能力（不进入本轮采纳）

| 能力 | third-party 证据 | MCA 现状 | 建议 |
| --- | --- | --- | --- |
| 语音 STT/TTS | `api/speech/**`（Sherpa/ONNX VAD/OpenAI/Deepgram）、`api/voice/**`（OpenAI/MiniMax/Doubao/VITS 等） | 无对应 Agent 语音链路 | P3，独立产品线 |
| 虚拟屏/PhoneAgent | `core/tools/agent/PhoneAgent.kt:119`、`ShowerController.kt`、`VirtualDisplayManager.kt`、`showerclient/` | 无 | P3，需 Shower server/特权，风险高 |
| 浏览器会话/UserScript | `core/tools/defaultTool/websession/**`（WebView 会话、userscript、下载） | 无 | P2/P3，视“联网 Agent”路线 |
| 终端/PRoot Ubuntu | `terminal/` 子模块、`ThirdPartyTerminalManager.kt`、`Terminal.kt`、README PRoot | 无 | P3，体积/权限/合规成本高 |
| 角色卡/头像/多角色 | `data/model/CharacterCard.kt`、`core/avatar/**`、`avator/*` 模块 | 有角色/助手体系与头像字段（`AssistantStore` 等）；无 3D/骨骼动画模块 | 不追 |
| 外部入口 | `integrations/http/ExternalChatHttpServer.kt`、`A2aHttpHandler.kt`、`ExternalChatReceiver.kt` | Local API（chat/responses/images） | P2，Agent API 另立 |
| 市场/备份/公告 | `ui/features/packages/market/**`、`data/api/MarketStatsApiService.kt`、`data/backup/**` | 模型市场 + 任务包导入导出 | 不追 |

---

## 14. 不可照搬项与风险

1. **许可证**：third-party 为 LGPL-3.0（`LICENSE:1-3`）。本文只作参考；不复制其源码、不引入其 Gradle 模块（`:quickjs`、`:showerclient`、`:terminal`、`:mnn`、`:llama`、`avator/*`）。
2. **特权通道**：Root（libsu）、Shizuku、无障碍、虚拟屏组合起来等于“远程控制整机”。MCA 若采纳，必须逐动作审批 + 可见状态 + 可停止；Root/Shizuku 建议不做。
3. **QuickJS 长超时**：脚本工具超时 1800s（`JsTimeoutConfig.kt:4`），与 MCA 现有 30s 工具超时/预算模型冲突；必须重新设计超时与取消，不可直接沿用。
4. **远端 embedding**：third-party 语义记忆依赖用户配置的云端 embedding endpoint；MCA 若默认出网会破坏“本地离线”叙事，必须可选/本地优先。
5. **插件可改提示词**：third-party 的 Prompt Hook 能让第三方改变系统提示词与工具提示词；这与 MCA 架构硬锁“插件不能绕过核心策略”冲突，不能照搬。
6. **营销文本 ≠ 源码事实**：third-party README 声称“最强大/最完整”（`README.zh-CN.md:23`），本文只采信可定位到 `path:line` 的机制。
7. **规模差异**：third-party `app` 模块约 1,152 个 Kotlin 文件（`ui` 528、`core` 223、`data` 172、`api` 87 等）；MCA 在本任务统计口径下 1,442 个 Kotlin 文件（含测试，排除 `build/`），但职责分布不同。不能按文件数判定能力。

---

## 15. MCA 采纳顺序（Top 8）

排序 = 用户价值 × 架构复用度 ÷ 风险。每项都保留 MCA 既有 owner/审批/egress 约束。

| 顺序 | 能力 | 优先级 | 为什么是它 | 最小可验证切片（建议） |
| --- | --- | --- | --- | --- |
| 1 | **聊天内 Agent 循环 + 工具时间线** | P0 | 把 Agent 从独立页搬进聊天主路径；工具调用/结果/停止在消息流里可见 | 同一 chat 下创建/绑定 run；单轮多工具；工具结果内联渲染；停止只停该 run |
| 2 | **声明式 Tool Package（无 JS）** | P1 | 把 4 个硬编码工具变成可安装目录；为后续 JS/市场铺路 | manifest（id/version/requires/tools/resources）+ 导入/启停 + 工具 schema 注册 + 权限描述 |
| 3 | **检索型记忆库** | P1 | 角色记忆从“最近摘要”升级为“可检索知识”；与聊天/任务共享 | 记忆空间/文件夹 + 关键词/本地 embedding 检索 + 文档分块 + 自动写入调度 |
| 4 | **MCP parity** | P1 | 已有 HTTP 客户端；补配置导入、stdio 本地 server、入站只读 server | `mcpServers` 导入预览 → 确认；stdio 会话生命周期；MCA-as-server 只读工具 |
| 5 | **JS/QuickJS 工具运行时** | P2 | third-party 生态的发动机；但必须最后做、且沙箱化 | 无 UI/无反射的最小 ToolPkg：注册工具 + 调宿主工具 + 超时/取消 + 每副作用审批 |
| 6 | **工作流/自动化** | P2 | 把 Agent 产物变成可重复流程；复用工具目录 | 任务模板 + WorkManager 定时触发 + condition/extract 节点 + 执行历史 |
| 7 | **Accessibility 设备控制** | P2 | 真正“手机 Agent”的差异点；风险可控（无 root） | 截图/点击/输入/返回 4 个工具 + 逐动作审批 + 前台可见 + 一键停止 |
| 8 | **Provider 预设 + 功能级模型路由** | P2 | 让总结/标题/翻译/记忆各用合适模型；降低云端单点 | provider 预设目录（Gemini/Ollama/LM Studio）+ per-function binding + 失败回退 |

> 不建议采纳：Root/Shizuku、虚拟屏/Shower、PRoot Ubuntu、通用 JS Hook host、插件 UI 模块。它们是 third-party 的形态选择，不是 MCA 的必要条件。

---

## 16. NOT_RUN / UNKNOWN / 边界

### 16.1 本任务未运行

| 项 | 状态 | 说明 |
| --- | --- | --- |
| third-party 构建/安装/启动 | `NOT_RUN` | 只读克隆，未执行 Gradle/ADB |
| third-party 工具执行、QuickJS、工作流、MCP | `NOT_RUN` | 无设备/无构建；仅静态阅读 |
| third-party 记忆检索/embedding | `NOT_RUN` | 无 endpoint/无数据 |
| third-party Root/Shizuku/无障碍 | `NOT_RUN` | 无设备 |
| MCA 构建/测试/设备 | `NOT_RUN` | 本任务为只读文档，未运行任何门禁 |
| MCA Agent 真实模型/MCP/云 | `NOT_RUN` | 沿用既有交接文档的 `NOT_RUN` 状态（`MCA_AGENT_V1_HANDOFF.md:14-17`） |

### 16.2 无法从本次材料判定（`UNKNOWN`）

- third-party ToolPkg 在真机上的加载成功率、QuickJS 内存/崩溃边界。
- third-party 工作流在 Doze/后台限制下的真实触发可靠性。
- third-party 记忆检索的实际召回质量（依赖 embedding 模型与数据）。
- third-party Shizuku/Root 在非 MIUI/AOSP 设备上的一致性。
- MCA `AgentCloudModelAdapter` 的真实云链路（交接文档标注 egress 未接，云选项隐藏）。

### 16.3 明确的证据边界

- 本文不把 third-party README/文档的自述当作能力证明；自述只用于定位，最终以源码 `path:line` 为准。
- 本文不把“编译通过/代码存在”当作运行时通过；MCA 侧同样不宣称其 Agent 已在设备上验证。
- third-party 的 UI/渲染细节（如工具 XML 渲染的具体排版）只按文件存在性记录，未做视觉核对。

---

## 17. 证据索引

### 17.1 third-party 关键文件（相对 `app/src/main/java/com/ai/assistance/thirdparty/`）

| 主题 | 文件 |
| --- | --- |
| 聊天循环 | `api/chat/EnhancedAIService.kt`、`api/chat/AIMessageManager.kt`、`api/chat/enhance/ConversationService.kt`、`api/chat/enhance/ConversationRoundManager.kt`、`api/chat/enhance/ToolExecutionManager.kt` |
| 工具框架 | `core/tools/AIToolHandler.kt`、`core/tools/ToolRegistration.kt`、`core/tools/ToolPackage.kt`、`core/tools/climode/CliToolModeSupport.kt` |
| 工具包 | `core/tools/packTool/{PackageManager,ToolPkgParser,ToolPkgManager,ToolPkgLoadOrder,ToolPkgRuntimeMonitor,ToolPkgApiVersion}.kt` |
| JS | `core/tools/javascript/{JsEngine,JsToolManager,JsLibraries,JsJavaBridge,JsTimeoutConfig,JsExecutionTrace}.kt`、`quickjs/**` |
| 工作流 | `core/workflow/{WorkflowExecutor,WorkflowScheduler,WorkflowWorker}.kt`、`data/model/Workflow.kt` |
| MCP | `core/tools/mcp/*`、`data/mcp/**`（含 `plugins/*`） |
| 记忆 | `api/chat/library/*`、`data/repository/MemoryRepository.kt`、`services/CloudEmbeddingService.kt`、`util/vector/VectorIndexManager.kt` |
| 模型 | `api/chat/llmprovider/*`、`data/model/ModelConfigData.kt`、`data/model/FunctionType.kt` |
| 权限 | `ui/permissions/*`、`core/tools/system/{RootAuthorizer,ShizukuAuthorizer,AndroidPermissionLevel}.kt`、`core/tools/system/shell/*` |
| UI | `ui/main/**`、`ui/features/chat/**`、`ui/floating/**`、`services/FloatingChatService.kt` |
| 会话 | `data/model/{ChatEntity,ChatHistory,MessageVariantEntity}.kt`、`services/ChatServiceCore.kt` |
| 插件 | `plugins/**`、`core/chat/hooks/*`、`core/chat/plugins/*` |
| 外部 | `integrations/**`、`web-chat/**` |

### 17.2 MCA 关键文件（相对 `E:\MAC`）

| 主题 | 文件/符号 |
| --- | --- |
| Agent 契约 | `core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/{AgentModelPort,AgentToolPort,AgentTaskTypes,AgentRunTypes,AgentJournal}.kt` |
| Agent 运行时 | `core/agent-runtime/.../run/{AgentPromptedLoopCoordinator,AgentRunOwner,AgentRunStateMachine,AgentBudgetPolicy}.kt` |
| 工具 | `app/src/main/java/com/muyuchat/mca/agent/tools/{AgentV1ToolSpecs,AgentToolCatalog}.kt` |
| MCP | `app/.../agent/extensions/mcp/{McpClient,AgentMcpRegistry,AgentMcpServerStore,McpToolAdapter}.kt` |
| Skills | `app/.../agent/extensions/skills/{AgentSkillManager,AgentSkillManifest}.kt` |
| 安全 | `app/.../agent/security/{AgentPermissionBroker,AgentNetworkEgressPolicy}.kt` |
| 任务/数据 | `app/.../agent/data/{AgentTaskRepository,RoomAgentJournal,AgentWorkspaceStore}.kt`、`agent/data/export/**` |
| 推理 | `app/.../agent/inference/{AgentCloudModelAdapter,McaAgentLocalModelAdapter}.kt`、`app/.../CloudChatProvider.kt` |
| UI | `feature/agent-workspace/.../AgentWorkspaceRoute.kt`、`ui/AgentTaskDetailScreen.kt`、`feature/agent/AgentScreen.kt`、`feature/chat/ChatHistoryDrawerScreens.kt:371-378` |
| 记忆 | `app/.../{AssistantMemoryOperations,CharacterLongTermMemory,AssistantMemoryTurnRecord,ChatSessionMemoryDao}.kt`、`PersistedRecords.kt:26` |
| 本地 API | `api/local/.../LocalApiCapabilityRegistryEndpoints.kt` |

### 17.3 命令（只读复现）

```powershell
# third-party 基线
git -C C:\Users\example\AppData\Local\Temp\thirdparty-src rev-parse HEAD
git -C C:\Users\example\AppData\Local\Temp\thirdparty-src status --porcelain

# MCA 基线
git -C E:\MAC rev-parse HEAD
git -C E:\MAC status --porcelain

# 关键计数（静态）
(Select-String -Path <thirdparty>\core\tools\ToolRegistration.kt -Pattern 'name = "').Count
(Select-String -Path <thirdparty>\data\model\ModelConfigData.kt -Pattern '^\s{8}[A-Z][A-Z0-9_]+').Count
```

---

## 18. 变更记录与最终声明

- 2026-10-19：创建本文件（首版）。本任务仅新增 `docs/proposals/agent-v1/THIRD_PARTY_FEATURE_MAP.md` 一个文件；未修改任何源码、构建文件、测试或既有文档（工作区其它未跟踪文件来自并行任务，非本任务创建）。
- 基线：third-party `dbf71916`（只读）、MCA `98eb8e4`（工作区含既有未提交改动，未触碰）。
- 最终声明：**本文是源码级参照，不是实现方案，也不是验收证据。** 所有 third-party 运行时行为 `NOT_RUN`；MCA 的采纳顺序是建议，不是已批准计划。任何后续实现必须重新查源码、按 `MCA_PROJECT_HARD_LOCK.md` 的 owner/状态/审批/验证要求立项。
