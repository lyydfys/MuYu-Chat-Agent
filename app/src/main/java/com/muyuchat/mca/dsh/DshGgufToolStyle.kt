package com.muyuchat.mca.dsh

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Selects the tool-call syntax the loaded GGUF was trained on.
 *
 * The two installed examples disagree: Qwen2.5/Qwen3 templates render
 * `<tool_call>{"name":…,"arguments":{…}}</tool_call>`, while Qwen3.5/Coder templates render
 * `<tool_call><function=…><parameter=…>`. The template stored in the GGUF is authoritative, so it
 * is read from the bounded header prefix and cached by (path, length, mtime). A file whose header
 * cannot be read falls back to the file-name hint and then to the Qwen2.5/Qwen3 form; the format is
 * a prompt hint, never an admission check.
 */
internal object DshGgufToolStyle {
    private val cache = ConcurrentHashMap<String, DshToolEnvelopeStyle>()

    private const val MAX_SCAN_BYTES = 64L * 1024 * 1024
        private const val TEMPLATE_KEY = "tokenizer.chat_template"
    private const val TEMPLATE_WINDOW = 8192
    private const val CHUNK_BYTES = 64 * 1024

    fun of(path: String?): DshToolEnvelopeStyle {
        val file = path?.trim()?.takeIf { it.isNotEmpty() }?.let(::File) ?: return fallbackForName("")
        if (!file.isFile) return fallbackForName(file.name)
        val key = file.absolutePath + '\u0000' + file.length() + '\u0000' + file.lastModified()
        return cache.getOrPut(key) { detect(file) ?: fallbackForName(file.name) }
    }

    private fun detect(file: File): DshToolEnvelopeStyle? = runCatching {
        file.inputStream().buffered().use { input ->
            val overlapLength = TEMPLATE_KEY.length - 1
            val chunk = ByteArray(CHUNK_BYTES)
            var scanned = 0L
            var carry = ""
            while (scanned < MAX_SCAN_BYTES) {
                val read = input.read(chunk)
                if (read <= 0) return@use null
                // ISO-8859-1 keeps a 1:1 byte/char mapping, so ASCII markers survive decoding.
                val text = carry + String(chunk, 0, read, Charsets.ISO_8859_1)
                val at = text.indexOf(TEMPLATE_KEY)
                if (at >= 0) {
                    return@use analyse(text.substring(at + TEMPLATE_KEY.length) + readWindow(input))
                }
                carry = text.takeLast(overlapLength)
                scanned += read
            }
            null
        }
    }.getOrNull()

    /** The template starts right after the key's type and length fields; read enough of it. */
    private fun readWindow(input: java.io.InputStream): String {
        val bytes = ByteArray(TEMPLATE_WINDOW)
        var filled = 0
        while (filled < bytes.size) {
            val read = input.read(bytes, filled, bytes.size - filled)
            if (read <= 0) break
            filled += read
        }
        return String(bytes, 0, filled, Charsets.UTF_8)
    }

    private fun analyse(text: String): DshToolEnvelopeStyle? {
        val start = text.indexOf("<tool_call>")
        if (start < 0) return null
        val end = minOf(text.length, start + TEMPLATE_WINDOW)
        val window = text.substring(start, end)
        return when {
            window.contains("<function=") || window.contains("<parameter=") -> DshToolEnvelopeStyle.XML
            // Jinja escapes the quotes as \"name\", so match the bare words inside the guide.
            window.contains("arguments") || window.contains("function-name") -> DshToolEnvelopeStyle.QWEN_JSON
            else -> null
        }
    }

    private fun fallbackForName(name: String): DshToolEnvelopeStyle {
        val lower = name.lowercase()
        return if (
            "qwen3.5" in lower || "qwen3-coder" in lower || "hermes" in lower || "coder" in lower
        ) {
            DshToolEnvelopeStyle.XML
        } else {
            DshToolEnvelopeStyle.QWEN_JSON
        }
    }
}
