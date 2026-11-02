# 玑绘（com.anima.npu）方案逐项照抄对照表（2026-10-30）

要求："先照抄参考项目，做到确定能跑通之后再发给测试者"。
本文逐项列出**参考实现做了什么 / 我们照抄到什么程度 / 还差什么**，以及"确定性"现在能到什么级别。

## 1. 逐项对照

| # | 参考实现（玑绘） | 我们的现状（1.0.11） | 状态 |
|---|---|---|---|
| 1 | 自带 **LiteRT v2.2.0** 运行时（`libLiteRt.so`，694 导出符号） | 用 Maven **LiteRT 2.3.0** 运行时 | ⚠️ 未照抄（ABI 兼容，已实测能加载他们的插件） |
| 2 | **MediaTek 编译器插件 0.1.0**（9.8 MB，源码路径可追溯到他们自建的 LiteRT 树） | 同一份二进制，打进 `assets/litert_ref/`，1.0.11 起**整图管线也用它**（`pluginSource=reference`） | ✅ 已照抄 |
| 3 | **MediaTek dispatch**（3.8 MB） | 同一份二进制，与插件同目录 | ✅ 已照抄 |
| 4 | **AOT 预编译产物**（`.dla` 192–449 MB + `stub`，按 SoC 分目录） | ❌ 没有：需要 PC 端 Neuron SDK 编译 | ❌ **硬缺口** |
| 5 | **w8/w16 量化权重**（编译期定点化，逐张量缩放） | ❌ 没有：我们是 fp32 图 + 插件 fp16 松弛 | ❌ **硬缺口**（这是他们不爆 fp16 的真正原因） |
| 6 | 图**分块**（DiT 切成 4–7 个 chunk 图） | 28 个独立 block 图（每块一次编译） | ❌ 未照抄（省内存，不解决量程） |
| 7 | 可选 options：`aot_compilation_options` / `optimization_hint` / `performance_mode` / `l1_cache_optimizations` / `gemma_compiler_optimizations` / `disable_dla_dir_removal` | 已接入并通过 payload dump **验证哪些键真被解析**；探测器可跑 5 组变体 | ✅ 已照抄（键的用法） |
| 8 | 运行前用 `vMax` 探针确认量程 | 改用"逐块 absmax + fp16 上限"判据（更直接），并钉住超限块 | ✅ 等价实现（更严格） |
| 9 | 逐块数值门禁 / CPU 回退 | 有：健康门禁 + 逐块 CPU 回退 + 输入量程护栏 | ✅ 已有（我们更保守） |
| 10 | 编译缓存 / 权重分页 / 流式预取（`npug::Engine`） | 编译缓存已跑但 `INEFFECTIVE`（插件未实现 `GetCacheKey`）；分页/预取未做 | ⚠️ 未照抄（性能项，非正确性） |

## 2. 为什么"照抄"卡在 #4 / #5

参考项目的 DiT 内部量程会到 **2.0×10⁵**（我们已实测，4 个 seed 一致），**fp16 上限只有 65,504**。
他们能在 fp16 硬件上跑通，靠的是 #5：**编译期把权重定点化（w8/w16），整图走定点/缩放，不吃 fp16 的动态范围上限**。
而 #5 必须在 PC 上用 **MediaTek NeuroPilot SDK / neuron 编译器**离线完成 → 该 SDK 需要厂商开发者账号，**公网无公开下载**（本轮再次确认）。
设备端 JIT 只能给 fp32 图 + fp16 松弛，这就是 12–21 号块必然溢出的原因。

因此"完整照抄"= 拿到 SDK 做 AOT + 量化；在拿到之前，我们的替代方案是 #8/#9 的等价物：
**把超出 fp16 量程的 10 个块钉在 CPU（精确 fp32），其余 18 块跑 NPU**。
PC 参考管线已按这个拆分复现：最终 latent 与纯 fp32 参考的 **relL1 = 2.91e-3（0.29%）**，无 NaN → 视觉一致。

## 3. "确定能跑通"目前能到哪一级

| 层级 | 证据 | 状态 |
|---|---|---|
| 数值可行性 | PC 参考管线按 1.0.11 的拆分跑满 4 步，relL1 2.9e-3，无 NaN/Inf | ✅ 已证 |
| 装配可行性 | 他们的插件 + dispatch + `mediatek` opaque options，在**真实 LiteRT** 里创建环境 / 加载插件 / 应用到编译链路全部 `status=0`，止步于 NeuronAdapter（高通无 MTN NPU，预期） | ✅ 已证（本地高通） |
| 包内容正确 | 1.0.11 APK：621 MiB、versionCode 23、同签名、28 条单测通过、`pluginSource=reference` 与钉块常量在包内 | ✅ 已证 |
| **MTK 硬件实跑** | **本地没有天玑设备**（只有 SM8750P 与小米 14 两台高通），无法在本机执行 | ❌ **不可证** |

