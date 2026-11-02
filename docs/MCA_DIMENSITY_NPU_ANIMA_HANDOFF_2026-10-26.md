# MCA 天玑 NPU / Anima 实施交接文档（2026-10-26）

> 本文是接手继续实现的证据账本与后续执行单。它不是 READY 声明，也不宣称“天玑 NPU Anima 生图已支持”。
> 当前最重要的是：**一台真实 Dimensity ARM64 设备已证明 tiny TFLite 图可在 MediaTek NPU 上完成非 CPU 全加速执行**；Anima 三图、semantic PNG、生产 runner、分区数量和完整验收仍未完成。

## 1. 结论先行

- **最小单图 NPU spike：PASS（仅限 tiny fixture）。** 在 HONOR 的 MT6899（Dimensity，Android 36，arm64-v8a）设备上，公开的 LiteRT C API 以 **NPU accelerator** 编译并运行 `SUM + FULLY_CONNECTED` 小模型，C API 返回：
  - `cProbeNonCpuFullyAccelerated=true`
  - `cProbeFullyAccelerated=true`
  - `cProbeStatus=OK`
  - `cProbeStage=OK`
  - `compileMs=110`、`runMs=4`、输出 40 B
- **这不是 Anima 生图，也不是完整 NPU 证据闭环。** 当前没有：
  - Qwen3 文本编码器 / Anima DiT / Qwen VAE 的 TFLite/Neuron 图；
  - 真实分区数量、CPU fallback op 数、设备 Neuron runtime 版本（目前只固定记录了 `litertVersion=2.3.0`）；
  - semantic PNG、生产 MediaTek JNI/native runner、Anima bundle/profile/runtime 接入；
  - MainActivity 与认证 Local API 共用同一 owner 的生图验收。
- **数值一致性尚未关闭：** tiny 输出 NPU vs CPU 为 `mismatchBytes=20/40`、`maxAbsDiff=223`。该差异不否定 NPU 执行，但在继续 Anima 图之前必须做误差分析和重复运行；不能据此宣称模型数值正确性已验收。
- **2026-10-26 晚接手增量：** 已交付 0.4.4 数值闭环工具并修正度量口径（旧的 `maxAbsDiff` 是 IEEE-754 原始字节差，不是数值误差；旧探针的 float 输入全为 0）。详见 §12。
- **2026-10-26 深夜增量：** 0.5.0 已把一块真实 Anima DiT block 导出为 LiteRT 图并通过 Android CPU 数值对拍，作为单次真机 NPU 闸门。详见 §13。本机没有天玑设备，所有天玑测试只能远程发给测试者。

## 2. 基线与不可变约束

- 仓库：`E:\MAC`
- 基线 HEAD：`68b6eef1e67870b02bfef6afda2e625d36663da9`
- 分支：`codex/enterprise-refactor`
- 工作区含其他 Agent 的既有脏改动，**没有执行 `git reset --hard`、`git clean`，不覆盖他人修改。**
- 子模块保持既有状态：
  - `third_party/llama.cpp` `4ceb1719`
  - `third_party/stable-diffusion.cpp` `16b3846a`（含既有内部 diff）
  - `third_party/tokenizers-cpp` `acbdc5a`
  - MNN `d407447e`（34 项内部改动）
- `AGENTS.md` 与 `UniversalDeviceAdmissionPolicyTest.kt` 仍由 `.git/info/exclude` 排除，**不得提交或发布。**
- 唯一 owner/state writer：未新增第二套 `ImageGenerationOwner`、图片队列、worker、状态 writer 或图库提交链。当前测试 App 与 MCA 推荐页卡片都是诊断/实验入口，**不是第二套生图执行链。**
- 不得伪造 `libneuron.so`、delegate、NPU trace、分区数、checksum、`npuActive=true`；不得把 CPU/OpenCL/MNN/QNN/NNAPI/MNN_CONVERT_NEUROPILOT 当天玑 NPU。
- 设备型号只用于推荐与记录，不能成为下载/导入/加载/运行 allowlist/denylist。未知设备仍可尝试通用兼容路径。

## 3. 本次真实设备证据（用户回传）

| 字段 | 值 |
|---|---|
| evidence schema | `mca.mediatek_npu_cprobe.v1` |
| 实现 | `public_litert_c_api_second_compilation`（第二次独立 C API 编译，非 Java `CompiledModel`） |
| 时间戳 | `2026-10-26T10:55:31Z` |
| 设备 | `HONOR / HONOR / SER-AN00` |
| SoC | `Mediatek / MT6899` |
| Android API | 36 |
| ABI | `arm64-v8a` |
| LiteRT | 2.3.0 |
| 模型 | `assets/mediatek/litert_fixture_model.tflite`（1,432 B） |
| 模型 SHA-256 | `554c505b68505ffd15b64fce2f1998ab51f944b59d2a934dd9d2c6f477ecbb89` |
| cProbeStatus | `OK` |
| cProbeStage | `OK` |
| nonCpuFullyAccelerated | **true** |
| fullyAccelerated | **true** |
| compileMs / runMs | 110 / 4 |
| 输出字节数 | 40 |
| 输出 SHA-256 | `b322413d2c89c649ccf9a3a99a2b83e6ff0555f3a1288e40c25ad5bf56ed563a` |
| CPU status / stage | `OK` / `OK` |
| CPU compileMs / runMs | 1 / 0 |
| CPU 输出 SHA-256 | `8b2151f2a911c197c354a21afe1030bd097936dc8a7baf0cbb2a65347e3bfdc1` |
| NPU vs CPU maxAbsDiff | **223** |
| NPU vs CPU mismatchBytes | **20 / 40** |
| NPU vs CPU outputLengths | `40 | 40` |
| elapsedMs | 138 |
| partitionCount | **UNKNOWN** |
| provesNpuExecution | false（当前 LiteRT 2.3.0 无可用分区计数） |
| 设备上 evidence 路径 | `/storage/emulated/0/Android/data/com.muyuchat.mca.mediateknputest/files/mediatek-npu-smoke/tinyprobe-1793012131718.txt` |

