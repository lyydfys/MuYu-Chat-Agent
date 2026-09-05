# MCA 遗留问题修复与验收执行手册

- 基线日期：2026-09-01
- 工作区：`E:\model\MAC`
- 目标读者：需要按显式步骤执行、不能依赖隐含上下文的实现模型
- 文档性质：修复与验收方案；本文件不是“问题已修复”的证据

## 0. 使用方法

执行者必须先完整阅读本文件，再从第一个未完成工作包开始。一次只处理一个工作包，不要同时修改 MNN、QNN、聊天模板、推荐目录和发布脚本。

每个工作包只能处于以下状态之一：

| 状态 | 含义 |
|---|---|
| `not_run` | 尚未执行，不能据此推断成功或失败 |
| `reproduced` | 已用当前 APK、模型和设备复现 |
| `code_fixed` | 代码或模型已修改，只有自动化测试通过 |
| `device_passed` | 指定真机 smoke 通过，但还不是产品入口闭环 |
| `product_passed` | 当前 APK 的 MainActivity 和认证 Local API 均通过 |
| `failed` | 当前证据明确失败，必须保留原始错误 |
| `blocked` | 缺模型、设备、许可证或外部资产，无法真实执行 |

禁止只写“应该可以”“看起来已修”“编译通过所以完成”。每个 `fixed` 结论都必须给出当前 APK SHA、模型或 bundle SHA、设备、命令、原始 JSON、日志窗口和成功字段。

## 1. 当前事实基线

| ID | 问题 | 当前可信结论 |
|---|---|---|
| R1 | MNN 生图显式 CPU | 未修复。当前 SD1.5 UNet 是 OpenCL 融合图，CPU 缺少 `GroupNorm`、`FmhaV2`、`SplitGeLU` creator |
| R2 | MNN/QNN 生图语义质量 | PNG 可生成且结构完整，但提示词遵循度和画质未达到生产级证据要求 |
| R3 | MNN 视觉聊天 | 当前 APK 的最新成功证据是纯文本；`visionValidated=false` 不能证明图片输入闭环 |
| R4 | 模型/设备覆盖 | 主要 QNN 证据只有一台 `SM8750P/HTP79`；其他 HTP、SDXL、Gen5、Gemma 4 E4B/12B 未完整覆盖 |
| R5 | MNN 输出格式 | CPU 短回答存在指令遵循问题；OpenCL 曾输出裸 `assistant` 控制文本 |
| R6 | 全量人工回归 | 基础页面和部分 API 已验证，但图片编辑、长上下文、大模型、切换恢复和跨设备组合未全量验收 |
| R7 | 发布状态 | 根工作树约有 77 个 tracked 修改和多个未跟踪实验/构建目录，不能直接视为可发布提交 |
| R8 | 质量矩阵工具 | `run-real-device-comparison.ps1` 把 `schemaVersion` 限定为 PowerShell `[int]`，可能误拒绝被解析为 `Int64` 的合法 JSON |

这些边界必须保留：

- 历史文档中旧 APK 的 MNN VLM 通过记录不能替代当前 APK 回归。
- MNN `auto` 从 CPU 回退 OpenCL 成功，不能写成“显式 CPU 已修复”。
- QNN 一步 graph smoke 不能替代 20-step 端到端生图和语义质量。
- PNG gate 只能证明文件和像素结构，不能判断对象、颜色、数量和位置。
- 代表性 ARM64 设备通过可以按项目规则开放能力，但不能生成其他设备的性能和特定 runtime 证据。

关键代码入口：

- CPU creator：`.codex/mnn-3.6.1-rebase/source/backend/cpu/CPUOPRegister.cpp`
- OpenCL creator：`.codex/mnn-3.6.1-rebase/source/backend/opencl/core/OpenCLOPRegister.cpp`
- 官方转换说明：`.codex/mnn-3.6.1-rebase/docs/transformers/diffusion.md`
- MNN 生图策略：`app/src/main/java/com/muyuchat/mca/LocalImageProvider.kt`
- backend 审计：`app/src/main/java/com/muyuchat/mca/MnnDiffusionBackendPolicy.kt`
- MNN native：`core/native/src/main/cpp/mnn_native_engine.cpp`
- 输出过滤：`core/native/src/main/cpp/mnn_stream_protocol_filter.hpp`
- 推荐目录：`core/download/src/main/java/com/muyuchat/core/download/ModelScopeClient.kt`
- 生图质量方案：`docs/LOCAL_IMAGE_QUALITY_PRODUCTIZATION_PLAN.md`
- 产品历史证据：`docs/MCA_REAL_DEVICE_REGRESSION.md`

## 2. 硬规则

### 2.1 Git 和本地文件

1. 禁止 `git reset --hard`、`git checkout --`、`git clean`。
2. 禁止覆盖或回滚不属于当前工作包的修改。
3. 禁止 `git add -A` 和 `git add .`；只有得到提交授权后才能用明确 pathspec 暂存。
4. `AGENTS.md` 和 `app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt` 必须留在 `.git/info/exclude`，不得发布。
5. `artifacts/`、`docs/experiments/`、APK、模型、Gradle 缓存和临时 SDK 不进入发布提交。
6. 旧日志只能标为历史证据；搜索到旧错误不等于当前 APK 仍失败。

