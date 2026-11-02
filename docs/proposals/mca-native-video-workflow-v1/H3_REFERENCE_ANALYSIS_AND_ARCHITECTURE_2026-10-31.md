# MiniMax H3 / FastH3 对标分析与 MCA 接入架构

日期：2026-10-31
参考项目：Nightmare Mobile `757ce68e0deb52983b7698560337b20bd7d76b07`
源码对照：HappyYzy stable-diffusion.cpp `a63954dc794f6daade32c407da1a9c4bc2d73cf0`，ggml `f8758628270072da26ad44baf5e189d4695d9800`

## 1. 结论

Nightmare 的 H3 接入不是把 Wan 的模型名、分辨率或采样步数替换成 H3。它复用了 HappyYzy 已有的 MiniMax H3 音视频扩散实现，再增加 FastH3 的 4-step 曲线表读取、HTP 数值修正、Qwen3-VL 4B 到 H3 5120 维条件空间的 ClipProj、音频回调 ABI，以及 Android 侧的磁盘帧管线、AAC 混流、预算告警和可恢复分段。

版本边界：Nightmare NPU workflow 默认基于 LocalDream `v3.0.0-alpha.4`，其 sdcpp gitlink 为 `35a3d48b23581d2be7c3ad95e92f34662bb5f305`，ggml 为 `f8758628270072da26ad44baf5e189d4695d9800`；HappyYzy `a63954d` 比该 sdcpp pin 多一个提交。报告用 a639 源码确认 H3 基础实现，并用 Nightmare 固定 commit 的 patch/workflow 确认产品增量，不能把二者说成同一 revision。Nightmare release 声明的 engine ZIP SHA 为 `1d47358c1477a1aa74b4d84688d9a50a5c8efa5646f891b4037fea2cb9824353`；本次未下载该 ZIP 重算，也不声称 MCA 当前打包库包含这些 patch。

MCA 当前已经具备部分 Wan/HTP 基础和 V2 视频请求、取消、LoRA、TAE、后端字段，但生产链仍是 Wan-only。现有 `t5xxl_path` 不能承载 H3 的 Qwen3-VL、`clipproj` 和音频 VAE，native adapter 也会释放音频结果；媒体验证器和发布器明确拒绝音轨。更关键的是，当前 MCA 构建输入的 dirty stable-diffusion.cpp HEAD `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0` 中没有 `MiniMaxH3Runner`/`MINIMAX_H3` 实现；H3 核心只在 HappyYzy `a63954d` 参考源码中确认。因此当前状态应描述为：**Wan 已有可复用基础；MCA 的 H3 产品能力按验收口径为 0%，引擎移植和产品接入都尚未验收**。

对标的最短正确路线是：先固定并构建 H3 engine baseline，建立 CPU 正确性门，再做 HTP 正确性和性能优化，最后接入 MCA 的统一 owner/gate/发布事务。不能先把 H3 塞进现有 Wan manifest，也不能把 shell 侧 HTP 结果当成 App UID 已通过。

## 2. 证据范围与可复核位置

Nightmare 的完整源码树、应用层源码和 patch 快照保存在：

