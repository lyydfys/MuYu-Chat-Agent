# 1.0.17 天玑真机结果独立复核与修复方案

日期：2026-10-31
复核人：MCA 主线 agent
证据：`D:\下载\mca-probe-report-1793427708902.txt`、`mca-probe-journal-1793427721950.txt`、`mca-mediatek-npu-evidence-1793427734573.txt`、`mca-anima-journal-1793428351147.txt`、`mca-anima-journal-1793429623458.txt`、`mca-mediatek-npu-evidence-1793428363456.txt`、`mca-mediatek-npu-evidence-1793429630876.txt`、`latent-1793428178258.f32`、`latent-1793429544571.f32`
对照：`docs/MCA_DIMENSITY_NPU_TEST_ANALYSIS_2026-10-31.md`（另一 agent 的报告）

---

## 0. 结论

1. **CPU 轮可信**：最终 latent `std=0.8620654`、`absmax=2.8521886`、SHA-256 `24b47821…db884`，与报告中 PC 参考数值一致（PC 输出未随附，无法二次复算）；PNG 79,714 B、图像 std 0.14347，猫图正常。
2. **NPU JIT 轮数值失败**：final latent `std=0.7205795`、`absmax=3.3116577`，与 CPU 基线逐元素 `relL1=0.95363635`、`maxAbsDiff=3.3155317`；PNG 631,831 B、图像 std 0.52852，白噪。**这是数值失败，不是“质量略差”。**
3. **根因是 JIT fp16 动态范围**：step 0 的 block 0–11 在 NPU 上通过健康门，但 `hidden[27]` 的 `absmax` 从 CPU 18,484 涨到 78,252（4.23×），`std` 从 765.1 涨到 1,761.6（2.30×）；之后量程护栏把 block 26/27 与 final 推回 CPU，误差已不可恢复。探针的 softmax 溢出与 amplitude scale=1000 全 Inf 支持同一结论。
4. **验收有两处硬缺陷**，导致失败轮被报成 `CPU_FALLBACK_OK` / `status=OK`：
   - 计划/量程钉住的 CPU 块被计入 `cpu-fallback`（`blocksCpuPinned=0`，`pinnedSet=12..21`），`openFallbackBlocks=13`，`NPU_OK` 在混合执行下不可达；
   - latent 基线选择未绑定“已确认 CPU 轮”身份：NPU 轮 `compared=latent-1793428706638.f32` 且 `relL1=0.0`、`maxAbs=0.0`，说明被选文件与 NPU 输出逐位相同，绝不可能是 CPU 基线（CPU 基线真实 `relL1=0.9536`）。漂移门因此被绕过。
5. **对另一份报告的修正**：其 §7 “gelu_exact 只有 10% 有限、transpose 只有 1% 有限”是误读。原始探针明确 `gelu_exact` NPU `count=524,288 nan=0 inf=0`、`transpose` NPU `count=2,097,152 nan=0 inf=0`，二者都与 CPU `fp16Equivalent=true`（relMeanAbs 4.17e-4 / 1.76e-4）。这两项 PASS 成立；真正失败的是 softmax、dequant_fc、mobilenet 编译。
6. **对另一份报告的其它修正**：其 §0 “18 个块稳定走 NPU”与实测每步 16/15/15/14、唯一 16 块不符；`blockRunsNpu=74` / `blockRunsOpenFallback=49` 未出现在随附证据中（随附为每步 NPU 计数与 `openFallbackBlocks=13`）。
7. **测试对象不是当前仓库**：被安装的是 `com.muyuchat.mca.mediateknputest` 1.0.17 (29)，APK 651,552,993 B，SHA-256 `2d6781aa…a9b4`；当前 `E:\MAC`（enterprise-refactor）与 `E:\MAC-wt-b1` 均无 `AnimaPackRunner` / `animaVerdict` / `compareWithPreviousLatent` 源码。**本仓库无法直接重编该诊断 App**；下面给出可移植的修复合同与补丁规格，生产侧 `MediaTekGraphScheduler` 已按该合同实现并有回归测试。

