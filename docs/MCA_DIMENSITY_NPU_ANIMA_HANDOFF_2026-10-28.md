# MCA 天玑 NPU / Anima 交接文档（2026-10-28 晚 · 根因分析版）

> 本文是接手继续做的状态账本 + 根因分析 + 唯一决定性实验设计。
> **不是 READY 声明。** 上一份：`docs/MCA_DIMENSITY_NPU_ANIMA_HANDOFF_2026-10-27.md`（§12–§14 是本轮增量）。

## 1. 一句话现状

- **设备 CPU 路径已经 PASS**：MT6899 上 4 步 3.6 分钟出图，最终 latent 与 PC 参考相对误差 5.7e-6，图像与 PC 参考 PSNR 39.75 dB。
- **NPU 路径数值 FAIL**：28/28 块报告 `npu=true nonCpu=1 fully=1`，但出图纯黑（PNG 2,227 B）。
- **根因已定位到模型格式层面（本地取证，未在设备复现）**：图包的 block 是 **weights-only int8 + 逐通道 DEQUANTIZE**，而 LiteRT 的 MediaTek 编译器插件字符串里明确存在 `Int8 is not supported.`、`Failed to set general per-channel quant params`、`Block-wise quantization is unsupported for weights dequantization`；同时 block 用了 `TRANSPOSE`/`SLICE`/`GELU`，插件里只有 `Failed to add GELU operation`，没有 TRANSPOSE/SLICE 的对应实现串。
- **下一步只需要一次决定性实验**（区分"int8 反量化"与"算子覆盖"两类原因），不要继续让测试者盲测。

## 2. 基线与不可变约束

- 仓库 `E:\MAC`，分支 `codex/enterprise-refactor`，HEAD `68b6eef1e678`。本轮**没有修改仓库生产代码**，只新增/更新 `docs/` 与 `artifacts/architecture/2026-10-26-dimensity-anima/`。
- 子模块 llama.cpp `4ceb1719`、stable-diffusion.cpp `16b3846a`、tokenizers-cpp `acbdc5a` 未动。
- `AGENTS.md` 与 `UniversalDeviceAdmissionPolicyTest.kt` 仍是本地排除项，禁止提交/发布。
- 唯一 owner/state writer 不变；没有新增第二套 ImageGenerationOwner、队列、worker、图库链。
- 设备型号只做推荐/记录，不做准入名单。
- 本机无天玑设备（只有 Qualcomm device-b / device-a），所有天玑验证只能远程交给测试者；测试者无电脑，只能装 APK + 分享文件。

## 3. 外置工作目录（不在仓库内）

