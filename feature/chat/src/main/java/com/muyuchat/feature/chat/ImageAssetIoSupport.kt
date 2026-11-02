package com.muyuchat.feature.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

// P9/S4：从 ImageWorkspaceScreens.kt 尾部等值移出的图像资产 I/O 与展示助手簇。
// 原样逐字迁移，未做行为改动；跨区被复用的私有纯工具按协议提升为 internal。
// P9/S4: private→internal（纯工具；跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun String.containsHanScriptForTranslationUi(): Boolean =
    codePoints().anyMatch { codePoint ->
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
    }

internal fun loadImageBitmap(
    context: Context,
    uriString: String,
    maxDimensionPx: Int? = null
): Bitmap? = runCatching {
    val uri = Uri.parse(uriString)
    fun decode(options: BitmapFactory.Options? = null): Bitmap? =
        if (uri.scheme.equals("file", ignoreCase = true)) {
            if (options == null) BitmapFactory.decodeFile(uri.path)
            else BitmapFactory.decodeFile(uri.path, options)
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                if (options == null) BitmapFactory.decodeStream(stream)
                else BitmapFactory.decodeStream(stream, null, options)
            }
        }

    val bound = maxDimensionPx?.coerceAtLeast(1) ?: return@runCatching decode()
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(boundsOptions)
    if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return@runCatching null
    var sampleSize = 1
    while (maxOf(boundsOptions.outWidth, boundsOptions.outHeight) / sampleSize > bound &&
        sampleSize <= (1 shl 29)
    ) {
        sampleSize *= 2
    }
    decode(BitmapFactory.Options().apply { inSampleSize = sampleSize })
}.getOrNull()

/**
 * Cache policy or metadata failure must never remove the image path, but a cancelled decode must
 * not silently fall through to a second, uncached decode either: cancellation is rethrown before
 * the fallback is considered.
 */
internal suspend fun <T : Any> loadCachedImageOrFallback(
    cached: suspend () -> T?,
    fallback: suspend () -> T?
): T? = try {
    cached()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    fallback()
}

internal suspend fun loadChatImageBitmap(
    context: Context,
    uriString: String,
    maxDimensionPx: Int? = null,
    revisionKey: Any? = null,
    useCache: Boolean = true
): Bitmap? {
    val appContext = context.applicationContext ?: context
    if (!useCache) {
        return withContext(Dispatchers.IO) {
            loadImageBitmap(appContext, uriString, maxDimensionPx)
        }
    }
    return loadCachedImageOrFallback(
        cached = { ChatImageBitmapStore.load(appContext, uriString, maxDimensionPx, revisionKey) },
        fallback = {
            withContext(Dispatchers.IO) {
                loadImageBitmap(appContext, uriString, maxDimensionPx)
            }
        }
    )
}

/**
 * Honest load state: a failed decode must not keep rendering "loading" forever.
 *
 * Legacy three-state projection kept for the call sites that only need Loading/Ready/Failed. New
 * surfaces that must also distinguish "no source yet" and that need request identity use
 * [rememberChatImageBitmap] with [ImageDisplayProjection] instead of widening this sealed type.
 */
internal sealed interface ChatImageLoadState {
    data object Loading : ChatImageLoadState
    data class Ready(val bitmap: Bitmap) : ChatImageLoadState
    data object Failed : ChatImageLoadState
}

