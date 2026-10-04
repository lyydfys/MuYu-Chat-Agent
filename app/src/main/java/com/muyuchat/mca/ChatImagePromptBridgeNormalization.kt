package com.muyuchat.mca

import org.json.JSONObject

/**
 * Normalization of a chat model's translation output into the two prompt branches.
 *
 * Everything here is text normalization: strip think/analysis prefaces and code fences,
 * extract complete JSON objects while respecting quoted braces and escapes, accept the
 * labelled plain-text form, then normalize punctuation and presentation list markers
 * without attempting to translate or rewrite semantic content.
 *
 * Moved verbatim out of `ChatImagePromptBridge`. `ChatImagePromptBridge.NormalizedChatImagePromptResponse` stays
 * nested in the object -- external callers name it as `ChatImagePromptBridge.X` and Kotlin
 * has no object-level typealias -- so this file refers to it by its qualified name. The
 * object keeps a one-line, test-facing forwarder for `normalizeChatImagePromptResponse`.
 */

/**
 * Normalizes both branches of a translation response.  The JSON form prevents a phrase such as
 * "negative prompt: text" from being accidentally fed to the positive CLIP encoder.  A labelled
 * plain-text form is accepted for small models that cannot emit valid JSON.
 */
internal fun chatImagePromptNormalizeResponse(raw: String): ChatImagePromptBridge.NormalizedChatImagePromptResponse? {
    var value = raw
        .replace(Regex("(?is)<think>.*?</think>"), "")
        .replace(Regex("(?is)<analysis>.*?</analysis>"), "")
        .trim()
    if (value.isBlank()) return null

    fun jsonParts(candidate: String): ChatImagePromptBridge.NormalizedChatImagePromptResponse? = runCatching {
        val trimmed = candidate.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return@runCatching null
        val json = JSONObject(trimmed)
        val positive = listOf("positive_prompt", "prompt", "english_prompt", "description")
            .firstNotNullOfOrNull { key ->
                // JSONObject.optString coerces arrays, numbers, and nested objects to text.
                // Those values are transport data, not a valid prompt branch, and accepting
                // them can leak JSON syntax into the image encoder.
                (json.opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)
            }
            ?: return@runCatching null
        val negative = listOf("negative_prompt", "negative", "exclusions")
            .firstNotNullOfOrNull { key ->
                (json.opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)
            }
        if (json.has("negative_prompt") &&
            json.opt("negative_prompt") !is String
        ) return@runCatching null
        ChatImagePromptBridge.NormalizedChatImagePromptResponse(positive.trim(), negative?.trim())
    }.getOrNull()

    // Remove a single fence before trying JSON. A Chinese preface outside the fence is ignored.
    Regex("(?is)```(?:text|plaintext|markdown|json)?\\s*(.*?)\\s*```")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(String::isNotBlank)
        ?.let { fenced -> value = fenced.trim() }
    // Models frequently surround otherwise valid JSON with a short explanation, even when asked
    // for JSON only. Extract complete objects while respecting quoted braces and escapes so the
    // explanation/wrapper cannot leak into the diffusion prompt. Only accept a recognized prompt
    // contract; a valid but unrelated JSON object remains a hard parse failure.
    val jsonCandidates = chatImagePromptExtractJsonObjects(value)
    jsonCandidates.forEach { candidate ->
        jsonParts(candidate)?.let { return chatImagePromptNormalizeParts(it) }
    }
    if (jsonCandidates.any { candidate ->
            runCatching { JSONObject(candidate) }.isSuccess
        }
    ) {
        return null
    }

    // Small/local models sometimes emit a YAML-like contract instead of JSON. Accept the
    // common underscore, dash, equals, and colon spellings so the wrapper itself never reaches
    // the image encoder. The model still remains responsible for choosing the visual content.
    val negativeLabel = Regex(
        "(?is)(?:^|\\n)\\s*(?:negative(?:[\\s_-]+prompt)?|negative_prompt|exclusions?|negative|负面提示词|负向|负面)\\s*[:：=]\\s*(.+)$"
    ).find(value)
    if (negativeLabel != null) {
        val positive = value.substring(0, negativeLabel.range.first).trim()
        .replace(
            Regex(
                "(?is)^(?:positive(?:[\\s_-]+prompt)?|positive_prompt|prompt|english(?:[\\s_-]+prompt)?|english_prompt|description)\\s*[:：=]\\s*"
            ),
            ""
        )
            .trim()
        return chatImagePromptNormalizeParts(
            ChatImagePromptBridge.NormalizedChatImagePromptResponse(positive, negativeLabel.groupValues[1].trim())
        )
    }
    return chatImagePromptNormalizeParts(
        ChatImagePromptBridge.NormalizedChatImagePromptResponse(chatImagePromptNormalizeOutput(value) ?: return null, null)
    )
}