⇒ 要在"发给测试者之前"就确定跑通，唯一办法是**把一台天玑设备接到这台电脑**上（USB 调试），我直接跑、反复调到绿再交付。
否则每次都必须经过测试者回传，且我无法本地复现 MTK 侧的任何异常。

## 4. 1.0.11 相对 1.0.10 的改动

- 整图管线（`AnimaPackRunner`）从"用 Maven AAR 自带的 2.3.0 插件"改为**优先用参考插件**：把 `assets/litert_ref/*.so` 复制到 `filesDir/litert_ref/`，再把它作为 CompilerPluginLibraryDir / DispatchLibraryDir；缺失时回退到打包目录。
- 新增证据行：`pluginSource=reference dir=… note=ported_from_reference_apk`（或 `pluginSource=bundled … note=reference_assets_missing`）。
- 其余（钉块 12–21、块输入量程护栏、final 护栏、逐块健康门禁、options 变体矩阵）全部保留。

## 5. 重大发现：他们的 AOT 产物是公开的，并且暴露了我们缺的契约

在参考 APK 里找到模型源，且 **huggingface.co 直连可达**：

```
模型仓：ledisbsb/anima-npu-dimensity9400（公开，sha a7a7abd3，2026-08-29）
  另见 ModelScope: ledi123456789/anima-npu-dimensity9400
  自建源：http://36.138.82.128:9178/models
```

`models/miaomiao8/` 下的实际产物（HF tree API 实测大小）：

| 文件 | 大小 | 含义 |
|---|---|---|
| `ditc1c_w8_w16L1024s4.npug` | 443,597,568 B | DiT 分块图 #1（1024 token / 4 步 / w8_w16） |
| `dva1_w8_w16L1024s4.npug` | 443,598,460 B | 分块图 #2 |
| `dva2_w8_w16L1024s4.npug` | 443,467,472 B | 分块图 #3 |
| `dva3_w8_w16L1024s4.npug` | 443,536,040 B | 分块图 #4 |
| `ctx.bin` | 1,048,592 B | **上下文 = [512, 1024] fp16**（1,048,576 = 512×1024×2 + 16B 头） |
| `modsq16s4_1024_{6..12}.bin` | 6.2–12.5 MB | 逐步 mods，每步 28×3×6144 fp16 ≈ 1.03 MB |
| `vaedec_{64,96,128x72,128x96}.npug` | 26 MB 级 | 按分辨率的 VAE 解码图 |

**这一组尺寸直接把他们的数据契约暴露出来了**，而且和我们 pack 完全同构：
- ctx：1024 维 × 512 token，fp16 ←→ 我们的 `context_pos/neg.f16`（同形状同精度）
- mods：每步 28 块 × 3 × 6144 fp16 ←→ 我们的 `mods.f16`（同形状）
- hidden：1024 token（32×32 patch）× 2048 ←→ 我们的 `block*.tflite` 输入输出

⇒ 我们的导出格式**本来就符合他们的 AOT 契约**；缺的只是"用 SDK 把它编译成 npug/dla"这一步，以及**把整图切成 4 块**（他们 4 块 ≈ 每块 7 层，我们 28 块逐块编译）。

`.npug` 容器头（HTTP Range 实测前 2 MB）：magic `NPUG` + version 1 + 头部长度 0x20E0，随后是逐张量描述记录（例：`{50397440, 1, 1024, 2048, 0x39AF6C16, …}`，维度 1024×2048 + 一个量化 scale 浮点位型），**内部既没有 `TFL3` 也没有 `.dla` 段标记** → 这是他们引擎自己的容器，只能由 `libanima.so`（0.7 MB）加载，不能直接喂给 LiteRT 的 stub+dla 路径。

参考 APK 同时带两份插件：`lib/arm64-v8a/libLiteRtCompilerPlugin_MediaTek.so`（**0.49 MB，JIT 用**）与 `assets/litert/…`（**9.8 MB，AOT/量化用**）。我们用的一直是后者。

## 6. 由此产生的两条可选路线

| 路线 | 做法 | 能"确定跑通"吗 | 代价 / 风险 |
|---|---|---|---|
| **A. 自研 + 混合（当前 1.0.11）** | 我们的 pack + 他们的插件/dispatch + 钉 12–21 到 CPU，其余 18 块 NPU | 数值与装配已证；硬件需一次真机 | 18/28 加速；不依赖第三方权重 |
| **B. 直接照抄引擎与权重** | 下他们的 `libanima.so` + 4 个 npug（≈1.8 GB）+ ctx/mods/vaedec，我们的 App 调他们的引擎 | **最强**：他们的 App 在同一台手机上已验证出图 | 等于把第三方引擎/权重塞进我们的产品；授权与可维护性风险高；约 2 GB 下载 + 逆向 JNI 接口 |

