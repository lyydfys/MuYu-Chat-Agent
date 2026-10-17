# MCA 旗舰级质感与性能落地手册 (V6.1)

> **对标基准**：Google Gemini Android App / OpenAI ChatGPT Android App
> **约束铁律**：**不改任何色值，不改设计语言。** `McaTheme.kt` 的 Light/Dark `ColorScheme`、`ChatColorTokens.kt` 的 7 个 token、用户气泡 20/6 圆角、助手全宽无气泡、`CircleShape` 输入栏、`McaLogoMark` 手绘标记——全部原样保留。
>
> **本文档与前序 V5 方案的根本区别**：
> - 每一条都基于本仓库**实测代码行号**，不假设任何不存在的基类。
> - 每一条都给出**为什么**、**改哪一行**、**如何验证**。
> - 所有测试断言都必须具备**可失败性**（mutation 后必须报红）。
>
> ---
>
> ## V6.1 修订记录
>
> V6.1 修正了 V6.0 中的 **5 处代码级缺陷**，其中 2 处会导致功能静默失效、3 处会导致编译失败或测试恒真。
>
> | # | 位置 | V6.0 的缺陷 | 严重性 | V6.1 处理 |
> |---|---|---|---|---|
> | 1 | P1-1 / P1-5 | `AnimatedVisibility(visible = true)` 首次组合即 `true`，`enter` 动画永不播放 | **功能静默失效** | 新增 `McaEnterAnimationBox`，用 `MutableTransitionState(false).apply { targetState = true }` 驱动 |
> | 2 | P1-2 | `McaSkeletonBlock` 内部无条件 `.fillMaxWidth()` 覆盖外部 `0.62f`；另有 3 行 `Modifier.let` 残渣 | **功能静默失效** | 参数化为 `widthFraction: Float = 1f`，清除残渣 |
> | 3 | P1-3 | `HapticFeedbackConstants.CONFIRM` 自 API 30 引入，`minSdk = 26` 下需降级 | **运行时无响应** | 增加 API 判断，回退到语义更贴切的 `KEYBOARD_TAP` |
> | 4 | P0-3 | `rememberChatImageBitmap` 里有两行死代码 `if (stamp.isEmpty()) return@LaunchedEffect; return@LaunchedEffect` | 逻辑不通 | 见下方说明：**不采用「每次命中都校验版本戳」的方案** |
> | 5 | 第四批 | `parsePlainMarkdownBlocks` 是 `private`，跨文件不可用；且原设计对 `Code` 段二次解析 | **编译失败** | 改为 `parseMarkdownBlocksOnce` 单次切分快路径，收益点重新定位 |
>
> **关于第 4 项的独立判断**：
> 曾有一个修订建议是在缓存命中后于 IO 线程重算 `versionStamp` 并与缓存中的比对，不一致则重解码。这个方向被**明确否决**，理由是它破坏了缓存的核心不变量：
>
> 1. 缓存的价值是「命中即不干活」。命中后仍无条件 `withContext(IO) { contentResolver.query(...) }`，意味着滚动列表时每个 item 进入组合都触发一次跨进程查询 —— 这比没有缓存更糟。
> 2. 比对会引入新的失效路径：`revisionKey` 变化（App 内编辑图片）时 `remember` 会重建 state，但缓存中的旧 `versionStamp` 可能导致提前 return，**跳过本该发生的重解码**。
> 3. 「同一 URI 被外部覆盖编辑」在 Android 上无法可靠检测（`DOCUMENT_ID` 可能变也可能不变）。
>
> **正确分工**：`revisionKey`（由调用方显式控制的信号）负责 App 内的失效；`resolveVersionStamp` 只用于**主动失效检查点**（App 回到前台、图片编辑完成），不参与每次命中校验。修正后的实现见 P0-3。
>
> **关于第 5 项的独立判断**：
> 原 V6.0 第四批的核心论断——「利用 `ChatMarkdownSource.Code.closed` 跳过尾部未闭合代码块的 AST 解析」——方向是对的，但收益点定位错了。看 `ChatMarkdownBlockParser.kt:55-67` 的实际结构：
>
> ```kotlin
> internal fun parseMarkdownBlocks(content: String, fullContent: String = content): List<MarkdownBlock> {
>     val completeCode = parseChatMarkdownSource(fullContent).filterIsInstance<ChatMarkdownSource.Code>()
>     var codeIndex = 0
>     return parseChatMarkdownSource(content).flatMap { source ->   // ← 第二次全量切分
>         when (source) {
>             is ChatMarkdownSource.Code -> listOf(MarkdownBlock.Code(...))
>             is ChatMarkdownSource.Text -> parsePlainMarkdownBlocks(source.raw)
>         }
>     }
> }
> ```
>
> **代码块内容本来就从不进入 `parsePlainMarkdownBlocks`。** 一个未闭合的 3000 字代码块，在现有实现里只做一次 `substring`，不会被「逐行 AST 解析」。所以「跳过 AST」这个卖点在这个仓库里没有对应的真实开销。
>
> 真实的可优化点是：**每帧调用 `parseChatMarkdownSource` 两次**，一次对 `content`、一次对 `fullContent`。而 `PagedAssistantRichText` 里两者长度接近，这是可以省掉的一整次全量切分。V6.1 的第四批按这个方向重写。

---

## 0. 基准事实（全部来自本仓库实测）

| 事实 | 证据 |
|---|---|
| `ChatMessage` 已有稳定 `id` | `core/engine/.../ChatMessageTypes.kt:23` — `val id: String = UUID.randomUUID().toString()` |
| 列表 key 含 index | `ChatMessageListSection.kt:81` |
| `indexOfLast` 每次重组全表扫描 | `ChatMessageListSection.kt:78` |
| `assistants.any` 在每 item 内 O(n²) | `ChatMessageListSection.kt:109` |
| 图片零内存缓存 | 全项目 `LruCache` 命中 0 |
| 滚动硬跳、无用户意图感知 | `ChatScreen.kt:254,256`，`animateScrollToItem` 命中 0 |
| 无 `spring()` | 全项目命中 0 |
| 无 `animate*AsState`（除 modelhub 1 处） | 命中 2 |
| 无触觉反馈 | `HapticFeedback` 命中 0 |
| 无骨架屏 | 只有语义上的 "skeleton engine"，无 UI 骨架 |
| 无 Baseline Profile | 命中 0 |
| 启动页 46 行纯文本 | `AppStartupScreen.kt` |
| 空态 = 8.dp 卡片 + 3 按钮 | `ChatBackgroundScreens.kt:339` |
| `McaTheme` 只有 colorScheme | `McaTheme.kt:78` |
| 分页阈值 16_384 字符 | `ChatMarkdownRenderSupport.kt:313` |
| `AssistantRichText` 每帧全量解析 | `ChatMarkdownBlockRenderers.kt:84` |
| 测试平台为纯 JUnit（无 Robolectric） | `feature/chat/build.gradle.kts` testImplementation 仅 junit + json |