| 路径 | 内容 |
|---|---|
| `D:\anima-spike\pack\device\` | 设备图包 37 文件（28×int8 block + prelude/final fp32 + `vae_decoder.onnx` + mods/context + manifest） |
| `D:\anima-spike\convert\` | 转换/验证脚本；本轮新增 `inspect_tflite.py`、`inspect_quant.py`、`make_latent.py` |
| `D:\anima-spike\out\` | PC 参考产物（`step4B.png` 71,755 B / SHA `d7310a21…`、`step4B.png.latent.npy`） |
| `D:\anima-spike\staging\anima-pack.azip` | 2,094,940,161 B 内嵌包（已从工程移出，避免误构建 2.1 GB APK） |
| `D:\anima-spike\dist\` | 1.0.0 单文件包等历史包 |
| `C:\mca-mtk-spike\mediatek-npu-smoke\` | 测试 App 工程（非 git），当前 1.0.3 |
| `C:\mca-mtk-spike\mtk-npu-cprobe\` | 独立 C API 探针（本轮加了 `--fd-size` 参数） |

## 4. 当前交付物

| 版本 | 文件 | 大小 | SHA-256 |
|---|---|---|---|
| 1.0.3（当前） | `C:\mca-mtk-spike\mediatek-npu-smoke\dist\mediatek-npu-smoke-1.0.3.apk` | 21,100,252 B | `2c4f9349865d6dcc31aea67f0b726958b7b32f3798586298cb878e00e83ef091` |
| 1.0.2 | `dist\mediatek-npu-smoke-1.0.2.apk` | 21,083,268 B | `29ea594f671332ae37323cf7eacb38c239f9dcb0a16f58cb50b2d817e34ef068` |

- 1.0.2：修复 `LiteRtCreateModelFromFd(fd, 0, **0**)`（size=0 必返回 `kLiteRtStatusErrorInvalidArgument`）→ 改为 `fstat` 真实长度；新增 native 错误串、逐块进度、输出尺寸校验、prelude NPU/CPU 对照、VAE 异常、证据实时落盘、内置 `latent_seed42.f32`。
- 1.0.3：新增 **CPU 模式按钮**、每步 `latent`/`hidden[0]`/`hidden[27]`/`final`/`image` 的 mean/std/min/max/**nan/inf**、最终 latent 文件 + 分享按钮、升级后自动恢复上一轮 PNG/latent。
- 三个版本签名证书一致（`ad116923…`），可覆盖安装，`filesDir/anima-pack`（2.09 GB 图包）保留。
- 测试说明：`TESTER_MESSAGE-1.0.2.md`、`TESTER_MESSAGE-1.0.3.md`。

## 5. 真机证据（MT6899 / HONOR SER-AN00 / API 36）

### 5.1 CPU 模式（1.0.3）= PASS

```
backend=cpu_only  steps=4 cfg=6.0 seed=42  latentSource=asset_seed42
blocksNpu=0 blocksCpu=28     （每块 npu=false nonCpu=0 fully=1，compile 0.4–0.7s，run 0.45–0.6s）
vaeBackend=cpu vaeRunMs=36837
image mean=0.945 std=0.143 min=-1.0 max=1.0 nan=0 inf=0
pngBytes=79714  pngSha256=f9ae10cb731979706a993855c407b5946522dbb0b630a2a56ae0007e43556bb8
latentFileSha256=24b478212904a10a77e420d519c887ebe1e0c631757d88612af775470abdb884
totalElapsedMs=215979  status=OK
```

与 PC 参考对比（本机计算）：

| 指标 | 设备 CPU vs PC |
|---|---|
| latent mean abs diff | 4.35e-6 |
| latent max abs diff | 2.04e-4 |
| latent relative mean abs | **5.68e-6** |
| NaN | 0 |
| 图像（QQ JPEG vs PC PNG） | MAE 0.91/255，PSNR 39.75 dB |

→ 图包、prelude、28 block、final、CFG/Euler、unpatchify、VAE、PNG 编码在设备上**全部正确**。

### 5.2 NPU 模式（1.0.2）= 数值 FAIL

```
28/28 block: npu=true nonCpu=1 fully=1，compile 2.0–2.7s，run 0.20–0.29s
step 耗时 84.5/85.4/85.7/72.2s，totalElapsedMs=356099，vaeBackend=cpu 27473ms
prelude NPU vs CPU: maxAbs 9.42e-4, relMean 2.82e-4   （fp32 FC 正常）
pngBytes=2227  pngSha256=e0101726d2928004b415af46d26a6b08a3884f46ce720c42f2adc52cab751648
```

- 出图为**纯黑**（回传 JPEG mean=1.0、std=0.0；纯黑 512×512 PNG 约 843 B，纯灰约 1,878 B，2,227 B 同量级）。
- 黑色与 `toByte(NaN)=0` 一致，说明 latent/VAE 输入退化（NaN/Inf 或常数）。
- **NPU 还比 CPU 慢**（356s vs 216s）：每块每步重新编译，112 次 × ~2.4s ≈ 268s。
- 对照：CPU 模式同一批块 `nonCpu=0`，所以 `nonCpu=1` 确实代表非 CPU 执行 —— NPU 真的在跑，只是算错。

## 6. 根因分析（本地取证）

### 6.1 block 的量化结构（`inspect_quant.py`，ai-edge-litert 2.3.0）

`block00.tflite`（64,329,952 B）：

- IO 全部 float32：输入 `[1,1024,2048]`、`[1,512,1024]`、3×`[1,6144]`，输出 `[1,1024,2048]`；
- **10 个 int8 权重张量 + 10 个 DEQUANTIZE + 10 个 FULLY_CONNECTED**，即 **weights-only int8、激活 float32 的混合精度图**；
- 权重 scale 是**逐通道**（长度 2048/8192），zero_point 全 0；
- scale 数值健康：`zero_scales 0`、`tiny_scales 0`、`nan_scales 0`，范围 2.35e-5 ~ 3.24e-3；
- 算子统计：`MUL 32, RESHAPE 27, ADD 18, SLICE 13, MEAN 10, DEQUANTIZE 10, FULLY_CONNECTED 10, TRANSPOSE 10, RSQRT 7, BATCH_MATMUL 4, SUB 3, CONCATENATION 2, SOFTMAX 2, GELU 1, DELEGATE 1`。

prelude/final 是纯 fp32（无 DEQUANTIZE），prelude 在 NPU 上数值正确 —— **差异点正好是 int8/DEQUANTIZE 与 block 独有算子**。

### 6.2 LiteRT MediaTek 编译器插件的能力边界（`llvm-strings libLiteRtCompilerPlugin_MediaTek.so`）

复现命令：

```
D:/model/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strings.exe \
  C:/mca-mtk-spike/mtk-npu-cprobe/lib/arm64-v8a/libLiteRtCompilerPlugin_MediaTek.so | grep -iE "int8|quant|gelu|unsupported|partition"
