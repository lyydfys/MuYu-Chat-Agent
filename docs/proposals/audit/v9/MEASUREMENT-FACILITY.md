# V9 测量设施：Compose 报告、Macrobenchmark 与验证 runner（MEASUREMENT-FACILITY）

- 文档日期：2026-10-18
- 基线：`E:\MAC`，HEAD `bbad9747a4d32c1d2bcc19be8f9a0079aef3175c`，分支 `codex/enterprise-refactor`
- 对应方案：`docs/proposals/MCA-GEMINI-GRADE-PATCH-V9.md` G00 / Q01 / 23.2 / 24 / 25.3
- 本次交付：只新增/修改构建脚本、benchmark 模块、脚本与文档。**没有修改任何 Kotlin 生产源码，没有运行 Gradle、没有真机测量、没有生成性能 PASS。**
- 状态词：PASS 只用于已运行且有原始证据的检查；本文件大部分条目是 NOT_RUN / 依赖未验证。

## 0. 一句话状态

```text
默认构建：不包含 :benchmark
Compose 报告：已在 app + feature/chat|settings|modelhub|agent + core/designsystem 接线
Macrobenchmark：模块与三场景已写好；依赖与目标 manifest 前提未验证；所有场景 NOT_RUN
verify-ui-quality.ps1：已写好；仅做无 Gradle 的 BLOCKED 自检，compile/unit/macro 未真实执行
```

## 1. 核对了什么（SOURCE）

| 项目 | 事实 |
|---|---|
| Kotlin | `2.3.21`（`gradle/libs.versions.toml`） |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose`，本次用本地缓存中的 `compose-compiler-gradle-plugin-2.3.21-gradle813.jar` 的 `javap` 确认：`reportsDestination` / `metricsDestination` 是 `DirectoryProperty` |
| AGP | `8.13.2` |
| Gradle | wrapper `8.14.3` |
| 本地 Gradle 缓存 | `androidx.benchmark` **不存在**；`androidx.test.uiautomator` 只有 `2.3.0`；`androidx.profileinstaller` 有 `1.3.0/1.3.1/1.4.0`；`androidx.test:runner` `1.7.0`、`androidx.test.ext:junit` `1.3.0` 存在 |
| Google Maven 元数据 | 可用（本次 HTTP 读取 `benchmark-macro-junit4/1.5.0` POM）：`benchmark-macro 1.5.0` 传递依赖 `androidx.test.uiautomator:2.4.0`、`androidx.test:runner:1.7.0`、`androidx.profileinstaller:1.4.1` |

## 2. Compose compiler reports / metrics

### 2.1 已接线的模块

- `:app`
- `:feature:chat`
- `:feature:settings`
- `:feature:modelhub`
- `:feature:agent`
- `:core:designsystem`

每个模块的 `build.gradle.kts` 都有：

```kotlin
val composeCompilerReportsEnabled = providers.gradleProperty("mca.composeCompilerReports")
    .map { value -> !value.trim().equals("false", ignoreCase = true) }
    .orElse(true)
