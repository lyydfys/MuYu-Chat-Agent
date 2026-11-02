# MCA 天玑 NPU + Anima 生图实施方案

状态：只读调研后的执行方案，未修改生产代码。
目标读者：可以按步骤执行、但不能自行补齐架构假设的弱模型或初级开发者。
范围：先让 Anima 通过现有 stable-diffusion.cpp CPU 路径可验证，再评估并接入 MediaTek NeuroPilot/Neuron NPU 路径。

## 1. 先读结论

本任务不能从“给模型目录加一行”和“把 backend 字符串改成 npu”开始。当前工程没有联发科 NPU runtime、delegate、JNI binding 或可分发的 MediaTek SDK；现有 QNN/HTP 代码是 Snapdragon 专用路径，不能复用成天玑实现。

应拆成两个独立交付物：

1. Anima CPU bring-up：复用现有 ImageGenerationOwner、LocalImageProvider、worker、图库和 Local API，只增加 Anima 的模型族、组件拓扑、执行 profile 和 stable-diffusion.cpp 证据。此阶段不宣称 NPU。
2. 天玑 NPU feasibility + integration：先获得并审查 NeuroPilot/Neuron SDK、授权和目标设备支持，再为 Anima 的文本编码器、DiT 和 VAE 分图转换与 delegate 执行。只有真实设备上产生 NPU graph execution 证据后，才允许显示 NPU 生图能力。

如果第二条线的 SDK、许可、算子覆盖或设备 runtime 不能满足条件，交付应停在“Anima CPU 可用 + 天玑 NPU BLOCKED”，不能用 CPU、OpenCL、MNN 的结果填充 NPU 结论。

本方案中的 “Anima” 指仓库已固定的 third_party/stable-diffusion.cpp/docs/anima.md 所描述的扩散模型。公开的 Fullive-AI/Anima 是智能家居 Agent OS，与 Android 扩散生图无关；如果用户指的是另一个 Anima 仓库，应先替换参考链接和模型契约。

## 2. 当前基线和不可覆盖项

开始任何实现前，执行并保存输出：

    Set-Location E:\MAC
    git rev-parse HEAD
    git branch --show-current
    git status --short --branch
    git submodule status --recursive
    git -C third_party/stable-diffusion.cpp status --short --branch

当前已知基线：分支为 codex/enterprise-refactor，父仓库约有 601 个 tracked 修改及大量未跟踪文件；stable-diffusion.cpp、MNN、llama.cpp 子模块也有既有内部修改。根目录的 .obj、.exe、旧 artifacts、.cxx 和旧日志不是本次生产通过证据。

禁止执行以下操作：

- git reset --hard、git clean -fdx、覆盖其他 Agent 的文件或还原脏子模块；
- 把 AGENTS.md 或本地策略测试加入提交；
- 把模型权重、私有 SDK、API key、APK 或临时 native 产物加入仓库；
- 把设备型号、SoC、认证名单或“未测试”作为隐藏下载、导入、加载、运行入口的条件。

每个阶段都必须记录：实际改动文件、owner、状态写入者、测试命令和 PASS/FAIL/NOT_RUN/BLOCKED，不能只写“已完成”。

## 3. 已确认的源码事实

### 3.1 现有图片执行链

生产链路是：

    Chat/Image UI 或 Local API
      -> ImageGenerationOwner
      -> LocalImageGenerationCoordinator / LocalImageProvider
      -> LocalImageRuntime 分派
      -> worker protocol / runtime owner
      -> 结果校验、图片资产提交、历史记录

关键入口：

- app/src/main/java/com/muyuchat/mca/ImageGenerationOwner.kt
- app/src/main/java/com/muyuchat/mca/LocalImageGenerationCoordinator.kt
- app/src/main/java/com/muyuchat/mca/LocalImageGenerationSdcppOperations.kt
- app/src/main/java/com/muyuchat/mca/LocalImageGenerationMnnOperations.kt
- app/src/main/java/com/muyuchat/mca/LocalImageWorkerProtocol.kt
- app/src/main/java/com/muyuchat/mca/LocalImageWorkerService.kt

