package com.muyuchat.mca

import com.muyuchat.mca.ChatImagePromptBridge.ProtectedPromptBranch
import com.muyuchat.mca.ChatImagePromptBridge.ProtectedPromptKind
import com.muyuchat.mca.ChatImagePromptBridge.ProtectedPromptSpan
import java.security.MessageDigest

/**
 * Protected-marker fidelity for chat image prompts: the spans a chat model must copy byte for byte,
 * the digests that make them auditable, and the numeric semantics that survive a translation pass.
 *
 * ## Where this comes from
 *
 * Moved verbatim out of `ChatImagePromptBridge` (a 1,651-line object) so the bridge file keeps the
 * request/orchestration responsibilities and this file owns exactly one: what may not be rewritten.
 * Only the declaration names changed here, and only to carry the responsibility prefix that keeps
 * same-package top-level declarations from colliding; every body, regex, comment and literal is the
 * original text. `ChatImagePromptBridge.protectedPromptSpansPreserved` still exists as a one-line
 * forwarder, so no caller moved.
 *
 * ## What must not change
 *
 * The marker alphabet, the span ordering/overlap rules, the digests, and the Chinese/Arabic number
 * conversions are all part of the prompt contract between the UI, the worker and image history. The
 * conversion tables below are data, not implementation detail: changing one silently changes which
 * counts and ordinals an image model receives.
 */
/** Preserve control syntax that a chat model must not translate or silently discard. */
internal fun chatImagePromptProtectedTokens(
    prompt: String,
    includeNumericLiterals: Boolean = true
): List<String> {
    val patterns = listOf(
        CHAT_IMAGE_PROMPT_ANGLE_CONTROL_TOKEN,
        Regex("(?i)(?:lora|lyco|lycoris|embedding):[A-Za-z0-9_+./-]{1,120}(?::[+-]?\\d+(?:\\.\\d+)?)?"),
        Regex("\\([^\\r\\n()]{1,160}:[+-]?\\d+(?:\\.\\d+)?\\)"),
        Regex("\\[[^\\r\\n\\[\\]]{1,160}:[+-]?\\d+(?:\\.\\d+)?\\]")
    )
    val tokens = buildList {
        patterns.forEach { pattern ->
            pattern.findAll(prompt).forEach { match ->
                if (match.value !in this) add(match.value)
            }
        }
        if (includeNumericLiterals) {
            // Counts and ordinals are protected in the direct-prompt path. Structured image-skill
            // payloads can contain unrelated numbers such as width/height, seed, or API version;
            // those values are summarized by the model instead of being treated as prompt syntax.
            CHAT_IMAGE_PROMPT_ARABIC_NUMBER.findAll(prompt).forEach { match ->
                if (match.value !in this) add(match.value)
            }
            CHAT_IMAGE_PROMPT_CHINESE_NUMBER.findAll(prompt).forEach { match ->
                val effective = chatImagePromptChineseNumberReplacement(match.value) ?: match.value
                if (effective !in this) add(effective)
            }
        }
    }
    return tokens.filterNot { token ->
        // A <lora:...> wrapper already protects its inner lora:name token.
        tokens.any { other -> other != token && other.length > token.length && other.contains(token) }
    }
}

