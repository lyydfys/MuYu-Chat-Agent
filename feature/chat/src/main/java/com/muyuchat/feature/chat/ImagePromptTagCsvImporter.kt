package com.muyuchat.feature.chat

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

internal fun readImagePromptTagCsvRows(
    file: File,
    maximumRows: Int,
    checkCancelled: () -> Unit,
    consumeRow: (ByteArray) -> Unit
): Int {
    val current = ByteArrayOutputStream()
    var physicalLineCount = 0
    var validRowCount = 0
    fun finishLine() {
        physicalLineCount++
        if (physicalLineCount > maximumRows) {
            throw ImagePromptTagStoreValidationException(ImagePromptTagStoreIssue.TOO_MANY_LINES)
        }
        val raw = current.toByteArray()
        current.reset()
        val withoutCarriageReturn = if (raw.lastOrNull() == '\r'.code.toByte()) {
            raw.copyOf(raw.size - 1)
        } else {
            raw
        }
        if (withoutCarriageReturn.all { it == ' '.code.toByte() || it == '\t'.code.toByte() }) {
            return
        }
        consumeRow(withoutCarriageReturn)
        validRowCount++
    }

    FileInputStream(file).buffered(COPY_BUFFER_BYTES).use { input ->
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val byte = buffer[index]
                if (byte == '\n'.code.toByte()) {
                    finishLine()
                } else {
                    if (current.size() >= ImagePromptTagCsv.MAX_UTF8_LINE_BYTES) {
                        throw ImagePromptTagStoreValidationException(
                            ImagePromptTagStoreIssue.MALFORMED_CSV
                        )
                    }
                    current.write(byte.toInt())
                }
            }
        }
    }
    if (current.size() > 0) finishLine()
    return validRowCount
}
