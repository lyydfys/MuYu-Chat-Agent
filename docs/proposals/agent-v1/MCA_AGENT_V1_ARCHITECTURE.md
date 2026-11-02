# MCA 原生 Agent V1：架构与执行合同

版本：V1.0 设计冻结候选稿  
日期：2026-10-19（源码与评审核查跨 10 月 18–19 日）  
实质源码审查基线：f72d2b315b128e823faf3f31093e48db674bc8ee  
交付复核 HEAD：16f65661efb3daeeb5cb97bd510ab28e4a08bbb9  
状态：方案；没有实施或运行生产验收。本文中的 S 级是交付目标，不是现有产品评分。

## 0. 阅读顺序、效力与交付边界

本方案由三份配套文档构成：

1. 本文：产品范围、架构决策、协议、状态、资源、数据、安全和扩展合同。
2. [执行包](MCA_AGENT_V1_EXECUTION_PACK.md)：按依赖排序的任务卡、文件写集、测试与回退点。
3. [验收矩阵](MCA_AGENT_V1_ACCEPTANCE_MATRIX.md)：测试 ID、量化指标、证据格式和阶段放行条件。

实施者先读适用的本地指令与 [MCA 架构硬锁](../../architecture/MCA_PROJECT_HARD_LOCK.md)，再读三份方案。硬锁优先；本文不修改硬锁、不宣布历史欠债已经消除。源码发生变化时先更新事实基线，不能机械套用历史行号、数据库版本和文件统计。

用户授权的是规划。本文中的生产改动、Spike、测试、ADR 落地和新模块均为后续实施任务，本轮没有实施。真实设备、模型、取消和升级证据一律标为 NOT_RUN，不能从文档推导为 PASS。

设计目标是让接手 Agent 的实现选择明确、变更范围有限、错误可被测试发现。任何文档都不能保证任意模型质量和任意实现者零错误；本方案使用依赖门槛、确定性测试和真实验收约束结果。

本轮只新增本目录三份文档。交付复核时，两个 HEAD 之间仅 AppStartupScreen.kt 与 McaBrandMarks.kt 有提交差异，属于并行启动页/品牌工作，未涉及本文模型、导航接入或数据库合同；这项差异检查不冒充重新全量源码审查。审查过程中另有 AppStartupViewModel.kt 及辅助脚本的并行工作区变化，与既有 tokenizers 子模块补丁均不属于本方案；不把它们归为本轮改动，也不覆盖或清理。后续执行者必须重新记录实际工作区，而不是将上述 HEAD 当成干净构建来源。

### 0.1 不可混用的三个“完成”

| 完成类别 | 必要条件 | 不代表什么 |
| --- | --- | --- |
| 设计完成 | 决策、接口、状态、默认值、任务与验收一致，源码事实可定位 | 不代表代码接线或真机通过 |
| V1 产品完成 | 本文 V1 范围全部实施，所有适用硬门槛 PASS | 不代表全部 DSH/Cordis 插件兼容 |
| DSH 能力域对标完成 | 对标矩阵对应能力逐项通过实测 | 不以“支持 Skills/MCP”代替终端、浏览器或设备控制 |

## 1. 对 DeepSeek 评审的分析与修正

核心批评成立：上一版把关键接入点留作“必须确认”，本地工具调用、资源竞争和迁移细节没有形成足够具体的执行合同。以下为本次采纳与修正结果。

| 评审意见 | V1 处理 |
| --- | --- |
| 需要真实 owner/lease 映射 | 采纳。第 5 节给出当前事实、接入链、并发矩阵和 P0 证据要求 |
| 本地工具调用未接线是最高风险 | 采纳。V1 默认使用 PROMPTED_V1；NATIVE 作为独立、实测后开放的能力 |
| 任意 Agent 必须共用一个 supervisor | 收窄为“同一进程、同一现有执行域复用现有权威”。supervisor 是身份/终态权威，不自动等于设备全局互斥锁；跨进程不能共享 Kotlin 单例 |
| Agent 应直接调用 McaInferenceService | 采用更窄的现有模型执行 funnel：app adapter → 现有 ChatExecutionFacade/ChatExecutionOwner → McaInferenceService。不经过 LocalApiRuntime 的聊天 UI hooks，不绕过已有 owner |
| JSON AST、Flow 依赖未决 | 采纳。契约边界使用 JSON 字符串；观察接口使用 Flow，显式依赖 coroutines-core；严格 JSON 解码只在 runtime 内部 |
| Room 固定 v28 | 改为实施时 N→N+1；本次源码 N=27，若基线未变化才是 27→28 |
| “credential vault”不准确 | 改用实际 CloudApiStore 的 Android Keystore AES-GCM 机制；不新造第二套云密钥存储 |
| 全部取消统一 ≤2 秒 | 拆为 UI 反馈、取消命令送达、真实停止、lease 释放四项。native 停止阈值须按后端实测，超时不能假装释放 |
| 10 次成功 ≥8 次就是架构通过 | 将模型任务成功率与执行安全分开。权限、身份、恢复、数据完整性要求 100%；80% 只用于指定模型/任务集的试验质量基线 |
| reasoning_content 应回传 | 按 provider 合同保存必要的不透明 continuation/reasoning item；不展示或编造内部推理，不把 reasoning 文本当工具控制协议 |
| 先完成迁移测试再写生产代码 | 区分“设计 P0”与未来“技术 Spike”。迁移测试、schema 和 native 证据需要实际实现，不能在规划阶段制造通过记录 |
| P0 就一次性补齐全局调度器 | 保留最小接入原则。只补 Agent 接入必需的共享资源缺口；不授权全应用调度重写或旧入口并发语义变化 |
| 更多门禁和 LOC 预算 | 采纳，但行数用于职责复核，不靠拆文件、套接口或巨大新 Manager 制造可维护性 |
| Agent Local API 未定义 | V1 不新增 Agent HTTP/Local API 入口；现有 API 回归仍是必测项 |

评审提到的外部 issue 编号是风险线索，未单独核验其全部上下文。本文的安全合同基于 MCA 当前源码和一般协议风险，不把 issue 标号当成已证实的 MCA 缺陷。

## 2. 产品范围与默认决策

### 2.1 用户可见定义

新增 MCA 原生 Agent 页面，独立于聊天和图片；入口位于左侧栏“图片”下方。支持选择 MCA 已配置的本地模型或云模型，在可见工作区内完成多步任务、调用工具、请求授权、产生文件，并能中止、查看历史和恢复中断任务。

默认模式为“本地离线”；用户明确切换后可使用云模型和联网工具。手机本地指执行器、任务数据、模型推理和所选工具都在手机完成；调用云模型或远程 MCP 的任务会明确显示联网。

本地文件任务闭环不依赖账号、远端服务、MCP 或 Node 环境；这些服务不可用不能阻止用户运行本地模型与内置工具。云端适配是可选使用路径，不是离线模式的隐式兜底。

### 2.2 V1.0 必须交付

- 原生 Compose 独立页面、任务列表/详情、事件时间线、资料与产物、模型绑定。
- MCA 本地模型 PROMPTED_V1 工具循环；至少 MNN 和 GGUF 各一条真实路径。
- MCA 既有云模型配置接入；已验证的 Responses 原生工具调用路径，以及明确标识的 prompted 兼容路径。
- 单一 Agent 任务 owner；独立任务数据；准确取消、预算、审批、中断恢复。
- 私有工作区、资料导入、文件读取/搜索、审批后产物写入、SAF 导出。
- Skills 文档导入与启停；MCP Streamable HTTP 工具接入的限定兼容范围。
- 资源竞争保护、离线强制策略、工具 schema 校验、日志与通知隐私。

### 2.3 后续能力，有设计接口但不在 V1.0 冒充完成

浏览器操作、现有生图能力的 Agent 工具适配、本地 MCP stdio host、终端/PRoot、Android 无障碍/Shizuku、子 Agent、任务工作流、定时自动化、DSH Cordis JS 插件 Host。

V1 不预建没有生产调用者的空接口。后续能力只保留必要的工具/模型/权限扩展点，进入相应阶段时再建具体实现。

### 2.4 固定产品决策

| 项目 | V1 决策 |
| --- | --- |
| 现有智能调参 | AppTab.AGENT 和 feature:agent 保留原职责 |
| 新导航标识 | AppTab.AGENT_WORKSPACE |
| 新入口名称 | Agent；无障碍名称“打开 Agent 工作区” |
| 同时活动 Agent run | 每个主应用进程最多 1 个；最多 5 个显式排队任务 |
| 工具并行 | V1 串行，每次模型响应最多执行 1 个工具调用 |
| Agent 外部 API | V1 不新增 |
| 操作权限 | 私有工作区读取在任务创建时明确授予；写入/联网/外部副作用逐项授权 |
| 自动续跑 | 页面切换可继续；进程死亡、设备重启后不静默续跑 |
| 模型自动切换/云端兜底 | 禁止；必须由用户确认新的绑定 |
| 系统备份 | 沿用现有数据库与凭据排除规则；Agent 显式导出独立实现 |
| 插件兼容声明 | 按经过测试的工具、schema 子集、transport 和版本列出 |

## 3. 可维护性设计与模块边界

### 3.1 三个新增模块

V1 使用三个有明确编译隔离收益的模块，不按每种工具继续分模块：

| 模块 | 内容 | 直接依赖 |
| --- | --- | --- |
| :core:agent-contract | 稳定 DTO、错误、能力、只读查询和命令/模型/工具/仓储端口 | :core:inference-contract、kotlinx-coroutines-core |
| :core:agent-runtime | 纯 Kotlin 编排、状态机、预算、上下文、严格协议/参数校验 | agent-contract、coroutines-core、严格 JSON parser |
| :feature:agent-workspace | Route、独立页面 VM、UI 投影、界面组件与交互状态 | agent-contract、designsystem、现有 AndroidX/Compose 依赖 |

三个模块可沿用仓库 Android library 构建插件，源码契约/runtime 不引入 Android API。这样避免新引入 JVM plugin 工程改造，同时以 import/依赖门禁保持平台无关。没有必要为了“纯 Kotlin”改造既有构建体系。

feature 不依赖 agent-runtime，因此无法直接创建执行 owner。app 依赖三个模块并注入窄的查询/命令端口。runtime 不依赖 app、feature、core:engine、native、LocalApiRuntime、Room、OkHttp 或 Android Context。

~~~mermaid
flowchart TD
    UI[独立 Agent 页面] --> C[agent-contract 窄命令与查询]
    API[未来后台入口] -.-> C
    C --> R[agent-runtime / AgentRunOwner]
    R --> P[模型 / Journal / 工具端口]
    P --> A[app 平台 adapters]
    A --> L[现有本地 facade / engine / lease]
    A --> H[既有云 provider + Agent 网络策略]
    A --> D[Room / 私有文件 / SAF]
    A --> B[PermissionBroker / MCP]
~~~

图中表示运行时调用方向；Gradle依赖由app指向contract/runtime/feature并完成注入，不能把adapter实现反向加到纯runtime依赖中。“未来后台入口”不是V1新增Local API。

### 3.2 平台实现归属

平台适配放在 app 的 agent 子包，按真实职责划分：

~~~text
app/.../agent/composition/   作用域、工厂、app/service 连接
app/.../agent/data/          Room DAO/entity/repository、payload、导出/导入
app/.../agent/inference/     MCA local/cloud adapter、模型能力/绑定映射
app/.../agent/security/      PermissionBroker、egress、grant 校验
app/.../agent/tools/         文件与检索等 Kotlin executor
app/.../agent/platform/      SAF、FGS、通知、进程恢复
app/.../agent/extensions/    Skill 安装、MCP transport
~~~

这些是文件归属，不能先生成空目录和所有占位类。一个切片只增加本切片生产调用者所需的类型。

### 3.3 状态和生命周期唯一性

| 事实 | 权威 | 其他层权限 |
| --- | --- | --- |
| Agent run 状态/步骤推进 | AgentRunOwner 经 AgentJournal 原子提交 | UI/工具只能发送命令 |
| Agent 当前持久状态 | Room Agent 表与对应事务序号 | 页面投影只读；内存缓存可重建 |
| 本地模型请求身份和终态 | 现有 production supervisor/ChatExecutionOwner | Agent 保存映射，不替代其结算 |
| 云模型 attempt 和终态 | AgentRunOwner 的 attempt 子 Job + AgentJournal | provider 只拥有该 Job 的 HTTP Call，不假定云端经过本地 supervisor |
| native load/unload/generate | 现有 MCA 推理服务及 lifecycle lease | Agent 不持有 native handle |
| 工具审批和资源范围 | app AgentPermissionBroker | Skill/MCP/模型都不能授予权限 |
| 产物文件与提交 | AgentArtifactStore 的事务日志 | UI 通过 ArtifactId 查询 |
| 侧栏导航 | 现有 app tab/selectTab | 只增加一个具名导航 intent |
| 页面展开/滚动/编辑草稿 | Agent 页面 VM/Saveable state，必要草稿持久化 | 不写 MainUiState |

### 3.4 不新增的耦合