```

命中的关键串：

- `Int8 is not supported.`
- `Failed to set general per-channel quant params`
- `Block-wise quantization is unsupported for weights dequantization` / `Doesn't support BlockWise quantize now`
- `Dynamic or non-positive dimensions are not supported for weights dequantization`
- `Failed to add GELU operation`
- 有实现/报错串的算子：`NEURON_ADD / SUB / MUL / MEAN / RSQRT / SOFTMAX / BATCH_MATMUL / CONCAT / RESHAPE / FULLY_CONNECTED / RESIZE_* / TRANSPOSE_CONV_2D`
- **没有** `NEURON_TRANSPOSE`、`NEURON_SLICE`、`NEURON_DEQUANTIZE` 相关串；另有 `LiteRtCompilerPluginPartition`、`Failed to create NeuronCompilation`、`Fail to get neuron api version`。

### 6.3 推理链与置信度

| 结论 | 依据 | 置信度 |
|---|---|---|
| NPU 确实在执行（不是 CPU 冒充） | `nonCpu=1`（CPU 模式为 0）、run 0.22s vs CPU 0.5s、prelude NPU 数值正确 | 高（实测） |
| 设备端管线/图包/采样器/VAE 正确 | CPU 模式 latent 与 PC 相对误差 5.7e-6、图像 PSNR 39.75 dB | 高（实测） |
| 黑色来自退化 latent（NaN/Inf 或常数） | 纯黑图像 + `toByte(NaN)=0`；正常错误值会出纹理而非纯色 | 中高（推断，缺 1.0.3 NPU 的 nan 统计） |
| 首要嫌疑：int8 weights-only + 逐通道 DEQUANTIZE 不被插件支持 | 插件串 `Int8 is not supported.` / `Failed to set general per-channel quant params`；block 与 prelude 的唯一结构差异 | 中（强指示，未在设备复现） |
| 次要嫌疑：TRANSPOSE / SLICE / GELU 覆盖不全 | 插件里没有 TRANSPOSE/SLICE 串，GELU 有失败串；但字符串不完整不能证明不支持 | 低-中 |
| `fullyAccelerated=1` 不能作为数值正确证据 | 本项目已两次遇到（tiny fixture maxAbsDiff 223 / 本次纯黑） | 高（实测） |

### 6.4 已排除

- 图包损坏/导入不全：`packSource=files`，37 文件齐全，CPU 出图正确。
- 采样器/patch 顺序/VAE/PNG：与 PC 逐项核对且 CPU 结果与 PC 一致。
- 初始噪声不同：1.0.3 起内置 PC 的 seed-42 latent（SHA `23c72e90…`）。
- fd 加载：1.0.2 已修（`size=0` → `fstat` 真实长度），并在 Qualcomm 设备上用同一 `libLiteRt.so` 复现过失败/成功。
- 缓冲区尺寸/形状：prelude 与 block 的 IO 字节数与 Kotlin 完全一致（8,388,608 / 2,097,152 / 3×24,576）。

## 7. 唯一决定性实验（一次跑完，不再盲测）

目标：一次区分 §6.3 的两个嫌疑。

1. 用 `export_pack.py` 的同一份权重，导出 **block00 的 fp32 版（无 DEQUANTIZE，约 242 MB）** 和 **block00 的 fp16 版（约 121 MB）**；
2. 在 PC 上先用 CPU 验证这两个探针模型与 int8 block00 的输出一致性（同输入，mean abs diff 阈值 1e-2）；
3. 把探针模型内嵌进 APK（`assets/probe/block00_fp32.tflite` 等），在 App 里加一个 **"Block numeric probe"**：
   - 用 step 0 的真实输入（prelude 输出 + context + mods，全部固定/来自图包），
   - 分别跑 NPU 与 CPU，输出 `maxAbsDiff / relMeanAbsDiff / nan / inf`，
   - 若 NPU 探针输出有限但差异大，再跑一个"只保留 FC+MUL/ADD 的子图"以判断是否 TRANSPOSE/SLICE/GELU；
