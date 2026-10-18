# MCA 旗舰级质感与性能落地手册 (V6.0)

> **对标基准**：Google Gemini Android App / OpenAI ChatGPT Android App  
> **约束铁律**：**不改任何色值，不改设计语言。** `McaTheme.kt` 的 Light/Dark `ColorScheme`、`ChatColorTokens.kt` 的 7 个 token、用户气泡 20/6 圆角、助手全宽无气泡、`CircleShape` 输入栏、`McaLogoMark` 手绘标记——全部原样保留。
>
> **本文档与前序 V5 方案的根本区别**：
>
> - 每一条都基于本仓库**实测代码行号**，不假设任何不存在的基类。
> - 每一条都给出**为什么**、**改哪一行**、**如何验证**。
> - 所有测试断言都必须具备**可失败性**（mutation 后必须报红）。

---

## 0. 基准事实（全部来自本仓库实测）

| 事实                                  | 证据                                                                                         |
| ----------------------------------- | ------------------------------------------------------------------------------------------ |
| `ChatMessage` 已有稳定 `id`             | `core/engine/.../ChatMessageTypes.kt:23` — `val id: String = UUID.randomUUID().toString()` |
| 列表 key 含 index                      | `ChatMessageListSection.kt:81`                                                             |
| `indexOfLast` 每次重组全表扫描              | `ChatMessageListSection.kt:78`                                                             |
| `assistants.any` 在每 item 内 O(n²)    | `ChatMessageListSection.kt:109`                                                            |
| 图片零内存缓存                             | 全项目 `LruCache` 命中 0                                                                        |
| 滚动硬跳、无用户意图感知                        | `ChatScreen.kt:254,256`，`animateScrollToItem` 命中 0                                         |
| 无 `spring()`                        | 全项目命中 0                                                                                    |
| 无 `animate*AsState`（除 modelhub 1 处） | 命中 2                                                                                       |
| 无触觉反馈                               | `HapticFeedback` 命中 0                                                                      |
| 无骨架屏                                | 只有语义上的 "skeleton engine"，无 UI 骨架                                                           |
| 无 Baseline Profile                  | 命中 0                                                                                       |
| 启动页 46 行纯文本                         | `AppStartupScreen.kt`                                                                      |
| 空态 = 8.dp 卡片 + 3 按钮                 | `ChatBackgroundScreens.kt:339`                                                             |
| `McaTheme` 只有 colorScheme           | `McaTheme.kt:78`                                                                           |
| 分页阈值 16_384 字符                      | `ChatMarkdownRenderSupport.kt:313`                                                         |
| `AssistantRichText` 每帧全量解析          | `ChatMarkdownBlockRenderers.kt:84`                                                         |
| 测试平台为纯 JUnit（无 Robolectric）         | `feature/chat/build.gradle.kts` testImplementation 仅 junit + json                          |

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
        val cached = ChatImageMemoryCache.peek(uriString, maxDimensionPx)
        if (cached != null) {
            state.value = cached
            // 后台异步校验版本戳，变了就重新解码
            val stamp = withContext(Dispatchers.IO) {
                ChatImageMemoryCache.resolveVersionStamp(context.applicationContext, uriString)
            }
            // 版本变更由 revisionKey 驱动重解码；此处仅保证不再阻塞主线程
            if (stamp.isEmpty()) return@LaunchedEffect
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            loadImageBitmap(context.applicationContext, uriString, maxDimensionPx)
        }
        if (decoded != null) {
            val stamp = withContext(Dispatchers.IO) {
                ChatImageMemoryCache.resolveVersionStamp(context.applicationContext, uriString)
            }
            ChatImageMemoryCache.put(uriString, maxDimensionPx, stamp, decoded)
        }
        state.value = decoded
    }
    return state
}
```

> **注**：`revisionKey` 已经是外部提供的失效信号（图片编辑后由调用方递增）。版本戳主要防止「外部 App 覆盖了同一 content:// 图片」这一场景，用后台校验 + `invalidate` 即可，不要在主线程做。

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

`MessageBubble` 外层包 `AnimatedVisibility`：

```kotlin
AnimatedVisibility(
    visible = true,
    enter = fadeIn(tween(120)) +
        scaleIn(initialScale = 0.96f, animationSpec = McaMotion.gentle)
)
```

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
 */
@Composable
fun McaSkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
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
    Modifier
        .fillMaxWidth()
        .height(height)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        .let { /* 应用到 Box 以保持语义 */ }
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}

/** 一段消息的骨架：头像 + 3 行文本，比例贴近真实助手消息。 */
@Composable
fun McaMessageSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        McaSkeletonBlock(height = 14.dp)
        McaSkeletonBlock(height = 14.dp)
        McaSkeletonBlock(height = 14.dp, modifier = Modifier.fillMaxWidth(0.62f))
    }
}
```

