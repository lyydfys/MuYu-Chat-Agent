# APK 构建约定

本文是 MCA 构建 APK 的长期约定。标准验证 APK 必须同时包含
`arm64-v8a` 和 `x86_64`，并包含 QAIRT/QNN、MNN、Stable Diffusion 和
llama.cpp 运行库。

## 标准 Windows 构建

先配置 JDK 17、Android SDK 和 QAIRT SDK。QNN SDK 路径可以通过下面任意一个
环境变量提供；标准脚本会把它们统一到同一个路径：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:MCA_QNN_SDK_ROOT = 'C:\path\to\qairt-sdk'
$env:QNN_SDK_ROOT = $env:MCA_QNN_SDK_ROOT
$env:QAIRT_SDK_ROOT = $env:MCA_QNN_SDK_ROOT
```

使用项目脚本构建完整 debug APK：

```powershell
.\scripts\build.ps1
```

脚本固定使用以下 Gradle 参数：

```text
:app:assembleDebug
-Pmca.abis=arm64-v8a,x86_64
-PmcaQnnSdkRoot=<QAIRT SDK path>
-PmcaQnnTypedBindingsRequired=true
```

也可以手动执行等价命令：

```powershell
.\gradlew.bat :app:assembleDebug `
  '-Pmca.abis=arm64-v8a,x86_64' `
  "-PmcaQnnSdkRoot=$env:MCA_QNN_SDK_ROOT" `
  '-PmcaQnnTypedBindingsRequired=true' `
  --no-daemon --console=plain
```

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

构建完成后应记录 APK 的字节数、修改时间和 SHA-256。体积不是固定接口，
但同一版本如果从约 350 MB 级别突然降到约 250 MB，首先检查是否误用了
arm64-only 参数；如果 QNN 库缺失，构建应视为无效，不能继续安装或发布。

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
