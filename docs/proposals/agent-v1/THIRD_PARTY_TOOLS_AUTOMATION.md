# third-party 工具与自动化子系统：只读审查与 MCA 分阶段采纳方案

版本：V1.0（设计提案）
日期：2026-10-19
审查对象：`thirdparty-src`，commit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`（2026-09-18，`git status` 干净）
审查方式：只读源码与仓库内文档；未编译、未运行、未安装、未联网抓取、未在设备上执行
MCA 侧观察基线：`codex/enterprise-refactor` @ `98eb8e4`，工作区存在与本任务无关的并行未提交改动
本轮写集：**仅本文件**。没有代码、构建、脚本或既有文档改动。

## 0. 效力、范围与证据边界

### 0.1 本文回答什么

1. third-party 的工具/自动化子系统**实际由哪些文件与类承担**，执行链与权限链分别在什么位置。
2. 其**权限模型与安全边界**的真实形态（包括实际存在、但文档未声明的旁路）。
3. MCA 若要吸收其中能力，**哪些可以照做、哪些必须明确拒绝**，以及一个保住 MCA 现有审批/egress 不变量、不给插件留旁路的**分阶段采纳计划**。

### 0.2 本文不代表什么

| 维度 | 状态 | 说明 |
| --- | --- | --- |
| third-party 源码事实 | 已读 | 逐文件定位到类/函数；行号是本次快照位置，不是长期合同 |
| third-party 行为实测 | `NOT_RUN` | 没有构建 APK、没有真机运行、没有触发任何工具或工作流 |
| third-party 供应链/许可 | 部分 | 仅看仓库内 `LICENSE` 与 `package.json` 元信息；未做法务结论 |
| MCA 代码事实 | 已读 | 只读核对 contract/runtime/app 三处 agent 文件，用于对照 |
| MCA 实施 | `NOT_RUN` | 本文是规划；没有任何 MCA 代码改动 |
| 性能、内存、耗电 | `NOT_RUN` | 不引用任何未测数字 |

### 0.3 与既有三份 MCA Agent 文档的关系

本文是第四份配套材料，不修改、不替代：

- [MCA_AGENT_V1_ARCHITECTURE.md](MCA_AGENT_V1_ARCHITECTURE.md)：产品范围、协议、状态、审批与 egress 合同（尤其 §8 审批、§11 威胁模型、§12 Skills/MCP、§15.3 后续 E 卡）。
- [MCA_AGENT_V1_EXECUTION_PACK.md](MCA_AGENT_V1_EXECUTION_PACK.md)：任务卡与写集。
- [MCA_AGENT_V1_ACCEPTANCE_MATRIX.md](MCA_AGENT_V1_ACCEPTANCE_MATRIX.md)：验收 ID 与证据格式。

本文只新增一个视角：**外部实现（third-party）的工具/自动化证据**，以及它如何映射到 MCA 已冻结的 E 卡。凡与架构文档冲突之处，架构文档与 [MCA_PROJECT_HARD_LOCK.md](../../architecture/MCA_PROJECT_HARD_LOCK.md) 优先。

### 0.4 术语

| 术语 | 含义 |
| --- | --- |
| 内置工具 | 注册在 `AIToolHandler` 的 Kotlin `ToolExecutor`，名称如 `read_file` |
| 包工具 | ToolPkg 子包声明的 JS 工具，调用名为 `packageName:toolName` |
| MCP 工具 | 外部 MCP server 的工具，调用名为 `serverName:toolName` |
| proxy 工具 | `package_proxy` / `proxy`：模型传 `tool_name` + `params` 转发到目标工具 |
| JS 桥 | QuickJS 内 `Tools.*`、`Java`、`Kotlin`、`Android` 等宿主能力注入 |
| 审批入口 | `ToolExecutionManager.checkToolPermission`（聊天工具调用路径） |
| 权限等级 | `AndroidPermissionLevel`：STANDARD / ACCESSIBILITY / DEBUGGER / ADMIN / ROOT |

---

## 1. 结论速览

### 1.1 七条事实结论

1. **注册表非常简单，能力面非常大。** 一个 `ConcurrentHashMap<String, ToolExecutor>` 加 180 个注册项即可构成 `AIToolHandler`；工具差别全部体现在 executor 内部，注册层没有能力声明、没有数据分级、没有超时或预算字段。（`core/tools/AIToolHandler.kt:45,179`；`core/tools/ToolRegistration.kt`）
2. **审批是“聊天入口策略”，不是“能力边界”。** 权限检查只存在于聊天工具调用路径与 CLI/proxy 路径；ToolPkg 的 JS、工作流执行节点、App 内各 UI 入口都直接调 `executeTool`，不经过 `ToolPermissionSystem`。（`api/chat/enhance/ToolExecutionManager.kt:429-493` 对 `javascript/JsNativeInterfaceDelegates.kt:690,733,788`、`core/workflow/WorkflowExecutor.kt:1275`）
3. **QuickJS 提供的是内存隔离，不是安全隔离。** 同一进程内通过反射桥、`Java.loadDex/loadJar`、OkHttp、`Android` 工具对象以及完整 `Tools.*` 表面暴露宿主能力；没有类白名单，没有内存/栈上限（`quickjs_jni.cpp` 只装了 interrupt handler）。（`javascript/JsEngine.kt:61,2134`；`javascript/JsExternalJavaCodeLoader.kt:164`；`quickjs/src/main/cpp/CMakeLists.txt`）
4. **持久授权是默认设计。** 权限是「全局默认 + 单工具覆盖」，`ALLOW`（含弹窗内 “ALWAYS_ALLOW”）会永久写入 DataStore，且没有作用域、没有撤销提醒、没有按数据类别区分。（`ui/permissions/ToolPermissionSystem.kt:192-283`）
5. **存在一个显式的、模型可控的审批旁路标签。** `deny_tool` 出现在调用原文里即跳过权限检查（`ToolExecutionManager.kt:457-458,487-492`）。这不是权限系统的一部分，而是绕过它的分支。
6. **扩展是“可信代码”模型。** ToolPkg 可以在主流程注入 prompt 钩子、改写工具提示词、注册 UI/生命周期钩子，并以该包脚本自身的权限调用任意宿主工具；包 manifest 没有能力声明字段（无网络/文件/root 声明）。（`docs/doc-src/package-dev/toolpkg.md:38-100`；`data/model` 无对应字段；`ToolPackage.kt:300-369`）
7. **工作流是同一工具面的编排层，不是独立执行域。** `ExecuteNode` 就是 `actionType` + `actionConfig` → `AITool` → `executeTool`；触发源包含导出广播（`exported=true`）与 WorkManager 周期任务。（`core/workflow/WorkflowExecutor.kt:1096-1319`；`docs/doc-src/feature-protocol/workflow_intent_trigger.md:168-169`；`core/workflow/WorkflowScheduler.kt:54-236`）

### 1.2 三条“看起来很强、实际不能照搬”

| 能力 | 表面价值 | 为什么 MCA 不能照搬 |
| --- | --- | --- |
| 进程内任意 JS/DEX + 反射桥 | 插件生态、脚本自由 | 与架构文档 §11.1 最后一行直接冲突：任意代码必然绕过进程内 broker；同 UID 不是沙箱 |
| 持久 `ALLOW` + 单工具覆盖 | 少弹窗、体验顺 | 与 MCA 冻结的 `ALLOW_ONCE` + 一次性消费 + 哈希绑定语义冲突；且无法证明“同一工具不同参数”的安全差异 |
| 一个 `package_proxy` 暴露全部包工具 | 省 schema token | 目标名来自模型文本；MCA 的冻结对象要求 descriptor 先于审批确定，proxy 会多出一条“先解析后冻结”的可攻击路径 |

### 1.3 五项建议（详见 §14）

1. **先把 MCA 审批管线闭环，再扩任何工具族**（当前 `AgentPermissionBroker` 的 KDoc 自述审批行未落库、`authorize` 未接线，所有外部调用 fail-closed 为 typed DENIED）。
2. **只加“内建、只读、有界”的工具族**（设备/环境只读信息、结果溢出落产物），全部走既有 descriptor→freeze→broker→executor 链。
3. **MCP 侧只补一致的收敛项**（schema 漂移使审批失效、结果块映射、参数完整性），不加新传输。
4. **脚本/终端与 Android 控制分别走 E03/E04 门槛**，且必须公开声明“可信代码模式”，不得宣称 broker 可阻止旁路。
5. **明确写下“不采纳清单”**（§16），尤其是 JS `Tools.*` 直连、工作流节点直连、`deny_tool`、持久 ALLOW、远端 denylist 作为准入门。

---

## 2. 子系统地图（文件 / 类 / 责任）

| 关注点 | 文件（`app/src/main/java/com/ai/assistance/thirdparty/…`） | 关键类/入口 | 责任 |
| --- | --- | --- | --- |
| 工具注册表 | `core/tools/AIToolHandler.kt`（491 行） | `AIToolHandler`、`ToolExecutor` | 名称→executor 映射、hook 分发、参数校验、串/流执行 |
| 内置工具注册 | `core/tools/ToolRegistration.kt`（2745 行） | `registerAllTools(handler, context)` | 180 个 `registerTool(name=…)`；描述生成器；proxy 解析 |
| 工具 schema / 提示词 | `core/config/SystemToolPrompts.kt`（1007 行） | `ToolPrompt`、`ToolParameterSchema`、`getAIAllCategoriesCn/En` | 给模型的工具说明书（硬编码、中英两份） |
| 暴露模式 | `core/tools/climode/CliToolModeSupport.kt` | `ToolExposureMode{FULL,CLI}`、`SEARCH_TOOL_NAME`、`PROXY_TOOL_NAME` | CLI 模式只暴露 `search`/`proxy` |
| 审批 | `ui/permissions/ToolPermissionSystem.kt`（305 行） | `PermissionLevel`、`checkToolPermission`、`requestPermission` | 全局开关 + 单工具覆盖 + 悬浮窗确认 |
| 审批结果 | `ui/permissions/ToolPermissionCheckResult.kt`、`PermissionRequestOverlay.kt` | `GRANTED/DENIED/OVERLAY_PERMISSION_REQUIRED/CONFIRMATION_TIMEOUT` | 超时与缺悬浮窗均 fail-closed |
| 聊天执行 | `api/chat/enhance/ToolExecutionManager.kt`（793 行） | `executeInvocations`、`checkToolPermission`、`aggregateToolResults` | XML 解析→多层拦截→并行/串行→结果聚合 |
| 聊天编排 | `api/chat/EnhancedAIService.kt`（3221 行） | `sendMessage`、`handleToolInvocation`、`getAvailableToolsForFunction` | 模型调用、工具轮次、工具结果回灌、递归 |
| JS 引擎 | `core/tools/javascript/JsEngine.kt`（2767 行）、`JsToolManager.kt`（477 行） | `executeScriptFunction`、`executeScript`、4 引擎池 | 脚本执行、会话、IPC、取消、内存采样 |
| QuickJS 原生 | `quickjs/src/main/cpp/quickjs_jni.cpp`、`quickjs/src/main/java/…/ThirdPartyQuickJsEngine.kt` | `QuickJsVm`、`ThirdPartyQuickJsEngine` | 单线程 runtime、eval/callFunction、interrupt |
| JS 桥 | `javascript/JsNativeInterfaceDelegates.kt`、`JsJavaBridge.kt`、`JsExternalJavaCodeLoader.kt` | `callToolSync/Async/AsyncStreaming`、`Java.loadDex/loadJar` | JS→宿主工具、反射、Dex/Jar 加载 |
| 包格式 | `core/tools/packTool/ToolPkgParser.kt`（78 KB）、`ToolPkgManifestRequirement.kt`、`ToolPkgApiVersion.kt`、`ToolPkgLoadOrder.kt` | manifest 解析、`requires`、API 1.0.0/1.0.1 | ZIP/目录→`ToolPackage`，依赖与顺序 |
| 包运行 | `core/tools/packTool/PackageManager.kt`（4101 行）、`PackageManagerToolPkgFacade.kt` | `usePackage`、`registerPackageTools`、`selectToolPackageState` | 启用、状态、env、注册、模板、资源 |
| 包监控 | `core/tools/packTool/ToolPkgRuntimeMonitor.kt` | `beginCall/finishCall/snapshot` | 调用时长/内存采样（仅观测） |
| 设备权限 | `core/tools/system/AndroidPermissionLevel.kt`、`defaultTool/ToolGetter.kt` | 5 级枚举、按级选实现 | 同一逻辑工具的 5 套实现 |
| Shell | `core/tools/system/shell/*` | `ShellExecutorFactory`、`Root/Admin/Debugger/Accessibility/StandardShellExecutor` | 命令执行通道 |
| 授权 | `system/ShizukuAuthorizer.kt`、`system/RootAuthorizer.kt` | Shizuku/libsu | 系统级特权桥 |
| UI 控制 | `defaultTool/accessbility/AccessibilityUITools.kt`、`standard/StandardUITools.kt`、`system/action/*` | 节点树简化、点击/输入/滑动、`ActionManager` | 无障碍/特权 UI 自动化 |
| UI 子代理 | `core/tools/agent/PhoneAgent.kt`（1297 行）、`VirtualDisplayManager.kt`、`Shower*` | 截图→文本模型→`do(action=…)` | 视觉驱动的手机代理 |
| 工作流 | `core/workflow/WorkflowExecutor.kt`（1320 行）、`WorkflowScheduler.kt`、`WorkflowWorker.kt` | `executeWorkflow`、`executeNode`、周期/一次性任务 | DAG 执行与调度 |
| 工作流工具 | `defaultTool/standard/StandardWorkflowTools.kt`（61 KB） | `create/update/patch/trigger_workflow` | 让模型改工作流 |
| MCP 消费 | `core/tools/mcp/MCPToolExecutor.kt`（610 行）、`McpRuntimeSession.kt`、`data/mcp/plugins/RemoteMcpRuntimeSession.kt` | `MCPToolExecutor`、`MCPManager`、`McpRuntimeDescriptor` | MCP server→工具；本地桥 + 远程 HTTP/SSE |
| MCP 启动 | `data/mcp/plugins/MCPStarter.kt` | 本地 stdio 插件 + 远程 Kotlin SDK 插件 | 启动、注册、健康检查 |
| MCP 配置 | `data/mcp/MCPLocalServer.kt`（1153 行） | `MCPConfig`、`autoApprove`、`pluginMetadata` | 配置持久化、导入、元数据 |

---

## 3. 工具注册、schema 与暴露面

### 3.1 注册表契约

`AIToolHandler` 只有三个概念：名称、描述生成器、执行器。

```text
availableTools: ConcurrentHashMap<String, ToolExecutor>   // AIToolHandler.kt:45
registerTool(name, descriptionGenerator?, executor)       // :179
executeTool(tool): ToolResult                             // :363
  ├─ notifyToolCallRequested → checkToolInterception（hook 可 Block）
  ├─ getToolExecutorOrActivate(name)                      // :303
  ├─ executor.validateParameters(tool)                    // 失败即返回 error
  └─ executor.invoke(tool)                                // 异常向上抛
```

要点：

- **没有能力字段。** `ToolExecutor` 只有 `invoke`、`invokeAndStream`、`validateParameters`（`:479-491`）。副作用等级、数据类别、超时、预算、审计标签都不在注册层，因此**无法在注册表层面回答“这个工具会不会写文件/联网”**。
- **缺工具会自动激活包。** `getToolExecutorOrActivate` 在名称含 `:` 且包已安装但未启用时直接 `packageManager.usePackage(packageName)`（`:315-331`），即**模型写对包名就等于启用插件**；MCP 会话失效时同样自动重连激活（`:333-356`）。
- **hook 是同步拦截点。** `AIToolHook.onToolCallIntercept` 返回 `Block(reason)` 即可阻断，hook 抛异常也按 Block 处理（`:99-117`）。这是 third-party 唯一的“全局策略钩子”，但它是可选的、由插件注册的，不是强制内核。

### 3.2 schema 来源

模型的工具说明书来自 `SystemToolPrompts.kt`：中英两份硬编码 `ToolPrompt`，含 `name`、`description`、`parametersStructured`、默认值文本。要点：

- schema 与执行实现分离在两个文件，仓库文档 `docs/doc-src/architecture/DEFAULT_TOOLS_ARCH.md` 明确列出“改参数必须同步 7 处”（prompt、注册、实现、JS wrapper、`.d.ts`、示例、assets 产物）。**这是典型的双写漂移风险**，MCA 用单一 `AgentToolDescriptor.schemaJson` + `schemaHash` 已经避免了它。
- 工具可见性还受四层过滤：全局 `enableTools`、`toolPromptVisibility`（按工具开关）、角色卡白名单 `roleCardToolAccess.isBuiltinToolAllowed`、以及**插件 hook** `applyToolPromptComposeHooksToAvailableTools`（`EnhancedAIService.kt:2882-3001`）。最后一项意味着**插件可以增删模型看到的工具**。

### 3.3 三种来源与命名空间

| 来源 | 注册名 | 谁注册 | 执行体 |
| --- | --- | --- | --- |
| 内置 | `read_file` 等 180 项 | `registerAllTools` 启动时幂等注册 | Kotlin `ToolExecutor` |
| 包工具 | `packageName:toolName` | `PackageManager.registerPackageTools`（`:3450-3467`） | `PackageToolExecutor` → `JsToolManager` |
| MCP 工具 | `serverName:toolName` | `MCPToolExecutor` + `MCPPackage.toToolPackage` | `MCPToolExecutor` → `McpRuntimeSession` |
| 代理 | `package_proxy`、`proxy` | 恒定注册 / CLI 模式 | 解析 `tool_name`+`params` 后转发 |

命名空间用 `:` 分隔是这套设计里少数值得直接继承的点：`MCPPackage.toToolPackage()` 把远端工具**先转成宿主 `ToolPackage`**，于是“启用/状态/环境/权限”全部复用同一条链，MCP 没有第二套生命周期（`MCPPackage.kt:133-165`）。MCA 的 `mcp:<serverId>:<name>` 是同一思想的更严格版本（多了 serverId 段与正则约束）。

### 3.4 暴露面统计

`ToolRegistration.kt` 中以 `name = "…"` 形式出现的注册项共 **180** 个（脚本提取，快照口径）。按名称粗分 14 个族：文件/工作区、终端/Shell、Android UI、应用与系统、网络/浏览器、包/插件、工作流、对话/消息、记忆、角色卡、模型配置、语音/音乐、蓝牙、媒体/FFmpeg；另有 `calculate`、`sleep`、`device_info`、`run_ui_subagent`、`agent_status`、`close_all_virtual_displays`、`update_user_preferences` 等单点工具。§12 按 17 行（含 MCP、UI 子代理、杂项）逐项对照。

---

## 4. 聊天如何调用工具（端到端）

### 4.1 内部协议是 XML，不是 provider tool_call

`docs/doc-src/feature-protocol/toolcall_xml_conversion.md` 给出可核对的结论：内部规范格式恒为

```xml
<tool name="read_file"><param name="path">README.md</param></tool>
<tool_result name="read_file" status="success"><content>…</content></tool_result>
```

`enableToolCall` 只是**对外协议转换开关**：请求前 XML→`tool_calls`，响应后 `tool_calls`→XML。因此 Provider 差异不进入执行层。MCA 相反：`PROMPTED_V1` envelope 与 native function-call 是两套显式能力，由模型能力标识选择，且都先转成同一条 `FrozenToolCommand`。**两者方向一致，MCA 更严格（显式协议版本 + 严格解码）。**

### 4.2 执行链（带位置）

```text
EnhancedAIService.sendMessage                              // :903
  ├─ getAvailableToolsForFunction(...)                     // :2882  工具说明书 + 过滤 + hook
  ├─ provider.sendMessage(...)                             // :1122  流式产出（含 XML 片段）
  ├─ ToolExecutionManager.extractToolInvocations(content)  // :1858  解析 XML → ToolInvocation[]
  └─ handleToolInvocation(...)                             // :2072
       └─ ToolExecutionManager.executeInvocations(...)     // :2120
            1) registerDefaultTools()（幂等兜底）           // ToolExecutionManager:520
            2) 角色卡工具访问解析                            // :524-534
            3) 暴露模式拦截 buildToolExposureDeniedResult   // :542-551
            4) 角色卡白名单拦截                             // :554-570
            5) hook 拦截 + checkToolPermission              // :575-599
            6) 注入包调用上下文（caller/chat/card）          // :601-618
            7) 并行（白名单）/串行分区                       // :621-630
            8) executeTool → aggregateToolResults           // :668-707
       └─ processToolResults(...)                        // :2192
            └─ 结果格式化 XML → 再次 sendMessage（递归）     // :2222-…
```

关键事实：

- **权限检查在第 5 步、执行在第 8 步，顺序正确；但只有这一条路有检查。**
- **并行只对 12 个只读/低耦合工具开放**（`list_files`、`read_file*`、`grep_code`、`calculate`、`visit_web`、`download_file` 等，`:621-625`；其中两项是网络只读）；权限检查在分区之前串行完成，所以不会并发弹窗。代价是审批串行化，N 个 ASK 工具需要 N 次点击。
- **结果聚合**：把同一调用的多段结果拼成一条文本，只保留最后一段的 `success/error`（`:710-734`）。也就是说，**中间某段失败但最后一段成功时，最终结果可以是 success**。这是 MCA 必须避免的语义（MCA 的 `AgentToolResult.status` 是事实状态，不允许“拼接后升级”）。
- **工具轮次没有显式上限**：`processToolResults` 直接再次 `sendMessage`，靠模型自行停止或用户取消。MCA 有 `AgentBudgetPolicy`（工具调用数、模型尝试、墙钟、活跃时长、生成 token 全部有额度），这是明确优势。

### 4.3 审批与结果的项目级后果

拒绝 → 生成 error 文本当作工具结果回灌模型（`ToolResult.error`），模型可继续尝试。没有“重复拒绝升级为暂停”，也没有 denied-loop 护栏；MCA 有 `PERMISSION_DENIED_LOOP`（连续 2 次相同拒绝转 PAUSED）。

---

## 5. 权限模型

### 5.1 数据结构

| 概念 | 位置 | 语义 |
| --- | --- | --- |
| 全局默认 | DataStore key `master_switch`，默认 `ASK` | 未单独配置的工具行为（`ToolPermissionSystem.kt:62-65,102-104`） |
| 单工具覆盖 | key `tool_permission_<toolName>` | 覆盖全局，无作用域、无过期（`:78,110-115`） |
| 三态 | `ALLOW` / `ASK` / `FORBID`（`CAUTION` 兼容读为 `ASK`） | `:34-50` |
| 决策 | `checkToolPermission` 顺序：读偏好 → 覆盖 ?: 全局 → 分支（`:192-207`） | 纯本地，无 run/参数/资源参与 |
| 交互 | `requestPermission`：置全局 `currentPermissionCallback` → 主线程悬浮窗 → 60 s 超时（`:212-263`） | 缺悬浮窗权限直接返回 `OVERLAY_PERMISSION_REQUIRED` |
| 记忆化 | 弹窗选 “ALWAYS_ALLOW” 立即 `saveToolPermission(tool.name, ALLOW)` | `:268-283`，**永久** |

`ToolPermissionCheckResult` 的四种结果里，`DENIED`、`OVERLAY_PERMISSION_REQUIRED`、`CONFIRMATION_TIMEOUT` 全是 `isGranted=false`——**超时与缺权限都是 fail-closed**，这一点是正确的。

### 5.2 检查点覆盖矩阵（本次源码核对）

| 入口 | 是否经过权限检查 | 证据 |
| --- | --- | --- |
| 聊天 XML 工具调用 | 是 | `ToolExecutionManager.kt:575-599` → `:429-493` |
| CLI 模式 `search`/`proxy` | 是（`proxy` 目标再查一次） | `ToolExecutionManager.kt:445-455`；`ToolRegistration.kt:221-266` |
| `package_proxy` 转发目标 | 是（按目标工具名） | `ToolExecutionManager.kt:67-88` + `checkToolPermission` |
| **ToolPkg JS 内 `Tools.*`** | **否** | `JsNativeInterfaceDelegates.kt:690,733,788` 直接 `toolHandler.executeTool` |
| **工作流 `ExecuteNode`** | **否** | `WorkflowExecutor.kt:1275` 直接 `toolHandler.executeTool` |
| App 内 UI 入口（文件管理器、工作区、FFmpeg 工具箱等） | 否（用户自己点击，属预期） | 多个 ViewModel 直接 `executeTool` |
| `deny_tool` 标签 | **跳过** | `ToolExecutionManager.kt:457-458,487-492` |

**结论：一旦某个包工具或工作流被批准（或根本不需要批准），它内部就不再有任何审批约束。** 审批的对象是“入口工具名”，不是“这次实际发生的副作用集合”。

### 5.3 并发与状态弱点

- `currentPermissionCallback` / `permissionRequestInfo` 是**单例全局可变字段**（`:83-84`）。聊天路径靠串行检查规避了并发，但任何未来并发调用都会互相覆盖回调。
- 权限键是**裸工具名**。`serverName:toolName` 与 `packageName:toolName` 共用同一键空间，重名包/server 会共享授权。

---

## 6. JS / QuickJS 执行模型

### 6.1 引擎与线程

- `JsToolManager` 持有 **4 个** `JsEngine` 组成 `Channel` 池（`JsToolManager.kt:29,45-48`）；ToolPkg 容器另有**按容器共享**的执行引擎（`PackageManager.getToolPkgExecutionEngine`）。
- 每个 `ThirdPartyQuickJsEngine` 有**一个专用 runtime 线程**（`ThirdPartyQuickJsRuntime`，daemon），所有 eval 通过单线程 executor 序列化；`runOnRuntimeThread` 用 `future.get()` 阻塞调用方（`ThirdPartyQuickJsEngine.kt:26-34,144-154`）。
- `PackageToolExecutor.invoke` 用 `runBlocking { jsToolManager.executeScript(...).last() }` 把异步流压成同步调用（`ToolPackage.kt:418-420`）。**执行器线程会被 JS 阻塞**，这是 `ToolExecutor` 同步契约的必然结果。

### 6.2 时间与内存边界（实测口径：源码声明）

| 边界 | 值 | 位置 | 评价 |
| --- | --- | --- | --- |
| 工具脚本超时 | **1800 s** | `JsTimeoutConfig.kt`（`MAIN_TIMEOUT_SECONDS = 1800`） | 相当于“基本不设上限” |
| 超时是否真能停止 | **能，但要 30 分钟** | 真正生效的是 `JsEngine.executeScriptFunction` 内的 `session.future.get(timeout)` + `interruptQuickJs(...)`（`JsEngine.kt:948-1033`）；`JsToolManager.kt:393` 外层 `withTimeout` 包住的是**阻塞调用**，对停止无贡献 | 一个失控脚本会占住一个引擎实例 30 分钟 |
| 中断机制 | 协作式 | `quickjs_jni.cpp:315` `JS_SetInterruptHandler`，`:587-593` 检查 `interrupted_` | 仅“每 N 条字节码”检查 |
| 内存上限 | **无** | `quickjs_jni.cpp:276-286` 只调 `JS_NewRuntime2`/`JS_NewContext`；全仓无 `JS_SetMemoryLimit` | JS 可占满进程堆 |
| 栈上限 | **无** | 无 `JS_SetMaxStackSize` | 深递归可打爆栈 |
| Hook 超时 | 1–60 s，默认 10 s | `docs/doc-src/package-dev/toolpkg.md:69-79` | 宿主可中断并丢弃 hook 返回值 |
| 运行时监控 | 仅观测 | `ToolPkgRuntimeMonitor.kt` 全文件只做采样与环形日志，无拒绝逻辑 | 遥测不等于执行限制 |

### 6.3 沙箱的真实边界

`JsLibraries.buildRuntimeBootstrapModules` 在每次启动时注入（`javascript/JsLibraries.kt`）：

- `Tools`（`JsTools.kt`，96 KB，全部宿主工具封装）
- `ToolPkg`（注册 API）
- `Java` / `Kotlin`（反射桥，`JsJavaBridge.kt` 56 KB）
- `Android` / `Intent` / `PackageManager` / `ContentProvider` / `SystemManager` / `DeviceController`（`assets/js/AndroidUtils.js`）
- `OkHttpClientBuilder` / `OkHttpClient` / `RequestBuilder`（`assets/js/OkHttp3.js`）
- `CryptoJS`、`Jimp`、`pako`、`UINode`、`ThirdPartyComposeDslRuntime`

再加两条直接突破：

1. **反射调用**：`ThirdPartyQuickJsEngine.dispatchNativeCall` 用 `Method.invoke` 调用已绑定实例的任意同名方法（`ThirdPartyQuickJsEngine.kt:156-173`），参数按 `parameterTypes` 强制转换；没有方法白名单。
2. **加载任意 JVM 代码**：`Java.loadDex(path)` / `Java.loadJar(path)` → `JsExternalJavaCodeLoader` → `DexClassLoader`/`PrefixIsolatedDexClassLoader`（`JsJavaBridge.kt:1230-1235`；`JsExternalJavaCodeLoader.kt:16-57,164`）。类加载器只隔离命名前缀，不隔离能力。

因此：**QuickJS 隔离的是“JS 堆”，不是“能力”。** 任何 ToolPkg 在获得执行权后，等价于以 App 身份运行任意本地代码。这与 MCA 架构 §12.3 第 5 条“PRoot/子进程都不是安全边界”的判断完全一致，也解释了为什么 third-party 必须把插件当可信代码。

### 6.4 JS 侧工具调用的三种形态

| 形态 | 入口 | 线程 | 备注 |
| --- | --- | --- | --- |
| 同步 | `callToolSync` → `executeTool` | JS runtime 线程 | 阻塞 JS，简单但可卡死 |
| 异步 | `callToolAsync` → `new Thread` | 独立线程 + 回调脚本 | 可并发，回调通过 pending queue 泵回 |
| 异步流 | `callToolAsyncStreaming` → `executeToolAndStream` | 独立线程 + `runBlocking` | 中间结果通过 `intermediateCallbackId` 回传 |

三种都**不检查权限**，也**不向 `ToolPkgRuntimeMonitor` 之外报告策略事件**。

---

## 7. ToolPkg 包模型

### 7.1 格式与 manifest

`.toolpkg` = ZIP；必需 `manifest.json|hjson`；可选 `packages/`、`ui/`、`resources/`、`i18n/`、`modules/*.wasm`。顶层字段（`docs/TOOLPKG_FORMAT_GUIDE.md:161-181`）：

| 字段 | 类型 | 作用 | 与安全的关系 |
| --- | --- | --- | --- |
| `schema_version` | int | 清单版本 | 结构演进 |
| `toolpkg_id` | string | 唯一 ID | 与目录/缓存/授权键相关 |
| `version` | semver | 包版本 | `requires` 的版本区间比较对象 |
| `api_version` | semver | 宿主 API 版本（1.0.0/1.0.1） | 不支持则**加载失败并提示**（`ToolPkgApiVersion.kt:102-115`） |
| `requires` | object[] | 依赖 + 加载顺序，含 `min_version`/`max_version` | 环依赖导致相关包加载失败 |
| `main` | path | 注册入口脚本 | 必须存在 |
| `subpackages[]` | object[] | 子包 id/entry/enabled_by_default | 工具声明位置 |
| `resources[]` | object[] | key/path/mime | 资源读取 |
| `wasm_modules[]` | object[] | wasm 声明与 ABI | 目前用于校验 |
| `workflow_templates[]` / `workspace_templates[]` | object[] | 注册到宿主入口的模板 | **导入模板不等于授权** |

**没有的能力声明**：没有任何字段描述“本包会访问网络/文件/root/无障碍”。因此宿主无法在安装时告诉用户“这个包能做什么”，只能在运行时用工具名 + 全局权限去兜。

### 7.2 子包与工具声明

```kotlin
data class PackageTool(name, description: LocalizedText, parameters, script, advice=false)
data class PackageToolParameter(name, description, type, required)
data class ToolPackage(name, tools, states, env, isBuiltIn, enabledByDefault, …)
```

`advice=true` 的工具不要求真实实现（`PackageManager.registerPackageTools` 过滤 `!it.advice`，`:3452`），即**“说明书工具”与“可执行工具”共用一个命名空间**。

### 7.3 状态、条件与环境变量

- `states[]`：`condition` 用 `ConditionEvaluator` 对能力快照求值（`PackageManager.selectToolPackageState:3469-3489`），可 `inheritTools`/`excludeTools`/追加工具。能力快照包含 `android.permission_level`、`android.shizuku_available` 等（`:3508-3537`）。**这是“按运行时能力选择工具集”，不是按设备型号准入**，与 MCA「通用设备准入」硬规则兼容。
- `env[]`：必填缺失则**拒绝启用**并返回可读消息（`:3300-3338`）；可选缺失用默认值。环境变量存在 `EnvPreferences`，由 `read/write_environment_variable` 工具暴露给模型（**模型可读写插件密钥**）。
- `requires`：启用时把依赖加入启用集合；被依赖的包不能单独关闭；环依赖报错（`ToolPkgLoadOrder.kt`）。

### 7.4 安装与来源

| 路径 | 入口 | 校验 |
| --- | --- | --- |
| 内置 assets | `loadToolPkgFromAsset` | 缓存签名（`buildAssetToolPkgCacheSignature`） |
| 外部文件导入 | `importPackageFileFromExternalStorage`（`:2503-2625`） | manifest/子包/资源结构、版本、API 版本、`requires` |
| 市场 | `ToolPkgMarketOrigin`、发布来源 | 记录来源与作者，展示导入提示 |
| 调试安装 | `installDebugToolPkg`（`:2736`） | 开发者通道 |
| 远端黑名单 | `PluginDenylistRepository.findDeniedImport` | 按 **sha256** 拒绝导入（`PackageManager.kt:1249-1254`） |

两个需要明确记录的事实：

1. **远端 denylist 是网络拉取的“远程关停开关”**：`PluginDenylistRepository` 从 `pointerUrl` 拉指针 + payload（8 s 超时）。它按文件哈希判定，不是按设备。对 MCA 而言，“按哈希拒绝恶意样本”是可接受的安全信号；但**必须用户可见、可解释、不得静默**，也不能变成供应商远程决定用户能装什么。
2. **缓存与重扫**：`buildToolPkgCacheSignature` 把 denylist 签名与 API 版本一起混入缓存键（`:824-856`），所以 denylist 更新会触发重扫——这是正确的一致性设计，MCA 若有扩展注册表应沿用“签名参与缓存键”的思路。

### 7.5 运行时监控（遥测，不是限制）

`ToolPkgRuntimeMonitor` 按包记录：进行中调用数、启动/完成/失败计数、最近/平均/最大时长、JS malloc 增量与峰值、120 条环形日志（单条 ≤4000 字符）。UI 用它做“插件运行时监控”。**没有任何阈值触发拒绝、降级或熔断。**

---

## 8. Android 设备控制

### 8.1 五级权限与提供者矩阵

```text
AndroidPermissionLevel = STANDARD, ACCESSIBILITY, DEBUGGER, ADMIN, ROOT
```

`ToolGetter`（`defaultTool/ToolGetter.kt`）按**用户首选等级**选实现；`ShellExecutorFactory` 缓存每级 executor 并提供 `getHighestAvailableExecutor`（ROOT→ADMIN→DEBUGGER→ACCESSIBILITY→STANDARD 逐个探测 `isAvailable && hasPermission`，找不到回落 STANDARD）。

| 等级 | 典型载体 | 能力 | 代价 |
| --- | --- | --- | --- |
| STANDARD | App 自身 | 应用私有目录、SAF、Intent、通知 | 无系统特权 |
| ACCESSIBILITY | 无障碍服务 | 读节点树、点击、输入、全局手势 | 用户需开启无障碍；节点树泄露屏幕内容 |
| DEBUGGER | ADB/Shizuku（`ShizukuAuthorizer`） | shell、`pm`、截图、虚拟屏 | 需 Shizuku/Sui；`PhoneAgent` 明确要求该级别 |
| ADMIN | 设备管理员/DeviceOwner | 改系统设置、静默安装等 | 企业级授权 |
| ROOT | libsu | 全权 | 最高风险 |

### 8.2 Shell 与命令执行

`execute_shell`、终端会话（`create/execute/input/close_terminal_session`、流式版）、`execute_hidden_terminal_command` 全部走 `StandardTerminalCommandExecutor` → `ShellExecutorFactory`。命令字符串**不做结构化校验**（没有命令白名单/参数模型），安全性完全依赖所选权限等级与用户审批。

### 8.3 UI 自动化

- `StandardUITools`：`get_page_info`（无障碍节点树 → `SimplifiedUINode`）、`tap`/`swipe`/`long_press`/`click_element`/`set_input_text`/`press_key`、`capture_screenshot`。
- `AccessibilityUITools`：节点树解析、按谓词查找、bounds 计算（`simplifyLayout`、`findNodesInXml`、`parseBounds`）。
- 私有注入走 `executeUiToolWithVisibility`：执行前隐藏悬浮窗、显示状态指示、延迟 50 ms、结束后恢复（`ToolRegistration.kt:39-59`）。**这是为了“自动化时不挡住自己”，不是安全控制。**

### 8.4 PhoneAgent（视觉子代理）

`PhoneAgent` 是一个独立的、**同一进程内**的循环：截图（`captureScreenshotForAgent`）→ 文本模型 → 解析 `do(action=…)` / `finish(message=…)` → 执行 → 下一步；依赖 Shizuku 提供虚拟屏与注入（`resolvePrivilegedExecutionState`，`:68-117`）。它的工具体是脚本化的 `do(action=click\|swipe\|type\|…)` DSL，与主工具面并行存在。

对 MCA 的意义：这是“一个 run 内再跑一个循环”的典型反例。MCA 架构 §15.3 E05 明确要求子 Agent 由同一 runtime 管理、共用预算、深度受限；third-party 的实现没有父子预算、没有深度限制、也没有把子循环的副作用纳入主审计。

---

## 9. 工作流与自动化

### 9.1 模型

```text
Workflow(id, name, nodes[], connections[], enabled, 统计字段)
WorkflowNode = TriggerNode | ExecuteNode | ConditionNode | LogicNode | ExtractNode
ExecuteNode(actionType=toolName, actionConfig=Map<String, ParameterValue>, jsCode?)
ParameterValue = StaticValue(String) | NodeReference(nodeId)
```

- `TriggerNode.triggerType ∈ {manual, schedule, tasker, intent, speech, app_open}`（`data/model/Workflow.kt:61`）。
- `ExecuteNode.jsCode` 会被 `StandardWorkflowTools` 读写/透传（`:569-584,1007-1016`），但 `WorkflowExecutor` 中**未见执行该字段的路径**：执行器只按 `actionType` 调工具。这是“存了但没执行”的字段，不是隐藏能力（本次只读核对无法排除其他调用点，标注为待复核项）。

### 9.2 执行

`executeWorkflow`（`:527`）→ `buildDependencyGraph` + `detectCycle`（`:726-800`）→ `executeTopologicalOrder`（`:801`）→ `executeNode`（`:1096`）。节点状态机为 `Pending/Running/Success/Skipped/Failed`；非 Execute 节点写 `Skipped` 并继续。

`ExecuteNode` 的执行只有一句关键代码：

```kotlin
val result = toolHandler.executeTool(tool)   // WorkflowExecutor.kt:1275
```

即**工作流是工具面的直接调用者，没有权限层、没有冻结对象、没有审批绑定**。工作流若由定时器/广播触发，用户不在场，也就不可能有人点“允许”。

### 9.3 触发与调度

- `WorkflowScheduler`：WorkManager `enqueueUniquePeriodicWork`（≥15 min）与 `enqueueUniqueWork(ExistingWorkPolicy.REPLACE)`；支持 cron 表达式与固定延迟（`:54-236`）。
- Intent 触发：`WorkflowTaskerReceiver`，文档明确 **`exported=true`，任何 App 都能发广播**，并自述“建议后续增加 permission 或校验/白名单”（`workflow_intent_trigger.md:166-169`）。
- 进度/结果：`WorkflowWorker` → `WorkflowExecutor`，统计写回 `Workflow` 字段。

**风险组合**：导出广播 + 无权限节点 + 定时器 = 外部可触发、无人值守的任意工具执行。这是本文最重要的一条“不要照搬”。

---

## 10. MCP 桥

### 10.1 消费方向（third-party 作为 MCP client）

| 形态 | 实现 | 传输 | 备注 |
| --- | --- | --- | --- |
| 本地插件 | `BridgeMcpRuntimeSession` + `MCPStarter` | stdio 桥（Node/pnpm；缺 Node 时状态 `NODEJS_MISSING`） | 依赖本机 Node 环境 |
| 远程插件 | `RemoteMcpRuntimeSession` | Kotlin SDK：`StreamableHttpClientTransport` / `SseClientTransport`（Ktor + OkHttp） | connect 15 s、request/socket 60 s |

`McpRuntimeSession` 是干净的传输无关接口：`connect/isConnected/isActive/listTools/callTool/close`（`McpRuntimeSession.kt:38-53`）。`MCPManager` 负责“选实现 + 缓存 + 重连 + 失败原因”，并且**同一时刻只有它决定具体实现**（`MCPToolExecutor.kt:519-570`）。这是本子系统里结构最好的一段，值得 MCA 对照。

### 10.2 工具发现与映射

`MCPPackage.loadFromServer` 连一次、`listTools`、把 `inputSchema.properties/required` 展平成 `MCPToolParameter`，再 `toToolPackage()` 变成宿主 `ToolPackage`。**只读了 schema 的 `properties`/`required`，没有校验 `type` 子集、嵌套对象、`$ref`、`oneOf`**——超出宿主理解的 schema 会被“尽力而为”地展平，而不是拒绝。MCA 的 `SupportedToolSchemaValidator` 是显式拒绝不支持子集，这一点 MCA 更安全。

### 10.3 调用与结果

`MCPToolExecutor.invoke`（`:263-361`）：

1. `server:tool` 拆分；2. `getOrCreateSession`；3. `isActive()`（未激活则提示用 `use_package` 先激活）；4. 参数 `associate` 成 Map → 按 schema 做 `smartConvert`；5. `callTool`；6. 结果按 `content[]`（text/image/resource）提取，图片进 `ImagePoolManager` 并转成 `<link type="image">`；7. 超过 `MAX_TEXT_RESULT_LENGTH=5000` 的结果**落盘**到 `cleanOnExitInternalDir` 并告诉模型用 `read_file_part`/`grep_code` 读取。

第 7 条是值得继承的具体做法：**大结果转文件 + 明确指引**，避免把 5 KB 以上的远端内容直接塞进上下文。

### 10.4 两个具体细节

- **参数完整性校验**：`validateParameters` 拒绝含 U+FFFD 的参数，并给出字符偏移与 UTF-8 字节偏移（`:412-461`），理由是“已经丢失的字节会让写请求把损坏内容持久化到远端”。这是一个**低成本、高价值**的检查，MCA 目前只在注释里提到 U+FFFD（`AgentUtf8Bounds.kt:9`），没有执行期拒绝。
- **`autoApprove` 是死字段**：它被持久化、导入、导出、传给插件元数据（`MCPLocalServer.kt:130,382,536,628,912`；`MCPConfigGenerator.kt:220-222`；`MCPDeployer.kt:459-478`），但本次全仓检索**未发现任何用它跳过审批的执行路径**。风险在于：一旦未来把它接到执行链，就是一个现成的持久免审批通道。MCA 必须显式写明“不存在 autoApprove 语义”。

### 10.5 暴露方向（third-party 作为 MCP server）

本轮未发现 third-party 把自身工具暴露为 MCP server 的实现；`MCPLocalServer` 是**本地 MCP 插件管理/配置持久化**，不是对外 server。这意味着 third-party 的 MCP 是单向消费。MCA 同样不应在 V1 新增 server 方向（架构 §12.2 未包含）。

---

## 11. 安全边界评估

### 11.1 信任边界

| 输入/执行者 | 实际边界 | 控制点 | 结论 |
| --- | --- | --- | --- |
| 模型输出 | 完全不可信 | XML 解析 + 参数校验 + 审批（仅聊天路径） | 审批可被 JS/工作流路径绕过 |
| ToolPkg 脚本 | **等同 App 代码** | QuickJS 堆隔离 + hook 超时 | 不是安全边界 |
| 第三方 DEX/JAR | **等同 App 代码** | 仅类名前缀隔离 | 不是安全边界 |
| MCP server | 不可信远端 | schema 展平 + 结果提取 + 参数完整性 | 无 egress 数据分级、无 schema 漂移失效 |
| 工作流 | 用户/外部广播可触发 | 无 | 无人值守的任意工具调用 |
| 广播发送方 | 任意 App（`exported=true`） | 无 | 触发面开放 |
| 插件提供的工具 schema | 可增删模型可见工具 | hook 机制 | 提示词注入面 |

### 11.2 六项具体弱点

| ID | 弱点 | 证据 | 影响 |
| --- | --- | --- | --- |
| S1 | JS `Tools.*` 直连 `executeTool`，不经审批 | `JsNativeInterfaceDelegates.kt:690,733,788` | 批准一个 `pkg:tool` 等于批准该脚本能做的**一切**；插件市场包可静默读写文件、执行 shell、发网络请求 |
| S2 | 工作流节点直连 `executeTool` | `WorkflowExecutor.kt:1275` | 定时/广播触发的自动化无人在场，无需审批 |
| S3 | `deny_tool` 文本旁路 | `ToolExecutionManager.kt:457-458` | 模型在原始输出里带该子串即跳过审批（无论作者意图） |
| S4 | 持久 `ALLOW`（含 ALWAYS_ALLOW），无作用域/过期/撤销 | `ToolPermissionSystem.kt:192-283` | 一次点击永久授权该工具名的**所有参数**；与 MCA 一次性审批语义直接冲突 |
| S5 | 任意 JVM 代码加载 + 反射桥 | `JsJavaBridge.kt:1230-1235`、`JsExternalJavaCodeLoader.kt:164`、`ThirdPartyQuickJsEngine.kt:156-173` | 进程内无隔离；崩溃/内存/权限全部共享 |
| S6 | 供应链：QuickJS 从 `master` 在构建期克隆；插件从市场/外部文件导入；远端 denylist 可远程关停 | `quickjs/src/main/cpp/CMakeLists.txt`（`thirdparty_prepare_git_source(... "https://github.com/bellard/quickjs.git" "master")`）、`PackageManager.kt:1249-1254` | 构建不可复现；准入依赖远端 | 

### 11.3 次要问题

- **单一全局权限回调**（`ToolPermissionSystem.kt:83`）：并发审批会互相覆盖。
- **工具名即权限键**：不同来源同名工具共享授权。
- **无内存/栈上限**（§6.2）：脚本可拖垮进程。
- **`withTimeout` 与真实停止脱节**（§6.2）：`JsToolManager` 的外层超时对阻塞 eval 无效，真正生效的是引擎内 `future.get` + 原生 interrupt；两个超时常量看似同一来源，语义却不同。
- **`runBlocking` 在同步 `ToolExecutor` 边界**（`ToolPackage.kt:418`、`MCPToolExecutor.kt:295,320,367`）：MCP/JS 调用会阻塞执行线程。
- **`advice` 工具与可执行工具共用命名空间**：模型无法从名字区分“说明”与“动作”。
- **工具 schema 与实现双写**（§3.2）：改一处漏一处即产生“模型按旧参数调用”的静默失败。

### 11.4 对 MCA 的硬结论

1. **审批必须在执行器之前、且是唯一入口。** 任何能让扩展或编排层直接拿到执行器的设计，都等价于 S1/S2。
2. **“批准一个工具”不能等于“批准它内部的一切”。** MCA 的 `FrozenToolCommand` 冻结的是**这一次**的规范化参数与资源，扩展没有“内部再调用”的能力，因为扩展不持有 executor。
3. **不运行任意代码**，除非明确标注为可信代码模式且用户知晓（架构 §11.1 末行、§12.3）。
4. **禁止持久 ALLOW**；只允许 `ALLOW_ONCE` 与任务级只读 grant。
5. **egress 不因“这是工具调用”而豁免**；工具结果的 `dataClass` 必须参与发送前复核。
6. **能力选择 ≠ 设备准入**：按 root/无障碍是否存在来**选择**暴露哪些工具是允许的；按设备型号/芯片阻止用户使用任何功能是禁止的。

---

## 12. 工具族 × MCA 等价物

说明：third-party 侧按注册名分组（同一名称可能跨族）；MCA 侧只列**当前源码已存在**的等价物，其余标注计划卡或“不采纳”。“已存在”指 `core/agent-contract`、`core/agent-runtime`、`app/.../agent/**` 中可定位的代码，不代表已在设备上验证。

| # | third-party 工具族 | 代表工具名 | MCA 现状与等价物 | 处置 |
| --- | --- | --- | --- | --- |
| 1 | 文件/工作区 | `list_files` `read_file(_part/_full)` `write_file` `apply_file` `edit_file` `file_exists` `file_info` `find_files` `grep_code` `grep_context` `make_directory` `move_file` `copy_file` `delete_file` `open_file` `share_file` `zip_files` `unzip_files` `read_file_binary` `write_file_binary` | ✅ `workspace.list` / `workspace.read_text` / `workspace.search_text` / `artifact.write_text`（`AgentV1ToolSpecs.kt`；`app/.../agent/tools/Workspace*Tool.kt`、`ArtifactWriteTextTool.kt`） | 已有 4 项为 V1 基线；`file_info`/`zip` 等**不新增**，除非 E 卡需要 |
| 2 | 终端 / Shell | `execute_shell` `create_terminal_session` `execute_in_terminal_session(_streaming)` `input_in_terminal_session` `execute_hidden_terminal_command` `close_terminal_session` `get_terminal_session_screen` | ❌ 无 | **E03** 门槛；默认不注册；可信代码模式需显式声明 |
| 3 | Android UI 控制 | `tap` `swipe` `long_press` `click_element` `set_input_text` `press_key` `get_page_info` `capture_screenshot` | ❌ 无 | **E04**；逐动作目标复核 + 用户系统授权 + 可撤销 |
| 4 | 应用与系统操作 | `start_app` `stop_app` `install_app` `uninstall_app` `list_installed_apps` `get_system_setting` `modify_system_setting` `get_app_usage_time` `get_notifications` `send_notification` `toast` `execute_intent` `send_broadcast` `manage_cookies` | ❌ 无（`device_info` 类只读信息可作为首个只读族候选） | E04 之后按项评估；安装/卸载/改设置必须逐项 ASK |
| 5 | 网络 / 浏览器 | `http_request` `multipart_request` `visit_web` `download_file` `browser_*`（navigate/snapshot/click/type/fill/evaluate/run_code/screenshot/tabs/…，22 项） | ⚠️ 只有 egress 治理：`AgentNetworkEgressPolicy.kt`、`AgentMcpEndpointPolicy.kt`；无通用 HTTP/浏览器工具 | **E02**；V1 只允许 provider/MCP origin，不开放任意 URL 的 form-POST |
| 6 | MCP 工具 | `serverName:toolName`（运行期注册） | ✅ `mcp:<serverId>:<name>`；`McpToolAdapter`、`AgentMcpRegistry`、`AgentMcpEndpointPolicy` | 保留；只补 schema 漂移与结果映射 |
| 7 | 包 / 插件 | `use_package` `package_proxy` `list_sandbox_packages` `set_sandbox_package_enabled` `execute_sandbox_script_direct` `read/write_environment_variable` | ⚠️ 仅 Skills（只读、只收窄）：`AgentSkillManager`、`AgentSkillToolPattern`；**没有任何 JS/脚本执行** | **E07** 决策门通过前不引入；`use_package`/模型启用插件**明确不采纳** |
| 8 | 工作流 | `create_workflow` `update_workflow` `patch_workflow` `delete_workflow` `enable_workflow` `disable_workflow` `get_workflow` `get_all_workflows` `trigger_workflow` | ❌ 无 | **E06**；节点必须复用 broker，导入工作流不得自动获批 |
| 9 | 对话 / 消息 | `send_message_to_ai(_streaming)` `create_new_chat` `switch_chat` `list_chats` `find_chat` `delete_chat` `update_chat_title` `get_chat_messages(_range)` `start/stop_chat_service` | ❌ 无 | **不采纳**：Agent 内再开聊天 = 第二 loop/第二 owner |
| 10 | 记忆 | `query_memory` `create_memory` `update_memory` `delete_memory` `get_memory_by_title` `move_memory` `link_memories` `delete_memory_link` `query_memory_links` | ❌ 无 Agent 工具（聊天侧有记忆能力） | 不在 V1；若做，需独立数据类别与删除审计 |
| 11 | 角色卡 | `create/update/delete/get/list_character_card` `list_character_cards_settings` `set/clear_active_character_card` `import/export_character_card_to_tavern_json` | ❌ 无 | **不采纳** |
| 12 | 模型配置 | `create/update/delete/list_model_config` `test_model_config_connection` `get/set_function_model_config` `list_function_model_configs` `call_chat_model` | ❌ 无（MCA 的模型绑定在 run 创建时冻结） | **不采纳**：Agent 不得改自己的模型绑定或调用第二个模型循环 |
| 13 | 语音 / 音乐 | `get/set_speech_services_config` `test_tts_playback` `music_play/pause/resume/stop/seek/set_volume/status/play_queue` | ❌ 无 | **不采纳（V1）** |
| 14 | 蓝牙 | `bluetooth_*`（19 项：scan/connect/send/read/listen/ble_*）`request_bluetooth_permission` `request_enable_bluetooth` `get_bluetooth_state` `list_bluetooth_bonded_devices` | ❌ 无 | **不采纳（V1）**：涉及邻近设备与权限提升 |
| 15 | 媒体 / FFmpeg | `ffmpeg_info` `ffmpeg_convert` `ffmpeg_execute` | ❌ 无 | 不在 V1；若做，属本地 CPU 重任务，需独立配额 |
| 16 | UI 子代理 | `run_ui_subagent` `agent_status` `close_all_virtual_displays` | ❌ 无 | **E05**：最多 2 child、深度 1、共用父预算 |
| 17 | 杂项 | `calculate` `sleep` `device_info` `get_device_location` `update_user_preferences` `update_user_profile` `trigger_tasker_event` `restart_mcp_with_logs` | ⚠️ 部分：`workspace.*` 之外无；`device.info` 可作只读扩展 | 只读、无副作用者优先；其余按需 |

**统计**：third-party 注册项 180；MCA 当前内建 4 + MCP 命名空间 1 类。这不说明 MCA 落后，而是说明“工具数量”不是可比较指标——按名称分组，third-party 的 180 项里约一半（约 92 项）落在 MCA 明确不采纳的域（对话/记忆/角色卡/模型配置/语音音乐/蓝牙/媒体/UI 子代理/插件执行/工作流）。

---

## 13. 采纳原则（不可妥协的十条）

| # | 原则 | 具体含义 | 对应 third-party 的反面 |
| --- | --- | --- | --- |
| P1 | 单一 choke point | 任何工具调用只能经 `AgentPermissionBroker.decide` → 冻结 → `authorize` → executor；没有第二条 `executeTool` 入口 | S1、S2 |
| P2 | 扩展不持有执行器 | Skill/MCP/未来扩展只贡献 descriptor（含 schema/hash/sideEffect/dataClass）与**纯函数式** adapter；不得拿到 host 句柄、Context、仓储或网络通道 | `Tools.*` 直连、`package_proxy` |
| P3 | 审批只 ALLOW_ONCE | 不允许持久 ALLOW、不允许 “always allow”、不允许 `autoApprove` 语义；任务级 grant 仅限只读范围 | S4、`autoApprove` |
| P4 | 冻结即不可变 | 审批前完成默认值、路径规范化、资源解析；审批后只执行冻结对象；`argsHash`/`schemaHash`/`resourceFingerprint` 绑定 | third-party 审批只绑工具名 |
| P5 | egress 与工具解耦但强制 | 工具结果带 `dataClass`；发送云/MCP 前按 run grant 复核来源；工具调用不是豁免理由 | third-party 无数据分级 |
| P6 | 能力选择 ≠ 设备准入 | 按 root/无障碍/Shizuku **是否存在**选择工具集是允许的；按设备型号/芯片阻止任何用户可见动作是禁止的 | third-party 用 `states` + 能力快照（这部分可借鉴） |
| P7 | 结果大小与时间有界 | 每个工具 `timeoutMillis`；结果超出阈值转产物 + 指引，不静默截断 | `MAX_TEXT_RESULT_LENGTH` 落盘（可借鉴）；1800 s 超时（不可借鉴） |
| P8 | 失败语义是事实 | 多段结果不得拼接后升级成功；未知副作用记 UNKNOWN | `aggregateToolResults` 取最后一段 |
| P9 | 任意外部代码必须显式声明 | 未隔离的脚本/终端只能标“可信代码模式”，UI 明示；不得宣称 broker 能阻止旁路 | `DexClassLoader` + 反射桥 |
| P10 | 证据分级 | 设计、接线、真机、供应链分别标注 PASS/NOT_RUN；不以“实现存在”代替验证 | third-party 文档以说明与实现为主，未采用 PASS/NOT_RUN/BLOCKED 证据格式 |

---

## 14. 分阶段采纳计划

### 14.0 阶段依赖

```text
P0 事实与反例（文档/fixture）
 └─ P1 审批管线闭环（MCA 现有缺口，最高优先）
     ├─ P2 内建只读工具族扩展
     ├─ P3 MCP 收敛（schema 漂移 / 结果映射 / 完整性）
     ├─ P4 E03 脚本与终端
     ├─ P5 E04 Android 控制
     ├─ P6 E06 工作流与调度
     └─ P7 E07 插件/DSH Host（仅决策门通过后）
```

P2/P3 可与 P1 并行做**设计**，但**不得在 P1 验收前把任何新工具接入生产执行链**——否则新工具会复制当前“审批未接线”的 fail-closed 到更多面，掩盖真实缺口。

### 14.1 P0 — 事实台账与反例（纯文档/fixture）

| 项 | 内容 |
| --- | --- |
| 目标 | 把本文 §5.2、§11.2 的旁路变成**可执行的反例 fixture 清单**，供后续每张卡复用 |
| 交付 | ① 本文件；② `THIRD_PARTY` 术语与工具族映射（§12）；③ 反例清单：JS 直连、工作流直连、`deny_tool`、持久 ALLOW、`autoApprove`、schema 漂移、U+FFFD、超大结果、`mcp:` 名称碰撞 |
| 退出条件 | 清单每条都能指向 MCA 的一段代码或一个测试位点；无代码改动 |
| 不做 | 不引入任何 third-party 依赖、不复制其源码、不改 MCA 契约 |
| 回退 | 删除本文件即可 |

### 14.2 P1 — 审批管线闭环（先补缺口，再谈扩展）

| 项 | 内容 |
| --- | --- |
| 事实 | `AgentPermissionBroker` 与 `AgentToolPermissionHook` 的 KDoc 均自述：生产路径只调 `decide`，不落 `agent_invocations` 行，resume 不调 `authorize`；MCP/外部调用因此 fail-closed 为 typed DENIED（`AgentPermissionHook.kt:17-26`；`AgentPermissionBroker.kt:28-34`） |
| 目标 | 让 ASK 路径真正可用：`decide → 持久 pending → UI 单 → resolveApproval → authorize(一次性消费) → executor → 记录` |
| 范围 | `app/.../agent/composition/RoomAgentApprovalStore.kt`、`AgentPermissionBroker.kt`（接线不改语义）、`core/agent-runtime` 的 resume 路径、`feature/agent-workspace` 审批单 |
| 必须保持 | 只 `ALLOW_ONCE`；5 min TTL；`argsHash/schemaHash/resourceFingerprint/runEpoch/policyVersion` 绑定；重复点击幂等；拒绝循环 → `PERMISSION_DENIED_LOOP` |
| 验收 | 批准后执行一次；重复批准不执行第二次；换参数/换 schema/换 epoch 全部失效；进程重启后 pending 不可执行；无 `agent_invocations` 行时绝不执行 |
| 不做 | 不加 `allow_for_session` / “记住我的选择” / 模型可读的授权状态 |
| 回退 | 关闭 ASK 工具注册，恢复 fail-closed（V1.1 当前行为即为安全退路） |

### 14.3 P2 — 内建只读工具族扩展

| 项 | 内容 |
| --- | --- |
| 候选族 | ① `device.info.read`（型号/ABI/SDK/屏幕/存储/权限状态，**只读、LOCAL_ONLY**）；② `workspace.stat`（存在性/大小/sha256）；③ `artifact.list`（产物清单） |
| 目标 | 用最小风险证明“新增工具族 = 新增 descriptor + executor，不改 broker 语义” |
| 依据 | third-party 的 `states` + 能力快照值得借鉴：工具集按**运行时能力**（如是否有 root/无障碍）选择，而非设备型号；这与项目既有的“按能力排序/选择、不按设备阻断用户可见入口”规则一致 |
| 必须保持 | `AgentToolDescriptor.sideEffect=READ_ONLY`、`dataClass=LOCAL_ONLY`（或 PRIVATE）、`timeoutMillis` 有值；全部经 `AgentToolCatalogAssembler` 的 `BUILT_IN` 源装配；冲突码复用（`DUPLICATE_TOOL_KEY` 等） |
| 验收 | schema 子集校验通过；越界路径拒绝；结果超阈值转 artifact + `truncated=true`；egress 默认不外发 LOCAL_ONLY |
| 不做 | 不做通用 `http_request`；不做任意路径 `list`；不新增第二套 catalog |
| 回退 | 从 assembler 的 built-in 列表移除即恢复 |

### 14.4 P3 — MCP 收敛（不扩传输）

| 项 | 内容 |
| --- | --- |
| 目标 | 补齐 third-party 已经暴露、MCA 尚未闭环的三个点 |
| 具体 | ① **schema 漂移使审批失效**（架构 X09）：`tools/list` 返回的 schemaHash 变化 → 递增 policyVersion → 旧 approval 变 INVALIDATED；② **结果块映射**：`content[]` 的 text/structuredContent 分开处理，未知块显式标记，不拼成控制协议；③ **参数完整性**：拒绝含 U+FFFD 的规范化参数（借鉴 `MCPToolExecutor.kt:412-461`），错误码稳定、含偏移 |
| 依据 | MCA 已有 `McpJsonRpc`、`McpSseParser`、`McpToolAdapter`、`AgentMcpRegistry`；只改判定，不改传输 |
| 必须保持 | `mcp:<serverId>:<name>` 命名空间；远端 annotations 只作提示；`EXTERNAL_SIDE_EFFECT/ASK`；断线不重放有副作用调用 |
| 验收 | schema 变 → 旧审批失效；U+FFFD → 拒绝且不落库；未知 content 块 → 明确 unsupported 摘要；超大结果 → 有界 + `truncated` |
| 不做 | 不加 stdio、不加旧 SSE 兼容、不加 OAuth、不做 MCP server 方向 |
| 回退 | 逐个判定可独立关闭（各一个 flag 级 seam，不新增模块） |

### 14.5 P4 — E03 脚本/终端（门槛式）

| 项 | 内容 |
| --- | --- |
| 前置 | 架构 §15.3 E03 全部条件；**且** P1 已验收 |
| 定位 | 独立 `ExecutionHostOwner`，进程组/会话/IO 有界；主 broker 只负责“是否允许启动”，不宣称能约束其内部 |
| third-party 对照 | 可借鉴：`ShellExecutorFactory` 的分级选择、`getHighestAvailableExecutor` 的探测顺序、`ToolPkgRuntimeMonitor` 的时长/内存采样；**不可借鉴**：同进程 `DexClassLoader`、反射桥、1800 s 超时、无内存上限 |
| 必须声明 | “可信代码模式”：用户明确知道该执行环境可绕过本机策略；broker 只约束启动参数与产物回收 |
| 验收 | timeout/kill 命中整个自有进程组；崩溃不污染主进程；产物只落 workspace；退出码/信号语义确定 |
| 不做 | 不为运行 Node/Python 降低 targetSdk；不做 PRoot 式“伪沙箱” |
| 回退 | 不注册该工具族即回到纯内建 |

### 14.6 P5 — E04 Android 控制（门槛式）

| 项 | 内容 |
| --- | --- |
| 前置 | E04 条件；用户系统授权（无障碍/Shizuku）；普通设备不得因此失去文件 Agent |
| third-party 对照 | 借鉴：五级权限抽象、按级选实现、动作前“隐藏自身 UI + 状态指示”（`executeUiToolWithVisibility` 的思路）；**不借鉴**：无目标复核的 `tap(x,y)`、把节点树整棵交给模型、`run_ui_subagent` 式内嵌第二循环 |
| 设计要点 | 每次动作绑定 `app/window/display/snapshotVersion`；执行前重核目标；敏感动作（支付/安装/删除/权限变更）逐项确认；授权撤销/锁屏/前台变化使旧动作拒绝 |
| 验收 | 目标漂移 → 拒绝而非误点；授权撤销后旧请求不执行；无障碍关闭 → 明确不可用而非静默失败 |
| 回退 | 关闭平台 adapter 即恢复无设备控制 |

### 14.7 P6 — E06 工作流/调度（门槛式）

| 项 | 内容 |
| --- | --- |
| 前置 | E06 条件；P1 已验收 |
| third-party 对照 | 借鉴：DAG + 拓扑 + 环检测 + 显式 Skipped/Failed 状态、WorkManager 唯一任务；**不借鉴**：节点直接 `executeTool`、`exported=true` 的触发广播、无人在场的自动批准 |
| 设计要点 | 每个执行节点复用现有“冻结→broker→executor”链；无人值守只能使用无需审批的只读能力，或使用**预先一次性确认过**的任务级只读 grant；导入的工作流不得自动获得批准；定时触发在批准过期后不得自动重放 |
| 验收 | 重复触发无重复副作用；中断后可核账；过期批准不被自动使用；外部无法触发 |
| 回退 | 停用调度入口，保留已创建工作流的只读展示 |

### 14.8 P7 — E07 插件/DSH Host（仅决策门通过后）

| 项 | 内容 |
| --- | --- |
| 前置 | 架构 §12.3 五项门全部通过（真实需求清单、ABI/target 验证、版本/许可/校验、工具桥与授权合同、隔离证明） |
| 硬约束 | 不得把 Skill 导入包装成插件兼容；不得让 Host 拥有第二个 Agent Loop；不能安全承载的插件明确标记不支持 |
| 与本文关系 | 本文只提供反例（S1/S5/S6），不提供实现方案 |

---

## 15. 风险登记与停止条件

| ID | 风险 | 影响 | 措施与停止条件 |
| --- | --- | --- | --- |
| R1 | 在 P1 前扩工具族 | 新工具复制“审批未接线”，并让 fail-closed 看起来像“已支持” | P1 未验收则停止 P2 之后的任何执行链接入 |
| R2 | 借“兼容 third-party 插件”引入进程内 JS | 直接违反架构 §11.1 末行与 §12.3 | 出现任何“插件直接调 executor”的设计即停止该卡并回退设计 |
| R3 | 审批绑定被削弱（按工具名、按会话） | 参数替换、越权重放 | 代码评审出现“工具名级授权”即阻断 |
| R4 | 能力探测变成设备准入 | 违反“不按设备型号/芯片阻断用户可见能力”的既有规则 | 出现“按型号/芯片隐藏入口”即阻断并回退 |
| R5 | 结果/日志泄露私有内容 | 隐私事故 | 结果超限必须落产物而非打印；日志只留 fingerprint |
| R6 | MCP schema 漂移未失效 | 旧审批执行新 schema | X09 未闭环前，MCP 工具保持每次 ASK |
| R7 | 工作流/定时绕过审批 | 无人值守副作用 | 触发面不得 exported；节点必须经 broker |
| R8 | 文档漂移 | 后续实现照搬过期结论 | 每次复用前重读 third-party 当前 commit 与 MCA 当前源码，不引用本文行号作为合同 |

---

## 16. 明确不采纳的 third-party 模式

| 模式 | 位置 | 不采纳理由 |
| --- | --- | --- |
| 持久 `ALLOW` / ALWAYS_ALLOW | `ToolPermissionSystem.kt:268-283` | 与一次性审批冲突；无法区分参数与资源 |
| 单工具名级权限键 | `:78,192-207` | 同名不同源共享授权；无作用域 |
| `deny_tool` 文本旁路 | `ToolExecutionManager.kt:457-458` | 模型可控的审批开关 |
| JS `Tools.*` 直连执行器 | `JsNativeInterfaceDelegates.kt:690` | 插件旁路（no-plugin-bypass 直接禁止） |
| 工作流节点直连执行器 | `WorkflowExecutor.kt:1275` | 无人值守无审批 |
| 进程内 `DexClassLoader` + 反射桥 | `JsJavaBridge.kt:1230`、`ThirdPartyQuickJsEngine.kt:156` | 任意代码，无隔离 |
| 模型可控的 `use_package` 自动激活 | `AIToolHandler.kt:315-331` | 模型写对名字即可启用插件 |
| 插件 hook 增删模型可见工具 | `EnhancedAIService.kt:2999` | 提示词注入面 |
| `autoApprove` 配置字段 | `MCPLocalServer.kt:130` 等 | 目前是死字段，但语义危险，MCA 不引入 |
| 导出广播触发自动化 | `WorkflowTaskerReceiver`（`exported=true`） | 任意 App 可触发 |
| 远端 denylist 作为准入门 | `PackageManager.kt:1249` | 可作安全信号，但必须用户可见、不得静默关停 |
| 构建期从 `master` 克隆 QuickJS | `quickjs/src/main/cpp/CMakeLists.txt` | 不可复现构建 |
| 1800 s 脚本超时 + 无内存上限 | `JsTimeoutConfig.kt`、`quickjs_jni.cpp` | 资源边界缺失 |
| 多段结果聚合升级为 success | `ToolExecutionManager.kt:710-734` | 违反“状态是事实” |

---

## 17. 证据索引

### 17.1 third-party（路径相对 `thirdparty-src/`；行号为本次快照）

| 主题 | 位置 |
| --- | --- |
| 注册表与执行 | `app/src/main/java/com/ai/assistance/thirdparty/core/tools/AIToolHandler.kt:45,179,303,363,419,479` |
| 内置注册（180 项） | `app/src/main/java/com/ai/assistance/thirdparty/core/tools/ToolRegistration.kt:36,103,221` |
| 工具 schema | `app/src/main/java/com/ai/assistance/thirdparty/core/config/SystemToolPrompts.kt` |
| 审批 | `app/src/main/java/com/ai/assistance/thirdparty/ui/permissions/ToolPermissionSystem.kt:34,62,78,192,212,268` |
| 审批结果/悬浮窗 | `ui/permissions/ToolPermissionCheckResult.kt`、`ui/permissions/PermissionRequestOverlay.kt` |
| 聊天执行链 | `app/src/main/java/com/ai/assistance/thirdparty/api/chat/enhance/ToolExecutionManager.kt:429,504,621,668,710` |
| 聊天编排 | `app/src/main/java/com/ai/assistance/thirdparty/api/chat/EnhancedAIService.kt:903,1858,2072,2120,2882,2999` |
| XML 协议说明 | `docs/doc-src/feature-protocol/toolcall_xml_conversion.md` |
| JS 引擎 | `core/tools/javascript/JsEngine.kt:61,788,1307,1500,2134`、`JsToolManager.kt:29,45,351,393` |
| QuickJS 原生 | `quickjs/src/main/cpp/quickjs_jni.cpp:276,281,315,587`、`quickjs/src/main/cpp/CMakeLists.txt`、`quickjs/src/main/java/…/ThirdPartyQuickJsEngine.kt:26,53,156` |
| JS 桥 | `core/tools/javascript/JsNativeInterfaceDelegates.kt:674,707,758`、`JsJavaBridge.kt:1230`、`JsExternalJavaCodeLoader.kt:164`、`JsLibraries.kt` |
| 包模型 | `core/tools/ToolPackage.kt:300,340,352,374`、`core/tools/packTool/PackageManager.kt:87,1249,3300,3450,3469` |
| 包格式/版本/依赖 | `docs/TOOLPKG_FORMAT_GUIDE.md:161-231`、`packTool/ToolPkgApiVersion.kt:74-115`、`packTool/ToolPkgManifestRequirement.kt`、`packTool/ToolPkgLoadOrder.kt` |
| 包监控 | `core/tools/packTool/ToolPkgRuntimeMonitor.kt` |
| 设备权限 | `core/tools/system/AndroidPermissionLevel.kt`、`defaultTool/ToolGetter.kt`、`system/shell/ShellExecutorFactory.kt`、`system/ShizukuAuthorizer.kt` |
| UI 控制 | `defaultTool/standard/StandardUITools.kt`、`defaultTool/accessbility/AccessibilityUITools.kt`、`system/action/ActionManager.kt` |
| UI 子代理 | `core/tools/agent/PhoneAgent.kt:68,355`、`agent/VirtualDisplayManager.kt` |
| 工作流 | `core/workflow/WorkflowExecutor.kt:527,801,1096,1275`、`WorkflowScheduler.kt:54,121,174`、`data/model/Workflow.kt:55,70`、`docs/doc-src/feature-protocol/workflow_intent_trigger.md:166-169` |
| MCP | `core/tools/mcp/MCPToolExecutor.kt:263,412,465`、`McpRuntimeSession.kt:7,38`、`data/mcp/plugins/RemoteMcpRuntimeSession.kt:21-60`、`core/tools/mcp/MCPPackage.kt:133`、`data/mcp/MCPLocalServer.kt:116-210`、`data/mcp/plugins/MCPStarter.kt:27-44` |

### 17.2 MCA（本仓库；用于对照，均只读）

| 主题 | 位置 |
| --- | --- |
| 工具契约 | `core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/AgentToolPort.kt` |
| 冻结与审批类型 | `core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/AgentInvocationTypes.kt` |
| 内建工具规范 | `core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime/validation/AgentV1ToolSpecs.kt` |
| schema 校验 | `core/agent-runtime/src/main/java/com/muyuchat/core/agent/runtime/validation/SupportedToolSchemaValidator.kt`、`AgentArgumentChecker.kt`、`AgentLogicalPaths.kt` |
| 权限判定 | `app/src/main/java/com/muyuchat/mca/agent/security/AgentPermissionBroker.kt`、`AgentPermissionTypes.kt`、`AgentFrozenCommandFactory.kt` |
| egress | `app/.../agent/security/AgentNetworkEgressPolicy.kt`、`AgentEgressGrantStore.kt`、`AgentEgressOrigins.kt` |
| MCP | `app/.../agent/extensions/mcp/AgentMcpRegistry.kt`、`AgentMcpEndpointPolicy.kt`、`McpToolAdapter.kt`、`AgentMcpEgressGuard.kt` |
| 目录装配 | `app/.../agent/tools/AgentToolCatalogAssembler.kt`、`AgentToolCatalog.kt` |
| 权限接线缝 | `app/.../agent/composition/AgentToolPermissionHook.kt`、`RunScopedAgentToolCatalogProvider.kt`、`RoomAgentApprovalStore.kt` |
| 预算 | `core/agent-runtime/.../run/AgentBudgetPolicy.kt` |
| 架构合同 | `docs/proposals/agent-v1/MCA_AGENT_V1_ARCHITECTURE.md` §8、§11、§12、§15.3 |

---

## 18. 未验证项与后续动作

| 项 | 状态 | 说明 |
| --- | --- | --- |
| third-party 构建/运行 | `NOT_RUN` | 未执行 Gradle/native 构建；`quickjs` 需构建期联网克隆 |
| third-party 工具/Pkg 实测 | `NOT_RUN` | 未安装 APK、未导入任何 `.toolpkg` |
| third-party 设备控制 | `NOT_RUN` | 未连接设备；Shizuku/root/无障碍均未验证 |
| third-party 工作流/调度 | `NOT_RUN` | 未触发广播、未运行 Worker |
| third-party MCP | `NOT_RUN` | 未连接任何 server |
| `ExecuteNode.jsCode` 是否有其他执行点 | 待复核 | 本次只读检索执行器与 workflow 包，未发现执行路径；不能据此断言全仓不存在 |
| third-party 许可证结论 | 未做 | 仓库 metadata 声明 LGPL-3.0；本文不构成法务意见 |
| MCA P1 审批闭环 | `NOT_RUN` | 本文只描述计划 |
| MCA 任一 E 卡 | `NOT_RUN` | 未进入实施 |

**下一步（建议顺序）**：

1. 把 §11.2 六项弱点转成 MCA 的负向测试 ID，先写会失败的 fixture（P0）。
2. 评审 P1 的接线范围（只接线、不改语义），确认 `agent_invocations` 落库与 `authorize` 消费的边界（P1）。
3. 让 P2 的只读族走通完整链，作为“新增工具不改 broker”的样板（P2）。
4. 复核 `ExecuteNode.jsCode` 与 third-party 浏览器族的真实后端，补 §18 的待复核项（如后续 E02/E06 需要）。