### 2.2 通用设备开放

1. 不得根据芯片、机型、验证设备、认证状态、profile、allowlist 或 whitelist 隐藏或阻止下载、导入、加载、运行。
2. 硬件检测只能做推荐排序和 runtime transport 选择。
3. 未知设备必须进入通用路径，由真实 native load、graph 或 generation 决定结果。
4. 只有损坏/不完整包、非法格式、缺少二进制、真实 native load 或真实执行失败可以拒绝。
5. 单设备失败只能增加最窄 fallback，不能恢复全局设备准入表。

### 2.3 证据边界

以下结论必须分开记录，不能互相代替：

```text
executionPassed       真实 runtime 完成执行
integrityPassed       输出文件、尺寸和像素结构完整
semanticPassed        内容符合提示词或图片事实
reproducibilityPassed 固定输入的重复运行稳定
productPassed         正式 UI 与认证 Local API 均完成独立请求
```

不并发运行 Gradle；同一台设备的正式生成和性能测试也必须串行。覆盖安装用 `adb install -r`，没有明确要求时不卸载、不清数据。

## 3. 固定操作协议

初始化：

```powershell
Set-Location E:\model\MAC
$env:JAVA_HOME = 'C:\Users\sy427\.jdks\ms-17.0.15'
$adb = 'D:\model\android-sdk\platform-tools\adb.exe'

git status --short
git diff --stat
git diff --check
git submodule status
git check-ignore -v AGENTS.md
git check-ignore -v app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt
& $adb devices -l
```

若本地守卫没有被排除，先恢复本地 exclude。若真机不可见，真机步骤标记 `blocked`，不得用模拟输出补齐。

冻结本轮身份：

```powershell
git branch --show-current
git rev-parse HEAD
Get-Item app\build\outputs\apk\debug\app-debug.apk |
    Select-Object FullName,Length,LastWriteTime
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
& $adb -s 98a37aa7 shell getprop ro.product.model
& $adb -s 98a37aa7 shell getprop ro.soc.model
& $adb -s 98a37aa7 shell getprop ro.build.version.release
& $adb -s 98a37aa7 shell getprop ro.product.cpu.abi
```

证据写到唯一且本地排除的目录，例如：

```text
docs/experiments/closure-20260901/<issue-id>/<apk-sha-prefix>/<session-id>/
```

每次先复现、保存原始 JSON/PNG/logcat/exit-info 和模型 SHA，再修改。失败证据不得删除或覆盖。

固定汇报格式：

```text
Issue ID:
State:
APK path/timestamp/size/SHA-256:
Device serial/model/SoC/Android/ABI:
Model path/revision/size/SHA-256:
Requested and effective runtime/backend:
Files changed:
Commands and tests:
Device result fields:
Crash/ANR/native-signal result:
Evidence directory:
Remaining gap:
```

## 4. 执行顺序

```text
P0 质量矩阵工具
 -> P1 MNN 显式 CPU 生图
 -> P2 MNN 输出协议和模板
 -> P3 MNN 视觉聊天
 -> P4 MNN/QNN 生图语义质量
 -> P5 模型/设备覆盖矩阵
 -> P6 全量人工产品回归
 -> P7 发布工作树和最终构建
```

P0 决定质量结果是否可信；P1-P3 是确定性缺陷；P4 依赖正确执行链；P5-P6 使用修复后的候选；P7 最后处理，避免误删现有工作。

## 5. P0：修复质量矩阵配置解析

`tools/benchmarks/run-real-device-comparison.ps1` 的 `Read-BenchmarkConfig` 使用 `$schemaVersion -isnot [int]`。应接受数值类型中数学上精确等于 1 的整数，统一转为 `Int64` 比较；拒绝字符串 `"1"`、布尔值、null、0、2、1.5、NaN 和 Infinity。

修改和测试范围：

- `tools/benchmarks/run-real-device-comparison.ps1`
- `tools/benchmarks/test-real-device-comparison-offline.ps1`

验收：

```powershell
pwsh -NoProfile -File tools\benchmarks\run-real-device-comparison.ps1 `
  -ConfigPath tools\benchmarks\flagship-sm8750p-qnn-image.json `
  -ValidateConfigOnly

pwsh -NoProfile -File tools\benchmarks\test-real-device-comparison-offline.ps1
```

必须新增上述正反例；两条命令都通过后，P4 批量结果才可采信。

## 6. P1：真正支持 MNN 显式 CPU 生图

### 6.1 根因和禁止项

当前 `MNN/stable-diffusion-v1-5-mnn-opencl` 使用 `--transformerFuse` 转换。UNet 含 `GroupNorm`、`FmhaV2`、`SplitGeLU`，而 MNN 3.6.1 CPU 注册表没有这些 creator；OpenCL 注册表才有。官方 `diffusion.md` 也要求其他后端转换时去掉 `--transformerFuse`。

