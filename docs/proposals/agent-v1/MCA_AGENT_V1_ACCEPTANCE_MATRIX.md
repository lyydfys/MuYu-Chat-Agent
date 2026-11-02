# MCA Agent V1：验收、度量与证据矩阵

版本：V1.0；配套[架构合同](MCA_AGENT_V1_ARCHITECTURE.md)与[执行包](MCA_AGENT_V1_EXECUTION_PACK.md)。本文全部是未来验收要求，未产生运行 PASS。

## 1. 结果语义与硬门槛

状态使用 PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE。每条结果标明源码审查、JVM确定性、Android真实runtime、真实UI、网络fixture或发布候选类型。

- PASS 必须有匹配当前源码/产物的实际证据；“未看到失败”不是PASS。
- 测试过滤器匹配0项、旧XML、空报告、编译代替运行、fake代替native都不能通过真实执行项。
- NOT_APPLICABLE 必须解释为何不影响本次交付；V1必交能力不能用此状态跳过。
- 安全、数据、准确取消、epoch隔离与终态不变量不接受80%通过；所有适用case必须100%。
- 某runtime缺真机证据不能被另一个runtime代替。缺工具链/设备时允许继续独立工作，发布声明保留具体BLOCKED。
- 用户设备/芯片不作为功能准入名单。验收结果是实现与代表路径证据；未知设备仍允许真实加载/执行尝试。

阶段判定：

| Gate | 必要证据 |
| --- | --- |
| G0技术可行 | A01.source/A02/A03、A04.constructFixture、A07.taskDiscovery；P01.source、P02–P13确定性协议/配对、P14.protocol/P15.runner；O01–O06模型/O08.fence/O13.Responses；Q01-G0/Q02.spike/Q03.spike。MNN/GGUF和云分开；尚无生产DB/审批/FGS/MCP的子项明确NOT_RUN |
| G1原生闭环 | A01–A06生产部分；T01–T13；P01–P15；O01–O09/O11–O13；S01–S06/S09/S10.cloudCore/S11–S15及S16.backup；D01–D12/D20；U01–U14；Q01-V1/Q02.production/Q03.production/Q04；R02–R05受影响范围。真实UI文件任务、拒绝、取消、中断必须完成；S10的真实Skill/MCP来源、O10/X/导入导出等后置项仍NOT_RUN |
| G2 V1候选 | 本矩阵全部适用项；Skills/MCP限定兼容、升级/回归、视觉/性能和证据索引 |
| G3正式交付审计 | K17清理/ADR/兼容/回退/未验证范围完整；不自动授权发布 |

### 1.1 跨卡子项与完整验收归属

一个ID若跨层，结果必须写subcaseId/evidenceKind，父ID由下表最终负责卡汇总。已有确定性通过可以复用同revision的证据，但不能冒充未做的真实UI/数据库/后端部分。G0是明确子集放行，不要求尚无依赖的整项PASS；G1/G2使用上述明确集合。

后续变更没有影响的旧证据可以引用，附原revision/工作区patch/产物及不受影响的diff说明；不为凑矩阵重复相同检查。候选APK的主流程、升级、设备恢复等端到端证据仍须与最终源码/产物绑定，历史单测不能替代它们。

