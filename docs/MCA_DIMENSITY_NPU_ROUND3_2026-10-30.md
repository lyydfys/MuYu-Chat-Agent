# 天玑 NPU 复现玑绘方案 · 第 3 轮进展（2026-10-30，1.0.8）

目标："参考他们的方式实现我们的天玑 NPU"。
本轮最重要的事：**推翻了自己上一轮的一个错误判断**，并据此把实验改成正确的开关，构建出 1.0.8。

## 1. 本轮纠正的错误（这是关键）

上一轮我根据 `llvm-strings` 在参考插件里看到 `relax_fp32_to_fp16`，就认定它是"关掉 fp16 松弛"的 TOML 键，并把它做进了 1.0.7 的 R1/R2 变体。

本轮用 `LrtGetOpaqueMediatekOptionsData` 把解析后的 payload 抓出来逐字节对比（`mtk_opts_dump`，真机）：

| TOML | payload 前 20 字节（ASCII） | 判定 |
|---|---|---|
| `neron_sdk_version = 2` | `neron_sdk_version =` | **被接受** |
| `gemma_compiler_optimizations = true` | `gemma_compiler_optim` | **被接受** |
| `aot_compilation_options = "--opt 0"` | `aot_compilation_opti` | **被接受** |
| `optimization_hint = 1` | `optimization_hint = ` | **被接受** |
| `performance_mode = 3` | `performance_mode = 3` | **被接受** |
| `l1_cache_optimizations = true` | `l1_cache_optimizatio` | **被接受** |
| **`relax_fp32_to_fp16 = false`** | `0000000000000000...`（全零） | **不被接受** |
| `zzz_unknown_key = 7`（对照） | `0000000000000000...`（全零） | 不被接受 |

⇒ 该插件**不解析** `relax_fp32_to_fp16`；那个字符串位于 ELF 段表之外的 **9.3 MB 附加符号区**（不是 .rodata，不是 TOML 键表），是符号名而不是配置键。
**如果 1.0.7 被跑，R1/R2 会等价于 R0，我们就会把"键无效"误判成"关掉松弛没用"。**

## 2. 由此得到的机制性收获

1. `LrtCreateMediatekOptionsFromToml` **不做即时解析**：它把 TOML 文本原样存进 payload，返回 `kLiteRtStatusOk`；
   真正的解析发生在**编译时**（`litert_toml_parser.cc`）。所以"创建成功"不代表"键被识别"。
2. 唯一可靠的判据是 payload 首字节是否为 0（被识别时 payload 里就是 TOML 文本）。
   → 已在 native 里固化为证据行：`mtkOptions=APPLIED ... parsed=1|0`（`parsed=0` 还会附
   `note=no_recognised_key_keys_are_silently_ignored...`）。
3. 参考插件里所有**性能/编译档位**键都被接受，其中唯一可能影响数值的是
   `gemma_compiler_optimizations`（插件会给 Neuron 传 `--option-bundle=gemma-decode-accuracy`，
   即 `--opt-accuracy --opt 3`）与 `aot_compilation_options`（编译参数，JIT 下可能被忽略）。

## 3. 本轮交付：1.0.8（已构建并核验）

| 项 | 值 |
|---|---|
| APK | `D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.8.apk` |
| 大小 / SHA-256 | **651,082,305 B** / `e2182189d52c67a59b6c8ab2145ed499dc473302e856af15a7b8bcefe960ac52` |
| 版本 / 签名 | 20 / **1.0.8**；证书与 1.0.3 相同（覆盖安装不丢图包） |
| 变体集（改成被验证的键） | `V0` 空 / `V1` `gemma_compiler_optimizations=true` / `V2` +`neron_sdk_version=2` / `V3` `aot_compilation_options="--opt-accuracy --opt 3"` / `V4` `optimization_hint=1 + performance_mode=3 + l1_cache_optimizations=true` |
| 汇总行改名 | `accuracyKnobEffective=YES|NO|UNKNOWN` + `bestVariant=` |
| 单测 | 28 条全过；dex 内已确认 `gemma_compiler_optimizations`、`accuracyKnobEffective`、`nativeRunnerOpenRef` 均在；`.so` 内已确认 `parsed=` |
| 证据行 | `mtkOptions=APPLIED ... parsed=1`，幅度扫描仍只在最佳变体上做 |

## 4. 本轮踩到的坑（写给下一位）

1. **磁盘写满**（D: 只剩 0.24 GB）导致 Copy-Item 失败、`apksigner` 报 FileNotFound。
   已清理 4.7 GB：删掉 `anima-pack-0.6.0*`（旧分片/整包 4 GB，0.6.1 才是分发用的）、
   1.0.4 APK、临时 dex/反汇编文件。**以后构建前先看 `Get-PSDrive D` 剩余空间。**
2. **PowerShell 文本改写会毁掉非 ASCII**：`Get-Content -Raw` + `Set-Content -Encoding UTF8`
   把源码里的 `—`/`§阶段` 变成了乱码（5 行注释）。已用 .NET `UTF8Encoding($false)` 读+写修好，
   并用 `[^\x00-\x7F]` 扫过确认零残留。**改源码一律用 edit/write 工具，不要用 PowerShell 拼字符串。**
3. APK 的 dex 现在是 `classes.dex` + `classes2.dex` + **`classes3.dex`** 三份，我们的代码在
   **classes3.dex**；只查前两个会误判"改动没进包"。

## 5. 下一步

1. 把 `mediatek-npu-smoke-1.0.8.apk` + `FORWARD_TO_TESTER-1.0.8.txt` 交给测试者（**不需要图包**，2–4 分钟）。
2. 判读：`parsed=1` 且 `V1`/`V2` 的 `relL1` 比 `V0` 小 ≥10×（或 `scale=1000` 不再全 Inf）→ 精度档有效 → 立刻跑 4 步整图。
3. 若 `V1/V2 ≈ V0` → 设备端 JIT 路线再无精度旋钮可调，转 AOT（需 NeuroPilot SDK，当前无权限，硬阻塞）。
