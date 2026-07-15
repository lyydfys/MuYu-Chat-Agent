# MCA 本地模型运行时 Bug 修复方案

- 更新时间：2026-07-15
- 状态：运行代码已实施；MNN 多模态代表机与 Qwen3.6 35B-A3B 稀疏 MoE 正式验收通过；Gemma 专项正式实机矩阵独立记录
- 范围：GGUF / llama.cpp、本地 MNN 模型包、Agent 参数、助手与会话参数、正式 UI、Local API

## 0. 实施与验收结果

本文件后续“当前缺陷”“临时处理”和“推荐实施顺序”保留为实施前基线与设计依据；本节是 2026-07-15 当前代码和发布验收的权威状态。

- 参数已按模型执行、助手生成、会话绑定和内部评测分层；llama.cpp、MNN、QAIRT 使用各自字段策略，未知或跨 runtime 字段不会直接透传 native。
- 已落地 `ModelRuntimeIdentity`、desired / resolvedLoad / activeLoaded / committedExecution / runtimeOverride / effectiveExecution 六签名、pending / active / LKG 事务、journal 恢复、一次性重载和有界回滚。
- 首次加载执行能力发现、安全基线和正确性 canary；普通重载复用该模型、本设备和 runtime 的已提交安全 profile。快速、标准、深度调优共享正确性和安全硬门槛，不静默修改 sampling、模板或输出长度。
- MNN 完整组件/ZIP 预检、路径安全、暂存和原子安装已实现；单个 `llm.mnn` 或缺组件包会返回准确缺失项。
- MainActivity 与认证 Local API 共用 coordinator、生命周期和请求 trace。生成开始进入 `GENERATING`，完成、错误、手动停止、清空会话和转后台均收口；空闲控制面返回 `busy=false / code=idle`。
- MNN 视觉已按功能 contract 对所有兼容 ARM64 设备默认开放，不使用芯片或逐机 `visionValidated` 白名单。代表机通过只提升功能准入，不跨设备复制线程、batch、KV、上下文或其他 profile 数值。
- 稀疏 MoE 准入只相信 GGUF architecture metadata，文件名只用于参数规模提示。`<=16 GiB` 设备强制 `mmap=true / mlock=false / n_parallel=1`，限制 `n_ctx/n_batch/n_ubatch<=4096/2048/256`，关闭大于 4 GiB 模型的整文件 mmap 预取，并禁止 mmap 失败后回退整包匿名内存。
- 精确验证的 Qwen3.6 35B-A3B SHA 才启用 Q4 KV、Flash Attention 与 `draft-mtp/2`。自适应配置按当前加载模型生成，不再借用全局推荐模型；规则 fingerprint v3 自动隔离此前错误提交的 `spec_type=none` profile，模型被重命名时仍由 SHA 能力补齐 MTP。

最终自动化与构建：

- `testDebugUnitTest`：675 tests，0 failures，0 errors，7 skipped。
- arm64 `:app:assembleDebug`：通过；MNN vendor/runtime stamp 与 QAIRT typed headers 校验通过。
- 最终 APK：196,981,949 bytes，SHA-256 `44AD5A320B47CEE0AAA1E8DF6D8C1C2EE81C85ACF8343B6BBE533954978CE428`。
- APK Signature Scheme v2：通过；签名证书 SHA-256 `2619AC4CE0AD8397B84C77DF6BA165801FD4FAB1460470F22F1EB7B3E4F9A9CF`；Elite 安装后 `base.apk` 与本地 APK 同哈希。

正式产品入口 MNN 代表机验收：

- 设备：Xiaomi `25091RP04C`，`SM8750P`，Android 16，`arm64-v8a`，约 12 GB RAM；正式 `MainActivity`，PID `4795` / UID `10336`。
- 模型：`qwen35 4b mnn bundle`，`backend=mnn_cpu`，`loaded=true`，`runnerReady=true`，`visionReady=true`；profile `safe-7fce66351af721de` revision 1，committed / safe。
- UI：`ui-de0140fb9f2048bb93fb0ecff73b8820`，native sequence 2，单图 `prepared=1 / failed=0 / preprocessing=1`，图片 SHA-256 `082D730D128C448B9CE3B8EF094363BC2B89F0C58FE6388B1E9D9894E42FFEBA`；可见回答识别 blue circle、red square、`VISION-7K4P`。
- 认证 Local API：`chatcmpl-249fd09ee83448069070b4c0147597c1`，native sequence 3，另一张图片 SHA-256 `C3AC789D17C7E729AA5F02595C93D7419E278D0CC0072BF82288BC67A50D4493`；HTTP 200，可见回答为白色玩具机器人站在木质表面。
- UI / API 前后 modelId、profileId、activeLoaded、committedExecution、effectiveExecution 完全一致；`RuntimeOverride=NONE`；请求后 `engineLifecycle=ready`、`generationActive=false`、`busy=false / code=idle`。
- 限定日志窗口中 App 进程与 crash buffer 均无 FATAL、ANR、SIGSEGV、SIGABRT、OOM 或进程死亡，MainActivity 保持 resumed。

正式产品入口 Qwen3.6 35B-A3B 稀疏 MoE 验收：

- 设备：同一台 Elite `98a37aa7` / Xiaomi `25091RP04C` / `SM8750P`，`MemTotal=11,617,264 kB`；只使用正式 `MainActivity` 与认证 Local API，最终 PID `23089`。
- 模型：modelId `879398d3-1ad2-47e0-8006-f20fecb2e54d`，GGUF architecture `qwen35moe`，文件 `11,686,646,144` bytes，精确 SHA-256 `1FB8A998362EBB5F7F3C8ECE6D4803A74BA32211C751DE2E76B81E3379FBF050`。
- profile：`balanced-14e7004f7a978df6` revision 1，`committed / safe`，`reloadRequired=false`，`RuntimeOverride=NONE`；规则 v3 没有复用此前错误的 v2 `spec_type=none` LKG。
- effective：`backend=llama.cpp-cpu`，`mmap=true`，`mlock=false`，`mmapFallbackAllowed=false`，`mmapPrefetchEnabled=false`，`nCtx=4096`，`nBatch=2048`，`nUbatch=256`，`nParallel=1`，K/V=`q4_0`，Flash Attention=`on`，`specType=draft-mtp`，`specDraftNMax=2`。
- UI：`ui-0bd6319ae25f4bc4a2f68804118fbffc`，native sequence 2，可见回答严格为 `ELITE_UI_35B_OK`；Local API：`chatcmpl-51173d5c49ec4181b78ed446d1e10e8b`，native sequence 3，HTTP 200，可见回答严格为 `ELITE_API_35B_OK`。
- canary decode `1.29161 token/s`；API 请求约 `33,868 ms`，最终 decode `1.32319 token/s`。观测峰值 PSS `4,167,113 kB`，最终 PSS `3,868,133 kB`；结束时 `MemAvailable=5,406,000 kB`，进程存活，`busy=false`、`engineLifecycle=ready`、`generationActive=false`。
- 最终安装后的限定日志窗口无 App FATAL、ANR、SIGSEGV、SIGABRT、OOM、LMKD 或进程死亡；exit-info 最新记录仅为覆盖安装导致的 `PACKAGE UPDATED`。

按产品准入规则，Elite 双入口正式通过即完成 MNN 视觉代表性验收，并默认开放全部兼容 ARM64 机型；Gen2 仅作可提速时的辅助回归，不是准入条件。Qwen3.6 35B-A3B 已独立通过本轮 12 GB 级 Elite 正式产品入口验收，16 GB 及其他兼容 ARM64 设备使用同一稀疏 MoE 准入规则并各自在首次加载生成设备专属 profile。上述结论不能外推成 Gemma 4、QAIRT 或 MNN-Diffusion 的完整正式实机矩阵。

## 1. 背景与结论

本方案最初处理以下用户反馈：

1. Qwen3.6 35B-A3B APEX MTP GGUF 加载失败，但 UI 将参数错误误报为“模型文件不完整”。
2. 用户单独导入 llm.mnn 无法使用，不清楚 MNN ZIP 应包含哪些文件。
3. Gemma 4 对“今天日期”等实时问题产生错误答案。
4. Gemma 4 或其他 GGUF 在第二轮生成时出现：

       Native beginCompletion failed: -11
       Completion config changes load-bound fields; reload the model before generating.

5. Agent、助手、会话和模型共用一份全局参数，导致一个模型的加载参数污染另一个模型。

实施前已经确认的核心结论：

- 35B 截图中的直接阻断不是先由文件损坏造成。请求配置包含 main_gpu=1、n_cpu_moe=5，但 APK 只有 CPU backend；native 在读取 GGUF 前便会拒绝。
- 当前 loadFailureAdvice 使用“路径包含 .gguf 且消息包含 failed”判断文件损坏，范围过宽，掩盖了真实参数错误。
- 单个 llm.mnn 只是 MNN 计算图，不能构成可加载模型；必须导入完整组件集合。
- -11 是有意的 native 安全保护，真正缺陷在于 App 修改加载期参数后没有统一重新加载。
- 当前系统提示没有动态日期，离线模型不能可靠回答今天日期。
- 当前参数规则只能覆盖部分推荐模型和通用硬件条件，不能视为完整模型知识库。

## 2. 修复目标

### 2.1 用户目标

- 选择模型后能够得到与设备、运行时和模型匹配的安全加载参数。
- 切换助手、会话或 Agent 参数后，不再向用户暴露 -11。
- 参数需要重载时，用户能看到具体变化及“应用并重新加载”入口。
- CPU-only 设备不会携带 GPU/MoE 参数进入 native。
- MNN 导入界面能明确说明缺少哪些组件。
- 日期和时间问题由设备时钟提供权威答案，不让模型猜测。
- 所有错误优先显示可执行的中文原因，完整 native stats 放在可展开诊断区。

### 2.2 工程目标