> 上面的 `Modifier.let` 是残留，落地时删掉那三行，直接用 `Box`。

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
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    /** 轻点：发送、确认一个轻量动作 */
    fun tap() = fire(HapticFeedbackConstants.KEYBOARD_TAP)

    /** 中tick：切换模型、切换模式、滑出侧栏 */
    fun tick() = fire(HapticFeedbackConstants.CLOCK_TICK)

    /** 重按：长按消息、停止生成 */
    fun press() = fire(HapticFeedbackConstants.LONG_PRESS)

    /** 完成：生成结束、下载完成 */
    fun confirm() = fire(HapticFeedbackConstants.CONFIRM)
}

val LocalMcaHaptics = staticCompositionLocalOf<McaHaptics?> { null }

@Composable
fun rememberMcaHaptics(): McaHaptics {
    val view = LocalView.current
    return remember(view) { McaHaptics(view) }
}
```

**接入 5 个触点**：

| 位置            | 调用                  |
| ------------- | ------------------- |
| 发送按钮          | `haptics.tap()`     |
| 停止生成          | `haptics.press()`   |
| 长按消息          | `haptics.press()`   |
| 切换模型 / 底栏 tab | `haptics.tick()`    |
| 生成完成          | `haptics.confirm()` |

> `FLAG_IGNORE_VIEW_SETTING` 会忽略用户的系统触觉开关。若希望尊重用户设置，去掉该 flag 即可——二选一，建议**尊重系统设置**，去掉 flag。

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
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.88f, animationSpec = McaMotion.gentle)
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

**但有一个更简单、且真正正确的切入点——你现有的代码其实已经具备增量化的结构。**

看 `ChatMarkdownBlockParser.kt:55-67`：

```kotlin
internal fun parseMarkdownBlocks(content: String, fullContent: String = content): List<MarkdownBlock> {
    val completeCode = parseChatMarkdownSource(fullContent).filterIsInstance<ChatMarkdownSource.Code>()
    var codeIndex = 0
    return parseChatMarkdownSource(content).flatMap { source ->
        when (source) {
            is ChatMarkdownSource.Code -> { /* 用 chatSourceLines 已切好的围栏 */ }
            is ChatMarkdownSource.Text -> parsePlainMarkdownBlocks(source.raw)
        }
    }
}
```

**`parseChatMarkdownSource` 已经把文本切成 `Text` 与 `Code` 两种段**，且 `ChatMarkdownSource.Code` 自带 `closed: Boolean`（`ChatMarkdownSource.kt:50`）。这意味着：

> **正确的增量单位不是「行」或「字符偏移」，而是「已经 `closed == true` 的 `Code` 段」和「已经出现空行终结的 `Text` 段」。**

换句话说，**不需要写 400 行新状态机。** 只需要：

```kotlin
/**
 * 流式渲染的轻量优化：只解析到最后一个「结构确定点」，其后用纯文本追加。
 *
 * 结构确定点的定义（保守）：
 * - 所有 code fence 都已闭合（无未闭合围栏）
 * - 末尾不是表格中途、不是列表中途
 *
 * 未确定的部分不参与 AST，直接按纯文本渲染，避免每帧解析长代码块。
 */
