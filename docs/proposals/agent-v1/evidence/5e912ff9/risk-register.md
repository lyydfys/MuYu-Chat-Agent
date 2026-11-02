# MCA Agent V1 — Risk Register（基线证据）

- 基线 revision：`5e912ff9ac7bf78f1ed1f452657da642cfbdfead`
- 工作区：tracked diff 为空（除子模块）；`third_party/tokenizers-cpp` dirty，patch SHA-256 `8B6C0D1DA1133E4A231482F80BE967C73D85AA403E5F2A20EBAB97A1EB22EDD6`
- 未跟踪：`docs/proposals/agent-v1/`（三份方案与本证据目录）
- 日期：2026-10-19
- 状态说明：本文是 K00 的事实/风险记录，不产生任何生产 PASS。所有运行项在无证据前均为 NOT_RUN。

本文把架构合同第 17.1 节的 10 条风险与本次源码核查新增的风险合并编号；每条给出触发条件、现有源码事实、缓解措施、停止条件（不满足即不得放行）和责任卡。

## 风险总表

| ID | 风险 | 触发/证据线索 | 影响卡 | 停止条件 |
| --- | --- | --- | --- | --- |
| RISK01 | 本地模型不能稳定产出 PROMPTED_V1 控制协议 | 本地 runner 接收 messagesJson/paramsJson，`Done.toolCalls` 默认无 calls；llama.cpp formatter 未把 tools 传入模板 | K02–K04, K08 | 每代表模型统计；未过 G0 协议门槛不得接生产工具 |
| RISK02 | 取消串任务或 lease 提前释放，导致聊天/图片损坏 | 现有无条件 stop 会 cancelAllActive；`acquireExclusiveLifecycleLease()` 默认 `stopActiveGeneration=true` | K04, K11 | 出现一次全局误停/旧回调串写即阻断候选 |
| RISK03 | 云端/插件资料未授权外发 | Agent 计划复用云 provider；现有 provider 无 Agent 级 egress 来源清单 | K07, K13 | 任何未授权字节外发即硬失败 |
| RISK04 | DB/文件跨资源崩溃导致数据丢失或重复产物 | Room 与文件系统无跨资源事务；现有 commit journal 是图片专用 | K05, K06, K14 | 任一崩溃点无法核账或出现重复副作用即阻断 |
| RISK05 | Android 后台限制导致任务中断或 FGS 违规 | targetSdk 36；现有生成服务使用 specialUse；Agent 需要新的用途声明与通知 | K11 | 不得降 targetSdk；启动失败必须可恢复暂停 |
| RISK06 | 新模块只是形式隔离，主 VM/Host 继续膨胀 | MainUiState 已有约 141 字段、MainCoordinatorHost 约 71 能力的历史债务 | K01, K08, K15 | 新增反向依赖/万能 Host 能力不得完成 |
| RISK07 | 桌面插件兼容被高估，引入任意代码/许可风险 | DSH/Node/Android ABI 未验证；V1 不运行任意代码 | E03/E07（非 V1） | 未过 Host 决策门只保留 Skills/MCP 限定声明 |
| RISK08 | 依赖或规范随时间变化，解析器/MCP 运行不一致 | Jackson Core 版本与 MCP 2025-06-18 均为外部输入 | K00, K02, K13 | 精确版本+校验和+兼容 fixture；不自动升级或宽松降级 |
| RISK09 | 文档与实际基线漂移（行号、迁移版本、文件移动） | 本基线 @Database = 27；HEAD 与方案审查基线不同 | 每卡 | 以实施时源码为准；事实冲突先形成差异决策 |
| RISK10 | 连接 EOF 被误判成功，截断响应触发工具执行 | 现有 OpenAI-compatible/Anthropic 可能在 EOF 后发 Done | K04, K08, K13 | 严格 terminal 策略；缺协议完成证明不得执行 |
| RISK11 | Agent 与现有 engine 作用域不一致（新增） | engine 由 `MainRuntimeAssembly` 以 `MainViewModel.viewModelScope` 创建；`onCleared()` 关闭 isolated runners。Agent 方案要求应用作用域常驻 owner | K04, K08, K11 | 只允许“同一 engine + 同一 facade”的窄注入；出现第二 engine/第二 runtime 即阻断 |
| RISK12 | 本地生图会卸载 LLM，Agent 自动续跑不成立（新增） | `ImageGenerationOwnerExecution` 通过同一 exclusive lifecycle lease 做内存交接并 unload；没有已证明的 Agent 自动 reload | V1 范围 | V1 不开放“本地 LLM→本地生图→自动续跑”；必须显式暂停/恢复模型 |
| RISK13 | 备份/迁移排除遗漏导致私人 Agent 数据进云备份（新增） | 现有 `backup_rules.xml`/`data_extraction_rules.xml` 只覆盖既有目录；Agent 新文件根未存在 | K06, K14 | Agent 文件根未加入 backup/device-transfer 排除前，不得开放写入 |
| RISK14 | 设备/模型资产不足被误报成产品缺陷或“已验证”（新增） | 当前仅一台 ARM64 设备（device-a）；代表 MNN/GGUF 模型资产与配置未在 K00 锁定 | K04, K11, K16 | 缺设备/模型只标 BLOCKED/NOT_RUN；不以云替代本地，不用 fake 代替 |
| RISK15 | 数据库版本被盲改或 destructive fallback 清理用户数据（新增） | 基线 `@Database(version = 27)`；方案允许 N→N+1 | K05 | 迁移必须来自 KSP 真实导出；禁止 fallbackToDestructiveMigration 与清库 |
| RISK16 | 现有会话/模型状态被 Agent 侧写污染（新增） | `MainUiState` 是 UI 权威；`UiStateStore` 有过渡写口 | K08, K09, K10 | 新增 Agent 业务字段或第二 writer 即阻断 |

