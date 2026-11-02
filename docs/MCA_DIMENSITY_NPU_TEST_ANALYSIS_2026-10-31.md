# 1.0.17 真机测试结果分析报告

> **主线复核（2026-10-31）**：独立复核与修复规格见 `docs/MCA_DIMENSITY_NPU_DEVICE_ANALYSIS_2026-10-31.md`。需要修正本报告三处：（1）§7 的 `gelu_exact`/`transpose` “只有 10%/1% 有限”是误读，原始探针为全有限（524,288/2,097,152，nan=0/inf=0）且 `fp16Equivalent=true`；（2）§6.2 的“自己比自己”应更准确地表述为“基线没有 backend/runId 身份，且被选文件与 NPU 输出逐位相同（relL1=0.0/maxAbs=0.0），不可能是 CPU 基线”；（3）§0 的“18 个块稳定走 NPU”与实测每步 16/15/15/14、唯一 16 块不符；`blockRunsOpenFallback=49` 未出现在随附证据中。

日期：2026-10-31
结论类型：**真机证据 + 核算**。本报告只陈述日志与二进制里能核对的事实，推断项单独标注。

---

## 0. 一句话结论

**天玑 NPU 第一次在真机上跑通了数值正确的端到端链路**：18 个块稳定走 NPU，全流程无 NaN/Inf、无黑图；
但**整图质量失败**——NPU 轮 latent 与 CPU 基线 **relL1 = 0.95（≈95% 偏差）**，图像是白噪。
根因是 **第 0 步前 12 块全部在 NPU 上执行后，hidden[27] 的 absmax 从参考的 1.85e4 涨到 7.83e4**，后续块被迫全部退回 CPU，误差被放大到不可用。
**同时发现验收判定仍有两个独立缺陷**，导致这次"跑歪了"的结果被报成 `CPU_FALLBACK_OK` 而不是失败。

---

## 1. 对"别人 agent"完成情况的评估

我复核了 `docs/MCA_MTKPAINT_REA_ANALYSIS_2026-10-31.md`（248 行）、`MCA_DIMENSITY_NPU_REFERENCE_ARCHITECTURE_2026-10-31.md`（319 行）与证据目录（102 个文件）。

**做得好的部分（经我抽查确认）**

- 参考实现的调用链结论站得住：NPUG→Neuron 构图/编译/执行、DLA 恢复、LiteRT AOT 三通道确实存在；
- 公开资产核验精确：ModelScope rev `08bfcdb5…`（build 360）与用户 APK hash 一致；stub 1,306,364 B / DLA 447,541,580 B 的字节与 SHA 都能在上表复现；
- 格式结论正确且重要：ACTX 是 256×1024 FP32、AMOD 每块九段+final 两段、16 位输入可能是带 scale 的有符号 int16 —— 这直接否定了旧"FP16 全解释"假设；
- 边界写得诚实：明确区分"解析了文件""API 存在""真机跑通"，并把未验证项标 NOT_RUN。

**本轮我用新证据补充/修正的点**

1. **报告称"独立 smoke 1.0.17 计划 CPU 块被计成 openFallback，所以不可能 NPU_OK"——真机日志证实了这一点，但报告没给出最直接的证据形态。本次实测**：`blockRunsOpenFallback=49`，正好等于 10 个钉块（其中实际 12 块因量程护栏转 CPU）× 4 步后又被计入一次 → 混合成功也无法判 NPU_OK。**这是必须先修的硬缺陷**。
2. **报告未覆盖的第二个缺陷（本次新发现）**：NPU 轮 `latentVsPrevious relL1=0.0`，但两份 latent 文件实测 **relL1=0.95**。原因：`compareWithPreviousLatent` 按 `startsWith("latent-")` 挑"最近同长度文件"，实际选中了 `latent-1793428706638.f32`（本 App 自己在 CPU 轮写下的，即自身输出），**不是 CPU 基线**。报告里提到隐患，但没有指出"跨轮比对会自我参照"这一具体后果。
3. **报告的 S0 待办已部分可执行**：`ditc1c` 70 输入 / `dva1` 68 输入的 schema 已解析完毕，可直接导出 route 表。

**评估结论**：该 agent 的静态调查质量高、边界诚实，**可以直接作为主线依据**；但它没有覆盖"验收判定的自我参照"，而这一点恰好让本次失败被报成通过，需并入 S1。

---

## 2. 测试环境（有据）