新增 runtime 必须接入同一个 owner 和同一个终态/资产提交链；不能在 Compose、Activity、Local API 或新 worker 中另建一套队列、状态流或 native runtime。

### 3.2 当前 stable-diffusion.cpp 实际是 CPU

core/sd-native/src/main/cpp/CMakeLists.txt 当前对 Android arm64 设置 CPU KleidiAI/OpenMP，并关闭 SD_VULKAN、GGML_VULKAN；没有 MediaTek、Neuron、NNAPI 或通用 NPU backend。stable_diffusion_bridge.cpp 的 context 参数固定为：

    backend = cpu
    params_backend = cpu

运行时诊断也返回 runtimeBackend = cpu。因此 Anima 即使被模型目录识别，也只能先走 CPU stable-diffusion.cpp，不能宣称天玑 NPU。

### 3.3 Anima 的真实组件和图结构

third_party/stable-diffusion.cpp/docs/anima.md 给出的组件不是单文件：

- Anima diffusion model：safetensors 或 GGUF；
- qwen_image_vae.safetensors；
- Qwen3-0.6B-Base 文本编码器：safetensors 或 GGUF。

上游示例使用：

    --diffusion-model anima-preview.safetensors
    --vae qwen_image_vae.safetensors
    --llm qwen_3_06b_base.safetensors
    --cfg-scale 6.0
    --sampling-method euler
    --offload-to-cpu

源码中的 Anima 不是 SD1.5/SDXL UNet：src/anima.hpp 声明 16 输入/输出 latent channels、2048 hidden、16 heads、128 head dim、28 层 DiT；AnimaConditioner 使用 Qwen3 LLM、Qwen BPE，并生成 T5 tokenizer 的 ID/weight side data；采样阶段通过 AnimaDiffusionExtra 把这些数据传给 DiT。现有 QNN 的 4-channel SD1.5/SDXL graph contract 不能直接套用。

### 3.4 当前 app 类型和 profile 尚无 Anima

目前：

- LocalImageRuntime 有 STABLE_DIFFUSION_CPP、MNN_DIFFUSION、QNN_HTP、ONNX_RUNTIME、CUSTOM；
- LocalImageModelFamily 没有 ANIMA；
- ImageModelVariant 没有 ANIMA；
- ImageEngineModelFamily/Variant 没有 ANIMA；
- StableDiffusionComponentSelection 对 split family 默认把 TEXT_ENCODER 映射到 llm，并要求 VAE + text encoder；
- 现有 QNN_HTP runner 和 bundle contract 明确是 Snapdragon/QNN graph 路径；
- MNN_CONVERT_NEUROPILOT 只是 MNN 离线转换枚举，不能证明 Android NeuroPilot runtime 存在。

### 3.6 可核验的公开参考项目

这些项目能证明 NeuroPilot 的集成形态，但没有一个项目可以直接证明本仓库的 Anima 三图已经能在天玑手机上运行：