### 证据强度

1. **有真实 NPU 执行。** 这不是“delegate 创建成功”：C API 返回的是一次完整模型执行结果，且 `nonCpuFullyAccelerated=true` / `fullyAccelerated=true`。
2. **仍不能给出分区数。** `IsNonCpuFullyAccelerated` / `IsFullyAccelerated` 是布尔证据，不是分区数量。不能据此写 `npuPartitions=1`。
3. **不是 Java 模型证据。** 探针通过 LiteRT C API 另建 `LiteRtCompiledModel`；它证明当前设备与该 tiny 图可在 NPU 运行，不证明 Java 层或 Anima 已接入 NPU。
4. **tiny fixture 是特意选择的无 Softmax 图，** 用来避免 MobileNet Softmax v1 回退造成的假阴性。它适合验证“可全委托”，不代表 DiT/VAE 可全委托。

## 4. 数值差异分析（必须继续关闭）

- tiny 输出为 10 个 float32（40 B）。NPU 与 CPU 输出 SHA 不同，`maxAbsDiff=223`、20/40 字节不同。
- 可能原因：不同 delegate 的算子实现、float32 累加顺序、量化/舍入路径、CPU 与 NPU 的 FULLY_CONNECTED 数值差异。**不能把该差异自动判成可接受。**
- 目前该差异发生在 tiny SUM+FC 探针上，不能推断为所有模型的固定误差。下一步应：
  1. 对同一输入保存 NPU/CPU 原始 float 值（而非只看 SHA/FNV）；
  2. 使用多组输入和重复运行（至少 3 次）；
  3. 写绝对/相对误差分布，而不是只看字节不同数；
  4. 为 Anima 各阶段分别设数值容差（text hidden、DiT latent、VAE pixel），任何超差先定位转换/量化/layout，不放宽断言。
- 该差异**不推翻** `nonCpuFullyAccelerated=true` 的 NPU 执行事实，但它是进入三图转换前必须单独记录和关闭的数值风险。

## 5. 当前实现与验证状态

### 已有交付

- 独立 `mediatek-npu-smoke` 0.4.3 APK（13,558,745 B，SHA-256 `1712c6d6a9742d637ad613c5bd7622c27306d89d473bbec737a11ce47ca136cc`）：
  - Java NPU/CPU；
  - C API MobileNet NPU/CPU；
  - tiny SUM+FC fixture NPU/CPU（本次真机通过的同一路径）；
  - Run all tests、Share evidence、重启自动加载上次 evidence。
- 分发包 `dist/mediatek-npu-smoke-0.4.3.zip`（7,075,219 B，SHA-256 `455eb0b397819071bb61139c6c172d231d6525eb9fcf13c45b7e2f1350b362d4`）。
- 独立原生 C API 探针工程 `C:\mca-mtk-spike\mtk-npu-cprobe`，已在骁龙设备验证：
  - MobileNet CPU `result=OK`（5/5 运行稳定，输出 1,001 B）；
  - tiny fixture CPU `result=OK`、`fullyAccelerated=1`、输出 40 B；
  - 骁龙 NPU 选项在缺 MediaTek runtime 时明确 `stage=compile`、`status=504`、`libneuron_sys_util.mtk.so not found`。
- `core:mediatek-native` 契约模块仍为 SDK-gated、无 JNI/CMake、未被 app 消费；无 `MEDIATEK_NEURON` runtime 生产枚举或 runner。
- MCA debug/deviceTest 推荐页有“天玑 NPU 实验自检”卡片（非模型）：点击仅打开独立测试 App。最后一个好 deviceTest APK：
  - `E:\MAC\app\build\outputs\apk\deviceTest\app-deviceTest.apk`
  - 476,138,747 B，SHA-256 `cff571c52321ddfc0221c047b99e25e91f0490c4450e0399c9c4752bef7f0f97`
  - 包含卡片和 `<queries>` 测试包可见性；**后续一次纯文案重建失败在无关 `core:sd-native` Vulkan CMake**，不是卡片错误，未产出新 APK。
- 无新增生产 owner、队列、worker、状态 writer 或图库链。

### 自动检查

- `MediaTekNeuronContractTest`：6/6 PASS（XML `2026-10-26T04:21:46.807Z`，`core/mediatek-native/build/test-results/testDebugUnitTest/TEST-*.xml`）。
- 架构静态检查：19/19 PASS（exit 0，`artifacts/architecture/2026-10-26-dimensity-anima/phase0-architecture-gate.log`）。
- 0.4.3 构建 PASS；`Run all tests`、`Share evidence`、`Loaded previous evidence` 字符串在 `classes3.dex` 中；JNI `nativeCProbe` 与 tiny/MobileNet 资产完整。
- 0.4.3 的 `Run all` 顺序与无电脑分享路径已验证到编译与 dex，**分享 UI 本身尚未在真机复核**。
- 真机 UI 测试：本机骁龙设备安装被 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，未改设备安全设置，故 deviceTest 推荐页 UI **NOT_RUN**；用户侧只回传了 tiny evidence 文本/截图，未附 Java/MobileNet 全部结果与 logcat。

