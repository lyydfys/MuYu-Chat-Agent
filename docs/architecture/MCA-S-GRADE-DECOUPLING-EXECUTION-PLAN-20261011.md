# MCA 企业级旗舰 S 级解耦与修复执行方案

**版本**：2026-10-11（当前修复版保护与受控执行版）  
**项目**：MCA / `E:\MAC`  
**定位**：工程代码优先、保留现有修复、功能等价解耦和生产验收方案  
**参照**：当前工作区源码、只读原项目 `E:\model\MAC`、既有实现、历史协议测试和 `E:\MAC_ENTERPRISE_DECOUPLING_ARCHITECTURE_PLAN_20261005.md`  
**适用边界**：本文 S 为本项目内部综合工程验收等级，不是行业统一认证；写出方案不表示已经达到该等级。

> 本文件是执行目标，不是当前完成声明。当前仓库仍为 `IN_PROGRESS_NOT_S_GRADE`。所有“已完成”只能以本文件规定的源码、构建、真实链路和设备证据为准。

**本版首要约束**：用户说明当前版本暂时已修完 bug。因此先冻结并保护当前修复版，复测已修行为；历史问题不得照抄为当前未修问题。只有当前源码/测试/真实链路重新确证的问题才补修。已有修复优先于旧源码；旧源码只作函数及依赖参考。未经独立验收的改动不能进入新的功能基线。

**执行入口**：§3 是当前事实与修复保护项；§5、§13 是职责/行数/源码迁移；§7、§14 是构建与物理边界；§8 是 R0–R9 工程优先顺序；§15–17 是综合 S 验收；§19–21 是防回归流程、模型分工与首批派单。配套总提示词及任务包模板在本文末尾链接。先派一个小任务包，不把整份方案交给弱模型自由改全仓。

## 1. 总目标与评级定义

### 1.1 总目标

在不改变已有用户功能、请求协议、模型格式、参数默认值、推理质量、持久化数据、鉴权语义、取消/重试语义和设备准入规则的前提下，完成以下四件事：

1. 保留当前已修复行为，关闭重新确证的剩余问题，补齐生产接线、native 构建边界和证据绑定；
2. 把 UI、Local API、Worker 的真实生产调用收口到同一套 Application Facade、Owner 和 Runtime；
3. 把 JNI、engine、session、request、callback、error、pipeline 和构建 target 做成物理可验证的单向边界；
4. 用可重复构建、功能等价、故障注入、真机长稳、发布完整性和供应链证据关闭 S 级门禁。

S 级不是“文件变少”或“编译通过”。S 级要求：**功能等价可证明、资源生命周期可证明、所有生产入口真实共用路径、失败可诊断、构建可复现、真实设备证据可追溯**。

### 1.2 评级分层

| 评级 | 必要条件 |
| --- | --- |
| A | 逻辑分层和主要 owner 建立，编译与单元测试通过，但仍存在未接线路径、未验证设备链路或构建不完整 |
| A+ | 主要生产路径已收口，核心错误/生命周期有保护测试，native target 物理化，但真实故障和发布证据不完整 |
| S-C | 代码复杂度、依赖方向、JNI/native 边界、错误模型达到目标，且没有未接线的生产 Facade |
| S-R | S-C 之上，真实 UI/API/Worker、取消/重试/崩溃恢复、故障注入和长稳通过 |
| S-O | S-R 之上，Release、签名、SBOM、供应链、可重复构建、产物绑定和发布回滚均通过 |

当前不得宣称 S：源码确认 `ChatExecutionFacade` / `ImageGenerationFacade` 未接入生产链，`BackendRuntimeOwner` 为 skeleton，native 大文件未完全迁移；真实设备验证已有进展，但完整故障矩阵、跨模态、多进程资源与 Release 证据尚未闭环，工作树也不具备干净可复现发布条件。

“修复所有问题”落实为：当前问题台账和后续发现的问题全部有责任人、复现、修复、回归证据；发布范围内已知功能、数据、安全、资源正确性问题全部关闭。不能承诺绝对没有未知 bug，不能通过改写评级或隐藏场景代替修复。

## 2. 不可改变的行为合同

### 2.1 功能合同

必须保留：

- 聊天、流式输出、上下文顺序、工具调用、图像工具、取消、重试、会话持久化和恢复；
- 生图、参数、批量、进度、取消、失败、重试、结果校验、资产提交、图库关联和恢复；
- 模型下载、导入、断点续传、校验、加载、切换、卸载、目录扫描、后台任务和恢复；
- Local API 的路径、鉴权、请求字段、默认值、幂等、SSE 顺序、响应内容、错误码和取消；
- 数据库、文件、密钥、资产、模型目录、历史 ID/关系、迁移和升级兼容；
- 前后台切换、进程重启、旧回调隔离、资源释放、失败可观察性和恢复语义。

重构只能改变责任位置和内部依赖，不能改变上述外部行为。行为不确定时，以旧源码、现有协议测试、characterization fixture 和真实生产行为为参考，不自行创造新业务规则。

### 2.2 设备准入硬规则

- 禁止使用芯片、机型、认证设备、profile、allowlist 或 whitelist 隐藏/阻断下载、导入、加载或运行；
- 未知设备必须走 generic compatible fallback，由真实 native load/graph/execution 结果决定成败；
- 硬件检测只能用于推荐、transport 和本地调优；
- “未测试”“未知芯片”“没有本地 profile”“未认证”都不能成为拒绝理由；
- `AGENTS.md` 和 `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 只能本地存在，不能 stage、commit、push 或进入交付包。

### 2.3 迁移硬规则

- 不得用 stub、fake、Preview 或 admission-only Facade 冒充生产完成；
- 不得并行保留两套真实执行路径；兼容层必须有调用方、删除条件和截止阶段；
- 先保护当前修复版，再补修确证问题和迁移；先真实接线并验收，再删除旧路径；
- 不以编译通过、单测通过、文件数量增加或行数下降单独宣称完成；
- 不使用 `git reset`、`git checkout`、`git clean` 或覆盖其他 Agent 的改动；
- 任何既有 dirty/untracked 文件都先登记，再按 owner 处理，不擅自删除。
- `E:\model\MAC` 永远只读，不在参照目录构建、写日志、移动文件或安装依赖；如需 A/B，按授权在独立目录重建参照制品。
- 本轮仅写方案文档，不执行生产代码修复、构建、安装、Git stage/commit/push。

## 3. 当前基线与关键风险

以下数字是 2026-10-11 当前工作区只读观测值，执行 R0 必须重新生成带哈希的 manifest。本轮没有运行构建/业务测试，历史 PASS 仅为有来源的历史记录，不能当作本轮重新验收。

### 3.1 Kotlin / 应用层

| 文件 | 当前行数 | 主要问题 | 最终目标 |
| --- | ---: | --- | ---: |
| `core/engine/.../LocalChatRunner.kt` | 3,623 | 执行、session、流式、资源和错误混合 | 原入口收为薄适配；拆后单文件 250–800，硬上限 1,000 |
| `core/engine/.../McaInferenceService.kt` | 3,162 | inference、生命周期和状态耦合 | 每个 owner 300–800，硬上限 1,000 |
| `core/engine/.../RuntimeParameters.kt` | 2,131 | schema、解析、校验、序列化混合 | schema/parser/validator/serializer 各 250–700，硬上限 900 |
| `app/.../CorePortAdapters.kt` | 2,504 | 多领域 adapter 聚集，易形成反向依赖 | 按领域拆分，每个 200–600，硬上限 800 |
| `app/.../MainViewModel.kt` | 499 | 行数已达标，但仍持 engine/业务装配责任 | 通常 350–600，目标 ≤600，硬上限 850；直接 engine/repository 为 0 |

MainViewModel 的 499 行不代表解耦完成，必须通过调用图和生产接线检查。350–600 是预计规模而非最低行数，不为达区间而补长文件。所有预算都不要求填满；更小且职责完整即可。行数门禁只在 owner、依赖和行为证据同时成立时有效。

### 3.2 Native 当前规模

| 文件 | 当前行数 | 真实责任问题 | 最终目标 / 硬上限 |
| --- | ---: | --- | ---: |
| `qnn_native_bridge.cpp` | 952 | JNI 外壳已变薄，但需继续只保留边界职责 | 300–500 / 700 |
| `mnn_native_engine.cpp` | 7,725 | JNI、engine、session、请求、采样、扩散逻辑聚集 | 原文件保留兼容入口 ≤500 / 700；逻辑全部迁入 owner/pipeline |
| `native_engine.cpp` | 1,586 | llama JNI/运行时/推理生命周期仍混合 | 入口 ≤500；runtime/engine/session 各自 ≤1,000 |
| `stable_diffusion_bridge.cpp` | 1,401 | bridge 与生成 pipeline、资源和回调混合 | JNI ≤500 / 700；pipeline 每个 500–1,000 / 1,200 |
| `qnn_semantic_pipeline.cpp` | 3,876 | 语义流程仍为大聚合文件 | 拆成 5–7 个文件，每个 400–900 / 1,100 |
| `qnn_executable_graph.cpp` | 1,689 | graph 创建、tensor、cache、执行和诊断混合 | graph owner/executor/cache/memory 各 400–900 / 1,100 |
| `qnn_conditioning_pipeline.cpp` | 1,549 | conditioning、token、tensor 和阶段控制混合 | 每个阶段 400–850 / 1,050 |
| `qnn_model_execution_pipeline.cpp` | 1,377 | model execution、回调、错误和输出混合 | executor 500–900 / 1,100 |

预算不是要求把代码压缩到不可读。迁移后总行数可以增加，因为合同、错误、恢复、测试和诊断代码应当显式存在；但任何超限文件必须有职责说明和架构审查，不得仅用注释解释。

### 3.3 当前工程缺口与待验证风险

1. `ChatExecutionFacade`、`ImageGenerationFacade` 的 KDoc 明示 admission-only；尚无生产构造点，也没有驱动真实 backend。现有产品执行链仍需保护，不能换成这两个空执行入口；
2. `BackendRuntimeOwner` 尚为 skeleton，创建的 engine `isReady` 恒为 `false`，不能直接替代现有真实 loader；
3. `CallbackRouter.clear()` 代际竞态已有修复；accepted queue 尚需明确容量、背压及文本完整性合同；
4. 逻辑请求准入/结算/清理已有统一 owner，真实 native drain/release ack、session binding、跨进程 incarnation 尚需接通并验证；
5. `catch(Throwable)`、`runCatching().getOrNull()`、`getOrDefault()` 需要逐站点分类；语法存在不自动等于 bug，未解释的业务吞错必须修；
6. 类型化错误、trace/correlation-id 尚未贯通全部 UI/API/Worker → Facade → Runtime → JNI → native → 返回链；终态回退读模型的富错误保真仍需验证；
7. 多个 native 新文件未纳入真实 CMake target或存在重复实现。先做 symbol/功能归属，再决定迁入或删除，不把磁盘文件存在当完成；
8. contract model、部分生产注入、单模态 soak 尚不能证明完整故障矩阵、混合长稳和 Release；
9. modified/untracked 生产输入较多，必须建立完整字节基线和 source→target→APK 对账。仅 HEAD 不能重现当前修复版；
10. prefix cache 晚 writer、closed writer 等边界为待验证项；本轮未复现，不能直接宣称当前产品存在这些 bug。

### 3.4 已修复、仍需补强、待复现必须分开

本版源码确认，以下均为**已有修复保护项**，不得重新按未修派单：

- `RuntimeSupervisor.admitRequest(requestId, consumer)` 在 `lifecycleLock` 内统一 running/epoch/attempt、registry、cancel registration、route subscription 和 `Accepted`，失败按 identity 回滚；Facade 不再在准入后另行注册；
- `settleRequest/releaseRequestResources` 统一结算、unregister cancel、recordOutcome、unsubscribe、evictTerminal；旧 cleanup 不应影响复用 requestId 的 successor；
- shutdown 已采用 cancelAllActive → drainAll → callback clear → registry clearAll → cancel clearAll → 尝试关闭所有 backend；“全部尝试关闭”不等于真实 SDK 物理释放已验证；
- `CallbackRouter` 已有 `recordLock/routerGeneration/RouteState.publishedGeneration`，阻止 clear 后在途 completion 复活旧档案；
- `EngineSessionOwner` 已用 lifecycle lock 串行 close/acquire，lease 为跨线程 CAS token、幂等 release。**close 后保留在途 lease，等 holder 释放，是保护资源的设计，不能为计数归零强制释放物理在途资源**；
- `BackendRuntimeOwner.createSession()` 已 `putIfAbsent`，重复 ID 明确冲突，不覆盖旧 session。

历史切片报告 `artifacts/architecture/20261010-r1-runtime-lifecycle/runtime-admission-terminal/SLICE-REPORT.md` 记录：engine 488 tests /0 failure /2 skipped，app 2,417 tests /0 failure /8 skipped；新增保护用例 15+5；engine+app 编译警告 73 条，切片写集内 0。该报告是逻辑切片验收，**不是本轮复跑，也不是生产 Facade/native ack/全设备链路认证**；跳过项仍需挂账。其压力轮数 400/300/2,000，不自动满足本文所有边界 ≥1,000 次要求。

prefix cache 已有 generation stamp，但 `storePrefixCache()` 未携带调用方 identity。执行者先沿真实 caller 证明旧 writer/close 交错是否可达；可达且违反合同才按最小修复派单，不能凭接口外观重写 cache。终态 read fallback 也须比较 `ErrorEnvelope` 的 code/trace/cause/时间，不能只保留状态就算等价。

最新已核验候选 APK 索引：`artifacts/repair/20261010-audit-fix/apk/MCA-qa-sdxlfab-debug.apk`，大小 **414,539,495 B**，SHA256 **`7c3c6b97fb4650466ee3388c6d96793ca0ba208c84521f6de7ae0cc3bcba83e5`**。对应 `artifacts/apk-build-records/debug-20261010-181624-749Z.json` 记录构建 passed，完成 UTC `2026-10-10T18:39:33.2444261Z`，sourceProvenance worktree SHA256 `50cdd63212bac3911a1a2eeb88e1fa26fb33d44bb2c284695780e6f75dc61cbb`。**这里只绑定候选文件与历史构建记录，尚未核验完整当前树与该 APK 等价**；R0 必须继续对账。

`architecture-final-report.json`、旧 `build-provenance.json` 及 `UNIFIED-ISSUE-TABLE.md` 各有不同时间截点。后者记录生产注入 5/5、121 分钟/413 次单模态 soak、host 38 pass+1 skip、lint 196，但不能直接用于上述候选 APK 全功能验收。构建记录初始 `installStatus=not_attempted/deviceSmokeStatus=not_run` 也不能否认之后其他 Agent 的测试；逐条核对 APK hash、日志、时间窗和真实设备。

历史人工记录曾提出 chat/image 孤儿状态、FGS、worker 内存/PID 重启候选。用户说明目前 bug 暂时修完，本版将其作为**复测场景**，不继续认定为当前未修产品 bug。只有当前修复版同 APK/全 PID/workload 确证复发，才新建 REGRESSED；峰值、缓存和 lmkd 需因果分析，不只看主进程或一个 memory 数字。

### 3.5 扩大后的工程债务覆盖范围

2026-10-11 生产源扫描还发现下列聚合文件。数据类、目录表、UI 和执行文件风险不同，行数大不自动等于 bug，但均须责任检查：

| 文件（相对 `E:\MAC`） | 当前行数 | 切分目标 | 单文件目标 / 硬上限 |
| --- | ---: | --- | ---: |
| `api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt` | 2,661 | transport、router、auth、SSE、idempotency、response 独立 | 250–650 / 800 |
| `api/local/src/main/java/com/muyuchat/api/local/ImageGenerationApiEvidenceValidation.kt` | 2,619 | schema、binding、各 backend evidence validator、error mapper | 250–650 / 800 |
| `api/local/src/main/java/com/muyuchat/api/local/ImageGenerationApiContract.kt` | 1,365 | request/response types、parse、validate、serialize | 250–650 / 800 |
| `core/modelstore/src/main/java/com/muyuchat/core/modelstore/ModelStoreRepository.kt` | 2,259 | catalog、download state、import、transaction、reconcile | 300–750 / 900 |
| `app/src/main/java/com/muyuchat/mca/LocalImagePromptLanguage.kt` | 2,528 | parser、AST、validation、token weighting、serializer | 250–650 / 800 |
| `app/src/main/java/com/muyuchat/mca/SdxlImagePhaseProtocol.kt` | 2,116 | phase types、request mapper、handoff、receipt validator | 300–700 / 850 |
| `app/src/main/java/com/muyuchat/mca/LocalImageGenerationOperations.kt` | 1,716 | request、dispatch、cancel、settlement、asset commit | 300–750 / 900 |
| `app/src/main/java/com/muyuchat/mca/composition/McaAppContainer.kt` | 1,579 | Runtime/Chat/Image/Model/Content/API wiring；根容器只装配 | 根 350–650 / 800；分域 250–600 / 750 |
| `app/src/main/java/com/muyuchat/mca/LocalImageWorkerService.kt` | 1,449 | Android service shell、transport、command、worker runtime | shell 250–500 / 650；runtime 350–750 / 900 |
| `app/src/main/java/com/muyuchat/mca/ImageExecutionProfileJson.kt` | 1,406 | schema、legacy parser、validation、serializer | 250–650 / 800 |
| `app/src/main/java/com/muyuchat/mca/ImageExecutionProfile.kt` | 1,313 | value types、execution policy、validation | 250–650 / 800 |
| `app/src/main/java/com/muyuchat/mca/LocalImageInputContract.kt` | 1,313 | input types、capability checks、file binding | 250–650 / 800 |
| `core/native/src/main/cpp/image_execution_math.cpp` | 1,316 | latent layout、CFG、resize、scaling、numeric validation | 300–700 / 850 |
| `core/native/src/main/cpp/mca/qnn/qnn_sdxl_unet_phase.hpp` | 1,316 | header 留 declarations；实现进 phase/tiling/execution TU | header 150–350 / 500；cpp 350–850 / 1,050 |
| `core/download/src/main/java/com/muyuchat/core/download/ModelScopeCatalogData.kt` | 3,176 | 将静态目录迁入受校验数据资源，保留 ID/字段/顺序 | loader 150–350 / 500；资源不靠硬拆行达标 |
| `core/download/src/main/java/com/muyuchat/core/download/ModelScopeTypes.kt` | 1,464 | 按 catalog/download/model 类型拆文件 | 200–600 / 750 |
| `feature/modelhub/.../ModelHubScreen.kt` | 2,995 | Route、filters、list、download、import、dialogs | 根 250–500 / 650；section 150–500 / 650 |
| `feature/settings/.../SettingsScreens.kt` | 2,960 | settings route、各分区、dialog；保持布局 | 根 250–500 / 650；section 150–500 / 650 |
| `feature/agent/.../AgentScreen.kt` | 1,855 | route、list、editor、run state、dialogs | 同上 |
| `feature/chat/.../ChatImagesWorkspaceOverlay.kt` | 1,770 | gallery、preview、job、parameters、dialogs | 同上 |
| `feature/chat/.../ImageInputSupportScreens.kt` | 1,632 | input types 对应 section，不重写规则 | 同上 |

R0 扫描覆盖全部手写 `src/main`、构建脚本和 third-party 本地补丁；测试、generated 和 vendor 原始源码单独统计。禁止通过移到 `.hpp`、生成器、字符串、资源或扩展函数逃避复杂度门禁。真正静态表迁资源必须以解析校验、版本和内容 fixture 保真。

行数口径为物理行（包含空行/注释），`.cpp` / `.hpp` 分别计；同时计算 code LOC、最长函数、嵌套深度、循环依赖和 owner 数量，不能只看其中一个指标。最终所有手写生产文件都有分类预算；任何普通执行文件 >1,200 行为阻断，静态合同/generated/vendor 必须由台账解释而非全局豁免。

## 4. 目标架构与唯一所有权

### 4.1 逻辑依赖

```text
Compose UI / Local API / Worker
        ↓ Intent / Command / Request
