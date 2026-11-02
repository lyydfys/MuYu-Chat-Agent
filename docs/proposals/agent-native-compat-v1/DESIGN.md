# 原生 Agent 详细架构、算法与失败语义

本文件是 [总方案](README.md) 的实施设计。所有“新增/拟定”路径、类型和表尚未实现。实际开发从当前源码增量演进；不要按本文一次性创建全部接口和目录。third-party 来源与能力边界见 [对照验收](PARITY_ACCEPTANCE.md)。

## 1. 先修的已知问题

| ID | 当前证据 | 影响 | 必须采用的修复方向 |
| --- | --- | --- | --- |
| B01 | `AgentCommandResult.Accepted` 不返回创建的 taskId；`AgentWorkflowLauncher` 与 Workspace VM 观察 unseen ID + goal | 相同目标并发、命令重放、分页时可能误关联任务 | 版本化创建回执；commandId→taskId/workspaceId 持久映射，与创建同事务；删除猜测路径 |
| B02 | `MainRuntimeAssembly` 把 MCP 传空且生产 catalog 只包 memory；Skill snapshot 在 runtime 构造时生成 | 管理成功不等于能调用；启停对后续 run 可能不生效 | 每 run 装配不可变 catalog；固定包/Skill/MCP/权限 revision；关闭 session 的责任明确 |
| B03 | cloud mapper 标 NATIVE，实际请求 `tools=emptyList()` 且结果只有 visibleText | 误报原生工具能力 | 先修声明；后增加结构化 calls/result 与真实 wire adapter；旧 prompted 独立保留 |
| B04 | `AgentMemoryContracts` 管理口仅 list；选择保存在页面；createTask/policy 未传选中空间 | 用户从普通 UI 无法完整使用已有记忆运行能力 | 管理命令、选择状态和任务创建事务接通；服务端校验空间，不依赖 chip |
| B05 | `AgentMemoryStore.search` 先固定排序取页，再 rank；宣称64候选却可一次加入256 | 后页相关记忆不可达，上界语义失真 | query-driven 候选索引；扫描预算与 topK 分开；全库晚页召回测试 |
| B06 | `RoomAgentMemoryPersistence.deleteItemWithChunks` 先按 memoryId 删除 chunks，再按 spaceId+memoryId 删除 item | A空间配B条目ID可能删掉B的 chunks | 同事务先校验所有权；所有子表删除带空间/父条目约束；错误归属零变化 |
| B07 | memory 用伪逻辑路径 `memory` 进入 workspace scope 模型 | 权限摘要/审批无法准确表达真实资源集合 | versioned typed ResourceScope，旧命令显式 upcast；权限绑定真实空间与版本 |
| B08 | `AgentMemoryRoomMigrationTest` 插入 fixture 未写 schema 要求的非空 revision | 设备迁移测试可能在准备数据时失败 | 修 fixture 后真实迁移；本轮未运行，不写 PASS |
| B09 | Agent Job 仍 parent 到传入的 VM scope；模型资源根也由既有启动链持有 | 直接加 Worker 会失去运行根或复制 runtime | N21 明确迁移实际资源根；在通过前只开放前台手动执行，后台能力状态透明 |

这些问题来自静态审阅。B06/B08 等必须用最小行为测试确认并修复；不得扩大为未经验证的全局故障结论。实施前复读相应文件，防止并行修复后重复修改。

## 2. 模块和职责分配

| 现有位置 | 保留职责 | 增量职责 |
| --- | --- | --- |
| `core/agent-contract` | 版本化纯 DTO、错误、命令、查询、模型/工具能力 | 回合结果、资源 scope、创建回执、workflow 定义及窄命令；按主题拆文件 |
| `core/agent-runtime` | run owner、预算、协议、上下文、调用账本端口 | 统一调用流水线、批调用计划、图验证与执行策略；仍无 Android/Room/HTTP |
| `app/.../agent/inference` | MCA 本地/云模型适配、lease、exact stop | 结构化 wire 映射、最终 context 准入、辅助模型窄适配 |
| `app/.../agent/tools` | 真实内置工具 | 文件/文档/网络等实现；平台操作委托 Android bridge |
| `app/.../agent/extensions` | Skills、MCP、toolpkg 存储 | 安装事务、host bridge、兼容转换、隔离进程客户端 |
| `app/.../agent/memory` | Agent 自有记忆与 Room persistence | 索引、提取、链接、整理、删除作业；不直写其他领域表 |
| `app/.../agent/workflow` | 旧 recipe 兼容入口 | 图存储、trigger inbox、调度平台适配；纯执行逻辑留 runtime |
| `app/.../agent/platform` | FGS、通知、恢复 | 权限状态、隔离服务、可选终端/设备通道、Worker；不持页面 |
| `app/.../presentation` | SAF/界面契约适配 | 记忆和流程 CRUD/导入预览；不能产生另一个业务 writer |
| `feature/agent-workspace` | 页面状态与投影 | 管理/编辑/运行可视化；无 executor/DAO/模型/平台桥 |

初期不新增大量 Gradle 模块。若 QuickJS JNI/隔离服务需要独立编译，N10 用 ADR 定义最小 `core:agent-script-runtime` 实现模块，feature 仍不得直接依赖；QuickJS C API/JNI 和 Kotlin bridge 分离。具体依赖、版本、ABI、来源和校验和在实施时锁定，不凭文档任填版本。

## 3. 共享身份、事务与资源 scope