| 验收组 | 早期证据 | 完整验收负责卡 |
| --- | --- | --- |
| A01–A06 | K00源码、K01编译依赖、K08/09实际owner/导航 | K09复核生产接线；K17最终审计 |
| A07/A08 | K01.taskDiscovery，单卡职责预算 | K15实际脚本/结果覆盖；K17清理 |
| T01–T13 | K03内存状态、K05幂等重启、K07审批 | K08生产headless+K11恢复/UI汇总 |
| P01–P15 | K02.codec、K03配对、K04.runner/protocol | K10补P14.ui；K11汇总模型路径，K16最终回归 |
| O01–O09/O11–O13 | K04模型/fence/云terminal；K06文件提交 | K11真实后台/取消/恢复，K16候选复核 |
| O10/S08/X06–X14 | K07网络纯策略、受控server fixture | K13真实MCP与线协议；K16复核 |
| S01–S06/S09–S14 | K07 broker/transport，K08真实executor | K11生产闭环；MCP payload分支K13 |
| S07/S17/X01–X05 | K02解析限制、K06文件根 | K12目录/ZIP/Skill；S07导出包分支K14 |
| S15/S16 | K06.backup、K07脱敏策略 | K11通知S15；K14完整export/backup的S16 |
| D01–D12/D20 | K05 schema/commands，K06文件/payload | K11真实run/审批恢复；K16升级复核 |
| D13–D19 | K06提交/配额基础 | K14导入导出/删除；K16升级/大任务 |
| U01–U14 | K09导航、K10页面渲染 | K11交互/恢复，K16两轮视觉最终复核 |
| R01–R08/Q01–Q06 | K04 Q01-G0/Q02.spike/Q03.spike；K10 UI；K11 Q01-V1/Q02.production/Q03.production/Q04 | K16全部适用回归/质量/性能，补Q02/Q03.finalCandidate |

### 1.2 Q02/Q03的分阶段证据

下列subcase使用同一Q02/Q03指标定义；不同阶段的覆盖面不能互相冒充。G0/G1只放行指定子项，不给Q02/Q03父ID提前PASS。

| subcaseId | 责任卡与最小执行范围 | 不包含/后续要求 |
| --- | --- | --- |
| Q02.spike | K04；MNN/GGUF每后端至少10次真实取消，覆盖等待/prefill/decode；记录headless命令ack、dispatch、native静止和lease释放 | headless ack不算真实UI反馈；无生产Room/FGS的完整停止验收 |
| Q03.spike | K04；每本地后端同配置10次headless任务与取消，检查session/worker/全进程内存回稳 | 不算页面离开、生产持久化和FGS的生命周期验证 |
| Q02.production | K11；生产UI→owner→Room/FGS→真实adapter完整链，每本地后端至少10次取消，覆盖等待/prefill/decode；UI ack与其余三个时间分别达标 | 不能仅引用K04的headless时间；K16仍需finalCandidate证据 |
| Q03.production | K11；每本地后端同配置10次生产任务，包含取消、离开Agent页面、返回及FGS退出，核对所有进程和资源归属 | K04可辅助解释基线，不能替代新增生产生命周期；中断恢复另按Q04 |
| Q02.finalCandidate / Q03.finalCandidate | K16；最终候选源码/APK上的同等生产场景与样本数；其他声称支持路径补适用验证 | 只有同一最终源码/产物已有完全匹配证据时可引用；否则重跑，不以早期Spike或不同APK作最终结果 |

K11提交Q02.production/Q03.production原始时间线与资源样本后才能过G1；K16汇总三个阶段证据、补最终候选包结果后才可给Q02/Q03父ID完整PASS。

## 2. 架构与构建 A

| ID | 场景/操作 | 必须断言 | 证据 |
| --- | --- | --- | --- |
| A01 | 读取当前owner链与基线 | engine/facade/supervisor/worker/lease/取消/释放/进程逐一定位；不把skeleton当生产 | owner-map、符号、revision |
| A02 | 新模块依赖图 | feature只依赖contract/designsystem；runtime无app/engine/native/UI；无环 | 静态gate正反fixture |
| A03 | 平台类型/实现泄漏 | contract/runtime无Android/Compose/Room/OkHttp/JNI；JSON库不泄漏公开API | import/公开签名检查 |
| A04 | 注入遗漏和错类型 | 无Any回退、无service locator；真实依赖缺失不能默默生成默认executor | 编译/构造测试 |
| A05 | 根状态/Host差量 | MainUiState无新增Agent业务字段；Host无新增Agent能力；旧onOpenAgent语义未改 | diff审查+导航测试 |
| A06 | owner构造与生产调用 | 每作用域唯一AgentRunOwner；用同生产engine/facade；没有装饰性空接口 | composition测试+源码追踪 |
| A07 | 验证脚本覆盖新模块 | AgentTests/FullTests/changed选择和结果聚合实际包含新模块；0tests失败 | 命令、task清单、XML计数 |
| A08 | 预算和清理审计 | 文件职责不扩成巨型Manager；临时adapter有生产调用者/退出条件；未放宽旧规则 | diff/LOC审查、ADR例外 |