Application Facade / UseCase
        ↓
Domain Owner / State Machine
        ↓ Port
Runtime Owner / Data Owner / Native Provider
        ↓
JNI Boundary / C++ Runtime / Engine / Session
        ↓
Native SDK / Filesystem / Database / Network
```

固定方向：

```text
feature → contracts / application
application → domain / contracts / ports
runtime / data → domain / contracts
api:local → application gateway
app → composition root
JNI → native contract，不反向引用 UI 或 ViewModel
```

禁止：UI → Engine、UI → Repository、UI → Local API runtime、Local API → MainViewModel、Worker → MutableStateFlow、ViewModel → 具体 native 实现、native callback → Java UI 状态。

### 4.2 唯一 owner 表

| 资源/状态 | 唯一 owner | 允许的访问 |
| --- | --- | --- |
| engine handle | native `EngineOwner`（物理句柄）；Kotlin `RuntimeOwner`（逻辑引用） | 命令和只读快照；不是双方都能释放 |
| session/context/KV cache | native `SessionOwner`（物理资源）；Kotlin `EngineSessionOwner`（身份/授权） | session lease；native ack 后释放逻辑绑定 |
| 模型 load/unload/recovery | `RuntimeLoadCoordinator` | Runtime Facade |
| lease | `RuntimeLeaseRegistry` | 原子 acquire/release |
| request identity | `RuntimeRequestIdentity` | owner 内部生成 |
| chat execution | `ChatExecutionOwner` | UI/API/Worker 共用 |
| image job | `ImageGenerationOwner` | submit/cancel/retry/observe |
| persistence mutation | 专属 mutation owner | transaction command |
| UI state | `UiStateStore` | ViewModel 只读暴露 |
| one-shot effect | `UiEffectDispatcher` | subscription/consume |
| Local API lifecycle | `LocalApiApplicationGateway` | router 只做协议映射 |
| native callback | `CallbackRouter` | attempt-scoped event |
| native error | 现有 `StableErrorCode` + `ErrorEnvelope` | 接通边界映射，不字符串猜测，不另起平行体系 |

## 5. 具体拆分蓝图

下面数字是**设计预算与预计大小**，不是预测最终一行不差，也不是最低行数。先有职责/源码映射再定文件；推荐的中心预算如下，具体backend细项仍按后续表：

| 责任文件 | 推荐规划值（物理行） | 硬上限 | 核心限制 |
| --- | ---: | ---: | --- |
| JNI entry（每backend） | 400 | 700 | 只保留ABI边界与命令转发 |
| JNI converters / handles / errors | 各300 | 600 /650 /600 | 通用部分优先复用已有jni_boundary，不每backend复制一套 |
| backend loader | 600 | 850 | 动态库/SDK/失败回滚；不混推理 |
| engine owner | 700 | 1,000 | model/backend生命周期唯一owner |
| session owner | 600 | 1,000 | context/KV/generation/lease/安全close |
| request context / settlement | 各500 | 850 | 单attempt参数/取消/终态/cleanup |
| request state machine | 600 | 950 | 复用现有状态合同，不改外部status |
| callback router / archive | 各500 | 750 | FIFO/代际/终态/有界档案 |
| 单阶段native pipeline | 800 | 1,200 | 一个计算阶段及必要资源，无多层混合 |
| Kotlin业务owner | 600 | 通常900–1,000 | 复用原函数，不变成新God class |
| Facade | 400 | 650 | 完整命令、用例调用、只读结果，无native实现 |
| 领域adapter / mapper | 400 | 800 | 一种协议或领域，无反向UI依赖 |
| composition root | 500 | 800 | 只装配，分域wiring通常≤600 |
| MainViewModel | ≤600 | 850 | 只intent/state/effect与Facade |
| 普通header | 250 | 500 | declarations；不把实现搬进hpp躲预算 |

清单中的 `RuntimeRequestIdentity`、`SessionGeneration`、owner名称表示责任位置；已存在 `RequestIdentity`、`SessionGeneration` 等类型就直接复用，不为了凑200行新建平行类型。参数类型/静态合同和模板按分类预算审查。较少方法的Facade若仍>650行，先查参数/业务是否泄漏，不通过大量无意义转发层强行变短。

## 5.1 Kotlin 应用层

### Runtime

从 `LocalChatRunner`、`McaInferenceService` 和 `RuntimeParameters` 按以下边界迁移：

```text
RuntimeContracts / value objects              200–450
RuntimeRequestIdentity                        250–500
RuntimeParametersSchema                       250–500
RuntimeParametersParser                       250–600
RuntimeParametersValidator                    300–700
RuntimeParametersSerializer                   200–500
RuntimeOwner                                  400–900
RuntimeLoadCoordinator                        350–800
RuntimeUnloadCoordinator                      300–700
RuntimeRecoveryCoordinator                   300–750
RuntimeLeaseRegistry                          250–650
RuntimeSessionBinding                         250–650
RuntimeEventMapper                            250–600
RuntimeMetrics / diagnostics                  200–550
```

规则：engine/session 只由对应 Runtime owner 持有；参数先生成不可变 snapshot，再进入一次 load/request；旧 generation 不得写回新 cache；load 失败不留下半初始化句柄；close、cancel、unload 必须幂等。以上是责任清单，不是强制每项都创建独立 Gradle module 或一层转发类；已经存在合格 owner 时复用它。

### Chat

```text
ChatApplicationFacade                        250–500
ChatExecutionOwner                            400–900
ChatRequestAssembler                          250–600
ChatContextAssembler                          300–800
ChatStreamReducer                             300–750
ChatGenerationLifecycle                       250–650
ChatCompletionSettlement                      300–700
ChatSessionMutationOwner                     300–750
ChatToolOrchestrator                          350–850
ChatImageToolCoordinator                      250–650
ChatApiResponseMapper                         200–500
```

UI、Local API、Worker 均必须调用 `ChatApplicationFacade`，不得保留 API 专用或 ViewModel 专用执行器。上下文拼接顺序、工具调用顺序、chunk 去重、终态落库、取消和 retry 必须由 characterization tests 锁定。

### Image

```text
ImageApplicationFacade                       250–500
ImageGenerationOwner                          450–950
ImageRequestSnapshot                          250–550
ImageJobStateStore                            300–750
ImageWorkerCoordinator                        350–850
ImageProgressMapper                           200–500
ImageCancellationCoordinator                  250–650
ImageOutputValidator                          250–650
ImageAssetCommitter                            300–750
ImageCompletionSettlement                     300–700
ImageRecoveryCoordinator                      300–750
```

复用现有 `core/engine/.../contract/RequestState.kt`，不得另造一套 `Completed` 与 `Succeeded` 不一致的状态：

```text
Created → Accepted → Preparing → Running
                         ↓           ↓
                    CancelRequested → DrainingCallbacks
                              ↓
             Succeeded / Failed / Cancelled / TimedOut / Aborted
