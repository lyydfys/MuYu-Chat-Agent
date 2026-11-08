package com.muyuchat.mca.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Private storage of the app-wide appearance wallpaper.
 *
 * The picker Uri is never persisted: a provider can revoke it or the user can delete the original, so
 * the chosen image is re-encoded into this app's private files directory and only the resulting *file
 * name* is kept in preferences.
 *
 * This deliberately mirrors `com.muyuchat.mca.BackgroundImageStore` (the chat background image store)
 * instead of sharing it. Sharing would be wrong for two reasons:
 *  - that store's `cleanup(referencedPaths)` deletes every file its chat records do not reference, so a
 *    wallpaper kept in the same directory would be deleted as an orphan on the next sweep;
 *  - one directory would make "delete the chat background" and "delete the wallpaper" the same
 *    operation to the orphan sweeper.
 * The *policy* is copied (byte cap, pixel cap, sampled decode, re-encode); the *directory* is not.
 *
 * Nothing here logs: the image is user content (`MCA_PROJECT_HARD_LOCK.md` §11).
 */
internal class AppearanceWallpaperStore(context: Context) {

    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, DIRECTORY).also { it.mkdirs() }

    /**
     * Copies [uri] into private storage, bounded, and returns the stored file name.
     *
     * @throws IOException when the source is unreadable, not an image, or exceeds the documented caps.
     */
    @Throws(IOException::class)
    fun import(uri: Uri): String {
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
                            throw IOException("wallpaper source is larger than ${MAX_SOURCE_BYTES / (1024 * 1024)} MiB")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(staging).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IOException("selected file is not a supported image")
            }
            if (bounds.outWidth.toLong() * bounds.outHeight > MAX_SOURCE_PIXELS) {
                throw IOException("wallpaper has too many pixels")
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = FileInputStream(staging).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("unable to decode the selected image")
            try {
                val bounded = scaleDown(decoded)
                try {
                    // A wallpaper is always opaque behind the app surface, so it is re-encoded as JPEG
                    // unless the source actually carries alpha; that keeps a 2048px wallpaper at a few
                    // hundred KiB instead of several MiB of PNG.
                    val hasAlpha = bounded.hasAlpha()
                    val name = "${UUID.randomUUID()}.${if (hasAlpha) "png" else "jpg"}"
                    FileOutputStream(File(root, name)).use { stream ->
                        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        check(bounded.compress(format, JPEG_QUALITY, stream)) { "unable to encode the wallpaper" }
                        stream.fd.sync()
                    }
                    return name
                } finally {
                    if (bounded !== decoded) bounded.recycle()
                }
            } finally {
                decoded.recycle()
            }
        } finally {
            staging.delete()
        }
    }

    /** Resolves a stored name to a file, or null when it escapes the wallpaper directory. */
    fun resolve(fileName: String): File? {
        if (fileName.isEmpty()) return null
        if (fileName.contains('/') || fileName.contains('\\') || fileName.startsWith(".")) return null
        if (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) !in SUPPORTED_EXTENSIONS) return null
        val file = File(root, fileName)
        return if (file.isFile) file else null
    }

    /** Decodes a stored wallpaper at no more than [maxDimension] on its longest edge, or null. */
    fun decode(fileName: String, maxDimension: Int = MAX_DIMENSION): Bitmap? {
        val file = resolve(fileName) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /** Deletes one stored wallpaper; the directory keeps no history of replaced images. */
    fun delete(fileName: String): Boolean = resolve(fileName)?.delete() == true

    /**
     * Deletes every stored wallpaper except [keep]. There is at most one wallpaper, so anything else in
     * the directory is either a replaced image or a leftover from a crash between write and persist.
     */
    fun retainOnly(keep: String?): Int = root.listFiles().orEmpty().count { file ->
        if (!file.isFile) return@count false
        val isCandidate = file.name.startsWith(".") ||
            file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
        if (!isCandidate) return@count false
        if (keep != null && file.name == keep) return@count false
        file.delete()
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    internal companion object {
        /** Kept apart from `background_images` on purpose; see the class KDoc. */
        const val DIRECTORY: String = "appearance_wallpaper"

        /**
         * Longest-edge ceiling for the stored file. A phone screen is ~1080-1440px wide, so 2048 keeps
         * a full-bleed wallpaper sharp on every density while bounding one ARGB_8888 bitmap to
         * 2048 * 2048 * 4 B = 16 MiB in the worst (square) case; a 16:9 wallpaper is ~9 MiB.
         */
        const val MAX_DIMENSION: Int = 2048

        /** Refuse absurd sources before touching the disk. */
        const val MAX_SOURCE_BYTES: Long = 32L * 1024L * 1024L

        /** 64 MP, i.e. well beyond any phone camera output we would accept as a wallpaper. */
        const val MAX_SOURCE_PIXELS: Long = 64L * 1024L * 1024L

        private const val BUFFER_BYTES = 64 * 1024
        private const val JPEG_QUALITY = 88
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}

/**
 * Power-of-two subsample factor that brings both edges to at most [maxDimension].
 *
 * Pure and extracted so the memory bound is unit-testable without decoding a real image: the factor
 * decides whether a 8000x6000 source is decoded as 2000x1500 or as a 48 MiB full-size bitmap.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    var sample = 1
    while (width / sample > maxDimension || height / sample > maxDimension) sample *= 2
    return sample
}

/** Persisted wallpaper preference: the stored *file name*, never the picker Uri. */
internal class AppearanceWallpaperPreferences(private val store: AppearancePreferenceStore) {

    fun loadFileName(): String? = store.read(KEY_WALLPAPER_FILE)?.takeIf { it.isNotBlank() }

    fun saveFileName(fileName: String?) {
        store.write(KEY_WALLPAPER_FILE, fileName)
    }

    internal companion object {
        const val KEY_WALLPAPER_FILE: String = "appearance_wallpaper_file"

        /**
         * A third preferences file, deliberately not `mca_appearance`: a wallpaper is a different
         * cadence of user edit (an occasional image) from the colour mode (a toggle), and a future
         * migration of one must not touch the other. Not `mca_chat_appearance` either, which is
         * per-conversation data.
         */
        const val PREFERENCES_NAME: String = "mca_appearance_wallpaper"
    }
}