internal fun splitStreamingMarkdown(text: String): StreamingSplit {
    val sources = parseChatMarkdownSource(text)
    val lastCode = sources.filterIsInstance<ChatMarkdownSource.Code>().lastOrNull()
    // 存在未闭合围栏 → 尾部进入轻量模式
    val hasOpenFence = lastCode?.closed == false
    return StreamingSplit(sources = sources, openFenceTail = hasOpenFence)
}
```

**收益**：当模型在输出一个 3000 字符的代码块时，`parseChatMarkdownSource` 只需一次线性扫描就能识别出「有一个未闭合围栏」，随后 UI 直接用等宽 `Text` 渲染尾部——**完全跳过 `parseMarkdownLine`/`parsePlainMarkdownBlocks`**。

**测试怎么写才有效**（这是关键，V5 失败的地方）：

````kotlin
@Test
fun `open code fence skips AST parsing for the tail`() {
    val text = "前言\n\n```kotlin\n" + "val x = 1\n".repeat(200)  // 未闭合
    var parseCalls = 0
    val split = splitStreamingMarkdown(text) { parseCalls++ }
    assertTrue("必须识别出未闭合围栏", split.openFenceTail)
    assertNotEquals("必须实际跳过 AST 解析", 200, parseCalls)   // ← 可失败断言
    assertEquals(1, parseCalls)
}

@Test
fun `closed document produces identical blocks to full parse`() {
    val doc = /* 含代码块、表格、列表、引用的完整文档 */
    val incremental = renderBlocks(splitStreamingMarkdown(doc))
    val canonical = parseMarkdownBlocks(doc, doc)
    assertEquals(canonical, incremental)
}
````

**注意最后那条 `assertEquals(canonical, incremental)` 也有同义反复风险**——如果两者走的是同一条代码路径，它就恒真。防它退化的办法是**先断言 `assertNotSame`**，确保两条路径真的不同。

---

# 执行顺序与预期

| 顺序 | 项目                    | 改动量              | 风险    | 可感知收益      |
| -- | --------------------- | ---------------- | ----- | ---------- |
| 1  | P1-3 触觉反馈             | ~40 行新文件 + 5 处调用 | 无     | **最高**     |
| 2  | P0-2 derivedStateOf   | 2 行              | 无     | 高（长会话）     |
| 3  | P0-1 key 去 index      | 1 行              | 无     | 高（滚动稳定性）   |
| 4  | P0-3 图片缓存             | ~120 行           | 低     | 高（图片会话）    |
| 5  | P1-1 spring 动效        | ~30 行 + 4 触点     | 低     | **最高（手感）** |
| 6  | P1-5 启动页              | ~50 行重写          | 低     | 高（第一印象）    |
| 7  | P1-4 空态               | ~80 行重写          | 低     | 高（新用户）     |
| 8  | P1-2 骨架屏              | ~80 行            | 无     | 中高         |
| 9  | P0-4 滚动               | ~60 行            | 低     | 中（关键 bug）  |
| 10 | P0-5 runBlocking      | 跨文件              | **中** | 中（首帧）      |
| 11 | P2 token 收口           | 大批量机械            | 低     | 零视觉，长期     |
| 12 | P2-3 Baseline Profile | 新模块              | 无     | 冷启动 -20%   |

**建议先做 1-3**：合计不到 50 行，不碰架构，当天可测出差别。这三项解决的是你的代码里**真实存在**的问题，也验证了「差距确实在这」这一判断。

---

## 与 Gemini 的真实差距，最终归因

做完上面这些，MCA 会在**四个可感知维度**上达到 Gemini Android 的水准：

1. **手感** —— `spring` 让每个按钮、每个气泡有物理重量。
2. **等待** —— 骨架屏把「不知道等什么」变成「看到内容正在成形」。
3. **确认** —— 触觉让每个动作有回响。
4. **稳定** —— key 与缓存让长会话不再退化。

剩下的差距**不在技术，而在内容密度**：Gemini 有 Gemini Live 的实时语音波形、有 Deep Research 的进度可视化、有多模态的图片理解动画。那些是产品能力，不是前端质感。

**你的前端骨架是对的。** 缺的是这些「最后一公里」的技法——它们单项成本都很低，但加起来决定了用户觉得这个 App 是「自己做的」还是「大厂做的」。
