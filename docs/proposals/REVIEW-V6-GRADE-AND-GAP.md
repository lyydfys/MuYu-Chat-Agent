# V6 方案评审 + MCA 前端真实差距分析

> 审阅对象：`docs/proposals/MCA-GEMINI-GRADE-PATCH-V6.md`（V6.1，1088 行）
> 审阅方式：逐条回读被引用的源文件核对行号与语义；独立做全仓计数复核；对方案给出的示例代码做编译可行性检查。
> 约束前提（来自你的要求，方案也遵守了）：**不改色系、不改设计语言**。本文所有建议同样遵守。
> 本文不含任何推测性数字；每个断言都给出 `文件:行号`。无法用静态证据判断的，明确标注"需实测"。

---

## 0. 一句话结论

**V6 是一份认真的、诚实的、可执行的工程笔记，但不是 10 分 S 级方案。综合 6.5 / 10。**

它的行号引用准确率很高（我抽查 14 条，11 条精确命中），自我否定的态度也罕见（主动否决了"命中即校验版本戳"和"增量状态机"两个错误方向）。它的问题不在态度，在**成本模型**：

- 它把 80% 的篇幅花在**每次重组只省几百纳秒**的地方；
- 而真正每帧都在烧掉可观 CPU 的那几处（**composition 里重建整个 UI 状态树**、**96 个回调的 action bundle 每帧重建两次**、**每个可见消息因 lambda 不稳定而全量重组**），它一个字没提；
- 决定"这是大厂做的"的那几项（**edge-to-edge 适配、深色冷启动白闪、选中色、缺失 token 导致的串色**），它也全部漏掉。

一句话概括差距：**V6 在修"代码写得不讲究"，而旗舰级的差距在"架构没为每帧设计过"。**

---

## 1. 评分卡

| 维度 | 分数 | 依据 |
|---|---|---|
| 事实准确度 | **7 / 10** | 行号命中率高；但有 5 处实质性事实错误（见 §3） |
| 问题诊断深度 | **6 / 10** | 找到了症状（key 含 index、无缓存、无 spring），漏掉了根因（见 §4） |
| 方案可执行性 | **7 / 10** | 改动小、风险低；但至少 2 段示例代码无法编译（见 §2.1、§2.2） |
| 收益判断 | **5 / 10** | 优化了错误的一层；全部收益声明无任何测量支撑 |
| 测试与验证 | **4 / 10** | 反复强调"测试必须可失败"，但自己给的两个测试都不可失败（见 §2.4、§2.5） |
| 体系完整性 | **4 / 10** | 无 a11y、无大字号、无平板/折叠、无 RTL、无深色启动、无 edge-to-edge、无性能基线 |
| **综合** | **6.5 / 10** | **A- 级工程笔记，不是 S 级旗舰方案** |

对比参照：Google/Meta 级别的设计+性能评审，第一页一定是**测量基线**（jank 率、P90 帧时长、冷启动 TTID、TTFD），最后才是修复项。V6 一个测量数字都没有。这不是文风问题——**没有基线的优化清单，本质是猜测**，而"猜测式优化"正是这份文档自己在第四批里批评过的东西。

---

## 2. V6 的六处硬伤

### 2.1 示例代码无法编译：`McaSkeletonBlock` 缺 `Box` import

方案 P1-2 给出的 `McaSkeleton.kt` 完整 import 列表（第 587–606 行）包含 `Column`、`fillMaxWidth`、`height`……**但没有 `androidx.compose.foundation.layout.Box`**，而函数体第 637 行用的是 `Box(...)`。

一个专门为了"修正 V6.0 编译失败"而写的 V6.1，自己在同一节里引入了新的编译失败。这类错误说明示例代码没有真正放进 IDE 编过。

### 2.2 示例代码无法编译：`McaTypography` 从未定义

P2-1 第 875–882 行写：

```kotlin
MaterialTheme(
    colorScheme = colorScheme,
    typography = McaTypography,     // ← 全文档没有任何地方定义 McaTypography
    shapes = MaterialTheme.shapes, // ← 读 Ambient 再传回同一个 Ambient，空操作
    content = content
)
```

`McaTypography` 在整份 1088 行文档里从未被定义（第 858–871 行只定义了 `McaSpacing` / `McaShapes`）。照抄即编译失败。而 `shapes = MaterialTheme.shapes` 是纯粹的自我引用空操作——写成这样说明作者知道该传，但没想清楚传什么。

### 2.3 P0-5 的前提是错的：`MainViewModel` 根本不在主线程构造

方案第 424 行断言：

> 如果 `MainViewModel` 在主线程构造，这就是一次主线程磁盘阻塞——首帧卡顿的直接来源。

**它不在主线程构造。** 实际代码：

```kotlin
// app/src/main/java/com/muyuchat/mca/AppStartupViewModel.kt:73-75
withContext(Dispatchers.IO) {
    prepared = MainViewModel(app, deferStartup = true, skipInitialModelDiscovery = skipModelDiscovery)
}
```

