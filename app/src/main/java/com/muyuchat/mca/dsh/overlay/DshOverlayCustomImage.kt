package com.muyuchat.mca.dsh.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Owns the user-supplied mascot bitmap.
 *
 * A picked photo is copied into app-private storage once, decoded to prove it really is an image,
 * and stored under a content hash so re-picking the same picture does not accumulate files. The
 * overlay then only ever reads a private path it wrote itself — never a content URI, which can be
 * revoked at any time by the picker. A missing/corrupt file is not an error state for the window:
 * [load] returns null and the renderer falls back to the vector whale.
 *
 * All methods touch the file system and are called off the main thread (the service uses
 * `Dispatchers.IO`); [exists] is a metadata-only check used by the settings panel.
 */
internal object DshOverlayCustomImage {

    /** A picked photo larger than this is rejected instead of being copied into the app. */
    const val MAX_SOURCE_BYTES = 8L * 1024 * 1024

    /** Decoded at most this large; the overlay mark never exceeds 96 dp, so 384 px is already ample. */
    private const val MAX_TARGET_PX = 384

    private const val DIRECTORY_NAME = "dsh-overlay"
    private const val IMAGE_PREFIX = "mascot-"
    private const val INCOMING_PREFIX = "incoming-"

    /**
     * Copies [uri] into app-private storage and returns the stored absolute path.
     *
     * Returns null for: no readable stream, an empty oversize or undecodable file, or a failed
     * commit. A null result leaves the previous image untouched.
     */
    fun import(context: Context, uri: Uri): String? {
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val incoming = File.createTempFile(INCOMING_PREFIX, ".bin", directory)
        try {
            val copied = runCatching {
                val input = context.contentResolver.openInputStream(uri) ?: return null
                input.use { source -> incoming.outputStream().use { sink -> copyBounded(source, sink) } }
            }.getOrNull() ?: return null
            if (copied <= 0L || copied > MAX_SOURCE_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(incoming.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val target = File(directory, IMAGE_PREFIX + digestOf(incoming) + ".png")
            if (target.isFile) {
                incoming.delete()
            } else if (!incoming.renameTo(target)) {
                return null
            }
            pruneOtherImages(directory, target)
            return target.absolutePath
        } finally {
            if (incoming.isFile) incoming.delete()
        }
    }

    /** Decodes the stored mark, bounded and sampled; null means "use the vector mark instead". */
    fun load(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_TARGET_PX &&
                bounds.outHeight / (sample * 2) >= MAX_TARGET_PX
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Metadata-only check for the settings panel ("所选图片不可用" vs ready). */
    fun exists(path: String?): Boolean = !path.isNullOrBlank() && File(path).isFile

    private fun directory(context: Context): File = File(context.applicationContext.filesDir, DIRECTORY_NAME)

    private fun copyBounded(source: InputStream, sink: OutputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_SOURCE_BYTES) return -1L
            sink.write(buffer, 0, read)
        }
        return total
    }

    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return buildString {
            digest.digest().forEach { byte -> append((byte.toInt() and 0xFF).toString(16).padStart(2, '0')) }
        }
    }

    /** Keeps exactly one stored mark so repeated picks cannot fill the private directory. */
    private fun pruneOtherImages(directory: File, keep: File) {
        directory.listFiles()?.forEach { candidate ->
            if (candidate.name.startsWith(IMAGE_PREFIX) && candidate != keep) candidate.delete()
        }
    }
}
