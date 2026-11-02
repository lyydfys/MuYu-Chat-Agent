# MCA 天玑 NPU / Anima 全量流水线 交接文档（2026-10-27 晚）

> 本文是接手继续实现的状态账本与执行单。**不是 READY 声明**，也不宣称“天玑 NPU Anima 生图已支持”。
> 上一份交接：`docs/MCA_DIMENSITY_NPU_ANIMA_HANDOFF_2026-10-26.md`（§12–§14 记录了 0.4.4 → 1.0.0 的演进）。

## 1. 一句话现状

- **完整 Anima 流水线（28 块 DiT + final + Euler/CFG 采样 + Qwen/Wan VAE）已经在 PC 上用“设备图包”跑出正确画面**（举牌猫，与 sdcpp 参考一致）。
- **天玑真机还没有成功跑过这条流水线。** 当前测试者手机上装的是 **0.6.1**（分卷导入版），点 Run 后 `pack_missing`。
- **1.0.0 单文件 APK（图包内嵌）已产出，但内嵌包的加载路径从未在真机验证，且很可能失败**（原因见 §5）。
- 本机（E 盘/MCA 仓库）生产代码、数据、schema、owner、子模块**均未修改**。全部工作在外置目录。

## 2. 基线与不可变约束

- 仓库：`E:\MAC`，分支 `codex/enterprise-refactor`，HEAD `68b6eef1e678`（本任务起点）。
- 子模块：llama.cpp `4ceb1719`、stable-diffusion.cpp `16b3846a`、tokenizers-cpp `acbdc5a`（均未改）。
- `AGENTS.md` 与 `UniversalDeviceAdmissionPolicyTest.kt` 仍被 `.git/info/exclude` 排除，**不得提交/发布**。
- 唯一 owner/state writer 不变：没有新增第二套 `ImageGenerationOwner`、队列、worker、状态 writer 或图库链。
- 不得伪造 NPU 证据；设备型号不得作为准入名单。
- 本机没有天玑设备（只有 Qualcomm SM8750P / SM8550）；所有天玑验证都只能远程交给测试者。

## 3. 外置工作目录（不在仓库内）