以下都不是修复：

- 只打开 `MNN_SUPPORT_TRANSFORMER_FUSE`。
- 只升级或重编相同 MNN 3.6.1。
- 把 status 2 改成成功或吞掉 `resizeSession` 错误。
- 把显式 CPU 静默改为 OpenCL。
- 只改推荐页文案声称 CPU 已支持。
- 手工删除 `.mnn` JSON 中的融合节点但不重建等价子图。

### 6.2 先保证推荐目录真实

`ModelScopeClient.kt` 的 `sd15_mnn_512_quality` 当前使用 OpenCL 包，却把 accelerator 标为 `CPU`。在 CPU 包真正通过前：

1. 现有 fused 包标为 `ImageEngineAccelerator.OPENCL_GPU`。
2. 描述明确写“显式 CPU 不支持，auto 可在真实 NOT_SUPPORT 后回退 OpenCL”。
3. 不隐藏下载，不按设备阻断。
4. CPU-compatible 包使用新 ID、revision 和组件 SHA，不覆盖旧包身份。

建议身份：

```text
sd15_mnn_512_opencl   现有 fused 包
sd15_mnn_512_cpu      新的未融合 CPU 包
```

### 6.3 首选方案：重新导出未融合模型

使用与 App 固定版本一致的 MNN 3.6.1 converter。准备同一 SD1.5 checkpoint、config 和 tokenizer，在仓库外或本地排除目录导出 ONNX；转换时不得传 `--transformerFuse`。`convert_mnn.py` 已自动加入 `--saveExternalData=1`，第一版保持 `--weightQuantBits=8`，减少变量。

执行前先确认所有占位路径存在：

```powershell
$exportRoot = 'E:\model\mnn-sd15-cpu-export'
$hfModel = '<absolute-sd15-source-path>'
$onnxRoot = Join-Path $exportRoot 'onnx'
$mnnRoot = Join-Path $exportRoot 'mnn'

Set-Location E:\model\MAC\.codex\mnn-3.6.1-rebase\transformers\diffusion\export
python onnx_export.py --model_path $hfModel --output_path $onnxRoot --opset 18
python convert_mnn.py $onnxRoot $mnnRoot '--weightQuantBits=8'
```

CPU 包严禁使用：

```powershell
python convert_mnn.py $onnxRoot $mnnRoot '--weightQuantBits=8 --transformerFuse'
```

输出至少包含：

```text
text_encoder.mnn
text_encoder.mnn.weight
unet.mnn
unet.mnn.weight
vae_decoder.mnn
vae_decoder.mnn.weight
tokenizer.mtok 或当前 runtime 明确支持的完整 tokenizer 资源
```

### 6.4 图算子审计

使用固定版本 `MNNConvert` 或 `MNNDump2Json`：

```powershell
& '<mnn-build>\MNNConvert.exe' -f MNN `
  --modelFile "$mnnRoot\unet.mnn" `
  --JsonFile "$mnnRoot\unet.mnn.json"
```

结构化读取全部 op type，并断言：

- 不存在 `GroupNorm`、`FmhaV2`、`SplitGeLU`。
- 输入仍为 `sample`、`timestep`、`encoder_hidden_states`。
- 支持 batch 1/2、`4x64x64` latent、`77x768` conditioning。

把审计做成可重复测试。现有 `core/native/src/test/cpp/.mnn_model_op_dump.cpp` 只输出数值 ID，不足以作为证据；应扩展为稳定 enum 名称，并在发现 forbidden op 时非零退出。

### 6.5 数值一致性

在推入手机前，用固定输入分别执行 ONNX 和 MNN：

1. text encoder：固定 77 token IDs。
2. UNet：固定 batch 1 和 batch 2 latent、timestep、conditioning。
3. VAE：固定 `1x4x64x64` latent。
4. 输出 shape 完全一致且无 NaN/Inf。
5. 记录 max abs、mean abs、cosine；量化阈值在首次参考运行后冻结，后续不得为通过而放宽。
6. 建议首轮门槛：text/UNet cosine `>=0.99`，VAE 输出 SSIM `>=0.95`。未达到则停止，不进入真机。

不要只比较最终 PNG。若偏差超标，要定位第一个偏差组件和 timestep。

### 6.6 App 接入和测试

修改：

- `core/download/src/main/java/com/muyuchat/core/download/ModelScopeClient.kt`
- `core/download/src/main/java/com/muyuchat/core/download/ModelScopeTypes.kt`
- 对应 download/catalog/bundle 测试

新包每个组件必须有 immutable revision、size、SHA-256 和许可证来源。CPU 包标 `CPU`，旧 fused 包标 `OPENCL_GPU`。不得按芯片决定下载或运行；op 审计必须阻止 fused 图再次被标为 CPU-compatible。