`MainActivity.kt:45` 只是 `get() = requireNotNull(startup.state.value.model)`，纯读取。所以 `MainViewModel.kt:71` 和 `:74` 的两处 `runBlocking` 阻塞的是**一个 IO 线程**，不会造成任何主线程卡顿、不会影响首帧。

结论：`runBlocking` 该改（阻塞协程线程池是坏味道），但方案给出的收益归因（"首帧卡顿的直接来源"）是**编造的**，而它又把这条列为"本批中唯一需要跨文件追踪的改动、风险中等"——用一个虚假收益换一个真实风险。这条应该降级到 P3，或者干脆不改。

### 2.4 P0-4 的 `scrollOffset` 符号反了

方案第 386 行：

```kotlin
listState.animateScrollToItem(target, scrollOffset = -bottomChromeHeightPx)
```

Compose 的 `scrollOffset` 语义是"**正向**滚动"，即正值把目标 item 往**上**推（推出视口顶部）。你要的效果是"让最后一条消息停在输入栏上方、不要被遮住"，需要把 item 往上推 → 应该用**正值** `+bottomChromeHeightPx`。

用负值的效果是把 item 往**下**推，配合 `contentPadding.bottom = 118.dp` 的余量，最后一条会贴到视口最底部甚至被输入栏盖住——**恰好是它想修的那个 bug**。

### 2.5 P0-1 的验证测试不可失败

方案第 100 行：

> 新增单测，构造 100 条消息 → 删除中间第 50 条 → 断言剩余 99 条的 `id` 集合不变。

**这个断言在任何实现下都成立**——`id` 是 `val`，删除一条消息不会改变其它消息的 `id`。无论 key 用 index 还是 id，这个测试都绿。它验证的是"删除不会改 id"这个 Kotlin 语言事实，不是 key 的稳定性。

而方案在开篇第 9 行立的规矩是："所有测试断言都必须具备可失败性（mutation 后必须报红）"。自己第一个测试就违反了。

**真正可失败的验证**：把 key 换回含 index 的版本，测试必须红。可以做的是把 key 提取成 `internal fun chatMessageListKey(index: Int, message: ChatMessage): Any`，然后断言 `chatMessageListKey(0, m) == chatMessageListKey(5, m)`——mutation 把 index 加回去，立即红。

### 2.6 P0-2 的 `derivedStateOf` 解释是错的（代码能用，但理由是错的）

方案第 117–131 行：

```kotlin
val lastAssistantIndex by remember(state.messages) {
    derivedStateOf { state.messages.indexOfLast { it.role == Role.ASSISTANT } }
}
```

然后解释："`derivedStateOf` 会缓存结果，只有依赖（`state.messages`）引用变化时才重算。"

**这是对 `remember(key)` 的描述，不是对 `derivedStateOf` 的描述。**

`derivedStateOf` 的失效条件是"块内读取的 **snapshot State** 发生变化"。这里块内读的是 `state.messages`——一个普通 data class 字段，**不是 snapshot State 读取**。所以这个 `derivedStateOf` 内部追踪不到任何依赖，永不主动失效；它之所以能拿到新值，纯粹是因为外层 `remember(state.messages)` 在引用变化时**重建了整个 derivedStateOf 对象**。

净效果：`derivedStateOf` 在这里**没有任何作用**，等价于直接 `remember(state.messages) { state.messages.indexOfLast {...} }`。方案为一个不存在的机制写了 15 行解释。

（顺带：`indexOfLast` 在 `LazyListScope` 的 content lambda 里，只在 LazyColumn 重组时跑；`assistants.any` 在 item lambda 里，是 O(可见项 × 助手数)，不是方案说的 O(n²)。方向对，量级说错了。）

---

## 3. 五处事实错误汇总

| # | 方案断言 | 实际 | 影响 |
|---|---|---|---|
| 1 | P0-5：`runBlocking` 造成主线程首帧阻塞 | 构造发生在 `Dispatchers.IO`（AppStartupViewModel.kt:73） | 收益归因错误，风险被低估 |
| 2 | P1-2：内部 `.fillMaxWidth()` 会覆盖外部 `fillMaxWidth(0.62f)` | Modifier 链外层优先，`fillMaxWidth(0.62f).fillMaxWidth()` 结果仍是 62% | 修了一个不存在的 bug |
| 3 | P0-4：`scrollOffset` 用负值把消息抬到输入栏上方 | 正值才是往上推 | 照抄会重现原 bug |
| 4 | P0-2：`derivedStateOf` 按引用缓存 | 该处 derivedStateOf 无依赖可追踪，等同 remember | 解释错误，代码冗余 |
| 5 | 第四批："流式期间两者长度几乎相同" | `safePrefix` 在 >16384 字符时截断，长回答流式时两者**必然不同**，快路径不命中；短回答时命中但 O(n) 本来就小 | 收益定位自相矛盾 |