- 模型加载参数、助手生成参数、会话参数彻底分层。
- 每个本地模型拥有独立、可版本化的加载配置。
- UI 与 Local API 共享同一套参数解析、加载签名和模型生命周期。
- 任何自动重载最多重试一次，不能形成加载循环。
- MNN ZIP 先检查、后暂存、再原子提交，失败时不污染现有模型。

## 3. 非目标

- 不承诺对任意未知 GGUF 自动找到性能最优值。
- 不为没有真实 backend 的设备伪造 GPU、NPU 或 MoE offload。
- 不把 debug Activity、native 直调或内部 smoke 当作最终验收。
- 不因一个未知模型缺少规则而阻止用户导入；未知模型使用保守兼容配置。

## 4. 根因矩阵

| Bug | 直接根因 | 结构性根因 | 实施前临时处理 |
|---|---|---|---|
| 35B 被误报为文件损坏 | CPU-only backend 收到 main_gpu=1、n_cpu_moe=5 | 全局参数跨模型复用；错误分类条件过宽 | 重置为 CPU 参数后重新加载，再核对文件 |
| 单独 llm.mnn 导入失败 | 缺少权重、配置、tokenizer、embedding | MNN 是组件包，但 UI 仍像单文件导入器 | 多选完整组件或导入完整 ZIP |
| Gemma 日期错误 | system prompt 没有当前日期 | 没有设备时钟工具；离线模型只能猜 | 开启联网或在提示中明确日期 |
| beginCompletion -11 | 当前请求的加载期参数与已加载参数不同 | 参数更新没有触发重载；未记录加载签名 | 修改参数后手动重新加载 |
| Gemma 普遍回答质量差 | 可能使用低比特 GGUF、错误上下文或错误模板 | 缺少量化质量提示和模型专项回归 | 新建对话，使用 IT 模型和更高精度量化 |

## 5. 参数体系重构

### 5.1 将参数拆成三类

#### A. ModelExecutionProfile

只属于模型实例、设备和 runtime，禁止保存到助手或会话。其中加载期字段变化后必须重新加载：

- n_ctx
- n_batch
- n_ubatch
- n_gpu_layers
- main_gpu
- split_mode
- n_cpu_moe
- cache_type_k
- cache_type_v
- flash_attn
- perf
- n_parallel
- spec_type
- spec_draft_n_max
- mmap
- mlock
- mmproj / projector
- MNN / QAIRT bundle fingerprint

模型执行 profile 还包含可热应用的执行字段：

- n_threads / n_threads_batch
- cache_reuse
- runtime 明确声明为 HOT_EXECUTION 的其他字段

模型执行 profile 同时引用模型行为策略：

- use_jinja / chat_template_mode / templatePolicyRef
- role 序列化、特殊 token、stop token 和 tool-call template

“能热应用”不等于“不影响语义”。use_jinja、chat_template_mode 和 templatePolicyRef 直接影响 role/prompt 与回答正确性；每次变化必须运行完整 template/correctness gate，不得进入纯性能搜索或在线自适应。cache_reuse 虽然目标是等价加速，也标记 affectsCorrectness=true 并验证缓存命中/未命中等价性。

字段是否能热更新不按名称猜测，由 runtime/native 版本的字段策略注册表决定。

建议数据结构：

    data class ModelExecutionProfile(
        val schemaVersion: Int,
        val modelId: String,
        val runtimeIdentity: ModelRuntimeIdentity,
        val loadBoundValues: LoadBoundValues,
        val hotExecutionValues: HotExecutionValues,
        val modelBehaviorValues: ModelBehaviorValues,
        val profileId: String,
        val revision: Long,
        val userOverrides: Set<String>,
        val resolvedAt: Long
    )

#### B. AssistantGenerationProfile

可以热更新，不应导致模型重载：

- system prompt
- temperature
- top_k
- top_p
- min_p
- repeat_penalty
- presence_penalty
- frequency_penalty
- reasoning mode
- hide reasoning
- stop words
- n_predict / max_tokens

助手卡的 paramsJson 迁移后只能保存这一层。

#### C. SessionBinding

会话只保存：

- assistantId
- local/cloud 模式
- modelId
- 可选的生成参数覆盖
- 仅供诊断的 observedProfileId

会话不能直接携带 n_ctx、KV、Flash、MTP、GPU/MoE 等加载期字段。

observedProfileId 只记录会话最后一次生成时的运行配置，用于诊断和可重现性；选择旧会话不得因此自动回滚模型 profile。

### 5.1.1 字段归属与运行时可变性注册表

建立唯一 ParameterFieldPolicyRegistry，每个字段记录：

- owner：MODEL_EXECUTION / ASSISTANT_GENERATION / SESSION_DIAGNOSTIC / INTERNAL_CANARY。
- mutabilityByRuntime：LOAD_BOUND / HOT_EXECUTION / GENERATION_ONLY / UNSUPPORTED。
- persistenceScope、apiOverridePolicy、validator、canonicalizer、behaviorClass、affectsSemantics、requiredGate 和依赖字段。
- 支持该分类的 runtime/native 版本范围和能力证据。

同一字段在不同 runtime 或 native 版本可以属于不同类别。未登记字段默认 UNSUPPORTED，不得透传 native。advancedJson 中的未知字段进入 quarantinedOverrides，可供用户导出诊断，但不参与加载或生成。

### 5.2 参数解析顺序

每次首次加载或切换模型时按以下顺序解析：

1. 运行时安全默认值。
2. 模型元数据规则。
3. 设备能力规则。
4. 用户性能模式。
5. 该模型自己的用户覆盖值。
6. 安全约束和依赖校验。

安全约束拥有最高优先级，用户覆盖不能绕过：

- 无 GPU backend 时强制 main_gpu=0、n_gpu_layers=0、n_cpu_moe=0、split_mode=none。
- 没有 MTP head 时禁止 draft-mtp。
- draft-mtp 启用时 cache_reuse 归零或明确标为 disabled_by_draft_mtp。
- 量化 KV 需要兼容的 Flash Attention。
- n_ubatch 不得大于 n_batch。
- n_ctx 不得超过模型、设备和内存安全上限。

### 5.3 参数来源必须可解释

解析结果应返回每个字段的来源：

    ParameterResolution(
        requested,
        resolved,
        sourceByField,
        warnings,
        reloadRequired,
        rejectedOverrides
    )

UI 示例：

    main_gpu: 1 -> 0
    原因：当前 APK 未注册 GPU backend

    n_cpu_moe: 5 -> 0
    原因：CPU-only 运行时不支持 MoE GPU offload

### 5.4 知识库分级

参数知识库不能再用“已完整覆盖”表述。规则证据、当前设备 profile 和产品准入是三个正交维度，禁止共用一个“VERIFIED”字段。

ruleEvidenceLevel：

- REPRESENTATIVE_VERIFIED：相同模型指纹、运行时和代表设备完成正式 UI + Local API 回归。
- ARCHITECTURE_COMPATIBLE：架构与参数依赖已知，使用保守配置，尚无该模型专项性能结论。
- UNCLASSIFIED：只应用最保守配置和能力发现，并提示用户未专项优化。
- RULE_BLOCKED：模型组件、架构或运行时明确不兼容。

另外使用 profileVerificationLevel=SAFE/COMPATIBLE/DEVICE_VERIFIED 和 featureCompatibility=OPEN/EXCEPTION。代表机通过只会提升 ruleEvidenceLevel 并将 featureCompatibility 设为 OPEN，不会把其他设备的 profileVerificationLevel 设为 DEVICE_VERIFIED。

每条规则必须包含：

- ruleSetVersion
- model family / architecture
- runtime
- quantization 条件
- backend 条件
- RAM 条件
- 来源和验证记录
- 是否影响正确性或仅影响性能

## 6. 35B 加载修复

### 6.1 正确的 CPU 基线

Qwen3.6 35B-A3B APEX MTP 在当前 Android CPU 路线的已验证关键值：

    n_ctx=4096
    n_batch=2048
    n_ubatch=256
    n_gpu_layers=0
    main_gpu=0
    split_mode=none
    n_cpu_moe=0
    cache_type_k=q4_0
    cache_type_v=q4_0
    flash_attn=on
    perf=true
    n_parallel=1
    spec_type=draft-mtp
    spec_draft_n_max=2
    use_jinja=true

draft-mtp 只能在读取模型元数据并确认恰好一个可用 MTP block 后启用。

### 6.2 加载前能力检查

在 MainViewModel.loadModel 进入 engine.loadModel 前调用统一解析器：

    resolveModelExecutionProfile(model, deviceCapabilities, savedOverrides)

若发现不支持的字段：

- 默认安全字段可自动归一化。
- 会改变用户明确性能意图的字段必须显示差异。
- 不允许把错误留到 native 后再显示长 JSON。

### 6.3 修复错误分类

新增 LlamaLoadFailureClassifier，按以下优先级分类：

1. UNSUPPORTED_RUNTIME_CONFIG
2. BACKEND_UNAVAILABLE
3. OUT_OF_MEMORY
4. MODEL_INTEGRITY
5. UNSUPPORTED_GGUF
6. PROJECTOR_MISMATCH
7. UNKNOWN_NATIVE_FAILURE

必须先匹配：

- Unsupported llama runtime config
- n_cpu_moe
- main_gpu
- GPU offload
- flash_attn
- cache_type
- spec_type

只有明确出现下列证据时才提示文件损坏：

- invalid GGUF magic
- unexpected EOF / truncated
- tensor data outside file
- 已知期望大小不一致
- SHA-256 不一致

删除当前“只要路径含 .gguf 且消息含 failed 就判为文件损坏”的条件。

### 6.4 文件完整性

固定推荐文件继续校验已知值：

    size = 11,686,646,144 bytes
    sha256 = 1fb8a998362ebb5f7f3c8ece6d4803a74ba32211c751de2e76b81e3379fbf050

本地 SAF 导入还需增加：