### 3.1 稳定身份

每条执行链携带：taskId、runId、runEpoch、processInstanceId、attemptId；工具增加 logicalInvocationId、dispatchAttempt、parentInvocationId；workflow 增加 definitionHash、nodeId、nodeAttempt；包增加 packageId/version/contentHash/exportName。不要复用 UI row ID 或用目标文本作业务关联。

- `logicalInvocationId` 代表一件动作。进程重启或通信重试不生成新 ID 掩盖未知副作用。
- `dispatchAttempt` 仅记录该动作的安全派发尝试；允许重试必须由 effect/recovery 策略决定。
- engine 实际请求继续携带已有 requestId/epoch/loadIncarnation/runtime；Agent ID 不能替代 engine 身份。
- 平台 token/session 加进程/服务实例随机标识，不用可重置计数器判断陈旧回调。

### 3.2 创建任务回执

新增兼容字段或专用 typed receipt：`creationCommandId, taskId, workspaceId, appliedRevision, idempotentReplay`。命令结果账本与 task 写入同事务。旧记录允许通过账本中明确保存的关联恢复；无法恢复时返回 `TASK_ANCHOR_UNAVAILABLE`，不猜第一个相同 goal。

Workflow slot/inbox 写入与 create 关联必须在同一事务边界，或使用 durable outbox + 幂等 owner 命令。Room 事务里禁止调用网络、native、跨进程和用户审批。

### 3.3 ResourceScope v2

设计为可组合的封闭资源类型；以下是数据形状，不要求一个巨型接口：

```text
Workspace(scopeId, relativePaths, accessMode, contentPreconditions)
Memory(spaceIds, itemIds?, revisions, accessMode)
Network(canonicalOrigins, methods, dataProvenance, bodyHash)
Device(operation, targetPackage?, selectorHash?, userVisibleSummary)
Process(runtimeId, cwdHandle, argvHash, environmentPolicy)
PackageResource(packageContentHash, relativePaths)
Composite(requiredScopes)
```

命令 schemaVersion=2，审批绑定 canonical args、schema、scope fingerprint、内容前置版本、runEpoch、policyVersion、packageHash 和有效期。V1 workspace 命令按已知规则 upcast；旧 memory 伪路径命令恢复后失效并重新申请真实 scope，不能猜测扩大授权。

原单个 `sideEffect` enum 不能表达“读私人文件并发网络”。新增 `effects: Set<EffectKind>`，保留旧 enum 读取适配。权限看效果集合与数据来源；工具声明和 MCP annotations 只能增加提示，不能自行降低可信策略。

Process 的摘要不能代替进程隔离。执行合同还必须绑定实际 fs/FD、network、UID/权限、环境变量、可执行内容 hash、进程组 owner 和资源限额。与宿主同 UID 的任意 shell 可直接读宿主文件、自行建 socket，因此即使 argv 已审批也不能兑现来源级 egress 或 LOCAL_ONLY。没有经真实探针证明的隔离时，任意 shell/Node/原生命令只能作为用户明确开启的高信任 broad capability，禁止进入 LOCAL_ONLY 与无人值守执行；界面说明其真实访问面。不能靠黑名单命令或一次参数检查伪装强边界。

### 3.4 时间

持续时间/超时用注入 monotonic clock；落库与日历触发用 wall clock + zoneId。重启后不能比较旧进程 monotonic 值。审批跨重启默认过期；定时计划记录 DST、时间回拨和 missed-slot 策略。

## 4. 工具调用内核

### 4.1 统一模型回合

增加 versioned `AgentModelTurn`：完整 text parts、结构化 calls（providerCallId/name/完整arguments）、finishReason、usage、terminalProof。内部 canonical transcript 支持 text/tool-call/tool-result/问题/回答；协议 wire 独立序列化。

- `PROMPTED_V1` 的严格单 JSON envelope 保留，不给旧模型突然切换批量格式。
- `PROMPTED_V2` 可输出 calls 数组，需显式能力与独立严格 parser，不能接受半截 JSON 后执行。
- 真正原生工具协议按 provider 适配。先用 MCA 已有三种 API 格式建立独立测试路径；无需为每个营销 provider 复制完整客户端。
- 在 Chat Completions、Responses、Anthropic 等接入实施前读取其官方当前协议；明确“可执行工具调用终态”和“最终文本终态”分别是什么，不能把工具回合当空文本失败，也不能把任意 EOF 当成功。
- tool schema 必须实际进入 wire；tool-call id、参数与 result pairing 必须从最后序列化请求和原始受控测试流验证，不能只断言 mapper 中间对象。
- native 与 prompted 的降级必须记录原因，且只在未派发副作用前发生。不能把模型拒绝/参数错误静默换协议重复写入。

流式 delta 仅用于展示/重建当前回合。完整终态、完整参数和 schema 校验前不执行工具，不按 `<tool` 前缀或 partial JSON 提前派发。

### 4.2 唯一调用流水线

从现有 `handleToolCall`/授权逻辑增量提取纯 Kotlin `AgentToolInvocationPipeline`（拟定名），表达一个真实职责：

