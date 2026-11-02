# 实施任务卡与可复制提示词

状态：未来实施用任务书。本轮交付仅为文档；下列命令和提示词没有在本轮执行。

先读 [架构方案](ARCHITECTURE.md) 和 [问题/验收表](PROBLEMS_AND_ACCEPTANCE.md)。本文件不授权发布、推送、数据清理、重装覆盖或修改原有冻结视觉。接手者还需遵守当次用户实际授权。

## 1. 统一执行规则

1. 开始前完整读`docs/architecture/MCA_PROJECT_HARD_LOCK.md`和适用本地指令；检查HEAD、tracked/untracked改动、子模块revision和内部diff，保存本卡基线。
2. 只认当前源码。文档中“已存在”若与当前不同，记录具体差异并沿真实owner调整；普通实现细节自主解决，不重新询问用户已授权事项。
3. 每卡先写范围、文件写集、唯一writer、实际执行owner、验证命令和回退点。禁止跨卡顺便改UI/升级依赖/迁移schema/重写native。
4. 没有上游卡证据不假装依赖已通过。可完成不依赖的fixture/纯逻辑准备；受缺失条件影响的执行保持NOT_RUN/BLOCKED。
5. 同一真实资源继续复用原engine/facade/worker/lease。不新建native runner、平行仲裁锁、全局AgentManager或第二套模型/工具循环。
6. 不给MainUiState加Agent业务字段，不扩大MainCoordinatorHost，不新增Any注入。feature互不依赖，不取得根状态任意写权限。
7. 默认聊天/图片/智能调参/模型/API行为、数据和视觉冻结。修复明确风险以独立小切片提交审查，不把整个原链路重写当作先决条件。
8. 明确自己不是唯一参与者；不得回滚其他人改动。公共契约、Room注册/版本、主装配、导航、根build仅整合者写；其他人提出差异，由整合者串行合入。
9. 构建统一使用项目锁。不要全机杀Gradle/Kotlin进程，不clean或删缓存碰运气；使用wrapper前先读当前脚本和参数。
10. PASS只代表实际执行且范围吻合的检查；记录测试匹配数、失败、错误、跳过。fake/stub/编译不替代真机native/迁移/升级/视觉。
11. 禁止把生产密钥、真实用户内容、私有日志、模型权重或APK写入分发文档。只纳入脱敏fixture和必要hash。
12. 未请求则不commit/push/merge/publish。必要常规可逆修改持续推进；超出授权的真实行为先完成可审查方案并说明影响。

## 2. 分工和依赖图

```text
C00 基线/证据/并行约定
 ├─ C01 原owner停止证明与settlement
 ├─ C02 旧聊天精确取消与搜索取消
 └─ C03 外发/扩展快照/云生产路由
      ↓ 适用安全实证 + 原G0
C04 唯一session kernel等值提取
 ├─ C05 创建回执/输入来源/handoff事务
 └─ C06 chat-tools完整纵切（依赖C04及C03适用部分）
      ↓ C05 + C06
C07 显式移交/任务引用/结果回草稿
C08 有真实双调用者的共享UI等值提取
C09 真机/多入口/升级/恢复/视觉/性能
C10 最终证据与交接
C11 后续DSH能力设计（独立排序，不夹带首发实现）
```

推荐最多三个有独立写集的实施角色：内核、数据、UI；整合者负责公共面。C01/C02涉及现有执行owner，应由同一负责人整合，不能两人各自改stop协议。C03网络fixture与C05迁移fixture可提前只读/纯测试准备，不能以此跳过门禁。

UI前端精修可并行在冻结写集内继续。共享Markdown/审批展示提取期间，前端不要同时修改同一组件；先移交文件所有权。所有编译仍串行。其他checkout不会自动带上当前未提交Agent代码，建立工作树前要确认可复现基线。

## 3. C00：锁定基线与唯一状态入口

**依赖**：无。**写集**：本集成目录下`evidence/<snapshot>/`的状态、owner/能力矩阵及差异说明；既有V1状态记录由其负责人更新。默认不改生产源码。

**步骤**：

1. 获取HEAD/分支、tracked/untracked写集、三个子模块revision及patch；辨认前端与Agent已有改动，不清理。
2. 读取真实生产调用链及测试；将P01–P19逐项标“仍存在/已修复待验证/已有真实证据/不适用”，附具体位置。
3. 记录原G0–G3与集成门禁，保留历史JVM/APK结果的原时间和范围。当前manifest只写可证明结论。
4. 审查原K05时序偏离，记录风险、保留现有数据和补偿验证路径；不修改原规则给既成事实补PASS。
5. 固定文件所有权/构建锁；读verify脚本，模块wrapper使用`-KeepDaemons`避免其默认全机进程清理。