- MainUiState 不增加 task/run/event/approval/model-selection 字段。
- MainCoordinatorHost 不增加 Agent 业务能力。
- 不新增 Any?、字符串 service locator、反射查服务或错类型回退真实实例。
- MainActivity 只做 Route 装配；MainViewModel 只新增导航 enum 所必需的声明，不增加 Agent 命令。
- ChatViewModel 只增加符合现有 surface 的一个具名导航转发，不承载 Agent 状态或工具规则。
- 不导入 feature:chat 的内部 Markdown/文件业务组件；确有共享语义时独立等值提取到 designsystem，且该切片必须证明旧渲染不变。

### 3.5 职责预算与修改影响

契约文件目标 ≤150 行、审查上限 300；owner 目标 200–400、上限 800；Route 目标 ≤150、上限 300；Screen/VM 目标 500–600、上限 800。沿用硬锁更严格的现有门禁。

接口超过 10 个操作、单对象超过 8 个直接协作者时先复核职责；不能用万能依赖包隐藏数量。AgentRunOwner 只协调状态和步骤，协议解析、数据库事务、网络、文件提交分别有内聚实现。

普通改动应具备以下局部性：

| 改动 | 主要影响位置 | 不应要求修改 |
| --- | --- | --- |
| 加一个文件工具 | tools + registry composition + fixture | MainActivity、聊天 VM、native runner |
| 加一种模型 provider | inference adapter + capability fixture | 页面 state machine、权限规则 |
| 改 Agent 布局 | workspace feature | native、Room schema、聊天布局 |
| 加一个授权类别 | contract + security policy + UI presentation | 所有工具逐个复制判断 |
| 增加持久字段 | data + 迁移 + 映射 | 主全局状态 |

## 4. 依赖、协议表示和注入

### 4.1 JSON 与协程决策

契约使用 argumentsJson/schemaJson/resultJson 字符串，复用既有 ChatToolDefinition/ChatToolCall 的语义。契约不暴露 org.json、JsonObject、Jackson、Android Parcelable 或数据库 entity。

Flow 仅用于只读观察查询。新增 coroutines-core catalog alias，固定使用现有 coroutines 1.9.0 版本；Android 实现沿用 coroutines-android。不得因本功能顺带升级全仓协程。

严格模型输出解析采用 Jackson Core streaming parser，不引入 databind、多态反序列化或全局 ObjectMapper。方案候选固定版本为 2.22.4：本轮仅核验 Maven Central metadata 存在该 release（2026-10-18 查询），未下载、编译或安全认证。实施 K00 需核验 POM、许可证、Android/JDK 兼容、校验和与已知漏洞，再把精确版本和来源记入依赖清单；不得用动态 latest。若该版本不满足条件，K00 形成精确替代决策后才能进入解析器切片，不得自动降级为宽松 org.json 解析。

采用维护中的 streaming parser 是为了严格 JSON、重复 key 检测和输入限制，避免自写 JSON 语法解析器。既有 org.json 使用不迁移；新 parser 仅封装在 agent-runtime 的内部 codec，不向 feature 泄漏类型。

### 4.2 注入和作用域

McaAppContainer 为当前应用主进程创建 AgentRuntimeAssembly。assembly 使用 Application-only 依赖，创建独立应用级 scope、owner、数据实现与平台 adapters；不得缓存 Activity、页面 VM 或 Composable scope。

Agent 页面通过两个窄端口获取能力：

~~~kotlin
interface AgentWorkspaceQueries {
    fun observeTasks(query: AgentTaskQuery): Flow<AgentTaskPage>
    fun observeRun(runId: String): Flow<AgentRunSnapshot>
    fun observeEvents(runId: String, afterSequence: Long, limit: Int): Flow<AgentEventPage>
}

interface AgentWorkspaceCommands {
    suspend fun createTask(command: CreateAgentTask): AgentCommandResult
    suspend fun startRun(command: StartAgentRun): AgentCommandResult
    suspend fun requestPause(command: PauseAgentRun): AgentCommandResult
    suspend fun requestCancel(command: CancelAgentRun): AgentCommandResult
    suspend fun continueTask(command: ContinueAgentTask): AgentCommandResult
    suspend fun answerQuestion(command: AnswerAgentQuestion): AgentCommandResult
    suspend fun resolveApproval(command: ResolveAgentApproval): AgentCommandResult
}
~~~

导入/导出、删除任务、扩展管理各自进入有真实生产调用者的窄用例，不为凑齐接口把所有能力加入 Commands。类型示意按 contract RFC 细化，不能复制出两个并行 owner。

观察 Flow 是冷查询或 Room 观察，不会启动模型。调用命令负责显式启动；同 commandId 重试只返回同一结果，不能因为新 collector/旋转页面再跑一次任务。

## 5. 真实资源 owner、Agent 接入链与并发

### 5.1 源码已确认的 ownership

| 生产事实 | 当前符号/路径 | 作用范围与限制 |
| --- | --- | --- |
| 共享 request identity/terminal | McaAppContainer.sharedRuntimeSupervisor | 每进程；身份/终态权威，不代表 native 全互斥 |
| 本地文本模型执行 owner | chatExecutionFacadeFor → ChatExecutionOwner | 多个文本入口复用同执行体；拥有 cancellation/terminal |
| 实际本地模型执行 | McaInferenceService.streamChat | lifecycle lease/GenerationStopToken；实际 native runner |
| 现有 UI/API funnel | LocalApiRuntime.streamChatWithContextProvider | provider 包含聊天 UI 状态 hooks，Agent 不调用此全局桥 |
| 图像请求身份 | imageExecutionGate / ImageExecutionOwner | 和聊天使用同进程 supervisor；仍有独立图像执行租约 |
| 图像 UI/API 互斥 | LocalImageGenerationCoordinator | 保持现有 busy/409 语义，不改成全局静默排队 |
| 本地图像内存交接 | prepareLocalImageRuntimeMemoryFor / ImageGenerationOwnerExecution | 获取同一 engine exclusive lifecycle lease，卸载 LLM；等待 exact 图像 worker 真实退出后才释放 |
| 前台保活 | GenerationForegroundLeaseRegistry / McaGenerationForegroundService | 保活 lease 与 native 执行 lease 作用不同 |
| Local API server 所有权 | LocalApiRuntime.claimOwner | 绑定/服务生命周期；不等同推理资源锁 |
| 聊天 worker | LocalChatWorkerService | 独立进程；不能假定主进程单例覆盖它 |
| BackendRuntimeOwner | core/engine/runtime | 现有 skeleton，不作为已接入资源权威 |

P0 必须把每个适用入口走到失败、取消、释放各一条链，并把当前符号、真实调用者、进程和测试记录在 owner-map。现有文档只是定位材料，不能替代代码核实。

### 5.2 选定的模型执行路径

~~~text
AgentRunOwner
  -> app McaAgentLocalModelAdapter
  -> 当前进程 McaAppContainer.chatExecutionFacadeFor(existingEngine)
  -> 现有 ChatExecutionOwner / sharedRuntimeSupervisor
  -> 同一个 McaInferenceService.streamChat
  -> 既有 lifecycle lease / native runner
~~~

这里复用的是“模型生成 attempt”的 owner，不复用聊天任务/消息/页面状态。AgentRunOwner 负责 Agent 多步骤任务；ChatExecutionOwner 只负责其中一次模型生成的生产身份、取消和终态。

Agent requestId 使用 agent:<runId>:<stepIndex>:<attemptOrdinal> 的独立命名空间。不得以 ui- 前缀伪装聊天，不调用 LocalApiRuntime provider，不触发 afterGenerationStarted/Completed 聊天 UI hooks。本地 request/session/role/context identity 使用 Agent 自己的值；V1 默认不启用持久 prefix/session cache。需启用时先证明 cache key 与 conversationOwnerId 的任务隔离。

P0 发现 ChatExecutionFacade 绑定的 engine、生命周期或 callback 作用域不适合此接入时，仅允许在同一真实执行体上增加窄 channel adapter，并保持旧调用者逐字语义等价。不得绕过 owner 直接另写 engine.streamChat 调用链。

### 5.2.1 本地资源等待与身份的确定流程

1. owner 先登记模型 attempt；adapter 报告 WAITING_RESOURCE，调用同一 engine 的 acquireExclusiveLifecycleLease(stopActiveGeneration=false)，只等待现有 mutex，最长 120 秒。禁止省略 false：默认值 true 会停止其他请求。
2. lease 到手后，复核Agent run epoch/取消标记，以及同一engine的activeExecutionProfile()/parameterSignatureSnapshot()与冻结binding；通过app侧ModelRuntimeProfileStore.pendingTransaction(identityHash)核对已持久候选。存在未结候选或active/committed签名不一致时返回RESOURCE_BUSY，不由Agent提交/回滚调参候选。
3. 同一个 LocalChatExecutionContext 传入 requestId、该 lifecycleLease、includeDeviceClockContext=false；不传可信 hot override 或擅自 load authorization。facade.streamChat 在 attempt 子 Job 内创建并立即收集一次，不把已准入但未收集的 Flow 存起来或分享给多个 collector。
4. streamChat 会同步完成 supervisor 准入。adapter 使用 facade.snapshot(requestId) 取得并记录实际 identity；若 stream 构造失败则结算失败。V1 为每次 attempt 使用永不复用的 requestId，因此 stopGenerationIfActive(requestId) 只能命中本 attempt；完整 identity 仍用于状态核对，不虚称 facade 返回了它没有提供的 handle。
5. acquire成功立刻将既有lease登记到app作用域的AgentModelLeaseRetention，再开始生成；取消子Job不能丢掉其引用。模型流结束、scoped stop 与native/worker停止证据一致后由该retention恰好释放一次。等待超时只取消自己的lock waiter；取消恰落在acquire返回边界，也必须登记后释放已到手lease。停止未证实时保留STOPPING fence，不能在通用finally无条件unlock。

这条链只在 app inference adapter 接触现有 engine；feature/runtime 看到的是 AgentModelPort 和 progress，不暴露 lease。每次模型步骤后释放 lease，审批/文件工具不占用它。

epoch分三类，禁止混用：AgentRun.epoch由Agent领域管理；RequestIdentity.runtimeEpoch在supervisor准入后取得；GenerationStopToken.epoch由既有engine stop逻辑捕获/重核对。现有engine没有可任意查询的统一“模型生命周期epoch”，adapter不得伪造。模型绑定使用真实profile identity/revision与active/committed签名，在同一lifecycle lease下保证一致。

AgentModelLeaseRetention属于app/inference adapter的资源记账，不创建新mutex、不准入其他任务，也不取代engine owner。它只保存engine对象身份、现有lease、Agent attempt/request映射和等待停止证明的子Job；由应用scope持有，页面/attempt取消不销毁它。stop返回true、Flow Done、registry终态或stop token清空都单独不算native退出证明。

K04必须核验每个后端的真实退出证明：同步native调用确实返回且无未结束工作；隔离worker由现有worker owner给出确切session/进程退出或安全空闲确认。若现有公开表面不足，只在同一engine/worker owner上新增按request/token读取或等待执行静止证据的窄接口，不从adapter直接操作runner、不轮询可能阻塞的JNI/Binder active查询。retention等待该证明后幂等release并回报owner；逾时保持STOPPING/诊断，不能强杀共享进程或“重置锁”。应用有序关闭先拒新任务并尽力结算；主进程死亡会销毁本机mutex，但重启须先由原worker owner核对/回收旧worker，再允许新run，持久记录不保存lease对象。

### 5.2.2 云模型接入与取消

当前云聊天可直接经 OpenAiCompatibleChatProvider 执行，并不自动经过 ChatExecutionOwner。Agent 云 adapter 复用该 provider 协议实现和 CloudApiStore 的配置/凭据读取，使用 Agent 专属、策略受控的 OkHttpClient 注入已有构造参数；不复制密钥、不改旧聊天 client 的行为。

每个云 attempt 在 AgentJournal 登记独立 attemptId/backendRequestId，AgentRunOwner 创建并持有唯一子 Job；该次 provider Flow/Call 只属于此 Job。停止取消该 Job/它的 Call，严禁 client.dispatcher.cancelAll()。成功必须有完整成功 Done，断连/超时/空流不当作成功；旧 Job 结果按 runEpoch/attempt CAS 拒绝。V1 不自动重试已发送请求或跨 provider 兜底；用户重试创建新 attempt 并计入预算。HTTP 已取消不等于远端算力/计费已停止，UI 和证据保留这一边界。

**不能直接信任现有所有provider的Done。** 当前OpenAI-compatible/Anthropic实现可能在EOF后发Done，即使没有合法协议终止信号。K04先以已校验terminal事件的Responses完成G0云验证；其他协议必须在既有provider内增加Agent显式启用的STRICT_AGENT_TERMINAL策略后才开放Agent工具。策略是有类型的构造/请求选项，旧聊天调用者默认保持原合同；不得复制整套provider解析器或从chunk中猜终态。

STRICT_AGENT_TERMINAL至少校验：Responses为成功response.completed且非incomplete/error；OpenAI SSE为完整消息帧、认可的finish_reason与[DONE]，length/content_filter等截断原因拒绝动作；Anthropic为合法stop_reason及message_stop，max_tokens/错误拒绝动作；非流JSON要求完整root、认可的完成原因和字段。具体endpoint不满足时标MODEL_TERMINAL_UNVERIFIED/NONE，不自动改宽松。即使输出文本恰好是完整合法envelope，EOF-before-terminal也必须Error且executor调用数为0。协议扩展完成前，云能力声明只列已经过证据的Responses路径。