```text
提议（model / workflow / script-host）
→ 找冻结 catalog 中的 descriptor
→ 严格 schema / 类型 / 大小检查
→ 归一化参数、解析 scope 和内容前置条件
→ 记录 PROPOSED / VALIDATED + 稳定 logicalInvocationId
→ 冻结命令及来源链
→ 权限策略（任务读取授权 / 单次审批 / workflow 窄授权）
→ 持久等待；释放不再需要的模型 lease
→ 用户/策略决议后再次验证 epoch、版本、资源与撤销
→ CAS 消费授权并记录 dispatch intent
→ 唯一 executor 派发
→ receipt/结果/产物提交 + terminal ledger
→ 配对 observation，交给原执行策略
```

M0 不顺带重写现有 owner。先让原 loop 走新流水线并证明行为等价，再允许 workflow/脚本调用。运行入口拿到的是窄 `propose/await/cancel` 能力，拿不到任意 `AgentToolExecutor`。

事务提交失败后禁止发送外部动作；外部动作已发送但结果提交失败返回 UNKNOWN 并核账。`cancel`、超时、断网不代表外部动作未发生。

这是新增目标，不是当前源码已具备的原子性：现有 broker 授权消费与 owner 的 approval-resume 提交存在分离边界。N03 必须把 approval consumed、不可变 decision event、invocation 状态、dispatch outbox、run/frame/node 关联纳入同一仓储事务；dispatch worker 只接受已提交 outbox，不直接消费内存中的批准回调。不能在 DB 事务内执行工具。

| 崩溃位置 | 恢复事实与动作 |
| --- | --- |
| 审批前/事务回滚 | 无提交 intent；本动作未被本派发器执行，旧未消费授权按 epoch/有效期处理 |
| 旧数据 CONSUMED，缺 dispatch 关联 | 先查完整旧日志；仅有可证明未派发的证据才记 NOT_EXECUTED 并重新请求审批；证据不完整转 UNKNOWN，不发补偿性新授权 |
| 新事务已提交，outbox 未送 | 派发器以 invocation ID 与 epoch CAS 认领；已提交授权只能供这一动作使用 |
| intent 已送，无 receipt | 按效果核账；读取可安全重试，未知外部副作用不自动重发 |
| receipt 已持久化，result 未提交 | 按同 receipt 幂等提交配对 observation，不能再执行 |

授权 CONSUMED 既不证明工具已执行，也不允许重新签一张无约束票。测试必须在 consume→run commit→dispatch→receipt→result 各边界注入死亡，覆盖旧日志 upcast 与新事务路径。

### 4.3 批调用与并发

V1 保持串行，V2/native 批调用完整落账后再规划。初始默认并发上限3，仅满足全部条件的调用并行：可信 READ_ONLY、无网络外发、无相同可变资源、无父子数据依赖、执行器声明可取消且受配额约束。其他调用按原顺序串行。

每个 call 有独立审批与终态；结果按模型原 ordinal 回传，不按完成时间。批中一项拒绝产生一项 DENIED，不丢 pairing。用户取消时取消全部属于此批的子调用，等待各自 stop proof；不使用全局 stop。

### 4.4 效果与恢复矩阵

| 效果 | 重启策略 | 结果丢失处理 |
| --- | --- | --- |
| 纯计算/稳定快照读取 | 可从相同输入重放 | 检验输入快照 hash |
| 私有文件原子写 | 临时文件→fsync适用层→原子提交→账本核对 | 依据目标hash/commit记录核账，冲突转审查 |
| 支持幂等键的远端API | 使用同一logical ID查询/重试 | 保存 provider receipt；不得换ID重发 |
| 点击、短信发送、shell写外部文件等未知效果 | 不自动重放 | `NEEDS_REVIEW`，展示已知派发与未知结果 |
| 模型生成 | 原 attempt 记中断，策略允许时新 attempt | 不把半截输出当工具提议 |

### 4.5 停止

保持现有 typed stop settlement：`NotDispatched/Released` 才可释放；`StoppingUnknown/TimedOut/Unknown` 留 fence。新增脚本/终端/设备 host action 各自提供受限 stop receipt。Kotlin Job 取消只说明协程状态。

运行取消记录四段：用户反馈、请求送达、实际执行静止、资源释放。未知 native 停止保持 STOPPING；进程重启后按隔离进程死亡/engine proof 核账，不用“registry为空”推导已停。

### 4.6 工具与平台能力分层

| 工具族 | 第一层可用行为 | 更高权限/依赖行为 | 验收重点 |
| --- | --- | --- | --- |
| 工作区/SAF | list/read/search/write/edit/move/delete、导入导出、压缩 | 用户授权的目录/document provider | 路径规范化、symlink/race、覆盖预览、原子提交、配额 |
| 网络 | HTTP/下载/上传、网页提取 | 用户选域、代理、受控WebView | redirect/DNS/私网策略、请求数据来源、证书、大小、取消 |
| 文档/媒体 | 文本/HTML/表格/图片信息、已有文档解析能力 | PDF/OCR/语音等可选本地依赖 | 来源许可证、离线、内存上界、输出artifact |
| Android标准 | Intent、应用信息、通知、剪贴板等系统允许范围 | 每项Android权限/用户交互 | 不隐藏系统授权，不把 permission 当全部操作许可 |
| UI自动化 | accessibility树、选择、点击、输入、滚动 | 用户启用服务；截图另需合法系统通道 | target/selector重新验证、敏感确认、屏幕锁定与旋转 |
| 终端 | 自有沙箱命令runtime、文件、进程、会话 | PTY/JNI/可选用户安装的兼容环境 | 真PTY、进程组停止、cwd/env、输出限额；sh不能冒充bash |
| 高权限设备 | 可选Shizuku/root等通道 | 用户实际授权与系统允许 | 单项能力探测、最小权限；默认实现不依赖这些 |
| 现有生图/模型 | 经窄业务入口调用已有能力 | 同一资源仲裁/lease | 不直接调用图片feature/主VM、不创建第二engine |

