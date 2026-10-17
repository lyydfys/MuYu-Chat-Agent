# MCA 重构质量、低质量代码与功能丢失风险复审

审计日期：2026-10-09，Asia/Shanghai。关键源码快照从 20:46 开始；源码清单在 20:53 采集；QNN 更新后再次核验至 21:13。基准提交：`afcc9c0ae7309b13efdd0b9844751529b4f1aa41`。审计对象包含未提交和未跟踪的当前工作区代码，不仅是 HEAD。

**结论：当前工作树是不可交付的重构中间态，关键生产链验收不合格。有明确的极低质量改动，也有已经丢失的原有实现。不能把它认定为完成了企业级 A 级，更不能认定综合 S 级。**

最严重的问题是：MNN 必需 JNI 入口缺失、聊天真实执行被成功标志代替、Sana 使用成功 stub、SD1.5 核心数值链被改错，以及 MNN 生产目标编译阻断、QNN 共享互斥量符号不匹配。应先恢复生产行为，再继续架构迁移。

这次必须纠正此前“生产 MNN 仍保持真实实现，仅新生命周期模块是骨架”的判断：当前 `mnn_native_engine.cpp` 本身已经发生实质退化。之前的判断漏检了生产 JNI 函数体，不能继续作为验收依据。

## 1. 审计范围与证据边界

本次对 `app/core/api/feature` 下第一方 `src/main` Kotlin/C++ 源码做完整文件清单和文本扫描，人工深入追踪了 JNI、MNN 聊天和图像计算、GGUF MTP、QNN 构建、Runtime/Session/Request/Callback、Facade 接线和测试/发布门禁。清单为 **909 个文件、284,510 物理行、19 个文件超过 1,500 行**。这些是扫描统计，不代表逐行人工证明了全部 28 万行正确。

执行或读取的证据：

- 比较当前四个桥的 JNI 定义与 HEAD，并搜索所有第一方 `src/main` 原生源码，核查被移除定义是否真正迁移；核查 Kotlin `external` 声明和调用链。
- 执行 `tools/native/verify_native_targets_compile.py`，保留失败日志，并审查该脚本自身覆盖范围。
- 用现存 ARM64 CMake `compile_commands.json` 的真实参数，直接执行 NDK `clang++ -fsyntax-only`；新 `target_sources` 使用其共享目标的同组参数。数据库来自较早配置，故这些检查证明所列翻译单元的语法状态，**不冒充最新全工程配置、链接、APK 或设备运行验收**。
- 读取最新 app JUnit XML，分别记录其他模块 XML 的时间，不把不同版本的绿灯拼成当前全工程通过。
- 重跑两个确实存在的 native host 测试：`mnn_sampler_source_contract`、`mca_native_helper_unit_tests`，2/2 通过；检查完整 13 项 CTest 注册表，9 项所需可执行文件缺失。
- 核查 Facade 构造点、真实 Local API 接线、架构门禁、故障矩阵、现有 APK、R8 设置、历史 Kotlin/lint 证据。

本次未修改业务代码、未修改测试源码、未提交或发布任何文件。新增内容仅为本报告和审计证据。未执行设备安装、真实模型推理、长稳或发布 APK 验收。

证据在同目录 `evidence-20261009/`：

- `audit_evidence.json`：源码统计、JNI 差异、已有测试与 lint 时间。
- `source_inventory.csv`：909 个源码文件的物理行数与 SHA-256。
- `exact_compile_results.json`、`exact_compile_llama_sd_results.json`：参数、源码哈希、退出码、诊断。
- `exact_compile_qnn_recheck_results.json`、`qnn_mutex_symbol_evidence.json`：更新后的 QNN 语法检查与真实桥 object 的符号证据。
- `native_syntax_gate.log`：通用 native 脚本执行记录。
- `native_host_test_summary.json`：已跑的测试与缺失可执行文件名单。
- `critical_source_excerpts.json`：关键源码片段、对应 HEAD 片段及源码哈希。
- `historical_kotlin_warnings.json`：历史编译告警的原文，明确标记为历史而非本次重新编译数量。

原始关键源码快照另保存在 `C:/Users/sy427/AppData/Local/Temp/mca-deep-audit-20261009-74og48n5/`。工作区有其他并行改动，后续验收必须重新绑定提交、源码和 APK 哈希。

## 2. 重构进度与质量判断

| 方面 | 实际状态 | 判断 |
| --- | --- | --- |
| 文件拆分 | MainViewModel 511 行；GGUF/MNN/SD 桥约 1,500 行以内；QNN 桥复核时 990 行 | 有形体进度，但行数不证明功能迁移 |
| 生产行为保留 | MNN JNI、聊天、Sana、SD1.5 数值链出现退化 | 不合格，优先阻断发布 |
| 真实所有权收口 | VM 扩展函数仍可访问整个 VM；native 仍依赖全局状态；新 Owner 多数未接线 | 未完成 |
| 统一入口 | Chat/Image Facade 仅登记请求；LocalApiFacadeAdapter 无生产构造点 | 未完成，无法声称 UI/API/Worker 已统一 |
| 自动检查 | 局部门禁更诚实，但文本合同、语法检查与整链行为之间有明显空隙 | 验收体系不足 |
| 生产可靠性 | 故障矩阵只驱动 Python 模型；无本快照的实机/长稳闭环 | 未验证 |
| 发布工程 | 仅有较早 debug APK；无当前 release APK 验收 | 未完成 |

