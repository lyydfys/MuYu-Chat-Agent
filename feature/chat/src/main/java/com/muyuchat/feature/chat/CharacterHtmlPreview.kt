package com.muyuchat.feature.chat

import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal sealed interface CharacterHtmlBlock {
    data class Text(val content: String) : CharacterHtmlBlock
    data class Details(val title: String, val content: String, val initiallyOpen: Boolean) : CharacterHtmlBlock
}

private const val MAX_CHARACTER_HTML_CHARS = 120_000
private val HTML_DOCUMENT = Regex("(?m)^[ \\t]{0,3}<(?:!doctype\\s+html\\b|html\\b|body\\b)", RegexOption.IGNORE_CASE)
private val HTML_STATUS = Regex("(?m)^[ \\t]{0,3}<(?:details|summary)\\b", RegexOption.IGNORE_CASE)
private val HTML_BLOCK_LINE = Regex(
    "(?m)^[ \\t]{0,3}<(?:article|aside|blockquote|div|footer|header|h[1-6]|li|ol|p|pre|section|span|table|tbody|td|th|thead|tr|ul)\\b",
    RegexOption.IGNORE_CASE
)
private val TRAILING_PARTIAL_TAG = Regex("<[!/A-Za-z][^>]*$")
private val DETAILS_TAG = Regex("</?details\\b[^>]*>", RegexOption.IGNORE_CASE)
private val SKIPPED_HTML_TAGS = setOf("head", "style", "script", "noscript", "iframe", "svg", "template")
private val BLOCK_HTML_TAGS = setOf(
    "article", "blockquote", "details", "div", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
    "header", "hr", "li", "ol", "p", "pre", "section", "summary", "table", "tbody", "tr", "ul"
)

internal fun characterHtmlMayNeedPreview(content: String): Boolean =
    content.length <= MAX_CHARACTER_HTML_CHARS && (
        HTML_DOCUMENT.containsMatchIn(content) ||
            HTML_STATUS.containsMatchIn(content) ||
            HTML_BLOCK_LINE.containsMatchIn(content)
        )

internal fun characterHtmlHasStatus(content: String): Boolean =
    content.length <= MAX_CHARACTER_HTML_CHARS &&
        HTML_STATUS.containsMatchIn(
            parseChatMarkdownSource(content).filterIsInstance<ChatMarkdownSource.Text>()
                .joinToString("") { it.raw }
        )

/** Display-only projection. The persisted reply and copy action keep the original source. */
internal fun characterHtmlPreview(content: String): String? = characterHtmlBlocks(content)?.joinToString("\n\n") { block ->
    when (block) {
        is CharacterHtmlBlock.Text -> block.content
        is CharacterHtmlBlock.Details -> listOf(block.title, block.content).filter(String::isNotBlank).joinToString("\n")
    }
}