A 是"照抄方法"，B 是"照抄实现"。两者不冲突：B 可以作为**真机基线**（证明链路与机型没问题、给 A 提供逐层对标的参考输出），A 作为可交付产品。
## 7. 定版：1.0.12（路线 A 的最终交付）

| 项 | 值 |
|---|---|
| APK | `D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.12.apk` |
| 大小 / SHA-256 | 651,088,269 B / `6cef6017f5c7374c40eeda067065c9e3faaaf999b9d7f0638e3bd4bbc05735bc` |
| 版本 / 签名 | 24 / 1.0.12，与前版同签名 |
| 单测 | 28 条全过 |

相对 1.0.11 的三点收尾：
1. **参考插件进入整图管线**（1.0.11 起）：`assets/litert_ref/*.so` 复制到 `filesDir/litert_ref/`，作为 CompilerPluginLibraryDir + DispatchLibraryDir；证据行 `pluginSource=reference dir=…`。
2. **参考插件打不开时自动退回自带插件，而不是掉成纯 CPU**（1.0.12）：只回退一次，证据行 `pluginSource=fallback dir=… reason=reference_plugin_open_failed block=N`。
3. **数值安全网不变**：钉住 block 12–21（静态，跨 4 个 seed 稳定）、块输入量程护栏（>60,000 强制 CPU）、final 层护栏、逐块健康门禁与 CPU 回退。

真机一次跑完能落三种结论：
- `NPU_OK` + PNG 5–10 万字节 → NPU 生图跑通（18/28 块加速）；
- `CPU_FALLBACK_OK` + PNG 正常 → 图对但 NPU 没吃上，按 `pluginSource=` / `pinned=` / `UNHEALTHY(` 定位；
- `FAIL` → journal 直接指出哪一块、哪一阶段，按行收窄。

## 8. 关于"确定能跑通"的最终说明

已证：
- 数值：同一拆分在 PC 参考管线复现，relL1 2.9e-3、无 NaN/Inf；
- 装配：参考插件 + dispatch + mediatek opaque options 在真实 LiteRT 中全部 `status=0`，止步于 NeuronAdapter（本机无 MTN NPU，属预期）；
- 包内容：静态核验过（版本/签名/单测/关键字符串/资产）。
不可证（本机限制）：
- MTK 真机执行。本机只有两台高通（SM8750P、小米 14），且用户确认无法接入天玑设备。
⇒ 无法在本机建立硬件级确定性；1.0.12 是"照抄到不依赖厂商 SDK 的最大程度 + 数值/装配双验证"的状态。
## 9. 1.0.13：把"哪块能上 NPU"变成可单测的策略对象

之前这条规则散落在 `AnimaPackRunner` 的手写分支里（还被我脚本化编辑过多次），无法在无设备条件下验证。现在收进单一对象 `Fp16RangePolicy`：

- `CPU_PINNED_BLOCKS = 12..21`（含跨 4 seed 的实测注释）
- `FP16_SAFE_ABSMAX = 60_000f`（比 fp16 上限 65,504 留 8% 余量）
- `decision(blockIndex, npuAvailable, inputAbsMax) -> {NPU | CPU_PINNED | CPU_RANGE, reason}`
  - 静态钉块 → `fp16_range`
  - 输入超限或 NaN → `fp16_range_input`
  - NPU 已禁用 → `npu_unavailable`
  - 否则 → `ok`
- `absMaxOf(ByteArray)` 就地按小端读 fp32（不做 FloatArray 转换，避免整图多分配 ~900 MB）

新增 `Fp16RangePolicyTest`（13 条）锁住这些不变量，其中三条是关键边界：

1. **block 21 必须被静态钉住**：它跨 seed 峰值 58,943 < 60,000，动态护栏不会触发，只有静态集合能拦住它；
2. **pin 集合恰好是 12..21**（11 与 22 都不在集合内）；
3. **NaN 输入按超限处理**（不允许把 NaN 放给 NPU，也不能静默当 0）。

单测总数 **28 → 41**，全过。

交付：`mediatek-npu-smoke-1.0.13.apk`，651,092,077 B，sha256 `050a7eae…08b35c`，versionCode 25，签名与 1.0.12 相同。
**行为与 1.0.12 完全一致**（同常量、同判定），差别只是逻辑被 13 条单测覆盖、且不再是散落的手写分支；两版发哪个都行，优先 1.0.13。
## 10. 1.0.14：补上 libc++_shared 依赖（结论要打折扣，见下）