正向成果也应保留：领域合同中已有类型化身份/错误/状态；回调路由核对 attempt、epoch、generation、worker；Facade 重复 requestId 已先预留、再登记并做失败回滚；SD 桥仍保留真实生成调用；构建脚本已明确区分 stub CI 和生产；终审报告已把整体状态修正为 `IN_PROGRESS_NOT_S_GRADE`。这些改善不能抵消以下生产阻断。

不提供“完成 70%/90%”的数字。缺少完整功能清单与逐项行为证据，百分比会掩盖已出现的关键回归。

## 3. 问题清单：6 项 P0、15 项 P1、6 项 P2

严重性含义：P0 为生产功能或构建阻断，必须先修；P1 为高风险缺陷、关键架构未完成或验收缺口；P2 为维护性、证据治理或告警治理问题。标记“未接线”表示源码有问题，但本次没有把它误报成当前生产用户已经遇到的故障。

### P0-01：MNN 丢失 20 个必需 JNI 入口【确认，生产源码】

定位：[NativeMnnBridge.kt](/E:/MAC/core/native/src/main/java/com/muyuchat/core/nativebridge/NativeMnnBridge.kt:15)、[NativeMnnDiffusionBridge.kt](/E:/MAC/core/native/src/main/java/com/muyuchat/core/nativebridge/NativeMnnDiffusionBridge.kt:15)、[LocalChatRunner.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/LocalChatRunner.kt:1016)。

HEAD 的 MNN 桥有 28 个唯一 JNI 名称，当前有 10 个；移除 20 个，同时新增 2 个。被移除的 20 个没有迁入其他第一方生产原生文件，Kotlin 声明仍存在，未发现 `RegisterNatives` 替代注册。

| 类 | 缺失入口 |
| --- | --- |
| NativeMnnBridge（8） | initBackends、isRunnerReady、getRuntimeStatsJson、requestStopIfActive、isGenerationRunning、getPrefillProgressJson、resetPrefillProgress、shutdown |
| NativeMnnDiffusionBridge（12） | isRunnerReady、resetImageCancellation、inspectBundle、runUnetSmoke、tokenizePromptTokenIds、tokenizePromptTokenIdsWithConfig、tokenizePromptTokenIdsFromJson、measurePromptTokens、encodePromptTokenIdsWithWeightsFromJson、encodeSdxlPromptConditioning、cancel、getRuntimeStatsJson |

触发：构建新原生库后调用这些 native 方法。后果是 `UnsatisfiedLinkError`；`runnerReady` 又将错误吞成 `false`，UI 会把已丢失符号误解释成 MNN 未打包。影响聊天、图像检查/取消/tokenizer，以及依赖 MNN conditioning 的 QNN 路径。JNI 动态查找使这类缺失可以绕过普通 Kotlin 编译和 C++ 链接检查。

修复：恢复原入口签名及真实委托，逐项对应 Kotlin 声明和完整参数/返回类型，不可补返回 true/空值的入口。验收：所有启用能力的 `external` 方法与产物动态符号/注册表一致；ARM64 APK 中实际调用上述入口，并完成真实 UI/API 运行。

### P0-02：MNN 聊天“加载成功”，却没有创建或输出模型【确认，生产函数体】

定位：[mnn_native_engine.cpp](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1337)、[beginCompletion](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1270)、[generateNextChunk](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1301)。

`loadModel` 只记录路径/config，直接设置 `g_loaded/g_runner_ready=true` 并返回 0。`g_llm` 初始化为 nullptr，没有真实 `createLLM` 和赋值。`beginCompletion` 做解析和缓存 bookkeeping 后返回 0，没有真实 response/prefill。`generateNextChunk` 最终无条件返回 nullptr；`prefill_ms=10.0` 是写死值。`unloadModel` 只切换标志，不能承担真实 Llm 释放。

功能丢失包括真实加载验证、文本生成、多模态执行、流输出与真实统计。修复必须恢复创建、配置、load、response/prefill、输出回调、逐块读取和销毁链。验收使用可用模型和损坏模型，证明有效模型产生非空文本、无效模型明确失败；中文/UTF-8 分片、取消、重载、多轮缓存和视觉输入都要走真实入口。

### P0-03：Sana 使用成功 stub 和伪执行证据【确认，生产函数体】

定位：[mnn_native_engine.cpp](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1438)。

生产 `generate` 中存在 `SanaSessionStub::run(){ return true; }`，之后使用固定 steps/conditioning 值和字面量 `"sha256"` 构造 `nativeEffective`，返回 `ok=true/path`。没有真实 Sana session、图执行或图片写入。HEAD 原有真实 `MnnSanaSession` 执行已经不在此链。

修复：恢复真实 Sana session/options/progress/cancel/output；成功条件必须包括有效文件、可解码像素和来自实际执行的证据。验收不能只断言 JSON 的 ok；必须检查产物、图调用、请求参数与实机效果。生产 target 禁止链接成功型 stub。

### P0-04：MNN SD1.5 核心计算被改坏【确认，数值数据流】

定位：[社区 CLIP 分支](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1058)、[初始 latent](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1120)、[scheduler.step](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1175)。

存在四处可直接确认的退化：

1. 社区 CLIP 编码结果写到 `.tmp.bin` 后没有读取，送入 UNet 的 embedding 改成全零。
2. 初始 latent 改为全零，删除了 HEAD 的 `mt19937(contract.seed)`、高斯噪声及 `init_noise_sigma` 缩放。
3. UNet 返回的 `batchedNoise` 没有参与 scheduler 更新；原有 `uncond + cfgScale*(cond-uncond)` 计算被删除，改为 `scheduler.step(modelInput, ...)`。调度器要求的是预测输出，却收到缩放后的模型输入。
4. `scale_model_input/step` 的 bool 返回值未检查，原先的 scheduler eta 和数值失败路径不再得到同等处理。

