# 天玑 NPU（Anima · NPUG + Neuron DLA）交接文档（2026-11-01）

接手前请先完整读 `docs/architecture/MCA_PROJECT_HARD_LOCK.md`，再读本文。本文是自足交接：不需要先读完 10-26/10-27/10-28/10-29/10-31 那几份历史 handoff 才能开工；历史文件只作为溯源。

---

## 0. 一句话状态

Anima 天玑 NPU 主线（NPUG 容器 + Neuron DLA 编译网络）已在真实 MT6993 设备端到端跑通，动态文本生效、VAE 8px 网格缺陷已定位并修复、r3 复检通过；App 推荐目录已上一张「Anima · 天玑 NPU」卡片（手动导入）。**唯一未完成的仍是目标第 6 步：生产 `MainActivity` + 认证 Local API 的验收（连续/取消/内存/性能）——因此推荐状态必须保持 `EXPERIMENTAL`，不得上调。**

---

## 1. 六步目标完成度

| 步 | 内容 | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | 锁模型/分辨率/步数/资产 + 身份绑定的参考张量基线 | 完成（S0） | `reference-1024-32x32`、512×512、6 步、seed 42、sigma `3t/(2t+1)`；ACTX/AMOD/VAE 资产逐件 pin size+SHA-256 |
| 2 | 执行/质量/后端归因结果契约 + 新 payload schema，保持 v3 兼容 | 完成（S2） | `mca.mediatek-image/4`（装在 `mca.image_engine.bundle.v1` 信封内）；`executionStatus` / `qualityStatus` / `acceptanceStatus` / `deviceVerification` 四字段分离，r3 `metadata.json` 已实证 |
| 3 | `core/mediatek-native` 窄 Neuron adapter（单次 restore→bind→run→compare→close） | 完成并在真机验证 | `MediaTekNeuronSmokeTest` + 一键 App；`transport=NEURON_COMPILED_NETWORK`、`backend=MEDIATEK_NEURON`、`nativeExecution=true`、`fallback=false` |
| 4 | conditioning/modulation/sampling/VAE 对齐，再做动态文本与参数能力 | 完成 | 固定 ACTX + 动态文本两条都跑通；VAE 反归一化缺陷已修（§3） |
| 5 | 经 `ImageGenerationOwner → worker → LocalImageProvider` 接线，带请求身份/租约/取消/释放屏障 | 代码完成，主机测试覆盖 | `executeMediaTekAotGeneration(...)` 走生产执行入口；`MediaTekAotExecutionGatingTest`、`MediaTekReferenceBundleHostTest` |
| 6 | 生产 `MainActivity` + 认证 Local API 的正确性/连续/取消/内存/性能验收 | **NOT_RUN** | r3 `metadata.json` 里 `acceptanceStatus=NOT_RUN`、`deviceVerification=NOT_RUN` 是有意保持的诚实标注 |

---

## 2. 已在真机核实的事实（不是推测）

设备：外部 MT6993（天玑 9400 级）会话回传；本机无 MediaTek 硬件（见「红线」§9.5）。

- 去噪链：6 步 × 4 chunk = 24 次编译网络执行，全部完成。
- 固定上下文（`conditioningMode=FIXED_CONTEXT`）：`run.log` 去噪 `42,292 ms`，逐秒/步 `8.03 → 7.05 s`；`metadata.elapsedMs=42,977`。
- 动态文本（`conditioningMode=DYNAMIC_TEXT`）：文本编码 `textEncoding.elapsedMs=72,736`（Qwen3-0.6B + 6 层 adapter，CPU，`lk=256`），去噪 `37,525 ms`（`7.12 → 6.25 s/步`），`metadata.elapsedMs=38,089`。
  → 注意口径：`elapsedMs` 覆盖「去噪 + VAE」，文本编码单列在 `textEncoding.elapsedMs`。差值即 VAE 解码（固定 685 ms / 动态 564 ms）。