注册数不是对标标准。N08 建立上游工具逐项 manifest：输入/输出、host依赖、权限、MCA对应和可运行样本。高权限功能允许因具体系统授权未取得而标记不可用，但必须如实保留能力说明与准备入口。不可用项不能记为 parity PASS。

## 5. 工具包、Skills 和 MCP

### 5.1 三类扩展各有含义

- Skill：说明与资源；选择工具和流程提示，不能自行授权或安装执行环境。
- Tool package：版本化可执行/声明式工具导出，包含脚本和资源，由 host API 获取能力。
- MCP：协议连接和远端/本地服务能力；token配置、连接、schema和session生命周期独立。

保持旧 inert package v1 兼容，新增 executable package v2，导入时不可根据同一个模糊字段猜类型。模型可申请使用已安装工具；安装、启用、升级及权限配置只由明确用户动作或被授权的管理流程完成。

### 5.2 包模型

```text
identity: packageId, version, contentHash, origin, license
compatibility: formatVersion, minHostApi, runtimeKind, abiRequirements
exports: toolKey -> entry, schema, effects, resultSchema, limits
dependencies: exact lock entries + source/hash + scope
capabilities: upper bound of host methods/resources/origins
resources: normalized relative path + hash + size
permissions: requested purpose + user policy reference（不是grant）
compatReport: third-party API coverage / unsupported APIs / transformation version
```

manifest 本身不可信。安装器扫描实际内容，拒绝 zip-slip/重复条目/符号链接逃逸/bomb/加密/未知可执行物；禁止任意 DEX/动态JNI 从第三方包进入主进程。支持的媒体/资源有显式大小与格式上界。

### 5.3 安装、更新与离线依赖

```text
SAF/已授权来源导入 → staging
→ 格式/完整性/依赖图/锁文件/许可证/能力预检
→ 显示新增/变化权限和兼容报告
→ 隔离无副作用验证（解析/导出schema/纯fixture）
→ 写不可变 content-addressed version
→ 事务切换 active pointer
→ 新run可见；旧run继续引用已固定内容
```

更新失败保留旧 active version。崩溃按 install journal 回滚未提交 staging；低磁盘失败不能删除旧版本腾空间后赌更新。依赖图拓扑解析，范围冲突/环/缺锁拒绝执行；离线 bundle 携完整锁定依赖，不在脚本运行时偷偷 npm/pip install。

卸载/禁用先撤销新准入和未消费审批；对正在跑的包按用户动作和策略停止，等待 host call 核账后 GC。运行中的旧版本有引用计数/持久引用，不得被更新清理。packageHash 必须进入审批绑定。

### 5.4 隔离执行

使用 Android `isolatedProcess` 服务承载 QuickJS 类运行时（最终依赖由N10确认），不持 MCA DB、模型文件路径、Keystore、HTTP client 或用户目录。服务仅收到脚本/只读资源句柄、输入、限额和窄 Binder/RPC bridge。CPU中断、堆/栈/时间/输出配额、服务死亡必须可验证。

**same UID 的独立进程、token或QuickJS本身都不能称为恶意代码强沙箱。** isolated UID/permission配置、文件描述符授予、JNI边界和实际系统调用探针共同构成验收。若平台不能满足，受影响脚本来源不得宣称强隔离，不能静默退回主进程执行。

bridge 不提供任意 reflection、Java对象、raw Context、自由文件路径或网络socket。脚本内 `fetch`、文件、设备、shell等行为全部转换为 host-call 提议，继续走工具流水线。父级“运行脚本”许可只允许启动计算，不能覆盖内部发送/删除/点击等副作用。

Host RPC 带 runtimeInstanceId、packageHash、parentInvocationId、stableCallOrdinal、argsHash；服务重放同号不同参数拒绝。宿主保存 child invocation 账本；取消父脚本立即拒绝新子调用，已派发动作仍分别结算。

这些字段不能由guest自报来建立信任。宿主创建不可伪造session capability，server端绑定runId/epoch、parentInvocationId、packageHash、allowed host methods/scopes、revocation epoch，再校验实际Binder来源和session映射。guest传入身份只作一致性核验；跨session/跨包重放拒绝。隔离进程启动Application时不得初始化MCA DB/engine/主执行根。首版采用一活动实例一隔离进程以隔离硬杀影响；后续池化先证明实例级取消与故障语义，不能停止A同时悄悄杀B。

通用 JS 栈/Promise 不作透明持久化承诺。重启后纯脚本可从固定输入重放，host调用按账本复用确定结果；遇到时间/随机数/外部未知效果时转 NEEDS_REVIEW 或显式 checkpoint 恢复。不可将这种恢复伪装为任意脚本 exactly-once。

### 5.5 third-party 兼容

采用 **导入适配器 + host API 兼容层 + 样本契约测试**，不把 third-party 的内部 service/Activity/全局配置移进 MCA。固定格式、API语义和样本来源，生成可审查 normalized package。

兼容报告分四类：`SUPPORTED`、`TRANSLATED`、`REQUIRES_HOST_CAPABILITY`、`UNSUPPORTED`。未知入口/API/权限在导入预检显示，不允许运行后悄悄跳过。依赖 root/虚拟显示/特定终端的包必须有对应 host adapter 和真实验收；不能用模拟返回值宣称兼容。

