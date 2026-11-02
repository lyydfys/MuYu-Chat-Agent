# 天玑 NeuroPilot + Anima 剩余实现执行提示词

本文给弱模型或初级开发者执行。目标是把当前 `:core:mediatek-native` 的 SDK-gated 契约推进到真实的 MediaTek NeuroPilot/Neuron Anima 生图；如果外部条件不满足，必须停在可审查的 `BLOCKED`，不能用 CPU、OpenCL、MNN 或 QNN 冒充天玑 NPU。

## 当前状态

已经存在：

- `core/mediatek-native`：Anima 三图 manifest、路径/哈希/shape/dtype/layout 校验、SDK marker 预检、`UNVERIFIED`/`BLOCKED`/`READY` 状态和 NPU evidence 契约。
- `docs/MCA_DIMENSITY_NPU_ANIMA_IMPLEMENTATION_PLAN.md`：总体架构、公开参考和 Go/No-Go 条件。
- 现有 Anima CPU 路径和图片任务 owner；它们是基线，不得复制成第二套队列。

还没有：

- MediaTek NeuroPilot/Neuron SDK、授权条件和设备 runtime；
- Qwen3 文本编码器、Anima DiT、Qwen VAE 的 TFLite/Neuron 图；
- TFLite Neuron delegate JNI；
- MediaTek worker、MainActivity/Local API 接入和真实设备 evidence。

因此，在拿到以下外部输入前，不能进入 native 实现阶段：

1. MediaTek 书面允许的 SDK/AAR/headers、版本、ABI、Android API 和分发条件。
2. 至少一台真实 ARM64 Dimensity 设备，能运行目标 Android 版本，并能提供 logcat、delegate 分区或官方 NPU trace。
3. Anima 模型来源和许可证，以及可转换的 diffusion、VAE、Qwen3 text encoder 权重。
4. 一个固定的转换工具链版本和转换配置。

如果任一项缺失，执行 Phase 0 到 Phase 1 后停止并输出 `BLOCKED`。

## 不可违反的规则

