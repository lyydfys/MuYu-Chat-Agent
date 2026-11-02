package com.muyuchat.feature.video.workspace.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.video.contract.VideoMediaProjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thumbnail surface and destructive confirmation of the media library (V1-05). */

@Composable
internal fun VideoThumbnail(
    media: VideoMediaProjection,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var attempt by remember(media.mediaId.value) { mutableIntStateOf(0) }
    var loading by remember(media.mediaId.value, attempt) { mutableStateOf(true) }
    var failed by remember(media.mediaId.value, attempt) { mutableStateOf(false) }
    var bitmap by remember(media.mediaId.value, attempt) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(media.mediaId.value, attempt) {
        loading = true
        failed = false
        bitmap = null
        val frame = withContext(Dispatchers.IO) {
            loadVideoThumbnail(context, media.reference)
        }
        bitmap = frame
        failed = frame == null
        loading = false
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val frame = bitmap
            when {
                frame != null -> Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = "视频缩略图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                failed -> {
                    Icon(
                        McaIcons.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(McaIconSize.S),
                    )
                    McaIconButton(
                        contentDescription = "重新加载缩略图",
                        onClick = { attempt += 1 },
                        size = 48.dp,
                        glyphSize = McaIconSize.S,
                        modifier = Modifier.align(Alignment.BottomEnd).testTag("video.thumbnail_retry"),
                    ) {
                        Icon(McaIcons.Refresh, contentDescription = null)
                    }
                }

                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
internal fun VideoDeleteConfirmDialog(
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    McaDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("删除视频？") },
        text = {
            Column {
                Text("将删除 MCA 私有副本，不影响已导出的副本或原始输入。此操作无法撤销。")
                if (error != null) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .testTag("video.delete_error")
                            .semantics { contentDescription = "删除失败：$error" },
                    )
                }
            }
        },
        confirmButton = {
            A11yLabeledControl(
                contentDescription = if (busy) "正在删除" else "删除作品",
                stateDescription = if (busy) "正在删除，请稍候" else null,
                enabled = !busy,
                onClick = onConfirm,
            ) {
                McaButton(
                    onClick = onConfirm,
                    style = McaButtonStyle.Text,
                    enabled = !busy,
                    loading = busy,
                    contentColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("video.delete_confirm"),
                ) {
                    Text(if (busy) "正在删除…" else "删除")
                }
            }
        },
        dismissButton = {
            A11yLabeledControl(
                contentDescription = "取消删除",
                enabled = !busy,
                onClick = onDismiss,
            ) {
                McaButton(
                    onClick = onDismiss,
                    style = McaButtonStyle.Text,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("取消")
                }
            }
        },
    )
}

/**
 * First meaningful frame of the owner's content URI, scaled to card size. Any decode failure is
 * reported as null so the caller can show the retryable placeholder instead of a broken image.
 */
private fun loadVideoThumbnail(context: Context, reference: String): Bitmap? {
    val uri = runCatching { Uri.parse(reference) }.getOrNull() ?: return null
    if (!uri.scheme.equals("content", ignoreCase = true)) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?.let { scaleDown(it, 320) }
        frame ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?.let { lateFrame -> scaleDown(lateFrame, 320) }
    } catch (failure: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun scaleDown(bitmap: Bitmap, maxWidth: Int): Bitmap {
    if (bitmap.width <= maxWidth || bitmap.width <= 0) return bitmap
    val targetHeight = (bitmap.height * (maxWidth.toFloat() / bitmap.width))
        .toInt()
        .coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}
