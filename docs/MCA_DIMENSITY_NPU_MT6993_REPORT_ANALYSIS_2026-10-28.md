# 1.0.5 探针报告分析（2026-10-28 · 设备 MT6993 / vivo V2545A）

来源证据（已归档到本目录）：
- `2026-10-28-MT6993-probe-report.txt`（463,603 B，5271 行）
- `2026-10-28-MT6993-probe-journal.txt`（61,387 B，488 行；SHA-256 `c6aa5614…96c412`，与报告内封条**逐字节一致**）
- 测试者另回传的 `mca-mediatek-npu-evidence-*.txt`（400,008 B）= 同一报告的前 400 KB 截断导出，非新增信息

## 1. 首要事实：这不是黑图那台设备

| 项 | 本轮报告 | 历史黑图 |
|---|---|---|
| SoC | **MT6993（天玑 9500）** | MT6899 |
| 机型 | vivo V2545A（`board=k6993v1_64`, `device=PD2545`） | HONOR SER-AN00 |
| Android | **37（API 37）** | 36 |
| Neuron SDK | 9.0.11（bytecode 与 runtime 版本一致） | 未知 |

⇒ **本报告只能回答"MT6993 上 NPU 能不能用"，不能直接给 MT6899 的黑图定案。** 两者必须分开记。

## 2. 结论表（plan `status` 口径）

| 探针 | 结论 | 关键数字 |
|---|---|---|
| `block00_int8`（出事那张图） | **PASS** | NPU vs PC golden `relL1=7.40e-4`、`nan/inf=0`、`poison=VALUES` |
| `block00_fp16` | **PASS** | `relL1=7.226e-4` |
| `block00_fp32` | **PASS** | `relL1=7.226e-4`（与 fp16 在 7 位有效数字上几乎相同） |
| `op_transpose` / `op_slice` | **PASS** | 1.76e-4 / 1.76e-4 |
| `op_gelu_exact` / `op_gelu_tanh` | **PASS** | 4.17e-4 / 3.49e-4 |
| `op_rmsnorm` | **PASS** | 4.08e-4 |
| `op_batchmatmul` | **PASS** | 3.53e-4 |
| `op_concat_sub` / `op_reshape_mul_add` | **PASS** | 2.33e-4 / 2.32e-4 |
| `op_softmax` | **FAIL** | `nan=18296 inf=40`，最差元素 **-65504**（fp16 上界），产物为垃圾 |
| `op_dequant_fc` | **FAIL（临界）** | `relL1=1.012e-3`，阈值为 `1e-3`（超 1.2%） |
| `mobilenet_v1_0.25_224_quant` | **FAIL** | NPU 编译 `status=504`，输入输出是 **uint8** |
| `block00_int8_full` | NOT_RUN | 设计性：int8 激活输入 |
| `litert_fixture_op_sum_fc` | DIAGNOSTIC_ONLY | 无 PC golden |

`summary planned=16 executed=15 pass=11 fail=3 notRun=1 invalidInput=0`

## 3. 根因（本轮已由设备证据定案）

### 3.1 插件把 fp32 计算松弛成 **fp16**（已证实，不是推断）

三条独立证据同时指向同一结论：

1. **权重精度不影响结果**：`block00_fp32`（252 MB，fp32 权重）与 `block00_fp16`（127 MB，fp16 权重）的 NPU 输出在 7 位有效数字上相同（`relL1` 7.225934e-4 vs 7.225945e-4；输出 `mean/std` 完全相同），而两者相对 PC 金标准都比设备 CPU 差三个数量级（CPU vs PC = 2.98e-7）。
   ⇒ 权重在编译期被统一降到 fp16，与"存 fp32 还是 fp16"无关。
2. **幅度扫描有硬悬崖**：真实 step0 输入整体乘系数后跑 NPU —
   `scale=1` → 正常；`scale=1e-3` → 正常；**`scale=1e3` → 2,097,152 个元素全 Inf**；`scale=4096` → 全 Inf。
   fp16 上界 65504，这正是 fp16 溢出的指纹。
3. 插件串里有 `NeuronModel_relaxComputationFloat32toFloat16` / `Failed to relaxFp32ToFp16`，且本轮日志里**没有任何一条**把 relaxation 显式关掉的记录。

**量级结论**：本图的 fp16 误差约 **7e-4（relL1，单块）**，比 CPU 路径（5.7e-6）差两个数量级，但**单块仍可用**。

### 3.2 存在"编译成功但算子跑空核"的静默错误路径（已抓到原文）

```
E/vpu2_algo: ERROR: [MTK NN] Softmax::GenKernels: Unsupported build combination! Run empty kernel!
```
出现在 `op_softmax` 那次编译附近（该探针只有 1 个算子，日志序列可对齐），而它**之后**仍然
`Partitioned subgraph<0>, selected 1 ops, from a total of 1 ops. resulted in 1 partitions` +
`Replacing 1 out of 1 node(s) with delegate (DispatchDelegate)`，即 **LiteRT 侧报告"完全委派、编译成功"**，但 MTK 编译器自己说"跑空核"。
输出侧对应 `nan=18296 / inf=40 / 最差 -65504`。

⇒ 这就是"`nonCpu=1 fully=1` 却输出垃圾"的**通用机制**：不是回写缺失、不是 buffer 契约，而是**某个算子在目标 SDK 上生成不出 kernel，MTK 用空核顶上，LiteRT 不知情**。