第 5 点值得展开，因为它是方案第四批的立论基础：

```kotlin
// ChatMarkdownBlockRenderers.kt:62-68
internal fun PagedAssistantRichText(content: String, modifier: Modifier = Modifier) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val visibleContent = remember(content, visibleCharacters) { content.safePrefix(visibleCharacters) }
    Column(...) { AssistantRichText(visibleContent, fullContent = content) }
}
```

`safePrefix` 在 `length <= maxCharacters` 时**直接返回 this**（`ChatMarkdownRenderSupport.kt:298`）。所以：

- 回答 < 16384 字符：`visibleContent === content`，快路径命中——但此时全文本来就短，省下的一次扫描是微秒级；
- 回答 > 16384 字符：`visibleContent` 是截断串，**永远不等于** `fullContent`，快路径**永不命中**——而长回答恰恰是唯一值得优化的场景。

**结论：这条优化的收益恰好出现在不需要它的地方，在需要它的地方完全不生效。** 方案自己在第 1047 行把它排到最低优先级、说"不要为了这一条去写 400 行状态机"——态度是对的，但整节 150 行的推演建立在一个反了的前提上。

---

## 4. V6 完全漏掉的、真正决定差距的 8 件事

以下按**真实每帧收益**降序。全部是我独立核验过的。

### 4.1 【最大项】`buildChatUiState` 在 composition 里每帧重建整个 UI 状态树

```kotlin
// app/src/main/java/com/muyuchat/mca/MainActivity.kt:243-248
ChatRoute(
    state = buildChatUiState(        // ← 没有 remember，直接写在 composable 参数位置
        state = state, sessionPresentations = sessionPresentations, ...
    ),
```

`buildChatUiState` 把 144 字段的 `MainUiState` 映射成 159 字段的 `ChatUiState`（`ChatScreenHeadTypes.kt:17`），内部至少 10 处 `map` / `buildList` / `associate`（`ChatUiStateMapping.kt`）。而 `MainUiState` 在流式生成时以 **`CHAT_STREAM_PUBLISH_INTERVAL_MS = 75ms`** 的节奏发射（`ChatStreamAccumulator.kt:14`），即 **13 次/秒**。

其中三处是纯浪费：

**(a) 每个助手每帧做一次 pretty-print JSON 序列化**

```kotlin
// app/src/main/java/com/muyuchat/mca/ChatUiStateHistoryMapping.kt:64
exportJson = assistant.toJson().toString(2),
```

`toJson()` 构造 20 个字段的 `JSONObject`（`AssistantStore.kt:39-58`），`.toString(2)` 再把它缩进美化。而 `exportJson` 只在这两个地方被读：

```kotlin
// ChatScreenAssistantRolePages.kt:134 和 :180
clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCA assistant", item.exportJson)))
```

即**用户点"复制角色卡"时**。为了一个点击事件，每帧给每个助手做一次 JSON 序列化 + 缩进。有 10 个助手就是 10 次/帧。

**(b) 每个会话每帧 new 一个 `SimpleDateFormat`**

```kotlin
// ChatUiStateHistoryMapping.kt:26-27
updatedAtText = java.text.SimpleDateFormat("MM-dd HH:mm", timestampLocale)
    .format(java.util.Date(session.updatedAt)),
```

`SimpleDateFormat` 构造是出了名的贵（解析 pattern、建 `DateFormatSymbols`）。同样的写法还出现在 `ChatUiStateMapping.kt:314`（每张图）、`ChatUiStateImageRuntimeMapping.kt:73`（每个文件）。历史会话多的时候，每帧构造几十个 formatter。

**(c) 每张图每帧重建 `toShareJson()`**

```kotlin
// ChatUiStateMapping.kt:319
parameterShareJson = sourceGeneration?.toShareJson().orEmpty(),
```

`toShareJson()` 构造一个含嵌套 `JSONArray` 的 `JSONObject`（`ImageGenerationMetadataQueries.kt:82+`），只为"分享参数"这个低频动作。

**修法（零风险、纯收益）**：

```kotlin
val chatUiState = remember(
    state.messages, state.chatSessions, state.activeChatSessionId, state.assistants,
    state.selectedAssistantId, state.localModels, state.images, state.imageJobs,
    /* …只列真正被消费的字段… */
) { buildChatUiState(...) }
```

并把 `exportJson` / `parameterShareJson` 从"每帧构造的 UI 模型字段"改成**按需计算**：UI 里存 `assistantId`，复制时再 `viewModel.assistantExportJson(id)`。这是 Gemini/ChatGPT 那种规模的应用一定会做的分层——**UI 模型只装"渲染需要的东西"，不装"导出需要的东西"**。

预期收益：生成期间每帧省下**数毫秒**，且随会话数/图片数线性增长。这比 V6 全部 P0 项加起来都大。

### 4.1b 【同级别】96 个回调的 action bundle 每帧重建两次

