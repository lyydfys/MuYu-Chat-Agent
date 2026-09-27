package com.muyuchat.feature.chat

internal sealed interface ChatMarkdownSource {
    val raw: String

    data class Text(override val raw: String) : ChatMarkdownSource
    data class Code(
        override val raw: String,
        val code: String,
        val language: String?,
        val fileName: String?,
        val infoString: String,
        val closed: Boolean
    ) : ChatMarkdownSource
}

private data class SourceLine(val start: Int, val end: Int, val text: String)
private data class Fence(val marker: Char, val length: Int, val infoOffset: Int)

/** Source ranges stay exact as an unfinished fence grows across streamed chunks. */
internal fun parseChatMarkdownSource(content: String): List<ChatMarkdownSource> {
    val lines = sourceLines(content)
    val result = mutableListOf<ChatMarkdownSource>()
    var plainStart = 0
    var lineIndex = 0
    while (lineIndex < lines.size) {
        val openingLine = lines[lineIndex]
        val opening = openingLine.text.openingFence()
        if (opening == null) {
            lineIndex += 1
            continue
        }
        if (openingLine.start > plainStart) {
            result += ChatMarkdownSource.Text(content.substring(plainStart, openingLine.start))
        }
        val info = openingLine.text.substring(opening.infoOffset)
        val metadata = parseFenceMetadata(info)
        var closeIndex = lineIndex + 1
        while (closeIndex < lines.size && !lines[closeIndex].text.closes(opening)) closeIndex += 1
        val closingLine = lines.getOrNull(closeIndex)
        val end = closingLine?.end ?: content.length
        val bodyStart = metadata.payloadOffset?.let { openingLine.start + opening.infoOffset + it }
            ?: openingLine.end
        result += ChatMarkdownSource.Code(
            raw = content.substring(openingLine.start, end),
            code = content.substring(bodyStart, closingLine?.start ?: content.length),
            language = metadata.language,
            fileName = metadata.fileName,
            infoString = info,
            closed = closingLine != null
        )
        plainStart = end
        lineIndex = if (closingLine == null) lines.size else closeIndex + 1
    }
    if (plainStart < content.length) result += ChatMarkdownSource.Text(content.substring(plainStart))
    return result.ifEmpty { listOf(ChatMarkdownSource.Text(content)) }
}

internal fun chatSourceLines(content: String): List<String> = sourceLines(content).map {
    content.substring(it.start, it.end)
}

private fun sourceLines(content: String): List<SourceLine> {
    val lines = mutableListOf<SourceLine>()
    var start = 0
    while (start < content.length) {
        var end = start
        while (end < content.length && content[end] != '\n' && content[end] != '\r') end += 1
        val text = content.substring(start, end)
        if (end < content.length) {
            end += if (content[end] == '\r' && content.getOrNull(end + 1) == '\n') 2 else 1
        }
        lines += SourceLine(start, end, text)
        start = end
    }
    return lines
}

private fun String.openingFence(): Fence? {
    val indent = takeWhile { it == ' ' }.length
    if (indent > 3) return null
    val marker = getOrNull(indent)?.takeIf { it == '`' || it == '~' } ?: return null
    val count = drop(indent).takeWhile { it == marker }.length
    if (count < 3) return null
    val tail = substring(indent + count)
    if (marker == '`' && '`' in tail) return null
    return Fence(marker, count, indent + count)
}

private fun String.closes(opening: Fence): Boolean {
    val indent = takeWhile { it == ' ' }.length
    if (indent > 3) return false
    val count = drop(indent).takeWhile { it == opening.marker }.length
    return count >= opening.length && drop(indent + count).all { it == ' ' || it == '\t' }
}

private data class FenceMetadata(val language: String?, val fileName: String?, val payloadOffset: Int?)

private fun parseFenceMetadata(info: String): FenceMetadata {
    val leading = info.takeWhile(Char::isWhitespace).length
    val token = info.drop(leading).takeWhile { !it.isWhitespace() }
    if (token.isEmpty()) return FenceMetadata(null, null, null)
    val compactLanguage = FENCE_LANGUAGES.firstOrNull { language ->
        token.startsWith(language, ignoreCase = true) && token.length > language.length &&
            (token[language.length] in "<{[\"'#/0123456789" ||
                INLINE_CODE_START.containsMatchIn(token.drop(language.length)))
    }
    if (compactLanguage != null) {
        return FenceMetadata(compactLanguage, null, leading + compactLanguage.length)
    }
    val tail = info.drop(leading + token.length)
    val fileName = FILE_METADATA.find(tail)?.let { match ->
        match.groupValues.drop(1).firstOrNull(String::isNotEmpty)
    }?.takeIf { it.none { character -> character == '\u0000' || character == '\r' || character == '\n' } }
    return FenceMetadata(token, fileName, null)
}

private val FENCE_LANGUAGES = listOf(
    "typescript", "javascript", "kotlin", "python", "markdown", "json", "html", "shell",
    "bash", "yaml", "java", "cpp", "c++", "css", "sql", "xml", "text", "plain",
    "ts", "js", "py", "sh", "yml", "md", "c"
)
private val INLINE_CODE_START = Regex("""^(?:import|from|def|class|print|const|let|function)\b""")
private val FILE_METADATA = Regex("""(?:^|\s)(?:file(?:name)?|title)=(?:"([^"]+)"|'([^']+)'|([^\s]+))""", RegexOption.IGNORE_CASE)