**发现**：参考插件与 dispatch 的 `DT_NEEDED` 都包含 `libc++_shared.so`，而我们 1.0.13 的 `lib/arm64-v8a/` 里**没有**这个库（我们自己的 JNI 全是静态链接，`libmtk_npu_cprobe.so` 只依赖 libLiteRt/liblog/libdl/libandroid/libm/libc）。

**验证（用纯 C、自身不带 libc++ 的 `dlopen_probe`，模拟 App 的 nativeLibraryDir 搜索路径）**：

| 场景 | 结果 |
|---|---|
| A) 搜索路径里没有 libc++_shared | `DLOPEN=OK` ← **仍然成功** |
| B) 搜索路径里放上参考项目那份 libc++_shared | `DLOPEN=OK` |
| C) 同一条件加载 dispatch | `DLOPEN=OK`（且 dispatch 也导出 `LrtCreateMediatekOptionsFromToml`） |

**A 之所以成功**：这台设备上 **ROM 自带 `/vendor/lib64/libc++_shared.so`**（1,292,440 B），落在默认搜索路径里。
所以这不是一个"已证实的加载故障"，而是**一个隐含的设备依赖**：如果某台设备的 ROM 没带它，参考插件就会加载失败。

**处置**：仍然把参考项目自带的那份 `libc++_shared.so`（1,292,896 B，与插件同源，ABI 完全匹配）放进 `jniLibs/arm64-v8a/`，让 App 自己提供依赖、不再赌 ROM。代价 +455 KB（压缩后）。

**同时纠正上一轮的一句话**：我一度判断"真机上参考插件必然加载失败"，这是**错的**——证据推翻了它，这里如实记录。

交付：`mediatek-npu-smoke-1.0.14.apk`，651,543,141 B，sha256 `9a3a258e…18e3055`，versionCode 26；APK 内已确认 `lib/arm64-v8a/libc++_shared.so` 存在；41 条单测全过。
## 11. 一个被数据推翻的假设：块"内部"动态范围（不采纳，仅留档）

**动机**：我们按块**输出**的 absmax 决定钉哪些块。理论上，若某块输入输出都不超限、但内部中间张量超限，它在 fp16 NPU 上会静默饱和（有限但错误），而这正是 1.0.6 被门禁放过去的原因。所以我去测了内部范围。

**方法**：`experimental_preserve_all_tensors=True` 跑真实链，逐张量取 `|max|`；因为图里有重名张量（`…;1`、`…;2`），改用**按 tensor index** 跨两次不同输入比对，`moved=True` 才算真实中间值。

**结果**：block 2..27 全部"内部超限"，且 `moved=True`（确认不是未初始化残留）：

| block | 输出 absmax | 内部 absmax |
|---|---|---|
| 2 | 3,004 | 9,056,200 |
| 14 | 194,696 | 37,647,278,080 |
| 27 | 18,484 | 288,393,760 |

**为什么不采纳**：
1. block 14 的内部值是输出的 **1.9×10⁵ 倍**。若该值真的流入残差链，fp32 链路自己也会崩——但 fp32 参考完全正常。⇒ 这些大张量位于**归一化之前**，而 LayerNorm 对整体缩放不变（`LN(k·x)=LN(x)`），所以块输出对它们不敏感。
2. 真机数据也否证了"内部大会导致失败"：1.0.6 里 block 0 在 NPU 上的输出 std 与 CPU 参考吻合到 **4×10⁻⁵**，block 0 的内部峰值 25,127 虽低于 65,504（不够有说服力），但 block 2..21 当时也全部判为"健康"，说明 Neuron 编译器会把这类归一化前的中间张量重构/消掉。
3. 真机实际的失败特征（block 22 起 Inf、hidden std 2795）是**输出级**溢出，而输出级正是我们已有判据覆盖的部分。

**结论**：**不改钉块集合**，仍以"块输出 absmax"为准（12–21 钉 CPU）。内部范围数据留档，若将来某个 0–11 的块在真机上出现输出错误，这条数据能立刻指出它内部本来就有 10⁶ 级张量。

证据：`artifacts/.../2026-10-30-internal-range-scan.log`、`2026-10-30-check_internal_real.py`、`2026-10-30-block_internal_range.py`。

**方法论教训**：这类"调试期读中间张量"的测量，必须同时做两件事——(1) 沿真实链跑（喂对输入）、(2) 跨两次输入按 index 比对（排除未写内存）。两者缺一个就会得到看似惊人但无意义的数字；本轮先犯过第一个错，又用第二条把结论从"必须钉 26 个块"拉回到"不用改"。