# 当前问题、解决方式与验收合同

适用方案：[架构 V1.1](ARCHITECTURE.md)；实施入口：[任务卡和提示词](EXECUTION_PROMPTS.md)。

## 1. 本轮检查范围

时间：2026-10-19。HEAD：`ea11d213dae43c8f7dd5ac15aaaaf62f5b8c71e7`，同时存在未提交 Agent 实现和并行前端改动。以下结论来自当前工作区源码和既有报告的只读检查，未改代码、未构建、未重跑测试、未在设备复现。

结束复核时并行会话HEAD已到`2368c72cec312f820e2452f2537302dcfc067805`，其间已提交变更为Local API设置页及帧测量脚本；本清单保留原检查窗口，不假装已对新工作区全量重测。C00重新锁定最新实际状态。

子模块输入：llama.cpp `4ceb1719101f32637b841206c172f3f058ffc182`；stable-diffusion.cpp `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0`；tokenizers-cpp `acbdc5a27ae01ba74cda756f94da698d40f11dfe`。tokenizers 有五个既有修改文件，未来验证必须附对应diff/hash，不将其当作干净revision。

本清单中“源码已确认”表示静态事实；“潜在风险”表示尚无真实事故复现；“未接线”与“未验证”分别记录。不得把这几类统一说成“线上已经出问题”。

## 2. 当前进度的可信说法

| 层次 | 当前证据 | 能得出什么结论 |
| --- | --- | --- |
| JVM报告 | 既有 XML/交接记录合计3245 tests，0 failures，0 errors，11 skipped；报告窗口约为当日16:10–16:13 | 当时任务集合有代码层证据；包含原app回归，不是3245条Agent专项，也不是本轮重跑 |
| 架构/编译/APK | 既有记录有14项静态检查、compile/assemble成功 | 仅代表当时源码/产物的对应范围，不证明当前dirty工作区每条运行路径 |
| 独立Agent实现 | contract/runtime/Room/工具/UI/FGS/Skills/MCP等大量代码存在 | 已进入实现与验证阶段，不能当作“只有文档”；也不能当作生产完成 |
| 真实MNN/GGUF任务、取消与资源返回 | 无可接受实证 | 原G0未完整通过 |
| 云端Agent | adapter存在，生产组合仍仅接本地；云选项默认不显示 | 云端尚未生产闭环 |
| MCP | run-scoped grant→connect→catalog→release已有生产接线；真实server和完整payload授权未闭环 | 不能继续说“完全没接线”，也不能说生产兼容验收已通过 |
| 设备验证 | 既有安装记录为`INSTALL_FAILED_USER_RESTRICTED` | 安装受设备设置阻塞，不能推断所有模型/迁移会失败 |
| 视觉/IME/TalkBack/性能/同签升级 | 缺真实证据 | NOT_RUN或对应BLOCKED，不能用UI编译替代 |

历史证据见 [V1交接](../agent-v1/MCA_AGENT_V1_HANDOFF.md)、[当日验证记录](../agent-v1/evidence/5e912ff9/verification-2026-10-19.md)。两份记录内部有新旧接线表述冲突，需C00收口；这里引用原始范围，不提升其门禁。

**当前不能宣布原V1的G0–G3全部通过，也没有本V1.1集成的生产验收结果。** 编码卡的“JVM/code-layer PASS”不能给父卡或release gate自动盖章。

## 3. 已定位问题和具体解决方式

### P01：停止证明偏弱，可能过早归还原native lease〔最高优先〕

源码已确认：[EngineAgentStopProofSource](../../../app/src/main/java/com/muyuchat/mca/agent/inference/AgentStopProofSource.kt)把token消失/变成其他请求且facade终态作为Confirmed；读取异常转null后也可能满足。原[流式执行](../../../core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceStreamChat.kt)在finally清token，不能单独证明隔离worker/native静止。

