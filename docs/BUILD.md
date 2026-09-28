# APK 构建约定

本文是 MCA 构建 APK 的长期约定。标准验证 APK 必须同时包含
`arm64-v8a` 和 `x86_64`，并包含 QAIRT/QNN、MNN、Stable Diffusion 和
llama.cpp 运行库。

## 标准 Windows 构建

先配置 JDK 21、Android SDK 和 QAIRT SDK。LiteRT-LM 的上游 AAR 包含 Java 21
字节码，宿主单测必须由 JDK 21 或更新版本运行。QNN SDK 路径可以通过下面任意一个
环境变量提供；标准脚本会把它们统一到同一个路径：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:MCA_QNN_SDK_ROOT = 'C:\path\to\qairt-sdk'
$env:QNN_SDK_ROOT = $env:MCA_QNN_SDK_ROOT
$env:QAIRT_SDK_ROOT = $env:MCA_QNN_SDK_ROOT
```

使用项目脚本构建完整 debug APK：

```powershell
.\scripts\build.ps1
```

完整 APK 必须通过 `scripts/build.ps1` 构建。不要复制旧 APK、手工补库后安装，也不要把“Gradle 成功”当作完整性通过。脚本负责传入双 ABI、QNN typed bindings 和独立的 QAIRT runtime root，并在构建后检查 APK 内容。

脚本固定使用以下 Gradle 参数：

```text
:app:assembleDebug
-Pmca.abis=arm64-v8a,x86_64
-PmcaQnnSdkRoot=<QAIRT SDK path>
-PmcaQnnRuntimeRoot=<QAIRT runtime library path>
-PmcaQnnTypedBindingsRequired=true
--rerun-tasks
```

也可以手动执行等价命令：

```powershell
.\gradlew.bat :app:assembleDebug `
  '-Pmca.abis=arm64-v8a,x86_64' `
  "-PmcaQnnSdkRoot=$env:MCA_QNN_SDK_ROOT" `
  "-PmcaQnnRuntimeRoot=$env:MCA_QNN_RUNTIME_ROOT" `
  '-PmcaQnnTypedBindingsRequired=true' `
  --rerun-tasks --no-daemon --console=plain
```

手动命令仅供排查；使用前必须把 `MCA_QNN_RUNTIME_ROOT` 指向包含完整 HTP runtime 的目录，并执行同等的 APK 文件清单校验。正常构建优先使用标准脚本，因为它会从相邻 QAIRT ZIP 准备 runtime。SDK 头文件目录与 runtime 库目录可能不同，不能只传 `mcaQnnSdkRoot`。

不要给 debug 或全量验证 APK 使用 `-Pmca.abis=arm64-v8a`。那会删除
`x86_64` 原生库，使 APK 体积突然减少约 100 MB，并且不能代表完整构建。

## 构建后必须确认

APK 输出在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

标准脚本会在 Gradle 成功后再次检查 APK。至少必须同时存在：

- `lib/arm64-v8a/` 和 `lib/x86_64/`；
- ARM64 的 `libmca_qnn_native.so`、`libmca_mnn_native.so`、
  `libmca_sd_native.so`、`libMNN.so`、`libQnnHtpV79.so`；
- 两个 ABI 的 `libmca_native.so`、`libmca_mnn_native.so`、
  `libmca_sd_native.so`；
- `assets/litert-qualcomm/` 下的 Qualcomm LiteRT 运行资源。
- llama.cpp HTP backend 所支持的 QNN HTP v68/v69/v73/v75/v79/v81 runtime 闭包；v68/v69/v73/v75 必须包含对应主库、CalculatorStub、Skel 和 Stub（v73 还需 QemuDriver）。
- 两个 ABI 的 MCA、Stable Diffusion、llama.cpp 核心库；arm64 还需完整 MNN CPU/OpenCL runtime。

构建完成后应记录 APK 的字节数、修改时间和 SHA-256。体积不是固定接口，
不能只靠体积判定完整性。双 ABI 丢失通常会少约 100 MB；2026-09-26 漏掉
17 个 QAIRT HTP runtime 库时则少了 35,424,653 字节。无论体积变化多少，
都必须逐文件核对 APK 清单；任一必需库缺失，构建无效，不能安装或发布。

### 每次构建的长期记录

`scripts/build.ps1` 会为每次运行写入 `artifacts/apk-build-records/` 下的 JSON 记录，包含开始/结束时间、状态、SDK 与 runtime 来源、构建参数、APK 路径和 SHA-256、源码工作树清单与哈希、各 ABI 原生库的 SHA-256/ELF 机器类型/SONAME/依赖、必需文件存在情况以及缺失项。构建前后重新冻结源码与 submodule 身份，变化时构建无效。构建验证失败时也会留下记录；`missingEntries` 或 `validationErrors` 非空时，该 APK 无效，不安装、不发布。库数量低于历史基线只写入 `validationDiagnostics`，不能单独判定 APK 无效。构建后应把遗漏原因和修正方式同步到本节历史，确保后续构建不重犯。

记录应覆盖：

- 工作区版本/状态（仓库元数据异常时注明，不能用 reset/clean“修复”）；
- JDK、Gradle、Android NDK、QAIRT 版本，SDK 头文件目录及 runtime ZIP 路径与 SHA-256；
- 完整构建命令/ABI/variant、APK versionName/versionCode、字节数、修改时间和 SHA-256；
- arm64-v8a 与 x86_64 库清单/数量，QNN HTP 各架构库闭包、typed bindings 证据和 LiteRT Qualcomm assets；
- 安装或实机测试结果，以及与上一次构建相比新增、缺失或改变的库。

### 构建遗漏历史

| 日期 | 结果 | 遗漏与原因 | 纠正与预防 |
| --- | --- | --- | --- |
| 2026-09-26 | 首次手工构建无效，未安装 | 只配置 QAIRT include/SDK 根，没有传 `mcaQnnRuntimeRoot`，APK 为 364,336,305 字节，arm64 仅 77 个库；缺少 HTP v68/v69/v73/v75 共 17 个 runtime 库。 | 后续完整 APK 恢复为 94 个 arm64、26 个 x86_64 库，并完成 QNN 实机生图验证。今后只用标准脚本；逐文件核验 HTP runtime，漏项写入该次 JSON 记录并终止构建。 |

Gradle 会自动执行 MNN vendor 和 runtime stamp 校验；这两个任务失败时不
得通过复制旧 APK 来代替当前源码构建。

`app` 的 `preDebugBuild` 任务也会拒绝缺少任一 ABI 的 debug 构建，因此直接
给 `assembleDebug` 传入 `-Pmca.abis=arm64-v8a` 会明确失败，而不会生成一
个看起来成功但少了约 100 MB 原生库的 APK。

## Release 与 debug 的区别

公开 GitHub Release 可以按 `docs/RELEASE.md` 发布签名的 arm64 APK，这是
为了减小面向实际 ARM64 手机的下载体积。它与本地完整 debug/验证 APK 是
不同构建配置，必须在文档和文件名中明确 ABI，不能把 arm64 release 当作
双 ABI debug APK。