## 3. 状态、幂等与预算 T

| ID | 场景/操作 | 必须断言 | 证据 |
| --- | --- | --- | --- |
| T01 | 合法模型→工具→结果→final | 状态顺序合法、真实产物、恰好一个终态 | deterministic transcript |
| T02 | 同commandId并发重试 | 同内容同结果；不同内容冲突；工具只执行一次 | 并发测试≥100轮 |
| T03 | 重复collector/旋转页面 | observe不start、不生成新run | 观察计数/UI测试 |
| T04 | 旧epoch/attempt晚到 | 无法更新新run/step/terminal，旧停止不影响替代请求 | property/竞争测试 |
| T05 | 暂停与终态继续 | 模型/可取消工具停止后PAUSED；继续创建新run，不复活旧终态 | 状态机测试 |
| T06 | 批准/拒绝重复点击 | 审批仅消费一次；拒绝产生一个DENIED结果；无副作用 | broker+loop测试 |
| T07 | 预算边界 | 12attempt/8tool/1repair/15min等边界准确；暂停原因明确 | fake clock+表驱动 |
| T08 | 等资源超时 | 120s后PAUSED；未持native lease；不误停别人的请求 | virtual time+集成 |
| T09 | 上下文不足 | 保留tool pairing；不静默截断；CONTEXT_LIMIT可解释 | tokenizer/估算fixture |
| T10 | 队列上限 | 1 active/5 waiting；第6个waiting得到明确冲突；取消queued不启动模型 | 并发命令测试 |
| T11 | 异常/collector取消/关闭/洪泛 | CancellationException传播正确；64普通命令上限；取消优先不饥饿；无fire-and-forget/双终态，关闭拒新命令 | 故障注入/协程测试 |
| T12 | 幂等与revision顺序 | 同内容commandId先返回原结果；创建无旧revision；task/run revision各自校验；跨重启结果相同 | 内存+Room并发测试 |
| T13 | 回答/继续/槽释放 | questionId只能回答一次且留同run；暂停后继续为新ordinal/旧run SUPERSEDED；等待保留活动槽且无native lease，PAUSED释放槽 | 表驱动+队列测试 |

## 4. 模型协议 P

| ID | 场景/操作 | 必须断言 | 证据 |
| --- | --- | --- | --- |
| P01 | 当前模型能力源审 | tools字段存在不等于本地FC；MNN/GGUF/Responses标识准确 | 源码表+模型fingerprint |
| P02 | 合法prompted三类envelope | tool/final/ask_user解码正确；host生成invocation | JSON fixture |
| P03 | partial/截断/Error/取消 | 即使partial看似完整也不执行；只有成功Done后接纳 | streaming fixture |
| P04 | 非法JSON | 重复key、双root、注释、单引号、尾逗号、NaN、非法UTF/深度拒绝 | adversarial fixture |
| P05 | 前后缀/Markdown/自然语言JSON | 不搜索子串或猜调用，executor计数0 | fixture |
| P06 | 参数schema | 未知/额外字段、错误type/bounds/enum、unsupported schema不执行 | validator表驱动 |
| P07 | reasoning或tool result里的动作 | parser只读visible assistant完成输出，不读reasoning/资料/工具文本 | 通道隔离测试 |
| P08 | 多toolcalls | 不执行任意子集；所有provider calls被合法配对NOT_EXECUTED或暂停 | native provider fixture |
| P09 | 格式修复 | 每run最多一次；同预算；二次错转暂停，无无限自动修复 | attempt计数 |
| P10 | call-result配对 | 每个accepted call恰有一个result，拒绝/错误也成对 | transcript checker |
| P11 | resume/compaction | 不产生孤立call/result，opaque provider state不跨provider复用 | 历史fixture |
| P12 | final伪产物 | 未登记/越权ArtifactId不呈现可点击产物、不假报存在 | final验证 |
| P13 | 大输出和输入 | 64KiB/32KiB/深度16限制、bounded buffer，无OOM或无界日志 | 边界/压力测试 |
| P14 | NATIVE/PROMPTED/NONE | Responses须显式启用；本地prompted不冒充native；NONE不阻普通推理入口 | 真实模型+UI详情 |
| P15 | 本地消息映射 | 显式SYSTEM/USER观察/ASSISTANT envelope与6.5.1一致；tools/exchanges为空仍有完整call-result，拒绝/用户回答配对 | messagesJson golden+MNN/GGUF真实闭环 |