internal fun chatImagePromptProtectedSpans(prompt: String): List<ProtectedPromptSpan> {
    val patterns = listOf(
        CHAT_IMAGE_PROMPT_ANGLE_CONTROL_TOKEN to ProtectedPromptKind.LORA_OR_EMBEDDING,
        Regex("(?i)(?:lora|lyco|lycoris|embedding):[A-Za-z0-9_+./-]{1,120}(?::[+-]?\\d+(?:\\.\\d+)?)?") to ProtectedPromptKind.LORA_OR_EMBEDDING,
        Regex("\\([^\\r\\n()]{1,160}:[+-]?\\d+(?:\\.\\d+)?\\)") to ProtectedPromptKind.WEIGHT,
        Regex("\\[[^\\r\\n\\[\\]]{1,160}:[+-]?\\d+(?:\\.\\d+)?\\]") to ProtectedPromptKind.WEIGHT
    )
    val matches = patterns.flatMap { (pattern, kind) ->
        pattern.findAll(prompt).map { match ->
            val before = prompt.substring(0, match.range.first)
            val lastNegative = CHAT_IMAGE_PROMPT_NEGATIVE_BRANCH_LABEL.findAll(before).lastOrNull()?.range?.first ?: -1
            val lastPositive = CHAT_IMAGE_PROMPT_POSITIVE_BRANCH_LABEL.findAll(before).lastOrNull()?.range?.first ?: -1
            ProtectedPromptSpan(
                branch = if (lastNegative > lastPositive) ProtectedPromptBranch.NEGATIVE else ProtectedPromptBranch.POSITIVE,
                kind = kind,
                originalOffset = match.range.first,
                rawText = match.value,
                digest = chatImagePromptProtectedDigest(match.value)
            )
        }.toList()
    }.sortedBy { it.originalOffset }
    return matches.filter { candidate ->
        matches.none { other ->
            other !== candidate && other.originalOffset <= candidate.originalOffset &&
                other.originalOffset + other.rawText.length >= candidate.originalOffset + candidate.rawText.length &&
                other.rawText.length > candidate.rawText.length
        }
    }
}

internal fun chatImagePromptProtectedSpansPreserved(
    original: List<ProtectedPromptSpan>,
    positive: String,
    negative: String?
): Boolean {
    val expected = original.groupingBy { Triple(it.branch, it.kind, it.rawText) }.eachCount()
    val actual = chatImagePromptProtectedSpans(
        "Positive prompt: $positive\nNegative prompt: ${negative.orEmpty()}"
    ).groupingBy { Triple(it.branch, it.kind, it.rawText) }.eachCount()
    return expected.all { (token, count) -> actual[token] == count }
}

private fun chatImagePromptProtectedDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

/**
 * Payload sent to the chat model with syntax and counts replaced by stable ASCII markers.
 * Markers are intentionally plain words rather than punctuation so small local models are less
 * likely to emit malformed JSON/Markdown around them.
 */
internal data class ChatImagePromptProtectedPayload(
    val payload: String,
    val replacements: Map<String, String>,
    val markerBranches: Map<String, ProtectedPromptBranch>
)