**最小验证**：I39–I41；文档链接和diff、状态与实际源码一致。**退出**：所有人知道哪个文件可写、哪个是权威状态、下一卡做什么。**回退**：仅撤本卡新增状态记录，不删历史证据或已有代码。

**复制提示词**：

> 执行MCA集成方案C00，只做基线与证据收口，不改生产代码。完整读硬锁及本方案三份文档；检查当前HEAD/dirty工作区/子模块patch；保留全部已有改动。按P01–P19复核实际状态，建立本集成evidence目录下唯一当前manifest，分别记录code/runtime/device/release和原G0–G3。不要拿旧XML当新运行、不要改原门禁、不要覆盖其他会话的状态文档。明确公共文件唯一整合者和串行build锁，避免全机杀构建进程。交付当前问题、下一安全切片、精确未验证项。

## 4. C01：原owner提供真实静止证明，完整传播STOPPING

**依赖**：C00。**负责人**：现有engine/worker与Agent model适配的同一整合者。**写集**：必要的原engine/worker owner窄接口；`app/.../agent/inference`的proof/retention/adapter；必要contract settlement与测试。禁止更换native算法/模板或新建engine。

**步骤**：

1. 列出MNN/GGUF从实际派发到native同步调用返回、worker回调/进程死亡的证明源；标明哪些只是UI/supervisor事实。
2. 在原owner内提供有类型的按精确请求identity读取/等待静止证据；包含worker incarnation/session generation，不以PID、null或token消失替代。
3. `Confirmed`只由真实证明构造；超时、读异常、缺失证据和陈旧identity返回Unknown/TimedOut。未dispatch路径保留安全直接释放。
4. adapter消费retention结果，回报settlement；owner收到未静止结果进入/保持STOPPING。应用scope持原lease引用和有界结算任务，取消attempt不丢资源账。
5. 不在生命周期mutex内等待网络、UI或不受控native查询。不无限NonCancellable，不通过全局stop/杀共享进程解锁。

**验证**：I01–I03；包括acquire取消边界、派发前/中取消、Flow完成native未退、重复/迟到proof、worker重启、证明读异常、停止超时后真正退出。先确定性测试，再真实MNN/GGUF各跑原矩阵要求；记录UI反馈、stop送达、真实静止、lease释放四个时间点。

**拒收**：仅将token==null改成更长delay；只用FixedStopProofSource；只显示STOPPING但实际已unlock；超时后终态允许新请求；引入第二native锁。

**回退**：关闭新Agent/chat-tools执行准入，等待准确结算；保留任务记录。原owner接口的回退不能让在途lease失主，不强制清资源。

**复制提示词**：

> 执行C01，目标修复P01/P02。你负责原engine/worker静止证明和Agent inference适配，不是唯一参与者，勿改他人代码。保留原engine/facade/lifecycle lease；仅补精确identity的静止证据，null/异常/旧worker绝不算Confirmed。将retention Blocked向owner传播并保留STOPPING和原lease，真实证明到达后幂等释放。添加能暴露提前unlock和错误proof的故障测试，再按可用条件做MNN/GGUF实测；无设备如实BLOCKED。不要把Flow终止、stop dispatch或supervisor terminal写成native停止。交付I01–I03结果与准确回退点。

## 5. C02：旧聊天精确取消与搜索取消兼容切片

**依赖**：C00；真实资源结算依赖C01。**写集**：聊天stop的typed窄port/adapter、原chat run identity登记处、搜索异常边界和相应测试。不要扩万能Host或改APIstop协议。

**步骤**：

1. 在聊天attempt创建时登记其真实request/attempt，取消前捕获原目标。不得用stop时active token代表目标。
2. UI stop只取消自己Job、waiter、搜索deferred/HTTP Call和精确facade请求；无已派发attempt不调用全局stop。保留原消息回滚与及时反馈。
3. 旧聊天requestId唯一性与identity核验一致；若依赖请求ID精确stop，证明永不复用，不能把过期ID映射到新请求。
4. 搜索重抛CancellationException；可恢复网络失败继续按原合同处理，不把用户取消当普通失败继续回答。

**验证**：I04–I06；聊天A迟到stop→AgentB不受影响；search取消→零后续模型请求；切模型/切会话/重复stop无误写；既有聊天cancel与Local API相关回归。

**拒收**：把所有stop统一替换成读当前engine token；删除原stop按钮或延迟用户反馈；把旧API响应改成新协议。

**回退**：可撤新chat-tools准入；安全精准stop修复单独回退需证明没有开放跨入口误停风险，不用恢复全局stop作为默认事故措施。

**复制提示词**：

> 执行C02，修复P03/P04，不重写聊天执行链。冻结原聊天attempt身份后做精确取消；禁止读取当前活动token去停止可能已属于Agent的新请求。保持用户立即停止反馈及现有历史settlement。搜索CancellationException必须传播，普通网络失败保留原语义。覆盖聊天停止迟到、Agent/API/图片继续、搜索取消不继续回答。不要扩大MainCoordinatorHost/MainUiState，不修改原Local API合同。提交I04–I06实测范围及未验证项。

