package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

// P9/S5：从 ChatChromeScreens.kt 尾部等值移出的 markdown 块解析器簇（含表格滚动提示常量 KDoc）。
// 原样逐字迁移，未做行为改动；跨区被复用的私有类型/纯函数按协议提升为 internal。
/**
 * 表格横向滚动提示的宽度；仅在 `canScrollForward` 时绘制。
 *
 * 取值依据（真机实测，手机 1440×3200 @560dpi）：单元格横向内边距为 10dp，
 * 原先取 22dp 时渐隐会**明显覆盖第二列文字的尾部**（截图可见 `Relative Scatte…`、
 * `Efficiency Facto…` 被切在词中）。收到 13dp 后仍覆盖内边距（10dp）并多出 3dp，
 * 足以在视觉上表达"右侧还有内容"，同时几乎不再压到文字。
 */
// P9/S5: private→internal（纯解析器/类型/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal val TABLE_SCROLL_HINT_WIDTH = 13.dp

@Composable
// P9/S5: private→internal（纯解析器/类型/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun inlineMarkdownText(value: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(value, codeColor, codeBackground, linkColor) {
        buildInlineMarkdown(value, codeColor, codeBackground, linkColor)
    }
}

// P9/S5: private→internal（纯解析器/类型/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val text: String, val level: Int) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<OrderedMarkdownItem>) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Code(
        val language: String?,
        val code: String,
        val fileName: String? = null,
        val copyCode: String = code
    ) : MarkdownBlock()
    data class Table(val rows: List<List<String>>) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

// P9/S5: private→internal（纯解析器/类型/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun parseMarkdownBlocks(content: String, fullContent: String = content): List<MarkdownBlock> {
    val parsedContent = parseChatMarkdownSource(content)
    val completeCode = if (content == fullContent) {
        parsedContent.filterIsInstance<ChatMarkdownSource.Code>()
    } else {
        parseChatMarkdownSource(fullContent).filterIsInstance<ChatMarkdownSource.Code>()
    }
    var codeIndex = 0
    return parsedContent.flatMap { source ->
        when (source) {
            is ChatMarkdownSource.Code -> {
                val complete = completeCode.getOrNull(codeIndex++)
                listOf(MarkdownBlock.Code(source.language, source.code, source.fileName, complete?.code ?: source.code))
            }
            is ChatMarkdownSource.Text -> parsePlainMarkdownBlocks(source.raw)
        }
    }
}