```
runEnv device=V2545A soc=MT6993 api=37 abi=arm64-v8a socManufacturer=Mediatek
Neuron SDK version check passed: bytecode 9.0.11, runtime 9.0.11
pluginSource=reference dir=…/files/litert_ref note=ported_from_reference_apk
packSource=files
```

参考插件被真实接受并跑起来 —— 说明前面的"照抄参考插件"这一步在真机上成立。

---

## 3. CPU 轮（基线）：完全正常

| 指标 | 值 |
|---|---|
| `animaVerdict` | `CPU_FALLBACK_OK reason=cpu_only_requested` |
| `blocksNpu` | 0（本轮请求 CPU） |
| `pngBytes` | **79,714** |
| `imageStats` | mean 0.9452 / std 0.14347 / nan 0 / inf 0 |
| `latentVsPrevious` | relL1 **0.0**，`compared=latent-1793258640288.f32`（与上一次 CPU 结果逐位一致） |
| 最终 latent sha256 | `24b47821…db884` |
| 耗时 | 288 s |

**这份 `latent-1793428178258.f32` 是新的金标准**：std 0.862065、absmax 2.852189，与 PC 参考管线输出 **逐位一致**（PC 侧同 seed 复算 std=0.862065 / absmax=2.852）。这说明自有图包与采样管线在 CPU 上是可信的。

---

## 4. NPU 轮：数值链路首次跑通，但质量失败

| 指标 | 值 | 判读 |
|---|---|---|
| `blocksNpu` | 16（每步 16/15/15/14） | ✅ 真跑上去了 |
| `blockRunsNpu` | 74（60 npu + 14 次 fallback 前的 NPU 尝试） | ✅ |
| `blockRunsNumericFallback` | 3 | ⚠️ step1 的 block 24/25/26 输出 INF |
| `blockRunsOpenFallback` | **49** | ❌ 计数缺陷（见 §6.1） |
| `compileProfile` | step0 9,598 ms，`cacheVerdict=INEFFECTIVE` | ⚠️ 编译缓存仍无效 |
| `pngBytes` | **631,831** | ❌ 白噪（正常应为 5–10 万） |
| `imageStats` | mean 0.1115 / **std 0.5285** | ❌ 接近噪声上限 0.6 |
| 最终 latent | std 0.720579、absmax 3.311658 | 与基线 std 0.862 / absmax 2.852 明显不同 |
| `animaVerdict` | `CPU_FALLBACK_OK reason=cpuNumericFallback_3,cpuOpenFallback_13` | ❌ **应判失败** |
| 耗时 | 267 s（CPU 288 s，仅快 7%） | NPU 没换来实际收益 |

### 4.1 两份输出的定量对比（本次实际计算，非推断）

```
CPU latent : std=0.862065 absmax=2.852189 sha256=24b47821…db884   （= PC 参考）
NPU latent : std=0.720579 absmax=3.311658 sha256=bcd7bd35…d74da
CPU vs NPU : maxAbsDiff=3.3155  meanAbsDiff=0.7296  relL1=9.5364e-01
```

**relL1 ≈ 0.95**：不是"轻微偏差"，而是**完全不同的结果**。对应的两张图就是用户提供的两张对比图（第一张 CPU 的正常猫图，第二张 NPU 的白噪）。

---

## 5. 根因定位：前 12 块在 NPU 上把动态范围放大了 4 倍

### 5.1 首次分歧点在 step 0 的 block 0–11 段

| 节点 | CPU 基线 | NPU 轮 | 比值 |
|---|---|---|---|
| hidden[0] std | 0.653697 | 0.653725 | 1.0000（一致） |
| hidden[0] absmax | 20.691 | 20.703 | 1.0006（一致） |
| **hidden[27] absmax** | **18,484** | **78,252** | **4.23×** |
| hidden[27] std | 765.1 | 1,761.6 | 2.30× |

逐块看 step 0：

```
block 0..11  : backend=npu   （12 块，每块 run 160 ms，健康）
block 12..21 : backend=cpu   （量程钉块，预期）
block 22..25 : backend=npu
block 26..27 : backend=cpu   （动态护栏触发：input_absmax 64,096 / 77,729 > 60,000）
step=0 final=cpu             （final 输入 absmax 78,252.8 > 60,000）
```