---

# 第一批：真实缺陷修复（P0）

> 这一批**不产生任何视觉变化**，但消除长会话卡顿、图片重解码、强制拉回。改动量极小，风险最低。

## P0-1 列表 key 去除 index

**为什么**：`LazyList` 的 key 契约是「同一内容的稳定标识」。一旦 `index` 参与 key，只要中间插入、删除、或分页边界变化，index 整体移位，所有下游 item 的 key 全部失效。Compose 会丢弃并重建每一行 —— 丢失滚动位置、丢失已解码 Bitmap、丢失展开/折叠状态。这是长会话「越用越卡」的根因。

**现状**（`ChatMessageListSection.kt:81`）：
```kotlin
key = { index, message -> "${message.role.name}:${message.createdAt}:$index" }
```

**改为**：
```kotlin
key = { _, message -> message.id }
```

**为什么可以这么做**：`ChatMessage` 的 `id` 字段在 `core/engine/ChatMessageTypes.kt:23` 已是 `UUID`，且是 `val`，构造后不变。这是最理想的 stable key。

**验证**：新增单测，构造 100 条消息 → 删除中间第 50 条 → 断言剩余 99 条的 `id` 集合不变。在 UI 层用 `Layout Inspector` 观察 `Recomposition count`，删除中间消息时应只有被删行消失，不应全量重组。

---

## P0-2 用 `derivedStateOf` 消除每帧 O(n) 与 O(n²)

**为什么**：`indexOfLast` 写在 `LazyColumn` 的 `content` lambda 里、位于 `itemsIndexed` 之外，所以**每次列表重组都会执行一次全表扫描**。而 `state.assistants.any { ... }` 写在 `itemsIndexed` 的 item lambda 内，是消息数 × 助手数 —— 真 O(n²)，且每个 item 算的是完全相同的结果。

**现状**（`ChatMessageListSection.kt:78` 与 `:109`）：
```kotlin
val lastAssistantIndex = state.messages.indexOfLast { it.role == Role.ASSISTANT }
// ...
characterCardHtml = state.assistants.any { assistant ->
    assistant.id == state.selectedAssistantId && assistant.characterCardImported
},
```

**改为**（在 `LazyColumn` 之前）：
```kotlin
val lastAssistantIndex by remember(state.messages) {
    derivedStateOf { state.messages.indexOfLast { it.role == Role.ASSISTANT } }
}
val showCharacterCard by remember(state.assistants, state.selectedAssistantId) {
    derivedStateOf {
        state.assistants.any { it.id == state.selectedAssistantId && it.characterCardImported }
    }
}
```

然后 item 内改为 `characterCardHtml = showCharacterCard`。

**为什么这样对**：`derivedStateOf` 会缓存结果，只有依赖（`state.messages` / `state.assistants`）**引用变化**时才重算。`ChatUiState` 是 data class，未变化时 `messages` 是同一个引用，所以滚动引起的重组不会触发重算。

**验证**：单测断言「消息未变时连续调用 100 次得到的 `lastAssistantIndex` 计算次数为 1」。可用一个计数器包裹 lambda 验证。

---

## P0-3 图片内存缓存（含 content:// 多维版本戳）

**为什么**：`rememberChatImageBitmap` 用 `produceState`，生命周期绑定在 composable 上。列表滚出屏幕 → Bitmap 被回收 → 滚回来 → **重新 openInputStream + 两次 decode**。全项目 `LruCache` 命中为 0。11 处调用点，`maxDimensionPx` 分别为 384 / 512 / 1280。一张 1280² 的 ARGB_8888 是 6.5MB，每次回滚重新解码必然掉帧。

**新建** `feature/chat/src/main/java/com/muyuchat/feature/chat/ChatImageMemoryCache.kt`：

```kotlin
package com.muyuchat.feature.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.File

/**
 * 图片内存缓存。
 *
 * 设计要点：
 * 1. key = uri + 目标尺寸，因为同一张图会在 384/512/1280 三个尺寸被请求。
 * 2. 内存预算 maxMemory/8，与 Compose 自身的图片预算不冲突。
 * 3. 版本戳用于检测 content:// 被同字节数覆盖编辑的情况，仅查 SIZE 会漏判。
 */
internal object ChatImageMemoryCache {

    private val maxBytes: Int = (Runtime.getRuntime().maxMemory() / 8L)
        .coerceIn(16L * 1024 * 1024, 96L * 1024 * 1024)
        .toInt()

    internal data class Entry(val versionStamp: String?, val bitmap: Bitmap)

    private val cache = object : LruCache<String, Entry>(maxBytes) {
        override fun sizeOf(key: String, value: Entry): Int =
            value.bitmap.byteCount.coerceAtLeast(1)
    }

    internal fun key(uriString: String, maxDimensionPx: Int?): String =
        "$uriString@${maxDimensionPx ?: 0}"

    /** 纯内存读取，零系统调用。可在组合阶段安全调用。 */
    fun peek(uriString: String, maxDimensionPx: Int?): Bitmap? =
        synchronized(cache) { cache.get(key(uriString, maxDimensionPx))?.bitmap }

    fun put(uriString: String, maxDimensionPx: Int?, stamp: String?, bitmap: Bitmap) {
        synchronized(cache) { cache.put(key(uriString, maxDimensionPx), Entry(stamp, bitmap)) }
    }

    fun invalidate(uriString: String) {
        synchronized(cache) {
            val snapshot = cache.snapshot()
            snapshot.keys.filter { it.startsWith("$uriString@") }.forEach(cache::remove)
        }
    }

    /**
     * 解析 URI 版本戳。**必须在 IO 线程调用。**
     *
     * content:// 只查 SIZE 无法识别「同字节数覆盖编辑」，因此追加
     * COLUMN_LAST_MODIFIED 与 DATE_MODIFIED 两个时间维度。
     */
    fun resolveVersionStamp(context: Context, uriString: String): String {
        val uri = Uri.parse(uriString)
        return when (uri.scheme?.lowercase()) {
            "content" -> {
                val projection = arrayOf(
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
                runCatching {
                    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                        if (!c.moveToFirst()) return@use "content:empty"
                        fun read(name: String): Long {
                            val i = c.getColumnIndex(name)
                            return if (i >= 0 && !c.isNull(i)) c.getLong(i) else -1L
                        }
                        "content:${read(OpenableColumns.SIZE)}:" +
                            "${read(DocumentsContract.Document.COLUMN_LAST_MODIFIED)}:" +
                            read(MediaStore.MediaColumns.DATE_MODIFIED)
                    } ?: "content:no_cursor"
                }.getOrDefault("content:error")
            }
            null, "file" -> {
                val path = if (uri.scheme == null) uriString else uri.path.orEmpty()
                val f = File(path)
                if (f.exists()) "file:${f.lastModified()}:${f.length()}" else "file:missing"
            }
            else -> "raw:$uriString"
        }
    }
}
```