## 5. 资源、取消与后台 O

| ID | 场景/操作 | 必须断言 | 证据 |
| --- | --- | --- | --- |
| O01 | Agent模型子请求接线 | 同生产engine→facade→owner；不走LocalApiRuntime聊天UI hooks | instrumentation/trace |
| O02 | 聊天/API生成中启动Agent | acquireExclusiveLifecycleLease(false)等待≤120s；context透传同lease；取消acquire边界无泄露；不提交调参候选/不抢占 | JVM+真机并发 |
| O03 | Agent中用户切模型/调参 | attempt结束后检测binding/epoch；不默跟新模型，不覆盖committed参数 | 真机/竞争测试 |
| O04 | 跨task/session/cache | 独立conversation/session/role，禁持久缓存或证明key隔离，无历史串味 | 双色秘密canary测试 |
| O05 | 精确stop与旧stop | 只停本attempt；不调cancelAllActive；旧通知/旧回调不影响chat/image | 交叉取消测试 |
| O06 | 模型等待/执行取消 | ack/dispatch/native停止/lease释放分别记录；停止前不放下个native | 真实时间线 |
| O07 | 工具取消/已提交写 | 不可撤销副作用如实保留；未提交文件可安全清理；不伪造CANCELLED | 工具fault测试 |
| O08 | stop超时/延迟退出 | 应用scope retention保留同lease引用；保持STOPPING，不误杀；exact请求/worker退出证明后恰好release；死进程恢复先核查worker | 慢backend/引用生命周期/故障测试 |
| O09 | image memory handoff | 旧image owner仍lease→unload→worker exit→release；V1无本地image续跑假承诺 | 旧image回归 |
| O10 | MCP取消/网络断开 | 本地request停止与远端是否停止分开；未知副作用UNKNOWN | server fixture |
| O11 | FGS启动/退出/锁屏 | 合法用途/通知，页面离开不误停，闲置无native lease，启动失败可恢复 | Android适用版本实测 |
| O12 | 进程死亡/force-stop/重启 | 旧审批过期、非终态核账、不静默续跑、无孤儿worker/lease | 至少3类中断各1次 |
| O13 | 云attempt/协议terminal/取消 | 子Job只取消自己Call；无cancelAll；严格策略验证成功terminal；缺DONE/finish_reason/message_stop及length/max_tokens即使JSON完整也不执行；旧attempt不能提交，远端计费不作承诺 | 各协议截断fixture+Responses真实请求+交叉取消 |

## 6. 安全、隐私与审批 S