```powershell
$env:JAVA_HOME = 'C:\Users\sy427\.jdks\ms-17.0.15'

.\gradlew.bat :app:testDebugUnitTest `
  --tests com.muyuchat.mca.MnnDiffusionBackendPolicyTest `
  --tests com.muyuchat.mca.LocalImageMnnDiffusionDefaultsTest `
  --tests com.muyuchat.mca.LocalImageProductClosureTest `
  --no-parallel

.\tools\tests\run-native-image-contract-host-tests.ps1
```

新增断言：显式 CPU 成功只有一次 CPU attempt；requested/effective/backendMode 都是 `cpu`；`backendFallback=false`；没有 OpenCL attempt；auto 回退契约仍正常；fused OpenCL 图不能被标成 CPU-compatible。

### 6.7 真机验收

```powershell
$bundle = '/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/sd15-mnn-cpu'

.\tools\device\run-mnn-diffusion-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 -BundleRoot $bundle `
  -Mode preflight -BackendMode cpu -Steps 1 -TimeoutSeconds 240 `
  -SessionId p1-cpu-preflight

.\tools\device\run-mnn-diffusion-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 -BundleRoot $bundle `
  -Mode generate -BackendMode cpu -Runner direct `
  -Width 512 -Height 512 -Steps 1 -Seed 42 -TimeoutSeconds 900 `
  -SessionId p1-cpu-one-step

.\tools\device\run-mnn-diffusion-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 -BundleRoot $bundle `
  -Mode generate -BackendMode cpu -Runner direct `
  -Width 512 -Height 512 -Steps 20 -Seed 42 -TimeoutSeconds 1800 `
  -SessionId p1-cpu-20-step

.\tools\device\run-mnn-diffusion-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 -BundleRoot $bundle `
  -Mode generate -BackendMode cpu -Runner direct -WorkerProductPath `
  -Width 512 -Height 512 -Steps 20 -Seed 42 -TimeoutSeconds 1800 `
  -SessionId p1-cpu-worker-product
```

P1 通过必须同时满足：

```text
status=completed
result.ok=true
nativeExecution=true
outputBytes>0
pngPreserved=true
pngQuality.quality.passed=true
requestedBackendMode=cpu
effectiveBackendMode=cpu
backendMode=cpu
backendFallback=false
backendAttempts.length=1
backendAttempts[0].backend=cpu
backendAttempts[0].ok=true
```

且无 `MNN_UNET_BACKEND_UNSUPPORTED`、`resizeSession status 2`、Create execution error、FATAL、SIGSEGV/SIGABRT、ANR。最后仍需 P4 的 20-step 语义质量；“CPU 真执行”和“画质合格”是两个门。

### 6.8 次选方案：实现 CPU creator

只有无法重导出同权重模型时才使用。分别实现并注册 CPU `GroupNorm`、`SplitGeLU`、`FmhaV2`，每个算子先做独立 FP32 reference、batch 1/2、实际 SD1.5 shape、epsilon/bias/swish/mask 覆盖和 NaN/Inf 测试，再接完整 UNet。

不得复制 OpenCL kernel、使用恒等映射或空实现。`FmhaV2` 的布局、mask、scale 和数值稳定性没有逐层对齐前，session 创建成功不算完成。走此路线还必须更新 `vendor/mnn/mca-mnn-3.6.1.patch`、patch SHA 和 vendor 验证契约。

## 7. P2：修复 MNN 输出协议和模板质量

### 7.1 先分清两类错误

1. 协议污染：`<|...|>`、`assistant` 控制行、下一轮 role header 泄漏。这是必须修的 native/template 错误。
2. 模型没有严格复述短语：可能是模板、采样或模型能力问题，不能通过字符串删除伪装成正确回答。

### 7.2 固定复现矩阵

对同一完整 MNN 文本包运行 CPU/OpenCL、cold/reuse、`maxTokens=8/16/32/128`，使用英文精确短语、中文精确短语和正文中合法出现单词 `assistant` 的句子。sampling 固定为 temperature 0、topK 1、topP 1、固定 seed。

```powershell
$advanced = '{"mca_debug_trace":true,"enable_thinking":false,"thinking_budget":0}'
$model = '/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/<complete-mnn-bundle>/config.json'

.\tools\device\run-mnn-chat-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 -ModelPath $model `
  -MnnBackendType opencl -AdvancedJson $advanced `
  -Prompt 'Reply with the exact words: MNN smoke passed.' `
  -Temperature 0 -TopK 1 -TopP 1 -MaxTokens 32 `
  -ExpectedTextFragments 'MNN smoke passed.' `
  -SessionId p2-opencl-debug
```

必须保存 `mnnDebugPrompt`、`mnnDebugRawOutput`、`mnnDebugRawOutputHex`、`mnnDebugGeneratedTokenIds`、`mnnDebugTokenIdsTruncated`、`generationStopReason`、最终文本和模板/tokenizer/stop-token SHA。

### 7.3 定位顺序