**改造** `ImageAssetIoSupport.kt` 的 `rememberChatImageBitmap`：

```kotlin
@Composable
internal fun rememberChatImageBitmap(
    context: Context,
    uriString: String,
    maxDimensionPx: Int? = null,
    revisionKey: Any? = null
): State<Bitmap?> {
    // 组合阶段只读内存，零 I/O。命中时连一帧空白都不会闪。
    val state = remember(uriString, maxDimensionPx, revisionKey) {
        mutableStateOf(ChatImageMemoryCache.peek(uriString, maxDimensionPx))
    }
    LaunchedEffect(uriString, maxDimensionPx, revisionKey) {
        // 命中即返回：这是缓存唯一的不变量。不做任何比对、不发起任何 I/O。
        // 版本一致性的责任交给显式的主动失效检查点（见下方 invalidateIfStale）。
        if (ChatImageMemoryCache.peek(uriString, maxDimensionPx) != null) return@LaunchedEffect

        val decoded = withContext(Dispatchers.IO) {
            loadImageBitmap(context.applicationContext, uriString, maxDimensionPx)
        }
        if (decoded != null) {
            ChatImageMemoryCache.put(uriString, maxDimensionPx, null, decoded)
        }
        state.value = decoded
    }
    return state
}
```

**主动失效检查点**。版本戳不再参与每次命中校验，改为由明确的生命周期事件驱动一次批量校验：

```kotlin
/**
 * 在「App 回到前台」「图片编辑/替换完成」这类明确的检查点调用。
 * 一次只校验传入的 URI 集合，不在滚动路径上执行。
 */
internal suspend fun invalidateStaleChatImages(
    context: Context,
    uriStrings: Collection<String>
) = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    uriStrings.forEach { uriString ->
        val cachedStamp = ChatImageMemoryCache.peekStamp(uriString) ?: return@forEach
        val liveStamp = ChatImageMemoryCache.resolveVersionStamp(appContext, uriString)
        if (cachedStamp != liveStamp) ChatImageMemoryCache.invalidate(uriString)
    }
}
```

> **为什么不把版本戳做成「命中即校验」**：
>
> 1. **破坏不变量**。`peek` 的价值是零系统调用。一旦命中后还要 `contentResolver.query`，滚动消息列表时每个可见图片 item 每次组合都触发一次跨进程 Binder 调用——比完全没有缓存更慢。
> 2. **引入反向失效**。`revisionKey` 变化时 `remember` 重建 state、`LaunchedEffect` 重跑，但缓存里存的旧 `versionStamp` 若参与比对，可能在图片**已经被正确更新**的情况下判定「不一致 → 重解码」，或反过来判定「一致 → 提前 return」而跳过本该发生的解码。两种都是错误的。
> 3. **Android 上无法可靠检测同 URI 覆盖**。`content://` 的 `DOCUMENT_ID` 可能变也可能不变，`COLUMN_LAST_MODIFIED` 并非所有 `ContentProvider` 都实现（返回 -1），`MediaStore` 的 `DATE_MODIFIED` 只有秒级精度。
>
> **正确的三层分工**：

| 信号 | 负责范围 | 触发时机 |
|---|---|---|
| `revisionKey` | App 内部对同一 URI 的显式替换 | 调用方递增（编辑完成、重新选取同一文件） |
| `invalidateIfStale` | 外部 App 覆盖了同一 `content://` | 回到前台 / 编辑退出等显式检查点 |
| 无信号 | 缓存永不自作主张 | 命中即返回 |

`Entry` 里的 `versionStamp` 字段本来就是为此保留的——它只被 `invalidateStaleChatImages` 读取，不被 `peek` 读取。为此需给缓存补一个 stamp 读取方法：

```kotlin
    /** 仅供主动失效检查点使用，不进入热路径。 */
    fun peekStamp(uriString: String): String? = synchronized(cache) {
        cache.snapshot().entries
            .firstOrNull { it.key.startsWith("$uriString@") }
            ?.value?.versionStamp
    }
```

并且 `put` 在解码路径上要真正把 stamp 写进去（否则上表第二行永远失效）：

```kotlin
        if (decoded != null) {
            val stamp = withContext(Dispatchers.IO) {
                ChatImageMemoryCache.resolveVersionStamp(context.applicationContext, uriString)
            }
            ChatImageMemoryCache.put(uriString, maxDimensionPx, stamp, decoded)
        }
```

**注意这两处的区别**：解码是一次已经付出成本的重操作，此时顺手查一次 stamp 是摊薄成本；而滚动命中的热路径上一次都不能查。这是「一次解码对应一次查询」与「一次滚动对应 N 次查询」的差别。

**验证**：
1. 单测（纯 JVM 不可行，需 instrumented）：滚出滚回同一图片，断言 `BitmapFactory.decodeStream` 调用次数不再增加。
2. 手动：图片多的会话快速上下滑动，用 `Profile` 看 `decodeStream` 不再出现。

---

## P0-4 滚动：动画 + 尊重用户意图 + 回到最新按钮

**为什么**：三处问题叠加。
1. `scrollToItem` 是瞬时跳转，用户看到的是列表「突兀地弹一下」。`animateScrollToItem` 命中 0。
2. 无 `isScrollInProgress` 判断 —— **用户在生成过程中向上翻看历史，下一条 token 到达时会被强行拉回底部**。这是聊天 App 最令人反感的体验缺陷，Gemini 与 ChatGPT 都用「上滑即暂停跟随」解决。
3. 没有「回到最新」按钮，用户上滑后只能手动再滑回去。

**新建** `feature/chat/src/main/java/com/muyuchat/feature/chat/ChatAutoScroll.kt`：

