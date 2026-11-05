# 天玑 NPU 生图生产入口修复（2026-11-05）

## 1. 症状

从图片页发起 Anima·天玑 NPU（MediaTek Neuron）生成时，任务在 native 之前被拒绝：

```
图片采样器不可用：MediaTek Anima profiles must be projected from the validated v3 bundle manifest.
```

面板同时落到通用回退值（512 固定、20 步、1–1000、CFG 7），而这些值不是包内 v4 AOT 档位
声明的 512×512 / 6 步 / CFG 1.0。

## 2. 根因

- Neuron 管线由包内 v3/v4 组件清单（`mediatekAnimaManifestPath`）驱动，没有 SD
  `ImageExecutionProfile`。
- 图片页准入、Local API 准入、prompt 准备、worker journal、生成后结算这五处仍无条件调用
  `resolveLocalImageExecutionProfile(...)`。对 `MEDIATEK_NEURON` 它会落到
  `genericProfile()`，而该函数按设计拒绝为 Neuron 合成通用执行档
  （`ImageExecutionGenericProfile.kt` 的 `require(runtime != MEDIATEK_NEURON)`），
  异常被 `ImageGenerationOwner` 包装成“图片采样器不可用”。
- 即使绕过该检查，图片页仍会带 `sampleMethod="euler"`，而 MediaTek 请求构造明确拒绝任何
  sampler；参数默认值也来自通用分支而不是 AOT 档位。

## 3. 修复

新增 `MediaTekImageProductContract`（app 层，JSON-only，不重复 hash 数 GB 资产）：

- 读取已装包的 `manifest.json` 与它 pin 的 v3/v4 组件清单，得到每个生成档位的
  尺寸、步数集合、默认步数、CFG 范围（v4 `cfgMode=NONE` 记为固定值）、是否支持负向提示，
  以及组件清单 SHA-256 作为 profile/prompt 绑定身份。
- 同一份 contract 同时供：UI 默认值、负向提示能力、准入参数校验、Local API 的
  `resolvedProfile`、worker journal、prompt execution 指纹。

接入点：

| 位置 | 变化 |
| --- | --- |
| `ImageGenerationOwner`（图片页准入） | `MEDIATEK_NEURON` 不再走 SD sampler 预检；按 manifest 校验尺寸/步数/CFG/负向提示，并把固定 sampler 清空后再入队 |
| `LocalApiImageGenerationOperations` | 同上；显式非 euler sampler 仍以 `unsupported_*` 422 拒绝 |
| `LocalImagePromptPreparation` | Neuron 走 manifest 绑定的 `DIRECT` prompt evidence，不再解析 SD profile |
| `LocalImageWorkerJournal` | journal 记录 manifest 指纹、档位尺寸/步数/CFG，不再解析 SD profile |
| `CreateLocalGeneratedImageAssetOperations` | 生成后不再用 SD profile 校验 prompt 证据；Neuron 的 native 证据仍由 worker 结果与 Local API gate 校验 |
| `LocalImageExecutionDefaults` / `LocalImageUiCapabilities` | v3 ANIMA 与 v4 AOT 都从 manifest 取默认值；v4 的负向提示字段隐藏，CFG 固定为 1 |
| `ChatModelChoice` / `ImageGenerationParameterNormalization` | 新增 CFG 上下界；恢复旧参数时把 CFG clamp 到包声明范围（min==max 即固定） |

## 4. 已知边界（本切片）

- Neuron 当前只接受英文（ASCII）提示词。旧的 Hy-MT2 中文翻译链路尚未接到 Neuron prompt
  evidence；中文提示会得到可操作的拒绝信息，而不是被静默改写。后续可复用现有
  `ImagePromptTranslationPort` 并提供 manifest 绑定的翻译证明。
- Neuron 的 prompt token 统计仍显示“暂不可用”；它不阻塞生成。
- 设备侧验收未在本机执行（本机无 MediaTek 硬件），需按
  `docs/MCA_DIMENSITY_NPU_HANDOFF_2026-11-01.md` §7.2/§7.3 在 MT6993 上复跑。

## 5. 验证

- `:app:compileDebugKotlin`、`:feature:chat:compileDebugKotlin` BUILD SUCCESSFUL（JDK 21，QAIRT 2.45.0）。
- 新增/更新单测：
  - `MediaTekImageProductContractTest`（纯 contract 校验、UI 默认值、prompt 指纹）
  - `MediaTekImageBundleManifestTest.v4BundleDrivesProductDefaultsAdmissionAndPromptEvidence`
    （真实 temp v4 bundle：默认值、准入归一化、旧 20 步/CFG 7 拒绝、prompt evidence、中文拒绝）
  - `ImageGenerationUiParameterSnapshotTest`（CFG clamp；MediaTek 固定 6 步/CFG 1）
- `:feature:chat:testDebugUnitTest` 全量 613 tests PASS。
- `python tools/architecture/check_architecture_rules.py`：19 项检查 PASS。
- NOT_RUN：真机 NPU 执行、`t08-npug-acceptance.ps1`、完整 APK 构建与安装升级。
