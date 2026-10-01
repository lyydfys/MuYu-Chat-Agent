package com.muyuchat.mca

import com.muyuchat.core.engine.localPromptTextFootprint

/**
 * UTF-8 byte-boundary arithmetic for prompt text.
 *
 * A prompt has two units of length that disagree as soon as the text is not pure ASCII: the
 * UTF-8 byte count a native tokenizer or a size budget measures, and the Kotlin string index
 * the UI uses to slice and highlight. Both operations here move between them without ever
 * cutting a character in half.
 *
 * These live together because they answer the same question from opposite directions:
 * [takeUtf8Prefix] trims text down to a byte budget, and [utf8ByteOffsetToUtf16] maps a byte
 * position back to a string index. Getting either wrong silently changes what the user typed,
 * which the plan forbids, so both are covered by tests that pin the multi-byte cases.
 *
 * Extracted verbatim from `MainViewModel` during P2; no branch or limit was changed.
 *
 * `localPromptTextFootprint` already lives in `core:engine`. Consolidating this file with it
 * is a module move, which P2 deliberately does not do -- it belongs to a later slice.
 */

/** Text that was reduced to a UTF-8 byte budget, and whether anything was dropped. */
internal data class Utf8BoundedText(val text: String, val truncated: Boolean)

/**
 * Longest prefix of this string that fits in [maxBytes] UTF-8 bytes.
 *
 * Whole code points are appended while they fit, so the result never ends with a partial
 * character. A string that already fits is returned unchanged with `truncated = false`.
 *
 * @throws IllegalArgumentException when [maxBytes] is not positive.
 */
internal fun String.takeUtf8Prefix(maxBytes: Int): Utf8BoundedText {
    require(maxBytes > 0)
    if (localPromptTextFootprint(this).utf8Bytes <= maxBytes) {
        return Utf8BoundedText(this, truncated = false)
    }
    val output = StringBuilder(minOf(length, maxBytes))
    var used = 0
    var index = 0
    while (index < length) {
        val codePoint = Character.codePointAt(this, index)
        val size = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        if (used > maxBytes - size) break
        output.appendCodePoint(codePoint)
        used += size
        index += Character.charCount(codePoint)
    }
    return Utf8BoundedText(output.toString(), truncated = true)
}

/**
 * Converts a UTF-8 byte offset reported by a native tokenizer into a Kotlin string index.
 *
 * Returns null when [byteOffset] is outside the prompt, or when it falls **inside** a
 * multi-byte sequence. The latter is the point of this function: a tokenizer that stops
 * mid-character must report "unknown" rather than an index that would make the UI highlight
 * or truncate the wrong part of the user's text.
 *
 * The check works by decoding the byte prefix and re-encoding it. `String(bytes, UTF_8)`
 * replaces malformed input with U+FFFD instead of throwing, so the round trip is what
 * detects a split character -- decoding alone cannot.
 */
internal fun utf8ByteOffsetToUtf16(prompt: String, byteOffset: Int): Int? {
    if (byteOffset !in 0..prompt.toByteArray(Charsets.UTF_8).size) return null
    val bytes = prompt.toByteArray(Charsets.UTF_8)
    val prefixBytes = bytes.copyOf(byteOffset)
    val prefix = runCatching { String(prefixBytes, Charsets.UTF_8) }.getOrNull() ?: return null
    return prefix.toByteArray(Charsets.UTF_8)
        .takeIf { it.contentEquals(prefixBytes) }
        ?.let { prefix.length }
}