后果：代码可能产出 PNG，但提示词/负向词/CFG/seed 的原有语义已不成立。这是功能退化，不是视觉效果尚待调参。

修复：恢复真实 embedding 读取/shape/finite 验证、带 seed 的噪声、UNet 输出和 CFG 合成、正确 scheduler 参数与错误处理。验收应从已有真实 scheduler/UNet seam 注入不同预测输出，证明 latent 更新随预测变化；相同 seed 的重复运行符合容差，不同 seed/提示词/CFG 在支持能力内影响实际计算；最后使用真实模型验证图片和执行证据。

### P0-05：QNN 语法已修复，但互斥量声明/定义是不同链接符号【object 符号确认】

定位：[qnn_native_bridge.cpp](/E:/MAC/core/native/src/main/cpp/qnn_native_bridge.cpp:81)、[qnn_shared_preview_publisher.hpp](/E:/MAC/core/native/src/main/cpp/mca/qnn/qnn_shared_preview_publisher.hpp:34)。

20:51 的初始检查有函数头缺失、括号、互斥量声明和模板查找等 15 条 error。审计期间工作区在 21:05 更新；21:10 重新使用真实 ARM64 参数检查，**QNN 桥语法已通过**，旧语法问题不再作为当前失败。

不过新桥在全局 namespace 声明 `extern std::timed_mutex g_qnn_context_execution_mutex`；preview header 中的定义位于 `mca::qnn`。`using namespace mca::qnn` 不会使全局 extern 自动变为该 namespace 的定义。编译实际桥 object 并执行 `llvm-nm -C` 得到 `U g_qnn_context_execution_mutex`；包含实际定义 header 的 object 得到 `B mca::qnn::g_qnn_context_execution_mutex`。这是两个不同符号，第一方生产源码中没有相应全局定义，现有 Android 链接参数又启用 `--no-undefined`。

本轮没有执行完整工程链接，不能把 object 检查说成已经跑出的整库 linker 日志；但当前声明/定义不匹配已经证实。非 inline 定义放在 header 还存在后续多 TU 引入后的 ODR 风险。

修复：执行互斥量由唯一 runtime/translation unit 所有，在同一 namespace 的 header 中声明，或通过窄接口注入，明确 phase 依赖。验收要求真实 SDK 下重新配置、编译、链接共享库，进一步核验 JNI 和图执行。较早 capability-probe 二义性后续复核也已通过，不再列作当前阻断。

### P0-06：MNN 新拆模块存在生产构建阻断【真实参数复核】

定位：[mnn_native_engine.cpp](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:55)、[mnn_sdxl_pipeline.cpp](/E:/MAC/core/native/src/main/cpp/mca/mnn/mnn_sdxl_pipeline.cpp:7)、[mnn_session_manager.cpp](/E:/MAC/core/native/src/main/cpp/mca/mnn/mnn_session_manager.cpp:177)。

真实启用 `MCA_WITH_MNN_LLM=1/MCA_WITH_MNN_DIFFUSION=1` 的参数下：桥包含不存在的 `diffusion/mtok_tokenizer.hpp`；SDXL 模块包含不存在的 `prompt_language_rules.hpp`；session manager 使用未声明的 `extract_advanced_params` 和 `normalize_mnn_contract_enum`。这些新模块已进入生产 `target_sources`。

修复：对照真实第三方版本与 HEAD 迁移接口、include 和实现；不要以关闭 MNN 宏、注释源列表或新增空实现消除错误。验收：清晰区分 enabled/disabled ABI 配置，在启用 MNN 的 ARM64 目标完整编译链接并恢复 P0-01 至 P0-04 的行为。

### P1-01：GGUF MTP speculative owner 在第一块后丢失【确认，生产所有权缺陷】

定位：[native_engine.cpp](/E:/MAC/core/native/src/main/cpp/native_engine.cpp:802)、[逐块分支](/E:/MAC/core/native/src/main/cpp/native_engine.cpp:1356)、[真实 unique_ptr 类型](/E:/MAC/third_party/llama.cpp/common/speculative.h:95)。

`make_speculative_environment_locked()` 把 `g_speculative` move 到局部 environment。prepare 路径会 move 回去，逐块 generate 路径在所有 return 前均没有恢复。真实启用 llama 的类型是带 deleter 的 unique_ptr；局部变量销毁会释放 speculative 对象，global 已为空，后续块无法维持同一 speculative state。

修复：persistent session 持有唯一 owner，环境只借用；或明确用覆盖所有 return/throw 的 scope guard 恢复。验收必须连续生成至少多个 chunk，覆盖 stop、异常和 max tokens，检查生命周期与重复释放。单次调用/语法通过不够。

### P1-02：会话租约使用线程锁承担异步生命周期【确认，未接线 Runtime】

定位：[EngineSessionOwner.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/EngineSessionOwner.kt:39)。

获取 lease 时持有 ReentrantLock，直到 lease.close 才释放；close 在非持锁线程只将 active=false，永远不 unlock。跨协程线程关闭后，其他线程会持续获取失败，close/advanceGeneration 还可阻塞。close 也未幂等：旧 lease 第二次关闭可能清除新 lease 的 active 标志、释放当前线程新租约持有的锁。获取租约前检查 isClosed 和加锁后的状态之间也存在竞态。

