# ADR 0003：独立双 Agent 模式（Kotlin Agent 与内嵌原版 DSH）

- 状态：**Accepted for implementation（接受实施方向）；R01 可行性门未通过（pending）**
- 日期：2026-10-19
- 决策范围：MCA 新增“独立 DSH 模式”的产品与工程边界；不包含 Kotlin Agent 的后续优化、不包含 DSH 上游升级
- 关联文档：[架构方案](../proposals/agent-dsh-embedded-v1/ARCHITECTURE.md)、[执行包](../proposals/agent-dsh-embedded-v1/EXECUTION_PACK.md)、[基线](../proposals/agent-dsh-embedded-v1/BASELINE.md)、[能力清单](../proposals/agent-dsh-embedded-v1/CAPABILITY_MANIFEST.json)、[验证计划](../proposals/agent-dsh-embedded-v1/VERIFICATION_PLAN.md)、[工程硬锁](../architecture/MCA_PROJECT_HARD_LOCK.md)、[ADR 0001](0001-runtime-ownership-and-identity.md)、[ADR 0002](0002-agent-v1-runtime-and-delivery.md)
- 基线：MCA `98eb8e47a98bd90d2f071203c2a56dcd29b013d1` + 当时未提交工作区 + 三个子模块内部 diff（见 BASELINE）；官方 DSH `687f282e8625c6b8d11ff58211a90524be2e4317`（0.2.1-rc.2）
- 结果词汇：`PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE`

## 1. 背景

1. MCA 已有一条独立的 Kotlin Agent 路线（Native Agent V1，见 ADR 0002）：自有内核、Compose 原生页面、Room 任务/会话模型、Agent 工具与审批体系。该路线属于保留产品能力，不是待替换旧实现。
2. 用户最新要求是**再增加一个独立 DSH 模式**：使用官方 DSH 代码、官方前端、官方工具/插件与 Cordis 扩展协议；MCA 只提供该模式入口、Android 必需宿主与模型能力。现有 Kotlin Agent 继续保留并后续独立优化。
3. 两条路线的执行内核不同，且同一个任务不能由两个内核推进。若为了“省事”把 DSH 塞进 Kotlin Agent 的执行循环、状态机或工具注册表，会同时破坏两侧的语义、审批与恢复边界。
4. 官方 DSH 是桌面/Node 程序，不是官方 Android 产品。把官方 Web profile 放进手机需要 Android 宿主；宿主方案仍处于 R01 可行性探针阶段（H1 JNI/shared-library 与 H2 packaged-exec 均 `NOT_RUN`，未选择）。
5. 因此本 ADR 只冻结“两个独立模式如何并存、谁拥有什么状态、共享什么、如何回退”，不宣告 Android 可运行、不宣告功能对齐、不代替 R01。

## 2. 决策

### D1：新增独立 DSH 模式，Kotlin Agent 原样保留

- DSH 是**新增的第三类工作面**，与既有“图片 / 聊天”以及 Kotlin Agent 并列；不是对 Kotlin Agent 的替换、包装、迁移或隐藏入口。
- Kotlin Agent 的执行、任务、会话、审批、工具、页面与恢复行为不因本 ADR 改变；其后续优化按原路线独立进行。
- 两个模式各自持有内核、前端、工具链、审批与数据 writer；不存在“共用一个 Agent loop、按开关切换内核”的实现。
- 同一个用户任务不得被两个内核同时推进；模式切换只改变可见工作面，不改变在途任务的所属内核。

### D2：DSH 使用官方内核、官方前端、官方工具/插件

- DSH 运行固定版本的官方 Web profile（`dsh --profile web` 语义）：官方 Node 内核、官方 Web 前端、官方会话/压缩/计划/子代理/工作流语义、官方工具注册与 Cordis 插件体系。
- MCA 不重画 DSH 的会话、审批、插件、模型选择、计划、文件与产物页面；DSH 页面内部保持官方视觉与交互。
- MCA 外层只负责：入口/返回、启动中/失败、运行时退出、必要的 Android 系统交互（文件选择/保存、分享、外链、通知、权限）。
- 插件兼容分三个独立结论：插件协议兼容、依赖可安装/加载、实际功能通过；三者不得互相替代。
- 官方发行树是能力真值：manifest 行从实际装配树生成，不以 README 功能名或“上游有该包”推断可用。