4. 一次运行给出：int8 是不是元凶、fp16 是否可行、算子覆盖是否够用；
5. 只有该实验通过（NPU 与 CPU 相对误差 < 1e-2 且无 NaN）才继续做全量 fp16 图包（约 3.4 GB）与产品化。

## 8. 必须一并处理的问题（与根因无关但影响可用性）

1. **每步重编译**：`runGraph/runGraphPair` 每次 run 后 `release`，导致 112 次编译。要么跨步缓存 28 个已编译模型（需实测内存），要么按步复用句柄。当前 NPU 因此比 CPU 慢。
2. **单文件内嵌包仍不可用**：`ZipSource` 传的是 `/proc/self/fd/<apk fd>`，`fstat` 得到的是 APK 整体大小；模型区域必须传 `nativeEntryInfo()[3]`（dataOffset）与 `[1]`（uncompressedSize）。
3. **测试者无 logcat**：所有诊断必须进 evidence 文本（已做），不要再要求 adb。
4. **NPU 数值门槛**：`provesNpuExecution` 仍要求分区数/语义 PNG/输出 SHA；LiteRT 2.3.0 不导出分区数，`nonCpu=1` 不能替代数值闭环。
5. **禁止**把 CPU 结果标成天玑 NPU；禁止设备黑名单；禁止新增第二套 owner/队列。

## 9. 阶段状态与 READY

| 阶段 | 状态 |
|---|---|
| Phase 0/1 基线与 SDK 门禁 | 完成（无天玑 SDK/设备，走公开 LiteRT 路径） |
| Phase 2 单图 NPU spike | PASS（tiny fixture `nonCpu=true`，历史） |
| Phase 3 三张 Anima 图 | 图包已转换并**在设备 CPU 上验证等价**；NPU 侧数值 FAIL |
| Phase 4 语义流水线 + PNG | **设备 CPU PASS**；NPU 出图纯黑 |
| Phase 5 独立 MediaTek runner | 未开始（等 NPU 数值闭环） |
| Phase 6 bundle/profile/runtime 枚举 | 未开始（不得先加"天玑 NPU 模型"卡片） |
| Phase 7 复用唯一执行链 | 未开始 |
| Phase 8 双入口/设备验收 | 未开始 |
| **READY** | **NOT_MET** |

## 10. 证据索引

`E:\MAC\artifacts\architecture\2026-10-26-dimensity-anima\`

- `2026-10-28-prelude-fd-root-cause.md`、`2026-10-28-prelude-fd-probe.txt`、`2026-10-28-block-final-cpu.txt`
- `2026-10-28-1.0.2-build.txt`、`2026-10-28-1.0.3-build.txt`
- `2026-10-28-npu-run-28blocks-evidence.txt`（SHA `f5b755a0…`）、`2026-10-28-npu-28block-run-analysis.md`
- `2026-10-28-cpu-path-pass.md`、`2026-10-28-1.0.3-cpu-final-latent.f32`（SHA `24b47821…`）
- `2026-10-28-1.0.3-cpu-evidence-screenshot.jpg`、`2026-10-28-1.0.3-cpu-cat.jpg`、`2026-10-28-1.0.2-npu-black.jpg`
- 历史：`phase0-1-report.json`、`phase2-*`、`phase3-anima-pack-0.6.0.json`

## 11. 接手时请继续做、不要重做的事

1. 不要重做 Phase 0/1、不要重新验证 fd/size（已闭环）。
2. 不要重新怀疑设备端管线正确性 —— CPU 与 PC 的 latent 差 5.7e-6，已经证明。
3. 不要再让测试者跑整包 NPU 来"试试看"；先做 §7 的单块探针实验。
4. 不要为了"看起来支持"而加 `MEDIATEK_NEURON` runtime 枚举、推荐页模型卡或 `npuActive=true`。
5. 需要设备数据时，一次实验只回答一个问题，并且所有结论必须写 PASS/FAIL/NOT_RUN/BLOCKED + 证据路径。

## 12. 回退

- 仓库侧：本轮只新增文档/证据，未改生产代码；删除新增文件即可回退。
- 外置侧：`C:\mca-mtk-spike`、`D:\anima-spike` 可整体删除；历史 APK 在 §4/§3 路径。
- 未执行 `git reset --hard` / `git clean`，未改用户设备设置，未卸载任何设备上的应用。