`E:\MAC\build\evidence\h3-reference-audit-20261031\`

关键参考文件：

- `app-ref/app/src/main/java/com/abrah/nightmare/npu/H3Node.kt`
- `app-ref/app/src/main/java/com/abrah/nightmare/npu/WanVideoNode.kt`
- `app-ref/app/src/main/java/com/abrah/nightmare/npu/WanParts.kt`
- `app-ref/app/src/main/java/com/abrah/nightmare/npu/AudioMux.kt`
- `app-ref/app/src/main/java/com/abrah/nightmare/npu/Backend.kt`
- `app-ref/app/src/main/java/com/abrah/nightmare/ModelCatalog.kt`
- `engine-ref/backend-patches/dit/069-hexagon-binary-wide-rows.ggml.patch`
- `engine-ref/backend-patches/dit/070-minimax-h3-audio-vae-on-te-backend.sdcpp.patch`
- `engine-ref/backend-patches/dit/071-minimax-h3-fused-partial-rope.sdcpp.patch`
- `engine-ref/backend-patches/dit/072-minimax-h3-curve-knots.sdcpp.patch`
- `engine-ref/backend-patches/dit/073-minimax-h3-htp-matmul-scale.sdcpp.patch`
- `engine-ref/backend-patches/dit/074-minimax-h3-vae-fused-rope.sdcpp.patch`
- `engine-ref/backend-patches/dit/075-minimax-h3-vae-flash-attn.sdcpp.patch`
- `engine-ref/backend-patches/dit/076-minimax-h3-clipproj.sdcpp.patch`
- `engine-ref/backend-patches/dit/078-h3-audio-abi110.patch`
- `engine-ref/backend-patches/dit/079-minimax-h3-vae-encode-one-frame.sdcpp.patch`

参考项目固定链接：

- [H3Node](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/757ce68e0deb52983b7698560337b20bd7d76b07/app/src/main/java/com/abrah/nightmare/npu/H3Node.kt)
- [WanVideoNode](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/757ce68e0deb52983b7698560337b20bd7d76b07/app/src/main/java/com/abrah/nightmare/npu/WanVideoNode.kt)
- [AudioMux](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/757ce68e0deb52983b7698560337b20bd7d76b07/app/src/main/java/com/abrah/nightmare/npu/AudioMux.kt)
- [H3 ClipProj patch](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/757ce68e0deb52983b7698560337b20bd7d76b07/backend-patches/dit/076-minimax-h3-clipproj.sdcpp.patch)
- [H3 build workflow](https://github.com/AbrahamPaulJ/nightmare-mobile/blob/757ce68e0deb52983b7698560337b20bd7d76b07/.github/workflows/dit-engine.yml)

本报告没有把维护者 patch 注释中的速度数字当作 MCA 实测；本轮也没有下载完整权重或运行设备负载。

## 3. Nightmare 的真实实现路径

### 3.1 模型角色不是 Wan 三件套

Nightmare 的 H3 catalog 声明五类资产：

| 角色 | Nightmare catalog 资产与大小 | 作用 |
|---|---|---|
| H3 DiT | FastH3 dense Q4_0 GGUF，11,583,540,608 B | 4-step 音视频联合扩散模型 |
| H3 video VAE | Q8_0 GGUF，2,936,962,688 B | 视频 latent 解码/首帧编码 |
| H3 audio VAE | fp32 safetensors，605,254,808 B | 32 kHz stereo PCM 解码 |
| Qwen3-VL 4B | Q4_0，2,375,774,112 B | 文本/视觉条件编码 |
| H3 ClipProj | 26,256,128 B，纯 Linear | 2560 维 encoder hidden → H3 5120 维条件 |

Qwen3-VL 4B 不是把 H3 原生 Qwen3-VL-32B 的权重直接替换掉。原生 H3 取 Qwen3-VL 第 50 层、跳过 final LM norm；4B 必须读取 ClipProj 的 tap、统计量、sink token 和矩阵元数据。参考项目还通过 `partsFrom = krea2_turbo` 复用模型分片，减少重复下载。

Nightmare 虽然在 `H3Node` 中复用 `WanVideoNode.runClip` 和 Wan 的尺寸/seed/结果处理，但 H3 仍有独立 `H3` recipe、backend type、帧网格、prompt builder、音频侧车和 segment-aware 路径；其 catalog 把 backend family 存成 `Family.WAN` 是参考项目内部兼容选择，不应照搬到 MCA 的领域类型里。

下表的 revision/SHA 来自本次查询到的 HF metadata，属于审计时观察到的资产版本，不是 Nightmare app catalog 自身固定的全部 pin，也不是本次下载后逐字节重算。Nightmare catalog 对这些文件使用 `resolve/main` URL；MCA 应在 manifest 里固定具体 revision 和哈希，再允许用户下载：

| 文件 | 仓库 revision | 大小 | LFS SHA-256 |
|---|---|---:|---|
| `fasth3_dense_v1_euler4_q4_0.gguf` | `AbrahamPJ/fasth3-dense-v1-q4_0-gguf@ce9e34a7ae25def5dd14c1bf65ec144fb7f8c6d5` | 11,583,540,608 B | `4cbaece051c8783490f7f97e79c528ccb3dcbf92ed901a3fac33deeee44018a8` |
| `minimax_h3_video_vae_Q8_0.gguf` | `ZawShiShawn/Gestura-MiniMax-H3-VAE-GGUF@ccf393e652b3e9abe649bd3a173a175a7341cc15` | 2,936,962,688 B | `6df6782b09181d2b24c49c136573804d43ba261cb8cc475237fa8294f51f9075` |
| `minimax_h3_audio_vae_fp32.safetensors` | `Comfy-Org/MiniMax-H3@912b6e38b2092ec4d2e86d24183e0d59ea059d48` | 605,254,808 B | `8e505d95dd1561d47abd43d4238fd40d9bb1ae9e147ed0a4cba778d76ae4db48` |
| `mmh3-4b-ClipProj-v3.1.safetensors` | `NicoLab28/ClipProj-MiniMax-H3@2ebdbcdc27a29a9607efdb221a9afcb9a0cdd808` | 26,256,128 B | `0184e5c8d666a131962506d21949c2d8a8c6f33445b7b5e347e9a7e0a5baa819` |
| `Qwen_Qwen3-VL-4B-Instruct-Q4_0.gguf` | catalog URL is `bartowski/Qwen_Qwen3-VL-4B-Instruct-GGUF/resolve/main/...` | 2,375,774,112 B | revision not fixed in the catalog; G0 must pin it before download |

H3 Community License 有地域及用途条款，相关模型、量化转换仓库和 ClipProj 的许可需分别核对；Nightmare 应用整体许可证不能替代模型许可。FastH3 Preview 已知蒸馏目标为 T2AV；原生 H3 支持 I2VA 不代表该 4-step checkpoint 的 I2VA 画质有同等保证。H3 prompt 还使用 `subject_definitions`、`summary`、`retention_analysis`、带时长的 `detailed_description`、`overall_soundscape`、`non_diegetic_music` 结构；分段提示词替换镜头描述并保留声音/角色上下文，MCA 应将 prompt recipe 和实际 segment prompt 纳入输入身份/hash。

### 3.2 HappyYzy 已有 H3 核心，Nightmare 做的是增量适配

原始 HappyYzy a639 已经包含 `MiniMaxH3Runner`、H3 video VAE、audio VAE、联合 audio/video latent 和 H3 conditioner。其关键配置是 video latent 24 通道、audio latent 32 通道、text dim 5120、50 层、56 heads、head dim 128。

Nightmare 的关键 patch：

| patch | 必须保留的行为 |
|---|---|
| 069 | 按 VTCM 行宽判断 H3 AdaLN/音频长行是否能上 HTP，不满足时安全走 CPU |
| 070 | audio VAE 与 TE/CPU backend 绑定；维护者记录音频 VAE 直接上 HTP 会 abort |
| 071 | H3 partial RoPE 的 rotate-half 权重重排，匹配 fused qknorm_rope |
| 072 | FastH3 的 9 个非均匀 curve knots 插值，禁止按 1025 uniform table 读取 |
| 073 | HTP 上 condition/out projection 输入乘 1/32、输出除 1/32，避免 FP16 溢出 |
| 074 | video VAE partial RoPE 的 fused 路径和权重重排 |
| 075 | 单独开启 VAE flash attention；diffusion flash attention 不会自动覆盖 VAE |
| 076 | 4B Qwen3-VL 的 ClipProj，拒绝带 `mlp.*` 的错误投影格式 |
| 078 | ABI110 音频回调和 `audio_vae_path`，先复制音频再释放 native buffer |
| 079 | I2V 首帧使用 HTP 支持的 causal 3D conv、reflect pad/crop，避免错误的 2D 简化 |

072 和 076 是正确性前置；069、070、073 是 HTP 正确性前置；071、074、075、079 是性能或 I2V 优化，必须逐项 A/B 并保留回退路径。

### 3.3 H3 是联合音视频扩散，不是生成视频后另跑 TTS

视频 latent 和 audio latent 在同一 DiT 采样中打包；video shift=12，audio shift=3，音频约 40 latent frames/s。采样后 audio latent 送 audio VAE，得到 32 kHz、双声道、交错 float32 PCM。参考 wrapper 用 NMA1 sidecar 保存 PCM 元数据和数据，Android 再编码 AAC 并与 H264 视频混流。

因此 MCA 必须让音频成为 native 结果的一等公民。`withAudio=true` 时，音频解码失败不能静默当成完整成功；至少要返回 `audioStatus=generated|unavailable|failed` 和错误原因。

### 3.4 H3 几何与采样协议独立于 Wan

H3 约束是：24 fps、宽高 32 对齐、最少 5 帧、合法帧数 `17k+5`，典型序列为 22、39、56、73、90、107、124 帧。latent frames 为 `frames<=5 ? 2 : ((frames-5)/17)*5+2`。不能复用 Wan 的 `4k+1`、121 帧或 shift=3。

Nightmare 默认 832×480、56 帧，界面约 2.3 秒；最长 124 帧实际约 5.17 秒。产品显示时应以 `generatedFrames/fps` 为准，不能把标签 5 秒当实际帧数证明。

### 3.5 Android 预算与分段是业务层机制

参考项目估算 H3 token：

`tokens = (width / 32) * (height / 32) * latentFrames`

这也解释用户观察到的“20 万像素约 5 秒、35 万像素约 3 秒”：面积和时长需一起看。按 H3 latent 近似，token 数正比于 `像素面积 × latentFrames`；5 秒任务的 latent 时间长度约为 3 秒任务的 1.65 倍，因此两组经验值的总 token 量接近。面积单独不能定时长，长宽比、32 对齐、完整帧格、audio graph、prompt token、backend budget 和可用 RAM 都会改变结果。MCA 应以同一 recipe 的模型/设备探针校准 estimator，并展示估算区间和告警原因。

预算器还把常驻权重和每 token 的经验图内存成本叠加。代码注释记录 v79 设备约 4.5 GiB 图预算，较低 HTP arch 约 2.75 GiB；H3 token 上限按设备测量约 8800/7100，另看 RAM share 和当次 backend 状态。它们用于估算最长长度和给 warning，执行路径仍允许用户继续尝试；这些数字是 Nightmare 的测量数据，不是芯片规格或 MCA 的硬限制。Catalog 上 `minArch=75/minRAM=12GB` 用于模型推荐/可见性处理不符合 MCA 的 universal device admission 约束，MCA 只能把硬件识别用于推荐和资源调优，unknown device 仍可进入 generic native load/graph smoke。

较长任务被拆成最多 10 段。下一段以上一段最后一张 RGB 帧作为 I2V 首帧，使用 `seed + partIndex`，丢弃下一段重复的第 0 帧；音频同步丢弃对应一帧时长的 samples。每段完成后写 marker，低内存设备可重启 backend，恢复从完整段边界继续。十段意味着最多十次分别采样后拼接；若每段接近最长 124 帧，总时长约可达 52 秒，但这不代表一次 H3 sampling 支持 50 秒连续生成。分段可能累积主体漂移、动作/音频接缝和语义断裂，需要独立评审。参考输出先把 RGB 帧逐帧写 NMV1，再由 Android 编码，避免一次性 Bitmap 数组；这不是 native sampler 的完整低内存 streaming。

## 4. MCA 当前状态与真实差距

当前生产源码的关键证据索引如下（行号以本轮审计工作区为准，后续改动后应重新核对）：

| 证据 | 位置 |
|---|---|
| 节点注册只有 Wan | `core/video-contract/src/main/java/com/muyuchat/core/video/contract/VideoNodeTypes.kt:95-105` |
| manifest 只有 DIT/UMT5/VAE | `app/src/main/java/com/muyuchat/mca/video/model/VideoModelManifest.kt:14-24,63-71` |
| workflow compiler 固定 Wan | `app/src/main/java/com/muyuchat/mca/video/workflow/VideoWorkflowCompiler.kt:119-133` |
| worker adapter 固定 manifest/encoder/VAE | `app/src/main/java/com/muyuchat/mca/video/worker/LocalVideoWorkerAdapter.kt:143-160` |
| API 无 H3 typed asset/audio/ref/segment 字段，且固定 Wan 帧格 | `api/local/src/main/java/com/muyuchat/api/local/VideoGenerationApiContract.kt:29-42,76-80` |
| V2 native request 把 `te_path` 当 T5 | `core/sd-native/src/main/cpp/mca/wan/mca_wan_api_v2.h:166-193` |
| V2 adapter 将 `te_path` 设为 T5 并释放 audio | `core/sd-native/src/main/cpp/mca/wan/mca_wan_api_v2.h:166-217`; `core/sd-native/src/main/cpp/mca/wan/mca_wan_adapter_v2.cpp:643,758-760` |
| V2 native bridge 已把 PPM 逐帧回读成整段 `sd_image_t[]`，随后 worker 编码 H264；仍是 Wan-only、无音频结果契约 | `core/sd-native/src/main/cpp/sd_video_generate.cpp:311-329,1053-1055`; `app/src/main/java/com/muyuchat/mca/video/worker/VideoWorkerPipeline.kt:135-192` |
| MP4 verifier/publisher 仍拒绝或硬编码无音频 | `app/src/main/java/com/muyuchat/mca/video/media/VideoMp4Verifier.kt:77-78,142`; `VideoMediaPublisher.kt:149` |

这些位置说明“接入 H3”是跨 manifest、compiler、native ABI、媒体和恢复协议的变更，不能通过增加一个 `modelId` 或复用 `te_path` 完成。

### 4.1 已有能力

- v1-27 已有 Wan V2 请求、I2V、TAE、LoRA、取消、进度和 backend 字段。
- HTP partial broadcast 拒绝修复已在源码/实验中证明能降低错误，但修复后的 engine、打包库和证据目录 hash 尚未绑定为一个可发布产物。
- 当前 MCA sd.cpp 子模块 HEAD `16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0` 未检出 `MiniMaxH3Runner`/`MINIMAX_H3` 符号；H3 核心目前只在参考的 HappyYzy a639 源码树中确认。
- MCA 已有 Wan 的 RGB frame sink→PPM→Android H264 链；H3 需要在同一 owner/gate 下增加 PCM/AAC 与 H3 graph 语义，不能说已有音视频完整 sink，也不能说完全没有帧级落盘能力。
- 现有 owner/gate/发布事务可作为 H3 的共同生命周期边界。

状态口径需按最新 v1-27 证据修正：LoRA 已经在正确 typed path 下真实应用（1099/1099），旧“LoRA无效”结论作废；TAE 的竖纹来自 HTP latent 污染，真实 VAE CPU latent 干净，不能据此判定 TAE 权重本身有缺陷；已有 `480×480` 的 shell 采样数据不能写成标准 `832×480` 通过。以上都属于 Wan 进度，不构成 H3 完成度。

### 4.2 生产链仍然 Wan-only

以下边界必须先改：

| MCA 位置 | 当前问题 |
|---|---|
| `VideoNodeTypes.kt` | registry 只有 Wan 闭集，没有 H3 节点族 |
| `VideoModelManifest.kt` | 只要求 DIT/UMT5/VAE 三角色，不能表达 QwenVL/ClipProj/audio VAE |
| `VideoWorkflowCompiler.kt`、`LocalVideoWorkerAdapter.kt` | 固定 Wan nodes 和 manifest resolve |
| `VideoGenerationApiContract.kt` | 没有 model family、LLM、ClipProj、audio、reference、segment plan 字段，帧校验固定 `4k+1` |
| `mca_wan_api_v2.h` / `mca_wan_adapter_v2.cpp` | `te_path` 被当作 t5xxl；没有 H3 LLM/vision/ClipProj/audioVAE；native audio 结果在 adapter 中被释放 |
| `VideoMp4Verifier.kt` / `VideoMediaPublisher.kt` | 有音轨直接失败，发布器硬编码 `hasAudio=false` |
| `VideoWorkerPipeline.kt` | 只调用 `VideoMp4Encoder`，effective 参数和时序规则是 Wan 语义 |
| 现有分段 | 没有父 run、子 segment、marker、边界音频裁剪和幂等恢复协议 |

现有 V2 不能称为 H3-ready；shell 侧 HTP 通过也不能替代 App UID 的真实 QNN/HTP session canary。既有 MCA 设备证据记录过 app UID 打开 FastRPC 节点被拒、shell 可用；现有 preflight 只检查设备节点可访问性，没有创建 DSP session。因此需要先验证受支持的签名 PD / FastRPC transport 能否由 production app 创建 session；若失败，H3 CPU fallback 可做功能实现，但不能计作对标 HTP 通过。不能靠芯片/机型白名单把入口藏起来。

## 5. 目标 MCA 架构

### 5.1 Family-aware 模型和 recipe

新增不可变的 `ModelFamily`：`WAN_TI2V`、`MINIMAX_H3`。每个 family 绑定独立 recipe：

```text
H3Recipe
  geometry: width/height multiple=32, fps=24, frames=17k+5, minFrames=5
  sampler: Euler4, cfg=1, videoShift=12, audioShift=3, fastCurveKnots=v1
  assets: dit, qwen3vl, clipproj, videoVae, audioVae
  conditioning: encoderKind=QWEN3VL4B_CLIPPROJ, refMode=I2V_LAST_FRAME
  output: videoTrack=H264, audioTrack=AAC-LC, audioRate=32000, channels=2