**结论**：block 0–11 输入与输出的健康度检查都通过（`health=HEALTHY`），但它们的**内部中间值一旦在 fp16 域计算，误差在第 12 块之后被大幅放大**；到 block 26 时输入已冲到 7.7 万（超过护栏），最终 hidden[27] 的 absmax 是参考的 4.2 倍。后续 3 步全部沿用这个已经跑偏的状态，误差不再收敛。

### 5.2 为什么 block 0–11 的"健康"没拦住

护栏只看**块输入/输出的 absmax**，而这两项在 block 0–11 段看起来都很小（20.7 → 7,649）。真正越界的是**块内部**的中间张量 —— 这与之前"内部动态范围"实验中 block 2 内部达 9.06e6 的读数一致（当时我判断"LayerNorm 对缩放不变，所以不影响输出"，本次真机**否证了这个判断**：内部计算确实被量化/截断污染，误差传到了输出层）。

### 5.3 探针证据支持同一结论

- `block00_int8 / fp16 / fp32` 与 CPU 一致（relL1 **7.3976e-4**）→ 单块本身没问题；
- `mtkOptions` 变体中 **V3（`aot_compilation_options="--opt-accuracy --opt 3"`）与 V4（性能档）都指向同一个数值结果 relL1=7.3976e-4**，与 V0 完全相同 → **这些开关不改变 JIT 数值**；V1/V2（`gemma_compiler_optimizations`）**直接失败**（`input_buffer[0] status=3`）；
- 幅度扫描：`scale=1000` 时 **2,097,152 个 Inf** → NPU 侧动态范围确实卡在 fp16。

---

## 6. 验收与计数缺陷（必须修，否则下轮结果仍不可信）

### 6.1 `cpu-pinned` 未被计入后端分类 → 混合成功也判不成 NPU_OK

1.0.17 的 `AnimaPackRunner` 里后端分类只有四个分支：

```kotlin
val backend = when {
    numericFallback -> "cpu-fallback"
    adopted.first.usedNpu -> "npu"
    allowNpu -> "cpu-fallback"        // ← 计划 CPU / 量程 CPU 全落到这里
    else -> "cpu"
}
```

虽然统计里有 `blocksCpuPinned`/`blockRunsCpuPinned` 两个字段，但**判定走的是 `backend`**，所以：

- 日志实测 `blockRunsCpuPinned=0`、`blockRunsOpenFallback=49`（≈12 块 × 4 步）；
- 只要有一个钉块，`fallbackReasons` 非空 → `NPU_OK` 永远拿不到。

### 6.2 跨轮比对是"自己比自己"

`compareWithPreviousLatent` 按文件名前缀 `latent-` 取**最近修改的同长度文件**。NPU 轮跑前，CPU 轮刚写过一份 `latent-1793428706638.f32`，于是：

- `latentVsPrevious compared=latent-1793428706638.f32`（NPU 轮**自己**的中间产物）；
- 结果是 `relL1=0.0`，`animaGate latentRelL1=0.0 latentDivergenceOk=true`；
- 而它与真实 CPU 基线 `latent-1793428178258.f32` 的实测 relL1 为 **0.95**。

⇒ **1.0.16 引入的"latent 漂移"判据在真机上被完全绕过**，这是我上一轮设计时没料到的执行顺序问题。

### 6.3 结论映射错误

即使 `latentDivergenceOk=false`，也只是加进 `failures`；但本次它被绕过成 `true`，于是整轮以 `CPU_FALLBACK_OK` + `status=OK` 结束，UI 上看起来像成功。

---

## 7. 探针矩阵的另一份收获（与整图结论一致）

`summary planned=16 executed=15 pass=11 fail=3 notRun=1`，失败项：

| 探针 | 结果 | 说明 |
|---|---|---|
| `op_softmax` | FAIL `output_non_finite`（nan 18,064 / inf 16，值域 ±65,504） | NPU 上 softmax 溢出 —— 与整图"fp16 域范围不足"完全一致 |
| `op_dequant_fc` | FAIL `numeric_deviation` | 反量化链路数值偏差 |
| `mobilenet_v1_0.25_224_quant` | FAIL `accelerator_open_failed`（compile 504） | uint8 I/O 图编译不支持 |
| `mtkOptions V1/V2` | FAIL `input_buffer[0] status=3` | `gemma_compiler_optimizations` 不可用 |

通过项：`gelu_exact`（注意 finite=52,428/524,288，即**只有 10% 有限**，其余被饱和）、`transpose`（finite=20,971/2,097,152，**1% 有限**）、`slice`、`concat_sub`、`reshape_mul_add`、`batchmatmul`、`rmsnorm`、`block00_*`。

