package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * The card format is deliberately a data model.  Nothing in a card is interpreted as code:
 * extensions, scripts, regular expressions, and macros remain opaque JSON values.
 */
enum class CharacterCardFormat {
    CC_V2,
    CC_V3,
    LEGACY_JSON
}

enum class CharacterCardSource {
    JSON,
    PNG_CHARA,
    PNG_CCV3
}

enum class CharacterCardParseErrorCode {
    EMPTY_INPUT,
    INPUT_TOO_LARGE,
    INVALID_UTF8,
    INVALID_JSON,
    UNSUPPORTED_CARD,
    MISSING_CARD_DATA,
    INVALID_PNG,
    PNG_TOO_LARGE,
    MISSING_CARD_METADATA,
    INVALID_CARD_METADATA,
    CARD_METADATA_TOO_LARGE,
    IO_ERROR
}

data class CharacterCardParseError(
    val code: CharacterCardParseErrorCode,
    val message: String,
    val cause: Throwable? = null
)

sealed interface CharacterCardParseResult {
    data class Success(
        val card: CharacterCard,
        val source: CharacterCardSource
    ) : CharacterCardParseResult

    data class Failure(
        val error: CharacterCardParseError
    ) : CharacterCardParseResult
}

/**
 * A parsed character card.  [rawJson] is retained byte-for-byte (apart from an optional UTF-8
 * BOM) so unknown top-level fields and extension payloads survive an import/export round trip.
 */
data class CharacterCard(
    val format: CharacterCardFormat,
    val specVersion: String,
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val exampleDialogue: String,
    val systemPrompt: String,
    val postHistoryInstructions: String,
    val creatorNotes: String,
    val creator: String,
    val characterVersion: String,
    val alternateGreetings: List<String>,
    val groupOnlyGreetings: List<String>,
    val tags: List<String>,
    val rawJson: String,
    /** A compact snapshot of the standard data.extensions object, when present. */
    val extensionsJson: String? = null,
    private val rawDataJson: String? = null
) {
    /** Returns a fresh object; callers cannot mutate the retained card representation. */
    fun toJson(): JSONObject = JSONObject(rawJson)

    /** Returns the original JSON, preserving unknown keys and extension values. */
    fun toJsonString(): String = rawJson

    /** Returns a fresh copy of data.extensions when that value is an object. */
    fun extensionsOrNull(): JSONObject? = extensionsJson?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * Reuses the existing AssistantStore compatibility mapping.  This keeps card import behavior
     * (including legacy aliases and generation-field isolation) in one place.
     */
    fun toAssistantRecord(defaults: AssistantRecord = AssistantRecord.default()): AssistantRecord =
        AssistantRecord.fromCharacterCard(this, defaults)

    /** The nested data object, or the root object for a legacy flat card. */
    fun dataJsonString(): String = rawDataJson ?: rawJson
}

/**
 * Bounded, clean-room parser for JSON character cards and PNG character-card metadata.
 *
 * PNG metadata is read from tEXt, zTXt, and iTXt chunks.  A ccv3 entry wins over a chara entry
 * regardless of chunk order.  A malformed explicit ccv3 entry is reported instead of silently
 * falling back to an older chara entry.
 */
object CharacterCardCodec {
    const val MAX_JSON_BYTES: Int = 1 * 1024 * 1024
    const val MAX_PNG_BYTES: Int = 16 * 1024 * 1024
    const val MAX_METADATA_TEXT_BYTES: Int = 2 * 1024 * 1024
    const val MAX_JSON_NESTING: Int = 64
    const val MAX_PNG_CHUNKS: Int = 16_384

    private const val PNG_TYPE_IHDR = "IHDR"
    private const val PNG_TYPE_IEND = "IEND"
    private const val PNG_TYPE_TEXT = "tEXt"
    private const val PNG_TYPE_ZTEXT = "zTXt"
    private const val PNG_TYPE_ITXT = "iTXt"

