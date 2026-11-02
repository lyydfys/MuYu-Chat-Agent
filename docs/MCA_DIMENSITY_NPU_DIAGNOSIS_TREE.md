# 天玑 NPU 真机结果判读表（1.0.15 冻结版）

拿到测试者回传的 `report` / `journal` / `latent` / `png` 后，按下面这张表走，不需要再猜。

## 0. 先看三行身份

| 行 | 正常值 | 异常含义 |
|---|---|---|
| `runEnv device=… soc=… api=… abi=arm64-v8a socManufacturer=MediaTek` | MT6993 / MediaTek / api≥34 | `socManufacturer` 不是 MediaTek → 装错设备，本轮数据作废 |
| `pluginSource=reference dir=…` | 用参考插件 | `pluginSource=bundled … note=reference_assets_missing` → 资产没进包（不该发生，说明安装包被改过） |
| `pluginSource=fallback … reason=reference_plugin_open_failed` | 不该出现 | 参考插件在这台设备打不开 → 看下一行 `npuBlocks=` |

## 1. NPU 轮（`Run full Anima pack (NPU)`）

### 1.1 成功的形状

```
blocksCpuPinned=10 pinnedSet=12,...,21
blockRunsTotal=112 blockRunsNpu≈72 blockRunsCpuPinned=40
animaVerdict=NPU_OK
pngBytes=50000..100000
latentVsPrevious relL1=1e-3 量级
```

→ **NPU 生图跑通**（18/28 块加速）。此时把 `latentVsPrevious relL1` 与 CPU 轮的同一指标对比，1e-3 量级即视觉一致。

### 1.2 变体 A：图对、但 NPU 完全没吃上

```
animaVerdict=CPU_FALLBACK_OK
blocksNpu=0
npuBlocks=DISABLED at block=0 ...
```

定位：往上找第一条 `block=0 step=0` 前后的 `envStatus` / `blockError`。

| 线索 | 结论 | 下一步 |
|---|---|---|
| `npuBlocks=DISABLED at block=0` + `pluginSource=fallback` | 参考插件与自带插件都打不开 | 回 `pluginSource=` 两行；检查 `libc++_shared.so` 是否在 `nativeLibraryDir` |
| `envStatus=…` 非 0 | 环境创建失败（缺 dispatch / 缺 Neuron 适配层） | 把 `envStatus` 原文发我 |
| `blockError=… "Failed to load NeuronAdapter"` | 设备没有 MTN 适配层（不是我们的问题） | 该机型不支持，换设备 |
| `blockError=… deadline/timeout` | 编译超时 | 记录单块 `compileMs` |

### 1.3 变体 B：NPU 跑起来了，但仍有 NaN / 黑图

```
animaVerdict=FAIL reason=latent_non_finite,png_bytes_2227,image_std_NaN
pinned=cpu reason=fp16_range_input input_absmax=7xxxx limit=60000   ← 关键
```

- 若出现 `reason=fp16_range_input`：说明**动态护栏已在工作**，但仍有块越界 → 把 `input_absmax` 那个块的编号记下来，把它加入静态钉块集合（改 `Fp16RangePolicy.CPU_PINNED_BLOCKS`），重发一版。
- 若**没有** `fp16_range_input`、但 `UNHEALTHY(inf)` 出现在 12..21 之外的块：说明该块的**内部**中间值溢出（输入没超、输出炸了）→ 同样把它钉到 CPU。
- 若 `UNHEALTHY` 出现在 22..27：这十个块参考 absmax 只有 0.7–2.1 万，出现溢出说明运行已漂移 → 检查前面的块是否有 `fallback=cpu` 混入（CPU/NPU 交叉会累积误差，但量级应仍安全），把 journal 发我。

### 1.4 变体 C：跑到一半失败

| 行 | 含义 | 下一步 |
|---|---|---|
| `status=FAILED stage=pack_missing` | 图包没导完 | 重新导入 part1–5 |
| `status=FAILED stage=block index=N` | 该块 CPU 也失败 | 看 `blockError`；可能是图包损坏（对比 sha256） |
| `status=FAILED stage=final` | `final.tflite` 失败 | 看 `finalError`；`final` 已带量程护栏 |
| `status=FAILED stage=vae` | VAE 解码失败 | 与 NPU 无关，是 ONNX 运行时问题 |

## 2. 探针轮（`Run probe matrix`）