---

## 1. 环境与构建身份（已核对）

```
packageName=com.muyuchat.mca.mediateknputest
versionName=1.0.17 versionCode=29
device=vivo V2545A  soc=MediaTek MT6993  api=37  abi=arm64-v8a
Neuron SDK: bytecode 9.0.11 / runtime 9.0.11
pluginSource=reference  packSource=files
apkSha256=2d6781aa3d8cbfcb0ab956a5229a22784a494ac420fcfa30c63481adb831a9b4
```

注意两点：

- 探针报告内嵌的 runner report 写 `appVersion=1.0.4 versionCode=16`，而 packageManager 的安装版本是 1.0.17/29。**`appVersion` 是探针 runner 的陈旧常量，不能作为构建身份**；构建身份以 APK 路径/SHA 与 packageManager 为准。
- 探针报告顶层 `provesNpuExecution=false`。NPU 真实执行证据是 `accel=npu run=OK nonCpu=1` 与 Neuron SDK 版本日志；正式 NPU 证明仍应单独标注，不能只凭该字段或只凭“插件被加载”。

---

## 2. CPU 轮（基线）

| 指标 | 值 |
|---|---|
| 每步 backend | 28/28/28/28 全 `cpu` |
| `animaVerdict` | `CPU_FALLBACK_OK reason=cpu_only_requested`（命名有误，见 §5.3） |
| latent | mean 0.1295491, std **0.8620654**, min -2.8521886, max 2.2008207, nan/inf 0 |
| latent SHA-256 | `24b478212904a10a77e420d519c887ebe1e0c631757d88612af775470abdb884` |
| `latentVsPrevious` | relL1 0.0, compared `latent-1793258640288.f32`（更早的 CPU 结果，合理） |
| `latentDivergenceChecked` | **false**，但 `latentDivergenceOk=true`（映射缺陷，见 §5.4） |
| PNG / 图像 | 79,714 B；mean 0.94517, std 0.14347 |
| 耗时 | 288,137 ms（VAE CPU 27,701 ms） |

CPU latent 与随附文件 `latent-1793428178258.f32` 逐字节一致；这是本次唯一可确认的正确基线。

---

## 3. NPU JIT 轮（失败事实）

| 指标 | 值 |
|---|---|
| 每步 backend | step0 16 npu/12 cpu-fallback；step1 15/13；step2 15/13；step3 14/14 |
| `blocksNpu` | 16（60 次成功 NPU block run；按 step 16/15/15/14） |
| `numericFallbackBlocks` | 3（step1 block 24/25/26 输出 INF） |
| `openFallbackBlocks` | 13 |
| `blocksCpuPinned` | **0**，但 `pinnedSet=12,13,…,21`（计数器与分类都错，见 §5.1） |
| `animaVerdict` | `CPU_FALLBACK_OK reason=cpuNumericFallback_3,cpuOpenFallback_13`（应失败） |
| latent | mean 0.0688374, std **0.7205795**, min -3.3116577, max 2.9855547 |
| latent SHA-256 | `bcd7bd35e9b0dcb21f939c2245daa166efaa97aaaa9373efff7aa923891d74da` |
| `latentVsPrevious` | relL1 **0.0**, maxAbs **0.0**, compared `latent-1793428706638.f32` |
| `latentDivergenceChecked` | true，`latentDivergenceOk=true` → **误判** |
| PNG / 图像 | 631,831 B；mean 0.11151, std **0.52852** |
| compile | step0 9,598 ms；`cacheVerdict=INEFFECTIVE`（plugin 可能未实现 GetCacheKey） |
| 耗时 | 267,076 ms（仅比 CPU 快 7.3%） |

### 3.1 独立复算（numpy，随附两份 latent）

```
CPU std=0.8620654 absmax=2.8521886 sha=24b47821…db884
NPU std=0.7205795 absmax=3.3116577 sha=bcd7bd35…d74da
maxAbsDiff=3.3155317 meanAbsDiff=0.7295679 relL1=0.95363635
```