- 复制前读取 OpenableColumns.SIZE。
- 复制后比较目标长度。
- 对目标文件重新读取 GGUF header，不能复用复制前 metadata。
- 计算完成后再注册 manifest。
- 失败时删除未注册目标文件。

## 6A. 智能调参完整适配

“智能调参完成”不能只表示存在几个模型名称判断或线程测速。正式目标是建立安全、可解释、可复用、可回滚的闭环调优系统。

当前实现还不符合这个定义：

- TuningPlan 同时携带 n_ctx / mmap 等加载字段和 sampling 生成字段，应用后没有统一的重载事务。
- loadModel 仍使用全局当前参数，并提示用户到 Agent 页手动运行调试；不会先为新模型生成专属加载配置。
- 现有短基准主要按 token/s、TTFT 和温控评分，没有验证回答语义、chat template、视觉输入或工具调用正确性。
- 调优记录没有绑定模型、设备、runtime 和 native 指纹，无法安全作为 last-known-good profile。
- 当前的模型名称特判和通用线程规则只是初始推荐器，不是完整的智能调参系统。

实施后的默认行为必须是：首次加载新模型时自动完成能力发现、安全基线和快速正确性校准；已有同身份稳定配置时直接复用，不在每次重载时反复长时间跑全量调优。

### 6A.1 调优原则

优先级必须固定为：

1. 回答正确性和模板正确。
2. 不崩溃、不 ANR、不出现 native signal。
3. 内存安全和生命周期稳定。
4. 温控、电量和持续性能。
5. TTFT。
6. decode token/s。

不能为了 token/s 牺牲答案正确性、上下文、模板、视觉输入或稳定性。

智能调参的输出必须拆分，不再由一个 TuningPlan 混合覆盖全局 GenerationParams：

- ModelExecutionProfile：模型/设备/runtime 专属的加载期和热执行参数。
- GenerationRecommendation：模型家族的 sampling、reasoning 和输出长度建议，只能由用户选择应用到助手，不能自动覆盖助手自定义参数。
- CanaryEvaluationParams：调优内部固定评测参数，任务结束后丢弃，不持久化为用户配置。

balanced / speed / quality / longContext profile 只表示执行配置变体。qualityProfile 不得暗中修改 system prompt、temperature、reasoning mode 或 n_predict。

### 6A.2 调优身份

只保留一份稳定运行时身份，禁止跨模型、跨设备直接复用：

    ModelRuntimeIdentity(
        artifactFingerprint,
        modelMetadataFingerprint,
        tokenizerAndTemplateFingerprint,
        projectorOrBundleFingerprint,
        runtime,
        runtimeCapabilityFingerprint,
        deviceCapabilityFingerprint,
        ruleSetVersion,
        correctnessSuiteVersion,
        safetyEnvelopeVersion
    )

    TuningProfileKey(
        identityHash,
        installationScopeId,
        profileKind
    )

artifactFingerprint 对 GGUF 为模型文件 SHA-256，对 MNN / QAIRT 为完整 bundle 的稳定 Merkle fingerprint，必须覆盖全部必需组件、相对路径和文件长度。projector、tokenizer 和 chat template 分别参与指纹，不得只用模型名称或主文件路径。

runtimeCapabilityFingerprint 包含 ABI、native library SHA-256、llama.cpp / MNN / QAIRT 版本、已注册 backend、driver/runtime 和字段能力。deviceCapabilityFingerprint 只包含稳定硬件/系统属性：SoC、ABI、总 RAM 档位、稳定 CPU 拓扑、Android 大版本和系统 low-memory threshold。当前可用内存、温度、电量、充电、前后台、系统压力和临时 cpuset 只进入 MeasurementEnvelope / RuntimeOverride，绝不参与 identityHash。

installationScopeId 在首次安装时随机生成，不可导出且不参与系统备份。它不是 IMEI、序列号或其他硬件标识；deviceCapabilityFingerprint 决定安全搜索空间，installationScopeId 阻止数据库/整机迁移把 ACTIVE/LKG 状态带到另一设备。profileId 和 profileRevision 只属于 execution_profile 记录，修订 profile 不会创建新 ModelRuntimeIdentity。

不得收集或保存 IMEI、序列号等用户设备身份信息。

下列任一项变化时配置自动失效并回到安全基线：

- 模型文件或 MNN bundle fingerprint 变化。
- APK native library 变化。
- llama.cpp / MNN / QAIRT runtime 版本变化。
- backend 能力变化。
- 设备 SoC、RAM 档位或系统大版本变化。
- 参数规则版本变化。
- chat template、tokenizer、projector、正确性套件或 SafetyEnvelope 版本变化。

### 6A.3 能力发现

调参前先完成只读能力发现：

- GGUF general.architecture、量化、层数、上下文上限。
- 是否 hybrid / recurrent。
- 是否包含 MTP / nextn_predict_layers。
- chat template 和 Jinja 能力。
- projector / vision 能力。
- MNN model_type、visual/audio、PLE、组件 fingerprint。
- QAIRT 芯片、runtime、context binary 和真实 NPU admission。
- CPU 核心拓扑、可用 RAM、内存阈值、电池、温度。
- 实际注册的 backend device，不能仅根据 SoC 名称推断。

能力发现结果生成 ModelRuntimeCapabilities，作为后续所有搜索的硬约束。

metadata 必须来自受限的文件解析器或不创建大额内存上下文的 native probe。模型名称、文件名正则和用户显示名只能用于 UI 提示，不能单独开启 MTP、GPU/NPU、视觉、chat template 或工具调用能力。

### 6A.4 安全基线

每个运行时先生成可加载的保守基线：

- llama.cpp CPU：GPU/MoE 为 0，保守上下文、合法 KV、单序列。
- MNN：只使用完整、校验通过的 bundle 和支持的 processor。
- QAIRT：只有通过真实 create/generate/destroy admission 的包才进入正式调优。
- UNKNOWN 模型：不启用 MTP、GPU、量化 KV 或激进上下文。

安全基线必须先完成一次正式加载和最小正确性请求，之后才能开始性能搜索。

安全基线完成 effective 配置回读、最小正确性 canary 和基本内存门槛后，必须在任何风险候选之前提交为 profileVerificationLevel=SAFE 的 bootstrap active/LKG。这样首次调优失败也有确定恢复目标。安全基线本身都无法通过时禁止进入性能搜索。

所有模式共用版本化 SafetyEnvelope，至少包含：

- 最小可用内存和 PSS/RSS 上限，触发 low-memory 后立即停止当前候选。
- 允许的温控档位、最低电量、是否必须充电以及候选间冷却条件。
- 单候选超时、总调优时间、最大候选数、最大重载数和 watchdog 截止条件。
- 用户取消、App 转后台、来电/系统压力下的暂停或回退规则；Activity 重建只重新订阅持久化 job，不触发回退。

安全上限不因快速/标准/深度模式而放宽，也不允许被普通模型参数绕过。

### 6A.5 分阶段搜索

调参采用约束搜索，不能对所有字段做笛卡尔积。

每个候选必须由 CandidateExecutor 执行真实配置：

1. 获取唯一 lifecycle lease，停止并等待现有生成结束。
2. LOAD_BOUND 字段变化时用候选 resolved profile 冷加载；HOT_EXECUTION 字段显式应用并在候选后恢复。
3. 从 runtime adapter 读取 ActiveLoadedSignature 和 EffectiveExecutionSignature，只有与候选规范化值一致才允许计分。
4. 在全新隔离会话中运行 canary，禁止混入用户聊天、助手人格、网络搜索或上一候选的 KV 状态。
5. 候选失败、取消或超时时，恢复进入候选前的 committed profile。

禁止只修改 GenerationParams 就声称完成 batch / KV / context / GPU / MTP 调优。高风险、深度模式和 UNKNOWN 模型候选优先在受监督的 :tuning 隔离进程执行；主进程通过持久化 journal、子进程退出原因和 watchdog 识别 native crash / OOM，然后隔离失败候选并回退。

#### Stage 1：加载与正确性

- 模型能够加载。
- chat template 产生正确 role 顺序。
- 简单中文、数字和格式 canary 正确。
- 无空输出、乱码和无限重复。

失败立即停止，不进入性能搜索。

正确性不能依赖人工观感或“非空即通过”。每次调优固定 CorrectnessSuiteId、prompt 版本、seed、sampling、解析器和 oracle，至少覆盖：

- 确定性数字/格式输出、中文指令遵循、role / template 顺序和两轮上下文。
- 空输出、乱码、循环重复、截断和特殊 token 外泄检测。
- 长上下文中固定 needle 召回，只在该候选改变 n_ctx / KV 时运行。
- 视觉候选使用至少两张内容不同的真实图片和可程序校验的标记，验证回答跟随图片变化。
- 模型家族专项项，例如 Gemma template / PLE、Qwen MTP 和 tool-call JSON schema。

候选必须通过全部硬性 oracle，并且不得比安全基线减少可用能力。非确定性语义项采用结构化特征/标记和多次一致性，不使用同一待测模型给自己打分。

#### Stage 2：线程

- 在设备安全候选集合中搜索 n_threads 和 n_threads_batch。
- 先短请求，再进行稳定请求。
- 记录 TTFT、decode、CPU 时间、温度和内存。
- 速度差异低于阈值时选择线程更少、温度更低的候选。

#### Stage 3：batch

- 在模型和内存允许范围搜索 n_batch / n_ubatch。
- 任何 OOM、内存阈值触发、明显 TTFT 回退均淘汰。
- 不能只凭一次短输出选择最大 batch。

#### Stage 4：KV 与 Flash

- 验证 cache_type_k / cache_type_v 与 Flash Attention 的依赖。
- 对相同 prompt 检查输出正确性，不能只比较速度。
- 保留至少固定内存余量。

#### Stage 5：上下文

- 先验证目标 n_ctx 能成功创建。
- 再进行接近上下文边界的 prefill。
- 记录峰值 PSS、RSS 和可用内存。
- 长上下文模式单独保存，不能覆盖日常平衡配置。

