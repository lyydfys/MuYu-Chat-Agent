# MCA S 级修复与解耦 Agent 执行提示词

你正在 `E:\MAC` 执行 MCA 的工程代码优先修复与解耦任务。你的目标不是“增加几个类”或“让编译通过”，而是在保持原项目功能、协议、数据、参数、推理质量和设备准入行为不变的前提下，修复真实问题、建立唯一 owner、接通真实生产路径，并提供可追溯证据。完整方案的 §12–18 是具体参考/迁移/构建/验收/并发合同；本提示词不能替代它。

默认先执行 R0 最小基线和 R1 修复，不直接启动全仓重构。S 是本项目内部综合工程门禁，不能承诺绝对无 bug；必须关闭已知正确性问题和关键未验证项，再判断阶段等级。

## 本次任务输入（派单时填写）

```text
阶段：R0/R1 或其它已获准的阶段
问题编号：Qxx +旧 issue ID
垂直切片：只选一个明确功能/生命周期
允许写集：列出精确绝对路径，不用“native 全目录”
共享文件 owner：CMake/Gradle/composition 由指定集成者独占
源码参考：commit:path +当前已验证修复
必须验证的生产入口：UI / authenticated Local API / Worker
构建配置：backend flags、ABI、Debug/Release、SDK hash
设备/资产：实际可用设备和模型 SHA256；缺失如实报告
输出目录：artifacts/architecture/<run-id>/<slice-id>
```

未知字段先从仓库和既有台账确定；不能把没有提供写集理解成允许覆盖所有 Agent 的修改。

## 开始前的硬约束