1. 开工前完整读取 `AGENTS.md`、`docs/architecture/MCA_PROJECT_HARD_LOCK.md` 和本文件。
2. 先运行基线命令，记录当前分支、HEAD、工作区、子模块 revision；禁止 `git reset --hard`、`git clean`、覆盖其他 Agent 修改或清理未知目录。
3. `AGENTS.md` 和 `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 是本地 safeguard，禁止提交或发布。
4. 不得伪造 `libneuron.so`、TFLite delegate、NPU trace、分区数量、输出 checksum 或 `npuActive=true`。
5. 不得把 QNN/HTP、MNN `MNN_CONVERT_NEUROPILOT`、NNAPI 通用执行或 CPU 结果改名成 MediaTek NPU。
6. 设备型号只能用于推荐、调参和证据记录，不能作为下载、导入、加载或运行的 allowlist/denylist。
7. 不新增第二套 `ImageGenerationOwner`、图片队列、状态 writer、worker 或图库提交链。UI、Local API、Worker 必须复用现有 owner。
8. 未知设备可以尝试通用路径；只有具体的 package 损坏、格式错误、native load 失败、graph execute 失败或输出校验失败才能拒绝一次请求。
9. 所有 SDK、模型、图和二进制只记录来源、版本、大小、SHA-256、许可证和 ABI；私有 SDK、权重、APK、`.so` 不入库。
10. 没有真实 semantic PNG 和 NPU evidence 时，状态最多是 `UNVERIFIED`，不能是 `READY`。

## 统一状态和证据格式

每个 Phase 结束时必须写一个 JSON 或 Markdown 记录，字段至少包括：

```text
phase
status: PASS | FAIL | NOT_RUN | BLOCKED
sourceRevision
submoduleRevisions
changedFiles
ownerAndStateWriter
commands
matchedTests
artifacts
unverified
rollback
nextPrerequisites
```

NPU 证据至少包括：

```text
requestedRuntime=MEDIATEK_NEURON
actualDelegate=<实际 delegate 名称>
actualRuntimeVersion=<设备实际版本>
stages=[TEXT_ENCODER,DIFFUSION,VAE]
npuPartitions=<真实数量>
cpuFallbackOps=<真实数量>
semanticGeneration=true
outputMime=image/png
outputSha256=<64 hex>
deviceModel=<证据记录，不作准入条件>
androidApi=<版本>
sdkVersion=<版本>
graphHashes=<三个图 hash>
```

`delegate 创建成功`、`模型 load 成功`、`一张随机 tensor 输出`都不能单独构成生图通过证据。

## Phase 0：重新锁定基线

**写集：** 只写 `artifacts/architecture/<date>-dimensity-anima/` 的脱敏日志和 manifest，不改生产代码。

**执行：**

```powershell
Set-Location E:\MAC
git rev-parse HEAD
git branch --show-current
git status --short --branch
git submodule status --recursive
git -C third_party/stable-diffusion.cpp status --short --branch
git -C third_party/MNN status --short --branch
python tools/architecture/check_architecture_rules.py
```

记录当前脏改动，不要恢复它们。确认现有 `core/mediatek-native` 测试仍然通过：

```powershell
./gradlew.bat :core:mediatek-native:testDebugUnitTest :core:mediatek-native:verifyMediaTekSdk --offline --no-daemon --console=plain
```

**通过条件：** 能区分本次写集、用户已有改动和子模块内部 diff；架构门禁 19/19 通过；无 SDK 时模块报告 `BLOCKED`。否则停止。

## Phase 1：SDK 和设备准入审查

**写集：** 只更新 Phase 记录，不把 SDK 复制到仓库。

**必须收集：**

- NeuroPilot/Neuron SDK 版本和下载来源；
- TFLite Shim + Neuron Delegate 或 NNAPI Delegate 的官方 sample；
- Android 普通第三方 APK 是否允许加载 runtime；
- `TfLiteNeuronDelegateCreate/Delete` 或等价 API 的头文件、AAR、ABI；
- 设备侧需要的 `libneuronusdk_adapter.mtk.so`、driver、权限和 linker namespace 条件；
- online compile/offline DLA compile 的设备绑定、首次编译延迟和 fallback 语义；
- 目标 Dimensity/MDLA、Android API、arm64-v8a 支持矩阵。

**禁止：** 不能因为目录里有一个空 `VERSION` 文件就认为 SDK 可用。把 SDK 根目录传给：

```powershell
./gradlew.bat :core:mediatek-native:verifyMediaTekSdk `
  -PmcaMediatekSdkRoot=C:\path\to\vendor\sdk `
  -PmcaMediatekNativeEnabled=true
```

**停止条件：** 没有授权、没有普通 APK 部署路径、没有匹配 ABI 的 runtime、没有可运行的真实设备，状态写 `BLOCKED`，不要写 JNI 或 production runtime。

## Phase 2：仓库外最小 TFLite/Neuron spike

不要从完整 Anima 开始。先在仓库外建立一个临时 spike 目录，例如 `C:\mca-mtk-spike`，不要将生成物复制进仓库。

**目标：** 证明 SDK、delegate、设备 runtime 和 TFLite interpreter 可以共同工作。

**顺序：**

1. 用官方 sample 的最小模型或一个单层 VAE block。
2. 固定静态 shape、dtype、layout、输入填充值和输出 checksum。
3. 运行 CPU reference，再运行 Neuron delegate。
4. 打印 delegate 分区数量、每个分区的设备、未接管 op、SDK/runtime 版本和耗时。
5. 使用官方 trace/perf counter 证明至少一个分区执行在 NPU/MDLA。

**通过条件：**

- delegate 真正加载；
- 至少一个 NPU 分区执行；
- CPU 与 NPU 输出在约定误差范围内；
- trace 能在设备上复现；
- 失败时能区分 load、compile、unsupported op、execute 和 output mismatch。

如果所有 op 都在 CPU，结果是 `FAIL`，不能通过增加日志字段改成 PASS。若只有某一设备能跑，记录兼容性 evidence，但不要写设备准入名单。

## Phase 3：Anima 三图转换

Anima 不是 SD1.5/SDXL UNet。不要复用 QNN 的 4-channel SD graph contract，也不要把完整 pipeline 粗暴导出成一个动态图。

**固定图角色：**

| stage | 内容 | 首版建议 |
|---|---|---|
| `TEXT_ENCODER` | Qwen3-0.6B-Base 文本编码器 | 固定 token 上限、batch=1、静态 mask/position 输入 |
| `DIFFUSION` | Anima DiT/denoiser | 固定 latent shape、hidden size、head 数和 scheduler step 输入 |
| `VAE` | Qwen Image VAE decoder | 先固定一个输出尺寸，确认 latent layout 和 pixel range |

**转换步骤：**

1. 记录原始权重文件名、来源、许可证、大小、SHA-256。
2. 从官方/上游实现导出 CPU reference，保存一组脱敏 tensor fixture 和 PNG golden。
3. 先转换 VAE，再转换单个 DiT block，最后才转换完整 DiT 和 text encoder。
4. 对每个图固定输入输出名称、shape、dtype、layout、量化参数和 graph hash。
5. 对每个图运行 CPU-vs-Neuron 数值比较；记录不支持的算子和 fallback 原因。
6. 生成 `mca.image_engine.bundle.v2` manifest，路径必须是 `graphs/...`，不能包含绝对路径或 `..`。

**必须保留的 Anima 语义：** Qwen/T5 side data、16-channel latent、28 层 DiT、Euler/scheduler 参数、CFG 6.0 的真实 profile。不要把 T5 token side data 误写成第二个神经文本编码器，除非实际 loader 证明它是独立 graph。

**停止条件：** 任一图只能 CPU、动态 shape 无法固定、量化误差不可接受、缺少设备支持或 VAE 输出异常时，停止在该图并记录 `BLOCKED`/`FAIL`。先修图转换，不要继续接入 Android。

## Phase 4：仓库外完整 semantic pipeline

**目标：** 在临时 native 程序中完成：

```text
tokenizer
  -> Qwen3 text graph
  -> CPU scheduler 初始化
  -> 多步 Anima DiT graph
  -> Qwen VAE graph
  -> CPU PNG 编码
