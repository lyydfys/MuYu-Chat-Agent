package com.muyuchat.feature.chat

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

private const val SHARED_IMAGE_DIRECTORY = "shared_images"
private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
private const val STALE_SHARED_IMAGE_AGE_MS = 24L * 60L * 60L * 1_000L
private const val MAX_STALE_SHARED_IMAGE_SCAN = 64

private val imageMimeTypePattern = Regex("^image/[a-z0-9][a-z0-9.+-]{0,63}$")
private val safeShareFileCharacterPattern = Regex("[^A-Za-z0-9._-]+")

/**
 * Builds a one-shot image share chooser backed only by the app's private cache/shared_images path.
 * Callers should run this function off the main thread because it copies the source image.
 */
internal fun createCachedImageShareIntent(
    context: Context,
    image: ImageAssetUiItem,
    includePrompt: Boolean = false
): Result<Intent> = runCatching {
    val appContext = context.applicationContext
    val sourceUri = Uri.parse(image.uriString.trim())
    require(!sourceUri.scheme.isNullOrBlank()) { "图片 URI 无效" }

    val mimeType = resolveSharedImageMimeType(appContext, sourceUri, image.name)
    val extension = sharedImageExtension(mimeType, image.name, sourceUri)
    val shareRoot = File(appContext.cacheDir, SHARED_IMAGE_DIRECTORY)
    require(shareRoot.isDirectory || shareRoot.mkdirs()) { "无法创建临时分享目录" }

    val canonicalCacheRoot = appContext.cacheDir.canonicalFile
    val canonicalShareRoot = shareRoot.canonicalFile
    require(canonicalShareRoot.parentFile == canonicalCacheRoot) {
        "临时分享目录不在应用缓存根目录内"
    }

    val shareFile = File.createTempFile(
        sharedImageFilePrefix(image),
        ".$extension",
        canonicalShareRoot
    ).canonicalFile
    require(shareFile.parentFile == canonicalShareRoot) { "临时分享文件路径无效" }

    try {
        openSharedImageInput(appContext, sourceUri).use { input ->
            FileOutputStream(shareFile).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        require(shareFile.length() > 0L) { "图片文件为空" }
    } catch (error: Throwable) {
        shareFile.delete()
        throw error
    }

    cleanupStaleSharedImages(canonicalShareRoot, protectedFile = shareFile)

    try {
        val contentUri = FileProvider.getUriForFile(
            appContext,
            appContext.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
            shareFile
        )
        createImageShareChooserIntent(
            context = appContext,
            contentUri = contentUri,
            mimeType = mimeType,
            displayName = image.name,
            prompt = image.prompt,
            includePrompt = includePrompt
        )
    } catch (error: Throwable) {
        shareFile.delete()
        throw error
    }
}

internal fun createImageShareChooserIntent(
    context: Context,
    contentUri: Uri,
    mimeType: String,
    displayName: String,
    prompt: String,
    includePrompt: Boolean = false
): Intent {
    val clip = ClipData.newUri(
        context.contentResolver,
        displayName.take(128).ifBlank { "MCA image" },
        contentUri
    )
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, contentUri)
        imageSharePromptOrNull(prompt, includePrompt)?.let { putExtra(Intent.EXTRA_TEXT, it) }
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(sendIntent, "分享图片").apply {
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun imageSharePromptOrNull(prompt: String, includePrompt: Boolean = false): String? =
    prompt.takeIf { includePrompt && it.isNotBlank() }

private fun openSharedImageInput(context: Context, uri: Uri): InputStream =
    when (uri.scheme?.lowercase(Locale.US)) {
        ContentResolver.SCHEME_CONTENT ->
            requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取图片文件" }
        ContentResolver.SCHEME_FILE ->
            File(requireNotNull(uri.path) { "图片路径无效" }).inputStream()
        else -> error("仅支持 content:// 或 file:// 图片")
    }

private fun resolveSharedImageMimeType(
    context: Context,
    sourceUri: Uri,
    displayName: String
): String = sequenceOf(
    runCatching { context.contentResolver.getType(sourceUri) }.getOrNull(),
    mimeTypeFromFileName(displayName),
    mimeTypeFromFileName(sourceUri.lastPathSegment.orEmpty())
)
    .mapNotNull(::normalizedImageMimeTypeOrNull)
    .firstOrNull()
    ?: "image/png"

private fun mimeTypeFromFileName(fileName: String): String? {
    val extension = fileName.substringAfterLast('.', "")
        .lowercase(Locale.US)
        .takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
        ?: return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}

private fun normalizedImageMimeTypeOrNull(raw: String?): String? {
    val normalized = raw
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.let { if (it == "image/jpg") "image/jpeg" else it }
        ?: return null
    return normalized.takeIf(imageMimeTypePattern::matches)
}

private fun sharedImageExtension(mimeType: String, displayName: String, sourceUri: Uri): String {
    val fromMime = MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(mimeType)
        ?.lowercase(Locale.US)
        ?.takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
    if (fromMime != null) return fromMime
    return sequenceOf(displayName, sourceUri.lastPathSegment.orEmpty())
        .map { it.substringAfterLast('.', "").lowercase(Locale.US) }
        .firstOrNull { it.matches(Regex("^[a-z0-9]{1,10}$")) }
        ?: "png"
}

private fun sharedImageFilePrefix(image: ImageAssetUiItem): String {
    val rawBase = image.name.substringBeforeLast('.', image.name)
        .ifBlank { "mca-${image.id.take(12)}" }
    return rawBase
        .replace(safeShareFileCharacterPattern, "_")
        .trim('.', '_', '-')
        .take(48)
        .ifBlank { "mca-image" }
        .let { if (it.length >= 3) "$it-" else "mca-$it-" }
}

private fun cleanupStaleSharedImages(shareRoot: File, protectedFile: File) {
    runCatching {
        val protectedPath = protectedFile.canonicalPath
        val cutoff = System.currentTimeMillis() - STALE_SHARED_IMAGE_AGE_MS
        shareRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isFile)
            ?.take(MAX_STALE_SHARED_IMAGE_SCAN)
            ?.forEach { candidate ->
                val canonicalCandidate = candidate.canonicalFile
                if (canonicalCandidate.parentFile == shareRoot &&
                    canonicalCandidate.canonicalPath != protectedPath &&
                    canonicalCandidate.lastModified() <= cutoff
                ) {
                    canonicalCandidate.delete()
                }
            }
    }
}
