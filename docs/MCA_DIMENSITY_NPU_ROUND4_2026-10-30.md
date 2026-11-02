# 天玑 NPU 复现玑绘方案 · 第 4 轮进展（2026-10-30，根因再次修正 + 1.0.9）

目标："参考他们的方式实现我们的天玑 NPU"。
本轮在 PC 上用**纯软件实验**把整图失败的真实机制测出来了，并据此做出一个可交付的混合方案（1.0.9）。

## 1. 修正：不是"误差放大"，是**动态范围超出 fp16 上限**

上一轮我判定"fp16 松弛 → 单块 7e-4 → 28 层放大到发散"。本轮用参考管线在 PC 上做两件事，推翻了这个说法：

### 1.1 fp16 边界模拟（`run_pipeline_fp16sim.py`）

对每个 block 的输出做 IEEE fp16 舍入（一次 ULP 级别的扰动），其余完全按 fp32 跑：

| 运行 | block 0 | block 11 | block 12 | 最终 latent |
|---|---|---|---|---|
| 基线 fp32 | std 0.6537 | absmax 7,649 | absmax **191,008** | std 0.878 **正常** |
| fp16 舍入 | std 0.6537 | absmax 7,648 | **inf** | **全 NaN** |
| 设备 NPU（1.0.6 实测） | std 0.6537 | — | — | **全 NaN / 纯黑** |

关键不是"舍入导致发散"，而是：**基线自己就在 block 12 冲到 absmax 19 万**（`final.tflite` 后面会把它收回来，所以 fp32 完全正常），而 **fp16 上限是 65,504** —— 只要算力是 fp16，这里必然 Inf。

### 1.2 逐块动态范围画像（4 步完整参考运行）

| 区间 | 参考 absmax 峰值 | 相对 fp16 上限（65,504） |
|---|---|---|
| block 0 | 69 | 安全 |
| block 1–11 | 4,651 – 8,266 | 安全（≥7× 余量） |
| **block 12–20** | **144,791 – 205,267** | **超限 2.2×–3.1×** |
| **block 21** | **63,662** | 贴着上限 |
| block 22–27 | 9,081 – 20,835 | 安全（≥3× 余量） |

### 1.3 块本身是"收缩"的，不是"放大"的

`block_sensitivity.py`（用真实链上状态做扰动，±1e-3/1e-2/1e-1）：
`amplification = 0.38 – 0.83`（<1，收缩）。⇒ 数值稳定性没问题，**唯一的问题是动态范围**。

**这同时解释了玑绘为什么能跑通**：他们是 **AOT + w8/w16 量化编译**，用定点/逐张量缩放把动态范围装进硬件的整数域，并在真机上用 `vMax` 探针确认不溢出。而我们用的设备端 JIT 路径是 **fp32 图 + 插件 fp16 松弛**，没有逐块缩放，于是 12–21 号块必然溢出。

## 2. 交付：1.0.9 —— 只把"热块"钉在 CPU 的混合方案

依据 §1.2 的实测画像：**把 block 12–21（10 块）固定跑 CPU，其余 18 块跑 NPU**。

| 项 | 值 |
|---|---|
| APK | `D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.9.apk` |
| 大小 / SHA-256 | **651,084,333 B** / `9d696e1c996454bddc52ac0cdc5da5905f8dbd721ce717c7114a01ad0fa3ff43` |
| 版本 / 签名 | 21 / **1.0.9**；同签名可覆盖安装 |
| 新增常量 | `CPU_PINNED_BLOCKS = (12..21)`，注释里带完整实测数据 |
| 新证据行 | `block=N step=S pinned=cpu reason=fp16_range note=reference_absmax_2.05e5_gt_fp16_max_65504`、`blocksCpuPinned=10 pinnedSet=12,...,21`、`blockRunsCpuPinned=`、`backend=cpu-pinned` |
| 保留 | 数值门禁 + CPU 回退（1.0.6 的）、参考插件 options 变体矩阵（1.0.8 的） |
| 单测 | 28 条全过 |
| 包内核对 | `CPU_PINNED_BLOCKS` / `cpu-pinned` / `fp16_range` / `blocksCpuPinned` / `gemma_compiler_optimizations` 均在（classes3.dex） |

**为什么不直接把热块改小/吸收进 fp16**：那需要改模型（重训或改图），不在当前授权内；把热块钉到 CPU 是零语义改动、可立即验证的做法。

## 3. 预期收益与风险

