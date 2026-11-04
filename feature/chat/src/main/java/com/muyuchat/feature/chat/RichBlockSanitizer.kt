package com.muyuchat.feature.chat

import java.util.Locale

/**
 * 安全富文本块（酒馆预设常见的 `<details>` / `<status>` / `<StatusBlock>` / `<div>` 小剧场）。
 *
 * MCA 以 Markdown 渲染助手输出；执行模型产出的 HTML 只允许被当作**结构提示**读取，绝不是浏览器。
 * 本解析器是纯 Kotlin、无脚本执行、无样式、无网络：
 *
 * - 白名单标签才进入结构；`script/style/iframe/object/embed/link/meta/form/input/button` 整段丢弃；
 * - 所有属性丢弃（`on*` 事件、`style`、`src/href`、`srcdoc`、`data:text/html`、`javascript:` 都
 *   不可能出现在结果里，因为结果只保留纯文本/结构）；
 * - 未知标签降级为 [RichBlock.Unknown]（原始文本，可展开），不静默丢内容；
 * - 上限：输入 200k 字符、500 个标签、嵌套 32 层；超限截断并标记 [RichBlockResult.truncated]；
 * - 解析幂等：纯文本/Markdown（无 HTML 标签）返回 `detected = false`，由原 Markdown 路径处理。
 *
 * 渲染只消费 [RichBlock]，见 `RichBlockView`；原始消息内容始终保留（复制/导出一致）。
 */

/** 解析上限；测试可注入更小值。 */
data class RichBlockLimits(
    val maxInputChars: Int = 200_000,
    val maxTags: Int = 500,
    val maxDepth: Int = 32,
    val maxBlockChars: Int = 20_000,
) {
    init {
        require(maxInputChars > 0 && maxTags > 0 && maxDepth > 0 && maxBlockChars > 0) {
            "Rich block limits must be positive."
        }
    }
}

/** 展示模型：只表达可读结构与纯文本，不携带任何样式、链接或可执行内容。 */
sealed interface RichBlock {
    /** details/status 等可折叠区。 */
    data class Section(
        val title: String,
        val body: List<RichBlock>,
        val initiallyExpanded: Boolean = false,
    ) : RichBlock

    data class Text(val text: String) : RichBlock

    /** 单行 `键：值` 结构。 */
    data class KeyValue(val key: String, val value: String) : RichBlock

    /** 简单表格（首行通常为表头，渲染层自行加粗）。 */
    data class Table(val rows: List<List<String>>) : RichBlock

    /** 未知标签：保留标签名与纯文本，渲染为可展开的小字块。 */
    data class Unknown(val tagName: String, val rawText: String) : RichBlock
}

data class RichBlockResult(
    val detected: Boolean,
    val blocks: List<RichBlock>,
    val truncated: Boolean = false,
    val droppedTags: Int = 0,
) {
    /** 判定为富文本但没有任何可读内容（例如整条只有 script）。 */
    val emptyButDetected: Boolean get() = detected && blocks.isEmpty()
}

private val WHITELIST_TAGS = setOf(
    "details", "summary", "status", "div", "span", "p", "br", "hr", "b", "i", "u", "em",
    "strong", "code", "pre", "table", "thead", "tbody", "tr", "td", "th", "ul", "ol", "li",
    "h1", "h2", "h3", "h4", "h5", "h6",
)

private val DROPPED_TAGS = setOf(
    "script", "style", "iframe", "object", "embed", "link", "meta", "form", "input",
    "button", "svg", "template", "noscript",
)

/** 视为“内联透明”的标签：丢弃标签本身，保留其文本。 */
private val INLINE_TAGS = setOf(
    "span", "b", "i", "u", "em", "strong", "code", "summary", "p", "div", "section",
    "article", "header", "footer", "main", "aside", "font", "mark", "small", "big",
)

/** `键：值` / `键: 值`；键限制在 40 字符内，避免把整段叙述误拆成两列。 */
private val KEY_VALUE_LINE = Regex("^([^\\n:：]{1,40})[：:]\\s*(\\S.{0,399})$")

