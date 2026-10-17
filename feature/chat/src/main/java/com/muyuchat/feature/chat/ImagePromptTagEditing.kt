package com.muyuchat.feature.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal fun findImagePromptActiveToken(text: String, cursor: Int): ImagePromptActiveToken? {
    if (text.length > MAX_PROMPT_TEXT_CHARS || cursor !in 1..text.length) return null
    val rawStart = text.lastIndexOf(',', startIndex = cursor - 1).let { if (it < 0) 0 else it + 1 }
    val segmentEnd = text.indexOf(',', startIndex = cursor).let { if (it < 0) text.length else it }
    var contentStart = rawStart
    while (contentStart < cursor && text[contentStart].isWhitespace()) contentStart++
    var queryEnd = cursor
    while (queryEnd > contentStart && text[queryEnd - 1].isWhitespace()) queryEnd--
    if (queryEnd <= contentStart) return null
    return ImagePromptActiveToken(
        query = text.substring(contentStart, queryEnd),
        contentStart = contentStart,
        segmentEnd = segmentEnd
    )
}

internal fun applyImagePromptTagSuggestion(
    text: String,
    cursor: Int,
    suggestion: ImagePromptTagSuggestion
): ImagePromptTextEdit? {
    val active = findImagePromptActiveToken(text, cursor) ?: return null
    if (suggestion.textualInversionId == null &&
        !isSafeCanonicalImagePromptTag(suggestion.replacementTag)
    ) {
        return null
    }
    if (suggestion.textualInversionId != null && !isSafeTagValue(suggestion.replacementTag)) {
        return null
    }
    val literal = if (suggestion.textualInversionId != null) {
        suggestion.replacementTag
    } else {
        renderImagePromptTagLiteral(suggestion.replacementTag)
    }
    val prefix = text.substring(0, active.contentStart)
    val suffixAfterDelimiter = if (active.segmentEnd < text.length) {
        text.substring(active.segmentEnd + 1).trimStart()
    } else {
        ""
    }
    val updated = buildString(prefix.length + literal.length + suffixAfterDelimiter.length + 2) {
        append(prefix)
        append(literal)
        append(", ")
        append(suffixAfterDelimiter)
    }
    if (updated.length > MAX_PROMPT_TEXT_CHARS) return null
    return ImagePromptTextEdit(updated, prefix.length + literal.length + 2)
}

internal fun adjustImagePromptActiveTagWeight(
    text: String,
    cursor: Int,
    delta: Double
): ImagePromptTextEdit? {
    if (!delta.isFinite() || abs(delta) > MAX_WEIGHT_DELTA) return null
    val segment = findNonEmptyPromptSegment(text, cursor) ?: return null
    val weighted = decodePromptWeight(segment.content) ?: return null
    val adjusted = roundToSingleDecimal(weighted.weight + delta)
        .coerceIn(MIN_PROMPT_WEIGHT, MAX_PROMPT_WEIGHT)
    val replacement = if (adjusted == 1.0) {
        weighted.body
    } else {
        "(${weighted.body}:${String.format(Locale.ROOT, "%.1f", adjusted)})"
    }
    val updated = text.replaceRange(segment.contentStart, segment.contentEnd, replacement)
    if (updated.length > MAX_PROMPT_TEXT_CHARS) return null
    return ImagePromptTextEdit(updated, segment.contentStart + replacement.length)
}

internal fun clearImagePromptActiveTag(text: String, cursor: Int): ImagePromptTextEdit? {
    val segment = findNonEmptyPromptSegment(text, cursor) ?: return null
    val commaBefore = text.lastIndexOf(',', startIndex = segment.contentStart - 1)
    val commaAfter = text.indexOf(',', startIndex = segment.contentEnd)
    return when {
        commaBefore >= 0 -> {
            val prefix = text.substring(0, commaBefore).trimEnd()
            val suffix = if (commaAfter >= 0) text.substring(commaAfter) else ""
            ImagePromptTextEdit(prefix + suffix, prefix.length)
        }

        commaAfter >= 0 -> {
            val remainder = text.substring(commaAfter + 1).trimStart()
            ImagePromptTextEdit(remainder, 0)
        }

        else -> ImagePromptTextEdit("", 0)
    }
}

