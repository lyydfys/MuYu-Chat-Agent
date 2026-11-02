# MCA Agent V1：可交接执行包

版本：V1.0，与[主架构合同](MCA_AGENT_V1_ARCHITECTURE.md)共同生效。本文是未来实施任务，不是已经实施的记录。

## 1. 接手者必须先做的事

1. 阅读当前适用本地指令和 MCA_PROJECT_HARD_LOCK，记录实际 HEAD、工作区与每个子模块 diff。
2. 读主架构合同、本文、[验收矩阵](MCA_AGENT_V1_ACCEPTANCE_MATRIX.md)，锁定本次单张任务卡。
3. 对涉及的 owner 读取入口、一个失败分支、取消与释放、相关测试；不得只读本文。
4. 列出本次保留行为、文件写集、状态 writer、真实调用者、测试与回退点。
5. 本次只实现当前卡，不跨卡顺带重写其他领域；满足前置 gate 后自主继续。

用户当前授权只有方案。未来实施任务必须以届时用户明确授权为准，不能从本执行包推导发布、推送、外部消息或设备数据破坏授权。

## 2. 默认执行规则

- 当前事实以实施时源码为准。若只是行号/文件移动，更新证据定位继续；若 owner、数据格式或行为合同发生变化，先更新差异决策，不擅自迁就旧方案。
- 现有 feature:agent/AppTab.AGENT 是智能调参，不转用途。新任务状态不进入 MainUiState，不扩 MainCoordinatorHost。
- 同一个生产 engine/facade/supervisor 由 app 注入。禁止 feature/Agent service 直接 new engine、runner、native loader 或第二 request supervisor。
- Agent 任务 owner 独立；模型子请求复用现有执行 owner。停止只针对 exact request/attempt，不调无条件 global stop。
- 根构建、数据库版本、公共契约和 composition 由一个整合者统一修改。并行工作按独立文件写集划分，构建统一串行。
- 每张卡检查新依赖方向、生产调用者、状态写权限和旧功能合同；不是最后一张卡才补架构边界。
- 缺设备/资产只能把具体真机项标 BLOCKED/NOT_RUN。不能用 fake、编译或日志“无失败”代替通过，也不因此阻断独立纯逻辑/文档工作。

## 3. 任务依赖图

~~~text
K00 当前事实与ADR草案
  -> K01 模块/公共契约/基础门禁
     -> K02 严格协议/schema/fixture
        -> K03 确定性Agent Loop/内存Journal
           -> K04 真实模型Spike/资源取消 -> G0技术放行

G0 -> K05 Room事务与迁移
G0 -> K06 文件/产物提交（依赖K05）
G0 -> K07 权限/egress（复用K01/K02，接K05）
K05+K06+K07+K04 -> K08 生产工具/owner接线
K01 -> K09 导航和独立Route（完整运行依赖K08）
K08+K09 -> K10 页面全状态
K08+K10 -> K11 FGS/中断恢复 -> G1原生闭环

G1 -> K12 Skills
G1 -> K13 MCPHTTP
K06+K10+K11 -> K14 导入导出/删除/配额
K12+K13+K14 -> K15 门禁/故障注入收口
K15 -> K16 端到端/回归/视觉性能 -> G2 V1候选
G2 -> K17 最终审计/交接/回退说明
~~~

K05 与 K07 可以按先冻结的 DAO/permission port 并行准备；共享 schema/entity 只由 K05 负责人写。K09 可先做编译接线，但不允许交付一个装饰性空页并宣称 Agent 可用。

图中G0是生产持久化/工具接线的硬前置。G0缺设备时允许只读schema分析、脱敏fixture准备和未接生产的测试设计；不修改真实数据库版本、迁移注册或开放工具。K01–K03的契约/内存实现允许先于G0存在，具体生产消费者分别在K04/K08接线；到该卡必须删除未使用的占位接口。

## 4. 单卡产出标准

每卡必须交付：改动文件、真实生产调用者、owner/状态变化、测试数量与结果、NOT_RUN/BLOCKED、临时路径退出条件、回退方式。只有所有适用硬条件通过才写 DONE。

验收ID可以在多卡逐步积累证据，但不能提前把整项PASS。每条证据写caseId+subcaseId+evidenceKind；例如P14.protocol在K04、P14.ui在K10，T12.memory在K03、T12.restart在K05。下面“某部分”必须列出具体subcase和未完成部分；全部必需subcase齐备才可对父ID给PASS。完整验收归属与门槛见矩阵第1.1节。

