# llama.cpp 升级与本轮运行时改进

本轮把 Android 集成使用的 `third_party/llama.cpp` 更新到
`4ceb1719101f32637b841206c172f3f058ffc182`，并保留 MCA 自有的 mmap 预取和
GenieX 兼容层。构建前由 `core/native/build.gradle.kts` 校验并应用
`vendor/llama/llama-stq1_0.patch`，因此不会把补丁状态依赖在子模块工作树上。

## 已完成

- arm64-v8a 与 x86_64 的 native CMake 构建均通过。
- 最新 mtmd API 已适配，图片加载使用新版初始化参数。
- 最新 mtmd 的 `deepseek4v.cpp` 已加入 MCA 静态库源列表，避免链接时缺失 vtable。
- MoE CPU tensor 覆盖改用当前上游公开的 `llm_ffn_block_regex` 接口。
- 增加 STQ1_0 native smoke：检查类型名、量化块布局、编码非空、反量化有限值和 RMSE 上限。
- STQ1_0 的来源、基线 commit、补丁 commit 和 SHA-256 固定在
  `vendor/llama/llama-vendor.properties`。
- UI 已支持第一次返回优先收起输入法；性能页按秒显示 CPU、进程 CPU、内存、GPU 和 NPU 可用状态。
- 下载任务完成后默认折叠，失败或校验失败仍保留展开入口。
- 多图任务保存完整图片 ID 列表，聊天结果卡片即时显示缩略图和总张数。
- GGUF + mmproj 绑定会重新加载当前模型，并以 `visionReady` 作为聊天页、模型页和 Local API 的共同就绪信号。

## STQ1_0 发布边界

当前上游 master 不包含 STQ1_0，因此 MCA 使用可审计的 PR #22836 补丁。补丁已进入
Android 编译链，但在完成真实 arm64 真机加载 Hy-MT2 1.25-bit、短句翻译、停止/取消、
卸载/重载和连续稳定性测试前，推荐页不得把该模型标记为“可用”。x86_64 只允许做加载拒绝或
标量 fallback 验证，不能宣称 GPU、NPU 或 QNN 支持。

## 尚未接入

离线中文转英文按钮所需的独立翻译 runtime 尚未随项目提供。`OfflinePromptTranslation.kt`
目前只负责固定包契约和安全失败，缺少经过许可、哈希固定的模型文件以及
`libmca_translation_native.so`。在这两个工件进入仓库并完成 smoke 前，不能把普通聊天模型、
云端 API 或启发式替代物接入生图流程。

## 构建约定

完整 Debug 验证包使用：

```powershell
$env:JAVA_HOME='C:\Users\sy427\.jdks\ms-17.0.15'
$env:GRADLE_USER_HOME='E:\model\MAC\.gradle-user-final-20260831'
.\gradlew.bat -Pmca.abis=arm64-v8a,x86_64 :app:assembleDebug --no-daemon --console=plain
```

构建任务会自动校验 MNN vendor、重新应用 STQ 补丁，并在 APK 身份记录中保存 ABI、库清单和哈希。

## 本轮构建证据

- `:core:native:externalNativeBuildDebug`：arm64-v8a 与 x86_64 通过。
- `:app:testDebugUnitTest`、`:feature:chat:testDebugUnitTest`、
  `:feature:modelhub:testDebugUnitTest`、`:core:download:testDebugUnitTest`、
  `:core:deviceprofile:testDebugUnitTest`：通过。
- `:app:assembleDebug`：通过。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- APK 大小：`356,095,612` bytes；SHA-256：
  `075478bc03fa0525679c45b251a7fedccc62a2f9891bbc9bc1c0d81408b6262e`。
- 包内 native 库：arm64-v8a `76` 个、x86_64 `26` 个；`aapt2` 报告两个 ABI 均在包内。
