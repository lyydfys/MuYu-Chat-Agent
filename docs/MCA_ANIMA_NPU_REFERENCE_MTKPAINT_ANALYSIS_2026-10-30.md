# 参考实现拆解：`com.anima.npu`（玑绘 / MTK Paint）如何在联发科 NPU 上跑通 Anima 生图

日期：2026-10-30 · 状态：**逆向分析**（无反编译源码，结论来自 APK 内符号/字符串/资产 + 公开产物体检）
样本：`D:\下载\base.apk.1`，50.6 MB，`package=com.anima.npu`，`versionName=0.9.9`，`versionCode=360`，label `MTK Paint / 玑绘`，minSdk 31 / targetSdk 35，`arm64-v8a`

## 1. 它是什么（先纠正一个前提）

- **没有公开源码。** APK 与三处官方入口里没有任何 GitHub/Gitee 链接，只有自建站 `https://mtknpu.zh.kg`、模型服务器 `https://36.138.82.128:9443/api/v1`、QQ 群。App 自己的文案写着"这个 App 是一个人在业余做的，模型移植、量化、缓存分发都自己扛"。
- **公开的是"产物"**：APK 本体 + 每颗 SoC 的**预编译模型包**，托管在 HuggingFace / ModelScope：
  - `https://huggingface.co/ledisbsb/anima-npu-dimensity9400`（HF，含 `models/`、`npucache/`）
  - `https://www.modelscope.cn/models/ledi123456789/anima-npu-dimensity9400`（ModelScope，含 `aot/<soc>/`、`aot-lab/`、`cacheup/`）
- 底座是**开源/官方**的：LiteRT（Apache-2.0）+ 联发科 NeuroPilot SDK（需向 MediaTek 申请）。所以更准确的说法是"**用开源底座 + 自建 AOT 流水线做出来的闭源 App**"。

## 2. 实现路径（五层，逐层都有证据）

### 第 1 层：自己从源码构建 LiteRT 与 MediaTek 插件（不是用 Maven AAR）

APK 里有**两套** LiteRT：

| 位置 | libLiteRt.so | CompilerPlugin | Dispatch |
|---|---|---|---|
| `lib/arm64-v8a/` | 5,508,376 | **513,296** | 318,848 |
| `assets/litert/` | 5,508,376（同一份，SHA 相同） | **9,813,072** | **3,782,064** |

- 大插件未 strip，调试路径直接写着源码树：`D:/anima-assets/research-tools/litert-v2.2.0/litert/vendors/mediatek/compiler/...`
  ⇒ **从 LiteRT v2.2.0 源码 Bazel 构建的 vendor plugin**（我们用的是 Maven `litert-npu-runtime-mediatek:2.3.0` 里的 429 KB 精简插件）。
- 大插件**导出了完整 MediaTek options C API**（我们那版只有 Get/Set 少数几个）：
  `LrtCreateMediatekOptionsFromToml` / `LrtSetMediatekOptionsAotCompilationOptions` /
  `LrtSetMediatekOptionsMediatekDlaDir` / `LrtSetMediatekOptionsNeronSDKVersionType` /
  `LrtSetMediatekOptionsPerformanceMode` / `LrtSetMediatekOptionsOptimizationHint` /
  `LrtSetMediatekOptionsL1CacheOptimizations` / `LrtSetMediatekOptionsDisableDlaDirRemoval` / `LrtGetOpaqueMediatekOptionsData`
- 16 项 SoC 白名单里**含 mt6993**（与 2.3.0 那份一致，所以白名单不是差异点）。
- 插件里可见 `LegalizeStridedSliceOp`、`LegalizeTransposeOp`、`LegalizeSoftmaxOp`、`LegalizeGeluOp`、`LegalizeRmsNormOp`、`LegalizeQuantizeOp`、`UnpackDenseInt4IntoInt8` 等——legalizer 覆盖面比 2.3.0 那份更全。

### 第 2 层：**AOT 预编译**（核心，不是设备端 JIT）

- ModelScope `aot/mt6993/` 里是逐文件的 **`.dla`**（Neuron DLA 字节码）+ **`stub`**（host 代码）+ `manifest.json` / `manifest_np9.json`（Neuron runtime 9）。
- 单图 `dla` ≈ **192–449 MB**，`stub` ≈ 0.6–1.4 MB；VAE 解码器 `vaedec_*.dla` ≈ **26 MB**。
- `manifest_np9.json` 每条记录形如：
  `"ditc1c_w8_w16s4": { "aot": 0, "stub": 1388800, "dla": 448422845, "sha256": "..." }`