“编译通过”“页面能打开”“fake模型有结果”只证明相应范围。当前卡没有计划改变 native 时，禁止顺带更新子模块、SDK、模型资产或 targetSdk。

## K00：锁定源码事实和设计决策

**前置**：实施授权、主架构合同已读。  
**目标**：消除资源/模型/数据库/依赖上的事实歧义。

读取范围：

- McaAppContainer、MainRuntimeAssembly、ChatExecutionFacade/Owner、ChatLocalApiProviderOperations。
- McaInferenceService、StreamPrefill/Decode、PrefixCache、LocalChatWorkerService。
- RuntimeReleaseOperations、ImageGenerationOwnerExecution、LocalImageGenerationCoordinator、前台lease。
- CloudChatProvider/CloudApiStore、Responses协议/decoder、Room数据库/迁移/backup。
- 导航动作链、现有feature:agent、架构/构建/测试脚本。

产出文件计划为 docs/proposals/agent-v1/evidence/<revision>/owner-map.md、capability-matrix.md、risk-register.md，以及 docs/adr 下“下一个未占用编号”的 Agent 资源/协议 ADR。不得假定 ADR0002 永久空闲，不能覆盖 ADR0001。

ADR 至少冻结：模型单次调用接入点、exact stop、模型/session隔离、进程作用域、并发矩阵、P0工具协议、数据库 N、JSON依赖、FGS用途、备份/导出策略、V1范围。依赖核对候选 Jackson Core 2.22.4 的实际兼容/安全，不自动全仓升级。

**验证**：A01、O01、P01、D01；只有源码证据可 PASS，运行项 NOT_RUN。  
**禁止**：创建执行器/页面、伪造 Spike 报告、修改硬锁降低门槛。  
**退出**：owner图每类请求有准入/租约/取消/终态/进程；没有“由某处处理”的未定位事实。  
**回退**：纯文档，不影响生产。

## K01：新增模块、冻结公共契约和基础门禁

**依赖**：K00。  
**写集**：settings.gradle.kts、版本catalog、新三个模块build文件；agent-contract源码；app受控依赖；架构/验证脚本必要映射。

三个模块名固定：core:agent-contract、core:agent-runtime、feature:agent-workspace。契约/runtime源码平台无关；feature只依赖contract/designsystem，不依赖runtime/engine/native/app/chat。

契约文件按职责划分：AgentTaskTypes、AgentRunTypes、AgentInvocationTypes、AgentEvents、AgentErrors、AgentModelPort、AgentToolPort、AgentJournal、AgentWorkspacePorts。每个文件遵守预算，不生成无调用者的大量空 port。命令带 commandId/适用task或run的expectedRevision，创建无旧revision；幂等查找先于revision校验。事件带sequence，JSON用String。加入questionId/AnswerAgentQuestion、统一STOPPING、SUPERSEDED及独立task.lifecycle；无同义取消状态。

观察查询使用Flow，显式coroutines-core 1.9.0依赖。模型port的生成是显式suspend操作，观察页面不会启动生成。

建议接口：

~~~kotlin
interface AgentModelPort {
    suspend fun infer(
        request: AgentModelRequest,
        progress: AgentModelProgressSink
    ): AgentModelResult
    suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch
}

interface AgentJournal {
    suspend fun readRun(runId: String): AgentRunSnapshot?
    suspend fun commit(command: AgentTransition): AgentCommitResult
}

interface AgentToolExecutor {
    val descriptor: AgentToolDescriptor
    suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult
}
~~~

AgentTransition为封闭的、可验证的领域命令，不暴露任意SQL/reducer lambda；AgentJournal的commit负责具体事务。model adapter内部保留现有ChatTool类型转换，不让底层服务类型泄漏UI。

**验证**：A02/A03、A04.constructFixture、A07.taskDiscovery；契约状态/版本用例只验证DTO与错误，不把尚无Loop的T01标PASS。A05/A06生产接线后验，A07实际测试执行覆盖由K15完成；报告0tests不是PASS。  
**退出**：依赖图无环、平台import禁令生效，接口/version/状态用例获复核。  
**回退**：新模块和接线可独立撤销，尚无生产数据。

