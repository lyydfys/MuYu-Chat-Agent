package com.muyuchat.mca

/** Decodes common text exports and removes their byte-order mark before JSON/keyword processing. */
internal fun decodeImportedText(bytes: ByteArray): String = when {
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
        bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
        bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
        bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
    else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
}
