package com.muyuchat.feature.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal object ImagePromptTagCsv {
    const val MAX_UTF8_LINE_BYTES: Int = 16_384
    const val MAX_TRANSLATION_COLUMNS: Int = 8

    fun parseTagLine(line: String): ImagePromptTagRecord? {
        if (line.length > MAX_UTF8_LINE_BYTES || hasUnpairedSurrogate(line)) return null
        return parseTagUtf8Line(line.toByteArray(Charsets.UTF_8))
    }

    fun parseTagUtf8Line(bytes: ByteArray): ImagePromptTagRecord? {
        val line = decodeUtf8Line(bytes) ?: return null
        val cells = parseBoundedCsvCells(line, expectedColumns = 3..4) ?: return null
        return try {
            val tag = cells[0].trim().removePrefix("\uFEFF")
            if (!isSafeCanonicalImagePromptTag(tag)) return null
            val category = cells[1].trim().toIntOrNull() ?: return null
            val postCount = cells[2].trim().toLongOrNull()?.takeIf { it >= 0L } ?: return null
            val aliasCells = cells.getOrNull(3)
                ?.split(',', limit = MAX_TAG_ALIASES + 1)
                .orEmpty()
            if (aliasCells.size > MAX_TAG_ALIASES) return null
            val aliasesByKey = LinkedHashMap<String, String>(aliasCells.size)
            for (rawAlias in aliasCells) {
                val alias = rawAlias.trim()
                if (alias.isEmpty()) continue
                if (!isSafeTagValue(alias)) return null
                val key = normalizeImagePromptTag(alias)
                if (key.isEmpty()) return null
                aliasesByKey.putIfAbsent(key, alias)
            }
            ImagePromptTagRecord(
                tag = tag,
                category = category,
                postCount = postCount,
                aliases = aliasesByKey.values.toList()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseTranslationLine(line: String): ImagePromptTagTranslationRow? {
        if (line.length > MAX_UTF8_LINE_BYTES || hasUnpairedSurrogate(line)) return null
        return parseTranslationUtf8Line(line.toByteArray(Charsets.UTF_8))
    }

    fun parseTranslationUtf8Line(bytes: ByteArray): ImagePromptTagTranslationRow? {
        val line = decodeUtf8Line(bytes) ?: return null
        val cells = parseBoundedCsvCells(
            line,
            expectedColumns = 2..MAX_TRANSLATION_COLUMNS
        ) ?: return null
        return try {
            val translation = cells.asSequence()
                .drop(1)
                .map(String::trim)
                .firstOrNull { value ->
                    value.isNotEmpty() && value.toDoubleOrNull() == null
                } ?: return null
            ImagePromptTagTranslationRow(
                tag = cells[0].trim().removePrefix("\uFEFF"),
                translation = translation
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeUtf8Line(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_UTF8_LINE_BYTES) return null
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return null
        }
        return decoded.takeIf {
            decoded.none { it == '\r' || it == '\n' || it == '\u0000' }
        }
    }

    private fun parseBoundedCsvCells(line: String, expectedColumns: IntRange): List<String>? {
        if (line.isEmpty()) return null
        val cells = ArrayList<String>(expectedColumns.last)
        var cursor = 0
        while (true) {
            if (cells.size >= expectedColumns.last + 1) return null
            val field = StringBuilder()
            if (cursor < line.length && line[cursor] == '"') {
                cursor++
                var closed = false
                while (cursor < line.length) {
                    val current = line[cursor]
                    if (current != '"') {
                        field.append(current)
                        cursor++
                    } else if (cursor + 1 < line.length && line[cursor + 1] == '"') {
                        field.append('"')
                        cursor += 2
                    } else {
                        closed = true
                        cursor++
                        break
                    }
                }
                if (!closed) return null
                while (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) cursor++
                if (cursor < line.length && line[cursor] != ',') return null
            } else {
                while (cursor < line.length && line[cursor] != ',') {
                    if (line[cursor] == '"') return null
                    field.append(line[cursor])
                    cursor++
                }
            }
            cells += field.toString()
            if (cursor >= line.length) break
            cursor++
            if (cursor == line.length) {
                cells += ""
                break
            }
        }
        return cells.takeIf { it.size in expectedColumns }
    }
}

internal fun normalizeImagePromptTag(value: String): String {
    if (value.isEmpty()) return ""
    val lower = value.lowercase(Locale.ROOT)
    val normalized = StringBuilder(lower.length)
    var separatorPending = false
    for (char in lower) {
        if (char == '_' || char == '-' || char.isWhitespace()) {
            separatorPending = normalized.isNotEmpty()
        } else {
            if (separatorPending) normalized.append('_')
            normalized.append(char)
            separatorPending = false
        }
    }
    return normalized.toString()
}

