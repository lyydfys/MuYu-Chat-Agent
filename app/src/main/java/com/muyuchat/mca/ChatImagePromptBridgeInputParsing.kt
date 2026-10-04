package com.muyuchat.mca

/**
 * Parses an authored image prompt into the positive/negative sections the bridge works on.
 *
 * This is the input half of prompt construction: one-line `a cat, negative prompt: blurry`
 * forms, English/Chinese section labels, inline negative labels, UI token annotations and
 * English-positive detection. Moved verbatim out of `ChatImagePromptBridge` together with
 * its section enum and its label regexes.
 *
 * `ChatImagePromptBridge.ChatImagePromptInputParts` stays nested in the object (tests and
 * callers name it that way), so this file refers to it by its qualified name. The object
 * keeps a one-line member forwarder because callers use `ChatImagePromptBridge.parseChatImagePromptInput`.
 */

private enum class ChatImagePromptInputSection { POSITIVE, NEGATIVE }

/** Splits explicit positive/negative sections and discards UI token-count annotations. */
internal fun chatImagePromptParseInput(prompt: String): ChatImagePromptBridge.ChatImagePromptInputParts {
    val sections = linkedMapOf(
        ChatImagePromptInputSection.POSITIVE to mutableListOf<String>(),
        ChatImagePromptInputSection.NEGATIVE to mutableListOf<String>()
    )
    var currentSection = ChatImagePromptInputSection.POSITIVE
    var currentSectionIsEnglishPositive = false
    var explicitSections = false
    var explicitEnglishPositive = false
    val explicitEnglishPositiveLines = mutableListOf<String>()
    val cleanedLines = mutableListOf<String>()

    // Accept the common one-line form too ("a cat, negative prompt: blurry"). Splitting this
    // before section parsing keeps the negative branch out of the positive encoder even when a
    // user did not paste each section on its own line.
    val inputLines = prompt.lines().flatMap { rawLine ->
        val inlineNegative = chatImagePromptTopLevelInlineNegative(rawLine)
        if (inlineNegative != null && inlineNegative.first.isNotBlank()) {
            listOf(
                // The separator may be matched as whitespace rather than the comma itself.
                // Strip punctuation left on the positive branch so an inline negative label
                // never changes the authored prompt (`a cat, negative_prompt: blurry` ->
                // `a cat`, not `a cat,`).
                inlineNegative.first.trim().trimEnd(',', '，', ';', '；').trim(),
                "负面提示词：${inlineNegative.second.trim()}"
            )
        } else {
            listOf(rawLine)
        }
    }
    for (rawLine in inputLines) {
        if (CHAT_IMAGE_BRIDGE_TOKEN_ANNOTATION_PATTERNS.any { it.matches(rawLine.trim()) }) continue
        val line = CHAT_IMAGE_BRIDGE_PROMPT_LABEL_ANNOTATION
            .replace(CHAT_IMAGE_BRIDGE_TOKEN_ANNOTATION_SUFFIX.replace(rawLine, ""), "")
            .trimEnd()
        if (line.isBlank()) continue
        val englishLabel = CHAT_IMAGE_BRIDGE_ENGLISH_SECTION.matchEntire(line)
        val chineseLabel = CHAT_IMAGE_BRIDGE_CHINESE_SECTION.matchEntire(line)
        cleanedLines += line
        when {
            englishLabel != null -> {
                explicitSections = true
                val value = englishLabel.groupValues[2].trim()
                val isNegative = englishLabel.groupValues[1].contains("negative", ignoreCase = true)
                currentSection = if (isNegative) {
                    ChatImagePromptInputSection.NEGATIVE
                } else {
                    ChatImagePromptInputSection.POSITIVE
                }
                currentSectionIsEnglishPositive = !isNegative
                value.takeIf(String::isNotBlank)?.let {
                    when {
                        currentSection == ChatImagePromptInputSection.NEGATIVE ->
                            sections.getValue(currentSection).add(it)
                        it.containsHanScript() -> sections.getValue(currentSection).add(it)
                        else -> {
                            explicitEnglishPositive = true
                            explicitEnglishPositiveLines.add(it)
                        }
                    }
                }
            }
            chineseLabel != null -> {
                explicitSections = true
                val label = chineseLabel.groupValues[1]
                val isNegative = label.contains("负")
                currentSection = if (isNegative) ChatImagePromptInputSection.NEGATIVE else ChatImagePromptInputSection.POSITIVE
                val isEnglishPositive = label.startsWith("英文") && !isNegative
                currentSectionIsEnglishPositive = isEnglishPositive
                chineseLabel.groupValues[2].trim().takeIf(String::isNotBlank)?.let { value ->
                    if (isEnglishPositive && !value.containsHanScript()) {
                        explicitEnglishPositive = true
                        explicitEnglishPositiveLines.add(value)
                    } else {
                        sections.getValue(currentSection).add(value)
                    }
                }
            }
            else -> line.trim().takeIf(String::isNotBlank)
                ?.let { value ->
                    if (currentSection == ChatImagePromptInputSection.POSITIVE &&
                        currentSectionIsEnglishPositive && !value.containsHanScript()
                    ) {
                        explicitEnglishPositive = true
                        explicitEnglishPositiveLines.add(value)
                    } else {
                        sections.getValue(currentSection).add(value)
                    }
                }
        }
    }

    val cleaned = cleanedLines.joinToString("\n").trim()
    val positiveSection = sections.getValue(ChatImagePromptInputSection.POSITIVE).joinToString("\n").trim()
    val explicitEnglish = explicitEnglishPositiveLines.joinToString("\n").trim()
    val negative = sections.getValue(ChatImagePromptInputSection.NEGATIVE).joinToString("\n").trim()
        .takeIf(String::isNotBlank)
    val positive = positiveSection.ifBlank {
        explicitEnglish.ifBlank { if (explicitSections && negative != null) "" else cleaned }
    }
    val englishPositive = explicitEnglish.takeIf(String::isNotBlank)
        ?: positive.takeIf {
            it.isNotBlank() && (explicitEnglishPositive || (explicitSections && !it.containsHanScript()))
        }
    return ChatImagePromptBridge.ChatImagePromptInputParts(
        cleanedInput = cleaned,
        positivePrompt = positive,
        englishPositivePrompt = englishPositive,
        negativePrompt = negative,
        hasExplicitSections = explicitSections
    )
}

