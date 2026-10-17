package com.muyuchat.mca

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

internal val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
)

/** Binary primitives for the character-card codec (T2B22-T3 split). */

internal fun findZero(bytes: ByteArray, offset: Int, length: Int): Int {
        val end = offset + length
        for (index in offset until end) if (bytes[index].toInt() == 0) return index
        return -1
    }

internal fun readLatin1(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.ISO_8859_1)

internal fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

internal fun validPngChunkType(bytes: ByteArray, offset: Int): Boolean {
        for (index in 0 until 4) {
            val value = bytes[offset + index].toInt() and 0xff
            if (value !in 'A'.code..'Z'.code && value !in 'a'.code..'z'.code) return false
        }
        // The reserved third bit must be zero according to the PNG specification.
        return (bytes[offset + 2].toInt() and 0x20) == 0
    }

internal fun hasValidCrc(bytes: ByteArray, typeOffset: Int, dataLength: Int, crcOffset: Int): Boolean {
        val crc = CRC32()
        crc.update(bytes, typeOffset, 4 + dataLength)
        return crc.value == readU32(bytes, crcOffset)
    }

internal fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

internal fun ByteArray.looksLikePngPrefix(): Boolean =
        size >= 4 && this[0] == PNG_SIGNATURE[0] && this[1] == PNG_SIGNATURE[1] &&
            this[2] == PNG_SIGNATURE[2] && this[3] == PNG_SIGNATURE[3]

internal fun String.utf8ByteCountExceeds(limit: Int): Boolean {
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

internal fun failure(
        code: CharacterCardParseErrorCode,
        message: String,
        cause: Throwable? = null
    ): CharacterCardParseResult.Failure =
        CharacterCardParseResult.Failure(CharacterCardParseError(code, message, cause))

internal fun duplicateMetadataFailure(keyword: String): CharacterCardParseResult.Failure =
        failure(CharacterCardParseErrorCode.INVALID_CARD_METADATA, "PNG contains duplicate $keyword metadata.")