- prompt 真的生效：动态跑用「a cat」出的是猫；设备侧 ACTX SHA `75bd6aa113e868f84d3bd612f65fa1564c8889e135990ad0f5f50c4cd99fe458` 与主机独立重算逐字节一致。
- 后端归因诚实：`nativeExecution=true`、`fallback=false`、`runtimeMessage` 明确写「device numerics and quality remain unverified」；`promptApplied/promptSuppressed`、`negativePromptSupported=false`、`cfgMode=NONE`、`latentInitialization=TORCH_MT19937_STANDARD_NORMAL` 均已落在结果契约里。
- 质量仍未判定：`qualityStatus=NOT_VERIFIED_NO_REFERENCE_BASELINE`——这是「缺同上下文参考基线」，不是失败。

---

## 3. VAE 根因与 r2 → r3 证据链（本次最关键的修复）

**现象**：r2 设备图正常但有规则 8px 网格。

**定位**：对 PNG 做 FFT/自相关，网格周期 8 与 latent→pixel 的 8× 下采样对齐；再用主机 CPU VAE 把设备回传的 `final-latent.f32` 重解，网格同样出现 → 排除 DLA/Neuron，锁定 VAE 输入侧。

**根因**：`vaeInput()` 直接把去噪得到的 **归一化** latent 量化送进 `vaedec_64.dla`，缺了 per-channel 反归一化（`z*std + mean`），每个 8×8 块的统计被按通道偏置，网格化。