private val TAG_OPEN = Regex("<\\s*([A-Za-z][A-Za-z0-9:-]*)\\b", RegexOption.IGNORE_CASE)

internal fun containsRichHtml(content: String): Boolean {
    if (content.isBlank()) return false
    val limit = minOf(content.length, 200_000)
    return hasRecognizableTag(content, limit)
}

/** 识别白名单/丢弃/未知标签，但排除 `<https://…>` 这类 Markdown 自动链接。 */
private fun hasRecognizableTag(content: String, limit: Int): Boolean {
    var index = 0
    while (index < limit) {
        val open = content.indexOf('<', index)
        if (open < 0 || open >= limit) return false
        val match = TAG_OPEN.find(content.substring(open, minOf(limit, open + 64)))
        if (match != null && match.range.first == 0) {
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            val afterName = open + match.groupValues[0].length
            val invalidAttr = afterName < limit &&
                (content[afterName] == ':' || content[afterName] == '@')
            if (!invalidAttr && (name in WHITELIST_TAGS || name in DROPPED_TAGS || name.startsWith("status"))) {
                return true
            }
            // 未知名但语法像标签（后面是空白/`>`/`/`）也算 HTML 片段。
            if (!invalidAttr && afterName < limit &&
                (content[afterName] == '>' || content[afterName] == '/' || content[afterName].isWhitespace())
            ) {
                return true
            }
        }
        index = open + 1
    }
    return false
}

/**
 * 把消息正文转成安全块；无 HTML 标签时返回 `detected = false`（Markdown 路径保持不变）。
 *
 * 解析只在 `parseChatMarkdownSource` 的普通文本段进行，围栏代码块原样保留为 [RichBlock.Unknown]
 * （code），避免把代码里的 `<div>` 当成界面结构。
 */
fun sanitizeRichBlocks(
    content: String,
    limits: RichBlockLimits = RichBlockLimits(),
): RichBlockResult {
    if (content.isBlank()) return RichBlockResult(detected = false, blocks = emptyList())
    val truncatedInput = content.length > limits.maxInputChars
    val source = if (truncatedInput) content.take(limits.maxInputChars) else content
    // 先做廉价识别：纯 Markdown/普通文本完全不走分片与树构建。
    if (!containsRichHtml(source)) {
        return RichBlockResult(detected = false, blocks = emptyList(), truncated = truncatedInput)
    }
    val parts = parseChatMarkdownSource(source)
    val detected = parts.filterIsInstance<ChatMarkdownSource.Text>().any { part ->
        hasRecognizableTag(part.raw, part.raw.length)
    }
    if (!detected) {
        return RichBlockResult(detected = false, blocks = emptyList(), truncated = truncatedInput)
    }
    val parser = RichBlockParser(limits)
    parts.forEach { part ->
        when (part) {
            is ChatMarkdownSource.Text -> parser.parseHtml(part.raw)
            is ChatMarkdownSource.Code -> parser.addCodeBlock(part.raw)
        }
    }
    return RichBlockResult(
        detected = true,
        blocks = parser.finish(),
        truncated = truncatedInput || parser.truncated,
        droppedTags = parser.droppedTags,
    )
}

/** 纯文本投影（用于测试与“复制为文本”类场景）；不含任何标签/属性。 */
fun richBlocksPlainText(blocks: List<RichBlock>): String {
    val builder = StringBuilder()
    fun appendLine(line: String) {
        if (line.isBlank()) return
        if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
        builder.append(line.trim())
    }
    fun append(block: RichBlock, depth: Int) {
        when (block) {
            is RichBlock.Section -> {
                if (block.title.isNotBlank()) appendLine(block.title)
                block.body.forEach { append(it, depth + 1) }
            }

            is RichBlock.Text -> appendLine(block.text)
            is RichBlock.KeyValue -> appendLine("${block.key}：${block.value}")
            is RichBlock.Table -> block.rows.forEach { row -> appendLine(row.joinToString(" | ")) }
            is RichBlock.Unknown -> if (block.rawText.isNotBlank()) appendLine(block.rawText)
        }
    }
    blocks.forEach { append(it, 0) }
    return builder.toString().trim()
}