修复：以原子 lease token/owner identity、Semaphore 或有明确规则的协程互斥保护短临界区；不要跨异步请求长期持有 ReentrantLock。关闭必须 compare-and-release 一次。验收：跨线程关闭、重复关闭、关闭旧租约时新租约已开始、并发 session.close/acquire。

### P1-03：统一 Facade 仍未执行、未接入生产【确认，完成度缺口】

定位：[ChatExecutionFacade.kt](/E:/MAC/app/src/main/java/com/muyuchat/mca/facade/ChatExecutionFacade.kt:30)、[ImageGenerationFacade.kt](/E:/MAC/app/src/main/java/com/muyuchat/mca/facade/ImageGenerationFacade.kt:30)、[LocalApiRuntimeOwner.kt](/E:/MAC/app/src/main/java/com/muyuchat/mca/LocalApiRuntimeOwner.kt:14)。

两个 Facade 仅登记请求、Accepted、取消记录和回调订阅，没有执行器。LocalApiFacadeAdapter 只由测试构造；真实 Local API 仍走既有 runtime/provider。ChatCommand.sessionId 未落实到实际 session 租约；identity 默认 generation/worker 没有接入真实链。

这不证明当前既有 API 无法工作，但证明“已统一三入口”尚未完成。直接替换现有入口会把请求变成永久 Accepted/SSE 等待。修复需要真实 UseCase/Executor、session/worker identity、终态与清理，并在生产 composition root 接线。验收须 UI/认证 API/Worker 三入口执行同一行为，再删除旧业务路径。

### P1-04：Kotlin/native 生命周期 Owner 仍有假加载语义【确认，未接线骨架】

定位：[BackendRuntimeOwner.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/BackendRuntimeOwner.kt:53)、[ModelLifecycleFacade.kt](/E:/MAC/app/src/main/java/com/muyuchat/mca/facade/ModelLifecycleFacade.kt:24)、[mnn_engine.cpp](/E:/MAC/core/native/src/main/cpp/mca/mnn/mnn_engine.cpp:11)。

Kotlin createEngine 创建立即 ready=true 的匿名 engine，未执行 native load；engineId 使用毫秒+文件名，有碰撞覆盖风险。native MnnEngine 使用 sentinel 表示 loaded，并以 byte+10 做测试用 tokenizer。源码已诚实说明骨架，但仍处在 main/生产目标中；ModelLifecycleFacade 又将 ready 用作加载状态。

修复：真实 BackendPort/EnginePort 绑定，success 只来自 native smoke；测试骨架搬到测试 fixture 或严格隔离 target；使用唯一 engine identity，明确替换/释放旧 owner。验收损坏模型不可 loaded、创建多个同名模型不会覆盖资源、关闭后 native 资源确实释放。

### P1-05：Runtime admission、epoch、shutdown 缺少原子屏障与兜底清理【确认，未接线 Runtime】

定位：[RuntimeSupervisor.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/RuntimeSupervisor.kt:32)。

epoch 先增加再 drainAll/cancelAll，新 epoch 同时登记的请求也可能被 drain；Facade dispatch 未在 supervisor 上检查健康并原子入场。shutdown 与 registerBackend 的 check-then-put 可交错；同 kind 覆盖旧 backend 不释放。关闭一个 backend 抛异常会中断剩余关闭，且 isRunning 已 false，后续 shutdown 直接返回。

修复：统一 admission transaction、按 epoch 精确 drain、阻止关闭期间新注册、聚合关闭错误并 finally 清理每个 owner。验收并发入场/换 epoch/关闭、backend.close 故障与资源计数归零。

### P1-06：请求状态有双重真相，公开可变对象绕过 Registry【确认，未接线 Runtime】

定位：[RequestRegistry.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/RequestRegistry.kt:18)。

stateMachine 和 terminalReached 各自保存终态。register/getEntry 暴露可变 stateMachine；调用 entry.stateMachine.abort/timeout 后，Registry.isActive 仍可能为 true。evict 可移除非终态请求；drainAll 留存条目、没有统一回调/取消订阅清理。

修复：唯一 owner 完成状态转移和一次终态提交；对外只给只读 snapshot；evict/cleanup 匹配完整 attempt identity；终态记录与所有 tracking 表的清理由统一 settlement 完成。验收故障/取消/重复终态/新 attempt 重用 ID 下状态与资源一致。

### P1-07：取消记录的身份和时间语义不稳【确认，未接线 Runtime】

定位：[RequestCancellationController.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/RequestCancellationController.kt:25)。

register 直接按 requestId 覆盖，unregister 无 attempt 比较，旧请求清理可删除新 attempt。先 CAS cancelRequested，再写 cancelReason，观察线程可看到已取消但 reason 尚为空。超时用 currentTimeMillis，会受墙钟调整影响。Facade 已修复本地重复预留顺序，但 controller 公开 API 本身仍可产生这些问题。

修复：完整身份/CAS 注册和条件删除，单个不可变 cancellation state，单调时钟计算 deadline。验收 ID 重用、旧 cleanup 延迟、取消读写竞态和模拟时间调整。

### P1-08：回调失败可能吞掉终态，状态与投递未联动【确认，未接线 Runtime】

定位：[CallbackRouter.kt](/E:/MAC/core/engine/src/main/java/com/muyuchat/core/engine/runtime/CallbackRouter.kt:43)。

router 先更新 lastSequence/terminalDispatched，再调用 consumer；consumer 抛异常后只累加 callbackFailures，终态已被视为投递完成。同序列重放被拒绝，Registry 没有在这里获得可靠 settlement。consumer 还在 synchronized(route) 内执行，慢回调长期占有路由临界区。