Agent云client还须在body读取层有界计数：单SSE消息≤64KiB、每attempt响应wire总量≤1MiB，超限在reader累积无界字符串前中断并返回typed error；控制envelope仍受64KiB更窄上限。网络chunk边界不是SSE/JSON消息边界，分帧必须跨chunk正确组装。

### 5.3 取消的危险边界

现有无条件 stopGeneration 可能 cancelAllActive。Agent 取消绝不调用该入口，不调用全局“停止全部”。

adapter 必须把 Agent attempt 映射到现有 RequestIdentity/GenerationStopToken/epoch，使用身份校验后的 stop。仅有 requestId 而不能证明当前 token 所属时，不能停止别人的任务。未来不同 attempt 不能复用同一个 requestId。

取消超时：保持 STOPPING 状态与资源 fence，展示“正在等待后端停止”。不能先释放资源再让新任务进入仍在运行的 native handle。只有既有 worker/process owner 确认可安全回收时才终止专属 worker，不杀共享主进程替代准确取消。

### 5.4 并发默认矩阵

| 现有工作负载 | Agent 想执行 | V1 行为 |
| --- | --- | --- |
| 同一个文本 native engine 正在生成 | 本地模型步骤 | WAITING_RESOURCE；不抢占、不全局 stop、不新建 engine |
| 正在 load/unload/模型参数事务/benchmark | 本地模型步骤 | 通过权威 lifecycle gate 等待或得到 busy；Agent 可见等待 |
| Agent 正在模型步骤 | UI/API 请求 | 保持原入口既有 busy/409/准入合同；不为 Agent 改旧返回语义 |
| 同一 local image coordinator 有活动任务 | 后续 Agent 生图工具 | 保持 image_generation_busy/409；不重复产生副作用 |
| 本地 native 生成 | 云模型步骤 | 可独立执行，仍遵守 Agent 单 active run 与预算 |
| 本地模型 busy | Agent 文件读取/导出 | 不持推理 lease，可在资源约束内运行 |
| 不同独立 worker/process | 任意本地工作负载 | 沿用现有协调策略；不得用主进程 mutex 假装跨进程覆盖 |

Agent 自己的等待队列不改变任何旧入口。等待资源默认最长 120 秒，超时进入 PAUSED(reason=RESOURCE_BUSY)，用户可继续；V1 不做复杂优先级或隐式抢占。

实际文本 engine 通过现有 mutex/lifecycle lease 串行。adapter 不加一个模拟 native 准入的轮询锁；等待权威 gate 时呈现 WAITING_RESOURCE，超时取消自己的等待。UI/API 原本等待还是立即冲突由原合同决定，本功能不统一改写。

现有本地图像执行通过 engine 的 exclusive lifecycle lease 完成正式内存 handoff，并非只有聊天页 busy 检查。它会卸载 LLM，图像结束也没有已证明的本地 Agent 自动 reload。故 V1.0 不开放“本地 LLM → 本地生图 → 自动续跑”组合；后续切片必须通过既有 model owner 重载捕获的模型/profile 并验证 worker exit，或明确暂停等待用户恢复模型。

如果现有生产 gate 不能覆盖 Agent 与调参或模型切换的真实冲突，必须先把 Agent 纳入现有权威准入，再开放本地步骤。新增轻量准入命令只能代理现有 lease，不能创立平行“AgentLease”冒充 native 权威。

### 5.5 模型绑定与 KV/session 隔离

Run 创建时固定 providerId、modelId、runtime、模型资产/配置 fingerprint、协议模式、采样配置和能力证据版本；绝不保存密钥。新步骤核对绑定与当前 loaded state，不默默跟随聊天页新选择。

V1 优先使用已加载的本地模型。未加载模型通过现有模型管理入口/已存在的窄加载用例处理；不得复制加载逻辑。用户改变全局实际加载模型后，Agent 进入 PAUSED(reason=MODEL_BINDING_CHANGED)，选择恢复原模型或创建新绑定后继续产生新 run，旧审批一律失效。

模型 adapter 为每个步骤提供完整、配对的 Agent transcript；必须证明底层 KV/prompt cache 不把其他任务的历史拼进该请求。不能证明 stateless/reset/session-key 隔离，就把该 runtime 的 Agent 模型路径标为未通过，不用改 taskId 假装解决。

## 6. 模型能力与 PROMPTED_V1 协议

### 6.1 当前能力与标识

源码中 ChatToolDefinition/Call/Exchange 和 Done.toolCalls 是协议基础，不等于每个本地 runner 已支持工具。当前 ChatRequest.messagesJson 不序列化 schema/exchanges；本地 runner 接收 messagesJson/paramsJson，decode 的 Done 默认没有 calls；llama.cpp formatter 未把 tools 传入 chat-template。V1 不顺带升级 native 模板。

Responses 的 supportsTools 是显式配置且旧配置默认 false。只有启用、协议匹配并通过终态校验时才能采用原生调用；不得为 Agent 修改聊天页云模型记录、把该开关自动开启或伪造支持。

每个运行后端 + 模型资产 fingerprint 的能力记录：

| 模式 | 定义 | 首次 V1 默认 |
| --- | --- | --- |
| NATIVE | provider/backend 实际接收工具 schema、输出完整结构化调用，经 fixture 与实际模型验证 | 已验证的云 Responses 可采用；本地不凭模型名称假定 |
| PROMPTED_V1 | 把工具约定放进 Agent 专属上下文，严格解析完整 JSON 控制响应 | 本地文本 runner 的首选接入 |
| NONE | 当前 provider 无有效工具协议，或用户禁用工具 | 仍可给文字建议；不虚构执行 |

模式可见于模型详情/任务设置；不把 PROMPTED 标成原生 FC。能力资料未知不等于拒绝设备运行；可以执行受限协议验证，结果用于本机建议与诊断。

### 6.2 严格 envelope

PROMPTED_V1 每次 assistant 可执行响应必须是一个完整 JSON object，外层无 Markdown fence、说明性前后缀或第二个 object。字段集合固定：

~~~json
{"protocol":"mca.agent.v1","kind":"tool","call":{"name":"workspace.read_text","arguments":{"path":"inputs/brief.txt","offset":0,"limit":4096}}}
~~~

~~~json
{"protocol":"mca.agent.v1","kind":"final","text":"已整理完成。","artifactIds":["由运行时已登记的产物ID"]}
~~~

模型可以请求澄清：

~~~json
{"protocol":"mca.agent.v1","kind":"ask_user","question":"报告需要面向哪类读者？"}
~~~

模型不生成 invocationId、不授予权限、不自报工具已执行、不创建任意 ArtifactId。最终引用必须指向当前任务真实存在、可访问的产物；未知引用不展示成可点击文件。

### 6.3 解码和安全规则

1. 只解析当前 assistant 模型输出通道，绝不扫描用户资料、tool result、网页标题或自然语言正文中的 JSON 来决定执行。
2. 本地 PROMPTED 输出在完整结束且通过校验前只呈现“正在生成下一步”；不流式执行 partial arguments。
   MCA 的 Chunk.text 与 Chunk.reasoning/hiddenReasoning 已分开；只累积 visible text。收到 Error、取消、缺少成功 Done 或长度截断时，即使缓存看似合法也不执行。
3. streaming parser 开启重复 key 检测；禁用注释、单引号、尾随逗号、NaN/Infinity；拒绝多 root、未知控制字段和不支持协议版本。
4. 解析深度 ≤16，控制响应 UTF-8 ≤64 KiB，单参数字符串 ≤32 KiB，单次最多 1 个 tool call。溢出取消本次模型 attempt 并记录协议错误。
5. 先 schema 校验，再解析资源范围，再生成 frozen tool command。未知工具、禁用工具、参数类型错误或额外参数均不执行。
6. 一次 run 最多 1 次格式修复。修复只给机器可读错误及合法 schema，计入模型/token/时间预算；不“修复”为自动执行的猜测调用。
7. 协议失败重试后仍无效，进入 PAUSED(reason=MODEL_PROTOCOL_ERROR)，显示原因为格式不兼容。可重新选模型/继续文字回答，不能冒称完成工具。
8. reasoning 通道若由 provider 单独提供，仅按该 provider 合同在 adapter 中保留必要 opaque state；不把其中内容送进控制解码器。若后端只给一段混合文本，V1 要求最终控制响应完整合规，否则失败；不正则搜索 think 标签外的任意 JSON 兜底。

### 6.4 schema 支持范围

V1 参数 schema 支持 object/properties/required/additionalProperties、string/enum/minLength/maxLength、number/integer/minimum/maximum、boolean/null、array/items/minItems/maxItems。工具 schema 必须限制根 object 的额外字段。

title/description/examples/deprecated等annotation可保留为有界metadata；default不代表自动填参数，内置默认值仅按第9.1节代码规则。$schema/$id仅作已知格式标记，不访问网络、不解析远程引用；MCP annotations/readOnlyHint绝不变成权限。原始schema与有效schema的hash均记录，任何验证语义变更需新版本和重新审批。

不支持的组合关键字、远程 $ref、无限递归 schema 不静默忽略。内置工具构建期拒绝；MCP 工具登记时显示 SCHEMA_UNSUPPORTED 并保持该工具禁用，其他兼容工具正常使用。后续采用完整 JSON Schema validator 是单独的依赖/协议升级任务。

### 6.5 transcript、reasoning 与工具配对

权威 transcript 按 UserInput → AssistantCall → ToolResult → AssistantNext 组成。runtime 生成 InvocationId，adapter 保留 providerCallId。每个被模型看见的 call 必须有一个且仅一个对应结果：成功、失败、用户拒绝或明确未执行。

原生 provider 多 call 响应在 V1 若超过 1 个，不执行其中任意一个；先按 provider 合同填入所有 NOT_EXECUTED 结果，再请求串行重发，或暂停。不能只执行第一个却遗漏其余配对。

暂停、恢复、上下文压缩不得留下孤立 tool call/result。provider 要求回传的 reasoning item/response id 作为不透明数据保存在 adapter continuation 中，不进入用户时间线，不记录到通用日志，不假定不同 provider 兼容。

### 6.5.1 本地 PROMPTED transcript 的唯一映射

本地 Role 只有 SYSTEM/USER/ASSISTANT。V1 不新增 TOOL role，也不指望 ChatRequest.toolExchanges 自动进入 messagesJson。专属 AgentPromptedTranscriptSerializer 按以下规则建立每一次完整 ChatRequest：

| 权威记录 | 本地 ChatMessage 映射 |
| --- | --- |
| Agent 协议、当前可用工具/schema、预算与信任规则 | 唯一显式 SYSTEM；版本与 hash 可追溯，不附聊天 persona/历史/知识背景 |
| 用户初始目标 | USER，content 为 JSON 数据记录 `{"record":"user_input","text":...}` |
| 已接受的 assistant 控制响应 | ASSISTANT，content 为校验通过的完整 envelope；保留原文，不以工具成功描述替换 |
| host 真实工具结果 | USER，content 为 host 序列化的 `{"record":"tool_result","invocationId":...,"callHash":...,"tool":...,"result":...}` |
| 模型 ask_user 与用户回答 | ASSISTANT 原 ask_user envelope，随后 USER `{"record":"user_answer","questionId":...,"text":...}` |
| 格式修复请求 | USER，`record=protocol_error`、稳定 errorCode 和 schema 提示；不把未校验调用当成功历史 |

所有 JSON 值用 codec 编码，不手写字符串插值拼接；工具结果里的引号/分隔符/伪 system 指令只能成为数据。SYSTEM 明确 tool_result 是低信任观察，不是新用户授权；这条提示辅助模型理解，真正权限仍由 broker 强制。工具结果的 invocationId 由 host 记录关联，模型原 envelope 不必含该 ID。

ChatRequest 使用显式 Agent SYSTEM、独立稳定 messageId、冻结 params；tools/toolExchanges 留空，persistentPrefixSystemPrompt/persistentSessionId/persistentRoleId 留空以禁持久缓存。权威 Agent transcript 必须先落 journal/有界 payload，再投影到 messages；只有当前模型的 visible 完成输出送控制 decoder。下一轮以完整配对历史重建，不把资料拼成新的 SYSTEM，不把其他 run 的失败 partial 混进上下文。

K02 固定两组 golden messagesJson（MNN/GGUF可消费的同一角色语义），覆盖成功、拒绝、超时/未执行和用户回答；K04 用真实 runner 对照 serializer 结果完成至少一轮 call→result→final。NATIVE 云协议使用 provider 原生 call/result，而非这套 USER 观察编码；需要 continuation 却无法合法回传的 provider 明确不启用该 NATIVE 路径。

### 6.6 预算默认值

