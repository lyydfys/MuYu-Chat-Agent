# MCA 推荐生图模型质量对标与产品化重构执行方案

- 制定日期：2026-07-17
- 状态：代码实施完成；代表性 QNN SD1.5 profile 已通过正式双入口验收
- 目标分支：mnn-tnn-benchmark
- 正式验收设备：一台代表性 ARM64 Elite 设备
- 正式验收入口：生产 MainActivity UI 与认证 Local API
- 基准策略：外部质量基准只作本地行为与画质 oracle，不进入产品源码或发布文档
- 实现策略：许可友好上游实现 + MCA 原生产品化重写

## 0. 执行结论

本方案不移植外部基准应用，而是复现正确的数值执行契约，并把它重构为 MCA 自己的多 runtime 产品能力。

核心决策：

1. 外部质量基准只用于确认模型包结构、embedding 格式、正确参数和输出质量。
2. Scheduler 以 Hugging Face Diffusers 的 Apache-2.0 实现和数值行为为权威来源。
3. Qualcomm Gen5 以 Qualcomm AI Hub Models 的 BSD-3-Clause 参考管线为权威来源。
4. MNN 以仓库已固定的官方 Apache-2.0 版本为权威来源。
5. CLIP tokenizer 使用 Apache-2.0 的标准 tokenizer 实现，不再维护简化 Unicode/BPE 分词器。
6. stable-diffusion.cpp 保留成熟 native sampler，MCA 只负责 profile 解析、参数映射和真实回显。
7. 所有模型共享统一 ImageExecutionProfile 与生命周期，但不共享错误的 family 全局参数。
8. 相同模型包目标为同一视觉质量档位；不同来源模型对标各自官方参考实现。
9. 不承诺逐像素一致，允许量化、浮点和随机数实现造成的合理差异。
10. graphExecute、PNG 非空和尺寸正确只代表技术执行通过，不代表画质通过。

## 1. 硬约束

### 1.1 通用设备开放

- 不得以芯片、设备型号、验证设备、认证状态、profile、allowlist 或 whitelist 隐藏或阻止下载、导入、加载和运行。
- 硬件信息只用于推荐排序、下载包选择、runtime transport 和设备本地性能调优。
- 未知设备和缺少本机档案必须回退到通用兼容路径，让真实 native load、graph smoke 或 generation 决定结果。
- 一台代表性 ARM64 设备通过生产 UI 和认证 Local API 后，该能力对全部兼容设备开放。
- 个别设备失败时只添加最窄兼容 fallback，不恢复任何设备准入名单。
- 只有损坏或不完整包、非法格式、缺少必需二进制、真实 native load 失败或真实执行失败可以拒绝请求。

### 1.2 质量优先

- 普通 SD1.5、SD2.1 和普通 SDXL 不得默认使用未达到画质门槛的极低步数。
- 1 至 8 步只适用于明确蒸馏、Turbo、LCM、Hyper 或 DMD2 模型。
- 速度 preset 必须公开展示实际 scheduler、steps、CFG、seed 和分辨率。
- native 真正执行值必须与 requested/resolved 值一致；不允许请求显示 Euler、native 实际执行 PNDM。
- 不得用内部 smoke、debug Activity、影子服务或复用结果作为正式验收。

### 1.3 真机验收

- 单元测试、scheduler golden 和静态检查是快速预检，不是最终完成证据。
- 正式完成必须由生产 MainActivity UI 和认证 Local API 各自产生独立 requestId、独立 native generation sequence 和独立输出。
- Elite 是主验证设备；Gen2 只在不拖慢主线时做辅助回归。
- 一个 execution profile 的代表模型通过双入口，即打开该 profile 的设备能力。
- 每个推荐模型仍需完成包完整性和至少一次真实任务出图，防止单个 ZIP 内容错误。

### 1.4 仓库命名去标识

- 产品代码、测试、注释、日志、UI、API、profile ID 和可发布文档不得出现外部基准项目名称。
- 产品内统一使用 community_clip、external_quality_baseline 或“外部质量基准”等中性术语。
- 外部应用身份、URL、临时检出路径和对照产物只保存在仓库外或已排除的本地实验目录。
- 每次提交前对 app、core、api、feature 和 docs 执行大小写不敏感零命中扫描；发现旧名称即阻止提交。

### 1.5 本地策略文件

- AGENTS.md 与 app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt 保持本地排除。
- 不得 stage、commit、push 或以其他形式发布这两个文件。
- docs/experiments 原始设备证据继续保持本地。

## 2. 上游来源与许可证

| 来源 | 固定版本 | 许可证 | 产品用途 |
|---|---|---|---|
| 外部质量基准 | 固定本地版本和 commit，身份不写入仓库 | 非商业署名许可证 | 只作行为和画质 oracle，不复制代码、注释、UI、提示词文本或目录资产 |
| Hugging Face Diffusers | v0.35.1，实施前固定完整 commit | Apache-2.0 | Scheduler 公式、配置语义、golden vectors |
| Qualcomm AI Hub Models | db311c000378c7142fe32bd0c4aea25db873adcc | BSD-3-Clause | Gen5 SD1.5、SD2.1、ControlNet 参考管线 |
| Alibaba MNN | cc20f672af9e177e2fa338c332dc097de2fc9264 | Apache-2.0 | MNN diffusion、tokenizer、runtime API |
| mlc-ai tokenizers-cpp 或 HF tokenizers | 实施前固定 commit | Apache-2.0 | 完整 tokenizer.json、Unicode normalizer、pre-tokenizer 和 BPE |
| stable-diffusion.cpp | be65ac7511b30379b003626c15224798929e33d4 | 当前子模块许可证 | GGUF/safetensors 多模型 runtime |

