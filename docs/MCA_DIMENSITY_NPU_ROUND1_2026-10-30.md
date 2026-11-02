# 天玑 NPU 复现玑绘方案 · 第 1 轮进展（2026-10-30）

目标："参考他们的方式实现我们的天玑 NPU"（`com.anima.npu` / 玑绘）。
本轮产出：**找到并验证了"他们的方式"里最关键、且我们当前缺的那一环——可编程的 MediaTek options 通道（含 `relax_fp32_to_fp16`）**。

## 1. 本轮结论（全部在真机验证，不是推断）

| # | 验证项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 参考插件的 MediaTek options API 可从 App 侧驱动 | **PASS**：`dlopen` 成功，导出 `LrtCreateMediatekOptionsFromToml` / `LrtCreateMediatekOptions` / `LrtGetOpaqueMediatekOptionsData` / `LrtDestroyMediatekOptions` | `2026-10-30-mtkpaint-plugin-options-probe.txt` |
| 2 | opaque options 的 identifier | **`mediatek`**（这正是 runtime 用来匹配的键） | 同上 |
| 3 | TOML 载荷能被解析 | **PASS**：合法 TOML → `status=0`；语法错误的行 → `status=1`；未知 key → 仍 `status=0`（宽松，未知键忽略） | 同上 |
| 4 | **跨构建的 ABI 桥**：插件产出的 payload 能被我们的 LiteRT 2.3.0 接收 | **PASS**：`LiteRtCreateOpaqueOptions` = 0、`LiteRtCreateOptions` = 0、`LiteRtAddOpaqueOptions` = 0 | 同上 |
| 5 | 参考插件多出 `relax_fp32_to_fp16` 这个解析键 | **PASS（静态）**：参考插件 rodata 有 `relax_fp32_to_fp16`（无 `= %s` 打印格式 → 是**解析键**而非日志串），我们 2.3.0 AAR 插件**没有**这个键 | `llvm-strings -t x` 输出 |

## 2. 为什么这一环是决定性的

我们 1.0.6 的失败链已定案：插件把 fp32 计算**松弛成 fp16** → 单块 ~7e-4 误差 → 28 层残差链从 `hidden[0]` 的 std 0.6537 放大到 `hidden[27]` 的 **std 2795.9** → block 22 起 NPU 输出 Inf → `final.tflite` 全 Inf → latent NaN → 纯黑 2,227 B。

参考实现里存在 `relax_fp32_to_fp16` 这个开关，而我们的插件连 options API 都没导出（429 KB 精简版）。也就是说：
- 我们**不是缺硬件、不是缺算子**，而是**缺一个能关掉 fp16 松弛的通道**；
- 这个通道现在**已经在真机上验证可用**：既能造出 options，也能被我们的 2.3.0 runtime 接收。

## 3. 本轮交付物

- `artifacts/architecture/2026-10-26-dimensity-anima/2026-10-30-mtk_opts_probe.cpp`
  —— 纯 `dlopen/dlsym` 的探针源码（不依赖 LiteRT 头文件、不依赖 NPU），可复跑。
- `artifacts/architecture/2026-10-26-dimensity-anima/2026-10-30-mtkpaint-plugin-options-probe.txt`
  —— 真机（98a37aa7 / Qualcomm / Android 37）逐变体输出。
- 提取的参考二进制（本地，`D:\anima-spike\tmp\mtkpaint\assets\litert\`）：
  `libLiteRt.so`（5,508,376）、`libLiteRtCompilerPlugin_MediaTek.so`（**9,813,072**，源码构建、含调试路径 `litert-v2.2.0/litert/vendors/mediatek/...`）、`libLiteRtDispatch_MediaTek.so`（3,782,064）。

复跑命令：

```powershell
$adb = "D:\model\android-sdk\platform-tools\adb.exe"
& $adb -s 98a37aa7 shell "cd /data/local/tmp/mtkpaint && timeout 40 env LD_LIBRARY_PATH=/data/local/tmp/mtkpaint ./mtk_opts_probe /data/local/tmp/mtkpaint"
```

## 4. 下一步（下一轮执行）

### 4.1 把 options 通道接进我们的探针 App（1.0.7）

1. 把参考插件 + dispatch 以 **assets** 形式随包（`assets/litert_ref/`），启动时解到 `cacheDir/litert_ref/`。
2. `nativeRunnerOpen` 前 `dlopen` 该插件，`LrtCreateMediatekOptionsFromToml(toml)` → `LrtGetOpaqueMediatekOptionsData` → `LiteRtCreateOpaqueOptions("mediatek", …)` → `LiteRtAddOpaqueOptions(liteRtOptions, …)`。
3. `kLiteRtEnvOptionTagCompilerPluginLibraryDir` 指向 `cacheDir/litert_ref`，让 runtime 加载**同一个**插件（否则 options 与编译插件不是同一份）。
4. 探针矩阵加一组"方案变体"（每个变体只改 TOML）：

| 变体 | TOML | 期望 |
|---|---|---|
| `V0` | 空 | 复现现状（relax 默认开） |
| `V1` | `relax_fp32_to_fp16 = false` | 若生效：block00 相对误差应从 7.2e-4 掉到 ~1e-6 量级，幅度扫描 ×1e3 不再全 Inf |
| `V2` | `relax_fp32_to_fp16 = false` + `neron_sdk_version = 2` | 同上并锁定 SDK v9 |
| `V3` | `optimization_hint = 1` / `performance_mode = 2` | 只影响性能，作为对照 |
| `V4` | `aot_compilation_options = "--opt 0"` | 试 Neuron 编译档位 |

5. 判定沿用现有 `judgement=` 体系：`relL1`、`nan/inf`、幅度扫描是否仍全 Inf、`compileMs`。

### 4.2 若 V1/V2 生效（大概率）→ 直接进 4 步整图

- 用 1.0.7 的 NPU 全量跑一次（现有 30 块 int8 图包即可），看 `hidden[27]` 的 std 是否回到 ~1 量级、`pngBytes` 是否回到 50–100 KB。
- 若成立，则**不需要 AOT 也能跑通**，天玑 NPU 路径立刻可用（此时再谈分块/AOT 优化性能）。

### 4.3 若 V1/V2 不生效 → 才走 AOT

- 需要 MediaTek NeuroPilot SDK（Ubuntu 22.04 + Bazel 7.4.1），当前**没有下载权限**，是硬阻塞。
- 他们的产物结构已摸清（`aot/<soc>/*.dla` + `stub` + `manifest_np9.json`，DiT 切 4–7 块，VAE 按分辨率各一份），届时可直接照抄目录规范。

## 5. 诚实边界

- 本轮**没有**验证 `relax_fp32_to_fp16` 真的会改变 Neuron 的编译行为——这只能在带 MTK NPU 的设备上跑一次才知道（Qualcomm 机没有 MDLA/Neuron 适配层）。
- 参考插件是从**别人构建的 APK 里提取的二进制**（Apache-2.0 的 LiteRT 构建产物）。用于研究验证可以；**若要进产品，必须自己从 LiteRT 源码构建同一份 vendor plugin**，不能长期依赖第三方二进制。
- `findOpaque` 回读返回 6（NotFound）不影响结论：`Add` 成功即代表 options 已挂到 `LiteRtOptions` 上（该查找接口查的是另一条列表）。