## 逐项说明（缓解与证据要求）

### RISK01 本地协议稳定性
- 事实：`ChatRequest.messagesJson()` 只序列化 role/id/content/created_at；`tools`/`toolExchanges` 不进入本地 messagesJson（`core/inference-contract/.../ChatRequestTypes.kt:49-65`）。本地 `Done` 的 toolCalls 默认空；llama.cpp 模板未接收 tools。
- 缓解：PROMPTED_V1 走独立 transcript 序列化；每次完整 ChatRequest 重建；严格 decoder 只读 visible assistant 完成输出；每 run 只允许 1 次 repair；按模型资产 fingerprint 记录能力。
- 证据：K02 的 golden messagesJson + K04 的真实 MNN/GGUF call→result→final 闭环。K03 的 fake 结果不能代替。

### RISK02 取消与 lease
- 事实：`ChatExecutionOwner.stopGeneration()` 调用 `supervisor.cancellationController.cancelAllActive(...)`（`core/engine/.../ChatExecutionOwner.kt:98-101`）；`McaInferenceService.acquireExclusiveLifecycleLease(stopActiveGeneration = true)` 是默认参数（`McaInferenceService.kt:155-158`）。生产 stop 必须用 identity 检验后的 `stopGenerationIfActive`。
- 缓解：Agent 只调用 `ChatExecutionFacade.stopGenerationIfActive(requestId)`；每次 attempt 唯一 requestId；acquire 必须显式传 `false`；`AgentModelLeaseRetention` 在 app 作用域持有 lease，停止证据齐备后恰好释放一次。
- 证据：O02/O05/O06/O08 交叉取消与真实停止时间线；Q02 四阶段时间。

### RISK03 egress
- 事实：云聊天可直接经 `OpenAiCompatibleChatProvider` 执行，不自动经过 `ChatExecutionOwner`（架构 §5.2.2）；Agent 需要独立策略 client 和 payload 来源清单。
- 缓解：Agent 专属 OkHttpClient；HTTPS/禁自动 redirect/DNS 路由绑定；每次发送前按来源清单复核；LOCAL_ONLY 由 transport 强制，不靠 prompt。
- 证据：S09/S10/S11/S14 的真实 payload capture（脱敏）。

### RISK04 崩溃一致性
- 事实：现有 `ChatImageCommitJournal` 是图片领域专用；Agent 需要独立 `agent_artifact_commits` 账本（架构 §10.3）。
- 缓解：staging→不可变版本 rename→DB 确认；恢复按 commitId/hash 核账；写/外部副作用未知一律 UNKNOWN。
- 证据：D06–D09 的每一步崩溃注入。