这条和 4.1 是同一个病根的第二个症状，但更隐蔽，也更容易被误认为"已经优化过"。

```kotlin
// app/src/main/java/com/muyuchat/mca/MainActivity.kt:259-266
actions = mainChatScreenActions(     // ← @Composable 函数，每帧执行
    viewModel = viewModel, state = state, ...
),
```

```kotlin
// app/src/main/java/com/muyuchat/mca/MainChatScreenActions.kt:32-46
@Composable
internal fun mainChatScreenActions(...): ChatScreenActions = ChatScreenActions(
    conversation = ChatConversationActions(onClearHistory = viewModel::clearChatHistory, /* 17 项 */),
    generation   = ChatGenerationActions(/* 11 项 */),
    content      = ChatContentActions(/* 15 项 */),
    image        = ChatImageActions(/* 36 项 */),
    shell        = ChatShellActions(/* 17 项 */),
)
```

我数过：**96 个字段，分 5 个嵌套 data class**（`ChatScreenActions.kt:12,33,48,67,107`）。这个函数是 `@Composable` 但**没有 `remember`**，所以每次 `McaApp` 重组都会：

1. 新建 5 个 data class 实例 + 96 个方法引用/lambda 实例；
2. 因为实例 identity 变了，进入 `ChatViewModel.update`：

```kotlin
// feature/chat/src/main/java/com/muyuchat/feature/chat/ChatViewModel.kt:76-79
if (this.capabilities !== capabilities) {   // ← 每帧都为 true
    this.capabilities = capabilities
    _screenActions = buildScreenActions()   // ← 又新建 96 个方法引用
}
```

3. `_screenActions` identity 变化 → `ChatScreen(actions = ...)` 参数变化 → **整个 ChatScreen 及其全部子组合无法跳过**。

净成本：**每帧 192 个对象分配**（96 个 lambda + 96 个方法引用），外加让"跳过重组"这条最基础的优化全线失效。

这段代码的作者是**有意识地**用 `!==` 做 identity 比较的（注释还解释了为什么），但**它假设了调用方会传稳定实例，而调用方从来没传过**。这是一个"看起来做了优化、实际每帧都在全量重建"的典型陷阱——比完全没写优化更糟，因为它让人以为这里不用管。

**修法**（~10 行）：

```kotlin
val actions = remember(viewModel) { mainChatScreenActions(...) }
```

但 `mainChatScreenActions` 捕获了 `state`（用于几个条件回调），所以需要先把那几处依赖 `state` 的 lambda 改成 `rememberUpdatedState` 或改成从 viewModel 内部读，才能安全 remember。稳妥顺序：

1. 先把 `mainChatScreenActions` 里读 `state.xxx` 的 lambda 改为在 `MainViewModel` 内部读（它本来就持有 uiState）；
2. 再 `remember(viewModel) { ... }`；
3. `ChatViewModel.update` 里的 `!==` 就能真正生效，不再每帧重建。

### 4.2 【第二大项】每个可见消息在每次流式更新时全量重组

```kotlin
// ChatMessageListSection.kt:79-82
itemsIndexed(items = state.messages, key = { index, message -> "${message.role.name}:${message.createdAt}:$index" })
{ index, message ->
    ...
    MessageBubble(
        ...
        onRetryChatImage = { generatedImageRequest?.let { onRetryChatImageRequest(it.id) } },
        onCancelChatImage = { ... },   // ← 每次都是新 lambda 实例
        ...
    )
}
```

`MessageBubble` 有 **29 个参数**（`ChatMessageBubbleScreens.kt:59-88`），其中 **7 个是调用点内联新建的 lambda**（`ChatMessageListSection.kt:117,120,123,126,129,159,160`）。Compose 跳过重组的条件是"所有参数 `equals`"；lambda 每次捕获新建实例，`equals` 必然 false → **无法跳过**。

（其余 22 个参数里，`onOpenBrowserTask` / `onRegenerate` 等是方法引用，identity 稳定；真正致命的是那 7 个内联的——尤其 `onDelete = { onDeleteMessage(index) }` 和 `onTogglePinned = { onTogglePinMessage(message.id) }`，它们捕获了 `index` / `message`，每帧都是新对象。）

再叠加：`state` 每 75ms 换一次 identity，`ChatScreen` 整个重组 → LazyColumn 重组 → 每个可见 item 的 lambda 重建 → `MessageBubble` 全部重组。一屏 8 条消息 × 13 次/秒 = **每秒 104 次完整消息重组**，每次都要重新跑 `parseMarkdownBlocks`（`ChatMarkdownBlockRenderers.kt:84` 的 `remember(content, fullContent)` 会拦住，但 `remember` 的 key 比较、`Surface`/`Column` 的结构比较仍然要走）。

**这正是"越用越卡"的真正根因**，不是 V6 说的 key 含 index。key 含 index 只在**插入/删除**时爆，而流式重组是**每帧**在爆。