// ---------------------------------------------------------------------------------------------
// 树模型与构建
// ---------------------------------------------------------------------------------------------

private sealed interface RichNode {
    data class Text(val text: String) : RichNode
    data class Element(
        val name: String,
        val attributes: Map<String, String>,
        val children: MutableList<RichNode> = mutableListOf(),
    ) : RichNode
}

private class RichBlockParser(private val limits: RichBlockLimits) {
    private val root: MutableList<RichNode> = mutableListOf()
    private val stack = ArrayDeque<RichNode.Element>()
    private var tagCount = 0
    private var textChars = 0
    private var dropNesting = 0
    private var dropName: String? = null
    var truncated: Boolean = false
        private set
    var droppedTags: Int = 0
        private set

    fun parseHtml(html: String) {
        if (html.isBlank()) return
        val text = StringBuilder()
        fun appendVisibleText(source: String, start: Int, end: Int) {
            if (dropNesting == 0 && start < end) text.append(source, start, end)
        }
        fun appendVisibleChar(char: Char) {
            if (dropNesting == 0) text.append(char)
        }
        fun flushText() {
            val raw = text.toString()
            text.setLength(0)
            if (raw.isNotBlank()) appendNode(RichNode.Text(normalizeWhitespace(raw)))
        }
        var index = 0
        while (index < html.length) {
            val lt = html.indexOf('<', index)
            if (lt < 0) {
                appendVisibleText(html, index, html.length)
                break
            }
            if (lt > index) appendVisibleText(html, index, lt)
            when {
                html.startsWith("<!--", lt) -> {
                    index = html.indexOf("-->", lt + 4).let { if (it < 0) html.length else it + 3 }
                }
                html.startsWith("<![CDATA[", lt, ignoreCase = true) -> {
                    val end = html.indexOf("]]>", lt)
                    if (end < 0) {
                        appendVisibleText(html, lt + 9, html.length)
                        index = html.length
                    } else {
                        appendVisibleText(html, lt + 9, end)
                        index = end + 3
                    }
                }
                html.startsWith("<!", lt) || html.startsWith("<?", lt) -> {
                    index = html.indexOf('>', lt).let { if (it < 0) html.length else it + 1 }
                }
                else -> {
                    val close = findTagEnd(html, lt + 1)
                    if (close < 0) {
                        appendVisibleText(html, lt, html.length)
                        index = html.length
                    } else {
                        val rawTag = html.substring(lt + 1, close)
                        val closing = rawTag.startsWith("/")
                        val selfClosing = rawTag.endsWith("/")
                        val body = rawTag.removePrefix("/").removeSuffix("/")
                        val name = TAG_OPEN.find("<$body")?.groupValues?.getOrNull(1)
                            ?.lowercase(Locale.ROOT)
                        if (name == null) {
                            appendVisibleChar('<')
                            appendVisibleText(html, lt + 1, close + 1)
                        } else if (closing) {
                            flushText()
                            closeTag(name)
                        } else {
                            flushText()
                            openTag(name, body, selfClosing)
                        }
                        index = close + 1
                    }
                }
            }
        }
        flushText()
    }

    fun addCodeBlock(raw: String) {
        appendNode(RichNode.Element("pre", emptyMap(), mutableListOf(RichNode.Text(raw.trim()))))
    }

    fun finish(): List<RichBlock> {
        while (stack.isNotEmpty()) closeTag(stack.last().name)
        return convert(root)
    }

    private fun openTag(name: String, body: String, selfClosing: Boolean) {
        if (name in DROPPED_TAGS) {
            droppedTags += 1
            // Void 元素（meta/link/input/embed 等）没有结束标签，不能开启“丢弃到闭合”状态。
            if (!selfClosing && name !in VOID_TAGS) {
                if (dropNesting == 0) {
                    dropName = name
                    dropNesting = 1
                } else if (dropName == name) {
                    dropNesting += 1
                }
            }
            return
        }
        if (dropNesting > 0) {
            if (name == dropName) dropNesting += 1
            return
        }
        tagCount += 1
        if (tagCount > limits.maxTags) {
            truncated = true
            return
        }
        val element = RichNode.Element(
            name = name,
            attributes = safeAttributes(body.substringAfter(name, "")),
        )
        if (selfClosing || name in SELF_CLOSING) {
            appendChild(element)
            return
        }
        if (stack.size >= limits.maxDepth) {
            // 超深嵌套：不再建更深节点，内容仍然并入当前父级（不丢文本），并标记截断。
            truncated = true
            return
        }
        appendChild(element)
        stack.addLast(element)
    }