## K02：实现严格协议和schema校验

**依赖**：K01。  
**写集**：agent-runtime/protocol、validation及fixture；仅必要catalog依赖。

实现PromptedV1Decoder、StrictAgentJsonCodec、SupportedToolSchemaValidator。严格parser置于内部，使用维护中的token reader，不自写JSON语法解析器；拒绝重复key/尾随root/越界/非法编码。定义final/tool/ask_user全部字段集合。

只接visible assistant最终完整输出；Error/取消/截断/partial永不执行。支持根schema子集，unsupported schema登记失败。一次run最多一次protocol repair。

fixture至少包括正常read→result→final、中文/Unicode、空字符串、合法转义、深度/字节限制、重复字段、extra字段、未知工具、reasoning里的工具JSON、文件内容里的JSON、双root、markdown、截断和多calls。

实现主合同6.5.1的AgentPromptedTranscriptSerializer，冻结显式SYSTEM、USER观察JSON、ASSISTANT envelope及消息ID/字段映射；golden覆盖成功、拒绝、NOT_EXECUTED、ask_user回答。tools/toolExchanges不充当本地序列化替代；四工具参数严格按主合同9.1生成schema和默认值。

**验证**：P02–P09/P12/P13的decoder/schema部分、P15.codec；P10/P11完整配对在K03，权限S02–S04在K07。未来建议测试名AgentPromptedProtocolTest、AgentToolSchemaValidationTest、AgentPromptedTranscriptTest。  
**退出**：所有无效输入产生稳定typed error，调用executor次数为0；输出只从合法assistant完成帧产生。  
**回退**：没有生产hook，可撤模块内部代码。

## K03：确定性Agent Loop、预算和状态机

**依赖**：K02。  
**写集**：agent-runtime/run、context、budget；测试内存Journal/fake ports。

实现AgentRunOwner与步骤协调，采用单run串行命令处理；I/O/模型在有结构化生命周期的子协程执行，返回带epoch/attempt身份的事件。状态转移表与主架构逐项一致。

单活动run/最多5排队；只读observe可多collector，不重启任务。冻结model binding、工具descriptor和policyVersion。预算/修复计数、tool pairing、拒绝后的有界处理、等待用户和明确终态均实现。

context assembler保留system/用户目标/真实toolcall-result配对；不拼聊天history、assistant identity或其他task结果。超context显式暂停，无静默压缩。

**验证**：T01.loop、T02.memory、T03.observe、T04–T11纯runtime部分、T12.memory、T13.state、P10/P11配对fixture、P15.codec、O02–O04的fake边界。跨重启、真实native、真实UI不能从本卡得PASS；建议AgentRunStateMachineTest、AgentCommandIdempotencyTest、AgentBudgetPolicyTest。  
**退出**：100轮确定性并发/取消fixture无重复终态/副作用；没有GlobalScope、事务内I/O或run间污染。  
**回退**：尚未接生产UI，核心可独立修正。

## K04：真实MCA本地/云模型Spike和G0放行

**依赖**：K03，真实模型与代表ARM64设备条件。  
**写集**：app/agent/inference、instrumentation/headless测试及必要composition；不改native工具模板。若公开退出证据不足，仅在现有engine/worker owner增加按请求读取/等待静止证据的窄表面；云strict terminal策略可修改既有OpenAiCompatibleChatProvider/协议helper，旧调用者默认行为必须等值且有回归测试。

从MainRuntimeAssembly注入同一engine和chatExecutionFacadeFor，禁止另建。严格执行主合同5.2.1：WAITING_RESOURCE→acquireExclusiveLifecycleLease(false)有界等待→lease下核对committed profile/epoch→context透传lease→成功/stop证明→仅释放自己lease。记录snapshot取得的实际identity；每attempt唯一requestId不复用。禁止默认true抢占、等待超时泄露lease或提交未结调参候选。

本地PROMPTED schema/exchange通过专属Agent transcript文本序列化，不能指望本地ChatRequest.tools自动生效。adapter只收Chunk.text，忽略reasoning控制动作；成功Done后解码。

云adapter复用CloudApiStore与provider，Responses只有显式supportsTools=true才NATIVE；否则显式prompted或NONE。测试使用脱敏公共fixture；模型凭据通过已有受控配置提供，绝不写fixture/命令/报告。