[McaAgentLocalModelAdapter](../../../app/src/main/java/com/muyuchat/mca/agent/inference/McaAgentLocalModelAdapter.kt)的finalize把该证明交给[retention](../../../app/src/main/java/com/muyuchat/mca/agent/inference/AgentModelLeaseRetention.kt)释放原lease。尚未证明真的发生并发重入，但证明链不足。

解决：只在原engine/worker owner补按request/token/worker incarnation核对的窄静止接口；读取失败/缺证据/陈旧证明返回UNKNOWN。未派发可直接结算；派发后须有真实返回或安全空闲/终止确认，才恰好释放原lease。不加第二native锁，不轮询会阻塞的JNI/Binder查询，不杀共享进程。

验收：迟到worker回调、Flow取消但native仍跑、token先清、读错/超时、重复proof、旧worker证明都不能提前释放；MNN/GGUF真实任务分别证明下一请求不会越过资源栅栏。责任C01，验收I01–I03。

### P02：停止超时结果未完整传给run状态

源码已确认：retention可返回`Blocked`，adapter的finalize未消费这个结果；上层可能把执行结束当作完成资源结算。

解决：以typed settlement回执把`Released / StoppingUnknown / Failed`传给session/owner；STOPPING期间保留活动槽和资源fence，由应用scope持有的准确结算器继续等待或接收原owner通知。不得吞掉Blocked，也不能让finally无限NonCancellable等待。故障展示和重试须有界，不能指导用户“重置锁”。

验收：UI已反馈停止但底层未停时，run不得显示CANCELLED且允许资源重入；迟到真实证明只释放一次。责任C01，I02–I03。

### P03：旧聊天停止仍是全局停止，新增Agent后存在误停风险

源码已确认：[ChatExecutionPortAdapters](../../../app/src/main/java/com/muyuchat/mca/ChatExecutionPortAdapters.kt)的requestRuntimeStop调用facade.stopGeneration；[ChatExecutionOwner](../../../core/engine/src/main/java/com/muyuchat/core/engine/ChatExecutionOwner.kt)执行cancelAllActive和全局engine stop。尚无本轮真机误停复现。

解决：停止目标在聊天attempt登记/取消前冻结；按原请求identity精确停止。不得停止时重新读当前active token作为目标。保留用户立即反馈、原聊天错误和回滚语义；Local API、图片、调参的stop合同独立审查，不能顺手重写。

验收：聊天A停止迟到时Agent B继续；Agent停止时聊天/API/图片不被标取消；没有attempt的聊天取消不会碰其他请求。责任C02，I04–I05。

### P04：搜索的异常包装可能吞掉取消

源码已确认：[WebSearchTurnExecution](../../../app/src/main/java/com/muyuchat/mca/WebSearchTurnExecution.kt)的runCatching可把CancellationException映射为搜索失败/继续回答。是否实际触发取决于调用时序。

解决：首先重抛CancellationException；业务可恢复错误才进入原降级语义。kernel搜索adapter调用同一窄搜索能力，不能复制爬取/搜索实现。

验收：取消搜索不再发后续模型请求、不追加成功回答；普通搜索失败原本允许直接回答的行为保留。责任C02/C06，I06。

### P05：云adapter存在但生产模型路由未闭环

源码已确认：[MainRuntimeAssembly](../../../app/src/main/java/com/muyuchat/mca/composition/MainRuntimeAssembly.kt)只把本地modelPort注入；[AgentModelCatalogAdapter](../../../app/src/main/java/com/muyuchat/mca/presentation/AgentModelCatalogAdapter.kt)默认`includeCloudModels=false`。不能把隐藏云开关改true当作接通。

解决：typed binding router根据冻结的provider/model选择既有凭据配置和精确HTTP Call；串起实际payload授权、严格terminal和取消。先验证已有严格adapter支持的协议；其他provider通过显式严格模式复用解析器，旧普通聊天默认不变。没有合法terminal的EOF、空流、incomplete都失败；不自动换provider。

