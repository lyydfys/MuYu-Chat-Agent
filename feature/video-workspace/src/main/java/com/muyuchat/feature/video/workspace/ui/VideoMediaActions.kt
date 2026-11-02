package com.muyuchat.feature.video.workspace.ui

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.muyuchat.core.video.contract.VideoMediaProjection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Platform side of the media actions (V1-05). The feature receives only the owner's opaque
 * `content://` reference: sharing rejects anything that is not a content URI (so a filesystem
 * path can never leak), and save-as streams into the document the user picked via SAF — no broad
 * storage permission is requested or needed.
 */

internal const val VIDEO_SAVE_MIME_TYPE: String = "video/mp4"

private const val VIDEO_COPY_BUFFER_BYTES = 64 * 1024

internal fun defaultVideoExportName(nowMs: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
    return "MCA_video_$stamp.mp4"
}

/**
 * Starts the system share chooser with a read grant on the owner's content URI. Returns a
 * user-facing error message, or null when the chooser was launched.
 */
internal fun shareVideo(context: Context, media: VideoMediaProjection): String? {
    val uri = runCatching { Uri.parse(media.reference) }.getOrNull()
    if (uri == null || !uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
        return "媒体引用不是 content URI，已阻止分享以避免暴露文件路径"
    }
    val clip = ClipData.newUri(context.contentResolver, "MCA 视频", uri)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, "分享视频").apply {
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(chooser)
        null
    } catch (failure: Throwable) {
        "分享失败：${failure.message ?: failure.javaClass.simpleName}"
    }
}

/**
 * Streams the owner's content URI into the SAF destination with a 64 KiB buffer and returns the
 * copied byte count. Every failure (including cancellation) deletes only the target this call
 * opened, never the source and never an unrelated file.
 */
internal suspend fun copyVideoToDocument(
    context: Context,
    sourceReference: String,
    target: Uri,
): Long = withContext(Dispatchers.IO) {
    val source = Uri.parse(sourceReference)
    require(source.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
        "媒体引用不是 content URI，无法另存"
    }
    val resolver = context.contentResolver
    val input = resolver.openInputStream(source) ?: error("无法读取 MCA 视频副本")
    var outputOpened = false
    try {
        input.use { sourceStream ->
            val output = resolver.openOutputStream(target, "w") ?: error("无法写入所选位置")
            outputOpened = true
            output.use { targetStream ->
                val buffer = ByteArray(VIDEO_COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = sourceStream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    targetStream.write(buffer, 0, read)
                    copied += read
                }
                targetStream.flush()
                require(copied > 0L) { "视频文件为空，未写入任何内容" }
                copied
            }
        }
    } catch (failure: Throwable) {
        if (outputOpened) deleteTargetQuietly(resolver, target)
        throw failure
    }
}

/** Best effort removal of the partially written SAF target opened by this save operation. */
private fun deleteTargetQuietly(resolver: ContentResolver, target: Uri) {
    val deleted = runCatching { DocumentsContract.deleteDocument(resolver, target) }.getOrDefault(false)
    if (!deleted) {
        runCatching { resolver.delete(target, null, null) }
    }
}

/** Display name of a picked document, used only as an import source label. */
internal fun queryDocumentDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0) {
            null
        } else {
            cursor.getString(index)?.trim()?.takeIf { it.isNotEmpty() }?.take(180)
        }
    }
}.getOrNull()
