package com.muyuchat.core.engine

import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject


/**
 * GenieX callbacks can mix normal JVM Unicode with GPT-2's reversible bytes-to-Unicode alphabet.
 * A possible UTF-8 lead byte is held across callbacks and decoded only after a complete, valid
 * sequence arrives. GPT-2's U+0100-and-above escape characters are interpreted only as
 * continuation bytes for such a sequence; otherwise they are emitted verbatim because they are
 * indistinguishable from intentional Latin Extended-A text.
 */
internal class GenieXUtf8ChunkAssembler {
    private val pendingUtf8Chars = StringBuilder(4)
    private val pendingUtf8Bytes = IntArray(4)
    private var pendingUtf8Size = 0
    private var expectedUtf8Size = 0
    private var pendingHighSurrogate: Char? = null

    fun append(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val output = StringBuilder()
        var index = 0

        pendingHighSurrogate?.let { highSurrogate ->
            flushPendingUtf8(output)
            output.append(highSurrogate)
            if (Character.isLowSurrogate(chunk.first())) {
                output.append(chunk.first())
                index = 1
            }
            pendingHighSurrogate = null
        }

        while (index < chunk.length) {
            val char = chunk[index]
            when {
                Character.isHighSurrogate(char) -> {
                    flushPendingUtf8(output)
                    if (index == chunk.lastIndex) {
                        pendingHighSurrogate = char
                        index += 1
                    } else {
                        output.append(char)
                        if (Character.isLowSurrogate(chunk[index + 1])) {
                            output.append(chunk[index + 1])
                            index += 2
                        } else {
                            index += 1
                        }
                    }
                }

                Character.isLowSurrogate(char) -> {
                    flushPendingUtf8(output)
                    output.append(char)
                    index += 1
                }

                else -> {
                    appendBmpChar(char, output)
                    index += 1
                }
            }
        }
        return output.toString()
    }

    fun finish(): String = buildString {
        flushPendingUtf8(this)
        pendingHighSurrogate?.let(::append)
        pendingHighSurrogate = null
    }

    private fun appendBmpChar(char: Char, output: StringBuilder) {
        val byte = gpt2Byte(char)
        if (expectedUtf8Size != 0) {
            if (byte != null && byte in UTF8_CONTINUATION_RANGE) {
                pendingUtf8Chars.append(char)
                pendingUtf8Bytes[pendingUtf8Size] = byte
                pendingUtf8Size += 1
                if (pendingUtf8Size == expectedUtf8Size) {
                    val codePoint = decodePendingUtf8()
                    if (codePoint == null) {
                        output.append(pendingUtf8Chars)
                    } else {
                        output.appendCodePoint(codePoint)
                    }
                    clearPendingUtf8()
                }
                return
            }
            flushPendingUtf8(output)
        }

        if (byte == null || char.code != byte) {
            output.append(char)
            return
        }

        when (val sequenceSize = utf8SequenceSize(byte)) {
            1 -> output.append(byte.toChar())
            2, 3, 4 -> {
                pendingUtf8Chars.append(char)
                pendingUtf8Bytes[0] = byte
                pendingUtf8Size = 1
                expectedUtf8Size = sequenceSize
            }
            else -> output.append(char)
        }
    }

    private fun decodePendingUtf8(): Int? {
        val first = pendingUtf8Bytes[0]
        val second = pendingUtf8Bytes[1]
        return when (expectedUtf8Size) {
            2 -> ((first and 0x1F) shl 6) or (second and 0x3F)
            3 -> {
                if ((first == 0xE0 && second < 0xA0) ||
                    (first == 0xED && second >= 0xA0)
                ) {
                    null
                } else {
                    ((first and 0x0F) shl 12) or
                        ((second and 0x3F) shl 6) or
                        (pendingUtf8Bytes[2] and 0x3F)
                }
            }
            4 -> {
                if ((first == 0xF0 && second < 0x90) ||
                    (first == 0xF4 && second > 0x8F)
                ) {
                    null
                } else {
                    ((first and 0x07) shl 18) or
                        ((second and 0x3F) shl 12) or
                        ((pendingUtf8Bytes[2] and 0x3F) shl 6) or
                        (pendingUtf8Bytes[3] and 0x3F)
                }
            }
            else -> null
        }
    }

    private fun flushPendingUtf8(output: StringBuilder) {
        if (pendingUtf8Size == 0) return
        output.append(pendingUtf8Chars)
        clearPendingUtf8()
    }

    private fun clearPendingUtf8() {
        pendingUtf8Chars.setLength(0)
        pendingUtf8Size = 0
        expectedUtf8Size = 0
    }

    private fun gpt2Byte(char: Char): Int? {
        val codePoint = char.code
        if (codePoint >= GPT2_CHAR_TO_BYTE.size) return null
        return GPT2_CHAR_TO_BYTE[codePoint].takeIf { it >= 0 }
    }

    private fun utf8SequenceSize(firstByte: Int): Int = when (firstByte) {
        in 0x00..0x7F -> 1
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0
    }