### D3：侧栏顺序固定为 图片 → Agent → DSH

- 左侧栏展示顺序为 **图片 → Agent → DSH**。
- 既有导航标识语义保持不变：
  - `AppTab.AGENT` 继续是既有调参入口；
  - `AGENT_WORKSPACE`（Kotlin Agent）继续打开原生 Agent 页面；
  - 新增独立稳定标识 `DSH_WORKSPACE`，按兼容合同追加枚举/持久化映射，**不移动旧 ordinal**。
- 恢复导航、返回栈、草稿、滚动与最后选择分别保存；进入 DSH 不重置 Kotlin Agent 的页面状态，反之亦然。
- 共享导航、根构建、资源 owner 与最终装配由单一整合者修改；并行前端工作不得互相覆盖。

### D4：只共享 MCA 模型能力与窄平台契约

两个模式共享的**仅限**以下两类：

1. **MCA 模型执行 owner**：模型目录、真实可用性、请求准入、lease、精确取消、native 资源生命周期仍由 MCA 现有模型执行 owner 独占。DSH 不创建第二套 engine/native loader，不绕过模型加载/切换的既有授权用例。
2. **窄平台契约**：
   - `core:dsh-contract`：DSH 运行时（phase/failure/snapshot/web entry/gateway endpoint）与模型网关请求/事件的协议中立契约；
   - Android 平台能力端口：文件选择/保存（SAF）、分享/外链、通知、必要的系统权限；
   - 认证/来源校验的宿主实现（loopback + launch token + Host/Origin），不暴露通用 JS bridge。

明确**不共享**：

- 不共享 Agent loop、任务状态机、会话历史格式；
- 不共享 Kotlin 工具注册表，不把 DSH 工具转换进 `AgentToolRegistry`；
- 不共享 Room 会话表，不把 DSH 会话 JSONL 双写或重建为 Kotlin 任务；
- 不共享审批状态机：各自展示、各自决策、各自记录；
- 不共享页面/Composable/ViewModel。

`feature:dsh-workspace` 与 `feature:agent-workspace` 互不依赖内部实现；共享能力只通过契约或 `core:designsystem` 的稳定 UI 基础。

### D5：模型网关是协议/身份适配，不是第二个 Agent

- DSH 通过官方 `llm-pi-ai` 的 `openai-completions` 自定义 provider 指向 MCA 内部模型网关；网关只做协议与身份适配，不执行 DSH 工具、不拼接 MCA 聊天提示词、不复制历史。
- 网关保留：真实模型目录与能力、tools/tool_choice、assistant tool_calls、tool_call_id、tool result、并行调用关联、流式 text/reasoning/tool 参数片段、严格终态、usage 与错误语义。
- 网关仅监听 loopback，使用 DSH 专用、可撤销、限制用途的凭据；不把 MCA 云服务原始密钥下发给 DSH。
- 本地模型请求与 Kotlin Agent/聊天/图片/API 一起进入 MCA 原准入与 lease；资源不足时排队或按既有合同报 busy，不创建第二个 native loader。
- 若现成 provider 无法无损表达 MCA 协议，才允许新增 `mca-model-provider` Cordis 插件；该选择必须有协议实测证据，且不能长期同时维护两条未区分的网关路径。

### D6：宿主与生命周期 owner

- DSH 运行时由 Android DSH Runtime Owner（专属 Service/宿主）唯一拥有：启动、ready、失败、停止、代次、崩溃与重启。Activity/WebView 不持有 Node 生命周期权威。
- 运行时状态为 `STOPPED / STARTING / READY / STOPPING / FAILED`，带运行代次；只描述宿主，不复制 DSH 会话状态。
- DSH 默认按需启动：进入 Kotlin Agent 或普通聊天不预加载 DSH；重复进页只附着同一宿主，不重复启动整棵 DSH tree。
- 返回/切模式不自动取消任一模式的任务。停止 DSH runtime 不关闭共享模型 owner，也不停止 Kotlin Agent 的调用；通知 action 必须带模式与代次。
- 精确取消按实际请求身份：DSH cancel/AbortSignal/HTTP 断连/Service 停止/Node 退出分别映射到自己的在途请求；释放 lease 需要原 worker/native 停稳证据，不能用 Flow 结束或布尔返回值代替。
- 进程死亡后按官方会话日志恢复事实，不自动重做未知副作用；新宿主代次撤销旧模型凭据/连接。