private val CHAT_IMAGE_PROMPT_PROTECTED_MARKER = Regex("MCA_KEEP_[A-Z]+_\\d+")
private val CHAT_IMAGE_PROMPT_NEGATIVE_BRANCH_LABEL = Regex(
    "(?im)(?:^|[,;，；\\n])\\s*(?:negative(?:[\\s_-]+prompt)?|negative_prompt|负面提示词|负向|负面)\\s*[:：=]"
)
private val CHAT_IMAGE_PROMPT_POSITIVE_BRANCH_LABEL = Regex(
    "(?im)(?:^|[,;，；\\n])\\s*(?:positive(?:[\\s_-]+prompt)?|positive_prompt|中文正向|英文正向|正向|正面)\\s*[:：=]"
)
// Only preserve angle-bracket forms that are known diffusion control syntax. A broad
// `<...>` matcher makes arbitrary HTML/XML/Markdown pasted into the image skill look like a
// mandatory LoRA token and can incorrectly reject an otherwise valid model summary.
private val CHAT_IMAGE_PROMPT_ANGLE_CONTROL_TOKEN = Regex(
    "(?i)<(?:lora|lyco|lycoris|embedding|ti|textual_inversion):[^>\\r\\n]{1,200}>"
)
// The generated marker contains an underscore before its index. Exclude underscore so the
// second pass cannot recursively replace the marker's own numeric suffix.
private val CHAT_IMAGE_PROMPT_ARABIC_NUMBER = Regex("(?<![A-Za-z0-9_])\\d+(?:\\.\\d+)?(?![A-Za-z0-9_])")
// Structured image-skill requests keep arbitrary numeric metadata in the model payload, but
// counts attached to visual nouns remain protected. This also covers compact diffusion syntax
// such as `1girl` and Chinese measure words such as `第2张图`.
private val CHAT_IMAGE_PROMPT_SEMANTIC_ARABIC_COUNT = Regex(
    "(?i)(?<![A-Za-z0-9_])\\d+(?=(?:\\s*(?:girls?|boys?|women?|men?|people|persons?|humans?|characters?|figures?|cats?|dogs?|birds?|horses?|cars?|trees?|flowers?|faces?|eyes?|hands?|images?|pictures?|panels?|scenes?|objects?|subjects?|张|只|个|位|人|名|幅|辆|匹|头|本|杯|朵|棵|座|间|层|面|颗|粒|枚|台|部|种|组|列|排|行|页|章|套|双|对|方|侧|图)))"
)
// A lone "一"/"二" is commonly part of ordinary Chinese wording ("一只猫") and is
// naturally rendered as an English article by the chat model. Protect explicit plural/compound
// counts where losing the number changes the image ("两只", "十二人", "一百张").
private val CHAT_IMAGE_PROMPT_CHINESE_NUMBER = Regex(
    // Keep explicit ordinals (第一/第二/第十二) intact as well as plural
    // counts. A lone 一/二 is usually an article in Chinese prose, but the
    // 第 prefix makes the sequence semantic and it must not be renumbered by
    // the chat model during prompt translation.
    // Single-digit counts with an explicit measure word (三只猫, 十个人) are
    // semantic too. Keep 一 unprotected for compatibility with ordinary
    // article-like phrases such as 一只猫; the model's English translation is
    // still expected to retain the singular meaning.
    "第[零〇一二三四五六七八九十百千万亿两]+|两|[零〇一二三四五六七八九十百千万亿]{2,}|" +
        "[零〇二三四五六七八九十](?=[个只张幅位人名条件辆匹头本杯朵棵座间层面颗粒枚台部种组列排行页章套双对方侧图])"
)

internal fun chatImagePromptProtect(
    prompt: String,
    protectNumericLiterals: Boolean = true
): ChatImagePromptProtectedPayload {
    val replacements = linkedMapOf<String, String>()
    val markerBranches = linkedMapOf<String, ProtectedPromptBranch>()
    var index = 0
    var value = prompt

    fun replaceMatches(pattern: Regex, kind: String, convert: (String) -> String = { it }) {
        value = pattern.replace(value) { match ->
            val marker = "MCA_KEEP_${kind}_${index++}"
            replacements[marker] = convert(match.value)
            val before = value.substring(0, match.range.first)
            val lastNegative = CHAT_IMAGE_PROMPT_NEGATIVE_BRANCH_LABEL.findAll(before).lastOrNull()?.range?.first ?: -1
            val lastPositive = CHAT_IMAGE_PROMPT_POSITIVE_BRANCH_LABEL.findAll(before).lastOrNull()?.range?.first ?: -1
            markerBranches[marker] = if (lastNegative > lastPositive) {
                ProtectedPromptBranch.NEGATIVE
            } else {
                ProtectedPromptBranch.POSITIVE
            }
            marker
        }
    }

    // Protect tags and weighted clauses first; their inner numbers must not be replaced twice.
    replaceMatches(CHAT_IMAGE_PROMPT_ANGLE_CONTROL_TOKEN, "TOKEN")
    replaceMatches(Regex("\\([^\\r\\n()]{1,160}:[+-]?\\d+(?:\\.\\d+)?\\)"), "TOKEN")
    replaceMatches(Regex("\\[[^\\r\\n\\[\\]]{1,160}:[+-]?\\d+(?:\\.\\d+)?\\]"), "TOKEN")
    replaceMatches(
        Regex("(?i)(?:lora|lyco|lycoris|embedding):[A-Za-z0-9_+./-]{1,120}(?::[+-]?\\d+(?:\\.\\d+)?)?"),
        "TOKEN"
    )
    if (protectNumericLiterals) {
        // Arabic counts/weights that are not inside a protected syntax token.
        replaceMatches(CHAT_IMAGE_PROMPT_ARABIC_NUMBER, "NUMBER")
        // Chinese cardinal/ordinal words are converted to ASCII before the chat model runs.
        // Keeping the 第 prefix in Chinese would reintroduce non-ASCII text after a valid
        // translation.
        replaceMatches(CHAT_IMAGE_PROMPT_CHINESE_NUMBER, "NUMBER") { chatImagePromptChineseNumberReplacement(it) ?: it }
    } else {
        // Image-skill payloads may contain transport numbers such as `seed: 42`, `size: 1024x1024`,
        // and API versions. Those values must remain visible to the chat model so it can ignore
        // them semantically, but visual counts still need a machine check. Protect only numbers
        // attached to a visual noun (for example `2 cats`, `1girl`, or `第2张图`).
        //
        // This branch is deliberately narrower than CHAT_IMAGE_PROMPT_ARABIC_NUMBER: it keeps arbitrary JSON/YAML
        // metadata opaque while preventing a model from silently changing a requested count.
        replaceMatches(CHAT_IMAGE_PROMPT_SEMANTIC_ARABIC_COUNT, "NUMBER")
        replaceMatches(CHAT_IMAGE_PROMPT_CHINESE_NUMBER, "NUMBER") { chatImagePromptChineseNumberReplacement(it) ?: it }
    }
    return ChatImagePromptProtectedPayload(value, replacements, markerBranches)
}