| ID | 场景/操作 | 必须断言 | 证据 |
| --- | --- | --- | --- |
| S01 | 未授权/拒绝写入 | executor未执行，文件/网络副作用0 | 工具spy+真实文件 |
| S02 | 审批后改参数/default | frozen hash不匹配拒绝；不重新拼模型参数 | TOCTOU测试 |
| S03 | TTL/schema/policy/epoch变化 | 旧审批无效，不消费、不执行 | 参数化测试 |
| S04 | 双击/重放批准 | one-shot CAS，invocation执行≤1次 | 并发≥100轮 |
| S05 | 路径逃逸/软链/前缀碰撞 | 不读写task root外；真实文件前后hash不变 | filesystem fixture |
| S06 | 覆盖期间文件变更 | expectedOldHash失效，原文件不被覆盖 | race fixture |
| S07 | 恶意Skill/ZIP | zip slip/bomb/重复路径/脚本/声明提权不生效 | 至少24条攻击语料总集 |
| S08 | 恶意MCP注入/schema替换 | hint不授予权限、hash漂移重新确认、namespace不覆盖 | MCP fixture |
| S09 | 严格离线 | 禁Agent cloud/remoteMCP网络；独立网络spy/断网实测0连接 | Android离线+transport测试 |
| S10 | 云与MCP实际payload egress | 云preview覆盖Skill/工具结果/摘要/检索/初始资料；MCP tools/call实际arguments按server/tool/origin/source校验；未授权source/LOCAL_ONLY/SECRET阻断，跨server不串凭据 | 云/MCP两组序列化payload capture脱敏 |
| S11 | HTTPS/DNS/跨server凭据 | 默认禁明文/自动redirect；DNS rebinding、IPv4-mapped/private/link-local/代理不旁路；校验绑定实际连接，跨origin不带认证 | 确定性DNS/连接/redirect fixture |
| S12 | 密钥/敏感日志 | API key不在DB/export/log/notification；错误正文脱敏 | 自动canary scan |
| S13 | audit写权限 | tool/Skill/MCP不可直接改journal；合法事件sequence不冲突 | 类型/权限+事务测试 |
| S14 | 运行中切离线/撤授权 | 在途自己的请求被取消；新调用拒绝；不声称召回已外发数据 | policy race |
| S15 | 通知隐私/陈旧action | 锁屏通用内容；runId/epoch核对；不泄露目标/文件 | 通知截图/intent测试 |
| S16 | 凭据/系统备份/导出 | 原云Keystore/prefs排除保留；Agent文件不自动备份；export无grants/secrets | XML检查+导出包扫描 |
| S17 | 目录/SAF树导入 | 与ZIP同等文件数/层级/总字节限制；循环document ID、非普通文件、软链、规范化重复路径拒绝；只读快照 | provider+目录fixture |

注入语料全部执行权限/协议断言，不只让模型回答“我拒绝”。语料通过不代表对任意提示注入的数学保证。

## 7. 数据、迁移与产物 D

| ID | 场景/操作 | 必须断言 | 证据 |
| --- | --- | --- | --- |
| D01 | 当前DB版本 | 从实际@Database读取N，本次基线27；不盲改固定版本 | source/schema |
| D02 | N→N+1迁移 | 旧聊天/助手/关联/附件数据完整；新增表/索引正确 | MigrationTestHelper |
| D03 | 支持的历史版本升级 | 至少最近N及仍支持的历史fixture到新版本，保留原数据 | 真实schema fixture |
| D04 | 事务回滚 | 状态/approval/invocation/event同事务，不半提交 | Room故障注入 |
| D05 | sequence/revision/幂等冲突 | 唯一约束成立；CAS失败不覆盖新状态 | 并发Room测试 |
| D06 | 正常产物提交 | bytes/hash/大小/DB引用一致，真实可预览 | 文件+DB断言 |
| D07 | 写入前/中/后崩溃 | staging可清理/恢复，原产物不损坏 | crash hook |
| D08 | rename后/DB前崩溃 | commitId/hash核账，只认一次提交不重执行工具 | crash hook |
| D09 | DB后/清理前崩溃 | 已提交产物保留，staging清理幂等 | crash hook |
| D10 | RUNNING invocation恢复 | 只读可重试；不确定副作用UNKNOWN，不静默重放 | 恢复fixture |
| D11 | 审批等待时重启 | 旧审批过期，继续需重新核对参数/资源 | DB+UI |
| D12 | 磁盘/权限/损坏 | 明确STORAGE错误，不清库/造空文件，不删无关资料 | storage fault |
| D13 | 导出包内容 | manifest版本/hash正确，选中产物完整，无credential/grant | SAF导出扫描 |
| D14 | 导出后导入 | 新ID、archived状态、内容hash一致，不续跑 | round-trip |
| D15 | 恶意导入/未知版本 | 拒路径/大小/entry/zip比/未知major，不提交半包 | archive fixture |
| D16 | 删除活动任务 | 先准确停止→TOMBSTONED→文件清理，不删其他task | 删除race |
| D17 | GC/配额 | 不删active/引用产物；孤儿staging可重试；空间不足安全 | fake clock+disk测试 |
| D18 | 事件分页/大任务 | cursor稳定、无漏/重复、查询有界；重组不加载全历史 | 10,000事件fixture |
| D19 | rollback与升级失败 | 旧APK降级不靠清库；前向修复/停Agent保数据方案可执行 | 升级演练/文档审查 |
| D20 | 持久事实完备 | attempts/command结果/grants/commit journal可恢复；IMPORTED_ARCHIVED不进执行查询；payload引用hash/size和文件一致 | schema+重启+孤儿引用fixture |