| 预算项 | V1 默认 | 语义 |
| --- | ---:| --- |
| model attempts/run | 12 | 包含格式修复请求 |
| tool invocations/run | 8 | 包含失败、拒绝，不含未执行协议垃圾 |
| calls/model response | 1 | V1 串行 |
| protocol repairs/run | 1 | 没有无限“自动纠错” |
| 单次模型 wall time | 180 秒 | 用户可在任务前提高；慢模型不因此被设备名单禁止 |
| 工具超时 | 30 秒 | 单工具 descriptor 可更短；长工具须另审 |
| run 活动执行时间 | 15 分钟 | 不含审批、资源等待、暂停与用户输入 |
| 等待资源 | 120 秒 | 超时转可继续暂停 |
| tool result 送入模型上限 | 16 KiB | 有界摘录 + 截断标记 + 产物引用，不声称完整 |
| 工具持久化普通结果上限 | 64 KiB | 大结果转 payload/artifact |
| 普通文本模型输出 | 2,048 token 起始默认 | 不覆盖旧聊天参数；绑定时展示并允许调整 |
| run 生成 token 总量 | 24,576 token 起始默认 | 含修复与可计量的 reasoning；设置与每 attempt 上限同时冻结 |

上下文预算按实际后端最大 context、系统/工具 schema、保留输出和 transcript 计算。不会静默删历史或 tool 配对。容纳不下时 PAUSED(CONTEXT_LIMIT)，用户选择缩小资料、显式压缩或更换模型。没有可靠 tokenizer 时保守估算并标识，真实溢出作为可诊断错误。

V1 不实现自动上下文压缩；“显式压缩”只允许用户确认的摘要作为新 run 输入，并保留原始配对记录，不擅自调用另一个总结模型。provider 不提供精确 token usage 时标为估算，使用保守余量；字节/attempt/时间硬上限仍必须执行，不能宣称精确的云端费用封顶。所有超时和 TTL 使用单调时钟；进程/设备重启或时钟可信度变化使未消费审批失效，不用系统日期回拨延长批准。

## 7. 类型、命令、状态与错误合同

### 7.1 核心记录与版本

所有持久/跨模块记录包含 schemaVersion=1。标识使用运行时生成的 UUID 字符串；展示标题和模型输出不能用作路径或主键。

| 类型 | 必需字段 |
| --- | --- |
| AgentTask | taskId、workspaceId、goal、lifecycle、createdAt、updatedAt、displayStatus、revision |
| AgentRun | runId、taskId、runOrdinal、state、reason、epoch、modelBinding、policyVersion、budget、spentBudget、lastSequence、revision |
| AgentModelAttempt | attemptId、runId、stepIndex、ordinal、backendRequestId、backendIdentityRef、bindingFingerprint、startedAt、terminal |
| AgentStep | stepId、runId、index、kind、status、modelAttemptId、invocationId、startedAt、endedAt |
| ToolInvocation | invocationId、runId、stepId、toolKey/version/schemaHash、frozenArgumentsRef/hash、resourceFingerprint、status、idempotencyKey、resultRef |
| AgentApproval | approvalId、invocationId、principal、argsHash、toolSchemaHash、resourceFingerprint、policyVersion、runEpoch、expiresAt、status、consumedAt |
| AgentArtifact | artifactId、taskId、invocationId、relativePath、mediaType、size、sha256、commitState、createdAt |
| AgentEvent | eventId、runId、sequence、type/version、createdAt、commandId/causationId、redactedPayloadRef |

task.displayStatus 是当前 run 的事务性摘要，不另设一套可冲突的任务状态机。页面选中项、toast 和展开状态不是这些持久记录的一部分。

### 7.2 命令身份与并发语义

所有外部命令带 commandId 和适用实体的 expectedRevision。创建任务没有旧 revision；启动/继续带 expectedTaskRevision，其余 run 命令带 expectedRunRevision。先查 commandId 幂等记录，再作 revision CAS：已成功的同内容命令即使 revision 已推进，也返回原结果；同 ID 不同参数返回 IDEMPOTENCY_CONFLICT，新命令的过期 revision 返回 CONFLICT。

一个 run 内状态推进串行化。owner 可使用 actor/串行 command processor，但不得在持锁/事务时跑模型、网络、文件 I/O 或 UI 回调。启动步骤先事务登记，执行子协程完成后带 runEpoch + attempt/invocation 身份回报。

命令入口有界（普通待处理命令最多64），满时返回 RESOURCE_BUSY，不用Channel.UNLIMITED。取消/暂停使用可合并的高优先级控制信号，由同一owner处理，不能排在大量普通命令之后；同run重复stop合并但仍保持command幂等结果。事件/进度投影可合并，审批决策和终态不可丢弃。

旧 callback 只有在 runId、epoch、step、attempt 身份、预期状态均匹配时才可提交。状态 CAS 失败记录 stale diagnostic 并丢弃，不重定向到当前活动任务。

AnswerAgentQuestion 必须带 questionId、runId、epoch 和用户输入；只有当前 WAITING_USER 的同一问题可接受，原子关闭问题并追加用户回答后恢复同 run。模型绑定/权限变化等系统阻断使用 PAUSED reason，不伪装为模型提问。用户修改目标、模型绑定或预算后继续，必须经 ContinueAgentTask 新建 run，重新确认适用授权；不得用回答问题绕过冻结绑定。

### 7.3 Run 状态转移

| 当前状态 | 触发者/事件 | 下一个状态 | 守卫与副作用 |
| --- | --- | --- | --- |
| QUEUED | 用户启动、owner 获得活动槽 | RUNNING | 绑定与策略有效；持久化 attempt 后执行 |
| RUNNING | 资源 busy | WAITING_RESOURCE | 没有 native lease；等待有上限 |
| WAITING_RESOURCE | 获得合法资源机会 | RUNNING | 重新核对 epoch/绑定，不靠旧 lease |
| WAITING_RESOURCE | 120 秒到期 | PAUSED | reason=RESOURCE_BUSY |
| RUNNING | 工具需要授权 | WAITING_APPROVAL | 释放模型 lease，保存 frozen command |
| WAITING_APPROVAL | 有效批准/拒绝 | RUNNING | 批准消费后执行；拒绝产生 DENIED tool result |
| WAITING_APPROVAL | 审批到期/资源变化 | PAUSED | reason=APPROVAL_EXPIRED/STALE |
| RUNNING | 模型 ask_user | WAITING_USER | 保存明确问题；不持 native lease |
| WAITING_USER | 用户回答当前 questionId | RUNNING | 写入用户回答；重新检查 binding/policy/预算，不重复回答 |
| RUNNING/等待态 | 用户暂停 | PAUSING → PAUSED | 模型 attempt 可取消；不可取消工具等待安全边界 |
| 活动/等待/暂停态 | 用户停止 | STOPPING → CANCELLED | 只有执行停止并结算后才能完成取消 |
| RUNNING | 完整合法 final | SUCCEEDED | 无未结工具/产物提交，资源已释放 |
| RUNNING | 可恢复预算/格式问题 | PAUSED | 明确 reason，用户可调整再继续 |
| RUNNING/STOPPING | 工具副作用无法核实且本地执行已结束 | NEEDS_REVIEW | 保留UNKNOWN配对结果，阻止自动重试/下一模型步骤；本地仍运行不满足守卫，保持STOPPING fence |
| 活动态 | 非恢复错误 | FAILED | 记录错误，清理可安全清理的资源 |
| 非终态 | 进程死亡后恢复 | INTERRUPTED → NEEDS_REVIEW/PAUSED | 不静默续跑；核对副作用 |
| PAUSED/NEEDS_REVIEW | 用户继续同task，原子创建successor run | SUPERSEDED | 旧run已核账、无本地活动执行；新runOrdinal入队，旧审批失效 |

终态 SUCCEEDED/FAILED/CANCELLED/SUPERSEDED 不再变回 RUNNING。用户“继续”创建新 runOrdinal，引用已核账上下文；旧 run 保留，旧审批失效。任务仍是同一个 taskId。

状态枚举以本表为准，取消统一使用 STOPPING，不另加 CANCEL_REQUESTED 同义状态。PAUSING 与 STOPPING 期间仍占活动槽，停止事实未确认不得释放。QUEUED 可直接取消而不构造执行器。PAUSED/NEEDS_REVIEW 不自动运行，释放活动槽；一次 task 只能有一个可继续的最新 run。ContinueAgentTask 在同一事务中将该旧 run 记为 SUPERSEDED（不可变终态）并创建新 ordinal；已有终态只追加 successor 关联。中断恢复先完成核账，再确定 PAUSED/NEEDS_REVIEW；不把原模型 attempt 复活。

WAITING_RESOURCE/WAITING_APPROVAL/WAITING_USER 保留唯一活动槽，避免另一个 run 越过尚未解决的步骤；均不持 native lease。WAITING_USER 可长期等待但不持 FGS 保活；用户可暂停该任务，让排队任务继续。审批 TTL 为 5 分钟，资源等待上限为 120 秒。页面切换、collector 取消和 AnswerAgentQuestion 不会新建 run。

### 7.4 工具调用状态

~~~text
PROPOSED -> VALIDATED
VALIDATED -> WAITING_APPROVAL -> APPROVED
VALIDATED -> APPROVED（仅已有有效的精确任务授权）
WAITING_APPROVAL -> DENIED / EXPIRED / NOT_EXECUTED
APPROVED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED / UNKNOWN
~~~

执行必须由 APPROVED 原子迁移到 RUNNING，同时消费 approval/grant、登记 idempotencyKey 和事件。进程重启后 RUNNING 且没有可核实结果，读工具可按重试策略恢复；写/外部副作用默认 UNKNOWN。

未真正执行的取消统一为 NOT_EXECUTED，拒绝为 DENIED，审批过期为 EXPIRED；这些内部状态投影成配对的非成功tool result。只有已运行且证明停止、没有已提交副作用的工具可记 CANCELLED。SUPERSEDED 是 run 终态；TOMBSTONED/IMPORTED_ARCHIVED 是 task lifecycle，不能互换。

任何暂停/过期/中断形成新run前，先将已接受但未执行的call补为NOT_EXECUTED，将已执行结果核账或标UNKNOWN；新run不得继承悬空call，更不能沿用原approval执行新命令。

取消是请求，不是任意工具都能撤销：已经提交的文件仍是已提交结果；run 可被取消但必须展示已完成副作用。不可把所有 invocation 统一改成 CANCELLED 来抹掉事实。

### 7.5 错误分类

| code family | 例子 | UI/重试 |
| --- | --- | --- |
| MODEL_* | NOT_LOADED、BINDING_CHANGED、PROTOCOL_ERROR、CONTEXT_LIMIT | 提示模型/格式问题，用户选择继续/换模型 |
| RESOURCE_* | BUSY、STOP_TIMEOUT、LEASE_LOST | 显示等待或停止中，不并行越过 fence |
| TOOL_* | INPUT_INVALID、TIMEOUT、FAILED、RESULT_UNKNOWN | 说明工具与是否可能已产生副作用 |
| PERMISSION_* | DENIED、EXPIRED、ARGUMENTS_CHANGED、SCOPE_REVOKED | 不执行；必要时重新审批 |
| POLICY_* | OFFLINE、EGRESS_DENIED、SCHEMA_UNSUPPORTED | 不偷偷联网或用其他工具绕过 |
| BUDGET_* | STEP_LIMIT、TOKEN_LIMIT、TIME_LIMIT、DISK_LIMIT | 可继续暂停，呈现已用预算 |
| STORAGE_* | MIGRATION、NO_SPACE、ARTIFACT_COMMIT、CORRUPTION | 保留证据与可恢复文件，不初始化清空 |
| INTERNAL_* | INVARIANT、UNEXPECTED | 用户简明错误 + 脱敏 correlationId |

AgentError 含 code、category、retryability、safeMessage、correlationId、causeRef、possibleSideEffects。不可把原始异常/服务器响应全文直接显示或写日志。

## 8. 审批、权限和 TOCTOU 合同

### 8.1 权限实现位置与顺序

权限决策在 app/.../agent/security/AgentPermissionBroker，纯策略规则可放 runtime；系统 SAF/网络授权事实由 app 实现。feature 只展示请求并提交用户决策。Tool/MCP/Skill 不持仓储写句柄、全局 Context 或直接网络通道。

每个候选调用执行以下固定流程：

~~~text
tool lookup/version/schema snapshot
 -> strict input validation
 -> resolve defaults and exact resource scope
 -> construct immutable FrozenToolCommand
 -> permission/egress decision
 -> present approval if needed
 -> revalidate resource + policy + schema + run epoch
 -> atomically consume approval and record RUNNING
 -> executor
 -> record result and artifacts
~~~

模型给出的路径、工具声明的 permission 字段、MCP annotations、Skill allowed-tools 都不是用户授权。

### 8.2 FrozenToolCommand 与审批绑定

冻结后包含 toolKey/version/schemaHash、normalizedArgumentsUtf8、资源集合/目标 precondition、network destinations、runEpoch、policyVersion、有效期。执行器只接收这份冻结对象，不重新读取模型字符串。

argsHash=SHA-256(length-prefixed UTF-8 fields)。固定顺序为domain=`mca.frozen-command.v1`、最终规范化参数bytes、tool identity、资源precondition、policyVersion，每字段用8字节大端无符号字节长度前缀；待hash的完整bytes保存后不得重序列化。审批前注入默认值并完成路径解析；审批后不再变换命令。无需宣称实现 RFC 8785 全 JSON 规范化。

一次审批绑定 invocationId + argsHash + schemaHash + resourceFingerprint + runEpoch + policyVersion，默认 5 分钟失效、只能消费一次。相同参数但不同 invocation 不共用 ALLOW_ONCE。

