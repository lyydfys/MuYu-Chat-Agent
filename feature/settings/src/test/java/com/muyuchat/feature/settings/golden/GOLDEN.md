# 设置首页视觉金样（screenshot golden）

本目录用 **Roborazzi + Robolectric** 在纯 JVM 上渲染 `:feature:settings` 的 Compose 界面并生成 PNG 基线。
不模拟器、不连真机。

> 基础设施的来源是 `:core:designsystem`，那份文档写得更细（含两个 JDK/Robolectric 坑的完整证据）：
> `core/designsystem/src/test/java/com/muyuchat/core/designsystem/golden/GOLDEN.md`。
> 本文件只记录本模块的差异点与 4 张基线。

## 1. 覆盖内容（4 张）

| # | 文件 | 内容 |
| --- | --- | --- |
| 1 | `settings-hub-light.png` | 设置首页（dashboard），浅色 |
| 2 | `settings-hub-dark.png` | 设置首页，深色 |
| 3 | `settings-search-light.png` | 设置搜索页展开（已输入查询），浅色 |
| 4 | `settings-search-dark.png` | 设置搜索页展开，深色 |

全部位于 `feature/settings/src/test/screenshots/`，均为 `1078x2338` RGBA
（= 411dp × 891dp @ 420dpi，与 `:core:designsystem` 的口径完全一致，两个模块的图可直接横向比较）。

首页那张包含：顶栏（`MCA · LOCAL CONTROL` + 关闭）、标题`系统设置`、hero 渐变卡与三个状态 chip、
**搜索入口**、`配置中心 / 7 个分区`、以及分区卡网格（411dp 视口内 4 张完整 + 底部露出下一行，
与实际滚动首屏一致）。

## 2. 怎么跑

```powershell
.\gradlew.bat :feature:settings:recordRoborazziDebug    # 录制
.\gradlew.bat :feature:settings:verifyRoborazziDebug    # 校验
.\gradlew.bat :feature:settings:compareRoborazziDebug   # 差异图
.\gradlew.bat :feature:settings:clearRoborazziDebug     # 清空（危险）
```

与 `:core:designsystem` 一样：**`testDebugUnitTest` 不会校验金样**。Roborazzi 只在上面这些任务里抓图，
普通单测里 `captureRoboImage` 是空操作。实测 `:feature:settings:testDebugUnitTest` 全绿（89 tests /
0 failures / 18 suites），且日志里 `finalizeTestRoborazziDebug SKIPPED` —— 即 CI 的默认测试任务
**不会**因为基线缺失或过期而变红，金样是显式调用的门禁。

## 3. 为什么 harness 是「复制」而不是复用

`core:designsystem` 的 `GoldenHarness` 在该模块的 **test 源集**里。Kotlin 的 test 源集不发布、
对其他模块不可见，所以 `:feature:settings` 的测试**无法** import 它（连 `internal` 都看不到）。
因此本模块有自己的 `SettingsGoldenHarness.kt`，设备档位/字体缩放/动画缩放三项口径与那份完全一致。

将来如果第三个模块也要金样，正确做法是抽一个独立的 test-fixture 模块（`testFixtures` 源集发布成
artifact），而不是继续复制第三份。现在两份的重复被限制在单个 harness 文件内。

## 4. 主题为什么用 `McaTheme` 而不是色彩方案

`:core:designsystem` 的 harness 直接取 `mcaColorScheme(dark)`，但那个函数是 designsystem 的
`internal`，本模块取不到。本模块改用公开的 `McaTheme(darkTheme = ...)` —— 它内部注入的正是同一个
`mcaColorScheme`，所以**像素与 designsystem 的金样同源、可直接比较**，且本模块没有第二套颜色权威。
（系统栏写入不在位图内，另行由 designsystem 的 `McaThemeSystemBarContractTest` 覆盖。）

## 5. 稳定性：hero 的无限动画已被实证冻结

`SettingsHubScreen` 的 hero 用 `rememberInfiniteTransition` 做 28 秒无限旋转光轨。这类动画是
截图最典型的抖动源：抓到的相位取决于跑了多少帧，两次录制就会得到两张"都对"的图。

本 harness 在每次抓图前把系统动画缩放三档全置 0，Compose 1.11 正是通过它推导 `MotionDurationScale`，
动画因此定格在初始值。**不是推测，已实证**：

1. 录制一轮，记录 4 张的 SHA-256；
2. 删除 `build/intermediates/roborazzi/` 与 `build/test-results/testDebugUnitTest/`（否则 record 会
   直接从中间目录**还原**旧图，测试任务仍是 UP-TO-DATE，等于没重跑——这一点很容易误判成"稳定"）；
3. 再录一次，确认 `testDebugUnitTest` 真的 **executed**（不是 UP-TO-DATE）；
4. 两次 4 张 SHA-256 **逐字节一致**。

以后复核稳定性时可以照做；注意第 2 步，否则会得到假阳性的"确定性"结论。

## 6. 为什么第 3、4 张拍搜索页而不是外观子页

`SettingsHubScreen` 把 `searchOpen` 与当前打开的 `SettingsSection` 都存在内部
`rememberSaveable` 状态里，**外部无法驱动**；唯一能从外部驱动的非默认态只有 `startInWebSearch`。
但搜索**页**本身（`SettingsSearchPage`）是 `internal`，而本测试就在本模块的 test 源集里，
可以直接渲染它 —— 于是拿到了真正可驱动的「搜索页展开」。

没有选外观子页，理由有两个，且第二条是决定性的：

1. `SettingsAppearanceScreen` 是 `private`，只能经内部 `section` 状态进入，同样外部不可驱动；
2. 外观表面**已经被 designsystem 的 4 张金样覆盖**（`appearance-section-*`），再拍一张只是近重复，
   不增加覆盖。

查询词用 `检索`：它能命中跨两个分区的多行（`启用联网检索`/`最近检索`/`测试检索`/`联网搜索` 等），
所以拍到的是**分组结果布局**而不是空态。

## 7. 局限（如实记录）

- 只有 1 个设备档位（411dp × 891dp / 420dpi / fontScale 1.0）与亮暗两套配色；
  未覆盖大字体、横屏、RTL、多语言。
- 只覆盖首页与搜索页两个表面；外观/运行/日志/隐私/联网检索/实验/关于 这些**子页**没有金样。
- 未覆盖交互态（按下、涟漪、滚动位置、键盘、焦点）。
- 首页用的是 `SettingsUiState()` 全默认值（无模型、无日志、统计为 0）——这是最稳定的状态，
  但不是"装满数据"的状态；带数据的首页若要金样，需要另建 fixture 并接受它随数据规则变化。
- 金样是像素回归网，不替代真机视觉验收。

## 8. 与 designsystem 金样的关系

| 模块 | 张数 | 任务前缀 |
| --- | --- | --- |
| `:core:designsystem` | 14 | `:core:designsystem:recordRoborazziDebug` 等 |
| `:feature:settings` | 4 | `:feature:settings:recordRoborazziDebug` 等 |

两套互不影响：录制本模块不会重写 designsystem 的基线，反之亦然（Roborazzi 任务按模块注册）。
两模块的 `--add-exports java.base/jdk.internal.access=ALL-UNNAMED` 与
`--add-opens java.base/java.io=ALL-UNNAMED` 必须保持一致；缺任一条，本模块所有 Robolectric 测试会在
应用启动阶段就以 `Failed to interact with raw FileDescriptor internals` 失败。