验收：真实受控云请求、拒绝外发、断流、单Call取消、旧回调和模型切换。无法获得条件时保持NOT_RUN和未开放状态。责任C03，I09–I10。

### P06：MCP已有origin接线，完整payload来源授权仍有缺口

源码已确认：[RunScopedAgentToolCatalogProvider](../../../app/src/main/java/com/muyuchat/mca/agent/composition/RunScopedAgentToolCatalogProvider.kt)按run连接registry，自动建立带PRIVATE分类但sourceIds为空的grant；guard主要检查origin。[AgentNetworkEgressPolicy.checkPayload](../../../app/src/main/java/com/muyuchat/mca/agent/security/AgentNetworkEgressPolicy.kt)存在，本轮搜索未发现生产调用者。这表明完整来源检查未证明接通，不等价于已证明数据泄露。

解决：区分发现连接权限与发送内容权限。每次实际模型/search/MCP请求在序列化发送前投影全部来源和hash，broker对新来源/分类/目标重新授权。工具逐次审批不替代payload校验；空source授权不能被解释为全资料授权。对无用户数据的协议包也记录明确的空来源投影。

验收：同origin新增PRIVATE/LOCAL_ONLY/SECRET资料、工具结果或Skill文本时，旧grant不能覆盖；拒绝时网络请求数为零；最终序列化字节与批准投影一致。责任C03，I07–I08。

### P07：MCP schema漂移未联动实际审批失效

源码已确认：[AgentMcpRegistry](../../../app/src/main/java/com/muyuchat/mca/agent/extensions/mcp/AgentMcpRegistry.kt)提供catalog变化listener；生产provider未传入实际失效listener。

解决：list/schema变化、停用和删除传给owner/broker，按generation使待批及已批未消费命令失效，禁止旧schema执行。网络发现不持catalog全局Mutex；generation CAS拒绝迟到发布。

验收：批准弹窗打开后schema变更、删除server、重连返回旧catalog均不能执行；不存在无限等待网络持锁。责任C03，I11–I12。

### P08：Skills快照可能停留在runtime第一次构建时

源码已确认：MainRuntimeAssembly在lazy runtime构造阶段buildRunSnapshot并作为provider固定列表传入；管理变更的pending approval失效通过异步回调处理。缺少“下一run一定拿到最新集合、撤销立即在执行口生效”的完整证据。

解决：每次run/session冻结当前Skill版本；immutable版本保留供审计，撤销作为独立实时generation/policy检查。禁用动作返回前先提交撤销事实，executor前检查，不靠异步刷新UI完成防护。不要每个token读取磁盘，也不要修改运行中快照文本。

验收：runtime创建后安装/启用的Skill影响下一run；旧run的已撤销工具不能继续调用；更新不篡改历史snapshot。责任C03，I13。

### P09：当前循环直接绑定工作区，不能直接拿给聊天

源码已确认：AgentPromptedLoopCoordinator及AgentLoopTypes直接使用AgentRun/Task/Journal、task.goal、taskId产物校验。直接调用会迫使聊天造task或进入workspace唯一活动槽。

解决：按架构§5等值提取唯一session kernel与typed主体、profile、event sink；workspace adapter保留原journal/CAS，chat turn使用自己的audit/历史adapter。禁止复制循环、fake task、fake成功journal。

验收：两个入口相同协议语料得到相同校验和权限结论；chat不创建task；workspace等审批不阻塞独立云聊天；原workspace回归保持。责任C04，I14–I16。

### P10：控制JSON delta不能直接当聊天自然语言流

源码已确认：[AgentModelPort](../../../core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/AgentModelPort.kt)只返回完整visibleText，当前TextDelta可能是控制JSON。直接接到聊天会泄露协议和半截参数。

解决：CONTROL缓冲/只显示活动，合法完成后同kernel进入tools-disabled FINAL_SYNTHESIS，独立attempt计预算，仅FinalAnswerDelta进入原accumulator。失败保留未完成状态，不伪造流式。

