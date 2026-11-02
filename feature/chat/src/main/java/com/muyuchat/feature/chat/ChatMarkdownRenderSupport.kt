package com.muyuchat.feature.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.muyuchat.core.engine.Role

// P9/S3：从 ChatChromeScreens.kt 尾部等值移出的 markdown/推理/代码块渲染助手簇。
// 原样逐字迁移，未做行为改动；跨区被复用的私有纯函数/只读常量按协议提升为 internal。
// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun String.isStrongCodeLine(): Boolean {
    val trimmed = trim()
    if (trimmed.length < 2) return false
    // Arithmetic tables are common code-like output from small local models.
    // Recognize both ASCII and Chinese multiplication signs so each row keeps
    // its own line instead of being merged into one paragraph.
    if (ARITHMETIC_EXPRESSION_PATTERN.matches(trimmed)) return true
    if (CODE_DECLARATION_PATTERN.containsMatchIn(trimmed)) return true
    if (CODE_KEYWORD_PATTERN.containsMatchIn(trimmed) && (trimmed.endsWith(":") || trimmed.endsWith("{"))) return true
    if (trimmed.startsWith("#include") || trimmed.startsWith("using namespace")) return true
    if (trimmed.startsWith("<") && trimmed.endsWith(">") && "/" in trimmed) return true
    if (trimmed.endsWith(";") && !trimmed.contains("；")) return true
    if (trimmed == "{" || trimmed == "}" || trimmed == "};") return true
    return CODE_ASSIGNMENT_PATTERN.containsMatchIn(trimmed) && !trimmed.any { it.isCjkLike() }
}

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun detectCodeLanguage(lines: List<String>): String? {
    val sample = lines.joinToString("\n") { it.trim() }
    val lower = sample.lowercase()
    return when {
        PYTHON_HINT.containsMatchIn(sample) -> "python"
        KOTLIN_HINT.containsMatchIn(sample) -> "kotlin"
        TYPESCRIPT_HINT.containsMatchIn(sample) -> "typescript"
        "#include" in sample || "std::" in sample -> "cpp"
        JAVA_HINT.containsMatchIn(sample) -> "java"
        SQL_HINT.containsMatchIn(sample) -> "sql"
        "<html" in lower || "</" in sample -> "html"
        else -> null
    }
}
// 探测用正则只在类加载时编译一次：detectCodeLanguage 会随每个代码块调用。
private val PYTHON_HINT = Regex("""(?m)^(def|from|import|elif|except|print)\b""")
private val KOTLIN_HINT = Regex("""(?m)^(fun|val|var|package|override|private|suspend)\b""")
private val TYPESCRIPT_HINT = Regex("""(?m)^(const|let|function|export|interface|type)\b""")
private val JAVA_HINT = Regex("""(?m)^(public|private|protected)\s+(class|static|void|int|String)\b""")
private val SQL_HINT = Regex("""(?m)^(select|insert|update|delete|create|alter)\b""", RegexOption.IGNORE_CASE)

private val MULTI_SPACE_PATTERN = Regex("""[ \t]{2,}""")
private val INDENT_AFTER_NEWLINE_PATTERN = Regex("""\n[ \t]+""")
private val MULTI_BLANK_LINE_PATTERN = Regex("""\n{3,}""")

private val CODE_DECLARATION_PATTERN = Regex(
    pattern = """^(def|class|fun|function|const|let|var|val|public|private|protected|override|interface|type|struct|enum|async\s+def)\b.*"""
)

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal val CODE_KEYWORD_PATTERN = Regex(
    pattern = """^(if|else|elif|for|while|when|switch|case|try|catch|except|finally|with|return|break|continue|await|import|from|package|using|namespace|select|insert|update|delete|create|alter)\b.*""",
    option = RegexOption.IGNORE_CASE
)

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal val CODE_CALL_PATTERN = Regex(
    pattern = """^(print|console\.log|System\.out\.println|printf|Log\.[diew]|println)\s*\(.*"""
)