#### Stage 6：模型专项能力

- Qwen3.6 APEX MTP：验证 MTP head、drafted、accepted、steps 和恢复能力。
- hybrid/recurrent：验证缓存策略和 partial-state checkpoint。
- Gemma：验证模板、PLE、连续文本隔离和日期上下文。
- 多模态：验证真实图片语义变化，不能只看非空输出。
- QAIRT：必须证明真实 NPU graph execution，禁止 fallback 冒充。

### 6A.6 调优模式

提供三种用户模式：

| 模式 | 目标 | 行为 |
|---|---|---|
| 快速 | 用最少候选得到安全配置 | 加载、正确性、线程小集合 |
| 标准 | 日常推荐 | 加载、正确性、线程、batch、KV、短稳定性 |
| 深度 | 高级用户和发布验收 | 加入上下文、温控、重复请求、取消恢复、UI/API |

模式只控制搜索深度，不允许降低正确性和安全门槛。

不固定宣称“快速一定 1 至 2 分钟”。35B 或慢存储设备的一次冷加载就可能超过该时间。UI 按历史冷加载时间、候选数、重复次数和冷却时间计算动态 ETA，无历史数据时显示范围而不显示虚假精确时间。

每个候选记录 MeasurementEnvelope：初始/结束温控、电量与充电状态、可用内存、CPU 拓扑/cpuset、App 前后台状态、冷却时间和系统压力。同组候选使用中位数、方差和失败比较，不按单次最高 token/s 排名。因当前温控临时降低的线程只作为 runtime override，不写回 last-known-good。

### 6A.7 评分函数

候选评分采用硬门槛加加权评分：

硬门槛：

- correctnessPassed=true
- crashCount=0
- anrCount=0
- nativeFatalSignalCount=0
- lowMemoryTriggered=false
- outputVisible=true
- templateValid=true

通过硬门槛后再评分：

    score =
        decodeScore
        + ttftScore
        + memoryHeadroomScore
        + thermalScore
        + repeatabilityScore

任何正确性失败候选得分直接为无效，不能因速度高而获胜。

### 6A.8 在线自适应

运行中只允许自动调整下列字段，且前提是当前 RuntimeParameterAdapter 已将它们登记为 HOT_EXECUTION：

- n_threads / n_threads_batch

temperature、top_k、top_p、min_p、repeat/presence/frequency penalty、seed 和 max_tokens 虽然不需重载，但会改变回答语义或截断输出，不得因设备温度或测速结果被静默修改。它们只能由模型家族安全默认值、用户/助手明确设置或用户明确选择的“助手生成预设”决定。它们与 balanced / speed / quality / longContext 执行 profile 完全分开。

use_jinja、chat_template_mode、templatePolicyRef 和 affectsSemantics/affectsCorrectness 字段禁止在线自适应，即使 runtime 技术上可以热应用。

在线线程降级使用 request-scoped RuntimeOverrideLease：请求成功、失败、取消或超时后都恢复 committed hot values，禁止把临时值写回 active/LKG 或泄漏到下一个 UI/API 请求。

加载期字段只能生成“建议变更”，进入统一重载流程：

- n_ctx
- batch / ubatch
- KV
- Flash
- GPU/MoE
- MTP
- mmap / mlock

温度过高、低电量或系统低内存时：

- 当前请求可通过 RuntimeOverrideLease 降低线程；输出预算只能在用户已明确选择省电型助手生成预设时按预定规则调整。
- 不静默更改模型加载签名。
- 需要修改加载参数时只创建候选建议，按应用策略提示并安全重载。

### 6A.9 配置保存与复用

每个模型至少保存：

- activeCommittedProfile
- pendingProfile
- lastKnownGoodProfile
- balancedProfile
- speedProfile
- qualityProfile
- longContextProfile
- rejectedCandidates / failedCandidates 摘要
- 调优证据时间和 APK/runtime identity

正常生命周期重载使用同身份的 activeCommittedProfile。用户应用或调优候选使用 pendingProfile；pending 完成加载、effective 配置核对、内部正确性和稳定性门槛后，才原子提升为 activeCommittedProfile。

每个新 profile 记录 parentCommittedProfileId，每个应用/验证事务在 journal 中锁定 rollbackTargetProfileId。即使候选经内部验证后已成为 COMPATIBLE LKG，之后正式双入口验证失败也能沿 parent 链回到上一个未被 REJECTED 的稳定 profile，不会“回滚到失败候选自己”。

资格规则明确为：

- 安全基线通过 effective 核对和最小 canary 后，成为 bootstrap LKG，profileVerificationLevel=SAFE。
- 候选通过内部正确性、稳定性和 effective 核对后，可成为 active/LKG，profileVerificationLevel=COMPATIBLE。
- 正式双入口验证状态为 NOT_RUN 时保持 COMPATIBLE；实际执行并 PASS 时升为 DEVICE_VERIFIED；实际执行并 FAIL 时当前候选失效，按该验证事务预先锁定的 rollbackTargetProfileId 回退。
- 代表机 DEVICE_VERIFIED 只能提升规则证据和产品功能准入，不能提升其他设备的 profileVerificationLevel。

每次候选执行前必须持久化 transaction journal、pending 签名、rollbackTargetProfileId 和当前阶段。失败、取消、超时或进程异常退出时恢复事务锁定的回退目标；目标失效时沿 parent 链查找，再失败退到安全基线，之后停止自动重试。

profile、journal 和失败候选使用支持事务和 schema migration 的持久化存储，不使用 SharedPreferences.apply() 承担崩溃恢复。存储损坏时将记录隔离后回到安全基线，不从半写入 JSON 恢复。

如果身份不匹配：

- 不复用旧配置。
- 重新执行安全预检。
- 可复用用户的性能偏好，但不能复用具体加载值。

profile 导出包只是脱敏的诊断/验收证据。导入到另一设备、runtime 或 native build 时不得继承 ACTIVE / last-known-good 状态，只可作为候选提示，并必须重跑本机安全基线与验证。

### 6A.10 UI

Agent 页显示：

- 当前模型与设备能力摘要，不显示 installationScopeId 或硬件唯一标识。
- 分开显示 featureCompatibility、profileVerificationLevel 和 ruleEvidenceLevel，不用一个“已验证”混合三种含义。
- 调优模式。
- “开始调优”、“暂停/续跑”、“取消”、“充电且达到温控条件时续跑”和“通过后自动应用”选项，加载期变更默认不自动提交。
- 正在测试的阶段和候选。
- 动态 ETA、冷却倒计时、暂停原因和重复失败退避时间。
- 正确性、内存、温控和速度结果。
- 推荐配置与当前配置差异。
- “应用并重新加载”。
- “回滚到上一个稳定配置”。
- “删除该模型调优记录”。
- 上次中断/崩溃恢复结果、ProfilePointers、profileRecordState、EngineLifecycle、TuningJobState 和最终提交状态。

不能只显示“智能调试完成并已应用”，必须明确是否发生重载、是否存在临时 RuntimeOverride，以及最终的 ActiveLoadedSignature / CommittedExecutionSignature。

### 6A.11 Local API

- /v1/models 返回当前 profileId、profileRecordState、profileVerificationLevel、engineLifecycle、tuningJobState 和 reloadRequired。
- /metrics 返回 desired/resolved/active/committed/override/effective 快照，但不包含隐私和密钥。
- 调优期间 Local API 返回明确 busy 状态，不能与模型卸载并发。
- 调优完成后必须用正式 Local API 请求验证最终配置。
- 外部客户端不能通过普通请求覆盖加载期调优配置。

调优控制面使用独立的认证任务 API，不复用 chat completion 的非标准字段：

- POST /v1/tuning/jobs：创建任务，参数只允许 modelId、mode、autoApply 和性能偏好。
- GET /v1/tuning/jobs/{id}：查询阶段、进度、候选、硬门槛和差异。
- POST /v1/tuning/jobs/{id}/pause 和 /resume：幂等暂停/续跑，只在 SafetyEnvelope 允许的安全边界生效。
- POST /v1/tuning/jobs/{id}/cancel：幂等取消并回到 active profile。
- POST /v1/tuning/jobs/{id}/apply：仅应用已通过硬门槛的 staged candidate。
- POST /v1/tuning/rollback：幂等回滚到 last-known-good profile。

所有写操作需要有效 Bearer Token 和 Idempotency-Key；同一模型同时只能有一个调优/加载任务。并发任务返回 HTTP 409，并包含 activeJobId、tuningJobState、engineLifecycle、activeProfileId 和 retryAfterMs。调优 API 不得暴露原始 prompt、用户会话、密钥或设备唯一标识。

REST、Binder 和 MainActivity 共用同一 ParameterCoordinator 和 lifecycle state：

- 普通外部推理请求只允许 generation-only 白名单；加载期、执行期或未知 native 字段返回 HTTP 409，不与全局 advanced 默认值合并。
- 请求 model 必须匹配当前 active model identity，否则返回 model_not_loaded 或 model_mismatch，不能忽略后使用其他已加载模型。
- busy、reload_required、无效 profile 和身份不匹配必须在写出 SSE/HTTP 200 头之前完成 preflight。调优中返回 tuning_in_progress 和 Retry-After。
- /v1/models 返回每个模型自己的 profileRecordState / profileVerificationLevel，不得把当前全局 n_ctx 或 profileId 复制给所有模型。
- /metrics 返回 coordinator 的完整签名快照集合，不只回传 runner 自由格式 stats，且不包含绝对路径、prompt 或密钥。

### 6A.12 未知模型

知识库不可能提前列举所有用户导入模型，因此“完整适配”定义为：

- 已知模型有专项规则。
- 未知模型能读取 metadata 并安全运行。
- 不支持的能力不会被猜测开启。
- 可以通过受约束实测生成该模型专属配置。
- 生成的配置绑定模型和设备 fingerprint。

未知模型不应因为没有名称匹配而继承上一个模型的高级参数。