修复：定义清楚“终态记录、终态可读取、回调尝试、客户端确认”的不同含义，由请求 owner 保持可恢复终态；失败形成带身份的错误和清理，投递在短临界区之外执行有序队列。验收 consumer 抛错、慢消费者、取消/终态并发且最终可查询真实终态。

### P1-09：新 Local API SSE 适配器会静默丢帧【确认，尚无生产构造点】

定位：[LocalApiFacadeAdapter.kt](/E:/MAC/app/src/main/java/com/muyuchat/mca/adapter/LocalApiFacadeAdapter.kt:105)。

两个分支使用 Channel.BUFFERED，忽略 trySend 失败。eventBridge 已把 terminal 标为 emitted 时，无论终态是否进入 channel 都 close。慢消费者可丢 token 和最终帧；没有人收集 flow 时，finally 中的取消也不会运行。

修复：显式背压与有界队列策略；token/terminal 不允许静默丢弃；断连和未开始收集有明确 owner/timeout；终态可恢复读取。验收超过实际缓冲容量的事件、慢读、断连、终态到达时队列已满。该发现不能被描述成既有生产 SSE 已确定坏掉。

### P1-10：MNN 图像周边合同/进度/资产终验也被删减【确认部分删除，运行影响待设备验证】

定位：[run_mnn_sd15_interpreter_direct](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1021)、[generate](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1401)。

HEAD 的 direct 执行包含 Textual Inversion 的 prepare/finalize execution assets、selection/consumption 核对和完整数值/调度证据。当前桥仅保留相关 using 声明，没有相应执行调用；有 `if (!useCommunityClip || !has_community_clip_bundle(...)) { /* contract condition satisfied */ }` 的空判断。JNI generate 将 progressCallback 传 nullptr，返回结果也不再保留同等 nativeEffective 证据。原先 generate 的请求状态/取消/错误结算大量逻辑未完整迁移。

后果包括进度不更新、资产选择与执行脱节、变更中资源未终验、API 因缺证据而拒绝结果、取消和下一请求污染风险。缺失 cancel/reset JNI 已由 P0-01 单列，不重复宣称已经设备复现。

修复：恢复原有执行资产事务和真实 evidence，不向响应补写固定字段。验收使用 TI/共享 CLIP、资源被替换、取消与失败重试，确保选择→消费→最终产物哈希闭环。

### P1-11：MNN JNI 没有统一异常边界【确认源码缺口】

定位：[beginCompletion](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1270)、[build_mnn_config](/E:/MAC/core/native/src/main/cpp/mnn_native_engine.cpp:1190)。

当前 MNN JNI 导出没有通用 guard/try-catch。`json::value` 的字段类型不符、sampling/config helper 或其他 native helper 可抛 C++ 异常。恢复真实执行后，异常不能越过 JNI 边界。通用 `mca_jni_boundary` 被链接并不证明每个入口实际采用了它。

修复：每个 JNI 入口采用返回类型一致的边界包装；保留已挂起 Java 异常；按错误分类映射，保证 scoped JNI/native 资源释放。验收错误 JSON 类型、非法配置、helper 抛错、回调挂起异常、分配失败路径；不可把全部失败改成空串/false。

### P1-12：大量源码字符串合同没有保护真实行为【确认】

定位：[LocalImageProductClosureTest.kt](/E:/MAC/app/src/test/java/com/muyuchat/mca/LocalImageProductClosureTest.kt:515)、[MainViewModel.kt](/E:/MAC/app/src/main/java/com/muyuchat/mca/MainViewModel.kt:507)、[stable_diffusion_bridge.cpp](/E:/MAC/core/sd-native/src/main/cpp/stable_diffusion_bridge.cpp:1139)。

测试用 readText/indexOf/contains 定位函数和关键短语；生产文件出现“Contract needles preserved for static inspection”注释，保留字符串即可让一些断言通过。最新 4 项 app 失败中，两个是迁移后旧函数定位丢失，预览测试也有 substring 的索引失败。这些失败需要修，但不能据此直接认定预览逻辑已经丢失——resolver 和 preview publisher 在新模块中仍存在。

更严重的是 P0 的真实生成退化没有被这些文本合同阻断。修复：行为测试优先，覆盖 executor 的真实输入/输出/调用参数、JNI 符号和多 chunk；源码合同仅作静态补充，禁止注释作为行为满足证据。迁移测试读取位置时必须同步补足行为验证，不能仅追求重新变绿。

### P1-13：native 编译门禁没有覆盖真实构建图【确认】

定位：[verify_native_targets_compile.py](/E:/MAC/tools/native/verify_native_targets_compile.py:125)、[CMakeLists.txt](/E:/MAC/core/native/src/main/cpp/CMakeLists.txt:183)。

脚本只解析 add_library，漏掉 target_sources；未覆盖 core/sd-native；统一宏没有启用真实 MNN LLM/diffusion，某些模块的重要分支被跳过。它只做语法，不链接，不核验 JNI，不验证计算。终审只接入 QNN runtime 的检查，生产 QNN JNI 桥的失败可以绕过去；21:10 的语法通过仍没拦住 P0-05 的符号不匹配。语法脚本打印“70 native modules”实际是 sources 计数，也易误读。

修复：CMake File API/compile DB 产生目标和源清单，按目标真实宏覆盖所有启用分支；生产库必须链接；增加 JNI/调用合同和产物验证。验收故意让 target_sources 语法出错、缺符号、缺 export、错误宏和未链接代码时，门禁都应失败。

### P1-14：CI 没有生产 native 验收闭环【确认，脚本已诚实披露】