**修法**（按投入产出排序）：

1. **把 lambda 提升为 `remember` 过的稳定引用**，或改为"传 id + 一个 `onEvent: (ChatMessageEvent) -> Unit` 单入口"，让 lambda 成为常量。
2. **把 `MessageBubble` 的参数收窄**：现在它吃 `state.conversationReadOnly`、`state.assistants.any{...}` 等，改为调用方算好一个 `MessageRenderModel` 传进来。
3. **给 `ChatMessage` / `ChatUiState` 加 `@Immutable`**（全仓 `@Immutable|@Stable` 只有 2 处，`ImagePromptEditHistory.kt:80`、`ImagePromptTagAutocompleteSession.kt:76`），或配 `stabilityConfigurationFile`。Kotlin 2.x 默认开 strong skipping，但它救不了"参数里塞了新 lambda"这种情况。

### 4.3 【最显眼的"不像大厂"】没有 edge-to-edge，targetSdk 36

全仓计数（我自己跑的，`core` + `feature` + `app/src/main`，排除 build/test）：

```
enableEdgeToEdge          : 0
setDecorFitsSystemWindows : 0
statusBarsPadding         : 0
systemBarsPadding         : 0
safeDrawing               : 0
```

而 `app/build.gradle.kts` 里 `targetSdk = 36`（`gradle/libs.versions.toml:7`）。**Android 15（API 35）起，targetSdk ≥ 35 强制 edge-to-edge**：应用被强制画到状态栏和导航栏底下，系统不再自动加 inset。

后果：在 Android 15/16 手机上，`ChatStatusBar` 会**顶到状态栏里**，被时钟、信号、电池图标压住。这不是"不够精致"，这是**在最新旗舰机上直接错版**——而 Gemini/ChatGPT 早就是 edge-to-edge + `WindowInsets` 分层。

`MainActivity.kt:233-234` 是 `Scaffold { padding -> Box(Modifier.padding(padding)) }`，而 `Scaffold` 的 `contentWindowInsets` 用的是默认值，`ChatScreen` 内部又自己 `imePadding()`（`ChatScreen.kt:319`）——**inset 责任被拆给平台 theme、Scaffold、Compose 三方，没有唯一 owner**。

**修法**：`enableEdgeToEdge()` + `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`，`ChatStatusBar` 加 `statusBarsPadding()`，输入栏用 `navigationBarsPadding()`（`ChatComposerScreens.kt:268` 已有）。零色值改动。

### 4.4 【第一印象】深色冷启动白闪

```
app/src/main/res/values/styles.xml:2
<style name="Theme.MuYuChatAgent" parent="android:style/Theme.Material.Light.NoActionBar">
    <item name="android:navigationBarColor">#FAFAFA</item>
    <item name="android:statusBarColor">#FAFAFA</item>
```

- `parent` 写死 **Light**
- 状态栏/导航栏色写死 **`#FAFAFA`**
- 全仓**没有 `values-night/` 目录**

而 `McaTheme.kt:73-81` 是在 `SideEffect` 里改系统栏颜色的——`SideEffect` 在**首帧组合之后**才跑。

所以深色模式用户冷启动会看到：**白色启动窗口 → 白色状态栏 → 白闪一帧 → 突然变黑**。这是用户对"档次"下判断的第一个 0.5 秒。

Gemini 和 ChatGPT 都有：`androidx.core:core-splashscreen`、`windowSplashScreenBackground` 跟随深色、`values-night` 完整主题。

**修法**（不改色系，只是把已有的深色值补到 XML）：
- 新建 `values-night/styles.xml`，`parent="android:style/Theme.Material.NoActionBar"`，`windowLightStatusBar=false`，状态栏色 `#121212`、导航栏色 `#202124`（**都是 `McaTheme.kt:54,56` 里已有的值**）；
- 可选：接 `core-splashscreen`，`windowSplashScreenBackground` 分别指向 `#F8F9FA` / `#121212`（同样是已有值）。

### 4.5 【串色】`outlineVariant` 从未声明，23 处边框在用 M3 默认紫色

```kotlin
// McaTheme.kt:40（Light）  只有 outline = Color(0xFFDADCE0)
// McaTheme.kt:60（Dark）   只有 outline = Color(0xFF5F6368)
```

全仓 `outlineVariant` 出现 **23 次**（我自己数的），但 `McaTheme.kt` **从未定义 `outlineVariant`**。Material3 的 `lightColorScheme()` / `darkColorScheme()` 会填默认值：

- Light 默认 `outlineVariant = #CAC4D0`（紫灰）
- Dark 默认 `outlineVariant = #49454F`（紫灰）

而你的色系是 Google 蓝灰（`#DADCE0` / `#5F6368`）。结果：**同一个应用里，边框画在两种灰上——一种是你选的蓝灰，一种是 Material 默认的紫灰。** 在 `#F3F7FF` 这种偏蓝的输入框上，`#CAC4D0` 的边框会明显偏紫。