1. 确认 prompt 是否只追加一次 assistant 起始头。
2. 确认裸 `assistant` 是特殊 token、词表 token、模板字面量还是 callback 拼接产物。
3. 确认模型是否生成下一轮 role header。
4. 确认 stop marker 是否跨 callback 被拆分。
5. CPU/OpenCL token IDs 是否一致；若一致而可见文本不同，问题在过滤器或 UTF-8 拼接。

### 7.4 修复原则

1. 使用 bundle 声明的 chat template 和 stop token，不按显示名称猜模板。
2. leading assistant header 只在答案开头消费一次。
3. 可见正文后出现 role header，立即停止且不泄漏 header。
4. callback 边界拆开的 marker 必须缓存后匹配。
5. 只有在行边界、协议状态和 bundle 模板同时匹配时，才可处理裸角色行。
6. 禁止 Java/Kotlin 全局 `replace("assistant", "")`，会破坏合法正文。
7. 禁止单纯减少 `maxTokens`、关闭 thinking 或添加宽泛 stop word 掩盖问题。

扩展 `core/native/src/test/cpp/mnn_stream_protocol_filter_test.cpp`，覆盖每个 marker 在每个字节位置拆分，以及正文 `The word assistant is part of this answer.`。必要时扩展 `mnn_chat_debug_trace_source_contract_test.cpp` 和 host test 脚本。

P2 通过条件：CPU/OpenCL/cold/reuse/短长 max tokens 都无 role token、EOS marker 或控制行泄漏；确定性 exact-answer 精确通过；合法正文中的普通 `assistant` 保留。

## 8. P3：完成当前 APK 的 MNN 视觉聊天闭环

### 8.1 完整包

至少确认存在并有 size/SHA：

```text
config.json
llm_config.json
llm.mnn
llm.mnn.weight
tokenizer.txt 或 tokenizer.mtok
visual.mnn
visual.mnn.weight
```

`visionReady=true` 只表示组件可加载，不表示图片影响推理。

### 8.2 两张反事实图片

可复用本地资产：

```text
docs/experiments/device-smoke/mnn-chat/qwen35-08b-vision-sm8550-20260713/inputs/shape-448.png
docs/experiments/device-smoke/mnn-chat/qwen35-08b-vision-sm8550-20260713/inputs/oracle-448.png
```

执行前记录两张图 SHA，并确保语义不同。先跑文本后图片，再跑双图反事实：

```powershell
.\tools\device\run-mnn-chat-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 `
  -ModelPath '/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/<vlm-bundle>/config.json' `
  -MnnBackendType cpu -SmokeMode text_then_image `
  -ImagePath '/storage/emulated/0/Android/data/com.muyuchat.mca/files/chat_smoke/inputs/shape-448.png' `
  -TextPreludePrompt 'Reply with only 42. What is 6 multiplied by 7?' `
  -Prompt 'Describe the image in one Chinese sentence.' `
  -MaxTokens 128 -SessionId p3-text-then-image

.\tools\device\run-mnn-chat-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 `
  -ModelPath '/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/<vlm-bundle>/config.json' `
  -MnnBackendType cpu -SmokeMode direct_counterfactual `
  -ImagePath '/storage/emulated/0/Android/data/com.muyuchat.mca/files/chat_smoke/inputs/shape-448.png' `
  -SecondImagePath '/storage/emulated/0/Android/data/com.muyuchat.mca/files/chat_smoke/inputs/oracle-448.png' `
  -Prompt 'Describe the image in one Chinese sentence.' `
  -FirstImageExpectedTextFragments '<fact-only-in-image-1>' `
  -SecondImageExpectedTextFragments '<fact-only-in-image-2>' `
  -MaxTokens 128 -SessionId p3-counterfactual
```

占位事实必须先通过人工查看图片确定，不能照抄示例。

通过条件：`visionReady=true`、`visualModelPath` 非空且 fingerprint 正确；恰好一条 first/second success；输入 SHA 不同；输出文本 SHA 与保存文本一致；两输出默认不同且分别包含唯一事实；embedding shape/finite/NaN/Inf 正常；无 SIGSEGV、FATAL、ANR、worker/Binder 死亡。

### 8.3 正式产品入口

debug smoke 通过后，用同一 APK 和模型完成：

1. MainActivity 新建对话，使用正式 Photo Picker 发送图片 1，保存 UI requestId、native sequence、回答。
2. 启用正式 Local API，保持真实认证和 provider，不启动影子 server。
3. 通过 `/v1/chat/completions` 的 `image_url` 发送图片 2，保存 API requestId、native sequence、HTTP 200 和正文 SHA。
4. UI/API 的 modelId、profileId、active loaded signature、committed execution signature 一致；requestId、native sequence、输入 SHA 各自不同。
5. 最后 `busy=false`、engine ready、MainActivity resumed、进程存活。

只有本节完成，P3 才能标为 `product_passed`；旧 APK 记录只能作历史参考。

## 9. P4：建立可证明的 MNN/QNN 生图语义质量

### 9.1 先修数学执行

