# 视觉金样（screenshot golden）测试

本目录用 **Roborazzi + Robolectric** 在纯 JVM 上渲染 Compose 界面并生成 PNG 基线。
不模拟器、不连真机、不需要 `connectedAndroidTest`。

金样覆盖两个「无状态、无 Activity、无 store 依赖」的设计系统表面：

| 文件 | 覆盖内容 |
| --- | --- |
| `OnboardingGoldenTest.kt` | 引导四页（欢迎 / 运行方式 / 模型 / 完成）× 亮暗，共 8 张 |
| `AppearanceGoldenTest.kt` | 外观分区，选中「跟随系统」与选中「深色」× 亮暗，共 4 张 |

合计 **12 张**基线，文件位于 `core/designsystem/src/test/screenshots/`。

---

## 1. 怎么跑

金样**不能**用 `testDebugUnitTest` 验证。Roborazzi 只有在下面这些任务里才会真正抓图；
普通单测任务里 `captureRoboImage` 是**空操作**，所以 `testDebugUnitTest` 变绿**不代表**金样一致。

```powershell
# 首次必须有基线：录制
.\gradlew.bat :core:designsystem:recordRoborazziDebug

# 之后校验像素：不一致会失败并给出差异图
.\gradlew.bat :core:designsystem:verifyRoborazziDebug

# 生成人工可看的差异图（不改基线）
.\gradlew.bat :core:designsystem:compareRoborazziDebug

# 清空全部基线（危险：需要重新录制并重新评审）
.\gradlew.bat :core:designsystem:clearRoborazziDebug

# 只跑一个测试类
.\gradlew.bat :core:designsystem:recordRoborazziDebug --tests '*OnboardingGoldenTest'
.\gradlew.bat :core:designsystem:verifyRoborazziDebug --tests '*AppearanceGoldenTest'
```

只作用于 `:core:designsystem`。其它模块没有这些任务，构建图和耗时不变。

### 失败产物

校验失败时，`_actual`（实际截图）与 `_compare`（并排/差异图）会写到
`core/designsystem/build/outputs/roborazzi/`。**不要**把这些目录里的图当成基线提交。

---

## 2. 改了 UI 之后的正确流程

1. 先跑 `verifyRoborazziDebug`，确认它因为**你预期的那些**页面而失败。
2. 跑 `recordRoborazziDebug` 重新生成基线。
3. **逐张打开 `src/test/screenshots/` 下的 PNG 用眼睛看**，对照本次改动确认：
   布局没有意外位移、文字没有被截断或换行错位、选中态仍然有非颜色信号、亮暗两套都成立。
4. 只有在第 3 步确认之后，才把「UI 改动 + 新 PNG」放在**同一个提交**里。

第 3 步是纪律，不是建议：

- **禁止**为了让 `verify` 通过而手工修改 PNG，也禁止把 `_actual` 直接拷贝覆盖基线。
- **禁止**「先 record 一把让它绿」而不看图的盲刷；这正是金样最容易失效的方式。
- 任何基线变化都必须在 PR/交付说明里解释清楚：改了哪个像素区域、为什么。
- `verify` 失败时先判断是**回归**还是**预期变化**。判断不了就停下来问，不要刷基线。

---

## 3. 环境固定与「为什么」

`GoldenHarness.kt` 每次抓图前都会固定下面三项，它们是截图不稳的三个主因：

| 固定项 | 值 | 不固定会怎样 |
| --- | --- | --- |
| 字体缩放 `fontScale` | `1.0` | 每个文字行盒高度都变，正文重排，下方所有元素跟着位移；同一台机器上被人放大过字体就会假失败 |
| 屏幕密度 `dpi` | `420`（411dp × 891dp 竖屏） | 1dp 折合的像素数变，图**尺寸**都不同，等于换了基线 |
| 动画进度 | `ANIMATOR_DURATION_SCALE` / `TRANSITION_ANIMATION_SCALE` / `WINDOW_ANIMATION_SCALE` 全置 `0` | 抓到的是动画中途的某一帧：快机器 30%、慢机器 80%，两张都「正确」，diff 永远抖 |

补充说明：

- 动画**不是**通过 `LocalMotionDurationScale` 固定的——Compose 1.11 已移除该
  CompositionLocal，改由 `MotionDurationScaleImpl` 读取系统动画缩放。所以这里直接改
  `Settings.Global`，这是该版本唯一有效的开关。
- 抓图前 Roborazzi 会 `ShadowLooper.shadowMainLooper().idle()`，所以组合、测量、布局一定
  已经完成，不会产出空白图。
- 金样只钉 **Compose 表面**。状态栏/导航栏的颜色不在位图里，由
  `McaThemeSystemBarContractTest` 单独钉住。
- 金样通过 `MaterialTheme(colorScheme = mcaColorScheme(dark))` 渲染，与 `McaTheme` 注入的是
  同一个 `mcaColorScheme`，所以像素与生产一致。**如果将来 `McaTheme` 增加了 typography 或
  shape 覆盖，必须同步 `GoldenHarness`**，否则金样会漏掉那部分变化。

---

## 4. 当前局限（如实记录）

- 只覆盖一个设备档位（411dp × 891dp / 420dpi / fontScale 1.0）、亮暗两套配色。
  未覆盖：大字体、横屏、RTL、多语言、尺寸极端的窄屏。
- 只覆盖两个设计系统表面，不覆盖 `:app` / `:feature:*` 的整页组合。
- 未覆盖交互态（按下、涟漪、键盘、焦点）；这些需要测试里驱动状态后才能截图。
- 金样是**像素回归网**，不替代真机视觉验收，也不证明动效正确。

---

## 5. 依赖与首次运行

- 版本集中在 `gradle/libs.versions.toml`：`roborazzi = "1.77.0"`、`robolectric = "4.17"`、
  `testCore = "1.7.0"`；插件 `io.github.takahirom.roborazzi` 只被 `:core:designsystem` 应用。
- 本机 Gradle 缓存中原本**没有** `io.github.takahirom.roborazzi` 与 `org.robolectric`
  （2026-11-08 核查）。首次 `record`/`verify` 会下载它们，以及 Robolectric 运行所需的
  `android-all-instrumented:16-robolectric-*`（API 36 镜像，体积较大）。
- 可达性已用 HEAD 探测确认：Maven Central、Google Maven、Gradle Plugin Portal 和阿里云镜像
  均可解析这些构件。**离线环境会失败**，这不是本模块的问题。

---

## 6. 常见故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `verify` 报找不到基线 | 基线还没录制。先跑 `recordRoborazziDebug`，再看图，再提交。 |
| `testDebugUnitTest` 绿但金样没更新 | 正常：普通单测里 capture 是空操作（见第 1 节）。必须用 roborazzi 任务。 |
| `ClassNotFoundException: androidx.compose.ui.test.*` | Roborazzi 的 Compose 规则 API 需要 `androidx.compose.ui:ui-test-junit4`。本目录用「无规则」捕获方式不需要它；若确实报这个错，把它加到 `testImplementation` 即可。 |
| 首次运行长时间无输出 | 正在下载 Robolectric 的 API 36 运行时镜像（上百 MB）。等它下完，之后走缓存。 |
| 图片尺寸和别人不一样 | 说明设备档位没固定住：确认跑的是本目录的 `golden()`，且没人在测试里改 `RuntimeEnvironment.setQualifiers`。 |
| 差异图里文字全都位移了一点 | 优先怀疑字体缩放或密度被改动，而不是「UI 真的错位」；查 `GoldenDevice` 是否被改过。 |