云attempt按主合同5.2.2归AgentRunOwner子Job/Journal，策略OkHttpClient注入既有provider；精确cancel自己的Call，不cancelAll，也不伪称云端经过本地supervisor。协议请求成功Done与断连/partial分别验证。

现有OpenAI-compatible/Anthropic的EOF后Done不足以授权工具。G0先用Responses真实terminal路径；实现Agent显式STRICT_AGENT_TERMINAL及各协议fixture后，其他协议才能执行工具，EOF/length/max_tokens不得成功。测试专用client只允许固定fixture origin；完整生产egress在K07。旧聊天默认策略不变，不复制整套provider。

实现应用作用域AgentModelLeaseRetention：lease到手先登记引用，attempt取消仍存活；只在同engine/worker owner证明该请求静止后恰好release。timeout保持STOPPING，后续exact worker退出能解除fence；不靠stop返回true或token清空当证明。pending查询点名app ModelRuntimeProfileStore.pendingTransaction(identityHash)；Agent epoch/supervisor runtimeEpoch/native token epoch不混用。

实现scoped stop：取消Agent子协程并对当前exact request/stop token发停；永不调用全局stopGeneration。记录UI反馈/命令送达/真实停止/lease释放四个时间戳。

G0 Spike使用测试专属只读fixture registry和内存Journal：输入brief→list→read→search→read命中片段→final，5轮响应/3类工具；只读executor确实读取测试目录，越界拒绝，不开放生产工具或公共debug入口。MNN、GGUF各固定一个可运行模型，云路径单独。K05–K07尚未实现，因此G0不要求生产写入/审批/迁移/FGS/MCP恢复，不能用测试内存实现给这些项目打PASS。

**验证**：O01–O06/O08的模型部分、O13、P01/P14.protocol/P15.runner、Q01的G0只读组、Q02.spike/Q03.spike、R02的受影响owner/API回归；源码/确定性/真机证据分别标注。headless ack不冒充UI反馈，Q02/Q03父项仍未完成。P14.ui在K10；O07/O09/O11/O12/Q04留G1，O10留K13，未到阶段标NOT_RUN。  
**G0通过**：两本地路径有真实闭环/scoped cancel/session隔离证据；已测试云路径明确；并发/错误没有越权和旧回调污染。  
**BLOCKED处理**：缺设备/模型记录具体项，不能用云替代本地；只允许前述只读/fixture准备，不修改生产schema或开放工具，不把本地能力标可发布。  
**回退**：Spike不加外部debug端点；临时hook删除或转为正式instrumentation，记录生产adapter是否保留。

## K05：Room事务、schema和迁移

**依赖**：K01/K03，G0=PASS；缺失G0只作只读/fixture准备。  
**写集**：app/agent/data、现有Room数据库entities/DAO/迁移注册、schema与androidTest；不改旧业务entity字段。

先读取实际@Database N；按N→N+1加Agent表、索引/外键。本次基线若未变化则27→28。实体、映射、DAO按职责分文件；不把所有Agent CRUD塞进原ChatSessionStore。

AgentJournal实现原子更新run revision/状态、invocation/approval、append event/sequence。unique约束落实commandId/sequence/idempotencyKey；事务失败没有半个新状态。

落表包含model attempts、command结果幂等日志、grants、artifact commit账本及大字段payload引用；不能只建任务/事件表而把重启所需事实留在内存。commandId同内容重试必须跨重启返回原结果，不受旧expectedRevision阻断；imported/archive与run执行状态分开。

测试从真实旧schema创建旧聊天/附件/助手等fixture，迁移后逐项检查原数据与schema；升级失败不fallbackToDestructiveMigration。

**验证**：D01–D05、D20.schemaAndCommands、T02.room/T04.room/T12.restart；payload真实文件和恢复完整断言在K06/K11。建议AgentRoomMigrationTest、AgentJournalTransactionTest。  
**退出**：迁移schema来自KSP真实导出，不手工伪造；查询分页/索引行为通过。  
**回退**：schema升级后前向修复；不能指导用户装旧APK清库。

## K06：私有workspace、产物提交与崩溃核账

**依赖**：K05/K02。  
**写集**：app/agent/data/ArtifactStore、WorkspaceStore、commit journal；文件系统适配测试。backup_rules.xml和data_extraction_rules.xml由整合者在本卡加入Agent文件根排除，覆盖cloud backup/device transfer。