## 8. UI、导航与无障碍 U

| ID | 场景/操作 | 必须断言 | 证据 |
| --- | --- | --- | --- |
| U01 | 打开侧栏 | 图片下紧邻Agent，旧最近/图片行为保留 | 真实截图+语义树 |
| U02 | 原调参入口 | AppTab.AGENT/onOpenAgent仍智能调参 | 交互回归 |
| U03 | 抽屉/页面返回 | 先关drawer；sheet→detail→list→来源，返回不取消任务 | UI自动化/录屏 |
| U04 | 旋转/重建/通知打开 | 保留taskId/草稿/定位；不重复start；通知进正确任务 | lifecycle UI |
| U05 | 状态隔离 | Agent新建/运行不写chat history/selected chat model/image UI | DB/state断言 |
| U06 | 无模型/空任务 | 明确下一步输入/模型管理入口，无假执行记录 | 渲染 |
| U07 | 模型生成/工具执行 | 状态区别明确；stop可触达；无raw reasoning默认展示 | 渲染/交互 |
| U08 | 审批/拒绝/过期 | 范围/diff/原因可读；长参数不遮按钮 | UI+无障碍 |
| U09 | 长中文/代码/表格/输出 | 不越界，活动可折叠；产物引用有效 | 固定fixture截图 |
| U10 | IME/多行/附件失败 | 输入与send/stop不被覆盖，重试/移除附件可用 | 键盘截图/交互 |
| U11 | 用户离开底部 | 不强制滚回，新内容可一键跳最新 | UI测试 |
| U12 | 断网/中断/恢复/STOPPING | 错误可恢复，不伪显示完成或已停 | 渲染+owner关联 |
| U13 | 浅深色/大字体/TalkBack | 1.0/1.5/2.0无关键裁剪，48dp触摸/语义/焦点顺序 | 真机+语义审查 |
| U14 | 两轮视觉/旧页对比 | 同McaTheme/图标/密度；旧chat/image/tuning无顺带换肤 | before/after两轮问题表 |

## 9. Skills和MCP X

| ID | 场景/操作 | 必须断言 | 证据 |
| --- | --- | --- | --- |
| X01 | 合法SKILL.md | 支持字段/manifest版本/hash/来源可查 | parser fixture+UI |
| X02 | 未支持YAML/脚本 | 明确不支持或仅预览，不执行script/安装runtime | fixture |
| X03 | Skill工具交集 | allowed-tools不能扩用户授权；禁用工具不被prompt绕过 | loop测试 |
| X04 | Skill更新/撤销 | 更新不换当前snapshot；停用/卸载立即禁止新调用、使审批失效，安全处理在途和迟到结果；历史引用不删除 | 竞争测试 |
| X05 | 真实Skill场景 | 纯文档Skill完成文件任务；依赖shell的Skill显示限制 | ARM64端到端 |
| X06 | MCP initialize/list/call | initialize→initialized/headers/version、tools capability/listChanged、JSON与SSE、ID/session/无session/202和分页边界符合合同 | wire protocol fixture |
| X07 | server namespace | 重名/恶意名字不覆盖内置或别的server | registry测试 |
| X08 | 配置/重启/删除 | server保留但默认断开，token不进manifest；删除取消自己Call/本机密钥引用，不假报远端revoke | Room+重启+撤销race |
| X09 | tools_changed/schema drift | 新schema hash使旧审批失效，unsupported单工具有明确原因 | server fixture |
| X10 | 断线/HTTP错误重连 | 有session的404重握手，无session的404报endpoint错；401/403无OAuth/重试循环；不重放call，旧session/晚到id不能推进；DELETE405不假报远端销毁 | 断线/HTTP fixture |
| X11 | cancel/timeout | 本地结束与远端未知分开，UNKNOWN不冒充取消成功 | 慢server |
| X12 | 大响应/错误/未支持能力 | 超限终止；分页不完整不报完整；JSON-RPC error/isError区分；未知server request拒绝、未知通知有界忽略；text/structuredContent/非文本block按合同映射，resource link不自动抓取 | wire边界fixture |
| X13 | 真实MCPHTTP | 一个受控server端到端经过同broker/egress | 真机+server trace |
| X14 | 兼容声明准确 | 只报已支持transport/schema/version；stdio/DSH未实现不宣称 | 文档/设置/功能对照 |