## 6. C03：实际payload授权、扩展撤销和云路由

**依赖**：C00；原G0和本地执行受C01/C02约束。**写集**：`agent/security`、`extensions/mcp`、`extensions/skills`、run-scoped catalog、模型路由/云adapter、相关测试；公共装配只由整合者改。

**步骤**：

1. 为实际序列化的模型/MCP/search请求建立来源投影及hash绑定；在发送前校验transport/origin/source/class/policy，不允许只检查域名。
2. 区分发现grant和内容grant；启用server或NETWORK_ENABLED不授予发送所有PRIVATE资料。新增来源/hash/目标重新审批；LOCAL_ONLY/SECRET不得由模型文本降级。
3. `offlineForced`做统一零socket约束，覆盖本机HTTP连接；旧普通聊天网络行为保持。所有Call取消只影响所属execution。
4. catalog IO移出全局Mutex；登记generation→锁外发现→CAS发布。schema/list变化/禁用/删除联动实际owner/broker，拒绝旧批准和旧结果。
5. 每次run构建当前Skill版本快照；撤销事实同步持久化，调用前检查generation，不靠异步UI回调。更新不替换已有快照内容。
6. typed binding router连接local/cloud，复用现有凭据仓储。严格终态先覆盖已具备实现的协议；不满足terminal/有界读取/外发的provider继续未开放。不开启自动重试或跨provider兜底。

**验证**：I07–I13；受控server捕获实际发送内容、拒绝时0请求、schema变化审批、Skill管理→新run、断流与单Call取消；至少一条真实云和一条受控MCP，无法完成保持未开放。原G0还需真实本地两路径，不能云替代。

**拒收**：只让cloud picker显示；空sourceIds当通配符；listener只有定义无生产调用；model声称工具只读就免审批；撤销后仍能消费旧批准。

**回退**：关闭新云/MCP执行，精确关闭其session/call并撤自己的grant，保留历史。不同执行共用OkHttp client时不能cancelAll/全局close。

**复制提示词**：

> 执行C03，按P05–P08补生产安全闭环。你负责网络/扩展/模型适配，勿重写聊天或native。每次真实payload投影所有来源和hash，发送前通过共同策略与当前授权；发现server的同意不等于允许传PRIVATE参数。完全离线必须零socket。把MCP generation变化接到批准失效，网络不持全局Mutex；Skill快照每run刷新，撤销在调用前生效。云模型通过typed router复用原凭据且严格terminal，不以打开选项代替接线。测试I07–I13，分别报告unit/真实云/真实MCP/设备状态，不假装外部条件已满足。

## 7. C04：唯一session kernel等值提取

**依赖**：C01–C03适用安全缺口、原G0；缺设备可准备纯fixture但不开放生产新入口。**写集**：core agent contract/runtime、workspace adapter、最少model/permission主体适配和测试。Room历史格式由adapter保持，不在本卡升级schema。

**步骤**：

1. 建立typed ExecutionSubject、ExecutionIdentity、SessionSpec、profile、SessionOutcome和窄sink。workspace映射真实task/run；chat用真实chat turn，禁止伪造task/run落库。
2. 从原协调器提取唯一CONTROL循环。算法、repair、预算、transcript配对、失败语义等值，先让旧workspace通过同一kernel。
3. 将kernel直接AgentJournal/CAS依赖变为有回执event sink。workspace adapter负责原revision/AppendEvents；run终态仍由原AgentRunOwner写。失败sink不可默默成功。
4. AgentModelAttemptRef等强绑run字段通过typed session适配统一相关身份；workspace request namespace继续`agent:`，拟新增chat-tools namespace使用`chattool:`，全部永不复用，旧持久化记录不重写。
5. 提取共同frozen binding/权限判定，不复制broker策略；workspace与chat分别适配持久化。批准消费在唯一owner事务执行，消费前重新核验schema/source/policy，不能双重消费或绕过。
6. workspace=1 active/5 queued保持，chat工具轮使用现有聊天准入；实际native互斥仍是原lease。生产删除旧循环体，无长期双实现开关。
7. 对P19先加能暴露旧缺口的故障fixture，再单独完成单调时钟硬deadline及跨yield累计；不把行为修复混作无差别等值搬迁。模型/工具永不返回也应准确超时取消，仍等真实静止证明；失败/取消尝试记账，预算不随新execute重置。

**验证**：I14–I16、I19及既有runtime/workspace测试；同一协议/工具语料经两个主体adapter的规则等价，坏sink不执行工具，过期回调不写新session。

