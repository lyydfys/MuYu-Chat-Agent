package com.muyuchat.mca

import java.util.Base64

/** PNG chunk parsing for character-card metadata (T2B22-T3 split). */

internal fun parseMetadataPayload(payload: String, source: CharacterCardSource): CharacterCardParseResult {
        if (payload.length > CharacterCardCodec.MAX_METADATA_TEXT_BYTES) {
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
            val decoded = runCatching {
                // Character-card exporters in the wild use both RFC 4648 and URL-safe
                // alphabets.  Accept either spelling while retaining the same bounded decode.
                runCatching { Base64.getDecoder().decode(compactBase64) }
                    .getOrElse { Base64.getUrlDecoder().decode(compactBase64) }
            }.getOrElse { error ->
                return failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, "Character-card metadata base64 is malformed.", error)
            }
            if (decoded.size > CharacterCardCodec.MAX_JSON_BYTES) {
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

internal fun parseTextChunk(bytes: ByteArray, dataOffset: Int, length: Int): PngTextEntry? {
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
        if (textLength > CharacterCardCodec.MAX_METADATA_TEXT_BYTES) throw CardMetadataException("Character-card text metadata is too large.")
        return PngTextEntry(keyword, readLatin1(bytes, textOffset, textLength))
    }

internal fun parseZTextChunk(bytes: ByteArray, dataOffset: Int, length: Int): PngTextEntry? {
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
        if (compressedLength <= 0 || compressedLength > CharacterCardCodec.MAX_METADATA_TEXT_BYTES) {
            throw CardMetadataException("zTXt metadata is empty or too large.")
        }
        val inflated = inflateBounded(bytes, compressedOffset, compressedLength)
            ?: throw CardMetadataException("zTXt metadata is not a valid bounded zlib stream.")
        return PngTextEntry(keyword, readLatin1(inflated, 0, inflated.size))
    }

internal fun parseITextChunk(bytes: ByteArray, dataOffset: Int, length: Int): PngTextEntry? {
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
        if (textLength > CharacterCardCodec.MAX_METADATA_TEXT_BYTES) throw CardMetadataException("iTXt metadata is too large.")
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

internal fun compactBase64(value: String): String? {
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

internal fun looksLikeJsonObject(value: String): Boolean {
        var index = 0
        while (index < value.length && value[index].isWhitespace()) index++
        if (index < value.length && value[index] == '\uFEFF') index++
        while (index < value.length && value[index].isWhitespace()) index++
        return index < value.length && value[index] == '{'
    }

internal data class PngTextEntry(val keyword: String, val text: String)

internal class CardMetadataException(message: String) : IllegalArgumentException(message)