FINAL必须换phase-aware prompt/serializer，移除要求控制JSON的system指令和工具schema，将Final envelope转回答材料；不能直接把未配对Final塞进CONTROL assembler，也不能绕旧聊天链重新拼历史。

验收：半截/非法/EOF控制响应无工具副作用、无JSON进入回答；最终回答真实流式、可取消；额外推理有时延/用量记录。责任C06，I17–I19。

### P11：任务创建回执无taskId，UI按同goal猜新任务

源码已确认：[AgentCommandResult](../../../core/agent-contract/src/main/java/com/muyuchat/core/agent/contract/AgentErrors.kt)成功结果不含taskId；[AgentWorkspaceViewModel](../../../feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/AgentWorkspaceViewModel.kt)以新出现且goal相同定位。

解决：创建返回专用强类型receipt；repository把完整receipt写入幂等记录；所有创建调用者直接使用task/workspace ID。创建revision是历史事实，READY后启动要读当前task revision再CAS。其他命令结果不改成多组nullable字段。

验收：同目标双创建、分页外任务、回执丢失重放都定位准确；同command不同payload冲突。责任C05，I20–I21。

### P12：输入快照实现未接生产，读取缺来源/hash栅栏

源码已确认：AgentInputSnapshotStore已有转码/hash/manifest，但本轮未发现生产调用；[AgentWorkspaceReadStore](../../../app/src/main/java/com/muyuchat/mca/agent/data/AgentWorkspaceReadStore.kt)的inputs读取未绑定预期hash/来源撤销。

解决：交接事务建立PREPARING→READY，输入目录绑定sourceId/storedHash/size/class；start、read、模型组装和egress均检查当前有效来源。不能用只读文件标志代替完整性和撤销。goal保持8KiB，不拿放大goal装全部历史。

现有store随机importId且rename后才写manifest，不能直接证明幂等复制。复制前持久化稳定inputId/path/expectedHash和PREPARED账本，重试复用同一计划；最终READY事务CAS revision/inputSetHash/sourceGeneration。首发来源撤销后原task禁止继续，重新确认创建排除该资料的新任务，不复用受污染transcript。

验收：每个DB/文件中断点、哈希损坏、权限撤销、不支持二进制、截取与原文区分；非READY不能启动。责任C05，I22–I24。

### P13：聊天按完整快照reconcile，Agent直接append可能被覆盖

源码已确认：[ChatSessionStoreDao](../../../app/src/main/java/com/muyuchat/mca/ChatSessionStoreDao.kt)会删插消息快照；[ChatSessionPersistenceOperations](../../../app/src/main/java/com/muyuchat/mca/ChatSessionPersistenceOperations.kt)有序列和回滚保护。Agent若直接DAO追加会成为第二writer。

解决：任务卡来自handoff侧表只读投影；聊天回答通过原settlement；结果回传首发只放入composer由用户发送。来源用session+messageId+hash软关联，不能用position或给非唯一messageId乱加FK。

验收：旧快照保存、编辑/删除、切会话/重试、草稿已有内容时，任务卡/回答不丢失、不写错会话、不自动发送。责任C05–C07，I25–I28。

### P14：附件导入存在live session读取，需要固定新快照边界

源码可见风险：[ChatAttachmentOperations](../../../app/src/main/java/com/muyuchat/mca/ChatAttachmentOperations.kt)异步导入中有读取live session/输入的路径。没有本轮实际串会话复现。

解决：新handoff和chat工具捕获import owner/session/draft revision，完成时只向原目标发布；必要时用现有附件mutation通道做最小修复。不要把composer marker当文件权限，不改聊天全部附件实现。

验收：导入过程中切会话、编辑草稿、撤销附件或关闭页面不把材料塞入其他会话/任务。责任C05/C07，I24、I27。

### P15：设备安装受阻，升级与运行验收缺失

既有记录：MIUI安装被拒。解决：保留现有安装和用户数据，由设备所有者调整USB安装许可后执行既定受控测试；无法调整时保持BLOCKED。隔离deviceTest变体可验证部分设备行为，但不能证明正式applicationId、同签升级和真实生产入口。