    /** Parses either a UTF-8 JSON card or a PNG containing card metadata. */
    fun parse(bytes: ByteArray): CharacterCardParseResult {
        if (bytes.isEmpty()) return failure(CharacterCardParseErrorCode.EMPTY_INPUT, "Card input is empty.")
        return if (bytes.startsWith(PNG_SIGNATURE) || bytes.looksLikePngPrefix()) {
            parsePng(bytes)
        } else {
            parseJsonBytes(bytes)
        }
    }

    /** Parses a bounded stream without closing the caller-owned stream. */
    fun parse(input: InputStream): CharacterCardParseResult {
        val bytes = runCatching { readBounded(input, MAX_PNG_BYTES) }.getOrElse { error ->
            return failure(CharacterCardParseErrorCode.IO_ERROR, "Could not read card input.", error)
        } ?: return failure(CharacterCardParseErrorCode.INPUT_TOO_LARGE, "Card input exceeds the bounded size limit.")
        return parse(bytes)
    }

    fun parseJson(rawJson: String): CharacterCardParseResult =
        parseJsonInternal(rawJson, CharacterCardSource.JSON)

    fun parseJson(bytes: ByteArray): CharacterCardParseResult = parseJsonBytes(bytes)

    fun parsePng(bytes: ByteArray): CharacterCardParseResult {
        if (bytes.size > MAX_PNG_BYTES) {
            return failure(CharacterCardParseErrorCode.PNG_TOO_LARGE, "PNG exceeds the bounded size limit.")
        }
        if (!bytes.startsWith(PNG_SIGNATURE)) {
            return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG signature is missing or invalid.")
        }

        var offset = PNG_SIGNATURE.size.toLong()
        var chunkCount = 0
        var sawIhdr = false
        var sawIend = false
        var charaPayload: String? = null
        var ccv3Payload: String? = null

        while (offset < bytes.size.toLong()) {
            if (chunkCount >= MAX_PNG_CHUNKS) {
                return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG contains too many chunks.")
            }
            val remaining = bytes.size.toLong() - offset
            if (remaining < PNG_CHUNK_OVERHEAD) {
                return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG ends inside a chunk record.")
            }

            val chunkOffset = offset.toInt()
            val length = readU32(bytes, chunkOffset)
            if (length > MAX_PNG_BYTES.toLong()) {
                return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG declares an oversized chunk.")
            }
            val nextOffset = offset + PNG_CHUNK_OVERHEAD + length
            if (nextOffset < offset || nextOffset > bytes.size.toLong()) {
                return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG chunk exceeds the input bounds.")
            }

            val typeOffset = chunkOffset + 4
            val dataOffset = chunkOffset + 8
            val dataLength = length.toInt()
            val crcOffset = dataOffset + dataLength
            if (!validPngChunkType(bytes, typeOffset)) {
                return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG contains an invalid chunk type.")
            }
            if (!hasValidCrc(bytes, typeOffset, dataLength, crcOffset)) {
                return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG chunk CRC does not match its contents.")
            }
            val type = readAscii(bytes, typeOffset, 4)

            if (!sawIhdr && type != PNG_TYPE_IHDR) {
                return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG must begin with IHDR.")
            }
            if (sawIend) {
                return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG contains data after IEND.")
            }

            when (type) {
                PNG_TYPE_IHDR -> {
                    if (sawIhdr || chunkCount != 0 || dataLength != 13) {
                        return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG IHDR is missing, duplicated, or malformed.")
                    }
                    val width = readU32(bytes, dataOffset)
                    val height = readU32(bytes, dataOffset + 4)
                    val bitDepth = bytes[dataOffset + 8].toInt() and 0xff
                    val colorType = bytes[dataOffset + 9].toInt() and 0xff
                    val allowedDepths = when (colorType) {
                        0 -> setOf(1, 2, 4, 8, 16)
                        2, 4, 6 -> setOf(8, 16)
                        3 -> setOf(1, 2, 4, 8)
                        else -> emptySet()
                    }
                    if (width !in 1..MAX_PNG_DIMENSION || height !in 1..MAX_PNG_DIMENSION ||
                        width * height > MAX_PNG_PIXELS || bitDepth !in allowedDepths ||
                        bytes[dataOffset + 10].toInt() != 0 ||
                        bytes[dataOffset + 11].toInt() != 0 ||
                        (bytes[dataOffset + 12].toInt() and 0xff) !in 0..1
                    ) {
                        return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG IHDR dimensions or pixel format are invalid.")
                    }
                    sawIhdr = true
                }
                PNG_TYPE_IEND -> {
                    if (dataLength != 0 || nextOffset != bytes.size.toLong()) {
                        return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG IEND is malformed or not terminal.")
                    }
                    sawIend = true
                }
                PNG_TYPE_TEXT -> {
                    val entry = try {
                        parseTextChunk(bytes, dataOffset, dataLength)
                    } catch (error: CardMetadataException) {
                        return failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, error.message ?: "tEXt metadata is malformed.", error)
                    }
                    if (entry != null) {
                        when (entry.keyword) {
                            KEY_CHARA -> {
                                if (charaPayload != null) return duplicateMetadataFailure(KEY_CHARA)
                                charaPayload = entry.text
                            }
                            KEY_CCV3 -> {
                                if (ccv3Payload != null) return duplicateMetadataFailure(KEY_CCV3)
                                ccv3Payload = entry.text
                            }
                        }
                    }
                }
                PNG_TYPE_ZTEXT -> {
                    val entry = try {
                        parseZTextChunk(bytes, dataOffset, dataLength)
                    } catch (error: CardMetadataException) {
                        return failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, error.message ?: "zTXt metadata is malformed.", error)
                    }
                    if (entry != null) {
                        when (entry.keyword) {
                            KEY_CHARA -> {
                                if (charaPayload != null) return duplicateMetadataFailure(KEY_CHARA)
                                charaPayload = entry.text
                            }
                            KEY_CCV3 -> {
                                if (ccv3Payload != null) return duplicateMetadataFailure(KEY_CCV3)
                                ccv3Payload = entry.text
                            }
                        }
                    }
                }
                PNG_TYPE_ITXT -> {
                    val entry = try {
                        parseITextChunk(bytes, dataOffset, dataLength)
                    } catch (error: CardMetadataException) {
                        return failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, error.message ?: "iTXt metadata is malformed.", error)
                    }
                    if (entry != null) {
                        when (entry.keyword) {
                            KEY_CHARA -> {
                                if (charaPayload != null) return duplicateMetadataFailure(KEY_CHARA)
                                charaPayload = entry.text
                            }
                            KEY_CCV3 -> {
                                if (ccv3Payload != null) return duplicateMetadataFailure(KEY_CCV3)
                                ccv3Payload = entry.text
                            }
                        }
                    }
                }
            }

            offset = nextOffset
            chunkCount++
        }

        if (!sawIhdr || !sawIend) {
            return failure(CharacterCardParseErrorCode.INVALID_PNG, "PNG is missing IHDR or terminal IEND.")
        }

        // The explicit V3 payload is authoritative, even when it appears after chara.
        if (ccv3Payload != null) {
            return parseMetadataPayload(ccv3Payload, CharacterCardSource.PNG_CCV3)
        }
        if (charaPayload != null) {
            return parseMetadataPayload(charaPayload, CharacterCardSource.PNG_CHARA)
        }
        return failure(CharacterCardParseErrorCode.MISSING_CARD_METADATA, "PNG has no chara or ccv3 character-card metadata.")
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(8192, limit))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > limit - count) return null
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    internal fun validateJsonBounds(value: String): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in value) {
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                when (character) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                    else -> if (character.code < 0x20) return "Card JSON contains a control character."
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > MAX_JSON_NESTING) return "Card JSON nesting exceeds the safety limit."
                    }
                    '}', ']' -> {
                        depth--
                        if (depth < 0) return "Card JSON has an unmatched closing delimiter."
                    }
                }
            }
        }
        return when {
            inString || escaped -> "Card JSON contains an unterminated string."
            depth != 0 -> "Card JSON has unbalanced delimiters."
            else -> null
        }
    }

    private const val PNG_CHUNK_OVERHEAD = 12L
    private const val MAX_PNG_DIMENSION = 16_384L
    private const val MAX_PNG_PIXELS = 64_000_000L
}

internal const val KEY_CHARA = "chara"
internal const val KEY_CCV3 = "ccv3"
