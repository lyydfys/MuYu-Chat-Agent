package com.muyuchat.feature.chat

import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private const val MAX_CHARACTER_HTML_CHARS = 120_000
private val HTML_DOCUMENT = Regex("(?m)^[ \\t]{0,3}<(?:!doctype\\s+html\\b|html\\b|body\\b)", RegexOption.IGNORE_CASE)
private val HTML_STATUS = Regex("(?m)^[ \\t]{0,3}<(?:details|summary)\\b", RegexOption.IGNORE_CASE)
private val HTML_BLOCK_LINE = Regex(
    "(?m)^[ \\t]{0,3}<(?:article|aside|blockquote|div|footer|header|h[1-6]|li|ol|p|pre|section|span|table|tbody|td|th|thead|tr|ul)\\b",
    RegexOption.IGNORE_CASE
)
private val TRAILING_PARTIAL_TAG = Regex("<[!/A-Za-z][^>]*$")
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

/** Display-only projection. The persisted reply and copy action keep the original source. */
internal fun characterHtmlPreview(content: String): String? {
    if (content.isBlank() || content.length > MAX_CHARACTER_HTML_CHARS) return null
    val parts = parseChatMarkdownSource(content)
    val code = parts.filterIsInstance<ChatMarkdownSource.Code>()
    if (code.any { !it.language.equals("html", ignoreCase = true) && !it.language.equals("css", ignoreCase = true) }) return null
    val plain = parts.filterIsInstance<ChatMarkdownSource.Text>().joinToString("") { it.raw }
    val hasPresentationMarkup = HTML_DOCUMENT.containsMatchIn(plain) ||
        HTML_STATUS.containsMatchIn(plain) || HTML_BLOCK_LINE.containsMatchIn(plain) ||
        code.any { it.language.equals("html", ignoreCase = true) && HTML_DOCUMENT.containsMatchIn(it.code) }
    if (!hasPresentationMarkup) return null

    val html = buildString {
        parts.forEach { part ->
            when (part) {
                is ChatMarkdownSource.Text -> append(part.raw)
                is ChatMarkdownSource.Code -> if (part.language.equals("html", ignoreCase = true)) append(part.code)
            }
        }
    }
    val body = runCatching { Jsoup.parse(html.replace(TRAILING_PARTIAL_TAG, "")).body() }.getOrNull() ?: return null
    val result = StringBuilder()
    body.childNodes().forEach { appendReadableHtml(it, result, inPre = false) }
    return result.toString()
        .replace("\r\n", "\n")
        .replace(Regex("(?m)^[ \\t]+"), "")
        .replace(Regex("[ \\t]+(?=\\n)"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
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
