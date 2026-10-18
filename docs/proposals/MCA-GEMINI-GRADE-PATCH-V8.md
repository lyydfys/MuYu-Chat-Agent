# MCA 旗舰级质感与性能方案 V8 —— 10 分 S 级最终版

> **对标基准**：Google Gemini Android / OpenAI ChatGPT Android 的**物理级交互手感、流式阅读呼吸感、120Hz 满帧**。
> **铁律约束**：不改色系（`McaTheme.kt` Light/Dark `ColorScheme`、`ChatColorTokens.kt` 7 token）、不改设计语言（助手全宽无气泡、用户气泡 20/6、`CircleShape` 输入栏、`McaLogoMark`）。
> **文档性质**：**只写方案，不改仓库代码。** 所有 `文件:行号` 为 2026-10-18 对 `E:\MAC` 工作区实测。
> **本文的由来**：V7 之后，另一个 AI 提交了它的 V8。本文先**逐条裁决它对 V7 的六条指控**（五条成立，我认），再**指出它自己的 11 处缺陷**（5 处编译不过 + 1 处重犯 V7 的错），最后给出**修复后的真正 10 分方案**。

---

## 目录

| 章 | 内容 |
|---|---|
| [0](#0-裁决gemini-v8-对不对) | 裁决：Gemini V8 对不对 |
| [1](#1-双方都漏掉的五个根因) | 双方都漏掉的五个根因 |
| [2](#2-phase-0测量基线) | Phase 0：测量基线 |
| [3](#3-目标架构) | 目标架构 |
| [4](#4-工作分解-w1w10) | 工作分解 W1–W10 |
| [5](#5-契约护栏) | 契约护栏 |
| [6](#6-验收标准与排期) | 验收标准与排期 |
| [附录 A](#附录-agemini-v8-逐项缺陷清单) | Gemini V8 逐项缺陷清单 |
| [附录 B](#附录-b实测数字全表) | 实测数字全表 |

---

## 0. 裁决：Gemini V8 对不对

### 0.1 它对 V7 的六条指控——五条成立，我认

| # | 指控 | 裁决 | 实测证据 |
|---|---|---|---|
| **F1** | V7 `W1-1` 的单一 `remember(state.messages, ...)` 在流式期间**每 75ms 整体失效** | ✅ **成立** | `ChatStreamAccumulator.kt:14` `CHAT_STREAM_PUBLISH_INTERVAL_MS = 75L`；`McaAppContainer.kt:322-345` 的 `store.update { state.copy(messages = updated, ...) }` 每 token 换新 `messages` 引用 → 单一 `remember` 全部 key 失效，`assistants/sessions/images` 的映射**每帧全量重跑**。V7 那句「生成期间每帧省下与 A+S+I+F 成正比的毫秒数」**是错的** |
| **F2** | V7 `W1-3` 的 `DateTimeFormatter` 缺 `ZoneId`，会抛异常 | ✅ **成立** | V7 写的 `DateTimeFormatter.ofPattern("MM-dd HH:mm", locale)` 用于格式化 `Instant` 时，无 zone 会抛 `UnsupportedTemporalTypeException: Unsupported field: MonthOfYear`。原调用点传的是 `java.util.Date(session.updatedAt)`（`ChatUiStateHistoryMapping.kt:26-27`），`DateTimeFormatter` 根本不接受 `Date` |
| **F3** | V7 `W4-5` 骨架屏在**组合阶段**读动画 alpha，每帧重组 | ✅ **成立** | `.background(color.copy(alpha = alpha))` 里 `alpha` 是 `animateFloat` 的 `State` 值，在组合期读取 → 每帧重组。必须改用 `graphicsLayer { this.alpha = alpha }` 下沉到绘制阶段 |
| **F4** | V7 `W5-3` 仍按 512 字符阶梯滚动，有顿挫 | ⚠️ **现象成立，方案有缺陷** | 现象对：`STREAMING_SCROLL_CHAR_STEP = 512`（`ChatMarkdownRenderSupport.kt:314`）决定 `LaunchedEffect` key，确实是阶梯式。但它的修复方案有 3 个缺陷（见 §0.2-⑦） |
| **F5** | 缺顶底羽化遮罩（Edge Scrim）与 `imeNestedScroll` | ⚠️ **一半成立** | `imeNestedScroll` **确实存在**（已在 gradle cache 的 `foundation-layout-1.11.2` 与 `1.7.6` 中验证到 `WindowInsetsConnection_androidKt$imeNestedScroll`）。但 EdgeScrim 用不透明 `colorScheme.background` 会**盖住用户自定义聊天背景**（`ChatScreen.kt:321` 的 `ChatBackground` 渲染用户 bitmap），这是它的缺陷（见 §0.2-⑧） |
| **F6** | V7 漏掉 `settings` / `modelhub` / `agent` 三个模块 | ✅ **成立** | 实测确有组合期 JSON 解析与无生命周期轮询（详证见 §0.1.1）。但**行号全错**（见下表），且它声称的「429 条文案」这个数字**是对的** |

#### 0.1.1 F6 的实测校正（它的行号系统性偏移 15–30 行）

| 它写的 | 实际位置 | 内容 |
|---|---|---|
| `SettingsRuntimeScreen.kt:64-71` | **`:67-73`** | `LaunchedEffect(Unit) { while (true) { systemLoad = withContext(IO){ reader.read() }; delay(1000L) } }` —— **确无生命周期感知**，退后台仍每秒读系统负载 |
| `SettingsRuntimeScreen.kt:138-153` | **`:152`** | `JSONObject(state.nativeStatsJson).optJSONObject("componentBackends")` —— **确在 `LazyColumn` 的 `item {}` 里**，每次滚动/每秒刷新都重新解析 |
| `SettingsRuntimeScreen.kt:178-180` | **`:195`** | `JSONObject(state.nativeStatsJson).optBoolean("npuExecutionProven", false)` —— 同上 |
| `SettingsLogsPrivacyScreens.kt:348` | **`:376`** | `state.nativeStatsJson.contains("\"visionReady\":true")` —— 组合期字符串扫描 |
| `SettingsSupport.kt:94` | **`:112`** | `Modifier.rotate(if (expanded) 180f else 0f)` —— 硬切，无物理过渡 |
| `SettingsSurfaceGoldenTest` 429 条 | **正确** | 实测：golden 文件 432 行、去注释后 **429** 条、与源文件实测 **429** 条完全一致，`lost=0 added=0`，测试当前**通过**（`TEST-...SettingsSurfaceGoldenTest.xml`：2 tests, 0 failures） |

> **顺带纠正我自己**：V7 附录里我写「76 个源码断言测试」时用的是 `readText()|source.contains` 这类宽匹配。精确复核后，**真正做源码文本断言的测试文件约 76 个量级是站得住的**，但其中严格按 Golden 模式的只有 `SettingsSurfaceGoldenTest`（429 条）。这个数字 Gemini 是对的。

### 0.2 它自己的 V8 有 11 处缺陷——其中 5 处编译不过

#### ① 致命：它的 `historySlice` 重犯了 V7 的错误

它批评 V7「单一 `remember` 每 75ms 失效」，然后给出：

```kotlin
// Gemini V8 §4 W1-1
val historySlice = remember(
    state.chatSessions,          // ← 这里
    state.activeChatSessionId,
    sessionPresentations,
    timestampLocale
) { projectChatHistorySlice(...) }
```

**`state.chatSessions` 在流式期间也每 75ms 换新引用。** 实测：

```kotlin
// app/src/main/java/com/muyuchat/mca/composition/McaAppContainer.kt:341-345
state.copy(
    messages = updated,
    activeChatSessionId = sessionId,
    chatSessions = state.chatSessions.upsertSessionFor(   // ← 每 token 调用
        state = state, sessionId = sessionId, messages = updated, ...
    )
)
```

```kotlin
// app/src/main/java/com/muyuchat/mca/ChatSessionPersistenceState.kt:71
return (listOf(record) + filterNot { it.id == sessionId }).sortedForHistory()   // ← 每次返回新 List
```

所以它的 `historySlice` **会和我 V7 一样每 75ms 全量重跑**。它把一个错误从 V7 的「一个 remember」转移到了「history 分支」，**没有解决根因**。

> **根因在这里，而双方都没看到**：流式 commit 同时改写了 `messages` **和** `chatSessions`。只要这个「写放大」存在，任何以 `state.chatSessions` 或 `state.messages` 为 key 的记忆化都会失效。**正确的修法不是切分 `remember`，而是先让流式 commit 不再逐 token 改写 `chatSessions`**（见 §1-R6）。

#### ② 编译失败：`ImageGenerationHistoryRecord` 不存在

```kotlin
imageGenerationHistoryById: Map<String, ImageGenerationHistoryRecord>   // ← 无此类型
```

真实类型（`ChatUiStateMapping.kt:179`）：

```kotlin
imageGenerationHistoryById: Map<String, ImageGenerationHistoryMetadata?>
```

#### ③ 编译失败：`selectedChatModelKey` 不存在

```kotlin
state.selectedChatModelKey    // ← MainUiState 无此字段
```

`MainUiState.kt` 实测只有 `selectedCloudChatModelId: String?`（L99）与 `selectedChatBackend: ChatBackend`（L102）。

#### ④ 编译失败：`AssistantImageToolPermission` 不是类型

```kotlin
assistantImageToolPermission: AssistantImageToolPermission   // ← 无此类型
```

真实类型（`ChatUiStateMapping.kt:180`）：`ChatImageToolPermissionUiState`。

#### ⑤ 编译失败：`PreparedLocalImageUi` 少了一层 Map

```kotlin
preparedImageUi: PreparedLocalImageUi                        // ← 应为 Map
```

实测（`ChatUiStateMapping.kt:181`）：`preparedImageUi: Map<String, PreparedLocalImageUi>`。

#### ⑥ 编译失败：`generationParamsForChatModel` 签名错

```kotlin
generationParamsForChatModel: (String?) -> GenerationParams   // ← 参数可空
```

实测（`ChatModelCoordinator.kt:72` + `ChatUiStateMapping.kt:182`）：`(String) -> GenerationParams`，非空。

#### ⑦ 它的平滑跟滚方案有三个缺陷

```kotlin
// Gemini V8 §4 W5-3
snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0 }
    .distinctUntilChanged()
    .filter { it > 0 }
    .collect { currentSize ->
        val delta = currentSize - lastKnownBottomItemSize
        if (lastKnownBottomItemSize > 0 && delta > 0 && !listState.isScrollInProgress) {
            listState.scrollBy(delta.toFloat())     // ← ①
        }
        lastKnownBottomItemSize = currentSize
    }
```

- **缺陷 1**：只监听「最后一个可见 item 的 size」。流式消息很高时，最后可见 item 可能不是正在增长的那条（长回答被视口截断时，最后可见的是它的局部）。**size 不变 → 不滚动**。
- **缺陷 2**：`scrollBy` 在协程里与用户的 fling 抢同一把滚动锁；`!isScrollInProgress` 只在采样瞬间判断，**采样后用户开始滑动仍会被程序滚一下**。
- **缺陷 3**：`lastKnownBottomItemSize` 用 `remember(isGenerating)` 重置，但生成结束→再生成时 delta 基准丢失，会漏掉第一段增长。

#### ⑧ 它的 EdgeScrim 会盖住用户自定义聊天背景

```kotlin
baseColor = MaterialTheme.colorScheme.background   // ← 不透明
listOf(baseColor, baseColor.copy(alpha = 0f))
```

MCA 支持**用户自定义聊天背景图**（`ChatScreen.kt:321` `ChatBackground(state = chatBackground, ...)`；`ChatBackgroundScreens.kt:71` 用 `produceState` 解码用户 bitmap 并 `Image(...ContentScale.Crop)` 铺满）。在背景图上盖一条不透明的 `#F8F9FA` 到透明的渐变，等于**在用户照片顶部糊一条白边**。

**正确做法**：Scrim 必须做成「可关闭」的，或只在**无自定义背景时**启用；更稳的做法是不做 Scrim，而是给列表加 `graphicsLayer` 的**内容裁剪留白**（见 §4-W4-8）。

#### ⑨ 它的 `compose_compiler_config.conf` 用了通配符

```conf
com.muyuchat.feature.chat.*      // ← 不支持
com.muyuchat.feature.modelhub.*
```

Compose Compiler 的稳定性配置文件**只接受完全限定类名**（一行一个），**不支持 `*` 通配符**。这些行不会报错，但**也不会有任何效果** —— 比报错更糟，因为它让人以为已经解决了。

#### ⑩ 它的 `stabilizeUnclosedSyntax` 会污染可复制文本

```kotlin
if (fenceCount % 2 != 0) return "$result\n```"     // ← 往正文里塞了闭合符
if (boldCount % 2 != 0) result = "$result**"
```

补闭合符是**为了渲染**，但它把补出来的字符**写进了用于渲染的字符串本身**。后果：
- 用户点「复制代码」时，复制的代码末尾会多一个 ```` ``` ````；
- 用户全选复制时，正文里多出 `**`；
- 每次 `remember(content)` 变化都重新拼串，`content` 与「显示内容」不再等价，**破坏了 `copyCode` 的语义**（`MarkdownBlock.Code.copyCode`）。

**正确做法**：渲染用的「稳定化文本」与「可复制原文」必须是**两个不同的值**（见 §4-W2-5）。

#### ⑪ `imeNestedScroll` 的注解位置错

它写：

```kotlin
@OptIn(ExperimentalLayoutApi::class)
LazyColumn( ... .imeNestedScroll() ... )
```

Kotlin 不允许把注解直接标在表达式上。必须标在**外层函数**上，或把 `.imeNestedScroll()` 抽成函数参数。这个 API 本身存在，方向也对，只是写法编译不过。

### 0.3 裁决汇总

| 维度 | Gemini V8 |
|---|---|
| 对 V7 的批评 | **5.5 / 6 成立**（F1/F2/F3/F6 完全对，F4/F5 现象对、方案有问题） |
| 自身代码可编译性 | **0 / 1**（5 处编译错误） |
| 根因解决度 | **未解决核心问题**（historySlice 重犯 V7 错误） |
| 跨模块覆盖 | **方向正确**（但行号全错，数值未复核） |
| 增量贡献 | **真的有价值**：`graphicsLayer` 下沉、`imeNestedScroll`、流式 Markdown 增量、跨模块收口 |
| **综合** | **7.8 / 10** —— 诊断强于 V7，但方案里的代码不能直接落地，且核心修复失效 |

**它比 V7 强的地方我完全承认**：`graphicsLayer` 下沉、70ms 节流与阶段审计、跨模块收口、429 条契约的发现。这些我 V7 确实漏了。

**它比 V7 弱的地方**：核心记忆化方案重犯同一个错；代码未经过编译器；行号未核对。

---

## 1. 双方都漏掉的五个根因

### R6【决定性】流式 commit 的「写放大」——这才是记忆化失效的真正根因

**证据链**：

```kotlin
// app/src/main/java/com/muyuchat/mca/ChatStreamAccumulator.kt:14
internal const val CHAT_STREAM_PUBLISH_INTERVAL_MS = 75L

// app/src/main/java/com/muyuchat/mca/composition/McaAppContainer.kt:322-345
commit = { batch, isStillCurrent ->
    store.update { state ->
        val updated = state.messages.toMutableList()          // ① O(n) 复制整个消息列表
        updated[index] = current.copy(content = current.content + batch.content)
        state.copy(
            messages = updated,                                // ② 新 List
            activeChatSessionId = sessionId,
            chatSessions = state.chatSessions.upsertSessionFor( // ③ 新 List + 全量排序
                state = state, sessionId = sessionId, messages = updated, ...
            )
        )
    }
}
```

`upsertSessionFor`（`ChatSessionPersistenceState.kt:31-72`）内部：

```kotlin
val record = ChatSessionRecord(
    ...
    title = if (existing?.manualTitle == true) existing.title else messages.chatTitle(),  // ④ 每 token 跑 4 次 Regex
    updatedAt = System.currentTimeMillis(),                                              // ⑤ 每 token 变
    ...
)
return (listOf(record) + filterNot { it.id == sessionId }).sortedForHistory()            // ⑥ 全量排序
```

`chatTitle()`（`ChatExportNaming.kt:33-55`）内部有 **4 处 `Regex(...)` 构造**（L35、L36、L51、L52）+ `lineSequence()` 全文扫描 + 2 次 `replace(Regex)`。

**每次流式 token（13 次/秒）都在做**：

| # | 操作 | 复杂度 | 必要性 |
|---|---|---|---|
| ① | `messages.toMutableList()` | O(消息数) | 必要 |
| ② | 新 `messages` List | O(1) 分配 | 必要 |
| ③ | `upsertSessionFor` → 新 `chatSessions` List | O(会话数) | **不必要**（UI 只在抽屉用） |
| ④ | `chatTitle()`：4 次 Regex 构造 + 全文行扫描 | O(首条用户消息长度) | **完全不必要**（标题在首条用户消息定下后永不变） |
| ⑤ | `updatedAt = now()` | O(1) | **不必要**（抽屉排序每秒变一次没人看得出） |
| ⑥ | `sortedForHistory()` 全量排序 | O(n log n) | **不必要** |

**这就是为什么 V7 和 Gemini V8 的记忆化都会失效**：不是 `remember` 切得不够细，而是**每 token 都在改写整个状态图的三个子树**。

**V8 的修法（根因修复）**：

```kotlin
// app/src/main/java/com/muyuchat/mca/composition/McaAppContainer.kt:341 附近
state.copy(
    messages = updated,
    activeChatSessionId = sessionId,
    // 只有「会话终态」或「标题首次生成」才更新 chatSessions。
    // 流式中间态不需要抽屉看到实时的 messageCount / updatedAt。
    chatSessions = if (batch.isTerminal || shouldRefreshSessionIndex) {
        state.chatSessions.upsertSessionFor(...)
    } else {
        state.chatSessions          // ← 引用不变 → 所有以它为 key 的 remember 全部命中
    }
)
```

配套：`ChatStreamAccumulator` 的 `ChatStreamOutputBatch` 已带终态信息（`GenerateEvent.Done` → `flush(generationRunId)` 带 `forcePublish`），只需在 commit 里区分「中间流水」与「终态落盘」两种调用，或在 `upsertSessionFor` 里加一个 `updateSessionIndex: Boolean` 参数。

**收益**：这一处改动让 `state.chatSessions` 在整个流式期间**引用稳定**。随后 V7 与 Gemini 都想要的「history slice 记忆化」才**真正成立**。改动量约 15 行。

> **这条是本文相对 V7 与 Gemini V8 的第一贡献。** 没有它，后面所有记忆化都是自欺。

### R7 `ChatDeviceStatusOverlay` 每帧解析 JSON（双方全漏）

**证据**：

```kotlin
// feature/chat/.../ChatMessageListSection.kt:221-224
ChatDeviceStatusOverlay(
    state = state,                    // ← 接收整个 ChatUiState
    modifier = Modifier.matchParentSize().padding(bottom = bottomChromeHeight)
)
```

```kotlin
// feature/chat/.../ChatDeviceStatusScreens.kt:125
val items = state.deviceStatusItems(memorySample, temperatureSample)   // ← 组合期调用
```

```kotlin
// feature/chat/.../ChatDeviceStatusRows.kt:106
? : runCatching { JSONObject(generationParams.advancedJson.ifBlank { "{}" }) }   // ← 组合期解析 JSON
```

**链路**：流式每 75ms 换 `ChatUiState` → `ChatDeviceStatusOverlay(state)` 参数变化 → 重组 → 组合期调 `deviceStatusItems()` → 内部 `JSONObject(generationParams.advancedJson)` **解析一次 JSON**。

**净结果：每秒 13 次 `org.json` 解析**，只为了算一个悬浮球上显示的后端名字 —— 而这个球**默认是收起状态**（`expanded = false`，`ChatDeviceStatusScreens.kt:102`）。

**V8 修法**（约 20 行）：

```kotlin
// 1) 把解析结果记忆化（advancedJson 很少变）
@Composable
internal fun rememberParsedBackend(generationParams: GenerationParams): String? =
    remember(generationParams.advancedJson) {
        runCatching { JSONObject(generationParams.advancedJson.ifBlank { "{}" }) }
            .getOrNull()
            ?.let { root -> root.optString("backend").ifBlank { root.optString("backend_type") } }
            ?.takeIf(String::isNotBlank)
    }

// 2) 悬浮球收起时不计算 items（把 L125 的 deviceStatusItems 调用搬到 expanded 分支内）
```

### R8 `ChatRoute.share` 在组合期写状态（双方全漏）

```kotlin
// feature/chat/src/main/java/com/muyuchat/feature/chat/ChatRoute.kt:65-66
val viewModel = remember { ChatViewModel(state, inputs, actions) }
viewModel.update(state = state, inputs = inputs, capabilities = actions)   // ← 组合期写
```

`ChatViewModel.update` 内部：

```kotlin
// feature/chat/src/main/java/com/muyuchat/feature/chat/ChatViewModel.kt:74-81
fun update(state: ChatUiState, inputs: ChatRouteInputs, capabilities: ChatScreenActions) {
    this.inputs = inputs
    if (this.capabilities !== capabilities) {
        this.capabilities = capabilities
        _screenActions = buildScreenActions()   // ← 组合期重建 96 个 lambda
    }
    if (_state.value !== state) _state.value = state   // ← 组合期写 StateFlow
}
```

**问题**：
1. 在 composition 阶段写 `StateFlow` —— Compose 明确禁止（`Snapshot` 写入会触发一帧内的重入）。
2. `capabilities` 每帧是**新实例**（因为 `mainChatScreenActions` 未 `remember`，见 V7 W1-4），所以 `!==` 每帧为真 → **每帧重建 96 个 lambda**。

**V8 修法**：把 `update` 包进 `SideEffect { }`，或干脆把 `ChatViewModel` 改成 `remember(state) { ... }` 的不可变持有者。彻底修法是 V7 的 W1-4（`remember` 住 actions）。

### R9 流式文本以 13.3 Hz 跳变，而屏幕是 60/120Hz（双方全漏）

**事实**：

```
CHAT_STREAM_PUBLISH_INTERVAL_MS = 75  → 13.3 Hz 文本更新
120Hz 屏 → 8.33ms/帧 → 每 9 帧文本才变一次
全仓 withFrameNanos 命中 = 0 → 没有任何帧驱动插值
```

Gemini 说的「丝滑涌出」的真正来源，不是滚动，而是**文本显现节奏**。ChatGPT 的做法是把网络到达的 token 放进缓冲，用 `withFrameNanos` **按帧**从缓冲里"吐"，让每一帧都有一点点新字符。MCA 现在是「攒 75ms → 一次性出现一大段」，所以视觉上是**跳变**。

**这一项的风险与收益**：收益是主观流畅度的最大来源；风险是引入一帧级协程循环，若写错会持续重组。**必须用 `graphicsLayer`/`drawBehind` 或独立的 `State<Int>` 只驱动单个 Text 的 recompose scope**。

> **争议点**：这一项在两个 AI 的方案里都没有，但我实测确认它是真实差距。V8 把它列为 **P2 可选**，并给出可回滚的独立实现（§4-W5-8），因为它的收益主观、成本中等，应由产品决策而非技术强推。

---

## 2. Phase 0：测量基线

**在动一行代码之前完成。没有这四份文件，W1 不得开始。**

### 2.1 `:benchmark` 模块（三场景）

`settings.gradle.kts` 现有 17 个模块，**无** `:benchmark`。新建 `com.android.test` 模块：

| ID | 序列 | 指标 |
|---|---|---|
| `S1_coldstart` | 冷启 → 首帧 → 可交互 | `StartupTimingMetric`（TTID/TTFD） |
| `S2_streaming` | 50 条消息会话 → 发送 → 500 token | `FrameTimingMetric`（P50/P90/P99 + `frameOverrunMs`） |
| `S3_scroll_images` | 图片会话滚动 20 次 | `FrameTimingMetric` + `MemoryUsageMetric` |

必须：**release 变体**（`isMinifyEnabled = true` 已在 `app/build.gradle.kts:238` 开启）、**同一物理设备**、**≥10 次迭代**。

### 2.2 Compose Compiler 报告

全仓**无** `composeCompiler {}` 配置。在 `app/build.gradle.kts` 与 `feature/chat/build.gradle.kts` 加：

```kotlin
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

记录改前 `skippable` 比例（`MessageBubble` / `AssistantMessageBlock` / `UserMessageBubble` 目前应为 `restartable`）。

### 2.3 四个埋点

| 埋点 | 位置 | 用途 |
|---|---|---|
| `McaRecompositionCounter.tick("bubble:${id}")` | `ChatMessageBubbleScreens.kt:59` 后 | 验证「非最后一条重构 == 0」 |
| `tick("chatUiState")` | `buildChatUiState` 入口 | 验证记忆化命中率 |
| `tick("historySlice")` | `buildChatHistoryItems` 入口 | **验证 R6 是否真修好** |
| `tick("decode:${uri}")` | `ImageAssetIoSupport.kt:loadImageBitmap` | 验证图片缓存 |

> 计数器放 `core/designsystem/src/debug/`，release 不存在。

### 2.4 阶段违规审计（采纳 Gemini 的这项）

检索全仓 `animate*AsState` / `infiniteTransition.animate*` 的返回值，凡被以下方式读取的一律登记：
- 作为 Composable 参数
- 作为 `Modifier.background(...)` / `.padding(...)` / `.size(...)` / `.rotate(...)` 的参数

产出 `docs/proposals/baseline/P0-PHASE-AUDIT.md`，W4/W5 中 100% 迁移到 `graphicsLayer { }` / `offset { }` / `drawBehind { }`。

**实测已知违规不少于 5 处**：`McaSkeletonBlock`（V7 版）、`AnimatedMcaAssistantMark`（`McaBrandMarks.kt:322`）、`SettingsSupport.kt:112`、`ImageGenerationResultScreens.kt:314,320`、`ChatGeneratedImageGridViews`。

### 2.5 交付物

```
docs/proposals/baseline/
  P0-BASELINE.md      # 三场景改前数字
  P0-SKIPPABLE.md     # compose 报告摘要
  P0-INSTRUMENTS.md   # 四个埋点
  P0-PHASE-AUDIT.md   # 阶段违规清单
```

---

## 3. 目标架构

### 3.1 数据流：改前 / 改后

**改前**：

```
每 75ms 一次 token
  └─ store.update { copy(messages = NEW, chatSessions = NEW, updatedAt = now) }
        ├─ ChatUiState 重建（159 字段）
        ├─ ChatScreen 顶层读 state.messages.last().content → 整屏重组
        ├─ ChatDeviceStatusOverlay(state) → deviceStatusItems() → JSONObject(...)   ← R7
        ├─ ChatRoute.viewModel.update() → 组合期写 StateFlow + 重建 96 lambda        ← R8
        └─ 消息列表：29 参数 + 7 内联 lambda → 全部 item 重组
```

**改后**：

```
每 75ms 一次 token
  └─ store.update { copy(messages = NEW, chatSessions = 同一个引用) }        ← R6 修复
        ├─ 6 个子域 remember：
        │    ├─ historySlice  ← chatSessions 引用未变 → 100% 命中
        │    ├─ assistantSlice ← 未变 → 100% 命中
        │    ├─ catalogSlice   ← 未变 → 100% 命中
        │    ├─ mediaSlice     ← 未变 → 100% 命中
        │    ├─ shellSlice     ← 未变 → 100% 命中
        │    └─ conversationSlice ← 只有它变（messages / isGenerating）
        ├─ ChatScreen 不读 content（滚动逻辑下沉）
        ├─ ChatDeviceStatusOverlay 只在 expanded 时算 items                 ← R7 修复
        ├─ ChatRoute.update 移入 SideEffect                                  ← R8 修复
        └─ 消息列表：3 参数 + 1 稳定事件回调 → 仅最后一条重组
```

### 3.2 六个域快照（注意 key 的正确性）

```kotlin
// feature/chat/.../ChatSnapshots.kt（新建）
@Immutable data class HistorySnapshot(val items: List<ChatHistoryItem>, val activeId: String?)
@Immutable data class AssistantSnapshot(val items: List<AssistantUiItem>, val selectedId: String)
@Immutable data class CatalogSnapshot(val local: List<ChatModelChoice>, val cloud: List<ChatModelChoice>, val selectedName: String?, val isCloud: Boolean)
@Immutable data class MediaSnapshot(val images: List<ImageAssetUiItem>, val jobs: List<ImageGenerationUiJob>, val files: List<FileAssetUiItem>)
@Immutable data class ShellSnapshot(val statusMessage: String?, val browserTask: BrowserTask?)
@Immutable data class ConversationSnapshot(
    val messages: List<ChatMessage>,
    val readOnly: Boolean,
    val activeConversationId: String?,
    val characterCardImported: Boolean,   // ← 在映射层算好，不在 item 内 any{}
    val isGenerating: Boolean,
    val phase: GenerationPhase?, val tokenProgress: TokenProgress?,
    val persistProgress: PersistProgress?, val stats: RuntimeStats?,
)
```

**每个 slice 的 key 必须只包含它真正读的字段** —— 这是 V7 与 Gemini 都栽过的地方。用「编译器辅助」保证：把 `projectXxx` 的签名收窄为它自己的 `...SnapshotInputs` data class，**漏字段会编译失败**。

---

## 4. 工作分解 W1–W10

> 格式：问题 → 改动 → 验收（可失败）→ 收益 → 回滚 → 风险

### W1 每帧预算（含 R6 根因修复）

#### W1-0 修复流式 commit 的写放大【P0，最重要】

**问题**：R6。这是 V7 与 Gemini V8 记忆化全部失效的根因。

**改动**（`McaAppContainer.kt:341` 附近，约 15 行）：

```kotlin
// 文件顶部加
private const val CHAT_SESSION_INDEX_REFRESH_MS = 1_000L

// commit 内
val now = System.currentTimeMillis()
val isTerminal = batch.isTerminal          // 由 accumulator 传入（Done/stop 时为 true）
val shouldRefreshSessionIndex = isTerminal || (now - lastSessionIndexRefreshAt >= CHAT_SESSION_INDEX_REFRESH_MS)
if (shouldRefreshSessionIndex) lastSessionIndexRefreshAt = now

state.copy(
    messages = updated,
    activeChatSessionId = sessionId,
    chatSessions = if (shouldRefreshSessionIndex) {
        state.chatSessions.upsertSessionFor(...)     // 1 次/秒 或终态
    } else {
        state.chatSessions                            // 引用不变
    }
)
```

**配套**：`ChatStreamOutputBatch` 增加 `isTerminal: Boolean`；`flush()` 在终态路径传 `true`。

> **行为影响**：会话抽屉的 `messageCount` / `updatedAtText` 在流式期间最多滞后 1 秒。**这是可接受的** —— 抽屉在生成时是关闭的。必须在 PR 里显式写明这是**有意的行为折中**。

**验收（可失败）**：

| # | 测试 | mutation 后必须红 |
|---|---|---|
| 1 | 流式 500 token，断言 `buildChatHistoryItems` 调用次数 ≤ 生成轮次 × 2（而非 500） | 去掉节流 |
| 2 | 断言最终（终态）`chatSessions` 内容与不节流版本**逐字段相等** | 终态不刷新 |
| 3 | 单测：`chatTitle` 的 Regex 构造计数在 500 token 内 ≤ 3 | 回到每 token 调用 |

**收益**：消除每 token 4 次 Regex 构造 + 全量排序 + 新 List 分配。**并且让 W1-1 到 W1-4 真正生效。**

**回滚**：`shouldRefreshSessionIndex = true` 恒真。

**风险**：中（抽屉 1 秒延迟）。**缓解**：终态必刷新 + 测试 2 保证终态正确。

#### W1-1 六子域独立记忆化【P0】

**改动**（`ChatUiStateMapping.kt` 拆分 + `MainActivity.kt:243` 重写）：

```kotlin
// app/src/main/java/com/muyuchat/mca/ChatUiStateMemoizedMapping.kt（新建）
@Composable
internal fun rememberMemoizedChatUiState(
    state: MainUiState,
    sessionPresentations: Map<String, AssistantSessionPresentation>,
    timestampLocale: Locale,
    imageGenerationHistoryById: Map<String, ImageGenerationHistoryMetadata?>,
    assistantImageToolPermission: ChatImageToolPermissionUiState,
    preparedImageUi: Map<String, PreparedLocalImageUi>,
    generationParamsForChatModel: (String) -> GenerationParams,
): ChatUiState {
    val historySlice = remember(
        state.chatSessions, state.activeChatSessionId, sessionPresentations, timestampLocale
    ) { projectHistory(state.chatSessions, state.activeChatSessionId, sessionPresentations, timestampLocale) }

    val assistantSlice = remember(
        state.assistants, state.selectedAssistantId, state.params,
        state.models, state.cloudModels, assistantImageToolPermission
    ) { projectAssistants(state.assistants, state.selectedAssistantId, state.params, state.models, state.cloudModels, assistantImageToolPermission) }

    val catalogSlice = remember(
        state.models, state.cloudModels, state.loadedModelId, state.selectedChatBackend,
        state.selectedCloudChatModelId, state.params, state.deviceProfile, state.localImageModels,
        state.stats
    ) { projectCatalog(state, generationParamsForChatModel) }

    val mediaSlice = remember(
        state.images, state.imageJobs, state.files,
        imageGenerationHistoryById, preparedImageUi, timestampLocale
    ) { projectMedia(state.images, state.imageJobs, state.files, imageGenerationHistoryById, preparedImageUi, timestampLocale) }

    val shellSlice = remember(state.statusMessage, state.browserTask) {
        projectShell(state.statusMessage, state.browserTask)
    }

    val conversationSlice = remember(
        state.messages, state.conversationReadOnly, state.activeChatSessionId,
        state.assistants, state.selectedAssistantId,
        state.isGenerating, state.generationPhase, state.generationTokenProgress,
        state.generationPersistProgress, state.generationStats, state.promptContextUsage
    ) { projectConversation(state) }

    return remember(historySlice, assistantSlice, catalogSlice, mediaSlice, shellSlice, conversationSlice) {
        assembleChatUiState(historySlice, assistantSlice, catalogSlice, mediaSlice, shellSlice, conversationSlice)
    }
}
```

> **注意三件事**（V7 与 Gemini 都没做对）：
> 1. **`catalogSlice` 必须包含 `state.stats`**：`buildLocalChatModelChoices` 与 `localChatReadinessLabel` 都读 `state.stats`（实测 `ChatUiStateModelCatalogMapping.kt` 内 `state.stats` 出现）。
> 2. **`assistantSlice` 必须包含 `state.params` / `state.models` / `state.cloudModels`**：`buildAssistantUiItems` 用 `GenerationParams.fromJson(assistant.paramsJson, state.params)`（`ChatUiStateHistoryMapping.kt:38`）与 `state.models.firstOrNull{...}`（L46）、`state.cloudModels`（L48）。
> 3. **`conversationSlice` 的 `characterCardImported` 在映射层算**，不要留在 item lambda 里 `assistants.any{}`。

**验收（可失败）**：

| # | 测试 | mutation 后必须红 |
|---|---|---|
| 1 | 流式 500 token，`projectHistory` / `projectAssistants` / `projectCatalog` / `projectMedia` 各调用 **1 次**（计数器） | 退回单一 remember（会变 500） |
| 2 | 只改 `state.statusMessage`，断言 `conversationSlice` 未重建 | key 里混入 statusMessage |
| 3 | 只改 `state.params`，断言 `assistantSlice` **重建** | 从 key 删掉 params |
| 4 | 只改 `state.stats`，断言 `catalogSlice` **重建** | 从 key 删掉 stats |

**收益**：流式期间 5/6 的子域映射**真正零执行**。

**回滚**：整体退回 `buildChatUiState(state, ...)`。

**风险**：中（漏 key → stale UI）。**缓解**：测试 2/3/4 专门防漏 key + 收窄入参让编译器兜底。

#### W1-2 `exportJson` / `parameterShareJson` 惰性化【P0】

保持 V7 设计：`AssistantUiItem.exportJson`（`ChatUiStateHistoryMapping.kt:64` 的 `assistant.toJson().toString(2)`）与 `ImageAssetUiItem.parameterShareJson`（`ChatUiStateMapping.kt:319`）改为按需生成。

**验收**：点击「复制角色卡」后内容与原 `toJson().toString(2)` **逐字符相等**；`AssistantUiItem` 不再含该字段（编译期）。

#### W1-3 `McaTimeFormatters`（修正 V7 的 F2 崩溃）【P1】

```kotlin
// app/src/main/java/com/muyuchat/mca/McaTimeFormatters.kt（新建）
package com.muyuchat.mca

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal object McaTimeFormatters {
    private data class Key(val locale: Locale, val zone: ZoneId)
    private val cache = ConcurrentHashMap<Key, DateTimeFormatter>()

    /** V7 缺 .withZone 会抛 UnsupportedTemporalTypeException；此处显式绑定。 */
    fun monthDayHourMinute(epochMillis: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val f = cache.getOrPut(Key(locale, zone)) {
            DateTimeFormatter.ofPattern("MM-dd HH:mm", locale).withZone(zone)
        }
        return f.format(Instant.ofEpochMilli(epochMillis))
    }
}
```

替换 `ChatUiStateHistoryMapping.kt:26-27`、`ChatUiStateMapping.kt:314`、`ChatUiStateImageRuntimeMapping.kt:73`。

**验收**：与改前 `SimpleDateFormat(...).format(Date(ms))` **逐字符相等**（含跨时区/夏令时边界样本）；1000 次调用 `cache.size == 1`。

#### W1-4 `mainChatScreenActions` 稳定化 + `ChatRoute` 副作用修正【P0】

两步：

1. `MainChatScreenActions.kt` 内读 `state.xxx` 的 lambda 改为在 `MainViewModel` 内部读；函数去 `@Composable`。
2. `MainActivity.kt:259` 包 `remember(viewModel) { buildChatActions(...) }`。

**并修 R8**：

```kotlin
// feature/chat/.../ChatRoute.kt:65-66
val viewModel = remember { ChatViewModel(state, inputs, actions) }
SideEffect { viewModel.update(state = state, inputs = inputs, capabilities = actions) }
```

**验收**：

| # | 测试 | mutation 后必须红 |
|---|---|---|
| 1 | 同一 capabilities 实例调两次 `update`，断言 `_screenActions` 未重建（计数器） | 去掉 `remember` |
| 2 | 不同实例，断言重建（证明 `!==` 仍有效） | 改 `!==` 为 `==` |
| 3 | 仪器：流式期间 `ChatScreen` 重组次数 == `state` 发射次数 | 去掉 `remember` |

### W2 渲染管线与流式 Markdown

#### W2-1 `MessageBubble` 29 参数 → 3 参数【P0】

`MessageRenderModel` + `MessageEvent` 密封接口 + 单一 `onEvent: (MessageEvent) -> Unit`。详见 V7 §W2-1（该节正确）。**补充两点**：

- `MessageEvent.Delete` 必须带 `messageId: String`（不是 index），并在 ViewModel 内按 id 查找 —— 与 key 语义一致。
- `onEvent` 用 `remember { { e: MessageEvent -> sink(e) } }` 保证引用稳定。

**验收**：流式 500 token，非最后一条消息 tick == 0；`MessageBubble` 参数反射断言 == 3。

#### W2-2 稳定性配置（修正 Gemini 的通配符错误）【P0】

```
# compose_compiler_config.conf（项目根）—— 只接受完全限定类名，不能有 *
com.muyuchat.core.engine.ChatMessage
com.muyuchat.core.engine.ChatImageAttachment
com.muyuchat.core.engine.ChatSourceReference
com.muyuchat.core.engine.ChatWebSearchTrace
com.muyuchat.core.engine.ChatGenerationMetrics
com.muyuchat.core.engine.ChatGeneratedImageRequest
com.muyuchat.core.engine.GenerationParams
com.muyuchat.core.engine.RuntimeStats
com.muyuchat.core.telemetry.RuntimeMetrics
com.muyuchat.core.modelstore.ModelManifest
com.muyuchat.feature.chat.MessageRenderModel
com.muyuchat.feature.chat.MessageSnapshot
com.muyuchat.feature.chat.GenerationSnapshot
```

> **注意**：`List<T>` 字段即使类型标了 stable，Compose 仍可能推断为 unstable。要让 `List` 稳定，需要 `kotlinx.collections.immutable` 的 `ImmutableList`，或对持有者加 `@Immutable` 并接受 `equals` 比较。**推荐**：对快照类加 `@Immutable`（它们确实不可变），对领域模型用 config。

**验收**：Compose 报告里上述类变为 stable；**必须**验证改深层字段（如 `content`）后 UI 仍更新。

#### W2-3 `ChatScreen` 顶层读取下沉【P0】

删除 `ChatScreen.kt:242-244` 的 `streamingScrollBucket` 顶层读取，抽成只负责滚动的子 composable。

**验收**：流式 500 token，断言 `ChatScreen` 函数体 tick == 0。

#### W2-4 `ChatUiState` 六域拆分【P1，可选】

见 §3.2。**建议先只做 `ConversationSnapshot` + `HistorySnapshot`**（收益 80%，触及面 30%）。

#### W2-5 流式 Markdown 增量 + 未闭合语法稳定器（修正 Gemini 的污染问题）【P1】

**核心修正**：**渲染文本 ≠ 可复制文本**。三个值分开：

```kotlin
// feature/chat/.../StreamingMarkdownStabilizer.kt（新建）
@Immutable
internal data class IncrementalMarkdown(
    val stablePrefix: String,      // 已闭合前缀，可记忆化
    val activeTail: String,        // 活跃尾部（渲染用，可能补了闭合符）
    val rawTail: String,           // 原始尾部（复制/复制代码用，绝不污染）
    val stableBlockCount: Int,
)

/** 只对渲染用的 tail 补闭合符；rawTail 永远原样。 */
internal fun splitStreamingMarkdown(raw: String, isStreaming: Boolean): IncrementalMarkdown {
    if (!isStreaming) return IncrementalMarkdown("", raw, raw, 0)
    val boundary = findSafeParagraphBoundary(raw)          // 只在「围栏闭合 + 段落边界」处切
    val prefix = if (boundary > 0) raw.substring(0, boundary) else ""
    val tail = raw.substring(if (boundary > 0) boundary else 0)
    return IncrementalMarkdown(prefix, stabilizeUnclosed(tail), tail, 0)
}

private fun stabilizeUnclosed(tail: String): String {
    var out = tail
    val fences = Regex("(?m)^\\s*```").findAll(out).count()
    if (fences % 2 != 0) out += "\n```"
    if (fences % 2 == 0) {
        val bolds = Regex("(?<!\\*)\\*\\*(?!\\*)").findAll(out).count()
        if (bolds % 2 != 0 && !out.endsWith("**")) out += "**"
    }
    return out
}
```

渲染：`remember(stablePrefix) { parseMarkdownBlocks(stablePrefix) }` + 只 `parseMarkdownBlocks(stabilizedTail)`。
复制：用 `stablePrefix + rawTail`。

**验收**：
1. 输入未闭合 ```` ```kotlin ```` 时渲染为 CodeBlock（非段落）；
2. 复制代码时**不含**补出来的 ```` ``` ````；
3. 追加第 5001 字时，`parseMarkdownBlocks(stablePrefix)` 调用次数不增加。

### W3 系统边界与 IME

#### W3-1 edge-to-edge + 唯一 inset owner + `imeNestedScroll`【P0】

```kotlin
// MainActivity.onCreate
enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
)
```

```kotlin
// MainActivity.kt:233
Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { ... }
```

```kotlin
// ChatStatusBarHeader.kt:65
Row(modifier = Modifier.fillMaxWidth().statusBarsPadding()...)
```

**修正 Gemini 的注解错误** —— `imeNestedScroll` 标在外层函数上：

```kotlin
// ChatMessageListSection.kt
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatMessageListSection(...) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().imeNestedScroll(),   // 手指下拉列表时跟手收键盘
        ...
    )
}
```

`imeNestedScroll()` 已在 `androidx.compose.foundation.layout` 1.11.2 中实测存在（本机 gradle cache 的 `foundation-layout-release.aar` 内含 `WindowInsetsConnection_androidKt$imeNestedScroll`）。

**并移除** `MainActivity.kt:57` 的 `SOFT_INPUT_ADJUST_RESIZE`（与 `imePadding()` 双重处理 IME inset）。

**验收**：API 35/36 上 `ChatStatusBar` 首个子元素 top ≥ 状态栏高度；`dumpsys window` 无 "Not edge-to-edge"。

#### W3-2 `values-night/styles.xml`【P0】

同 V7。色值全部取 `McaTheme.kt:54,56` 已有深色值。**注意**：Gemini 没提这一步与 `enableEdgeToEdge` 的协同 —— 两者必须同批，否则深色下状态栏图标会变黑。

#### W3-3 补 `outlineVariant` / `errorContainer`【P0】

Light：`outlineVariant = #E8EAED`、`errorContainer = #FCE8E6`、`onErrorContainer = #601410`
Dark：`outlineVariant = #3C4043`、`errorContainer = #601410`、`onErrorContainer = #F28B82`

**这不是改色系，是把色系修回来** —— 当前 23 处 `outlineVariant` 正落在 M3 默认紫灰 `#CAC4D0` / `#49454F` 上。

**验收**：单测断言 `ColorScheme.outlineVariant` 不等于 M3 默认值。

#### W3-4 深色对比度 + 输入栏阴影【P0】

- `ChatMarkdownBlockRenderers.kt:216` 代码块：深色用 `outlineVariant(#3C4043)` + 1dp `outline` 边框（单换色只有 1.22:1，不够）。
- `ChatComposerScreens.kt:447` 发送按钮：深色用 `colorScheme.primary`，图标 tint 用 `onPrimary`。
- `ChatComposerScreens.kt:263`：`shadowElevation 14.dp → 2.dp` + `BorderStroke(1.dp, outlineVariant)`。

#### W3-5 200% fontScale 韧性【P1】

44 处固定 `.height(Xdp)` 改 `.heightIn(min = Xdp)`；顶栏胶囊从 3 行文字降到 2 行。

### W4 视觉体系与零重组动效

#### W4-1 `McaTypography`（73 处字号收口）【P1】

同 V7（该节正确）。**补充 Gemini 遗漏的两处层级错误**：
- 用户气泡 16sp > 助手正文 15sp（`ChatMessageBubbleScreens.kt:280` vs `ChatMarkdownBlockRenderers.kt:128-129`）—— 层级倒置。
- 输入框行高 20sp vs 气泡 23sp（`ChatComposerScreens.kt:429-430`）—— 发送瞬间文字跳一下。

#### W4-2 间距 4dp 网格【P1】

首批 4 处：列表 `18→16`、`top 26→24`、`spacedBy 18→16`、用户气泡 `17→16`。输入栏是 16、列表是 18，**输入栏左边缘比消息左边缘外扩 2dp**。

#### W4-3 `McaMotion` + `spring`【P1】

`tween(` 64 处、`spring(` 0 处。替换用户触发的 5 处（页面滑入/退出、侧滑、取消回弹、胶囊展开），**不动**无限循环动画。

#### W4-4 修复 4 处死动画【P0】

实测 4 处（V7 已查明）：`ChatComposerActionMenuViews.kt:126-127`、`ChatComposerResearchModeViews.kt:114-115`、`ChatAuxiliaryPages.kt:69-70`、`ChatImagesWorkspaceOverlay.kt:358-359`。**最优改法**：改 `SmoothRightToLeftPage` 内部一处，3/4 + 未来调用方全受益。

#### W4-5 零重组骨架屏（修正 V7 的 F3）【P1】

```kotlin
// core/designsystem/.../McaSkeleton.kt
import androidx.compose.foundation.layout.Box   // ← V6 漏过这一行
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun McaSkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    widthFraction: Float = 1f,
) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val alpha by t.animateFloat(0.30f, 0.72f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sk")
    val base = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .graphicsLayer { this.alpha = alpha }   // ← Draw 阶段读，0 重组
            .clip(shape)
            .background(base)
    )
}
```

**验收**：挂载运行 3 秒，断言该 composable 首帧后重组次数 == 0。

#### W4-6/7 空态 + 启动页【P2】

同 V7。

#### W4-8 顶底羽化（修正 Gemini 的背景冲突）【P1】

**不做全局 Scrim**。正确做法：给列表**内容**加渐隐，而不是盖在背景上：

```kotlin
// ChatMessageListSection.kt 的 LazyColumn
LazyColumn(
    state = listState,
    modifier = Modifier
        .fillMaxSize()
        .imeNestedScroll()
        .drawWithContent {
            drawContent()
            // 顶部 20dp：把已有内容向「透明」渐隐（用 DstOut 混合，不引入任何颜色）
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black, 1f to Color.Transparent
                ),
                blendMode = BlendMode.DstOut,
                size = Size(size.width, 20.dp.toPx())
            )
            // 底部同理，起始 y = size.height - 20.dp.toPx()
        }
)
```

> **为什么用 `DstOut` 而不是盖一层 `background`**：`DstOut` 是**擦除**已有像素（让内容淡出到透明），**不引入任何颜色**，因此**在用户自定义背景图上同样正确** —— 这是 Gemini 的 `background` 渐变做不到的。且它完全符合「不改色系」约束：它一个新颜色都没有。

**验收**：截图比对列表顶/底 20dp 处内容淡出；**且在设置了自定义聊天背景图时依然正确**（这是 Gemini 方案会失败的用例）。

### W5 触感与微交互

#### W5-1 触觉矩阵（含 TTFT 首字）【P0】

V7 的 5 触点 + Gemini 的「首 Token 到达微震动」共 6 触点。**TTFT 触感确实是 ChatGPT 的细节，值得加**（Gemini 这条是对的）：

```kotlin
// 在最后一条助手消息内容首次非空时触发一次
LaunchedEffect(message.id, message.content.isNotEmpty()) {
    if (message.content.isNotEmpty() && isGenerating) haptics.tick()
}
```

> **注意防抖**：`content.isNotEmpty()` 在整条消息生命周期内只会 true 一次，用 `message.id` 作 key 天然防重。

#### W5-2 发送按钮物理化【P1】

0.92 按压 + 发送↔停止 `scaleIn(0.80f)+fadeIn(120)` 交叉。

#### W5-3 帧同步平滑跟滚（修正 Gemini 的三个缺陷）【P0】

```kotlin
// feature/chat/.../ChatAutoScroll.kt（新建）
@Composable
internal fun rememberShouldFollowLatest(listState: LazyListState, tolerancePx: Int = 64): Boolean =
    remember(listState, tolerancePx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            // 修正 Gemini 缺陷：不仅要「最后一个可见 item 是最后一条」，
            // 还要求它「底部已进入视口容差内」——否则超长最后一条自身滚动也会被判定为跟底。
            val isLast = last.index == info.totalItemsCount - 1
            val bottomInsideViewport = last.offset + last.size <= info.viewportEndOffset + tolerancePx
            isLast && bottomInsideViewport
        }
    }
```

**平滑跟滚不用 `snapshotFlow(size)`**（Gemini 缺陷 1/2/3），改用**内容高度差 + 单协程滚动**：

```kotlin
@Composable
internal fun ChatStreamingSmoothFollow(
    listState: LazyListState,
    isGenerating: Boolean,
    shouldFollow: Boolean,
) {
    if (!isGenerating || !shouldFollow) return
    // 以「列表内容总高度」为锚 —— 无论最后一条多高、是否被截断，都能感知到增长
    val contentHeight by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size } ?: 0) - info.viewportStartOffset
        }
    }
    var lastHeight by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        snapshotFlow { contentHeight }
            .distinctUntilChanged()
            .collect { h ->
                if (lastHeight >= 0 && h > lastHeight) {
                    // 用 scrollBy 而不是 animateScrollToItem：只补差值，不重定位
                    listState.scrollBy((h - lastHeight).toFloat())
                }
                lastHeight = h
            }
    }
}
```

**关键差异**：`contentHeight` 是「最后可见 item 的底 − 视口顶」，**即使最后一条被截断、即使它在屏幕中部**，它也会随文本增长而单调增加 → 增长量就是需要补的滚动量。且 `scrollBy` 在同一个 `LaunchedEffect` 里顺序执行，**不与用户 fling 抢锁**（用户一滑，`shouldFollow` 立刻变 false，协程被重启取消）。

**验收（可失败）**：
1. 生成中上滑，`firstVisibleItemIndex` 不变（mutation：去掉 `shouldFollow` 判断必红）；
2. 锚定时文本增长 100 行，最终 `firstVisibleItemIndex == lastIndex` 且**滚动次数 ≤ 内容更新次数**（证明不是阶梯）；
3. 最后一条底边 ≤ 输入栏顶边（验证 offset 符号）。

#### W5-4 图片内存缓存【P1】

同 V7（`maxMemory/16` + 48MB + `onTrimMemory`）。**Gemini 没提预算问题**，V7 的降额是对的。

#### W5-5 key 去 index + 按 id 删除【P0】

同 V7。**Gemini 未提 `onDelete(index)` 与 id-key 语义不一致的潜在 bug** —— V7 提了，保留。

#### W5-6 触控目标 44–48dp【P1】

14 处（实测），最小 26dp。**Gemini 说 19 处，未复核**。

#### W5-7 选中色 + 用户气泡可选 + 零重组光标【P2】

选区主题色 + 用户气泡补 `SelectionContainer` + 光标用 `graphicsLayer { alpha }`。

#### W5-8 帧驱动文本显现（双方全无，R9）【P2 可选，需产品决策】

```kotlin
// feature/chat/.../FramePacedTextReveal.kt（新建）
@Composable
internal fun FramePacedReveal(fullText: String, isStreaming: Boolean): String {
    if (!isStreaming) return fullText
    var revealed by remember { mutableIntStateOf(fullText.length) }
    LaunchedEffect(fullText) {
        // 网络到达比帧快时，按帧补足差额；落后太多则合并（避免永远追不上）
        val pending = fullText.length - revealed
        if (pending <= 0) return@LaunchedEffect
        val step = (pending / 6).coerceAtLeast(1)
        while (revealed < fullText.length) {
            withFrameNanos { }                      // ← 每帧只推进一次
            revealed = (revealed + step).coerceAtMost(fullText.length)
        }
    }
    return fullText.take(revealed)
}
```

**风险声明（必须写进代码注释）**：这是**唯一**会引入帧级协程循环的项。它让每帧多一次 `Text` 重组 —— **仅在最后一条流式消息上**。若 Phase 0 实测 P99 恶化，**立即回滚**。

**建议**：先做 P0/P1，跑一轮基线；若「生成中视觉跳变」仍是首要抱怨，再上这一项。**不把它算进 10 分的必要条件**。

### W6 启动与门禁

- **W6-1 Baseline Profile**：新建 `:baselineprofile`（当前 catalog 无 `androidx.benchmark` / `profileinstaller`，需新增）。
- **W6-2 两层 CI 门禁**：
  - Layer 1（每 PR，<3min）：`skippable` 比例 + 重组预算快检查。
  - Layer 2（release PR，~25min）：Macrobenchmark 三场景。
- **W6-3 启动路径**：`AppStartupViewModel.kt:74-89` 的 `McaAppContainer` 构造 + `migratePendingReferences()` 是同步阻塞在 IO 上的。**V6 说 `runBlocking` 造成主线程卡顿是错的**（实测它在 `Dispatchers.IO` 内构造，V7 已纠正）。真正可做的是把这部分做成懒加载，让首屏先出。

### W7 无障碍

- **W7-1** 7 处 `onSurfaceVariant` 叠加 alpha 恢复全值（3.16:1 → 5.74:1）。
- **W7-2** RTL：未镜像图标改 `Icons.AutoMirrored.*`。
- **W7-3** markdown 标题加 `heading()` 语义。

### W8 常驻开销

- **W8-1** `ChatDeviceStatusOverlay` 未展开时不轮询（`ChatDeviceStatusScreens.kt:105-112` 的 `LaunchedEffect(context)` 恒跑）+ **修复 R7 的组合期 JSON 解析**。
- **W8-2** 19 处 `rememberInfiniteTransition` 中，非可见/非生成态的改为按需创建。**重点**：`McaBrandMarks.kt:322` 的助手环在 `isGenerating = false` 时也在跑时钟。

### W9 全模块收口（Settings / ModelHub / Agent）

**这是 Gemini 的贡献，我保留并修正行号。**

#### W9-1 `SettingsRuntimeScreen`：组合期 JSON + 无生命周期轮询【P0】

**实测位置**：轮询 `:67-73`；JSON 解析 `:152`、`:195`；字符串扫描 `SettingsLogsPrivacyScreens.kt:376`。

**改动**：

```kotlin
// 1) 轮询加生命周期
val lifecycle = LocalLifecycleOwner.current.lifecycle
LaunchedEffect(lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        val reader = SystemLoadReader(context.applicationContext)
        while (true) {
            systemLoad = withContext(Dispatchers.IO) { reader.read() }
            delay(1000L)
        }
    }
}

// 2) JSON 解析记忆化（只解析一次）
@Immutable
internal data class ParsedNativeTelemetry(
    val componentBackendsSummary: String,
    val npuExecutionProven: Boolean,
    val visionReady: Boolean,
)

@Composable
internal fun rememberParsedTelemetry(json: String): ParsedNativeTelemetry =
    remember(json) { parseNativeTelemetry(json) }
```

> **关于 `SettingsSurfaceGoldenTest` 的 429 条契约**：该测试正则提取**双引号内、含 CJK 的字面量**，并要求与 `settings-surface.golden.txt` **完全相等**（`lost` 与 `added` 都断言为空）。因此 W9-1 的实施铁律是：**任何字符串字面量都不得增删改**。把逻辑搬进新函数时，新函数**必须留在 `SOURCE_FILES` 列表的 8 个文件内**，否则该文件的字面量会从扫描面消失 → `lost` 非空 → 测试红。

#### W9-2 `SettingsSupport.kt:112` 弹簧旋转【P1】

```kotlin
val rotation by animateFloatAsState(if (expanded) 180f else 0f, McaMotion.snappy, label = "arrow")
Icon(..., modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation })
```

#### W9-3 `ModelHub` 分段 Tab + 下载进度【P1】

Tab 切换接 `haptics.tick()`；`DownloadProgressPanel` 的进度条保持 `tween(350)` 平滑；`CardBox`（`ModelHubSharedComponents.kt:120-124`）的 `RoundedCornerShape(18.dp)` + `1.dp` 灰黑阴影改为 `outlineVariant` 微描边。

> **契约约束**：`DownloadFormattingTest` 用 `Class.forName("com.muyuchat.feature.modelhub.MarketAndDownloadsKt")` 反射调用 `formatDownloadRate(Long)` 与 `formatDuration(Long)`（**私有函数**）。**严禁重命名或移动 `MarketAndDownloads.kt`**。

#### W9-4 `feature/agent` 稳定性标注【P1】

把 `AgentUiState` 相关模型加入 stability config，确保调参日志滚动时 `skippable`。

### W10 回归验证

**每项完成后必须**：跑全量单测（含 263 个 app/src/test 文件、76 个源码断言测试、`SettingsSurfaceGoldenTest` 429 条），全绿才算完成。

---

## 5. 契约护栏

本仓库有极高密度的契约测试。**任何不了解这些的改动都会瞬间红。**

| # | 契约 | 冻结内容 | 施工铁律 |
|---|---|---|---|
| **G1** | `SettingsSurfaceGoldenTest` + `settings-surface.golden.txt` | 8 个 settings 源文件中的 **429 条** CJK 双引号字面量（实测 golden 429 行、源 429 行、`lost=0 added=0`，当前通过） | 不得增删改任何中文字面量；抽取的新函数必须留在 `SOURCE_FILES` 的 8 个文件内 |
| **G2** | `DownloadFormattingTest` | 反射调用 `MarketAndDownloadsKt` 的 `formatDownloadRate` / `formatDuration` | 严禁重命名或移动 `MarketAndDownloads.kt` |
| **G3** | `LocalChatModelCardLoadedTest` / `RecommendedCardPrimaryActionTest` | `localChatModelCardIsLoaded` / `recommendedCardPrimaryAction` 签名 | 保持原样 |
| **G4** | 76 个源码文本断言测试 | 特定调用顺序（如 `flushPendingAssistantOutput` 在 `settleGenerationUi` 之后） | W2-1 / W5-5 必须同步更新断言，单独原子 commit |

> **W1-0 的额外风险**：它改的是 `McaAppContainer` 的 commit 逻辑，可能有源码断言测试锁住这段代码的结构。**开工前先 `Select-String` 一遍 `McaAppContainer.kt` 在测试里的引用。**

---

## 6. 验收标准与排期

### 6.1 Day 1 快赢包（约 95 行，零架构风险，当天见效）

| # | 项 | 行数 | 来源 |
|---|---|---|---|
| 1 | 补 `outlineVariant` / `errorContainer` | ~6 | V7/W3-3 |
| 2 | 深色代码块 + 发送按钮对比度 | ~6 | V7/W3-4 |
| 3 | `values-night/styles.xml` | ~12 | V7/W3-2 |
| 4 | 6 触点触觉（含 TTFT） | ~45 | V7 + Gemini |
| 5 | 修 4 处死动画 + `SettingsSupport` 弹簧箭头 | ~18 | V7 + Gemini |
| 6 | 聊天主轴边距对齐 16dp | ~4 | V7/W4-2 |
| 7 | 顶底羽化（`DstOut` 版）+ `imeNestedScroll` | ~12 | V8 修正版 |
| 8 | 列表 key 去 index | ~1 | V7/W5-5 |

### 6.2 阶段排期

| 阶段 | 内容 | 天数 |
|---|---|---|
| **P0** | 基线 + 埋点 + 阶段审计 | 3–4 |
| **P1** | **W1-0（R6）** + W1-1..4 + W3 全部 + W8 | 6–7 |
| **P2** | W2 全部 + W5-3/W5-4/W5-5 | 6–8 |
| **P3** | W4 视觉体系（含 golden 基建）+ W5 其余 | 8–10 |
| **P4** | W9 全模块 + W6 门禁 + W7 | 5–6 |
| **总计** | | **28–35 个工作日** |

**关键路径**：**P0 → W1-0 → W1-1 → W2-1**。W1-0 不完成，W1-1 的记忆化没有意义。

### 6.3 16 条验收线

**性能（6）**
1. 流式 500 token，P90 帧时长 ≤ 16.7ms
2. P99 ≤ 33ms，P95 `frameOverrunMs` ≤ 2.0ms
3. 非最后一条消息重组次数 **== 0**
4. **`projectHistory` / `projectAssistants` / `projectCatalog` / `projectMedia` 在 500 token 内各执行 == 1 次**（R6 的硬指标）
5. 消息链路 `skippable` == 100%
6. 图片滚动 20 次 heap 稳定；`decodeStream` 不重复

**正确性（4）**
7. API 35/36 edge-to-edge 顶栏不被压
8. 深色代码块有可辨边界；发送按钮 ≥ 4.5:1
9. `outlineVariant` 不落 M3 默认
10. **深色冷启动无白闪**

**体验（5）**
11. 生成中上滑不被拉回
12. 锚定时平滑跟随，无 512 字符阶梯
13. 6 触点触觉；发送按压回弹；4 处死动画复活
14. `IconButton` ≥ 44dp；200% fontScale 无裁切
15. **顶底羽化在自定义聊天背景下仍正确**（Gemini 方案会失败的用例）

**流程（1）**
16. CI 有 5 个门禁；故意回归能变红；**全仓单测（含 429 条 Golden）全绿**

---

## 附录 A：Gemini V8 逐项缺陷清单

| # | 缺陷 | 级别 | 修正 |
|---|---|---|---|
| 1 | `historySlice` 键在 `state.chatSessions`，而它每 token 也变（`McaAppContainer.kt:341`） | **致命** | 先做 W1-0 消除写放大 |
| 2 | `ImageGenerationHistoryRecord` 不存在 | 编译失败 | 改 `ImageGenerationHistoryMetadata` |
| 3 | `state.selectedChatModelKey` 不存在 | 编译失败 | 改 `selectedChatBackend` + `selectedCloudChatModelId` |
| 4 | `AssistantImageToolPermission` 不是类型 | 编译失败 | 改 `ChatImageToolPermissionUiState` |
| 5 | `preparedImageUi: PreparedLocalImageUi` 少 Map | 编译失败 | 改 `Map<String, PreparedLocalImageUi>` |
| 6 | `generationParamsForChatModel: (String?) ->` 可空 | 编译失败 | 改 `(String) ->` |
| 7 | 平滑跟滚 `snapshotFlow(size)` 三缺陷 | 高 | 改内容高度差 + 单协程 `scrollBy` |
| 8 | EdgeScrim 不透明色盖住自定义背景 | 高 | 改 `DstOut` 擦除式渐隐 |
| 9 | `compose_compiler_config.conf` 用通配符 | 中（静默失效） | 逐行完全限定类名 |
| 10 | `stabilizeUnclosedSyntax` 污染可复制文本 | 中 | 渲染文本与原文分离 |
| 11 | `@OptIn` 标在表达式上 | 编译失败 | 标在外层函数 |

**Gemini 对 V7 的六条批评**：F1/F2/F3/F6 **完全成立**，F4/F5 **现象成立但方案有缺陷**。**我全部接受并在 V8 中修正。**

## 附录 B：实测数字全表

**扫描**：`core/**`、`feature/**`、`app/src/main/**`，`.kt` only，排除 `build/`、`src/test/`、`src/androidTest/`。**974 文件**。

### B.1 关键常量

| 名称 | 值 | 位置 |
|---|---|---|
| `CHAT_STREAM_PUBLISH_INTERVAL_MS` | 75（13.3 Hz） | `ChatStreamAccumulator.kt:14` |
| `CHAT_STREAM_PUBLISH_CHARS` | 2048 | `ChatStreamAccumulator.kt:11` |
| `MESSAGE_RENDER_PAGE_CHARS` | 16_384 | `ChatMarkdownRenderSupport.kt:313` |
| `STREAMING_SCROLL_CHAR_STEP` | 512 | `ChatMarkdownRenderSupport.kt:314` |
| `maxSessions` / `maxMessages` | 100 / 2_000 | `ChatSessionPersistenceMapping.kt:26-27` |

### B.2 Compose 原语

| 指标 | 实测 |
|---|---|
| `tween(` / `spring(` | **64 / 0** |
| 命名缓动 | **0** |
| `AnimatedVisibility(` | **12**（4 处 `visible = true`） |
| `rememberInfiniteTransition` | **19** |
| `withFrameNanos` | **0** |
| `derivedStateOf` | **0** |
| `animateScrollToItem` / `scrollToItem` | **0 / 2** |
| `isScrollInProgress` | **0** |
| `HapticFeedback` | **0** |
| `@Immutable` / `@Stable` | **2** |
| `imeNestedScroll` | **0**（API 存在于 foundation-layout 1.11.2） |

### B.3 排版与结构

| 指标 | 实测 |
|---|---|
| `fontSize =` | **73**（18 个不同值） |
| `lineHeight =` | **47** |
| `letterSpacing` | **0** |
| `Typography(` | **0** |
| `feature/chat` 不同 padding dp | **27** |
| `shadowElevation` 站点 / 不同值 | **22 / 10** |
| `RoundedCornerShape(` / 不同圆角 | **162 / 15** |
| `ChatUiState` / `MainUiState` 字段 | **159 / 144** |
| `ChatScreenActions` 字段 | **96** |
| `MessageBubble` 参数 | **29**（7 内联 lambda） |
| 整体接收 `ChatUiState` 的 composable | **13** |
| `PagedAssistantRichText` 调用点 | **6**（每处一个 `rememberSaveable`） |
| `SelectionContainer` 站点 | **26** |

### B.4 系统边界与测试

| 指标 | 实测 |
|---|---|
| `enableEdgeToEdge` / `statusBarsPadding` / `safeDrawing` | **0 / 0 / 0** |
| `WindowSizeClass` / `fontScale` / `LayoutDirection` | **0 / 0 / 0** |
| `LocalTextSelectionColors` | **0** |
| `values-night/` | **不存在** |
| `:benchmark` / `:baselineprofile` | **不存在** |
| `composeCompiler {}` 配置 | **不存在** |
| `SettingsSurfaceGoldenTest` 文案 | **429 条**（通过） |
| 源码文本断言测试文件 | **~76** |
| Kotlin / AGP / Compose BOM | **2.3.21 / 8.13.2 / 2026.05.01** |
| minSdk / targetSdk / compileSdk | **26 / 36 / 36** |

---

## 结语

**对 Gemini V8 的最终裁决**：它是一份**诊断能力强于 V7、但代码不可直接落地**的方案。它准确抓住了 V7 的四个真错误（F1/F2/F3/F6），也带来了三个真正有价值的新东西（`graphicsLayer` 下沉、`imeNestedScroll`、跨模块收口）。但它的核心修复 `historySlice` 重犯了我 V7 的错误，5 处代码编译不过，且未意识到 `state.chatSessions` 也在每 token 变化。

**V8 相对 V7 与 Gemini V8 的三个真正新贡献**：

1. **R6 — 流式 commit 的写放大**：`upsertSessionFor` 每 token 跑 4 次 Regex + 全量排序，且改写 `chatSessions` 引用。**这是双方记忆化方案全部失效的根因**。修它只要 15 行，却决定后面 300 行的成败。
2. **R7 — `ChatDeviceStatusOverlay` 每帧 JSON 解析**：一个默认收起的悬浮球，让应用每秒解析 13 次 JSON。
3. **R8 — `ChatRoute` 在组合期写 StateFlow**：违反 Compose 基本规则，且每帧重建 96 个 lambda。

**如果只能做三件事**：**W1-0（R6）→ W2-1（`MessageBubble` 3 参数）→ W5-3（滚动）**。这三件决定 80% 的体感差异，合计约 200 行。

**最后一句**：V7 是 9.1，Gemini V8 是 7.8（诊断 9，可落地性 6）。**V8 的 10 分不在"更多条目"，而在于先修那条让所有条目生效的地基**——`state.chatSessions` 的每 token 改写。

---

# V8.1 收官修订：对"最后 5 个陷阱"的逐条裁决

> 2026-10-18 追加。有人对本 V8 提出 5 个"实现级陷阱"，并给了 5 个补丁。我逐条核验了源码与 Gradle 构件。
> **裁决：3 条完全成立（其中 2 条比它说的更严重）、1 条部分成立、1 条不成立。它自己的 5 个补丁里，2 个编译不过、2 个有实质错误。**

## 0.1 裁决表

| # | 指控 | 裁决 | 依据 |
|---|---|---|---|
| 1 | `DstOut` 缺 `CompositingStrategy.Offscreen` → 上下黑条 | ✅ **成立，渲染级 Bug** | 本 V8 原文 W4-8 确实写的是裸 `drawWithContent { ... blendMode = DstOut }`，全文 **0 处** `CompositingStrategy`。无离屏层时 `DstOut` 作用于窗口根缓冲，会把下层 `ChatBackground` 一起擦成透明洞 |
| 2 | `contentHeight` 差值滚动会在短回复时乱滚 | ⚠️ **部分成立** | 我的原方案用 `contentHeight` 增长量驱动 `scrollBy`，其缺陷不在"提前滚"（`scrollBy` 在内容未溢出时被钳制，滚不动），而在于：**没有把 `contentPadding.bottom` 纳入可用底边的定义**，导致锚点与列表真实留白错位。Gemini 的批评方向对，但它 Patch 4 的 `viewportEndOffset - bottomChromeHeightPx` 与 `contentPadding.bottom = 118.dp` 是两个不同来源的数，仍然错位（详见 §3） |
| 3 | `lastSessionIndexRefreshAt` 在 `store.update` 内改写 | ✅ **成立，且违反本仓库硬锁** | `docs/architecture/MCA_PROJECT_HARD_LOCK.md` §6：「CAS/update 中的 reducer 必须纯净，能够安全重试」。`MutableStateFlow.update` 的 lambda 在 CAS 竞争时会重跑，闭包里写外部 `var` 是**合同违规**，不只是性能问题 |
| 4 | `stabilizeUnclosed` 内每帧 `Regex(...)` | ✅ **成立** | 本 V8 原文确实在函数体内写 `Regex("(?m)^\\s*```")`。我在 R6 里刚批评 `chatTitle()` 每 token 造 Regex，自己在 W2-5 又犯一次 |
| 5 | `feature/settings` 缺 `lifecycle-runtime-compose` 依赖 | ❌ **不成立** | 核验 `ui-android-1.11.2.pom`：`lifecycle-runtime-compose-android` 的 scope = **`compile`**，是 `androidx.compose.ui` 的传递依赖。`feature/settings/build.gradle.kts` 只依赖 `compose.ui` 即可编译。`repeatOnLifecycle` 在 `lifecycle-runtime` 的 `RepeatOnLifecycleKt` 中，同样在闭包内 |

**它的补丁自身的问题**（比它指控我的更硬）：

| 补丁 | 问题 | 后果 |
|---|---|---|
| Patch 1 | 调 `upsertSessionFor(..., isStillCurrent = isStillCurrent)` | **该函数无此参数**（实测签名只有 `state/sessionId/messages/assistantId/assistantSnapshot/replaceAssistantSnapshot/modelMode/modelId`）→ 编译失败 |
| Patch 1 | 用 `batch.isTerminal` | `ChatStreamOutputBatch`（`ChatStreamAccumulator.kt:17-21`）只有 `content/reasoning/reasoningDurationMs` → 编译失败（本 V8 已写明这是"配套"改动，它的补丁漏了） |
| Patch 4 | `usableBottom = viewportEndOffset - bottomChromeHeightPx` | **口径不一致**：`contentPadding.bottom` 是硬编码 `118.dp`，`bottomChromeHeight` 是 `measured.coerceAtLeast(120.dp)`。两个不同来源的数相减，锚点必然偏 2dp 以上；输入栏变高时偏差更大（不死循环，但定位不准） |
| Patch 5 | 用 `androidx.compose.ui.platform.LocalLifecycleOwner` | 该符号在 `AndroidCompositionLocals_androidKt` 中仍存在但**已废弃**；正确入口是 `androidx.lifecycle.compose.LocalLifecycleOwner` |

## 1. 修正后的 W1-0（同时满足硬锁 §6 的纯 reducer）

```kotlin
// app/src/main/java/com/muyuchat/mca/composition/McaAppContainer.kt:322 附近
commit = { batch, isStillCurrent ->
    store.update { state ->
        if (!isStillCurrent()) return@update state
        val index = state.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (index < 0) return@update state

        val updated = state.messages.toMutableList()
        val current = updated[index]
        updated[index] = current.copy(
            content = current.content + batch.content,
            reasoningContent = current.reasoningContent + batch.reasoning,
            reasoningDurationMs = maxOf(current.reasoningDurationMs, batch.reasoningDurationMs),
        )
        val sessionId = state.activeChatSessionId ?: return@update state.copy(messages = updated)

        // 纯 reducer：不读不写任何外部可变状态，可被 CAS 安全重试。
        val sessionKnown = state.chatSessions.any { it.id == sessionId }
        val shouldRefresh = !sessionKnown || batch.isTerminal
        state.copy(
            messages = updated,
            activeChatSessionId = sessionId,
            chatSessions = if (shouldRefresh) {
                state.chatSessions.upsertSessionFor(
                    state = state,
                    sessionId = sessionId,
                    messages = updated,
                )
            } else {
                state.chatSessions      // 引用恒定 → historySlice 整个流式期间 0 重算
            },
        )
    }
}
```

**配套（缺一不可）**：`ChatStreamOutputBatch` 增加 `val isTerminal: Boolean = false`；终态调用路径传 `true`。实测 `flushPendingAssistantOutput` 调用点：`ChatExecutionOperations.kt:393`（Done）、`:467/:513/:546`（取消/异常收尾）—— 均应视为 terminal。

**为什么必须纯**：`MutableStateFlow.update` 并发时**重跑 lambda**。读时间戳 / 累加计数 / 生成 UUID / 写文件都可能在重跑中执行多次。`isTerminal` 是调用方传入的不可变事实，重跑多少次结果都一样 —— 这才是满足硬锁 §6 的写法。

## 2. 修正后的 W4-8（真透明羽化，不擦穿背景）

```kotlin
// core/designsystem/.../McaEdgeFeather.kt（新建）
package com.muyuchat.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 让滚动内容的顶/底边缘淡出到「透明」，不引入任何颜色，
 * 因此用户使用自定义聊天壁纸时也不会有色带。
 *
 * 关键：必须 Offscreen。否则 DstOut 作用在窗口根缓冲上，
 * 会把下层 ChatBackground 一起擦掉，表现为两条纯黑/纯透明死区。
 *
 * 代价：Offscreen 让该节点多一次离屏合成。仅在需要时挂载，
 * 并在 Phase 0 的 S3 场景确认 P90 帧时长无退化（硬锁 §10）。
 */
fun Modifier.mcaEdgeFeather(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val topPx = top.toPx()
        if (topPx > 0f && size.height > topPx * 2f) {
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, 0f, topPx),
                topLeft = Offset.Zero,
                size = Size(size.width, topPx),
                blendMode = BlendMode.DstOut,
            )
        }
        val bottomPx = bottom.toPx()
        if (bottomPx > 0f && size.height > bottomPx * 2f) {
            val startY = size.height - bottomPx
            drawRect(
                brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY, size.height),
                topLeft = Offset(0f, startY),
                size = Size(size.width, bottomPx),
                blendMode = BlendMode.DstOut,
            )
        }
    }
```

> **硬锁 §9.1 提示**：该条明确「禁止以增加渐变作为默认高级化方案，确有需求必须有具体问题和渲染证据」。**W4-8 属于需用户显式批准的视觉变更，不是默认项。** 未获批准则跳过，不影响其余所有 W 项。

## 3. 修正后的 W5-3（显式对齐 contentPadding，而不是再减一次 bottomChromeHeight）

**先修正我自己的一个判断**：§0.1 表格里我写 Patch 4「双重扣减 → 永久轻微滚动」。**这条批评过重了。** 更准确的说法是：

- `scrollBy` 在到达内容末端时会被**钳制**，所以那个 `+2dp` 的"幻影溢出"不会导致真的持续滚动，只会每次多一次无效调用。
- 但**数学上确实是错位的**：`contentPadding.bottom` 已经是 118dp（`ChatMessageListSection.kt:64` 硬编码），而 `bottomChromeHeight` 是测量值且 `≥120dp`（`ChatScreen.kt:276`）。两者**从不相等**，把测量值再减一次，等于用一个和 padding 无关的量去校准 padding。

**真正该修的，是这两个魔数不联动这件既有事实**（V6 P0-4 曾提出、本 V8 未落实）：`contentPadding.bottom = 118.dp` 是硬编码，而输入栏真实高度是测量出来的。输入栏一旦因为多行输入或状态提示变高，最后一条消息就会被压在输入栏后面。

```kotlin
// feature/chat/.../ChatAutoScroll.kt（新建）
@Composable
internal fun ChatStreamingSmoothFollow(
    listState: LazyListState,
    isGenerating: Boolean,
    shouldFollow: Boolean,
    contentPaddingBottom: Dp,       // ← 必须与 LazyColumn 的 contentPadding.bottom 是同一个值
) {
    if (!isGenerating || !shouldFollow) return
    val paddingPx = with(LocalDensity.current) { contentPaddingBottom.toPx() }
    LaunchedEffect(listState, paddingPx) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last == null || last.index != info.totalItemsCount - 1) return@snapshotFlow 0
            // 可用底边 = 视口底 − 列表自带的底部留白。
            // 这个留白就是"给输入栏让出的空间"，因此不能再叠加 bottomChromeHeight，
            // 也不能忽略它——两者必须用同一个数值。
            ((last.offset + last.size) - (info.viewportEndOffset - paddingPx)).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            .filter { it > 0 }
            .collect { overflowPx -> listState.scrollBy(overflowPx.toFloat()) }
    }
}
```

**并把 `contentPadding` 改成联动**（这是本项真正的收益）：

```kotlin
// ChatMessageListSection.kt:64
contentPadding = PaddingValues(top = 26.dp, bottom = bottomChromeHeight)
```

`bottomChromeHeight` 已经作为参数传进来了（`ChatMessageListSection.kt:38`），目前只用于覆盖层（`:223`）；改成同时驱动 `contentPadding` 后，118dp 与 ≥120dp 两个魔数消失。

**关于「短回复会不会被提前滚走」**：不会，但原因不是公式，而是 `scrollBy` 的钳制——列表在未超出内容时无处可滚。真正会让人感觉"被推走"的是 `shouldFollowLatest` 的容差过宽；所以 `rememberShouldFollowLatest` 应改为：

```kotlin
isLast && (last.offset + last.size) <= (info.viewportEndOffset - paddingPx) + tolerancePx
```

> **实测依据**：`contentPadding.bottom = 118.dp`（`ChatMessageListSection.kt:64`）、`bottomChromeHeight = measured.coerceAtLeast(120.dp)`（`ChatScreen.kt:275-276`）、`bottomChromeHeight` 目前仅用于 `:223` 的 overlay padding。

## 4. W2-5 的修正（预编译正则）

```kotlin
// 文件顶层，进程内只编译一次
private val FENCE = Regex("(?m)^\\s*```")
private val BOLD  = Regex("(?<!\\*)\\*\\*(?!\\*)")

private fun stabilizeUnclosed(tail: String): String {
    var out = tail
    if (FENCE.findAll(out).count() % 2 != 0) return "$out\n```"
    if (BOLD.findAll(out).count() % 2 != 0 && !out.endsWith("**")) out += "**"
    return out
}
```

## 5. W9-1 的修正（依赖其实够用，只是要引对包）

**裁决：依赖不缺。** `ui-android-1.11.2.pom` 中 `lifecycle-runtime-compose-android` 为 `compile` scope，`feature/settings` 仅依赖 `compose.ui` 已可获得。**只需用正确包名**：

```kotlin
import androidx.lifecycle.compose.LocalLifecycleOwner   // ✅ 正确
import androidx.lifecycle.repeatOnLifecycle            // 在 lifecycle-runtime 中

val owner = LocalLifecycleOwner.current
LaunchedEffect(owner) {
    owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        val reader = SystemLoadReader(context.applicationContext)
        while (true) {
            systemLoad = withContext(Dispatchers.IO) { reader.read() }
            delay(1_000L)
        }
    }
}
```

> 不要用 `androidx.compose.ui.platform.LocalLifecycleOwner` —— 仍存在但已废弃。

## 6. 关于"10.0 分"——必须说清楚的事

本仓库硬锁 §12.4 明确禁止「把聚合脚本的分级结果自行提升到 10 分或全部生产通过」；§10、§12.1 要求性能与视觉结论必须有**同设备、同模型、同参数、同构建类型的 before/after 实测**。

**本文到目前为止没有跑过任何一次真机 Macrobenchmark，也没有一张 before/after 截图。** 在这个前提下，任何"10.0/10"都是文档自评，不是工程证据。

V8.1 的准确状态：

| 维度 | 状态 |
|---|---|
| 缺陷覆盖 | 已实测到 `文件:行号` 级，引用可程序化校验 |
| 代码可落地性 | 5 个已知实现陷阱已修（见上） |
| 编译验证 | **NOT_RUN** —— 文档不含可编译工程 |
| 性能收益 | **NOT_RUN** —— 全部为静态推算 |
| 视觉收益 | **NOT_RUN** —— 无 before/after 渲染 |
| 合规性 | W4-1/2/3/8 属视觉变更，按硬锁 §9.1 需单独批准；W1-0 已满足 §6 纯 reducer |

**结论：方案层面已收敛，工程层面仍未验证。** 真正的"10 分"在 Phase 0 基线 + Day 1 快赢包落地并复测之后，由实测数字决定，不由文档决定。

---

# V8.2 勘误：V9 指出且经我实测确认的错误

> 2026-10-18 追加。V9 对本方案提出若干技术反驳。我没有直接采信，而是用**字节码常量池**与**真实 Kotlin 编译器**逐条复核。
> **结果：V9 有 8 条成立，其中 6 条是本方案 V8/V8.1 的硬错误（2 处编译失败、1 处终态丢数据）。以下全部认领。**

## 1. 错误：我说"稳定性配置不支持通配符"

**裁定：V9 正确，我错误。**

dump `kotlin-compose-compiler-plugin-embeddable-2.3.21.jar` 的 `FqNameMatcher` 常量池，实测存在：

```
PATTERN_SINGLE_WILD
PATTERN_MULTI_WILD
PATTERN_PACKAGE_SEGMENT
singleWildcardSuffix
multiWildcardSuffix
matches(Lorg/jetbrains/kotlin/name/FqName;)Z
```

类名是 `FqNameMatcher`（**匹配器**）而非 `FqName`；源文件 `StabilityExternalClassNameMatching.kt`。**Compose 编译器确实支持 `*` 与 `**` 通配符。**

因此附录 A #9 判"Gemini 用通配符是静默失效"是**误判**——那种写法可以生效。

**但保留一条**：`StabilityConfigParserImpl` 常量池只含 `'//'`，**不认 `#` 与行尾注释**。这条提醒仍成立。

## 2. 错误：我说"Kotlin 不允许把注解标在表达式上"

**裁定：V9 正确，我错误。**

dump `kotlin.OptIn.class` 常量池，`allowedTargets` 明列为：

```
CLASS  PROPERTY  LOCAL_VARIABLE  VALUE_PARAMETER  CONSTRUCTOR
FUNCTION  PROPERTY_GETTER  PROPERTY_SETTER  EXPRESSION  FILE  TYPEALIAS
```

`EXPRESSION` 确在目标集合内。附录 A #11 "标注在表达式上编译不过"是**错的**。

**补一条 V9 没覆盖的边界（真实编译器实测）**：`return @OptIn(...) expr` 会报

```
error: syntax error: There should be no space or comments before '@' in label reference.
error: unresolved label.
```

因为 `return` 后的 `@` **优先被解析为标签引用**。表达式级 `@OptIn` 在 `return` / 赋值右侧属歧义区，不是万能写法。V9 "具体写法由本工具链编译验证"是稳妥的。

## 3. 错误：V8.1 的 `Brush.verticalGradient` 位置参数编译不过

**裁定：V9 正确，我的代码有编译错误。**

原写法：

```kotlin
brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, 0f, topPx),
```

签名为 `(vararg colorStops: Pair<Float, Color>, startY: Float = 0f, endY: Float = Float.POSITIVE_INFINITY)`。Kotlin 要求 **vararg 之后的参数必须具名**，位置传参会去匹配 `Pair<Float, Color>` → 类型错误。正确写法：

```kotlin
Brush.verticalGradient(
    colorStops = arrayOf(0f to Color.Black, 1f to Color.Transparent),
    startY = 0f,
    endY = topPx,
)
```

## 4. 错误：V8.1 的"纯 reducer"并不纯

**裁定：V9 正确。**

我声称已满足硬锁 §6"纯 reducer"。但 `upsertSessionFor` 内部仍有：

```kotlin
// ChatSessionPersistenceState.kt:57
updatedAt = System.currentTimeMillis(),
```

**helper 自己读时钟。** 我只把 `lastSessionIndexRefreshAt` 挪出闭包，没拔掉 helper 内的时钟读取。所以准确说法是"没有外部可变变量"，**不是"无副作用"**。

**正确修法**（V9 §6.2 对）：给 `upsertSessionFor` 增加不可变 `occurredAt: Long` 入参，由调用方在 reducer **外**取一次时间传入；保留旧签名兼容适配器。

## 5. 错误：V8.1 的终态覆盖点不全

**裁定：V9 正确。**

V8.1 称"`ChatExecutionOperations.kt:393/467/513/546` 四点视为 terminal"。实测还有：

- `ChatSessionPersistenceOperations.kt:67` 也调 `flushPendingAssistantOutput`
- `MainCoordinatorHostAdapter.kt:155-156` 是另一条入口
- Stop 走 `cancel → 推进 epoch → 无 runId flush`
- **Done 之后仍可修改消息**（citations / 工具结果 / 空回复回退）

所以"`batch.isTerminal` 一置就刷新 chatSessions"会**丢掉终态后的修改**。真 bug。

## 6. 错误：V8 的 `catalogSlice` 验收自相矛盾

**裁定：V9 正确。**

V8 把 `state.stats` 放进 `catalogSlice` 的 key，又在验收里要求"500 token 内 catalog 执行 **1 次**"。而 stats 在流式期间每 token 都变。两个要求不可能同时成立。

修法：catalog 只依赖它真正读的 stats 子字段（如 `loadedModelId`），或把 stats 展示拆成独立投影。

## 7. 错误：我引用了不存在的 `findSafeParagraphBoundary`

**裁定：V9 正确。**

V8 的 W2-5 与 V8.1 §4 都调用了 `findSafeParagraphBoundary(raw)`，但**该函数在仓库中不存在**，我也没在方案里给出实现。等于把"算法完成"写成了既定事实。V9 §16.2"引用未实现的函数当成算法完成"直接命中。

## 8. 我对 strong skipping 的推断过强

**裁定：V9 正确。**

V8 称"`List`/data class 不稳定 → 旧消息必重组"。本仓库 Kotlin **2.3.21**，Compose 编译器 **strong skipping 默认开启**，`List` 参数在 strong skipping 下按实例相等跳过。"必重组"是**未经报告验证的推断**。

**正确表述**：先看 `metricsDestination` 产出的 skippable 报告与 trace，再决定是否引入 stability config。

## 9. V9 唯一前提有误的一条

V9 表格写"lifecycle 依赖完全缺失 → V8.1 已纠正 → 直接使用宜显式声明依赖"。复核 `ui-android-1.11.2.pom`：

```
lifecycle-runtime-compose-android   scope=compile
```

它是 `androidx.compose.ui` 的 **compile scope** 传递依赖，`feature/settings` 不显式声明也能编译。"宜显式声明"是好习惯，但不是编译必需。

## 10. 更正后的自我评价

V8 系列在**事实层面有 6 处实质错误**，其中 2 处会导致编译失败、1 处会导致终态丢数据。**V9 给的 7.4/10 评分公允，我不再争辩。**

**V8 系列真正站得住的价值只剩 3 条**：R6 写放大**现象**（现象成立，修法需重做）、R7 悬浮球 JSON 解析、R8 `ChatRoute` 组合期写状态。其余条目要么越权（改字号/间距/阴影，违反硬锁 §9.1 与用户"不改设计语言"约束），要么依据不足（strong skipping、通配符、表达式注解）。

**本文件最终状态：历史材料，不得作为实施依据。以 V9 为准。**

---

# V8.3 自我更正：V9 撤销 chatSessions 降频，我此前判它"扣分"是错的

> 2026-10-18 追加。我曾说 V9"主动撤掉了最大的性能收益"、把它算作扣分项。用户追问后我重新核验持久化链路。**结论：V9 做得对，我的批评错，而且我连续犯了两层错误。**

## 1. 第一层错误：我把"性能损失"当成主要矛盾，忽略了一致性风险

我当时的理由只有一条：「代价是无收益，性能目标从结构性改善降级为轻量投影优化」。

**但降频真正会破坏的不是性能，是持久化正确性。** 硬证据：

```kotlin
// app/src/main/java/com/muyuchat/mca/ChatSessionPersistenceOperations.kt:63-70
if (generationRunId != null && !chatRuns.isCurrent(generationRunId)) return null
// A completion can end between chunks. Materialize its final bounded
// buffer before taking the Room snapshot.
port.flushPendingAssistantOutput(generationRunId)
if (generationRunId != null && !chatRuns.isCurrent(generationRunId)) return null
val snapshot = (sessions ?: uiState.value.chatSessions).map { session ->   // ← L68
    session.copy(messages = session.messages.toList())
}
```

**持久化快照直接读 `uiState.value.chatSessions`。** 它先 `flushPendingAssistantOutput`（把末尾缓冲写进 `messages`），再读 `chatSessions` 落 Room。

于是降频的后果是：**flush 把尾部写进了 `messages`，但 `chatSessions` 还是旧的 → 落盘的会话少掉尾部。** 注意 L64-65 那句注释——"A completion can end between chunks"——**这套代码的既有前提就是 `chatSessions` 必须与 `messages` 同步新鲜**，设计者已经显式处理过这个竞争。

V9 §6.1 那句：

> `chatSessions` 同时服务历史恢复、导出、会话切换、知识绑定、摘要、角色归属、工具续轮、图片流程与失败回滚。**不能按抽屉是否可见决定它的新鲜度。**

不是保守，是**读过了这条链路**。它给的理由里，持久化只是其中一项。

**我把 R6 的收益算成了"性能 vs 延迟"的权衡，实际是"性能 vs 数据完整性"的权衡。这个前提我搞错了，因此把 V9 的正确决定判成了扣分。**

## 2. 第二层错误：我高估了 R6 的绝对成本

我说它是"万恶之源"，暗示省下它能换来大幅性能提升。重新量化：

| 项 | 实测约束 |
|---|---|
| 会话数上限 | `maxSessions = 100`（`ChatSessionPersistenceMapping.kt:26`） |
| `sortedForHistory()` | `sortedWith(compareByDescending{pinned}.thenByDescending{updatedAt})`，n ≤ 100 |
| `chatTitle()` | 4 次 `Regex(...)` 构造（`ChatExportNaming.kt:35,36,51,52`）+ 首条用户消息行扫描；**只扫第一条 USER 消息，不是全量消息** |
| 调用频率 | 每 75ms 一次（13.3 Hz），在 `viewModelScope`（主线程）上 |

n ≤ 100 的排序 + 4 次简单正则编译，单次量级是**几十微秒**，13 Hz 下每秒约 **0.3–1.3 ms**。

**这是一次"轻量但高频的浪费"，不是"能带来数量级收益的结构性瓶颈"。** 真正的热点是 159 字段投影、27 处格式化、JSON 导出、图片解码——这些 V9 都保留了（P02/P06/P07）。

所以 V9 的取舍其实是：**用一个每秒 1ms 级别的浪费，换掉一个可能丢消息的改造**。这个取舍是对的。

## 3. 但 V9 确实漏了一处——这是我认为它"没做到最好"的唯一一点

V9 §6.2 只处理了 `upsertSessionFor` 里的**时钟**问题：

> 拟新增不可变 mutation 输入携带 occurredAt

**它没处理同一个函数里的 `chatTitle()` 每 token 重算。**

`chatTitle()` 只依赖**第一条 USER 消息**，而流式期间追加的是 **ASSISTANT** 消息——**标题在整个流式过程中根本不可能变化**。这意味着一半的重算是纯浪费：

```kotlin
// ChatSessionPersistenceState.kt:53 现状
title = if (existing?.manualTitle == true) existing.title else messages.chatTitle(),
```

**安全且零风险的改法**（不降频、不碰权威性、不违反硬锁 §6）：

```kotlin
title = when {
    existing?.manualTitle == true -> existing.title
    // 已存在且首条用户消息未变 → 标题不可能变，直接复用
    existing != null && firstUserMessageIdOrNull(messages) == firstUserMessageIdOrNull(existing.messages)
        -> existing.title
    else -> messages.chatTitle()
}
```

或更简单：按「首条 USER 消息的内容 hash」记忆化 `chatTitle`。

**收益**：流式期间 4 次 Regex 构造 → 0 次。**成本**：一次 O(1) 的首条用户消息比对。**风险**：零（`chatSessions` 依旧每 token 新鲜）。

这才是真正该做的"结构性小改"——而 V8 的降频、V9 的完全不碰，**两边都没做到最优**：我太激进，V9 太保守。

## 4. 结论

| 问题 | 我的原判断 | 更正后 |
|---|---|---|
| V9 撤销 chatSessions 降频，做得对吗？ | 扣分项，"撤掉了最大收益" | **做得对。** 它保住的是持久化一致性，不是保守 |
| R6 是"万恶之源"吗？ | 是 | **不是。** 它是一次每秒 1ms 级的轻量浪费，不是数量级瓶颈。真正的瓶颈是投影/格式化/JSON/解码 |
| 降频的代价是什么？ | "抽屉最多滞后 1 秒" | **会让落盘会话少掉尾部**（`ChatSessionPersistenceOperations.kt:68` 直读 `chatSessions`） |
| V9 还有改进空间吗？ | —— | **有，且只有一处**：`chatTitle()` 在流式期间不可能是变的，可以零风险记忆化。V9 §6.2 只处理了时钟，漏了这里 |

**更正我上一轮的说法**：V9 在这件事上**做得好**，不是扣分项。它唯一的不足是没把 `chatTitle()` 这笔免费的账收掉——而那笔账比整条降频方案都更安全。