@Composable
internal fun rememberChatImageLoadState(
    context: Context,
    uriString: String,
    maxDimensionPx: Int? = null,
    revisionKey: Any? = null,
    taskIdentity: Any? = null
): ChatImageLoadState {
    val state = produceState<ChatImageLoadState>(
        initialValue = ChatImageLoadState.Loading,
        key1 = uriString,
        key2 = maxDimensionPx,
        key3 = chatImageRevisionToken(revisionKey) to taskIdentity
    ) {
        // A retry (revisionKey change) restarts the producer; reset to Loading so the failed
        // appearance does not persist while the new decode runs.
        value = ChatImageLoadState.Loading
        val bitmap = try {
            loadChatImageBitmap(context, uriString, maxDimensionPx, revisionKey)
        } catch (cancelled: CancellationException) {
            // A cancelled producer must never publish Failed: it is a superseded request, not a
            // user-visible decode failure.
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        // The effect may already be disposed (asset removed, screen left, request superseded);
        // a terminal value must not be written back into a surface that no longer owns it.
        if (!coroutineContext.isActive) return@produceState
        value = if (bitmap != null) ChatImageLoadState.Ready(bitmap) else ChatImageLoadState.Failed
    }
    return state.value
}

/**
 * Request-identified image projection for one surface.
 *
 * The historical name is kept for the existing product-closure contract; the function now returns
 * the full [ImageDisplayProjection] instead of a nullable bitmap, because "unreadable", "no frame
 * produced yet" and "still decoding" must not all collapse into `null`:
 *
 * - [ImageDisplayPhase.ABSENT] no source configured yet (nothing exists to render or retry);
 * - [ImageDisplayPhase.LOADING] a matching decode is running;
 * - [ImageDisplayPhase.READY] a result committed for *this* request (URI, size, revision, owner);
 * - [ImageDisplayPhase.FAILED] the read failed and can be retried by bumping the revision key.
 *
 * A request identity mismatch projects Loading synchronously on the first frame, the decode checks
 * cancellation and request identity before committing, and the cache/concurrency owner stays
 * [ChatImageBitmapStore] -- no second cache or runtime is introduced.
 */
@Composable
internal fun rememberChatImageBitmap(
    context: Context,
    uriString: String,
    maxDimensionPx: Int? = null,
    revisionKey: Any? = null,
    taskIdentity: Any? = null
): ImageDisplayProjection<Bitmap> {
    val request = remember(
        uriString,
        maxDimensionPx?.coerceAtLeast(1),
        chatImageRevisionToken(revisionKey),
        taskIdentity
    ) {
        ImageDisplayRequest(
            uriString = uriString,
            maxDimensionPx = maxDimensionPx,
            revisionKey = revisionKey,
            taskIdentity = taskIdentity
        )
    }
    val committed = remember { mutableStateOf<ImageDisplayProjection<Bitmap>?>(null) }
    val slot = remember {
        ImageDisplaySlot<Bitmap>(
            read = { committed.value },
            write = { next -> committed.value = next }
        )
    }
    val latestRequest by rememberUpdatedState(request)
    LaunchedEffect(request) {
        if (!request.isPresent) return@LaunchedEffect
        driveImageDisplay(
            slot = slot,
            request = request,
            currentRequest = { latestRequest },
            decode = { decodeRequest ->
                loadChatImageBitmap(
                    context = context,
                    uriString = decodeRequest.uriString,
                    maxDimensionPx = decodeRequest.maxDimensionPx,
                    revisionKey = decodeRequest.revisionKey
                )
            }
        )
    }
    return slot.project(request)
}

// P9/S4: private→internal（纯工具；跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun downloadImageAssetToGallery(context: Context, image: ImageAssetUiItem): Result<String> =
    runCatching { copyImageAssetToGallery(context, image).displayPath }

private data class SavedImageCopy(
    val uri: Uri,
    val displayPath: String,
    val mimeType: String
)

private fun copyImageAssetToGallery(context: Context, image: ImageAssetUiItem): SavedImageCopy =
    runCatching {
        val fileName = image.downloadFileName()
        val mimeType = fileName.imageMimeType()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MCA")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建系统图片文件")
        try {
            openImageAssetInputStream(context, image.uriString).use { input ->
                resolver.openOutputStream(outputUri)?.use { output ->
                    input.copyTo(output)
                } ?: error("无法写入系统图片库")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outputUri, values, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(outputUri, null, null)
            throw error
        }
        SavedImageCopy(
            uri = outputUri,
            displayPath = "${Environment.DIRECTORY_PICTURES}/MCA/$fileName",
            mimeType = mimeType
        )
    }.getOrThrow()

private fun openImageAssetInputStream(context: Context, uriString: String): InputStream {
    val uri = Uri.parse(uriString)
    return if (uri.scheme.equals("file", ignoreCase = true)) {
        File(requireNotNull(uri.path) { "图片路径无效" }).inputStream()
    } else {
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片文件" }
    }
}

private fun ImageAssetUiItem.downloadFileName(): String {
    val rawName = name.ifBlank { "MCA-${id.take(8)}" }
    val extension = rawName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in setOf("png", "jpg", "jpeg", "webp") }
        ?: uriString.substringAfterLast('.', "")
            .substringBefore('?')
            .lowercase()
            .takeIf { it in setOf("png", "jpg", "jpeg", "webp") }
        ?: "png"
    val base = rawName.substringBeforeLast('.', rawName)
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .trim('_')
        .take(64)
        .ifBlank { "MCA-${id.take(8)}" }
    return "$base.$extension"
}

private fun String.imageMimeType(): String =
    when (substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

// P9/S4: private→internal（纯工具；跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun String.displayImageSource(): String =
    when {
        startsWith("upscaled:", ignoreCase = true) -> "高清放大"
        startsWith("generated:", ignoreCase = true) -> removePrefix("generated:")
            .replace('-', ' ')
            .replace('_', ' ')
            .ifBlank { "生成图片" }
        equals("generated", ignoreCase = true) -> "生成图片"
        equals("uploaded", ignoreCase = true) -> "上传图片"
        isBlank() -> "图片"
        else -> this
    }

// P9/S4: private→internal（纯工具；跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun Long.formatElapsed(): String {
    val minutes = this / 60L
    val seconds = this % 60L
    return "%02d:%02d".format(minutes, seconds)
}

internal fun ChatModelChoice.isQwenImage21Model(): Boolean =
    id.contains("qwen_image_21", ignoreCase = true) ||
        displayName.contains("Qwen-Image-2.1", ignoreCase = true)