```

CPU 负责 tokenizer、prompt packing、scheduler、IO 和 PNG；NPU 负责实际接管的 text/DiT/VAE 子图。若有 CPU fallback，必须把数量和原因写入 evidence。

**固定最小参数：** 384x384 或设备允许的最小尺寸、1 个 prompt、Euler、CFG 6.0、固定 seed、2 到 4 steps 起步，然后再增加到产品 steps。

**通过条件：**

- 输出是可解码 PNG；
- 三个 stage 都真实执行；
- 至少一个 stage 有 NPU 分区；
- semantic generation 完成而不是单次随机 tensor；
- 重复运行输出和误差在约定范围；
- 取消和超时能释放 interpreter/delegate；
- 记录完整 evidence。

没有这一阶段的证据，不允许改 `LocalImageRuntime` 或显示 NPU 选项。

## Phase 5：实现独立 MediaTek native runner

**写集：**

- `core/mediatek-native/src/main/cpp/`：JNI、TFLite interpreter、delegate 生命周期、tensor copy、stage runner、evidence journal；
- `core/mediatek-native/CMakeLists.txt` 或等价 CMake 文件；
- `core/mediatek-native/src/main/java/.../MediaTekNeuronBridge.kt`；
- 仅在 SDK gate 通过时修改 `build.gradle.kts` 的 externalNativeBuild。

**实现要求：**

1. SDK 通过 `MCA_MEDIATEK_SDK_ROOT` 或 `-PmcaMediatekSdkRoot` 注入，不能硬编码本机路径。
2. 没有 SDK 时不编译 vendor include、不链接假库、不打包空 `.so`。
3. JNI 只接受 typed manifest/profile/request，不接受 `Any`、万能 container 或字符串 backend 猜测。
4. `load` 只报告 manifest、图和 runtime 可加载；`READY` 必须由 semantic PNG + NPU evidence 结算。
5. 每次请求有 requestId、cancel、timeout、worker teardown 和幂等终态。
6. delegate/interpreter/context 由一个明确的 runtime owner 创建、使用、取消、释放；页面重组不能创建第二份。
7. 所有 native 错误映射到具体 stage：`sdk_missing`、`delegate_load_failed`、`graph_compile_failed`、`unsupported_op`、`graph_execute_failed`、`output_invalid`、`cancelled`。

**不得做：** 不把 `core/sd-native` 的 CPU `backend=cpu` 改成 `backend=npu`；不把 QNN native bridge 改名；不在 `MainActivity` 里直接调用 JNI。

## Phase 6：接入 bundle/profile 契约

只有 Phase 4/5 通过后，才修改生产契约。每次只改一个切片并运行对应单测。

**建议写集：**

- `app/src/main/java/com/muyuchat/mca/LocalImageBundleContracts.kt`
- `app/src/main/java/com/muyuchat/mca/DownloadedBundleManifest.kt`
- `app/src/main/java/com/muyuchat/mca/DownloadedImageExecutionProfile.kt`
- `app/src/main/java/com/muyuchat/mca/ImageExecutionProfileJsonBehavior.kt`
- `app/src/main/java/com/muyuchat/mca/ImageExecutionGenericProfile.kt`
- `core/download/src/main/java/com/muyuchat/core/download/ModelScopeImageEngineContracts.kt`
- 对应 fixture 和 focused tests。

**要求：**

1. 增加 `MEDIATEK_NEURON` runtime 和 `MEDIATEK_NPU` accelerator 时保留旧 JSON 值和旧安装兼容。
2. 新 profile 必须绑定 `ANIMA`，不能让 QNN、MNN 或普通 SD bundle 误进入 NeuroPilot runner。
3. 解析依据 bundle/graph/manifest 的真实证据，不依据设备型号。
4. 缺少组件、SHA/大小错误、graph schema 错误要给具体错误；不能隐藏下载或导入入口。
5. 不把 NPU profile 的默认值套用到 SD1.5/SDXL，也不能让现有 QNN 逻辑出现 `else` 误分派。
6. schema 演进必须有旧 fixture、迁移、回退和中断恢复测试。

建议每次执行：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests '*Image*Profile*' --offline --no-daemon --console=plain
./gradlew.bat :core:mediatek-native:testDebugUnitTest --offline --no-daemon --console=plain
```