## 6. 阶段状态总表

| 阶段 | 状态 | 说明 |
|---|---|---|
| Phase 0 基线 | PASS | 文档、git、子模块、架构门禁 19/19、契约 6/6 |
| Phase 1 SDK/授权/设备 | PARTIAL | 出现 MT6899 真机 + 公共 LiteRT 运行路径；NeuroPilot 转换 SDK/许可仍未取得 |
| Phase 2 最小 NPU spike | **PASS（仅 tiny）** | MT6899 上 C API 第二次编译执行 tiny，`nonCpu/fully=true`；分区数 UNKNOWN。0.4.4 数值闭环工具已构建并在 Qualcomm CPU 验证，Dimensity 数值结果待回传 |
| Phase 3 三张 Anima 图转换 | **PARTIAL（1/28 DiT block）** | 已用 litert-torch 导出 block 0（278 MB），Android CPU 数值对拍通过；28 块、text、VAE 未导出 |
| Phase 4 semantic pipeline | NOT_RUN | 无 tokenizer→text→scheduler→DiT→VAE→PNG |
| Phase 5 独立 MediaTek JNI/native runner | NOT_RUN | 尚未进入生产实现；无假库 |
| Phase 6 bundle/profile/runtime 接入 | NOT_RUN | 无 `MEDIATEK_NEURON`/`MEDIATEK_NPU` 生产枚举或 JSON 迁移 |
| Phase 7 owner/Local API/Worker | NOT_RUN | 未接入现有 ImageGenerationOwner、Local API、worker、取消/超时/进程死亡/图库链 |
| Phase 8 双入口/设备验收 | NOT_RUN | 未完成 MainActivity + 认证 Local API、取消、失败、重试、后台、进程死亡 |
| READY | **NOT_READY** | 不得写“天玑 NPU Anima 生图已支持” |

## 7. 后续执行单（按顺序）

### 7.1 先补齐本次证据包

请测试者从 0.4.3 App 的 `files/mediatek-npu-smoke` 目录回传全部文件：

- `evidence-*.txt`（Java NPU/CPU）
- `cprobe-*.txt`（MobileNet C API NPU/CPU）
- `tinyprobe-*.txt`（本次 tiny fixture）
- 如可获取，附 logcat（`MediaTekNpuSmoke MtkNpuCProbe LiteRT NeuronDelegate NeuronAdapter`）。

目的：确认非 tiny 的真实 CNN 是否有部分 NPU 分区，及 Softmax v1 是否造成 CPU fallback；同时收集 **设备 Neuron runtime 版本**，不能只写 `litertVersion=2.3.0`。

### 7.2 Phase 3：单图转换与验证（先 VAE block 或单个 DiT block）

不要直接转完整 Anima，也不要复用 QNN 的 SD1.5 4-channel 契约。顺序建议：

1. 一个 VAE block / decoder slice；
2. 一个 DiT block；
3. 完整 DiT；
4. Qwen3-0.6B 文本编码器。

每个图必须固定并写入记录：

- 原始权重来源、许可证、文件大小、SHA-256；
- 图文件路径（只能 `graphs/...`）、graph SHA-256；
- 输入/输出 tensor 名称、shape、dtype、layout（NHWC/静态）；
- 量化参数、batch、latent layout、pixel range；
- 不支持算子及 fallback 原因；
- runtime/Neuron 版本、online/offline compile mode、首次编译耗时；
- CPU reference 输出与 NPU 输出 SHA、绝对/相对误差、重复运行稳定性。

Anima 约束必须保留：Qwen3-0.6B text、16-channel latent、28 层 DiT、2048 hidden、16 heads/128 dim、1024 text dim、Euler + FLOW、CFG 6、Qwen/Wan VAE 16ch/8x；不能把 T5 side data 写成第二个神经文本编码器。

若转换需要 NeuroPilot/Neuron SDK 或 DLA，记录 SDK 版本、授权、MDLA/Neuron 绑定；**没有授权转换工具链时该步骤只能 BLOCKED，不能用 CPU/MNN/QNN 产物冒充 Neuron 图。**

验证要求：每张图在 MT6899 上通过公开 C API 执行，至少一个 NPU 分区或非 CPU 全加速；同时保留 CPU reference、输出 SHA 和误差分布。只有 CPU 全部回退算 FAIL，不算 NPU。

### 7.3 Phase 4：仓库外完整 semantic pipeline

在仓库外临时程序完成：

```text
tokenizer -> Qwen3 text encoder -> CPU scheduler 初始化
-> 多步 Anima DiT -> Qwen VAE -> CPU PNG 编码
```

- 起始参数：384x384（或设备允许最小尺寸）、1 prompt、Euler、CFG 6、固定 seed、2–4 steps；
- NPU 负责实际接管的 text/DiT/VAE 子图；CPU 负责 tokenizer、prompt packing、scheduler、IO、PNG；
- 必须输出可解码 PNG，记录 runtime version、graph hashes、真实 partition、fallback op 数/原因、各阶段耗时和输出 SHA-256；
- 需要证明取消/超时可释放 interpreter/delegate；随机 tensor 或单次 delegate 创建不算通过。

### 7.4 Phase 5：独立 MediaTek JNI/native runner（只有 Phase 2-4 全过后）

