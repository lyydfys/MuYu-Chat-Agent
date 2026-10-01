package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role

/**
 * Derives and sanitises conversation titles.
 *
 * Two directions of the same concern live here. [List.chatTitle] derives a title from a
 * conversation's first usable user turn, and [safeChatExportTitle] turns a title into a
 * file name that a document picker and the filesystem will accept. Both need the same
 * judgement about what counts as a usable title line, which is why
 * [isUnsafeChatTitleLine] is shared rather than duplicated.
 *
 * Titles are user/model controlled. They may contain Markdown fences, source code, line
 * breaks, control characters, or path separators. Keeping that content in a SAF suggested
 * name makes the picker display a confusing name and, on some providers, can make the
 * export fail. Only the short title is used for naming; the complete conversation remains
 * in the exported body.
 */

/**
 * Title shown for a conversation whose first user turn is [this].
 *
 * Prefers the first line that is neither blank, nor code-shaped (see
 * [isUnsafeChatTitleLine]), nor an attachment placeholder. When every line is unusable it
 * falls back to naming the attached file or image, and finally to a generic title.
 *
 * The result has Markdown markers stripped, whitespace collapsed, and is cut to 28
 * characters with an ellipsis. Extracted verbatim from `MainViewModel` during P2; no branch
 * or limit was changed.
 */
internal fun List<ChatMessage>.chatTitle(): String {
    val userText = firstOrNull { it.role == Role.USER }?.content.orEmpty()
    val fileName = Regex("""【上传文件：([^】]+)】""").find(userText)?.groupValues?.getOrNull(1)
    val imageName = Regex("""【上传图片：([^】]+)】""").find(userText)?.groupValues?.getOrNull(1)
    val raw = userText
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            line.isNotBlank() &&
                !isUnsafeChatTitleLine(line) &&
                !line.startsWith("【上传文件：") &&
                !line.startsWith("【上传图片：") &&
                !line.startsWith("（文件较大")
        }
        ?: fileName?.let { "文件问答：$it" }
        ?: imageName?.let { "图片：$it" }
        ?: "新对话"
    val compact = raw
        .replace(Regex("""[#>*`_\[\]()]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return compact.ifBlank { "新对话" }.let { if (it.length > 28) it.take(28) + "..." else it }
}

internal fun safeChatExportTitle(raw: String?): String {
    val source = raw.orEmpty()
    // A model response can begin with a code fence, JSON, HTML, or a complete source line.  A
    // session title is user-facing metadata, so never use that payload as a SAF filename.  Keep
    // normal prose (including short technical questions) intact.
    val titleSource = source
        .lineSequence()
        .map(String::trim)
        .firstOrNull { line -> line.isNotBlank() && !isUnsafeChatTitleLine(line) }
        ?: ""
    val cleaned = titleSource
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        // Markdown/code markers are presentation syntax, not useful filename
        // content. Removing them prevents values such as ```html<... from
        // becoming the visible file name in a document picker.
        .replace(Regex("[`*_#>\\[\\]()]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.', ' ')
        .take(48)
        .trim('.', ' ')

    val value = cleaned.ifBlank { "chat" }
    return if (value.uppercase() in WINDOWS_RESERVED_NAMES) "chat-$value" else value
}

internal fun isUnsafeChatTitleLine(value: String): Boolean {
    val line = value.trim()
    if (line.isBlank()) return true
    if (line.startsWith("```") || line.startsWith("<!DOCTYPE", ignoreCase = true)) return true
    if (line.startsWith("<html", ignoreCase = true) || line.startsWith("</html", ignoreCase = true)) return true
    if (line.startsWith("{") || line.startsWith("[") || line.endsWith("};")) return true
    if (line.startsWith("#include") || line.startsWith("import ") || line.startsWith("from ")) return true
    if (Regex("(?i)^(?:def|class|fun|function|const|let|var|val|public|private|protected|return|if|else|for|while|try|catch|SELECT|INSERT|UPDATE|CREATE)\\b")
            .containsMatchIn(line)
    ) return true
    // A response can be a code line without a fence.  Do not let common call
    // expressions or shell snippets become the conversation title (and, later,
    // the suggested export filename).  Keep this intentionally anchored so a
    // normal sentence such as "请调用 console.log" remains a useful title.
    if (Regex(
            "(?i)^(?:console\\.(?:log|warn|error|info)|System\\.out\\.println|println|print|printf)\\s*\\("
        ).containsMatchIn(line)
    ) return true
    if (Regex(
            "^(?:[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*(?:=|\\+=|-=|\\*=|/=|:=)\\s*.+"
        ).containsMatchIn(line)
    ) return true
    if (Regex(
            "(?i)^(?:#!|\\$\\s+|>\\s+(?:adb|curl|python|pip|npm|gradle|java)|(?:adb|curl|python|pip|npm|gradle)\\s+)"
        ).containsMatchIn(line)
    ) return true
    if (Regex("^<[/!]?\\s*[A-Za-z][^>]*>$").containsMatchIn(line)) return true
    if (Regex("^\\d+\\s*[×x*＋+−-]\\s*\\d+\\s*=\\s*\\d+(?:\\s+\\d+\\s*[×x*＋+−-]\\s*\\d+\\s*=\\s*\\d+)*$")
            .containsMatchIn(line)
    ) return true
    if (line.contains("</") || line.contains("=>") || line.contains("{ ") || line.contains(";")) return true
    return line.count { it == '{' || it == '}' || it == '(' || it == ')' } >= 2
}

private val WINDOWS_RESERVED_NAMES = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
)