### D7：数据 writer 与隔离

- DSH 会话/任务/审批/插件配置/日志采用官方 DSH home/workspace/配置/日志格式，**DSH 是唯一 writer**；MCA 不双写权威会话。
- MCA 只保存可重建的导航引用（`runtime/profile/session` 稳定身份 + 最小显示信息）；重建失败不得篡改官方日志。
- DSH 数据目录与 MCA 聊天/模型/Kotlin Agent 数据逻辑分离；清空 DSH 历史/插件/配置不清理 Kotlin Agent，反向亦然。
- 两种模式的 session/task ID 使用明确命名空间；未来“聊天转交”只能创建新的目标会话并保存来源引用，需要独立授权与协议；本轮不实现自动路由、上传全部历史或切页换内核。
- 目录分离是逻辑隔离，**不是 OS 安全隔离**，不得在文档/UI 中混写。

### D8：更新、回退与发布边界

- 官方 DSH 固定 commit 与 lockfile；禁止跟随浮动 master/next。升级单独评审并重跑受影响回归。
- 维护分三层：官方源码/发行闭包、Android 平台补丁、MCA 宿主与模型适配。每层记录上游 SHA、lockfile、所有 npm/native 依赖、Android ABI/API、构建参数、许可证、校验和与补丁。
- Android 侧保持 targetSdk 36 / minSdk 26，不因第三方 runtime 启动器降级。
- 数据写入新格式后不承诺简单 APK 降级可回退；保留可验证快照、前向修复或官方支持的回退方案。
- 回退以“停用 DSH 入口 + 停止/移除 DSH 宿主接线”为最小动作：不迁移既有 MCA 数据、不删除 Kotlin Agent、不改动普通聊天/图片/模型配置。回退后 DSH 自己的数据目录可保留或按用户明确操作清理。

## 3. 状态与所有权矩阵

| 状态/资源 | 唯一 writer / owner | 读取者 | 禁止 |
| --- | --- | --- | --- |
| DSH 会话、事件、压缩、计划、审批、插件配置 | 官方 DSH | DSH 前端、MCA 导航（仅身份引用） | MCA 双写、重建为 Kotlin 任务、页面状态写入 |
| Kotlin Agent 任务/会话/审批/工具 | Kotlin Agent 既有 owner | Kotlin Agent 页面 | DSH 触碰、迁移、只读归档 |
| 普通聊天/图片/模型设置 | 既有 MCA owner | 各自页面 | DSH 旁路修改 |
| 模型目录、准入、lease、native 资源 | MCA 模型执行 owner | Kotlin Agent、聊天、图片、API、DSH 网关 | DSH 创建第二套 engine/loader；网关绕开准入 |
| 模型网关 call 身份 | 网关 call registry（`runtimeEpoch + callId → requestId/attempt/lease`） | Runtime Owner、UI 终态 | 全局 stop 清空其他入口；旧回调更新新请求 |
| DSH 宿主 phase/epoch/失败 | Android DSH Runtime Owner | `feature:dsh-workspace`、通知 | Activity/WebView 持有生命周期权威 |
| 认证 token/入口 URL | Runtime Owner（内存、当代次） | WebView 一次性加载 | 普通日志、历史、崩溃报告、外部 Intent |
| 导航/返回/草稿/滚动 | MCA 导航与 DSH 页面各自 | 各自页面 | 用 DSH 重写 MCA 其他页面，或反向 |
| MCA 模型云密钥/本地授权 | 既有 MCA 凭据 owner | 模型 owner | 下发 DSH、写入 prompt/日志 |