验收：真实旧数据→兼容签名新包，Room迁移、生产MainActivity、认证Local API、MNN/GGUF、FGS/通知、SAF、IME/无障碍和性能；不能以卸载/清库/改包名替代升级。责任C09，I30–I38。

### P16：记录漂移与阶段门槛顺序不一致

文档可见：旧记录混用3121/3168/3245测试数，K14完成/待做并存，MCP连接/未连接及FGS状态相互矛盾；旧checkout标识与当前HEAD不同。原K05要求G0通过后再做生产schema，但当前已有27→28与生产接线，未找到精确时序例外记录。

解决：C00建立一个当前状态manifest，给旧证据加revision/时间/范围；保留历史事实。审查并记录实际时序偏离及补偿验证，先补G0和资源/外发安全，不倒填PASS、不改规则逃避、不删已有表或数据。父门禁以必要子证据齐全为准。

验收：每个声明能追到原始记录；代码层PASS不自动变设备/发布PASS；没有互相冲突的“当前状态”。责任C00/C10，I39–I40。

### P17：构建便利脚本可能杀掉其他会话的Java进程

源码已确认：[agent-verify.ps1](../../../scripts/agent-verify.ps1)默认按进程命令行匹配全机Gradle/Kotlin Java进程并Stop-Process，范围未绑定本checkout/本次构建。即使持本项目锁，也不能证明匹配到的其他项目进程可杀。

解决：本集成验证优先使用项目受控verify-code；需模块wrapper时先审参数，使用`-KeepDaemons`避免全机清理。后续如修脚本，限制为本任务确切创建且核验owner的PID，不能以正则扫全机代替所有权。共享build锁仍必须执行。

验收：并行其他项目/前端会话进程不受影响，测试空匹配或0报告不得成功；本轮仅记录问题，未停止任何进程。责任C00，I41。

### P18：独立归档和产物审计要分清已修与未决

已修事实：导入归档已有“显示已导入归档”开关，且只读；不能继续列成缺失入口。未决事实：导入产物因v28 invocationId非空不能写通常的artifact_commits行。

解决：保持只读归档和默认不导出inputs/goal；由专门provenance决策定义imported receipt，必要时再做独立迁移。聊天结果引用只接受真实已提交产物或明确归档来源；不伪造invocation来补账，不因本集成顺手改为自动运行导入任务。

验收：归档不可启动；输出引用能说明来源；导出不带授权、凭据和移交私人输入。责任C05/C07/C10，I29。

### P19：预算有数值，但硬超时和跨yield累计需补齐

源码已确认：AgentPromptedLoopCoordinator在infer返回后检查modelWallTime，每次execute重新设置activeSince；本轮在run/tools实现内未发现toolTimeoutMillis的执行限时使用。遇到永不返回调用或多次审批yield时，仅这些检查不足以落实合同上限；尚无本轮真实超时事故复现。

解决：同一execution维持单调时钟硬deadline、累计活跃时间和尝试记账，跨yield checkpoint保留已消费预算；等待批准才暂停活跃计时。模型/工具deadline触发所属调用准确取消，仍遵守真实静止后释放lease；未知副作用记UNKNOWN不重放。失败、取消、修复和FINAL全计数，不能成功返回才计费。执行机制与协议等值提取分开审查，先让故障测试暴露旧缺口，再修复。

验收：永不返回模型/工具能触发取消；多次yield无法刷新总预算；超时后未静止不得释放资源；用户拒绝不无限重试。责任C01/C04/C06，I19。

## 4. 新增验收ID：全部待实施，不是本轮PASS

状态字段统一`PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`；另有scope字段区分static/unit/device/runtime/release。下列I01–I41的初始执行状态均为`NOT_RUN`，P15对应的部分真实设备工作已有历史`BLOCKED`。不可把本轮写文档算这些实现验收通过。