## 10. 旧功能回归 R

| ID | 场景 | 最低范围 |
| --- | --- | --- |
| R01 | 聊天UI | 本地/云流式、多轮、附件/工具适用、编辑/重试/取消/错误、历史 |
| R02 | 聊天owner/API | ChatExecutionOwnerTest、RuntimeAdmissionTransactionTest及相关身份/终态测试；真实API取消/错误 |
| R03 | 图片 | 原UI/API owner、busy/409、内存handoff、worker exit、图库/导出、取消 |
| R04 | 调参/benchmark | 旧入口/参数事务/profile状态/暂停取消，无Agent字段串写 |
| R05 | authenticated Local API | 生产MainActivity启用后的认证、路由/流/错误/断连/取消保原合同；不新增Agent API |
| R06 | 模型生命周期 | 加载/卸载/切换、现有profile、模型目录/推荐/下载与导入不被Agent影响 |
| R07 | 数据/设置/凭据 | 原数据库fixture/assistant/知识/模型配置/云key加密和备份排除不变 |
| R08 | 原视觉和导航 | chat/image/tuning固定状态、背景、滚动、键盘、返回；只接受新增Agent侧栏行 |

根据实际触及范围使用已有测试和真实端到端，不能只运行测试名包含Agent的集合。

## 11. 模型质量、性能和可维护性 Q

### 11.1 Q01–Q06

| ID | 测量 | 初始目标 | 放行语义 |
| --- | --- | --- | --- |
| Q01 | 固定文件任务两阶段 | G0=list→read→search→read→final（只读fixture）；V1=list→read→search→ASK-write→final；MNN/GGUF每组各10次，云单列 | 指定参考模型每类≥8/10任务成功；安全不变量10/10；G0不替代V1产物/审批验收 |
| Q02 | 精确取消 | 每本地后端≥10次，含prefill/decode/等待 | ack P95≤200ms；命令dispatch P95≤500ms；实际native stop P95≤5s；stop后lease release P95≤2s |
| Q03 | 重复运行/泄漏 | 同配置10次任务+取消+离页 | 无孤儿worker/重复session；资源回稳、没有单调增长趋势；列Java/native/worker内存 |
| Q04 | 真实中断恢复 | 模型中/写提交中/审批中各至少1次 | 3/3正确核账；副作用重复0；旧审批消费0 |
| Q05 | UI/启动/空闲负担 | 同设备/热状态/构建基线，warm≥20、cold≥10 | 旧入口P95回归≤max(10%,50ms)目标；Agent未用不加载模型/连接网络 |
| Q06 | 工具/事件/磁盘 | 10,000事件、边界输出、配额 | 主线程无解析/磁盘IO，查询有界；token写库合并；无OOM/ANR和无界缓存 |

Q02是参考设备初始性能目标，不能对所有未知硬件作统一承诺；每个runtime在P0记录分布/最坏样本。若5秒目标未满足，不释放仍在执行的native资源，也不屏蔽用户入口；应修复/报告具体后端限制，并在候选版审查中决定是否能宣称“准确快速停止”。“10/10安全”不因模型成功率8/10而放宽。