```kotlin
package com.muyuchat.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * 「是否应跟随到底部」。
 *
 * 判据：最后一个可见 item 距离列表末端不超过 2 项。
 * 用户一旦上滑超过 2 项，即视为主动阅读历史，暂停自动跟随。
 */
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

**改造** `ChatScreen.kt:249-259`：

```kotlin
val shouldFollowLatest = rememberShouldFollowLatest(listState)

LaunchedEffect(
    state.messages.size,
    state.isGenerating,
    streamingScrollBucket,
    state.browserTask?.taskId,
    state.browserTask?.navigationId,
    browserSearchCardVisible
) {
    if (!shouldFollowLatest) return@LaunchedEffect   // 用户在读历史，不要打断
    val target = if (browserSearchCardVisible) {
        state.messages.size + if (state.messages.isEmpty()) 1 else 0
    } else {
        state.messages.lastIndex
    }
    if (target < 0) return@LaunchedEffect
    // 用动画替代硬跳；scrollOffset 为负值让消息停在输入栏上方而非被遮住
    listState.animateScrollToItem(target, scrollOffset = -bottomChromeHeightPx)
}
```

> `bottomChromeHeightPx` 已存在（`ChatScreen.kt:274`），此前只用于列表底部 padding，现在同时用于滚动偏移，消除两个魔数（`118.dp` 与 `120.dp`）彼此不联动的问题。

**「回到最新」悬浮按钮**（新增，放在 `ChatMessageListSection` 的 Box 内，与列表同层）：

```kotlin
AnimatedVisibility(
    visible = !shouldFollowLatest && state.messages.isNotEmpty(),
    enter = fadeIn(tween(140)) + slideInVertically(tween(160)) { it / 2 },
    exit = fadeOut(tween(110)) + slideOutVertically(tween(140)) { it / 2 },
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = bottomChromeHeight + 12.dp)
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(40.dp)
            .clickable { scope.launch { listState.animateScrollToItem(state.messages.lastIndex) } }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("↓", color = MaterialTheme.colorScheme.primary)
        }
    }
}
```

**验证**：手动测试——发送一条长回答，生成过程中上滑到顶部，断言不再被拉回；点击「↓」能平滑回到最新。

---

## P0-5 把 `runBlocking` 移出 `MainViewModel` 构造函数

**为什么**：`MainViewModel.kt:71,74` 在构造函数里两次 `runBlocking(Dispatchers.IO)`。无论 IO 里跑得多快，**调用方都要同步等待**。如果 `MainViewModel` 在主线程构造，这就是一次主线程磁盘阻塞——首帧卡顿的直接来源。

**现状**：
```kotlin
private val restoredModelIdentityReferences = runBlocking(Dispatchers.IO) { ... }
internal val installationScopeId = runBlocking(Dispatchers.IO) { container.runtimeProfileStore.installationScopeId() }
```

**改为**：
```kotlin
// 用 lateinit 或可空 + 一次性 init 协程
private var restoredModelIdentityReferences: X? = null
internal var installationScopeId: String? = null
    private set

init {
    viewModelScope.launch {
        val refs = withContext(Dispatchers.IO) { modelIdentityReferenceMigration.migratePendingReferences() }
        val scopeId = withContext(Dispatchers.IO) { container.runtimeProfileStore.installationScopeId() }
        restoredModelIdentityReferences = refs
        installationScopeId = scopeId
        onStartupReady()
    }
}
```

> **风险提示**：这两处的使用方若假定「构造完成即可用」，改后需要处理 null 分支。这是本批中唯一需要跨文件追踪的改动，建议**单独一次提交**，便于回滚。

---

# 第二批：质感跃迁（P1）

> 这一批**只换技法、不换视觉**。色值和圆角一个不动，但手感会从「电子动画」变成「物理对象」。

## P1-1 动效语言：建立 `McaMotion` 并替换高频触点

**为什么**：全项目 `spring()` 命中 0，`tween()` 64 处。`tween` 是定时长插值，`spring` 是物理仿真。**点发送按钮时，压缩到 0.88 再弹回 1.0，你会觉得「它被按了」；用 tween 做同样的事，你会觉得「它变大变小了」。** 前者是物理，后者是动画——这就是 Gemini 手感的来源。

**新建** `core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaMotion.kt`：

```kotlin
package com.muyuchat.core.designsystem

import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 语义化动效规范。
 *
 * 三档刚度覆盖绝大多数场景。**不要**为每个组件单独调参数，
 * 否则会重演现在「64 处 tween、各写各的时长」的碎片化。
 */
object McaMotion {

    /** 按钮按压、图标切换：紧、快、轻微过冲 */
    val snappy: FloatSpringSpec =
        spring(dampingRatio = 0.55f, stiffness = 900f)

    /** 气泡出现、卡片展开：柔和、几乎无过冲 */
    val gentle: FloatSpringSpec =
        spring(dampingRatio = 0.85f, stiffness = 380f)

    /** 页面级、全屏变化：缓慢稳定 */
    val page: FloatSpringSpec =
        spring(dampingRatio = 0.9f, stiffness = 220f)

    /**
     * 无限循环专用。**必须线性**，否则旋转会顿。
     * 现有的 AnimatedMcaAssistantMark 已经用对了，此处仅作显式声明。
     */
    const val LOOP_MS = 1300
}
```

**触点 1：发送按钮**

`ChatComposerScreens.kt` 的发送 FAB。加按压缩放：

```kotlin
val interactionSource = remember { MutableInteractionSource() }
val pressed by interactionSource.collectIsPressedAsState()
val scale by animateFloatAsState(
    targetValue = if (pressed) 0.88f else 1f,
    animationSpec = McaMotion.snappy,
    label = "sendScale"
)

FloatingActionButton(
    onClick = { haptics.tap(); onSend() },
    modifier = Modifier
        .size(40.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale },
    interactionSource = interactionSource,
    // 其余参数保持原样
)
```

**触点 2：消息气泡首次出现**

`MessageBubble` 外层包一层「入场一次」的容器。**这里不能用 `AnimatedVisibility(visible = true)`**——首次组合时 `currentState` 与 `targetState` 同为 `true`，`enter` 转场根本不会启动，气泡会直接出现，动画静默失效。正确做法是让初始状态为 `false`，再在首帧把 `targetState` 置为 `true`：

```kotlin
/**
 * 入场动画容器：仅用于「挂载时播放一次」的场景。
 *
 * 与 `AnimatedVisibility(visible = true)` 的关键区别：
 * 后者首次组合即处于目标状态，`enter` 转场被跳过。
 * 这里用 MutableTransitionState(false) 起步，首帧再置 true，
 * 保证首次组合也会完整播放 enter。
 */