进程死亡、模型绑定改变、工具更新、MCP schema 改变、workspace grant 撤销、离线策略改变使旧审批失效。执行前 CAS 消费审批；重复点击批准不能执行两次。

### 8.3 权限矩阵

| 能力 | 默认 | 授权范围 |
| --- | --- | --- |
| 用户附加的只读输入 | 任务创建时明确 ALLOW_FOR_TASK | 当前任务选定文件/目录快照 |
| 私有 workspace 读取/搜索 | ALLOW_FOR_TASK | 不能越出 task root；不扩为整个 filesDir |
| 新建/覆盖产物 | ASK | 显示相对路径、大小和覆盖 diff；绑定 expectedOldHash |
| SAF 导出到用户选定位置 | 明确用户操作 | 只导出选中产物；不能借此任意写共享目录 |
| 云模型 egress | 用户开启联网并授予本 run 的 provider/context 类别 | provider fingerprint、数据类别和资料范围 |
| MCP 网络调用 | ASK/明确连接授权 | server identity、URL origin、tool、数据类别 |
| 终端/系统/无障碍/Shizuku | V1 不注册 | 后续逐能力设计与撤销 |
| Skill/插件安装 | 用户明确导入并预览 | 安装不等于授予工具/网络权限 |

ALLOW_FOR_TASK 只适用于 broker 允许的范围，不可用于自动批准未限定目标的写操作/命令执行。用户拒绝操作要形成 DENIED tool result；模型可继续给建议，但重复请求同一操作不产生无限弹窗。连续 2 次相同 denied proposal 转 PAUSED(reason=PERMISSION_DENIED_LOOP)，不伪造模型questionId。

### 8.4 文件与 SAF 防护

- 路径是相对 task root 的逻辑路径；拒绝绝对路径、盘符、NUL、..、符号链接逃逸、路径规范化后越界。
- 使用内部映射 ArtifactId/ImportedInputId，尽量避免把真实私有绝对路径交给模型。
- 写入仅在 app 私有 workspace；V1 不直接写 SAF 外部目录。外部文档先复制为受限输入快照，保留来源 URI fingerprint。
- 覆盖基于 expectedOldHash，写入前重查；用户/其他步骤改动后返回 PRECONDITION_CHANGED，不继续覆盖。
- SAF provider 不是普通 POSIX 文件系统，不宣称支持硬链接/原子 rename。外部导出失败可清理自己创建的目标，不能删除来源文件。

## 9. 内置工具 V1 规范

| 工具 key | 参数与结果 | 副作用/限制 |
| --- | --- | --- |
| workspace.list | path、limit≤200；返回文件引用、类型、大小 | 只读，不递归无限遍历 |
| workspace.read_text | path、offset、limit≤16 KiB；返回内容片段、总长、sha256 | 只读；输入需 UTF-8/识别编码，二进制返回明确不支持 |
| workspace.search_text | query、path、maxMatches≤100；返回有界片段与位置 | V1 literal search，避免模型给 regex 导致 ReDoS |
| artifact.write_text | path、content、expectedOldHash/null；返回 ArtifactId | ASK；content≤32 KiB；原子提交到私有 workspace |

用户澄清只使用 kind=ask_user 控制消息，字段 question 必填、可选 options 最多3项；不额外注册 task.ask_user 工具，避免两条等待输入路径。question≤4 KiB，option≤128字符。

knowledge.search 是 V1.0 之后的可选扩展工具，前提是调用既有知识检索窄端口并通过数据权限测试；缺少 headless port 时先补真实 adapter，不从聊天 VM 借整个上下文。V1.0首版内置工具仍严格为上述四项。

所有结果为带 schemaVersion 的结构化结果，包含 ok/status、summary、data/artifactRefs、truncated、errorCode、possibleSideEffects。失败不返回伪造成功文本。超大结果转产物，供模型读取有界片段。

V1 自有工具具备 descriptor→validator→broker→executor→journal 的同一链路；不得给“内置工具”开无审批旁路。

### 9.1 参数、默认值与字节语义

四工具根参数均为 object、additionalProperties=false。缺必需字段/显式 null/额外字段不采用静默默认；只有下表列明的可选字段可以补默认。路径只支持逻辑 inputs/ 与 artifacts/，长度≤512字符、层级≤16；write 目标仅 artifacts/。limits 的 KiB 均为 1,024 字节，UTF-8 字节预算与 schema 字符长度分别检查。

| 工具 | 必需 | 可选默认与确定行为 |
| --- | --- | --- |
| workspace.list | 无 | path=`inputs/`，limit=100（1–200），cursor=null；排序按规范化相对路径；cursor绑定目录版本，变化返回 CURSOR_STALE，不静默漏项 |
| workspace.read_text | path:string | offset=0，limit=4,096（1–16,384字节）；offset为UTF-8字节偏移，非字符边界返回 INPUT_INVALID；结尾缩至完整字符，返回 nextOffset/eof/总字节/hash；limit装不下首字符时返回LIMIT_TOO_SMALL，不返回不前进的假成功 |
| workspace.search_text | query:string（1–256字符） | path=`inputs/`，maxMatches=20（1–100）；字面量、区分大小写；排序路径后行号；扫描总量≤32MiB/单文件≤8MiB，触限返回 truncated 与 scannedBytes，不伪称全量搜索 |
| artifact.write_text | path:string、content:string、expectedOldHash:string或null | 无其他可选字段；null明确表示仅新建，已有逻辑路径即 PRECONDITION_CHANGED；覆盖必须给当前SHA-256；content≤32KiB UTF-8 |

输入文本统一为 UTF-8 只读快照；导入时可明确选择编码转换，并记录源 hash/编码和转换后 hash。无法识别/解码时不猜编码覆盖原文；二进制不进入文本工具。所有遍历/查找在 IO dispatcher 流式处理，受同一工具时间/结果大小预算约束。

### 9.2 结果与完成的确定语义

结果状态为 SUCCEEDED/FAILED/DENIED/NOT_EXECUTED/CANCELLED/UNKNOWN；status 是事实，ok 仅在 SUCCEEDED 时为 true。内部审批EXPIRED映射为NOT_EXECUTED并附明确reason。每个可见结果引用 invocationId/toolKey，结果截断必须同时返回 truncated=true、完整结果引用及下一步可读方式；若完整结果因策略不保存则明确说明。final.text≤32KiB、artifactIds≤20个且不重复；kind=ask_user 的 options 若出现必须是1–3个字符串，否则协议错误。

final必须有非空可见text或至少一个已验证产物，不能用空final将没有交付内容的任务记成完成。

安全演示任务固定为用户附加 brief.txt → list → read → search → 写入审批 → write(report.md) → final。每一步模型响应、审批拒绝/批准、结果 hash 和实际文件均可对应；仅声称“报告已生成”而没有已登记产物，不算任务成功。

## 10. 持久化、产物、配额与恢复

### 10.1 数据库决策

复用当前 mca.db，新增 Agent 独立表。当前源码为 Room v27；实施 K05 再读 @Database 与最新 schema，用实际 N→N+1。不得盲目修改 27.json，也不得在用户数据上 destructiveMigration 或清库。

最小持久记录及其落表（完整列定义在 K01/K05 RFC 依此冻结，不得漏掉幂等/恢复所需记录）：

| 表 | 关键字段/约束 |
| --- | --- |
| agent_workspaces | workspace_id PK、created_at、display_name、storage_ref |
| agent_tasks | task_id PK、workspace FK、goal、lifecycle、last_run_id、summary_status、revision |
| agent_runs | run_id PK、task FK、ordinal、state/reason、epoch、model_binding_json、budget_json、revision、last_seq |
| agent_model_attempts | attempt_id PK、run/step FK、ordinal、backend_request_id UNIQUE、identity_ref、binding_hash、terminal、usage/stop时间戳 |
| agent_steps | step_id PK、run FK、step_index、kind/status、attempt_ref/invocation_ref；run+index 唯一 |
| agent_invocations | invocation_id PK、run/step FK、tool/schema fingerprints、args_ref/hash、status、idempotency_key UNIQUE、result_ref |
| agent_approvals | approval_id PK、invocation FK、bound hashes/epoch/policy、expires、decision、consumed_at |
| agent_events | event_id PK、run FK、sequence、type/version、command_id、payload_ref；run+sequence 唯一 |
| agent_artifacts | artifact_id PK、task/invocation FK、relative_path、size、sha256、commit_state |
| agent_extensions | extension_id PK、kind、version、source/hash、enabled、manifest_ref |
| agent_commands | command_id PK、scope_id、content_hash、result_ref、applied_revision；与业务变化同事务保存 |
| agent_grants | grant_id PK、task/run FK、scope/policy/model fingerprints、expires/revoked；只表示本机用户授予，导出排除 |
| agent_artifact_commits | commit_id PK、invocation/artifact FK、staging_ref、目标逻辑path、旧hash、新hash/size、phase；恢复核账权威 |

MCP 凭据不存 extension manifest；只保存现有或专用 Keystore-backed credential reference。数据库字段保存稳定 enum 字符串，不保存 Kotlin ordinal。

额外唯一约束至少有 runs(task_id,ordinal)、attempts(run_id,step_id,ordinal)、invocations(step_id,call_ordinal)；每 invocation 最多一个有效待消费审批，单步骤最多一个执行中的工具。多 call 协议拒绝的 NOT_EXECUTED 配对也能完整记录，不能用唯一索引丢掉其余调用。workspace 与 task 在 V1 为一对一；取消/删除按 task 聚合处理，禁止默认级联删除整个 workspace 下其他任务。provider continuation、transcript、问题/回答和大字段使用带类型/版本/hash/size的 payload_ref；不存在文件的引用不得确认为可用记录。

首次导入的 task.lifecycle 使用独立 ACTIVE/IMPORTED_ARCHIVED/TOMBSTONED 字段，不把 archived 塞进 AgentRun 执行状态。导入 run 保留原状态作为不可执行的 provenance，只提供展示，不进入 owner 的可继续查询。进程恢复显式扫描 STOPPING/PAUSING/INTERRUPTED 等所有非终态，不只扫描 RUNNING。

数据事实由关系表权威持有，事件是同事务提交的审计/时间线，不要求运行时重放所有事件才能启动。状态摘要、invocation 变化、event sequence 在同事务提交；不是两套独立 writer。

### 10.2 存储位置

~~~text
app private files/agent/workspaces/<workspaceId>/tasks/<taskId>/
  inputs/        导入的只读快照
  artifacts/     已提交产物
  payloads/      有界 transcript/tool result 与大字段
  staging/       未完成提交
~~~

路径通过 ArtifactStore 解析，不允许调用者拼接真实绝对路径。agent 根目录加入系统备份/设备迁移排除，与 mca.db 排除保持一致；凭据继续沿用既有排除规则。

当前 Room 数据库没有因本方案自动获得内容加密。应用私有目录和 Android 文件级加密不等于独立数据库加密；文档/设置不得宣称“所有 Agent 数据端到端加密”。SECRET 级结果默认不进入事件/日志/导出；必要敏感 continuation 单独用 Keystore AES-GCM 实现，禁止复用或导出云 API key。

### 10.3 文件/数据库提交协议

数据库与文件系统之间没有跨资源原子事务。产物提交使用可核账阶段：

1. 数据库登记 invocation RUNNING、artifact PREPARING、commitId 和期望目标。
2. 在 staging 写临时文件，close/flush；计算 SHA-256、大小并校验 quota。
3. 在同一私有文件系统原子 rename 到以新 artifactId/commitId 命名的不可变物理版本；逻辑 path 与物理 storage_ref 分开，不原地覆盖旧版本。close/flush 不等于掉电持久化，支持时执行文件 fsync 与目录同步；失败保留可核账状态。
4. 数据库再次核对逻辑 path 的 expectedOldHash，原子提交新 artifact COMMITTED、最新版本映射、invocation SUCCEEDED、事件和结果引用；旧版本仍保留可恢复引用，拒绝覆盖冲突。
5. 清理旧 staging/备份；清理失败进入有界 maintenance 队列。

崩溃注入必须覆盖每一步。若文件已 rename 但数据库未提交，恢复通过 commitId/hash 核账，不能重新调用模型或重复写入。若文件不存在而 DB PREPARING，恢复为失败/需核实；不能凭 DB 行生成空产物假装成功。

同一路径的最新版本由已提交 artifact 的提交序号确定，task+logicalPath+version 唯一；没有 DB 确认的新物理文件是待核账候选，不自动成为最新产物。磁盘掉电/损坏超出普通进程死亡测试时单独报告，不能用 rename 一个动作宣称抵抗一切断电或跨资源 exactly-once。

### 10.4 恢复策略

| 中断点 | 恢复动作 |
| --- | --- |
| QUEUED/WAITING_RESOURCE | PAUSED，用户继续后重新准入 |
| 模型生成中 | INTERRUPTED；不重用旧 native attempt；用户继续创建新 run |
| 等待审批 | 原审批 EXPIRED，需重新冻结/审批 |
| 只读工具 RUNNING | 可标安全重试；实际继续仍经新 run/命令 |
| 私有产物提交中 | 先按 commit journal/hash 核账，再决定完成或 UNKNOWN |
| 外部副作用未知 | NEEDS_REVIEW；V1 不静默重试 |
| 已提交终态 | 只读展示；不再次执行 |

