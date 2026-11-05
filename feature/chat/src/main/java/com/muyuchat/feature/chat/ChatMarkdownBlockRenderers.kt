package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaColorMotion
import com.muyuchat.core.designsystem.McaIcons

import android.content.ClipData
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.designsystem.McaMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S8：从 ChatChromeScreens.kt 尾部等值移出的 markdown 块渲染器簇（解析器见 ChatMarkdownBlockParser.kt）。
// 原样逐字迁移，未做行为改动；跨区入口 @Composable 按协议提升为 internal。
@Composable
// P9/S8: private→internal（跨文件被 ChatChromeScreens 复合区引用的渲染器入口）
// R00: 增加显式 contentIdentity 入口并保留原兼容调用（content + modifier 位置参数不变）。
// 分页页数绑定当前显示 owner：换会话/换消息（identity 变化）即重置为第一页；同 owner 追加文本
// 保留用户已展开的页数。identity 为 null 时沿用旧行为（依赖父级 key 隔离），不退化。
internal fun PagedAssistantRichText(content: String, modifier: Modifier = Modifier, contentIdentity: ChatMessageIdentity? = null) {
    PagedAssistantRichText(
        content = content,
        modifier = modifier,
        contentIdentity = contentIdentity,
        isStreaming = false
    )
}

@Composable
internal fun PagedAssistantRichText(
    content: String,
    modifier: Modifier = Modifier,
    contentIdentity: ChatMessageIdentity? = null,
    isStreaming: Boolean
) {
    var visibleCharacters by rememberSaveable(contentIdentity) {
        mutableStateOf(MESSAGE_RENDER_PAGE_CHARS)
    }
    val visibleContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantRichText(
            content = visibleContent,
            fullContent = content,
            contentIdentity = contentIdentity,
            visibleCharacters = visibleCharacters,
            isStreaming = isStreaming
        )
        if (visibleContent.length < content.length) {
            McaButton(style = McaButtonStyle.Text,
                onClick = {
                    visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                        .coerceAtMost(content.length)
                }
            ) {
                Text("显示更多（${visibleContent.length}/${content.length}）")
            }
        }
    }
}