```

这是概览图，不替代现有 `canTransitionTo()` 的完整转移表。已有 Image Job 状态不强制重命名，通过 mapper 对齐 request lifecycle；外部 JSON/status 保持原合同。终态只能结算一次。Worker 只写 Job store 或发布领域事件，不写 UI Flow。结果必须先验证，再用可恢复事务提交资产和会话关联。

### Model / Content / Settings / Tuning / API

- `ModelLifecycleOwner`：目录、下载、导入、校验、load、切换、unload、recovery 分开；
- `ContentOwner`、`SearchUseCases`、`KnowledgeUseCases`：不持有 UI 状态；
- `SettingsOwner`、`ParameterTransactionOwner`：校验、默认值、持久化、回滚分开；
- `TuningOwner`：job、candidate、canary、rollback、commit 分开；
- `LocalApiApplicationGateway`：只做 auth、幂等、协议映射和生命周期，必须调用 Facade。

### MainViewModel 门禁

目标 ≤600（预计 350–600，无最低行数），硬上限 850；以下必须为 0：

- 直接持有 engine、Repository、Local API server、具体 native provider；
- `this@MainViewModel` 反向 adapter；
- `runBlocking`；对外暴露 `MutableStateFlow`；
- 业务 Mutex/Atomic 和 native cancellation 逻辑散落在 VM；
- 未接线的匿名 owner 或 second execution path。

普通 Kotlin 函数目标 ≤60、硬上限 100 物理行；复杂状态机/编排目标 ≤120、硬上限 180，且要按阶段提取函数、禁止混有 UI/IO/native 多层职责。普通 C++ 函数目标 ≤60、硬上限 120；推理循环上限 160，仅允许保留单个算法阶段，load/parse/execute/output/error 不能仍塞在同一个函数。头文件优先 declarations，通常 150–350、硬上限 500；模板/纯合同例外必须注册并测实际复杂度。

## 5.2 JNI 桥接拆分

JNI 的唯一职责：Java/Kotlin 参数读取、UTF/byte array 转换、handle 校验、生命周期入口、callback 转发、native error 转 Java。JNI 禁止模型策略、session 状态机、采样、tensor 计算、QNN graph 构建、业务状态和 UI/Worker 决策。

### JNI 文件预算

```text
<backend>_jni_entry.cpp                        300–500 / 700
<backend>_jni_converters.cpp                   200–400 / 600
<backend>_jni_handles.cpp                      200–450 / 650
<backend>_jni_callbacks.cpp                    250–600 / 700
<backend>_jni_errors.cpp                       200–400 / 600
<backend>_jni_lifecycle.cpp                    250–500 / 700
```

`qnn_native_bridge.cpp`、`mnn_native_engine.cpp`、`native_engine.cpp`、`stable_diffusion_bridge.cpp` 最终只能保留入口和必要兼容符号，每个目标 300–500、硬上限 700；MNN chat/diffusion 的入口可分到两个 JNI TU，各自 ≤700，但原文件必须删除或只保留一个入口集合，禁止重复导出。复杂实现迁入下列 native owner，不得把代码机械搬到另一个巨型文件。

## 5.3 Native engine / session / request

### Engine / backend

```text
BackendRuntimeOwner                           400–800 / 1,000
BackendLoader                                 300–700 / 850
EngineOwner                                   400–800 / 1,000
EngineCapabilities                            250–550 / 700
EngineLifecycleCoordinator                    300–700 / 850
```

Engine owner 唯一拥有 model handle 和 backend resource。load、ready、drain、unload、recovery 有显式状态，不用布尔值伪装完整生命周期。`isReady` 必须来自真实 loader/engine probe，不能恒 false。

### Session / lease

```text
SessionOwner                                  400–800 / 1,000
SessionContext                                300–650 / 800
SessionLease                                  300–650 / 800
LeaseRegistry                                 300–700 / 850
SessionGeneration                             200–450 / 600
SessionCleanup                                250–600 / 750
```

同一 `sessionId` 重复创建按原业务合同返回 existing 或显式冲突，禁止悄悄覆盖或擅自改变用户 session ID。close 先关闭准入并 cancel/drain；**确认 native 停止后**才释放 lease、KV cache 和 native context，最后清理 callback route/cancel/session binding。释放失败进入可观察 pending/lost 状态，不静默吞掉；不能直接把 `activeLease` 清成 null 后允许复用仍在运行的资源。

### Request / state

复用现有 `RequestIdentity.kt` 和 C++ `request_identity.hpp`，每个真实请求必须贯通下列字段，禁止字段已定义但实际始终为默认 0/空串：

```text
requestId
attemptId
runtimeEpoch
sessionGeneration
workerIncarnation
nativeSequence
traceId
```

native request 文件预算（数字指 `.cpp`；对应 `.hpp` 只留必要 declarations，普通 header 硬上限 500）：

```text
request_contract.cpp                          250–550 / 700
request_parser.cpp                            300–700 / 850
request_context.cpp                           300–700 / 850
request_state_machine.cpp                     350–800 / 950
request_cancellation.cpp                      250–600 / 750
request_settlement.cpp                        300–700 / 850
```

超时使用 monotonic clock。旧 attempt 不能结算新 attempt；应用级终态 exactly once；物理释放由 native ack / 确认 worker death 决定。逻辑失败/断连先保留取消和 tombstone，不能因 HTTP 已返回而提前销毁资源。状态能被观察、持久化、恢复和审计。

身份元组由提交方和 Runtime 一次绑定并以 immutable snapshot 传递；事件序号另递增，不反过来改变 attempt。epoch/generation/incarnation 应匹配当前对应 backend/session/worker，不能拿另一 session 的全局 generation 过滤，也不能只拒绝“小于当前”而允许无来源的未来 identity。server 不能信任客户端伪造内部 epoch/handle。

## 5.4 Callback / event router

```text
callback_contract.hpp                          ≤400
callback_router.cpp                            250–600 / 750
callback_sequence_guard.cpp                    200–500 / 650
callback_terminal_archive.cpp                  250–600 / 750
callback_backpressure.cpp                      200–500 / 650
callback_shutdown.cpp                           200–500 / 650
```

必须是单 drainer、FIFO、attempt-scoped、sequence 去重；terminal 先落 archive 再投递；archive 有界；sink 抛错映射为诊断错误。shutdown 设置 closed + generation，shutdown 后在途 completion 不得重新插入 terminal record。`clear()` 不能简单清空容器绕过在途回调。

## 5.5 MNN / QNN / SD pipeline

### MNN

将 `mnn_native_engine.cpp` 按真实责任拆为：

```text
mnn_jni_entry                                ≤700
mnn_runtime_owner                             400–900 / 1,100
mnn_engine_owner                              400–900 / 1,100
mnn_session_manager                           300–700 / 850
mnn_request_parser                             300–700 / 850
mnn_stream_router                              250–600 / 750
mnn_sampling_strategy                          300–800 / 950
mnn_token_boundary_guard                       200–500 / 650
mnn_prompt_cache_manager                      300–700 / 850
mnn_chat_pipeline                              500–950 / 1,150
mnn_diffusion_pipeline                         600–1,000 / 1,200
mnn_diffusion_assets                           300–700 / 850
mnn_tensor_copy                                250–600 / 750
mnn_debug_trace                                250–600 / 750
```

采样、token boundary、prompt cache、tensor copy 和 debug trace 必须由单元/host/native contract 覆盖；不能因为旧实现是匿名 namespace 就把所有 helper 留在一个 TU。

### QNN

将当前大文件继续拆成：

```text
qnn_jni_entry                                 ≤700
qnn_runtime_owner                             400–900 / 1,100
qnn_system_symbol_loader                      300–700 / 850
qnn_bundle_manifest                           250–600 / 750
qnn_graph_builder                              400–900 / 1,100
qnn_executable_graph                          400–900 / 1,100
qnn_graph_executor                             500–950 / 1,150
qnn_tensor_memory                              300–700 / 850
qnn_tensor_quantizer                           300–700 / 850
qnn_context_binary_cache                       300–700 / 850
qnn_conditioning_tokenizer                     350–800 / 950
qnn_conditioning_tensors                       350–800 / 950
qnn_semantic_request                            350–800 / 950
qnn_semantic_stage_runner                      450–900 / 1,100
qnn_semantic_output                             300–700 / 850
qnn_model_execution_pipeline                    500–950 / 1,150
qnn_preview / diagnostics                       250–600 / 750
```

重点：不能只把 `qnn_native_bridge.cpp` 变薄而把 3,876 行转移到另一个 `qnn_semantic_pipeline.cpp`。每个新文件必须有清晰输入、输出、资源 owner 和测试。

### Stable Diffusion

```text
sd_jni_entry                                  ≤700
sd_runtime_owner                               400–900 / 1,100
sd_model_loader                                300–700 / 850
sd_request_parser                              300–700 / 850
sd_scheduler                                   400–900 / 1,050
sd_conditioning                                400–900 / 1,050
sd_unet_executor                               500–1,000 / 1,200
sd_vae_decoder                                 400–900 / 1,050
sd_output_writer                               250–600 / 750
sd_callback_and_cancel                        250–600 / 750
```

任何 pipeline 文件超过 1,200 行必须暂停迁移并进行责任审查，不允许通过宏或匿名 namespace 隐藏复杂度。

## 6. 错误模型与可观测性

### 6.1 统一错误类型

优先扩展并接通现有 `ErrorEnvelope.kt`、`contract/error/StableErrorCode.kt`、`ErrorCatalogRegistry.kt` 与 C++ `mca/contract/stable_error_code.hpp`、`error_envelope.hpp`。不要在旁边再建一套互不相通的 `McaErrorCode`。下列名称表示错误分类目标，不要求逐个建立继承类：

```text
McaErrorCode
McaDomainError
McaNativeError
McaTransportError
McaPersistenceError
McaCancellation
McaTimeout
McaLifecycleError
McaValidationError
McaResourceExhausted
```

每个错误至少包含：`code`、`stage`、`retryable`、`userVisible`、`traceId`、`requestId`、`attemptId`、底层 cause 摘要。native 内部不能只返回字符串；JNI 不能吞异常；Kotlin 在可恢复边界捕获预期异常并立即映射。`CancellationException` 原样传播或按已定义取消合同结算，不能当成功/普通失败吞掉；`VirtualMachineError` 等致命错误不包装成可重试业务成功。安全的顶层/JNI/cleanup 边界可 catch-all，但必须做最佳努力清理、保留 cause、重新抛出或返回明确失败，不要求机械清零所有 `catch(Throwable)`。

### 6.2 禁止的错误写法

- 业务路径无理由使用 `runCatching().getOrNull()` / `getOrDefault()`；
- 用错误字符串比较代替 error code；
- catch 后只打印日志继续返回成功；
- fallback 不记录原因、阶段和 trace；
- 把设备未知、未测试、无 profile 映射为“不支持”。

每个 fallback 必须有：触发条件、保留行为、日志级别、稳定 code、测试和删除/长期保留决定。

### 6.3 trace 贯通

`traceId` 从 UI/API/Worker request 创建，经过 Facade、Owner、Runtime、JNI、native request、callback、错误、持久化和响应；日志必须可按 `traceId + requestId + attemptId + runtimeEpoch` 串联。跨进程 local service 必须保留 correlation-id。

### 6.4 参数和 JSON 合同

Kotlin 复用项目现有结构化 serializer；C++ 优先复用现有 nlohmann JSON 和 contract parser，不用 substring/regex/手写 lexical scan 解析结构化 JSON。迁移时先固定 missing/null/default、数值边界、重复 key、unknown field、Unicode 和 legacy 参数 fixture；不能因为 parser 换库而改变旧兼容行为。校验和 default 不得在 API、UI、JNI 各自再实现一份。schema 的单一 authority 和各 backend 有效参数 snapshot 有版本/hash。

## 7. CMake 与构建目标物理化

最终 target 图：

```text
mca_contract
mca_common
mca_errors
mca_observability
mca_jni_boundary
mca_request
mca_callback
mca_runtime_lifecycle
mca_llm_runtime
mca_mnn_runtime
mca_mnn_chat
mca_mnn_diffusion
mca_qnn_runtime
mca_qnn_graph
mca_qnn_semantic
mca_sd_runtime
mca_native
mca_mnn_native
mca_qnn_native
mca_sd_native
mca_prompt_handoff
```

要求：

1. 每个 target 显式 source list、include directory、compile definitions 和 link dependencies；
2. 禁止循环依赖；调用依赖为 JNI/shared → backend/pipeline → runtime/request/callback → contract/common，底层不依赖 JNI；
3. 新文件不能仅存在于磁盘，必须被正确 target 编译并在 manifest 中列出；
4. 产品配置缺少必选 SDK/库/源码时明确失败；显式 host/JVM 测试、无该能力的 ABI 配置可以保留 capability-disabled 构建，但必须准确报告不可用，不能返回成功或作为全功能交付；
5. host test 与 Android target 尽可能复用同一核心实现；
6. `MCA_QNN_TYPED_BINDINGS_REQUIRED=ON` 时缺 SDK/header 必须明确失败；
7. tokenizer、MNN、llama、QNN、SD 的版本、路径和哈希进入 build provenance；
8. Release 与 Debug 不得误把旧目录产物当当前源码产物；
9. APK 内 `.so` 必须逐个与 provenance 的 build output hash 绑定；
10. source-set gate、dependency gate、preprocessor balance gate、artifact gate 必须在 CI 执行。

## 8. 工程代码优先的执行路线

每个阶段必须遵循：

```text
保护测试 / characterization
→ 保留既有修复，补修当前确证问题 / 按冻结设计迁移
→ 编译
→ 单元 / host / protocol 测试
→ 真实链路验证
→ owner / dependency / source manifest 检查
→ 删除旧路径
→ 记录证据、风险和回退点
```

### R0：基线冻结

生成 source manifest、逐文件行数、调用图、target/source 清单、dirty/untracked 清单、依赖版本、APK provenance、功能台账和未验证清单。冻结当前修复版 B0，不能只记 HEAD。R0 不修改业务代码；最小基线完备即可进入第一个工程任务包，不先做全仓长篇排版。

退出：基线可重现，所有当前事实有路径、哈希和时间。

### R1：修复版防回归与工程门禁（确证新问题才补修）

先复跑现有真实生产链和 §3.4 已修保护测试，核对历史台账状态。已修 admission/clear/cleanup/duplicate session 不重复改造。先把写集/hash 检查、构建独占、source→target→artifact 对账、差异/测试结果核对做成可失败的工程守卫（见 §19–21），随后处理重新确证的缺陷、残缺 native helper 和构建接线。prefix writer、timeout clock、富错误保真及跨模态历史场景先测/定位再决定是否改。

真实 bugfix 尽可能有修前失败→修后通过；无法可靠构造 RED 的竞态需明确记为构造性修复+确定性/压力证据，不能伪造 RED。纯迁移是基线通过→迁移通过，不要求制造失败。禁止为清 lease 标志提前释放仍执行中的 native 资源。

退出：当前修复版基线通过；本切片已知功能/数据/安全/生命周期缺陷关闭；工程守卫的负例确实 FAIL；生命周期及终态保护通过，物理 release 不冒充逻辑计数。若该切片必需真实模型/设备验证缺失，则不切生产、不删旧路径；可继续其它互不依赖且已验证切片，不扩大未经保护的迁移。

### R2：JNI + request + callback 垂直切片

选择一个真实 MNN chat 或 SD 请求，把 JNI converter/handle/error、request context、callback router、terminal archive 从原路径拆出，并保持旧 JNI 外部签名。新路径必须被真实 UI/API/Worker 至少一个入口调用。

退出：新 target 编译；旧入口调用图可解释；host/native/protocol/真实链路通过；旧重复逻辑删除。

### R3：真实 Runtime owner 接线

实现 `BackendRuntimeOwner` 的真实 loader、ready、unload、recovery 和错误映射；把 `isReady=false` skeleton 替换为真实状态。接通 `EngineSessionOwner`、lease registry 和 native handle。

退出：生产调用点非 0；真实 model load/execute/unload 通过；失败无半初始化 handle；未知设备走 generic fallback。

### R4：Chat 统一入口

UI、Local API、Worker 全部改为 `ChatApplicationFacade → ChatExecutionOwner → RuntimeOwner`。锁定 context、stream、tool、cancel、retry、persistence 的前后行为。

退出：生产构造点和调用点有证据；不存在 API-only / VM-only execution path；协议 fixture 与真实链路一致。

### R5：Image 统一任务链

按 Job 状态机接通 submit/cancel/retry/observe、Worker、资产提交、恢复和 Local API。终态 exactly once，资源和 lease 清理可验证。

退出：单任务、并发拒绝/排队、取消、失败、恢复、重试和真实图片结果通过。

### R6：Native pipeline 和 target 物理拆分

按本文件的 engine/session/request/callback/pipeline 预算迁移 MNN、QNN、SD。每迁一个 target，就执行 ON/OFF 构建和 source manifest 校验。不得把复杂度转移到新巨型文件。

退出：JNI 文件 ≤700；pipeline 无超限未审查文件；所有 production source 纳入 target；无未接线新文件。

### R7：Model / Settings / Tuning / Local API 收口

统一目录、下载、导入、参数事务、调优、API auth/idempotency/error mapping。保留既有 `/health`、`/v1/models` 合同，任何鉴权改变必须另行授权和版本化。

退出：所有入口共用 owner；协议和数据迁移测试通过；无设备 allowlist。

### R8：错误、trace、可靠性证据

最终关闭类型化错误、correlation-id、故障注入矩阵、取消/超时/worker death/进程恢复/跨模态/并发/长稳。错误、trace、故障保护从 R1 起每个切片就做，R8 不得成为拖延理由。真实 production injection 不得用 model-only 代替。

最低建议门槛：

| 指标 | S 级门槛 |
| --- | --- |
| 生产关键路径功能测试 | 100% 必选场景通过 |
| terminal exactly once | 0 duplicate / 0 missing |
| 取消后资源 | native stop/drain ack 或 worker death 确认后，0 active lease / 0 stale route；超时则隔离不复用 |
| crash-free soak | 24h 连续；旗舰发布前 7d 预发布观测 |
| ANR | 验收关键路径 0 次；发布 SLO 见 §15，不能仅凭测试 0 次计算生产比例 |
| chat/image soak | 至少 1,000 次混合请求，0 未解释失败 |
| 故障注入 | 生产矩阵 100% 有 PASS/明确受控失败 |
| artifact binding | APK 内每个生产 `.so` 可追溯到当前 source/build |

这些是最低门槛，不是允许掩盖设备资源问题的平均数。每次失败必须有分类、日志和修复/豁免记录。

### R9：删除旧路径与发布

删除死 adapter、旧执行器、未接线 Facade、重复状态和临时构建逻辑。未纳入 target 的文件先做 symbol/功能去向表；能力已迁移并经 owner 确认后才删，不能一刀删除其他 Agent 的 untracked 文件。完成 Release、签名、Android R8 shrinker（区别于本方案 R8 阶段）、SBOM、依赖漏洞扫描、license、可复现构建、回滚包和最终审计。

退出：干净 checkout 可构建；source/target/artifact manifest 一致；最新 APK 安装、UI、认证 API、长稳和发布校验均绑定同一 SHA256。

## 9. 每个切片的验收证据

每个切片必须提交：

1. 变更文件和 owner 表；
2. 旧调用点扫描与新调用点扫描；
3. source/target manifest、编译命令和构建输出；
4. characterization、unit、host/native、protocol 测试结果；
5. 至少一个真实 UI/API/Worker 生产入口证据；
6. 资源/状态/错误/trace 证据；
7. 未验证项、失败项、环境限制和回退点；
8. 删除旧路径的依据。

`PASS`、`FAIL`、`SKIP`、`NOT_VERIFIED` 必须严格区分。旧 APK 或旧设备 smoke 不能作为最新 APK 证据；contract model 不能冒充真实故障注入；编译通过不能冒充生产通过。

## 10. 最终 S 级门禁

以下任一项不满足，评级最多 A+，不能宣称 S：

- 有生产 Facade 未接线或 `isReady` skeleton 仍在真实路径；
- JNI、pipeline、engine/session 文件超限且未完成责任审查；
- 任一生产 source 未纳入真实 target；
- terminal/lease/cancel/route cleanup 无测试或存在重复结算；
- 错误仍主要依靠字符串、静默吞错或 catch 后返回成功；未分类宽泛捕获不为 0；
- UI/API/Worker 仍有重复执行逻辑；
- 存在 device/chip/profile allowlist 阻断能力；
- 故障矩阵只有 model-only，没有真实 production injection；
- 最新 APK 未完成同版本安装、UI、认证 API 和长稳验证；
- Release 产物不能由干净 checkout 重建或 `.so` 无法绑定当前 source；
- `AGENTS.md` 或本地策略测试被发布。

## 11. 最终交付物

```text
docs/architecture/MCA-S-GRADE-DECOUPLING-EXECUTION-PLAN-20261011.md
docs/architecture/MCA-S-GRADE-AGENT-EXECUTION-PROMPT-20261011.md
artifacts/architecture/<run-id>/source-manifest.json
artifacts/architecture/<run-id>/target-manifest.json
artifacts/architecture/<run-id>/owner-matrix.md
artifacts/architecture/<run-id>/behavior-diff.md
artifacts/architecture/<run-id>/test-report.json
artifacts/architecture/<run-id>/fault-injection-report.json
artifacts/architecture/<run-id>/device-soak-report.json
artifacts/architecture/<run-id>/build-provenance.json
artifacts/architecture/<run-id>/release-integrity.json
```

最终报告必须单独列出：已完成、未验证、失败、环境限制、已知残余风险。不得为了晋级 S 删除未验证行。

## 12. 原源码参考与功能等价方法

### 12.1 不重新造业务的参考优先级

优先级为：**当前已验证修复 > 对应功能的既有真实实现 > 旧实现及传递 helper > 已冻结协议/数据 fixture**。原项目不是整仓“绝对正确”，历史缺陷不能因追求等价重新引入；每个差异必须注明 `PRESERVED`、`INTENTIONAL_BUG_FIX` 或 `UNEXPLAINED`。后者不得进入交付。

已存在参考台账：`E:\MAC\docs\repair\MCA_FUNCTION_PARITY_REFERENCE_LEDGER_20261009.json`。只读确认以下 commit 在本地可解析，但它们不是整仓 known-good 认证：

| 用途 | 本地 reference commit | 使用方法 |
| --- | --- | --- |
| 当前 HEAD | `afcc9c0ae7309b13efdd0b9844751529b4f1aa41` | HEAD 与 dirty 修复分别记录，不能把 HEAD 叫当前完整源码 |
| 最后 native 抽取前 | `76348a5b55baa49052c5a02674c29baf4307d5a6` | 查 GGUF/QNN/SD 函数及 helper 的行为 |
| 最后 VM 抽取前 | `02fc01810a5fb4fb5fbda2e61f1f20dd47351d01` | 查功能入口、导入、UI 到执行的调用链 |
| 首次物理抽取前 | `b6f8e0cd3bd762cb78f1c4c1ad5a336711a91866` | 辅助查丢失能力，不整仓回滚 |
| 指标修复保护 | `aa0f9bf1c70a199ef952cdc657a223c331329479` | 防止恢复旧错误指标 |

执行时用 `git show <ref>:<path>`、`git log -- <path>`、`git diff` 和原项目只读函数对照。恢复必须函数级、依赖感知，不整文件覆盖当前已修复版本；禁止直接执行现有 `tools/refactor/shrink_bridge.py` 或 VM slimming 脚本批量搬运未经审核的逻辑。

### 12.2 功能台账必须逐行绑定

每项记录：feature ID、旧入口、旧函数及 helper、数据副作用、默认值、JNI 完整 JVM descriptor、C++ 导出/注册、目标 owner、新入口、保护测试、真实模型资产、产物哈希、验证状态、回退点。**迁移前后 JNI 数量相同不是等价证明**，每个签名、重载、ABI、可见性、异常和实际调用都要匹配。

必选功能集：

- GGUF 文本/视觉/CPU/OpenCL、prefix cache、persistent cache、MTP/speculative 的原有能力；
- MNN 文本、视觉、greedy/随机采样、stop markers、UTF-8 边界、prompt cache、SD1.5/Sana/SDXL conditioning 的已实现能力；
- QNN graph/context 执行、SDXL phase、conditioning handoff、progress/cancel、img2img/inpaint/ControlNet/Ultrafix 的已实现分支；
- SDCPP generate/upscale、LoRA、textual inversion、seed、CFG、scheduler、分辨率、输入/蒙版、preview、取消；
- LiteRT-LM、GenieX/QAIRT 与云端原有 runner 的加载、取消、unload/recovery；不能因为本文重点 native 而遗漏；
- tools、联网搜索、知识、助手、内容导入预览、数据升级、通知、前后台、Local API/SSE/幂等、下载恢复。

先从源码确认存在，再确定必选矩阵；缺模型/凭据写 `NOT_VERIFIED`，不把低频功能删出范围。

### 12.3 A/B 对照

固定工具链、third-party 补丁、ABI、模型/包/输入 SHA256、seed、参数、上下文、线程、backend、热状态、缓存状态和运行时间窗。每个切片比较：输入快照、native effective 参数、状态转移、callback 次序、错误、取消、最终文件、持久化差异。

- 纯函数、JSON/协议和确定性 tokenization 采用精确 equality；
- 确定性 CPU backend 先要求 token ID/像素一致；
- 硬件浮点非确定性使用 R0 预先冻结的逐 tensor 误差/NaN/Inf/shape、图像质量和生成统计容差，不因测试失败临时放宽；
- PNG 有效、路径存在、非空文本、`ok=true` 都只是低级校验，不能证明参数和算法保真；
- MTP 必须用确有对应层的模型；普通 GGUF 通过不替代 MTP；QNN HTP 真实 graph 不用 CPU mock 替代；
- 预先存在的错误响应保持协议，明确 bug fix 允许内部修正，但不得偷偷收窄 `/health`、`/v1/models` 的既有免认证合同。

## 13. Native 逐来源迁移清单与边界合同

### 13.1 预计拆分规模

当前四个入口文件合计 11,664 物理行。目标四个入口主 TU 合计约 1,200–2,000 行（每个 300–500，硬上限 700）；若 MNN diffusion 单独入口，五个入口合计约 1,500–2,500 行。其余逻辑保留于约 20–35 个**参与本轮迁移的责任 TU**，其中许多已有，不是要新造 35 个框架类。预计新增净文件约 10–20 对 `.cpp/.hpp`，R0 根据现有 owner 去重后核定，不以数量做 KPI。

四个 QNN 大 pipeline 合计 8,491 行，预计形成/复用约 12–18 个阶段/graph/conditioning/receipt 实现，每个通常 350–850 行。它们和四个入口迁移的 helper 有交集，不能相加作为新增行数。总 production LOC 不设强制缩减比例；以原算法完整保留、每个职责可单测和唯一 owner 为目标。

| 原来源/符号锚点（执行时重定位行号） | 目标及具体预算 | 保留事项 | 第一批保护测试 |
| --- | --- | --- | --- |
| MNN `g_llm`、`destroy_llm_locked`、`loadModel/unloadModel` | 复用 `mnn_engine`/`mnn_backend_owner`；loader 450–800，engine 400–800，各硬 1,000 | 初始化/失败回滚、线程参数、backend/lib 选择、unload 顺序 | load 失败每一 allocation stage；二次 unload；unload 与执行冲突 |
| MNN `g_visual_stream`、`g_visual_producer`、`join_visual_producer` | `mnn_visual_execution` 450–850/1,050；stream router 300–600/750 | 不能持 `g_mnn_mutex` join；保留停止信号与超时隔离 | producer stall、跨线程停止、UTF-8 split、队列溢出、join 失败 |
| MNN `g_generation_active`、`beginCompletion`、`generateNextChunk` | chat pipeline 550–900/1,100；request 350–650/850；stream reducer 300–650/800 | 同一轮参数 snapshot、greedy、stop marker、完整 output | greedy 温度=0、long stream、停词跨 chunk、cancel/retry、输出对照 |
| MNN `mnn_prompt_cache_*`、KV history helper | 复用 `mnn_prompt_cache_manager` 350–750/900；session 350–750/900 | 文本/多模态 cache 隔离、generation、assistant suffix、history 统计 | hit/miss、旧 writer、context invalidation、cancel cache commit |
| MNN text encoder asset/CLIP/tokenizer/weighted prompt | 修复后复用 `mnn_diffusion_assets` 400–800/1,000；tokenizer 350–750/900；SD15 encoder 450–850/1,050；SDXL encoder 500–900/1,100 | BOS/EOS、77-token 分支、权重、embedding dtype/shape、receipt、坏 tokenizer 失败 | 中文/emoji、坏 JSON、negative/positive binding、字节级 handoff |
| MNN `DirectMnnUnetSession`、`run_mnn_sd15_interpreter_direct` | session 450–800/1,000；UNet execute 500–850/1,050；SD15 pipeline 550–950/1,150 | 原 MNN Interpreter API、timestep、scheduler.step、VAE scaling、tensor ownership | timestep/数值固定 fixture、tensor shape、CPU/OpenCL、部分初始化清理 |
| MNN `MnnSanaSession` 和原 generate 分支 | Sana adapter/pipeline 450–850/1,050；diffusion entry 350–500/700 | 原第三方 runner，不自行实现 Sana 算法 | 真资产生成、cancel、输出和 effective config |
| GGUF `g_model/g_context`、`environment`/speculative ownership | 复用 `llama_model_loader`、`llama_context_manager`、`llama_speculative_engine`；各 400–850/1,050 | 已修 MTP owner 不能重新借临时对象所有权 | 模型加载 rollback、speculative owner lifespan、真实 MTP、cache equivalence |
| GGUF runtime config、OpenCL dispatch、stats、formatter | 复用现有 `mca/llm/*`，每个 250–750/900；JNI 350–500/700 | 已验证 OpenCL 独立库名、fallback、metrics、模板、mmproj | GPU trace、CPU fallback、model mismatch、persistent cache、Unicode |
| QNN `QnnExecutableGraph`、`open/execute/reset`、mmap/DL handle | graph resource 500–850/1,050；executor 450–850/1,050；loader/cache 350–700/850 | SDK provider/version、graphInfo 生命周期、失败后 poison/隔离 | 每个 acquire/release 失败点、invalid context、graph execute failure |
| QNN `qnn_context_binary_cache` 与 `qnn_system_symbol_loader` | 两者先 symbol-owner 去重；若保留 cache，350–650/800 | 禁止删除 unique `ScopedDlHandle`/mmap 行为；禁止把重复 TU 直接重新加进 CMake | 生产实现 host 测试、`nm` 唯一定义、file mutation、release failure |
| QNN semantic pipeline | orchestrator 450–700/900；request 350–700/850；conditioning binding 400–800/1,000；denoise 500–900/1,100；output receipt 350–700/850；diagnostics 250–550/700 | model fingerprint、真实参数、CFG/scheduler、cancel/preview、输出 evidence | 错参数、missing assets、stale generation、每 stage fault、真实图片 |
| QNN conditioning pipeline | 复用 JSON parser；token/weight reader 350–650/800；asset evidence 400–800/1,000；handoff consumer 350–700/850 | hash/fingerprint、TOCTOU、prompt-to-encoder receipt、shape、分支 | asset 变化、错误 binding、negative 分支、Unicode、校验拒绝后重试 |
| QNN model execution pipeline | ControlNet 350–700/850；UNet/inpaint 450–850/1,050；SDXL UNet 450–850/1,050；VAE/tiled decode 450–850/1,050；img2img encoder 350–750/900 | 保留原 CFG、latent scaling、tile overlap、mask 和 ControlNet residual layout | 原值对照、shape/NaN、inpaint/ControlNet/Ultrafix 逐参数 |
| SDCPP `generate_impl`、context key、model load、progress、upscale | 复用 `mca/sd/*` owner/context key/phase/request/output transaction；orchestrator 500–850/1,050；JNI 350–500/700 | 不手写 third-party UNet/VAE/sampler 替代现有 `stable-diffusion.cpp` | context reuse、LoRA/TI、input/mask、cancel、upscale、output commit failure |

预算不要求拆第三方源码。上面的 `sd_unet_executor` / `sd_vae_decoder` 仅当 MCA 自有逻辑确实需要独立适配时创建；底层 SDK 已一次性执行 generation 时保留它，不能为了图示重写算法或增加无意义转发层。

### 13.2 全局状态迁移

`g_model/g_llm/g_context` → native engine/session；`g_generation_*`、pending chunk、UTF-8 tail、stop marker filter、debug trace → request/attempt；`g_mnn_diffusion_*` → image runtime/request。锁和保护的数据一起迁移，不允许一个文件保留 mutex、其他文件通过 `extern` 随意改 state。

要求：

- engine 的 model/options snapshot 生命周期长于 session，session 长于 lease/request；
- request 结束后不可保留第三方 context 的裸借用指针；
- 模型卸载关闭准入 → cancel/drain → session cleanup → model/backend release；
- RAII destructor `noexcept`，失败释放由显式 `close_checked` 返回 typed error；
- 不能用 detach、强行 free、吞异常恢复 ready；backend release 不可确认时标为 Poisoned/ReleasePending，由既有隔离 worker 或安全恢复路径处理；
- native 非抢占 SDK 的在途 call 必须完成或确认进程死亡才允许 reuse，不等待 UI 线程，不在数据锁内调用外部 sink 或 join producer；
- JVM lock、C++ mutex、worker RPC 的 lock order 有独立表；跨进程不假设 Kotlin mutex 可保护 native 另一进程。

`RuntimeOwner` 是每个实际运行进程/受隔离 backend 的唯一资源 authority，不是把多个进程合并成虚构 singleton。主应用持逻辑 job 和 RPC identity，worker 持物理 engine/session；用 incarnation/lease ack 协调。UI/API/业务 Worker 共用提交用例，但执行型 native service 接收已 accepted command，不能再次调用 submit 生成第二份 request 或递归排 Worker。

### 13.3 JNI ABI、引用、异常和线程

1. 保留完整 JVM descriptor、类/package、重载 JNI 名称或 `RegisterNatives` 表、可见性和 `.so` 名称，建立 DEX → descriptor → native export/registration → packaged ABI → 真调用对应表；
2. `JNIEnv*` 只能在所属线程使用，不缓存后交给 producer；跨线程用 `JavaVM` + scoped attach，只有本线程 attach 的才 detach；
3. `GetString*`、`GetByteArray*` 等用 RAII 成对 release；loop callback 用 scoped local frame；`GlobalRef` 有唯一 owner，shutdown/drain 后 delete，不能提前删除在途 sink；
4. UTF 转换复用现有 `mca` codec，保留中文、emoji、surrogate 和 split UTF-8；`NewStringUTF` 使用 modified UTF-8，不能把任意标准 UTF-8 直接塞进去；
5. 保留 `null`、空 chunk、结束标志的旧差异；不可用 `""` 替换错误/终态导致轮询无限循环；
6. C++ exception 不穿过 JNI；每个入口有统一 boundary wrapper；存在 pending Java exception 时按 JNI 规则清理，不能继续做不合法 JNI 操作；catch-all 映射仍不能返回成功；
7. `noexcept` 入口内的 allocation/JSON/SDK 调用必须在边界捕获，不让 exception 触发 `std::terminate`；
8. handle 不直接暴露可伪造裸指针；用 registry + generation 或等价校验，close identity-checked/幂等，stale handle 拒绝必须是真资源状态，不是设备 profile；
9. CheckJNI、重载导出门禁、重复 invoke、callback GC stress 和 thread detach 必须验证；callback 支持投递与轮询两种原合同，不强制改协议。

### 13.4 Request admission、settlement、shutdown 的原子合同

在现有 `RuntimeSupervisor/RequestRegistry/CallbackRouter` 上收口，不另起第二套 supervisor：

```text
admit(identity input, sink):
  under lifecycle transaction:
    verify running / epoch / duplicate / concrete resources
    allocate attempt / reserve registry
    register cancellation + attempt route + session binding
    publish Accepted only when all reservations succeed
    on failure compare-remove this attempt's reservations
  outside lock:
    schedule actual backend execution
    if rejected by executor, settle and clean this attempt
```

事务中禁止跑 model load、native inference、user callback 或阻塞 join。shutdown 与 epoch advance 使用同一事务边界；若采用 token + revalidation 两阶段协议，必须证明线性化点和 rollback 等价。当前 admit 已完成 cancel/route registration 收口；保留它，仅在真实执行接入时补齐必要 session binding/调度失败结算，不整套推倒重写。

```text
terminal settlement:
  compare attempt → CAS terminal → durable/bounded outcome record
  record native stop/release ack (or pending/lost)
  commit output/session if successful, preserve failure record otherwise
  stop input producers / request cancel if required
  deliver terminal at most once per consumer
  after native drain: release lease, unregister cancel, retire route/session binding
  evict live registry while retaining bounded queryable outcome
```

“exactly once”指应用内终态提交/资产事务和单个 consumer 的逻辑投递，不能承诺进程崩溃与网络重试下物理消息只送一次；跨进程/HTTP 用 durable job、idempotency key 和 replay 去重。终态档案有容量和 TTL，查询协议保持原合同。lease release 必须有 token compare，旧 cleanup 不能释放新 attempt。

shutdown：封闭 admission → stamp shutdown generation → snapshot attempts → cancel → 有界 drain → 记录各 attempt 结果 → 按 native ack 释放/隔离 → 清 route/cancel/live registry → 关闭 backend；每个 close 都尝试，汇总所有错误，不能只记第一个然后丢失其余。in-flight terminal completion 必须检查 router generation，不把 clear 后的 record 重插进去。

callback accepted queue 初始建议上限 256 events 或 2 MiB（任一先到）；terminal 保留专用容量，文本 chunk 不丢失；预览/纯进度仅在现有合同允许时 coalesce，否则超限明确失败并取消。容量由 R0 workload 和延迟实测修订后冻结；不得无限增长，也不得在 UI 主线程等缓慢 consumer。

最小并发测试：用 barrier/latch 控制 dispatch-register 与 shutdown、terminal-delivery 与 clear、close 与 lease acquire、epoch advance 与 admit、旧 writer 与新 generation、旧 cleanup 与 requestId reuse；每个至少 1,000 次随机调度压力 + 确定性边界测试，报告超时和死锁。不能只靠 sleep 制造“有时通过”。

### 13.5 Engine / Session / Request 的真实执行接线合同

当前 `RuntimeContracts.kt` 的 `BackendPort/EnginePort/SessionPort` 有 create/unload/lease等合同，**还没有完整真实执行入口**。集成者先盘点原runner/native执行API，复用其完整参数及能力；按使用方需要补内部执行Port/adapter，而不是在Facade里另写推理算法，或给现有Port添加默认成功实现。真实load前不能把`isReady`改成true冒充接通。

下表是需要覆盖的内部语义状态，非要求新增同名公开enum、重命名现有状态或改变JSON。已有正确状态/标志通过映射复用；必要内部扩展由L2/L3工程包冻结后实现，弱worker不能自行设计：

| owner与阶段 | 准入及资源规则 | 必需失败/交错验证 |
| --- | --- | --- |
| backend初始化/关闭 | SDK/library/provider由backend owner持有；close开始后不再publish新engine | createEngine与shutdown交错，晚创建对象归还owner/关闭，不能loadedEngines漏挂 |
| engine未加载/加载中 | 构造不等于loaded；model/options快照固定；部分资源由loading scope管理 | 每个acquire失败rollback；重复load/切model合同与原码一致 |
| engine可执行 | 真native load/probe成功且未poison才ready；model长于所有session | execute与unload竞争、无ready假成功；真实backend fallback |
| engine停止准入/排空/释放 | 阻新session/request，cancel并等待实际drain；外部阻塞操作不在数据锁里 | createSession与unload交错；排空失败保留ReleasePending/隔离，不free在途 |
| session开放/租用中 | 唯一context/KV；一个实际execution lease或原合同允许的调度；generation有源 | duplicate冲突保原session；旧generation不写新cache，旧lease不清新lease |
| session逻辑关闭/物理待释放 | close后不再acquire；旧lease由holder安全结束；native resources仍有唯一owner | close/acquire、cancel、late callback、release失败、holder晚归还 |
| request accepted/执行/终态 | supervisor逻辑身份收口；真实executor接收同一不可变identity/options；终态与asset commit按原合同 | executor拒绝/抛错、成功/取消/超时/worker death、retry、晚cleanup |
| 物理释放确认/worker丢失 | ack必须匹配backend/session/attempt/incarnation；死亡确认区别于RPC超时 | fake/stale/重复ack拒绝；超时不当已死亡；隔离后恢复新身份 |

Kotlin逻辑Registry/cancel/route清理和native物理资源清理不能共用“map已经空了”作为证据。若逻辑终态提前evict（如当前`settleStreamFailure`），真实Runtime执行owner必须仍持取消句柄、lease、回调GlobalRef及ReleasePending/tombstone，直到native stop/drain ack或确认worker死亡；否则接线后会丢失停止/释放能力。复用现有隔离runtime/journal/cleanup结构，不另造第二套业务请求状态机。

命令→执行Port→原backend函数→callback→终态查询的每一字段都做映射；不得丢maxTokens/stop/sampling、图像输入/negative/seed/scheduler/LoRA等已有参数。Port调用的load/execute/unload是实际工作，Fixture里模拟Accepted/Completed不是接线。所有主入口真实调用共用用例；native worker只消费已accepted命令，其incarnation来自真实进程启动信息，不能默认恒1。

错误返回必须保留原公开code/HTTP/status/SSE合同，内部`ErrorEnvelope`与native stage可补诊断；archive/query应保留同一attempt的富错误，bounded TTL与原查询生命周期冲突时先审合同。close汇总所有失败并尽力关其它owner，不能将第一错后剩余未释放的资源报告为全成功。

## 14. 编译器强制边界、构建矩阵与工程清理

### 14.1 Gradle 物理化：最迟 R7 强制完成

保留现有 `:core:engine`、`:core:native`、`:core:sd-native`、`:core:modelstore`、`:core:download`、`:api:local` 和 feature 模块，避免全仓重命名。建议新增/核实复用以下纯 JVM 模块，按实际纯净职责迁移，不制造空模块：

| 模块 | 内容 | 允许依赖 | 必须禁用 |
| --- | --- | --- | --- |
| `:core:contracts` | 公共 identity/event/error/Port/value types | Kotlin/必要协议库 | Android/Compose/SDK/native imports |
| `:core:domain` | 纯规则、状态机、validator、领域值对象 | contracts | Context、Service、JNI、ViewModel |
| `:core:application` | Facade、UseCase、业务 Owner、事务策略 | domain/contracts/必要 coroutine | app、UI、具体 runner/repository |
| `:core:runtime` | 纯 supervisor/registry/lease/router 及 loader/session ports | domain/contracts | Android、JNI 具体类、app |
| 现有 `:core:engine` | Android runtime adapters、backend runner、worker transport | runtime/domain/contracts/native/sd-native | app/feature/API |
| 现有 data 模块 | DB/file/download/settings 实现 | domain/contracts | app/feature/ViewModel |
| `:api:local` | HTTP/router/auth/SSE mapping | application/contracts | app、ViewModel、具体 engine |
| feature 模块 | Route/ViewModel/UI | application/contracts/designsystem | concrete engine/native/data |
| `:app` | composition、平台入口、service/worker shells | 各实现模块 | 业务算法堆入 container |

Port 定义归使用方边界，adapter 向纯契约依赖；application 不能为了 new RuntimeSupervisor 依赖 Android engine。以现有清晰接口迁移为先，确需额外 module 才补，不把每个 UseCase 拆成 module。

R3 纯 runtime 物理边界到位；R4/R5 Facade 从 app 搬入 application 并接线；R7 全图退出检查，不能到 R9 还只有 package 声明。Kotlin `internal`、C++ private headers/hidden symbols、Gradle dependency graph 与 import/classfile gate 同时约束。domain/contract/application 的 Android、Compose、JNI、具体 SDK import 为 0，发布 CI 每次检查。

### 14.2 CMake target 依赖与现有图修正

最终设计采用 STATIC/OBJECT 实现库 + 薄 SHARED JNI，contracts 为 INTERFACE。不追求创建更多 `.so`，保持现有加载名称/SONAME 和客户端合同。

| target 组 | 依赖（consumer → dependency） | 构建责任 |
| --- | --- | --- |
| contract/errors/common | errors → contract；common → contract | 无 JNIEnv/Java/Android UI；稳定 code、路径、codec |
| request/callback | → contract/errors/common/必要 observability | 不依赖 jni_boundary；纯逻辑可 host test |
| runtime_lifecycle | → request/callback/contract/errors | 移除当前 runtime_lifecycle → jni_boundary 反向耦合 |
| llm/mnn/qnn/sd runtime | → runtime_lifecycle/common/errors | owns SDK/backend；SDK dependency PRIVATE unless public header 真需要 |
| mnn_chat/mnn_diffusion | → mnn_runtime/request/callback | sampler/tokenizer/encoder/pipeline；macro 与 SDK includes 设在这些 target |
| qnn_graph | → qnn_runtime | context/tensor/executor；semantic 不得反向被 graph 依赖 |
| qnn_semantic | → qnn_graph/request/callback | denoise/conditioning/output orchestration |
| sd_runtime | → 稳定 diffusion SDK + 自有 contract | 在 sd-native 独立图构建，不假设另一个 Gradle 模块的 CMake target 自动可见 |
| jni_boundary | → contract/errors/common | UTF、RAII、JNI error/handle；仅此组和 JNI shared 需要 JNI headers |
| mca_native/mca_mnn_native/mca_qnn_native/mca_sd_native | → 对应 runtime/pipeline + jni_boundary | 唯一 JNI 导出、Android log/dl 适配、thin boundary |
| mca_prompt_handoff | → contract/common | 共享 handoff registry/ABI；不得改为各 DSO 一份 STATIC 造成数据不相通 |

当前 sd-native CMake 重复编译 `process_runtime_host.cpp`、`backend_runtime.cpp`；多个 JNI `.so` 链接 STATIC runtime 可能各自有独立 singleton。不能假设 `ProcessRuntimeHost::instance()` 跨 DSO 自动唯一。首选 Kotlin 每进程 supervisor 作为 authoritative epoch，通过 request/loader port 注入 native，native state 明确是 backend-local；若确需 native 进程级 registry，采用有验证 ABI 的共享实现并证明跨库同一实例。禁止用 linker 顺序偶然合并符号。

PIC 必须用于所有进入 shared 的 static/object library；SDK 宏、include 和编译选项属于真正编译该 TU 的 target，不能仅设在最终 shared 后认为上游生效。用 target compile database 验证；可适用时 `-Wl,--no-undefined`、hidden visibility 和 explicit JNI exports。保留 OpenCL/GenieX ggml 库名隔离和既有有效 native patch；它们不是可随意“简化”的遗留项。

`qnn_context_binary_cache.cpp` 当前故意未编译，因为其多个定义与生产 `qnn_system_symbol_loader` 重复；MNN 八个抽取模块当前因恢复单一真实 monolith/缺 header 被排除。正确顺序是 helper 对照 →修 include/接口/ownership → 编译新 TU → 把调用改到新 TU → 删除 monolith 对应 body → 符号与功能对照；**禁止一口气恢复八个 source 或直接删除它们**。测试必须改为生产唯一实现，不单测残缺副本。

### 14.3 最小构建矩阵

| 配置 | 必需结果 | 不得冒充 |
| --- | --- | --- |
| host pure core（Windows；可用 Linux） | 实际 request/callback/lifecycle/math 核心编译和测试 | source grep 代替行为测试 |
| llama ON / OFF | ON 真链接；OFF 真实支持的 capability-disabled 分支不含 llama TU/header 依赖 | OFF 通过宣称 GGUF 通过 |
| QNN typed ON + 完整 SDK | configure/compile/link、真 export、设备 graph | `__has_include` 偶然拾取本机其他 SDK |
| QNN typed ON + 缺/坏 SDK | 明确 configure failure、可诊断 code | 默默 fallback stub |
| QNN explicit disabled | 仅测试/声明无能力的配置可构建并准确不可用 | 作为全功能生产包 |
| MNN chat/diffusion ON | 固定 MNN split runtime、tokenizer、diffusion source 真链接 | 未有 SDK 的 JVM pass |
| tokenizer ON/OFF | ON 完整 tokenizer.json；OFF 不声称 tokenizer 能力且保留明确失败合同 | 用简化 tokenization 返回看似成功 |
| SDCPP | native Release/Debug、真实 SDK、所有 adapters | 只编 JNI shell |
| Android arm64-v8a | 全能力产品 Debug + Release | x86 smoke 代替 ARM64/native SDK |
| Android x86_64 | 按既有支持范围声明 backend capabilities | 将 ARM-only HTP 声称在 x86 可用 |

不需要穷举全部 2^N 开关组合，但所有支持组合及缺依赖反例必须在 machine-readable matrix 明示。主产品能力不因 CI SDK 缺失而关闭。

### 14.4 CI 必须有生产 lane

现有 `.github/workflows/android-ci.yml` 清楚标注 QNN stub、MNN 未构建、非生产产物，保留其 JVM lane 价值。新增受控 Windows/self-hosted native lane：合法取得 QNN SDK，固定哈希与许可；复用 `tools/build-mnn-runtime.ps1`，构建 full JNI/Release；密钥和 SDK 不上传公开仓库。设备 lane 在一台代表性物理 ARM64 走 MainActivity 和认证 Local API；其他设备仅增加兼容覆盖，不作为功能准入白名单。

若 self-hosted 受控 runner 暂不可用，产物保留 NOT_VERIFIED，不能降测试门槛晋升 S。将 existing architecture/native gate、release verifier 与 fault runner 修成读取当前证据的 fail-closed 门禁；补负向测试证明删 source、漏 export、改 hash、unknown profile 被拒绝、缺报告、stale APK 时门禁确实失败。

### 14.5 编译警告与工具质量清零策略

历史“有大量 Kotlin 警告”不能用 lint 数量替代。分别收集 Kotlin compiler、C++ compiler/linker、Android lint、Gradle/AGP deprecation、third-party 和脚本解析/runtime warnings，绑定工具版本、源码 hash、行列、任务和日志。

- 首先修 nullability/恒真条件、缺 return、fallthrough、uninitialized、use-after-free 风险、API level、失效 coroutine、未消费 Result 和未使用的真实业务状态；
- 对 unused 逐调用图判定，不能删除回调/参数让警告消失却丢功能；
- 不 blanket `@Suppress`、改 lint baseline 或全局关 warning；第三方警告不盲改 vendor，独立记录 pinned source 和 owner；
- 自有手写生产代码最终 compiler warnings=0、lint error=0；低风险 lint warning 只有逐条书面解释/等效保护才可例外，High/Correctness/Security 项=0，未分诊项=0；
- `catch(Throwable)` 和 fallback 逐站点分类，未解释宽泛捕获=0、无日志无 code 的业务吞错=0；不是机械替换语法；
- 对修改的新 target 启用严谨 warning，确认存量问题修复后将可适用的自有 targets `-Werror` / Kotlin warnings-as-errors；不强行使 third-party 构建失败；
- 使用既有 build lock + content snapshot；全体 build/test wrapper 统一接入，直接 Gradle/CMake 绕锁要可检测。不能同时在相同 build/.cxx 目录开多个 Gradle；
- host CTest 按 generator 实际配置运行；运行依赖 DLL/so 由构建 `copy/runtime dependency` 自动布置，不靠每次手动复制；测试起不来与真正失败分别登记；
- wrapper 统一退出码、UTF-8 日志、stderr 处理、`NO_PROXY/no_proxy` 去重，不能把 stdout `BUILD SUCCESSFUL` 当唯一结果；
- `tools/refactor`、源码文本合同和架构脚本不得靠压缩代码/更新黄金字符串制造通过；修改门禁必须有“原本坏例会失败、修复例通过”的测试。

## 15. 综合可靠性、故障与性能门禁

### 15.1 SLO 的使用边界

下面是**拟定验收目标**，不是当前测得水平。R0 冻结 workload、设备环境、模型资产、输入、样本量和统计方法；不得由于失败就改目标。S-C/S-R 的工程验收可以先完成，S-O 的运营指标必须有足够预发布/生产观测。没有足够样本时写 `INSUFFICIENT_SAMPLE`，不把“0 次事故”当 100% 可靠性。默认不上传用户提示词/图片/密钥，不为了 SLO 强迫开启远程遥测。

| 指标 | 初始硬门槛/验收目标 | 测量口径 |
| --- | --- | --- |
| crash-free session | 发布目标 ≥99.95%；验收 workload 0 app/native 未解释 crash | main + chat/image worker + JNI tombstone；受控 kill 注入单独计，不混入正常 workload |
| ANR session rate | 发布目标 ≤0.1%；验收 UI 关键路径 0 ANR | ANR trace + ActivityManager/exit reason，不只查 logcat 一条关键字 |
| request 正常执行可靠性 | ≥99.9% 目标；验收 ≥1,000 次混合有效请求 0 未解释失败 | 非用户取消/故意坏包/预设冲突；所有拒绝和失败分类，不能删除分母 |
| terminal correctness | 0 missing/duplicate settlement；0 旧 attempt 污染新 attempt | 状态、落库、输出提交、callback/查询一致；断网允许 replay 不允许业务重复提交 |
| UI 主线程 | native/model IO 0；关键 UI action p95 ≤200ms，不含实际推理时间 | Perfetto/frame/input event；必要加载动画保持响应 |
| cancel receipt | UI/API 取消命令 p95 ≤250ms | 从输入到 native cancel signal 发布；不等于实际停止 |
| cancel drain | 合作式 backend 正常模型目标 p95 ≤5s；原 SDK 不可抢占者使用已冻结有界 timeout，初始 30s | timeout 后按旧隔离策略 RecoveryRequired/poison，禁止复用；不是无条件 kill 主进程 |
| worker recovery | process-death 检出到 metadata ready p95 ≤30s，不含大模型 reload | 新 incarnation、旧 attempt aborted、重新加载/重试成功 |
| TTFT、decode tok/s、image latency | 同源 A/B TTFT/image p95 退化 ≤5%，decode 退化 ≤5%；如基线极短同时列 absolute 差值 | 同模型/backend/seed/热状态；cold/warm 分开，不跨模型平均 |
| framework overhead | 上述性能不退化；router/mapper added time 记录 p50/p95/p99，trace 能分离 SDK 时间 | ≥30 warm + ≥10 cold；长稳有效样本再扩充 |
| 空闲 CPU/线程/FGS | quiescent 后无持续计算、没有无理由常驻 generation FGS；线程回落稳定 | main/worker 全进程；借口“idle state”不能掩盖 orphan job |
| trace coverage | 关键入口 100% 有非空 trace/attempt/epoch；错误能定位 native stage | 生产请求，不仅 fixture |

5% 为第一版性能门槛。对于设备热降频、后台干扰、SDK 随机性，按预先定义的重复/置信区间复核，不任意挑最快轮次。UI/API 响应时间和推理时延分开，否则 SDK 的长耗时会掩盖应用卡顿。

统计例子：在近似独立同分布且零失败的样本下，单侧95%失败率上界约`3/n`。1,000次零失败只能支持约0.3%的上界，不能据此宣称≥99.9%请求可靠性；该目标约需≥2,995次零失败有效请求，≥99.95% crash-free约需≥5,990个符合定义的session零crash。存在失败时用实际置信区间；持续同设备循环有相关性，还需分workload/时间窗复核，不能把循环次数伪装独立真实用户session。24h/1,000次是工程长稳最低门禁，7d是时间门禁，都不自动满足运营SLO样本门禁。样本不足保留`INSUFFICIENT_SAMPLE`，不阻断兼容设备准入，但不宣称S-O运营证据闭环。

### 15.2 资源泄漏与高峰内存

覆盖 main、`:local_chat`、`:local_image` 和其他实际运行进程；每 10s 采样 PID/incarnation、PSS/RSS、native heap、线程、FD、wakelock/FGS、GPU/HTP memory（工具可取得时）。权限不足或 PID 已死写 `INVALID/NOT_AVAILABLE`，不能写 0。PSS、RSS、SDK/GPU 指标分开，不直接相加当真实总使用量。

workload：先 warm-up 20 次，然后 10 组每组 100 chat/image mixed 请求，每组结束等 quiescent；另连续 24h 执行；至少 100 次 load/unload/switch、100 次 cancel/retry、30 次前后台/旋转、10 次受控 worker death 和 10 次进程恢复。复杂大模型过慢时在 R0 选择代表性真实模型组合，但所有原 capability 至少有正常执行和失败恢复证据，不能只测最轻模型。

初始稳定资源目标：

- 相同模型/缓存策略的 quiescent PSS，后 5 组中位数相对 warm baseline 漂移 ≤`max(64 MiB, 5%)`，无持续无界上升；用回归斜率及 SDK cache budget 解释，不用一个阈值掩盖增长；
- FD 差值 ≤5、线程差值 ≤2，且既有常驻线程有 owner；负载 peak 不使用 idle 阈值，但必须在预先定义的模型峰值预算内；
- 每轮 resource-release ack 后 lease、live route、cancel record、live registry 和 active job =0；保留的 terminal archive 不超过容量/TTL；
- 正常单/混合 workload 不发生由资源泄漏触发的 lmkd；真实资源不足允许结构化失败和恢复，不能靠限制设备名单解决；
- 如果图像模型+聊天并存超过设备真实内存，采用现有可复现的模型换出/加载顺序和请求级资源策略，不偷偷改并发合同；具体 runtime resource 不足可拒绝，请求/设备未经验证不能拒绝；
- load/unload 后的内存是否回 OS 由 allocator/SDK 决定，测“可重用”和“长期增长”两项，不强求每次 RSS 回零；单次 peak 增大也不能无证据直接定性泄漏。

### 15.3 真实故障矩阵

复用 `tools/fault-injection` 并按 production source 注入点执行；原 22 个 contract model 仍保留，但在报告中分成 `MODEL_TEST`、`PRODUCTION_INJECTION`、`DEVICE_EXECUTION` 三个独立维度，不覆盖计数。以下至少逐行验证，含正向对照、恢复和资产/数据检查：

| 故障组 | 场景 | 合同 |
| --- | --- | --- |
| load | 缺 binary、坏 tokenizer、截断 model、坏 context、SDK/provider mismatch | 真实失败、稳定 code、无半句柄，恢复后能加载 |
| request | duplicate id、旧 attempt callback、epoch/generation/incarnation 改变 | 不污染新请求；幂等与 conflict 原合同保持 |
| cancel/timeout | 排队取消、prefill/decode/denoise/VAE 各阶段取消、native stall | 正确终态、drain/隔离、有界恢复、lease 不提前复用 |
| callback/SSE | FIFO/重入、slow client、buffer overflow、sink throw、disconnect | 无丢失成功假象；取消/清理可追踪；terminal 可查询 |
| process | worker 死亡、binder/RPC 断开、主进程恢复、后台限制 | incarnation 更新、旧请求隔离、无永久 generating/FGS |
| resource | allocation failure、磁盘满、output write/rename fail、权限撤销 | 不提交半资产、不损坏会话、可解释失败 |
| crossmodal | chat→image→chat 多次切换、并发、多客户端、load+unload 与执行 | 无 orphan busy/idle 矛盾；CPU/FGS 回落 |
| native lifetime | 每 acquisition/release 点失败、重复 close、late cleanup | 不 UAF/double free、其它 backend 仍清理、failed release 不 pretend ready |
| persistence | job/资产/session 提交前后断电或 kill、升级中断 | 恢复幂等，旧数据完整，无假成功/重复文件 |
| input/security | malformed JSON、huge input、zip traversal/symlink、invalid paths | 边界拒绝，不越目录、不泄漏敏感日志、不改变设备准入 |

坏 tokenizer 在已注册 bundle 中被 fingerprint guard 先拦截，不能算 tokenizer 分支验证；改用新导入/未注册测试资产或受控 JNI harness，分别验证边界拒绝和真实解析失败，不关闭校验 guard 让测试通过。故障注入 hook 只在测试制品中，release 不打包 debug activity、注入指令或后门。

### 15.4 短稳到长稳的分级

每个切片先 30 分钟 targeted loop；Chat/Image 接线各 2h；R8 混合 24h；Release candidate 7d 预发布观测。连续单模态 121 分钟的既有结果有价值，但不等于 mixed load/unload、全进程泄漏和 24h/7d 通过。

一台代表性物理 ARM64 通过 MainActivity UI + 认证 Local API，即打开所有兼容设备能力；额外 API level、CPU/GPU/HTP、内存规格用于排查兼容性，不建立 per-device certification。x86 或 host test 不能代替 ARM64 真实执行。

## 16. 数据、安全、发布与持续维护

### 16.1 数据和资产事务

现有 repository/store/job journal/asset transaction 优先复用。跨 DB 和文件的“原子”不能靠口号：staging → 校验 → durable journal → 原子 rename/DB commit → reconcile；为每个 interruption point 写恢复测试。保留旧 ID、session 关系、默认值、字段、路径和 schema migration。

模型下载保留 resume、Range/ETag/hash、暂停/取消、partial file 和重试；导入保留目录规范、zip traversal/symlink、大小/格式校验，不因解耦弱化校验。删除 assets/session/model 先遵守引用和 in-use 合同，再提交；失败不能让 UI 认为已删除且磁盘/DB 实际未改。

升级测试从最老仍支持 schema、当前 schema、带真实历史的 fixture 执行新 APK；至少覆盖 app kill 发生在迁移中、file journal 中、worker 执行中、成功输出但 session 未提交时。旧版本回滚不一定可读新 schema，必须声明回滚与备份策略，不盲装旧 APK 清用户数据。

### 16.2 安全与隐私

- 审计 Local API bind 地址、token 存储/轮换、鉴权、CORS、OPTIONS、payload 限制、SSE 和路径；保持旧认证合同，改变公开 endpoint 必须用户授权/版本化；
- `traceId` 不是密钥，日志/报告不包含 bearer token、云凭据、用户原文/图片或未经脱敏的路径；public error 不泄漏内部 cause；
- HTTP auth、拒绝路径和 capability 查询有协议测试；loopback/LAN 的不同风险有文档；
- JNI/native 对 input 尺寸、整数溢出、tensor shape、文件长度、endianness 做边界校验，不只捕获异常；
- self-hosted 构建/SDK/签名密钥最小权限，测试 APK 不携带发布密钥/调试后门；
- Critical/High 可利用漏洞=0，SBOM 的漏洞、license 与 vendor patch 各有 owner/修复或经授权处置，不全局忽略依赖。

### 16.3 Release 与可复现构建

当前候选 audit-fix Debug APK 大小为 414,539,495 B（哈希见 §3.4）；不能拿 Debug+多 ABI 包大小直接评价最终 Release。R0 建立 arm64 Release 各库、DEX、resources、SDK 的分项基线；初始 RC 预算为 arm64 Release ≤250 MiB、单 slice 相对同能力基线增幅 ≤5%。预算待首个真实 Release 测量后在扩大开发前冻结；超限靠符号外置、R8、重复 SDK 去重、ABI 分发修，不删功能或库强行达标。

Release 任务：

1. 固定 JDK 21、Gradle/AGP/Kotlin、SDK/NDK/CMake、Rust/tokenizer/MNN/QNN 和 submodule hashes；包括 nested submodule dirty patch；
2. 合法 SDK 和本地 GenieX AAR 有可恢复的来源、license、hash、构建/补丁记录，不依赖匿名开发机绝对路径；
3. 开启并验证 Android R8 minification/resource shrinking（适用时）；JNI 类/方法、反射、序列化、Worker/service 用精确 keep rule，不用 keep 整仓规避问题；
4. 使用 Release APK 执行真实 UI/API/Worker/JNI/upgrade，不只对 debug 做验证；
5. native debug symbols 外置并绑定 Build ID；保留 crash 可符号化，不因减小包体失去排障能力；
6. 产物包含 build revision、content snapshot、工具版本、feature flags、compile database、各 `.so` hash、SBOM、签名证书 fingerprint、设备安装包 SHA256；
7. 同一冻结 source 在两个干净 build dir 重建；unsigned deterministic outputs SHA256 相同为目标；不确定 timestamp/path/archive metadata 按预先声明规范处理，差异必须解释；签名 payload/证书独立验证；
8. `apksigner`、manifest/package/version/ABI/ELF/JNI descriptor/exports/SDK stub marker/source coverage 全部验证；源码有关键修复字符串只是辅助，不能单独证明完整功能；
9. untracked production inputs 与 build manifest 逐文件交叉；用户授权提交时纳入完整实现，文档本身不授权 Agent `git add/commit/push`；local-only guard 永不纳入；
10. 保留带 SHA256 的测试 APK、Release candidate、symbols 和回滚说明，不把旧 APK 标成最新。

### 16.4 维护能力与团队可接手

S 级可维护性不只是代码行数，还要新人/第二名维护者能修问题：

- 每个 contract/owner/target 有 owner、领域边界、输入/输出、资源与错误合同；
- 关键决策写 ADR：跨 DSO epoch、session lease、cancel/drain、typed errors、handoff、data commit、public API；
- load/execute/unload/worker death 有 runbook，能从 request trace 到 native symbols 定位；
- 第二名维护者从干净环境按 runbook 构建 full APK，复现一个故障并关闭一次切片评审；不能只有原作者知道本机 SDK 和脚本；
- 每条保护测试链接对应风险，不用只扫源码字符串的几千条合同测试替代行为证据；
- 所有权/依赖/source coverage/警告预算由 CI 持续强制，防止后续“临时兼容”重新双路径；
- 主工程 gate 和报告由不同于修复者的人/Agent 复核；更新测试 golden/门禁需解释行为变化，不准为晋级改判据。

## 17. 问题关闭矩阵与阶段硬退出

R0 读取 `docs/repair`、`docs/architecture/audits`、`artifacts/repair/.../final/issue-table.json`、最新切片报告和生产源码。历史问题逐条更新为 `FIXED_PROTECT`、`OPEN_CONFIRMED`、`CANDIDATE_NOT_REPRODUCED`、`ENGINEERING_GAP`、`NOT_VERIFIED` 或 `SUPERSEDED`，不得把 20261010 旧描述直接派为未修。以下是规划/防回归重点，不声称静态审计已找出全部未知 bug：

| 编号 | 责任/问题 | 首个修复阶段 | 必须证据 |
| --- | --- | --- | --- |
| Q01 | 当前修复版/历史功能：先保护；重新确证回归才补修 | R0/R1 | B0 通过；新增 bug 有复现/原函数对照/修复后真实结果 |
| Q02 | FIXED_PROTECT：admit/cancel/route 已在生命周期事务内 | R1复测 | barrier、shutdown 交错、失败回滚、无后注册残留 |
| Q03 | FIXED_PROTECT：clear 后 completion 重插已加代际保护 | R1复测 | 阻塞在途 sink、clear、放行，0 错 epoch archive |
| Q04 | FIXED_PROTECT：close/acquire 互斥；ENGINEERING_GAP：真实 drain/ack | R1保护/R3接线 | close 后不发新 lease；旧 holder 安全释放；真实 ack 前不 free/reuse |
| Q05 | CANDIDATE_NOT_REPRODUCED：prefix 旧/closed writer | R1验证/R3 | caller 可达性、identity/generation/close 并发；证实违反才最小修 |
| Q06 | FIXED_PROTECT：重复 session 不再替换；真实 backend 生命周期待接线 | R1保护/R3 | conflict 不影响原 session；真实 create/unload/shutdown 资源证据 |
| Q07 | FIXED_PROTECT：registry/cancel/route 已统一清理；富错误读模型待验证 | R1保护/R3 | 查询状态/错误保真、逻辑资源清理、旧 cleanup 不影响 successor |
| Q08 | BackendRuntimeOwner 和两 Facade 未接生产 | R3/R4/R5 | production constructor/callsite +执行 trace +真实 load/execute |
| Q09 | JNI descriptor/UTF/ref/thread/exception | R2/R6 | export 全对账 + CheckJNI +真实调用 +Unicode/GC stress |
| Q10 | MNN 未编译残缺抽取、QNN 重复 symbol/非生产测试副本 | R2/R6 | source→target→symbol→APK 对账；测试生产唯一实现 |
| Q11 | 跨 DSO runtime singleton、global state 混杂 | R3/R6 | authority/epoch/instance 证据和 stale test |
| Q12 | errors/trace 定义存在但真实链没用 | R1 起/R8收口 | production error mapping/trace 100% 和 privacy 测试 |
| Q13 | 历史跨模态 orphan/FGS/worker memory 场景：当前修复版复测 | R1验证/R5/R8 | 同 APK、全 PID/time window；复发才登记 REGRESSED，因果证据 |
| Q14 | compiler warnings/diagnostics 逐站点复核，不沿用旧行号 | R1/R7 | fresh compiler 日志、当前符号定位、边界保护，不 suppress 消失 |
| Q15 | model-only、旧报告与新设备证据计数矛盾 | R0/R8 | 三维矩阵、原日志哈希、fresh manifest、反例门禁 |
| Q16 | 包/module 边界未由编译强制 | R3/R7 | Gradle graph +import/classfile gate +无循环 |
| Q17 | CTest DLL 布局、脚本/锁绕过、build pollution | R1/R6 | 从新目录一次运行成功，负向 gate + content hash |
| Q18 | Release/R8/SBOM/签名/供应链/复现 | R9（从R0准备） | full native Release +第二次重建 +安装/功能/升级 |

每阶段不能用“预算不足”自动延期：必需证据缺失则该切片停在 `PARTIAL_VERIFIED`，不集成未经验证的新执行路径。R1 重新确证的 P0/P1/功能退化未关闭，不进入受影响路径迁移；R3 未真实 loader 接线不切 R4/R5 生产；R4/R5 未功能对等不删旧执行；R6 未 target/source 对账不宣称 native 解耦；R7 未物理模块不宣称架构完成；R8/R9 未综合证据不宣称 S。

最终项目级 S 要求 release scope 的已知功能/数据/资源/安全 bug=0、重要未验证 capability=0、重复真实 owner/path=0、未接线生产 Facade=0、未分诊 warnings=0、无 trace 关键错误=0、全部 source coverage=100%。可接受的低风险 stylistic lint/vendor warning/文档遗留必须有明确例外，不包括上述正确性问题。

## 18. 多 Agent 执行与直接交付提示词

只在用户授权范围内安排并发；本文件不会自动创建 chat、向其他任务发消息、stage/commit 或运行构建。建议工作流：一个协调者维护 issue/owner/evidence 表，一个集成者独占 composition+CMake+Gradle+构建，一个只读审计者验收，其余按互斥写集修切片。禁止多人同时编辑大 monolith 或同一 CMake/Gradle；native backend 必须先冻结合同，再做各自 TU。

| 执行包 | 默认写集 | 非写集依赖/验收 |
| --- | --- | --- |
| E1 生命周期/请求 | `core/engine/.../runtime`、相关 contract、对应测试 | 不接触 SDK/JNI/CMake；与 E2 预先约定 lease/ack |
| E2 native JNI/资源 | 一个 backend 的 JNI +该 backend `mca/*` +host test | 同一时刻只一人编辑原桥；CMake 请求集成者修改 |
| E3 Chat 接线 | Chat Facade/Owner/assembly/stream/persistence +测试 | 等 Runtime 实际 port ready；UI/API/Worker caller 交由集成者统一切换 |
| E4 Image 接线 | Image Job/Worker/asset/recovery +测试 | 等 Runtime/JNI 合同；不覆盖 Chat shared state |
| E5 Model/API/参数 | 按一个领域划定具体文件清单 +测试 | server/contract 文件互斥，不同时修改 CorePortAdapters |
| E6 构建/模块集成 | settings/build/CMake/composition/manifest gates/CI | 单人写集，所有构建遵守同一 lock；接线在 slice ready 后进行 |
| E7 验证 | artifacts、device/fault/release harness | 默认不改业务源码；发现问题派给 owner，不降低测试 |

实际任务必须列出精确文件，不能只写“修改 native 全目录”。共享文件变更向集成者提供小 patch 和 contract test，不并发覆盖。验证生产 snapshot 时暂停该写集修改或使用已授权不可变 snapshot；记录 source hash 起止，不停止他人进程。

完整总提示词见同目录 `MCA-S-GRADE-AGENT-EXECUTION-PROMPT-20261011.md`。交给执行 Agent 时必须明确这六项：

```text
阶段：R1/R2/...，本次只做一个垂直切片
问题：Qxx +已有 issue ID，不自选 UI 美化
写集：精确绝对路径；共享 CMake/Gradle 由集成者独占
源码参考：ref:path + 当前已验证修复；不整文件回滚
验证：保护测试、实际 target、真实生产入口、设备/产物证据
退出：旧路径调用点=0、资源正确、功能等价、未验证如实列出
```

**开工第一句话可直接使用**：当前 bug 暂时已修完，按本版 R0/R1 先冻结保护当前修复版、复跑防回归并实施工程守卫；历史已修项不重做，只有当前重新确证问题才补修。协调者先填一个完整任务包，弱模型只做低风险函数/helper 迁移，高风险生命周期/JNI/构建接线交给集成者并独立验收。以当前修复及旧源码作函数级参考，禁止自创业务、stub、设备名单和未验证晋级。

## 19. 当前修复版保护与重构防回归协议

### 19.1 基线不是 HEAD：建立 B0 与逐切片 Bn

`B0` 是当前修复版的不可变内容快照，不是原项目旧提交，也不是一个 APK 文件名。记录 HEAD、全部实际生产输入的内容哈希、dirty patch、untracked 自有源码、third-party 本地补丁、模型/SDK/toolchain、测试清单、APK hash 和日志时间窗。敏感配置仅记录脱敏标识及哈希；本地-only 两个 guard 不入快照交付包，单独本地核对排除规则及执行结果。

冻结内容和运行构建需在获准的独立目录，或协调暂停相关写集后执行；不能让 Agent 在审计/编译期间继续改被验收的文件。其他已授权工作照常，但输入树出现未解释变化即该次验收无效。保护当前 dirty/untracked 修复，绝不从旧 HEAD 重置基线。

`Bn` 只包含 B0 + 已经独立验收的 n 个切片。一次只切换一个责任，后续 Agent 以 Bn 的哈希派单。未验收补丁、未编译 helper 和未接线 Facade 不进入 Bn。B0 如不能复测通过，先对账/定位，不扩大重构；区分产品失败、环境失败和陈旧证据，不能一律改代码。

旧源码只帮助找回具体函数、helper、常量、宏、默认值和副作用。参考优先级仍按 §12.1，任何搬移不得覆盖当前 greedy、API31、metrics、OpenCL、生命周期等已经存在的修复。历史名称只是保护场景，实际修复内容以当前函数和对应报告确认。

### 19.2 工程实现优先：先建立能拒绝坏改动的守卫

先由集成者完成下面最小工具切片并验收，再派业务搬移。优先复用现有 `tools/build-guards`、native gate、final gate 和 build records；下表是**待实现/补齐的要求**，不能因本文存在便宣称已有强制执行。

| 工程守卫 | 必须实现的拒绝条件 | 必须跑的隔离负例 |
| --- | --- | --- |
| 任务包 preflight | 未填字段/不存在的路径或符号/基线 hash 变化/写集冲突/没有 reviewer/没有验证命令 | 包中删一项必填字段、改一个 hash、放入不存在 helper，均非零退出 |
| 写集与输入一致性 | 实际变更不属于批准写集；snapshot 起止不匹配；外部修改未重基派单 | 临时 fixture 加写集外文件、模拟并发输入变更，均拒绝 |
| build/test 互斥 | 锁模块缺失、已有活跃 holder、绕锁 active compiler；错误退出未释放 | 两个受控执行者竞争，第二个拒绝；异常后能安全重新取得锁 |
| 原→新实现归属 | 函数没有唯一目标、helper/宏/常量未映射、生产调用仍指向被删路径 | fixture 漏 helper、保留双实现、断一个生产 caller，均拒绝/报告阻断 |
| source/target/包内库 | 生产 TU 无 owner/未编译/重复强符号；SDK REQUIRED 被 stub；缺应有 ABI/so | 隔离构建删 source、禁 mandatory SDK、替换旧 so，均不能 PASS |
| 测试结果与产物绑定 | 没执行/零测试/清单少用例/新增 skip/日志不属于本 snapshot/非当前 APK | 陈旧 XML、空 XML、少一个 mandatory case、旧 APK hash，均拒绝 |
| 依赖边界与质量 | 新 Android import 进入纯模块、反向边/环、新 warning、未经解释超限 | fixture 引入非法 import/环/超限，门禁有可核对诊断 |
| 行为差异 | 修改默认值、序列/codec/算法/错误合同，且不在批准差异中 | fixture 改温度默认值、丢 emoji、少末尾 chunk、改 error field，应被测试捕获 |

文本扫描只作辅助：源码断言/关键词/类名一致不能证明真实接线。AST/编译图/调用点分析必须结合生产执行、完整 ABI 对账和语义 A/B；无法自动证明的部分由独立 reviewer 明确签核，不能伪造“脚本证明全部正确”。门禁实现也要独立审核，worker 无权修改它让自身通过。

**本版实测构建包装差异**：`scripts/build.ps1` 自持 `McaBuildLock`；`scripts/verify-code.ps1` 使用 `Global\McaGradleBuildQueue` mutex，这两者尚不等价于统一共享锁。集成者先统一同一锁协议、lock 缺失 fail-closed、失败退出码和快/严两条 lane。不得外层已持同一 `McaBuildLock` 再调用自持锁的 build.ps1，否则自锁。改前如需快速验证，由协调者确认锁顺序、外层保护现有测试入口，并禁止其它 build/test 绕过；worker 不自选锁包装。

快 lane 保留增量反馈；切片最终验收用实际必要 target/config 的 rerun 或独立新构建目录，并绑定内容快照。不是每个 helper 都重建全 APK；但任何 native/JNI/生产接线切换，在进入 Bn 前必须有该 snapshot 的真实产品 build/link/package/设备证据，不能只看 host test。

### 19.3 每个切片固定的十步

1. **派单**：协调者填好 §20 的任务包，源/目标函数、签名、依赖、写集、命令、expected cases、风险、回退均具体化；preflight 通过才交 worker。
2. **基线验证**：运行任务包的 before tests 和必要真实链路，保留结果；before 不通过则停迁移并交集成者分诊。`SKIP/NOT_VERIFIED` 不算通过。
3. **函数盘点**：列源函数→目标函数，以及传递 helper、宏、常量、static state、线程、锁、资源、异常、null/空值与所有 caller。行号可变，完整签名+内容哈希才是锚点。
4. **按原码迁移**：一次只迁一个责任/算法阶段，搬函数与其依赖；保留算法顺序、边界条件、精度、缓存、默认值和返回协议。不加新设计、不“顺便优化”。
5. **快速验证**：编译真正受影响模块/TU，运行约定测试；每次失败保留日志。不得删断言、改 golden、调小矩阵、添加 skip/stub/default 来消失失败。
6. **接线准备**：worker 提交未生产切换的小 patch 和迁移图；集成者修改共享 CMake/Gradle/composition/caller，校验 target/ABI/符号。切片内 staged helper 不算架构完成。
7. **真实等价验收**：在隔离验收 snapshot 执行 UI、认证 API、业务 Worker 相关入口、model load/run/cancel/unload、错误/恢复与 A/B。按 §12、§15 冻结容差，输入及有效参数都要比较。
8. **独立 review**：核实完整原码/helper、参数透传、生命周期、测试质量、构建覆盖、证据哈希；worker 自评 PASS 不能替代此步。
9. **切生产并删旧**：只在隔离验收已通过后把已验证 patch 纳入生产基线；移除旧执行 body/调用链，再重跑必要 compile/tests/真实 smoke。仅旧文件名 wrapper 可以短暂保留，所有 caller 只走一个真实 body。
10. **晋级与回退**：内容起止/产物 hash 相符、独立验收无阻断才登记 Bn+1。失败只撤该切片 patch，并按 §19.6 复测旧基线；未验证切片保持不集成，其他无关已验证切片可继续。

“删除旧路径前验收”允许在独立验收 snapshot 试接新路径；实际活跃基线一次只保留一个执行 body，不增加运行时分流 flag 长期维持两套 owner。不可把同样 bug 的两份新/旧副本互相 A/B 当真保真：确定性 fixture、原参考和已修保护测试都需参与。

### 19.4 迁移时最容易丢功能的检查点

| 切分部位 | 禁止丢失的内容 | 必须比对 |
| --- | --- | --- |
| Facade command | 现有完整采样/上下文/工具/图像输入/backend/调优/恢复字段及缺省语义 | 旧 entry→effective native config 逐字段；不能把当前 skinny `ChatCommand/ImageCommand` 当完整产品合同 |
| JNI converter | null/empty/end、字符串编码、数组长度/dtype、long/size_t、输出路径 | 完整 JVM descriptor、Unicode fixture、错误和终态差异 |
| engine/session | 模型和 context 所有权、SDK配置、历史/KV/prefix、MTP、视觉/transport fallback | 生命周期、失败回滚、load/unload/reuse、真实模型输出 |
| request state | 同一 attempt 的参数 snapshot、cancel flag、deadline、worker incarnation | 完整转移表、terminal一次、retry不复用旧身份 |
| callback | chunk FIFO、UTF-8 tail、stop marker split、最终 flush、progress/preview、GC refs | 所有序列/字节、slow sink、clear/reentry、断开后的错误/查询 |
| error | code/phase/retryability/trace/cause/public response/terminal query | 成功、失败、取消、超时、拒绝、worker death，不只比较成功图/文本 |
| pipeline | scheduler/timestep、CFG、latent/VAE scale、mask/tile overlap、ControlNet/layout | seed/effective params/中间 tensor/最终输出；不能通过“能出图”替代 |
| build target | 原 SDK 宏、include、link options、PIC、ABI、SONAME、runtime assets | compile database、实际对象/符号、APK ELF、真实加载 |
| data/asset | ID、顺序、默认值、旧 schema、journal、输出commit、图库关联 | upgrade/restart/半提交恢复、原数据不丢 |

所有表中场景是测试合同而非一律要求重写这些实现。能原样迁移便原样迁移；既有合格模块直接复用。新增错误/背压/timeout 内部策略若改变外部行为，先记录为设计差异交集成者/用户合同审查，worker 不从候选数值直接改产品。

### 19.5 局部与全局验证分层

- 单个纯 helper：before/after 测试、实际 TU 编译、依赖/函数映射/输出精确比较、独立 review；不以一串只检查源码字符串的测试充数。
- owner/session/request/callback：再加确定性 barrier/latch、并发压力、失败/释放各阶段、native ack/进程隔离、相关入口与设备 loop。
- JNI/target/生产接线：再加真正 Android build/link/package、CheckJNI/ABI/exports、代表性 ARM64、主 UI+认证 Local API+相关 worker、cancel/retry/恢复、A/B。
- 阶段集成与最终 RC：完整功能台账、ON/OFF/Debug/Release 矩阵、各能力资产、跨模态/多进程、fault、24h/7d、升级、签名/SBOM/复现，按 §14–17。

同一包先跑适合的最小有意义验证，再跑必需集成门禁。无需每次注释/纯搬移都全仓全模型长稳；也不能以快 lane 替代关键路径 final gate。最终 RC 的全部必需证据绑定同一制品哈希，源码有变须按影响图重验，不能把几轮不同 APK 的成功拼成完整认证。

### 19.6 停止和回退

发现基线/写集外哈希变化、原逻辑缺 helper/宏/资源、unexpected ABI/default/算法差异、测试失败不能定位、需要引入新锁/状态机/数据格式、证据不能绑定时，立即输出 `STOP_NOT_INTEGRATED`，保留日志和小 patch，交集成者分诊。不可自行绕过。并发构建锁冲突是等待/重排，不得杀其它 Agent 的进程。

回退以切片 before bytes/hash 和自有 patch 为单位。先比较当前内容与本切片 expected after；若第三方已写入则不直接反向覆盖，交协调者按 hunk 解决。只恢复本切片影响的内容；新增文件只有证明本切片独占且无 caller 后才撤。不得整仓 reset/checkout/clean，不回滚其他已修 bug，不删除用户数据或重新初始化数据库。回退后重新运行受影响基线测试及真实 smoke，结果与 Bn 核对。

## 20. 弱模型受控派单、冻结设计与独立验收

### 20.1 能力分工与风险等级

| 等级 | 典型任务 | 执行责任 | 弱模型边界 |
| --- | --- | --- | --- |
| L0 | 只读盘点、行数/hash、caller/函数映射、整理已有证据 | worker，reviewer核对 | 可以做，不能自行判定功能PASS |
| L1 | 无共享状态纯 helper、单阶段已冻结算法搬移、明确 adapter mapping | worker +独立reviewer+集成者 | 可以做；设计/源函数/依赖/测试已完整给出 |
| L2 | engine/session/lease/request/callback、异步取消、cache、错误传播 | 足够能力的工程执行者+独立审查 | 弱模型仅盘点/机械片段，不自主决定生命周期或锁序 |
| L3 | JNI ABI、跨DSO/进程所有权、CMake/Gradle、Facade切换、数据升级、R8/发布 | 集成者+独立审查 | 不交弱模型独立集成，不允许猜测设计 |

模型强弱是派单能力假设，不是可靠性保证；强模型也可能写错。正确性来自清晰任务包、可失败守卫、真实测试和独立验收。若只有弱模型且没有能承担 L2/L3 的集成者，L0/L1 可进行，高风险内容保留 `NOT_VERIFIED/NOT_INTEGRATED`，不能为了“做完”放宽。

### 20.2 一个包的默认粒度

每包一个责任、1–3 个允许编辑的生产文件（确需 header 同步可独立列入），实际迁移逻辑默认 ≤300 行；测试文件另外列精确写集。先从 1 个 ≤150 行纯 helper 开始，成功后才扩到 2–3 个相互紧密依赖函数。一个既有大函数超过限制，集成者先指定阶段边界，不让弱模型自行发明拆法。

搬迁 diff 包含原文件删除+目标添加，总 diff 行数可能是逻辑行数的两倍以上；依据函数级映射和实际逻辑预算，不机械按 diff 总行数拒绝正确搬移。超过 3 个生产文件、新增共享状态/锁、改模块/target/ABI、同时涉及两个 backend 时重新派单拆包，不自行扩写集。

共享 monolith 同时只有一位 writer；弱模型不直接改 `CMakeLists.txt`、`*.gradle.kts`、composition、门禁脚本、schema 或 `.git`。向集成者提供小 integration patch 建议而非实际落地。清单中的文件/类名是职责建议，优先复用现有同名/同责实现，不新建平行框架。

### 20.3 派单输入必须完整

每包至少填写：

1. run/slice ID、阶段、风险等级、目标、授权范围、负责人/集成者/独立 reviewer、依赖包与当前 Bn；
2. 精确写集/只读集/禁止集、各文件 before SHA256、新文件是否存在、dirty/untracked归属；
3. source `ref:path`、当前函数完整签名+原字节哈希、旧参考函数、全部 helper/常量/宏/资源/锁/线程/exception/caller；
4. 唯一目标 owner/函数签名、现有类复用、接口变更（没有就填 NONE）、被迁走 old body 的移除条件；
5. 不可改变合同，包括每个输入/默认值/null/输出/副作用、已有修复 ID及测试、allowed differences；
6. 可执行的 exact commands、cwd、shell、工具链/SDK flags/ABI/config、锁由谁取得、输出目录、测试class+case清单、基线总数/既有 skip、预期退出码；
7. 真实入口/模型资产/hash/设备/产物验证需求；不需要真实设备的纯helper写明原因；required但不可用不能写N/A；
8. 正/负向门禁、独立 reviewer检查项、交付物、停止条件和精确回退方法。

模板里任何 `<待填>`/`TBD`/空值都不是执行授权。协调者填完并跑 preflight 后才派；worker 缺信息只能做不依赖它的只读盘点，不能“合理猜测”执行高风险变更。expected test counts 来源于该包 before run/XML 清单；§3.4 历史 488/2,417 不是未来所有包的固定数字。

### 20.4 Worker 固定操作与停止次数

worker 先打印任务包摘要+hash核验，运行 before、仅按指定函数搬移、运行 after，并交证据。禁止读完整方案后自选全仓工作、重新设计领域类型、启新重构脚本、重写算法或“顺手修所有warning”。

同一失败最多做 2 次**有日志和明确原因的局部纠正**，不是盲目重跑次数；之后 `STOP_NOT_INTEGRATED`。第一时间发现 UAF、deadlock、功能丢失、写集越界、ABI/参数变化则立即停，不等两次。局部纠正不能改 fixture/断言/skip/默认值/守卫。工具环境失败先上报，不改业务代码补偿环境。

### 20.5 独立验收合同

reviewer 默认只读，不是 worker 让同一个模型换个角色再自签 PASS。可以是独立 Agent 或人工；必须阅读完整 source/target/helper，检查保留修复和全部调用方，并查看真实原始日志。更高能力不替代证据。

裁决为 `APPROVE_FOR_INTEGRATION`、`REQUEST_CHANGES` 或 `BLOCK_NOT_VERIFIED`。前者仍不是 Git merge/push/发布授权；集成者完成接线后的必要后验才可更新 Bn。worker 使用 `READY_FOR_REVIEW`，不得自己标 `S_GRADE_COMPLETE`。

签核至少包含：源码/hash/写集一致、算法与合同等价、唯一 owner、生命周期/锁序、actual target compile/link/ABI、mandatory tests完整且无新增skip、真实入口证据、错误/trace、已知风险、回退可执行。任何 mandatory项缺失都不批准；缺reviewer则只交未集成patch。

## 21. 第一批工程任务包与交付顺序

下面是协调者的派单顺序，**不是可直接无填写运行的命令清单**。每包展开为配套模板并预检；若依赖未通过，后继只能只读准备，不能切生产。

| 包 | 责任与产出 | 先后关系 | 退出证据 |
| --- | --- | --- | --- |
| E00（L0） | 冻结当前修复版B0、已修保护/最新APK对账、函数及helper参考 | 先做最小基线 | 内容manifest+功能/修复保护清单+当前before结果；不改业务 |
| E01（L3） | 统一build/test锁、preflight/写集/snapshot守卫、完整测试结果检查 | E00后，工程代码第一批 | 锁冲突/异常释放/陈旧证据/越界等隔离负例失败，正例通过 |
| E02（L2） | 已修runtime防回归，核对native ack、富错误query、prefix写者 | E00后，可在互斥写集配合E01 | 已修项不倒退；候选明确可达性/复现；确证bug最小修复 |
| E03（L3） | native source→target→symbol→APK清单；逐个分诊残缺MNN TU/QNN重复副本 | E01通过后 | 函数/符号唯一归属，禁stub真实target编译，mandatory `.so`可追溯 |
| E04（L1试点） | 从仍聚合的MNN/QNN/SD中选一个完整纯helper原样迁出 | E03合同/target已明确后 | before→after精确一致、真实TU编译、独立review；约≤150逻辑行 |
| E05（L2/L3） | 一条真实load→session→request→cancel/drain→unload接Runtime Port | E02–E04通过后 | 真实SDK/model/ARM64、failure rollback、ack、资源及trace |
| E06（L3） | 单一Chat入口先在隔离snapshot接线，再覆盖UI/API/Worker | E05通过后 | 完整command字段、context/tool/stream/cache、终态/恢复与A/B |
| E07（L3） | 单一Image入口/worker job与asset事务接线 | E05通过，相关共享合同冻结 | conditioning/seed/params/进度/cancel/图库/恢复与A/B |
| E08+ | 逐个native阶段/Model/API/参数/UI责任及物理模块，按R6/R7 | 各前置包通过 | 预算/依赖/真实功能/target门禁，任何required失败不切生产 |
| RC | R8/R9跨模态/故障/长稳/Release/升级/供应链/可复现 | 所有必需功能包通过 | 同一最终制品综合S-C/S-R/S-O证据全部关闭 |

E01/E03以修现有包装/构建和强制边界为主，不先画更多图、造空类或美化UI。E04试点成功也不等于所有弱模型可承担L2/L3。每一轮都交付已验证的小工程结果，不累计数十个“编译以后再说”的未接线文件。

最终可宣称的是：“在指定功能范围、工具链、模型、设备场景、时间窗和样本量下，全部必需综合门禁通过、已知正确性bug关闭、功能与当前修复版等价”。不能承诺未来无未知bug，也不能保证弱模型永不出错；本方案要求**出错不能静默进入基线**，未验证不集成、失败可追溯、回退不损伤已有修复。

配套文件：

- [协调者 / 弱模型 Worker / 独立 Reviewer 提示词](./MCA-S-GRADE-AGENT-EXECUTION-PROMPT-20261011.md)
- [单切片任务包模板](./MCA-S-GRADE-SLICE-PACKET-TEMPLATE-20261011.md)

本版只读审计并编写这三份文档；未修改生产/测试/构建实现，未构建/安装APK，未stage/commit/push，未发起其他Agent任务。文中工程守卫及S门禁须在执行阶段真实实现和验证。

