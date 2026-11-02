# MCA 天玑 NPU / Anima 根因分析与单次决定性实验（2026-10-28 晚 · 第二版）

> 本文是 `docs/MCA_DIMENSITY_NPU_ANIMA_HANDOFF_2026-10-28.md` 的接续：那份文档给出的
> "int8 嫌疑 + fp32/fp16 探针"方向本轮被**四路独立取证修正**，并已把"一次装一次跑"的
> 探针包做完。**仍然不是 READY 声明。** 仓库本轮只新增文档，未修改任何生产代码。

## 1. 一句话现状

- **设备 CPU 路径 PASS**（不变）：MT6899 上 4 步 3.6 分钟出图，latent 与 PC 参考相对误差 5.7e-6，PSNR 39.75 dB。
- **NPU 路径数值 FAIL**（不变）：28/28 块 `npu=true nonCpu=1 fully=1`，出图纯黑（PNG 2,227 B）。
- **本轮新增**：把"为什么纯黑"从 8 条猜测收敛为 **1 个必须靠设备回答的问题**，并把回答它所需
  的全部仪器（插件内部日志、格式对照、逐算子探针、毒化检测、幅度扫描）打包进了一个 647 MiB 的
  单次探针 APK。**测试者只需装一次、点一次、分享一个 txt。**

## 2. 交接文档中被本轮修正/加强的三条判断

| 交接文档原判断 | 本轮结论 | 依据 |
|---|---|---|
| "int8 weights-only + 逐通道 DEQUANTIZE 不被插件支持"是首要嫌疑 | **降级为次要**（置信度约 20%）。插件对"静态 shape + per-channel + zp=0 + 权重常量"的 dequant 有专门通路；`Int8 is not supported.` 属于另一条元素类型分支，且失败形态是**显式报错回退**，不产生"报加速 + 纯黑" | `analysis/03-plugin-capability.md` |
| "插件的 TRANSPOSE/SLICE 覆盖不足"是次要嫌疑 | **升级为主要嫌疑之一**：`SLICE` 四重否证（无 legalizer 文件 / 无 `Legalize Slice` 日志 / 无 `NEURON_SLICE` / 无失败串）；`TRANSPOSE` 有 legalizer 但全库无 `NEURON_TRANSPOSE`，落地只剩 reshape。模型里有 13 个 SLICE + 10 个 TRANSPOSE | 同上 |
| "LiteRT 2.3.0 不导出分区数，`nonCpu=1` 不能替代数值闭环" | **补强**：`nonCpu/fully` 的确切语义是 `cpu_delegated==0 && undelegated==0`，只证明"每个 op 都被吞进 dispatch 节点"，**不证明 dispatch 执行/写回**；同时发现**分区数其实有日志**（`Replacing %d out of %d node(s) ... yielding %zu partitions`） | `analysis/01-cpp-litert-audit.md` §3/§7 |

## 3. 已排除（不要重复查）

四份报告共列 20+ 条，以下是最容易被反复怀疑的：

1. **Kotlin/管线数学**：CPU 与 NPU 模式的数学代码 100% 相同，唯一差异是传给 JNI 的 accelerator 整数（`AnimaPackRunner.kt:204`、`mtk_runner.cpp:259-261`）。切片偏移、字节序、mods 取段经真实字节数核对**恰好卡在边界、无越界**。
2. **Kotlin 把 NPU 输出当 int8 反量化**：全 Anima 路径零 int8 处理，输出一律按密集 fp32 读。
3. **IO 顺序 / f16→f32 / 缓冲尺寸**：block 模型 5 个输入顺序与绑定一致；证据里每块 `outBytes=8388608` 精确等于 packed size（无 stride padding）。
4. **同步缺失**：`LiteRtRunCompiledModel` 头文件明确同步，`LiteRtLockTensorBuffer` 内部会 sync 已附加的 event。
5. **权重反量化本身有错**：PC CPU 上 int8 版 vs fp32 版 `relMeanAbs=0.577%`、`cosine=0.99998`、无 nan/inf；逐通道 scale 范围健康（2.9e-5~3.2e-3，zp=0）。
6. **fp16 权重不可行**：已用 `FLOAT_CASTING(16bit)` 造出 `block00_fp16.tflite`（126,924,832 B），PC CPU vs fp32 `relMeanAbs=2.2e-7`。
7. **插件与 runtime ABI 不匹配**：插件导出的 17 个 C API 与 runtime dlsym 的 17 个**完全吻合**，同为 NDK r29/clang 21 构建。
8. **fd 加载 / 图包完整性 / VAE / PNG**：前一轮已闭环，本轮无新反证。

