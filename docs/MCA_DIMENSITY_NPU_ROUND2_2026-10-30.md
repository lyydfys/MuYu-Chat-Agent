# 天玑 NPU 复现玑绘方案 · 第 2 轮进展（2026-10-30，1.0.7 已构建）

目标："参考他们的方式实现我们的天玑 NPU"。
本轮产出：**把参考实现的"可编程 MediaTek options"通道接进我们的探针 App，并构建出 1.0.7**，用于一次真机判定 `relax_fp32_to_fp16` 是否有效。

## 1. 本轮完成（已构建、已验证）

| 项 | 内容 | 状态 |
|---|---|---|
| 参考插件入包 | `assets/litert_ref/libLiteRtCompilerPlugin_MediaTek.so`（9,813,072 B，sha256 `6414d100…`）、`assets/litert_ref/libLiteRtDispatch_MediaTek.so`（3,782,064 B，sha256 `13471a68…`） | PASS（APK 内已确认） |
| 新增 JNI | `Java_..._ProbeMatrixRunner_nativeRunnerOpenRef(modelPath, refLibDir, accelerator, toml)`（另导出一个 `..._ProbeMatrix_...` 别名，双保险） | PASS（`nm -D` 已确认符号在包里） |
| options 装配 | `dlopen(refLibDir/libLiteRtCompilerPlugin_MediaTek.so)` → `LrtCreateMediatekOptionsFromToml` → `LrtGetOpaqueMediatekOptionsData` → `LiteRtCreateOpaqueOptions("mediatek",…)` → `LiteRtAddOpaqueOptions`；失败只写 `mtkOptions=FAIL stage=… status=…` 并继续无 options 编译 | PASS（编译通过；设备待验） |
| 变体矩阵 | `ProbeMatrix` 末尾追加 `mtkOptions variant=R0..R4`（R0 默认 / R1 `relax_fp32_to_fp16=false` / R2 +`neron_sdk_version=2` / R3 +性能档 / R4 `aot_compilation_options="--opt 0"`），每个都给 `npuOpen/compileMs/runMs/nan/inf/std/relL1/verdict` | PASS（编译通过） |
| 幅度扫描 | 对最佳变体做 `scale ∈ {1, 1e3}`，判据：现状 `scale=1e3` 是 2,097,152 个 Inf，若关掉松弛则应变成有限 | PASS（代码就位） |
| 版本 | versionCode **19** / versionName **1.0.7** | PASS |
| 单测 | `testDebugUnitTest` → **28 条全过**（BlockHealth 10 + ProbeVerdict 17 + Journal 1） | PASS |
| APK | `D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.7.apk`，**651,082,053 B**，sha256 `beb14ceb1d55466baa9a58da3ef2a9c2ba5dc6f0d64018e282ceb505dfa3195e`，签名与 1.0.3 相同（覆盖安装不丢图包） | PASS |
| 探针资产 | `assets/probe/**` 71 条目 / 638,052,567 B 保持不变 | PASS |

## 2. 判定口径（真机一次跑完就能落分支）

看 4 行：

```
mtkOptions variant=R0 ... relL1=...            # 参考插件 + 默认
mtkOptions variant=R1 ... relL1=...            # relax_fp32_to_fp16 = false
mtkOptions variant=R2 ... relL1=...
mtkOptions variant=R2 amplitude scale=1000.0 ...verdict=...
```

- **有效**：`R1`/`R2` 的 `relL1` 比 `R0` 小 ≥10×，或 `scale=1000` 不再 `ALL_NON_FINITE`
  → 直接进 4 步整图（用现有 int8 图包），看 `hidden[27]` 的 std 是否回到 ~1 量级、`pngBytes` 是否回到 50–100 KB。
- **无效**：`R1`/`R2 ≈ R0` → 该开关不改变 Neuron 行为，转 AOT（PC 端 Neuron SDK 预编译，当前无 SDK 权限，是硬阻塞）。
- **`mtkOptions=FAIL stage=…`**：说明我们的 2.3.0 runtime 不接受"参考插件（含参考 dispatch）"这一组合；下一步先只换编译器插件、保留我们的 2.3.0 dispatch（见 §4）。

