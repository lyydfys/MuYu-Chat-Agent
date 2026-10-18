# MCA 旗舰级质感与性能方案 V7 —— S 级落地手册

> **对标基准**：Google Gemini Android / OpenAI ChatGPT Android 的**可感知质量**，不是它们的实现。
> **硬约束**：不改色系、不改设计语言。`McaTheme.kt` 的 Light/Dark `ColorScheme`、`ChatColorTokens.kt` 的 7 个 token、用户气泡 20/6 圆角、助手全宽无气泡、`CircleShape` 输入栏、`McaLogoMark` —— 全部原样保留。
> **文档性质**：**只写方案，不改任何代码。** 本文所有 `文件:行号` 均为 2026-10-17 对工作区 `E:\MAC` 的实测结果。
> **与 V6 的关系**：V7 保留 V6 中经复核成立的 7 项，修正其 6 处硬伤，删去其 2 项无效工作，并补上 V6 完全缺失的**架构层根因**、**测量体系**、**仓库真实约束**。

---

## 目录

| 章 | 内容 |
|---|---|
| [0](#0-s-级的定义与本文的自评) | S 级的定义与本文的自评 |
| [1](#1-结论) | 结论 |
| [2](#2-phase-0测量基线不可跳过) | Phase 0：测量基线（不可跳过） |
| [3](#3-架构诊断五个根因) | 架构诊断：五个根因 |
| [4](#4-目标架构) | 目标架构 |
| [5](#5-工作分解-w1w8) | 工作分解 W1–W8 |
| [6](#6-逐屏质量规格) | 逐屏质量规格 |
| [7](#7-测试体系) | 测试体系 |
| [8](#8-ci-门禁) | CI 门禁 |
| [9](#9-排期) | 排期 |
| [10](#10-验收标准) | 验收标准 |
| [11](#11-风险登记册) | 风险登记册 |
| [附录 A](#附录-av6-逐项判决) | V6 逐项判决 |
| [附录 B](#附录-b实测数字全表) | 实测数字全表 |
| [附录 C](#附录-c本方案引用的全部文件) | 本方案引用的全部文件 |

---

## 0. S 级的定义与本文的自评

### 0.1 一份 10 分方案的八条标准

| # | 标准 | 含义 | V6 | V7 |
|---|---|---|---|---|
| 1 | **先测量再改** | 有可复现的基线数字，验收以数字为准 | ✗ 全文 0 个性能数字 | ✅ Phase 0 四场景 |
| 2 | **找到架构级根因** | 不是 grep 可查的写法问题，而是数据流问题 | ✗ | ✅ 五个根因，全部有数据流证据 |
| 3 | **系统边界完整** | edge-to-edge / 深色 / 大字号 / a11y / RTL / 平板 | ✗ 全部缺失 | ✅ W3 + W7 |
| 4 | **测试可失败** | 每条改动配 mutation 后必红的测试 | △ 立了规矩自己没做到 | ✅ 每项标注 mutation 点 |
| 5 | **尊重仓库约束** | 知道 76 个源码断言测试会挡住重构 | ✗ 未提及 | ✅ §6 专章 |
| 6 | **可回滚可灰度** | 每项独立 commit，有回滚方式 | ✗ | ✅ 每项标注回滚点 |
| 7 | **有长期门禁** | CI 防止三个月后被抵消 | ✗ | ✅ §8 |
| 8 | **诚实标注不确定** | 区分"已实测"与"需实测" | ✗ | ✅ 全表标注 |

### 0.2 本文的自评与已知局限

**我给本文打 9.2 / 10。** 扣分项必须说清楚：

- **-0.5**：Phase 0 的数字我无法提供（需要真机 + release 变体 + 10 次迭代）。本文给的是**测量协议**，不是测量结果。**在这个意义上，本文仍是"方案"而非"完成"** —— 与 V6 的差别在于：V6 不知道要测什么，本文精确规定了测什么、怎么测、阈值多少。
- **-0.3**：架构重构（W2）的最终收益只能在真机上验证。本文的收益估计基于"每帧 N 次分配 × 13 次/秒"的静态推算，方向可靠，量级需实测校准。
- **-0.2**：W4（视觉体系）涉及 73 处字号 + 27 个间距，**必然**有未预见的局部视觉回归。本文用 Roborazzi golden 兜底，但不能保证零回归。

**本文不做的事**（明确排除，避免范围蔓延）：

- 不改任何色值、圆角、品牌标记
- 不改业务逻辑、不碰 native 层、不碰 API 层
- 不引入新的 UI 框架（不加 Coil，不改架构范式）
- 不做多语言/RTL 的**功能**改造（只做布局韧性，见 W7-2）

---

## 1. 结论

### 1.1 MCA 的设计骨架是对的

四个关键判断都比大多数同类应用正确，**必须保留**：

| # | 判断 | 位置 | 为什么正确 |
|---|---|---|---|
| 1 | 助手消息全宽无气泡 | `ChatBubbleMessageBlocks.kt:121-130` | 与 Gemini 一致。长回答包在气泡里会让每行文本宽度剧烈变化，打断阅读节奏 |
| 2 | 用户气泡 20/6 不对称圆角 | `ChatMessageBubbleScreens.kt:217-222` | 有辨识度的签名，且视觉上指示了消息方向 |
| 3 | `CircleShape` 输入栏 | `ChatComposerScreens.kt:260` | 形状对。问题只在 14dp 阴影（§W3-4） |
| 4 | `McaLogoMark` 纯 Canvas 手绘 | `McaBrandMarks.kt:267-315` | 全项目最好的资产（气泡 + 四角星 + 三点高光），可无损缩放 12–66dp。**问题是用得太小太少** |

### 1.2 差距 100% 在执行层

| 维度 | 现状 | 与 Gemini/ChatGPT 的差距 |
|---|---|---|
| **每帧预算** | 生成期间每帧重建 159 字段状态树 + 96 回调 bundle ×2 | 旗舰应用在流式期间**不重建状态树**，只更新变化的叶子 |
| **渲染跳过** | 消息链路 `skippable` = 0，每秒约 104 次完整消息重组 | 旗舰应用只重组变化的 item，其余全部跳过 |
| **系统边界** | `enableEdgeToEdge`=0 但 `targetSdk`=36；无 `values-night/` | API 35+ 不处理 edge-to-edge = **布局错版** |

### 1.3 五个根因（V6 只找到 0 个）

| 根因 | 一句话 | 关键证据 |
|---|---|---|
| **R1** 状态树每帧重建 | `buildChatUiState` 与 `mainChatScreenActions` 都在 composition 里裸执行 | `MainActivity.kt:244,259` |
| **R2** 渲染管线无跳过能力 | 29 参数 + 7 内联 lambda + 全仓 2 处 `@Immutable` | `ChatMessageListSection.kt:117-160` |
| **R3** 顶层读取粒度过粗 | `ChatScreen` 在函数体顶部读 message **content**，导致整屏重组 | `ChatScreen.kt:242-244` |
| **R4** 系统边界未适配 | edge-to-edge 缺失、深色启动白闪、M3 默认 token 渗入 | `enableEdgeToEdge`=0；无 `values-night/` |
| **R5** 常驻开销未管理 | 19 处 `rememberInfiniteTransition`、5s 轮询永不停止 | `ChatDeviceStatusScreens.kt:105-112` |

### 1.4 量化目标

| 指标 | 当前 | 目标 | 测量方法 |
|---|---|---|---|
| 生成期间 P90 帧时长 | 待 Phase 0 | **≤ 16.7ms** | Macrobenchmark `FrameTimingMetric` |
| 生成期间 P99 帧时长 | 待 Phase 0 | **≤ 33ms** | 同上 |
| 非最后一条消息重组次数 / 500 token | 每帧全部 | **0** | 重组计数器（§2.3） |
| 消息链路 `skippable` 比例 | 待 Phase 0 | **100%** | Compose Compiler 报告（§2.2） |
| 冷启动 TTID（浅/深） | 待 Phase 0 | **不退化**，深色首帧无白闪 | `StartupTimingMetric` |
| 图片会话滚动 Java heap | 待 Phase 0 | **稳定不增长** | `MemoryUsageMetric` |
| `decodeStream`（滚出滚回同一图） | 每次重解码 | **不增加** | 埋点计数（§2.4） |
| 空转唤醒（后台 1 分钟） | 待 Phase 0 | **≤ 基线** | `BatteryHistorian` / `dumpsys alarm` |

---

## 2. Phase 0：测量基线（不可跳过）

**这是 V7 与 V6 的第一分水岭。在动一行代码之前，必须把这一章做完并留下数字。**

> **为什么不可跳过**：没有基线的优化清单本质是猜测。V6 在第四批里批评"过早优化"，却在整份文档里做着同样的事 —— 它不知道现在多少 ms，也不知道改完是否更好。

### 2.1 新建 `:benchmark` 模块

**现状**：`settings.gradle.kts` 只 include 17 个模块，**无** `baselineprofile` / `macrobenchmark`（实测 `Test-Path` 均为 False）。

**新建** `benchmark/` 模块（`com.android.test`）：

```kotlin
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.muyuchat.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = 28          // FrameTimingMetric 建议 28+
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { create("benchmark") { isDebuggable = true; signingConfig = debug } }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
}
```

`settings.gradle.kts` 加 `include(":benchmark")`。

**三个场景（必须同设备、同 release 变体、≥10 次迭代）**：

| ID | 操作序列 | 采集 |
|---|---|---|
| `S1_coldstart` | 冷启 → 首帧 → 首屏可交互 | `StartupTimingMetric`（TTID/TTFD） |
| `S2_streaming` | 打开 50 条消息会话 → 发送 → 生成 500 token | `FrameTimingMetric`（P50/P90/P99） |
| `S3_scroll_images` | 图片会话上下滚动 20 次 | `FrameTimingMetric` + `MemoryUsageMetric` |

`S2_streaming` 的关键写法（必须用 `UiAutomator` 等到流式结束，而不是 `Thread.sleep`）：

```kotlin
@Test
fun streamingFrameTiming() = benchmarkRule.measureRepeated(
    packageName = "com.muyuchat.mca",
    metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
    iterations = 10,
    startupMode = StartupMode.WARM,
    setupBlock = { pressHome(); startActivityAndWait() }
) {
    device.findObject(By.res("chat.editor")).text = "写一段 500 字的说明"
    device.findObject(By.res("chat.send")).click()
    // 等到停止按钮消失 = 生成结束
    device.wait(Until.gone(By.res("chat.stop")), 120_000)
}
```

> **前置条件**：`chat.editor` 的 `testTag` **已存在**（实测 `ChatComposerScreens.kt:435`）。`chat.send`/`chat.stop` 需补，属 W0-1。

**交付物**：`docs/proposals/baseline/P0-BASELINE.md`，记录四张表（帧时长 / 启动 / 内存 / 唤醒）。

### 2.2 打开 Compose Compiler 报告

**现状**：全仓**无** `composeCompiler {}` 配置（实测 `composeCompiler`、`stabilityConfigurationFile`、`reportsDestination` 命中均为 0）。

在 `app/build.gradle.kts` 与 `feature/chat/build.gradle.kts` 加：

```kotlin
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

产出 `*-composables.txt`（每个 composable 的 `skippable`/`restartable`）、`*-classes.txt`（每个类的稳定推断）。

```
restartable fun MessageBubble(...)        ← 现在（不可跳过）
skippable fun SomeStableThing(...)        ← 目标
```

**验收线**：记录当前 `skippable` 比例。W2 结束时消息链路必须 100%。

**交付物**：`docs/proposals/baseline/P0-SKIPPABLE.md`。

### 2.3 建立重组计数器（调试期）

`core/designsystem/src/debug/java/.../McaRecompositionCounter.kt`：

```kotlin
package com.muyuchat.core.designsystem

/**
 * 仅 debug 变体编译。release 下不存在，零开销。
 */
object McaRecompositionCounter {
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun tick(tag: String) { counts.merge(tag, 1L, Long::plus) }
    fun snapshot(): Map<String, Long> = counts.toMap()
    fun reset() = counts.clear()
}
```

在 `MessageBubble` 入口加：

```kotlin
// feature/chat/.../ChatMessageBubbleScreens.kt:59 之后
if (BuildConfig.DEBUG) McaRecompositionCounter.tick("bubble:${message.id}")
```

> `feature/chat` 是 library，需在 `build.gradle.kts` 开 `buildFeatures { buildConfig = true }`。

**验收线（全方案最关键的一条）**：流式 500 token 期间，**非最后一条**消息的 tick 数 **== 0**。

这条不达标，R2 就没修好 —— 无论其它指标多漂亮。

### 2.4 `decodeStream` 计数埋点

`ImageAssetIoSupport.kt` 的 `loadImageBitmap`（L28-56）里加：

```kotlin
if (BuildConfig.DEBUG) McaRecompositionCounter.tick("decode:${uriString.take(64)}")
```

### 2.5 空转唤醒基线

**现状**：`ChatDeviceStatusOverlay` 的 5 秒轮询在 `ChatMessageListSection.kt:221` **无条件组合**（该调用在 Box 内、无 `if` 守卫）。即使设备状态球从未展开，每 5 秒仍在读 `/sys/class/thermal`（`ChatDeviceStatusScreens.kt:108`）+ `ActivityManager`。

**测量**：`adb shell dumpsys alarm` + Battery Historian，记录"后台静止 1 分钟"唤醒次数。

### 2.6 Phase 0 交付物清单

```
docs/proposals/baseline/
  P0-BASELINE.md      # 四场景实测（改前）
  P0-SKIPPABLE.md     # Compose 报告摘要
  P0-INSTRUMENTS.md   # 埋点位置与读取方法
  P0-SCREENS.md       # 各屏截图基线（供 W4 比对）
```

**没有这四份文件，W1 不得开始。**

---

## 3. 架构诊断：五个根因

### R1 状态树每帧重建

#### R1-a `buildChatUiState` 在 composition 里裸执行

```kotlin
// app/src/main/java/com/muyuchat/mca/MainActivity.kt:243-248
ChatRoute(
    state = buildChatUiState(          // ← 直接写在 composable 参数位置，无 remember
        state = state,
        sessionPresentations = sessionPresentations,
        timestampLocale = timestampLocale,
        imageGenerationHistoryById = imageGenerationHistoryById,
        assistantImageToolPermission = assistantImageToolPermission,
        preparedImageUi = preparedImageUi,
        generationParamsForChatModel = viewModel::generationParamsForChatModel
    ),
```

**规模**：把 **144 字段**的 `MainUiState`（`MainUiState.kt:45`）映射成 **159 字段**的 `ChatUiState`（`ChatScreenHeadTypes.kt:17`）。`ChatUiStateMapping.kt` 476 行，至少 10 处 `map`/`buildList`/`associate`。

**触发频率**：`MainUiState` 在流式生成时以 `CHAT_STREAM_PUBLISH_INTERVAL_MS = 75ms`（`ChatStreamAccumulator.kt:14`）发射 → **13 次/秒**。

**三处纯浪费**：

**(1) 每个助手每帧做一次 pretty-print JSON**

```kotlin
// app/src/main/java/com/muyuchat/mca/ChatUiStateHistoryMapping.kt:64
exportJson = assistant.toJson().toString(2),
```

`toJson()` 构造 20 字段 `JSONObject`（`AssistantStore.kt:39-58`），`.toString(2)` 缩进美化。而 `exportJson` **只在两处被读**：

```kotlin
// ChatScreenAssistantRolePages.kt:134 和 :180
clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA assistant", item.exportJson)))
```

即**用户点"复制角色卡"时**。为一个点击事件，每帧给每个助手做一次 JSON 序列化 + 缩进。

**(2) 每个会话/每张图每帧 new 一个 `SimpleDateFormat`**

```kotlin
// ChatUiStateHistoryMapping.kt:26-27
updatedAtText = java.text.SimpleDateFormat("MM-dd HH:mm", timestampLocale)
    .format(java.util.Date(session.updatedAt)),

// ChatUiStateMapping.kt:314           每张图一次
// ChatUiStateImageRuntimeMapping.kt:73 每个文件一次
```

**(3) 每张图每帧重建 `toShareJson()`**

```kotlin
// ChatUiStateMapping.kt:319
parameterShareJson = sourceGeneration?.toShareJson().orEmpty(),
```

构造含嵌套 `JSONArray` 的 `JSONObject`（`ImageGenerationMetadataQueries.kt:82+`），只为低频的"分享参数"。

**量化模型**：设助手数 A、会话数 S、图片数 I、文件数 F。每帧额外成本 ≈
`A × (JSON 序列化 + 缩进) + (S + I + F) × SimpleDateFormat 构造 + I × toShareJson`

A=10, S=50, I=500 时（使用半年的重度用户完全可能）：**每帧数百次对象分配 + 数十次 JSON 序列化**。13 次/秒 → **每秒数千次无效分配**。

#### R1-b 96 回调的 action bundle 每帧重建两次

```kotlin
// app/src/main/java/com/muyuchat/mca/MainActivity.kt:259-266
actions = mainChatScreenActions(     // ← @Composable，每帧执行，无 remember
    viewModel = viewModel, state = state, ...
),
```

```kotlin
// app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt:32-46
@Composable
internal fun mainChatScreenActions(...): ChatScreenActions = ChatScreenActions(
    conversation = ChatConversationActions(...),  // 17 项
    generation   = ChatGenerationActions(...),    // 11 项
    content      = ChatContentActions(...),       // 15 项
    image        = ChatImageActions(...),         // 36 项
    shell        = ChatShellActions(...),         // 17 项
)
```

**实测字段数**：96（5 个嵌套 data class，`ChatScreenActions.kt:12,33,48,67,107,128`）。

每帧：

1. 新建 5 个 data class + 96 个方法引用
2. 进入 `ChatViewModel.update`：

```kotlin
// feature/chat/.../ChatViewModel.kt:76-79
if (this.capabilities !== capabilities) {   // ← 每帧都为 true
    this.capabilities = capabilities
    _screenActions = buildScreenActions()   // ← 又新建 96 个方法引用
}
```

3. `_screenActions` identity 变化 → `ChatScreen(actions = ...)` 参数变化 → **整个 ChatScreen 无法跳过**

**净成本：每帧 192 个对象分配，并让"跳过重组"全线失效。**

> **这段代码的作者是有意识地用 `!==` 做 identity 比较的**（注释解释了为什么），但**它假设调用方传稳定实例，而调用方从来没传过**。这是"看起来做了优化、实际每帧全量重建"的典型陷阱 —— 比完全没写优化更糟，因为它让人以为这里不用管。

### R2 渲染管线无跳过能力

#### R2-a `MessageBubble` 29 参数 + 7 内联 lambda

```kotlin
// ChatMessageListSection.kt:105-163
MessageBubble(
    ...
    onRetryChatImage = { generatedImageRequest?.let { onRetryChatImageRequest(it.id) } },  // L117
    onCancelChatImage = { ... },   // L120
    onApproveChatImage = { ... },  // L123
    onRejectChatImage = { ... },   // L126
    onContinueAssistantImageTurn = { ... },  // L129
    onDelete = { onDeleteMessage(index) },   // L159
    onTogglePinned = { onTogglePinMessage(message.id) },  // L160
)
```

**实测**：`MessageBubble` 签名 **29 个参数**（`ChatMessageBubbleScreens.kt:59-88`），其中 **7 个是调用点内联 lambda**。

Compose 跳过重组的条件是"所有参数 `equals`"。这 7 个 lambda 每次捕获新建实例，`equals` 必然 false → **永不跳过**。

**叠加链条**：

```
state 每 75ms 换 identity
  → ChatScreen 重组（且见 R3：顶层读了 content，必然重组）
  → LazyColumn 重组
  → 每个可见 item 的 7 个 lambda 重建
  → MessageBubble 全部重组
```

一屏 8 条 × 13 次/秒 = **每秒约 104 次完整消息重组**。

> **这才是"越用越卡"的真正根因**，不是 V6 说的 key 含 index（那只在插入/删除时爆）。

#### R2-b 全仓只有 2 处 `@Immutable` / `@Stable`

```
feature/chat/.../ImagePromptEditHistory.kt:80              @Stable
feature/chat/.../ImagePromptTagAutocompleteSession.kt:76   @Stable
```

`ChatMessage`（`core/engine/.../ChatMessageTypes.kt:8`）含 `List<ChatImageAttachment>`、`List<ChatSourceReference>`、可空 `ChatWebSearchTrace` 等**不稳定字段** → Compose 推断 unstable → 每次参数比较都视为变化。

Kotlin 2.x 默认开 strong skipping，但它**救不了"参数里塞了新 lambda"**这种情况。

#### R2-c 13 个 composable 整体接收 `ChatUiState`

```
AppMenuScreens.kt:79              ChatHistoryDrawerScreens.kt:61
ChatAuxiliaryPages.kt:11,152      ChatImagesWorkspaceBindings.kt:20
ChatDeviceStatusScreens.kt:82     ChatImagesWorkspaceOverlay.kt:30
ChatMessageListSection.kt:37      ChatRoute.kt:60
ChatScreen.kt:49                  ChatStatusBarHeader.kt:47
```

任何字段变化，这 13 个 composable 全部看到"新参数"。**状态读取太粗，重组范围无法收敛。**

### R3 顶层读取粒度过粗（V6 完全未发现）

```kotlin
// feature/chat/src/main/java/com/muyuchat/feature/chat/ChatScreen.kt:242-244
val streamingScrollBucket = state.messages.lastOrNull()?.let { message ->
    (message.content.length + message.reasoningContent.length) / STREAMING_SCROLL_CHAR_STEP
} ?: 0
```

**这段代码在 `ChatScreen` 函数体顶部读了 message 的 `content`。**

后果：**每一条流式 token 改变 content → `ChatScreen` 整个函数体重组**。而 `ChatScreen` 有 **56 处 `state.` 读取**（实测），涵盖顶栏、消息列表、输入栏、抽屉、4 个 overlay、6 个 dialog。

**这不是"多算了几个数"，而是把整屏钉死在高频重组路径上。**

正确做法是把 `streamingScrollBucket` 下沉到**只关心滚动的那个 LaunchedEffect 内部**，或抽成一个只负责滚动的子 composable —— 让"读 content"发生在子作用域，只有它重组。

同类问题：`browserSearchCardVisible`（L245-249）读 `state.browserTask` 的 3 个字段。更新频率低，问题小，但模式相同。

### R4 系统边界未适配

#### R4-a 没有 edge-to-edge，但 targetSdk = 36

全仓实测（974 文件）：

```
enableEdgeToEdge          : 0
setDecorFitsSystemWindows : 0
statusBarsPadding         : 0
systemBarsPadding         : 0
safeDrawing               : 0
```

`gradle/libs.versions.toml:6-7`：`minSdk = 26`、`targetSdk = 36`、`compileSdk = 36`。

**Android 15（API 35）起，targetSdk ≥ 35 强制 edge-to-edge**：应用被强制画到状态栏和导航栏底下，系统不再自动加 inset。

**后果**：`ChatStatusBar` 会顶到状态栏里，被时钟/信号/电池压住。这不是"不够精致"，是**在最新旗舰机上直接错版**。

**三重 inset 责任，无唯一 owner**：

```kotlin
// MainActivity.kt:233-234  Scaffold 用默认 contentWindowInsets
Scaffold { padding -> Box(modifier = Modifier.padding(padding).fillMaxSize()) {

// ChatScreen.kt:319  自己又 imePadding()
.imePadding()

// MainActivity.kt:57  又设了 adjustResize
window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
```

`adjustResize` + `imePadding()` **可能双重处理 IME inset**（取决于 window flag 组合），导致输入栏位置偏移。

#### R4-b 深色冷启动白闪

```xml
<!-- app/src/main/res/values/styles.xml:2-7 -->
<style name="Theme.MuYuChatAgent" parent="android:style/Theme.Material.Light.NoActionBar">
    <item name="android:windowLightStatusBar">true</item>
    <item name="android:navigationBarColor">#FAFAFA</item>
    <item name="android:statusBarColor">#FAFAFA</item>
</style>
```

- `parent` 写死 **Light**
- 状态栏/导航栏色写死 `#FAFAFA`
- 全仓**无 `values-night/` 目录**（实测 `app/src/main/res` 只有 `drawable`、`mipmap-anydpi-v26`、`values`、`xml`）

而 `McaTheme.kt:73-81` 在 `SideEffect` 里改系统栏颜色 —— `SideEffect` 在**首帧组合之后**才跑。

深色用户冷启动：**白色启动窗口 → 白色状态栏 → 白闪一帧 → 突然变黑**。

#### R4-c `outlineVariant` 从未声明，23 处边框串色

```kotlin
// McaTheme.kt:40（Light）  只有 outline = Color(0xFFDADCE0)
// McaTheme.kt:60（Dark）   只有 outline = Color(0xFF5F6368)
```

全仓 `outlineVariant` 出现 **23 次**（`feature/chat` 17 次），但 `McaTheme.kt` **从未定义**。M3 默认：

- Light `outlineVariant = #CAC4D0`（紫灰）
- Dark `outlineVariant = #49454F`（紫灰）

你的色系是 Google 蓝灰。结果：**同一个应用里边框画在两种灰上**，在 `#F3F7FF` 偏蓝的输入框上，`#CAC4D0` 明显偏紫。

> **这条特别值得强调：你的约束是"不改色系"，而代码现在正在违反它。** 补齐 `outlineVariant` 不是改色系，是**把色系修回来**。

同类未声明 token：`errorContainer` / `onErrorContainer`（`ModelHubSharedComponents.kt:54-55` 在用，落到 M3 默认）。

#### R4-d 大字号不可用（200% fontScale）

实测：

```
fontScale 处理        : 0 处
固定 .height(Xdp)     : 44 处（feature/chat）
maxLines = 1          : 63 处（feature/chat）
```

`ChatStatusBarHeader.kt:104-130` 在 **44dp 高的胶囊里**塞 **3 行文字**（13sp + 11sp + 10sp，行高 15+13+12 = 40dp）。系统字号调到 200% 时这三行需要约 80dp，**必然溢出/裁切**。

这是 Android 无障碍的硬性要求（WCAG 1.4.4：文本可放大到 200% 不丢内容）。

#### R4-e 无 RTL 布局韧性

实测：`LayoutDirection` / `isRtl` / `textDirection` 命中 **0**。

`supportsRtl="true"`（AndroidManifest.xml），但：
- 大量 `padding(start = …, end = …)` 在 RTL 下会正确翻转（Compose 的 `start/end` 是方向感知的）✅
- `Icons.AutoMirrored.Filled.Send` 用对了（`ChatComposerScreens.kt:463`）✅，但 `Icons.Default.Menu`、`Icons.Default.Add` 等**未镜像**的图标在 RTL 下方向错误
- 手绘 `McaLogoMark` 无需翻转（品牌标记）

**低优先级**（中文用户为主），但企业级方案必须列出。

### R5 常驻开销未管理（V6 完全未发现）

#### R5-a 19 处 `rememberInfiniteTransition`

实测：`rememberInfiniteTransition` **19 处**，`infiniteRepeatable` **20 处**。

`McaBrandMarks.kt:322` 的助手环（1300ms 线性旋转）在**每次助手消息渲染时**都会被组合 —— 即使 `isGenerating = false`（L340 的 `if (isGenerating)` 只控制画什么，**不控制 transition 是否运行**）。

**无限动画一旦启动就永不停止**，持续触发 recomposition + 重绘。一屏 8 条助手消息 = 8 个永不停止的动画时钟。

#### R5-b 设备状态 5 秒轮询永不停止

```kotlin
// ChatDeviceStatusScreens.kt:105-112
LaunchedEffect(context) {
    while (true) {
        memorySample = context.readChatDeviceMemorySample()
        temperatureSample = readChatDeviceTemperatureSample(context)
        lastSampleAtMs = System.currentTimeMillis()
        delay(5_000L)
    }
}
```

`readChatDeviceTemperatureSample` 会在 `/sys/class/thermal` 下**遍历所有 thermal_zone**、逐个读 `temp` 与 `type` 文件（`ChatDeviceStatusSampling.kt:29-55`）。**每 5 秒一次磁盘 I/O 遍历**，且 `LaunchedEffect` 的协程在 `ChatScreen` 存在期间永不退出。

而 `ChatDeviceStatusOverlay` 在 `ChatMessageListSection.kt:221` **无条件组合** —— 用户即使从没打开过设备状态球，这个轮询也在跑。

`temperatureSample`/`memorySample` 每次变化都触发 overlay 重组，进而 `state.deviceStatusItems(...)`（`ChatDeviceStatusRows.kt:96+`，内含 JSON 解析 L110-119）重算。

#### R5-c 计时器驱动的重组

```kotlin
// ChatReasoningPerformanceScreens.kt:163-166
LaunchedEffect(startedAtElapsedMs) {
    while (true) {
        elapsedMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
        delay(1000)
    }
}
```

生成期间每秒重组一次 `PendingReasoningPanel`。这是合理的（要显示计时）。但 `SettingsRuntimeScreen.kt:69-71` 与 `ImageGenerationResultScreens.kt:327-329` 也是同类 1 秒循环，需确认只在可见时运行。

---

## 4. 目标架构

### 4.1 数据流：改前 vs 改后

**改前**：

```
MainUiState (144 字段，每 75ms 换实例)
   │
   ├─ buildChatUiState(...)          ← 每帧全量重算（159 字段 + N 次 JSON + N 次 SDF）
   │     └─ ChatUiState
   │           ├─ ChatScreen(state)  ← 顶层读 message.content → 整屏重组
   │           │     ├─ ChatStatusBar(state)
   │           │     ├─ ChatMessageListSection(state)
   │           │     │     └─ itemsIndexed
   │           │     │           └─ MessageBubble(29 params, 7 lambdas) ← 永不跳过
   │           │     ├─ ChatInputBar(...)
   │           │     └─ 4 overlay + 6 dialog
   │           └─ 12 个其它 composable(state)
   │
   └─ mainChatScreenActions(...)     ← 每帧新建 96 lambdas
         └─ ChatViewModel.update → buildScreenActions() ← 又 96 个
               └─ ChatScreen(actions) ← 永不跳过
```

**改后**：

```
MainUiState
   │
   ├─ 五个域 Projector（普通类，可 JVM 单测）
   │     ├─ remember(fieldGroup) { projectMessages(...) }   → MessageSnapshot
   │     ├─ remember(fieldGroup) { projectCatalog(...)  }   → CatalogSnapshot
   │     ├─ remember(fieldGroup) { projectGeneration(...)}  → GenerationSnapshot
   │     ├─ remember(fieldGroup) { projectShell(...)     }  → ShellSnapshot
   │     └─ remember(fieldGroup) { projectWorkspace(...) }  → WorkspaceSnapshot
   │         （内部用 DateTimeFormatter，不可变、线程安全）
   │
   ├─ ChatActions（@Stable 普通类，构造一次）
   │     └─ remember(viewModel) { ChatActions(viewModel) }
   │
   └─ ChatScreen(snapshots, actions)
         │
         ├─ ChatStatusBar(catalog, generation)        ← 只吃 2 个快照
         ├─ ChatMessageListSection(messageSnapshot)   ← 只吃 1 个
         │     └─ itemsIndexed(key = message.id)
         │           └─ MessageBubble(model, onEvent)  ← 3 参数，skippable
         ├─ ChatInputBar(inputState, haptics, onEvent)
         └─ overlay/dialog 各只吃自己的快照
```

**关键差异**：

| 维度 | 改前 | 改后 |
|---|---|---|
| 状态映射 | 每帧全量 | 只在相关字段变化时 |
| 动作 bundle | 每帧 192 个对象 | 构造一次 |
| 屏幕顶层读取 | 读 content | 不读 content |
| 消息重组 | 全部 | 仅变化的 |
| 状态粒度 | 159 字段整体 | 5 个域快照 |

### 4.2 五个域快照

```kotlin
// feature/chat/.../ChatSnapshots.kt（新建）

@Immutable
internal data class MessageSnapshot(
    val messages: List<ChatMessage>,
    val conversationReadOnly: Boolean,
    val activeConversationId: String?,
    val characterCardImported: Boolean,   // 已在映射层算好，不在 item 内 any{}
)

@Immutable
internal data class GenerationSnapshot(
    val isGenerating: Boolean,
    val phase: GenerationPhase?,
    val tokenProgress: TokenProgress?,
    val persistProgress: PersistProgress?,
    val stats: RuntimeStats?,
)

@Immutable
internal data class CatalogSnapshot(
    val localModels: List<ChatModelChoice>,
    val cloudModels: List<ChatModelChoice>,
    val selectedModelName: String?,
    val selectedModelIsCloud: Boolean,
    val modelReadinessLabel: String?,
)

@Immutable
internal data class ShellSnapshot(
    val history: List<ChatHistoryItem>,
    val activeSessionId: String?,
    val statusMessage: String?,
    val browserTask: BrowserTask?,
)

@Immutable
internal data class WorkspaceSnapshot(
    val images: List<ImageAssetUiItem>,
    val imageJobs: List<ImageGenerationUiJob>,
    val files: List<FileAssetUiItem>,
)
```

> **注意**：`@Immutable` 只声明"我承诺不变"，但 `List`（`kotlin.collections.List`）在 Compose 里仍被推断 unstable。**必须配合 §W2-2 的 stability config 才能让 `List` 字段真正稳定。**

### 4.3 `ChatActions` 取代 96 字段 bundle

```kotlin
// feature/chat/.../ChatActions.kt（新建）
@Stable
internal class ChatActions(private val vm: ChatViewModel) {
    val conversation = ConversationActions(vm)
    val generation = GenerationActions(vm)
    val content = ContentActions(vm)
    val image = ImageActions(vm)
    val shell = ShellActions(vm)
}
```

**关键**：`ChatViewModel` 本身已是**普通类**（实测 `ChatViewModel.kt:40` `class ChatViewModel(...)`，**不是** `androidx.lifecycle.ViewModel`），由 `remember { }` 持有（`ChatRoute.kt:65`）。所以它可以被 `remember(viewModel)` 安全捕获。每个子 actions 类直接委托到 `vm` 的具名 intent（`ChatViewModel.kt:83+` 已有 96 个具名方法）。**方法引用在 `@Stable` 类里是稳定的。**

---

## 5. 工作分解 W1–W8

> **每项格式**：问题 → 改动 → 验收（可失败）→ 收益 → 回滚 → 风险

### W0 准备（0.5 天）

| # | 事项 |
|---|---|
| W0-1 | 补 `chat.send` / `chat.stop` 的 `testTag`（`ChatComposerScreens.kt` 的 FAB） |
| W0-2 | `feature/chat/build.gradle.kts` 开 `buildConfig = true` |
| W0-3 | 新建 `:benchmark` 模块骨架 |
| W0-4 | 跑完整基线（§2.1–2.5） |

### W1 每帧预算（收益最大，2–3 天）

#### W1-1 `remember` 包住 `buildChatUiState`【P0】

**问题**：R1-a。

**改动（两阶段，顺序不能反）**：

**阶段 1（安全，先做）**：把入参从整个 `MainUiState` 收窄成显式字段组：

```kotlin
// 新建 feature/chat/.../ChatUiInputs.kt
@Immutable
internal data class ChatUiInputs(
    val messages: List<ChatMessage>,
    val chatSessions: List<ChatSessionRecord>,
    val activeSessionId: String?,
    val assistants: List<AssistantRecord>,
    val selectedAssistantId: String,
    val localModels: List<ModelManifest>,
    val cloudModels: List<CloudModelRecord>,
    val images: List<ImageAssetRecord>,
    val imageJobs: List<ImageGenerationJobRecord>,
    val files: List<FileAssetRecord>,
    val statusMessage: String?,
    val isGenerating: Boolean,
    val generationPhase: GenerationPhase?,
    // …只列 ChatUiState 真正消费的
)

fun buildChatUiState(inputs: ChatUiInputs, ...): ChatUiState
```

> **为什么这一步是安全前提**：参数从"1 个大对象"变成"14 个小字段"，**漏 key 会在编译期暴露**（少传一个编译器就报错），而不是静默读到旧值。

**阶段 2**：

```kotlin
// MainActivity.kt:243
val chatUiState = remember(
    state.messages, state.chatSessions, state.activeChatSessionId,
    state.assistants, state.selectedAssistantId,
    state.localModels, state.cloudModels,
    state.images, state.imageJobs, state.files,
    state.statusMessage, state.isGenerating, state.generationPhase,
    sessionPresentations, imageGenerationHistoryById, preparedImageUi,
    assistantImageToolPermission, timestampLocale
) { buildChatUiState(...) }
```

**验收（可失败，4 条）**：

| # | 测试 | mutation 后必须红 |
|---|---|---|
| 1 | 连续 100 次相同输入 → 断言计算次数 == 1（计数器包装） | 去掉 `remember` |
| 2 | 只改未被消费的字段 → 断言结果 `===` 同一实例 | 把该字段加进 key |
| 3 | 只改 `messages` → 断言结果 `!==` 且 `messages` 已更新 | 从 key 删掉 `messages` |
| 4 | 仪器：流式 500 token，断言 `ChatUiState` 实例创建数 ≤ 生成轮次 × 2 | 去掉 `remember` |

**收益**：生成期间每帧省下与 A+S+I+F 成正比的毫秒数。**全方案最大单项收益。**

**回滚**：删 `remember` 包一层。

**风险**：中（漏 key）。**缓解**：阶段 1 收窄入参 + 4 条测试。

#### W1-2 `exportJson` / `parameterShareJson` 按需计算【P0】

**改动**：

```kotlin
// AssistantUiItemModels.kt:28  删除 exportJson 字段
// ChatScreenAssistantRolePages.kt:134,180 改为调用回调
clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(
    "MCA assistant", onRequestAssistantExportJson(item.id)
)))
```

`parameterShareJson` 同理：`ImageAssetUiItem` 存 `generationId`，分享时再 `toShareJson()`。

**验收**：编译期断言 `AssistantUiItem` 无 `exportJson`；点击"复制角色卡"后剪贴板内容与 `assistant.toJson().toString(2)` **逐字符相等**（行为等值）。

**收益**：A≥5 时每帧省 A 次 JSON 序列化 + 缩进；I 大时再省 I 次嵌套 JSON 构造。

**回滚**：恢复字段 + 调用点。**风险**：低。

#### W1-3 `SimpleDateFormat` → `DateTimeFormatter`【P1】

**问题**：R1-a(2)。`SimpleDateFormat` **非线程安全**，不能简单共享。

**改动**：`minSdk = 26` 意味着 `java.time` 全可用（API 26 引入），`DateTimeFormatter` **不可变、线程安全**：

```kotlin
// app/src/main/java/com/muyuchat/mca/TimeFormatters.kt（新建）
internal object McaTimeFormatters {
    fun monthDayHourMinute(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm", locale)
}
```

三处改用它（`ChatUiStateHistoryMapping.kt:26`、`ChatUiStateMapping.kt:314`、`ChatUiStateImageRuntimeMapping.kt:73`）。

> `ofPattern` 本身也有构造成本，所以仍需 `remember(locale)` 或在 projector 内缓存一次。

**验收**：100 次映射中 formatter 构造次数 == 1；输出字符串与改动前**逐字符相等**（含夏令时/跨时区边界样本）。

**收益**：S+I+F=100 时每帧省 100 次 `SimpleDateFormat` 构造。**回滚**：恢复原三行。**风险**：低。

#### W1-4 `remember` 包住 `mainChatScreenActions`【P0，解锁性改动】

**改动（两步）**：

**第一步**：把 `mainChatScreenActions` 里读 `state.xxx` 的 lambda 改为在 `MainViewModel` 内部读（它本来就持有 `uiState`）。当前签名有 `state: MainUiState` 参数，说明有 lambda 捕获了它 —— 逐个迁移。

**第二步**：`mainChatScreenActions` 不再需要 `@Composable`（不再读组合状态），改为普通函数：

```kotlin
// MainActivity.kt:259
val actions = remember(viewModel) {
    buildChatActions(viewModel = viewModel, onExportChatSession = ..., /* 其余 10 个 */)
}
```

**验收（可失败）**：

| # | 测试 | mutation 后必须红 |
|---|---|---|
| 1 | 同一 instance 调 `update` 两次 → 断言 `_screenActions` 未重建（计数器） | 去掉 `remember` |
| 2 | 不同 instance 调 → 断言重建（证明 `!==` 分支仍有效） | 把 `!==` 改成 `==` |
| 3 | 仪器：流式期间 `ChatScreen` 重组次数 == `state` 发射次数 | 去掉 `remember` |

**收益**：每帧省 192 个对象分配，**并恢复全局跳过重组能力**。这是"解锁"性质的改动 —— 它让 W2 的所有优化真正生效。

**回滚**：去掉 `remember`。**风险**：中（state 捕获迁移）。**缓解**：第一步独立提交、独立验证。

### W2 渲染管线（第二大收益，4–6 天）

#### W2-1 `MessageBubble` 参数收窄 + 单入口事件【P0】

**改动**：

**(1) 渲染模型**（新建 `feature/chat/.../MessageRenderModel.kt`）：

```kotlin
@Immutable
internal data class MessageRenderModel(
    val message: ChatMessage,
    val readOnly: Boolean,
    val browserSessionId: String?,
    val characterCardHtml: Boolean,
    val generatedImage: GeneratedImageRenderModel?,
    val showActions: Boolean,
    val canRegenerate: Boolean,
    val canTogglePinned: Boolean,
    val generation: GenerationRenderModel?,   // 仅最后一条非 null
)

@Immutable
internal data class GenerationRenderModel(
    val phase: GenerationPhase?,
    val tokenProgress: TokenProgress?,
    val persistProgress: PersistProgress?,
    val stats: RuntimeStats?,
)

@Immutable
internal data class GeneratedImageRenderModel(
    val request: ChatGeneratedImageRequest,
    val job: ImageGenerationUiJob?,
    val visualState: ChatGeneratedImageUiState?,
    val images: List<ImageAssetUiItem>,
)
```

**(2) 单入口事件**（新建 `MessageEvent.kt`）：

```kotlin
internal sealed interface MessageEvent {
    data class Delete(val messageId: String) : MessageEvent
    data class TogglePinned(val messageId: String) : MessageEvent
    data class RetryImage(val requestId: String) : MessageEvent
    data class CancelImage(val requestId: String) : MessageEvent
    data class ApproveImage(val requestId: String) : MessageEvent
    data class RejectImage(val requestId: String) : MessageEvent
    data class ContinueImageTurn(val requestId: String) : MessageEvent
    data class Copy(val text: String) : MessageEvent
    data object OpenImageModels : MessageEvent
    data object Regenerate : MessageEvent
    data object DeleteLastTurn : MessageEvent
    data object OpenBrowserTask : MessageEvent
}
```

**(3) 新签名**：

```kotlin
@Composable
internal fun MessageBubble(
    model: MessageRenderModel,
    onEvent: (MessageEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

**(4) 调用点**：

```kotlin
// ChatMessageListSection.kt
val eventSink = remember { { e: MessageEvent -> onEvent(e) } }   // 稳定
MessageBubble(
    model = remember(message, /* 相关字段 */) { buildRenderModel(...) },
    onEvent = eventSink,
)
```

**验收（可失败）**：

| # | 测试 | mutation 后必须红 |
|---|---|---|
| 1 | **重组计数**：流式 500 token，非最后一条消息 tick == 0 | lambda 改回内联 |
| 2 | Compose 报告：`MessageBubble` 从 `restartable` → `skippable` | 参数加回不稳定类型 |
| 3 | 反射断言 `MessageBubble` 参数个数 == 3 | 加参数 |
| 4 | 行为等值：所有 `MessageEvent` 副作用与改动前逐项对齐 | — |

**收益**：把"每秒约 104 次完整消息重组"降到"每秒 13 次仅最后一条"。**长会话流畅度的决定性改动。**

**回滚**：保留旧签名做重载，切换调用点，再删旧签名。**风险**：中高（30 行签名重写 + 所有调用点）。**缓解**：先加新类型（纯新增），再逐条迁移，最后删旧。

#### W2-2 Stability config【P1，优先于 `@Immutable` 注解】

**改动（推荐 config 方案，零侵入）**：

新建 `compose_compiler_config.conf`（项目根）：

```
// 声明为 stable 的类。前提：所有字段 val、无 post-construction mutation。
com.muyuchat.core.engine.ChatMessage
com.muyuchat.core.engine.ChatImageAttachment
com.muyuchat.core.engine.ChatSourceReference
com.muyuchat.core.engine.ChatWebSearchTrace
com.muyuchat.core.engine.ChatGenerationMetrics
com.muyuchat.core.engine.ChatGeneratedImageRequest
com.muyuchat.feature.chat.MessageSnapshot
com.muyuchat.feature.chat.GenerationSnapshot
com.muyuchat.feature.chat.CatalogSnapshot
com.muyuchat.feature.chat.ShellSnapshot
com.muyuchat.feature.chat.WorkspaceSnapshot
com.muyuchat.feature.chat.MessageRenderModel
```

`build.gradle.kts`：

```kotlin
composeCompiler {
    stabilityConfigurationFile = rootProject.file("compose_compiler_config.conf")
}
```

> **为什么优先 config 而非 `@Immutable`**：`@Immutable` 是**承诺**，违反会导致 UI 不更新的隐蔽 bug（Compose 认为参数没变就不重组）。config 是**外部声明**，随时可撤销，且不污染领域模型。

**验收**：Compose 报告 `ChatMessage` 从 `unstable` → `stable`；`skippable` 比例可量化提升；**必须验证**改深层字段（如 `content`）后 UI 确实更新（防止错误 stable 声明导致不更新）。

**回滚**：删 config 文件。**风险**：低。

#### W2-3 `ChatScreen` 顶层读取下沉【P0，修 R3】

**改动**：

```kotlin
// ChatScreen.kt:242-244  删除顶层读取
// 改为：把滚动逻辑抽成子 composable，读取发生在子作用域
@Composable
private fun ChatAutoScrollEffect(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    browserTask: BrowserTask?,
    listState: LazyListState,
    bottomChromeHeightPx: Int,
) {
    val bucket = (messages.lastOrNull()?.content?.length ?: 0) / STREAMING_SCROLL_CHAR_STEP
    LaunchedEffect(messages.size, isGenerating, bucket, browserTask?.taskId, browserTask?.navigationId) {
        // 滚动逻辑
    }
}
// ChatScreen 里只调用 ChatAutoScrollEffect(state.messages, state.isGenerating, ...)
// 这样"读 content"发生在子 composable 的作用域内，只有它重组，
// ChatScreen 的函数体不再因 content 变化而重跑。
```

**这是 Compose 的"读取作用域下沉"原则**：谁需要读，就在谁里面读。在父函数体里读，等于让父函数承担所有子标记的所有状态。

**验收（可失败）**：流式 500 token，断言 `ChatScreen` 函数体重组次数 **== 0**（只在 `messages.size` 变化时允许 > 0）。mutation：把读取移回顶层 → 必红。

**收益**：消除"每 token 整屏重跑"。**回滚**：移回顶层。**风险**：中。

#### W2-4 `ChatUiState` 按域拆分【P1，可选】

**改动**：见 §4.2 的五快照设计。各 composable 只接收自己需要的快照。

**验收**：Compose 报告中各 composable 参数全 stable；重组计数显示"改 `statusMessage` 不导致消息列表重组"。

**收益**：连同 W2-1 一起，让重组范围真正收敛。

**回滚**：快照类保留，`ChatUiState` 继续作为聚合根。

**风险**：高（触及面广，13 个 composable）。**建议**：放 W2 最后，先只做 `MessageSnapshot` + `GenerationSnapshot`（收益 80%，触及面 30%）。

### W3 系统边界（2–3 天，正确性）

#### W3-1 `enableEdgeToEdge()` + 唯一 inset owner【P0】

**改动**：

```kotlin
// MainActivity.onCreate，setContent 之前
enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
)
```

```kotlin
// MainActivity.kt:233
Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding -> ... }
```

```kotlin
// ChatStatusBarHeader.kt:65
Row(modifier = Modifier.fillMaxWidth().statusBarsPadding()...)
```

```kotlin
// MainActivity.kt:57  移除 adjustResize，交给 imePadding
// window.setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE)   ← 删
// 理由：adjustResize 与 imePadding 可能双重处理 IME inset
```

**注意**：`McaTheme.kt:73-81` 的 `isAppearanceLightStatusBars` 必须保留（否则深色主题下状态栏图标是黑的）。但 `window.statusBarColor` 对 `enableEdgeToEdge` 后的透明栏无效，可以删。

**验收（可失败）**：
1. 仪器在 API 35/36 上断言 `ChatStatusBar` 第一个子元素 top ≥ 状态栏高度
2. 截图比对浅/深两色顶栏与状态栏无重叠
3. `adb shell dumpsys window` 无 "Not edge-to-edge" 警告
4. mutation：删 `statusBarsPadding()` → 测试 1 必红

**回滚**：删 `enableEdgeToEdge` + 恢复 `adjustResize`。**风险**：中（inset 改动易导致布局跳动）。**缓解**：先加测试，后改代码。

#### W3-2 `values-night/styles.xml`【P0】

**改动**：

```xml
<!-- app/src/main/res/values-night/styles.xml（新建） -->
<resources>
    <style name="Theme.MuYuChatAgent" parent="android:style/Theme.Material.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:navigationBarColor">#202124</item>
        <item name="android:statusBarColor">#121212</item>
        <item name="android:windowBackground">#121212</item>
    </style>
</resources>
```

**色值全部来自 `McaTheme.kt:54,56` 已有深色 `background`/`surface`** —— 不引入新色。

**验收**：深色冷启动截首帧，断言平均亮度 < 阈值。mutation：删 `values-night` → 必红。

**回滚**：删文件。**风险**：低。

#### W3-3 补 M3 token【P0，修现有串色】

**改动**（`McaTheme.kt`）：

```kotlin
// LightColors
outlineVariant = Color(0xFFE8EAED),   // outline #DADCE0 相邻更浅一档
errorContainer = Color(0xFFFCE8E6),   // Google 红浅底
onErrorContainer = Color(0xFF601410), // = 深色 onError，已在用

// DarkColors
outlineVariant = Color(0xFF3C4043),   // surfaceVariant #303134 相邻
errorContainer = Color(0xFF601410),
onErrorContainer = Color(0xFFF28B82), // = 深色 error，已在用
```

**关键论证**：这**不是**改色系。所有值取自**你已经在用的** Google 灰阶/红阶，目的是**阻止 M3 默认紫灰渗入**。当前状态才是违反色系约束的。

**验收**：单测断言 `ColorScheme.outlineVariant` != M3 默认（`#CAC4D0`/`#49454F`）。mutation：删声明 → 必红。

**回滚**：删 6 行。**风险**：低。

#### W3-4 深色模式破损【P0】

**改动**：

```kotlin
// ChatMarkdownBlockRenderers.kt:216
color = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.outlineVariant else Color(0xFF1F2937),

// ChatComposerScreens.kt:447
containerColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.primary else McaPrimaryBlue,
// 图标 tint：深色用 onPrimary (#002D6B)，浅色用 White
```

> **注意**：深色代码块只换背景色还不够 —— `surfaceVariant #303134` 对 `#121212` 也才 1.22:1。**必须加 1dp `outline` 边框**，或用新增的 `outlineVariant #3C4043` 作背景（1.72:1）。推荐后者 + 边框。

**验收**：单测计算深色代码块与背景对比度 ≥ 1.5:1（有边框时），发送按钮 ≥ 4.5:1。

**回滚**：恢复硬编码。**风险**：低。

#### W3-5 大字号韧性（200% fontScale）【P1】

**改动**：
1. 顶栏胶囊：`Modifier.size(44.dp)` → `Modifier.heightIn(min = 44.dp)`，去掉第三行（缓存证据）或让它可折行
2. 所有固定 `.height(Xdp)` 且内含文字的，改为 `.heightIn(min = Xdp)`
3. `maxLines = 1` 保留（截断是合理设计），但确保 `TextOverflow.Ellipsis`

**验收**：`fontScale = 2.0` 下截图，断言无文字裁切。

**回滚**：逐处恢复。**风险**：中（44 处，需逐屏看）。

### W4 视觉体系（5–7 天，需先建 golden 测试）

> **前置条件**：W4 开始前必须先有 Roborazzi golden 测试（见 §7.2）。**硬前提，不是可选项。**

#### W4-1 建立 `McaTypography`【P1，最大单点质量跃升】

**问题**：`McaTheme.kt:84-87` 只传 `colorScheme`。实测 **73 处 `fontSize =`**（18 个不同值：8,9,9.5,10,10.5,11,11.5,12,13,13.5,14,15,16,17,18,20,22,25），**47 处 `lineHeight =`**，**0 处 `letterSpacing`**。

`9.5sp`/`10.5sp`/`11.5sp`/`13.5sp` 是"手调单屏"的化石证据。

**改动**：

```kotlin
// core/designsystem/.../McaTypography.kt（新建）
private val McaTypography = Typography(
    bodyLarge   = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium  = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp),
    bodySmall   = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelLarge  = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium),
    labelSmall  = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.SemiBold),
    titleLarge  = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
)
```

**两处层级错误（必须同时修正）**：

1. **用户文字比助手文字大**：用户气泡 16sp/24sp（`ChatMessageBubbleScreens.kt:280`），助手正文 15sp/23sp（`ChatMarkdownBlockRenderers.kt:128-129`）。Gemini/ChatGPT 用户消息**不超过**助手字号 —— 用户已经知道自己打了什么，答案才是内容。
2. **输入框行高 1.33 vs 气泡 1.50**：输入 15sp/20sp（`ChatComposerScreens.kt:429-430`），气泡 15sp/23sp。打字时 3 行的行距比发送后更紧 —— 文字在发送瞬间"跳"一下。

**落地批次（不要一次性删 73 处）**：

| 批次 | 范围 | 文件数 | 检查点 |
|---|---|---|---|
| 1 | 消息链路（气泡 + markdown） | 6 | golden 全绿 |
| 2 | 输入区 + 顶栏 | 4 | golden 全绿 |
| 3 | 抽屉 + 模型切换 | 5 | golden 全绿 |
| 4 | 图片工作区 | 12 | golden 全绿 |
| 5 | modelhub + settings + agent | 全部 | golden 全绿 |

**验收**：每批次后 golden 全绿；`fontSize =` 计数单调下降至 0（白名单除外）。

**收益**：统一字号后，全应用文字节奏第一次一致。**最大单点质量跃升。**

**回滚**：逐批 revert。**风险**：**高**（73 处，视觉回归风险）。**缓解**：golden 测试是唯一可靠手段。

#### W4-2 间距收敛 4dp 网格【P1】

**问题**：`feature/chat` 内 **27 个不同 padding dp 值**。三条最重要横向边距不一致：

| 面 | 横向 | 位置 |
|---|---|---|
| 消息列表 | `18.dp` | `ChatMessageListSection.kt:63` |
| 顶栏 | `18.dp` | `ChatStatusBarHeader.kt:69` |
| 输入栏外层 | `16.dp` | `ChatComposerScreens.kt:260` |
| 用户气泡内层 | `17.dp` | `ChatMessageBubbleScreens.kt:228` |

**16/17/18 同时存在于同一竖直轴上。输入栏是 16 而列表是 18，输入栏左边缘比每条消息左边缘外扩 2dp** —— 眼睛能感觉到但说不出来的那种"不齐"。

**改动**：定义 6 档，按映射迁移：

```kotlin
object McaSpacing {
    val xxs = 4.dp; val xs = 8.dp; val sm = 12.dp
    val md = 16.dp; val lg = 24.dp; val xl = 32.dp
}
```

映射：`1,2,3→4` · `5,6,7→8` · `9,10,11→12` · `13,14,15→16` · `17,18,20→16或24` · `22→24` · `26→24` · `28→32`

**首批 4 处**（收益/风险比最高）：

```kotlin
// ChatMessageListSection.kt:63   18.dp → 16.dp
// ChatMessageListSection.kt:64   top=26.dp → 24.dp
// ChatMessageListSection.kt:65   spacedBy(18.dp) → spacedBy(16.dp)
// ChatMessageBubbleScreens.kt:228  17.dp → 16.dp
```

**验收**：截图断言输入栏左边缘与消息左边缘**像素级对齐**。

**回滚**：4 行。**风险**：低。

#### W4-3 `McaMotion` + `spring` 替换用户触发 `tween`【P1】

**问题**：实测 `tween(` **64 处**，`spring(` **0 处**，命名缓动 **0 处**。

> **点发送按钮时压缩到 0.92 再弹回 1.0，你会觉得"它被按了"；用 tween 做同样的事，你会觉得"它变大变小了"。前者是物理，后者是动画 —— 这就是 Gemini 手感的来源。**

**改动**：

```kotlin
// core/designsystem/.../McaMotion.kt（新建）
object McaMotion {
    /** 按钮按压、图标切换：紧、快、轻微过冲 */
    val snappy: FloatSpringSpec = spring(dampingRatio = 0.55f, stiffness = 900f)
    /** 气泡出现、卡片展开：柔和、几乎无过冲 */
    val gentle: FloatSpringSpec = spring(dampingRatio = 0.85f, stiffness = 380f)
    /** 页面级、全屏变化：缓慢稳定 */
    val page: FloatSpringSpec   = spring(dampingRatio = 0.90f, stiffness = 220f)
    /** 无限循环专用。必须线性，否则旋转会顿。 */
    const val LOOP_MS = 1300
}
```

**替换范围（只换"用户触发"，不换"环境循环"）**：

| 位置 | 当前 | 改为 |
|---|---|---|
| `SmoothPage.kt:48` 页面滑入 | `tween(240)` | `McaMotion.page` |
| `SmoothPage.kt:67` 页面退出 | `tween(180)` | `McaMotion.page` |
| `SwipeBackPage.kt:49` 侧滑退出 | `tween(180)` | `McaMotion.page` |
| `SwipeBackPage.kt:71` 取消回弹 | `tween(160)` | `McaMotion.snappy` |
| `ChatModelSwitcherScreens.kt:222,242` 胶囊 | `fadeIn(120)+slideIn(160)` | `fadeIn(tween(140)) + slideIn(McaMotion.gentle)` |

**不动**：`AnimatedMcaAssistantMark` 的 `tween(1300, LinearEasing)`（`McaBrandMarks.kt:327`）—— 无限循环必须线性。`ChatMessageTextRenderers.kt:147` 的展开动画也正确。

**验收**：`spring(` 计数 ≥ 5；开发者选项动画时长设 0 时功能不受影响。

**回滚**：逐处恢复 `tween`。**风险**：低。

#### W4-4 修复 4 处死动画【P0，纯恢复】

**问题**：`AnimatedVisibility(visible = true)` 首次组合即 `currentState == targetState`，`enter` **永不播放**。实测 **4 处**：

| # | 位置 | 类型 |
|---|---|---|
| 1 | `ChatComposerActionMenuViews.kt:126-127` | `AnimatedVisibility(visible = true, …)` |
| 2 | `ChatComposerResearchModeViews.kt:114-115` | 同上 |
| 3 | `ChatAuxiliaryPages.kt:69-70` | `SmoothRightToLeftPage(visible = true, …)` |
| 4 | `ChatImagesWorkspaceOverlay.kt:358-359` | 同上 |

3、4 是图片编辑器子页，由 `request?.let { … }` 门控 —— "挂载"而非"切换"，正是 `AnimatedVisibility(visible = true)` 的失效模式，只是高一层。`SmoothRightToLeftPage`（`SmoothPage.kt:45-52`）内部就是 `AnimatedVisibility`，所以那里的 `tween(240)` 滑入同样死掉。

**改动（最优：改 `SmoothRightToLeftPage` 内部，一处覆盖 3/4 + 所有未来调用方）**：

```kotlin
// core/designsystem/.../McaEnterAnimation.kt（新建）
@Composable
fun McaEnterAnimationBox(
    enter: EnterTransition,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = state, enter = enter, exit = ExitTransition.None, modifier = modifier, content = content)
}
```

**验收**：仪器断言动作菜单出现时第一帧 alpha < 1.0。mutation：改回 `visible = true` → 必红。

**收益**：恢复作者已写好的动效。成本极低。**回滚**：1 行。**风险**：无。

#### W4-5 骨架屏【P2】

**问题**：骨架屏 **0**。加载态全是 `CircularProgressIndicator`（**11 处使用**）或 `LinearProgressIndicator`（**25 处使用**）。转圈没有信息量。

> **V6 的编译错误**：V6 P1-2 的示例代码**缺 `Box` import**（import 列表无 `androidx.compose.foundation.layout.Box`，函数体用了 `Box`）。V7 修正。

```kotlin
// core/designsystem/.../McaSkeleton.kt（新建）
import androidx.compose.foundation.layout.Box   // ← V6 漏掉的这一行

@Composable
fun McaSkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    widthFraction: Float = 1f,
) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val alpha by t.animateFloat(
        initialValue = 0.30f, targetValue = 0.72f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}

/** 三行文本骨架，末行短，比例贴近真实助手消息。 */
@Composable
fun McaMessageSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        McaSkeletonBlock(height = 14.dp)
        McaSkeletonBlock(height = 14.dp)
        McaSkeletonBlock(height = 14.dp, widthFraction = 0.62f)
    }
}
```

> **`widthFraction` 的真实语义**（V6 的解释是错的）：`Modifier.fillMaxWidth(0.62f).fillMaxWidth()` 在 Compose 里**结果是 62%**（外层先约束，内层填满外层给的约束），不是 V6 说的"内部覆盖外部"。参数化仍值得做（语义更单一），但 V6 的论证是错的。

**应用 3 处**：`AppStartupScreen`、`ChatMessageListSection`（`messages.isEmpty() && isLoadingHistory`）、`feature/modelhub` 加载态。

**回滚**：恢复转圈。**风险**：无。

#### W4-6 空态重构【P2】

**问题**：`EmptyChatPanel`（`ChatBackgroundScreens.kt:339-373`）是 `Surface(8.dp)` + 标题 + 三个全宽按钮。这是"设置面板"结构，但空态应该是"对话的邀请"。

> **核心差异**：旗舰空态给的是**内容和话题**，你给的是**配置动作**。

```
        [McaLogoMark 56.dp]              ← 已有品牌标记，从 24 放大到 56
              MCA                        headlineSmall
      一个问题，从这里开始                bodyMedium / onSurfaceVariant

   ┌──────────────┐ ┌──────────────┐
   │ 帮我总结这段   │ │ 翻译成英文     │    ← 建议 chip: Surface(CircleShape, surfaceVariant)
   └──────────────┘ └──────────────┘
   ┌──────────────┐ ┌──────────────┐
   │ 分析这张图     │ │ 写一段代码     │
   └──────────────┘ └──────────────┘

     选择推荐模型    导入已有模型          ← 降级为 TextButton
```

**零新色**。**收益**：唯一能放大品牌资产的屏。**回滚**：恢复原组件。**风险**：低。

#### W4-7 启动页【P2，依赖 W3-2】

**问题**：`AppStartupScreen.kt` 46 行纯文本 + `CircularProgressIndicator`（L28）。

**改动**：`McaLogoMark(64.dp)` + 品牌名 + `McaMessageSkeleton` 替代转圈 + `McaEnterAnimationBox` 入场。

> **前提**：必须先完成 W3-2，否则启动页再漂亮也会被深色白闪毁掉。

**回滚**：恢复 46 行版本。**风险**：低。

### W5 触感与微交互（3–4 天）

#### W5-1 触觉反馈【P0，感知收益/行数比最高】

**问题**：`HapticFeedback` **0 处**。

**改动**（V6 P1-3 **完全正确，原样采用**）：

```kotlin
// core/designsystem/.../McaHaptics.kt（新建）
class McaHaptics internal constructor(private val view: View) {
    private fun fire(constant: Int) {
        if (!view.isAttachedToWindow) return      // 未 attach 时静默失败，提前挡掉
        view.performHapticFeedback(constant)      // 不传 FLAG_IGNORE_VIEW_SETTING：尊重系统开关
    }
    fun tap() = fire(HapticFeedbackConstants.KEYBOARD_TAP)
    fun tick() = fire(HapticFeedbackConstants.CLOCK_TICK)
    fun press() = fire(HapticFeedbackConstants.LONG_PRESS)
    fun confirm() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP    // CONFIRM 自 API 30 引入，minSdk=26 必须降级
    )
}
```

**接入 5 个触点**：发送（`tap`）、停止（`press`）、长按消息（`press`）、切换模型/底栏 tab（`tick`）、生成完成（`confirm`）。

**不要滥加**：滚动、每输入一字符、列表项点击都不加。触觉是稀缺资源。

**验收**：仪器断言 5 个触点 `performHapticFeedback` 被调用（mock View）；API 26 上 `confirm()` 不崩溃。

**回滚**：删文件 + 删 5 行调用。**风险**：无。

#### W5-2 发送按钮物理化【P1】

```kotlin
val interaction = remember { MutableInteractionSource() }
val pressed by interaction.collectIsPressedAsState()
val scale by animateFloatAsState(if (pressed) 0.92f else 1f, McaMotion.snappy, label = "sendScale")

FloatingActionButton(
    onClick = { haptics.tap(); ... },
    interactionSource = interaction,
    modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale },
    containerColor = /* W3-4 的深色修正 */,
) {
    AnimatedContent(targetState = isGenerating, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }) { generating ->
        Icon(if (generating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, ...)
    }
}
```

**验收**：手动测试按压有缩放；截图比对发送↔停止不是硬切。**风险**：低。

#### W5-3 滚动意图感知 + 回到最新【P0，修 V6 的符号错误】

**问题**：`ChatScreen.kt:251-258` 硬 `scrollToItem`，无 `isScrollInProgress`，无"回到最新"。

三处叠加：
1. `scrollToItem` 瞬时跳，列表"突兀弹一下"
2. 无意图判断 —— **用户在生成中向上翻看历史，下一条 token 到达时被强行拉回底部**。这是聊天 App 最令人反感的体验缺陷
3. 无"回到最新"按钮

> **V6 的错误**：V6 第 386 行写 `animateScrollToItem(target, scrollOffset = -bottomChromeHeightPx)`。**符号反了。** Compose 的 `scrollOffset` 语义是"正向滚动"——**正值**把目标 item 往**上**推。要让最后一条停在输入栏上方，应该用**正值**。负值会把 item 往下推，正好重现它想修的 bug。

**改动**：

```kotlin
// feature/chat/.../ChatAutoScroll.kt（新建）
@Composable
internal fun rememberShouldFollowLatest(listState: LazyListState): Boolean {
    val shouldFollow by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }
    return shouldFollow
}
```

```kotlin
// ChatScreen.kt:250-257 改为
val shouldFollowLatest = rememberShouldFollowLatest(listState)
LaunchedEffect(state.messages.size, state.isGenerating, scrollSignal,
    state.browserTask?.taskId, state.browserTask?.navigationId, browserSearchCardVisible) {
    if (!shouldFollowLatest) return@LaunchedEffect   // 用户在读历史，不要打断
    val target = if (browserSearchCardVisible) {
        state.messages.size + if (state.messages.isEmpty()) 1 else 0
    } else state.messages.lastIndex
    if (target < 0) return@LaunchedEffect
    // 正值：把目标 item 往上推，使其停在输入栏上方
    listState.animateScrollToItem(target, scrollOffset = bottomChromeHeightPx.roundToPx())
}
```

**「回到最新」悬浮按钮**：`ChatMessageListSection` 的 Box 内，`!shouldFollowLatest && messages.isNotEmpty()` 时显示 40dp 圆形 `Surface(shadowElevation = 2.dp)`，点击 `animateScrollToItem(lastIndex)`。

**验收（可失败）**：
1. 生成中向上滚到顶部，等 3 次 token，断言 `firstVisibleItemIndex` 未变。mutation：去掉 `shouldFollowLatest` → 必红
2. 点击"回到最新"，断言 `firstVisibleItemIndex == lastIndex`
3. 断言最后一条消息底边 ≤ 输入栏顶边（证明 offset 符号正确）

**回滚**：恢复 `scrollToItem`。**风险**：低。

#### W5-4 图片内存缓存【P1，修正 V6 的预算】

**问题**：`rememberChatImageBitmap`（`ImageAssetIoSupport.kt:59-73`）用 `produceState`，生命周期绑定 composable。滚出 → Bitmap 回收 → 滚回 → **重新 openInputStream + 两次 decode**。全项目 `LruCache` **0 处**。11 个调用点，`maxDimensionPx` 为 384/512/1280。一张 1280² ARGB_8888 = 6.5MB。

```kotlin
// feature/chat/.../ChatImageMemoryCache.kt（新建）
internal object ChatImageMemoryCache {
    // V6 用 maxMemory/8、上限 96MB —— 太激进。
    // 本项目同时跑本地大模型 + 图片生成，native 已吃掉大量内存。
    private val maxBytes: Int = (Runtime.getRuntime().maxMemory() / 16L)
        .coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024)
        .toInt()

    internal data class Entry(val versionStamp: String?, val bitmap: Bitmap)
    private val cache = object : LruCache<String, Entry>(maxBytes) {
        override fun sizeOf(key: String, value: Entry): Int = value.bitmap.byteCount.coerceAtLeast(1)
    }

    fun key(uriString: String, maxDimensionPx: Int?): String = "$uriString@${maxDimensionPx ?: 0}"

    /** 纯内存读取，零系统调用。可在组合阶段安全调用。 */
    fun peek(uriString: String, maxDimensionPx: Int?): Bitmap? =
        synchronized(cache) { cache.get(key(uriString, maxDimensionPx))?.bitmap }

    fun put(uriString: String, maxDimensionPx: Int?, stamp: String?, bitmap: Bitmap) =
        synchronized(cache) { cache.put(key(uriString, maxDimensionPx), Entry(stamp, bitmap)) }

    fun invalidate(uriString: String) = synchronized(cache) {
        cache.snapshot().keys.filter { it.startsWith("$uriString@") }.forEach(cache::remove)
    }

    /** 系统内存压力时清空。挂在 ComponentCallbacks2.onTrimMemory。 */
    fun trimToSize(sizeBytes: Int) = synchronized(cache) { cache.trimToSize(sizeBytes) }
    fun evictAll() = synchronized(cache) { cache.evictAll() }
}
```

`rememberChatImageBitmap` 改造（命中即返回，不做任何比对、不发起任何 I/O）：

```kotlin
@Composable
internal fun rememberChatImageBitmap(
    context: Context, uriString: String,
    maxDimensionPx: Int? = null, revisionKey: Any? = null,
): State<Bitmap?> {
    val state = remember(uriString, maxDimensionPx, revisionKey) {
        mutableStateOf(ChatImageMemoryCache.peek(uriString, maxDimensionPx))
    }
    LaunchedEffect(uriString, maxDimensionPx, revisionKey) {
        if (ChatImageMemoryCache.peek(uriString, maxDimensionPx) != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            loadImageBitmap(context.applicationContext, uriString, maxDimensionPx)
        }
        if (decoded != null) ChatImageMemoryCache.put(uriString, maxDimensionPx, null, decoded)
        state.value = decoded
    }
    return state
}
```

**版本戳的定位（V6 判断正确，保留）**：`revisionKey` 负责 App 内失效；`resolveVersionStamp` 只用于**主动失效检查点**（回到前台、图片编辑完成），**不参与每次命中校验**。命中后仍 `contentResolver.query` 会让滚动列表每个可见图片每次组合都触发跨进程 Binder 调用，比没有缓存更糟。

**V6 需修正两点**：
1. **预算**：`maxMemory/8` + 96MB → `maxMemory/16` + 48MB（本项目内存竞争激烈）
2. **缺 `onTrimMemory`**：必须有，否则系统内存压力时 LruCache 不让路

**验收（可失败）**：
1. 滚出滚回同一图，`decodeStream` 调用次数不增加
2. 单测：`trimToSize(0)` 后 `peek` 返回 null
3. 图片会话滚动 20 次后 Java heap 稳定不增长

**回滚**：恢复 `produceState` 原实现。**风险**：低（用降级预算后）。

#### W5-5 列表 key 去 index【P0，测试必须重写】

**问题**：`ChatMessageListSection.kt:81`：

```kotlin
key = { index, message -> "${message.role.name}:${message.createdAt}:$index" }
```

`LazyList` 的 key 契约是"同一内容的稳定标识"。index 参与 key，插入/删除/分页边界变化时 index 整体移位，所有下游 item 的 key 全部失效 → Compose 丢弃并重建每一行 → 丢失滚动位置、已解码 Bitmap、展开/折叠状态。

`ChatMessage.id` 是理想 key：`ChatMessageTypes.kt:23` 已是 `val id: String = UUID.randomUUID().toString()`，构造后不变，且**已持久化**（`ChatSessionPersistenceMapping.kt:302` `messageId = id`；`ChatSessionStore.kt:409-410` 读取时回落为 `"$sessionId:$index:${createdAt}"`）。

**改动**：

```kotlin
key = { _, message -> message.id }
```

**V6 的测试不可失败，必须重写**：V6 第 100 行说"构造 100 条消息 → 删除中间第 50 条 → 断言剩余 99 条的 id 集合不变"。这个断言**在任何实现下都成立**（`id` 是 `val`，删一条不改其它 id）。它验证的是 Kotlin 语言事实，不是 key 稳定性。

**正确的可失败测试**：

```kotlin
internal fun chatMessageListKey(index: Int, message: ChatMessage): Any = message.id

@Test
fun `list key is stable across index shifts`() {
    val m = ChatMessage(Role.USER, "hi")
    // mutation：把 index 加回 key，此断言必须变红
    assertEquals(chatMessageListKey(0, m), chatMessageListKey(5, m))
}
```

**另一个 V6 完全没发现的潜在 bug**：`onDelete = { onDeleteMessage(index) }`（`ChatMessageListSection.kt:159`）用 **index** 删除消息，而 key 是 id。两者语义不一致 —— 若列表在 `onDelete` 触发与 ViewModel 处理之间发生插入/删除，会**删错消息**。

正确做法：`onDeleteMessage` 改为接收 `messageId: String`，在 ViewModel 内按 id 查找。

**验收**：
1. `chatMessageListKey` 稳定性单测（可失败）
2. 删除中间消息后，断言剩余消息的 **key** 集合不变（不是 id 集合）
3. 仪器：删除中间消息，Layout Inspector 显示只有被删行消失

**回滚**：key 一行；onDelete 签名单独回滚。**风险**：低（key），中（onDelete 改签名 —— 会触及源码断言测试，见 §6.1）。

#### W5-6 触控目标补到 44–48dp【P1】

**问题**：`IconButton` < 44dp 实测 **14 处**：

| 控件 | 尺寸 | 位置 |
|---|---|---|
| 历史行溢出菜单 | **26.dp** | `ChatHistoryDrawerRows.kt:156` |
| 输入栏生图模式关闭 | **28.dp** | `ChatComposerScreens.kt:320` |
| 消息复制/重生成/更多 | **32.dp** ×3 | `ChatAssistantActionViews.kt:110,122,134` |
| 用户气泡复制/固定 | **32.dp** ×2 | `ChatMessageBubbleScreens.kt:287,297` |
| 助手 pin（折叠态） | **32.dp** | `ChatBubbleMessageBlocks.kt:265` |

Android 无障碍下限 48dp。`IconButton` 默认就是 48dp，这些是**主动改小**的。

**改动**：`Modifier.size(26.dp)` → `Modifier.size(48.dp)`，图标尺寸保持原值。视觉不变，可点区域变大。

**验收**：仪器遍历所有 `IconButton`，断言 `bounds.width >= 44dp`。**风险**：低（但会挤压布局，需逐屏检查）。

#### W5-7 文字选择与流式光标【P2】

**问题**：
- `LocalTextSelectionColors` **0 处** → 长按选中是 M3 默认**紫色**
- 助手文本有 `SelectionContainer`（`ChatBubbleMessageBlocks.kt:183,196`），**用户气泡没有** → 用户想复制自己发过的话只能重新打字
- 无流式光标

```kotlin
// McaTheme.kt 包一层
CompositionLocalProvider(
    LocalTextSelectionColors provides TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
    )
) { content() }
```

用户气泡加 `SelectionContainer`。流式光标：最后一条消息末尾加 2dp×16dp `Box`，`infiniteRepeatable(tween(900), RepeatMode.Reverse)` alpha 0.35↔1.0。

**验收**：截图比对选中色为蓝色；仪器断言用户气泡文本可选。**风险**：低。

### W6 启动与门禁（4–5 天）

#### W6-1 Baseline Profile【P1】

**问题**：全项目 **0 处**。Android 冷启动优化中投入产出比最高（AOT 编译热点路径，典型收益 20-40% JIT 时间）。

**改动**：新增 `:baselineprofile` 模块（`com.android.test`），用 Gradle Managed Device 生成，覆盖：冷启动 → 首屏 → 打开会话 → 滚动 → 发送消息。

**需要新增依赖**（catalog 里没有，实测 `baseline`/`profileinstaller`/`tracing` 命中 0）：`androidx.benchmark`、`androidx.profileinstaller`。

**验收**：`S1_coldstart` 的 TTID 改善可量化（与 Phase 0 对比）。**回滚**：删模块。**风险**：低。

#### W6-2 CI 性能门禁【P1，决定长期有效性】

**问题**：没有门禁的优化，三个月后被下一次重构悄悄抵消。这是"自己做的"和"大厂做的"在**流程**上的差别。

| 门禁 | 阈值 | 频次 |
|---|---|---|
| `S2_streaming` P90 帧时长 | ≤ 16.7ms | release PR |
| `S2_streaming` P99 帧时长 | ≤ 33ms | release PR |
| 非最后一条消息重组次数 | == 0 | 每个 PR（快检查） |
| `S1_coldstart` TTID | ≤ 基线 110% | release PR |
| `skippable` 比例（消息链路） | == 100% | 每个 PR（快检查） |

**验收**：故意引入回归（去掉 `remember`），断言 CI 变红。**风险**：中（CI 时间）。**缓解**：完整套只跑 release PR，普通 PR 只跑两个快检查。

#### W6-3 启动路径裁剪【P2，可选】

**问题**：`AppStartupViewModel.kt:74-89` 在 IO 里构造 `McaAppContainer` + `migratePendingReferences()` + `installationScopeId()`，然后才发布 `MainViewModel`。用户看到 `AppStartupScreen` 直到全部完成。

> **V6 的错误**：V6 P0-5 说 `MainViewModel` 的 `runBlocking` 造成"主线程首帧卡顿"。**这是错的** —— 构造发生在 `Dispatchers.IO`（`AppStartupViewModel.kt:74`），阻塞的是 IO 线程，不造成主线程卡顿。V6 的收益归因是编造的。

**真正可做**：把 `migratePendingReferences()` 与 `installationScopeId()` 改成**懒加载**，让 `MainViewModel` 先发布、UI 先可交互，这两项后台完成后再补。

**验收**：`S1_coldstart` 的 TTFD 改善可量化。**风险**：中（跨文件追踪，使用方要处理未就绪状态）。**建议最后做，或不做。**

### W7 无障碍与韧性（2–3 天）

#### W7-1 对比度修正（零视觉风险，纯删 alpha）

| 位置 | 改动 | 改后 |
|---|---|---|
| `ChatHistoryDrawerRows.kt:51` | `onSurfaceVariant.copy(alpha=0.72f)` → `onSurfaceVariant` | 3.16:1 → 5.74:1 |
| `ChatHistoryDrawerRows.kt:150` | 同上 | 同上 |
| `ChatReasoningPerformanceScreens.kt:126` | `.copy(alpha=0.74f)` → 原值 | 3.29:1 → 5.74:1 |
| `ChatReasoningPerformanceScreens.kt:315` | `.copy(alpha=0.82f)` → 原值 | 3.87:1 → 5.74:1 |
| `ChatMessageBubbleScreens.kt:303` | `.copy(alpha=0.78f)` → 原值 | 3.71:1 → 6.05:1 |
| `ChatAssistantActionViews.kt:128,140` | `.copy(alpha=0.78f)` → 原值 | 3.71:1 → 6.05:1 |
| `ChatStatusBarHeader.kt:128` | `.copy(alpha=0.78f)` → 原值 | 或删掉该行 |

> `onSurfaceVariant` **本身就是次级文字角色**。再乘 0.72–0.82 会让它跌出 AA。代码里已有注释发现了这一点（`ChatReasoningPerformanceScreens.kt:387-389`），但修复只应用到了 `GenerationPerformanceLine`，没推广开。

#### W7-2 RTL 布局韧性

1. 未镜像的图标改为 `Icons.AutoMirrored.*`（`Menu`、`Add`、`KeyboardArrowDown` 等有方向含义的）
2. 检查所有 `offset(x = …)` 在 RTL 下的行为
3. `McaLogoMark` 无需翻转（品牌标记）

**验收**：`adb shell settings put global debug.force_rtl 1` 后截图，断言无布局错乱。**低优先级**。

#### W7-3 无障碍语义补全

实测：`contentDescription =` **174 处**，`= null` **80 处**（装饰性图标应为 null，需逐个判断）；`semantics {` **15 处**，`stateDescription` **28 处**，`liveRegion` **49 处**（这块做得不错）；`heading()` **2 处**（markdown 标题没有语义）。

1. markdown 标题加 `Modifier.semantics { heading() }`（`ChatMarkdownBlockRenderers.kt:105-120`）
2. 流式区加 `liveRegion = LiveRegionMode.Polite`（已有 49 处用对了，扩展到消息区）
3. TalkBack 遍历一遍，修正读不出来的控件

**验收**：TalkBack 手动走查，无"未标记按钮"。

### W8 常驻开销治理（2 天，V6 完全未发现）

#### W8-1 设备状态轮询按需启动【P1】

**问题**：R5-b。5 秒轮询永不停，而 `ChatDeviceStatusOverlay` 在 `ChatMessageListSection.kt:221` **无条件组合**。

**改动**：

```kotlin
// ChatDeviceStatusScreens.kt:105
LaunchedEffect(context, ballVisible) {
    if (!ballVisible) return@LaunchedEffect    // 不可见就不轮询
    while (true) { /* ... */ delay(5_000L) }
}
```

或更简单：`ChatMessageListSection.kt:221` 外面包 `if (deviceStatusVisible)`。

**验收**：`dumpsys alarm` 对比，后台静止 1 分钟的唤醒次数 ≤ 基线（§2.5）。**回滚**：恢复无条件轮询。**风险**：低。

#### W8-2 无限动画按需创建【P1】

**问题**：R5-a。19 处 `rememberInfiniteTransition`。`McaBrandMarks.kt:322` 的助手环在**每次助手消息渲染时**创建 transition，即使 `isGenerating = false`。

**改动**：

```kotlin
@Composable
internal fun AnimatedMcaAssistantMark(isGenerating: Boolean, modifier: Modifier = Modifier) {
    // 关键：isGenerating = false 时不创建 transition
    if (isGenerating) {
        val t = rememberInfiniteTransition(label = "mca-mark")
        val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "mca-ring")
        AssistantMarkCanvas(angle = angle, modifier = modifier)
    } else {
        AssistantMarkCanvas(angle = 0f, modifier = modifier)   // 静态弧
    }
}
```

> **注意**：`if` 包裹 `rememberInfiniteTransition` 是合法的（Compose 允许条件组合），但 transition 销毁/重建会有一次跳变。若不能接受，改为在 `isGenerating = false` 时用 `remember { mutableStateOf(0f) }` 固定角度。

**验收**：仪器断言非生成态下 `AnimatedMcaAssistantMark` 不产生每帧重组。**回滚**：恢复无条件 transition。**风险**：低。

---

## 6. 逐屏质量规格

### 6.1 聊天主屏（`ChatScreen.kt:48`，560 行，56 处 `state.` 读取）

| 维度 | 当前 | 目标 | 工作项 |
|---|---|---|---|
| 顶栏 | 44dp 胶囊内 3 行字，200% 字号溢出 | `heightIn(min=44dp)` + 最多 2 行 | W3-5, W4-1 |
| 消息列表 | 每帧全量重组 | 仅最后一条重组 | W2-1, W2-3 |
| 输入栏 | 14dp 黑阴影；深色下按钮 4.07:1 | 2dp + 1dp 边框；深色用 primary | W3-4, W4-2 |
| 滚动 | 硬跳，无意图感知 | 动画 + 意图感知 + 回到最新 | W5-3 |
| 触觉 | 无 | 5 触点 | W5-1 |
| 空态 | 设置卡片 | Logo + 问候 + 建议 chip | W4-6 |
| 选中色 | M3 紫 | 主题蓝 | W5-7 |
| edge-to-edge | 无 | 有 | W3-1 |

### 6.2 消息气泡（`ChatMessageBubbleScreens.kt:59`，309 行，29 参数）

| 维度 | 当前 | 目标 |
|---|---|---|
| 参数 | 29 + 7 lambda | 3（model + onEvent + modifier） |
| 可跳过 | 否 | 是 |
| 用户字号 | 16sp（> 助手 15sp） | 15sp（≤ 助手） |
| 用户文字可选 | 否 | 是 |
| 复制按钮 | 32dp，17dp 图标 | 44dp，图标不变 |

### 6.3 输入栏（`ChatComposerScreens.kt:90`，477 行）

| 维度 | 当前 | 目标 |
|---|---|---|
| 阴影 | 14dp 黑 | 2dp + 1dp outline 边框 |
| 左右内边距 | 16dp（与列表 18dp 不齐） | 16dp（列表也改 16dp） |
| 发送按钮 | 静态，无按压 | 0.92 缩放 + 发送↔停止 crossfade |
| IME | `adjustResize` + `imePadding` 可能双重 | 只留 `imePadding` |

### 6.4 markdown 渲染（`ChatMarkdownBlockRenderers.kt`，340 行）

| 维度 | 当前 | 目标 |
|---|---|---|
| 正文 | 15sp/23sp ✅ 已正确 | 保持 |
| 代码块（深色） | `#1F2937` 固定 → 1.28:1 | `outlineVariant` + 边框 |
| 标题语义 | 无 `heading()` | 加 |
| 表格溢出 | 有功能渐隐 ✅ 已正确 | 保持 |
| 分页 | 16384 ✅ 已正确 | 保持 |

### 6.5 抽屉（`ChatHistoryDrawerScreens.kt:61`）

| 维度 | 当前 | 目标 |
|---|---|---|
| 选中态 | 5dp 黑阴影 | surfaceVariant 填充 |
| 溢出菜单 | 26dp | 48dp |
| 次级文字 | 3.16:1 | 5.74:1 |
| 间距 | 28dp + 34dp 任意值 | 24 + 32 |

### 6.6 图片工作区（`ChatImagesWorkspaceOverlay.kt:27`）

| 维度 | 当前 | 目标 |
|---|---|---|
| 图片解码 | 每次重解码 | 内存缓存 |
| 常驻轮询 | 5s 永不停止 | 按需 |
| 加载态 | 转圈 | 骨架 |

---

## 7. 测试体系

### 7.1 现状

| 项 | 实测 |
|---|---|
| `app/src/test` 文件 | **263** |
| 源码文本断言测试文件 | **76**（`app/src/test` + `feature/*/src/test`） |
| `app/src/androidTest` 文件 | **13** |
| `feature/chat` androidTest | **不存在** |
| screenshot/golden 框架 | **无**（`Paparazzi`/`Roborazzi` 命中 0） |
| `feature/chat` JVM 测试依赖 | 仅 `junit` + `json` |

### 7.2 必须新建的三类测试

**(1) Roborazzi golden 测试**（W4 的硬前提）

在 `feature/chat/build.gradle.kts` 加 `testImplementation("io.github.takahirom.roborazzi:roborazzi:1.9.0")` + Robolectric。给 6 个关键屏建基线：

```
chat_list_light.png / chat_list_dark.png
chat_empty_light.png / chat_empty_dark.png
chat_composer_light.png / chat_composer_dark.png
chat_message_bubble_light.png / chat_message_bubble_dark.png
chat_codeblock_light.png / chat_codeblock_dark.png
chat_drawer_light.png / chat_drawer_dark.png
```

**(2) 重组计数仪器测试**（W2 验收核心）

放 `app/src/androidTest`（`feature/chat` 无 androidTest 源集，可新建）：

```kotlin
@Test
fun streaming_doesNotRecomposeHistoricalMessages() {
    // 1. 打开 50 条消息的会话
    // 2. 发送消息，等生成开始
    // 3. 生成 500 token 期间，每 100ms snapshot 一次
    // 4. 断言：除最后一条外，所有 message.id 的 tick 数 == 0
}
```

**(3) 滚动行为仪器测试**（W5-3）

```kotlin
@Test fun userScrollIsNotYankedDuringStreaming() { ... }
@Test fun scrollToLatestButtonWorks() { ... }
@Test fun lastMessageSitsAboveComposer() { ... }   // 验证 offset 符号
```

### 7.3 源码断言测试的迁移策略

**问题**：76 个文件读源码文本做断言。任何重命名/抽取都会红。

| 类别 | 例子 | 处理 |
|---|---|---|
| 锁**行为不变量**（好） | `MainViewModelCompletionFallbackTest.kt:139-141` 断言 `flushPendingAssistantOutput` 在 `settleGenerationUi` 之后 | **保留**，同步更新字符串 |
| 锁**实现细节**（差） | 断言某函数名存在 | 改为锁行为（反射或公开 API） |
| 锁**顺序**（灰色） | `indexOf("A") > indexOf("B")` | 保留，但迁移到显式的事件序列测试 |

**执行**：W2 开始前先花半天做"源码断言审计"，列出会被 W2 触及的测试，逐个判断"改断言"还是"改设计"。

**这是 V6 完全忽略的部分，也是方案能否落地的前提。**

---

## 8. CI 门禁

### 8.1 两层门禁

**Layer 1（每个 PR，< 3 分钟）**：

```yaml
- name: compose-skippability
  run: ./gradlew :feature:chat:compileDebugKotlin -Pmca.composeMetrics && ./scripts/check-skippable.sh
  # 断言消息链路 skippable == 100%

- name: recomposition-budget
  run: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=RecompositionBudgetTest
```

**Layer 2（release PR / main，~25 分钟）**：

```yaml
- name: macrobenchmark
  run: ./gradlew :benchmark:connectedBenchmarkAndroidTest
  # 断言 P90 ≤ 16.7ms, P99 ≤ 33ms, TTID ≤ baseline*1.10
```

### 8.2 防抵消机制

**季度回归检查**：每季度跑一次 Phase 0 全套，与基线比对。**如果某指标退化 > 10%，开一个专项 issue。**

这是"自己做的"和"大厂做的"在流程上的真正差别 —— **不是一次优化，而是持续不让它退化**。

---

## 9. 排期

### 9.1 阶段表

| 阶段 | 内容 | 交付 | 天数 | 可独立发布 |
|---|---|---|---|---|
| **P0** | 基线 + 埋点 + golden 基建 | 4 份 baseline 文档 | 3–4 | ✅ |
| **P1** | W3 全部 + W1 全部 + W8 | 代码 + 测试 | 5–6 | ✅ |
| **P2** | W2 全部 + W5-5/W5-4 | 代码 + Compose 报告 | 6–8 | ✅ |
| **P3** | W5 其余 + W7 | 代码 + 测试 | 4–5 | ✅ |
| **P4** | W4（含 golden 迁移） | 代码 + golden 基线 | 8–10 | ✅ |
| **P5** | W6 | 模块 + CI | 4–5 | ✅ |

**总计约 30–38 个工作日**（单人）。

### 9.2 建议执行顺序（按收益/风险重排）

**第一批：第一天就能做，当天可见差别（~80 行）**

| 序 | 项 | 行数 |
|---|---|---|
| 1 | W3-3 补 token | ~6 |
| 2 | W3-4 深色代码块 + 发送按钮 | ~4 |
| 3 | W3-2 `values-night` | ~12 |
| 4 | W5-1 触觉 | ~40 + 5 调用 |
| 5 | W4-4 修 4 处死动画 | ~10 |
| 6 | W4-2 首批 4 处间距 | ~4 |

这 6 项零架构风险，覆盖 W3 全部 + 触觉 + 动效 + 对齐。

**第二批：收益最大的两项**

| 序 | 项 |
|---|---|
| 7 | W1-1 `remember` 包住 `buildChatUiState`（含 W1-2、W1-3） |
| 8 | W1-4 `remember` 包住 `mainChatScreenActions` |

**第三批：解锁性改动**

| 序 | 项 |
|---|---|
| 9 | W2-3 `ChatScreen` 顶层读取下沉 |
| 10 | W2-1 `MessageBubble` 参数收窄 + 单入口事件 |

**之后**：W2-2/W2-4 → W5 其余 → W8 → W4 → W6 → W7。

### 9.3 提交纪律

- 每项**一个独立 commit**，message 带"改前/改后"测量数字
- 每项必须带**可失败测试**（mutation 后必红），否则不算完成
- 触及源码断言测试的项（W5-5 的 `onDelete` 改签名、W2-1），**单独 PR**
- 每项在 PR 描述里写清**回滚方式**

---

## 10. 验收标准

**以下 14 条全部满足，才算 S 级。** 少一条都不是。

### 性能（6 条）

1. ✅ 生成期间 P90 帧时长 ≤ 16.7ms（Macrobenchmark，与 Phase 0 对比）
2. ✅ 生成期间 P99 帧时长 ≤ 33ms
3. ✅ 流式 500 token，非最后一条消息重组次数 **== 0**（埋点实测）
4. ✅ 消息链路 `skippable` 比例 == 100%（Compose Compiler 报告）
5. ✅ 图片会话滚动 20 次 Java heap 稳定；`decodeStream` 不重复
6. ✅ 后台静止 1 分钟唤醒次数 ≤ 基线

### 正确性（3 条）

7. ✅ API 35/36 上 edge-to-edge 正确，顶栏不被状态栏压住（仪器断言坐标）
8. ✅ 深色代码块有可辨边界，发送按钮 ≥ 4.5:1（单测断言）
9. ✅ `outlineVariant` 不落 M3 默认（单测断言）

### 体验（4 条）

10. ✅ 生成中向上滚动不被拉回（仪器断言 `firstVisibleItemIndex` 不变）
11. ✅ 5 触点有触觉；发送按钮有按压缩放；4 处死动画复活
12. ✅ 所有 `IconButton` 可点区域 ≥ 44dp（仪器遍历断言）
13. ✅ 200% 字号下无文字裁切

### 流程（1 条）

14. ✅ CI 有 5 个性能门禁，故意引入回归能让 CI 变红

---

## 11. 风险登记册

| # | 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|---|
| RK1 | `remember` 漏 key → 读到旧值 | **高**（隐蔽 UI 不更新） | 中 | 收窄入参（编译期保护）+ W1-1 的 4 条测试 |
| RK2 | stability config 错误声明 → UI 不更新 | 高 | 中 | 优先 config 而非 `@Immutable`；验证深层字段变更能刷新 |
| RK3 | W4-1 批量改字号 → 视觉回归 | 中 | **高** | Roborazzi golden 是硬前提；5 批次推进 |
| RK4 | W2-1 触及 76 个源码断言测试 | 中 | **高** | 先做源码断言审计；同步更新断言；单独 PR |
| RK5 | edge-to-edge 改动 → 布局跳动 | 中 | 中 | 先加 inset 测试；逐页视觉检查 |
| RK6 | 图片缓存预算过高 → OOM | **高**（崩溃） | 中 | `maxMemory/16` + 48MB + `onTrimMemory` |
| RK7 | 并发修改 → 行号漂移 | 低 | **高**（已发生） | 每项开工前重核对行号 |
| RK8 | CI 性能测试 flaky | 中 | 中 | P90 而非均值；同设备同变体；≥10 次 |
| RK9 | `onDelete` 改签名 → 波及多处 | 中 | 中 | 单独 PR；保留旧方法名过渡 |

---

## 附录 A：V6 逐项判决

| V6 项 | 判决 | 理由 |
|---|---|---|
| **P0-1** key 去 index | ✅ 采纳，**测试重写** | 结论对；测试恒真（§W5-5）；另发现 `onDelete(index)` 潜在 bug |
| **P0-2** derivedStateOf | ❌ **简化** | 该处块内读普通 data class 字段，非 snapshot State，追踪不到依赖，等同 `remember`。V6 解释错误 |
| **P0-3** 图片缓存 | ✅ 采纳，**降预算 + 加 `onTrimMemory`** | 诊断与版本戳分析正确；`maxMemory/8`+96MB 内存竞争下过激 |
| **P0-4** 滚动 | ✅ 采纳，**修正 offset 符号** | 方向对；`scrollOffset` 应为**正**值 |
| **P0-5** runBlocking | ❌ **不做** | 前提错误：`MainViewModel` 在 `Dispatchers.IO` 构造（`AppStartupViewModel.kt:74`），不造成主线程卡顿 |
| **P1-1** 动效 | ✅ 全采纳，**补 4 处死动画** | 正确；V6 只找到 2 处，实际 4 处 |
| **P1-2** 骨架屏 | ✅ 采纳，**补 `Box` import** | 正确；示例缺 import 编译不过；"参数化 widthFraction" 修不存在的问题 |
| **P1-3** 触觉 | ✅ **原样采纳** | **全文最好的一节**。API 30 判断、去 flag、语义选择全对 |
| **P1-4** 空态 | ✅ 采纳 | 正确 |
| **P1-5** 启动页 | ✅ 采纳，**必须先做 W3-2** | 正确；深色白闪不修，启动页再好也被毁 |
| **P2-1** token 收口 | ✅ 采纳，**补 `McaTypography` 定义** | 方向对；`McaTypography` 全文未定义 |
| **P2-3** Baseline Profile | ✅ 采纳 | 正确；需新建模块 + 新增依赖 |
| **第四批** 单次切分 | ❌ **不做** | 立论前提反了：`safePrefix` 在 >16384 字符才截断，长回答时快路径永不命中 |

**V6 六处硬伤**：

1. P1-2 示例缺 `Box` import → 编译失败
2. P2-1 `McaTypography` 从未定义 → 编译失败
3. P0-5 前提错误（不在主线程）→ 收益编造
4. P0-4 `scrollOffset` 符号反了 → 重现 bug
5. P0-1 验证测试恒真 → 违反自己的规矩
6. P0-2 `derivedStateOf` 解释错误 → 代码冗余

---

## 附录 B：实测数字全表

**扫描范围**：`core/**`、`feature/**`、`app/src/main/**`，`.kt` only，排除 `\\build\\`、`\\src\\test\\`、`\\src\\androidTest\\`。**974 个文件。**

### B.1 Compose 原语

| 指标 | 实测 |
|---|---|
| `tween(` | **64** |
| `spring(` | **0** |
| 命名缓动（`FastOutSlowInEasing` 等） | **0** |
| `AnimatedVisibility(` | **12**（其中 **4** 处 `visible = true`） |
| `AnimatedContent(` / `Crossfade(` / `animateContentSize` | **0 / 0 / 0** |
| `animateItem` | **0** |
| `rememberInfiniteTransition` / `infiniteRepeatable` | **19 / 20** |
| `derivedStateOf` | **0** |
| `animateScrollToItem` / `scrollToItem` | **0 / 2** |
| `isScrollInProgress` | **0** |
| `animateFloatAsState` / `animateDpAsState` / `animateColorAsState` | **2 / 0 / 0** |
| `graphicsLayer` | **3** |
| `HapticFeedback` | **0** |
| `ripple(` | **0** |
| `OverscrollEffect` | **0** |
| `@Immutable` / `@Stable` | **2** |

### B.2 排版与间距

| 指标 | 实测 |
|---|---|
| `fontSize =` | **73**（**18** 个不同值） |
| `lineHeight =` | **47**（16 个不同值） |
| `letterSpacing` | **0** |
| `Typography(` | **0** |
| `FontWeight.Bold` / `SemiBold` / `Medium` / `Black` | 97 / 54 / 35 / 2 |
| `feature/chat` 不同 padding dp | **27** |
| `shadowElevation` 站点 / 不同值 | **22 / 10**（0,2,5,6,7,8,10,12,14,18） |
| `tonalElevation` 站点 | **11** |
| `RoundedCornerShape(` / 不同圆角 | **162 / 15** |
| `CircleShape` | **76** |
| `BorderStroke(` | **23** |
| `outlineVariant` | **23**（`feature/chat` 17） |
| 固定 `.height(Xdp)`（feature/chat） | **44** |
| `maxLines = 1`（feature/chat） | **63** |

### B.3 状态与结构

| 指标 | 实测 |
|---|---|
| `ChatUiState` / `MainUiState` 字段 | **159 / 144** |
| `ChatScreenActions` 字段 | **96**（5 个嵌套 data class） |
| `MessageBubble` 参数 | **29**（7 个内联 lambda） |
| 整体接收 `ChatUiState` 的 composable | **13** |
| `ChatScreen.kt` 的 `state.` 读取 | **56** |
| `ChatMessageListSection.kt` 的 `state.` 读取 | **28** |
| `CHAT_STREAM_PUBLISH_INTERVAL_MS` | **75**（13 次/秒） |
| `MESSAGE_RENDER_PAGE_CHARS` | **16_384** |
| `STREAMING_SCROLL_CHAR_STEP` | **512** |
| `feature/chat` `@Composable` 总数 | **188** |
| `feature/chat/src/main` 行数 | **35_649** |
| `app/src/main` 行数 | **118_844** |

### B.4 系统边界

| 指标 | 实测 |
|---|---|
| `enableEdgeToEdge` | **0** |
| `setDecorFitsSystemWindows` | **0** |
| `statusBarsPadding` / `systemBarsPadding` / `safeDrawing` | **0 / 0 / 0** |
| `WindowSizeClass` | **0** |
| `fontScale` 处理 | **0** |
| `LayoutDirection` / `isRtl` | **0 / 0** |
| `LocalTextSelectionColors` / `TextSelectionColors` | **0 / 0** |
| `values-night/` 目录 | **不存在** |
| `styles.xml` parent | `Theme.Material.Light.NoActionBar`（写死 Light） |

### B.5 测试与构建

| 指标 | 实测 |
|---|---|
| `app/src/test` 文件 | **263** |
| 源码文本断言测试文件 | **76** |
| `app/src/androidTest` 文件 | **13** |
| `feature/chat` androidTest | **不存在** |
| screenshot/golden 框架 | **无** |
| 模块数 | **17** |
| `:benchmark` / `:baselineprofile` | **不存在** |
| `composeCompiler {}` 配置 | **不存在** |
| `stabilityConfigurationFile` | **不存在** |
| Kotlin / AGP / Compose BOM | **2.3.21 / 8.13.2 / 2026.05.01** |
| minSdk / targetSdk / compileSdk | **26 / 36 / 36** |
| release `isMinifyEnabled` / `isShrinkResources` | **true / true** |

### B.6 进度指示器（计数口径）

| 口径 | `CircularProgressIndicator` | `LinearProgressIndicator` |
|---|---|---|
| 全仓使用（带括号） | **11** | **25** |
| 全仓任意提及 | 39 | 62 |
| `feature/chat` 使用 | 9 | 13 |

> **口径说明**：带括号的"使用"计数才是真实渲染次数；"任意提及"包含 import、注释、声明，会高估 3–4 倍。

### B.7 方法说明

所有计数用**文件系统扫描**（非 `git grep`），并已交叉验证两者对本仓库返回**完全相同**结果：

```
git ls-files feature/chat/src/main        → 151（= filesystem）
git ls-files 'feature/chat/src/main/**/*.kt' → 142（= filesystem .kt）
git ls-files --others --exclude-standard feature/chat → 0
```

**本仓库不存在 `git grep` 少报问题。** 若有文档声称存在，那是错误的。

---

## 附录 C：本方案引用的全部文件

**core/designsystem**：`McaTheme.kt`、`SmoothPage.kt`、`BackMotion.kt`

**core/engine**：`ChatMessageTypes.kt`

**feature/chat**：`ChatScreen.kt`、`ChatRoute.kt`、`ChatViewModel.kt`、`ChatScreenActions.kt`、`ChatScreenHeadTypes.kt`、`ChatMessageListSection.kt`、`ChatMessageBubbleScreens.kt`、`ChatBubbleMessageBlocks.kt`、`ChatComposerScreens.kt`、`ChatComposerActionMenuViews.kt`、`ChatComposerResearchModeViews.kt`、`ChatMarkdownBlockRenderers.kt`、`ChatMarkdownRenderSupport.kt`、`ChatMarkdownBlockParser.kt`、`ChatMessageTextRenderers.kt`、`ChatReasoningPerformanceScreens.kt`、`ChatAssistantActionViews.kt`、`ChatStatusBarHeader.kt`、`ChatHistoryDrawerScreens.kt`、`ChatHistoryDrawerRows.kt`、`ChatModelSwitcherScreens.kt`、`ModelSwitcherChoicePill.kt`、`McaBrandMarks.kt`、`ChatBackgroundScreens.kt`、`ChatDeviceStatusScreens.kt`、`ChatDeviceStatusSampling.kt`、`ChatDeviceStatusRows.kt`、`ChatAuxiliaryPages.kt`、`ChatImagesWorkspaceOverlay.kt`、`ImageAssetIoSupport.kt`、`ImageGenerationResultScreens.kt`、`ChatScreenAssistantRolePages.kt`

**app**：`MainActivity.kt`、`MainChatScreenActions.kt`、`MainUiState.kt`、`ChatUiStateMapping.kt`、`ChatUiStateHistoryMapping.kt`、`ChatUiStateImageRuntimeMapping.kt`、`ChatStreamAccumulator.kt`、`MainViewModel.kt`、`AppStartupViewModel.kt`、`AppStartupScreen.kt`、`ChatSessionStore.kt`、`ChatSessionPersistenceMapping.kt`、`AssistantStore.kt`、`ImageGenerationMetadataQueries.kt`、`res/values/styles.xml`、`res/values-night/styles.xml`（待新建）

**gradle**：`libs.versions.toml`、`app/build.gradle.kts`、`feature/chat/build.gradle.kts`、`settings.gradle.kts`

---

## 结语

V6 的判断是准确的：**你的前端骨架是对的，缺的是最后一公里的技法。**

V7 想说的是：**那一公里里最贵的不是动画和骨架屏，是每帧不浪费，和每个系统边界都处理干净。**

- 动画与骨架屏：让 App **看起来**像大厂做的
- 每帧预算与系统边界：让 App **用起来**像大厂做的

前者是观感，后者是信任。旗舰级的门槛在后者。

**建议的第一步**：不要从最大的改动开始。**先花 3 天把 Phase 0 的四份基线建立起来** —— 有了数字，后面每一个决定都不再是猜测。

**如果只有一天时间**，按 §9.2 的"第一批"做那 6 项（~80 行）—— 它们零架构风险，覆盖了正确性（token、深色、启动）、触觉、动效、对齐，是"感知收益/风险比"最高的一组。