## 4. 剩余假设（按可能性排序）

| # | 假设 | 置信度 | 判别方法 |
|---|---|---|---|
| H1 | **NPU 侧按 fp16 松弛计算**（插件 strings 有 `NeuronModel_relaxComputationFloat32toFloat16` / `Failed to relaxFp32ToFp16`），本图中间激活溢出成 Inf/NaN | 高 | 幅度扫描（scale 1/1e-3/1e3/4096）+ 毒化统计的 `nan/inf` |
| H2 | **算子级静默错算**（SLICE / TRANSPOSE / GELU 等），Neuron 自报支持但插件的 legalizer 落地错误 | 高 | 10 个逐算子微探针（NPU vs CPU 同输入） |
| H3 | **dispatch 从未回写输出**（padded buffer 契约不一致；runtime 自带 `MediaTek dispatch API returned contradictory buffer requirements ... instead of 4` 检测串） | 中 | 毒化法：输出缓冲预填 `0xCD`，run 后统计 `unchanged == total` |
| H4 | **SoC 目标不对**：插件有 16 项 SoC 硬白名单，其中没有 mt6899；若设备上报未列出的串则硬失败 4001，但我们的编译却成功了 → 说明实际送入的 SoC 串是别的值，MDLA 版本可能不匹配 | 中 | 日志里 `Starting MediaTek Compilation ... soc_model=%s`、`Compiling for MediaTek architecture: %s` |
| H5 | int8/DEQUANTIZE 权重路径 | 低-中 | `block00_int8` vs `block00_fp16` vs `block00_fp32` 三兄弟对照 |

**为什么本地无法闭环**：x86 PC 没有 MTK NPU；唯一的天玑设备在测试者手上、且没有 adb。前一轮的失误在于"把设备当成只能出图的黑盒"，而真正缺的是**插件自己的诊断日志**。

## 5. 本轮的关键仪器突破：进程级 liblog 钩子

插件 `.so` 不依赖 `libLiteRt.so`，而是静态链接了自己那份 logger，最终走 `__android_log_vprint("litert", ...)`。
因此 LiteRT 的 sink logger 抓不到它，但 **liblog 的进程级 logger 可以**：

```c
__android_log_set_minimum_priority(ANDROID_LOG_VERBOSE);  // API 30+
__android_log_set_logger(my_sink);                        // 进程级，跨 DSO
```

再加上 `kLiteRtEnvOptionTagMinLoggerSeverity = 25`（VERBOSE）把插件自己的静态 logger 级别降下来，
**测试者不需要 adb 就能拿到 `Int8 is not supported.` / `Unsupported SoC model` / 分区统计 / `Failed to add NEURON_*`**。

已在真机上验证（`98a37aa7` / Android 37 / arm64，4/4 marker 全中，含 `__android_log_vprint` 路径，
且 logcat 未被破坏）：`dist-probe/_selftest/liblog-hook-selftest-98a37aa7.log`。

## 6. 单次决定性实验：1.0.4 探针包