`relL1≈0.954` 与另一份报告一致。两张用户截图（CPU 猫图 / NPU 白噪）与数值一致。

### 3.2 根因链路（证据支持）

| step 0 | CPU | NPU | 比值 |
|---|---|---|---|
| hidden[0] std | 0.6536972 | 0.6537250 | 1.0000 |
| hidden[0] absmax | 20.6915 | 20.7031 | 1.0006 |
| hidden[27] std | 765.1291 | 1,761.6341 | 2.30× |
| hidden[27] absmax | 18,484.168 | 78,252.79 | **4.23×** |

- step0 block 0–11 均在 NPU，健康门均通过（块输入/输出 absmax 尚小）；block 26/27 与 final 因输入 absmax 64,096/77,729/78,252 超过 60,000 护栏转 CPU。
- 探针 `block00_fp16/fp32/int8` 与 CPU relL1 7.4e-4（单块本身好）；`op_softmax` NPU 出现 nan 18,064 / inf 16，值域 ±65,504；amplitude scale=1000/4096 时 **2,097,152 个 Inf**；`mtkOptions` V3/V4 与 V0 数值相同，V1/V2 打开即失败。这些共同指向 **JIT fp16 域内溢出/饱和**，不是某个可调开关的问题。
- 结论“误差从 block 0–11 的内部中间值开始、经后续块放大”属于**强推断**：日志只记录块输入/输出，没有记录块内中间张量；与 block00 单独一致、softmax/scale 探针一致，但未直接抓取块内值。

---

## 4. 探针矩阵（独立核对）

`summary planned=16 executed=15 pass=11 fail=3 notRun=1 invalidInput=0`，与报告一致。失败项：

| 探针 | 事实 |
|---|---|
| `op_softmax` | NPU `nan=18064 inf=16`，值域 ±65,504；CPU 正常；pair relL1=NaN |
| `op_dequant_fc` | `relL1=1.011999e-3`，被判 `numeric_deviation`（该探针阈值比 micro 0.05 更严） |
| `mobilenet_v1_0.25_224_quant` | NPU compile status=504（`kLiteRtStatusErrorCompilation`） |
| `mtkOptions V1/V2` | `input_buffer[0] status=3`，不可用 |
| `block00_int8_full` | 有意 NOT_RUN |

通过项中，**`op_gelu_exact` 与 `op_transpose` 是全有限的**（分别 524,288 / 2,097,152 个元素，nan=0 inf=0），pair `fp16Equivalent=true`（relMeanAbs 4.17e-4 / 1.76e-4）。另一份报告“只有 10%/1% 有限”是把总元素数误读成了有限数；这两项 PASS 不应被否定，但也不代表所有算子都可用。

---

## 5. 验收缺陷（按可证程度分级）

### 5.1 后端分类把计划/量程 CPU 算成 fallback（已证）

- 日志/证据：`pinnedSet=12..21`（10 块）、`blocksCpuPinned=0`、`openFallbackBlocks=13`；step0 的 12 个 `cpu-fallback` 正好是 10 个钉块 + 2 个动态护栏（block 26/27）。
- 影响：任何混合执行都带 `openFallbackBlocks>0`，`NPU_OK` 不可达；正常“18 NPU + 10 钉块”也会被判为 fallback 失败。
- 另一份报告引用的 `blockRunsOpenFallback=49` 未出现在随附证据中（随附为 `openFallbackBlocks=13`）；49 只能来自未提供的 run counter，不能作为已核实数字引用。
- **修复合同**：块后端必须区分 `npu`、`cpu-planned`、`cpu-range-pinned`、`cpu-fallback-open`、`cpu-fallback-numeric`；`openFallback` 只统计“真实 NPU open/verify 失败后的 CPU 接管”；`blocksCpuPinned` 统计钉块数；混合成功应给 `NPU_OK`（或明确的 `MIXED_NPU_OK`），而不是 `CPU_FALLBACK_OK`。

### 5.2 基线选择未绑定身份，实际选中了与 NPU 输出逐位相同的文件（已证）