这条特别值得强调：**你的约束是"不改色系"，而代码现在正在违反它**。补齐 `outlineVariant`（用你已有灰阶的相邻值，如 `#E8EAED` / `#3C4043`）不是改色系，是**把色系修回来**。

同类未声明 token：`errorContainer` / `onErrorContainer`（`ModelHubSharedComponents.kt:54-55` 在用，同样落到 M3 默认）。

### 4.6 【每次都看到】文字选中色是 Material 紫

全仓 `LocalTextSelectionColors` / `TextSelectionColors` = **0**。

用户长按选中助手回答里的任何一段文字，选中高亮是 Material3 默认的**紫色**——和你整套 Google 蓝完全不搭。这是高频、可见、且修复只要 6 行：

```kotlin
CompositionLocalProvider(
    LocalTextSelectionColors provides TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    )
) { content() }
```

包在 `McaTheme` 里。零新色。

顺带一个不一致：助手文本有 `SelectionContainer`（`ChatBubbleMessageBlocks.kt:183,196`），**用户气泡文本没有**（`ChatMessageBubbleScreens.kt:162-309` 里没有）。用户想复制自己发过的话只能重新打字——这是真实的功能缺口。

### 4.7 【深色模式破了】代码块和发送按钮在深色下失效

```kotlin
// ChatMarkdownBlockRenderers.kt:217-218
Surface(color = Color(0xFF1F2937), ...)   // 两种主题都用同一个深色
```

深色模式下：代码块 `#1F2937` on 背景 `#121212` = **1.28:1**。几乎看不见边界，代码块变成一个"洞"。

```kotlin
// ChatComposerScreens.kt:447
containerColor = McaPrimaryBlue,   // #3F7DE8 固定
```

深色模式下 `#3F7DE8` on `#202124` = **4.07:1**，而 `McaTheme` 的深色 `primary` 是 `#8AB4F8`（8.89:1）。发送按钮无视了深色主题的 primary。

这两处都不是"设计风格"问题，是**深色模式没做完**。

### 4.8 【合规线】触控目标低于 48dp

19 个 `IconButton` 尺寸小于 44dp。最极端的：

| 控件 | 尺寸 | 位置 |
|---|---|---|
| 历史行溢出菜单 | **26.dp** | `ChatHistoryDrawerRows.kt:156` |
| 输入栏生图模式关闭 | **28.dp** | `ChatComposerScreens.kt:320` |
| 消息复制/重生成/更多 | **32.dp** ×3 | `ChatAssistantActionViews.kt:110,122,134` |
| 用户气泡复制/固定 | **32.dp** ×2 | `ChatMessageBubbleScreens.kt:287,297` |

Android 无障碍下限是 48dp。`IconButton` 默认就是 48dp，这些是**主动改小**的。配合"全仓 0 触觉反馈"，这是手感上"不像原生应用"的物理原因。

---

## 5. 我认为该怎么做：重排后的优先级

V6 的执行顺序（第 1055–1069 行）把触觉、derivedStateOf、key 排前面。我的顺序不同，因为**收益量级不同**。

### 第一梯队：改动小、收益大、零视觉风险（约 120 行，1–2 天）

| # | 项 | 改动 | 收益 | 来源 |
|---|---|---|---|---|
| 1 | `remember` 包住 `buildChatUiState` + 把 `exportJson`/`parameterShareJson` 改为按需计算 | ~20 行 | **生成期间每帧数毫秒**，会话越多收益越大 | 本文 §4.1（V6 漏） |
| 1b | `remember` 包住 `mainChatScreenActions`（96 回调 × 每帧两次） | ~10 行 | **每帧省 192 个对象分配，并恢复全局跳过重组** | 本文 §4.1b（V6 漏） |
| 2 | `enableEdgeToEdge()` + `WindowInsets.safeDrawing` | ~15 行 | **修 Android 15/16 顶栏被状态栏压住** | 本文 §4.3（V6 漏） |
| 3 | `values-night/styles.xml` | ~12 行 | 修深色冷启动白闪 | 本文 §4.4（V6 漏） |
| 4 | 补 `outlineVariant` / `errorContainer` / `onErrorContainer` | ~6 行 | 消除全应用紫灰串色 | 本文 §4.5（V6 漏） |
| 5 | `LocalTextSelectionColors` + 用户气泡加 `SelectionContainer` | ~10 行 | 去掉 Material 紫选中色；补功能缺口 | 本文 §4.6（V6 漏） |
| 6 | 深色代码块 + 深色发送按钮 | ~4 行 | 修 1.28:1 / 4.07:1 | 本文 §4.7（V6 漏） |
| 7 | 触觉反馈（V6 P1-3 原样采用） | ~40 行 | 感知收益最高 | V6 P1-3 ✅ |
| 8 | 删除 `onSurfaceVariant` 上叠的 0.72/0.74/0.78/0.82 alpha | ~6 行 | 6 处 AA 不达标 → 达标 | 视觉审计 §11.5 |