| 项 | 值 |
|---|---|
| APK | `D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.4.apk` |
| 大小 / SHA-256 | 678,155,126 B / `cb5c93acbc802cbfdbd89c016994a731e4bc9e5cd94f8131f6a5e40557b9fbeb` |
| 版本 / 签名 | versionCode 16 / 1.0.4；证书与 1.0.3 **逐字节相同** → 覆盖安装，不丢 `filesDir` 的 2.09 GB 图包 |
| 资产 | `assets/probe/**` 71 条目 / 638,052,567 B，0 缺失 0 长度不符；15 个 `.tflite` 全 Stored |
| 测试者操作 | 装 APK → 点 `Run probe matrix` → 分享一个 txt |

### 6.1 探针矩阵（16 个模型，每个都 NPU + CPU 同输入对跑）

- 三个**格式兄弟**：`block00_int8`（出事那张图）、`block00_fp16`、`block00_fp32`。三者 op 序列只差
  `10×DEQUANTIZE` 与权重 dtype，IO/signature 逐字节相同 → **控制变量完美**。吃的是**真实 step0 激活**
  （x 的 std=0.1004，而非 N(0,1)），并与随包的 **PC 金标准**比对。
- 十个**逐算子微探针**：`transpose / slice / gelu_exact / gelu_tanh / rmsnorm / batchmatmul / softmax /
  dequant_fc（真实 q_proj 权重、per-channel 2048 scale）/ concat_sub / reshape_mul_add`。
- 两个对照：`mobilenet_v1_0.25_224_quant`（真正的全 int8 图）、`litert_fixture_op_sum_fc`（历史已知能
  `nonCpu=1` 的图，用来证明"探针链路能测出成功"）。
- `block00_int8_full`（全 int8）设计性 NOT_RUN：输入是 int8，且精度已废（cosine 0.208），不作为画质参考。

### 6.2 每个探针都会输出的判据

```
accel=npu open=FAIL error=…                          → 插件/SoC/编译是否硬失败
accel=npu poisonStats unchanged=/zero=/nan=/inf=/total=  → NEVER_WRITTEN / ALL_ZERO / HAS_NON_FINITE / VALUES
accel=npu|cpu stats mean/std/min/max/nan/inf/zero     → 数值分布
pair maxAbsDiff/meanAbsDiff/relMeanAbs/fp16Equivalent → NPU vs CPU（基线 CPU-vs-PC = 5.7e-6，fp16 舍入 ≈ 4.9e-4）
ref=pc_cpu maxAbsDiff/relMeanAbs                      → 设备 vs PC 金标准
amplitudeSweep scale=1/1e-3/1e3/4096                  → fp16 溢出指纹（只有 4096 能越过 65504）
expectedSanity=PASS|DIVERGES(...)                     → 先证明输入喂对了，否则后面结论作废
--- LOG EXCERPTS ---                                  → 插件原文（soc_model / 分区数 / 失败算子）
```

### 6.3 决策树（一次运行即可落到某一分支）

| 观测 | 结论 | 下一步 |
|---|---|---|
| 所有 NPU 探针 `open=FAIL`（`Unsupported SoC model` / `0 compiler plugins were applied`） | NPU 在这台机器上**根本没被用上**，此前 `fully=1` 是假象 | NPU 路线判死，转 CPU 交付 + 数值门禁 |
| `block00_fp32` PASS 而 int8/fp16 FAIL | **量化/DEQUANTIZE 路径**有问题 | 只能全 fp32（7 GB，不可行）→ NPU 判死或等插件修复 |
| 三兄弟全 PASS 而全量出图仍黑 | 单块没问题、**28 层 × 4 步累积**或 fp16 溢出 | 看幅度扫描 + 逐步统计 |
| 三兄弟全 FAIL 且 `poisonStats unchanged==total` | dispatch **从未回写**（缓冲契约） | 修 buffer type/requirements 或换 runtime |
| 三兄弟全 FAIL 且 `nan/inf > 0` | **数值溢出**（H1 坐实） | 尝试 fp32 强制/降幅度/分段 |
| 某个微探针单独 FAIL | **精确到算子**（H2） | 该算子改用 CPU 侧图外实现，或改写模型规避它 |
| block00_fp16 PASS 且 relMeanAbs < 1e-3 | **fp16 图包可行** | 做 3.5 GB fp16 包 + 跨步句柄复用，NPU 才可能快于 CPU |

