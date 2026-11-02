# Wan 2.2 TI2V-5B 视频引擎 — 交接与后续对标方案（2026-10-31 下午）

上一版交接：`HANDOFF_2026-10-31_VIDEO_ENGINE.md`。本文档取代它描述**当前**状态；旧文档里的结论只
在本文未推翻时有效。证据目录统一为 `build/evidence/v1-27/`（下文简写 `v1-27/`）。

---

## 1. 不变量（接手即生效，不要重新讨论）

- 先读 `docs/architecture/MCA_PROJECT_HARD_LOCK.md`。
- **不得**用芯片型号 / 设备型号 / "未测试设备" / "没有 profile" 来隐藏或拦截用户的下载、导入、加载、
  运行。硬件探测只能用于排序推荐和选择 runtime transport；缺 profile 走通用兼容路径，由真实 native
  load / graph / execution 决定可用性。
- `AGENTS.md` 与 `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 是本地
  safeguard，**永不 stage / commit / push**，保留在 `.git/info/exclude`。
- **不自动提交**：除非用户明确要求，否则不 commit / push / publish，留 dirty tree + 证据。
- 不发布模型权重、SDK、APK、Hexagon 运行时到任何远程。
- 不用 WSL（用户明确禁止）。
- 共享工作区有并行 lane：Gradle 走 `build/.mca-exclusive-build.lock` 排队，不杀别人的 daemon。
- 结论分层写清：**生产已接线 / 构建与契约通过 / 真机验证**，三者不能互相顶替。CPU fallback 或小分辨
  率成功**不得**替代某个 BLOCKED 项的验收。

---

## 2. 当前真实状态（逐项，全部有文件证据）

### P0 基线与构建

| 项 | 状态 | 证据 |
|---|---|---|
| 固定 sdcpp a63954d / ggml f8758628 树可复现 | 通过（patch queue 逐字节还原） | `docs/proposals/mca-native-video-workflow-v1/BASELINE_MANIFEST.md`、`patches/0001..0008` |
| 打包集内无重复 `sd_*` 导出（符号互插风险） | **已修复并通过门禁** | `v1-27/P0-BUILD-AND-ABI-GATE.md`、`tools/verify-engine-abi.sh` |
| `libstable-diffusion.so` 由仓库内构建链产出 | **未达标（D-P0-3）** | 该 .so 仍是 out-of-tree 预编译产物，CMake 不会重建它 |
| 设备探针二进制可重建 | 通过（NDK 直接编译，非生产路径） | `v1-27/build-shim.sh` |

门禁实测：`:core:sd-native:externalNativeBuildRelease` 成功后 arm64 `libmca_sd_native.so`
107,779,984 B，导出 `sd_*` **47 → 0**；`:app:mergeDiagnosticNativeLibs` 后 `ABI GATE: PASS`。

### P1 typed adapter（v2 ABI）

已验证（全部在**当前源码构建的产物** `3721fad2…` 上跑过）：negative prompt / guidance / flow shift /
flash-attn、TAE、per-module backend echo、结果字段、采样参数映射、I2V init image
（`encode_first_stage completed, taking 501ms`）、Turbo LoRA（1099/1099 tensors）、真实进度
（3 次回调，无加载步泄漏）、**三阶段取消**（load / sampling / decode-publish 均为
`status=1 error_code=7` 且零帧发布）。证据：`v1-27/P1-CANCEL-PHASES-AND-ADAPTER-DRIFT.md`。

未完成：end image、reference images、custom sigmas（NOT_RUN）；`prep_ms` 计时修正**代码已写完、只编
译过、未在真机验证**（见 §5.1）。

### P2 真实 HTP session

> **2026-10-31 23:49 更正（本节原结论作废）**：App 进程内已建成真实 HTP 路径并跑通
> 480×832×5。证据 `build/evidence/v1-27/INAPP-HTP-480P-PASS.md`，我已独立复核：MP4 30,728 B、
> `sha256=6b243808…1286` 与 API 响应一致、`ffprobe` 480×832/h264/24fps/5 帧、抽帧为蓝底红球。
> 原"只有 `/dev` 节点预检"的判据错误，正确判据是 `htp canary devices=2 [HTP0,CPU]` 与响应中
> `external_backend=htp` 且无 `external_backend_fallback`/`_reason` 字段（两字段只在回落 CPU 时写入）。
> 三个代码级根因已修：`libcdsprpc.so` 不应打包、DSP 骨架需 `chdir` 到部署目录、算术 canary 需 opt-in。

### P3 资产与画质

> **2026-10-31 23:49 更正（本节结论作废）**：多帧噪声的真因是 **HTP elementwise 部分广播计算错误**
> （patch 0011，另一 lane 已修），不是时间轴/RoPE/折叠问题。下面这张 2×2 矩阵是在**未含 0011 的
> 出厂引擎**上、且**用 1 步**跑出来的，只能作为修复前的症状记录，不代表当前状态。当前状态：App 内
> 480×832×5、4 步、TAE 已产出连贯画面（`INAPP-HTP-480P-PASS.md`）。P3 剩余项是**画质对标**
> （未与对标项目做逐帧/指标比较）与 **Turbo LoRA 接入**，不是"引擎画错了"。

历史记录（修复前，1 步压力测试，仅作症状留档）：

| size | frames | steps | hf | 判读 |
|---|---|---|---|---|
| 128² | 1 | 1 | 0.90 | 平滑 |
| 128² | 1 | 20 | 2.01 | 平滑 |
| 128² | 5 | 1 | 12.42 | 损坏 |
| 480² | 1 | 1 | 4.32 | 平滑 |
| 480² | 5 | 1 | 20.29 | 损坏 |

Turbo LoRA 有效：480²×5、4 步、76.49 s 采样（20 步需 586.77 s），1099/1099 tensors。
`v1-27/TURBO-LORA-WORKS.md`。

### P4 有界输出 / P5 单 owner / P6 验收矩阵

**均未开始**。PPM 写盘→读回→重写仍在；MainActivity 与 Local API 尚未共用
`VideoGenerationOwner`；严格验收矩阵 0 项。

---

## 3. 已撤回 / 已纠正（避免重复踩坑）

1. **adapter 二进制曾与源码漂移**：`jniLibs/arm64-v8a/libmca_wan.so` 一度是 `ddbb66ca…`，而源码重建
   是 `3721fad2…`。漂移产物没有 P1-D1 的进度过滤，导致加载步冒充采样步。所有 P1 结论已在新产物上重
   绑。**教训：jniLibs 是提交进仓库的预编译产物，任何源码改动后必须重建并比对 hash，否则设备证据无
   效。**
2. **"TAE 固有条纹" 不成立**：用 CPU latent 解码干净，损坏在 DiT 输出，不在 TAEHV。
3. **"LoRA 不生效" 不成立**：必须经 typed `loras[]`（或 prompt `<lora:name:w>`）传，`--lora-model-dir`
   单独无效。
4. **"baseline 采样参数能修好 HTP 多帧" 不成立**：cfg6 / negative / flow-shift 3 / euler / fa 全开仍是
   损坏输出。
5. **480²×5 用完整 Wan VAE 整段解码在该设备上不可用**：VmSwap 9.17 GB 抖动。
6. 旧交接里"probe 比 shell 慢 4.6–9×" 是**指标口径错误**（probe 的 sample_ms 含 conditioning），
   已撤回；真实采样 6.68 s ≈ shell 6.55 s。

---

## 4. 关键产物与路径

| 产物 | 路径 | 当前 hash/大小 |
|---|---|---|
| typed ABI 头 | `core/sd-native/src/main/cpp/mca/wan/mca_wan_api_v2.h` | 已追加 `prep_ms`（append-only） |
| adapter 源码 | `core/sd-native/src/main/cpp/mca/wan/mca_wan_adapter_v2.cpp` | ~770 行 |
| **真机已验证的 adapter** | `core/sd-native/src/main/jniLibs/arm64-v8a/libmca_wan.so` | `3721fad2…3762`，157,368 B |
| 引擎（a63954d，非仓库构建） | 同上目录 `libstable-diffusion.so` | 163,228,224 B |
| HTP skeleton | 同上目录 `libggml-htp-mca-v79.so` | 930,472 B |
| 设备探针源码 | `core/sd-native/src/main/cpp/mca/wan/mca_wan_probe_v2.c` | 支持尺寸参数与 3 种取消模式 |
| 构建脚本 | `build/evidence/v1-27/build-shim.sh` | adapter 重建入口 |
| ABI 门禁 | `tools/verify-engine-abi.sh` | PASS |
| 设备侧目录 | `device-b:/data/local/tmp/hy98` | 引擎/skeleton/adapter/探针/输出 |
| 模型资产 | `device-b:/data/local/tmp/wan98` | Q4_0 DiT、umt5 Q4_K_M、TAEHV、VAE |

探针用法：

```
mca_wan_probe <out> <steps> <frames> <tae|vae> <threads> <fa> <vram> <mode[,mode]> [w] [h]
# mode: plain | cancel | cancel-ms=N | cancel-on-decode | lora | i2v
```

---

## 5. 后续跑通对标的方案

原则：**先修"结果是错的"，再谈"跑得动"，最后谈"跑得久"。** P3 的画质阻塞不解除，P4/P6 的任何
长视频验收都是在测一个坏引擎。

### W1（第 1 优先级）：P3 时间轴定位与修复 —— 预计 1–2 人日

**目标**：把"多 latent 帧损坏"定位到具体算子并修掉，使 480²×5 与 CPU 同种子同参数的 latent 一致。

1. 在 a63954d 树加 `final_latent` dump（`src/pipeline/video.cpp` 在 `decode_video_outputs` 前），做成
   patch queue 的 `0009-dump-final-latent.patch`（不入生产路径，仅探针构建）。
2. 固定 seed / prompt / 1 步 / 2 latent 帧，分别用 `backend=cpu` 与 `HTP0` 跑，dump 后逐元素 diff。
3. 分支判读：
   - 差异只出现在**第 2 帧** → 查 temporal RoPE / 位置编码索引（`wan.hpp` 中 time dim 的 freq 计算）。
   - 两帧都差但**形状**正常 → 查 5D→4D patch-embedding 折叠的顺序（kt=1 时 48×3072 的堆叠顺序）。
   - 两帧都差且随 patch 位置变化 → 查 patchify/unpatchify 的 reshape+permute（`wan.hpp:628-634`）。
4. 用 `GGML_HEXAGON_OPFILTER` 把可疑算子类打回 CPU 做二分（已有脚本 `v1-27/htp-opfilter.sh`），
   把范围缩到单个 op。
5. 修复后重跑 §2 的 2×2 矩阵，**验收判据**：480²×5 的 hf ≤ 5（与 480²×1 同量级），且 CPU/HTP latent
   最大相对误差落在量化误差量级。

**依赖**：无。**这是当前唯一必须最先做的项。**

### W2：P0-3 引擎产物可复现 —— 预计 1 人日

**目标**：`libstable-diffusion.so` 与 HTP v73/v79 skeleton 由仓库内 patch queue + NDK/CMake 产出，
产物 hash 记录进 `BASELINE_MANIFEST.md`。

1. 新增 `docs/proposals/mca-native-video-workflow-v1/build-engine.sh`：从 `.dsh-tmp/happyyzy/*.tar.gz`
   解包 → 应用 0001..000N → 用本机 NDK（`E:\model\android-sdk\ndk\29.0.14206865`）交叉编译
   arm64-v8a，**不用 WSL**。
2. 复现成功后把 `libstable-diffusion.so` 的产出 hash 与当前 163,228,224 B 版本对照；若不同，记录差异
   原因（编译器版本/flags），不要声称"逐字节一致"。
3. 验收：干净目录下脚本能重建出 .so 与 skeleton，并在设备上跑通同一探针用例。

**依赖**：无。可与 W1 并行（不同文件集）。**建议派给子智能体。**

### W3：P1 收尾（小项）—— 预计 0.5 人日

1. `prep_ms` 已在代码里：`load/prep/sample/decode/total` 分离。重建 adapter → 拷入 jniLibs → 推送 →
   跑 1 次 `1 5 tae 4 1 2 plain 128 128`。**验收**：`prep_ms` ≈ 50 s 且 `sample_ms` ≈ 8.8 s（而非
   `load=0.6s / sample=59s`），引擎自报 sampling 8.74 s 对得上。
2. end image、reference images、custom sigmas：在探针加模式各跑一次；缺失语义要返回
   `UNSUPPORTED_FIELD` 而不是静默丢弃。
3. **在验证前，不要把 `b82e797a…` 拷进 jniLibs**（当前 jniLibs 仍是已验证的 `3721fad2…`）。

### W4：P4 有界输出 —— 预计 2–3 人日

1. 删除"PPM 写盘 → 全部读回 `sd_image_t[]` → 重写"路径，改为 sink 逐帧交付 + 有背压的 FrameConsumer。
2. **注意：TAEHV 忽略 tile sink**（`vae.hpp:59`），所以**只能用完整 Wan VAE 走流式**；若坚持 TAE 配
   方，则需接受整段解码并把该限制写进结果字段，不得声称"有界"。
3. 同时保留空间 tiling 时，时间块外循环 → 块内全部空间 patch → 合成 → 更新各空间位置因果 cache →
   交付。`CACHE_T=2` 是因果历史，**不是可丢弃的输出重叠**。
4. 验收：1 / 5 / 25 / 121 帧，480² 后 704²；记录 `prep_ms`、RSS、系统可用内存、温度、uptime、已写帧
   数；ffprobe 帧数 / fps / hash / 抽帧连续性。

**依赖**：W1（画质）、W3（计时）。

### W5：P2 真实 canary + P5 单 owner —— 预计 2 人日

1. `BackendSession`：按 module 解析 backend → 加载 backend/skeleton → 建 FastRPC session → 跑最小可
   校验 graph canary → 回显 `requested/actual/reason`。**替换掉 `/dev` 节点预检**，预检只保留为日志。
2. 生成前失败可按既定策略回落 CPU 并回显原因；**生成期失败不得静默重跑成 CPU**。
3. MainActivity 与 Local API 共用 `VideoGenerationOwner`（请求身份、lease、恢复、取消、发布同一状态
   链），禁止 UI/API/Worker 各自建 native runtime。

### W6：P6 严格验收矩阵 —— 预计 1–2 人日

T2V 与 I2V、480² 与 704²、单帧与约 5 秒（121 帧 @24fps）、HTP 与 CPU fallback、默认配方与 4-step
Turbo、取消、重复请求、进程死亡恢复、磁盘不足、模型损坏、输出 hash、许可证/资产完整性。每一项写
PASS / FAIL / NOT_RUN + 证据路径；任何"未测试设备"类理由不得作为拒绝功能的依据。

### 建议执行顺序

```
W1（必须最先，阻塞一切画质结论）
 ├─ W2（可与 W1 并行，派子智能体；注意不跑 Gradle，直接 NDK 构建）
 ├─ W3（W1 之后半天内收尾）
 └─ W4 → W5 → W6
```

预计单人 5–8 人日（W1 若卡在某个 op 上无法定位，另加 1–2 人日）。**只要 W1 未通过，W4–W6 的验收
一律标 NOT_RUN，不要用 CPU fallback 或小分辨率成功充数。**

---

## 6. 常用命令

```bash
# 重建 adapter（输出 build/evidence/v1-27/libmca_wan.so）
bash build/evidence/v1-27/build-shim.sh

# 编译探针
NDKBIN=/e/model/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin
"$NDKBIN/aarch64-linux-android29-clang" -O2 -Wall -Wextra \
  -I core/sd-native/src/main/cpp/mca/wan \
  core/sd-native/src/main/cpp/mca/wan/mca_wan_probe_v2.c \
  -L build/evidence/v1-27 -lmca_wan -o build/evidence/v1-27/mca_wan_probe

# 推送（必须 MSYS_NO_PATHCONV=1，否则 Git Bash 改写 /data 路径）
MSYS_NO_PATHCONV=1 adb -s device-b push build/evidence/v1-27/libmca_wan.so /data/local/tmp/hy98/libmca_wan.so
MSYS_NO_PATHCONV=1 adb -s device-b push build/evidence/v1-27/mca_wan_probe /data/local/tmp/hy98/mca_wan_probe

# 运行
adb -s device-b shell 'cd /data/local/tmp/hy98 && LD_LIBRARY_PATH=. ./mca_wan_probe out 1 5 tae 4 1 2 plain 128 128'

# 构建与 ABI 门禁（Gradle 需先拿 build/.mca-exclusive-build.lock）
MCA_QNN_SDK_ROOT=E:/model/qairt-sdk/qairt/2.45.0.260326 \
  ./gradlew.bat :core:sd-native:externalNativeBuildRelease :app:mergeDiagnosticNativeLibs --offline
bash tools/verify-engine-abi.sh
```

## 7. 本轮证据索引（`build/evidence/v1-27/`）

- `P0-BUILD-AND-ABI-GATE.md` — 构建复现 + ABI 门禁通过，含"陈旧打包产物导致假 FAIL"的陷阱记录。
- `P1-CANCEL-PHASES-AND-ADAPTER-DRIFT.md` — 三阶段取消 + adapter 二进制漂移撤回 + D-P1-T1。
- `P3-MULTIFRAME-MATRIX.md` — 2×2 画质矩阵，时间轴定位与 4 个假设的排除。
- `NEXT-ROUND-2026-10-31.md` — 本轮早先的简短交接（本文取代它）。
- `htp128-f1-*`, `htp128-f5-*`, `htp128-s20-f1-*`, `htp480-f1-*`, `htp480-f5-*` — 帧图与 PPM 原图。

---

## 8. 续作更新（2026-10-31 下午，W1 已闭环）

> 本节记录 W1 的结论，取代 §5-W1 的"待做"状态；其余章节的基线与不变量不变。

### 8.1 根因（已有逐阶段证据）

**`ggml_hexagon_supported_binary()` 对所有 `ggml_can_repeat()` 允许的部分广播都宣称支持，但 HTP 的
elementwise MUL/ADD kernel 只对 same-shape 与 row-broadcast（`src1->ne[1..3]==1`）正确。**
Wan 2.2 TI2V-5B 只有在 latent 帧数 >1 时才走这条路径：

- `WanAttentionBlock::forward` 的 `modulate_mul/add` 把 token 张量 reshape 成 `[dim, L/T, T, N]`，
  再把 per-frame modulation `[dim, 1, T, N]` 沿 `ne[2]` 广播；
- `Rope::apply_rope` 对 q/k 的 3D PE 也是部分广播。

T=1 时广播退化为 row 形式，所以单帧一直正常；T≥2 时第 0 个 block 的 `norm1` 就偏离，
`block_out` absmax 1 359 vs CPU 106，最后整段 latent 爆炸。

### 8.2 证据链

1. `final_latent`（patch 0009）：128²×5、1 步，CPU vs HTP rel_l2 **1.6947 / corr 0.145**；
   同参数 T=1 只有 **0.0415 / corr 0.9991**。
2. 逐阶段探针（patch 0010，`MCA_WAN_TENSOR_PROBE=1 MCA_WAN_TENSOR_STATS=1`）：
   `wan.emb`、`wan.e`、`wan.e0`、`es0/es1`、`b0.norm1_raw`（LayerNorm）CPU/HTP 逐位一致；
   `b0.norm1`（调制后）sumsq 95 991 vs 49 659。首个 block 即发散。
3. 算子过滤二分：仅 `OPFILTER=mul` 不修复（rel_l2 1.72）；`OPFILTER='mul|add'` 完全修复
   （rel_l2 0.0500 / corr 0.99945）。图中不存在 `ROPE` op，早先 `OPFILTER=rope` 是空操作。
4. 模型层 `ggml_repeat` 展开只把 rel_l2 降到 1.17，说明 apply_rope 也在广播，故修复放后端。

### 8.3 修复

`patches/0011-htp-reject-partial-broadcast.patch`（host 侧，无需重建 skeleton）：对 MUL/ADD
拒绝非 same-shape、非 row-broadcast 的 `src1`，让这些节点走 CPU。MUL_MAT / FLASH_ATTN / NORM /
CONV_3D 等仍全部在 HTP。HTP 128²×5 1 步 62.0 s → 62.1 s；480²×5 77.9 s → 80.8 s（噪声内）。

### 8.4 验收结果（`device-b`，1 步，TAE，seed 42）

| 用例 | 修复前 | 修复后 |
|---|---|---|
| 128²×2 latent 帧 CPU/HTP rel_l2 | 1.6947 (corr 0.145) | **0.0500 (corr 0.99945)** |
| 480²×5 解码 hf（frame 0/4） | 10.15 / 7.94（std 72/58） | **1.55 / 1.10（std 7.6/5.4）** |
| 480²×1 解码 hf | 2.162 | 2.162（路径未变） |
| 128²×5 解码 hf（frame 0/4） | 6.21 / 5.21 | 3.90 / 3.87 |

W1 验收（480²×5 hf ≤ 5，CPU/HTP latent 落在量化误差量级）在 1-step TAE 用例上达成。
详见 `build/evidence/v1-27/P3-W1-ROOT-CAUSE-AND-FIX.md`。

### 8.5 追加验收（同日）

- **20 步 base（480²×5，TAE）PASS**：采样 187.49 s，hf 1.275 / 1.329 / 1.390（std 42.8/34.4/31.7）。
- **4 步 Turbo LoRA（480²×5，TAE）PASS**：`(1099 / 1099) LoRA tensors have been applied`，
  采样 132.89 s，hf 1.555 / 1.560 / 1.497（std 24.3/19.1/17.4）。
- **W3 `prep_ms` 真机验证 PASS**：新 adapter（sha256 `b82e797a…`）在 staging 目录运行
  `1 5 tae 4 1 2 plain 128 128`：`load=719 prep=62470 sample=8589 decode=3`，与引擎自报
  sampling 8.74 s 对得上（此前 62 s 被错记到 sample）。见 `W3-PREP-MS-VERIFIED.md`。
- 引擎产物可复现脚本修正：GNU patch 优先（git apply 在父仓库内会静默 skip）、OUT_DIR 绝对化、
  `JOBS=4` 防止本机页面文件耗尽；生产补丁集含 0011。

### 8.6 仍未完成 / 边界

- patch 0009 / 0010 是 debug-only（env gated）；0011 是生产修复，已进入构建链。
- 仍未跑：完整 Wan VAE 解码、121 帧、704²、App 进程内 HTP session/canary、单一
  `VideoGenerationOwner`、有界输出。在它们通过前不得宣称 P3/P4/P5 全量闭环。

### 8.7 续作更新（2026-10-31 晚，打包与 ABI 收口）

- **可复现引擎上机 PASS**：`build/repro-engine-repro/artifacts/libstable-diffusion.so`
  `sha256 1f320b73…`（162,473,432 B）在 480²×5、4 步 Turbo LoRA 下产出 5/5 帧，
  `prep=51.99 s sample=63.27 s decode=0.012 s`，hf 1.555/1.560/1.497，与调试树完全一致。
- **发现并修复 ABI 混用缺陷**：首次在该引擎上跑出 `prep=21474836485`——等于
  `(5<<32)|5`，即旧 adapter（`3721fad2…`，早于 `prep_ms` 字段）把 `frames_written`/
  `delivered_frames` 写到了新探针的 `prep_ms` 位置。修复：`struct_size` 双向要求**精确相等**，
  否则 `MCA_WAN_ERR_ABI_MISMATCH` 并打印两侧尺寸；新增 `mca_wan_abi_test` 在真机验证
  旧 request（256≠264）、旧 result（1168≠1176）、错误版本三类调用全部被拒（`ABI-GUARD PASS`）。
- **适配器补 `out_dir` 创建**：调用方只需给目录名；创建失败在解码前以 `MCA_WAN_ERR_IO` 返回，
  不再在采样结束后才丢帧。
- **打包完成**：`jniLibs` 引擎 `1f320b73…`、adapter `2f6f8d09…`；
  `:app:mergeDiagnosticNativeLibs`（`-PmcaQnnSdkRoot=E:/model/qairt-sdk/qairt/2.45.0.260326`）
  BUILD SUCCESSFUL，merged 产物与 jniLibs 哈希一致；`tools/verify-engine-abi.sh` → ABI GATE: PASS
  （无重复/未解析 `sd_*`，打包引擎为 a63954d `generate_video_streaming=1` 标记）。
- **字段语义实测**（480²×5，HTP0）：
  - I2V PASS：`encode_first_stage` 1.17 s，帧哈希与同参数 T2V 不同（真的被消费）。
  - custom sigmas PASS：`1.0/0.25/0.0` 调度与默认 2 步调度帧哈希不同。
  - **end_image 在 Wan 2.2 TI2V-5B 上无效**（引擎仅 FLF2V/MiniMax-H3/LTXAV/Hunyuan 分支消费），
    实测与 T2V 帧逐字节相同；adapter 现显式拒绝（`status=3`、`error_code=3`、
    `unsupported_field=end_image_path`，628 ms），不再静默丢弃。
- **P2 首次用真实 session 取证**（`mca_htp_canary`，建 backend + 跑 `a+b` 图，不是 `/dev` 探测）：
  - shell uid：HTP0（v79, cdsp:3）创建成功，`0/64` 不匹配 → **PASS**；
  - App uid（`run-as com.muyuchat.mca`，`u:r:runas_app:s0`）：**BLOCKED**，两层独立原因——
    应用命名空间无法 `dlopen libcdsprpc.so`（补该库又级联需要 `vendor.qti.hardware.dsp-V1-ndk.so`），
    且 `/dev/fastrpc-cdsp` 为 `system:system 0664` / `vendor_qdsp_device`，`run-as` 写入 `rc=1`。
    registry 只剩 CPU。详见 `P2-APP-HTP-CANARY-BLOCKED.md`。
  - 结论：App 内 HTP 是外部条件阻断（需 vendor 侧开放 FastRPC 通路），产品路径必须回显
    `requested=htp, actual=cpu, reason=…`，不得用 CPU fallback 记作 P2 通过。
- **P4：121 帧（480²，≈5 s @24 fps）PASS**：`frames written=121 delivered=121`，
  `prep 48.31 s / sample 530.79 s / total 580.06 s`，可用内存 6.78→5.31 GB，温度 32.3→54.2 °C，
  hf 1.824 / 2.473 / 2.319。仍用 TAE 解码与 PPM spool（80 MB 全帧落盘），有界输出未达标。
  见 `P4-121-FRAMES.md`。
- 证据：`P6-ACCEPTANCE-MATRIX.md`、`BASELINE_MANIFEST.md` §5/§5.1、`w5-repro-lora-480-4s.log`、
  `w5frames/`、`gradle-merge-20261031b/c.log`、`P2-APP-HTP-CANARY-BLOCKED.md`、`P4-121-FRAMES.md`。

---

## 9. 续作更新（2026-10-31 23:49）：P2 App 内 HTP 已由 BLOCKED 转为 PASS

上文 §P2 的结论「App 内 HTP 是外部条件阻断（需 vendor 放行 FastRPC）」**已作废**。
两项依据都来自本机真机实测，不需要 root、系统签名或高通授权。

### 9.1 结果

POST /v1/video（engine=sdcpp_external、ackend=htp、480×832×5、4 步、seed 42）返回 **200**：

- external_backend=htp（顶层与 execution 内一致，logcat 无 allback 行）；
- htp canary devices=2 [HTP0,CPU]、equested=[htp/cpu/cpu] actual=[htp/cpu/cpu]、rames=5 delivered=5；
- 产物 30,728 B，sha256=6b243808…9f1286，与 API 返回**完全一致**；
  fprobe：h264 / 480×832 / 24 fps / 5 帧 / 208.3 ms；
- 耗时 elapsed_ms=400870。

### 9.2 三个真实阻断点（都可在代码层解决）

1. **libcdsprpc.so 不应打包进 APK。** 它在 /vendor/etc/public.libraries.txt 里，
   由系统解析即可；打包副本抢在系统副本之前加载，其 vendor 依赖（libvmmem.so、
   endor.qti.hardware.dsp-V1-ndk.so 等）在 App namespace 无法解析 ⇒ devices=1 [CPU]。
   移除后立即变为 devices=2 [HTP0,CPU]。
2. **骨架搜索路径。** DSP loader 只搜 /vendor/dsp 与进程 cwd（./）；App 的 cwd 是 /。
   ADSP_LIBRARY_PATH 对 App 进程**不生效**（实测）。解法：chdir 到骨架部署目录
   （本项目为 /data/local/tmp/hy98，目录不存在则不动作）。
3. **预检算术 canary 会在 App 进程 SIGSEGV**（ault addr 0x1000000，发生在 graph_compute）。
   计算校验已改为 opt-in（MCA_HTP_CANARY_COMPUTE=1），默认只验证设备可达且能初始化。

### 9.3 480p 多帧的两个额外前提

- **内存**：UMT5 Q8_0 常驻约 6.0 GB，480p 采样期会被 LMK 杀。新增 Q4_0_Q4_K_M profile
  （DiT/VAE 不变，编码器 3.66 GB，省 2.38 GB；sha256 已与 HF 钉值核对）。
- **TAE 解码与分块不相容**：	emporal_tiling=1 + 	ile_frames=1 时，
  ae_tiling.hpp:266 报 	emporal tiling left output frame 1 uncovered，解码产出 0 帧。
  TAE profile 下必须关闭 temporal/spatial tiling 且不下发 	emporal_tile_frames。

### 9.4 仍未达成（不得记作通过）

- 本结果为 **TAE 解码**；**完整 Wan VAE** 的 480p×5 仍因内存不可用（ABORTED-true-vae-480p.md）；
- 未与对标项目输出做逐帧/指标画质对比；当前 4 步非蒸馏 + TAE，边缘偏软；
- **Turbo LoRA 未接入 App 请求链**（shell 侧已验证可加载，w5-repro-lora-480-4s.log）；
- UI 默认仍是 33 帧 / 16 fps / 20 步（产品目标 24 fps / 4 步）；
- 有界输出（PPM 落盘→读回→重写）与单 VideoGenerationOwner 仍 NOT_RUN；
- 单帧 MP4 容器时长仍为 0（校验层已放行，编码层未根治）。

**证据**：uild/evidence/v1-27/INAPP-HTP-480P-PASS.md、
inapp-htp-480p-media-667a03f6-….mp4、inapp-480p-f0/f4.png、
INAPP-HTP-WORKS.md（128²）、INAPP-480P-TAE-ATTEMPT.md（失败过程）。