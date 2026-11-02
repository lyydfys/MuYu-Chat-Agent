package com.muyuchat.feature.video.workspace

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Upper bound of one ComfyUI API-format document read from the system picker. */
internal const val MAX_COMFY_API_JSON_BYTES: Int = 4 * 1024 * 1024

/**
 * Reads one ComfyUI API-format JSON document with an explicit 4 MiB byte ceiling and strict
 * UTF-8 validation. Android's default reader would silently replace malformed bytes; here an
 * invalid encoding is a reported import failure, never a half-parsed workflow. A leading UTF-8
 * BOM is stripped because some exporters write it and JSON parsers reject it as content.
 */
internal fun readComfyApiJson(
    input: InputStream,
    maxBytes: Int = MAX_COMFY_API_JSON_BYTES,
): String {
    require(maxBytes > 0) { "读取上限必须为正数" }
    val buffer = ByteArray(64 * 1024)
    val collected = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            throw IllegalArgumentException("导入文件超过 ${maxBytes / (1024 * 1024)} MiB 上限")
        }
        collected.write(buffer, 0, read)
    }
    if (total == 0) throw IllegalArgumentException("导入文件为空")
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(collected.toByteArray())).toString()
    } catch (invalid: CharacterCodingException) {
        throw IllegalArgumentException("导入文件不是有效的 UTF-8 文本", invalid)
    }
    return text.removePrefix("\uFEFF")
}