**拒收**：copy原类改名聊天版；新万能Manager；no-op成功journal；每个chat生成隐藏AgentTask；把全部聊天塞进workspace等待队列。

**回退**：本卡尚未开放chat调用时可回到旧workspace路径；数据格式不变。已接chat后先停新chat-tools，再准确结算，不能一边跑新一边删kernel。

**复制提示词**：

> 执行C04，按架构§5等值提取AgentSessionKernel。唯一CONTROL循环从AgentPromptedLoopCoordinator迁移，workspace adapter保留原Journal/CAS及AgentRunOwner唯一终态。输入和输出使用typed session主体/profile；chat不伪造task或journal。共用模型、schema、预算、权限和精确取消，存储adapter各归其主。保留旧持久化格式、原工作区行为和调度范围。先证明workspace等值，迁移完成清除旧生产循环，不扩大Host/store/composition职责。提交I14–I16/I19和真实生产调用者图。

## 8. C05：确定创建回执、交接输入和可恢复事务

**依赖**：C04契约稳定；迁移fixture可提前准备。**写集**：任务创建契约/仓储、`agent/handoff`、input来源/读取、迁移/DAO及测试；workspace创建调用者的回执消费由UI/整合者接线。

**步骤**：

1. 创建命令返回专用AgentCreateTaskResult和完整receipt；幂等记录持久化原receipt。删除UI同goal猜任务路径；更新所有创建调用者/测试，不破坏其他命令结果。creationTaskRevision不可变，READY后Start使用查询取得的当前task revision。
2. 在现有创建事务附加handoff PREPARING；不复制创建算法。创建指纹包含目标、选择顺序、内容hash、模型/策略/预算和版本。
3. 原Room N→N+1仅增加必要关系表/索引与聊天audit表。按架构§6.2冻结chat批准持久化字段及唯一约束，批准消费/调用开始/命令回执同事务；不能只存摘要。若采用独立批准投影表，在本卡冻结理由和迁移。两类数据共一次计划迁移；若分两卡实施，按实际N顺序演进，禁止各自预占同版本。整合者独占版本/注册及KSP schema。
4. agent_task_handoffs同时是任务引用，input_sources记录真实来源。快照正文进入私有payload，不改变旧聊天entity或导出格式。
5. 先PREPARING任务，复制前持久化稳定inputId/path/expectedHash和PREPARED账本，幂等导入不能每次随机命名；按同一计划复制/rename/登记COMMITTED，覆盖rename→manifest窗口。最终READY事务CAS handoff revision/inputSetHash/sourceGeneration；start/continue/spec层拒绝非READY，不能只做UI禁用。
6. 捕获session/message/asset/draft revision后异步导入；输入读取绑定expectedHash和当前撤销状态，不借读临时聊天路径，不用message position当身份。
7. 覆盖DB↔文件每个中断点，恢复核账不自动执行；source撤销影响模型/tool/egress及已读transcript。首发REVOKED原task禁止继续，用户确认新建排除该资料的任务，不在原task自动successor。提交结果/读取/发送也验sourceGeneration。
8. 删除路径清理新来源、manifest和引用隐私信息；保留必要幂等壳。历史任务卡若保留仅留脱敏handoff壳，不能一边整行删一边承诺显示。仅删除源聊天时解除聊天关联/投影，独立task仍保留readiness/REVOKED/幂等ledger，不能删行变无handoff任务绕过门禁。chat audit/snapshot/payload随源会话/轮删除准确取消后清理，孤儿按引用核账GC；保持原备份合同，新私有根排除备份。导出默认不带inputs/goal/grants；归档不运行，import provenance另记决策不伪造invocation。

**验证**：I20–I25、I28–I29、I36的fixture层；旧聊天/助手/附件fixture迁移逐字段保持，同goal并发/分页外创建、重复command跨重启、copy/rename/READY/start各中断点。

**拒收**：放大goal塞历史；先create正常task让它可start，再异步补资料；直接Agent DAO append聊天；清库迁移；以改applicationId当升级证据。

**回退**：关闭新handoff创建；PREPARING不启动，可重试/删除；保留新schema与旧数据，必要时前向修复，不能数据库降级。

**复制提示词**：

> 执行C05，严格按架构§7实现稳定创建receipt和PREPARING→READY交接。你负责分配的数据/仓储文件，不是唯一参与者，Room版本/注册/公共contract交整合者。复用AgentTaskRepository唯一创建事务，加handoff和input_sources；正文放inputs/payload，goal上限不变。start/continue命令层拒绝未准备好或已撤销资料，真实hash校验和撤销必须贯穿read/model/egress。聊天引用使用session+messageId+contentHash软关联，Agent不直接写聊天消息。做崩溃/幂等/迁移/删除和来源撤销验收，不删现有表数据，不自动恢复运行。交付I20–I25/I28/I29/I36证据。