internal fun chatImagePromptRestoreProtectedPart(
    output: String,
    protected: ChatImagePromptProtectedPayload
): String? {
    var restored = output
    for ((marker, original) in protected.replacements) {
        if (restored.contains(marker)) restored = restored.replace(marker, original)
    }
    // A model sometimes changes marker case or inserts spaces; accepting that would make a
    // protected weight/count unverifiable. Any marker left in either branch is rejected by the
    // caller after both branches have been restored.
    if (CHAT_IMAGE_PROMPT_PROTECTED_MARKER.containsMatchIn(restored)) return null
    return restored
}

/** Converts the common Chinese cardinal forms used in prompts to an ASCII number. */
private fun chatImagePromptChineseNumberToArabic(value: String): String? {
    if (value.isBlank()) return null
    val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    if (value.length == 1 && digit.containsKey(value[0])) return digit[value[0]].toString()
    var total = 0
    var section = 0
    var number = 0
    fun flushSection(multiplier: Int) {
        section += number
        total += section * multiplier
        section = 0
        number = 0
    }
    for (char in value) {
        when {
            digit.containsKey(char) -> number = digit.getValue(char)
            char == '十' -> {
                section += if (number == 0) 1 else number
                number = 0
                section *= 10
            }
            char == '百' -> {
                section += if (number == 0) 1 else number
                number = 0
                section *= 100
            }
            char == '千' -> {
                section += if (number == 0) 1 else number
                number = 0
                section *= 1000
            }
            char == '万' -> flushSection(10_000)
            char == '亿' -> flushSection(100_000_000)
            else -> return null
        }
    }
    val result = total + section + number
    return result.takeIf { it > 0 }?.toString()
}

/** Converts an explicit Chinese ordinal to stable ASCII wording for image prompts. */
private fun chatImagePromptChineseNumberReplacement(value: String): String? {
    if (!value.startsWith('第')) return chatImagePromptChineseNumberToArabic(value)
    val number = chatImagePromptChineseNumberToArabic(value.removePrefix("第"))?.toIntOrNull() ?: return null
    return when (number) {
        0 -> "0th"
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        5 -> "fifth"
        6 -> "sixth"
        7 -> "seventh"
        8 -> "eighth"
        9 -> "ninth"
        10 -> "tenth"
        11 -> "eleventh"
        12 -> "twelfth"
        else -> when (number % 100) {
            11, 12, 13 -> "${number}th"
            else -> when (number % 10) {
                1 -> "${number}st"
                2 -> "${number}nd"
                3 -> "${number}rd"
                else -> "${number}th"
            }
        }
    }
}