@Composable
private fun AssistantRichText(
    content: String,
    fullContent: String = content,
    contentIdentity: ChatMessageIdentity? = null,
    visibleCharacters: Int = content.length,
    isStreaming: Boolean = false
) {
    val blocks = rememberMarkdownBlocks(
        content = content,
        fullContent = fullContent,
        contentIdentity = contentIdentity,
        visibleCharacters = visibleCharacters
    )
    if (blocks == null) {
        // R01: only pages above the synchronous budget wait here. Parsing runs once per pending
        // request on Dispatchers.Default, so this is not a per-token loading flicker; nothing about
        // math, links, tables or body text is dropped while the projection is being produced.
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            key(index, block.blockKindKey) {
                when (block) {
                    is MarkdownBlock.Code -> CodeBlock(block.language, block.code, block.fileName, block.copyCode)
                    is MarkdownBlock.Heading -> HeadingBlock(block.text, block.level)
                    is MarkdownBlock.Paragraph -> ParagraphBlock(block.text, isStreaming && index == blocks.lastIndex)
                    is MarkdownBlock.BulletList -> ListBlock(block.items, ordered = false)
                    is MarkdownBlock.NumberedList -> NumberedListBlock(block.items)
                    is MarkdownBlock.Quote -> QuoteBlock(block.text)
                    is MarkdownBlock.Table -> TableBlock(block.rows)
                    MarkdownBlock.Divider -> HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * R01: short replies keep the exact synchronous parse that shipped before. Only pages above the
 * scheduling budget go through one bounded background projection owned by [contentIdentity].
 */
@Composable
private fun rememberMarkdownBlocks(
    content: String,
    fullContent: String,
    contentIdentity: ChatMessageIdentity?,
    visibleCharacters: Int
): List<MarkdownBlock>? {
    if (!markdownProjectionNeedsBackground(content, fullContent)) {
        return remember(content, fullContent) { parseMarkdownBlocks(content, fullContent) }
    }
    val displayed = remember { mutableStateOf<MarkdownProjectionResult?>(null) }
    val controller = remember {
        MarkdownProjectionController(
            parseBlocks = { displayContent, completeContent ->
                withContext(Dispatchers.Default) {
                    // Both the block parse and the complete-copy payload scan of the parser run off
                    // the main thread; the parser itself is unchanged.
                    parseMarkdownBlocks(displayContent, completeContent)
                }
            },
            onCommitted = { result ->
                displayed.value = result
                result.failure?.let { failure ->
                    Log.w(MARKDOWN_PROJECTION_LOG_TAG, "Markdown projection fell back to source: $failure")
                }
            }
        )
    }
    val request = remember(content, fullContent, contentIdentity, visibleCharacters) {
        MarkdownProjectionRequest(
            owner = contentIdentity,
            visibleCharacters = visibleCharacters,
            contentLength = fullContent.length,
            displayContent = content,
            fullContent = fullContent
        )
    }
    LaunchedEffect(controller) { controller.run() }
    LaunchedEffect(controller, request) { controller.submit(request) }
    // While the newest projection is pending, keep the previous blocks only when they are a strict
    // prefix of the new page. A replaced body, a different owner or a page range that is no longer
    // compatible must not show the old result.
    return displayed.value?.takeIf { request.canDisplay(it) }?.blocks
}

private const val MARKDOWN_PROJECTION_LOG_TAG = "McaMarkdownProjection"

@Composable
private fun HeadingBlock(text: String, level: Int) {
    Text(
        text = inlineMarkdownText(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = if (level <= 2) {
            MaterialTheme.typography.titleMedium.copy(lineHeight = 23.sp)
        } else {
            MaterialTheme.typography.titleSmall.copy(lineHeight = 21.sp)
        },
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        softWrap = true
    )
}

@Composable
private fun ParagraphBlock(text: String, isStreamingTail: Boolean = false) {
    val baseColor = MaterialTheme.colorScheme.onSurface
    val inlineText = inlineMarkdownText(text)
    val layout = remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text = inlineText,
        color = baseColor,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 15.sp,
            lineHeight = 23.sp
        ),
        modifier = Modifier.fillMaxWidth().streamingTailFade(inlineText.text, isStreamingTail) { layout.value },
        onTextLayout = { layout.value = it },
        softWrap = true
    )
}

@Composable
private fun ListBlock(items: List<String>, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEachIndexed { index, item ->
            key(index) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        text = if (ordered) "${index + 1}." else "•",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = if (ordered) 22.dp else 12.dp)
                    )
                    Text(
                        text = inlineMarkdownText(item),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        modifier = Modifier.weight(1f),
                        softWrap = true
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberedListBlock(items: List<OrderedMarkdownItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEachIndexed { index, item ->
            key(index, item.number) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        text = "${item.number}.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 22.dp)
                    )
                    Text(
                        text = inlineMarkdownText(item.text),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                        modifier = Modifier.weight(1f),
                        softWrap = true
                    )
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
        )
        Text(
            text = inlineMarkdownText(text),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            modifier = Modifier.weight(1f),
            softWrap = true
        )
    }
}

@Composable
private fun CodeBlock(language: String?, code: String, fileName: String? = null, copyCode: String = code) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val iconScale by animateFloatAsState(
        targetValue = if (copied) 1.15f else 1.0f,
        animationSpec = McaMotion.sizeChange,
        label = "codeCopyScale"
    )
    val iconRotation by animateFloatAsState(
        targetValue = if (copied) 0f else -8f,
        animationSpec = McaMotion.sizeChange,
        label = "codeCopyRotation"
    )
    val copyActionTint by animateColorAsState(
        targetValue = if (copied) Color(0xFF93C5FD) else Color.White.copy(alpha = 0.78f),
        animationSpec = McaColorMotion.state,
        label = "codeCopyTint"
    )
    val highlightedCode = remember(code, language) {
        buildHighlightedCode(code, language)
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    Surface(
        color = Color(0xFF1F2937),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        listOfNotNull(fileName, language?.ifBlank { null }).joinToString(" · ").ifBlank { "代码" },
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    McaButton(style = McaButtonStyle.Text,
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("MCA code", copyCode))
                                )
                                copied = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        // R08: the row geometry stays 30dp at the normal font size (min, not fixed), so
                        // 2x font scaling cannot clip the label while the frozen visual height holds.
                        modifier = Modifier.heightIn(min = 30.dp)
                    ) {
                        Icon(
                            imageVector = if (copied) McaIcons.Check else McaIcons.ContentCopy,
                            contentDescription = if (copied) "已复制代码" else "复制代码",
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                    rotationZ = if (copied) iconRotation else 0f
                                },
                            tint = copyActionTint
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (copied) "已复制" else "复制",
                            color = copyActionTint,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.08f))
            Text(
                text = highlightedCode,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }
}