覆盖脚本参数/返回/异步/错误/超时、资源、ToolPkg 容器、工具组合、host方法、允许的 UI/Hook。全局 prompt hook 改为受限贡献点：catalog metadata、context附加数据、after-result观察、运行结束观察；不得修改权限或直接重入派发。

固定兼容输入包括 JSON/HJSON manifest、`schema_version=1`、`toolpkg_id/version/api_version/requires/main`、子包 JS 的 METADATA 与 `<subpackage_id>:<tool_name>` 命名、CommonJS require、预编译 JS、资源、WASM、workflow/workspace 模板，以及 main/ui/sandbox/provider 四上下文。MCA 自有 executable v2 与 third-party v1 是两套版本号，不混为同一格式。main 的注册阶段不得派发资源或 WASM 副作用；provider 通过窄来源端口提供数据，IPC 不授予额外权限。每一上下文有独立 host API 暴露表和关闭顺序。

third-party 原始 API 的功能需要逐项映射，但更窄的安全语义可能使部分包无法原样运行。此时提供转译结果及精确差异；不得把全局任意 Hook 替换为无效回调后写“完全兼容”。最终目标包含 WASM/UI/provider 等能力，阶段内缺项必须保留为待补卡或明确未达标，而不是从对标分母删除。

包 UI 以结构化表单/结果组件或隔离的无桥接脚本视图实现。若采用 WebView，禁用任意Java bridge并限制导航/文件/网络；用户动作仍产生独立审批命令。与现有页面互不侵入。

third-party 源码和样本许可证逐项检查。行为参考不意味着可以无义务复制；若采用LGPL代码或其他第三方代码，必须先形成依赖/修改/再分发义务清单。名称“兼容”不等于使用其品牌。

### 5.6 MCP/Skills

- MCP 先接现有固定协议子集，写清支持的transport/auth/tools/resources/prompts；未支持的协议不能标兼容。Android本地stdio server依赖可执行runtime/进程owner；不能让server自带无限shell权限。
- 每run session/grant/schema快照固定；schema drift撤销旧审批，连接失败显示降级原因；不能完全静默回落让用户以为工具仍在。
- Skills 每run读取启用快照；allowed-tools只收窄；body/resources按需受限注入，不把任意Skill脚本当成工具包执行。
- 安装更新不持模型lease；等待MCP/用户审批时不占native计算资源。

## 6. 记忆体系

### 6.1 所有权与数据模型

Agent记忆继续独立于聊天/助手/知识库。可复用分词、文档解析或embedding算法能力；跨域内容进入Agent必须通过有来源和用户范围的导入/引用契约，不让Agent DAO直接读写其他领域表。

增量实体（最终表名由N13迁移设计确定）：

| 记录 | 关键字段与作用 |
| --- | --- |
| memory item revision | itemId/spaceId/revision、textHash、kind、sourceTask/run/artifact、validTime、dataClass、retention |
| memory metadata | folderId/path、tags、typed properties、importance/credibility、aliases；移动/改名保留稳定 itemId 与来源 |
| memory proposal | sourceRefs、提取模型/模板hash、候选diff、confidence、决议、policyVersion；未通过不进入检索 |
| links | source/target item revision、relationType、provenance；端点均必须在授权范围 |
| lexical index | term/chunk/space/version；用FTS或有界倒排实现query-driven检索 |
| vector jobs/index metadata | embedding model fingerprint、tokenizer/pooling/normalization/dim、chunkHash、index generation、job status |
| deletion/rebuild jobs | tombstone revision、覆盖的派生数据、checkpoint、失败原因；可恢复 |

已有空间/条目/分块ID保留；新字段给显式兼容默认/回填，不吞未知 enum。旧词法数据不强制先embedding才能读取。

### 6.2 读取与UI闭环

管理页具备 create/import/edit/delete/search preview、来源/引用、链接、索引状态和删除进度；选择器把spaceIds写进CreateAgentTask和冻结policy。空间删除/授权撤销立即阻止新检索；投影刷新不等于已撤销执行权限，执行器派发前仍检查。

完整查询支持显式空间、文件夹、标签/属性、有效时间/创建修改时间、snapshot revision、最小分数、topK 和引用选项；限制词法/向量可比较的分数语义，不能把不同算法原始分数当同一个阈值。快照读取只返回当时可见且当前仍有权限的版本；删除墓碑优先于旧快照，不得借历史检索绕过删除。链接查询和文档 chunk 读取同样重新鉴权。移动跨空间要在源/目标都获授权的事务中处理，不凭文件夹拖动隐式放大范围。

检索结果含 itemRevision、chunkHash、sourceRef、摘要/截断标记。取回原文时再次授权，不用citation变成任意文件读取票据。

### 6.3 混合检索算法

1. 校验task scope、空间有效状态、query及输出预算。
2. 在授权空间内query-driven词法候选（BM25类/词项分数）；扫描上界和候选topK分别记录。
3. 若本地语义能力可用，从同授权空间/同embedding fingerprint索引取向量候选；**不能全库ANN后只在最后过滤造成跨空间泄露/召回饥饿**。
4. 合并去重，RRF等确定性融合；根据证据允许的local reranker可选重排，缺能力明确回落。
5. 可选一跳链接扩展，仍检查端点授权、深度和候选限额。
6. 多样性/去重复、按最终模型token预算选择excerpt；输出稳定引用及truncated/estimated字段。
7. 在最终模型序列化阶段重新计数，不假设“1024 estimated tokens”足以保证实际context。