定位：[android-ci.yml](/E:/MAC/.github/workflows/android-ci.yml:1)。

当前 CI 明确是 QNN stub 的 JVM compile/test/lint；MNN 生产 runtime 依赖受控 Windows 构建。该 CI 有价值，但不会拦住生产 SDK、C++ 分支和图执行回归。真实 native、受控 SDK和发布目标没有与提交强绑定的强制验收流水线。

修复：保留快速 JVM CI，另设受控真实 native/release runner，不公开私有 SDK；每提交生成原生链接/JNI/产物哈希和设备验收证据。不得用每机型认证或 allowlist 充当设备兼容性准入。

### P1-15：综合 S 级的设备/故障/产物证据尚未闭合【确认，验收缺口】

定位：[fault_matrix_report.json](/E:/MAC/tools/fault-injection/fault_matrix_report.json:1)、[architecture-final-report.json](/E:/MAC/architecture-final-report.json:1)。

22 个故障场景仅驱动 Python 合同模型；production injections=0、device verified=0。无当前 release APK。现有 debug APK 是 2026-10-09 03:57 的 412,462,931 字节，早于这里的桥改动，不能证明当前源码可用。没有本次快照的 crash/ANR、冷启动/首 token、连续加载卸载、长期 native/JNI 内存、温控/后台/worker 重启等实测证据。

修复：绑定源码/构建/模型/设备/产物哈希，真实注入失败并核查 cleanup；确定按能力和设备环境解释的 SLO，再完成短回归和长稳。一个代表性兼容 ARM64 设备通过生产 UI 和认证 API 即满足通用设备准入规则；其他设备用于兼容性发现，不得变成白名单。

### P2-01：MainViewModel 瘦身仍保留广泛可变依赖【确认，维护性】

定位：[MainViewModelChatExecutionDelegates.kt](/E:/MAC/app/src/main/java/com/muyuchat/mca/MainViewModelChatExecutionDelegates.kt:12)、[MainCoordinatorHostAdapter.kt](/E:/MAC/app/src/main/java/com/muyuchat/mca/composition/MainCoordinatorHostAdapter.kt:29)。

扩展函数以 MainViewModel 为 receiver，sendPreparedMessage 还把 this 传回 coordinator。HostAdapter 转发 engine/container/store/job/lock 和大量回调，VM 有许多 internal 状态和长行组装。物理移动降低行数，但依赖面、修改影响范围和 owner 清晰度没有相同比例改善。

修复：每个 UseCase 只注入自己的 Port/State/Lease；流程状态进入唯一 owner，composition root 只组装；业务 API 不暴露整个 VM/container。预算建议为 VM 约 300–600 行、普通 owner/usecase 200–700 行，并辅以函数长度/依赖数检查，不能硬凑行数。

### P2-02：复杂度被搬到新的巨型模块【确认，维护性】

QNN semantic pipeline 3,876 行，其中 qnn_semantic_generate_json 从约第 605 行延伸到文件末尾，仍是约 3,200 行流程；executable graph 约 1,700 行。LocalChatRunner 3,473、McaInferenceService 3,146、CorePortAdapters 2,504、ModelStoreRepository 2,259、RuntimeParameters 2,123、McaAppContainer 1,579 行。完整清单见 CSV。

推荐按 validate/conditioning/session/UNet iteration/CFG/scheduler/VAE/output/evidence 分阶段，每阶段输入输出有类型、资源由 session/request owner 持有；phase 约 150–500 行，复杂数值模块 300–900 行，临时迁移桥可 1,500 行以内，最终 JNI 边界通常约 100–400 行。不是每个数据 catalog 或 UI 长文件都天然有缺陷；流程大函数与隐藏状态优先。

### P2-03：模块物理边界和架构检查只覆盖局部【确认】

定位：[check_architecture_rules.py](/E:/MAC/tools/architecture/check_architecture_rules.py:170)、[core/engine/build.gradle.kts](/E:/MAC/core/engine/build.gradle.kts:78)。

domain 零 Android 只检查 contract package；core:engine 本身仍为 Android library、依赖 core:native，非 contract 的 4 个文件使用 Android。Worker UI 检查只扫 app/.../worker，生产根包中的 *WorkerService 未纳入。没有全图依赖方向/循环、用途边界与完整函数预算。

修复：合同/domain 物理 JVM 模块、Android/native adapters 分离；根据真实 target/Gradle 依赖做约束；实际 service/provider 作为入口清单。不能将“8 条局部规则通过”解释成全项目已解耦。

### P2-04：编译告警和错误处理需按语义治理【确认债务，当前告警总量未重新编译】

已有历史 Kotlin 告警包括：data class 非公开构造函数通过生成的 copy 暴露、Java/Kotlin nullability 不一致、对可空接收者调用、恒真条件/无效 Elvis、冗余转换/!!/safe call、弃用 API、override 参数名不一致。

`RuntimeParameters.kt` 的 CanonicalParameterSet/各种 Signature 私有构造意在通过工厂保证规范值；公开 copy 会绕过工厂，属于应优先审查的封装告警。nullability 告警可能转成 Kotlin 升级后的编译错误或运行时异常。其余风格建议不能与 native 功能丢失同级，也不能通过全局 suppress 冒充治理。

当前源码扫描有 catch(Throwable) 142 处、runCatching 1,457 次、getOrNull 566 次、getOrDefault 194 次。**这些是文本次数，不是缺陷数**：getOrNull 包含集合访问，catch(Throwable) 中不少已有 CancellationException 透传或回滚。NativeMnnBridge.runnerReady 的吞错是已确认具体例子；其他位置需分别区分建议性探测、事务/资源清理、取消、真正失败和静默降级。