| 行 | 判读 |
|---|---|
| `mtkOptions=APPLIED … parsed=1` | 该 TOML 键被参考插件识别 |
| `mtkOptions=APPLIED … parsed=0` | 键不被识别（payload 全零）→ 该变体无意义 |
| `mtkOptions variant=V1 pair vsCpu relL1=…` | `gemma_compiler_optimizations` 是否改变数值 |
| `mtkOptionsSweep … accuracyKnobEffective=YES` | 精度档有效 → 可尝试把热块也放回 NPU |
| `mtkOptionsSweep=NOT_RUN reason=…` | 该段没跑成，其余 16 个探针仍有效 |
| `mtkOptions=FAIL stage=…` | 插件没接受 options，行里写阶段 |

## 3. 每轮结束必须记的三个数

1. `pngBytes`（好坏一眼分：2,227 = 黑图；5–10 万 = 正常）
2. `latentVsPrevious relL1`（NPU vs CPU 的数值差；1e-3 量级 = 视觉一致）
3. `blocksCpuPinned` + `blockRunsNpu`（实际吃到多少加速）

## 4. 一句话总结

**PNG 正常 + `NPU_OK` + `blockRunsNpu≈72` = 目标达成**；否则按 §1.2–§1.4 的三条分支各走一步，不需要再做新实验。

## 1.5 新增（1.0.16）：`latent_diverged_` 分支

1.0.15 及之前，整图判定只看 `pngBytes` 与像素标准差——**一张统计正常但内容错误的图会被标成 NPU_OK**。1.0.16 起加入"与上一轮 latent 的漂移"判据（仅对 NPU 轮生效，阈值 `MAX_LATENT_DIVERGENCE = 5e-2`，PC 预测值 ~3e-3，留 17× 余量）：

```
animaGate … latentDivergenceChecked=true latentRelL1=0.0031 latentDivergenceOk=true limit=0.05 compared=latent-….f32
animaVerdict=NPU_OK
```

若出现：

```
animaVerdict=FAIL reason=latent_diverged_0.1834
```

含义与处置：

| 情况 | 含义 | 处置 |
|---|---|---|
| `latentRelL1` 0.001–0.01 | 正常（量程护栏 + 钉块的预期漂移） | 收工 |
| 0.05–0.3 且 `blocksNpu` 正常 | NPU 块在数值上系统性偏移 | 按 `pinned=`/`UNHEALTHY(` 找块；必要时扩大钉块集合 |
| > 0.3 或大量 `UNHEALTHY` | 与 1.0.6 同类的发散 | 直接看 `hidden` 统计与 `blocksNpu` 轨迹 |
| `latentDivergenceChecked=false` | 只跑了 NPU 一轮、没有基线 | 先跑 CPU 轮再跑 NPU 轮 |

**注意**：CPU 轮不参与该判据（`allowNpu=false` 时不检查），所以先跑 NPU 再跑 CPU 不会被误判。
## 1.6 验收判定现在是可单测的纯函数（1.0.17）

`animaVerdict` 的判定从 `AnimaPackRunner` 内联代码搬进 `AnimaAcceptance.evaluate(Input)`，并新增 **17 条单测**（总数 41 → 58）覆盖真实出现过的每种形态：

| 场景 | 期望 |
|---|---|
| 18/28 块 NPU、无回退、relL1 2.9e-3 | `NPU_OK` |
| 钉块 10 个（`cpu-pinned`） | **不降级**，仍 `NPU_OK` |
| 任一数值回退 | `CPU_FALLBACK_OK reason=cpuNumericFallback_n` |
| 打开回退 | `CPU_FALLBACK_OK reason=cpuOpenFallback_n` |
| 无 NPU 块被采纳 | `CPU_FALLBACK_OK reason=no_npu_block_adopted` |
| CPU 轮 | `CPU_FALLBACK_OK reason=cpu_only_requested`（不做漂移检查） |
| 1.0.6 黑图签名（2227 B + NaN） | `FAIL reason=latent_non_finite,png_bytes_2227,…` |
| 统计正常但 relL1 0.18 | `FAIL reason=latent_diverged_0.1834` |
| 无基线（只跑一轮） | 不检查漂移 |
| 阈值边界（正好 5e-2） | 仍通过 |

**行为与 1.0.16 完全一致**，差别只是判定逻辑被单测锁住。发 1.0.17 或 1.0.16 都可以。