- 命名即规格：`w8_w16`=权重 int8/int16 混合、`s4`=4 步蒸馏、`L1024/L2304/L3072/L4032`=token 长度档、`ditc1c/ditc2c/dva1..3/dvb1..2`=**把 28 块 DiT 切成 4～7 个 chunk 图**。
- 引擎里对应 `npug::Engine::tryAotGraph`、`setRouting`、`startPrefetch/joinPrefetch`、`runStreaming`、`dropWeightPages`、`demoteNative`，以及 AOT 参数串：
  `--opt 0 --mem-opt 3 --num-mdla 1 --no-parallel --opt-footprint`、`--num-mdla 4`、`--opt 0 --num-mdla 4`。

### 第 3 层：分块 + 内存预算（他们踩过 OOM 的实锤）

`aot/mt6993/manifest_np9.json` 的 note 原文：

> "2026-09-23 追加七图版 L=3072（dva1/2/3+dvb1/2+ditc1c/2c）。依据：现场 5 台 9500、16 个同起跑内存桶，**七图比四图装载省 1.0~2.2GB**，四图装完普遍只剩 ~1000MB 被系统杀。四图 12 张原样保留，可随时切回。｜2026-09-27 加 1MP（七图 L4032）+ 1MP 五个比例的 VAE（O-134）。"

⇒ **把图切小**是他们在真机上换来稳定出图的关键手段，不是优化项。

### 第 4 层：自研引擎 `libanima.so`（738 KB，`npug::` + `anima::` 命名空间）

直接调 LiteRT C API（不走 Java）：

- 打开/运行：`LiteRtCreateEnvironment` → `LiteRtCreateOptions` → `LiteRtSetOptionsHardwareAccelerators` → `LiteRtCreateOpaqueOptions`+`LiteRtAddOpaqueOptions`（把 MediaTek options 挂上去）→ `LiteRtCreateModelFromFile` → `LiteRtCreateCompiledModel` → `LiteRtCreateManagedTensorBufferFromRequirements` → `LiteRtRunCompiledModel`。
- **可观测性**：`LiteRtUseSinkLogger` + `LiteRtGetSinkLoggerSize/Message`（和我们 1.0.5 里自己做的 sink logger 同一思路）。
- 路由/流式：`npug::Engine::{build, run, runRouted, runStreaming, ensureCompiled, cacheToken, execReport, setDropRestoreCopy, takeDroppedTotalMb, memAvailableKb, vmHwmKb}`，还有 `Balloon`（按可用内存膨胀/让位）。
- 计时埋在 `/route_trace.csv`：`create_ms,input_ms,output_ms,prefetch_ms,compute_ms,destroy_ms,unload_ms,route_ms`。
- 诊断入口：`nativeProbe`、`nativeProbeDla`、`nativeProbeCompile`、`nativeDiagnose`、`nativeRuntimeInfo`、`nativeNeuronMajor`。
- 文本链路：`qwenEncode`（Qwen3 端上跑）+ `t5Encode` + `TextNpu::build/encodeQwen/encodeAdapter` + CLIP 资产（`vocab.json`/`merges.txt`）。
- 生成链路：`nativeSetGrid/SetCanvas/SetInpaintBox/SetCtx/LoadMods/LoadText/LoadTextNpu/DitReady/SetVaeScales/Generate`；另有一套 SDXL 入口（`nativeSdxl*`）。

### 第 5 层：分发（按 SoC 下发预编译产物）

- App 内置 + 云端合并的 SoC 表 `models/soc_index.json`（原文）：
  `mt6897 天玑8300/8350 verified`、`mt6899 天玑8400 verified`、`mt6991 天玑9400/9400+ verified(tiers: t1024,t2304,t3072w16,t3072w8,vae)`、`mt6993 天玑9500 verified`、`mt8799 天玑9400+平板 verified`、`mt6985/mt6989 空`。
- 注意：`tiers` 只有 mt6991 有——即**只有 D9400 用了"设备端编译缓存"档**，其余 SoC 直接吃 AOT `.dla`。
- `npucache/t1024|t2304|t3072w16|t3072w8|vae/<HASH>1|<HASH>2` + `manifest.txt` = 编译缓存条目（成对文件 = 字节码 + host）。
- App 自更新：`latest.json`（当前 0.9.9 / 360；HF 上公开的 `latest.json` 还是 0.2.4/139，说明还有自建更新源）。

## 3. 模型与权重来源