| ID | 必须证明的行为 | 最低证据/所属卡 |
| --- | --- | --- |
| I01 | null/异常/陈旧proof不能确认native静止 | 故障注入+C01 |
| I02 | STOPPING/Blocked准确向上投影，原lease不提前释放 | 状态/竞争+C01 |
| I03 | 真实MNN/GGUF停止→静止→原lease返回→下一请求，各自成立 | 真实后端+C01/C09 |
| I04 | 聊天迟到stop不能取消新Agent/图片/API | 身份竞争+真机+C02/C09 |
| I05 | Agent/chat-tools取消只命中自己的waiter/attempt/HTTP Call | 并发测试+C02/C06 |
| I06 | 搜索取消传播，不继续回答；普通失败语义保留 | 行为测试+C02/C06 |
| I07 | 实际payload新增来源/hash变化必须重新批准 | 传输捕获/拒绝零请求+C03 |
| I08 | 完全离线profile所有请求零socket，含MCP/loopback/搜索/云 | 受控网络spy+真设备+C03/C09 |
| I09 | 云binding到实际provider/client且复用既有凭据 | 真实云纵切+C03/C09 |
| I10 | EOF/incomplete/断流/取消不被算成功，不影响其他Call | 协议fixture+真实服务+C03 |
| I11 | MCP schema/server generation变化使旧批准失效 | 受控server+C03/C09 |
| I12 | catalog网络不持全局锁，迟到generation不得发布 | 调度/超时+C03 |
| I13 | 每run刷新Skill snapshot；撤销立即阻止下一调用 | 管理→执行纵切+C03 |
| I14 | 生产只有一套通用模型/工具循环，无fake task或no-op journal | 调用链+行为+C04 |
| I15 | 原workspace协议、budget、批准、journal、取消与恢复等值 | 既有回归/故障+C04 |
| I16 | workspace等待审批不把普通云聊天或chat-tools塞进同一全局队列 | 并发+C04/C06 |
| I17 | raw控制JSON/reasoning/partial arguments不进聊天正文、不执行工具 | 注入/分帧fixture+C06 |
| I18 | FINAL_SYNTHESIS真实流式、禁工具、可精确取消、合法terminal | 模型/协议/设备+C06/C09 |
| I19 | 控制/修复/最终/失败/取消共用硬预算；永不返回调用超时；跨yield不重置；拒绝循环有界 | deadline/边界行为+C04/C06 |
| I20 | 创建回执返回确定task/workspace ID，不按goal猜 | 同goal并发/分页+C05 |
| I21 | 同command同payload跨重建重放原receipt；不同payload冲突 | DB重启/唯一约束+C05 |
| I22 | PREPARING/FAILED/REVOKED不能start/continue | 命令层直调+C05 |
| I23 | 稳定inputId与导入前ledger覆盖复制/rename/DB崩溃，READY CAS拒绝撤销竞争且不自动启动 | 文件+Room故障+C05/C09 |
| I24 | 缺文件/hash错误/权限撤销/二进制/截取资料有准确结果 | 真文件/SAF+C05 |
| I25 | 聊天与task分开writer，Agent不直接append聊天DAO | 调用链+旧快照覆盖+C05/C06 |
| I26 | 结果只进固定会话/草稿revision，用户发送；provenance/class保留到payload授权 | UI/mutation+C07 |
| I27 | 切会话/编辑/删除/重试/异步附件不会写错目标 | 竞争+UI+C05–C07 |
| I28 | 删除源/解除引用/删除任务/撤销来源正确；chat audit/payload取消后清理；脱敏引用壳语义明确 | 数据/执行/恢复+C05/C07 |
| I29 | 归档不能运行，产物真实来源，导出不泄露inputs/grants | fixture+SAF+C05/C07 |
| I30 | 默认local/cloud聊天、角色/记忆/压缩/重试/流式保持 | 现有回归+真实入口+C09 |
| I31 | 附件/视觉和/image、生图审批及角色续答保持；无重复搜索或生成 | 现有回归+真实入口+C09 |
| I32 | 侧栏顺序、独立route、旧智能调参入口保持 | UI真实渲染+C07/C09 |
| I33 | 浅深色/背景/大字/代码/表格/长文/滚动选择保持 | 受影响两轮渲染+C08/C09 |
| I34 | IME/返回/焦点/TalkBack及审批失效状态完整 | 真实设备+C07–C09 |
| I35 | FGS/通知精确停止、后台和进程恢复不自动外发/推理 | 真实设备+C09 |
| I36 | 原数据同签升级和新schema迁移/中断恢复通过 | 历史fixture+真实升级+C05/C09 |
| I37 | 生产认证Local API、模型切换/调参、图片并发不回归 | 真实UI/API+C09 |
| I38 | TTFT/总时延/取消四阶段/总资源/磁盘有同条件对照 | 测量+C09 |
| I39 | 当前状态唯一、证据包含HEAD/dirty patch/submodule/APK/model范围 | 清单审计+C00/C10 |
| I40 | 旧G0–G3与新集成门禁分开，缺项不能升父PASS | 证据审计+C00/C10 |
| I41 | 构建锁有效、不全机杀其他构建，过滤器匹配且报告非空 | 脚本审查/必要行为验证+C00 |