### RISK05 FGS
- 事实：现有 manifest 已有多个 `specialUse` 服务（`app/src/main/AndroidManifest.xml`），targetSdk 36；`McaGenerationForegroundService.kt` 已有生成保活。Agent 需要新增用途/通知 channel 前核对平台限制。
- 缓解：优先复用现有 FGS lease/服务；等待审批/资源/用户时不持 native lease；启动失败转 PAUSED(FOREGROUND_SERVICE_UNAVAILABLE)。
- 证据：O11 的 Android 适用版本实测；S15 通知隐私。

### RISK06 形式隔离
- 事实：硬锁 D01/D02 已记录大 Host 与 141 字段 MainUiState；本次不得继续扩张。
- 缓解：`AgentWorkspaceQueries/Commands` 窄端口；`MainUiState` 不加 Agent 字段；`MainCoordinatorHost` 不加 Agent 能力；feature 不依赖 runtime/engine。
- 证据：A05/A08 的 diff 审查 + K15 差量门禁。

### RISK11 engine 作用域（本基线新增）
- 事实：`MainRuntimeAssembly.engine` 由 `(injectedEngine as? McaInferenceService) ?: container.createInferenceService(...)` 创建（`MainRuntimeAssembly.kt:62-66`）；`MainRuntimeAssembly.scope` 是 `MainViewModel.viewModelScope`（`MainViewModel.kt:80-86`）。`MainViewModel.onCleared()` 调用 `container.isolatedLocalChatRunners.close()`（`MainViewModel.kt:463-470`）。
- 含义：方案第 3.2/14 节要求 AgentRunOwner 常驻应用作用域，但 engine 的生产创建点是 VM 作用域。V1 只能通过“同一 engine + 同一 facade 的窄注入”让 app 作用域 Agent owner 复用现有执行域；不得移动/复制 engine 创建点，也不得让 Agent 直接 new engine。
- 退出条件：若后续把 engine 迁移到真正的 application 作用域，需要独立 ADR 和所有权迁移测试；在那之前 `AgentRuntimeAssembly` 的 model port 必须由 `MainRuntimeAssembly` 注入并在 VM 清理时显式 detach（settle 为中断/取消），不得保留悬空 engine 引用。

### RISK12 生图交接
- 事实：`ImageGenerationOwnerExecution`/`RuntimeReleaseOperations` 以同一 engine exclusive lifecycle lease 完成 LLM→图像内存交接并 unload；Agent V1 没有已证明的自动 reload。
- 决策：V1 不注册本地生图工具；后续 E01 单独 ADR。

### RISK13 备份排除
- 事实：Agent 计划新增 `files/agent/workspaces/...`；既有 backup XML 只按当前已知路径排除。K06 必须同时修改 `backup_rules.xml` 与 `data_extraction_rules.xml`，覆盖 cloud backup 与 device transfer。
- 证据：S16 XML 检查 + 导出包扫描。

### RISK14 设备/模型
- 事实：`adb devices` 当前有一台 ARM64 设备（序列 device-a，model 2304FPN6DC）。MNN/GGUF 代表模型资产、加载配置与云端可用凭据未在 K00 锁定。K00 不允许把“设备在线”写成“模型路径已验证”。
- 缓解：K04 先固定模型资产 fingerprint/后端/量化/有效参数；MNN 与 GGUF 分开记录；云端单列。
- 证据：Q01/Q02/Q03 的逐后端样本；缺证据保留 BLOCKED。

### RISK15 数据库
- 事实：`ChatSessionStoreRoomDatabase.kt:33` 为 `version = 27`；导出 schema 目录 `app/schemas/com.muyuchat.mca.McaRoomDatabase/27.json` 存在。K05 必须用真实 KSP 导出生成 28，不得手改 27.json。
- 缓解：MigrationTestHelper 以真实旧 schema 建库；保留旧聊天/附件/助手 fixture；禁止 destructive fallback。

### RISK16 状态污染
- 事实：`MainUiState`/`UiStateStore` 是现有 UI 权威；Agent 页面状态必须独立。
- 缓解：Agent task/run/event/approval/model-selection 不进 MainUiState；Agent 页面 VM 只做投影/intent；models/offline 选择通过 Agent 命令端口持久化。