- 在 `core:mediatek-native` 实现真实 JNI/native runner；SDK 通过 `MCA_MEDIATEK_SDK_ROOT`/`-PmcaMediatekSdkRoot` 注入，不硬编码机器路径；
- 无 SDK 时不编 vendor include、不链接假库、不打包空 `.so`；
- JNI 只接收 typed manifest/profile/request；`load` 只表示 manifest/graph/runtime 可加载，`READY` 必须由 semantic PNG + NPU evidence 结算；
- requestId/cancel/timeout/worker teardown/幂等终态；错误细分 `sdk_missing`、`delegate_load_failed`、`graph_compile_failed`、`unsupported_op`、`graph_execute_failed`、`output_invalid`、`cancelled`；
- delegate/interpreter/context 由一个 runtime owner 创建、使用、取消、释放，禁止页面重组产生第二份。

### 7.5 Phase 6：bundle/profile/runtime 契约

- 增加 `MEDIATEK_NEURON` runtime 与 `MEDIATEK_NPU` accelerator，保留旧 JSON 值、旧 fixture 和迁移，不能影响 QNN/MNN/CPU；
- profile 仅绑定 ANIMA，QNN/MNN/普通 SD 不得误入 NeuroPilot runner；
- manifest schema v2：三图路径、shape/dtype/layout/size/SHA、graph inputs/outputs、delegate、graph hash、SDK/version/compileMode；
- 缺件/坏 hash/坏 schema 给具体错误，**不隐藏下载/导入/通用 CPU 路径，不能用设备型号做准入名单。**

### 7.6 Phase 7：复用唯一执行链

```text
MainActivity / Local API
  -> ImageGenerationOwner
  -> LocalImageGenerationCoordinator
  -> LocalImageMediatekOperations
  -> existing worker protocol/service
  -> MediaTekNeuronBridge
  -> image validation / gallery commit
```

- requestId/lease/epoch 贯穿 native 请求；UI 取消要真实调 native cancel + worker teardown fallback；
- 进程死亡后必须失败/取消结算，不能悬挂“生成中”；
- 旧请求回调不得写新请求；
- 临时 PNG 原子 rename 后走现有 asset commit；
- NPU 失败给具体 stage 错误，并允许选择 CPU Anima 路径；CPU fallback 不得标成 `MEDIATEK_NPU`。

### 7.7 Phase 8：双入口与设备验收

至少：

1. MT6899（或另一台 Dimensity ARM64）；
2. 一台非目标/未知设备，确认通用路径未被芯片准入阻断。

验收：text/DiT/VAE smoke、2–4 steps semantic、产品 steps、MainActivity、认证 Local API、取消、失败、重试、后台/进程死亡、缺件/损坏、未知设备仍可通用 CPU。记录 app version、父 SHA、子模块 diff、设备/Android/RAM、图 hash、尺寸、steps、耗时、峰值内存、取消结果和 stage trace；不得记录 prompt/API key/私人内容。

## 8. READY 判定清单

| READY 项 | 当前 |
|---|---|
| 三张 Anima 图真实加载 | NOT_RUN |
| Neuron delegate 真实执行 | PARTIAL：tiny C API 在 MT6899 上执行，未接 Anima |
| 至少一个真实 NPU 分区 | **有非 CPU 全加速执行，但分区数量 UNKNOWN；不得伪造数量** |
| text、DiT、VAE 三阶段完成 | NOT_RUN |
| semantic generation 输出可解码 PNG | NOT_RUN |
| runtime version | LiteRT 2.3.0 已知；**设备 Neuron runtime 版本未取到** |
| graph hash | tiny fixture 已固定；Anima 三图无 |
| partition / fallback | partition UNKNOWN；CPU reference 有；fallback op 数 UNKNOWN |
| output SHA-256 | tiny 输出 SHA 有；semantic PNG 无 |
| MainActivity 与 Local API 同一 owner | NOT_RUN |
| 至少一台真实 Dimensity ARM64 设备通过 | **tiny spike 已通过 MT6899；Anima 未验收** |
| 未知设备无 allowlist | PASS（没有新增芯片准入名单） |

## 9. 回退与清理

- 独立 `mediatek-npu-smoke` 和 `mtk-npu-cprobe` 工程位于 `C:\mca-mtk-spike`，删除即可回退；它们不改 MCA 生产路径。
- 推荐页卡片涉及 5 个文件：`ModelHubScreen.kt`、`RecommendedModelsSection.kt`、`MainActivity.kt`、`app/src/debug/AndroidManifest.xml`、`app/src/deviceTest/AndroidManifest.xml`；需要回退时只还原这些文件。其余生产 owner、数据、schema、runtime enum 均未改。
- 最后一个好 deviceTest APK 为 `cff571c5…`；若回滚推荐页卡片，仅还原上述文件。无关 `core:sd-native` Vulkan 失败不是卡片回退点，不要覆盖他人 native 改动。
- 不要执行 `git reset --hard` / `git clean`，不要发布 `AGENTS.md` 或本地策略测试。

## 10. 证据索引

- 基线/门禁：`artifacts/architecture/2026-10-26-dimensity-anima/phase0-1-report.json`、`phase0-architecture-gate.log`
- 用户测试交付与状态：`phase2-user-test-spike.json`、`phase2-apk-verification.txt`、`phase2-build.log`
- 独立 C API CPU 稳定：`phase2-cprobe-cpu.txt`、`phase2-cprobe-cpu-5x.txt`
- 独立 tiny CPU：`phase2-cprobe-tiny-cpu.txt`
- 骁龙 NPU 预期失败：`phase2-cprobe-npu-failure.txt`
- 推荐页诊断入口与 deviceTest 包：`phase6-recommendation-diagnostic-entry.json`、`phase6-diagnostic-deviceTest-build.log`
- 真机 tiny NPU 结果：用户回传文本/截图，`tinyprobe-1793012131718.txt`（2026-10-26T10:55:31Z）