Force stop、设备重启、权限撤销不承诺自动后台运行。启动恢复不自动获取网络或推理资源。

等待审批时中断且没有执行记录，原 invocation 以 NOT_EXECUTED(reason=INTERRUPTED_BEFORE_EXECUTION) 结算，并补齐配对结果；“重新审批”指新 run 的新 invocation，不复用旧批准。UNKNOWN 结果仍配对，但不可被模型自动重试。执行过的模型/工具预算按真实事实保留，创建新 run 不篡改旧消耗。

### 10.5 配额与删除

- V1 建议软阈值：每 task 256 MiB，全部 Agent 文件 1 GiB；磁盘真实不足时返回 STORAGE_NO_SPACE，不丢历史。
- 普通事件 payload≤64 KiB，模型上下文摘录≤16 KiB；完整资料/产物通过引用访问。
- 不把每个 token 写 Room。流式展示与持久检查点分离，默认 250ms 或 4KiB 合并一次，终态强制 flush；记录中断后可能损失的未提交尾段。
- 默认不自动删除用户资料和已完成产物。30 天后可清理没有引用的 staging、已结束临时缓存和过期审批敏感内容；不删除用于核账的状态/hash。
- 删除任务先要求无 active run；运行时选择取消并等待停止，再标 TOMBSTONED。数据库事务删除关联记录，文件异步删除可重试；不得误删其他 task root。
- 索引至少包含 tasks.updated_at、runs.task_id、events(run_id,sequence)、invocations(run_id,status)、artifacts(task_id)。事件查询按 cursor/sequence 分页。

### 10.6 导出与导入

V1 采用 SAF CreateDocument 的版本化 ZIP，manifest 格式 mca.agent.export.v1，包含选中任务摘要、事件的脱敏表示和用户选中的产物，附 SHA-256/size。默认排除 credentials、授权 grants、opaque provider state、原始调试日志、SECRET payload。

导出前说明文件范围与是否包含私人资料；V1 不自创密码学备份格式。若用户需要加密全备份，作为单独格式升级和安全评审。系统自动备份继续排除 Agent 数据。

导入压缩包和展开总量均≤512 MiB、最多 2,000 个 entries、单 entry≤256 MiB、路径层级≤16、每entry压缩比≤100:1（含流式计数，不能只信 ZIP header）；拒绝路径穿越、绝对路径、符号链接、规范化重复路径、加密/未知压缩方法和超限展开。先校验 manifest/hash 后提交；未知 major 拒绝，已知 major 的可选未知字段保留为未执行 metadata。导入生成新 task/run ID，task.lifecycle=IMPORTED_ARCHIVED；不恢复执行、审批、网络授权或 API key。

## 11. 威胁模型、网络和隐私

### 11.1 信任边界

| 输入/执行者 | 风险 | 代码侧控制 |
| --- | --- | --- |
| 用户资料/网页标题/工具结果 | prompt injection、伪造 system 指令 | 低信任内容标签；工具必须经过 broker |
| 模型输出 | 非法 schema、越权路径、伪造完成 | 完整协议校验、真实产物核对 |
| Skill 包 | 路径逃逸、工具/网络权限提升 | 安装校验、来源/hash、运行工具交集 |
| MCP server | tool poisoning、schema 漂移、结果注入、网络泄露 | schema snapshot、命名空间、审批绑定、egress |
| 自定义模型 endpoint | 数据外发、重定向到恶意地址 | 明确 provider origin、TLS/重定向策略、egress 授权 |
| 已授权写操作 | TOCTOU、重复提交 | hash/precondition、one-shot consume、幂等/核账 |
| 插件原生/任意代码 | 绕过进程内 broker | V1 不运行任意代码；未来需真正隔离，不靠约定 |

### 11.2 离线强制语义

离线策略必须由 app 的 Agent transport/egress 层强制执行，不能只靠 prompt“不准联网”。LOCAL_ONLY 模式允许本地模型、离线文件工具，不允许云模型、远程 MCP、浏览器/search；无合法 transport 即无法建立连接。

任务运行期间切为离线，递增 policyVersion、撤销未消费的网络审批、取消自己的在途网络请求并记录结果可能未知。已经发出的数据无法召回，UI 不应声称撤回成功。

只影响 Agent 自己的网络，不改变整个 MCA 应用现有聊天/下载配置。模型/插件包预安装下载属于准备阶段，不能把安装下载计为“任务离线推理”。

### 11.3 Egress 预览

第一次云模型步骤前展示 provider origin、模型、目标/用户输入、将发送的资料类别和具体文件引用。用户可查看有界预览并在本 run 授权固定范围；新增资料或目标 origin 需更新审批。

预览的来源清单从实际即将序列化的 payload 投影，覆盖 Agent SYSTEM/schema、选中 Skill 正文/资源、用户输入、工具结果、检索片段、用户确认的摘要及 provider continuation 的来源标记。每个内容块携带 sourceId/hash/dataClass；组装后、发送前复核授权范围。内部控制字段和不透明 state 可以不向用户展示原始内容，但不得成为夹带未授权资料的旁路；展示范围与脱敏 payload capture 必须一致。默认 PRIVATE 工具结果只有在该 run 已批准其来源/类别时才能续传云端。

工具结果标记 PUBLIC/PRIVATE/LOCAL_ONLY/SECRET。LOCAL_ONLY 和 SECRET 不自动进云上下文；要发送必须先经明确的资源级授权或安全脱敏。MCP 服务器同样适用，不因“工具调用”绕过数据外发控制。

网络 adapter 限制请求超时、响应大小、重定向跳转和认证转发。远程 MCP 不自动跟随到 loopback/private IP；确需本机 server 使用单独 LOCAL_SERVER 配置与明确地址权限。DNS/IP 校验、重定向后重校验和 TLS 合同必须有测试，不把 localhost 当身份认证。

Agent 远端连接默认只允许 HTTPS，保持正常证书/hostname 校验；V1 自动重定向关闭，3xx 显示 origin 变化并要求用户更新配置。LOCAL_SERVER 可显式选择本机/私网的精确 host:port 与必要明文范围，凭据不得从远端配置自动继承。地址校验覆盖 IPv4/IPv6/IPv4-mapped、loopback/private/link-local/multicast/未指定地址；每次新连接重新检查，受控 DNS 仅返回已经验证的地址并绑定实际连接路由，禁止“验证一次再让系统重新解析”。V1 不接入未受审的代理/自定义DNS旁路；若既有云端配置需要此能力，明确提示 Agent 暂不支持该网络配置，保持聊天旧行为不变。连接池/重连/本机授权撤销同样参与 policyVersion 检查。

### 11.4 日志、通知和审计边界

日志只记录 request/run/attempt ID、阶段、耗时、错误码、字节数和脱敏 fingerprint。调试原文默认关闭且不得包含 API key、完整文件、工具参数 secret。

通知默认“Agent 正在执行任务 / 等待授权 / 已完成”，不显示目标全文、文件名、prompt、模型推理文本或密钥；点击进入当前任务。锁屏保持 PRIVATE 可见性和通用 publicVersion。

事件通过 repository 只追加，不提供插件任意 update/delete 接口。该设计防止合法扩展路径篡改审计，不宣称能抵抗 root、同 UID 任意代码或被攻破的应用进程。

### 11.5 提示注入最小语料

验收至少包含：工具输出要求升级权限；文件名/网页标题写系统指令；伪造 tool_result；资料内合法工具 JSON；双 object；Markdown fence；重复 key；路径逃逸；符号链接；Unicode 混淆工具名；假 final 产物；请求读取云密钥；本地资料上传；跨 MCP server 凭据；schema 替换；批准后换参数；过期批准；拒绝后重试；reasoning 内工具 JSON；流式 partial JSON；截断 Done；tool result 冒充用户授权；重定向到本机；zip slip。

这些 case 检验代码侧权限与解析不变量，不能据此宣称解决所有模型 prompt injection。

## 12. Skills 与 MCP V1 兼容规范

### 12.1 Skills

V1 支持用户显式导入的目录或 ZIP，包含根 SKILL.md；导入后生成 MCA 安装 manifest：id、name、description、schemaVersion、packageVersion、source、sha256、entryPath、enabled、declaredToolPatterns、resourcePaths。

兼容 SKILL.md 常见 name/description 元信息。V1 采用受限 frontmatter 子集：单行字符串、字符串列表和明确 block scalar；不支持 YAML 自定义 tag、对象构造、anchor/alias。遇到不支持写法返回可解释错误或仅按普通 Markdown 预览，不静默把错误元信息当授权。别的 harness 专属字段保留为未执行 metadata。

资源仅允许 Markdown/文本/已支持附件。scripts 可展示来源但 V1 不执行；Skill 声明 bash/python/node 不会自动安装运行时。解压遵守第10.6节限制。

目录/SAF树与 ZIP 一样执行文件数、展开字节、单文件、层级和规范化重复路径限制；只复制普通文件为私有快照，不跟随软链、循环 document ID 或 provider 返回的根外条目。SKILL.md UTF-8≤64KiB；name≤64字符、description≤2KiB；最多同时启用4个 Skill，实际注入总量仍受上下文预算约束，不以静默截断使脚本或限制语义改变。V1 由用户明确选用 Skill，不从不可信资料自动安装/启用。

工具实际可用集合 = 用户当前任务授权 ∩ 工具注册表启用能力 ∩ Skill 声明允许范围；声明只能收窄，不能扩大。动态选择 Skill 后记录 hash/version，运行中更新包不改变当前 snapshot。

allowed-tools缺省只表示“不额外限制”，仍不能超出前两项；存在时V1仅支持完整工具key或`workspace.*`、`artifact.*`、`mcp:<明确serverId>:*`，不支持regex/任意glob。多个Skill同时生效取限制交集，冲突在启用预览说明；不能合并成权限并集。任务授权不是工具已批准执行的同义词：读取范围可ALLOW_FOR_TASK，写入仍按第8节ASK。

更新只生成新版本，不替换当前 run 内容；显式停用/卸载属于撤销，递增 policyVersion、使旧审批失效、禁止后续调用。活动调用只做安全取消/核账，已经提交的副作用保留；迟到成功回调不能恢复被撤销的授权或启动下一步。卸载不能删除历史 run 引用的内容快照，待无引用后再 GC。

宣称“Skill 格式兼容”不等于其中所有 shell 步骤都可执行。UI 明确显示缺失工具/未支持脚本，不能把文本导入算作完整执行兼容。

### 12.2 MCP

V1 transport 为 Streamable HTTP，协议候选固定 MCP 2025-06-18；实施时按正式规范与 fixture 锁定 exact negotiated version。旧 HTTP+SSE 和 stdio 不自动假称支持；需要单独 adapter/测试后登记。

生命周期：用户创建 server 配置 → 明确连接授权 → initialize/version negotiation → tools/list → schema snapshot → tool invoke → bounded result/cancel → disconnect。断线时只重连会话和重新发现；不会自动重放有副作用 tools/call。

V1 tools-only 线协议按 [MCP 2025-06-18 transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) 冻结以下子集：

- 发送 JSON-RPC 2.0 initialize；只有协商为明确支持版本后，发送 notifications/initialized，随后才 tools/list/call。POST Content-Type=application/json，Accept 同时包含 application/json 与 text/event-stream；通知的 HTTP202 不当作 tool result。
- initialize成功结果必须声明result.capabilities.tools，否则本client标该server为工具不兼容；仅在result.capabilities.tools.listChanged=true时依赖相应变更通知，未声明时新run主动重新发现。
- initialize 响应若含 Mcp-Session-Id，仅当前 server/origin 使用；后续携带该 header 和 MCP-Protocol-Version。仅携带已建立session的请求返回404可作为session失效，重新握手与发现但不重发旧tools/call；无session/首次endpoint的404为地址错误。合法无状态服务应工作。401/403分别报认证/授权错误，不自动OAuth或循环重试。
- 同步 JSON 与 POST 返回的 SSE 响应都要解析；SSE只消费完整data消息，response核对JSON-RPC id，notification按method分流。V1 不依赖自动 Last-Event-ID 续传，不因重开 SSE 重放工具；可选独立 GET stream 不作为 V1 必需能力。需要其才能完成工具的服务列为不兼容。
- tools/list 按 nextCursor 最多10页/合计1,000工具完成；游标循环、超限或半途失败则本次注册失败，不能把不完整列表声称完整。单schema≤64KiB、注册集合≤1MiB；投影到模型仍必须满足context预算。
- 只声明已实现的 client capabilities；服务端 ping 正常响应，未支持的 server request（sampling/elicitation/roots等）返回 JSON-RPC Method not found，不代替用户执行。未知通知有界忽略；notifications/tools/list_changed 重新发现、比较hash并使旧审批失效。
- notifications/cancelled 使用原 requestId，message/SSE总响应预算≤256KiB、deadline默认30秒。isError 工具结果与 JSON-RPC error 分开映射；错误结果仍与调用配对，晚到响应只供核账，不推进下一步。

结果映射固定如下：content中的text块按原顺序作为低信任工具文本，structuredContent仅在通过深度/大小限制后作为JSON数据保留；两者都经过来源标记、结果摘录和egress检查。image/audio/blob不在V1文本模型能力内，不自动解码、转视觉模型或下载，返回明确unsupported block摘要及有界metadata；resource/resource_link仅保留描述和引用信息，不自动读URI或发起另一请求。未知block同样明确标记，不拼成控制协议。isError=true映射FAILED，JSON-RPC error映射稳定TOOL/TRANSPORT错误，两者都保留对应调用的唯一结果；MCP已返回成功只证明远端声称完成，不能当本机文件存在的证据。