```

Wan recipe 保持原字段、默认值和 API fixture，不通过“兼容判断”悄悄改变旧请求。

### 5.2 Manifest 和资产角色

把资产校验统一成 `AssetRole`，但不复用错误的 `t5xxl_path`：

```text
H3_DIT_Q4_0
H3_QWEN3VL4B_Q4_0
H3_CLIPPROJ_LINEAR
H3_VIDEO_VAE_Q8_0
H3_AUDIO_VAE_FP32
```

manifest 必须保存来源 revision、文件大小、SHA-256、许可证、格式版本和变换版本。ClipProj loader 要校验 offset/size 乘法不溢出、finite、std 非零、tap 在 encoder 层范围内、输出宽度为 5120，并拒绝 `mlp.*` 残差网络格式。

### 5.3 Versioned native ABI

保留 V2 兼容入口，新建 V3 或 family-specific ABI，固定 `struct_size` 和 `abi_version`：

```text
request:
  model_family
  dit_path
  llm_path / llm_vision_path
  clipproj_path
  video_vae_path / audio_vae_path
  encoder_kind
  prompt / negative_prompt / image
  width / height / frames / fps / seed
  steps / cfg / sampler / h3_shifts / curve_version
  with_audio / segment_id / cancel_token / output_sink

callbacks/results:
  progress(step,total,phase,actual_backend)
  video_frame(index, pts_us, rgb_or_path)
  audio_pcm(path_or_chunk, rate, channels, sample_frames)
  complete(video_frames, audio_status, audio_metadata, timings)
  error(code, phase, recoverable)