## 11. 接手时必须继续做而不是重做的事

1. **不要重做 0.4.3 单 APK、C API 探针或 tiny NPU 验证；它们已完成并在 MT6899 上通过。**
2. 先收齐 Java/MobileNet 全部 evidence 与 Neuron runtime 版本，再做单 VAE/DiT block 转换验证。
3. 关闭 tiny 的 NPU/CPU 数值差异，形成可复核的容差与重复运行记录，不能用“看起来差不多”代替。
4. 只有三图转换、semantic PNG、分区/fallback 证据齐全后，才进入生产 JNI/runner、bundle/profile、owner/Local API/设备验收。
5. 每一步输出：改动文件、owner/state writer、命令、匹配测试数、PASS/FAIL/NOT_RUN/BLOCKED、未验证项、回退点；没有证据时写 BLOCKED，不能伪造完成。

## 12. 接手后增量（2026-10-26 晚）：0.4.4 数值闭环工具

### 12.1 结论

- **口径修正：** 旧字段 `cProbeNpuCpuMaxAbsDiff=223` / `cProbeNpuCpuMismatchBytes=20/40` 是逐字节比较 IEEE-754 原始表示，**不是 float 数值误差**；且旧探针对 float32 输入全部填 0。两者都不足以判定数值风险，也不能据此宣称“误差很大”或“数值正确”。
- **已完成：** 0.4.4 单 APK 与独立 C 探针加入确定性 xorshift32 float 输入、每加速器 3 次重复、逐次输出 SHA、真实 float 误差（`maxAbsErr`/`meanAbsErr`/`rmsErr`/`maxRelErr`）、以及设备 Neuron runtime 信息采集。`Run all` 一次即可产出全部证据，并合并成一个 `runall-*.txt` 供 Share 一次发回。
- **已验证：** 同一 C 探针在 Qualcomm `98a37aa7`（SM8750P，API 37，非 MediaTek）上完成 seeded CPU 验证：seed 1 连续两次输出 FNV 均为 `3705034104993990690`，seed 2 为 `11076504071473363812`，seed 3 为 `6272502320593339382`，输出 10 个有限 float32、40 B。说明输入填充可复现且随 seed 变化。
- **仍未取得：** Dimensity 上的 NPU vs CPU float 误差、NPU 重复稳定性、设备 Neuron runtime 版本。MT6899 测试机不在本机，§4 的数值风险状态为 **OPEN（工具就绪，结果待回传）**。
- **Phase 3–8 不变：** 无授权 NeuroPilot/Neuron 转换 SDK、无 Anima 权重/图，全部 BLOCKED。未新增生产 owner、队列、worker、状态 writer、runtime 枚举或图库链。

### 12.2 交付物

| 项 | 值 |
|---|---|
| 独立工程 | `C:\mca-mtk-spike\mediatek-npu-smoke` |
| 版本 | 0.4.4（versionCode 8） |
| APK | 13,743,619 B，SHA-256 `888cf019f5e31225be5c8171bcdf6ef09c1f2eb3706cb20440d63927f09a173d` |
| 分发包 | `dist/mediatek-npu-smoke-0.4.4.zip`，7,106,171 B，SHA-256 `dd6128f4745513d9ce0ac5947ff992155ece72357ce3b3fc851eaa7f96c0ee1d` |
| JNI | `nativeCProbe(byte[], String, byte[], byte[], int, int, int) -> int[12]`；符号已在 `libmtk_npu_cprobe.so` 中确认导出 |
| 证据记录 | `artifacts/architecture/2026-10-26-dimensity-anima/phase2-numeric-closure-0.4.4.json` |
| 种子 CPU 证据 | `phase2-cprobe-tiny-seeded-cpu.txt`、`phase2-cprobe-tiny-seeded-npu-failure.txt` |
| 构建日志 | `phase2-build-0.4.4.log` |

0.4.4 的 `numeric-*.txt` 关键字段：

```text
schema=mca.mediatek_npu_numeric.v1
numericSeeds=1,2,3   numericRepeats=3
inputFill=xorshift32_uniform_float32_minus1_to_1
seed=N inputSha256=... cpuInputSha256=... inputMatch=true
seed=N npuRunShas=a,b,c npuRunStable=true
seed=N cpuRunShas=a,b,c cpuRunStable=true
seed=N npuFloats=... cpuFloats=...
seed=N maxAbsErr=... meanAbsErr=... rmsErr=... maxRelErr=... maxAbsErrIndex=...
seed=N withinFp16Reference=true|false      （只与 2^-10 机械比较，不是验收）
npuSeedsExecuted=N pairedSeeds=N
neuronRuntimeVersion=...                    （属性/库哈希/有界字符串提示，不伪造版本）
partitionCount=UNKNOWN provesNpuExecution=false
```

### 12.3 本次验证（证据范围）

| 项目 | 结果 | 说明 |
|---|---|---|
| 0.4.4 构建 | PASS | `:app:assembleDebug`，exit 0 |
| APK 内容/版本/JNI | PASS | aapt2 0.4.4/code 8；新字符串在 dex；JNI 符号导出；MediaTek dispatch/plugin 与 LiteRT 库齐全 |
| seeded CPU（Qualcomm） | PASS | 同 seed 重复一致、不同 seed 不同；10 个有限 float |
| seeded NPU（Qualcomm） | FAIL（预期） | `stage=compile`、`status=504`、缺 `libneuron_sys_util.mtk.so`；不是 NPU 证据 |
| APK 安装（两台 Qualcomm） | NOT_RUN | `INSTALL_FAILED_USER_RESTRICTED`；未改设备安全设置 |
| Dimensity 数值闭环 | NOT_RUN | 无 Dimensity 设备连接 |
| Anima 三图转换 / semantic PNG | BLOCKED | 无授权 SDK / 权重 / 图 |