### RISK17 设备安装策略（BLOCKED）
- 事实：既有 `com.muyuchat.mca` 与 debug APK 签名不一致，会触发 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。已构建隔离变体 `.devicetest`（debug 签名、独立 applicationId、`testBuildType=deviceTest`）以避免卸载清数据；该变体 APK 构建成功。
- 新阻塞：MIUI 设备对 `adb install` / `pm install` 返回 `INSTALL_FAILED_USER_RESTRICTED`（“Install canceled by user”）。这是开发者选项“USB 安装”权限，adb 无法代开；`pm set-user-restriction` 无 MANAGE_USERS。
- 影响：Room 27→28 仪器迁移、Room journal、K14 SAF 持久化、Compose Agent androidTest 全部无法执行；不能把“APK 构建成功”写成“迁移通过”。
- 处置：保留 NOT_RUN/BLOCKED，请用户在设备开发者选项开启“USB 安装”后重跑；不得用假 PASS、手改 schema 或卸载数据绕过。

### RISK18 K14 剩余取舍
- 事实：K14 导出/导入/删除/配额已实现并通过 114 个 data JVM 测试；导入任务以 `IMPORTED_ARCHIVED` 单事务持久化，物理 artifact 原子落盘并复核 hash。
- 取舍：v28 的 `agent_artifact_commits.invocationId` 为 NOT NULL FK，导入没有 invocation，故不写 commit 行（`commitId=null`）。若审计要求 commit 行，必须新增 v29 迁移把该列改为 nullable，属于新决策。
- 处置：文档记录；不擅自改 schema。

### RISK19 运行时接线缺口（新增）
- 事实：Skills/MCP 尚未合并进 per-run tool catalog（assembler 已完成并 17 tests 通过，但 composition 仍只传 built-ins）；cloud adapter 未接 egress client，云模型选择被隐藏；FGS lease/stop action 的 guard/router 组件已测试（23 tests），但未接入 MainViewModel/service 的 owner 生命周期；Skill revocation 未 invalidate approval。
- 影响：模型/工具能力在真实执行链上仍可能退化为 built-ins + 本地模型；JVM 合同测试通过不等于端到端可用。
- 处置：按 K15/K16 接入并在设备/真实模型上取证；未接部分保持 NOT_RUN。

### RISK20 并行会话回归（已解决）
- 事实：`verify-code -AgentTests` 曾报 2914 项 / 2 失败。`KnowledgeBaseRoomContractTest` 的 v27 断言已随 v28 更新；`LocalImageModelStoreIntegrationTest` 的同长度/恢复 mtime 失败来自并行前端会话未提交的 `LocalImageModelIdentity` 缓存优化。
- 解决：对方会话已回退该缓存改动；FGS 契约测试按新增 Agent stop-action 控制路径更新。最新 `verify-code -AgentTests -Rerun` → **3121 / 0 / 0 / 11 skipped，VERIFY OK**。
- 保留教训：并行会话不得绕过 build lock；全量门禁的输入指纹必须前后一致，本轮曾因并发写入被判 `input-changed-during-run`。

## 未决问题（K00 必须继续核清或带入首卡）

1. `McaInferenceService` 的 native 停止“确切静止证明”在 MNN inline / isolated worker 两类后端分别由哪个现有 API 暴露；若不足，K04 只允许在现有 engine/worker owner 上增加窄读取/等待接口。
2. 云端 Responses `supportsTools` 的实际配置字段与默认值、以及现有 terminal 判定是否已满足 STRICT_AGENT_TERMINAL（K00 capability matrix 正在核）。
3. Agent 文件工具所需的 `workspace` 根目录与现有 no-backup 目录策略如何对齐（K06 定案）。
4. MNN/GGUF 代表模型在设备上的实际可用性与 context 上限（K04 定案，K00 不猜）。
5. `AppTab` 是否有 ordinal 持久化：本基线检索 `AppTab.values`/saved state 未发现持久化映射；K09 再复核，若发现则先做稳定名迁移。
6. Local API 的进程 owner 与 Agent 的单 active run 是否会在同一进程内竞争 FGS/engine；K04/K11 需实测。

## 证据状态

- 本文依据的每一条源码事实均可在基线 revision 中按文件/符号复核。
- 运行证据：全部 NOT_RUN。未运行 Gradle/native/模型/数据库/UI。
- 后续更新规则：RAID 变更必须附新 revision/工作区/子模块 hash，不能用本文替代当前源码。