@Composable
fun McaEnterAnimationBox(
    enter: EnterTransition,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = enter,
        exit = ExitTransition.None,
        modifier = modifier
    ) { content() }
}
```

调用处：

```kotlin
McaEnterAnimationBox(
    enter = fadeIn(tween(120)) +
        scaleIn(initialScale = 0.96f, animationSpec = McaMotion.gentle)
) {
    MessageBubble(/* 原参数不动 */)
}
```

> **为什么不用 `animateEnterExit`**：它要求父级已经在 `AnimatedVisibilityScope` 内，而消息列表的 item 没有这样的父容器，硬套会引入一个无意义的 `AnimatedVisibility(visible = true)` 包裹层，回到同一个坑。
>
> **为什么 `exit = ExitTransition.None`**：消息列表 item 的移除由 `LazyColumn` 的 key 变化驱动，item 会立刻从组合中消失，`exit` 根本没有播放窗口。写成 `None` 是诚实的声明，避免读代码的人误以为删除消息有退场动画。
>
> **性能注意**：`scaleIn` 走 `graphicsLayer`，不触发布局；`fadeIn` 走 alpha，也不触发。这条入场动画的每帧成本是两次图层属性更新，可接受。但如果一屏有 20 条历史消息同时「入场」，会有 20 个并发 spring——所以 `McaEnterAnimationBox` **只应用于流式新增的那一条消息**，历史消息直接渲染，或由 `ChatMessageListSection` 用一个 `appearedOnce` 标记控制。

**触点 3：顶栏模型切换** —— `Crossfade(targetState = selectedModelName, animationSpec = McaMotion.gentle)`。

**触点 4：侧栏开合** —— `ModalNavigationDrawer` 已自带，无需改。

**不要动的**：`AnimatedMcaAssistantMark` 的 `tween(1300, LinearEasing)`。无限循环必须线性，这是正确的。

**验证**：`Settings > Developer Options > Animation scale` 设为 0 时，`spring` 会退化为瞬时，确保不依赖动画也能用。

---

## P1-2 骨架屏（使用现有 surfaceVariant，零新色）

**为什么**：全项目骨架屏为 0，所有加载态都是 `CircularProgressIndicator`。转圈的问题是**没有信息量**——用户不知道在等什么。骨架屏预先画出未来内容的形状，主观等待时间显著缩短。

**新建** `core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaSkeleton.kt`：

```kotlin
package com.muyuchat.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 呼吸式骨架块。
 *
 * 采用 alpha 循环而非渐变扫光：
 * 1. 完全落在现有 surfaceVariant 色板内，不引入任何新色。
 * 2. 只有 alpha 变化，无 shader，渲染开销接近零。
 * 3. 与 Google 系视觉的克制感一致。
 *
 * [widthFraction] 用来表达「不占满行」的骨架（如段末短行）。
 * 不要把 .fillMaxWidth(0.62f) 作为 modifier 传进来——内部的 fillMaxWidth
 * 会叠加在同一约束链上，导致比例失真。
 */
@Composable
fun McaSkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    widthFraction: Float = 1f
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}

/** 一段消息的骨架：3 行文本，末行短，比例贴近真实助手消息。 */
@Composable
fun McaMessageSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        McaSkeletonBlock(height = 14.dp)
        McaSkeletonBlock(height = 14.dp)
        McaSkeletonBlock(height = 14.dp, widthFraction = 0.62f)
    }
}
```

> **为什么参数化而不是让调用方传 modifier**：`fillMaxWidth(0.62f)` 作为 modifier 传进来时，内部再叠一次 `fillMaxWidth()`，两次 `Fill` 约束会以外层为准（`fillMaxWidth(1f)` 覆盖比例），骨架仍然是满宽——这正是 V6.0 里那个「传了 0.62 却看不出效果」的问题。把比例收敛为显式参数，语义单一，且不会被后续 `modifier` 链意外覆盖。
>
> **`widthFraction` 与 `fillMaxWidth` 的语义差异**：前者是「占父级可用宽度的比例」，后者是「撑满自身约束」。在 `Column` 内两者结果一致，但在 `Row(weight = ...)` 内前者更直观。如果你想让骨架在横排里也工作，把 `fillMaxWidth(widthFraction)` 换成 `fillMaxWidth(widthFraction).widthIn(max = ...)` 之类不必——保持简单。

**应用到三处**：
1. `AppStartupScreen`（见 P1-5）
2. `ChatMessageListSection`：`state.messages.isEmpty() && state.isLoadingHistory` 时渲染 3 个 `McaMessageSkeleton`
3. `feature/modelhub` 列表加载态

---

## P1-3 触觉反馈（15 行，感知收益最大）

**为什么**：`HapticFeedback` 全项目 0 处。旗舰 App 的触觉是细粒度的——发送、停止、长按、切换，各有不同权重。这一项**改动量最小、感知最强**。

**新建** `core/designsystem/src/main/java/com/muyuchat/core/designsystem/McaHaptics.kt`：

```kotlin
package com.muyuchat.core.designsystem

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * 语义化触觉反馈。
 *
 * 调用方只表达意图（"发送了"/"停止"/"切换"），不关心具体 constant，
 * 便于将来在 API level 差异下统一调整。
 */
class McaHaptics internal constructor(private val view: View) {

    private fun fire(constant: Int) {
        // 未 attach 的 View 调用 performHapticFeedback 会静默失败，
        // 提前挡掉，避免调用方以为震动已经发生。
        if (!view.isAttachedToWindow) return
        // 不传 FLAG_IGNORE_VIEW_SETTING：尊重用户在系统设置里关闭触觉的选择。
        view.performHapticFeedback(constant)
    }

    /** 轻点：发送、确认一个轻量动作 */
    fun tap() = fire(HapticFeedbackConstants.KEYBOARD_TAP)

    /** 中 tick：切换模型、切换模式、滑出侧栏 */
    fun tick() = fire(HapticFeedbackConstants.CLOCK_TICK)

    /** 重按：长按消息、停止生成 */
    fun press() = fire(HapticFeedbackConstants.LONG_PRESS)

    /**
     * 完成：生成结束、下载完成。
     *
     * CONFIRM 自 API 30 引入，minSdk = 26 下必须判断；
     * 低版本回退 KEYBOARD_TAP —— 注意不能回退 VIRTUAL_KEY，
     * 后者的语义是"虚拟键盘按键"，与"确认/完成"无关。
     */
    fun confirm() = fire(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
    )
}