补充观察（非本次产出）：仓库内 `app/build/outputs/apk/deviceTest/app-deviceTest.apk` 已更新为 565,556,773 B，SHA-256 `c5d81d3bb05aa0aa7260731791291a337fab919dcb2202f68e417b20c18b3bcf`；其 dex 含诊断卡片字符串，`RecommendedModelsSection.kt` 的卡片只调用测试包 launch intent，不是第二套生图链。该 APK 的真实 UI 仍未在设备上验证（两台 Qualcomm 机安装受限）。

### 12.4 下一步（测试者一次操作）

1. 安装 `mediatek-npu-smoke-0.4.4.apk` 到 MT6899 设备。
2. 点 **Run all tests**（或只点 **Run numeric closure**），完成后 **Share evidence**。
3. 回传全部 `evidence-*`、`cprobe-*`、`tinyprobe-*`、`numeric-*`；有电脑时运行 `collect-evidence.ps1`，同时捕获 logcat、设备属性与 vendor 库清单。
4. 用回传的 `numeric-*` 关闭 §4：若 `pairedSeeds=3`、`npuRunStable=true`，则以实测 `maxRelErr` 形成记录，再为 Anima 三个阶段分别设容差；任何超差先查转换/量化/layout，不放宽断言。

### 12.5 回退

- 删除 `C:\mca-mtk-spike\mediatek-npu-smoke\dist\mediatek-npu-smoke-0.4.4*`、0.4.4 的源码改动（外部工程无 git，可从 0.4.3 副本恢复）、本节的 artifact 记录与 seeded 证据文件。
- 仓库生产代码、数据、schema、runtime 枚举、owner、子模块均未改；未执行 `git reset --hard` / `git clean`，未发布 `AGENTS.md` 与本地策略测试。

## 13. 0.5.0：真实 Anima DiT block 已导出，做成单次真机闸门

### 13.1 结论

- **已把一块真实 Anima DiT block 转成 LiteRT/TFLite 并验证数值。** 权重来自本地
  `D:/anima-spike/weights/anima-preview.safetensors`（Cosmos-Predict2-2B 派生，
  28 blocks、hidden 2048、16 heads×128、patch 2、text 1024）。导出的 block 0：
  - 输入 `x[1,1024,2048]`、`context[1,512,1024]`、`embedded_timestep[1,2048]`、
    `temb[1,6144]`；输出 `[1,1024,2048]`；
  - 277,927,568 B，SHA-256 `3d2b345de83cf15bd60685acc48b134744bb6511495c0aed0c757174aa10500f`；
  - 63.142 G MACs / 126.283 G ops 每次前向。
- **Android LiteRT CPU 实测通过：** Qualcomm `98a37aa7` 上 `result=OK`，
  compile 91 ms、run 1,517 ms、`fullyAccelerated=1`、输出 8,388,608 B。
- **数值对拍通过：** 同一输入下与 PyTorch 参考相比 `max_abs=1.602e-4`、
  `mean_abs=2.001e-6`、`rms=4.322e-6`（参考输出 std 2.888，`p99_rel=4.507e-5`；
  `max_rel` 由接近 0 的元素主导）。转换链路可信。
- **FD 资产路径已在真机验证：** `LiteRtCreateModelFromFd`（APK 未压缩资产使用的
  同一 API）与 buffer 路径输出 FNV 完全一致；APK 不再把 278 MB 模型复制进 Java 堆。
- **未完成：** 其余 27 块、final layer、Qwen3 text、VAE、sampler、PNG、28 块图包、
  量化、真机 NPU。Phase 3 仍为 PARTIAL，READY 不成立。

### 13.2 交付物（0.5.0 单 APK）

| 项 | 值 |
|---|---|
| APK | 291,558,854 B，SHA-256 `cc3e86bf4b0a0582539aada579838abe43d7e932edd1421a5eb0b3bf2f98f289` |
| 分发包 | `dist/mediatek-npu-smoke-0.5.0.zip`，137,993,916 B，SHA-256 `ee2f81505cf5c2bbe4d2854866946caf51ce1958408091d8dd9913e80ad23199` |
| 内置图 | `assets/anima/block00.tflite`（未压缩存储，fd 打开） |
| 新入口 | **Run all** 内含 “Run Anima DiT block NPU + CPU”；证据前缀 `animablock` |
| 新回传方式 | **Share evidence file (.txt)**：经 MediaStore 导出到 Downloads 再分享，避免长文本被聊天工具截断 |
| 证据记录 | `artifacts/architecture/2026-10-26-dimensity-anima/phase2-anima-block-0.5.0.json` |

### 13.3 转换工具链（本地，可复现）

- Windows 没有 `litert-converter` wheel；转换在 WSL Ubuntu 22.04 的 ext4 venv 中完成。
- `litert-torch 0.10.0` + `torch 2.9.1+cpu` + `litert-converter 0.5.0`；
  `torchao 0.18` 与 torch 2.9.1 不兼容（缺 `ScalingType`），固定 `torchao 0.17.0`。
- 参考实现 `D:/anima-spike/convert/anima_torch.py` 严格按
  `third_party/stable-diffusion.cpp/src/anima.hpp`、`rope.hpp` 的语义：
  cosine-first timestep、axes [44,42,42]、h/w NTK 4.0、rotate-half、Q/K RMSNorm、
  AdaLN-Zero、GELU-tanh、patch 顺序 (c,m,n)。