未知模型的确定性上限：单序列、CPU 安全 backend、保守上下文、不开启量化 KV / MTP / GPU / NPU 和未声明的 tool calling。UNKNOWN 不得仅凭名称猜测视觉能力；但完整 MNN bundle 或 projector/metadata 明确声明视觉组件、processor 可读、runtime 能力匹配且 native 报告 visionReady=true 时，可在隔离候选中开启并运行视觉反事实 canary。通过后按代表机默认开放规则使用，不要求逐机 visionValidated 认证。

如果 metadata 损坏、tokenizer 最小契约不满足或缺失可用 chat template，状态转为 BLOCKED_WITH_ACTION，要求用户补全文件或明确选择模板，不猜测后宣称可用。

UNKNOWN 只能在同一 ModelRuntimeIdentity 下通过能力发现、正确性套件和安全边界后得到 profileVerificationLevel=COMPATIBLE；只有进一步完成正式 UI + Local API 回归才能得到 profileVerificationLevel=DEVICE_VERIFIED。仅测速成功不得升级。

### 6A.13 runtime 与模型专属适配器

调参核心不得把一份 JSON 同时发给所有运行时。建立 RuntimeParameterAdapter 接口，每个 adapter 公开：

- 可识别的 metadata、可加载字段、可热更新字段和禁止字段。
- 字段取值范围、依赖图、默认值、内存估算器和加载签名序列化。
- 正确性 canary、性能候选生成器、运行时错误分类和回退基线。

字段可变性必须由 ParameterFieldPolicyRegistry.forRuntime(runtime, runtimeVersion, nativeLibrarySha256) 给出，不能使用全局静态列表。例如 llama.cpp 中 n_ctx / batch / KV 为加载期字段，而某些 MNN 版本可在 beginCompletion 重设同名配置；每个 adapter 必须按真实 native 能力将字段分为 LOAD_BOUND、HOT_EXECUTION、GENERATION_ONLY 或 UNSUPPORTED。这个注册表的版本参与调优身份和加载签名。

不同运行时分别处理：

- llama.cpp / GGUF：n_ctx、batch / ubatch、KV、Flash、mmap / mlock、CPU 线程、GPU/MoE、MTP、Jinja 和 projector。
- MNN：thread_num、backend、precision / memory / power mode、processor 和视觉组件。禁止注入 GGUF 专属的 KV、Flash、MTP 或 main_gpu 字段。
- QAIRT：只在真实注册的 HTP backend、匹配的 SoC/runtime/context binary 和固定 shape 约束内调优；不允许 CPU fallback 冒充 NPU 结果。
- 云端模型：只解析生成参数，不创建本地加载 profile。

在 runtime adapter 之上增加 ModelFamilyAdapter，负责 chat template、role 顺序、stop tokens、thinking 模式、tool calling、sampling 安全范围和模型专项 canary。已知家族使用版本化规则；未知家族从 metadata 和 tokenizer template 推导，推导失败时保守运行而不猜测开启高级能力。

MNN 的“校验通过”只指 bundle 结构完整、processor / 视觉组件可读且 native ready，不要求每台设备存在单独的 visionValidated 认证记录。代表机的正式 UI + Local API 通过后，所有满足兼容约束的 ARM64 机型默认开放；设备专项失败再单独回退或优化。

### 6A.14 自动触发与参数变化规则

“重新加载”和“重新调参”是两件事。重载每次都自动重新解析该模型的 effective profile，但只有身份失效、首次运行或受影响的参数域变化时才重跑相应调优，不盲目跑全量 benchmark。

| 事件 | 自动处理 | 是否调优 | 是否重载 |
|---|---|---|---|
| 首次导入/加载模型 | 能力发现 -> 安全基线 -> 模型规则 -> 快速正确性校准 | 自动快速调优一次 | 基线与最终候选各最多一次 |
| 同一身份再次加载 | 复用 activeCommittedProfile，运行轻量健康检查；active 损坏/缺失时才恢复 lastKnownGoodProfile | 否 | 是 |
| 模型/native/runtime/设备指纹变化 | 旧 profile 失效，回到安全基线 | 自动重跑快速调优 | 是 |
| 助手或会话仅改生成参数 | 按当前模型规则归一化 sampling / stop / reasoning | 只做参数校验 | 否 |
| 助手或会话绑定了另一模型 | 切换到新模型自身的 profile | 仅在无有效 profile 时自动快速调优 | 是 |
| Agent 改变加载期字段 | 计算受影响的依赖闭包，创建 staged candidate | 只重跑受影响阶段 | 应用候选时是 |
| 温控、电量或内存状态变化 | 只自动调整允许热更新的线程；输出预算只提示或执行用户预先授权的省电策略 | 否 | 否 |
| 用户锁定安全的手动值 | 保留用户意图，依赖校验不通过时拒绝并解释 | 不自动覆盖手动值 | 视字段类型 |

新生成参数也要按当前模型自动适配，但不得静默覆盖用户明确设置。解析结果同时保存 requested / resolved / effective 值和规则来源；不安全值被拒绝，建议值可一键应用。

自动应用边界固定为：

| 变更类型 | 默认决策 |
|---|---|
| 不支持或越界值的安全裁剪 | 自动归一化，保存差异和原因 |
| 同身份的 activeCommittedProfile | 正常加载时自动应用；仅恢复流程使用 lastKnownGoodProfile |
| 调优中的候选 | 只在隔离的调优任务中临时应用，不提交 |
| 首次快速调优选出的热线程值 | 通过正确性后可自动应用，并保留回滚 |
| 改变加载签名的性能候选 | 默认显示预览并由用户应用；只有用户在启动调优时明确勾选“通过后自动应用”才自动提交 |
| sampling / max_tokens 等语义参数 | 保留用户/助手请求；仅对非法值做安全裁剪 |
| 普通 Local API 请求中的加载期字段 | 不静默调优或重载，返回 HTTP 409 和字段差异 |

### 6A.15 正交状态、profile 指针和事务提交

不把 profile 状态、调优任务和 engine 生命周期混成一个枚举。ParameterCoordinator 原子发布三类状态：

    ProfilePointers(
        activeProfileId,
        pendingProfileId,
        lastKnownGoodProfileId,
        rollbackTargetProfileId
    )

    EngineLifecycle =
        UNLOADED / LOADING / READY / GENERATING / STOPPING /
        RELOADING / ROLLING_BACK / ERROR

    TuningJobState =
        QUEUED / RUNNING / PAUSED / CANCELING / VALIDATING /
        SUCCEEDED / FAILED / RECOVERING

profile 记录自身使用 STAGED / COMMITTED / REJECTED，验证等级另存 profileVerificationLevel = SAFE / COMPATIBLE / DEVICE_VERIFIED。Activity 重建只重新订阅持久化 job，不将候选判为失败；仅进程死亡、journal 不一致、watchdog 超时或可确认的 native/OOM 退出进入 RECOVERING。

候选 profile 先写入 STAGED 记录并设为 pending，再加载和验证；只有成功后才在一个数据库事务中将它转为 COMMITTED、更新 active 指针并清空 pending。lastKnownGoodProfile 在候选完成对应资格前不得被覆盖。

候选加载失败、正确性失败、已实际执行的正式 UI/API 验证失败、调优取消、超时或进程中断时：

1. 将候选标记 REJECTED，保存脱敏错误、签名和失败阶段，不再自动应用同一候选。
2. 优先加载当前事务预先锁定的 rollbackTargetProfileId 一次；它已失效时沿 parentCommittedProfileId 链查找上一个未被 REJECTED 的稳定 profile；都没有时加载安全基线一次。
3. 回退后重新核对 ActiveLoadedSignature 和 CommittedExecutionSignature，恢复 UI 和 Local API 生成。
4. 回退加载仍失败时保持模型未加载，显示原因、重试加载、回滚稳定配置和复制诊断入口。
5. “候选应用一次 + 稳定配置回退一次”是硬上限，禁止循环重载。-11 对同一请求的重试仍最多一次。

busy 只拦截新推理和会改变模型生命周期的操作。任务查询、取消、恢复状态和 health 必须始终可用。Local API 在 job=RUNNING/CANCELING/VALIDATING/RECOVERING 或 engine=LOADING/STOPPING/RELOADING/ROLLING_BACK 时，对新推理返回结构化 busy 响应，包含 code、tuning_job_state、engine_lifecycle、active_profile_id 和 retry_after_ms。无效 Bearer Token 返回 HTTP 401，不得进入推理或调优。

### 6A.16 正式 UI + Local API 验证与提交

调优候选要成为 profileVerificationLevel=DEVICE_VERIFIED，必须在同一 APK、同一设备、同一模型 fingerprint 下同时满足：

1. 在同一 acceptanceCampaignId 下，从 MainActivity 正式导航进入聊天，以独立 uiRequestId 使用候选 profile 产生可见且正确的回答。
2. 独立外部客户端携带有效 Bearer Token，以不同的 apiRequestId 调用 App 当前启用端口的 /v1/chat/completions，返回 HTTP 200 和可见有效正文。
3. uiRequestId 与 apiRequestId 必须不同，native generation sequence 分别递增，禁止复用同一次输出。两个请求报告相同的 modelId、profileId、ActiveLoadedSignature 和 CommittedExecutionSignature，且验收期间 RuntimeOverrideSignature=NONE。
4. 文本模型通过模型家族 canary；多模态模型还要求真实图片内容改变时回答语义跟随改变。
5. 限定窗口内无 App FATAL、ANR、native fatal signal、进程死亡或重载循环。

/v1/models、设置页自检、debug Activity、native 直调和内部 smoke 都不能替代这两次真实生成。快速/标准调优可先生成 profileRecordState=COMMITTED、profileVerificationLevel=COMPATIBLE 的设备专属配置，但只有完成上述双入口验证才能提升为 profileVerificationLevel=DEVICE_VERIFIED。只有代表机的 DEVICE_VERIFIED 证据才可将规则提升为 ruleEvidenceLevel=REPRESENTATIVE_VERIFIED 并开放功能准入；不得因此改写其他设备的 profile 验证等级。