## 5. 集成门禁：与原G0–G3并存

| 门禁 | 必要条件 | 未满足时允许做什么 |
| --- | --- | --- |
| INTEGRATION-0 安全底座 | P01–P04停止/取消、P06–P08适用权限缺口解决；原G0有真实本地闭环 | 只读设计、纯逻辑及fixture准备；不以新UI掩盖风险开放新执行 |
| INTEGRATION-1 共核等值 | I14/I15、I19纯kernel部分及I16主体隔离fixture通过；workspace等值回归 | 开发隔离的C06聊天工具纵切；I17/I18及I16聊天端到端留C06后，不作本门禁前置 |
| INTEGRATION-2 数据可靠 | I20–I29、迁移fixture及真实Room/SAF适用项通过 | 继续UI/恢复验证，不用未READY任务做演示成功 |
| INTEGRATION-3 产品与多入口 | I01–I38中所有拟声明支持项有对应证据，原V1门禁缺口已准确处理 | 形成候选，不扩大云/MCP/模型支持声明 |
| INTEGRATION-4 交付 | I39–I41、最终diff、回退/前向修复、限制与证据齐全 | 才能声明对应范围完成；无授权不发布 |

云端或MCP若作为独立后续发布，必须在能力矩阵中标未发布且UI/执行入口保持对应门禁；不能将未发布模块写成整体支持。手机纯离线版本不依赖云/MCP验收，但仍需真实本地两个既定后端和所有适用安全证据。

性能不预设“零额外时延”。工具模式新增CONTROL和最终回答推理成本，记录TTFT、总耗时、成功率和资源；默认普通聊天无额外kernel请求、catalog联网或snapshot IO。超过项目已有阈值须修复或如实记录，不缩历史、精度、画质或功能换绿灯。

## 6. 当前状态manifest建议格式〔拟新增，不是已执行证据〕

```yaml
snapshot_id: integration-v1_1-<time>
scope: code | runtime | device | release
head: <full-sha>
worktree_patch_sha256: <tracked-and-untracked-owned-sources>
submodules: <revision-and-patch-hash>
apk_sha256: <value-or-null>
model_fingerprint: <value-or-null>
check_id: Ixx
status: NOT_RUN
command: <exact-command-or-null>
tests: <count-failed-errors-skipped-or-null>
raw_evidence: <relative-path-or-null>
limitations: <concrete-missing-items>
```

原始日志不可被摘要替代；禁止将旧XML改日期算新运行。状态manifest是当前事实入口，旧记录保留为历史，在受影响段落链接当前入口并说明适用范围。涉及真实私人内容或凭据的日志必须脱敏，不纳入可分发材料。
