package com.muyuchat.mca

import java.io.File
import java.util.UUID
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Image asset file management: atomic write, deletion leases and directory reconciliation.
//
// Moved here verbatim from MainViewModel during P2. These were already top-level declarations
// above the class; the move gives them a file named for what they do.
//
// Kept in com.muyuchat.mca rather than moved to a layer module, because there is no import
// change anywhere: the class and the tests reach these exactly as before.
//
// The six private constants at the end belong to this file and are used nowhere else, so they
// moved with it and stay private. Nothing in this slice needed its visibility widened.
//
// No size limit, extension, lease rule or reconciliation branch was edited.

internal fun writeImageAssetBytesAtomically(
    directory: File,
    fileName: String,
    bytes: ByteArray,
    parentDirectorySyncer: ParentDirectorySyncer = AndroidParentDirectorySyncer
): File {
    require(bytes.isNotEmpty()) { "Generated image bytes must not be empty." }
    require(fileName.isNotBlank() && fileName == File(fileName).name) {
        "Generated image file name must be a safe leaf name."
    }
    val root = directory.canonicalFile
    require(root.isDirectory || root.mkdirs()) {
        "Unable to create the image asset directory."
    }
    val requiredSpace = Math.addExact(bytes.size.toLong(), MIN_IMAGE_ASSET_FREE_SPACE_BYTES)
    val usableSpace = root.usableSpace
    require(usableSpace <= 0L || usableSpace >= requiredSpace) {
        "Insufficient storage to publish the generated image while preserving the safety reserve."
    }
    val output = File(root, fileName).canonicalFile
    require(output.parentFile == root) { "Generated image path escaped the image asset directory." }
    check(!output.exists()) { "Generated image destination already exists." }
    val temp = File(root, ".$fileName.${UUID.randomUUID()}.part")
    try {
        temp.outputStream().use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        check(temp.isFile && temp.length() == bytes.size.toLong()) {
            "Generated image staging write was incomplete."
        }
        durableMoveWithinParent(
            source = temp,
            target = output,
            move = { staged, published ->
                check(staged.renameTo(published)) {
                    "Unable to atomically publish the generated image."
                }
            },
            parentDirectorySyncer = parentDirectorySyncer
        )
        return output
    } catch (error: Throwable) {
        runCatching { output.delete() }
        throw error
    } finally {
        runCatching { temp.delete() }
    }
}

internal data class ImportedImageAssetFile(
    val file: File,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mimeType: String
)

internal data class ImageAssetDeletionLease(
    val original: File?,
    val staged: File?
) {
    fun commit(): Boolean = staged?.let { file ->
        runCatching { !file.exists() || file.delete() || !file.exists() }.getOrDefault(false)
    } ?: true

    fun rollback(): Boolean {
        val source = staged ?: return true
        val target = original ?: return false
        return runCatching {
            !source.exists() || (!target.exists() && source.renameTo(target))
        }.getOrDefault(false)
    }
}

internal data class ImageAssetReconciliationReport(
    val restoredDeletions: Int = 0,
    val deletedOrphans: Int = 0,
    val failed: Int = 0
)

internal suspend fun copyImageAssetStreamAtomically(
    directory: File,
    suggestedExtension: String,
    input: InputStream,
    timestamp: Long = System.currentTimeMillis()
): ImportedImageAssetFile {
    val root = directory.canonicalFile
    require(root.isDirectory || root.mkdirs()) { "Unable to create the image asset directory." }
    val temp = File(root, ".import-${UUID.randomUUID()}.part").canonicalFile
    require(temp.parentFile == root) { "Image import staging path escaped its directory." }
    var copied = 0L
    var publishedOutput: File? = null
    try {
        temp.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                copied = Math.addExact(copied, read.toLong())
                require(copied <= MAX_IMAGE_ASSET_FILE_BYTES) {
                    "Image exceeds the 96 MiB library limit."
                }
                val remainingSpace = root.usableSpace
                require(remainingSpace <= 0L ||
                    remainingSpace >= Math.addExact(read.toLong(), MIN_IMAGE_ASSET_FREE_SPACE_BYTES)
                ) {
                    "Insufficient storage to import the image while preserving the safety reserve."
                }
                output.write(buffer, 0, read)
            }
            output.flush()
            output.fd.sync()
        }
        currentCoroutineContext().ensureActive()
        require(copied > 0L && temp.length() == copied) { "Image import was empty or incomplete." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temp.path, bounds)
        val pixels = Math.multiplyExact(bounds.outWidth.toLong(), bounds.outHeight.toLong())
        require(bounds.outWidth in 1..MAX_IMAGE_ASSET_SIDE &&
            bounds.outHeight in 1..MAX_IMAGE_ASSET_SIDE &&
            pixels in 1L..MAX_IMAGE_ASSET_PIXELS
        ) { "Image exceeds the 4096-pixel side or 16-megapixel library limit." }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 512) {
            sampleSize = Math.multiplyExact(sampleSize, 2)
        }
        val decoded = BitmapFactory.decodeFile(
            temp.path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: error("Image bytes could not be decoded.")
        try {
            require(decoded.width > 0 && decoded.height > 0) { "Decoded image is empty." }
        } finally {
            decoded.recycle()
        }
        val mimeType = bounds.outMimeType?.trim()?.lowercase().orEmpty()
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/heif", "image/heic" -> "heic"
            "" -> suggestedExtension.lowercase().takeIf(IMAGE_ASSET_EXTENSIONS::contains)
                ?: error("Unsupported image format.")
            else -> error("Unsupported image format.")
        }
        val safeTimestamp = timestamp.coerceAtLeast(0L)
        val output = File(
            root,
            "$safeTimestamp-${UUID.randomUUID().toString().take(8)}.$extension"
        ).canonicalFile
        require(output.parentFile == root && !output.exists()) {
            "Image import destination is invalid."
        }
        publishedOutput = output
        currentCoroutineContext().ensureActive()
        durableMoveWithinParent(
            source = temp,
            target = output,
            move = { staged, published ->
                check(staged.renameTo(published)) {
                    "Unable to atomically publish the imported image."
                }
            }
        )
        return ImportedImageAssetFile(
            file = output,
            sizeBytes = copied,
            width = bounds.outWidth,
            height = bounds.outHeight,
            mimeType = mimeType.ifBlank { "image/$extension" }
        )
    } catch (error: Throwable) {
        runCatching { publishedOutput?.delete() }
        throw error
    } finally {
        runCatching { temp.delete() }
    }
}