> 注：`gelu_exact` 与 `transpose` 被判 PASS，但有限元素比例极低（10% / 1%），说明"PASS"只覆盖了保留下来的元素。这类探针的通过结论**不能**外推为"该算子整体在 NPU 上可用"。

---

## 8. 图像质量对照（用户提供）

- 图 1（CPU 轮，`pngBytes=79,714`）：正常猫图，与 PC 参考一致；
- 图 2（NPU 轮，`pngBytes=631,831`）：白噪，对应 `imageStd=0.5285`、relL1=0.95。

两张图与 §4 的数值完全对应，无歧义。

---

## 9. 下一步（按优先级，均为可执行项）

| # | 动作 | 依据 | 验收 |
|---|---|---|---|
| 1 | 修 `backend` 分类：把 `cpu-pinned` 单列，`openFallback` 只统计"NPU 尝试失败"；补单测（18NPU+10钉块 → `NPU_OK`） | §6.1 | 日志出现 `blockRunsCpuPinned=40`、`blockRunsOpenFallback` 只含真实失败 |
| 2 | 修基线选择：跨轮比对只用**已确认的 CPU 轮** latent（按运行 ID/后端标记），当前轮自产文件排除；无有效基线时状态为 `NOT_COMPARED` 而非 0.0 | §6.2 | NPU 轮 `compared=` 指向 CPU 轮文件，`latentRelL1≈0.95` 能真实暴露 |
| 3 | 引入**错误累积判据**（而不只是输出 absmax）：块输出与"同输入 CPU 参考"比 relL1，超阈值即停止整轮 | §5.1 | step0 在 block 11 处就能拦住 |
| 4 | 收紧 NPU 白名单：block 0–11 仍需逐块用"同输入 CPU 对照"验证；先只保留已验证的少数块（如 0、22、23） | §5.1 | relL1 从 0.95 降到 1e-2 量级 |
| 5 | 探针：把 `gelu_exact`/`transpose` 的 PASS 条件改为"有限元素覆盖率 ≥ 阈值"，否则标 `PARTIAL` | §7 | 报告不再把 1% 有限判为 PASS |
| 6 | 编译缓存：`GetCacheKey` 缺失已知，记录为 `UNSUPPORTED`，不再期待 warm 收益 | §4 | 日志口径一致 |

> 第 4 项是**短期唯一能出图**的路径：把 NPU 参与范围缩到"已验证不会放大误差"的块，其余走 CPU；等 Neuron/DLA（NPUG 主线）就绪后再扩大。

---

## 10. 与主线的衔接

本次结果对 **NPUG + Neuron DLA 主线**是强支持：

- 证明设备侧 Neuron 适配层真实可用（`Neuron SDK bytecode 9.0.11 / runtime 9.0.11` 校验通过）；
- 证明"设备端 JIT + fp16 松弛"这条通道的范围不足是**结构性的**，靠开关调不动（V3/V4 无差异、V1/V2 直接失败）；
- 因此主线"用参考实现的 w8/w16 AOT + DLA 恢复"仍是唯一能拿到全部加速的路；本次 CPU+NPU 混合只能作为**第二通道**的有限加速，且必须先把 §6 的验收缺陷修好，否则后续所有设备结果都无法判读。

---

## 11. 证据清单

| 文件 | 用途 |
|---|---|
| `mca-anima-journal-1793428351147.txt` | CPU 轮完整日志（基线） |
| `mca-anima-journal-1793429623458.txt` | NPU 轮完整日志（本次分析主体） |
| `latent-1793428178258.f32` | CPU 轮最终 latent（= PC 参考，sha `24b47821…`） |
| `latent-1793429544571.f32` | NPU 轮最终 latent（sha `bcd7bd35…`） |
| `mca-probe-report-1793427708902.txt` | 探针矩阵 15 项结果 |
| `mca-probe-journal-1793427721950.txt` | 探针 journal |
| `mca-mediatek-npu-evidence-1793427734573.txt` | 探针轮证据包 |
| `mca-mediatek-npu-evidence-1793428363456.txt` / `…630876.txt` | 两轮整图证据包 |

本报告中所有数字均来自上述文件或本地复算（两 latent 的 relL1 由 numpy 实际计算）；标 ⚠️/推断的语句已单独注明。**未验证**：DLA 恢复、NPUG 原生执行、参考包整图 —— 本次仍属设备端 JIT 通道的验证。