- 导出脚本 `D:/anima-spike/convert/export_block.py`；对拍脚本 `check_block.py`。

### 13.4 这一次真机测试能回答什么

在 MT6899 上安装 0.5.0，点 **Run all tests**，再点 **Share evidence file (.txt)**，
一次回传即可回答：

1. 66M 参数、266 MB 的 attention block 能否在 MediaTek NPU 上编译；
   `animaBlockNpuStage` 给出精确阶段（compile / run / …）。
2. 是否 `nonCpuFullyAccelerated=true`（`animaBlockNpuNonCpuFullyAccelerated`）。
3. 单块 NPU compile/run 时间（`animaBlockNpuCompileMs` / `AnimaBlockNpuRunMs`）。
4. NPU 与 CPU 的真实 float 误差（`animaBlockMaxAbsErr` / `MaxRelErr`）。
5. 同时带回旧的 Java/MobileNet/tiny/numeric/Neuron runtime 全套证据。

### 13.5 如果这一块通过：完整图包方案（尚未构建）

- 每块一张静态形状 TFLite；调制向量与 512×1024 text context 在 PC 预计算，
  设备端不做 AdaLN/text 数学。
- 28 块顺序执行；Euler flow + CFG 在设备 CPU；VAE 用 Wan/Qwen decoder 图
  （Conv3D，预计 CPU 回退）→ PNG。
- 体积：fp32 约 7.4 GB、fp16 约 3.7 GB、int8 约 1.9 GB；**全量图包必须先量化**。
- 关键未验证风险：NPU 对 266 MB 图的编译/内存、28 块连续数值漂移、
  Conv3D 的 NPU 支持、手机存储。
- 许可证：Anima 权重为 CircleStone 非商业许可（Cosmos-Predict2 派生）；
  图包只用于本地/测试诊断，不发布权重。

### 13.6 回退

删除 `dist/mediatek-npu-smoke-0.5.0*`、外部工程中的
`assets/anima/block00.tflite` 与 0.5.0 源码改动、D:/anima-spike 的转换产物、
以及 §13 的 artifact 记录。仓库生产代码、数据、schema、子模块未改，
未执行 `git reset --hard` / `git clean`。

## 14. 0.6.0：全量 Anima 图包与 PC 端 semantic PNG 跑通

### 14.1 结论

- **PC 端已用“设备图包”跑出完整 semantic PNG：** 28 块 int8 DiT + final + ONNX VAE，
  4 步、CFG 6、seed 42、512×512，得到一张举牌的猫（与 sdcpp 参考提示一致）。
  这说明图包、采样器、VAE 与设备 runner 使用的数据布局是同一条正确流水线。
- **找到并修复三个根因：**
  1. **采样器把网络输出当成了 x0。** 实际是 flow velocity；`denoised = x - sigma*pred`，
     Euler 更新为 `x += pred * (sigma_next - sigma)`。
  2. **AdaLN 调制多了一个 SiLU。** `AdaLayerNormZero` 只在 timestep 输入上做一次
     SiLU，随后 linear1 → linear2 **直接相连**；只有 timestep embedder 在两次线性
     之间有 SiLU。旧 `precompute_mods` 在 linear1/2 之间多加了 SiLU，导致所有调制错。
  3. **输入/输出 patch 顺序不同。** patchify 输入是 `(C, ph, pw)`（C 最慢）；
     final layer 输出是 `(ph, pw, C)`（C 最快）。旧 runner 两处都用输入顺序。
- **VAE 改用 ONNX Runtime。** TFLite 的 Conv3D 在 CPU 上解码 512×512 需要 20+ 分钟；
  同一 decoder 的 ONNX 版本用 ORT CPU 约 28 秒，数值误差 7e-6。
- **逐项对拍（BF16 sdcpp，Linux 本地编译带转储的副本）：** RoPE 6e-8、embedded 1.0、
  temb 1.0、prelude 1.0、AdaLN 权重逐位一致、修正后调制 0.999995、
  28 块完整 pred 0.999995（mean abs 0.0027 / std 1.10）。
- **Dimensity NPU 仍未运行**；本次交付是“一次真机测试”的完整包。

### 14.2 交付物

| 项 | 值 |
|---|---|
| APK | 0.6.0（code 10），20,790,326 B，SHA-256 `53e7623ff307164b40326615040a0232395d58593451da1e2f52f82a5e8e7764` |
| 图包 zip | `dist/anima-pack-0.6.0.zip`，2,094,940,161 B，SHA-256 `55bab62dd35d70962f601e018c78fe82e0c5c47b70a917da01d3380069f53827` |
| 图包内容 | 28×int8 block（64,329,952 B/块）、prelude/final fp32、vae_decoder.onnx、4 步 mods、contexts、manifest |
| PC 验证图 | `D:/anima-spike/out/step4B.png`，71,590 B，SHA-256 `d7310a216870fb9daa9825b001e741808d158c9ee0878396b0ef56c510afb61b` |
| 证据记录 | `artifacts/architecture/2026-10-26-dimensity-anima/phase3-anima-pack-0.6.0.json` |

### 14.3 设备端流程

```text
Import .zip（解压到 files/anima-pack）
  -> patchify（C 最慢）
  -> prelude
  -> 28 × int8 block（逐块 open，优先进 NPU，失败回退 CPU）
  -> final -> unpatchify（C 最快）-> CFG 合并 -> Euler 更新（4 步）
  -> latent * std + mean -> vae_decoder.onnx -> PNG
  -> evidence + Share
```

