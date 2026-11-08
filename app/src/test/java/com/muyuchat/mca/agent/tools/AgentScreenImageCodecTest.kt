package com.muyuchat.mca.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the screen tool's visual fallback conversion layer.
 *
 * Only the platform-free decisions are unit-tested here: the aspect-preserving target size and the
 * up-front rejection rules of [encodeScreenshotForModel]. The decode/compress half needs a real
 * `BitmapFactory`, which this module's JVM tests do not have — the project has no Robolectric
 * dependency and `unitTests.isReturnDefaultValues = true` makes every `android.*` call return a
 * default instead of throwing, so asserting on a "successful" encode here would be a fake green.
 * Those paths are exercised by the on-device screen tests instead.
 */
class AgentScreenImageCodecTest {

    @Test
    fun `target size preserves aspect ratio without enlarging`() {
        assertEquals(1024 to 768, targetSize(2048, 1536, 1024))
        assertEquals(768 to 1024, targetSize(1536, 2048, 1024))
        assertEquals(1024 to 1024, targetSize(4000, 4000, 1024))
    }

    @Test
    fun `an image already within the cap keeps its exact pixels`() {
        assertEquals(1024 to 768, targetSize(1024, 768, 1024))
        assertEquals(300 to 200, targetSize(300, 200, 1024))
        assertEquals(1 to 1, targetSize(1, 1, 1024))
    }

    @Test
    fun `extreme aspect ratios never collapse an axis to zero`() {
        val (wide, tall) = targetSize(20_000, 8, 1024)
        assertEquals(1024, wide)
        assertEquals(1, tall)

        val (narrowWide, narrowTall) = targetSize(8, 20_000, 1024)
        assertEquals(1, narrowWide)
        assertEquals(1024, narrowTall)
    }

    @Test
    fun `non-positive inputs are reported as not encodable`() {
        assertEquals(0 to 0, targetSize(0, 100, 1024))
        assertEquals(0 to 0, targetSize(100, 0, 1024))
        assertEquals(0 to 0, targetSize(-4, 100, 1024))
        assertEquals(0 to 0, targetSize(100, 100, 0))
        assertEquals(0 to 0, targetSize(100, 100, -1))
    }

    @Test
    fun `the longest edge is the one that respects the cap`() {
        listOf(
            2048 to 1536,
            1536 to 2048,
            5000 to 100,
            100 to 5000,
            1025 to 1024
        ).forEach { (width, height) ->
            val (targetWidth, targetHeight) = targetSize(width, height, 1024)
            assertTrue(
                "target ${targetWidth}x$targetHeight for ${width}x$height exceeds the cap",
                maxOf(targetWidth, targetHeight) <= 1024
            )
            assertTrue(
                "target ${targetWidth}x$targetHeight for ${width}x$height lost an axis",
                targetWidth >= 1 && targetHeight >= 1
            )
        }
    }

    @Test
    fun `empty and oversized screenshots are refused before decoding`() {
        assertNull(encodeScreenshotForModel(ByteArray(0)))
        // One byte over the 8 MiB cap: refused from the length check, never handed to BitmapFactory.
        assertNull(encodeScreenshotForModel(ByteArray(MAX_SOURCE_BYTES + 1)))
    }

    @Test
    fun `undecodable bytes return null instead of throwing`() {
        assertNull(encodeScreenshotForModel(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(encodeScreenshotForModel("not an image".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `bounds are the documented defaults`() {
        assertEquals(1024, DEFAULT_SCREENSHOT_MAX_SIDE_PX)
        assertEquals(70, DEFAULT_SCREENSHOT_JPEG_QUALITY)
        assertEquals(8 * 1024 * 1024, MAX_SOURCE_BYTES)
    }
}