## Phase 7：接入现有 ImageGenerationOwner 和 Worker

**唯一调用链：**

```text
MainActivity / Local API
  -> ImageGenerationOwner
  -> LocalImageGenerationCoordinator
  -> LocalImageMediatekOperations
  -> existing worker protocol/service
  -> MediaTekNeuronBridge
  -> image validation / gallery commit
```

不要新建第二个图片队列或第二套图库提交逻辑。

**必须覆盖：**

- requestId/lease/epoch 贯穿 native request；
- UI 取消真正调用 native cancel，不能只停止 Flow collector；
- Worker 进程死亡后明确结算为失败/取消，不留下生成中标志；
- 旧请求回调不能写入新请求；
- Native PNG 写入临时文件后原子 rename，再交给现有 asset commit；
- NPU 请求失败时显示具体 stage 错误，并允许用户选择 CPU Anima 路径；
- CPU fallback 不能悄悄标成 `MEDIATEK_NPU`。

**测试：**

- owner request identity/取消/超时/重复请求；
- worker death/reconnect；
- Local API 与 MainActivity 使用同一 owner；
- 缺 graph、损坏 graph、delegate load failure、output invalid；
- generic CPU fallback 仍可尝试，未知设备不被隐藏。

## Phase 8：加入调试诊断，但不污染产品文案

增加 debug-only 或受控 diagnostics 输出：

- requested runtime；
- actual delegate/runtime version；
- graph stage、partition、fallback op；
- model/graph hash；
- input/output shape/dtype/layout；
- compile mode、首次编译延迟、执行耗时、峰值内存；
- cancel/teardown 结果。

禁止输出 prompt、API key、私人图片内容。产品界面只有在 `provesNpuExecution=true` 后才显示真实 NPU 状态；预检状态显示“未验证”或具体阻断原因。

## Phase 9：双入口和设备验收

至少使用：

1. 一台真实 Dimensity ARM64 设备；
2. 一台非目标或未知设备，用来验证通用路径没有被芯片准入阻断。

按以下顺序执行：

| 验收 | 必须结果 |
|---|---|
| 单图 text/DiT/VAE smoke | delegate、分区、输出 checksum、NPU trace |
| semantic 2-4 steps | PNG、三 stage、fallback 统计 |
| semantic 产品 steps | PNG、耗时、内存、温度/稳定性 |
| MainActivity | 选择、进度、取消、预览、图库提交 |
| authenticated Local API | 同一 owner、同一 requestId/终态、SSE/JSON 正确 |
| 后台/进程死亡 | 恢复或明确失败，不悬挂 |
| 缺件/损坏 | 具体失败，CPU 路径仍可尝试 |
| 未知设备 | 不隐藏下载/导入/运行，真实 native 条件决定结果 |