## 9. C06：聊天工具轮纵切和真实最终回答流式

**依赖**：C04、C03相关能力，chat audit schema由C05/整合者提供；C01/C02准确取消。**写集**：拟新增`app/chattools`、必要typed聊天接缝、model phase/progress契约及adapter、聊天feature局部projection/action和测试。禁止复制ChatExecutionOperations到新目录。

**步骤**：

1. 发送时冻结LEGACY_CHAT/CHAT_TOOLS/IMAGE_EXISTING_FLOW。默认和已存在/image、附件、生图路径保持；本卡只开放受支持文本工具轮。
2. 窄snapshot builder复用原chat context业务，冻结角色/模型/参数/已准入历史/资料/引用；不把MainUiState给kernel、不重复追加上下文。
3. 实现ChatToolTurnUseCase，只管turn生命周期/audit/批准yield/settlement，不含第二模型工具循环。使用真实chat主体资源scope，不借用别人的task workspace。
4. 首发注册`materials.list/read_text/search_text`以及已授权的`web.search`等语义工具，实际名字由本卡contract固定；复用底层资料reader/search服务。不能因为名字相似直接向聊天暴露全workspace工具。
5. CHAT_TOOLS路径不运行legacy自动搜索或生图tool handler；search只由kernel调用共同provider。显式搜索意图纳入snapshot，未实际检索不能显示“已检索”。默认聊天仍走原搜索/生图。
6. CONTROL缓冲，工具结果/参数按既有限额；FINAL_SYNTHESIS定义typed模型phase和FinalAnswerDelta。使用phase-aware prompt/serializer移除JSON协议和工具schema，Final envelope转回答材料，不塞CONTROL assembler或绕回旧聊天链重复组装。禁工具且不扫描FINAL文本执行命令。控制/修复/最终/失败/取消attempt共用5次、2工具、8192 tokens/180秒初始硬预算，跨yield不重置。
7. final delta复用原accumulator，经冻结session/message/epoch核验后投递；成功经原settlement完成引用、摘要/记忆等适用行为。中断不误写成功记忆，不另写历史DAO。
8. 写入或超出只读scope返回NeedsWorkspace，显示交接建议，必须用户确认后C07创建；不自动后台运行。等待外发批准不持native lease，但仍占原聊天发送槽并保留生成时切会话限制；不能清isGenerating让新turn覆盖它。独立workspace不占此槽。
9. 进程恢复只显示中断；重试为新执行，旧pending批准失效。关闭feature gate不自动重发普通聊天。

**验证**：I05–I08、I14–I19、I25、I27、I30–I31适用范围。包含不启工具无额外推理/连接、最终自然语言真实delta、非法控制不调用tool、重复搜索次数、时延/预算、取消和旧回调。

**拒收**：原始JSON进入气泡；解析完分字播放当流式；静默切云/切模型；工具超限继续loop；每轮创建假task；聊天VM自己实现loop或拿根store权限。

**回退**：拒新chat-tools，准确结算在途turn；默认聊天原路可用，已有chat audit保留。用户重试普通聊天必须显式发送，不自动再执行。

**复制提示词**：

> 执行C06，接入按轮启用的只读聊天工具模式，默认聊天和现有生图流程保持。ChatToolTurnUseCase只管理本轮身份/audit/历史适配，调用同一AgentSessionKernel，不写第二循环、不创建AgentTask。冻结原context和角色/model/params；工具路径避免legacy重复搜索。CONTROL完整验证前不执行、不显示raw JSON；合法完成后同kernel用tools-disabled FINAL_SYNTHESIS真实流式生成自然语言，额外attempt全部计预算、可精确取消。通过原accumulator和唯一settlement写目标消息，禁止Agent直接DAO append。写入/外部副作用先建议移交并等用户确认。按I14–I19/I25/I27/I30/I31及外发/取消验收交付，缺条件不要冒充通过。

## 10. C07：显式移交、聊天任务引用和结果回草稿

**依赖**：C05/C06；只做handoff UI时不必先开放所有网络能力。**写集**：feature/chat的选择/确认/任务引用组件、typed handoff port、Agentroute定位、composer结果引用adapter。导航共享写集仅整合者。

**步骤**：

1. 显式“交给Agent执行”打开目标/选择清单/模型/网络确认；不默认带全历史、记忆或所有附件。正在流式的assistant资料提示等待，不偷偷停止。
2. 一次确认一次commandId，连点和重建复用；内容变更先重新确认。展示创建/准备/失败/READY状态，不把“资料复制中”写成任务正在推理。
3. READY后查询当前task revision，仅按明确创建并执行意图发幂等Start；导航/观察不启动。打开task使用receipt ID，分页/同目标不猜列表，不拿创建时旧revision直接Start。
4. 聊天任务卡只读投影handoff+Agent权威状态，支持打开/解除引用；精确取消Agent必须明确作用于对应run，不能复用聊天stop。
5. 结果只选已提交产物/摘要放回composer；冻结targetSessionId/draftRevision/source run和commit/hash，异步完成后重验，不能写当前页面。原草稿非空提供可理解合并/取消，保留输入光标/附件语义和引用provenance/class直到实际发送授权，不自动发送或粘贴文本丢分类。
6. 源删除/任务删除/来源撤销显示各自正确状态；返回优先关弹层、详情回工作区/来处，旋转/重组不重复创建。