新增依赖方向（示意，模块名以真实落地为准）：

```text
feature:dsh-workspace ──> core:dsh-contract <── api:dsh-gateway
        │                        │
        └──> core:designsystem   └──> MCA 模型执行 owner（既有 facade/lease）
app(composition root) 负责装配；不承载 DSH 业务规则
```

- `core:dsh-contract` 保持平台无关（不含 Android/Compose/Room/native）。
- `feature:dsh-workspace` 不依赖 `core:engine`、Kotlin Agent 内部实现或 `app`。
- `api:dsh-gateway` 只依赖契约与既有模型能力，不依赖 UI/feature。
- 不扩大 `MainCoordinatorHost`、不向 `MainUiState` 添加 DSH 全量业务状态、不传万能 container、不新增 `Any?` 注入。

## 4. 安全定位（必须与实际机制一致）

- **单 APK + 同 UID 不是沙箱**。普通 `:dsh` Service 进程只隔离崩溃与部分生命周期，不阻止插件使用 Node `fs/net/process` 访问同 UID 资源；官方 DSH `SAFETY.md` 也明确其为未安全审计的开发预览。
- **保留官方审批与权限语义**：默认 `workspace-write + ask`，不复制参考 app 的 `danger-full-access + approval: never` 默认。审批与路径约定能降低误操作风险，但**不构成对恶意插件/模型代码的隔离**。
- **不宣称“完全隔离”**：若验收要求任意不可信插件不能接触 MCA 私有数据库/凭据，必须采用额外 UID 或有效 OS 隔离（独立进程/独立签名 runtime APK 等），并先探针；单 APK `isolatedProcess` 需要 FD、存储与网络代理适配，不能假定普通多进程已满足。
- **不宣称“全离线”**：本机运行不等于全离线。选择本地模型且工具/插件不联网时可离线运行；云模型、MCP 远端、搜索、在线插件安装需要网络。若提供强离线开关，必须覆盖整个 DSH/plugin 进程的网络出口，仅限制模型网关不足以保证离线；未经有效机制验证不得展示强隔离/全离线保证。
- **认证边界**：只监听 loopback；保留官方 launch-token → HttpOnly/SameSite cookie 流程与 Host/Origin 校验；随机端口不是认证；token 不得进入普通日志、崩溃报告、页面历史或外部 Intent；外链交 Android 受控打开，拒绝外站获得原生桥或认证 token。
- **插件信任合同**：明确 DSH 与其安装的插件是应用内受信任可执行代码；模型密钥不下发、目录分离与凭据缩权只能缩小风险面，不能抵消同 UID 文件访问能力。
- **验证边界**：安全结论只能是“在指定产物/设备/配置上观察到”；源代码存在、开关设置或 mock 不能写成安全通过。

## 5. 后果

### 正面

- 两个 Agent 模式各自保持语义与演进节奏；Kotlin Agent 不需为 DSH 改内核/数据/审批。
- DSH 直接复用官方内核、前端、工具与插件生态，减少“看起来像 DSH”的自绘实现风险。
- 模型资源只有唯一 owner，避免双份 engine/内存/竞争。
- 契约窄、写集清晰，可由多个 Agent 并行实现，最终由整合者统一接线与验证。

### 负面 / 风险

- Android 宿主是硬门槛：H1/H2 均未验证，targetSdk 36 下 exec、Bionic、native addon、PTY/PTC 都可能是 `BLOCKED`。
- 官方 Web profile 的 native/桌面依赖（node-pty、koffi、sharp、node-addon-system、ripgrep、LibreOffice 等）需要重建或替换，工作量与可移植性未知。
- 模型协议保真需要实测：现有 MCA Local API/OpenAI 兼容层被源码证实丢失 tools/tool_calls/tool 角色与终态区别（见 R02）。
- 两个模式共享同一模型资源，竞争、排队与精确取消需要 R03 的证据；UI 上“看起来停了”不等于 native 停稳。
- 同 UID 方案有明确安全上限；若后续要求恶意插件隔离，需要新的架构决策与分发成本。
- 官方 DSH 升级会带来 lockfile/native/插件/前端兼容回归；必须独立评审。