internal fun renderImagePromptTagLiteral(tag: String): String {
    require(isSafeCanonicalImagePromptTag(tag)) {
        "Canonical tag is invalid for prompt insertion."
    }
    val spaced = StringBuilder(tag.length)
    var index = 0
    while (index < tag.length) {
        if (tag[index] == '\\' && index + 1 < tag.length && tag[index + 1] == '_') {
            spaced.append('_')
            index += 2
        } else {
            spaced.append(if (tag[index] == '_') ' ' else tag[index])
            index++
        }
    }

    val escaped = StringBuilder(spaced.length + 4)
    var precedingBackslashes = 0
    for (char in spaced) {
        if ((char == '(' || char == ')') && precedingBackslashes % 2 == 0) escaped.append('\\')
        escaped.append(char)
        precedingBackslashes = if (char == '\\') precedingBackslashes + 1 else 0
    }
    return escaped.toString()
}

private data class PromptSegment(
    val contentStart: Int,
    val contentEnd: Int,
    val content: String
)

private data class DecodedPromptWeight(
    val body: String,
    val weight: Double
)

private fun findNonEmptyPromptSegment(text: String, cursor: Int): PromptSegment? {
    if (text.length > MAX_PROMPT_TEXT_CHARS || cursor !in 0..text.length) return null
    val rawStart = if (cursor == 0) {
        0
    } else {
        text.lastIndexOf(',', startIndex = cursor - 1).let { if (it < 0) 0 else it + 1 }
    }
    val rawEnd = text.indexOf(',', startIndex = cursor).let { if (it < 0) text.length else it }
    var start = rawStart
    while (start < rawEnd && text[start].isWhitespace()) start++
    var end = rawEnd
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start >= end) return null
    return PromptSegment(start, end, text.substring(start, end))
}

private fun decodePromptWeight(content: String): DecodedPromptWeight? {
    var body = content.trim()
    if (body.isEmpty()) return null
    var weight = 1.0
    repeat(MAX_WEIGHT_WRAPPERS) {
        if (isWholePromptWrapper(body, '(', ')')) {
            val inner = body.substring(1, body.length - 1).trim()
            // A colon only denotes an explicit weight when it belongs to the
            // current wrapper level. Nested prompt groups (for example
            // `((tag:1.2))`) must be unwrapped before their inner colon is
            // considered. Escaped punctuation remains literal prompt text.
            val colon = findTopLevelWeightSeparator(inner)
            if (colon > 0 && colon < inner.lastIndex) {
                val explicit = inner.substring(colon + 1).trim().toDoubleOrNull()
                val explicitBody = inner.substring(0, colon).trim()
                if (
                    explicit != null && explicit.isFinite() &&
                    explicit in MIN_ACCEPTED_EXISTING_WEIGHT..MAX_ACCEPTED_EXISTING_WEIGHT &&
                    explicitBody.isNotEmpty()
                ) {
                    val wrapperDelta = weight - 1.0
                    return DecodedPromptWeight(
                        explicitBody,
                        roundToSingleDecimal(explicit + wrapperDelta)
                    )
                }
            }
            body = inner
            weight += 0.1
        } else if (isWholePromptWrapper(body, '[', ']')) {
            body = body.substring(1, body.length - 1).trim()
            weight -= 0.1
        } else {
            return DecodedPromptWeight(body, roundToSingleDecimal(weight))
        }
        if (body.isEmpty()) return null
    }
    return null
}

private fun findTopLevelWeightSeparator(value: String): Int {
    val stack = ArrayDeque<Char>()
    var candidate = -1
    var index = 0
    while (index < value.length) {
        when (val character = value[index]) {
            '\\' -> {
                if (index + 1 < value.length) index++
            }
            '(' -> stack.addLast(')')
            '[' -> stack.addLast(']')
            ')', ']' -> {
                if (stack.lastOrNull() == character) stack.removeLast()
                else return -1
            }
            ':' -> if (stack.isEmpty()) candidate = index
        }
        index++
    }
    return if (stack.isEmpty()) candidate else -1
}

private fun isWholePromptWrapper(value: String, open: Char, close: Char): Boolean {
    if (value.length < 2 || value.first() != open || value.last() != close) return false
    var depth = 0
    var index = 0
    while (index < value.length) {
        when (value[index]) {
            '\\' -> index++
            open -> depth++
            close -> {
                depth--
                if (depth < 0 || (depth == 0 && index != value.lastIndex)) return false
            }
        }
        index++
    }
    return depth == 0
}

private fun roundToSingleDecimal(value: Double): Double = round(value * 10.0) / 10.0

private const val MAX_PROMPT_TEXT_CHARS: Int = 65_536
private const val MIN_PROMPT_WEIGHT: Double = 0.1
private const val MAX_PROMPT_WEIGHT: Double = 2.0
private const val MIN_ACCEPTED_EXISTING_WEIGHT: Double = -100.0
private const val MAX_ACCEPTED_EXISTING_WEIGHT: Double = 100.0
private const val MAX_WEIGHT_DELTA: Double = 1.0
private const val MAX_WEIGHT_WRAPPERS: Int = 16