已有 lint XML 是 2026-10-02 的 126 warnings/0 errors；某些历史文档记 128，时间和版本不一致。历史日志也不能证明当前 Kotlin 还有“58/50”或已经零警告。本次没有全量重编译 Kotlin，当前数量应标记未复核。修复后应强制重新产生 warning 清单，逐项处理或记录窄范围理由。

### P2-05：证据文档与代码/门禁互相矛盾【确认】

当前 architecture-final-report.json 整体诚实标记 IN_PROGRESS_NOT_S_GRADE，但仍将 S-C 标记 PASSED，只凭桥 LOC/VM LOC；其中 5,386 行桥统计已经落后于 21:10 的约 5,415 行。当前有构建阻断与生产语义退化，S-C 的通过结论无效。

外部 `E:/MAC_REFACTOR_EVIDENCE/A11-final-integration-release-candidate.md` 仍写 release/fault matrix 全部通过、S_GRADE_READY；其证据不适用于当前代码。文档需要显著过期标记，不能只更新一个 JSON、保留另一个相反的总验收。

修复：每个通过项附 source/target/artifact/model hash、命令、退出码、设备范围；缺任一必需证据则 incomplete；完成度由功能映射和唯一 owner/旧路径删除判定。禁止代码生成或自填计数作为真实执行证明。

### P2-06：发布体积、R8 与供应链尚未形成可执行验收【确认设置与证据缺口】

定位：[app/build.gradle.kts](/E:/MAC/app/build.gradle.kts:232)。release 的 isMinifyEnabled=false；较早 debug APK 约 393.4 MiB（412.5 MB），包含多 ABI/多个后端，并非当前 release 体积，不能拿它直接给 release 定超标结论。SBOM 文件存在不等于可复现构建、许可证/漏洞审查和完整依赖追踪已完成。

修复：先恢复可用构建，再制定按 ABI/后端区分的发布预算；在独立候选产物启用 R8/shrink 并验证 JNI、反射、serialization/服务入口；检查 APK 内全部 ELF 的 16 KB 对齐、签名、依赖锁定、SBOM 和哈希。不能为了减包删掉已有用户能力，不能盲目开启混淆。

## 4. 功能丢失风险矩阵

| 能力/链路 | 本次结论 | 验收重点 |
| --- | --- | --- |
| MNN 文本/多轮/视觉聊天 | 必需 JNI 缺失、真实模型未创建、输出为空，确认源码退化 | 加载、首 token、多块、UTF-8、视觉、多轮缓存、停止、释放 |
| MNN Sana | 成功 stub，确认真实生成丢失 | 真实 session、图执行、steps、conditioning、可解码文件 |
| MNN SD1.5 | 零 embedding/零 latent/错误 scheduler 输入，确认语义退化 | UNet 预测与 CFG、seed 噪声、scheduler、实际图片与证据 |
| QNN SD1.5/SDXL | 桥语法已通过，但互斥量符号不匹配；MNN conditioning 入口又缺失 | 实际 target 链接、conditioning payload、graph smoke、UI/API |
| GGUF 基础聊天 | JNI 名称保留、桥语法通过；本次未实机验证 | 不可据此宣称行为完全保持 |
| GGUF MTP | owner move-back 缺失，确认连续 chunk 的所有权问题 | 多 chunk/stop/异常/缓存/重载 |
| SDCPP 图像、LoRA/TI、ControlNet、img2img/inpaint、Ultrafix | 桥和选定模块语法通过、8 个 JNI 名称保留、实际生成调用仍存在；不是完整行为证明 | 所有参数/效果/证据/取消/预览/输出事务的逐项回归 |
| 图像 token 计量和共享 CLIP | MNN native 入口丢失，可能牵连其他后端的 preflight | backend 的实际 tokenizer 与准确/近似语义 |
| 既有 Local API/Worker | 仍使用原有生产路径；新 Facade 并未替换完成 | 认证、SSE、幂等/重连、断连取消、持久化终态 |
| 新 LocalApiFacadeAdapter | 无执行器，且有丢帧/终态风险；尚未生产接线 | 慢消费者/队列满/未收集/重复请求/完整身份 |
| 持久化/导入/模型下载/云端/Agent/知识库/更新 | 本次未确认普遍丢失；拆分仍有改动风险 | VM 方法映射、数据库 migration、导入导出 golden、取消/回滚/进程重启 |
| 通用设备准入 | 两个本地 safeguard 仍由 .git/info/exclude 隔离；不可用合同 package 扫描代替全入口检查 | 未知 profile 可操作、generic fallback、真实 load 决定结果 |

“未确认丢失”不等于“证明安全”。最后一行之外，本次没有以设备型号或认证作为功能准入结论。

## 5. 验证结果应如何解读