按顺序排查 tokenizer（NFC/Unicode/BPE/EOS/PAD）、text encoder dtype/shape、正负 conditioning、CFG 分支和 scale、scheduler timestep/sigma/prediction、latent seed/scaleModelInput、VAE scaling location、model family/profile。每次只改一层，并定位第一个偏差 timestep。

重点风险：FP32 `token_emb` 被误当成两份 FP16；简化 CLIP tokenizer；negative prompt 未消费；scheduler sidecar 未使用；SD2.1 错用 SD1.5 scheduler；Gen5 VAE 重复 scaling；SDXL 仍走 1-step proof 路径。

### 9.2 固定矩阵

```text
resolution=512x512
steps=20
seeds=42,43,44
cfg=7.0
scheduler=模型 profile 指定值
negative prompt=固定并记录
model revision/components SHA=固定
```

至少覆盖单对象颜色、人物结构、产品数量材质、风景层次、多对象空间关系五类提示词。优先复用 `tools/benchmarks/flagship-sm8750p-qnn-image.json` 的 frozen cases，为 MNN 建立相同输入的本地配置。

MNN 示例：

```powershell
.\tools\device\run-mnn-diffusion-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 `
  -BundleRoot '/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/<mnn-bundle>' `
  -Mode generate -BackendMode opencl -Runner direct `
  -Prompt 'A bright blue sports car on a snowy mountain road, no text.' `
  -Width 512 -Height 512 -Steps 20 -Seed 42 -CfgScale 7 `
  -SessionId p4-mnn-bluecar-42
```

QNN 示例：

```powershell
.\tools\device\run-qnn-image-smoke.ps1 `
  -Adb $adb -Serial 98a37aa7 `
  -BundleRoot '/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/<qnn-bundle>' `
  -Mode semantic -Prompt 'A bright blue sports car on a snowy mountain road, no text.' `
  -NegativePrompt '' -Width 512 -Height 512 -Steps 20 -Seed 42 -CfgScale 7 `
  -SessionId p4-qnn-bluecar-42
```

QNN PNDM 四步只是最低可执行 smoke，不是画质结论。

### 9.3 四层门槛

**执行门**：真实 text encoder、UNet、VAE；MNN effective backend 与请求一致；QNN `nativeExecution=true`、`qnnGraphExecution=true`、`npuActive=true`、`fallback=false`；requested/resolved/nativeEffective 的 scheduler、steps、CFG、seed 一致。

**完整性门**：PNG 可解码，尺寸/通道/bytes/SHA 正确；无单色、低动态、横纹、全噪声或残缺发布。它应称 integrity/corruption gate，不能称语义通过。

**语义和画质门**：使用 `image_generation_v1` rubric，权重为 prompt fidelity 0.30、composition 0.20、subject structure 0.20、artifact freedom 0.20、visual coherence 0.10。至少两名独立审阅者隐藏 backend/文件名查看原图；每张加权分 `>=3.5/5`、全组中位数 `>=4.0/5`、每维 `>=3.0/5`，关键对象/数量/颜色/空间关系完全错误为 critical defect，critical defect 必须为 0。没有两名审阅者时写 `not_run`。

**重复和产品门**：每 case 三次有效输出，不挑图；无 crash/ANR/OOM，耗时/内存离散度在配置阈值内；MainActivity 和认证 Local API 各独立生成一张，requestId/native sequence/PNG SHA 不复用。

### 9.4 回归要求

任何 tokenizer、scheduler、CFG 或 VAE 修改都必须同时回归 MNN 和 QNN 数学契约。最终结果记录 bundle fingerprint，不能只写显示名称。

## 10. P5：补齐模型和设备覆盖

### 10.1 最小身份

每条结果至少绑定：

```text
APK SHA
+ runtime/native library SHA and version
+ package fingerprint/revision
+ model family/size/quant
+ requested/effective backend
+ device ABI/OS/driver
```

设备型号不是功能准入键；设备差异只产生本机性能和兼容证据。

### 10.2 必测矩阵

| 维度 | 最少覆盖 |
|---|---|
| 聊天 runtime | llama.cpp/GGUF、MNN、LiteRT-LM、GenieX QAIRT/llama.cpp |
| 聊天 backend | CPU、OpenCL/GPU、Qualcomm NPU（包真实支持时） |
| 聊天规模 | E2B、E4B、12B；记录 public package 是否真实存在 |
| 生图 runtime | MNN Diffusion、QNN/QAIRT、stable-diffusion.cpp |
| 生图 family | SD1.5、SDXL、Gen5；图片编辑/img2img/inpaint 单列 |
| 生命周期 | cold、reuse、模型切换、取消后恢复、worker 死亡后恢复 |
| 上下文 | 短输入、长上下文、KV miss、KV hit、追加 token |
| 产品入口 | debug smoke、MainActivity、认证 Local API；最终只认后两者 |
| 设备 | 当前 SM8750P/HTP79、另一 HTP 世代（若可得）、未知 profile ARM64 通用路径 |