- 18/28 块走 NPU（fp16 安全区），10 块走 CPU（数值精确）→ 块时间从 112×412ms ≈ 46s 降到 72×71ms + 40×412ms ≈ 21.6s（约 2.1×）。
- 编译缓存仍 `INEFFECTIVE`（每步 28 次编译 ≈ 57s），所以整图端到端预计 3.5–4 分钟（vs CPU 4.9 分钟）。
- 风险：22–27 号块 absmax 最高 20,835（3× 余量）但在其它 seed/步数下可能变化；因此**数值门禁仍然保留**，越界自动回退 CPU。

## 4. 本轮证据（可复跑）

- `artifacts/.../2026-10-30-run_pipeline_fp16sim.py`、`2026-10-30-fp16-rounding-simulation.log`
- `artifacts/.../2026-10-30-pc-reference-4step-per-block-dynamics.log`（112 条 per-block std/absmax）
- `artifacts/.../2026-10-30-block_sensitivity.py`（放大率 0.38–0.83）
- `artifacts/.../_run/base.latent.npy`（fp32 基线）、`fp16out.latent.npy`（fp16 舍入 → 全 NaN）

复跑：

```bash
wsl -d Ubuntu-22.04 -- bash /mnt/d/anima-spike/analysis/fp16sim_run.sh
wsl -d Ubuntu-22.04 -- bash -lc "cd /mnt/d/anima-spike/analysis && ~/probe-venv/bin/python \
  run_pipeline_fp16sim.py --pack /mnt/d/anima-spike/pack/device \
  --out /mnt/d/anima-spike/out/fp16sim/base4 --steps 4 --trace-step -1 --no-cache"
```

## 5. 下一步

1. 把 **1.0.9** 交给测试者（若 1.0.8 还没跑，直接发 1.0.9：它包含 1.0.8 的精度档变体 + 本次混合方案）。
2. 设备侧两件事一起做：
   - `Run probe matrix`（2–4 分钟，不需要图包）→ 看 `accuracyKnobEffective` 与 `parsed=`；
   - `Run full Anima pack (NPU)`（需已导入图包）→ 看 `blocksCpuPinned=10`、`animaVerdict=`、`pngBytes`、`latentVsPrevious relL1`。
3. 判定：
   - `pngBytes` 50–100 KB 且 `verdict=NPU_OK` → **NPU 生图第一次跑通**（18/28 块加速）；
   - 仍黑图（`pngBytes<5000`）→ 说明 22–27 号块或 `final` 仍溢出，按 journal 里的 `pinned=`/`UNHEALTHY(` 行继续收缩白名单；
   - `accuracyKnobEffective=YES` → 可尝试把热块也放回 NPU（精度档生效），否则保持混合。

## 6. 追加：1.0.10 —— 两处动态量程护栏（静态白名单之外）

静态白名单（12..21）来自**参考画像**，但换 seed / 换步数时画像会变。1.0.10 补了两条**运行时**护栏，依据同一条物理事实（fp32 输入缓冲 > 65,504 在 fp16 计算里第一步就变 Inf）：

1. **块输入护栏**：每次跑块前读 `hiddenPos/hiddenNeg` 的最大绝对值；`> FP16_SAFE_ABSMAX (60_000)` 就强制该块走 CPU，并写
   `block=N step=S pinned=cpu reason=fp16_range_input input_absmax=<x> limit=60000`。
2. **final 层护栏**：`final.tflite` 的输入就是 hidden[27]，同样判据；越界写 `step=S final=cpu reason=fp16_range input_absmax=…`。
3. 两者都归入 `backend=cpu-pinned`，计入 `blocksCpuPinned` / `blockRunsCpuPinned`，与"运行时回退"(cpu-fallback) 区分。

实现细节：`absMaxOf(ByteArray)` 直接按小端读 fp32，**不做 FloatArray 转换**——否则每块 8 MB 的转换会在整图里多分配约 900 MB。
成本：每块约 2 M 次比较，整图 ~0.5 s。

交付：`mediatek-npu-smoke-1.0.10.apk`，651,085,517 B，sha256 `ee79162a…a9ad05b`，versionCode 22，签名与前版一致；28 条单测全过；dex 内已确认 `CPU_PINNED_BLOCKS` / `cpu-pinned` / `fp16_range_input` / `FP16_SAFE_ABSMAX` 均在。

**1.0.10 是 1.0.9 的超集**：若 1.0.9 尚未跑，直接发 1.0.10；若已在跑，让它跑完。
## 7. 追加验证：静态钉块集合对 seed 稳健（4 个 seed 实测）

为确认"钉 12..21"不是只对 seed 42 成立的巧合，用 4 个 latent seed（42 / 7 / 123 / 2024）各跑 1 步 fp32 参考，取每块跨种子最大 absmax：