**重要限定**：这是**单元级**证据。同样含 2 个 SOFTMAX 的 `block00` 三种权重格式全部 PASS（149 ops 全进 1 个分区），说明**该 block 的 softmax 形状能正常生成 kernel**；失败的 `op_softmax` 探针用的是缩小版 `[1,16,256,256]`（为了控制资产体积把 token 从 1024 降到 256），与生产形状不同。因此：
- 不能据此说"block 的注意力坏了"；
- 但也不能说"softmax 安全"——**kernel 能不能生成依赖具体形状**，这是必须逐形状验证的风险点。

### 3.3 int8 张量（不是 int8 权重）不被支持

`mobilenet`（IO 都是 uint8）在 NPU 上**编译直接失败** `status=504 kLiteRtStatusErrorCompilation`，同图 CPU 正常。
结合插件串 `Int8 is not supported.`，可以定案：**被拒绝的是 int8/uint8 的 I/O 张量**，而 weights-only int8（IO 仍 fp32）**完全可用**（`block00_int8` PASS，149 ops 全委派）。

### 3.4 SoC 白名单实际上从未生效

每次编译都打 `Starting MediaTek Compilation for 1 subgraphs, soc_model=(null)` — **插件不知道 SoC**，因此那 16 项白名单只在"查询支持型号"接口里用，**不参与编译放行**。这也解释了为什么 MT6899 当初也能编译成功。

## 4. 性能实测（同一 block00，同一输入）

| 权重格式 | NPU 编译 | NPU 单次 run | CPU 单次 run | 单块加速 |
|---|---|---|---|---|
| fp16（127 MB） | 4,954 ms | **71 ms** | 412 ms | **5.8×** |
| fp32（253 MB） | 6,052 ms | 77 ms | 422 ms | 5.5× |
| int8（64 MB） | 2,054 ms | **255 ms** | 435 ms | 1.7× |

**int8 权重对 NPU 是双输**：不但没有任何精度收益（NPU 反正按 fp16 算），单次 run 还比 fp16 慢 3.6 倍（每层多一次 DEQUANTIZE）。**NPU 路径应选 fp16 权重。**

四步全量的推算（28 块 × 4 步 = 112 次 run）：

| 方案 | 总耗时（估） |
|---|---|
| CPU 路径（实测） | 216 s |
| NPU fp16 + 每次重编译（现状缺陷） | ~563 s（比 CPU 慢） |
| NPU fp16 + 每块只编译一次（`release` 缺陷修掉） | **~147 s** |
| NPU fp16 + 持久编译缓存（`kLiteRtEnvOptionTagCompilerCacheDir=18`） | 编译只发生在第一次，稳态仅 ~8 s/四步 |

⇒ 编译复用 + 编译缓存是 NPU 路径能否"快于 CPU"的决定因素；数值正确性不再是最短板。

## 5. 对黑图问题的意义（诚实边界）

- 本轮**没有**复现黑图：同一批 block 在 MT6993 上数值正常、写回正常（`poison=VALUES`，无 `unchanged` 残留）。
- 黑图那台是 **MT6899**，Neuron SDK 版本未知。最可能的解释：**3.2 的空核机制**在它上面命中了某个算子（该机器不支持某 kernel 的组合），于是 silent garbage → latent 退化 → `toByte` 全 0 → 纯黑；而 MT6993 的 9.0.11 能生成对应 kernel。
- 该解释**尚未验证**，需要 MT6899 跑一次同一份 1.0.5 探针。若该机仍在测试者手上，这是成本最低的一步。

## 6. 本轮暴露的探针设计问题（需修）

1. `op_softmax` 用了缩小形状 `[1,16,256,256]`，与生产 `[1,16,1024,1024]` 不同 → 结论不可外推。应改为 `[1,2,1024,1024]`（约 8 MB）等**接近生产形状**的版本。
2. `op_dequant_fc` 的 `relL1=1.012e-3` 落在阈值 1e-3 外 1.2%，与 `block00_int8` 的 7.4e-4 同阶 —— 这是 fp16 噪声而不是缺陷，但说明**逐层单算子比整块更敏感**，阈值应按"reference 是 PC golden 还是 CPU 对"分别校准。
3. 探针报告内层 `RUNNER REPORT` 里写着 `appVersion=1.0.4 versionCode=16`（`ProbeMatrix` 里硬编码），与封套 `1.0.5/17` 不一致，容易误判版本。

## 7. 下一步（对应修复方案）

| 阶段 | 动作 | 状态 |
|---|---|---|
| 阶段 2（MT6899） | 同一份 1.0.5 在 MT6899 上跑一次，验证"空核"假设 | 待设备 |
| 阶段 2（MT6993 全图） | 修 `release` 缺陷 → 用 fp16 权重跑完整 4 步，与 CPU/PC 图对比 | 可立即做 |
| 阶段 3 | 全 28 块 ×4 步逐块 trace，确认误差是否累积到可见差异 | 依赖上一步 |
| 阶段 4 | 数值门禁（`nan/inf`、`std` 健康带、`relL1`）+ 单块 CPU fallback | 设计已就绪 |
| 阶段 5 | 编译复用 + `CompilerCacheDir` | 已有明确入口 |