| 组件 | 来源 | 证据 |
|---|---|---|
| 基础模型 | **Anima 2B**（CircleStone Labs × 社区，Civitai `models/2458426/anima`，turbo/distilled 变体） | App 文案 "8 步蒸馏 · 2B DiT"；`models/miaomiao8/*` 的 `ditc*`/`dva*` 图名 |
| DiT 权重 | 作者自转：int8/int16 混合量化（`w8_w16`），AOT 编译进 `.dla` | `manifest_np9.json` 的 `dla` 尺寸 + `w8_w16` 命名 |
| 文本编码器 | **Qwen3**（端上跑，`text.bin`/`ctx.bin`）+ CLIP-L/CLIP-G（SDXL 模式） | `assets/clip/*`、`assets/tok.bin`、`qwenEncode/t5Encode` 符号 |
| VAE | 按输出分辨率各编一份（`vaedec_64/96/128x72/128x96/144x112/112x144/168x96/96x168/126x128`），另有 TAESD 解码选项 | `aot/mt6993/vaedec_*.dla` 每份 26.2 MB |
| 预烘参数 | `rope_{32x32,36x64,48x48,64x36,64x48}.bin`、`modsq16s4_<tokens>_<step>.bin`、`modsq8f_3072_<step>.bin` | HF `models/miaomiao8/` 文件列表 |
| 下载源 | HF `ledisbsb/anima-npu-dimensity9400`、ModelScope `ledi123456789/anima-npu-dimensity9400`、自建 `36.138.82.128:9178/models` | dex 字符串 |
| 单个 SoC 产物体量 | mt6993：DiT 部分 ~4×448 MB + 1MP 组 ~7×256–321 MB + VAE ~9×26 MB ≈ **4–5 GB** | `aot/mt6993` 文件尺寸 |

## 4. 与我们当前方案的关键差异（为什么他们跑通、我们黑图）

| 维度 | 他们 | 我们（1.0.6 现状） |
|---|---|---|
| 编译时机 | **PC 上 AOT**，设备只加载 `.dla`+`stub` | 设备端 JIT（每块 2–5 s，112 次编译 ≈ 6 分钟） |
| 精度 | 编译期决定 `w8`/`w16` 混合量化，直接烘进字节码 | 运行期 fp32 图 + 插件 fp16 松弛 → 单块 7e-4 误差 → 28 层发散 |
| 图粒度 | DiT 切成 **4–7 个 chunk**，实测省 1.0–2.2 GB | 28 个独立 block 图，逐个 acquire/release |
| 插件 | 自建 LiteRT **v2.2.0** + vendor plugin（9.8 MB，全 options API） | Maven LiteRT **2.3.0** + 429 KB 精简插件（options API 未导出） |
| 参数烘焙 | RoPE + 每步 AdaLN mods 全部预烘（`modsq16s4_*.bin`） | mods 预烘（fp16），RoPE 烘进图内 |
| VAE | VAE 解码也上 NPU（按分辨率 AOT） | VAE 走 ONNX CPU（27–37 s） |
| 内存 | `Balloon`/`dropWeightPages`/`prefetch`/`route` 全套 | 只靠逐图释放，无预算控制 |
| 验证 | 每颗 SoC 真机探针 + `vMax` 门禁 + 分桶内存实测 | 单块数值门禁 + 逐块 CPU 回退 |

## 5. 我们能直接复用的四件事（按性价比）

1. **AOT 取代 JIT**：这是"跑通"的分水岭。用 Neuron SDK（Linux + Ubuntu 22.04 + Bazel）在 PC 上把每张图编成 `.dla`+`stub`，设备侧只设 `MediatekDlaDir` 加载。**但需要 MediaTek NeuroPilot SDK 的下载权限**（当前我们没有，是硬阻塞）。
2. **分块策略**：即使暂不做 AOT，把 28 块按 4–7 段合并成"chunk 图"能显著降峰值内存；他们实测省 1–2 GB，这条与 AOT 无关、可独立验证。
3. **编译期量化**：`w8_w16` 混合量化在编译期完成，才能既保精度又吃 NPU；运行期 weight-only int8 + DEQUANTIZE（我们现在这套）在 NPU 上既慢（255 ms vs 71 ms）又没有精度收益。
4. **自建 LiteRT + vendor plugin**：他们能设 `AotCompilationOptions`/`optimization_hint`/`L1Cache` 等全部旋钮，是因为从源码构建了 vendor plugin；Maven AAR 那份把 API 裁掉了。若要复现，需从 LiteRT 源码（对应 2.x 末版）构建。

## 6. 诚实边界

- 本文**没有源码级证据**：结论来自未 strip 的 `libanima.so` 符号、插件调试路径、`.dla`/manifest 结构与公开产物体检；`npug::` 内部算法（routing/prefetch 的具体策略）只能从符号与参数串推断。
- 他们**没有公开源码**，也没有公开 AOT 编译脚本；`aot-lab/`、`tmp/`、`cacheup/` 只是产物目录。
- 他们的 SoC 表把 `mt6899` 标为 **verified**（j 天玑 8400）——正是我们最初黑图的那台机器的芯片；说明"同一颗芯片可以跑通"，差异在流水线而不在芯片。
- 我们的 `mt6993`（天玑 9500）在他们的表里也是 `verified`，但**没有 npucache tiers**（走 AOT 直载）。