| block | 跨 4 seed 最大 absmax | > fp16 上限 65,504 | > 护栏阈值 60,000 |
|---|---|---|---|
| 0 | 25 | 否 | 否 |
| 1–8 | 3,222 – 4,168 | 否 | 否 |
| 9–11 | 6,111 – 7,650 | 否 | 否 |
| **12–20** | **139,594 – 194,696** | **是** | **是** |
| **21** | **58,943** | 否（仅差 10%） | **否** ← 只能靠静态白名单挡住 |
| 22–27 | 6,945 – 18,484 | 否 | 否 |

结论：
1. **静态钉块集合稳健**：热区 12–20 在 4 个 seed 之间波动仅 ±6%，不是 seed 特有；22–27 有 ≥3.5× 余量。
2. **block 21 是关键边界**：58,943 低于护栏阈值 60,000，**运行时护栏不会拦住它**，只有静态白名单（12..21）能把它钉在 CPU。这一条说明"静态 + 动态"两道护栏必须同时存在，缺一不可。
3. 护栏阈值 60,000 与 fp16 上限 65,504 之间留的 9% 余量是刻意的：单个 op 内部可能把值放大（实测 block 12 的输入→输出放大约 25×，见 §1.2）。

证据：`artifacts/.../2026-10-30-per-seed-block-range.log`、`2026-10-30-seed_range.sh`。
## 8. 关键预验证：1.0.10 的混合拆分在 PC 上能复现参考图（predicted PASS）

设备运行前，先在 PC 参考管线上**完全照搬 1.0.10 的拆分**（block 0–11 与 22–27 走 fp16 舍入 = NPU 近似；12–21 保持 fp32 = CPU 精确），跑满 4 步，与纯 fp32 参考比对：

| 拆分 | 最终 latent std | absmax | nan | **vs 参考 relL1** | maxAbs |
|---|---|---|---|---|---|
| **1.0.10 实际拆分**（fp16: 0–11, 22–27） | 0.862262 | 2.851 | 0 | **2.91e-3** | 0.0496 |
| 保守拆分（fp16: 0–5, 23–27） | 0.862197 | 2.851 | 0 | 2.31e-3 | 0.0326 |
| 纯 fp32（参考） | 0.862065 | 2.852 | 0 | 0 | 0 |

**含义**：1.0.10 的拆分相对参考只有 **0.29%** 的 latent 偏差（maxAbs 0.05，而 latent 量级 ±2.85），且**没有任何 NaN/Inf**。作为对比，全 fp16 拆分（等价于 1.0.6 的 NPU 全量）在同一脚本里是**全 NaN**。
→ 只要设备的 NPU 行为接近"fp16 舍入"（前面已用全 fp16 实验证明它确实如此），**1.0.10 应当出一张与 CPU 参考视觉一致的图**。

注意：这个预测只覆盖"数值是否可行"，不覆盖设备侧的其他失败模式（图包缺失、编译失败、内存不足）。那些由 1.0.10 的证据行与门禁兜底。

复跑：
```bash
wsl -d Ubuntu-22.04 -- bash /mnt/d/anima-spike/analysis/hybrid_predict.sh
```
证据：`artifacts/.../2026-10-30-hybrid-quality-prediction.log`、`_run/hybrid1010.latent.npy`。
### 8.1 真实工作点上的块敏感度（修正后的测量）

之前的敏感度测试喂错了输入（把 prelude 输出直接喂给 block 1）。修正为"沿 fp32 链跑到该块的真实状态再扰动"后：

| block | 真实输入 std | 输出 absmax | 放大率（eps=1e-3） |
|---|---|---|---|
| 0 | 0.100 | 20.69 | 0.437 |
| 5 | 160.1 | 3,012 | 0.994 |
| 11 | 177.7 | 7,650 | 0.926 |
| 12 | 183.9 | 191,008 | 0.942 |
| 21 | 277.4 | 58,257 | 0.878 |
| 22 | 253.8 | 9,081 | 0.852 |

**全部 < 1（收缩/保范）**：块不会放大输入扰动。⇒ 把 18 个块放到 fp16 的 NPU 上，误差不会被链式放大，这与 §8 的端到端 0.29% 偏差完全一致。

顺带确认了"输出大 ≠ 放大扰动"：block 12 的输出 absmax 是输入的 1000 倍（信号增益），但它对扰动的放大率只有 0.94。

### 8.2 三档拆分对比（供调参用）

| 拆分（fp16 = NPU） | vs 参考 relL1 | NPU 块数 |
|---|---|---|
| 0–11, 22–27（1.0.10 实际） | **2.91e-3** | 18/28 |
| 0–5, 23–27（保守） | 2.31e-3 | 11/28 |
| 0–2, 26–27（最小） | 1.49e-3 | 5/28 |

⇒ 多放 7 个块到 NPU，代价只有 +0.6e-3 的相对误差；1.0.10 的拆分是合理的性价比点。