private fun parsePlainMarkdownBlocks(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val listItems = mutableListOf<String>()
    val orderedListItems = mutableListOf<OrderedMarkdownItem>()
    var listOrdered = false
    val plainCodeLines = mutableListOf<String>()
    val tableLines = mutableListOf<String>()

    fun flushParagraph() {
        val value = paragraph.joinToString("")
        if (value.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(value)
        }
        paragraph.clear()
    }

    fun flushList() {
        if (listOrdered && orderedListItems.isNotEmpty()) {
            blocks += MarkdownBlock.NumberedList(orderedListItems.toList())
        } else if (!listOrdered && listItems.isNotEmpty()) {
            blocks += MarkdownBlock.BulletList(listItems.toList())
        }
        listItems.clear()
        orderedListItems.clear()
    }

    fun flushPlainCode() {
        if (plainCodeLines.isEmpty()) return
        val nonBlank = plainCodeLines.filter { it.isNotBlank() }
        val shouldRenderAsCode = nonBlank.size >= 2 || nonBlank.any { it.isStrongCodeLine() }
        if (shouldRenderAsCode) {
            blocks += MarkdownBlock.Code(
                language = detectCodeLanguage(nonBlank),
                code = plainCodeLines.joinToString("")
            )
        } else {
            paragraph += plainCodeLines.joinToString("")
        }
        plainCodeLines.clear()
    }

    fun flushTable() {
        if (tableLines.isEmpty()) return
        val rows = tableLines
            .filterNot { it.isTableSeparatorLine() }
            .map { it.toTableCells() }
            .filter { it.isNotEmpty() }
        if (rows.isNotEmpty()) {
            blocks += MarkdownBlock.Table(rows)
        }
        tableLines.clear()
    }

    chatSourceLines(content).forEach { rawLine ->
        val line = rawLine.removeSuffix("\n").removeSuffix("\r")
        if (line.isTableLine()) {
            flushPlainCode()
            flushParagraph()
            flushList()
            tableLines += line
        } else if (line.isBlank()) {
            flushTable()
            if (plainCodeLines.isNotEmpty()) {
                plainCodeLines += rawLine
            } else {
                paragraph += rawLine
                flushParagraph()
                flushList()
            }
        } else if (line.isHorizontalRule()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Divider
        } else if (line.isLikelyCodeLine(plainCodeLines.isNotEmpty())) {
            flushTable()
            flushParagraph()
            flushList()
            plainCodeLines += rawLine
        } else if (line.trimStart().startsWith("#")) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            val trimmed = line.trimStart()
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
            blocks += MarkdownBlock.Heading(trimmed.drop(level).trim(), level)
        } else if (line.trimStart().startsWith(">")) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            flushList()
            blocks += MarkdownBlock.Quote(line.trimStart().removePrefix(">").trim())
        } else if (line.isBulletLine()) {
            flushTable()
            flushPlainCode()
            flushParagraph()
            val ordered = line.isNumberedLine()
            if ((listItems.isNotEmpty() || orderedListItems.isNotEmpty()) && listOrdered != ordered) flushList()
            listOrdered = ordered
            if (ordered) {
                val fallback = orderedListItems.lastOrNull()?.number?.plus(1) ?: 1
                parseOrderedMarkdownItem(line, fallback)?.let { orderedListItems += it }
            } else {
                listItems += stripListMarker(line)
            }
        } else {
            flushTable()
            flushPlainCode()
            flushList()
            paragraph += rawLine
        }
    }
    flushTable()
    flushPlainCode()
    flushParagraph()
    flushList()
    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(content)) }
}


private val NUMBERED_LIST_ITEM_PATTERN = Regex("""\d{1,4}[.)、](?=\s|[^\d\s])\s*.+""")

private fun String.isBulletLine(): Boolean {
    val trimmed = trimStart()
    return trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.matches(NUMBERED_LIST_ITEM_PATTERN)
}

private fun String.isNumberedLine(): Boolean =
    isOrderedMarkdownLine()

private fun stripListMarker(value: String): String {
    val trimmed = value.trimStart()
    if (trimmed.isOrderedMarkdownLine()) {
        return stripOrderedMarkdownMarker(trimmed)
    }
    return trimmed
        .removePrefix("- ")
        .removePrefix("* ")
}

private fun String.isHorizontalRule(): Boolean {
    val trimmed = trim()
    return trimmed.length >= 3 && trimmed.all { it == '-' || it == '_' || it == '*' || it.isWhitespace() }
}

private fun String.isTableLine(): Boolean {
    val trimmed = trim()
    if (!trimmed.contains("|")) return false
    if (trimmed.count { it == '|' } < 2) return false
    return trimmed.startsWith("|") || trimmed.endsWith("|")
}

private fun String.isTableSeparatorLine(): Boolean {
    val compact = trim().trim('|').replace(" ", "")
    return compact.isNotBlank() && compact.all { it == '-' || it == ':' || it == '|' }
}

private fun String.toTableCells(): List<String> =
    trim()
        .trim('|')
        .split('|')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun String.isLikelyCodeLine(continuingCodeBlock: Boolean): Boolean {
    val trimmed = trim()
    if (trimmed.isBlank()) return false
    if (startsWith("    ") || startsWith("\t")) return true
    if (continuingCodeBlock && (
            trimmed == "}" ||
                trimmed == "};" ||
                trimmed.startsWith("}") ||
                trimmed.startsWith("else") ||
                trimmed.startsWith("elif") ||
                trimmed.startsWith("except") ||
                trimmed.startsWith("finally") ||
                trimmed.startsWith("catch") ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("# ")
            )
    ) {
        return true
    }
    return trimmed.isStrongCodeLine() ||
        CODE_KEYWORD_PATTERN.containsMatchIn(trimmed) ||
        CODE_CALL_PATTERN.containsMatchIn(trimmed)
}