实现输入复制快照、逻辑path/ArtifactId映射、SHA256、staging→不可变版本rename→数据库确认/逻辑最新映射。覆盖写先核对expectedOldHash，旧物理版本保留引用；fsync/掉电局限按主合同10.3记录。产物和原文件都不能因取消而被无条件删除。

注入staging写前/中/后、rename后、DBcommit前/后故障。恢复读取commitId/hash，确定已提交或UNKNOWN；不重复执行tool。

文件工具只写私有目录，SAF外部provider只用于导入/显式导出。symlink/../绝对路径/路径前缀碰撞均拒绝。

**验证**：D06–D09/D12文件提交故障、D20.payloadRefs、S05/S06路径与覆盖；D10/D11完整run/审批恢复由K11。建议AgentArtifactCommitRecoveryTest、AgentWorkspacePathTest。  
**退出**：所有崩溃点产物事实可核对，没有“DB成功但空文件”或跨task删除。  
**回退**：保留可恢复staging；迁移不删除用户原文件。

## K07：PermissionBroker、审批与egress

**依赖**：K02/K05。  
**写集**：app/agent/security与纯策略测试；feature只消费presentation DTO。

实现FrozenToolCommand：defaults/路径解析在审批前完成；hash包含精确arguments bytes、descriptor/schema、resource precondition、policy/run epoch。5分钟TTL、once消费、policy变更失效。

ALLOW_FOR_TASK只能收窄到已授权资料/根目录/目标；写/外部副作用仍ASK。审批拒绝生成DENIED结果，重复相同proposal有限处理。

实现LOCAL_ONLY/NETWORK_ENABLED运行策略和provider/data-category egress；新输入/新origin扩大范围需新授权。在途网络撤销只取消Agent请求，不能声称撤回已发数据。

egress来源清单从最终payload投影，包含Skill/工具结果/摘要/检索内容，不只展示初始附件。Agent专属client默认HTTPS/禁自动redirect、校验并绑定实际DNS路由，LOCAL_SERVER单独授权；代理/自定义DNS未审则不接入Agent，保持旧聊天合同。

**验证**：S01–S06/S09–S14的broker/transport策略部分、T06.policy；真实executor在K08、通知S15在K11、导出S16在K14、Skill/MCP专属S07/S08/S17在K12/K13，不提前PASS。建议AgentApprovalBindingTest、AgentOfflinePolicyTest、AgentEgressScopeTest。  
**退出**：参数/schema/resource/epoch/TTL任一变更均拒绝旧批准；executor未执行；密钥不出日志/Room。  
**回退**：关闭新run启动保留记录；撤销grants，不改现有MCA云配置。

## K08：生产工具与owner组合接线

**依赖**：K04/K05/K06/K07。  
**写集**：app/agent/tools、agent/composition，McaAppContainer必要窄装配。

注册workspace.list/read_text/search_text、artifact.write_text四个工具。ask_user使用协议控制路径，不另外注册同功能工具。所有工具统一经过schema→frozen command→broker→executor→journal。

生产AgentRunOwner使用RoomJournal/真实model adapter/工具registry，应用scope持有。registry是不可变snapshot；运行中更新extension不修改当前run工具集。

真实生产model adapter和permission不能被缺省fake替换；注入遗漏编译失败，不默默new真实默认依赖。

**验证**：T01–T13、D06/D20、S01、A05/A06、O13；完整headless本地/云各一条。  
**退出**：单owner构造点、查询只读、task与chat状态隔离、没有无用层或旧双实现。  
**回退**：停止新run，待当前执行准确结算后禁用workspace入口；数据保留。

## K09：独立导航、侧栏入口和Route

**依赖**：K01，生产运行依赖K08。  
**共享写集由整合者负责**：

~~~text
MainViewModel.kt：仅AppTab新增AGENT_WORKSPACE
MainChatScreenActions.kt：onOpenAgentWorkspace绑定现有host.onTab
feature/chat/ChatScreenActions.kt：ChatShellActions具名回调
feature/chat/ChatViewModel.kt：必要单个导航转发
feature/chat/ChatScreen.kt：drawer callback接线
feature/chat/ChatHistoryDrawerScreens.kt：图片下方新增AppFeatureButton
MainActivity.kt：同级AgentWorkspaceRoute装配
feature/agent-workspace：Route/VM factory
~~~

