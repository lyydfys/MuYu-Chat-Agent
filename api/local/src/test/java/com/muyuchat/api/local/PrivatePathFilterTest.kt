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
    fun `any url with a scheme counts as private, which is a false positive`() {
        // Measured, not assumed. The drive-letter check is `[A-Za-z]:[\\/]`, and "https://..."
        // contains "s:/", so every http(s) URL is classified as a private path. Both original
        // copies behaved this way; the duplication did not cause it and unifying did not change
        // it.
        //
        // This matters because one caller rejects the whole image provider response on a match
        // (private_image_input_path_exposed in ImageGenerationApiContract), so a URL in that
        // payload would fail the response rather than merely having a field stripped. Whether
        // that is reachable in practice needs a real worker response, which this environment
        // cannot produce.
        //
        // Pinned rather than fixed: narrowing the pattern is a behaviour change and the plan
        // requires those to be separate, analysed tasks. Recording it here means the next
        // person sees the false positive is known, and a test failure tells them they changed
        // behaviour that the current code depends on.
        assertTrue("https://example.com/image.png".looksLikePrivatePath())
        assertTrue("http://example.com/a".looksLikePrivatePath())
    }

    @Test
    fun `private directory segments match anywhere, including inside a url`() {
        // This is a real false positive in both original copies and it is preserved on purpose:
        // tightening it here would be a behaviour change in a refactor, and being over-eager
        // costs a dropped display field rather than a leaked path. Recorded so that anyone who
        // later decides to narrow the rule sees it was deliberate and knows what to weigh.
        assertTrue("https://example.com/data/x.png".looksLikePrivatePath())
        assertTrue("prefix/storage/suffix".looksLikePrivatePath())
    }

    @Test
    fun `surrounding whitespace does not change the answer`() {
        assertTrue("  /data/x.png  ".looksLikePrivatePath())
        assertFalse("  x.png  ".looksLikePrivatePath())
    }
}