- NPU 轮 `compared=latent-1793428706638.f32`，`relL1=0.0 maxAbs=0.0`。`relL1=0` 意味着被选文件与 NPU 输出**逐位相同**；它不可能是 CPU 基线（真实 relL1=0.9536）。
- 随附文件只有 CPU `latent-1793428178258.f32` 与 NPU `latent-1793429544571.f32`；`1793428706638` 未提供，因此无法证明它是否由同一轮写出。但**无论来源如何，它都不是独立 CPU 基线**，用它做漂移判定无效。
- **修复合同**：每次运行写入 `{runId, backend(cpu/npu/mixed), modelIdentity, seed, steps, latentSha256, dims, createdAt}` 的 run manifest；基线只能从 `backend=cpu` 且 `runId != currentRunId`、`modelIdentity/seed/steps` 相同的记录中选；无合格基线 → `NOT_COMPARED`（`latentDivergenceOk=null/false`），不能用 0.0；同一请求内不得把自身输出当基线。

### 5.3 判定映射：失败仍 `status=OK`（已证）

- `animaVerdict=CPU_FALLBACK_OK` + `status=OK`，同时 `numericFallbackBlocks=3`、`openFallbackBlocks=13`、图像 std 0.5285（白噪）。
- `CPU_FALLBACK_OK` 只应在“允许 CPU 回退且没有数值失败、没有基线分歧”时使用；本次应至少为 `FAILED_DIVERGED` 或 `FAILED_NUMERIC`。
- CPU-only 轮应为 `CPU_OK reason=cpu_only_requested`，不是 `CPU_FALLBACK_OK`（CPU 是请求的后端，不是 fallback）。

### 5.4 未检查时 `latentDivergenceOk=true`（已证）

CPU 轮 `latentDivergenceChecked=false` 但 `latentDivergenceOk=true`。未检查应是 `null`/`false`，绝不能为 `true`；否则所有“无基线”运行都会默认通过漂移门。

### 5.5 白名单/误差累积（建议，非既有合同）

- 块级健康门只看块输入/输出 absmax，挡不住块内 fp16 溢出；建议在 step0 对每个 NPU 块用“同输入 CPU 参考”计算 relL1，超过阈值立即停止整轮（而不是等 4 步跑完）。
- 在拿到逐块数值证据前，临时把 NPU 参与范围缩到已验证块（报告建议 0、22、23），其余 CPU；这是 **JIT 第二通道**的止血，不是 NPUG/DLA 主线的替代。
- 这属于 S3（自有 TFLite JIT）独立路线；NPUG/DLA 主线仍按 v4 四段链走。

---

## 6. 可移植修复规格（给 harness/其他 agent）

```kotlin
enum class BlockBackend { NPU, CPU_PLANNED, CPU_RANGE_PINNED, CPU_FALLBACK_OPEN, CPU_FALLBACK_NUMERIC }

fun classify(usedNpu: Boolean, numericFallback: Boolean, pinned: Boolean,
             npuAttempted: Boolean, npuFailed: Boolean): BlockBackend = when {
    usedNpu -> NPU
    numericFallback -> CPU_FALLBACK_NUMERIC
    pinned -> CPU_RANGE_PINNED
    npuAttempted && npuFailed -> CPU_FALLBACK_OPEN
    else -> CPU_PLANNED
}

data class RunManifest(val runId: String, val backend: String, val modelIdentity: String,
                       val seed: Long, val steps: Int, val dims: List<Int>, val latentSha256: String)

fun selectBaseline(all: List<RunManifest>, current: RunManifest): RunManifest? =
    all.filter { it.backend == "cpu" && it.runId != current.runId &&
                 it.modelIdentity == current.modelIdentity && it.seed == current.seed &&
                 it.steps == current.steps && it.dims == current.dims }
       .maxByOrNull { it.runId }

val verdict = when {
    numericFallback > 0 -> "FAILED_NUMERIC"
    baseline == null -> "NOT_COMPARED"
    relL1 > 0.05 -> "FAILED_DIVERGED"
    openFallback > 0 -> "MIXED_NPU_FALLBACK_OK"
    npuRuns > 0 -> "NPU_OK"
    else -> "CPU_OK"
}
```