/** The source remains untouched; only safe text and details structure reach Compose. */
internal fun characterHtmlBlocks(content: String): List<CharacterHtmlBlock>? {
    if (content.isBlank() || content.length > MAX_CHARACTER_HTML_CHARS) return null
    val parts = parseChatMarkdownSource(content)
    val code = parts.filterIsInstance<ChatMarkdownSource.Code>()
    val plain = parts.filterIsInstance<ChatMarkdownSource.Text>().joinToString("") { it.raw }
    val standaloneHtmlDocument = plain.isBlank() &&
        code.any { it.language.equals("html", ignoreCase = true) && HTML_DOCUMENT.containsMatchIn(it.code) }
    val hasPresentationMarkup = HTML_DOCUMENT.containsMatchIn(plain) ||
        HTML_STATUS.containsMatchIn(plain) || HTML_BLOCK_LINE.containsMatchIn(plain) ||
        standaloneHtmlDocument
    if (!hasPresentationMarkup) return null

    val preservedCode = mutableListOf<String>()
    var openDetails = 0
    val html = buildString {
        parts.forEach { part ->
            when (part) {
                is ChatMarkdownSource.Text -> {
                    append(part.raw)
                    openDetails = detailsDepthAfter(part.raw, openDetails)
                }
                is ChatMarkdownSource.Code -> {
                    when {
                        part.language.equals("css", ignoreCase = true) &&
                            (standaloneHtmlDocument || openDetails > 0) -> Unit
                        part.language.equals("html", ignoreCase = true) &&
                            (openDetails > 0 || standaloneHtmlDocument) -> {
                            append(part.code)
                            openDetails = detailsDepthAfter(part.code, openDetails)
                        }
                        else -> {
                            val marker = "\uE000MCA_HTML_CODE_${preservedCode.size}\uE001"
                            preservedCode += part.raw
                            append('\n').append(marker).append('\n')
                        }
                    }
                }
            }
        }
    }
    val body = runCatching { Jsoup.parse(html.replace(TRAILING_PARTIAL_TAG, "")).body() }.getOrNull() ?: return null
    fun readable(nodes: List<Node>): String {
        val result = StringBuilder()
        nodes.forEach { appendReadableHtml(it, result, inPre = false) }
        var preview = result.toString()
            .replace("\r\n", "\n")
            .replace(Regex("(?m)^[ \\t]+"), "")
            .replace(Regex("[ \\t]+(?=\\n)"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        preservedCode.forEachIndexed { index, raw ->
            preview = preview.replace("\uE000MCA_HTML_CODE_$index\uE001", raw.trimEnd())
        }
        return preview
    }

    val statusElements = body.select("details").filter { details ->
        details.parents().none { it.normalName() == "details" }
    }
    val statuses = mutableListOf<CharacterHtmlBlock.Details>()
    statusElements.forEach { details ->
        val summary = details.children().firstOrNull { it.normalName() == "summary" }
        val status = CharacterHtmlBlock.Details(
            title = summary?.let { readable(it.childNodes()) }?.ifBlank { "状态栏" } ?: "状态栏",
            content = readable(details.childNodes().filterNot { it === summary }),
            initiallyOpen = details.hasAttr("open")
        )
        if (summary == null && status.content.isBlank()) {
            details.remove()
        } else {
            val index = statuses.size
            statuses += status
            details.replaceWith(TextNode("\uE002MCA_HTML_DETAILS_$index\uE003"))
        }
    }
    val text = readable(body.childNodes())
    val marker = Regex("\uE002MCA_HTML_DETAILS_(\\d+)\uE003")
    val blocks = mutableListOf<CharacterHtmlBlock>()
    var previousEnd = 0
    marker.findAll(text).forEach { match ->
        text.substring(previousEnd, match.range.first).trim().takeIf(String::isNotEmpty)?.let {
            blocks += CharacterHtmlBlock.Text(it)
        }
        statuses.getOrNull(match.groupValues[1].toInt())?.let { blocks += it }
        previousEnd = match.range.last + 1
    }
    text.substring(previousEnd).trim().takeIf(String::isNotEmpty)?.let {
        blocks += CharacterHtmlBlock.Text(it)
    }
    if (blocks.isNotEmpty()) return blocks
    return listOf(CharacterHtmlBlock.Text(text))
}

private fun detailsDepthAfter(fragment: String, initialDepth: Int): Int {
    var depth = initialDepth
    DETAILS_TAG.findAll(fragment).forEach { match ->
        depth = if (match.value.startsWith("</")) (depth - 1).coerceAtLeast(0) else depth + 1
    }
    return depth
}

private fun appendReadableHtml(node: Node, output: StringBuilder, inPre: Boolean) {
    when (node) {
        is TextNode -> {
            val text = node.wholeText
            output.append(if (inPre) text else text.replace(Regex("[ \\t]+"), " "))
        }
        is Element -> {
            val tag = node.normalName().lowercase(Locale.ROOT)
            if (tag in SKIPPED_HTML_TAGS) return
            if (tag == "br") {
                output.append('\n')
                return
            }
            if (tag == "img") {
                output.append(node.attr("alt"))
                return
            }
            val block = tag in BLOCK_HTML_TAGS
            if (block) appendHtmlLineBreak(output)
            if (tag == "li") output.append("- ")
            node.childNodes().forEach { appendReadableHtml(it, output, inPre || tag == "pre") }
            if (tag == "td" || tag == "th") output.append("  ")
            if (block) appendHtmlLineBreak(output)
        }
    }
}

private fun appendHtmlLineBreak(output: StringBuilder) {
    if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
}