| 检查 | 结果 | 证据范围 |
| --- | --- | --- |
| app 最新 JUnit XML | 256 suites / 2,399 tests / 4 failures / 8 skipped，2026-10-09 20:20–20:21 CST | 当前最新已有运行，后续源码修改未自动被证明 |
| core:engine XML | 435 tests / 0 failures / 2 skipped，2026-10-09 00:52 CST | 较早版本结果 |
| api:local XML | 221 tests / 0 failures，2026-10-09 03:41 CST | 较早版本结果 |
| feature:chat XML | 273 tests / 0 failures，同上 | 较早版本结果 |
| 通用 native 语法脚本 | 70 sources，3 个源失败 | 脚本覆盖有缺口；不可读成 70 个完整生产库 |
| 真实参数：MNN/QNN 等 6 个 TU | 4 失败，2 通过 | MNN bridge/session/SDXL 与 QNN bridge 失败；QNN capability probe、MNN diffusion pipeline 通过 |
| QNN 工作区更新后重查 | 语法通过；桥 object 引用与实际定义 header 的 mutex 符号不同 | 21:10–21:13；覆盖初始检查的 QNN 语法状态，完整链接未执行 |
| 补充真实参数：GGUF/SD 5 个 TU | 5/5 语法通过 | native_engine、SD bridge/evidence/parser/response；没有链接或模型运行 |
| native host 重跑 | 两个已存在的可执行文件 2/2 通过 | 13 个注册测试中另外 9 个执行文件缺失；两个 CMake 脚本未在本轮重跑 |
| 故障矩阵 | 22 个 Python 合同场景；0 production/0 device | 架构模型证据 |
| release / 设备 / 长稳 | 本轮未完成 | 不能签综合 S |

4 项 app 失败的原文与对应 XML 路径见 JSON。不得用删除断言、添加关键词注释、关闭能力宏或不执行测试的方法消除红灯。

## 6. 修复执行顺序与退出条件

### 第一步：恢复生产实现，暂停按行数压缩

先关闭 P0-01 至 P0-06，再关闭 MTP 所有权问题。建立 HEAD/可靠历史版本→新实现的功能映射；逐项搬迁真实函数，保留成功与失败语义。已确认的 P0 必须阻断新候选 APK 发放。可以查阅可靠历史实现恢复行为，但不得 reset/覆盖其他 Agent 的工作树。

退出条件：启用全部既有能力的 ARM64 配置完成 native 编译和链接；JNI 声明/符号对应；真实模型可生成；任何成功响应均有有效产物/真实 token/实际 evidence；关键变体和取消/重载通过。

### 第二步：把 owner/request/session 合同做成可信实现

修正 lease 线程/幂等、完整 attempt identity、单调 deadline、原子 admission/epoch、终态 settlement、回调失败/SSE 背压。只做小而真实的 executor seam，给新 Facade 接上实际 backend。骨架禁止成为成功型生产实现。

退出条件：跨线程和故障竞态测试通过；无旧请求清理新 attempt；注册/路由/取消/资源计数在终态后归零或进入受控归档；UI/API/Worker 共用实际执行链后才删除旧路径。

### 第三步：完成责任拆分和编译器边界

QNN 大函数按 request stage 拆；MNN JNI 只做转换和委托；GGUF persistent session 所有资源；VM 不再作为业务万能 host；domain/contract 与 Android/native adapter 物理模块隔离。每次拆分先有 characterization/协议测试，后删旧入口。

退出条件：明确唯一 owner、目标依赖图无循环、真实入口落到同一 UseCase、旧业务路径删除、完整功能矩阵保持；行数预算仅作辅助信号。

### 第四步：完成产品可靠性与发布验收

真实 native CI、代表性 ARM64 生产 MainActivity 与认证 Local API、取消/异常/资源长稳、故障注入、R8 候选产物、16 KB/签名/SBOM/依赖锁与体积预算。先定义可解释的目标，再拿证据关闭，不能从短 host 测试推导 crash-free/ANR 指标。

退出条件：同一源码/构建/模型/产物哈希下所有必需功能和可靠性证据通过，且无 P0/未受控 P1。只有此时才可以讨论综合 S 级候选；团队长期演进和生产可靠性仍需持续验证。

## 7. 可直接交给后续执行 Agent 的约束提示词

```text
在 E:/MAC 修复重构回归。先阅读本审计报告和本地 AGENTS.md。当前代码不是 S 级完成态。
第一优先级是恢复真实生产行为：MNN 20 个 JNI 入口、真实 Llm load/prefill/流输出、Sana 真 session、
SD1.5 实际 embedding/seed 噪声/UNet→CFG→scheduler 链，以及 QNN/MNN 当前生产编译链接错误；
然后修复 GGUF MTP 的 speculative 唯一 owner。在这些关闭前不要继续为了行数压缩或宣称完成。

为你负责的功能建立可靠历史版本→新函数/模块→JNI→实际调用→行为测试→设备证据映射。
修复必须包含真实实现、真实失败、取消/释放，不得 stub、假 success、固定耗时/哈希、注释关键词、
删测试、关宏、移出 target_sources 或使用旧 APK 冒充当前源码验收。

每个执行 Agent 只改约定写集，特别是 CMake、JNI 桥、共享 header 和最终报告必须由指定集成 owner 合并。
不要 reset/clean/revert 他人代码。先 native 构建和 JNI 验证，再行为测试，再实际 UI/认证 API。
Runtime/Fascade 接线前先修 session lease、身份/终态、admission/epoch、错误和 SSE 背压；
旧路径只在行为合同证明后删除。报告命令、退出码、源码/库/APK/模型哈希、未验证项。

AGENTS.md 和 UniversalDeviceAdmissionPolicyTest.kt 始终本地排除，禁止 stage/commit/push。
未知芯片/profile 不得阻断下载导入加载运行；用 generic compatible fallback 和真实 load 决定结果。
交付逐项修复与验收证据；无证据不得写 S_GRADE_READY。最终 APK 必须来自修复后的同一源码快照。
```

本报告列出了本次证据能够支持的全部发现和主要待验证风险；静态审计与现有测试无法证明项目已不存在其他缺陷。后续应按功能清单逐项取得行为和设备证据，避免再以文件数、行数或自生成报告作为完成标志。