质量统计记录模型资产hash、运行后端、量化、有效参数、context、temperature、seed（若可控）、热状态和repair次数。未成功样本全部归因：模型未规划、格式错、权限拒绝、runtime错误、资源等待等，不能从分母删掉失败。

### 11.2 S级目标评分的使用边界

仅在有实现/渲染/真实运行证据后评分。建议权重：架构20、ownership/取消15、状态/事务15、安全15、数据兼容10、验证证据10、UX5、维护与回退10。每项绑定验收ID和缺口，不以文档篇幅或自评代替证据。

任何越权、数据丢失、旧任务串写、全局误停、重复副作用、认证绕过、伪造PASS、旧功能删减都是硬失败；总分再高也不能叫S级。当前文档不打生产分，不宣布10/10。

## 12. 证据记录格式

建议目录（运行时才创建，不预填PASS）：

~~~text
docs/proposals/agent-v1/evidence/<revision-short>/<run-id>/
  manifest.json
  acceptance.json
  logs/
  junit/
  device/
  screenshots/
  model-capabilities.json
  known-limitations.md
~~~

真实APK、私人数据和模型文件放适用的本地/受控产物位置，不直接提交仓库；manifest只引用位置和hash。记录脏子模块patch hash，不能只记父仓库SHA。

~~~json
{
  "evidenceSchemaVersion": 1,
  "taskCard": "K04",
  "caseId": "O06",
  "subcaseId": "nativeStopAndLeaseRelease",
  "status": "NOT_RUN",
  "evidenceKind": "ANDROID_REAL",
  "source": {
    "revision": "<actual-head>",
    "worktreePatchSha256": "<actual-patch-hash>",
    "submodules": [
      {"path": "<relative-path>", "revision": "<sha>", "patchSha256": "<hash>"}
    ]
  },
  "artifact": {"apkSha256": "<actual-hash>", "buildType": "<actual>"},
  "environment": {
    "jdk": "<actual>",
    "device": "<diagnostic-device-info>",
    "androidApi": 0,
    "runtime": "<MNN-or-GGUF-or-cloud>",
    "modelFingerprint": "<secret-free-fingerprint>",
    "effectiveParamsFingerprint": "<hash>"
  },
  "execution": {
    "command": "<secret-free-command>",
    "startedAt": "<iso8601>",
    "matchedTests": 0,
    "executedTests": 0,
    "failures": 0,
    "skips": 0,
    "rawEvidence": []
  },
  "observations": [],
  "limitations": ["模板，尚未执行"]
}
~~~

上面的0和占位符是模板，绝不能作为实际结果提交。每个PASS必须有真实时间、命令、测试数/步骤、原始日志或截图及适用解释。

## 13. 可执行验证入口与诚实边界

现有受控入口（先读脚本当前参数）：

~~~powershell
python tools/architecture/check_architecture_rules.py
.\scripts\verify-code.ps1 -Tests '*ActualTestName'
.\scripts\verify-code.ps1 -FeatureTests
.\scripts\verify-code.ps1 -FullTests
.\scripts\build.ps1
~~~

- -FeatureTests 目前仅chat，不能代替Agent feature。
- -AgentTests 是执行包要求未来加入的参数，当前不能宣称已有或已运行。
- -Changed 目前会遗漏未跟踪文件/新模块；必须核对实际选择和报告。
- 新模块Android library的预期任务为testDebugUnitTest，具体task existence在K01核对；instrumentation命令走现有受控构建锁/设备入口，不在同build目录并发Gradle。
- JDK21、实际SDK/ABI合同、子模块patch、模型资产都纳入构建来源。
- 聚合final gate会写报告，本次文档任务不运行；未来运行前读参数并保留旧证据。

## 14. 本轮实际验证范围

本轮仅做源码/评审读取、文档一致性、链接与diff审查。未运行Gradle、native编译、模型推理、数据库迁移或Android UI。所有上述生产/性能/升级验收均为NOT_RUN，后续必须按当前revision重新执行。