### 第二梯队：手感与重组（约 200 行，3–5 天）

| # | 项 | 收益 |
|---|---|---|
| 9 | 消息 item 的 lambda 稳定化 / 单入口事件回调 | **根治流式卡顿**（§4.2） |
| 10 | `@Immutable` 或 stability config | 让 Compose 能跳过 |
| 11 | key 去 index（V6 P0-1，但要配**可失败**的测试） | 插入/删除不丢状态 |
| 12 | 滚动意图感知 + 回到最新按钮（V6 P0-4，**修正 offset 符号**） | 修最烦人的行为缺陷 |
| 13 | 图片内存缓存（V6 P0-3，**但预算要降**：`maxMemory/16`、上限 48MB、加 `onTrimMemory` 清理） | 图片会话回滚不掉帧 |
| 14 | 修 2 处死掉的 `AnimatedVisibility(visible = true)`（V6 P1-1，正确） | 恢复作者已写好的动效 |
| 15 | `spring()` 替换用户触发的 `tween`（V6 P1-1，正确） | 物理手感 |
| 16 | 发送按钮按压缩放 + 发送↔停止交叉淡入 | 最高频控件变"物理" |
| 17 | `Modifier.animateItem` | 插入/删除不跳 |
| 18 | 触控目标补到 44–48dp | 少误触、更像原生 |

### 第三梯队：体系与观感（约 300 行，1 周）

| # | 项 | 说明 |
|---|---|---|
| 19 | 一个真正的 `Typography` + 删除 73 处 `fontSize=` | 最大单点质量跃升，但影响面大，必须逐模块截图比对 |
| 20 | 空态改为 Logo + 问候 + 建议 chip（V6 P1-4，正确） | 唯一能放品牌的地方 |
| 21 | 启动页（V6 P1-5，正确，但要先修 §4.4 的白闪） | 第一印象 |
| 22 | 骨架屏（V6 P1-2，**修 Box import**） | 替代裸转圈 |
| 23 | 流式光标 / 首 token 指示 | 生成"活着"的感觉 |
| 24 | Baseline Profile + Macrobenchmark | 冷启动与帧率 |
| 25 | 大字号（200%）与平板/折叠适配 | 企业级合规 |

---

## 6. 怎么证明改对了（V6 完全没有的部分）

没有这一步，上面所有数字都只是我的判断。企业级评审必须能给出**改前/改后同一台机器上的对比**。

**必须建立的四条基线**（都是 Android 官方工具，不需要新依赖）：

1. **帧时长 / jank**
   `Macrobenchmark` + `FrameTimingMetric`，跑"打开 50 条消息的会话 → 发送 → 生成 500 token → 滚动"。看 `frameDurationCpuMs` 的 P50/P90/P99 和 `frameOverrunMs`。
   *验收线*：生成期间 P90 < 16.7ms，P99 < 33ms。

2. **重组计数**
   Compose Compiler 报告（`composeCompiler { reportsDestination }`）+ Layout Inspector 的 recomposition count。
   *验收线*：流式期间，非最后一条消息的 item 重组次数 = **0**。这是 §4.2 是否真修好的唯一硬指标。

3. **冷启动**
   `StartupTimingMetric`（TTID / TTFD），浅色 + 深色各跑一次。
   *验收线*：深色首帧不再是白色（截图比对），TTID 不退化。

4. **内存**
   `MemoryProfiler` 或 `dumpsys meminfo`，在图片会话里滚动 20 次。
   *验收线*：Java heap 稳定不增长；图片缓存命中后 `BitmapFactory.decodeStream` 调用次数不增加（可加计数埋点）。

**还有一条最便宜的**：把这四项做成一个 `:benchmark` 模块，进 CI。没有 CI 的门禁，任何"优化"都会在三个月后被下一次重构悄悄抵消——这也是"自己做的"和"大厂做的"在**流程**上的差别。

---

## 7. 明确不要动的（V6 判断正确，我同意）