实施时新增 docs/IMAGE_PIPELINE_UPSTREAM_LICENSES.md，仅记录真正进入产品或测试树的许可友好上游：

- URL、tag、完整 commit、获取日期。
- 使用文件和用途。
- LICENSE、NOTICE 和版权头要求。
- golden 数据生成脚本和来源。
- 模型权重、QNN ZIP 和 tokenizer sidecar 的独立许可证。

外部质量基准身份和本地产物不写入该文档。

## 3. 当前根因基线

### 3.1 Scheduler

- QNN SD1.5/SDXL 当前只接受 PNDM。
- PNDM/PLMS 时间表缺少标准 skip-PRK 的重复校正 timestep。
- QNN 第二次校正没有保存首次 curSample，sample、timestep 和 model output 不一致。
- MNN direct 上层声明 Euler，native 实际运行自研 PNDM。
- MNN direct 固定 CFG 7.5，没有证明用户 CFG 真正执行。
- requestOptionsJson 只是请求审计，不能作为 native 执行证据。

### 3.2 Conditioning

- 社区 SD1.5 包可能提供 FP16 或 legacy FP32 token_emb.bin。
- 49408 × 768 × 2 = 75,890,688 字节是 FP16 表。
- 49408 × 768 × 4 = 151,781,376 字节是 FP32 表。
- 当前代码把 FP32 表误判成两份 FP16 slice，已产生明确伪影。
- 简化 CLIP tokenizer 没有完整执行 tokenizer.json 的 NFC、Unicode regex、pre-tokenizer 和 post-processor。
- 当前 unconditional prompt 固定为空，没有完整负面提示词产品能力。

### 3.3 Qualcomm Gen5

- 推荐包下载 scheduler_config.json，但 generation 路径没有消费。
- 官方 SD1.5 参考使用 EulerDiscreteScheduler，默认 20 步。
- 官方 SD2.1 要求 DDIMScheduler、v_prediction 和 steps_offset=1。
- 当前所有非 SDXL QNN 包共用 SD1.5 epsilon/PNDM。
- 官方 Gen5 VAE export 已在图内除以 scaling_factor，当前 host 又执行一次 1/0.18215，形成重复缩放。

### 3.4 SDXL

- 普通 SDXL 当前被强制为 1-step proof-of-life。
- 普通 SDXL、DMD2、Turbo 和其他蒸馏变体没有独立 profile。
- 两阶段 worker 只证明 UNet/VAE 分进程可运行，没有完成正确多步采样。

### 3.5 质量门

- 当前 PNG 检查主要排除无法解码、单色、低动态范围和横向条纹。
- 纯噪声和严重伪影仍可能通过。
- 现有 quality gate 实际是 corruption gate，必须改名并增加数学、语义、清晰度和相对基线判断。

## 4. 当前 18 个推荐模型与目标 profile

### 4.1 社区 QNN SD1.5

| 推荐 ID | 当前状态 | 目标 profile | 关键工作 |
|---|---|---|---|
| cyberrealistic_sd15_qnn228 | 推荐/快速 | community.sd15.qnn228 | 标准 tokenizer、正负 conditioning、20 步质量默认、正确 sampler |
| realisticvisionhyper_sd15_qnn228 | 推荐 | community.sd15.hyper.qnn228 | 读取 Hyper 少步配置，不套普通 SD1.5 默认 |
| dreamshaper_sd15_qnn228 | 推荐 | community.sd15.qnn228 | 模型配置和外部质量基线对照 |
| meinamix_sd15_qnn228 | 实验 | community.sd15.legacy-fp32.qnn228 | FP32 embedding 转换，移除 dual-slice 语义 |

### 4.2 社区 QNN SDXL

| 推荐 ID | 当前状态 | 目标 profile | 关键工作 |
|---|---|---|---|
| sdxl_base_qnn228 | 实验 | community.sdxl.base.qnn228 | 多步 scheduler、双 CLIP、pooled/time_ids、1024 输出 |
| realismsdxl_dmd2_alt_qnn228 | 实验 | community.sdxl.dmd2-alt.qnn228 | 从包契约读取 DMD2 steps/CFG/scheduler |
| animagine_xl_v4_qnn228 | 实验 | community.sdxl.base.qnn228 | 模型专属默认值和画质对照 |
| cyberrealisticxl_qnn228 | 实验 | community.sdxl.base.qnn228 | 移除普通模型 1-step proof 路径 |

### 4.3 Qualcomm Gen5

| 推荐 ID | 当前状态 | 目标 profile | 关键工作 |
|---|---|---|---|
| qualcomm_sd15_gen5_qnn | 推荐 | qualcomm.sd15.gen5.qnn245 | Euler、20 步、QNN text encoder、graph-internal VAE scaling |
| qualcomm_sd21_gen5_qnn | 推荐 | qualcomm.sd21.gen5.qnn245 | DDIM、v_prediction、动态 embedding width、正确 VAE scaling |
| qualcomm_controlnet_canny_gen5_qnn | 待接入 | qualcomm.controlnet-canny.gen5.qnn245 | canny 输入、residual 注入、base profile 联动 |