/**
 * Checks whether a structured image-skill response preserved a visual count while translating
 * the marker into ordinary English.  This is intentionally narrow: it accepts a cardinal word
 * (`two cats`), an explicit count before a visual noun (`2 cats`), or compact diffusion syntax
 * (`1girl`), while metadata numbers and bare numbers remain invalid.
 */
internal fun chatImagePromptSemanticNumberPreserved(
    replacement: String,
    emitted: String
): Boolean {
    val value = replacement.trim().lowercase()
    if (value.isBlank()) return false

    // Ordinals are already emitted as stable English words by chatImagePromptChineseNumberReplacement().
    if (value.any { it.isLetter() } && !value.all { it.isDigit() || it == '.' }) {
        return Regex("(?i)(?<![A-Za-z0-9_])${Regex.escape(value)}(?![A-Za-z0-9_])")
            .containsMatchIn(emitted)
    }

    val number = value.toIntOrNull() ?: return false
    val escaped = Regex.escape(number.toString())
    val visualNoun = "(?:girls?|boys?|women?|men?|people|persons?|humans?|characters?|figures?|cats?|dogs?|birds?|horses?|cars?|trees?|flowers?|faces?|eyes?|hands?|images?|pictures?|panels?|scenes?|objects?|subjects?)"
    val compactOrCounted = Regex(
        "(?i)(?<![A-Za-z0-9_])$escaped(?:\\s*$visualNoun|(?=$visualNoun))"
    )
    if (compactOrCounted.containsMatchIn(emitted)) return true

    val cardinal = chatImagePromptEnglishCardinal(number) ?: return false
    return Regex("(?i)(?<![A-Za-z0-9_])${Regex.escape(cardinal)}(?![A-Za-z0-9_])")
        .containsMatchIn(emitted)
}

internal fun chatImagePromptAuthoredTokenOccurrences(token: String, output: String): Int {
    val numeric = token.toDoubleOrNull()?.let { token.matches(Regex("\\d+(?:\\.\\d+)?")) }
    val pattern = if (numeric == true) {
        val escaped = Regex.escape(token)
        val standalone = Regex("(?<![A-Za-z0-9_.])$escaped(?![A-Za-z0-9_.])")
        val compactVisual = Regex(
            "(?i)(?<![A-Za-z0-9_])$escaped(?=(?:girls?|boys?|women?|men?|people|persons?|humans?|characters?|figures?|cats?|dogs?|birds?|horses?|cars?|trees?|flowers?|faces?|eyes?|hands?|images?|pictures?|panels?|scenes?|objects?|subjects?))"
        )
        // Diffusion shorthand such as `1girl` is a valid authored count even though the
        // number is adjacent to the visual noun rather than separated by punctuation.
        return standalone.findAll(output).count() + compactVisual.findAll(output).count()
    } else {
        Regex(Regex.escape(token))
    }
    return pattern.findAll(output).count()
}

private fun chatImagePromptEnglishCardinal(number: Int): String? {
    if (number !in 0..999_999) return null
    val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )
    val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    fun underThousand(value: Int): String {
        val hundred = value / 100
        val remainder = value % 100
        val prefix = if (hundred > 0) "${ones[hundred]} hundred" else ""
        val suffix = when {
            remainder == 0 -> ""
            remainder < 20 -> ones[remainder]
            else -> tens[remainder / 10] + if (remainder % 10 == 0) "" else "-${ones[remainder % 10]}"
        }
        return listOf(prefix, suffix).filter(String::isNotBlank).joinToString(" ")
    }
    return when {
        number < 1000 -> underThousand(number)
        else -> {
            val thousands = number / 1000
            val remainder = number % 1000
            listOf(
                underThousand(thousands) + " thousand",
                underThousand(remainder).takeIf { remainder > 0 }
            ).filterNotNull().joinToString(" ")
        }
    }
}
