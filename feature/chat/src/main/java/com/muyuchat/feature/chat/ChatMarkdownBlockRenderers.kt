package com.muyuchat.feature.chat

import android.content.ClipData
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S8：从 ChatChromeScreens.kt 尾部等值移出的 markdown 块渲染器簇（解析器见 ChatMarkdownBlockParser.kt）。
// 原样逐字迁移，未做行为改动；跨区入口 @Composable 按协议提升为 internal。
@Composable
// P9/S8: private→internal（跨文件被 ChatChromeScreens 复合区引用的渲染器入口）
internal fun PagedAssistantRichText(content: String, modifier: Modifier = Modifier) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val visibleContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantRichText(visibleContent, fullContent = content)
        if (visibleContent.length < content.length) {
            TextButton(
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
private fun AssistantRichText(content: String, fullContent: String = content) {
    val blocks = remember(content, fullContent) { parseMarkdownBlocks(content, fullContent) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> CodeBlock(block.language, block.code, block.fileName, block.copyCode)
                is MarkdownBlock.Heading -> HeadingBlock(block.text, block.level)
                is MarkdownBlock.Paragraph -> ParagraphBlock(block.text)
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
private fun ParagraphBlock(text: String) {
    Text(
        text = inlineMarkdownText(text),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 15.sp,
            lineHeight = 23.sp
        ),
        modifier = Modifier.fillMaxWidth(),
        softWrap = true
    )
}

@Composable
private fun ListBlock(items: List<String>, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEachIndexed { index, item ->
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

@Composable
private fun NumberedListBlock(items: List<OrderedMarkdownItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
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
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    Surface(
        color = Color(0xFF1F2937),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
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
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("MCA code", copyCode))
                            )
                            copied = true
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "已复制代码" else "复制代码",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White.copy(alpha = 0.78f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (copied) "已复制" else "复制",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Text(
                text = code,
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

private const val MIN_TABLE_COLUMN_DP = 92f
private const val MAX_TABLE_COLUMN_DP = 190f

/**
 * Estimated single-line width of a table cell at the 13sp table style. CJK glyphs take roughly a
 * full em, Latin glyphs about 0.55em; the result is clamped by the caller.
 */
private fun estimatedTableCellWidthDp(text: String): Float {
    var units = 0f
    for (character in text) {
        units += if (character.code > 0x2E80) 1.0f else 0.55f
    }
    return units * 14f + 20f
}

@Composable
private fun TableBlock(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val scrollState = rememberScrollState()
    // One width per column, taken from the widest cell in that column across every row, so the
    // grid lines up instead of each row sizing its own cells independently.
    val columnWidths = remember(rows) {
        val columnCount = rows.maxOfOrNull { it.size } ?: 0
        (0 until columnCount).map { column ->
            val widest = rows.maxOfOrNull { row ->
                estimatedTableCellWidthDp(row.getOrNull(column).orEmpty())
            } ?: MIN_TABLE_COLUMN_DP
            widest.coerceIn(MIN_TABLE_COLUMN_DP, MAX_TABLE_COLUMN_DP)
        }
    }
    // 窄屏（手机 411dp）下三列表格会超出屏宽，末列被裁切且**没有任何滚动提示**，用户会以为
    // 被截断的列名就是完整内容。此处在可继续横滚时给出**功能性**右缘渐隐（不是装饰性渐变）。
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.74f)),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
        ) {
            Column {
                rows.forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.background(
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
                        }
                    }
                    if (rowIndex != rows.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f))
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