disconnect先关本机流与请求，再对支持的有状态会话best-effort HTTP DELETE；405表示服务不提供该终止方式，不循环重试，也不声称远端session已销毁。本机凭据/授权清理不依赖DELETE成功。

namespace：mcp:<serverId>:<toolName>。serverId 是本地稳定 ID，模型可见名称通过受限 ASCII 映射并保持映射表；重名不能覆盖内置工具。

NATIVE provider名称映射固定为`mcp_`加SHA-256(serverId、原始toolName的长度前缀字段)前32位小写hex，保持≤64字符的ASCII标识；映射表保留原始名字和schema hash，检测碰撞即拒绝登记。用户展示名与执行key分开，不把远端恶意名称直接当路径或协议方法。

server config 和 schema hash 持久化；进程重启后保持注册配置但默认未连接。schema/version/server origin 改变使旧审批失效，受影响工具需重新确认。删除 server 撤销连接、token 和 grants，不删其他 server 配置。

删除/停用 server 时取消自己的在途 Call、失效审批与 grants、移除本机 credential reference；副作用未知记 UNKNOWN，并阻止迟到回调启动后续步骤。远端 token 只有服务端提供受支持 revocation endpoint 才能尝试撤销，否则明确仅删除本机副本、远端有效期未改变。V1 采用用户配置的静态 bearer/API-key 或无认证模式；OAuth发现/动态注册不是默认已支持能力，要求OAuth的服务明确提示不兼容。

请求 ID 唯一；默认工具 timeout 30 秒、响应≤256 KiB，送模型的结果仍≤16 KiB。支持规范取消通知的服务发送 cancel；远端是否真的停止不可假定，副作用不确定时 UNKNOWN。

MCP server 返回工具 annotations/hints 只作为提示，不改变 broker 的副作用等级。未知工具默认有副作用 ASK，禁止因为 readOnlyHint=true 就自动授予读取私有文件或网络。

broker约束本机发送什么数据、允许调用哪个动作，不能证明远端server内部只读、停止执行或不再转发已收到的数据。远程MCP属于用户明确选择信任的外部执行者，UI显示server来源和可能副作用；恶意返回内容仍按低信任数据处理，不授予本机权限。

### 12.3 DSH Host 决策门

进入实现前必须同时提供：

1. 用户确实需要且无法由原生/MCP 替代的具体 DSH 插件清单和版本。
2. Node/Bionic/runtime/native dependency 的 Android ABI、现代 targetSdk 和生命周期验证；不能为运行 runtime 静默降低 MCA targetSdk。
3. 版本锁定、来源/签名或 checksum、许可证、补丁回放和升级回滚方案。
4. 精确的工具桥、授权/egress、输出/进程预算和断连/取消合同。
5. 安全隔离证明：独立 :process 只是 crash 隔离，同 UID 不是恶意插件沙箱；PRoot 也不是安全边界。若不能隔离任意插件代码，必须明确为用户信任代码模式，不宣称 broker 可阻止其全部旁路。

Host 不运行第二个 MCA Agent Loop；首选导出限定工具供 MCA 调用。若 DSH 无法独立导出插件工具、必须让其拥有完整任务循环，则作为独立 harness 模式提出新 ADR，不能把两个 owner 隐藏在同一个 run 内。

## 13. 原生页面、导航与视觉合同

### 13.1 精确导航链

~~~text
ChatHistoryDrawerScreens：图片按钮下一行 Agent
 -> ChatScreen 的 onOpenAgentWorkspace
 -> 现有 ChatShellActions / ChatViewModel 具名导航转发
 -> MainChatScreenActions
 -> host.onTab(AppTab.AGENT_WORKSPACE)
 -> MainActivity 同级 AgentWorkspaceRoute
~~~

现有 onOpenAgent 仍打开“智能调参”。不重命名现有 enum ordinal/持久值造成历史 tab 恢复错位；若 tab 被 ordinal 持久化，先改为稳定映射并迁移。

在原 AppFeatureButton 范式中新增紧邻“图片”的一行，沿用同 icon family 的既有合适任务/自动化图标。测试 tag：drawer.agent_workspace；可访问名称“打开 Agent 工作区”。保留旧“最近”区域的内容与行为，只接受新入口本身带来的必要垂直位移。

### 13.2 页面信息架构

首页：安静顶栏“Agent”+ 新任务主操作，运行中任务置顶、最近任务列表；模型/离线模式作为次级控制。空态直接提供目标输入，不放宣传 hero、KPI 卡片或假示例执行记录。

详情：任务目标/当前状态 → 最终回答和紧凑执行活动 → 产物入口 → 底部输入/继续/停止。重复工具活动折叠为紧凑行；详细参数/日志按需展开。使用用户可理解的进度摘要，不默认展示原始内部日志或 chain-of-thought。

输入区支持多行、附件、模型选择、离线/联网标记；运行时主操作变为停止。用户滚离底部后不强制自动滚动，提供“回到最新”动作。

审批视图必须显示工具动作、读写/网络目标、参数摘要、覆盖前后变化、有效期和允许/拒绝。默认焦点不在破坏性批准上；长内容仍能访问操作。

### 13.3 UI 合同

| 项目 | 决策 |
| --- | --- |
| 任务模式/表面 | EXISTING_SYSTEM / android-native |
| primary_job | 提交任务、看懂执行状态、处理必要授权、取得产物 |
| 层级 | 任务/结果 > 工具活动 > 文件/context > 全局 chrome |
| 主题 | core:designsystem 的 McaTheme；沿用既有 Google-grey 与蓝色语义色 |
| 结构 | 默认列表与分隔，不给每个工具事件加大卡片 |
| scroll | 每个主页面单一 LazyColumn；sheet 内容单独滚动 |
| 输入 | IME inset + 安全区域；底部空间跟输入高度对应 |
| 回退 | sheet先关闭→详情回列表→工作区回原入口；返回不自动停止 |
| 恢复 | task/run ID、草稿、列表定位可恢复；恢复不触发 start |
| 动效 | 仅沿用既有导航和状态变化；无持续装饰动画 |
| 大屏 | 先保证手机单列；只有确认现有 adaptive 合同时才增加列表/详情双栏 |

### 13.4 状态与视觉验收

必须覆盖空态、载入、生成、等资源、等审批、拒绝、失败、停止中、取消、已完成、进程恢复、无模型、协议不支持、预算不足、MCP 断线、Skill 缺工具。

真实渲染检查浅/深色、既有自定义背景适用状态、长中文/英文/代码/表格、大字体 1.0/1.5/2.0、键盘开闭、TalkBack、紧凑/较大手机窗口。触摸目标至少48dp或保持更严格的现有规范，不能靠截字隐藏关键按钮。

新页面做两轮渲染审查。现有聊天/图片/调参使用固定基线对比；除了授权新增的侧栏入口，不混入布局/配色整理。未拿到真实渲染证据时视觉结果 NOT_RUN，不自评分为生产精修完成。

## 14. 后台执行与进程合同

V1 AgentRunOwner 常驻于应用作用域，页面 VM 只观察。运行模型/工具期间获取必要的现有 FGS lease；等待审批/资源/用户时释放 native lease，不维持无目的永久常驻进程。

前台服务类型建议沿用现有生成服务的 specialUse 合法用途路径，新增 Agent 任务用途/通知 channel 前必须核对 targetSdk36 的运行限制、manifest subtype 和发行要求。不得误用 dataSync 类型制造无限执行；不得降低 targetSdk。若现有服务 API 语义不适合，只能新增薄 platform service 连接同一 AgentRunOwner，不能在 service 内创建第二 owner/runner。

启动前先按 Android 前台启动规则创建可见通知；通知权限受限时提示具体运行限制，不把缺通知权限等同 native 模型不可用。必要的平台 FGS 启动失败转 PAUSED/FOREGROUND_SERVICE_UNAVAILABLE。

Service/notification stop action 带 runId/epoch，由 owner 校验。一个陈旧通知不能取消当前其他任务。scope 关闭或应用有序退出先拒绝新命令、取消自己的子工作、等待有限清理并保留中断记录。

## 15. DSH 能力域对标与超越 third-party 的验收路线

本节都是计划目标，尚未实施。能力域相似不代表已超过竞品；只有在相同任务、权限、模型/设备约束和可重复证据下才能作比较结论。

| 能力域 | MCA V1 计划范围 | 后续阶段 | 对标证据 |
| --- | --- | --- | --- |
| 原生独立工作区 | 完整 | 适应大屏 | 主入口/返回/状态独立/渲染 |
| 本地/云模型多步 Agent Loop | 完整限定协议 | 更多 native FC backend | MNN/GGUF/云分别端到端 |
| 类型化工具与审批 | 完整 | 更多工具 | schema、哈希、拒绝、取消测试 |
| 任务/步骤/产物/恢复 | 完整 | 更复杂 checkpoint/workflow | crash points、幂等、导出 |
| Skills | 文档/资源兼容子集 | 安全脚本执行 | 格式矩阵和真实 Skill 场景 |
| MCP | Streamable HTTP + schema 子集 | stdio/旧传输/更多schema | server fixture与断线/漂移 |
| 浏览器/网页交互 | 不在V1 | DOM refs、操作验证、进程隔离 | 页面 generation、错误目标保护 |
| 本地生图工具 | 不在V1 | 正式 memory handoff + reload | 本地 LLM→图像→精确恢复 |
| 终端/代码执行 | 不在V1 | 可选受限执行环境 | kill/timeout/path/sandbox |
| Android自动化 | 不在V1 | Accessibility/Shizuku/vscreen | 明确权限、撤销、目标验证 |
| 子Agent/工作流 | 不在V1 | 共享预算、父子取消、结果汇总 | 无循环委派、资源预算 |
| Cordis插件原样复用 | 不在V1 | DSH Host决策门通过后 | 指定插件版本兼容矩阵 |

“超越 third-party”按以下可比较维度验收：本地模型与既有 MCA 资源体系一致；授权与 egress 可核查；执行/停止/恢复不串任务；产物和副作用可核账；插件不能绕过核心策略；UI 保持手机原生一致性。未完成浏览器/终端/Android 控制时，不宣称功能广度已全面超过 third-party。

### 15.1 外部项目的已核验参考与不能照搬的部分

2026-10-19 只读核验官方/用户指定 GitHub 仓库的 metadata、固定 commit 的下列文件；没有克隆、构建或执行这些项目。README 的能力声明按“项目自述”处理，不是本轮实测结论。后续复用按实际文件及依赖核对许可证，不能只看仓库顶部标签。