验收必须同时满足：`latentFinite && latentNonZero && pngHealthy && (baseline == null ? NOT_COMPARED : relL1 <= 0.05) && numericFallback == 0`。`NOT_COMPARED` 不得映射为 `status=OK` 的质量通过；UI/API 只能显示“已执行、质量未验证”。

生产侧对应合同已在 `core/mediatek-native/.../MediaTekGraphScheduler.kt` 落地并有测试：
`plannedCpuWorkAndNpuSuccessAreNotCountedAsFallback`（18 NPU + 10 量程钉块）、
`missingBaselineStaysNotComparedAndCannotClaimVerifiedQuality`、
`divergedQualityCannotMapToAccepted`、
`cpuRepairLabelRequiresARecordedNpuFailure`。

---

## 7. 对主线（NPUG/DLA）的影响

- 本次仍是 **JIT 第二通道** 的验证；没有触发 NPUG stub/DLA 恢复，也没有我们的 v4 四段链。
- 设备侧 Neuron 适配层真实运行（SDK 9.0.11、`accel=npu run=OK`），但 JIT fp16 域不足是结构性的（V3/V4 无差异、V1/V2 失败、softmax/scale 溢出）。因此 **NPUG 拓扑 + DLA 恢复仍是唯一能拿到完整加速与参考数值的路线**；JIT 只能做有限混合加速，且必须先修 §5 的验收缺陷。
- 本次 CPU latent 可作为第二通道的已知正确基线（SHA `24b47821…`）；NPU latent 是失败样本，可用于验证“基线分歧必须判失败”的回归。

---

## 8. 下一轮设备验收

1. 先修 harness 的 §5.1/5.2/5.3/5.4，重跑 CPU→NPU（同 seed/steps/modelIdentity）：期望 NPU 轮 `compared` 指向 CPU 基线（`24b47821…`），`relL1≈0.95`，verdict=`FAILED_DIVERGED`，`status` 不为 OK。
2. 再跑 NPUG/DLA 主线（v4）：`run_neuron_smoke.py --mode probe/restore/chain`；本轮日志不能替代该结果。
3. 只有 NPUG/DLA 单图 baseline 与 S6 验收通过后，才更新推荐状态。

---

## 9. 修复落地状态（2026-10-31）

1.0.17 harness 源码已定位在 `C:/mca-mtk-spike/mediatek-npu-smoke`，并已应用 §6 的后端分类、基线身份、verdict 映射三组修复：

- `AnimaAcceptance.blockBackend` 区分 `npu` / `cpu-pinned` / `cpu-range` / `cpu-fallback` / `cpu`；
- 每次 latent 写 `latent-<ts>.json` 侧车（backend/runId/seed/steps/sha），基线只选“已确认 CPU + 同 seed/steps + 非当前 run”；
- 无基线 → `NOT_COMPARED`；`latentDivergenceChecked=false` 时 `latentDivergenceOk=false`；失败/未比对返回非 OK status；
- CPU-only → `CPU_OK`；混合全 NPU 成功 → `NPU_OK`；数值/开启 fallback 仍非 NPU_OK。

Harness 单测 `:app:testDebugUnitTest` 已通过（**60 tests, 0 failures**）。1.0.18 APK 已构建：

- `D:/anima-spike/dist-probe/mediatek-npu-smoke-1.0.18.apk`
- versionName **1.0.18 / versionCode 30**
- bytes **651,558,385**
- SHA-256 **c3373d84b6987740b99f0740cf56d04deba0d53ebb299595d2e47d944748375f**
- tester message: `D:/anima-spike/dist-probe/TESTER_MESSAGE-1.0.18.md`

这只修复“结果是否可信”，不修复 JIT fp16 数值本身；NPU 轮预期仍为 `FAILED_DIVERGED`（relL1≈0.95）或 `NOT_COMPARED`（若未先跑 CPU 轮）。NPUG/DLA 主线仍 NOT_RUN。