为避免要求普通用户开启对外 REST，App 可在调优期间使用只绑定 127.0.0.1 的生产 Local API 路由做兼容性健康检查，但这个自动检查只能赋予 profileVerificationLevel=COMPATIBLE，不能单独提升为 DEVICE_VERIFIED。正式设备/发布验收必须请求 App 当前实际启用的 Local API 服务，使用真实认证 key 和真实 engine/provider，禁止临时空 key、影子 server、替换 provider、注入假 token 或以内部函数直调代替 HTTP 请求。

多模态验收使用两张不同的可程序校验 canary 图片：MainActivity 通过正式图片选择器发送真实 JPG/PNG；独立 HTTP 客户端通过真实 Bearer key 发送 base64 image_url。两个入口都要产生与各自图片语义一致的回答，并记录 uiImageSha256 / apiImageSha256、媒体预处理计数和独立 native generation sequence。

## 7. 加载签名与自动重载

### 7.1 运行时身份与签名集合

统一 ModelRuntimeIdentity 同时作为调优 key 和加载签名的身份基础，覆盖 6A.2 的完整 artifact/分片/组件、projector、tokenizer/template、runtime adapter/version、native build、ABI、backend 能力、engine contract、字段策略、规则、evaluator 和 schema 版本。

ParameterCoordinator 同时维护：

- DesiredProfileSignature：用户或调优任务希望应用的类型化配置。
- ResolvedLoadSignature：经过能力裁剪和依赖归一化后的加载期配置。
- ActiveLoadedSignature：native 加载成功并回读 effective 值后发布的真实加载配置。
- CommittedExecutionSignature：active profile 承诺的线程等热执行字段。
- RuntimeOverrideSignature：当前单请求 RuntimeOverrideLease 的临时字段；无 override 时为 NONE。
- EffectiveExecutionSignature：ActiveLoadedSignature + CommittedExecutionSignature + 该请求 RuntimeOverrideSignature 的实际组合。

所有签名只基于排序后的类型化和规范化字段，不比较原始 advancedJson、JSON 键顺序、临时绝对路径或用户输入的别名。加载成功后必须由 runtime adapter 回读 effective 值并核对，之后才能原子发布 active 状态。

正式 UI/API 验收窗口冻结在线自适应，要求 RuntimeOverrideSignature=NONE，再比较两个独立请求的 ActiveLoadedSignature 和 CommittedExecutionSignature。非验收日常请求可分别记录受控 RuntimeOverrideSignature，不将临时差异误判为 profile 不一致。

每次 UI、REST 或 Binder 请求进入 engine 前重新计算请求签名。UI 和 Local API 只能读取 ParameterCoordinator 的同一个不可变快照，不能分别依赖 ViewModel 全局参数和 native 自由格式字符串。

### 7.2 字段变化处理

- 只有当前 runtime 注册为 HOT_EXECUTION 或 GENERATION_ONLY 的参数变化：校验后直接生成。
- 只有已由受信任 UI/调优任务授权、携带有效 transactionId 并已持久化为 pending profile 的 LOAD_BOUND 变化，才能在 engine 空闲时执行一次受控重载。
- 助手、会话、普通 API 或无匹配 transactionId 的请求包含 LOAD_BOUND 变化：拒绝并返回差异，不创建 pending、不重载。
- 正在生成：先提示停止，不能并发卸载。
- 模型或 projector 变化：必须通过已授权模型切换/pending 事务重载。
- 配置非法：在 Java/Kotlin 层拒绝，不能进入 native。

### 7.3 -11 恢复

请求进入 native 前，ParameterCoordinator 先拆出 generation-only 请求和 model-execution 配置。助手、会话和普通 API 请求不得携带加载期字段。

llama.cpp 的 native -11 保留为最后一道保护，映射为统一 LOAD_SIGNATURE_MISMATCH。MNN 和 QAIRT 即使没有 -11 返回码，也必须由各自 adapter 在 begin 前做相同签名检查，不允许静默将加载期候选应用到旧 session。

受信任 UI 应用新模型参数时，必须在请求前完成参数预览/autoApply 授权、创建 pending resolved profile、持久化 transactionId 和回退目标。native 返回 LOAD_SIGNATURE_MISMATCH 后分两种情况：

1. 存在同一 model identity、匹配 transactionId 和匹配 ResolvedLoadSignature 的已授权 pending：获取 lifecycle lease，使用 pending 受控重载一次。
2. 不存在匹配 pending：视为内部状态漂移或残留字段，不得从原请求创建新 profile；只允许重新加载 activeCommittedProfile 一次。
3. 回读 ActiveLoadedSignature；与目标 resolved 签名不一致时不得重试生成。
4. 重试请求只携带清洗后的 generation-only 参数，不能把原请求中的加载字段原样再发给 native。
5. 同一请求自动恢复最多一次；pending 路径失败后按 6A.15 恢复事务 rollbackTargetProfileId/parent 稳定 profile，状态漂移路径失败后保持未加载并返回结构化差异，禁止循环。

UI 文案：

    模型参数已变化，正在重新加载：
    n_ctx 4096 -> 8192
    cache_type_k f16 -> q4_0

Local API 行为：

- 标准 temperature、max_tokens 等字段继续热更新。
- 非标准加载期扩展字段默认不能让第三方请求静默重载大模型。
- 如确需修改，返回 HTTP 409：

      code: model_reload_required
      changed_fields: [...]

- App 自己的受信任设置流程可调用统一 reload 接口。

### 7.4 参数变化入口

以下入口都必须使用同一 ParameterCoordinator：

- updateParams
- selectAssistant
- saveAssistantProfile
- importAssistantCard
- selectChatSession
- applyAgentRecommendation
- rollbackAgentParams
- runAgentDebug
- loadModel
- LocalApiRuntime.generationParamsProvider

这些入口不能再直接替换全局 GenerationParams。

## 8. MNN 导入方案

### 8.1 支持的导入形式

正式支持：

1. 一次多选完整组件。
2. 导入完整 ZIP。
3. 后续增加 SAF 目录导入。
4. 推荐页自动下载完整 bundle。

单独选择 llm.mnn 时不再显示笼统失败，而应显示缺失清单。

### 8.2 基础聊天包

基础必需组件：

    config.json
    llm_config.json
    llm.mnn
    llm.mnn.weight
    tokenizer.txt 或 tokenizer.mtok
    embeddings_bf16.bin 或 llm.mnn.json

Gemma 4 文本包额外必需：

    ple_embeddings_int4.bin

多模态包根据 config / llm_config 声明增加：

    visual.mnn
    visual.mnn.weight
    visual.mnn.json（若被引用）

其他配置声明的 processor、embedding 或 sidecar 同样必须存在、可读且非空。

### 8.3 ZIP 检查器

新增 MnnBundleImportInspector：

- 只读取 ZIP 索引和小型 JSON，先不解压权重。
- 防止 zip slip、绝对路径、..、NUL 和 Windows drive path。
- 拒绝重复目标路径和同 basename 冲突。
- 限制 entry 数量、总解压大小和异常压缩比，防止 ZIP bomb。
- 解析 config.json 和 llm_config.json 得出真实组件依赖。
- 显示必需、可选、缺失和未知文件。

当前导入器会按 basename 扁平化。应改成：

- 保存安全的相对路径。
- 配置引用什么路径，安装目录就保留什么路径。
- 兼容旧的根目录平铺包。

### 8.4 原子安装

复用 ModelBundleInstaller 的 staging / audit / commit 模式：

1. 解压到 .installing/content。
2. 完成组件检查和本地 SHA-256。
3. 生成 bundle audit manifest。
4. 原子替换最终目录。
5. 失败时恢复旧 bundle，不生成半成品模型卡。

### 8.5 导入 UI

导入页应明确展示：

- “GGUF 单文件”
- “MNN 完整组件/ZIP”
- “MNN 单个 llm.mnn 不能运行”
- 当前识别模型类型
- 缺失文件
- 预计复制空间
- 是否包含视觉组件
- 是否将作为文本包隔离安装

## 9. Gemma 4 正确性方案

### 9.1 设备日期工具

新增 DeviceClockContextProvider，提供：

- localDate
- localTime
- timeZoneId
- UTC offset
- locale

为了避免每分钟变化破坏 prefix cache：

- 系统提示只注入当天日期和时区，按天稳定。
- 用户明确询问当前时间时调用本地 device_time 工具返回精确时间。
- 不把日期永久写入助手卡或会话历史。

示例动态上下文：

    运行时信息：当前本地日期为 2026-07-15，时区为 Asia/Shanghai。
    日期和时间以此运行时信息为准，不得根据训练数据猜测。

### 9.2 日期问题处理

- “今天几号”“现在时间”等问题优先使用设备时钟。
- “今天发生了什么”“今天新闻”等外部事实才触发联网。
- Local API 与 UI 必须注入相同运行时上下文。

### 9.3 Gemma 模型质量

模型卡和加载页应显示：

- 模型是否为 instruction-tuned。
- 量化等级。
- 极低比特量化可能降低中文、OCR、事实和指令遵循质量。
- 当前 chat template 来源和 Jinja 状态。

推荐顺序：

1. 已验证的 Gemma 4 MNN E4B 文本包。
2. 合理精度的 Q4 GGUF。
3. IQ2/极低比特仅作为低内存实验，不标为高质量默认。

对 Gemma 4 增加正式回归：

- 你好 / 身份
- 当前日期
- 两轮连续中文
- system prompt 遵循
- 助手切换
- 会话切换
- 参数变化后重载
- UI 与 Local API 输出可见

## 10. UI 与错误信息

### 10.1 用户区

错误卡只显示：

- 发生了什么
- 为什么
- 用户下一步操作
- “复制诊断”按钮

