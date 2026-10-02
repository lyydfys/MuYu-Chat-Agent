package com.muyuchat.api.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the leak filter now that history and the API share one copy of it.
 *
 * The two previous copies were body-for-body identical, so this does not cover behaviour that
 * was untested before. It exists because a filter that decides whether a path may be echoed
 * into stored history or into an API response is exactly the kind of rule that must not drift,
 * and because pinning the false positives matters as much as pinning the true positives: a
 * filter that starts rejecting ordinary values would silently strip fields a client expects.
 *
 * The http(s) false positive that earlier revisions of this file recorded as pinned-but-not-fixed
 * has since been fixed, so the assertions that documented it now assert the corrected behaviour.
 * The drive-letter pattern requires a non-alphanumeric before the letter and a single separator
 * after the colon; the tests below pin both boundaries in each direction, so a future attempt to
 * loosen the pattern fails here rather than in production.
 */
class PrivatePathFilterTest {

    @Test
    fun `filesystem-looking values are rejected`() {
        listOf(
            "/data/user/0/com.muyuchat.mca/files/x.png",
            "/storage/emulated/0/DCIM/x.png",
            "/sdcard/x.png",
            "/mnt/media/x.png",
            "/cache/x.png",
            "/tmp/x.png",
            "\\\\server\\share\\x.png",
            "C:\\Users\\x.png",
            "C:/Users/x.png"
        ).forEach { assertTrue("$it should be treated as private", it.looksLikePrivatePath()) }
    }

    @Test
    fun `resolver and file uris are rejected regardless of case`() {
        listOf(
            "file:///data/x.png",
            "FILE:///data/x.png",
            "content://media/external/images/1",
            "Content://media/external/images/1",
            "android.resource://com.muyuchat.mca/drawable/x"
        ).forEach { assertTrue("$it should be treated as private", it.looksLikePrivatePath()) }
    }

    @Test
    fun `ordinary values are kept`() {
        listOf(
            "x.png",
            "Qwen-Image-2.1",
            "SM8550",
            ""
        ).forEach { assertFalse("$it should not be treated as private", it.looksLikePrivatePath()) }
    }

    @Test
    fun `a url with a scheme is not a private path`() {
        // This was a real false positive and it is fixed here. The drive-letter check used to be
        // the bare `[A-Za-z]:[\\/]`, which matches the "s:/" inside "https://" and the "p:/"
        // inside "http://", so every http(s) URL was classified as a private path. It mattered
        // beyond a dropped field: one caller rejects the whole image provider response when this
        // returns true (private_image_input_path_exposed in ImageGenerationApiContract).
        //
        // The pattern now requires a non-alphanumeric before the letter, which is what a URI
        // scheme cannot supply -- its colon is preceded by a letter.
        assertFalse("https://example.com/image.png".looksLikePrivatePath())
        assertFalse("http://example.com/a".looksLikePrivatePath())
        assertFalse("ftp://host/x".looksLikePrivatePath())
        assertFalse("sms://12345".looksLikePrivatePath())
        // A single-letter scheme has no preceding letter to reject it, so the second boundary is
        // what stops it: two separators after the colon mean a URI, one means a drive path.
        assertFalse("a://host".looksLikePrivatePath())
    }

    @Test
    fun `a drive path is still rejected in every position a real one appears`() {
        // The other direction of the same fix. Loosening the pattern must not have made the
        // filter blind to drive paths; each of these places a real path where one actually
        // occurs in a stored value or a response payload.
        listOf(
            "C:\\Users\\x.png",
            "C:/Users/x.png",
            "C:\\",
            "  C:\\x.png  ",
            "path=C:\\x.png",
            "a C:\\b",
            "\"C:/x.png\"",
            "[C:\\x.png]",
            "src:C:\\x.png"
        ).forEach { assertTrue("$it should be treated as private", it.looksLikePrivatePath()) }
    }

    @Test
    fun `private directory segments match anywhere, including inside a url`() {
        // This one is left deliberately over-eager. Matching "/storage/" anywhere also matches
        // inside a URL, and tightening it is a separate behaviour change from the drive-letter
        // fix. Being over-eager costs a dropped display field rather than a leaked path, so the
        // asymmetry argues for leaving it until there is a reason to move it.
        assertTrue("https://example.com/data/x.png".looksLikePrivatePath())
        assertTrue("prefix/storage/suffix".looksLikePrivatePath())
    }

    @Test
    fun `surrounding whitespace does not change the answer`() {
        assertTrue("  /data/x.png  ".looksLikePrivatePath())
        assertFalse("  x.png  ".looksLikePrivatePath())
    }
}