### 4.4 MNN

| 推荐 ID | 当前状态 | 目标 profile | 关键工作 |
|---|---|---|---|
| sd15_mnn_512_quality | 实验 | mnn.sd15.official.512 | 真实 sampler 回显、20 步默认、CFG/负面词生效 |
| mnn_sana_edit_v2 | 待接入 | mnn.sana-edit.v2 | IMAGE_EDIT 任务、输入图、mask/strength 和正确 scheduler |

### 4.5 stable-diffusion.cpp 与新架构

| 推荐 ID | 当前状态 | 目标 profile | 关键工作 |
|---|---|---|---|
| sd_turbo_512_experimental | 推荐 | sdcpp.sd-turbo | 使用模型元数据少步配置 |
| z_image_turbo_q4 | 实验 | sdcpp.z-image-turbo | flow/scheduler 和 GGUF 组件解析 |
| flux2_klein_4b_q4 | 待接入 | sdcpp.flux2-klein | 完整组件包、能力发现和模型默认值 |
| qwen_image_2512_q2 | 实验 | sdcpp.qwen-image | tokenizer/text encoder/autoencoder 组件绑定 |
| longcat_image_q4 | 实验 | sdcpp.longcat-image | metadata 驱动，不传 SD1.5 通用参数 |

外部质量基准只覆盖重叠的社区 SD1.5/SDXL QNN 包。Gen5、MNN 和 stable-diffusion.cpp 分别对标其官方或上游参考。

## 5. 统一 ImageExecutionProfile

### 5.1 数据结构

新增不可变、可版本化 profile：

    data class ImageExecutionProfile(
        val schemaVersion: Int,
        val profileId: String,
        val profileRevision: Int,
        val modelFingerprint: String,
        val runtime: LocalImageRuntime,
        val family: LocalImageModelFamily,
        val variant: ImageModelVariant,
        val task: ImageTask,
        val provenance: ImageProfileProvenance,
        val tokenizer: ImageTokenizerContract,
        val conditioning: ImageConditioningContract,
        val scheduler: ImageSchedulerContract,
        val latent: ImageLatentContract,
        val vae: ImageVaeContract,
        val graph: ImageGraphContract,
        val defaults: ImageGenerationDefaults,
        val capabilities: ImageGenerationCapabilities
    )

profile 必须绑定模型或包 fingerprint，不能只依赖显示名称、family 或设备型号。

### 5.2 Tokenizer contract

必须记录：

- backend：TOKENIZERS_CPP、MNN_MTOK、SDCPP_NATIVE。
- tokenizer.json、vocab、merges 或 sentencepiece 路径与 fingerprint。
- BOS、EOS、PAD、maxLength。
- Unicode normalization、lowercase、pre-tokenizer、post-processor。
- CLIP1/CLIP2 不同 PAD 规则。
- prompt weighting 和 textual inversion 能力。
- 正面/负面提示词独立编码规则。

### 5.3 Embedding contract

必须记录：

- token/position table shape。
- diskDataType：FP16、FP32、BF16、GRAPH_INTERNAL。
- exactByteSize、elementCount 和转换策略。
- text encoder 输入输出 shape。
- SDXL 双 encoder、pooled 输出和拼接顺序。

AUTO 只按精确结构解析：

- 精确 FP16 大小按 FP16 读取。
- 精确 FP32 大小逐元素转换。
- 其他大小返回 PACKAGE_FORMAT_INVALID。
- 产品语义中删除 dual_slice_first_half 和 dual_slice_second_half。

### 5.4 Scheduler contract

必须记录：

- algorithm：DPM++ 2M、Euler、Euler A、DDIM、PNDM/PLMS、LCM、FlowMatch。
- predictionType：epsilon、v_prediction、sample、flow。
- numTrainTimesteps、beta/sigma schedule。
- timestepSpacing、stepsOffset、setAlphaToOne、skipPrkSteps。
- initNoiseSigma 和 scaleModelInput。
- order、历史状态和重复 timestep 规则。
- default/min/max steps。
- RNG、seed 宽度和初始 latent layout。

PNDM 若保留，必须完全匹配 Diffusers：

- skip-PRK 时间表含重复校正 timestep。
- 保存首次 curSample。
- 第二次评估使用正确 sample/timestep。
- native 回显 timetable 长度和 UNet execution count。

### 5.5 Guidance contract

- defaultPrompt/defaultNegativePrompt 来源。
- defaultCfg、范围和推荐值。
- CFG 双分支或蒸馏单分支。
- 用户值与模型默认值优先级。
- 用户显式空负面词表示关闭默认值。

不得从外部基准复制默认提示词文本；使用模型发布者配置、模型卡或 MCA 自有默认值。

### 5.6 Latent 与 VAE contract

- latent shape/layout/channels。
- scheduler 与 UNet layout。
- VAE factor。
- scalingLocation：HOST_BEFORE_GRAPH、GRAPH_INTERNAL、NONE。
- VAE 输入输出 shape/layout/range/channel order。

禁止按 family 全局硬编码。社区 VAE、Qualcomm 官方图、MNN 和 stable-diffusion.cpp 各自使用明确契约。

### 5.7 Graph contract

- text encoder、UNet、VAE、ControlNet 安全相对路径。
- graphName、tensor role/shape/dtype/quantization。
- QNN SDK、HTP arch、context metadata。
- shared-session 或 split-process 策略。
- scheduler/tokenizer/config sidecar。