```

native adapter 直接调用 HappyYzy H3 C API；不要照搬 Nightmare 的 `DIT_MODEL_WAN` 命名。音频 callback 内必须复制或落盘后才能释放 native buffer。`actual_backend` 必须来自真实执行阶段，不能直接回显请求字段。

### 5.4 统一执行和媒体所有权

`VideoGenerationOwner` 仍是唯一状态写入者，`NativeMediaExecutionGate` 仍是唯一并发门。H3 只替换 recipe、native request 和媒体产物，不新建第二套取消/发布 owner。

执行顺序：

1. 校验 manifest、几何和资产完整性。
2. 预算计算并显示 advisory warning。
3. native 生成视频帧和 PCM sidecar。
4. 帧 sink 逐帧送 H264 encoder，PCM 送 AAC encoder。
5. 按同一时间轴 mux MP4，验证帧数、PTS、音频采样数和时长。
6. 通过单一 publish transaction 写入 `VideoMediaRecord`，包含真实 `hasAudio` 和音频元数据。

`withAudio=true` 且 audio status 不是 generated 时，按产品策略返回明确失败或“无音频完成”，不能生成带假成功状态的静音文件。

### 5.5 预算和十段计划

预算器输入为 family、宽高、合法帧数、latent frames、audio samples、资产常驻大小、backend 和当前可用内存。它只用于推荐最长时长、提示和分段建议；未知芯片只能走 generic recipe，不能被当作不可用。

`SegmentPlan` 采用父 run + 子 segment：保存 `partIndex`、合法 H3 frames、输入首帧 hash、输出帧 hash、音频 sample 起止、seed、状态和 marker。取消停止当前段，不自动启动下一段；恢复只从最后一个完整 marker 重启。拼接时删除视觉重复帧并同步删除 `sampleRate / fps` 对应音频 samples，按有理数时间基累积边界以避免每段整数取整造成音画漂移，统一 PTS。

## 6. 分阶段实施方案

### 6.1 实施文件写集与回归验证

以下是每阶段预期写集，不代表这些文件已改动：

| 阶段 | 主要写集 | 验收重点 |
|---|---|---|
| G0 | `third_party/stable-diffusion.cpp`、`third_party/ggml` 的明确 patch queue；engine build/provenance 脚本；`build/evidence/h3-*` | 固定源码/补丁/产物 hash；保住 5D GGUF、tokenizer Android 和 HTP partial-broadcast 本地修复；先不改 app |
| G1 | sdcpp H3 conditioner/model builders/video pipeline；CLI 或窄 H3 smoke harness | FastH3 knots、ClipProj tensors、帧格、finite latent/PCM、CPU T2V质量；Wan smoke 不回归 |
| G2 | ggml-hexagon 行宽保护、H3 backend routing/linear scale 补丁 | CPU/HTP 同输入 latent 误差、NaN/Inf、audio VAE CPU、可解释 fallback；shell 结论单列 |
| G3 | 新的 versioned native video ABI/JNI/adapter；`VideoNodeTypes.kt`、manifest、compiler、worker；音频 encoder/verifier/publisher/API | Wan V2 golden 不变；H3 typed mapping、AAC mux、真实 `hasAudio`；含签名 PD / FastRPC 合法接线的 App UID session canary |
| G4 | H3 VAE/输出 sink 和 `VideoWorkerPipeline` bounded consumer | 峰值 RSS、磁盘、温度；071/074/075/079逐项A/B；取消后的临时文件清理 |
| G5 | `SegmentPlan`、run store schema migration、worker resume、segment prompt UI | 2/3/5/10段、marker/hash、重启恢复、幂等、边界音频 samples/PTS、取消 |
| G6 | UI node catalog/inspector、Local API request/response、media projection | MainActivity/API 共用同一 Owner/Gate；旧 API fixture 不变；MP4/AAC播放和 ffprobe |

G3 ABI 文件名/目录待核对当前 JNI target 后确定；这是建议命名，不是现存文件。新增通用数据类型进入既有平台无关 contract，native implementation 不泄漏到 contract。既有回归入口包括 `VideoWorkflowCompilerTest`、`VideoModelAssetVerifierTest`、`VideoWorkerCoreTest`、`VideoMp4EncoderTest`、`VideoGenerationOwnerTest`、`NativeMediaExecutionGateConcurrencyTest`、`VideoGenerationApiContractTest`。纯 JVM/fake native 测试不能替代 engine smoke 或真机 App UID HTP 验收。

### G0：引擎移植基线和产物绑定，时间待差异评审后定

先对比 MCA `16b3846a`（含本地修改）与 HappyYzy `a63954d`、Nightmare shipping 基线 LocalDream `35a3d48b` 的 H3 依赖差异，选定一条可复现移植路径；明确保留 MCA 5D GGUF 堆叠、tokenizer Android 修复和现有 HTP partial-broadcast 修正，不能整仓覆盖。随后固定 sdcpp/ggml revision、逐项 patch manifest、Hexagon skeleton revision，以及每个 ABI/header/so 的 SHA-256；确认 v1-27 修复确实进入同一 engine 和 APK。DoD 是能复建的 H3 engine 与产物清单。此 merge/port 工时目前未估，因为 16b 与 a639 的源码差异尚未做逐文件兼容审计。

### G1：CPU H3 最小正确性，2–4 人日

先在有足够内存的 host CPU 上证明数值和功能正确性，再做目标 ARM64 手机的 CPU smoke（若保留该执行方式）；两者是不同验收门，host PASS 不代表 Android 内存/时延可用。接入 FastH3、video VAE、audio VAE、Qwen3VL4B、ClipProj；先跑 Nightmare UI 可选择的最短合法长度 22 帧（原生最低几何边界 5 帧只在引擎明确允许时作为单元探针），步骤为 Euler4/CFG1。验证 condition/latent/PCM 全部 finite、输出帧数合法、音频 32 kHz stereo、T2V 主体可辨。先集成 072、076，不做 HTP 优化；I2V 另作为独立质量门，因为 FastH3 4-step 的蒸馏目标是 T2AV。

### G2：shell HTP 正确性，3–5 人日

按 069→070→073 顺序启用；每步做同 seed CPU/HTP latent 相对误差、NaN/Inf、音频可解码和取消测试。HTP audio VAE 默认 CPU。这里的 shell 结果只证明 engine，不证明 App UID 权限。

### G3：App native 与音视频单段，3–5 人日

新增 V3 ABI、H3 manifest/compiler/worker 路由和真实 App UID HTP session canary。音频 sidecar→AAC→H264 mux 完整跑通；补 `VideoMp4Verifier`、publisher、projection 和 API response 的 audio 字段。所有 native phase 都写 actual backend 和 error phase。

### G4：性能和内存，2–4 人日

逐项 A/B 071、074、075、079；记录峰值 RSS、图预算、耗时、温度和产物 hash。H3 VAE 的空间/时间 tile 逻辑独立于 Wan sink，不要把 `temporal_tile_frames` 直接套进 H3。输出帧使用 bounded sink，避免 Bitmap 全量驻留。

### G5：预算告警和 2→10 段恢复，3–5 人日

先做 2 段，再做 3、5、10 段。验证上一段末帧作为下一段 I2V、去重帧、音频 sample 裁剪、marker 恢复、取消幂等、设备重启后继续。告警只提示，不按设备型号隐藏入口。

### G6：UI/API 和发布验收，2–3 人日

保留 Wan 默认请求体逐字节兼容；H3 显式选择 model family。UI 展示真实生成秒数、段数、音频状态和预算告警。验收 production MainActivity、Local API、取消/恢复、MP4+AAC 播放和至少一台 ARM64 代表机。

以上是工程规划粗估，不是完成时间承诺；在 H3 engine baseline 已建立、权重/SDK可用且设备条件允许后，G1–G6 约需 15–28 人日。engine merge/port 另计，需 G0 差异审计后估时。App UID 权限、权重下载或 HTP graph 失败可能形成外部阻塞，不能用代码工时掩盖。

## 7. 验收矩阵

| 门 | 通过条件 | 当前状态 |
|---|---|---|
| H3 engine baseline | 将 HappyYzy H3 核心并入 MCA 实际构建输入，同时保住 5D GGUF/tokenizer 本地补丁 | NOT STARTED |
| H3 CPU | 最小合法帧、主体、finite、PCM | NOT RUN |
| H3 HTP shell | 069/070/073 后 CPU 对照和音频解码 | NOT RUN |
| App HTP | 真实 UID 创建 session，失败可解释回落 | NOT RUN |
| 音视频 | AAC/H264 同一 PTS，verifier/publisher 真实 hasAudio | NOT RUN |
| H3 几何 | 17k+5、24fps、32 对齐 | 未接入 |
| 预算 | warning/advisory，不是白名单拒绝 | 未接入 |
| 分段 | 2→10 段、去重、音频边界、marker 恢复 | 未接入 |
| Wan 回归 | 旧 API、默认请求和 Wan 4k+1 全部不变 | 需在每个阶段复测 |

## 8. 不应宣称的结论

- 参考项目的“8gen3 以上”“20 万像素 5 秒”是其设备和经验模型，不是 MCA 的普适硬上限。
- H3 4B encoder 不能简化成普通 Qwen 图片 encoder；没有 ClipProj 就不能声称对齐。
- FastH3 4-step 不能按均匀 sigma 表替代 curve knots。
- `generate_video` 完成后再 callback 不等于 native 低峰值流式；仍需测量 graph peak 和输出驻留。
- 维护者 patch 注释中的速度、质量和“强于 Wan 14B”不是本项目验收证据。
- 现有 MCA 只有 shell HTP 或 V2 视频成功，不代表 H3 已接入、更不代表 App 内 HTP 和带声音视频已通过。

## 9. 下一步执行顺序

先完成 G0 的 revision/patch/binary 绑定，然后在隔离 engine 目录做 G1 CPU 最小 H3；G1 通过后接 G2 的 HTP 数值门。G2 通过且 App UID canary 有真实结果，再改 MCA V3 ABI 和音视频媒体链。最后才接预算告警、十段计划和 UI/API。每个阶段保留原始日志、模型指纹、设备状态和产物 hash，失败时停在对应门，不把下游未验证项包装成“已对标”。
