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

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )
    private const val PNG_TYPE_IHDR = "IHDR"
    private const val PNG_TYPE_IEND = "IEND"
    private const val PNG_TYPE_TEXT = "tEXt"
    private const val PNG_TYPE_ZTEXT = "zTXt"
    private const val PNG_TYPE_ITXT = "iTXt"
    private const val KEY_CHARA = "chara"
    private const val KEY_CCV3 = "ccv3"
    private const val JSON_DATA = "data"

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

    private fun parseJsonBytes(bytes: ByteArray): CharacterCardParseResult {
        if (bytes.size > MAX_JSON_BYTES) {
            return failure(CharacterCardParseErrorCode.INPUT_TOO_LARGE, "JSON card exceeds the bounded size limit.")
        }
        val raw = decodeUtf8(bytes) ?: return failure(CharacterCardParseErrorCode.INVALID_UTF8, "JSON card is not valid UTF-8.")
        return parseJsonInternal(raw, CharacterCardSource.JSON)
    }

    private fun parseJsonInternal(rawInput: String, source: CharacterCardSource): CharacterCardParseResult {
        if (rawInput.isEmpty()) return failure(CharacterCardParseErrorCode.EMPTY_INPUT, "Card JSON is empty.")
        if (rawInput.length > MAX_JSON_BYTES * 2) {
            return failure(CharacterCardParseErrorCode.INPUT_TOO_LARGE, "JSON card exceeds the bounded size limit.")
        }
        if (rawInput.utf8ByteCountExceeds(MAX_JSON_BYTES)) {
            return failure(CharacterCardParseErrorCode.INPUT_TOO_LARGE, "JSON card exceeds the bounded size limit.")
        }

        val rawJson = rawInput.removePrefix("\uFEFF")
        val shapeError = validateJsonBounds(rawJson)
        if (shapeError != null) return failure(CharacterCardParseErrorCode.INVALID_JSON, shapeError)

        val parsed = runCatching { JSONObject(rawJson) }.getOrElse { error ->
            return failure(CharacterCardParseErrorCode.INVALID_JSON, "Card JSON is malformed.", error)
        }
        return buildCard(parsed, rawJson, source)
    }

    private fun buildCard(
        root: JSONObject,
        rawJson: String,
        source: CharacterCardSource
    ): CharacterCardParseResult {
        val spec = root.stringValue("spec")
        val format = when (spec) {
            "chara_card_v2" -> CharacterCardFormat.CC_V2
            "chara_card_v3" -> CharacterCardFormat.CC_V3
            else -> CharacterCardFormat.LEGACY_JSON
        }
        val data = if (format == CharacterCardFormat.LEGACY_JSON) {
            root.optJSONObject(JSON_DATA) ?: root
        } else {
            root.optJSONObject(JSON_DATA) ?: return failure(
                CharacterCardParseErrorCode.MISSING_CARD_DATA,
                "${format.name} card is missing its data object."
            )
        }

        if (format == CharacterCardFormat.LEGACY_JSON && !looksLikeLegacyCard(root, data)) {
            return failure(CharacterCardParseErrorCode.UNSUPPORTED_CARD, "JSON does not contain a supported character-card shape.")
        }

        val extensionsJson = runCatching { data.optJSONObject("extensions")?.toString() }.getOrNull()
        return CharacterCardParseResult.Success(
            card = CharacterCard(
                format = format,
                specVersion = root.stringValue("spec_version"),
                name = data.firstStringValue("name", "char_name", "title"),
                description = data.firstStringValue("description", "desc", "char_persona"),
                personality = data.firstStringValue("personality"),
                scenario = data.firstStringValue("scenario", "world_scenario"),
                firstMessage = data.firstStringValue("first_mes", "firstMessage", "greeting", "char_greeting"),
                exampleDialogue = data.firstStringValue("mes_example", "example_dialogue"),
                systemPrompt = data.firstStringValue("system_prompt", "systemPrompt", "prompt", "instructions"),
                postHistoryInstructions = data.firstStringValue("post_history_instructions", "postHistoryInstructions"),
                creatorNotes = data.firstStringValue("creator_notes", "creatorNotes"),
                creator = data.firstStringValue("creator"),
                characterVersion = data.firstStringValue("character_version", "characterVersion"),
                alternateGreetings = data.stringArray("alternate_greetings", "alternateGreetings"),
                groupOnlyGreetings = data.stringArray("group_only_greetings", "groupOnlyGreetings"),
                tags = data.stringArray("tags"),
                rawJson = rawJson,
                extensionsJson = extensionsJson,
                rawDataJson = runCatching { data.toString() }.getOrNull()
            ),
            source = source
        )
    }

    private fun parseMetadataPayload(payload: String, source: CharacterCardSource): CharacterCardParseResult {
        if (payload.length > MAX_METADATA_TEXT_BYTES) {
            return failure(CharacterCardParseErrorCode.CARD_METADATA_TOO_LARGE, "Character-card metadata is too large.")
        }
        val candidate = payload.trim()
        if (candidate.isEmpty()) {
            return failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, "Character-card metadata is empty.")
        }

        // Some producers write JSON directly in iTXt; the usual chara/ccv3 representation is
        // base64-encoded UTF-8 JSON.  Both paths remain data-only.
        val jsonText = if (looksLikeJsonObject(payload)) {
            payload
        } else {
            val compactBase64 = compactBase64(candidate)
                ?: return failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, "Character-card metadata is not valid base64 JSON.")
            val decoded = runCatching { Base64.getDecoder().decode(compactBase64) }.getOrElse { error ->
                return failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, "Character-card metadata base64 is malformed.", error)
            }
            if (decoded.size > MAX_JSON_BYTES) {
                return failure(CharacterCardParseErrorCode.CARD_METADATA_TOO_LARGE, "Decoded character-card metadata is too large.")
            }
            decodeUtf8(decoded) ?: return failure(
                CharacterCardParseErrorCode.INVALID_CARD_METADATA,
                "Character-card metadata is not valid UTF-8."
            )
        }
        return when (val result = parseJsonInternal(jsonText, source)) {
            is CharacterCardParseResult.Success -> result
            is CharacterCardParseResult.Failure -> CharacterCardParseResult.Failure(
                result.error.copy(
                    code = if (result.error.code == CharacterCardParseErrorCode.INPUT_TOO_LARGE) {
                        CharacterCardParseErrorCode.CARD_METADATA_TOO_LARGE
                    } else {
                        CharacterCardParseErrorCode.INVALID_CARD_METADATA
                    },
                    message = "Character-card metadata JSON is invalid: ${result.error.message}"
                )
            )
        }
    }

    private fun parseTextChunk(bytes: ByteArray, dataOffset: Int, length: Int): PngTextEntry? {
        val separator = findZero(bytes, dataOffset, length)
        if (separator < 0) {
            // Ignore malformed unrelated text chunks, but never try to interpret their bytes.
            return null
        }
        val keywordLength = separator - dataOffset
        if (keywordLength !in 1..79) return null
        val keyword = readLatin1(bytes, dataOffset, keywordLength)
        if (keyword != KEY_CHARA && keyword != KEY_CCV3) return null
        val textOffset = separator + 1
        val textLength = length - keywordLength - 1
        if (textLength > MAX_METADATA_TEXT_BYTES) throw CardMetadataException("Character-card text metadata is too large.")
        return PngTextEntry(keyword, readLatin1(bytes, textOffset, textLength))
    }

    private fun parseZTextChunk(bytes: ByteArray, dataOffset: Int, length: Int): PngTextEntry? {
        val separator = findZero(bytes, dataOffset, length)
        if (separator < 0) return null
        val keywordLength = separator - dataOffset
        if (keywordLength !in 1..79) return null
        val keyword = readLatin1(bytes, dataOffset, keywordLength)
        if (keyword != KEY_CHARA && keyword != KEY_CCV3) return null
        val methodOffset = separator + 1
        if (methodOffset >= dataOffset + length) throw CardMetadataException("zTXt metadata is truncated.")
        if ((bytes[methodOffset].toInt() and 0xff) != 0) throw CardMetadataException("zTXt uses an unsupported compression method.")
        val compressedOffset = methodOffset + 1
        val compressedLength = length - keywordLength - 2
        if (compressedLength <= 0 || compressedLength > MAX_METADATA_TEXT_BYTES) {
            throw CardMetadataException("zTXt metadata is empty or too large.")
        }
        val inflated = inflateBounded(bytes, compressedOffset, compressedLength)
            ?: throw CardMetadataException("zTXt metadata is not a valid bounded zlib stream.")
        return PngTextEntry(keyword, readLatin1(inflated, 0, inflated.size))
    }

    private fun parseITextChunk(bytes: ByteArray, dataOffset: Int, length: Int): PngTextEntry? {
        var cursor = dataOffset
        val end = dataOffset + length
        val keywordEnd = findZero(bytes, cursor, end - cursor)
        if (keywordEnd < 0) return null
        val keywordLength = keywordEnd - cursor
        if (keywordLength !in 1..79) return null
        val keyword = readLatin1(bytes, cursor, keywordLength)
        cursor = keywordEnd + 1
        if (keyword != KEY_CHARA && keyword != KEY_CCV3) return null
        if (cursor + 2 > end) throw CardMetadataException("iTXt metadata is truncated.")
        val compressionFlag = bytes[cursor].toInt() and 0xff
        val compressionMethod = bytes[cursor + 1].toInt() and 0xff
        if (compressionFlag !in 0..1 || compressionMethod != 0) {
            throw CardMetadataException("iTXt uses an unsupported compression mode.")
        }
        cursor += 2
        val languageEnd = findZero(bytes, cursor, end - cursor)
        if (languageEnd < 0) throw CardMetadataException("iTXt language tag is truncated.")
        cursor = languageEnd + 1
        val translatedEnd = findZero(bytes, cursor, end - cursor)
        if (translatedEnd < 0) throw CardMetadataException("iTXt translated keyword is truncated.")
        cursor = translatedEnd + 1
        val textLength = end - cursor
        if (textLength > MAX_METADATA_TEXT_BYTES) throw CardMetadataException("iTXt metadata is too large.")
        val text = if (compressionFlag == 1) {
            if (textLength <= 0) throw CardMetadataException("Compressed iTXt metadata is empty.")
            val inflated = inflateBounded(bytes, cursor, textLength)
                ?: throw CardMetadataException("iTXt metadata is not a valid bounded zlib stream.")
            decodeUtf8(inflated) ?: throw CardMetadataException("iTXt metadata is not valid UTF-8.")
        } else {
            decodeUtf8(bytes.copyOfRange(cursor, end))
                ?: throw CardMetadataException("iTXt metadata is not valid UTF-8.")
        }
        return PngTextEntry(keyword, text)
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

    private fun compactBase64(value: String): String? {
        val builder = StringBuilder(value.length)
        value.forEach { character ->
            when (character) {
                ' ', '\t', '\r', '\n', '\u000c' -> Unit
                else -> {
                    if (character.code > 0x7f) return null
                    builder.append(character)
                }
            }
        }
        return builder.takeIf { it.isNotEmpty() }?.toString()
    }

    private fun looksLikeJsonObject(value: String): Boolean {
        var index = 0
        while (index < value.length && value[index].isWhitespace()) index++
        if (index < value.length && value[index] == '\uFEFF') index++
        while (index < value.length && value[index].isWhitespace()) index++
        return index < value.length && value[index] == '{'
    }

    private fun validateJsonBounds(value: String): String? {
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

    private fun looksLikeLegacyCard(root: JSONObject, data: JSONObject): Boolean {
        if (root.stringValue("schema") == "mca.assistant.card") return true
        val keys = arrayOf(
            "name", "char_name", "title", "description", "desc", "char_persona", "personality",
            "scenario", "world_scenario", "first_mes", "firstMessage", "greeting", "char_greeting",
            "mes_example", "example_dialogue", "system_prompt",
            "systemPrompt", "prompt", "instructions", "creator", "creator_notes"
        )
        return keys.any { key -> data.has(key) && !data.isNull(key) }
    }

    private fun JSONObject.firstStringValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            val value = opt(key)
            (value as? String)?.takeIf { it.isNotBlank() }
        }.orEmpty()

    private fun JSONObject.stringValue(key: String): String = (opt(key) as? String).orEmpty()

    private fun JSONObject.stringArray(vararg keys: String): List<String> {
        val array = keys.firstNotNullOfOrNull { key -> optJSONArray(key) } ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val value = array.opt(index) as? String ?: continue
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun inflateBounded(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
        val inflater = Inflater()
        val output = ByteArrayOutputStream(minOf(8192, MAX_METADATA_TEXT_BYTES))
        val buffer = ByteArray(8192)
        return try {
            inflater.setInput(bytes, offset, length)
            while (true) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (_: DataFormatException) {
                    return null
                }
                if (count > 0) {
                    if (output.size() > MAX_METADATA_TEXT_BYTES - count) return null
                    output.write(buffer, 0, count)
                } else if (inflater.finished()) {
                    if (inflater.remaining != 0) return null
                    break
                } else {
                    if (inflater.needsDictionary() || inflater.needsInput()) return null
                    return null
                }
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun findZero(bytes: ByteArray, offset: Int, length: Int): Int {
        val end = offset + length
        for (index in offset until end) if (bytes[index].toInt() == 0) return index
        return -1
    }

    private fun readLatin1(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.ISO_8859_1)

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

    private fun validPngChunkType(bytes: ByteArray, offset: Int): Boolean {
        for (index in 0 until 4) {
            val value = bytes[offset + index].toInt() and 0xff
            if (value !in 'A'.code..'Z'.code && value !in 'a'.code..'z'.code) return false
        }
        // The reserved third bit must be zero according to the PNG specification.
        return (bytes[offset + 2].toInt() and 0x20) == 0
    }

    private fun hasValidCrc(bytes: ByteArray, typeOffset: Int, dataLength: Int, crcOffset: Int): Boolean {
        val crc = CRC32()
        crc.update(bytes, typeOffset, 4 + dataLength)
        return crc.value == readU32(bytes, crcOffset)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun ByteArray.looksLikePngPrefix(): Boolean =
        size >= 4 && this[0] == PNG_SIGNATURE[0] && this[1] == PNG_SIGNATURE[1] &&
            this[2] == PNG_SIGNATURE[2] && this[3] == PNG_SIGNATURE[3]

    private fun String.utf8ByteCountExceeds(limit: Int): Boolean {
        var count = 0L
        var index = 0
        while (index < length) {
            val character = this[index]
            val add = when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                character.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                    index++
                    4
                }
                character.isLowSurrogate() -> return true
                else -> 3
            }
            count += add
            if (count > limit.toLong()) return true
            index++
        }
        return false
    }

    private fun failure(
        code: CharacterCardParseErrorCode,
        message: String,
        cause: Throwable? = null
    ): CharacterCardParseResult.Failure =
        CharacterCardParseResult.Failure(CharacterCardParseError(code, message, cause))

    private fun duplicateMetadataFailure(keyword: String): CharacterCardParseResult.Failure =
        failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, "PNG contains duplicate $keyword metadata.")

    private data class PngTextEntry(val keyword: String, val text: String)

    private class CardMetadataException(message: String) : IllegalArgumentException(message)

    private const val PNG_CHUNK_OVERHEAD = 12L
}