**验证**：I20–I29、I32、I34；真实交互、连点/重建/分页外任务/切会话/原草稿/源删除/导航失败；生产sidebar顺序保持图片下Agent，旧智能调参不变。

**拒收**：一个按钮直接转移整段会话；任务卡写成伪消息；打开详情自动start；结果回传自动发一轮模型。

**回退**：关闭新handoff动作，保留已建任务和关联数据；现有Agent入口继续可访问既有任务，不删数据。

**复制提示词**：

> 执行C07，仅实现已冻结handoff和聊天投影UI。用户逐项选资料和目标后才创建独立任务，使用稳定receipt、PREPARING/READY状态和原幂等Start；页面观察/导航不启动。聊天卡片只读关联Agent权威，不写假消息。结果放回原composer并保留已有草稿，由用户发送。保持图片下Agent独立入口、旧智能调参、键盘/返回/焦点/滚动。源删除、任务删除、解除引用和撤销资料要按不同语义处理。你只写指定UI文件，公共route/装配交整合者，勿依赖另一个feature内部。交付I20–I29/I32/I34适用证据。

## 11. C08：共享UI等值提取

**依赖**：C06/C07有两个真实调用者。**写集**：实际复用的内容原语和活动/批准展示组件、两侧调用点；与前端精修负责人逐文件移交。

**步骤**：只提取业务语义一致的最小组件，值和行为先等值；共享DTO不带chat VM/task owner。原专有MessageRow、滑动/重试/历史状态不整体搬家。每个新组件至少两处生产使用，否则保留局部，不堆装饰性抽象。

**验证**：I33–I34；浅深色、自定义背景、长文、代码/表格、选择复制、大字、IME、TalkBack、批准过期/失效；两轮实际渲染。无可信baseline则明确限制，不能称严格视觉等值。

**拒收**：Agent依赖chat feature；新平行主题；整理token顺便改字号/间距；Preview当真机。**回退**：只回组件调用点，业务owner/数据不动。

**复制提示词**：

> 执行C08，先与前端负责人核对文件写集。只把聊天和Agent已有两个真实调用者的通用内容/工具活动/审批展示等值提取到合适共享层，业务action用typed callback。不要整体搬聊天MessageRow，不让Agent依赖chat内部，不创建平行主题，不调整冻结排版/色彩/尺寸。提取后两轮真实渲染并验证键盘、选择、返回、TalkBack及全部审批状态。交付I33/I34和差异说明；没设备保持NOT_RUN。

## 12. C09：集成、真机、升级、恢复与性能

**依赖**：C01–C08适用项。**写集**：测试、受控fixture、证据；只对已定位故障做小修复，禁止借验收重写架构。

**步骤**：

1. 最终候选绑定HEAD+tracked/untracked源码patch+子模块patch+APK hash+签名来源；模型绑定版本/hash/有效参数，证据脱敏。
2. 运行当前真实受影响模块测试和架构门禁；核对脚本是否包含新增模块/untracked文件，空报告不得PASS。
3. MNN和GGUF分别完成原矩阵任务、取消、会话/任务隔离和资源返回；云/MCP单列，不互相代替。
4. 多入口压力：workspace审批中发云聊天；聊天A迟到stop时AgentB运行；Agent排队与原Local API/调参/模型切换/图片竞争；不同owner不能彼此误停或污染。
5. 真实旧数据同签升级，验证原内容和新schema；Room/文件每个中断点、至少原矩阵要求的进程死亡恢复；不会静默发网或继续副作用。
6. FGS失败/后台/通知精确停止、SAF权限/导入导出、键盘/返回/无障碍、浅深色两轮渲染。
7. 同条件记录默认聊天before/after和工具轮额外开销：TTFT、总时延、四阶段取消、全进程/native/GPU适用内存、磁盘；给样本和波动，不声称无代价。
8. 安装权限或云条件缺失就保留BLOCKED/NOT_RUN，不卸载清数据、不改包名冒充正式升级、不降低断言。

**验证**：I01–I38全部适用项及原V1矩阵；映射到对应APK/模型。**拒收**：只报3245旧测试；debug隔离包当生产升级；UI编译当视觉通过；降低context/精度/画质提性能。

**回退**：关新入口和新执行，按准确identity结算，保留schema与数据；前向修复迁移问题，不能恢复旧APK清库。