    private fun closeTag(name: String) {
        if (dropNesting > 0) {
            if (name == dropName) {
                dropNesting -= 1
                if (dropNesting == 0) dropName = null
            }
            return
        }
        val index = stack.indexOfLast { it.name == name }
        if (index < 0) return
        while (stack.size > index) stack.removeLast()
    }

    private fun appendChild(node: RichNode) {
        if (stack.isEmpty()) root += node else stack.last().children += node
    }

    private fun appendNode(node: RichNode) {
        if (dropNesting > 0) return
        appendChild(node)
    }

    private fun findTagEnd(source: String, from: Int): Int {
        var quote: Char? = null
        var index = from
        while (index < source.length) {
            val char = source[index]
            if (quote != null) {
                if (char == quote) quote = null
            } else if (char == '"' || char == '\'') {
                quote = char
            } else if (char == '>') {
                return index
            }
            index += 1
        }
        return -1
    }

    /** 只保留白名单属性（title/name/open），其余一律丢弃：事件、样式、src/href 永不进入结果。 */
    private fun safeAttributes(raw: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        ATTRIBUTE.findAll(raw).forEach { match ->
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            val value = match.groupValues[3]
                .ifBlank { match.groupValues[4] }
                .ifBlank { match.groupValues[5] }
                .ifBlank { match.groupValues[6] }
            when (name) {
                "title", "name" -> attributes[name] = decodeEntities(value).trim()
                "open" -> attributes["open"] = "true"
                else -> Unit
            }
        }
        return attributes
    }

    private fun normalizeWhitespace(raw: String): String {
        val decoded = decodeEntities(raw)
        val builder = StringBuilder(decoded.length)
        var lastWasSpace = false
        var pendingNewlines = 0
        decoded.forEach { char ->
            when {
                char == '\r' -> Unit
                char == '\n' -> {
                    pendingNewlines += 1
                    lastWasSpace = false
                }

                char == ' ' || char == '\t' -> {
                    lastWasSpace = true
                }

                else -> {
                    if (pendingNewlines > 0) {
                        while (builder.isNotEmpty() && builder.last() == ' ') builder.setLength(builder.length - 1)
                        if (builder.isNotEmpty()) builder.append('\n')
                        pendingNewlines = 0
                    } else if (lastWasSpace && builder.isNotEmpty()) {
                        builder.append(' ')
                    }
                    lastWasSpace = false
                    if (textChars < limits.maxBlockChars * limits.maxTags) {
                        builder.append(char)
                        textChars += 1
                    } else {
                        truncated = true
                    }
                }
            }
        }
        return builder.toString().trim()
    }