val LocalMcaHaptics = staticCompositionLocalOf<McaHaptics?> { null }

@Composable
fun rememberMcaHaptics(): McaHaptics {
    val view = LocalView.current
    return remember(view) { McaHaptics(view) }
}
```

**接入 5 个触点**：

| 位置 | 调用 |
|---|---|
| 发送按钮 | `haptics.tap()` |
| 停止生成 | `haptics.press()` |
| 长按消息 | `haptics.press()` |
| 切换模型 / 底栏 tab | `haptics.tick()` |
| 生成完成 | `haptics.confirm()` |

> **`CONFIRM` 的 API 30 问题**：`HapticFeedbackConstants.CONFIRM` 与 `REJECT`、`GESTURE_START`、`GESTURE_END` 一同在 API 30 引入。本项目 `gradle/libs.versions.toml:6` 声明 `minSdk = 26`，直接引用不会有编译报错（常量是 `static final int`，会被内联），但运行时在 API 26–29 上 `performHapticFeedback` 收到无法识别的常量后会返回 `false` 且不震动——**静默失效**，没有任何日志。这类问题在真机上极难定位，因此必须显式判断。
>
> **为什么回退到 `KEYBOARD_TAP` 而不是 `VIRTUAL_KEY`**：两者都存在于 API 5+，但语义完全不同。`KEYBOARD_TAP` 是「键盘敲击」，短促、清脆，用于确认类反馈是 Android 社区惯例；`VIRTUAL_KEY` 明确指「虚拟键盘按键」，在非键盘场景使用会让部分厂商 ROM 的触觉引擎走到错误分支。
>
> **关于 `FLAG_IGNORE_VIEW_SETTING`**：V6.0 传了这个 flag，等于无视用户在系统设置里关掉的触觉反馈。旗舰 App 的通行做法是**尊重系统开关**——用户关掉触觉，一定有理由（省电、无障碍需求、静音场合）。去掉 flag 后 `performHapticFeedback` 返回 `Boolean`，需要更细的控制时可据此判断，但通常无需关心。
>
> **不要在这 5 个触点之外滥加**：滚动、输入每个字符、列表项点击都不加触觉。触觉是稀缺资源，加多了会变成噪音，反而削弱了发送与完成这两个关键时刻的份量。

---

## P1-4 空态重构（复用 `McaLogoMark`，不动色）

**为什么**：`EmptyChatPanel`（`ChatBackgroundScreens.kt:339`）是 `Surface(8.dp 圆角)` + 标题 + 三个全宽按钮。这是「设置面板」的结构，但空态应该是「对话的邀请」。Gemini 是居中问候 + 建议 chip 网格；ChatGPT 是居中问候 + 宽输入框 + 图标化建议。

**核心差异**：旗舰空态给的是**内容和话题**，你给的是**配置动作**。

**改造**：`EmptyChatPanel` 改为居中构图，复用已有的 `McaLogoMark`（当前只在 24.dp 顶栏和 22.dp 头像出现，是「最好的品牌资产放在了最不显眼的位置」）。

```
        [McaLogoMark 56.dp]              ← 已有的品牌标记，放大
              MCA                        headlineSmall
      一个问题，从这里开始                bodyMedium / onSurfaceVariant

   ┌──────────────┐ ┌──────────────┐
   │ 帮我总结这段   │ │ 翻译成英文     │    ← 建议 chip: CircleShape + surfaceVariant
   └──────────────┘ └──────────────┘
   ┌──────────────┐ ┌──────────────┐
   │ 分析这张图     │ │ 写一段代码     │
   └──────────────┘ └──────────────┘

     选择推荐模型    导入已有模型          ← 降级为 TextButton
```

关键变化：
1. **Logo 从 24.dp 提到 56.dp**，成为视觉主体。
2. **建议 chip 取代全宽按钮**成为主视觉。
3. **配置动作降级为 TextButton**，弱化权重。

chip 用现有色板：`Surface(shape = CircleShape, color = colorScheme.surfaceVariant)`，文字 `onSurfaceVariant`。零新色。

---

## P1-5 启动页重做

**为什么**：`AppStartupScreen.kt` 全屏只有 `Text("MCA", headlineMedium)` + `CircularProgressIndicator` + 一行说明。这是用户看到的第一帧，也是判断档次的瞬间。

**改造**（保持 46 行的简洁，但换成品牌 + 骨架）：

```kotlin
@Composable
internal fun AppStartupScreen(state: AppStartupState, onRetry: () -> Unit, onSkipScan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // 品牌：复用已有的 Canvas 手绘标记
            // 启动页是「挂载即入场一次」的典型场景，必须用 McaEnterAnimationBox，
            // 不能写 AnimatedVisibility(visible = true) —— 后者 enter 永不播放。
            McaEnterAnimationBox(
                enter = fadeIn(tween(220)) +
                    scaleIn(initialScale = 0.88f, animationSpec = McaMotion.gentle)
            ) {
                McaLogoMark(size = 64.dp, cornerRadius = 18.dp)
            }
            Spacer(Modifier.height(20.dp))
            Text("MCA", style = MaterialTheme.typography.titleMedium,
                 color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(28.dp))

            if (state.loading) {
                // 骨架替代转圈
                McaMessageSkeleton(Modifier.widthIn(max = 280.dp))
                Spacer(Modifier.height(16.dp))
                Text("正在读取本地记录…",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(state.problem.orEmpty(), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onSkipScan) { Text("暂缓模型扫描，继续启动") }
                TextButton(onClick = onRetry) { Text("重新启动") }
            }
        }
    }
}
```

**为什么这样**：`McaLogoMark` 已经存在且质量不错，只是没被用在第一屏。这一改动的成本是一个 import 和一个函数调用。

---

# 第三批：体系收口（P2）

> 这一批**视觉零变化**，但决定未来能否稳定迭代。

## P2-1 补齐 `McaTheme` 的 typography / shapes / spacing

**为什么**：`McaTheme` 只传了 `colorScheme`，`typography`、`shapes` 全部走 Material 默认。同时全项目有 **73 处显式 `fontSize = `** 和 **25+ 种 `.dp` 字面量**（8.dp×254、10.dp×131、12.dp×115、18.dp×100、7.dp×36、9.dp×16、17.dp×11…）。`7.dp` 出现 36 次不是设计决策，是手调的化石。

**新增** `McaTokens.kt`（**取值全部沿用现有实测值，不引入新数值**）：

```kotlin
object McaSpacing {
    val xxs = 2.dp;  val xs = 4.dp;   val sm = 6.dp;  val s = 8.dp
    val m = 10.dp;   val l = 12.dp;   val xl = 16.dp
    val screen = 18.dp      // 屏边距主力值
    val section = 26.dp
}