### 中性

- 现有 MCA 债务（大 Host、大 UiState、`Any?` 注入、有限架构检查）不为本模式提供豁免；新代码不得扩大这些债务。

## 6. 被否决的替代方案

| 替代 | 否决理由 |
| --- | --- |
| 把 DSH 内核嵌入 Kotlin Agent 执行循环 | 两套语义/审批/恢复相互污染；同一任务可能被双内核推进 |
| 把 DSH 工具注册进 Kotlin `AgentToolRegistry` | 绕过官方插件生命周期与 DSH 审批；制造第二套工具真值 |
| 在 MCA 原生 Compose 中重画 DSH 页面 | 不再是“原版 DSH”；信息架构/视觉/交互必然分叉 |
| 把官方会话 JSONL 映射进 Room | 双写权威状态；破坏官方恢复与压缩语义 |
| 为 DSH 单独创建 engine/native loader | 双份模型资源、竞争与不可取消；违反唯一 owner |
| 依赖参考 app 的可写目录 exec（targetSdk 28） | MCA 保持 targetSdk 36；Android 10+ 限制可写目录执行；参考实现非官方支持 |
| 用 mock/固定返回/关闭工具换“完整 DSH”绿灯 | 伪造证据；违反硬锁与执行包 |
| 宣称“单 APK 同 UID 已隔离”或“全离线” | 与实际机制不符；官方 SAFETY 明确非安全产品 |

## 7. 实施顺序与验证门

- R00（本 ADR + 基线 + 能力清单 + 验证计划）：已交付方向与清单，所有运行结果 `NOT_RUN`。
- **R01 可行性门**：Android 完整宿主探针（H1/H2 最小对比、官方 Web profile、loader/addon、认证、持久化/锁、工具子进程、PTY/PTC、停止/回收）。**R01 未通过前不得开始大规模适配，也不得把任一宿主方案写成已选定。**
- R02：模型工具协议闭环（本地 + 云端各一条真实链，条件不足则 `NOT_RUN/BLOCKED`）。
- R03：精确取消、竞争与 runtime 生命周期。
- R04：独立入口与官方前端容器（图片 → Agent → DSH）。
- R05：原工具/插件平台对齐。
- R06：双模式数据隔离、恢复与升级。
- R07：固定版本功能与插件对照验收（独立验证者）。
- R08：候选整合、可维护性与回退。

本 ADR 的状态是 **Accepted for implementation**，不是发布接受：R01 门未过，DSH 在 Android 上的可运行性、功能对齐与安全结论均未成立。

## 8. 回退

1. 移除/隐藏 DSH 入口（`DSH_WORKSPACE` 映射与侧栏项），保留其余导航语义。
2. 停止并移除 DSH Runtime Owner 与模型网关接线；不迁移、不清理 MCA 既有数据。
3. 保留 DSH 自己的数据目录（用户明确要求时才单独清理），不触碰 Kotlin Agent 的任务/历史/审批/工具配置。
4. 回退后 Kotlin Agent、普通聊天、图片与模型设置继续使用原 owner；模型执行 owner 的既有合同不变。
5. 若 DSH 数据格式已升级，写清“APK 降级不保证数据可回退”，保留快照/前向修复方案。

回退不需要修改本 ADR 的“并行双模式”方向；但任何与“主备/合并/替换”有关的决定都必须另立 ADR。

## 9. 本 ADR 不决定的事项

- 宿主 H1/H2 的选择（R01）。
- 具体 Node 构建、版本、ABI 与打包方式（R01）。
- 模型网关是否必须新增 provider 插件（R02 以协议实测决定）。
- 本地模型是否具备原生 tool calling，以及提示词兼容模式的产品标签（R02）。
- 云端模型是否进入 V1（由用户/产品决定，条件不足时记录 `NOT_RUN/BLOCKED`）。
- 强隔离/全离线是否作为产品承诺（当前不做承诺）。
- Kotlin Agent 的后续优化内容（属于原路线）。
- 发布、签名、推送与远端操作（按用户授权另行执行）。