1. **助手消息全宽无气泡**（`ChatBubbleMessageBlocks.kt:121-130`，`Surface(color = Color.Transparent, shape = RoundedCornerShape(0.dp))`）。这是现代正确做法，和 Gemini 一致。包成卡片是倒退。
2. **用户气泡 20/6 圆角**（`ChatMessageBubbleScreens.kt:217-222`）。不对称尾巴是有辨识度的签名。
3. **`CircleShape` 输入栏**（`ChatComposerScreens.kt:260`）。形状对，只有 14dp 阴影该降到 2dp + 1dp 描边。
4. **`McaLogoMark`**（`McaBrandMarks.kt:267-315`）——纯 `Canvas` 手绘气泡 + 四角星 + 三点高光，能干净缩放到 12–66dp。这是全项目最好的资产，问题只是**用得太小太少**。
5. **`AnimatedMcaAssistantMark`**（`McaBrandMarks.kt:318-358`）——1300ms 线性环，蓝弧 + 绿弧。全项目最有"设计感"的动效资产。保留；可以拿来当首 token 指示器。
6. **表格横向滚动渐隐**（`ChatMarkdownBlockRenderers.kt:325-338`）——带 `canScrollForward` 门控的**功能性**边缘渐隐，还写了注释说明它是为了提示截断而非装饰。这个直觉应该复制到消息列表边缘。
7. **代码块 chrome**（`ChatMarkdownBlockRenderers.kt:217-275`）——语言标签 + 复制按钮 + 1400ms 确认态 + `FontFamily.Monospace`，和 ChatGPT 同级。只有深色背景色要改。
8. **分页渲染**（`MESSAGE_RENDER_PAGE_CHARS = 16_384`，`ChatMarkdownRenderSupport.kt:313`）。这是真实的性能决策。
9. **预测式返回**——`SystemBackMotionHandler`（`BackMotion.kt:26-67`）接到 `SwipeBackPage` 和 `MainActivity`，还处理了 IME 可见性。大多数应用没做。
10. **`McaInputField #F3F7FF` / `McaInputText #1F2937`**——13.67:1 对比度，舒服的输入面。保持。

---

## 8. 对 V6 逐批的判决

| 批次 | 正确性 | 价值 | 判决 |
|---|---|---|---|
| **P0-1** key 去 index | 结论对，测试不可失败 | 中 | **采纳，但重写测试**（§2.5） |
| **P0-2** derivedStateOf | 代码可用，理由是错的 | 低 | **简化为 `remember`**（§2.6） |
| **P0-3** 图片缓存 | 诊断对，代码基本可编译 | 中高 | **采纳，但降预算 + 加 `onTrimMemory`**（§5 第 13 项） |
| **P0-4** 滚动 | 方向对，**offset 符号错** | 中 | **采纳，改符号 + 加用户意图判断**（§2.4） |
| **P0-5** runBlocking | **前提错误** | 低 | **降级到 P3 或不做**（§2.3） |
| **P1-1** 动效 | 正确（含对死动画的识别） | **高** | **全采纳** |
| **P1-2** 骨架屏 | **缺 import，且修了个不存在的 bug** | 中 | **采纳，补 import，删掉"参数化 widthFraction"的伪修复**（§2.1、§3-2） |
| **P1-3** 触觉 | **完全正确**（API 30 判断、去 flag、语义选择都对） | **最高** | **原样采纳，这是全文最好的一节** |
| **P1-4** 空态 | 正确 | 中高 | 采纳 |
| **P1-5** 启动页 | 正确 | 中 | 采纳，**但必须先修深色白闪**（§4.4） |
| **P2-1** token 收口 | 方向对，**`McaTypography` 未定义** | 长期 | 采纳，但要真正定义类型表 |
| **P2-3** Baseline Profile | 正确 | 中高 | 采纳，补 macrobenchmark 配置 |
| **第四批** 单次切分 | **立论前提反了** | 极低 | **不做**（§3-5） |

---

## 9. 最终判断

**V6 不是 10 分 S 级方案。它是 6.5 分的 A- 级工程笔记。**

它做对的事：行号真实、主动自我否决、把"测试必须可失败"写成规矩、P1-3 触觉一节堪称范本、结尾"剩下的差距不在技术而在内容密度"的判断是准确的。

它做错的事，可以归结为一句：**它用 grep 找到了"不讲究的写法"，但没有找到"为每帧设计过的架构"。**

- `derivedStateOf` 命中 0、`spring()` 命中 0、`LruCache` 命中 0 —— 这些 grep 能查出来；
- `buildChatUiState` 没包 `remember`、`mainChatScreenActions` 每帧重建 96 个回调、`MessageBubble` 的 7 个内联 lambda 导致永不跳过、`exportJson` 每帧做 pretty-print JSON —— 这些 grep 查不出来，必须**读懂数据流**才能发现；
- edge-to-edge / 深色白闪 / 选中色 / 缺失 token —— 这些 grep 也查不出来，必须**在真机上按用户路径走一遍**。

而这三类里，第二类和第三类恰恰是用户**能直接感觉到**的。第一类（不讲究的写法）用户基本感觉不到。

所以我的建议是：**V6 里 P1-1、P1-3、P1-4、P1-5、P0-1、P0-3、P0-4 照做**（修正 §2 的错误），**P0-2、P0-5、第四批不要做**，然后把省下的时间全部投到 §4 的 8 项和我 §5 的第一梯队——那才是"从自己做的到大厂做的"真正跨过去的地方。

最后一句直说：**你的设计骨架是对的**（无气泡助手、20/6 气泡、圆形输入栏、手绘 Logo，这四个判断都比大多数同类应用正确）。差的不是设计，是**执行层的最后一公里**——而那一公里里，最贵的不是动画和骨架屏，是**每帧不浪费**和**每个系统边界都处理干净**。