private val CODE_ASSIGNMENT_PATTERN = Regex(
    pattern = """^[A-Za-z_][A-Za-z0-9_.$\[\]]*\s*(=|\+=|-=|\*=|/=|:=)\s*.+"""
)
private val ARITHMETIC_EXPRESSION_PATTERN = Regex(
    pattern = """^\d+\s*[×x*＋+−-]\s*\d+\s*=\s*\d+$"""
)

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun cleanReasoningForDisplay(value: String): String {
    return value
        .replace("<think>", "", ignoreCase = true)
        .replace("</think>", "", ignoreCase = true)
        .replace("<|think|>", "", ignoreCase = true)
        .replace("<|channel>thought", "", ignoreCase = true)
        .replace("<|channel|>thought", "", ignoreCase = true)
        .replace("<|channel>analysis", "", ignoreCase = true)
        .replace("<|channel|>analysis", "", ignoreCase = true)
        .replace("<channel|>", "", ignoreCase = true)
        .replace("<channel>", "", ignoreCase = true)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(MULTI_SPACE_PATTERN, " ")
        .replace(INDENT_AFTER_NEWLINE_PATTERN, "\n")
        .replace(MULTI_BLANK_LINE_PATTERN, "\n\n")
        .trim()
}

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun reasoningPreview(value: String): String {
    val lines = value
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .take(REASONING_COLLAPSED_MAX_LINES)
        .toList()
    return if (lines.isEmpty()) value.take(REASONING_PREVIEW_MAX_CHARS) else {
        val preview = lines.joinToString("\n")
        if (preview.length <= REASONING_PREVIEW_MAX_CHARS) {
            preview
        } else {
            preview.take(REASONING_PREVIEW_MAX_CHARS).trimEnd()
        }
    }
}

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun buildInlineMarkdown(
    value: String,
    codeColor: Color,
    codeBackground: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    var index = 0

    fun appendPlain(text: String) {
        append(wrapForDisplay(text))
    }

    // 单调游标：URL 正则只对尚未扫描的后缀执行一次 find。
    // 命中被消费（index 越过 match.range.last）后才从新位置重扫，
    // 避免每个纯文本片段都对整段剩余后缀跑一次正则。
    var cachedUrlMatch: MatchResult? = null
    var cachedUrlScannedFrom = -1
    fun urlMatchAt(from: Int): MatchResult? {
        val cached = cachedUrlMatch
        if (cached != null && cached.range.first >= from) return cached
        if (cached == null && cachedUrlScannedFrom in 0..from) return null
        cachedUrlScannedFrom = from
        return URL_PATTERN.find(value, from).also { cachedUrlMatch = it }
    }

    while (index < value.length) {
        when {
            value.startsWith("[", index) -> {
                val match = MARKDOWN_LINK_PATTERN.find(value, index)
                if (match != null && match.range.first == index) {
                    // The blue/underline styling is kept as a span so existing render tests still see
                    // it; the LinkAnnotation makes the styled text actually open the target.
                    withLink(LinkAnnotation.Url(match.groupValues[2])) {
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(wrapForDisplay(match.groupValues[1]))
                        }
                    }
                    index = match.range.last + 1
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            urlMatchAt(index)?.let { it.range.first == index } == true -> {
                val match = urlMatchAt(index)!!
                withLink(LinkAnnotation.Url(match.value)) {
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(wrapForDisplay(match.value))
                    }
                }
                index = match.range.last + 1
            }
            value.startsWith("`", index) -> {
                val end = value.indexOf('`', startIndex = index + 1)
                if (end > index + 1) {
                    withStyle(
                        SpanStyle(
                            color = codeColor,
                            background = codeBackground,
                            fontFamily = FontFamily.Monospace
                        )
                    ) {
                        append(wrapForDisplay(value.substring(index + 1, end)))
                    }
                    index = end + 1
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            // LaTeX 数学：`$...$` 与 `$$...$$`。必须在反引号分支之后，代码 span 才能优先。
            // 识别与转换都在 MarkdownMathText.kt 的可测纯函数里；这里只做分派。
            value.startsWith("$", index) -> {
                val span = findMathSpan(value, index)
                if (span != null) {
                    appendPlain(renderLatexMath(span.tex))
                    index = span.endExclusive
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            value.startsWith("**", index) -> {
                val end = value.indexOf("**", startIndex = index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendPlain(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            value.startsWith("__", index) -> {
                val end = value.indexOf("__", startIndex = index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendPlain(value.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    appendPlain(value[index].toString())
                    index++
                }
            }
            else -> {
                // 顺序取最小候选，避免每个片段都分配一个 List。
                var next = urlMatchAt(index)?.range?.first ?: value.length
                val dollar = value.indexOf('$', startIndex = index)
                if (dollar in 0 until next) next = dollar
                val backtick = value.indexOf('`', startIndex = index)
                if (backtick in 0 until next) next = backtick
                val bold = value.indexOf("**", startIndex = index)
                if (bold in 0 until next) next = bold
                val underline = value.indexOf("__", startIndex = index)
                if (underline in 0 until next) next = underline
                val bracket = value.indexOf('[', startIndex = index)
                if (bracket in 0 until next) next = bracket
                appendPlain(value.substring(index, next))
                index = next
            }
        }
    }
}

private val MARKDOWN_LINK_PATTERN = Regex("""\[(.+?)]\((https?://[^)\s]+)\)""")
private val URL_PATTERN = Regex("""https?://[^\s)）]+""")
// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal const val REASONING_COLLAPSED_MAX_LINES = 4
private const val REASONING_PREVIEW_MAX_CHARS = 520
private val REASONING_LEADING_MARKERS = listOf(
    Regex("""(?is)^\s*here(?:'s| is)\s+a\s+thinking\s+process[^:：\n]*[:：]?\s*"""),
    Regex("""(?is)^\s*this\s+is\s+(?:my\s+)?(?:thinking|reasoning)\s+process[^:：\n]*[:：]?\s*"""),
    Regex("""(?im)^\s*thinking\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*thought\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*reasoning\s*process\s*\d*\s*[.:：]?\s*"""),
    Regex("""(?im)^\s*analysis\s*\d*\s*[:：]?\s*"""),
    Regex("""(?m)^\s*(思考过程|推理过程|分析过程)\s*\d*\s*[:：.]?\s*""")
)
private val REASONING_NUMBERED_STEP_PATTERN = Regex(
    pattern = """(?im)^\s*\d+\.\s*\*\*(analyze|determine|check|identify|construct|decide|consider|reason)[^*]{0,96}\*\*\s*[:：]?\s*"""
)
private val REASONING_BOLD_STEP_PATTERN = Regex(
    pattern = """(?im)^\s*\*\*(analyze|determine|check|identify|construct|decide|consider|reason)[^*]{0,96}\*\*\s*[:：]?\s*"""
)
private val ENGLISH_REASONING_SCAFFOLD_PREFIXES = listOf(
    "* user asks",
    "* role:",
    "* system requirements",
    "* default response language",
    "* style:",
    "* specific thinking pattern",
    "* constraint checklist",
    "* output format",
    "* determine the role",
    "* analyze the request",
    "* context:",
    "* question:"
)

private val ENGLISH_REASONING_SCAFFOLD_KEYWORDS = listOf(
    "the user is asking",
    "user asks",
    "identify core",
    "identity constraints",
    "determine persona",
    "determine the role",
    "formulate the answer",
    "final review",
    "self-correction",
    "system prompt",
    "system instruction",
    "system requirements",
    "default response language",
    "output format",
    "constraint checklist",
    "role:",
    "question:",
    "context:",
    "language:",
    "style:",
    "developer:",
    "nature:",
    "must use",
    "use /think",
    "thinking mode is open"
)

private val REASONING_SCAFFOLD_SEGMENT_SPLIT = Regex(
    pattern = """(?=(?:^|\s)(?:\d+\.\s*)?(?:\*\*)?(?:User|Role|Question|Context|Language|Style|Constraint|Constraints|System|Output|Identify|Determine|Formulate|Final Review|Self-Correction|Developer|Nature|Type)\b)""",
    option = RegexOption.IGNORE_CASE
)

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun wrapForDisplay(value: String): String = value

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal fun String.safePrefix(maxCharacters: Int): String {
    if (length <= maxCharacters) return this
    var end = maxCharacters.coerceAtLeast(0)
    if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
        end--
    }
    return substring(0, end)
}

private fun Char.isCjkLike(): Boolean =
    this in '\u4E00'..'\u9FFF' ||
        this in '\u3400'..'\u4DBF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\uAC00'..'\uD7AF'

// P9/S3: private→internal（纯渲染助手/只读常量；跨文件被 ChatChromeScreens 复合区引用）
internal const val MESSAGE_RENDER_PAGE_CHARS = 16_384
internal const val STREAMING_SCROLL_CHAR_STEP = 512

private val CODE_SYNTAX_TOKEN_PATTERN = Regex(
    pattern = """(//[^\n]*|#[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|@[A-Za-z_][A-Za-z0-9_]*|\b(?:fun|val|var|class|interface|object|data|sealed|enum|override|private|internal|public|protected|suspend|inline|return|if|else|elif|when|switch|case|for|while|do|try|catch|except|finally|throw|raise|import|from|package|as|is|in|def|lambda|yield|with|pass|async|await|const|let|function|export|default|type|struct|namespace|using|void|int|long|float|double|boolean|bool|char|string|String|Int|Long|Float|Double|Boolean|Unit|List|Map|Set|true|false|null|None|True|False|self|this|super|new|delete|select|where|insert|into|update|join|left|right|inner|outer|group|order|by|limit|create|alter|drop|table|values|and|or|not)\b|\b\d+(?:\.\d+)?[fFdDlL]?\b)"""
)

private val CODE_COMMENT_COLOR = Color(0xFF9AA0A6)
private val CODE_STRING_COLOR = Color(0xFF81C995)
private val CODE_KEYWORD_COLOR = Color(0xFF8AB4F8)
private val CODE_ANNOTATION_COLOR = Color(0xFFFDD663)
private val CODE_NUMBER_COLOR = Color(0xFF78D9EC)

/**
 * Lightweight, allocation-bounded syntax highlighting using the existing MCA dark code palette.
 * Preserves 100% of original characters, whitespace, and line endings (`result.text == code`).
 */
internal fun buildHighlightedCode(code: String, language: String?): AnnotatedString {
    if (code.isEmpty() || code.length > MESSAGE_RENDER_PAGE_CHARS) {
        return AnnotatedString(code)
    }
    val normalizedLang = language?.trim()?.lowercase().orEmpty()
    if (normalizedLang in setOf("text", "plain", "txt", "markdown", "md")) {
        return AnnotatedString(code)
    }
    return buildAnnotatedString {
        append(code)
        for (match in CODE_SYNTAX_TOKEN_PATTERN.findAll(code)) {
            val token = match.value
            val first = token[0]
            val color = when {
                token.startsWith("//") || token.startsWith("/*") ||
                    (first == '#' && normalizedLang !in setOf("c", "cpp", "c++", "css", "html")) ->
                    CODE_COMMENT_COLOR
                first == '"' || first == '\'' || first == '`' -> CODE_STRING_COLOR
                first == '@' || first == '#' -> CODE_ANNOTATION_COLOR
                first.isDigit() -> CODE_NUMBER_COLOR
                else -> CODE_KEYWORD_COLOR
            }
            addStyle(
                style = SpanStyle(color = color),
                start = match.range.first,
                end = match.range.last + 1
            )
        }
    }
}