每格只允许：`not_run`、`not_available`、`package_invalid`、`load_failed`、`execution_failed`、`pass`。不允许空白，也不允许把“未测试”改写成“不支持”。

### 10.3 Gemma 4/.litertlm 资产

对 E2B、E4B、12B 分别记录国内镜像/ModelScope URL、上游 immutable revision、文件名/bytes/SHA、`.litertlm` 内部必需模型段（例如 `TF_LITE_PREFILL_DECODE`）、CPU/GPU/Qualcomm 变体、许可证和再分发限制。

- 找不到公开 Qualcomm 专包时写 `not_available`，不要制造链接或把 CPU/GPU 包标成 NPU。
- 推荐页可显示研究目标，但 `downloadable` 必须反映文件真实存在。
- 小包/截断包在导入阶段按缺段、size 或 SHA 明确拒绝，不能等 native 崩溃。
- 未知设备仍可尝试真实兼容包，不能因不在表中隐藏按钮。

### 10.4 12B 完整加载

文件映射成功不等于模型可用。至少完成：

```text
完整 SHA -> 格式/segment 预检 -> 隔离 worker load
-> 短 prefill/decode -> 第二轮 reuse -> 取消
-> 卸载/切换 runtime -> Binder/进程恢复
```

记录峰值 RSS/PSS、available RAM、温度、TTFT 和 decode TPS。不能用静态 RAM 或芯片表提前阻断；真实 OOM/LMKD 才是具体失败。

### 10.5 QNN/HTP/SDXL/Gen5

相关入口：`QnnImageRuntimeStager.kt`、`LiteRtQualcommRuntimeStager.kt`、`ImageExecutionProfileResolver.kt`、`QnnHtpArchTelemetry.kt`、`SdxlImagePhaseProtocol.kt`。每个包严格按：

```text
组件 size/SHA -> manifest/profile -> runtime directory
-> native load -> graph context -> one-step smoke
-> family-correct semantic steps -> PNG integrity -> semantic quality
-> MainActivity -> authenticated Local API -> repeat/cancel/recovery
```

runtime 目录由包声明和真实加载结果决定；HTP arch 只用于 transport 选择和遥测，不得成为下载/运行白名单。

## 11. P6：全量人工产品回归

### 11.1 候选冻结

完成 P0-P5 后，单线程构建候选 APK，记录 timestamp/size/SHA 和安装后 `base.apk` SHA。任何代码重建都会使此前产品验收失效，必须重跑受影响项。

### 11.2 主界面和状态

模拟普通用户逐项操作：首次启动、升级已有数据、返回/前后台、会话新建重命名删除、助手和模型选择、推荐页分组、下载暂停/恢复/重试、完整/损坏包导入、设置、Local API、图片页、模型管理、空列表、旋转或窗口变化。

空列表是合法状态；只有崩溃、错误操作、错误状态或无法继续使用才记缺陷。

### 11.3 每类至少一个真实加载

聊天：GGUF/llama.cpp CPU、MNN CPU、MNN OpenCL、LiteRT-LM CPU/GPU/NPU、GenieX fallback/hybrid、QAIRT（真实包存在时）、MNN VLM。

生图：MNN OpenCL、MNN 显式 CPU（P1 通过后）、QNN SD1.5、QNN SDXL/Gen5（完整包存在时）、stable-diffusion.cpp、图片编辑/img2img/inpaint（实现和完整包存在时；否则 `not_run`）。

每类执行 cold load、首轮、第二轮、取消、重新生成、离开后回来。聊天额外测长上下文、KV hit/miss；生图额外测 20-step、取消和下一次恢复。

### 11.4 Local API

验证 `/health`、`/v1/models`、非流式和 SSE `/v1/chat/completions`、MNN VLM `image_url`、`/v1/images/generations`、busy/取消/错误响应、正确/错误认证和关闭服务行为。API key 不得写入日志或证据。

### 11.5 日志窗口

每项测试前记录时间边界；测试后保存：

```powershell
& $adb -s 98a37aa7 logcat -d -b main -b system -b crash
& $adb -s 98a37aa7 shell dumpsys activity exit-info com.muyuchat.mca
& $adb -s 98a37aa7 shell pidof com.muyuchat.mca
& $adb -s 98a37aa7 shell pidof com.muyuchat.mca:local_chat
& $adb -s 98a37aa7 shell pidof com.muyuchat.mca:local_image
```

只统计 MCA 自身的 `FATAL EXCEPTION`、ANR、SIGSEGV/SIGABRT、OOM、LMKD、DeadObjectException 和 worker timeout；不能把其他应用日志算到 MCA。

## 12. P7：整理发布工程

### 12.1 只读审计

```powershell
git status --short
git diff --name-only
git diff --stat
git diff --check
git diff --submodule=log
git -C third_party/llama.cpp status --short
git -C third_party/stable-diffusion.cpp status --short
git -C third_party/stable-diffusion.cpp apply --reverse --check `
  ..\patches\stable-diffusion.cpp-mca-android.patch