internal fun ImageAssetRecord.stageLocalCopyDeletion(ownedRoot: File): ImageAssetDeletionLease? =
    runCatching {
        val root = ownedRoot.canonicalFile
        val original = ownedLocalImageFileOrNull(root)
            ?: return@runCatching ImageAssetDeletionLease(null, null)
        if (!original.exists()) return@runCatching ImageAssetDeletionLease(original, null)
        val staged = File(
            root,
            ".delete-${UUID.randomUUID()}--${original.name}"
        ).canonicalFile
        if (staged.parentFile != root || staged.exists() || !original.renameTo(staged)) {
            return@runCatching null
        }
        ImageAssetDeletionLease(original, staged)
    }.getOrNull()

internal fun ImageAssetRecord.ownedLocalImageFileOrNull(ownedRoot: File): File? = runCatching {
    val uri = Uri.parse(uriString)
    if (!uri.scheme.equals("file", ignoreCase = true)) return@runCatching null
    val root = ownedRoot.canonicalFile
    val file = uri.path?.let(::File)?.canonicalFile ?: return@runCatching null
    file.takeIf { it.parentFile == root }
}.getOrNull()

internal fun reconcileImageAssetDirectory(
    directory: File,
    images: List<ImageAssetRecord>
): ImageAssetReconciliationReport {
    val root = runCatching { directory.canonicalFile }.getOrNull()
        ?: return ImageAssetReconciliationReport(failed = 1)
    if (!root.exists() && !root.mkdirs()) return ImageAssetReconciliationReport(failed = 1)
    val referenced = images.mapNotNullTo(mutableSetOf()) { image ->
        runCatching {
            val uri = Uri.parse(image.uriString)
            if (!uri.scheme.equals("file", ignoreCase = true)) return@runCatching null
            val file = uri.path?.let(::File)?.canonicalFile ?: return@runCatching null
            file.path.takeIf { file.parentFile == root }
        }.getOrNull()
    }
    var restored = 0
    var deleted = 0
    var failed = 0
    root.listFiles().orEmpty().filter(File::isFile).forEach { raw ->
        val file = runCatching { raw.canonicalFile }.getOrNull()
        if (file == null || file.parentFile != root) {
            failed++
            return@forEach
        }
        val deletionMatch = IMAGE_ASSET_DELETION_STAGE_REGEX.matchEntire(file.name)
        if (deletionMatch != null) {
            val original = File(root, deletionMatch.groupValues[1]).canonicalFile
            if (original.parentFile != root) {
                failed++
            } else if (original.path in referenced) {
                val recovered = if (original.exists()) file.delete() else file.renameTo(original)
                if (recovered) restored++ else failed++
            } else if (file.delete() || !file.exists()) {
                deleted++
            } else {
                failed++
            }
        } else if (file.path !in referenced) {
            if (file.delete() || !file.exists()) deleted++ else failed++
        }
    }
    return ImageAssetReconciliationReport(restored, deleted, failed)
}

private const val MAX_IMAGE_ASSET_FILE_BYTES = 96L * 1024L * 1024L
private const val MIN_IMAGE_ASSET_FREE_SPACE_BYTES = 64L * 1024L * 1024L
private const val MAX_IMAGE_ASSET_SIDE = 4_096
private const val MAX_IMAGE_ASSET_PIXELS = 16_777_216L
private val IMAGE_ASSET_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "heic", "heif")
private val IMAGE_ASSET_DELETION_STAGE_REGEX = Regex(
    "\\.delete-[0-9a-fA-F-]{36}--(.+)"
)