    private fun convert(nodes: List<RichNode>): List<RichBlock> {
        val blocks = mutableListOf<RichBlock>()
        val text = StringBuilder()
        fun flush() {
            val value = text.toString()
            text.setLength(0)
            appendTextBlocks(blocks, value)
        }
        nodes.forEach { node ->
            when (node) {
                is RichNode.Text -> {
                    if (text.isNotEmpty() && !text.last().isWhitespace()) text.append(' ')
                    text.append(node.text)
                }

                is RichNode.Element -> when (node.name) {
                    "br" -> text.append('\n')
                    "hr" -> {
                        flush()
                        blocks += RichBlock.Unknown("hr", "")
                    }

                    "details" -> {
                        flush()
                        val title = node.children
                            .filterIsInstance<RichNode.Element>()
                            .firstOrNull { it.name == "summary" }
                            ?.let { inlineText(it).trim() }
                            ?.takeIf(String::isNotBlank)
                            ?: "详情"
                        val body = node.children.filterNot { it is RichNode.Element && it.name == "summary" }
                        blocks += RichBlock.Section(
                            title = title,
                            body = convert(body),
                            initiallyExpanded = node.attributes["open"] == "true",
                        )
                    }

                    "status", "statusblock", "status-block" -> {
                        flush()
                        val title = node.attributes["title"]
                            ?.takeIf(String::isNotBlank)
                            ?: node.attributes["name"]?.takeIf(String::isNotBlank)
                            ?: "状态"
                        blocks += RichBlock.Section(title = title, body = convert(node.children))
                    }

                    "table" -> {
                        flush()
                        val rows = tableRows(node)
                        if (rows.isNotEmpty()) {
                            blocks += RichBlock.Table(rows)
                        } else if (inlineText(node).isNotBlank()) {
                            blocks += RichBlock.Text(inlineText(node).trim())
                        }
                    }

                    "ul", "ol" -> {
                        flush()
                        val ordered = node.name == "ol"
                        var itemIndex = 1
                        node.children.filterIsInstance<RichNode.Element>()
                            .filter { it.name == "li" }
                            .forEach { item ->
                                val marker = if (ordered) "${itemIndex}." else "•"
                                val content = inlineText(item).trim()
                                if (content.isNotBlank()) {
                                    blocks += RichBlock.Text("$marker $content")
                                }
                                itemIndex += 1
                            }
                    }

                    "pre" -> {
                        flush()
                        val content = node.children.joinToString("") { child ->
                            when (child) {
                                is RichNode.Text -> child.text
                                is RichNode.Element -> inlineText(child)
                            }
                        }.trim()
                        if (content.isNotBlank()) blocks += RichBlock.Text(content)
                    }

                    "summary", "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote" -> {
                        flush()
                        val nested = convert(node.children)
                        if (nested.isNotEmpty()) blocks += nested
                    }

                    in INLINE_TAGS -> {
                        text.append(inlineText(node))
                    }

                    else -> {
                        flush()
                        blocks += RichBlock.Unknown(
                            tagName = node.name,
                            rawText = inlineText(node).trim().take(limits.maxBlockChars),
                        )
                    }
                }
            }
        }
        flush()
        return blocks
    }

    private fun appendTextBlocks(target: MutableList<RichBlock>, raw: String) {
        if (raw.isBlank()) return
        raw.split('\n').forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val match = KEY_VALUE_LINE.matchEntire(trimmed)
            if (match != null) {
                target += RichBlock.KeyValue(
                    key = match.groupValues[1].trim(),
                    value = match.groupValues[2].trim(),
                )
            } else {
                val merged = target.lastOrNull()
                if (merged is RichBlock.Text) {
                    target[target.lastIndex] = merged.copy(text = merged.text + "\n" + trimmed)
                } else {
                    target += RichBlock.Text(trimmed.take(limits.maxBlockChars))
                }
            }
        }
    }

    private fun inlineText(node: RichNode): String = when (node) {
        is RichNode.Text -> node.text
        is RichNode.Element -> when (node.name) {
            "br" -> "\n"
            in DROPPED_TAGS -> ""
            else -> node.children.joinToString("") { child ->
                when (child) {
                    is RichNode.Text -> child.text
                    is RichNode.Element -> inlineText(child)
                }
            }
        }
    }

    private fun tableRows(table: RichNode.Element): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        fun visit(element: RichNode.Element) {
            when (element.name) {
                "tr" -> {
                    val row = element.children.filterIsInstance<RichNode.Element>()
                        .filter { it.name == "td" || it.name == "th" }
                        .map { cell -> inlineText(cell).trim() }
                    if (row.isNotEmpty()) rows += row
                }

                "thead", "tbody", "table" -> element.children
                    .filterIsInstance<RichNode.Element>()
                    .forEach(::visit)
            }
        }
        visit(table)
        return rows
    }

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        return value
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
    }

    private companion object {
        val SELF_CLOSING = setOf("br", "hr", "img", "input", "meta", "link")
        val VOID_TAGS = setOf(
            "meta", "link", "input", "embed", "img", "br", "hr", "source", "track", "wbr",
            "area", "base", "col", "param",
        )
        val ATTRIBUTE = Regex(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)(\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?"
        )
    }
}