## 7. 顺带发现的确定缺陷（与纯黑无因果，但都是真 bug）

1. **每步重编译**：`AnimaPackRunner.kt:753/:831` 每次 run 后 `release(name)`，与 `:757-760` 的注释（"缓存到整轮"）直接矛盾 → 4 步 × 28 块 = 112 次 64 MB 图编译（≈268 s），这是 NPU 比 CPU 还慢的主因。
2. **单文件内嵌包路径必坏**：`AnimaPackRunner.kt:128-131` 把容器 fd 当模型 fd，`mtk_runner.cpp:244-246` 用 `offset=0, size=文件全尺寸` → 模型被当成整个 2 GB zip 解析。必须改传 `nativeEntryInfo()[3]`(dataOffset) 与 `[1]`(uncompressedSize)。当前被 `packSource=files` 掩盖。
3. **异常路径证据被截断**：`Activity:1047-1053` 把 evidence 换成 3 行 `status=FAILED`，前 N 行只留在 progress 文件里，分享出来几乎为空。
4. **缺数值门禁**：`manifest` 里 28 个 block 的 `referenceMean/referenceStd` 从未被使用；`prelude` 抽查（阈值 1% 且只查纯 fp32 图）挡不住 int8 图的问题。建议按块做健康带检查，越界即该块回退 CPU —— 这样"NPU 算错"最多退化成"慢"，永不退化成纯黑。

## 8. 未验证项（诚实标注）

| 项 | 状态 | 风险 | 兜底 |
|---|---|---|---|
| APK 覆盖安装 | NOT_RUN（本机两台 HyperOS 的"USB 安装"开关未开，adb/pm/PackageInstaller 三通道均被拦） | 低（签名已逐字节比对一致；测试者装过 1.0.0–1.0.3） | 装不上时先分享旧证据再卸载 |
| Kotlin 探针驱动运行时 | NOT_RUN | **中**（1333 行新代码，仅编译通过） | 每探针 try/catch；整体失败仍留 progress 文件；三个老按钮仍可用 |
| 610 MB 资产流式读取 | NOT_RUN | 中（`.f32` 被压缩，走 `AssetManager.open()` 流式；`.tflite` 为 Stored） | 单探针读失败只写 NOT_RUN |
| 真天玑 MT6899 NPU | NOT_RUN | — | 本轮全部意义所在 |

## 9. 证据索引

- 仓库外取证报告：`D:\anima-spike\analysis\{01-cpp-litert-audit,02-kotlin-pipeline-audit,03-plugin-capability,04-probe-models,05-probe-app}.md`
- 探针模型与金标准：`D:\anima-spike\probe\`（含 `EXPECTED.json`、`micro/`、`inputs/`）
- 交付物：`D:\anima-spike\dist-probe\`（APK、`TESTER_MESSAGE-1.0.4.md`、构建日志、liblog 自测）
- 历史证据：`artifacts/architecture/2026-10-26-dimensity-anima/`

## 10. 回退

- 仓库侧：本轮只新增本文件，删除即可回退；未改生产代码、未动子模块。
- 外部侧：`D:\anima-spike\dist-probe\`、`probe\`、`analysis\` 可整体删除；`C:\mca-mtk-spike\mediatek-npu-smoke` 的 1.0.3 产物仍在 `dist\`。
- 设备侧：未执行 `git reset --hard` / `git clean`，未卸载任何设备上的应用；两台本地测试机被改过两个开发设置（`install_non_market_apps=1`、`verifier_verify_adb_installs=0`），可随时改回。
