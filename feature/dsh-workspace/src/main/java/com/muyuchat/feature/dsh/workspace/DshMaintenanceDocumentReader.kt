package com.muyuchat.feature.dsh.workspace

import android.content.Context
import android.net.Uri
import com.muyuchat.core.dsh.contract.console.DSH_MAINTENANCE_MAX_DOCUMENT_CHARS

/**
 * Bounded reader for the user-selected console patch / theme document (SAF).
 *
 * The console never interprets the document: it is decoded by the app-side maintenance adapter
 * (patch: strict JSON + owner conflict checks; theme: the strict theme schema codec) and every
 * rejection is typed. This reader exists only so the UI can hand over a string without ever loading
 * an unbounded file into memory or bypassing the contract's size limit.
 *
 * A provider that cannot be opened, a read error, or a document larger than the contract limit all
 * return null, and the console shows "无法读取所选文件" instead of submitting a truncated document.
 */
internal object DshMaintenanceDocumentReader {

    /** UTF-8 worst case for one contract character is 4 bytes; kept tight to bound one read. */
    const val MAX_BYTES: Int = DSH_MAINTENANCE_MAX_DOCUMENT_CHARS * 2

    fun read(context: Context, uri: Uri): String? {
        var bytes: ByteArray? = null
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                bytes = readBounded(input)
            }
        }
        return decodeBounded(bytes)
    }

    /** Reads at most [MAX_BYTES]; returns null when the stream is longer (never truncates). */
    fun readBounded(input: java.io.InputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            if (buffer.size() + read > MAX_BYTES) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /** UTF-8 decode plus the contract's character bound; null for empty/oversized/invalid text. */
    fun decodeBounded(bytes: ByteArray?): String? {
        val data = bytes?.takeIf { it.isNotEmpty() } ?: return null
        val text = runCatching { data.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (text.length > DSH_MAINTENANCE_MAX_DOCUMENT_CHARS) return null
        return text
    }
}

/**
 * Attaches a user-selected document to the two console intents that need one.
 *
 * Intents that do not accept a document are returned unchanged, so the caller never has to branch
 * on the intent type and an ambiguous submission cannot silently drop the document.
 */
internal fun withDocument(
    intent: com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent,
    document: String
): com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent = when (intent) {
    is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ApplyConsolePatch ->
        intent.copy(payload = document)
    is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ImportTheme ->
        intent.copy(payload = document)
    is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ImportMcpDeclaration ->
        intent.copy(payload = document)
    else -> intent
}

/** True for the intents whose effect depends on a user-selected document. */
internal fun requiresUserDocument(
    intent: com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
): Boolean =
    intent is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ApplyConsolePatch ||
        intent is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ImportTheme ||
        intent is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ImportMcpDeclaration