建议初始lexical/vector各100候选、融合50、rerank20、返回6；这些是**待测默认值**，N14按设备内存/延迟和召回曲线调整，不能删减测试样本制造性能收益。分页尾部目标、中文同义/英文缩写、多个空间同名、删除中索引都必须覆盖。

本地向量索引先采用小数据集可核验的bounded exact search；数据规模需要时选固定依赖的ANN实现，使用同一语义测试集证明召回。不要为了模仿上游HNSW预先引入重依赖。

### 6.4 本地辅助模型

embedding/提取/重排通过窄 capability port 调用 MCA 真实资源仲裁。生成可兼任提取；embedding须实际支持输出向量，不能拿生成文本hash当语义向量。

当前未证实已有生产文本向量请求/结果契约，N15 不假定仅增加app adapter即可接通。先做本地后端可行性切片：增量扩纯推理契约、同一资源owner调度、真实runner/native向量输出、模型资产识别与exact stop，再做索引。由整合者独占相应写集；真实文本probe确认分词/pooling/normalize/dim和语义近远关系，图像CLIP/内部token张量不能直接冒充句向量能力。没有通过时语义索引保持BLOCKED，不另开一套绕过共享owner的引擎。

- model fingerprint包含模型/导出/运行时/分词/pooling/normalize/dim；不混用维度或不同模型旧向量。
- 不强迫主生成模型与embedding同时驻留；先完成/释放生成lease，再申请索引批次；取消时准确归还。
- 索引是可重建派生物，item/chunk为权威数据。写item后outbox异步索引，job可重复；崩溃保留旧可用generation直到新索引完整提交。
- 热切换embedding模型建立新generation并后台回填；查询只使用匹配generation，过渡时词法回落有提示。
- `LOCAL_ONLY`覆盖embedding/提取/重排/遥测；不得因本地失败转远端。

### 6.5 写入、自动提取和整理

提供 remember/update/forget/link/search/get 工具，所有写入经过相同调用账本和ResourceScope审批。手动保存和模型提案共用一个仓储事务，不能UI直写绕过版本校验。

自动提取是用户可选策略：指定space、来源范围、信息类别、保留期限、是否需逐次确认；默认只产生待确认proposal。开放自动保存时，授权必须是用户明示的窄策略，SECRET/凭据不纳入自动保存，冲突/覆盖/删除仍需决议。不能仅因模型给出高confidence就信任事实。

提取将用户声明、工具事实、模型推测区分，保留原始来源引用。第三方网页/工具输出中的“记住所有秘密”是数据，不能扩权。修改已存在事实用revision CAS；矛盾保留冲突或提出merge，不无声覆盖。

整理包括dedupe候选、相近内容合并、标签/链接、过期处理和摘要；先生成可审查变更集，再按授权提交。摘要不是原文替代，保留可追溯版本及合法保留策略。

补齐 alias 管理、weighted directed links/backlinks、文件夹/标签/属性编辑、导入导出、索引重建和用户 profile 文档。profile 更新复用提案/策略和 revision 事务，不能偷偷修改全局聊天 system prompt。按空间保存提取/自动保存的持久计划与最近成功游标，节流、分组、失败退避和重启去重均有独立事实；自动记忆任务在 N21/N22 执行根与触发可用后才启用冷启动运行。

### 6.6 删除与外发

删除事务先核验space+item归属，再tombstone并撤销检索，持久删除作业清理chunks、词法/vector缓存、links、proposal和导出staging。错误空间ID必须零变化。空间删除同理。

既有task快照可能包含过去使用过的片段；UI必须提供“仅删记忆”与“清除关联任务副本”的明确语义，不能谎称只删索引即彻底遗忘。清除任务副本遵守日志与用户数据合同，最小化保留去内容审计。闪存物理擦除和已经外发的远端副本不作保证。

初期保留 LOCAL_ONLY 的现有fail-closed路径；完整目标增加基于来源的外发授权：每次cloud/model/network序列化携provenance，PRIVATE需明确scope，LOCAL_ONLY/SECRET阻断外发。不能仅靠“网络模式”chip。撤销授权取消自己的在途请求并阻断新发出，已发送数据不能声称收回。

来源标签由宿主保守传播：工具/脚本/model/Transform 输出继承全部已读取输入的 provenance join，concat、摘要、Hook、错误、日志、缓存、artifact 和后续节点同样继承。脚本没有精确污点跟踪时，对整次 invocation 的所有输出继承输入全集；包自报“公开”不能降低标签。只有用户明确的特定内容 declassification 决议可放宽，需持久化范围、目的和版本。网络请求、远端 MCP、云模型序列化、分享/导出均为 egress 检查点，不能靠字符串改写洗掉来源。

## 7. Workflow 图、执行与恢复

### 7.1 兼容与定义

旧recipe文件保留只读/编辑能力；转换为一个 `AgentTask` 节点的新图时创建新definition版本并记录来源hash，不改旧task历史。

定义分 draft / immutable published version。发布内容包括 typed inputs/outputs、nodes/edges、bindings、tool/package/skill/model要求、budget、failure policy、trigger配置引用和definitionHash。每次run固定完整版本，编辑draft不改变运行。