任何一行失败都不能写“天玑 NPU 已支持”。

## Phase 10：构建和发布门禁

**无 SDK 的开发机：**

```powershell
./gradlew.bat :core:mediatek-native:testDebugUnitTest :core:mediatek-native:verifyMediaTekSdk --offline --no-daemon --console=plain
```

结果应为测试 PASS、SDK `BLOCKED`，不能生成含假 vendor 库的 APK。

**有 SDK 的验证机：**

```powershell
./gradlew.bat :core:mediatek-native:verifyMediaTekSdk `
  -PmcaMediatekSdkRoot=C:\path\to\sdk `
  -PmcaMediatekNativeEnabled=true
./gradlew.bat :app:assembleDebug `
  -PmcaMediatekSdkRoot=C:\path\to\sdk `
  -PmcaMediatekNativeEnabled=true
```

打包前检查：

- APK 中只有授权、匹配 ABI 的 runtime；
- 没有临时 graph、私有权重、调试 prompt 或 API key；
- `readelf`/`llvm-readelf` 依赖闭合；
- 设备侧 runtime 版本与 SDK/图转换版本匹配；
- NPU evidence 与 APK、源码、模型、SDK、设备版本可追溯。

## 完成定义

只有同时满足以下条件，才能把状态改成 `READY` 或对外写“天玑 NPU Anima 生图可用”：

1. SDK、授权和普通 APK 部署方式已审查；
2. text、DiT、VAE 三图有固定 hash 和 tensor contract；
3. 仓库外 spike 证明真实 NPU 分区；
4. 仓库内 native runner 完成 load、cancel、teardown 和 evidence；
5. semantic generation 真实输出 PNG；
6. MainActivity 和 Local API 复用同一 owner；
7. CPU fallback、失败、取消、进程死亡和图库提交有测试；
8. 至少一台 Dimensity 真机通过，未知设备没有被 allowlist 阻断；
9. 架构门禁、focused tests、设备证据和发布检查全部通过。

缺少任何一项时，最终状态只能是 `BLOCKED`、`UNVERIFIED` 或 `NOT_READY`。

## 给弱模型的直接执行提示词

把以下内容作为下一次 Agent 的完整任务提示，不要删掉停止条件：

```text
你负责把 MCA 项目的 MediaTek NeuroPilot/Neuron Anima 生图从 SDK-gated 契约推进到真实可验证实现。你不是唯一 Agent，工作区有既有脏改动，禁止 reset、clean、覆盖他人文件。

先读：AGENTS.md、docs/architecture/MCA_PROJECT_HARD_LOCK.md、docs/MCA_DIMENSITY_NPU_ANIMA_IMPLEMENTATION_PLAN.md、docs/MCA_DIMENSITY_NPU_ANIMA_REMAINING_EXECUTION_PROMPT.md。

先执行 Phase 0 基线命令并报告。一次只做一个 Phase。开始前列出写集、owner、状态 writer、回退点和验证命令。没有 MediaTek SDK/授权 runtime/真实 Dimensity 设备时，只做 Phase 0-1 并把结果写 BLOCKED；绝不写假 JNI、假 delegate、假 NPU evidence。

先在仓库外做单图 delegate spike，再做 VAE、DiT、text 三图转换，再做仓库外 semantic PNG。只有这些全部通过，才能改生产 runtime、worker、MainActivity 或 Local API。Anima 必须保持 Qwen3 text encoder、Anima DiT、Qwen VAE 三张图和真实 scheduler 语义，不能复用 QNN 的 SD graph。

每个阶段结束输出：Phase、基线 revision、写集、实际行为、唯一 owner/state writer、命令和匹配测试数、PASS/FAIL/NOT_RUN/BLOCKED、未验证项、回退方式、下一步前置条件。

只有真实三阶段 semantic PNG、Neuron delegate、NPU 分区、runtime 版本、output SHA-256、MainActivity/Local API 双入口和设备 evidence 全部通过，才允许写 READY 或“天玑 NPU 已支持”。
```