composeCompiler {
    if (composeCompilerReportsEnabled.get()) {
        reportsDestination = layout.buildDirectory.dir("compose-compiler/reports")
        metricsDestination = layout.buildDirectory.dir("compose-compiler/metrics")
    }
}
```

产物（模块内）：

```text
<module>/build/compose-compiler/reports/*.txt|*.json
<module>/build/compose-compiler/metrics/*.csv
```

关闭方式（例如只出包、不要诊断文件）：

```powershell
.\gradlew.bat -Pmca.composeCompilerReports=false :app:assembleDebug
```

注意：

- 报告只在 Kotlin 编译任务真实执行时刷新；`UP-TO-DATE` 的编译不会产生新报告。需要新报告时对单个编译任务使用 `--rerun`（不要用 `--rerun-tasks`，会连带重跑 native/CMake 相关任务）：
  `.\gradlew.bat :app:compileBenchmarkKotlin --rerun`
- 这些报告用于寻找 stability/skippable 热点，**不能**证明 UI 已变快；实际帧数据必须来自第 4 节的 Macrobenchmark。
- `gradle/libs.versions.toml` **不需要**为 Compose 报告新增 alias：compiler plugin alias 已存在（`kotlin-compose`），报告 DSL 由该插件提供。

## 3. Macrobenchmark 模块（`:benchmark`）

### 3.1 默认构建不含 `:benchmark`

`settings.gradle.kts` 按需 include：

```kotlin
val mcaIncludeBenchmark =
    gradle.startParameter.projectProperties["mca.includeBenchmark"]
        ?.trim()?.equals("true", ignoreCase = true) == true ||
        System.getenv("MCA_INCLUDE_BENCHMARK")
            ?.trim()?.equals("true", ignoreCase = true) == true
if (mcaIncludeBenchmark) {
    include(":benchmark")
}
```

原因：`androidx.benchmark` 构件不在本机默认 Gradle 缓存中。若默认 include，`verify-code.ps1` 或离线构建会在解析依赖阶段失败，而不是在真正的检查处失败。

启用（任选其一）：

```powershell
$env:MCA_INCLUDE_BENCHMARK = 'true'
# 或每次命令行显式传：
.\gradlew.bat -Pmca.includeBenchmark=true :benchmark:tasks
```

开启后第一次构建需要网络访问 Google Maven，并会解析以下**未在本地缓存**的构件：

| 构件 | 版本 | 本地缓存 | 状态 |
|---|---|---|---|
| `androidx.benchmark:benchmark-macro-junit4` | `1.5.0` | 无 | **依赖未验证**（仅核对过 Maven 元数据/POM，未构建） |
| `androidx.benchmark:benchmark-macro`（传递） | `1.5.0` | 无 | **依赖未验证** |
| `androidx.test.uiautomator:uiautomator`（传递） | `2.4.0` | 仅 `2.3.0` | **依赖未验证**；不能把 catalog 的 `2.3.0` 强塞给 `:benchmark`，`MacrobenchmarkScope` 需要 2.4.0 的 `UiAutomatorTestScope` |
| `androidx.test:runner` | `1.7.0` | 有 | 本地可用 |
| `androidx.test.ext:junit` | `1.3.0` | 有 | 本地可用 |
| `androidx.profileinstaller:profileinstaller` | `1.4.0`（app） | 有 | 本地可用；`benchmark-macro` 还会传递 `1.4.1`，启用 `:benchmark` 时需联网 |

### 3.2 目标 app：release-like `benchmark` 变体

`app/build.gradle.kts` 新增：

```kotlin
create("benchmark") {
    initWith(getByName("release"))          // R8 + shrinkResources + 实际代码路径
    signingConfig = signingConfigs.getByName("debug")
    matchingFallbacks += listOf("release")
    applicationIdSuffix = ".benchmark"      // 与真实安装的数据完全隔离
    isDebuggable = false                    // Macrobenchmark 拒绝测量 debuggable target
}
```

同时 `implementation(libs.androidx.profileinstaller)`（1.4.0）加入 app：Macrobenchmark 需要目标 app 内含 ProfileInstaller 1.3+ 来安装/重置 profile 和清 shader cache。

静态核对（未构建）：

- `profileinstaller:1.4.0` 的传递依赖 `androidx.startup:startup-runtime:1.1.1`、`androidx.concurrent:concurrent-futures:1.1.0`、`androidx.annotation:1.8.1`、`com.google.guava:listenablefuture:1.0` 均已在本地 Gradle 缓存中，没有“版本不存在”的解析风险；
- 现有版本若更高（如 annotation 1.9.1、concurrent-futures 1.2.0），Gradle 会取高版本且这些版本也在缓存中；因此**静态判断**不会因版本冲突失败；
- 但这仍是一处生产运行时变更：profileinstaller 会合并 `androidx.startup` 的 `ProfileInstallerInitializer`，并注册一个受 `android.permission.DUMP` 保护的 `ProfileInstallReceiver`。它不改变 UI，但可能在冷启动早期排队一次 profile 安装/校验。**必须**在 Q01 冷启动 before/after 中确认没有可测量的启动回退；本次没有测量 → NOT_RUN。
- 若集成者希望生产 APK 完全不增加该依赖，可将其改为仅 `benchmarkImplementation(libs.androidx.profileinstaller)`（本切片按 Lead 指示保留 `implementation`，未做此收窄）。

### 3.3 三个场景

代码在 `benchmark/src/main/java/com/muyuchat/mca/benchmark/`；模块是 `com.android.test`，`targetProjectPath = ":app"`，`experimentalProperties["android.experimental.self-instrumenting"] = true`。

| 场景 | 测试类/方法 | 指标 | StartupMode | CompilationMode 默认 | 迭代默认 |
|---|---|---|---|---|---|
| 冷启动 S00 | `ColdStartupBenchmark#coldStartup` | `StartupTimingMetric` | `COLD` | `CompilationMode.DEFAULT` | 10 |
| 流式 500 token S01/S02 | `StreamingTokenBenchmark#streamingFiveHundredTokenResponse` | `FrameTimingMetric` | `null`（不重启进程，避免模型重载进入测量窗） | `CompilationMode.DEFAULT` | 10 |
| 图片列表滚动 S08 | `ImageGalleryScrollBenchmark#imageGalleryFling` | `FrameTimingMetric` | `WARM`（每次重启 Activity，统一导航起点） | `CompilationMode.DEFAULT` | 10 |

可覆盖的 instrumentation 参数：

```text
mca.iterations              正整数，默认 10
mca.compilationMode         default | partial | full | none | ignore
mca.generationTimeoutMs     流式等待终态的超时，默认 180000
mca.streamingPrompt         流式场景的提示词（默认约 500 token 的中文散文请求）
```

示例：

```powershell
.\gradlew.bat -Pmca.includeBenchmark=true `
  :benchmark:connectedBenchmarkAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.ColdStartupBenchmark `
  -Pandroid.testInstrumentationRunnerArguments.mca.iterations=10 `
  -Pandroid.testInstrumentationRunnerArguments.mca.compilationMode=default
```

该 Gradle task 名来自 AGP 的 `benchmark` 变体命名规则（`connected<BuildType>AndroidTest`）；本次**没有运行 Gradle 验证 task 列表**，`scripts/verify-ui-quality.ps1` 会在执行前用 `:benchmark:tasks --all` 发现真实 task，不把本文档的名字当既成事实。

### 3.4 必须满足的前提（当前仓库尚未全部满足）

| 前提 | 当前状态 | 说明 |
|---|---|---|
| `:app` 的 `benchmark` 变体包含 `<profileable android:shell="true" tools:targetApi="29" />` | **缺失，未创建**。该文件属于 `app/src/benchmark/AndroidManifest.xml`，超出本切片的写入范围（只允许 `app/build.gradle.kts`）。 | API 29+ 上 Macrobenchmark 会拒绝非 debuggable 且非 profileable 的目标。**需要集成交付者新增该变体 manifest**，内容见 `benchmark/README.md`。 |
| 真机（推荐 API 29+，非模拟器） | NOT_RUN | 模拟器结果不能代表用户设备 |
| 目标 app 的 benchmark 变体已安装（`com.muyuchat.mca.benchmark`） | NOT_RUN | 不带旧用户数据；数据与正式安装隔离 |
| 流式 fixture：已加载本地聊天模型或已配置云端聊天模型，输出约 500 token | NOT_RUN | 未配置时测试以 JUnit assumption 跳过，runner 必须记录 NOT_RUN，不能算 PASS |
| 图片 fixture：图库 ≥ 20 张图片 | NOT_RUN | 空图库时画廊不可滚动，场景无效 |
| 设备温度/电量/后台负载记录 | NOT_RUN | V9 7.3 要求 |

### 3.5 输出归档位置

- 连接测试 XML：AGP 8.x 通常写在 `benchmark/build/outputs/androidTest-results/connected/...`；旧/变体路径可能是 `benchmark/build/test-results/...`。runner 会同时递归扫描 `test-results`、`outputs/androidTest-results`、`reports/androidTests` 三处，不依赖单一布局。
- 额外输出（Macrobenchmark JSON / Perfetto trace）：`benchmark/build/outputs/connected_android_test_additional_output/benchmarkAndroidTest/connected/<device>/`（以 Gradle 实际输出为准）。
- `scripts/verify-ui-quality.ps1` 会把上述文件复制到 `artifacts/ui-quality/<runId>/`，并记录 SHA256 与 mtime；不复制/不打印用户会话内容。

## 4. 帧指标的解释边界

- `FrameTimingMetric` 的 `frameDurationCpuMs` 与 `frameOverrunMs` 必须分开报告；只有 `frameOverrunMs <= 0` 表示该帧满足时限。API < 31 没有 overrun 数据时报告“不支持”，不能编造。
- 60Hz/90Hz/120Hz 的帧预算不同，先记录实际刷新率再比较（V9 7.4）。
- 每次 before/after 必须记录：HEAD、工作区指纹、APK SHA-256、变体、依赖版本、设备/API、分辨率/density/fontScale、主题、刷新率、温度/电量、fixture hash、CompilationMode、冷热状态（V9 7.3）。
- 任何一次运行 `validFrames=0`、fixture 未消费完、手势未执行或采样窗口遗漏目标交互，都判为无效运行，不能进入统计。

## 5. `scripts/verify-ui-quality.ps1`

新增脚本，串行调用现有 `verify-code.ps1` 并归档证据。设计要点：

- **不伪造**：每个 lane 记录退出码、原始日志路径、报告数量与时间；没有测试执行/没有报告就写 `NOT_RUN`/`FAIL`，不会写 PASS。
- 复用现有共享构建锁 `McaBuildLock.psm1`（直接跑 Gradle 的 lane）与 `McaWorktreeFingerprint.ps1`（运行前后指纹一致才认可结果）。
- `verify-code.ps1` 作为**子进程**串行调用，再由脚本在需要额外 Gradle lane 时自行取锁；避免同进程嵌套持锁失败（V9 25.3 第 6 条）。
- 归档目录：`artifacts/ui-quality/<timestamp>-<pid>/`（`artifacts` 在指纹排除目录内，不会把 runner 自己生成的证据误判为输入变化）。
- 设备缺失时 Macrobenchmark lane 返回 `BLOCKED`（exit 3），不自动换模拟器冒充性能证据。
- 默认 lane 只有 `compile` + `unit`；Macrobenchmark 必须显式 `-Lane macro -DeviceSerial <serial>`。

已做的**无 Gradle 自检**（2026-10-18）：用 `-Lane macro` 且不传 `-DeviceSerial` 运行脚本，得到 `BLOCKED`（exit 3），生成 `RUN.md`/`RUN.json`，未调用 Gradle。第一次自检时其它 agent 正在写生产文件，脚本按契约判为 `input-changed-during-run`（FAIL）；第二次自检前后指纹一致，证明该判定来自真实的并发写入而不是 runner 自身生成的日志/证据。`compile`/`unit`/`macro` 的真实执行仍为 NOT_RUN。

## 6. NOT_RUN / 依赖未验证 清单（本次交付）

| 项目 | 状态 | 原因 |
|---|---|---|
| `settings.gradle.kts` 的 opt-in include 行为 | NOT_RUN（配置未用 Gradle 验证） | 禁止在本切片运行 Gradle；逻辑为 `-Pmca.includeBenchmark=true` / `MCA_INCLUDE_BENCHMARK=true`，缺省不 include |
| 六个模块的 `composeCompiler {}` DSL 与报告路径 | NOT_RUN（未编译验证） | 已用 Kotlin 2.3.21 插件 jar 的 `javap` 确认 `reportsDestination`/`metricsDestination` 是 `DirectoryProperty`，但未跑 Gradle |
| `:app:compileBenchmarkKotlin` / `:benchmark` 编译 | NOT_RUN | 禁止在本切片运行 Gradle；且 benchmark 依赖未在本地缓存 |
| Compose reports 生成与内容审阅 | NOT_RUN | 同上（需要编译）；自检只看到部分模块存在旧的 4 个报告文件（fresh=0），不能当作本次产物 |
| 三个 Macrobenchmark 场景 | NOT_RUN | 需要启用模块、网络依赖、profileable manifest、真机与 fixture |
| `scripts/verify-ui-quality.ps1` 的 compile/unit/macro lane 真实执行 | NOT_RUN | 禁止在本切片运行 Gradle；只做了无 Gradle 的 BLOCKED 自检（见第 5 节） |
| `androidx.benchmark`/uiautomator 2.4.0 解析 | 依赖未验证 | 只核对了 Google Maven 元数据与 POM，未构建 |
| 目标 app 的 profileable manifest | 缺失 | 超出本切片写入范围，见 3.4 |
| `profileinstaller` 对生产冷启动的影响 | NOT_RUN | 未测量；必须在 Q01 冷启动 before/after 中确认 |
| 性能收益/帧改善 | NOT_RUN | 没有任何原始测量 |

## 7. UI 动效证据采集（`scripts/verify-ui-motion.ps1`）

### 7.1 目的与边界

把“动效是否真的在动”变成可复现的像素证据：

```text
adb screenrecord → pull mp4 → ffmpeg -vf fps=10 → PIL 相邻帧平均绝对差 → motion.json
```

- PASS 只表示：录制窗口内至少 `-MinMotionFrames` 帧的相邻帧平均差达到 `-MotionThreshold`，并且要求的动作确实打开了对应界面。
- 它**不**证明动画曲线、时长、物理参数、视觉质量、全设备流畅度或 TalkBack/大字体表现；也不替代 Macrobenchmark 帧数据。
- 录制窗口内任何系统 UI 变化（状态栏、输入法、通知）也可能计入差异；JSON 保存完整 timeline，复核者可以重新判定。
- 本工具不修改生产代码；adb/ffmpeg/PIL 是外部工具。脚本会恢复并校验设备动画缩放。

### 7.2 参数与用法

```powershell
# 默认：在 chat 界面录 drawer 开/关，动画缩放为 0 时直接 NOT_RUN
.\scripts\verify-ui-motion.ps1 -DeviceSerial device-a -Stage chat-drawer -Actions drawer

# 临时把动画缩放置 1（必须在 finally 恢复原值；脚本会二次校验）
.\scripts\verify-ui-motion.ps1 -DeviceSerial device-a -Stage chat-menu -Actions menu -EnableAnimationScale

# 多个动作：逗号分隔（powershell -File 也支持）或 PowerShell 数组
.\scripts\verify-ui-motion.ps1 -DeviceSerial device-a -Stage menu-model -Actions menu,model -EnableAnimationScale

# 显式证据目录（默认在 artifacts/ui-motion/，该目录已在本地 git exclude 中）
.\scripts\verify-ui-motion.ps1 -DeviceSerial device-a -OutDir artifacts/ui-motion/manual-pass -Actions drawer -EnableAnimationScale
```

`-Actions` 支持 `drawer`、`menu`、`model`、`newchat`、`send`；未知动作退出码 2。`send` 总会被移到最后一个：录制前先确认模型就绪，再点输入框、输入探测文本、确认发送按钮存在；如果模型未就绪（头部出现 `待加载`/`选择模型`/`加载中`/`需要重载`/`加载失败` 等），直接 **BLOCKED**，不录制、不污染输入框。`-Actions @()` 会录一段静止画面，这是有意的“无动效应判 FAIL”诊断，不是生产场景。

### 7.3 判定与退出码

| 场景 | 结果 | 退出码 | 说明 |
|---|---|---|---|
| `animator_duration_scale = 0` 且未加 `-EnableAnimationScale` | `NOT_RUN` | 3 | Compose 动画按设计停住；不录制、不伪造 PASS |
| 动画缩放 0，加 `-EnableAnimationScale` | 继续测量 | — | 三个 scale 临时置 1，finally 恢复原值并二次读回校验 |
| 至少 `-MinMotionFrames` 帧均值差 ≥ 阈值，动作界面被观测到 | `PASS` | 0 | 只证明“录制窗口内确实有运动” |
| 无持续运动 / 动作界面未出现 / send 未产生生成 / 分析失败 / 缩放恢复失败 | `FAIL` | 1 | 不把失败写成 PASS |
| 缺 adb/ffmpeg/Python+PIL、无设备、app 不在 chat、send 缺模型 fixture | `BLOCKED` | 3 | 环境或前置条件不满足 |
| 未知 `-Actions`、输出目录不可创建 | 用法错误 | 2 | 参数在录制前拒绝 |
| `-EnableAnimationScale` 且中途异常/用户中断 | 仍执行 finally 恢复 | — | 恢复结果写进 JSON；恢复失败时整体判 FAIL |

恢复逻辑：保存 `window_animation_scale` / `transition_animation_scale` / `animator_duration_scale` 的原始字符串（含 `null`）；结束后逐项写回，先即时读回、再等 300ms 二次读回；任何不一致都会把本次 verdict 覆盖为 FAIL 并记录错误。自测期间发现并修复了一个 PowerShell 大小写不敏感变量冲突（局部 `$original` 覆盖了参数 `$Original`，只恢复第一个 scale）；修复后必须保留注释与二次校验，禁止改回同名变量。

### 7.4 输出

`<OutDir>/` 下：

| 文件 | 内容 |
|---|---|
| `motion.json` | schema/verdict/exitCode/stage/HEAD/device/app/tools、动画缩放 before/effective/restore、动作观测、recording 路径+SHA256+bytes、帧数/尺寸、motionFrameCount/maxFrameDiff/p95/motionRanges/timeline |
| `RUN.md` | 人类可读摘要、动作观测表、限制说明 |
| `recording.mp4` | 原始 screenrecord 文件 |
| `frames/` | ffmpeg 抽出的 PNG 帧（默认 10fps、原始分辨率） |
| `screenrecord.log` / `ffmpeg.log` / `analysis.log` / `frame-analysis.json` | 原始工具日志与分析中间产物 |
| `analyze-motion.py` | 本次运行实际使用的 PIL 分析器副本（可复查算法） |

`motion.json` 中 `motion.timeline` 每项包含 `frame/timeMs/meanDiff/changedRatio/motion`；`motionRanges` 是连续运动帧的合并区间。`frames.count`、`motion.motionFrameCount`、`recording.sha256` 是复核的最小证据集。

### 7.5 自测结果（2026-10-19，真机 device-a / API 36 / 1440x3200）

| 用例 | 命令要点 | 结果 |
|---|---|---|
| 动画缩放 0 | 默认 `-Actions drawer` | `NOT_RUN`，exit 3，无录制，缩放保持 0.0 |
| 抽屉动效 | `-Actions drawer -EnableAnimationScale` | `PASS`，6 个运动帧，maxFrameDiff 25.61，录制/帧/JSON 齐全，缩放恢复 0.0/0.0/0.0 |
| 菜单+模型动效 | `-Actions menu,model -EnableAnimationScale` | `PASS`，7 个运动帧，maxFrameDiff 12.63，两个动作 observed=true，缩放恢复 0.0/0.0/0.0 |
| 静止画面 | 空 `-Actions` + `-EnableAnimationScale` | `FAIL`，0 个运动帧，maxFrameDiff 0.14，缩放恢复 0.0/0.0/0.0 |
| send 缺 fixture | `-Actions send -EnableAnimationScale`（模型 `待加载`） | `BLOCKED`，exit 3，未录制、输入框无探测文本、缩放未变 |
| 未知动作 | `-Actions bogus` | 用法错误，exit 2 |

校准数据（同设备，10fps，1440x3200）：静止 chat 画面相邻帧均值差 p95≈0.03、max≈0.45；抽屉/菜单动画峰值实测约 12–90（与录制窗口和手势抖动有关）。默认阈值 `1.0`、最少 `2` 帧；更微妙的淡入淡出可降低阈值并同时看 `changedRatio`，必须在 JSON 中记录本次阈值。

自测时 HEAD 已从任务描述的 `e964539a` 前进到 `9054004c`（motion 提交）；脚本每次运行都记录实际 `git rev-parse HEAD` 与 dirty/untracked 数量，不把旧基线写进证据。自测使用的是 0.2.4 生产包（`com.muyuchat.mca`），未改动 app 的动画缩放设计，也没有修改任何 Kotlin 源码。

### 7.6 未覆盖（NOT_RUN）

- 未测 60Hz/90Hz/120Hz 下的动画帧预算；本工具只看帧间像素差，不产出 FrameTiming 指标。
- 未测字体缩放、TalkBack、横屏、分屏和系统动画缩放非 1（如 0.5/1.5）的组合。
- 未做多设备对比；`PASS` 只属于本设备、本次构建、本次录制窗口。
- 未验证 `send` 的真实流式动效（设备当前没有已加载聊天模型，按设计为 BLOCKED）。
- 未证明任何动画“更高级/更顺滑”；视觉质量仍需真机录像 + 人工复核。

## 8. 回退点

- 删除 `:benchmark` 的 opt-in include 即回到原默认构建；该模块不被任何生产模块依赖。
- 删除 app 的 `benchmark` build type 与 `profileinstaller` 依赖、删除六个模块的 `composeCompiler {}` 块、删除 `scripts/verify-ui-quality.ps1` 与本文档，即完全回到基线构建行为。
- 删除 `scripts/verify-ui-motion.ps1` 与本节即移除动效测量入口；它不接入生产构建、不修改业务源码，`artifacts/ui-motion/` 下的自测证据可单独删除。
- 以上操作都不触碰业务源码、数据库、协议或冻结视觉。