internal const val MIN_TABLE_COLUMN_DP = 92f
internal const val MAX_TABLE_COLUMN_DP = 190f

/**
 * Estimated single-line width of a table cell at the 13sp table style. CJK glyphs take roughly a
 * full em, Latin glyphs about 0.55em. Pure so the alignment contract can be tested without Compose.
 */
internal fun estimatedTableCellWidthDp(text: String): Float {
    var units = 0f
    for (character in text) {
        units += if (character.code > 0x2E80) 1.0f else 0.55f
    }
    return units * 14f + 20f
}

/**
 * One width per column, taken from the widest cell in that column across every row. Rows are
 * allowed to be ragged: a missing cell simply does not contribute to that column's width.
 */
internal fun tableColumnWidthsDp(rows: List<List<String>>): List<Float> {
    val columnCount = rows.maxOfOrNull { it.size } ?: 0
    return (0 until columnCount).map { column ->
        val widest = rows.maxOfOrNull { row ->
            estimatedTableCellWidthDp(row.getOrNull(column).orEmpty())
        } ?: MIN_TABLE_COLUMN_DP
        widest.coerceIn(MIN_TABLE_COLUMN_DP, MAX_TABLE_COLUMN_DP)
    }
}

@Composable
private fun TableBlock(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val scrollState = rememberScrollState()
    // One width per column, taken from the widest cell in that column across every row, so the
    // grid lines up instead of each row sizing its own cells independently.
    val columnWidths = remember(rows) { tableColumnWidthsDp(rows) }
    // 窄屏（手机 411dp）下三列表格会超出屏宽，末列被裁切且**没有任何滚动提示**，用户会以为
    // 被截断的列名就是完整内容。此处在可继续横滚时给出**功能性**右缘渐隐（不是装饰性渐变）。
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        ) {
            Column {
                rows.forEachIndexed { rowIndex, row ->
                    key(rowIndex) {
                        Row(
                            modifier = Modifier
                                .height(IntrinsicSize.Min)
                                .background(
                                    if (rowIndex == 0) {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                        ) {
                            row.forEachIndexed { cellIndex, cell ->
                                Text(
                                    text = inlineMarkdownText(cell),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp
                                    ),
                                    fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .width(columnWidths.getOrElse(cellIndex) { MIN_TABLE_COLUMN_DP }.dp)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    softWrap = true
                                )
                                if (cellIndex != row.lastIndex) {
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .fillMaxHeight()
                                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f))
                                    )
                                }
                            }
                        }
                        if (rowIndex != rows.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                            )
                        }
                    }
                }
            }
        }
        if (scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(TABLE_SCROLL_HINT_WIDTH)
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.surface
                        )
                    )
            )
        }
    }
}