**复制提示词**：

> 执行C09，验证最终整合候选，不仅验证各子Agent分支。绑定源码dirty patch/子模块patch/APK/model/签名，串行使用受控build锁，跑真实匹配的测试并保留日志。分别验证MNN/GGUF、云、MCP以及默认聊天/生图/智能调参/认证Local API；重点是迟到stop隔离、真实静止和lease、输入准备/撤销/崩溃、同签旧数据升级、FGS/SAF/IME/TalkBack和两轮渲染。性能给同条件实测。缺设备/权限/凭据诚实BLOCKED，禁止清库、伪造PASS或削减功能。按I01–I38和原门禁逐项交付。

## 13. C10：最终证据、维护和回退交接

**依赖**：所有拟声明支持范围的C09证据。**写集**：本集成状态manifest/交接、适用ADR、能力/兼容矩阵。不要覆盖原始日志。

**交付**：

- 实际功能与未开放能力，明确默认聊天、chat-tools、workspace三个边界。
- 唯一owner/资源lease/状态writer/生产调用者图，普通修改应局限哪些文件。
- 共享kernel和权限无重复实现；无反向依赖、Host/store债务扩张或无用adapter。
- schema/格式演进、旧数据兼容和来源撤销/删除说明；新表/文件隐私清理路径。
- 逐项实际检查、原始证据、revision/APK/model绑定、NOT_RUN/BLOCKED和外部限制。
- 临时adapter及退出条件，开关关闭顺序、数据保留、准确停止和前向修复步骤。
- 当前状态入口收口，历史JVM/设备结果不篡改，不以“全面超越”替代比较证据。

**验证**：I39–I41和所有父门禁汇总；源码/文档链接、diff及敏感信息检查。**退出**：另一个维护者能回答新增工具/改权限/改模型/改页面各找谁，并复现声明范围。

**复制提示词**：

> 执行C10最终审计，审整合后的真实生产接线和diff，不照抄子Agent完成摘要。逐项核实owner、writer、唯一kernel、权限消费、数据迁移和回退；引用每项真实证据的revision/dirty patch/APK/model范围。收口一份当前状态manifest，保留历史，明确NOT_RUN/BLOCKED、未支持插件/协议和临时adapter退出条件。只声明已验证能力，不宣布无证据的10分或全面超过第三方/DSH。无授权不push/merge/publish。

## 14. C11：后续DSH能力路线，单独授权和排序

本卡首发只做设计/能力矩阵，不实现任意脚本执行、Termux/终端、Android自动化或子Agent。

依次评估：资料parser → 受限执行沙箱 → 进程树owner/取消/配额 → 终端工具 → 子Agent的身份/预算/授权交集 → Android窄工具 → 更广Skill/MCP兼容。每项要求手机端真实ARM64执行、资源回收、隔离/授权和恢复证据。

**复制提示词**：

> 仅做C11后续能力设计，不改首发实现。基于当前共享kernel和真实手机限制，分别写资料处理、代码/终端沙箱、子Agent、Android控制、Skill/MCP兼容的先决条件、owner、权限、数据恢复、资源成本和验证方法。区分手机本地执行与云端依赖，不把安装插件等同于可运行。以同任务/模型/条件建立DSH/第三方比较基线，禁止无证据宣称全面超越。每项给独立可回退任务，不挤入C01–C10。

## 15. 给整合主Agent的一次性总提示词

下面这段用于未来获得实施授权后启动；本轮没有执行它。