### 14.4 已知风险

- MediaTek NPU 可能拒绝 64 MB 的 int8 图或编译很慢；runner 会回退 CPU，
  整轮时间可能显著变长（PC 上 int8 每步约 70 秒 / 56 次 block 运行）。
- VAE ONNX 512×512 需要约 2–4 GB 内存；NNAPI 可能拒绝 Conv3D 而回退 ORT CPU。
- 4 步 CFG 6 画质一般（背景发白）；本次目标是“跑通”，不是最终画质。
- 文本编码器仍在 PC 预计算，尚未上设备/NPU。

### 14.5 下一步

测试者一次操作：装 APK → Import 图包 → Run full Anima pack → Share evidence file
+ Share last Anima PNG。若 NPU 图编译失败，evidence 会给出 `block=NN stage` 与
`blocksNpu/blocksCpu` 计数；若 VAE 内存不足，会给出 `stage=vae`。

### 14.6 回退

删除 `dist/anima-pack-0.6.0.zip`、`pack/device`、0.6.0 APK 分发、
`D:/anima-spike/sdcpp-dump` 与带转储的构建、以及 §14 记录。仓库生产代码、
数据、schema、子模块未改，未执行 `git reset --hard` / `git clean`。

### 14.7 图包分卷与 0.6.1（便于传输）

- 图包拆成 **5 个独立 zip 分卷**（每个约 370–520 MB），供网盘/即时通讯逐个发送；
  每个分卷解压到同一目录即组成完整图包，**不需要字节拼接**。

| 分卷 | 大小 (B) | SHA-256 |
|---|---|---|
| `anima-pack-0.6.1-part1.zip` | 393,337,090 | `12aac1281c9b49b23541f5a52fe28de88e99fca98d57ca5a7dcca05e40a82ee4` |
| `anima-pack-0.6.1-part2.zip` | 385,980,358 | `fa45ae60af73fddf789177453f5c56a46a5876ee5012cd43954e751d241f8abb` |
| `anima-pack-0.6.1-part3.zip` | 385,980,358 | `dc3f7beeef38a8335d899048e789b129bc8246f28f976dfb1ac394dde31e31f7` |
| `anima-pack-0.6.1-part4.zip` | 385,980,358 | `b6e9527e5e9881f04bf8d52b874a6eac25e0890f69175ed327f76362881cdc2a` |
| `anima-pack-0.6.1-part5.zip` | 543,662,049 | `cf4a1807ad7a9bea1cb607c3bb56e0e9faae92797c61f9371ad1f9f9b8890277` |

- 单包：`anima-pack-0.6.1.zip`，2,094,940,161 B，SHA-256
  `2af1f90127a8c93fff7bb55b73791796064efed29776ad90715fb146a572f89d`。
- **APK 0.6.1**（code 11）：20,630,969 B，SHA-256
  `327002d5f4a244557089d47aeafd98c25f91832fa53d154ab3f419fb6a25402e`。
  改动：允许**分卷逐个导入**（部分导入不再报错，并提示缺失文件数）。
- 跑通证据：5 个分卷一起解压得到 37 个文件，`SHA256SUMS.txt`（已改为 LF 换行）
  逐文件校验全部通过，`mods.f16`、`vae_decoder.onnx`、`block27.tflite` 与设备图包逐字节一致。
- 电脑端可选 `assemble-pack.ps1`：解压全部分卷 → 逐文件校验 → 重新打包成单个 zip。
- 交付包：`dist/mediatek-npu-smoke-0.6.1.zip`（13,974,229 B，
  SHA-256 `74cc63bebda81e085d5b1a8bb12461ab0bb4bead8f9303e99b23b4975679e6a2`）。

### 14.8 单文件交付（1.0.0）：整包内嵌进 APK

- 目标：测试者**只装一个文件、点一下运行**，不需要先导入图包。
- 做法：把整个图包打成未压缩的 `anima-pack.azip`（2,094,940,161 B），作为 APK 的
  `assets/anima-pack.azip`（`aapt` 记录为 Stored）。新增 `zip_reader.cpp` 解析 zip
  中央目录，原生代码通过 asset 的 fd（`/proc/self/fd/<n>`）用 `pread` 直接读任意
  block/final/prelude 图交给 `LiteRtCreateModelFromFd`，ONNX Runtime 也从同一 fd 加载
  VAE——**不复制 2 GB、不占额外内部存储**。已解析 APK 验证该资产位于偏移 20,075,323、
  未压缩，其本地头与数据长度一致。
- 优先级：若手机里已有完整导入的图包目录，仍优先使用；否则自动使用内嵌包。
- 产物：`dist/MCA-Anima-NPU-Test.apk`，2,115,598,141 B，SHA-256
  `bc30f8b96c6ec02928a60e634b9db0ad42722d709c0653c2b53f69664b03a1f0`；
  同内容的单文件包 `dist/MCA-Anima-NPU-Test.zip`，2,115,604,413 B，SHA-256
  `aea833980363fdf7a918f56dd4bb8fa61623c7dd105f9fd7f6ed4612a3fea3ff`。
- 未验证：在 MT6899 上从 APK 资产 fd 加载图与 VAE 尚未实机验证；若某机型对
  asset fd 读取有限制，回退到 0.6.1 分卷导入路径。
- 注意：C 盘在构建期间只剩约 1.5 GB，合并 2 GB 资源需要临时空间；本次通过把
  `java.io.tmpdir` 指向 D 盘完成构建，随后已恢复原配置并移除源目录中的 2 GB 资产副本。