```

反向检查返回 0 只证明该 patch 已应用，不代表子模块没有其他改动。

### 12.2 按责任域审查

分为 MNN vendor/native/chat、MNN diffusion/catalog、LiteRT-LM、QNN image/runtime/SDXL、GenieX/llama.cpp、smoke/benchmark/quality tools、UI/API、documentation。逐组执行 `git diff -- <pathspec>`，确认每个改动有对应测试和证据；不要为使状态干净而删除未知文件。

### 12.3 自动化和构建门

```powershell
$env:JAVA_HOME = 'C:\Users\sy427\.jdks\ms-17.0.15'

.\gradlew.bat :app:testDebugUnitTest --no-parallel
.\tools\tests\run-native-image-contract-host-tests.ps1
pwsh -NoProfile -File tools\benchmarks\test-real-device-comparison-offline.ps1
pwsh -NoProfile -File tools\device\tests\test-png-quality-gate-offline.ps1
pwsh -NoProfile -File tools\device\tests\test-run-mnn-chat-smoke-strict-counterfactual.ps1

.\gradlew.bat :app:assembleDebug `
  '-Pmca.abis=arm64-v8a' `
  '-PmcaQnnSdkRoot=E:\model\qairt-sdk\qairt\2.45.0.260326' `
  --no-parallel
```

QAIRT SDK 路径不存在时停止并报告，不要用假库绕过。

### 12.4 安装和 APK 身份

```powershell
$apk = 'E:\model\MAC\app\build\outputs\apk\debug\app-debug.apk'
Get-Item $apk | Select-Object FullName,Length,LastWriteTime
Get-FileHash $apk -Algorithm SHA256
& $adb -s 98a37aa7 install -r $apk
& $adb -s 98a37aa7 shell pm path com.muyuchat.mca
```

拉取安装后的 `base.apk` 到唯一证据目录并核对 SHA。公开发布按 `docs/RELEASE.md` 构建签名 release APK；debug APK 不能公开发布。

### 12.5 暂存规则

只有得到提交授权后才能：

```powershell
git add -- <explicit-file-1> <explicit-file-2>
git diff --cached --stat
git diff --cached --check
```

若 staged 列表含 `AGENTS.md`、本地策略测试、`artifacts/`、`docs/experiments/`、`app/build/`、`.gradle*`、模型、APK、SDK 或设备日志，立即停止并取消对应暂存，不影响其他修改。不自动 push。

## 13. 失败分类

```text
文件缺失、size/SHA 不符
  -> 包损坏；重新下载或导入，不改 runtime

包完整但缺 TF_LITE_PREFILL_DECODE 等必需段
  -> 格式/变体不兼容；导入阶段明确拒绝

native load 失败
  -> 检查 runtime/version/ABI/格式，保留具体 native 错误

load 成功但 graph/resize 失败
  -> 算子、shape、backend 或 runtime contract；不要归因于“未认证设备”

graph 成功但 PNG 损坏
  -> VAE、文件发布、worker 生命周期或编码

PNG 完整但不符合提示词
  -> tokenizer、conditioning、CFG、scheduler、latent/VAE scaling 或 profile

VLM 两图回答相同
  -> 图片未进入 prefill、embedding 被复用或 session 污染

出现 assistant/role token
  -> chat template、stop token 或 stream protocol；禁止全局字符串删除

worker crash/DeadObjectException
  -> 保存 tombstone/exit-info，修隔离和生命周期；不要在主进程继续原生重试

测试脚本失败
  -> 先修工具；不能把工具失败算成模型失败或模型通过
```

## 14. 最终完成定义

只有全部满足，才能宣称当前遗留问题闭环：

1. MNN CPU：显式 CPU 请求、真实 CPU 执行、无 fallback，完成 20-step PNG 和语义质量。
2. MNN/QNN 质量：执行、完整性、语义、可重复性和产品入口五个状态分别通过。
3. MNN VLM：不同图片真实进入视觉 prefill，产生符合各自事实的不同回答，当前 APK 的 UI/API 均通过。
4. 输出格式：CPU/OpenCL 无 role/protocol 污染；exact-answer 通过，同时保留合法正文中的普通 `assistant`。
5. 覆盖矩阵：每个组合有明确状态；缺包写 `not_available`，未跑写 `not_run`，不得伪装 pass 或 unsupported。
6. 通用设备开放测试通过，不存在设备/芯片/认证白名单阻断。
7. MainActivity、认证 Local API、取消恢复、长上下文、KV cache 和图片任务完成候选 APK 回归。
8. 全量自动化、单线程 arm64 构建、APK 安装哈希、签名和限定日志窗口通过。
9. 发布改动逐文件审查；本地策略文件、实验产物、模型和 APK 未进入 staged/commit。
10. 最终报告只引用本轮 APK SHA 对应的证据，历史错误明确标为 historical 或 superseded。

任何一项缺少真实资产或真机证据，应准确写 `blocked` 或 `not_run`。不能通过回退、吞错、文案或放宽门槛制造“全部修复”。