object McaShapes {
    val bubble = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)  // 用户气泡，原样
    val composer = RoundedCornerShape(36.dp)                    // 输入栏，原样
    val card = RoundedCornerShape(8.dp)                         // 52 处主力
    val thumb = RoundedCornerShape(14.dp)
    val pill = CircleShape
}
```

**`McaTheme` 改为**：

```kotlin
MaterialTheme(
    colorScheme = colorScheme,     // 不动
    typography = McaTypography,    // 新增，值来自现有 13/11 sp 等
    shapes = MaterialTheme.shapes, // 暂不动，避免 Golden Test 漂移
    content = content
)
```

> **落地策略**：不要一次性替换 73 处 `fontSize`。按模块推进，每模块跑一次截图对比，确保零视觉差异。这是纯机械工作，可以让下游 Agent 批量做，但必须有人审。

## P2-2 统一 `ChatStatusBarHeader` 的字号

`ChatStatusBarHeader.kt` 里的 `labelMedium.copy(fontSize = 13.sp, lineHeight = 15.sp)` 与 `labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp)` 是最典型的双轨痕迹。收进 `McaTypography` 后原地替换。

## P2-3 Baseline Profile

**为什么**：全项目命中 0。这是 Android 冷启动优化中投入产出比最高的一项（AOT 编译热点路径，典型收益 20-40% JIT 时间）。

新增 `:baselineprofile` 模块，用 Gradle Managed Device 生成，覆盖：冷启动 → 首屏 → 打开会话 → 滚动 → 发送消息。

---

# 第四批：关于「增量 Markdown」，我的建议

上一轮我验证了 V5 的 `scanConservativeBoundary`，发现它的 `len - lineEnd >= 64` 条件会造成**末尾 64 字符死区**：段落短于 64 字符时提交点永不推进，增量完全空转。

**但 V6.0 里我给的这个切入点，收益点定位是错的。** 先看 `ChatMarkdownBlockParser.kt:55-67` 的真实结构：

```kotlin
internal fun parseMarkdownBlocks(content: String, fullContent: String = content): List<MarkdownBlock> {
    val completeCode = parseChatMarkdownSource(fullContent).filterIsInstance<ChatMarkdownSource.Code>()
    var codeIndex = 0
    return parseChatMarkdownSource(content).flatMap { source ->   // ← 第二次全量切分
        when (source) {
            // 对 Code 段是直接构造，内容不进入 plain 解析
            is ChatMarkdownSource.Code -> {
                val complete = completeCode.getOrNull(codeIndex++)
                listOf(MarkdownBlock.Code(source.language, source.code, source.fileName, complete?.code ?: source.code))
            }
            // 只有 Text 段才走 plain 解析
            is ChatMarkdownSource.Text -> parsePlainMarkdownBlocks(source.raw)
        }
    }
}
```

两个事实：

1. **代码块内容本来就从不进入 `parsePlainMarkdownBlocks`。** 一个未闭合的 3000 字代码块，在这份实现里只做一次 `substring`，不会被逐行 AST 解析。所以「跳过尾部代码块的 AST 解析」这个卖点，在本仓库没有对应的真实开销。
2. **`parsePlainMarkdownBlocks` 是 `private`**（`ChatMarkdownBlockParser.kt:69`），跨文件写 `splitStreamingMarkdown` 时直接调用它会编译失败。

**真实的可优化点是：每帧调用 `parseChatMarkdownSource` 两次。** 一次对 `content`（截断态，`PagedAssistantRichText` 的当前页），一次对 `fullContent`（完整内容）。流式过程中这一对字符串长度几乎相同，等于把同一份文本线性扫描了两遍，还额外分配了两份 `List<ChatMarkdownSource>`。这才是每帧实打实的 O(n) 浪费。

### 正确做法：单次切分快路径

不引入任何状态机、不引入任何「结构确定点」判断，只把重复的切分合并：

```kotlin
/**
 * 单次切分快路径。
 *
 * 与 parseMarkdownBlocks(content, fullContent) 的区别：
 * 当 content == fullContent（结构相等）时，只做一次 parseChatMarkdownSource，
 * 直接把 Code 段自身当作完整内容，省掉一次全量扫描与一次列表分配。
 *
 * 语义与双参版本完全一致：当两者相同时，completeCode 与本次切分出的
 * Code 段一一对应，用 source.code 替代 complete.code 是等价替换。
 */
internal fun parseMarkdownBlocksOnce(
    content: String,
    fullContent: String = content
): List<MarkdownBlock> {
    val sources = parseChatMarkdownSource(content)

    // 快路径：不需要第二遍扫描
    if (content == fullContent) {
        return sources.map { source ->
            when (source) {
                is ChatMarkdownSource.Code -> MarkdownBlock.Code(
                    language = source.language,
                    code = source.code,
                    fileName = source.fileName,
                    copyCode = source.code
                )
                is ChatMarkdownSource.Text -> parsePlainMarkdownBlocks(source.raw)
            }
        }
    }

    // 慢路径：content 是 fullContent 的截断，Code 段需要回填完整代码
    val completeCode = parseChatMarkdownSource(fullContent)
        .filterIsInstance<ChatMarkdownSource.Code>()
    var codeIndex = 0
    return sources.flatMap { source ->
        when (source) {
            is ChatMarkdownSource.Code -> {
                val complete = completeCode.getOrNull(codeIndex++)
                listOf(
                    MarkdownBlock.Code(
                        language = source.language,
                        code = source.code,
                        fileName = source.fileName,
                        copyCode = complete?.code ?: source.code
                    )
                )
            }
            is ChatMarkdownSource.Text -> parsePlainMarkdownBlocks(source.raw)
        }
    }
}
```

`PagedAssistantRichText` / `AssistantRichText` 的调用点从 `parseMarkdownBlocks(content, fullContent)` 改为 `parseMarkdownBlocksOnce(content, fullContent)`。

> **为什么快路径能省下一整次扫描**：当 `content == fullContent` 时，`completeCode` 与第一遍扫出的 `Code` 段是同一次切分的产物，`complete.code` 恒等于 `source.code`。双参版本里那个 `getOrNull(codeIndex++)` 取回的就是自己。合并后语义不变，但省掉一次 `parseChatMarkdownSource` 全量线性扫描 + 一次 `filterIsInstance` 列表分配。
>
> **为什么用内容比较而不是引用比较**：`content` 与 `fullContent` 在 `PagedAssistantRichText` 里由不同表达式构造，引用不可能相同，只能比内容。Kotlin 的 `String ==` 走 `equals`，会先比长度再比字符，最坏 O(n)——但这只是一次廉价的数组比较，远低于 `parseChatMarkdownSource` 的逐行扫描加对象分配。

### 测试应该断言什么

V6.0 的测试断言 `parseCalls == 1`，但原文档只有 1 个 `Text` 段——这个断言只证明了「`flatMap` 对 1 个元素调用 1 次」，是恒真的。**必须断言的是 `parseChatMarkdownSource` 的调用次数**，那才是本优化唯一改变的量。

```kotlin
@Test
fun `same content and full content parses source only once`() {
    val doc = buildString {
        appendLine("前言段落")
        appendLine()
        appendLine("```kotlin")
        repeat(200) { appendLine("val x$it = $it") }
        appendLine("```")
        appendLine()
        appendLine("结尾段落")
    }
    var sourceScans = 0
    val blocks = parseMarkdownBlocksOnce(
        content = doc,
        fullContent = doc,
        onSourceScan = { sourceScans++ }   // 测试专用钩子，见下
    )
    // 可失败：把快路径删掉、退回双参实现，这里会变成 2
    assertEquals("内容相同时不应重复切分", 1, sourceScans)
    assertTrue(blocks.any { it is MarkdownBlock.Code })
}