HTP arch 只用于 transport 选择，不用于用户准入。

## 6. Profile 解析与事务

### 6.1 解析优先级

1. 包内显式 executionProfile，schema/fingerprint 有效。
2. 标准 scheduler/config/tokenizer sidecar。
3. 精确 recommendation ID、revision、SHA 对应的内置 profile。
4. graph/tensor/文件结构能力发现。
5. family 通用兼容默认。
6. 用户本次请求覆盖。
7. 最终一致性校验。

设备只参与下载排序、transport、线程、内存和 worker 策略，不参与功能准入。

### 6.2 三层参数

每次请求保存：

- requested：用户/API 请求。
- resolved：profile 解析结果和字段来源。
- nativeEffective：native 真正执行值。

以下字段不一致必须报 EXECUTION_CONTRACT_MISMATCH：

- profileId/revision/fingerprint。
- scheduler/predictionType/steps。
- timestep count/UNet execute count。
- CFG/unconditional branch。
- tokenizer backend/token count。
- embedding disk dtype。
- VAE scaling location/factor。
- width/height/seed/runtime/graph/fallback。

### 6.3 Profile 事务

- 下载和导入进入 staging。
- hash、sidecar 和 profile 校验后原子安装。
- profile 使用 pending/active/LKG。
- 新 profile 真实 generation 失败时回滚同 fingerprint 的 LKG。
- 回滚仅影响该模型，不关闭 runtime 或其他设备。
- 文件变化后重新解析，不按旧文件名复用。

## 7. Scheduler 产品化

建议新增：

- core/native/src/main/cpp/diffusion_scheduler.hpp
- core/native/src/main/cpp/diffusion_scheduler.cpp
- core/native/src/main/cpp/diffusion_scheduler_config.hpp
- core/native/src/test/cpp/diffusion_scheduler_test.cpp
- tools/reference/generate-diffusers-scheduler-golden.py

第一批实现：

1. DPM++ 2M epsilon。
2. EulerDiscrete epsilon。
3. DDIM epsilon/v_prediction。
4. 标准 PNDM/PLMS。

第二批实现：

1. Euler A。
2. LCM。
3. SDXL/DMD2 包实际声明 sampler。
4. FlowMatch。

golden 测试逐步比较：

- scheduler config。
- timesteps/sigmas。
- 初始 latent。
- model output fixture。
- 每步 prev_sample。
- v_prediction 转换。

不只比较最终 PNG；必须定位第一个偏差 timestep。

## 8. Tokenizer、负面提示词和 embedding

### 8.1 标准 tokenizer

- 产品 generation 移除简化 ClipBpeTokenizer 依赖。
- 接入固定版本 tokenizers-cpp，完整加载 tokenizer.json。
- MNN MtokTokenizer 必须通过同一 token ID golden。
- stable-diffusion.cpp 保留原生 tokenizer并回显来源。

固定样例：

- 空字符串、英文缩写、数字标点。
- 中文连续文本和全角标点。
- NFC/分解 Unicode、emoji。
- 77 token 截断。
- SDXL encoder 2 PAD。

### 8.2 负面提示词

- LocalImageGenerationOptions 增加 negativePrompt。
- UI 增加负面提示词输入。
- Local API 接受 negative_prompt。
- profile 默认值只在用户未设置时应用。
- 用户显式空字符串不能被自动补回。

### 8.3 Prompt weighting

- 第一阶段实现基础括号权重。
- textual inversion 由包能力声明。
- 权重解析和 tokenizer 独立测试。

### 8.4 Legacy FP32

- MeinaMix 是强制回归包。
- FP32 表逐元素转换，禁止字节切片。
- 抽样向量与参考值比较。
- 使用 mmap/read-window，避免完整 FP32/FP16 双副本造成 OOM。

## 9. Runtime 改造

### 9.1 社区 QNN SD1.5

顺序：

1. CyberRealistic 代表路径。
2. DreamShaper。
3. RealisticVision Hyper。
4. MeinaMix。

要求：

- 标准 tokenizer 和正确正负 conditioning。
- profile 声明的 sampler/steps。
- QNN UNet/VAE 真执行，fallback=false。
- metadata 回显 scheduler、predictionType、timetable、embedding dtype 和 VAE scaling。
- Hyper 配置不得继承普通模型默认。
- MeinaMix 修复 FP32 后才能晋级。

### 9.2 Qualcomm Gen5 SD1.5

- token 文件为 negative 77 + positive 77 int32。
- text_encoder.bin 分别真实执行两次。
- 使用官方 EulerDiscreteScheduler 和 scaleModelInput。
- 默认 20 步，范围由 profile/sidecar 决定。
- VAE 直接接收 scheduler latent，scalingLocation=GRAPH_INTERNAL。
- 输出证明 text encoder、UNet、VAE 和实际 HTP runtime。

### 9.3 Qualcomm Gen5 SD2.1

- 新增 SD21 family/variant，不继续当 SD15/CUSTOM。
- 读取 scheduler_config.json。
- DDIM + v_prediction。
- text encoder width 由 graph 动态校验。
- VAE scalingLocation=GRAPH_INTERNAL。
- 缺 sidecar/shape 冲突返回具体包错误，不返回“设备未验证”。

### 9.4 QNN SDXL/DMD2