节点最小类型：Trigger、Tool、AgentTask、Model、Script、Condition、Logic/Transform、Extract、Wait/Approval、Subworkflow、Output。第三方基线是DAG；普通图强制无环。需重复时采用显式bounded loop/foreach控制节点作为MCA增强，不能把任意回边放进DAG并声称可恢复。

### 7.2 验证和数据绑定

发布前检查：唯一ID、边端点、输入输出类型、required参数、可达性/结束节点、拓扑序、条件路由完整性、权限上界、依赖内容hash、子流程递归/环、表达式限制、大小和预算。

变量绑定使用versioned受限JSON路径/表达式AST，禁止eval任意Kotlin/JS。缺字段、null、类型不符产生稳定错误；secret仅以credentialRef传递且不可打印/内联导出。

分支输出按scope明确；fan-in默认等待所有被激活上游终态，未选分支写SKIPPED；不能因未激活分支永远等待。foreach定义集合上限、每项稳定index和并行上限。子流程固定版本，禁止运行中追随latest。

初始防失控默认建议：100节点、8层子流程、100次显式迭代、模型并发1、工具并发3；共享run预算优先，超限等待/失败明确。数值需经过N17验证，不能凭默认配置宣称可承担无限任务。

### 7.3 单一执行根

`AgentRunOwner`保持run唯一writer。`WorkflowGraphExecution`是其选用的执行策略，负责读取ready节点、产出节点事实和下一步请求；不得自己创建engine或持tool executor map。

- Tool/Script host调用统一InvocationPipeline。
- Model节点走统一模型attempt gateway、lease、context准入和终态，不自行复制HTTP解析。
- AgentTask 节点固定使用**同 root run 的目标执行 frame**，复用原目标循环的单步推进协议，不调用公共 startRun 把子 run 排进“一 active run + FIFO”的队列。frameId/nodePath、预算份额、输入/输出、取消链和 checkpoint 均显式；父 frame 等待时不占模型 lease。这样避免父占 active slot 等待排队子任务的死锁。单独展示子任务时只能创建引用投影，不另建事实执行者；不得用 goal 猜归属。
- Subworkflow作为同执行root下的固定版本frame，nodePath形成稳定身份；定义/节点局部状态与原run状态不重复。
- wait/approval/delay 不持 native/脚本计算 lease。增加 owner 内部 admission 状态 ACTIVE/PARKING/PARKED/RESUMABLE，与用户可见 run 状态分开；它是同一 owner 的调度元数据，不是第二个 run 状态机。确认计算停止、保存 checkpoint 后 CAS 释放 active slot，使其他普通任务可以准入。唤醒以 `(runId, epoch, checkpointRevision, wakeId)` 去重后排队；恢复前 CAS 占 slot，不能直接跳过共享准入。取消/唤醒竞态只能有一个 epoch 推进。

原目标任务执行不受workflow影响。共享提取仅在拥有两个真实生产调用者时完成，避免把所有领域合成一个巨型“AgentManager”。

停车不释放未知执行的 fence：存在未结算 native/script/host 副作用时，按真实冲突资源阻止不安全准入；不将 UNKNOWN 假装空闲。队列对恢复与新任务采用有界公平策略，记录等待时间，避免大量定时唤醒饿死手动任务。共享父预算覆盖所有 frame，父取消一次传播，已派发子调用逐项核账。

### 7.4 节点状态、事务与恢复

节点尝试状态建议：PENDING、READY、RUNNING、WAITING_APPROVAL/USER/TIMER/RESOURCE、SUCCEEDED、FAILED、SKIPPED、CANCELLED、UNKNOWN。run状态仍使用现有AgentRunState，按节点事实由owner归并。

持久化至少包括：workflow definition versions、workflow run关联、node attempts、bounded input/output payload refs、trigger inbox/slots、dispatch outbox、checkpoint序号。节点输出与成功checkpoint/ready后继写入同事务。

崩溃恢复逐节点按工具效果矩阵处理：纯节点/稳定读取可重跑；已提交输出直接复用；外部动作无receipt转UNKNOWN/NEEDS_REVIEW。**DB事务不能使远端副作用exactly-once**。不支持查询幂等状态的系统操作不得自动重放。

恢复须有 `executionKind/recoveryVersion` 分派和启动 admission fence。现有 startup reconciler 可直接写 journal，并将多类等待状态暂停；不能原样套用到图任务。进程先单飞分页核账全部 nonterminal，区分旧目标任务与 graph/frame/parked token，在 owner mailbox 或独占恢复阶段串行提交后才开放 UI/Worker/trigger 的 dispatch。旧目标任务保留现有不自动续跑合同；图任务按已授权恢复策略保留 timer/approval token，过期授权重新申请。恢复阶段和普通 owner 不得并发写同一 run。

Workflow结束标准是所有激活必需节点成功且真实输出提交；某分支失败按definition failure policy：fail-fast、继续指定错误分支或等待人工。补偿是显式动作，要审批且可失败，不许宣称任意操作可自动回滚。

### 7.5 触发、调度和无人值守权限

所有 trigger 先写 inbox，校验来源/版本/去重/配额，再通过同一 owner 创建或唤醒 run。固定 third-party 六类基线为 **manual、schedule、tasker、intent、speech、app_open（冷启动事件）**；每类分别有启停、来源校验和真实入口验收，不能用通知/MCP 事件替换 Tasker 或语音。通知/MCP/更多应用事件作为 MCA 可选增强另计。导入 workflow 不能自动注册高权限 trigger。

