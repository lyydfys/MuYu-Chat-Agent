package com.muyuchat.feature.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal fun isImagePromptTagFuzzyScanAllowed(termCount: Long): Boolean =
    termCount in 0L..ImagePromptTagAutocomplete.MAX_FUZZY_SCANNED_TERMS

internal fun isSafeTagValue(value: String): Boolean =
    value.isNotBlank() &&
        value == value.trim() &&
        value.length <= MAX_TAG_TEXT_CHARS &&
        !hasUnpairedSurrogate(value) &&
        value.none { it == ',' || it == '\r' || it == '\n' || it == '\u0000' || it.isISOControl() }

/**
 * A tag selected from the dictionary becomes executable prompt text. Keep that one direction
 * intentionally narrower than aliases and translations: they are search text and may be Chinese,
 * while a canonical replacement must be an English-compatible ASCII tag.
 */
internal fun isSafeCanonicalImagePromptTag(value: String): Boolean =
    isSafeTagValue(value) && value.all { character -> character.code in 0x20..0x7e }

internal fun isSafeTranslationValue(value: String): Boolean =
    value.isNotBlank() &&
        value == value.trim() &&
        value.length <= MAX_TRANSLATION_TEXT_CHARS &&
        !hasUnpairedSurrogate(value) &&
        value.none { it == '\r' || it == '\n' || it == '\u0000' || it.isISOControl() }

internal fun hasUnpairedSurrogate(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return true
                index += 2
            }

            char.isLowSurrogate() -> return true
            else -> index++
        }
    }
    return false
}

internal const val MAX_TAG_TEXT_CHARS: Int = 256
private const val MAX_TRANSLATION_TEXT_CHARS: Int = 512