- 普通 SDXL 移除 1-step 限制。
- UNet worker 一次 context load 内完成全部采样。
- 原子写 latent/metadata，确认 worker 释放后启动 VAE worker。
- DMD2 从包配置或精确 fingerprint profile 读取少步参数。
- 不按文件名猜完整配置。
- 普通 SDXL 与 DMD2 分别建立基线。

### 9.5 MNN

- direct/module 必须回显真实 scheduler。
- 若使用共享 scheduler，MNN 只执行 text encoder、UNet、VAE graph。
- cfgScale、negativePrompt、steps、seed、backend、runner 全链路生效。
- 去掉“native 字段缺失视为通过”。
- 普通 SD1.5 默认 20 步。

### 9.6 stable-diffusion.cpp

- 不重写成熟 sampler。
- profile 映射到 runtime 支持名称。
- native 回显 component selection、sampler、flow/prediction、steps、CFG、seed。
- 不支持字段返回 UNSUPPORTED_GENERATION_PARAMETER。
- 新架构模型使用 metadata，不共享 SD1.5 默认。

### 9.7 ControlNet Canny

- task=CONTROL_IMAGE。
- UI/API 必须提供 control image。
- canny 阈值、尺寸、归一化进入 request/profile。
- down block 和 mid block residual 进入 UNet。
- scheduler/VAE 继承精确 base profile。
- 缺输入返回 CONCRETE_INPUT_MISSING。

### 9.8 Sana Edit

- task=IMAGE_EDIT。
- 明确 input image、mask/strength、prompt、尺寸。
- 编辑链未完成前保持待接入，但不因设备档案禁止下载和尝试。

## 10. UI 产品化

正式生图页增加：

- 正面/负面提示词。
- scheduler、steps、CFG、seed。
- 分辨率/宽高比。
- 速度/平衡/质量 preset。
- profile 默认来源。
- requested/resolved/nativeEffective 高级诊断。

规则：

- preset 改动全部可见。
- 用户改过字段标记为自定义。
- 切换模型提示不兼容字段，并提供“应用模型推荐值”。
- 不静默重置 prompt、negative prompt 或 seed。
- 模型卡分开显示包完整性、runtime、最近执行、画质基线和实验原因。
- 不得以未知芯片、本机未验证或缺档案禁用按钮。

统一进度：

1. PREPARING
2. TOKENIZING
3. TEXT_ENCODING
4. SAMPLING
5. DECODING
6. PUBLISHING
7. COMPLETED/FAILED/CANCELLED

取消点覆盖 tokenizer、text encoder、每个 scheduler step、ControlNet/UNet、VAE 和原子发布前。

## 11. Local API

认证 POST /v1/images/generations 和 /images/generations 支持：

    {
      "model": "model-id",
      "prompt": "a ceramic robot",
      "negative_prompt": "blur, low quality",
      "size": "512x512",
      "n": 1,
      "response_format": "b64_json",
      "seed": 20260717,
      "steps": 20,
      "cfg_scale": 7.0,
      "sampler": "dpmpp_2m"
    }

第一阶段 n 只支持 1。API 与 UI 必须共用 resolver/coordinator。

响应增加：

- request_id/model/created。
- profileId/revision/fingerprint。
- runtime/backend。
- scheduler/predictionType/steps/CFG/seed。
- tokenizerBackend/embeddingDiskDataType。
- vaeScalingLocation。
- nativeGenerationSequence/workerPid。
- graphExecution/nativeExecution/fallback。
- width/height/outputBytes/stageTrace/timing。

execution 全部来自 nativeEffective。

增加认证取消入口；busy 时返回 409 image_generation_busy。取消后清理 worker、journal、latent 和临时 PNG。

## 12. 进程隔离与崩溃恢复

状态机：

    IDLE
      -> PREPARING
      -> CONDITIONING
      -> SAMPLING
      -> DECODING
      -> PUBLISHING
      -> COMPLETED | FAILED | CANCELLED

Journal 原子记录：

- requestId、model/profile fingerprint。
- requested/resolved 摘要。
- phase、step、steps。
- native stage mask、worker PID、时间戳。
- latent/output 临时路径。
- terminal status/error。

应用启动或 worker 重连：

- 检测非终态 journal。
- 确认旧 PID。
- 清理未发布文件。
- 标记 INTERRUPTED，不伪造完成。
- 提供同 seed/profile 重新开始。

不恢复已死亡的 QNN/MNN context 中间状态；确定性重试从初始 latent 开始。

Worker 策略由 profile 指定：

- IN_PROCESS。
- DEDICATED_WORKER。
- SPLIT_UNET_VAE。
- SHARED_TEXT_UNET_VAE。

输出先写 requestId.tmp.png，完整质量门通过后原子 rename。

## 13. 测试与质量验收

### 13.1 快速预检

- profile schema/迁移/优先级/fingerprint/LKG。
- scheduler 每步 golden。
- PNDM 重复 timestep/curSample。
- Euler scaleModelInput。
- DDIM v_prediction。
- tokenizer ID parity。
- FP16/FP32 embedding。
- VAE scalingLocation。
- UI/API/worker round-trip。
- nativeEffective 不一致失败。
- journal/取消/Binder death。
- 通用设备开放策略。

### 13.2 外部质量对照

重叠模型使用同一：

- 模型文件 hash。
- prompt/negative prompt。
- seed、尺寸、scheduler、steps、CFG。

代表 profile 固定三类提示词：

