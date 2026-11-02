package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
@Immutable
internal sealed class MarkdownBlock(val blockKindKey: Int) {
    @Immutable
    data class Paragraph(val text: String) : MarkdownBlock(1)
    @Immutable
    data class Heading(val text: String, val level: Int) : MarkdownBlock(2)
    @Immutable
    data class BulletList(val items: List<String>) : MarkdownBlock(3)
    @Immutable
    data class NumberedList(val items: List<OrderedMarkdownItem>) : MarkdownBlock(4)
    @Immutable
    data class Quote(val text: String) : MarkdownBlock(5)
    @Immutable
    data class Code(
        val language: String?,
        val code: String,
        val fileName: String? = null,
        val copyCode: String = code
    ) : MarkdownBlock(6)
    @Immutable
    data class Table(val rows: List<List<String>>) : MarkdownBlock(7)
    @Immutable
    data object Divider : MarkdownBlock(8)
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
            blocks += MarkdownBlock.Table(padTableRows(rows))
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

// R02: one scanner owns table tokenisation so "is this a table line", "is this the separator row"
// and "split into cells" can never disagree. It keeps the outer boundary pipes separate from real
// empty cells, keeps an escaped `\|` inside its cell as a literal pipe, and ignores `|` inside
// inline code spans (including spans opened with more than one backtick).
private class TableRowScan(
    val cells: List<String>,
    val pipeCount: Int,
    val startsWithBoundary: Boolean,
    val endsWithBoundary: Boolean
)

private fun String.scanTableRow(): TableRowScan {
    val value = trim()
    var start = 0
    var end = value.length
    val startsWithBoundary = value.startsWith("|")
    if (startsWithBoundary) start = 1
    val endsWithBoundary = end > start && value[end - 1] == '|' && !value.isEscapedAt(end - 1)
    if (endsWithBoundary) end -= 1

    val cells = ArrayList<String>(4)
    val cell = StringBuilder()
    var codeSpanFence = 0
    var pipeCount = 0
    var index = start
    while (index < end) {
        val character = value[index]
        when {
            // Backslash escaping: only `\|` matters for cell boundaries (it renders as `|`). The
            // backslashes themselves are kept verbatim; an odd tail escapes the pipe, an even run
            // leaves the pipe as a real delimiter.
            character == '\\' -> {
                var run = 1
                while (index + run < end && value[index + run] == '\\') run += 1
                val pipeAt = index + run
                if (pipeAt < end && value[pipeAt] == '|') {
                    if (run % 2 == 1) {
                        repeat(run - 1) { cell.append('\\') }
                        cell.append('|')
                        index = pipeAt + 1
                    } else {
                        repeat(run) { cell.append('\\') }
                        index = pipeAt
                    }
                } else {
                    repeat(run) { cell.append('\\') }
                    index += run
                }
            }

            // Inline code span: pipes inside it are content, not column boundaries.
            character == '`' -> {
                var run = 1
                while (index + run < end && value[index + run] == '`') run += 1
                if (codeSpanFence == 0) {
                    codeSpanFence = run
                } else if (run == codeSpanFence) {
                    codeSpanFence = 0
                }
                repeat(run) { cell.append('`') }
                index += run
            }

            character == '|' && codeSpanFence == 0 -> {
                pipeCount += 1
                cells += cell.toString().trim()
                cell.setLength(0)
                index += 1
            }

            else -> {
                cell.append(character)
                index += 1
            }
        }
    }
    // The trailing cell is always appended, even when empty: an empty real cell must stay in place
    // so later columns do not shift left.
    cells += cell.toString().trim()
    return TableRowScan(
        cells = cells,
        pipeCount = pipeCount + (if (startsWithBoundary) 1 else 0) + (if (endsWithBoundary) 1 else 0),
        startsWithBoundary = startsWithBoundary,
        endsWithBoundary = endsWithBoundary
    )
}

private fun String.isEscapedAt(position: Int): Boolean {
    var backslashes = 0
    var index = position - 1
    while (index >= 0 && this[index] == '\\') {
        backslashes += 1
        index -= 1
    }
    return backslashes % 2 == 1
}

private fun String.isTableLine(): Boolean {
    val trimmed = trim()
    if (!trimmed.contains('|')) return false
    val scan = trimmed.scanTableRow()
    if (scan.pipeCount < 2) return false
    return scan.startsWithBoundary || scan.endsWithBoundary
}

private val TABLE_SEPARATOR_CELL = Regex(""":?-+:?""")

private fun String.isTableSeparatorLine(): Boolean {
    // R02: separator recognition uses the same cells as the data rows; a row is only a separator
    // when every cell is a dash-based alignment marker.
    val cells = trim().scanTableRow().cells
    return cells.isNotEmpty() && cells.all { cell ->
        cell.isNotEmpty() && TABLE_SEPARATOR_CELL.matches(cell)
    }
}

private fun String.toTableCells(): List<String> = trim().scanTableRow().cells

/**
 * R02: ragged rows are padded to the widest row instead of being truncated. The padded cells are
 * empty, so the renderer shows a placeholder in that column and no cell is silently dropped.
 */
private fun padTableRows(rows: List<List<String>>): List<List<String>> {
    val columnCount = rows.maxOf { it.size }
    return rows.map { row ->
        if (row.size == columnCount) row else row + List(columnCount - row.size) { "" }
    }
}

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