| 路径 | 内容 | 是否必要 |
|---|---|---|
| `D:\anima-spike\convert\` | 全部转换/验证脚本（见 §6） | 是，可复现的源头 |
| `D:\anima-spike\pack\device\` | 设备图包（37 个文件，int8 block + ONNX VAE） | 是 |
| `D:\anima-spike\dist\` | 所有可分发包（§8 有哈希） | 是 |
| `D:\anima-spike\weights\` | Anima 原始权重（DiT 3.9G、Qwen3 1.2G、VAE 243M） | 是（非商业许可，勿外传） |
| `D:\anima-spike\sdcpp-dump\` | 带转储/调试开关的 sdcpp 源码副本 | 对拍用，可重建 |
| `D:\anima-spike\build-dump\`、WSL `/root/sdcpp-linux` | 仪器化 sdcpp 的 Android / Linux 构建 | 对拍用，可重建 |
| `C:\mca-mtk-spike\mediatek-npu-smoke\` | 测试 App 工程（非 git） | 是 |
| `C:\mca-mtk-spike\mtk-npu-cprobe\` | 独立 C API 探针 | 辅助 |

## 4. 本次修复的四个根因（全部已在 PC 验证）

1. **采样器把网络输出当成了 x0。** 实际是 flow velocity：`denoised = x - sigma*pred`，Euler 为
   `x += pred * (sigma_next - sigma)`。
2. **AdaLN 调制多了一个 SiLU。** `AdaLayerNormZero` 只对 timestep 输入做一次 SiLU，然后
   `linear1 → linear2` **直接相连**；只有 timestep embedder 在两次线性之间有 SiLU。
   旧 `precompute_mods` 在 linear1/2 之间多加 SiLU，导致全部 28 块 × 所有 step 的调制错误。
3. **输入/输出 patch 特征顺序不同。** patchify 输入 `(C, ph, pw)`（C 最慢）；final layer 输出
   `(ph, pw, C)`（C 最快）。
4. **VAE 换成 ONNX Runtime。** TFLite Conv3D 解 512×512 要 20+ 分钟；ONNX/ORT CPU 约 28 秒，
   误差 7e-6。

对拍证据（BF16 sdcpp，Linux 本地编译带转储副本）：RoPE 6e-8、`embedded_timestep`/`temb` 1.0、
prelude 1.0、AdaLN 权重逐位一致、修正后调制 0.999995、28 块完整 pred 0.999995
（mean abs 0.0027 / std 1.10）。PC 用**同一份 int8 设备图包**跑 4 步/CFG6/seed42/512 得到
`D:\anima-spike\out\step4B.png`（71,590 B，SHA-256 `d7310a21…`）。

## 5. 真机状态与卡点

### 5.1 测试者实测（2026-10-27）

- 安装的是 **0.6.1**（不是 1.0.0）。点 **Run full Anima pack (NPU)** 后状态栏显示
  `Anima pack failed at pack_missing`。
- 含义：没有完整导入的图包目录，且该构建没有内嵌图包 → 直接返回 `pack_missing`。
  **不是 NPU 失败，也不是块/VAE 失败。**
- 用户反馈“点击之后没反应”：实际是很快失败并把原因写在状态栏顶部；按钮没有禁用/进度提示，容易被当成无响应。

### 5.2 1.0.0 内嵌包路径为什么很可能失败（必须修）

- 1.0.0 APK 内 `assets/anima-pack.azip` 虽以 Stored 方式打包（已解析 APK 中央目录确认：偏移
  20,075,323、未压缩、长度 2,094,940,161 B），但 **`AGP 8.x` 会把 `assets/**` 里除
  `.mp3/.ogg/...` 之外的扩展名压缩**；`.azip` 不在白名单，`noCompress` 只是打包阶段的清单，
  `mergeDebugAssets` 之后仍会生成 `compressed_assets` 中间产物。
- 因此 `AAsset_openFileDescriptor64` 很可能拿不到 fd，原实现（`/proc/self/fd/<Java fd>` 直读）
  会失败并返回 `pack_missing`。
- **注意：这个失败结论是推断，尚未在真机上确认**；1.0.0 的构建日志里 `compressDebugAssets`
  任务确实执行了，但仍需在设备上验证。
- **关于 `noCompress`：** 1.0.0 的构建只声明了 `noCompress += listOf("tflite")`，没有 `"azip"`；
  1.0.1 才加上。即便加上，`.azip` 不在 AGP 默认不压缩扩展名白名单内，**是否真正 Stored 必须以
  成品的中央目录为准**（解析方法：读 EOCD → 中央目录 → 该条目的 method 字段，0 为 Stored）。

### 5.3 已经做过的修复（未交付）

- `zip_reader.cpp` 改为通过 **`AAssetManager`** 打开资产、`AAsset_openFileDescriptor64` 取 fd，
  取不到时回退 `AAsset_read` 并报出具体原因（`asset_fd_unavailable` /
  `asset_entry_compressed:<name>` / `asset_entries_missing`）。
- `AnimaPackRunner` 新增 `lastEmbeddedError`，`pack_missing` 时把原因写进 evidence
  （`embeddedPackError=...`），并在状态栏显示原因 + “可直接导入分卷”的替代提示。
- 版本号已改为 **1.0.1（code 13）**，`noCompress` 增加 `"azip"`。
- 已编译通过（native 链接 `libandroid`），**1.0.1 APK 尚未成功打包**（见 §9.1）。

## 6. 关键脚本（`D:\anima-spike\convert\`）

| 脚本 | 作用 |
|---|---|
| `anima_torch.py` | Anima DiT 参考实现（严格对齐 sdcpp 语义）+ 权重直读加载 |
| `anima_text.py` | Qwen3-0.6B + 6 层 adapter → context（正/负向） |
| `export_pack.py` | 导出 28 个 block（mod 接口）、final、prelude、VAE、调制向量 |
| `quantize_pack.py` | `ai_edge_quantizer` 权重 only int8（block 242MB→64MB） |
| `make_pack_parts.py` / `make_pack_zip.py` / `make_one_file_bundle.py` | 分卷 / 单包 / 单文件测试包 |
| `run_pipeline.py` | 用 LiteRT 解释器跑整条流水线（设备图包） |
| `decode_vae.py` | 单独用 ONNX VAE 解码 latent |
| `export_vae_onnx.py` | VAE 导出 ONNX（`nearest-exact`→`nearest` 补丁） |
| `compare_context.py` / `compare_step0.py` / `search_*.py` | 与 sdcpp 逐级对拍 |
| `patch_*.py` | 本次对 App 源码的补丁脚本 |

**工具链坑（已踩过）：**
- Windows 无 `litert-converter` wheel → 转换在 WSL Ubuntu 22.04 的 ext4 venv
  （`/root/anima-venv`）里做；WSL 的 pip 连 PyPI 会超时，改为在 Windows 侧
  `pip download --platform manylinux_2_27_x86_64 --python-version 310` 下载到
  `D:/anima-spike/wheels-linux` 再离线安装。
- `litert-torch 0.10.0` + `torch 2.9.1` + `litert-converter 0.5.0` + **`torchao 0.17.0`**
  （0.18 与 torch 2.9.1 不兼容）。
- `diffusers 0.35.2`（0.42 要求 Python ≥3.11），并需给
  `diffusers/quantizers/torchao/torchao_quantizer.py` 补一行 `logger = logging.get_logger(...)`。

## 7. 图包与设备端流程

图包（`D:\anima-spike\pack\device`，37 文件）：
- 28 × `blockNN.tflite`（int8 权重，64,329,952 B/块）
- `prelude.tflite`、`final.tflite`（fp32）
- `vae_decoder.onnx`（286,341,695 B，opset 17）
- `mods.f16` [4,28,3,1,6144]、`mods_final.f16` [4,1,4096]
- `context_pos.f16` / `context_neg.f16` [512,1024]
- `manifest.json`（sigmas 等）、`SHA256SUMS.txt`（LF 换行）

设备流程：patchify(C 最慢) → prelude → 28 block（逐块开图，先 NPU 后 CPU）→ final →
unpatchify(C 最快) → CFG → Euler ×4 → VAE(ONNX，NNAPI→CPU) → PNG。

## 8. 可分发包（哈希均于 2026-10-27 重新计算）

| 包 | 大小 (B) | SHA-256 | 说明 |
|---|---|---|---|
| `MCA-Anima-NPU-Test.apk` | 2,115,598,141 | `bc30f8b96c6ec02928a60e634b9db0ad42722d709c0653c2b53f69664b03a1f0` | **1.0.0**，内嵌包路径未验证 |
| `MCA-Anima-NPU-Test.zip` | 2,115,604,413 | `aea833980363fdf7a918f56dd4bb8fa61623c7dd105f9fd7f6ed4612a3fea3ff` | 1.0.0 + 说明 |
| `anima-pack.azip` | 2,094,940,161 | `294e0f2572fee2f5cbeeda63f721ad31e066cc58cc8433cc277c2512038881d3` | 内嵌用 Stored zip |
| `anima-pack-0.6.1.zip` | 2,094,940,161 | `2af1f90127a8c93fff7bb55b73791796064efed29776ad90715fb146a572f89d` | 单包（配合 0.6.1） |
| `anima-pack-0.6.1-part1..5.zip` | 393,337,090 / 385,980,358 ×3 / 543,662,049 | `12aac128…` / `fa45ae60…` / `dc3f7bee…` / `b6e9527e…` / `cf4a1807…` | 5 个独立分卷 |

App 版本：**0.6.1**（code 11，`dist/mediatek-npu-smoke-0.6.1.apk`，SHA-256
`327002d5f4a244557089d47aeafd98c25f91832fa53d154ab3f419fb6a25402e`）是**唯一在真机上装过**的版本；
1.0.0 的真机状态未知（测试者装的是 0.6.1）。

## 9. 未完成 / 卡点

### 9.1 构建被 C 盘空间阻断（当前最直接的阻塞）

- C 盘 232 GB 已用满（剩余约 2.8 GB）。2 GB 资产的 merge + compress + package 需要数 GB 临时空间。
- 已尝试：`java.io.tmpdir` 指到 D（对 `mergeDebugAssets` 有效）；junction +
  `PackageAndroidArtifact.incomingPackagingDirectory`（该属性在此 AGP 版本不可用，已回退）；
  删除 `~/.gradle/caches/build-cache-1`（1.6 GB，可再生成）。
- **可行方向（未执行）：** 不清理用户缓存的前提下，把构建输出目录搬到 D 盘，或减小图包体积
  （见 9.2 的 fp16/int4 方向），或换到有空间的工作区构建。*清理用户缓存、删除大文件前必须先征得用户同意。*
- 当前工程状态：`app/src/main/assets/anima-pack.azip` 存在（2,094,940,161 B，SHA-256
  `294e0f25…`，与 `dist` 一致）；`app/build/outputs/apk/debug/` 为空（构建未完成）；
  `gradle.properties` 已恢复原样（临时 `java.io.tmpdir` 设置已移除）。

### 9.2 模型与包体积

可把全量包从 2.0 GB 压到约 1.0–1.2 GB：block 用 int4（≈32 MB/块），VAE 用 fp16（≈140 MB）。
需重新量化并重做 PC 端 semantic PNG 验证（数值门槛：p99 相对误差 ≤ 1%，且出图与 sdcpp 结构一致）。

### 9.3 其他未验证

- 天玑 MT6899 上 DiT 块的 NPU 执行（从未跑通；PC 上 NPU 相关只有 tiny 图的历史证据）。
- 从 APK 资产 fd 加载图与 VAE。
- VAE 在设备上的内存（512×512 ONNX 约需 2–4 GB）。
- 产品集成（Phase 5–8）完全未开始：没有 `MEDIATEK_NEURON` runtime、JNI runner、
  bundle/profile、owner/Local API 接线。

## 10. 接手后建议顺序

1. **先让 0.6.1 + 分卷在真机跑通一次**（不依赖任何新构建）：测试者装 0.6.1，按
   `IMPORT_PARTS.md` 依次导入 5 个分卷，再点 Run。这一轮会给出：28 块是否 NPU、编译/运行耗时、
   VAE 后端与耗时、是否出图。这是判断后续投入的最便宜一步。
2. 根据 step 1 结果决定：
   - 若 NPU 可用但慢/内存紧 → 先做 §9.2 的体积/量化优化，再谈产品化。
   - 若 NPU 编译失败 → 用 evidence 里的 `block=NN stage` 定位是图结构还是运行时限制。
3. 若必须单文件体验 → 按 §5.3 + §9.1 完成 1.0.1 构建，并在真机确认 `packSource=embedded`。
4. 期间可同步补全 UI 反馈：Run 时按钮禁用 + 进度行（避免“点了没反应”）。
5. 任何一步都要如实写 PASS / FAIL / NOT_RUN / BLOCKED，并附证据路径与回退点。

## 11. 回退

- 仓库侧：本任务没有修改任何仓库文件，无需回退。
- 外置侧：删除 `C:\mca-mtk-spike`、`D:\anima-spike` 即可；App 工程无 git，如需回到旧版本，使用
  §8 中对应 APK/分卷。
- 未执行 `git reset --hard` / `git clean`，未触碰用户设备设置。

## 12. 2026-10-28 增量：真机 prelude 失败的根因与 1.0.2

### 12.1 测试者证据（原文）

```
schema=mca.mediatek_npu_anima_pack.v1
packSource=files
steps=4 cfg=6.0 seed=42
sigmas=1.0,0.8573265,0.60071945,0.002994012,0.0
contextMeanPos=-9.20750177328955E-5 contextMeanNeg=-1.4482879991934194E-5
status=FAILED stage=prelude step=0
```

`packSource=files` 说明 37 个图包文件已完整导入；失败发生在第一个计算图 `prelude.tflite`，不是 NPU、不是 block、不是 VAE。

### 12.2 根因（本机真机复现，不是推断）

`mtk_runner.cpp` 调 `LiteRtCreateModelFromFd(environment, fd, 0, 0, &model)`。LiteRT v2.3.0 的实现对
`size == 0` 直接返回 `kLiteRtStatusErrorInvalidArgument`（`litert/c/litert_model.cc`），没有“0 = 读到文件尾”的语义。
所以 prelude、28 个 block、final 在 NPU 与 CPU 两条路径上都会在 `create_model` 失败；旧代码不把 native
状态写回 Kotlin，证据只剩 `stage=prelude`。

本机复现（Qualcomm SM8750P，非天玑，只走 CPU；LiteRT 2.3.0 同一 AAR）：

- `size=0` → `result=FAIL stage=create_model status=1 kLiteRtStatusErrorInvalidArgument`
- `size=fstat.st_size (557948)` → `result=OK`，`compileMs=10 run0Ms=20 input0Bytes=278528 output0Bytes=8388608`

证据：`artifacts/architecture/2026-10-26-dimensity-anima/2026-10-28-prelude-fd-probe.txt`。
这同时证明 prelude 图（SHA-256 `676140c8…`）在 Android LiteRT 2.3.0 上 CPU 可编译可运行，且 IO 字节数与 Kotlin 侧一致。

### 12.3 1.0.2 修复内容

- native：`fstat` 取真实长度传给 `LiteRtCreateModelFromFd`；每个失败点写 `g_lastError`，新增
  JNI `nativeRunnerLastError()`；输入长度超限报 `-23` 并写明 buffer/provided；buffer 创建逐项报阶段与索引。
- Kotlin：证据新增 `preludeError/blockError/finalError/vaeError`、每块每步
  `block=NN step=N npu=... compileMs=... runMs=... outBytes=...`；输出字节数只在小于期望时失败（容忍 padding）；
  step 0 若 prelude 走 NPU，追加一次 CPU 对照，记录 `preludeNpuCpuMaxAbsDiff/MeanAbsDiff/RelMeanAbsDiff`，
  相对平均误差 > 1% 时 `npuBlocks=DISABLED reason=prelude_mismatch` 并改用 CPU 结果继续；
  VAE 异常不再被吞掉。
- 可复现性：APK 内置 `assets/latent_seed42.f32`（torch `manual_seed(42)`，262,144 B，SHA `23c72e90…`），
  seed=42/steps=4 时设备用同一初始 latent，出图可与 PC `step4B.png` 对拍；证据记录 `latentSource/latentSha256`。
- 证据逐行实时写入 `animapack-<ts>.txt`，长时间运行被系统杀掉后重启仍能分享最后阶段。
- Activity：失败时状态栏直接显示证据里最后一条 `*Error=` 行。
- **未修**：单文件内嵌包路径（`/proc/self/fd/<apk fd>` 的 `fstat` 是 APK 整体大小，模型区域应传
  `nativeEntryInfo()[3]` offset 与 `[1]` size）；本次 1.0.2 不含内嵌包，测试者走 `packSource=files`，不受影响。

### 12.4 交付物

`C:\mca-mtk-spike\mediatek-npu-smoke\dist\mediatek-npu-smoke-1.0.2.apk`

- 21,083,268 B；SHA-256 `29ea594f671332ae37323cf7eacb38c239f9dcb0a16f58cb50b2d817e34ef068`
- package `com.muyuchat.mca.mediateknputest`，versionCode 14，versionName 1.0.2
- 不含内嵌图包（assets 只有 `latent_seed42.f32` + 两个探针模型）；覆盖安装保留 `filesDir/anima-pack`
- APK 内 `libLiteRt.so` 与设备验证用库逐字节一致（`e5b7e341…`）
- 2.09 GB 的 `app/src/main/assets/anima-pack.azip` 暂存到 `D:\anima-spike\staging\anima-pack.azip`，避免再次构建出 2.1 GB APK
- 构建证据：`artifacts/architecture/2026-10-26-dimensity-anima/2026-10-28-1.0.2-build.txt`
- 测试者说明：`C:\mca-mtk-spike\mediatek-npu-smoke\TESTER_MESSAGE-1.0.2.md`

### 12.5 状态

| 项 | 状态 |
|---|---|
| prelude 真机失败根因 | 已定位并修复（本机复现 + 源码确认）；真机待验证 |
| 真机 Anima 流水线 | NOT_RUN（此前只到 prelude） |
| 28 个 DiT block 的 NPU/CPU | NOT_RUN |
| VAE（ONNX） | NOT_RUN |
| MT6899 上的 Anima NPU 分区 | 无证据；tiny fixture 的 `nonCpuFullyAccelerated=true` 不能外推 |
| READY | NOT_MET |

### 12.6 下一步

1. 测试者覆盖安装 1.0.2，直接 Run（不要 Import），等待逐块日志；失败则发回 evidence txt。
2. 若 prelude 通过而 block 失败：证据里会有 `block=N` 与 native 状态，按 NPU→CPU 回退结果定位。
3. 若拿到 `preludeNpuCpuMaxAbsDiff` 很大：先关 NPU 走 CPU 出图，再单独解决 NPU 数值差异（tiny fixture 曾 20/40 字节不一致）。
4. 需要单文件包时，按 12.3 的未修项传 entry offset/size，再验证 `packSource=embedded`。
5. 任何结果都按 PASS/FAIL/NOT_RUN/BLOCKED 记录，禁止把 CPU 结果写成 NPU。

## 13. 2026-10-28 增量：全量 28 块在 MT6899 上跑完，但出图是坏图

### 13.1 结果

测试者用 1.0.2（`packSource=files`，seed 42，4 步，内置 seed-42 latent）跑完整条流水线：

- **28/28 个 DiT block `npu=true`、`nonCpu=1`、`fully=1`**（LiteRT 报告完全非 CPU 加速）
- 4 步耗时 84.5 / 85.4 / 85.7 / 72.2 秒（约 5.5 分钟）；块编译约 2.0–2.7 秒/块，112 次编译约占一步时间的 80%
- prelude NPU vs CPU：max abs `9.42e-4`、相对平均 `2.82e-4`
- VAE `vaeBackend=cpu`、27,473 ms；总耗时 356 秒；`status=OK`
- **PNG 只有 2,227 B**（PC 参考 71,755 B；纯灰 512×512 PNG 约 1,878 B）

证据：`artifacts/architecture/2026-10-26-dimensity-anima/2026-10-28-npu-run-28blocks-evidence.txt`
（SHA `f5b755a0…`）与 `2026-10-28-npu-28block-run-analysis.md`。

### 13.2 判定

- 这是第一次在真天玑设备上把 Anima 图包（prelude + 28 block + final + VAE）整条跑完，且 LiteRT 报告所有块完全非 CPU 加速；
- 但 **PNG 2,227 B 与"几乎单色"同量级，图片退化，不能算 PASS**，也不能据此宣称"NPU 生图成功"；
- 最可能原因：int8 block 的 NPU 数值错误，或某块输出 NaN/Inf 污染 latent；VAE 本身运行正常（耗时与 PC 一致），端口代码与 PC 已逐项对照。

### 13.3 1.0.3（诊断版）

- 新增 **Run full Anima pack (CPU)**，用同一图包/初始 latent 跑 CPU 参考图；
- 证据新增：每步 `latent` 的 mean/std/min/max/nan/inf + SHA；step 0 的 `hidden[0]`/`hidden[27]`、`finalPos/finalNeg`、VAE 后 `image` 统计；
- 保存最终 latent 到 `anima-pack-output/latent-*.f32`，新增 **Share last latent (.f32)**，可与 PC `step4B.png.latent.npy`（mean 0.129549、std 0.862065、min -2.85218、max 2.20084）逐值对比；
- 升级/重启后自动恢复上一轮 PNG 与 latent（先取回 1.0.2 的 2,227 B PNG）；
- 交付：`dist/mediatek-npu-smoke-1.0.3.apk`，21,100,252 B，SHA-256
  `2c4f9349865d6dcc31aea67f0b726958b7b32f3798586298cb878e00e83ef091`，versionCode 15；
- 说明：`TESTER_MESSAGE-1.0.3.md`。

### 13.4 下一步

1. 取回 1.0.2 的 PNG 确认是否平坦；
2. 跑 1.0.3 CPU 模式，比较 CPU PNG 与 PC 参考、比较最终 latent 统计；
3. 若 CPU 正确、NPU 错误：优先把 block 换成 fp16/fp32 或限制 NPU 子图，再验证；
4. 若 CPU 也平坦：按 latent/image 的 nan/inf 与 step 统计定位 sampler/VAE/端口；
5. 在任何一步都不要把"跑完"当成"PASS"，READY 仍未达成。

## 14. 2026-10-28 晚：设备 CPU 参考 PASS，NPU 块数值 FAIL

### 14.1 CPU 模式结果（1.0.3，MT6899）

- `Run full Anima pack (CPU)`：4 步 / CFG 6.0 / seed 42，`backend=cpu_only`
- 总耗时 215,979 ms（约 3.6 分钟）；VAE CPU 36,837 ms；每块 compile 0.4–0.7 s、run 0.45–0.6 s
- PNG 79,714 B，SHA `f9ae10cb…`；`image mean=0.945 std=0.143 min=-1 max=1 nan=0`
- 最终 latent `latent-1793173110141.f32`（262,144 B，SHA `24b47821…`）与 PC `step4B.png.latent.npy` 对比：
  mean abs 4.35e-6、max abs 2.04e-4、相对平均 5.68e-6、NaN 0
- 设备出图与 PC 参考图 MAE 0.91/255、PSNR 39.75 dB（QQ JPEG 重压缩差异）
- 归档：`2026-10-28-1.0.3-cpu-final-latent.f32`、`2026-10-28-1.0.3-cpu-evidence-screenshot.jpg`、
  `2026-10-28-1.0.3-cpu-cat.jpg`、`2026-10-28-cpu-path-pass.md`

**结论：设备端完整流水线（prelude → 28 block → final → CFG/Euler → VAE → PNG）在 CPU 上 PASS。**

### 14.2 NPU 块

- 1.0.2 的 NPU 运行 28/28 块 `npu=true nonCpu=1 fully=1`（CPU 参考里是 `nonCpu=0`），但出图**纯黑**
  （PNG 2,227 B，JPEG mean=1.0/std=0.0）；
- NPU 总耗时 356 秒，**比 CPU 的 216 秒还慢**：每块每次运行都重新编译，约 2.4 秒 ×112 次；
- 问题已隔离到 MediaTek NPU 执行 int8 block 的数值（NaN/Inf 或退化常数），图包、采样器、VAE、PNG 均无问题。

### 14.3 下一步

1. 用 1.0.3 再跑一次 **NPU 模式**（约 6 分钟），取 `hidden[0]`/`hidden[27]`/`final`/`latent` 的
   nan/inf 统计，定位 NaN 从第几块开始；
2. 若 block 0 已坏：做单块 fp16/fp32 NPU 对照探针（可内嵌一个 fp16 block，约 120 MB）；
   若第 27 块才坏：考虑混合精度/分段；
3. 在 NPU 数值闭环前，产品可先用 **CPU 3.6 分钟**路径交付；NPU 不作为可用能力宣传；
4. READY 仍为 **NOT_MET**。