1. 先读取 `E:\MAC\AGENTS.md` 和 `E:\MAC\docs\architecture\MCA-S-GRADE-DECOUPLING-EXECUTION-PLAN-20261010.md`。
2. 先运行只读 `git status --short`、`git diff --stat`、`git ls-files --others --exclude-standard`，登记 dirty/untracked 文件；不覆盖、不回滚、不删除、不 `reset`、不 `checkout`、不 `clean` 其他 Agent 的改动。
3. `AGENTS.md` 和 `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 是本地-only 文件，绝不 stage、commit、push 或放入发布/证据包；确认它们仍在 `.git/info/exclude`。
4. 只修改本任务声明的写集。发现写集外问题时记录并派单，不擅自扩大范围。
5. 工程代码、生命周期、错误、构建和生产接线优先；不要先做 UI 美化、重命名或机械移动文件。
6. `E:\model\MAC` 只读，不在原项目构建、写日志、修改或安装依赖。已有 reference ledger 为 `docs/repair/MCA_FUNCTION_PARITY_REFERENCE_LEDGER_20261009.json`；按函数及 helper 对照，不整文件回滚。
7. 不默认获得 Git stage/commit/push、发布、删文件、停止他人进程、改 API 合同的授权。删除本切片旧路径须证明 owner 和调用点，非本切片文件不动。

## 行为保真规则

- 先用当前实现、旧源码、协议测试和 characterization fixture 作为行为参考；不用猜测重写业务。
- 不改变 API 路径、字段、默认值、鉴权、错误码、SSE 顺序、数据库关系、模型格式、推理参数和输出质量。
- 不删除低频功能；不因为迁移困难隐藏入口、减少上下文、降低分辨率或降低精度。
- 不增加任何芯片、机型、认证、profile、allowlist/whitelist 阻断。未知设备走 generic compatible fallback，最终由真实 native load/execute 决定。
- 不使用 stub、fake、Preview 或 admission-only Facade 冒充生产实现。
- 单元测试可以使用必要 mock/test double，但标记为测试隔离；它们不替代 production injection、真实 SDK/model 或设备验收。
- 参数/算法/数据以原实现为准，既有修复优先保留；差异标为 `PRESERVED`、有保护证据的 `INTENTIONAL_BUG_FIX` 或 `UNEXPLAINED`，最后一种不能交付。

## 执行顺序

严格按以下顺序；除非用户明确改变优先级，不得跳到后面的阶段：

1. **保护测试**：为即将修复的行为先写失败测试或 characterization test。
2. **修生产代码**：先修资源、终态、竞态、错误和构建问题，再移动职责。
3. **编译和测试**：运行对应 Kotlin、host/native、protocol、CMake ON/OFF 构建。
4. **真实接线**：证明 UI、Local API 或 Worker 至少一个真实生产入口调用新 owner；只测 Facade 单测不算接线。
5. **依赖和 owner 检查**：扫描旧调用点、重复 owner、反向依赖和 source/target 清单。
6. **删除旧路径**：只有在本切片所有 UI/API/Worker caller 已切到同一个 owner、旧调用点为零、行为证据存在、回退点记录后删除。允许短期单路径兼容 adapter，但要列 caller/截止阶段；不能保留两套真实执行。
7. **报告证据**：逐项区分 `PASS`、`FAIL`、`SKIP`、`NOT_VERIFIED`，不把环境未执行写成通过。

## 当前优先修复项

按风险优先处理：

1. shutdown 与在途 callback 的竞态，确保 terminal 先记录、后投递，shutdown 后不重新插入 terminal；不要忽略 admitted 与 cancel/route register 的中间窗口；
2. active lease、cancel handle、callback route、session binding 的 terminal cleanup；区分逻辑结算和 native drain/release ack，不提前 free 在途资源；
3. 重复 session ID 覆盖、旧 generation 回写新 cache、超时使用非 monotonic clock；
4. admission、cancel 注册、callback 注册未形成原子事务的问题；
5. `catch(Throwable)`、无意义 `runCatching().getOrNull()` / `getOrDefault()` 导致的错误吞噬；
6. `BackendRuntimeOwner` 的 skeleton 和恒 false `isReady`，必须接入真实 native loader/engine probe；
7. `ChatExecutionFacade`、`ImageGenerationFacade` 未接生产 UI/API/Worker 的问题；
8. CMake 中存在但未编译、被排除或符号重复的新 native 文件；
9. JNI 与 engine/session/request/pipeline 混合的大文件；
10. source manifest、target manifest、APK `.so` 和 build provenance 不一致的问题。

此外把旧 repair issue 表逐项纳入，不遗漏跨模态 chat/image 孤儿 busy/idle、FGS 不退出、全 worker 内存/FD、坏 tokenizer 的真实分支、`libmca_sd_native.so` source binding、R8/native failure diagnostics 和 compiler warnings。候选问题先复现/定位，不把报告假设直接当根因。

**已修复项不能重新报未修**：Registry/attempt identity、非线程绑定 CAS lease、single-drainer FIFO、terminal record 先记后投递、Facade reserve-first 等已有改进须保留。早期 JSON 与最新 `UNIFIED-ISSUE-TABLE.md` 冲突时按 APK hash/原日志时间窗逐项对账；5/5 生产注入与 121 分钟单模态 soak 不等于全矩阵/24h 混合长稳。

## 实施边界

### Kotlin

- `MainViewModel` 只接收 intent、调用 Facade、暴露只读 state、转发 effect；不持有 engine、Repository、Local API runtime、native provider。
- Runtime owner 唯一持有 engine/session/lease/epoch；UI、API、Worker 只通过 Facade/Port。
- Chat 使用 `ChatApplicationFacade → ChatExecutionOwner → RuntimeOwner`；Image 使用 `ImageApplicationFacade → ImageGenerationOwner → Worker/Runtime`。
- 复用已有 `RequestIdentity`，真实链贯通 `requestId`、`attemptId`、`runtimeEpoch`、`sessionGeneration`、`workerIncarnation`、`nativeSequence`、`traceId`，不另造只在 fixture 用的类型。
- 复用已有 `RequestState` 的 Created/Accepted/Preparing/Running/CancelRequested/DrainingCallbacks/Succeeded/Failed/Cancelled/TimedOut/Aborted，遵守现有 transition 表，不新增不一致 Completed。
- 状态必须应用内 terminal exactly once；物理资源 native ack/worker death 后清理，终态 bounded outcome 留作查询；网络/进程崩溃用幂等和 replay 去重，不声称物理消息 exactly-once。
- domain/contracts/application 零 Android/Compose/JNI SDK import；R3/R4/R5 逐步物理化 Gradle，最迟 R7 硬门禁，不无限期停 package 分层。

### JNI / C++

- JNI 只做参数读取、转换、handle 校验、生命周期入口、callback 转发和错误映射。
- JNI 禁止模型策略、采样、tensor 计算、QNN graph、session 状态机和 UI 状态。
- JNI 文件目标 300–500 行，硬上限 700；普通 native 文件 400–900 行，硬上限 1,100；pipeline 硬上限 1,200；普通函数硬上限 120 行（单阶段推理循环 160）；header 常规硬上限 500。行数按物理行，不靠压行或 header 搬家达标。
- 不允许把大文件逻辑原样搬进另一个大文件；每次拆分都要有真实输入/输出/owner/测试。
- `mca_mnn_native`、`mca_qnn_native`、`mca_sd_native` 必须编译所有生产 source；文件存在但未纳入 target 不算完成。
- JNIEnv 不跨线程，UTF/array/LocalRef/GlobalRef 成对释放，modified UTF-8 与原 codec 语义保留，C++ exception 不穿 JNI；保留完整 JVM descriptor、重载和现有 `.so` 名称。
- 多个 `.so` 的 STATIC singleton 不是自动全进程唯一；epoch authority 必须明确。`mca_prompt_handoff` 共享 registry 不拆成多份。
- CMake source、SDK macro、include、PIC、依赖设在真实编译该 TU 的 target；runtime 不反向依赖 JNI。不一次性重加被排除的残缺 MNN/QNN TU。
- 缺必选 SDK 的产品构建应明确失败；显式 disabled 的 host/JVM 或无能力 ABI 测试可以保留，但不能作为全功能 APK。

### 错误和可观测性

- 接通现有 `StableErrorCode`、`ErrorEnvelope`、`ErrorCatalogRegistry` 与 native 对应合同，不另建 `McaErrorCode` 的平行体系；每个错误带 phase/retryability/traceId/request identity。
- 业务层不能静默吞错或以字符串猜测错误。CancellationException 不吞；致命 VM error 不伪装可重试成功；合理顶层/JNI/cleanup catch-all 可保留但有 code/cause/失败传播，不机械清零 catch(Throwable)。
- trace 从 UI/API/Worker 贯通到 Facade、Runtime、JNI、native、callback 和响应。

## 阶段交付要求

每个提交/切片都必须报告：

- 写集和未触碰文件；
- 当前 owner 与目标 owner；
- 保护测试、生产修复、编译命令、测试命令和结果；
- 新路径的真实调用点、旧路径调用点扫描；
- 行数和 target/source manifest 变化；
- 资源 cleanup、错误映射和 trace 证据；
- 未验证、失败、环境限制、残余风险和回退点。

构建独占：使用既有 `McaBuildLock` 和 content `SourceSnapshot`，不在共享 build/.cxx 并发 Gradle/CMake，也不停止其他进程。只读审计先行，源码修改中不得拿开始前的 class/test APK 作为当前结果。host CTest 遵守 generator 的实际 configuration 并自动布置 DLL，进程起不来不是产品测试失败/skip。

### R1 运行时修复

先完成 shutdown、lease、terminal、session generation、取消事务和错误吞噬修复。不得先搬文件。必须有并发/取消/重复终态/close 幂等测试。

R0 只做启动切片所需基线、源码参照和证据分诊，不能先花大量时间排版报告而不修 bug。R1 未关闭当前已知 P0/P1、数据和功能退化或真实主要能力未恢复时停在修复阶段，不扩大迁移。

### R2 JNI 垂直切片

从一个真实 MNN chat 或 SD 请求开始，拆 converter、handle、error、request 和 callback；保持 JNI 外部签名和协议行为。必须由真实生产入口调用。

### R3 Runtime 接线

实现真实 loader、ready、unload、recovery；删除或隔离 skeleton；真实 native load/execute/unload 通过。未知设备不能被拒绝。

### R4 Chat / R5 Image

UI、Local API、Worker 共用同一个 Chat/Image Facade 和 Owner。验证流式、上下文、工具、取消、重试、持久化、图片结果、恢复和资产提交。

### R6 Native target

逐个拆 MNN/QNN/SD 的 engine、session、request、callback、pipeline，并同步 CMake target、host test 和 source manifest。禁止把复杂度转移到新巨型文件。

### R8/R9 证据和发布

执行真实故障注入、真机 UI/API、混合 soak、进程恢复、worker death、跨模态、取消/重试、Release、SBOM、签名和可复现构建。最新 APK 的所有证据必须绑定同一 SHA256。

最低长稳：24h 连续、≥1,000 次混合有效请求、全 main/chat/image PID/FD/PSS/线程、cancel/retry/load/unload/worker death；预发布 7d 观测。只有一台代表性物理 ARM64 通过生产 UI+认证 API 即开放兼容设备，额外设备不能变成准入名单。缺真实资产的 MTP/QNN/Sana/云等能力保留 NOT_VERIFIED，不删功能项。

升级和资产中断测试必做；Release 的 R8/minify/JNI keep、源/target/包内 ELF、签名、SBOM、vendor patch、clean rebuild 与最终安装一并闭环。Debug 通过不能直接算 Release 通过。

编译 warning 逐条归因：Kotlin、C++、lint、Gradle、third-party 分开；不以 lint=0 error 声称 Kotlin=0 warning，不 blanket suppress/baseline。自有 compiler warnings=0、严重 correctness/security lint=0、未分诊项=0；合理低风险例外逐条批准。

## 完成判定

以下任一情况只能报告未完成：

- 新 Facade 只有单测，没有生产构造点/调用点；
- `isReady` 仍恒 false 或真实 loader 未接线；
- 文件拆分后旧路径仍在生产使用；
- 生产 source 未进入真实 CMake target；
- terminal/lease/cancel/route cleanup 没有测试；
- model-only 合同测试被写成真实故障注入通过；
- 旧 APK smoke 被写成最新 APK 验收；
- 未验证项被删除或改写为 PASS；
- 使用设备名单阻断能力；
- `AGENTS.md` 或本地策略测试被发布。
- 只有主进程采样而忽略 native worker，或单模态 soak 冒充混合长稳；
- 关键功能资产缺失被标为 PASS，或 warning 被直接隐藏；
- 重要纯模块仍依赖 Android/具体 SDK，或只有 package 规范没有编译强制。

若任务需要真实设备/SDK/签名授权而当前不可用，先完成其它可验证代码和测试，列阻塞原因，不造证据、不关闭必需项、不宣称完成 S。

最终回复必须使用以下格式：

```text
本轮写集：...
已修复：...
已真实接线：...
测试与构建：...
证据文件：...
未验证：...
失败/阻塞：...
回退点：...
下一步：...
```

不要声称“无 bug”。只能说明已验证范围、仍有风险和下一步。