| 项目与固定审查版本 | 核验入口 | MCA 采用的思想与边界 |
| --- | --- | --- |
| DSH：687f282e8625c6b8d11ff58211a90524be2e4317 | [架构](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/docs/architecture.zh.md)、[package.json](https://github.com/deepseek-ai/deepseek-harness/blob/687f282e8625c6b8d11ff58211a90524be2e4317/package.json) | Cordis 将模型、工具、日志、loop 做成插件；学习类型化能力、工具流水线与日志可追溯。其 Node 依赖声明为 ^22.19.0 或 ≥24.0.0，不等于 Android 原生 SDK；MCA 的核心安全/owner 不做任意可替换插件 |
| third-party：dbf71916fae9750cfdc9f9a774f5a0fee56633fb | [README](https://github.com/AAswordman/third-party/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/README.zh-CN.md)、[SkillManager](https://github.com/AAswordman/third-party/blob/dbf71916fae9750cfdc9f9a774f5a0fee56633fb/app/src/main/java/com/ai/assistance/thirdparty/core/tools/skill/SkillManager.kt) | 自述含MNN/GGUF、终端、工作区、Android控制、Skill/MCP；代码有metadata解析、快照和ZIP导入。学习移动任务体验与扩展管理；不嵌入应用、不照搬共享Downloads技能目录，也不把它称为只能依赖云端。仓库metadata为LGPL-3.0 |
| DSH Android：98162a23b39eeeb75b8d4608b3e773bbb13be43d | [构建说明](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/README.md)、[Manifest](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/android-app/AndroidManifest.xml)、[第三方声明](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/THIRD_PARTY_NOTICES.md)、[功能自述](https://github.com/woaiys3/deepseek-harness-android-app/blob/98162a23b39eeeb75b8d4608b3e773bbb13be43d/README.md) | Native壳打包DSH/Node-Bionic；README要求targetSdk28，Manifest确为28，不能作为MCA targetSdk36的可行性证明。学习本机桥鉴权、浏览器tab/ref校验、故障账本与不删用户数据的修复；不采用降target方案。仓库MIT标签不能覆盖其中明确源自第三方的LGPL虚拟屏模块 |
| Codex：8c076942cb70fba8e5c4732c23f1faabc4aed484 | [README](https://github.com/openai/codex/blob/8c076942cb70fba8e5c4732c23f1faabc4aed484/README.md) | 该入口定位为本地计算机CLI；本轮未核验Android兼容性。可作为后续harness交互参考，不能据此保证CLI直接嵌入手机可用；MCA V1不依赖Codex二进制或订阅 |

MCA 原生路线的收益在于复用已有手机推理、Compose、资源lease和数据工程；代价是终端/浏览器/Android控制及全部JS插件必须逐项建设。嵌入另一整套应用/loop虽然可能较快获得演示广度，却会引入第二份状态、运行时、UI和维护链；因此本方案选原生核心、协议扩展和后置受控Host。

### 15.2 “超越”的比较任务和判据

比较集固定为：离线资料报告、拒绝一次写入后继续、长任务精确停止、杀进程后核账、MCP断线/漂移、恶意Skill试图外发、本地模型与图片竞争、真实Android/浏览器任务（扩展后）。两产品使用相同可共享输入与尽量相同模型；模型不同或能力缺失分别标明，不能归因成架构优劣。

每组至少10次，报告完成率、越权/重复副作用、恢复正确率、用户授权次数、停止四阶段耗时、全进程内存、耗电和操作步数；保留每个失败。只有覆盖对应能力域且没有安全/数据硬失败，才可对该维度称为达到或超过参考结果。未实际安装/运行竞品时，仅交付MCA绝对指标，不填竞品零分。

### 15.3 完整 Agent 体系的后续切片合同

以下 E 卡不属于 V1.0；进入时仍执行相同架构硬锁、任务写集和证据格式，不预建空接口。每卡单独立项与ADR，不要求为了V1先实现全部。

| 卡 | 明确交付/owner | 强制边界与放行证据 |
| --- | --- | --- |
| E01 本地生图工具 | app ImageToolAdapter调用既有ImageExecutionOwner；AgentRunOwner仍是任务owner | 捕获本地binding→既有exclusive handoff/unload→确切image worker退出→既有load owner恢复原模型/profile→核对fingerprint再继续；取消不影响其他图像/模型；完整链实测10次，失败明确暂停 |
| E02 手机浏览器工具 | 独立浏览器组件/进程owner，工具仅通过broker桥接 | tabId+documentGeneration+origin+nodeRef绑定；导航后旧ref失效，点击后检验目标结果；HTTP(S)/URL与凭据策略、下载/上传独立授权；Web崩溃只回收自有进程，登录/cookie不暴露模型；跨tab/ref/重定向/弹窗fixture和真实网页任务通过 |
| E03 脚本/终端环境 | 专门ExecutionHostOwner，进程组/会话/IO有界 | 先做targetSdk36+ABI+打包执行可行性；任意代码要有独立身份或可证明的WASM/hostcall隔离。仅同UID子进程/PRoot只能标可信代码模式，不能承诺broker阻止旁路。timeout/kill整个自有进程组、文件挂载/网络权限/密钥边界和崩溃恢复通过后才注册；不为运行Node/Python降低target |
| E04 Android控制 | Accessibility/Shizuku各自窄platform adapter，用户可随时吊销 | 用户明确系统授权；app/window/display/snapshot版本绑定，操作前目标重核对，敏感支付/安装/删除/权限变更逐项确认；锁屏/前台变化/授权撤销拒绝旧动作；Shizuku非默认依赖、普通设备不因此失去文件Agent能力 |
| E05 子Agent | 父任务图由同一Agent runtime管理，child有独立run与会话 | 首版最多2个child、深度1，共用父预算，授予权限只能缩小；本地推理仍串行共享同一engine，云并行有界；父取消传播但不伪撤销已提交副作用；循环委派、预算超额、旧child回调测试100%通过 |
| E06 工作流/调度 | 版本化DAG与平台调度adapter，不另建循环owner | 每节点复用现有模型/工具合同，失败/重试有幂等边界；定时/开机仅用户显式创建并受Android限制，过期批准不能自动使用；中断/重复触发/设备重启不会重复外部副作用 |
| E07 完整DSH插件Host | 第12.3节门全部通过后的独立adapter | 锁定插件+DSH+runtime版本与许可；桥接tool而非隐含第二loop；10个真实需求插件先建兼容清单，按文件/网络/进程/持久化逐项测试；不能安全承载的插件明确不支持，不能把Skill导入包装成Cordis兼容 |

推荐顺序为E01/E02→E03/E04→E05/E06；E07仅由真实插件需求推动。每个新增工具先加descriptor/schema/权限/故障fixture，再连实际executor；核心状态机不为每个新工具新增特例。

## 16. 兼容、架构门禁与回退

### 16.1 必须更新的工程接线

- settings.gradle.kts、app/build.gradle.kts、各新模块 build.gradle、固定版本 catalog。
- tools/architecture/check_architecture_rules.py：新增模块平台 import、依赖方向/循环、feature→engine/app/chat 禁止、运行时构造点、无类型注入差量。
- tools/architecture/generate_architecture_manifest.py：登记新模块。
- scripts/verify-code.ps1：compile/test任务、-Changed模块映射、untracked提示、-FullTests与结果聚合；-FeatureTests 的既有 chat 语义不能静默变成“全 feature”。
- 新增有名称的 -AgentTests 参数，或等价明确受控入口；必须实际执行 contract/runtime/feature/app integration 测试，拒绝0项执行假通过。
- Room schema/迁移列表、备份/数据迁移规则、SAF导出入口、FGS manifest/通知合同。

本方案不修改这些脚本；实施任务必须检查脚本实际参数/任务，不能直接假定这里拟定的新参数已经存在。

### 16.2 回退策略

每个切片独立 diff，整合者保留回退点。内部开发开关可控制 Agent rollout，但不能按芯片/设备 profile 隐藏入口。V1 正式交付开启入口，开关仅用于候选版验证/事故回退。

新增数据库迁移后禁止把“回退 APK”当作安全数据库回退；若旧二进制不理解新 schema，应保留新版本并前向修复/关闭 Agent 能力。禁止降级清库。新增表不改旧聊天数据；回滚 UI/runtime 时仍保留可导出 Agent 数据。

任何临时 adapter 都记录生产调用者、保留理由、退出条件和后续切片。不要为了本功能借机重写全局主 VM/导航/存储/全部 provider。

## 17. 阶段门和事实证据

| 阶段 | 设计/实施范围 | 放行 |
| --- | --- | --- |
| P0-D 设计前置 | owner-map、RFC、schema草案、威胁模型、依赖与风险 | 文档一致且源码证据可定位 |
| P0-T 技术Spike | 未来实现最小 headless MNN/GGUF/云循环与 scoped cancel、真实lease | 真机/原始日志，禁止mock冒充 |
| P1 原生闭环 | 模块/owner/数据库/文件工具/页面/后台/恢复 | 关键安全和数据用例100%通过 |
| P2 V1扩展 | Skills+MCPHTTP、egress、导入导出、配额与门禁 | 限定兼容声明、全适用矩阵通过 |
| P3 V1发布候选 | 既有功能回归、升级/长任务、视觉/性能 | 当前revision+APK绑定，无阻断项 |
| P4能力扩展 | 图像/browser/terminal/Android/DSH Host | 每个独立ADR和真实验证，不搭便车 |

当前状态：P0-D 的方案已编写；其余执行证据全部 NOT_RUN。V1 的完整实施顺序和 go/no-go 见执行包，测试逐项见验收矩阵。

### 17.1 风险登记与停止条件

| 风险 | 影响/责任卡 | 必须采取的措施与停止条件 |
| --- | --- | --- |
| RISK01 本地模型不稳定输出协议 | 工具闭环不可用；K02–K04 | 严格decoder+一次repair+逐模型统计；不猜调用、不用云掩盖本地失败；未过G0不接生产工具 |
| RISK02 取消串任务或lease提前释放 | 聊天/图片损坏、native竞争；K04/K11 | 非抢占acquire+唯一ID+真实停止fence；发现一次全局误停/旧回调串写即阻断候选 |
| RISK03 云端/插件资料外发 | 隐私泄露；K07/K13 | 实际payload来源授权、策略client、跨origin认证隔离；任何未授权字节即硬失败 |
| RISK04 DB/文件跨资源崩溃 | 数据丢失/重复产物；K05/K06/K14 | 事务journal+不可变文件版本+核账；不确定副作用UNKNOWN；不清库/静默重放 |
| RISK05 Android后台限制 | 任务中断；K11 | target36合法FGS用途、启动失败暂停、恢复不续跑；不通过降target/常驻hack绕过 |
| RISK06 新模块只是形式隔离 | 主VM/Host继续膨胀；K01/K08/K15 | 依赖正反fixture、唯一writer/生产调用审查；新增反向依赖/万能能力不得完成 |
| RISK07 桌面插件兼容被高估 | APK体积/任意代码/许可风险；E03/E07 | pinned来源、现代target验证、真实隔离；未过Host门只保留Skills/MCP限定声明 |
| RISK08 依赖/规范随时间变化 | parser/MCP运行不一致；K00/K02/K13 | 精确版本+hash+兼容fixture；变更版本先记录差异，不自动升级或宽松降级 |
| RISK09 文档与实际基线漂移 | 错改旧导航/迁移；每卡 | 重读当前源码、写集和diff；事实变化先形成差异决策，保留已有并行改动 |
| RISK10 连接EOF被误判成功 | 截断响应触发工具；K04/K08 | Agent严格terminal策略+各provider截断fixture；Done无协议完成证明就不得执行 |

进度以gate证据计，不以工期承诺倒推通过。设备/模型/网络条件缺失时明确阻塞项，允许独立文档/fixture准备；不允许把未达门槛的新能力开放为“已完成”。

## 18. 源码证据索引

本表是读过的入口与符号，不是运行通过报告。链接指向当前仓库文件；行号不作为长期合同。

| 主题 | 入口 |
| --- | --- |
| 规范 | [MCA_PROJECT_HARD_LOCK](../../architecture/MCA_PROJECT_HARD_LOCK.md) |
| 既有所有权ADR | [ADR0001](../../adr/0001-runtime-ownership-and-identity.md) |
| 现有调参页 | [AgentScreen](../../../feature/agent/src/main/java/com/muyuchat/feature/agent/AgentScreen.kt) |
| 导航enum | [MainViewModel](../../../app/src/main/java/com/muyuchat/mca/MainViewModel.kt) |
| 侧栏入口 | [ChatHistoryDrawerScreens](../../../feature/chat/src/main/java/com/muyuchat/feature/chat/ChatHistoryDrawerScreens.kt) |
| 动作链 | [MainChatScreenActions](../../../app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt)、[ChatScreenActions](../../../feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreenActions.kt)、[ChatViewModel](../../../feature/chat/src/main/java/com/muyuchat/feature/chat/ChatViewModel.kt) |
| 生产装配 | [McaAppContainer](../../../app/src/main/java/com/muyuchat/mca/composition/McaAppContainer.kt)、[MainRuntimeAssembly](../../../app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt) |
| 模型owner/funnel | [ChatExecutionFacade](../../../app/src/main/java/com/muyuchat/mca/facade/ChatExecutionFacade.kt)、[ChatExecutionOwner](../../../core/engine/src/main/java/com/muyuchat/core/engine/ChatExecutionOwner.kt)、[ChatLocalApiProviderOperations](../../../app/src/main/java/com/muyuchat/mca/ChatLocalApiProviderOperations.kt) |
| 本地执行 | [McaInferenceService](../../../core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceService.kt)、[McaInferenceStreamPrefill](../../../core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceStreamPrefill.kt)、[McaInferenceStreamDecode](../../../core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceStreamDecode.kt) |
| 现有工具契约 | [ChatToolingTypes](../../../core/inference-contract/src/main/java/com/muyuchat/core/engine/ChatToolingTypes.kt)、[ChatRequestTypes](../../../core/inference-contract/src/main/java/com/muyuchat/core/engine/ChatRequestTypes.kt) |
| llama模板 | [llm_prompt_formatter.cpp](../../../core/native/src/main/cpp/mca/llm/llm_prompt_formatter.cpp) |
| 图像资源交接 | [RuntimeReleaseOperations](../../../app/src/main/java/com/muyuchat/mca/RuntimeReleaseOperations.kt)、[ImageGenerationOwnerExecution](../../../app/src/main/java/com/muyuchat/mca/ImageGenerationOwnerExecution.kt) |
| 云凭据 | [CloudChatProvider](../../../app/src/main/java/com/muyuchat/mca/CloudChatProvider.kt)、[CredentialStoragePolicy](../../../app/src/main/java/com/muyuchat/mca/CredentialStoragePolicy.kt) |
| Room | [ChatSessionStoreRoomDatabase](../../../app/src/main/java/com/muyuchat/mca/ChatSessionStoreRoomDatabase.kt)、[27.json](../../../app/schemas/com.muyuchat.mca.McaRoomDatabase/27.json) |
| 迁移测试样板 | [ChatHistoryMigrationTest](../../../app/src/androidTest/java/com/muyuchat/mca/ChatHistoryMigrationTest.kt) |
| 主题 | [McaTheme](../../../core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaTheme.kt) |
| 工程门禁 | [check_architecture_rules](../../../tools/architecture/check_architecture_rules.py)、[verify-code](../../../scripts/verify-code.ps1)、[build](../../../scripts/build.ps1) |

外部参考的固定commit、审查文件与限制见第15.1节；后续实际复用仍需核验许可证与依赖。本轮只读查询公开资料，没有克隆、构建或执行外部项目。