private fun chatImagePromptNormalizeParts(
    parts: ChatImagePromptBridge.NormalizedChatImagePromptResponse
): ChatImagePromptBridge.NormalizedChatImagePromptResponse? {
    val positive = chatImagePromptNormalizeOutputInternal(parts.positive) ?: return null
    val negative = parts.negative?.let(::chatImagePromptNormalizeOutputInternal)
        ?.takeIf(String::isNotBlank)
        ?.takeUnless(::chatImagePromptIsEmptyNegativePromptPlaceholder)
    return ChatImagePromptBridge.NormalizedChatImagePromptResponse(positive, negative)
}

/** Finds balanced JSON object candidates without treating braces inside strings as delimiters. */
private fun chatImagePromptExtractJsonObjects(value: String): List<String> {
    val candidates = mutableListOf<String>()
    var objectStart = -1
    var depth = 0
    var inString = false
    var escaped = false
    value.forEachIndexed { index, char ->
        if (objectStart < 0) {
            if (char == '{') {
                objectStart = index
                depth = 1
                inString = false
                escaped = false
            }
            return@forEachIndexed
        }
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            return@forEachIndexed
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    candidates += value.substring(objectStart, index + 1)
                    objectStart = -1
                }
            }
        }
    }
    return candidates
}

/** Removes common provider wrappers without attempting to translate or rewrite semantic content. */
internal fun chatImagePromptNormalizeOutput(raw: String): String? {
    return chatImagePromptNormalizeOutputInternal(raw)
}

