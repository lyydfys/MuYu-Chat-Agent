package com.muyuchat.mca

internal val knownSearchNameTerms = setOf(
    "OpenAI",
    "ChatGPT",
    "GitHub",
    "ModelScope",
    "DashScope",
    "MiMo",
    "Qwen",
    "Claude",
    "Anthropic",
    "Android",
    "MCA"
)
internal fun String.normalizeWhitespace(): String =
    replace(Regex("""\s+"""), " ").trim()

internal fun String.limitForPrompt(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars).trimEnd() + "..."

internal fun String.expandCompactSearchWords(): String =
    replace(Regex("""(?<=[a-z])(?=[A-Z])"""), " ")
        .replace(Regex("""(?<=[A-Z])(?=[A-Z][a-z])"""), " ")
        .replace(Regex("""(?<=[\p{L}])(?=\d)"""), " ")
        .replace(Regex("""(?<=\d)(?=[\p{L}])"""), " ")

internal fun String.restoreKnownSearchNames(): String =
    replace(Regex("""\bOpen AI\b""", RegexOption.IGNORE_CASE), "OpenAI")
        .replace(Regex("""\bChat GPT\b""", RegexOption.IGNORE_CASE), "ChatGPT")
        .replace(Regex("""\bGit Hub\b""", RegexOption.IGNORE_CASE), "GitHub")
        .replace(Regex("""\bModel Scope\b""", RegexOption.IGNORE_CASE), "ModelScope")
        .replace(Regex("""\bDash Scope\b""", RegexOption.IGNORE_CASE), "DashScope")
        .replace(Regex("""\bMi Mo\b""", RegexOption.IGNORE_CASE), "MiMo")

internal fun String.cleanWebSearchText(): String =
    decodeBasicHtmlEntities()
        .replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), " ")
        .replace(Regex("""(?is)<style\b[^>]*>.*?</style>"""), " ")
        .replace(Regex("""(?is)<noscript\b[^>]*>.*?</noscript>"""), " ")
        .replace(Regex("""(?is)<svg\b[^>]*>.*?</svg>"""), " ")
        .replace(Regex("""(?is)<br\s*/?>"""), "\n")
        .replace(Regex("""(?is)</p>|</div>|</li>|</h[1-6]>"""), "\n")
        .replace(Regex("""(?is)<[^>]+>"""), " ")
        .replace(Regex("""!\[[^\]]*]\([^)]+\)"""), " ")
        .replace(Regex("""\[(.*?)]\((.*?)\)"""), "$1")
        .decodeBasicHtmlEntities()
        .normalizeWhitespace()

internal fun String.htmlToReadableText(): String =
    replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), " ")
        .replace(Regex("""(?is)<style\b[^>]*>.*?</style>"""), " ")
        .replace(Regex("""(?is)<noscript\b[^>]*>.*?</noscript>"""), " ")
        .replace(Regex("""(?is)<svg\b[^>]*>.*?</svg>"""), " ")
        .replace(Regex("""(?is)<header\b[^>]*>.*?</header>"""), " ")
        .replace(Regex("""(?is)<nav\b[^>]*>.*?</nav>"""), " ")
        .replace(Regex("""(?is)<footer\b[^>]*>.*?</footer>"""), " ")
        .replace(Regex("""(?is)<br\s*/?>"""), "\n")
        .replace(Regex("""(?is)</p>|</div>|</li>|</h[1-6]>"""), "\n")
        .replace(Regex("""(?is)<[^>]+>"""), " ")
        .decodeBasicHtmlEntities()
        .lineSequence()
        .map { it.normalizeWhitespace() }
        .filter { it.length >= 24 }
        .distinct()
        .take(80)
        .joinToString("\n")

internal fun String.extractHtmlTitle(): String =
    Regex("""(?is)<title[^>]*>(.*?)</title>""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
        .replace(Regex("""\s+"""), " ")
        .decodeBasicHtmlEntities()
        .trim()
        .limitForPrompt(120)

internal fun String.extractJinaReaderTitle(): String =
    lineSequence()
        .firstOrNull { it.startsWith("Title:", ignoreCase = true) }
        ?.substringAfter(":")
        .orEmpty()
        .normalizeWhitespace()
        .limitForPrompt(120)

internal fun String.jinaReaderToReadableText(): String {
    val markdown = substringAfter("Markdown Content:", this)
    return markdown
        .lineSequence()
        .map { line ->
            line
                .replace(Regex("""!\[[^\]]*]\([^)]+\)"""), " ")
                .replace(Regex("""\[(.*?)]\((.*?)\)"""), "$1")
                .trim()
        }
        .filterNot { line ->
            line.startsWith("Title:", ignoreCase = true) ||
                line.startsWith("URL Source:", ignoreCase = true) ||
                line.startsWith("Published Time:", ignoreCase = true) ||
                line.startsWith("Warning:", ignoreCase = true) ||
                line.equals("Markdown Content:", ignoreCase = true)
        }
        .map { it.trimStart('#', '-', '*', '>', ' ').normalizeWhitespace() }
        .filter { it.length >= 20 }
        .distinct()
        .take(80)
        .joinToString("\n")
}

internal fun String.decodeBasicHtmlEntities(): String =
    replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .decodeNumericHtmlEntities()

internal fun String.decodeNumericHtmlEntities(): String =
    replace(Regex("""&#(x?[0-9A-Fa-f]+);""")) { match ->
        val raw = match.groupValues.getOrNull(1).orEmpty()
        val codePoint = if (raw.startsWith("x", ignoreCase = true)) {
            raw.drop(1).toIntOrNull(16)
        } else {
            raw.toIntOrNull()
        }
        codePoint
            ?.takeIf { Character.isValidCodePoint(it) }
            ?.let { String(Character.toChars(it)) }
            ?: match.value
    }
