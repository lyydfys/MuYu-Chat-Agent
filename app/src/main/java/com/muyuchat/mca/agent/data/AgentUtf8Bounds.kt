package com.muyuchat.mca.agent.data

/**
 * Strict UTF-8 prefix decoding for `workspace.read_text` (architecture 9.1).
 *
 * A window can end in the middle of a multi-byte character. [decodeCompletePrefix] returns only the
 * complete characters and how many bytes they consumed; malformed input is distinguished from an
 * incomplete tail so a caller can return `TOOL_INPUT_INVALID` instead of silently replacing bytes
 * with U+FFFD.
 */
object AgentUtf8Bounds {
    data class Decoded(val text: String, val consumedBytes: Int)

    /**
     * @param atEof true when the window reaches the end of the file: a trailing incomplete sequence
     * then proves the file is not valid UTF-8.
     * @throws AgentStorageException with `TOOL_INPUT_INVALID` for malformed UTF-8.
     */
    fun decodeCompletePrefix(bytes: ByteArray, atEof: Boolean): Decoded {
        var index = 0
        while (index < bytes.size) {
            val lead = bytes[index].toInt() and 0xff
            val needed = when {
                lead <= 0x7f -> 0
                lead in 0xc2..0xdf -> 1
                lead == 0xe0 -> 2
                lead in 0xe1..0xec -> 2
                lead == 0xed -> 2
                lead in 0xee..0xef -> 2
                lead == 0xf0 -> 3
                lead in 0xf1..0xf3 -> 3
                lead == 0xf4 -> 3
                else -> throw invalidUtf8()
            }
            if (needed == 0) {
                index += 1
                continue
            }
            if (index + needed >= bytes.size) {
                if (atEof) throw invalidUtf8()
                return Decoded(String(bytes, 0, index, Charsets.UTF_8), index)
            }
            for (offset in 1..needed) {
                val continuation = bytes[index + offset].toInt() and 0xff
                if (continuation !in 0x80..0xbf) throw invalidUtf8()
            }
            when (lead) {
                0xe0 -> if ((bytes[index + 1].toInt() and 0xff) < 0xa0) throw invalidUtf8()
                0xed -> if ((bytes[index + 1].toInt() and 0xff) > 0x9f) throw invalidUtf8()
                0xf0 -> if ((bytes[index + 1].toInt() and 0xff) < 0x90) throw invalidUtf8()
                0xf4 -> if ((bytes[index + 1].toInt() and 0xff) > 0x8f) throw invalidUtf8()
            }
            index += needed + 1
        }
        return Decoded(String(bytes, 0, index, Charsets.UTF_8), index)
    }

    /** True when [offset] is a character boundary in valid UTF-8 (not a continuation byte). */
    fun isCharBoundary(byteAtOffset: Int): Boolean = (byteAtOffset and 0xc0) != 0x80

    private fun invalidUtf8(): AgentStorageException = AgentStorageException(
        AgentDataErrorCodes.INPUT_INVALID,
        "file content is not valid UTF-8"
    )
}