示例：

    当前 APK 只有 CPU 后端，但模型参数请求了 n_cpu_moe=5。
    已为该模型生成 CPU 安全参数。点击“应用并重新加载”。

### 10.2 诊断区

完整 native stats 放入折叠区域，包含：

- errorCode
- errorCategory
- nativeLastError
- requestedConfig
- resolvedConfig
- changedFields
- model fingerprint
- APK/native library version

不要再把整段 JSON 直接塞进主错误正文。

## 11. 数据迁移

### 11.1 旧数据

当前数据来源包括：

- mca_generation_params 全局参数
- AssistantRecord.paramsJson
- 会话绑定
- native activeLoadSession

### 11.2 迁移规则

首次升级：

1. 从全局参数提取生成参数，作为默认 AssistantGenerationProfile。
2. 从 advancedJson 提取加载期字段。
3. 仅在能确认 lastLoadedModelId 且通过能力校验时，将其迁移为该模型的 ModelExecutionProfile。
4. 不能确认归属或不兼容的字段放入 rejectedLegacyOverrides，不自动应用。
5. 助手 paramsJson 删除加载期字段。
6. 会话保留 modelId，但不复制模型加载参数。

迁移必须幂等，并保留旧 JSON 备份一个版本周期。

profile 与调优事务使用 Room 或同等支持原子事务的存储，至少建立：

- model_runtime_identity：完整 ModelRuntimeIdentity 和唯一 identityHash。
- execution_profile：profileId、revision、parentCommittedProfileId、status、desired/resolved/active/committed/override/effective 签名、规则和评测版本。
- tuning_job / tuning_journal：任务状态、pending/rollback 目标、当前阶段、取消标记和最后心跳。
- candidate_measurement：脱敏 MeasurementEnvelope、硬门槛、评分、失败分类和签名。

唯一键和事务约束保证同一 identity 最多一个 active、一个 pending 和一个正在运行的 job。启动时先扫描未完成 journal，执行恢复后再允许生成。failed candidate 和证据设置数量/保留期上限并脱敏；不保存用户 prompt、会话、绝对路径或密钥。

## 12. 代码改动地图

| 模块 | 主要改动 |
|---|---|
| core:engine | ModelExecutionProfile、签名快照集合、ParameterCoordinator、ParameterFieldPolicyRegistry、运行时 adapter、生命周期 lease、-11 单次恢复、DeviceClockContextProvider |
| core:tuning | ModelExecutionProfile / GenerationRecommendation / CanaryEvaluationParams 拆分、版本化能力/模型规则、SafetyEnvelope、CorrectnessSuite、profile 事务和覆盖等级 |
| core:benchmark | CandidateExecutor、真实冷加载/热配置执行、effective 签名核对、MeasurementEnvelope 和中位数/方差评分 |
| core:advisor | 只生成可解释建议；不再把混合 TuningPlan 直接写回全局 GenerationParams |
| core:modelstore | GGUF 复制后复检、MNN ZIP inspector、安全路径和完整性检查 |
| core:download | bundle audit 复用、固定文件大小/SHA、失败恢复 |
| core:native | 保留 llama -11；补充结构化错误、加载/热执行能力和可回读 effective 配置 |
| app | 参数状态拆分、profile/journal 数据库、恢复协调、重载状态、导入预览和简洁错误卡 |
| feature:agent | 调优启动/暂停/取消/应用/回滚 UI；执行参数与生成建议分屏显示 |
| feature:chat | 助手编辑器和角色卡导入只允许生成参数；加载/执行字段进入隔离区 |
| feature:modelhub | 模型专项参数预览、MNN 缺失组件列表 |
| api:local REST/Binder | 共享 coordinator/lifecycle；写头前 preflight；reload/busy 结构化错误；调优任务控制面；动态日期一致性 |

## 13. 实施阶段

### Phase 0：阻断级修复（代码完成）

- 修正 35B CPU 参数过滤。
- 修正 loadFailureAdvice。
- 落地最小 ParameterCoordinator + ParameterFieldPolicyRegistry + llama.cpp/MNN RuntimeParameterAdapter，先正确区分加载、热执行、生成和不支持字段。
- 增加 ModelRuntimeIdentity 和 desired/resolved/active/committed/override/effective 签名快照。
- UI 只对已授权 pending 执行一次重载；-11/等价 mismatch 自动恢复最多一次。
- 增加设备日期上下文。

完成条件：35B、Gemma 在正常 UI 流程不再出现误导错误或裸 -11。

### Phase 1：参数分层（代码完成）

- 建立 per-model / per-device / per-runtime ModelExecutionProfile。
- 助手和会话迁移到纯生成参数。
- 完成 profile/journal 持久化、pending/active/LKG 原子事务和崩溃恢复。
- 将完整 ParameterCoordinator 接入 MainActivity、REST、Binder、助手、会话和 Agent，并提供参数来源解释。
- Local API 与 UI 使用同一快照集合。

完成条件：切换模型、助手和会话不会相互污染加载参数。

### Phase 2：MNN 导入（代码完成）

- ZIP 预检。
- 路径保留。
- 原子安装。
- 目录导入。
- 组件缺失 UI。

完成条件：完整 Qwen/Gemma MNN 包可导入；单文件、缺 PLE、路径冲突均给出准确错误。

### Phase 3：智能调参知识库与质量闭环（代码完成；正式实机证据按能力分别累积）

- 规则版本化。
- ruleEvidenceLevel / profileVerificationLevel / featureCompatibility 三维独立分级。
- 完成能力发现、安全基线、分阶段搜索和评分函数。
- 完成 CandidateExecutor、高级 runtime/model-family adapter 规则和 effective 签名核对。
- 保存 per-model / per-device 调优身份、active/pending/last-known-good 配置和崩溃恢复 journal。
- 增加快速、标准、深度调优模式。
- 增加版本化正确性套件、SafetyEnvelope、内存/温控、取消/崩溃恢复和 Local API 任务闭环。
- Gemma 和 Qwen3.6 专项 UI/API 回归。
- 量化质量提示。

## 14. 测试策略

### 14.1 开发回归

单元和集成测试用于防止代码回归，但不能替代实机：

- CPU-only 参数裁剪。
- 加载签名比较。
- desired / resolved / active / committed / override / effective 签名规范化、回读和不一致拒绝。
- 热更新字段不重载。
- 加载期字段触发重载。
- 同名字段在 llama.cpp / MNN / QAIRT 不同 native 版本下的字段策略。
- -11 只重试一次。
- 助手/会话迁移不携带加载参数。
- 助手卡和 Local API 中的未知/advanced 字段被隔离，不透传 native。
- MNN 缺失组件。
- ZIP 路径穿越、重复路径和 ZIP bomb。
- 日期及时区格式。
- GGUF 导入后大小/header 复检。
- 调优身份变化时旧 profile 失效。
- 未知模型不会继承其他模型的加载参数。
- 正确性失败候选不能因 token/s 高而入选。
- 调优失败恢复 journal 锁定的 rollback target/parent 稳定配置，无可用目标时恢复安全基线。
- 调优与普通生成不能并发卸载模型。
- CandidateExecutor 确实用候选冷加载/热配置，候选后恢复原 committed profile。
- pending journal 在进程中断、存储半写入和回退失败时有界恢复。
- REST/Binder/UI 共用 coordinator，SSE 写头前完成 busy / model / profile preflight。

### 14.2 正式实机验收

最终验收只认可正式产品入口：

1. MainActivity UI。
2. 认证 Local API。

debug Activity、native 直调和内部 smoke 仅用于定位。

一台代表性兼容 ARM64 设备在同一 APK 上同时通过 UI + Local API 后，即可默认开放兼容机型；第二台设备为补充回归，不作为准入门槛。后续个别失败机型记录为明确例外。

“默认开放兼容机型”指产品功能准入，不代表跨设备复制具体调参数值。兼容指 bundle 完整、所需 ABI/runtime/backend 存在、native ready 且满足最低内存约束，不是“这台设备已单独认证”。

featureCompatibility 按功能/runtime contract 版本定义（例如 mnn_vision_vN），不按芯片型号、设备序列号或单个模型名称建 allowlist。一台代表机用完整已知良好 bundle 通过双入口后，同一 contract 对所有满足结构和 native-ready 约束的 bundle/兼容 ARM64 设备开放。包不完整或 native 无法读取仍可按包本身拒绝，但不得转化为“该机型未认证”。

产品准入与调优状态分开：

- featureCompatibility = OPEN / EXCEPTION：决定功能是否展示和允许使用。
- profileVerificationLevel = SAFE / COMPATIBLE / DEVICE_VERIFIED：描述本设备的执行 profile。
- ruleEvidenceLevel = REPRESENTATIVE_VERIFIED / ARCHITECTURE_COMPATIBLE / UNCLASSIFIED / RULE_BLOCKED：描述可发布规则的证据。

代表机通过后，新设备首次运行仍从本机能力发现和安全基线生成 device-bound profile，不得直接继承代表设备的线程、batch、KV 或上下文数值。不得用芯片 allowlist 关闭 MNN 视觉；后续失败设备只登记精确例外或安全回退，不反向关闭其他兼容机型。

featureCompatibility=OPEN 时，新设备在安全基线 native ready 后即可通过正式 UI/Local API 使用功能。快速调优可延后、暂停、取消或失败，只影响本机 profileVerificationLevel 和性能参数，绝不重新构成 MNN 视觉逐机准入门槛。只有可复现的真实 runtime/native/内存不兼容才能登记 featureCompatibility=EXCEPTION。

正式验收必须操作 MainActivity 正式产品入口，并请求 App 当前实际启用、携带真实认证 key 的 Local API。禁止临时启动影子 server、替换 stream provider、直接调用 ViewModel/engine 或注入假 token 冒充 API 通过。UI 和 API 属于同一 acceptanceCampaignId，但必须有不同 uiRequestId / apiRequestId 和独立递增的 native generation sequence；两者报告相同 modelIdentity、profileId、ActiveLoadedSignature 和 CommittedExecutionSignature，且 RuntimeOverrideSignature=NONE。