```text
你要在MCA现有Agent V1实现上，完成“聊天工具增强 + 独立Agent工作区 + 一个共享执行内核”的增量集成。

先完整读适用项目指令、docs/architecture/MCA_PROJECT_HARD_LOCK.md，以及
docs/proposals/agent-chat-integration-v1/下ARCHITECTURE.md、PROBLEMS_AND_ACCEPTANCE.md、EXECUTION_PROMPTS.md。
同时读原Agent V1合同/执行包/验收矩阵，原门禁缺口不能被新方案覆盖。

先锁定当前HEAD、dirty工作区、untracked生产文件、子模块revision及patch，保留全部他人改动。
不要假设文档基线就是当前基线。逐项复核P01–P19，不将已修复项继续当缺口，也不将源码存在当真实运行通过。

按C00→C01/C02/C03安全底座→原G0→C04共核等值→C05数据/C06工具纵切→C07/C08界面→C09/C10验收交付执行。
存在设备等阻塞时继续独立的只读/纯逻辑/fixture工作，记录具体NOT_RUN/BLOCKED，不能开放未就绪生产路径或降低门禁。

必须保持：
1. 默认聊天原体验、角色/记忆/上下文/附件/视觉/搜索/生图续答、图片页、智能调参、模型和认证Local API均连续。
2. 独立Agent入口在侧栏图片下；旧AppTab.AGENT含义不变，AGENT_WORKSPACE独立。
3. chat-tools按轮显式启用；CONTROL不显示/执行partial JSON，FINAL_SYNTHESIS真实流式且禁工具；预算计算所有尝试。
4. 共享唯一session kernel、模型适配、schema/工具注册、权限规则和精确取消；聊天页不写第二套循环。
5. 原AgentRunOwner写task/run，chat owner写消息，chat-tools有最小audit；不造假task，不直接Agent DAO append聊天。
6. 交给Agent必须用户选目标/资料并确认；原创建事务返回稳定receipt并PREPARING，真实文件/hash校验后READY，未READY命令层不能启动。
7. 结果回传首发只进composer由用户发送；卡片是只读任务引用。源删除、解除引用、任务删除、来源撤销各有明确语义。
8. 原engine/facade/worker/lease唯一；真实native静止前不释放lease。null/Flow终止/token清空不是proof；Blocked保持STOPPING。
9. 旧聊天stop也必须精确，迟到stop不能停新Agent。完全离线零socket；实际cloud/MCP/search payload逐次来源/hash授权。
10. feature不相互依赖，公共组件等值提取；不加MainUiState业务字段，不扩万能Host，不新增Any注入，不把策略塞composition。

并行只分独立写集，每名执行者说明不是唯一改代码的人，不得回滚他人。公共contract、根build、Room版本/注册、主装配、导航由你唯一整合。
所有Gradle/native验证用同一项目锁，禁全机杀构建进程、乱clean/删缓存。准确记录匹配测试数、跳过、实际日志；不拿旧XML或fake/stub代替真机。
数据迁移按实际N演进，不清库、不降级、不用改包名代替同签升级。回退优先关闭新执行、准确结算并保留数据。

持续完成已授权切片和必要验证，普通实现选择自主推进。真实超授权行为先给可审查影响，不反复询问已授权常规工作。
每卡交付实际改动、行为/数据影响、owner/writer、证据、NOT_RUN/BLOCKED、临时路径退出条件、回退点和下一卡。
未经授权不commit/push/merge/publish，不宣布无证据的全功能或10分。
```

## 16. 验证命令模板：实施时核对当前脚本后使用

当前完整测试工具链要求JDK21。先读脚本，以下只是任务选择模板，不代表本轮执行结果。

```powershell
# 独立运行，不能和Gradle/native并发抢同一构建目录。
python tools/architecture/check_architecture_rules.py

# 原Agent/app集合；不能同时叠加-Tests/-FeatureTests/-FullTests。
.\scripts\verify-code.ps1 -AgentTests -Rerun

# 聊天feature与真实受影响app测试分别运行，过滤器用仓库实际类名。
.\scripts\verify-code.ps1 -FeatureTests -Rerun
.\scripts\verify-code.ps1 -Tests '*ActualRelevantTest' -Rerun

# 必须先阅读wrapper；-KeepDaemons避免默认广域进程清理，-KeepLog保留证据。
.\scripts\agent-verify.ps1 -KeepDaemons -KeepLog -Tasks `
  ':core:agent-contract:testDebugUnitTest',':core:agent-runtime:testDebugUnitTest'

# 发布候选构建仍用项目受控入口和真实SDK合同。
.\scripts\build.ps1
```

新增模块/源集若不在上述脚本集合内，整合者补充明确任务；`-Changed`不能自动证明untracked已覆盖。测试过滤为0/未生成报告时失败，不写PASS。设备安装和instrumentation先确认真实变体/签名/数据，不能直接从旧命令复制覆盖正式安装。

## 17. 每卡统一交付模板

```text
任务卡/实际范围：
起点HEAD、tracked/untracked patch、子模块revision/patch：
本卡写集及未触碰的共享面：
实际owner / 唯一状态writer / 请求身份 / 原lease：
生产调用者与已退出的旧路径：
功能、默认值、数据、协议和视觉影响：
运行命令/测试匹配数/失败/错误/跳过/原始日志：
证据作用范围：static | unit | device | runtime | release
PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE逐项：
依赖门禁和例外：
临时adapter退出条件：
回退或前向修复：
下一张可独立执行的卡：
```

## 18. 本次文档交付检查

2026-10-19仅新增本目录三份Markdown文档。已核对相对链接目标、代码围栏、编号连续性、空白及常见凭据格式；包含C00–C11共12卡、P01–P19共19项问题、I01–I41共41项验收。两名只读审查者分别检查共享kernel/聊天生命周期和交接数据/恢复协议，发现的问题已补入架构及对应提示词，并复核关闭。

这些检查只证明方案的链接/编号和所审设计边界一致；未执行提示词、未改生产源码、未改变数据库、未构建或真机测试，I01–I41仍是待实施验收。既有工作区及并行前端变更保留。