1. 人像和皮肤细节。
2. 桌面物体和浅景深。
3. 室外场景、建筑和无文字要求。

每类三个 seed。对照产物、参数、token IDs 和中间统计只进入本地实验目录。

### 13.3 四层质量门

A. 文件完整性：

- PNG 可解码、尺寸/通道合理。
- 非全黑/白/单色/低动态/条纹。
- 现有 Assert-PngQuality 改名 Assert-PngIntegrity。

B. 数学一致性：

- token IDs、timesteps/sigmas、每步 latent、VAE scaling 和 nativeEffective。

C. 相对画质：

- prompt-image 语义。
- 清晰度/边缘能量。
- 高频和过锐化比例。
- 感知相似度。
- 饱和/裁剪像素比例。

阈值由外部或官方基线的三提示词 × 三 seed 分布校准，不能用单一高边缘指标把噪声判为清晰。

D. 人工 A/B：

- 原尺寸、隐藏来源。
- 检查主体、脸/手、纹理、背景、颜色、噪点、过锐化和提示词服从。
- MCA 不得明显劣于基线。

### 13.4 Elite 正式矩阵

每个 profile 代表模型：

- MainActivity UI 真生成。
- 认证 Local API 真生成。
- 两次 requestId、native sequence、输出 hash 独立。
- nativeExecution=true。
- QNN 还需 qnnGraphExecution=true、fallback=false。
- MainActivity resumed，进程存活，无 FATAL/ANR/native signal/OOM/LMKD。

每个推荐模型：

- 包下载/缓存完整性。
- Elite 至少一次任务匹配的真实输出。
- 重叠模型做外部质量对照；其他模型做官方基线对照。
- 待接入任务只有真实输入链完成后才能晋级。

代表设备通过后对所有兼容设备开放。

## 14. 分阶段执行

### Phase 0：冻结基线与来源

- 固定所有上游 commit/许可证。
- 固定 18 个推荐模型 ID、revision、文件名、size/SHA。
- 建立外部/官方参数和输出基线。
- 保存当前 8/20 步、FP32 embedding 和 SDXL 已知问题证据。
- 完成仓库名称零命中清理。

退出：来源可追溯、无外部基准代码进入产品树、每个模型有目标 profile。

### Phase 1：Profile 与迁移

- 新增数据结构、schema、resolver、validator。
- 扩展 ImageEngineBundleSpec 和安装 manifest。
- 兼容旧 manifest。
- requested/resolved/nativeEffective。
- fingerprint、pending/active/LKG。

退出：18 个模型解析到明确或通用兼容 profile，未知设备不影响可用性。

### Phase 2：共享 Scheduler

- Diffusers golden 脚本。
- DPM++ 2M、Euler、DDIM/v_prediction、标准 PNDM。
- QNN 接入。
- 修复 timestep/curSample/scaleModelInput/execution count。
- native 真回显。

退出：golden 全通过，sampler 不一致必失败，CyberRealistic 20 步结构稳定。

### Phase 3：Tokenizer/负面词/embedding

- 标准 tokenizer。
- Unicode token golden。
- negativePrompt 全链路。
- prompt weighting 第一阶段。
- legacy FP32 修复。

退出：token parity、MeinaMix 不再 dual slice、UI/API/native 正负词一致。

### Phase 4：VAE/包契约

- scalingLocation。
- 社区 host scaling 与 Qualcomm graph-internal scaling 分离。
- 读取 sidecar。
- graph shape/profile 校验。

退出：Gen5 无重复 scaling，SD2.1=DDIM/v_prediction，冲突返回具体包错误。

### Phase 5A：社区 SD1.5

顺序：CyberRealistic -> DreamShaper -> RealisticVision Hyper -> MeinaMix。

退出：代表模型完成三提示词 × 三 seed；四包完成 Elite 真实出图；状态与质量一致。

### Phase 5B：Qualcomm Gen5

顺序：SD1.5 -> SD2.1 -> ControlNet Canny。

退出：对齐官方参考契约；UI/API 双入口；ControlNet 输出受控制图约束。

### Phase 5C：SDXL/DMD2

顺序：DMD2 ALT -> SDXL Base -> CyberRealisticXL -> Animagine XL。

退出：普通 SDXL 非噪声；DMD2/Base profile 分离；UNet 全采样后释放再 VAE。

### Phase 5D：MNN/stable-diffusion.cpp

- MNN 参数和真实 sampler。
- MNN SD1.5 质量对照。
- stable-diffusion.cpp adapter。
- SD-Turbo、Z-Image、Flux、Qwen-Image、LongCat。
- Sana Edit。

退出：不跨 runtime 传无效参数；每个可下载模型完成 Elite 实际生成。

### Phase 6：UI/API/生命周期

- 参数面板和模型默认。
- API 扩展和真实 execution。
- 取消、journal、Binder death、原子输出、profile watchdog。

退出：UI/API 同 resolver；异常后 busy=false，下一请求可启动。

### Phase 7：质量验收

- 强化四层质量门。
- Elite 单机 UI/API 矩阵。
- 人工 A/B。
- 日志、内存、温控、进程存活。

退出：目标 profile 同档；推荐状态真实；无设备白名单。

### Phase 8：清理与 Git