现有onOpenAgent仍指智能调参。复用已有tab字段；不添加Agent业务到MainUiState/Host。drawer点击先按现有模式关闭抽屉，再导航，不能叠加出两个可交互页面。

返回：先关闭弹层，再详情回列表，再工作区返回来处；默认从drawer回CHAT。通知进入详情时由Route保存原目的地；进程恢复无记录时回CHAT。返回/旋转不启动/取消任务。

**验证**：U01–U05、A05；建议AgentWorkspaceNavigationTest与既有调参入口回归。  
**退出**：图片下Agent顺序正确、旧AGENT入口行为不变、没有第二业务writer。  
**回退**：撤新入口/route接线，不改变旧枚举值映射/数据。

## K10：原生页面和全状态交互

**依赖**：K08/K09。  
**写集**：feature:agent-workspace内部，除明确共享等值提取不写chat内部。

文件按语义分：首页/任务列表、详情时间线、输入/附件、审批、产物、模型选择、状态投影。Route≤150目标，VM只做projection/intent，不执行模型/工具/Room事务。

任务列表每页50、事件初始100按sequence加载，查询limit≤200；不把所有输出放进一个巨大StateFlow。草稿保存与run state分离，observe多次不触发start。

执行时间线默认紧凑工具行，可展开参数/错误/产物。无虚构运行记录、无原始reasoning面板。长日志按需查看。任务和产物实际存在才可点击。

审批显示真实目标和覆盖diff；键盘打开后继续/批准/停止仍可触达；用户滚离底部不自动拉回。沿用McaTheme/图标/圆角，不改现有页面审美。

**验证**：U06–U14页面部分、P14.ui、Q05；U12中断渲染与真实进程恢复分开，后者在K11。两轮渲染，浅深色/紧凑大屏/IME/大字体/TalkBack。  
**退出**：空/忙/审批/失败/取消/恢复均有可操作结果；无Preview代替真机。  
**回退**：UI可独立回退，owner/persistence不受影响。

## K11：前台保活、准确停止和中断恢复

**依赖**：K08/K10/K06。  
**写集**：agent/platform、既有FGS/lease的必要typed扩展、manifest；恢复查询/通知。

优先扩展现有生成FGS lease让Agent用途可识别；确需新service时它只连接同一owner，不创建runtime。核对targetSdk36 specialUse/subtype与平台限制。等待用户/审批不持native lease。

通知带runId/epoch和generic text，stop action只取消它自己的run。过期通知不能停止新任务；不显示prompt/文件/敏感输出。

启动恢复扫描非终态run，先核账artifact/invocation；旧审批过期；RUNNING外部副作用UNKNOWN；显示继续/检查，绝不自动发网或重启推理。

**验证**：O05–O09/O11/O12、D09–D12/D20、Q01-V1（MNN/GGUF各10次，云单列）、Q02.production/Q03.production（按矩阵1.2，每本地后端至少10次，补真实UI/Room/FGS的时间和资源证据）、Q04、U12、S15及G1矩阵已具备项；至少三次真实进程中断恢复；MCP的O10在K13。Q02/Q03最终候选包的完整验收仍由K16负责，不提前给父项PASS。  
**G1通过**：独立页面可完成文件任务、拒绝、停止、后台与恢复全闭环；旧页面与API无回归。  
**回退**：停止接受新run并准确取消/核账；service卸载前清理自己lease，不能清他人注册。

## K12：Skills安装与执行约束

**依赖**：G1/K02/K07。  
**写集**：app/agent/extensions/skills、feature的扩展管理、受限格式fixtures。

实现目录/ZIP导入、SKILL.md解析子集、manifest/hash、来源/版本/启停、不可变run snapshot。unsupported元信息明确提示；scripts只预览不执行。

allowed-tools仅作为收窄条件，运行时broker强制交集；安装不会自动授予路径/网络。目录/SAF树与ZIP同样检查文件数/总量/深度/重复路径/循环document ID，复制普通文件快照。更新不换当前版本；停用/卸载是明确撤销，使待批准失效、禁止下一次调用并安全处理在途动作。