@Test
fun `truncated content still recovers complete code`() {
    val full = "```kotlin\nval a = 1\nval b = 2\n```\n尾"
    val truncated = "```kotlin\nval a = 1"
    val blocks = parseMarkdownBlocksOnce(truncated, full)
    val code = blocks.filterIsInstance<MarkdownBlock.Code>().single()
    // 慢路径必须回填完整代码，而不是截断的 val a = 1
    assertEquals("val a = 1\nval b = 2\n", code.copyCode)
}
```

> **`onSourceScan` 钩子怎么做**：`parseChatMarkdownSource` 是顶层 `internal fun`，不能直接注入。两个办法：
> 1. 把 `parseMarkdownBlocksOnce` 增加一个 `internal` 的计数参数（可空 `MutableIntRef?`），生产调用传 `null`，测试传实例。改动最小，但污染了签名。
> 2. 把 `parseChatMarkdownSource` 抽到一个 `internal interface MarkdownSourceScanner`，`parseMarkdownBlocksOnce` 接受默认实现。更干净，但多一层间接。
>
> 推荐 **方案 2**，因为将来若真要做流式增量，切分器是需要被替换的接缝。若只想快速验证，方案 1 也可接受——但**绝不要**为了测试给 `parseChatMarkdownSource` 加 `var callCount` 全局计数器，那会让测试并行跑时互相污染。

### 这条优化值不值得做

诚实评估：

| 项 | 判断 |
|---|---|
| 真实收益 | 流式期间每帧省一次 O(n) 扫描 + 一次列表分配。长回答（10k 字符）下可见，短回答下可忽略 |
| 改动量 | ~40 行（一个函数 + 两个调用点改名） |
| 风险 | 低。快/慢两条路径语义等价，且有截断用例兜底 |
| 相对 P0-1/P0-2/P1-3 的优先级 | **低**。前面那三项是「每帧都在犯的错」，这条是「每帧多做了一遍同一件事」 |

**结论**：可以做，但排在其他项之后。真正决定流式流畅度的是 P0-1（key 稳定性）与 P0-2（`derivedStateOf`），不是这里。**不要为了这一条去写 400 行增量状态机**——V5 那份方案的失败已经证明，在没有真实瓶颈的地方做过早优化，代价是引入一整类新的正确性缺陷。

---

# 执行顺序与预期

| 顺序 | 项目 | 改动量 | 风险 | 可感知收益 |
|---|---|---|---|---|
| 1 | P1-3 触觉反馈 | ~40 行新文件 + 5 处调用 | 无 | **最高** |
| 2 | P0-2 derivedStateOf | 2 行 | 无 | 高（长会话） |
| 3 | P0-1 key 去 index | 1 行 | 无 | 高（滚动稳定性） |
| 4 | P0-3 图片缓存 | ~120 行 | 低 | 高（图片会话） |
| 5 | P1-1 spring 动效 | ~30 行 + 4 触点 | 低 | **最高（手感）** |
| 6 | P1-5 启动页 | ~50 行重写 | 低 | 高（第一印象） |
| 7 | P1-4 空态 | ~80 行重写 | 低 | 高（新用户） |
| 8 | P1-2 骨架屏 | ~80 行 | 无 | 中高 |
| 9 | P0-4 滚动 | ~60 行 | 低 | 中（关键 bug） |
| 10 | P0-5 runBlocking | 跨文件 | **中** | 中（首帧） |
| 11 | P2 token 收口 | 大批量机械 | 低 | 零视觉，长期 |
| 12 | P2-3 Baseline Profile | 新模块 | 无 | 冷启动 -20% |
| 13 | 第四批 `parseMarkdownBlocksOnce` | ~40 行 + 2 处改名 | 低 | 低（长回答流式） |

**建议先做 1-3**：合计不到 50 行，不碰架构，当天可测出差别。这三项解决的是你的代码里**真实存在**的问题，也验证了「差距确实在这」这一判断。

**关于第 13 项**：它排在最后不是因为它难，而是因为它的收益最小、最不紧急。如果你只有一天时间，做完 1-5 就停，感知差别已经很明显。

---

## 与 Gemini 的真实差距，最终归因

做完上面这些，MCA 会在**四个可感知维度**上达到 Gemini Android 的水准：

1. **手感** —— `spring` 让每个按钮、每个气泡有物理重量。
2. **等待** —— 骨架屏把「不知道等什么」变成「看到内容正在成形」。
3. **确认** —— 触觉让每个动作有回响。
4. **稳定** —— key 与缓存让长会话不再退化。

剩下的差距**不在技术，而在内容密度**：Gemini 有 Gemini Live 的实时语音波形、有 Deep Research 的进度可视化、有多模态的图片理解动画。那些是产品能力，不是前端质感。

**你的前端骨架是对的。** 缺的是这些「最后一公里」的技法——它们单项成本都很低，但加起来决定了用户觉得这个 App 是「自己做的」还是「大厂做的」。
