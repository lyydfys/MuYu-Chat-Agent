package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the extension chosen for an image asset, and proves the P2 extraction changed nothing.
 *
 * The extension becomes part of the stored file name, so it is a small piece of the on-disk
 * format: the gallery, the decoder and any later export all read it back. Both functions
 * restrict themselves to an allow-list and fall back to `png`, and neither had a test.
 *
 * The two paths also disagree on JPEG, which is recorded here rather than smoothed over.
 */
class ImageFileExtensionTest {

    @Test
    fun `a recognised extension is kept as it was written`() {
        listOf("jpg", "jpeg", "png", "webp", "heic", "heif").forEach { extension ->
            assertEquals(
                "photo.$extension",
                extension,
                imageExtension("photo.$extension")
            )
        }
    }

    @Test
    fun `the extension is read case insensitively from the last dot`() {
        assertEquals("png", imageExtension("PHOTO.PNG"))
        assertEquals("jpg", imageExtension("Photo.JpG"))
        assertEquals("png", imageExtension("archive.tar.png"))
        assertEquals("jpg", imageExtension("my.photo.v2.jpg"))
    }

    @Test
    fun `anything unrecognised falls back to png`() {
        listOf(
            "photo.gif",
            "photo.bmp",
            "photo.tiff",
            "photo.svg",
            "photo.avif",
            "photo",
            "photo.",
            "",
            "   "
        ).forEach { name ->
            assertEquals(
                "'$name' must fall back",
                FALLBACK_EXTENSION,
                imageExtension(name)
            )
        }
    }

    @Test
    fun `a mime type maps to the extension the gallery expects`() {
        assertEquals("jpg", imageExtensionForMime("image/jpeg"))
        assertEquals("jpg", imageExtensionForMime("image/jpg"))
        assertEquals("webp", imageExtensionForMime("image/webp"))
        assertEquals("heic", imageExtensionForMime("image/heic"))
        assertEquals("heif", imageExtensionForMime("image/heif"))
    }

    @Test
    fun `mime parsing ignores case and parameters`() {
        assertEquals("jpg", imageExtensionForMime("IMAGE/JPEG"))
        assertEquals("jpg", imageExtensionForMime("Image/Jpeg; charset=utf-8"))
        assertEquals("webp", imageExtensionForMime("  image/webp  "))
        assertEquals("jpg", imageExtensionForMime("image/jpeg;charset=utf-8;q=0.9"))
    }

    @Test
    fun `an unrecognised mime type falls back to png`() {
        assertEquals("png", imageExtensionForMime("image/gif"))
        assertEquals("png", imageExtensionForMime("image/bmp"))
        assertEquals("png", imageExtensionForMime("application/octet-stream"))
        assertEquals("png", imageExtensionForMime(""))
        assertEquals("png", imageExtensionForMime("image/"))
    }

    @Test
    fun `image png reaches png through the fallback, not through a named entry`() {
        // There is no "image/png" branch. Recording it keeps the behaviour explicit, so a
        // later edit that adds one does not quietly change the fallback's meaning.
        assertEquals(FALLBACK_EXTENSION, imageExtensionForMime("image/png"))
    }

    @Test
    fun `both functions agree that gif is not a storable extension`() {
        assertEquals("png", imageExtension("animation.gif"))
        assertEquals("png", imageExtensionForMime("image/gif"))
    }

    @Test
    fun `the two paths disagree on jpeg, and that is recorded`() {
        // An imported photo.jpeg keeps ".jpeg"; a generated image/jpeg becomes ".jpg".
        // Both are valid JPEG extensions and the bytes are unchanged, so this is a naming
        // inconsistency rather than a data problem. Pinned so it stays visible.
        assertEquals("jpeg", imageExtension("photo.jpeg"))
        assertEquals("jpg", imageExtensionForMime("image/jpeg"))
        assertNotEquals(imageExtension("photo.jpeg"), imageExtensionForMime("image/jpeg"))
    }

    @Test
    fun `the extraction matches the original implementations on every sample`() {
        val names = listOf(
            "photo.jpg", "photo.jpeg", "photo.JPG", "photo.png", "photo.webp",
            "photo.heic", "photo.heif", "photo.gif", "photo.bmp", "photo",
            "photo.", "", "   ", "archive.tar.png", "my.photo.v2.jpg", "a.b.c.webp",
            "PHOTO.PNG", "photo.未知"
        )
        names.forEach { name ->
            assertEquals(
                "name mapping diverged for '$name'",
                originalImageExtension(name),
                imageExtension(name)
            )
        }

        val mimeTypes = listOf(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic",
            "image/heif", "image/gif", "image/bmp", "IMAGE/JPEG", "Image/Jpeg; charset=utf-8",
            "  image/webp  ", "image/jpeg;charset=utf-8;q=0.9", "", "image/", "text/plain",
            "application/octet-stream", "image/jpeg;", ";image/jpeg"
        )
        mimeTypes.forEach { mime ->
            assertEquals(
                "mime mapping diverged for '$mime'",
                originalImageExtensionForMime(mime),
                imageExtensionForMime(mime)
            )
        }
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.imageExtension`. */
    private fun originalImageExtension(name: String): String {
        val extension = name.substringAfterLast('.', "png").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "heic", "heif" -> extension
            else -> "png"
        }
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.imageExtensionForMime`. */
    private fun originalImageExtensionForMime(mimeType: String): String =
        when (mimeType.lowercase().substringBefore(";").trim()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> "png"
        }
}