- 删除 dual-slice、普通 SDXL 1-step、伪 sampler echo 和废弃分支。
- 更新参数、推荐页、上游许可证和回归摘要。
- 排除 APK、模型、临时检出和实验产物。
- 检查本地策略文件未 stage。
- 完成一次有边界最终 commit，不自动 push。

## 15. 并行执行

四槽并发采用主集成线 + 三条子任务线。

主线独占：

- LocalImageProvider.kt
- MainViewModel.kt
- qnn_native_bridge.cpp
- ModelScopeClient.kt
- 构建、安装、Elite UI/API 验收

子任务 A：Scheduler

- 新 scheduler 文件。
- golden 脚本/fixtures/native 单测。
- 不直接改 qnn_native_bridge.cpp。

子任务 B：Tokenizer/embedding/VAE

- tokenizers 接入。
- token ID golden。
- FP32 converter。
- VAE contract。
- 不直接改 LocalImageProvider.kt。

子任务 C：UI/API/worker

- 新 UI 参数组件。
- api/local 解析与测试。
- worker protocol/journal/watchdog 新文件。
- 避免同时修改 MainViewModel。

规则：

- 子任务优先新增文件，主线集中接线。
- 每个 Phase 末合并。
- 同批只构建一次 APK。
- 下载与 host golden 并行。
- 单台 Elite 的正式出图串行，避免设备争抢降低效率。

## 16. 预计代码落点

新增：

- app/src/main/java/com/muyuchat/mca/ImageExecutionProfile.kt
- app/src/main/java/com/muyuchat/mca/ImageExecutionProfileResolver.kt
- app/src/main/java/com/muyuchat/mca/ImageExecutionJournal.kt
- app/src/main/java/com/muyuchat/mca/ImageGenerationContract.kt
- core/native/src/main/cpp/diffusion_scheduler.hpp
- core/native/src/main/cpp/diffusion_scheduler.cpp
- core/native/src/main/cpp/image_conditioning.hpp
- core/native/src/main/cpp/image_conditioning.cpp
- core/native/src/main/cpp/image_vae_contract.hpp
- core/native/src/test/cpp/diffusion_scheduler_test.cpp
- tools/reference/generate-diffusers-scheduler-golden.py
- docs/IMAGE_PIPELINE_UPSTREAM_LICENSES.md

改造：

- core/download/src/main/java/com/muyuchat/core/download/ModelScopeTypes.kt
- core/download/src/main/java/com/muyuchat/core/download/ModelScopeClient.kt
- app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt
- app/src/main/java/com/muyuchat/mca/LocalImageBundleContract.kt
- app/src/main/java/com/muyuchat/mca/LocalImageWorkerProtocol.kt
- app/src/main/java/com/muyuchat/mca/LocalImageWorkerService.kt
- app/src/main/java/com/muyuchat/mca/LocalImageWorkerClient.kt
- app/src/main/java/com/muyuchat/mca/SdxlTwoPhaseCoordinator.kt
- app/src/main/java/com/muyuchat/mca/SdxlImagePhaseWorkerService.kt
- app/src/main/java/com/muyuchat/mca/MainViewModel.kt
- api/local/src/main/java/com/muyuchat/api/local/McaLoopbackServer.kt
- core/native/src/main/cpp/mnn_native_engine.cpp
- core/native/src/main/cpp/qnn_native_bridge.cpp
- core/native/src/main/cpp/qnn_sdxl_isolated_phases.hpp
- NativeMnnDiffusionBridge.kt
- NativeQnnBridge.kt

## 17. 风险

| 风险 | 应对 |
|---|---|
| 外部非商业代码进入产品树 | 外部检出保持仓库外；零名称扫描；只从许可友好上游落代码 |
| Scheduler 再次“能跑但数学错误” | 每步 golden、timestep trace、nativeEffective |
| QNN context/runtime 不兼容 | context metadata 选 transport；通用尝试；具体 runtime 错误 |
| SDXL 内存峰值 | UNet/VAE 分阶段、一次 load 内多步、journal |
| tokenizer 增加包体 | 裁剪 backend，仅 arm64，固定依赖 |
| FP32 转换 OOM | mmap/read-window，无完整双副本 |
| preset 覆盖用户参数 | requested/resolved 分离，显式空值和覆盖标记 |
| 指标把噪声判清晰 | 语义、频域、裁剪比例、人工 A/B 联合门 |
| 全模型矩阵过慢 | profile 代表完整矩阵，兄弟模型包级真机出图 |
| profile 阻断未知设备 | 自动策略测试；设备只排序不准入 |

## 18. 完成定义

1. 18 个推荐条目都有明确 execution profile 或准确任务 profile。
2. 社区重叠模型达到外部质量基准同一视觉档位。
3. Qualcomm Gen5 达到 AI Hub Models 官方参考质量档位。
4. MNN 与 stable-diffusion.cpp 达到各自上游参考质量档位。
5. 普通 SDXL 不再 1-step；DMD2 与普通 SDXL 分离。
6. SD2.1 正确执行 DDIM/v_prediction。
7. Gen5 VAE 不重复 scaling。
8. legacy FP32 token_emb 不按 dual slice 读取。
9. UI/API 支持并真实执行 negative prompt、scheduler、steps、CFG、seed。
10. 取消、超时、worker 崩溃和重启后状态可恢复，busy 归零。
11. 质量验收不只依赖 PNG 存在或 graphExecute。
12. 一台 Elite 的生产 UI + 认证 Local API 通过后，对全部兼容设备开放。
13. 外部基准源码、身份、URL、产物、临时检出不进入产品代码和可发布文档。
14. AGENTS.md 和本地策略测试不进入 commit。
15. app、core、api、feature、docs 的外部基准名称扫描为零命中。
16. 最终 commit 仅包含计划内代码、测试和可发布文档，且不自动 push。