**验证**：X01–X05、S07/S17；至少一个纯文本工作流Skill完成真实文件任务，另一个依赖bash的Skill显示限制。  
**退出**：兼容声明精确到支持字段和脚本限制。  
**回退**：停用扩展保留任务snapshot，不修改历史。

## K13：MCP Streamable HTTP客户端

**依赖**：G1/K02/K07。  
**写集**：app/agent/extensions/mcp、MCP配置/工具UI、transport与fixture。

逐条实现主合同12.2 tools-only线协议：initialize→initialized、HTTP headers、JSON/SSE、session/404、tools-list分页上限、request ID、cancel、未知server request拒绝与disconnect；server namespace/schema snapshot持久化。notifications/tools/list_changed/schema漂移使待审批失效。重连只恢复连接与发现，不自动重发副作用调用；OAuth/stdio/legacy/独立GET订阅未做就明确不支持。

网络范围/redirect/secret转发受egress控制。server hint不能降低副作用级别。响应/超时有界，不支持schema则单tool禁用有解释。

停用/删除server立即撤本机连接、credential reference及grants，安全取消在途调用并fence旧结果；远端不提供revoke时不得称远端token已失效。

测试server必须受控且不带真实凭据fixture；测试脚本不是生产公开端口。配置重启后保留但默认断开。

**验证**：X06–X14、S08/S11/S14、O10；需要至少一个真实受控server端到端，fixture不替代transport。  
**退出**：报告exact协议/transport/schema限制，stdio/legacy未做就写不支持。  
**回退**：断开server、撤Agent自己的grants/tokens，保留task历史。

## K14：导入导出、任务删除与配额

**依赖**：K06/K10/K11。  
**写集**：agent/data/export、quota/gc、SAF platform、相关feature intent；复核K06已修改的backup_rules.xml/data_extraction_rules.xml，必要差量修正仍由整合者负责。

版本化ZIP manifest/hash，选中摘要/产物导出；排除凭据/grants/SECRET/opaque状态。CreateDocument直接写用户选择URI，不扩大现有FileProvider全目录。

导入先staging校验路径/hash/zip大小/entry限制，生成新ID和task.lifecycle=IMPORTED_ARCHIVED，不恢复授权/运行。删除active task先准确取消，TOMBSTONED后文件重试清理。软配额警告，硬磁盘不足不得吞数据。

**验证**：D13–D19、S05/S07/S16；导出→导入前后内容hash与脱敏范围。  
**退出**：备份规则同时覆盖Agent文件根和数据库；不改旧聊天导出格式。  
**回退**：保留version reader，已有导出可读；不以删task掩盖失败。

## K15：架构、故障注入与验证脚本收口

**依赖**：K12/K13/K14。  
**写集**：tools/architecture、scripts/verify-code、受影响CI/docs、测试fixtures。

新增模块依赖/import/cycle、无类型注入、owner构造点、feature直连runtime、新模块LOC审查。规则有正/反fixture，不能只写文本搜索“没有new engine”假装全仓所有权证明。

verify-code显式AgentTests跑contract/runtime/feature/app测试并聚合各模块XML。-Changed识别新增/untracked或明确要求补任务；不能report空测试PASS。不改变现有FullTests/FeatureTests的既有覆盖承诺。

整理至少24条注入语料、状态竞争/approval/commit fault cases和schema fixtures。删除临时hook、重复实现和无生产调用者接口，记录确需保留adapter的退出条件。

**验证**：A01–A08、P02–P13/P15、T01–T13、S01–S17、D01–D20、X01–X14。  
**退出**：所有适用确定性硬不变量100%通过，没有suppression扩大或测试范围缩水。  
**回退**：门禁变化不降低现有规则；单独回退新规则也不能用来宣告实现完成。

## K16：V1端到端、既有功能回归、视觉和性能

**依赖**：K15。  
**写集**：测试/证据、仅针对已复现问题的最小修复；禁止混入全局重构。

按验收矩阵绑定revision+工作区diff+子模块diff+APK hash、设备/模型fingerprint/配置。JDK21，使用受控build/test脚本与构建锁；没有SDK/设备如实BLOCKED。

真实任务覆盖本地MNN、GGUF、云NATIVE/明确prompted、Skills、MCP、离线、取消、后台、进程中断、升级、旧Chat/Image/Tuning/LocalAPI。两个本地runtime的结果分开，不互相代替。