    private companion object {
        val UTF8_CONTINUATION_RANGE = 0x80..0xBF

        val GPT2_CHAR_TO_BYTE = IntArray(0x144) { -1 }.apply {
            for (byte in 0x21..0x7E) this[byte] = byte
            for (byte in 0xA1..0xAC) this[byte] = byte
            for (byte in 0xAE..0xFF) this[byte] = byte

            var encodedCodePoint = 0x100
            for (byte in 0x00..0xFF) {
                val isDirect = byte in 0x21..0x7E || byte in 0xA1..0xAC || byte in 0xAE..0xFF
                if (!isDirect) {
                    this[encodedCodePoint] = byte
                    encodedCodePoint += 1
                }
            }
        }
    }
}

internal fun genieXVlmMessagesFromJson(messagesJson: String): Array<VlmChatMessage> {
    val array = runCatching { JSONArray(messagesJson) }.getOrNull() ?: return emptyArray()
    return Array(array.length()) { index ->
        val item = array.optJSONObject(index) ?: JSONObject()
        val contents = when (val raw = item.opt("content")) {
            is JSONArray -> genieXVlmContents(raw)
            is JSONObject -> listOf(VlmContent("text", raw.toString()))
            else -> listOf(VlmContent("text", item.optString("content")))
        }.ifEmpty { listOf(VlmContent("text", "")) }
            .deduplicateVisionContents()
        VlmChatMessage(
            role = item.optString("role", "user"),
            contents = contents
        )
    }
}

private fun genieXVlmContents(parts: JSONArray): List<VlmContent> = buildList {
    for (index in 0 until parts.length()) {
        val part = parts.optJSONObject(index) ?: continue
        when (part.optString("type").lowercase()) {
            "text", "input_text" -> add(VlmContent("text", part.optString("text")))
            "image", "image_url", "input_image" -> {
                // Accept all image-part spellings used by OpenAI-compatible
                // clients.  In particular, Responses-style `input_image`
                // puts its source under `input_image`, while some local
                // clients use a scalar `image` or a `path`/`uri` field.  The
                // old parser only inspected image_url/image, silently
                // dropping input_image and then invoking the VLM with text
                // only (or with an empty image path).
                val path = genieXImageSource(part)
                if (path.isNotBlank()) add(VlmContent("image", path))
            }
        }
    }
}

private fun genieXImageSource(part: JSONObject): String {
    val value = sequenceOf("image_url", "input_image", "image", "data", "url", "path", "uri")
        .mapNotNull { key -> part.opt(key) }
        .firstOrNull()
    val raw = when (value) {
        is JSONObject -> sequenceOf("url", "data", "path", "uri")
            .mapNotNull { key -> value.optString(key).takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: ""
        is String -> value
        else -> ""
    }.trim()
    if (raw.isBlank()) return ""
    if (!raw.startsWith("file:", ignoreCase = true)) return raw
    // Keep the URI's decoded path instead of asking java.io.File to canonicalize
    // it.  The latter silently rewrites a POSIX path such as file:/tmp/a.jpg to
    // E:\\tmp\\a.jpg when the parser is exercised on Windows (and in host
    // tests), which breaks duplicate detection and makes the native boundary
    // receive a path different from the one supplied by the caller.  Android's
    // file URIs already expose the correct absolute path through URI.path.
    return runCatching { URI(raw).path?.takeIf(String::isNotBlank) }
        .getOrNull()
        ?: raw.replaceFirst(Regex("^file:(//)?"), "")
}

internal fun genieXCurrentTurnImagePaths(messages: Array<VlmChatMessage>): Array<String> {
    val currentUserMessage = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }
        ?: return emptyArray()
    return currentUserMessage.contents
        .asSequence()
        .filter { it.type.equals("image", ignoreCase = true) }
        .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .toList()
        .toTypedArray()
}

internal data class GenieXLlmMessage(
    val role: String,
    val content: String
)

internal fun genieXLlmMessagesFromJson(messagesJson: String): List<GenieXLlmMessage> {
    val array = runCatching { JSONArray(messagesJson) }.getOrNull() ?: return emptyList()
    return List(array.length()) { index ->
        val item = array.optJSONObject(index) ?: JSONObject()
        GenieXLlmMessage(
            role = item.optString("role", "user"),
            content = when (val raw = item.opt("content")) {
                is JSONArray -> raw.genieXTextContent()
                is JSONObject -> raw.toString()
                else -> item.optString("content")
            }
        )
    }
}

internal fun genieXNativeLlmMessages(messages: List<GenieXLlmMessage>): Array<ChatMessage> =
    messages.map { message ->
        ChatMessage(
            role = message.role,
            content = message.content
        )
    }.toTypedArray()

internal fun genieXLlmBoundaryIsCanonical(
    canonical: List<GenieXLlmMessage>,
    sdkMessages: Array<ChatMessage>
): Boolean = canonical.size == sdkMessages.size && canonical.indices.all { index ->
    canonical[index].role == sdkMessages[index].role &&
        canonical[index].content == sdkMessages[index].content
}

internal fun requireNonBlankGenieXTemplate(value: String): String =
    value.takeIf(String::isNotBlank)
        ?: error("GenieX LLM returned an empty chat template.")

private fun JSONArray.genieXTextContent(): String = buildString {
    for (index in 0 until length()) {
        val part = optJSONObject(index) ?: continue
        if (part.optString("type") == "text") {
            if (isNotEmpty()) append('\n')
            append(part.optString("text"))
        }
    }
}
