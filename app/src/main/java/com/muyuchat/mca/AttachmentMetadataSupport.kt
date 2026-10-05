package com.muyuchat.mca

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

// R1/S2: 从 MainViewModel 抽出的附件元数据纯逻辑。行为逐字等价，仅把 Application 依赖参数化为
// ContentResolver；调用点在 VM 内用 getApplication<Application>().contentResolver 传入。

internal fun textAttachmentMimeForExtension(name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "json", "jsonl" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "kt", "java", "py", "js", "ts" -> "text/x-code"
        else -> "text/plain"
    }

internal fun mimeTypeForTextAttachment(contentResolver: ContentResolver, uri: Uri, name: String): String {
    contentResolver.getType(uri)?.takeIf { it.isNotBlank() }?.let { return it }
    return textAttachmentMimeForExtension(name)
}

internal fun textAttachmentSizeFromCursor(cursor: Cursor?): Long =
    cursor?.use { c ->
        val index = c.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && c.moveToFirst() && !c.isNull(index)) c.getLong(index) else 0L
    } ?: 0L

internal fun sizeForUri(contentResolver: ContentResolver, uri: Uri): Long {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        return uri.path?.let { File(it).length() } ?: 0L
    }
    return runCatching {
        textAttachmentSizeFromCursor(contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null))
    }.getOrDefault(0L)
}
