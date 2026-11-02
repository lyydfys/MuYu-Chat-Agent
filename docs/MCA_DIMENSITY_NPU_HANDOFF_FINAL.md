# 天玑 NPU 项目：当前交接状态（2026-10-30 收拢版）

目标："参考他们的方式（玑绘 / com.anima.npu）实现我们的天玑 NPU"。

## 1. 一句话状态

**软件侧能做的都做完了并已用数据/测试钉住；剩下唯一缺口是"一次天玑真机运行"，而本机没有天玑硬件。**

## 2. 交付物（唯一有效版本）

| 项 | 值 |
|---|---|
| APK | `D:\anima-spike\dist-probe\mediatek-npu-smoke-1.0.17.apk` |
| 大小 / SHA-256 | 651,552,993 B / `2d6781aa3d8cbfcb0ab956a5229a22784a494ac420fcfa30c63481adb831a9b4` |
| 版本 / 签名 | versionCode 29 / 1.0.17；证书 `ad116923…d7c16a`（覆盖安装不丢图包） |
| 测试者说明 | `FORWARD_TO_TESTER-1.0.17.txt`、`TESTER_MESSAGE-1.0.17.md` |
| 单测 | 58 条（BlockHealth 10 / ProbeVerdict 17 / Fp16RangePolicy 13 / AnimaAcceptance 17 / Journal 1） |

作废版本：1.0.0 – 1.0.16。

## 3. 这个版本做了什么（与参考项目的关系）

| 参考实现 | 我们 | 说明 |
|---|---|---|
| 自带 MediaTek 插件 0.1.0（9.8 MB） | ✅ 用它 | 探针 + 整图管线都走它；打不开则自动回退自带插件 |
| 自带 dispatch | ✅ 用它 | 与插件同目录 |
| 可编程 options（TOML） | ✅ 接入并验证 | 逐字节 dump 证明哪些键真被解析 |
| AOT 预编译 + w8/w16 定点化 | ❌ 缺 SDK | **这是他们能扛住 2×10⁵ 动态范围的原因** |
| 图分块（4 块） | ❌ 我们 28 块 | 省内存/编译次数，不影响数值 |
| 量程探针（vMax） | ✅ 等价实现 | 改按块输出 absmax 判定，并钉住超限块 |

**替代方案（等价效果）**：模型内部在 block 12–20 会冲到 14 万–20.5 万，而 fp16 上限 65,504，所以这 10 块**钉在 CPU**，其余 18 块走 NPU（≈64% 块加速）。PC 参考管线按同一拆分复现：最终 latent 与纯 fp32 的 **relL1 = 2.9e-3**，无 NaN。

## 4. 已验证到什么程度（可复跑）

| 层级 | 证据 | 复跑命令/文件 |
|---|---|---|
| 数值可行性 | 同一拆分 relL1 2.9e-3、无 NaN/Inf | `analysis/hybrid_predict.sh` |
| 钉块集合正确 | 4 个 seed：12–20 超限 2–3×，21 是边界，22–27 有 3.5× 余量 | `analysis/seed_range.sh` |
| 钉块判定 | 13 条单测 | `Fp16RangePolicyTest` |
| 验收判定 | 17 条单测（含 1.0.6 黑图签名、漂移、混合不降级） | `AnimaAcceptanceTest` |
| 装配完整性 | 插件/dispatch/options 在真实 LiteRT 全部 `status=0`，止步于 NeuronAdapter（高通无 MTN NPU，属预期） | `artifacts/…/2026-10-30-ref-stack-compile-test.txt` |
| 依赖闭包 | 参考插件需要 `libc++_shared.so`；已随包提供，不再依赖 ROM | APK `lib/arm64-v8a/libc++_shared.so` |
| 远端可诊断 | `runEnv` / `pluginSource` / `pinned` / `blocksCpuPinned` / `animaGate` / `animaVerdict` 六类行 | `docs/MCA_DIMENSITY_NPU_DIAGNOSIS_TREE.md` |
| **MTK 真机** | **无** | 本机只有 SM8750P 与小米 14 两台高通 |

## 5. 唯一剩下的动作（需要人）

把 `mediatek-npu-smoke-1.0.17.apk` + `FORWARD_TO_TESTER-1.0.17.txt` 发给测试者，按顺序：

1. `Run probe matrix`（2–4 分钟，**不需要图包**）
2. `Run full Anima pack (CPU)`（4–6 分钟，需要图包）
3. `Run full Anima pack (NPU)`（3–10 分钟）
4. 回传：probe report + probe journal + 两轮的 PNG / latent / journal

## 6. 结果回来后我会做什么（判读表摘要）

- `animaVerdict=NPU_OK` + `pngBytes` 5–10 万 + `blockRunsNpu≈72` → **目标达成**
- `latent_diverged_…` 或某块 `UNHEALTHY(inf)` → 把该块加入 `CPU_PINNED_BLOCKS`，改一个常量 + 跑 58 条单测，重新出包
- `pluginSource=fallback` + `blocksNpu=0` → 参考插件在该机不可用，改用自带插件（数值结论不变）
- `stage=pack_missing` → 图包没导完

## 7. 已知边界（不要期待）

- 无法在本机建立硬件级确定性（无 MTK 设备，用户已确认不可接入）。
- 完整复刻需要 MediaTek NeuroPilot SDK 做 AOT + 定点量化，公网无公开下载。
- 参考插件是从第三方 APK 提取的二进制（Apache-2.0 的 LiteRT 构建产物），仅用于研究验证；产品化必须自行从源码构建。