private fun chatImagePromptNormalizeOutputInternal(raw: String): String? {
    var value = raw
        .replace(Regex("(?is)<think>.*?</think>"), "")
        .replace(Regex("(?is)<analysis>.*?</analysis>"), "")
        .trim()
    if (value.isBlank()) return null
    // Some instruction-tuned models ignore the plain-text contract and return a tiny JSON
    // object. Accept only the common prompt fields; never execute or interpret arbitrary JSON.
    fun jsonPromptOrNull(candidate: String): String? = runCatching {
        if (!candidate.trim().startsWith("{") || !candidate.trim().endsWith("}")) return@runCatching null
        val json = JSONObject(candidate.trim())
        listOf("prompt", "english_prompt", "positive_prompt", "description")
            .firstNotNullOfOrNull { key ->
                (json.opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)
            }
    }.getOrNull()
    jsonPromptOrNull(value)?.let { extracted -> value = extracted.trim() }
    // Handle fenced text/json responses before prefix extraction. For a JSON fence, extract the
    // prompt field after removing the fence instead of feeding the JSON punctuation to CLIP.
    Regex("(?is)```(?:text|plaintext|markdown|json)?\\s*(.*?)\\s*```")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(String::isNotBlank)
        ?.let { fenced -> value = fenced.trim() }
    value = value.replace(Regex("(?is)^```(?:text|plaintext|markdown|json)?\\s*"), "")
        .replace(Regex("(?is)\\s*```$"), "")
        .trim()
    jsonPromptOrNull(value)?.let { extracted -> value = extracted.trim() }
    if (value.endsWith("```")) value = value.removeSuffix("```").trim()
    val prefixes = listOf(
        "English prompt:", "English Prompt:", "English_prompt:", "english_prompt:",
        "Positive prompt:", "Positive Prompt:", "Positive_prompt:", "positive_prompt:",
        "Prompt:", "prompt:", "英文提示词：", "英文提示词:", "英文描述：", "英文描述:",
        "正向提示词：", "正向提示词:", "提示词：", "提示词:"
    )
    prefixes.firstOrNull { value.startsWith(it) }?.let { prefix ->
        value = value.removePrefix(prefix).trim()
    }
    value = value.replace(
        Regex(
            "(?i)^[*_`#\\s]*(?:english(?:[\\s_-]+prompt)?|positive(?:[\\s_-]+prompt)?|prompt|final(?:[\\s_-]+prompt)|here(?:'s| is)\\s+the\\s+prompt|the\\s+prompt\\s+is)[*_`#\\s]*[:=]\\s*[*_`#\\s]*"
        ),
        ""
    ).trim()
    if (value.length >= 2 &&
        ((value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\''))
    ) {
        value = value.substring(1, value.length - 1).trim()
    }
    // Providers sometimes prepend a Chinese sentence and then put the usable prompt on the
    // next line. Select the strongest ASCII candidate instead of rejecting the whole response.
    if (value.containsHanScript() || value.lineSequence().count() > 1) {
        val candidates = value
            .lineSequence()
            .map { line -> line.trim().trim('`', '*', '#', '"', '\'') }
            .filter { line -> line.isNotBlank() && !line.containsHanScript() }
            .filterNot { line ->
                line.startsWith("Here ", ignoreCase = true) ||
                    line.startsWith("Sure", ignoreCase = true) ||
                    line.startsWith("当然") ||
                    line.startsWith("以下")
            }
            .toList()
        if (candidates.isNotEmpty()) {
            // A model may return one visual attribute per line. Keeping only the longest line
            // silently drops composition, lighting, or character details. JSON/labelled output
            // has already been parsed above; for a plain multi-line response, remove presentation
            // list markers and merge all usable ASCII lines into the single diffusion prompt.
            // Only line-leading markers are removed: visual counts such as `1girl` and `2 cats`
            // remain untouched.
            value = candidates
                .map(::chatImagePromptStripListMarker)
                .filter(String::isNotBlank)
                .joinToString(", ")
        }
    }
    // A model may put a Chinese lead-in and the English prompt on the same line. Remove only the
    // non-ASCII lead-in; a Chinese-only response becomes blank and is still rejected below.
    if (value.containsHanScript()) {
        val mixedLine = value.replace(Regex("^[^A-Za-z0-9<\\[(]+"), "").trim()
        if (mixedLine.isNotBlank() && !mixedLine.containsHanScript()) value = mixedLine
    }
    // Normalize punctuation commonly emitted by Chinese chat models while retaining diffusion
    // control syntax. This makes a valid English line pass the same admission grammar as direct
    // input.
    value = value
        .replace('，', ',')
        .replace('：', ':')
        .replace('；', ';')
        .replace('（', '(')
        .replace('）', ')')
        .replace('［', '[')
        .replace('］', ']')
        .replace('｛', '{')
        .replace('｝', '}')
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace('—', '-')
        .replace('–', '-')
        .replace(Regex("\\s+"), " ")
        .trim()
        // Markdown emphasis frequently wraps the whole returned prompt. Strip only
        // decoration at the boundaries; interior asterisks remain valid prompt text.
        .trim('*', '`', '#', '_')
        .trim()
    return value.takeIf(String::isNotBlank)
}

private fun chatImagePromptStripListMarker(line: String): String = line.replace(
    Regex("^\\s*(?:(?:[-*•‣▪])\\s+|(?:\\d{1,3}|[A-Za-z])[.)、:]\\s+|\\([0-9]{1,3}\\)\\s+)"),
    ""
).trim()