**修复**：[core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekNpugChainPlan.kt:132](file:///E:/MAC/core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekNpugChainPlan.kt#L132)（`VAE_MEAN` / `VAE_STD` 16 通道常量在同文件 `:154`）

```kotlin
val denormalized = FloatArray(latentNchw.size) { index ->
    val channel = index / plane
    latentNchw[index] * VAE_STD[channel] + VAE_MEAN[channel]
}
return MediaTekNpugVaeCodec.nchwToNhwcInt16(denormalized, latentChannels, height, width, vaeInputScale, vaeInputZeroPoint)
```

配套单测改名并更新期望：`MediaTekNpugChainPlanTest.vaeInputAppliesVaeDenormalizationThenRequantizes`，期望 `-3975 / -3730 / -1536`。

**为什么这个修复是可信的**（三条同时成立）：

1. 去噪链零影响：r2 与 r3 的 `final-latent.f32` SHA **完全相同**
   - 固定 `48839cf3b1c396f79f69b0d7d75c0201598be0d0fb6fc2e5d7440bd5ca5c3ad7`
   - 动态 `130c874ea559ffaa1f376e01056f9d902abe14b3d1da02d53b68f4748389ecee`
2. 只有像素变了：r3 PNG `617,385 B` → `24aa87b3dfb178c529e91b81b820cb6cb927c1b6f2407765fca0b6b5cc2ec8b3`、`500,767 B` → `f6efb46765b5800e8d381aa5846f4d4dda8761364a4170e5163edf25d6e051dd`，网格消失。
3. 与主机参考同分布：设备 PNG vs 主机 CPU 反归一化重解，余弦 `0.999711`（固定）/ `0.999949`（动态）。

对照 r2 的 PNG（已被取代，仅作历史）：`4b0a399094787b298ddaa70b32076df08e8ff8a37896b543c7bfec019f2bfdb9`、`c5e217ca05820f403899ee439e087f5542ed7819a46b558d561c00260db9d8e3`。

---

## 4. 产物与指纹（本地路径，勿发布）

| 产物 | 路径 | 大小 (B) | SHA-256 |
| --- | --- | --- | --- |
| 生产 App（含目录卡片） | `D:/anima-spike/dist-probe/mca-mediatek-app-20261101.apk` | 684,193,834 | `1515360a19a025376cae1d28a65cb83e100a8806eeeeb82ba7b42f750e5fa374` |
| v4 固定 bundle | `E:/MAC/build/mca-mediatek-reference-v4-fixed.zip` | 1,830,262,377 | `438440754de27f49a395bce448dc8c2fe2df944c83f90a980b438e431fdbce9f` |
| v4 动态 bundle | `E:/MAC/build/mca-mediatek-reference-v4-dynamic.zip` | 3,292,929,429 | `e4c020640fcf567ce743c364a36a67a85a4eb29d54a166025f6db132cd7070fe` |
| 一键链测 androidTest APK (r2) | `D:/anima-spike/dist-probe/mca-mediatek-chain-androidTest-20261031-r2.apk` | 22,381,497 | `1884eb09e1b5bfc98949d9db6390310b10292bb1aee579484b5b742cc8541e2d` |
| 链测参考模型 | （bundle 内）`b0bd7e79e6ebc0cac291377585474d23c266d4bb0708a2d25a78e138369a9151` | — | 记在 `metadata.modelSha256` |

设备证据（r3，全部保留）：`E:/MAC/artifacts/device/mca-chain-20261031/`
- `chain-1793510702554/`（固定）与 `chain-1793510795116/`（动态），每个目录 4 件：`result.png`、`metadata.json`、`final-latent.f32`（262,144 B = 16×64×64 f32）、`run.log`；同名 `.zip` 是测试者回传的原始包。
- r2 同目录 `chain-1793461673299/`、`chain-1793461932443/`。
- 主机对照：`host-vae-reference/`（`fixed-denorm.png`、`dynamic-denorm.png`、`fixed-vae.png`、`README`）。
- 主机 ACTX SHA 记录：`E:/MAC/build/device-actx-sha.txt`。

---

## 5. 模型与资产：一张卡片，不是五款模型

- 资产仓库：ModelScope `ledi123456789/anima-npu-dimensity9400` @ revision `08bfcdb5d00874fc382bf5407fdf4fb38edf71e7`（build 360）。
- HF 镜像 `ledisbsb/…` **陈旧**（0.2.4 / build 139），不要拿它做基线或对照。
- 变体矩阵（**同一 Anima 模型的 AOT 编译档位**，按 SoC/DLA 与分辨率区分，不是不同模型）：

| 档位 | 说明 | 本项目状态 |
| --- | --- | --- |
| MT6993 · NP9 · L1024s4 · 512×512 · 6 步 | 固定 ACTX + 动态文本两条 | **已真机验证** |
| MT6993 · L2304r / L4032 | 更高分辨率档位 | 未验证，需各自 NPUG/AMOD/VAE bundle + route/profile |
| MT6897 / MT6899 · NP9 | 其他天玑 SoC 档位 | 未验证，同上 |

- 对标 App 的做法（读 `D:/下载/base.apk.1` 的 `assets/i18n.json` 与 DEX 字符串得到）：模型库按「档位」展示（「模型库显示哪些档位」「用哪个模型出图」），即单一 Anima 模型 + 档位选择；此外还有独立 SDXL 后端（`SDXL + DMD2（1024×1024）`、`SDXL 出图`、`com.anima.npu.SDXL_CANCEL`）。**SDXL 在本项目未接入、未验证，字符串/类名不是可交付证据。**
- 历史决策：我曾按档位铺 5 张卡片，被明确纠正为「四款只是同一模型的多种变体，而不是多种模型」→ 收敛为 **1 张卡片 + 描述里写清变体矩阵**。接手者不要退回 5 卡片。

---

## 6. App / 推荐目录接入现状

**目录卡片** — [core/download/src/main/java/com/muyuchat/core/download/ModelScopeRecommendationCatalogPart3.kt:564](file:///E:/MAC/core/download/src/main/java/com/muyuchat/core/download/ModelScopeRecommendationCatalogPart3.kt#L564)

- id `mediatek_anima_npu_np9`，标题「Anima · 天玑 NPU (NPUG + DLA)」，`kind=IMAGE`、`status=EXPERIMENTAL`、`downloadable=false`、`manualImportRequired=true`、`plannedImageAccelerator=MEDIATEK_NPU`、`downloadPolicy=ALL_DEVICES`。
- `downloadBlockReason` 指向「导入本地包」这一具体操作路径，而不是「未认证/未测试」。

**合同扩展** — `ModelScopeDownloadEligibility.kt:90`

- 新增 `manualImportRequired: Boolean = false`；无 bundle 的 IMAGE 允许 `IN_DEVELOPMENT` **或** manual-import（`:106`）；托管 bundle 不得置位该标记（`:112`）。

**UI**

- 卡片主按钮从「禁用的下载」变成「导入本地包」+ `McaIcons.UploadFile`：`RecommendedModelCard.kt:443-452`，透传于 `:331`；紧凑卡片同样透传：`RecommendedModelCompactCard.kt:222`。
- 呈现层：格式串 `MCA Anima v4`、能力「Anima 文生图」、体积行「下载大小：手动导入约 1.8–3.3 GB」（`RecommendationPresentation.kt:80`）。
- `onImportLocalImageModel` 已在 `RecommendedModelsSection.kt` / `ModelHubScreen.kt` 接线。

**测试与构建**

- `ModelScopeClientTest`（id、EXPERIMENTAL + `manualImportRequired`、计数 55 / 46 / 12）、`RecommendationCatalogTest`（单一 MediaTek id、npuImage 12）、`RecommendationPresentationTest`（`mediatekAnimaShowsVerifiedV4ContractAndManualImportState`）、`RecommendationQueryTest`（id 改名）。
- `:core:download` + `:feature:modelhub` BUILD SUCCESSFUL。
- APK DEX 已核：含 `mediatek_anima_npu_np9` 与「导入本地包」，**不含** `mediatek_anima_npu_mt6993_l2304r` / `mediatek_anima_npu_mt6897_np9`。
- `MediaTekReferenceBundleHostTest`（app 模块）跑 `../build/v4-bundle-fixed` 与 `../build/v4-bundle-dynamic`，断言 Ready、runtime/family/task、4 variants、`setOf(6)`、`FIXED_CONTEXT`/`DYNAMIC_TEXT`、组件文件齐全。

**通用设备准入（不可违背）**：硬件检测只用于排序/推荐与选传输；缺失或未知的设备档案只是建议性。只有具体运行时条件（包损坏、缺二进制、原生加载失败、真实执行失败）才能拒绝。「未测试」「未知芯片」「本机无档案」「未在此设备认证」永远不是拒绝理由。

---

## 7. 测试者操作手册

### 7.1 一键链测 App（无需 adb / Python，对标 1.0.17 的体验）

1. 装 `mca-mediatek-chain-androidTest-20261031-r2.apk`（debug 变体，构建时须 `-Pmca.abis=arm64-v8a,x86_64`）。
2. 桌面打开「MCA MTK Chain Test」（`app/src/debug/AndroidManifest.xml` 注册的 `.debug.MediaTekChainSmokeActivity`）。
3. 点一次选包 → SAF 选 v4 bundle ZIP（固定或动态）→ 自动解压（zip-slip 防护）→ `inspectLocalImageBundleManifestFromRoot` → `executeMediaTekAotGeneration(..., onFinalLatent = ...)`。
4. 产出落在设备 `Android/data/com.muyuchat.mca/files/mtk-chain-runs/<runId>/`：`result.png`、`metadata.json`、`final-latent.f32`、`run.log`；用界面「Share last results」经 FileProvider（`shared_images/`）打包回传。
5. Neuron 运行时装载已在 adapter 里兜底：先试 `libneuronusdk_adapter.9.mtk.so`，再试 adapter/system 路径，再扫 `/vendor|/system|/odm|/product` 的 `lib64`/`lib` 里名字含 `neuron` 的 `.so`；全失败时报 `discovered=` 列表。此前 r2 前的 `dlopen /system/lib/libneuronusdk.so not found` 就是这个坑（NDK 29 无 `android_get_exported_namespace`，所以用普通 `dlopen`，不走 vendor namespace）。

### 7.2 生产 App 路径（第 6 步要用的入口）

1. 装 `mca-mediatek-app-20261101.apk`。
2. 模型库找到「Anima · 天玑 NPU (NPUG + DLA)」→ 点「导入本地包」→ 选 v4 bundle ZIP。
3. 经 `MainActivity` 的本地生图发起生成；同时用认证 Local API `/v1/images/generations` 各跑一遍。

### 7.3 Local API 验收脚本

```
tools/device/t08-npug-acceptance.ps1 -ModelId <id> -Runs 2 [-Serial <sn>] [-OutDir artifacts/device/npug-acceptance]
```

- 脚本只 push/run/拉证据，不装 APK、不构建；bundle 必须已导入。
- 判据行：`RUN=$i RESULT=PASS HTTP=… PNG=… BYTES=… SHA=… PROMPT_SUPPRESSED=… SECONDS=…`，成功标志 `NPUG_ACCEPTANCE_PASS`；证据目录 `/data/local/tmp/mca-npug-accept`。
- **动态 bundle 必须显式 `EXPECT_PROMPT_SUPPRESSED=no`**（`t08-npug-acceptance.sh:37-38` 默认 `yes`，`:64-70` 会 grep `"promptSuppressed":true`）。固定 bundle 保持默认。

### 7.4 证据校验器（不接受手改报告）

- `tools/mediatek/validate_chain_report.py`：状态字段、基线字段、逐 chunk 统计、PNG/output SHA 一致、final-latent 尺寸/SHA/有限性、动态 ACTX 尺寸/SHA。做过「合法 vs 篡改」自测。
- `tools/mediatek/run_neuron_smoke.py`：chain 模式跑完自动调用上面的校验器，校验不过返回 1；会拉 `final-latent.f32`。
- `tools/mediatek/compare_latent.py`：原始 f32 张量对比（max/mean 绝对差、relative_l1、RMSE、余弦、Pearson）。
- `tools/mediatek/compare_actx.py`：上下文张量对比。
- `tools/mediatek/build_v4_bundle.py` / `patch_v4_bundle_routes.py`：重建/修补 bundle（见 §8.4 的已知小坑）。

---

## 8. 待办（按优先级，逐条可验收）

1. **第 6 步生产验收**（唯一阻塞「状态上调」的项）：`MainActivity` + 认证 Local API 的正确性、连续多次、取消、内存（含 VAE/上下文峰值与 OOM 观察）、性能；证据按 §7.3/7.4 归档。
2. **同上下文参考基线**：现在只有「跑通 + 与 CPU 重解一致」，没有同 context 的参考链数值基线，`qualityStatus` 因此保持 `NOT_VERIFIED_NO_REFERENCE_BASELINE`。补上后才能判「纹理/数值是否等价」。
3. **其他档位**：L2304r / L4032 / MT6897 / MT6899 需要各自的 NPUG + AMOD + VAE bundle、route 与 profile、再走同一校验器。禁止凭芯片名或档位名虚构可用性；`minDeviceTier` 之类只做建议。
4. **bundle 侧已知坑**（做新档位时一并处理）：`ImageEngineBundleSpec` 的 `init` 要求 MediaTek Neuron runtime 与 MEDIATEK_NPU accelerator 成对、恰好一个 Anima v3 CONFIG 组件、且该组件 pin 了 source size + SHA-256；带 execution profile 时 task/family/宽高步数必须逐项对齐。生产导入器会拒绝未 pin 的 CONFIG 组件。
5. **SDXL 后端**：对标 App 有，本项目未接入。要做就按独立能力走，不要混进 Anima 卡片。
6. **清理项（不影响结论，可顺手）**：
   - 一键 App 的 `run.log` 尾部 PASS 行偶发缺失（UI 线程 `StringBuilder` 更新在 finally 写文件之后才 post）。
   - 动态 bundle 的 envelope `id` 仍是 `mediatek-reference-anima-512-fixed`（纯外观；真正语义在 `conditioningMode=DYNAMIC_TEXT`）。
   - `app/src/test/java/com/muyuchat/mca/presentation/AgentToolPackageImportGateTest.kt` 里我加过一行 `ThirdPartyTranslationFixtures` 的 import，属他人会话文件的最小可编译修复，语义未动。

---

## 9. 红线（违反即返工）

1. 模型资产再分发：CircleStone NC v1.2 §2.a/§3 允许**非商业再分发**（附许可证、署名、修改声明、NVIDIA 声明，且不得加更严限制）。v4 衍生的公开仓库已建于 ModelScope **`lyydfys/anima-npug-dla-mt6993-nc`**（license `other`）；仍禁止商用，不得改成 Apache/MIT。旧的 `ledi123456789/anima-npu-dimensity9400` 属另一个账号且错标 Apache-2.0，需由该账号修正或下架。
2. 在第 6 步验收通过前，`mediatek_anima_npu_np9` 的 `status` 保持 `EXPERIMENTAL`，不得改 `RECOMMENDED`；不得为了「标记完成」放宽任何测试或合同校验。
3. `AGENTS.md` 与 `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 是本地专属防护，两条路径必须留在 `.git/info/exclude`，绝不 stage/commit/push；防护测试本身要在排除被移除时失败。
4. 本文与 `docs/` 下历史 handoff 目前都未被 git 跟踪（`git status --porcelain docs/` 显示 `??`）；保持本地，不要为了「留档」把它们推进提交。
5. 本机没有 MediaTek 硬件。任何「NPU 通过」的结论必须来自外部 MT6993 真机回传的证据；本机只有 ARM64 通用设备，不能替代天玑验收。
6. 涉及设备的构建/安装/执行、以及任何不可逆操作，先取得明确授权；设备重启本身就是失败证据，保留现场。

---

## 10. 接手检查表（开工前先做这 4 件）

1. 核对 §4 的四个 SHA-256（App APK、固定/动态 bundle、链测 APK）与磁盘一致。
2. 跑 `python tools/mediatek/validate_chain_report.py` 对 r3 两个目录，确认历史证据仍自洽（`chain-1793510702554`、`chain-1793510795116`）。
3. 跑 `:core:mediatek-native` + `:core:download` + `:feature:modelhub` 测试；注意 Gradle 构建锁可能与另一会话争用（共享 `McaBuildLock.psm1`、`E:/gradle-tmp`、必要时 `--no-build-cache`、日志是 UTF-16、KSP 失败先重试）。
4. 若要用设备：先确认设备在线且未被他人会话占用（共享设备 `f9d98aa3` 上他人的 `mca.devicetest` + SLEEP 会使 run 失效），做 preflight 并保持屏幕常亮。

---

## 11. 关键源码索引

| 主题 | 位置 |
| --- | --- |
| VAE 反归一化修复 | `core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekNpugChainPlan.kt:132`（常量 `:154`） |
| NPUG 执行结果（含 `finalLatent`） | `core/mediatek-native/src/main/java/com/muyuchat/core/mediatek/MediaTekNpugExecutor.kt` |
| VAE 编解码 | `core/mediatek-native/.../MediaTekNpugVaeCodec`（`nchwToNhwcInt16`） |
| Neuron 运行时装载 | `core/mediatek-native/src/main/cpp/mediatek_neuron_adapter.cpp`（`openNeuronRuntime` / `discoverNeuronRuntimePaths`） |
| 生产执行入口 | `app/src/main/java/com/muyuchat/mca/LocalImageGenerationOperations.kt`（`executeMediaTekAotGeneration`，`onFinalLatent` 默认空实现，生产行为不变） |
| v4 payload 合同 | `core/download/src/main/java/com/muyuchat/core/download/ModelScopeImageEngineContracts.kt`（`ImageEngineBundleSpec` 及其 `init`） |
| 推荐合同 | `core/download/.../ModelScopeDownloadEligibility.kt:90` |
| 目录卡片 | `core/download/.../ModelScopeRecommendationCatalogPart3.kt:564` |
| 一键链测 App | `app/src/debug/java/com/muyuchat/mca/debug/MediaTekChainSmokeActivity.kt` + `app/src/debug/AndroidManifest.xml` |
| 真机 smoke | `core/mediatek-native/src/androidTest/java/com/muyuchat/core/mediatek/MediaTekNeuronSmokeTest.kt` |
| bundle 主机测试 | `app/src/test/java/com/muyuchat/mca/MediaTekReferenceBundleHostTest.kt` |

---

## 12. 相关历史文档（溯源用，非必读）

- `docs/MCA_DIMENSITY_NPU_HANDOFF_2026-10-31.md` — 逐切片过程、验证矩阵、参数审计、首轮天玑真机结果、目录接入记录（§7.1 完成度审计）。
- `docs/MCA_NPUG_DEVICE_TEST_2026-10-31.md` — r2/r3 测试包、v4 bundle、一键步骤、生产 App 章节、设备发现。
- `docs/MCA_DIMENSITY_NPU_REFERENCE_ARCHITECTURE_2026-10-31.md` — 参考实现的容器/张量布局（ACTX/AMOD/ATOK/ATXW、RoPE、17 通道 patchify、Euler、sigma）。
- `docs/MCA_DIMENSITY_NPU_DEVICE_ANALYSIS_2026-10-31.md`、`docs/MCA_DIMENSITY_NPU_TEST_ANALYSIS_2026-10-31.md`、`docs/MCA_DIMENSITY_NPU_DIAGNOSIS_TREE.md`。
- `docs/MCA_DIMENSITY_NPU_ANIMA_HANDOFF_2026-10-26..29.md` — JIT 通道与早期 1.0.4–1.0.18 迭代。

## 13. 2026-11-01 晚：v4 衍生仓库已发布

- 仓库：**https://modelscope.cn/models/lyydfys/anima-npug-dla-mt6993-nc**（public，license `other`，中文名「Anima NPUG+DLA 天玑9400 非商业衍生版」）。
- 内容：`README.md`、`LICENSE.md`（CircleStone NC v1.2 原文）、`NOTICE.txt`（指定署名 + 修改声明 + NVIDIA 两条）、`NVIDIA-OPEN-MODEL-LICENSE-NOTICE.txt`、`MANIFEST.json`、`download.json`、`SHA256SUMS.txt`、`verify_repo.py`、`verify_remote.py`。
- 两个归档（LFS）：
  - `bundles/mca-mediatek-reference-v4-fixed.zip` 1,830,262,377 B，SHA-256 `438440754de2…`（commit `aa6f1e7f…`）
  - `bundles/mca-mediatek-reference-v4-dynamic.zip` 3,292,929,429 B，SHA-256 `e4c020640fcf…`（最终 commit `54c1e5607e4b86b86f9603789f87a5a4347636ac`）
- `download.json` 已 pin 到 `54c1e560…`，两个 resolve URL 返回 200；`python verify_remote.py` 输出 `REMOTE_REPO_COMPLETE`。
- 定性：这是 **AOT 编译/量化（NPUG+DLA，w8/w16）的衍生制品**，不是重新训练的新模型；原始权重归 CircleStone Labs，受 CircleStone NC + NVIDIA Cosmos 条款约束，仅限非商业使用，输出图可商用（按模型卡）。
- 本地仓库：`D:/anima-derivative-repo`（git `9d59800`），`python verify_repo.py` → `REPO_METADATA_VALID`。
- 后续：
  1. MCA 推荐卡把 `downloadable` 改为 true，消费 `download.json` 的 URL + SHA（当前仍是手动导入）；需在下载前展示非商业许可与署名。
  2. 上传用的 access token 已在会话截图中出现，建议轮换。
  3. 旧仓库 `ledi123456789/anima-npu-dimensity9400` 属另一账号、错标 Apache-2.0，需由该账号修正/下架。