每次正式双入口验收记录：安装中 base.apk SHA-256/签名、App PID/UID、API server instance/当前端口、设备型号/SoC/ABI、模型/native/bundle/projector fingerprint、profileId/revision、完整签名快照集合、两个独立 requestId/native sequence、UI 可见回答、API HTTP 状态与正文哈希（key 脱敏）、调优/重载/回退时间线、峰值内存、TTFT/decode 以及限定窗口崩溃/ANR/native signal 检查结果。

### 14.3 必测矩阵

#### Qwen3.6 35B

- 推荐下载文件大小和 SHA 正确。
- CPU 安全参数正式 UI 加载成功。
- UI 连续两轮生成。
- Local API 连续两轮生成。
- MTP requested/effective 和 drafted/accepted telemetry 合理。
- 设置 main_gpu=1 或 n_cpu_moe=5 时，加载前给出准确提示，不误报文件损坏。
- 快速、标准、深度调优均从 CPU 安全基线开始。
- 最终 profile 在正式 UI 与 Local API 使用相同加载签名。

#### Gemma 4

- UI 当前日期与设备日期一致。
- Local API 当前日期与设备日期一致。
- 你好、中文问答、多轮对话正常。
- 同模型切换助手不得改变加载签名或触发重载；只有助手明确绑定另一模型时，才按模型切换事务加载该模型自己的 profile。
- 切换会话后不出现 -11。
- 修改 n_ctx/KV/Flash 后明确重载并继续生成。

#### MNN 导入

- 多选基础 Qwen 组件成功。
- 根目录 ZIP 成功。
- 保留相对路径 ZIP 成功。
- 缺 llm.mnn.weight 失败并列出文件。
- Gemma 缺 ple_embeddings_int4.bin 失败。
- 单独 llm.mnn 明确提示不完整。
- 多模态包加载后，MainActivity 通过正式图片选择器发送真实 JPG/PNG，独立外部 HTTP 客户端通过 base64 image_url 发送另一真实图片；两次请求使用不同 requestId/native sequence，并分别得到与各自图片语义一致的回答。

#### 智能调参与恢复

- 从 MainActivity 正式入口分别启动快速、标准和深度调优，验证动态 ETA、阶段、候选和安全门槛可见。
- 验证取消、暂停/续跑和 App 转后台；Activity 重建只重新订阅同一持久化 job，不将候选误判失败或留下半提交 profile。
- 分别验证“预览后手动应用”和用户明确授权的“通过后自动应用”。
- 加载期候选只冷加载一次，热线程候选不重载，sampling / max_tokens 不被设备自适应静默改写。
- 调优期间认证 Local API 在写 SSE 200 头前返回明确 busy/Retry-After；普通请求夹带加载字段时返回 409。
- 无授权 pending/transactionId 时故意触发 LOAD_SIGNATURE_MISMATCH，确认只重载 activeCommittedProfile，不从请求创建 pending；有匹配 transactionId 时才可应用已授权 pending。
- 故意构造加载失败、正确性失败和 effective 签名不一致候选，确认失败候选未成为 active，且自动恢复事务 rollback target/parent 稳定 profile。
- 在 TuningJobState=RUNNING/VALIDATING 和 EngineLifecycle=RELOADING 阶段分别强制结束 App / :tuning 进程，重启后通过 RECOVERING + journal 恢复，同一失败候选不再循环应用。
- 验证模型改名但 SHA 相同、同名但 SHA 不同、projector 替换、native/runtime 升级和规则/评测版本变化时 profile 复用或失效正确。
- 用同名字段验证 llama.cpp 与 MNN 的 mutability 分类不互相污染，UNSUPPORTED/unknown advanced 字段不进入 native。
- 改变 use_jinja / chat_template_mode / templatePolicyRef，确认即使可热应用也必须重跑完整 template/correctness gate，不被纯性能搜索修改。
- 触发温控线程降级，确认 RuntimeOverrideLease 在成功/失败/取消/超时后恢复 committed hot values，不泄漏到下一请求或 LKG。
- 导入恶意/旧助手卡，其 n_ctx / advanced 字段被隔离，不覆盖模型 profile。
- 并发 UI 和 Local API 请求，确认只有一个 lifecycle 任务并且请求不与卸载并发。
- 回滚后 MainActivity 和认证 Local API 均能继续产生可见有效回答，且两者的 profileId、ActiveLoadedSignature 和 CommittedExecutionSignature 一致，RuntimeOverrideSignature=NONE。
- 复制数据库/系统备份到另一设备，确认不可导出的 installationScopeId 使旧 ACTIVE/LKG 失效，但不收集硬件唯一标识。
- featureCompatibility=OPEN 的 MNN 视觉在快速调优延后、取消或失败时仍可用安全基线通过 UI/API 发送图片，不回到逐机认证阻断。
- profile 导出只能作为证据；跨设备导入时必须重跑本机安全基线和验证，不得直接标记为 last-known-good。

## 15. 验收标准

以下是完整方案的代码与分能力验收标准。运行代码已经实现这些约束；正式实机结论必须按具体模型/runtime 的 campaign 记录，不得用本次 MNN 代表机结果替代 35B 或 Gemma 专项矩阵：

- 35B CPU-only 设备不再收到 GPU/MoE 冲突配置。
- 文件完整性错误与参数错误不会混淆。
- 正常 UI 操作不再向用户暴露 beginCompletion -11。
- 自动重载最多一次且无死循环。
- 助手和会话不会改变模型加载参数，除非用户明确应用。
- 助手/会话生成参数、ModelExecutionProfile 和内部 canary 参数完全拆分，智能调参不静默改写 sampling 或 max_tokens。
- 智能调参结果绑定完整 artifact/bundle/projector/template、模型、稳定设备能力、installationScopeId、runtime/native、字段策略、规则和评测 fingerprint；动态温控/内存不污染 identity。
- 每个候选按真实冷加载或热配置执行，回读 effective 签名一致后才计分。
- 调参以正确性和稳定性为硬门槛，不以 token/s 作为唯一目标。
- 未知模型可以生成保守的模型专属 profile；metadata/template 不足时 BLOCKED_WITH_ACTION，且绝不继承其他模型参数。
- pending/active/last-known-good 原子提交和持久化 journal 能够在失败、取消、native crash、OOM 或进程中断后有界恢复。
- 安全基线在风险搜索前建立 bootstrap LKG；无授权 transactionId 的 mismatch 不得创建或应用 pending。
- 完整 MNN ZIP 可导入，缺失组件能准确定位。
- Gemma 日期回答与设备日期、时区一致。
- UI、REST 和 Binder 使用相同 coordinator/lifecycle、模型身份、profileId、ActiveLoadedSignature、CommittedExecutionSignature 和动态日期；临时 RuntimeOverride 请求结束即恢复。
- 正式实机 UI + Local API 以两个不同 requestId/native generation sequence 均产生可见有效回答，不复用同一次结果。
- 测试窗口无 App FATAL、ANR、native fatal signal 或进程死亡。
- 一台代表 ARM64 设备双入口通过后提升 ruleEvidenceLevel 并对所有兼容机型设置 featureCompatibility=OPEN，但不提升其他设备的 profileVerificationLevel，也不跨设备复制具体调参值。
- featureCompatibility=OPEN 时调优未完成/取消/失败不得重新关闭 MNN 视觉；只有可复现的真实不兼容才登记 EXCEPTION。

## 16. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 35B 自动重载耗时长、内存高 | 发送前显示重载状态；串行生命周期；禁止循环 |
| 参数迁移改变旧用户行为 | 保留旧 JSON 备份；显示迁移摘要；允许恢复 |
| 日期注入影响 prefix cache | 只注入按天稳定的日期；精确时间走本地工具 |
| ZIP 解压占满空间 | 预估总大小、压缩比限制、staging 空间检查 |
| 未知模型被过度调参 | UNKNOWN 使用保守 CPU 配置，不声称最优 |
| Local API 被请求触发频繁重载 | 非标准加载字段返回 409，不允许静默重载 |
| 调优候选导致 native crash / OOM | 高风险候选在 :tuning 隔离进程；事前 journal；启动恢复 LKG/安全基线；失败候选隔离 |
| 调优时间过长或设备过热 | SafetyEnvelope、动态 ETA、候选上限、冷却/充电续跑、暂停和 watchdog |
| 静态知识库无法覆盖新模型 | metadata 能力发现 + UNKNOWN 安全基线 + 版本化受约束实测；不用模型名猜测能力 |
| 调优证据泄露隐私或过度增长 | 只保存脱敏测量、设置容量/保留期；不保存 prompt、会话、绝对路径、密钥或设备唯一标识 |

## 17. 实际实施顺序与后续专项验收

1. 先修错误分类和 CPU 参数过滤，使 35B 用户立即得到正确提示并能加载。
2. 建立 ParameterFieldPolicyRegistry 和参数归属分层，先阻止助手/会话/Local API 携带加载字段进入 native。
3. 实现 ModelRuntimeIdentity、完整签名快照集合、runtime adapter、ParameterCoordinator 和 -11 / 非 llama 等价签名恢复。
4. 实现 profile/journal 数据库、pending/active/LKG 原子事务、lifecycle lease 和崩溃恢复。
5. 拆分并迁移模型执行配置、助手生成参数和会话诊断引用，同时完成 REST/Binder 写头前 preflight。
6. 完成能力发现、CandidateExecutor、SafetyEnvelope、CorrectnessSuite、分阶段调优、评分、应用和回滚。
7. 接入设备日期工具，完成 Gemma UI/API 回归。
8. 完成 MNN ZIP inspector、路径保留和原子导入。
9. 在一台代表设备的同一 APK 上完成 MainActivity + 真实认证 Local API 全矩阵，再提升 ruleEvidenceLevel=REPRESENTATIVE_VERIFIED、设置 featureCompatibility=OPEN 并发布。