Tasker 使用明确协议适配与受控事件字段，不默认导出任意内部组件；Intent 验证组件导出、调用方、action 和 payload 限额，非可信来源不得自带授权；speech 将本地语音转录/用户确认和触发命中分开，无法本地识别时不能宣称离线；app_open 是幂等的冷启动事件，不在每次重组/旋转时重放。权限被撤回和用户禁用触发均先撤销后续准入。

slot key包括definition version、trigger id和canonical event/计划时段；DB唯一约束兜底。重复通知、重启补发、Worker重试只归并同slot。任务创建回执、slot关联和outbox保证崩溃后不会丢失或重复创建。最大积压、同流程重叠策略（skip/queue/replace）与全局队列上限明确；replace先exact cancel并等资源结算。

WorkManager只负责持久唤醒，不是精确定时器、模型owner或节点引擎。日历任务转换成下一次wake，记录timezone/DST/missed-slot策略；不得把简化cron计算宣称完整cron支持。Android强制停止、权限被撤回、重启/电池限制要显示真实状态和补偿策略，不承诺全天候准点。

无人值守分两层：

1. 默认仅LOCAL_ONLY只读工作；无需审批的范围由创建workflow时明确选择。
2. 达到更广自动化时引入**workflow授权策略**：definitionHash、tool/packageHash、精确scope/参数约束、次数/期限、触发来源和撤销version全部绑定。策略为每次符合条件的动作签发一次性执行授权并落账；不是把所有write设ALLOW。变化超界转WAITING_APPROVAL并通知，不能静默跳过或在后台自动同意。

UI高风险操作、凭据外发、包安装、系统权限变化默认不允许无人值守。用户授权可用于明确业务动作，但OS需要的交互仍由系统要求；不靠root等通道隐去确认。

### 7.6 冷启动执行根迁移

这是独立ADR和切片，不是加一个Application单例字段：

1. 画出当前AppStartupViewModel/MainViewModel/container/engine/facade/AgentData/FGS创建和关闭链。
2. 将实际共享执行服务根移到明确进程holder，application-only依赖；UI attach/detach只增减观察/绑定，不再销毁仍有合法任务的资源根。
3. 聊天/图片/Local API/Agent统一取得同实例窄能力；native隔离、supervisor、lease和stop路径连续；禁止保留新旧两套实例。
4. lifecycle root重建要核账旧进程资源，初始化单飞；Worker/Receiver只拿provider/inbox，不能从Activity或VM借实例。
5. idle资源释放政策与未证明停止fence不矛盾；应用关闭/任务停止/OS杀进程分别验证。
6. 冷启动加载模型使用用户已授权的workflow绑定和MCA既有模型加载合同；不可拿“当前聊天选中模型”代替固定绑定。

初始化必须识别process role：主进程、既有native工作进程、isolated脚本进程各自只建所需依赖；不能所有Application.onCreate都创建同一份MCA模型/数据root。

在上述回归未通过前，保持手动/前台能力，调度可以显示已计划但阻塞于执行根未就绪，不能宣称后台自动执行完成。

### 7.7 编辑器与运行界面

既有workflow页增加定义列表、编辑与运行详情。手机首选可访问的节点列表/连接表单，同时提供缩放画布投影；两者编辑同一draft，不建立两个事实源。至少包括节点增删、连线、输入绑定、条件、错误分支、验证错误定位、导入/导出和发布差异。

运行详情展示当前节点、输入来源、脱敏输出、等待原因、审批、耗时/消耗、恢复/取消及产物。工具/节点结果与事实账本一致，不能依据动画播放完成标SUCCEEDED。

## 8. 上下文、配额、诊断与兼容

- system、工具schema、Skills、memory、workflow输入、工具结果和输出预留一起进入最终context预算。优先用后端tokenizer/真实模板；仅估算时标estimated，并在真实提交处以模型实际context限制二次准入。
- 工具结果保留完整payload/artifact引用，模型侧使用有hash与truncated标记的摘要；不无声丢弃配对历史。
- 长任务压缩是新增versioned策略：保留指令/授权/未结调用/来源引用，摘要作为派生checkpoint；质量测试不过保留现有CONTEXT_LIMIT暂停，不能为性能偷偷截短。
- run/space/package/workflow各有磁盘、输出、安装、队列与索引配额。低磁盘先拒绝新增写入并保留旧数据，不能自动删模型或用户文件。
- 日志字段：身份、阶段、catalogHash、protocol、policyVersion、scopeHash、结果码、stop阶段、时钟和数量；默认无原文、token、URI凭据、完整selector/通知内容。调试导出先预览脱敏。
- schema版本分配由集成者维护migration ledger；新表按职责独立migration函数，但统一Room事务与版本。旧recipe/包/任务/记忆、未知枚举、缺字段和中断升级均需fixture。
- 回退优先停新入口/新来源/触发，保留现有数据与只读历史；数据库升级后采用前向修复或版本化备份恢复，不做destructive fallback。

## 9. 设计完成不等于运行完成

所有新增路径都必须有真实生产调用者、精确测试和产物绑定。Android平台限制、QuickJS隔离、本地embedding、workflow冷启动和第三方包兼容，本轮都未运行。最终验收按 [PARITY_ACCEPTANCE.md](PARITY_ACCEPTANCE.md)，不得用方案文字或fake的PASS替代。