## 19. 2026-07-17 实施与正式验收记录

### 19.1 构建与自动化验证

- `core/native` 使用 ARM64 与 QAIRT 2.45 完整构建通过，包含 MNN、QNN、标准 tokenizer 和共享 Scheduler。
- `core/sd-native` 的 67 个编译单元与最终共享库链接通过；最初的链接失败确认为仓库旧构建缓存占满磁盘，不是源码错误。
- `:app:compileDebugKotlin` 通过。
- ImageExecutionProfile、JSON/sidecar、native contract、journal、MNN、stable-diffusion.cpp、worker、SDXL、readiness 与本地通用设备开放守卫的定向 JVM 测试通过。
- `:api:local:testDebugUnitTest` 全量通过；ModelScope 推荐下载回归通过。
- 共享 Scheduler 的 MSVC `/W4 /WX` golden 测试通过，覆盖 Euler、DDIM、PNDM/PLMS 与 DPM++ 2M。
- ARM64 + QAIRT Debug APK 完整 assemble 通过，并且只覆盖安装到代表性 Elite 设备；未卸载、未清数据、未操作辅助设备。

### 19.2 正式 UI 发现并修复的 AIDL 打包缺陷

首次从生产 `MainActivity -> 图片 -> 生成图片` 发起请求时，独立 `:local_image` 进程真实暴露 `ILocalImageWorker$Stub` 未进入 APK 的崩溃。该问题不会被 JVM 测试或内部 smoke 发现。

修复后构建具备两层保护：

1. 8 组 worker AIDL 接口及其 `$Stub.class` 被声明为 Javac 具体输出；任一文件缺失都会使增量 Javac 失效并自动重跑。
2. 每个 variant 在 APK 打包前执行 worker AIDL 完整性门；重跑后仍缺类会直接拒绝打包。

验证时主动删除 `ILocalImageWorker$Stub.class`，普通增量构建自动恢复；最终 APK 的 DEX 审计确认 8 组接口、Stub、Proxy 和 Default 共 32 个定义均存在。

### 19.3 生产 MainActivity UI 证据

- 正式路径：生产 `MainActivity -> 历史抽屉 -> 图片 -> 本地生图 -> 生成图片`。
- requestId：`ui-img-4ab2f28d-7ee0-4451-95e1-8aadab1f0794`。
- native requestId：`qnn-htp-1784240003805-ae158575-4f69-4d98-8026-6d985b6e3c29`。
- worker PID：`13074`；native generation sequence：`18760805528338`。
- profile：`generic.compat.qnn_htp.sd15`；Scheduler：PNDM/PLMS；20 steps；21 timetable entries；42 次真实 UNet 执行；VAE 执行 1 次。
- `nativeExecution=true`、`npuActive=true`、`qnnGraphExecution=true`、`fallback=false`，最终阶段为 `semantic_generation_passed`。
- 输出：512x512 PNG，486,476 bytes，SHA-256 `50A4C440CA46DEA86A37E88B1D20F9ABF78AE27DB1B47FC3F5046E4060ECB423`。
- 视觉检查：主体、轮廓、材质、杯子与桌面光影清晰可辨，不是单色、噪声图、横纹图或整体模糊图。

### 19.4 认证 Local API 证据

- API 通过生产 UI 的“本机调用”开关启用；开放端口保持关闭；认证 Key 未输出到日志或文档。
- endpoint：`POST /v1/images/generations`；HTTP 200；`response_format=b64_json`。
- requestId：`img-e453c02c-52c1-4302-99bb-525a32430ade`，与 UI requestId 独立。
- native requestId：`qnn-htp-1784240343263-bd8abe3a-be60-4ee2-b24c-c42e602b94e5`，与 UI native requestId 独立。
- worker PID：`13074`；native generation sequence：`18783039075090`，与 UI sequence 独立。
- 固定 seed `24681357` 在 nativeEffective 中原样生效；20 steps；21 timetable entries；42 次真实 UNet 执行。
- `nativeExecution=true`、`npuActive=true`、`qnnGraphExecution=true`、`fallback=false`，最终阶段为 `semantic_generation_passed`。
- 输出：512x512 PNG，440,798 bytes，SHA-256 `734A25D9719FA8E03A7BDDE428DC7C6443AB6CFCF86B55DB1C4EEBACB9880D4D`。
- 视觉检查：灯笼主体、木桌、椅背、发光区域和背景景深清晰可辨；与 UI 输出内容和哈希均不同，没有复用 UI 结果。
- 本轮 API 日志中 FATAL、ANR 和 OOM 均为零。

### 19.5 开放结论与边界

- 代表性 ARM64 Elite 设备已用生产 UI 与认证 Local API 完成独立真实生成，因此该 profile 的能力对全部兼容设备开放；未知设备不得被白名单或认证状态阻止。
- 该结论不伪造未下载模型包的包级完整性。后续若某个具体包出现 graph、tokenizer、shape 或 runtime 失败，只对该包增加最窄兼容修复，不恢复设备准入名单。
- 两张验收 PNG、APK、原始 logcat 和临时构建产物不进入 commit。