| 项目 | 真实覆盖范围 | 对本项目的用处和限制 |
|---|---|---|
| [MediaTek-NeuroPilot/tflite-neuron-delegate](https://github.com/MediaTek-NeuroPilot/tflite-neuron-delegate) | TFLite Neuron Delegate、Bazel/AAR 构建和 native shim | 最接近普通 Android APK 的 delegate 参考；仍需要 MediaTek 的 Neuron SDK/runtime，不能从本仓库直接编译出可用 NPU 库 |
| [sitjac/mtk-neuro-pilot-example](https://github.com/sitjac/mtk-neuro-pilot-example) | `NeuroPilotTFLiteShim.h`、模型转换和 native 推理示例 | 可参考 JNI/转换流程；示例不包含 Anima 的 Qwen3、DiT、VAE 三图契约，也不提供本项目所需的发行 runtime |
| [onnxruntime/onnxruntime-ep-neuropilot](https://github.com/onnxruntime/onnxruntime-ep-neuropilot) | ONNX Runtime NeuroPilot plugin EP；需要 `libneuron_op_capability.so`，设备还需要 `libneuronusdk_adapter.mtk.so` | 可参考动态加载、子图分区和 CPU fallback 证据；不是 Android Anima pipeline，且含闭源运行时依赖 |
| [bearwbearw/neuropilot-ep-evalkit](https://github.com/bearwbearw/neuropilot-ep-evalkit) | 在 Genio 板上统计 NPU/CPU 分区、精度和性能的评估工具 | 可参考 smoke/evidence 格式；README 明确要求用户自行提供 plugin 和 support library，不能当作手机端 runtime |
| `third_party/MNN/source/backend/neuropilot` | 开源 MNN 中的占位目录 | README 明确说明 NeuroPilot 后端不随开源仓库发布，CMake 会强制 `MNN_NEUROPILOT=OFF`；不能把 MNN 枚举当成已接入实现 |

许可证和依赖必须在引入源码前单独审查。公开参考项目不包含本项目模型权重、MediaTek 私有 SDK 或可直接重新分发的 `libneuron*`，本仓库不复制这些二进制。

其中 TFLite delegate 参考暴露 `TfLiteNeuronDelegateCreate/Delete`，shim 示例通过 `ANeuroPilotTFLite_*` 动态入口执行；这些 API 只能在拿到匹配版本的 SDK/AAR 和设备 runtime 后接入。ORT EP 的 README 还要求与目标平台匹配的 `libneuron_op_capability.so`，Android 运行时需要 `libneuronusdk_adapter.mtk.so`。这些依赖缺一项都只能进入 BLOCKED，不能用一个“delegate 对象创建成功”日志替代真实 NPU 执行证据。

### 3.5 天玑官方约束

MediaTek NeuroPilot 官方资料说明：第三方 Android APK 优先使用 TFLite Shim + NNAPI Delegate 或 Neuron Delegate；Neuron Runtime API 有 vendor-partition 使用限制，不能直接按厂商应用示例塞进普通 APK。offline DLA 编译还绑定具体 MDLA 硬件和 Neuron 版本。SDK/工具需要向 MediaTek 申请，申请时要给出目标 SoC、Android 版本和 AI 功能。

参考：

- https://developer.mediatek.com/ai/6423c8b6c612745b3a4baa2c.html
- https://developer.mediatek.com/ai/6423c916c612745b3a4baa2d.html
- https://developer.mediatek.com/ai/642fd8304054c120c42632af.html

## 4. 目标架构

### 4.1 CPU Anima

CPU 阶段继续使用：

    LocalImageRuntime.STABLE_DIFFUSION_CPP
    LocalImageModelFamily.ANIMA
    ImageModelVariant.ANIMA_PREVIEW

不要新增一个“Anima runtime”。Anima 是模型族/变体，stable-diffusion.cpp 是执行 runtime。

组件应通过现有角色表达：

    DIFFUSION    anima diffusion file
    VAE          qwen image VAE
    TEXT_ENCODER Qwen3-0.6B-Base (native slot: llm)
    TOKENIZER    只有在实际 native resolver 需要外部文件时才声明

不要把 T5 tokenizer 误写成第二个神经文本编码器。当前 stable-diffusion.cpp 的 AnimaConditioner 只有一个 Qwen3 LLM graph，但会生成 Qwen 和 T5 两套 token side data；是否需要额外 T5 文件必须以实际 checkpoint 和 loader 检查结果为准，不能凭文件名猜测。

### 4.2 天玑 NPU

只有在 SDK gate 通过后新增独立 runtime，例如：

    LocalImageRuntime.MEDIATEK_NEURON
    ImageEngineBundleRuntime.MEDIATEK_NEURON
    ImageEngineAccelerator.MEDIATEK_NPU

不能把 QNN_HTP 改名、给 MNN_DIFFUSION 增加隐藏分支，或把 backendMode = npu 传给 CPU stable-diffusion.cpp。

推荐的首版图划分：

    CPU: tokenizer + prompt packing + scheduler + request/asset IO
    NPU: Qwen3 text encoder（若 delegate 支持）
    NPU: Anima DiT / denoiser，每个 scheduler step 重复执行
    NPU: Qwen VAE decoder
    CPU: PNG/JPEG 编码、最终文件提交

如果某一图不能被 delegate 接管，必须记录实际的 CPU/NPU 分区，显示为 hybrid 或失败；不能把“delegate 创建成功”当作整条 pipeline 在 NPU 上运行。

## 5. 分阶段执行清单

### Phase 0：基线与证据目录（只读）

改动：只新增任务记录或方案附件，不改生产代码。
执行：

1. 保存第 2 节的 git/submodule 输出。
2. 读取 AGENTS.md、docs/architecture/MCA_PROJECT_HARD_LOCK.md 和本方案。
3. 记录 stable-diffusion.cpp 父 SHA、子模块 HEAD、内部 diff 摘要。
4. 建立 artifacts/architecture/<date>-dimensity-anima/，只放脱敏日志和 manifest fixture，不放权重。

通过条件：能区分当前源码、脏子模块改动和旧 build 产物；不能通过时停止，不能清理工作区。

### Phase 1：独立证明 Anima 原生 CPU 路径

目标：在不接 UI 的情况下证明固定版本的 stable-diffusion.cpp 能加载 Anima 三类组件并输出 PNG。

1. 固定一个可分发模型版本、文件名、大小、SHA-256、许可证和来源；至少记录 diffusion、VAE、Qwen3 LLM 三个文件。
2. 在仓库外或临时目录构建/运行上游 sd-cli，使用与文档相同的 Euler、CFG 6.0 参数。不要把权重复制进仓库。
3. 记录模型识别为 VERSION_ANIMA、text encoder、diffusion、VAE 的加载日志，以及尺寸、步数、seed、耗时和峰值内存。
4. 验证取消、失败和输出文件原子写入；没有 PNG 或 native 失败就标记 FAIL。

通过条件：同一模型/同一参数在 native CLI 产生可读 PNG；仅“模型文件能打开”不算通过。

### Phase 2：把 Anima 接入现有 CPU profile

建议写集：

- app/src/main/java/com/muyuchat/mca/LocalImageBundleContracts.kt
  - 增加 ANIMA family；from/infer 只依据包/文件证据，不依据芯片。
- app/src/main/java/com/muyuchat/mca/ImageExecutionProfileTypeContracts.kt
  - 增加 ANIMA_PREVIEW variant；同步 schema/json 解析和兼容读取。
- app/src/main/java/com/muyuchat/mca/ImageExecutionProfileJsonBehavior.kt
  - 增加 anima/anima_preview 的 family/variant 映射。
- app/src/main/java/com/muyuchat/mca/ImageExecutionProfileTemplates.kt、ImageExecutionProfileCatalogData.kt
  - 增加一个有固定 revision/fingerprint 的 sdcpp.anima profile。
- app/src/main/java/com/muyuchat/mca/StableDiffusionComponentSelection.kt
  - 将 ANIMA 纳入显式 companion family；text encoder slot 使用 llm；要求 diffusion + VAE + Qwen3 LLM；按真实 loader 结果处理 tokenizer。
- core/download/src/main/java/com/muyuchat/core/download/ModelScopeImageEngineTypes.kt
  - 增加 Anima family/variant；不要在没有已核实来源和许可证的情况下添加推荐下载项。
- core/download/src/main/java/com/muyuchat/core/download/ModelScopeImageEngineContracts.kt
  - 保证 split bundle 的组件、默认值、scheduler 和尺寸约束能够表达 Anima；必要时增加向后兼容字段或 schema revision。
- app/src/main/java/com/muyuchat/mca/LocalImageGenerationSdcppOperations.kt
  - 只增加 Anima 的 profile/native contract 分支；保留现有 owner、lease、progress、cancel、asset commit。
- core/sd-native/src/main/cpp/mca/sd/* 或 stable_diffusion_bridge.cpp
  - 仅在 Phase 1 证明当前 JNI 不能传递 Anima topology 时修改；不要顺便改 backend。

CPU profile 初始值：Euler + CFG 6.0 可作为上游 smoke 默认；步数、宽高倍数、负面提示词、prompt 上限必须由 native 实测和 profile contract 决定，不能照搬 SD1.5/SDXL。Anima 的 16-channel latent 和 DiT 维度必须进入 execution evidence，不能伪装成 4-channel UNet。

通过条件：

- 完整 bundle 才注册为 ready；缺 diffusion/VAE/LLM 的包给出具体缺件；
- CPU 请求经过现有 ImageGenerationOwner，UI 和 Local API 结果一致；
- native 返回 actualModelFamily=ANIMA、actualModelVariant=ANIMA_PREVIEW、组件路径/fingerprint、runtimeBackend=cpu；
- 取消、超时、进程死亡、失败、重试和图库提交沿用现有终态；
- 任何未知设备仍可下载/导入/尝试通用 CPU 路径。

### Phase 3：增加 Anima CPU 回归保护

新增测试只覆盖真实风险：

- LocalImageModelFamily/variant 解析不会把 Anima 误判成 Qwen Image、Flux 或 CUSTOM；
- manifest 必须绑定唯一 diffusion、VAE、LLM，并拒绝路径逃逸、缺件、哈希/大小不匹配；
- StableDiffusionComponentSelection 将 LLM 绑定到 llm，不会丢 VAE；
- Anima profile 的 16-channel/采样器/CFG/尺寸合同不会落入 SD1.5 默认；
- native response 的 family、variant、backend 和实际组件与 Android request 一致；
- ImageGenerationOwner 的 requestId/lease/取消/失败终态不回写旧请求；
- Local API 和 MainActivity 共用同一个执行 owner；
- 损坏一个组件只导致具体运行失败或 generic CPU fallback，不产生设备黑名单。

建议命令（以实际匹配数量和退出码为准）：

    python tools/architecture/check_architecture_rules.py
    .\scripts\verify-code.ps1 -Tests '*Image*Profile*'
    .\scripts\verify-code.ps1 -Tests '*LocalImage*'

过滤器没有匹配测试时记为 NOT_RUN，不能写成 PASS。

### Phase 4：MediaTek SDK feasibility gate（先于任何 NPU 代码）

必须取得并审查：

1. NeuroPilot/Neuron SDK 版本、Android API、ABI、目标 Dimensity/MDLA、license 和 APK 重新分发条件；
2. TFLite Shim + NNAPI Delegate、TFLite Shim + Neuron Delegate 的真实 sample 和依赖库；
3. 是否允许普通第三方 APK 加载所需 runtime；
4. Anima 三类图（Qwen3 text、Anima DiT、Qwen VAE）所需算子、静态 shape、量化/浮点格式、动态 batch/尺寸限制；
5. online compile 与 offline DLA compile 的版本绑定、首帧延迟、HIDL 开销和 fallback 语义。

硬性停止条件：没有 SDK/许可/运行库，或没有明确的第三方 APK 部署方式，Phase 4 只能 BLOCKED；不能提交伪造的 libneuron.so、stub 结果或“天玑 NPU 已支持”文案。

### Phase 5：在仓库外做单图 NPU spike

先做一个最小 TFLite/Neuron 验证程序，不接 MCA UI：

1. 先从最容易转换的 VAE 或单个 DiT block 开始，不要一次转换完整 Anima。
2. 固定输入 shape、dtype、layout、量化参数和输出 checksum。
3. 打印 delegate 分区数量、每个分区的设备、未接管的 op、runtime/SDK 版本、耗时和峰值内存。
4. 用 vendor trace/perf counter 或官方 API 证明实际 NPU/MDLA 执行；“delegate 对象创建成功”不算。
5. 对同一输入跑 CPU reference，做有限误差比较；数值不一致时定位到转换、量化或 layout，而不是放宽断言。

单图通过条件：至少有一个真实 NPU 分区执行并产生稳定输出；如果所有 op 都回退 CPU，记为 FAIL。若只支持某个具体设备，记录为 runtime compatibility evidence，不把设备型号写成全局准入名单。

### Phase 6：实现独立 MediaTek runtime/worker

SDK gate 和单图 spike 通过后再改仓库。

建议写集：

- 新建 :core:mediatek-native（或经架构审查后使用同等隔离模块），封装 TFLite Shim/Neuron delegate JNI；不要把 MediaTek 头文件塞进 core/sd-native。
- settings.gradle.kts、对应 Gradle/CMake 文件：SDK 通过显式 MCA_MEDIATEK_SDK_ROOT 注入；没有 SDK 时构建必须明确跳过或失败，不能链接假库。
- app LocalImageRuntime、core/download ImageEngineBundleRuntime、accelerator enum：增加 MEDIATEK_NEURON/MEDIATEK_NPU，保留旧 JSON 值和迁移；不改 QNN_HTP 含义。
- 新增 LocalImageMediatekRunner/LocalImageGenerationMediatekOperations，只接收 typed bundle/profile，不接收万能 container 或 Any。
- 复用 ImageGenerationOwner、LocalImageProvider 和 LocalImageWorkerProtocol；必要时增加向后兼容的 runtime/stage/evidence 字段，不复制第二套队列。
- 如 runtime 生命周期、SONAME、厂商库或崩溃风险要求隔离，使用现有 worker service/process 边界；取消必须是 request cancel + worker teardown fallback，不能只停止 Flow collector。

MediaTek bundle manifest 最少要能表达：

    schema: mca.image_engine.bundle.v2
    family: ANIMA
    runtime: MEDIATEK_NEURON
    accelerator: MEDIATEK_NPU
    components:
      TEXT_ENCODER -> graphs/qwen3_text.tflite
      DIFFUSION    -> graphs/anima_dit.tflite
      VAE          -> graphs/qwen_vae.tflite
      TOKENIZER    -> tokenizer/...
    graphs:
      text/diffusion/vae inputs, outputs, delegate, graph hash
    sdk:
      name=neuron, version, compileMode=online|offline

这是执行契约示意，不得直接复制为空字段。真实 tensor 名称、shape、dtype、delegate、SDK 和图 hash 必须从转换产物生成并校验。若 schema v2 破坏旧安装，必须提供迁移、旧 fixture 和回退说明。

阶段内执行顺序：

1. 解析并校验 manifest、路径、大小、SHA-256、图输入输出和 SDK metadata；
2. worker health/load，只报告 runtime/graph 可加载，不报告“可生图”；
3. 1-step graph smoke：text（若有）、DiT、VAE 分别执行并写 stage journal；
4. semantic generation：tokenizer → text → scheduler loop → DiT 多步 → VAE → PNG；
5. 只有 semantic generation + NPU evidence 都通过时，才把 engine 设为可选择；
6. 所有失败均回到具体 bundle/runtime/graph/tensor/delegate/output/cancel 错误，保留 CPU Anima 路径。

### Phase 7：真实设备和双入口验收

至少覆盖一台真实 ARM64 Dimensity 设备和一个非目标/未知设备：

| 场景 | 必须观察 |
|---|---|
| CPU Anima 文生图 | PNG、参数、seed、耗时、内存、进度、取消、重试 |
| MediaTek 1-step graph smoke | runtime load、delegate 分区、真实 NPU evidence、输出 checksum |
| MediaTek semantic generation | Qwen3/DiT/VAE 全链路、多个 scheduler step、PNG |
| MainActivity | 选择、进度、预览/结果、失败文案、图库写入 |
| Local API | 同一 owner、同一 requestId/终态、认证和 JSON/SSE 契约 |
| 进程死亡/后台切换 | worker 重连或失败结算，不产生悬挂生成标志 |
| 损坏/缺失组件 | 具体失败；CPU generic fallback 仍可尝试 |
| 未知 SoC/无本地 profile | 不隐藏下载/导入/运行入口；由真实 native load/graph 决定 |

记录 app version、父 SHA、子模块内部 diff 摘要、设备型号/SoC/Android/RAM、每个组件 hash、尺寸、步数、线程、耗时、峰值内存、取消结果和 stage trace。不要记录 prompt、API key 或私人内容。

## 6. 准入、fallback 和状态规则

必须保持以下规则：

- minDeviceTier、Dimensity 型号、设备 profile 只能用于推荐 runtime/参数；不能阻止下载、导入、加载或通用运行。
- “未知芯片”“未认证”“没有本地 profile”不能作为拒绝原因。
- 只有完整性损坏、缺少必需 binary、格式无效、native load 失败、graph execute 失败、输出校验失败等具体运行条件可以拒绝一次请求。
- NPU 请求失败时，错误要指出阶段；用户仍能选择或继续尝试稳定的 CPU Anima 兼容路径。不能静默把 NPU 请求改成 CPU 后仍标记 npuActive=true。
- NPU 证据必须至少包括请求 runtime、实际 delegate、每个图的执行阶段、CPU fallback op 数/原因、输出 artifact 和版本信息。

## 7. 弱模型执行协议（每次只做一个切片）

把下面的规则原样作为后续实现 Agent 的工作提示：

    你不是唯一 Agent。先读取 AGENTS.md、docs/architecture/MCA_PROJECT_HARD_LOCK.md 和本方案。
    先运行基线命令，保留当前工作区和所有子模块内部修改；禁止 reset、clean、覆盖他人文件。
    一次只执行一个 Phase。开始前列出本 Phase 的写集、owner、状态写入者、回退点和验证命令。
    先做 CPU Anima；没有通过 CPU native PNG 证据，不得写 NPU 代码。
    没有 MediaTek NeuroPilot/Neuron SDK、license、目标设备和真实 delegate 证据时，标记 BLOCKED 并停止该 Phase，不写假 runtime。
    不能复用 QNN_HTP 表示天玑，不能把 MNN_CONVERT_NEUROPILOT 枚举当成 runtime。
    不能新增第二套 ImageGenerationOwner、队列、全局状态 writer 或 UI/API 平行执行链。
    所有模型/图/SDK 文件都要有来源、版本、大小、SHA-256 和许可证；权重和私有 SDK 不入库。
    每次结束输出：改动文件、行为影响、测试命令及匹配数量、PASS/FAIL/NOT_RUN/BLOCKED、未验证项、回退方式。

### Agent 交付模板

    Phase：
    基线 revision / 子模块 revision：
    本次改动文件：
    新增状态与唯一 owner：
    数据/协议/视觉影响：
    测试命令与实际执行数量：
    结果：PASS / FAIL / NOT_RUN / BLOCKED
    失败或未验证项：
    回退点：
    下一步前置条件：

## 8. 最终 Go/No-Go

## 8.1 本次代码交付边界

在没有 MediaTek SDK、授权 runtime 和真实天玑设备的环境中，本次最多交付以下可编译代码：

- 独立的 `:core:mediatek-native` SDK-gated 模块，提供 typed bundle/graph/evidence 契约和 resolver；
- 未配置 SDK 或 manifest 不完整时返回可诊断的 `BLOCKED`，不创建 JNI delegate、不伪造 NPU 计数、不把 CPU 结果标为 NPU；
- SDK 根目录、NeuroPilot 版本、ABI 和必需库的构建检查；
- manifest 路径、大小、SHA-256、graph 输入输出和 runtime metadata 校验的 JVM 单测；
- 真实 TFLite/Neuron JNI、Anima 图转换、NPU trace 和 MainActivity/Local API 接入保留为后续 Phase 5/6，必须在 SDK gate 和设备 spike 通过后实现。

因此，“代码模块已建立”不等于“天玑 NPU 生图已完成”。没有真实 delegate 和设备证据时最终状态只能是 `BLOCKED` 或 `NOT_READY`。

### 可以宣称“Anima 本地生图可用”

- 固定组件完整、hash/许可证记录齐全；
- stable-diffusion.cpp native 真实生成 PNG；
- app CPU 路径经过 MainActivity 和 Local API；
- 取消、失败、重试、资产提交和进程恢复有证据；
- 文案明确为 CPU/实验性本地生图，不宣称 NPU。

### 可以宣称“天玑 NPU Anima 生图可用”

除上项外，还必须同时满足：

- MediaTek SDK/许可和第三方 APK 部署方式经过审查；
- 文本、DiT、VAE 图的转换产物和输入输出契约固定；
- 真实设备 delegate trace/计数证明至少对应图在 NPU/MDLA 执行；
- semantic generation 完成并输出 PNG，非仅 graph smoke；
- CPU fallback、取消、worker 崩溃、内存/热限制和输出错误可定位；
- MainActivity 与认证 Local API 使用同一 execution owner；
- 未知设备仍保留通用兼容路径，没有芯片 allowlist；
- 记录可复现的 app、源码、子模块、SDK、模型和设备证据。

任何一项缺失都只能写 NOT_READY 或 BLOCKED，不能写“天玑 NPU 已支持”。