## 3. 本轮踩到的两个坑（写给下一位）

1. `--offline` 构建失败：`No cached version of com.android.tools.build:aapt2:8.13.2-14304508 available for offline mode`。**去掉 `--offline` 即可**（1.0.7 就是这样构建成功的）。
2. 一次偶发的 `:app:checkDebugAarMetadata` 失败（`Cannot invoke java.util.List.get(int) because "path" is null`），重跑即恢复——是并发/瞬时状态，不是代码问题。**不要与其它 Agent 同时跑 Gradle**。

## 4. 下一步（下一轮）

1. 把 1.0.7 交给测试者跑一次探针矩阵（**不需要图包**，2~4 分钟）。
2. 若 `mtkOptions=FAIL` 出现在 environment/dispatch 阶段：把 `DispatchLibraryDir` 改回 `applicationInfo.nativeLibraryDir`（我们 2.3.0 的 dispatch），只让编译器插件指向 `cacheDir/litert_ref`——插件与 dispatch 混用通常可行（都以设备上的 Neuron 9 为准）。
3. 若 `relax_fp32_to_fp16=false` 有效：立刻跑 4 步整图（1.0.7 的 NPU 全量入口仍在），用 `blocksNpu / hidden 统计 / pngBytes / latentVsPrevious` 判 PASS。
4. 若无效且要走到 AOT：需要 MediaTek NeuroPilot SDK（Ubuntu 22.04 + Bazel 7.4.1），当前**无下载权限**；在此之前只能继续在设备端 JIT 路线上找开关（例如 `aot_compilation_options` 组合、`gemma_compiler_optimizations`）。

## 5. 诚实边界

- `relax_fp32_to_fp16` 是否真的改变 Neuron 编译行为，**只能由 MT6993 真机回答**；本轮只证明了"通道打通 + 选项可挂 + 请求能发到插件"。
- 参考插件是第三方 APK 里提取的二进制（Apache-2.0 的 LiteRT 构建产物），仅用于研究验证；**产品化必须自己从 LiteRT 源码构建**。
- 本轮新增代码只加行、不改既有 16 个探针的输出，最坏情况是 `mtkOptionsSweep=NOT_RUN reason=…`，不影响已有结论。

## 6. 追加：本地真机预验证（把"最可能失败的那一环"先打掉）

1.0.7 的组合是"**我们的 2.3.0 runtime + 参考插件 + 参考 dispatch + `mediatek` opaque options**"。这套混搭最怕的是 ABI/版本门禁直接把插件拒掉。用独立 CLI（`2026-10-30-mtk_ref_compile.cpp`）在本地高通机上跑了一遍**真实编译链路**：

```
ENV     status=0 (kLiteRtStatusOk)
MODEL   status=0 (kLiteRtStatusOk)
ACCEL   status=0 (kLiteRtStatusOk)
MTK_OPTIONS create=0 get=0 id=mediatek payload=...   createOpaque=0 addOpaque=0
INFO    Loaded plugin .../libLiteRtCompilerPlugin_MediaTek.so
INFO    Initialize plugin ...   Registered 0 transformations
INFO    SoC model name is not specified
ERROR   libneuron_sys_util.mtk.so not found          ← 高通机预期，止步于此
COMPILE status=504 (kLiteRtStatusErrorCompilation)
```

结论：
- **版本/ABI 门禁通过**——参考插件（ver 0.1.0）被我们的 2.3.0 runtime 正常加载、初始化、走到 NeuronAdapter 阶段，没有出现"API version too new for compiler plugin"之类拒绝；
- **options 链路在真实 runtime 里成立**：`LiteRtCreateOpaqueOptions`+`LiteRtAddOpaqueOptions` 均返回 0；
- 唯一失败点是 Qualcomm 没有 MTN NPU 适配层（`libneuron_sys_util.mtk.so`），与我们的组合无关。

⇒ 1.0.7 在 MT6993 上最可能的失败模式已从"插件被拒"降为"`relax_fp32_to_fp16` 不改变行为"，判定会更干净。

证据：`artifacts/architecture/2026-10-26-dimensity-anima/2026-10-30-ref-stack-compile-test.txt`（59 行）、`2026-10-30-mtk_ref_compile.cpp`。

