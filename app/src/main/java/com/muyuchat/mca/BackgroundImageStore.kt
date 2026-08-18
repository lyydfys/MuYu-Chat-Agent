package com.muyuchat.mca

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Owns background images selected through the system picker.
 *
 * Picker Uris are deliberately not persisted: providers may revoke them or the
 * user may remove the original file.  Images are decoded, bounded and copied
 * into this app's private files directory before being referenced by a model.
 */
class BackgroundImageStore(context: Context) {
    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, DIRECTORY).also { it.mkdirs() }

    /** Copies [uri] into private storage and returns its stable path/fingerprint. */
    @Throws(IOException::class)
    fun import(uri: Uri): StoredBackgroundImage {
        val resolver = appContext.contentResolver
        val staging = File(root, ".${UUID.randomUUID()}.tmp")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Unable to open image Uri")
            input.use { source ->
                FileOutputStream(staging).use { output ->
                    var total = 0L
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SOURCE_BYTES) {
                            throw IOException("Background image is larger than ${MAX_SOURCE_BYTES / (1024 * 1024)} MiB")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(staging).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IOException("Selected file is not a supported image")
            }
            if (bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS) {
                throw IOException("Background image has too many pixels")
            }

            val sample = calculateSample(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = FileInputStream(staging).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("Unable to decode selected image")
            try {
                val bounded = scaleDown(bitmap)
                try {
                    val hasAlpha = bounded.hasAlpha()
                    val extension = if (hasAlpha) "png" else "jpg"
                    val output = File(root, "${UUID.randomUUID()}.$extension")
                    FileOutputStream(output).use { stream ->
                        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        check(bounded.compress(format, JPEG_QUALITY, stream)) { "Unable to encode background image" }
                        stream.fd.sync()
                    }
                    val digest = output.sha256()
                    return StoredBackgroundImage(
                        path = output.canonicalPath,
                        sha256 = digest,
                        width = bounded.width,
                        height = bounded.height,
                        sizeBytes = output.length()
                    )
                } finally {
                    if (bounded !== bitmap) bounded.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            staging.delete()
        }
    }

    /** Resolves a previously stored path only when it remains inside our root. */
    fun resolve(path: String, expectedSha256: String? = null): File? {
        val file = secureFile(path) ?: return null
        if (expectedSha256 != null && !SHA256_PATTERN.matches(expectedSha256.lowercase(Locale.ROOT))) return null
        if (expectedSha256 != null && !file.sha256().equals(expectedSha256, ignoreCase = true)) return null
        return file
    }

    fun delete(path: String): Boolean = secureFile(path)?.delete() == true

    /** Removes orphaned image files; [referencedPaths] must contain canonical paths. */
    fun cleanup(referencedPaths: Set<String> = emptySet()): Int {
        val references = referencedPaths.mapTo(HashSet()) { runCatching { File(it).canonicalPath }.getOrNull() }
        return root.listFiles().orEmpty().count { file ->
            if (!file.isFile || file.name.startsWith(".")) return@count false
            if (file.extension.lowercase(Locale.ROOT) !in SUPPORTED_EXTENSIONS) return@count false
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@count false
            canonical !in references && file.delete()
        }
    }

    private fun secureFile(path: String): File? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!isBackgroundImagePathOwned(root, file)) return null
        if (!file.isFile || file.extension.lowercase(Locale.ROOT) !in SUPPORTED_EXTENSIONS) return null
        return file
    }

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    companion object {
        const val DIRECTORY = "background_images"
        const val MAX_DIMENSION = 4096
        const val MAX_SOURCE_BYTES = 32L * 1024L * 1024L
        const val MAX_SOURCE_PIXELS = 64L * 1024L * 1024L
        private const val BUFFER_BYTES = 64 * 1024
        private const val JPEG_QUALITY = 90
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class StoredBackgroundImage(
    val path: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

internal fun isBackgroundImagePathOwned(root: File, candidate: File): Boolean = runCatching {
    candidate.canonicalFile.parentFile == root.canonicalFile
}.getOrDefault(false)