private fun chatImagePromptTopLevelInlineNegative(value: String): Pair<String, String>? {
    val closing = ArrayDeque<Char>()
    var quote: Char? = null
    var escaped = false
    value.forEachIndexed { index, char ->
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '(' || char == '[' || char == '<' ->
                closing.addLast(when (char) { '(' -> ')'; '[' -> ']'; else -> '>' })
            closing.isNotEmpty() && char == closing.last() -> closing.removeLast()
            closing.isEmpty() && (char.isWhitespace() || char in ",，;；") -> {
                val remainder = value.substring(index).trimStart(',', '，', ';', '；', ' ', '\t')
                val label = CHAT_IMAGE_BRIDGE_INLINE_NEGATIVE_LABEL.matchEntire(remainder)
                if (label != null) return value.substring(0, index) to label.groupValues[1]
            }
        }
    }
    return null
}

private val CHAT_IMAGE_BRIDGE_ENGLISH_SECTION = Regex(
    "(?i)^\\s*((?:english[\\s_-]+)?(?:positive|negative)(?:[\\s_-]+prompt)?)(?:\\s*[:：=]\\s*(.*))?\\s*$"
)

private val CHAT_IMAGE_BRIDGE_CHINESE_SECTION = Regex(
    "^\\s*(中文正向|中文正面|英文正向|英文正面|英文负向|英文负面|正向|正面|负向|负面)(?:提示词)?(?:\\s*[:：]\\s*(.*))?\\s*$"
)

private val CHAT_IMAGE_BRIDGE_INLINE_NEGATIVE_LABEL = Regex(
    "(?is)^(?:negative(?:[\\s_-]+prompt)?|负面提示词|负向|负面)\\s*[:：=]\\s*(.+)$"
)

private val CHAT_IMAGE_BRIDGE_PROMPT_LABEL_ANNOTATION = Regex(
    "(?i)\\s*[（(][^）)]*(?:token(?:s)?|令牌|推荐)[^）)]*[）)]"
)

private val CHAT_IMAGE_BRIDGE_TOKEN_ANNOTATION_PATTERNS = listOf(
    Regex("(?i)^\\s*(?:prompt\\s*)?tokens?(?:\\s*(?:count|数量|数))?\\s*[:：]?\\s*\\d+\\s*/\\s*\\d+(?:\\s*(?:tokens?|个令牌|令牌|个))?\\s*$"),
    Regex("(?i)^\\s*提示词\\s*token(?:数量|数)?\\s*[:：]?\\s*\\d+\\s*/\\s*\\d+(?:\\s*(?:tokens?|个令牌|令牌|个))?\\s*$"),
    Regex("(?i)^\\s*\\d+\\s*/\\s*\\d+\\s*(?:tokens?|个令牌|令牌)\\s*$")
)

private val CHAT_IMAGE_BRIDGE_TOKEN_ANNOTATION_SUFFIX = Regex(
    "(?i)(?:[,，;；]\\s*|\\s+)(?:prompt\\s*)?tokens?(?:\\s*(?:count|数量|数))?\\s*[:：]?\\s*\\d+\\s*/\\s*\\d+(?:\\s*(?:tokens?|个令牌|令牌|个))?\\s*$"
)