UI两轮render/critique，旧页面固定状态回归。测UI反馈、native停止、lease释放、TTFT、总内存/磁盘，不能靠缩context/精度/功能制造收益。

**验证**：验收矩阵全部适用项；Q02.finalCandidate/Q03.finalCandidate绑定最终源码/APK执行，再汇总早期子项形成父ID完整结果，不沿用不同APK的Spike结论。  
**G2通过**：硬不变量全绿，所有声称支持的生产路径有真实证据；质量/性能阈值明确且范围诚实。  
**回退**：真实问题修复仍按单边界小切片，不能批量刷golden/清数据。

## K17：最终审计与接手交付

**依赖**：G2。  
**交付**：实现文件/owner图/ADR/协议/schema兼容表、证据索引、NOT_RUN/BLOCKED、临时adapter、已知模型限制、回退/前向修复、用户简明使用说明。

整合者审查全部diff与实际生产接线；检查没有修改本地专用规则/未知子模块、凭据/私人资料/模型二进制未进入变更。无授权不执行push/merge/publish。

最终声明分层：V1功能完成、指定runtime/模型验证、Skills子集、MCPHTTP兼容、未实现的DSH/终端/Android控制。禁止笼统写“全功能S级/全面超越第三方”掩盖范围。

**退出**：每个PASS可追到原始证据，数据升级可解释，普通改动影响局部且有正式owner。  
**回退**：提供最近安全版本、DB不可随便降级说明、关闭Agent新run入口的事故步骤和数据保留方式。

## 5. 多Agent分工

| 角色 | 所有文件/职责 | 不能并行抢写 |
| --- | --- | --- |
| 整合者 | contract、root build/catalog、Room版本/迁移注册、app composition、导航旧链 | 这些公共写集只能一个人 |
| 核心执行者 | protocol/state/budget/context及纯逻辑测试 | 不改app/native/Roomschema |
| 数据执行者 | DAO/repository/artifact/迁移fixture | 不自行改数据库版本号或旧表 |
| UI执行者 | feature:agent-workspace内部与渲染 | 不加MainUiState/Host/engine依赖 |
| 扩展执行者 | Skills/MCP adapters及受控fixture | 不更改permission策略 |

角色可由同一个Agent串行承担。实际并行最多按独立写集，不为填满槽位开重复探索/编译。契约改动先整合再通知下游，不能让下游猜接口。

K05文件级划分：数据执行者独占新Agent entity/DAO/repository及迁移fixture；整合者独占原McaRoomDatabase声明、迁移注册、版本号、schema接入和backup XML。导出schema由同一次受控构建产生，不能多人手改。K09的旧导航链及Route/VM factory由整合者独占；UI执行者从K10开始只改Screen/组件/状态投影，未经移交不修改该factory/Route。扩展执行者不改K07策略，提出新权限需求先交整合者冻结。

## 6. 每张卡的提交记录模板

~~~text
任务卡ID：
实际起点HEAD/分支/已有工作区与子模块差异：
本卡范围与保留行为：
改动文件及唯一职责：
生产调用者/owner/状态writer/资源lease：
数据/协议/默认值/视觉影响：
检查命令、匹配测试数、失败/跳过数：
证据revision/APK/设备/模型绑定：
PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE逐项：
临时adapter及退出条件：
回退/前向修复：
下一张可执行卡：
~~~

## 7. 可直接复制给实施Agent的启动指令

> 在 MCA 当前 checkout 实施指定任务卡 Kxx。首先读取适用本地指令、MCA_PROJECT_HARD_LOCK，以及本目录架构合同、执行包、验收矩阵。先核对当前源码/测试、HEAD、工作区与子模块差异；只认当前事实。严格保留聊天、生图、模型、调参、Local API 的功能、数据与既有UI合同。新增Agent页面在图片入口下方，保留旧AGENT智能调参。Agent任务有独立owner/Room表/页面状态；模型子请求复用同一生产facade/engine和exact身份取消。不得扩MainUiState/万能Host、不得Any注入或新native runtime。按本卡写集小步实施，遇到普通实现细节按已冻结决策自主处理；契约/owner/数据事实冲突先形成差异说明，不能擅自放宽规则。执行适用验收ID并提交真实结果；缺证据标NOT_RUN/BLOCKED。不要发布或推送。只完成当前卡及其明确授权的依赖，不越界堆未来功能。
